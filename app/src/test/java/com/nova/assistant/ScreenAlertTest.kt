package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAlertTest {
    private val own = "com.nova.assistant"

    @Test fun chatMessageSayingPaymentFailedDoesNotSpeak() {
        val lines = listOf("Rahul", "payment failed yaar", "OK", "Type a message")
        assertNull(ScreenAlert.detect("com.whatsapp", lines, own))
        assertNull(ScreenAlert.detect("org.telegram.messenger", lines, own))
        assertNull(ScreenAlert.detect("com.android.chrome", listOf("Error 404", "Retry"), own))
    }

    @Test fun realDialogsSpeak() {
        val anr = ScreenAlert.detect("android", listOf("Camera isn't responding", "Close app", "Wait"), own)
        assertNotNull(anr)
        assertEquals("isn't responding", anr!!.phrase)
        // system ANR dialog is a dialog by its wording even if the buttons are not readable
        assertNotNull(ScreenAlert.detect("android", listOf("Maps isn't responding"), own))
        val d = ScreenAlert.detect("com.example.shop", listOf("Download failed", "Retry", "Cancel"), own)
        assertNotNull(d)
        assertEquals("failed", d!!.phrase)
        assertEquals("com.example.shop|failed", d.key)
        assertNotNull(ScreenAlert.detect("com.example.app", listOf("Something went wrong", "OK"), own))
        assertNotNull(ScreenAlert.detect("com.example.app", listOf("Unfortunately, App has stopped", "OK"), own))
    }

    @Test fun noDialogNoAlert() {
        assertNull(ScreenAlert.detect("com.example.app", listOf("Everything is fine", "OK"), own))
        assertNull(ScreenAlert.detect("com.example.app", listOf("Download failed"), own))          // no button: just text
        assertNull(ScreenAlert.detect("com.example.app", listOf("Warning", "OK"), own))             // generic words dropped
        assertNull(ScreenAlert.detect("com.example.app", listOf("Network blocked", "OK"), own))
        assertNull(ScreenAlert.detect("com.example.app", listOf("Terror alert", "OK"), own))        // whole words only
        assertNull(ScreenAlert.detect("com.example.app", listOf("x".repeat(101) + " error", "OK"), own))
        assertNull(ScreenAlert.detect("com.example.app", List(30) { "line $it" } + listOf("failed", "OK"), own))   // a full page is not a dialog
        assertNull(ScreenAlert.detect("com.example.app", emptyList(), own))
    }

    @Test fun ignoredAndProtectedApps() {
        val lines = listOf("Something went wrong", "OK")
        assertNull(ScreenAlert.detect(own, lines, own))
        assertNull(ScreenAlert.detect("com.android.settings", lines, own))
        assertNull(ScreenAlert.detect("com.phonepe.app", lines, own))
        assertNull(ScreenAlert.detect("com.google.android.apps.nbu.paisa.user", lines, own))
        assertNull(ScreenAlert.detect("com.android.launcher3", lines, own))
        assertNull(ScreenAlert.detect("com.google.android.inputmethod.latin", lines, own))
        assertNull(ScreenAlert.detect("com.instagram.android", lines, own))
        assertNull(ScreenAlert.detect("", lines, own))
    }

    @Test fun gateLimits() {
        val g = ScreenAlert.Gate()
        val t0 = 1_000_000L
        assertTrue(g.allow(t0, "a|x"))
        g.record(t0, "a|x")
        assertFalse(g.allow(t0 + 30_000L, "b|y"))                                   // min gap 60 s
        assertTrue(g.allow(t0 + ScreenAlert.MIN_GAP_MS, "b|y"))
        assertFalse(g.allow(t0 + ScreenAlert.MIN_GAP_MS + 1, "a|x"))                 // same key: 10 min snooze
        assertFalse(g.allow(t0 + ScreenAlert.SNOOZE_MS - 1, "a|x"))
        assertTrue(g.allow(t0 + ScreenAlert.SNOOZE_MS, "a|x"))
        // hourly cap
        val g2 = ScreenAlert.Gate()
        for (i in 0 until ScreenAlert.HOURLY_CAP) {
            val t = t0 + i * ScreenAlert.SNOOZE_MS
            assertTrue(g2.allow(t, "k$i"))
            g2.record(t, "k$i")
        }
        val t4 = t0 + ScreenAlert.HOURLY_CAP * ScreenAlert.SNOOZE_MS
        assertFalse(g2.allow(t4, "other"))                                           // 4th within the hour
        assertTrue(g2.allow(t0 + ScreenAlert.HOUR_MS + ScreenAlert.SNOOZE_MS, "other"))
    }

    @Test fun spokenTextAsksNeverInstructs() {
        val h = ScreenAlert.Hit("android", "isn't responding", "Camera isn't responding")
        val hi = ScreenAlert.question(h, "Camera", false)
        assertTrue(hi.contains("Detail bolun? Haan bolo."))
        assertFalse(hi.lowercase().contains("say analyze"))
        assertFalse(hi.lowercase().contains("screen alert"))
        assertTrue(ScreenAlert.question(h, "Camera", true).contains("Say yes"))
    }
}
