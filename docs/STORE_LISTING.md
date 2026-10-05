# Store listing copy

Draft copy for the app store listing. Written to stay on the right side of the Chess.com
trademark line (see `PUBLISHING.md` §2) — it describes compatibility factually and never implies
affiliation.

---

## App name (30 char limit on Play)

**Chess Analyzer** *(15)*

Alternatives if taken: `Chess Game Review` (17), `PGN Chess Analyzer` (18), `Post Game Chess` (15).

Do **not** use: anything containing "Chess.com", or a name that reads as an official companion app.

## Short description (80 char limit)

> Analyse your chess games with Stockfish. Find the tactics you missed.

*(69 chars)*

## Full description

> **See exactly where your games were won and lost.**
>
> Import a game from any chess site or app and get a full engine review on your phone — no
> account, no upload, no waiting on a server.
>
> **Every move, graded**
> Each move is rated from Brilliant to Blunder, with the reason spelled out in plain language and
> the engine's better idea shown on the board.
>
> **The tactics you found — and the ones you missed**
> The app names the motifs on both sides of the board: forks, pins, skewers, discovered attacks,
> deflections, back-rank mates and more. When you miss one, tap "Show me" and play through exactly
> how it would have gone.
>
> **A game report that tells you something**
> Accuracy for both players, an estimated performance rating, a breakdown of every move type, an
> evaluation graph, and the moments the game actually turned on.
>
> **Get your games in, easily**
> Share a game straight from your chess app, open a PGN file, or paste the moves. Multi-game
> files, custom starting positions and clock times are all handled.
>
> **Runs on your device**
> Analysis uses Stockfish 19 running locally, and the narration voice runs on your phone too. Your games
> are never uploaded, and the app has no network permission at all. No sign-up, no ads, no tracking.
>
> The app is large (about 365 MB) because the engine's neural network and the narration voice are built
> in, so it works fully offline from the first launch. The first analysis takes a few extra seconds to set
> itself up.
>
> Analysis powered by Stockfish (GPLv3) — stockfishchess.org
> Opening data from the lichess-org/chess-openings project (CC0).
> Narration voice: sherpa-onnx and Kokoro-82M (Apache 2.0), with espeak-ng pronunciation data (GPL v3 or later).
> Source code: <ADD YOUR REPO URL>
>
> Not affiliated with, endorsed by, or connected to Chess.com or Lichess. PGN files exported from
> those sites are supported because PGN is an open standard.

## Category and tags

- Category: **Games → Board**, or **Tools** if you prefer to avoid the Games review queue.
  (Board is the better discovery fit.)
- Tags: chess, analysis, PGN, Stockfish, game review, tactics, training.
- Content rating: **Everyone**. No user-generated content, no ads, no purchases.

## Screenshots to capture (minimum 2, ideally 5)

1. Review screen mid-game showing a Blunder badge and its commentary
2. The missed-tactic "Show me" walkthrough with the arrow drawn
3. Game report — accuracy, estimated rating, move breakdown
4. Evaluation graph across the game
5. Import screen showing the share-from-another-app flow

Do not include any Chess.com UI, logo, or branded screenshot in these.

## What reviewers will look at

- **Size.** About 365 MB, stated in the description above. It is a single APK; on Play it would need Play
  Asset Delivery (see `PUBLISHING.md` §3).
- **No network permission.** The manifest declares no `INTERNET` and no `ACCESS_NETWORK_STATE`; nothing is
  downloaded (the Google Cloud voice option was removed in Round 13, and the net and voice are bundled).
- **Data safety form.** Answers are in `PUBLISHING.md` §5 — no collection, no sharing, network use none.
- **GPL compliance.** Have the source URL live before submitting, and put it in both the listing
  and the in-app About screen.
