package com.nova.assistant

/**
 * v32 LOOSE LOCAL UNDERSTANDING.
 *
 * Logic.classify only accepts short, exact phrases ("lock phone"). People speak long and casual:
 * "mere mobile ko turant lock kar do". Understand.simplify turns such a sentence into the short phrase classify already
 * knows, when - and only when - EVERY word is either a filler word or belongs to ONE safe intent.
 * Anything else (a name, an app, a question, a negation, two different intents) gives null and the sentence goes on
 * to the AI (Gemini with phone tools), which understands free speech.
 *
 * Pure Kotlin (unit tested). It can only produce harmless, reversible commands: it never produces a tap, typing,
 * call, message, payment, unlock or anything that needs a spoken yes.
 */
object Understand {
    const val MAX_WORDS = 14

    private val NEGATE = setOf("mat", "nahi", "nahin", "nhi", "nai", "dont", "don", "not", "never", "without", "bina", "mt", "no")

    /** Words that carry no meaning for a command (polite words, "my", "now", "do it", the word phone / mobile). */
    private val FILL = setOf(
        "nova", "hey", "please", "plz", "pls", "kindly", "kripya", "zara", "zra", "thoda", "thodi", "yaar", "yar", "bhai", "bro", "dost", "boss",
        "mere", "mera", "meri", "mujhe", "mujhko", "mereko", "apna", "apni", "apne", "my", "me", "i", "we", "hum",
        "ko", "ka", "ki", "ke", "ye", "yeh", "is", "iss", "isko", "usko", "wo", "woh", "uss", "the", "a", "an", "it", "this", "that",
        "to", "toh", "hi", "bhi", "na", "ab", "abhi", "now", "se", "tak", "wali", "wala", "par", "pe", "per", "pr", "mein", "me", "mai",
        "turant", "tarant", "jaldi", "jldi", "fatafat", "foran", "immediately", "quickly", "fast", "asap", "right", "away", "just",
        "bas", "ek", "baar", "dobara", "fir", "phir", "again",
        "phone", "mobile", "mob", "fon", "cell", "handset", "device",
        "tum", "aap", "tu", "you", "can", "could", "would", "will", "sakte", "sakta", "sakti", "skte", "ho", "hu", "hoon", "hai", "hain", "h",
        "kar", "kr", "karo", "kro", "kardo", "krdo", "karde", "krde", "kariye", "kijiye", "kijiyega", "karna", "krna", "karein", "karen",
        "dena", "de", "do", "dijiye", "dedo", "dedena", "want", "need", "chahiye", "chahta", "chahti"
    )

    private val LOCK_WORDS = setOf("lock", "lok", "lauk", "lak", "laak", "loc")
    private val VOLUME_WORDS = setOf("volume", "awaaz", "awaz", "aawaz", "sound")
    private val BRIGHT_WORDS = setOf("brightness", "brightnes", "roshni", "roshani", "ujala")
    private val TORCH_WORDS = setOf("torch", "tourch", "torchlight", "flashlight", "flashlite", "flash")
    private val ON_WORDS = setOf("on", "chalu", "chalao", "chala", "chalaao", "jalao", "jala", "jalaao", "jalado", "jalade", "jalana", "start", "shuru", "enable")
    private val OFF_WORDS = setOf("off", "band", "bandh", "bujha", "bujhao", "bujhado", "bujhade", "bujhana", "stop", "disable", "khatam")
    private val UP_WORDS = setOf(
        "up", "badha", "badhao", "badhado", "badhade", "badhana", "badhaiye", "zyada", "jyada", "jyda", "tez", "tezz",
        "increase", "raise", "high", "higher", "loud", "louder", "bada", "bado", "more", "brighter"
    )
    private val DOWN_WORDS = setOf(
        "down", "kam", "kamm", "kum", "lower", "decrease", "reduce", "low", "dheere", "dheeri", "dheemi", "halki", "halka",
        "softer", "chhota", "chota", "ghatao", "ghata", "ghatado", "ghatana", "less", "dim"
    )
    private val MAX_WORDS_SET = setOf("max", "maximum", "full", "poora", "poori", "pura", "puri", "fullest")
    private val MIN_WORDS_SET = setOf("min", "minimum", "lowest")
    private val OPEN_WORDS = setOf("dikhao", "dikha", "dikhado", "show", "open", "kholo", "khol", "dekho", "launch", "jao", "jaao", "jana", "go", "chalo")
    private val MEDIA_NOUNS = setOf("gana", "gaana", "song", "music", "video", "playback", "media", "track")
    private val PAUSE_WORDS = setOf("pause", "band", "bandh", "rok", "roko", "rokdo", "rukwa", "stop", "ruko", "thehro", "thahro")
    private val PLAY_WORDS = setOf("play", "resume", "chalao", "chala", "chalu", "bajao", "baja", "shuru", "start", "continue")

