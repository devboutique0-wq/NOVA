package com.nova.assistant

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI image generate / edit with the user's own Gemini key. Only called after an explicit tap in the chat screen.
 * When a photo is attached, the user must also tap HAAN in a native box before anything is uploaded.
 * The key goes in a request header only, never in a URL or a log.
 */
object ImageGen {
    class Out(val ok: Boolean, val msg: String, val img: ByteArray? = null, val code: Int = 0)

    // newest first; older ones are only tried when a newer id answers 404
    private val MODELS = listOf("gemini-3.1-flash-image", "gemini-3.1-flash-lite-image", "gemini-2.5-flash-image")

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    /** Decodes, honours the EXIF rotation, scales so the longest side is at most maxSide, returns JPEG bytes. */
    fun shrink(raw: ByteArray, maxSide: Int): ByteArray? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            var bmp: Bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, o2) ?: return null
            var rot = 0f
            try {
                val ex = android.media.ExifInterface(ByteArrayInputStream(raw))
                rot = when (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f
                    3 -> 180f
                    8 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
            }
            val sc = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val m = Matrix()
            if (sc < 1f) m.postScale(sc, sc)
            if (rot != 0f) m.postRotate(rot)
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val bo = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, bo)
            return bo.toByteArray()
        } catch (e: Throwable) {
            return null
        }
    }

    private fun generateGemini(ctx: Context, prompt: String, photo: ByteArray?): Out {
        val keys = SecureStore.getKeys(ctx)
            .ifEmpty { listOf(SecureStore.getKey(ctx)).filter { it.isNotBlank() } }
            .distinct()
        if (keys.isEmpty()) return Out(false, "Gemini key nahi hai. Settings mein key daalo.", null, -1)
        val parts = JSONArray()
        parts.put(JSONObject().put("text", prompt))
        if (photo != null) {
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64(photo))))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
            .toString()
        val deadline = System.currentTimeMillis() + 150000L
        var code0 = 404
        for (model in MODELS) {
            for (key in keys) {
                if (System.currentTimeMillis() > deadline) return Out(false, failMsg(code0), null, code0)
                val r = post(model, key, body)
                val code = r.first
                if (code in 200..299) {
                    val ex = extract(r.second)
                    val img = ex.first
                    if (img != null) return Out(true, ex.second, img)
                    return Out(
                        false,
                        if (ex.second.isNotEmpty()) "Gemini ne image nahi di: " + ex.second.take(300)
                        else "Gemini ne image nahi di (shayad content policy). Alag shabdon mein try karo."
                    )
                }
                if (code == 404) break
                code0 = code
            }
        }
        return Out(false, failMsg(code0), null, code0)
    }

    // FIXFREE2: when Gemini fails for a technical reason (quota, key, network, model) and there is no photo,
    // the free providers in FreeImage may be tried, but ONLY if the user switched the fallback on in Settings.
    // code 0 = Gemini refused on content policy: never retried elsewhere.
    fun generate(ctx: Context, prompt: String, photo: ByteArray?): Out {
        val g = generateGemini(ctx, prompt, photo)
        if (g.ok || g.code == 0) return g
        if (photo != null) {
            return Out(false, g.msg + " Photo edit sirf Gemini se hota hai, free options sirf text se image banate hain.", null, g.code)
        }
        if (!FreeImage.enabled(ctx)) {
            return Out(false, g.msg + " Free option try karne ke liye Settings mein FREE FALLBACK ON karo.", null, g.code)
        }
        val f = FreeImage.run(ctx, prompt)
        val fimg = f.img
        if (f.ok && fimg != null) return Out(true, f.msg, fimg)
        return Out(false, g.msg + " Free options bhi nahi chale: " + f.msg, null, g.code)
    }

    fun failMsg(code: Int): String = when (code) {
        429 -> "Gemini ka limit ya quota khatam hai (code 429). Image ke liye aksar billing wali key chahiye, free key par band ho sakta hai. Thodi der baad try karo."
        400, 403 -> "Gemini ne request nahi mani (code $code). Key galat, billing band, ya region ki dikkat ho sakti hai. AI Studio mein check karo."
        401 -> "Key galat lag rahi hai (code 401). Settings mein dobara daalo."
        404 -> "Image model abhi mila nahi (code 404)."
        else -> "Network ya Gemini server ki dikkat (code $code). Thodi der baad try karo."
    }

    /** One tiny text request, only to see whether key + network work. Returns (http code, model id). */
    fun textPing(ctx: Context): Pair<Int, String> {
        val keys = SecureStore.getKeys(ctx)
            .ifEmpty { listOf(SecureStore.getKey(ctx)).filter { it.isNotBlank() } }
            .distinct()
        if (keys.isEmpty()) return Pair(0, "")
        val body = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Reply with the single word OK"))))
        ).toString()
        var last = Pair(599, "")
        for (model in (listOf(Logic.DEFAULT_MODEL) + Logic.MODELS).distinct()) {
            val r = post(model, keys[0], body)
            last = Pair(r.first, model)
            if (r.first in 200..299) return last
        }
        return last
    }

    private fun post(model: String, key: String, body: String): Pair<Int, String> {
        try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 80000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            return Pair(code, text)
        } catch (e: Exception) {
            return Pair(599, "")
        }
    }

    /** First image of the answer plus any plain text. */
    private fun extract(resp: String): Pair<ByteArray?, String> {
        try {
            val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return Pair(null, "")
            var img: ByteArray? = null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                val inl = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                if (inl != null) {
                    if (img == null) img = Base64.decode(inl.optString("data"), Base64.DEFAULT)
                } else if (p.has("text")) {
                    sb.append(p.optString("text")).append(' ')
                }
            }
            return Pair(img, sb.toString().trim())
        } catch (e: Exception) {
            return Pair(null, "")
        }
    }

    /** Saves into Gallery > Pictures > NOVA (Android 10+). Returns a short place name, or null on failure. */
    fun saveToGallery(ctx: Context, data: ByteArray): String? {
        try {
            val isPng = data.size > 4 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte()
            val ext = if (isPng) "png" else "jpg"
            val mime = if (isPng) "image/png" else "image/jpeg"
            val name = "NOVA_" + System.currentTimeMillis() + "." + ext
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues()
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name)
                cv.put(MediaStore.Images.Media.MIME_TYPE, mime)
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NOVA")
                cv.put(MediaStore.Images.Media.IS_PENDING, 1)
                val res = ctx.contentResolver
                val uri = res.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
                val os = res.openOutputStream(uri) ?: return null
                os.use { it.write(data) }
                val done = ContentValues()
                done.put(MediaStore.Images.Media.IS_PENDING, 0)
                res.update(uri, done, null, null)
                return "Gallery > Pictures > NOVA"
            }
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
            dir.mkdirs()
            File(dir, name).writeBytes(data)
            return "App folder (Android/data/com.nova.assistant/files/Pictures)"
        } catch (e: Throwable) {
            return null
        }
    }
}
