# NOVA — Safe Voice-Text Recognition Upgrade

## Scope

This is a **backward-compatible voice-input-only patch**. It does not replace NOVA or redesign its UI, assistant personality, routing, permissions, wake word, model setup, reply language setting, TTS voice, or existing feature set.

## What changed

- Added `app/src/main/java/com/nova/assistant/VoiceText.kt`, a pure Kotlin, local-only normalization layer.
- The spoken-transcript path now standardizes common split speech-recognition spellings for known names/terms such as `you tube` → `youtube`, `insta gram` → `instagram`, `face book` → `facebook`, `tele gram` → `telegram`, `whats app` / `what sap` → `whatsapp`, `hot star` → `hotstar`, `blue tooth` → `bluetooth`, `wi-fi` → `wifi`, and `flash light` → `flashlight`.
- Existing Devanagari-to-Roman conversion remains the same `HindiRoman` implementation. Existing local command classification and skill matching still decide what the text means.
- The original transcript is retained for display; normalization is for routing only.
- Alias rewriting is restricted to speech captures (`pcm` is present). Plain Latin typed chat is passed through unchanged; the prior Devanagari typed-chat conversion is retained.
- Groq/Gemini transcript text passes through the same conservative normalizer after the pre-existing Hindi-to-Roman conversion.

## Explicitly unchanged

- Wake word / wake grammar and Vosk models.
- Existing offline Vosk, optional Groq STT, and configured Gemini transcription fallback order.
- App-action routing, fuzzy shortcut matching, site/agent/image flows, knowledge fallbacks and all other assistant features.
- Speaker/voice lock and permission gates.
- Confirmation semantics: a dangerous action still requires NOVA's existing short, local, spoken affirmative. Extra words or ambiguous answers do not become approval.
- Existing speech-endpoint/silence timing and wake behavior.
- Current reply-language configuration and TTS settings.
- API-key storage. No key, token, model file, or dependency has been hardcoded or added by this patch.
- Audio storage/upload behavior. This change adds no audio file writes and no new network request. Existing configured cloud transcription continues to behave exactly as before.

## Test evidence in this workspace

- `VoiceTextTest.kt`: 7 JUnit test methods, with 62 table-driven transcript/routing examples plus typed-text and strict-confirmation assertions.
- JVM smoke test of the standalone `HindiRoman.kt` + `VoiceText.kt`: **24/24 checks passed**.
- Existing repository contract scripts: **13/13 passed**, including the voice-lock, agent safety, Hindi conversion, memory, UI, knowledge, image/site intent, and provider-chain checks.

## Not verified here

- Android Gradle/JUnit build: not run because this ZIP has no Gradle wrapper and the workspace has no installed `gradle` executable or Android SDK build environment.
- Actual phone microphone recognition, wake-word false-positive rate, provider latency, TTS playback, and device-specific behavior: not tested on a physical Android device in this workspace.
- A full app build or installable APK is **not claimed**.

## Suggested verification on the owner's build environment

1. Run the project's existing Gradle test task and assemble task from Android Studio or CI.
2. Try voice commands in Hindi, Hinglish, and English: `टॉर्च चालू करो`, `open insta gram`, `open face book`, `open you tube`, `volume badhao`, `battery kitni hai`.
3. Check that plain typed text, wake behavior, reply voice setting, speaker lock, and existing spoken confirmations behave as before.
4. If anything fails, fix the smallest relevant regression without refactoring other NOVA features.
