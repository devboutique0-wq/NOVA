#!/usr/bin/env python3
"""v33 Part 1: Python mirror of ScreenSession.kt (word sets are READ from the Kotlin file) + guards on the NovaService wiring."""
import re, os, sys
here = os.path.dirname(os.path.abspath(__file__))
def rd(n): return open(os.path.join(here, '..', 'app/src/main/java/com/nova/assistant', n), encoding='utf-8').read()
SS, NS, LG = rd('ScreenSession.kt'), rd('NovaService.kt'), rd('Logic.kt')

def sset(name):
    m = re.search(r'private val ' + name + r' = setOf\((.*?)\)\n', SS, re.S)
    assert m, name
    return set(re.findall(r'"([a-z0-9]+)"', m.group(1)))
STOP, ACK, MORE, MORE_SOFT, IMPORTANT, READ, REPLY_WORD, REPLY_DO, NEXT, DO_Q, REFRESH, LOOK, NOW_THIS, PIC, PIC_NOT = (
    sset(n) for n in ('STOP', 'ACK', 'MORE', 'MORE_SOFT', 'IMPORTANT', 'READ', 'REPLY_WORD', 'REPLY_DO', 'NEXT', 'DO_Q',
                      'REFRESH', 'LOOK', 'NOW_THIS', 'PIC', 'PIC_NOT'))
def const(n): return int(re.search(r'const val ' + n + r' = ([0-9_]+)L?', SS).group(1).replace('_', ''))
MAX_TURNS, IDLE_MS, MAX_TEXT, MAX_DRAFT = const('MAX_TURNS'), const('IDLE_MS'), const('MAX_TEXT'), const('MAX_DRAFT')

def tokens(t): return [w for w in re.sub(r'[^a-z0-9]', ' ', t.lower()).split(' ') if w and w != 'nova']

def intent(text):
    t = tokens(text)
    if not t: return 'OTHER'
    has = lambda s: any(w in s for w in t)
    if 'samajh' in t and ('nahi' in t or 'nahin' in t or 'na' in t): return 'MORE'
    if len(t) <= 4 and has(STOP): return 'STOP'
    if len(t) <= 3 and all(w in ACK or w in STOP for w in t): return 'STOP'
    if has(PIC) and not has(PIC_NOT) and len(t) <= 8: return 'PICTURE'
    if has(REFRESH) or (has(LOOK) and has(NOW_THIS) and len(t) <= 6): return 'REFRESH'
    if has(REPLY_WORD) and has(REPLY_DO): return 'REPLY'
    if any(w in ('likhu', 'likhun', 'likhoon') for w in t): return 'REPLY'
    if has(READ) and len(t) <= 7: return 'READ'
    if has(IMPORTANT): return 'IMPORTANT'
    if has(NEXT) or ('kya' in t and has(DO_Q)) or ('what' in t and 'should' in t): return 'NEXT'
    if has(MORE): return 'MORE'
    if len(t) <= 4 and has(MORE_SOFT) and any(w in ('aur', 'more', 'thoda') for w in t): return 'MORE'
    if 'kya' in t and ('hai' in t or 'he' in t or 'h' in t) and len(t) <= 6: return 'WHAT'
    if 'what' in t and ('is' in t or 'this' in t) and len(t) <= 6: return 'WHAT'
    return 'OTHER'

def is_open(state, now):
    if state is None: return False
    last, turns = state
    if turns >= MAX_TURNS or now < last: return False
    return now - last <= IDLE_MS

CASES = [
    ("aur detail", 'MORE'), ("thoda aur batao", 'MORE'), ("ye samajh nahi aaya", 'MORE'), ("more detail please", 'MORE'),
    ("isme important kya hai", 'IMPORTANT'), ("ye kya hai", 'WHAT'), ("what is this", 'WHAT'),
    ("ise padh ke sunao", 'READ'), ("read it out", 'READ'),
    ("iska jawab likho", 'REPLY'), ("reply me kya likhu", 'REPLY'), ("reply karo", 'REPLY'),
    ("aage kya karu", 'NEXT'), ("mujhe kya karna chahiye", 'NEXT'), ("what should i do", 'NEXT'),
    ("ab ye dekho", 'REFRESH'), ("dobara dekho", 'REFRESH'),
    ("picture dekho", 'PICTURE'), ("ye image kya hai", 'PICTURE'),
    ("ruko", 'STOP'), ("bas", 'STOP'), ("nahi", 'STOP'), ("theek hai", 'STOP'), ("thanks", 'STOP'), ("stop", 'STOP'),
    ("", 'OTHER'), ("haan", 'OTHER'), ("ye message kisne bheja", 'OTHER'),
]
bad = 0
for text, want in CASES:
    got = intent(text)
    if got != want:
        bad += 1; print('FAIL', repr(text), 'got', got, 'want', want)
