# NOVA 6.0 - Autopsy Report

## Result (read this first)

| Item | Result |
|---|---|
| `gradle testDebugUnitTest assembleDebug` | **NOT RUN** (no Gradle, Kotlin compiler, Android SDK or network in the repair environment) |
| Unit tests (`LogicTest.kt`, 38 tests) | **NOT RUN**. Expected values were traced by hand against `Logic.kt`; that is not the same as executing them |
| `ui.html` script | `node --check` **passed**; every `Android.*` call in the page exists in `MainActivity.Bridge`; every element id used by the script exists |
| XML (manifest, accessibility config, resources) | all well-formed (parsed), all `@resource` references resolve |
| Static Kotlin review | brace/paren balance OK in all 7 files; every cross-file member (`Logic.*`, `SecureStore.*`, `Cfg.*`, `NovaService.*`, `NovaAccessibilityService.*`) exists; no `catch (_`, no `!!`, no `Log.`/`println`; imports checked heuristically; all of `NovaService.kt` and `NovaAccessibilityService.kt` re-read line by line |
| Secret scan (CI patterns) | nothing found |
| Physical-device testing | **NOT DONE** |

So: the code is **not** verified as "fully working". A compile error that a human reader and these scripts missed is still possible. The next step is to push to GitHub, run **Build and Test APK**, and send the failing log if it fails.

## Issues found and fixed

Voice pipeline
1. Wrong/unavailable Gemini model IDs in the rotation (`Logic.MODELS` now `gemini-3.5-flash`, `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`; 2.5 models excluded, shut down 2026-10-16).
2. Substring command matching ("time" matched "time zone", "volume fifty" fell through): replaced by whole-word `Logic.classify` with number-word parsing, because Vosk returns words, never digits.
3. TTS race: the microphone could hear NOVA's own voice or stay blocked forever. Now per-utterance ids, `ttsActive` is set before `speak()`, a watchdog deadline, and the answer window opens only after the confirm prompt finished.
4. Mic loop: read errors looped forever (now abort after 5), `n == 0` busy loop, native `UnsatisfiedLinkError` escaped (`catch Throwable`), recognizers not reset after a blocked period, silence thresholds too slow (600 ms end silence, Vosk endpoint ends capture early, 12 s hard cap, 3 s no-speech cap).
5. Service lifecycle: two mic loops could overlap, `START_STICKY` restarted silently, loop failure left the app "ON". Now `START_NOT_STICKY`, previous thread joined, per-instance `alive`, cleanup + `stopSelf()` on failure, corrupt model directory deleted and rebuilt.

Phone tools (every tool now returns a real result)
6. Volume "set" went into the "raise" branch. Fixed; volume and brightness are read back.
7. Torch verified with a `TorchCallback`; media verified with `isMusicActive`; global actions return Android's own result; brightness sets manual mode and reads back.
8. Android 10+ silently blocks background activity launches. `launch()` now verifies via `uiVisible` or a foreground-package change; otherwise says "requested, not confirmed" and posts a tap-to-open notification.
9. `open_url` accepts only http/https; alarm/timer/calendar ranges validated; no `!!`.
10. `CALL_PHONE` removed: a call only opens the dialer (`ACTION_DIAL`). `QUERY_ALL_PACKAGES` / overlay permission are not used (`<queries>` MAIN/LAUNCHER only).
11. Brightness no longer opens a permission screen on a voice command; the user taps ALLOW BRIGHTNESS in the app. Contacts permission is requested only from the app UI.

