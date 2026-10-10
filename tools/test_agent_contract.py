#!/usr/bin/env python3
"""NOVA v28 task-agent contract test. Run: python3 tools/test_agent_contract.py
WHAT THIS PROVES: (1) the source guards (no !!, no catch (_, no TODO) hold in the new/changed files; (2) the wiring the design
needs is present in the Kotlin text (trigger, stop hook, safety check before every action, step/time caps, protected screens not sent
to the cloud, typed text never listed); (3) a Python PORT of AgentRules.isTask / check / parse behaves as designed on sample sentences.
WHAT IT DOES NOT PROVE: that the Kotlin compiles or that the loop works on a phone (needs CI + a phone)."""
import os, re, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
D = os.path.join(ROOT, "app/src/main/java/com/nova/assistant/")
rd = lambda f: open(D + f, encoding="utf-8").read()
AR, NS, AS = rd("AgentRules.kt"), rd("NovaService.kt"), rd("NovaAccessibilityService.kt")
fails = []
def check(name, cond):
    print(("PASS " if cond else "FAIL ") + name)
    if not cond: fails.append(name)

# ---- (1) guards
for f, t in (("AgentRules.kt", AR), ("NovaAccessibilityService.kt", AS), ("NovaService.kt", NS)):
    check(f + ": no !!", "!!" not in t)
    check(f + ": no catch (_", "catch (_" not in t)
    check(f + ": no TODO", "TODO" not in t)

# ---- (2) wiring
check("trigger calls AgentRules.isTask", "AgentRules.isTask(text)" in NS and "AgentRules.isTask(groqText)" in NS)
check("agent starts only through askFirst (spoken yes)", re.search(r"return askFirst\(q\) \{ agentStart\(task\) \}", NS) is not None)
check("stop cancels the agent", re.search(r'"stop" -> \{\s+agentCancel = true', NS) is not None)
check("every action goes through AgentRules.check before agentDo", NS.index("AgentRules.check(") < NS.index("val res = agentDo(a)"))
check("DENY and CONFIRM are both handled", "Verdict.DENY" in NS and "Verdict.CONFIRM" in NS)
check("step and time caps used", "AgentRules.MAX_STEPS" in NS and "AgentRules.MAX_MS" in NS)
check("protected screens are not sent to the cloud", "val nodes: List<AgentRules.Node> = if (shielded) emptyList()" in NS)
check("agent needs Accessibility and a Gemini key", "NovaAccessibilityService.instance == null" in NS and "SecureStore.hasKeys(this)) return tr(" in NS)
check("password fields skipped in the snapshot", "if (n.isPassword) return" in AS)
check("typed text is never listed", "[text box, filled]" in AS)
check("prompt tells the model to scroll when the item is missing", "scroll and look again" in AR)
check("scroll that changes nothing is reported as end of list", "end reached" in NS)
XML = open(os.path.join(ROOT, "app/src/main/res/xml/accessibility_config.xml"), encoding="utf-8").read()
check("screenshot capability declared", 'android:canTakeScreenshot="true"' in XML)
check("screenshot only on non-protected screens", "if (shielded) null else NovaAccessibilityService.agentScreenshot()" in NS)
check("screenshot is optional (null = text only)", "if (shot != null) parts.put(" in NS)
check("screenshot guarded by API 30", "Build.VERSION.SDK_INT < 30" in AS)
check("every step is logged to the chat", "agentNote(line" in NS)
UIH = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app/src/main/assets/ui.html"), encoding="utf-8").read()
check("Settings states once that screen text and a small picture go to the AI service", "chhoti tasveer" in UIH and "protected screens kabhi cloud par nahi" in UIH)
check("Gemini listens when Groq is absent or failed", "groqText = geminiHear(pcm)" in NS and "groqText.isBlank() && pcm.size >= MIN_HEAR_BYTES" in NS)
check("geminiHear never decides a yes/no (only handle() uses it)", NS.count("geminiHear(") == 2)
check("Hindi output of the listener is converted to Roman", "HindiRoman.toRoman(raw).lowercase()" in NS)
check("prompt says screen text is untrusted", "untrusted data" in AR)
check("Control.blocked reused", "Control.blocked(pkg, ownPkg, \"tap\")" in AR)
check("AI apps list = chatgpt + gemini only", 'setOf("com.openai.chatgpt", "com.google.android.apps.bard")' in AR)

