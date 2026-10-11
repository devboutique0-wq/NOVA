package com.nova.assistant

/**
 * v17 "only my voice". Pure rules (no Android classes) so they are unit tested.
 * A voiceprint is the x-vector Vosk's speaker model gives for one utterance. The owner's profile is the average of several.
 * THRESHOLD is a cosine distance and has NOT been tuned on a real phone. A recording of the owner can still fool this,
 * so risky actions keep their own spoken yes.
 */
object SpeakerRules {
    const val THRESHOLD = 0.5
    const val MIN_FRAMES_COMMAND = 60
    const val MIN_FRAMES_ANSWER = 30
    const val MIN_FRAMES_ENROLL = 120
    const val ENROLL_SAMPLES = 5

    enum class Verdict { ALLOW, REJECT, TOO_SHORT }

    /** Owner's strictness knob: 0 = STRICT (default), 1 = NORMAL (the old 0.5), 2 = LOOSE. A lower distance limit refuses more voices. */
    fun thresholdFor(level: Int): Double = when (level) {
        1 -> 0.5
        2 -> 0.6
        else -> 0.4
    }

    /**
     * Part 5: the limit the owner's own tuning chose (VoiceTune) wins over the fixed STRICT / NORMAL / LOOSE values.
     * A missing or out-of-range tuned value (0 = not tuned) falls back to the level.
     */
    fun effectiveThreshold(level: Int, tuned: Double): Double =
        if (tuned.isFinite() && tuned >= VoiceTune.MIN_THRESHOLD && tuned <= VoiceTune.MAX_THRESHOLD) tuned else thresholdFor(level)

    /** 0 = same direction, 2 = opposite. Mismatched, empty or all-zero vectors are 2.0 (never a match). */
    fun cosineDistance(a: DoubleArray, b: DoubleArray): Double {
        if (a.isEmpty() || a.size != b.size) return 2.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0.0 || nb <= 0.0) return 2.0
        return 1.0 - dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    /** Mean of equally long vectors, or null when there is nothing usable. */
    fun average(vs: List<DoubleArray>): DoubleArray? {
        if (vs.isEmpty()) return null
        val n = vs[0].size
        if (n == 0 || vs.any { it.size != n }) return null
        val out = DoubleArray(n)
        for (v in vs) for (i in 0 until n) out[i] += v[i]
        for (i in 0 until n) out[i] /= vs.size.toDouble()
        return out
    }

    fun decide(vec: DoubleArray?, frames: Int, profile: DoubleArray?, isAnswer: Boolean, threshold: Double = THRESHOLD): Verdict {
        if (vec == null || profile == null) return Verdict.REJECT
        val need = if (isAnswer) MIN_FRAMES_ANSWER else MIN_FRAMES_COMMAND
        if (frames < need) return Verdict.TOO_SHORT
        return if (cosineDistance(vec, profile) <= threshold) Verdict.ALLOW else Verdict.REJECT
    }
}
