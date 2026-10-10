package com.nova.assistant

/**
 * Turns Hindi (Devanagari) text into the Roman (Hinglish) words Logic.classify, LocalSkills and KnowledgeMatcher know.
 * Step 1: a word table for command words and for words whose usual Roman spelling is not the plain letter-by-letter one.
 * Step 2 (PART 4): every word NOT in the table is transliterated by rule (letters, matras, conjuncts, simple schwa deletion),
 * so a Hindi question like "पानी कितने डिग्री पर उबलता है" becomes "pani kitne digri par ubalta hai" instead of being junk.
 * Pure Kotlin, no Android. It only changes WORDS: it never approves, taps or sends anything by itself.
 */
object HindiRoman {
    private val MAP: Map<String, String> = mapOf(
        "नोवा" to "nova",
        "खोलो" to "kholo", "खोलें" to "kholo", "खोलिए" to "kholo", "खोल" to "khol", "ओपन" to "open",
        "चालू" to "chalu", "चालु" to "chalu", "शुरू" to "chalu", "चलाओ" to "chalao",
        "बंद" to "band", "बन्द" to "band",
        "करो" to "karo", "कर" to "kar", "दो" to "do", "कीजिए" to "karo", "करें" to "karo",
        "टॉर्च" to "torch", "टार्च" to "torch", "टोर्च" to "torch", "फ्लैशलाइट" to "flashlight",
        "वॉल्यूम" to "volume", "वोल्यूम" to "volume", "वॉल्युम" to "volume",
        "आवाज़" to "awaaz", "आवाज" to "awaaz",
        "कम" to "kam", "घटाओ" to "kam",
        "बढ़ाओ" to "badhao", "बढाओ" to "badhao", "बढ़ा" to "badhao",
        "ज़्यादा" to "badhao", "ज्यादा" to "badhao", "तेज़" to "badhao", "तेज" to "badhao",
        "बैटरी" to "battery", "ब्राइटनेस" to "brightness", "चार्जिंग" to "charging",
        "इंस्टाग्राम" to "instagram", "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp",
        "वॉट्सएप" to "whatsapp", "यूट्यूब" to "youtube", "क्रोम" to "chrome", "कैमरा" to "camera",
        "गूगल" to "google", "मैप्स" to "maps", "टेलीग्राम" to "telegram", "फेसबुक" to "facebook",
        "सेटिंग" to "settings", "सेटिंग्स" to "settings",
        "वाईफाई" to "wifi", "वाई-फाई" to "wifi", "ब्लूटूथ" to "bluetooth",
        "होम" to "home", "बैक" to "back", "वापस" to "back", "रीसेंट" to "recent",
        "नोटिफिकेशन" to "notifications", "लॉक" to "lock",
        "गाना" to "gana", "अगला" to "agla", "पिछला" to "pichla",
        "रोको" to "ruko", "रुको" to "ruko", "चुप" to "chup",
        "समय" to "samay", "तारीख" to "tarikh", "कितने" to "kitne", "बजे" to "baje",
        "स्क्रीन" to "screen", "देखो" to "dekho",
        "ऑन" to "on", "ऑफ" to "off", "म्यूट" to "mute", "फुल" to "full", "मैक्स" to "max",
        "प्लीज" to "please", "प्ले" to "play", "पॉज" to "pause", "म्यूजिक" to "music",
        "संगीत" to "music", "नेक्स्ट" to "next", "सॉन्ग" to "song",
        // yes / no and answers (spelling must match Logic.parseAnswer's words)
        "हाँ" to "haan", "हां" to "haan", "हा" to "haan", "जी" to "ji", "ठीक" to "theek", "ओके" to "ok", "यस" to "yes",
        "नहीं" to "nahi", "नही" to "nahi", "ना" to "na", "मत" to "mat", "नो" to "no", "कैंसिल" to "cancel",
        // words whose Roman spelling keeps a double a
        "आज" to "aaj", "अभी" to "abhi", "आधा" to "aadha", "आठ" to "aath", "बाद" to "baad", "नाक" to "naak",
        "पाँच" to "paanch", "पांच" to "paanch", "रात" to "raat", "साँप" to "saanp", "सांप" to "saanp", "सात" to "saat",
        "शाम" to "shaam", "याद" to "yaad", "आप" to "aap", "काम" to "kaam", "कल" to "kal",
        // calls, messages, common verbs
        "कॉल" to "call", "फोन" to "phone", "मैसेज" to "message", "एसएमएस" to "sms", "भेजो" to "bhejo", "भेज" to "bhej",
        "लगाओ" to "lagao", "रखो" to "rakh", "रख" to "rakh", "भूल" to "bhool", "जा" to "ja", "बताओ" to "batao", "बता" to "bata",
        "क्या" to "kya", "है" to "hai", "हैं" to "hain", "में" to "mein", "मैं" to "main", "मुझे" to "mujhe", "मेरा" to "mera",
        "कौन" to "kaun", "कैसे" to "kaise", "क्यों" to "kyun", "कब" to "kab", "कहाँ" to "kahan", "कहां" to "kahan",
        "का" to "ka", "की" to "ki", "के" to "ke", "को" to "ko", "से" to "se", "पर" to "par", "और" to "aur", "या" to "ya",
        // numbers
        "एक" to "ek", "दो" to "do", "तीन" to "teen", "चार" to "char", "छह" to "chhe", "छः" to "chhe", "छे" to "chhe",
        "नौ" to "nau", "दस" to "das", "बीस" to "bees", "सौ" to "sau", "हज़ार" to "hazaar", "हजार" to "hazaar", "लाख" to "lakh",
        "अप" to "up", "डाउन" to "down", "सी" to "c", "डी" to "d", "विटामिन" to "vitamin", "माँ" to "maa", "मां" to "maa", "खून" to "khoon", "दूध" to "doodh",
        "आधे" to "aadha", "पौन" to "paun", "सवा" to "sava", "डेढ़" to "dedh", "प्रतिशत" to "percent", "फीसदी" to "percent"
    )

