package com.nova.assistant

/**
 * Part 3: SILENT TURN. The owner says an order and adds "chup rehna" / "bina bole" / "don't speak": NOVA does the job with NO speech,
 * shows only a short message on the card, and goes quiet again. It lasts for that one order only (it is not the permanent quiet mode).
 * Pure Kotlin (no Android classes), unit tested.
 *
 * Safety rules that live here:
 *  - a waiting yes/no question is ALWAYS spoken (call / message / payment-style actions keep their spoken yes);
 *  - a failure is never replaced by a happy message: the real failure text is shown;
 *  - "Kaam complete ho gaya" is shown only when every action that ran is one whose success the phone really confirms
 *    (torch, volume, brightness). For everything else the real answer of the tool is shown, so nothing is overclaimed;
 *  - the silent phrase is only recognised at the START or END of the sentence, and when the sentence looks like it carries a message
 *    text ("... ki tum ...") it must be separated by a comma / "aur" / "and", so a message is never cut by accident.
 */
object SilentRules {
    class Parsed(val text: String, val silent: Boolean, val fillerOnly: Boolean)

    class Out(val speak: Boolean, val show: String)

    private const val P = "(?:" +
        "chup\\s*chap|chup\\s+(?:rehna|rehana|rehne|rahna|raho|rahiye|rehkar|rahkar)|khamosh\\s+(?:rehna|raho)|shant\\s+(?:rehna|raho)|" +
        "bina\\s+(?:bole|bolke|bol\\s+ke|awaaz|awaz)|bolna\\s+mat|mat\\s+bolna|bolo\\s+mat|kuch\\s+mat\\s+bolna|" +
        "awaa?z\\s+mat\\s+(?:karna|nikalna|karo)|awaa?z\\s+band\\s+(?:rakhna|rakho)|voice\\s+mat\\s+(?:dena|karna)|" +
        "silently|quietly|silent\\s+(?:rehna|raho|rahna)|stay\\s+(?:quiet|silent)|be\\s+(?:quiet|silent)|keep\\s+quiet|" +
        "don'?t\\s+(?:speak|talk|say\\s+anything)|do\\s+not\\s+(?:speak|talk)|without\\s+(?:speaking|talking|voice)|no\\s+voice|" +
        "चुप\\s*(?:चाप|रहना|रहो|रहने)|बिना\\s*बोले|बोलना\\s*मत|मत\\s*बोलना" +
        ")"
    private const val POLITE = "(?:\\s+(?:please|plz|ok|okay|hai|ho|na|yaar|bhai|nova|ji))*"
    private const val CONN = "(?:aur|and|par|bas|lekin|but|please|plz)"

    /** Phrase at the END, with an explicit separator (comma, full stop, "aur", "and"...) in front. */
    private val TAIL_SEP = Regex("(?:[,.;!]+\\s*|\\s+" + CONN + "\\s+)" + P + POLITE + "\\s*[.!]?\\s*$", RegexOption.IGNORE_CASE)

    /** Phrase at the END with only a space in front (used when the sentence cannot be a message text). */
    private val TAIL_BARE = Regex("(?:^|\\s)" + P + POLITE + "\\s*[.!]?\\s*$", RegexOption.IGNORE_CASE)

    /** Phrase at the START. */
    private val HEAD = Regex("^\\s*(?:" + CONN + "\\s+)?" + P + POLITE + "(?:\\s*,\\s*|\\s+(?:aur|and|par|bas|lekin|but)\\s+|\\s+)", RegexOption.IGNORE_CASE)

    /** Words that show the sentence carries a message / text to be sent or typed. */
    private val MESSAGEY = setOf(
        "whatsapp", "whatsap", "message", "msg", "sms", "text", "bhejo", "bhej", "send", "likho", "likh", "type", "mail", "email",
        "ki", "keh", "kehdo", "kaho", "bol", "bolo", "say", "tell", "reply"
    )

    private val FILLER = setOf(
        "ye", "yeh", "is", "iska", "isko", "kaam", "pura", "poora", "karo", "kar", "do", "de", "dena", "khatam", "complete", "finish",
        "it", "this", "task", "job", "the", "please", "plz", "nova", "ab", "abhi", "jaldi", "wo", "woh", "usko", "sab", "sara", "baki",
        "baaki", "jo", "chal", "raha", "rha", "hai", "hua", "ho", "wala", "chalu", "rakho", "bhi", "to", "bas", "ok", "okay", "ji",
        "bhai", "yaar", "na", "aur", "and", "done", "up", "off"
    )
    private val FILLER_KEY = setOf("kaam", "task", "job", "ye", "yeh", "iska", "isko", "it", "this", "pura", "poora", "complete", "finish", "khatam")

    private val SHOW_DONE_KINDS = setOf(
        "torch_on", "torch_off", "torch", "volume", "brightness_set", "brightness_step", "brightness"
    )

    private fun words(s: String): List<String> =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun looksLikeMessage(s: String): Boolean = words(s).any { it in MESSAGEY }

    private fun tidy(s: String): String = s.replace(Regex("^[\\s,.;!\\-]+|[\\s,.;!\\-]+$"), "")

    /** True when what is left of the sentence is only "ye kaam pura karo" style words: no real order, it talks about a job already running. */
    fun isFillerOnly(rest: String): Boolean {
        val w = words(rest)
        if (w.isEmpty()) return false
        return w.all { it in FILLER } && w.any { it in FILLER_KEY }
    }

    /**
     * Cuts the silent phrase out of the sentence. If there is none, or nothing is left, or the sentence could carry a message text and
     * the phrase has no clear separator, the sentence comes back unchanged with silent = false (a bare "chup" keeps its old meaning: stop).
     */
    fun extract(raw: String): Parsed {
        val none = Parsed(raw, false, false)
        if (raw.isBlank()) return none
        var rest: String? = null
        val sep = TAIL_SEP.find(raw)
        if (sep != null) {
            rest = raw.substring(0, sep.range.first)
        } else {
            val bare = TAIL_BARE.find(raw)
            if (bare != null) {
                val before = raw.substring(0, bare.range.first)
                if (!looksLikeMessage(before)) rest = before
            } else {
                val head = HEAD.find(raw)
                if (head != null) {
                    val after = raw.substring(head.range.last + 1)
                    if (!looksLikeMessage(after)) rest = after
                }
            }
        }
        if (rest == null) return none
        val t = tidy(rest)
        if (t.isBlank()) return none
        return Parsed(t, true, isFillerOnly(t))
    }

    /** One action kind of the pending turn ran fine. Only some kinds are confirmed by the phone itself. */
    fun confirmedKind(kind: String): Boolean = kind in SHOW_DONE_KINDS

    /**
     * What a SILENT turn does with its reply. [okKinds] are the action kinds that ran and returned ok in this turn.
     * A pending yes/no is spoken and shown. A failure shows the real text. "done" is shown only for confirmed kinds.
     */
    fun present(confirmPending: Boolean, failed: Boolean, okKinds: List<String>, reply: String, done: String): Out {
        if (confirmPending) return Out(true, reply)
        if (failed) return Out(false, reply)
        if (okKinds.isNotEmpty() && okKinds.all { confirmedKind(it) }) return Out(false, done)
        return Out(false, reply)
    }

    /** A silent request is only valid for the turn it was said in, or for the spoken yes that answers its own question. */
    fun carryValid(carryAt: Long, now: Long, ttlMs: Long): Boolean = carryAt > 0L && !Logic.isExpired(carryAt, now, ttlMs)
}
