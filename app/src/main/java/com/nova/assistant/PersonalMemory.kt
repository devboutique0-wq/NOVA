package com.nova.assistant

/**
 * Personal memory (PART 2): short facts the owner told NOVA to remember ("yaad rakh mera naam Shiv hai").
 * Pure Kotlin (no Android classes) so every rule is unit tested. The store itself is PersonalMemoryStore.
 *
 * Privacy rules enforced here:
 *  - secrets are REFUSED, never stored: OTP / password / PIN / CVV, card, Aadhaar-PAN-passport style IDs, bank account, any long number
 *  - at most MAX_FACTS facts of MAX_FACT_CHARS characters, no duplicates, newest first
 *  - nothing here logs or uploads anything; facts reach a model only through relevant() (max 3) and factsBlock()
 * The four command kinds are NOT in Brain.SAFE_KINDS, so shortcuts, skills and the offline model can never trigger them.
 */
object PersonalMemory {
    const val MAX_FACTS = 100
    const val MAX_FACT_CHARS = 200
    const val MIN_FACT_CHARS = 3
    const val MAX_FOR_PROMPT = 3
    const val MAX_READ_BACK = 8

    const val K_SAVE = "mem_save"
    const val K_LIST = "mem_list"
    const val K_FORGET = "mem_forget"
    const val K_FORGET_ALL = "mem_forget_all"
    val KINDS: Set<String> = setOf(K_SAVE, K_LIST, K_FORGET, K_FORGET_ALL)

    fun isMemoryKind(kind: String): Boolean = kind in KINDS

    // ------------------------------------------------------------------ voice command parser
    // Input is Logic.norm() text with the wake word already removed (lower-case a-z0-9 words).
    // Each regex is a single-line raw string so tools/test_memory_contract.py can run the SAME expressions in Python.

    private val LEAD_RE = Regex("""^(?:(?:please|zara|nova|hey|ok|okay)\s+)+""")
    private val FORGET_ALL_RE = Regex("""^(?:(?:sab|sabhi|saari|sari|saara|sara|saare|sare)\s+(?:kuch\s+)?(?:yaad\s+)?(?:bhool|bhul)\s+ja(?:o|na)?|(?:bhool|bhul)\s+ja(?:o|na)?\s+(?:sab|sabhi|saari|sari|saara|sara)(?:\s+kuch)?|forget\s+(?:everything|all)(?:\s+about\s+me)?|delete\s+(?:all\s+)?(?:my\s+)?(?:memory|memories))$""")
    private val LIST_RE = Regex("""^(?:(?:mujhe|tumhe|tujhe|aapko)\s+)?(?:kya(?:\s+kya)?\s+yaad\s+hai|yaad\s+kya\s+hai)$|^what\s+(?:do|did)\s+(?:you|i)\s+(?:remember|tell\s+you)(?:\s+about\s+me)?$|^(?:read|show|tell)\s+(?:me\s+)?(?:my\s+)?(?:memory|memories)$|^meri\s+(?:yaadein|yaad)\s+(?:batao|sunao|padho)$""")
    private val FORGET_RE = Regex("""^(?:(?:bhool|bhul)\s+ja(?:o|na)?|forget(?:\s+about|\s+that)?|mat\s+yaad\s+rakh(?:o|na)?|yaad\s+se\s+hata\s+do)\s+(.+)$""")
    private val FORGET_POST_RE = Regex("""^(.+?)\s+(?:bhool|bhul)\s+ja(?:o)?$""")
    private val SAVE_RE = Regex("""^(?:(?:mujhe|mere\s+liye)\s+)?(?:yaad\s+(?:rakhiyega|rakhiye|rakhna|rakho|rakh|kar\s+lo|kar\s+le|karo|kar)|remember|note\s+(?:kar\s+lo|karo|kar|down)|save\s+this)\s+(.+)$""")
    private val SAVE_POST_RE = Regex("""^(.+?)\s+(?:yaad\s+(?:rakh|rakho|rakhna|rakhiye)|yaad\s+kar\s+lo|note\s+kar\s+lo)$""")
    private val ARG_LEAD_RE = Regex("""^(?:(?:ki|that|this|ye|yeh|ke)\s+)+""")

