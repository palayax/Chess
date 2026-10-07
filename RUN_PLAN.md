# RUN_PLAN — Android Chess Game Analysis App

**Goal:** Fully working, signed, publishable APK. Ingests PGN (file or Android share),
analyzes with latest Stockfish, replays the game with chess.com-style per-move
annotations, tactics/missed-tactics detection for both sides, missed-tactic
simulation, and a game rating. Chess.com UI/UX as design reference.

## Stated assumptions (made without asking, per protocol §0.5)

1. **Stockfish delivery.** Official SF19 Android binaries are 100 MB (embedded NNUE)
   and `ET_EXEC` non-PIE — unshippable and unsafe to exec on modern Android.
   Instead: vendor SF19 **source** (official repo, tag `sf_19`), compile via NDK/CMake
   into `libstockfish.so` with a JNI UCI bridge, built with `-DNNUE_EMBEDDING_OFF`.
2. **"Download from official site and update as needed"** is implemented as: the NNUE
   net (`nn-1a298aa575a0.nnue`, 79 MB) is downloaded on first run from the official
   endpoint `tests.stockfishchess.org/api/nn/`, SHA-verified, cached, and
   re-checked/updated in-app. The app also polls the official GitHub releases API for
   newer Stockfish versions and surfaces them.
   **Constraint (Android platform, not a choice):** API 29+ forbids executing
   downloaded binaries, so the *engine code* itself updates via app update; the
   *net* updates fully at runtime.
3. **ABIs:** `arm64-v8a`, `armeabi-v7a` (real devices) + `x86_64` (emulator, so the
   build is verifiable here without physical hardware).
4. **"User" vs "opponent"** = the PGN side selected on import; auto-detected from
   chess.com PGN `[White]`/`[Black]` tags when a username is configured, else user picks.
5. **Game rating** = chess.com-style accuracy → estimated performance Elo, computed
   from win-probability loss per move. It is an *estimate*, labelled as such in-app.
6. **Not** a chess.com API client — no login, no scraping. PGN in, analysis out.
7. **Design reference only** — chess.com-like layout/palette/feel; no copied assets,
   logos, or trademarks (keeps it publishable).
8. **Signing:** a release keystore is generated locally; the signed APK is the
   deliverable. Keystore + passwords handed to user, never committed.

## Subtasks

| # | Subtask | Done-condition (mechanical) | Cx | Est. tok |
|---|---------|------------------------------|----|----------|
| 1 | Gradle/Compose scaffold, AGP 8.4/Kotlin 1.9/compileSdk 34, manifest, intents | `gradle assembleDebug` exit 0 | mod | 25k |
| 2 | Vendor SF19 source + CMake + JNI UCI bridge | APK contains `lib/*/libstockfish.so` for 3 ABIs | cplx | 60k |
| 3 | Kotlin chess core: board, legal movegen, FEN, SAN, PGN parser | JVM unit tests pass; **perft** to depth 5 matches known values | cplx | 70k |
| 4 | UCI engine service (multipv, depth/time control, lifecycle) | Instrumented test: engine returns bestmove for start pos | mod | 40k |
| 5 | Analysis pipeline: win%-loss, chess.com classification (Brilliant→Blunder), accuracy, est. Elo | Unit tests on fixture games reproduce expected labels | cplx | 55k |
| 6 | Tactics/pattern detection: found + missed, both sides (fork, pin, skewer, discovered, hanging, back-rank, deflection, decoy, overload, mate nets, sac) | Unit tests over curated FEN corpus | cplx | 65k |
| 7 | Missed-tactic simulation flow (play out engine PV as guided replay) | Instrumented test drives sim to completion | mod | 35k |
| 8 | Compose UI: board, eval bar, move list w/ badges, comment cards, report screen — chess.com palette | App launches, screens render on emulator | cplx | 75k |
| 9 | Ingest: SAF file picker + ACTION_SEND/ACTION_VIEW share from chess.com et al. | Instrumented test: share intent → parsed game | mod | 30k |
| 10 | Net download + engine version check UI | Instrumented test w/ stubbed download | mod | 25k |
| 11 | Emulator smoke run end-to-end + screenshots | Real PGN analyzed on emulator, screenshot captured | mod | 40k |
| 12 | Release signing + `assembleRelease` | Signed APK exists, `apksigner verify` passes | triv | 10k |
| 13 | Fresh-context adversarial review vs this plan | Reviewer reports no unaddressed gaps | mod | 30k |

**Rough total estimate:** ~560k tokens.

## Risks
- NDK download (~2 GB) — in progress, blocks subtask 2.
- SF19 source is C++20 + arch-dispatch; ARMv7 may need NEON flags tuned or may be dropped.
- Emulator boot on this host is unverified; fallback is unit-test-only verification + unsigned-install instructions.

---

## STATUS as of session 1, after ~2h

| # | Subtask | Status |
|---|---------|--------|
| 1 | :app scaffold + design system | **DONE** (placeholder data) |
| 2 | Stockfish NDK/JNI, 3 ABIs | **DONE + verified on device** |
| 3 | :core chess rules + PGN (perft-exact) | **DONE** |
| 4 | UCI engine service | **DONE + 6/6 on-device tests** |
| 5 | Analysis pipeline (classify/accuracy/rating) | agent running |
| 6 | Tactics detection | agent running |
| 7 | Missed-tactic simulation | core half in 5; UI half pending integration |
| 8 | Compose UI | shell DONE; real-data wiring pending |
| 9 | PGN ingest (SAF + share intents) | manifest DONE; parsing wiring pending |
| 10 | Net download + update check UI | engine API DONE; UI pending |
| 11 | End-to-end emulator run + screenshots | pending |
| 12 | Release signing + signed APK | keystore + signingConfig DONE; build pending |
| 13 | Adversarial review | pending |
| 14 | *(added)* Original vector piece artwork | agent running |

### Remaining critical path
**Integration** is now the only thing between here and a working APK:
1. Replace `app/.../ui/model` placeholders with real `:core` types.
2. Data layer: PGN import -> parse -> engine analysis loop (per-ply, MultiPV 3, depth 18)
   -> `GameReport`, with progress reporting, cancellation and result caching.
3. Engine lifecycle + net download gating (must not analyse before a verified net exists —
   see the exit() defect in RUN_LOG).
4. Wire all five screens to real data; implement the "Show me" simulation UI.
5. Settings persistence (DataStore): depth, MultiPV, username for side auto-detection.

### Correction to earlier assumption
The NNUE net is **98,511,183 bytes on disk**; the 78,944,870 figure from the HTTP HEAD is the
*compressed transfer* size. Both matter (data cost vs storage) and both are now in docs/PUBLISHING.md.

---

## SCOPE CHANGE — narrated video review (added mid-run by the user)

The user asked, after the analyser was working, for the analysis to be delivered as a **one-shot
narrated tutorial video** in the style of the GothamChess channel — full board simulation, mistakes
by both sides, found and missed tactics exposed, as a self-teaching artifact — **and exportable as
a video file**. A later message added: use ElevenLabs (their paid subscription) and/or open-source
AI models for professional narration.

