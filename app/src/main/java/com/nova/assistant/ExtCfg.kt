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

    private const val FREE_CHAIN = "free_chain"
    private const val FREE_KEYLESS = "free_keyless"

    /** Free online AI chain (Groq / OpenRouter / Pollinations). Default ON. Only the spoken question is ever sent. */
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

    /** The no-key provider (Pollinations, a public anonymous service). Default ON, can be switched off on its own. */
    fun freeKeyless(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(FREE_KEYLESS, true)
    } catch (e: Exception) { true }

    fun setFreeKeyless(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(FREE_KEYLESS, on).apply()
        } catch (e: Exception) { }
    }
}
