package com.malacca.guide.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.malacca.guide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Records the tourist's question straight from the glasses microphone.
 *
 * Replaces Android's [android.speech.SpeechRecognizer], which decides on its own
 * when speech has ended and which microphone to use. Over the glasses' Bluetooth
 * SCO link that went wrong two ways: it endpointed after 0.79s, and it repeatedly
 * ignored the pinned route and opened the phone's built-in mic instead. Owning
 * the capture removes both — [AudioRecord.setPreferredDevice] names the device
 * directly, and the stop condition is ours.
 *
 * The audio is returned as a WAV so it can be posted to the backend, where Gemini
 * transcribes it.
 */
object VoiceRecorder {

    private const val TAG = "VoiceRecorder"

    private const val SAMPLE_RATE = 16_000
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    /** Hard ceiling so a stuck recording can't run forever. */
    private const val MAX_DURATION_MS = 12_000L
    /** Stop once the wearer has been quiet this long after speaking. */
    private const val TRAILING_SILENCE_MS = 1_400L
    /** Give up if they never start speaking at all. */
    private const val NO_SPEECH_TIMEOUT_MS = 6_000L
    /** Ignore the first moments, where the SCO link is still settling. */
    private const val NOISE_FLOOR_SAMPLE_MS = 400L

    /**
     * Absolute floor for what counts as speech. Kept low on purpose: detection is
     * driven mainly by the ratio to the measured noise floor, because speech level
     * over this link varies enormously — one recording peaked at 27000, another at
     * 1946 for the same speaker. A high fixed floor (1200 was tried) silently
     * discarded the quieter one.
     *
     * The cue tone is no longer a concern here; it finishes before recording starts.
     */
    private const val MIN_SPEECH_LEVEL = 150.0

    /** Consecutive frames above the threshold before speech is declared. */
    private const val SPEECH_CONFIRM_FRAMES = 3

    data class Result(
        val wav: ByteArray,
        val durationMs: Long,
        val peakAmplitude: Int,
        val heardSpeech: Boolean,
    )

