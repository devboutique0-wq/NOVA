package com.nova.assistant

/**
 * Pure logic (no Android classes) so it can be unit-tested on every build.
 * Everything that decides "what did the user mean / what should we do next" lives here.
 */
object Logic {
    const val DEFAULT_WAKE = "nova"

    // Gemini model IDs that are currently served. gemini-2.5-* is scheduled for shutdown on
    // 2026-10-16, and unknown IDs only waste a request, so they are not in the rotation.
    const val DEFAULT_MODEL = "gemini-3.5-flash"
    val MODELS = listOf(
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite"
    )

    // ---- timing / bounds ----
    const val END_SILENCE_MS = 600          // silence after speech that ends a command
    const val MAX_COMMAND_MS = 12000        // hard cap for one spoken command
    const val NO_SPEECH_COMMAND_MS = 3000   // give up if nothing is said after the wake word
    const val MAX_ANSWER_MS = 6000          // hard cap for a yes/no answer
    const val NO_SPEECH_ANSWER_MS = 4000
    const val MAX_DICTATION_MS = 15000      // hard cap for one dictated driving reply
    const val NO_SPEECH_DICTATION_MS = 6000 // give up if nothing is said after "bolo, kya jawab bhejna hai"
    const val DICTATION_SILENCE_MS = 1300   // people pause mid-sentence: wait a bit longer than for a command
    const val PENDING_TTL_MS = 30000L       // a confirmation older than this is dead
    const val WAKE_COOLDOWN_MS = 1500L      // after a turn ends, ignore the wake word for a moment (echo, tail of our own voice)
    const val HISTORY_TTL_MS = 120_000L     // cloud chat memory older than this is forgotten, so an old command can never come back
    const val HISTORY_MAX = 6
    const val MAX_TOOL_STEPS = 6
    const val MAX_KEYS = 10
    const val MAX_CALLS = 12                // upper bound of HTTP attempts for ONE request
    const val CLOUD_DEADLINE_MS = 45000L
    const val ALERT_MIN_GAP_MS = 15000L
    const val ALERT_SAME_GAP_MS = 120000L

    /** Wake word: one English word, 3-12 letters. Returns null if not valid. */
    fun cleanWake(raw: String?): String? {
        val w = (raw ?: "").trim().lowercase()
        if (w.length < 3 || w.length > 12) return null
        if (!w.all { it in 'a'..'z' }) return null
        return w
    }

    fun wakeOrDefault(raw: String?): String = cleanWake(raw) ?: DEFAULT_WAKE

    fun wakeGrammar(w: String): String = "[\"$w\", \"hey $w\", \"[unk]\"]"

    fun cleanModel(raw: String?): String = if (raw != null && raw in MODELS) raw else DEFAULT_MODEL

    fun clampRate(v: Float): Float = v.coerceIn(0.6f, 1.6f)
    fun clampPitch(v: Float): Float = v.coerceIn(0.7f, 1.4f)

    private val TEXT_RE = Regex("\"(?:text|partial)\"\\s*:\\s*\"([^\"]*)\"")

    /** Raw spoken text of a Vosk JSON result (wake word included). */
    private fun rawText(json: String): String = TEXT_RE.find(json)?.groupValues?.get(1)?.trim() ?: ""

    private val WAKE_PREFIX = Regex("^(?:(?:the|hey|a)\\s+)?" + DEFAULT_WAKE + "\\b\\s*")

    /** Removes the wake word ("nova", "the nova", "hey nova") from the START of a command, so it is never part of the command. */
    fun stripWake(s: String): String {
        var x = s.trim()
        while (true) {
            val y = WAKE_PREFIX.replace(x, "").trim()
            if (y == x) break
            x = y
        }
        return x
    }

    /** Reads the spoken COMMAND text out of a Vosk JSON result (the leading wake word is removed). */
    fun extractText(json: String): String = stripWake(rawText(json))

    fun heardWake(json: String, wake: String): Boolean =
        rawText(json).split(" ").any { it == wake }

    // ---------------------------------------------------------------- yes / no

    private val YES = setOf("yes", "yeah", "yep", "yup", "okay", "ok", "sure", "haan", "han", "ha", "ji")
    private val NO = setOf(
        "no", "nope", "nah", "cancel", "stop", "dont", "don't", "not", "nahi", "nahin", "mat", "ruko", "wait"
    )

