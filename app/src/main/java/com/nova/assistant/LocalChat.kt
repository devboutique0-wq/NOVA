package com.nova.assistant

/**
 * Rules for the on-device chat model (offline answers) and for choosing between cloud and local. Pure Kotlin, tested.
 * The model is untrusted text in, untrusted text out: it has no tools, cannot run commands, cannot approve anything.
 * Its answer is only SPOKEN / SHOWN. Actions still go through Logic.classify + the spoken "yes" gate.
 */
object LocalChat {
    const val MAX_INPUT = 300
    const val MAX_REPLY_CHARS = 420
    const val MAX_NEW_TOKENS = 120
    const val TIMEOUT_MS = 30000L

    private const val PERSONA =
        "You are NOVA, a friendly voice assistant on an Android phone. You are running OFFLINE on the phone: " +
            "you have no internet and no live information (no news, weather, prices, scores, today's events). " +
            "Reply in the same language style as the user (English, or Hindi written in Roman letters). " +
            "Keep it short: at most 3 short sentences, plain words, no lists, no markdown. " +
            "If you are not sure, say \"mujhe pakka nahi pata\". Never invent facts, names, numbers or links. " +
            "You are an AI, never claim to be human. For health, money or legal questions give only general information " +
            "and tell the user to ask a doctor or expert. Never give medicine doses."

