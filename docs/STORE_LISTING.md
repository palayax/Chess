# Store listing copy

Draft copy for the Google Play listing (D2f; replaces the bundled-build draft, whose "about 365 MB" and "no
network permission" lines are no longer true). Our own words. It stays on the right side of the Chess.com
trademark line (`PUBLISHING.md` §2): it describes PGN compatibility factually and never implies
affiliation. Stockfish appears only as "powered by Stockfish" in the description, never in the title.

Character counts were measured with Python `len()` on the exact text between the quote marks (D2f).

---

## App name (Play limit: 30 characters)

**Palaya Chess: Game Review** *(25)*

Alternatives if the owner prefers: `Palaya Chess` (12), `Palaya Chess – Game Analysis` (28).

Do **not** use: "Chess.com", "Lichess" or "Stockfish" in the name, or a name that reads as an official
companion app of any chess site.

## Short description (Play limit: 80 characters)

> Review your chess games on your phone: every move graded, missed tactics shown.

*(79)*

## Full description (Play limit: 4000 characters)

> **Find out where your game was won and lost.**
>
> Palaya Chess reviews the games you play anywhere. Share a game from your chess app, open a PGN file or
> paste the moves, and get a full review on your phone. There is no account to create and nothing to
> upload.
>
> **Every move graded**
> Each move is rated from Brilliant to Blunder, with a short explanation in plain language and the better
> move shown on the board.
>
> **The tactics you found, and the ones you missed**
> The review names the ideas on both sides of the board: forks, pins, skewers, discovered attacks,
> deflections, back-rank mates and more. When you miss one, the walkthrough plays out how it would have
> gone, move by move.
>
> **Practise your own mistakes**
> Turn the positions you got wrong into puzzles and try them again until you find the right move.
>
> **A report that tells you something**
> Accuracy for both players, an estimated performance rating, a count of every kind of move, an evaluation
> graph and the moments the game turned on.
>
> **A narrated video of your game**
> Watch a spoken review in the app, or save it as an MP4 video to keep or share. The narration voice runs on
> your phone.
>
> **Private by design**
> The analysis is powered by Stockfish 19, running on your phone, not on a server. Your games stay on your
> device. No sign-up, no ads, no analytics, no tracking.
>
> **One-time setup download**
> To keep the app itself small, the chess engine's data and the narration voice (about 210 MB together) are
> downloaded once, the first time you open the app, and only when you tap Download. Wi-Fi is recommended;
> on mobile data the app asks first. After that the app works offline. It goes online again only if you tap
> "Check for updates" in Settings.
>
> **Getting your games in**
> Share a game straight from your chess app, open a .pgn file, or paste the moves. Files with several games,
> custom starting positions and clock times are supported.
>
> Analysis powered by Stockfish (GPLv3), stockfishchess.org.
> Opening names from the lichess-org/chess-openings project (CC0).
> Narration voice: sherpa-onnx and Kokoro-82M (Apache 2.0), with espeak-ng pronunciation data (GPL v3 or
> later). Piece artwork: Cburnett (CC BY-SA 3.0).
> Palaya Chess is free software under the GNU GPL v3. Source code: https://github.com/palayax/Chess
>
> Palaya Chess is not affiliated with, endorsed by or connected to Chess.com or Lichess. Games exported from
> those sites work because PGN is an open standard.

*(2,382 characters without the `>` and `**` markup, placeholder included; the markup is not part of the text Play receives:
bold does not render on Play, so paste the text without the asterisks.)*

## Category, tags, rating

- Category: **Board** (Games). **Tools** is the alternative if the owner wants to avoid the Games review
  queue; Board is the better fit for discovery.
- Tags: chess, analysis, PGN, game review, tactics, training.
- Content rating questionnaire: no user-generated content, no ads, no purchases, no sharing of location or
  personal data: expected rating **Everyone / PEGI 3**.
- Ads: **No**. In-app purchases: **No**.
- Privacy policy URL: https://palayax.github.io/Chess/privacy/

## Screenshots to capture (minimum 2, ideally 5; phone portrait)

1. The Summary of a reviewed game: accuracy, estimated rating, move counts.
2. The Board with a Blunder badge and its explanation.
3. The missed-tactic walkthrough with the arrow drawn.
4. Practise: a position from the user's own game.
5. The Setup screen ("Review games on your phone", both sizes stated) — it shows the one-time download
   honestly, which also helps the foreground-service review.

No chess.com UI, logo or branded screenshot. Use a public-domain game (the Immortal Game, the Opera Game)
with the real players' names.

## What reviewers will look at

- **Size and the first-run download.** The Play download is about 14.5 MB on a 64-bit phone (bundletool,
  `PUBLISHING.md` §3). The app then downloads about 201 MB of data on its first run, after a tap, and needs
  about 400 MB of free space while it unpacks (about 260 MB once done). The description says so.
- **Downloads data, not code.** The two files are an engine evaluation network and a voice model, read by
  code that ships in the app (`PUBLISHING.md` §3b, Device and Network Abuse).
- **Network permission.** `INTERNET` and `ACCESS_NETWORK_STATE` are declared for the user-started download
  and "Check for updates" only. The app makes no request on its own (measured: `NoNetworkAfterSetupTest`,
  `UpdateCheckNetworkTest`).
- **Foreground services.** `dataSync` for the setup download, `mediaProcessing` (dataSync on Android 10-14)
  for the video export; declarations in `PUBLISHING.md` §3.
- **Data safety form.** Answers in `PUBLISHING.md` §5: no data collected, none shared.
- **GPL compliance.** The source URL must be live before submitting, in the listing and in About.
