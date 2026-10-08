package com.nova.assistant

/**
 * Driving mode (pure Kotlin, no Android classes, so every rule is unit-testable).
 *
 * While driving mode is ON, NOVA reads new messages from a few messaging apps aloud and can send ONE of a few
 * fixed quick replies ("I am driving...") - but only after the user says a spoken, local "yes".
 *
 * Privacy rules: message text lives only in memory (last 8, cleared when driving mode ends), is never logged,
 * never saved to disk and never sent to the cloud. A message that looks like it holds a code / OTP / password
 * is not read out at all.
 */
object Driving {
    /** On only while the user says so. Never saved: after a restart driving mode is OFF. */
    @Volatile var enabled = false

    const val MAX_MSGS = 8
    const val MAX_TEXT = 160
    const val DEDUPE_MS = 60_000L
    const val READ_BATCH = 3
    const val MAX_DICTATION = 300

    /** Only these apps are ever read. Banks, payment apps, mail and everything else are ignored. */
    val APPS: Map<String, String> = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "com.google.android.apps.messaging" to "Messages",
        "com.samsung.android.messaging" to "Messages"
    )

    data class Msg(
        val key: String,
        val pkg: String,
        val app: String,
        val sender: String,
        val text: String,        // "" when hidden
        val ts: Long,
        val canReply: Boolean,
        val hidden: Boolean      // looked like a code / OTP / password: never spoken
    )

    val inbox = Inbox()

    private val URL_RE = Regex("https?://\\S+|www\\.\\S+")
    private val SECRET_WORD_RE = Regex("\\b(otp|password|passcode|cvv|pin|verification code|one time password)\\b")
    private val CODE_RE = Regex("\\b\\d{4,8}\\b")

    /** One line, no links, no control characters, at most [MAX_TEXT] characters. */
    fun cleanText(s: String): String {
        val noUrl = URL_RE.replace(s, " link ")
        val sb = StringBuilder(noUrl.length)
        for (ch in noUrl) sb.append(if (ch.isISOControl()) ' ' else ch)
        return sb.toString().replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)
    }

    /** True when the text probably holds a one-time code or secret. Better to stay silent than to read it out. */
    fun looksSensitive(raw: String): Boolean {
        val s = raw.lowercase()
        return SECRET_WORD_RE.containsMatchIn(s) || CODE_RE.containsMatchIn(s)
    }

    /** null when the app is not allowed or there is nothing to say. */
    fun makeMsg(key: String, pkg: String, title: String, text: String, ts: Long, canReply: Boolean): Msg? {
        val app = APPS[pkg] ?: return null
        if (text.isBlank()) return null
        val hidden = looksSensitive(title + " " + text)
        val sender = cleanText(title).take(40).ifBlank { "Someone" }
        return Msg(key, pkg, app, sender, if (hidden) "" else cleanText(text), ts, canReply, hidden)
    }

    /** What NOVA says. [hint] adds one short "how to answer" sentence (only for the first message of a drive). */
    fun intro(m: Msg, hinglish: Boolean, hint: Boolean): String {
        if (m.hidden) {
            return if (hinglish) "${m.sender} ka ${m.app} par message aaya, usme code ya password lag raha hai, isliye nahi padha."
            else "A message from ${m.sender} on ${m.app} looks like it has a code or password, so I did not read it."
        }
        val base = if (hinglish) "${m.sender}, ${m.app} par: ${m.text}." else "${m.sender} on ${m.app}: ${m.text}."
        if (!hint || !m.canReply) return base
        return base + if (hinglish) " Jawab dene ke liye bolo reply driving, reply busy, ya reply likho."
        else " To answer, say reply driving, reply busy, or reply write."
    }

    // ------------------------------------------------------------------ quick replies (fixed texts only)

    private val QUICK_WORDS: List<Pair<String, List<String>>> = listOf(
        "driving" to listOf("driving", "drive", "gaadi"),
        "busy" to listOf("busy", "vyast"),
        "ok" to listOf("ok", "okay", "theek", "thik"),
        "thanks" to listOf("thanks", "thank", "shukriya", "dhanyavad"),
        "later" to listOf("later", "baad")
    )

    fun quickId(words: List<String>): String? {
        for (w in words) for ((id, ws) in QUICK_WORDS) if (w in ws) return id
        return null
    }

    /** The exact text that would be sent. The user hears it and must say yes first. */
    fun quickText(id: String, hinglish: Boolean): String? = when (id) {
        "driving" -> if (hinglish) "Main abhi drive kar raha hoon, baad mein baat karta hoon." else "I am driving right now, I will reply later."
        "busy" -> if (hinglish) "Main abhi busy hoon, baad mein baat karte hain." else "I am busy right now, talk to you later."
        "ok" -> if (hinglish) "Theek hai." else "Okay."
        "thanks" -> if (hinglish) "Shukriya!" else "Thank you!"
        "later" -> if (hinglish) "Main thodi der baad baat karta hoon." else "I will get back to you later."
        else -> null
    }

    // ------------------------------------------------------------------ voice commands

    private val READ_PHRASES = setOf(
        "read messages", "read message", "read new messages", "messages padho", "message padho", "new messages", "read it"
    )
    private val CLEAR_PHRASES = setOf("clear messages", "clear message", "messages hatao")
    private val OFF_WORDS = setOf("off", "stop", "band", "bandh", "disable", "end")

    /** Starts a dictated reply: NOVA asks "what shall I send?", records ONE utterance, reads it back, asks yes/no. */
    private val DICTATE_PHRASES = setOf(
        "reply likho", "reply write", "jawab likho", "write reply", "custom reply", "reply dictate", "dictate reply"
    )

    /**
     * The user's own dictated words, made safe to send: no [unk] tokens, no control characters, one line,
     * first letter capital, at most [MAX_DICTATION] characters. null when nothing usable was heard.
     */
    fun cleanDictation(raw: String): String? {
        val sb = StringBuilder(raw.length)
        for (ch in raw) sb.append(if (ch.isISOControl()) ' ' else ch)
        val words = sb.toString().split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase() != "[unk]" }
        val s = words.joinToString(" ").trim().take(MAX_DICTATION).trim()
        if (s.isEmpty()) return null
        return s.substring(0, 1).uppercase() + s.substring(1)
    }

    /** Called from Logic.classify. [n] = normalised sentence, [core] = same without filler words. */
    fun command(n: String, core: String): Logic.Cmd? {
        val t = n.split(" ")
        if (("driving" in t || "drive" in t) && "mode" in t && t.size <= 6) {
            return Logic.Cmd(if (t.any { it in OFF_WORDS }) "drive_off" else "drive_on")
        }
        if (core in READ_PHRASES) return Logic.Cmd("drive_read")
        if (core in CLEAR_PHRASES) return Logic.Cmd("drive_clear")
        if (core in DICTATE_PHRASES) return Logic.Cmd("drive_dictate")
        val m = Regex("^reply (.+)$").find(core)
        if (m != null) {
            val id = quickId(m.groupValues[1].split(" "))
            if (id != null) return Logic.Cmd("drive_reply", id)
        }
        return null
    }

    // ------------------------------------------------------------------ the in-memory inbox

    class Inbox {
        private class Entry(val msg: Msg, var read: Boolean)

        private val items = ArrayList<Entry>()

        /** false = duplicate of a very recent message (apps often repost the same notification). */
        @Synchronized fun offer(m: Msg): Boolean {
            for (e in items) {
                if (e.msg.sender == m.sender && e.msg.text == m.text && e.msg.hidden == m.hidden && m.ts - e.msg.ts in 0..DEDUPE_MS) return false
            }
            items.add(Entry(m, false))
            while (items.size > MAX_MSGS) items.removeAt(0)
            return true
        }

        @Synchronized fun markRead(m: Msg) {
            for (e in items) if (e.msg == m) e.read = true
        }

        /** Up to [READ_BATCH] oldest unread messages, now marked as read. A hidden one is announced as "not read" (see intro). */
        @Synchronized fun unreadBatch(): List<Msg> {
            val out = ArrayList<Msg>()
            for (e in items) {
                if (e.read) continue
                if (out.size >= READ_BATCH) break
                e.read = true
                out.add(e.msg)
            }
            return out
        }

        @Synchronized fun latestReplyable(): Msg? = items.lastOrNull { it.msg.canReply }?.msg

        @Synchronized fun unreadCount(): Int = items.count { !it.read }

        @Synchronized fun size(): Int = items.size

        @Synchronized fun clear() { items.clear() }
    }
}