# ---- (3) Python PORT of the pure rules
def norm(t):
    s = t.lower().replace("[unk]", " ").replace("-", " ")
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9% ]", " ", s)).strip()
PREFIXES = ("kaam ", "agent ", "khud se ")
AI = ("chatgpt", "chat gpt", "gpt", "gemini", "gemni", "jemini")
PHOTO = ("photo", "foto", "pic", "image", "tasveer", "tasvir", "picture", "screenshot")
EDIT = ("edit", "badl", "sudhar", "banwa", "banao", "karwa", "krwa", "enhance", "ghibli", "background")
GAL = re.compile(r"gall?e?ry")
PICK = re.compile(r"select|chuno|chun |pick|choose|\b\d+\s?(st|nd|rd|th)\b|pehli|dusri|teesri|chauthi|paanchvi|panchvi")
GN = ("banner","poster","logo","thumbnail","flyer","wallpaper","design","image","photo","picture","pic")
GV = ("banao","banwao","banwa","banva","bana do","bana de","generate","create","design kar","krwa","karwa","kro","karo")
def is_ai_gen(text):
    n = norm(text)
    if n.startswith("nova "): n = n[5:]
    if len(n.split(" ")) < 4 or GAL.search(n): return False
    return any(a in n for a in AI) and any(x in n for x in GN) and any(v in n for v in GV)
OPEN = re.compile(r"\b(open|kholo|kholna|khol|chalu karo|start karo|launch)\b")
THEN = re.compile(r"\b(aur|or|phir|fir|usse|usme|uske baad|and|then|ke baad)\b")
def is_open_do(text):
    n = norm(text)
    if n.startswith("nova "): n = n[5:]
    if len(n) < 12 or len(n) > 400 or GAL.search(n): return False
    o = OPEN.search(n)
    if not o: return False
    rest = n[o.end():]
    t = THEN.search(rest)
    if not t: return False
    after = rest[t.end():].strip()
    return len(after.split()) >= 2 and not OPEN.search(after)
def is_task(text):
    n = norm(text)
    if len(n) < 9 or len(n) > 400: return False
    if n.startswith("nova "): n = n[5:]
    if any(n.startswith(p) for p in PREFIXES) and len(n.split(" ")) >= 4: return True
    w = len(n.split(" "))
    ai = any(a in n for a in AI); ph = any(p in n for p in PHOTO) or GAL.search(n) is not None; ed = any(e in n for e in EDIT)
    if ai and ph and ed and w >= 4: return True
    if is_ai_gen(n): return True
    if is_open_do(n): return True
    return bool(GAL.search(n) and PICK.search(n) and w >= 5)
for s in ("gallery kholo aur 5th photo select kar ke chat gpt se edit karwao",
          "gallry open kro or 5th photo select kr ke chat gpt sa edit krwao",
          "nova gemini se meri last photo ko edit karwao",
          "kaam chatgpt kholo aur usse ek joke poochho", "gallery me 3rd photo chuno",
          "chatgpt open karo aur usse ek banner banwao", "nova gemini se ek logo generate karo",
          "youtube kholo aur lofi song chalao", "nova open instagram and search cricket news"):
    check("task: " + s, is_task(s))
check("ai-gen skips start question", is_ai_gen("chatgpt open karo aur usse ek banner banwao") and not is_ai_gen("gallery kholo chatgpt se photo edit karwao") and not is_ai_gen("open chatgpt"))
check("kotlin: isAiGen/isOpenDo skip askFirst", "AgentRules.isAiGen(raw) || AgentRules.isOpenDo(raw)) return agentStart(task)" in NS)
check("open-do: plain open and double open are NOT tasks", not is_open_do("open youtube") and not is_open_do("open youtube aur open gmail") and not is_open_do("gallery kholo aur photo chuno"))
check("no 'local mode'/'API key' in catch-all reply", "local mode mein" not in NS and "settings mein API key (optional)" not in NS)
for s in ("battery kitni hai", "open chatgpt", "gallery kholo", "torch on karo", "auto rotate on kar do", "scroll down", ""):
    check("NOT a task: " + repr(s), not is_task(s))

