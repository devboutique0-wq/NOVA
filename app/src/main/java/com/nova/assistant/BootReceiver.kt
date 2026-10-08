package com.nova.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a phone restart. Android 11+ does not let a microphone service start by itself
 * in the background (the mic would give silence), so on those phones we show one
 * notification and a single tap starts NOVA. Older phones start it directly.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val a = i.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        val cfg = Cfg(c)
        if (!cfg.autostart || !cfg.active) return
        // Modern Android blocks background microphone foreground-service startup.
        // Show a notification; a user tap opens the visible activity, which then starts the mic service safely.
        showTapToStart(c)
    }

    companion object {
        fun showTapToStart(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("nova_boot", "NOVA start", NotificationManager.IMPORTANCE_HIGH)
            )
            val open = Intent(c, MainActivity::class.java)
                .putExtra("autostart", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                c, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(c, "nova_boot")
                .setContentTitle("NOVA band hai")
                .setContentText("Chalu karne ke liye yahan tap karo")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        }
    }
}