# making a picture is not a screen picture
if intent('ek billi ki photo banao') == 'PICTURE': bad += 1; print('FAIL photo banao is PICTURE')
# a volume sentence with "zyada" is meant for the normal command path: the service checks local commands BEFORE the intent
for i, s in enumerate([(0, 0)]): pass

# lifetime
st = (1000, 0)
for now, want in [(1000, True), (1000 + IDLE_MS, True), (1000 + IDLE_MS + 1, False), (500, False)]:
    if is_open(st, now) != want: bad += 1; print('FAIL open', now, want)
if is_open((2000, MAX_TURNS), 2000): bad += 1; print('FAIL last turn still open')
if is_open(None, 0): bad += 1; print('FAIL None open')
if not (MAX_TURNS == 6 and IDLE_MS == 90000 and MAX_TEXT == 6000): bad += 1; print('FAIL constants')

# ---------------- wiring guards (static, on the real source)
def need(cond, msg):
    global bad
    if not cond: bad += 1; print('FAIL wiring:', msg)
need('"screen_end"' in LG and '"screen"' in LG.split('LOUD_KINDS')[1][:400], 'screen kinds are spoken (LOUD_KINDS)')
need('"screen_end"' in re.search(r'NO_FOLLOW_KINDS = setOf\((.*?)\)', NS).group(1), 'screen_end is a no-follow kind')
need('followCount < MAX_FOLLOW || screenOpen()' in NS, 'follow window opens while a session is open')
need('if (screenOpen() ||' in NS.split('private fun utteranceEnded')[1][:700], 'utteranceEnded opens the follow window for a session')
h = NS.split('private fun handle(')[1]
need(h.index('screenFollow(pcm, text)') < h.index('AgentRules.isTask(text)'), 'session follow-up is checked before the task agent')
need(h.index('agentRunning && text.isNotBlank()') < h.index('screenFollow(pcm, text)'), 'a running agent keeps priority over the session')
a = NS.split('private fun analyzeScreen()')[1].split('private fun screenFollow')[0]
need('screenGate(' in a and 'ScreenSession.start(' in a, 'analyzeScreen starts a session and uses the gate')
g = NS.split('private fun screenGate')[1].split('private fun screenTalk')[0]
need('ScreenSession.isProtected(scrPkg, packageName)' in g, 'protected screens are gated')
need(g.index('isProtected') < g.index('hasKeys'), 'protected check comes before any cloud use')
t = NS.split('private fun screenTalk')[1].split('private fun analyzeScreen')[0]
need('isProtected(now.pkg, packageName)' in t and 'agentScreenshot()' in t, 'screenshot only on a non-protected foreground screen')
need(t.index('isProtected') < t.index('agentScreenshot()'), 'protected check before the screenshot')
need('postFallback(' in t and 'cloudBody(contents, false,' in t, 'screen talk uses the existing cloud body without tools')
need('functionDeclarations' not in t and 'declarations()' not in t, 'screen talk has NO tools (it can only talk)')
f = NS.split('private fun screenFollow')[1].split('private fun endIfLast')[0]
need(f.index('cmd.kind != "analyze"') < f.index('ScreenSession.Intent.STOP'), 'normal local commands win over the STOP words')
need('!turnIsFollow' in f, 'a wake-word sentence that is not a screen intent leaves the session')
need('isOtherFeature(said)' in f, 'picture-making / tasks leave the session')
r = NS.split('private fun screenAnswer')[1]
need('askFirst(ScreenSession.askType' in r and 'typeText(draft)' in r, 'a reply draft is typed only after a spoken yes')
need('tapLabel(label)' in r and r.index('askFirst(ScreenSession.askType') < r.index('tapLabel(label)'), 'Send is pressed only inside the spoken-yes block')
need('tapLabel' not in r.split('askFirst(ScreenSession.askType')[0], 'no tap before the spoken yes')
need('turnIsFollow = false' in NS.split('fun typed(')[1][:500], 'typed text is never a spoken follow-up')
# provider names must not be in anything spoken by the session
for fn in ('protectedAsk', 'noKey', 'noText', 'noPicture', 'noReply', 'askType', 'ended'):
    body = SS.split('fun ' + fn)[1].split('\n\n')[0].lower()
    for w in ('gemini', 'groq', 'openrouter', 'pollinations', 'qwen'):
        need(w not in body, fn + ' names ' + w)
for w in ('!!', 'catch (_', 'TODO'):
    need(w not in SS, 'ScreenSession.kt contains ' + w)
print('screen session contract:', 'ALL OK' if bad == 0 else str(bad) + ' FAILED')
sys.exit(1 if bad else 0)