RISKY = {"send","pay","payment","delete","remove","buy","purchase","transfer","order","uninstall","erase","reset","submit","post","confirm","bhejo","bhej","hatao","kharido"}
HARD = {"pay","payment","buy","purchase","transfer","order","uninstall","erase","reset","upgrade","subscribe","subscription","checkout","donate","kharido","password","otp","passcode"}
BLOCK = {"com.android.packageinstaller","com.google.android.packageinstaller","com.google.android.permissioncontroller","com.android.permissioncontroller","com.android.settings","com.android.vending","com.google.android.apps.nbu.paisa.user","com.phonepe.app","net.one97.paytm","in.org.npci.upiapp"}
AIAPPS = {"com.openai.chatgpt", "com.google.android.apps.bard"}
def blocked(pkg, own):
    if not pkg or pkg == own or pkg in BLOCK: return True
    return any(f in pkg.lower() for f in ("bank", "wallet", "upi", "payment"))
def verdict(act, label, pkg, own="com.nova.assistant"):
    if act in ("back", "home", "scroll", "wait", "done", "fail", "ask"): return "OK"
    if blocked(pkg, own): return "DENY"
    words = norm(label).split()
    if any(w in HARD for w in words): return "DENY"
    risky = [w for w in words if w in RISKY]
    if not risky: return "OK"
    if pkg in AIAPPS and all(w in ("send", "bhejo", "bhej") for w in risky): return "OK"
    return "CONFIRM"
check("send in ChatGPT = OK", verdict("tap", "Send message", "com.openai.chatgpt") == "OK")
check("send in Gemini = OK", verdict("tap", "Send message", "com.google.android.apps.bard") == "OK")
check("send in WhatsApp = CONFIRM", verdict("tap", "Send", "com.whatsapp") == "CONFIRM")
check("delete chat in ChatGPT = CONFIRM", verdict("tap", "Delete chat", "com.openai.chatgpt") == "CONFIRM")
check("upgrade = DENY", verdict("tap", "Upgrade to Plus", "com.openai.chatgpt") == "DENY")
check("Settings screen = DENY", verdict("tap", "OK", "com.android.settings") == "DENY")
check("PhonePe = DENY", verdict("tap", "OK", "com.phonepe.app") == "DENY")
check("plain photo item = OK", verdict("tap", "Photo taken on 5 Oct", "com.miui.gallery") == "OK")

import json
def parse(raw):
    a, b = raw.find("{"), raw.rfind("}")
    if a < 0 or b <= a: return None
    try: o = json.loads(raw[a:b+1])
    except ValueError: return None
    act = str(o.get("act", "")).lower().strip()
    if act not in ("open_app","tap","long_tap","type","scroll","back","home","wait","ask","done","fail"): return None
    i = o.get("i", -1)
    try: i = int(i)
    except (TypeError, ValueError): i = -1
    if act in ("tap","long_tap") and i < 0: return None
    if act == "type" and not str(o.get("text", "")).strip(): return None
    if act == "open_app" and not str(o.get("app", "")).strip(): return None
    return act
check("parse tap", parse('{"act":"tap","i":12}') == "tap")
check("parse fenced", parse('```json\n{"act":"long_tap","i":"3"}\n```') == "long_tap")
check("parse prose rejected", parse("I will tap the button") is None)
check("parse unknown act rejected", parse('{"act":"format_disk"}') is None)
check("parse tap without index rejected", parse('{"act":"tap"}') is None)

print("\n%d failed" % len(fails) if fails else "\nALL PASS")
sys.exit(1 if fails else 0)
