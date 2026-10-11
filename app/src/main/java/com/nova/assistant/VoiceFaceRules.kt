package com.nova.assistant

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.TimeUnit

/**
 * Part 5: VOICE + FACE. Pure Kotlin (no Android classes), unit tested.
 *
 *  - VoiceTune: the owner says about 10 sentences; their distances to the saved voiceprint pick a personal limit instead of the
 *    untuned 0.4 / 0.5 / 0.6. Optionally another person's distances show whether the voice check can tell them apart.
 *  - LockGate: while the phone is LOCKED (and the owner switched the gate on) NOVA acts only when BOTH the voice check AND the
 *    system face / fingerprint screen (BiometricPrompt) pass. Android gives apps no face-unlock data and does not let an app demand
 *    "face only": the prompt accepts whatever biometric the phone has set up (face or fingerprint). A recording of the owner can fool
 *    the voice check, which is why the second factor exists.
 *  - FaceGateState: hand-off between the mic thread that waits and the face screen that answers.
 */
object VoiceTune {
    const val SAMPLES = 10
    const val MIN_SAMPLES = 6
    const val OTHER_SAMPLES = 3
    const val MIN_THRESHOLD = 0.25
    const val MAX_THRESHOLD = 0.60
    const val MIN_ACCEPT = 0.8          // below this the owner would be refused too often

    class Result(val ok: Boolean, val threshold: Double, val acceptRate: Double, val rejectRate: Double?, val note: String)

    private fun clean(v: List<Double>): List<Double> = v.filter { it.isFinite() && it >= 0.0 }

    /** Nearest-rank 90th percentile of a sorted list. */
    private fun p90(sorted: List<Double>): Double {
        val idx = Math.ceil(0.9 * sorted.size).toInt() - 1
        return sorted[idx.coerceIn(0, sorted.size - 1)]
    }

    /**
     * [own] = distances of the owner's test sentences to the saved voiceprint. [others] = distances of another person's sentences
     * (may be empty). note is one of: "few", "ok", "separated", "overlap", "noisy".
     */
    fun choose(own: List<Double>, others: List<Double>): Result {
        val o = clean(own).sorted()
        if (o.size < MIN_SAMPLES) return Result(false, 0.0, 0.0, null, "few")
        val a = p90(o)
        var thr = (a * 1.15 + 0.02).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        val x = clean(others)
        var note = "ok"
        if (x.size >= 2) {
            val closest = x.minOrNull() ?: 0.0
            if (closest <= a) {
                // another voice is as close as the owner's own: the voice check cannot be trusted alone
                val acc0 = o.count { it <= thr } / o.size.toDouble()
                return Result(false, thr, acc0, x.count { it > thr } / x.size.toDouble(), "overlap")
            }
            thr = minOf(thr, ((a + closest) / 2.0).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD))
            note = "separated"
        }
        val acc = o.count { it <= thr } / o.size.toDouble()
        val rej = if (x.size >= 2) x.count { it > thr } / x.size.toDouble() else null
        if (acc < MIN_ACCEPT) return Result(false, thr, acc, rej, "noisy")
        return Result(true, thr, acc, rej, note)
    }

    /** Distances are stored as plain numbers (no audio). */
    fun encode(v: List<Double>): String = v.joinToString(",") { "%.4f".format(java.util.Locale.US, it) }

    fun decode(s: String): List<Double> =
        s.split(",").mapNotNull { it.trim().toDoubleOrNull() }.filter { it.isFinite() && it >= 0.0 }
}

object LockGate {
    const val BIOMETRIC_SUCCESS = 0      // same value as BiometricManager.BIOMETRIC_SUCCESS
    const val FACE_VALID_MS = 60_000L    // one passed face check covers the turns of the next minute (voice is still checked every turn)
    const val FACE_WAIT_MS = 25_000L     // how long the mic thread waits for the face screen

    enum class Mode { WEAK, CREDENTIAL, NONE }

    /**
     * Which system screen can be shown. WEAK = the phone's biometric (face or fingerprint, whatever is set up).
     * CREDENTIAL = PIN / pattern (only Android 11+ allows that alone). NONE = nothing usable: NOVA says so and does nothing.
     */
    fun pickMode(sdk: Int, weakStatus: Int, deviceSecure: Boolean): Mode =
        if (weakStatus == BIOMETRIC_SUCCESS) Mode.WEAK
        else if (deviceSecure && sdk >= 30) Mode.CREDENTIAL
        else Mode.NONE

    enum class Step { ALLOW, NEED_FACE, DENY_NO_VOICEPRINT }

    /** Called after the normal voice step already allowed the turn. Unlocked phone or gate off: nothing extra. */
    fun step(gateOn: Boolean, locked: Boolean, voiceLockActive: Boolean, faceFresh: Boolean): Step {
        if (!gateOn || !locked) return Step.ALLOW
        if (!voiceLockActive) return Step.DENY_NO_VOICEPRINT   // "both" cannot be met without a saved voice
        return if (faceFresh) Step.ALLOW else Step.NEED_FACE
    }

    fun faceFresh(lastOkAt: Long, now: Long): Boolean = lastOkAt > 0L && now >= lastOkAt && now - lastOkAt <= FACE_VALID_MS

    /** The gate can only be switched on when a voiceprint exists and the phone has some second factor. */
    fun canEnable(hasVoiceprint: Boolean, mode: Mode): String? =
        if (!hasVoiceprint) "novoice" else if (mode == Mode.NONE) "noface" else null
}

object FaceGateState {
    private val lk = ReentrantLock()
    private val cond = lk.newCondition()
    private var token = 0L
    private var result: Boolean? = null
    @Volatile var lastOkAt = 0L
        private set

    /** Starts one face check. Any older check is dead from now on. */
    fun begin(): Long = lk.withLock {
        token += 1
        result = null
        token
    }

    /** The face screen reports. A report for an old or already answered check is ignored. */
    fun finish(t: Long, ok: Boolean, now: Long) {
        lk.withLock {
            if (t == token && result == null) {
                result = ok
                if (ok) lastOkAt = now
                cond.signalAll()
            }
        }
    }

    /** True only when check [t] is still the current one and passed. Waits up to [timeoutMs]. */
    fun await(t: Long, timeoutMs: Long): Boolean {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lk.lock()
        try {
            while (t == token && result == null && nanos > 0L) nanos = cond.awaitNanos(nanos)
            val ok = t == token && result == true
            if (t == token && result == null) result = false   // timed out: a late success must not count
            return ok
        } finally {
            lk.unlock()
        }
    }

    /** For tests and for "forget the face check" (for example when NOVA stops). */
    fun reset() {
        lk.withLock {
            token += 1
            result = null
            lastOkAt = 0L
        }
    }
}
