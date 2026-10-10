"""v17 only-my-voice contract: Python port of SpeakerRules + source guards on the service gate, storage and UI."""
import math, re, sys, os
R = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main")
J = os.path.join(R, "java", "com", "nova", "assistant")
rd = lambda p: open(p, encoding="utf-8").read()
NS, VL, SR, EC, MA = (rd(os.path.join(J, f)) for f in ("NovaService.kt", "VoiceLock.kt", "SpeakerRules.kt", "ExtCfg.kt", "MainActivity.kt"))
UI = rd(os.path.join(R, "assets", "ui.html"))
fails = 0
def check(name, cond):
    global fails
    print(("PASS " if cond else "FAIL ") + name)
    if not cond: fails += 1

def cos(a, b):
    if not a or len(a) != len(b): return 2.0
    na = sum(x * x for x in a); nb = sum(x * x for x in b)
    if na <= 0 or nb <= 0: return 2.0
    return 1 - sum(x * y for x, y in zip(a, b)) / (math.sqrt(na) * math.sqrt(nb))
def decide(v, fr, prof, ans):
    if v is None or prof is None: return "REJECT"
    if fr < (30 if ans else 60): return "TOO_SHORT"
    return "ALLOW" if cos(v, prof) <= 0.5 else "REJECT"
me = [1.0, 0.2, 0.0, 0.5]
check("same voice allowed", decide([1.0, 0.25, 0.05, 0.45], 200, me, False) == "ALLOW")
check("other voice rejected", decide([-0.5, 1.0, 0.8, -0.3], 200, me, False) == "REJECT")
check("short audio never allowed", decide(me, 10, me, False) == "TOO_SHORT" and decide(me, 10, me, True) == "TOO_SHORT")
check("missing vector/profile rejected", decide(None, 200, me, False) == "REJECT" and decide(me, 200, None, False) == "REJECT")
check("zero/mismatched vectors never match", cos([0, 0], [1, 1]) == 2.0 and cos([1], [1, 2]) == 2.0)
check("constants mirrored", "THRESHOLD = 0.5" in SR and "MIN_FRAMES_COMMAND = 60" in SR and "MIN_FRAMES_ANSWER = 30" in SR)
# source guards
check("gate runs before processAnswer and process", NS.index("voiceAllows(bytes, isAnswer)") < NS.index("processAnswer(text) else process(bytes, text, dur)"))
check("gate covers yes/no answers too", "voiceAllows(bytes, isAnswer)" in NS)
check("gate is off the mic thread (inside Thread)", re.search(r"Thread \{\s*if \(!voiceAllows\(bytes, isAnswer\)\) return@Thread", NS) is not None)
check("lock needs switch AND profile", "ExtCfg.voiceLock(this) && VoiceLock.hasProfile(this)" in NS)
check("missing speaker model refuses (fail closed)", "if (got == null) tr(\"Awaaz jaanchne wala model nahi chal paya" in NS)
check("voiceMd cleared when loop ends", "voiceMd = null" in NS)
check("audio is never written to disk", "FileOutputStream" not in VL and "writeBytes" not in VL)
check("only voiceprint numbers saved, in no-backup dir", "noBackupFilesDir" in VL and "put(\"v\", arr)" in VL)
check("model download https + single host + size caps", "u.protocol != \"https\"" in VL and "MODEL_HOST" in VL and "MAX_ZIP_BYTES" in VL and "MAX_UNZIPPED_BYTES" in VL)
check("zip-slip guard", "bad zip path" in VL)
check("no keys/tokens in new files", not re.search(r"AIza|gsk_|ghp_|sk-[A-Za-z0-9]{20}", VL + SR))
check("lock default OFF", "getBoolean(VOICE_LOCK, false)" in EC)
check("bridge enabling needs a profile", "\"noprofile\"" in MA)
bridge = set(re.findall(r"Android\.(\w+)\(", UI))
check("every Android.x in ui.html exists in MainActivity", all(("fun %s(" % m) in MA for m in bridge))
check("camera/video not used by voice code", "Camera" not in VL + SR and "MediaRecorder.VideoSource" not in NS)
print("ALL PASSED" if not fails else "%d failed" % fails)
sys.exit(1 if fails else 0)
