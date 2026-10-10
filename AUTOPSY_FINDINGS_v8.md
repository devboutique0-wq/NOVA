# NOVA autopsy findings (2026-10-10)
Review: every Kotlin file, manifest, build.yml, ui.html JS. Pure-Kotlin part compiled + 116 unit tests run in a sandbox; Android part NOT compiled; phone NOT tested.

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | High | Offline listening is English-only Vosk; Hindi command words (chalu, band, kholo) cannot be recognised offline | OPEN - needs phone/CI test (see prompt) |
| 2 | High | Every command waited 5 s of silence; Vosk early endpoint was disabled | FIXED (smart end of speech, ~1.2 s) |
| 3 | Medium | Custom wake word broke stop/open X/home/back/settings (stripWake hard-coded to "nova") | FIXED + tests |
| 4 | Medium | model-en-in (36 MB) bundled but never used | OPEN (part of #1) |
| 5 | Medium | Service killed by battery savers; battery check was non-important | FIXED (button + important health item); START_NOT_STICKY unchanged on purpose |
| 6 | Medium | Pattern unlock had no voice command | FIXED ("unlock phone", needs UNLOCK ON) |
| 7 | Low | Old code-copy zip + .pyc in repo; ~15 finished write-enabled one-shot workflows; docs said build NOT RUN | zip/pyc: use CLEANUP_WORKFLOW.yml.txt; workflows: delete by hand in GitHub (a bot cannot); docs UPDATED |
| 8 | Low | Second launcher icon "NOVA Pattern"; APK is built as debug (debuggable) | OPEN - ask owner |
| 9 | Low | Hindi yes/no ("haan") unreliable with English model (fails safe = cancel) | OPEN (part of #1); gate deliberately NOT loosened |

Verified OK: secret scan clean (tree + old zip); Gemini ids gemini-3.5-flash / 3.5-flash-lite / 3.1-flash-lite and image id gemini-3.1-flash-image exist
(ai.google.dev deprecations page, 2026-10-10); confirmation gate, WhatsApp exact-text send, Keystore AES-GCM keys, updater (https + host list + SHA-256 + same signature),
WebView hardening, bounded cloud failover.
