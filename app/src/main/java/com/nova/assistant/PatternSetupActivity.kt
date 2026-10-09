package com.nova.assistant

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Own screen for NOVA's pattern unlock: save the pattern by tapping dots, ON/OFF, TEST, DELETE.
 * The pattern is never shown on screen (only how many dots were tapped).
 */
class PatternSetupActivity : Activity() {

    private val seq = StringBuilder()
    private val dots = ArrayList<Button>()
    private lateinit var tapped: TextView
    private lateinit var info: TextView
    private lateinit var sw: Switch
    private val h = Handler(Looper.getMainLooper())
    private var building = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, size: Float): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(Color.WHITE)
        v.gravity = Gravity.CENTER
        v.setPadding(0, dp(8), 0, dp(8))
        return v
    }

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        title = "NOVA Pattern"
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(24), dp(20), dp(24))
        col.setBackgroundColor(Color.parseColor("#05060f"))

        col.addView(label("NOVA PATTERN UNLOCK", 20f))
        info = label("", 14f)
        col.addView(info)

        // 3 x 3 dots: tap them in the order of your pattern
        var n = 1
        for (r in 0 until 3) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            for (c in 0 until 3) {
                val d = n
                n++
                val bt = Button(this)
                bt.text = "\u25CF"
                bt.textSize = 22f
                bt.setOnClickListener {
                    if (seq.indexOf(d.toString()) < 0) {
                        seq.append(d)
                        bt.isEnabled = false
                        updateTapped()
                    }
                }
                dots.add(bt)
                row.addView(bt, LinearLayout.LayoutParams(dp(84), dp(84)))
            }
            col.addView(row)
        }
        tapped = label("", 14f)
        col.addView(tapped)

        col.addView(btn("SAVE PATTERN") {
            if (PatternUnlock.save(this, seq.toString())) {
                resetTaps()
                msg("Pattern save ho gaya (encrypted). Ab ON kar sakte ho.")
            } else {
                msg("Pattern 4 se 9 alag dots ka hona chahiye.")
            }
        })
        col.addView(btn("RESET TAPS") { resetTaps(); msg("") })

        sw = Switch(this)
        sw.text = "UNLOCK ON (default OFF)"
        sw.setTextColor(Color.WHITE)
        sw.setOnCheckedChangeListener { _, on ->
            if (building) return@setOnCheckedChangeListener
            if (on && !PatternUnlock.isSet(this)) {
                building = true
                sw.isChecked = false
                building = false
                msg("Pehle pattern save karo.")
            } else {
                PatternUnlock.setEnabled(this, on)
                msg(if (on) "Unlock ON" else "Unlock OFF")
            }
        }
        col.addView(sw)

        col.addView(btn("TEST (10 sec mein phone lock karo)") { startTest() })
        col.addView(btn("DELETE PATTERN") {
            PatternUnlock.clear(this)
            resetTaps()
            msg("Pattern delete ho gaya aur unlock OFF hai.")
        })

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#05060f"))
        sv.addView(col)
        setContentView(sv)
        msg("")
    }

    override fun onResume() {
        super.onResume()
        msg("")
    }

    private fun updateTapped() {
        val sb = StringBuilder()
        for (i in 0 until seq.length) sb.append("\u25CF ")
        tapped.text = sb.toString()
    }

    private fun resetTaps() {
        seq.setLength(0)
        for (d in dots) d.isEnabled = true
        updateTapped()
    }

    private fun msg(extra: String) {
        val acc = NovaAccessibilityService.instance != null
        val last = getSharedPreferences("nova_pu", Context.MODE_PRIVATE).getString("last", "") ?: ""
        building = true
        sw.isChecked = PatternUnlock.enabled(this)
        building = false
        info.text = "Pattern save: " + (if (PatternUnlock.isSet(this)) "haan" else "nahi") +
            "\nAccessibility chalu: " + (if (acc) "haan" else "NAHI - pehle Accessibility ON karo") +
            (if (last.isNotEmpty()) "\nPichhla test: $last" else "") +
            (if (extra.isNotEmpty()) "\n$extra" else "")
    }

    private fun beep(ok: Boolean) {
        try {
            val t = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            t.startTone(if (ok) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 600)
            h.postDelayed({ t.release() }, 1500L)
        } catch (e: Exception) {
            // sound is optional
        }
    }

    private fun saveLast(s: String) {
        getSharedPreferences("nova_pu", Context.MODE_PRIVATE).edit().putString("last", s).apply()
    }

    private fun startTest() {
        if (!PatternUnlock.isSet(this)) { msg("Pehle pattern save karo."); return }
        if (NovaAccessibilityService.instance == null) { msg("Accessibility chalu nahi hai."); return }
        try {
            val pm = getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:pattest")
            wl.acquire(40000L)
        } catch (e: Exception) {
            // optional
        }
        msg("10 second mein NOVA try karega. Power button dabake phone abhi lock karo.")
        val app = applicationContext
        h.postDelayed({
            val svc = NovaAccessibilityService.instance
            val km = app.getSystemService(KeyguardManager::class.java)
            if (svc == null) {
                saveLast("Accessibility band thi")
                beep(false)
            } else if (!km.isKeyguardLocked) {
                saveLast("Phone lock nahi tha, test nahi hua")
                beep(false)
            } else {
                PatternUnlock.run(svc) { ok ->
                    saveLast(if (ok) "PHONE KHUL GAYA" else "Phone NAHI khula (phone ya lock screen ne gesture roka)")
                    beep(ok)
                }
            }
        }, 10000L)
    }
}
