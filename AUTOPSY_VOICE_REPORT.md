# NOVA voice patch autopsy (VoiceText.kt)

Baseline = the previous NOVA_v15_assistant.zip (my last build, CI green). Candidate = NOVA_v15_voice_recognition_safe_upgrade.zip.
`diff -rq` shows ONLY: VoiceText.kt (new), VoiceTextTest.kt (new), VOICE_RECOGNITION_SAFE_UPGRADE.md (new), CLAUDE_VOICE_AUTOPSY_PROMPT.md (new), and 3 hunks in NovaService.kt (handle(), Gemini listen, Groq listen). Nothing else differs.

## Result: no real defect found. No code was changed by me.

## Checked (source read)
- VoiceText.normalize only returns text. No network, file, audio or key code in it.
- handle(): typed chat calls process(ByteArray(0),...) so aliases are skipped; spoken calls pass pcm so aliases apply. Devanagari still goes through HindiRoman.toRoman in both cases.
- Confirmation path untouched: processAnswer does not call VoiceText. A fuzzy or cloud transcript cannot approve a risky action.
- Voice lock gate (voiceAllows), mic loop, endpointing, buffers, Groq/Gemini order, endpoints and key retrieval: not in the diff.
- Dictated replies go through processAnswer, so they are not rewritten.
- Normalizing twice gives the same text (idempotent), checked on 15 sentences with a Python port of the regexes.

## Findings
| # | Severity | Where | Note |
|---|----------|-------|------|
| 1 | Low | VoiceText.kt ASR_ALIASES | Spoken ordinary English can be rewritten: "a hot star is bright" -> "a hotstar is bright", "make a face book cover" -> "make a facebook cover", "what sap" -> "whatsapp". Only voice, only routing text. Not fixed: it is the intended behaviour and the rules say fix real defects only. |
| 2 | Info | handle() | Spoken text is now lowercased and spaces collapsed. Vosk and the cloud listeners already return lowercase, so no change expected. |

## Test matrix
| Check | Status |
|---|---|
| tools/test_*.py (13 scripts) | PASSED (run here) |
| JS syntax check of ui.html | PASSED (run in the earlier round on the same ui.html, file unchanged) |
| Python port of alias regexes, 15 sentences | PASSED (run here; a port, not the Kotlin code) |
| VoiceTextTest.kt (7 methods) | NOT RUN (no Gradle or Android SDK here) |
| Android Gradle build / assemble | NOT RUN here |
| CI (GitHub) for this candidate | NOT RUN yet |
| Phone microphone test | NOT RUN |
