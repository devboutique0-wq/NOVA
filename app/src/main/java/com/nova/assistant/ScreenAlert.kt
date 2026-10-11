package com.nova.assistant

/**
 * v33 PART 2: the screen ALERT, made rare and useful.
 *
 * Before: any short UI line with a word like "failed" / "error" / "warning" in ANY app made NOVA speak every ~2 minutes
 * ("Screen alert: failed. Say analyze screen for details.").
 * Now an alert needs a DIALOG: a short line with an alert phrase AND a button line (OK / Retry / Cancel / Close ...)
 * on a small screen, in an app where such words are not just content. Then a strict rate limit. Then NOVA only ASKS
 * ("Detail bolun? Haan bolo."); a spoken yes opens the Part 1 screen session; no yes = silence.
 *
 * Pure (no Android classes), unit-tested (ScreenAlertTest + tools/test_screen_alert_contract.py).
 */
object ScreenAlert {
    const val SNOOZE_MS = 10 * 60_000L      // the same (app + alert) is not announced again for 10 minutes
    const val HOUR_MS = 60 * 60_000L
    const val HOURLY_CAP = 3                // at most 3 spoken alerts per hour, whatever they are
    const val MIN_GAP_MS = 60_000L          // and never two within a minute
    const val MAX_LINES = 20                // a dialog is a small screen; a full app page is not a dialog
    const val MAX_LINE = 100                // long text is content (chat, article), not a dialog title

    class Hit(val pkg: String, val phrase: String, val line: String) {
        val key: String get() = "$pkg|$phrase"
    }

    /** Apps where "failed" / "error" are normally CONTENT: messaging, browsers, social, news, mail, launcher, keyboards. */
    private val IGNORE_FRAGMENTS = listOf(
        "whatsapp", "telegram", "messaging", "mms", "messenger", "sms", "signal", "discord", "viber", "line.android",
        "instagram", "facebook", "twitter", "snapchat", "reddit", "linkedin", "threads", "pinterest", "tiktok", "sharechat",
        "chrome", "browser", "firefox", "opera", "brave", "duckduckgo", "edge", "vivaldi",
        "youtube", "news", "dailyhunt", "inshorts", "google.android.gm", "mail", "outlook",
        "launcher", "miui.home", "inputmethod", "keyboard", "swiftkey", "latin", "gboard", "nova.assistant"
    )

    fun ignoredPackage(pkg: String, ownPkg: String): Boolean {
        if (pkg.isEmpty() || pkg == ownPkg) return true
        if (ScreenSession.isProtected(pkg, ownPkg)) return true      // settings, installer, Play Store, bank / payment: never
        val p = pkg.lowercase()
        return IGNORE_FRAGMENTS.any { it in p }
    }

    /** Whole-line button words of an error dialog (English + a few Hindi). */
    private val BUTTONS = setOf(
        "ok", "okay", "retry", "try again", "cancel", "close", "close app", "wait", "dismiss", "got it", "report",
        "app info", "force close", "send feedback", "ठीक है", "फिर से कोशिश करें", "बंद करें", "रद्द करें"
    )

    /** System "app not responding / has stopped" dialogs: these are dialogs by their wording alone when they come from the system. */
    private val STRONG = listOf("isn't responding", "is not responding", "not responding", "has stopped", "keeps stopping", "stopped working")
    private val WEAK = listOf(
        "payment failed", "verification failed", "access denied", "permission denied", "something went wrong",
        "no internet connection", "crashed", "couldn't", "could not", "unable to", "failed", "error"
    )
    private fun re(p: String) = Regex("\\b" + Regex.escape(p) + "\\b")
    private val STRONG_RES = STRONG.map { it to re(it) }
    private val WEAK_RES = WEAK.map { it to re(it) }

    private fun norm(s: String): String = s.trim().lowercase().replace('’', '\'').replace('‘', '\'')

    /** null = no alert. Dialog context is required (see the class comment). */
    fun detect(pkg: String, lines: List<String>, ownPkg: String): Hit? {
        if (ignoredPackage(pkg, ownPkg)) return null
        if (lines.isEmpty() || lines.size > MAX_LINES) return null
        val ls = lines.map { norm(it) }.filter { it.isNotEmpty() }
        val hasButton = ls.any { it in BUTTONS }
        val system = pkg == "android"
        for (i in ls.indices) {
            val l = ls[i]
            if (l.length > MAX_LINE || l in BUTTONS) continue
            for ((phrase, r) in STRONG_RES) if (r.containsMatchIn(l) && (hasButton || system)) return Hit(pkg, phrase, lines[i].trim())
            if (!hasButton) continue
            for ((phrase, r) in WEAK_RES) if (r.containsMatchIn(l)) return Hit(pkg, phrase, lines[i].trim())
        }
        return null
    }

    /** Rate limit state (one instance in the accessibility service). Times are monotonic milliseconds. */
    class Gate {
        private val byKey = HashMap<String, Long>()
        private val spoken = ArrayList<Long>()

        fun allow(now: Long, key: String): Boolean {
            spoken.removeAll { now < it || now - it >= HOUR_MS }
            if (spoken.size >= HOURLY_CAP) return false
            if (spoken.isNotEmpty() && now - spoken[spoken.size - 1] < MIN_GAP_MS) return false
            val last = byKey[key]
            if (last != null && now >= last && now - last < SNOOZE_MS) return false
            return true
        }

        /** Call only when the alert was really spoken. */
        fun record(now: Long, key: String) {
            spoken.add(now)
            byKey[key] = now
            if (byKey.size > 50) byKey.entries.removeAll { now - it.value >= SNOOZE_MS }
        }
    }

    /** Short reason in words (no provider names). */
    fun reason(h: Hit, en: Boolean): String = when {
        STRONG.any { it == h.phrase } -> if (en) "the app is not responding or has stopped" else "app atak gaya ya band ho gaya"
        h.phrase == "no internet connection" -> if (en) "there is no internet" else "internet nahi chal raha"
        h.phrase == "payment failed" -> if (en) "a payment failed" else "payment fail hua"
        else -> if (en) "an error message is showing" else "ek error message dikh raha hai"
    }

    /** The only thing NOVA says: one-line reason + a yes/no question. Never asks the owner to do work. */
    fun question(h: Hit, appLabel: String, en: Boolean): String =
        if (en) "In $appLabel ${reason(h, true)}. Shall I tell you more? Say yes."
        else "$appLabel mein ${reason(h, false)}. Detail bolun? Haan bolo."
}
