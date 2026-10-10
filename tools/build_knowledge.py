#!/usr/bin/env python3
"""Merges tools/knowledge_src/k_*.py into app/src/main/assets/knowledge.json (sorted by id, unique ids)."""
import glob, importlib.util, json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "knowledge.json")

def load():
    entries, seen = [], {}
    for path in sorted(glob.glob(os.path.join(HERE, "knowledge_src", "k_*.py"))):
        spec = importlib.util.spec_from_file_location(os.path.basename(path)[:-3], path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        for t in mod.E:
            if len(t) != 5:
                sys.exit("bad tuple in %s: %r" % (path, t[:1]))
            i, q, hi, en, tags = t
            if i in seen:
                sys.exit("duplicate id %s (%s and %s)" % (i, seen[i], path))
            seen[i] = path
            entries.append({"id": i, "q": list(q), "hi": hi, "en": en, "tags": list(tags)})
    entries.sort(key=lambda e: e["id"])
    return entries

def main():
    entries = load()
    doc = {"title": "NOVA knowledge pack", "version": 1, "entries": entries}
    with open(OUT, "w", encoding="utf8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=0)
        f.write("\n")
    print("wrote %d entries, %d bytes -> %s" % (len(entries), os.path.getsize(OUT), os.path.relpath(OUT)))

if __name__ == "__main__":
    main()
