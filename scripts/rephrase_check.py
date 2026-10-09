#!/usr/bin/env python3
"""Independent Python twin of core's ClaimChecker (docs/LLM_REPHRASE_DESIGN.md section 5.4).

The Kotlin ``core.text.ClaimChecker`` decides whether a model's rewording of a verified commentary text
(a CARD text or a NARRATION beat) may be shown.  This file re-implements the same rules from the written
spec only (own regexes, no shared code) so that two implementations can be made to agree on every recorded
text and every mutation.  A text the Kotlin checker ACCEPTs but this one REJECTs is a checker bug.

Python 3.13, standard library only; run it as ``python -I scripts/rephrase_check.py <mode> <file.jsonl>``.

Modes
-----
``rephrase <file.jsonl>``
    Lines: {"original", "candidate", "surface", optional "kotlin", "kotlin_reason"}.  Prints the verdict counts
    per surface, the rejections per reason and (when "kotlin" is present) the agreement with Kotlin.
    Exit 1 on any verdict difference, or if Kotlin ACCEPTed something this checker REJECTs.
``mutate-rephrase <corpus.jsonl>``
    Lines: {"text", "surface"}.  Applies every negative-control mutation (see ``mutations``) and checks the
    result with ``check``; the unmutated text must be UNCHANGED, every mutation must be REJECT.  Exit 1 on a miss.

API: ``check(original, candidate, surface) -> (verdict, reason)``, ``facts(text, surface) -> dict``,
``mutations(text, surface) -> [(name, candidate, expected_reasons)]``, ``main(argv) -> int``.
"""

from __future__ import annotations

import collections
import json
import re
import sys
import unicodedata

# ---------------------------------------------------------------------------------------------------------
# Normalisation and splitting
# ---------------------------------------------------------------------------------------------------------

_WS = re.compile(r"[ \t\n\r\f\v]+")
_WS_CHARS = " \t\n\r\f\v"


def normalise(text: str) -> str:
    """NFC, straight quotes, collapsed whitespace, trimmed, a final full stop added if missing."""
    t = unicodedata.normalize("NFC", text)
    t = t.replace("‘", "'").replace("’", "'").replace("“", '"').replace("”", '"')
    t = _WS.sub(" ", t).strip(_WS_CHARS)
    if t and t[-1] not in ".!?":
        t += "."
    return t


_SPOKEN_DIGITS = {"one": "1", "two": "2", "three": "3", "four": "4", "five": "5", "six": "6", "seven": "7",
                  "eight": "8"}
_SPOKEN_SQUARE = re.compile(r"\b([a-h]) (one|two|three|four|five|six|seven|eight)\b")


def fold(text: str) -> str:
    """NARRATION only: spoken squares ("h five") back to notation ("h5")."""
    return _SPOKEN_SQUARE.sub(lambda m: m.group(1) + _SPOKEN_DIGITS[m.group(2)], text)


def prepared(text: str, surface: str) -> str:
    """Normalised text, folded for NARRATION: the form every fact is extracted from."""
    t = normalise(text)
    return fold(t) if surface == "NARRATION" else t


def sentences(t: str) -> list[str]:
    return [s for s in re.split(r"(?<=[.!?])\s+", t) if s]


def word_count(t: str) -> int:
    return len(t.split(" ")) if t else 0


# ---------------------------------------------------------------------------------------------------------
# Notation: moves, squares
# ---------------------------------------------------------------------------------------------------------

_MOVE_BODY = (r"O-O-O|O-O|[KQRBN][a-h]?[1-8]?x?[a-h][1-8]|[a-h]x[a-h][1-8](?:=[QRBN])?|[a-h][1-8]=[QRBN]")
MOVE_RE = re.compile(r"(?<![A-Za-z0-9])(?:" + _MOVE_BODY + r")[+#]?(?![A-Za-z0-9=])")
SQUARE_RE = re.compile(r"(?<![A-Za-z0-9])[a-h][1-8][+#]?(?![A-Za-z0-9])")
# first move-or-square token, moves first at the same position
TOKEN_RE = re.compile(MOVE_RE.pattern + "|" + SQUARE_RE.pattern)


def strip_moves(t: str) -> str:
    return MOVE_RE.sub(" ", t)


def strip_moves_and_squares(t: str) -> str:
    return SQUARE_RE.sub(" ", strip_moves(t))


# ---------------------------------------------------------------------------------------------------------
# Fact classes
# ---------------------------------------------------------------------------------------------------------

