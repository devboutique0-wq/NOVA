package com.nova.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

/**
 * Settings > SELF TEST. Runs real checks one after another on the phone and writes PASS / FAIL / WARN / SKIP lines.
 * It never calls, messages, pays, installs or deletes anything. It briefly opens the Android Settings screen to prove
 * that NOVA can read another app, then comes back. Nothing is sent anywhere except one tiny Gemini text request (if a key is set).
 */
object SelfTest {
    @Volatile var running = false

    fun start(act: Activity, emit: (String) -> Unit, done: (String) -> Unit) {
        if (running) return
        running = true
        Thread {
            val out = ArrayList<String>()
            val rec: (String, String, String) -> Unit = { tag, name, detail ->
                val line = tag.padEnd(5) + " " + name + (if (detail.isNotEmpty()) " - " + detail else "")
                out.add(line)
                emit(line)
            }
            try {
                body(act, rec)
            } catch (e: Throwable) {
                rec("FAIL", "Self test beech mein ruk gaya", e.javaClass.simpleName)
            }
            val vName = try {
                act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "?"
            } catch (e: Exception) { "?" }
            val sb = StringBuilder()
            sb.append("NOVA SELF TEST | app ").append(vName).append(" | ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" | Android ").append(Build.VERSION.SDK_INT).append('\n')
            for (l in out) sb.append(l).append('\n')
            sb.append("SUMMARY pass=").append(out.count { it.startsWith("PASS") })
                .append(" fail=").append(out.count { it.startsWith("FAIL") })
                .append(" warn=").append(out.count { it.startsWith("WARN") })
                .append(" skip=").append(out.count { it.startsWith("SKIP") })
            running = false
            done(sb.toString())
        }.start()
    }

    private fun pause(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    private fun body(act: Activity, rec: (String, String, String) -> Unit) {
        val ctx = act.applicationContext

        // ---- 1. permissions and switches (read only)
        for (item in HealthCheck.run(ctx)) {
            if (item.ok) rec("PASS", item.name, "") else rec(if (item.important) "FAIL" else "WARN", item.name, item.fix)
        }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) rec("PASS", "Contacts permission", "")
        else rec("WARN", "Contacts permission", "call/message ke liye chahiye")
        val kn = KnowledgeStore.count(ctx)
        if (kn > 0) rec("PASS", "Knowledge pack", kn.toString() + " entries loaded")
        else rec("WARN", "Knowledge pack", "knowledge.json load nahi hua (offline jawab band)")
        val nl = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        if (nl.contains(ctx.packageName)) rec("PASS", "Notification access", "")
        else rec("WARN", "Notification access", "sirf driving mode ke liye (optional)")
        if (Settings.System.canWrite(ctx)) rec("PASS", "Write settings (brightness)", "")
        else rec("WARN", "Write settings (brightness)", "sirf brightness command ke liye (optional)")

        // ---- 2. the service
        val svc = NovaService.instance
        if (!NovaService.running || svc == null) {
            rec("FAIL", "NOVA service", "chal nahi rahi. Home par ACTIVATE dabao, phir test dobara chalao")
        } else {
            rec("PASS", "NOVA service", svc.testState())
            svc.speakTest("NOVA self test. Agar awaaz aa rahi hai to speaker theek hai.")
            rec("INFO", "Awaaz test", "NOVA ne abhi bola. Awaaz aayi? Mujhe haan ya nahi batao")
            pause(3500)
        }

        // ---- 3. does the command matcher understand these phrases (runs on this phone)
        val cases = listOf(
            "battery kitni hai" to "battery",
            "analyze screen" to "analyze",
            "screen dekho" to "analyze",
            "screen par kya hai" to "analyze",
            "flashlight on" to "torch_on",
            "volume up" to "volume"
        )
        for ((p, want) in cases) {
            val got = Logic.classify(p)?.kind ?: "none"
            rec(if (got == want) "PASS" else "WARN", "Samajh: \"" + p + "\"", "mila=" + got + " chahiye=" + want)
        }

        // ---- 4. real phone tools
        if (svc != null) {
            val b = svc.testBattery()
            rec(if (b.any { it.isDigit() }) "PASS" else "FAIL", "Battery padhna", b)
            val t1 = svc.testTorch(true)
            pause(700)
            val t2 = svc.testTorch(false)
            rec(if (t1.first && t2.first) "PASS" else "FAIL", "Torch on aur off", t1.second + " / " + t2.second)
            val am = ctx.getSystemService(AudioManager::class.java)
            val vol0 = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val v1 = svc.testVolume("up")
            val v2 = svc.testVolume("down")
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, vol0, 0) } catch (e: Exception) { }
            rec(if (v1.first && v2.first) "PASS" else "FAIL", "Volume up aur down", v1.second + " / " + v2.second)
        } else {
            rec("SKIP", "Battery, torch, volume", "NOVA service band hai")
        }

