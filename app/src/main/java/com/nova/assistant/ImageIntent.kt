package com.nova.assistant

/**
 * v30 FREE IMAGE BY VOICE: "nova ek billi ki photo banao" / "make a picture of a cat on the moon".
 * Pure Kotlin (no Android classes), so it runs in plain JVM unit tests. It only decides (a) is this a request to MAKE a
 * picture and (b) what the picture should show. It never sends anything anywhere.
 * Input is Roman text (Devanagari is converted by HindiRoman before this is called).
 * tools/test_image_contract.py reads the sets below straight from this file, so keep one set / one map entry per line format.
 */
object ImageIntent {
    class Parsed(val subject: String, val blocked: Boolean)

    private val VERBS = setOf("banao", "bana", "banado", "banade", "banana", "banaiye", "banaye", "bnao", "bna", "banwao", "banwa", "generate", "create", "draw", "make", "paint")
    private val NOUNS = setOf("photo", "foto", "image", "images", "picture", "pic", "tasveer", "tasvir", "painting", "drawing", "wallpaper", "poster")
    // These words mean another feature owns the sentence (agent job, gallery, editing, sharing, camera).
    private val NOT = setOf("kholo", "khol", "open", "edit", "badlo", "badal", "change", "chatgpt", "gemini", "gpt", "gallery", "select", "chuno", "camera", "kheencho", "kheench", "click", "delete", "hatao", "bhejo", "send", "share", "whatsapp", "instagram", "call", "kaam", "agent")
    private val FILLER = setOf("nova", "ek", "mujhe", "mere", "meri", "mera", "hamare", "ki", "ka", "ke", "ko", "se", "me", "mein", "par", "pe", "ye", "wo", "please", "plz", "pls", "zara", "kripya", "kar", "karo", "kardo", "do", "dena", "de", "dijiye", "chahiye", "chahie", "chaiye", "hai", "ho", "wala", "wali", "wale", "a", "an", "the", "of", "for", "my", "i", "want", "need", "can", "you", "could", "would", "ai", "free", "mujhko", "humko", "hume")
    private val BLOCK = setOf("nude", "naked", "nsfw", "porn", "porno", "sex", "sexy", "xxx", "nangi", "nanga", "erotic", "boobs", "gore")
    private val HI = mapOf(
        "billi" to "cat", "kutta" to "dog", "kutte" to "dog", "sher" to "lion", "bagh" to "tiger", "hathi" to "elephant",
        "ghoda" to "horse", "gaay" to "cow", "chidiya" to "bird", "machhli" to "fish", "bandar" to "monkey", "mor" to "peacock",
        "titli" to "butterfly", "phool" to "flower", "gulab" to "rose", "ped" to "tree", "pahad" to "mountain", "nadi" to "river",
        "samundar" to "ocean", "samandar" to "ocean", "aasman" to "sky", "suraj" to "sun", "chand" to "moon", "taare" to "stars",
        "baadal" to "clouds", "barish" to "rain", "ghar" to "house", "mandir" to "temple", "masjid" to "mosque", "gaadi" to "car",
        "jahaz" to "ship", "ladki" to "girl", "ladka" to "boy", "bachcha" to "child", "aadmi" to "man", "aurat" to "woman",
        "raja" to "king", "rani" to "queen", "sundar" to "beautiful", "bada" to "big", "chhota" to "small", "lal" to "red",
        "neela" to "blue", "hara" to "green", "peela" to "yellow", "safed" to "white", "kala" to "black", "shahar" to "city",
        "raat" to "night", "subah" to "morning", "aur" to "and", "bagicha" to "garden", "baag" to "garden"
    )

    fun tokens(s: String): List<String> {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            if ((ch in 'a'..'z') || (ch in '0'..'9')) sb.append(ch) else sb.append(' ')
        }
        val out = ArrayList<String>()
        for (w in sb.toString().split(' ')) if (w.isNotEmpty()) out.add(w)
        return out
    }

    /** null = this is not a "make a picture" sentence. Otherwise the subject (may be empty) and whether it must be refused. */
    fun parse(text: String): Parsed? {
        val t = tokens(text)
        if (t.isEmpty() || t.size > 40) return null
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
            keep.add(HI[w] ?: w)
        }
        var subject = keep.joinToString(" ")
        if (subject.length < 3) subject = ""
        return Parsed(subject, blocked)
    }
}
