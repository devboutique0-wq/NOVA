package com.nova.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Non-secret settings. The API key lives in SecureStore, never here. */
class Cfg(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("nova", Context.MODE_PRIVATE)

    var wake: String
        get() = Logic.wakeOrDefault(p.getString("wake", null))
        set(v) { p.edit().putString("wake", v).apply() }

    var lang: String
        get() = if (p.getString("lang", "hi") == "en") "en" else "hi"
        set(v) { p.edit().putString("lang", v).apply() }

    var rate: Float
        get() = Logic.clampRate(p.getFloat("rate", 1.0f))
        set(v) { p.edit().putFloat("rate", v).apply() }

    var pitch: Float
        get() = Logic.clampPitch(p.getFloat("pitch", 1.0f))
        set(v) { p.edit().putFloat("pitch", v).apply() }

    var model: String
        get() = Logic.cleanModel(p.getString("model", null))
        set(v) { p.edit().putString("model", v).apply() }

    /** After a restart, remind/start NOVA if it was ON before. */
    var autostart: Boolean
        get() = p.getBoolean("autostart", true)
        set(v) { p.edit().putBoolean("autostart", v).apply() }

    /** True while the user has NOVA switched ON (set by ACTIVATE, cleared by STOP). */
    var active: Boolean
        get() = p.getBoolean("active", false)
        set(v) { p.edit().putBoolean("active", v).apply() }

    /** Explicit opt-in (default OFF) for LOCAL screen monitoring of error/warning text. Never uploads anything. */
    var screenMonitor: Boolean
        get() = p.getBoolean("screenMonitor", false)
        set(v) { p.edit().putBoolean("screenMonitor", v).apply() }

    fun locale(): Locale = if (lang == "en") Locale("en", "IN") else Locale("hi", "IN")

    fun applyVoice(t: TextToSpeech?) {
        if (t == null) return
        val r = t.setLanguage(locale())
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.US)
        }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
    }
}
