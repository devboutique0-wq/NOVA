package com.nova.assistant

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** FIXFREE2: pure helpers with no Android classes, so they run in plain JVM unit tests. */
object FreeImageRules {
    const val MAX_PROMPT = 500

    /** Removes control characters, squeezes spaces, cuts to MAX_PROMPT. */
    fun cleanPrompt(raw: String): String {
        val sb = StringBuilder()
        var lastSpace = true
        for (ch in raw) {
            if (ch.isWhitespace() || ch.code < 32) {
                if (!lastSpace) {
                    sb.append(' ')
                    lastSpace = true
                }
            } else {
                sb.append(ch)
                lastSpace = false
            }
            if (sb.length >= MAX_PROMPT) break
        }
        return sb.toString().trim()
    }

    /** JPEG, PNG or WEBP signature. Anything else (an error page, JSON) is not an image. */
    fun looksImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 0x50.toByte()
        val webp = b[0] == 0x52.toByte() && b[1] == 0x49.toByte() && b[8] == 0x57.toByte()
        return jpg || png || webp
    }

    fun isHttps(u: String): Boolean = u.length in 12..2000 && u.startsWith("https://") && !u.contains("@")

    /** Short Hinglish reason. Never contains a key. */
    fun failText(name: String, code: Int): String = when (code) {
        401, 403 -> name + " ne key nahi mani (code " + code + ")"
        402 -> name + " par credits nahi hain (code 402)"
        429 -> name + " ka limit abhi khatam (code 429)"
        599 -> name + " tak network nahi pahuncha"
        else -> name + " se jawab nahi mila (code " + code + ")"
    }

    /** Remembers "do not call this provider until time X". The clock is injected so tests can fake time. */
    class Cooldown(private val clock: () -> Long) {
        private val until = HashMap<String, Long>()

        @Synchronized
        fun blocked(id: String): Boolean {
            val u = until[id] ?: return false
            return clock() < u
        }

        @Synchronized
        fun set(id: String, ms: Long) {
            until[id] = clock() + ms
        }
    }
}

/**
 * FIXFREE2: optional free text-to-image providers.
 * Used ONLY when (1) Gemini could not make the image because of a technical failure, (2) the request has NO photo and
 * (3) the user switched FREE IMAGE FALLBACK on in Settings (default OFF). A photo is never sent to these services.
 * Order: Together AI (own key) -> Pollinations (no key) -> Hugging Face (own token, may cost money).
 * Keys live in SecureStore, travel only in an Authorization header, and are never put in a URL, log or message.
 * To add a provider later: add a Prov to ORDER, write one function returning Try, add it in call(). Nothing else changes.
 */
object FreeImage {
    class Outcome(val ok: Boolean, val img: ByteArray?, val msg: String)

    private class Try(val img: ByteArray?, val code: Int, val msg: String)
    private class Http(val code: Int, val bytes: ByteArray)
    private class Prov(val id: String, val label: String, val slot: String?)

    private const val PREF = "nova_freeimg"
    private const val ON = "on"
    private const val MAX_BYTES = 12 * 1024 * 1024

    private val TOGETHER_MODELS = listOf("black-forest-labs/FLUX.1-schnell-Free")
    private val HF_MODELS = listOf("black-forest-labs/FLUX.1-schnell")

    private val ORDER = listOf(
        Prov("together", "Together AI (free)", "together"),
        Prov("pollinations", "Pollinations (free)", null),
        Prov("hf", "Hugging Face", "hf")
    )

    private val cool = FreeImageRules.Cooldown { System.currentTimeMillis() }

