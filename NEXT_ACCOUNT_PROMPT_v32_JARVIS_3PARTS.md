# NOVA - HANDOVER PROMPT v32 (written 2026-10-11). Adds to v31; read v31 sections 0-2 and 5b first (rules, repo, quiet mode).
Paste this file as the FIRST message of the next chat and attach NOVA_v15_assistant.zip (it contains v32 code + this file). The owner must NOT have to explain anything. If something is unclear, decide sensibly, say what you decided, continue.

## 0. Status when this was written
- v32 (already in the zip): Understand.kt loose local understanding + Gemini prompt/gate fixes (see HANDOFF_STATUS.md v32 entry). Evidence: written + statically checked + python contract tests. Kotlin NOT compiled, CI NOT run, phone NOT tested.
- FIRST JOB: owner uploads the zip -> "Unpack zip" -> "Build and Test APK". If RED: ask only for the red lines, fix, repeat. Do not start the parts below until v32 is green (or the owner says go anyway).
- Owner (Hinglish, Android phone only, non-programmer): wants SHORT Hinglish replies, one step at a time, nothing fake, no DONE without evidence. Same delivery rules as v31 section 0 (one zip NOVA_v15_assistant.zip, .yml separate, preview + direct links, ask only for red lines).

## 1. What the owner asked (his words, summarised)
1. "Screen analyse karo" today runs once and stops - it does exactly what is said and nothing more. He wants JARVIS style: a real session, follow-up questions, more detail on request, helpful next steps.
2. The screen ALERT keeps speaking every little while ("Screen alert: failed. Say analyze screen for details."). Fix it.
3. Add Qwen as an automatic fallback WITHOUT asking the user. After the user has given the app permissions ("allow, allow") everything must load and set up by itself in the background. Users must not do setup steps.
4. He wants it built in 3 PARTS (usage limit). Tell him the size of each part before starting (he asked this last time too). Rough sizes: Part 1 = medium, Part 2 = small-medium, Part 3 = medium to large. Say "ye andaza hai" - never promise.

## 2. Facts found in the code (verify before editing; line numbers are v32)
- analyzeScreen() (NovaService.kt ~2635): one shot. Reads accessibility text (NovaAccessibilityService.readScreen(false), password-free), if NOVA is in front it waits 8 s for the user to open another app, sends the text (max 6000 chars) to Gemini with cloudBody(.., false, null) (no tools, no history), speaks the reply, ends. Without a Gemini key it only reads the top 8 lines. No follow-up, no memory of the screen, no picture.
- Conversation mode exists: ExtCfg.convo + followWindow + MAX_FOLLOW = 3 (listen again after a spoken reply without the wake word). Gemini chat history exists (history, Logic.HISTORY_TTL_MS = 120 s, text only).
- v28 task agent already has a screenshot path: NovaAccessibilityService.agentScreenshot (JPEG <= 640 px, Android 11+) and agentSnapshot (numbered items, no password fields). Protected-screen refusal rules are in AgentRules (settings, permission, installer, Play Store, payment/bank, NOVA itself are NOT sent to the cloud).
- Screen alert: NovaAccessibilityService.scan -> Logic.detectAlert(lines) matches VERY broad words in any short (<=100 chars) UI line of ANY app: "failed", "error", "warning", "unable to", "could not", "blocked", "crashed", ... then NovaService.announceIfIdle("Screen alert: $hit. Say analyze screen for details."). Guard: Logic.shouldAnnounce with ALERT_MIN_GAP_MS = 15 s (different alert) and ALERT_SAME_GAP_MS = 120 s (same alert); scan gap 3 s. So any chat, news or settings page containing the word "failed" makes NOVA speak every 2 minutes. That is the bug the owner hears. The spoken text also tells the user to do work ("say analyze screen") instead of doing it.
- Qwen: ModelCatalog.kt has Qwen2.5-0.5B-Instruct Q4_K_M (491 MB, SHA-256 + size checked by ModelDownload.kt, RAM gate 3000 MB). ModelAuto.kt says Qwen is NEVER auto-downloaded because LocalBrains.factory == null: the offline llama.cpp engine was REMOVED (needs Kotlin 2.3 / AGP 8.9.1; see v31 section 3: do not re-add it, do not re-add configurations.all force). LocalChat.kt already has a ChatML prompt builder for Qwen2.5. The ONLINE free chain (Groq -> OpenRouter -> Pollinations, FreeProviders.kt, ExtCfg.freeChain) is automatic and text-only.
- Auto setup today: voice-check model (14 MB) downloads by itself when the mic service starts (ModelAuto.autoStart, every 6 h). v31 rule: permissions are asked once, just-in-time (first ACTIVATE = mic + notifications + contacts).

