# Famous games (G1)

A built-in, offline library of 91 famous games, reviewed through the normal analysis flow. Owner request
(Round 14 queue, G1): "A nice feature would be the option to download famous games from public repos." The
accepted plan has three parts:

1. **Built-in library** (this round): about 100 classic games inside the APK, bare moves plus factual headers,
   descriptions in our own words. Done: 91 games, 60 KB of PGN plus a 20 KB index.
2. **Opening a PGN from storage**: already in the app since R5 (Home, "Open a game file"). Verified in G1, nothing
   added (section 6).
3. **Optional downloadable collections** from our own GitHub release: designed below as G1b (section 7), not built.

## 1. What ships

| File | What it holds |
|---|---|
| `app/src/main/assets/famous_games.pgn` | All games, in index order. Tags: `Event`, `Site`, `Date`, `Round`, `White`, `Black`, `Result`, and `ECO` (from our CC0 opening book, `openings.tsv`: the deepest book position the game passes through). Bare SAN moves, a move number before every White move, the result at the end. No comments, NAGs, variations or `!`/`?` glyphs. |
| `app/src/main/assets/famous_games_index.tsv` | One row per game: `id, era, title, white, black, year, result, description`. The list is drawn from this file alone; the PGN text of one game is only read when the user reviews it. |
| `docs/famous_games_sources.tsv` | Per game: the Wikipedia article the moves were taken from, the second source compared at curation time, how they compared, its ply count and final position. |
| `scripts/verify_famous_games.py` | The independent check with python-chess (section 3). Exit code 1 on any bad game. |
| `scripts/famous_games/` | The curation tools that produced the three files above (section 4), kept so the library can be rebuilt or extended. |

Groups (`era`): Romantic era (before 1880, 8 games), Classical era (1880-1914, 18), Between the wars (1915-1945, 11),
Post-war era (1946-1984, 17), Karpov and Kasparov era (1985-2005, 10), Modern era (2006 to today, 22), Humans against
computers (5). 12 games end in checkmate on the board; the others end where the loser resigned (or, for
Steinitz - von Bardeleben, left the hall), or in an agreed draw.

**Dates.** Year only (`1851.??.??` style) unless the source article states the day. The Polish Immortal's year is
disputed (1928, 1929 or 1930 in different sources); the index shows 1930 and the description says so.

## 2. Licensing

- **Game scores are facts.** A record of the moves actually played in a game is not a creative work; we took
  only the moves (and the factual headers: players, event, place, date, result) from the cited articles.
  No annotation, commentary, diagram caption or other text from any source is in the app.
- **The titles and descriptions are ours.** Each description is one factual sentence (who played, when, where,
  and the name the game is known by or why it is remembered, in neutral terms). They make no claim about the
  chess the app could not check; `verify_famous_games.py` rejects any description with move notation in it.
- **Sources** are Wikipedia articles (CC BY-SA text; we copy no text, only the move lists, which are facts).
  chessgames.com and other sites with restrictive terms were not used: Wikipedia's reference links to
  chessgames.com were never followed.
- **ECO codes** come from the app's own opening book (lichess-org/chess-openings, CC0).
- **About** says so, under "Licenses & attribution", "Famous games" (`about_license_famous_*`).

## 3. Verification

Two independent checks, both run on every build or by hand, and both fail on a single bad game:

**`python scripts/verify_famous_games.py`** (python-chess 1.11.2):
- every game parses without errors from the standard start; every move legal; the SAN written is exactly
  python-chess's own SAN (so `+` and `#` mark checks and mates, and nothing else is attached to a move);
- move numbers in sequence, no comments, NAGs, variations or glyphs; only the factual tags; PGN dates; ECO form;
- the `Result` tag equals the movetext's result, and fits the final position: a checkmate is won by the side
  that gave it, a stalemate is a draw;
