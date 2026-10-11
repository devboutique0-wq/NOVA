"""v32 loose-understanding contract: reads the REAL word tables out of Understand.kt, mirrors the matching rule in Python,
runs the same cases as UnderstandTest.kt, and guards the NovaService wiring."""
import os, re, sys
R = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "java", "com", "nova", "assistant")
rd = lambda f: open(os.path.join(R, f), encoding="utf-8").read()
U, NS = rd("Understand.kt"), rd("NovaService.kt")
fails = 0
def check(name, cond):
    global fails
    print(("PASS " if cond else "FAIL ") + name)
    if not cond: fails += 1

class S(frozenset):
    def __add__(self, o): return S(frozenset(self) | frozenset(o))
def setOf(*a): return S(a)
def emptySet(): return S()
def listOf(*a): return list(a)
class Intent:
    def __init__(self, canon, any=frozenset(), need=frozenset(), allow=frozenset(), numeric=False):
        self.canon, self.any, self.need, self.allow, self.numeric = canon, any, need, allow, numeric
        self.all = S(any) + S(need) + S(allow)
    def match(self, rest):
        hasAny, hasNeed, num = False, not self.need, None
        for w in rest:
            d = percent(w)
            if self.numeric and d is not None:
                if num is not None: return None
                num = d; continue
            if w not in self.all: return None
            if w in self.any: hasAny = True
            if w in self.need: hasNeed = True
        if not hasAny or not hasNeed: return None
        if self.numeric:
            return None if num is None else self.canon.replace("{n}", str(num))
        return self.canon
def percent(w):
    d = w[:-1] if w.endswith("%") else w
    if not d or len(d) > 3 or not d.isdigit(): return None
    return int(d) if int(d) <= 100 else None

head = U[U.index("private val NEGATE"):U.index("private class Intent")]
ints = U[U.index("private val INTENTS"):U.index("/**\n     * The short phrase")]
strip = lambda t: re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", t, flags=re.S))
head, ints = strip(head), strip(ints)
flat = lambda t: "\n".join(l.strip() for l in t.splitlines() if l.strip())
head, ints = flat(head), flat(ints)
head = re.sub(r"private val (\w+) = ", r"\1 = ", head)
head = head.replace("MAX_WORDS_SET", "MAXW").replace("MIN_WORDS_SET", "MINW")
ints = ints.replace("MAX_WORDS_SET", "MAXW").replace("MIN_WORDS_SET", "MINW")
ints = re.sub(r"private val INTENTS: List<Intent> = listOf\(", "INTENTS = listOf(", ints)
ints = ints.replace("numeric = true", "numeric = True")
ns = dict(setOf=setOf, emptySet=emptySet, listOf=listOf, Intent=Intent)
exec(head, ns); exec(ints, ns)
NEG, FILL, INTENTS = ns["NEGATE"], ns["FILL"], ns["INTENTS"]

def norm(t):
    s = t.lower().replace("-", " ")
    s = re.sub(r"[^a-z0-9% ]", " ", s); s = re.sub(r"\s+", " ", s).strip()
    return s.replace("wi fi", "wifi").replace("flash light", "flashlight")
def simplify(text):
    n = norm(text)
    n = re.sub(r"^(?:(?:the|hey|a)\s+)?nova\b\s*", "", n)
    words = [w for w in n.split(" ") if w]
    if not words or len(words) > 14 or len(n) > 160: return None
    if any(w in NEG for w in words): return None
    rest = [w for w in words if w not in FILL]
    if not rest: return None
    hit = None
    for i in INTENTS:
        c = i.match(rest)
        if c is None: continue
        if hit is not None and hit != c: return None
        hit = c
    return hit

CASES = [
    ("mere mobile lock kro turant", "lock phone"),
    ("nova mere mobile ko turant lock kar do", "lock phone"),
    ("please phone lock kar do yaar", "lock phone"),
    ("zara screen lock kar dena", "lock phone"),
    ("mera torch jala do", "torch on"),
    ("yaar torch jaldi se band kar de", "torch off"),
    ("flash light on kar do abhi", "torch on"),
    ("awaaz thodi kam kar do", "volume down"),
    ("volume thoda badha do", "volume up"),
    ("phone ki awaaz poori kar do", "volume max"),
    ("phone silent kar do", "volume mute"),
    ("awaaz band kar do", "volume mute"),
    ("brightness thodi zyada kar do", "brightness up"),
    ("roshni kam kar do", "brightness down"),
    ("brightness full kar do", "brightness max"),
    ("mujhe batao battery kitni hai", "battery"),
    ("abhi time kya hua hai", "time"),
    ("aaj ki date batao", "date"),
    ("gana band kar do", "pause music"),
    ("gana chalao", "play music"),
    ("agla gana laga do", None),
    ("agla gana chalao", "next song"),
    ("mera camera kholo", "camera"),
    ("wifi on kar do", "wifi on"),
    ("home screen par jao", "home"),
    ("peeche jao", "back"),
    ("notification dikhao", "notifications"),
    ("recent apps dikhao", "recents"),
    # must NOT match (goes to the AI instead)
    ("mere mobile ko lock mat karo", None),
    ("lock kaise karte hain", None),
    ("lock aur torch on kar do", None),
    ("whatsapp pe rahul ko message bhej do ki main aa raha hoon", None),
    ("torch jala do aur awaaz kam kar do", None),
    ("volume up volume down", None),
    ("phone unlock kar do", None),
    ("sandeep ko call karo", None),
    ("youtube par gana chalao", None),
    ("battery saver on kar do", None),
    ("camera back wala kholo", None),
    ("", None),
    ("kar do", None),
]
for said, want in CASES:
    check(f"{said!r} -> {want!r}", simplify(said) == want)

# source guards
check("Understand.kt has no !!, catch (_ or TODO", "!!" not in U and "catch (_" not in U and "TODO" not in U)
check("never produces risky kinds", not re.search(r'Intent\("(tap|type|call|sms|whatsapp|unlock|send|pay)', U))
check("handle() uses Understand.classify for the Vosk text", "val c0 = if (text.isBlank()) null else Understand.classify(text)" in NS)
check("handle() uses Understand.classify for the Groq/Gemini text", NS.count("c = Understand.classify(groqText)") == 2)
check("false-wake gate counts the Gemini key too", "SecureStore.hasGroq(this) || SecureStore.hasKeys(this)))" in NS)
check("system prompt no longer tells Gemini to refuse unclear phrasing", "If it is empty or unclear, ask the user briefly to repeat" not in NS)
check("system prompt teaches intent extraction", "Work out the INTENT" in NS)
check("tool calls use a low temperature", '"temperature", 0.2' in NS)
print("FAILS:", fails)
sys.exit(1 if fails else 0)
