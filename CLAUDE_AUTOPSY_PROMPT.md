# Claude Autopsy + Repair Prompt for NOVA 6.0

You are the final code reviewer, Android build engineer, and repair agent for this NOVA project. The project is an Android voice assistant that must be **local-first**, fast, additive (do not remove working features), and actually buildable.

## Your job

Perform a full “autopsy” of the uploaded NOVA 6.0 source tree. Do not merely report issues. **Fix every issue you can reproduce or prove from the code, rebuild the project, rerun tests, and return a repaired ZIP.**

Preserve existing behavior while upgrading it. Do not replace the architecture with a different app unless a defect makes that necessary.

## Required invariants

1. **No API key is required to activate NOVA.** Local phone actions must continue to work with zero API keys.
2. **Wake word is offline.** Vosk model is downloaded in CI and must be validated before the build.
3. **Fast command path.** After wake word, common commands must execute locally without a Gemini round-trip. Do not add unnecessary network calls to local commands.
4. **Speech capture must be responsive.** Keep end-of-speech short enough for normal speech, but do not truncate normal phrases. Verify race conditions around TTS, wake capture, command capture, and confirmation capture.
5. **Screen monitoring is explicitly user-controlled.** It must not upload screen text to a cloud service automatically. Monitoring must be local/event-based.
6. **Screen analysis must be honest.** If it only sees Accessibility text, do not claim visual/image understanding. Cloud analysis may be used only when explicitly requested and when an authorized key exists.
7. **Accessibility must be permission-safe.** It may be used for WhatsApp send confirmation, global navigation, and screen monitoring, but no silent/hidden cloud exfiltration is allowed.
8. **Dangerous/destructive actions need confirmation.** At minimum: phone calls, WhatsApp sends, and any future destructive action. Do not weaken the existing spoken confirmation gate.
9. **Fallback is failover, not quota bypass.** Multiple authorized keys and supported models may rotate on 429/5xx/availability errors. Never invent keys, scrape keys, or bypass provider restrictions.
10. **Boot behavior must respect modern Android foreground-service rules.** Do not start microphone foreground service silently from BOOT_COMPLETED/background. A notification/user-visible flow is acceptable.
11. **Do not reintroduce QUERY_ALL_PACKAGES or overlay permission unless you can demonstrate a concrete runtime requirement. Prefer scoped package visibility and native APIs.**
12. **No secrets in source, assets, Git history, test fixtures, README files, or ZIP output.**
13. **No “TODO” fixes left for critical runtime paths.**
14. **Every claimed feature must have a real implementation path or be explicitly documented as a limitation.**

## Audit checklist

### Build and packaging
- Run the GitHub/Gradle build exactly as the repository expects.
- Run all unit tests.
- Verify AndroidManifest XML, accessibility XML, resources, Kotlin compilation, JS syntax, and Gradle dependency resolution.
- Verify the CI wake-model download path and required model files.
- Ensure a debug APK is actually produced.
- Inspect the APK/source for accidental API keys or private credentials.

### Voice pipeline
- Verify wake-word recognizer initialization, grammar, model loading, buffer sizing, microphone errors, service restart, and cleanup.
- Verify “wake → command” does not require waiting for a fixed timer.
- Verify silence detection and max-command timeout.
- Verify TTS does not permanently block microphone capture.
- Verify confirmation capture has a separate, safe state and cannot accidentally approve an action.
- Verify microphone service start/stop is thread-safe and does not leak AudioRecord/Vosk/TTS resources.

### Local phone agent
- Test: torch, volume, brightness, battery, charging, time, date, media controls, app launch, Wi-Fi settings, Bluetooth settings, Home, Back, Recents, Notifications, Quick Settings, Lock.
- For every tool, verify actual success/failure is returned. The assistant must never say an action succeeded when Android rejected it.
- Check background activity-launch restrictions and add an honest fallback if an app cannot be opened directly.

### Screen agent / monitoring
- Verify Accessibility retrieves window content only when needed.
- Verify monitoring is opt-in and local.
- Verify screen changes are throttled/debounced to avoid battery drain and notification spam.
- Verify obvious error/warning detection cannot get stuck or loop.
- Verify “analyze screen” produces a useful local summary with no API key and a deeper result only when cloud AI is explicitly invoked.

### Cloud AI + fallback
- Verify every network request uses timeouts and disconnects resources.
- Verify key rotation order is deterministic.
- Verify model rotation order is deterministic.
- Verify 429/401/403/404/5xx/network failures fall through correctly.
- Verify provider errors never crash the foreground service.
- Verify history is bounded.
- Verify tool calls are bounded so loops cannot run forever.
- Verify no API key is logged or rendered in WebView.

### Security
- Verify API keys are encrypted at rest with Android Keystore.
- Verify screen content, contacts, and messages are not persisted unnecessarily.
- Verify WhatsApp auto-send only acts on the exact confirmed message and correct app.
- Verify Accessibility cannot accidentally send an old queued message.

### UX / resilience
- Every permission should be requested only when necessary.
- The app should clearly state when it is in local-only mode.
- If the Vosk model fails to load, show a recoverable setup error instead of silently pretending to listen.
- If Gemini is unavailable, local commands must continue working.
- If Accessibility is disabled, local phone controls that do not need it must still work.

## Fix policy

When you find an issue:
1. Reproduce it or prove it statically.
2. Fix the root cause, not only the symptom.
3. Add/modify a unit or smoke test when practical.
4. Rebuild and retest.
5. Re-scan for regressions.

Do not stop after finding the first problem. Continue until the repository is in a buildable, testable state.

## Final deliverables

Return:
- repaired source ZIP,
- exact build result and test result,
- short list of every issue you found and fixed,
- short list of any remaining Android/device-specific limitations that cannot be proven without a physical device,
- exact steps to install/test the APK.

Do not claim “fully working” unless the Gradle build and tests actually pass. If a physical-device check cannot be performed, state that limitation explicitly.
