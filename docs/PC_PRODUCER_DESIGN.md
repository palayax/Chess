# PC Producer Design — `palaya-review`

Design for Round 12 task 53. Everything below about existing code is grounded in the tree as of 2026-09-25; every path is under `C:\Claude\ChessAnalyzer\`. Where I am estimating rather than measuring, I say so.

## 0. Decisions at a glance

| Question | Decision | One-line reason |
|---|---|---|
| Orchestration language | **Kotlin/JVM, new Gradle module `:desktop`** | The analysis, classification, tactics, sequences, spoken-chess and templated-narration logic is 6,300 lines of tested Kotlin in `:core`; a Python orchestrator would have to fork it or embed a JVM anyway |
| Python | **Only** the TTS worker (`pc/tts/tts_worker.py`, JSON-lines over stdin/stdout, pluggable backends) | PyTorch is unavoidable for Chatterbox/VoxCPM2; isolate it behind one small protocol |
| LLM | llama-server over HTTP (`127.0.0.1:8080`, OpenAI-compatible, `response_format: json_schema`) started and stopped by the producer | Per `docs/pc_research/LLM.md` |
| Engine | `pc/bin/stockfish/stockfish-windows-x86-64-universal.exe` over UCI via `ProcessBuilder`, MultiPV 3, `go depth N` | Same protocol and field mapping as `StockfishEngine`/`AnalysisService`, so `PositionEval`s are identical in shape and semantics |
| Renderer | **Java2D** (headless `BufferedImage`), frames piped to ffmpeg as `rawvideo` on stdin | In the JDK, deterministic, flat UI content; no Skiko native payload |
| Encoder | ffmpeg 9 `libx264` (default) or `h264_nvenc` (`--encoder nvenc`) | GPU is free by the time we render (TTS is done) |
| Intermediates | `pc/work/<gameId>/` with one JSON per stage plus per-stage fingerprint; any stage re-runnable | Requirement 2 |
| Narration fallback | Existing typed `Sentence`s rendered through `EnglishNarration` (core) | Requirement 4, no new prose |
| Hebrew | `--lang he`: Hebrew `SpokenVocabulary` implemented in `:desktop` first (promote to `:core` later), Hebrew LLM output, Chatterbox Multilingual V3 or VoxCPM2 backend, `dicta` diacritics | `NarrationLocales.all` is English-only today (`core/.../narration/NarrationStrings.kt:633`) |
| `:core` changes | Two small, additive, Android-safe ones (§15) | Keep the 279 core tests green and the app untouched |

## 1. Process and language split

### 1.1 Why Kotlin orchestration, not Python

What the producer must reuse is *logic*, not files:

- PGN → positions: `core/src/main/kotlin/net/palaya/chessanalyzer/core/pgn/PgnParser.kt:24` (`parse(text): List<PgnGame>`), with `positionFenBefore/After` per move (`core/pgn/Pgn.kt:26-37`).
- Evals → report: `GameAnalyzer.analyze(game, evals, userColor, book)` (`core/analysis/GameAnalyzer.kt:35`), which requires `evals.size == game.moves.size + 1` (`:36-39`), runs the classifier ladder (`core/analysis/MoveClassifier.kt:28-86`), the motif detector, `SimulationBuilder` (`:98-100`), and picks the top-5 key moments (`:157-162`).
- Significance and grouping: `TacticSignificance.prune(report, thresholdCp)` (`core/analysis/TacticSignificance.kt:141`), `MoveSequenceDetector.detect(annotations)` (`core/analysis/MoveSequences.kt:67`), the default threshold `NarrationOptions.DEFAULT_SIGNIFICANCE_THRESHOLD_CP = 50` (`core/narration/NarrationContract.kt:307`).
- Spoken chess: `SpokenChess.describe/describeUci/describeLine` (`core/narration/SpokenChess.kt:25,63,79`) → `SpokenMove` → `EnglishNarration.Vocabulary.movePhrase/square` (`core/narration/EnglishNarration.kt:759-800`; squares are always "f three", `:763`). `NotationGuard.scrub/containsNotation` (`core/narration/NotationGuard.kt:49,56`) is the leak backstop.
- Templated script: `VideoScriptGenerator` (`core/narration/VideoScriptGenerator.kt:50-56`) and the whole `Sentence` catalogue (`NarrationStrings.kt:236-625`).

Re-implementing any of that in Python forks `docs/ANALYSIS_SPEC.md`, which CLAUDE.md forbids ("Do not invent thresholds in code"). A Python orchestrator would therefore have to shell out to a Kotlin CLI for analysis and storyboard anyway, i.e. two runtimes and two process boundaries. Inverting it — Kotlin orchestrates, Python does only the one thing that needs PyTorch — gives one boundary (the TTS worker) and keeps every chess claim inside tested code.

Rendering in Kotlin (Java2D) rather than Python (Pillow/cairo) follows from the same argument: `SegmentFrameBuilder` (`app/src/main/kotlin/net/palaya/chessanalyzer/video/SegmentFrameBuilder.kt:56-172`) already encodes how a `BoardDirective` becomes frames (easing at `:255-258`, 400 ms move animation at `:31`, excursion tint driven by `SegmentKind.MISSED_TACTIC` at `:162-171`) and is pure Kotlin except for its UI-model imports; it ports almost verbatim.

Cost of the choice: `:desktop` needs a JSON library (`kotlinx-serialization-json`, plugin added to `gradle/libs.versions.toml`; `:core` stays dependency-free) and one more Gradle module. Gradle constraint from the brief: **one `./gradlew` at a time**. The producer is therefore run from `desktop/build/install/palaya-review/bin/palaya-review.bat` (`installDist`), never via `gradlew run`, so producing a video never holds the Gradle lock while the Android session builds.

### 1.2 Processes at runtime

```
palaya-review (JVM, :desktop)
 ├─ stockfish.exe            stdin/stdout UCI      stage: analyze
 ├─ llama-server.exe         HTTP 127.0.0.1:8080   stage: script     (GPU+CPU)
 ├─ python tts_worker.py     stdin/stdout JSONL    stage: audio      (GPU)
 └─ ffmpeg.exe               rawvideo on stdin     stage: render/mix (CPU, optional NVENC)
```

Never two GPU consumers at once (§11). Never Stockfish and llama-server at once either — both saturate the 8 cores.

## 2. Module layout

New module `desktop/` (`include(":desktop")` in `settings.gradle.kts`), package root `net.palaya.chessanalyzer.desktop`.

```
desktop/build.gradle.kts            kotlin("jvm"), application, kotlinx-serialization; jvmToolchain(17)
                                    mainClass = net.palaya.chessanalyzer.desktop.cli.Main
                                    processResources { from("../app/src/main/assets") { include("openings.tsv", "PIECES_LICENSE.txt") } }
desktop/src/main/kotlin/net/palaya/chessanalyzer/desktop/
  cli/Main.kt                       arg parsing, stage dispatch, exit codes
  cli/Options.kt                    ProducerOptions data class (all flags, §12)
  work/WorkDir.kt                   pc/work/<gameId>/ layout, atomic write (.tmp + rename), stage meta
  work/Stage.kt                     enum Stage { ANALYZE, STORYBOARD, SCRIPT, AUDIO, RENDER, MIX } + fingerprints
  work/Json.kt                      kotlinx-serialization config (prettyPrint, explicitNulls=false)
  engine/UciClient.kt               ProcessBuilder Stockfish driver; keeps stdin open until bestmove
  engine/UciLineParser.kt           copy of engine/.../AnalysisModels.kt:54-136 (it is `internal` to :engine)
  engine/EngineAnalyzer.kt          per-ply loop → List<PositionEval>; terminal short-circuit; checkpoints
  analysis/AnalysisStage.kt         PGN → evals → analysis.json (+ report.json for humans)
  analysis/ReportFactory.kt         evals + game → GameReport (GameAnalyzer + OpeningBook), pruned view
  storyboard/Director.kt            GameReport → Storyboard (tiers, structure, beats)
  storyboard/Tiering.kt             tier assignment + FULL cap + duration budget
  storyboard/Variations.kt          missed line / refutation line / rewind cues (SimulationBuilder, evals PV)
  storyboard/Facts.kt               per-beat fact sheet + placeholder table
  storyboard/Fallback.kt            templated narration per beat from Sentence + NarrationStrings
  storyboard/StoryboardModel.kt     serializable Storyboard/Beat/Cue/Placeholder DTOs
  llm/LlamaServerProcess.kt         start/health/stop llama-server
  llm/ChatClient.kt                 interface + HttpChatClient (java.net.http) + FakeChatClient for tests
  llm/Prompts.kt                    persona, style guide, ban-list, grounding rules, per-chunk user message
  llm/ScriptWriter.kt               chunking, requests, retries, assembly → script.json
  llm/ScriptValidator.kt            token/leak/length/ban/language checks
  llm/PlaceholderRenderer.kt        {M23} → spoken text (per language) / caption text (SAN)
  tts/TtsWorkerProcess.kt           start python worker, JSONL protocol, health
  tts/TtsClient.kt                  interface TtsBackend { synth(line) }, ProcessTtsBackend, FakeTtsBackend
  tts/AudioStage.kt                 per-line WAVs, manifest with measured duration/RMS/peak/leading silence
  tts/Wav.kt                        port of app/.../video/WavUtil.kt (pure JVM already, no Android imports)
  timeline/Timeline.kt              beats+lines+cues on a ms axis, audio-driven (§7)
  timeline/TimelineBuilder.kt
  timeline/CaptionTimer.kt          word/chunk timing estimation (§10)
  render/FrameRenderer.kt           Java2D compositor; layer caches; dirty-time frame reuse
  render/BoardLayer.kt              squares, coordinates, highlights, arrows, pieces, animating piece, badge
  render/PanelLayer.kt              player cards, move list, chapter, verdict chip, tactic line
  render/EvalBarLayer.kt, EvalGraphLayer.kt, CaptionLayer.kt, CardLayer.kt
  render/PiecePaths.kt              Cburnett paths as java.awt.geom.Path2D (port of app/.../ui/board/PieceVectors.kt)
  render/Palette.kt                 constants copied from app/.../video/BoardFrameRenderer.kt:177-210
  render/Fonts.kt                   bundled OFL fonts (Latin + Hebrew), Font.createFont
  encode/FfmpegProcess.kt           rawvideo pipe → silent video.mp4; probe(); loudness measure
  encode/RenderStage.kt             timeline → frames → ffmpeg
  audio/MixStage.kt                 narration.wav + sfx.wav + music → mix.wav (loudnorm) → mux review.mp4
  audio/Sfx.kt                      CC0 Kenney cue table + PCM mixing
  lang/HebrewSpokenVocabulary.kt    implements core SpokenVocabulary (Hebrew square/piece/move phrases)
