# Palaya Chess

An Android app that takes a PGN file — from chess.com, Lichess, or anywhere else — and replays the
game with move-by-move analysis: chess.com-style move grades from Brilliant to Blunder, the tactics
each side found, the tactics each side missed, a playable walkthrough of what the missed tactic
would have looked like, and an estimated performance rating.

Analysis runs **entirely on the device** using [Stockfish](https://stockfishchess.org/) 19, and the
narration voice runs on the device too. There is no account, no server of our own, no telemetry and no
ads. The installer is small (about 15 MB from Google Play on a 64-bit phone, 36 MB for the arm64 APK):
the engine's neural network (98.5 MB) and the narration voice (102.5 MB download, 158 MB unpacked) are
downloaded **once**, on the first-run Setup screen, only after the user taps Download, from this
project's GitHub release, and checked against hashes compiled into the app. After that the app works
offline and makes no network request on its own; Settings > Check for updates asks the server only when
tapped. Design: [`docs/MODEL_DOWNLOAD_DESIGN.md`](docs/MODEL_DOWNLOAD_DESIGN.md). Privacy:
[`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md).

---

## Status

Version 1.1 (versionCode 2): signed APKs and an App Bundle built and verified on emulators (API 34 and
36), including the update from the bundled 1.0 build. Not yet live: the GitHub repo and the model release
the app downloads from (owner steps in `HANDOFF.md` and `docs/PUBLISHING.md`). See `RUN_PLAN.md` for the
task breakdown and `RUN_LOG.md` for what has been built and verified, including the defects found along
the way.

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
- Android SDK with platform 36 (AGP 8.9.3 installs build-tools 35.0.0 itself)
- Android NDK `28.2.13676358` (r28c) and CMake `3.22.1` (for compiling Stockfish)

```bash
# 1. Fetch the Stockfish source (pinned to tag sf_19). Not vendored in git.
scripts/fetch_stockfish.sh

# 2. Build. The models are NOT needed for this: the build compiles their pins from vendor/models/MODELS.lock.
./gradlew :app:assembleDebug

# 3. Only for the instrumented tests, the local test server and publishing: fetch the two model files
#    (~201 MB) into the gitignored vendor/models/.
scripts/fetch_models.sh
```

The app downloads the two model files on first run from `<MODEL_BASE_URL><release.tag>/<file>`
(`app/build.gradle.kts`, `vendor/models/MODELS.lock`) and checks size and SHA-256 against the pins
before anything uses them. `scripts/fetch_models.sh` fetches the same files for developers: the Stockfish
NNUE network (98.5 MB, named by the first 12 hex digits of its own SHA-256) and the Kokoro voice, which
the app downloads as `kokoro-int8-en-v0_19.tar.gz` (the upstream `.tar.bz2` re-compressed with
`gzip -9 -n`, so the phone inflates it in about a second instead of running a slow bzip2 decoder).
`scripts/publish_models.sh` puts them on the GitHub release with a signed update manifest
(`docs/PUBLISHING.md` §4b). A debug build can be pointed at a local server instead:
`python scripts/model_test_server.py` and `-PpalayaModelBaseUrl=http://10.0.2.2:8787/`.

The instrumented tests carry the two files as assets of the TEST APK only (never the app);
`connectedDebugAndroidTest` takes 12-15 minutes per device for `:app`.

### Why Stockfish is compiled from source

The official Stockfish Android release binary is a 100 MB non-PIE executable with the network
embedded. Shipping that per ABI would triple the net, and executing a downloaded binary is blocked on
Android 10+. Compiling from source with `NNUE_EMBEDDING_OFF` into a JNI shared library gives ~1.6 MB per
ABI instead, with the network as a separate data file (downloaded once on first run, never code).

---

## Project layout

| Module | What it is |
|---|---|
| `:core` | Pure Kotlin/JVM. Board, legal move generation, FEN, SAN, PGN parsing, and the whole analysis model. No Android dependencies, so all of it is unit-testable on the host. Move generation is verified by perft against the five standard test positions — depth 5 on the start position and Position 3, depth 4 on Kiwipete, Position 4 and Position 5 (deeper runs on those are too slow for a unit test). |
| `:engine` | Android library. Stockfish compiled via CMake/NDK into `libstockfish.so`, a JNI bridge that pipes UCI over stdin/stdout, a coroutine-based Kotlin wrapper, and `NetStore` (the downloaded net in `filesDir/nets/`, verified by size and SHA-256 before the engine may load it). |
| `:app` | Jetpack Compose UI, navigation, PGN intake, the analysis orchestration, the Practise screens, the narrated video, and the first-run Setup download and Check for updates (`data/models/`). |

---

## Licence

**GPLv3.** This app links Stockfish, which is GPLv3, so the whole work is GPLv3 and the corresponding
source must be offered to anyone who receives a binary. See [`docs/PUBLISHING.md`](docs/PUBLISHING.md)
for what that means in practice.

Third-party components:

- **Stockfish 19** — GPLv3. <https://stockfishchess.org/> Its NNUE network was trained on data from
  the Leela Chess Zero project, made available under the Open Database License (per the Stockfish README).
- **sherpa-onnx** (voice inference) — Apache 2.0. <https://github.com/k2-fsa/sherpa-onnx>
- **Kokoro-82M** (narration voice model, downloaded once on first run) — Apache 2.0. <https://huggingface.co/hexgrad/Kokoro-82M>
- **espeak-ng pronunciation data** (inside the Kokoro archive, downloaded with it) — the espeak-ng project is
  "GPL version 3 or later" per its README; the archive ships no separate licence file for this data.
  <https://github.com/espeak-ng/espeak-ng>
- **Opening book** — [lichess-org/chess-openings](https://github.com/lichess-org/chess-openings),
  CC0 public domain dedication.
- **Piece artwork** — the [Cburnett](https://commons.wikimedia.org/wiki/Category:SVG_chess_pieces)
  set from Wikimedia Commons, **CC BY-SA 3.0**, transcribed to vector paths and adapted for this
  app's renderer. See `app/src/main/assets/PIECES_LICENSE.txt`.
- **Classification badges** — original to this project.

Chess.com is used only as a *design reference*. This app is not affiliated with, endorsed by, or
connected to Chess.com, and contains none of its artwork, logos, or trademarks.
