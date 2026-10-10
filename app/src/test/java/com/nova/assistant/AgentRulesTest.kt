package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRulesTest {
    private val own = "com.nova.assistant"

    private fun node(i: Int, text: String, desc: String = "", editable: Boolean = false) =
        AgentRules.Node(i, text, desc, "Button", true, editable, false)

    @Test fun ownersSentenceIsATask() {
        assertTrue(AgentRules.isTask("gallery kholo aur 5th photo select kar ke chat gpt se edit karwao"))
        assertTrue(AgentRules.isTask("gallry open kro or 5th photo select kr ke chat gpt sa edit krwao"))
        assertTrue(AgentRules.isTask("nova gemini se meri last photo ko edit karwao"))
        assertTrue(AgentRules.isTask("kaam chatgpt kholo aur usse ek joke poochho"))
        assertTrue(AgentRules.isTask("gallery me 3rd photo chuno"))
        assertTrue(AgentRules.isTask("chatgpt open karo aur usse ek banner banwao"))
        assertTrue(AgentRules.isTask("nova gemini se ek logo generate karo"))
        assertTrue(AgentRules.isAiGen("chatgpt open karo aur usse ek banner banwao"))
        assertFalse(AgentRules.isAiGen("gallery kholo chatgpt se photo edit karwao"))
        assertFalse(AgentRules.isAiGen("open chatgpt"))
        assertTrue(AgentRules.isTask("youtube kholo aur lofi song chalao"))
        assertTrue(AgentRules.isTask("nova open instagram and search cricket news"))
        assertTrue(AgentRules.isOpenDo("youtube kholo aur lofi song chalao"))
        assertFalse(AgentRules.isOpenDo("open youtube"))
        assertFalse(AgentRules.isOpenDo("open youtube aur open gmail"))
        assertFalse(AgentRules.isOpenDo("gallery kholo aur photo chuno"))
    }

    @Test fun oneStepCommandsAreNeverTaken() {
        assertFalse(AgentRules.isTask("battery kitni hai"))
        assertFalse(AgentRules.isTask("open chatgpt"))
        assertFalse(AgentRules.isTask("gallery kholo"))
        assertFalse(AgentRules.isTask("torch on karo"))
        assertFalse(AgentRules.isTask("auto rotate on kar do"))
        assertFalse(AgentRules.isTask("scroll down"))
        assertFalse(AgentRules.isTask(""))
    }

    @Test fun taskTextDropsThePrefix() {
        assertEquals("chatgpt kholo aur joke poochho", AgentRules.taskText("kaam chatgpt kholo aur joke poochho"))
        assertEquals("gallery me 3rd photo chuno", AgentRules.taskText("gallery me 3rd photo chuno"))
    }

    @Test fun parseReadsOneAction() {
        val a = AgentRules.parse("{\"act\":\"tap\",\"i\":12,\"note\":\"photo chun raha hu\"}")
        assertNotNull(a)
        assertEquals("tap", a?.act)
        assertEquals(12, a?.index)
        assertEquals("photo chun raha hu", a?.note)
    }

    @Test fun parseToleratesFencesAndStringIndex() {
        val a = AgentRules.parse("```json\n{\"act\":\"long_tap\",\"i\":\"3\"}\n```")
        assertEquals("long_tap", a?.act)
        assertEquals(3, a?.index)
    }

    @Test fun parseRejectsBadAnswers() {
        assertNull(AgentRules.parse("I will tap the button"))
        assertNull(AgentRules.parse("{\"act\":\"format_disk\"}"))
        assertNull(AgentRules.parse("{\"act\":\"tap\"}"))
        assertNull(AgentRules.parse("{\"act\":\"type\",\"text\":\"\"}"))
        assertNull(AgentRules.parse("{\"act\":\"open_app\"}"))
        assertNull(AgentRules.parse("{not json}"))
    }

    @Test fun parseClampsWaitAndDirection() {
        assertEquals(12, AgentRules.parse("{\"act\":\"wait\",\"sec\":900}")?.sec)
        assertEquals("down", AgentRules.parse("{\"act\":\"scroll\",\"dir\":\"sideways\"}")?.dir)
    }

    @Test fun payAndSecurityLabelsAreDenied() {
        val a = AgentRules.Action("tap", 0)
        for (label in listOf("Buy now", "Upgrade to Plus", "Subscribe", "Pay", "Enter password", "Transfer money")) {
            val c = AgentRules.check(a, node(0, label), "com.openai.chatgpt", own)
            assertEquals(label, AgentRules.Verdict.DENY, c.v)
        }
    }

    @Test fun sendIsFreeOnlyInsideAiApps() {
        val a = AgentRules.Action("tap", 0)
        val send = node(0, "", "Send message")
        assertEquals(AgentRules.Verdict.OK, AgentRules.check(a, send, "com.openai.chatgpt", own).v)
        assertEquals(AgentRules.Verdict.OK, AgentRules.check(a, send, "com.google.android.apps.bard", own).v)
        assertEquals(AgentRules.Verdict.CONFIRM, AgentRules.check(a, send, "com.whatsapp", own).v)
    }

    @Test fun deleteAndPostAlwaysAskEvenInAiApps() {
        val a = AgentRules.Action("tap", 0)
        assertEquals(AgentRules.Verdict.CONFIRM, AgentRules.check(a, node(0, "Delete chat"), "com.openai.chatgpt", own).v)
        assertEquals(AgentRules.Verdict.CONFIRM, AgentRules.check(a, node(0, "Post"), "com.openai.chatgpt", own).v)
    }

    @Test fun protectedScreensAreRefused() {
        val a = AgentRules.Action("tap", 0)
        assertEquals(AgentRules.Verdict.DENY, AgentRules.check(a, node(0, "Allow"), "com.google.android.permissioncontroller", own).v)
        assertEquals(AgentRules.Verdict.DENY, AgentRules.check(a, node(0, "OK"), "com.android.settings", own).v)
        assertEquals(AgentRules.Verdict.DENY, AgentRules.check(a, node(0, "OK"), own, own).v)
        assertEquals(AgentRules.Verdict.DENY, AgentRules.check(a, node(0, "OK"), "", own).v)
    }

    @Test fun missingItemAndBadTypingAreDenied() {
        assertEquals(AgentRules.Verdict.DENY, AgentRules.check(AgentRules.Action("tap", 9), null, "com.openai.chatgpt", own).v)
        val t = AgentRules.Action("type", 0, "hello")
        assertEquals(AgentRules.Verdict.DENY, AgentRules.check(t, node(0, "Photos"), "com.openai.chatgpt", own).v)
        assertEquals(AgentRules.Verdict.OK, AgentRules.check(t, node(0, "Ask anything", editable = true), "com.openai.chatgpt", own).v)
    }

    @Test fun navigationIsAlwaysAllowed() {
        for (act in listOf("back", "home", "scroll", "wait", "done", "fail", "ask")) {
            assertEquals(AgentRules.Verdict.OK, AgentRules.check(AgentRules.Action(act), null, "", own).v)
        }
    }

    @Test fun stuckNeedsThreeSameActionsOnTheSameScreen() {
        assertTrue(AgentRules.stuck(listOf("tap5", "tap5", "tap5"), listOf(7, 7, 7)))
        assertFalse(AgentRules.stuck(listOf("tap5", "tap5", "tap5"), listOf(7, 8, 8)))
        assertFalse(AgentRules.stuck(listOf("tap5", "tap6", "tap5"), listOf(7, 7, 7)))
        assertFalse(AgentRules.stuck(listOf("tap5"), listOf(7)))
    }

    @Test fun promptNeverContainsTypedTextAndIsBounded() {
        val nodes = (0 until 300).map { node(it, "item " + it) }
        val p = AgentRules.userPrompt("edit photo", 1, "com.openai.chatgpt", nodes, emptyList())
        assertTrue(p.contains("TASK: edit photo"))
        assertTrue(p.length < 9000)
        assertTrue(AgentRules.userPrompt("x", 1, "", emptyList(), emptyList()).contains("protected screen"))
        assertTrue(AgentRules.systemPrompt().contains("untrusted"))
        assertTrue(AgentRules.systemPrompt().contains("scroll and look again"))
        assertTrue(p.contains("more items not listed"))
    }
}
