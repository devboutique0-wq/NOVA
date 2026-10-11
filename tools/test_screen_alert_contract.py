#!/usr/bin/env python3
"""v33 Part 2: Python mirror of ScreenAlert.kt (lists are READ from the Kotlin file) + wiring guards."""
import re, os, sys
here = os.path.dirname(os.path.abspath(__file__))
def rd(n): return open(os.path.join(here, '..', 'app/src/main/java/com/nova/assistant', n), encoding='utf-8').read()
SA, NS, AX, LG, CT = rd('ScreenAlert.kt'), rd('NovaService.kt'), rd('NovaAccessibilityService.kt'), rd('Logic.kt'), rd('Control.kt')
OWN = 'com.nova.assistant'

def lst(name, src=SA):
    m = re.search(r'val ' + name + r' = (?:list|set)Of\((.*?)\)\n', src, re.S)
    assert m, name
    return re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
IGN, STRONG, WEAK = lst('IGNORE_FRAGMENTS'), lst('STRONG'), lst('WEAK')
BUTTONS = set(lst('BUTTONS'))
def const(n): return int(re.search(r'const val ' + n + r' = ([0-9_ *]+)L?', SA).group(1).replace('_', '').replace(' ', '').replace('L', '').replace('*', '*') and eval(re.search(r'const val ' + n + r' = ([0-9_ *]+)L?', SA).group(1).replace('_', '')))
SNOOZE, HOUR, CAP, GAP, MAXL, MAXLINE = (const(n) for n in ('SNOOZE_MS', 'HOUR_MS', 'HOURLY_CAP', 'MIN_GAP_MS', 'MAX_LINES', 'MAX_LINE'))
BLOCKED_PK = set(re.findall(r'"([a-z0-9_.]+)"', re.search(r'BLOCKED_PACKAGES = setOf\((.*?)\)\n', CT, re.S).group(1)))
BLOCKED_FR = re.findall(r'"([a-z]+)"', re.search(r'BLOCKED_FRAGMENTS = listOf\((.*?)\)\n', CT, re.S).group(1))

def protected(pkg): return (not pkg) or pkg == OWN or pkg in BLOCKED_PK or any(f in pkg.lower() for f in BLOCKED_FR)
def ignored(pkg): return (not pkg) or pkg == OWN or protected(pkg) or any(f in pkg.lower() for f in IGN)
def norm(s): return s.strip().lower().replace('’', "'").replace('‘', "'")
def has(p, l): return re.search(r'\b' + re.escape(p) + r'\b', l) is not None
def detect(pkg, lines):
    if ignored(pkg) or not lines or len(lines) > MAXL: return None
    ls = [norm(x) for x in lines if norm(x)]
    btn = any(l in BUTTONS for l in ls)
    system = pkg == 'android'
    for l in ls:
        if len(l) > MAXLINE or l in BUTTONS: continue
        for p in STRONG:
            if has(p, l) and (btn or system): return p
        if not btn: continue
        for p in WEAK:
            if has(p, l): return p
    return None

class Gate:
    def __init__(s): s.k = {}; s.t = []
    def allow(s, now, key):
        s.t = [x for x in s.t if not (now < x or now - x >= HOUR)]
        if len(s.t) >= CAP: return False
        if s.t and now - s.t[-1] < GAP: return False
        l = s.k.get(key)
        if l is not None and now >= l and now - l < SNOOZE: return False
        return True
    def rec(s, now, key): s.t.append(now); s.k[key] = now

bad = 0
def need(c, m):
    global bad
    if not c: bad += 1; print('FAIL:', m)

