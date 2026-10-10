package com.nova.assistant

/**
 * Part 1A of the free AI provider chain. PURE Kotlin: no Android classes, no network, no org.json
 * (org.json is only a stub in JVM unit tests, so this file has its own tiny JSON reader/writer).
 * The network call is passed in as a lambda, so every failover rule below is unit tested.
 *
 * Chain order used by the app (wired in part 1B): Gemini (existing, user key) -> Groq (user's free key)
 * -> OpenRouter free models (user's free key) -> Pollinations text (anonymous try; their current docs say a key is required, so this may 401 and is then skipped for 10 min) -> offline model -> offline skills.
 * Failover is between DIFFERENT providers and different models of ONE provider. It never rotates several
 * accounts of the same provider to get more quota (invariant 9).
 */

/** Minimal JSON: enough to build OpenAI-style requests and read their replies. */
object MiniJson {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Returns Map / List / String / Double / Boolean / null. Any malformed input also returns null (never throws). */
    fun parse(s: String): Any? = try {
        Reader(s).readAll()
    } catch (e: IllegalArgumentException) {
        null
    }

    fun field(o: Any?, key: String): Any? = (o as? Map<*, *>)?.get(key)

    fun at(o: Any?, index: Int): Any? = (o as? List<*>)?.getOrNull(index)

    private class Reader(private val s: String) {
        private var i = 0

        fun readAll(): Any? {
            val v = value(0)
            ws()
            if (i != s.length) fail()
            return v
        }

        private fun fail(): Nothing = throw IllegalArgumentException("bad json at $i")

        private fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun value(depth: Int): Any? {
            if (depth > 40) fail()
            ws()
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return v
        }

        private fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            if (st == i) fail()
            return s.substring(st, i).toDoubleOrNull() ?: fail()
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) fail()
                val e = s[i++]
                when (e) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) fail()
                        val h = s.substring(i, i + 4).toIntOrNull(16) ?: fail()
                        sb.append(h.toChar())
                        i += 4
                    }
                    else -> fail()
                }
            }
            fail()
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (i < s.length) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) fail()
                val c = s[i++]
                if (c == '}') return m
                if (c != ',') fail()
            }
            fail()
        }

        private fun arr(depth: Int): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (i < s.length) {
                l.add(value(depth + 1))
                ws()
                if (i >= s.length) fail()
                val c = s[i++]
                if (c == ']') return l
                if (c != ',') fail()
            }
            fail()
        }
    }
}

/** One OpenAI-compatible chat endpoint. [keyName] = which user key it needs (null = no key needed). */
class FreeProvider(
    val id: String,
    val label: String,
    val url: String,
    val models: List<String>,
    val keyName: String?
)

/** One HTTP answer. code 598 = network is down, 599 = timeout / IO error (same convention as Logic.nextStep). */
class NetAnswer(val code: Int, val body: String)

class ChainResult(
    val text: String?,
    val providerId: String?,
    val model: String?,
    /** "" on success, otherwise network | quota | key | model | server | other | slow | cooling | nokey */
    val failKind: String,
    val tried: List<String>
)

/** Per-provider "skip until" times. The clock is passed in, so tests need no sleeping. */
class Cooldowns {
    private val until = HashMap<String, Long>()

    @Synchronized
    fun cooling(id: String, now: Long): Boolean = (until[id] ?: 0L) > now

    @Synchronized
    fun set(id: String, ms: Long, now: Long) {
        if (ms > 0L) until[id] = now + ms
    }

    @Synchronized
    fun clear() {
        until.clear()
    }
}

object FreeProviders {
    // Model names and URLs live ONLY here. Free model ids change over time: a 404/400 moves on to the next model
    // by itself, and a provider whose models all fail is skipped for a while. Nothing here was reachable from the
    // build sandbox (no network), so the endpoints are written from the providers' public OpenAI-compatible docs.
    val GROQ = FreeProvider(
        "groq", "Groq", "https://api.groq.com/openai/v1/chat/completions",
        listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"), "groq"
    )
    val OPENROUTER = FreeProvider(
        "openrouter", "OpenRouter (free models)", "https://openrouter.ai/api/v1/chat/completions",
        listOf("openrouter/free", "meta-llama/llama-3.3-70b-instruct:free"), "openrouter"
    )
    val POLLINATIONS = FreeProvider(
        "pollinations", "Pollinations (anonymous, may need a key)", "https://gen.pollinations.ai/v1/chat/completions",
        listOf("openai/gpt-5.4-nano", "openai"), null
    )

    fun ordered(): List<FreeProvider> = listOf(GROQ, OPENROUTER, POLLINATIONS)

    const val MAX_USER_CHARS = 1000
    const val MAX_REPLY_CHARS = 700

