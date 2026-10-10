# NOVA - HANDOVER PROMPT v31 (written 2026-10-11). Replaces v27 to v30.
Paste this whole file as the FIRST message of the next chat and attach NOVA_v15_assistant.zip. The owner must NOT have to explain anything: read this, then HANDOFF_STATUS.md (v28 entries at the end), then continue. If something is unclear, decide sensibly, say what you decided, continue.

## 0. Owner, rules, delivery style
- Owner = non-programmer on an Android phone (Xiaomi 24116RNC1I, Android 36). Writes Hinglish. Wants SHORT Hinglish replies, numbered steps, nothing fake. Never say DONE / WORKING / VERIFIED without evidence.
- Evidence ladder: written < statically checked < compiled + tests run < GitHub CI green < phone tested. Always state the level of each change.
- EVERY delivery: (a) a PREVIEW (screenshot / published HTML preview; if there is no UI change say so) and (b) DIRECT LINKS (Actions page, upload page).
- Owner cannot read long logs: ask only for "the red lines of the failed step" (a screenshot is fine).
- Return ONE zip named exactly NOVA_v15_assistant.zip, all files at the ROOT. .yml workflow files inside the zip are ignored: deliver any .yml as a separate download.
- Code guards (static): no `!!` in main code, no `catch (_`, no TODO. After every change run: python3 tools/test_chain_contract.py, test_hindi_contract.py, test_knowledge_contract.py, test_memory_contract.py, test_self_improve.py, test_agent_contract.py and tools/validate_skillpack.py (ALL pass at v28).
- Sandbox: no kotlinc / Android SDK / Gradle; python3 works. Kotlin is only statically checked by you; the real compiler is GitHub Actions.

