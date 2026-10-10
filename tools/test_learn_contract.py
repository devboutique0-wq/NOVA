#!/usr/bin/env python3
"""v16: Python mirror of LearnRules.kt. Reads the word sets from the Kotlin file, so the two cannot drift apart silently."""
import re, sys, os
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, '..', 'app/src/main/java/com/nova/assistant/LearnRules.kt'), encoding='utf-8').read()

def sset(name):
    m = re.search(r'private val ' + name + r' = setOf\((.*?)\)\n', src, re.S)
    assert m, name
    return set(re.findall(r'"([a-z0-9]+)"', m.group(1)))

VERBS, NOT, FILLER, STOP, SENSITIVE = (sset(n) for n in ('VERBS', 'NOT', 'FILLER', 'STOP', 'SENSITIVE'))

def tokens(s): return re.sub(r'[^a-z0-9]', ' ', s.lower()).split()

def parse(text):
    t = tokens(text)
    if not t or len(t) > 25: return None
    verb = False
    for w in t:
        if w in NOT: return None
        if w in VERBS: verb = True
    if not verb: return None
    blocked = False; keep = []
    for w in t:
        if w in SENSITIVE: blocked = True
        if w in VERBS or w in FILLER: continue
        keep.append(w)
    s = ' '.join(keep)
    if len(s) < 3: s = ''
    return (s, blocked)

def clean_note(raw):
    t = re.sub(r'https?://\S+', ' ', raw)
    t = re.sub(r'\[\d+\]', ' ', t)
    t = re.sub(r'[*#`_>|]', ' ', t)
    t = re.sub(r'\s+', ' ', t).strip()
    if len(t) > 600:
        cut = t[:600]; dot = cut.rfind('.')
        t = cut[:dot + 1] if dot > 200 else cut.strip()
    return None if len(t) < 20 else t

def score(said, topic):
    q = {w for w in tokens(said) if w not in FILLER and w not in STOP}
    tw = [w for w in tokens(topic) if w not in FILLER and w not in STOP]
    if not tw or not q: return 0.0
    hit = 0; long_hit = False
    for w in tw:
        if w in q:
            hit += 1
            if len(w) >= 3: long_hit = True
    if not long_hit: return 0.0
    return hit / len(tw)

bad = 0
def check(name, got, want):
    global bad
    if got != want:
        bad += 1; print('FAIL', name, 'got', got, 'want', want)

check('p1', parse('nova solar panel ke baare mein seekho'), ('solar panel', False))
check('p2', parse('learn about black holes'), ('black holes', False))
check('p3', parse('nova seekho'), ('', False))
check('p4', parse('nova bank password seekho'), ('bank password', True))
check('p5', parse('photo seekho'), None)
check('p6', parse('battery kitni hai'), None)
check('p7', parse('whatsapp kholo aur seekho'), None)
check('c1', clean_note('Solar panels turn light into power. [1] See https://x.com/a for more.'), 'Solar panels turn light into power. See for more.')
check('c2', clean_note('short'), None)
check('c3', len(clean_note('Ek bada vakya hai. ' * 80)) <= 600, True)
check('s1', score('solar panel kya hota hai', 'solar panel') >= 0.6, True)
check('s2', score('battery kitni hai', 'solar panel'), 0.0)
check('s3', score('solar energy batao', 'solar panel') >= 0.6, False)
check('s4', score('kya', 'solar panel'), 0.0)
if bad: print(bad, 'FAILED'); sys.exit(1)
print('ALL PASSED')
