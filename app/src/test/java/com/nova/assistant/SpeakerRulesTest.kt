package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRulesTest {
    private val me = doubleArrayOf(1.0, 0.2, 0.0, 0.5)

    @Test fun sameVoiceIsAllowed() {
        assertEquals(SpeakerRules.Verdict.ALLOW, SpeakerRules.decide(doubleArrayOf(1.0, 0.25, 0.05, 0.45), 200, me, false))
    }

    @Test fun otherVoiceIsRejected() {
        assertEquals(SpeakerRules.Verdict.REJECT, SpeakerRules.decide(doubleArrayOf(-0.5, 1.0, 0.8, -0.3), 200, me, false))
    }

    @Test fun shortAudioIsNeverAllowed() {
        assertEquals(SpeakerRules.Verdict.TOO_SHORT, SpeakerRules.decide(me, 10, me, false))
        assertEquals(SpeakerRules.Verdict.TOO_SHORT, SpeakerRules.decide(me, 10, me, true))
        assertEquals(SpeakerRules.Verdict.ALLOW, SpeakerRules.decide(me, 40, me, true))
    }

    @Test fun missingVectorOrProfileIsRejected() {
        assertEquals(SpeakerRules.Verdict.REJECT, SpeakerRules.decide(null, 200, me, false))
        assertEquals(SpeakerRules.Verdict.REJECT, SpeakerRules.decide(me, 200, null, false))
    }

    @Test fun badVectorsNeverMatch() {
        assertEquals(2.0, SpeakerRules.cosineDistance(doubleArrayOf(), doubleArrayOf()), 0.0)
        assertEquals(2.0, SpeakerRules.cosineDistance(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0)), 0.0)
        assertEquals(2.0, SpeakerRules.cosineDistance(doubleArrayOf(1.0), doubleArrayOf(1.0, 2.0)), 0.0)
    }

    @Test fun averageWorks() {
        val a = SpeakerRules.average(listOf(doubleArrayOf(1.0, 3.0), doubleArrayOf(3.0, 5.0)))
        assertTrue(a != null && a[0] == 2.0 && a[1] == 4.0)
        assertNull(SpeakerRules.average(emptyList()))
        assertNull(SpeakerRules.average(listOf(doubleArrayOf(1.0), doubleArrayOf(1.0, 2.0))))
    }
}
