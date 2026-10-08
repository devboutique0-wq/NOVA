package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private val sha = "a".repeat(64)
    private fun item(
        id: String = "skills-2026-10", type: String = "skills",
        url: String = "https://raw.githubusercontent.com/u/r/main/p.json",
        sha256: String = sha, size: Long = 1000, version: Int = 1, minApp: Int = 6
    ) = UpdateItem(id, type, "Pack", url, sha256, size, version, minApp)

    @Test fun goodItemPasses() { assertNull(Updater.validate(item(), 6, null)) }

    @Test fun rejectsBadHostsAndSchemes() {
        assertNotNull(Updater.validate(item(url = "http://raw.githubusercontent.com/x"), 6, null))
        assertNotNull(Updater.validate(item(url = "https://evil.example.com/x"), 6, null))
        assertNotNull(Updater.validate(item(url = "https://raw.githubusercontent.com@evil.com/x"), 6, null))
        assertNull(Updater.validate(item(url = "https://my.feed.host/x"), 6, "my.feed.host"))
    }

    @Test fun rejectsBadFields() {
        assertNotNull(Updater.validate(item(id = "A B"), 6, null))
        assertNotNull(Updater.validate(item(type = "script"), 6, null))
        assertNotNull(Updater.validate(item(sha256 = "xyz"), 6, null))
        assertNotNull(Updater.validate(item(size = 0), 6, null))
        assertNotNull(Updater.validate(item(size = Updater.MAX_SKILLS_BYTES + 1), 6, null))
        assertNotNull(Updater.validate(item(minApp = 99), 6, null))
    }

    @Test fun apkMustBeNewer() {
        assertNotNull(Updater.validate(item(id = "app-6", type = "apk", version = 6, size = 5000000), 6, null))
        assertNull(Updater.validate(item(id = "app-7", type = "apk", version = 7, size = 5000000), 6, null))
    }

    @Test fun versionsAndFeedUrl() {
        assertTrue(Updater.isNewer(2, null))
        assertTrue(Updater.isNewer(2, 1))
        assertFalse(Updater.isNewer(1, 1))
        assertTrue(Updater.feedUrlOk("https://raw.githubusercontent.com/u/r/main/feed.json"))
        assertFalse(Updater.feedUrlOk("http://x.com/feed.json"))
        assertFalse(Updater.feedUrlOk("file:///sdcard/feed.json"))
    }

    @Test fun sha256Known() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256Hex("abc".toByteArray()))
    }

    @Test fun sizeText() {
        assertEquals("512 B", Updater.sizeText(512))
        assertEquals("2 KB", Updater.sizeText(2048))
        assertEquals("1.0 MB", Updater.sizeText(1024L * 1024))
    }

    @Test fun updateCheckPhrases() {
        assertEquals(Logic.Cmd("update_check"), Logic.classify("check updates"))
        assertEquals(Logic.Cmd("update_check"), Logic.classify("kuch naya hai"))
    }
}
