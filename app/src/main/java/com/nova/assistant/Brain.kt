package com.nova.assistant

/**
 * NOVA "Layer 1" brain: memory and shortcuts that run instantly on the phone (no model, no network).
 * Pure Kotlin (no Android classes) so every rule is unit-testable. Persistence is in BrainStore.kt.
 *
 * What it learns:
 *  - Skills: a trigger phrase -> one or more local command sentences (e.g. "good night" -> "volume mute", "torch off").
 *    A skill can only contain commands NOVA already understands (Logic.classify) plus "wait N". It can never contain
 *    a risky tap (send/pay/delete/...), a call or a message, so a skill can never bypass the spoken "yes".
 *  - Events: what was heard and whether a local command / skill / nothing handled it (last 200, local only).
 *  - Proposals: when an unknown phrase is repeatedly followed by a known command, NOVA offers to remember it
 *    as a shortcut. It only ever ASKS; the user's spoken "yes" is what saves it.
 */
data class Skill(
    val name: String,
    val triggers: List<String>,
    val steps: List<String>,
    val source: String          // "user", "learned" or "pack:<id>"
)

data class BrainEvent(val ts: Long, val heard: String, val kind: String, val cmd: String)

data class Proposal(val phrase: String, val command: String, val count: Int)

object Brain {
    const val MAX_EVENTS = 200
    const val MAX_STEPS = 8
    const val MAX_SKILLS = 200
    const val MAX_TRIGGERS = 8
    const val PAIR_WINDOW_MS = 90_000L
    const val MIN_PAIR_COUNT = 2
    const val NUDGE_GAP_MS = 6L * 60 * 60 * 1000       // at most one question every 6 hours
    const val FUZZY_MIN = 0.84

    /** Command kinds a skill step (or a learned shortcut) may use. No tap/type (they depend on the screen). */
    val SAFE_KINDS = setOf(
        "battery", "time", "date", "torch_on", "torch_off", "volume", "brightness_set", "brightness_step",
        "media", "global", "settings", "camera", "open_app", "scroll"
    )

    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
        "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred"
    )

    /** "wait 3" -> 3 seconds (1..10), else null. */
    fun waitSeconds(step: String): Int? {
        val m = Regex("^wait\\s+(\\d{1,2})$").find(step.trim().lowercase()) ?: return null
        val n = m.groupValues[1].toInt()
        return if (n in 1..10) n else null
    }

    /** Returns null when the skill is acceptable, otherwise a short reason. */
    fun validateSkill(s: Skill): String? {
        if (s.name.isBlank() || s.name.length > 40) return "bad name"
        if (s.triggers.isEmpty() || s.triggers.size > MAX_TRIGGERS) return "bad triggers"
        for (t in s.triggers) {
            val n = Logic.norm(t)
            if (n.length < 3 || n.length > 60) return "bad trigger"
            if (Logic.classify(n) != null) return "trigger would hide a built-in command"
        }
        if (s.steps.isEmpty() || s.steps.size > MAX_STEPS) return "bad steps"
        for (st in s.steps) {
            if (waitSeconds(st) != null) continue
            val c = Logic.classify(st) ?: return "step not understood: $st"
            if (c.kind !in SAFE_KINDS) return "step not allowed: ${c.kind}"
        }
        return null
    }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0.0 .. 1.0. Differing number words never match (so "volume fifty" cannot become "volume fifteen"). */
    fun similarity(x: String, y: String): Double {
        val a = Logic.norm(x)
        val b = Logic.norm(y)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val na = a.split(" ").filter { it in NUMBER_WORDS }
        val nb = b.split(" ").filter { it in NUMBER_WORDS }
        if (na != nb) return 0.0
        val longest = maxOf(a.length, b.length)
        return 1.0 - lev(a, b).toDouble() / longest
    }

    /** Exact trigger first, then a close match (speech recognizers mishear). null when nothing is close. */
    fun resolve(skills: Collection<Skill>, text: String): Skill? {
        val n = Logic.norm(text)
        if (n.length < 3) return null
        for (s in skills) if (s.triggers.any { Logic.norm(it) == n }) return s
        var best: Skill? = null
        var bestScore = 0.0
        for (s in skills) for (t in s.triggers) {
            val tn = Logic.norm(t)
            if (tn.length < 6 || n.length < 6) continue          // very short phrases only match exactly
            val sc = similarity(n, tn)
            if (sc > bestScore) { bestScore = sc; best = s }
        }
        return if (bestScore >= FUZZY_MIN) best else null
    }

    /** Keeps only the newest [MAX_EVENTS]. */
    fun record(events: MutableList<BrainEvent>, e: BrainEvent) {
        events.add(e)
        while (events.size > MAX_EVENTS) events.removeAt(0)
    }

    /**
     * "Unknown phrase, then within 90 s a local command" seen at least twice -> proposal.
     * [taken] = trigger phrases that already exist, [declined] = "phrase=>command" keys the user said no to.
     */
    fun proposals(events: List<BrainEvent>, taken: Set<String>, declined: Set<String>): List<Proposal> {
        val counts = LinkedHashMap<String, Int>()
        val firstCmd = HashMap<String, String>()
        for (i in 0 until events.size - 1) {
            val u = events[i]
            val k = events[i + 1]
            if (u.kind != "unknown" || k.kind != "local") continue
            if (k.ts - u.ts !in 0..PAIR_WINDOW_MS) continue
            val phrase = Logic.norm(u.heard)
            val command = Logic.norm(k.heard)
            if (phrase.length !in 3..60 || command.isEmpty()) continue
            if (k.cmd !in SAFE_KINDS) continue
            if (phrase in taken || key(phrase, command) in declined) continue
            if (Logic.classify(phrase) != null) continue
            val key = key(phrase, command)
            counts[key] = (counts[key] ?: 0) + 1
            firstCmd[key] = command
        }
        val out = ArrayList<Proposal>()
        for ((k, n) in counts) {
            if (n >= MIN_PAIR_COUNT) out.add(Proposal(k.substringBefore("=>"), firstCmd[k] ?: continue, n))
        }
        out.sortByDescending { it.count }
        return out
    }

    fun key(phrase: String, command: String): String = "$phrase=>$command"

    /** A learned shortcut skill built from an accepted proposal. */
    fun skillFrom(p: Proposal): Skill = Skill(
        name = "learned: " + p.phrase.take(30),
        triggers = listOf(p.phrase),
        steps = listOf(p.command),
        source = "learned"
    )

    fun canNudge(now: Long, lastNudgeAt: Long): Boolean = lastNudgeAt <= 0L || now - lastNudgeAt >= NUDGE_GAP_MS
}
