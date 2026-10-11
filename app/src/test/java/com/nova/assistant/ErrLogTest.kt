package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrLogTest {
    @Test fun emptyListSaysNone() {
        ErrLog.clear()
        assertEquals("RECENT ERRORS: none", ErrLog.dump(1000L))
    }

    @Test fun keepsOnlyTheNewestThirtyAndShowsNewestFirst() {
        ErrLog.clear()
        for (i in 1..40) ErrLog.add("t", "e$i", 1000L)
        assertEquals(ErrLog.MAX, ErrLog.count())
        val d = ErrLog.dump(2000L)
        assertTrue(d.contains("| e40\n"))
        assertTrue(d.contains("| e11\n"))
        assertFalse(d.contains("| e10\n"))
        assertTrue(d.indexOf("| e40\n") < d.indexOf("| e11\n"))
        ErrLog.clear()
    }

    @Test fun keyLikeTokensAreMaskedAndLongTextIsCut() {
        ErrLog.clear()
        ErrLog.add("gemini", "bad key " + "A".repeat(40) + " rejected", 1000L)
        val d = ErrLog.dump(2000L)
        assertFalse(d.contains("A".repeat(30)))
        assertTrue(d.contains("***"))
        assertTrue(ErrLog.clean("ab ".repeat(200)).length <= ErrLog.MAX_LEN + 3)
        assertEquals("one two", ErrLog.clean(" one\ntwo "))
        ErrLog.clear()
    }

    @Test fun blankMessagesAreIgnored() {
        ErrLog.clear()
        ErrLog.add("x", "   ", 1000L)
        assertEquals(0, ErrLog.count())
    }

    @Test fun agoText() {
        assertEquals("5s ago", ErrLog.ago(10_000L, 5_000L))
        assertEquals("2m ago", ErrLog.ago(120_000L, 0L))
        assertEquals("1h ago", ErrLog.ago(3_700_000L, 0L))
        assertEquals("0s ago", ErrLog.ago(0L, 5_000L))
    }
}
