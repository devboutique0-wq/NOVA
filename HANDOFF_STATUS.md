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

## v16c (2026-10-10) - one launcher icon
- Owner saw 2 NOVA icons: PatternSetupActivity had its own LAUNCHER filter. Removed it (exported=false). New Bridge.openPatternSetup() + Update Center row "Pattern unlock setup" (status key "pattern" = PatternUnlock.isSet).
- Verification: node --check OK on 6 script blocks, `!!` / `catch (_` guards clean. Kotlin NOT compiled here, GitHub build NOT RUN for this change, phone NOT TESTED.
- Owner also reported: app loads, "gets stuck at the pattern stage but the pattern itself works" - not yet understood, asked the owner what exactly freezes.

## v17 (2026-10-10) - autopsy: real offline engine + two "stuck" bugs (Kotlin NOT compiled here, GitHub build NOT RUN, phone NOT TESTED)
FOUND (fake / dead):
1. Offline AI was a facade: LocalBrains.factory was null, so the OFFLINE BRAIN download button never showed and "Offline:" answers never happened. FIXED (written, not compiled): NEW LlamaBrain.kt (llama.cpp via io.github.ljcamargo:llamacpp-kotlin:0.4.0; calls copied from the library's demo MainViewModel.kt: LlamaHelper(contentResolver, scope, sharedFlow), load(path, contextLength){}, predict, stopPrediction, abort, release, events Ongoing/Done/Error). LocalBrains.installEngine(ctx) is called from NovaService.onCreate and MainActivity.onCreate. build.gradle.kts: that dependency + kotlinx-coroutines-core 1.8.1 + abiFilters arm64-v8a only.
2. Mic/Notification ALLOW button became dead after Android's "don't ask again": Update Center's big button kept pointing at it = looked stuck. FIXED: Bridge.requestBasics opens the app's settings page in that case (returns "settings", ui shows a note).
3. Update Center's big button said "UPDATE FEED URL DALO" forever when no feed is set (feed is optional). FIXED: it now says "SAB TAIYAAR - BAND KARO" and closes.
UNVERIFIED RISKS (check first if the build is red): (a) LlamaHelper member names/types come from the library's master-branch demo, not from the 0.4.0 AAR; (b) the AAR's minSdk could be above 26; (c) licence: README says MIT, Maven POM says Apache-2.0 (both permissive); (d) native code from a one-person project ships in the APK; (e) sampling settings are not exposed by the library. If red: delete LlamaBrain.kt, the 2 dependency lines and the installEngine calls = back to v16c behaviour.
Phone test: Update Center > OFFLINE BRAIN > DOWNLOAD (Wi-Fi, 491 MB) > "nova hello" > Wi-Fi off > "nova why is the sky blue" (note latency, heat). Permissions: deny mic twice, then tap ALLOW again = Settings page should open.

## v17b (2026-10-10) - lighting + open animations (owner request)
- ui.html: new <style id="lights-v1"> + #lt/#flash/#shock layers + lights(t) driven from the existing intro frame loop (beams, halo, anamorphic flare, white-cyan flash and shock ring at the reveal, intro fade 0.5s -> 0.2s), app "slam" entrance, light sweep, spring+glow popups (.ucb, .sh, #chat). All under prefers-reduced-motion:no-preference. Checked in headless Chromium with a mock bridge: no JS errors, frames look right. NOT phone tested (real GPU speed unknown; if janky remove .bm blur filters first).
- NovaHud.kt: HudMath.springEase / entryFlash (+3 tests in HudMotionTest.kt, maths checked in Python: peak 1.18, end 1.002), card opens in 560 ms with slide + spring, border flash, light sweep, shock ring (only while opening). Kotlin NOT compiled.

## v18a (2026-10-10) - Part 1A: free AI provider chain, PURE CORE only (Kotlin NOT compiled here, tests NOT run)
This sandbox had no kotlinc and no network. Status: STATICALLY CHECKED only (brace/paren balance with a real tokenizer, no `!!`, no `catch (_`, no TODO, no name clash).
NEW: app/src/main/java/com/nova/assistant/FreeProviders.kt (pure, no Android, no org.json):
- MiniJson (quote + strict parser, never throws, depth limit 40), FreeProvider, NetAnswer(code, body) [598 = no internet, 599 = timeout/IO], ChainResult, Cooldowns (clock passed in).
- FreeProviders: GROQ (llama-3.3-70b-versatile, llama-3.1-8b-instant), OPENROUTER (two ":free" models), POLLINATIONS (https://text.pollinations.ai/openai, no key); systemPrompt(en), buildBody, parseReply (choices[0].message.content; a 200 with an "error" body = failure), clean (no <think>, no markdown, cap 700 chars on a sentence end), kindOf, cooldownFor (key 10 min, quota 2 min, server 30 s, network 15 s, model 60 s, other 20 s), failText (honest Hindi/English).
- ProviderChain.run(providers, keyOf, enabled, system, user, cooldowns, now, budgetMs=14000, call): skip disabled / cooling / keyless-needed providers with NO call; 429/400/404/422 -> next model; 401/403/5xx/timeout -> next provider; 598 -> stop whole chain; useless 200 body = failure; budget stops the chain.
- NEW test: FreeProvidersTest.kt (28 tests). Written, NOT executed.
UNVERIFIED (check first if CI is red): (a) the three endpoint URLs and the model ids were written from public OpenAI-compatible docs and could not be called from here; free model ids change, the chain moves on by itself on 404/400; (b) Kotlin compile of FreeProviders.kt (`'\b'` char literal, `while`+`fail()` ending in Nothing-returning functions, `break` inside `when`-free loops) - if the compiler complains, fix that line only.
NOT DONE (this is part 1B): real HTTP caller (HttpURLConnection, returns 598/599, never throws), key slots in SecureStore + Settings UI (Groq key already exists via SecureStore.getGroq; OpenRouter needs a slot), NovaService wiring, crisis/live-info preAnswer + LocalChat.guard on the chain's answers, AiRouter.plan change (cloud is no longer "configured only with a Gemini key": Pollinations needs none), per-provider on/off, a Python mock-server run of the failover. Nothing in the running app calls the chain yet, so app behaviour is unchanged.
Privacy note: the chain sends ONLY the user's spoken/typed question to these third-party services. Screen text, notifications, memory and contacts must never be sent.
Next action: see NEXT_ACCOUNT_PROMPT_v18_PART1B.md.

## v18b (2026-10-10) - Part 1B: free AI chain WIRED into the app (Kotlin NOT compiled here, unit tests NOT run)
Sandbox again had no kotlinc and no network. Verified HERE: bracket balance (real tokenizer) of FreeProviders/FreeHttp/ExtCfg/MainActivity/NovaService/test; no `!!` / `catch (_` / TODO in any file I touched; ui.html node --check OK (6 blocks); tools/test_chain_contract.py ALL PASSED (24 checks, real HTTP sockets, see below); old tools/test_self_improve.py OK. NOT verified: Kotlin compile, the 37 FreeProvidersTest tests, phone.
Changes:
- NEW FreeHttp.kt: HTTPS-only POST, no redirects, connect 6 s / read 10 s, body cap 200 KB, UnknownHost/Connect -> 598, any other error -> 599, never throws, never logs key/question/answer. OpenRouter gets HTTP-Referer + X-Title.
- ExtCfg: freeChain (default ON), freeKeyless (default ON). MainActivity Bridge: saveOpenRouterKey / hasOpenRouterKey / clearOpenRouterKey (SecureStore slot "openrouter", never sent back to the page), get/setFreeChain, get/setFreeKeyless. ui.html: new box "FREE ONLINE AI" (two switches + OpenRouter key). Groq key = the existing speech key.
- FreeProviders.kt (pure) gained: canUseChain, chainPreAnswer (crisis words -> fixed 112 + Tele-MANAS answer, NEVER sent online; live-data words -> honest "no live source" - LocalChat.preAnswer says "offline", which would be false when online), combinedFailure (no-internet beats everything, then Gemini's own message, then the chain's).
- NovaService.cloudStep now = Gemini (own cooldown geminiCoolUntil) -> free chain (shared Cooldowns) -> combinedFailure. plan() gets (geminiKey || freeChain). Chain answer passes LocalChat.guard, lastKind="free_chain", text only, never an action. Gemini that already ran a tool is never followed by the chain.
- 9 more tests in FreeProvidersTest (37 total).
BEHAVIOUR CHANGE to check on the phone: with the default switch ON, a question NOVA cannot answer locally now goes online (Pollinations needs no key) even with no Gemini key. Only the spoken question is sent. Turn off in Settings > FREE ONLINE AI.
KNOWN LIMITS: (1) Logic.norm keeps only a-z0-9, so a Devanagari-only text counts as "junk" and never reaches the chain; fix in part 4 (Hindi). (2) Free model ids / endpoints could not be called from this sandbox: if a provider 404s the chain moves on, but confirm each on a phone with real keys. (3) tools/test_chain_contract.py checks a Python PORT of the rules plus the Kotlin SOURCE TEXT; it is not a run of the Kotlin code. (4) build.yml is not in this zip: add `python3 tools/test_chain_contract.py` next to test_self_improve.py when the workflow is available.
Phone test: Settings > FREE ONLINE AI; say "nova why is the sky blue" with Wi-Fi on and NO keys (Pollinations should answer), then add a Groq key and repeat, then airplane mode (expect the honest "no internet" text or the offline model), "nova weather today" (honest no-live-source text), "nova torch on" (still instant, offline).

## v18c (2026-10-10) - Part 1 reviewed by a second account (static review + 2 factual fixes; Kotlin still NOT compiled, tests NOT run)
Checked here: python3 tools/test_chain_contract.py ALL PASSED; tools/test_self_improve.py OK; ui.html node --check OK (6 blocks); tokenizer bracket balance over every .kt file OK; no `!!` / `catch (_` / TODO in the files Part 1 touched; NovaService/ExtCfg diff against the v15 zip read line by line (logic: Gemini first, own cooldown; chain only after a Gemini failure without a tool run; crisis words never leave the phone; chain answer goes through LocalChat.guard; text only).
FIXED (found by reading current provider docs): (1) Pollinations text needs a key now and text.pollinations.ai is the old endpoint -> FreeProviders.POLLINATIONS uses https://gen.pollinations.ai/v1/chat/completions, models openai/gpt-5.4-nano then openai, still tried anonymously (401 -> skipped 10 min). UI box text and contract test updated. (2) OpenRouter models: openrouter/free first. Groq model ids (llama-3.3-70b-versatile, llama-3.1-8b-instant) match current docs.
Verdict: Part 1 is COMPLETE AS WRITTEN CODE (all planned pieces exist and are wired) but only STATICALLY VERIFIED. Not compiled, 37 FreeProvidersTest tests never run, no live provider call, no phone test.
Reliable free route for the owner: a free Groq key (reused from the speech key) or an OpenRouter key. No key = offline model + offline skills only.
Next: PART 2 (personal memory), see NEXT_ACCOUNT_PROMPT_v20_PART2.md. Standing rule: every part ends with a zip + a new handover prompt.

## v18d (2026-10-10) - Part 2: personal memory (Kotlin NOT compiled here, Kotlin tests NOT run, phone NOT tested)
Voice (Hindi/Hinglish/English): "yaad rakh <fact>" / "remember that <fact>" / "<fact> yaad rakh", "mujhe kya yaad hai" / "what do you remember", "bhool ja <thing>" / "forget <thing>", "sab bhool ja" (spoken yes via askFirst). Kinds mem_save / mem_list / mem_forget / mem_forget_all are NOT in Brain.SAFE_KINDS (tests added).
NEW: PersonalMemory.kt (pure), PersonalMemoryStore.kt, PersonalMemoryTest.kt (generated), tools/memory_cases.py + gen_memory_test.py + test_memory_contract.py. CHANGED: Logic.kt (classify hook before Timers, LOUD_KINDS), NovaService.kt (handle short-circuit before brain.record + masking, 4 runCommand branches, memSave/memList/memForget/memForgetAll, chainFacts, local model gets facts), LocalBrains.chat(facts), ExtCfg.memoryInChain, MainActivity Bridge (getMemoryCount/getMemoryText/clearMemory/get+setMemoryInChain), ui.html memory box, SafetyInvariantsTest (+2).
Privacy: refuses OTP/password/PIN/CVV/card/Aadhaar-PAN-passport/bank account/any 6+ digit number (typed or spoken digits); max 100 facts x 200 chars, newest first, case-insensitive dedupe; stored only in SharedPreferences "nova_memory" (allowBackup=false); never logged; heard text of a memory command is NOT recorded by brain.record and is replaced by "(yaad rakhne ka command)" in chat/card; Gemini never gets facts; the online free chain gets at most 3 RELEVANT, non-contact-like facts and only while ExtCfg.memoryInChain is ON (default ON, UI switch); the on-device model gets up to 3 relevant facts.
Checked HERE: python3 tools/test_memory_contract.py ALL PASSED (94 checks: real Kotlin regexes + Python port of the logic over the shared table; found and fixed 2 real bugs); test_chain_contract.py + test_self_improve.py still pass; ui.html node --check OK (6 blocks); tokenizer bracket balance over every touched .kt; no `!!` / `catch (_` / TODO in touched files. NOT verified: Kotlin compile, the 14 PersonalMemoryTest + 2 safety tests, Android store, phone.
Phone test: say "nova yaad rakh mera naam Shiv hai" (reply "Theek hai, yaad rakh liya", chat shows "(yaad rakhne ka command)"); "nova mujhe kya yaad hai" (reads it back); "nova yaad rakh mera otp 4821" (refused, nothing stored); "nova bhool ja mera naam"; "nova sab bhool ja" then "haan"; Settings > YAAD box shows the count, DIKHAO shows the text, SAB HATAO needs two taps.
Next: PART 3 knowledge pack, see NEXT_ACCOUNT_PROMPT_v21_PART3.md.

## v22 (2026-10-10) - Part 3 SAVE-POINT (knowledge pack): matcher written, data 39/150+, NOT wired, nothing compiled or run
DONE (written, statically checked only: tokenizer bracket balance OK, no `!!`, no TODO, canon values never in STOP):
- NEW app/.../KnowledgeMatcher.kt (pure): STOP words, CANON (339 Hinglish spelling variants + synonyms), LIVE words (today/price/news... -> always null), integer dice score (THRESHOLD 60, TIE_MARGIN 5, MIN_OVERLAP 2, one typo forgiven only in words of 6+ letters), prepare/match/reply/parse(json via MiniJson). Not referenced by any other file yet, so app behaviour is unchanged.
- NEW tools/knowledge_src/k_science.py: 39 science/body entries (id, 5-6 question variants, Hinglish answer, English answer, tags). Checked: >=5 variants each, no LIVE word in any variant, answers short.
NOT DONE (see NEXT_ACCOUNT_PROMPT_v22_PART3.md): remaining ~115 entries (India, first-aid, math rules, daily how-tos, NOVA tips), tools/build_knowledge.py (merge to app/src/main/assets/knowledge.json), tools/validate_skillpack.py, tools/test_knowledge_contract.py (Python port + 100 question cases), KnowledgeStore.kt, KnowledgeMatcherTest.kt, wiring in NovaService.handle, docs.

## v23 SAVE-POINT: Part 3 knowledge pack, data + tools DONE, Kotlin wiring NOT done
Ladder level: written + statically checked + Python contract tests run in the sandbox. NOT compiled, NOT run on a phone, NOT in CI.
- DONE: assets/knowledge.json (193 entries, 1157 variants, built by tools/build_knowledge.py from tools/knowledge_src/k_*.py: science 39, india 39, health 30, math 28, daily 27, nova 30).
- DONE: tools/validate_skillpack.py (OK), tools/knowledge_port.py (Python port, reads STOP/CANON/LIVE/KEEP1 from KnowledgeMatcher.kt), tools/test_knowledge_contract.py (100+ cases, ALL PASSED).
- KnowledgeMatcher.kt changed in this chat: KEEP1 = setOf("c","d") (so "vitamin c" vs "vitamin d" differ) and STOP got "si","sa","sabse".
- NOT DONE: KnowledgeStore.kt, NovaService wiring, Kotlin tests (KnowledgeMatcherTest, knowledge.json test, SafetyInvariantsTest additions), self-test count line. The phone still behaves exactly as before: nothing calls the matcher.

## v24 (2026-10-10): Part 3 complete (knowledge pack wired), NOT compiled, NOT run on a phone
Ladder level: written + statically checked (brackets balanced in the new/edited files, no `!!`, no `catch (_`, no TODO) + Python contract tests pass in the sandbox. Kotlin NOT compiled, tests NOT run, CI NOT run, phone NOT tested.
- NEW KnowledgeStore.kt: lazy, cached, synchronized load of assets/knowledge.json (600 KB cap, never throws); answer(ctx, text, en) = KnowledgeMatcher.match + reply; count(ctx) for the self test.
- NovaService.handle: after Logic.classify / LocalSkills / shortcuts / groq text all gave nothing (c, c0, sk == null) and BEFORE brain.record(... "unknown"): junk is skipped; on a hit lastTurnLocal = true, lastKind = "knowledge", brain.record(now, text, "local", "knowledge"), the answer is returned. Runs before the offline translator and Gemini / free chain. Text only, never an action. In quiet-replies mode the answer is shown but not spoken (same as local_chat, "knowledge" is not in LOUD_KINDS).
- SelfTest: new line "Knowledge pack: N entries loaded" (WARN if 0).
- NEW test KnowledgeMatcherTest.kt (hits, language, no match, near miss, live words, too long, vitamin c vs d, tie, typo, tokens, parse good/bad, real pack: every entry's first variant finds itself). Expected values were cross-checked with tools/knowledge_port.py. SafetyInvariantsTest: "knowledge" and "local_skill" are not in Brain.SAFE_KINDS.
- Still true: python3 tools/build_knowledge.py, validate_skillpack.py, test_knowledge_contract.py all pass after any data change.
- NEXT: PART 4 (Hindi command model: Logic.norm keeps only a-z0-9, so Devanagari is junk; HindiRoman.kt exists) and PART 5 (integration + regression + one build.yml adding python3 tools/test_chain_contract.py, test_memory_contract.py, test_knowledge_contract.py, validate_skillpack.py). See NEXT_ACCOUNT_PROMPT_v24_PART4.md.

## v25 (2026-10-10): Part 4 complete (Hindi / Devanagari input), NOT compiled, NOT run on a phone
Ladder level: written + statically checked (brackets balanced, no `!!`, no `catch (_`, no TODO) + Python mirror tests pass in the sandbox. Kotlin NOT compiled, tests NOT run, CI NOT run, phone NOT tested.
- HindiRoman.kt extended: word table (command words, yes/no, numbers, double-a spellings, vitamin c/d, calls/messages) + NEW rule transliteration for every Devanagari word not in the table (consonants, matras, nukta, conjuncts via virama, anusvara, simple schwa deletion, Devanagari digits). hasDevanagari(), toRoman() unchanged in name. Only changes words, never approves / taps / sends.
- NovaService.handle(pcm, rawText): Devanagari is converted at the very top; the chat shows the original text; Logic.classify, LocalSkills, KnowledgeMatcher, free chain all see Roman. groqHear still converts too. A typed yes still never approves a risky action.
- Safety unchanged: SAFE_KINDS, risky labels, parseAnswer not touched. "हाँ" -> haan, "नहीं" -> nahi, "हाँ नहीं" -> false (tested).
- NEW tests: HindiRomanTest.kt (table words, rule words, vitamin c/d, digits/nukta/danda, odd input never throws, yes/no strict, classify equals the Roman command, junk before/after, Hindi questions find knowledge answers incl. the real pack, live word "आज" gives null). NEW tools/hindi_port.py + tools/test_hindi_contract.py (ALL PASSED).
- Known limits: spelling is a best guess (e.g. "खून" is in the table as khoon, but an unlisted word may come out as khun and miss a match); Hindi words of Sanskrit origin or long rare words may be spelled oddly; "ज़्यादा/तेज़" still map to "badhao" (old volume mapping). Hindi text only helps where Devanagari text arrives (typed keyboard, Groq Whisper); the offline Vosk model still returns Roman letters.
- NEXT: PART 5 (integration + regression + ONE build.yml for the owner to paste). See NEXT_ACCOUNT_PROMPT_v25_PART5.md.

## v26 (2026-10-10): Part 5 done (static review + CI file). Parts 1-4 all written; NOTHING compiled, NOTHING run on a phone
Ladder level: written + statically checked + Python contract tests run in the sandbox (5 scripts, all pass). Kotlin NOT compiled (this sandbox has no kotlinc / Gradle / Android SDK), CI NOT run, phone NOT tested.
- Static review of Part 3+4 files by hand: KnowledgeStore.kt (locking simplified so there is no non-local return inside synchronized), KnowledgeMatcher.kt (MiniJson.field/parse API checked), HindiRoman.kt (class named Syl, not Kotlin's Unit; explicit String type), NovaService.kt wiring (vals c, c0, sk, now, groqText all defined before use), SelfTest.kt (ctx and rec exist), the three new/changed test files (every expected value cross-checked with the Python ports; knowledge.json checked against the Kotlin parse rules: 193 unique ids matching the id regex, answers <= 324 chars, variants 3..90 chars). Source guards: no `!!`, no `catch (_`, no TODO in app/src/main.
- Build is Kotlin 1.9.24 / AGP 8.5.2 / JUnit 4.13.2; unit tests read "src/main/assets/knowledge.json" relative to the app/ module dir.
- NEW python-tests.yml (separate workflow, delivered as a download, NOT in the zip; the owner pastes it at https://github.com/devboutique0-wq/NOVA/new/main?filename=.github/workflows/python-tests.yml). It runs the source guards + validate_skillpack.py + test_knowledge_contract.py + test_hindi_contract.py + test_chain_contract.py + test_memory_contract.py. build.yml was NOT touched (its text was not available in the zip, so rewriting it blind could drop its model downloads / secret scan / keystore handling).
- NEXT: the owner uploads the zip, runs "Unpack zip", then "Build and Test APK". If red: ask only for the red lines of the failed step and fix them first. If green: install APK, ACTIVATE, run SELF TEST (look for "Knowledge pack: 193 entries loaded"), try a typed Hindi question, a knowledge question, and a voice command.