_PIECES = "pawn|knight|bishop|rook|queen|king"
_PIECE_SQUARE_RE = re.compile(r"\b(" + _PIECES + r")s? on ([a-h][1-8])\b", re.I)
_PIECE_RE = re.compile(r"\b(" + _PIECES + r")s?\b", re.I)

_PLAYER_RE = re.compile(r"\byour opponent\b|\b(?:you|your|yours)\b|\b(?:White|Black)\b", re.I)


def _player_label(s: str) -> str:
    low = s.lower()
    if low == "your opponent":
        return "OPPONENT"
    if low in ("you", "your", "yours"):
        return "YOU"
    return s.upper()  # White / Black are case-sensitive; see _players


def _players(t: str) -> list[str]:
    out: list[str] = []
    for m in _PLAYER_RE.finditer(t):
        s = m.group(0)
        low = s.lower()
        if low in ("white", "black"):
            if s not in ("White", "Black"):
                continue  # case-sensitive: "the white pieces" names no player
            label = s.upper()
        else:
            label = _player_label(s)
        if not out or out[-1] != label:
            out.append(label)
    return out


def _player_before(s: str, pos: int) -> str | None:
    last = None
    for m in _PLAYER_RE.finditer(s[:pos]):
        text = m.group(0)
        if text.lower() in ("white", "black") and text not in ("White", "Black"):
            continue
        last = _player_label(text)
    return last


_SPELLED = ("two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
            "seventeen eighteen nineteen twenty").split()
_DIGITS_RE = re.compile(r"\d+(?:\.\d+)?")
_SPELLED_RE = re.compile(r"\b(" + "|".join(_SPELLED) + r")\b", re.I)
_HALF_RE = re.compile(r"\bhalf\b", re.I)
_TWICE_RE = re.compile(r"\btwice\b", re.I)
_DOUBLE_RE = re.compile(r"\bdouble\b(?! (?:attack|check))", re.I)


def _numbers(t: str) -> list[str]:
    body = strip_moves_and_squares(t)
    out = _DIGITS_RE.findall(body)
    out += [m.group(1).lower() for m in _SPELLED_RE.finditer(body)]
    out += ["0.5"] * len(_HALF_RE.findall(body))
    out += ["2x"] * (len(_TWICE_RE.findall(body)) + len(_DOUBLE_RE.findall(body)))
    return sorted(out)


def _w(pattern: str) -> re.Pattern:
    return re.compile(r"\b(?:" + pattern + r")\b", re.I)


TERMS: dict[str, re.Pattern] = {
    "fork": _w(r"forks?|forked|forking"),
    "pawn fork": _w(r"pawn forks?"),
    "double attack": _w(r"double attacks?"),
    "pin": _w(r"pins?|pinned|pinning"),
    "absolute pin": _w(r"absolute pins?"),
    "relative pin": _w(r"relative pins?"),
    "skewer": _w(r"skewers?|skewered|skewering"),
    "discovered": _w(r"discovered|uncovers?|uncovered|uncovering|unmasked"),
    "discovered check": _w(r"discovered checks?"),
    "double check": _w(r"double checks?"),
    "check": _w(r"checks?"),
    "mate": _w(r"(?:check)?mat(?:e|es|ed|ing)"),
    "checkmate": _w(r"checkmates?"),
    "forced mate": _w(r"forced mates?|mate in"),
    "en prise": _w(r"en prise"),
    "undefended": _w(r"undefended|unprotected|loose|hanging|no defenders?|nothing defending|nothing guarding"),
    "trapped": _w(r"traps?|trapped|no safe squares?"),
    "the exchange": _w(r"the exchanges?"),
    "zwischenzug": _w(r"zwischenzugs?|in-between moves?"),
    "overload": _w(r"overload|overloaded|overloads"),
    "desperado": _w(r"desperados?"),
    "back rank": _w(r"back[ -]ranks?"),
    "smothered": _w(r"smothered"),
    "only move": _w(r"only (?:legal )?moves?"),
    "sacrifice": re.compile(r"\b(?:sacrific(?:e|es|ed|ing)|offered)\b|\boffers?\b(?= (?:the|a|an|its)\b)", re.I),
    "deflect": _w(r"deflect|deflects|deflected|deflection|deflecting"),
    "decoy": _w(r"decoys?|lures?|lured"),
    "clearance": _w(r"clearance|clears|cleared"),
    "removing the defender": _w(r"removing the defenders?|takes away"),
    "interference": re.compile(r"\binterference\b|\bcuts?\b.{0,40}?\boff\b", re.I),
    "greek gift": _w(r"greek gifts?"),
    "windmill": _w(r"windmills?"),
    "promotion": _w(r"promote|promotes|promoted|promoting|promotion|underpromote|underpromotes|underpromoted|underpromotion"),
    "underpromotion": _w(r"underpromotions?"),
    "winning position": _w(r"winning positions?"),
    "decisive advantage": _w(r"decisive advantages?"),
    "more attackers": _w(r"more attackers than defenders|more often than it is defended"),
    "turning point": _w(r"turning points?"),
    "theory": _w(r"theory|book"),
    "castle": _w(r"castle|castles|castled|castling"),
    "kingside": _w(r"kingside"),
    "queenside": _w(r"queenside"),
    "engine": _w(r"engine(?:'s|s)?"),
    "material": _w(r"material"),
    "accuracy": _w(r"accuracy|percent"),
}
# "pin" must not fire inside "relative pin"/"absolute pin" counts of their own, but the spec counts each term
# independently, so overlapping terms are counted independently on purpose.


