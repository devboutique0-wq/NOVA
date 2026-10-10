package com.nova.assistant

/**
 * v16 WEBSITE BY VOICE: "nova ek restaurant ki website banao" / "make a website for my gym".
 * Pure Kotlin (no Android classes), so it runs in plain JVM unit tests. It only decides (a) is this a request to BUILD a
 * website and (b) what the site is about. It never sends anything anywhere.
 * Input is Roman text (Devanagari is converted by HindiRoman before this is called).
 * tools/test_site_contract.py reads the sets below straight from this file, so keep one set per line.
 */
object SiteIntent {
    class Parsed(val subject: String, val blocked: Boolean)

    private val VERBS = setOf("banao", "bana", "banado", "banade", "banana", "banaiye", "banaye", "bnao", "bna", "banwao", "banwa", "generate", "create", "build", "make", "design", "develop")
    private val NOUNS = setOf("website", "websites", "webpage", "webpages", "site", "portfolio", "landing")
    // Words that mean another feature owns the sentence (pictures, opening apps, agent jobs, sharing, deleting).
    private val NOT = setOf("kholo", "khol", "open", "photo", "foto", "image", "picture", "pic", "tasveer", "tasvir", "wallpaper", "chatgpt", "gemini", "gpt", "delete", "hatao", "bhejo", "send", "share", "whatsapp", "instagram", "call", "kaam", "agent", "chrome", "browser")
    private val FILLER = setOf("nova", "ek", "mujhe", "mere", "meri", "mera", "hamare", "ki", "ka", "ke", "ko", "se", "me", "mein", "par", "pe", "ye", "wo", "please", "plz", "pls", "zara", "kripya", "kar", "karo", "kardo", "do", "dena", "de", "dijiye", "chahiye", "chahie", "chaiye", "hai", "ho", "wala", "wali", "wale", "a", "an", "the", "of", "for", "my", "i", "want", "need", "can", "you", "could", "would", "free", "mujhko", "humko", "hume", "liye", "naya", "nayi", "new", "web", "page", "pages", "ek")
    private val PUBVERBS = setOf("publish", "publis", "deploy", "upload", "host")
    private val BLOCK = setOf("nude", "naked", "nsfw", "porn", "porno", "sex", "xxx", "nangi", "nanga", "erotic", "phishing", "hack", "hacking", "scam", "fake")

    fun tokens(s: String): List<String> {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            if ((ch in 'a'..'z') || (ch in '0'..'9')) sb.append(ch) else sb.append(' ')
        }
        val out = ArrayList<String>()
        for (w in sb.toString().split(' ')) if (w.isNotEmpty()) out.add(w)
        return out
    }

    /** True for "nova website publish karo" / "publish my site". The caller still asks a spoken yes before anything is sent. */
    fun isPublish(text: String): Boolean {
        val t = tokens(text)
        if (t.isEmpty() || t.size > 30) return false
        var pub = false
        var noun = false
        for (w in t) {
            if (w in NOT) return false
            if (w in PUBVERBS) pub = true
            if (w in NOUNS) noun = true
        }
        return pub && noun
    }

    /** null = this is not a "build a website" sentence. Otherwise the subject (may be empty) and whether it must be refused. */
    fun parse(text: String): Parsed? {
        val t = tokens(text)
        if (t.isEmpty() || t.size > 60) return null
        var verb = false
        var noun = false
        for (w in t) {
            if (w in NOT) return null
            if (w in VERBS) verb = true
            if (w in NOUNS) noun = true
        }
        if (!verb || !noun) return null
        var blocked = false
        val keep = ArrayList<String>()
        for (w in t) {
            if (w in BLOCK) blocked = true
            if (w in VERBS || w in NOUNS || w in FILLER) continue
            keep.add(w)
        }
        var subject = keep.joinToString(" ")
        if (subject.length < 3) subject = ""
        return Parsed(subject, blocked)
    }
}
