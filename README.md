# Palaya Chess

An Android app that takes a PGN file — from chess.com, Lichess, or anywhere else — and replays the
game with move-by-move analysis: chess.com-style move grades from Brilliant to Blunder, the tactics
each side found, the tactics each side missed, a playable walkthrough of what the missed tactic
would have looked like, and an estimated performance rating.

Analysis runs **entirely on the device** using [Stockfish](https://stockfishchess.org/) 19. There is
no account, no server, no telemetry.

---

## Status

Working, signed APK built and verified on-device. See `RUN_PLAN.md` for the task breakdown and
`RUN_LOG.md` for what has
been built and verified so far, including the defects found along the way.

---

## Getting a game in

Three ways:

1. **Share from another app.** The app registers for `ACTION_SEND` (text and PGN mime types), so it
   appears in the Android share sheet when you share a game from chess.com or a similar app.
2. **Open a `.pgn` file** from a file manager — the app registers for `ACTION_VIEW` on PGN mime types
   and on a `.pgn` path pattern.
3. **Pick a file or paste PGN text** from the import screen.

Multi-game PGN files, games starting from a custom FEN, clock annotations (`{[%clk 0:02:31.6]}`),
variations and NAGs are all handled.

---

## How the analysis works

The scoring model is documented in full in [`docs/ANALYSIS_SPEC.md`](docs/ANALYSIS_SPEC.md) — it is
the authoritative spec, not a summary. In short:

- Every ply is searched at the same depth with MultiPV, so evaluation deltas are meaningful rather
  than depth noise.
- Centipawn scores become win probabilities via a sigmoid; a move's cost is the win-probability it
  gives up.
- Move grades follow an ordered decision ladder (Forced → Book → Brilliant → Great → Miss → Best →
  … → Blunder), including a genuine sacrifice test for Brilliant based on Static Exchange
  Evaluation.
- Accuracy blends a volatility-weighted mean with a harmonic mean; the estimated rating is a
  piecewise-linear map from accuracy, and is labelled as an estimate everywhere it appears.
- Tactics are detected from attack maps and SEE, and raised to high confidence only when the engine's
  principal variation actually plays the follow-up.

Where these approximate chess.com's proprietary model, the spec says so explicitly.

---

## Building

Requirements:

- JDK 17
- Android SDK with platform 34, build-tools 34.0.0
- Android NDK `26.1.10909125` and CMake `3.22.1` (for compiling Stockfish)

```bash
# 1. Fetch the Stockfish source (pinned to tag sf_19). Not vendored in git.
scripts/fetch_stockfish.sh

# 2. Build
./gradlew :app:assembleDebug
```

The NNUE evaluation network is **not** bundled — it is ~79 MB compressed / 98.5 MB on disk, which
would dwarf the rest of the app. The app downloads it from the official Stockfish network endpoint
on first run and verifies it by SHA-256 (the filename encodes the first 12 hex digits of the file's
own hash). To run the on-device engine tests without downloading inside the test:

```bash
scripts/push_test_net.sh
./gradlew :engine:connectedDebugAndroidTest
```

### Why Stockfish is compiled from source

The official Stockfish Android release binary is a 100 MB non-PIE executable with the network
embedded. Shipping that is impractical (Play Store size limits, multiplied per ABI) and executing a
downloaded binary is blocked on Android 10+. Compiling from source with `NNUE_EMBEDDING_OFF` into a
JNI shared library gives ~1.6 MB per ABI instead, and lets the network update at runtime.

---

## Project layout

| Module | What it is |
|---|---|
| `:core` | Pure Kotlin/JVM. Board, legal move generation, FEN, SAN, PGN parsing, and the whole analysis model. No Android dependencies, so all of it is unit-testable on the host. Move generation is verified by perft against the five standard test positions — depth 5 on the start position and Position 3, depth 4 on Kiwipete, Position 4 and Position 5 (deeper runs on those are too slow for a unit test). |
| `:engine` | Android library. Stockfish compiled via CMake/NDK into `libstockfish.so`, a JNI bridge that pipes UCI over stdin/stdout, a coroutine-based Kotlin wrapper, and network download/verification. |
| `:app` | Jetpack Compose UI, navigation, PGN intake, and the analysis orchestration. |

---

## Licence

**GPLv3.** This app links Stockfish, which is GPLv3, so the whole work is GPLv3 and the corresponding
source must be offered to anyone who receives a binary. See [`docs/PUBLISHING.md`](docs/PUBLISHING.md)
for what that means in practice.

Third-party components:

- **Stockfish 19** — GPLv3. <https://stockfishchess.org/>
- **Opening book** — [lichess-org/chess-openings](https://github.com/lichess-org/chess-openings),
  CC0 public domain dedication.
- **Piece artwork** — the [Cburnett](https://commons.wikimedia.org/wiki/Category:SVG_chess_pieces)
  set from Wikimedia Commons, **CC BY-SA 3.0**, transcribed to vector paths and adapted for this
  app's renderer. See `app/src/main/assets/PIECES_LICENSE.txt`.
- **Classification badges** — original to this project.

Chess.com is used only as a *design reference*. This app is not affiliated with, endorsed by, or
connected to Chess.com, and contains none of its artwork, logos, or trademarks.