def _terms(t: str) -> dict[str, int]:
    out = {}
    for name, rx in TERMS.items():
        n = len(rx.findall(t))
        if n:
            out[name] = n
    return out


_BAND_RE = re.compile(
    r"\b(?:decisively winning|decisively lost|completely winning|completely lost|completely won|clearly better|"
    r"slightly better|clearly worse|slightly worse|about level|the evaluation moves|winning|losing|equal)\b", re.I)

BAND_WORDS = ["decisively winning", "decisively lost", "completely winning", "completely lost", "completely won",
              "clearly better", "slightly better", "clearly worse", "slightly worse", "about level", "winning",
              "losing", "equal"]


def _bands(t: str) -> list[str]:
    return [m.group(0).lower() for m in _BAND_RE.finditer(t)]


OUTCOMES: dict[str, re.Pattern] = {
    "WIN": re.compile(r"\b(?:wins?|won(?!')|picks? up|picked up|picking up|winning (?:a|an|the)|gains?|gained|gaining|collects?|collected|collecting|nets?|netted|netting)\b", re.I),
    "LOSE": re.compile(r"\b(?:loses?|lost|losing (?:a|an|the)|drops?|dropped|dropping|hangs?|hung|gives? away|giving away|gave away|throwing(?: \S+){0,6}? away|costing|throws?(?: \S+){0,6}? away|costs?)\b",
                       re.I),
    "THREAT": _w(r"threats?|threaten|threatens|threatened|threatening"),
    "ATTACK": _w(r"attacks?|attacked|attacking|attacker|attackers|hits?|hitting|piles? up|piled up|piling up"),
    "DEFEND": _w(r"saves?|saved|saving|holds?|held|holding|defends?|defended|defending|defender|defenders|defence|defense|"
                 r"guards?|guarded|guarding|protects?|protected|protecting"),
    "CAPTURE": re.compile(r"\b(?:takes?|took|taking|captur(?:e|es|ed|ing)|grabs?|grabbed|grabbing)\b"
                          r"(?!\s+(?:White|Black|[Yy]ou|[Yy]our)\b)", re.I),
    "FORCE": _w(r"force|forces|forced|forcing"),
}
# re.I would make White/Black in the CAPTURE look-ahead case-insensitive; that is harmless ("takes white").


def _outcomes(t: str) -> dict[str, int]:
    out = {}
    for name, rx in OUTCOMES.items():
        n = len(rx.findall(t))
        if n:
            out[name] = n
    return out


_WORD_RE = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
_NOT_NAMES = {"White", "Black", "I", "You", "Your", "Yours"}


def _cap_tokens(s: str):
    """Words of a sentence as (word, start, end) with a trailing 's removed."""
    out = []
    for m in _WORD_RE.finditer(s):
        w = m.group(0)
        if w.endswith("'s"):
            w = w[:-2]
        out.append((w, m.start(), m.end()))
    return out


def _is_cap(w: str) -> bool:
    return len(w) >= 2 and w[0].isupper()


def _names(t: str) -> list[str]:
    found: set[str] = set()
    for s in sentences(strip_moves(t)):
        toks = _cap_tokens(s)
        for i, (w, _, end) in enumerate(toks):
            if not _is_cap(w) or w in _NOT_NAMES:
                continue
            if i > 0:
                found.add(w)
                continue
            if i + 1 < len(toks):
                nw, nstart, _ = toks[i + 1]
                if s[end:nstart].replace("'s", "") == " " and _is_cap(nw) and nw not in ("White", "Black"):
                    found.add(w)
    return sorted(found)


