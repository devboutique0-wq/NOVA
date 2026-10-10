package com.nova.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Discover -> Ask -> Add. Reads a feed the USER chose (their own GitHub feed.json), keeps a list of offers, and installs
 * one only when told to (after the user's yes). Everything is verified; nothing runs without approval.
 *
 *  skills : a JSON pack of shortcuts -> validated one by one by Brain.validateSkill (data only, never code).
 *  model  : a file for the offline brain slot (see LocalBrain.kt). The previous model is kept for rollback.
 *  apk    : must be this same app, newer, and signed with the same key. Android itself then shows the install
 *           screen and the user taps Install. There is no silent install, and no downgrade/rollback for an apk.
 */
object UpdateManager {

    class Offer(val item: UpdateItem, val status: String)

    private val status = ConcurrentHashMap<String, String>()     // id -> offered|downloading N%|installed|failed: ...
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "updates.json")

    // ------------------------------------------------------------------ persisted state

    private class State {
        val installed = LinkedHashMap<String, Int>()
        val declined = HashSet<String>()
        val offers = ArrayList<UpdateItem>()
        var lastCheck = 0L
        var feedHost: String? = null
        var modelPath = ""
        var prevModelPath = ""
        var modelId = ""
    }

    private fun load(ctx: Context): State {
        val s = State()
        try {
            val f = file(ctx)
            if (!f.exists()) return s
            val o = JSONObject(f.readText())
            val ins = o.optJSONObject("installed")
            if (ins != null) for (k in ins.keys()) s.installed[k] = ins.optInt(k)
            val d = o.optJSONArray("declined")
            if (d != null) for (i in 0 until d.length()) s.declined.add(d.optString(i))
            val of = o.optJSONArray("offers")
            if (of != null) for (i in 0 until of.length()) toItem(of.optJSONObject(i))?.let { s.offers.add(it) }
            s.lastCheck = o.optLong("lastCheck", 0L)
            s.feedHost = o.optString("feedHost", "").ifEmpty { null }
            s.modelPath = o.optString("modelPath", "")
            s.prevModelPath = o.optString("prevModelPath", "")
            s.modelId = o.optString("modelId", "")
        } catch (e: Exception) {
            return State()
        }
        return s
    }

    private fun save(ctx: Context, s: State) {
        try {
            val o = JSONObject()
            val ins = JSONObject()
            for ((k, v) in s.installed) ins.put(k, v)
            val of = JSONArray()
            for (i in s.offers) of.put(fromItem(i))
            o.put("installed", ins).put("declined", JSONArray(s.declined.toList())).put("offers", of)
                .put("lastCheck", s.lastCheck).put("feedHost", s.feedHost ?: "")
                .put("modelPath", s.modelPath).put("prevModelPath", s.prevModelPath).put("modelId", s.modelId)
            val tmp = File(file(ctx).parentFile, "updates.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file(ctx))) { file(ctx).writeText(o.toString()); tmp.delete() }
        } catch (e: Exception) {
            // disk problem: state stays in memory for this run only
        }
    }

    private fun toItem(j: JSONObject?): UpdateItem? {
        if (j == null) return null
        return UpdateItem(
            j.optString("id"), j.optString("type"), j.optString("title"), j.optString("url"),
            j.optString("sha256").lowercase(), j.optLong("size", 0L), j.optInt("version", 0), j.optInt("minApp", 0)
        )
    }

    private fun fromItem(i: UpdateItem): JSONObject = JSONObject().put("id", i.id).put("type", i.type)
        .put("title", i.title).put("url", i.url).put("sha256", i.sha256).put("size", i.size)
        .put("version", i.version).put("minApp", i.minApp)

    // ------------------------------------------------------------------ helpers

    fun appVersion(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (e: Exception) { 0 }

    /** Active offline-model file, or "" when none is installed. Read by LocalBrain. */
    fun activeModelPath(ctx: Context): String = synchronized(lock) {
        val p = load(ctx).modelPath
        if (p.isNotEmpty() && File(p).exists()) p else ""
    }

    fun offers(ctx: Context): List<Offer> = synchronized(lock) {
        val s = load(ctx)
        s.offers.filter { it.id !in s.declined && Updater.isNewer(it.version, s.installed[it.id]) }
            .map { Offer(it, status[it.id] ?: "offered") }
    }

    fun installedJson(ctx: Context): String = synchronized(lock) {
        val s = load(ctx)
        val o = JSONObject()
        for ((k, v) in s.installed) o.put(k, v)
        o.toString()
    }

    fun skip(ctx: Context, id: String) = synchronized(lock) {
        val s = load(ctx)
        s.declined.add(id)
        save(ctx, s)
    }

    fun statusOf(id: String): String = status[id] ?: ""

    // ------------------------------------------------------------------ network

    private fun open(url: String, feedHost: String?): HttpURLConnection {
        var cur = url
        for (hop in 0..5) {
            if (!Updater.hostAllowed(cur, feedHost)) throw IOException("blocked host")
            val c = URL(cur).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false           // every redirect is checked against the allow-list
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "NOVA-updater")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("redirect without target")
                cur = URL(URL(cur), loc).toString()
                continue
            }
            if (code != 200) { c.disconnect(); throw IOException("HTTP $code") }
            return c
        }
        throw IOException("too many redirects")
    }

    /** Downloads the feed text. Throws IOException with a short reason. The feed may live on any https host. */
    private fun fetchFeed(url: String): String {
        var cur = url.trim()
        for (hop in 0..5) {
            if (Updater.hostOf(cur) == null) throw IOException("bad redirect")
            val x = URL(cur).openConnection() as HttpURLConnection
            x.instanceFollowRedirects = false
            x.connectTimeout = 10000
            x.readTimeout = 30000
            x.setRequestProperty("User-Agent", "NOVA-updater")
            try {
                val code = x.responseCode
                if (code in 300..399) {
                    val loc = x.getHeaderField("Location") ?: throw IOException("bad redirect")
                    cur = URL(URL(cur), loc).toString()
                    continue
                }
                if (code != 200) throw IOException("feed HTTP $code")
                val buf = java.io.ByteArrayOutputStream()
                x.inputStream.use { ins ->
                    val b = ByteArray(8192)
                    while (true) {
                        val n = ins.read(b)
                        if (n < 0) break
                        buf.write(b, 0, n)
                        if (buf.size() > Updater.MAX_FEED_BYTES) throw IOException("feed too large")
                    }
                }
                return buf.toString("UTF-8")
            } finally {
                x.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    /** Reads the feed. null = ok (offers updated), otherwise a short error. Call from a worker thread. */
    fun refresh(ctx: Context, feedUrl: String): String? {
        if (!Updater.feedUrlOk(feedUrl)) return "feed URL must be https"
        val host = Updater.hostOf(feedUrl.trim()) ?: return "feed URL must be https"
        val text: String = try {
            fetchFeed(feedUrl)
        } catch (e: IOException) {
            return e.message ?: "feed error"
        } catch (e: Exception) {
            return "no connection"
        }
        val items = ArrayList<UpdateItem>()
        try {
            val arr = JSONObject(text).optJSONArray("items") ?: JSONArray()
            val app = appVersion(ctx)
            for (i in 0 until minOf(arr.length(), 50)) {
                val cand = toItem(arr.optJSONObject(i)) ?: continue
                if (Updater.validate(cand, app, host) == null) items.add(cand)     // invalid items are dropped silently
            }
        } catch (e: Exception) {
            return "feed is not valid JSON"
        }
        synchronized(lock) {
            val s = load(ctx)
            val keepBuiltIn = s.offers.any { it.id == ModelCatalog.ID }     // the built-in offline model survives a feed refresh
            s.offers.clear()
            s.offers.addAll(items)
            if (keepBuiltIn && s.offers.none { it.id == ModelCatalog.ID }) s.offers.add(ModelCatalog.ITEM)
            s.feedHost = host
            s.lastCheck = System.currentTimeMillis()
            save(ctx, s)
        }
        return null
    }

    fun lastCheck(ctx: Context): Long = synchronized(lock) { load(ctx).lastCheck }

    /**
     * Puts the built-in offline model (ModelCatalog.ITEM) on the offer list so the user can tap DOWNLOAD.
     * Nothing is downloaded here. null = offered, otherwise a short reason.
     */
    fun offerOfflineBrain(ctx: Context): String? = synchronized(lock) {
        val s = load(ctx)
        val item = ModelCatalog.ITEM
        val why = Updater.validate(item, appVersion(ctx), s.feedHost)
        if (why != null) return@synchronized why
        s.declined.remove(item.id)
        if (s.offers.none { it.id == item.id }) s.offers.add(item)
        save(ctx, s)
        null
    }

    fun modelInstalledId(ctx: Context): String = synchronized(lock) { load(ctx).modelId }

    // ------------------------------------------------------------------ install (after the user said yes)

    /** Starts the download + verify + apply on its own thread. [done] gets (success, sentence to tell the user). */
    fun install(ctx: Context, id: String, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var ok = false
            var msg: String
            try {
                val r = doInstall(app, id)
                ok = r.first
                msg = r.second
            } catch (e: Exception) {
                msg = "Install failed: " + (e.message ?: e.javaClass.simpleName)
            }
            status[id] = if (ok) "installed" else "failed: $msg"
            try { done(ok, msg) } catch (e: Exception) { }
        }.start()
    }

    private fun doInstall(ctx: Context, id: String): Pair<Boolean, String> {
        val s0 = synchronized(lock) { load(ctx) }
        val item = s0.offers.firstOrNull { it.id == id } ?: return Pair(false, "That update is no longer offered")
        if (Updater.validate(item, appVersion(ctx), s0.feedHost) != null) return Pair(false, "That update did not pass the safety check")
        if (status[id]?.startsWith("downloading") == true) return Pair(false, "Already downloading")

        val dir = File(ctx.filesDir, "updates"); dir.mkdirs()
        val part = File(dir, Updater.safeName(id) + ".part")
        if (item.type == "model") {
            // big file: resumable download (a stopped download keeps the .part file; tapping again continues)
            status[id] = "downloading 0%"
            val r = ModelDownload.download(
                item.url, part, item.size, item.sha256,
                { Updater.hostAllowed(it, s0.feedHost) }, { false },
                { pct -> status[id] = "downloading $pct%" }, dir.usableSpace
            )
            return when (r) {
                is ModelDownload.Result.Ok -> applyModel(ctx, item, r.file)
                is ModelDownload.Result.Fail ->
                    if (r.resumable) Pair(false, "Download stopped (" + r.reason + "). Tap again to resume")
                    else Pair(false, "Download refused (" + r.reason + "), the file was discarded")
            }
        }
        if (dir.usableSpace < item.size + item.size / 5) return Pair(false, "Not enough free storage")

        status[id] = "downloading 0%"
        val conn = open(item.url, s0.feedHost)
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            conn.inputStream.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    var lastPct = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > item.size) throw IOException("file is bigger than the feed promised")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        val pct = (total * 100 / item.size).toInt()
                        if (pct != lastPct && pct % 5 == 0) { lastPct = pct; status[id] = "downloading $pct%" }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (total != item.size || Updater.hex(md.digest()) != item.sha256) {
            part.delete()
            return Pair(false, "Download did not match the checksum, so I discarded it")
        }
        return when (item.type) {
            "skills" -> applySkills(ctx, item, part)
            "model" -> applyModel(ctx, item, part)
            "apk" -> applyApk(ctx, item, part)
            else -> { part.delete(); Pair(false, "Unknown update type") }
        }
    }

    private fun markInstalled(ctx: Context, item: UpdateItem, edit: (State) -> Unit = {}) = synchronized(lock) {
        val s = load(ctx)
        s.installed[item.id] = item.version
        edit(s)
        save(ctx, s)
    }

    private fun applySkills(ctx: Context, item: UpdateItem, f: File): Pair<Boolean, String> {
        val brain = BrainStore.get(ctx)
        val text = try { f.readText() } catch (e: Exception) { f.delete(); return Pair(false, "Could not read the pack") }
        f.delete()
        val arr = try { JSONObject(text).optJSONArray("skills") } catch (e: Exception) { null }
            ?: return Pair(false, "The pack is not valid")
        val source = "pack:" + item.id
        brain.removeBySource(source)                               // an upgrade replaces the old version of this pack
        var added = 0
        var refused = 0
        for (i in 0 until minOf(arr.length(), 100)) {
            val j = arr.optJSONObject(i) ?: continue
            val tr = j.optJSONArray("triggers") ?: JSONArray()
            val st = j.optJSONArray("steps") ?: JSONArray()
            val sk = Skill(
                j.optString("name"),
                (0 until tr.length()).map { tr.optString(it) },
                (0 until st.length()).map { st.optString(it) },
                source
            )
            if (brain.addSkill(sk) == null) added++ else refused++
        }
        if (added == 0) return Pair(false, "No shortcut in the pack passed the safety rules")
        markInstalled(ctx, item)
        return Pair(true, "Added $added shortcuts from '${item.title}'" + if (refused > 0) " ($refused refused by safety rules)" else "")
    }

    private fun applyModel(ctx: Context, item: UpdateItem, f: File): Pair<Boolean, String> {
        val dir = File(ctx.filesDir, "models"); dir.mkdirs()
        val dest = File(dir, Updater.safeName(item.id) + "-v" + item.version + ".bin")
        if (!f.renameTo(dest)) { f.delete(); return Pair(false, "Could not save the model") }
        markInstalled(ctx, item) { s ->
            s.prevModelPath = s.modelPath
            s.modelPath = dest.absolutePath
            s.modelId = item.id
        }
        return Pair(true, "Offline brain model '${item.title}' saved. It will be used once an engine is plugged in")
    }

    private fun applyApk(ctx: Context, item: UpdateItem, f: File): Pair<Boolean, String> {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // keep the verified file; the user allows "Install unknown apps" for NOVA once, then taps INSTALL again
            val keep = File(f.parentFile, Updater.safeName(item.id) + ".apk")
            f.renameTo(keep)
            return Pair(false, "Allow 'Install unknown apps' for NOVA in Settings, then try the update again")
        }
        val why = checkApk(ctx, f)
        if (why != null) { f.delete(); return Pair(false, "The new app file was refused: $why") }
        val pi = ctx.packageManager.packageInstaller
        val sid = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(sid).use { sess ->
            f.inputStream().use { ins ->
                sess.openWrite("nova.apk", 0, f.length()).use { out ->
                    ins.copyTo(out)
                    sess.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction("com.nova.assistant.INSTALL_RESULT")
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            sess.commit(PendingIntent.getBroadcast(ctx, sid, intent, flags).intentSender)
        }
        f.delete()
        markInstalled(ctx, item)
        return Pair(true, "The new NOVA is ready. Tap Install on the screen Android shows")
    }

    /** Same package, higher versionCode, same signing key. null = fine. */
    private fun checkApk(ctx: Context, f: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val newer = pm.getPackageArchiveInfo(f.absolutePath, flags) ?: return "not a valid app file"
        if (newer.packageName != ctx.packageName) return "different app"
        val newCode = if (Build.VERSION.SDK_INT >= 28) newer.longVersionCode.toInt() else newer.versionCode
        if (newCode <= appVersion(ctx)) return "not newer"
        val cur = pm.getPackageInfo(ctx.packageName, flags)
        val a = sigs(newer)
        val b = sigs(cur)
        if (a.isEmpty() || b.isEmpty()) return "cannot read the signature"
        if (a.none { x -> b.any { y -> x.contentEquals(y) } }) return "signed with a different key"
        return null
    }

    private fun sigs(pi: android.content.pm.PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptyList()
            val arr = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            return arr?.map { it.toByteArray() } ?: emptyList()
        }
        return pi.signatures?.map { it.toByteArray() } ?: emptyList()
    }

    // ------------------------------------------------------------------ rollback (skills and models only)

    fun rollback(ctx: Context, id: String): String = synchronized(lock) {
        val s = load(ctx)
        val ver = s.installed[id] ?: return@synchronized "That update is not installed"
        val item = s.offers.firstOrNull { it.id == id }
        if (item?.type == "apk") return@synchronized "A new app version cannot be rolled back from inside the app"
        val brain = BrainStore.get(ctx)
        val removed = brain.removeBySource("pack:$id")
        var msg: String
        if (removed > 0) {
            msg = "Removed $removed shortcuts from that pack"
        } else if (s.modelId == id) {
            val old = s.modelPath
            s.modelPath = s.prevModelPath
            s.prevModelPath = ""
            s.modelId = ""
            try { File(old).delete() } catch (e: Exception) { }
            msg = "Went back to the previous offline model"
        } else {
            return@synchronized "Nothing to roll back for that update (v$ver)"
        }
        s.installed.remove(id)
        status.remove(id)
        save(ctx, s)
        msg
    }

    // ------------------------------------------------------------------ tell the user

    fun notifyUser(ctx: Context, text: String, tap: Intent?) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("nova_updates", "NOVA updates", NotificationManager.IMPORTANCE_DEFAULT))
            val target = tap ?: ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(ctx, 2, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(ctx, "nova_updates")
                .setContentTitle("NOVA")
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        } catch (e: Exception) {
            // notifications not allowed: the update list in the app still shows everything
        }
    }
}
