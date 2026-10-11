package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part 3: silent turn. Pure rules only. */
class SilentRulesTest {
    @Test fun trailingPhraseIsCutAndFlagged() {
        val a = SilentRules.extract("torch jala do chup rehna")
        assertTrue(a.silent); assertEquals("torch jala do", a.text); assertFalse(a.fillerOnly)
        val b = SilentRules.extract("volume kam karo aur chup rehna")
        assertTrue(b.silent); assertEquals("volume kam karo", b.text)
        val c = SilentRules.extract("brightness full kar do, bina bole.")
        assertTrue(c.silent); assertEquals("brightness full kar do", c.text)
        val d = SilentRules.extract("open camera and don't speak")
        assertTrue(d.silent); assertEquals("open camera", d.text)
        val e = SilentRules.extract("torch on karo silently")
        assertTrue(e.silent); assertEquals("torch on karo", e.text)
    }

    @Test fun leadingPhraseIsCut() {
        val a = SilentRules.extract("chup rehna aur torch jala do")
        assertTrue(a.silent); assertEquals("torch jala do", a.text)
        val b = SilentRules.extract("chupchap volume badha do")
        assertTrue(b.silent); assertEquals("volume badha do", b.text)
        val c = SilentRules.extract("stay quiet, open camera")
        assertTrue(c.silent); assertEquals("open camera", c.text)
    }

    @Test fun devanagariPhraseWorks() {
        val a = SilentRules.extract("torch jala do, चुप रहना")
        assertTrue(a.silent); assertEquals("torch jala do", a.text)
    }

    @Test fun plainSentencesAndBareStopWordsAreUntouched() {
        for (s in listOf("torch jala do", "chup", "quiet", "stop", "volume kam karo", "bolo time kya hua", "mat bolo", "")) {
            val r = SilentRules.extract(s)
            assertFalse(s, r.silent); assertEquals(s, r.text)
        }
        // the phrase alone leaves no order: the old meaning stays
        for (s in listOf("chup rehna", "stay quiet", "bina bole")) {
            val r = SilentRules.extract(s)
            assertFalse(s, r.silent); assertEquals(s, r.text)
        }
    }

    @Test fun messageTextIsNeverCutBySpace() {
        for (s in listOf("whatsapp par rahul ko bhejo ki tum chup rehna", "message bhejo mummy ko ki bolna mat", "sms likho hello chup rehna")) {
            val r = SilentRules.extract(s)
            assertFalse(s, r.silent); assertEquals(s, r.text)
        }
        // with a clear separator the owner means silence for this order
        val ok = SilentRules.extract("whatsapp par rahul ko hello bhejo, chup rehna")
        assertTrue(ok.silent); assertEquals("whatsapp par rahul ko hello bhejo", ok.text)
    }

    @Test fun finishThatJobStyleSentenceIsFillerOnly() {
        val a = SilentRules.extract("ye kaam pura karo, chup rehna")
        assertTrue(a.silent); assertTrue(a.fillerOnly)
        val b = SilentRules.extract("kaam complete karo chup rehna")
        assertTrue(b.silent); assertTrue(b.fillerOnly)
        assertFalse(SilentRules.extract("torch jala do chup rehna").fillerOnly)
        assertFalse(SilentRules.isFillerOnly("karo"))   // no word that points to a job
        assertFalse(SilentRules.isFillerOnly(""))
    }

    @Test fun waitingQuestionIsAlwaysSpoken() {
        val o = SilentRules.present(true, false, listOf("torch"), "Message bhejun? Haan ya nahi bolo.", "done")
        assertTrue(o.speak); assertEquals("Message bhejun? Haan ya nahi bolo.", o.show)
        val f = SilentRules.present(true, true, emptyList(), "q", "done")
        assertTrue(f.speak)
    }

    @Test fun failureShowsTheRealTextNeverDone() {
        val o = SilentRules.present(false, true, listOf("torch"), "Is phone mein flash nahi mila", "Kaam complete ho gaya")
        assertFalse(o.speak); assertEquals("Is phone mein flash nahi mila", o.show)
    }

    @Test fun doneIsShownOnlyForConfirmedKinds() {
        assertEquals("Kaam complete ho gaya", SilentRules.present(false, false, listOf("torch_on"), "x", "Kaam complete ho gaya").show)
        assertEquals("Kaam complete ho gaya", SilentRules.present(false, false, listOf("volume", "brightness"), "x", "Kaam complete ho gaya").show)
        // camera / alarm / media give no real confirmation: the tool's own words are shown
        assertEquals("Camera khul gaya", SilentRules.present(false, false, listOf("camera"), "Camera khul gaya", "Kaam complete ho gaya").show)
        assertEquals("r", SilentRules.present(false, false, listOf("torch_on", "alarm"), "r", "d").show)
        // an answer with no action (battery, chat) is shown as is
        assertEquals("80 percent", SilentRules.present(false, false, emptyList(), "80 percent", "d").show)
        assertFalse(SilentRules.present(false, false, emptyList(), "80 percent", "d").speak)
    }

    @Test fun carryOnlyForTheOwnQuestionAndExpires() {
        assertFalse(SilentRules.carryValid(0L, 1000L, 60000L))
        assertTrue(SilentRules.carryValid(1000L, 2000L, 60000L))
        assertFalse(SilentRules.carryValid(1000L, 70000L, 60000L))
        assertFalse(SilentRules.carryValid(5000L, 1000L, 60000L))   // clock went back
    }

    @Test fun existingStopAndQuietCommandsStillWork() {
        assertEquals(Logic.Cmd("stop"), Logic.classify("quiet"))
        assertEquals(Logic.Cmd("quiet_on"), Logic.classify("quiet mode on"))
    }
}