_NEG_RE = re.compile(r"\b(?:not|no|nothing|none|never|without|nobody|neither|nor|cannot|nowhere)\b|n't\b", re.I)

_BETTER_RE = re.compile(r"Better was (\S+?)(?=[,.:;](?:\s|$))")
_CHARGE_TRIGGER = re.compile(r"\b(?:lets?|hands|allows)\b|\bcan play\b", re.I)


def _better_was(t: str) -> str | None:
    m = _BETTER_RE.search(t)
    return m.group(1) if m else None


def _charges(t: str) -> list[str]:
    out = []
    for s in sentences(t):
        if not _CHARGE_TRIGGER.search(s):
            continue
        m = TOKEN_RE.search(s)
        if not m:
            continue
        out.append(f"{_player_before(s, m.start())}>{m.group(0)}")
    return out


def facts(text: str, surface: str) -> dict:
    """All fact classes of ``text`` (normalised, and folded for NARRATION)."""
    t = prepared(text, surface)
    moves = sorted(m.group(0) for m in MOVE_RE.finditer(t))
    squares = sorted(m.group(0) for m in SQUARE_RE.finditer(strip_moves(t)))
    piece_squares = sorted({f"{m.group(1).lower()}@{m.group(2)}" for m in _PIECE_SQUARE_RE.finditer(t)})
    pieces = sorted(m.group(1).lower() for m in _PIECE_RE.finditer(t))
    return {
        "MOVES": moves,
        "SQUARES": squares,
        "PIECE_SQUARES": piece_squares,
        "PIECES": pieces,
        "PLAYERS": _players(t),
        "NUMBERS": _numbers(t),
        "TERMS": _terms(t),
        "BANDS": _bands(t),
        "OUTCOMES": _outcomes(t),
        "NAMES": _names(t),
        "NEGATION": len(_NEG_RE.findall(t)),
        "BETTER_WAS": _better_was(t),
        "CHARGES": _charges(t),
    }


_FACT_ORDER = ["MOVES", "SQUARES", "PIECE_SQUARES", "PIECES", "PLAYERS", "NUMBERS", "TERMS", "BANDS", "OUTCOMES",
               "NAMES", "NEGATION"]

# ---------------------------------------------------------------------------------------------------------
# Rule tables
# ---------------------------------------------------------------------------------------------------------

_FORBIDDEN_PUNCT = set('#*"`_[]{}<>|\\~^=@$%&')
_LIST_MARKER = re.compile(r"^(?:- |• |\d+[.)] )")
_PREAMBLE = re.compile(r"^(here is|here's|sure|certainly|of course|okay|ok|rewritten|rewrite|polished|output|text|"
                       r"answer|result)\b", re.I)
_REWRIT = re.compile(r"\brew(?:rit|rot)\w*", re.I)
_CLASS_NAMES = {"blunder", "mistake", "inaccuracy", "brilliant", "great", "best", "excellent", "good", "book",
                "forced", "miss"}
_FIRST_PERSON_I = re.compile(r"\bI(?:'m|'d)?\b")  # "I" is case-sensitive


def _first_person_count(t: str) -> int:
    n = len(re.findall(r"\b(?:we're|we've|we'll|let's|ours?|we|us|our|me|my)\b", t, re.I))
    return n + len(_FIRST_PERSON_I.findall(t))


_BANNED_PHRASES = ["forces mate", "allowed", "drops the", "sets up a deflection", "sets up an",
                   "sets up a clearance", "keeping material level", "stunning", "opens a discovered attack",
                   "the point becomes clear", "probably", "might", "plan", "idea", "intends", "strategic"]
_BANNED_WORDS = """likely perhaps maybe possibly could should must always never obviously brilliant beautiful
crushing devastating dominant initiative tempo development control pressure positional strong stronger strongest
weak weaker weakest powerful excellent great good bad terrible awful mistake mistakes blunder blunders error
errors inaccuracy inaccuracies accurate inaccurate precise imprecise careless clever nice fantastic superb
impressive dubious risky dangerous aggressive passive crucial critical decisive decisively serious huge massive
best worst better worse fine solid sound reasonable slip wrong correct right winner loser advantage edge lead
ahead behind seems appears probably certainly surely definitely really very simply easily completely totally
entirely fully finally unfortunately luckily sadly happily amazing incredible remarkable elegant subtle quiet
only just all every meanwhile however""".split()
BANNED_ITEMS: list[tuple[str, re.Pattern]] = [(p, re.compile(r"\b" + re.escape(p) + r"\b", re.I))
                                              for p in dict.fromkeys(_BANNED_PHRASES + _BANNED_WORDS)]
