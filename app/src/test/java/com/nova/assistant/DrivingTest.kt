package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingTest {
    private fun cmd(kind: String, arg: String = "") = Logic.Cmd(kind, arg)

    private fun msg(sender: String = "Rahul", text: String = "hello bhai", ts: Long = 1000L, canReply: Boolean = true, key: String = "k1") =
        Driving.makeMsg(key, "com.whatsapp", sender, text, ts, canReply)

    // ---- voice commands
    @Test fun modeCommands() {
        assertEquals(cmd("drive_on"), Logic.classify("driving mode on"))
        assertEquals(cmd("drive_on"), Logic.classify("nova start driving mode"))
        assertEquals(cmd("drive_off"), Logic.classify("driving mode off"))
        assertEquals(cmd("drive_off"), Logic.classify("stop driving mode"))
        assertEquals(cmd("drive_off"), Logic.classify("drive mode band karo"))
    }

    @Test fun readAndClear() {
        assertEquals(cmd("drive_read"), Logic.classify("read my messages"))
        assertEquals(cmd("drive_read"), Logic.classify("messages padho"))
        assertEquals(cmd("drive_clear"), Logic.classify("clear messages"))
    }

    @Test fun quickReplyCommands() {
        assertEquals(cmd("drive_reply", "driving"), Logic.classify("reply driving"))
        assertEquals(cmd("drive_reply", "busy"), Logic.classify("nova reply busy please"))
        assertEquals(cmd("drive_reply", "thanks"), Logic.classify("reply thank you"))
        assertEquals(cmd("drive_reply", "ok"), Logic.classify("reply okay"))
    }

    @Test fun dictatedReplyCommands() {
        assertEquals(cmd("drive_dictate"), Logic.classify("reply likho"))
        assertEquals(cmd("drive_dictate"), Logic.classify("nova jawab likho please"))
        assertEquals(cmd("drive_dictate"), Logic.classify("write reply"))
        assertEquals(cmd("drive_dictate"), Logic.classify("custom reply"))
        // free typing into the screen is untouched
        assertEquals(cmd("type", "hello"), Logic.classify("type hello"))
    }

    @Test fun dictationIsCleanedBeforeItIsReadBack() {
        assertEquals("Main 10 minute mein pahunchta hoon", Driving.cleanDictation("main 10 minute mein pahunchta hoon"))
        assertEquals("Ok bhai", Driving.cleanDictation("  [unk]  ok\n bhai [unk] "))
        assertNull(Driving.cleanDictation("   "))
        assertNull(Driving.cleanDictation("[unk] [unk]"))
        assertTrue((Driving.cleanDictation("a ".repeat(400)) ?: "").length <= Driving.MAX_DICTATION)
    }

    @Test fun unknownReplyIsNotACommand() {
        assertNull(Logic.classify("reply banana"))
        assertNull(Logic.classify("reply"))
        assertNull(Logic.classify("driving"))
    }

    @Test fun existingCommandsUnaffected() {
        assertEquals(cmd("analyze"), Logic.classify("read the screen"))
        assertEquals(cmd("battery"), Logic.classify("battery"))
    }

    // ---- messages
    @Test fun onlyAllowedAppsAreRead() {
        assertNotNull(msg())
        assertNull(Driving.makeMsg("k", "com.bank.app", "Bank", "Your balance", 1L, false))
        assertNull(Driving.makeMsg("k", "com.google.android.gm", "Mail", "hello", 1L, false))
        assertNull(msg(text = "   "))
    }

    @Test fun textIsCleaned() {
        assertEquals("see link now", Driving.cleanText("see https://example.com/a?b=1 now"))
        assertEquals("a b", Driving.cleanText("a\nb"))
        assertTrue(Driving.cleanText("x".repeat(500)).length <= Driving.MAX_TEXT)
    }

    @Test fun codesAndSecretsAreNeverRead() {
        val otp = msg(text = "Your OTP is 482913")
        assertNotNull(otp)
        assertTrue(otp?.hidden == true)
        assertEquals("", otp?.text)
        assertTrue(Driving.looksSensitive("my password is abc"))
        assertTrue(Driving.looksSensitive("code 1234"))
        assertFalse(Driving.looksSensitive("kal milte hain"))
        assertFalse(Driving.looksSensitive("call me on 9876543210"))
    }

    @Test fun introSaysSenderAndText() {
        val m = msg() ?: return assertTrue(false)
        val hi = Driving.intro(m, true, false)
        assertTrue(hi.contains("Rahul") && hi.contains("hello bhai") && hi.contains("WhatsApp"))
        assertFalse(hi.contains("reply driving"))
        assertTrue(Driving.intro(m, true, true).contains("reply driving"))
        assertTrue(Driving.intro(m, true, true).contains("reply likho"))
        assertTrue(Driving.intro(m, false, true).contains("reply driving"))
        val noReply = msg(canReply = false) ?: return assertTrue(false)
        assertFalse(Driving.intro(noReply, false, true).contains("reply driving"))
    }

    @Test fun hiddenIntroDoesNotLeakTheCode() {
        val m = msg(text = "Your OTP is 482913") ?: return assertTrue(false)
        val s = Driving.intro(m, false, true)
        assertFalse(s.contains("482913"))
        assertTrue(s.contains("did not read"))
    }

    // ---- quick replies
    @Test fun quickReplyTexts() {
        assertTrue(Driving.quickText("driving", true)?.startsWith("Main abhi drive") == true)
        assertEquals("Okay.", Driving.quickText("ok", false))
        assertNull(Driving.quickText("anything else", true))
        assertEquals("busy", Driving.quickId(listOf("busy")))
        assertNull(Driving.quickId(listOf("banana")))
    }

    // ---- inbox
    @Test fun inboxDedupesRepeatedNotifications() {
        val inbox = Driving.Inbox()
        val a = msg(ts = 1000L) ?: return assertTrue(false)
        val b = msg(ts = 1000L + 5_000L) ?: return assertTrue(false)
        val c = msg(ts = 1000L + Driving.DEDUPE_MS + 1) ?: return assertTrue(false)
        assertTrue(inbox.offer(a))
        assertFalse(inbox.offer(b))
        assertTrue(inbox.offer(c))
    }

    @Test fun inboxKeepsOnlyTheNewest() {
        val inbox = Driving.Inbox()
        for (i in 1..12) inbox.offer(msg(text = "message number $i", ts = i * 100_000L) ?: return assertTrue(false))
        assertEquals(Driving.MAX_MSGS, inbox.size())
    }

    @Test fun readsInSmallBatchesAndMarksThemRead() {
        val inbox = Driving.Inbox()
        for (i in 1..5) inbox.offer(msg(text = "m$i", ts = i * 100_000L) ?: return assertTrue(false))
        assertEquals(3, inbox.unreadBatch().size)
        assertEquals(2, inbox.unreadCount())
        assertEquals(2, inbox.unreadBatch().size)
        assertEquals(0, inbox.unreadBatch().size)
    }

    @Test fun latestReplyableSkipsMessagesWithoutAReplyButton() {
        val inbox = Driving.Inbox()
        inbox.offer(msg(text = "first", ts = 1L, key = "a") ?: return assertTrue(false))
        inbox.offer(msg(text = "second", ts = 200_000L, canReply = false, key = "b") ?: return assertTrue(false))
        assertEquals("a", inbox.latestReplyable()?.key)
        inbox.clear()
        assertNull(inbox.latestReplyable())
        assertEquals(0, inbox.size())
    }

    @Test fun drivingModeIsOffByDefaultAndShortcutsCannotUseIt() {
        assertFalse(Driving.enabled)
        for (k in listOf("drive_on", "drive_off", "drive_read", "drive_clear", "drive_reply", "drive_dictate")) {
            assertFalse("$k must not be a shortcut kind", k in Brain.SAFE_KINDS)
        }
        assertNotNull(Brain.validateSkill(Skill("bad", listOf("lights please"), listOf("reply driving"), "user")))
        assertNull(LocalBrainRules.parseOutput("reply driving"))
        assertNull(LocalBrainRules.parseOutput("reply likho"))
    }
}