    /** MAP keys in the normal form (see normKey), built once. */
    private val KEYS: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for ((k, v) in MAP) m[normKey(k)] = v
        m
    }

    private const val NUKTA = '\u093C'
    private const val VIRAMA = '\u094D'

    fun hasDevanagari(s: String): Boolean {
        for (ch in s) if (ch in '\u0900'..'\u097F') return true
        return false
    }

    /** Precomposed nukta letters -> base + nukta; chandrabindu -> anusvara; zero-width marks removed; Devanagari digits -> 0-9. */
    private fun decompose(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (ch in s) {
            when (ch) {
                '\u0958' -> { sb.append('\u0915'); sb.append(NUKTA) }
                '\u0959' -> { sb.append('\u0916'); sb.append(NUKTA) }
                '\u095A' -> { sb.append('\u0917'); sb.append(NUKTA) }
                '\u095B' -> { sb.append('\u091C'); sb.append(NUKTA) }
                '\u095C' -> { sb.append('\u0921'); sb.append(NUKTA) }
                '\u095D' -> { sb.append('\u0922'); sb.append(NUKTA) }
                '\u095E' -> { sb.append('\u092B'); sb.append(NUKTA) }
                '\u095F' -> { sb.append('\u092F'); sb.append(NUKTA) }
                '\u0901' -> sb.append('\u0902')
                '\u200C', '\u200D' -> {}
                in '\u0966'..'\u096F' -> sb.append(('0' + (ch - '\u0966')))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Lookup form of a word: decomposed, without nukta. */
    internal fun normKey(w: String): String = decompose(w).replace(NUKTA.toString(), "")

    private val CONS = mapOf(
        '\u0915' to "k", '\u0916' to "kh", '\u0917' to "g", '\u0918' to "gh", '\u0919' to "n",
        '\u091A' to "ch", '\u091B' to "chh", '\u091C' to "j", '\u091D' to "jh", '\u091E' to "n",
        '\u091F' to "t", '\u0920' to "th", '\u0921' to "d", '\u0922' to "dh", '\u0923' to "n",
        '\u0924' to "t", '\u0925' to "th", '\u0926' to "d", '\u0927' to "dh", '\u0928' to "n",
        '\u092A' to "p", '\u092B' to "ph", '\u092C' to "b", '\u092D' to "bh", '\u092E' to "m",
        '\u092F' to "y", '\u0930' to "r", '\u0932' to "l", '\u0933' to "l", '\u0935' to "v",
        '\u0936' to "sh", '\u0937' to "sh", '\u0938' to "s", '\u0939' to "h"
    )
    /** Consonant + nukta. */
    private val CONS_NUKTA = mapOf(
        '\u0915' to "q", '\u0916' to "kh", '\u0917' to "g", '\u091C' to "z", '\u0921' to "r", '\u0922' to "rh",
        '\u092B' to "f", '\u092F' to "y"
    )
    private val IND_VOWEL = mapOf(
        '\u0905' to "a", '\u0906' to "a", '\u0907' to "i", '\u0908' to "i", '\u0909' to "u", '\u090A' to "u",
        '\u090B' to "ri", '\u090F' to "e", '\u0910' to "ai", '\u0913' to "o", '\u0914' to "au",
        '\u0904' to "a", '\u090D' to "e", '\u0911' to "o", '\u0949' to "o"
    )
    private val MATRA = mapOf(
        '\u093E' to "a", '\u093F' to "i", '\u0940' to "i", '\u0941' to "u", '\u0942' to "u", '\u0943' to "ri",
        '\u0947' to "e", '\u0948' to "ai", '\u094B' to "o", '\u094C' to "au", '\u0945' to "e", '\u0949' to "o"
    )

    private class Syl(var cons: String, var vowel: String?, var suffix: String = "")

    /** Rule transliteration of ONE Devanagari word. Characters that are not Devanagari are copied through. */
    internal fun translit(word: String): String {
        val w = decompose(word)
        val units = ArrayList<Syl>()
        val other = StringBuilder()          // non-Devanagari characters inside the word (kept in place, rare)
        var i = 0
        var openCluster = false              // the last unit ended with a virama: the next consonant joins it
        while (i < w.length) {
            val ch = w[i]
            val c = CONS[ch]
            if (c != null) {
                var r: String = c
                if (i + 1 < w.length && w[i + 1] == NUKTA) { r = CONS_NUKTA[ch] ?: c; i++ }
                if (openCluster && units.isNotEmpty()) {
                    val u = units[units.size - 1]
                    u.cons += r
                    u.vowel = null
                } else {
                    units.add(Syl(r, null))
                }
                openCluster = false
            } else if (ch == VIRAMA) {
                if (units.isNotEmpty()) { units[units.size - 1].vowel = ""; openCluster = true }
            } else if (MATRA.containsKey(ch) && units.isNotEmpty() && units[units.size - 1].vowel == null) {
                units[units.size - 1].vowel = MATRA[ch]
                openCluster = false
            } else if (IND_VOWEL.containsKey(ch)) {
                units.add(Syl("", IND_VOWEL[ch]))
                openCluster = false
            } else if (ch == '\u0902') {
                if (units.isNotEmpty()) units[units.size - 1].suffix += "n"
            } else if (ch == '\u0903') {
                if (units.isNotEmpty()) units[units.size - 1].suffix += "h"
            } else if (ch == NUKTA || ch == '\u0964' || ch == '\u0965') {
                // stray nukta / danda: ignore
            } else {
                units.add(Syl(ch.toString(), ""))
                openCluster = false
            }
            i++
        }
        // schwa deletion: a bare consonant (vowel == null) loses its "a" at the end of the word and, going right to left,
        // inside the word when its left neighbour and its right neighbour both still carry a vowel. The first unit keeps it.
        val n = units.size
        if (n > 1 && units[n - 1].vowel == null) units[n - 1].vowel = ""
        var k = n - 2
        while (k >= 1) {
            val u = units[k]
            if (u.vowel == null && u.suffix.isEmpty() && units[k - 1].vowel != "" && units[k + 1].vowel != "") u.vowel = ""
            k--
        }
        val sb = StringBuilder()
        for (u in units) {
            sb.append(u.cons)
            sb.append(u.vowel ?: "a")
            sb.append(u.suffix)
        }
        sb.append(other)
        return sb.toString()
    }

    fun toRoman(s: String): String {
        val tokens = s.split(Regex("[\\s,\u0964.?!]+")).filter { it.isNotBlank() }
        return tokens.joinToString(" ") { tok ->
            if (!hasDevanagari(tok)) tok
            else MAP[tok] ?: KEYS[normKey(tok)] ?: translit(tok)
        }
    }
}
