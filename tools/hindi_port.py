"""Python mirror of HindiRoman.translit / toRoman (same tables, read from HindiRoman.kt). Used to cross-check the Kotlin tests.
Not a run of the Kotlin code."""
import re, os
HERE = os.path.dirname(os.path.abspath(__file__))
KT = open(os.path.join(HERE, "..", "app", "src", "main", "java", "com", "nova", "assistant", "HindiRoman.kt"), encoding="utf8").read()
def _u(s): return re.sub(r"\\u([0-9A-Fa-f]{4})", lambda m: chr(int(m.group(1), 16)), s)
def _tbl(name):
    m = re.search(r"val %s = mapOf\((.*?)\n    \)" % name, KT, re.S)
    return {_u(a): b for a, b in re.findall(r"'(\\u[0-9A-F]{4})' to \"([a-z]*)\"", m.group(1))}
CONS, CONS_N, IND, MATRA = _tbl("CONS"), _tbl("CONS_NUKTA"), _tbl("IND_VOWEL"), _tbl("MATRA")
mm = re.search(r"val MAP: Map<String, String> = mapOf\((.*?)\n    \)\n", KT, re.S)
MAP = dict(re.findall(r'"([^"]+)" to "([^"]+)"', mm.group(1)))
NUKTA, VIRAMA = "\u093c", "\u094d"
PRE = {"\u0958": "\u0915", "\u0959": "\u0916", "\u095a": "\u0917", "\u095b": "\u091c", "\u095c": "\u0921", "\u095d": "\u0922", "\u095e": "\u092b", "\u095f": "\u092f"}
def decompose(s):
    o = []
    for ch in s:
        if ch in PRE: o += [PRE[ch], NUKTA]
        elif ch == "\u0901": o.append("\u0902")
        elif ch in "\u200c\u200d": pass
        elif "\u0966" <= ch <= "\u096f": o.append(chr(ord("0") + ord(ch) - 0x966))
        else: o.append(ch)
    return "".join(o)
def norm_key(w): return decompose(w).replace(NUKTA, "")
KEYS = {norm_key(k): v for k, v in MAP.items()}
def has_dev(s): return any("\u0900" <= c <= "\u097f" for c in s)
def translit(word):
    w = decompose(word); units = []; open_c = False; i = 0
    while i < len(w):
        ch = w[i]
        if ch in CONS:
            r = CONS[ch]
            if i + 1 < len(w) and w[i + 1] == NUKTA: r = CONS_N.get(ch, r); i += 1
            if open_c and units: units[-1][0] += r; units[-1][1] = None
            else: units.append([r, None, ""])
            open_c = False
        elif ch == VIRAMA:
            if units: units[-1][1] = ""; open_c = True
        elif ch in MATRA and units and units[-1][1] is None:
            units[-1][1] = MATRA[ch]; open_c = False
        elif ch in IND:
            units.append(["", IND[ch], ""]); open_c = False
        elif ch == "\u0902":
            if units: units[-1][2] += "n"
        elif ch == "\u0903":
            if units: units[-1][2] += "h"
        elif ch in (NUKTA, "\u0964", "\u0965"): pass
        else: units.append([ch, "", ""]); open_c = False
        i += 1
    n = len(units)
    if n > 1 and units[-1][1] is None: units[-1][1] = ""
    for k in range(n - 2, 0, -1):
        u = units[k]
        if u[1] is None and not u[2] and units[k - 1][1] != "" and units[k + 1][1] != "": u[1] = ""
    return "".join(u[0] + ("a" if u[1] is None else u[1]) + u[2] for u in units)
def to_roman(s):
    toks = [t for t in re.split("[\\s,\u0964.?!]+", s) if t.strip()]
    return " ".join(t if not has_dev(t) else MAP.get(t) or KEYS.get(norm_key(t)) or translit(t) for t in toks)
