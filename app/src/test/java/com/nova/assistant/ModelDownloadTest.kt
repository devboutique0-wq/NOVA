package com.nova.assistant

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class ModelDownloadTest {
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val data = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val sha = Updater.hex(MessageDigest.getInstance("SHA-256").digest(data))
    private var port = 0
    @Volatile private var ignoreRange = false
    @Volatile private var cutAfter = -1          // send only this many bytes, then drop the connection
    @Volatile private var served = ArrayList<String>()
    private fun url(p: String) = "http://127.0.0.1:$port$p"
    private val allow: (String) -> Boolean = { it.startsWith("http://127.0.0.1:") }

    @Before fun up() {
        dir = Files.createTempDirectory("nova-dl").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/model") { ex -> serve(ex, data) }
        server.createContext("/redir") { ex ->
            ex.responseHeaders.add("Location", "/model"); ex.sendResponseHeaders(302, -1); ex.close()
        }
        server.createContext("/bad") { ex -> serve(ex, ByteArray(data.size) { 1 }) }
        server.createContext("/big") { ex -> serve(ex, ByteArray(data.size + 5000) { 2 }) }
        server.createContext("/gone") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        server.start()
    }
    @After fun down() { server.stop(0); dir.deleteRecursively() }

    private fun serve(ex: HttpExchange, body: ByteArray) {
        val range = ex.requestHeaders.getFirst("Range")
        served.add(range ?: "full")
        var from = 0
        if (range != null && !ignoreRange) from = range.removePrefix("bytes=").removeSuffix("-").toInt()
        val slice = body.copyOfRange(from, body.size)
        if (from > 0) {
            ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
            ex.sendResponseHeaders(206, slice.size.toLong())
        } else ex.sendResponseHeaders(200, slice.size.toLong())
        val out = ex.responseBody
        if (cutAfter >= 0) { out.write(slice, 0, minOf(cutAfter, slice.size)); out.flush(); ex.close(); return }
        out.write(slice); out.close()
    }

    private fun dl(path: String, part: File = File(dir, "m.part"), size: Long = data.size.toLong(), hash: String = sha,
                   free: Long = Long.MAX_VALUE, cancelled: () -> Boolean = { false }) =
        ModelDownload.download(url(path), part, size, hash, allow, cancelled, {}, free)

    @Test fun fullDownloadVerifies() {
        val r = dl("/model")
        assertTrue(r is ModelDownload.Result.Ok)
        assertEquals(sha, ModelDownload.sha256File(File(dir, "m.part")))
    }
    @Test fun followsRedirect() { assertTrue(dl("/redir") is ModelDownload.Result.Ok) }
    @Test fun interruptedThenResumes() {
        cutAfter = 100_000
        val r1 = dl("/model")
        assertTrue(r1 is ModelDownload.Result.Fail && r1.resumable)
        val part = File(dir, "m.part")
        assertTrue(part.length() in 1 until data.size.toLong())
        cutAfter = -1
        served.clear()
        val r2 = dl("/model")
        assertTrue(r2 is ModelDownload.Result.Ok)
        assertTrue("second call must use Range, got $served", served.single().startsWith("bytes="))
        assertEquals(sha, ModelDownload.sha256File(part))
    }
    @Test fun serverIgnoresRangeRestartsCleanly() {
        File(dir, "m.part").writeBytes(data.copyOf(50_000))
        ignoreRange = true
        assertTrue(dl("/model") is ModelDownload.Result.Ok)
        assertEquals(sha, ModelDownload.sha256File(File(dir, "m.part")))
    }
    @Test fun wrongChecksumDeletesFile() {
        val r = dl("/bad")
        assertTrue(r is ModelDownload.Result.Fail && !r.resumable)
        assertFalse(File(dir, "m.part").exists())
    }
    @Test fun biggerThanPromisedDeletesFile() {
        val r = dl("/big")
        assertTrue(r is ModelDownload.Result.Fail && !r.resumable)
        assertFalse(File(dir, "m.part").exists())
    }
    @Test fun notEnoughStorageIsRefusedBeforeDownloading() {
        served.clear()
        val r = dl("/model", free = 1000)
        assertTrue(r is ModelDownload.Result.Fail)
        assertTrue(served.isEmpty())
    }
    @Test fun blockedHostIsRefused() {
        val r = ModelDownload.download(url("/model"), File(dir, "x.part"), data.size.toLong(), sha, { false })
        assertTrue(r is ModelDownload.Result.Fail)
    }
    @Test fun httpErrorIsReportedAndPartKept() {
        val r = dl("/gone")
        assertTrue(r is ModelDownload.Result.Fail && r.reason.contains("404"))
    }
    @Test fun cancelKeepsPartForResume() {
        var n = 0
        val r = dl("/model", cancelled = { n++ > 1 })
        assertTrue(r is ModelDownload.Result.Fail && r.resumable)
        assertTrue(dl("/model") is ModelDownload.Result.Ok)
    }
    @Test fun oversizedPartIsDiscarded() {
        File(dir, "m.part").writeBytes(ByteArray(data.size + 10))
        assertTrue(dl("/model") is ModelDownload.Result.Ok)
    }
    @Test fun alreadyCompletePartIsJustVerified() {
        File(dir, "m.part").writeBytes(data)
        served.clear()
        assertTrue(dl("/model") is ModelDownload.Result.Ok)
        assertTrue(served.isEmpty())
    }
}
