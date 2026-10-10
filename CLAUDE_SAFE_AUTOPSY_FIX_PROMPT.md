# CLAUDE MASTER AUTOPSY + SAFE REPAIR PROMPT — NOVA v17

You are auditing a real Android voice assistant, not creating a demo. The owner is a non-programmer and works primarily from an Android phone using Chrome and GitHub web. Explain results in short, simple Hinglish. Never make the owner understand Kotlin or build logs.

## Files to inspect

1. `NOVA_v15_original_baseline.zip` — original source before this change.
2. `NOVA_v15_assistant.zip` — updated candidate source (internal project versionName 6.17); includes `NOVA_SAFE_FALLBACKS_v17_REPORT.md` and tests.
3. This prompt.

**The candidate is not presumed correct. The original backup is the rollback/reference source.** Inspect both archives, compare file inventories, and compare all source changes. Do not assume GitHub `main` matches either ZIP. If you have repository access, read the repo before writing; the local ZIP may be newer than `main`.

## Hard safety rules

- Do not replace the app with a new sample app, redesign the architecture, or remove any existing feature to make tests easier.
- Preserve package `com.nova.assistant`, existing application identity, user settings/data compatibility, encrypted key handling, Vosk wake word, Gemini calls, command routing, memory, local knowledge, website generation/publishing, learning, image flows, popup/HUD, Android permissions, and GitHub Actions workflow structure.
- Do not edit or discard user keys, alter signing credentials, reset app data, delete memory, or change the original ZIP.
- Do not silently expand permissions, upload audio/screens/contacts/memory, remove protected-screen guards, bypass Android permissions, or remove spoken confirmation for risky actions.
- Do not add an unbounded action loop, arbitrary shell execution, background microphone bypass, or self-modifying code.
- Do not add a service that looks “free” but can silently start paid billing. Any optional provider with credits/possible billing must be clearly labelled and opt-in.
- No fake buttons, mocked network results, hardcoded test success, fake APK links, or claims that a model/provider is installed when it is not.
- Never claim “working/verified” without saying the evidence level: source review, static checks, local tests, GitHub CI, or physical phone.

## Autopsy requirements

### A. Compare baseline to candidate

1. Unzip both safely and list all files.
2. Compare file paths, hashes, Kotlin/HTML/XML resources, manifest, Gradle config, and tests.
3. Report any deleted file, deleted function, changed permission, removed command, weakened safety rule, changed key-storage path, or user-data migration risk.
4. Treat unintended deletion or semantic changes as a bug. Restore the original behavior and keep the new fallback in a separate, minimal patch.
5. Check there are no API keys, private tokens, signing files, generated APKs, build caches, or machine-specific paths accidentally packaged in the ZIP.

### B. Verify the Wikipedia public knowledge fallback

Check `PublicKnowledgeFallback.kt` and the hook in `NovaService.cloudStep`:

- It must run only when the existing FREE AI setting is ON.
- It must run after local command/knowledge routing and before Gemini, and only for explicitly recognizable general-information questions.
- It may send only the extracted topic to the public Wikipedia API—not full chat, audio, screen contents, saved personal memory, contacts, or notifications.
- Personal/security-related topics and live/current data (news, weather, prices, scores, “today/current/latest”, etc.) must be declined by this fallback and continue through existing safe routing.
- Network must be HTTPS with a meaningful User-Agent, short bounded timeouts, response-size cap, bounded cache, and graceful failure.
- Content is text only; it must never be parsed as commands or actions. Ensure the response includes a source title/link and does not claim to be live or AI-generated.
- Test the public API only with harmless topics such as “AI”, “solar panel”, or “quantum computing”; don't send personal user data during tests.
- Run the newly added JUnit tests `PublicKnowledgeFallbackTest` and `FreeImageRulesTest.qualityBriefIsBoundedAndIdempotent` in the real Gradle environment. Verify Hindi/English responses do not cross-contaminate the bounded cache.

### C. Verify image-generation improvements

- Confirm the quality brief is applied to text-to-image prompts without adding a separate AI request, and the helper is idempotent across Gemini-to-free fallback.
- Confirm image-edit prompts and attached image bytes are unchanged, including explicit consent before a photo is uploaded.
- Confirm output decoding, gallery saving, and content safety were not weakened. Confirm no accidental photo upload was introduced. The voice-image path currently uses the existing `FreeImage.run` flow; chat/UI image generation uses `ImageGen.generate`.
- Do not claim native 4K or local diffusion. The prompt enhancer is not an upscaler or model.
- Check each provider's actual current API/auth expectations against official docs. Treat Pollinations' keyless legacy endpoint as best effort only; do not repeatedly wait/retry indefinitely.
- If a fix requires a provider token, document it and keep it optional. Never put secrets in source code or URLs. Do not automatically use a possibly paid provider without explicit user opt-in.

