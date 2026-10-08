package com.nova.assistant

/**
 * NOVA "Layer 2": an optional offline language model that can turn a phrase NOVA did not understand
 * ("make it dark in here") into ONE command NOVA already knows ("brightness minimum").
 *
 * Pure Kotlin (no Android classes) so the prompt and the output rules are unit-testable.
 * The model is only a translator: its output is accepted only if Logic.classify understands it AND it is a
 * safe kind. It can never approve anything, never type text, never run a risky action without the spoken "yes".
 */
interface LocalBrain {
    /** true once the model is loaded and can answer. */
    val ready: Boolean

    /** Returns the raw model text, or null on failure. May be slow; the caller applies a time limit. */
    fun complete(prompt: String): String?
}

object LocalBrainRules {
    const val MAX_INPUT = 160
    const val MAX_OUTPUT_CHARS = 120
    const val MAX_WORDS = 6
    const val TIMEOUT_MS = 8000L

    /** Command kinds a model suggestion may use directly. Same set as shortcuts: no tap/type/call/send. */
    val DIRECT_KINDS: Set<String> get() = Brain.SAFE_KINDS

    /** "tap" is allowed too, but NovaService always asks a spoken "yes" first for a tap that came from a model. */
    const val TAP_KIND = "tap"

    private val EXAMPLES = listOf(
        "make it dark in here" to "brightness minimum",
        "i cannot hear anything" to "volume up",
        "what is the charge level" to "battery",
        "light on karo" to "torch on",
        "go back" to "back"
    )

    /** The prompt for the model. The user's words are data, quoted on their own line. */
    fun buildPrompt(userText: String): String {
        val heard = userText.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX_INPUT)
        val sb = StringBuilder()
        sb.append("You translate a spoken phone request into ONE short command for a phone assistant.\n")
        sb.append("Allowed commands: battery, time, date, torch on, torch off, volume up, volume down, volume mute, ")
        sb.append("volume max, volume <number>, brightness up, brightness down, brightness <number>, ")
        sb.append("pause, next song, previous song, home, back, recents, notifications, quick settings, lock, ")
        sb.append("scroll up, scroll down, open <app name>, camera.\n")
        sb.append("Answer with exactly one command on one line, or the single word NONE if no command fits.\n")
        sb.append("Never explain. Never invent other commands.\n\n")
        for ((q, a) in EXAMPLES) sb.append("Request: ").append(q).append("\nCommand: ").append(a).append("\n\n")
        sb.append("Request: ").append(heard).append("\nCommand:")
        return sb.toString()
    }

    /**
     * Accepts the model's answer only when it is ONE line that Logic.classify understands and that is a safe kind
     * (or a tap). Returns the cleaned command sentence, or null. Everything else (several lines, prose, "NONE",
     * type/call/send/analyze/update/stop...) is refused.
     */
    fun parseOutput(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim()
        if (s.isEmpty() || s.length > 400) return null
        s = s.removePrefix("```").removeSuffix("```").trim()
        s = s.removePrefix("Command:").trim()
        if (s.lines().filter { it.isNotBlank() }.size != 1) return null
        s = s.trim().trim('"', '\'', '`', '.').trim()
        if (s.isEmpty() || s.length > MAX_OUTPUT_CHARS) return null
        if (s.any { it in "!?,;:" }) return null                      // prose, not a bare command
        if (s.split(Regex("\\s+")).size > MAX_WORDS) return null
        val n = Logic.norm(s)
        if (n == "none" || n == "unknown" || n.isEmpty()) return null
        val c = Logic.classify(n) ?: return null
        return if (accepted(c)) n else null
    }

    fun accepted(c: Logic.Cmd): Boolean = c.kind in DIRECT_KINDS || c.kind == TAP_KIND
}
