package com.nova.assistant

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

/**
 * Part 5: the second factor for a LOCKED phone. Android gives apps NO access to the phone's face-unlock data, so the only honest way
 * is the system BiometricPrompt (the phone's own face / fingerprint screen, shown over the lock screen). It never unlocks the phone;
 * it only tells NOVA "yes, the owner is here". Which biometric it accepts (face or fingerprint) is the phone's choice, not the app's.
 * Decision rules live in LockGate (pure, unit tested). Evidence: written only; not compiled here, not phone tested.
 */
object FaceGate {
    private const val CH = "nova_face"
    private const val NID = 7731

    /** Token of the check whose screen is really on the display (set by the activity). */
    @Volatile var shownToken = 0L

    fun mode(ctx: Context): LockGate.Mode {
        val weak = try {
            BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } catch (t: Throwable) { -99 }
        val secure = try {
            (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        } catch (t: Throwable) { false }
        return LockGate.pickMode(Build.VERSION.SDK_INT, weak, secure)
    }

    /** If the lock state cannot be read, the phone counts as LOCKED (the safe side). */
    fun isLocked(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (t: Throwable) { true }

    /** Shows the system face screen. Worker thread. If the screen did not come up by itself, a full-screen notification is posted. */
    fun launch(ctx: Context, token: Long, mode: LockGate.Mode) {
        val go = Intent(ctx, FaceGateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            .putExtra("token", token)
            .putExtra("mode", if (mode == LockGate.Mode.CREDENTIAL) "credential" else "weak")
        try { ctx.startActivity(go) } catch (t: Throwable) { }
        try { Thread.sleep(1200) } catch (e: InterruptedException) { }
        if (shownToken != token) {
            try { notice(ctx, go) } catch (t: Throwable) { }
        }
    }

    private fun notice(ctx: Context, go: Intent) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "NOVA face check", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 0, go, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("NOVA")
            .setContentText("Face / fingerprint se pehchaan karo")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        nm.notify(NID, n)
    }

    fun dismissNotice(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NID) } catch (t: Throwable) { }
    }
}

/** Transparent screen that only hosts the system BiometricPrompt. */
class FaceGateActivity : FragmentActivity() {
    private var token = 0L
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        token = intent.getLongExtra("token", 0L)
        FaceGate.shownToken = token
        val credential = intent.getStringExtra("mode") == "credential"
        val auth = if (credential) BiometricManager.Authenticators.DEVICE_CREDENTIAL else BiometricManager.Authenticators.BIOMETRIC_WEAK
        val main = Handler(Looper.getMainLooper())
        val exec = Executor { r -> main.post(r) }
        val prompt = BiometricPrompt(this, exec, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                report(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                report(false)
            }
        })
        val b = BiometricPrompt.PromptInfo.Builder()
            .setTitle("NOVA")
            .setSubtitle(if (credential) "Phone ka PIN / pattern daalo" else "Face ya fingerprint se pehchaan karo")
            .setAllowedAuthenticators(auth)
        if (!credential) b.setNegativeButtonText("Cancel")
        try {
            prompt.authenticate(b.build())
        } catch (t: Throwable) {
            report(false)
        }
    }

    private fun report(ok: Boolean) {
        if (done) return
        done = true
        FaceGateState.finish(token, ok, System.currentTimeMillis())
        finish()
    }

    override fun onDestroy() {
        if (!done) {
            done = true
            FaceGateState.finish(token, false, System.currentTimeMillis())   // closed without an answer = not verified
        }
        super.onDestroy()
    }
}
