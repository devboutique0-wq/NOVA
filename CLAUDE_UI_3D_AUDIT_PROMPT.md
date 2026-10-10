# Claude Audit Request — NOVA 3D Icons + Tap Animations

You are independently auditing the attached `NOVA_v15_assistant.zip`. The project is an existing Android Kotlin voice assistant. The owner is a non-technical user and asks for a careful check that the UI-only update did not break anything.

## Your assignment
Read this prompt first. Then inspect the full project, the handover/status files, and the exact source diffs. Confirm whether the 3D-themed icons, tactile tap animations, launcher icon polish, and native popup button feedback introduce any regression. Do not rely only on the test summary below; reproduce the checks yourself.

## Expected changed files
1. `app/src/main/assets/ui.html`
   - Appends a presentation-only style pack named `nova-3d-icons-v17` and event-delegated tap-feedback script named `nova-3d-tap-v17`.
   - Intended effects: beveled/glass 3D depth for icon buttons and feature cards, press/bounce animations, focus-visible styling, and reduced-motion support.
   - The script must only toggle a CSS class; it must not prevent or replace existing clicks, alter bridge methods, or change command behavior.
2. `app/src/main/java/com/nova/assistant/NovaHud.kt`
   - Adds visual pressed feedback to the floating native popup's existing minimize/close controls.
   - Preserve existing control geometry, minimize/restore logic, dismissal logic, lifecycle, and callback wiring.
3. `app/src/main/res/drawable/ic_launcher_foreground.xml`
   - Adds static beveled/glossy highlights to the same NOVA neon orb icon.
   - Must not rename resources or change the package/manifest.
4. `CLAUDE_UI_3D_AUDIT_PROMPT.md` and `UI_3D_REFRESH_REPORT.md` are documentation only; they are not app runtime changes.

## Hard safety rules
- Do not replace or rewrite the app. Do not alter Vosk, wake-word behavior, Gemini or other AI provider routing, command parsing, microphone/services, accessibility, permissions, persistence, keys/tokens, update logic, GitHub publishing, dangerous-action confirmation or data formats.
- Do not delete working functionality to make a build pass.
- No API keys or credentials in logs, reports, ZIP, screenshots, or source.
- Never say a build/test passed unless it really ran. Distinguish source/static tests, browser tests with a fake Android bridge, GitHub CI, real Android build, and physical-phone testing.
- Preserve `com.nova.assistant`, Gradle/AGP/Kotlin versions, min/target SDK, package identity and user-data compatibility.

## Required independent checks
1. Compare against the original/source state available to you and list every changed/added file. Inspect full diffs; intended runtime changes should be limited to the three files above.
2. Run every `tools/test_*.py` test and report each test's exact pass/fail result.
3. Extract every inline script from `app/src/main/assets/ui.html`; run `node --check` on every script independently.
4. Check that all `Android.xxx(` references in `ui.html` have corresponding bridge functions in `MainActivity.kt`; the missing set must be empty.
5. Parse all Android XML resources. Specifically validate `ic_launcher_foreground.xml` and verify the vector features are compatible with the project's Android resource toolchain.
6. Inspect Kotlin code around `NovaHud.onTouchEvent`, `drawControl`, `toggleMinimized`, `hide`, and `destroy`. Verify Canvas save/restore balance, pressed-state reset on UP/CANCEL, and that button hit targets/callbacks still behave as before.
7. If Gradle + Android SDK are available, run the actual Android compile, unit tests and APK build. If unavailable, mark the Android build as BLOCKED/UNVERIFIED; do not fake an APK.
8. If Chromium/Playwright is available, load `ui.html` at 360x520, 360x640 and 390x844 with an explicitly identified test-only Android bridge stub. Check console/page errors, page width, scrolling, icon depth styles, tap class application/removal, reduced-motion behavior, and that a pre-existing UI button still opens its original panel.
9. Check whether the additional event listener interferes with click propagation. The new script must not call `preventDefault`, `stopPropagation`, or rebind existing handlers.

## Starting evidence (reproduce it; do not take it on trust)
- 11 `tools/test_*.py` scripts passed in the local working environment after the touch-feedback signature was adjusted to preserve existing static contracts.
- 10 inline JavaScript blocks passed `node --check`.
- 75 distinct Android bridge names referenced by HTML, none missing from `MainActivity.kt`.
- All 12 XML files parsed with Python's XML parser.
- Browser smoke check with a fake test bridge ran at all three requested viewports with no console/page errors; page width matched viewport; `preserve-3d` styles and tap class worked; existing `CHAT` button opened the chat panel and the press class cleared. This is not Android-device testing.
- No Gradle/Android SDK build or physical phone test has been performed in that working environment.

## Deliverables
- Create `CLAUDE_AUDIT_REPORT.md` with severity-ranked findings, exact tests/commands, result evidence, changed-file list, and what remains unverified.
- If any regression is found, fix only its narrow root cause, rerun relevant and then full tests, and provide a complete repaired ZIP.
- If no regression is found, return the project ZIP with only your audit report added; avoid unrelated code rewrites.
- Produce an installable APK only after an actual Android build succeeds. Never invent artifacts or download links.

## Simple owner handover
Explain in Hinglish, briefly, whether any feature broke, what was tested, what still needs GitHub Actions/device testing, and provide direct artifact links. If a check fails, ask the owner only for the red error lines or a screenshot.