BANNED_ITEMS += [
    ("clearly (not better/worse)", re.compile(r"\bclearly\b(?! (?:better|worse)\b)", re.I)),
    ("wins the <piece> on", re.compile(r"\bwins the (?:pawn|knight|bishop|rook|queen|king) on\b", re.I)),
    ("<piece> up/down", re.compile(r"\b(?:pawn|knight|bishop|rook|queen|piece|exchange|material)s? "
                                   r"(?:up|down|ahead|behind)\b", re.I)),
]

_STARTERS = set("""the this that it and but so now then here there after before with in on at for from instead still
both each every one two three four five six seven eight nine ten only no not nothing all what which when while yet
also even just once next first last back again later soon shortly""".split())


# ---------------------------------------------------------------------------------------------------------
# The check
# ---------------------------------------------------------------------------------------------------------


def check(original: str, candidate: str, surface: str) -> tuple[str, str | None]:
    """Returns (verdict, reason): ("ACCEPT"|"UNCHANGED"|"REJECT", reason code or None)."""
    # 1
    raw = candidate.strip(_WS_CHARS)
    if not raw.strip():
        return "REJECT", "EMPTY"
    if "\n" in raw or "\r" in raw:
        return "REJECT", "SHAPE_FORMAT"
    o = normalise(original)
    c = normalise(candidate)
    # 2
    if c == o:
        return "UNCHANGED", None
    # 3
    for ch in c:
        if ch in o:
            continue
        if not (" " <= ch <= "~") and ch != "–":
            return "REJECT", "SHAPE_FORMAT"
        if ch in _FORBIDDEN_PUNCT:
            return "REJECT", "SHAPE_FORMAT"
    # 4
    if _LIST_MARKER.match(c):
        return "REJECT", "SHAPE_FORMAT"
    # 5
    m = _PREAMBLE.match(c)
    if m:
        mo = _PREAMBLE.match(o)
        if not (mo and mo.group(1).lower() == m.group(1).lower()):
            return "REJECT", "SHAPE_PREAMBLE"
    if _REWRIT.search(c) and not _REWRIT.search(o):
        return "REJECT", "SHAPE_PREAMBLE"
    # 6
    if surface == "NARRATION":
        if len(MOVE_RE.findall(c)) + len(SQUARE_RE.findall(strip_moves(c))) > \
                len(MOVE_RE.findall(o)) + len(SQUARE_RE.findall(strip_moves(o))):
            return "REJECT", "SHAPE_FORMAT"
    # 7
    ow, cw = word_count(o), word_count(c)
    if cw < 0.7 * ow or cw > 1.3 * ow or cw > ow + 12:
        return "REJECT", "SHAPE_LENGTH"
    # 8
    osent, csent = sentences(o), sentences(c)
    if len(csent) == 0 or len(csent) > len(osent) + 1:
        return "REJECT", "SHAPE_SENTENCES"
    # 9
    low = [s.lower() for s in csent]
    if len(set(low)) < len(low) and len(set(s.lower() for s in osent)) == len(osent):
        return "REJECT", "SHAPE_REPEAT"
    # 10
    fw = re.match(r"[A-Za-z']+", c)
    if fw and fw.group(0).lower() in _CLASS_NAMES:
        ofw = re.match(r"[A-Za-z']+", o)
        if not (ofw and ofw.group(0).lower() == fw.group(0).lower()):
            return "REJECT", "REGISTER_CLASS_NAME"
    # 11
    if (surface == "CARD" and "!" in c) or c.count("!") > o.count("!"):
        return "REJECT", "REGISTER_EXCLAMATION"
    # 12
    if _first_person_count(c) > _first_person_count(o):
        return "REJECT", "REGISTER_FIRST_PERSON"
    # 13
    of = fold(o) if surface == "NARRATION" else o
    cf = fold(c) if surface == "NARRATION" else c
    for _, rx in BANNED_ITEMS:
        if len(rx.findall(cf)) > len(rx.findall(of)):
            return "REJECT", "BANNED"
    # 14
    fo, fc = facts(o, surface), facts(c, surface)
    if fo["BETTER_WAS"] is not None or fc["BETTER_WAS"] is not None:
        if fo["BETTER_WAS"] != fc["BETTER_WAS"]:
            return "REJECT", "BETTER_WAS"
        if c.count("Better was") != 1:
            return "REJECT", "BETTER_WAS"
        if not csent[-1].startswith("Better was " + fc["BETTER_WAS"]):
            return "REJECT", "BETTER_WAS"
    # 15
    cfs = sentences(prepared(c, surface))
    for charge in fo["CHARGES"]:
        who, _, move = charge.partition(">")
        pat = re.compile(r"(?<![A-Za-z0-9])" + re.escape(move) + r"(?![A-Za-z0-9+#])")
        ok = False
        for s in cfs:
            for mm in pat.finditer(s):
                if str(_player_before(s, mm.start())) == who:
                    ok = True
                    break
            if ok:
                break
        if not ok:
            return "REJECT", "CHARGE"
    # 16
    for key in _FACT_ORDER:
        if fo[key] != fc[key]:
            return "REJECT", "FACTS_" + key
    cprep = prepared(c, surface)
    oprep = prepared(o, surface)
    oword = {w.lower() for w, _, _ in _cap_tokens(strip_moves(oprep))}
    for s in sentences(strip_moves(cprep)):
        for w, _, _ in _cap_tokens(s):
            if _is_cap(w) and w not in _NOT_NAMES and w.lower() not in oword and w.lower() not in _STARTERS:
                return "REJECT", "FACTS_NAMES"
    # 17
    return "ACCEPT", None