| # | Subtask | Done-condition | Status |
|---|---------|----------------|--------|
| 14 | Narration contract | compiles, consumed by both sides | **DONE** |
| 15 | `VideoScriptGenerator` (:core) | deterministic; **regex test proves narration never contains algebraic notation** | **DONE** (151 tests) |
| 16 | `BoardFrameRenderer` + `VideoExporter` (MediaCodec/MediaMuxer) | exported MP4 opens, has video+audio tracks, correct duration/resolution | **DONE** (6/6 instrumented) |
| 17 | In-app playback (`VideoScreen`) | shares the exact renderer with the exporter | **DONE** (no UI test — noted gap) |
| 18 | ElevenLabs provider + encrypted BYO key | fallback to device voice proven by test; key ciphertext verified on device | **DONE except live API path (no valid key)** |
| 19 | Side panel: eval bar, players, ratings | frame with a **Black-favouring** eval verified visually | agent running |
| 20 | Adversarial gap review | reviewer reports no unaddressed gaps | pending |
| 21 | Final signed release APK | `apksigner verify` passes on the final build | pending |

### Decisions recorded
- **The ElevenLabs key is NOT compiled into the APK.** Published-app strings are trivially
  extracted; a baked key would let strangers drain the owner's paid quota. Device TTS is the
  default; ElevenLabs is opt-in with the user's own key in encrypted storage.
- **No generative model renders the board.** Board animation must be deterministic and legally
  correct; a text-to-video model would invent illegal positions. Open-source on-device TTS
  (Piper/Kokoro-class) is recorded as a future `NarrationVoiceProvider`, not built.

---

## Round 4 status (narration quality)

| # | Item | Status |
|---|------|--------|
| 22 | Narrated video: speed control | **FIXED** — `setSpeechRate` only affects the next `speak()` |
| 23 | Narrated video: full-screen board | **FIXED** — forced landscape; immersive alone just letterboxed 16:9 |
| 24 | Missed-tactic pivot (per-ply excursion) | **DONE** — 175 -> 181 core tests |
| 25 | Device-TTS naturalness (voice pick, rate/pitch, sentence pacing) | **DONE**, with a stated ceiling |
| 26 | Pre-generated narration + persistent store | **DONE** — `NarrationStore` in filesDir, playback prefers files |
| 27 | Phrase bank manifest + generation script | **DONE** — 1933 entries, 100% fixed-sentence coverage |
| 28 | Decimal-point sentence-split defect | **FIXED** — "59.9" was spoken as two sentences |
| 29 | On-device neural TTS (sherpa-onnx) | agent running |
| 30 | Final suite + signed APK rebuild | pending |

### Architecture decision recorded
Three narration providers behind one `NarrationVoiceProvider` interface:
**Device TTS** (free, offline, robotic) / **on-device neural via sherpa-onnx** (free, offline,
Apache-2.0, model downloaded + SHA-verified like the NNUE net) / **ElevenLabs** (BYO key).

The neural provider is intended as the shipping default because it is the only option that is
simultaneously free to the user, offline, good quality, and **licence-clean for a GPLv3 app**.
It also covers the ~5% of narration a phrase bank structurally cannot (player names, opening names,
estimated ratings are unbounded).

The 18.4 MB ElevenLabs phrase bank is therefore likely unnecessary. Its manifest and generation
script are retained — cheap to keep, and still the right answer if ElevenLabs-quality fixed lines
are ever wanted.

---

## Round 5 status — verification round

The brief for this round was explicitly *don't trust the docs*: verify the neural TTS end-to-end,
make it the default if it works, rebuild and verify the signed APK, and do a full e2e pass.

| # | Item | Status |
|---|------|--------|
| 29 | On-device neural TTS (sherpa-onnx) | **VERIFIED** — WAV pulled off device and analysed on the host, not just self-reported by the test |
| 31 | *(added)* Neural voice as the real default | **DONE** — auto-provisions on first narration; confirmed in the release build |
| 32 | *(added)* Explicit-vs-default provider choice | **FIXED** — `providerExplicitlyChosen`; the promotion can no longer override a deliberate pick |
| 33 | *(added)* StateFlow-seed race in that check | **FIXED** — decisions now read `repository.current()`, not the eagerly-seeded StateFlow |
| 30 | Final suite + signed APK rebuild | **DONE** — 181 core / 30 instrumented (0 skipped), v2+v3 signed |
| 11 | End-to-end emulator run + screenshots | **DONE** — against the signed release APK, clean install, `docs/screenshots/r5_*` |

### What changed in the assessment

The previous round recorded the neural TTS as verified. It was not — not in the sense claimed. The
evidence was the test reporting its own measurements to logcat; the WAV it was supposed to leave
behind had never been retrieved, because Gradle uninstalls the app after the instrumented run and
takes the external files directory with it. The audio turned out to be genuine, so the conclusion
stands, but it stood on nothing until this round. Recorded in RUN_LOG under Round 5.

### Newly known cost

Adding sherpa-onnx took the release APK from **17 MB to 108 MB** — ~89 MB of it is
`libonnxruntime.so` plus sherpa's JNI/C-API across three ABIs, stored uncompressed. Per-ABI splits
(~51 MB for arm64) are the mitigation and are deliberately **not** applied, because the deliverable
is one universal signed APK and this is a POC. It is the first thing to revisit if this ships.

### Deliberately not done

The "Narration ready" dialog's "instead of calling the API again" copy is wrong now that the
on-device voice is the default. Left unfixed so the delivered APK is exactly the artifact that was
verified end-to-end; it should go in with the next release build rather than after it.

---

## SCOPE CHANGE — Round 6, owner refinements

Five refinements requested after reviewing the exported video. Two required a decision because the
obvious readings differed wildly in cost; both were put to the owner and answered.

### Research findings that shaped the plan

**There is no off-the-shelf on-device Hebrew voice.** Checked all 644 model assets published on
sherpa-onnx's `tts-models` release:

| Family | Assets | Hebrew? |
|---|---|---|
| `vits-piper` | 536 (~48 languages) | **No** |
| `vits-mms` (pre-converted) | 8 — deu, eng, fra, nan, rus, spa, tha, ukr | **No** |
| `kokoro` | 6 — multi-lang covers en/es/fr/hi/it/ja/pt/zh | **No** |
| `matcha` / `kitten` / `zipvoice` | 16 | **No** |

`facebook/mms-tts-heb` exists upstream but is not converted to ONNX and would have to be converted
and hosted by us. Android's own TTS does support `he-IL`. Two further constraints matter more than
the voice: **every narration sentence is generated as English text in `:core`**
(`CommentaryGenerator`, `VideoScriptGenerator`), so Hebrew is a localisation project, not a voice
swap; and Hebrew TTS mispronounces unvocalized text badly without niqqud.

**Decision (owner): i18n groundwork now, Hebrew content later.** Make language a parameter rather
than hardcoded English, support RTL, ship a language setting that currently offers English only.

