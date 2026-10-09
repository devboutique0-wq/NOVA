package com.nova.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * NOVA's own pattern unlock. Default OFF.
 * The pattern is stored encrypted (Android Keystore, AES-GCM), never shown, never logged.
 * It is only saved from the app screen by tapping the dots, never from a voice command.
 * It draws the pattern with an Accessibility gesture. Some phones block this on the lock screen:
 * NOVA then says so honestly and does not claim the phone is unlocked.
 */
object PatternUnlock {
    private const val ALIAS = "nova_pattern_v1"
    private const val PREF = "nova_pu"
    private val main = Handler(Looper.getMainLooper())

    // ---------- pure helpers (testable) ----------

    /** Dots are numbered 1..9, left to right, top to bottom. 4 to 9 different dots. */
    fun validSeq(s: String): Boolean {
        if (s.length < 4 || s.length > 9) return false
        if (!s.all { it in '1'..'9' }) return false
        return s.toSet().size == s.length
    }

    /** Centre of dot d inside a grid box. Returns x, y. */
    fun dotCenter(d: Int, left: Float, top: Float, right: Float, bottom: Float): Pair<Float, Float> {
        val row = (d - 1) / 3
        val col = (d - 1) % 3
        val cw = (right - left) / 3f
        val ch = (bottom - top) / 3f
        return Pair(left + (col + 0.5f) * cw, top + (row + 0.5f) * ch)
    }

    // ---------- storage ----------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun isSet(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("p", null).isNullOrEmpty()

    fun save(ctx: Context, seq: String): Boolean {
        if (!validSeq(seq)) return false
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(seq.toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("p", blob).commit()
        } catch (e: Exception) {
            false
        }
    }

    private fun load(ctx: Context): String = try {
        val blob = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("p", null)
        if (blob.isNullOrEmpty()) "" else {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        ""
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("p").putBoolean("on", false).apply()
    }

    /** The feature is OFF until the user switches it on in Settings AND a pattern is saved. */
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("on", false) && isSet(ctx)

    fun setEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("on", v).apply()
    }

    // ---------- the unlock itself ----------

    private fun findGrid(n: AccessibilityNodeInfo?, depth: Int): Rect? {
        if (n == null || depth > 12) return null
        val cls = n.className?.toString()?.lowercase() ?: ""
        val id = n.viewIdResourceName?.lowercase() ?: ""
        if (cls.contains("lockpattern") || id.contains("lockpattern")) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 200 && r.height() > 200) return r
        }
        for (i in 0 until n.childCount) {
            val r = findGrid(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    /**
     * Wakes the screen, swipes up, draws the saved pattern, then checks whether the phone really unlocked.
     * cb(true) only when Android itself says the keyguard is gone.
     * Needs android:canPerformGestures="true" in accessibility_config.xml.
     */
    fun run(svc: AccessibilityService, cb: (Boolean) -> Unit) {
        val seq = load(svc)
        if (!validSeq(seq)) { cb(false); return }
        val km = svc.getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { cb(true); return }
        val dm = svc.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        try {
            @Suppress("DEPRECATION")
            val wl = svc.getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "nova:unlock"
            )
            wl.acquire(15000L)
        } catch (e: Exception) {
            // screen may already be on
        }
        // 1) swipe up to reveal the pattern pad
        main.postDelayed({
            val p = Path()
            p.moveTo(w * 0.5f, h * 0.85f)
            p.lineTo(w * 0.5f, h * 0.35f)
            svc.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build(),
                null, null
            )
        }, 700L)
        // 2) draw the pattern
        main.postDelayed({
            val grid = findGrid(svc.rootInActiveWindow, 0)
            val l: Float; val t: Float; val r: Float; val b: Float
            if (grid != null) {
                l = grid.left.toFloat(); t = grid.top.toFloat(); r = grid.right.toFloat(); b = grid.bottom.toFloat()
            } else {
                // guess: pattern pad sits in the lower middle of the screen
                l = w * 0.12f; r = w * 0.88f; t = h * 0.45f; b = h * 0.85f
            }
            val path = Path()
            for ((i, ch) in seq.withIndex()) {
                val c = dotCenter(ch - '0', l, t, r, b)
                if (i == 0) path.moveTo(c.first, c.second) else path.lineTo(c.first, c.second)
            }
            val dur = 200L + 150L * seq.length
            val ok = svc.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(),
                null, null
            )
            // 3) ask Android whether the phone is really unlocked
            main.postDelayed({
                cb(ok && !km.isKeyguardLocked)
            }, dur + 1200L)
        }, 1800L)
    }
}
