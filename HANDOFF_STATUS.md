# NOVA handoff status (updated 2026-10-09)

Everything below was written in a sandbox WITHOUT a Kotlin compiler, Android SDK or network.
**Build: NOT RUN. Unit tests: NOT RUN. Phone: NOT TESTED.** Status ladder: STATICALLY VERIFIED at best.
Python tooling (tools/test_self_improve.py, tools/build_feed.py offline part) WAS run here: LOCAL RUNTIME VERIFIED for those scripts only.

Built (code written, never compiled):
- Original NOVA 6.0, Step 1 floating card (NovaHud), Step 2 screen control (Control, NovaAccessibilityService),
  Step 3 Layer-1 brain (Brain, BrainStore), Step 4 Discover-Ask-Add updater (Updater, UpdateManager, InstallReceiver).
- A (partly): static review of the 5 suspicious spots. Only one change was made: BrainStore lambdas now have explicit `: Unit`.
  The real "build green" still needs a GitHub run.
- B: app/build.gradle.kts signing via env vars (NOVA_KEYSTORE_PATH/PASSWORD, NOVA_KEY_ALIAS/PASSWORD, NOVA_VERSION_CODE);
  build.yml loads secrets; make-keystore.yml creates the key once; release.yml publishes nova-vc<N>.apk.
- C: tools/build_feed.py + feed.yml (skills from skillpacks/, newest release apk, models from tools/models.json via Hugging Face);
  skillpacks/starter.json. tools/models.json is EMPTY (no model chosen yet).
- D: tools/self_improve.py + self-improve.yml (PR only, protected paths, max 3 files, tests must pass);
  SafetyInvariantsTest.kt; tools/test_self_improve.py (also run in build.yml).
- E: LocalBrain.kt (interface + prompt + strict output parser), LocalBrains.kt (slot, 8 s timeout), hook in NovaService.handle()
  after brain.resolve and before the cloud. A model suggestion of "tap" always asks a spoken yes. NO inference engine yet
  (LocalBrains.factory is null, so behaviour is unchanged). LocalBrainTest.kt written.

Next action: follow SETUP_STEPS.md step 1, run "Build and Test APK", fix whatever the log reports.

## Feature 6: Driving mode (added 2026-10-09, code written, NEVER compiled or run)
Files: Driving.kt (pure rules), NovaNotificationService.kt (NotificationListenerService + DrivingBridge), manifest service entry,
Logic.classify hook (Driving.command), NovaService (onDrivingMessage, driveOn/Off/Read/Clear/Reply, runCommand branches), DrivingTest.kt,
SafetyInvariantsTest extended.
Behaviour: voice "driving mode on" (needs Notification access switched on by the user in Android settings; NOVA opens that screen).
New messages from WhatsApp / WhatsApp Business / Telegram / Messages are read aloud. Voice "read messages", "clear messages",
"reply driving|busy|okay|thanks|later" -> NOVA says the exact fixed text and sends it through the notification's own Reply button ONLY after a
spoken local "yes" (askFirst). Messages with codes/OTP/password words are never read. Text stays in memory only (last 8), is never logged or
uploaded, and is wiped on "driving mode off" and when the service stops. Driving mode is OFF after every restart. drive_* kinds are NOT in
Brain.SAFE_KINDS, so shortcuts and the offline model can never trigger them.
Not built: free dictation of a reply (needs a capture-next-utterance mode in NovaService), an on/off button in ui.html, other apps.
Status: STATICALLY VERIFIED at best. Check first if the build fails: NovaNotificationService.kt (Notification.Action / RemoteInput calls),
the new NovaService methods, DrivingTest expectations against Logic.classify.

## Feature 10: blue Transformer UI v2 (2026-10-09, browser-tested only, NEVER compiled for Android or run on a phone)
ui.html rebuilt: full-screen canvas pod (10 plates incl. wings, bevels, rivets, light strips, sheen) assembles -> opens -> core boots -> dashboard;
synthesized sound (WebAudio, no files) + phone vibration (Android.vibrate); close/standby sequence (power button or Back); metal doors for Settings;
feature cards open a clickable sheet; globe dashboard + decorative voice waveform (NOT the real mic level) + greeting card.
Kotlin changes (check first if the build fails): MainActivity.kt -> hardenWebView (mediaPlaybackRequiresUserGesture, domStorageEnabled),
onBackPressed() (calls novaBack() in the page, moveTaskToBack on 'exit'), vibe() + Bridge.vibrate(); AndroidManifest.xml -> VIBRATE permission.
Status: LOCAL RUNTIME VERIFIED for ui.html in headless Chromium only. TARGET PHONE VERIFIED needed (sound, vibration, Back button, animation speed).

