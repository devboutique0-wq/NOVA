# Claude Prompt — NOVA Voice Upgrade Autopsy (No Assistant Redesign)

You are reviewing the supplied NOVA Android/Kotlin project ZIP after a **small, backward-compatible voice-text normalization patch**. The owner has explicitly instructed: **DO NOT CHANGE NOVA'S EXISTING ASSISTANT BEHAVIOR OR FEATURES.** This is an audit and targeted repair task, not permission to redesign or rewrite the assistant.

## Non-negotiable rules

1. Inspect the complete project and all modifications before editing anything.
2. Preserve the current UI, wake word, Vosk model flow, voice/speaker lock, permissions, assistant personality, app actions, shortcut/brain matching, site builder, task agent, image creation, knowledge fallback, memory, TTS configuration, reply-language setting, and confirmation safety.
3. Do not remove, replace, disable, simplify, or silently change an existing feature.
4. Do not add new API keys, tokens, credentials, analytics, dependencies, network requests, or audio-file storage.
5. Never change confirmation logic so that a fuzzy transcript or cloud response can approve a risky action. Only NOVA's existing short, local, spoken affirmative can approve an awaiting confirmation; unclear answers must continue to cancel.
6. Do not add Android `SpeechRecognizer` as a second microphone owner or redesign microphone capture. Do not download a Hindi Vosk model.
7. Make changes only if a real defect is found. First attempt a minimal fix isolated to `VoiceText.kt`, its call sites in `NovaService.kt`, or its tests. If a suspected defect is unrelated, report it separately instead of fixing it in this patch.
8. Never claim a test, build, install, physical-device check, or feature passed unless you ran it and captured the result.

## Files specifically changed by this patch

- `app/src/main/java/com/nova/assistant/VoiceText.kt` (new conservative transcript normalizer)
- `app/src/main/java/com/nova/assistant/NovaService.kt` (normalizer calls at the existing input/transcription boundary)
- `app/src/test/java/com/nova/assistant/VoiceTextTest.kt` (new regression tests)
- `VOICE_RECOGNITION_SAFE_UPGRADE.md` (test/limitation report)

## Autopsy checklist

- Verify the normalizer does not execute actions; it only returns text.
- Verify Devanagari conversion still uses the existing `HindiRoman` behavior.
- Verify the common speech-recognition spelling aliases are conservative and do not over-correct ordinary sentences.
- Verify alias rewriting is voice-only; plain Latin typed text is unchanged, and typed Devanagari follows the prior conversion path.
- Verify Groq and Gemini fallback conditions, endpoints, key retrieval, and ordering did not change.
- Verify the normalizer does not store audio, add network requests, log secrets, or introduce hardcoded keys.
- Verify original audio buffer lifetime, wake/listen state machine, adaptive endpointing, voice lock, dangerous-action confirmation, and reply/TTS settings remain unchanged.
- Review all 7 new JUnit test methods (62 table-driven examples plus strict typed-text/confirmation checks). Run Android JUnit and assemble tasks if the environment supports Gradle/Android SDK.
- Run all existing `tools/test_*.py` contract scripts. Investigate every regression; do not delete or weaken tests to achieve a green result.
- Check code formatting, imports, Kotlin compatibility for this project's configured versions, and edge cases around empty text, punctuation, and mixed Hindi/English.

## Required output

1. A plain-language autopsy report listing each issue found, severity, exact file/line, and evidence.
2. A clear test matrix: passed / failed / not run, with actual commands and output summaries.
3. If—and only if—a real voice-normalization regression is confirmed, apply the smallest safe fix and rerun relevant tests.
4. Return the complete corrected project ZIP with project files at ZIP root, plus the report and a short change log.
5. State explicitly whether Android Gradle build, JVM/unit tests, CI, and physical-device tests were actually run. Do not invent results.

Final acceptance criterion: NOVA behaves exactly as it did before for existing inputs/features, except that the intended known split-word speech aliases are normalized before reaching the existing router. If preserving that invariant cannot be proved, do not make speculative edits; report the uncertainty instead.
