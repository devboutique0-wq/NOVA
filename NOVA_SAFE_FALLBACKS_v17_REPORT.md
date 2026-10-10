# NOVA Safe Fallback Upgrade v17 — Change & Verification Report

Date: 2026-10-11
Source baseline: uploaded `NOVA_v15_assistant.zip` (project versionName 6.16)
Candidate: `NOVA_v15_assistant.zip` (project versionName 6.17, default versionCode 7)

## What changed

### 1. Added API-key-free public knowledge fallback

New file: `app/src/main/java/com/nova/assistant/PublicKnowledgeFallback.kt`.

- Recognizes only explicit general-information questions (examples: “what is artificial intelligence”, “tell me about quantum computing”, “solar panel ke baare mein batao”).
- Runs only when the existing **FREE AI ON** setting is enabled.
- Runs after the existing local commands/knowledge pack and before a Gemini request, so some factual questions can be answered without spending an LLM request.
- Sends the extracted topic only to the public English Wikipedia MediaWiki API; it does not send voice audio, the whole conversation, saved memory, notifications, screen content, or phone data.
- Adds a source URL to returned article summaries.
- Skips explicit current/live requests (e.g. current/latest/today/weather/news/prices/scores) and personal/security-related topics.
- Uses HTTPS, a descriptive User-Agent, short connection/read timeouts, capped response size, and a bounded 12-hour in-memory cache (32 entries maximum). Hindi and English response variants use separate cache keys. Common short topics such as “AI” are allowed through the speech-noise filter.
- On no internet, API error, or unrecognized question it returns `null`; NOVA continues through the existing fallback chain.

This is a public knowledge lookup, **not another general AI model**. It can answer certain public factual lookups; it cannot replace reasoning, current news, personal assistance, or the task agent.

### 2. Image prompt quality brief without an extra AI request

`FreeImageRules.enhancePrompt()` adds a bounded, provider-neutral composition/lighting/texture brief to text-to-image prompts before the existing Gemini image call and free-provider fallback. It is deterministic and adds no separate AI request. It is idempotent so the fallback does not append the quality brief twice. Existing provider order/key-storage logic is preserved, and photo-edit prompts plus attached image bytes remain unchanged. This may improve prompt clarity but does **not** upscale a generated image or guarantee native 4K resolution; output quality/resolution still depend on the provider/model and its current limits.

### 3. Clearer UI disclosure

The **FREE ONLINE AI** Settings copy now explains the topic-only Wikipedia fallback, its no-key status, the current-information/personal-topic exclusions, and the fact that the legacy no-key Pollinations chat attempt may fail. Existing controls and IDs remain unchanged.

### 4. Version metadata

`app/build.gradle.kts`: default versionCode 7 and versionName 6.17, unless the GitHub workflow supplies `NOVA_VERSION_CODE`.

## Source inventory comparison

Compared with the uploaded baseline ZIP before packaging:
- Existing file paths deleted: **0**.
- Existing runtime/test files changed: `app/build.gradle.kts`, `app/src/main/assets/ui.html`, `app/src/main/java/com/nova/assistant/ExtCfg.kt`, `FreeImage.kt`, `ImageGen.kt`, `NovaService.kt`, and `app/src/test/java/com/nova/assistant/FreeImageRulesTest.kt`.
- Added files: `app/src/main/java/com/nova/assistant/PublicKnowledgeFallback.kt`, `app/src/test/java/com/nova/assistant/PublicKnowledgeFallbackTest.kt`, `tools/test_public_knowledge_contract.py`, this report, and `CLAUDE_SAFE_AUTOPSY_FIX_PROMPT.md`.
- No `.yml` / `.yaml` workflow files are present in the uploaded source ZIP, so this update adds none and does not replace the repository's GitHub Actions workflows.

## Existing features deliberately preserved

No files or functions were intentionally removed. The following areas were not redesigned: Vosk wake word, Kotlin service/pipeline, Gemini request path/tool calling, Groq STT, OpenRouter/Groq chain, LocalSkills, local knowledge pack, memory, website generation/publishing, learning workflow, image intent, existing image providers, accessibility task-agent policy, popup/HUD, encrypted key storage, Android manifest permissions, spoken confirmation rules, and protected-screen blocks.

The voice image path still uses its existing free-provider path (`FreeImage.run`). Chat/UI image generation continues to use the existing `ImageGen.generate` path. Only the prompt sent to free image providers gained a quality brief.

## What remains limited / not claimed

- There is **no installed offline generative LLM** in this build: `LocalBrains.factory` is still unset by `LocalBrains.installEngine`. Offline operation is the existing local commands, knowledge pack, saved notes/memory, and deterministic skills—not arbitrary ChatGPT-level conversation.
- No provider offers guaranteed unlimited free usage. Groq/OpenRouter use user-owned keys and rate limits; the keyless Pollinations legacy path may fail or be limited; optional image services can require keys, credits, or billing. Image generation may fail even when the text assistant works.
- Universal control of “any phone task” is not guaranteed. The existing task agent depends on Android Accessibility permission and Gemini credentials, and intentionally blocks protected/banking/payment/password screens. Risky actions still require a spoken confirmation.
- This environment did not have Android SDK/Gradle available, so Kotlin was not compiled and an APK/physical-phone run could not be verified here. GitHub Actions build + phone testing are required before calling this production-ready.

## Checks actually run in the available environment

- All **12** `tools/test_*.py` scripts passed, including the new `test_public_knowledge_contract.py` (31 contract/parser checks).
- All **10** embedded JavaScript blocks in `ui.html` passed `node --check`.
- All **75** unique `Android.*()` bridge method references found in `ui.html` matched method names found in `MainActivity.kt`.
- All **12** Android XML resources/manifests parsed successfully.
- Headless Chromium UI smoke checks passed at **360×520**, **360×640**, and **390×844** using a test-only mock Android bridge: no horizontal overflow or JS errors, and 3D icon tap feedback applied. This is not a physical Android UI test.
- A source scan found no `!!` or `catch (_` patterns in the app's main Kotlin files.
- Added Kotlin/JUnit regression coverage for public-topic parsing, AI short acronyms, personal/current/action-query rejection, prompt bounds, and prompt-enhancer idempotence. These JUnit tests were **not executed here** because the Android Gradle build environment is unavailable.
- A harmless public Wikipedia `solar panel` query returned JSON containing an article extract and source URL when independently checked; this verifies the public endpoint response format, not the app's Android networking path.
- Basic delimiter triage passed for the new/edited files; `NovaService.kt` has the pre-existing parenthesis-count difference of -2 documented in the project's handover, which a count-only check cannot resolve.

These are static/contract checks only. They do not prove Kotlin compilation, live service availability, billing status, successful image generation, or physical phone behavior.

## Official references consulted

- MediaWiki API Search: https://www.mediawiki.org/wiki/API:Search/en
- MediaWiki API etiquette (User-Agent/caching/request guidance): https://www.mediawiki.org/wiki/API:Etiquette/en
- Pollinations current API documentation (generation authentication and legacy-key limits): https://gen.pollinations.ai/docs
- Groq rate limits: https://console.groq.com/docs/rate-limits
- OpenRouter rate limits: https://github.com/OpenRouterTeam/docs/blob/main/api_reference/limits.mdx
- Hugging Face Inference Providers pricing: https://huggingface.co/docs/inference-providers/pricing
- Gemini API pricing: https://ai.google.dev/gemini-api/docs/pricing
