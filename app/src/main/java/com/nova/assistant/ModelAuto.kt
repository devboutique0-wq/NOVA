package com.nova.assistant

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v17 model downloads.
 *  - Voice-check model (about 14 MB): downloaded by itself once the mic service runs (so the permissions are given),
 *    at most one attempt every 6 hours, only when the owner left "auto" ON. Also available as a manual button.
 *  - Qwen offline brain (491 MB): NEVER downloaded automatically. In this build the offline engine is switched off
 *    (LocalBrains.factory == null), so a download could not be used. The manual button says so.
 * Evidence level: written + statically checked. Not compiled here, not phone tested.
 */
object ModelAuto {
    private const val PREF = "nova_ext"
    private const val LAST = "auto_model_last"
    private const val GAP_MS = 6L * 60 * 60 * 1000
    private val running = AtomicBoolean(false)

    private fun say(t: String) { try { NovaService.listener?.invoke("ai", t) } catch (e: Exception) { } }

    /** Called when the mic service starts. Silent unless it actually has something to download. */
    fun autoStart(ctx: Context) {
        if (!ExtCfg.autoModels(ctx) || VoiceLock.modelReady(ctx)) return
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong(LAST, 0L) < GAP_MS) return
        p.edit().putLong(LAST, now).apply()
        runVoice(app, false)
    }

    /** Settings button. "ok" started, "ready" already there, "busy" already running. */
    fun manualVoice(ctx: Context): String {
        val app = ctx.applicationContext
        if (VoiceLock.modelReady(app)) return "ready"
        if (running.get()) return "busy"
        runVoice(app, true)
        return "ok"
    }

    private fun runVoice(app: Context, manual: Boolean) {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                say("Awaaz-pehchaan ka chhota model download ho raha hai (lagbhag 14 MB). Dobara mat dabao.")
                val err = VoiceLock.downloadModel(app)
                if (err == null) say("Awaaz model download ho gaya. Ab Settings > MERI AWAAZ me AWAAZ SAVE KARO dabao.")
                else if (err != "busy") say("Awaaz model download nahi hua ($err). Internet check karke Settings me MODEL DOWNLOAD dabao." + if (manual) "" else " Main 6 ghante baad phir koshish karunga.")
            } finally {
                running.set(false)
            }
        }, "nova-model-auto").start()
    }
}
