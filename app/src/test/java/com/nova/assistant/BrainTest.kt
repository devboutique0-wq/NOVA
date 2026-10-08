package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {
    private fun skill(trigger: String, vararg steps: String) =
        Skill("s-$trigger", listOf(trigger), steps.toList(), "user")

    @Test fun validSkillPasses() {
        assertNull(Brain.validateSkill(skill("good night", "volume mute", "wait 2", "torch off")))
    }

    @Test fun skillCannotHideBuiltInOrUseRiskySteps() {
        assertNotNull(Brain.validateSkill(skill("battery", "torch on")))        // hides a built-in command
        assertNotNull(Brain.validateSkill(skill("do it", "tap send")))          // tap is not allowed in skills
        assertNotNull(Brain.validateSkill(skill("do it", "type hello")))
        assertNotNull(Brain.validateSkill(skill("do it", "fly to moon")))       // not understood
        assertNotNull(Brain.validateSkill(skill("do it", "wait 99")))
        assertNotNull(Brain.validateSkill(Skill("x", listOf("good night"), List(9) { "torch on" }, "user")))
    }

    @Test fun exactAndFuzzyResolve() {
        val s = skill("good night", "torch off")
        assertEquals(s, Brain.resolve(listOf(s), "good night"))
        assertEquals(s, Brain.resolve(listOf(s), "good nights"))
        assertNull(Brain.resolve(listOf(s), "good morning"))
        assertNull(Brain.resolve(listOf(s), "hi"))
    }

    @Test fun numbersNeverFuzzyMatch() {
        assertEquals(0.0, Brain.similarity("volume fifty", "volume fifteen"), 0.0)
    }

    @Test fun proposalNeedsTwoPairsAndRespectsDecline() {
        val ev = ArrayList<BrainEvent>()
        ev.add(BrainEvent(1000, "torch jalao", "unknown", ""))
        ev.add(BrainEvent(5000, "torch on", "local", "torch_on"))
        assertTrue(Brain.proposals(ev, emptySet(), emptySet()).isEmpty())
        ev.add(BrainEvent(100000, "torch jalao", "unknown", ""))
        ev.add(BrainEvent(104000, "torch on", "local", "torch_on"))
        val p = Brain.proposals(ev, emptySet(), emptySet())
        assertEquals(1, p.size)
        assertEquals("torch jalao", p[0].phrase)
        assertEquals("torch on", p[0].command)
        assertTrue(Brain.proposals(ev, emptySet(), setOf(Brain.key("torch jalao", "torch on"))).isEmpty())
        assertTrue(Brain.proposals(ev, setOf("torch jalao"), emptySet()).isEmpty())
    }

    @Test fun proposalIgnoresSlowAndUnsafePairs() {
        val ev = listOf(
            BrainEvent(0, "do it", "unknown", ""), BrainEvent(200000, "torch on", "local", "torch_on"),
            BrainEvent(300000, "do it", "unknown", ""), BrainEvent(500000, "torch on", "local", "torch_on"),
            BrainEvent(600000, "go", "unknown", ""), BrainEvent(601000, "tap send", "local", "tap"),
            BrainEvent(700000, "go", "unknown", ""), BrainEvent(701000, "tap send", "local", "tap")
        )
        assertTrue(Brain.proposals(ev, emptySet(), emptySet()).isEmpty())
    }

    @Test fun eventLogIsBounded() {
        val ev = ArrayList<BrainEvent>()
        for (i in 0 until 300) Brain.record(ev, BrainEvent(i.toLong(), "x$i", "local", "battery"))
        assertEquals(Brain.MAX_EVENTS, ev.size)
        assertEquals("x299", ev.last().heard)
    }

    @Test fun nudgeGap() {
        assertTrue(Brain.canNudge(1000, 0))
        assertTrue(!Brain.canNudge(1000, 500))
        assertTrue(Brain.canNudge(Brain.NUDGE_GAP_MS + 10, 5))
    }

    @Test fun waitParsing() {
        assertEquals(3, Brain.waitSeconds("wait 3"))
        assertNull(Brain.waitSeconds("wait 0"))
        assertNull(Brain.waitSeconds("wait three"))
    }
}
