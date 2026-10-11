package com.nova.assistant

/**
 * Part 2: a small in-memory list of the last things that FAILED (a phone command, a Gemini call, the whole AI chain).
 * It lives only while the app process lives (nothing is written to disk). It stores a short reason, never audio and never a key:
 * any long token-like string is replaced by *** before it is kept. The owner copies it from Settings and pastes it into a chat.
 * Pure Kotlin, unit tested.
 */
object ErrLog {
    const val MAX = 30
    const val MAX_LEN = 160

    private class E(val at: Long, val src: String, val msg: String)

    private val items = ArrayList<E>()
    private val TOKEN = Regex("[A-Za-z0-9_\\-]{30,}")

    /** One line, no key-like tokens, at most MAX_LEN characters (plus "..." when cut). */
    fun clean(raw: String): String {
        val oneLine = raw.replace('\n', ' ').replace('\r', ' ').trim()
        val masked = oneLine.replace(TOKEN, "***")
        return if (masked.length > MAX_LEN) masked.substring(0, MAX_LEN) + "..." else masked
    }

    fun add(src: String, msg: String, now: Long = System.currentTimeMillis()) {
        val m = clean(msg)
        if (m.isEmpty()) return
        synchronized(items) {
            items.add(E(now, clean(src).take(30), m))
            while (items.size > MAX) items.removeAt(0)
        }
    }

    fun count(): Int = synchronized(items) { items.size }

    fun clear() {
        synchronized(items) { items.clear() }
    }

    fun ago(now: Long, at: Long): String {
        val s = ((now - at) / 1000L).coerceAtLeast(0L)
        return when {
            s < 60L -> "${s}s ago"
            s < 3600L -> "${s / 60L}m ago"
            else -> "${s / 3600L}h ago"
        }
    }

    /** Newest first. */
    fun dump(now: Long = System.currentTimeMillis()): String {
        val copy: List<E> = synchronized(items) { ArrayList(items) }
        if (copy.isEmpty()) return "RECENT ERRORS: none"
        val sb = StringBuilder()
        sb.append("RECENT ERRORS (newest first, since the app started)\n")
        for (e in copy.asReversed()) {
            sb.append("- ").append(ago(now, e.at)).append(" | ").append(e.src).append(" | ").append(e.msg).append('\n')
        }
        return sb.toString()
    }
}
