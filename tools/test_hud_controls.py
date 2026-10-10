#!/usr/bin/env python3
"""Static contract checks for the NOVA floating HUD minimize/close UI."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
hud = (ROOT / "app/src/main/java/com/nova/assistant/NovaHud.kt").read_text(encoding="utf-8")
ui = (ROOT / "app/src/main/assets/ui.html").read_text(encoding="utf-8")
checks = {
    "minimize callback is wired by NovaHud": "nv.onMinimizeTap = {" in hud,
    "compact state can be toggled": "fun toggleMinimized()" in hud,
    "compact and full size use onMeasure": "if (minimized) miniViewW else fullViewW" in hud,
    "touches can restore the compact chip": "else onMinimizeTap?.invoke()" in hud,
    "close control is drawn on the full card": "drawControl(c, right - padForControls(), top + 24f * d, true)" in hud,
    "minimize control is drawn on the full card": "drawControl(c, right - 51f * d, top + 24f * d, false)" in hud,
    "compact chip has an explicit close control": "drawControl(c, right - 17f * d, cy, true)" in hud,
    "UI refresh preserves original app mount id": 'id="app"' in ui,
    "UI refresh includes reduced-motion support": "@media(prefers-reduced-motion:reduce)" in ui,
    "Update Center remains explicitly opened": "function ucAuto()" in ui and "function ucMaybe()" in ui,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if failed:
    sys.exit(1)
print(f"ALL PASSED ({len(checks)} checks)")
