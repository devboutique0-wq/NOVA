package com.nova.assistant

/**
 * Knowledge pack matcher (PART 3). Pure Kotlin, no Android, no network, no model.
 * Question in -> the best matching entry of the bundled knowledge pack (assets/knowledge.json) or null.
 * null is the honest "I do not know this offline": the caller then goes on to Gemini / free chain / offline model.
 * It NEVER guesses: below THRESHOLD, on a tie between two different entries, for a "live" question (today, price, news ...)
 * or for a very long sentence the answer is null. An answer is TEXT ONLY: nothing here can act on the phone.
 *
 * The data tables below (STOP, CANON, LIVE) and the constants are written in a plain literal form on purpose:
 * tools/test_knowledge_contract.py reads this file, runs the SAME tables through a Python port of the scoring and checks
 * 100+ question cases against the real assets/knowledge.json.
 */
object KnowledgeMatcher {
    const val MAX_TEXT_CHARS = 160
    const val MAX_QUERY_TOKENS = 14
    const val THRESHOLD = 60        // dice score in percent: 200 * matched / (user tokens + entry tokens)
    const val TIE_MARGIN = 5        // a different entry within this many points of the best one = ambiguous = null
    const val MIN_OVERLAP = 2       // at least two shared content tokens (one is allowed only for a one-concept question)
    const val FUZZY_MIN_LEN = 6     // one typo is forgiven only in words of this length or more
    const val MAX_ENTRIES = 1000

    /** One-letter words that still carry a topic ("vitamin c", "vitamin d"). Every other single letter is dropped. */
    val KEEP1 = setOf("c", "d")

    /** Filler and question words. They carry no topic, so they are dropped on both sides. */
    val STOP = setOf(
        "nova", "hey", "please", "plz", "zara", "batao", "bata", "btao", "bolo", "bol", "tell", "me", "us", "explain", "define",
        "what", "whats", "which", "who", "whom", "when", "where", "why", "how", "is", "are", "was", "were", "be", "been", "am",
        "the", "a", "an", "of", "in", "on", "at", "to", "for", "and", "or", "do", "does", "did", "can", "could", "should", "will",
        "i", "you", "it", "this", "that", "these", "those", "about", "tha", "thi", "the", "he", "ho", "hota", "hoti", "hote",
        "hai", "hain", "h", "hu", "hun", "kya", "kaun", "kon", "kis", "kisne", "kisko", "kaise", "kese", "kyu", "kyun", "kyon",
        "kab", "kahan", "kaha", "kitna", "kitne", "kitni", "ka", "ki", "ke", "ko", "se", "me", "mein", "mai", "par", "pe", "ne",
        "mujhe", "mera", "meri", "mere", "hum", "humko", "apna", "apne", "aap", "tum", "yeh", "ye", "woh", "wo", "us", "iska",
        "iski", "uska", "uski", "matlab", "meaning", "mean", "means", "samjhao", "samjha", "jaankari", "information", "info",
        "bhi", "to", "toh", "na", "yaar", "sir", "ji", "kuch", "koi", "kare", "karte", "karta", "karti", "karen", "karein",
        "chahiye", "chaiye", "sakta", "sakte", "sakti", "jata", "jati", "jate", "jaata", "jaati", "jaate", "gaya", "gayi",
        "wala", "wali", "wale", "si", "sa", "sabse", "ek", "one", "some", "any", "my", "your", "its", "with", "by", "from", "as", "so", "than",
        "get", "gets", "have", "has", "had", "make", "makes", "need", "needs", "must", "give", "gives", "use", "used", "using"
    )

