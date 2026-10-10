# NOVA - next chat prompt (start here: PART 1B)

You are continuing an Android (Kotlin) voice-assistant project called NOVA. The owner writes Hinglish; reply in Hinglish, short, no fluff.
Read first: HANDOFF_STATUS.md (bottom: v18a), CLAUDE_AUTOPSY_PROMPT.md (14 invariants), OWNER_MASTER_UPGRADE_PROMPT.md.

## Ground rules (owner's words: "real kuch bhi fake nahi")
1. Say exactly what was verified. Ladder: written < statically checked < compiled/unit-tested < CI green < phone tested. Never claim a higher level than reached.
2. First check the sandbox: `which kotlinc java node python3` and a network test. If there is no kotlinc, say so; Kotlin can only be statically checked (use a real tokenizer for brace balance, grep `!!`, `catch (_`, TODO). If kotlin-compiler-embeddable + junit jars exist, compile the pure files and run the tests (earlier sessions did: 166 tests).
3. Work in SMALL parts; after each part give a save-point zip so a usage limit never loses work. Update HANDOFF_STATUS.md every part.
4. Invariants that matter here: no key needed to activate NOVA; no secrets in source/zip; no screen text or memory ever sent to any cloud; dangerous actions keep the spoken yes; fallback is FAILOVER between different providers/models, never rotating many accounts of one provider for quota.

## State
Part 1A is written (pure core, NOT compiled/run): FreeProviders.kt + FreeProvidersTest.kt. Nothing in the app calls it yet.

## PART 1B - do this now
1. `FreeHttp.kt` (Android-side, small): POST JSON with HttpURLConnection, headers Content-Type, Authorization: Bearer <key> when key not empty, for OpenRouter also `HTTP-Referer` and `X-Title: NOVA`; connect timeout 6 s, read timeout 10 s; returns NetAnswer(code, body); no internet -> 598, any IO/timeout -> 599; response body cap 200 KB; NEVER throws; NEVER logs keys, questions or answers.
2. Keys: reuse SecureStore.getGroq/saveGroq for Groq (same key as speech-to-text). Add an "openrouter" slot via SecureStore.saveSlot/getSlot. MainActivity Bridge: save/clear/has for OpenRouter; ui.html Settings: one row each for Groq and OpenRouter (masked, never echoed back) + a switch "Free online AI" (Cfg.freeChain, default ON) with a plain line "Sirf tumhara sawaal in free services ko jaata hai".
3. NovaService wiring: new answer source order = Gemini (existing, only if user key) -> ProviderChain (Groq, OpenRouter, Pollinations) -> offline model (LocalBrains) -> honest local message. Keep `Routing.run` shape; add the chain as part of the cloud step or as a new step, and change AiRouter.plan so the cloud is "configured" when Gemini key OR chain enabled. Keep ONE shared `Cooldowns` instance in the service.
   - Before any network call: LocalChat.preAnswer (crisis / live-info) must answer locally; junk text never leaves the phone.
   - After the chain answers: LocalChat.guard (health/money/legal safety line); prefix nothing; set lastKind = "free_chain".
   - The chain's answer is TEXT ONLY, never an action.
   - On failure use FreeProviders.failText(result.failKind, en), and map failKind to AiRouter cooldown via Routing.CloudResult.
4. Tests: extend RoutingTest/AiRouter tests for the new plan rule. Pure parts only in unit tests.
5. Python mock-server proof (this one CAN run without kotlinc): `tools/mock_provider_server.py` (http.server) that imitates 429 / 401 / 503 / garbage-200 / slow / good answers for 3 fake providers, and `tools/test_chain_contract.py` that checks the JSON request shape produced by buildBody (copy the expected shape from FreeProvidersTest) and the reply shape parseReply expects. State clearly that this proves the CONTRACT, not the Kotlin code.
6. Guards, node --check on ui.html script blocks, update HANDOFF_STATUS.md (v18b), save-point zip.

## Then, one part per message
- PART 2 personal memory: "yaad rakh ...", "mujhe kya yaad hai", "bhool ja ..."; local only, never in logs/uploads; refuse OTP/password/card/Aadhaar-like data; only relevant items go into an AI prompt (and never to the free chain unless the user asked a question that needs it, max 3 items); wipe command; PersonalMemory.kt (pure) + store (SharedPreferences, no secrets) + tests.
- PART 3 knowledge pack: skillpacks/starter.json bigger + pure matcher (normalize, token overlap, threshold, "no match" is honest); Python validator for the pack format; matcher also run in Python as a cross-check.
- PART 4 Hindi command model: HindiRoman/Logic synonyms, Devanagari + Hinglish normalization, 100+ example-command table test; Hindi few-shot lines for the offline model prompt.
- PART 5 integration + regression: re-run every test and guard, check the 14 invariants, final HANDOFF_STATUS.md, complete zip.

## Honest limits to repeat to the owner
- Free tiers of Gemini / Groq / OpenRouter need the owner's own free key once; only Pollinations works with no key.
- Real "green" = GitHub "Build and Test APK" run; phone behaviour needs a phone test.
