package com.nova.assistant

import java.time.LocalTime

/**
 * Offline timer / reminder / alarm understanding. Pure Kotlin (no Android), so it is unit tested.
 * It only turns a sentence into a Logic.Cmd. The phone work is done by the system Clock app through
 * AlarmClock intents (NovaService.timerTool / alarmTool), so the alarm really rings even if NOVA is asleep.
 *
 *  - "timer"     : arg = label, num = seconds
 *  - "alarm"     : arg = label, num = minute of day (0..1439), the time was unambiguous (am/pm said, or 13..24)
 *  - "alarm_any" : arg = label, num = (hour % 12) * 60 + minute; no am/pm said, so the NEXT such time is used (resolveAny)
 * A reminder ("20 minute baad chai yaad dilao") is a timer whose label is the thing to remember.
 * Cancelling an alarm is not supported (Android gives no safe way), so such sentences return null.
 */
object Timers {
    private val UNIT_SEC = mapOf(
        "second" to 1, "seconds" to 1, "sec" to 1, "secs" to 1, "sekand" to 1,
        "minute" to 60, "minutes" to 60, "min" to 60, "mins" to 60,
        "hour" to 3600, "hours" to 3600, "ghanta" to 3600, "ghante" to 3600, "ghanta." to 3600
    )
    private val HIN = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6, "che" to 6,
        "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "pandrah" to 15, "bees" to 20, "tees" to 30,
        "chalis" to 40, "pachas" to 50
    )
    private val NUMW = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "and"
    )
    private val DIG = Regex("\\d+")
    private val FILLER = setOf(
        "nova", "hey", "please", "zara", "set", "a", "an", "the", "for", "me", "my", "in", "after", "of", "to", "ka", "ki", "ke", "ko",
        "laga", "lagao", "lagana", "do", "dena", "karo", "kar", "start", "timer", "reminder", "remind", "yaad", "dilao", "dilana",
        "baad", "later", "mujhe", "that", "about", "from", "now", "tell", "bata", "dena"
    )

    private fun isNumTok(tok: String, next: String?): Boolean =
        tok in NUMW || DIG.matches(tok) ||
            (tok in HIN && (tok != "do" || next != null && (next in UNIT_SEC || next == "baje")))

    private fun numVal(toks: List<String>): Double? {
        val mapped = toks.mapIndexed { i, x -> if (x in HIN) (if (x == "do" && toks.size == 1) "2" else HIN[x].toString()) else x }
        return LocalSkills.parseNum(mapped)
    }

    /** Sum of every "<number> <unit>" pair, plus half-hour words. null if no duration found. */
    internal fun durationSeconds(t: List<String>): Int? {
        var total = 0L
        var found = false
        var i = 0
        while (i < t.size) {
            val w = t[i]
            if ((w == "half" || w == "aadha" || w == "adha") && i + 1 < t.size) {
                val u = if (t[i + 1] == "an" && i + 2 < t.size) t[i + 2] else t[i + 1]
                if (u == "hour" || u == "ghanta") { total += 1800; found = true; i += 2; continue }
            }
            if (w == "dedh" && i + 1 < t.size && t[i + 1] == "ghanta") { total += 5400; found = true; i += 2; continue }
            var j = i
            while (j < t.size && isNumTok(t[j], t.getOrNull(j + 1)) && t[j] != "and") j++
            if (j > i && j < t.size && t[j] in UNIT_SEC) {
                val v = numVal(t.subList(i, j))
                if (v == null || v != Math.floor(v) || v > 100000) return null
                total += v.toLong() * (UNIT_SEC[t[j]] ?: 1)
                found = true
                i = j + 1
                continue
            }
            i++
        }
        return if (found && total in 1..86400) total.toInt() else null
    }

    private fun label(t: List<String>): String {
        val out = ArrayList<String>()
        var i = 0
        while (i < t.size) {
            val w = t[i]
            if (w in FILLER) { i++; continue }
            var j = i
            while (j < t.size && isNumTok(t[j], t.getOrNull(j + 1))) j++
            if (j > i && j < t.size && t[j] in UNIT_SEC) { i = j + 1; continue }   // the duration itself
            if (w in UNIT_SEC || w == "half" || w == "aadha" || w == "adha" || w == "dedh") { i++; continue }
            out.add(w); i++
        }
        return out.joinToString(" ").take(40)
    }

    /** One clock group: tens+ones ("twenty two"), a single number word, or digits. Returns value and tokens used. */
    private fun group(t: List<String>, i: Int): Pair<Int, Int>? {
        val w = t.getOrNull(i) ?: return null
        if (DIG.matches(w)) return (w.toIntOrNull() ?: return null) to 1
        val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)
        val ones = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9)
        val tn = tens[w]
        if (tn != null) {
            val o = ones[t.getOrNull(i + 1)]
            return if (o != null) (tn + o) to 2 else tn to 1
        }
        if (w == "do" && t.getOrNull(i + 1) != "baje") return null   // "alarm laga do" is not 2 o'clock
        val v = LocalSkills.parseNum(listOf(w)) ?: HIN[w]?.toDouble() ?: return null
        return v.toInt() to 1
    }

    private val AM = setOf("am", "morning", "subah", "savere", "savera")
    private val PM = setOf("pm", "afternoon", "evening", "shaam", "raat", "night", "dopahar", "sham")

    private fun alarm(t: List<String>): Logic.Cmd? {
        // "a m" / "p m" spoken as two letters
        val toks = ArrayList<String>()
        var k = 0
        while (k < t.size) {
            if ((t[k] == "a" || t[k] == "p") && t.getOrNull(k + 1) == "m") { toks.add(t[k] + "m"); k += 2 } else { toks.add(t[k]); k++ }
        }
        val isAm = toks.any { it in AM }
        val isPm = toks.any { it in PM }
        // first numeric group that is not part of a duration is the clock time
        var i = 0
        while (i < toks.size && group(toks, i) == null) i++
        if (i >= toks.size) return null
        var (h, used) = group(toks, i) ?: return null
        var m = 0
        var p = i + used
        if (toks.getOrNull(p) == "oh" || toks.getOrNull(p) == "o" || toks.getOrNull(p) == "zero") p++
        if (DIG.matches(toks[i]) && toks[i].length in 3..4) { m = h % 100; h /= 100; p = i + 1 }
        else {
            val g2 = group(toks, p)
            if (g2 != null && toks.getOrNull(p) != "baje") {
                m = g2.first
                p += g2.second
            }
        }
        if (h !in 0..24 || m !in 0..59) return null
        if (h == 24) h = 0
        val lbl = label(toks.filterIndexed { idx, w -> idx < i || idx >= p }.filter {
            it !in AM && it !in PM && it !in setOf("alarm", "baje", "wake", "up", "at", "am", "pm", "oh", "jagana", "jaga", "uthana", "uthao", "alarm.", "morning", "evening")
        })
        return when {
            h > 12 || h == 0 -> Logic.Cmd("alarm", lbl, h * 60 + m)
            isAm -> Logic.Cmd("alarm", lbl, (if (h == 12) 0 else h) * 60 + m)
            isPm -> Logic.Cmd("alarm", lbl, (if (h == 12) 12 else h + 12) * 60 + m)
            else -> Logic.Cmd("alarm_any", lbl, (h % 12) * 60 + m)
        }
    }

    /** t = normalised words (Logic.norm). Returns null when the sentence is not clearly a timer/reminder/alarm request. */
    fun parse(t: List<String>): Logic.Cmd? {
        if (t.isEmpty() || t.size > 14) return null
        val s = t.toSet()
        if (s.any { it in setOf("cancel", "delete", "remove", "hatao", "hata", "off", "band", "stop", "disable") } && s.any { it in setOf("alarm", "timer") }) return null
        val dur = durationSeconds(t)
        val remind = s.any { it in setOf("remind", "reminder", "yaad") }
        val timer = "timer" in s || "countdown" in s
        if (dur != null && (timer || remind)) return Logic.Cmd("timer", label(t), dur)
        if (dur != null && "alarm" in s && !timer) return Logic.Cmd("timer", label(t), dur)   // "alarm in 10 minutes"
        if ("alarm" in s || (("wake" in s || "jagana" in s || "jagao" in s) && "at" in s)) return alarm(t)
        return null
    }

    /** For "alarm_any": the next time (today or tomorrow) whose hour%12 and minute match; returns minute of day. */
    fun resolveAny(minOf12h: Int, now: LocalTime): Int {
        val m = minOf12h % 60
        val h12 = minOf12h / 60
        val nowMin = now.hour * 60 + now.minute
        val a = h12 * 60 + m
        val b = (h12 + 12) * 60 + m
        return listOf(a, b).firstOrNull { it > nowMin } ?: a   // both passed (late evening): the morning one tomorrow
    }

    fun human(seconds: Int, en: Boolean): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add(if (en) "$h hour" else "$h ghanta")
        if (m > 0) parts.add(if (en) "$m minute" else "$m minute")
        if (s > 0) parts.add("$s second")
        return parts.joinToString(" ")
    }

    fun clock(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(java.util.Locale.ENGLISH, "%d:%02d %s", h12, m, if (h < 12) "AM" else "PM")
    }
}
