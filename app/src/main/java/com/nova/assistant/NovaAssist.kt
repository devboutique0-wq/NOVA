package com.nova.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Lets the user pick NOVA as the phone's default assistant (Settings > Default digital assistant app).
 * Then a long-press of Home / the power button wakes NOVA like the wake word does.
 * NOVA does NOT read the screen through this door: onHandleAssist / screenshots are not used.
 */
class NovaInteractionService : VoiceInteractionService()

class NovaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NovaSession(this)
}

class NovaSession(private val svc: NovaSessionService) : VoiceInteractionSession(svc) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            val s = NovaService.instance
            if (s != null && NovaService.running) {
                s.wakeNow()                       // same as saying the wake word
            } else {
                svc.startActivity(
                    Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: Exception) {
            // never crash the assistant slot
        }
        hide()
    }
}

/** Android lists an assistant only if it names a recognition service. NOVA listens through Vosk itself, so this one just says "not available". */
class NovaRecognitionStub : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (e: Exception) { }
    }
    override fun onCancel(listener: Callback?) { }
    override fun onStopListening(listener: Callback?) { }
}
