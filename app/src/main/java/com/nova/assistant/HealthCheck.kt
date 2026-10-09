package com.nova.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast

/**
 * Self-check. It only READS the state of permissions and services and shows a box when something is wrong.
 * It changes nothing and sends nothing anywhere. The user can copy the report and paste it into a chat.
 */
object HealthCheck {
    class Item(val name: String, val ok: Boolean, val fix: String, val important: Boolean = true)

    private var lastSig = ""
    private var dialog: AlertDialog? = null

    fun run(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        val mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        out.add(Item("Mic permission", mic, "Settings > Apps > NOVA > Permissions > Microphone: Allow"))
        val notif = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        out.add(Item("Notification permission", notif, "Settings > Apps > NOVA > Notifications: Allow"))
        val accOn = NovaAccessibilityService.isEnabled(ctx)
        val accLive = NovaAccessibilityService.instance != null
        val accFix = if (accOn && !accLive)
            "Switch is ON but Android did not connect it (malfunctioning). Force stop NOVA, then Accessibility > NOVA Phone Control OFF and ON. Also set Autostart ON and Battery: No restrictions"
        else
            "Settings > Accessibility > NOVA Phone Control: ON"
        out.add(Item("Phone Control (Accessibility)", accLive, accFix))
        out.add(Item("Display over other apps", Settings.canDrawOverlays(ctx),
            "Settings > Apps > NOVA > Display over other apps: Allow"))
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val bat = pm != null && pm.isIgnoringBatteryOptimizations(ctx.packageName)
        out.add(Item("Battery unrestricted", bat,
            "Settings > Apps > NOVA > Battery: No restrictions (and Autostart ON)", false))
        out.add(Item("NOVA listening", NovaService.running, "Press ACTIVATE in NOVA", false))
        return out
    }

    fun problems(items: List<Item>): List<Item> = items.filter { !it.ok && it.important }

    fun report(ctx: Context, items: List<Item>): String {
        val vName = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        val sb = StringBuilder()
        sb.append("NOVA HEALTH REPORT\n")
        sb.append("App ").append(vName).append(" | ").append(Build.MANUFACTURER).append(' ')
            .append(Build.MODEL).append(" | Android ").append(Build.VERSION.SDK_INT).append('\n')
        for (i in items) {
            sb.append(if (i.ok) "OK    " else "FAIL  ").append(i.name)
            if (!i.ok) sb.append("  -> ").append(i.fix)
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Called from MainActivity.onResume: runs shortly after the screen is back. */
    fun auto(act: Activity, anchor: View) {
        anchor.postDelayed({ now(act) }, 1500L)
    }

    /** Shows a box only when the list of problems CHANGED since last time (no nagging). */
    private fun now(act: Activity) {
        if (act.isDestroyed || act.isFinishing) return
        try {
            val bad = problems(run(act))
            val sig = bad.joinToString("|") { it.name }
            if (sig == lastSig) return
            lastSig = sig
            dialog?.dismiss()
            dialog = null
            if (bad.isEmpty()) {
                Toast.makeText(act, "NOVA health: sab theek hai", Toast.LENGTH_SHORT).show()
                return
            }
            val msg = bad.joinToString("\n\n") { "- " + it.name + "\n  Fix: " + it.fix }
            val d = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("NOVA health check")
                .setMessage(msg)
                .setPositiveButton("COPY REPORT") { _, _ -> copy(act) }
                .setNegativeButton("OK", null)
                .create()
            d.setOnDismissListener { if (dialog === d) dialog = null }
            d.show()
            dialog = d
        } catch (e: Exception) {
            // a health check must never crash the app
        }
    }

    private fun copy(act: Activity) {
        try {
            val txt = report(act, run(act))
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NOVA health report", txt))
            Toast.makeText(act, "Report copy ho gaya, Claude ko paste karo", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
        }
    }
}
