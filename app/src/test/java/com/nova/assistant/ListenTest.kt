package com.nova.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smart end of speech, custom wake word and the unlock command. Pure JVM. */
class ListenTest {

    @After fun resetWake() { Logic.setWake(Logic.DEFAULT_WAKE) }

    // ------------------------------------------------------------ smart end of speech

    @Test fun fastEnd_completeSimpleCommands_areShort() {
        assertEquals(Logic.FAST_END_MS, Logic.fastEndMs("torch on"))
        assertEquals(Logic.FAST_END_MS, Logic.fastEndMs("torch chalu karo"))
        assertEquals(Logic.FAST_END_MS, Logic.fastEndMs("what is the battery"))
        assertEquals(Logic.FAST_END_MS, Logic.fastEndMs("volume fifty"))
        assertEquals(Logic.FAST_END_MS, Logic.fastEndMs("go home"))
        assertEquals(Logic.FAST_END_MS, Logic.fastEndMs("brightness up"))
        assertEquals(Logic.FAST_END_MS, Logic.fastEndMs("pause the music"))
        assertEquals(Logic.FAST_END_MS, Logic.fastEndMs("unlock phone"))
    }

    @Test fun fastEnd_openApp_waitsLonger() {
        assertEquals(Logic.FAST_END_OPEN_APP_MS, Logic.fastEndMs("open youtube"))
        assertTrue(Logic.FAST_END_OPEN_APP_MS > Logic.FAST_END_MS)
    }

    @Test fun fastEnd_incompleteOrFreeText_keepsNormalWait() {
        assertEquals(0, Logic.fastEndMs(""))
        assertEquals(0, Logic.fastEndMs("   "))
        assertEquals(0, Logic.fastEndMs("torch"))                       // on or off? not complete
        assertEquals(0, Logic.fastEndMs("volume"))                      // no number / direction yet
        assertEquals(0, Logic.fastEndMs("open"))
        assertEquals(0, Logic.fastEndMs("tell me a story about a dragon"))
        assertEquals(0, Logic.fastEndMs("type hello how are you"))      // free text may continue
        assertEquals(0, Logic.fastEndMs("tap send"))                    // taps are never rushed
        assertEquals(0, Logic.fastEndMs("reply likho"))                 // dictation starts a separate capture
    }

    // ------------------------------------------------------------ custom wake word

    @Test fun stripWake_default_stillWorks() {
        assertEquals("open youtube", Logic.stripWake("nova open youtube"))
        assertEquals("open youtube", Logic.stripWake("hey nova open youtube"))
        assertEquals("open youtube", Logic.stripWake("the nova open youtube"))
        assertEquals("torch on", Logic.stripWake("torch on"))
    }

    @Test fun stripWake_customWord_isRemoved_andNovaToo() {
        Logic.setWake("jarvis")
        assertEquals("open youtube", Logic.stripWake("jarvis open youtube"))
        assertEquals("open youtube", Logic.stripWake("hey jarvis open youtube"))
        assertEquals("open youtube", Logic.stripWake("nova open youtube"))
        assertEquals("stop", Logic.stripWake("jarvis stop"))
    }

    @Test fun classify_withCustomWake_findsCommandsThatNeedTheStart() {
        Logic.setWake("jarvis")
        assertEquals("stop", Logic.classify("jarvis stop")?.kind)
        assertEquals("open_app", Logic.classify("jarvis open youtube")?.kind)
        assertEquals("youtube", Logic.classify("jarvis open youtube")?.arg)
        assertEquals("home", Logic.classify("jarvis go home")?.arg)
        assertEquals("settings", Logic.classify("jarvis wifi settings")?.kind)
    }

    @Test fun classify_defaultWake_unchanged() {
        assertEquals("stop", Logic.classify("nova stop")?.kind)
        assertEquals("youtube", Logic.classify("hey nova open youtube")?.arg)
        assertNull(Logic.classify("nova"))
    }

    @Test fun setWake_invalidFallsBackToNova() {
        Logic.setWake("x")
        assertEquals("open youtube", Logic.stripWake("nova open youtube"))
        Logic.setWake("hey nova")
        assertEquals("stop", Logic.stripWake("nova stop"))
    }

    // ------------------------------------------------------------ unlock command

    @Test fun unlock_isRecognised_andNeverAShortcut() {
        assertEquals("unlock", Logic.classify("unlock phone")?.kind)
        assertEquals("unlock", Logic.classify("nova unlock the phone")?.kind)
        assertEquals("global", Logic.classify("lock phone")?.kind)      // lock still locks
        assertTrue("unlock" !in Brain.SAFE_KINDS)                       // a shortcut / model can never unlock the phone
        val sk = Skill("bad", listOf("open sesame"), listOf("unlock phone"), "user")
        assertNotNull(Brain.validateSkill(sk))
    }
}
