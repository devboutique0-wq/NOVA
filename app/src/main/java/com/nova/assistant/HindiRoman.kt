package com.nova.assistant

/** Turns common Hindi (Devanagari) command words from a cloud listener into the Roman words Logic.classify knows. */
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
        "संगीत" to "music", "नेक्स्ट" to "next", "सॉन्ग" to "song"
    )

    fun toRoman(s: String): String {
        val tokens = s.split(Regex("[\\s,।.?!]+")).filter { it.isNotBlank() }
        return tokens.joinToString(" ") { MAP[it] ?: it }
    }
}
