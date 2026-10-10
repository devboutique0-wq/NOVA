package com.nova.assistant

/**
 * Pure logic (no Android classes) so it can be unit-tested on every build.
 * Everything that decides "what did the user mean / what should we do next" lives here.
 */
object Logic {
    const val DEFAULT_WAKE = "nova"

    // Gemini model IDs that are currently served. gemini-2.5-* is scheduled for shutdown on
    // 2026-10-16, and unknown IDs only waste a request, so they are not in the rotation.
    const val DEFAULT_MODEL = "gemini-3.5-flash"
    val MODELS = listOf(
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite"
    )

    // ---- timing / bounds ----
    const val END_SILENCE_MS = 5000          // silence after speech that ends a command
    const val MAX_COMMAND_MS = 12000        // hard cap for one spoken command
    const val NO_SPEECH_COMMAND_MS = 3000   // give up if nothing is said after the wake word
    const val MAX_ANSWER_MS = 6000          // hard cap for a yes/no answer
    const val NO_SPEECH_ANSWER_MS = 4000
    const val MAX_DICTATION_MS = 15000      // hard cap for one dictated driving reply
    const val NO_SPEECH_DICTATION_MS = 6000 // give up if nothing is said after "bolo, kya jawab bhejna hai"
    const val DICTATION_SILENCE_MS = 1300   // people pause mid-sentence: wait a bit longer than for a command
    const val PENDING_TTL_MS = 30000L       // a confirmation older than this is dead
    const val WAKE_COOLDOWN_MS = 1500L      // after a turn ends, ignore the wake word for a moment (echo, tail of our own voice)
    const val HISTORY_TTL_MS = 120_000L     // cloud chat memory older than this is forgotten, so an old command can never come back
    const val HISTORY_MAX = 6
    const val MAX_TOOL_STEPS = 6
    const val MAX_KEYS = 10
    const val MAX_CALLS = 12                // upper bound of HTTP attempts for ONE request
    const val CLOUD_DEADLINE_MS = 45000L
    const val ALERT_MIN_GAP_MS = 15000L
    const val ALERT_SAME_GAP_MS = 120000L

    /** Wake word: one English word, 3-12 letters. Returns null if not valid. */
    fun cleanWake(raw: String?): String? {
        val w = (raw ?: "").trim().lowercase()
        if (w.length < 3 || w.length > 12) return null
        if (!w.all { it in 'a'..'z' }) return null
        return w
    }

    fun wakeOrDefault(raw: String?): String = cleanWake(raw) ?: DEFAULT_WAKE

    fun wakeGrammar(w: String): String = "[\"$w\", \"hey $w\", \"[unk]\"]"

    fun cleanModel(raw: String?): String = if (raw != null && raw in MODELS) raw else DEFAULT_MODEL

    fun clampRate(v: Float): Float = v.coerceIn(0.6f, 1.6f)
    fun clampPitch(v: Float): Float = v.coerceIn(0.7f, 1.4f)

    private val TEXT_RE = Regex("\"(?:text|partial)\"\\s*:\\s*\"([^\"]*)\"")

    /** Raw spoken text of a Vosk JSON result (wake word included). */
    private fun rawText(json: String): String = TEXT_RE.find(json)?.groupValues?.get(1)?.trim() ?: ""

    private fun wakePrefixFor(w: String): Regex =
        Regex("^(?:(?:the|hey|a)\\s+)?(?:" + DEFAULT_WAKE + "|" + w + ")\\b\\s*")

    @Volatile private var wakePrefix: Regex = wakePrefixFor(DEFAULT_WAKE)

    /** The wake word chosen in Settings. "nova" is always stripped too. Called by NovaService when the listener starts. */
    fun setWake(w: String) { wakePrefix = wakePrefixFor(cleanWake(w) ?: DEFAULT_WAKE) }

