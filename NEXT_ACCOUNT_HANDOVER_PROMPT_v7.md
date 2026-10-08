# NOVA - handover prompt v7 (paste this into the next Claude chat together with NOVA_v13_transformer_hud.zip)

You are continuing an existing Android project called NOVA (Kotlin, Gradle, Vosk offline wake word, foreground mic
service, WebView UI in app/src/main/assets/ui.html). Read HANDOFF_STATUS.md, README_NOVA_V6.md and AUTOPSY_REPORT.md first (HANDOFF_STATUS.md is the most current).
Do NOT restart the project and do NOT recreate what exists. Follow: ADD -> TEST -> INTEGRATE -> REGRESSION TEST -> VERIFY.

## About the user
- Not a programmer. Replies in Hinglish, direct, no theory. ONE step at a time: give the next exact action, then wait for pasted output.
- Never claim DONE / WORKING / VERIFIED without evidence. Use this ladder: ARCHITECTURE ONLY -> STATICALLY VERIFIED -> AUTOMATED TEST VERIFIED -> LOCAL RUNTIME VERIFIED -> TARGET PHONE VERIFIED -> END-TO-END VERIFIED.
- When the user pastes a log: name the exact root cause and say whether it is a project bug or a user command-entry mistake.
- Whenever the user must type commands in Windows, remind them: do not copy the "PS D:\...>" prompt.
- Do not ask the user to re-send the project; ask for the zip only if it is not attached.

## Current state (written WITHOUT a Kotlin compiler / Android SDK / network, so status = STATICALLY VERIFIED at best)
All five planned steps now exist in the zip, but NOTHING has been compiled, unit-tested or run on a phone yet:
1. Original NOVA 6.0, floating card (NovaHud), voice screen control (Control, NovaAccessibilityService), Layer-1 brain (Brain, BrainStore),
   Discover -> Ask -> Add updater (Updater, UpdateManager, InstallReceiver).
2. B signing: app/build.gradle.kts reads NOVA_KEYSTORE_PATH / NOVA_KEYSTORE_PASSWORD / NOVA_KEY_ALIAS / NOVA_KEY_PASSWORD / NOVA_VERSION_CODE;
   workflows: build.yml (loads secrets), make-keystore.yml (run once), release.yml (publishes nova-vc<N>.apk, then refreshes the feed).
3. C feed: tools/build_feed.py + .github/workflows/feed.yml, skillpacks/starter.json, tools/models.json (EMPTY: no model chosen).
   Note: the phone can only read feed.json if the repo is public (or the feed is hosted elsewhere).
4. D self-improve: tools/self_improve.py + self-improve.yml (workflow_dispatch, PR only, protected paths, max 3 files, tests must be green),
   SafetyInvariantsTest.kt, tools/test_self_improve.py. The Python guard tests WERE run in the sandbox and pass.
   Limit: SafetyInvariantsTest covers the pure rule objects, not NovaService wiring, so the owner must still read every PR.
5. E offline model slot: LocalBrain.kt (interface, prompt builder, strict one-line output parser), LocalBrains.kt (slot, 8 s timeout),
   hook in NovaService.handle() after brain.resolve and before the cloud; a model-suggested "tap" always asks a spoken yes.
   NO inference engine is bundled (LocalBrains.factory == null), so behaviour is unchanged until one is added and compile-tested in CI.
Unit tests written, never run: LogicTest, ControlTest, BrainTest, HudMathTest, UpdaterTest, LocalBrainTest, SafetyInvariantsTest, DrivingTest.


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

