package com.malacca.guide.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

private const val TAG = "TtsManager"
private const val UTTERANCE_ID = "heycyan_tts"

class TtsManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: String? = null
    private var pendingLang: String? = null
    private var onSpeechDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            isReady = status == TextToSpeech.SUCCESS
            Log.d(TAG, "init ready=$isReady")
            if (isReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onSpeechDone?.invoke()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onSpeechDone?.invoke()
                    }
                })
                val t = pendingText
                val l = pendingLang
                if (t != null) {
                    speakInternal(t, l ?: "EN")
                    pendingText = null
                    pendingLang = null
                }
            }
        }
    }

    /**
     * @param onDone invoked when playback finishes. Only the most recent call's
     *   callback is retained, matching the QUEUE_FLUSH behaviour below.
     */
    fun speak(text: String, languageCode: String, onDone: (() -> Unit)? = null) {
        onSpeechDone = onDone
        if (isReady) {
            speakInternal(text, languageCode)
        } else {
            pendingText = text
            pendingLang = languageCode
        }
    }

    private fun speakInternal(text: String, languageCode: String) {
        val locale = when (languageCode) {
            "MS" -> Locale("ms", "MY")
            else -> Locale.ENGLISH
        }
        tts?.language = locale

        // Route through the glasses when their headset is pinned as the
        // communication device; voice-call usage follows that route, media
        // usage would fall back to the phone speaker.
        val streamType = GlassesAudioRouter.ttsStreamType()
        val usage = if (GlassesAudioRouter.routed.value) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, streamType)
        }
        Log.d(TAG, "speak routed=${GlassesAudioRouter.routed.value} stream=$streamType len=${text.length}")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
    }

    fun stop() {
        onSpeechDone = null
        tts?.stop()
    }

    fun shutdown() {
        onSpeechDone = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
