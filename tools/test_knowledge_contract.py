#!/usr/bin/env python3
"""PART 3 contract test: the REAL tables of KnowledgeMatcher.kt (read from the source) + the REAL assets/knowledge.json run
through a Python port of the matcher (knowledge_port.py). Not a run of the Kotlin code: it catches data and table mistakes early."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import knowledge_port as K

E = K.load_entries()
ids = {e.id for e in E}
fails = []
def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + extra))
    if not cond: fails.append(name)

POS = [  # (text, expected id)
 ("nova india ki rajdhani kya hai", "india-capital"), ("bharat ki capital kya hai", "india-capital"), ("what is the capital of india", "india-capital"),
 ("hey nova capital of india please", "india-capital"), ("india ke kitne rajya hain", "india-states-count"), ("how many states are there in india", "india-states-count"),
 ("rashtriya pakshi kaun sa hai", "india-national-bird"), ("india ka national animal kya hai", "india-national-animal"), ("national flower of india", "india-national-flower"),
 ("jana gana mana kisne likha tha", "india-national-anthem"), ("vande mataram kisne likha", "india-national-song"),
 ("tiranga mein kitne rang hote hain", "india-flag"), ("india ko azadi kab mili thi", "india-independence-day"), ("republic day kyun manate hain", "india-republic-day"),
 ("bharat ka samvidhan kab bana", "india-constitution"), ("first prime minister of india kaun the", "india-first-pm"), ("india ke pehle rashtrapati kaun the", "india-first-president"),
 ("india ki sabse lambi nadi kaun si hai", "india-longest-river"), ("kangchenjunga kitna uncha hai", "india-highest-peak"), ("india ka sabse bada rajya kaun sa hai", "india-largest-state"),
 ("bharat ki mudra kya hai", "india-currency"), ("taj mahal kisne banwaya", "india-taj-mahal"), ("where is the red fort", "india-red-fort"),
 ("lok sabha mein kitni seats hain", "india-parliament"), ("india mein vote dene ki umar kitni hai", "india-voting-age"), ("gandhi jayanti kab hoti hai", "india-gandhi-jayanti"),
 ("kya hindi rashtriya bhasha hai", "india-national-language"), ("chandrayaan 3 kab utra", "india-chandrayaan3"), ("aadhaar kya hai", "india-aadhaar"),
 ("upi pin kya hota hai", "india-upi"), ("india ke padosi desh kaun se hain", "india-neighbours"), ("holi kyun manate hain", "india-holi"),
 ("pani kitne degree par ubalta hai", "sci-water-boil"), ("water boiling point", "sci-water-boil"), ("why is the sky blue", "sci-sky-blue"), ("aasman neela kyun hota hai", "sci-sky-blue"),
 ("gravity kya hai", "sci-gravity"), ("indradhanush kaise banta hai", "sci-rainbow"), ("how many bones in human body", "sci-bones-count"), ("vitamin c kis mein hota hai", "sci-vitamin-c"),
 ("vitamin d kahan se milta hai", "sci-vitamin-d"), ("surya grahan kyun lagta hai", "sci-eclipse"),
 ("cpr kaise karte hain", "health-cpr"), ("heart attack ke lakshan kya hain", "health-heart-attack"), ("signs of a stroke", "health-stroke"), ("ors kya hai", "health-ors"),
 ("normal body temperature", "health-body-temperature"), ("normal bp kitna hota hai", "health-blood-pressure"), ("haath kitni der tak dhona chahiye", "health-handwashing"),
 ("bmi kaise nikalte hain", "health-bmi"), ("pythagoras theorem kya hai", "math-pythagoras"), ("area of a circle formula", "math-area-circle"),
 ("simple interest ka formula kya hai", "math-simple-interest"), ("leap year ka rule kya hai", "math-leap-year"), ("bodmas kya hai", "math-bodmas"),
 ("1 crore mein kitne lakh", "math-lakh-crore"), ("what is a prime number", "math-prime"),
 ("chai kaise banate hain", "daily-make-tea"), ("how to cook rice", "daily-cook-rice"), ("strong password kaise banate hain", "daily-strong-password"),
 ("online fraud se kaise bachein", "daily-online-fraud"), ("what is the 20 20 20 rule", "daily-20-20-20"), ("bhukamp aaye to kya karein", "daily-earthquake"),
 ("wake word kaise badlein", "nova-wake-word"), ("nova bina internet ke kya kya karta hai", "nova-offline-works"), ("groq key kya hai", "nova-groq-key"),
 ("free online ai kya hai", "nova-free-online-ai"), ("self test kya hai", "nova-self-test"), ("accessibility on kaise karein", "nova-accessibility"),
 ("nova ko chup kaise karayein", "nova-stop-words"),
 # typos / spelling variants / filler
 ("india ki rajdhaani kya hai", "india-capital"), ("taj mahal kisne banvaya", None), ("chandrayan 3 kab utra", "india-chandrayaan3"),
 ("nova zara bata india ki rajdhani kya hai", "india-capital"), ("bhukamp aaye to kya karein please", "daily-earthquake"),
]
NEG = [
 "torch on", "nova torch band karo", "aaj ka mausam", "aaj ki taaza khabar", "gold ka price kya hai", "ipl ka score", "bitcoin ka rate", "abhi kitne baje hain",
 "hello", "nova", "okay", "hmm", "", "   ", "kya", "mujhe ek kahani sunao jisme ek raja aur ek rani ho aur wo dono jungle mein ghoomne jaate hain aur wahan unhe ek sher milta hai jo bahut dayalu hota hai",
 "dubai ki sabse unchi building kaun si hai", "quantum computing kya hota hai", "mere dost ka naam kya hai", "tell me a joke", "tomorrow ka weather kaisa rahega", "latest cricket news",
 "india", "rajdhani", "taj", "pani ubalta hai", "pani ubalta hai", "dubai ki sabse unchi building kaun si hai", "open youtube", "volume badhao", "battery kitni hai", "send message to mom", "meri maa ko call karo",
]
for t, exp in POS:
    if exp is None: continue
    m = K.match(E, t)
    check("pos: " + t, m is not None and m[0].id == exp, "got %r" % ((m and (m[0].id, m[1])),))
for t in NEG:
    m = K.match(E, t)
    check("neg: %r" % t, m is None, "got %r" % ((m and (m[0].id, m[1])),))

# every entry: all its own variants hit itself; both answers present; expected ids exist
for e in E:
    check("self: " + e.id, all((K.match(E, q) or (None,))[0] is e for q in e.q))
for t, exp in POS:
    if exp: check("id exists: " + exp, exp in ids)

# tie: two entries sharing every token of the query must give None
class _E:  # tiny synthetic pack
    def __init__(s, i, q):
        s.id = i; s.q = q; s.hi = s.en = "x"; s.tags = []; s.tokens = [K.content_tokens(x) for x in q]
pack = [_E("a", ["suraj tara grah"]), _E("b", ["suraj tara chand"])]
check("tie gives None", K.match(pack, "suraj tara") is None)
check("one concept ok", K.match([_E("c", ["rainbow"])], "rainbow kya hai") is not None)
check("long text gives None", K.match(E, "india ki rajdhani " + "kya hai " * 30) is None)
check("15 words gives None", K.match(E, " ".join(["india"] * 15)) is None)
check("within1 typo", K.within1("ubalta", "ubalte") and not K.within1("ubalta", "ubalti x"))
check("fuzzy only for 6+ letters", not K.tok_eq("tara", "tare") and K.tok_eq("rajdhani", "rajdhaani"))
check("count>=150", len(E) >= 150, str(len(E)))
check("positive cases >= 60", sum(1 for _, x in POS if x) >= 60, str(sum(1 for _, x in POS if x)))
check("negative cases >= 20", len(NEG) >= 20)
print("ALL PASSED" if not fails else "FAILED: %d" % len(fails))
sys.exit(1 if fails else 0)
