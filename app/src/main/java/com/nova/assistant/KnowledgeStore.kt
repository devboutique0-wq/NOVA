package com.nova.assistant

import android.content.Context

/**
 * Loads assets/knowledge.json once and answers from it (PART 3). Text only, offline, never throws.
 * A missing or broken file simply means "no entries": every question then goes on to the next layer as before.
 */
object KnowledgeStore {
    const val ASSET = "knowledge.json"
    private const val MAX_FILE_BYTES = 600_000

    @Volatile private var cache: List<KnowledgeMatcher.Entry>? = null
    private val lock = Any()

    fun entries(ctx: Context): List<KnowledgeMatcher.Entry> {
        cache?.let { return it }
        return synchronized(lock) {
            val have = cache
            if (have != null) have
            else {
                val loaded = load(ctx)
                cache = loaded
                loaded
            }
        }
    }

    private fun load(ctx: Context): List<KnowledgeMatcher.Entry> {
        return try {
            val bytes = ctx.applicationContext.assets.open(ASSET).use { it.readBytes() }
            if (bytes.size > MAX_FILE_BYTES) emptyList() else KnowledgeMatcher.parse(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Number of loaded entries (for the self test). */
    fun count(ctx: Context): Int = entries(ctx).size

    /** The answer text in the right language, or null = "I do not know this offline". */
    fun answer(ctx: Context, text: String, en: Boolean): String? {
        return try {
            val list = entries(ctx)
            if (list.isEmpty()) return null
            val m = KnowledgeMatcher.match(list, text) ?: return null
            KnowledgeMatcher.reply(m, en)
        } catch (e: Exception) {
            null
        }
    }
}
