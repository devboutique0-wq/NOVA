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
        "drive_on", "drive_off", "drive_read", "drive_clear", "drive_reply", "drive_dictate", "analyze", "quiet_on", "quiet_o