## Feature 11: floating wake card in pod style (2026-10-09, code written, NEVER compiled)
NovaHud.kt: metallic plates with inner panel / light strip / rivets, seam flare (HudMath.seamFlare + test), core badge with spinning arcs,
blue waveform, one vibration tick when a new reply appears. No wake-time sound/vibration on purpose (mic would hear it). Needs overlay permission.
Handover prompt for the next chat: NEXT_ACCOUNT_HANDOVER_PROMPT_v7.md. Next action is still: run "Build and Test APK" and fix the log.

## Feature 12: sleep-by-default + Update Center (2026-10-09, code written, NEVER compiled; ui.html tested in headless Chrome with a MOCK bridge only)
User asked: NOVA must be active only after the wake word, do the task, then go back to sleep (nothing unnecessary); and after app launch a section
that shows what was updated / what is to be done, asks permission and does it there.
Sleep (NovaService.kt): new mode "sleep". After every turn finishTurn() -> armSleep(card hold + 0.6 s) -> goSleep(): waits while busy/listening/speaking/
a yes is pending, then clears chat memory + last command, hides the card, sends mode "sleep" (UI dims, status "Sleep mode - 'nova' bolo to jaagunga").
"Heard nothing" after a wake also sleeps after 2.5 s. startCapture() cancels the sleep timer. Service start now begins in "sleep".
withNudge ("shortcut bana du?") is now OFF by default (Cfg.nudges) because it opened the mic by itself after commands.
Update Center (ui.html + MainActivity bridge getStatus / requestBasics / openNotificationAccess; "resumed" flow event from onResume):
opens by itself ONCE per launch after the intro only if: new version not yet seen, mic/notification permission missing, or a new update offer exists.
Shows: what is new (CHANGES array in ui.html - edit it every release), permission list with ALLOW buttons, offers with INSTALL/SKIP, and one big
"next step" button (first missing must-have permission -> install permission -> install first offer -> set feed URL -> check). Also reachable from the Safe Updates card.
Nothing installs without the user's tap; the existing Updater safety checks (https, host list, SHA-256, size, same signature) are unchanged.
FOUND + FIXED in CI: build.yml ui.html syntax check used a greedy regex over TWO script blocks (would have failed); now checks each block.
FOUND + FIXED: `catch (_: Throwable)` in MainActivity.kt (vibe) and NovaHud.kt would have been rejected by the build.yml "catch (_" guard.
If the build fails here, check first: NovaService.goSleep/armSleep, MainActivity.getStatus (canRequestPackageInstalls, DrivingBridge.listenerEnabled), REQ_BASIC.
Status: STATICALLY VERIFIED at best. Phone test needed: does NOVA still wake reliably, does the card hide, does the Update Center open once and the ALLOW buttons work.

## Feature 13: default assistant option (2026-10-09, code written, NEVER compiled or run)
NovaAssist.kt (NovaInteractionService, NovaSessionService/NovaSession, NovaRecognitionStub), res/xml/voice_interaction.xml, 3 manifest service entries,
NovaService.wakeNow() + wakeRequest (audio loop starts a capture like the wake word), MainActivity: getStatus "assistant", openAssistantSettings(), isDefaultAssistant().
Update Center row "Default assistant" opens Android's Digital assistant app screen; the user must pick NOVA there (an app cannot set it by itself).
NOVA does not use screen/assist data from this door. If the build fails, check first: NovaAssist.kt (VoiceInteractionSession ctor, RecognitionService.Callback override signatures).
Phone check needed: does NOVA appear in the assistant list, does long-press Home/power wake it.

## Autopsy v8 (2026-10-10) - 4 fixes written, pure-Kotlin part COMPILED + TESTED here, Android part NOT compiled
Verified in the sandbox with kotlin-compiler-embeddable 2.0.21 + JUnit: Logic, Brain, Control, Driving, HindiRoman, LocalBrain, Updater and 8 test classes
= 116 tests, all green (incl. new ListenTest.kt). ui.html: node --check passed (5 script blocks). CI guards (`!!`, `catch (_`) clean.
NOT compiled here (no Android SDK): NovaService.kt, MainActivity.kt, HealthCheck.kt. Check these first if the GitHub build is red.
1. Smart end of speech: Logic.fastEndMs + NovaService.audioLoop (`fastDone`). A complete simple command now runs after ~1.2 s of silence
   (open_app ~1.8 s) instead of the 5 s wait. Not used for yes/no answers, dictated replies, typing, taps. The user's wait setting still applies to everything else.
2. Custom wake word: Logic.setWake()/stripWake()/classify now strip the chosen wake word (and "nova") from the start of a command.
3. Voice "unlock phone": Logic kind "unlock" -> NovaService.unlockPhone() -> PatternUnlock.run. Works only if UNLOCK ON in the NOVA Pattern screen
   and Accessibility is on. NOT in Brain.SAFE_KINDS (a shortcut / offline model can never unlock). Anyone who says the wake word can unlock: user's choice.
