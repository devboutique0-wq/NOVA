#!/usr/bin/env python3
"""Static and parser-contract checks for the opt-in-to-free-chain, no-key Wikipedia fallback."""
from pathlib import Path
import re, sys
ROOT = Path(__file__).resolve().parents[1]
kt = (ROOT / "app/src/main/java/com/nova/assistant/PublicKnowledgeFallback.kt").read_text(encoding="utf-8")
svc = (ROOT / "app/src/main/java/com/nova/assistant/NovaService.kt").read_text(encoding="utf-8")
ui = (ROOT / "app/src/main/assets/ui.html").read_text(encoding="utf-8")
img = (ROOT / "app/src/main/java/com/nova/assistant/FreeImage.kt").read_text(encoding="utf-8")
image_gen = (ROOT / "app/src/main/java/com/nova/assistant/ImageGen.kt").read_text(encoding="utf-8")
checks = {
    "public knowledge uses Wikipedia HTTPS endpoint": "https://en.wikipedia.org/w/api.php?action=query&generator=search" in kt,
    "request uses no provider/API key": "Authorization" not in kt and "api_key" not in kt.lower(),
    "request sends topic query and returns source URL": "gsrsearch=$q" in kt and 'optString("fullurl"' in kt,
    "meaningful User-Agent is set": 'setRequestProperty("User-Agent"' in kt and 'setRequestProperty("Api-User-Agent"' in kt,
    "short connection/read timeouts and response-size cap exist": "CONNECT_TIMEOUT_MS = 1800" in kt and "READ_TIMEOUT_MS = 2400" in kt and "MAX_RESPONSE_BYTES" in kt,
    "results cached with bounded entry count": "CACHE_TTL_MS" in kt and "size > 32" in kt,
    "cache separates Hindi and English answer variants": 'if (en) "en:" else "hi:"' in kt,
    "common short topic AI is protected from generic noise filter": 'shortTopicAllowlist = setOf("ai"' in kt and 'topic.lowercase() !in shortTopicAllowlist' in kt,
    "time-sensitive and private topic guards exist": "liveWords" in kt and "personalWords" in kt,
    "lookup runs only if FREE AI chain is enabled": re.search(r"if \(ExtCfg\.freeChain\(this\)\)\s*\{\s*val publicAnswer = PublicKnowledgeFallback\.answer", svc) is not None,
    "public answer returns before Gemini request": svc.index("PublicKnowledgeFallback.answer") < svc.index("askCloud(pcm, said)", svc.index("private fun cloudStep")),
    "settings discloses topic-only lookup and accurately explains separate memory sharing": "Wikipedia (no key" in ui and "topic public Wikipedia ko bhejta hai" in ui and "poori chat ya awaaz nahi" in ui and "max 3 relevant saved facts" in ui,
    "free image flow uses quality prompt helper": "FreeImageRules.enhancePrompt(rawPrompt)" in img,
    "text-to-image Gemini prompt gets the same bounded quality brief": "val imagePrompt = if (photo == null) FreeImageRules.enhancePrompt(prompt) else prompt" in image_gen and "generateGemini(ctx, imagePrompt, photo)" in image_gen,
    "photo-edit prompt and photo bytes path are preserved": "if (photo != null) return Out(false, \"Photo edit abhi nahi ho paya" in image_gen and "inline_data" in image_gen,
    "prompt enhancer is idempotent across provider fallback": "if (subject.endsWith(QUALITY_BRIEF)) return subject" in img,
    "image prompt does not pretend to upscale or promise 4K": "does not upscale pixels or promise native 4K" in img,
    "existing voice image path remains": "val r = FreeImage.run(this, subject)" in svc,
    "agent safety gates are still present": "AgentRules.check(a, node, pkg, packageName)" in svc and "protected screens" in svc,
}
for name, ok in checks.items(): print(("PASS " if ok else "FAIL ") + name)
failed = [name for name, ok in checks.items() if not ok]

# A compact Python mirror for explicit-query intent only; it does not call the live endpoint.
def normalize(s):
    s = s.lower().replace("[unk]", " ").replace("-", " ")
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9% ]", " ", s)).strip()
LIVE = set("today tonight tomorrow yesterday latest current currently now live news weather mausam khabar price prices stock score scores trending election result results aaj abhi kal taaza naya rate bhaav".split())
PRIVATE = set("my mera meri mere mujhe humara hamara hamari phone contacts contact message messages password otp pin account bank payment address location aadhaar aadhar pan private personal family friend friends".split())
PREFIXES = ("tell me about ", "give me facts about ", "information about ", "what is ", "what are ", "who is ", "who are ", "who was ", "who were ", "what was ", "define ", "explain ", "meaning of ", "history of ", "how does ", "who invented ", "who discovered ", "when was ")
SUFFIXES = (" ke baare mein batao", " ke bare mein batao", " ke baare me batao", " ke bare me batao", " ke baare mein samjhao", " ke bare mein samjhao", " ke baare me samjhao", " ke bare me samjhao", " ke baare mein jankari do", " ke bare mein jankari do", " kya hai", " kaun hai", " kon hai", " kya hota hai", " ka matlab kya hai")
def topic_of(s):
    s = re.sub(r"(?i)^nova\s*[,.:;-]?\s*", "", s.strip())
    s = re.sub(r"[?!.]+$", "", s).strip()
    n = normalize(s); words = set(n.split())
    if not n or len(s) > 240 or words & LIVE: return None
    t = None
    for p in PREFIXES:
        if n.startswith(p): t = n[len(p):]; break
    if t is None:
        for z in SUFFIXES:
            if n.endswith(z): t = n[:-len(z)]; break
    if t is None: return None
    t = re.sub(r"^(?:please |mujhe |hame |hamen |tell me |about )+", "", t).strip()
    words = t.split()
    if not 2 <= len(t) <= 100 or len(words) > 10 or set(words) & (LIVE | PRIVATE): return None
    if any(not w.strip() for w in words): return None
    return t

cases = [
    ("what is artificial intelligence", "artificial intelligence"),
    ("what is AI", "ai"),
    ("tell me about quantum computing", "quantum computing"),
    ("nova solar panel ke baare mein batao", "solar panel"),
    ("mujhe photosynthesis kya hai", "photosynthesis"),
    ("who invented telephone", "telephone"),
    ("what is today's weather", None),
    ("what is current bitcoin price", None),
    ("who is my doctor", None),
    ("open YouTube", None),
    ("send a message", None),
    ("what is my bank account", None),
]
for text, expected in cases:
    got = topic_of(text)
    ok = got == expected
    print(("PASS " if ok else "FAIL ") + f"topic parser: {text!r} -> {got!r}")
    if not ok: failed.append(f"topic parser {text}")

if failed:
    print(f"\n{len(failed)} failed")
    sys.exit(1)
print(f"\nALL PASSED ({len(checks) + len(cases)} checks)")
