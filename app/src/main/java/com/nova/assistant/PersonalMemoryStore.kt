package com.nova.assistant

import android.content.Context

/**
 * Where the personal facts live: one private SharedPreferences file "nova_memory" on this phone only.
 * allowBackup is false in the manifest, so the file is not copied to any cloud backup either.
 * Facts are never logged and never leave the phone from here (see PersonalMemory for the only two ways a few relevant
 * facts reach a model: LocalBrains.chat on the device itself, and the optional online chain system prompt).
 */
object PersonalMemoryStore {
    private const val PREF = "nova_memory"
    private const val KEY = "facts"
    private val lock = Any()

    fun load(ctx: Context): List<String> = synchronized(lock) {
        try {
            PersonalMemory.decode(ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** true = written. commit() so a crash right after "yaad rakh liya" cannot lose it. */
    fun save(ctx: Context, list: List<String>): Boolean = synchronized(lock) {
        try {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, PersonalMemory.encode(list)).commit()
        } catch (e: Exception) {
            false
        }
    }

    fun clear(ctx: Context): Boolean = synchronized(lock) {
        try {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).commit()
        } catch (e: Exception) {
            false
        }
    }

    fun count(ctx: Context): Int = load(ctx).size
}
