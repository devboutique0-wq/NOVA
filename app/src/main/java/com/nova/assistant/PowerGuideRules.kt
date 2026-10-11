package com.nova.assistant

/**
 * Part 2: which Android "autostart / background" screen belongs to which phone brand, and what to tap there.
 * Pure Kotlin (no Android classes), unit tested. These screens are private to each brand and can change with an update,
 * so PowerGuide.open tries each one and falls back to the standard Android battery screen when none opens.
 */
object PowerGuideRules {
    class Target(val pkg: String, val cls: String)

    private fun brand(manufacturer: String): String {
        val m = manufacturer.trim().lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "xiaomi"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> "oppo"
            m.contains("vivo") || m.contains("iqoo") -> "vivo"
            m.contains("huawei") || m.contains("honor") -> "huawei"
            m.contains("samsung") -> "samsung"
            else -> "other"
        }
    }

    fun targets(manufacturer: String): List<Target> = when (brand(manufacturer)) {
        "xiaomi" -> listOf(
            Target("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        "oppo" -> listOf(
            Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Target("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            Target("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        )
        "vivo" -> listOf(
            Target("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
        )
        "huawei" -> listOf(
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        "samsung" -> listOf(
            Target("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
        )
        else -> emptyList()
    }

    /** What the owner must switch on once the screen is open. */
    fun hint(manufacturer: String): String = when (brand(manufacturer)) {
        "xiaomi" -> "Autostart me NOVA ON karo. Phir Settings > Apps > NOVA > Battery saver > No restrictions."
        "oppo" -> "Auto launch / Startup manager me NOVA ON karo. Phir Battery me 'Allow background activity' ON karo."
        "vivo" -> "Background start me NOVA allow karo. Battery me 'High background power consumption' allow karo."
        "huawei" -> "App launch me NOVA ko 'Manage manually' karo aur teeno switch (Auto-launch, Secondary launch, Run in background) ON karo."
        "samsung" -> "Battery > Background usage limits > Never sleeping apps me NOVA jodo."
        else -> "Settings > Apps > NOVA > Battery: No restrictions (aur Autostart ho to ON)."
    }
}