    private class Intent(
        val canon: String,
        val any: Set<String>,
        val need: Set<String> = emptySet(),
        val allow: Set<String> = emptySet(),
        val numeric: Boolean = false
    ) {
        private val all: Set<String> = any + need + allow

        /** The canonical phrase when [rest] (the non-filler words) fits this intent exactly, else null. */
        fun match(rest: List<String>): String? {
            var hasAny = false
            var hasNeed = need.isEmpty()
            var num: Int? = null
            for (w in rest) {
                val d = percent(w)
                if (numeric && d != null) {
                    if (num != null) return null          // two numbers: not a clear command
                    num = d
                    continue
                }
                if (w !in all) return null
                if (w in any) hasAny = true
                if (w in need) hasNeed = true
            }
            if (!hasAny || !hasNeed) return null
            if (numeric) {
                val n = num ?: return null
                return canon.replace("{n}", n.toString())
            }
            return canon
        }
    }

    /** "50" or "50%" -> 50 (only 0..100), else null. */
    private fun percent(w: String): Int? {
        val d = w.removeSuffix("%")
        if (d.isEmpty() || d.length > 3 || !d.all { it in '0'..'9' }) return null
        val v = d.toIntOrNull() ?: return null
        return if (v in 0..100) v else null
    }

    private val INTENTS: List<Intent> = listOf(
        Intent("lock phone", any = LOCK_WORDS, allow = setOf("screen", "display", "device")),
        Intent("home", any = setOf("home"), allow = OPEN_WORDS + setOf("screen", "page")),
        Intent("back", any = setOf("back", "wapas", "peeche", "pichhe", "pichche"), allow = OPEN_WORDS + setOf("screen", "page")),
        Intent("recents", any = setOf("recents", "recent"), allow = OPEN_WORDS + setOf("apps", "app", "list")),
        Intent("notifications", any = setOf("notification", "notifications", "notif", "notifs"), allow = OPEN_WORDS + setOf("panel", "shade", "bar", "neeche", "list")),

        Intent("torch on", any = TORCH_WORDS, need = ON_WORDS, allow = setOf("light", "batti")),
        Intent("torch off", any = TORCH_WORDS, need = OFF_WORDS, allow = setOf("light", "batti")),

        Intent("volume up", any = VOLUME_WORDS, need = UP_WORDS, allow = setOf("level")),
        Intent("volume down", any = VOLUME_WORDS, need = DOWN_WORDS, allow = setOf("level")),
        Intent("volume max", any = VOLUME_WORDS, need = MAX_WORDS_SET, allow = setOf("level")),
        Intent("volume mute", any = VOLUME_WORDS + setOf("mute", "silent"), need = setOf("mute", "silent", "band", "bandh")),
        Intent("volume {n}", any = setOf("volume", "awaaz", "awaz", "aawaz"), allow = setOf("percent", "pratishat", "level", "set"), numeric = true),

        Intent("brightness up", any = BRIGHT_WORDS, need = UP_WORDS, allow = setOf("level")),
        Intent("brightness down", any = BRIGHT_WORDS, need = DOWN_WORDS, allow = setOf("level")),
        Intent("brightness max", any = BRIGHT_WORDS, need = MAX_WORDS_SET, allow = setOf("level")),
        Intent("brightness min", any = BRIGHT_WORDS, need = MIN_WORDS_SET, allow = setOf("level")),
        Intent("brightness {n}", any = BRIGHT_WORDS, allow = setOf("percent", "pratishat", "level", "set"), numeric = true),

        Intent(
            "battery", any = setOf("battery", "batri", "bettery", "charge", "charging"),
            allow = setOf(
                "kitni", "kitna", "kitne", "kya", "level", "percent", "status", "bata", "batao", "bataiye", "bataye", "batana",
                "check", "dekho", "dekh", "dikhao", "dikha", "much", "left", "bacha", "bachi", "how", "what", "is", "reh", "rahi", "raha"
            )
        ),
        Intent(
            "time", any = setOf("time", "samay", "baje", "waqt", "wakt"),
            allow = setOf("kitne", "kitna", "kya", "current", "what", "is", "bata", "batao", "bataiye", "bataye", "tell", "hua", "hui", "raha", "rha", "dekho")
        ),
        Intent(
            "date", any = setOf("date", "tarikh", "tareekh", "taarikh", "tarik"),
            allow = setOf("aaj", "kya", "kitni", "today", "what", "is", "current", "bata", "batao", "bataiye", "tell")
        ),

        Intent("pause music", any = MEDIA_NOUNS + setOf("pause"), need = PAUSE_WORDS),
        Intent("play music", any = MEDIA_NOUNS + setOf("play", "resume"), need = PLAY_WORDS),
        Intent("next song", any = setOf("next", "agla", "aglaa", "agli", "skip"), allow = MEDIA_NOUNS + setOf("badlo", "badal", "chalao", "bajao")),
        Intent("previous song", any = setOf("previous", "prev", "pichla", "pichhla", "pichli", "pichhli"), allow = MEDIA_NOUNS + setOf("chalao", "bajao")),

        Intent("camera", any = setOf("camera"), allow = setOf("kholo", "khol", "open", "start", "chalao", "chalu", "on", "launch")),
        Intent("wifi on", any = setOf("wifi"), need = ON_WORDS + OFF_WORDS + setOf("connect"), allow = setOf("settings")),
        Intent("bluetooth on", any = setOf("bluetooth"), need = ON_WORDS + OFF_WORDS + setOf("connect"), allow = setOf("settings"))
    )

