package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for transcript text only; no microphone, network, or command execution is used. */
class VoiceTextTest {
    @Test fun hindiDevanagariExamplesNormalizeToExistingRomanPath() {
        val cases = listOf(
            "टॉर्च चालू करो" to "torch chalu karo",
            "टॉर्च बंद करो" to "torch band karo",
            "व्हाट्सएप खोलो" to "whatsapp kholo",
            "यूट्यूब खोलो" to "youtube kholo",
            "इंस्टाग्राम खोलो" to "instagram kholo",
            "फेसबुक खोलो" to "facebook kholo",
            "टेलीग्राम खोलो" to "telegram kholo",
            "सेटिंग्स खोलो" to "settings kholo",
            "वॉल्यूम बढ़ाओ" to "volume badhao",
            "वॉल्यूम कम करो" to "volume kam karo",
            "ब्लूटूथ चालू करो" to "bluetooth chalu karo",
            "वाईफाई बंद करो" to "wifi band karo",
            "बैटरी कितनी है" to "battery kitni hai",
            "हाँ" to "haan",
            "नहीं" to "nahi",
            "वॉल्यूम ५० करो" to "volume 50 karo"
        )
        for ((spoken, expected) in cases) {
            assertEquals("Hindi transcript: $spoken", expected, VoiceText.normalize(spoken))
        }
    }

    @Test fun hinglishAsrSplitWordsAreCanonicalizedConservatively() {
        val cases = listOf(
            "open insta gram" to "open instagram",
            "insta-gram kholo" to "instagram kholo",
            "open face book" to "open facebook",
            "face-book kholo" to "facebook kholo",
            "open tele gram" to "open telegram",
            "tele-gram kholo" to "telegram kholo",
            "open whats app" to "open whatsapp",
            "whats-app kholo" to "whatsapp kholo",
            "open what sap" to "open whatsapp",
            "hot star kholo" to "hotstar kholo",
            "Open You Tube" to "open youtube",
            "blue tooth settings" to "bluetooth settings",
            "wi-fi settings" to "wifi settings",
            "flash light on" to "flashlight on",
            "volume badhao" to "volume badhao",
            "battery kitni hai" to "battery kitni hai",
            "NOVA TORCH ON" to "nova torch on",
            "  open   camera  " to "open camera"
        )
        for ((spoken, expected) in cases) {
            assertEquals("Hinglish/ASR transcript: $spoken", expected, VoiceText.normalize(spoken))
        }
    }

    @Test fun commonEnglishAndMixedSpokenExamplesRemainRecognisable() {
        val cases = listOf(
            "open Instagram" to "open instagram",
            "open Facebook" to "open facebook",
            "open Telegram" to "open telegram",
            "open WhatsApp" to "open whatsapp",
            "open Hotstar" to "open hotstar",
            "turn torch on" to "turn torch on",
            "torch off" to "torch off",
            "what is my battery" to "what is my battery",
            "volume fifty" to "volume fifty",
            "open wifi settings" to "open wifi settings",
            "play the next song" to "play the next song",
            "hello NOVA, battery kitni hai?" to "hello nova, battery kitni hai?",
            "hello, duniya" to "hello, duniya",
            "" to "",
            "    " to ""
        )
        for ((spoken, expected) in cases) {
            assertEquals("English/mixed transcript: $spoken", expected, VoiceText.normalize(spoken))
        }
    }

    @Test fun splitAppNamesReachTheSameExistingLocalCommand() {
        val cases = listOf(
            "open insta gram" to "open instagram",
            "open face book" to "open facebook",
            "open tele gram" to "open telegram",
            "open whats app" to "open whatsapp",
            "open hot star" to "open hotstar",
            "open you tube" to "open youtube"
        )
        for ((heard, canonical) in cases) {
            val parsed = Logic.classify(VoiceText.normalize(heard))
            val expected = Logic.classify(canonical)
            assertNotNull("Expected existing classifier to understand $heard", parsed)
            assertEquals("Alias should only reach the existing app-opening route: $heard", expected, parsed)
            assertEquals("open_app", parsed?.kind)
        }
    }

    @Test fun typedTextDoesNotReceiveAsrOnlyAliasRewrites() {
        assertEquals("insta gram kholo", VoiceText.normalize("insta gram kholo", canonicalizeAsrAliases = false))
        assertEquals("face book kholo", VoiceText.normalize("face book kholo", canonicalizeAsrAliases = false))
        assertEquals("what sap kholo", VoiceText.normalize("what sap kholo", canonicalizeAsrAliases = false))
        assertEquals("open CAMERA", VoiceText.normalize("open CAMERA", canonicalizeAsrAliases = false))
        assertEquals("  open CAMERA  ", VoiceText.normalize("  open CAMERA  ", canonicalizeAsrAliases = false))
    }

    @Test fun mixedScriptAndPunctuationAreHandledWithoutThrowing() {
        val cases = listOf(
            "NOVA, टॉर्च चालू करो" to "nova torch chalu karo",
            "Battery कितनी है?" to "battery kitni hai",
            "वॉल्यूम कम करो" to "volume kam karo",
            "wifi\u00A0settings" to "wifi settings",
            "नहीं, अभी नहीं" to "nahi abhi nahi",
            "🙂 टॉर्च ऑन" to "🙂 torch on",
            "OPEN Insta Gram PLEASE" to "open instagram please"
        )
        for ((spoken, expected) in cases) {
            assertEquals("Mixed-script transcript: $spoken", expected, VoiceText.normalize(spoken))
        }
    }

    @Test fun safetyConfirmationsRemainStrictAfterNormalization() {
        assertEquals(true, Logic.parseAnswer(VoiceText.normalize("हाँ")))
        assertEquals(true, Logic.parseAnswer(VoiceText.normalize("haan")))
        assertEquals(false, Logic.parseAnswer(VoiceText.normalize("नहीं")))
        assertEquals(false, Logic.parseAnswer(VoiceText.normalize("haan nahi")))
        assertNull(Logic.parseAnswer(VoiceText.normalize("haan sabko bhej do")))
        assertNull(Logic.parseAnswer(VoiceText.normalize("send it now")))
        assertNull(Logic.parseAnswer(VoiceText.normalize("maybe")))
        assertFalse(VoiceText.normalize("open whats app").isBlank())
    }
}