    private fun cleanArg(t: String): String = ARG_LEAD_RE.replace(t.trim(), "").trim()

    /** null = not a memory command. */
    fun parse(norm: String): Logic.Cmd? {
        var s = norm.trim()
        if (s.isEmpty() || s.length > 150) return null
        s = LEAD_RE.replace(s, "").trim()
        if (s.isEmpty()) return null
        if (FORGET_ALL_RE.matches(s)) return Logic.Cmd(K_FORGET_ALL)
        if (LIST_RE.matches(s)) return Logic.Cmd(K_LIST)
        val f1 = FORGET_RE.find(s)
        if (f1 != null) {
            val t = cleanArg(f1.groupValues[1])
            if (t.isNotEmpty()) return Logic.Cmd(K_FORGET, t)
        }
        val f2 = FORGET_POST_RE.find(s)
        if (f2 != null) {
            val t = cleanArg(f2.groupValues[1])
            if (t.isNotEmpty() && t !in PRONOUNS) return Logic.Cmd(K_FORGET, t)
        }
        val a1 = SAVE_RE.find(s)
        if (a1 != null) {
            val t = cleanArg(a1.groupValues[1])
            if (t.length >= MIN_FACT_CHARS) return Logic.Cmd(K_SAVE, t)
        }
        val a2 = SAVE_POST_RE.find(s)
        if (a2 != null) {
            val t = cleanArg(a2.groupValues[1])
            if (t.length >= MIN_FACT_CHARS) return Logic.Cmd(K_SAVE, t)
        }
        return null
    }

    private val PRONOUNS = setOf("wo", "woh", "vo", "ye", "yeh", "it", "that", "this", "sab", "kuch")

    // ------------------------------------------------------------------ refusal list

