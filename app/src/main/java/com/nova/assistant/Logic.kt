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
