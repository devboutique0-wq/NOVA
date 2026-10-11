package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFaceRulesTest {
    private val own = listOf(0.10, 0.12, 0.15, 0.18, 0.20, 0.22, 0.25, 0.28, 0.30, 0.35)

    // ---------------------------------------------------------------- VoiceTune
    @Test fun ownTestPicksALimitFromTheOwnersVoice() {
        val r = VoiceTune.choose(own, emptyList())
        assertTrue(r.ok)
        assertEquals("ok", r.note)
        assertEquals(0.365, r.threshold, 1e-9)       // 90th percentile 0.30 * 1.15 + 0.02
        assertEquals(1.0, r.acceptRate, 1e-9)
        assertNull(r.rejectRate)
    }

    @Test fun orderOfTheSamplesDoesNotMatter() {
        val r = VoiceTune.choose(own.reversed(), emptyList())
        assertEquals(0.365, r.threshold, 1e-9)
    }

    @Test fun tooFewSamplesAreRefused() {
        val r = VoiceTune.choose(listOf(0.1, 0.2, 0.2, 0.3, 0.3), emptyList())
        assertFalse(r.ok)
        assertEquals("few", r.note)
    }

    @Test fun brokenNumbersAreIgnored() {
        val r = VoiceTune.choose(listOf(Double.NaN, -1.0, 0.1, 0.1, 0.1, 0.1, 0.1), emptyList())
        assertFalse(r.ok)                              // only 5 usable numbers are left
        assertEquals("few", r.note)
    }

    @Test fun limitStaysInsideSafeBounds() {
        val tiny = VoiceTune.choose(List(10) { 0.05 }, emptyList())
        assertTrue(tiny.ok)
        assertEquals(VoiceTune.MIN_THRESHOLD, tiny.threshold, 1e-9)    // never tighter than 0.25
    }

    @Test fun veryUnsteadyVoiceIsNotTunedAndTheLimitIsNotChanged() {
        val wild = listOf(0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1)
        val r = VoiceTune.choose(wild, emptyList())
        assertFalse(r.ok)
        assertEquals("noisy", r.note)
        assertEquals(VoiceTune.MAX_THRESHOLD, r.threshold, 1e-9)
        assertTrue(r.acceptRate < VoiceTune.MIN_ACCEPT)
    }

    @Test fun anotherVoiceFarAwayKeepsTheLimitAndIsRefused() {
        val r = VoiceTune.choose(own, listOf(0.8, 0.9, 1.0))
        assertTrue(r.ok)
        assertEquals("separated", r.note)
        assertEquals(0.365, r.threshold, 1e-9)
        assertEquals(1.0, r.rejectRate!!, 1e-9)
    }

    @Test fun anotherVoiceCloserPullsTheLimitDown() {
        val r = VoiceTune.choose(own, listOf(0.42, 0.5))
        assertTrue(r.ok)
        assertEquals("separated", r.note)
        assertEquals(0.36, r.threshold, 1e-9)         // halfway between 0.30 and 0.42, below 0.365
        assertEquals(1.0, r.acceptRate, 1e-9)
        assertEquals(1.0, r.rejectRate!!, 1e-9)
    }

    @Test fun anotherVoiceAsCloseAsTheOwnerMeansDoNotTrustVoiceAlone() {
        val r = VoiceTune.choose(own, listOf(0.28, 0.9))
        assertFalse(r.ok)
        assertEquals("overlap", r.note)
        assertEquals(0.5, r.rejectRate!!, 1e-9)       // only one of the two was refused
    }

    @Test fun onlyOneOtherSampleIsNotEnoughToJudge() {
        val r = VoiceTune.choose(own, listOf(0.1))
        assertTrue(r.ok)
        assertEquals("ok", r.note)
        assertNull(r.rejectRate)
    }

    @Test fun distancesSurviveStorage() {
        assertEquals("0.1000,0.2500", VoiceTune.encode(listOf(0.1, 0.25)))
        assertEquals(listOf(0.1, 0.25), VoiceTune.decode("0.1000, 0.2500,abc,-1,NaN"))
        assertTrue(VoiceTune.decode("").isEmpty())
    }

    @Test fun tunedLimitWinsOverTheLevelOnlyWhenItIsSane() {
        assertEquals(0.33, SpeakerRules.effectiveThreshold(1, 0.33), 0.0)
        assertEquals(0.4, SpeakerRules.effectiveThreshold(0, 0.0), 0.0)               // not tuned
        assertEquals(0.6, SpeakerRules.effectiveThreshold(2, 0.9), 0.0)               // out of range: the level
        assertEquals(0.4, SpeakerRules.effectiveThreshold(0, Double.NaN), 0.0)
        assertEquals(0.25, SpeakerRules.effectiveThreshold(0, 0.25), 0.0)
        assertEquals(0.6, SpeakerRules.effectiveThreshold(0, 0.6), 0.0)
    }

    // ---------------------------------------------------------------- LockGate
    @Test fun secondFactorChoice() {
        assertEquals(LockGate.Mode.WEAK, LockGate.pickMode(34, LockGate.BIOMETRIC_SUCCESS, false))
        assertEquals(LockGate.Mode.WEAK, LockGate.pickMode(26, LockGate.BIOMETRIC_SUCCESS, true))
        assertEquals(LockGate.Mode.CREDENTIAL, LockGate.pickMode(34, 11, true))        // nothing enrolled, PIN exists, Android 11+
        assertEquals(LockGate.Mode.NONE, LockGate.pickMode(29, 11, true))              // PIN alone is not possible before Android 11
        assertEquals(LockGate.Mode.NONE, LockGate.pickMode(34, 11, false))             // no lock at all
        assertEquals(LockGate.Mode.NONE, LockGate.pickMode(34, 12, false))
    }

    @Test fun gateOffOrUnlockedAddsNothing() {
        assertEquals(LockGate.Step.ALLOW, LockGate.step(false, true, true, false))
        assertEquals(LockGate.Step.ALLOW, LockGate.step(true, false, false, false))
        assertEquals(LockGate.Step.ALLOW, LockGate.step(true, false, true, false))
    }

    @Test fun lockedNeedsBothVoiceprintAndFace() {
        assertEquals(LockGate.Step.DENY_NO_VOICEPRINT, LockGate.step(true, true, false, false))
        assertEquals(LockGate.Step.DENY_NO_VOICEPRINT, LockGate.step(true, true, false, true))   // a fresh face never replaces the voice
        assertEquals(LockGate.Step.NEED_FACE, LockGate.step(true, true, true, false))
        assertEquals(LockGate.Step.ALLOW, LockGate.step(true, true, true, true))
    }

    @Test fun faceStaysValidForAMinuteOnly() {
        assertFalse(LockGate.faceFresh(0L, 5000L))                                      // never passed
        assertTrue(LockGate.faceFresh(1000L, 1000L))
        assertTrue(LockGate.faceFresh(1000L, 1000L + LockGate.FACE_VALID_MS))
        assertFalse(LockGate.faceFresh(1000L, 1001L + LockGate.FACE_VALID_MS))
        assertFalse(LockGate.faceFresh(5000L, 1000L))                                   // clock went back
    }

    @Test fun switchingTheGateOnNeedsVoiceAndASecondFactor() {
        assertEquals("novoice", LockGate.canEnable(false, LockGate.Mode.WEAK))
        assertEquals("novoice", LockGate.canEnable(false, LockGate.Mode.NONE))
        assertEquals("noface", LockGate.canEnable(true, LockGate.Mode.NONE))
        assertNull(LockGate.canEnable(true, LockGate.Mode.WEAK))
        assertNull(LockGate.canEnable(true, LockGate.Mode.CREDENTIAL))
    }

    // ---------------------------------------------------------------- FaceGateState
    @Test fun passedCheckIsRemembered() {
        FaceGateState.reset()
        val t = FaceGateState.begin()
        FaceGateState.finish(t, true, 1234L)
        assertTrue(FaceGateState.await(t, 1000L))
        assertEquals(1234L, FaceGateState.lastOkAt)
    }

    @Test fun failedCheckNeverCounts() {
        FaceGateState.reset()
        val t = FaceGateState.begin()
        FaceGateState.finish(t, false, 50L)
        assertFalse(FaceGateState.await(t, 1000L))
        assertEquals(0L, FaceGateState.lastOkAt)
    }

    @Test fun timeoutDeniesAndALateSuccessIsIgnored() {
        FaceGateState.reset()
        val t = FaceGateState.begin()
        assertFalse(FaceGateState.await(t, 30L))
        FaceGateState.finish(t, true, 99L)                  // the face screen answers too late
        assertFalse(FaceGateState.await(t, 10L))
        assertEquals(0L, FaceGateState.lastOkAt)
    }

    @Test fun oldCheckCannotAnswerTheNewOne() {
        FaceGateState.reset()
        val t1 = FaceGateState.begin()
        val t2 = FaceGateState.begin()
        assertNotNull(t2)
        FaceGateState.finish(t1, true, 5L)                  // a stale screen
        assertFalse(FaceGateState.await(t1, 10L))
        assertEquals(0L, FaceGateState.lastOkAt)
        FaceGateState.finish(t2, true, 7L)
        assertTrue(FaceGateState.await(t2, 10L))
        assertEquals(7L, FaceGateState.lastOkAt)
    }

    @Test fun answerFromAnotherThreadWakesTheWaiter() {
        FaceGateState.reset()
        val t = FaceGateState.begin()
        Thread {
            Thread.sleep(50)
            FaceGateState.finish(t, true, 42L)
        }.start()
        assertTrue(FaceGateState.await(t, 3000L))
        assertEquals(42L, FaceGateState.lastOkAt)
    }

    @Test fun resetForgetsEverything() {
        FaceGateState.reset()
        val t = FaceGateState.begin()
        FaceGateState.finish(t, true, 10L)
        FaceGateState.reset()
        assertEquals(0L, FaceGateState.lastOkAt)
        assertFalse(FaceGateState.await(t, 10L))
    }
}