### D. Existing assistant & phone-task regressions

Audit and regression-test:
- Vosk wake word and microphone lifecycle; no duplicate listeners or accidental auto-activation.
- Local deterministic commands still work with internet OFF and no keys.
- Offline knowledge pack, learned notes, memory commands, timers, media/device controls and local shortcuts remain intact.
- Gemini primary route and Groq/OpenRouter/fallback route preserve their original behavior when the new public lookup is ineligible or fails.
- Task agent still requires Accessibility and the configured model/key, uses `AgentRules.check`, bounds steps/time, respects stop/cancel, never acts on protected screens, and still requires spoken confirmation for risky actions.
- Images still validate real image bytes before display/save.
- Website creation/publishing and learn/approve workflow remain intact.
- No key, memory, private file, notification or screenshot is leaked by the new feature.

Do not interpret “phone ka koi bhi task” as permission to bypass OS restrictions. Android only permits tasks supported by declared features and enabled permissions. The task agent is not universal.

### E. Run the project's required checks

From the extracted candidate source root:

```sh
for t in tools/test_*.py; do python3 "$t" || exit 1; done
```

Also perform:
- `node --check` on every inline `<script>` in `app/src/main/assets/ui.html`.
- Check every `Android.method()` used in UI exists in `MainActivity.Bridge`.
- Parse all XML files and inspect the manifest.
- Run Kotlin/Gradle tests, lint and `assembleDebug` using a real Android SDK/Gradle environment or the repository's GitHub Actions. Do not substitute a brace counter for a compiler.
- Verify the output APK package, versionCode/versionName, APK signature/installability, and archive contents. Build from the checked source; do not copy an unrelated old APK.
- If a real endpoint is tested, use a harmless public topic, bounded request count, and no personal data.
- Test error conditions: no internet, Wikipedia 403/429/5xx, empty/bad JSON, question not eligible, local answer first, Free AI OFF, Gemini quota, fallback provider failure, image provider quota, and cancel/stop flows.
- Run viewport/HTML smoke tests only if available; don't claim on-device animation/audio behavior from headless browser tests.

### F. Repair policy

If anything fails, find the actual root cause and make the smallest backward-compatible fix. Add a regression test for each repaired issue. Re-run the complete test suite after every fix. Keep original features even when a test is broken; don't delete the test or weaken assertions to obtain green results. Do not bump dependencies/toolchain without a concrete reason and successful CI feedback. No `!!`, no `catch (_`, no TODO markers in main code.

If your environment lacks Android SDK/Gradle, continue static review and Python/JS/XML tests, then clearly mark Kotlin compilation, APK build, and physical phone tests as NOT VERIFIED. Do not invent success.

## Final deliverables required

1. One complete source ZIP named exactly `NOVA_v15_assistant.zip`, with project files at the archive root. Keep all `.yml`/`.yaml` workflow files as separate downloads if the user's Unpack ZIP workflow ignores them.
2. A genuine APK generated from the audited candidate, if and only if a real build succeeds; otherwise say no APK was built.
3. `CLAUDE_AUTOPSY_REPORT.md` with baseline comparison, missing/deleted items, bugs found, fixes made, exact test commands/results, evidence level, security/privacy findings, and outstanding blockers.
4. `CLAUDE_TEST_RESULTS.md` with pass/fail/unverified status for every check.
5. `CLAUDE_REPAIR_DIFF.md` explaining each source change and why it was necessary.
6. A short final Hindi/Hinglish handover with actual links only. Do not ask the owner to interpret stack traces; if a GitHub run fails, request only a screenshot of the red error lines.

## GitHub workflow / no-PC handover

If repository write access is available, use a dedicated audit branch and never overwrite `main` without permission. If no repository write access is available, do not claim changes are pushed. Give the owner only these steps:

1. Upload the checked `NOVA_v15_assistant.zip` here: https://github.com/devboutique0-wq/NOVA/upload/main and commit.
2. Run `Unpack zip`: https://github.com/devboutique0-wq/NOVA/actions
3. Run `Build and Test APK` on the same Actions page.
4. Download the APK artifact only after the run is green.

Do not ask the owner to delete the original backup. Never claim complete production readiness until CI and a physical phone smoke test succeed.

**Start now:** inspect both ZIPs, compare the source, execute all available tests, fix actual regressions, and return the evidence-backed deliverables. Do not stop at a report if you can safely repair the issue.