    /** Hinglish spelling variants and a few English/Hindi synonyms -> one canonical token. */
    val CANON: Map<String, String> = mapOf(
        "paani" to "pani", "pani" to "pani", "water" to "pani", "panee" to "pani",
        "suraj" to "suraj", "sooraj" to "suraj", "surya" to "suraj", "sun" to "suraj",
        "chand" to "chand", "chaand" to "chand", "moon" to "chand",
        "dharti" to "prithvi", "earth" to "prithvi", "prithvi" to "prithvi", "zameen" to "prithvi",
        "aasman" to "aasman", "asman" to "aasman", "aakash" to "aasman", "sky" to "aasman",
        "tara" to "tara", "taara" to "tara", "star" to "tara", "sitara" to "tara",
        "hawa" to "hawa", "havaa" to "hawa", "air" to "hawa", "vayu" to "hawa",
        "aag" to "aag", "agni" to "aag", "fire" to "aag",
        "baadal" to "badal", "badal" to "badal", "cloud" to "badal",
        "barish" to "barish", "baarish" to "barish", "varsha" to "barish", "rain" to "barish",
        "bijli" to "bijli", "electricity" to "bijli",
        "dil" to "dil", "heart" to "dil",
        "khoon" to "khoon", "blood" to "khoon", "lahu" to "khoon",
        "haddi" to "haddi", "haddiyan" to "haddi", "bone" to "haddi",
        "dimag" to "dimag", "dimaag" to "dimag", "brain" to "dimag",
        "aankh" to "aankh", "aankhein" to "aankh", "aankhen" to "aankh", "ankh" to "aankh", "eye" to "aankh", "eyes" to "aankh",
        "daant" to "daant", "dant" to "daant", "teeth" to "daant", "tooth" to "daant",
        "bukhar" to "bukhar", "bukhaar" to "bukhar", "fever" to "bukhar",
        "khansi" to "khansi", "cough" to "khansi",
        "sardi" to "sardi", "zukam" to "sardi",
        "dard" to "dard", "pain" to "dard", "ache" to "dard",
        "neend" to "neend", "nind" to "neend", "sleep" to "neend",
        "khana" to "khana", "food" to "khana", "bhojan" to "khana",
        "nashta" to "nashta", "breakfast" to "nashta",
        "sabzi" to "sabzi", "sabji" to "sabzi", "vegetable" to "sabzi", "vegetables" to "sabzi",
        "phal" to "phal", "fruit" to "phal", "fruits" to "phal",
        "doodh" to "doodh", "dudh" to "doodh", "milk" to "doodh",
        "chai" to "chai", "tea" to "chai",
        "namak" to "namak", "salt" to "namak",
        "cheeni" to "cheeni", "chini" to "cheeni", "sugar" to "cheeni", "shakkar" to "cheeni",
        "chawal" to "chawal", "rice" to "chawal",
        "daal" to "dal", "dal" to "dal", "lentil" to "dal", "lentils" to "dal",
        "roti" to "roti", "chapati" to "roti", "chapatti" to "roti",
        "ghar" to "ghar", "home" to "ghar", "house" to "ghar",
        "paisa" to "paisa", "paise" to "paisa", "money" to "paisa", "rupaya" to "paisa", "rupee" to "paisa", "rupees" to "paisa",
        "desh" to "desh", "country" to "desh",
        "shehar" to "shehar", "sheher" to "shehar", "city" to "shehar",
        "rajya" to "rajya", "state" to "rajya", "pradesh" to "rajya",
        "rajdhani" to "rajdhani", "capital" to "rajdhani",
        "nadi" to "nadi", "river" to "nadi",
        "pahad" to "pahad", "parvat" to "pahad", "mountain" to "pahad",
        "samundar" to "samudra", "samudra" to "samudra", "sagar" to "samudra", "ocean" to "samudra", "sea" to "samudra",
        "bharat" to "india", "hindustan" to "india", "india" to "india",
        "pradhanmantri" to "pm", "pm" to "pm",
        "ghanta" to "ghanta", "ghante" to "ghanta", "hour" to "ghanta", "hours" to "ghanta",
        "minute" to "minute", "mint" to "minute", "minutes" to "minute",
        "second" to "second", "seconds" to "second", "sec" to "second",
        "saal" to "saal", "sal" to "saal", "year" to "saal", "years" to "saal", "varsh" to "saal",
        "mahina" to "mahina", "mahine" to "mahina", "month" to "mahina", "months" to "mahina",
        "hafta" to "hafta", "hafte" to "hafta", "week" to "hafta", "weeks" to "hafta",
        "din" to "din", "day" to "din", "days" to "din",
        "raat" to "raat", "night" to "raat",
        "kilo" to "kg", "kilogram" to "kg", "kilograms" to "kg", "kg" to "kg",
        "meter" to "meter", "metre" to "meter", "metres" to "meter", "meters" to "meter",
        "degree" to "degree", "degrees" to "degree", "digri" to "degree", "celsius" to "degree",
        "chalu" to "chalu", "shuru" to "chalu", "start" to "chalu", "on" to "chalu", "enable" to "chalu",
        "band" to "band", "bandh" to "band", "off" to "band", "stop" to "band", "disable" to "band",
        "awaaz" to "volume", "awaz" to "volume", "avaz" to "volume", "volume" to "volume", "sound" to "volume",
        "mobile" to "phone", "phone" to "phone", "fone" to "phone", "smartphone" to "phone", "cellphone" to "phone",
        "battery" to "battery", "baitari" to "battery", "charge" to "charge", "charging" to "charge", "charger" to "charge",
        "wifi" to "wifi", "internet" to "internet", "net" to "internet",
        "torch" to "torch", "flashlight" to "torch",
        "alarm" to "alarm", "timer" to "timer",
        "yaad" to "yaad", "yad" to "yaad", "remember" to "yaad", "memory" to "yaad",
        "bhool" to "bhool", "forget" to "bhool",
        "sikhao" to "sikha", "seekh" to "sikha", "learn" to "sikha",
        "banta" to "bana", "banti" to "bana", "bante" to "bana", "banate" to "bana", "banata" to "bana", "bana" to "bana", "banaye" to "bana",
        "banaya" to "bana", "banaiye" to "bana", "banaen" to "bana", "bnata" to "bana", "bnta" to "bana", "bnti" to "bana", "make" to "bana",
        "peena" to "pina", "pina" to "pina", "pine" to "pina", "peeta" to "pina", "pita" to "pina", "drink" to "pina",
        "khaana" to "khana", "khaye" to "khana", "khaen" to "khana", "eat" to "khana", "khate" to "khana", "khata" to "khana",
        "der" to "der", "late" to "der", "time" to "samay", "samay" to "samay", "waqt" to "samay", "vakt" to "samay",
        "ubalta" to "ubal", "ubalna" to "ubal", "ubalne" to "ubal", "ubal" to "ubal", "boil" to "ubal", "boils" to "ubal", "boiling" to "ubal", "ubalega" to "ubal",
        "jamta" to "jam", "jam" to "jam", "freeze" to "jam", "freezes" to "jam", "freezing" to "jam", "jamna" to "jam",
        "thanda" to "thanda", "thandi" to "thanda", "cool" to "thanda", "cold" to "thanda",
        "garam" to "garam", "hot" to "garam", "warm" to "garam",
        "ped" to "ped", "tree" to "ped", "pedh" to "ped", "paudha" to "paudha", "plant" to "paudha", "paudhe" to "paudha",
        "janwar" to "janwar", "jaanwar" to "janwar", "animal" to "janwar", "pashu" to "janwar",
        "pakshi" to "pakshi", "bird" to "pakshi", "chidiya" to "pakshi", "chidiyan" to "pakshi",
        "machhli" to "machhli", "machli" to "machhli", "fish" to "machhli",
        "saanp" to "saanp", "snake" to "saanp", "sanp" to "saanp",
        "kutta" to "kutta", "dog" to "kutta",
        "chot" to "chot", "injury" to "chot", "hurt" to "chot",
        "jalna" to "jalna", "jala" to "jalna", "burn" to "jalna", "burnt" to "jalna", "jalne" to "jalna",
        "bleeding" to "khoon", "nakseer" to "nakseer", "nosebleed" to "nakseer",
        "bachche" to "bachcha", "bachcha" to "bachcha", "baccha" to "bachcha", "bacche" to "bachcha", "child" to "bachcha", "children" to "bachcha", "kids" to "bachcha", "kid" to "bachcha",
        "buzurg" to "buzurg", "elderly" to "buzurg", "senior" to "buzurg",
        "doctor" to "doctor", "dr" to "doctor", "daktar" to "doctor", "vaid" to "doctor",
        "dawai" to "dawai", "dawa" to "dawai", "davai" to "dawai", "medicine" to "dawai", "tablet" to "dawai", "goli" to "dawai",
        "hospital" to "hospital", "aspatal" to "hospital",
        "exercise" to "vyayam", "vyayam" to "vyayam", "vyayaam" to "vyayam", "workout" to "vyayam", "kasrat" to "vyayam",
        "motapa" to "motapa", "obesity" to "motapa", "weight" to "vajan", "vajan" to "vajan", "wazan" to "vajan"
    )

