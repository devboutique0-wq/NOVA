# NOVA - HANDOVER PROMPT v23 (PART 3 knowledge pack: data + tools DONE, Kotlin wiring LEFT). Written 2026-10-10.
Paste this whole file as the first message of the next chat and attach the newest NOVA zip. The owner must NOT have to explain anything. If unclear, decide sensibly, say what you decided, continue.
(Replaces v22. Sections 0,1,2,5,6 of v21 still apply: owner = non-programmer on an Android phone, writes Hinglish, wants short Hinglish replies, numbered steps, nothing fake; never say DONE/WORKING/VERIFIED without evidence; ladder: written < statically checked < compiled + tests run < GitHub CI green < phone tested. Repo https://github.com/devboutique0-wq/NOVA. Return ONE zip named exactly NOVA_v15_assistant.zip with all files at the ROOT; owner uploads at https://github.com/devboutique0-wq/NOVA/upload/main, then Actions > "Unpack zip" > Run workflow, then "Build and Test APK". Workflow files in the zip are ignored. Owner cannot read long logs: ask only for "the red lines of the failed step". After EVERY part deliver a zip + a new handover prompt. Owner wants things EARLY: deliver as soon as the part is done.)
Sandbox: no kotlinc / Android SDK, pip/npm/Maven unreachable; python3 works. Kotlin is only statically checked (bracket balance, no `!!`, no `catch (_`, no TODO).

## State
- Parts 1 (free online AI chain) and 2 (personal memory): written + wired, statically checked only.
- PART 3 knowledge pack: DONE = app/src/main/assets/knowledge.json (193 entries; source tools/knowledge_src/k_*.py; rebuild with `python3 tools/build_knowledge.py`), tools/validate_skillpack.py, tools/knowledge_port.py, tools/test_knowledge_contract.py (all pass in sandbox). Run all three after any data change.
- KnowledgeMatcher.kt (pure) exists; this chat added KEEP1 and 3 STOP words. NOTHING in the app calls it yet.

## Design decisions (keep)
1. knowledge.json is separate from skillpacks/ (do not touch skillpacks/). Format {"title","version","entries":[{"id","q","hi","en","tags"}]}.
2. Matcher rules are in KnowledgeMatcher.kt (dice score >=60, tie margin 5, live words -> null, text only, never an action). Answer language: cfg.lang == "en" -> en else hi.
3. Wiring place: NovaService.handle, AFTER `c` (classify / shortcut / groq text) is still null and BEFORE `brain.record(now, text, "unknown", "")` (around line 982). `said` = groqText if non-blank else text; skip when groqText blank and Logic.isJunk(text). On a hit: lastTurnLocal = true; lastKind = "knowledge"; brain.record(now, text, "local", "knowledge"); return the answer. Runs before the offline translator (Layer 2) and Gemini / free chain.
4. Logic.classify and LocalSkills run first, so commands win. The validator already bans command-shadowing words in variants.

## Remaining work (in this order, then deliver zip + prompt v24)
D. app/src/main/java/com/nova/assistant/KnowledgeStore.kt: lazy-load assets/knowledge.json once (assets.open, cached, never throws, @Volatile/synchronized) and `fun answer(ctx: Context, text: String, en: Boolean): String?` = KnowledgeMatcher.match + reply. Then the NovaService wiring from decision 3. Optional: a self-test line "knowledge: N entries loaded" (HealthCheck / SelfTest).
E. Kotlin tests in app/src/test/java/com/nova/assistant/: KnowledgeMatcherTest.kt (hits, near-misses, no match, Hinglish/English variants, junk, live-word null, tie null, within1, parse of a tiny json, parse skips a bad entry, KEEP1 vitamin c vs d) + a test reading "src/main/assets/knowledge.json" asserting every entry's first variant matches its own id; add to SafetyInvariantsTest: "knowledge" and "local_skill" are not in Brain.SAFE_KINDS (check Brain.SAFE_KINDS first).
F. HANDOFF_STATUS.md "v24 Part 3 complete" with the honest ladder level; zip + prompt v24 for PART 4 (Hindi command model: Logic.norm keeps only a-z0-9 so Devanagari is junk; HindiRoman.kt exists) and PART 5 (integration + regression + one build.yml to paste adding python3 tools/test_chain_contract.py, test_memory_contract.py, test_knowledge_contract.py and validate_skillpack.py).

## Honest limits to tell the owner
Nothing is compiled or run on a phone. Part 3 so far = 193 data entries + tools that pass in the sandbox, NOT wired: the phone behaves exactly as before. Free online providers need the owner's own free key once (Groq or OpenRouter). The first GitHub build may be red: send only the red lines of the failed step.
