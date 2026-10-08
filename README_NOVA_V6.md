# NOVA 6.0 - Local-First Phone Agent

NOVA is an Android voice assistant. Wake word and command recognition are offline (Vosk). Common phone commands run on the phone with **no API key**. A Gemini key is optional and only used for open-ended questions, web search and an explicitly requested deep screen analysis.

> **Build status: NOT RUN.** This source tree has never been compiled and the unit tests have never been executed (the repair sandboxes had no Kotlin compiler, Android SDK or network). It has also **not been tested on a physical phone**. Push to GitHub and run the "Build and Test APK" workflow to find out; see "Build".

## What NOVA does

- **Local commands (no key, no network):** battery/charging, time, date, torch, volume (up/down/mute/max/set a number), brightness, media pause/play/next/previous, Home/Back/Recents/Notifications/Quick Settings/Lock, open any installed app, camera, Wi-Fi/Bluetooth/other settings pages, screen monitor on/off, analyze screen (local summary).
- **Needs a Gemini key (understood by the cloud, executed on the phone):** calls, SMS, WhatsApp messages, alarms, timers, calendar events, open a URL, maps navigation, play a song on YouTube, web search, and any free-form question. For these (and only these) the recorded audio of that one command is uploaded to Gemini. Without a key NOVA says it needs one. Local commands never upload anything.
- **Real results:** every tool reports what actually happened. NOVA only says "done" when it checked (volume and brightness are read back, torch state is observed, media uses `isMusicActive`, global actions return Android's own result).
- **Calls and WhatsApp need a spoken "yes".** A call only opens the dialer (`ACTION_DIAL`; no CALL_PHONE permission) and you press call yourself. WhatsApp auto-send runs only after a local "yes", only inside WhatsApp, and only for the exact confirmed message. The cloud is never asked to decide yes/no. Anything that is not a clear "yes" counts as "no". A confirmation expires after 30 seconds.
- **App launching is verified.** Android 10+ can silently block activities started from a background service. NOVA checks that the app really came to the front. If it cannot confirm, it says **"requested, not confirmed"** and posts a notification you can tap to open the app. It never claims "opened" without proof.
- **Screen monitoring: OFF by default, local only.** When you turn it on (needs Accessibility) NOVA watches visible UI text for obvious error/warning wording and announces it, with debounce and a spam guard. Nothing is uploaded.
- **"Analyze screen":** a local summary of the accessible text first. Only with a saved key does NOVA also send the text to Gemini, and only when you ask: password fields and editable fields are excluded, at most 6000 characters. It reads accessibility text only: **no images, no OCR**; games, canvas, video and secure screens may be unreadable.
- **Cloud failover (optional):** with one or more of *your own* keys, NOVA tries models then keys in a fixed order on quota/server/model errors (max 12 HTTP attempts, 45 s total). This is failover, not a way around provider limits. If the cloud fails, local commands keep working. Keys are AES-GCM encrypted with the Android Keystore and are never shown again, logged, or put in this repository.
- **Boot:** after a restart NOVA only shows a notification; one tap opens the app and starts the microphone service (Android does not allow starting it silently).

Model IDs tried, in order: `gemini-3.5-flash`, `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`. They were taken from Google's documentation in October 2026, but model availability changes; an unavailable ID is skipped on a 404.

## New in this version (not yet compiled or tested)

- **Floating card:** after the wake word a card assembles from steel plates over any app (optional "Display over other apps").
- **Screen control (needs Accessibility):** "tap <label>", "click <label>", "<label> dabao", "type <text>", "scroll/swipe up|down|left|right". Risky labels (send, pay, delete, buy, ...) ask for a spoken "yes" first. tap/type are refused in the package installer, permission screens, system Settings, Play Store, payment/bank apps and NOVA itself. Password boxes are never typed into. After a tap NOVA says whether the screen visibly changed, never just "done".
- **Memory (Layer 1):** NOVA remembers shortcuts on the phone only. If you say an unknown phrase and then a known command twice, it asks once (max every 6 h) whether to remember it. Shortcuts can only contain built-in safe commands, never tap/type/call/message. Fuzzy matching never merges different numbers. CLEAR MEMORY wipes everything.

## Limits you should know about

- **English-only confirmation:** the offline recognizer is an English model. Spoken confirmations are "yes", "yeah", "yep", "okay", "sure", plus "haan", "han", "ji". Hindi in Devanagari is not recognized; Hinglish commands work only as far as the English model hears the words.
- **No barge-in:** while NOVA is speaking the microphone is deliberately not listening. Use the CHUP button (or wait) to interrupt.
- **Wi-Fi/Bluetooth/airplane mode:** Android does not let apps toggle them, so NOVA opens the settings page and says so.
- **Alarm and timer** (key needed) are handed to the system clock app without a confirmation screen NOVA can observe, so NOVA says "requested" rather than claiming it is set.
- **Contacts:** "call/message <name>" needs the contacts permission. Tap ALLOW CONTACTS in Settings. Saying a phone number needs no permission.
- **Brightness** needs "Modify system settings". Tap ALLOW BRIGHTNESS in Settings.
- Accessibility is optional. Without it, navigation commands (Home/Back/...), WhatsApp send, screen monitoring and launch verification by foreground app are unavailable; everything else works.
- Android permissions are requested only from the app screen after you tap something, never from a voice command or in the background.
- No `QUERY_ALL_PACKAGES`, no `CALL_PHONE`. The floating NOVA card (assemble animation) uses the optional "Display over other apps" permission (tap ALLOW POPUP in Settings); without it NOVA works exactly as before, just without the card.

## Build

The Vosk model (~40 MB) is not in this archive. The workflow `.github/workflows/build.yml` does the following, in order:

1. scans the source for key/secret patterns, forbidden permissions, `catch (_` and `!!`;
2. downloads `vosk-model-small-en-us-0.15`, checks `graph/words.txt`, `am/final.mdl` and its size;
3. syntax-checks the script in `ui.html` (`node --check`);
4. runs `gradle testDebugUnitTest assembleDebug` (Gradle 8.7, JDK 17, AGP 8.5.2, Kotlin 1.9.24);
5. verifies the APK contains the model, `ui.html`, `classes.dex` and `libvosk.so`.

Result: the APK is uploaded as the `nova-apk` artifact. If something fails, download the log (or the `test-report` artifact) and fix from that.

For a manual local build: download the same Vosk model, extract, rename the folder to `model-en`, put it in `app/src/main/assets/`, then run `gradle testDebugUnitTest assembleDebug` with the Android SDK installed.

## Install and first run

1. On the phone enable "Install unknown apps" for your browser/file manager and install `app-debug.apk`.
2. Open NOVA, allow **Microphone** (and Notifications if asked).
3. Tap **ACTIVATE** (the badge says "LOCAL MODE - no API key needed").
4. Say "nova", wait for the beep, then a command, e.g. "battery", "torch on", "volume fifty", "open youtube".
5. Optional Accessibility: Settings > APP INFO > menu (three dots) > **Allow restricted settings**, then ACCESSIBILITY ON KARO and switch on "NOVA Phone Control".
6. Optional: ALLOW CONTACTS, ALLOW BRIGHTNESS, paste Gemini keys (one per line).

## Manual smoke tests (not yet done)

- Wake: "nova" > "battery", "time", "torch on", "volume up", "volume fifty", "open youtube".
- Navigation (Accessibility on): "home", "back", "recent apps", "notifications", "quick settings".
- Launch honesty: lock the phone or use a strict-OEM phone, say "open whatsapp"; expect either "opened" or "requested, not confirmed" plus a tap notification.
- Monitor: "monitor on", open an app showing an error, expect one announcement; "monitor off".
- Analyze: "analyze screen" with no key gives a local summary; with a key, a Gemini answer.
- Calls/WhatsApp (key needed): ask to call a number; NOVA must ask first; say nothing or "maybe" and nothing happens; say "yes" and the dialer opens.
- Failover: two keys, one invalid; the second is used. Remove all keys; local commands still work.
- Model missing/corrupt: NOVA must show a setup error instead of pretending to listen.