Confirmation and security
12. A local "yes" still triggered a cloud call and the cloud could approve actions. Removed: approval is only `Logic.parseAnswer == true`; unclear answers count as no; pending confirmations expire after 30 s; a new wake or "stop" cancels them.
13. WhatsApp auto-send: restricted to `com.whatsapp` / `com.whatsapp.w4b`, exact message match, 15 s timeout, armed only after the "yes".
14. History stored and re-uploaded base64 audio every turn: now text only, bounded by `Logic.boundHistory`; audio is attached only for the current turn.
15. Fake/unsafe `screen_read` / `analyze_screen` cloud tools removed. Analysis runs only on the explicit "analyze screen" command, local summary first, cloud only with a key, password-free, non-editable, at most 6000 characters.
16. Keys: multi-key pool encrypted with Android Keystore; migration deletes the legacy single/plain copy; `clearKeys` wipes everything; key is sent in a header (never in a URL), never logged, never sent back to the page (`saveKeys` returns only "ok:N").
17. Cloud: every request has timeouts (8 s connect, 25 s read) and `disconnect()`; deterministic models x keys failover with `Logic.nextStep`, max 12 attempts, 45 s deadline; 598 (offline) stops immediately; tool loop bounded to 6 steps and 8 tool calls.
18. Screen monitoring: default OFF, local, debounced (1 s debounce, 3 s minimum gap), alert spam guard, event mask widened only while monitoring or a WhatsApp send is armed; turning it ON requires Accessibility (`need_acc` otherwise).
19. WebView hardened: no file/content access, navigation and sub-requests outside `file:///android_asset/` blocked, JS interface removed on destroy.
20. Permission flow: `onRequestPermissionsResult` now continues startup (previously the user had to tap ACTIVATE again) and never loops on denial.

Build, tests, docs
21. Old `LogicTest` asserted the wrong model names; fully rewritten for the new `Logic`.
22. `build.yml`: grep matched a non-existent model; now also runs a secret scan, forbidden-permission/`catch (_`/`!!` checks, model size check, `ui.html` syntax check, and verifies the APK contains the model, `ui.html`, `classes.dex` and `libvosk.so`.
23. README claimed unverified model IDs and listed alarm/timer/SMS as local; corrected (they need a key and say so). UI now shows "LOCAL MODE - no API key needed" and marks keys optional.
24. Removed stale handoff material (`PART1B_SPEC.md`, `NEXT_ACCOUNT_PROMPT.md`, `reference/`).

## Known limitations (documented, not bugs)

- English-only offline recognizer; confirmations are "yes/yeah/yep/okay/sure/haan/han/ji". Hindi in Devanagari is not recognized offline.
- No barge-in while NOVA is speaking (the mic is deliberately off); use CHUP.
- Wi-Fi/Bluetooth/airplane mode cannot be toggled by apps: NOVA opens the settings page and says so.
- Alarm/timer/calendar/SMS/calls hand over to other apps; NOVA reports "requested", the user presses the final button. Calls, SMS, WhatsApp, alarm, timer, calendar, URL, maps, YouTube and web search need a Gemini key; for those the recorded audio of that one command is uploaded.
- Screen analysis = accessibility text only: no images/OCR; games, canvas and secure screens are unreadable.
- `Logic.pickContact` falls back to the shortest name when no word matches. It is only fed results of a `LIKE '%name%'` contact query, so every candidate already contains the spoken text.

## Device-only things that cannot be proven without a phone

Vosk wake accuracy and false triggers (wake detection uses partial results), VAD/noise thresholds, whether each OEM allows the background launch (the "requested, not confirmed" path exists for this), torch from the background, media-key routing to the active player, WhatsApp view ids / send-button lookup, Keystore behaviour after lock-screen changes, foreground-service start rules on Android 14/15 vendors, first-start model copy time (~40 MB).

## Install and test the APK

1. Push the repo to GitHub, open **Actions > Build and Test APK > Run workflow**. If it fails, send the failing log.
2. Download the `nova-apk` artifact, unzip, copy `app-debug.apk` to the phone.
3. Enable "Install unknown apps" for the app you opened it with, install.
4. Open NOVA, allow **Microphone** (and Notifications), tap **ACTIVATE**. First start copies the model, so wait for "Listening".
5. Say "nova", wait for the beep, then e.g. "battery", "torch on", "volume fifty".
6. Optional Accessibility: Settings > APP INFO > three dots > **Allow restricted settings**, then ACCESSIBILITY ON KARO and enable "NOVA Phone Control".
7. Optional: ALLOW CONTACTS, ALLOW BRIGHTNESS, Gemini keys.
