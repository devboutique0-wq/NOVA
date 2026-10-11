# NOVA - NEXT ACCOUNT PROMPT (read fully before doing anything)

You are taking over the NOVA Android voice assistant project. The owner is non-technical and works from a phone. Read every section. Follow section 1 exactly.

## 0. STATE WHEN THIS PROMPT IS GIVEN (AFTER-PART-3 prompt)
- Part 1 (camera front/video): WRITTEN. Test cameraWithOptionsGoesToTheAiNotToAnAppNameGuess exists.
- Part 2 (self-diagnosis): WRITTEN. ErrLog.kt, PowerGuideRules.kt, PowerGuide.kt, tests ErrLogTest / PowerGuideRulesTest, buttons AKHRI GALTIYAN and BATTERY / AUTOSTART, ErrLog hooks in NovaService, HealthCheck and SelfTest reports.
- Part 3 (silent mode): WRITTEN. New file SilentRules.kt (pure rules) + SilentRulesTest.kt (11 tests). Wiring in NovaService.kt: fields silentTurn / silentCarryAt / turnFailed / turnOk / silentFillerHeard / agentSilent / agentAsked; beginTurn, silentAllowed, silentStrip, silentFillerReply; finishTurn uses SilentRules.present; process / processAnswer / handle (groqHear + geminiHear) / runLocalTool / runTool / agentStart / agentRun ('ask' branch) are hooked.
  Behaviour: owner adds "chup rehna", "bina bole", "chupchap", "don't speak" etc. to an order: NOVA does it with no speech and shows a short card message. Only for that one order. A waiting yes/no is ALWAYS spoken; a failure shows the real text; "Kaam complete ho gaya" is shown only for torch / volume / brightness (the phone confirms these), otherwise the tool's real message is shown; a phrase glued into a message text ("... ki tum chup rehna") is not cut unless separated by a comma / aur / and; bare "chup" / "quiet" still mean stop.
- NOTHING IS CI-GREEN YET. Evidence so far: a hand-made harness (kotlinc + a JUnit shim) ran the pure-Kotlin tests in the chat sandbox: 327 pass, 0 fail (tests that need Android classes: Hud*, FreeImageRules, PublicKnowledge, GroqStt were not run). An independent read-through found no compile errors in the Part 1-3 code, but nothing was compiled with Gradle.
- A logic/safety review of Part 3 by an independent agent was NOT completed (the owner stopped it). Do that review yourself first (race conditions between threads, stale silentTurn / silentCarryAt leaking into a later turn, any hidden question, regex over/under-match).
- FIRST JOB: owner uploads the zip, runs "Unpack zip", then "Build and Test APK". Fix whatever the build says until CI-GREEN for Parts 1, 2 and 3. Then the owner checks Parts 2 and 3 on the phone (checklists below). Then start Part 4.
- Owner's new demand (applies to everything): "no mistakes at all; have the work analysed by several AIs/reviewers; NOVA must hear clearly what I say". So before delivering each part, run independent review passes (separate agents that only see the code, not your conclusion) and a hand walk-through of every new test.

### Listening audit findings (from an independent review, NOT yet implemented; do them in Part 4 or as a separate listening fix, each with tests where pure)
1. Vosk (offline) text is classified first, so a misheard word can run a wrong local command before Groq/Gemini is asked (NovaService handle(), Understand.classify / brain.resolve before groqHear/geminiHear). Safer: when a Groq or Gemini key exists and the audio is long enough, take the cloud transcript first, fall back to Vosk only if blank; keep exact-match shortcuts for stop/time/battery.
2. The noise estimate `noise` is updated while the owner speaks the wake word, so the "speaking" threshold (max(450, noise*2.5)) rises and soft words count as silence. Update `noise` only when rms < max(450, noise*2).
3. Dictated replies end after 1.3 s of silence (Logic.DICTATION_SILENCE_MS = 1300): raise to about 2500.
4. Early-finish timers (Logic.FAST_END_MS 1.2 s, FAST_END_OPEN_APP_MS 1.8 s) cut compound commands ("volume badhao ... aur brightness kam karo"): raise to about 1800 / 2500, and skip fast-end if live text ends with aur / and / phir / ke baad.
5. startCapture beep (120 ms) + skip=2 drops the first 200 ms after the wake word: use skip=1 and a shorter beep.
6. GroqStt PROMPT is a list of real commands ("Nova, torch chalu karo. Instagram kholo...") and there is no language hint: Whisper can echo it on noise and run a command. Use a neutral prompt, consider language=hi, verbose_json and drop segments with high no_speech_prob.
7. Up to 5 s of trailing silence is uploaded to Groq/Gemini: trim pcm to the last speaking chunk + about 0.4 s (keep MIN_HEAR_BYTES logic consistent).
8. No AutomaticGainControl / NoiseSuppressor on the AudioRecord: attach them when available (do NOT loosen wake thresholds).
9. AudioRecord buffer is small (about 0.5 s): use maxOf(minBuf*4, 65536) (plausible, not proven).
Do not weaken wake strictness (WakeStrictTest, ListenTest) or any safety rule while doing these.

