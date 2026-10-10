#!/usr/bin/env python3
"""v30: Python mirror of ImageIntent.kt. Reads the word sets from the Kotlin file, so the two cannot drift apart silently."""
import re, sys, os
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, '..', 'app/src/main/java/com/nova/assistant/ImageIntent.kt'), encoding='utf-8').read()

def sset(name):
    m = re.search(r'private val ' + name + r' = setOf\((.*?)\)\n', src, re.S)
    assert m, name
    return set(re.findall(r'"([a-z0-9]+)"', m.group(1)))

VERBS, NOUNS, NOT, FILLER, BLOCK = (sset(n) for n in ('VERBS', 'NOUNS', 'NOT', 'FILLER', 'BLOCK'))
hm = re.search(r'private val HI = mapOf\((.*?)\n    \)\n', src, re.S)
assert hm
HI = dict(re.findall(r'"([a-z]+)" to "([a-z]+)"', hm.group(1)))

def tokens(s):
    return re.sub(r'[^a-z0-9]', ' ', s.lower()).split()

def parse(text):
    t = tokens(text)
    if not t or len(t) > 40: return None
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
        keep.append(HI.get(w, w))
    s = ' '.join(keep)
    if len(s) < 3: s = ''
    return (s, blocked)

CASES = [
    ("nova ek billi ki photo banao", ("cat", False)),
    ("ek sher ki tasveer bana do", ("lion", False)),
    ("make a picture of a cat on the moon", ("cat on moon", False)),
    ("Create an image of sunset over the sea", ("sunset over sea", False)),
    ("nova sundar phool ki photo banao", ("beautiful flower", False)),
    ("photo banao", ("", False)),
    ("nova ek photo bana do please", ("", False)),
    ("lal gaadi ki photo banao", ("red car", False)),
    ("nova ek nude photo banao", ("nude", True)),
    ("photo kholo", None),
    ("camera kholo aur photo kheencho", None),
    ("kaam chatgpt kholo aur ek photo banao", None),
    ("gallery ki 5th photo select karo", None),
    ("is photo ko edit karo", None),
    ("chatgpt se photo banwao", None),
    ("battery kitni hai", None),
    ("whatsapp par photo bhejo", None),
    ("torch on", None),
    ("a b " * 30 + "photo banao", None),
]
bad = 0
for text, want in CASES:
    got = parse(text)
    if got != want:
        bad += 1; print('FAIL', repr(text), 'got', got, 'want', want)
if bad: print(bad, 'FAILED'); sys.exit(1)
print('ALL PASSED (%d cases)' % len(CASES))
