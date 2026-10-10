package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FIXFREE2: guards the pure helpers of the free image fallback. */
class FreeImageRulesTest {
    @Test fun promptIsCleaned() {
        assertEquals("a red rose", FreeImageRules.cleanPrompt("  a\n red\t rose  "))
        assertEquals("", FreeImageRules.cleanPrompt(" \n\t "))
        assertEquals("a b", FreeImageRules.cleanPrompt("a\u0000b"))
        assertEquals(FreeImageRules.MAX_PROMPT, FreeImageRules.cleanPrompt("x".repeat(2000)).length)
    }

    @Test fun onlyRealImagesPass() {
        val jpg = ByteArray(20)
        jpg[0] = 0xFF.toByte()
        jpg[1] = 0xD8.toByte()
        val png = ByteArray(20)
        png[0] = 0x89.toByte()
        png[1] = 0x50.toByte()
        assertTrue(FreeImageRules.looksImage(jpg))
        assertTrue(FreeImageRules.looksImage(png))
        assertFalse(FreeImageRules.looksImage(ByteArray(20)))
        assertFalse(FreeImageRules.looksImage(ByteArray(5)))
        assertFalse(FreeImageRules.looksImage("{\"error\":\"not an image at all\"}".toByteArray()))
    }

    @Test fun onlyPlainHttpsUrlsAreFollowed() {
        assertTrue(FreeImageRules.isHttps("https://example.com/a.png"))
        assertFalse(FreeImageRules.isHttps("http://example.com/a.png"))
        assertFalse(FreeImageRules.isHttps("https://example.com@evil.com/a"))
        assertFalse(FreeImageRules.isHttps(""))
    }

    @Test fun failTextsNameTheCodeAndNeverLeakKeys() {
        assertTrue(FreeImageRules.failText("Together AI", 429).contains("429"))
        assertTrue(FreeImageRules.failText("Hugging Face", 402).contains("402"))
        assertTrue(FreeImageRules.failText("Pollinations", 599).contains("Pollinations"))
        assertFalse(FreeImageRules.failText("X", 401).contains("Bearer"))
    }

    @Test fun cooldownExpires() {
        var now = 1000L
        val c = FreeImageRules.Cooldown { now }
        assertFalse(c.blocked("a"))
        c.set("a", 500L)
        assertTrue(c.blocked("a"))
        assertFalse(c.blocked("b"))
        now = 1600L
        assertFalse(c.blocked("a"))
    }
}