        // ---- 5. accessibility: can NOVA really read another app's screen
        if (NovaAccessibilityService.instance == null) {
            rec("FAIL", "Accessibility (screen padhna, scroll, back)", "connect nahi hua. Settings > Accessibility > NOVA Phone Control OFF phir ON, aur Autostart ON")
        } else {
            rec("PASS", "Accessibility connected", "")
            val own = NovaAccessibilityService.readScreen(false)
            rec("INFO", "NOVA ki apni screen", "app=" + (own?.pkg ?: "none") + " lines=" + (own?.lines?.size ?: 0))
            act.runOnUiThread {
                try { act.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) { }
            }
            pause(3000)
            val snap = NovaAccessibilityService.readScreen(false)
            val lines = snap?.lines?.filter { it.isNotBlank() } ?: emptyList()
            val okRead = snap != null && snap.pkg != ctx.packageName && lines.size >= 3
            rec(
                if (okRead) "PASS" else "FAIL", "Dusre app ki screen padhna (Android Settings)",
                "app=" + (snap?.pkg ?: "none") + " lines=" + lines.size + " sample=" + lines.take(3).joinToString(" | ").take(120)
            )
            val sd = NovaAccessibilityService.scrollDir("down")
            pause(600)
            NovaAccessibilityService.scrollDir("up")
            rec(if (sd.ok) "PASS" else "FAIL", "Scroll gesture", "code=" + sd.code)
            val bk = NovaAccessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            pause(1300)
            if (!NovaService.uiVisible) {
                act.runOnUiThread {
                    try {
                        act.startActivity(
                            Intent(act, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    } catch (e: Exception) { }
                }
                pause(1300)
            }
            rec(if (bk) "PASS" else "FAIL", "Global action BACK", if (NovaService.uiVisible) "NOVA wapas aa gaya" else "NOVA screen par wapas nahi aaya")
        }

        // ---- 6. cloud text (one tiny request)
        if (!SecureStore.hasKeys(ctx)) {
            rec("SKIP", "Gemini AI (text)", "key nahi hai")
        } else {
            val r = ImageGen.textPing(ctx)
            if (r.first in 200..299) rec("PASS", "Gemini AI (text) jawab", "code=" + r.first + " model=" + r.second)
            else rec("FAIL", "Gemini AI (text) jawab", "code=" + r.first + " " + ImageGen.failMsg(r.first))
        }
        if (SecureStore.hasGroq(ctx)) rec("SKIP", "Groq awaaz key", "key hai, par alag se test nahi kiya")
        else rec("SKIP", "Groq awaaz key", "key nahi hai (optional)")
        rec("INFO", "Image banana/edit", "iske liye neeche IMAGE TEST dabao (paisa lag sakta hai)")

        // ---- 7. things a machine cannot hear: the user answers these
        rec("MANUAL", "1", "Awaaz aayi thi? haan/nahi")
        rec("MANUAL", "2", "Home par jaake bolo: nova  -> NOVA jaagi? haan/nahi")
        rec("MANUAL", "3", "Phir bolo: battery kitni hai  -> jawab aaya? haan/nahi")
        rec("MANUAL", "4", "Chrome kholo, bolo: nova screen dekho  -> NOVA ne kya bola? likh do")
    }
}
