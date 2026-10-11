package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenGuardTest {
    // ---- noise estimate: the wake word / a command must not raise the "speaking" threshold
    @Test fun loudChunkBarelyMovesNoise() {
        val n = ListenGuard.nextNoise(300.0, 3000.0)
        assertTrue(n > 300.0)
        assertTrue(n < 310.0)
    }

    @Test fun wakeWordSpokenLoudDoesNotRaiseThresholds() {
        var n = 300.0
        repeat(8) { n = ListenGuard.nextNoise(n, 2500.0) }     // about 0.8 s of the wake word
        assertTrue("noise was $n", n < 340.0)
        assertTrue(maxOf(900.0, n * 3.0) < 1100.0)               // wake peak limit stays near its 900 floor
        assertTrue(maxOf(450.0, n * 2.5) < 850.0)                // soft words still count as speech
    }

    @Test fun quietRoomLowersNoise() {
        var n = 300.0
        repeat(200) { n = ListenGuard.nextNoise(n, 100.0) }
        assertTrue("noise was $n", n < 120.0)
    }

    @Test fun reallyLoudRoomStillAdaptsSlowly() {
        var n = 300.0
        repeat(2000) { n = ListenGuard.nextNoise(n, 1200.0) }
        assertTrue("noise was $n", n > 1000.0)
    }

    @Test fun voiceDetection() {
        assertTrue(ListenGuard.isVoice(500.0, 100.0))
        assertFalse(ListenGuard.isVoice(400.0, 100.0))           // below the floor
        assertFalse(ListenGuard.isVoice(1000.0, 500.0))          // not 2.5 times the room
    }

    // ---- never talk over people
    @Test fun othersTalkingWindow() {
        assertFalse(ListenGuard.othersTalking(0L, 5000L))        // nobody heard yet
        assertTrue(ListenGuard.othersTalking(1000L, 2000L))
        assertTrue(ListenGuard.othersTalking(1000L, 1000L + ListenGuard.VOICE_QUIET_MS - 1))
        assertFalse(ListenGuard.othersTalking(1000L, 1000L + ListenGuard.VOICE_QUIET_MS))
        assertFalse(ListenGuard.othersTalking(5000L, 1000L))     // clock went back: never block forever
    }

    @Test fun announceWaitsWhileSomeoneTalks() {
        assertFalse(ListenGuard.mayAnnounce(10_000L, 11_000L))
        assertTrue(ListenGuard.mayAnnounce(10_000L, 20_000L))
        assertTrue(ListenGuard.mayAnnounce(0L, 20_000L))
    }

    // ---- compound commands are not cut
    @Test fun joinerAtTheEnd() {
        assertTrue(ListenGuard.endsWithJoiner("volume badhao aur"))
        assertTrue(ListenGuard.endsWithJoiner("torch on and"))
        assertTrue(ListenGuard.endsWithJoiner("brightness kam karo phir"))
        assertTrue(ListenGuard.endsWithJoiner("kuch kaam ke baad"))
        assertTrue(ListenGuard.endsWithJoiner("टॉर्च चालू और"))
        assertFalse(ListenGuard.endsWithJoiner("volume badhao"))
        assertFalse(ListenGuard.endsWithJoiner("torch chalu karo"))
        assertFalse(ListenGuard.endsWithJoiner(""))
        assertFalse(ListenGuard.endsWithJoiner("   "))
    }

    @Test fun fastEndSkipsAfterJoiner() {
        assertEquals(0, Logic.fastEndMs("torch on aur"))
        assertEquals(0, Logic.fastEndMs("torch on and"))
        assertEquals(Logic.FAST_END_MS, Logic.fastEndMs("torch on"))
    }

    @Test fun timersAreLongEnoughForCompoundAndDictation() {
        assertTrue(Logic.FAST_END_MS >= 1800)
        assertTrue(Logic.FAST_END_OPEN_APP_MS >= 2500)
        assertTrue(Logic.FAST_END_OPEN_APP_MS > Logic.FAST_END_MS)
        assertTrue(Logic.DICTATION_SILENCE_MS >= 2500)
        assertTrue(Logic.DICTATION_SILENCE_MS < Logic.MAX_DICTATION_MS)
    }

    // ---- trailing silence is not uploaded
    @Test fun trailingSilenceIsCut() {
        val pcm = ByteArray(160_000) { (it % 100).toByte() }     // 5 s
        val out = ListenGuard.trimTail(pcm, 32_000)              // last loud chunk ended at 1 s
        assertEquals(32_000 + ListenGuard.TAIL_KEEP_MS * ListenGuard.BYTES_PER_MS, out.size)
        for (i in out.indices) assertEquals(pcm[i].toInt(), out[i].toInt())      // the start is untouched
    }

    @Test fun trimNeverGoesBelowTheHearMinimum() {
        val pcm = ByteArray(30_000)
        val out = ListenGuard.trimTail(pcm, 6_400, ListenGuard.TAIL_KEEP_MS, 25_600)
        assertEquals(25_600, out.size)
    }

    @Test fun shortClipIsKeptWhole() {
        val pcm = ByteArray(20_000)
        assertSame(pcm, ListenGuard.trimTail(pcm, 3_200, ListenGuard.TAIL_KEEP_MS, 25_600))
    }

    @Test fun noSpeechDetectedMeansNoCut() {
        val pcm = ByteArray(100_000)
        assertSame(pcm, ListenGuard.trimTail(pcm, 0))
        assertSame(pcm, ListenGuard.trimTail(pcm, 100_000))
        assertSame(pcm, ListenGuard.trimTail(pcm, 150_000))
    }

    @Test fun trimKeepsWholeSamples() {
        val pcm = ByteArray(100_000)
        assertEquals(1000, ListenGuard.trimTail(pcm, 1001, 0, 0).size)
    }

    // ---- Whisper hint and noise hallucinations
    @Test fun hintIsNeutral() {
        val p = ListenGuard.GROQ_PROMPT.lowercase()
        assertFalse(p.contains("torch"))
        assertFalse(p.contains("kholo"))
        assertFalse(p.contains("volume"))
        assertFalse(p.contains("chalu"))
    }

    @Test fun echoOfTheHintIsDropped() {
        assertEquals("", ListenGuard.stripPromptEcho(ListenGuard.GROQ_PROMPT))
        assertEquals("", ListenGuard.stripPromptEcho("Voice message for a phone assistant"))
        assertEquals("torch on", ListenGuard.stripPromptEcho("Voice message for a phone assistant called Nova. torch on"))
    }

    @Test fun realCommandsAreNeverTouched() {
        assertEquals("nova", ListenGuard.stripPromptEcho("nova"))
        assertEquals("phone", ListenGuard.stripPromptEcho("phone"))
        assertEquals("torch chalu karo", ListenGuard.stripPromptEcho("torch chalu karo"))
    }

    @Test fun noSpeechSegments() {
        assertFalse(ListenGuard.allNoSpeech(emptyList()))        // no information: keep the text
        assertTrue(ListenGuard.allNoSpeech(listOf(0.9, 0.95)))
        assertFalse(ListenGuard.allNoSpeech(listOf(0.9, 0.1)))
        assertFalse(ListenGuard.allNoSpeech(listOf(0.8)))        // exactly at the limit is not enough
    }
}