    fun enabled(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(ON, false)
    } catch (e: Exception) {
        false
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(ON, on).apply()
        } catch (e: Exception) {
        }
    }

    /** Tries every provider that is usable right now, in order, until one returns a real image. */
    fun run(ctx: Context, rawPrompt: String): Outcome {
        val prompt = FreeImageRules.cleanPrompt(rawPrompt)
        if (prompt.isEmpty()) return Outcome(false, null, "Prompt khaali hai")
        val deadline = System.currentTimeMillis() + 120000L
        val notes = ArrayList<String>()
        for (p in ORDER) {
            if (System.currentTimeMillis() > deadline) {
                notes.add("time khatam ho gaya")
                break
            }
            var key = ""
            val slot = p.slot
            if (slot != null) {
                key = SecureStore.getSlot(ctx, slot)
                if (key.isEmpty()) continue
            }
            if (cool.blocked(p.id)) {
                notes.add(p.label + " abhi aaram par (limit), thodi der baad")
                continue
            }
            val t = attempt(p, key, prompt, deadline)
            val img = t.img
            if (img != null) return Outcome(true, img, "Ye image " + p.label + " se bani.")
            notes.add(t.msg)
        }
        if (notes.isEmpty()) return Outcome(false, null, "koi free option chalu nahi hua")
        return Outcome(false, null, notes.joinToString(". "))
    }

    /** Settings > FREE IMAGE TEST. Checks each provider on its own with a tiny text prompt. No photo. */
    fun testAll(ctx: Context, emit: (String) -> Unit) {
        emit("INFO  Free fallback switch: " + (if (enabled(ctx)) "ON" else "OFF (chat mein tab chalega jab ON karo, ye test switch se bandha nahi)"))
        val prompt = "A plain blue circle on a white background, minimal"
        for (p in ORDER) {
            var key = ""
            val slot = p.slot
            if (slot != null) {
                key = SecureStore.getSlot(ctx, slot)
                if (key.isEmpty()) {
                    emit("SKIP  " + p.label + ": key saved nahi")
                    continue
                }
            }
            emit("INFO  " + p.label + " try ho raha hai...")
            val t0 = System.currentTimeMillis()
            val t = attempt(p, key, prompt, System.currentTimeMillis() + 120000L)
            val sec = (System.currentTimeMillis() - t0) / 1000L
            val img = t.img
            if (img != null) emit("PASS  " + p.label + ": image mili (" + (img.size / 1024) + " KB, " + sec + " sec)")
            else emit("FAIL  " + p.label + ": " + t.msg)
        }
        emit("INFO  Free image test poora hua")
    }

    private fun attempt(p: Prov, key: String, prompt: String, deadline: Long): Try {
        var t = call(p, key, prompt)
        // Pollinations without an account allows about one request per 15 seconds: wait once, then retry.
        if (t.img == null && t.code == 429 && p.id == "pollinations" && System.currentTimeMillis() + 20000L < deadline) {
            try {
                Thread.sleep(16000L)
            } catch (e: InterruptedException) {
                return t
            }
            t = call(p, key, prompt)
        }
        if (t.img == null && t.code == 429) cool.set(p.id, 60000L)
        return t
    }

    private fun call(p: Prov, key: String, prompt: String): Try {
        if (p.id == "together") return together(key, prompt)
        if (p.id == "hf") return hf(key, prompt)
        return pollinations(prompt)
    }

    private fun together(key: String, prompt: String): Try {
        var last = Try(null, 404, FreeImageRules.failText("Together AI", 404))
        for (model in TOGETHER_MODELS) {
            val body = JSONObject().put("model", model).put("prompt", prompt).toString().toByteArray(Charsets.UTF_8)
            val hdr = HashMap<String, String>()
            hdr["Authorization"] = "Bearer " + key
            hdr["Content-Type"] = "application/json"
            val r = http("https://api.together.xyz/v1/images/generations", "POST", hdr, body, 90000)
            if (r.code !in 200..299) {
                last = Try(null, r.code, FreeImageRules.failText("Together AI", r.code))
                if (r.code == 404 || r.code == 400) continue   // this model id is gone: try the next id
                return last
            }
            val img = togetherImage(r.bytes)
            if (img != null) return Try(img, 200, "")
            return Try(null, 598, "Together AI ne image nahi di")
        }
        return last
    }

    /** Together answers with b64_json or (some models) a signed https url. Both are checked as real images. */
    private fun togetherImage(raw: ByteArray): ByteArray? {
        try {
            val d = JSONObject(String(raw, Charsets.UTF_8)).optJSONArray("data")?.optJSONObject(0) ?: return null
            val b64 = d.optString("b64_json", "")
            if (b64.length > 100) {
                val img = Base64.decode(b64, Base64.DEFAULT)
                if (valid(img)) return img
            }
            val u = d.optString("url", "")
            if (FreeImageRules.isHttps(u)) {
                val g = http(u, "GET", HashMap<String, String>(), null, 60000)
                if (g.code in 200..299 && valid(g.bytes)) return g.bytes
            }
        } catch (e: Exception) {
        }
        return null
    }

    private fun hf(key: String, prompt: String): Try {
        var last = Try(null, 404, FreeImageRules.failText("Hugging Face", 404))
        for (model in HF_MODELS) {
            val body = JSONObject().put("inputs", prompt).toString().toByteArray(Charsets.UTF_8)
            val hdr = HashMap<String, String>()
            hdr["Authorization"] = "Bearer " + key
            hdr["Content-Type"] = "application/json"
            val r = http("https://router.huggingface.co/hf-inference/models/" + model, "POST", hdr, body, 90000)
            if (r.code !in 200..299) {
                last = Try(null, r.code, FreeImageRules.failText("Hugging Face", r.code))
                if (r.code == 404 || r.code == 400) continue
                return last
            }
            if (valid(r.bytes)) return Try(r.bytes, 200, "")
            return Try(null, 598, "Hugging Face ne image nahi di")
        }
        return last
    }

    private fun pollinations(prompt: String): Try {
        val q = java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val url = "https://image.pollinations.ai/prompt/" + q + "?width=1024&height=1024&nologo=true&model=flux&seed=" + (System.currentTimeMillis() % 100000L)
        val r = http(url, "GET", HashMap<String, String>(), null, 90000)
        if (r.code in 200..299) {
            if (valid(r.bytes)) return Try(r.bytes, 200, "")
            return Try(null, 598, "Pollinations ne image nahi di")
        }
        return Try(null, r.code, FreeImageRules.failText("Pollinations", r.code))
    }

    /** Signature check plus a bounds-only decode, so a broken file never reaches the chat screen or the gallery. */
    private fun valid(b: ByteArray): Boolean {
        if (!FreeImageRules.looksImage(b)) return false
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            o.outWidth > 0 && o.outHeight > 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun http(url: String, method: String, headers: Map<String, String>, body: ByteArray?, readMs: Int): Http {
        var result = Http(599, ByteArray(0))
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15000
                c.readTimeout = readMs
                for (h in headers.entries) c.setRequestProperty(h.key, h.value)
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val st = if (code in 200..299) c.inputStream else c.errorStream
                val data = if (st != null) readLimited(st, MAX_BYTES) else ByteArray(0)
                result = Http(code, data)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            result = Http(599, ByteArray(0))
        }
        return result
    }

    /** Reads at most max bytes. A bigger answer is thrown away (empty), never kept in memory. */
    private fun readLimited(st: InputStream, max: Int): ByteArray {
        val bo = ByteArrayOutputStream()
        try {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = st.read(buf)
                if (n < 0) break
                bo.write(buf, 0, n)
                if (bo.size() > max) return ByteArray(0)
            }
        } finally {
            try {
                st.close()
            } catch (e: Exception) {
            }
        }
        return bo.toByteArray()
    }
}
