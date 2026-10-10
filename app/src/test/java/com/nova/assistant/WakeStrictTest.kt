package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeStrictTest {
    @Test fun singleGlitchNeverWakes() {
        var hits = Logic.nextWakeHits(0, true)
        assertFalse(Logic.wakeConfirmed(hits, false))
        hits = Logic.nextWakeHits(hits, false)       // next chunk has no wake word: counter restarts
        assertEquals(0, hits)
        assertFalse(Logic.wakeConfirmed(hits, false))
    }

    @Test fun repeatedPartialWakes() {
        var hits = 0
        hits = Logic.nextWakeHits(hits, true)
        hits = Logic.nextWakeHits(hits, true)
        assertTrue(Logic.wakeConfirmed(hits, false))
    }

    @Test fun finalResultWakesAtOnce() {
        assertTrue(Logic.wakeConfirmed(1, true))
        assertFalse(Logic.wakeConfirmed(0, true))     // a final result without the wake word is never a wake
    }

    @Test fun nothingHeardIsFalseWake() {
        assertTrue(Logic.isFalseWake(false, "", false, false))
        assertTrue(Logic.isFalseWake(false, "", true, true))
    }

    @Test fun noiseOnlyIsFalseWakeOffline() {
        assertTrue(Logic.isFalseWake(true, "the", false, false))
        assertTrue(Logic.isFalseWake(true, "", false, false))
        assertFalse(Logic.isFalseWake(true, "torch on", false, false))
    }

    @Test fun cloudHearingAndAnswersAreNotDropped() {
        assertFalse(Logic.isFalseWake(true, "", false, true))     // Groq may still understand the audio
        assertFalse(Logic.isFalseWake(true, "the", true, false))  // a yes/no answer goes to the safe "unclear = no" path
    }
}
