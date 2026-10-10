"""Python port of KnowledgeMatcher.kt. The tables (STOP, CANON, LIVE) and constants are READ from the Kotlin source text,
the scoring is a line-by-line port with integer maths. Not a run of the Kotlin code: it catches data / table mistakes early."""
import json, os, re
HERE = os.path.dirname(os.path.abspath(__file__))
KT = open(os.path.join(HERE, "..", "app", "src", "main", "java", "com", "nova", "assistant", "KnowledgeMatcher.kt"), encoding="utf8").read()
JSON_PATH = os.path.join(HERE, "..", "app", "src", "main", "assets", "knowledge.json")

def _block(name, opener):
    m = re.search(r"val %s[^=]*= %s\((.*?)\n    \)" % (name, opener), KT, re.S)
    assert m, name
    return m.group(1)
def _const(name):
    return int(re.search(r"const val %s = (\d+)" % name, KT).group(1))

STOP = set(re.findall(r'"([^"]+)"', _block("STOP", "setOf")))
LIVE = set(re.findall(r'"([^"]+)"', _block("LIVE", "setOf")))
KEEP1 = set(re.findall(r'"([^"]+)"', re.search(r"val KEEP1 = setOf\((.*?)\)", KT).group(1)))
CANON = dict(re.findall(r'"([^"]+)" to "([^"]+)"', _block("CANON", "mapOf")))
MAX_TEXT_CHARS, MAX_QUERY_TOKENS, THRESHOLD, TIE_MARGIN = _const("MAX_TEXT_CHARS"), _const("MAX_QUERY_TOKENS"), _const("THRESHOLD"), _const("TIE_MARGIN")
MIN_OVERLAP, FUZZY_MIN_LEN = _const("MIN_OVERLAP"), _const("FUZZY_MIN_LEN")

def canon(w):
    if w in CANON: return CANON[w]
    if len(w) > 3 and w.endswith("s") and not w.endswith("ss"):
        b = w[:-1]
        return CANON.get(b, b)
    return w

def _clean(text):
    return re.sub(r"[^a-z0-9]", " ", text.lower().replace("[unk]", " "))

def content_tokens(text):
    out = []
    for w in _clean(text).split(" "):
        if not w or w in STOP: continue
        c = canon(w)
        if c in STOP or not c: continue
        if len(c) < 2 and not c[0].isdigit() and c not in KEEP1: continue
        if c not in out: out.append(c)
    return out

def prepare(text):
    if not text.strip() or len(text) > MAX_TEXT_CHARS: return None
    raw = [w for w in _clean(text).split(" ") if w]
    if not raw or len(raw) > MAX_QUERY_TOKENS: return None
    return (content_tokens(text), len(raw), any(w in LIVE for w in raw))

def within1(a, b):
    if a == b: return True
    d = len(a) - len(b)
    if d > 1 or d < -1: return False
    i = j = edits = 0
    while i < len(a) and j < len(b):
        if a[i] == b[j]:
            i += 1; j += 1
        else:
            edits += 1
            if edits > 1: return False
            if len(a) > len(b): i += 1
            elif len(b) > len(a): j += 1
            else: i += 1; j += 1
    edits += (len(a) - i) + (len(b) - j)
    return edits <= 1

def tok_eq(a, b):
    return a == b or (len(a) >= FUZZY_MIN_LEN and len(b) >= FUZZY_MIN_LEN and within1(a, b))

def overlap(u, q):
    used = [False] * len(q)
    n = 0
    for a in u:
        for i in range(len(q)):
            if not used[i] and tok_eq(a, q[i]):
                used[i] = True; n += 1; break
    return n

def score(u, q, raw_count):
    if not u or not q: return 0
    n = overlap(u, q)
    one = n == 1 and len(u) == 1 and len(q) <= 2 and raw_count >= 2
    if n < MIN_OVERLAP and not one: return 0
    return 200 * n // (len(u) + len(q))

class Entry:
    def __init__(self, d):
        self.id, self.q, self.hi, self.en, self.tags = d["id"], d["q"], d["hi"], d["en"], d.get("tags", [])
        self.tokens = [t for t in (content_tokens(x) for x in self.q) if t]

def load_entries(path=JSON_PATH):
    return [Entry(d) for d in json.load(open(path, encoding="utf8"))["entries"]]

def entry_score(e, qt, raw):
    return max([score(qt, t, raw) for t in e.tokens] + [0])

def match(entries, text):
    p = prepare(text)
    if p is None: return None
    qt, raw, live = p
    if live or not qt: return None
    best, bs, second = None, 0, 0
    for e in entries:
        s = entry_score(e, qt, raw)
        if s > bs: second, bs, best = bs, s, e
        elif s > second: second = s
    if best is None or bs < THRESHOLD: return None
    if second >= THRESHOLD and bs - second < TIE_MARGIN: return None
    return (best, bs)