**Decision (owner): switch the default voice to Kokoro and tune the TTS process.** Note that the
Kokoro tier is already coded (`KOKORO_SPEC`) but has **never been verified** — the instrumented
tests only ever exercised Piper. Verifying it is part of the task, not an assumption.

### Already built, so not re-built

| Ask | What already exists | The actual gap |
|---|---|---|
| Per-position eval score | `PositionEval`, `EvalBar` (`formatEval`), `EvalGraph` | numeric score **per move in the list**, and both sides' scores |
| Colour coding | 11-class `MoveClassification` with colours + glyphs, used in report and `BoardFrameRenderer` | extend to **sequences** and to the review move list / eval graph |
| Tactic highlighting | tactics detected and named (Deflection, Skewer, Relative pin, Hanging piece…), "Show me" missed-tactic simulation | a **clean reference example** per pattern for learning |
| Threshold filter | nothing — `NarrationDepth` is a coarse 3-value enum | genuinely new: a centipawn threshold, default **±0.5** |

### Subtasks

| # | Subtask | Done-condition | Status |
|---|---------|----------------|--------|
| 34 | Foreground service for video export | export survives leaving the screen; instrumented test | **DONE** — FGS type `dataSync` (mediaProcessing is API 35); 30 -> 33 tests, skipped=0 |
| 35 | Narration quality: verify Kokoro, make it default, tune pacing/prosody | Kokoro synthesis verified on-device by pulled-WAV + RMS/envelope, same bar as Piper | **DONE** — af_bella @ length_scale 1.20; 33 -> 46 tests, skipped=0 |
| 36 | Per-move engine score for both sides | score visible per ply in review + video side panel | **DONE** — shared `EvalFormat`, score+swing in move list & video panel |
| 37 | Significance threshold (default ±0.5) filtering narration | only moves/sequences whose eval swing exceeds the threshold are narrated; unit-tested at the boundary | **DONE** — 50cp default, 49/50/51 boundary tested; EVERY_MOVE exempt (see note) |
| 38 | Sequence-level colour coding (red→green) | applied to move list, eval graph and video, not just report badges | **DONE** — chips, eval graph, sequence bands; glyphs retained for colour-blindness |
| 39 | Named tactic patterns + clean reference simulation **+ swing gate on tactics** | each detected pattern can play a canonical teaching example; tactics filtered by the SAME eval-swing threshold as task 37 | **DONE** — `TacticSignificance` (spec §9.6: found = MultiPV margin, missed = ply swing, mate/BRILLIANT/GREAT/MISS exempt); 28-position verified corpus (§10) played through the "Show me" screen; see RUN_LOG "Round 6, task 39" |
| 40 | i18n groundwork + RTL, language setting (English only for now) | no user-facing narration/UI string hardcoded; RTL verified | **DONE (groundwork)** — `:core` narration behind the `Sentence`/`NarrationStrings` contract with `EnglishNarration`; language setting (English only, API 33+ via `LocaleManager`, no AppCompat); RTL rendered via per-app `he-IL` locale, board pinned LTR, move-list bidi defect fixed; **Hebrew device TTS UNVERIFIED — engine reports `LANG_NOT_SUPPORTED`, no Hebrew pack in its manifest**. See RUN_LOG Round 10 |

### Sequencing constraint

These cannot be parallelised the way the earlier rounds were. Every task needs Gradle, and **two
concurrent `./gradlew` invocations against `app/build` corrupt the dex cache** in this project. So
agents run strictly one at a time. Task 40 (i18n) touches every string in the app and must run
**last**, after the features that will add new strings.

### Owner clarification (mid-Round 6)

Threshold semantics confirmed: **eval swing**, `|after - before| >= threshold`, **not** absolute
position eval. The same mechanism must also gate **known tactics** — the sample game surfaced
Deflection x5, Relative pin x3 and Skewer x3, most of which had no bearing on the result, so
detection without a significance gate is noise. Folded into task 39 rather than into the in-flight
task 37 agent, because 39 is where tactic selection lives and 37 is mid-implementation.

`TacticType` already defines 30+ named patterns (Fork, Skewer, Deflection, Greek gift, Windmill,
Smothered mate, Zwischenzug, Desperado, ...) — the reference library in task 39 has a real
vocabulary to work from.

### Verified reference corpus (built while waiting on task 37)

`fixtures/tactic_references.json` — **15 mechanically verified** teaching positions, gated by
`scripts/verify_tactic_references.py` (non-zero exit on any failure, and it **fails closed**: a
pattern with no structural check defined is rejected rather than trusted).

Why the gate exists: of the first 10 positions written by hand, **7 were wrong** — an illegal move
(the side to move was already in check), a "double check" delivering one check, a knight fork
attacking nothing, an invented knight move (e5 to f6), and a Ruy Lopez bishop "pin" that is not a
pin because the d7 pawn blocks the diagonal. Plausible-looking chess is the default failure mode
here, and this is a *teaching* feature: telling a learner "this is a fork" when it is not is the
worst outcome available. The verifier checks each pattern's own structural claim, not just legality.

### Task 40 (i18n) — constraints established before speccing

Findings from the tree, so the design does not start from guesses:

| Fact | Consequence |
|---|---|
| `minSdk = 26`, `targetSdk/compileSdk = 34` | `LocaleManager` (per-app language) is **API 33+**. Below that needs AppCompat's `AppCompatDelegate.setApplicationLocales()` or a `ContextWrapper`. |
| **No AppCompat** — pure Compose (`activity-compose` + `material3`) | The usual backport is not currently available; adding it is a real dependency decision, not a formality. |
| `android:supportsRtl="true"` already set | RTL is declared, but **nothing has ever been rendered RTL** — declaring it is not the same as it working. |
| Only `res/values` exists | No locale resources yet; clean slate. |
| **~400 narration strings are hardcoded in `:core`** | `:core` is deliberately Android-free (CLAUDE.md) and so **cannot use `R.string` at all**. This is the heart of the task, not the UI layer. |

**Android uses `iw`, not `he`, for Hebrew.** Resources placed in `values-he` are silently ignored on
many devices because the platform resolves Hebrew to the deprecated ISO code `iw`. Ship `values-iw`;
`values-he` at best serves as a fallback before an in-app language override applies. This is a
silent-failure trap — the app simply shows English and nothing reports an error.

**Design constraint that rules out the naive approach:** Hebrew has grammatical gender on verbs and
adjectives, different plural rules, and different word order. An extraction that concatenates
fragments ("White " + "played " + move) produces broken Hebrew no matter how carefully each fragment
is translated. The abstraction must key **whole sentences** by id and let a locale restructure the
sentence entirely — not substitute words inside an English skeleton. `:core` should therefore expose
a narration string *contract* (ids + parameters) with an English implementation, rather than literals
inline.

### Task 41 (Google Cloud TTS provider) — the integration landmine, found before building

| # | Subtask | Done-condition | Status |
|---|---------|----------------|--------|
| 41 | Google Cloud TTS as a 4th BYO-key provider | synthesizes real audio on-device, key never embedded, Hebrew voice reachable | pending |

Established from Google's REST reference and this repo's own code:

- Endpoint `POST https://texttospeech.googleapis.com/v1/text:synthesize`; body is
  `{input, voice, audioConfig}`; the response is **JSON** whose `audioContent` is **base64**.
- For `LINEAR16`, **Google includes the WAV header** — the decoded bytes are a full RIFF container.

**Both facts collide head-on with the existing audio guard**, which was written for ElevenLabs'
*raw PCM* stream (`ElevenLabsProvider`):

- `isPcmContentType()` **rejects `application/json`** — deliberately, because a JSON body is how that
  API reports an error. Google's *successful* response is `application/json`.
- `encodedAudioFormatOrNull()` sniffs magic numbers and **rejects `RIFF` outright** — "already a
  WAV/RIFF container; double-wrapping would corrupt it". Google's successful LINEAR16 payload starts
  with `RIFF`.

So a naive Google provider is rejected twice by this app's own defences, and the tempting "fix" —
loosening those checks — would reintroduce the exact defect they were added for: encoded audio
silently embedded as raw PCM, producing noise in a video the user paid to generate.

**Correct design: do NOT weaken the guard.** It encodes ElevenLabs' contract, not a general rule.
Google gets its own path — parse the JSON, base64-decode `audioContent`, and hand the RIFF bytes
straight to the existing `WavUtil` (which already walks RIFF chunks and validates `fmt `/`data`),
with its own validation appropriate to a container format. Per-provider response contracts, not one
shared gate.

Free tier is per **Google Cloud billing project**, not per end user — a consumer Google account
grants no quota. So the key must be BYO, reusing the Keystore-encrypted storage already built for
ElevenLabs; embedding the owner's key would let anyone with `jadx` drain their billing. ~7k
characters per game review, so 1M free chars/month is roughly 140 reviews **across all users of one
key**.

---

## SCOPE CHANGE — owner directive: ElevenLabs is out, Google is BYO-account

| # | Subtask | Done-condition | Status |
|---|---------|----------------|--------|
| 42 | **Remove ElevenLabs entirely** | no `ElevenLabsProvider`, no key UI, no references in code/docs/strings; suites green | pending |
| 41 | Google Cloud TTS, **per-user own account/key** | synthesizes real audio; key never embedded; in-app guidance for obtaining a free-tier key | pending |

### 42 — removal scope (mapped, 27 files reference it)

Code: `ElevenLabsProvider.kt` (delete), `NarrationVoiceSettings.kt`, `NarrationSettingsRepository.kt`,
`AnalysisViewModel.kt`, `SettingsScreen.kt`, `VideoScreen.kt` (the cost-confirmation dialog exists
only for ElevenLabs), `ChessAnalyzerNavHost.kt`, `NarrationCoordinator.kt`, `NarrationVoiceProvider.kt`,
`VideoExporter.kt`, `NarrationStore.kt`, `DeviceTtsProvider.kt`, `NeuralTtsProvider.kt`,
`VideoPlayerController.kt`, `strings.xml`.
Tests: `NarrationSettingsRepositoryTest`, `ResumeAndAudioGuardTest`, `NarrationCacheInstrumentedTest`,
`VideoExporterInstrumentedTest`.
Docs: `PUBLISHING.md` §7, `NEURAL_VOICE.md`, `PHRASE_BANK.md`, `HANDOFF.md`, About-screen licences.

**Also dies with it: the phrase bank.** `core/.../narration/PhraseBank.kt`,
`scripts/generate_phrase_bank.sh`, `phrasebank/manifest.tsv` (1933 entries) and `docs/PHRASE_BANK.md`
exist *solely* to pre-generate ElevenLabs audio. It was already recorded as "likely unnecessary" once
the neural voice landed; with ElevenLabs gone it is unambiguously dead. **Owner confirmed removal.**

Worth recording what is being thrown away, because real work went into it: 1933 manifest entries,
93,301 characters, measured coverage of 100% of fixed sentences / 63% verbatim / 95%
verbatim-or-stitchable across 18 generated scripts, and a resumable generator that refuses safely
without a key. **No audio was ever generated**, so nothing was ever spent on it. It is superseded on
every axis by the on-device neural voice: free, offline, no key, and it also covers the ~5% a phrase
bank structurally cannot (player names, opening names, unbounded ratings).

**Keep** the Keystore-backed `EncryptedSharedPreferences` machinery — the security rationale is
unchanged and task 41 needs exactly it for the Google key. Repurpose, do not delete.

**Expect the instrumented test count to DROP.** Several tests exist only to prove ElevenLabs
fallback/key behaviour. A lower number after this task is correct; it must be reported as such and
not disguised by padding.

### 41 — revised by owner: every user brings their own Google account

Confirms the BYO design and rules out a shared key. Additionally: **ship in-app guidance** for
creating a free-tier key — a short wizard/helper rather than a docs link, since obtaining a Google
Cloud API key is genuinely fiddly (create project, enable the Text-to-Speech API, create credentials,
optionally restrict the key).

Note the guard analysis recorded above for task 41 **changes** once ElevenLabs is removed:
`encodedAudioFormatOrNull` / `isPcmContentType` live in `ElevenLabsProvider` and encode *that* API's
raw-PCM contract. They go with it. Google's path is therefore not "bypass the guard" but simply the
correct one from the start: parse JSON, base64-decode `audioContent`, hand the RIFF bytes to
`WavUtil`, which already walks RIFF chunks and validates `fmt `/`data`.

### 41 — friction check: Cloud TTS requires BILLING ENABLED, even for the free tier

Verified against Google's own docs before designing the in-app wizard, because a wizard that walks a
user into a dead end is worse than no wizard.

To call Cloud Text-to-Speech at all, the project must have **billing enabled** — the free tier is a
discount on a billing account, not an alternative to one. So each user's real path is:

1. create a Google Cloud account, 2. create a project, 3. enable the Text-to-Speech API,
4. **enable billing / attach a payment method**, 5. create an API key, 6. restrict it to that API.

Step 4 is the problem. For the project owner this is trivial and the free tier then covers roughly
140 full reviews a month at top quality. For a general chess player it is a hard stop — very few will
attach a card to watch a game review.

**Consequence for the design (not a blocker, a positioning change):**

- The **on-device neural voice stays the default** — free, offline, no account, no card, no quota.
- **Cloud TTS is an opt-in upgrade** for power users and, importantly, the only high-quality
  **Hebrew** path currently available (Chirp 3: HD covers `he-IL`; no on-device model does).
- The wizard must state the billing requirement **up front**, before a user invests six steps in it,
  and must say plainly that exceeding the free tier bills *them*.
- New Google Cloud customers get $300 in credits, which is worth mentioning but is a trial, not a
  free tier.

**Zero-friction Hebrew alternative worth keeping in scope:** Android's own Google TTS engine supports
`he-IL` with no key, no account and no billing. Lower quality than Chirp 3: HD, but it is the only
Hebrew option a normal user will actually reach. Sensible shape: device TTS as the Hebrew default,
Cloud TTS as the quality upgrade for whoever sets up a key.

### Owner decision: device TTS is the Hebrew default, Cloud TTS is the upgrade