# ---------------------------------------------------------------------------------------------------------
# Negative controls
# ---------------------------------------------------------------------------------------------------------

_MIRROR = dict(zip("abcdefgh", "hgfedcba"))
_PIECE_WORD = re.compile(r"\b(pawn|knight|bishop|rook|queen|king)(s?)\b", re.I)
_SIDE_RE = re.compile(r"\b[Yy]our opponent's|\b[Yy]our opponent\b|\b[Yy]ou\b|\bWhite\b|\bBlack\b")
_BAND_PHRASE = "|".join(re.escape(b) for b in BAND_WORDS)
_FROM_TO = re.compile(r"\bfrom (" + _BAND_PHRASE + r") to (" + _BAND_PHRASE + r")\b", re.I)


def _cap_like(src: str, dst: str) -> str:
    return dst[:1].upper() + dst[1:] if src[:1].isupper() else dst


def _insert_first_sentence(text: str, ins: str) -> str | None:
    m = re.search(r"[.!?](?=\s|$)", text)
    return None if not m else text[:m.start()] + ins + text[m.start():]


def _split_sentences(text: str) -> list[str]:
    return [s for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s]


def mutations(text: str, surface: str) -> list[tuple[str, str, list[str]]]:
    """The negative controls that apply to ``text``: (name, candidate, expected reason codes)."""
    out: list[tuple[str, str, list[str]]] = []
    text = text.strip()
    base = text if text and text[-1] in ".!?" else text + "."

    def add(name, cand, expected):
        if cand is not None and cand != text:
            out.append((name, cand, expected))

    # square changed
    if surface == "CARD":
        masked = MOVE_RE.sub(lambda m: " " * len(m.group(0)), text)
        m = SQUARE_RE.search(masked)
        if m:
            add("square changed (file mirrored)", text[:m.start()] + _MIRROR[text[m.start()]] + text[m.start() + 1:],
                ["FACTS_SQUARES", "CHARGE", "BETTER_WAS"])
    else:
        m = _SPOKEN_SQUARE.search(text)
        if m:
            s = m.start(1)
            add("square changed (file mirrored)", text[:s] + _MIRROR[text[s]] + text[s + 1:],
                ["FACTS_SQUARES", "CHARGE", "BETTER_WAS"])
    # piece renamed
    m = _PIECE_WORD.search(text)
    if m:
        new = "knight" if m.group(1).lower() == "bishop" else "bishop"
        add("piece renamed", text[:m.start()] + _cap_like(m.group(1), new) + m.group(2) + text[m.end():],
            ["FACTS_PIECES", "FACTS_PIECE_SQUARES"])
    # side flipped
    if _SIDE_RE.search(text):
        def flip(mm):
            s = mm.group(0)
            if s == "White":
                return "Black"
            if s == "Black":
                return "White"
            low = s.lower()
            if low == "your opponent's":
                return _cap_like(s, "your")
            if low == "your opponent":
                return _cap_like(s, "you")
            return _cap_like(s, "your opponent")
        add("side flipped", _SIDE_RE.sub(flip, text), ["FACTS_PLAYERS", "CHARGE"])
    # Better was
    sents = _split_sentences(base)
    bi = [i for i, s in enumerate(sents) if "Better was" in s]
    if bi:
        i = bi[-1]
        if len(sents) > 1:
            add("Better was dropped", " ".join(sents[:i] + sents[i + 1:]), ["BETTER_WAS", "SHAPE_LENGTH"])
            if i != 0:
                add("Better was moved first", " ".join([sents[i]] + sents[:i] + sents[i + 1:]), ["BETTER_WAS"])
        m = _BETTER_RE.search(base)
        if m:
            other = "Qd2" if m.group(1) == "Qd1" else "Qd1"
            add("Better was names another move", base[:m.start(1)] + other + base[m.end(1):], ["BETTER_WAS"])
    # number changed
    masked = MOVE_RE.sub(lambda m: " " * len(m.group(0)), text)
    masked = SQUARE_RE.sub(lambda m: " " * len(m.group(0)), masked)
    m = re.search(r"(?<![A-Za-z0-9])\d+(?:\.\d+)?(?![A-Za-z0-9])", masked)
    if m:
        tok = m.group(0)
        if "." in tok:
            ip, fp = tok.split(".")
            new = f"{int(ip) + 1}.{fp}"
        else:
            new = str(int(tok) + 1)
        add("number changed", text[:m.start()] + new + text[m.end():], ["FACTS_NUMBERS"])
    # bands swapped
    for m in _FROM_TO.finditer(text):
        a, b = m.group(1), m.group(2)
        if a.lower() != b.lower():
            add("bands swapped", text[:m.start()] + f"from {b} to {a}" + text[m.end():], ["FACTS_BANDS"])
            break
    # term added
    if "fork" not in text.lower():
        add("term added (a fork)", _insert_first_sentence(text, ", a fork"), ["FACTS_TERMS", "SHAPE_LENGTH"])
    # term weakened
    for pat, repl in (("relative pin", "pin"), ("absolute pin", "pin"), ("en prise", "attacked"),
                      ("zwischenzug", "move"), ("forced mate", "attack")):
        m = re.search(r"\b" + pat + r"(?=s?\b)", text, re.I)
        if m:
            add("term weakened (" + pat + ")", text[:m.start()] + _cap_like(m.group(0), repl) + text[m.end():],
                ["FACTS_TERMS", "FACTS_OUTCOMES"])
            break
    # outcome verb added
    add("outcome verb added (and wins it)", _insert_first_sentence(text, " and wins it"),
        ["FACTS_OUTCOMES", "SHAPE_LENGTH"])
    # hedge added
    parts = text.split(" ", 1)
    if len(parts) == 2:
        add("hedge added (probably)", parts[0] + " probably " + parts[1], ["BANNED"])
    # praise
    add("praise added (a brilliant idea)", base + " It is a brilliant idea.", ["BANNED", "SHAPE_LENGTH"])
    # doubled
    add("text doubled", base + " " + base, ["SHAPE_LENGTH", "SHAPE_SENTENCES", "SHAPE_REPEAT"])
    # list format
    add("list format", "- " + base.replace(". ", ".\n- "), ["SHAPE_FORMAT"])
    # preamble
    add("preamble", "Here is the rewritten text: " + text, ["SHAPE_PREAMBLE"])
    # twice as long
    n = word_count(normalise(text)) + 1
    add("twice as long", base + " " + " ".join(["more"] * n) + ".", ["SHAPE_LENGTH"])
    # classification name
    fwm = re.match(r"[A-Za-z']+", text)
    if not (fwm and fwm.group(0).lower() == "blunder"):
        add("classification name prepended", "Blunder. " + text, ["REGISTER_CLASS_NAME", "SHAPE_LENGTH"])
    # you -> White
    if re.search(r"\byou\b", text, re.I):
        add("you -> White", re.sub(r"\byou\b", "White", text, flags=re.I), ["FACTS_PLAYERS", "CHARGE"])
    # negation dropped
    m = re.search(r"\b(?:not|no|nothing) ", text, re.I)
    if m:
        add("negation dropped", text[:m.start()] + text[m.end():], ["FACTS_NEGATION", "FACTS_TERMS", "FACTS_OUTCOMES"])
    # first person
    add("first person added", _insert_first_sentence(text, ", as we see"), ["REGISTER_FIRST_PERSON", "SHAPE_LENGTH"])
    # notation in narration
    if surface == "NARRATION":
        m = _SPOKEN_SQUARE.search(text)
        if m:
            add("notation in narration (h five -> h5)",
                text[:m.start()] + m.group(1) + _SPOKEN_DIGITS[m.group(2)] + text[m.end():], ["SHAPE_FORMAT"])
    return out


