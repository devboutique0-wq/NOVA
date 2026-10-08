package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTest {
    private val own = "com.nova.assistant"

    @Test fun classifyScroll() {
        assertEquals(Logic.Cmd("scroll", "down"), Logic.classify("scroll down"))
        assertEquals(Logic.Cmd("scroll", "up"), Logic.classify("nova scroll up please"))
        assertEquals(Logic.Cmd("scroll", "left"), Logic.classify("swipe left"))
        assertEquals(Logic.Cmd("scroll", "down"), Logic.classify("neeche scroll karo"))
    }

    @Test fun classifyTap() {
        assertEquals(Logic.Cmd("tap", "send"), Logic.classify("tap send"))
        assertEquals(Logic.Cmd("tap", "search"), Logic.classify("click on search"))
        assertEquals(Logic.Cmd("tap", "search"), Logic.classify("search button dabao"))
    }

    @Test fun classifyTypeKeepsEveryWord() {
        assertEquals(Logic.Cmd("type", "hello how are you to the man"), Logic.classify("type hello how are you to the man"))
        assertEquals(Logic.Cmd("type", "battery"), Logic.classify("type battery"))
    }

    @Test fun existingCommandsStillWork() {
        assertEquals(Logic.Cmd("battery"), Logic.classify("battery"))
        assertEquals(Logic.Cmd("global", "home"), Logic.classify("home"))
        assertEquals(Logic.Cmd("open_app", "youtube"), Logic.classify("open youtube"))
        assertEquals(Logic.Cmd("torch_on"), Logic.classify("torch on"))
    }

    @Test fun scrollWithoutDirectionIsNotACommand() {
        assertNull(Logic.classify("scroll"))
    }

    @Test fun riskyLabels() {
        assertTrue(Control.isRiskyLabel("send"))
        assertTrue(Control.isRiskyLabel("Pay now"))
        assertTrue(Control.isRiskyLabel("delete"))
        assertFalse(Control.isRiskyLabel("search"))
        assertFalse(Control.isRiskyLabel("settings"))
    }

    @Test fun blockedPlaces() {
        assertTrue(Control.blocked(null, own, "tap"))
        assertTrue(Control.blocked("", own, "scroll"))
        assertTrue(Control.blocked("com.android.settings", own, "tap"))
        assertTrue(Control.blocked("com.google.android.packageinstaller", own, "tap"))
        assertTrue(Control.blocked("com.google.android.permissioncontroller", own, "type"))
        assertTrue(Control.blocked("com.phonepe.app", own, "tap"))
        assertTrue(Control.blocked("com.sbi.mybank", own, "tap"))
        assertTrue(Control.blocked(own, own, "tap"))
        assertFalse(Control.blocked("com.android.settings", own, "scroll"))
        assertFalse(Control.blocked("com.whatsapp", own, "tap"))
    }

    @Test fun matching() {
        assertEquals(2, Control.matchScore("Send", "send"))
        assertEquals(1, Control.matchScore("Send message", "send"))
        assertEquals(0, Control.matchScore("Sender", "send"))
        assertEquals(0, Control.matchScore("", "send"))
    }

    @Test fun directionsAndPaths() {
        assertEquals("down", Control.direction(listOf("scroll", "neeche")))
        assertNull(Control.direction(listOf("scroll")))
        val p = Control.swipePath("down") ?: throw AssertionError("no path")
        assertTrue(p[1] > p[3])           // finger moves up the screen
        assertNull(Control.swipePath("diagonal"))
    }

    @Test fun cleanTyped() {
        assertEquals("hi there", Control.cleanTyped("  hi there \n"))
        assertEquals(500, Control.cleanTyped("a".repeat(900)).length)
    }
}
