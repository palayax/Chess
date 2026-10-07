# Part of the famous-games curation tools (G1, docs/FAMOUS_GAMES.md section 4).
"""Extract main-line move sequences from Wikipedia wikitext.

Only the moves are taken (facts); commentary is dropped. Every sequence is replayed with python-chess,
so whatever comes out is a legal game prefix. Numbering discipline keeps variations out: a move is only
accepted right after its own move number (White) or right after White's move / its own "N..." (Black).
"""
import re
import chess

PIECE_MAPS = {
    "en": {},
    "de": {"K": "K", "D": "Q", "T": "R", "L": "B", "S": "N"},
    "nl": {"K": "K", "D": "Q", "T": "R", "L": "B", "P": "N"},
    "es": {"R": "K", "D": "Q", "T": "R", "A": "B", "C": "N"},
    "it": {"R": "K", "D": "Q", "T": "R", "A": "B", "C": "N"},
    "fr": {"R": "K", "D": "Q", "T": "R", "F": "B", "C": "N"},
    "pl": {"K": "K", "H": "Q", "W": "R", "G": "B", "S": "N"},
    "ru": {"Кр": "K", "Ф": "Q", "Л": "R", "С": "B", "К": "N"},
    "uk": {"Кр": "K", "Ф": "Q", "Т": "R", "С": "B", "К": "N"},
    "bg": {"Цр": "K", "Ц": "K", "Д": "Q", "Т": "R", "О": "B", "К": "N"},
    "cs": {"K": "K", "D": "Q", "V": "R", "S": "B", "J": "N"},
    "sk": {"K": "K", "D": "Q", "V": "R", "S": "B", "J": "N"},
    "hu": {"K": "K", "V": "Q", "B": "R", "F": "B", "H": "N"},
    "sv": {"K": "K", "D": "Q", "T": "R", "L": "B", "S": "N"},
    "da": {"K": "K", "D": "Q", "T": "R", "L": "B", "S": "N"},
    "no": {"K": "K", "D": "Q", "T": "R", "L": "B", "S": "N"},
    "fi": {"K": "K", "D": "Q", "T": "R", "L": "B", "R": "N"},
    "pt": {"R": "K", "D": "Q", "T": "R", "B": "B", "C": "N"},
    "ca": {"R": "K", "D": "Q", "T": "R", "A": "B", "C": "N"},
    "ro": {"R": "K", "D": "Q", "T": "R", "N": "B", "C": "N"},
    "eo": {"R": "K", "D": "Q", "T": "R", "K": "N"},
}
FIGURINES = {"♔": "K", "♚": "K", "♕": "Q", "♛": "Q", "♖": "R", "♜": "R", "♗": "B", "♝": "B", "♘": "N", "♞": "N"}
RESULTS = {"1-0": "1-0", "0-1": "0-1", "1/2-1/2": "1/2-1/2", "½-½": "1/2-1/2", "1/2": "1/2-1/2", "½:½": "1/2-1/2",
           "1:0": "1-0", "0:1": "0-1", "½": "1/2-1/2"}


def strip_nested(text, open_s="{{", close_s="}}"):
    out = []
    depth = 0
    i = 0
    while i < len(text):
        if text.startswith(open_s, i):
            depth += 1
            i += len(open_s)
            continue
        if depth and text.startswith(close_s, i):
            depth -= 1
            i += len(close_s)
            if depth == 0:
                out.append(" ")
            continue
        if depth == 0:
            out.append(text[i])
        i += 1
    return "".join(out)


def unwrap_templates(text):
    text = re.sub(r"\{\{\s*(?:sfn|efn|refn|cite[^|}]*|harvnb|r)[^{}]*\}\}", " ", text, flags=re.I)
    text = re.sub(r"\{\{[^{}|]*\|", " ", text)
    text = text.replace("{{", " ").replace("}}", " ")
    text = re.sub(r"\|\s*\d+\s*=", " ", text)
    return text


UNWRAP = False


