package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HindiRomanTest {
    private fun r(s: String) = HindiRoman.toRoman(s)

    @Test fun detectsDevanagari() {
        assertTrue(HindiRoman.hasDevanagari("बैटरी"))
        assertTrue(HindiRoman.hasDevanagari("battery कितनी"))
        assertFalse(HindiRoman.hasDevanagari("battery kitni hai"))
        assertFalse(HindiRoman.hasDevanagari(""))
    }

    @Test fun latinTextIsUntouched() {
        assertEquals("torch on", r("torch on"))
        assertEquals("", r(""))
        assertEquals("", r("   "))
    }

    @Test fun commandWordsFromTheTable() {
        assertEquals("torch chalu karo", r("टॉर्च चालू करो"))
        assertEquals("torch band karo", r("टॉर्च बंद करो"))
        assertEquals("battery", r("बैटरी"))
        assertEquals("volume up", r("वॉल्यूम अप"))
        assertEquals("nova torch chalu karo", r("नोवा टॉर्च चालू करो"))
    }

    @Test fun ruleTransliterationOfOtherWords() {
        assertEquals("pani kitne digri par ubalta hai", r("पानी कितने डिग्री पर उबलता है"))
        assertEquals("bharat ki rajdhani kya hai", r("भारत की राजधानी क्या है"))
        assertEquals("suraj ek tara hai kya", r("सूरज एक तारा है क्या"))
        assertEquals("dil ki dharkan kitni hoti hai", r("दिल की धड़कन कितनी होती है"))
        assertEquals("jaldi", r("जल्दी"))
        assertEquals("pyar", r("प्यार"))
        assertEquals("samajhna", r("समझना"))
        assertEquals("kamal", r("कमल"))
        assertEquals("skul", r("स्कूल"))
    }

    @Test fun vitaminCAndDKeepTheirLetter() {
        assertEquals("vitamin c kya karta hai", r("विटामिन सी क्या करता है"))
        assertEquals("vitamin d kahan se milta hai", r("विटामिन डी कहाँ से मिलता है"))
    }

    @Test fun digitsPunctuationAndNukta() {
        assertEquals("volume 50 karo", r("वॉल्यूम ५० करो"))
        assertEquals("bharat ki rajdhani kya hai", r("भारत की राजधानी क्या है।"))
        assertEquals("20 20 20 niyam kya hai", r("20 20 20 नियम क्या है"))
        assertEquals(r("आवाज़ कम करो"), r("आवाज कम करो"))
    }

    @Test fun neverThrowsOnOddInput() {
        for (s in listOf("्", "ा", "ँ", "क्", "\u200d", "क््क", "ऽ", "🙂 क", "ॐ", "।।।")) {
            assertNotNull(r(s))
        }
    }

    @Test fun yesAndNoStayStrict() {
        assertEquals(true, Logic.parseAnswer(r("हाँ")))
        assertEquals(false, Logic.parseAnswer(r("नहीं")))
        assertEquals(false, Logic.parseAnswer(r("हाँ नहीं")))
        assertNull(Logic.parseAnswer(r("शायद")))
        assertNull(Logic.parseAnswer(r("सबको भेज दो अभी")))
    }

    @Test fun hindiCommandsReachTheNormalClassifier() {
        assertEquals(Logic.classify("torch off"), Logic.classify(r("टॉर्च बंद करो")))
        assertEquals(Logic.classify("torch on"), Logic.classify(r("टॉर्च ऑन")))
        assertEquals(Logic.classify("battery"), Logic.classify(r("बैटरी")))
        assertEquals(Logic.classify("volume up"), Logic.classify(r("वॉल्यूम अप")))
        assertNotNull(Logic.classify(r("बैटरी")))
    }

    @Test fun devanagariIsNoLongerJunkAfterConversion() {
        assertTrue(Logic.isJunk("बैटरी"))
        assertFalse(Logic.isJunk(r("बैटरी")))
    }

    @Test fun hindiQuestionsFindKnowledgeAnswers() {
        val list = listOf(
            KnowledgeMatcher.Entry("water-boil", listOf("paani kitne degree par ubalta hai"), "hi", "en", emptyList()),
            KnowledgeMatcher.Entry("vitamin-c", listOf("vitamin c kya karta hai"), "hi", "en", emptyList()),
            KnowledgeMatcher.Entry("vitamin-d", listOf("vitamin d kahan se milta hai"), "hi", "en", emptyList()),
            KnowledgeMatcher.Entry("india-capital", listOf("bharat ki rajdhani kya hai"), "hi", "en", emptyList())
        )
        fun id(s: String) = KnowledgeMatcher.match(list, r(s))?.entry?.id
        assertEquals("water-boil", id("पानी कितने डिग्री पर उबलता है"))
        assertEquals("vitamin-c", id("विटामिन सी क्या करता है"))
        assertEquals("vitamin-d", id("विटामिन डी कहाँ से मिलता है"))
        assertEquals("india-capital", id("भारत की राजधानी क्या है"))
        assertNull(id("आज भारत की राजधानी क्या है"))   // live word "aaj": never answered from the static pack
    }

    @Test fun hindiQuestionsFindRealPackAnswers() {
        val f = listOf("src/main/assets/knowledge.json", "app/src/main/assets/knowledge.json").map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("knowledge.json not found from the test working directory", f)
        val list = KnowledgeMatcher.parse(f?.readText(Charsets.UTF_8) ?: "")
        fun id(s: String) = KnowledgeMatcher.match(list, r(s))?.entry?.id
        assertEquals("sci-water-boil", id("पानी कितने डिग्री पर उबलता है"))
        assertEquals("sci-sun-star", id("सूरज एक तारा है क्या"))
        assertEquals("india-capital", id("भारत की राजधानी क्या है"))
        assertEquals("sci-vitamin-c", id("विटामिन सी क्या करता है"))
    }
}
