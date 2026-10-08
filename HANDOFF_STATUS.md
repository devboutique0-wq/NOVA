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