4. Battery: new Update Center row "Battery: No restrictions" (Bridge.openBatterySettings, status key "battery", REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
   and HealthCheck now treats it as important.
Phone test needed: say "nova torch on" - reaction in ~1.5 s; "nova go home"; custom wake word + "open youtube"; "nova unlock phone" with phone locked.
Still open (needs a phone/CI test, see NEXT_ACCOUNT_PROMPT_v8.md): Hindi listening model, unused model-en-in, Hindi yes/no.

## v9 (2026-10-10) - listening quality, part B(a)
- NovaService: command recognizer `cm` now uses assets/model-en-in (Indian English) via loadCommandModel(); wake recognizer `wk`, resolveWake and words.txt stay on model-en.
  Any failure while loading model-en-in (missing asset, corrupt copy, native error) -> returns null and `cm` falls back to model-en. model is closed in finally.
- Verification: sandbox compile + 116 JUnit tests OK (pure logic unchanged). NovaService change = STATICALLY CHECKED ONLY (no `!!`, no `catch (_`); GitHub build NOT RUN yet; NOT phone tested.
- Risk to check on phone: RAM (two Vosk models loaded), start-up time, and whether commands are recognised better than before. If the app crashes on ACTIVATE, revert this one change.

## v9b (2026-10-10) - glass popup, app theme, strict wake
- NovaHud.kt: new "holographic glass" card (glowing orb, state colours, waveform, reply text, pop-in/out). Public API unchanged. HudMath maths = JUnit tested (sandbox). Drawing code = NOT phone tested.
- ui.html: added <style id="theme-v2"> (colours/shapes only, no ids/scripts changed). node --check OK on all 6 script blocks; headless-Chromium screenshot checked. Note: JS overwrites #app className, so CSS must use #app, not .wrap.
- Strict wake: Logic.nextWakeHits/wakeConfirmed (wake word must repeat in 2 consecutive results unless it is a final result) + Logic.isFalseWake (nothing heard, or noise only in offline mode -> silent sleep, no spoken error, no card). Gates kept: loudness gate, 1.5 s cooldown. Yes/no answers and dictation unchanged.
- Verification: sandbox 122 JUnit tests OK (116 + 6 new) + 12 HUD tests OK. GitHub build NOT RUN. Phone test NOT DONE. Check: false wakes with TV/noise, normal "nova torch on" still wakes first time, card look on a real phone.

## v15b (2026-10-10) - Layer 0 offline skills (6B step 1)
- NEW LocalSkills.kt (pure Kotlin): calculator (plus/minus/times/divided by/percent of/square/root, digits or spelled numbers incl. lakh/crore/point), unit conversion (length, mass, volume incl. cup/tbsp/tsp, C<->F), date maths ("45 din baad kya tarikh", "10 days ago", weekday of today/tomorrow), emergency numbers India (112/100/101/102/108/1091/1098/1930), first aid text for burn / nosebleed / bleeding / snake bite (general info, always "doctor/112", no dosing).
- Routing: NovaService.handle() calls LocalSkills.answer() FIRST (before Logic.classify). Every skill is strict (numbers must parse fully, units known, topic known) so it returns null and normal routing continues for anything else. It only produces text, never acts on the phone.
- Not done yet from step 1: timers/alarms/reminders (needs AlarmManager + exact-alarm OK), local notes/to-do list, festival/holiday list, contacts lookup. Chains like "5 plus 6 plus 7" are not supported on purpose.
- Verification: sandbox compile + 136 JUnit tests OK (122 old + 14 LocalSkillsTest). node --check OK on 6 script blocks. `!!` and `catch (_` guards clean. NovaService change = STATICALLY CHECKED only. GitHub build NOT RUN. Phone NOT TESTED.
- Phone test phrases: "nova fifteen percent of two hundred", "nova 5 kg to pounds", "nova forty five din baad kya tarikh", "nova police number", "nova first aid for burn", and check "nova torch on" / "volume fifty" still work.

## v15c (2026-10-10) - offline timer / reminder / alarm (6B Layer 0, part 2)
- FOUND: timer/alarm existed only as Gemini tools (cloud), so they did not work offline. NEW Timers.kt (pure): "timer" (seconds), "alarm" (minute of day), "alarm_any" (no am/pm said: next matching time, resolved at run time). Reminder = timer whose label is the thing to remember. Hooked in Logic.classify (before Driving) and NovaService.runLocalTool -> existing timerTool/alarmTool (AlarmClock intents: the system Clock app rings, works while NOVA sleeps, no exact-alarm permission). Replies now say the real duration / clock time. Android gives no confirmation: the reply says so.
- Not supported on purpose: cancel/delete alarm (no safe Android API), chains, timer > 24 h, reminders without a duration ("remind me at 5" = use alarm).
- Two old LogicTest asserts changed on purpose (they expected timer/alarm to go to the cloud).
- Verification: sandbox 144 JUnit tests OK (136 + 8 TimersTest). Guards clean, node --check OK. NovaService edit STATICALLY CHECKED only. GitHub build NOT RUN, phone NOT TESTED.
- Phone test: "nova set timer for five minutes", "nova do minute ka timer laga do", "nova das minute baad chai yaad dilao", "nova set alarm for six thirty am", "nova alarm laga do six baje" (check Clock app for each; misheard numbers are possible, read the reply).

## v16 (2026-10-10) - offline AI infrastructure, PURE parts only (Android wiring NOT done)
Verified in sandbox: 166 JUnit tests OK (compile list: Logic Brain Control Driving HindiRoman LocalBrain Updater LocalSkills Timers ModelDownload ModelCatalog LocalChat + 14 test classes). No Android file was edited in v16 except Updater.kt (pure). GitHub build NOT RUN, phone NOT TESTED.
NEW pure files (all unit tested, NOT yet called from the app):
- ModelDownload.kt: resumable (Range) + verified (size, SHA-256) + redirect-checked download. Tested against a local HTTP server: full, redirect, interrupted+resume, server ignores Range, wrong hash (file deleted), too big (deleted), low storage (refused before any request), blocked host, 404, cancel+resume, oversized/complete .part.
- ModelCatalog.kt: ITEM = Qwen2.5-0.5B-Instruct Q4_K_M, official repo Qwen/Qwen2.5-0.5B-Instruct-GGUF, licence apache-2.0, size 491400032, sha256 74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db (read twice from the HF git-lfs pointer through WebFetch; if a download ever fails with "checksum mismatch" re-check this value first, the app fails safe), MIN_TOTAL_RAM_MB 3000.
- LocalChat.kt: ChatML prompt (Qwen format) with persona, output cleaner (template/markdown/loop/length), preAnswer (live-info questions never guessed; crisis words get a fixed safe answer with 112 + Tele-MANAS 14416), guard (health/money/legal safety line). AiRouter: plan(cloudConfigured, cloudCooling, localReady, text) = cloud first for real questions, local first for small talk, cooldownMs per failure kind.
- Updater.hostAllowed now also accepts *.hf.co and *.huggingface.co (HF changes CDN hosts; SHA pin protects content).
Owner's master prompt saved as OWNER_MASTER_UPGRADE_PROMPT.md. See NEXT_ACCOUNT_PROMPT_v12_FULL.md for the exact remaining steps.

## v16b (2026-10-10) - STEP 1 wiring: model download + offline-brain routing (no engine yet)
- UpdateManager.doInstall: type "model" now uses ModelDownload.download (resumable, SHA-256 + size, host allow-list, free-space check). A stopped download keeps the .part file ("Tap again to resume"); checksum/size failures delete it. skills/apk keep the old loop. refresh() keeps the built-in offer.
- UpdateManager.offerOfflineBrain(ctx) validates ModelCatalog.ITEM and adds it to the offers (nothing downloads until the user taps). Bridge: offerOfflineBrain(); getStatus adds engine, ramMb, ramOk, brainInstalled.
- ui.html Update Center: new section OFFLINE BRAIN. Download button only if engine present AND ramOk; otherwise it says why. HATAO = rollbackUpdate (deletes the model). Built-in model is hidden from the normal UPDATES list and from auto-open.
- LocalBrain.close() default; LocalBrains: available(), release(), single busy flag (second request refused), chat() = LocalChat prompt + clean + guard. NovaService.onTrimMemory(>= RUNNING_LOW) and onDestroy release the model.
- NovaService.handle: junk never reaches any model (also not the translator). Order via AiRouter.plan + new pure Routing.run: cloud failure -> cooldown (AiRouter.cooldownMs) -> local chat; if a cloud tool already acted, no local answer is added; local answer is TEXT ONLY, prefixed "Offline:". No source -> the old honest local-mode message.
- Tests: RoutingTest (9 cases). NOT RUN: this sandbox had no Kotlin compiler.
- Verification level: STATICALLY CHECKED only (re-read, brace balance, `!!` / `catch (_` guards clean, node --check OK on 6 script blocks). Unit tests NOT executed. GitHub build NOT RUN. Phone NOT TESTED.
- Engine still missing (LocalBrains.factory == null) so the download button stays hidden today. Next: STEP 2 (one Gradle dependency + LlamaBrain.kt).
