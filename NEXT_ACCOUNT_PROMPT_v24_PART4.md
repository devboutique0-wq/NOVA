# NOVA - HANDOVER PROMPT v24 (PART 3 complete and wired; PART 4 + PART 5 LEFT). Written 2026-10-10.
Paste this whole file as the first message of the next chat and attach the newest NOVA zip. The owner must NOT have to explain anything. If unclear, decide sensibly, say what you decided, continue.
(Replaces v23. Sections 0,1,2,5,6 of v21 still apply: owner = non-programmer on an Android phone, writes Hinglish, wants short Hinglish replies, numbered steps, nothing fake; never say DONE/WORKING/VERIFIED without evidence; ladder: written < statically checked < compiled + tests run < GitHub CI green < phone tested. Repo https://github.com/devboutique0-wq/NOVA. Return ONE zip named exactly NOVA_v15_assistant.zip with all files at the ROOT; owner uploads at https://github.com/devboutique0-wq/NOVA/upload/main, then Actions > "Unpack zip" > Run workflow, then "Build and Test APK". Workflow files in the zip are ignored. Owner cannot read long logs: ask only for "the red lines of the failed step". After EVERY part deliver a zip + a new handover prompt. Owner wants things EARLY.)
Sandbox: no kotlinc / Android SDK, pip/npm/Maven unreachable; python3 works. Kotlin is only statically checked (bracket balance, no `!!`, no `catch (_`, no TODO).

## State
- Parts 1 (free online AI chain), 2 (personal memory), 3 (knowledge pack: knowledge.json 193 entries, KnowledgeMatcher.kt, KnowledgeStore.kt, wiring in NovaService.handle, KnowledgeMatcherTest.kt, SafetyInvariantsTest additions, SelfTest line) are written + statically checked. Nothing compiled or run on a phone. See HANDOFF_STATUS.md v24.
- Knowledge answers are text only; in quiet-replies mode they are shown, not spoken (kind "knowledge" is not in Logic.LOUD_KINDS). Ask the owner once if he wants them spoken even in quiet mode.

## Remaining work
PART 4: Hindi command model. Logic.norm keeps only a-z0-9, so Devanagari speech text is junk (Logic.isJunk true). Make Devanagari input work through HindiRoman.kt (transliterate to Roman first, then the existing classify), without loosening any safety rule (SAFE_KINDS, risky labels, yes/no parsing). Add tests; keep the Python contract tests green.
PART 5: integration + regression + ONE build.yml for the owner to paste (phone only) that also runs python3 tools/test_chain_contract.py, tools/test_memory_contract.py, tools/test_knowledge_contract.py and tools/validate_skillpack.py before the Gradle build. Then deliver zip + prompt v25.

## Honest limits to tell the owner
Nothing is compiled or run on a phone. The first GitHub build with Part 3 may be red (new files KnowledgeStore.kt, KnowledgeMatcherTest.kt, edits in NovaService.kt and SelfTest.kt): send only the red lines of the failed step. Free online providers need the owner's own free key once (Groq or OpenRouter).