# ---------------------------------------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------------------------------------


def _read_jsonl(path: str) -> list[dict]:
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def _fmt(verdict: str, reason: str | None) -> str:
    return verdict + (" " + reason if reason else "")


def cmd_rephrase(path: str) -> int:
    rows = _read_jsonl(path)
    per_surface: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    reasons: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    diffs, reason_diffs, bugs = [], [], []
    with_kotlin = 0
    for r in rows:
        v, why = check(r["original"], r["candidate"], r["surface"])
        per_surface[r["surface"]][v] += 1
        if v == "REJECT":
            reasons[r["surface"]][why] += 1
        k = r.get("kotlin")
        if k is None:
            continue
        with_kotlin += 1
        kr = r.get("kotlin_reason")
        if k != v:
            diffs.append((r, v, why))
            if k == "ACCEPT" and v == "REJECT":
                bugs.append((r, v, why))
        elif v == "REJECT" and kr != why:
            reason_diffs.append((r, v, why))
    print(f"{path}: {len(rows)} lines")
    for s in sorted(per_surface):
        c = per_surface[s]
        print(f"  {s}: ACCEPT {c['ACCEPT']}  UNCHANGED {c['UNCHANGED']}  REJECT {c['REJECT']}")
        for why, n in reasons[s].most_common():
            print(f"      {why}: {n}")
    if with_kotlin:
        print(f"agreement with Kotlin on {with_kotlin} lines: {len(diffs)} verdict differences, "
              f"{len(reason_diffs)} reason-code differences on REJECT/REJECT lines")
        for r, v, why in diffs[:30]:
            print(f"--- DISAGREE [{r['surface']}] kotlin={_fmt(r['kotlin'], r.get('kotlin_reason'))} "
                  f"python={_fmt(v, why)}\n    original : {r['original']}\n    candidate: {r['candidate']}")
        if reason_diffs:
            print(f"reason-code differences (first 15 of {len(reason_diffs)}):")
            for r, v, why in reason_diffs[:15]:
                print(f"--- [{r['surface']}] kotlin={r.get('kotlin_reason')} python={why}"
                      f"  ({r.get('mutation', '')})\n    original : {r['original']}\n    candidate: {r['candidate']}")
    if bugs:
        print(f"CHECKER BUG, STOP: {len(bugs)} line(s) Kotlin ACCEPTed that Python REJECTs")
    return 1 if (diffs or bugs) else 0


