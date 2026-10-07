# Part of the famous-games curation tools (G1, docs/FAMOUS_GAMES.md section 4).
"""Small Wikipedia helper for the famous-games curation."""
import json
import os
import sys
import tempfile
import urllib.parse
import urllib.request

UA = "PalayaChessCuration/1.0 (https://github.com/palayax/Chess)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.environ.get("FAMOUS_WIKI_CACHE", os.path.join(tempfile.gettempdir(), "palaya_famous_wiki"))
os.makedirs(CACHE, exist_ok=True)


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8")


def api(lang, **params):
    params.setdefault("format", "json")
    url = f"https://{lang}.wikipedia.org/w/api.php?" + urllib.parse.urlencode(params)
    return json.loads(get(url))


def category(lang, cat):
    out = []
    cont = {}
    while True:
        d = api(lang, action="query", list="categorymembers", cmtitle=cat, cmlimit=500, **cont)
        out += [m["title"] for m in d.get("query", {}).get("categorymembers", [])]
        if "continue" in d:
            cont = {"cmcontinue": d["continue"]["cmcontinue"]}
        else:
            return out


def raw(lang, title):
    fn = os.path.join(CACHE, f"{lang}__" + title.replace("/", "_").replace(":", "_").replace(" ", "_") + ".txt")
    if os.path.exists(fn):
        return open(fn, encoding="utf-8").read()
    url = f"https://{lang}.wikipedia.org/w/index.php?" + urllib.parse.urlencode({"title": title, "action": "raw", "redirect": "true"})
    try:
        t = get(url)
    except Exception as e:  # noqa
        t = ""
        print("FAIL", lang, title, e, file=sys.stderr)
    # follow a redirect
    if t.lstrip().upper().startswith("#REDIRECT") or t.lstrip().upper().startswith("#WEITERLEITUNG"):
        import re
        m = re.search(r"\[\[([^\]|#]+)", t)
        if m:
            return raw(lang, m.group(1))
    open(fn, "w", encoding="utf-8").write(t)
    return t


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "cat":
        for t in category(sys.argv[2], sys.argv[3]):
            print(t)
    elif cmd == "raw":
        sys.stdout.reconfigure(encoding="utf-8")
        print(raw(sys.argv[2], sys.argv[3]))
