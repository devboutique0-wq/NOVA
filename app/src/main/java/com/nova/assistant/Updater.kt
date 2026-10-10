package com.nova.assistant

import java.net.URI
import java.security.MessageDigest

/** One thing NOVA found in the update feed and may offer to the user. */
data class UpdateItem(
    val id: String,
    val type: String,        // "skills" (shortcut pack), "model" (offline brain model), "apk" (new app version)
    val title: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val version: Int,        // pack/model version, or the versionCode for an apk
    val minApp: Int          // lowest app versionCode this item works with
)

/**
 * Pure rules for the Discover -> Ask -> Add system. Nothing is ever installed without (1) the user's yes,
 * (2) an https download from an allow-listed host, (3) an exact SHA-256 + size match with what the feed promised.
 * An APK additionally must be the same app, newer, and signed with the SAME key (checked in UpdateManager).
 */
object Updater {
    val TYPES = setOf("skills", "model", "apk")

    /** Hosts an item may be downloaded from (the feed's own host is added at call time). */
    val HOSTS = setOf(
        "raw.githubusercontent.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "huggingface.co",
        "cdn-lfs.huggingface.co",
        "cdn-lfs-us-1.huggingface.co",
        "cas-bridge.xethub.hf.co"
    )

    const val MAX_FEED_BYTES = 256 * 1024
    const val MAX_SKILLS_BYTES = 200L * 1024
    const val MAX_APK_BYTES = 150L * 1024 * 1024
    const val MAX_MODEL_BYTES = 2L * 1024 * 1024 * 1024
    const val CHECK_GAP_MS = 22L * 60 * 60 * 1000          // automatic check at most about once a day

    private val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")
    private val SHA_RE = Regex("^[0-9a-f]{64}$")

    fun hostOf(url: String): String? = try {
        val u = URI(url)
        if (u.scheme == "https" && u.userInfo == null) u.host?.lowercase() else null
    } catch (e: Exception) { null }

    /** https only, no user-info tricks, host must be on the list (or equal to [extraHost], the feed's own host). */
    fun hostAllowed(url: String, extraHost: String? = null): Boolean {
        val h = hostOf(url) ?: return false
        return h in HOSTS || isHfHost(h) || (extraHost != null && h == extraHost.lowercase())
    }

    /** Hugging Face serves big files from changing CDN hosts under its own domains; the SHA-256 pin protects the content. */
    fun isHfHost(h: String): Boolean = h.endsWith(".hf.co") || h.endsWith(".huggingface.co")

    /** The feed URL itself is chosen by the user, so any https host is fine - but it must be https. */
    fun feedUrlOk(url: String): Boolean = hostOf(url.trim()) != null && url.trim().length <= 300

    fun maxBytes(type: String): Long = when (type) {
        "skills" -> MAX_SKILLS_BYTES
        "apk" -> MAX_APK_BYTES
        "model" -> MAX_MODEL_BYTES
        else -> 0L
    }

    /** null = acceptable, otherwise a short reason. [appVersion] = the installed app's versionCode. */
    fun validate(i: UpdateItem, appVersion: Int, feedHost: String?): String? {
        if (!ID_RE.matches(i.id)) return "bad id"
        if (i.type !in TYPES) return "bad type"
        if (i.title.isBlank() || i.title.length > 80) return "bad title"
        if (!hostAllowed(i.url, feedHost)) return "download host not allowed"
        if (!SHA_RE.matches(i.sha256)) return "bad sha256"
        if (i.size <= 0L || i.size > maxBytes(i.type)) return "bad size"
        if (i.version < 0) return "bad version"
        if (i.minApp > appVersion) return "needs a newer app"
        if (i.type == "apk" && i.version <= appVersion) return "not newer than the installed app"
        return null
    }

    fun isNewer(offered: Int, installed: Int?): Boolean = installed == null || offered > installed

    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> (bytes / 1024).toString() + " KB"
        else -> "$bytes B"
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x.toInt() and 0xff))
        return sb.toString()
    }

    fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    fun safeName(id: String): String = id.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
}
