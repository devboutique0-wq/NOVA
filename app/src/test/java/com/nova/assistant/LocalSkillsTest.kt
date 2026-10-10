package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LocalSkillsTest {
    private val today = LocalDate.of(2026, 10, 10) // Saturday
    private fun a(text: String, lang: String = "en") = LocalSkills.answer(text, today, lang)

    // ---- numbers
    @Test fun numberWords() {
        assertEquals(250.0, LocalSkills.parseNum("two hundred fifty".split(" "))!!, 0.0)
        assertEquals(2500.0, LocalSkills.parseNum("two thousand five hundred".split(" "))!!, 0.0)
        assertEquals(100000.0, LocalSkills.parseNum("one lakh".split(" "))!!, 0.0)
        assertEquals(3.5, LocalSkills.parseNum("three point five".split(" "))!!, 1e-9)
        assertEquals(12.5, LocalSkills.parseNum(listOf("12.5"))!!, 0.0)
        assertNull(LocalSkills.parseNum(listOf("milk")))
        assertNull(LocalSkills.parseNum(emptyList()))
        assertNull(LocalSkills.parseNum("five apples".split(" ")))
    }

    // ---- calculator
    @Test fun addSubMulDiv() {
        assertEquals("5 + 6 = 11", a("five plus six"))
        assertEquals("20 - 7 = 13", a("what is twenty minus seven"))
        assertEquals("12 * 4 = 48", a("12 times 4"))
        assertEquals("48 * 2 = 96", a("forty eight into two"))
        assertEquals("10 / 4 = 2.5", a("ten divided by four"))
        assertEquals("3 + 4 = 7", a("3 + 4"))
        assertEquals("9 - 5 = 4", a("subtract five from nine"))
        assertEquals("6 * 7 = 42", a("multiply six by seven"))
        assertEquals("2 + 3 = 5", a("add two and three"))
    }
    @Test fun percentAndSquare() {
        assertEquals("15 percent of 200 = 30", a("what is fifteen percent of two hundred"))
        assertEquals("15 percent of 200 = 30", a("15% of 200"))
        assertEquals("root 144 = 12", a("square root of one hundred forty four"))
        assertEquals("9 squared = 81", a("square of nine"))
    }
    @Test fun divideByZero() {
        assertEquals("Zero se divide nahi hota", a("five divided by zero", "hi"))
        assertEquals("Zero se divide nahi ho sakta", a("five divided by zero", "en"))
    }
    @Test fun calcNeverHijacksCommands() {
        assertNull(a("add milk to the list"))
        assertNull(a("volume fifty"))
        assertNull(a("set brightness to fifty percent"))
        assertNull(a("open youtube"))
        assertNull(a("torch on"))
        assertNull(a("what is the time"))
        assertNull(a("battery"))
        assertNull(a("five plus six plus seven")) // chains are not supported, never guessed
    }

    // ---- conversion
    @Test fun conversions() {
        assertEquals("5 kg = 11.0231 lb", a("convert five kg to pounds"))
        assertEquals("10 km = 6.2137 miles", a("10 km in miles"))
        assertEquals("100 °C = 212 °F", a("100 celsius to fahrenheit"))
        assertEquals("98.6 °F = 37 °C", a("98.6 fahrenheit to celsius"))
        assertEquals("2 cup = 480 ml", a("2 cups to ml"))
        assertEquals("1 m = 100 cm", a("how many cm in a meter"))
        assertEquals("3 tablespoon = 9 teaspoon", a("how many teaspoons in 3 tablespoons"))
    }
    @Test fun conversionRejectsMismatch() {
        assertNull(a("5 kg to km"))
        assertNull(a("5 foo to bar"))
        assertNull(a("kg to lb"))
        assertNull(a("open camera in kg"))
    }

    // ---- dates (today = Saturday 10 Oct 2026)
    @Test fun dateOffsets() {
        assertEquals("45 days from today: 24 November 2026 (Tuesday)", a("what is the date after 45 days"))
        assertEquals("45 din baad: 24 November 2026 (Tuesday)", a("45 din baad kya tarikh hogi", "hi"))
        assertEquals("10 days ago: 30 September 2026 (Wednesday)", a("what date was 10 days ago"))
        assertEquals("2 weeks from today: 24 October 2026 (Saturday)", a("what is the date after two weeks"))
        assertEquals("1 months from today: 10 November 2026 (Tuesday)", a("what date is it in 1 months"))
    }
    @Test fun weekdays() {
        assertEquals("Today is 10 October 2026 (Saturday)", a("what day is it today"))
        assertEquals("Aaj 10 October 2026 (Saturday) hai", a("aaj kaun sa din hai", "hi"))
        assertEquals("Tomorrow is 11 October 2026 (Sunday)", a("what day is tomorrow"))
        assertTrue(a("kal kya din hai", "hi")!!.contains("Sunday"))
    }
    @Test fun plainTimeDateStayWithClassify() {
        assertNull(a("what is the date"))
        assertNull(a("tarikh"))
        assertNull(a("45 din baad remind karo"))
    }

    // ---- emergency + first aid
    @Test fun emergencyNumbers() {
        assertTrue(a("police ka number kya hai", "hi")!!.startsWith("Police: 100"))
        assertTrue(a("ambulance number")!!.startsWith("Ambulance: 102 or 108"))
        assertTrue(a("fire brigade number")!!.startsWith("Fire brigade: 101"))
        assertTrue(a("emergency number")!!.startsWith("Emergency: 112"))
        assertTrue(a("women helpline number")!!.contains("1091"))
        assertTrue(a("cyber crime helpline")!!.contains("1930"))
        assertNull(a("call police"))
        assertNull(a("emergency"))
    }
    @Test fun firstAid() {
        assertTrue(a("first aid for burn")!!.startsWith("Burn:"))
        assertTrue(a("nose bleeding kya karu", "hi")!!.startsWith("Naak se khoon"))
        assertTrue(a("what to do for heavy bleeding")!!.startsWith("Bleeding:"))
        assertTrue(a("snake bite first aid")!!.startsWith("Snake bite:"))
        for (q in listOf("first aid for burn", "snake bite first aid", "what to do for bleeding")) {
            val r = a(q)!!
            assertTrue(r, r.contains("not a doctor") || r.contains("doctor"))
            assertTrue(r, r.contains("112"))
        }
        assertNull(a("first aid")) // topic unknown: never guess
        assertNull(a("burn the cd"))
    }
    @Test fun noDosingEver() {
        for (q in listOf("first aid for burn", "what to do for bleeding", "snake bite first aid", "nose bleeding treatment")) {
            val r = a(q)!!.lowercase()
            assertTrue(r, !r.contains(" mg") && !r.contains("tablet") && !r.contains("paracetamol"))
        }
    }

    @Test fun junkAndLong() {
        assertNull(a(""))
        assertNull(a("   "))
        assertNull(a("x".repeat(200)))
        assertNotNull(a("5 plus 5"))
    }
}