    /**
     * Records until the wearer stops talking, or the limits above are hit.
     * Returns null if the recorder could not be opened.
     *
     * @param preferredDevice microphone to record from; null uses the default.
     * @param noSpeechTimeoutMs how long to wait for the wearer to start speaking.
     *   Shorter for the follow-up window, where silence is the normal outcome and
     *   holding the microphone open is intrusive.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(
        context: Context,
        preferredDevice: AudioDeviceInfo?,
        noSpeechTimeoutMs: Long = NO_SPEECH_TIMEOUT_MS,
    ): Result? = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "record: invalid min buffer size $minBuffer")
            return@withContext null
        }
        val bufferSize = minBuffer * 2

        val recorder = try {
            AudioRecord(
                // VOICE_RECOGNITION avoids the aggressive AGC/noise suppression
                // that VOICE_COMMUNICATION applies, which mangles speech.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "record: could not construct AudioRecord: ${e.message}")
            return@withContext null
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "record: AudioRecord not initialised (state=${recorder.state})")
            recorder.release()
            return@withContext null
        }

        if (preferredDevice != null) {
            val ok = recorder.setPreferredDevice(preferredDevice)
            Log.d(
                TAG,
                "record: setPreferredDevice(${preferredDevice.productName}/${preferredDevice.type}) -> $ok"
            )
        }

        val pcm = ByteArrayOutputStream()
        val buffer = ShortArray(bufferSize / 2)
        var peak = 0
        var heardSpeech = false
        var noiseFloor = 0.0
        var noiseFloorFrames = 0
        var loudFrames = 0
        var lastSpeechAt = 0L
        val startedAt = System.currentTimeMillis()

        try {
            recorder.startRecording()
            Log.d(TAG, "record: started, actual source device=${recorder.routedDevice?.productName}")

            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    Log.w(TAG, "record: read returned $read, stopping")
                    break
                }

                var sum = 0.0
                var framePeak = 0
                for (i in 0 until read) {
                    val amplitude = abs(buffer[i].toInt())
                    sum += amplitude
                    if (amplitude > framePeak) framePeak = amplitude
                }
                val mean = sum / read
                if (framePeak > peak) peak = framePeak

                // Write every frame, including the noise-floor window — trimming
                // it risks clipping the start of the question.
                val bytes = ByteArray(read * 2)
                for (i in 0 until read) {
                    val v = buffer[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                pcm.write(bytes)

                val elapsed = System.currentTimeMillis() - startedAt

                // Calibrate against the room rather than a fixed threshold, so
                // this works both in a quiet office and on Jonker Street.
                if (elapsed < NOISE_FLOOR_SAMPLE_MS) {
                    noiseFloor += mean
                    noiseFloorFrames++
                    continue
                }
                val threshold = if (noiseFloorFrames > 0) {
                    ((noiseFloor / noiseFloorFrames) * 2.5).coerceAtLeast(MIN_SPEECH_LEVEL)
                } else {
                    MIN_SPEECH_LEVEL
                }

                // Require the level to hold up across consecutive frames. A single
                // loud frame is a click, a door, or the tail of our own cue tone;
                // speech sustains.
                if (mean > threshold) {
                    loudFrames++
                    if (loudFrames >= SPEECH_CONFIRM_FRAMES) {
                        if (!heardSpeech) {
                            Log.d(TAG, "record: speech started at ${elapsed}ms (mean=${mean.toInt()} threshold=${threshold.toInt()})")
                        }
                        heardSpeech = true
                        lastSpeechAt = elapsed
                    }
                } else {
                    loudFrames = 0
                }

                if (heardSpeech && elapsed - lastSpeechAt > TRAILING_SILENCE_MS) {
                    Log.d(TAG, "record: trailing silence, stopping at ${elapsed}ms")
                    break
                }
                if (!heardSpeech && elapsed > noSpeechTimeoutMs) {
                    Log.d(TAG, "record: no speech within ${noSpeechTimeoutMs}ms, stopping")
                    break
                }
                if (elapsed > MAX_DURATION_MS) {
                    Log.d(TAG, "record: hit max duration")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "record: failed: ${e.message}", e)
            return@withContext null
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        val pcmBytes = pcm.toByteArray()
        val durationMs = pcmBytes.size * 1000L / (SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
        Log.d(
            TAG,
            "record: done ${pcmBytes.size} bytes (${durationMs}ms) peak=$peak heardSpeech=$heardSpeech"
        )
        if (pcmBytes.isEmpty()) return@withContext null

        val wav = wrapAsWav(pcmBytes)
        saveDebugCopy(context, wav)
        Result(wav, durationMs, peak, heardSpeech)
    }

    /** Minimal 44-byte RIFF/WAVE header around raw little-endian PCM. */
    private fun wrapAsWav(pcm: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val out = ByteArrayOutputStream(44 + pcm.size)

        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun int32(v: Int) = out.write(
            byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte()
            )
        )
        fun int16(v: Int) = out.write(
            byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        )

        ascii("RIFF"); int32(36 + pcm.size); ascii("WAVE")
        ascii("fmt "); int32(16); int16(1); int16(CHANNELS)
        int32(SAMPLE_RATE); int32(byteRate)
        int16(CHANNELS * BITS_PER_SAMPLE / 8); int16(BITS_PER_SAMPLE)
        ascii("data"); int32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * Debug builds only: keeps the last recording so it can be pulled off the
     * device and listened to. Whether the SCO audio is intelligible at all is
     * otherwise indistinguishable from the transcriber being poor.
     */
    private fun saveDebugCopy(context: Context, wav: ByteArray) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = context.getExternalFilesDir("captures") ?: return
            val file = File(dir, "last_question.wav")
            file.writeBytes(wav)
            Log.d(TAG, "saveDebugCopy: wrote ${file.absolutePath}")
        }.onFailure { Log.w(TAG, "saveDebugCopy failed: ${it.message}") }
    }
}
