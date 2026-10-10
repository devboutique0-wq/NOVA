package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    private val Q = "why is the sky blue"
    private fun ok(t: String) = Routing.CloudResult(t, true)
    private fun fail(kind: String) = Routing.CloudResult("cloud failed $kind", false, false, kind)

    @Test fun cloudOkNeverTouchesLocal() {
        var localCalled = false
        val r = Routing.run(AiRouter.plan(true, false, true, Q), Q, { ok("blue") }, { localCalled = true; "x" }, { })
        assertEquals("blue", r)
        assertFalse(localCalled)
    }
    @Test fun networkFailureFallsBackToLocalAndSetsCooldown() {
        var cd = 0L
        val r = Routing.run(AiRouter.plan(true, false, true, Q), Q, { fail("network") }, { "Offline: scattering" }, { cd = it })
        assertEquals("Offline: scattering", r)
        assertEquals(AiRouter.cooldownMs("network"), cd)
    }
    @Test fun quotaFailureSetsLongCooldownAndPlanSkipsCloudNextTime() {
        var cd = 0L
        Routing.run(AiRouter.plan(true, false, true, Q), Q, { fail("quota") }, { "local" }, { cd = it })
        assertEquals(120_000L, cd)
        assertEquals(listOf(AiRouter.Step.LOCAL), AiRouter.plan(true, cd > 0, true, Q))
    }
    @Test fun cloudActedThenFailedNeverAsksLocal() {
        var localCalled = false
        val acted = Routing.CloudResult("Torch on, but then the AI stopped", false, true, "network")
        val r = Routing.run(AiRouter.plan(true, false, true, Q), Q, { acted }, { localCalled = true; "x" }, { })
        assertEquals("Torch on, but then the AI stopped", r)
        assertFalse(localCalled)
    }
    @Test fun noKeyButLocalReadyUsesLocal() {
        val steps = AiRouter.plan(false, false, true, Q)
        assertEquals(listOf(AiRouter.Step.LOCAL), steps)
        assertEquals("local answer", Routing.run(steps, Q, { error("cloud must not run") }, { "local answer" }, { }))
    }
    @Test fun nothingReadyGivesNull() {
        val steps = AiRouter.plan(false, false, false, Q)
        assertTrue(steps.isEmpty())
        assertNull(Routing.run(steps, Q, { error("no") }, { error("no") }, { }))
    }
    @Test fun cloudFailsAndLocalFailsReturnsTheCloudMessage() {
        val r = Routing.run(AiRouter.plan(true, false, true, Q), Q, { fail("server") }, { null }, { })
        assertEquals("cloud failed server", r)
    }
    @Test fun junkIsNeverRouted() {
        for (junk in listOf("", "   ", "uh", "hmm")) {
            val r = Routing.run(listOf(AiRouter.Step.CLOUD, AiRouter.Step.LOCAL), junk,
                { error("junk reached the cloud") }, { error("junk reached the model") }, { })
            assertNull(r)
        }
    }
    @Test fun otherFailureHasNoCooldown() {
        var cd = -1L
        Routing.run(listOf(AiRouter.Step.CLOUD), Q, { fail("other") }, { null }, { cd = it })
        assertEquals(-1L, cd)   // setCooldown is not called for kinds with cooldown 0
    }
}
