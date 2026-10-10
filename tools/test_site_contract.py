#!/usr/bin/env python3
"""v16: Python mirror of SiteIntent.kt. Reads the word sets from the Kotlin file, so the two cannot drift apart silently."""
import re, sys, os
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, '..', 'app/src/main/java/com/nova/assistant/SiteIntent.kt'), encoding='utf-8').read()

def sset(name):
    m = re.search(r'private val ' + name + r' = setOf\((.*?)\)\n', src, re.S)
    assert m, name
    return set(re.findall(r'"([a-z0-9]+)"', m.group(1)))

VERBS, NOUNS, NOT, FILLER, BLOCK, PUBVERBS = (sset(n) for n in ('VERBS', 'NOUNS', 'NOT', 'FILLER', 'BLOCK', 'PUBVERBS'))

def tokens(s):
    return re.sub(r'[^a-z0-9]', ' ', s.lower()).split()

def parse(text):
    t = tokens(text)
    if not t or len(t) > 60: return None
    verb = noun = False
    for w in t:
        if w in NOT: return None
        if w in VERBS: verb = True
        if w in NOUNS: noun = True
    if not verb or not noun: return None
    blocked = False; keep = []
    for w in t:
        if w in BLOCK: blocked = True
        if w in VERBS or w in NOUNS or w in FILLER: continue
        keep.append(w)
    s = ' '.join(keep)
    if len(s) < 3: s = ''
    return (s, blocked)

def is_publish(text):
    t = tokens(text)
    if not t or len(t) > 30: return False
    pub = noun = False
    for w in t:
        if w in NOT: return False
        if w in PUBVERBS: pub = True
        if w in NOUNS: noun = True
    return pub and noun

PUB = [("nova website publish karo", True), ("publish my website", True), ("site upload kar do", True),
       ("photo upload karo", False), ("website banao", False), ("battery kitni hai", False), ("chrome kholo website publish karo", False)]
CASES = [
    ("nova ek restaurant ki website banao", ("restaurant", False)),
    ("make a website for my gym", ("gym", False)),
    ("mere liye portfolio website bana do", ("", False)),
    ("nova ek photographer ka portfolio banao", ("photographer", False)),
    ("Build a landing page for my bakery", ("bakery", False)),
    ("website banao", ("", False)),
    ("nova ek phishing website banao", ("phishing", True)),
    ("ek fake bank website banao", ("fake bank", True)),
    ("chrome kholo aur website banao", None),
    ("website ke liye photo banao", None),
    ("ek billi ki photo banao", None),
    ("battery kitni hai", None),
    ("whatsapp par website bhejo", None),
    ("torch on", None),
    ("a b " * 40 + "website banao", None),
]
bad = 0
for text, want in CASES:
    got = parse(text)
    if got != want:
        bad += 1; print('FAIL', repr(text), 'got', got, 'want', want)
for text, want in PUB:
    if is_publish(text) != want:
        bad += 1; print('FAIL publish', repr(text))
if bad: print(bad, 'FAILED'); sys.exit(1)
print('ALL PASSED (%d cases)' % len(CASES))
