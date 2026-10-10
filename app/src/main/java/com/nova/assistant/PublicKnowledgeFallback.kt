package com.nova.assistant

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap

/**
 * API-key-free public knowledge lookup used only for clear, general-knowledge questions.
 * It sends the extracted TOPIC only to English Wikipedia, never chat history, personal memory, audio,
 * screen contents, or the full original sentence. Live/current and personal queries are deliberately skipped.
 * This source returns text plus a source URL; it never creates actions or executes model output.
 */
object PublicKnowledgeFallback {
    private const val MAX_TOPIC_CHARS = 100
    private const val MAX_SUMMARY_CHARS = 680
    private const val CONNECT_TIMEOUT_MS = 1800
    private const val READ_TIMEOUT_MS = 2400
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L

    private data class Cached(val expiresAt: Long, val answer: String)
    private val cache = LinkedHashMap<String, Cached>(32, 0.75f, true)

    private val liveWords = setOf(
        "today", "tonight", "tomorrow", "yesterday", "latest", "current", "currently", "now", "live",
        "news", "weather", "mausam", "khabar", "price", "prices", "stock", "score", "scores", "trending",
        "election", "result", "results", "aaj", "abhi", "kal", "taaza", "naya", "rate", "bhaav"
    )
    private val personalWords = setOf(
        "my", "me", "mera", "meri", "mere", "mujhe", "humara", "hamara", "hamari", "phone", "contacts",
        "contact", "message", "messages", "password", "otp", "pin", "account", "bank", "payment", "address",
        "location", "aadhaar", "aadhar", "pan", "private", "personal", "family", "friend", "friends"
    )
    // Logic.isJunk deliberately rejects one/two-letter speech noise; preserve common explicit concepts like "AI kya hai".
    private val shortTopicAllowlist = setOf("ai", "ml", "uk", "us", "tv", "pc", "vr", "ar", "ui", "ux")

    /** A topic is returned only for an explicit information request; ordinary chat is not searched. */
    fun topicOf(raw: String): String? {
        var text = raw.trim().replace(Regex("(?i)^nova\\s*[,.:;-]?\\s*"), "")
        text = text.replace(Regex("[?!.]+$"), "").trim()
        if (text.isEmpty() || text.length > 240) return null
        val normalized = Logic.norm(text)
        if (normalized.isBlank()) return null
        val words = normalized.split(Regex("\\s+")).toSet()
        if (words.any { it in liveWords }) return null

        val lower = normalized
        val englishPrefixes = listOf(
            "tell me about ", "give me facts about ", "information about ", "what is ", "what are ",
            "who is ", "who are ", "who was ", "who were ", "what was ", "define ", "explain ",
            "meaning of ", "history of ", "how does "
        )
        for (prefix in englishPrefixes) {
            if (lower.startsWith(prefix)) return validTopic(lower.removePrefix(prefix))
        }
        val inventPrefix = listOf("who invented ", "who discovered ", "when was ")
        for (prefix in inventPrefix) {
            if (lower.startsWith(prefix)) return validTopic(lower.removePrefix(prefix))
        }

        val hinglishSuffixes = listOf(
            " ke baare mein batao", " ke bare mein batao", " ke baare me batao", " ke bare me batao",
            " ke baare mein samjhao", " ke bare mein samjhao", " ke baare me samjhao", " ke bare me samjhao",
            " ke baare mein jankari do", " ke bare mein jankari do", " kya hai", " kaun hai", " kon hai",
            " kya hota hai", " ka matlab kya hai"
        )
        for (suffix in hinglishSuffixes) {
            if (lower.endsWith(suffix)) return validTopic(lower.removeSuffix(suffix))
        }
        return null
    }

    private fun validTopic(raw: String): String? {
        var topic = raw.trim()
        topic = topic.replace(Regex("^(?:please |mujhe |hame |hamen |tell me |about )+"), "").trim()
        topic = topic.replace(Regex("\\s+"), " ")
        if (topic.length !in 2..MAX_TOPIC_CHARS) return null
        val tokens = topic.split(" ")
        if (tokens.size > 10 || topic.any { it.code < 32 }) return null
        if (tokens.any { it in liveWords || it in personalWords }) return null
        if (Logic.isJunk(topic) && topic.lowercase() !in shortTopicAllowlist) return null
        return topic
    }

    /** Returns null quickly for ineligible questions, offline access, API errors, or missing article summaries. */
    fun answer(raw: String, en: Boolean): String? {
        val topic = topicOf(raw) ?: return null
        // A single topic can have different response wrappers based on output language; keep cached variants separate.
        val cacheKey = (if (en) "en:" else "hi:") + topic.lowercase()
        val now = System.currentTimeMillis()
        synchronized(cache) {
            val entry = cache[cacheKey]
            if (entry != null && entry.expiresAt > now) return entry.answer
            if (entry != null) cache.remove(cacheKey)
        }

        var conn: HttpURLConnection? = null
        return try {
            val q = URLEncoder.encode(topic, "UTF-8")
            val endpoint = "https://en.wikipedia.org/w/api.php?action=query&generator=search" +
                "&gsrsearch=$q&gsrnamespace=0&gsrlimit=1&prop=extracts%7Cinfo" +
                "&exintro=1&explaintext=1&exsentences=3&inprop=url&format=json&formatversion=2"
            val c = URL(endpoint).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "GET"
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.useCaches = true
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "NOVAAndroidAssistant/6.17 (public topic lookup)")
            c.setRequestProperty("Api-User-Agent", "NOVAAndroidAssistant/6.17 (public topic lookup)")
            val code = c.responseCode
            if (code !in 200..299) return null
            val stream = c.inputStream ?: return null
            val body = readLimited(stream, MAX_RESPONSE_BYTES)
            if (body.isEmpty()) return null
            val root = JSONObject(String(body, Charsets.UTF_8))
            val pages = root.optJSONObject("query")?.optJSONArray("pages") ?: return null
            val page = pages.optJSONObject(0) ?: return null
            if (page.optBoolean("missing", false)) return null
            val title = page.optString("title", "").trim()
            var summary = page.optString("extract", "").replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty() || summary.length < 40) return null
            if (summary.length > MAX_SUMMARY_CHARS) {
                val part = summary.take(MAX_SUMMARY_CHARS)
                val stop = maxOf(part.lastIndexOf('.'), part.lastIndexOf('!'))
                summary = if (stop >= 180) part.substring(0, stop + 1) else part.trimEnd()
            }
            val url = page.optString("fullurl", "").takeIf { it.startsWith("https://en.wikipedia.org/wiki/") }
                ?: "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
            val answer = if (en) "$title: $summary Source: $url" else "Wikipedia ka short summary (English source): $title. $summary Source: $url"
            synchronized(cache) {
                cache[cacheKey] = Cached(now + CACHE_TTL_MS, answer)
                while (cache.size > 32) cache.remove(cache.entries.first().key)
            }
            answer
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    private fun readLimited(stream: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size() + n > maxBytes) return ByteArray(0)
                output.write(buffer, 0, n)
            }
        }
        return output.toByteArray()
    }
}
