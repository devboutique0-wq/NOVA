# Shared case table for PART 2 (personal memory). tools/test_memory_contract.py runs it against a Python port of
# PersonalMemory.kt (regexes are extracted from the Kotlin source). PersonalMemoryTest.kt is generated from the SAME table
# by tools/gen_memory_test.py, so Kotlin and Python check identical cases.
PARSE = [
    # (spoken text, expected kind or None, expected arg or None)
    ("nova yaad rakh mera naam Shiv hai", "mem_save", "mera naam shiv hai"),
    ("yaad rakho ki meri car blue hai", "mem_save", "meri car blue hai"),
    ("Remember that my sister's birthday is in May", "mem_save", "my sister s birthday is in may"),
    ("remember my favourite colour is green", "mem_save", "my favourite colour is green"),
    ("mujhe yaad rakhna ki kal doctor ke paas jana hai", "mem_save", "kal doctor ke paas jana hai"),
    ("mera naam shiv hai yaad rakh", "mem_save", "mera naam shiv hai"),
    ("note kar lo meri wifi ka naam home hai", "mem_save", "meri wifi ka naam home hai"),
    ("yaad kar lo mere papa ka naam ram hai", "mem_save", "mere papa ka naam ram hai"),
    ("please remember i like tea", "mem_save", "i like tea"),
    ("hey nova remember that i drink two cups of tea", "mem_save", "i drink two cups of tea"),
    ("yaad rakhna ki mujhe subah gym jana hai", "mem_save", "mujhe subah gym jana hai"),
    ("mujhe kya yaad hai", "mem_list", None),
    ("kya yaad hai", "mem_list", None),
    ("tumhe kya yaad hai", "mem_list", None),
    ("kya kya yaad hai", "mem_list", None),
    ("nova what do you remember", "mem_list", None),
    ("what do you remember about me", "mem_list", None),
    ("what did i tell you", "mem_list", None),
    ("show my memory", "mem_list", None),
    ("read me my memories", "mem_list", None),
    ("meri yaadein batao", "mem_list", None),
    ("bhool ja mera naam", "mem_forget", "mera naam"),
    ("bhool jao meri car", "mem_forget", "meri car"),
    ("forget my favourite colour", "mem_forget", "my favourite colour"),
    ("forget about the car", "mem_forget", "the car"),
    ("forget that i like tea", "mem_forget", "i like tea"),
    ("mera naam bhool ja", "mem_forget", "mera naam"),
    ("mat yaad rakh meri car", "mem_forget", "meri car"),
    ("sab bhool ja", "mem_forget_all", None),
    ("sab kuch bhool jao", "mem_forget_all", None),
    ("saari yaad bhool ja", "mem_forget_all", None),
    ("forget everything", "mem_forget_all", None),
    ("forget all about me", "mem_forget_all", None),
    ("delete all my memories", "mem_forget_all", None),
    ("delete my memory", "mem_forget_all", None),
    # NOT memory commands
    ("yaad dilao 5 minute baad chai", None, None),
    ("das minute baad chai yaad dilao", None, None),
    ("remind me in 5 minutes", None, None),
    ("what time is it", None, None),
    ("battery kitni hai", None, None),
    ("torch on", None, None),
    ("remember", None, None),
    ("yaad rakh", None, None),
    ("bhool ja", None, None),
    ("forget", None, None),
    ("wo bhool ja", None, None),
    ("i remember my childhood", None, None),
    ("mujhe yaad nahi hai", None, None),
    ("main bhool jaunga", None, None),
    ("tum bhool gaye", None, None),
]

# (fact, expected refusal reason or None)
REFUSE = [
    ("my wifi password is abc", "secret"),
    ("mera otp 4821", "secret"),
    ("atm pin 1234", "secret"),
    ("my card number is 4111 1111 1111 1111", "card"),
    ("cvv 123", "card"),
    ("mera aadhaar 1234 5678 9012", "id"),
    ("pan card number abcde1234f", "id"),
    ("my passport is in the drawer", "id"),
    ("bank account number 1234567890", "bank"),
    ("mummy ka number 9876543210", "number"),
    ("mera number nine eight seven six five four three two one zero", "number"),
    ("meri pin code 110001", "secret"),
    ("mera naam shiv hai", None),
    ("meri umar 25 saal hai", None),
    ("my birthday is 12 may 1995", None),
    ("meri car ka number 4521", None),
    ("i like pineapple pizza", None),
    ("papa ka birthday paanch june hai", None),
    ("ek do teen char gino", None),
]

FACTS = ["mera naam shiv hai", "meri car blue hai", "papa ka naam ram hai", "my wifi password is abc", "mummy ka number 9876543210"]

# (query, expected removed, expected ambiguous) against FACTS[:3]
FORGET = [
    ("car", 1, False),
    ("shiv", 1, False),
    ("papa ka naam", 1, False),
    ("mera naam", 0, True),
    ("kuch nahi", 0, False),
    ("", 0, False),
]

# (question, expected facts) against FACTS
RELEVANT = [
    ("meri car ka colour kya hai", ["meri car blue hai"]),
    ("mera naam kya hai", ["mera naam shiv hai", "papa ka naam ram hai"]),
    ("wifi password batao", []),
    ("mummy ka number kya hai", []),
    ("what is the weather", []),
    ("hi", []),
]