    private val CARD_RE = Regex("""\b(?:card\s+number|credit\s+card|debit\s+card|atm\s+card|cvv|cvc|expiry)\b""")
    private val ID_RE = Regex("""\b(?:aadhaar|aadhar|adhar|pan\s+card|pan\s+number|passport|voter\s+id|ssn|driving\s+licen[cs]e)\b""")
    private val BANK_RE = Regex("""\b(?:account\s+number|bank\s+account|khata\s+number|ifsc|netbanking|net\s+banking)\b""")
    private val SECRET_RE = Regex("""\b(?:otp|password|passwords|passcode|paswad|pasword|pin|secret\s+code|verification\s+code|one\s+time\s+password|security\s+code)\b""")
    private val DIGIT_RUN_RE = Regex("""\d(?:[\s-]?\d){5,}""")
    private val DIGIT_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "shunya", "ek", "do", "teen", "char", "paanch", "panch", "chhe", "che", "saat", "aath", "nau"
    )

    /** A run of 6+ digits (typed or spoken as words): card, Aadhaar, account, phone and similar numbers. */
    fun hasLongNumber(text: String): Boolean {
        val s = text.lowercase()
        if (DIGIT_RUN_RE.containsMatchIn(s)) return true
        var run = 0
        for (w in s.split(Regex("[^a-z0-9]+"))) {
            if (w in DIGIT_WORDS) {
                run++
                if (run >= 6) return true
            } else if (w.isNotEmpty()) {
                run = 0
            }
        }
        return false
    }

    /** null = fine to store, else a reason code: card / id / bank / secret / number. */
    fun refuseReason(fact: String): String? {
        val s = fact.lowercase()
        if (ID_RE.containsMatchIn(s)) return "id"          // before CARD_RE: "pan card number" is an ID, not a bank card
        if (CARD_RE.containsMatchIn(s)) return "card"
        if (BANK_RE.containsMatchIn(s)) return "bank"
        if (SECRET_RE.containsMatchIn(s)) return "secret"
        if (hasLongNumber(s)) return "number"
        return null
    }

    // ------------------------------------------------------------------ list operations (pure)

    fun cleanFact(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) sb.append(if (ch.isISOControl()) ' ' else ch)
        return sb.toString().replace("<|", " ").replace("|>", " ").replace(Regex("\\s+"), " ").trim()
    }

    /** status: saved / dup / short / long / refused. [list] is the new list (unchanged unless saved). Newest first. */
    class AddResult(val status: String, val reason: String, val list: List<String>)

    fun add(list: List<String>, raw: String): AddResult {
        val fact = cleanFact(raw)
        if (fact.length < MIN_FACT_CHARS) return AddResult("short", "", list)
        if (fact.length > MAX_FACT_CHARS) return AddResult("long", "", list)
        val why = refuseReason(fact)
        if (why != null) return AddResult("refused", why, list)
        val key = fact.lowercase()
        for (e in list) {
            if (e.lowercase() == key) return AddResult("dup", "", list)
        }
        val out = ArrayList<String>(list.size + 1)
        out.add(fact)
        out.addAll(list)
        while (out.size > MAX_FACTS) out.removeAt(out.size - 1)
        return AddResult("saved", "", out)
    }

    private val STOP = setOf(
        "ki", "ke", "ka", "ko", "hai", "hain", "he", "ho", "wo", "woh", "vo", "ye", "yeh", "that", "this", "the", "an",
        "my", "mera", "meri", "mere", "is", "are", "was", "to", "of", "and", "aur", "bhi", "about", "mat", "kuch", "wala",
        "wali", "kya", "me", "mein", "se", "par", "pe", "you", "tum", "mujhe", "tha", "thi", "what", "when", "where",
        "who", "which", "how", "kab", "kaun", "kaise", "kahan", "batao", "bolo", "tell", "please", "nova", "hey",
        "for", "with", "have", "has", "had", "ek", "do", "yaad", "remember"
    )

    fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        for (w in Logic.norm(s).split(" ")) {
            if (w.length >= 2 && w !in STOP && w !in out) out.add(w)
        }
        return out
    }

    private fun sameWord(a: String, b: String): Boolean =
        a == b || (a.length >= 4 && b.length >= 4 && a.substring(0, 4) == b.substring(0, 4))

    private fun overlap(q: List<String>, e: List<String>): Int {
        var n = 0
        for (w in q) {
            for (x in e) {
                if (sameWord(w, x)) {
                    n++
                    break
                }
            }
        }
        return n
    }

    /** removed = how many facts were deleted; ambiguous = several facts matched equally well, nothing was deleted. */
    class ForgetResult(val removed: Int, val ambiguous: Boolean, val list: List<String>)

    fun forget(list: List<String>, query: String): ForgetResult {
        val q = tokens(query)
        if (q.isEmpty() || list.isEmpty()) return ForgetResult(0, false, list)
        val scores = IntArray(list.size)
        var best = 0
        for (i in list.indices) {
            scores[i] = overlap(q, tokens(list[i]))
            if (scores[i] > best) best = scores[i]
        }
        if (best == 0 || best.toDouble() / q.size < 0.5) return ForgetResult(0, false, list)
        var ties = 0
        for (i in list.indices) if (scores[i] == best) ties++
        if (ties > 1) return ForgetResult(0, true, list)
        val out = ArrayList<String>(list.size)
        for (i in list.indices) if (scores[i] != best) out.add(list[i])
        return ForgetResult(1, false, out)
    }

    /** True when a fact looks like a contact number, an email or a secret: such facts are never sent to any model. */
    fun looksPrivateContact(fact: String): Boolean {
        val s = fact.lowercase()
        return hasLongNumber(s) || s.contains("@") || refuseReason(s) != null
    }

    /** At most [max] facts that share a real word with [question], best match first, never contact-like or secret-like facts. */
    fun relevant(facts: List<String>, question: String, max: Int = MAX_FOR_PROMPT): List<String> {
        val q = tokens(question).filter { it.length >= 3 }
        if (q.isEmpty() || facts.isEmpty()) return emptyList()
        val idx = ArrayList<Int>()
        val sc = IntArray(facts.size)
        for (i in facts.indices) {
            if (looksPrivateContact(facts[i])) continue
            sc[i] = overlap(q, tokens(facts[i]))
            if (sc[i] > 0) idx.add(i)
        }
        val sorted = idx.sortedWith(compareBy<Int>({ -sc[it] }, { it }))
        val out = ArrayList<String>()
        for (i in sorted) {
            if (out.size >= max) break
            out.add(facts[i])
        }
        return out
    }

    /** Text appended to a model's system prompt. "" when there is nothing relevant. Facts are the owner's own words. */
    fun factsBlock(facts: List<String>, en: Boolean): String {
        if (facts.isEmpty()) return ""
        val body = facts.take(MAX_FOR_PROMPT).joinToString("; ") { cleanFact(it).take(MAX_FACT_CHARS) }
        return if (en) {
            "\nThe user asked you to remember these facts about them: $body. Use them only if they help answer the question; never repeat them otherwise."
        } else {
            "\nUser ne tumse ye baatein yaad rakhne ko kaha tha: $body. Inhe sirf tab use karo jab sawaal ke jawab mein kaam aaye, warna mat dohrao."
        }
    }

    fun encode(list: List<String>): String = list.joinToString("\n")

    fun decode(s: String?): List<String> {
        if (s.isNullOrEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (line in s.split("\n")) {
            val t = cleanFact(line)
            if (t.isNotEmpty() && out.size < MAX_FACTS) out.add(t)
        }
        return out
    }

    // ------------------------------------------------------------------ spoken replies (never echo a refused secret)

    fun saveReply(r: AddResult, en: Boolean): String = when (r.status) {
        "saved" -> if (en) "Okay, I will remember that." else "Theek hai, yaad rakh liya."
        "dup" -> if (en) "I already remember that." else "Ye mujhe pehle se yaad hai."
        "short" -> if (en) "What should I remember? Say it in full, like: remember my name is Shiv." else "Kya yaad rakhna hai? Poora bolo, jaise: yaad rakh mera naam Shiv hai."
        "long" -> if (en) "That is too long. Please say it shorter." else "Ye bahut lamba hai, chhota karke bolo."
        else -> refuseReply(r.reason, en)
    }

    fun refuseReply(reason: String, en: Boolean): String = when (reason) {
        "card" -> if (en) "I do not store card details. That is not safe." else "Card ki jaankari main yaad nahi rakhta, ye safe nahi hai."
        "id" -> if (en) "I do not store ID numbers like Aadhaar, PAN or passport." else "Aadhaar, PAN ya passport jaisi ID main yaad nahi rakhta."
        "bank" -> if (en) "I do not store bank account details." else "Bank account ki jaankari main yaad nahi rakhta."
        "secret" -> if (en) "I do not store passwords, OTPs or PINs. That is not safe." else "Password, OTP ya PIN main yaad nahi rakhta, ye safe nahi hai."
        else -> if (en) "I do not store long numbers (card, account, Aadhaar or phone numbers)." else "Lamba number (card, account, Aadhaar ya phone number) main yaad nahi rakhta."
    }

    fun forgetReply(r: ForgetResult, en: Boolean): String = when {
        r.ambiguous -> if (en) "Several memories match. Please say it more exactly." else "Kai baatein match hui, thoda aur saaf bolo."
        r.removed > 0 -> if (en) "Okay, I forgot it." else "Theek hai, bhool gaya."
        else -> if (en) "I could not find that in what I remember." else "Ye mujhe yaad hi nahi mila."
    }

    fun listReply(list: List<String>, en: Boolean): String {
        if (list.isEmpty()) {
            return if (en) "I am not remembering anything yet. Say: remember, then the fact."
            else "Abhi mujhe kuch yaad nahi hai. Bolo: yaad rakh, phir baat."
        }
        val shown = list.take(MAX_READ_BACK)
        val body = shown.withIndex().joinToString(". ") { (i, f) -> "${i + 1}. $f" }
        val more = list.size - shown.size
        return if (en) {
            "I remember: $body" + (if (more > 0) ". And $more more." else ".")
        } else {
            "Mujhe ye yaad hai: $body" + (if (more > 0) ". Aur $more baatein." else ".")
        }
    }

    fun forgetAllPrompt(en: Boolean): String =
        if (en) "Forget everything I remember about you? Say yes or no." else "Main sab yaad rakhi baatein bhool jaun? Haan ya nahi bolo."
}