def clean(text):
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    text = re.sub(r"<ref[^>/]*/>", " ", text)
    text = re.sub(r"<ref[^>]*>.*?</ref>", " ", text, flags=re.S)
    text = unwrap_templates(text) if UNWRAP else strip_nested(text)
    # [[link|label]] -> label ; [[link]] -> link ; files dropped
    text = re.sub(r"\[\[(?:File|Datei|Image|Bild|Файл|Archivo|Fichier|Plik|Bestand):[^\]]*(?:\[\[[^\]]*\]\][^\]]*)*\]\]", " ", text)
    text = re.sub(r"\[\[[^\]|]*\|([^\]]*)\]\]", r"\1", text)
    text = re.sub(r"\[\[([^\]]*)\]\]", r"\1", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = text.replace("&nbsp;", " ").replace("&thinsp;", "")
    return text


def marked_only(text):
    """Bold runs and definition-list terms; segments separated by a marker."""
    parts = []
    for line in text.split("\n"):
        s = line.strip()
        if s.startswith(";"):
            parts.append(s.lstrip(";"))
            parts.append(" | ")
        for m in re.finditer(r"'''(.*?)'''", line):
            parts.append(m.group(1))
            parts.append(" | ")
    return " ".join(parts)


TOKEN_RE = re.compile(r"(\d+)\s*(\.\s*(?:\.\.|…|\.\s*\.)?|…)|([^\s]+)")


def tokens(text):
    text = text.replace("…", "...").replace("–", "-").replace("—", "-").replace("−", "-")
    text = re.sub(r"(\d+)\s*\.\.(?!\.)", lambda m: m.group(1) + "...", text)
    # glue "1.e4" -> "1. e4", "12...Nf6" -> "12... Nf6"
    text = re.sub(r"(\d+)\.(\.\.)?(?=[^\s\d.])", lambda m: m.group(0) + " ", text)
    text = re.sub(r"(\d+)\s*\.\s+\.\.\.", r"\1...", text)
    text = re.sub(r"(\d+)\s*\.\s*\.\s*\.", r"\1...", text)
    out = []
    for raw in text.split():
        m = re.fullmatch(r"(\d+)(\.+)", raw)
        if m:
            out.append(("num", int(m.group(1)), len(m.group(2)) >= 2))
            continue
        if raw == "...":
            out.append(("dots",))
            continue
        out.append(("w", raw))
    return out


def norm_san(tok, lang):
    t = tok.strip(",;:()[]\"'“”„")
    t = t.replace("e.p.", "")
    for k, v in FIGURINES.items():
        t = t.replace(k, v)
    t = re.sub(r"[!?⁈⁉‼⁇]+$", "", t)
    t = t.replace("×", "x").replace(":", "x")
    t = t.rstrip("+#‡†")
    t = t.replace("0-0-0", "O-O-O").replace("0-0", "O-O")
    if not t:
        return None
    pm = PIECE_MAPS.get(lang, {})
    for k in sorted(pm, key=len, reverse=True):
        if t.startswith(k):
            t = pm[k] + t[len(k):]
            break
    # promotion letter in local language
    m = re.fullmatch(r"(.*[a-h][18])=?(\w+)", t)
    if m and m.group(2) not in ("Q", "R", "B", "N") and pm:
        letter = pm.get(m.group(2))
        if letter:
            t = m.group(1) + "=" + letter
    elif m and not t.endswith(("=Q", "=R", "=B", "=N")) and m.group(2) in ("Q", "R", "B", "N"):
        t = m.group(1) + "=" + m.group(2)
    return t


def try_move(board, tok, lang):
    for lg in ((lang, "en") if lang != "en" else ("en",)):
        t = norm_san(tok, lg)
        if not t or not re.search(r"[a-h][1-8]|O-O", t):
            continue
        try:
            return board.parse_san(t)
        except Exception:
            continue
    return None


def is_result(tok):
    t = tok.strip(",;:().'\"").replace("–", "-").replace("—", "-")
    return RESULTS.get(t)


def sequences(text, lang, mode="marked", min_plies=10):
    text = clean(text)
    if mode == "marked":
        text = marked_only(text)
    toks = tokens(text)
    found = []
    board = None
    armed = False
    expect_black_after_dots = False
    result = None
    pos_start = 0

    def finish():
        if board is not None and len(board.move_stack) >= min_plies:
            found.append({"moves": [m for m in board.move_stack], "result": result, "start": pos_start,
                          "board": board.copy()})

    i = 0
    while i < len(toks):
        tk = toks[i]
        if tk[0] == "num":
            n, dots = tk[1], tk[2]
            if i + 1 < len(toks) and toks[i + 1][0] == "dots":
                dots = True
                i += 1
            if n == 1 and not dots:
                nxt = toks[i + 1] if i + 1 < len(toks) else None
                if nxt and nxt[0] == "w" and try_move(chess.Board(), nxt[1], lang):
                    if board is None or len(board.move_stack) > 1:
                        finish()
                        board = chess.Board()
                        result = None
                        pos_start = i
                        armed = True
                        i += 1
                        continue
            if board is not None:
                if n == board.fullmove_number and ((board.turn == chess.WHITE and not dots) or (board.turn == chess.BLACK and dots)):
                    armed = True
                else:
                    armed = False
            i += 1
            continue
        if tk[0] == "dots":
            i += 1
            continue
        word = tk[1]
        if board is not None and is_result(word) and armed:
            result = is_result(word)
            armed = False
            i += 1
            continue
        if board is not None and armed:
            mv = try_move(board, word, lang)
            if mv is not None:
                board.push(mv)
                armed = board.turn == chess.BLACK  # black may follow white directly
                i += 1
                continue
            # annotation tokens that do not break the chain
            if re.fullmatch(r"[!?⁈⁉‼+#=∞±∓⩲⩱]+|\(D\)|\|", word):
                if word == "|" and board.turn == chess.WHITE:
                    armed = False
                i += 1
                continue
            armed = False
        i += 1
    finish()
    return found


def to_san_line(moves):
    b = chess.Board()
    parts = []
    for mv in moves:
        if b.turn == chess.WHITE:
            parts.append(f"{b.fullmove_number}.")
        parts.append(b.san(mv))
        b.push(mv)
    return " ".join(parts)
