package com.nova.assistant

/**
 * v16 LEARN FROM THE INTERNET, pure rules (no Android classes, runs in plain JVM tests).
 * "nova seekho solar panel ke baare mein" -> parse() gives the topic. cleanNote() makes a short plain-text note out of
 * a web answer. score() decides whether a KEPT note answers a later question. A note is text only: it can never trigger
 * an action, and it is only used after the owner pressed RAKHO in Settings > KYA SEEKHA.
 * tools/test_learn_contract.py reads the sets below straight from this file, so keep one set per line.
 */
object LearnRules {
    class Parsed(val topic: String, val blocked: Boolean)

    private val VERBS = setOf("seekho", "seekh", "sikho", "sikh", "learn", "study", "padho")
    private val NOT = setOf("kholo", "khol", "open", "call", "delete", "hatao", "bhejo", "send", "share", "whatsapp", "photo", "image", "website", "site", "kaam", "agent", "banao", "bana", "make", "build")
    private val FILLER = setOf("nova", "ek", "ki", "ka", "ke", "ko", "se", "me", "mein", "par", "pe", "ye", "wo", "please", "plz", "pls", "zara", "kar", "karo", "kardo", "do", "dena", "de", "hai", "ho", "about", "the", "a", "an", "of", "for", "my", "i", "want", "you", "to", "can", "kuch", "naya", "internet", "net", "search", "baare", "bare", "baarein", "topic", "mujhe", "mere", "tum", "aap", "khud", "online", "web", "google")
    private val STOP = setOf("kya", "kaun", "kaise", "kitna", "kitni", "kyun", "what", "who", "how", "is", "are", "batao", "bata", "tell", "me", "explain", "samjhao", "hota", "hoti", "hain", "was", "does", "do")
    private val SENSITIVE = setOf("password", "otp", "pin", "cvv", "aadhaar", "aadhar", "pan", "passport", "bank", "card")

    fun tokens(s: String): List<String> {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            if ((ch in 'a'..'z') || (ch in '0'..'9')) sb.append(ch) else sb.append(' ')
        }
        val out = ArrayList<String>()
        for (w in sb.toString().split(' ')) if (w.isNotEmpty()) out.add(w)
        return out
    }

    /** null = not a "learn this" sentence. Otherwise the topic (may be empty) and whether it must be refused. */
    fun parse(text: String): Parsed? {
        val t = tokens(text)
        if (t.isEmpty() || t.size > 25) return null
        var verb = false
        for (w in t) {
            if (w in NOT) return null
            if (w in VERBS) verb = true
        }
        if (!verb) return null
        var blocked = false
        val keep = ArrayList<String>()
        for (w in t) {
            if (w in SENSITIVE) blocked = true
            if (w in VERBS || w in FILLER) continue
            keep.add(w)
        }
        var topic = keep.joinToString(" ")
        if (topic.length < 3) topic = ""
        return Parsed(topic, blocked)
    }

    /** Plain one-paragraph note, at most 600 chars, cut on a sentence end. null when nothing usable is left. */
    fun cleanNote(raw: String): String? {
        var t = raw
        t = t.replace(Regex("https?://\\S+"), " ")
        t = t.replace(Regex("\\[\\d+\\]"), " ")
        t = t.replace(Regex("[*#`_>|]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.length > 600) {
            val cut = t.substring(0, 600)
            val dot = cut.lastIndexOf('.')
            t = if (dot > 200) cut.substring(0, dot + 1) else cut.trim()
        }
        return if (t.length < 20) null else t
    }

    /** 0.0..1.0: how much of the note's topic words the spoken question covers. Needs at least one word of 3+ letters. */
    fun score(said: String, topic: String): Double {
        val q = HashSet<String>()
        for (w in tokens(said)) if (w !in FILLER && w !in STOP) q.add(w)
        val tw = ArrayList<String>()
        for (w in tokens(topic)) if (w !in FILLER && w !in STOP) tw.add(w)
        if (tw.isEmpty() || q.isEmpty()) return 0.0
        var hit = 0
        var longHit = false
        for (w in tw) if (w in q) { hit++; if (w.length >= 3) longHit = true }
        if (!longHit) return 0.0
        return hit.toDouble() / tw.size
    }

    const val MIN_SCORE = 0.6
}
