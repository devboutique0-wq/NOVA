package com.nova.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * v17 "only my voice": storage + speaker model + vector extraction.
 * Privacy: the voiceprint (numbers only, no audio) stays in the app's private no-backup folder. Audio is never saved,
 * and neither the print nor audio is sent anywhere. Only the public Vosk speaker model is downloaded (one https host).
 * Evidence level: written + statically checked. Not compiled here, not phone tested.
 */
object VoiceLock {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    private const val MODEL_HOST = "alphacephei.com"
    private const val MAX_ZIP_BYTES = 40L * 1024 * 1024
    private const val MAX_UNZIPPED_BYTES = 90L * 1024 * 1024

    @Volatile private var spk: SpeakerModel? = null

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "model-spk")
    private fun profileFile(ctx: Context) = File(ctx.noBackupFilesDir, "voiceprint.json")

    fun modelReady(ctx: Context): Boolean {
        val d = modelDir(ctx)
        return File(d, ".done").isFile && (d.listFiles()?.count { it.isFile && it.name != ".done" } ?: 0) >= 2
    }

    fun hasProfile(ctx: Context): Boolean = loadProfile(ctx) != null

    fun loadProfile(ctx: Context): DoubleArray? = try {
        val f = profileFile(ctx)
        if (!f.isFile) null else {
            val a = JSONObject(f.readText()).optJSONArray("v")
            if (a == null || a.length() == 0) null else DoubleArray(a.length()) { a.optDouble(it, 0.0) }
        }
    } catch (e: Exception) { null }

    fun saveProfile(ctx: Context, v: DoubleArray): Boolean = try {
        val arr = JSONArray()
        for (x in v) arr.put(x)
        profileFile(ctx).writeText(JSONObject().put("v", arr).toString())
        true
    } catch (e: Exception) { false }

    /** Deletes the owner's voiceprint (the shared speaker model file stays). */
    fun deleteProfile(ctx: Context) {
        try { profileFile(ctx).delete() } catch (e: Exception) { }
    }

    fun release() {
        try { spk?.close() } catch (t: Throwable) { }
        spk = null
    }

    private fun speakerModel(ctx: Context): SpeakerModel? {
        spk?.let { return it }
        if (!modelReady(ctx)) return null
        return try {
            val m = SpeakerModel(modelDir(ctx).absolutePath)
            spk = m
            m
        } catch (t: Throwable) { null }
    }

    /** x-vector and frame count for one utterance (16-bit mono PCM). null = no usable vector. */
    fun vector(ctx: Context, md: Model, pcm: ByteArray, rate: Int): Pair<DoubleArray, Int>? {
        val sm = speakerModel(ctx) ?: return null
        var rec: Recognizer? = null
        return try {
            val r = Recognizer(md, rate.toFloat(), sm)
            rec = r
            var off = 0
            while (off < pcm.size) {
                val n = minOf(3200, pcm.size - off)
                r.acceptWaveForm(pcm.copyOfRange(off, off + n), n)
                off += n
            }
            val j = JSONObject(r.finalResult)
            val a = j.optJSONArray("spk")
            if (a == null || a.length() == 0) null
            else Pair(DoubleArray(a.length()) { a.optDouble(it, 0.0) }, j.optInt("spk_frames", 0))
        } catch (t: Throwable) { null } finally {
            try { rec?.close() } catch (t: Throwable) { }
        }
    }

    /** Downloads the public speaker model once (about 14 MB). Returns null on success, else a short reason. */
    fun downloadModel(ctx: Context): String? {
        if (modelReady(ctx)) return null
        val tmpZip = File(ctx.cacheDir, "spk.zip.part")
        val tmpDir = File(ctx.cacheDir, "spk_unpack")
        try {
            tmpZip.delete()
            tmpDir.deleteRecursively()
            var cur = MODEL_URL
            var c: HttpURLConnection? = null
            for (hop in 0..4) {
                val u = URL(cur)
                if (u.protocol != "https" || !(u.host == MODEL_HOST || u.host.endsWith(".$MODEL_HOST"))) return "blocked host"
                val x = u.openConnection() as HttpURLConnection
                x.instanceFollowRedirects = false
                x.connectTimeout = 10000
                x.readTimeout = 30000
                x.setRequestProperty("User-Agent", "NOVAAndroidAssistant/6.17 (speaker model)")
                val code = x.responseCode
                if (code in 300..399) {
                    val loc = x.getHeaderField("Location")
                    x.disconnect()
                    if (loc == null) return "redirect"
                    cur = URL(u, loc).toString()
                    continue
                }
                if (code != 200) { x.disconnect(); return "http $code" }
                c = x
                break
            }
            val conn = c ?: return "too many redirects"
            var total = 0L
            conn.inputStream.use { ins ->
                tmpZip.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ZIP_BYTES) throw IOException("too big")
                        out.write(buf, 0, n)
                    }
                }
            }
            conn.disconnect()
            tmpDir.mkdirs()
            val root = tmpDir.canonicalPath + File.separator
            var unzipped = 0L
            ZipInputStream(tmpZip.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val dest = File(tmpDir, e.name)
                    if (!dest.canonicalPath.startsWith(root)) throw IOException("bad zip path")
                    if (e.isDirectory) { dest.mkdirs(); continue }
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            unzipped += n
                            if (unzipped > MAX_UNZIPPED_BYTES) throw IOException("too big")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            val kids = tmpDir.listFiles() ?: emptyArray()
            val src = if (kids.size == 1 && kids[0].isDirectory) kids[0] else tmpDir
            val dest = modelDir(ctx)
            dest.deleteRecursively()
            if (!src.renameTo(dest)) return "could not save"
            File(dest, ".done").writeText("1")
            return if (modelReady(ctx)) null else "model files missing"
        } catch (t: Throwable) {
            return t.javaClass.simpleName
        } finally {
            try { tmpZip.delete() } catch (t: Throwable) { }
            try { tmpDir.deleteRecursively() } catch (t: Throwable) { }
        }
    }
}
