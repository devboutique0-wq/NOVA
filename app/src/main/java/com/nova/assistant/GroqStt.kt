package com.nova.assistant

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * Optional Groq Whisper listening. Used ONLY when the user typed a Groq key in Settings.
 * The key goes only into the Authorization header and is never logged.
 * The returned text is untrusted: it can start a normal command, but it can never approve anything.
 */
object GroqStt {
    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3"
    private const val PROMPT = "Nova, torch chalu karo. Instagram kholo. Volume kam karo."
    private const val RATE = 16000

    class Res(val code: Int, val text: String?)

    fun failText(code: Int): String = when (code) {
        598, 599 -> "internet"
        401, 403 -> "key galat"
        429 -> "limit"
        else -> "error $code"
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
        }
        fun i16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    fun transcribe(key: String, pcm: ByteArray): Res {
        if (key.isBlank() || pcm.size < 3200) return Res(0, null)
        val boundary = "novaform" + System.currentTimeMillis()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").toByteArray(Charsets.UTF_8))
        }
        field("model", MODEL)
        field("response_format", "json")
        field("temperature", "0")
        field("prompt", PROMPT)
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cmd.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray(Charsets.UTF_8))
        body.write(wav(pcm))
        body.write(("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8))
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + key)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) Res(code, null)
            else Res(code, JSONObject(text).optString("text", "").trim())
        } catch (e: UnknownHostException) {
            Res(598, null)
        } catch (e: IOException) {
            Res(599, null)
        } catch (e: Exception) {
            Res(1, null)
        } finally {
            conn?.disconnect()
        }
    }
}
