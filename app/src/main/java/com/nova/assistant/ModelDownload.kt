package com.nova.assistant

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Resumable, verified download for big files (the offline model). Pure JVM (no Android classes) so it is tested
 * against a local HTTP server. Rules:
 *  - every hop (including redirects) must pass [allow] (the caller passes the https + host allow-list),
 *  - an interrupted download keeps the .part file and the next call resumes with a Range request,
 *  - a size overrun or a SHA-256 mismatch deletes the file (never kept, never used),
 *  - cancelling keeps the .part file for a later resume.
 */
object ModelDownload {
    sealed class Result {
        class Ok(val file: File) : Result()
        /** [resumable] = the .part file was kept, calling download() again continues. */
        class Fail(val reason: String, val resumable: Boolean) : Result()
    }

    private fun open(url: String, offset: Long, allow: (String) -> Boolean): HttpURLConnection {
        var cur = url
        for (hop in 0..5) {
            if (!allow(cur)) throw IOException("blocked host")
            val c = URL(cur).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "NOVA-updater")
            if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("redirect without target")
                cur = URL(URL(cur), loc).toString()
                continue
            }
            return c
        }
        throw IOException("too many redirects")
    }

    fun sha256File(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return Updater.hex(md.digest())
    }

    fun download(
        url: String, part: File, size: Long, sha256: String,
        allow: (String) -> Boolean,
        cancelled: () -> Boolean = { false },
        progress: (Int) -> Unit = {},
        freeBytes: Long = Long.MAX_VALUE
    ): Result {
        if (size <= 0L) return Result.Fail("bad size", false)
        var have = if (part.exists()) part.length() else 0L
        if (have > size) { part.delete(); have = 0L }
        if (have < size) {
            if (freeBytes < (size - have) + size / 20) return Result.Fail("not enough free storage", true)
            val c: HttpURLConnection
            try { c = open(url, have, allow) } catch (e: IOException) { return Result.Fail(e.message ?: "network error", true) }
            try {
                val code = c.responseCode
                var append = false
                when {
                    code == 206 -> {
                        val cr = c.getHeaderField("Content-Range") ?: ""
                        val m = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").find(cr.trim())
                        if (m == null || m.groupValues[1].toLong() != have || m.groupValues[3].toLong() != size) {
                            part.delete(); return Result.Fail("server sent a different range", true)
                        }
                        append = true
                    }
                    code == 200 -> { have = 0L }                      // server ignored Range: start again from zero
                    code == 416 -> { part.delete(); return Result.Fail("restarting download", true) }
                    else -> return Result.Fail("HTTP $code", true)
                }
                if (!append && part.exists()) part.delete()
                var total = have
                var lastPct = -1
                try {
                    c.inputStream.use { ins ->
                        RandomAccessFile(part, "rw").use { out ->
                            out.seek(total)
                            val buf = ByteArray(65536)
                            while (true) {
                                if (cancelled()) throw IOException("cancelled")
                                val n = ins.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > size) throw IllegalStateException("bigger")
                                out.write(buf, 0, n)
                                val pct = (total * 100 / size).toInt()
                                if (pct != lastPct) { lastPct = pct; progress(pct) }
                            }
                        }
                    }
                } catch (e: IllegalStateException) {
                    part.delete()
                    return Result.Fail("file is bigger than promised", false)
                } catch (e: IOException) {
                    return Result.Fail(e.message ?: "interrupted", true)
                }
            } finally {
                c.disconnect()
            }
        }
        if (part.length() != size) return Result.Fail("incomplete", true)
        if (sha256File(part) != sha256) { part.delete(); return Result.Fail("checksum mismatch", false) }
        return Result.Ok(part)
    }
}
