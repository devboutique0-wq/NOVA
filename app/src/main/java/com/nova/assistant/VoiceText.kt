package com.nova.assistant

import java.util.Locale

/**
 * Conservative, local-only normalisation of recognised speech before NOVA's existing router sees it.
 * It does not execute commands, decide intent, grant confirmations, or contact any provider.
 *
 * When canonicalizeAsrAliases is true (a spoken utterance), it repairs only well-known split app-name
 * spellings commonly emitted by speech recognisers. Typed chat can turn this off so its wording is
 * not unexpectedly rewritten. Devanagari transliteration is the same existing HindiRoman path NOVA
 * already uses; unknown words are not fuzzy-corrected here.
 */
object VoiceText {
    private val SPACE = Regex("\\s+")
    private val ASR_ALIASES = listOf(
        Regex("\\byou[\\s-]+tube\\b", RegexOption.IGNORE_CASE) to "youtube",
        Regex("\\bblue[\\s-]+tooth\\b", RegexOption.IGNORE_CASE) to "bluetooth",
        Regex("\\bwi[\\s-]+fi\\b", RegexOption.IGNORE_CASE) to "wifi",
        Regex("\\bflash[\\s-]+light\\b", RegexOption.IGNORE_CASE) to "flashlight",
        Regex("\\binsta[\\s-]+gram\\b", RegexOption.IGNORE_CASE) to "instagram",
        Regex("\\bface[\\s-]+book\\b", RegexOption.IGNORE_CASE) to "facebook",
        Regex("\\btele[\\s-]+gram\\b", RegexOption.IGNORE_CASE) to "telegram",
        Regex("\\bwhat[\\s-]*sap\\b", RegexOption.IGNORE_CASE) to "whatsapp",
        Regex("\\bwhats[\\s-]+app\\b", RegexOption.IGNORE_CASE) to "whatsapp",
        Regex("\\bhot[\\s-]+star\\b", RegexOption.IGNORE_CASE) to "hotstar"
    )

    /** Returns routing text; retain the original separately for transcripts/display. */
    fun normalize(raw: String, canonicalizeAsrAliases: Boolean = true): String {
        if (raw.isBlank()) return ""
        val devanagari = HindiRoman.hasDevanagari(raw)
        // Preserve existing typed-chat handling byte-for-byte for Latin text, and preserve its prior
        // HindiRoman conversion for Devanagari text. ASR cleanup applies only to actual voice captures.
        if (!canonicalizeAsrAliases) return if (devanagari) HindiRoman.toRoman(raw) else raw
        var value = if (devanagari) HindiRoman.toRoman(raw) else raw
        value = value.lowercase(Locale.ROOT).replace('\u00A0', ' ')
        for ((pattern, replacement) in ASR_ALIASES) {
            value = pattern.replace(value, replacement)
        }
        return SPACE.replace(value, " ").trim()
    }
}