    /**
     * Offline reading of a yes/no answer.
     * false = refusal, true = clearly "yes", null = unclear. Unclear is ALWAYS treated as "no"
     * by the caller, so only a short, purely affirmative answer can ever approve something.
     */
    fun parseAnswer(text: String): Boolean? {
        val w = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() && it != "[unk]" }
        if (w.isEmpty()) return null
        if (w.any { it in NO }) return false
        if (w.size <= 3 && w.all { it in YES }) return true
        return null
    }

    // ---------------------------------------------------------------- noise guard / quiet replies

    private val NOISE_WORDS = setOf("the", "a", "an", "uh", "huh", "hm", "hmm", "um", "ah", "oh", "and", "it", "is", "i", "so", "to", "of")

    /** True when a capture holds no real words (silence, a cough, TV noise turned into "the"). Such a capture is never sent anywhere. */
    fun isJunk(text: String): Boolean {
        val w = norm(text).split(" ").filter { it.isNotBlank() }
        return w.isEmpty() || w.all { it in NOISE_WORDS || it.length <= 2 }
    }

    /** Replies of these commands are always spoken: the user asked to HEAR something (driving messages, screen text, the mode switch). */
    val LOUD_KINDS: Set<String> = setOf(
        "drive_on", "drive_off", "drive_read", "drive_clear", "drive_reply", "drive_dictate", "analyze", "quiet_on", "quiet_off"
    )

    /** false = show the reply in the chat / card only. A waiting yes/no question is ALWAYS spoken (risky actions stay audible). */
    fun speakReply(quiet: Boolean, confirmPending: Boolean, kind: String): Boolean =
        !quiet || confirmPending || kind in LOUD_KINDS

    fun isExpired(createdAt: Long, now: Long, ttlMs: Long): Boolean = now < createdAt || now - createdAt > ttlMs

    // ---------------------------------------------------------------- messages / contacts

    fun normalizeMsg(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")

    fun sameMessage(a: String, b: String): Boolean = normalizeMsg(a) == normalizeMsg(b)

    /** Digits for a wa.me link. 10 digit numbers are treated as Indian (+91). */
    fun waNumber(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return when {
            d.length == 10 -> "91$d"
            d.length == 11 && d.startsWith("0") -> "91" + d.substring(1)
            else -> d
        }
    }

    fun shorten(s: String, max: Int = 160): String =
        if (s.length <= max) s else s.substring(0, max).trimEnd() + "..."

    /** Picks the best contact for a spoken name. Returns index or -1. */
    fun pickContact(query: String, names: List<String>): Int {
        if (names.isEmpty()) return -1
        val q = query.trim().lowercase()
        val exact = names.indexOfFirst { it.trim().lowercase() == q }
        if (exact >= 0) return exact
        val word = names.indices.filter { i -> names[i].lowercase().split(" ", "-").contains(q) }
        if (word.isNotEmpty()) return word.minByOrNull { names[it].length } ?: -1
        return names.indices.minByOrNull { names[it].length } ?: -1
    }

    /** Best installed-app match for a spoken name. Returns index or -1. */
    fun bestAppIndex(query: String, labels: List<String>): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty() || labels.isEmpty()) return -1
        val l = labels.map { it.trim().lowercase() }
        val qc = q.replace(" ", "")
        val lc = l.map { it.replace(" ", "") }
        val exact = lc.indexOfFirst { it == qc }
        if (exact >= 0) return exact
        val starts = l.indices.filter { lc[it].startsWith(qc) }
        if (starts.isNotEmpty()) return starts.minByOrNull { l[it].length } ?: -1
        val words = l.indices.filter { l[it].split(" ").contains(q) }
        if (words.isNotEmpty()) return words.minByOrNull { l[it].length } ?: -1
        // a very short heard word must not match "somewhere inside" a name (misheard "in" opened random apps)
        val sub = if (q.length >= 4) l.indices.filter { l[it].contains(q) } else emptyList()
        return sub.minByOrNull { l[it].length } ?: -1
    }

    // ---------------------------------------------------------------- API keys / cloud failover

    /** Splits pasted text into plausible keys. Never logs or echoes them. */
    fun parseKeys(raw: String): List<String> =
        raw.split(Regex("[\\r\\n,;]+"))
            .map { it.trim() }
            .filter { it.length in 20..200 && it.none { c -> c.isWhitespace() } }
            .distinct()
            .take(MAX_KEYS)

    enum class Next { SUCCESS, NEXT_KEY, NEXT_MODEL, STOP }

    /**
     * Deterministic failover rule for ONE http answer.
     * 598 = network is down (stop at once, retrying only wastes time), 599 = timeout / IO error.
     */
    fun nextStep(code: Int, body: String): Next = when {
        code in 200..299 -> Next.SUCCESS
        code == 598 -> Next.STOP
        code == 400 ->
            if (body.contains("API key", ignoreCase = true) || body.contains("API_KEY_INVALID")) Next.NEXT_KEY
            else Next.STOP
        code == 401 || code == 403 -> Next.NEXT_KEY
        code == 404 -> Next.NEXT_MODEL
        code == 429 -> Next.NEXT_KEY
        code == 599 || code in 500..599 -> Next.NEXT_KEY
        else -> Next.STOP
    }

    /** nokey | network | key | quota | model | server | other */
    fun failureKind(code: Int): String = when {
        code == 0 -> "nokey"
        code == 598 || code == 599 -> "network"
        code == 400 || code == 401 || code == 403 -> "key"
        code == 429 -> "quota"
        code == 404 -> "model"
        code in 500..597 -> "server"
        else -> "other"
    }

    /** Keeps the newest entries. Entries are stored as user/model pairs, so drop two at a time. */
    fun <T> boundHistory(list: MutableList<T>, max: Int = HISTORY_MAX) {
        while (list.size > max) {
            list.removeAt(0)
            if (list.isNotEmpty()) list.removeAt(0)
        }
    }
