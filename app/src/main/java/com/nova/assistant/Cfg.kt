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
        get() = Logic.clampRate(p.getFloat("rate", 0.95f))
        set(v) { p.edit().putFloat("rate", v).apply() }

    var pitch: Float
        get() = Logic.clampPitch(p.getFloat("pitch", 0.9f))
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

    /** Default ON: command replies go to the chat / floating card silently. NOVA speaks only for alerts, confirmations and driving. */
    var quietReplies: Boolean
        get() = p.getBoolean("quietReplies", false)
        set(v) { p.edit().putBoolean("quietReplies", v).apply() }

    /** Default OFF: NOVA never opens the mic by itself to ask "shall I remember this shortcut?" after a command. */
    var nudges: Boolean
        get() = p.getBoolean("nudges", false)
        set(v) { p.edit().putBoolean("nudges", v).apply() }

    /** The update feed the USER chose (https only). Empty = no update checks at all. */
    var feedUrl: String
        get() = p.getString("feedUrl", "") ?: ""
        set(v) { p.edit().putString("feedUrl", v).apply() }

    fun locale(): Locale = if (lang == "en") Locale("en", "IN") else Locale("hi", "IN")

    fun applyVoice(t: TextToSpeech?) {
        if (t == null) return
        val r = t.setLanguage(locale())
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.US)
        }
        pickBestVoice(t)
        t.setSpeechRate(rate)
        t.setPitch(pitch)
    }

    /** v16: of the voices already on the phone for this language, prefer the highest quality one that works offline. */
    private fun pickBestVoice(t: TextToSpeech) {
        try {
            val want = if (lang == "en") "en" else "hi"
            var best: android.speech.tts.Voice? = null
            for (v in t.voices ?: return) {
                if (v.locale.language != want) continue
                if (v.isNetworkConnectionRequired) continue
                val b = best
                if (b == null || v.quality > b.quality) best = v
            }
            val pick = best ?: return
            val cur = t.voice
            if (cur == null || cur.name != pick.name) t.setVoice(pick)
        } catch (e: Exception) { }
    }
}