Confirmed. Resulting narration matrix once tasks 41/42/40 land:

| Language | Default | Upgrade |
|---|---|---|
| English | **On-device neural (Kokoro)** — free, offline, no account | Cloud TTS (BYO key) |
| Hebrew | **Android device TTS (`he-IL`)** — free, no key, no billing | Cloud TTS Chirp 3: HD (BYO key) |

Rationale: no on-device neural model exists for Hebrew (checked all 644 sherpa-onnx `tts-models`
assets), and Cloud TTS needs billing enabled, which most users will not do. Device TTS is the only
Hebrew path a normal user actually reaches.

**This must be PROVEN, not assumed — it is a live risk, not a formality.** Google TTS is installed on
the emulator (`com.google.android.tts`, `codePath=/product/app/GoogleTTS`,
`versionName=googletts.google-speech-apk_20230123.02_p3.506463867`), so it is testable here. But
Android TTS returns `LANG_MISSING_DATA` / `LANG_NOT_SUPPORTED` when a locale's voice data is not
installed, and this emulator has no Play Store to fetch it from. So "Google TTS supports Hebrew" may
well be true in general and still produce **silence or an error** on this device.

Required evidence, the same bar Round 5 set for Piper and Round 6 for Kokoro: synthesize a real
Hebrew sentence, **pull the WAV off the device, and verify duration AND RMS on the host**. If Hebrew
voice data cannot be obtained on the emulator, say so plainly and mark the Hebrew default as
**unverified on real hardware** — do not infer it from `isLanguageAvailable()` returning a hopeful
constant.

### Emulator trap confirmed — re-seed the NNUE net before ANY manual UI driving

Verified first-hand, not taken on report. After a `connectedDebugAndroidTest` the app is gone and so
is its `filesDir`:

```
$ adb shell pm list packages | grep chessanalyzer      # (nothing)
$ adb shell run-as net.palaya.chessanalyzer ls ...      # run-as: unknown package
$ adb shell ls -l /data/local/tmp/nn-1a298aa575a0.nnue  # 98511183 bytes - SURVIVES
```

The app downloads the ~98 MB Stockfish net at runtime into its own `filesDir`, so a fresh install
starts with nothing and a hand-driven smoke test will sit on "Preparing engine…" re-downloading it.
Seed it from the copy already on the device instead:

```bash
# 1. LAUNCH THE APP ONCE FIRST - filesDir does not exist until then, and run-as fails
#    with "No such file or directory", which reads like a permissions problem and is not one.
adb shell am start -n net.palaya.chessanalyzer/.MainActivity
# 2. then copy locally (NOT a 98 MB download)
adb shell 'run-as net.palaya.chessanalyzer sh -c "cat /data/local/tmp/nn-1a298aa575a0.nnue > /data/data/net.palaya.chessanalyzer/files/nn-1a298aa575a0.nnue"'
```

Two gotchas, same family as the existing "Gradle uninstalls the app after
`connectedDebugAndroidTest`" note: `filesDir` only exists post-launch, and **every** connected test
run wipes the seed again. This bites specifically on the final signed-APK smoke test. Note `run-as`
needs a debuggable build — it does not work against the release APK, which must download the net for
real (as it did in Round 5).

Credit: flagged by the peer session working on the panel-label fix; confirmed here independently.

---

## SCOPE CHANGE — Round 12, PC video producer ("option 1")

**Owner decision (2026-09-25):** the videos are for the owner's own use, so build **option 1**: a PC
program that takes a game and outputs a finished, narrated analysis MP4. The Android app stays as-is.
Two explicit quality asks on top of the port:

1. **A better TTS model** than Kokoro, meaning better narration.
2. **A better LLM** writing the commentary, rather than templated sentences.

**Target:** the result should feel like a GothamChess game-analysis video (or similar). That means
we copy the *format and energy*: storytelling, personality, reactions, arrows, eval bar, and pacing.
**We do not clone Levy Rozman's voice, name or branding.** Doing so would be impersonation, and it
is out of scope whatever the use.

**Hard constraints carried over:** free, risk-free, no credit card, no cloud APIs; everything runs
locally.

### Target hardware (measured, not assumed)

| Part | Value | Consequence |
|---|---|---|
| CPU | i9-10885H, 8C/16T (laptop) | CPU inference is viable for ≤~30B-class MoE models |
| RAM | 31.7 GB | colibri-class giant MoE (~0.05–0.1 tok/s at this size) is **impractical**; a mid-size MoE in llama.cpp is the realistic ceiling |
| GPU | GTX 1650 Ti Max-Q, **4 GB VRAM**, Turing (fp16, no bf16) | Small TTS models can use CUDA; LLMs mostly stay on CPU |
| Disk | 415 GB free | Not a constraint |
| Tooling | Python 3.13, uv, git, JDK 17; **no ffmpeg, no ollama yet** | Both must be installed (free, no account) |

### Subtasks

| # | Subtask | Done-condition | Status |
|---|---------|----------------|--------|
| 50 | Research: best free local expressive TTS for this hardware (EN + HE) | shortlist with licence, VRAM/CPU fit, speed; claims sourced | **done** → docs/pc_research/TTS.md |
| 51 | Research: best free local LLM for this hardware + grounding strategy | shortlist with measured-or-sourced tok/s on 32 GB / 4 GB VRAM | **done** → docs/pc_research/LLM.md |
| 52 | Research: anatomy of a GothamChess-style analysis video | concrete, buildable format spec | **done** → docs/pc_research/VIDEO_FORMAT.md |
| 53 | Design (Fable 5.1, high): PC producer architecture | design doc in `docs/PC_PRODUCER_DESIGN.md` | **done** |
| 54 | Spike: TTS samples on THIS PC, candidate models vs Kokoro baseline | WAVs the owner can hear; duration + RMS checked; RTF measured | **done**, samples sent; awaiting owner's ear |
| 55 | Spike: LLM commentary on a real fixture game, grounded in engine facts | script checked move-by-move against the engine facts: no invented moves or evals | pending |
| 56 | Build the producer end-to-end | `palaya-review game.pgn -o review.mp4` produces a watchable video | pending |

**The samples come before the build.** "Better narration" is judged by ear, so the owner hears the
candidate voices and a sample script before we commit to a pipeline.

### Build phases, from the design (docs/PC_PRODUCER_DESIGN.md §14)

The design decisions:
- **Orchestration:** a Kotlin/JVM `:desktop` module, reusing `:core` unchanged apart from 2 additive changes.
- **Python:** only the TTS worker (JSONL over stdio).
- **LLM:** llama-server over HTTP.
- **Rendering:** Java2D frames piped as rawvideo into ffmpeg.
- **Caching:** every stage writes to `pc/work/<gameId>/`.

