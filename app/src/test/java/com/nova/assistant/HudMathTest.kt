package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudMathTest {
    @Test fun plateProgressIsClamped() {
        assertEquals(0f, HudMath.plateProgress(0, 0f), 0.0001f)
        assertEquals(1f, HudMath.plateProgress(0, 1f), 0.0001f)
        assertEquals(0f, HudMath.plateProgress(11, 0.1f), 0.0001f)
    }

    @Test fun lastPlateFinishesBeforeEnd() {
        assertEquals(1f, HudMath.plateProgress(HudMath.PANELS - 1, 1f), 0.0001f)
    }

    @Test fun easeOutBackEndpoints() {
        assertEquals(0f, HudMath.easeOutBack(0f), 0.0001f)
        assertEquals(1f, HudMath.easeOutBack(1f), 0.0001f)
        assertTrue(HudMath.easeOutBack(0.8f) > 1f)   // overshoot
    }

    @Test fun textAppearsOnlyAfterJoin() {
        assertEquals(0f, HudMath.textAlpha(0.5f), 0.0001f)
        assertEquals(0f, HudMath.textAlpha(0.75f), 0.0001f)
        assertEquals(1f, HudMath.textAlpha(1f), 0.0001f)
    }
}
