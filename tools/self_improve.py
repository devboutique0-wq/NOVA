#!/usr/bin/env python3
"""Self-improve loop (step D). Asks Gemini for a patch, applies it ONLY if it passes the guard rules below.

It never commits, never pushes, never merges. The workflow runs the unit tests afterwards and opens a pull
request only if they are green; the owner merges by hand.

Guard rules (same as the handover): whole-file replacements, at most 3 files, protected paths refused,
only app source / ui / resources / skill packs may change, no secrets in the new text, size caps.
The API key is read from the GEMINI_API_KEY environment variable and is sent in a header, never in a URL or log.
Standard library only.
"""
import json, os, re, sys, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAX_FILES = 3
MAX_FILE_BYTES = 120 * 1024
MAX_CONTEXT_BYTES = 160 * 1024
MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.5-flash")

SRC = "app/src/main/java/com/nova/assistant/"
ALLOWED_PREFIXES = (SRC, "app/src/main/res/", "skillpacks/")
ALLOWED_FILES = {"app/src/main/assets/ui.html"}
PROTECTED_NAMES = {
    "SecureStore.kt", "Control.kt", "Updater.kt", "UpdateManager.kt", "InstallReceiver.kt",
    "AndroidManifest.xml", "gradle.properties", "settings.gradle.kts",
}
PROTECTED_PREFIXES = (".github/", "app/src/test/", "tools/", "feed/", "gradle/")
SECRET_RES = [re.compile(p) for p in (r"AIza[0-9A-Za-z_-]{35}", r"-----BEGIN [A-Z ]*PRIVATE KEY-----", r"sk-[A-Za-z0-9]{32,}")]


def path_problem(path):
    """None when the model may change this file, otherwise the reason."""
    if not isinstance(path, str) or not path: return "empty path"
    if path.startswith("/") or "\\" in path or "\x00" in path: return "bad path"
    parts = path.split("/")
    if ".." in parts or "." in parts or "" in parts: return "path traversal"
    name = parts[-1]
    if name in PROTECTED_NAMES or name.endswith(".gradle.kts") or name.endswith(".gradle"): return "protected file"
    if any(path.startswith(p) for p in PROTECTED_PREFIXES): return "protected folder"
    if path in ALLOWED_FILES: return None
    if any(path.startswith(p) for p in ALLOWED_PREFIXES): return None
    return "outside the allowed folders"


def content_problem(path, text):
    if not isinstance(text, str) or not text.strip(): return "empty content"
    if len(text.encode("utf-8")) > MAX_FILE_BYTES: return "file too large"
    for r in SECRET_RES:
        if r.search(text): return "looks like a secret"
    if path.endswith(".kt"):
        if "!!" in text: return "unsafe !! operator (CI rule)"
        if re.search(r"catch \(_", text): return "catch (_ ...) is not valid Kotlin 1.9 (CI rule)"
        if not text.lstrip().startswith("package com.nova.assistant"): return "missing package line"
    if path.endswith(".json"):
        try: json.loads(text)
        except Exception: return "invalid JSON"
    return None


def check_patch(patch):
    """Returns (files, summary) or raises ValueError with the reason. Used by the workflow and by the tests."""
    if not isinstance(patch, dict) or not isinstance(patch.get("files"), list): raise ValueError("no files list")
    files = patch["files"]
    if not 1 <= len(files) <= MAX_FILES: raise ValueError("must change 1 to %d files" % MAX_FILES)
    seen, out = set(), []
    for f in files:
        if not isinstance(f, dict): raise ValueError("bad file entry")
        p, c = f.get("path"), f.get("content")
        why = path_problem(p)
        if why: raise ValueError("refused %r: %s" % (p, why))
        if p in seen: raise ValueError("duplicate path %r" % p)
        seen.add(p)
        why = content_problem(p, c)
        if why: raise ValueError("refused %r: %s" % (p, why))
        out.append((p, c))
    return out, str(patch.get("summary", ""))[:500]


