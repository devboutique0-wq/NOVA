package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeProvidersTest {

    private fun okBody(t: String) = "{\"choices\":[{\"message\":{\"content\":" + MiniJson.quote(t) + "}}]}"

    private fun prov(id: String, key: String?, vararg models: String) =
        FreeProvider(id, id, "https://x.test/$id", models.toList(), key)

    /** A scripted chain run: fake clock, fake network, records every call as "provider:model". */
    private class Rig(val provs: List<FreeProvider>, val keys: Map<String, String> = emptyMap()) {
        val cd = Cooldowns()
        var t = 0L
        val calls = ArrayList<String>()
        var disabled: Set<String> = emptySet()

        fun go(budget: Long = 14_000L, script: (String, String) -> NetAnswer): ChainResult =
            ProviderChain.run(provs, { keys[it] ?: "" }, { it !in disabled }, "sys", "hello", cd, { t }, budget) { p, m, _, _ ->
                calls.add(p.id + ":" + m)
                script(p.id, m)
            }
    }

    // ---------- MiniJson ----------

    @Test fun jsonParsesNestedValues() {
        val v = MiniJson.parse("{\"a\":[1,2,{\"b\":true,\"c\":null}],\"s\":\"x\"}")
        assertEquals("x", MiniJson.field(v, "s"))
        assertEquals(2.0, MiniJson.at(MiniJson.field(v, "a"), 1))
        assertEquals(true, MiniJson.field(MiniJson.at(MiniJson.field(v, "a"), 2), "b"))
    }

    @Test fun jsonReadsUnicodeEscapesAndHindi() {
        assertEquals("\u0928\u092e\u0938\u094d\u0924\u0947", MiniJson.parse("\"\\u0928\\u092e\\u0938\\u094d\\u0924\\u0947\""))
        assertEquals("\u0928\u092e\u0938\u094d\u0924\u0947", MiniJson.parse("\"\u0928\u092e\u0938\u094d\u0924\u0947\""))
    }

    @Test fun jsonMalformedReturnsNullAndNeverThrows() {
        for (bad in listOf("", "{", "{\"a\":", "[1,", "\"abc", "{\"a\" 1}", "tru", "{\"a\":1}x", "\"\\u12\"", "\"\\q\"")) {
            assertNull("should be null: $bad", MiniJson.parse(bad))
        }
    }

    @Test fun jsonDeepNestingIsRefusedNotCrashing() {
        val deep = "[".repeat(200) + "]".repeat(200)
        assertNull(MiniJson.parse(deep))
    }

    @Test fun jsonQuoteRoundTrips() {
        val s = "line1\nline2 \"quoted\" back\\slash\ttab \u0939\u093f\u0928\u094d\u0926\u0940 \u0001"
        assertEquals(s, MiniJson.parse(MiniJson.quote(s)))
    }

    // ---------- request / reply ----------

    @Test fun buildBodyIsValidJsonWithModelAndMessages() {
        val body = FreeProviders.buildBody("m-1", "be \"short\"", "kya haal hai\nbhai?")
        val v = MiniJson.parse(body)
        assertNotNull(v)
        assertEquals("m-1", MiniJson.field(v, "model"))
        val msgs = MiniJson.field(v, "messages")
        assertEquals("system", MiniJson.field(MiniJson.at(msgs, 0), "role"))
        assertEquals("be \"short\"", MiniJson.field(MiniJson.at(msgs, 0), "content"))
        assertEquals("user", MiniJson.field(MiniJson.at(msgs, 1), "role"))
        assertEquals("kya haal hai\nbhai?", MiniJson.field(MiniJson.at(msgs, 1), "content"))
    }

    @Test fun buildBodyCapsVeryLongUserText() {
        val body = FreeProviders.buildBody("m", "s", "a".repeat(5000))
        val content = MiniJson.field(MiniJson.at(MiniJson.field(MiniJson.parse(body), "messages"), 1), "content") as String
        assertEquals(FreeProviders.MAX_USER_CHARS, content.length)
    }

    @Test fun parseReplyReadsNormalAnswer() {
        assertEquals("Aasman blue hota hai.", FreeProviders.parseReply(okBody("Aasman blue hota hai.")))
    }

    @Test fun parseReplyStripsMarkdownAndThinkBlocks() {
        val r = FreeProviders.parseReply(okBody("<think>hmm\nlong</think>**Delhi** is the capital.\n\n## Done"))
        assertEquals("Delhi is the capital. Done", r)
    }

    @Test fun parseReplyRejectsErrorsEmptyAndGarbage() {
        assertNull(FreeProviders.parseReply("{\"error\":{\"message\":\"rate limit\"}}"))
        assertNull(FreeProviders.parseReply("{\"choices\":[]}"))
        assertNull(FreeProviders.parseReply(okBody("   ")))
        assertNull(FreeProviders.parseReply(okBody("<think>only thoughts</think>")))
        assertNull(FreeProviders.parseReply("<html>502</html>"))
        assertNull(FreeProviders.parseReply("{\"choices\":[{\"message\":{\"content\":null}}]}"))
    }

    @Test fun cleanCapsLongReplyOnASentenceEnd() {
        val long = "Yeh ek lamba vaakya hai jo kaafi der tak chalta hai. ".repeat(40)
        val c = FreeProviders.clean(long)
        assertTrue(c.length <= FreeProviders.MAX_REPLY_CHARS)
        assertTrue(c.endsWith("."))
    }

    // ---------- defaults ----------

    @Test fun defaultProviderOrderAndShape() {
        val p = FreeProviders.ordered()
        assertEquals(listOf("groq", "openrouter", "pollinations"), p.map { it.id })
        assertNull(p[2].keyName)
        assertEquals("groq", p[0].keyName)
        assertEquals("openrouter", p[1].keyName)
        for (x in p) {
            assertTrue(x.url.startsWith("https://"))
            assertTrue(x.models.isNotEmpty())
        }
    }

    @Test fun promptsAndFailTextsAreNeverEmpty() {
        assertTrue(FreeProviders.systemPrompt(true).isNotBlank())
        assertTrue(FreeProviders.systemPrompt(false).isNotBlank())
        assertTrue(FreeProviders.systemPrompt(true) != FreeProviders.systemPrompt(false))
        for (k in listOf("network", "quota", "key", "cooling", "slow", "model", "server", "other", "nokey")) {
            assertTrue(FreeProviders.failText(k, true).isNotBlank())
            assertTrue(FreeProviders.failText(k, false).isNotBlank())
        }
    }

    @Test fun kindsAndCooldowns() {
        assertEquals("network", FreeProviders.kindOf(598))
        assertEquals("network", FreeProviders.kindOf(599))
        assertEquals("key", FreeProviders.kindOf(401))
        assertEquals("quota", FreeProviders.kindOf(429))
        assertEquals("model", FreeProviders.kindOf(404))
        assertEquals("server", FreeProviders.kindOf(503))
        assertEquals(600_000L, FreeProviders.cooldownFor("key"))
        assertEquals(120_000L, FreeProviders.cooldownFor("quota"))
        assertEquals(0L, FreeProviders.cooldownFor("slow"))
        assertEquals("quota", FreeProviders.worse("model", "quota"))
        assertEquals("quota", FreeProviders.worse("quota", "model"))
    }

    // ---------- the chain ----------

    @Test fun firstProviderAnswersAndNoOtherIsCalled() {
        val rig = Rig(listOf(prov("a", "k", "m1"), prov("b", null, "m1")), mapOf("k" to "KEY"))
        val r = rig.go { _, _ -> NetAnswer(200, okBody("hi")) }
        assertEquals("hi", r.text)
        assertEquals("a", r.providerId)
        assertEquals(listOf("a:m1"), rig.calls)
    }

    @Test fun quotaOnAllModelsFallsToNextProviderAndCoolsTheFirst() {
        val rig = Rig(listOf(prov("a", "k", "m1", "m2"), prov("b", null, "m1")), mapOf("k" to "KEY"))
        val r = rig.go { id, _ -> if (id == "a") NetAnswer(429, "slow down") else NetAnswer(200, okBody("from b")) }
        assertEquals("from b", r.text)
        assertEquals(listOf("a:m1", "a:m2", "b:m1"), rig.calls)
        assertTrue(rig.cd.cooling("a", rig.t))
        // the next question skips "a" without calling it
        rig.calls.clear()
        val r2 = rig.go { _, _ -> NetAnswer(200, okBody("again b")) }
        assertEquals(listOf("b:m1"), rig.calls)
        assertEquals("again b", r2.text)
    }

    @Test fun cooldownEndsWithTheClock() {
        val rig = Rig(listOf(prov("a", "k", "m1"), prov("b", null, "m1")), mapOf("k" to "KEY"))
        rig.go { id, _ -> if (id == "a") NetAnswer(429, "") else NetAnswer(200, okBody("b")) }
        rig.t = 119_999L
        assertTrue(rig.cd.cooling("a", rig.t))
        rig.t = 120_001L
        assertFalse(rig.cd.cooling("a", rig.t))
        rig.calls.clear()
        rig.go { _, _ -> NetAnswer(200, okBody("a again")) }
        assertEquals(listOf("a:m1"), rig.calls)
    }

    @Test fun secondModelOfSameProviderIsTriedAfter404() {
        val rig = Rig(listOf(prov("a", "k", "old", "new"), prov("b", null, "m1")), mapOf("k" to "KEY"))
        val r = rig.go { _, m -> if (m == "old") NetAnswer(404, "no such model") else NetAnswer(200, okBody("ok $m")) }
        assertEquals("ok new", r.text)
        assertEquals("a", r.providerId)
        assertEquals(listOf("a:old", "a:new"), rig.calls)
    }

    @Test fun badKeyStopsThatProviderAtOnceAndWaitsTenMinutes() {
        val rig = Rig(listOf(prov("a", "k", "m1", "m2"), prov("b", null, "m1")), mapOf("k" to "BAD"))
        val r = rig.go { id, _ -> if (id == "a") NetAnswer(401, "bad key") else NetAnswer(200, okBody("b")) }
        assertEquals("b", r.providerId)
        assertEquals(listOf("a:m1", "b:m1"), rig.calls)
        rig.t = 599_000L
        assertTrue(rig.cd.cooling("a", rig.t))
        rig.t = 601_000L
        assertFalse(rig.cd.cooling("a", rig.t))
    }

    @Test fun serverErrorMovesToNextProviderWithoutTryingMoreModels() {
        val rig = Rig(listOf(prov("a", "k", "m1", "m2"), prov("b", null, "m1")), mapOf("k" to "KEY"))
        val r = rig.go { id, _ -> if (id == "a") NetAnswer(503, "down") else NetAnswer(200, okBody("b")) }
        assertEquals("b", r.providerId)
        assertEquals(listOf("a:m1", "b:m1"), rig.calls)
    }

    @Test fun noInternetEndsTheWholeChainImmediately() {
        val rig = Rig(listOf(prov("a", "k", "m1"), prov("b", null, "m1")), mapOf("k" to "KEY"))
        val r = rig.go { _, _ -> NetAnswer(598, "") }
        assertNull(r.text)
        assertEquals("network", r.failKind)
        assertEquals(listOf("a:m1"), rig.calls)
        // no cooldown was set: when the internet is back everything works again
        assertFalse(rig.cd.cooling("a", 0L))
    }

    @Test fun providerWithoutUserKeyIsSkippedWithoutACall() {
        val rig = Rig(listOf(prov("a", "k", "m1"), prov("b", null, "m1")), emptyMap())
        val r = rig.go { _, _ -> NetAnswer(200, okBody("keyless")) }
        assertEquals("b", r.providerId)
        assertEquals(listOf("b:m1"), rig.calls)
        assertEquals(listOf("b"), r.tried)
    }

    @Test fun disabledProviderIsNeverCalled() {
        val rig = Rig(listOf(prov("a", null, "m1"), prov("b", null, "m1")))
        rig.disabled = setOf("a")
        val r = rig.go { _, _ -> NetAnswer(200, okBody("x")) }
        assertEquals("b", r.providerId)
        assertEquals(listOf("b:m1"), rig.calls)
    }

    @Test fun okStatusWithUselessBodyCountsAsFailureAndMovesOn() {
        val rig = Rig(listOf(prov("a", null, "m1", "m2"), prov("b", null, "m1")))
        val r = rig.go { id, _ -> if (id == "a") NetAnswer(200, "{\"error\":\"limit\"}") else NetAnswer(200, okBody("real")) }
        assertEquals("real", r.text)
        assertEquals("b", r.providerId)
        assertEquals(listOf("a:m1", "a:m2", "b:m1"), rig.calls)
    }

    @Test fun everythingFailingReportsTheMostUsefulReason() {
        val rig = Rig(listOf(prov("a", null, "m1"), prov("b", null, "m1")))
        val r = rig.go { id, _ -> if (id == "a") NetAnswer(404, "") else NetAnswer(429, "") }
        assertNull(r.text)
        assertEquals("quota", r.failKind)
        assertEquals(listOf("a", "b"), r.tried)
    }

    @Test fun allProvidersCoolingReportsCoolingAndMakesNoCall() {
        val rig = Rig(listOf(prov("a", null, "m1")))
        rig.go { _, _ -> NetAnswer(429, "") }
        rig.calls.clear()
        val r = rig.go { _, _ -> NetAnswer(200, okBody("should not run")) }
        assertNull(r.text)
        assertEquals("cooling", r.failKind)
        assertTrue(rig.calls.isEmpty())
    }

    @Test fun timeBudgetStopsTheChain() {
        val rig = Rig(listOf(prov("a", null, "m1"), prov("b", null, "m1"), prov("c", null, "m1")))
        val r = rig.go(budget = 5_000L) { _, _ ->
            rig.t += 6_000L
            NetAnswer(599, "timeout")
        }
        assertNull(r.text)
        // a timed out (599 = network kind) after 6 s: the budget is gone, b and c are never called
        assertEquals(listOf("a:m1"), rig.calls)
        assertTrue(r.failKind == "network" || r.failKind == "slow")
    }

    @Test fun noProviderAtAllGivesNokey() {
        val rig = Rig(listOf(prov("a", "k", "m1")), emptyMap())
        val r = rig.go { _, _ -> NetAnswer(200, okBody("x")) }
        assertNull(r.text)
        assertEquals("nokey", r.failKind)
        assertTrue(rig.calls.isEmpty())
    }

    // ---------- part 1B: pure decisions used by NovaService ----------

    @Test fun chainIsUsedOnlyForRealQuestionsWithTheSwitchOn() {
        assertTrue(FreeProviders.canUseChain(true, "why is the sky blue"))
        assertFalse(FreeProviders.canUseChain(false, "why is the sky blue"))
        assertFalse(FreeProviders.canUseChain(true, ""))
        assertFalse(FreeProviders.canUseChain(true, "   "))
        assertFalse(FreeProviders.canUseChain(true, "the"))
        assertFalse(FreeProviders.canUseChain(true, "uh hm"))
    }

    @Test fun crisisWordsNeverGoOnlineAndGetTheSafeAnswer() {
        val en = FreeProviders.chainPreAnswer("i want to die", true)
        assertNotNull(en)
        assertTrue(en.orEmpty().contains("14416"))
        assertTrue(en.orEmpty().contains("112"))
        val hi = FreeProviders.chainPreAnswer("main marna chahta hoon", false)
        assertNotNull(hi)
        assertTrue(hi.orEmpty().contains("14416"))
    }

    @Test fun liveDataQuestionsGetAnHonestAnswerThatDoesNotClaimOffline() {
        for (q in listOf("what is the weather in delhi", "aaj ka mausam kaisa hai", "bitcoin price", "cricket score batao")) {
            val a = FreeProviders.chainPreAnswer(q, true)
            assertNotNull(q, a)
            assertFalse(q, a.orEmpty().contains("offline", ignoreCase = true))
            assertTrue(q, a.orEmpty().contains("live"))
        }
    }

    @Test fun ordinaryQuestionsAreNotBlockedByThePreAnswer() {
        assertNull(FreeProviders.chainPreAnswer("why is the sky blue", true))
        assertNull(FreeProviders.chainPreAnswer("aaj khana kya banau", false))
        assertNull(FreeProviders.chainPreAnswer("explain photosynthesis", true))
    }

    @Test fun failureOutcomeNoInternetBeatsEverything() {
        val r = FreeProviders.combinedFailure("quota", "Gemini limit", "network", true)
        assertEquals("network", r.second)
        assertEquals(FreeProviders.failText("network", true), r.first)
    }

    @Test fun failureOutcomeKeepsGeminiMessageWhenGeminiIsTheReason() {
        val r = FreeProviders.combinedFailure("key", "Gemini key wrong", "quota", true)
        assertEquals("key", r.second)
        assertEquals("Gemini key wrong", r.first)
    }

    @Test fun failureOutcomeUsesChainReasonWhenGeminiWasNotUsed() {
        val r = FreeProviders.combinedFailure("", "", "quota", false)
        assertEquals("quota", r.second)
        assertEquals(FreeProviders.failText("quota", false), r.first)
    }

    @Test fun failureOutcomeWithNothingTriedIsCooling() {
        val r = FreeProviders.combinedFailure("", "", "", true)
        assertEquals("cooling", r.second)
        assertTrue(r.first.isNotBlank())
    }

    @Test fun planCountsTheFreeChainAsACloudSource() {
        // NovaService passes (geminiKey || freeChainOn): with no Gemini key but the chain ON the cloud step still runs
        val q = "why is the sky blue"
        assertEquals(listOf(AiRouter.Step.CLOUD), AiRouter.plan(false || true, false, false, q))
        assertEquals(listOf(AiRouter.Step.CLOUD, AiRouter.Step.LOCAL), AiRouter.plan(true, false, true, q))
        assertEquals(listOf(AiRouter.Step.LOCAL), AiRouter.plan(true, true, true, q))
    }
}