## Feature 7: Driving dictated reply (added 2026-10-09, code written, NEVER compiled or run)
Voice: "reply likho" (also "jawab likho", "write reply", "custom reply"). Needs driving mode ON and a message with a Reply button.
Flow: NOVA asks "kya jawab bhejna hai?" -> beep -> ONE utterance is recorded (no wake word) -> NOVA reads it back
("... bhej du: '<text>'? Haan ya nahi") -> sent through the notification's Reply button ONLY after a spoken local "yes".
Files changed: Driving.kt (DICTATE_PHRASES, cleanDictation, hint text), Logic.kt (3 dictation timing constants),
NovaService.kt (dictatePending / dictTarget, driveDictate, dictatedReply, processAnswer branch, audio loop: dictCapture = longer
capture that does NOT stop at Vosk's first pause), DrivingTest.kt, SafetyInvariantsTest.kt.
Safety: drive_dictate is NOT in Brain.SAFE_KINDS (shortcuts / offline model can never trigger it). Dictated text is never shown in the
chat log, never saved, never uploaded; wiped on driving off / clear / stop / new wake word / service stop.
Known limit: the bundled Vosk model is ENGLISH, so Hindi/Hinglish words will be recognised badly. The read-back + yes/no protects the user,
but real Hinglish dictation needs a Hindi Vosk model (next step, not built).
Status: STATICALLY VERIFIED at best (no Kotlin compiler in the sandbox). If the build fails, check first: NovaService.processAnswer
(the new `isDict` branch inside `when`), the audio loop `dictCapture` lines, DrivingTest.dictatedReplyCommands.

## Fix 8: false wake / repeated command / missed words / quiet replies (2026-10-09, code written, NEVER compiled or run)
User report (phone): torch turns on by itself, the last command is repeated 5-10 min later, words are missed or heard late.
Suspected root causes (from reading code, NOT yet proven with a log - ask for a log to confirm):
 a) Wake grammar is tiny ["nova","hey nova","[unk]"] and was accepted on ANY partial result with no loudness check -> TV/room noise = false wake.
 b) A false-wake capture with noise went to the cloud (when an API key is set) together with the chat history -> the model "continued" the
    old command (torch). History never expired.
 c) startCapture() reset the command recognizer and dropped the first words spoken while the wake word was recognised.
Changes: NovaService audio loop (wake needs peak > max(400, 1.8*noise) and 1.5 s after the last turn; 5-chunk pre-roll fed to the command
recognizer), handle(): Logic.isJunk(text) never reaches the cloud/LLM, cloud history expires after 120 s, system prompt says never repeat an old
command and do nothing on unclear audio.
Quiet replies (default ON, Cfg.quietReplies): command replies appear only in the chat / floating card. SPOKEN: yes/no confirmations (all risky
actions), driving mode (drive_*), "read the screen", quiet_on/quiet_off, and every alert (announce / announceIfIdle: screen monitor, driving
messages, updates). Voice: "quiet mode on/off", "voice replies on/off". New: Logic.isJunk, Logic.speakReply, Logic.LOUD_KINDS, QuietTest.kt.
Risk to watch: the wake loudness gate may be too strict in a loud car (noise EMA is high). If NOVA stops hearing "nova" there, lower the 1.8 factor.
Status: STATICALLY VERIFIED at best.

## Feature 9: animated wake card (2026-10-09, code written, NEVER compiled or run)
User: when NOVA wakes in the background nothing is visible; wants a visible, strongly animated popup on wake.
Finding: NovaHud (floating card) already exists but is a SILENT no-op without the "Display over other apps" permission.
Changes: NovaHud.kt (card 206dp tall; motion layer: 3 ripple rings, 28-bar waveform that follows the mic level, scanner light band while
thinking; HudMath.barHeight / rippleAlpha / mix / holdMs), NovaService (mic level fed to the card during capture; card stays holdMs() so a
silent reply can be READ; overlayNudge(): once per start, if the permission is missing, a chat note + tap-to-fix notification),
HudMotionTest.kt. Visual quality can only be judged on the phone (TARGET PHONE VERIFIED needed).
Status: STATICALLY VERIFIED at best.

