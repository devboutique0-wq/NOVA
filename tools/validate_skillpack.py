#!/usr/bin/env python3
"""Validates app/src/main/assets/knowledge.json (the merged knowledge pack). Exit code 1 on any failure."""
import json, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import knowledge_port as K

path = sys.argv[1] if len(sys.argv) > 1 else K.JSON_PATH
doc = json.load(open(path, encoding="utf8"))
errs = []
def bad(i, msg): errs.append("%s: %s" % (i, msg))

ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{2,47}$")
DOSE_RE = re.compile(r"\b\d+(\.\d+)?\s*(mg|mcg|ml|tablets?|gram|grams|g)\b\s*(of\s+)?(paracetamol|ibuprofen|aspirin|crocin|dolo|antibiotic|medicine|dawai|tablet)?", re.I)
DOSE_WORD = re.compile(r"\b(dose|doses|dosage)\b", re.I)
# words that Logic.classify / LocalSkills would catch before the knowledge layer (v22 code): never in a question variant
SHADOW_ANY = {"torch", "flashlight", "flashlite", "alarm", "unlock", "ambulance", "police", "women", "child", "childline", "cyber", "emergency",
              "tap", "click", "dabao", "dabana", "kholo", "khol", "launch", "scroll", "swipe"}
AID_TOPIC = {"snake", "saanp", "nosebleed", "burn", "burnt", "jal", "jala", "bleeding", "khoon", "cut", "wound", "zakhm"}
AID_ASK = {"treatment", "upay", "ilaj", "karu", "karna", "karein", "help", "aid"}
VOL_TRIG = {"mute", "silent", "max", "maximum", "full", "down", "lower", "decrease", "kam", "reduce", "low", "up", "raise", "increase", "badha", "badhao", "higher", "high"}
BRI_TRIG = VOL_TRIG | {"min", "minimum", "lowest", "more", "brighter", "less", "dim"}
TIME_BLOCK = {"timer", "alarm", "zone", "set", "remind", "reminder", "countdown"}
SHADOW_SHORT = {"battery": 6, "charging": 6, "charge": 6, "time": 5, "samay": 5, "date": 5, "tarikh": 5, "tareekh": 5}
SCREEN_ACT = {"analyze", "analyse", "analysis", "dekho", "check", "read", "summarize", "summary", "dekh", "dekhna", "dikh", "dikha", "dikhao", "dikhta", "padh", "padho", "padhna", "batao", "bata", "kya", "likha", "describe", "tell", "see"}
BAD_WORDS = [r"^open\b", r"^start\b", r"^type\b", r"^likho\b", r"^write\b", r"driving mode", r"^reply\b", r"\b(percent|square|square root) of\b", r"^stop$", r"^quiet mode (on|off)$", r"yaad rakh"]
NUMWORDS = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "ek", "do", "teen", "char", "paanch", "das"}

def sentences(t):
    return len([s for s in re.split(r"(?<=[.!?])\s+", t.strip()) if s])
def shadow(q):
    raw = re.sub(r"[^a-z0-9% ]", " ", q.lower()).split()
    if raw and raw[0] == "nova": raw = raw[1:]          # Logic.stripWake removes a leading wake word
    s = set(raw)
    size = len(raw)
    hit = s & SHADOW_ANY
    if hit: return "shadow word " + ",".join(sorted(hit))
    if (s & AID_TOPIC) and (s & AID_ASK or "first aid" in " ".join(raw) or "what to do" in " ".join(raw) or "how to treat" in " ".join(raw)):
        return "first-aid skill words"
    if "volume" in s or "awaaz" in s or "awaz" in s:
        if s & VOL_TRIG or any(c.isdigit() for c in q): return "volume command"
    if "brightness" in s and (s & BRI_TRIG or any(c.isdigit() for c in q)): return "brightness command"
    if s & {"monitor", "monitoring"} and (size <= 4 or s & {"off", "stop", "band", "bandh", "disable"}): return "monitor command"
    for w, n in SHADOW_SHORT.items():
        if w in s and size <= n and not (w in ("time", "samay", "date", "tarikh", "tareekh") and (s & TIME_BLOCK)):
            return "shadow word %s in <=%d words" % (w, n)
    if "timer" in s and (any(c.isdigit() for c in q) or s & NUMWORDS): return "timer with a number"
    if "screen" in s and (s & SCREEN_ACT): return "screen + action word"
    if s & {"wifi", "bluetooth"} and size <= 3: return "wifi/bluetooth short"
    for rx in BAD_WORDS:
        if re.search(rx, " ".join(raw)): return "pattern " + rx
    return None

if not isinstance(doc, dict) or not isinstance(doc.get("entries"), list): sys.exit("FAIL: no entries list")
seen, sets = {}, {}
for e in doc["entries"]:
    i = e.get("id", "?")
    if not isinstance(e.get("id"), str) or not ID_RE.match(e["id"]): bad(i, "bad id")
    if i in seen: bad(i, "duplicate id")
    seen[i] = 1
    for k in ("hi", "en"):
        a = e.get(k)
        if not isinstance(a, str) or not a.strip(): bad(i, "missing " + k); continue
        if len(a) > 400: bad(i, "%s longer than 400 chars (%d)" % (k, len(a)))
        if sentences(a) > 3: bad(i, "%s has more than 3 sentences" % k)
        if DOSE_WORD.search(a) or re.search(r"\b\d+\s*(mg|mcg)\b", a, re.I): bad(i, "dose pattern in " + k)
    qs = e.get("q")
    if not isinstance(qs, list) or len(qs) < 5: bad(i, "needs 5+ variants"); continue
    if not isinstance(e.get("tags"), list): bad(i, "tags must be a list")
    for q in qs:
        if not isinstance(q, str) or not 3 <= len(q) <= 90: bad(i, "variant length: %r" % q); continue
        ql = q.lower()
        if DOSE_WORD.search(q): bad(i, "dose word in variant")
        raw = set(re.sub(r"[^a-z0-9]", " ", ql).split())
        if raw & K.LIVE: bad(i, "live word %s in variant %r" % (sorted(raw & K.LIVE), q))
        r = shadow(q)
        if r: bad(i, "%s in variant %r" % (r, q))
        t = K.content_tokens(q)
        if not t: bad(i, "no content tokens: %r" % q); continue
        key = tuple(sorted(t))
        if key in sets and sets[key] != i: bad(i, "same token set as entry %s: %r" % (sets[key], q))
        sets.setdefault(key, i)
    if len(set(x.lower() for x in qs)) != len(qs): bad(i, "duplicate variant")
if len(doc["entries"]) < 150: errs.append("pack has %d entries, need 150+" % len(doc["entries"]))
if errs:
    print("FAIL: %d problem(s)" % len(errs))
    for x in errs[:80]: print("  " + x)
    sys.exit(1)
print("OK: %d entries, %d variants" % (len(doc["entries"]), sum(len(e["q"]) for e in doc["entries"])))