def gemini_json(prompt, key):
    body = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"responseMimeType": "application/json", "temperature": 0.2},
    }).encode("utf-8")
    req = urllib.request.Request(
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent" % MODEL, data=body,
        headers={"Content-Type": "application/json", "x-goog-api-key": key})
    with urllib.request.urlopen(req, timeout=180) as r:
        data = json.loads(r.read().decode("utf-8"))
    text = data["candidates"][0]["content"]["parts"][0]["text"]
    return json.loads(text)


def editable_files():
    out = []
    for base, _, names in os.walk(os.path.join(ROOT, "app", "src", "main")):
        for n in names:
            rel = os.path.relpath(os.path.join(base, n), ROOT).replace(os.sep, "/")
            if path_problem(rel) is None: out.append(rel)
    return sorted(out)


def main():
    task = (os.environ.get("TASK") or "").strip()
    key = os.environ.get("GEMINI_API_KEY") or ""
    if not task: sys.exit("TASK is empty")
    if len(task) > 1500: sys.exit("TASK is too long (max 1500 characters)")
    if not key: sys.exit("GEMINI_API_KEY secret is missing")

    files = editable_files()
    ctx = ""
    for f in ("HANDOFF_STATUS.md", "README_NOVA_V6.md"):
        p = os.path.join(ROOT, f)
        if os.path.exists(p): ctx += "\n=== %s ===\n%s\n" % (f, open(p, encoding="utf-8").read()[:6000])

    step1 = (
        "You help improve an Android voice assistant written in Kotlin. Task from the owner:\n" + task + "\n\n"
        "Project notes:\n" + ctx + "\n\nFiles you may change:\n" + "\n".join(files) + "\n\n"
        'Reply as JSON: {"files": ["path", ...]} listing the 1 to 3 files you need to read and change.'
    )
    picked = gemini_json(step1, key).get("files", [])
    if not isinstance(picked, list) or not 1 <= len(picked) <= MAX_FILES: sys.exit("model picked an invalid file list")
    for p in picked:
        why = path_problem(p)
        if why or p not in files: sys.exit("model picked a refused file %r (%s)" % (p, why or "unknown file"))

    blob, total = "", 0
    for p in picked:
        t = open(os.path.join(ROOT, p), encoding="utf-8").read()
        total += len(t.encode("utf-8"))
        blob += "\n=== FILE %s ===\n%s\n=== END FILE ===\n" % (p, t)
    if total > MAX_CONTEXT_BYTES: sys.exit("selected files are too large to send")

    step2 = (
        "Task from the owner:\n" + task + "\n\nCurrent files:\n" + blob + "\n"
        "Rules: change as little as possible. Kotlin 1.9, no !! operator, no catch (_ ...). Do not touch safety rules: "
        "risky actions need a spoken yes, tap/type are blocked in sensitive apps, downloads need an allow-listed host and a "
        "matching SHA-256. Never put keys or secrets in code. Existing unit tests must keep passing.\n"
        'Reply as JSON: {"summary": "one or two sentences", "files": [{"path": "...", "content": "the COMPLETE new file"}]}. '
        "Only the files listed above."
    )
    patch = gemini_json(step2, key)
    try:
        changes, summary = check_patch(patch)
    except ValueError as e:
        sys.exit("PATCH REFUSED: %s" % e)
    for p, _ in changes:
        if p not in picked: sys.exit("PATCH REFUSED: %r was not one of the files read" % p)
    for p, c in changes:
        with open(os.path.join(ROOT, p), "w", encoding="utf-8", newline="\n") as fh: fh.write(c)
        print("wrote", p)
    with open(os.path.join(ROOT, "patch_summary.txt"), "w", encoding="utf-8") as fh:
        fh.write("Task: %s\n\nModel summary: %s\n\nFiles: %s\n" % (task, summary, ", ".join(p for p, _ in changes)))
    print("Patch applied to the working tree (not committed). Tests decide whether a pull request is opened.")


if __name__ == "__main__":
    main()