desktop/src/main/resources/         openings.tsv (copied), fonts/, sfx/, PIECES_LICENSE.txt, prompts/*.md
desktop/src/test/kotlin/...         §13
pc/tts/tts_worker.py                the Python sidecar (uv venv, Python 3.11)
pc/tts/backends/{chatterbox_turbo,chatterbox_v3,voxcpm2,fake}.py
pc/tts/requirements-*.txt
pc/work/                            gitignored
```

## 3. Pipeline stages, work dir and schemas

`gameId = sha256(normalizedPgn)[:12]` where normalization strips comments/clocks and whitespace (so the same game from chess.com and from a hand-typed PGN cache to the same dir). Work dir: `pc/work/<gameId>/`.

```
game.pgn
analysis.json        stage ANALYZE  (evals, engine settings)
report.json          derived, informational (GameReport dump for humans and for the LLM spike)
storyboard.json      stage STORYBOARD
script.json          stage SCRIPT
audio/manifest.json  stage AUDIO   + audio/<lineId>.wav
timeline.json        (computed at RENDER from script+audio; cached)
video.mp4            stage RENDER  (silent, video only)
narration.wav, sfx.wav, mix.wav
review.mp4           stage MIX     (final; copied to -o path)
stages.json          per-stage {inputFingerprint, configVersion, finishedAt, tool versions}
metrics.json         measured: engine s/ply, LLM tok/s, TTS RTF, render fps, ffmpeg time
```

Rule: a stage runs when its output is missing, its recorded `inputFingerprint` differs from the hash of its current inputs (upstream artifact + the subset of options it depends on + a `configVersion` constant bumped when code changes semantics), or `--force`/`--from <stage>` says so. Stage outputs are written to `*.tmp` and renamed. `--only <stage>` runs one stage and stops.

Why `analysis.json` stores evals, not the report: `PositionEval` (`core/analysis/Contract.kt:37-44`) is small and the expensive thing; `GameAnalyzer.analyze` is pure and takes milliseconds, so the report is recomputed on load (`ReportFactory`). That also means a `:core` classification fix retroactively improves cached games without re-running Stockfish. `report.json` is still dumped for inspection (it is what the task-55 spike checks the script against).

### 3.1 analysis.json

```json
{
  "schema": "palaya.analysis/1",
  "gameId": "3f9c1a2b7d4e",
  "pgnSha256": "…",
  "tags": {"White":"MorphyFan1857","Black":"DukeAndCount","WhiteElo":"1487","Result":"1-0"},
  "startFen": null,
  "engine": {"name":"Stockfish 19","binarySha256":"…","threads":8,"hashMb":512,"multiPv":3,"depth":22,"movetimeCapMs":8000},
  "evals": [
    {"ply":0,"fen":"rnbqkbnr/… w KQkq - 0 1","depth":22,
     "lines":[{"multiPv":1,"scoreCp":31,"mateIn":null,"depth":22,"pvUci":["e2e4","e7e5"]},
              {"multiPv":2,"scoreCp":27,"mateIn":null,"depth":22,"pvUci":["d2d4"]},
              {"multiPv":3,"scoreCp":20,"mateIn":null,"depth":22,"pvUci":["c2c4"]}]}
  ],
  "checkpoint": {"completedPlies": 47}
}
```

`evals[i]` maps 1:1 to `PositionEval(fen, lines, depth)`; `lines[]` maps 1:1 to `EngineLineInput` (`Contract.kt:26-34`, scores from the side-to-move perspective exactly as the UCI line reports them; the perspective flip is core's job, `WinProbability.kt:69-81`). `evals.size == moves.size + 1`.

### 3.2 storyboard.json

```json
{
  "schema": "palaya.storyboard/1",
  "gameId": "…", "lang": "en", "targetSeconds": [480, 840], "estimatedSeconds": 612,
  "header": {"white":"MorphyFan1857","black":"DukeAndCount","whiteElo":1487,"blackElo":1502,
             "result":"1-0","opening":"Philidor Defense","eco":"C41","timeControl":"600","date":"2026-03-14"},
  "chapters": [{"id":"cold_open","title":"Cold open","firstBeat":"b000"}, …],
  "beats": [
    {
      "id": "b017", "chapter": "climax", "kind": "FULL_ASK", "tier": "FULL",
      "ply": 23, "moveNumber": 12, "color": "WHITE",
      "board": [
        {"cue":"hold","fen":"…","at":"start"},
        {"cue":"highlight","squares":["f7","d5"],"at":"start"},
        {"cue":"caption_card","text":"Find the move","at":"start"},
        {"cue":"hold_silence","ms":3000,"at":"end"}
      ],
      "eval": {"winPercentWhite":91.2,"cp":412,"mateIn":null},
      "facts": { … §6.2 … },
      "placeholders": {"{M23}":{"uci":"d1h5","san":"Qh5+","spoken":"queen to h five, check"},
                       "{BEST23}":{"uci":"f3f7","san":"Nxf7","spoken":"knight takes the pawn on f seven"},
                       "{SQ_f7}":{"spoken":"f seven","caption":"f7"}, "{WHITE}":{"spoken":"MorphyFan","caption":"MorphyFan1857"}},
      "wordBudget": {"min":15,"max":30},
      "fallbackText": "White to play. There is a move here that wins a whole piece. Pause the video. Can you find it?",
      "sfx": [{"name":"riser","at":"start"}],
      "musicMode": "tense"
    },
    {
      "id": "b018", "kind": "FULL_REVEAL", "tier": "FULL", "ply": 23,
      "board": [{"cue":"arrow","from":"f3","to":"f7","role":"BEST","at":"start"},
                {"cue":"play_line","fromFen":"…","uci":["f3f7","e8f7","d1h5","f7g8","h5e5"],"san":["Nxf7","Kxf7","Qh5+","Kg8","Qxe5"],
                 "label":"VARIATION","perMoveMs":800,"evalTrace":[412,405,398,390,610],"at":"line:2"},
                {"cue":"rewind","toFen":"…","perMoveMs":150,"at":"end"}],
      "facts": {…}, "placeholders": {…}, "wordBudget": {"min":60,"max":110}, "fallbackText": "…"
    },
    { "id":"b019", "kind":"FULL_REACT", "tier":"FULL", "ply":23,
      "board":[{"cue":"play_move","fen":"…","uci":"d1h5","san":"Qh5+","classification":"BLUNDER","at":"start"},
               {"cue":"badge","square":"h5","classification":"BLUNDER","at":"start+350"},
               {"cue":"shake","at":"start+350"}],
      "sfx":[{"name":"wah_wah","at":"start+400"}], … },
    { "id":"b020", "kind":"SKIP_BATCH", "tier":"SKIP", "plies":[24,25,26,27,28],
      "board":[{"cue":"play_moves","plies":[24,25,26,27,28],"perMoveMs":700,"at":"start"}],
      "wordBudget":{"min":8,"max":22}, "fallbackText":"The next few moves are just development and shuffling. Nothing breaks, so let's jump to move 15." }
  ]
}
```

Beat kinds: `COLD_OPEN, INTRO_CARD, OPENING_COMMENT, SKIP_BATCH, BRIEF, DWELL, DWELL_VARIATION, FULL_ASK, FULL_REVEAL, FULL_REACT, FULL_VARIATION, FULL_BACK, ENDGAME_SPRINT, RECAP_CARD, CTA`. Cue anchors (`at`): `start`, `start+<ms>`, `end`, `end-<ms>`, `line:<n>` (start of spoken line n), `frac:<0..1>`.

### 3.3 script.json

```json
{
  "schema": "palaya.script/1", "gameId":"…", "lang":"en",
  "model": {"name":"gemma-4-26B-A4B-it-UD-Q4_K_M","temperature":0.9,"seed":7,"promptSha256":"…"},
  "beats": [
    {"id":"b017","source":"llm","attempts":1,
     "lines":[
       {"id":"b017_l0","text":"{WHITE} to move, and I want you to pause here.","spoken":"MorphyFan to move, and I want you to pause here.","caption":"MorphyFan1857 to move, and I want you to pause here.","emotion":"neutral","tokens":["{WHITE}"]},
       {"id":"b017_l1","text":"There is a move that wins a whole piece. Three, two, one.","spoken":"…","caption":"…","emotion":"excited","tokens":[]}
     ]},
    {"id":"b018","source":"template","attempts":3,"validatorErrors":["leaked notation: Qh5","word count 131 > 110"], "lines":[…]}
  ]
}
```

`text` is the model output with placeholders; `spoken` and `caption` are the two renderings (§6.5). `emotion ∈ {neutral, excited, shocked, amused, disappointed, tense}` maps to backend tags.

### 3.4 audio/manifest.json

```json
{"schema":"palaya.audio/1","backend":{"name":"chatterbox_turbo","version":"…","device":"cuda","sampleRate":24000,"voice":"default","fingerprint":"sha256(backend+voice+params)"},
 "lines":[{"id":"b017_l0","wav":"audio/b017_l0.wav","durationMs":2310,"leadingSilenceMs":120,"trailingSilenceMs":210,"rmsDbfs":-21.4,"peakDbfs":-3.2,"nearSilentRatio":0.19,"rtf":0.6,"textSha256":"…"}]}
```

The cache key per line is `sha256(spoken text + backend fingerprint)` (same idea as `app/.../video/NarrationStore.kt:36`), so re-running the LLM only re-synthesizes lines whose text changed.

### 3.5 timeline.json

```json
{"fps":30,"width":1920,"height":1080,"totalMs":612400,
 "beats":[{"id":"b017","startMs":118200,"endMs":126900,
           "lines":[{"id":"b017_l0","startMs":118200,"audioStartMs":118200,"durationMs":2310}],
           "cues":[{"cue":"hold","atMs":118200},{"cue":"hold_silence","atMs":123900,"ms":3000}],
           "captions":[{"startMs":118200,"endMs":119050,"words":["MorphyFan","to","move,"],"key":0}]}],
 "sfx":[{"name":"riser","atMs":118200}],
 "music":[{"mode":"tense","fromMs":118200,"toMs":126900}]}
```

## 4. Engine adapter — identical `PositionEval`s

The Android path is `AnalysisService.analyze` (`app/src/main/kotlin/net/palaya/chessanalyzer/data/AnalysisService.kt:110-183`): one `position fen <fen>` + `go depth D` per ply (`:145-146`), MultiPV set once via `setoption name MultiPV` when it changes (`engine/.../StockfishEngine.kt:207-210`), the *latest* `info` line per `multipv` slot kept until `bestmove` (`:218-232`), `PositionEval.depth = result.depth` = the deepest completed iteration across lines (`:225-227`, `AnalysisService.kt:267`), and a terminal-position short-circuit that never asks the engine about a checkmate/stalemate (`AnalysisService.kt:234-243`: `mateIn = 0` for checkmate, `scoreCp = 0` for stalemate). `bestmove (none)` must parse (`AnalysisModels.kt:59-66`).

`desktop/engine/UciClient` reproduces exactly that:

- `uci` → wait `uciok`; `setoption name Threads value 8`, `Hash value 512`, `MultiPV value 3`; `ucinewgame`; `isready` → `readyok`.
- Per ply: `position fen <fen>`, `go depth <D> movetime <cap>` — depth is the primary limit, the movetime cap (default 8000 ms, `--movetime-cap`) is a safety net for pathological positions; both are passed exactly as `StockfishEngine.analyze` does when both are given (`:212-216`, "whichever limit is hit first").
- **Keep stdin open until `bestmove`** (the RUN_LOG gotcha): the writer is a `BufferedWriter` that is flushed, never closed, until `quit`; the reader thread drains stdout into an unbounded queue (same reasoning as `StockfishEngine.kt:50-55`). On cancel: `stop`, drain to `bestmove`.
- Mapping to `EngineLineInput(multiPv, scoreCp, mateIn, depth, pvUci)` identical to `AnalysisService.toPositionEval` (`:260-268`), including the "no info lines → one line with just the bestmove" fallback.
- No NNUE download: the official Windows build embeds its net, so the `setEvalFile` guard (`StockfishEngine.kt:126-146`) has no desktop counterpart. The engine still exits on a bad `EvalFile`; we never set it.
- Depth: default **22** (`--depth`), MultiPV **3** (`--multipv`; GREAT needs ≥ 2, `MoveClassifier.kt:58-63`; `evalSecondBestCp` needs ≥ 2, `GameAnalyzer.kt:69-72`). The app's default is 14 (`app/.../ui/model/GameModels.kt:230`, `SettingsRepository.kt:105`); `ANALYSIS_SPEC.md §8` names 12/18/24 tiers. Depth 22 at 3.4 Mnps (measured, RUN_LOG Round 12) is roughly 1–3 s per ply → 2–4 min for an 80-ply game (estimate; `metrics.json` records the real number). `--quality best` uses depth 26.
- Checkpoint every 5 plies into `analysis.json.checkpoint` (mirrors `CHECKPOINT_EVERY_PLIES = 5`, `AnalysisService.kt:284`), resume verified by FEN alignment per index (mirrors `usableResumePrefix`, `:253-258`).
- `UciLineParser` is copied from `engine/src/main/kotlin/net/palaya/chessanalyzer/engine/AnalysisModels.kt:54-136` (80 lines, `internal` to an Android library module, so not importable). A later cleanup could move it to `:core` and have `:engine` import it; not now, to avoid touching `:engine`.

Parity test (§13): a captured UCI transcript fixture is parsed by both the copied parser and a hand-written expectation; and the classification counts for `fixtures/immortal.pgn` at depth 14/MultiPV 3 are recorded once as a regression fixture.

Report construction: `GameAnalyzer(MoveClassifier(StaticExchangeEvaluator()), MotifDetector(see))` exactly as `AnalysisService.kt:196-198`; opening book via `OpeningBook.load(reader)` (`core/analysis/OpeningBook.kt:59`) over the copied `openings.tsv`; `userColor` from `--user <name>` matched against the White/Black tags (same as `detectUserColor`, `:216-225`).

## 5. The Director (storyboard)

Input: the pruned report `TacticSignificance.prune(report, thresholdCp)` (the same pruning `VideoScriptGenerator` applies at `VideoScriptGenerator.kt:100`), the raw `evals` (for post-move PVs, which `MoveAnnotation` does not carry), `PgnGame`, `NarrationStrings`, options.

### 5.1 Per-ply scoring (reuse, do not reinvent)

For each `MoveAnnotation a`:
- `swingCp = |evalAfterCp − evalBeforeCp|` (`TacticSignificance.swingCp`, `:43`).
- `significant` per ANALYSIS_SPEC §9.2: `swingCp ≥ threshold || a has surviving tactics || in a MoveSequence with totalSwingCp ≥ threshold`. This rule is currently private in `VideoScriptGenerator.isSignificant` (`:430-440`) with the checkmate/never-empty guarantees in `applyThreshold` (`:413-425`). **Extract it** into a public `object SignificanceFilter` in `core.narration` (§15) so the Director and the generator cannot drift; `NarrationSignificanceTest` already pins the behaviour.
- `beatKind` mirrors `VideoScriptGenerator.beatKind` (`:454-464`): missed tactic (best move ≠ played, confidence ≥ 0.6, `:466-472`), error (BLUNDER/MISTAKE/MISS), found tactic, inaccuracy, threat allowed, key moment, normal.
- `interest(a)` for quiet plies mirrors `:443-452`.
- Turning point = max-loss ply, ties to the later ply (`:128-130`).

### 5.2 Pacing tiers (VIDEO_FORMAT.md §2)

| Tier | Assigned when | Words | Board |
|---|---|---|---|
| **SKIP** | BOOK, FORCED, BEST/EXCELLENT/GOOD with swingCp < 30, recaptures (`isRecapture`, `:1230-1234`), and anything not significant | batches of 3–6 plies, 8–22 words for the batch | auto-play 0.6–1.0 s/move |
| **BRIEF** | INACCURACY; threat allowed on a non-good move; non-climax member of a COLLAPSE/TACTIC sequence; significant but < 100 cp swing | 10–20 | play move, badge, one arrow |
| **DWELL** | MISTAKE; GREAT; found tactic with `confidence ≥ 0.95`; a demoted FULL | 40–75 | play move, badge, arrows for played+best+threat (`annotateDirective`, `:1302-1316`), optional 2–4-ply variation without a puzzle pause |
| **FULL** | BLUNDER, MISS, BRILLIANT, missed tactic with `materialSwing ≥ 150` or a mating motif or mate available (`worthAPuzzle`, `:708-709`) | ASK 15–30, REVEAL 40–90, REACT 20–45, VARIATION 40–80 (if separate), BACK 8–15 | find-the-move hold, reveal arrow, variation show-and-rewind, played move with shake |

**Cap**: FULL candidates sorted by `loss` desc, ties earlier; keep the top **3** (`--full-moments 4` allows 4); the rest become DWELL. The turning point is always FULL and always the climax chapter. The checkmate always gets at least a DWELL beat (`applyThreshold` keeps it, `:418-420`).

### 5.3 Structure

1. **Cold open** (0–15 s): `hold` on `fenBefore` of the climax ply, eval bar frozen at `evalBefore`, one line (facts: the standing before/after via `NarrationVocabulary.standing` — it is `internal` to core, so the Director maps `winPercent` to the same `Standing` cut-offs, or the object is made public in §15). Hard cut to the intro. No logo.
2. **Intro card** (~15 s): names, ratings, time control (`TimeControl` tag), result; hidden ratings when `--guess-the-elo`.
3. **Opening** (10–15%): book plies (`classification == BOOK`) auto-played at 0.7 s each in SKIP batches with one `OPENING_COMMENT` per 3–4 moves; the opening name from `report.openingName/openingEco` (`GameAnalyzer.kt:151`, `OpeningBook.longestMatchingOpening`, `:48-54`), the family plan via `OpeningFamily` (`NarrationStrings.kt:170-196`) offered to the LLM as a fact, and "theory runs out at move N" from the last BOOK ply (`openingSummary`, `:268-271`).
4. **Middlegame** (40–50%): the tiered beats in ply order, with `SKIP_BATCH` connectives between them (a gap ≥ 3 plies becomes a batch, as at `:313-322`).
5. **Climax** (20–25%): the turning point's FULL sequence: `FULL_ASK` (3 s `hold_silence`, "Find the move" caption card, target/involved squares highlighted as in `puzzlePrompt`, `:1020-1043`) → `FULL_REVEAL` (best-move arrow, then `play_line` of the variation, eval bar live from `evalTrace`) → `rewind` (0.15 s/move, whoosh) → `FULL_REACT` (the move actually played, badge pop, shake, 1.3× zoom for this chapter only) → `FULL_BACK`, then the eval graph strip appears under the board for the rest of the video.
6. **Ending** (~10%): `ENDGAME_SPRINT` batches at 0.5 s/move down to the last move; the mating move gets its own DWELL with `MateClosing`-style narration.
7. **Recap card**: accuracy and estimated rating per side (`report.white.accuracy/estimatedRating`), error counts (`classificationCounts`), the key moment, the full eval graph (`report.evalGraph`, White-relative win% per ply, `GameAnalyzer.kt:42,171`), then a 5 s `CTA` card with the Palaya logo (`app/src/main/res/drawable-nodpi/palaya_logo.png`, owner's own brand).

### 5.4 Variations (show-and-rewind)

Three sources, in priority order, all producing `play_line` cues with `uci[]`, `san[]` (via `Position.moveToSan`) and `evalTrace[]`:

1. **Missed tactic**: `a.simulation` from `SimulationBuilder` (`core/analysis/SimulationBuilder.kt:42-103`; capped at 8 plies, truncated when the payoff is realised, `:27,86-87`), else the `bestLineSan` fallback exactly as `missedLine` (`VideoScriptGenerator.kt:1321-1342`). Per-ply reasons come from `SimulationBuilder`'s `perPlyExplanation` (on-screen text, notation allowed there) and from `Sentence.ExcursionMove/LineEndsInMate/MaterialTaken/…` for speech (`:810-879`).
2. **Refutation of a blunder** ("and now Black just plays…"): `evals[ply].best.pvUci` — the PV *after* the blunder, which the Director has because it holds the raw evals; `threatsAllowed` (`GameAnalyzer.kt:93-96`) names the motif. Cap 4 plies.
3. **Textbook reference** (`TacticReferenceLibrary.simulation(type)`, `core/analysis/TacticReferenceLibrary.kt:48-73`): not in the main line (it breaks pace, which is why the generator only *offers* it, `:1158-1161`); available as an optional post-recap "one clean example" beat behind `--textbook`.

`evalTrace` for a variation: the engine only scored game positions, so along a missed line the eval is `evalBefore` throughout (the generator's reasoning at `:997-1005`); the bar therefore holds steady during the variation and the "live" motion is the badge/arrows, unless `--quality best` is on, in which case `AnalysisStage` additionally scores each variation position at depth 16 (`extraEvals` in `analysis.json`) so the bar really moves.

Rewind cue: the renderer plays the plies backwards at 150 ms each from the line's end FEN to `fenBefore`, with a `whoosh` SFX and the "VARIATION" banner sliding out.

### 5.5 Duration budget

`estimatedMs(beat) = max(estimateSpeechMs(fallbackText or wordBudget.mid, wpm) + holds, visualMinMs)` using `VideoScriptGenerator.estimateSpeechMs` (`:64-71`, public) with the TTS's measured wpm (Kokoro measured 169 wpm, `docs/voice_samples/README.md`; Chatterbox to be measured by the spike; default `--wpm 165`). If the total exceeds the 14-min ceiling: demote the lowest-interest DWELL→BRIEF, then BRIEF→SKIP, then shrink word budgets by 15%; if under 8 min (short games): promote by interest and allow the textbook beat. Storyboard records `estimatedSeconds`; the real length is known only after audio.

### 5.6 Fallback narration per beat

`Fallback.kt` builds each beat's `fallbackText` from typed `Sentence`s rendered through `NarrationStrings.render(sentence, NarrationStyle.COACH)` (`NarrationStrings.kt:51`) with `SpokenMove`s from `SpokenChess` — e.g. `Sentence.Played(subject, MoveVerb.PLAY, spoken)`, `Sentence.ErrorOpener(classification)`, `Sentence.ConsequenceChanged(...)`, `Sentence.BetterWas(best)`, `Sentence.PuzzlePrompt(side, prize)`, `Sentence.PivotIn/PivotReveal/PayoffMate/PivotOut/ChanceGone`, `Sentence.SkipAhead(n)`, `Sentence.OutroAccuracy(...)`. Variant rotation: `PhrasePicker` is `internal` (`NarrationVocabulary.kt:90`), so `Fallback` keeps its own deterministic cursor per `poolKey`. Everything passes `NotationGuard.scrub` (`:56`) like `add()` does (`VideoScriptGenerator.kt:1360`). Net effect: `--no-llm` produces a complete, speakable, template-only video, and any beat the validator rejects degrades to exactly this text.

## 6. The LLM script writer

### 6.1 Requests are per chunk, not per script

One request per chapter-sized chunk: (cold open + intro), (opening), each FULL moment alone, middlegame batches of ≤ 8 beats, (ending + recap). Reasons: `LLM.md` sizes a whole game at 5–7K facts + 1.5K persona + 3–4K output against a 16K context, and at the estimated 8–14 tok/s a 3.5K-token script is 5–10 min — a per-chunk failure must not throw all of it away. A running "story so far" (≤ 120 words, model-written summary of the previous chunk plus up to two `callbacks` it may reuse — this is the running-gag mechanism) is carried forward.

### 6.2 Prompt architecture (`desktop/src/main/resources/prompts/`)

- `persona.md`: an original character — energetic, honest, self-deprecating, explains the chess, personifies pieces, rhetorical questions, reactions scaled to the eval swing, **always one sentence of why after a joke** (VIDEO_FORMAT.md §4). No real names.
- `style_rules.md`: spoken register; short sentences; numbers only from the facts; second person only when `facts.viewerSide` is set; no notation of any kind; never say "engine line" more than once per beat; emotions as a field, not as words.
- `banlist.txt`: catchphrases and signature lines of real creators (kept as data, checked case-insensitively by the validator, never echoed in the prompt as examples of what to avoid — listing them would teach the model to produce them; the prompt says "do not imitate any real commentator", the validator enforces the list).
- `grounding.md`: the hard rules from `LLM.md`: mention no move, square, piece or number that is not in the beat's facts; never calculate; refer to moves **only** through the placeholder tokens listed for the beat; jokes are about drama, never new chess claims.
- `schema.json`: the `json_schema` sent as `response_format`.

Per-beat fact sheet (the only chess the model is told):

```json
{"id":"b019","kind":"FULL_REACT","tier":"FULL","wordBudget":{"min":20,"max":45},
 "moveNumber":12,"side":"WHITE","sideName":"{WHITE}","viewer":false,
 "played":"{M23}","best":"{BEST23}","classification":"BLUNDER",
 "standingBefore":"WINNING","standingAfter":"CLEARLY_WORSE","lossSeverity":"THE_WHOLE_GAME",
 "evalBefore":"+4.1","evalAfter":"-1.8","mateAvailableBefore":null,
 "tactic":{"type":"FORK","byOpponent":true,"targets":["{SQ_e1}","{SQ_h4}"],"victimPieces":["king","queen"]},
 "pieceMoved":"queen","captured":null,"givesCheck":true,
 "runningGags":[{"key":"knight_b1_idle","fact":"White's queen's knight has not moved since move 1"}],
 "allowedNumbers":[12,4.1,1.8],
 "allowedTokens":["{M23}","{BEST23}","{SQ_e1}","{SQ_h4}","{WHITE}","{BLACK}"]}
```

Facts come only from `MoveAnnotation` fields (`Contract.kt:160-199`), `NarrationVocabulary` cut-offs (`standing`, `lossSeverity`, `materialPayoff`, `NarrationVocabulary.kt:15-44`), `EvalFormat.score` (`core/analysis/EvalFormat.kt:33`) for the pre-formatted evals, and `SpokenChess` for piece names. "Running gags" are computed by the Director from game data only (a piece that never moved, a player who castled on move 30, three captures on the same square, etc.), each with a factual sentence the model may riff on.

Output schema (enforced by llama-server's `json_schema`):

```json
{"type":"object","required":["beats","storySoFar"],
 "properties":{"storySoFar":{"type":"string","maxLength":800},
  "beats":{"type":"array","items":{"type":"object","required":["id","lines"],
   "properties":{"id":{"type":"string"},
     "lines":{"type":"array","minItems":1,"maxItems":6,"items":{"type":"object","required":["text","emotion"],
       "properties":{"text":{"type":"string","maxLength":320},
                     "emotion":{"enum":["neutral","excited","shocked","amused","disappointed","tense"]}}}}}}}}}
```

`maxLength: 320` per line keeps every line under Chatterbox-Turbo's ~350-character limit and 40 s cap (`TTS.md`, Gotchas). Sampling: `temperature 0.9, top_p 0.95, seed = hash(gameId, attempt)`; thinking off (`--reasoning-budget 0` or the model's no-think chat-template flag, per llama-server's Gemma 4 support — to be confirmed in the task-55 spike).

### 6.3 Validator (`ScriptValidator`) — every beat, every attempt

1. Structural: JSON parses; beat ids equal the request's; ≥ 1 line.
2. Tokens: every `\{[A-Z_0-9a-h]+\}` ∈ `allowedTokens`; unknown tokens → error.
3. Leak detection on the text with tokens removed: `NotationGuard.containsNotation` (`:49`), plus `\b[a-h][1-8]\b` (bare squares), `\b[KQRBN][a-h]?[1-8]?x?[a-h][1-8]`, `O-O`, and "mate in N"/"M<n>" patterns unless the number is in `allowedNumbers`.
4. Numbers: every numeric literal ∈ `allowedNumbers` (move numbers, evals, accuracies, ratings the facts supplied). This is what stops "the eval dropped from plus six" when the facts said 4.1.
5. Length: total words ∈ `wordBudget` (±10% tolerance); each line ≤ 320 chars.
6. Ban-list: no phrase from `banlist.txt`, no real-creator names (list in the same file).
7. Chess anchoring: a FULL/DWELL beat must contain at least one of `{M..}`/`{BEST..}` or the tactic's name — otherwise it is a joke with no chess.
8. Language: for `he`, ≥ 80% of letters are Hebrew script (allowing Latin for player names inside tokens); for `en`, ASCII letters.
9. Emotion tags only from the enum (schema already enforces).

Failure → regenerate the chunk with the errors appended as a corrective user message ("Beat b019 used the square e5, which is not in its facts; remove it"), temperature −0.15 per attempt, up to 3 attempts; then the failing beats (only those) fall back to `fallbackText` with `source: "template"` and the error list recorded in `script.json`. The run summary prints the fallback ratio; `--llm-strict` makes > 20% fallback an error exit.

### 6.4 Speed estimate (unmeasured until task 55)

At the `LLM.md` MoE estimate of 8–14 tok/s: a 12-min video is ~45 beats ≈ 2,800 output tokens ≈ 4–6 min of generation, plus prompt processing per chunk (~2K tokens × 8 chunks; prompt eval is much faster than generation on `--cpu-moe`), plus one model load (~5 GB GGUF from disk, 30–90 s). Retries add ≤ 50%. Budget: **5–12 min** for the script stage. Dense 31B "quality" mode would be 25–40 min — offered only behind `--llm-model`.

### 6.5 Placeholders → spoken and written text per language

`PlaceholderRenderer` holds, per beat, `Placeholder(token, uci?, san?, square?, spokenBy: (SpokenVocabulary) -> String, caption: String)`:

- `{M23}`, `{BEST23}`, `{VAR23_n}`: spoken = `vocabulary.movePhrase(SpokenChess.describeUci(positionBefore, uci))` (`SpokenChess.kt:63`, `EnglishNarration.kt:780`); caption = SAN.
- `{SQ_e5}`: spoken = `vocabulary.square(Square.fromAlgebraic("e5"))` ("e five"); caption = "e5".
- `{WHITE}`/`{BLACK}`: spoken = the player's name if `NotationGuard.containsNotation(name)` is false (the generator's `spokenName` rule, `:1467-1471`), else `vocabulary.side(color)`; caption = full tag.
- `{OPENING}`: name; `{ECO}` caption only.

Two renderings are produced for each line: `spoken` (fed to TTS, then `NotationGuard.scrub`bed as a belt-and-braces pass) and `caption` (burned in, keeps SAN). For `--lang he`, `HebrewSpokenVocabulary` (in `:desktop`, implementing `core.narration.SpokenVocabulary`, `NarrationStrings.kt:63-82`) supplies piece nouns, "takes"/"to" phrasing and how squares are spoken (Hebrew letter for the file? Latin letter? — decide by ear in the spike; the interface leaves it to the locale, which is exactly what the Round 10 design intended). The LLM is asked for Hebrew text with the same ASCII tokens; the validator's Hebrew-script check plus the token whitelist keep it grounded. The persona prompt is translated once (a Hebrew `persona.he.md`).

## 7. Timing sync — audio drives the picture

The rule, inherited from `TimelineBuilder` (`app/.../video/ScriptTimeline.kt:32-52`): the measured WAV duration is the beat's speech duration; estimates exist only before audio exists. Differences from the app: the app pads or **truncates** speech to `speechDurationMs` (`VideoExporter.kt:501-508`); the producer never truncates audio.

Per beat:
```
lineStart(0) = beatStart
lineStart(n) = lineStart(n-1) + audioMs(n-1) + gapMs(n-1)        gap 220 ms, 320 after "?" (NarrationSynthesizer.kt:449-452)
speechEnd    = lineStart(last) + audioMs(last)
visualMin    = sum of cue durations that are anchored in sequence (moves × perMoveMs, hold_silence, rewind)
beatEnd      = max(speechEnd + tailMs, cueEnd) where tailMs = 250 (INTER_SEGMENT_GAP_MS, ScriptTimeline.kt:37), min beat 900 ms (:34)
```

Stretch/pad rules per cue type:
- `play_move` anchored at a line start: 350 ms animation (VIDEO_FORMAT §3; the app uses 400 ms, `SegmentFrameBuilder.kt:31`), 150 ms in SKIP batches; the settled position holds until the beat ends. Audio shorter than the animation → pad silence.
- `play_moves` (SKIP batch of N plies): `perMoveMs = clamp((speechMs − 300) / N, 600, 1200)`; if speech is still longer, hold the last position; if shorter, pad audio so the batch is never cut mid-move.
- `play_line` (variation): fixed 800 ms/move so the viewer can follow; the narration line(s) anchored to it are laid out first, and the moves are spread evenly across `max(speechMs, N × 800)`; remaining speech plays over the held final position.
- `rewind`: 150 ms/move, always after speech ends (`at: end`), adds to the beat.
- `hold_silence` (find-the-move): exactly 3,000 ms of narration silence with the tense music loop and a ticking SFX; placed after the ASK line ends.
- Badges pop 150 ms after the move settles; shake 300 ms; zoom eases over 400 ms and holds for the chapter.

Captions and SFX are placed on the same axis (§10, §9). The output is `timeline.json`; the renderer and the mixer both read it, so picture and sound cannot disagree (the same single-source principle as `ScriptTimeline` being shared by playback and export).

## 8. Rendering

### 8.1 Java2D, justified

- **Java2D**: in the JDK, headless (`-Djava.awt.headless=true`), `BufferedImage.TYPE_3BYTE_BGR` matches ffmpeg `-pix_fmt bgr24` byte-for-byte (no conversion pass — the app had to hand-convert ARGB→YUV, `VideoExporter.kt:402-414`). Anti-aliased shapes and text are good enough for flat UI; the known weaknesses (no LCD subpixel text on headless, softer glyphs at small sizes) are handled by rendering at 1080p with fonts ≥ 22 px and `KEY_FRACTIONALMETRICS on`, `KEY_STROKE_CONTROL pure`, `KEY_TEXT_ANTIALIASING on`. Bidi/RTL text works through `TextLayout`/`AttributedString`, which matters for Hebrew captions.
- **Skiko** rejected: ~40 MB native, a second graphics API to learn, and no meaningful quality gain for this content.
- **Python renderer** rejected: it would need the board/animation logic duplicated across the process boundary (§1).

### 8.2 Frame budget and static reuse

12 min × 30 fps = 21,600 frames at 1920×1080×3 = 6.2 MB each. Approach: **render only at dirty times, pipe every frame**.

- `Timeline` yields a sorted list of *change points* (cue starts/ends, animation ticks at 30 Hz only while an animation is active, caption chunk boundaries, eval-bar tween ticks). Between change points the frame is identical; the renderer keeps the last `BufferedImage` and writes its backing byte array again.
- Estimate: ~80 game plies × 11 frames (350 ms) + variations/rewinds (~200) + badge/shake/zoom ticks (~300) + caption chunk changes (~1,200) + eval-bar tweens (~500) ≈ **3,000–5,000 rendered frames** of 21,600. At an estimated 8–15 ms per full composite (layer caches below), rendering is ~1 min; the pipe write of 134 GB at ~1.5 GB/s is ~90 s; x264 `veryfast` at 1080p on 8 cores is roughly 100–200 fps → 2–4 min. **Render+encode ≈ 3–6 min** (estimate; `metrics.json` records it). NVENC halves the encode.
- Layer caches: `BoardLayer` static squares+coordinates cached once per orientation; piece sprites rasterised once per (piece, square size) from `PiecePaths` into `BufferedImage`s with alpha; `PanelLayer` re-rendered only when its inputs change (per beat); `EvalGraphLayer` once. Compositing = 4–6 `drawImage` calls plus the dynamic bits (animating piece, arrows, badge, caption).
- Why not PNG files + `image2`/`concat`: PNG encoding at 1080p is ~20–40 ms per frame and 21,600 files; the concat demuxer with per-image durations would avoid duplicates but ffmpeg then decodes PNG anyway. The raw pipe is simpler and faster.

ffmpeg invocation (render stage, video only):
```
ffmpeg -y -f rawvideo -pix_fmt bgr24 -s 1920x1080 -r 30 -i pipe:0
       -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p -g 60 -movflags +faststart video.mp4
```
`--quality best`: `-preset slow -crf 18`; `--encoder nvenc`: `-c:v h264_nvenc -preset p5 -rc vbr -cq 21 -b:v 0`. The process is started with `ProcessBuilder`, stdout/stderr drained to a log file; a write failure (`IOException` on the pipe) is surfaced with ffmpeg's last 40 log lines.

### 8.3 Layout (1920×1080, VIDEO_FORMAT.md §3)

```
x:   40  eval bar (36 wide)      100  board 720×720 (66.7% of height)     860 … 1880  right panel (1020 wide)
y:  100  board top (centered in the 0..920 band)                          y: 100 top player card (black), 200 move list, 640 caption/insight area, 820 bottom player card (white)
y:  840  optional eval-graph strip under the board (1080-840=240 tall band shared with captions)
y:  930 … 1040  burned-in word captions, centred over x 40..1880, 64 px font, safe margin 40
```

Elements, all ported from `BoardFrameRenderer` (`app/.../video/BoardFrameRenderer.kt`): palette `:177-196` (board `EBECD0/739552`, chrome `302E2B`, last-move `99BACA44`, check `EE3B3B`, excursion tint `C77DFF` at `:188`); classification colours `:198-210` (brilliant `26C2A3`, great `749BBF`, best/excellent `81B64C`, book `A88865`, inaccuracy `F7C631`, mistake `E58F2A`, miss `FF7769`, blunder `FA412D`) — these already follow the convention VIDEO_FORMAT names; arrow colours by `ArrowRole` `:212-217` (green played, blue best, red threat, yellow support — VIDEO_FORMAT wants green=best, red=threat, blue=idea, yellow=played; the producer's `Palette.kt` uses the VIDEO_FORMAT mapping and documents the difference from the app); eval bar `:649`; eval readout via `EvalFormat.score` (`:33`, `+0.9`/`M3`/`#`); badge = filled circle + glyph (`app/.../ui/theme/ClassificationBadge.kt:36-62`, original art); the hanging-piece pulsing ring is a 2-s sinusoidal alpha on a stroked circle.

Piece art: `PieceVectors.kt` (`app/src/main/kotlin/net/palaya/chessanalyzer/ui/board/PieceVectors.kt`) is the Cburnett set transcribed to Compose `Path` calls, CC BY-SA 3.0 (`app/src/main/assets/PIECES_LICENSE.txt`). It cannot be linked from a JVM module (Compose types, `internal`), but the transcription ports mechanically to `java.awt.geom.Path2D.Float` (`moveTo/lineTo/curveTo`; the relative variants become absolute; the `PathOperation.Difference` carve-outs become `java.awt.geom.Area.subtract`). Licence status: CC BY-SA 3.0 is fine for the owner's personal videos; the attribution file ships in `desktop/src/main/resources` and, if a video is ever published, one line in the description satisfies attribution. `res/drawable` holds only `ic_notification_video_export.xml` and `palaya_logo.png`; the logo is reused for the CTA card.

Fonts: bundle Inter (OFL) and Noto Sans Hebrew (OFL) under `resources/fonts/`; never rely on system fonts (Windows has Arial/Segoe but the render must be reproducible).

Eval graph (recap and strip): port of `app/.../ui/components/EvalGraph.kt` semantics (win% line per ply, segment coloured by the move's classification, dots on mistakes, sequence bands) using `report.evalGraph` and per-ply classifications; a moving cursor at the current ply on the strip.

## 9. Audio mix

Inputs: per-line WAVs (mono, backend sample rate, 24 kHz for Chatterbox), CC0 Kenney SFX (`resources/sfx/`: `move.wav`, `capture.wav`, `check.wav`, `chime.wav`, `wah_wah.wav`, `whoosh.wav`, `riser.wav`, `tick_loop.wav`; VIDEO_FORMAT §5 lists exactly these roles; the actual Kenney files are picked and renamed at build time with a `SFX_LICENSE.txt`), optional music (`--music tense.ogg --music-upbeat upbeat.ogg`, CC0 only; none bundled by default).

Steps in `MixStage`:
1. `narration.wav`: 48 kHz mono, built like `buildPcmTrack` (`VideoExporter.kt:491-514`) from `timeline.json` — each line's samples at `audioStartMs`, silence elsewhere; resampling via the ported `Wav.readAsMono16(file, 48000)`; **no truncation**. Normalised to −16 LUFS with a two-pass `loudnorm` (measure JSON → apply `measured_*`).
2. `sfx.wav`: mixed in the JVM from the timeline's `sfx[]` list (16-bit add with a soft clip guard), each cue at −12 to −18 dBFS relative gain.
3. `music.wav` (optional): the two beds concatenated per `music[]` spans with 400 ms crossfades and a **1 s drop-out before every FULL_REVEAL** (VIDEO_FORMAT §5), at −26 dB.
4. One ffmpeg filter graph does ducking, mix, final loudness and mux:
```
ffmpeg -y -i video.mp4 -i narration.wav -i sfx.wav -i music.wav -filter_complex "
 [1:a]asplit=2[nv][nk];
 [3:a][nk]sidechaincompress=threshold=0.02:ratio=10:attack=15:release=450:makeup=1[duck];
 [nv][2:a][duck]amix=inputs=3:normalize=0:dropout_transition=0[mix];
 [mix]loudnorm=I=-14:TP=-1.5:LRA=11:measured_I=…:measured_TP=…:measured_LRA=…:measured_thresh=…:linear=true[out]"
 -map 0:v -map "[out]" -c:v copy -c:a aac -b:a 192k -ar 48000 -ac 2 -shortest review.mp4
```
Without music the graph drops the sidechain branch. The `loudnorm` measured values come from a first pass (`-af loudnorm=print_format=json`), so the result is genuinely −14 LUFS integrated rather than the single-pass approximation. Verification (§13) re-measures with `ebur128` on the produced file.

## 10. Captions without ASR

Burned-in 2–4-word chunks with the key word highlighted. Timing:

1. **Line anchors are exact**: every line's start and measured duration are known from the WAV.
2. **Trim silence**: `AudioStage` measures leading/trailing silence per WAV (first/last 20 ms window with RMS > −40 dBFS) and stores `leadingSilenceMs/trailingSilenceMs`; captions are laid out over the *voiced* span only. This removes the largest systematic error.
3. **Sentence anchors inside a line**: energy-based segmentation — pauses ≥ 180 ms below −38 dBFS are matched, in order, to the line's sentence boundaries (`.`/`!`/`?`; the same splitter rules as `NarrationSynthesizer.splitIntoSentences`, `app/.../video/NarrationSynthesizer.kt:479-488`, including the decimal-point guard). When the pause count equals the boundary count the sentences get exact spans; otherwise the line is treated as one span.
4. **Words inside a span**: proportional to a weight of `syllables(word) + 0.6` (syllables ≈ vowel groups; digits count as their spoken length: "91.4" → "ninety one point four" ≈ 6), with 120 ms added for a trailing comma and 250 ms for a sentence end.
5. Chunking: greedy 2–4 words, each chunk shown for ≥ 500 ms (merge shorter ones), the highlighted word = the placeholder-bearing word or the longest content word.

Is it good enough? For chunk-level captions (not per-word karaoke) the error that matters is chunk start drift. Steps 2–3 bound it to the within-span estimation error, which for spans ≤ 6 s is on the order of ±150–250 ms — below a 500 ms chunk. It is not good enough for single-word karaoke and the design does not attempt that. Escape hatch: `--align whisper` (faster-whisper `tiny`, local, MIT) is a clearly separated optional `CaptionTimer` implementation for later; it is not in the phased plan.

Hebrew captions: RTL layout via `TextLayout` with `TextAttribute.RUN_DIRECTION_RTL`, chunking on the same word basis, Noto Sans Hebrew; the move list stays LTR (the Round 10 finding: notation must be pinned LTR).

## 11. Model and process lifecycle

`ProcessManager` owns every child with a guaranteed teardown (JVM shutdown hook + `destroyForcibly` + wait 10 s + `taskkill /F /T` as last resort on Windows, because `Process.destroy()` on Windows does not kill grandchildren such as llama-server's worker threads' CUDA context reliably).

Sequence (strictly serial GPU use):

```
ANALYZE    stockfish (CPU, 8 threads)                     → analysis.json
STORYBOARD (CPU, ms)                                      → storyboard.json
SCRIPT     start llama-server → wait /health 200 (timeout 240 s) → chunks → stop → wait exit
           verify VRAM released: `nvidia-smi --query-gpu=memory.used --format=csv,noheader` < 500 MiB, else wait up to 30 s and fail loudly
AUDIO      start tts_worker → hello → synth lines (cache hits skipped) → quit → wait exit → same VRAM check
RENDER     ffmpeg (CPU; NVENC only here, when the GPU is idle)
MIX        ffmpeg
```

llama-server command (from `LLM.md`, with the local model path):
```
pc/bin/llama/llama-server.exe -m pc/models/gemma-4-26B-A4B-it-UD-Q4_K_M.gguf --jinja -c 16384 -ngl 99 --cpu-moe -fa on -ctk q8_0 -ctv q8_0 --host 127.0.0.1 --port 8080 --threads 8
```
`-ngl 99 --cpu-moe` keeps attention on the 4 GB GPU and experts on the CPU; if it OOMs at load (the spike will tell), fall back to `-ngl 20`. Health: `GET /health`; readiness also requires one 5-token completion to succeed (the server answers `/health` before the model is fully warm in some builds).

TTS worker protocol (JSON lines, UTF-8, one object per line; the worker never prints anything else on stdout — logs go to stderr):
```
→ {"cmd":"hello"}
← {"ok":true,"backend":"chatterbox_turbo","version":"…","device":"cuda","sampleRate":24000,"supportsTags":["[laugh]","[chuckle]","[gasp]","[sigh]"],"supportsExaggeration":false,"langs":["en"]}
→ {"cmd":"synth","id":"b017_l0","text":"…","lang":"en","emotion":"excited","out":"C:/…/audio/b017_l0.wav"}
← {"ok":true,"id":"b017_l0","durationMs":2310,"sampleRate":24000,"rtf":0.61}
← {"ok":false,"id":"…","error":"CUDA out of memory"}          (the client retries once on CPU via {"cmd":"device","value":"cpu"})
→ {"cmd":"quit"}
```
Backends implement `synth(text, lang, emotion) -> np.int16 mono`; emotion maps to tags (Turbo) or `exaggeration` (V3: neutral 0.5, excited 0.7, shocked 0.8) or a style prefix (VoxCPM2). Chatterbox specifics from `TTS.md` are encoded in the backend: `t3_model="v3"` for Multilingual, `dicta-1.0.int8.onnx` path passed to `add_hebrew_diacritics` for Hebrew, fp16 on CUDA, ≤ 350 chars per call (the schema's 320-char line limit guarantees it), watermark accepted (personal use). The Python env is `pc/tts/.venv` (uv, Python 3.11) created by `pc/tts/setup.ps1`; the producer checks `pc/tts/.venv/Scripts/python.exe` exists and prints the setup command if not.

Resumability: every stage is idempotent over its artifacts (§3); the TTS cache is per line hash; a crash mid-AUDIO loses at most one line. `--from audio` after editing `script.json` by hand re-synthesizes only changed lines and re-renders — the intended workflow for fixing a bad joke.

## 12. CLI

```
palaya-review <game.pgn> -o <review.mp4> [--lang en|he] [--quality fast|best]
  --game N                 which game in a multi-game PGN (default 1)
  --user NAME              viewer's name → second-person narration and board orientation
  --depth 22 --multipv 3 --threads 8 --hash 512 --movetime-cap 8000
  --threshold 50           significance threshold, cp (NarrationOptions default)
  --full-moments 3         cap on FULL-treatment moments (max 4)
  --target 8:00-14:00      duration window
  --no-llm                 template narration only (Fallback)
  --llm-url http://127.0.0.1:8080  --llm-model PATH  --llm-strict  --llm-seed N
  --tts chatterbox_turbo|chatterbox_v3|voxcpm2|fake  --voice NAME  --wpm 165
  --music PATH --music-upbeat PATH   (CC0 only; none by default)
  --encoder x264|nvenc  --fps 30  --size 1920x1080
  --work DIR               default pc/work
  --from STAGE | --only STAGE | --force
  --keep-video-only        also write the silent video.mp4 next to the output
  --dry-run                analyze + storyboard, print the beat table and duration estimate, no models
```
`--quality fast` = depth 18, x264 veryfast, MoE model; `best` = depth 26, variation re-scoring (§5.4), x264 slow, optional dense model. Exit codes: 0 ok, 2 bad input, 3 engine failure, 4 LLM failure (with `--llm-strict`), 5 TTS failure, 6 ffmpeg failure; every failure names the stage and the log file.

## 13. Testing and verification

Same standards as CLAUDE.md: a test is evidence only if it reads the artifact back; `assumeTrue` skips are forbidden in the default suite (a missing tool is a **failure** unless `-Ppalaya.noTools=true` is passed explicitly, and the run summary prints the skip count, which must be 0).

Default suite (`./gradlew :desktop:test`, no models, no GPU):
- `UciLineParserTest`: fixture transcript with MultiPV 3, mate scores, `lowerbound`, `bestmove (none)` → expected `PositionEval`s.
- `UciClientTest` (requires `pc/bin/stockfish`): mate-in-1 FEN returns `mateIn = 1` on line 1; MultiPV 3 returns 3 lines; `depth ≥ requested`; a checkmated position is short-circuited without a `go`; **stdin stays open** (a `go depth 20` on startpos returns a `bestmove` after ≥ 20 depth lines — the exact failure RUN_LOG saw).
- `AnalysisParityTest`: `fixtures/immortal.pgn` at depth 14 / MultiPV 3 → classification counts match a committed regression file `desktop/src/test/resources/immortal_depth14.json` (re-recorded deliberately when Stockfish is upgraded).
- `DirectorTest` on a synthetic report built the way `core/src/test/.../narration/NarrationFixture.kt:44-60` builds one (fake evals, forced missed forks): FULL ≤ cap; turning point is FULL and in the climax chapter; every significant ply has a beat; the checkmate has a beat; opening name present; cold open holds the climax `fenBefore`; byte-identical `storyboard.json` on repeat; estimated duration within the target window for the two fixture games; every `fallbackText` passes `NotationGuard.containsNotation == false`.
- `ScriptValidatorTest`: each rule with a positive and a negative case (leaked `Qh5`, bare `e5`, unknown `{M99}`, number 6 not in facts, ban-list phrase, 140 words in a 45-word beat, Latin text under `he`).
- `ScriptWriterTest` with `FakeChatClient`: valid on first try; invalid twice then valid (attempts = 3); invalid three times → `source: "template"` and the error list recorded; chunk boundaries respect the 16K budget (token estimate).
- `PlaceholderRendererTest`: `{M23}` → "queen to h five, check" (EN) and the Hebrew vocabulary; caption keeps `Qh5+`.
- `TimelineBuilderTest`: audio longer than the animation → beat extends, no truncation; audio shorter than a 5-move batch → padded; `hold_silence` = 3,000 ms exactly; rewind after speech; gaps 220/320 ms.
- `CaptionTimerTest`: monotone, non-overlapping, every chunk ≥ 500 ms, chunk starts inside the voiced span; on a synthetic WAV with two 300 ms silences the sentence anchors land at those silences ±20 ms.
- `FrameRendererTest`: render one frame from a known spec and sample pixels: a1 is `739552`, h1 is `EBECD0`, the eval bar's white fill height = `winPercent × barHeight` ±2 px, the badge colour at the destination square, an arrow pixel on the from→to midpoint; identical specs return the same buffer instance (reuse), a caption change returns a new one.
- `AudioStageTest` with `FakeTtsBackend` (a Python `fake` backend that writes a 440 Hz tone whose length is proportional to the text — exercised through the real JSONL protocol and a real Python process): manifest durations equal the WAV headers; RMS/peak measured by the test's own reader, not taken from the worker's reply.
- `MixStageTest` / `EndToEndTest` (requires ffmpeg): `palaya-review fixtures/immortal.pgn --no-llm --tts fake` → `ffprobe -show_streams -show_format`: h264 1920×1080 30/1, aac 48 kHz 2 ch, container duration = `timeline.totalMs` ±100 ms, frame count = `round(totalMs × 30 / 1000)` ±1; `ffmpeg -af ebur128` integrated loudness −14 ±1 LUFS; the extracted audio's near-silent ratio < 60% and the tone is present at every line's `audioStartMs` (cross-correlation against the known tone, the Round 11 technique). The MP4 is **kept** in `pc/work/tests/<timestamp>/` for the owner to open.

Live suite (`./gradlew :desktop:liveTest`, runs the models; never part of the default run):
- `LlamaLiveTest`: real llama-server on the immortal storyboard; ≥ 90% of beats valid on first attempt; tok/s and total time written to `metrics.json`; the produced `script.json` is kept for the task-55 move-by-move check.
- `TtsLiveTest`: real backend synthesises 10 representative lines (coordinates, a two-decimal accuracy, a question, a `[gasp]` tag, one 320-char line): duration within [0.5×, 2.0×] of `estimateSpeechMs`, RMS ≥ −30 dBFS, peak ≤ −1 dBFS, no clipped samples, near-silent ratio < 60%, RTF recorded; WAVs kept under `pc/work/tests/` and listed in the summary so someone **listens**.
- `FullLiveEndToEnd`: the whole thing on `fixtures/chesscom_style_game.pgn`; the artifact plus `metrics.json` are the done-condition evidence for phase milestones.

Every test prints measured numbers; the phase reports quote those numbers and the artifact paths, never "BUILD SUCCESSFUL".

## 14. Phased build plan

Estimates are working-session estimates for one agent; each phase ends with a kept artifact.

| Phase | Scope | Done when | Est. |
|---|---|---|---|
| **P0 Skeleton** | `:desktop` module, CLI, `WorkDir`, `UciClient`, `UciLineParser` copy, `AnalysisStage`, `ReportFactory`, `--dry-run` | `palaya-review immortal.pgn --dry-run` writes `analysis.json`/`report.json`; `UciClientTest` + `AnalysisParityTest` green; per-ply seconds in `metrics.json`; `:core:test` still 279/0 | 0.5 day |
| **P1 Crude end-to-end** | Director v0 = a thin adapter that turns `VideoScriptGenerator.generate(...)` segments (`BoardDirective`s at `NarrationContract.kt:23-57`) into beats; `Fallback` only; `TtsClient` + `fake` backend + the Kokoro baseline or the spike's first Chatterbox backend; `FrameRenderer` with board, pieces, eval bar, last-move highlight, arrows, a plain caption bar; `RenderStage` rawvideo pipe; `MixStage` narration only | `palaya-review immortal.pgn -o out.mp4 --no-llm` produces an MP4 that `EndToEndTest` verifies (duration, streams, loudness, audio present) and the owner watches. Ugly is fine; complete is required | 1.5 days |
| **P2 Director v1** | Tiers + FULL cap, cold open, intro/recap cards, SKIP batches, find-the-move hold, variations from `SimulationBuilder` and post-move PVs with rewind, eval graph, duration budget, `SignificanceFilter` extraction in core | `DirectorTest` green; storyboard for both fixtures within 8–14 min estimated; a P1-style video with the new structure, watched | 2 days |
| **P3 LLM writer** | `LlamaServerProcess`, `HttpChatClient`, prompts, chunking, `ScriptValidator`, retries, fallback accounting, `PlaceholderRenderer` | `LlamaLiveTest` ≥ 90% first-try valid; a script with < 10% template fallback on the chess.com fixture; task-55 style move-by-move check passes (no invented moves/evals); measured tok/s recorded | 2–3 days |
| **P4 Voice** | Spike's chosen backend, emotion→tag mapping, per-line silence trimming, `CaptionTimer`, word captions with highlight | `TtsLiveTest` numbers within bounds; captions visibly in sync on a watched video; RTF recorded | 1–2 days |
| **P5 Polish** | Badge pop, shake, climax zoom, player-card flash, panel move list, SFX, music ducking, `loudnorm` two-pass, NVENC option | ebur128 −14 ±1 LUFS; SFX audible at cue times (cross-correlation); render fps recorded | 1–2 days |
| **P6 Hebrew** | `HebrewSpokenVocabulary`, `persona.he.md`, validator Hebrew rule, RTL captions/font, Chatterbox V3 or VoxCPM2 backend with `dicta` | A Hebrew video whose narration a Hebrew speaker (the owner) confirms is intelligible; token/leak checks green | 1–2 days |
| **P7 Hardening** | Resumability edge cases, `--from/--only`, teardown on Ctrl-C, docs (`docs/PC_PRODUCER.md` usage), `metrics.json` summary | Killing the process in every stage and re-running resumes without redoing finished stages | 0.5–1 day |

### Risks

- **Chatterbox VRAM on 4 GB** (Multilingual V3 measured 3.6 GB per `TTS.md`): fp16 plus a 24-kHz vocoder may OOM on long lines. Mitigation: the 320-char line limit, the worker's automatic CPU fallback (Nano/Turbo on CPU is ~3× real time per `TTS.md`), and the cache so a retry costs one line.
- **LLM speed and load** (unmeasured): if `--cpu-moe` at 16K context is < 5 tok/s the script stage becomes 20+ min; mitigation: smaller chunks, `-c 8192` with tighter facts, or Gemma 4 12B fallback. The spike decides.
- **LLM grounding**: the validator cannot catch a *plausible* wrong claim expressed without any square or number ("the knight was hanging"). Mitigation: rule 7 (anchoring) plus the fact-sheet design where every tactic fact names the pieces; the task-55 spike's manual move-by-move check is the real test, and `report.json` exists for it.
- **Hebrew**: `NarrationLocales` is English-only; Gemma 4's Hebrew is unbenchmarked (`LLM.md`); Chatterbox Hebrew needs the `dicta` ONNX or it produces gibberish (`TTS.md`). P6 is deliberately last and behind the spike.
- **Java2D text quality**: acceptable at ≥ 22 px; if the owner finds the panel text soft, the fix is rendering the panel at 2× and downscaling with bilinear interpolation, a contained change in `PanelLayer`.
- **Pipe throughput**: 134 GB through stdin; if Windows pipes turn out slower than ~500 MB/s the encode becomes pipe-bound (~4–5 min). Mitigation: `-pix_fmt yuv420p` conversion in the JVM (halves the bytes) or NVENC.
- **Gradle lock**: building `:desktop` while the Android session builds corrupts `app/build` state (HANDOFF trap). Producer runs from `installDist`; the design doc states it, the CLI banner prints it.
- **Duration drift**: audio-driven timing means the final video can exceed 14 min if the model writes long; the word budgets are validated, so the ceiling is bounded (≈ 45 beats × max words / wpm ≈ 13 min plus visuals), and `--target` demotes before the LLM runs.

## 15. Changes to `:core` (minimal, additive, Android-safe)

1. **`core/narration/SignificanceFilter.kt`** (new, ~40 lines): public object with `isSignificant(a, annotations, sequences, thresholdCp)` and `applyThreshold(candidates, annotations, thresholdCp)`, moved out of `VideoScriptGenerator.kt:413-440`; the generator calls it. Pinned by the existing `NarrationSignificanceTest`. No behaviour change.
2. **Make `NarrationVocabulary` public** (`core/narration/NarrationVocabulary.kt:12`, currently `internal`) so the Director uses the same `standing/lossSeverity/materialPayoff` cut-offs instead of copying them. `PhrasePicker` stays internal.

Everything else the producer needs is already public: `GameAnalyzer`, `MoveClassifier`, `MotifDetector`, `StaticExchangeEvaluator`, `OpeningBook`, `TacticSignificance`, `MoveSequenceDetector`, `SimulationBuilder`, `TacticReferenceLibrary`, `SpokenChess`, `NotationGuard`, `EnglishNarration` (+ `Vocabulary`), `Sentence`, `NarrationStrings`, `VideoScriptGenerator` (+ `estimateSpeechMs`), `EvalFormat`, `WinProbability`, `PgnParser`, `Position`. The Android app is not touched.

Two items deliberately left out of `:core` for now: the copied `UciLineParser` (moving it means touching `:engine`) and `HebrewSpokenVocabulary` (implemented against the core interface in `:desktop`; promoted to `:core` when the app wants Hebrew).

### Critical Files for Implementation
- `C:\Claude\ChessAnalyzer\core\src\main\kotlin\net\palaya\chessanalyzer\core\narration\VideoScriptGenerator.kt` (beat selection, significance, excursion shape, fallback sentences; the extraction in §15)
- `C:\Claude\ChessAnalyzer\core\src\main\kotlin\net\palaya\chessanalyzer\core\narration\NarrationContract.kt` (`BoardDirective`, `ScriptSegment`, `NarrationOptions` — the visual vocabulary the storyboard extends)
- `C:\Claude\ChessAnalyzer\app\src\main\kotlin\net\palaya\chessanalyzer\data\AnalysisService.kt` (the exact eval loop, terminal short-circuit and `PositionEval` mapping the UCI adapter must reproduce)
- `C:\Claude\ChessAnalyzer\app\src\main\kotlin\net\palaya\chessanalyzer\video\BoardFrameRenderer.kt` (palette, classification colours, layout and panel logic to port to Java2D) together with `app\src\main\kotlin\net\palaya\chessanalyzer\video\SegmentFrameBuilder.kt` (directive→frame animation logic)
- `C:\Claude\ChessAnalyzer\core\src\main\kotlin\net\palaya\chessanalyzer\core\narration\SpokenChess.kt` with `EnglishNarration.kt:759-845` and `NotationGuard.kt` (placeholder rendering and leak detection)