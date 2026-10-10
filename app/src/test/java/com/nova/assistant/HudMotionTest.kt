package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudMotionTest {
    @Test fun barsStayInRange() {
        for (st in listOf("listen", "think", "reply")) for (i in 0 until HudMath.BARS) for (ms in listOf(0L, 333L, 5000L)) {
            val v = HudMath.barHeight(i, HudMath.BARS, 0.7f, ms, st)
            assertTrue("$st $i $ms -> $v", v in 0f..1f)
        }
    }

    @Test fun louderVoiceMakesTallerBars() {
        var quiet = 0f
        var loud = 0f
        for (i in 0 until HudMath.BARS) {
            quiet += HudMath.barHeight(i, HudMath.BARS, 0f, 1000L, "listen")
            loud += HudMath.barHeight(i, HudMath.BARS, 1f, 1000L, "listen")
        }
        assertTrue(loud > quiet)
    }

    @Test fun rippleFadesOut() {
        assertEquals(1f, HudMath.rippleAlpha(0f), 0.0001f)
        assertEquals(0f, HudMath.rippleAlpha(1f), 0.0001f)
    }

    @Test fun colourMixEndpoints() {
        assertEquals(0xFF27E1FF.toInt(), HudMath.mix(0xFF27E1FF.toInt(), 0xFFB06BFF.toInt(), 0f))
        assertEquals(0xFFB06BFF.toInt(), HudMath.mix(0xFF27E1FF.toInt(), 0xFFB06BFF.toInt(), 1f))
    }

    @Test fun cardStaysLongEnoughToReadASilentReply() {
        assertEquals(1500L, HudMath.holdMs(0))
        assertTrue(HudMath.holdMs(100) > HudMath.holdMs(10))
        assertEquals(9000L, HudMath.holdMs(5000))
    }

    @Test fun seamFlareOnlyAtTheEndOfTheJoin() {
        assertEquals(0f, HudMath.seamFlare(0.5f), 0.0001f)
        assertEquals(1f, HudMath.seamFlare(0.85f), 0.001f)
        assertEquals(0f, HudMath.seamFlare(1f), 0.0001f)
    }
}

class HudGlassTest {
    @org.junit.Test fun popEaseEndpointsAndMonotonic() {
        org.junit.Assert.assertEquals(0f, HudMath.popEase(0f), 0.0001f)
        org.junit.Assert.assertEquals(1f, HudMath.popEase(1f), 0.0001f)
        org.junit.Assert.assertTrue(HudMath.popEase(0.3f) < HudMath.popEase(0.6f))
        org.junit.Assert.assertEquals(1f, HudMath.popEase(5f), 0.0001f)
    }

    @org.junit.Test fun glowStaysInRange() {
        for (st in listOf("listen", "think", "reply")) for (ms in 0L..4000L step 250L) {
            val g = HudMath.glowLevel(ms, st)
            org.junit.Assert.assertTrue(g in 0f..1f)
        }
    }
}