## 1. COMPULSORY RULES (owner's orders, apply to EVERY account, copy them into EVERY prompt you write)
1. Everything you deliver must be REAL and WORKING. Nothing half-done, nothing stubbed, no "TODO", no fake success messages, no feature that only looks finished. If something cannot be done on Android, say so plainly and build the closest honest version.
2. Finish each part completely (code + unit tests + a short phone check list for the owner) before starting the next part. Do not skip any part and do not leave anything out.
3. Never claim more than the evidence shows. Use exactly these three levels: WRITTEN (code exists), CI-GREEN (the GitHub build and all unit tests passed), PHONE-TESTED (the owner tried it on the phone and said it works). You cannot compile or run the Android app in the chat sandbox, so a part is never CI-GREEN until the owner shows a green build. (A pure-Kotlin test harness with kotlinc can be made in the sandbox by downloading the compiler from GitHub releases and writing a tiny JUnit shim; it checks pure logic only and is NOT CI-GREEN.)
4. Give the owner a prompt BEFORE you start work, and another prompt after Part 2 is complete (that is the pattern for every account). Every prompt you write must contain these rules and all 11 parts.
5. Owner is non-technical, works only from an Android phone, writes Hinglish: reply in Hinglish, short messages, ONE step at a time, clickable links, files to download instead of long pastes.
6. Never weaken or delete an existing test or safety rule to make a build green (SafetyInvariantsTest guards risky actions: spoken yes, no tap/type in banking/payment apps, etc.).
7. Privacy: no audio saved, no voiceprint or API key leaves the phone, never log or print a key. Recording features must show Android's own notification (cannot and must not be hidden).
8. NEW: independent review before delivery. Use separate reviewers that read only the code (compile errors; logic and safety; for listening features, the audio path). Report their findings honestly, including what they could not check.