    /** Removes the wake word ("nova", "the nova", "hey nova", or the custom one) from the START of a command, so it is never part of the command. */
    fun stripWake(s: String): String {
        var x = s.trim()
        val re = wakePrefix
        while (true) {
            val y = re.replace(x, "").trim()
            if (y == x) break
            x = y
        }
        return x
    }

    // ---------------------------------------------------------------- smart end of speech

    const val FAST_END_MIN_SILENCE_MS = 600     // only look at the live text after this much silence
    const val FAST_END_MS = 1200                // a complete simple command: stop waiting after this much silence
    const val FAST_END_OPEN_APP_MS = 1800       // app names can have several words: a little longer

    private val FAST_KINDS = setOf(
        "battery", "time", "date", "torch_on", "torch_off", "volume", "brightness_set", "brightness_step",
        "media", "global", "settings", "camera", "monitor_on", "monitor_off", "quiet_on", "quiet_off",
        "update_check", "analyze", "drive_on", "drive_off", "drive_read", "drive_clear", "drive_reply", "unlock"
    )

    /**
     * How much silence is enough when the live text already is ONE complete, simple local command. 0 = keep the normal wait.
     * Never for typed text, taps or dictated replies (they may continue after a pause).
     */
    fun fastEndMs(liveText: String): Int {
        if (liveText.isBlank()) return 0
        val c = classify(liveText) ?: return 0
        if (c.kind == "open_app") return FAST_END_OPEN_APP_MS
        return if (c.kind in FAST_KINDS) FAST_END_MS else 0
    }

    /** Reads the spoken COMMAND text out of a Vosk JSON result (the leading wake word is removed). */
    fun extractText(json: String): String = stripWake(rawText(json))

    fun heardWake(json: String, wake: String): Boolean =
        rawText(json).split(" ").any { it == wake }

    // ---------------------------------------------------------------- yes / no

    private val YES = setOf("yes", "yeah", "yep", "yup", "okay", "ok", "sure", "haan", "han", "ha", "ji")
    private val NO = setOf(
        "no", "nope", "nah", "cancel", "stop", "dont", "don't", "not", "nahi", "nahin", "mat", "ruko", "wait"
    )

