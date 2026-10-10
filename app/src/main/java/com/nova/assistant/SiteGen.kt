package com.nova.assistant

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v16 WEBSITE BY VOICE, step 2: write ONE self-contained index.html from the owner's own keys and save it on the phone.
 * Order: the owner's Gemini key(s) first, then the owner's Groq key. Nothing is uploaded or published here; only the
 * site topic text goes to the provider. Keys go in request headers only, never in a URL or a log.
 * Failure text never names a provider (owner asked for silent fallback).
 * Evidence level: written + statically checked. Not compiled here, not phone tested.
 */
object SiteGen {
    class Out(val ok: Boolean, val html: String = "", val msg: String = "")

    const val MAX_HTML = 400_000
    private const val BUDGET_MS = 170_000L

    const val SYSTEM = "You are a senior web designer. Reply with ONE complete, self-contained HTML5 file and nothing else: " +
        "start with <!DOCTYPE html>, end with </html>. Put all CSS in one <style> and any JS in one <script>. " +
        "No external files, no external images, no CDN: use CSS gradients, emoji and inline SVG for visuals. " +
        "Mobile-first responsive layout, clean modern look, readable contrast, real placeholder text written for the topic " +
        "(sections like hero, about/services, gallery or menu, contact). Write the visible text in the language the owner used. " +
        "Never include login forms that send data anywhere, trackers, or code that copies a real brand."

    /** Pure: takes the model's raw answer, returns clean HTML or null when it is not a usable full page. */
    fun cleanHtml(raw: String): String? {
        var t = raw.trim()
        val lower = t.lowercase()
        var start = lower.indexOf("<!doctype")
        if (start < 0) start = lower.indexOf("<html")
        if (start < 0) return null
        val endTag = lower.lastIndexOf("</html>")
        if (endTag < start) return null
        t = t.substring(start, endTag + 7)
        if (t.length < 200 || t.length > MAX_HTML) return null
        return t
    }

    /** Pure: a safe ASCII file name part from the spoken topic. */
    fun slug(subject: String): String {
        val sb = StringBuilder()
        for (ch in subject.lowercase()) {
            if ((ch in 'a'..'z') || (ch in '0'..'9')) sb.append(ch) else if (sb.isNotEmpty() && sb.last() != '_') sb.append('_')
        }
        var s = sb.toString().trim('_')
        if (s.length > 24) s = s.substring(0, 24).trim('_')
        return if (s.isEmpty()) "site" else s
    }

    private fun userPrompt(subject: String): String = "Build a website for: " + subject

    fun failMsg(): String = "Abhi website nahi ban payi. Thodi der baad dobara bolo"

    fun generate(ctx: Context, subject: String): Out {
        val start = System.currentTimeMillis()
        val gKeys = SecureStore.getKeys(ctx)
            .ifEmpty { listOf(SecureStore.getKey(ctx)).filter { it.isNotBlank() } }
            .distinct()
        val groqKey = SecureStore.getGroq(ctx)
        if (gKeys.isEmpty() && groqKey.isEmpty()) {
            return Out(false, "", "Website banane ke liye koi AI key nahi mili. Settings mein key daalo")
        }
        for (key in gKeys) {
            for (model in (listOf(Logic.DEFAULT_MODEL) + Logic.MODELS).distinct()) {
                if (System.currentTimeMillis() - start > BUDGET_MS) return Out(false, "", failMsg())
                val r = gemini(model, key, subject)
                if (r.first in 200..299) {
                    val h = cleanHtml(geminiText(r.second))
                    if (h != null) return Out(true, h)
                    continue
                }
                if (r.first == 401 || r.first == 403 || r.first == 598) break   // this key will not work / no internet
            }
        }
        if (groqKey.isNotEmpty()) {
            for (model in FreeProviders.GROQ.models) {
                if (System.currentTimeMillis() - start > BUDGET_MS) return Out(false, "", failMsg())
                val r = groq(model, groqKey, subject)
                if (r.first in 200..299) {
                    val h = cleanHtml(groqText(r.second))
                    if (h != null) return Out(true, h)
                    continue
                }
                if (r.first == 401 || r.first == 403 || r.first == 598) break
            }
        }
        return Out(false, "", failMsg())
    }

    private fun gemini(model: String, key: String, subject: String): Pair<Int, String> {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userPrompt(subject))))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 16000).put("temperature", 0.7))
            .toString()
        return http("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", body) { c ->
            c.setRequestProperty("x-goog-api-key", key)
        }
    }

    private fun groq(model: String, key: String, subject: String): Pair<Int, String> {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", userPrompt(subject))))
            .put("max_tokens", 7000)
            .put("temperature", 0.7)
            .toString()
        return http(FreeProviders.GROQ.url, body) { c ->
            c.setRequestProperty("Authorization", "Bearer " + key)
        }
    }

    private fun http(url: String, body: String, headers: (HttpURLConnection) -> Unit): Pair<Int, String> {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 120000
            c.instanceFollowRedirects = false
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers(c)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            return Pair(code, text)
        } catch (e: java.net.UnknownHostException) {
            return Pair(598, "")
        } catch (e: Exception) {
            return Pair(599, "")
        }
    }

    private fun geminiText(resp: String): String {
        try {
            val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                if (p.has("text")) sb.append(p.optString("text"))
            }
            return sb.toString()
        } catch (e: Exception) {
            return ""
        }
    }

    private fun groqText(resp: String): String {
        try {
            return JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content") ?: ""
        } catch (e: Exception) {
            return ""
        }
    }

    /** Saves into Downloads > NOVA (Android 10+), else the app's own Documents folder. Returns a short place name or null. */
    fun save(ctx: Context, subject: String, html: String): String? {
        try {
            val name = slug(subject) + "_" + System.currentTimeMillis() + ".html"
            val bytes = html.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues()
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name)
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/html")
                cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/NOVA")
                cv.put(MediaStore.Downloads.IS_PENDING, 1)
                val res = ctx.contentResolver
                val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
                val os = res.openOutputStream(uri) ?: return null
                os.use { it.write(bytes) }
                val done = ContentValues()
                done.put(MediaStore.Downloads.IS_PENDING, 0)
                res.update(uri, done, null, null)
                return "Downloads > NOVA > " + name
            }
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return null
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
            return "App folder (Android/data/com.nova.assistant/files/Documents) > " + name
        } catch (e: Throwable) {
            return null
        }
    }
}
