package com.malacca.guide.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Short audible cues played through whichever speaker the app is using.
 *
 * The wearer is looking at a landmark, not at the phone, so a change on screen
 * tells them nothing. When the app starts listening for a follow-up question it
 * has to say so out loud, otherwise the first words are spoken before the
 * microphone is open and get lost.
 */
object Earcon {

    private const val TAG = "Earcon"
    // Long and loud enough to be noticed outdoors over traffic, while still
    // short enough not to delay the follow-up window.
    private const val BEEP_MS = 220
    private const val VOLUME = 90

    /** Rising note meaning "I'm listening now". */
    fun playListening() {
        play(ToneGenerator.TONE_PROP_BEEP)
    }

    private fun play(tone: Int) {
        runCatching {
            // Follows the same stream as speech, so it comes out of the glasses
            // when they are routed and the phone otherwise.
            val stream = GlassesAudioRouter.ttsStreamType()
            val generator = ToneGenerator(stream, VOLUME)
            generator.startTone(tone, BEEP_MS)
            // ToneGenerator holds an audio track; release it once the tone ends.
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { generator.release() } },
                (BEEP_MS + 250).toLong()
            )
            Log.d(TAG, "playListening on stream=$stream")
        }.onFailure { Log.w(TAG, "tone failed: ${it.message}") }
    }
}
