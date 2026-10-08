package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quiet replies, noise guard, and the wake word staying strict. */
class QuietTest {
    @Test fun quietCommands() {
        assertEquals(Logic.Cmd("quiet_on"), Logic.classify("quiet mode on"))
        assertEquals(Logic.Cmd("quiet_on"), Logic.classify("nova voice replies off"))
        assertEquals(Logic.Cmd("quiet_off"), Logic.classify("quiet mode off"))
        assertEquals(Logic.Cmd("quiet_off"), Logic.classify("voice replies on please"))
        assertEquals(Logic.Cmd("stop"), Logic.classify("quiet"))
    }

    @Test fun silentByDefaultButConfirmationsAndDrivingAreSpoken() {
        assertFalse(Logic.speakReply(true, false, "torch_on"))
        assertFalse(Logic.speakReply(true, false, "battery"))
        assertFalse(Logic.speakReply(true, false, "cloud"))
        assertTrue(Logic.speakReply(true, true, "whatsapp"))        // a yes/no question is always spoken
        assertTrue(Logic.speakReply(true, false, "drive_read"))
        assertTrue(Logic.speakReply(true, false, "drive_dictate"))
        assertTrue(Logic.speakReply(true, false, "analyze"))
        assertTrue(Logic.speakReply(false, false, "torch_on"))      // quiet mode off: everything is spoken
    }

    @Test fun noiseIsNeverARealCommand() {
        assertTrue(Logic.isJunk(""))
        assertTrue(Logic.isJunk("[unk]"))
        assertTrue(Logic.isJunk("the"))
        assertTrue(Logic.isJunk("uh huh"))
        assertTrue(Logic.isJunk("a it"))
        assertFalse(Logic.isJunk("torch on"))
        assertFalse(Logic.isJunk("what is the weather"))
    }

    @Test fun wakeWordIsStillWholeWord() {
        assertTrue(Logic.heardWake("""{"partial": "nova"}""", "nova"))
        assertFalse(Logic.heardWake("""{"partial": "innovate"}""", "nova"))
        assertFalse(Logic.heardWake("""{"partial": "[unk]"}""", "nova"))
    }

    @Test fun newConstantsAreSane() {
        assertTrue(Logic.WAKE_COOLDOWN_MS in 500L..5000L)
        assertTrue(Logic.HISTORY_TTL_MS in 30_000L..600_000L)
        for (k in listOf("quiet_on", "quiet_off")) assertFalse(k in Brain.SAFE_KINDS)
        assertNull(LocalBrainRules.parseOutput("quiet mode on"))
    }
}
