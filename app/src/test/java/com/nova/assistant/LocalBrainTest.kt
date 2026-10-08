package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBrainTest {

    @Test fun acceptsOneKnownSafeCommand() {
        assertEquals("torch on", LocalBrainRules.parseOutput("torch on"))
        assertEquals("battery", LocalBrainRules.parseOutput("  Battery.  "))
        assertEquals("torch off", LocalBrainRules.parseOutput("\"torch off\""))
        assertEquals("volume up", LocalBrainRules.parseOutput("Command: volume up"))
    }

    @Test fun acceptsTapButItStillNeedsAYes() {
        val c = Logic.classify(LocalBrainRules.parseOutput("tap search") ?: "")
        assertEquals(LocalBrainRules.TAP_KIND, c?.kind)
        assertTrue(LocalBrainRules.accepted(Logic.Cmd("tap", "send")))
    }

    @Test fun refusesNoneNullAndEmpty() {
        assertNull(LocalBrainRules.parseOutput(null))
        assertNull(LocalBrainRules.parseOutput(""))
        assertNull(LocalBrainRules.parseOutput("NONE"))
        assertNull(LocalBrainRules.parseOutput("unknown"))
    }

    @Test fun refusesProseAndSeveralLines() {
        assertNull(LocalBrainRules.parseOutput("Sure! I will turn the torch on for you"))
        assertNull(LocalBrainRules.parseOutput("torch on\nvolume mute"))
        assertNull(LocalBrainRules.parseOutput("torch on\n\ntap send"))
    }

    @Test fun neverAcceptsTypeAnalyzeUpdateStopOrMonitor() {
        assertNull(LocalBrainRules.parseOutput("type my password is 1234"))
        assertNull(LocalBrainRules.parseOutput("check updates"))
        assertNull(LocalBrainRules.parseOutput("monitor on"))
        assertNull(LocalBrainRules.parseOutput("analyze screen"))
        assertFalse(LocalBrainRules.accepted(Logic.Cmd("type", "hello")))
        assertFalse(LocalBrainRules.accepted(Logic.Cmd("update_check")))
        assertFalse(LocalBrainRules.accepted(Logic.Cmd("skill", "x")))
        assertFalse(LocalBrainRules.accepted(Logic.Cmd("stop")))
    }

    @Test fun refusesTooLongOutput() {
        assertNull(LocalBrainRules.parseOutput("torch on " + "x".repeat(200)))
    }

    @Test fun promptContainsTheUserWordsOnOneLine() {
        val p = LocalBrainRules.buildPrompt("make it\ndark\r\nplease")
        assertTrue(p.endsWith("Request: make it dark please\nCommand:"))
        assertTrue(p.contains("NONE"))
    }

    @Test fun promptCutsVeryLongInput() {
        val p = LocalBrainRules.buildPrompt("a".repeat(1000))
        assertTrue(p.length < 2500)
    }

    @Test fun directKindsAreExactlyTheShortcutKinds() {
        assertEquals(Brain.SAFE_KINDS, LocalBrainRules.DIRECT_KINDS)
    }
}
