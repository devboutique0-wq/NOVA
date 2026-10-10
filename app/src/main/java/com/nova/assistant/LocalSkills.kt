package com.nova.assistant

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Layer 0 of the offline brain: deterministic daily-life skills. Pure Kotlin, no model, no network, no Android.
 * answer() returns a short reply or null. null means "not mine" and the normal routing continues.
 * Every skill is STRICT: it only fires on a clear pattern (numbers must parse completely, units must be known),
 * so it can never swallow an ordinary command like "add milk to the list" or "volume fifty".
 * Nothing here acts on the phone. It only produces text, so the confirmation gate is not involved.
 */
object LocalSkills {

    fun answer(text: String, today: LocalDate, lang: String): String? {
        if (text.isBlank() || text.length > 160) return null
        val n = tnorm(text)
        if (n.isEmpty()) return null
        val en = lang == "en"
        return emergency(n, en) ?: firstAid(n, en) ?: dateSkill(n, today, en) ?: convert(n) ?: calc(n, en)
    }

    // ------------------------------------------------------------------ text + number helpers

    /** Lower-case words; keeps digits and decimal points between digits; maps + % x * / - to words. */
    internal fun tnorm(text: String): String {
        var s = text.lowercase().replace("[unk]", " ")
        s = s.replace("°", " ").replace("%", " percent ").replace("×", " times ").replace("÷", " divided by ")
        s = s.replace("+", " plus ").replace("*", " times ").replace("/", " divided by ")
        s = s.replace(Regex("\\s-\\s"), " minus ")
        s = s.replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), " times ")
        s = s.replace(Regex("(?<=\\d),(?=\\d{3})"), "")
        s = s.replace(Regex("[^a-z0-9. ]"), " ")
        s = s.replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private val ONES = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )
    private val BIG = mapOf(
        "thousand" to 1e3, "lakh" to 1e5, "lac" to 1e5, "million" to 1e6, "crore" to 1e7, "billion" to 1e9
    )
    private val DIGITS = Regex("\\d+(\\.\\d+)?")

    /** Strict: the WHOLE token list must be one number ("two hundred fifty", "12.5", "three point five"), else null. */
    internal fun parseNum(t: List<String>): Double? {
        if (t.isEmpty()) return null
        var total = 0.0
        var cur = 0.0
        var seen = false
        var i = 0
        while (i < t.size) {
            val w = t[i]
            val one = ONES[w]
            val ten = TENS[w]
            val big = BIG[w]
            when {
                w == "and" -> { /* "one hundred and five" */ }
                w == "point" -> {
                    val frac = StringBuilder()
                    for (d in t.drop(i + 1)) {
                        val v = ONES[d]
                        if (v != null && v in 0..9) frac.append(v)
                        else if (d.length == 1 && d[0].isDigit()) frac.append(d)
                        else return null
                    }
                    if (frac.isEmpty()) return null
                    return total + cur + ("0." + frac).toDouble()
                }
                DIGITS.matches(w) -> { cur += w.toDouble(); seen = true }
                one != null -> { cur += one; seen = true }
                ten != null -> { cur += ten; seen = true }
                w == "hundred" -> { cur = (if (cur == 0.0) 1.0 else cur) * 100; seen = true }
                big != null -> { total += (if (cur == 0.0) 1.0 else cur) * big; cur = 0.0; seen = true }
                else -> return null
            }
            i++
        }
        return if (seen) total + cur else null
    }

    internal fun fmt(x: Double): String {
        if (x.isNaN() || x.isInfinite()) return "?"
        if (x == Math.floor(x) && Math.abs(x) < 1e15) return x.toLong().toString()
        val s = String.format(Locale.ENGLISH, "%.4f", x).trimEnd('0').trimEnd('.')
        return if (s == "-0" || s.isEmpty()) "0" else s
    }

    private val LEAD = setOf(
        "nova", "hey", "please", "zara", "tell", "me", "what", "whats", "is", "the", "calculate", "compute", "find",
        "batao", "bata", "kya", "hai", "kitna", "kitne", "hota", "hoga", "how", "much", "convert", "total"
    )
    private val TRAIL = setOf("kitna", "kitne", "hota", "hoga", "hai", "equals", "equal", "please", "kya", "hote", "hain")

    private fun side(s: String): List<String> {
        val t = s.split(" ").filter { it.isNotEmpty() }.toMutableList()
        while (t.isNotEmpty() && t.first() in LEAD) t.removeAt(0)
        while (t.isNotEmpty() && t.last() in TRAIL) t.removeAt(t.size - 1)
        return t
    }

    // ------------------------------------------------------------------ calculator

    private fun calc(n: String, en: Boolean): String? {
        Regex("^(.+?) percent of (.+)$").find(n)?.let { m ->
            val a = parseNum(side(m.groupValues[1]))
            val b = parseNum(side(m.groupValues[2]))
            if (a != null && b != null) return "${fmt(a)} percent of ${fmt(b)} = ${fmt(a * b / 100.0)}"
        }
        Regex("^(?:what is |whats |find |calculate )*square root of (.+)$").find(n)?.let { m ->
            val a = parseNum(side(m.groupValues[1]))
            if (a != null && a >= 0) return "root ${fmt(a)} = ${fmt(Math.sqrt(a))}"
        }
        Regex("^(?:what is |whats |find |calculate )*square of (.+)$").find(n)?.let { m ->
            val a = parseNum(side(m.groupValues[1]))
            if (a != null) return "${fmt(a)} squared = ${fmt(a * a)}"
        }
        val divZero = if (en) "Zero se divide nahi ho sakta" else "Zero se divide nahi hota"
        val forms = listOf(
            Triple("^(?:add|sum of) (.+?) (?:and|plus|to) (.+)$", '+', false),
            Triple("^subtract (.+?) from (.+)$", '-', true),
            Triple("^multiply (.+?) (?:by|and) (.+)$", '*', false),
            Triple("^divide (.+?) by (.+)$", '/', false),
            Triple("^(.+?) plus (.+)$", '+', false),
            Triple("^(.+?) minus (.+)$", '-', false),
            Triple("^(.+?) (?:times|into|multiplied by) (.+)$", '*', false),
            Triple("^(.+?) divided by (.+)$", '/', false)
        )
        for ((re, op, swap) in forms) {
            val m = Regex(re).find(n) ?: continue
            var a = parseNum(side(m.groupValues[1])) ?: continue
            var b = parseNum(side(m.groupValues[2])) ?: continue
            if (swap) { val x = a; a = b; b = x }
            if (op == '/' && b == 0.0) return divZero
            val r = when (op) { '+' -> a + b; '-' -> a - b; '*' -> a * b; else -> a / b }
            return "${fmt(a)} $op ${fmt(b)} = ${fmt(r)}"
        }
        return null
    }

    // ------------------------------------------------------------------ unit conversion

    private class U(val dim: Char, val f: Double, val label: String)

    private val UNITS: Map<String, U> = HashMap<String, U>().also { m ->
        fun add(d: Char, f: Double, label: String, vararg names: String) { for (x in names) m[x] = U(d, f, label) }
        add('L', 1.0, "m", "m", "meter", "meters", "metre", "metres")
        add('L', 1000.0, "km", "km", "kilometer", "kilometers", "kilometre", "kilometres")
        add('L', 0.01, "cm", "cm", "centimeter", "centimeters", "centimetre", "centimetres")
        add('L', 0.001, "mm", "mm", "millimeter", "millimeters", "millimetre", "millimetres")
        add('L', 1609.344, "miles", "mile", "miles")
        add('L', 0.0254, "inch", "inch", "inches")
        add('L', 0.3048, "feet", "foot", "feet", "ft")
        add('L', 0.9144, "yards", "yard", "yards")
        add('M', 1.0, "kg", "kg", "kilo", "kilos", "kilogram", "kilograms", "kilogramme")
        add('M', 0.001, "g", "g", "gram", "grams", "gm")
        add('M', 0.45359237, "lb", "lb", "lbs", "pound", "pounds")
        add('M', 0.028349523125, "oz", "oz", "ounce", "ounces")
        add('V', 1.0, "litre", "l", "litre", "litres", "liter", "liters")
        add('V', 0.001, "ml", "ml", "millilitre", "millilitres", "milliliter", "milliliters")
        add('V', 0.24, "cup", "cup", "cups")
        add('V', 0.015, "tablespoon", "tablespoon", "tablespoons", "tbsp")
        add('V', 0.005, "teaspoon", "teaspoon", "teaspoons", "tsp")
        add('V', 3.785411784, "gallon", "gallon", "gallons")
        add('T', 1.0, "C", "celsius", "centigrade", "c")
        add('T', 1.0, "F", "fahrenheit", "f")
    }
    private val CONV_LEAD = setOf(
        "convert", "what", "is", "whats", "how", "much", "please", "nova", "hey", "tell", "me", "kitna", "hota", "hai", "batao", "degrees", "degree"
    )
    private val CONV_SEP = setOf("to", "in", "into", "mein", "me")

    private fun convert(n: String): String? {
        val t = n.split(" ").filter { it.isNotEmpty() && it != "degrees" && it != "degree" }
        if (t.size < 3) return null
        var from: List<String>
        val target: String
        if (t.size >= 4 && t[0] == "how" && t[1] == "many") {
            val inIdx = t.indexOf("in")
            if (inIdx < 3) return null
            target = t.subList(2, inIdx).filter { it != "are" && it != "is" }.singleOrNull() ?: return null
            from = t.drop(inIdx + 1)
            if (from.size >= 2 && (from[0] == "a" || from[0] == "an")) from = listOf("one") + from.drop(1)
        } else {
            val body = t.dropWhile { it in CONV_LEAD }
            if (body.size < 3) return null
            val sepIdx = body.size - 2
            if (body[sepIdx] !in CONV_SEP) return null
            target = body.last()
            from = body.subList(0, sepIdx)
        }
        if (from.size < 2) return null
        val fu = UNITS[from.last()] ?: return null
        val tu = UNITS[target] ?: return null
        if (fu.dim != tu.dim) return null
        val v = parseNum(from.dropLast(1)) ?: return null
        val r = if (fu.dim == 'T') {
            when {
                fu.label == tu.label -> v
                fu.label == "C" -> v * 9.0 / 5.0 + 32.0
                else -> (v - 32.0) * 5.0 / 9.0
            }
        } else v * fu.f / tu.f
        val sym = { u: U -> if (u.dim == 'T') "°" + u.label else u.label }
        return "${fmt(v)} ${sym(fu)} = ${fmt(r)} ${sym(tu)}"
    }

    // ------------------------------------------------------------------ dates

    private val DAYUNITS = setOf("day", "days", "din", "week", "weeks", "hafte", "hafta", "month", "months", "mahine", "mahina")
    private val DATE_LEAD = setOf(
        "what", "is", "the", "date", "tarikh", "tareekh", "kya", "hogi", "hoga", "hai", "after", "in", "nova", "hey", "please",
        "tell", "me", "batao", "kaun", "sa", "konsa", "aaj", "se", "from", "today", "which", "day", "will", "be", "was", "it"
    )
    private val FUTURE = setOf("baad", "later", "after", "from", "ke")
    private val PAST = setOf("ago", "pehle", "before")

    private fun dayName(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun dateText(d: LocalDate): String =
        "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${d.year} (${dayName(d)})"

    private fun dateSkill(n: String, today: LocalDate, en: Boolean): String? {
        val t = n.split(" ")
        val s = t.toSet()
        val asksDate = s.any { it == "date" || it == "tarikh" || it == "tareekh" } ||
            n.contains("what day") || n.contains("which day") || n.contains("kaun sa din") || n.contains("kya din") || n.contains("konsa din")
        if (!asksDate) return null
        val ui = t.indexOfFirst { it in DAYUNITS }
        if (ui > 0) {
            val before = t.subList(0, ui).dropWhile { it in DATE_LEAD }
            val count = parseNum(before)
            if (count != null && count == Math.floor(count) && count in 0.0..100000.0) {
                val past = t.any { it in PAST }
                val future = t.any { it in FUTURE } || t.contains("in")
                if (past || future) {
                    val k = count.toLong()
                    val unit = t[ui]
                    val sign = if (past) -1L else 1L
                    val d = when (unit) {
                        "week", "weeks", "hafte", "hafta" -> today.plusWeeks(sign * k)
                        "month", "months", "mahine", "mahina" -> today.plusMonths(sign * k)
                        else -> today.plusDays(sign * k)
                    }
                    val head = if (en) "${fmt(count)} $unit ${if (past) "ago" else "from today"}"
                    else "${fmt(count)} $unit ${if (past) "pehle" else "baad"}"
                    return "$head: ${dateText(d)}"
                }
            }
        }
        if (n.contains("what day") || n.contains("which day") || n.contains("kaun sa din") || n.contains("kya din") || n.contains("konsa din")) {
            return when {
                s.contains("tomorrow") -> (if (en) "Tomorrow is " else "Kal (aane wala) ") + dateText(today.plusDays(1)) + (if (en) "" else " hai")
                s.contains("yesterday") -> (if (en) "Yesterday was " else "Beeta hua kal ") + dateText(today.minusDays(1)) + (if (en) "" else " tha")
                s.contains("kal") ->
                    "Aaj ${dateText(today)}. Aane wala kal ${dayName(today.plusDays(1))}, beeta hua kal ${dayName(today.minusDays(1))}."
                else -> if (en) "Today is ${dateText(today)}" else "Aaj ${dateText(today)} hai"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ emergency numbers + first aid

    private val NUM_WORDS = setOf("number", "numbers", "nambar", "helpline", "no")
    private const val SAFE_EN = "Numbers can differ by state; 112 works everywhere in India. I cannot dial for you, please call yourself."
    private const val SAFE_HI = "Number state ke hisaab se alag ho sakte hain; 112 poore India mein chalta hai. Main khud call nahi lagata, aap dial karo."

    private fun emergency(n: String, en: Boolean): String? {
        val s = n.split(" ").toSet()
        if (s.none { it in NUM_WORDS }) return null
        val tail = if (en) SAFE_EN else SAFE_HI
        return when {
            s.contains("ambulance") -> "Ambulance: 102 or 108 (or 112). $tail"
            s.contains("police") -> "Police: 100 (or 112). $tail"
            s.contains("fire") && (s.contains("brigade") || s.contains("service") || s.contains("station") || s.contains("department")) ->
                "Fire brigade: 101 (or 112). $tail"
            s.contains("women") -> "Women helpline: 1091 (or 112). $tail"
            s.contains("child") || s.contains("childline") -> "Childline: 1098 (or 112). $tail"
            s.contains("cyber") -> "Cyber crime helpline: 1930 (or 112). $tail"
            s.contains("emergency") -> "Emergency: 112. Police 100, fire 101, ambulance 102 or 108. $tail"
            else -> null
        }
    }

    private val AID_WORDS = setOf("treatment", "upay", "ilaj", "karu", "karna", "karein", "help", "aid")

    private fun firstAid(n: String, en: Boolean): String? {
        val s = n.split(" ").toSet()
        val asks = n.contains("first aid") || n.contains("what to do") || n.contains("how to treat") || s.any { it in AID_WORDS }
        if (!asks) return null
        val foot = if (en) " This is general information, not a doctor's advice."
        else " Ye general jaankari hai, doctor ki salah ka vikalp nahi."
        val body: String = when {
            s.any { it == "snake" || it == "saanp" } ->
                if (en) "Snake bite: stay calm and still, keep the bitten limb steady at heart level or lower, remove rings and tight items, do not cut, suck or tie it tightly. Call 108 or 112 now and go to a hospital."
                else "Saanp ka katna: shant raho aur kam hilo, kate hue hisse ko dil ke level ya neeche sthir rakho, anguthi aur tight cheezein hata do. Kaato nahi, choosna nahi, tight patti mat bandho. Turant 108 ya 112 karo aur hospital jao."
            (s.contains("nose") || s.contains("naak") || s.contains("nosebleed")) &&
                (s.contains("bleeding") || s.contains("blood") || s.contains("khoon") || s.contains("nosebleed")) ->
                if (en) "Nosebleed: sit up and lean slightly forward, pinch the soft part of the nose for 10 minutes and breathe through the mouth. If it does not stop after 20 minutes or followed an injury, get medical help or call 112."
                else "Naak se khoon: seedhe baitho aur thoda aage jhuko, naak ka naram hissa 10 minute dabake rakho, muh se saans lo. 20 minute baad bhi na ruke ya chot lagi ho to doctor ya 112."
            s.any { it == "burn" || it == "burnt" || it == "jal" || it == "jala" } ->
                if (en) "Burn: hold the burn under cool running water for 10 to 20 minutes, no ice, toothpaste, butter or ghee, do not pop blisters, cover loosely with a clean cloth. For a large burn, or on face, hands or joints, get a doctor or call 112."
                else "Jalna: jale hisse par 10 se 20 minute nal ka thanda paani daalo, baraf, toothpaste, makhan ya ghee mat lagao, chhale mat phodo, saaf kapde se halka dhak do. Bada jalna ya chehra, haath, jodon par ho to doctor ya 112."
            s.any { it == "bleeding" || it == "khoon" || it == "cut" || it == "wound" || it == "zakhm" } ->
                if (en) "Bleeding: press firmly on the wound with a clean cloth and keep pressing, add more cloth on top if it soaks through, raise the injured part. If heavy or it does not stop, call 112 or 108 now."
                else "Khoon behna: saaf kapde se zakhm par zor se dabao aur dabake rakho, bhig jaye to hatao mat, upar aur kapda rakho, zakhmi hissa upar rakho. Bahut khoon ya na ruke to turant 112 ya 108."
            else -> return null
        }
        return body + foot
    }
}