    /** A question about something that changes (today, now, price, news, score ...) is never answered from a static pack. */
    val LIVE = setOf(
        "aaj", "abhi", "today", "tonight", "now", "current", "currently", "latest", "recent", "recently", "news", "khabar",
        "price", "daam", "bhav", "dollar", "usd", "gold", "silver", "crypto", "score", "live", "tomorrow", "yesterday", "stock", "sensex", "nifty", "bitcoin",
        "petrol", "diesel", "election", "results", "result", "match", "ipl"
    )

    class Entry(
        val id: String,
        val questions: List<String>,
        val hi: String,
        val en: String,
        val tags: List<String>
    ) {
        /** Content tokens of every question variant, computed once. */
        val tokens: List<List<String>> = questions.map { contentTokens(it) }.filter { it.isNotEmpty() }
    }

    class Match(val entry: Entry, val score: Int)

    class Query(val tokens: List<String>, val rawCount: Int, val live: Boolean)

    // ------------------------------------------------------------------ tokens

    private fun canon(w: String): String {
        CANON[w]?.let { return it }
        if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            val b = w.substring(0, w.length - 1)
            return CANON[b] ?: b
        }
        return w
    }

    /** Lower-case a-z0-9 words, stop words dropped, spelling variants unified, plural "s" removed, no duplicates. */
    fun contentTokens(text: String): List<String> {
        val s = text.lowercase().replace("[unk]", " ").replace(Regex("[^a-z0-9]"), " ")
        val out = ArrayList<String>()
        for (w in s.split(" ")) {
            if (w.isEmpty() || w in STOP) continue
            val c = canon(w)
            if (c in STOP || c.isEmpty()) continue
            if (c.length < 2 && !c[0].isDigit() && c !in KEEP1) continue
            if (c !in out) out.add(c)
        }
        return out
    }

    /** null = too long / too short / empty: not a knowledge question. */
    fun prepare(text: String): Query? {
        if (text.isBlank() || text.length > MAX_TEXT_CHARS) return null
        val raw = text.lowercase().replace("[unk]", " ").replace(Regex("[^a-z0-9]"), " ").split(" ").filter { it.isNotEmpty() }
        if (raw.isEmpty() || raw.size > MAX_QUERY_TOKENS) return null
        val live = raw.any { it in LIVE }
        return Query(contentTokens(text), raw.size, live)
    }

    // ------------------------------------------------------------------ scoring

    /** true when a and b differ by at most one inserted, deleted or replaced letter. */
    internal fun within1(a: String, b: String): Boolean {
        if (a == b) return true
        val d = a.length - b.length
        if (d > 1 || d < -1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > 1) return false
                if (a.length > b.length) i++
                else if (b.length > a.length) j++
                else {
                    i++
                    j++
                }
            }
        }
        edits += (a.length - i) + (b.length - j)
        return edits <= 1
    }

    internal fun tokEq(a: String, b: String): Boolean =
        a == b || (a.length >= FUZZY_MIN_LEN && b.length >= FUZZY_MIN_LEN && within1(a, b))

    internal fun overlap(u: List<String>, q: List<String>): Int {
        val used = BooleanArray(q.size)
        var n = 0
        for (a in u) {
            for (i in q.indices) {
                if (!used[i] && tokEq(a, q[i])) {
                    used[i] = true
                    n++
                    break
                }
            }
        }
        return n
    }

    /** 0 = no match. Otherwise 0..100 (integer maths only, so Kotlin and the Python port agree exactly). */
    internal fun score(u: List<String>, q: List<String>, rawCount: Int): Int {
        if (u.isEmpty() || q.isEmpty()) return 0
        val n = overlap(u, q)
        val oneConcept = n == 1 && u.size == 1 && q.size <= 2 && rawCount >= 2
        if (n < MIN_OVERLAP && !oneConcept) return 0
        return 200 * n / (u.size + q.size)
    }

    fun entryScore(e: Entry, q: Query): Int {
        var best = 0
        for (t in e.tokens) {
            val s = score(q.tokens, t, q.rawCount)
            if (s > best) best = s
        }
        return best
    }

    /** Best entry, or null (no match, below THRESHOLD, live question, or a tie between two different entries). */
    fun match(entries: List<Entry>, text: String): Match? {
        val q = prepare(text) ?: return null
        if (q.live || q.tokens.isEmpty()) return null
        var best: Entry? = null
        var bestScore = 0
        var second = 0
        for (e in entries) {
            val s = entryScore(e, q)
            if (s > bestScore) {
                second = bestScore
                bestScore = s
                best = e
            } else if (s > second) {
                second = s
            }
        }
        val b = best ?: return null
        if (bestScore < THRESHOLD) return null
        if (second >= THRESHOLD && bestScore - second < TIE_MARGIN) return null
        return Match(b, bestScore)
    }

    fun reply(m: Match, en: Boolean): String = if (en) m.entry.en else m.entry.hi

    // ------------------------------------------------------------------ pack file

    private val ID_RE = Regex("^[a-z0-9][a-z0-9-]{2,47}$")

    /** Reads assets/knowledge.json text. A bad entry is skipped; a bad file gives an empty list. Never throws. */
    fun parse(json: String): List<Entry> {
        val root = MiniJson.parse(json)
        val arr = MiniJson.field(root, "entries") as? List<*> ?: return emptyList()
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (o in arr) {
            if (out.size >= MAX_ENTRIES) break
            val id = MiniJson.field(o, "id") as? String ?: continue
            val hi = MiniJson.field(o, "hi") as? String ?: continue
            val en = MiniJson.field(o, "en") as? String ?: continue
            val qs = (MiniJson.field(o, "q") as? List<*>)?.filterIsInstance<String>() ?: continue
            val tags = (MiniJson.field(o, "tags") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            if (!ID_RE.matches(id) || id in seen) continue
            if (hi.isBlank() || en.isBlank() || hi.length > 400 || en.length > 400) continue
            val good = qs.filter { it.length in 3..90 }
            if (good.isEmpty() || good.size > 30) continue
            seen.add(id)
            out.add(Entry(id, good, hi, en, tags))
        }
        return out
    }
}