    /** ChatML prompt (the format Qwen2.5 instruct models are trained on). The user's words are data. */
    fun buildPrompt(userText: String, facts: List<String> = emptyList()): String {
        val heard = userText.replace(Regex("[\\r\\n\\t]+"), " ").replace("<|", " ").replace("|>", " ").trim().take(MAX_INPUT)
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(PERSONA)
        if (facts.isNotEmpty()) {
            sb.append("\nThings the user told you to remember (use only if relevant): ")
            sb.append(facts.take(8).joinToString("; ") { it.replace("<|", " ").replace("|>", " ").take(80) })
        }
        sb.append("<|im_end|>\n<|im_start|>user\n").append(heard).append("<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
    }

    /** Cleans the raw model text. null = unusable (empty, echo of the template, endless repetition). */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        var s = raw
        val cut = s.indexOf("<|im_end|>")
        if (cut >= 0) s = s.substring(0, cut)
        s = s.replace("<|im_start|>", " ").replace("<|endoftext|>", " ")
        s = s.replace(Regex("^\\s*(assistant|nova)\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("[*_#`>]+"), "").replace(Regex("\\s+"), " ").trim()
        if (s.length < 2) return null
        val words = s.lowercase().split(" ")
        if (words.size >= 12 && words.toSet().size * 3 < words.size) return null   // "ha ha ha ha ..." loops
        if (s.length > MAX_REPLY_CHARS) {
            val cutAt = s.lastIndexOf('.', MAX_REPLY_CHARS)
            s = if (cutAt > 80) s.substring(0, cutAt + 1) else s.substring(0, MAX_REPLY_CHARS).trimEnd() + "..."
        }
        return s
    }

    private val LIVE = setOf(
        "news", "weather", "mausam", "score", "price", "rate", "stock", "sensex", "nifty", "bitcoin", "cricket", "match",
        "latest", "today", "aaj", "abhi", "live", "trending", "headlines", "khabar", "result", "election"
    )
    private val MED = setOf(
        "medicine", "dawa", "dawai", "tablet", "dose", "doctor", "fever", "bukhar", "pain", "dard", "symptom", "diagnosis",
        "infection", "cancer", "diabetes", "bp", "pressure", "pregnant", "injection"
    )
    private val LEGAL_MONEY = setOf("legal", "lawyer", "court", "sue", "loan", "invest", "investment", "tax", "insurance", "mutual", "shares")
    private val CRISIS = listOf(
        "suicide", "kill myself", "end my life", "want to die", "marna chahta", "marna chahti", "mar jaunga", "mar jaungi",
        "jeena nahi chahta", "jeena nahi chahti", "khud ko khatam"
    )

    private fun words(text: String): Set<String> = Logic.norm(text).split(" ").toSet()

    /** A fixed safe answer for things the small model must never improvise. null = ask the model. */
    fun preAnswer(text: String, en: Boolean): String? {
        val n = Logic.norm(text)
        if (CRISIS.any { n.contains(it) }) return if (en)
            "I am really sorry you feel this way. Please talk to someone you trust right now. In India you can call Tele-MANAS at 14416, or 112 in an emergency."
        else "Mujhe bahut afsos hai ki aap aisa mehsoos kar rahe ho. Abhi kisi apne se baat karo. India mein Tele-MANAS 14416 par call kar sakte ho, emergency mein 112."
        val w = words(text)
        if (w.any { it in LIVE }) return if (en)
            "That needs live information and I am offline right now. Please try again when the internet is on."
        else "Is ke liye live jaankari chahiye aur main abhi offline hoon. Internet chalu hone par dobara poochho."
        return null
    }

    /** Adds the safety line for health / money / legal answers. */
    fun guard(userText: String, reply: String, en: Boolean): String {
        val w = words(userText)
        return when {
            w.any { it in MED } -> reply + (if (en) " This is general information only, please ask a doctor or pharmacist." else " Ye sirf general jaankari hai, doctor ya pharmacist se zaroor poochho.")
            w.any { it in LEGAL_MONEY } -> reply + (if (en) " This is general information only, please ask an expert." else " Ye sirf general jaankari hai, kisi expert se poochho.")
            else -> reply
        }
    }

    val offlinePrefixEn = "Offline: "
    val offlinePrefixHi = "Offline jawab: "
}

/** Chooses the order of answer sources. Pure, so the fallback rules are unit tested. */
object AiRouter {
    enum class Step { LOCAL, CLOUD }

    private val SMALL_TALK = setOf(
        "hello", "hi", "hey", "namaste", "thanks", "thank", "shukriya", "dhanyavad", "bye", "goodbye", "kaise", "kaisa", "kaisi", "who", "tum", "aap", "joke", "chutkula", "sing", "gaana"
    )

    fun isSmallTalk(text: String): Boolean {
        val t = Logic.norm(text).split(" ").filter { it.isNotEmpty() }
        return t.isNotEmpty() && t.size <= 6 && t.any { it in SMALL_TALK }
    }

    /**
     * cloud first for real questions (better quality), local first only for small talk (saves API calls),
     * and each source is used only if it is configured / ready / not cooling down after a failure.
     */
    fun plan(cloudConfigured: Boolean, cloudCooling: Boolean, localReady: Boolean, text: String): List<Step> {
        val cloud = cloudConfigured && !cloudCooling
        val out = ArrayList<Step>()
        if (isSmallTalk(text) && localReady) {
            out.add(Step.LOCAL)
            if (cloud) out.add(Step.CLOUD)
        } else {
            if (cloud) out.add(Step.CLOUD)
            if (localReady) out.add(Step.LOCAL)
        }
        return out
    }

    /** How long to skip the cloud after a failure of this kind (Logic.failureKind). */
    fun cooldownMs(kind: String): Long = when (kind) {
        "quota" -> 120_000L
        "server" -> 30_000L
        "network" -> 15_000L
        "model" -> 60_000L
        else -> 0L
    }
}

/**
 * Runs the answer sources in the order AiRouter.plan chose. Pure (the sources are passed in), so every fallback rule
 * is unit tested. Rules: junk is never routed; a cloud failure puts the cloud on cooldown and the next source is tried;
 * if the cloud already ACTED (a tool ran) it is never followed by a local answer; a local answer is text only.
 */
object Routing {
    /** [ok] = a normal answer; [acted] = a phone action already ran; [failKind] = Logic.failureKind of a failure. */
    class CloudResult(val text: String, val ok: Boolean, val acted: Boolean = false, val failKind: String = "other")

    /** null = no source produced an answer (the caller shows the honest "not available offline" message). */
    fun run(
        steps: List<AiRouter.Step>,
        text: String,
        cloud: () -> CloudResult,
        local: () -> String?,
        setCooldown: (Long) -> Unit
    ): String? {
        if (Logic.isJunk(text)) return null
        var cloudFailText: String? = null
        for (step in steps) {
            when (step) {
                AiRouter.Step.CLOUD -> {
                    val r = cloud()
                    if (r.ok || r.acted) return r.text
                    val cd = AiRouter.cooldownMs(r.failKind)
                    if (cd > 0L) setCooldown(cd)
                    cloudFailText = r.text
                }
                AiRouter.Step.LOCAL -> {
                    val t = local()
                    if (t != null) return t
                }
            }
        }
        return cloudFailText
    }
}
