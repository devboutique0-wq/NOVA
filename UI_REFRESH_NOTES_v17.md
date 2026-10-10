# NOVA visual-only UI refresh v17

Date: 2026-10-11
Base: owner-uploaded `NOVA_v15_assistant.zip` (existing app versionName remains 6.16).

## Changed
- `app/src/main/assets/ui.html`: added a final presentation-only CSS layer for a richer cyan/blue/violet glass look, orbit rings, redesigned cards and controls, smoother modal/chat entrances, responsive canvas sizing and reduced-motion support. Existing HTML IDs, JS handlers, Android bridge calls and feature wiring are kept.
- `app/src/main/java/com/nova/assistant/NovaHud.kt`: added visual minimize and close controls to the existing floating voice card. Minus collapses to a small animated NOVA chip; tapping the chip expands it; X closes it with the existing exit animation. Existing voice / service command routing was not intentionally changed.
- `tools/test_hud_controls.py`: adds static contract checks for the new popup controls.
- `NEXT_ACCOUNT_PROMPT_v16_FULL.md`: records this UI patch and its verification status.

## Verification evidence
- All `tools/test_*.py` contract tests pass locally after the patch.
- Every inline JavaScript block in `ui.html` passes `node --check`.
- All 75 unique `Android.method()` uses in `ui.html` match methods on `MainActivity.Bridge` (zero missing methods).
- `NovaHud.kt` brace counts balance.

## Not verified
- Kotlin/Gradle compilation was not run because this environment lacks the Android SDK and Gradle setup.
- GitHub Actions build and physical Android phone behavior have not been tested.
- Headless Chromium preview was attempted, but browser navigation was blocked by this environment's policy. Therefore no claim is made that the mobile render was visually validated here.

## Next step
Upload the ZIP to the existing NOVA GitHub repository and run its `Unpack zip` workflow, then `Build and Test APK`. After a green build, phone-test the popup controls: minus -> mini chip, tap chip -> full card, X -> close. Existing app behavior should be tested via the existing self-test as a regression check.