    fun systemPrompt(en: Boolean): String =
        if (en) {
            "You are NOVA, a short, honest voice assistant on the user's phone. Reply in plain text only: no markdown, no lists, " +
                "at most 3 short sentences, in the language the user used. You cannot press buttons or change phone settings in " +
                "this answer, so never say you did. You have no live data (weather, prices, scores, news): say so instead of " +
                "guessing. If you are not sure, say so. Do not ask follow-up questions."
        } else {
            "Tum NOVA ho, user ke phone par ek chhota aur imaandaar voice assistant. Sirf plain text mein jawab do: koi markdown ya " +
                "list nahi, zyada se zyada 3 chhote vaakya, aur usi bhasha mein jisme user ne poocha (Hindi, Hinglish ya English). " +
                "Is jawab mein tum phone par koi button nahi dabaa sakte ya setting nahi badal sakte, isliye kabhi mat kehna ki kar diya. " +
                "Live data (mausam, bhaav, score, khabar) tumhare paas nahi hai: andaza mat lagao, saaf bolo ki nahi pata. " +
                "Pakka na ho to bolo. Wapas sawaal mat poochho."
        }

    fun buildBody(model: String, system: String, user: String, maxTokens: Int = 350): String {
        val u = if (user.length > MAX_USER_CHARS) user.substring(0, MAX_USER_CHARS) else user
        return "{\"model\":" + MiniJson.quote(model) +
            ",\"messages\":[{\"role\":\"system\",\"content\":" + MiniJson.quote(system) +
            "},{\"role\":\"user\",\"content\":" + MiniJson.quote(u) +
            "}],\"max_tokens\":" + maxTokens + ",\"temperature\":0.6}"
    }

    /** choices[0].message.content as clean spoken text, or null (also for 200-answers that carry an "error" object). */
    fun parseReply(body: String): String? {
        val root = MiniJson.parse(body) ?: return null
        val msg = MiniJson.field(MiniJson.at(MiniJson.field(root, "choices"), 0), "message")
        val content = MiniJson.field(msg, "content") as? String ?: return null
        val t = clean(content)
        return if (t.isBlank()) null else t
    }

    /** Spoken-text cleanup: no reasoning blocks, no markdown marks, one line, capped on a sentence end. */
    fun clean(text: String): String {
        var t = text.replace(Regex("(?s)<think>.*?</think>"), "")
        t = t.replace(Regex("[*#`]+"), "")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.length > MAX_REPLY_CHARS) {
            val cut = t.substring(0, MAX_REPLY_CHARS)
            val p = maxOf(maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('?')), maxOf(cut.lastIndexOf('!'), cut.lastIndexOf('\u0964')))
            t = if (p > 200) cut.substring(0, p + 1) else cut.trimEnd()
        }
        return t
    }

    fun kindOf(code: Int): String = when {
        code == 598 || code == 599 -> "network"
        code == 401 || code == 403 -> "key"
        code == 429 -> "quota"
        code == 400 || code == 404 || code == 422 -> "model"
        code in 500..597 -> "server"
        else -> "other"
    }

    /** How long a provider is skipped after it failed with this kind. A wrong key is retried rarely (10 min). */
    fun cooldownFor(kind: String): Long = when (kind) {
        "key" -> 600_000L
        "other" -> 20_000L
        else -> AiRouter.cooldownMs(kind)
    }

    private val RANK = listOf("nokey", "cooling", "slow", "other", "model", "server", "quota", "key", "network")

    /** The more informative of two failure kinds (what the user is told if nothing worked). */
    fun worse(a: String, b: String): String = if (RANK.indexOf(b) > RANK.indexOf(a)) b else a

    // Questions that need LIVE data. The free models have none, so they would only guess: answer honestly instead.
    private val LIVE_ONLINE = setOf(
        "news", "weather", "mausam", "score", "price", "stock", "sensex", "nifty", "bitcoin", "cricket", "headlines",
        "khabar", "election", "trending"
    )

    /**
     * Local answer BEFORE any network call, or null = the question may go to the chain.
     * Crisis words always get the fixed safe answer (112 + Tele-MANAS) and never leave the phone.
     * Live-data questions get an honest "I have no live source" (LocalChat.preAnswer would say "offline", which is false here).
     */
    fun chainPreAnswer(said: String, en: Boolean): String? {
        val crisis = LocalChat.preAnswer(said, en)
        if (crisis != null && crisis.contains("14416")) return crisis
        val w = Logic.norm(said).split(" ").toSet()
        if (w.any { it in LIVE_ONLINE }) {
            return if (en) {
                "That needs live information such as weather, prices, scores or news, and I have no live source, so I would only be guessing. Please check a news or weather app."
            } else {
                "Is ke liye live jaankari chahiye, jaise mausam, bhaav, score ya khabar, aur mere paas live source nahi hai, andaza lagana galat hoga. Kisi news ya weather app mein dekh lo."
            }
        }
        return null
    }

    /** The chain is used only for a real, non-junk question and only if the user left the switch ON. */
    fun canUseChain(switchOn: Boolean, said: String): Boolean =
        switchOn && said.isNotBlank() && !Logic.isJunk(said)

    /**
     * What to tell the user when every online source failed: (text, kind).
     * "No internet" beats everything (it is the real reason); otherwise Gemini's own message and kind win, then the chain's.
     * Nothing tried at all (for example Gemini still cooling and the chain switched off) = "cooling".
     */
    fun combinedFailure(geminiFail: String, geminiText: String, chainKind: String, en: Boolean): Pair<String, String> {
        val kind = when {
            chainKind == "network" -> "network"
            geminiFail.isNotEmpty() -> geminiFail
            chainKind.isNotEmpty() -> chainKind
            else -> "cooling"
        }
        val text = if (kind == geminiFail && geminiText.isNotEmpty()) geminiText else failText(kind, en)
        return Pair(text, kind)
    }

    fun failText(kind: String, en: Boolean): String = if (en) {
        when (kind) {
            "network" -> "There is no internet, so I could not get an online answer."
            "quota" -> "The free AI services have hit their limit for now. Please try again in a few minutes."
            "key" -> "An AI key was rejected. Please check the keys in Settings."
            "cooling" -> "The AI services are resting for a moment after errors. Please try again shortly."
            "slow" -> "The answer took too long, so I stopped waiting."
            else -> "The free AI services could not answer right now."
        }
    } else {
        when (kind) {
            "network" -> "Internet nahi hai, isliye online jawab nahi mil paaya."
            "quota" -> "Free AI services ki limit abhi poori hai. Thodi der baad try karo."
            "key" -> "Ek AI key reject hui. Settings mein keys check karo."
            "cooling" -> "Errors ke baad AI services thodi der rest par hain. Kuch der baad try karo."
            "slow" -> "Jawab aane mein bahut der lag rahi thi, isliye ruk gaya."
            else -> "Free AI services abhi jawab nahi de paayin."
        }
    }
}

