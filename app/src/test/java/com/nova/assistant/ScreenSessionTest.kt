package com.nova.assistant

import com.nova.assistant.ScreenSession.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSessionTest {
    @Test fun intentsFromOwnerSentences() {
        assertEquals(Intent.MORE, ScreenSession.intent("aur detail"))
        assertEquals(Intent.MORE, ScreenSession.intent("thoda aur batao"))
        assertEquals(Intent.MORE, ScreenSession.intent("ye samajh nahi aaya"))
        assertEquals(Intent.IMPORTANT, ScreenSession.intent("isme important kya hai"))
        assertEquals(Intent.WHAT, ScreenSession.intent("ye kya hai"))
        assertEquals(Intent.READ, ScreenSession.intent("ise padh ke sunao"))
        assertEquals(Intent.REPLY, ScreenSession.intent("iska jawab likho"))
        assertEquals(Intent.REPLY, ScreenSession.intent("reply me kya likhu"))
        assertEquals(Intent.NEXT, ScreenSession.intent("aage kya karu"))
        assertEquals(Intent.NEXT, ScreenSession.intent("mujhe kya karna chahiye"))
        assertEquals(Intent.REFRESH, ScreenSession.intent("ab ye dekho"))
        assertEquals(Intent.REFRESH, ScreenSession.intent("dobara dekho"))
        assertEquals(Intent.PICTURE, ScreenSession.intent("picture dekho"))
        assertEquals(Intent.PICTURE, ScreenSession.intent("ye image kya hai"))
    }

    @Test fun stopWords() {
        assertEquals(Intent.STOP, ScreenSession.intent("ruko"))
        assertEquals(Intent.STOP, ScreenSession.intent("bas"))
        assertEquals(Intent.STOP, ScreenSession.intent("nahi"))
        assertEquals(Intent.STOP, ScreenSession.intent("theek hai"))
        assertEquals(Intent.STOP, ScreenSession.intent("thanks"))
    }

    @Test fun unknownIsOther() {
        assertEquals(Intent.OTHER, ScreenSession.intent(""))
        assertEquals(Intent.OTHER, ScreenSession.intent("ye message kisne bheja"))
        assertEquals(Intent.OTHER, ScreenSession.intent("haan"))
    }

    @Test fun makingPicturesIsNotAScreenPicture() {
        assertFalse(ScreenSession.intent("ek billi ki photo banao") == Intent.PICTURE)
        assertTrue(ScreenSession.isOtherFeature("ek billi ki photo banao"))
        assertTrue(ScreenSession.isOtherFeature("kaam chatgpt kholo aur ek line likh do"))
        assertFalse(ScreenSession.isOtherFeature("aur detail"))
    }

    @Test fun sessionLifetime() {
        val s = ScreenSession.start("com.whatsapp", 1000L)
        assertTrue(ScreenSession.isOpen(s, 1000L))
        assertTrue(ScreenSession.isOpen(s, 1000L + ScreenSession.IDLE_MS))
        assertFalse(ScreenSession.isOpen(s, 1000L + ScreenSession.IDLE_MS + 1))
        assertFalse(ScreenSession.isOpen(null, 1000L))
        assertFalse(ScreenSession.isOpen(s, 500L))   // clock went backwards
        var x = s
        for (i in 1..ScreenSession.MAX_TURNS) x = ScreenSession.touch(x, 2000L)
        assertTrue(ScreenSession.isLastTurn(x))
        assertFalse(ScreenSession.isOpen(x, 2000L))
        assertFalse(ScreenSession.isLastTurn(ScreenSession.touch(s, 2000L)))
        assertEquals(1, ScreenSession.touch(s, 2000L).turns)
    }

    @Test fun refreshRestartsIdleClock() {
        val s = ScreenSession.start("a", 0L)
        val r = ScreenSession.refresh(s, 80_000L)
        assertTrue(ScreenSession.isOpen(r, 80_000L + ScreenSession.IDLE_MS))
        assertEquals(0, r.turns)
    }

    @Test fun protectedScreens() {
        val own = "com.nova.assistant"
        assertTrue(ScreenSession.isProtected("com.android.settings", own))
        assertTrue(ScreenSession.isProtected("com.google.android.apps.nbu.paisa.user", own))
        assertTrue(ScreenSession.isProtected("com.sbi.bank.app", own))
        assertTrue(ScreenSession.isProtected("com.android.vending", own))
        assertTrue(ScreenSession.isProtected(own, own))
        assertTrue(ScreenSession.isProtected("", own))
        assertTrue(ScreenSession.isProtected(null, own))
        assertFalse(ScreenSession.isProtected("com.whatsapp", own))
        assertFalse(ScreenSession.isProtected("com.android.chrome", own))
    }

    @Test fun textHelpers() {
        assertEquals("a\nb", ScreenSession.cleanText(listOf(" a ", "", "a", "b")))
        assertEquals(ScreenSession.MAX_TEXT, ScreenSession.cleanText(listOf("x".repeat(9000))).length)
        assertTrue(ScreenSession.needsPicture(""))
        assertTrue(ScreenSession.needsPicture("  ok "))
        assertFalse(ScreenSession.needsPicture("Rahul: kal milte hain office me"))
    }

    @Test fun draftRules() {
        assertEquals("Theek hai, kal milte hain", ScreenSession.cleanDraft("\"Theek hai, kal milte hain\"\n"))
        assertEquals("", ScreenSession.cleanDraft("   "))
        assertEquals("", ScreenSession.cleanDraft("x".repeat(ScreenSession.MAX_DRAFT + 1)))
    }

    @Test fun promptsAreSafeAndShort() {
        val p = ScreenSession.systemPrompt("hi", "WhatsApp", "com.whatsapp", "hello")
        assertTrue(p.contains("untrusted"))
        assertTrue(p.contains("only talk"))
        assertTrue(p.contains("SCREEN TEXT:\nhello"))
        assertTrue(ScreenSession.systemPrompt("en", "A", "a", "t").contains("Reply in English"))
        assertTrue(ScreenSession.askFor(Intent.REPLY, "").contains("NO_MESSAGE"))
        assertTrue(ScreenSession.askFor(Intent.OTHER, "kisne bheja").contains("kisne bheja"))
    }

    @Test fun spokenTextsNeverNameProviders() {
        val all = listOf(
            ScreenSession.protectedAsk(false), ScreenSession.protectedAsk(true), ScreenSession.noKey(false), ScreenSession.noKey(true),
            ScreenSession.noText(false), ScreenSession.noPicture(false), ScreenSession.noReply(false), ScreenSession.askType(false, "ok")
        )
        for (t in all) for (w in listOf("gemini", "groq", "openrouter", "pollinations", "qwen")) assertFalse(t.lowercase().contains(w))
    }
}
