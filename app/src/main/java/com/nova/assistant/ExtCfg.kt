package com.nova.assistant

import android.content.Context

/** Extra settings that live in their own small store: how long NOVA waits after you stop speaking. */
object ExtCfg {
    private const val PREF = "nova_ext"
    private const val WAIT = "wait_sec"
    const val DEFAULT_WAIT_SEC = 5

    fun waitSec(ctx: Context): Int = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(WAIT, DEFAULT_WAIT_SEC).coerceIn(2, 10)
    } catch (e: Exception) { DEFAULT_WAIT_SEC }

    fun setWaitSec(ctx: Context, sec: Int) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(WAIT, sec.coerceIn(2, 10)).apply()
        } catch (e: Exception) { }
    }

    fun waitMs(ctx: Context): Int = waitSec(ctx) * 1000

    private const val ALERTS = "alerts_on"

    /** v16 proactive alerts (low battery spoken once per 30 minutes). Default ON. */
    fun alerts(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(ALERTS, true)
    } catch (e: Exception) { true }

    fun setAlerts(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(ALERTS, on).apply()
        } catch (e: Exception) { }
    }

    private const val CONVO = "convo_mode"

    /** v16 conversation mode: after a spoken reply NOVA listens again (up to 3 times in a row) without the wake word. Default ON. */
    fun convo(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(CONVO, true)
    } catch (e: Exception) { true }

    fun setConvo(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(CONVO, on).apply()
        } catch (e: Exception) { }
    }

    private const val AUTO_MODELS = "auto_models"

    /** v17: download the small voice-check model by itself when the mic service starts. Default ON (about 14 MB). */
    fun autoModels(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(AUTO_MODELS, true)
    } catch (e: Exception) { true }

    fun setAutoModels(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(AUTO_MODELS, on).apply()
        } catch (e: Exception) { }
    }

    private const val VOICE_LEVEL = "voice_level"

    /** v17: how strict the voice lock is. 0 STRICT (default), 1 NORMAL, 2 LOOSE. */
    fun voiceLevel(ctx: Context): Int = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(VOICE_LEVEL, 0).coerceIn(0, 2)
    } catch (e: Exception) { 0 }

    fun setVoiceLevel(ctx: Context, level: Int) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(VOICE_LEVEL, level.coerceIn(0, 2)).apply()
        } catch (e: Exception) { }
    }

    // ---- Part 5: voice self-tuning + lock-screen face gate
    private const val VOICE_THR = "voice_threshold"
    private const val VOICE_OWN = "voice_own_dists"
    private const val FACE_GATE = "face_gate"

    /** The limit chosen by the owner's own voice test. 0.0 = not tuned (the STRICT / NORMAL / LOOSE level is used). */
    fun voiceThreshold(ctx: Context): Double = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat(VOICE_THR, 0f).toDouble()
    } catch (e: Exception) { 0.0 }

    fun setVoiceThreshold(ctx: Context, v: Double) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putFloat(VOICE_THR, v.toFloat()).apply()
        } catch (e: Exception) { }
    }

    /** Distances (plain numbers, no audio) of the owner's test sentences, kept so another person's test can be compared later. */
    fun voiceOwnDists(ctx: Context): String = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(VOICE_OWN, "") ?: ""
    } catch (e: Exception) { "" }

    fun setVoiceOwnDists(ctx: Context, s: String) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(VOICE_OWN, s).apply()
        } catch (e: Exception) { }
    }

    /** Locked phone: NOVA acts only when the voice AND the system face / fingerprint check pass. Default OFF. */
    fun faceGate(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(FACE_GATE, false)
    } catch (e: Exception) { false }

    fun setFaceGate(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(FACE_GATE, on).apply()
        } catch (e: Exception) { }
    }

    private const val VOICE_LOCK = "voice_lock"

    /** v17 "only my voice": when ON (and a voiceprint exists) every spoken turn must match the owner. Default OFF until enrolled. */
    fun voiceLock(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(VOICE_LOCK, false)
    } catch (e: Exception) { false }

    fun setVoiceLock(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(VOICE_LOCK, on).apply()
        } catch (e: Exception) { }
    }

    private const val FREE_CHAIN = "free_chain"
    private const val FREE_KEYLESS = "free_keyless"

    /** Public topic-only Wikipedia lookup plus the free online AI chain (Groq / OpenRouter / legacy Pollinations). Default ON. */
    fun freeChain(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(FREE_CHAIN, true)
    } catch (e: Exception) { true }

    fun setFreeChain(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(FREE_CHAIN, on).apply()
        } catch (e: Exception) { }
    }

    private const val MEMORY_IN_CHAIN = "memory_in_chain"

    /** Default ON: at most 3 RELEVANT personal facts may be added to the online free chain's prompt (never to Gemini). */
    fun memoryInChain(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(MEMORY_IN_CHAIN, true)
    } catch (e: Exception) { true }

    fun setMemoryInChain(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(MEMORY_IN_CHAIN, on).apply()
        } catch (e: Exception) { }
    }

    /** Legacy no-key Pollinations attempt. Current provider docs may require a key; keep failures best-effort only. */
    fun freeKeyless(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(FREE_KEYLESS, true)
    } catch (e: Exception) { true }

    fun setFreeKeyless(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(FREE_KEYLESS, on).apply()
        } catch (e: Exception) { }
    }
}
