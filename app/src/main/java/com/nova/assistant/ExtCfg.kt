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
}