## Feature 10: blue Transformer UI v2 (app/src/main/assets/ui.html, 2026-10-09, tested ONLY in headless Chromium)
User wants the blue Transformer theme from a reference image (NOT gold; an earlier gold version was rejected). Full-screen canvas pod:
10 plates (incl. 2 wings; bevels, rivets, light strips, sheen) fly in and assemble -> seam beam -> panels open -> core boots with
"INITIALIZING AI CORE... %" -> dashboard (globe with orbit rings, decorative waveform that is NOT the real mic level, greeting card,
9 clickable feature cards that open a sheet with real Android.* bridge actions). Power button / Android Back plays the close sequence
(panels close, core seals, "SYSTEM STANDBY"); tap wakes it; Back in standby calls moveTaskToBack. Settings opens/closes with metal doors.
Sound is synthesized with WebAudio (no files): servo, clank, hiss, boom, chime; phone vibration through Android.vibrate. Speaker button
turns sound + vibration off (localStorage "nova_snd"). Tapping the intro skips it.
Kotlin touched for this (check first if the build fails): MainActivity.kt -> hardenWebView (mediaPlaybackRequiresUserGesture=false,
domStorageEnabled=true), onBackPressed() (evaluateJavascript novaBack()), vibe(), Bridge.vibrate(ms); AndroidManifest.xml -> VIBRATE.
The old robot canvas (#tf) was removed from ui.html; its IIFE exits early. JS must keep every id used by the settings panel code.

## Feature 11: floating wake card in pod style (NovaHud.kt, 2026-10-09, code written, NEVER compiled)
Home-screen wake: the card (HudView) appears over other apps ONLY with the "Display over other apps" permission (ALLOW POPUP in settings);
without it everything is a silent no-op (plus one overlayNudge notification). Changes: metallic plate shader (cached), raised inner panel,
light strip, rivets, blue seam flare as the plates join (HudMath.seamFlare, tested in HudMotionTest), glowing core badge with two spinning arcs,
blue waveform gradient, one 22 ms vibration tick when a NEW reply appears (NovaHud.tick). DELIBERATELY no sound or vibration at wake time:
the mic is open then and would hear it as speech (same family as Fix 8 false wakes). Do not add wake-time sound without a mic-echo guard.
If the build fails here, check: NovaHud.kt onDraw plate block (sh/pa/inRect), tick() (VibratorManager, Build import), HudMotionTest.seamFlare.

## What to do next (one step at a time)
A. FIRST (still not done!): get the build green. Follow SETUP_STEPS.md step 1, run workflow "Build and Test APK", fix every compile/test error from the log.
   Already reviewed statically (no problem found): NovaAccessibilityService companion -> private clickNode, GestureResultCallback, BrainStore
   synchronized/return@synchronized (made explicit `: Unit`), UpdateManager Session.use / signingInfo, InstallReceiver getParcelableExtra, NovaHud listener.
   New and equally unproven: LocalBrain*.kt, the hook in NovaService.handle(), both new test files (check test expectations against Logic.classify first
   if a test fails: the test may be wrong, not the code).
B-D. After the build is green: set the secrets (SETUP_STEPS.md 2-4), run make-keystore once, run Release APK, check that an in-app update installs over the old app.
E. Pick an offline engine (MediaPipe / LiteRT / llama.cpp) and a small open-licensed model, add the engine behind LocalBrains.factory,
   compile-test it in CI, add the model to tools/models.json. Only then claim it works.

After A: ask the user to test ON THE PHONE and report: opening animation speed, sound, vibration, Back-button close, home-screen wake card
(needs ALLOW POPUP). Possible follow-ups: Hinglish dictation (Hindi Vosk model), real mic level in the dashboard waveform, on/off button for driving mode.

## Hard rules (never change without the user's explicit approval)
- Risky actions (call, send, pay, delete, buy...) always need a spoken local "yes". Cloud/LLM output can never approve anything.
- tap/type are refused in package installer, permission screens, system Settings, Play Store, payment/bank apps and NOVA itself; never type into password fields.
- Nothing is downloaded or installed without the user's yes + https + allow-listed host + exact SHA-256 + exact size. No silent APK install; no downgrade.
- Skills/shortcuts are data only and may contain only built-in safe commands. NOVA never rewrites its own code on the phone.
- API keys never appear in logs, URLs, the repo, or the UI.
- UI look: blue Transformer theme only (no gold). Do not claim animations/sound "work" before the user confirms them on the phone.