- the index agrees with the PGN (White, Black, Result, the Date's year), in order; ids, titles and move
  sequences are unique; 80-120 games;
- every game has a Wikipedia source URL; where a second source was compared, its ply count and final position
  must match (`same-moves`, `same-position`), or, when it stops earlier (`prefix`), the library's game must pass
  through the second source's last position at that ply.

Result on the committed files: `OK: 91 games verified with python-chess 1.11.2; second sources: none 20,
prefix 7, same-moves 61, same-position 3; 12 end in checkmate`. Self-check: a wrong result on a mate, an illegal
move, a `!` on a move and a `{comment}` each make it exit 1 (`scripts/famous_games/selftest_verifier.py`).

**Host tests, with our own code** (`:app` unit tests, `FamousGamesAssetTest`): every game of the real assets is
parsed by `:core`'s `PgnParser` (which replays every move with our move generator), with no comment, NAG or
variation; the tags are the factual set; the Result matches the movetext and the final position (checked with
`Position.isCheckmate`/`isStalemate`); every `+`/`#` matches a check/mate on our board; the index and the PGN
hold the same games in the same order (`FamousGamesLibrary.from`); no duplicates. `FamousGamesLogicTest` covers
the index reader, the PGN splitter, tags, the ply count, the search (accents folded) and the grouping.

**Second sources.** 71 of 91 games were compared with a second, independent article (another language edition
of Wikipedia, or a second English article):
- `same-moves` (61): identical move lists;
- `same-position` (3): the same number of moves and the same final position, the scores differing only in a
  transposition or in which of two equal pieces is named (1921 game 10, 1927 game 34, 2006 rapid game 4);
- `prefix` (7): the second article stops earlier, and our game passes through its last position.
The other 20 rest on one article. For each of them the end was read in the article itself (the resignation,
the mate or the result is stated right after the last move), the moves replay legally, and the length agrees
with the game's known length where the article gives it. Known disagreements, all resolved in favour of the
majority or the fuller source: Lasker - Bauer 1889 (de.wikipedia transposes moves 6 and 7; en and ca agree),
the Polish Immortal (de writes 16...Ngxe5+, en 16...Ndxe5+: the same position), Bogoljubov - Alekhine 1922
(fr.wikipedia agrees for 80 plies, then breaks off with a move that does not fit; de gives the full finish),
Tal - Botvinnik 1960 game 1 (en.wikipedia's score stops at move 29; de and hu give all 32 moves).

## 4. How the library was built

`scripts/famous_games/` (Python 3 + python-chess; run from that directory; the Wikipedia pages are cached in the
system temp directory):

1. `wk.py` fetches an article's wikitext through the public `action=raw` endpoint (with a descriptive
   User-Agent) and caches it.
2. `extract.py` takes every move sequence out of the wikitext: templates, references and links are stripped,
   then tokens are replayed with python-chess. Only bold text and definition terms are read first (Wikipedia
   prints the game's own moves in bold and variations in plain text), then the whole text; piece letters of
   other languages (de, ru, es, fr, nl, pl, cs, hu, ...) are mapped to English. A move is taken only right after
   its own move number (White) or right after White's move / its own "N..." (Black), so variations in the
   commentary do not leak in. Whatever comes out is a legal game prefix.
3. `survey.py` / `crosscheck.py` run that over the chess-game categories of seven Wikipedias, the World
   Championship articles (per game section) and every interlanguage sister page (about 1,300 pages), and pool
   the sequences.
4. `resolve.py` picks a game's primary sequence (article, section, first moves) and finds the best second
   source in the pool: identical moves, same final position, or a strict prefix; it also prints conflicts.
5. `games_list.py` is the curated list: the source of each game, the factual headers and our title and
   description. `curate.py` writes the PGN, the index and the sources file, and refuses a mate with the wrong
   result or a source whose result token disagrees.

Adding a game: add an entry to `games_list.py`, run `python curate.py <repo root>`, then
`python scripts/verify_famous_games.py` and `./gradlew :app:testDebugUnitTest`.

## 5. The app

- **Home** shows a "Famous games" card under the start card ("Classic games from chess history, ready to
  review."), the whole card one 56 dp button with a book icon and an auto-mirrored chevron.
- **Famous games** (`FamousGamesScreen`, route `famous_games`): `AppBarTitle` with Back; a search field ("Find a
  game", hint "Player, year or name"; every word must match the title, a player or the year, accents ignored,
  so "reti" finds Réti; a clear button); the games grouped by era under headings, each row the title, "White vs
  Black" (left to right in any layout direction) and "1851 · 1-0" (LRM-protected). No match says so in a polite
  live region. The list scrolls in portrait and landscape; no text has `maxLines`.
- **A game's sheet**: the title (heading), the players, event and place, "year · result · N moves", our
  description, and one button, **Review this game**. It hands the game's PGN text to the same `startAnalysis`
  a shared game goes through: analysed at once when the engine net is installed, otherwise kept in
  `setup_waiting_game.json` and the Setup screen opened ("Your game is kept ..."), exactly as in D2c. Back from
  Analysing or from the Summary returns to the library.
- The library is read from the APK's assets once per process (`FamousGamesStore`, off the main thread). No
  network: nothing in it can open a connection (`NetworkCallSitesTest` still passes).
- **Reference (owner rule, chess.com as design reference):** chess.com's master-games library: a searchable list
  of games by player and year, each row the players, the year and the result, and one primary action that opens
  the game for analysis. We took that information hierarchy and the single action; grouping by era instead of
  filters is ours, for simplicity. Pattern only: no assets, wording or data. Recorded in RUN_LOG "G1".

## 6. Opening a PGN from storage (verified, unchanged)

Home's "Open a game file" (and "Open file" once games exist) launches the system document picker
(`ActivityResultContracts.OpenDocument`, MIME types `application/x-chess-pgn`, `application/vnd.chess-pgn`,
`text/x-chess-pgn`, `text/plain`, `application/octet-stream`). The chosen file is read as UTF-8
(`readTextFromUri`) and goes through the same `startAnalysis` as a share; an unreadable or empty file shows "That
doesn't look like a chess game ..." on Home. A file holding several games analyses the first
(`AnalysisService`: `otherGamesInFile`). Opening a `.pgn` from a file manager (`ACTION_VIEW`) and sharing a file
(`ACTION_SEND` with `EXTRA_STREAM`) use the same reader. Nothing was needed for G1.

## 7. G1b (future, not built): optional downloadable collections

Goal: more games (themed collections: a player's best games, one world championship match complete, endgame
classics) without growing the APK, under the same rules as the models: nothing is fetched unless the user taps.

- **Where:** our own GitHub repository's releases (`palayax/Chess`), a rolling tag `collections`, one `.pgn`
  per collection plus an index (`collections.json`: id, title, description in our words, game count, size,
  SHA-256, min app version) and its detached signature `collections.json.sig`, signed with the same P-256
  manifest key as `models.json` (D2e; the public key is already in the app).
- **Download:** only through `ModelDownloader` (the one class allowed to open a connection; `NetworkCallSitesTest`
  keeps it so), from a "More collections" row at the end of the Famous games list: tap -> fetch the signed index
  (two small requests, as "Check for updates") -> a sheet listing the collections with their sizes -> tap one
  -> download, verify size and SHA-256 against the signed index, store in `filesDir/collections/<id>.pgn`. No
  background fetch, no automatic refresh; metered-network warning as for the models.
- **Trust and content checks on the device:** the signature first; then every game is parsed with `PgnParser`
  and must pass the same rules as the built-in library (legal moves, no comments/NAGs/variations, factual tags,
  result consistent with a final mate) or the whole collection is refused. Descriptions come only from our signed
  index, never from PGN comments.
- **Publishing:** `scripts/famous_games/` builds a collection the same way as the built-in one;
  `scripts/verify_famous_games.py` gains a `--collection <pgn>` mode; `publish_models.sh --sign` signs the index.
- **UI:** downloaded collections appear as further groups in the same list, with a "Remove" action in the
  sheet's overflow; search covers them too. Storage shown in Settings next to the narration cache.
- **Open questions for the owner:** which collections first (suggestion: all decisive World Championship games,
  and the games of each world champion); whether to keep G1b at all, since "Open a game file" already covers
  users who have their own PGN files.

## 8. The games

Generated from the assets (moves = full moves; second source as in `docs/famous_games_sources.tsv`, with its language edition).

| # | Group | Title | White - Black | Year | Result | Moves | Second source |
|---|---|---|---|---|---|---|---|
| 1 | Romantic era | McDonnell vs La Bourdonnais, 1834 | Alexander McDonnell - Louis-Charles Mahé de La Bourdonnais | 1834 | 0-1 | 37 | same-moves (de) |
| 2 | Romantic era | The Immortal Game | Adolf Anderssen - Lionel Kieseritzky | 1851 | 1-0 | 23 | same-moves (de) |
| 3 | Romantic era | The Evergreen Game | Adolf Anderssen - Jean Dufresne | 1852 | 1-0 | 24 | same-moves (de) |
| 4 | Romantic era | Paulsen vs Morphy, New York 1857 | Louis Paulsen - Paul Morphy | 1857 | 0-1 | 28 | none |
| 5 | Romantic era | The Opera Game | Paul Morphy - Duke Karl of Brunswick and Count Isouard | 1858 | 1-0 | 17 | same-moves (de) |
| 6 | Romantic era | Morphy vs Anderssen, Paris 1858 | Paul Morphy - Adolf Anderssen | 1858 | 1-0 | 17 | none |
| 7 | Romantic era | Dubois vs Steinitz, London 1862 | Serafino Dubois - Wilhelm Steinitz | 1862 | 0-1 | 37 | none |
| 8 | Romantic era | The Immortal Draw | Carl Hamppe - Philipp Meitner | 1872 | 1/2-1/2 | 18 | same-moves (ru) |
| 9 | Classical era | Zukertort vs Blackburne, London 1883 | Johannes Zukertort - Joseph Henry Blackburne | 1883 | 1-0 | 33 | none |
| 10 | Classical era | The first world championship, game 1 | Johannes Zukertort - Wilhelm Steinitz | 1886 | 0-1 | 46 | same-moves (en) |
| 11 | Classical era | The first world championship, game 20 | Wilhelm Steinitz - Johannes Zukertort | 1886 | 1-0 | 19 | same-moves (en) |
| 12 | Classical era | Lasker vs Bauer, Amsterdam 1889 | Emanuel Lasker - Johann Bauer | 1889 | 1-0 | 38 | same-moves (ca) |
| 13 | Classical era | Tarrasch vs Marco, Dresden 1892 | Siegbert Tarrasch - Georg Marco | 1892 | 1-0 | 18 | same-moves (ca) |
| 14 | Classical era | Lasker takes the title, 1894 | Emanuel Lasker - Wilhelm Steinitz | 1894 | 1-0 | 52 | same-moves (en) |
| 15 | Classical era | Steinitz vs von Bardeleben, Hastings 1895 | Wilhelm Steinitz - Curt von Bardeleben | 1895 | 1-0 | 25 | same-moves (es) |
| 16 | Classical era | Pillsbury vs Lasker, Saint Petersburg 1896 | Harry Nelson Pillsbury - Emanuel Lasker | 1896 | 0-1 | 32 | prefix (fr) |
| 17 | Classical era | Pillsbury vs Lasker, Cambridge Springs 1904 | Harry Nelson Pillsbury - Emanuel Lasker | 1904 | 1-0 | 30 | none |
| 18 | Classical era | Lasker vs Napier, Cambridge Springs 1904 | Emanuel Lasker - William Ewart Napier | 1904 | 1-0 | 35 | same-moves (en) |
| 19 | Classical era | Rubinstein's Immortal | Georg Rotlewi - Akiba Rubinstein | 1907 | 0-1 | 25 | same-moves (de) |
| 20 | Classical era | Lasker vs Schlechter, 1910, game 10 | Emanuel Lasker - Carl Schlechter | 1910 | 1-0 | 71 | none |
| 21 | Classical era | Réti vs Tartakower, Vienna 1910 | Richard Réti - Savielly Tartakower | 1910 | 1-0 | 11 | none |
| 22 | Classical era | Roesch vs Schlage, Hamburg 1910 | Roesch - Willi Schlage | 1910 | 0-1 | 15 | same-moves (de) |
| 23 | Classical era | Capablanca vs Bernstein, San Sebastián 1911 | José Raúl Capablanca - Ossip Bernstein | 1911 | 1-0 | 34 | none |
| 24 | Classical era | The Gold Coins Game | Stepan Levitsky - Frank Marshall | 1912 | 0-1 | 23 | same-moves (it) |
| 25 | Classical era | Edward Lasker vs Thomas, London 1912 | Edward Lasker - George Alan Thomas | 1912 | 1-0 | 18 | same-moves (de) |
| 26 | Classical era | Lasker vs Capablanca, Saint Petersburg 1914 | Emanuel Lasker - José Raúl Capablanca | 1914 | 1-0 | 42 | none |
| 27 | Between the wars | Capablanca's title match, game 10 | Emanuel Lasker - José Raúl Capablanca | 1921 | 0-1 | 68 | same-position (en) |
| 28 | Between the wars | Bogoljubov vs Alekhine, Hastings 1922 | Efim Bogoljubov - Alexander Alekhine | 1922 | 0-1 | 53 | none |
| 29 | Between the wars | The Immortal Zugzwang Game | Friedrich Sämisch - Aron Nimzowitsch | 1923 | 0-1 | 25 | same-moves (de) |
| 30 | Between the wars | Réti vs Alekhine, Baden-Baden 1925 | Richard Réti - Alexander Alekhine | 1925 | 0-1 | 40 | none |
| 31 | Between the wars | Alekhine takes the title, 1927 | Alexander Alekhine - José Raúl Capablanca | 1927 | 1-0 | 82 | same-position (hu) |
| 32 | Between the wars | The Polish Immortal | Glucksberg - Miguel Najdorf | 1930 | 0-1 | 22 | same-moves (es) |
| 33 | Between the wars | Sultan Khan vs Capablanca, Hastings 1930 | Mir Sultan Khan - José Raúl Capablanca | 1930 | 1-0 | 65 | same-moves (cs) |
| 34 | Between the wars | The Peruvian Immortal | Esteban Canal - NN | 1934 | 1-0 | 14 | same-moves (de) |
| 35 | Between the wars | The Pearl of Zandvoort | Max Euwe - Alexander Alekhine | 1935 | 1-0 | 47 | same-moves (en) |
| 36 | Between the wars | Botvinnik vs Capablanca, AVRO 1938 | Mikhail Botvinnik - José Raúl Capablanca | 1938 | 1-0 | 41 | same-moves (fr) |
| 37 | Between the wars | The Uruguayan Immortal | B. Molinari - Luis Roux Cabral | 1943 | 0-1 | 33 | same-moves (de) |
| 38 | Post-war era | Botvinnik vs Bronstein, 1951, game 23 | Mikhail Botvinnik - David Bronstein | 1951 | 1-0 | 57 | same-moves (hu) |
| 39 | Post-war era | Botvinnik vs Smyslov, 1954, game 14 | Mikhail Botvinnik - Vasily Smyslov | 1954 | 1-0 | 41 | none |
| 40 | Post-war era | The Game of the Century | Donald Byrne - Robert James Fischer | 1956 | 0-1 | 41 | same-moves (de) |
| 41 | Post-war era | Tal vs Botvinnik, 1960, game 1 | Mikhail Tal - Mikhail Botvinnik | 1960 | 1-0 | 32 | same-moves (hu) |
| 42 | Post-war era | Botvinnik vs Tal, 1960, game 6 | Mikhail Botvinnik - Mikhail Tal | 1960 | 0-1 | 47 | prefix (hu) |
| 43 | Post-war era | Botvinnik vs Tal, 1961, game 21 | Mikhail Botvinnik - Mikhail Tal | 1961 | 1-0 | 33 | prefix (ru) |
| 44 | Post-war era | Petrosian vs Botvinnik, 1963, game 19 | Tigran Petrosian - Mikhail Botvinnik | 1963 | 1-0 | 66 | same-moves (hu) |
| 45 | Post-war era | Petrosian vs Spassky, 1966, game 10 | Tigran Petrosian - Boris Spassky | 1966 | 1-0 | 30 | same-moves (hu) |
| 46 | Post-war era | Spassky vs Petrosian, 1969, game 19 | Boris Spassky - Tigran Petrosian | 1969 | 1-0 | 24 | same-moves (hu) |
| 47 | Post-war era | Spassky vs Bronstein, Leningrad 1960 | Boris Spassky - David Bronstein | 1960 | 1-0 | 23 | none |
| 48 | Post-war era | Sundin vs Andersson, correspondence 1964 | Arvid Sundin - Erik Andersson | 1964 | 1-0 | 31 | none |
| 49 | Post-war era | Larsen vs Spassky, Belgrade 1970 | Bent Larsen - Boris Spassky | 1970 | 0-1 | 17 | none |
| 50 | Post-war era | Fischer vs Spassky, 1972, game 6 | Robert James Fischer - Boris Spassky | 1972 | 1-0 | 41 | same-moves (de) |
| 51 | Post-war era | Spassky vs Fischer, 1972, game 13 | Boris Spassky - Robert James Fischer | 1972 | 0-1 | 74 | same-moves (de) |
| 52 | Post-war era | Bagirov vs Gufeld, Kirovabad 1973 | Vladimir Bagirov - Eduard Gufeld | 1973 | 0-1 | 36 | prefix (fr) |
| 53 | Post-war era | Karpov vs Korchnoi, 1978, game 32 | Anatoly Karpov - Viktor Korchnoi | 1978 | 1-0 | 41 | same-moves (hu) |
| 54 | Post-war era | Kasparov vs Karpov, 1984, game 32 | Garry Kasparov - Anatoly Karpov | 1984 | 1-0 | 41 | none |
| 55 | Karpov and Kasparov era | Karpov vs Kasparov, 1985, game 16 | Anatoly Karpov - Garry Kasparov | 1985 | 0-1 | 40 | same-moves (en) |
| 56 | Karpov and Kasparov era | Kasparov takes the title, 1985 | Anatoly Karpov - Garry Kasparov | 1985 | 0-1 | 42 | none |
| 57 | Karpov and Kasparov era | Kasparov vs Karpov, 1986, game 16 | Garry Kasparov - Anatoly Karpov | 1986 | 1-0 | 41 | same-moves (en) |
| 58 | Karpov and Kasparov era | Kasparov vs Karpov, 1986, game 22 | Garry Kasparov - Anatoly Karpov | 1986 | 1-0 | 46 | prefix (de) |
| 59 | Karpov and Kasparov era | Kasparov vs Karpov, 1990, game 20 | Garry Kasparov - Anatoly Karpov | 1990 | 1-0 | 41 | same-moves (en) |
| 60 | Karpov and Kasparov era | Kasparov vs Karpov, 1987, game 24 | Garry Kasparov - Anatoly Karpov | 1987 | 1-0 | 64 | prefix (ru) |
| 61 | Karpov and Kasparov era | Ivanchuk vs Yusupov, Brussels 1991 | Vassily Ivanchuk - Artur Yusupov | 1991 | 0-1 | 39 | none |
| 62 | Karpov and Kasparov era | Kasparov's Immortal | Garry Kasparov - Veselin Topalov | 1999 | 1-0 | 44 | same-moves (de) |
| 63 | Karpov and Kasparov era | Kasparov versus the World | Garry Kasparov - The World | 1999 | 1-0 | 62 | same-moves (de) |
| 64 | Karpov and Kasparov era | Anand vs Radjabov, Dortmund 2003 | Viswanathan Anand - Teimour Radjabov | 2003 | 0-1 | 39 | none |
| 65 | Modern era | Topalov vs Kramnik, 2006, game 2 | Veselin Topalov - Vladimir Kramnik | 2006 | 0-1 | 63 | same-moves (en) |
| 66 | Modern era | Kramnik vs Topalov, 2006, rapid tiebreak | Vladimir Kramnik - Veselin Topalov | 2006 | 1-0 | 45 | same-position (en) |
| 67 | Modern era | Anand vs Kramnik, 2008, game 6 | Viswanathan Anand - Vladimir Kramnik | 2008 | 1-0 | 47 | same-moves (ca) |
| 68 | Modern era | Topalov vs Anand, 2010, game 12 | Veselin Topalov - Viswanathan Anand | 2010 | 0-1 | 56 | same-moves (bg) |
| 69 | Modern era | Anand vs Gelfand, 2012, game 8 | Viswanathan Anand - Boris Gelfand | 2012 | 1-0 | 17 | same-moves (ca) |
| 70 | Modern era | Anand's Immortal | Levon Aronian - Viswanathan Anand | 2013 | 0-1 | 23 | none |
| 71 | Modern era | Carlsen vs Anand, 2013, game 5 | Magnus Carlsen - Viswanathan Anand | 2013 | 1-0 | 58 | same-moves (de) |
| 72 | Modern era | Anand vs Carlsen, 2013, game 6 | Viswanathan Anand - Magnus Carlsen | 2013 | 0-1 | 67 | same-moves (ca) |
| 73 | Modern era | Anand vs Carlsen, 2013, game 9 | Viswanathan Anand - Magnus Carlsen | 2013 | 0-1 | 28 | same-moves (ca) |
| 74 | Modern era | Carlsen vs Anand, 2014, game 11 | Magnus Carlsen - Viswanathan Anand | 2014 | 1-0 | 45 | same-moves (de) |
| 75 | Modern era | Carlsen vs Karjakin, 2016, game 10 | Magnus Carlsen - Sergey Karjakin | 2016 | 1-0 | 75 | same-moves (de) |
| 76 | Modern era | Carlsen vs Karjakin, 2016, rapid tiebreak | Magnus Carlsen - Sergey Karjakin | 2016 | 1-0 | 51 | prefix (en) |
| 77 | Modern era | Carlsen vs Caruana, 2018, rapid tiebreak | Magnus Carlsen - Fabiano Caruana | 2018 | 1-0 | 51 | same-moves (en) |
| 78 | Modern era | Carlsen vs Nepomniachtchi, 2021, game 6 | Magnus Carlsen - Ian Nepomniachtchi | 2021 | 1-0 | 136 | same-moves (de) |
| 79 | Modern era | Carlsen vs Nepomniachtchi, 2021, game 8 | Magnus Carlsen - Ian Nepomniachtchi | 2021 | 1-0 | 46 | same-moves (en) |
| 80 | Modern era | Ding vs Nepomniachtchi, 2023, game 4 | Ding Liren - Ian Nepomniachtchi | 2023 | 1-0 | 47 | same-moves (en) |
| 81 | Modern era | Ding vs Nepomniachtchi, 2023, game 12 | Ding Liren - Ian Nepomniachtchi | 2023 | 1-0 | 38 | same-moves (en) |
| 82 | Modern era | Nepomniachtchi vs Ding, 2023, rapid tiebreak | Ian Nepomniachtchi - Ding Liren | 2023 | 0-1 | 68 | same-moves (en) |
| 83 | Modern era | Gukesh vs Ding, 2024, game 1 | Gukesh Dommaraju - Ding Liren | 2024 | 0-1 | 42 | same-moves (en) |
| 84 | Modern era | Gukesh vs Ding, 2024, game 3 | Gukesh Dommaraju - Ding Liren | 2024 | 1-0 | 37 | same-moves (en) |
| 85 | Modern era | Gukesh vs Ding, 2024, game 11 | Gukesh Dommaraju - Ding Liren | 2024 | 1-0 | 29 | same-moves (en) |
| 86 | Modern era | Ding vs Gukesh, 2024, game 14 | Ding Liren - Gukesh Dommaraju | 2024 | 0-1 | 58 | same-moves (en) |
| 87 | Humans against computers | Deep Blue vs Kasparov, 1996, game 1 | Deep Blue - Garry Kasparov | 1996 | 1-0 | 37 | same-moves (de) |
| 88 | Humans against computers | Kasparov vs Deep Blue, 1996, game 2 | Garry Kasparov - Deep Blue | 1996 | 1-0 | 73 | same-moves (ca) |
| 89 | Humans against computers | Kasparov vs Deep Blue, 1996, game 6 | Garry Kasparov - Deep Blue | 1996 | 1-0 | 43 | same-moves (ca) |
| 90 | Humans against computers | Kasparov vs Deep Blue, 1997, game 1 | Garry Kasparov - Deep Blue | 1997 | 1-0 | 45 | same-moves (ca) |
| 91 | Humans against computers | Deep Blue vs Kasparov, 1997, game 6 | Deep Blue - Garry Kasparov | 1997 | 1-0 | 19 | same-moves (ru) |
