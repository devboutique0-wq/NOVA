package com.nova.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var cfg: Cfg
    private var testTts: TextToSpeech? = null
    private var listenerOwned = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Cfg(this)
        SecureStore.migrateOld(this)
        SecureStore.migrateSingleToPool(this)
        web = WebView(this)
        hardenWebView(web)
        web.setBackgroundColor(0)
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl(ASSET_URL)
        setContentView(web)
        NovaService.listener = { role, text ->
            runOnUiThread {
                if (!isDestroyed) {
                    web.evaluateJavascript(
                        "onNova(" + JSONObject.quote(role) + "," + JSONObject.quote(text) + ")", null
                    )
                }
            }
        }
        listenerOwned = true
        handleIntent(intent)
    }

    /** The page is a local asset only: no file/content access, no navigation away, no network sub-requests. */
    private fun hardenWebView(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.mediaPlaybackRequiresUserGesture = false   // lets the opening sound play without a tap
        s.domStorageEnabled = true                  // remembers the sound on/off choice
        s.allowFileAccess = false              // file:///android_asset/ is always readable regardless
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.setGeolocationEnabled(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Never navigate anywhere except our own page.
                return request?.url?.toString() != ASSET_URL
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return blocked()
                return if (u.startsWith(ASSET_PREFIX)) null else blocked()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null && !url.startsWith(ASSET_PREFIX)) view?.stopLoading()
            }
        }
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Back: the page decides (close sheet / settings, play the closing animation, or leave). */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.novaBack?novaBack():'exit')") { r ->
            if (r == null || r.contains("exit")) moveTaskToBack(true)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibe(ms: Long) {
        try {
            val v: android.os.Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (v == null) return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(ms)
            }
        } catch (e: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        NovaService.uiVisible = true
        pushFlow("resumed")      // back from an Android settings screen: the page re-checks its permission list
    }

    override fun onPause() {
        NovaService.uiVisible = false
        super.onPause()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        setIntent(i)
        handleIntent(i)
    }

    override fun onDestroy() {
        NovaService.uiVisible = false
        if (listenerOwned) NovaService.listener = null
        testTts?.shutdown()
        testTts = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.removeJavascriptInterface("Android")
        web.destroy()
        super.onDestroy()
    }

    // Tapped the "NOVA is off" notification after a phone restart (a visible, user-initiated start).
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("autostart", false) == true) {
            i.removeExtra("autostart")
            if (!NovaService.running && startFlow(false) == "started") moveTaskToBack(true)
        }
    }

    private fun isDefaultAssistant(): Boolean = try {
        (Settings.Secure.getString(contentResolver, "assistant") ?: "").startsWith("$packageName/")
    } catch (e: Exception) { false }

    private fun granted(p: String) =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /**
     * Requests missing permissions (only reached from a user tap or a user-tapped notification),
     * then starts NOVA. Returns a short status word for the UI: started / perm / nomic.
     * afterResult=true means Android just answered a permission dialog, so we never ask again (no loop).
     */
    private fun startFlow(afterResult: Boolean): String {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            if (afterResult) return "nomic"
            val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                ask.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            runOnUiThread { requestPermissions(ask.toTypedArray(), REQ_START) }
            return "perm"
        }
        if (!afterResult && Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            // Notification permission is needed so the foreground-service notification is visible.
            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_START) }
            return "perm"
        }
        cfg.active = true
        val svc = Intent(this, NovaService::class.java)
        runOnUiThread {
            try {
                startForegroundService(svc)
            } catch (e: Exception) {
                cfg.active = false
                pushFlow("start_failed")
            }
        }
        return "started"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_START -> pushFlow(startFlow(true))
            REQ_BASIC -> pushFlow("basics_done")
            REQ_CONTACTS -> {
                val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                pushFlow(if (ok) "contacts_ok" else "contacts_denied")
            }
        }
    }

    /** Tells the page the result of an asynchronous action (the page defines onFlow). */
    private fun pushFlow(status: String) {
        runOnUiThread {
            if (!isDestroyed) {
                web.evaluateJavascript(
                    "if(window.onFlow)onFlow(" + JSONObject.quote(status) + ")", null
                )
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun vibrate(ms: Int) { vibe(ms.toLong().coerceIn(5L, 400L)) }

        @JavascriptInterface
        fun running() = NovaService.running

        @JavascriptInterface
        fun hasKey() = SecureStore.hasKeys(this@MainActivity)

        /** Keys are only ever written here, never sent back to the page. */
        @JavascriptInterface
        fun saveKeys(raw: String): String = try {
            val keys = Logic.parseKeys(raw)
            if (keys.isEmpty()) "invalid"
            else if (SecureStore.saveKeys(this@MainActivity, keys)) "ok:" + keys.size
            else "fail"
        } catch (e: Exception) { "fail" }

        @JavascriptInterface
        fun keyCount(): Int = SecureStore.getKeys(this@MainActivity).size

        @JavascriptInterface
        fun clearKey() { SecureStore.clearKeys(this@MainActivity) }

        @JavascriptInterface
        fun stopSpeak() { NovaService.instance?.stopSpeaking() }

        @JavascriptInterface
        fun toggle(): String {
            if (NovaService.running) {
                cfg.active = false
                runOnUiThread { stopService(Intent(this@MainActivity, NovaService::class.java)) }
                return "stopped"
            }
            return startFlow(false)
        }

        /** READ_CONTACTS is requested here, on a user tap, and nowhere else. */
        @JavascriptInterface
        fun requestContacts(): String {
            if (granted(Manifest.permission.READ_CONTACTS)) return "granted"
            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS) }
            return "asked"
        }

        /** Brightness control needs the special "Modify system settings" permission; the user opens it here. */
        @JavascriptInterface
        fun openWriteSettings() {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    pushFlow("write_settings_failed")
                }
            }
        }

        /** The floating NOVA card needs "Display over other apps"; the user opens it here. */
        @JavascriptInterface
        fun openOverlaySettings() {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    pushFlow("overlay_settings_failed")
                }
            }
        }

        // ---------------------------------------------------------------- Update Center (what is new / what is missing)

        /** One snapshot for the Update Center page: version + which permissions are on. Nothing is changed here. */
        @JavascriptInterface
        fun getStatus(): String {
            val vName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
            return JSONObject()
                .put("versionName", vName)
                .put("versionCode", UpdateManager.appVersion(this@MainActivity))
                .put("mic", granted(Manifest.permission.RECORD_AUDIO))
                .put("notif", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
                .put("overlay", Settings.canDrawOverlays(this@MainActivity))
                .put("acc", NovaAccessibilityService.isEnabled(this@MainActivity))
                .put("notifAccess", DrivingBridge.listenerEnabled(this@MainActivity))
                .put("installOk", packageManager.canRequestPackageInstalls())
                .put("assistant", isDefaultAssistant())
                .put("contacts", granted(Manifest.permission.READ_CONTACTS))
                .put("writeSettings", Settings.System.canWrite(this@MainActivity))
                .put("feedUrl", cfg.feedUrl)
                .put("running", NovaService.running)
                .toString()
        }

        /** Opens Android's own "Default digital assistant app" choice. The user picks NOVA there; NOVA cannot set it by itself. */
        @JavascriptInterface
        fun openAssistantSettings() {
            runOnUiThread {
                val tries = listOf(
                    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS)
                )
                for (i in tries) {
                    try { startActivity(i); return@runOnUiThread } catch (e: Exception) { }
                }
                pushFlow("update_msg:Settings could not be opened. Open Settings > Apps > Default apps > Digital assistant app")
            }
        }

        /** Asks Android for the microphone and notification permission (the user sees the normal Android dialog). */
        @JavascriptInterface
        fun requestBasics(): String {
            val ask = ArrayList<String>()
            if (!granted(Manifest.permission.RECORD_AUDIO)) ask.add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                ask.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ask.isEmpty()) return "ok"
            runOnUiThread { requestPermissions(ask.toTypedArray(), REQ_BASIC) }
            return "asked"
        }

        /** Driving mode reads messages only after the user switches "Notification access" on for NOVA. */
        @JavascriptInterface
        fun openNotificationAccess() {
            runOnUiThread { DrivingBridge.openListenerSettings(this@MainActivity) }
        }

        // ---------------------------------------------------------------- updates (Discover -> Ask -> Add)

        /** "ok" or "bad" (the feed URL must be https). An empty URL turns update checks off. */
        @JavascriptInterface
        fun setFeedUrl(url: String): String {
            val u = url.trim()
            if (u.isEmpty()) { cfg.feedUrl = ""; return "ok" }
            if (!Updater.feedUrlOk(u)) return "bad"
            cfg.feedUrl = u
            return "ok"
        }

        @JavascriptInterface
        fun checkUpdates() {
            val url = cfg.feedUrl
            Thread {
                val e = if (url.isBlank()) "set the feed URL first" else UpdateManager.refresh(this@MainActivity, url)
                pushFlow(if (e == null) "updates_ok" else "updates_err:$e")
            }.start()
        }

        /** {"offers":[{id,type,title,sizeText,status}],"installed":{id:version}} */
        @JavascriptInterface
        fun getUpdates(): String {
            val arr = JSONArray()
            for (o in UpdateManager.offers(this@MainActivity)) {
                arr.put(
                    JSONObject().put("id", o.item.id).put("type", o.item.type).put("title", o.item.title)
                        .put("sizeText", Updater.sizeText(o.item.size)).put("status", o.status)
                )
            }
            return JSONObject().put("offers", arr)
                .put("installed", JSONObject(UpdateManager.installedJson(this@MainActivity))).toString()
        }

        /** Only the user's tap on INSTALL calls this. The download is verified (https, host list, SHA-256, size). */
        @JavascriptInterface
        fun installUpdate(id: String): String {
            UpdateManager.install(this@MainActivity, id) { ok, msg ->
                if (!ok || msg.isNotEmpty()) pushFlow("update_msg:$msg")
            }
            return "started"
        }

        @JavascriptInterface
        fun skipUpdate(id: String): String { UpdateManager.skip(this@MainActivity, id); return "ok" }

        @JavascriptInterface
        fun rollbackUpdate(id: String): String = UpdateManager.rollback(this@MainActivity, id)

        /** "Install unknown apps" for NOVA - needed once, only for installing a new NOVA version. */
        @JavascriptInterface
        fun openInstallSettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    pushFlow("update_msg:Settings could not be opened. Use APP INFO > Install unknown apps")
                }
            }
        }

        /** {"skills":n,"events":n,"learned":n} - how much NOVA has learned on this phone. */
        @JavascriptInterface
        fun brainStats(): String = BrainStore.get(this@MainActivity).statsJson()

        /** Wipes all learned shortcuts and the local event log. */
        @JavascriptInterface
        fun brainClear(): String {
            BrainStore.get(this@MainActivity).clear()
            return "ok"
        }

        @JavascriptInterface
        fun getSettings(): String {
            val keyCount = SecureStore.getKeys(this@MainActivity).size
            val hasKey = SecureStore.hasKeys(this@MainActivity)
            return JSONObject()
                .put("wake", cfg.wake)
                .put("lang", cfg.lang)
                .put("rate", cfg.rate.toDouble())
                .put("pitch", cfg.pitch.toDouble())
                .put("model", cfg.model)
                .put("models", JSONArray(Logic.MODELS))
                .put("autostart", cfg.autostart)
                .put("hasKey", hasKey)
                .put("keyCount", keyCount)
                .put("localMode", !hasKey)
                .put("acc", NovaAccessibilityService.isEnabled(this@MainActivity))
                .put("screenMonitor", cfg.screenMonitor)
                .put("contacts", granted(Manifest.permission.READ_CONTACTS))
                .put("writeSettings", Settings.System.canWrite(this@MainActivity))
                .put("overlay", Settings.canDrawOverlays(this@MainActivity))
                .put("feedUrl", cfg.feedUrl)
                .put("running", NovaService.running)
                .toString()
        }

        /** Returns ok, need_acc (everything saved but screen monitoring stayed OFF), bad_wake or error. */
        @JavascriptInterface
        fun saveSettings(json: String): String {
            return try {
                val o = JSONObject(json)
                val w = Logic.cleanWake(o.optString("wake"))
                if (w == null) {
                    "bad_wake"
                } else {
                    cfg.wake = w
                    cfg.lang = if (o.optString("lang") == "en") "en" else "hi"
                    cfg.rate = Logic.clampRate(o.optDouble("rate", 1.0).toFloat())
                    cfg.pitch = Logic.clampPitch(o.optDouble("pitch", 1.0).toFloat())
                    cfg.model = Logic.cleanModel(o.optString("model"))
                    cfg.autostart = o.optBoolean("autostart", true)
                    val wantMonitor = o.optBoolean("screenMonitor", false)
                    val accOn = NovaAccessibilityService.isEnabled(this@MainActivity)
                    val before = cfg.screenMonitor
                    cfg.screenMonitor = wantMonitor && accOn
                    if (before != cfg.screenMonitor) NovaAccessibilityService.monitorChanged()
                    NovaService.instance?.reloadVoice()
                    if (wantMonitor && !accOn) "need_acc" else "ok"
                }
            } catch (e: Exception) {
                "error"
            }
        }

        @JavascriptInterface
        fun testVoice() {
            runOnUiThread {
                val text = if (cfg.lang == "en") "Hello, I am Nova" else "नमस्ते, मैं नोवा हूँ"
                val svc = NovaService.instance
                if (svc != null) {
                    svc.speakTest(text)
                } else {
                    testTts?.shutdown()
                    var created: TextToSpeech? = null
                    created = TextToSpeech(this@MainActivity) { st ->
                        if (st == TextToSpeech.SUCCESS) {
                            cfg.applyVoice(created)
                            created?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test")
                        }
                    }
                    testTts = created
                }
            }
        }

        @JavascriptInterface
        fun openAccessibility() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        @JavascriptInterface
        fun openAppInfo() {
            runOnUiThread {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private companion object {
        const val ASSET_URL = "file:///android_asset/ui.html"
        const val ASSET_PREFIX = "file:///android_asset/"
        const val REQ_START = 1
        const val REQ_CONTACTS = 2
        const val REQ_BASIC = 3
    }
}