    /**
     * The short phrase for a long / casual sentence, or null when the sentence is not clearly ONE safe intent.
     * The result is meant to be fed to Logic.classify (see [classify]).
     */
    fun simplify(text: String): String? {
        val n = Logic.stripWake(Logic.norm(text))
        if (n.isEmpty() || n.length > 160) return null
        val words = n.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_WORDS) return null
        if (words.any { it in NEGATE }) return null
        val rest = words.filter { it !in FILL }
        if (rest.isEmpty()) return null
        var hit: String? = null
        for (i in INTENTS) {
            val c = i.match(rest) ?: continue
            if (hit != null && hit != c) return null     // two different intents in one sentence: let the AI decide
            hit = c
        }
        return hit
    }

    /** Strict Logic.classify first (nothing that worked before changes), then the loose reading. */
    fun classify(text: String): Logic.Cmd? {
        val strict = Logic.classify(text)
        // "mera camera kholo": the strict parser reads "mera camera" as the NAME of an app (open_app). When the whole sentence is
        // really one known safe intent, that reading is wrong, so the loose reading wins over a guessed app name.
        if (strict != null && strict.kind != "open_app") return strict
        val s = simplify(text)
        if (s == null) {
            // "front camera se video kholo": the strict parser would guess an app called "front camera se video". When camera comes with
            // video / front / selfie words it is a camera request with options, so give no local answer and let the AI choose the tool args.
            if (strict != null && strict.kind == "open_app") {
                val w = strict.arg.split(" ").filter { it.isNotEmpty() }
                if ("camera" in w && w.any { it in CAMERA_OPTION_WORDS }) return null
            }
            return strict
        }
        return Logic.classify(s) ?: strict
    }

    private val CAMERA_OPTION_WORDS = setOf("video", "vid", "videos", "front", "selfie", "record", "recording", "aage", "photo")
}
