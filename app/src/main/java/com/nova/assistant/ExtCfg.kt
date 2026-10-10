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
