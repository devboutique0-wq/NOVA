package com.nova.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import org.json.JSONArray
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NOVA foreground service.
 *  PART 1A (this half): lifecycle, Vosk model, mic loop, TTS state machine, confirmation, command routing.
 *  PART 1B (second half, at the marker at the end): phone tools, launch verification, cloud failover,
 *  screen analysis. 1A calls these 1B functions: runLocalTool(Logic.Cmd), analyzeScreen(), askCloud(ByteArray, String).
 */
class NovaService : Service() {

    companion object {
        private const val MAX_FOLLOW = 3   // v16: at most 3 follow-up listens in a row without the wake word
        private val NO_FOLLOW_KINDS = setOf("stop", "sleep", "agent", "image", "site", "learn", "publish", "screen_end")
        @Volatile var running = false
        @Volatile var uiVisible = false          // set by MainActivity.onResume/onPause (PART 2)
        var listener: ((String, String) -> Unit)? = null
        var permRequest: ((String) -> Unit)? = null   // v31: the open app shows the Android permission dialog at the moment a command needs it
        var publishListener: ((Boolean, String, String) -> Unit)? = null   // v16: voice publish finished -> chat screen (ok, message, url)
        var learnListener: (() -> Unit)? = null   // v16: a new note arrived -> refresh Settings > KYA SEEKHA
        @Volatile var lastSiteSubject = ""   // v16: the newest built website, so PUBLISH in the chat can send it
        @Volatile var lastSiteHtml = ""
        var siteListener: ((Boolean, String, String, String) -> Unit)? = null   // v16: finished website -> chat screen (ok, message, html, saved place)
        var imageListener: ((Boolean, String, String, String) -> Unit)? = null   // v30: finished picture -> chat screen (ok, message, base64 thumb, saved place)
        @Volatile var instance: NovaService? = null
        @Volatile private var loopThread: Thread? = null

        const val RATE = 16000
        const val MIN_HEAR_BYTES = 25600   // about 0.8 s of 16 kHz audio: shorter clips are never sent to be transcribed
        private const val HUD_MAX_MS = 35000L
        private const val MAX_READ_ERRORS = 5
    }

    /** An action that waits for the user's spoken "yes" (call, WhatsApp send). Expires after PENDING_TTL_MS. */
    private class Pending(val prompt: String, val createdAt: Long, val onNo: (() -> Unit)? = null, val run: () -> String)

    private val h = Handler(Looper.getMainLooper())
    private lateinit var cfg: Cfg
    private var tts: TextToSpeech? = null

    // TTS state machine. Every utterance has its own id so an old onStop/onDone cannot unblock the mic.
    @Volatile private var ttsReady = false
    @Volatile private var ttsActive = false
    @Volatile private var ttsDeadline = 0L          // watchdog: a lost callback can never block the mic forever
    @Volatile private var curUtt = ""
    @Volatile private var confirmUtt = ""
    private val uttSeq = AtomicInteger(0)

    @Volatile private var busy = false              // a turn is being processed
    @Volatile private var listening = false         // the loop is capturing a command / answer
    @Volatile private var pending: Pending? = null

    // PART 3 (silent turn): set per order when the owner adds "chup rehna" / "bina bole" / "don't speak". See SilentRules.
    @Volatile private var lastVoiceAt = 0L              // PART 4: last time a voice-like sound was heard while asleep (NOVA never starts talking over people)
    @Volatile private var silentTurn = false
    @Volatile private var silentCarryAt = 0L            // a silent order that asked a yes/no: the spoken yes keeps the result silent
    @Volatile private var silentCarryP: Any? = null     // the exact question that silent order asked (a different question never inherits silence)
    @Volatile private var turnFailed = false
    private val turnOk: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile private var silentFillerHeard = false
    @Volatile private var agentSilent = false
    @Volatile private var agentAsked = false
    @Volatile private var lastKind = ""                      // kind of the command the last turn ran (decides spoken or silent reply)
    @Volatile private var historyAt = 0L                     // when the cloud chat memory was last used
    @Volatile private var cloudCoolUntil = 0L                // after a cloud failure the cloud is skipped until this time (AiRouter.cooldownMs)
    @Volatile private var lastCloudFail = ""                 // Logic.failureKind of the last cloud failure, "" = no failure
    private val freeCooldowns = Cooldowns()                      // per-provider skip times of the free AI chain (part 1B)
    @Volatile private var geminiCoolUntil = 0L                   // Gemini alone is skipped until this time; the free chain still runs
    @Volatile private var cloudActed = false                 // true once a cloud tool ran in this request (then no local answer is added)
    @Volatile private var dictatePending: Pending? = null   // the pending that waits for a DICTATED driving reply (not a yes/no)
    @Volatile private var dictTarget: Driving.Msg? = null    // the message that dictated reply answers (memory only)
    @Volatile private var answerWindow = false
    @Volatile private var followWindow = false   // v16 conversation mode: listen again after a spoken reply
    @Volatile private var nextFollow = false
    @Volatile private var replyUtt = ""
    @Volatile private var followCount = 0
    // v33 JARVIS screen session (ScreenSession.kt has the rules). Text only lives in memory, never on disk.
    @Volatile private var scrState: ScreenSession.State? = null
    @Volatile private var scrPkg = ""
    @Volatile private var scrLabel = ""
    @Volatile private var scrText = ""
    @Volatile private var scrLines: List<String> = emptyList()
    @Volatile private var turnIsFollow = false      // this turn was heard in the follow window (no wake word)
    private val scrHist: MutableList<JSONObject> = Collections.synchronizedList(ArrayList<JSONObject>())
    @Volatile private var wakeRequest = false        // set by the default-assistant button (long-press Home / power): same as the wake word
    @Volatile private var alive = false             // per-instance: true from onCreate until onDestroy / loop failure

    // Text only, bounded with Logic.boundHistory (PART 1B reads/writes it; synchronize when iterating).
    private val history: MutableList<JSONObject> = Collections.synchronizedList(ArrayList<JSONObject>())

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int = START_NOT_STICKY

    private fun tr(hi: String, en: String): String = if (cfg.lang == "en") en else hi
    private fun mode(m: String) {
        listener?.invoke("mode", m)
        h.post { hudMode(m) }
    }

    // ------------------------------------------------------------------ floating card (needs "Display over other apps")

    private var hud: NovaHud? = null
    private lateinit var brain: BrainStore
    @Volatile private var lastTurnLocal = false      // the last turn was handled by a built-in command or a shortcut

    private val hudHide = object : Runnable {
        override fun run() {
            val hd = hud ?: return
            if (!hd.isShown) return
            val waiting = busy || listening || ttsActive || pending != null || answerWindow || followWindow
            val shownMs = hd.visibleForMs()
            if (shownMs > HUD_MAX_MS || (!waiting && shownMs >= NovaHud.MIN_VISIBLE_MS)) hd.hide()
            else h.postDelayed(this, 400)
        }
    }

    // ---- SLEEP: NOVA is awake only between the wake word and the end of the task. Then it sleeps again.
    @Volatile private var curWake = "nova"
    private val sleepRun = Runnable { goSleep() }

    /** Go back to sleep [afterMs] from now (the card/reply gets time to be read first). A new wake cancels it. */
    private fun armSleep(afterMs: Long) {
        h.removeCallbacks(sleepRun)
        h.postDelayed(sleepRun, afterMs.coerceIn(800L, 12000L))
    }

    /** Only the wake-word listener stays on. Chat memory, last command and the card are cleared. */
    private fun goSleep() {
        if (!alive) return
        if (busy || listening || ttsActive || pending != null || answerWindow || followWindow) {
            h.postDelayed(sleepRun, 1500L)   // still working or waiting for a spoken yes: stay awake, look again soon
            return
        }
        synchronized(history) { history.clear() }
        historyAt = 0L
        lastKind = ""
        lastTurnLocal = false
        mode("sleep")
        sys(tr("Sleep mode - \"$curWake\" bolo to jaagunga", "Asleep - say \"$curWake\" to wake me"))
    }

    /** Called by the assistant session. The audio loop picks it up on its next chunk; ignored while NOVA is busy or speaking. */
    fun wakeNow() { if (alive && !busy && !ttsActive) wakeRequest = true }

    private var overlayNudged = false

    /** The card is a silent no-op without "Display over other apps": say so ONCE, with a tap-to-fix notification. */
    private fun overlayNudge() {
        if (overlayNudged) return
        overlayNudged = true
        val msg = tr(
            "Card dikhane ke liye 'Display over other apps' allow karo. Notification par tap karo.",
            "To see the card, allow 'Display over other apps'. Tap the notification."
        )
        sys(msg)
        try {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            val pi = PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(this, "nova")
                .setContentTitle(tr("NOVA card band hai", "NOVA card is off"))
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(7, n)
        } catch (e: Exception) { }
    }

