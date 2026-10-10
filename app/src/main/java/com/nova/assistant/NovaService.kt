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
        @Volatile var running = false
        @Volatile var uiVisible = false          // set by MainActivity.onResume/onPause (PART 2)
        var listener: ((String, String) -> Unit)? = null
        @Volatile var instance: NovaService? = null
        @Volatile private var loopThread: Thread? = null

        const val RATE = 16000
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
    @Volatile private var lastKind = ""                      // kind of the command the last turn ran (decides spoken or silent reply)
    @Volatile private var historyAt = 0L                     // when the cloud chat memory was last used
    @Volatile private var dictatePending: Pending? = null   // the pending that waits for a DICTATED driving reply (not a yes/no)
    @Volatile private var dictTarget: Driving.Msg? = null    // the message that dictated reply answers (memory only)
    @Volatile private var answerWindow = false
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
            val waiting = busy || listening || ttsActive || pending != null || answerWindow
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
        if (busy || listening || ttsActive || pending != null || answerWindow) {
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
        brain = BrainStore.get(this)
        h.postDelayed(autoCheck, 45_000L)
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

    override fun onDestroy() {
        alive = false
        Driving.enabled = false
        Driving.inbox.clear()
        DrivingBridge.forgetAll()
        if (instance === this) { instance = null; running = false }
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

    /** Typed chat: runs exactly like a spoken command. A typed "yes" never approves a risky action (that needs the spoken yes). */
    fun typed(text: String): String {
        if (!alive) return "off"
        if (busy) return "busy"
        busy = true
        Thread { process(ByteArray(0), text, 0) }.start()
        return "sent"
    }
    fun announce(text: String) { h.post { if (alive) say(text) } }
    fun reloadVoice() { cfg.applyVoice(tts) }
    fun speakTest(text: String) { cfg.applyVoice(tts); speak(text, false, false) }

    /** Used by the Accessibility alert monitor. False when NOVA is busy / listening / waiting for "yes". */
    fun announceIfIdle(text: String): Boolean {
        if (!alive || busy || listening || ttsActive || pending != null || !ttsReady) return false
        if (Looper.myLooper() == Looper.getMainLooper()) return say(text)
        h.post { if (alive && !busy && !listening && !ttsActive && pending == null) say(text) }
        return true
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

    private fun loadModel(): Model {
        val dir = File(filesDir, "model-en")
        val done = File(dir, ".done")
        if (!done.exists() || !modelOk(dir)) {
            sys(tr("Pehli baar model tayyar ho raha hai, thoda ruko...", "Preparing the model for the first time..."))
            dir.deleteRecursively()
            copyAssets("model-en", dir)
            if (!modelOk(dir)) {
                dir.deleteRecursively()
                throw IllegalStateException("model files missing inside the app")
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

    private fun audioLoop() {
        var model: Model? = null
        var wake: Recognizer? = null
        var cmd: Recognizer? = null
        var rec: AudioRecord? = null
        var tone: ToneGenerator? = null
        try {
            val md = loadModel()
            model = md
            val wakeWord = resolveWake()
            curWake = wakeWord
            val wk = Recognizer(md, RATE.toFloat(), Logic.wakeGrammar(wakeWord))
            wake = wk
            val cm = Recognizer(md, RATE.toFloat())
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
            mode("sleep")
            sys(tr("Sleep mode - \"$wakeWord\" bolo to jaagunga", "Asleep - say \"$wakeWord\" to wake me"))

            val buf = ShortArray(1600) // 100 ms
            var capturing = false
            var answerCapture = false
            var dictCapture = false          // this capture is a dictated driving reply: longer, ends only on silence
            var pcm = ByteArrayOutputStream()
            val cmdText = StringBuilder()
            var heard = false
            var silentMs = 0
            var totalMs = 0
            var skip = 0
            var noise = 300.0
            var wasBlocked = false
            var readErrors = 0
            var peak = 0.0                    // loudest recent chunk: a wake word must be spoken, not just a faint sound
            var lastCaptureEnd = 0L           // cooldown against a second false wake right after a turn
            val pre = ArrayList<ShortArray>() // last ~0.5 s of audio, so the first words after the wake word are not lost

            fun startCapture(answer: Boolean) {
                h.removeCallbacks(sleepRun)    // awake now: no sleeping in the middle of a task
                wk.reset()
                cm.reset()
                tn.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                capturing = true
                listening = true
                answerCapture = answer
                dictCapture = answer && pending != null && pending === dictatePending
                pcm = ByteArrayOutputStream()
                cmdText.setLength(0)
                heard = false
                silentMs = 0
                totalMs = 0
                skip = 2                       // do not record our own beep
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
                    if (wakeRequest) {
                        wakeRequest = false
                        pending = null
                        dictatePending = null
                        dictTarget = null
                        startCapture(false)
                        continue
                    }
                    noise = noise * 0.97 + minOf(rms, 1500.0) * 0.03
                    peak = maxOf(rms, peak * 0.85)
                    pre.add(buf.copyOf(n))
                    while (pre.size > 5) pre.removeAt(0)
                    val done = wk.acceptWaveForm(buf, n)
                    val txt = if (done) wk.getResult() else wk.getPartialResult()
                    val wakeNow = Logic.heardWake(txt, wakeWord)
                    if (wakeNow && (peak < maxOf(900.0, noise * 3.0) || System.currentTimeMillis() - lastCaptureEnd < Logic.WAKE_COOLDOWN_MS)) {
                        wk.reset()                 // too faint or too soon: a false wake (TV, room noise, echo of our voice)
                    } else if (wakeNow) {
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
                    // Vosk's own end-of-utterance (acceptWaveForm == true) ends the capture early.
                    // After it fires the text must be taken with getResult(); getFinalResult() would be empty.
                    var endpoint = false
                    if (cm.acceptWaveForm(buf, n)) {
                        val t = Logic.extractText(cm.getResult())
                        if (t.isNotBlank()) { cmdText.append(' ').append(t); endpoint = false; heard = true }
                    }
                    val maxMs = if (dictCapture) Logic.MAX_DICTATION_MS else if (answerCapture) Logic.MAX_ANSWER_MS else Logic.MAX_COMMAND_MS + ExtCfg.waitMs(this)
                    val noSpeechMs = if (dictCapture) Logic.NO_SPEECH_DICTATION_MS else if (answerCapture) Logic.NO_SPEECH_ANSWER_MS else Logic.NO_SPEECH_COMMAND_MS
                    val silenceMs = if (dictCapture) Logic.DICTATION_SILENCE_MS else if (answerCapture) 1500 else ExtCfg.waitMs(this)
                    val end = endpoint || (heard && silentMs >= silenceMs) ||
                        totalMs >= maxMs || (!heard && totalMs >= noSpeechMs)
                    if (end) {
                        capturing = false
                        listening = false
                        lastCaptureEnd = System.currentTimeMillis()
                        val isAnswer = answerCapture
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
                        val dur = totalMs
                        cm.reset()
                        wk.reset()
                        if (!heard) {
                            mode("on")
                            armSleep(2500L)
                            if (isAnswer) {
                                pending = null
                                dictatePending = null
                                dictTarget = null
                                say(tr("Jawab nahi mila, isliye cancel kar diya", "No answer heard, so I cancelled it"))
                            } else {
                                sys(tr("Kuch suna nahi, dobara \"$wakeWord\" bolo", "Heard nothing, say \"$wakeWord\" again"))
                                h.post { hud?.setReply(tr("Kuch suna nahi", "Heard nothing")) }
                            }
                            continue
                        }
                        busy = true
                        mode("think")
                        sys(tr("Soch raha hoon...", "Thinking..."))
                        if (isAnswer) Thread { processAnswer(text) }.start()
                        else Thread { process(bytes, text, dur) }.start()
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
            try { rec?.stop() } catch (t: Throwable) { }
            try { rec?.release() } catch (t: Throwable) { }
            try { tone?.release() } catch (t: Throwable) { }
            try { wake?.close() } catch (t: Throwable) { }
            try { cmd?.close() } catch (t: Throwable) { }
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
        h.post {
            if (alive) {
                val p = pending
                val confirm = p != null && !Logic.isExpired(p.createdAt, System.currentTimeMillis(), Logic.PENDING_TTL_MS)
                // kind == null (answers to a yes/no, errors) is always spoken; a normal command reply may be silent
                val loud = kind == null || Logic.speakReply(cfg.quietReplies, confirm, kind)
                if (loud) {
                    say(reply, confirm)  // say() FIRST (sets ttsActive) ...
                } else {
                    listener?.invoke("ai", reply)                     // silent: chat + floating card only
                    hud?.setReply(reply)
                }
                mode("on")
                armSleep((hud?.holdMs() ?: 3000L) + 600L)   // reply stays readable, then NOVA sleeps (waits if a yes is pending)
            }
            busy = false                 // ... THEN release the mic loop
        }
    }

    private fun process(pcm: ByteArray, text: String, durMs: Int) {
        val reply: String = try {
            handle(pcm, text)
        } catch (t: Throwable) {
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

    private fun handle(pcm: ByteArray, text: String): String {
        listener?.invoke("me", "🎤 " + (if (text.isNotBlank()) text else tr("(awaaz)", "(voice)")))
        h.post { hud?.setHeard("🎤 " + (if (text.isNotBlank()) text else tr("(awaaz)", "(voice)"))) }
        val c0 = if (text.isBlank()) null else Logic.classify(text)
        val sk = if (c0 == null && text.isNotBlank()) brain.resolve(text) else null   // Layer 1: shortcuts + fuzzy match
        var c: Logic.Cmd? = c0 ?: sk?.let { skillCmd(it) }
        var groqText = ""
        if (c == null && pcm.isNotEmpty() && SecureStore.hasGroq(this)) {
            groqText = groqHear(pcm)
            if (groqText.isNotBlank()) c = Logic.classify(groqText)
        }
        val now = System.currentTimeMillis()
        lastTurnLocal = c != null
        lastKind = c?.kind ?: ""
        if (c0 != null) brain.record(now, text, "local", c0.kind)
        else if (sk != null) brain.record(now, text, "skill", sk.name)
        else brain.record(now, text, "unknown", "")
        if (c != null) return runCommand(c)                       // local, offline, no network
        if (text.isNotBlank()) {                                  // Layer 2: optional offline model (a translator only)
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
        if (SecureStore.hasKeys(this)) {                           // only when the user added an optional key
            if (groqText.isBlank() && Logic.isJunk(text)) {                             // noise / cough / TV: never sent to the cloud, never acted on
                return tr("Samajh nahi aaya, dobara bolo", "I did not catch that, please say it again")
            }
            lastKind = "cloud"
            return askCloud(pcm, if (groqText.isNotBlank()) groqText else text)
        }
        return tr(
            "Ye command local mode mein samajh nahi aaya. Battery, torch, volume, ya open YouTube jaise commands bolo. Khule sawaalon ke liye settings mein API key (optional) daalo.",
            "I did not understand that as a local command. Try battery, torch, volume or open YouTube. For open questions add an optional API key in Settings."
        )
    }

    /** The user's answer to a pending confirmation. ONLY a clear local "yes" approves; the cloud is never asked. */
    private fun processAnswer(text: String) {
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
            tr("Kaam nahi ho paya", "That did not work")
        }
        finishTurn(reply)
    }

    /** Optional Groq Whisper listening (only when the user added a Groq key). Worker thread. The text can start a normal command, never approve one. */
    private fun groqHear(pcm: ByteArray): String {
        val key = SecureStore.getGroq(this)
        if (key.isBlank()) return ""
        val r = GroqStt.transcribe(key, pcm)
        val raw = r.text
        if (raw == null) {
            listener?.invoke("sys", tr(
                "Groq se sunna nahi ho paya (" + GroqStt.failText(r.code) + ")",
                "Groq could not listen (" + GroqStt.failText(r.code) + ")"
            ))
            return ""
        }
        if (raw.isBlank()) return ""
        listener?.invoke("sys", "Groq: \"" + raw + "\"")
        return Logic.stripWake(HindiRoman.toRoman(raw).lowercase())
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
        "analyze" -> analyzeScreen()      // PART 1B
        else -> runLocalTool(c)           // PART 1B: torch, volume, brightness, media, global, open_app, settings, camera
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
                "tap" -> tapCmd(c.arg)
                "type" -> typeCmd(c.arg)
                "scroll" -> scrollCmd(c.arg)
                else -> R(false, tr("Ye command samajh nahi aaya", "I did not understand that command"))
            }
        } catch (e: Exception) {
            R(false, tr("Ye kaam nahi ho paya", "That did not work"))
        }
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
            return CR("", "", R(false, tr(
                "Contacts ki permission nahi hai. NOVA app mein ALLOW CONTACTS dabao",
                "Contacts permission is missing. Tap ALLOW CONTACTS in the NOVA app"
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
        val t = String.format(Locale.ENGLISH, "%02d:%02d", hour, minute)
        return if (fire(i)) R(true, tr("$t ke alarm ki request bheji. Android confirm nahi karta, clock app mein dekh lo",
            "Requested an alarm for $t. Android gives no confirmation, please check the clock app"))
        else R(false, tr("Alarm set karne wala app nahi mila", "No app could set the alarm"))
    }

    private fun timerTool(seconds: Int, label: String): R {
        if (seconds !in 1..86400) return R(false, tr("Timer 1 second se 24 ghante ke beech hona chahiye", "A timer must be between 1 second and 24 hours"))
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (fire(i)) R(true, tr("$seconds second ke timer ki request bheji. Android confirm nahi karta",
            "Requested a $seconds second timer. Android gives no confirmation"))
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
                "camera" -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), null, "Camera")
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
            R(false, "the tool failed")
        }
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
            return Pair(code, text)
        } catch (e: UnknownHostException) {
            return Pair(598, "")
        } catch (e: ConnectException) {
            return Pair(598, "")
        } catch (e: SocketTimeoutException) {
            return Pair(599, "")
        } catch (e: IOException) {
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

    private fun cloudFailure(code: Int): String = when (Logic.failureKind(code)) {
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
        return """You are NOVA, a voice assistant on the user's Android phone. The user's message is a short audio recording of a spoken command in Hindi, Hinglish or English, sometimes with a rough offline transcript that may be wrong: trust the audio. If it is empty or unclear, ask the user briefly to repeat. Your replies are read aloud, so: no markdown, no lists, no emojis, no URLs, short natural sentences. Reply in the language the user spoke: Hindi in Devanagari script for Hindi or Hinglish, English for English. Use the provided tools for phone actions and never claim an action was done unless the tool result says so; if a tool result says requested or not confirmed, say exactly that. The call and whatsapp tools ask the user for a spoken confirmation by themselves, so call them directly without asking first. For call, sms and whatsapp pass the contact name in Latin letters as it is usually saved, for example Rahul. For whatsapp pass only the final message text. You cannot see the phone screen. For analysis or explanations give a clear answer: conclusion first, then two to four reasons, then key risks. Use search_web for anything current such as news, prices or scores. Keep answers under about 8 sentences unless asked for detail. Never call or message anyone the user did not ask for. Never repeat, redo or continue an earlier command unless the user clearly asks for it again in this new message. If the audio is silent, noisy, cut off or unclear, call no tool, change nothing, and only ask the user to repeat. Current time: $now. Phone battery: $bat percent."""
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
        t.put(tool("camera", "Open the camera"))
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
        if (withTools) b.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations())))
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
        val hint = if (text.isBlank()) "" else " Rough offline transcript (may be wrong): \"$text\"."
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
            val content = candidateContent(resp) ?: return tr("Jawab nahi mila", "No answer received")
            val rparts = content.optJSONArray("parts") ?: return tr("Jawab nahi mila", "No answer received")
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
        return tr("Kaam poora nahi ho paya", "I could not finish that")
    }

    // ---------------------------------------------------------------- screen analysis (explicit command only)
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
            if (snap == null || snap.pkg == packageName) return tr(
                "Mujhe koi dusra app nahi dikha. NOVA se bahar jaakar dobara bolo: screen dekho",
                "I did not see another app. Leave NOVA and say it again: analyze screen"
            )
        }
        val lines = snap?.lines ?: emptyList()
        val top = Logic.topLines(lines, 8)
        if (top.isEmpty()) return tr(
            "Is screen par padhne layak text nahi mila (app: ${snap?.pkg}). Main sirf accessibility text padhta hoon, images nahi. Canvas, game aur secure screens nahi padh sakta",
            "I found no readable text here (app: ${snap?.pkg}). I only read accessibility text, not images, and I cannot read canvas, game or secure screens"
        )
        val local = tr(
            "Screen par ye likha hai: " + top.joinToString(", ") + ". Ye sirf accessibility text hai, image nahi.",
            "The screen shows: " + top.joinToString(", ") + ". This is accessibility text only, no images."
        )
        if (!SecureStore.hasKeys(this)) return local
        val text = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n").take(6000)
        val ask = "This is the visible text of an Android screen (text only, no images). Give a concise conclusion first, " +
            "then important controls or warnings. Do not invent anything that is not in the text. No markdown, short spoken sentences. " +
            (if (cfg.lang == "en") "Reply in English." else "Reply in Hindi (Devanagari).") + "\nSCREEN TEXT:\n" + text
        val body = cloudBody(JSONArray().put(msg("user", ask)), false, null)
        val (code, resp) = postFallback(body)
        if (code !in 200..299) return local + " " + cloudFailure(code)
        return candidateText(resp).ifEmpty { local }
    }
}