## 3. HONEST LIMITS - tell the owner in 3 lines before Part 3
- Android does NOT let any app switch on Accessibility, Notification access, Overlay or Brightness by itself. Those stay one-time manual switches ("allow, allow"). Do not promise otherwise.
- "Qwen automatically" has two meanings: (A) ONLINE Qwen through a free provider = no download, no RAM, works at once. (B) OFFLINE Qwen on the phone = 491 MB download + a llama.cpp engine that does not build with the current toolchain. Recommend (A) first, and ask the owner ONE question: "Offline Qwen bhi chahiye? Iske liye build system upgrade karna padega, risk hai." If B: Wi-Fi only, resumable, silent, with the existing size + SHA-256 + RAM + free-storage checks; never on mobile data without his OK because it costs money.
- Do not invent model ids: look up the currently free hosted Qwen model on OpenRouter / Hugging Face / Together at build time (web search) and read the provider's docs. Put ids in one table (like ModelCatalog) so a change is one line.

## 4. PART 1 - JARVIS screen session ("screen analyse karo")  [size: medium]
Goal: "nova screen dekho" starts a SESSION, not a one-shot.
- Reply first in 2 short sentences (what app, what is on it, one thing that matters). Then stay listening (use the existing follow window; for the screen session allow up to ~6 follow-ups or ~90 s, then end quietly). The owner can say: "aur detail", "isme important kya hai", "ye kya hai", "ise padh ke sunao", "iska jawab likho", "aage kya karu", "ruko / bas".
- Keep the screen text + a short Gemini chat history for the session (text; optional small screenshot via agentScreenshot only when the text is empty or the owner says "picture dekho / ye image kya hai"). Re-read the live screen when he says "ab ye dekho" or the foreground app changed.
- Give next-step SUGGESTIONS in words ("Reply likhu? Haan bolo"). NEVER act by itself: every action keeps the spoken yes (v31 rules). A suggested action runs through the existing command / agent path.
- Protected screens (bank, payment, password, OTP, settings, installer, NOVA itself) are NOT sent to the cloud: say so and offer to read the visible non-secret text locally.
- No Gemini key: local fallback reads top lines and says that detail needs a key (no provider names, v31 quiet rule).
- Tests: pure helper for session state (turn cap, timeout, intent words: more / stop / read / reply), python contract test, UI preview only if the chat UI changes (else say "no UI change").

## 5. PART 2 - Screen alert fix  [size: small-medium]
Goal: speak rarely, only when it matters, and do the useful thing.
- Make detectAlert strict: require dialog-like context (a short line with an alert phrase AND an OK/Retry/Cancel/Close style button, or a system/app error dialog) and an ignore list for apps where those words are content (messaging, browsers, social, news, launcher, NOVA itself, keyboards). Drop the generic words "error", "warning", "could not", "unable to" on their own.
- Rate limit: once per (package + alert) per screen visit, 10 min snooze, global cap ~3 spoken alerts per hour, never while NOVA is busy / listening / driving mode quiet rules, never when the screen is off.
- Change the sentence: not "Say analyze screen for details" but a one-line reason + "Detail bolun? Haan bolo." A spoken yes opens the Part 1 session on that screen. No yes = silence.
- Settings: keep the on/off switch; add nothing noisy. A chat line (not speech) may show why an alert fired, for debugging.
- Tests: Logic unit tests (false positives: a chat message saying "payment failed" in a messaging app must NOT speak; a real "App isn't responding" dialog must), shouldAnnounce rate limits, python contract test.

## 6. PART 3 - Auto setup + Qwen fallback  [size: medium to large]
Goal: after the owner has pressed "allow" on the permission screens, the rest happens by itself.
1. Order of the free chain becomes: Gemini (own key) -> Groq -> OpenRouter Qwen (online, free model found at build time) -> Pollinations -> offline. All automatic, silent, text only, never an action (existing rule). Nothing announces a provider (v31 quiet rule). Add the Qwen row to FreeProviders + Settings test button (provider names are allowed only in Settings and explicit tests).
2. A single background "auto setup" runner (one place, e.g. AutoSetup.kt, started from the service after permissions): voice-check model, optional offline model (only if owner chose B), health check of providers with a small self test, retries with back-off, no pop-ups, no spoken progress. Results go to Settings > SELF TEST as lines.
3. Permissions: keep just-in-time asks (v31). Add the battery-optimisation exemption request ONLY as the standard one-tap system dialog at a sensible moment, and a calm one-line checklist in Settings for the manual switches (Accessibility, Notification access) with direct deep links. No promises of silent granting.
4. If B (offline Qwen): LocalBrains factory + llama.cpp engine need Kotlin 2.x / AGP upgrade. Do it in its OWN build.yml experiment first, keep the working build untouched, and only merge when "Build and Test APK" is green. If it does not build, drop B and say so honestly.
5. Tests: provider order test, auto-setup state machine test (pure), python contract test, existing Routing/FreeProviders tests must still pass.

## 7. After each part
State the evidence level, give the preview/links, and give the owner ONE test to run on the phone with the exact sentence to say. Update HANDOFF_STATUS.md and write the next prompt (v33...). Run all python contract tests: test_understand_contract, test_chain, test_hindi, test_knowledge, test_memory, test_self_improve, test_agent, test_voice, test_image, test_ui and validate_skillpack.
