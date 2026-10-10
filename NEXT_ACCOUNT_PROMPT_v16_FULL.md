# NOVA - HANDOVER PROMPT v16 (written 2026-10-11). Replaces v31 and every older NEXT_ACCOUNT_PROMPT file.
Paste this whole file as the FIRST message of the next chat and attach NOVA_v15_assistant.zip (it is the v16 code; the zip name is kept on purpose, see section 1). The owner must NOT have to explain anything: read this, then HANDOVER_v16.md, then HANDOFF_STATUS.md (older history), then continue. If something is unclear, decide sensibly and say what you assumed in one line.

## 0. Owner and how to talk
- Owner = non-programmer, works ONLY from an Android phone (Xiaomi 24116RNC1I, Android 36), Chrome + GitHub web. No PC.
- Reply in Hinglish. SHORT messages (phone shows ~7 lines). ONE step at a time. Give full clickable URLs. Numbered steps.
- Never write DONE / WORKING / VERIFIED without evidence. State the evidence level of every change: written < statically checked < tests run on my computer < GitHub CI green < phone tested.
- Owner wants results, not excuses. Never say "limit ho gayi". If something cannot be done, say plainly what and why in 2 lines and offer the nearest thing that works.
- Owner cannot read long logs: ask only for "the red lines of the failed step" (a screenshot is fine).
- Every delivery: one zip + direct links. If the UI changed, say so; there is no screenshot tool for Kotlin, only headless Chromium (Playwright python is installed) for ui.html.

