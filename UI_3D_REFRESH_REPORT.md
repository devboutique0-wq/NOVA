# NOVA 3D UI refresh — change summary

## Intent
Visual-only polish to the existing app UI and floating popup controls. No change intended to AI, voice commands, permissions, keys, or data logic.

## Runtime files changed
- `app/src/main/assets/ui.html`: adds a CSS bevel/glass-depth theme to current SVG icon buttons and feature cards; adds visual press/bounce feedback using a delegated pointer/click listener; includes focus-visible and reduced-motion handling.
- `app/src/main/java/com/nova/assistant/NovaHud.kt`: adds depressed highlight/scale feedback while the existing minimize/close controls are pressed; preserves existing callbacks and control hit areas.
- `app/src/main/res/drawable/ic_launcher_foreground.xml`: adds a darker bevel under the orbit and glossy highlights on the existing NOVA orb.

## Local verification evidence
- Python contract tests: 11/11 passed (`tools/test_*.py`).
- JavaScript syntax: all 10 inline script blocks passed individual `node --check` checks.
- Android bridge consistency: 75 distinct `Android.xxx()` names used by HTML; zero missing methods in `MainActivity.kt`.
- XML: 12 Android XML resources parsed successfully.
- Browser smoke check: Chromium/Playwright loaded the UI at 360x520, 360x640, and 390x844 using a test-only fake Android bridge. No console/page errors; horizontal document width matched viewport; scrolling was available on small heights; 3D styles applied; press class applied/cleared; existing CHAT button opened the chat panel.

## Not verified
- Android Gradle/Kotlin compilation and APK build were not run (the working environment does not provide the Android SDK/Gradle build chain).
- No physical Android device test was performed. Test-only browser bridge is not a replacement for testing the real `MainActivity.Bridge`.
- The app launcher icon was XML-parsed but not rendered/installed on Android.

## Change boundary
No source was intentionally changed outside the three runtime files above. Audit documentation files are included for Claude.
