package com.bharatgps.drishti

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Text-to-speech + vibration helper. Keeps speech short and never builds a backlog. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: String? = null

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.getDefault())
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            tts.setSpeechRate(1.1f)
            ready = true
            pending?.let { say(it, true) }
            pending = null
        }
    }

    val isSpeaking: Boolean get() = ready && tts.isSpeaking

    /**
     * urgent = interrupt whatever is being said.
     * Non-urgent messages are dropped if something is already being spoken.
     */
    fun say(text: String, urgent: Boolean = false): Boolean {
        if (!ready) { pending = text; return true }
        if (!urgent && tts.isSpeaking) return false
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "drishti-${System.nanoTime()}")
        return true
    }

    fun stop() { if (ready) tts.stop() }

    fun buzz(ms: Long = 120, strength: Int = 255) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(ms, strength.coerceIn(1, 255)))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}
