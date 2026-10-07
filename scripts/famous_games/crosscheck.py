# Part of the famous-games curation tools (G1, docs/FAMOUS_GAMES.md section 4).
"""Follow interlanguage links of the primary pages and extract from every sister article (second sources)."""
import json
import sys
from wk import api
from survey import survey

sys.stdout.reconfigure(encoding="utf-8")
LANGS = {"de", "en", "ru", "uk", "fr", "es", "it", "nl", "pl", "cs", "hu", "sv", "pt", "ca", "fi", "bg", "da", "no", "sk", "ro"}

targets = json.load(open(sys.argv[1], encoding="utf-8"))
done = {(l, t) for l, t, _ in targets}
extra = []
for lang, title, per in targets:
    try:
        d = api(lang, action="query", prop="langlinks", titles=title, lllimit=500, redirects=1)
    except Exception as e:
        print("ERR", lang, title, e, file=sys.stderr)
        continue
    for p in d.get("query", {}).get("pages", {}).values():
        for ll in p.get("langlinks", []):
            l2, t2 = ll["lang"], ll["*"]
            if l2 in LANGS and (l2, t2) not in done:
                done.add((l2, t2))
                extra.append([l2, t2, per])
print("sister pages:", len(extra), file=sys.stderr)
rows = []
for lang, title, per in extra:
    try:
        rows += survey(lang, title, per)
    except Exception as e:
        print("ERR", lang, title, e, file=sys.stderr)
json.dump(rows, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("rows:", len(rows), file=sys.stderr)