CASES = [
    ('com.whatsapp', ['Rahul', 'payment failed yaar', 'OK', 'Type a message'], None),
    ('org.telegram.messenger', ['payment failed', 'OK'], None),
    ('com.android.chrome', ['Error 404', 'Retry'], None),
    ('android', ["Camera isn't responding", 'Close app', 'Wait'], "isn't responding"),
    ('android', ["Maps isn’t responding"], "isn't responding"),
    ('com.example.shop', ['Download failed', 'Retry', 'Cancel'], 'failed'),
    ('com.example.app', ['Something went wrong', 'OK'], 'something went wrong'),
    ('com.example.app', ['Unfortunately, App has stopped', 'OK'], 'has stopped'),
    ('com.example.app', ['Everything is fine', 'OK'], None),
    ('com.example.app', ['Download failed'], None),
    ('com.example.app', ['Warning', 'OK'], None),
    ('com.example.app', ['Network blocked', 'OK'], None),
    ('com.example.app', ['Terror alert', 'OK'], None),
    ('com.example.app', ['x' * 101 + ' error', 'OK'], None),
    ('com.example.app', ['line %d' % i for i in range(30)] + ['failed', 'OK'], None),
    ('com.example.app', [], None),
    (OWN, ['Something went wrong', 'OK'], None),
    ('com.android.settings', ['Something went wrong', 'OK'], None),
    ('com.phonepe.app', ['Payment failed', 'OK'], None),
    ('com.android.launcher3', ['Something went wrong', 'OK'], None),
    ('com.google.android.inputmethod.latin', ['Something went wrong', 'OK'], None),
    ('com.instagram.android', ['Something went wrong', 'OK'], None),
]
for pkg, lines, want in CASES:
    got = detect(pkg, lines)
    need(got == want, '%s %r got %r want %r' % (pkg, lines[:2], got, want))

g = Gate(); t0 = 1_000_000
need(g.allow(t0, 'a|x'), 'first allowed'); g.rec(t0, 'a|x')
need(not g.allow(t0 + 30000, 'b|y'), 'min gap'); need(g.allow(t0 + GAP, 'b|y'), 'after gap')
need(not g.allow(t0 + SNOOZE - 1, 'a|x'), 'snooze'); need(g.allow(t0 + SNOOZE, 'a|x'), 'snooze over')
g2 = Gate()
for i in range(CAP): g2.rec(t0 + i * SNOOZE, 'k%d' % i)
need(not g2.allow(t0 + CAP * SNOOZE, 'o'), 'hourly cap'); need(g2.allow(t0 + HOUR + SNOOZE, 'o'), 'cap resets')
need((SNOOZE, CAP, GAP) == (600000, 3, 60000), 'constants')

# wiring
for w in ('detectAlert', 'shouldAnnounce', 'ALERT_MIN_GAP', 'ALERT_SAME_GAP', 'Say analyze screen'):
    need(w not in LG and w not in AX and w not in NS, 'old alert code still present: ' + w)
scan = AX.split('private val scan = Runnable')[1].split('override fun onServiceConnected')[0]
need('ScreenAlert.detect(' in scan and 'isInteractive' in scan and 'alertGate.allow' in scan, 'scan uses detect + screen-off + gate')
need(scan.index('isInteractive') < scan.index('screenAlertAsk') and scan.index('alertGate.allow') < scan.index('screenAlertAsk'), 'checks before asking')
need('alertGate.record' in scan and scan.index('screenAlertAsk') < scan.index('alertGate.record'), 'recorded only when really asked')
a = NS.split('fun screenAlertAsk')[1].split('// ----')[0]
for w in ('Driving.enabled', 'screenOpen()', 'pending != null', 'busy', 'listening', 'ttsActive', 'followWindow', 'answerWindow'):
    need(w in a, 'screenAlertAsk guard missing: ' + w)
need('Pending(' in a and 'screenStartFrom(' in a and 'say(q, true)' in a, 'alert asks first (spoken yes) then opens the session')
need('announceIfIdle' not in a, 'alert does not use the old announce path')
need(a.index('Pending(') < a.index('say(q, true)'), 'pending is registered before speaking')
need('alertKind' in NS.split('private fun processAnswer')[1].split('private fun geminiHear')[0], 'a yes to the alert keeps the session follow window')
need('screenStartFrom(' in NS.split('private fun analyzeScreen()')[1].split('private fun screenStartFrom')[0], 'analyzeScreen uses the shared start')
q = SA.split('fun question')[1].lower()
for w in ('gemini', 'groq', 'openrouter', 'pollinations', 'qwen', 'say analyze'):
    need(w not in q, 'spoken alert names ' + w)
need('Detail bolun? Haan bolo.' in SA, 'spoken question text')
for w in ('!!', 'catch (_', 'TODO'):
    need(w not in SA, 'ScreenAlert.kt contains ' + w)
print('screen alert contract:', 'ALL OK' if bad == 0 else str(bad) + ' FAILED')
sys.exit(1 if bad else 0)
