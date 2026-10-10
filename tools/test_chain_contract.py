#!/usr/bin/env python3
"""NOVA part 1B contract test. Run: python3 tools/test_chain_contract.py
WHAT THIS PROVES: (1) the Kotlin source still contains the request skeleton, providers, order and cooldown numbers this test expects
(read from the .kt text); (2) the failover RULES, written as a Python PORT of ProviderChain/FreeProviders, behave as designed over REAL
HTTP sockets against a mock server (429, 401, 503, garbage 200, error-in-200, timeout, connection refused).
WHAT IT DOES NOT PROVE: that the Kotlin compiles or that FreeHttp/NovaService behave on a phone. Those need CI + a phone."""
import json, os, re, socket, sys, urllib.request, urllib.error
sys.path.insert(0, os.path.dirname(__file__))
import mock_provider_server as M

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = open(os.path.join(ROOT, "app/src/main/java/com/nova/assistant/FreeProviders.kt"), encoding="utf-8").read()
fails = []
def check(name, cond):
    print(("PASS " if cond else "FAIL ") + name)
    if not cond: fails.append(name)

# ---- (1) the Kotlin source still says what the contract expects
for frag in ['{\\"model\\":', '\\"role\\":\\"system\\"', '\\"role\\":\\"user\\"', '\\"max_tokens\\"', '\\"temperature\\":0.6']:
    check("kotlin buildBody contains " + frag, frag in KT)
urls = re.findall(r'"(https://[^"]+)"', KT)
check("3 https endpoints in Kotlin", len(urls) == 3 and all(u.startswith("https://") for u in urls))
check("provider order groq, openrouter, pollinations", re.search(r"listOf\(GROQ, OPENROUTER, POLLINATIONS\)", KT) is not None)
check("cooldown key = 600000", re.search(r'"key" -> 600_000L', KT) is not None)
check("pollinations anonymous (no key slot) on the current gen.pollinations.ai endpoint", re.search(r'listOf\("openai/gpt-5.4-nano", "openai"\), null', KT) is not None and "https://gen.pollinations.ai/v1/chat/completions" in KT and "text.pollinations.ai" not in KT)
check("chain sends only said text: buildBody takes (model, system, user)", "fun buildBody(model: String, system: String, user: String" in KT)

# ---- (2) Python PORT of the chain rules
def clean(t):
    t = re.sub(r"(?s)<think>.*?</think>", "", t); t = re.sub(r"[*#`]+", "", t)
    return re.sub(r"\s+", " ", t).strip()[:700]

def build_body(model, system, user):  # same field order and names as FreeProviders.buildBody
    return json.dumps({"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": user[:1000]}], "max_tokens": 350, "temperature": 0.6})