## 1. Repo, zip, workflows (do not change this method)
- Repo: https://github.com/devboutique0-wq/NOVA (public, branch main), owner account devboutique0-wq, package com.nova.assistant.
- CURRENT STATE OF THE REPO: UNKNOWN and probably OLD. The owner has NOT uploaded the v16 code yet. The newest code lives only in the attached zip. Do not assume the repo equals the zip.
- Return ONE zip named exactly NOVA_v15_assistant.zip, all files at the ROOT (the "Unpack zip" workflow expects that name; the code inside is v16, versionName 6.16). .yml workflow files inside the zip are ignored: deliver any .yml as a separate download.
- Owner steps for a new zip: 1) upload https://github.com/devboutique0-wq/NOVA/upload/main -> Commit changes. 2) https://github.com/devboutique0-wq/NOVA/actions -> "Unpack zip" -> Run workflow. 3) same page -> "Build and Test APK" -> Run workflow. 4) green run -> Artifacts -> APK -> install. Red -> send only the red lines.
- Other workflows: "Python tests (NOVA)", "Release APK", "Cleanup old files", make-keystore. Single file edit: https://github.com/devboutique0-wq/NOVA/edit/main/<path>
- CAUTION: unpacking a zip overwrites newer repo files. If the owner edited files on GitHub, first ask for Code -> Download ZIP (https://github.com/devboutique0-wq/NOVA/archive/refs/heads/main.zip) and merge.
- Sandbox facts: java exists, NO kotlinc / Android SDK / Gradle / network. python3 and node work. Kotlin is only read-checked by you; the real compiler is GitHub Actions.

## 2. App (what exists)
Android voice assistant "NOVA": Kotlin 1.9.24, AGP 8.5.2, minSdk 26, compile/target 34, JUnit 4.13.2. Vosk offline wake word "nova", WebView UI (app/src/main/assets/ui.html, JS bridge object `Android` = MainActivity.Bridge), main pipeline NovaService.handle(). Built earlier and CI-green up to v27 (build #61 green, phone SELF TEST pass=25 fail=0 skip=1): voice commands (torch, volume, brightness, alarms, apps, calls via dialer), floating card (NovaHud), accessibility screen control, Layer-1 brain shortcuts, Update Center, sleep-by-default, driving mode (notification reader), personal memory ("yaad rakh"), offline knowledge pack, free online AI chain (Groq -> OpenRouter -> Pollinations), Gemini/Groq listening for Hinglish, Hindi->Roman conversion.
Since v27 (NEVER compiled, NEVER on a phone): v28 task agent (reads screen via accessibility, Gemini picks ONE JSON action, AgentRules checks it), v28 geminiHear, v30 free image by voice (Together/Pollinations/HF), v31 quiet mode + layout fixes, and everything in section 3.

## 3. What v16 added (all WRITTEN + STATICALLY CHECKED + Python tests; Kotlin NOT compiled; phone NOT tested)
1. Website by voice: "nova ek restaurant ki website banao". SiteIntent.kt (pure parser) -> NovaService.siteBegin -> SiteGen.kt (one self-contained index.html from the owner's Gemini key(s), then Groq key; failure text never names a provider) -> saved to Downloads > NOVA (MediaStore, API 29+, else app Documents folder) -> chat message with DEKHO preview (sandboxed iframe, allow-scripts, no same-origin) via NovaService.siteListener / MainActivity / ui.html onSite. Phishing/fake words refused.
2. Publish: GitHubPublish.kt (GET /user, POST /user/repos public repo "nova-<slug>-<4 digits>", PUT contents/index.html on main, POST pages) with a token in SecureStore slot "github" (Settings > GITHUB PUBLISH; token link https://github.com/settings/tokens/new?scopes=repo&description=NOVA). Two ways: PUBLISH (GITHUB) button in chat (native HAAN dialog) and voice "nova website publish karo" (SiteIntent.isPublish -> publishBegin -> askFirst spoken yes -> publishRun). Result goes to chat via onPublish. Pages needs 1-2 min to go live.
3. Learn from the internet: LearnRules.kt (pure) + LearnStore.kt (learned.json in app files dir) + NovaService.learnBegin. "nova <topic> ke baare mein seekho" -> searchWeb (Gemini google_search); if that fails the free chain writes the note (src "ai", shown as "[AI se, internet nahi]"). A note is PENDING until the owner taps RAKHO in Settings > KYA SEEKHA; only kept notes answer questions (checked before KnowledgeStore.answer in handle). Notes are text only, never actions. Sensitive topic words are refused.
4. Conversation mode: after a SPOKEN reply NOVA listens again up to 3 times without the wake word (followWindow / followCount / MAX_FOLLOW in NovaService, set in utteranceEnded, consumed in the mic loop; ExtCfg.convo default ON; switch in Settings). Not after stop/sleep/agent/image/site/learn/publish or yes/no questions (NO_FOLLOW_KINDS).
5. Better voice: Cfg.pickBestVoice chooses the highest-quality OFFLINE voice installed for the language; defaults rate 0.95, pitch 0.9. Owner may need to install a better voice in Android Settings > Text-to-speech.
6. Proactive alert: low battery spoken at most every 30 min when idle (ACTION_BATTERY_LOW receiver registered in NovaService.onCreate, ExtCfg.alerts default ON, switch in Settings).
7. Fixes: "LOCAL MODE" badge removed (modeTxt null-safe); Update Center "KYA NAYA HAI" text corrected (no false llama.cpp claim); popup never opens by itself (only UPDATE CENTER button -> ucOpen; ucMaybe is a no-op); scroll fix verified in code (app.className always keeps "wrap "); FREE FALLBACK checkbox stays hidden by a script near the end of ui.html.
New/edited files: SiteIntent.kt, SiteGen.kt, LearnRules.kt, LearnStore.kt, GitHubPublish.kt (new); NovaService.kt, MainActivity.kt, Cfg.kt, ExtCfg.kt, ui.html, build.gradle.kts (versionName 6.16); tools/test_site_contract.py, tools/test_learn_contract.py (new).

## 4. NOT real / NOT built (never claim otherwise)
- NO offline AI model. The llama.cpp engine was removed (needs Kotlin 2.x / AGP 8.9; project is on 1.9.24 / 8.5.2). LocalBrains.factory is null. Offline = local commands + the fixed knowledge pack + kept notes only. Upgrading the toolchain is possible only as its own step with CI feedback after the build is green.
- No calendar/meeting alerts (needs READ_CALENDAR + a reader). Only the low-battery alert exists.
- "Free brain" = free Gemini/Groq keys with daily limits; with no key and no internet only local commands work.
- Android never allows silent permissions: Accessibility, notification access, overlay, brightness need one manual switch in Settings. First ACTIVATE asks mic + notification + contacts once.
- Task agent depends on ChatGPT/Gemini app screens (they change); 30 steps / 5 min cap; needs Accessibility ON (switch it OFF and ON once after installing a new APK) + Gemini key.
- Free image: Pollinations without key allows about 1 request per 15 s and may fail; Together needs a key. Settings > FREE IMAGE TEST tells the truth.

## 5. Hard rules (never relax)
- Risky actions (call, message, payment, delete, publish by voice) need a SPOKEN local "yes". A typed yes never approves. Native HAAN dialog taps are fine for the PUBLISH button.
- No silent installs. API keys / GitHub token never in logs, repo, URLs or sent back to the page. NOVA never rewrites its own code. Protected screens (settings, permission, installer, Play Store, payment/bank, NOVA itself) never go to the cloud.
- Quiet mode: after a key is saved nothing announces which provider/model is used; fallbacks are silent; user-visible failures are generic ("Abhi photo nahi ban payi..."). No pop-ups by themselves.
- Do NOT build anything that logs in/out of AI accounts to get around usage limits. The owner agreed to skip it.
- Code guards: no `!!`, no `catch (_`, no TODO in main code. Never use localStorage-style tricks in ui.html that need a PC.

## 6. Checks to run after EVERY change (all must pass)
cd to the unzipped root, then:
- for t in tools/test_*.py; do python3 $t; done   (10 files: agent, chain, hindi, image, knowledge, learn, memory, self_improve, site, ui)
- node --check on every <script> of app/src/main/assets/ui.html (extract with a regex; 8 scripts, 0 errors expected)
- every `Android.xxx(` used in ui.html must exist as `fun xxx(` in MainActivity.kt (script: set difference must be empty)
- brace balance of each edited .kt file (NovaService.kt paren count is -2 in the ORIGINAL too, ignore parens there)
- ui layout: Playwright headless Chromium at 360x520, 360x640, 390x844 (home page must scroll on small screens, no JS error).

## 7. Next jobs (in this order)
1. Greet in 3 lines of Hinglish. Ask the owner ONLY to do the upload steps from section 1 (zip is NOT on GitHub yet). Do not ask him to explain the project.
2. Build result: if RED, ask for the red lines only and fix first. Likeliest compile spots (never compiled): NovaService.kt (companion vals MAX_FOLLOW / NO_FOLLOW_KINDS / lastSiteHtml, siteBegin, learnBegin, publishBegin, batteryLowRx, the mic-loop followWindow block), MainActivity.kt (Bridge methods publishLastSite / startPublish / listeners), Cfg.pickBestVoice (TextToSpeech.voices / setVoice), SiteGen.kt, GitHubPublish.kt, LearnStore.kt. Also the older never-compiled v28/v30 blocks (agent, geminiHear, FreeImage).
3. If green: owner installs the APK, switches Accessibility OFF/ON, then tests ONE AT A TIME and reports: (a) chat: "nova ek restaurant ki website banao" + DEKHO; (b) PUBLISH with a GitHub token; (c) "nova solar panel ke baare mein seekho" + RAKHO + asking about it; (d) conversation mode (ask a question, then talk without "nova"); (e) free photo; (f) the agent job. Fix only what he reports, one thing at a time.
4. Later, only on request: toolchain upgrade for an offline model (own step), calendar alerts, better UI polish, Play Store (undecided).

## 8. v17 visual-only UI refresh (2026-10-11)
- Based on the owner's uploaded `NOVA_v15_assistant.zip`; package name and versionName 6.16 are preserved.
- `app/src/main/assets/ui.html`: added a final presentation-only theme layer with richer glass panels, cyan/blue/violet lighting, orbit rings, upgraded feature cards, chat/sheet transitions, responsive sizing, and reduced-motion support. Existing DOM ids, JS, and Android bridge contracts are retained.
- `app/src/main/java/com/nova/assistant/NovaHud.kt`: the floating voice card now has visible top-right minimize (minus) and close (X) controls. Minimize shrinks it into a small animated NOVA chip; tapping the chip restores the full card; X closes it with the existing exit animation. No wake-word, command, AI/API, permission or service-routing logic was intentionally changed.
- Evidence after patch: Python contract tests pass; all 9 embedded JavaScript blocks pass `node --check`; 75 distinct `Android.*()` calls were matched against methods in `MainActivity.Bridge` with no missing methods; `NovaHud.kt` brace counts are balanced.
- NOT VERIFIED: Kotlin/Gradle compile, GitHub CI, physical-phone behavior. Browser render attempt was blocked by this environment's Chromium policy, so layout is not claimed as visually verified.
- Next: upload this zip using the existing workflow steps in section 1, run `Unpack zip`, then `Build and Test APK`. If red, report only the error lines. After green, test the HUD on phone: tap minus to minimize, tap NOVA chip to restore, tap X to close.
