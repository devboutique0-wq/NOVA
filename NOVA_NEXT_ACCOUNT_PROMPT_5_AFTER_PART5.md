# NOVA - NEXT ACCOUNT PROMPT (after Part 5; read fully before doing anything)

You are taking over the NOVA Android voice assistant project. The owner is non-technical and works from a phone. Read every section. Follow section 1 exactly.

## 0. STATE WHEN THIS PROMPT IS GIVEN (AFTER-PART-5 prompt)
- Parts 1-4 are WRITTEN (see section 4 and the "Part 3/4 notes" below). Part 5 (VOICE + FACE) is WRITTEN (details below).
- NOTHING IS CI-GREEN YET. The chat sandbox used so far had no Kotlin compiler and no network, so nothing from the Part 3 fixes, Part 4 and Part 5 was compiled or run. Pure-logic tests were walked through by hand.
- Part 5 content:
  * VoiceFaceRules.kt (pure): VoiceTune.choose (the owner says 10 test sentences; limit = 90th percentile of their distances * 1.15 + 0.02, clamped 0.25..0.60; owner must pass at least 80%; optional 3 sentences of another person: limit pulled halfway down if they are separable, "overlap" when they are as close as the owner = do not trust voice alone), LockGate (pickMode, step, faceFresh 60 s, canEnable), FaceGateState (token hand-off between the mic worker thread and the face screen; a late or stale answer never counts).
  * SpeakerRules.effectiveThreshold: a sane tuned limit wins over STRICT / NORMAL / LOOSE; choosing a level again, deleting the voice or saving a new voiceprint clears the tuned limit.
  * FaceGate.kt (Android): FaceGate.mode / isLocked (unreadable = locked) / launch / notice, FaceGateActivity (FragmentActivity, transparent, showWhenLocked, hosts androidx.biometric BiometricPrompt, BIOMETRIC_WEAK + Cancel, or DEVICE_CREDENTIAL on Android 11+ when nothing is enrolled). If the screen does not come up in 1.2 s a full-screen-intent notification is posted. Manifest: FaceGateActivity, USE_FULL_SCREEN_INTENT. build.gradle.kts: androidx.biometric 1.1.0 + androidx.fragment 1.6.2. themes.xml: Theme.NovaGate.
  * NovaService: startVoiceTune(others), tuneSample (consumes test sentences, never runs them as commands), faceAllows() called right after voiceAllows in the turn thread (locked + gate on + voice not active = refused; no usable second factor = refused; face screen not passed in 25 s = refused; all refusals are shown in the chat only, never spoken).
  * MainActivity bridge: voiceTune, getVoiceLimitText, faceMode, getFaceGate, setFaceGate, faceTest (+ onFaceTest callback). ui.html: new Settings box "AWAAZ TEST + FACE LOCK".
  * ExtCfg: voiceThreshold, voiceOwnDists (numbers only, no audio), faceGate.
  * Tests: VoiceFaceRulesTest (about 25 tests). Existing tests untouched.
- Honest limits of Part 5 (tell the owner): Android gives apps NO access to the phone's face-unlock data; NOVA shows the phone's own BiometricPrompt, which accepts the face OR fingerprint the phone has set up (the app cannot demand face only); on a phone whose face unlock is not accepted by BiometricPrompt, fingerprint / PIN is used. Passing the prompt does not unlock the phone. A recording of the owner can fool the voice check, which is why the second factor exists. Showing the prompt over a locked screen from the background needs the overlay permission ("display over other apps", already used by the card) or the full-screen notification; Android 14+ may need the owner to allow full-screen notifications. On Android 10 and older PIN-only fallback is not offered. The voice limit was never tuned on a real phone before; the tuning only measures the owner's own voice (and optionally one other person), it is not a security proof.
- Part 5 phone check list (Hinglish for the owner): (a) Settings > AWAAZ SAVE, phir AWAAZ TEST: 10 vaakya bolo, chat me "Naya limit" aana chahiye; (b) FACE CHECK TEST dabao: phone ki face/fingerprint screen aaye aur pass par "pass hua" likha aaye; (c) "Phone locked ho to awaaz + face dono" ON karo (pehle 'sirf meri awaaz' ON hona chahiye), phone lock karo, bolo "nova torch jala do": face/fingerprint screen aani chahiye, pass karo to torch jale; (d) wahi screen par Cancel dabao: NOVA kuch na kare, sirf chat me likhe; (e) doosre insaan se locked phone par "nova torch jala do" bulwao: kuch nahi hona chahiye; (f) screen aati hi nahi ho to "Display over other apps" permission check karo aur batao.
- Part 3/4 notes: Part 3 review fixes (silent phrase cut from the cloud transcript too; silent carry tied to the exact question) and Part 4 (ListenGuard.kt: noise estimate, joiner-aware fast end, longer timers, shorter beep, trailing-silence trim, neutral Whisper hint with echo / no_speech filter, no proactive speech while people talk) are WRITTEN. Not done from the listening audit on purpose: cloud transcript before Vosk, AutomaticGainControl / NoiseSuppressor, larger AudioRecord buffer, Whisper language hint.
- FIRST JOB: owner uploads the zip, runs "Unpack zip", then "Build and Test APK" (ONE build for Parts 1-5). Fix whatever the build says until CI-GREEN. Likely first suspects: androidx dependency resolution, FaceGate.kt compile, ui.html. Then the owner does the phone check lists (Parts 2, 3, 4, 5). Then start Part 6.
- Owner's demand (applies to everything): "no mistakes at all; have the work analysed by several AIs/reviewers; NOVA must hear clearly what I say". Before delivering each part run independent review passes and a hand walk-through of every new test.

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
4. NEVER WAKE BY ITSELF / NEVER INTERRUPT - WRITTEN (see section 0). Original goal: no activation from TV, other people, or background noise; if the owner is talking to someone else NOVA must not cut in. Audit the wake-word path (see WakeStrictTest, ListenTest), add a stricter gate, and a rule that NOVA stays silent unless the wake word plus owner voice check pass. Include the listening-audit items from section 0 that are safe (noise estimate, fast-end timers, skip/beep, trailing-silence trim, Groq prompt).
5. VOICE + FACE - WRITTEN (see section 0). Original goal: Voice: self-tuning enrollment test (owner says about 10 sentences; measure accept/reject; choose the threshold; replace the untuned 0.4/0.5/0.6). Locked phone: NOVA wakes only if BOTH voice AND face verify (owner chose "both", not "either one"); otherwise stay silent. Face: Android gives apps NO access to the phone's face-unlock data. Use androidx.biometric BiometricPrompt (system face/fingerprint screen, shown over the lock screen, asks permission in-app once). On phones where face is only "weak", BiometricPrompt may refuse: then offer fingerprint or device credential as the second factor and tell the owner honestly. A recording of the owner's voice can fool voice-ID, which is why the second factor exists. Add real tests for the decision rules (pure Kotlin).
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