## 1. Repo and workflows
- Repo: https://github.com/devboutique0-wq/NOVA (branch main), owner account devboutique0-wq, works from the phone browser.
- Actions: https://github.com/devboutique0-wq/NOVA/actions . Workflows (NOT all in the zip): "Build and Test APK" (APK in the run's Artifacts), "Python tests (NOVA)", "Unpack zip"/"Unzip NOVA", "Release APK", "Cleanup old files".
- Owner steps for a new zip: 1) upload https://github.com/devboutique0-wq/NOVA/upload/main -> Commit. 2) Actions -> "Unpack zip" -> Run workflow. 3) Actions -> "Build and Test APK" -> Run workflow. 4) green run -> Artifacts -> APK. Single file edit: https://github.com/devboutique0-wq/NOVA/edit/main/<path>
- CAUTION: unpacking an OLD zip overwrites newer repo files. If the owner edited files on GitHub, ask for Code -> Download ZIP (https://github.com/devboutique0-wq/NOVA/archive/refs/heads/main.zip) first.

## 2. App
Android voice assistant "NOVA" (Kotlin, com.nova.assistant, minSdk 26, compile/target 34, Kotlin 1.9.24, AGP 8.5.2, JUnit 4.13.2). Vosk offline wake word "nova", WebView UI (assets/ui.html, bridge `Android`), NovaService.handle pipeline: Hindi->Roman, personal memory, local skills, knowledge pack (193), offline commands, free online AI chain (Groq/OpenRouter/Pollinations), Gemini with the owner's key, Groq Whisper listening, driving mode, timers, Accessibility control, AI image generate/edit (Gemini) + free image fallback, updater, pattern lock, self test. The "LOCAL MODE" badge only means NO Gemini key is saved (localMode = !hasKey).

## 3. Evidence
- v27 (last CI-verified): "Build and Test APK" #61 GREEN, "Python tests (NOVA)" #4 GREEN, phone SELF TEST pass=25 fail=0 skip=1 (Gemini gemini-3.5-flash code 200). Offline llama engine was removed (needs Kotlin 2.3 / AGP 8.9.1): do not re-add. Do not recreate ModelDownloadTest.kt. Do not re-add `configurations.all { force(...) }`.
- v28 (this zip): everything below is WRITTEN + STATICALLY CHECKED + 6 Python contract tests + validate_skillpack pass. Kotlin NOT compiled, CI NOT run, phone NOT tested.

## 4. What v28 added (read before touching these files)
1. TASK AGENT: the owner says a whole job once (e.g. "gallery kholo aur 5th photo select karke chat gpt se edit karwao"); NOVA loops: read screen -> Gemini returns ONE JSON action -> AgentRules.check -> run -> repeat. Caps: 30 steps / 5 min; "stop/ruko" cancels; stuck detection; scroll when the item is not visible, "end reached" hint after a scroll that changes nothing.
   - AgentRules.kt (pure: isTask, taskText, parse, check OK/CONFIRM/DENY, stuck, prompts) + AgentRulesTest.kt + tools/test_agent_contract.py.
   - NovaAccessibilityService: agentSnapshot (numbered items, no password fields, text typed in boxes never listed), agentTap(index,long) with gesture fallback, agentType, agentScreenshot (JPEG <=640 px, Android 11+, android:canTakeScreenshot in accessibility_config.xml).
   - NovaService (search "task agent (v28)"): trigger in handle() (also for Groq/Gemini-heard text), agentBegin (spoken yes first, says screen text + small picture go to Gemini), agentRun, agentDo, agentAskYes, every step written to the chat as a "sys" line.
   - Triggers: sentence starts with "kaam"/"agent"/"khud se" (>=4 words), OR AI app (chatgpt/gemini) + photo + edit words, OR gallery + "Nth photo/select/chuno". One-step commands are never taken over.
   - Safety: DENY pay/buy/subscribe/upgrade/transfer/order/password/otp; protected screens (settings, permission, installer, Play Store, payment/bank, NOVA itself) refused and NOT sent to the cloud (no text, no picture); "send" auto-allowed only in com.openai.chatgpt and com.google.android.apps.bard; delete/post/submit/send elsewhere ask a spoken yes each time; screen text is declared untrusted in the system prompt.
   - Needs: Accessibility ON, Gemini key, internet. After installing the new APK the owner must switch the NOVA Accessibility service OFF and ON once (new screenshot capability).
2. LISTENING: command text used to come only from offline Vosk (weak for Hindi/Hinglish). Now NovaService.geminiHear(pcm) transcribes the clip with the owner's Gemini key when Vosk found no local command and (no Groq key or Groq failed); clips < 0.8 s never sent (MIN_HEAR_BYTES). yes/no answers still use ONLY local parsing (never cloud). Cost: one extra Gemini call per non-local utterance (free-tier quota). Best quality = Groq Whisper: the owner's Groq key is saved but never tested.
3. No UI (ui.html) change in v28.

## 5. v30: FREE IMAGE BY VOICE (decided by the previous assistant: voice/typed command + existing FreeImage providers; the agent route and a Pollinations key slot were NOT built)
- "nova ek billi ki photo banao" (also typed in chat) -> ImageIntent.parse -> NovaService.imageBegin -> FreeImage.run (Together with key, Pollinations no key, Hugging Face with token) -> ImageGen.saveToGallery -> chat via NovaService.imageListener -> spoken result. Details + owner test in HANDOFF_STATUS.md (v30 entry). Evidence: written + statically checked + test_image_contract.py (19 cases). Kotlin NOT compiled, CI NOT run, phone NOT tested.
- Open: does Pollinations' legacy endpoint work without a key right now (vendor articles say 1 request per 15 s, maybe watermark)? Settings > FREE IMAGE TEST answers it. If it fails, ask the owner for a free Together AI key (Settings slot exists) or discuss a Pollinations key slot after reading its docs.

## 5b. v31 OWNER RULES (quiet mode, read before touching any user-visible text)
- After a key is saved NOTHING announces which provider/model is used. Fallbacks (Gemini -> free chain, Gemini -> free image, Groq -> Gemini -> local listening) are automatic and silent. User-visible failures are generic ("Abhi photo nahi ban payi..."). Provider names appear only in Settings and in the explicit tests (SAB TEST, IMAGE TEST, FREE IMAGE TEST). tools/test_ui_contract.py fails if the old provider lines come back.
- No pop-ups by themselves: the Update Center never opens on its own; mode badge hidden; FREE FALLBACK switch hidden (always on).
- Permissions: ask once, at the moment of need (first ACTIVATE = mic + notifications + contacts; contacts also just-in-time). Android cannot turn permissions on silently: Accessibility / notification access / overlay / brightness stay one-time manual switches. Do NOT promise otherwise.
- NEVER relax: spoken yes for risky actions and for agent start, protected screens never sent to the cloud, typed yes never approves, no silent installs, keys never shown.
- Home page bug history: `app.className = ...` must always keep "wrap " (see HANDOFF_STATUS v31). Test the layout in headless Chromium (Playwright python) at 360x520, 360x640, 390x844.

## 6. Next jobs (in this order)
1. Greet in 3 lines of Hinglish (v30 free-image work is in this zip; run test_image_contract.py too). Owner uploads the zip -> "Unpack zip" -> "Build and Test APK". If RED: ask only for the red lines and fix first (likeliest spots: NovaService agent block + geminiHear, NovaAccessibilityService.collectAgent / agentScreenshot (TakeScreenshotCallback, TargetApi), AgentRulesTest).
2. If green: owner installs APK, switches Accessibility OFF/ON, then tests ONE AT A TIME and reports (chat shows "Gemini ne suna: ..." and "step N: ..." lines, ask him to send them): a) "nova kaam chatgpt kholo aur ek line likh do"; b) the photo job with a clear edit wish; c) the 4 manual checks from v27 (wake word, "battery kitni hai", "nova screen dekho" in Chrome, awaaz heard); d) Groq listening test; e) FREE IMAGE TEST in Settings, then "nova ek billi ki photo banao".
3. Fix only what he reports, one thing at a time. If the agent stalls, look at the step lines (what Gemini chose) before changing prompts.
4. Fix whatever the free-image test shows (section 5).
5. Known limits to tell him honestly: agent sees screen text + a small picture, ChatGPT/Gemini screens change between versions, Xiaomi gallery may need long_tap, 30-step/5-min cap, needs Gemini key + internet, one job at a time.
6. Ideas only on request: Settings switch to turn the agent off, live progress on the HUD, Hindi offline Vosk model, more UI polish.
