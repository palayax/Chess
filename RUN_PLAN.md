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