    private fun hudMode(m: String) {
        val hd = hud ?: return
        when (m) {
            "listen" -> {
                h.removeCallbacks(hudHide)
                if (pending == null) { hd.setReply(""); hd.setHeard("") }   // keep the confirm question visible
                if (!NovaHud.canShow(this)) overlayNudge()
                hd.show("listen")
            }
            "think" -> { h.removeCallbacks(hudHide); hd.setState("think") }
            "on" -> if (hd.isShown) {
                hd.setState("reply")
                h.removeCallbacks(hudHide)
                h.postDelayed(hudHide, hd.holdMs())
            }
            "sleep" -> { h.removeCallbacks(hudHide); if (hd.isShown) hd.hide() }
            "off" -> { h.removeCallbacks(hudHide); hd.destroy() }
        }
    }
    private fun sys(t: String) { listener?.invoke("sys", t) }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        cfg = Cfg(this)
        LocalBrains.installEngine(this)
        brain = BrainStore.get(this)
        h.postDelayed(autoCheck, 45_000L)
        try { registerReceiver(batteryLowRx, IntentFilter(Intent.ACTION_BATTERY_LOW)) } catch (e: Exception) { }
        hud = NovaHud(this)
        SecureStore.migrateOld(this)
        SecureStore.migrateSingleToPool(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("nova", "NOVA", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "nova")
            .setContentTitle(tr("NOVA active hai", "NOVA is active"))
            .setContentText(tr("\"${cfg.wake}\" bolo, main sun raha hoon", "Say \"${cfg.wake}\", I am listening"))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            // Android refuses a microphone foreground service from the background / without permission.
            BootReceiver.showTapToStart(this)
            cfg.active = false
            stopSelf()
            return
        }
        instance = this
        initTts()
        alive = true
        running = true
        val prev = loopThread
        val t = Thread {
            // never run two mic loops at once: wait for the previous instance to release AudioRecord/Vosk
            try { prev?.join(3000) } catch (e: InterruptedException) { return@Thread }
            if (alive) audioLoop()
        }
        loopThread = t
        t.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) LocalBrains.release()   // free the offline model when RAM is short
    }

    override fun onDestroy() {
        alive = false
        AutoSetup.stop()
        LocalBrains.release()
        VoiceLock.release()
        Driving.enabled = false
        Driving.inbox.clear()
        DrivingBridge.forgetAll()
        if (instance === this) { instance = null; running = false }
        try { unregisterReceiver(batteryLowRx) } catch (e: Exception) { }
        h.removeCallbacksAndMessages(null)
        try { hud?.destroy() } catch (e: Exception) { }
        hud = null
        pending = null
        dictatePending = null
        dictTarget = null
        answerWindow = false
        history.clear()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        tts = null
        ttsReady = false
        ttsActive = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ TTS

    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            val t = tts
            if (st == TextToSpeech.SUCCESS && t != null) {
                cfg.applyVoice(t)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { utteranceEnded(id, true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { utteranceEnded(id, false) }
                    override fun onError(id: String?, errorCode: Int) { utteranceEnded(id, false) }
                    override fun onStop(id: String?, interrupted: Boolean) { utteranceEnded(id, false) }
                })
                ttsReady = true
            }
        }
    }

    /** Only the CURRENT utterance can release the mic; the answer window only opens after a confirm prompt finished. */
    private fun utteranceEnded(id: String?, finished: Boolean) {
        if (id == null || id != curUtt) return
        ttsActive = false
        if (finished && id == confirmUtt) answerWindow = true
        if (finished && id == replyUtt) {
            val ss = scrState
            if (ss != null) scrState = ScreenSession.refresh(ss, System.currentTimeMillis())   // the spoken reply does not eat the idle time
            if (screenOpen() || (ExtCfg.convo(this) && followCount < MAX_FOLLOW)) followWindow = true
        }
    }

    private fun say(text: String, confirm: Boolean = false): Boolean = speak(text, confirm, true)

    private fun speak(text: String, confirm: Boolean, show: Boolean): Boolean {
        if (text.isEmpty()) return false
        if (show) {
            listener?.invoke("ai", text)
            h.post { hud?.setReply(text) }
        }
        val t = tts
        if (t == null || !ttsReady) {
            if (confirm) answerWindow = true   // no voice: the user can still read the question and answer
            return false
        }
        cfg.applyVoice(t)
        val id = "u" + uttSeq.incrementAndGet()
        curUtt = id
        confirmUtt = if (confirm) id else ""
        replyUtt = if (nextFollow && !confirm) id else ""
        nextFollow = false
        ttsActive = true                                 // BEFORE speak(), so the loop can never hear our own voice
        ttsDeadline = System.currentTimeMillis() + minOf(60000L, 4000L + text.length * 90L)
        val r = try { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) } catch (e: Exception) { TextToSpeech.ERROR }
        if (r != TextToSpeech.SUCCESS) {
            ttsActive = false
            if (confirm) answerWindow = true
            return false
        }
        return true
    }

    fun stopSpeaking() { try { tts?.stop() } catch (e: Exception) { }; ttsActive = false }

    // ---- FIXTEST: hooks for Settings > SELF TEST. They only read state or run harmless local tools.
    fun testState(): String {
        val t = tts
        val hi = try {
            if (t == null) "tts=none" else when (t.isLanguageAvailable(java.util.Locale("hi", "IN"))) {
                TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "hindi_voice=ok"
                else -> "hindi_voice=missing"
            }
        } catch (e: Exception) { "hindi_voice=unknown" }
        return "alive=" + alive + " ttsReady=" + ttsReady + " " + hi + " busy=" + busy
    }
    fun testBattery(): String = batteryReply()
    fun testTorch(on: Boolean): Pair<Boolean, String> { val r = torch(on); return Pair(r.ok, r.msg) }
    fun testVolume(action: String): Pair<Boolean, String> { val r = volume(action, -1); return Pair(r.ok, r.msg) }

    /** Typed chat: runs exactly like a spoken command. A typed "yes" never approves a risky action (that needs the spoken yes). */
    fun typed(text: String): String {
        if (!alive) return "off"
        if (busy) return "busy"
        busy = true
        turnIsFollow = false   // v33: typed text never counts as a spoken follow-up
        Thread { process(ByteArray(0), text, 0) }.start()
        return "sent"
    }
    fun announce(text: String) { h.post { if (alive) say(text) } }
    fun reloadVoice() { cfg.applyVoice(tts) }
    fun speakTest(text: String) { cfg.applyVoice(tts); speak(text, false, false) }

    /** Used by the Accessibility alert monitor. False when NOVA is busy / listening / waiting for "yes". */
    fun announceIfIdle(text: String): Boolean {
        if (!alive || busy || listening || ttsActive || pending != null || !ttsReady) return false
        if (!ListenGuard.mayAnnounce(lastVoiceAt, System.currentTimeMillis())) return false   // PART 4: never talk over people
        if (Looper.myLooper() == Looper.getMainLooper()) return say(text)
        h.post { if (alive && !busy && !listening && !ttsActive && pending == null) say(text) }
        return true
    }

    /** v33 Part 2: the screen alert only ASKS. A spoken yes opens the screen session on that screen; no yes = silence. Main thread. */
    @Volatile private var alertKind: String? = null

    fun screenAlertAsk(sn: NovaAccessibilityService.Snap, hit: ScreenAlert.Hit): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (!alive || !ttsReady || Driving.enabled || screenOpen()) return false
        if (busy || listening || ttsActive || pending != null || answerWindow || followWindow || dictatePending != null) return false
        if (!ListenGuard.mayAnnounce(lastVoiceAt, System.currentTimeMillis())) return false   // PART 4: never ask over people who are talking
        val en = cfg.lang == "en"
        val q = ScreenAlert.question(hit, appLabel(sn.pkg), en)
        sys("alert: ${hit.key} | ${hit.line}")   // debug line in the chat only (not spoken)
        val first = sn
        pending = Pending(q, System.currentTimeMillis(), null) {
            val cur = NovaAccessibilityService.readScreen(false)
            val use = if (cur != null && cur.pkg.isNotEmpty() && cur.pkg != packageName) cur else first
            val reply = screenStartFrom(use)
            alertKind = lastKind
            reply
        }
        val ok = say(q, true)
        if (!ok) pending = null   // nothing was spoken: do not leave a hidden question waiting
        return ok
    }

    // ------------------------------------------------------------------ Vosk model

    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path)
        if (list == null || list.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (n in list) copyAssets("$path/$n", File(dest, n))
        }
    }

    private fun modelOk(dir: File): Boolean =
        File(dir, "am/final.mdl").isFile && File(dir, "graph/HCLr.fst").isFile && File(dir, "graph/Gr.fst").isFile

    private fun loadModel(): Model = loadModelDir("model-en")

    private fun loadModelDir(name: String): Model {
        val dir = File(filesDir, name)
        val done = File(dir, ".done")
        if (!done.exists() || !modelOk(dir)) {
            if (name == "model-en") sys(tr("Pehli baar model tayyar ho raha hai, thoda ruko...", "Preparing the model for the first time..."))
            dir.deleteRecursively()
            copyAssets(name, dir)
            if (!modelOk(dir)) {
                dir.deleteRecursively()
                throw IllegalStateException("model files missing inside the app: $name")
            }
            done.writeText("1")
        }
        try {
            return Model(dir.absolutePath)
        } catch (t: Throwable) {
            dir.deleteRecursively()   // corrupt copy: it is rebuilt from the APK on the next start
            throw t
        }
    }

    /** Indian-English model for the COMMAND recognizer only. Any problem -> null, and the caller keeps the main model. */
    private fun loadCommandModel(): Model? {
        return try {
            if (assets.list("model-en-in").isNullOrEmpty()) null else loadModelDir("model-en-in")
        } catch (t: Throwable) {
            null
        }
    }

    /** The chosen wake word must exist in the Vosk vocabulary, else we use the default. */
    private fun resolveWake(): String {
        val w = cfg.wake
        if (w == Logic.DEFAULT_WAKE) return w
        return try {
            val f = File(filesDir, "model-en/graph/words.txt")
            val found = f.useLines { lines -> lines.any { it.substringBefore(' ').substringBefore('\t') == w } }
            if (found) w else {
                listener?.invoke("ai", tr(
                    "\"$w\" shabd model mein nahi mila, abhi \"nova\" use kar raha hoon. Settings mein doosra shabd try karo.",
                    "\"$w\" is not in the model vocabulary, using \"nova\" for now. Try another word in Settings."
                ))
                Logic.DEFAULT_WAKE
            }
        } catch (e: Exception) { w }
    }

    private fun fatal(msg: String) {
        listener?.invoke("ai", msg)
        cfg.active = false
    }

    // ------------------------------------------------------------------ mic loop: wake -> command / answer

    // ---- v17 "only my voice" (SpeakerRules + VoiceLock). The gate runs on the worker thread, never on the mic thread.
    @Volatile private var voiceMd: Model? = null
    @Volatile private var enrollLeft = 0
    private val enrollVecs = ArrayList<DoubleArray>()
    @Volatile private var tuneLeft = 0              // PART 5: test sentences still to say (voice self-tuning)
    @Volatile private var tuneOthers = false        // PART 5: true = the test sentences are another person's
    private val tuneDists = ArrayList<Double>()

    /**
     * PART 5 (voice self-tuning). Called from Settings. The owner says about 10 test sentences; their distances to the saved voiceprint
     * pick a personal limit. With others = true another person says 3 sentences, to see whether the check can tell them apart.
     * "ok" = started, anything else = a short reason.
     */
    fun startVoiceTune(others: Boolean): String {
        if (!alive || voiceMd == null) return "notrunning"
        if (!VoiceLock.hasProfile(this) || !VoiceLock.modelReady(this)) return "noprofile"
        if (enrollLeft > 0) return "busy"
        if (others && VoiceTune.decode(ExtCfg.voiceOwnDists(this)).size < VoiceTune.MIN_SAMPLES) return "noowntest"
        synchronized(tuneDists) { tuneDists.clear() }
        tuneOthers = others
        tuneLeft = if (others) VoiceTune.OTHER_SAMPLES else VoiceTune.SAMPLES
        val n = tuneLeft
        listener?.invoke("ai", if (others) tr(
            "Doosre insaan ki awaaz ka test: wo \"$curWake\" bole aur 4-5 second ka lamba vaakya, $n baar (1/$n).",
            "Other person's voice test: they say \"$curWake\" and then a 4-5 second sentence, $n times (1/$n)."
        ) else tr(
            "Awaaz test: \"$curWake\" bolo aur 4-5 second ka lamba vaakya, $n baar (1/$n). Apni normal awaaz me, kabhi dheere kabhi tez.",
            "Voice test: say \"$curWake\" and then a 4-5 second sentence, $n times (1/$n). Normal voice, sometimes softer, sometimes louder."
        ))
        return "ok"
    }

    /** One test sentence. Always returns false: a test sentence is consumed and never runs as a command. */
    private fun tuneSample(pcm: ByteArray): Boolean {
        val profile = VoiceLock.loadProfile(this)
        val md = voiceMd
        val got = if (md != null && profile != null) VoiceLock.vector(this, md, pcm, RATE) else null
        if (got == null || profile == null) { voiceDeny(tr("Awaaz samajh nahi aayi, dobara bolo.", "I could not read your voice, say it again.")); return false }
        if (got.second < SpeakerRules.MIN_FRAMES_COMMAND) { voiceDeny(tr("Vaakya bahut chhota tha, thoda lamba bolo.", "That was too short, say a longer sentence.")); return false }
        val n = synchronized(tuneDists) { tuneDists.add(SpeakerRules.cosineDistance(got.first, profile)); tuneDists.size }
        tuneLeft -= 1
        if (tuneLeft > 0) {
            voiceDeny(tr("Theek ($n). Ab agla vaakya bolo.", "Got it ($n). Say the next sentence."))
            return false
        }
        val mine: List<Double> = synchronized(tuneDists) { ArrayList(tuneDists) }
        val others = tuneOthers
        val r = if (others) VoiceTune.choose(VoiceTune.decode(ExtCfg.voiceOwnDists(this)), mine) else VoiceTune.choose(mine, emptyList())
        if (!others) ExtCfg.setVoiceOwnDists(this, VoiceTune.encode(mine))
        val acc = Math.round(r.acceptRate * 100)
        val msg = when {
            r.note == "few" -> tr("Kam vaakya mile, test dobara karo.", "Too few sentences, run the test again.")
            r.note == "noisy" -> tr(
                "Aapki awaaz ke numbers bahut alag-alag aaye (sirf $acc% pass). Shaant jagah par, ek jaisi awaaz me dobara test karo. Purana limit nahi badla.",
                "Your voice numbers varied too much (only $acc% pass). Test again in a quiet place with a steady voice. The old limit was not changed."
            )
            r.note == "overlap" -> tr(
                "Doosre insaan ki awaaz aapki awaaz jitni paas aayi. Sirf awaaz par bharosa mat karo: Face lock ON rakho. Limit nahi badla.",
                "The other person's voice came as close as yours. Do not trust the voice check alone: keep the face lock ON. The limit was not changed."
            )
            else -> {
                ExtCfg.setVoiceThreshold(this, r.threshold)
                val rej = r.rejectRate
                if (rej != null) tr(
                    "Test pura. Aapki awaaz $acc% pass, doosre ki ${Math.round(rej * 100)}% roki gayi. Naya limit ${"%.2f".format(r.threshold)} lag gaya.",
                    "Test done. Your voice passes $acc%, the other person is refused ${Math.round(rej * 100)}%. New limit ${"%.2f".format(r.threshold)} is set."
                ) else tr(
                    "Test pura. Aapki awaaz $acc% pass. Naya limit ${"%.2f".format(r.threshold)} lag gaya. Doosre se bhi test karwao to bharosa badhega.",
                    "Test done. Your voice passes $acc%. New limit ${"%.2f".format(r.threshold)} is set. Test with another person too for more confidence."
                )
            }
        }
        voiceDeny(msg)
        return false
    }

    /**
     * PART 5: on a LOCKED phone (gate switched on) NOVA acts only when the voice AND the system face / fingerprint check pass.
     * Called on the worker thread right after the voice check allowed the turn. false = refused (shown in the chat only).
     */
    private fun faceAllows(): Boolean {
        if (!ExtCfg.faceGate(this)) return true
        val voiceActive = ExtCfg.voiceLock(this) && VoiceLock.hasProfile(this)
        val now = System.currentTimeMillis()
        when (LockGate.step(true, FaceGate.isLocked(this), voiceActive, LockGate.faceFresh(FaceGateState.lastOkAt, now))) {
            LockGate.Step.ALLOW -> return true
            LockGate.Step.DENY_NO_VOICEPRINT -> {
                voiceDeny(tr("Phone locked hai aur face lock ON hai: awaaz + face dono chahiye. Pehle Settings mein apni awaaz save karke 'sirf meri awaaz' ON karo.",
                    "The phone is locked and the face lock is ON: voice and face are both needed. Save your voice in Settings and switch 'only my voice' ON first."))
                return false
            }
            LockGate.Step.NEED_FACE -> { }
        }
        val mode = FaceGate.mode(this)
        if (mode == LockGate.Mode.NONE) {
            voiceDeny(tr("Is phone par face / fingerprint ya screen lock set nahi hai, isliye maine kuch nahi kiya.",
                "This phone has no face / fingerprint or screen lock set up, so I did nothing."))
            return false
        }
        val tok = FaceGateState.begin()
        FaceGate.launch(this, tok, mode)
        val ok = FaceGateState.await(tok, LockGate.FACE_WAIT_MS)
        FaceGate.dismissNotice(this)
        if (!ok) {
            voiceDeny(tr("Face / fingerprint check pass nahi hua (ya screen nahi khuli), isliye kuch nahi kiya.",
                "The face / fingerprint check did not pass (or its screen did not open), so I did nothing."))
            return false
        }
        return true
    }

    /** Called from Settings. "ok" = enrolment started (chat tells the owner what to say), anything else = a short reason. */
    fun startVoiceEnroll(): String {
        if (!alive || voiceMd == null) return "notrunning"
        Thread({
            if (!VoiceLock.modelReady(this)) {
                listener?.invoke("ai", tr("Awaaz pehchaan ka chhota model download ho raha hai (lagbhag 14 MB)...", "Downloading the small voice model (about 14 MB)..."))
                val err = VoiceLock.downloadModel(this)
                if (err == "busy") {
                    listener?.invoke("ai", tr("Download pehle se chal raha hai, thoda ruko. Dobara mat dabao.", "The download is already running, please wait. Do not tap again."))
                    return@Thread
                }
                if (err != null) {
                    listener?.invoke("ai", tr("Model download nahi hua ($err). Wi-Fi/internet check karke dobara dabao.", "The model did not download ($err). Check Wi-Fi/internet and try again."))
                    return@Thread
                }
            }
            synchronized(enrollVecs) { enrollVecs.clear() }
            enrollLeft = SpeakerRules.ENROLL_SAMPLES
            listener?.invoke("ai", tr(
                "Awaaz save: ab 5 baar \"$curWake\" bolo aur 4-5 second ka lamba vaakya bolo (1/5). Shaant jagah par, apni normal awaaz me.",
                "Voice setup: say \"$curWake\" and then a 4-5 second sentence, 5 times (1/5). Quiet place, normal voice."
            ))
        }, "nova-voice-enroll").start()
        return "ok"
    }

    private fun voiceDeny(msg: String) {
        h.post {
            listener?.invoke("ai", msg)
            hud?.setReply("")
            mode("on")
            armSleep(2000L)
            busy = false
        }
    }

    /** true = go on and handle this utterance. false = it was consumed (enrolment) or refused (not the owner). */
    private fun voiceAllows(pcm: ByteArray, isAnswer: Boolean): Boolean {
        if (tuneLeft > 0 && enrollLeft <= 0) return tuneSample(pcm)   // PART 5: a test sentence of the voice self-tuning
        val enrolling = enrollLeft > 0
        if (!enrolling && !(ExtCfg.voiceLock(this) && VoiceLock.hasProfile(this))) return true
        val md = voiceMd
        val got = if (md != null) VoiceLock.vector(this, md, pcm, RATE) else null
        if (enrolling) {
            if (got == null) { voiceDeny(tr("Awaaz samajh nahi aayi, dobara bolo.", "I could not read your voice, say it again.")); return false }
            if (got.second < SpeakerRules.MIN_FRAMES_ENROLL) { voiceDeny(tr("Vaakya bahut chhota tha, thoda lamba bolo.", "That was too short, say a longer sentence.")); return false }
            val n = synchronized(enrollVecs) { enrollVecs.add(got.first); enrollVecs.size }
            val done = n >= SpeakerRules.ENROLL_SAMPLES
            if (!done) { voiceDeny(tr("Save hua ($n/${SpeakerRules.ENROLL_SAMPLES}). Ab agla vaakya bolo.", "Saved ($n/${SpeakerRules.ENROLL_SAMPLES}). Say the next sentence.")); return false }
            enrollLeft = 0
            val avg = synchronized(enrollVecs) { SpeakerRules.average(ArrayList(enrollVecs)) }
            synchronized(enrollVecs) { enrollVecs.clear() }
            if (avg != null && VoiceLock.saveProfile(this, avg)) {
                ExtCfg.setVoiceThreshold(this, 0.0)      // PART 5: a new voiceprint makes the old tuning wrong
                ExtCfg.setVoiceOwnDists(this, "")
                ExtCfg.setVoiceLock(this, true)
                voiceDeny(tr("Awaaz save ho gayi. Ab NOVA sirf aapki awaaz par jawab dega.", "Voice saved. NOVA will now answer only your voice."))
            } else voiceDeny(tr("Awaaz save nahi ho payi. Dobara try karo.", "The voice could not be saved. Try again."))
            return false
        }
        val profile = VoiceLock.loadProfile(this)
        val thr = SpeakerRules.effectiveThreshold(ExtCfg.voiceLevel(this), ExtCfg.voiceThreshold(this))   // PART 5: the owner's own tuned limit wins
        if (got != null && profile != null) {   // shown in the chat so the owner can see (and tune) how close a voice was
            val d = SpeakerRules.cosineDistance(got.first, profile)
            sys(tr("Awaaz match: " + "%.2f".format(d) + " (seema " + "%.2f".format(thr) + ")", "Voice match: " + "%.2f".format(d) + " (limit " + "%.2f".format(thr) + ")"))
        }
        return when (SpeakerRules.decide(got?.first, got?.second ?: 0, profile, isAnswer, thr)) {
            SpeakerRules.Verdict.ALLOW -> true
            SpeakerRules.Verdict.TOO_SHORT -> { voiceDeny(tr("Awaaz bahut chhoti thi, pehchaan nahi paya. Dobara bolo.", "Too short to recognise your voice. Say it again.")); false }
            SpeakerRules.Verdict.REJECT -> {
                val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                voiceDeny(
                    if (got == null) tr("Awaaz jaanchne wala model nahi chal paya, isliye kuch nahi kiya. Settings > MERI AWAAZ dekho.", "The voice check is not available, so I did nothing. See Settings > MY VOICE.")
                    else tr("Anjaan awaaz ($t) - jawab nahi diya.", "Unknown voice ($t) - I did not answer.")
                )
                false
            }
        }
    }

    private fun audioLoop() {
        var model: Model? = null
        var wake: Recognizer? = null
        var cmd: Recognizer? = null
        var cmdModel: Model? = null
        var rec: AudioRecord? = null
        var tone: ToneGenerator? = null
        try {
            val md = loadModel()
            model = md
            voiceMd = md
            val wakeWord = resolveWake()
            curWake = wakeWord
            Logic.setWake(wakeWord)          // a custom wake word must never stay inside the command text
            val wk = Recognizer(md, RATE.toFloat(), Logic.wakeGrammar(wakeWord))
            wake = wk
            val cmModel = loadCommandModel()   // en-in for commands; wake word and vocabulary stay on model-en
            cmdModel = cmModel
            val cm = Recognizer(cmModel ?: md, RATE.toFloat())
            cmd = cm
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rc = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(if (minBuf > 0) minBuf else 0, 16384)
            )
            rec = rc
            if (rc.state != AudioRecord.STATE_INITIALIZED) {
                fatal(tr("Mic start nahi hua. Mic permission check karo.", "Mic did not start. Check the mic permission."))
                return
            }
            val tn = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone = tn
            rc.startRecording()
            if (rc.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fatal(tr("Mic busy hai (koi doosra app use kar raha hai).", "The mic is busy (another app is using it)."))
                return
            }
            AutoSetup.start(this)   // v34: voice-check model + provider health check, silently in the background (one runner)
            mode("sleep")
            sys(tr("Sleep mode - \"$wakeWord\" bolo to jaagunga", "Asleep - say \"$wakeWord\" to wake me"))

            val buf = ShortArray(1600) // 100 ms
            var capturing = false
            var answerCapture = false
            var dictCapture = false          // this capture is a dictated driving reply: longer, ends only on silence
            var followCap = false            // v33: this capture started from the follow window (no wake word)
            var pcm = ByteArrayOutputStream()
            val cmdText = StringBuilder()
            var heard = false
            var silentMs = 0
            var totalMs = 0
            var skip = 0
            var noise = 300.0
            var lastSpeechBytes = 0           // PART 4: pcm size right after the last loud chunk (trailing silence is cut before the cloud hears it)
            var wasBlocked = false
            var readErrors = 0
            var peak = 0.0                    // loudest recent chunk: a wake word must be spoken, not just a faint sound
            var wakeHits = 0                  // consecutive recognizer results that contained the wake word
            var lastCaptureEnd = 0L           // cooldown against a second false wake right after a turn
            val pre = ArrayList<ShortArray>() // last ~0.5 s of audio, so the first words after the wake word are not lost

            fun startCapture(answer: Boolean) {
                h.removeCallbacks(sleepRun)    // awake now: no sleeping in the middle of a task
                wk.reset()
                cm.reset()
                tn.startTone(ToneGenerator.TONE_PROP_BEEP, 60)   // PART 4: shorter beep, so less of the first word is lost
                capturing = true
                listening = true
                answerCapture = answer
                dictCapture = answer && pending != null && pending === dictatePending
                pcm = ByteArrayOutputStream()
                cmdText.setLength(0)
                heard = false
                silentMs = 0
                totalMs = 0
                skip = 1                       // do not record our own beep (PART 4: was 2 = 200 ms of the owner's first word lost)
                lastSpeechBytes = 0
                if (!answer) {                 // words spoken while the wake word was being recognised are kept
                    for (c in pre) {
                        try {
                            if (cm.acceptWaveForm(c, c.size)) {
                                val t = Logic.extractText(cm.getResult())
                                if (t.isNotBlank()) cmdText.append(' ').append(t)
                            }
                        } catch (e: Exception) { }
                        for (s in c) {
                            pcm.write(s.toInt() and 0xff)
                            pcm.write((s.toInt() shr 8) and 0xff)
                        }
                    }
                }
                pre.clear()
                mode("listen")
                sys(
                    if (dictCapture) tr("Jawab bolo...", "Say your reply...")
                    else if (answer) tr("Haan ya nahi bolo...", "Say yes or no...")
                    else tr("Bolo, main sun raha hoon...", "Speak, I am listening...")
                )
            }

            while (alive) {
                val n = rc.read(buf, 0, buf.size)
                if (n < 0) {
                    if (++readErrors >= MAX_READ_ERRORS) {
                        fatal(tr("Mic se awaaz nahi aa rahi (error $n). NOVA band ho gaya, dobara ACTIVATE karo.",
                            "The microphone stopped delivering audio (error $n). NOVA stopped; press ACTIVATE again."))
                        return
                    }
                    Thread.sleep(50)
                    continue
                }
                if (n == 0) { Thread.sleep(20); continue }
                readErrors = 0

                if (ttsActive && System.currentTimeMillis() > ttsDeadline) ttsActive = false // lost callback
                if (busy || ttsActive) {
                    capturing = false
                    listening = false
                    wasBlocked = true
                    continue
                }
                if (wasBlocked) {            // our own voice may still sit in the recognizers
                    wasBlocked = false
                    wk.reset()
                    cm.reset()
                    pre.clear()
                    wakeHits = 0
                    skip = 2
                }
                if (skip > 0) { skip--; continue }

                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = Math.sqrt(sum / n)
                val ms = n * 1000 / RATE

                if (!capturing) {
                    val p = pending
                    if (p != null && Logic.isExpired(p.createdAt, System.currentTimeMillis(), Logic.PENDING_TTL_MS)) {
                        pending = null
                        answerWindow = false
                    }
                    if (answerWindow) {
                        answerWindow = false
                        if (pending != null) { startCapture(true); continue }
                    }
                    if (followWindow) {
                        followWindow = false
                        if (pending == null && (followCount < MAX_FOLLOW || screenOpen())) { followCount++; followCap = true; startCapture(false); continue }
                    }
                    if (wakeRequest) {
                        wakeRequest = false
                        followCap = false
                        followCount = 0
                        pending = null
                        dictatePending = null
                        dictTarget = null
                        startCapture(false)
                        continue
                    }
                    if (ListenGuard.isVoice(rms, noise)) lastVoiceAt = System.currentTimeMillis()   // PART 4: people are talking nearby: no alerts over them
                    noise = ListenGuard.nextNoise(noise, rms)   // PART 4: the wake word itself must not raise the "speaking" threshold
                    peak = maxOf(rms, peak * 0.85)
                    pre.add(buf.copyOf(n))
                    while (pre.size > 5) pre.removeAt(0)
                    val done = wk.acceptWaveForm(buf, n)
                    val txt = if (done) wk.getResult() else wk.getPartialResult()
                    val wakeNow = Logic.heardWake(txt, wakeWord)
                    wakeHits = Logic.nextWakeHits(wakeHits, wakeNow)
                    if (wakeNow && (peak < maxOf(900.0, noise * 3.0) || System.currentTimeMillis() - lastCaptureEnd < Logic.WAKE_COOLDOWN_MS)) {
                        wk.reset()                 // too faint or too soon: a false wake (TV, room noise, echo of our voice)
                        wakeHits = 0
                    } else if (wakeNow && Logic.wakeConfirmed(wakeHits, done)) {
                        wakeHits = 0
                        followCount = 0
                        followCap = false
                        pending = null // a new command cancels any waiting confirmation
                        dictatePending = null
                        dictTarget = null
                        startCapture(false)
                    }
                } else {
                    hud?.setLevel((rms / 3000.0).toFloat().coerceIn(0f, 1f))   // waveform on the card follows your voice
                    totalMs += ms
                    val speaking = rms > maxOf(450.0, noise * 2.5)
                    if (speaking) { heard = true; silentMs = 0 } else if (heard) silentMs += ms
                    for (i in 0 until n) {
                        pcm.write(buf[i].toInt() and 0xff)
                        pcm.write((buf[i].toInt() shr 8) and 0xff)
                    }
                    if (speaking) lastSpeechBytes = pcm.size()
                    // Vosk's own end-of-utterance (acceptWaveForm == true) ends the capture early.
                    // After it fires the text must be taken with getResult(); getFinalResult() would be empty.
                    var endpoint = false
                    if (cm.acceptWaveForm(buf, n)) {
                        val t = Logic.extractText(cm.getResult())
                        if (t.isNotBlank()) { cmdText.append(' ').append(t); endpoint = false; heard = true; lastSpeechBytes = pcm.size() }
                    }
                    val maxMs = if (dictCapture) Logic.MAX_DICTATION_MS else if (answerCapture) Logic.MAX_ANSWER_MS else Logic.MAX_COMMAND_MS + ExtCfg.waitMs(this)
                    val noSpeechMs = if (dictCapture) Logic.NO_SPEECH_DICTATION_MS else if (answerCapture) Logic.NO_SPEECH_ANSWER_MS else Logic.NO_SPEECH_COMMAND_MS
                    val silenceMs = if (dictCapture) Logic.DICTATION_SILENCE_MS else if (answerCapture) 1500 else ExtCfg.waitMs(this)
                    // Smart end of speech: once the live text already is ONE complete simple command, a short pause is enough
                    // (instead of the full wait). Never for yes/no answers, dictated replies, typing or taps.
                    var fastDone = false
                    if (!answerCapture && !dictCapture && heard && silentMs >= Logic.FAST_END_MIN_SILENCE_MS) {
                        val live = (cmdText.toString() + " " + Logic.extractText(try { cm.getPartialResult() } catch (e: Exception) { "" })).trim()
                        val need = Logic.fastEndMs(live)
                        if (need > 0 && silentMs >= minOf(need, silenceMs)) fastDone = true
                    }
                    val end = endpoint || fastDone || (heard && silentMs >= silenceMs) ||
                        totalMs >= maxMs || (!heard && totalMs >= noSpeechMs)
                    if (end) {
                        capturing = false
                        listening = false
                        lastCaptureEnd = System.currentTimeMillis()
                        val isAnswer = answerCapture
                        val wasDict = dictCapture
                        turnIsFollow = followCap && !isAnswer && !wasDict
                        followCap = false
                        answerCapture = false
                        dictCapture = false
                        if (!endpoint) {
                            try {
                                val ft = Logic.extractText(cm.getFinalResult())
                                if (ft.isNotBlank()) cmdText.append(' ').append(ft)
                            } catch (e: Exception) { }
                        }
                        val text = cmdText.toString().trim()
                        val bytes = pcm.toByteArray()
                        val sendBytes = ListenGuard.trimTail(bytes, lastSpeechBytes, ListenGuard.TAIL_KEEP_MS, MIN_HEAR_BYTES)   // PART 4: no 5 s of silence to the cloud
                        val dur = totalMs
                        cm.reset()
                        wk.reset()
                        if (Logic.isFalseWake(heard, text, isAnswer || wasDict, SecureStore.hasGroq(this) || SecureStore.hasKeys(this))) {   // v32: Gemini can hear it too
                            h.post { hud?.setReply("") }
                            if (isAnswer || wasDict) mode("on") else mode("sleep")   // a false wake leaves quietly: no speech, no card
                            armSleep(if (isAnswer || wasDict) 2500L else 800L)
                            if (isAnswer) {
                                pending = null
                                dictatePending = null
                                dictTarget = null
                                say(tr("Jawab nahi mila, isliye cancel kar diya", "No answer heard, so I cancelled it"))
                            } else if (!wasDict) {
                                sys(tr("Kuch suna nahi, dobara \"$wakeWord\" bolo", "Heard nothing, say \"$wakeWord\" again"))
                            }
                            continue
                        }
                        busy = true
                        mode("think")
                        sys(tr("Soch raha hoon...", "Thinking..."))
                        Thread {
                            if (!voiceAllows(bytes, isAnswer)) return@Thread   // v17: only the owner's voice (when switched on)
                            if (!faceAllows()) return@Thread                   // PART 5: locked phone: the face / fingerprint screen as well
                            if (isAnswer) processAnswer(text) else process(sendBytes, text, dur)
                        }.start()
                    }
                }
            }
        } catch (t: Throwable) {   // includes UnsatisfiedLinkError from the native Vosk library
            fatal(tr(
                "Wake word model load nahi hua (${t.javaClass.simpleName}). App dobara kholo ya reinstall karo.",
                "The wake word model could not be loaded (${t.javaClass.simpleName}). Reopen or reinstall the app."
            ))
        } finally {
            listening = false
            voiceMd = null
            enrollLeft = 0
            tuneLeft = 0
            try { rec?.stop() } catch (t: Throwable) { }
            try { rec?.release() } catch (t: Throwable) { }
            try { tone?.release() } catch (t: Throwable) { }
            try { wake?.close() } catch (t: Throwable) { }
            try { cmd?.close() } catch (t: Throwable) { }
            try { cmdModel?.close() } catch (t: Throwable) { }
            try { model?.close() } catch (t: Throwable) { }
            if (alive) {   // the loop ended by itself (error): do not pretend to listen
                alive = false
                cfg.active = false
                if (instance === this) running = false
                mode("off")
                h.post { stopSelf() }
            }
        }
    }

    // ------------------------------------------------------------------ one turn

    private fun finishTurn(reply: String, kind: String? = null) {
        val silent = silentTurn
        val failed = turnFailed
        val oks: List<String> = synchronized(turnOk) { ArrayList(turnOk) }
        h.post {
            if (alive) {
                val p = pending
                val now = System.currentTimeMillis()
                val confirm = p != null && !Logic.isExpired(p.createdAt, now, Logic.PENDING_TTL_MS)
                var shown = reply
                val loud: Boolean
                if (silent) {
                    // PART 3: silent order. A waiting yes/no is still spoken; a failure shows its real text; nothing is overclaimed.
                    val o = SilentRules.present(confirm, failed, oks, reply, tr("Kaam complete ho gaya", "Task complete"))
                    shown = o.show
                    loud = o.speak
                    silentCarryAt = if (confirm) now else 0L
                    silentCarryP = if (confirm) p else null
                } else {
                    // kind == null (answers to a yes/no, errors) is always spoken; a normal command reply may be silent
                    loud = kind == null || Logic.speakReply(cfg.quietReplies, confirm, kind)
                    silentCarryAt = 0L
                    silentCarryP = null
                }
                if (loud) {
                    nextFollow = !confirm && kind != null && kind !in NO_FOLLOW_KINDS && !silent
                    say(shown, confirm)  // say() FIRST (sets ttsActive) ...
                } else {
                    listener?.invoke("ai", shown)                     // silent: chat + floating card only
                    hud?.setReply(shown)
                }
                mode("on")
                armSleep((hud?.holdMs() ?: 3000L) + 600L)   // reply stays readable, then NOVA sleeps (waits if a yes is pending)
            }
            busy = false                 // ... THEN release the mic loop
        }
    }

    /** PART 3: resets the per-order silent state. [carry] = this turn is the spoken yes/no answer to a question a silent order asked. */
    private fun beginTurn(carry: Boolean) {
        val cp = silentCarryP
        silentTurn = carry && cp != null && cp === pending && SilentRules.carryValid(silentCarryAt, System.currentTimeMillis(), Logic.PENDING_TTL_MS)
        if (!silentTurn) { silentCarryAt = 0L; silentCarryP = null }
        turnFailed = false
        silentFillerHeard = false
        synchronized(turnOk) { turnOk.clear() }
    }

    /** A silent phrase must not eat a typed dictation or a memory sentence ("yaad rakh ... chup rehna ..."). */
    private fun silentAllowed(stripped: String): Boolean {
        val k = Logic.classify(stripped)?.kind ?: return true
        return k != "type" && !PersonalMemory.isMemoryKind(k)
    }

    /** Heard text (Groq / Gemini transcript): cuts a silent phrase and turns the silent order on. "" + silentFillerHeard = only "ye kaam pura karo chup rehna". */
    private fun silentStrip(t: String): String {
        if (t.isBlank()) return t
        val sx = SilentRules.extract(t)
        if (!sx.silent || !silentAllowed(sx.text)) return t
        silentTurn = true
        if (sx.fillerOnly) { silentFillerHeard = true; return "" }
        return sx.text
    }

    /** "ye kaam pura karo, chup rehna": nothing new to do. If a job is running it finishes quietly; otherwise NOVA asks (spoken). */
    private fun silentFillerReply(): String {
        if (agentRunning) {
            agentSilent = true
            return tr("Theek hai, kaam chup-chaap poora karunga", "Okay, I will finish the job quietly")
        }
        silentTurn = false   // nothing to do silently, and the owner needs to HEAR this question
        return tr("Kaun sa kaam? Pehle kaam bolo, phir 'chup rehna' bolo", "Which job? Say the job first, then add 'stay quiet'")
    }

    private fun process(pcm: ByteArray, text0: String, durMs: Int) {
        beginTurn(false)
        var text = text0
        val sx = SilentRules.extract(text0)
        var fillerOnly = false
        if (sx.silent && silentAllowed(sx.text)) {   // PART 3: "... chup rehna" in the offline text
            silentTurn = true
            text = sx.text
            fillerOnly = sx.fillerOnly
        }
        val reply: String = try {
            if (fillerOnly) {
                listener?.invoke("me", "🎤 " + text0)
                silentFillerReply()
            } else handle(pcm, text)
        } catch (t: Throwable) {
            turnFailed = true
            tr("Kuch gadbad ho gayi, dobara try karo", "Something went wrong, please try again")
        }
        finishTurn(withNudge(reply), lastKind)
    }

    // ------------------------------------------------------------------ Layer 1 brain: shortcuts

    /** A one-step shortcut behaves exactly like the built-in command it points to (confirmations unchanged). */
    private fun skillCmd(sk: Skill): Logic.Cmd {
        if (sk.steps.size == 1) {
            val one = Logic.classify(sk.steps[0])
            if (one != null) return one
        }
        return Logic.Cmd("skill", sk.name)
    }

    private fun runSkill(name: String): String {
        val sk = brain.skill(name) ?: return tr("Ye shortcut nahi mila", "That shortcut was not found")
        val out = ArrayList<String>()
        for (step in sk.steps.take(Brain.MAX_STEPS)) {
            if (!alive) break
            val w = Brain.waitSeconds(step)
            if (w != null) {
                try { Thread.sleep(w * 1000L) } catch (e: InterruptedException) { break }
                continue
            }
            val sc = Logic.classify(step)
            if (sc == null) { out.add(tr("Ek step samajh nahi aaya, ruk gaya", "One step was not understood, so I stopped")); break }
            out.add(runCommand(sc))
            if (pending != null) break           // a step is asking for a yes: never run on past it
        }
        return if (out.isEmpty()) tr("Shortcut mein kuch nahi tha", "The shortcut was empty") else out.joinToString(". ")
    }

    // ------------------------------------------------------------------ Discover -> Ask -> Add (updates)

    private val autoCheck = object : Runnable {
        override fun run() {
            if (!alive) return
            Thread {
                try {
                    if (cfg.feedUrl.isNotBlank() &&
                        System.currentTimeMillis() - UpdateManager.lastCheck(this@NovaService) >= Updater.CHECK_GAP_MS
                    ) {
                        val known = UpdateManager.offers(this@NovaService).map { it.item.id }.toSet()
                        if (UpdateManager.refresh(this@NovaService, cfg.feedUrl) == null) {
                            val fresh = UpdateManager.offers(this@NovaService).filter { it.item.id !in known }
                            if (fresh.isNotEmpty()) {
                                UpdateManager.notifyUser(
                                    this@NovaService,
                                    tr("${fresh.size} naye update mile. Bolo 'check updates' ya app kholo.",
                                        "${fresh.size} new update(s) found. Say 'check updates' or open the app."),
                                    null
                                )
                            }
                        }
                    }
                } catch (e: Exception) { }
            }.start()
            h.postDelayed(this, 6L * 60 * 60 * 1000)          // look again in 6 h (it only refreshes about once a day)
        }
    }

    /** Voice: "check updates". Reads the feed, then ASKS before anything is downloaded. Worker thread. */
    private fun updateCheck(): String {
        if (cfg.feedUrl.isBlank()) return tr(
            "Pehle app ki settings mein update feed URL daalo", "Set the update feed URL in the app settings first"
        )
        val err = UpdateManager.refresh(this, cfg.feedUrl)
        if (err != null) return tr("Update check nahi ho paya: $err", "The update check failed: $err")
        val list = UpdateManager.offers(this)
        if (list.isEmpty()) return tr("Abhi koi naya update nahi hai", "Nothing new right now")
        val first = list[0].item
        val size = Updater.sizeText(first.size)
        val q = tr(
            "${list.size} update mile. Pehla: ${first.title}, $size. Install karu? Haan ya nahi bolo.",
            "${list.size} update(s) found. First: ${first.title}, $size. Install it? Say yes or no."
        )
        if (pending != null) return q
        pending = Pending(q, System.currentTimeMillis(), { UpdateManager.skip(this, first.id) }) {
            UpdateManager.install(this, first.id) { ok, msg ->
                UpdateManager.notifyUser(this, msg, null)
                h.post { if (alive) announceIfIdle(msg) }
            }
            tr("Download shuru kar diya, khatam hone par bataunga", "Download started, I will tell you when it is done")
        }
        return q
    }

    // ------------------------------------------------------------------ Driving mode (read messages aloud, reply only after "yes")

    private var drivingHintShown = false

    /** Called by NovaNotificationService (any thread) for a new message while driving mode is on. */
    fun onDrivingMessage(m: Driving.Msg) {
        if (!alive || !Driving.enabled) return
        val hint = !drivingHintShown && m.canReply && !m.hidden
        if (announceIfIdle(Driving.intro(m, cfg.lang != "en", hint))) {
            Driving.inbox.markRead(m)
            if (hint) drivingHintShown = true
        }
    }

    private fun driveOn(): String {
        if (!DrivingBridge.listenerEnabled(this)) {
            DrivingBridge.openListenerSettings(this)
            return tr(
                "Pehle settings mein NOVA ke liye Notification access chalu karo, maine settings khol di hain. Phir dobara bolo driving mode on",
                "Turn on Notification access for NOVA in the settings I just opened, then say driving mode on again"
            )
        }
        Driving.enabled = true
        drivingHintShown = false
        return tr(
            "Driving mode chalu. Naye messages main bol kar sunaunga. Jawab bhejne se pehle main aapse haan poochunga.",
            "Driving mode is on. I will read new messages aloud, and I always ask for your yes before sending a reply."
        )
    }

    private fun driveOff(): String {
        Driving.enabled = false
        Driving.inbox.clear()
        DrivingBridge.forgetAll()
        dictatePending = null
        dictTarget = null
        return tr("Driving mode band. Saare messages bhool gaya.", "Driving mode is off. I forgot all the messages.")
    }

    private fun driveClear(): String {
        Driving.inbox.clear()
        DrivingBridge.forgetAll()
        dictatePending = null
        dictTarget = null
        return tr("Saare messages saaf kar diye", "Cleared all messages")
    }

    private fun driveRead(): String {
        if (!Driving.enabled) return tr("Driving mode band hai. Pehle bolo driving mode on", "Driving mode is off. Say driving mode on first")
        val batch = Driving.inbox.unreadBatch()
        if (batch.isEmpty()) return tr("Koi naya message nahi hai", "No new messages")
        return batch.joinToString(" ") { Driving.intro(it, cfg.lang != "en", false) }
    }

    /** One of a few FIXED replies, sent only after the user hears the exact text and says a local "yes". */
    private fun driveReply(id: String): String {
        if (!Driving.enabled) return tr("Driving mode band hai. Pehle bolo driving mode on", "Driving mode is off. Say driving mode on first")
        val m = Driving.inbox.latestReplyable()
            ?: return tr("Jawab dene layak koi message nahi hai", "There is no message I can reply to")
        val text = Driving.quickText(id, cfg.lang != "en")
            ?: return tr("Ye jawab mujhe nahi aata", "I do not know that reply")
        val q = tr(
            "${m.sender} ko ${m.app} par bhej du: '$text'? Haan ya nahi bolo.",
            "Send '$text' to ${m.sender} on ${m.app}? Say yes or no."
        )
        return askFirst(q) {
            if (DrivingBridge.sendReply(this, m.key, text)) tr("Bhej diya", "Sent")
            else tr("Bhej nahi paya, wo notification ab nahi hai", "I could not send it, that notification is gone")
        }
    }

    /**
     * Step 1 of a dictated reply ("reply likho"): NOVA asks what to send and records ONE utterance (no wake word needed).
     * Nothing is sent here. The words only become a message after step 2 (read back + spoken local "yes").
     */
    private fun driveDictate(): String {
        if (!Driving.enabled) return tr("Driving mode band hai. Pehle bolo driving mode on", "Driving mode is off. Say driving mode on first")
        val m = Driving.inbox.latestReplyable()
            ?: return tr("Jawab dene layak koi message nahi hai", "There is no message I can reply to")
        if (pending != null) return tr("Ek confirmation pehle se baaki hai", "Another confirmation is already waiting")
        val q = tr(
            "${m.sender} ko kya jawab bhejna hai? Beep ke baad bolo.",
            "What shall I reply to ${m.sender}? Speak after the beep."
        )
        val p = Pending(q, System.currentTimeMillis(), null) { "" }   // run is never used: processAnswer routes it to dictatedReply
        dictTarget = m
        dictatePending = p
        pending = p
        return q
    }

    /** Step 2: the user's own words are read back; they are sent ONLY after a spoken, local "yes". Worker thread. */
    private fun dictatedReply(raw: String): String {
        val m = dictTarget
        dictTarget = null
        if (!Driving.enabled || m == null) return tr("Driving mode band hai, jawab nahi bheja", "Driving mode is off, so I did not send anything")
        val msg = Driving.cleanDictation(raw)
            ?: return tr("Kuch samajh nahi aaya. Dobara bolo reply likho", "I did not catch that. Say reply write to try again")
        val q = tr(
            "${m.sender} ko ${m.app} par bhej du: '$msg'? Haan ya nahi bolo.",
            "Send '$msg' to ${m.sender} on ${m.app}? Say yes or no."
        )
        return askFirst(q) {
            if (DrivingBridge.sendReply(this, m.key, msg)) tr("Bhej diya", "Sent")
            else tr("Bhej nahi paya, wo notification ab nahi hai", "I could not send it, that notification is gone")
        }
    }

    /** After a successful local command NOVA may offer to remember a phrase it keeps hearing (max one question / 6 h). */
    private fun withNudge(reply: String): String {
        if (!cfg.nudges || !lastTurnLocal || pending != null || reply.isBlank()) return reply
        val now = System.currentTimeMillis()
        val p = brain.nextProposal(now) ?: return reply
        val q = tr(
            "Ek baat: '${p.phrase}' ko '${p.command}' ka shortcut bana du? Haan ya nahi bolo.",
            "One thing: shall I remember '${p.phrase}' as a shortcut for '${p.command}'? Say yes or no."
        )
        pending = Pending(q, now, { brain.decline(p) }) {
            val err = brain.accept(p)
            if (err == null) tr("Yaad kar liya: '${p.phrase}' ab '${p.command}' chalayega", "Saved: '${p.phrase}' now runs '${p.command}'")
            else tr("Shortcut save nahi ho paya", "I could not save that shortcut")
        }
        return "$reply. $q"
    }

    private fun handle(pcm: ByteArray, rawText: String): String {
        // VoiceText is a conservative text-only layer. Keep original rawText for display and let the existing
        // classifier/brain/confirmation pipeline make every routing decision. Typed text is not alias-rewritten.
        val text = VoiceText.normalize(rawText, canonicalizeAsrAliases = pcm.isNotEmpty())
        // PART 2: a personal-memory command is handled FIRST and never reaches brain.record (that file keeps heard text on disk),
        // the cloud, the free chain or any model. The words of "yaad rakh ..." are not shown in the chat or on the card.
        val memCmd = if (text.isBlank()) null else Logic.classify(text)?.takeIf { PersonalMemory.isMemoryKind(it.kind) }
        val shown = if (memCmd?.kind == PersonalMemory.K_SAVE) tr("(yaad rakhne ka command)", "(remember command)")
            else if (rawText.isNotBlank()) rawText else tr("(awaaz)", "(voice)")
        listener?.invoke("me", "🎤 " + shown)
        h.post { hud?.setHeard("🎤 " + shown) }
        if (memCmd != null) {
            lastTurnLocal = true
            lastKind = memCmd.kind
            return runCommand(memCmd)
        }
        val ls = if (text.isBlank()) null else LocalSkills.answer(text, java.time.LocalDate.now(), cfg.lang)   // Layer 0: calc / convert / dates / emergency numbers, offline
        if (ls != null) {
            lastTurnLocal = true
            lastKind = "local_skill"
            brain.record(System.currentTimeMillis(), text, "local", "local_skill")
            return ls
        }
        // v28 TASK AGENT: while one runs, only "stop" is accepted; a whole spoken job starts one (after a spoken yes).
        if (agentRunning && text.isNotBlank()) {
            if (Logic.classify(text)?.kind == "stop") return runCommand(Logic.Cmd("stop"))
            return if (Logic.isJunk(text)) "" else tr("Abhi ek kaam chal raha hai. Rokne ke liye 'ruko' bolo", "A task is running. Say stop to cancel it")
        }
        if (!agentRunning && screenOpen() && (text.isNotBlank() || pcm.isNotEmpty())) {   // v33: a screen session is open: follow-ups first
            val sr = screenFollow(pcm, text)
            if (sr != null) return sr
        }
        if (text.isNotBlank() && AgentRules.isTask(text)) {
            lastTurnLocal = false
            lastKind = "agent"
            return agentBegin(text)
        }
        if (text.isNotBlank() && SiteIntent.isPublish(text)) {   // v16: "nova website publish karo" (spoken yes first)
            lastTurnLocal = false
            lastKind = "publish"
            return publishBegin()
        }
        val learnReq = if (text.isBlank()) null else LearnRules.parse(text)   // v16: "nova solar panel ke baare mein seekho"
        if (learnReq != null) {
            lastTurnLocal = false
            lastKind = "learn"
            return learnBegin(learnReq)
        }
        val siteReq = if (text.isBlank()) null else SiteIntent.parse(text)   // v16: "nova ek ... ki website banao"
        if (siteReq != null) {
            lastTurnLocal = false
            lastKind = "site"
            return siteBegin(siteReq)
        }
        val imgReq = if (text.isBlank()) null else ImageIntent.parse(text)   // v30: "nova ek ... ki photo banao" (free picture, no Gemini needed)
        if (imgReq != null) {
            lastTurnLocal = false
            lastKind = "image"
            return imageBegin(imgReq)
        }
        val c0 = if (text.isBlank()) null else Understand.classify(text)   // v32: long / casual sentences too ("mere mobile ko turant lock kar do")
        val sk = if (c0 == null && text.isNotBlank()) brain.resolve(text) else null   // Layer 1: shortcuts + fuzzy match
        var c: Logic.Cmd? = c0 ?: sk?.let { skillCmd(it) }
        var groqText = ""
        if (c == null && pcm.isNotEmpty() && SecureStore.hasGroq(this)) {
            groqText = silentStrip(groqHear(pcm))
            if (groqText.isNotBlank()) c = Understand.classify(groqText)
        }
        // v28: no Groq key, or Groq failed: the owner's Gemini key listens instead (Hindi / Hinglish / English, far better than the
        // small offline Vosk model, which only guesses Roman words). Only after the wake word, only if Vosk found no local command.
        if (c == null && groqText.isBlank() && pcm.size >= MIN_HEAR_BYTES) {
            groqText = silentStrip(geminiHear(pcm))
            if (groqText.isNotBlank()) c = Understand.classify(groqText)
        }
        if (silentFillerHeard) return silentFillerReply()   // PART 3: only "ye kaam pura karo, chup rehna" was heard
        if (c == null && groqText.isNotBlank() && AgentRules.isTask(groqText)) {   // v28: a whole job heard by Groq Whisper or Gemini
            lastTurnLocal = false
            lastKind = "agent"
            return agentBegin(groqText)
        }
        if (c == null && groqText.isNotBlank()) {   // v16: a "build a website" sentence heard by Groq Whisper or Gemini
            val gs = SiteIntent.parse(groqText)
            if (gs != null) {
                lastTurnLocal = false
                lastKind = "site"
                return siteBegin(gs)
            }
        }
        if (c == null && groqText.isNotBlank()) {   // v30: a "make a picture" sentence heard by Groq Whisper or Gemini
            val gi = ImageIntent.parse(groqText)
            if (gi != null) {
                lastTurnLocal = false
                lastKind = "image"
                return imageBegin(gi)
            }
        }
        val now = System.currentTimeMillis()
        if (c == null && c0 == null && sk == null && text.isNotBlank()) {   // PART 3: offline knowledge pack (text only, never an action)
            val said0 = if (groqText.isNotBlank()) groqText else text
            if (groqText.isNotBlank() || !Logic.isJunk(text)) {
                val kn = LearnStore.answer(this, said0) ?: KnowledgeStore.answer(this, said0, cfg.lang == "en")   // v16: kept notes first
                if (kn != null) {
                    lastTurnLocal = true
                    lastKind = "knowledge"
                    brain.record(now, text, "local", "knowledge")
                    return kn
                }
            }
        }
        lastTurnLocal = c != null
        lastKind = c?.kind ?: ""
        if (c0 != null) brain.record(now, text, "local", c0.kind)
        else if (sk != null) brain.record(now, text, "skill", sk.name)
        else brain.record(now, text, "unknown", "")
        if (c != null) return runCommand(c)                       // local, offline, no network
        val said = if (groqText.isNotBlank()) groqText else text
        val junk = groqText.isBlank() && Logic.isJunk(text)       // noise / cough / TV: never sent to any model, never acted on
        if (text.isNotBlank() && !junk) {                         // Layer 2: optional offline model (a translator only)
            val line = LocalBrains.ask(this, text)
            val mc = line?.let { Logic.classify(it) }
            if (line != null && mc != null && LocalBrainRules.accepted(mc)) {
                lastKind = mc.kind
                if (mc.kind == LocalBrainRules.TAP_KIND) {        // a model never taps by itself: spoken local "yes" first
                    return askFirst(
                        tr("Main '${mc.arg}' dabau? Haan ya nahi bolo.", "Tap '${mc.arg}'? Say yes or no.")
                    ) { runCommand(mc) }
                }
                return runCommand(mc)
            }
        }
        // Answer sources in order: cloud (only with the user's key, not cooling down) and the offline chat model (if installed).
        val steps = AiRouter.plan(SecureStore.hasKeys(this) || ExtCfg.freeChain(this), now < cloudCoolUntil, LocalBrains.available(this), said)
        if (steps.isNotEmpty()) {
            if (junk) return tr("Samajh nahi aaya, dobara bolo", "I did not catch that, please say it again")
            val ans = Routing.run(steps, said, { cloudStep(pcm, said) }, { localAnswer(said) },
                { ms -> cloudCoolUntil = System.currentTimeMillis() + ms })
            if (ans != null) return ans
        }
        if (!SecureStore.hasKeys(this)) return tr(
            "Ye abhi samajh nahi aaya. Chhota bolo, jaise 'phone lock karo'. Lambi baat ke liye Settings mein Gemini key daalo.",
            "I did not catch that. Say it short, like 'lock phone'. For long sentences add a Gemini key in Settings."
        )
        return tr(
            "Ye samajh nahi aaya. Ek baar aur, thoda saaf bolo.",
            "I did not catch that. Please say it once more, a bit clearer."
        )
    }

    /** The cloud as one answer source: runs askCloud and reports whether it failed and whether a tool already acted. */
    private fun cloudStep(pcm: ByteArray, said: String): Routing.CloudResult {
        lastCloudFail = ""
        cloudActed = false
        lastKind = "cloud"
        val en = cfg.lang == "en"
        // API-key-free public knowledge path for explicit, non-current factual lookups. It receives only the extracted topic,
        // runs only while FREE AI is enabled, and returns a cited text summary (never an action). This can avoid an LLM request.
        if (ExtCfg.freeChain(this)) {
            val publicAnswer = PublicKnowledgeFallback.answer(said, en)
            if (publicAnswer != null) {
                lastKind = "public_knowledge"
                lastCloudFail = ""
                return Routing.CloudResult(publicAnswer, true)
            }
        }
        var geminiFail = ""
        var geminiText = ""
        // 1) Gemini (the user's own key, can run phone tools). Skipped while it is cooling down after a failure.
        if (SecureStore.hasKeys(this) && System.currentTimeMillis() >= geminiCoolUntil) {
            val r = askCloud(pcm, said)
            val fail = lastCloudFail
            if (fail.isEmpty() || cloudActed) return Routing.CloudResult(r, fail.isEmpty(), cloudActed, if (fail.isEmpty()) "other" else fail)
            geminiFail = fail
            geminiText = r
            val cd = AiRouter.cooldownMs(fail)
            if (cd > 0L) geminiCoolUntil = System.currentTimeMillis() + cd
        }
        // 2) Free chain (Groq -> OpenRouter -> Pollinations). TEXT ONLY, never an action. Crisis / live-info questions are
        //    answered locally by LocalChat.preAnswer and never leave the phone. Only the spoken question is sent.
        var chainKind = ""
        if (FreeProviders.canUseChain(ExtCfg.freeChain(this), said)) {
            FreeProviders.chainPreAnswer(said, en)?.let {
                lastKind = "local_chat"
                lastCloudFail = ""
                return Routing.CloudResult(it, true)
            }
            val res = runFreeChain(said, en)
            val t = res.text
            if (t != null) {
                lastKind = "free_chain"
                lastCloudFail = ""
                return Routing.CloudResult(LocalChat.guard(said, t, en), true)
            }
            chainKind = res.failKind
        }
        val (text, kind) = FreeProviders.combinedFailure(geminiFail, geminiText, chainKind, en)
        lastCloudFail = kind
        ErrLog.add("ai-chain", "no AI answered: " + kind)
        return Routing.CloudResult(text, false, false, kind)
    }

    /** Up to 3 relevant personal facts for the ONLINE chain's system prompt. "" when the switch is off or nothing matches. */
    private fun chainFacts(said: String, en: Boolean): String {
        if (!ExtCfg.memoryInChain(this)) return ""
        return PersonalMemory.factsBlock(PersonalMemory.relevant(PersonalMemoryStore.load(this), said), en)
    }

    /** One run of the free chain with the user's own keys. Groq reuses the speech key; OpenRouter has its own slot. */
    private fun runFreeChain(said: String, en: Boolean): ChainResult {
        val keyless = ExtCfg.freeKeyless(this)
        return ProviderChain.run(
            FreeProviders.ordered(),
            { name ->
                when (name) {
                    "groq" -> SecureStore.getGroq(this)
                    "openrouter" -> SecureStore.getSlot(this, "openrouter")
                    "zai" -> SecureStore.getSlot(this, "zai")
                    else -> ""
                }
            },
            { id -> id != "pollinations" || keyless },
            FreeProviders.systemPrompt(en) + chainFacts(said, en),
            said,
            freeCooldowns,
            { System.currentTimeMillis() }
        ) { p, _, body, key -> FreeHttp.post(p, body, key) }
    }

    /** The offline chat model as one answer source: TEXT ONLY, never an action. null = nothing to say. */
    private fun localAnswer(said: String): String? {
        if (said.isBlank()) return null
        val en = cfg.lang == "en"
        LocalChat.preAnswer(said, en)?.let { lastKind = "local_chat"; return it }   // crisis / live-info questions get a fixed safe answer
        val a = LocalBrains.chat(this, said, en, PersonalMemory.relevant(PersonalMemoryStore.load(this), said)) ?: return null
        lastKind = "local_chat"
        return (if (en) LocalChat.offlinePrefixEn else LocalChat.offlinePrefixHi) + a
    }

    /** The user's answer to a pending confirmation. ONLY a clear local "yes" approves; the cloud is never asked. */
    private fun processAnswer(text: String) {
        beginTurn(true)   // PART 3: a yes to a silent order's question keeps the result silent
        val p = pending
        pending = null
        val isDict = p != null && p === dictatePending
        dictatePending = null
        // a dictated message is never shown in the chat log: it is read back and kept in memory only
        listener?.invoke("me", "🎤 " + if (isDict) tr("(dictate kiya hua jawab)", "(dictated reply)")
            else if (text.isNotBlank()) text else tr("(jawab)", "(answer)"))
        val reply: String = try {
            when {
                p == null -> tr("Koi confirmation baaki nahi tha", "Nothing was waiting for confirmation")
                Logic.isExpired(p.createdAt, System.currentTimeMillis(), Logic.PENDING_TTL_MS) -> {
                    dictTarget = null
                    tr("Confirmation ka time nikal gaya, cancel kar diya", "That confirmation expired, so I cancelled it")
                }
                isDict -> dictatedReply(text)
                else -> when (Logic.parseAnswer(text)) {
                    true -> p.run()
                    false -> { p.onNo?.invoke(); tr("Theek hai, cancel kar diya", "Okay, cancelled") }
                    null -> tr("Samajh nahi aaya, isliye cancel kar diya", "I did not understand, so I cancelled it")
                }
            }
        } catch (t: Throwable) {
            turnFailed = true
            tr("Kaam nahi ho paya", "That did not work")
        }
        val k = alertKind   // set only by a yes to a screen alert: the screen session then keeps listening for follow-ups
        alertKind = null
        finishTurn(reply, k)
    }

    /**
     * Worker thread. Gemini (the owner's own key) writes down what was said, like a voice keyboard: Hindi / Hinglish become Roman
     * letters, English stays English. "" = no key, cooling down, failed or only noise: the caller keeps the offline text.
     * Like Groq it can start a normal command, never approve a yes/no.
     */
    private fun geminiHear(pcm: ByteArray): String {
        if (!SecureStore.hasKeys(this) || System.currentTimeMillis() < geminiCoolUntil) return ""
        val parts = JSONArray()
        parts.put(JSONObject().put("inlineData", JSONObject()
            .put("mimeType", "audio/wav")
            .put("data", Base64.encodeToString(wavBytes(pcm), Base64.NO_WRAP))))
        parts.put(JSONObject().put("text",
            "Write down exactly what the speaker said, word for word. The speaker mixes Hindi, Hinglish and English and may speak fast. " +
            "Write Hindi words in Roman letters (for example: gallery kholo aur paanchvi photo chuno). Keep English words in English. " +
            "Do not translate, do not answer, do not add anything. Output only the transcript. If there is only noise or silence, output nothing."))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.0).put("maxOutputTokens", 1024))
            .toString()
        val (code, resp) = postFallback(body)
        if (code !in 200..299) return ""
        val raw = candidateText(resp).trim().trim('"').trim()
        if (raw.isBlank()) return ""
        val roman = HindiRoman.toRoman(raw).lowercase()
        return Logic.stripWake(VoiceText.normalize(roman))
    }

    /** Optional Groq Whisper listening (only when the user added a Groq key). Worker thread. The text can start a normal command, never approve one. */
    private fun groqHear(pcm: ByteArray): String {
        val key = SecureStore.getGroq(this)
        if (key.isBlank()) return ""
        val r = GroqStt.transcribe(key, pcm)
        val raw = r.text
        if (raw == null) return ""   // v31: silent fallback, the next listener (or the local one) simply takes over
        if (raw.isBlank()) return ""
        val roman = HindiRoman.toRoman(raw).lowercase()
        return Logic.stripWake(VoiceText.normalize(roman))
    }

    /** Registers a dangerous action (call, WhatsApp send). It only runs after a spoken, local "yes". */
    private fun askFirst(prompt: String, run: () -> String): String {
        if (pending != null) return tr("Ek confirmation pehle se baaki hai", "Another confirmation is already waiting")
        pending = Pending(prompt, System.currentTimeMillis(), null, run)
        return prompt
    }

    // ------------------------------------------------------------------ local commands handled in 1A

    private fun runCommand(c: Logic.Cmd): String = when (c.kind) {
        "stop" -> {
            agentCancel = true
            try { tts?.stop() } catch (e: Exception) { }
            ttsActive = false
            pending = null
            dictatePending = null
            dictTarget = null
            tr("Theek hai", "Okay")
        }
        "battery" -> batteryReply()
        "time" -> SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date()).let { tr("Abhi $it baj rahe hain", "It is $it") }
        "date" -> SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH).format(Date()).let { tr("Aaj $it hai", "Today is $it") }
        "monitor_on" -> monitorOn()
        "monitor_off" -> {
            cfg.screenMonitor = false
            NovaAccessibilityService.monitorChanged()
            tr("Screen monitoring band kar diya", "Screen monitoring is off")
        }
        "skill" -> runSkill(c.arg)
        "update_check" -> updateCheck()
        "drive_on" -> driveOn()
        "drive_off" -> driveOff()
        "drive_read" -> driveRead()
        "drive_clear" -> driveClear()
        "drive_reply" -> driveReply(c.arg)
        "drive_dictate" -> driveDictate()
        "quiet_on" -> {
            cfg.quietReplies = true
            tr("Theek hai, ab jawab chat mein likhunga, bolunga nahi. Alert aur confirmation bol kar bataunga.",
                "Okay, replies now go to the chat silently. I will speak only for alerts and confirmations.")
        }
        "quiet_off" -> {
            cfg.quietReplies = false
            tr("Theek hai, ab jawab bol kar bhi dunga", "Okay, I will speak my replies again")
        }
        "unlock" -> unlockPhone()
        PersonalMemory.K_SAVE -> memSave(c.arg)
        PersonalMemory.K_LIST -> memList()
        PersonalMemory.K_FORGET -> memForget(c.arg)
        PersonalMemory.K_FORGET_ALL -> memForgetAll()
        "analyze" -> analyzeScreen()      // PART 1B
        else -> runLocalTool(c)           // PART 1B: torch, volume, brightness, media, global, open_app, settings, camera
    }

    // ---------------------------------------------------------------- personal memory (PART 2)

    private fun memSave(fact: String): String {
        val en = cfg.lang == "en"
        val r = PersonalMemory.add(PersonalMemoryStore.load(this), fact)
        if (r.status == "saved" && !PersonalMemoryStore.save(this, r.list)) {
            return tr("Yaad rakh nahi paya, phone ne save nahi kiya", "I could not save that on the phone")
        }
        return PersonalMemory.saveReply(r, en)
    }

    private fun memList(): String = PersonalMemory.listReply(PersonalMemoryStore.load(this), cfg.lang == "en")

    private fun memForget(query: String): String {
        val en = cfg.lang == "en"
        val r = PersonalMemory.forget(PersonalMemoryStore.load(this), query)
        if (r.removed > 0 && !PersonalMemoryStore.save(this, r.list)) {
            return tr("Bhool nahi paya, phone ne save nahi kiya", "I could not forget it, the phone did not save the change")
        }
        return PersonalMemory.forgetReply(r, en)
    }

    /** "sab bhool ja" needs a spoken local yes (askFirst), like every risky action. */
    private fun memForgetAll(): String {
        val en = cfg.lang == "en"
        if (PersonalMemoryStore.count(this) == 0) return tr("Mujhe kuch yaad hi nahi hai", "I am not remembering anything")
        return askFirst(PersonalMemory.forgetAllPrompt(en)) {
            if (PersonalMemoryStore.clear(this)) tr("Sab bhool gaya", "I forgot everything")
            else tr("Bhool nahi paya, phone ne save nahi kiya", "I could not forget, the phone did not save the change")
        }
    }

    /** Voice "unlock phone": only when the user switched NOVA's pattern unlock ON. Worker thread (it waits for the result). */
    private fun unlockPhone(): String {
        if (!PatternUnlock.enabled(this)) return tr(
            "Unlock band hai. NOVA Pattern screen mein pattern save karke UNLOCK ON karo",
            "Unlock is off. Save a pattern and switch UNLOCK ON in the NOVA Pattern screen"
        )
        val svc = NovaAccessibilityService.instance ?: return tr(
            "Pehle Accessibility permission on karo", "Turn on the Accessibility permission first"
        )
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return tr("Phone pehle se unlock hai", "The phone is already unlocked")
        val latch = java.util.concurrent.CountDownLatch(1)
        val ok = AtomicBoolean(false)
        PatternUnlock.run(svc) { r -> ok.set(r); latch.countDown() }
        try { latch.await(12, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { }
        return if (ok.get()) tr("Phone khul gaya", "Phone unlocked")
        else tr("Phone nahi khula. Kuch phones lock screen par gesture rok dete hain", "The phone did not unlock. Some phones block gestures on the lock screen")
    }

    private fun batteryReply(): String {
        val pct = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct !in 0..100) return tr("Battery level abhi nahi mil raha", "I could not read the battery level")
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return if (charging) tr("Battery $pct percent hai aur charging par hai", "Battery is at $pct percent and charging")
        else tr("Battery $pct percent hai", "Battery is at $pct percent")
    }

    private fun monitorOn(): String {
        if (!NovaAccessibilityService.isEnabled(this)) return tr(
            "Screen monitor ke liye pehle Accessibility permission on karo",
            "Turn on the Accessibility permission first to use screen monitoring"
        )
        cfg.screenMonitor = true
        NovaAccessibilityService.monitorChanged()
        return tr(
            "Screen monitoring on hai. Ye sirf phone par local chalta hai, kuch upload nahi hota",
            "Screen monitoring is on. It runs only on the phone and uploads nothing"
        )
    }

    // ======================================================================================
    // PART 1B: phone tools (real success/failure), launch verification, cloud failover, screen analysis
    // ======================================================================================

    /** ok = Android accepted the request. msg = the exact sentence to speak (never claims more than was verified). */
    private class R(val ok: Boolean, val msg: String)
    private class CR(val name: String, val number: String, val err: R?)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- launching activities
    /**
     * Android 10+ blocks activity starts from the background SILENTLY (no exception). So after startActivity we verify:
     * NOVA's own screen visible -> allowed; otherwise the foreground package must change (Accessibility) within 1.5 s.
     * If it cannot be confirmed we post a tap-to-open notification and say "requested, not confirmed".
     * Must be called from a worker thread (it sleeps).
     */
    private fun launch(i: Intent, expectPkg: String?, what: String): R {
        val before = NovaAccessibilityService.foregroundPackage()
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            return R(false, tr("$what nahi khul paya: koi app ye kaam nahi kar sakta", "Could not open $what: no app can handle it"))
        }
        if (uiVisible) return R(true, tr("$what khul gaya", "$what opened"))
        if (NovaAccessibilityService.instance != null) {
            for (k in 0 until 10) {
                try { Thread.sleep(150) } catch (e: InterruptedException) { break }
                val pkg = NovaAccessibilityService.foregroundPackage()
                if (pkg != null && pkg != before && (expectPkg == null || pkg == expectPkg)) {
                    return R(true, tr("$what khul gaya", "$what opened"))
                }
            }
        }
        notifyOpen(i, what)
        return R(true, tr(
            "$what kholne ki request bheji hai par confirm nahi hua. Notification par tap karo",
            "I requested $what but it is not confirmed. Tap the notification to open it"
        ))
    }

    /** Opens the still or video camera, optionally on the front lens. Android has no official "front" switch, so the common extras are
     *  all sent; most camera apps follow them, a few ignore them. We never press record: that needs the user's own tap. */
    private fun cameraTool(modeRaw: String, facingRaw: String): R {
        val video = modeRaw.trim().lowercase().let { it == "video" || it == "vid" || it == "record" }
        val front = facingRaw.trim().lowercase().let { it == "front" || it == "selfie" }
        val i = Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (front) {
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
            i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        val what = (if (front) tr("Front ", "Front ") else "") + (if (video) tr("Video camera", "Video camera") else "Camera")
        val r = launch(i, null, what)
        return if (r.ok && video) R(true, r.msg + tr(". Record dabane ka button aap dabao", ". Tap the record button yourself")) else r
    }

    /** For intents that show no screen (alarm / timer with SKIP_UI): Android gives no confirmation at all. */
    private fun fire(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    private fun notifyOpen(i: Intent, what: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("nova_open", "NOVA open", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(
                this, 7, Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, "nova_open")
                .setContentTitle("NOVA")
                .setContentText(tr("Kholne ke liye tap karo: $what", "Tap to open: $what"))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- local tools (offline)
    private fun runLocalTool(c: Logic.Cmd): String {
        val r: R = try {
            when (c.kind) {
                "torch_on" -> torch(true)
                "torch_off" -> torch(false)
                "volume" -> volume(c.arg, c.num)
                "brightness_set" -> setBrightness(c.num)
                "brightness_step" -> stepBrightness(c.num)
                "media" -> media(c.arg)
                "global" -> globalAct(c.arg)
                "settings" -> openSettings(c.arg, c.num == 2)
                "camera" -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), null, "Camera")
                "open_app" -> openApp(c.arg)
                "timer" -> timerTool(c.num, c.arg)
                "alarm" -> alarmTool(c.num / 60, c.num % 60, c.arg)
                "alarm_any" -> Timers.resolveAny(c.num, java.time.LocalTime.now()).let { alarmTool(it / 60, it % 60, c.arg) }
                "tap" -> tapCmd(c.arg)
                "type" -> typeCmd(c.arg)
                "scroll" -> scrollCmd(c.arg)
                else -> R(false, tr("Ye command samajh nahi aaya", "I did not understand that command"))
            }
        } catch (e: Exception) {
            ErrLog.add("local:" + c.kind, "exception " + e.javaClass.simpleName)
            R(false, tr("Ye kaam nahi ho paya", "That did not work"))
        }
        if (!r.ok) { ErrLog.add("local:" + c.kind, r.msg); turnFailed = true } else turnOk.add(c.kind)
        return r.msg
    }

    // ---------------------------------------------------------------- screen control (Accessibility)

    private fun ctlFail(code: String, what: String): R = R(false, when {
        code == "no_acc" -> tr("Pehle Accessibility permission on karo", "Turn on the Accessibility permission first")
        code == "no_window" -> tr("Screen padh nahi paya, isliye kuch nahi kiya", "I could not read the screen, so I did nothing")
        code == "blocked" -> tr("Is screen par main control nahi karta (security/payment/settings). Ye aap khud karo",
            "I do not control this screen (security, payment or settings). Please do it yourself")
        code == "not_found" -> tr("Screen par '$what' nahi mila", "I could not find '$what' on the screen")
        code.startsWith("ambiguous") -> tr("'$what' ${code.substringAfter(':')} jagah mila, thoda aur specific bolo",
            "'$what' appears ${code.substringAfter(':')} times, please be more specific")
        code == "no_focus" -> tr("Koi text box select nahi hai", "No text box is selected")
        code == "password" -> tr("Password box mein main type nahi karta", "I never type into password boxes")
        else -> tr("Ye kaam nahi ho paya", "That did not work")
    })

    // ---------------------------------------------------------------- free image by voice (v30)
    // "nova ek billi ki photo banao": FreeImage (Together with own key, Pollinations without key, Hugging Face with own token) ->
    // saved to Gallery > Pictures > NOVA and shown in the chat. Only the subject text leaves the phone; the owner is told so.
    // Evidence level: written + statically checked. Not compiled here, not phone tested.
    @Volatile private var imageRunning = false

    private fun imageBegin(req: ImageIntent.Parsed): String {
        if (req.blocked) return tr("Ye wali photo main nahi bana sakta", "I cannot make that kind of picture")
        if (req.subject.isEmpty()) return tr(
            "Kis cheez ki photo banau? Aise bolo: nova ek billi ki photo banao",
            "A picture of what? Say: nova make a picture of a cat"
        )
        if (imageRunning) return tr("Ek photo abhi ban rahi hai, thoda ruko", "A picture is already being made, please wait")
        imageRunning = true
        val subject = req.subject
        Thread({
            var spoken = ""
            try {
                val r = FreeImage.run(this, subject)
                val img = r.img
                if (r.ok && img != null) {
                    val saved = ImageGen.saveToGallery(this, img)
                    val thumb = ImageGen.b64(ImageGen.shrink(img, 1024) ?: img)
                    imageListener?.invoke(true, r.msg, thumb, saved ?: "")
                    spoken = if (saved != null) tr("Photo ban gayi aur gallery mein save ho gayi", "The picture is ready and saved to your gallery")
                    else tr("Photo ban gayi, par gallery mein save nahi ho payi", "The picture is ready, but saving it to the gallery failed")
                } else {
                    val why = tr("Abhi photo nahi ban payi. Thodi der baad dobara bolo", "I could not make the picture right now. Please try again in a little while")
                    imageListener?.invoke(false, why, "", "")
                    spoken = why
                }
            } catch (e: Throwable) {
                spoken = tr("Photo banate waqt dikkat aayi", "Something went wrong while making the picture")
            }
            imageRunning = false
            val msg = spoken
            h.post { say(msg) }
        }, "nova-image").start()
        return tr(
            "Theek hai, $subject ki photo bana raha hu. Thoda time lagega",
            "Okay, making a picture of $subject. It will take a little while"
        )
    }

    // ---------------------------------------------------------------- learn from the internet (v16)
    // "nova solar panel ke baare mein seekho": web search with the owner's Gemini key -> short plain note -> saved as PENDING.
    // The owner presses RAKHO in Settings > KYA SEEKHA before it is ever used in an answer. If the web search fails the free
    // chain writes the note from the model's own knowledge (source "ai"), silently. A note is text only, never an action.
    // Evidence level: written + statically checked. Not compiled here, not phone tested.
    @Volatile private var learnRunning = false

    private fun learnBegin(req: LearnRules.Parsed): String {
        if (req.blocked) return tr("Aisi cheez main yaad nahi karunga", "I will not learn that kind of thing")
        if (req.topic.isEmpty()) return tr(
            "Kya seekhun? Aise bolo: nova solar panel ke baare mein seekho",
            "Learn what? Say: nova learn about solar panels"
        )
        if (learnRunning) return tr("Abhi ek cheez seekh raha hu, thoda ruko", "I am already learning something, please wait")
        learnRunning = true
        val topic = req.topic
        Thread({
            var spoken = ""
            try {
                var src = "web"
                var raw = searchWeb("Short factual note about: " + topic + ". Maximum 4 sentences, plain text, no links, no markdown.")
                var note = if (raw.startsWith("search failed") || raw == "no result" || raw == "no query") null else LearnRules.cleanNote(raw)
                if (note == null) {
                    val r = runFreeChain("Explain in at most 4 short plain sentences, no markdown: " + topic, cfg.lang == "en")
                    val t = r.text
                    if (t != null) { note = LearnRules.cleanNote(t); src = "ai" }
                }
                if (note != null) {
                    LearnStore.addPending(this, topic, note, src)
                    learnListener?.invoke()
                    spoken = tr("Maine $topic ke baare mein seekha. Settings mein KYA SEEKHA kholo aur RAKHO dabao, tabhi ye yaad rahega",
                        "I learned about $topic. Open Settings, KYA SEEKHA and press RAKHO to keep it")
                } else {
                    spoken = tr("Abhi ye seekh nahi paya. Thodi der baad dobara bolo", "I could not learn that right now. Please try again in a little while")
                }
            } catch (e: Throwable) {
                spoken = tr("Seekhte waqt dikkat aayi", "Something went wrong while learning")
            }
            learnRunning = false
            val msg = spoken
            h.post { say(msg) }
        }, "nova-learn").start()
        return tr("Theek hai, $topic ke baare mein seekh raha hu", "Okay, learning about $topic")
    }

    // ---------------------------------------------------------------- publish by voice (v16)
    // "nova website publish karo": only the newest built website, only after a spoken local "yes", public GitHub Pages repo.
    // Evidence level: written + statically checked. Not compiled here, not phone tested.
    @Volatile private var publishRunning = false

    private fun publishBegin(): String {
        val subject = lastSiteSubject
        val html = lastSiteHtml
        if (html.isEmpty()) return tr("Pehle website banao, phir publish karenge", "Build a website first, then we can publish it")
        if (!SecureStore.hasSlot(this, GitHubPublish.SLOT)) return tr(
            "GitHub token nahi hai. Settings mein GITHUB PUBLISH mein daalo",
            "There is no GitHub token. Add it in Settings, GITHUB PUBLISH"
        )
        if (publishRunning) return tr("Publish ho raha hai, thoda ruko", "Publishing is already running, please wait")
        return askFirst(
            tr("$subject ki website GitHub par sabke liye public karun? Haan ya nahi", "Publish the $subject website publicly on GitHub? Yes or no")
        ) { publishRun(subject, html) }
    }

    private fun publishRun(subject: String, html: String): String {
        publishRunning = true
        Thread({
            var spoken = ""
            try {
                val r = GitHubPublish.run(this, subject, html)
                publishListener?.invoke(r.ok, r.msg, r.url)
                spoken = if (r.ok) tr("Website publish ho gayi. Link chat mein hai, live hone mein ek-do minute lagenge", "The website is published. The link is in the chat; it can take a minute or two to go live")
                else r.msg
            } catch (e: Throwable) {
                spoken = tr("Publish karte waqt dikkat aayi", "Something went wrong while publishing")
            }
            publishRunning = false
            val msg = spoken
            h.post { say(msg) }
        }, "nova-publish").start()
        return tr("Theek hai, publish kar raha hu", "Okay, publishing")
    }

    // ---------------------------------------------------------------- proactive alerts (v16)
    // Low battery is spoken once (at most every 30 minutes) when NOVA is idle. Switch: Settings > BAAT-CHEET MODE box. Default ON.
    @Volatile private var lastAlertAt = 0L
    private val batteryLowRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            if (!alive || !ExtCfg.alerts(this@NovaService)) return
            val now = System.currentTimeMillis()
            if (now - lastAlertAt < 30L * 60L * 1000L) return
            lastAlertAt = now
            h.post {
                if (alive && !busy && !listening && !ttsActive && pending == null) {
                    say(tr("Sir, battery kam hai. Charger laga lijiye", "Sir, the battery is low. Please plug in the charger"))
                }
            }
        }
    }

    // ---------------------------------------------------------------- website by voice (v16)
    // "nova ek restaurant ki website banao": SiteGen (owner's own keys) -> one index.html saved to Downloads > NOVA and
    // shown in the chat with a preview button. Nothing is published. Only the topic text leaves the phone.
    // Evidence level: written + statically checked. Not compiled here, not phone tested.
    @Volatile private var siteRunning = false

    private fun siteBegin(req: SiteIntent.Parsed): String {
        if (req.blocked) return tr("Ye wali website main nahi bana sakta", "I cannot build that kind of website")
        if (req.subject.isEmpty()) return tr(
            "Kis cheez ki website banau? Aise bolo: nova ek restaurant ki website banao",
            "A website for what? Say: nova build a website for a restaurant"
        )
        if (siteRunning) return tr("Ek website abhi ban rahi hai, thoda ruko", "A website is already being built, please wait")
        siteRunning = true
        val subject = req.subject
        Thread({
            var spoken = ""
            try {
                val r = SiteGen.generate(this, subject)
                if (r.ok) {
                    lastSiteSubject = subject
                    lastSiteHtml = r.html
                    val saved = SiteGen.save(this, subject, r.html)
                    siteListener?.invoke(true, subject, r.html, saved ?: "")
                    spoken = if (saved != null) tr("Website ban gayi. Chat mein dekho, aur Downloads mein save ho gayi", "The website is ready. See it in the chat, and it is saved in Downloads")
                    else tr("Website ban gayi, par file save nahi ho payi. Chat mein dekh sakte ho", "The website is ready, but saving the file failed. You can still see it in the chat")
                } else {
                    siteListener?.invoke(false, r.msg, "", "")
                    spoken = r.msg
                }
            } catch (e: Throwable) {
                spoken = tr("Website banate waqt dikkat aayi", "Something went wrong while building the website")
            }
            siteRunning = false
            val msg = spoken
            h.post { say(msg) }
        }, "nova-site").start()
        return tr(
            "Theek hai, $subject ki website bana raha hu. Isme ek-do minute lag sakte hain",
            "Okay, building a website for $subject. It can take a minute or two"
        )
    }

    // ---------------------------------------------------------------- task agent (v28)
    // One spoken job -> loop: read screen -> Gemini picks ONE action (JSON) -> AgentRules checks it -> run it.
    // Evidence level: written + statically checked. Not compiled here, not phone tested.
    @Volatile private var agentRunning = false
    @Volatile private var agentCancel = false

    private fun agentBegin(raw: String): String {
        val task = AgentRules.taskText(raw)
        if (agentRunning) return tr("Ek kaam abhi chal raha hai. 'Ruko' bolo to rok dunga", "A task is already running. Say stop to cancel it")
        if (!NovaAccessibilityService.isEnabled(this) || NovaAccessibilityService.instance == null) return tr(
            "Is kaam ke liye Accessibility permission chahiye. Pehle use on karo",
            "This job needs the Accessibility permission. Turn it on first"
        )
        if (!SecureStore.hasKeys(this) && !agentChainReady()) return tr(
            "Abhi ye kaam shuru nahi ho pa raha. Settings me key check karo",
            "I cannot start this job right now. Check the key in Settings"
        )
        val q = tr(
            "Kaam: $task. Shuru karun? Haan ya nahi bolo.",
            "Job: $task. Start? Say yes or no."
        )
        if (AgentRules.isAiGen(raw) || AgentRules.isOpenDo(raw)) return agentStart(task)   // v17: typing a prompt into an AI app is not risky, no extra question
        return askFirst(q) { agentStart(task) }
    }

    private fun agentStart(task: String): String {
        if (agentRunning) return tr("Ek kaam abhi chal raha hai", "A task is already running")
        agentRunning = true
        agentCancel = false
        agentSilent = silentTurn      // PART 3: "... chup rehna" said with the job (or with its spoken yes)
        agentAsked = false
        Thread({
            val result = try {
                agentRun(task)
            } catch (e: Exception) {
                tr("Kaam beech mein ruk gaya", "The task stopped midway")
            }
            agentRunning = false
            h.post {
                if (agentSilent && !agentAsked) {   // PART 3: silent job: the result goes to the card only (a question to the owner is still spoken)
                    agentSilent = false
                    listener?.invoke("ai", result)
                    hud?.setReply(result)
                    if (alive && !busy && !listening) { mode("on"); armSleep((hud?.holdMs() ?: 3000L) + 600L) }
                } else {
                    agentSilent = false
                    say(result)
                }
            }
        }, "nova-agent").start()
        return tr("Theek hai, kaam shuru. Rokna ho to 'ruko' bolo", "Okay, starting. Say stop to cancel")
    }

    private fun agentSleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    private fun agentNote(s: String) {
        listener?.invoke("sys", s)
        h.post { hud?.setReply(s) }
    }

    private fun agentDescribe(a: AgentRules.Action, n: AgentRules.Node?): String = when (a.act) {
        "tap", "long_tap" -> a.act + " [" + a.index + "] '" + AgentRules.label(n).take(30) + "'"
        "type" -> "type '" + a.text.take(30) + "'"
        "open_app" -> "open_app " + a.app
        "scroll" -> "scroll " + a.dir
        "wait" -> "wait " + a.sec + "s"
        else -> a.act
    }

    /** A spoken yes/no from inside the agent loop. Only a local "haan" approves; stop, no, silence or 34 s = no. */
    private fun agentAskYes(q: String): Boolean {
        val ok = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        h.post {
            if (pending != null) {
                done.set(true)
            } else {
                pending = Pending(q, System.currentTimeMillis(), { done.set(true) }) {
                    ok.set(true)
                    done.set(true)
                    tr("Theek hai", "Okay")
                }
                say(q, true)
            }
        }
        val end = SystemClock.elapsedRealtime() + 34000L
        while (!done.get() && !agentCancel && SystemClock.elapsedRealtime() < end) agentSleep(300)
        if (!done.get()) h.post { pending = null }
        return ok.get() && !agentCancel
    }

    private fun agentDo(a: AgentRules.Action): String {
        return when (a.act) {
            "open_app" -> {
                val x = openApp(a.app)
                agentSleep(1800)
                if (x.ok) "opened" else "FAILED: " + x.msg
            }
            "tap", "long_tap" -> {
                val c = NovaAccessibilityService.agentTap(a.index, a.act == "long_tap")
                agentSleep(1000)
                if (c.ok) "ok" else "FAILED (" + c.code + ")"
            }
            "type" -> {
                val c = NovaAccessibilityService.agentType(a.index, a.text)
                agentSleep(500)
                if (c.ok) "ok" else "FAILED (" + c.code + ")"
            }
            "scroll" -> {
                val c = NovaAccessibilityService.scrollDir(a.dir)
                agentSleep(600)
                if (c.ok) "ok" else "FAILED (" + c.code + ")"
            }
            "back", "home" -> {
                val x = globalAct(a.act)
                agentSleep(700)
                if (x.ok) "ok" else "FAILED"
            }
            "wait" -> {
                agentSleep(a.sec * 1000L)
                "waited"
            }
            else -> "ignored"
        }
    }

    /** Worker thread. Returns the sentence to speak at the end. Hard caps: AgentRules.MAX_STEPS and MAX_MS. */
    /** The free online chain can drive the agent only when the owner left it ON and added a Groq or OpenRouter key. */
    private fun agentChainReady(): Boolean =
        ExtCfg.freeChain(this) && (SecureStore.hasGroq(this) || SecureStore.hasSlot(this, "openrouter") || SecureStore.hasSlot(this, "zai"))

    /** One text-only agent answer from the free chain (keys only, no keyless provider). null = nothing usable. */
    private fun agentChain(system: String, user: String): String? = try {
        ProviderChain.run(
            FreeProviders.ordered(),
            { name ->
                when (name) {
                    "groq" -> SecureStore.getGroq(this)
                    "openrouter" -> SecureStore.getSlot(this, "openrouter")
                    "zai" -> SecureStore.getSlot(this, "zai")
                    else -> ""
                }
            },
            { id -> id != "pollinations" },
            system,
            user,
            freeCooldowns,
            { System.currentTimeMillis() }
        ) { p, _, body, key -> FreeHttp.post(p, body, key) }.text
    } catch (t: Throwable) { null }

    private fun agentRun(task: String): String {
        val t0 = SystemClock.elapsedRealtime()
        val hist = ArrayList<String>()
        val acts = ArrayList<String>()
        val sigs = ArrayList<Int>()
        var bad = 0
        var blind = 0
        var denied = 0
        var step = 0
        while (step < AgentRules.MAX_STEPS) {
            if (agentCancel) return tr("Kaam rok diya", "Task stopped")
            if (SystemClock.elapsedRealtime() - t0 > AgentRules.MAX_MS) return tr("Time khatam ho gaya, kaam adhura hai", "Time is up, the task is unfinished")
            step++
            val snap = NovaAccessibilityService.agentSnapshot()
            if (snap == null) {
                blind++
                if (blind >= 4) return tr("Screen padh nahi pa raha, kaam ruk gaya", "I cannot read the screen, so I stopped")
                agentSleep(1200)
                continue
            }
            val pkg = snap.first
            val shielded = pkg.isNotEmpty() && Control.blocked(pkg, packageName, "tap")
            val nodes: List<AgentRules.Node> = if (shielded) emptyList() else snap.second   // protected screens are never sent to the cloud
            val prompt = AgentRules.userPrompt(task, step, pkg, nodes, hist)
            val shot = if (shielded) null else NovaAccessibilityService.agentScreenshot()   // protected screens: no picture either
            val parts = JSONArray()
            if (shot != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(shot, Base64.NO_WRAP))))
            parts.put(JSONObject().put("text", prompt))
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", AgentRules.systemPrompt()))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json").put("maxOutputTokens", 2048))
                .toString()
            // v17: Gemini first (it can also see the picture). If Gemini is out of quota or fails, the user's own free keys
            // (Groq / OpenRouter) answer from the same screen TEXT, no picture. Whoever answers, the safety check below still decides.
            val gem = if (SecureStore.hasKeys(this)) postFallback(body) else Pair(0, "")
            val code = gem.first
            var answer: String? = if (code in 200..299) candidateText(gem.second) else null
            if (answer == null && agentChainReady() && !agentCancel) answer = agentChain(AgentRules.systemPrompt(), prompt)
            if (agentCancel) return tr("Kaam rok diya", "Task stopped")
            if (answer == null) return tr(
                "Abhi jawab nahi mila (" + Logic.failureKind(code) + "). Kaam ruk gaya",
                "No answer right now (" + Logic.failureKind(code) + "). The task stopped"
            )
            val a = AgentRules.parse(answer)
            if (a == null) {
                bad++
                hist.add("step $step: (your last answer was not one valid JSON object, answer again with exactly one)")
                if (bad >= 3) return tr("Jawab samajh nahi aaya, kaam ruk gaya", "I could not understand the answer, so I stopped")
                continue
            }
            if (a.note.isNotEmpty()) agentNote(a.note)
            when (a.act) {
                "done" -> return if (a.say.isNotBlank()) a.say else tr("Kaam ho gaya", "Done")
                "fail" -> return tr("Kaam nahi ho paya. ", "I could not finish. ") + a.say
                "ask" -> {
                    agentAsked = true   // PART 3: the owner must HEAR this, even in a silent job
                    return if (a.say.isNotBlank()) a.say else tr("Mujhe aapki madad chahiye", "I need your help")
                }
            }
            val node = nodes.getOrNull(a.index)
            val chk = AgentRules.check(a, node, pkg, packageName)
            if (chk.v == AgentRules.Verdict.DENY) {
                denied++
                hist.add("step $step: " + agentDescribe(a, node) + " -> REFUSED by safety (" + chk.why + ")")
                if (denied >= 3) return tr(
                    "Ye step main nahi kar sakta (" + chk.why + "). Aap khud karo",
                    "I cannot do this step (" + chk.why + "). Please do it yourself"
                )
                continue
            }
            if (chk.v == AgentRules.Verdict.CONFIRM) {
                val yes = agentAskYes(tr("'" + chk.why + "' dabaun? Haan ya nahi bolo", "Tap '" + chk.why + "'? Say yes or no"))
                if (!yes) return tr("Theek hai, kaam yahin rok diya", "Okay, I stopped the task here")
            }
            val before = NovaAccessibilityService.screenSignature()
            val res = agentDo(a)
            val after = NovaAccessibilityService.screenSignature()
            val endHint = if (a.act == "scroll" && after == before) " (end reached: nothing more in this direction)" else ""
            val line = "step $step: " + agentDescribe(a, node) + " -> " + res + if (after != before) ", screen changed" else ", screen unchanged" + endHint
            hist.add(line)
            agentNote(line + (if (shot != null) " [pic]" else " [text only]") + " [" + pkg + "]")   // visible in the chat: the owner can send it for fixing
            if (a.act != "wait") {
                acts.add(a.act + a.index + a.text.take(10) + a.app)
                sigs.add(after)
                if (AgentRules.stuck(acts, sigs)) return tr(
                    "Screen par kuch badal nahi raha, main atak gaya. Aap dekh lo",
                    "The screen is not changing, I am stuck. Please take a look"
                )
            }
        }
        return tr("Bahut steps ho gaye, kaam adhura chhoda", "Too many steps, I stopped")
    }

    private fun tapCmd(label: String): R {
        if (Control.isRiskyLabel(label)) {
            val msg = askFirst(
                tr("'$label' dabaun? Haan ya nahi bolo", "Tap '$label'? Say yes or no")
            ) { doTap(label).msg }
            return R(true, msg)
        }
        return doTap(label)
    }

    private fun doTap(label: String): R {
        val before = NovaAccessibilityService.screenSignature()
        val r = NovaAccessibilityService.tapLabel(label)
        if (!r.ok) return ctlFail(r.code, label)
        try { Thread.sleep(700) } catch (e: InterruptedException) { }
        val after = NovaAccessibilityService.screenSignature()
        return if (after != before) R(true, tr("'$label' dabaya, screen badal gayi", "Tapped '$label', the screen changed"))
        else R(true, tr("'$label' dabaya, par screen mein badlav nahi dikha", "Tapped '$label', but I could not see the screen change"))
    }

    private fun typeCmd(text: String): R {
        val r = NovaAccessibilityService.typeText(text)
        return if (r.ok) R(true, tr("Likh diya", "Typed it")) else ctlFail(r.code, text)
    }

    private fun scrollCmd(dir: String): R {
        val before = NovaAccessibilityService.screenSignature()
        val r = NovaAccessibilityService.scrollDir(dir)
        if (!r.ok) return ctlFail(r.code, dir)
        try { Thread.sleep(500) } catch (e: InterruptedException) { }
        val after = NovaAccessibilityService.screenSignature()
        return if (after != before) R(true, tr("Scroll kar diya", "Scrolled $dir"))
        else R(true, tr("Scroll kiya, par screen badli nahi (shayad end aa gaya)", "Scrolled, but nothing changed (maybe the end)"))
    }

    private fun torch(on: Boolean): R {
        val cm = getSystemService(CameraManager::class.java)
        val id: String? = try {
            cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) { null }
        if (id == null) return R(false, tr("Is phone mein flash nahi mila", "This phone has no usable flash"))
        val state = AtomicInteger(-1)
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) state.set(if (enabled) 1 else 0)
            }
        }
        cm.registerTorchCallback(cb, h)
        try {
            Thread.sleep(120)                       // the callback first reports the current state
            cm.setTorchMode(id, on)
            val want = if (on) 1 else 0
            var waited = 0
            while (state.get() != want && waited < 800) { Thread.sleep(50); waited += 50 }
            return if (state.get() == want) R(true, tr(if (on) "Torch on hai" else "Torch off hai", if (on) "Torch is on" else "Torch is off"))
            else R(false, tr(
                "Torch badli ya nahi, confirm nahi hua. Kuch phones background mein torch rok dete hain",
                "I could not confirm the torch changed. Some phones block it from the background"
            ))
        } catch (e: Exception) {
            return R(false, tr("Torch control nahi ho paya", "Torch control failed"))
        } finally {
            try { cm.unregisterTorchCallback(cb) } catch (e: Exception) { }
        }
    }

    private fun volume(action: String, pct: Int): R {
        val am = getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        val before = am.getStreamVolume(s)
        val target = Math.round(max * pct.coerceIn(0, 100) / 100f).coerceIn(0, max)
        when (action) {
            "max" -> am.setStreamVolume(s, max, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "down" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "up" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "set" -> {
                if (pct !in 0..100) return R(false, tr("Volume 0 se 100 ke beech bolo", "Say a volume between 0 and 100"))
                am.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI)   // "set" is NOT the raise branch
            }
            else -> return R(false, tr("Volume ka ye command samajh nahi aaya", "I did not understand that volume command"))
        }
        try { Thread.sleep(150) } catch (e: InterruptedException) { }
        val after = am.getStreamVolume(s)
        val now = if (max > 0) after * 100 / max else 0
        val fail = R(false, tr("Android ne volume nahi badla (abhi $now percent)", "Android did not change the volume (it is $now percent)"))
        return when (action) {
            "max" -> if (after == max) R(true, tr("Volume full hai", "Volume is at maximum")) else fail
            "mute" -> if (after == 0 || am.isStreamMute(s)) R(true, tr("Volume mute hai", "Volume is muted")) else fail
            "set" -> if (after == target) R(true, tr("Volume $now percent hai", "Volume is $now percent")) else fail
            "up" -> if (after > before) R(true, tr("Volume $now percent hai", "Volume is $now percent"))
                else if (before == max) R(true, tr("Volume pehle se full hai", "Volume is already at maximum")) else fail
            else -> if (after < before) R(true, tr("Volume $now percent hai", "Volume is $now percent"))
                else if (before == 0) R(true, tr("Volume pehle se sabse kam hai", "Volume is already at the lowest")) else fail
        }
    }

    private fun setBrightness(pct: Int): R {
        if (pct !in 0..100) return R(false, tr("Brightness 0 se 100 ke beech bolo", "Say a brightness between 0 and 100"))
        if (!Settings.System.canWrite(this)) {
            return R(false, tr(
                "Brightness badalne ke liye permission chahiye. NOVA app mein ALLOW BRIGHTNESS dabao",
                "Brightness needs a permission. Tap ALLOW BRIGHTNESS in the NOVA app"
            ))
        }
        val v = (pct * 255 / 100).coerceIn(1, 255)
        val okMode = Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val okVal = Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        val back = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        return if (okMode && okVal && Math.abs(back - v) <= 2) R(true, tr("Brightness $pct percent hai", "Brightness is $pct percent"))
        else R(false, tr("Brightness badli nahi", "The brightness did not change"))
    }

    private fun stepBrightness(delta: Int): R {
        val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return setBrightness((cur * 100 / 255 + delta).coerceIn(0, 100))
    }

    private fun media(action: String): R {
        val am = getSystemService(AudioManager::class.java)
        val code = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return R(false, tr("Media ka ye command samajh nahi aaya", "I did not understand that media command"))
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        if (action == "play" || action == "pause") {
            try { Thread.sleep(400) } catch (e: InterruptedException) { }
            val want = action == "play"
            return if (am.isMusicActive == want) R(true, tr(if (want) "Music chal raha hai" else "Music ruk gaya", if (want) "Media is playing" else "Media is paused"))
            else R(true, tr("$action ki request bheji, par confirm nahi hua. Shayad koi player active nahi hai",
                "I sent $action but it is not confirmed. Maybe no player is active"))
        }
        return R(true, tr("$action track ki request bheji (confirm nahi kar sakta)", "Requested $action track (cannot be confirmed)"))
    }

    private fun globalAct(a: String): R {
        if (!NovaAccessibilityService.isEnabled(this) || NovaAccessibilityService.instance == null) return R(false, tr(
            "Iske liye Accessibility permission chahiye. App Info mein Allow restricted settings ke baad on karo",
            "This needs the Accessibility permission (App Info > Allow restricted settings first)"
        ))
        val code: Int
        val label: String
        when (a) {
            "home" -> { code = AccessibilityService.GLOBAL_ACTION_HOME; label = "Home" }
            "back" -> { code = AccessibilityService.GLOBAL_ACTION_BACK; label = "Back" }
            "recents" -> { code = AccessibilityService.GLOBAL_ACTION_RECENTS; label = "Recent apps" }
            "notifications" -> { code = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; label = "Notifications" }
            "quick_settings" -> { code = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; label = "Quick settings" }
            "lock" -> {
                if (Build.VERSION.SDK_INT < 28) return R(false, tr("Lock ke liye Android 9 ya naya chahiye", "Lock needs Android 9 or newer"))
                code = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN; label = "Lock"
            }
            else -> return R(false, tr("Ye action samajh nahi aaya", "I did not understand that action"))
        }
        return if (NovaAccessibilityService.performGlobalAction(code)) R(true, tr("$label ho gaya", "$label done"))
        else R(false, tr("Android ne $label action reject kar diya", "Android rejected the $label action"))
    }

    private fun openSettings(page: String, toggleAsked: Boolean): R {
        val p: Pair<String, String> = when (page) {
            "wifi" -> Pair(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")
            "bluetooth" -> Pair(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")
            "display" -> Pair(Settings.ACTION_DISPLAY_SETTINGS, "Display settings")
            "sound" -> Pair(Settings.ACTION_SOUND_SETTINGS, "Sound settings")
            "location" -> Pair(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location settings")
            "battery" -> Pair(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Battery settings")
            "airplane" -> Pair(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode settings")
            "apps" -> Pair(Settings.ACTION_APPLICATION_SETTINGS, "App settings")
            "dnd" -> Pair("android.settings.ZEN_MODE_PRIORITY_SETTINGS", "Do not disturb settings")
            else -> Pair(Settings.ACTION_SETTINGS, "Settings")
        }
        val r = launch(Intent(p.first), null, p.second)
        if (!toggleAsked) return r
        return R(r.ok, tr(
            "Android apps ko Wi-Fi ya Bluetooth seedha on off karne nahi deta. ",
            "Android does not let apps switch Wi-Fi or Bluetooth directly. "
        ) + r.msg)
    }

    private fun openApp(name: String): R {
        val q = name.trim()
        if (q.isEmpty()) return R(false, tr("Kaun sa app kholna hai?", "Which app should I open?"))
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val labels = list.map { it.loadLabel(pm).toString() }
        val idx = Logic.bestAppIndex(q, labels)
        if (idx < 0) return R(false, tr("$q naam ka app nahi mila", "I could not find an app called $q"))
        val pkg = list[idx].activityInfo.packageName
        val li = pm.getLaunchIntentForPackage(pkg)
            ?: return R(false, tr("${labels[idx]} ko kholne ka raasta nahi mila", "${labels[idx]} has no launch screen"))
        return launch(li, pkg, labels[idx])
    }

    // ---------------------------------------------------------------- contacts, call, sms, WhatsApp
    private val NUMBER_RE = Regex("^[+0-9 \\-]{5,}$")

    private fun resolveContact(who: String): CR {
        val q = who.trim()
        if (q.isEmpty()) return CR("", "", R(false, tr("Kisko? Naam bolo", "Who should I contact? Say a name")))
        if (NUMBER_RE.matches(q)) {
            val d = q.filter { it.isDigit() || it == '+' }
            return CR(d, d, null)
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            if (uiVisible && permRequest != null) {   // v31: ask right now, once, no trip to Settings
                permRequest?.invoke("contacts")
                return CR("", "", R(false, tr("Contacts ki permission de do, phir dobara bolo", "Allow contacts access, then say it again")))
            }
            return CR("", "", R(false, tr(
                "Contacts ki permission chahiye. NOVA app kholke ek baar Allow karo",
                "Contacts permission is needed. Open the NOVA app and allow it once"
            )))
        }
        return try {
            val names = ArrayList<String>()
            val nums = ArrayList<String>()
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$q%"), null
            )?.use { c ->
                while (c.moveToNext() && names.size < 50) {
                    names.add(c.getString(0) ?: "")
                    nums.add(c.getString(1) ?: "")
                }
            }
            val idx = Logic.pickContact(q, names)
            if (idx < 0 || nums[idx].isBlank()) CR("", "", R(false, tr("$q contact nahi mila", "I could not find a contact called $q")))
            else CR(names[idx], nums[idx], null)
        } catch (e: SecurityException) {
            CR("", "", R(false, tr("Contacts padh nahi paya", "I could not read the contacts")))
        }
    }

    private fun callTool(who: String): R {
        val c = resolveContact(who)
        c.err?.let { return it }
        val name = c.name
        val num = c.number
        val prompt = tr("$name ko call karun?", "Shall I call $name?")
        // Only the dialer is opened (ACTION_DIAL, no CALL_PHONE permission); the user presses call.
        val reply = askFirst(prompt) {
            launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", num, null)), null, "Dialer").msg +
                tr(". Call ka button aap dabaiye", ". Press the call button yourself")
        }
        return R(pending != null && reply == prompt, reply)
    }

    private fun smsTool(to: String, message: String): R {
        val c = resolveContact(to)
        c.err?.let { return it }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", message)
        val r = launch(i, null, "SMS")
        return R(r.ok, r.msg + tr(". Send aap dabaiye", ". Press send yourself"))
    }

    private fun whatsappTool(to: String, message: String): R {
        val c = resolveContact(to)
        c.err?.let { return it }
        if (message.isBlank()) return R(false, tr("Message khali hai, kya likhna hai?", "The message is empty, what should I write?"))
        val name = c.name
        val num = c.number
        val prompt = tr("$name ko WhatsApp par bheju: ${Logic.shorten(message)}?", "Send $name on WhatsApp: ${Logic.shorten(message)}?")
        val reply = askFirst(prompt) { sendWhatsApp(num, message) }
        return R(pending != null && reply == prompt, reply)
    }

    /** Runs only after the spoken yes. The Accessibility service presses send ONLY in WhatsApp and ONLY for this exact text. */
    private fun sendWhatsApp(number: String, text: String): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${Logic.waNumber(number)}?text=" + enc(text)))
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
        if (pkg != null) intent.setPackage(pkg)
        val canPress = NovaAccessibilityService.isEnabled(this) && NovaAccessibilityService.instance != null
        val launchFailed = AtomicBoolean(false)
        if (canPress) {
            NovaAccessibilityService.armSend(text) { ok ->
                h.post {
                    if (alive && !launchFailed.get()) say(
                        if (ok) tr("Message bhej diya", "Message sent")
                        else tr("Send button nahi daba paya, aap khud bhej dijiye", "I could not press send, please send it yourself")
                    )
                }
            }
        }
        val r = launch(intent, pkg, "WhatsApp chat")
        if (!r.ok) { launchFailed.set(true); return r.msg }
        return if (canPress) r.msg + tr(". Message bhejne ki koshish kar raha hoon, result bataunga", ". I am trying to press send and will tell you the result")
        else r.msg + tr(". Send button aap dabaiye", ". Please press send yourself")
    }

    // ---------------------------------------------------------------- alarm / timer / calendar / urls
    private fun alarmTool(hour: Int, minute: Int, label: String): R {
        if (hour !in 0..23 || minute !in 0..59) return R(false, tr("Alarm ka time galat hai", "That alarm time is not valid"))
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val t = Timers.clock(hour * 60 + minute)
        return if (fire(i)) R(true, tr("$t ke alarm ki request bheji. Android confirm nahi karta, clock app mein dekh lo",
            "Requested an alarm for $t. Android gives no confirmation, please check the clock app"))
        else R(false, tr("Alarm set karne wala app nahi mila", "No app could set the alarm"))
    }

    private fun timerTool(seconds: Int, label: String): R {
        if (seconds !in 1..86400) return R(false, tr("Timer 1 second se 24 ghante ke beech hona chahiye", "A timer must be between 1 second and 24 hours"))
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val d = Timers.human(seconds, cfg.lang == "en")
        return if (fire(i)) R(true, tr("$d ke timer ki request bheji. Android confirm nahi karta, clock app mein dekh lo",
            "Requested a $d timer. Android gives no confirmation, please check the clock app"))
        else R(false, tr("Timer set karne wala app nahi mila", "No app could set the timer"))
    }

    private fun calendarTool(title: String, start: String): R {
        val ms: Long? = try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ENGLISH).parse(start)?.time } catch (e: Exception) { null }
        if (ms == null || title.isBlank()) return R(false, tr("Event ka naam ya time samajh nahi aaya", "I could not understand the event title or time"))
        val r = launch(
            Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + 3600000L),
            null, "Calendar"
        )
        return R(r.ok, r.msg + tr(". Save aap dabaiye", ". Press save yourself"))
    }

    private fun openUrl(raw: String): R {
        var u = raw.trim()
        if (u.isEmpty()) return R(false, tr("Kaun si website?", "Which website?"))
        if (!u.contains("://")) u = "https://$u"
        val uri = try { Uri.parse(u) } catch (e: Exception) { null }
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            return R(false, tr("Sirf http ya https link khol sakta hoon", "I can only open http or https links"))
        }
        return launch(Intent(Intent.ACTION_VIEW, uri), null, uri.host ?: "the page")
    }

    // ---------------------------------------------------------------- tool dispatcher for the cloud model
    private fun runTool(name: String, a: JSONObject): String {
        val r: R = try {
            when (name) {
                "open_app" -> openApp(a.optString("name"))
                "open_url" -> openUrl(a.optString("url"))
                "call" -> callTool(a.optString("who"))
                "sms" -> smsTool(a.optString("to"), a.optString("message"))
                "whatsapp" -> whatsappTool(a.optString("to"), a.optString("message"))
                "alarm" -> alarmTool(a.optInt("hour", -1), a.optInt("minute", -1), a.optString("label"))
                "timer" -> timerTool(a.optInt("seconds", -1), a.optString("label"))
                "torch" -> torch(a.optBoolean("on", false))
                "volume" -> volume(a.optString("action"), a.optInt("percent", -1))
                "navigate" -> {
                    val place = a.optString("place").trim()
                    if (place.isEmpty()) R(false, "no place given")
                    else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + enc(place))), null, "Maps")
                }
                "play_music" -> {
                    val q = a.optString("query").trim()
                    if (q.isEmpty()) R(false, "no song given")
                    else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q))), null, "YouTube")
                }
                "camera" -> cameraTool(a.optString("mode", "photo"), a.optString("facing", "back"))
                "settings" -> openSettings(a.optString("page", "main"), false)
                "brightness" -> setBrightness(a.optInt("percent", -1))
                "brightness_step" -> stepBrightness(a.optInt("delta", 0))
                "media" -> media(a.optString("action"))
                "global_action" -> globalAct(a.optString("action"))
                "calendar_event" -> calendarTool(a.optString("title"), a.optString("start"))
                "search_web" -> R(true, searchWeb(a.optString("query")))
                else -> R(false, "unknown tool")
            }
        } catch (e: Exception) {
            ErrLog.add("ai-tool:" + name, "exception " + e.javaClass.simpleName)
            R(false, "the tool failed")
        }
        if (!r.ok) { ErrLog.add("ai-tool:" + name, r.msg); turnFailed = true } else turnOk.add(name)
        return if (r.ok) r.msg else "FAILED: " + r.msg
    }

    // ---------------------------------------------------------------- cloud (only with a user supplied key)
    private fun postOnce(model: String, key: String, json: String, readMs: Int): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = readMs
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) ErrLog.add("gemini", "http " + code + " (" + model + ")")
            return Pair(code, text)
        } catch (e: UnknownHostException) {
            ErrLog.add("gemini", "no internet")
            return Pair(598, "")
        } catch (e: ConnectException) {
            ErrLog.add("gemini", "no internet")
            return Pair(598, "")
        } catch (e: SocketTimeoutException) {
            ErrLog.add("gemini", "timeout (" + model + ")")
            return Pair(599, "")
        } catch (e: IOException) {
            ErrLog.add("gemini", "network error (" + model + ")")
            return Pair(599, "")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Failover, not a quota bypass: only keys the user typed in, models x keys in a fixed order, bounded by
     * Logic.MAX_CALLS and a 45 s deadline. 598 = offline (stop at once), 599 = timeout.
     */
    private fun postFallback(json: String): Pair<Int, String> {
        val keys = SecureStore.getKeys(this)
            .ifEmpty { listOf(SecureStore.getKey(this)).filter { it.isNotBlank() } }
            .distinct()
        if (keys.isEmpty()) return Pair(0, "")
        val models = (listOf(cfg.model) + Logic.MODELS).distinct()
        val deadline = SystemClock.elapsedRealtime() + Logic.CLOUD_DEADLINE_MS
        var calls = 0
        var last = Pair(599, "")
        outer@ for (model in models) {
            for (key in keys) {
                val remain = deadline - SystemClock.elapsedRealtime()
                if (calls >= Logic.MAX_CALLS || remain < 3000L) return last
                calls++
                val r = postOnce(model, key, json, minOf(25000L, remain).toInt())
                last = r
                when (Logic.nextStep(r.first, r.second)) {
                    Logic.Next.SUCCESS -> return r
                    Logic.Next.STOP -> return r
                    Logic.Next.NEXT_KEY -> { }
                    Logic.Next.NEXT_MODEL -> continue@outer
                }
            }
        }
        return last
    }

    private fun msg(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun remember(user: String, reply: String) {
        historyAt = System.currentTimeMillis()
        synchronized(history) {
            history.add(msg("user", if (user.isBlank()) "(voice command)" else user))
            history.add(msg("model", reply))
            Logic.boundHistory(history)
        }
    }

    private fun candidateContent(resp: String): JSONObject? = try {
        JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
    } catch (e: Exception) { null }

    private fun candidateText(resp: String): String {
        val parts = candidateContent(resp)?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.has("text") && !p.optBoolean("thought", false)) sb.append(p.optString("text")).append(' ')
        }
        return sb.toString().trim()
    }

    private fun cloudFailure(code: Int): String = when (Logic.failureKind(code).also { lastCloudFail = it }) {
        "nokey" -> tr("AI key nahi hai. Local commands chalte rahenge", "No AI key is set. Local commands still work")
        "network" -> tr("Internet nahi hai ya bahut slow hai. Local commands phir bhi chalte hain", "No internet or it is too slow. Local commands still work")
        "key" -> tr("API key galat ya band lagti hai. Settings mein check karo", "The API key looks wrong or disabled. Check it in Settings")
        "quota" -> tr("AI ki limit poori ho gayi, thodi der baad try karo", "The AI limit is reached, try again in a while")
        "model" -> tr("AI model abhi available nahi hai", "The AI model is not available right now")
        "server" -> tr("AI server abhi busy hai, thodi der baad try karo", "The AI server is busy, try again later")
        else -> tr("AI se jawab nahi mila (error $code)", "No answer from the AI (error $code)")
    }

    private fun wavBytes(pcm: ByteArray): ByteArray {
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

    private fun tool(name: String, desc: String, vararg params: Pair<String, String>): JSONObject {
        val d = JSONObject().put("name", name).put("description", desc)
        if (params.isNotEmpty()) {
            val props = JSONObject()
            val req = JSONArray()
            params.forEach { (n, t) -> props.put(n, JSONObject().put("type", t.uppercase())); req.put(n) }
            d.put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", req))
        }
        return d
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
        val bat = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return """You are NOVA, a voice assistant on the user's Android phone. The user's message is a spoken command in Hindi, Hinglish or English: the audio plus a transcript that may have small mistakes (if they differ, trust the audio). People speak naturally: long, casual, with filler and polite words, a different word order, or several things in one sentence, for example 'mere mobile ko turant lock kar do', 'yaar thodi awaaz kam kar de aur torch jala de', 'mujhe subah saat baje uthana hai alarm laga dena'. Work out the INTENT of the whole sentence and act with the tools; when the user asks for several things, call several tools in the same turn. Never say you did not understand just because the phrasing is unusual or long: choose the most likely action. Ask ONE short question only when two very different actions are equally likely, and ask the user to repeat only when the audio is really empty. Your replies are read aloud, so: no markdown, no lists, no emojis, no URLs, short natural sentences. Reply in the language the user spoke: Hindi in Devanagari script for Hindi or Hinglish, English for English. For the camera tool pass mode video when the user says video or record, and facing front when the user says front, selfie or aage wala camera. Use the provided tools for phone actions and never claim an action was done unless the tool result says so; if a tool result says requested or not confirmed, say exactly that. The call and whatsapp tools ask the user for a spoken confirmation by themselves, so call them directly without asking first. For call, sms and whatsapp pass the contact name in Latin letters as it is usually saved, for example Rahul. For whatsapp pass only the final message text. You cannot see the phone screen. For analysis or explanations give a clear answer: conclusion first, then two to four reasons, then key risks. Use search_web for anything current such as news, prices or scores. Keep answers under about 8 sentences unless asked for detail. Never call or message anyone the user did not ask for. Never repeat, redo or continue an earlier command unless the user clearly asks for it again in this new message. If the audio is silent or only background noise, call no tool, change nothing, and only ask the user to repeat. Current time: $now. Phone battery: $bat percent."""
    }

    private fun declarations(): JSONArray {
        val t = JSONArray()
        t.put(tool("open_app", "Open an installed app by its English name, e.g. WhatsApp, Instagram, Calculator", "name" to "string"))
        t.put(tool("open_url", "Open a web page (http or https) in the browser", "url" to "string"))
        t.put(tool("call", "Phone call a saved contact name or number. The user is asked to confirm by voice first; only the dialer opens", "who" to "string"))
        t.put(tool("sms", "Open the SMS app with a prefilled message to a contact name or number", "to" to "string", "message" to "string"))
        t.put(tool("whatsapp", "Send a WhatsApp message to a contact name or number. The user is asked to confirm by voice first", "to" to "string", "message" to "string"))
        t.put(tool("alarm", "Set an alarm (24-hour clock)", "hour" to "integer", "minute" to "integer", "label" to "string"))
        t.put(tool("timer", "Start a countdown timer", "seconds" to "integer", "label" to "string"))
        t.put(tool("torch", "Turn the flashlight on or off", "on" to "boolean"))
        t.put(tool("volume", "Change media volume: action is one of up, down, mute, max", "action" to "string"))
        t.put(tool("navigate", "Start navigation or show a place in Google Maps", "place" to "string"))
        t.put(tool("play_music", "Search a song or video on YouTube", "query" to "string"))
        t.put(JSONObject().put("name", "camera").put("description",
            "Open the camera app. mode is photo or video (default photo); facing is front (selfie camera) or back (default back). Only opens the camera, it cannot press the record or shutter button")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                .put("mode", JSONObject().put("type", "STRING").put("description", "photo or video"))
                .put("facing", JSONObject().put("type", "STRING").put("description", "front or back")))))
        t.put(tool("settings", "Open a phone settings page: wifi, bluetooth, display, sound, location, battery, airplane, apps, dnd, or main", "page" to "string"))
        t.put(tool("brightness", "Set screen brightness 0-100. May need a special permission", "percent" to "integer"))
        t.put(tool("brightness_step", "Increase or decrease brightness by a percentage delta", "delta" to "integer"))
        t.put(tool("media", "Control media playback: play, pause, next, previous", "action" to "string"))
        t.put(tool("global_action", "Phone navigation: home, back, recents, notifications, quick_settings, lock", "action" to "string"))
        t.put(tool("calendar_event", "Create a calendar event. start is local time like 2026-10-09T18:00", "title" to "string", "start" to "string"))
        t.put(tool("search_web", "Search the internet for current information and get a text summary", "query" to "string"))
        return t
    }

    private fun cloudBody(contents: JSONArray, withTools: Boolean, system: String?): String {
        val b = JSONObject().put("contents", contents)
        if (system != null) b.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        if (withTools) {
            b.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations())))
            b.put("generationConfig", JSONObject().put("temperature", 0.2))   // v32: steady tool choice for spoken commands
        }
        return b.toString()
    }

    /** Separate grounding call (Google Search can not be mixed with function tools). Only on an explicit request. */
    private fun searchWeb(q: String): String {
        if (q.isBlank()) return "no query"
        val b = JSONObject()
            .put("contents", JSONArray().put(msg("user", q + "\nGive a concise factual summary with key numbers and dates.")))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .toString()
        val (code, resp) = postFallback(b)
        if (code !in 200..299) return "search failed: " + Logic.failureKind(code)
        return candidateText(resp).ifEmpty { "no result" }
    }

    /** The AI path. Reached only when the user has added their own key AND the sentence is not a local command. */
    private fun askCloud(pcm: ByteArray, text: String): String {
        if (System.currentTimeMillis() - historyAt > Logic.HISTORY_TTL_MS) synchronized(history) { history.clear() }  // old commands are forgotten
        val parts = JSONArray()
        if (pcm.isNotEmpty()) {
            parts.put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "audio/wav")
                .put("data", Base64.encodeToString(wavBytes(pcm), Base64.NO_WRAP))))
        }
        val hint = if (text.isBlank()) "" else " Transcript (may have small mistakes; trust the audio if they differ, and act on the intent): \"$text\"."
        parts.put(JSONObject().put("text", "(This is the user's spoken command, said after the wake word. Understand it and act on it.$hint)"))
        val userMsg = JSONObject().put("role", "user").put("parts", parts)
        val contents = JSONArray()
        synchronized(history) { history.forEach { contents.put(it) } }   // text only, bounded
        contents.put(userMsg)
        val histUser = if (text.isBlank()) "(voice command)" else text
        var executed = 0
        for (step in 0 until Logic.MAX_TOOL_STEPS) {
            val (code, resp) = postFallback(cloudBody(contents, true, systemPrompt()))
            if (code !in 200..299) return cloudFailure(code)
            val content = candidateContent(resp) ?: run { lastCloudFail = "other"; return tr("Jawab nahi mila", "No answer received") }
            val rparts = content.optJSONArray("parts") ?: run { lastCloudFail = "other"; return tr("Jawab nahi mila", "No answer received") }
            val calls = ArrayList<JSONObject>()
            val sb = StringBuilder()
            for (i in 0 until rparts.length()) {
                val p = rparts.optJSONObject(i) ?: continue
                if (p.has("functionCall")) calls.add(p.getJSONObject("functionCall"))
                else if (p.has("text") && !p.optBoolean("thought", false)) sb.append(p.optString("text")).append(' ')
            }
            if (calls.isEmpty()) {
                val reply = sb.toString().trim().ifEmpty { tr("Theek hai", "Okay") }
                remember(histUser, reply)
                return reply
            }
            contents.put(content)
            val res = JSONArray()
            cloudActed = true                       // a tool is about to run: never add a local answer after this
            for (fc in calls) {
                val name = fc.optString("name")
                val out = if (executed++ >= 8) "skipped: too many tool calls"
                else try { runTool(name, fc.optJSONObject("args") ?: JSONObject()) } catch (e: Exception) { "FAILED: tool error" }
                res.put(JSONObject().put("functionResponse",
                    JSONObject().put("name", name).put("response", JSONObject().put("result", out))))
            }
            val wait = pending
            if (wait != null) {                     // a call / WhatsApp send now waits for the user's spoken yes
                remember(histUser, wait.prompt)
                return wait.prompt
            }
            contents.put(JSONObject().put("role", "user").put("parts", res))
        }
        lastCloudFail = "other"
        return tr("Kaam poora nahi ho paya", "I could not finish that")
    }

    // ---------------------------------------------------------------- screen session (v33 JARVIS, explicit command only)
    // "nova screen dekho" reads the screen ONCE and then stays in a session: the owner can ask follow-ups without the wake word
    // (aur detail, isme important kya hai, ise padh ke sunao, iska jawab likho, aage kya karu, ab ye dekho, ruko).
    // The session only TALKS. Typing a reply draft needs a spoken yes; nothing is ever sent. Protected screens never go to the cloud.

    private fun screenOpen(): Boolean = ScreenSession.isOpen(scrState, System.currentTimeMillis())

    private fun endScreenSession() {
        scrState = null
        synchronized(scrHist) { scrHist.clear() }
        scrText = ""
        scrLines = emptyList()
        scrPkg = ""
        scrLabel = ""
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun scrAdopt(sn: NovaAccessibilityService.Snap) {
        scrPkg = sn.pkg
        scrLines = sn.lines
        scrText = ScreenSession.cleanText(sn.lines)
        scrLabel = appLabel(sn.pkg)
        synchronized(scrHist) { scrHist.clear() }   // a new screen: the old talk would only confuse
    }

    /** What the screen shows, read on the phone only (no cloud). */
    private fun screenLocalRead(lines: List<String>, max: Int): String {
        val top = Logic.topLines(lines, max, 120)
        if (top.isEmpty()) return tr(
            "Is screen par padhne layak text nahi mila. Main sirf accessibility text padhta hoon, canvas, game aur secure screens nahi padh sakta",
            "I found no readable text here. I only read accessibility text, and I cannot read canvas, game or secure screens"
        )
        return tr(
            "Screen par ye likha hai: " + top.joinToString(", ") + ".",
            "The screen shows: " + top.joinToString(", ") + "."
        )
    }

    /** Protected screen or no key: answered here, nothing goes to the cloud. null = the cloud may be used. */
    private fun screenGate(intent: ScreenSession.Intent): String? {
        val en = cfg.lang == "en"
        if (ScreenSession.isProtected(scrPkg, packageName)) {
            val lines = scrLines
            return askFirst(ScreenSession.protectedAsk(en)) { screenLocalRead(lines, 8) }
        }
        if (!SecureStore.hasKeys(this)) {
            val reply = screenLocalRead(scrLines, if (intent == ScreenSession.Intent.READ) 12 else 8) + " " + ScreenSession.noKey(en)
            endScreenSession()
            lastKind = "screen_end"
            return reply
        }
        return null
    }

    /** One AI turn about the screen. Pair(true, text) or Pair(false, spoken failure). Worker thread. */
    private fun screenTalk(ask: String, histUser: String, intent: ScreenSession.Intent): Pair<Boolean, String> {
        val en = cfg.lang == "en"
        var shot: ByteArray? = null
        val thin = ScreenSession.needsPicture(scrText)
        if (intent == ScreenSession.Intent.PICTURE || thin) {
            val now = NovaAccessibilityService.readScreen(false)
            if (now == null || ScreenSession.isProtected(now.pkg, packageName)) {
                return Pair(false, if (intent == ScreenSession.Intent.PICTURE) ScreenSession.noPicture(en) else ScreenSession.noText(en))
            }
            shot = NovaAccessibilityService.agentScreenshot()
            if (shot == null) return Pair(false, if (intent == ScreenSession.Intent.PICTURE) ScreenSession.noPicture(en) else ScreenSession.noText(en))
        }
        val parts = JSONArray()
        if (shot != null) {
            parts.put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(shot, Base64.NO_WRAP))))
        }
        parts.put(JSONObject().put("text", ask))
        val contents = JSONArray()
        synchronized(scrHist) { scrHist.forEach { contents.put(it) } }
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        val sys = ScreenSession.systemPrompt(cfg.lang, scrLabel, scrPkg, scrText)
        val (code, resp) = postFallback(cloudBody(contents, false, sys))
        if (code !in 200..299) return Pair(false, cloudFailure(code))
        val out = candidateText(resp).trim()
        if (out.isEmpty()) return Pair(false, tr("Jawab nahi mila", "No answer received"))
        synchronized(scrHist) {
            scrHist.add(msg("user", histUser))
            scrHist.add(msg("model", out))
            Logic.boundHistory(scrHist, ScreenSession.MAX_HIST)
        }
        return Pair(true, out)
    }

    /** First reading ("nova screen dekho"). */
    private fun analyzeScreen(): String {
        if (!NovaAccessibilityService.isEnabled(this) || NovaAccessibilityService.instance == null) return tr(
            "Screen padhne ke liye Accessibility permission on karo. Baaki local commands bina iske chalte hain",
            "Turn on the Accessibility permission to read the screen. Other local commands work without it"
        )
        var snap = NovaAccessibilityService.readScreen(false)   // password-free, non-editable text only  // FIXSCREEN1
        if (snap == null || snap.pkg == packageName) {
            // NOVA's own chat/card is in front: give the user time to open the other app, then read it
            announce(tr("Jo screen padhni hai wo kholo, main 8 second mein padhungi", "Open the screen you want read, I will read it in 8 seconds"))
            val until = System.currentTimeMillis() + 9000L
            while (System.currentTimeMillis() < until) {
                try { Thread.sleep(700) } catch (e: InterruptedException) { break }
                val sn = NovaAccessibilityService.readScreen(false)
                if (sn != null && sn.pkg != packageName && sn.pkg.isNotEmpty()) { snap = sn; break }
            }
        }
        val sn = snap
        if (sn == null || sn.pkg == packageName || sn.pkg.isEmpty()) return tr(
            "Mujhe koi dusra app nahi dikha. NOVA se bahar jaakar dobara bolo: screen dekho",
            "I did not see another app. Leave NOVA and say it again: analyze screen"
        )
        return screenStartFrom(sn)
    }

    /** Opens the session on an already read screen. Used by "screen dekho" and by a spoken yes to a screen alert. Worker thread. */
    private fun screenStartFrom(sn: NovaAccessibilityService.Snap): String {
        lastTurnLocal = false
        lastKind = "screen"
        scrAdopt(sn)
        scrState = ScreenSession.start(sn.pkg, System.currentTimeMillis())
        val gate = screenGate(ScreenSession.Intent.REFRESH)
        if (gate != null) return gate
        val (ok, out) = screenTalk(ScreenSession.firstAsk(), "(screen dekho)", ScreenSession.Intent.REFRESH)
        if (!ok) {
            val local = screenLocalRead(scrLines, 8)
            endScreenSession()
            lastKind = "screen_end"
            return local + " " + out
        }
        return out
    }

    /**
     * A spoken or typed follow-up while a session is open. Returns the reply, or null when the sentence is something else
     * (a normal command, a picture request, a task): the session then ends and the normal path runs. Worker thread.
     */
    private fun screenFollow(pcm: ByteArray, text0: String): String? {
        val en = cfg.lang == "en"
        val now = System.currentTimeMillis()
        val st = scrState
        if (st == null || !ScreenSession.isOpen(st, now)) { endScreenSession(); return null }
        var said = text0
        var cmd = if (said.isBlank()) null else Understand.classify(said)
        var intent = if (said.isBlank()) ScreenSession.Intent.OTHER else ScreenSession.intent(said)
        // The offline text is weak for Hindi: the better listeners write it down (same silent fallback as everywhere else).
        if (intent == ScreenSession.Intent.OTHER && cmd == null && pcm.size >= MIN_HEAR_BYTES) {
            var hs = if (SecureStore.hasGroq(this)) groqHear(pcm) else ""
            if (hs.isBlank()) hs = geminiHear(pcm)
            if (hs.isNotBlank()) {
                said = hs
                cmd = Understand.classify(hs)
                intent = ScreenSession.intent(hs)
            }
        }
        lastTurnLocal = false
        if (said.isBlank()) {   // nothing understood: ask again, this still counts as one turn
            lastKind = "screen"
            scrState = ScreenSession.touch(st, now)
            return endIfLast(tr("Samajh nahi aaya, dobara bolo", "I did not catch that, please say it again"))
        }
        if (cmd != null && cmd.kind != "analyze" && cmd.kind != "stop") { endScreenSession(); return null }   // a normal command ("torch jala do")
        if (cmd?.kind == "stop" || intent == ScreenSession.Intent.STOP) {
            endScreenSession()
            lastKind = "screen_end"
            return ScreenSession.ended(en)
        }
        if (cmd?.kind == "analyze") intent = ScreenSession.Intent.REFRESH
        else if (ScreenSession.isOtherFeature(said)) { endScreenSession(); return null }
        if (intent == ScreenSession.Intent.OTHER && !turnIsFollow) { endScreenSession(); return null }   // a new wake-word sentence is not a screen question
        lastKind = "screen"
        scrState = ScreenSession.touch(st, now)
        return endIfLast(screenAnswer(intent, said))
    }

    /** After the last allowed follow-up the session ends once this reply is spoken. */
    private fun endIfLast(reply: String): String {
        val s = scrState
        if (s != null && ScreenSession.isLastTurn(s)) {
            endScreenSession()
            lastKind = "screen_end"
        }
        return reply
    }

    private fun screenAnswer(intent: ScreenSession.Intent, said: String): String {
        val en = cfg.lang == "en"
        val snap = NovaAccessibilityService.readScreen(false)
        val other = snap != null && snap.pkg.isNotEmpty() && snap.pkg != packageName
        if (other && snap != null && (intent == ScreenSession.Intent.REFRESH || snap.pkg != scrPkg)) scrAdopt(snap)   // "ab ye dekho" or another app is in front now
        else if (!other && intent == ScreenSession.Intent.REFRESH) return tr(
            "Mujhe naya screen nahi dikha. Jo screen dekhni hai wo kholo, phir bolo: ab ye dekho",
            "I do not see a new screen. Open the one you want, then say: look at this now"
        )
        val gate = screenGate(intent)
        if (gate != null) return gate
        val ask = ScreenSession.askFor(intent, said)
        val tag = "(" + intent.name.lowercase() + ") " + if (intent == ScreenSession.Intent.OTHER) said.take(120) else ""
        val (ok, out) = screenTalk(ask, tag.trim(), intent)
        if (!ok) return out
        if (intent == ScreenSession.Intent.REPLY) {
            val draft = ScreenSession.cleanDraft(out)
            if (draft.isEmpty() || out.uppercase().contains("NO_MESSAGE")) return ScreenSession.noReply(en)
            return askFirst(ScreenSession.askType(en, draft)) {   // the spoken yes first; typing only, never sending
                val r = NovaAccessibilityService.typeText(draft)
                if (!r.ok) ctlFail(r.code, draft).msg
                else {
                    try { Thread.sleep(600) } catch (e: InterruptedException) { }   // the Send button appears after text is typed
                    var sent = false
                    for (label in ScreenSession.SEND_LABELS) {
                        if (NovaAccessibilityService.tapLabel(label).ok) { sent = true; break }
                    }
                    if (sent) tr("Likh diya aur bhej diya", "Typed it and sent it")
                    else tr("Likh diya, par Send button nahi mila. Aap khud dabao", "Typed it, but I could not find the Send button. Please press it yourself")
                }
            }
        }
        return out
    }
}
