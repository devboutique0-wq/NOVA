package com.nova.assistant

/**
 * Part 4: NEVER WAKE BY ITSELF / NEVER INTERRUPT, and the safe listening fixes.
 * Pure Kotlin (no Android classes), unit tested. Nothing here loosens a wake rule: the wake word still needs two consecutive hits,
 * a spoken-loud peak (>= 900), the cooldown, and (when switched on) the owner's voice check.
 */
object ListenGuard {
    /** Quietest level that can ever count as speech. */
    const val SPEECH_FLOOR = 450.0

    /** While someone is talking near the phone (or the owner talks to another person), NOVA does not start talking by itself. */
    const val VOICE_QUIET_MS = 3500L

    /** Audio kept after the last loud chunk when a command is sent to a cloud transcriber (trailing silence is cut). */
    const val TAIL_KEEP_MS = 600
    const val BYTES_PER_MS = 32            // 16 kHz, 16 bit, mono

    /** Whisper is given this neutral hint (a real command list could be echoed back on noise and run). */
    const val GROQ_PROMPT = "Voice message for a phone assistant called Nova."

    /** A chunk that is clearly speech: louder than the quiet floor and 2.5 times the room noise. */
    fun isVoice(rms: Double, noise: Double): Boolean = rms > maxOf(SPEECH_FLOOR, noise * 2.5)

    /**
     * Room noise estimate. Chunks that are loud like speech (the wake word itself, a command) hardly move it, so the "speaking"
     * threshold does not rise while the owner talks and soft words are not lost as silence. A room that really stays loud still
     * adapts, only slowly.
     */
    fun nextNoise(noise: Double, rms: Double): Double {
        val calm = rms < maxOf(SPEECH_FLOOR, noise * 2.0)
        val w = if (calm) 0.03 else 0.003
        return noise * (1.0 - w) + minOf(rms, 1500.0) * w
    }

    /** True while a voice was heard in the last [quietMs]: proactive speech (alerts, driving read-out) waits. */
    fun othersTalking(lastVoiceAt: Long, now: Long, quietMs: Long = VOICE_QUIET_MS): Boolean =
        lastVoiceAt > 0L && now >= lastVoiceAt && now - lastVoiceAt < quietMs

    fun mayAnnounce(lastVoiceAt: Long, now: Long): Boolean = !othersTalking(lastVoiceAt, now)

    private val JOINERS = setOf(
        "aur", "and", "phir", "fir", "then", "also", "ya", "or", "plus", "uske", "baad", "ke", "और", "फिर", "या"
    )

    /** The live text stops on a joining word ("... aur", "... phir", "... ke baad"): the owner is not finished, never rush the end. */
    fun endsWithJoiner(live: String): Boolean {
        val w = live.lowercase().replace(Regex("[^a-z0-9\u0900-\u097F ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (w.isEmpty()) return false
        return w.last() in JOINERS
    }

    /**
     * Cuts the silence after the last loud chunk. [lastSpeechBytes] = pcm size right after the last speech chunk (0 = never detected,
     * then nothing is cut). Never shorter than [minBytes] (the shortest clip that is sent to be transcribed), never longer than the input.
     */
    fun trimTail(pcm: ByteArray, lastSpeechBytes: Int, keepMs: Int = TAIL_KEEP_MS, minBytes: Int = 25600): ByteArray {
        if (lastSpeechBytes <= 0 || lastSpeechBytes >= pcm.size) return pcm
        var end = lastSpeechBytes + keepMs * BYTES_PER_MS
        end = maxOf(end, minOf(pcm.size, minBytes))
        end = minOf(end, pcm.size)
        end -= end % 2                    // whole 16-bit samples only
        return if (end >= pcm.size) pcm else pcm.copyOf(end)
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9\u0900-\u097F ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Whisper sometimes writes its own hint sentence back when it hears only noise. That echo is never a command: a transcript that is
     * only the hint (or a 4+ word piece of it) becomes "", and a hint glued in front of a real sentence is cut out of it.
     * Any other text comes back unchanged (a short real command such as "nova" is never touched).
     */
    fun stripPromptEcho(text: String, prompt: String = GROQ_PROMPT): String {
        val t = norm(text)
        if (t.isEmpty()) return text
        val p = norm(prompt)
        if (t == p) return ""
        if (t.split(" ").size >= 4 && p.contains(t)) return ""
        if (t.contains(p)) return t.replace(p, " ").replace(Regex("\\s+"), " ").trim()
        return text
    }

    /** Whisper segments that all say "no speech" (high no_speech_prob): the text is a hallucination on noise. Empty list = no information, keep. */
    fun allNoSpeech(noSpeechProbs: List<Double>, limit: Double = 0.8): Boolean =
        noSpeechProbs.isNotEmpty() && noSpeechProbs.all { it > limit }
}
