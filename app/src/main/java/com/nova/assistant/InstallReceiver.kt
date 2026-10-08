package com.nova.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Receives Android's answer for a PackageInstaller session. Android never installs silently: it hands us a
 * confirmation screen that the USER must accept. We show it right away if NOVA's screen is open, otherwise
 * as a tap-to-continue notification (Android blocks activities started from the background).
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, i: Intent) {
        val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (st) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val c = confirmIntent(i) ?: return
                c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (NovaService.uiVisible) {
                    try { ctx.startActivity(c) } catch (e: Exception) {
                        UpdateManager.notifyUser(ctx, "New NOVA is ready. Tap to install.", c)
                    }
                } else {
                    UpdateManager.notifyUser(ctx, "New NOVA is ready. Tap to install.", c)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.notifyUser(ctx, "NOVA was updated.", null)
            else -> {
                val m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"
                UpdateManager.notifyUser(ctx, "NOVA update did not install: $m", null)
            }
        }
    }

    private fun confirmIntent(i: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            i.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }
}