## 2. HOW WORK IS DELIVERED (the owner's real method, do not change it)
- Repo: https://github.com/devboutique0-wq/NOVA (Android voice assistant "NOVA", Kotlin, package com.nova.assistant). Built ONLY by GitHub Actions; the owner has no PC.
- You give ONE zip: NOVA_v15_assistant.zip (the whole project). Owner steps:
  1. Upload the zip: https://github.com/devboutique0-wq/NOVA/upload/main -> Commit changes.
  2. Actions -> "Unpack zip" -> Run workflow (https://github.com/devboutique0-wq/NOVA/actions). THIS puts the new code into the repo.
  3. Actions -> "Build and Test APK" -> Run workflow (or let the automatic one finish).
  4. Green -> download the APK from the run's Artifacts and install. Red -> owner searches the log for FAILED and sends a screenshot.
- IMPORTANT LESSON: the build that starts right after the upload commit ("Add files via upload") runs on the OLD code because the zip is not unpacked yet. It can be red for an old reason. Only the build after "Unpack zip" counts. Tell the owner this every time.
- Always unzip the owner's zip first and read the real code before editing. Repack the full project as NOVA_v15_assistant.zip and send the file.

## 3. WHAT THE CODE ALREADY HAS (read from the real zip, verified)
Folder app/src/main/java/com/nova/assistant/ ; UI is one web page app/src/main/assets/ui.html talking to Kotlin through the Android JS bridge in MainActivity.
- Logic.kt: strict command parser (Logic.classify, Logic.Cmd(kind,arg,num)), speakReply / LOUD_KINDS, timing constants. Understand.kt: loose parser for long casual sentences; only safe, reversible commands; anything unclear goes to the AI. Tests: LogicTest, UnderstandTest.
- NovaService.kt (about 3000 lines): the always-on service. Mic loop (startCapture, noise/peak/speaking, Vosk wake word), process / processAnswer / finishTurn, handle(), runCommand, runLocalTool (offline commands), runTool (commands the cloud AI calls), declarations() (tool list for Gemini), systemPrompt(), cameraTool(mode, facing), launch(), openApp(), agent (agentBegin/agentStart/agentRun), pending yes/no (askFirst, Pending).
- AI chain (FreeProviders.kt, FreeHttp.kt, GroqStt.kt, FreeImage.kt): Gemini (owner key; hears audio AND is the only one with phone tools) -> Groq (llama-3.3-70b, llama-3.1-8b; Whisper speech-to-text) -> Qwen via Groq -> GLM (Z.ai) -> OpenRouter free models -> Pollinations -> offline Qwen2.5-0.5B (LocalBrains) -> offline skills. Images: Together FLUX, HuggingFace FLUX, Pollinations. Failover is between DIFFERENT providers only.
- Speaker lock: SpeakerRules.kt + VoiceLock.kt (Vosk x-vector, average of 5 enrollment samples, cosine distance, strictness 0.4 / 0.5 / 0.6, never tuned on a real phone).
- PatternUnlock.kt / PatternSetupActivity.kt: voice-triggered pattern unlock (security sensitive).
- Memory and learning: PersonalMemory(+Store), LearnRules/LearnStore, Brain/BrainStore (skills, triggers), KnowledgeStore + assets/knowledge.json.
- Health: HealthCheck.kt, SelfTest.kt, ErrLog.kt, PowerGuide(+Rules).kt. Silent mode: SilentRules.kt.
- Timers.kt, Driving.kt, ScreenAlert.kt, NovaAccessibilityService.kt, NovaHud.kt, UpdateManager.kt, Updater.kt, GitHubPublish.kt, AutoSetup*.kt.
- Tests: app/src/test/java/com/nova/assistant/ (about 340 tests). CI runs them all; one failing test makes the whole build red.

## 4. THE 11 PARTS (owner agreed this order; finish each fully before the next)
1. CAMERA - WRITTEN. "mere mobile ka camera kholo aur front camera se video on karo" in one sentence. camera tool has mode (photo|video) and facing (front|back); video opens MediaStore video camera; front sends CAMERA_FACING / LENS_FACING_FRONT / USE_FRONT_CAMERA extras (best effort, some camera apps ignore them). NOVA cannot press the record button; it tells the owner to tap it. Understand.classify returns null for camera + video/front/selfie/record words so the AI chooses the arguments.
2. SELF-DIAGNOSIS - WRITTEN. ErrLog (last 30 failures, in memory only, keys masked) in HealthCheck and SelfTest reports; Settings buttons AKHRI GALTIYAN and BATTERY / AUTOSTART; PowerGuide opens the brand's autostart screen (Xiaomi, Oppo/Realme/OnePlus, Vivo/iQOO, Huawei/Honor, Samsung) with a standard-Android fallback. Limits: log is cleared when the app is killed; brand screens are private and may not exist; the app can open screens but cannot switch settings itself. (Still to add if not present: the health report showing which AI keys are set, never the key.)
3. SILENT MODE - WRITTEN (see section 0). Phone check list for the owner:
   a. Say "nova torch jala do, chup rehna": torch on, no voice, card says "Kaam complete ho gaya".
   b. "nova volume kam karo bina bole": volume lower, no voice.
   c. "nova battery kitni hai chup rehna": answer only on the card, no voice.
   d. "nova torch band karo" (without the silent phrase): speaks normally.
   e. Ask NOVA to call someone with "chup rehna": it must still ASK yes/no aloud.
   Limits: if NOVA mishears the phrase it speaks; phrase inside a dictated message text is not cut unless separated by comma/aur/and; Android's own notifications / privacy indicators cannot be hidden.
4. NEVER WAKE BY ITSELF / NEVER INTERRUPT. No activation from TV, other people, or background noise; if the owner is talking to someone else NOVA must not cut in. Audit the wake-word path (see WakeStrictTest, ListenTest), add a stricter gate, and a rule that NOVA stays silent unless the wake word plus owner voice check pass. Include the listening-audit items from section 0 that are safe (noise estimate, fast-end timers, skip/beep, trailing-silence trim, Groq prompt).
5. VOICE + FACE. Voice: self-tuning enrollment test (owner says about 10 sentences; measure accept/reject; choose the threshold; replace the untuned 0.4/0.5/0.6). Locked phone: NOVA wakes only if BOTH voice AND face verify (owner chose "both", not "either one"); otherwise stay silent. Face: Android gives apps NO access to the phone's face-unlock data. Use androidx.biometric BiometricPrompt (system face/fingerprint screen, shown over the lock screen, asks permission in-app once). On phones where face is only "weak", BiometricPrompt may refuse: then offer fingerprint or device credential as the second factor and tell the owner honestly. A recording of the owner's voice can fool voice-ID, which is why the second factor exists. Add real tests for the decision rules (pure Kotlin).
6. BACKGROUND RECORDING. "background me recording on karo aur mobile off kar do" works with the screen off: foreground service (microphone type; camera type for video) with the mandatory visible notification and Android's privacy indicator. Audio recording is reliable; video with screen off depends on the phone and Android version: test, and report honestly. Save files to the app's own folder / MediaStore, show where. Needs a clear spoken or on-screen stop. No hidden or covert recording.
7. EVERY FREE AI CAN CONTROL THE PHONE + QUOTA METER. Today only Gemini can call phone tools; Groq/Qwen/GLM/OpenRouter only chat. Make each of them return a strict JSON command that is validated and mapped to the SAME safe Logic.Cmd / runTool path (same safety rules, same confirmations). Show in Settings which provider is working, which hit a limit, and when it cools down. Free-tier limits change: read the provider dashboards or docs when quoting numbers, never guess them.
8. CONTEXT MEMORY. Follow-ups: "isko band karo", "thoda aur tez", "wahi dobara". Keep the last few turns (what was just done) so pronouns and "aur" work; expire after a short time; never repeat risky actions from context.
9. TEACH ONCE. Owner corrects NOVA once ("mummy = Sunita", "mera camera = Camera app", "iska matlab ye hai"); NOVA stores it (LearnStore/PersonalMemory), applies it next time, lets the owner see/delete it, and has a backup/restore of learned data (export to a file the owner can keep; restore after reinstall).
10. ROUTINES. "good morning" -> several steps in order (existing Brain skills are the base). Safe kinds only (Brain.SAFE_KINDS), step limit, owner can edit/delete routines.
11. SEE THE SCREEN. "dekh ke batao / screen par ye click karo": use the accessibility screen tree first and, when useful, a screenshot sent to Gemini vision. Android and apps block some screens (banking, payment, passwords, FLAG_SECURE); respect Control.RISKY and the blocked list; say plainly when a screen cannot be read.

## 5. DEFINITION OF DONE FOR EVERY PART (all items, every time)
- Real code wired end to end (not just a file that nothing calls).
- Unit tests for every pure rule; existing tests untouched and still logically passing. Walk through each new test by hand, and run the pure ones in the sandbox harness if possible.
- Independent review passes (compile, logic/safety, and audio path where relevant); report their findings honestly.
- A phone check list of 3 to 6 short steps for the owner, in Hinglish, each with the expected result.
- An honest limits list for that part.
- Updated zip presented as a file; then ONE next step for the owner (upload link first).
- A short summary of evidence level (WRITTEN / CI-GREEN / PHONE-TESTED) for each item.

## 6. ANDROID LIMITS TO TELL THE OWNER STRAIGHT (do not promise around them)
- Apps cannot read the phone's face-unlock data; only BiometricPrompt is possible.
- Apps cannot press the camera shutter/record button without an accessibility tap (screen dependent); recording always shows a notification and a privacy indicator.
- Wi-Fi and Bluetooth cannot be switched by an app; NOVA opens the settings page.
- Some apps/screens block accessibility reading and taps; NOVA must say so.
- Free AI keys have per-minute and per-day limits; sentences and audio go to the provider used, so say so in settings text.
- No speech recogniser is perfect: offline Vosk guesses Roman words; cloud transcripts (Groq Whisper, Gemini) are better. Never promise zero mishearing; reduce it and make risky actions need a spoken yes.

## 7. YOUR FIRST MESSAGE TO THE OWNER
In Hinglish, 3 to 5 short lines: what state you found, what the single next step is (with a clickable link), and nothing else.
