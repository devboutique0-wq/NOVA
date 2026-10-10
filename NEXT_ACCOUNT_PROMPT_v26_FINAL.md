# NOVA - HANDOVER PROMPT v26 (Parts 1-5 written; BUILD + PHONE TEST LEFT). Written 2026-10-10.
Paste this whole file as the first message of the next chat and attach the newest NOVA zip. The owner must NOT have to explain anything. If unclear, decide sensibly, say what you decided, continue.
(Replaces v25. Sections 0,1,2,5,6 of v21 still apply: owner = non-programmer on an Android phone, writes Hinglish, wants short Hinglish replies, numbered steps, nothing fake; never say DONE/WORKING/VERIFIED without evidence; ladder: written < statically checked < compiled + tests run < GitHub CI green < phone tested. Repo https://github.com/devboutique0-wq/NOVA. Return ONE zip named exactly NOVA_v15_assistant.zip with all files at the ROOT. Workflow files inside the zip are ignored: any .yml is delivered as a separate download. Owner cannot read long logs: ask only for "the red lines of the failed step".)
Sandbox: no kotlinc / Android SDK / Gradle, pip/npm/Maven unreachable; python3 works. Kotlin is only statically checked (bracket balance, no `!!` in main code, no `catch (_`, no TODO). Build is Kotlin 1.9.24, AGP 8.5.2, JUnit 4.13.2.

## State (details in HANDOFF_STATUS.md v24, v25, v26)
Part 1 free online AI chain, Part 2 personal memory, Part 3 knowledge pack (193 entries, KnowledgeStore + wiring in NovaService.handle), Part 4 Hindi / Devanagari input (HindiRoman rule transliteration at the top of handle), Part 5 review + python-tests.yml: all written + statically checked; five Python contract scripts pass. NOTHING compiled, NOTHING run on a phone.

## What the owner does (give him these as numbered Hinglish steps)
1. Upload the zip at https://github.com/devboutique0-wq/NOVA/upload/main, commit.
2. Actions > "Unpack zip" > Run workflow. Then Actions > "Build and Test APK" > Run workflow.
3. Optional: paste python-tests.yml at https://github.com/devboutique0-wq/NOVA/new/main?filename=.github/workflows/python-tests.yml and run it.
4. If a run is red: send only the red lines of the failed step. First job of the next chat = fix exactly those errors (most likely compile errors in the new files KnowledgeStore.kt, HindiRoman.kt, edits in NovaService.kt / SelfTest.kt, or the new tests KnowledgeMatcherTest.kt / HindiRomanTest.kt), re-run all five python3 tools/test_*.py + validate_skillpack.py, deliver a new zip.
5. If green: install the APK, press ACTIVATE, run SELF TEST (expect "Knowledge pack: 193 entries loaded"), type a Hindi question (पानी कितने डिग्री पर उबलता है) and a Roman one (what is the capital of india), say one voice command. Report what happened; fix phone-only problems one at a time.

## Known open points
- Knowledge answers are text only; in quiet-replies mode they are shown, not spoken (kind "knowledge" is not in Logic.LOUD_KINDS). Ask the owner once if he wants them spoken.
- Offline Vosk returns Roman letters only; Devanagari helps for typed text and Groq Whisper. A Hindi offline model (vosk-model-small-hi-0.22) as a second recognizer is a possible later step.
- build.yml was not changed (not available in the zip); only a separate python-tests.yml was added.
- Free online providers need the owner's own free key once (Groq or OpenRouter).
