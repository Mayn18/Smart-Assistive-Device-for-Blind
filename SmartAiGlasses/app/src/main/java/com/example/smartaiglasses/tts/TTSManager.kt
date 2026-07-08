package com.example.smartaiglasses.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

private const val TAG = "TTSManager"

class TTSManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsActive = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {

                val result = tts?.setLanguage(Locale.US)

                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language not supported")
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        ttsActive = true
                    }

                    override fun onDone(id: String?) {
                        ttsActive = false
                        Log.d(TAG, "TTS done")
                    }

                    override fun onError(id: String?) {
                        ttsActive = false
                        Log.e(TAG, "TTS error")
                    }
                })

                ttsReady = true
                Log.d(TAG, "Offline TTS ready")

            } else {
                Log.e(TAG, "TTS init failed")
            }
        }
    }

    fun speak(text: String) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready")
            return
        }

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "utt_${System.currentTimeMillis()}"
        )
    }

    fun isBusy(): Boolean = ttsActive

    fun shutdown() {
        tts?.shutdown()
    }
}