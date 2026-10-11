package com.nova.assistant

import com.nova.assistant.AutoSetupPlan.Outcome
import com.nova.assistant.AutoSetupPlan.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSetupPlanTest {
    @Test fun startsWithTwoDueTasks() {
        val t = AutoSetupPlan.initial()
        assertEquals(listOf(AutoSetupPlan.VOICE, AutoSetupPlan.PROVIDERS), t.map { it.id })
        assertEquals(AutoSetupPlan.VOICE, AutoSetupPlan.due(t, 0L)?.id)
    }

    @Test fun okAndSkipFinishOneShotTasks() {
        val v = AutoSetupPlan.initial()[0]
        assertEquals(Status.OK, AutoSetupPlan.apply(v, Outcome.OK, 100L).status)
        assertEquals(Status.SKIPPED, AutoSetupPlan.apply(v, Outcome.SKIP, 100L).status)
        assertEquals(Status.GAVE_UP, AutoSetupPlan.apply(v, Outcome.FATAL, 100L).status)
    }

    @Test fun healthCheckRepeatsEverySixHours() {
        val p = AutoSetupPlan.initial()[1]
        val a = AutoSetupPlan.apply(p, Outcome.OK, 1000L)
        assertEquals(Status.PENDING, a.status)
        assertEquals(1000L + AutoSetupPlan.RECHECK_MS, a.nextAt)
        assertNull(AutoSetupPlan.due(listOf(a), 1000L + AutoSetupPlan.RECHECK_MS - 1))
        assertEquals(AutoSetupPlan.PROVIDERS, AutoSetupPlan.due(listOf(a), 1000L + AutoSetupPlan.RECHECK_MS)?.id)
        // no key now = skipped, but asked again later (the owner may add a key)
        assertEquals(Status.PENDING, AutoSetupPlan.apply(p, Outcome.SKIP, 1000L).status)
    }

    @Test fun retriesBackOffThenGiveUp() {
        var t = AutoSetupPlan.initial()[0]
        var now = 0L
        for (i in 0 until AutoSetupPlan.MAX_ATTEMPTS - 1) {
            val wait = AutoSetupPlan.backoff(t.attempts)
            t = AutoSetupPlan.apply(t, Outcome.RETRY, now)
            assertEquals(Status.PENDING, t.status)
            assertEquals(now + wait, t.nextAt)
            assertNull(AutoSetupPlan.due(listOf(t), now + wait - 1))
            now = t.nextAt
        }
        t = AutoSetupPlan.apply(t, Outcome.RETRY, now)
        assertEquals(Status.GAVE_UP, t.status)
        assertNull(AutoSetupPlan.nextWake(listOf(t)))
        for (i in 1 until AutoSetupPlan.BACKOFF_MS.size) assertTrue(AutoSetupPlan.BACKOFF_MS[i] > AutoSetupPlan.BACKOFF_MS[i - 1])
    }

    @Test fun answerCodes() {
        assertEquals(Outcome.OK, AutoSetupPlan.outcomeFor(200))
        assertEquals(Outcome.RETRY, AutoSetupPlan.outcomeFor(598))
        assertEquals(Outcome.RETRY, AutoSetupPlan.outcomeFor(429))
        assertEquals(Outcome.RETRY, AutoSetupPlan.outcomeFor(503))
        assertEquals(Outcome.OK, AutoSetupPlan.outcomeFor(401))   // a result, shown in SELF TEST, not retried
        assertTrue(AutoSetupPlan.line("Groq", 200).startsWith("PASS"))
        assertTrue(AutoSetupPlan.line("Groq", 401).startsWith("FAIL"))
        assertTrue(AutoSetupPlan.line("Groq", 598).startsWith("WARN"))
    }

    @Test fun nextWakeIsEarliestPending() {
        val t = listOf(AutoSetupPlan.Task("a", nextAt = 500L), AutoSetupPlan.Task("b", nextAt = 200L), AutoSetupPlan.Task("c", Status.OK, nextAt = 1L))
        assertEquals(200L, AutoSetupPlan.nextWake(t))
    }
}
