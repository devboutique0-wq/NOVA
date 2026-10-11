package com.nova.assistant

/**
 * v34 PART 3: pure state machine of the silent background "auto setup" (no Android classes, unit-tested).
 * AutoSetup.kt (Android) runs the real work; this file only decides WHICH task is due, what happens after a result,
 * and how long to wait before a retry (back-off). Nothing here speaks, shows a pop-up or asks the owner anything.
 */
object AutoSetupPlan {
    const val VOICE = "voice_model"
    const val PROVIDERS = "providers"
    const val MAX_ATTEMPTS = 5
    const val RECHECK_MS = 6L * 60 * 60 * 1000
    /** Wait before retry number 1..5 (index = attempts so far). */
    val BACKOFF_MS = longArrayOf(60_000L, 5L * 60_000, 30L * 60_000, 2L * 3_600_000, 6L * 3_600_000)

    enum class Status { PENDING, OK, SKIPPED, GAVE_UP }
    enum class Outcome { OK, SKIP, RETRY, FATAL }

    /** [repeatMs] > 0 = the task runs again that much later after OK or SKIP (the provider health check). */
    data class Task(
        val id: String,
        val status: Status = Status.PENDING,
        val attempts: Int = 0,
        val nextAt: Long = 0L,
        val repeatMs: Long = 0L
    )

    fun initial(): List<Task> = listOf(Task(VOICE), Task(PROVIDERS, repeatMs = RECHECK_MS))

    /** First pending task whose time has come, or null. */
    fun due(tasks: List<Task>, now: Long): Task? = tasks.firstOrNull { it.status == Status.PENDING && it.nextAt <= now }

    /** Earliest time a pending task becomes due, or null when nothing is pending (the runner can stop). */
    fun nextWake(tasks: List<Task>): Long? = tasks.filter { it.status == Status.PENDING }.minOfOrNull { it.nextAt }

    fun backoff(attempts: Int): Long = BACKOFF_MS[attempts.coerceIn(0, BACKOFF_MS.size - 1)]

    fun apply(t: Task, o: Outcome, now: Long): Task = when (o) {
        Outcome.OK, Outcome.SKIP -> {
            val done = if (o == Outcome.OK) Status.OK else Status.SKIPPED
            if (t.repeatMs > 0L) t.copy(status = Status.PENDING, attempts = 0, nextAt = now + t.repeatMs)
            else t.copy(status = done, attempts = 0)
        }
        Outcome.RETRY -> {
            val n = t.attempts + 1
            if (n >= MAX_ATTEMPTS) t.copy(status = Status.GAVE_UP, attempts = n)
            else t.copy(status = Status.PENDING, attempts = n, nextAt = now + backoff(t.attempts))
        }
        Outcome.FATAL -> t.copy(status = Status.GAVE_UP)
    }

    /** A provider answer code -> what the runner does next. 200..299 and key / model errors are RESULTS (shown), network / server / quota are retried. */
    fun outcomeFor(code: Int): Outcome = when {
        code in 200..299 -> Outcome.OK
        code == 598 || code == 599 || code == 429 || code in 500..597 -> Outcome.RETRY
        else -> Outcome.OK   // 401 / 403 / 404 ...: nothing to retry, the line in SELF TEST tells the owner
    }

    /** One SELF TEST line for a provider check. */
    fun line(label: String, code: Int): String = when {
        code in 200..299 -> "PASS $label - code=$code"
        code == 598 || code == 599 -> "WARN $label - internet nahi / timeout"
        code == 429 -> "WARN $label - limit poori (baad mein chalega)"
        code == 401 || code == 403 -> "FAIL $label - key reject hui"
        code == 404 || code == 400 || code == 422 -> "WARN $label - model abhi nahi mila (agla provider chalega)"
        else -> "WARN $label - code=$code"
    }
}
