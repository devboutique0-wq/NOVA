"""Hindi (Devanagari) contract: Python mirror of HindiRoman + real knowledge.json. Run: python3 tools/test_hindi_contract.py"""
import hindi_port as h, knowledge_port as k, sys
fails = 0
def check(name, got, want):
    global fails
    if got != want:
        fails += 1; print("FAIL", name, "got", repr(got), "want", repr(want))
R = h.to_roman
for a, b in [("टॉर्च चालू करो", "torch chalu karo"), ("टॉर्च बंद करो", "torch band karo"), ("बैटरी", "battery"), ("वॉल्यूम अप", "volume up"),
             ("पानी कितने डिग्री पर उबलता है", "pani kitne digri par ubalta hai"), ("भारत की राजधानी क्या है", "bharat ki rajdhani kya hai"),
             ("सूरज एक तारा है क्या", "suraj ek tara hai kya"), ("दिल की धड़कन कितनी होती है", "dil ki dharkan kitni hoti hai"),
             ("जल्दी", "jaldi"), ("प्यार", "pyar"), ("समझना", "samajhna"), ("कमल", "kamal"), ("स्कूल", "skul"),
             ("विटामिन सी क्या करता है", "vitamin c kya karta hai"), ("विटामिन डी कहाँ से मिलता है", "vitamin d kahan se milta hai"),
             ("वॉल्यूम ५० करो", "volume 50 karo"), ("हाँ", "haan"), ("नहीं", "nahi"), ("torch on", "torch on"), ("", "")]:
    check(a, R(a), b)
check("nukta", R("आवाज़ कम करो"), R("आवाज कम करो"))
for s in ["्", "ा", "ँ", "क्", "\u200d", "क््क", "ऽ", "🙂 क", "ॐ", "।।।"]:
    try: R(s)
    except Exception as e: check("no-throw " + repr(s), repr(e), "ok")
real = k.load_entries()
def hit(t):
    m = k.match(real, R(t)); return None if m is None else getattr(m, "entry", m[0] if isinstance(m, tuple) else m).id
for t, want in [("पानी कितने डिग्री पर उबलता है", "sci-water-boil"), ("सूरज एक तारा है क्या", "sci-sun-star"),
                ("भारत की राजधानी क्या है", "india-capital"), ("विटामिन सी क्या करता है", "sci-vitamin-c"),
                ("आज भारत की राजधानी क्या है", None)]:
    check("knowledge " + t, hit(t), want)
print("ALL PASSED" if not fails else "%d FAILED" % fails); sys.exit(1 if fails else 0)