def parse_reply(body):
    try: c = json.loads(body)["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError): return None
    if not isinstance(c, str): return None
    t = clean(c); return t or None

def post(url, body, key, timeout):  # FreeHttp.post equivalent: 598 = no internet, 599 = timeout / IO
    req = urllib.request.Request(url, data=body.encode(), method="POST", headers={"Content-Type": "application/json"})
    if key: req.add_header("Authorization", "Bearer " + key)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, r.read().decode()
    except urllib.error.HTTPError as e: return e.code, e.read().decode()
    except (ConnectionRefusedError, socket.gaierror): return 598, ""
    except urllib.error.URLError as e:
        return (598, "") if isinstance(e.reason, (ConnectionRefusedError, socket.gaierror)) else (599, "")
    except (socket.timeout, OSError): return 599, ""

def cooldown_for(kind): return {"key": 600, "other": 20, "quota": 120, "server": 30, "network": 15, "model": 60}.get(kind, 0)
def kind_of(c): return "network" if c in (598, 599) else "key" if c in (401, 403) else "quota" if c == 429 else "model" if c in (400, 404, 422) else "server" if 500 <= c <= 597 else "other"
RANK = ["nokey", "cooling", "slow", "other", "model", "server", "quota", "key", "network"]
def worse(a, b): return b if RANK.index(b) > RANK.index(a) else a

def run_chain(providers, keys, cooling, user, timeout=1.0):
    """providers: list of (id, url, models, keyname). cooling: dict id -> True. Returns (text, provider, failkind, calls)."""
    calls, worst = [], "nokey"
    for pid, url, models, keyname in providers:
        if cooling.get(pid): worst = worse(worst, "cooling"); continue
        key = keys.get(keyname, "") if keyname else ""
        if keyname and not key: continue
        kind = "other"
        for m in models:
            calls.append(pid + ":" + m)
            code, body = post(url, build_body(m, "sys", user), key, timeout)
            if 200 <= code < 300:
                t = parse_reply(body)
                if t: return t, pid, "", calls
                kind = worse(kind, "other"); continue
            if code == 598: return None, None, "network", calls
            kind = worse(kind, kind_of(code))
            if code not in (429, 400, 404, 422): break
        if cooldown_for(kind): cooling[pid] = True
        worst = worse(worst, kind)
    return None, None, worst, calls

srv, port = M.start()
U = lambda p: "http://127.0.0.1:%d%s" % (port, p)

# a rate-limited, key-protected provider falls to a keyless one that answers; markdown / think are cleaned
M.SEEN.clear()
cool = {}
t, p, k, calls = run_chain([("groq", U("/rate"), ["m1", "m2"], "groq"), ("poll", U("/ok"), ["openai"], None)], {"groq": "K123456789012345678901"}, cool, "kyun aasman blue hai")
check("429 on both models -> next provider answers", p == "poll" and calls == ["groq:m1", "groq:m2", "poll:openai"])
check("answer is cleaned (no markdown / think)", t == "Aasman blue hota hai.")
check("rate-limited provider is cooling", cool.get("groq") is True)
auth = {path: a for path, a, _ in M.SEEN}
check("keyed provider got Bearer key, keyless got none", auth.get("/rate") == "Bearer K123456789012345678901" and auth.get("/ok") is None)
check("mock accepted the request shape (no 400)", all(b is not None for _, _, b in M.SEEN))

t, p, k, calls = run_chain([("a", U("/badkey"), ["m1", "m2"], "a"), ("b", U("/ok"), ["m"], None)], {"a": "x" * 20}, {}, "hello there")
check("401 -> one call to that provider, next provider answers", calls == ["a:m1", "b:m"] and p == "b")

t, p, k, calls = run_chain([("a", U("/down"), ["m1", "m2"], None), ("b", U("/ok"), ["m"], None)], {}, {}, "hello there")
check("503 -> no second model, next provider", calls == ["a:m1", "b:m"] and p == "b")

t, p, k, calls = run_chain([("a", U("/nomodel"), ["old", "new"], None), ("b", U("/ok"), ["m"], None)], {}, {}, "hello there")
check("404 -> second model tried, then next provider", calls == ["a:old", "a:new", "b:m"])

t, p, k, calls = run_chain([("a", U("/garbage"), ["m"], None), ("b", U("/errbody"), ["m"], None), ("c", U("/ok"), ["m"], None)], {}, {}, "hello there")
check("garbage 200 and error-inside-200 both count as failure", p == "c" and calls == ["a:m", "b:m", "c:m"])

t, p, k, calls = run_chain([("a", U("/slow"), ["m"], None), ("b", U("/ok"), ["m"], None)], {}, {}, "hello there", timeout=0.5)
check("timeout (599) -> next provider", p == "b" and calls == ["a:m", "b:m"])

dead = socket.socket(); dead.bind(("127.0.0.1", 0)); dport = dead.getsockname()[1]; dead.close()
t, p, k, calls = run_chain([("a", "http://127.0.0.1:%d/x" % dport, ["m"], None), ("b", U("/ok"), ["m"], None)], {}, {}, "hello there")
check("connection refused = 598 -> whole chain stops, b never called", t is None and k == "network" and calls == ["a:m"])

t, p, k, calls = run_chain([("a", U("/rate"), ["m"], None), ("b", U("/nomodel"), ["m"], None)], {}, {}, "hello there")
check("all fail -> most useful reason (quota)", t is None and k == "quota")

t, p, k, calls = run_chain([("a", U("/ok"), ["m"], "a")], {}, {}, "hello there")
check("provider needing a key the user lacks is skipped with no call", t is None and calls == [] and k == "nokey")

body_q = build_body("m", 'be "short"', 'line1\nline2 \u0939\u093f\u0928\u094d\u0926\u0940')
check("buildBody port is valid JSON with newline and Hindi preserved", json.loads(body_q)["messages"][1]["content"] == 'line1\nline2 \u0939\u093f\u0928\u094d\u0926\u0940')

print("\n%d failed" % len(fails) if fails else "\nALL PASSED")
sys.exit(1 if fails else 0)