| Phase | Scope | Done when | Status |
|---|---|---|---|
| P0 | Skeleton: `:desktop`, CLI, UCI client, analysis stage, `--dry-run` | `analysis.json`/`report.json` written for immortal.pgn; UciClient + parity tests green; `:core:test` still 279/0 | **done** (core now 282 after 2 bug fixes) |
| P1 | Crude end-to-end: template narration, Java2D board, ffmpeg mux | `--no-llm` MP4 verified by ffprobe + loudness + audio-present test; watched | **done** |
| P2 | Director v1: tiers, cold open, find-the-move, variations + rewind, recap | DirectorTest green; 8–14 min estimate on both fixtures | **done** |
| P3 | LLM writer: prompts, placeholders, validator, retries, fallback | ≥ 90% first-try valid; move-by-move check passes; tok/s measured | pending |
| P4 | Voice: chosen backend, emotions, captions | TtsLiveTest bounds; captions in sync | pending |
| P5 | Polish: badges, shake, zoom, SFX, music ducking, loudnorm | −14 ±1 LUFS; SFX at cue times | pending |
| P6 | Hebrew | owner confirms intelligibility | pending |
| P7 | Hardening and resumability | kill/resume at every stage | pending |

**Ordering:**
- The LLM spike (task 55) runs on P0's `report.json`, after the TTS spike frees the GPU.
- Gradle stays strictly one invocation at a time.

---

## SCOPE CHANGE — Round 13, MOBILE focus (owner, 2026-10-03)

**Owner direction.** The PC producer (Round 12) is **paused**. P2 and the LLM spike were stopped and left nothing; P0/P1 are done and stay. Focus is the Android app.

The owner tested the app on a real Android phone. Feedback: **the UI/UX is a bit too complex, so make it simpler and more user-friendly.** They asked to continue with all the improvement ideas. Two constraints:
- **Drop the Google Cloud voice option.** A big APK is fine.
- **Everything works locally, with no external APIs.**

### Decisions I made (flagged for the owner to overrule)

| Decision | Reason |
|---|---|
| **Cut "import by username"** (Lichess/chess.com) | It calls external APIs, which the owner ruled out |
| **Bundle the Stockfish net and the voice model in the APK** so the first launch needs no download | "Everything local" plus "big APK is fine". It also removes the slowest first-run step, which is itself UX complexity. Reversible |
| UX redesign goes to a **Fable 5.1 / high** design agent first | Owner's standing instruction for complicated design tasks |

### Subtasks

| # | Subtask | Done-condition | Status |
|---|---------|----------------|--------|
| 60 | Remove Google Cloud TTS entirely: provider, setup wizard, key storage (including the plaintext fallback), strings, tests, docs, `NarrationProviderChoice.CLOUD` | no Cloud code; `:core`/`:app` tests green; no network call remains except the (soon-removed) model downloads | DONE (2026-10-03) |
| 61 | Backup rules: exclude `tts_models`, the narration cache and the key prefs from cloud backup and device transfer | rules updated; verified with `bmgr`/dumpsys or a rules lint | DONE (2026-10-03) |
| 62 | Re-run `:app` tests after the core mate-sign and PGN fixes | 77+ tests, 0 failures, skipped read from XML | DONE (2026-10-03) |
| 63 | UX redesign: simpler flow and settings, written as a design | `docs/MOBILE_UX_DESIGN.md` | **done** (Fable) |
| 64 | Core: better pacing for the app video (tiers, cap on dramatic moments, no missed-line walks for inaccuracies) | the 17-move fixture comes out near 3–6 min, not 11; tests | **done**: 17-move game 629 s → 314 s; brilliancy fix in progress |
| 65 | Core: clean up tactic detection (confident and significant only, max 2 per ply, mate first) | 17.Rd8# no longer tagged Fork/Hanging/Skewer; tests | **done**: 17.Rd8# → Mate net only; 15.Bxd7+ → Clearance only; corpus 28/28 |
| 66 | UX implementation per the design | screens simplified; emulator screenshots | pending |
| 67 | "Train on your own blunders": puzzles from the user's mistakes | playable from the report; tests | pending |
| 68 | Bundle models in the APK (net + voice), with first-run extraction and progress | cold install works in airplane mode | pending |
| 69 | Smaller items: share video, export-time estimate, recap card, accessibility/font-scale pass | each verified on the emulator | pending |
| 70 | Final: signed release APK, full e2e pass on the emulator, fresh screenshots, docs | APK verified; RUN_LOG updated | pending |

**Sequencing:** only one `./gradlew` at a time, so edit agents run one after another. Read-only design agents can run alongside.

### UX design accepted (docs/MOBILE_UX_DESIGN.md)

Root cause per the audit: not the number of screens, but landing on the board after analysis instead of the report, 26+ engine/TTS settings (two dead), five data points per move chip, and jargon (PGN, MultiPV, centipawns, plies).

The owner's open questions were answered with the design's recommended defaults:
1. Land on the **Summary**, not the board.
2. Add a **"Which side were you?"** chooser on the Summary.
3. Playback speed becomes a **player cycle button**, not a setting.

Implementation order (design §7). Each step is one agent, one Gradle build at a time:

| Step | Scope | Status |
|---|---|---|
| U1 | Copy and resources pass; move hardcoded English into strings | **done** |
| U2 | Navigation shell: land on Summary, back arrows, error state | **done** |
| U3 | Home | **done** |
| U4 | Analysing | **done** |
| U5 | Summary hub, with the side chooser | **done** |
| U6 | Board | **done** |
| U7 | Video | **done** (R2) |
| U8 | Walkthrough | **done** (R2) |
| U9 | Settings (3 visible rows plus Advanced) and repository cleanup | **done** |
| U10 | Accessibility, font-scale and RTL pass, screenshots `r13_*` | **done** (R6a) |

**Gating:** task 60 (Cloud removal) must land before U9. Core tasks 64 and 65 run before the UX steps, because the Summary and video depend on the cleaner tactic data and pacing.

### Practise-your-mistakes design accepted (docs/PRACTICE_DESIGN.md), task 67

The feature is entirely local, with no engine call in v1. Puzzles are the user's own MISTAKE/MISS/BLUNDER positions (capped at 5), judged from the cached MultiPV lines.

The selection rule only admits positions where the cache can decide, so a wrong answer is never a guess. The design's two open questions were answered with its defaults: hide Practise on "Not me", and never turn inaccuracies into puzzles.

| Step | Scope | Status |
|---|---|---|
| P1 | Core: `CandidateLine` plus `MoveAnnotation.candidateLines`, filled by `GameAnalyzer` | pending |
| P2 | Core: `PracticeSelector` and `PracticeJudge` with boundary tests, spec §11 | pending |
| P3 | App: route, `PracticeScreen`, ViewModel (after U5/U6) | pending |
| P4 | App: Summary entry card, RTL/font pass, screenshots | pending |
| P5 | Optional: persist solved plies | pending |

**Sequence:** U3/U4 (running) → P1+P2 (core) → U5 → U6 → P3+P4 → U7 → U8 → U9 → bundling → U10 and final.

### Bundled-models design accepted (docs/BUNDLED_MODELS_DESIGN.md), task 68

The net (`nnue/…` in `:engine`) and the Kokoro voice (a plain `.tar` in `:app`) ship in the APK as **stored** assets. The net is copied once to `filesDir` and SHA-256-verified, because Stockfish needs a real file path. The voice is extracted once.

