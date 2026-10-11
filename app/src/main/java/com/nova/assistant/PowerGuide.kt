package com.nova.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Part 2: opens the screen where the owner lets NOVA keep running in the background. It only OPENS a screen. It changes no setting
 * itself (Android does not allow that). The text it returns says what to tap there.
 */
object PowerGuide {
    fun open(ctx: Context): String {
        val maker = Build.MANUFACTURER ?: ""
        val hint = PowerGuideRules.hint(maker)
        for (t in PowerGuideRules.targets(maker)) {
            try {
                ctx.startActivity(
                    Intent().setComponent(ComponentName(t.pkg, t.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return hint
            } catch (e: Exception) {
                // this brand screen is not on this phone: try the next one
            }
        }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = pm != null && pm.isIgnoringBatteryOptimizations(ctx.packageName)
        if (!ignoring) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Battery list khuli hai: NOVA dhoondho aur 'Don't optimize' / 'Allow' karo. $hint"
            } catch (e: Exception) {
                // fall through to the app info screen
            }
        }
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "NOVA ki app info khuli hai. $hint"
        } catch (e: Exception) {
            return "Settings khul nahi paayi. Haath se karo: $hint"
        }
    }
}
