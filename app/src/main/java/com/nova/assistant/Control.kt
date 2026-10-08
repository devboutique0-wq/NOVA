package com.nova.assistant

/**
 * Pure rules for voice screen control (tap / type / scroll / swipe). No Android classes, so every rule
 * here is unit-testable. The Accessibility service does the actual work; these rules decide WHAT is allowed.
 *
 * Safety model:
 *  - Risky labels (send, pay, delete, buy, ...) always need a spoken local "yes" first.
 *  - tap/type are refused in the package installer, permission screens, system Settings, the Play Store,
 *    payment/banking apps and NOVA's own screen, so NOVA can never grant itself permissions, install
 *    something, change security settings or move money by itself. Scrolling is allowed everywhere.
 *  - Password fields are never typed into or read.
 */
object Control {

    val RISKY = setOf(
        "send", "pay", "payment", "delete", "remove", "buy", "purchase", "transfer", "order", "uninstall",
        "erase", "reset", "submit", "post", "confirm", "bhejo", "bhej", "hatao", "kharido"
    )

    private val BLOCKED_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.settings",
        "com.android.vending",
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp"
    )

    private val BLOCKED_FRAGMENTS = listOf("bank", "wallet", "upi", "payment")

    fun isRiskyLabel(label: String): Boolean =
        Logic.norm(label).split(" ").any { it in RISKY }

    /** kind is "tap", "type" or "scroll". Scroll is harmless, everything else is blocked in sensitive places. */
    fun blocked(pkg: String?, ownPkg: String, kind: String): Boolean {
        if (pkg == null || pkg.isEmpty()) return true            // no readable window: do not act blind
        if (kind == "scroll") return false
        if (pkg == ownPkg) return true
        if (pkg in BLOCKED_PACKAGES) return true
        val p = pkg.lowercase()
        return BLOCKED_FRAGMENTS.any { it in p }
    }

    private fun clean(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** 2 = the whole text equals the label, 1 = the text contains the label as whole words, 0 = no match. */
    fun matchScore(nodeText: String, label: String): Int {
        val n = clean(nodeText)
        val l = clean(label)
        if (n.isEmpty() || l.isEmpty()) return 0
        if (n == l) return 2
        return if (" $n ".contains(" $l ")) 1 else 0
    }

    /** "down"/"up"/"left"/"right" from spoken words (English and Hinglish), or null. */
    fun direction(words: Collection<String>): String? {
        for (w in words) {
            when (w) {
                "down", "neeche", "niche", "nichhe" -> return "down"
                "up", "upar", "oopar", "uper" -> return "up"
                "left", "baayein", "bayen" -> return "left"
                "right", "daayein", "dayen" -> return "right"
            }
        }
        return null
    }

    /**
     * Finger path (x1,y1,x2,y2) as fractions of the screen. "scroll down" = content moves up = the finger
     * swipes up. "swipe left" = the finger moves left.
     */
    fun swipePath(dir: String): FloatArray? = when (dir) {
        "down" -> floatArrayOf(0.5f, 0.72f, 0.5f, 0.28f)
        "up" -> floatArrayOf(0.5f, 0.28f, 0.5f, 0.72f)
        "left" -> floatArrayOf(0.8f, 0.5f, 0.2f, 0.5f)
        "right" -> floatArrayOf(0.2f, 0.5f, 0.8f, 0.5f)
        else -> null
    }

    /** Text to type: at most 500 characters, control characters removed. */
    fun cleanTyped(s: String): String =
        s.filter { !it.isISOControl() || it == ' ' }.trim().take(500)
}