One "Setting up (one time)…" phase runs inside the Analysing screen before the first analysis. **INTERNET and ACCESS_NETWORK_STATE are removed from the manifest**, with host and instrumented tests that prove it. Piper is dropped. APK ≈ 365 MB.

The owner's two open questions were answered with the design's defaults: drop Piper, and set up before the first analysis.

| Step | Scope | Status |
|---|---|---|
| B0 | Repo prep: gitignore, `MODELS.lock`, `scripts/fetch_models.sh`, fetch and pin | **done**, verified |
| B1 | `:engine`: assets, `BundledNetProvider`, engine tests | **done** |
| B2 | `:app`: installer, `FirstRunSetup`, remove downloads/permissions/tier UI (back-to-back with B1; sequence with U9) | **done** |
| B3 | `:app` instrumented tests rewritten for bundling | **done** |
| B4 | Docs, licence texts (including the espeak-ng GPL gap), delete the push scripts | pending |
| B5 | Release build and the offline evidence run | **done** (R7: signed release APK, airplane-mode cold install, narrated MP4 measured; RUN_LOG R7) |

**Flagged licence gap (pre-existing):** `espeak-ng-data/` (18 MB) is GPL-3.0-or-later (from memory, to be verified). The app already ships it, via the Kokoro download, but the About text and `NEURAL_VOICE_LICENSE.txt` never mentioned it. B4 adds the attribution.

---

### Owner rule added 2026-10-04: chess.com is the UX reference

For every UI/UX decision not clearly reflected in or derived from the owner's own goals, chess.com is the reference. Owner goals always win. Reference only: patterns, never assets, copy, icons, logos or screenshots.

- **Recorded in:** `CLAUDE.md` ("Design reference"), and in memory (`feedback-chesscom-ux-reference.md`).
- **Alignment audit (Fable 5.1, high):** a read-only agent audits our designs and screens against chess.com's public patterns and returns `docs/CHESSCOM_REFERENCE_ALIGNMENT.md`.
- **Effect on the queue:** it can change U7 (Video), U8 (Walkthrough), U9 (Settings), P3/P4 (Practise) and U10 before they are built. It may also yield a short list of rework items for the screens already built (U2–U6), and those are ranked by user impact.
- **Gate:** U7 onwards waits for this audit. The U6 agent's final verification is unaffected.

### Chess.com alignment audit accepted (docs/CHESSCOM_REFERENCE_ALIGNMENT.md)

Verdict: **Home, key moments, board, move chips and the class names are already aligned with chess.com's conventions.** Divergences are deliberate and come from the owner's goals: no account, the side chooser, a simple walkthrough, and details tucked away.

