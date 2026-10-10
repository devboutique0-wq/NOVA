package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnowledgeMatcherTest {
    private fun e(id: String, vararg q: String, hi: String = "hi-$id", en: String = "en-$id") =
        KnowledgeMatcher.Entry(id, q.toList(), hi, en, emptyList())

    private val pack = listOf(
        e("water-boil", "paani kitne degree par ubalta hai", "at what temperature does water boil"),
        e("vitamin-c", "vitamin c kya karta hai", "what does vitamin c do"),
        e("vitamin-d", "vitamin d kahan se milta hai", "where do we get vitamin d"),
        e("india-capital", "bharat ki rajdhani kya hai", "what is the capital of india"),
        e("sun-star", "suraj ek tara hai kya", "is the sun a star")
    )

    private fun id(text: String) = KnowledgeMatcher.match(pack, text)?.entry?.id

    @Test fun hitsInHinglishAndEnglish() {
        assertEquals("water-boil", id("paani kis degree par ubalta hai"))
        assertEquals("water-boil", id("at what temperature does water boil"))
        assertEquals("india-capital", id("India ki capital kya hai"))
    }

    @Test fun answerLanguageFollowsFlag() {
        val m = KnowledgeMatcher.match(pack, "what is the capital of india") ?: error("no match")
        assertEquals("en-india-capital", KnowledgeMatcher.reply(m, true))
        assertEquals("hi-india-capital", KnowledgeMatcher.reply(m, false))
    }

    @Test fun noMatchIsNull() {
        assertNull(id("mujhe pizza pasand hai"))
        assertNull(id(""))
        assertNull(id("   "))
        assertNull(id("[unk]"))
    }

    @Test fun nearMissIsNull() {
        assertNull(id("paani"))
        assertNull(id("degree"))
    }

    @Test fun liveWordsAreNeverAnswered() {
        assertNull(id("aaj ki capital of india news"))
        assertNull(id("india capital today"))
        assertNull(id("water boil price now"))
    }

    @Test fun tooLongIsNull() {
        assertNull(id("what is the capital of india " + "very ".repeat(40)))
        assertNull(id("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen india capital"))
    }

    @Test fun vitaminCAndDStayApart() {
        assertEquals("vitamin-c", id("vitamin c kya karta hai"))
        assertEquals("vitamin-d", id("vitamin d kahan se milta hai"))
        assertEquals("vitamin-d", id("where do we get vitamin d"))
    }

    @Test fun tieBetweenDifferentEntriesIsNull() {
        val twin = listOf(e("one-aaa", "red apple tree"), e("two-bbb", "red apple tree"))
        assertNull(KnowledgeMatcher.match(twin, "red apple tree"))
    }

    @Test fun oneTypoInALongWordIsForgiven() {
        assertTrue(KnowledgeMatcher.within1("temperature", "temperatura"))
        assertTrue(KnowledgeMatcher.within1("abc", "abcd"))
        assertFalse(KnowledgeMatcher.within1("abcdef", "abcxyz"))
        assertTrue(KnowledgeMatcher.tokEq("rajdhani", "rajdhanii"))
        assertFalse(KnowledgeMatcher.tokEq("cat", "cut"))
    }

    @Test fun tokensDropStopWordsAndUnifySpelling() {
        val t = KnowledgeMatcher.contentTokens("Nova, paani kya hai")
        assertEquals(listOf("pani"), t)
        assertEquals(listOf("pani"), KnowledgeMatcher.contentTokens("water waters"))
    }

    @Test fun parseTinyJson() {
        val j = """{"title":"t","version":1,"entries":[
            {"id":"abc-def","q":["what is salt"],"hi":"namak","en":"salt","tags":["x"]}]}"""
        val list = KnowledgeMatcher.parse(j)
        assertEquals(1, list.size)
        assertEquals("abc-def", list[0].id)
    }

    @Test fun parseSkipsBadEntriesAndBadFile() {
        val j = """{"entries":[
            {"id":"BAD ID","q":["what is salt"],"hi":"a","en":"b"},
            {"id":"no-answer","q":["what is salt"],"hi":"a"},
            {"id":"good-one","q":["what is salt"],"hi":"a","en":"b"},
            {"id":"good-one","q":["what is sugar"],"hi":"a","en":"b"}]}"""
        val list = KnowledgeMatcher.parse(j)
        assertEquals(listOf("good-one"), list.map { it.id })
        assertTrue(KnowledgeMatcher.parse("not json at all").isEmpty())
        assertTrue(KnowledgeMatcher.parse("").isEmpty())
    }

    @Test fun realPackEveryFirstVariantFindsItself() {
        val f = listOf("src/main/assets/knowledge.json", "app/src/main/assets/knowledge.json").map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("knowledge.json not found from the test working directory", f)
        val list = KnowledgeMatcher.parse(f?.readText(Charsets.UTF_8) ?: "")
        assertTrue("pack too small: ${list.size}", list.size >= 150)
        for (en in list) {
            val m = KnowledgeMatcher.match(list, en.questions[0])
            assertNotNull("no match for first variant of ${en.id}: ${en.questions[0]}", m)
            assertEquals("first variant of ${en.id} matched another entry", en.id, m?.entry?.id)
        }
    }
}