object ProviderChain {
    /**
     * Tries [providers] in order until one gives a usable answer.
     * - a provider that is disabled, cooling down, or needs a key the user has not set is skipped without a call;
     * - 429 / 400 / 404 / 422 -> next MODEL of the same provider; 401 / 403 / 5xx / timeout -> next PROVIDER;
     * - 598 (no internet) ends the whole chain at once (every other provider would fail too);
     * - a 200 answer with no usable text counts as a failure ("other") and moves on;
     * - a provider that failed is put on cooldown (FreeProviders.cooldownFor) so the next question skips it;
     * - [budgetMs] bounds the total waiting time, because a voice assistant must not hang.
     * [call] = (provider, model, jsonBody, key) -> NetAnswer; it must never throw (the Android caller returns 599 for IO errors).
     */
    fun run(
        providers: List<FreeProvider>,
        keyOf: (String) -> String,
        enabled: (String) -> Boolean,
        system: String,
        user: String,
        cd: Cooldowns,
        now: () -> Long,
        budgetMs: Long = 14_000L,
        call: (FreeProvider, String, String, String) -> NetAnswer
    ): ChainResult {
        val start = now()
        val tried = ArrayList<String>()
        var worst = "nokey"
        for (p in providers) {
            if (!enabled(p.id)) continue
            if (now() - start > budgetMs) {
                worst = FreeProviders.worse(worst, "slow")
                break
            }
            if (cd.cooling(p.id, now())) {
                worst = FreeProviders.worse(worst, "cooling")
                continue
            }
            val key = if (p.keyName == null) "" else keyOf(p.keyName).trim()
            if (p.keyName != null && key.isEmpty()) continue
            tried.add(p.id)
            var kind = "other"
            var cool = true
            for (m in p.models) {
                if (now() - start > budgetMs) {
                    kind = "slow"
                    cool = false
                    break
                }
                val r = call(p, m, FreeProviders.buildBody(m, system, user), key)
                if (r.code in 200..299) {
                    val t = FreeProviders.parseReply(r.body)
                    if (t != null) return ChainResult(t, p.id, m, "", tried)
                    kind = FreeProviders.worse(kind, "other")
                    continue
                }
                if (r.code == 598) return ChainResult(null, null, null, "network", tried)
                kind = FreeProviders.worse(kind, FreeProviders.kindOf(r.code))
                val sameProviderNextModel = r.code == 429 || r.code == 400 || r.code == 404 || r.code == 422
                if (!sameProviderNextModel) break
            }
            if (cool) cd.set(p.id, FreeProviders.cooldownFor(kind), now())
            worst = FreeProviders.worse(worst, kind)
        }
        return ChainResult(null, null, null, worst, tried)
    }
}
