#!/usr/bin/env python3
"""Builds feed/feed.json for NOVA's Discover -> Ask -> Add updater (step C).

Items (same fields the app reads: id,type,title,url,sha256,size,version,minApp):
  skills : every skillpacks/*.json  (pack file: {"title","version","skills":[{name,triggers,steps}]})
  apk    : newest GitHub release asset named nova-vc<versionCode>.apk
  model  : entries of tools/models.json, sha256/size read from the Hugging Face file tree

Rules are the SAME as Updater.validate in the app: https + allow-listed host, 64-hex sha256, size limits.
The app still re-checks everything before it offers or installs anything. Standard library only.
"""
import hashlib, json, os, re, sys, urllib.request
from urllib.parse import urlparse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOSTS = {
    "raw.githubusercontent.com", "github.com", "objects.githubusercontent.com",
    "release-assets.githubusercontent.com", "huggingface.co", "cdn-lfs.huggingface.co",
    "cdn-lfs-us-1.huggingface.co", "cas-bridge.xethub.hf.co",
}
MAX = {"skills": 200 * 1024, "apk": 150 * 1024 * 1024, "model": 2 * 1024 * 1024 * 1024}
ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{2,63}$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
APK_RE = re.compile(r"^nova-vc(\d+)\.apk$")
MAX_FEED_BYTES = 256 * 1024


def check_item(i):
    """Returns None when the item would pass Updater.validate, else a short reason."""
    if not ID_RE.match(i["id"]): return "bad id"
    if i["type"] not in MAX: return "bad type"
    if not i["title"].strip() or len(i["title"]) > 80: return "bad title"
    u = urlparse(i["url"])
    if u.scheme != "https" or u.username or (u.hostname or "").lower() not in HOSTS: return "host not allowed"
    if not SHA_RE.match(i["sha256"]): return "bad sha256"
    if i["size"] <= 0 or i["size"] > MAX[i["type"]]: return "bad size"
    if i["version"] < 0 or i["minApp"] < 0: return "bad version"
    return None


def get_json(url, token=None):
    req = urllib.request.Request(url, headers={"User-Agent": "nova-feed", "Accept": "application/json"})
    if token: req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def sha256_url(url):
    h = hashlib.sha256(); n = 0
    req = urllib.request.Request(url, headers={"User-Agent": "nova-feed"})
    with urllib.request.urlopen(req, timeout=300) as r:
        while True:
            b = r.read(1 << 20)
            if not b: break
            h.update(b); n += len(b)
    return h.hexdigest(), n


def skill_items(repo, branch):
    out = []
    d = os.path.join(ROOT, "skillpacks")
    if not os.path.isdir(d): return out
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".json"): continue
        raw = open(os.path.join(d, fn), "rb").read()
        try:
            pack = json.loads(raw.decode("utf-8"))
            assert isinstance(pack.get("skills"), list) and 0 < len(pack["skills"]) <= 100
            for s in pack["skills"]:
                assert isinstance(s.get("name"), str) and isinstance(s.get("triggers"), list) and isinstance(s.get("steps"), list)
        except Exception as e:
            print("SKIP pack %s: not a valid pack (%s)" % (fn, e)); continue
        out.append({
            "id": "skills-" + fn[:-5].lower().replace("_", "-"), "type": "skills",
            "title": str(pack.get("title", fn[:-5]))[:80],
            "url": "https://raw.githubusercontent.com/%s/%s/skillpacks/%s" % (repo, branch, fn),
            "sha256": hashlib.sha256(raw).hexdigest(), "size": len(raw),
            "version": int(pack.get("version", 1)), "minApp": int(pack.get("minApp", 0)),
        })
    return out


def apk_item(repo, token):
    try:
        rels = get_json("https://api.github.com/repos/%s/releases?per_page=20" % repo, token)
    except Exception as e:
        print("SKIP apk: cannot read releases (%s)" % e); return None
    best = None
    for rel in rels:
        if rel.get("draft") or rel.get("prerelease"): continue
        for a in rel.get("assets", []):
            m = APK_RE.match(a.get("name", ""))
            if m and (best is None or int(m.group(1)) > best[0]): best = (int(m.group(1)), a, rel)
    if best is None:
        print("SKIP apk: no release asset named nova-vc<number>.apk"); return None
    vc, a, rel = best
    digest = str(a.get("digest") or "")
    if digest.startswith("sha256:") and SHA_RE.match(digest[7:]):
        sha, size = digest[7:], int(a["size"])
    else:
        sha, size = sha256_url(a["browser_download_url"])
    return {"id": "apk-nova", "type": "apk", "title": "NOVA app %s" % (rel.get("name") or rel.get("tag_name") or vc),
            "url": a["browser_download_url"], "sha256": sha, "size": size, "version": vc, "minApp": 0}


def model_items():
    p = os.path.join(ROOT, "tools", "models.json")
    if not os.path.exists(p): return []
    out = []
    for m in json.load(open(p, encoding="utf-8")).get("models", []):
        try:
            tree = get_json("https://huggingface.co/api/models/%s/tree/main" % m["repo"])
            f = next(x for x in tree if x.get("path") == m["file"])
            lfs = f.get("lfs") or {}
            sha, size = lfs.get("oid", ""), int(lfs.get("size") or f.get("size") or 0)
            out.append({"id": m["id"], "type": "model", "title": m["title"][:80],
                        "url": "https://huggingface.co/%s/resolve/main/%s" % (m["repo"], m["file"]),
                        "sha256": sha, "size": size, "version": int(m.get("version", 1)), "minApp": int(m.get("minApp", 0))})
        except Exception as e:
            print("SKIP model %s (%s)" % (m.get("id"), e))
    return out


def main():
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    branch = os.environ.get("FEED_BRANCH", "main")
    token = os.environ.get("GITHUB_TOKEN")
    if not repo: sys.exit("GITHUB_REPOSITORY is not set (format owner/name)")
    items = skill_items(repo, branch)
    a = apk_item(repo, token)
    if a: items.append(a)
    items += model_items()
    good = []
    for i in items:
        why = check_item(i)
        if why: print("DROP %s: %s" % (i["id"], why))
        else: good.append(i)
    text = json.dumps({"generated": "by tools/build_feed.py", "items": good}, indent=1, sort_keys=True) + "\n"
    if len(text.encode()) > MAX_FEED_BYTES: sys.exit("feed too large")
    os.makedirs(os.path.join(ROOT, "feed"), exist_ok=True)
    open(os.path.join(ROOT, "feed", "feed.json"), "w", encoding="utf-8").write(text)
    print("feed.json written with %d item(s)" % len(good))


if __name__ == "__main__":
    main()
