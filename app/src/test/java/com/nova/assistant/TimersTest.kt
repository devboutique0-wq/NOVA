package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class TimersTest {
    private fun p(s: String) = Timers.parse(Logic.norm(s).split(" "))

    @Test fun timers() {
        assertEquals(Logic.Cmd("timer", "", 300), p("set a timer for five minutes"))
        assertEquals(Logic.Cmd("timer", "", 600), p("ten minute timer"))
        assertEquals(Logic.Cmd("timer", "", 90), p("timer one minute thirty seconds"))
        assertEquals(Logic.Cmd("timer", "", 3600), p("set timer for one hour"))
        assertEquals(Logic.Cmd("timer", "", 1800), p("timer half an hour"))
        assertEquals(Logic.Cmd("timer", "", 1800), p("timer aadha ghanta"))
        assertEquals(Logic.Cmd("timer", "", 120), p("do minute ka timer laga do"))
        assertEquals(Logic.Cmd("timer", "", 300), p("paanch minute ka timer"))
        assertEquals(Logic.Cmd("timer", "", 1500), p("timer twenty five minutes"))
    }
    @Test fun reminders() {
        assertEquals(Logic.Cmd("timer", "call mom", 1200), p("remind me in twenty minutes to call mom"))
        assertEquals(Logic.Cmd("timer", "chai", 600), p("das minute baad chai yaad dilao"))
    }
    @Test fun alarmsClear() {
        assertEquals(Logic.Cmd("alarm", "", 6 * 60 + 30), p("set alarm for six thirty am"))
        assertEquals(Logic.Cmd("alarm", "", 18 * 60 + 5), p("alarm six oh five pm"))
        assertEquals(Logic.Cmd("alarm", "", 6 * 60), p("subah six baje alarm"))
        assertEquals(Logic.Cmd("alarm", "", 21 * 60), p("alarm twenty one hundred".replace(" hundred", "")))
        assertEquals(Logic.Cmd("alarm", "", 22 * 60 + 30), p("alarm twenty two thirty"))
        assertEquals(Logic.Cmd("alarm", "", 0), p("alarm twelve am"))
        assertEquals(Logic.Cmd("alarm", "", 12 * 60), p("alarm twelve pm"))
        assertEquals(Logic.Cmd("alarm", "", 6 * 60 + 30), p("alarm 6 30 am"))
    }
    @Test fun alarmAmbiguousIsResolvedLater() {
        assertEquals(Logic.Cmd("alarm_any", "", 7 * 60), p("set an alarm for seven"))
        assertEquals(Logic.Cmd("alarm_any", "", 5 * 60 + 15), p("wake me up at five fifteen"))
        assertEquals(Logic.Cmd("alarm_any", "", 6 * 60), p("alarm laga do six baje"))
    }
    @Test fun resolveAny() {
        assertEquals(7 * 60, Timers.resolveAny(7 * 60, LocalTime.of(6, 0)))      // 7 AM still ahead
        assertEquals(19 * 60, Timers.resolveAny(7 * 60, LocalTime.of(8, 0)))     // 7 AM passed -> 7 PM
        assertEquals(7 * 60, Timers.resolveAny(7 * 60, LocalTime.of(21, 0)))     // both passed -> tomorrow 7 AM
        assertEquals(12 * 60 + 30, Timers.resolveAny(30, LocalTime.of(10, 0)))   // 12:30 -> noon one
    }
    @Test fun notMine() {
        assertNull(p("cancel alarm"))
        assertNull(p("alarm band karo"))
        assertNull(p("stop timer"))
        assertNull(p("open clock"))
        assertNull(p("torch on"))
        assertNull(p("what is the time"))
        assertNull(p("remind me"))                       // no duration: never guessed
        assertNull(p("timer"))
        assertNull(p("set the volume to fifty"))
        assertNull(p("alarm"))
    }
    @Test fun limits() {
        assertNull(p("timer one hundred hours"))
        assertNull(p("timer thirty hours"))
    }
    @Test fun humanText() {
        assertEquals("1 hour 30 minute", Timers.human(5400, true))
        assertEquals("20 minute", Timers.human(1200, false))
        assertEquals("6:30 AM", Timers.clock(6 * 60 + 30))
        assertEquals("12:00 AM", Timers.clock(0))
        assertEquals("12:05 PM", Timers.clock(12 * 60 + 5))
    }
}