def cmd_mutate(path: str) -> int:
    rows = _read_jsonl(path)
    applied: collections.Counter = collections.Counter()
    rejected: collections.Counter = collections.Counter()
    unexpected: collections.Counter = collections.Counter()
    misses: list[tuple] = []
    unexpected_ex: list[tuple] = []
    for r in rows:
        text, surface = r["text"], r["surface"]
        v, why = check(text, text, surface)
        applied["identity (UNCHANGED)"] += 1
        if v == "UNCHANGED":
            rejected["identity (UNCHANGED)"] += 1
        else:
            misses.append(("identity", surface, text, text, _fmt(v, why)))
        for name, cand, expected in mutations(text, surface):
            key = name.split(" (")[0] if name.startswith("term weakened") else name
            applied[key] += 1
            v, why = check(text, cand, surface)
            if v == "REJECT":
                rejected[key] += 1
                if why not in expected:
                    unexpected[key] += 1
                    unexpected_ex.append((name, surface, text, cand, why))
            else:
                misses.append((name, surface, text, cand, _fmt(v, why)))
    print(f"{path}: {len(rows)} texts")
    for key in sorted(applied):
        print(f"  {key}: applied {applied[key]}, as expected {rejected[key]}"
              + (f", reason outside the usual set {unexpected[key]}" if unexpected[key] else ""))
    if unexpected_ex:
        print(f"(note) {len(unexpected_ex)} mutation(s) rejected with a reason outside the usual set; first 10:")
        for name, surface, text, cand, why in unexpected_ex[:10]:
            print(f"    {name} [{surface}] -> {why}\n      text: {text}\n      cand: {cand}")
    if misses:
        print(f"MISSES: {len(misses)}")
        for name, surface, text, cand, got in misses[:40]:
            print(f"--- {name} [{surface}] got {got}\n    text: {text}\n    cand: {cand}")
        return 1
    print("no misses")
    return 0


def main(argv: list[str]) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    if len(argv) != 2 or argv[0] not in ("rephrase", "mutate-rephrase"):
        print("usage: rephrase_check.py rephrase <file.jsonl> | mutate-rephrase <corpus.jsonl>", file=sys.stderr)
        return 2
    return cmd_rephrase(argv[1]) if argv[0] == "rephrase" else cmd_mutate(argv[1])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