**Adopted changes** (owner's rule applied; all small and in the "simpler" direction):
- **B1:** the badge drawn on the destination square.
- **B2:** a "Next key moment" button on the Board.
- **B3:** no repeated class word in the coach text.
- **B4:** a one-sentence game summary (needs a core sentence).
- **B5:** "Great" and "Best" as the class names.
- **U7:** a single mute icon, and "game review" strings renamed to "video review".
- **U8:** "Before the mistake" plus a step counter, and no "Back to the game".
- **P3/P4:** a two-step hint, a "Try it" button on each key moment (route `practice/{gameId}?ply=`), and a check/cross badge.
- **Walkthrough intro defect:** fix the "…a line that The pawn… .." sentence in core.

**Not adopted, deliberately:** graph on top, autoplay, a coach avatar, phase grades, and any rating, points, streaks or daily cap.

**Owner decision pending (not blocking):** `GreenPrimary 0xFF81B64C` and the board colours `0xFFEBECD0` / `0xFF739552` are very close to chess.com's brand values, from memory. The owner tested the app with this look and did not object. **Recommend changing them to our own values before any public listing, and leaving them for the personal POC.** Not changed now.

### Revised queue (after the U6 final verification)

| Step | Scope | Status |
|---|---|---|
| R1 | Core: game-summary sentence (B4), de-duplicated coach text (B3), walkthrough intro defect | **done** (stretch not done) |
| R2 | App: B1, B2, B5, summary sentence, plus U7 Video and U8 Walkthrough with the chess.com adoptions | **done** |
| R3 | App: P3+P4 Practise screen and the "Try it" entries | **done** |
| R4 | App + engine: bundling B1–B2 together with U9 Settings (sequenced, never interleaved) | **R4a done**; U9 Settings next |
| R5 | Bundling tests, docs, licence texts (B3–B4) | pending |
| R6 | U10 and task 69 smalls: large font, RTL, accessibility; share video, export-time estimate | **R6a done** (U10, measured time left, Share, About line); **R6b done** (silent recap end card in the exported MP4, 4-6 s, outside the pacing budget; the video title card says "N moves" instead of "N plies"); **R6c done** (title and final-numbers cards: fit logic for long and Hebrew names, each fact once, whole-percent accuracy, bidi isolation) |
| R7 | Final: signed release APK, offline run, full e2e, fresh screenshots (B5, task 70) | **done** (signed `dist/PalayaChess-1.0-release.apk`, 364,733,235 B; offline proof on chess34 in airplane mode; Summary-header bidi fix; RUN_LOG R7) |

**R1b (added):** fix the existing commentary defects R1 found but left alone, since they are wrong chess claims shown to the user:
- "This forces mate. Better was Ba6." describes the opponent's mate as if it were the mover's.
- A brilliant move says it "allowed" the very motif it sets up.
- Chesscom ply 12 says "pins the piece on b7" for Nf6.
- Both walkthrough intros repeat the first move in the second sentence.

Also, as the stretch item: store the raw tactics on `MoveAnnotation` so the card text can switch to you/your opponent when the side is chosen later.

Runs after R3 and before R4 (bundling).

**R1b additions (found in R2):**
- **Video length for tiny games:** a 4-move scholar's-mate game produced a **3 min 43 s** video. The budget `min(720 s, 120 s + 14 s × moves)` has too large a base for very short games. Re-scale the budget so a 4-move game lands around 1 minute, with no padding. Pin it with a test.
- **Walkthrough result line:** it reads "Result: has invested material in the attack" on the last step (a core `payoffDescription` problem). Fix it at the source, and guard the payoff sentence.

**R1b: done** (see RUN_LOG).

**Bundling split, because of context size:**
- **R4a:** B1 + B2 + B3 in one agent, because `:app` will not compile until B2 and its tests must compile too. It writes checkpoint notes to RUN_LOG as it goes, so a cut-off is recoverable.
- **R4b:** U9 Settings rebuild, per the design and the chess.com audit.
- **R4c:** B4 docs and licence texts (including the espeak-ng credit).

**R4c (B4 docs, licences, About text): done.**

**Remaining queue:**
- **R6:** done (R6a accessibility, time left, Share; R6b recap end card and "moves" wording; R6c title and final-numbers card polish). Still open from the R6 list: nothing.
- **R7:** **done** (final signed release APK, the airplane-mode offline proof (B5), full gate, fresh screenshots, final docs and handoff). Nothing is queued.

---

## D-track: Google Play readiness (owner, 2026-10-06)

The owner will publish on Google Play. Current Play rules (checked 2026-10-06): target API 36 for new apps and updates from 31 Aug 2026, 16 KB page-size support for apps with native code that target 35+, an App Bundle, and a declared foreground-service type.

| Step | Scope | Status |
|---|---|---|
| D0 | Design: move the two bundled models (net, Kokoro voice) from the APK to a first-run download (`docs/MODEL_DOWNLOAD_DESIGN.md`) | **done, accepted** (open questions settled by defaults: provisional `palayax/palaya-chess` URL, plain tar now and measure `.tar.gz` in D2f, signing key next to the keystore, Setup first, "Check for updates" in Settings) |
| D1 | Build Play-ready: target/compile API 36 (AGP 8.9.3, Gradle 8.11.1), edge-to-edge and API 35/36 behaviour fixes, NDK r28 and 16 KB alignment of every `.so`, R8 + resource shrinking with JNI keep rules, `mediaProcessing` FGS type with `onTimeout`, signed AAB plus bundletool sizes, per-ABI APKs, API 36 AVD and the instrumented suites on API 34 and 36 | **done** (RUN_LOG D1) |
| D2a | Pins and build: MODELS.lock net pins + release tag, `generateModelPins` in both modules (replaces `verifyBundledModels`), models and `noCompress` out of the APK, `MODEL_BASE_URL`/`MODEL_MANIFEST_URL`/`SHERPA_ONNX_VERSION` and the debug-only `-PpalayaModelBaseUrl`, `publish_models.sh`, `model_test_server.py`, `*.pem` ignored | **done** (RUN_LOG "D2a + D2b") |
| D2b | Stores, downloader, setup logic (no UI): `NetStore`, `VoiceStore`, `ModelDownloader`, `DownloadStateMachine`, `ModelSetup`, `NetworkStatus`, `FaultHttpServer`; INTERNET + ACCESS_NETWORK_STATE; debug-only cleartext config; backup rules; `Failure.SETUP_REQUIRED`; `FirstRunSetup`/`BundledNetProvider`/`BundledVoiceInstaller`/`FIRST_RUN_SETUP` deleted; migration from bundled builds; host fault tests; `NetworkCallSitesTest` | **done** (RUN_LOG "D2a + D2b") |
| D2c | Service and UI: `ModelDownloadService`, Setup screen, nav gating, Home card, Analysing `SETUP_REQUIRED` state ("Set up" button), Video notice, notifications, a game shared before setup kept and analysed once the net is in | **done** (RUN_LOG "D2c") |
| D2d | Instrumented tests: seed assets in the test APKs, `TestApp`/`TestNet` seeding (already written in D2b, assets not wired), §6.2 tests (port `FirstRunSetup`/`BundledVoiceInstaller`/`BundledNetProvider` instrumented tests from git HEAD), plus `SetupFlowInstrumentedTest` for D2c's service | **done** (RUN_LOG "D2d") |
| D2e | Updates: `ModelManifest`, `ModelCompatibility`, `ManifestSignature`, `UpdateChecker`, `ModelActivator` + journal + `recoverOnStartup`, `EngineController.switchNet`, per-net eval caches, voice id in the narration cache key, Settings "Check for updates" + result sheet, maintainers' P-256 signing key, `publish_models.sh --sign/--verify`; `ActivationRollbackInstrumentedTest`, `UpdateCheckNetworkTest` (the update half of "no network after setup") | **done** (RUN_LOG "D2e") |
| D2f | Docs, policy, migration proof, release: versionCode 2 / 1.1; voice as `.tar.gz` (measured: 55.7 MB smaller, ~1 s to inflate); bundled 1.0 -> 1.1 update proven on chess36; release APKs + AAB in `dist/`; PUBLISHING, PRIVACY_POLICY, STORE_LISTING; EmojiCompat font fetch found and removed | **done** (RUN_LOG "D2f"); the release run against the real GitHub URL: **done in R8** |
| R8 | Real-GitHub fresh-install proof: release build, fresh install, Download from `palayax/Chess` (both files verified first time, no retry: chess36 104 s, chess34 92 s), offline analysis + best line + George/Normal export, "You're up to date."; the emulator's lost bytes diagnosed (its user-mode network via 10.0.2.2, not our code) | **done** (RUN_LOG "R8"). **The small-installer track is fully done.** |

D1 left the models in the APK on purpose: the bundle is ~196 MB per arm64 device, just under Play's 200 MB base limit, and ~14 MB without the models.
## Round 14 queue (owner, 2026-10-06; order confirmed by the owner)

One task at a time (one Gradle invocation at a time). Each is verified from the result XML and screenshots before the next starts.

| # | Task | Status |
|---|---|---|
| F1 | **Bug, games/game01.txt:** "Deep" took forever and stuck at move 23, then reported it could not analyse. Measured on the host (Stockfish 19, Threads 4, Hash 96, MultiPV 3, depth 18): the position after 23.Rdg1 (`r1q2rk1/pb1n1p1p/2pP2p1/2P1bB2/Q3N3/4Bp2/PP3P2/2K3RR b - - 1 23`) took 59-86 s and 80-129 M nodes cold, against 1-15 s for every other position (9.8 s warm, so the blow-up is not stable). The search has no node or time cap (`AnalysisService` sends `go depth D` only). The failure message is unknown: the owner's report is "can't analyse"; the likeliest is GAME_TEXT_LOST after a background process kill. Fix: per-strength node budget plus a time safety cap, capped plies flagged; elapsed/time-left and current-depth progress on the Analysing screen; an on-device diagnostic log (including the previous process's exit reason) shareable from Settings and from the error screen; a regression test on that position. | **done** (RUN_LOG "F1") |
| D2a–D2f, R8 | Small installer: first-run model download (D-track above) | **fully done** (D2a-D2f; R8 proved it against the live GitHub release); owner steps before Play at the top of HANDOFF.md |
| V1 | Voice picker: the 11 speakers already inside the Kokoro model (`voices.bin` = 5,755,904 B = 11 x 523,264 B), with an on-device sample | **done** (RUN_LOG "V1 + V3") |
| V3 | Video pace: slower key moments (a pause before the critical move, readable sequence speed) and a Relaxed/Normal/Brisk setting, in the player and the MP4 | **done** (RUN_LOG "V1 + V3"; ANALYSIS_SPEC §9.8) |
| V2 | Best-line simulation: play the engine's recommended sequence move by move on the board (Back/Next/Play) for every key moment, and in the video instead of only an arrow | **done** (RUN_LOG "V2"; ANALYSIS_SPEC §6.2 and §9.8) |
| G1 | Famous games: a built-in library of ~100 classic games (bare moves, sources checked), optional collections downloaded on tap from our own GitHub release, and opening a PGN file from storage | **next** |
| C1 | Commentary: professional terminology and much more phrasing variety in the templates, every claim still verified | queued |
| C2 | Measured spike: a small on-device LLM that only rephrases verified facts, with a claim checker and template fallback; report quality, rejection rate, speed and size for an owner decision | queued |
