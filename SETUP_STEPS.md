# NOVA - setup steps (one at a time, phone/PC both fine)

Windows reminder: when you type a command, copy ONLY the command, never the `PS D:\...>` prompt.

## 1. Push to GitHub and make the first build (step A)
1. Make a **private** GitHub repository and upload the project files (keep the `.github` folder).
2. Open the repo -> Actions -> "Build and Test APK" -> Run workflow.
3. If it turns red, open the failed step and paste the red lines to Claude. It names the exact cause.

## 2. Fixed signing key (step B) - only needed for in-app APK updates
1. Actions -> "Make signing key (run ONCE)" -> Run workflow. When done, download artifact `nova-signing-key`.
2. Repo -> Settings -> Secrets and variables -> Actions -> New repository secret. Make three secrets:
   - `NOVA_KEYSTORE_B64` = the text inside `NOVA_KEYSTORE_B64.txt`
   - `NOVA_KEYSTORE_PASSWORD` = the text inside `NOVA_KEYSTORE_PASSWORD.txt`
   - `NOVA_KEY_ALIAS` = `nova`
3. Keep a private backup of `nova.jks` and the password. Then delete the artifact.
4. Every later build is signed with this key. **Uninstall the old (debug-key) NOVA once and install the new one.**

## 3. Release + feed (step C)
1. Actions -> "Release APK" -> Run workflow -> versionCode (higher than the installed one, e.g. 7). It publishes `nova-vc7.apk`
   and refreshes `feed/feed.json` by itself.
2. In the NOVA app settings, set the feed URL to:
   `https://raw.githubusercontent.com/<owner>/<repo>/main/feed/feed.json`
   (a private repo is not readable by the phone: for the feed to work the repo must be public, or host feed.json elsewhere).
3. Skill packs: add `skillpacks/<name>.json`, push to main, the feed rebuilds. Models: list them in `tools/models.json`.

## 4. Self-improve loop (step D)
1. Secret `GEMINI_API_KEY`. Repo -> Settings -> Actions -> General -> "Allow GitHub Actions to create and approve pull requests" ON.
2. Actions -> "Self-improve (pull request only)" -> Run workflow -> type the task. If tests are green a pull request appears.
   You read it and press Merge yourself. Nothing merges automatically.

## 5. Offline model slot (step E)
The slot, the prompt and the safety filter are built. No inference engine is bundled yet (it must be compile-tested in CI first).
