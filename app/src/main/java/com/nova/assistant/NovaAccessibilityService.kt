package com.nova.assistant

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * What this service does (and nothing else):
 *  1. Global navigation (home/back/recents/notifications/quick settings/lock) on a local voice command.
 *  2. Press the green SEND button in WhatsApp after the user said "yes" (WhatsApp packages only,
 *     only when the text box holds exactly the confirmed message, 15 s time limit).
 *  3. Read the visible accessibility TEXT when the user says "analyze screen", or - only if the user
 *     switched Screen Monitoring ON - scan it locally (debounced) for obvious error/warning wording.
 * It never stores screen text and never sends anything anywhere by itself.
 */
class NovaAccessibilityService : AccessibilityService() {

    class Snap(val pkg: String, val lines: List<String>)

    companion object {
        @Volatile var instance: NovaAccessibilityService? = null
        private val PKGS = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val SEND_WORDS = setOf("send", "भेजें", "भेजे", "enviar", "senden", "envoyer")
        private const val TIMEOUT_MS = 15000L
        private const val DEBOUNCE_MS = 1000L
        private const val MIN_SCAN_GAP_MS = 3000L
        private const val MAX_LINES = 300

        fun armSend(message: String, cb: (Boolean) -> Unit) {
            val s = instance
            if (s == null) cb(false) else s.arm(message, cb)
        }

        /**
         * Visible text of the active window. Password fields are never read. Editable fields (what the
         * user is typing) are only included when [includeEditable] is true (local use only).
         * Returns null when Accessibility is not connected or Android exposes no window.
         */
        fun readScreen(includeEditable: Boolean): Snap? {
            val s = instance ?: return null
            val root = s.rootInActiveWindow ?: return null
            val out = ArrayList<String>()
            collect(root, 0, out, includeEditable)
            return Snap(root.packageName?.toString() ?: "", out)
        }

        fun foregroundPackage(): String? = instance?.rootInActiveWindow?.packageName?.toString()

        fun performGlobalAction(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        /** Cancels pending scans at once when monitoring is switched off. */
        fun monitorChanged() { instance?.onMonitorChanged() }

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val s = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return s.split(':').any {
                val c = ComponentName.unflattenFromString(it)
                c != null && c.packageName == ctx.packageName && c.className.endsWith("NovaAccessibilityService")
            }
        }

        private fun collect(n: AccessibilityNodeInfo?, depth: Int, out: ArrayList<String>, includeEditable: Boolean) {
            if (n == null || depth > 30 || out.size >= MAX_LINES) return
            try {
                if (!n.isVisibleToUser) return
                if (!n.isPassword && (includeEditable || !n.isEditable)) {
                    val t = n.text?.toString()?.trim()
                    val d = n.contentDescription?.toString()?.trim()
                    val s = if (!t.isNullOrBlank()) t else d
                    if (!s.isNullOrBlank()) out.add(s.take(200))
                }
                for (i in 0 until n.childCount) collect(n.getChild(i), depth + 1, out, includeEditable)
            } catch (e: Exception) {
                // node went away while walking: ignore
            }
        }
    }

    private class Job(val message: String, val cb: (Boolean) -> Unit, val deadline: Long)

    private val h = Handler(Looper.getMainLooper())
    private val cfg by lazy { Cfg(this) }
    private var job: Job? = null
    private var scanQueued = false
    private var lastScanAt = -MIN_SCAN_GAP_MS
    private var lastAlertAt = -1L
    private var lastAlertKey = ""

    private val tick = object : Runnable {
        override fun run() {
            val j = job ?: return
            if (SystemClock.elapsedRealtime() > j.deadline) { done(false); return }
            if (!attempt()) h.postDelayed(this, 500)
        }
    }

    private val scan = Runnable {
        scanQueued = false
        if (!cfg.screenMonitor) return@Runnable
        lastScanAt = SystemClock.elapsedRealtime()
        val snap = readScreen(false) ?: return@Runnable
        if (snap.pkg == packageName || snap.pkg in PKGS && job != null) return@Runnable
        val hit = Logic.detectAlert(snap.lines) ?: return@Runnable
        val key = snap.pkg + "|" + hit
        val now = SystemClock.elapsedRealtime()
        if (!Logic.shouldAnnounce(now, lastAlertAt, lastAlertKey, key)) return@Runnable
        // NovaService only speaks if it is idle (not busy, not waiting for a confirmation, not talking).
        if (NovaService.instance?.announceIfIdle(
                "Screen alert: $hit. Say analyze screen for details."
            ) == true
        ) {
            lastAlertAt = now
            lastAlertKey = key
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        updateMask()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        h.removeCallbacksAndMessages(null)
        scanQueued = false
        val j = job
        job = null
        j?.cb?.invoke(false)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (job != null) attempt()
        if (!cfg.screenMonitor) return
        if (e?.packageName?.toString() == packageName) return
        if (scanQueued) return
        scanQueued = true
        val wait = maxOf(DEBOUNCE_MS, lastScanAt + MIN_SCAN_GAP_MS - SystemClock.elapsedRealtime())
        h.postDelayed(scan, wait)
    }

    private fun onMonitorChanged() {
        h.post {
            if (!cfg.screenMonitor) {
                h.removeCallbacks(scan)
                scanQueued = false
            }
            updateMask()
        }
    }

    /** Content-change events are only requested while they are actually needed. */
    private fun updateMask() {
        try {
            val info = serviceInfo ?: return
            val wantContent = cfg.screenMonitor || job != null
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                (if (wantContent) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0)
            serviceInfo = info
        } catch (e: Exception) {
            // service is shutting down
        }
    }

    private fun arm(message: String, cb: (Boolean) -> Unit) {
        h.post {
            val old = job
            job = Job(message, cb, SystemClock.elapsedRealtime() + TIMEOUT_MS)
            old?.cb?.invoke(false) // a newer message replaces (and cancels) the older one
            updateMask()
            h.removeCallbacks(tick)
            h.postDelayed(tick, 700)
        }
    }

    private fun done(ok: Boolean) {
        val j = job
        job = null
        h.removeCallbacks(tick)
        updateMask()
        j?.cb?.invoke(ok)
    }

    /** True when the job is finished (sent). False = keep waiting. */
    private fun attempt(): Boolean {
        val j = job ?: return true
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        if (pkg !in PKGS) return false
        // Only press send when the text box holds exactly the message the user confirmed.
        val entry = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry")?.firstOrNull()
            ?: findEditable(root, 0) ?: return false
        val typed = entry.text?.toString() ?: return false
        if (!Logic.sameMessage(typed, j.message)) return false
        val send = findSend(root, pkg) ?: return false
        if (clickNode(send)) { done(true); return true }
        return false
    }

    private fun findEditable(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 14) return null
        if (n.isEditable && n.className?.toString()?.endsWith("EditText") == true) return n
        for (i in 0 until n.childCount) {
            val r = findEditable(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send")?.firstOrNull()?.let { return it }
        return search(root, 0)
    }

    private fun search(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 12) return null
        val d = n.contentDescription?.toString()?.trim()?.lowercase()
        if (d != null && d in SEND_WORDS && n.isClickable) return n
        for (i in 0 until n.childCount) {
            val r = search(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
            depth++
        }
        return false
    }
}