    /**
     * Offline reading of a yes/no answer.
     * false = refusal, true = clearly "yes", null = unclear. Unclear is ALWAYS treated as "no"
     * by the caller, so only a short, purely affirmative answer can ever approve something.
     */
    fun parseAnswer(text: String): Boolean? {
        val w = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() && it != "[unk]" }
        if (w.isEmpty()) return null
        if (w.any { it in NO }) return false
        if (w.size <= 3 && w.all { it in YES }) return true
        return null
    }

    // ---------------------------------------------------------------- noise guard / quiet replies

    private val NOISE_WORDS = setOf("the", "a", "an", "uh", "huh", "hm", "hmm", "um", "ah", "oh", "and", "it", "is", "i", "so", "to", "of")

    /** True when a capture holds no real words (silence, a cough, TV noise turned into "the"). Such a capture is never sent anywhere. */
    fun isJunk(text: String): Boolean {
        val w = norm(text).split(" ").filter { it.isNotBlank() }
        return w.isEmpty() || w.all { it in NOISE_WORDS || it.length <= 2 }
    }

    /** Replies of these commands are always spoken: the user asked to HEAR something (driving messages, screen text, the mode switch). */
    val LOUD_KINDS: Set<String> = setOf(
        "drive_on", "drive_off", "drive_read", "drive_clear", "drive_reply", "drive_dictate", "analyze", "quiet_on", "quiet_off"
    )

    /** false = show the reply in the chat / card only. A waiting yes/no question is ALWAYS spoken (risky actions stay audible). */
    fun speakReply(quiet: Boolean, confirmPending: Boolean, kind: String): Boolean =
        !quiet || confirmPending || kind in LOUD_KINDS

    fun isExpired(createdAt: Long, now: Long, ttlMs: Long): Boolean = now < createdAt || now - createdAt > ttlMs

    // ---------------------------------------------------------------- messages / contacts

    fun normalizeMsg(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")

    fun sameMessage(a: String, b: String): Boolean = normalizeMsg(a) == normalizeMsg(b)

    /** Digits for a wa.me link. 10 digit numbers are treated as Indian (+91). */
    fun waNumber(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return when {
            d.length == 10 -> "91$d"
            d.length == 11 && d.startsWith("0") -> "91" + d.substring(1)
            else -> d
        }
    }

    fun shorten(s: String, max: Int = 160): String =
        if (s.length <= max) s else s.substring(0, max).trimEnd() + "..."

    /** Picks the best contact for a spoken name. Returns index or -1. */
    fun pickContact(query: String, names: List<String>): Int {
        if (names.isEmpty()) return -1
        val q = query.trim().lowercase()
        val exact = names.indexOfFirst { it.trim().lowercase() == q }
        if (exact >= 0) return exact
        val word = names.indices.filter { i -> names[i].lowercase().split(" ", "-").contains(q) }
        if (word.isNotEmpty()) return word.minByOrNull { names[it].length } ?: -1
        return names.indices.minByOrNull { names[it].length } ?: -1
    }

    /** Best installed-app match for a spoken name. Returns index or -1. */
    fun bestAppIndex(query: String, labels: List<String>): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty() || labels.isEmpty()) return -1
        val l = labels.map { it.trim().lowercase() }
        val qc = q.replace(" ", "")
        val lc = l.map { it.replace(" ", "") }
        val exact = lc.indexOfFirst { it == qc }
        if (exact >= 0) return exact
        val starts = l.indices.filter { lc[it].startsWith(qc) }
        if (starts.isNotEmpty()) return starts.minByOrNull { l[it].length } ?: -1
        val words = l.indices.filter { l[it].split(" ").contains(q) }
        if (words.isNotEmpty()) return words.minByOrNull { l[it].length } ?: -1
        // a very short heard word must not match "somewhere inside" a name (misheard "in" opened random apps)
        val sub = if (q.length >= 4) l.indices.filter { l[it].contains(q) } else emptyList()
        return sub.minByOrNull { l[it].length } ?: -1
    }

    // ---------------------------------------------------------------- API keys / cloud failover

    /** Splits pasted text into plausible keys. Never logs or echoes them. */
    fun parseKeys(raw: String): List<String> =
        raw.split(Regex("[\\r\\n,;]+"))
            .map { it.trim() }
            .filter { it.length in 20..200 && it.none { c -> c.isWhitespace() } }
            .distinct()
            .take(MAX_KEYS)

    enum class Next { SUCCESS, NEXT_KEY, NEXT_MODEL, STOP }

    /**
     * Deterministic failover rule for ONE http answer.
     * 598 = network is down (stop at once, retrying only wastes time), 599 = timeout / IO error.
     */
    fun nextStep(code: Int, body: String): Next = when {
        code in 200..299 -> Next.SUCCESS
        code == 598 -> Next.STOP
        code == 400 ->
            if (body.contains("API key", ignoreCase = true) || body.contains("API_KEY_INVALID")) Next.NEXT_KEY
            else Next.STOP
        code == 401 || code == 403 -> Next.NEXT_KEY
        code == 404 -> Next.NEXT_MODEL
        code == 429 -> Next.NEXT_KEY
        code == 599 || code in 500..599 -> Next.NEXT_KEY
        else -> Next.STOP
    }

    /** nokey | network | key | quota | model | server | other */
    fun failureKind(code: Int): String = when {
        code == 0 -> "nokey"
        code == 598 || code == 599 -> "network"
        code == 400 || code == 401 || code == 403 -> "key"
        code == 429 -> "quota"
        code == 404 -> "model"
        code in 500..597 -> "server"
        else -> "other"
    }

    /** Keeps the newest entries. Entries are stored as user/model pairs, so drop two at a time. */
    fun <T> boundHistory(list: MutableList<T>, max: Int = HISTORY_MAX) {
        while (list.size > max) {
            list.removeAt(0)
            if (list.isNotEmpty()) list.removeAt(0)
        }
    }

    // ---------------------------------------------------------------- screen helpers

    /** De-duplicated, shortened text lines for a summary or an explicit analysis request. */
    fun topLines(lines: List<String>, max: Int, maxLen: Int = 80): List<String> =
        lines.map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(max)
            .map { shorten(it, maxLen) }

    private val ALERT_PHRASES = listOf(
        "payment failed", "verification failed", "access denied", "permission denied",
        "something went wrong", "no internet connection", "not responding", "has stopped",
        "keeps stopping", "unable to", "could not", "couldn't", "failed", "failure",
        "error", "warning", "blocked", "crashed"
    )
    private val ALERT_RES = ALERT_PHRASES.map { it to Regex("\\b" + Regex.escape(it) + "\\b") }

    /** Obvious error/warning wording in short UI lines (long chat text is ignored). */
    fun detectAlert(lines: List<String>): String? {
        for (line in lines) {
            if (line.length > 100) continue
            val l = line.lowercase()
            for ((phrase, re) in ALERT_RES) if (re.containsMatchIn(l)) return phrase
        }
        return null
    }

    /** Alert spam guard. lastAt < 0 means "never announced". */
    fun shouldAnnounce(now: Long, lastAt: Long, lastKey: String, key: String): Boolean {
        if (lastAt < 0) return true
        val gap = now - lastAt
        return if (key == lastKey) gap >= ALERT_SAME_GAP_MS else gap >= ALERT_MIN_GAP_MS
    }

    // ---------------------------------------------------------------- local command understanding

    data class Cmd(val kind: String, val arg: String = "", val num: Int = 0)

    private val FILLER = setOf(
        "please", "the", "my", "nova", "hey", "now", "a", "an", "to", "for", "me",
        "can", "you", "could", "will", "just", "zara"
    )
    private val NAVFILL = setOf("open", "show", "go", "jao", "kholo", "khol", "dikhao", "dekho", "karo", "kar", "do")

    /** Lower-case words only. Vosk never returns digits or punctuation. */
    fun norm(text: String): String {
        var s = text.lowercase().replace("[unk]", " ").replace("-", " ")
        s = s.replace(Regex("[^a-z0-9% ]"), " ").replace(Regex("\\s+"), " ").trim()
        return s.replace("wi fi", "wifi").replace("flash light", "flashlight")
            .replace("blue tooth", "bluetooth").replace("you tube", "youtube")
    }

    private val ONES = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    /** "fifty" -> 50, "twenty five" -> 25, "one hundred" -> 100, "50%" -> 50. null if no number. */
    fun parseNumber(tokens: List<String>): Int? {
        var cur = 0
        var seen = false
        for (t in tokens) {
            val d = t.removeSuffix("%").toIntOrNull()
            if (d != null) return d
            val o = ONES[t]
            val tn = TENS[t]
            if (o != null) { cur += o; seen = true }
            else if (tn != null) { cur += tn; seen = true }
            else if (t == "hundred") { cur = (if (cur == 0) 1 else cur) * 100; seen = true }
        }
        return if (seen) cur else null
    }

    fun settingsPage(name: String): String? = when (name) {
        "wifi", "wifi settings" -> "wifi"
        "bluetooth", "bluetooth settings" -> "bluetooth"
        "settings", "setting", "phone settings" -> "main"
        "display settings" -> "display"
        "sound settings" -> "sound"
        "location settings" -> "location"
        "battery settings" -> "battery"
        "airplane mode", "airplane settings", "flight mode" -> "airplane"
        "app settings", "apps settings" -> "apps"
        "do not disturb", "dnd" -> "dnd"
        else -> null
    }

    /**
     * Whole-word command matching. Returns null when the sentence is not a simple local command,
     * in which case the caller may use the cloud (only if the user configured a key).
     */
    fun classify(text: String): Cmd? {
        val n = stripWake(norm(text))   // a custom wake word at the start is never part of the command
        if (n.isEmpty() || n.length > 120) return null
        val t = n.split(" ")
        val s = t.toSet()
        val size = t.size
        fun has(vararg w: String): Boolean = w.any { it in s }
        val core = t.filter { it !in FILLER }.joinToString(" ")
        val nav = t.filter { it !in FILLER && it !in NAVFILL }.joinToString(" ")
        val wantsSettings = has("settings", "setting")

        if (core in setOf("stop", "cancel", "chup", "quiet", "ruko", "be quiet", "shut up")) return Cmd("stop")

        // ---- NOVA's own pattern unlock (only works when the user switched it ON in the NOVA Pattern screen)
        if (core in setOf("unlock", "unlock phone", "phone unlock", "unlock karo", "phone unlock karo", "unlock the phone", "unlock screen")) return Cmd("unlock")

        if (core in setOf("check updates", "check update", "any updates", "any update", "update check", "updates", "kuch naya hai", "kuch naya")) {
            return Cmd("update_check")
        }

        // ---- quiet replies on / off (default on: replies are shown in the chat, not spoken)
        if (core in setOf("quiet mode on", "silent replies on", "voice replies off")) return Cmd("quiet_on")
        if (core in setOf("quiet mode off", "silent replies off", "voice replies on")) return Cmd("quiet_off")

        // ---- driving mode (read messages aloud, fixed quick replies). Checked before the free-text "type" rule.
        Driving.command(n, core)?.let { return it }

        // ---- screen control (needs Accessibility). Checked early because typed text may contain any word.
        Regex("^(?:(?:please|nova|hey)\\s+)*(?:type|likho|write)\\s+(.+)$").find(n)?.let {
            val txt = Control.cleanTyped(it.groupValues[1])
            if (txt.isNotEmpty()) return Cmd("type", txt)
        }
        if (has("scroll", "swipe")) {
            val d = Control.direction(t)
            if (d != null && size <= 5) return Cmd("scroll", d)
        }
        Regex("^(?:tap|click|press|dabao|dabana)(?:\\s+on)?\\s+(.+)$").find(core)?.let {
            val label = it.groupValues[1].removeSuffix(" button").trim()
            if (label.isNotEmpty() && label.length <= 40 && label.split(" ").size <= 5) return Cmd("tap", label)
        }
        Regex("^(.+?)\\s+(?:dabao|dabana)$").find(core)?.let {
            val label = it.groupValues[1].removeSuffix(" button").trim()
            if (label.isNotEmpty() && label.length <= 40 && label.split(" ").size <= 5) return Cmd("tap", label)
        }

        if (has("monitor", "monitoring")) {
            if (has("off", "stop", "band", "bandh", "disable")) return Cmd("monitor_off")
            if (size <= 4) return Cmd("monitor_on")
            return null
        }

        if (has("screen") && !has("brightness", "brightnes", "timeout", "lock", "rotate", "rotation", "record", "recording", "shot", "off", "band") && // FIXSCREEN2
            (has("analyze", "analyse", "analysis", "dekho", "check", "read", "summarize", "summary",
                "dekh", "dekhna", "dikh", "dikha", "dikhao", "dikhta", "padh", "padho", "padhna", "batao", "bata", "kya", "likha",
                "describe", "tell", "see") ||
                (has("what") && has("on")))
        ) return Cmd("analyze")

        if (!wantsSettings && size <= 6 && has("battery", "charging", "charge")) return Cmd("battery")

        val timeBlock = has("timer", "alarm", "zone", "set", "remind", "reminder", "countdown")
        if (!wantsSettings && size <= 5 && !timeBlock && (has("time", "samay") || n.contains("kitne baje"))) {
            return Cmd("time")
        }
        if (!wantsSettings && size <= 5 && has("date", "tarikh", "tareekh")) return Cmd("date")

        if (has("torch", "flashlight", "flashlite")) {
            if (has("off", "band", "bandh", "stop")) return Cmd("torch_off")
            if (has("on", "chalu", "chalao", "start")) return Cmd("torch_on")
            return null
        }

        if (has("volume", "awaaz", "awaz")) {
            if (has("mute", "silent")) return Cmd("volume", "mute")
            if (has("max", "maximum", "full")) return Cmd("volume", "max")
            if (has("down", "lower", "decrease", "kam", "reduce", "low")) return Cmd("volume", "down")
            if (has("up", "raise", "increase", "badha", "badhao", "higher", "high")) return Cmd("volume", "up")
            val num = parseNumber(t)
            if (num != null) return Cmd("volume", "set", num.coerceIn(0, 100))
            return null
        }

        if (has("brightness", "brightnes")) {
            val num = parseNumber(t)
            if (num != null) return Cmd("brightness_set", "", num.coerceIn(0, 100))
            if (has("max", "maximum", "full")) return Cmd("brightness_set", "", 100)
            if (has("min", "minimum", "lowest")) return Cmd("brightness_set", "", 5)
            if (has("up", "increase", "higher", "badha", "badhao", "more", "brighter")) return Cmd("brightness_step", "", 10)
            if (has("down", "decrease", "lower", "kam", "less", "reduce", "dim")) return Cmd("brightness_step", "", -10)
            return null
        }

        val mediaWords = setOf(
            "pause", "play", "resume", "continue", "music", "media", "song", "track", "video",
            "playback", "next", "previous", "last", "agla", "pichla", "gana", "it", "stop"
        )
        if (size <= 4 && t.all { it in mediaWords || it in FILLER }) {
            if (has("pause")) return Cmd("media", "pause")
            if (has("stop") && has("music", "media", "song", "playback")) return Cmd("media", "pause")
            if (has("next", "agla")) return Cmd("media", "next")
            if (has("previous", "pichla") || (has("last") && has("song", "track", "gana"))) return Cmd("media", "previous")
            if (has("resume", "continue") || (has("play") && has("music", "media", "song", "playback"))) {
                return Cmd("media", "play")
            }
        }

        when (nav) {
            "home", "home screen" -> return Cmd("global", "home")
            "back" -> return Cmd("global", "back")
            "recents", "recent", "recent apps", "recent app", "app switcher", "switch apps" -> return Cmd("global", "recents")
            "notifications", "notification", "notification panel", "notification shade" -> return Cmd("global", "notifications")
            "quick settings", "quick panel", "quick settings panel" -> return Cmd("global", "quick_settings")
            "lock", "lock phone", "lock screen", "phone lock", "screen lock" -> return Cmd("global", "lock")
        }

        // Android does not let an app flip Wi-Fi/Bluetooth; we open the settings page and say so.
        if (size <= 3 && has("wifi", "bluetooth") && has("on", "off", "chalu", "band", "bandh")) {
            return Cmd("settings", if (has("wifi")) "wifi" else "bluetooth", 2)
        }

        val name0: String? =
            Regex("^(?:open|launch|start|kholo|khol)\\s+(.+)$").find(core)?.let { it.groupValues[1] }
                ?: Regex("^(.+?)\\s+(?:kholo|khol)$").find(core)?.let { it.groupValues[1] }
                ?: core.takeIf { settingsPage(it) != null || it == "camera" }
        if (name0 != null) {
            val name = name0.removeSuffix(" app").trim()
            if (name.isEmpty() || name.length > 30 || name.split(" ").size > 4) return null
            if (name == "camera") return Cmd("camera")
            val page = settingsPage(name)
            if (page != null) return Cmd("settings", page)
            return Cmd("open_app", name)
        }
        return null
    }
}

