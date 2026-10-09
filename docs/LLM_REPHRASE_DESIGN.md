# On-device LLM rephrasing of the verified commentary: design (C2)

Produced by a Fable 5.1 design agent (read-only, no product code), 2026-10-09. Scope: RUN_PLAN "C2". Owner
decision of 2026-10-09: Gemini Nano (ML Kit GenAI Prompt API through AICore) where the phone supports it, a
downloadable small open model everywhere else, the deterministic C1 generator as the source of truth and the
fallback. The owner's own phone is a Pixel 8 and will exercise the downloadable path.

"Measured" = read from the tree or git history; "estimate" = arithmetic or a guess, marked as such; web facts
carry their URL and are marked **[confirmed]** (read on the page this session) or **[not confirmed]**.
Everything with a size, a speed or a licence term in §1-§3 was fetched on 2026-10-09; the pages change, so
P2/P3 re-check them before pinning.

## 0. Decisions

| Question | Decision | Why (short) |
|---|---|---|
| What the LLM does | **Rephrases only.** Input = the C1 text (already verified) plus its fact list; output = the same facts in better English, or nothing | "Generated text is a claim" (CLAUDE.md): the LLM never adds a claim, so nothing it says needs a new verifier |
| Downloadable model | **Qwen2.5-1.5B-Instruct, Q4_K_M GGUF** from the official `Qwen/Qwen2.5-1.5B-Instruct-GGUF` repo, Apache 2.0, re-hosted on our GitHub release, pinned in `MODELS.lock` (§1) | The only candidate that is OSI-licensed, GPLv3-compatible, redistributable without a pass-through use policy, good enough at short English rewrites, and runs at usable speed on a Pixel 8 CPU. Gemma 3 1B and Llama 3.2 1B fail the licence test (§1.2) |
| Runtime | **llama.cpp compiled from source through the NDK** as a new Android-library module `:rephrase` (the `:engine` pattern), one `librephrase.so` per 64-bit ABI; weights downloaded, code in the APK | Proven pattern in this repo (CMake, 16 KB pages, R8 keep rules, ABI splits); MIT licence; GGUF is the lingua franca of small open models (§2) |
| Gemini Nano | **ML Kit GenAI Prompt API** `com.google.mlkit:genai-prompt:1.0.0-beta4`, `checkStatus()` gating, `seed`+`temperature 0` for stability, system instruction only on nano-v3+ devices (§3). **Ships only if the owner accepts two things the research found**: the ML Kit SDK's own analytics upload to Google, and the GenAI terms' 18+ clause | The owner's decision, with the facts that qualify it (§3.4, §9.3) |
| The claim checker | **A fact-closure comparator in `:core`** (`core.text.ClaimChecker`): the rephrased text must carry exactly the original's moves, squares, piece-on-square pairs, players, numbers, professional terms and evaluation bands, no new tactic or outcome verb, no hedge, within a length band. Rejection = the original text. A Python twin in `scripts/audit_commentary.py rephrase` re-checks the dump independently, and `mutate`-style negative controls prove the checker bites (§5) | The C1 verifiers prove template sentences; they cannot parse free text. Closure over the fact tokens is what free text can be held to |
| Where it runs | **Narrow interface `Rephraser` in `:core`**, implementations in the app layer; two integration points: `MoveAnnotation.text` after `regenerate`, and a per-sentence post-pass over the finished, paced `VideoScript` (§6) | Lands after V4 without touching `VideoScriptGenerator` or the V4 segments |
| When | Cards: key moments at the end of the analysis ("Polishing the commentary…", skippable), the rest lazily per card on first display. Narration: a visible pre-step on the Video screen before synthesis ("Polishing the narration… 12 of 61"), with Skip | The WAV cache is keyed by text, so the narration text must be final before synthesis; Nano refuses inference from a service (§3.3) |
| Caching | `filesDir/rephrase/<modelId>/<key>.txt`, key = SHA-256 of `v<schema>|modelId|promptVersion|surface|original text`; rejections cached too | Deterministic per input; `Variety(ply)` keeps rotating because the original text is in the key |
| Setting | **"Natural wording (on-device AI)", off by default** (§7) | Beta API, a 1.1 GB download, a measured decision still to be taken by the owner, and the app's "every sentence is verified" promise |
| Play policy | The AI-generated content policy's reporting duty is read as **not applying** (no user prompt, no AI-authored facts, rewording only); the Data safety form **changes if Nano ships** (§9) | Marked [not confirmed] where the policy text is a reading, not a quote |

## 1. The downloadable model

### 1.1 What the job needs

The inputs are one to three sentences, 8-60 words, in a closed vocabulary (`docs/COMMENTARY_STYLE.md` §1; the 150
narration sentence ids in `docs/NARRATION_STRINGS.md`). The output is the same facts in 8-70 words. There is no
reasoning, no chess knowledge needed (the checker forbids adding any), and no long context: prompt ≈ 350-450
tokens with the system prompt and four few-shot pairs, output ≈ 20-80 tokens. A model that cannot keep a fact list
straight is rejected by the checker and costs nothing but latency, so the trade is: **quality (acceptance rate and
how natural the accepted text reads) against download size, RAM and tokens/s on a Pixel 8-class CPU.**

### 1.2 Candidates and the licence test

The app is GPLv3 (Stockfish). The weights are data the app downloads, not part of the program, so GPLv3 §5/§6 do not
reach them; but the app's About screen and release notes must state their licence, the file sits on **our** GitHub
release (redistribution by us), and the app must not pass a use policy on to its users that GPLv3 §10 ("you may not
impose any further restrictions") and the project's own promise forbid. Apache 2.0 is GPLv3-compatible per the FSF
(https://www.gnu.org/licenses/license-list.html#apache2 [confirmed, standing FSF position]).

| Model | Licence | Redistribute on our release? | Size (GGUF) | Verdict |
|---|---|---|---|---|
| **Qwen2.5-1.5B-Instruct** | Apache 2.0 [confirmed, https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF, LICENSE in the repo, ungated] | Yes, with the LICENSE and a NOTICE line | official repo, commit `91cad51170dc346986eccefdc2dd33a9da36ead9` (last change 2024-09-20): `qwen2.5-1.5b-instruct-q4_k_m.gguf` **1,117,320,736 B**, SHA-256 `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`; q5_k_m 1,285,494,304 B; q8_0 1,894,532,128 B; q4_0 ≈ 1.07 GB [confirmed via the Hub API with `?blobs=true`] | **Chosen.** OSI licence, official GGUF files that have not moved in two years (an immutable pin by commit + SHA-256), no thinking mode, good English at this size |
| Qwen2.5-0.5B-Instruct | Apache 2.0 [confirmed] | Yes | official repo, commit `9217f5db79a29953eb74d5343926648285ec7e67`: q8_0 **675,710,816 B**, SHA-256 `ca59ca7f13d0e15a8cfa77bd17e65d24f6844b554a7b6c12e07a5f89ff76844e`; q4_k_m 491,400,032 B (a 0.5B loses noticeably at Q4; Q8 is the sane quant) | **Measured fallback** (P2a): about half the RAM, ~2.5x the speed, visibly weaker rewrites; ships only if the 1.5B fails the latency bar on the Pixel 8 |
| Qwen3-1.7B / Qwen3-0.6B (2025) | Apache 2.0 [confirmed] | Yes | the **official** GGUF repos hold Q8_0 only (1.7B 1,834,426,016 B; 0.6B 639,446,688 B); Q4_K_M files (≈ 1.11 GB / 0.40 GB) exist only in community repos (unsloth) | Better on benchmarks, but a hybrid "thinking" mode that must be disabled (`enable_thinking=False` or `/no_think`, then strip the empty `<think></think>`), the model card discourages greedy decoding, and a 1.1 GB file would have to come from a third-party quantisation we re-verify ourselves. P2a measures it next to Qwen2.5-1.5B; it replaces it only if clearly better **before** the pin is cut |
| Qwen3.5-0.8B (2026) | Apache 2.0 [confirmed, https://huggingface.co/Qwen/Qwen3.5-0.8B] | Yes | no official GGUF; multimodal (vision encoder); llama.cpp support [not confirmed] | Watch, not ship |
| Gemma 3 1B (and 3n) | Gemma Terms of Use, last modified 2026-04-01 [confirmed, https://ai.google.dev/gemma/terms]: redistribution only with the §3.2 use restrictions "as an enforceable provision in any agreement" with downstream users, a copy of the terms to every recipient, a Notice file; Google "reserves the right to restrict (remotely or otherwise) usage"; termination = delete all copies | Allowed only with that flow-down | `ggml-org/gemma-3-1b-it-GGUF` Q4_K_M 806,058,240 B | **Rejected**: the flow-down is a "further restriction" (GPLv3 §7/§10) if it binds the app's users, Google keeps a remote kill right, and the official file is gated. (Gemma 4, 2026-04, is Apache 2.0 but its smallest on-device size is E2B = 5.1 B total parameters: far too big) |
| Llama 3.2 1B Instruct | Llama 3.2 Community License [confirmed, the LICENSE in meta-llama/llama-models]: "non-transferable" grant, "Built with Llama" prominently, a Notice file, the Acceptable Use Policy flows down, gated download | Allowed with the terms attached | no official quant; community Q4_K_M ≈ 808 MB | **Rejected** for the same reason as Gemma, plus the naming/attribution clauses |
| SmolLM2-360M / 1.7B-Instruct | Apache 2.0 [confirmed] | Yes | official 360M q8_0 386 MB; official 1.7B q4_k_m 1.06 GB | 360M is too weak for a rewrite with a fact list (expected rejection well above half, estimate); 1.7B is not better than Qwen2.5-1.5B in English |
| Phi-3.5-mini / Phi-4-mini (3.8B) | MIT [confirmed] | Yes | community Q4_K_M 2.49 GB | Too large for the download and the RAM budget |

The sizes and hashes above are what the Hub reported on 2026-10-09; **P2a pins the bytes and SHA-256 of the file
actually fetched** (`/resolve/<commit>/<file>`, then mirrored to our release, so the pin never depends on the Hub).
Apache 2.0's own duties: keep the LICENSE with the weights, add a NOTICE stating the source and that the file is
unmodified (we do not requantise; if P2a ever does, say so).

### 1.3 Speed and RAM on a Pixel 8 (Tensor G3: 1x Cortex-X3, 4x A715, 4x A510; 8 GB RAM)

No published llama.cpp measurement on a Pixel 8 was found [not confirmed: none exists that we could read]. The
closest data, a Snapdragon 8 Gen 3 (Galaxy Z Fold6, 12 GB) with llama.cpp b11440, CPU only, KleidiAI, 6 threads,
n_ctx 2048, greedy [confirmed from the author's table, https://github.com/RomanQuintero/pocket-llama; one phone, on
USB]: **Qwen3-1.7B Q4_K_M (1.11 GB): decode 31 tok/s, prefill 134 tok/s on a 246-token prompt, peak RSS 2.5 GB;
Qwen3-0.6B Q4_K_M (0.40 GB): 73 / 371 tok/s, RSS 1.2 GB**; 6 threads beat 8. The 8 Gen 3's Cortex-X4 is faster than
the G3's X3, so the Pixel 8 should land at roughly a half to two thirds of that (estimate):

| Model, quant (Pixel 8, estimate) | Peak RSS | Prefill | Decode |
|---|---|---|---|
| Qwen2.5-1.5B Q4_K_M (1.12 GB, the same size class as Qwen3-1.7B) | ≈ 1.6-2.5 GB | ≈ 70-90 tok/s | ≈ 15-20 tok/s |
| Qwen2.5-0.5B Q8_0 (0.68 GB) | ≈ 1.0-1.3 GB | ≈ 200-250 tok/s | ≈ 40-50 tok/s |

Two measured facts shape the build: the CPU feature flags change speed about **10x** (a Zenfone 9 build without the
dotprod/i8mm flags fell from 55.8/15.0 to 6.4/2.6 tok/s, pp512/tg128, https://github.com/ggml-org/llama.cpp/issues/10662
[confirmed]), and llama.cpp's KleidiAI kernels reportedly accelerate only **Q4_0 and Q8_0**, with K-quants falling
back to generic kernels (a Dimensity 7300 saw 121 vs 43 tok/s prefill, secondary source, [not confirmed] against the
source at the pinned tag). So P2a measures **Q4_0 against Q4_K_M** for the 1.5B (1.07 vs 1.12 GB) on the Pixel 8 and
pins whichever passes the quality bar faster; the design's default remains Q4_K_M until that number exists.

With the prompt's fixed prefix (system prompt + few-shot, ≈ 300 tokens) kept in the KV cache across calls
(`llama_kv_self_seq_rm` from the prefix boundary, llama.cpp's prefix reuse) only the ≈ 60-120 changing tokens are
processed per call, so one card (≈ 40 output tokens) costs ≈ 3-5 s on the 1.5B and ≈ 1-2 s on the 0.5B, estimate; a
30-card game is 1.5-3 minutes of background work on the 1.5B, a 60-beat narration 3-6 minutes. That is why the
design rephrases key moments first and the rest lazily (§6.2), and why the owner's decision needs the measured number
(§5.5). Thread count: the big cores only (X3 + 4x A715 = 5 threads; P2a sweeps 4, 5, 6, 8). Sustained generation
throttles a phone's big cores within one to two minutes (the same source); the jobs are made of short calls with
idle gaps, and the measurement records the clock.

### 1.4 File, pins, download, update

**One new entry kind in `vendor/models/MODELS.lock`** (Java properties, same style as the net and the voice):

```
rephrase.model.id=qwen2.5-1.5b-instruct-q4_k_m        # the modelId in cache keys and the manifest
rephrase.model.file=qwen2.5-1.5b-instruct-q4_k_m.gguf  # file name on the release
rephrase.model.url=https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf
rephrase.model.size=<bytes>                            # pinned by fetch_models.sh on its first run (the Hub reported 1117320736 on 2026-10-09)
rephrase.model.sha256=<64 hex>                         # idem (the Hub reported 6a1a2eb6…9407e; the script must agree or stop)
rephrase.model.arch=qwen2                              # GGUF general.architecture, read from the header (structural check)
rephrase.model.license=Apache-2.0
rephrase.prompt.version=1                              # bumps move every cache key (§6.3)
rephrase.runtime.tag=b<nnnn>                           # the llama.cpp tag vendored by scripts/fetch_llama_cpp.sh
```

- `scripts/fetch_models.sh` gains the GGUF (cache under `vendor/models/rephrase-assets/`, pin size/SHA-256/arch on
  first run, verify afterwards); `generateModelPins` in `:app` writes `REPHRASE_MODEL_ID/FILE/SIZE/SHA256/ARCH`
  into `GeneratedModelPins` and fails the build on a malformed lock, exactly as for the voice. The build never
  needs the file. `scripts/publish_models.sh` uploads it to the **same immutable tag** as the other two files when a
  new tag is cut, or to a new tag `models-2026.11` with the two existing files re-uploaded (binaries under a tag
  never change, PUBLISHING §4c); `release.tag` stays one value.
- **Download only on the user's tap** (Settings, §7), through `ModelDownloadService` with a new job kind
  `Rephrase` (the service already has pause/resume/cancel, the notification, `onTimeout`, `START_NOT_STICKY`):
  `rephrase/<file>.part`, `Range` resume, streaming SHA-256, size and hash against the pins, then
  `RephraseModelStore.installVerified(part)` (atomic move into `filesDir/rephrase/models/`) after a **structural
  check in Kotlin**: GGUF magic `GGUF`, version 3, `general.architecture == rephrase.model.arch`, tensor count and
  KV count plausible (< 10 000), file length ≥ the sum of the tensor sizes (a short file would be read past its end by
  llama.cpp). llama.cpp returns null from `llama_model_load_from_file` on a bad file rather than calling `exit()`
  (it logs and returns; **[not confirmed] for every failure mode: P2b feeds it a truncated and a corrupted file on
  the emulator and the design treats a crash there as a blocker**), so the engine's "never test-load" rule is
  not needed, but a load failure still marks the file damaged and deletes it.
- **Wi-Fi advice and space**: the metered dialog and the pre-check of `SetupLogic` reused ("This download is about
  1.1 GB. Download now, or wait until you're on Wi-Fi."); space = file + 32 MB margin (no unpacking). The Setup
  screen is untouched: the model is **not** part of setup, and `needsNet()`/`needsVoice()` do not see it.
- **Update path**: a third `models.json` entry `{"id":"rephrase-qwen","compat":{"kind":"gguf","arch":"qwen2",
  "runtime":{"name":"llama.cpp","min":"b<nnnn>","max":"b<mmmm>"}},"promptVersion":1,...}` judged by
  `ModelCompatibility` (kind, arch, runtime range containing the compiled tag, 200 MB ≤ size ≤ 3 GB, https under the
  base URL, version-code range), listed in the "Check for updates" sheet as "Wording model · about 1.1 GB", installed
  by `ModelUpdateInstaller` + `ModelActivator.activateRephraseModel`: journal `swapped`, move, **trial** (load +
  one fixed rephrase that must pass the checker), `committed`, delete the old file, **clear `filesDir/rephrase/<old
  modelId>/`**; a death in the trial rolls back at the next start like the net and the voice (§4 of
  `MODEL_DOWNLOAD_DESIGN.md`). The signed manifest and the key custody are unchanged.
- Backup rules: `rephrase/` excluded (models and cache) in all three rule sets; `BackupRulesTest` updated.

## 2. Runtime

### 2.1 Options

| Option | Licence | Format | Code size per ABI | Verdict |
|---|---|---|---|---|
| **llama.cpp from source, NDK/CMake, new `:rephrase` module** | MIT [confirmed, https://github.com/ggml-org/llama.cpp/blob/master/LICENSE] | GGUF (the official Qwen files) | a few MB stripped (Debian's arm64 `libllama0` ≈ 3.2 MB + `libggml-cpu` ≈ 0.5 MB as the only reference; **[not confirmed]** for an Android build, P2b measures) | **Chosen** |
| LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android:0.18.0`, 2026-10-06) | Apache 2.0 [confirmed, https://github.com/google-ai-edge/LiteRT-LM] | `.litertlm` from the `litert-community` Hub org (e.g. `Qwen3-0.6B-int4` ≈ 332 MB, Apache); no GGUF | AAR 20.9 MB (prebuilt; 16 KB alignment [not confirmed]) | **The credible alternative**: Google-maintained Kotlin API, CPU/GPU/NPU. Not chosen because the model files are third-party conversions (not Qwen's own, so our pin would be of a community artefact), the AAR is opaque prebuilt code to audit like sherpa-onnx, and a source build gives us the 16 KB flags, the size and the symbol list. If P2b's NDK build turns into a sink, switch to it: the `Rephraser` interface does not care |
| MediaPipe LLM Inference (`com.google.mediapipe:tasks-genai:0.10.35`) | Apache 2.0 | `.task`/`.litertlm`, Gemma-first; PyTorch conversion needs "a Linux machine with at least 64 GB RAM"; no GGUF | AAR 42.4 MB | **Rejected**: the page itself says the API is in **maintenance-only mode** and points to LiteRT-LM [confirmed, https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference] |
| onnxruntime-genai | MIT | ONNX exports per model | Android "requires build from source", no Maven artifact | Rejected: a second onnxruntime next to Kokoro's, an export pipeline, no Android packaging |

### 2.2 The `:rephrase` module (mirrors `:engine`)

- `scripts/fetch_llama_cpp.sh` clones `ggml-org/llama.cpp` at the pinned tag `rephrase.runtime.tag` into
  `vendor/llama.cpp/` (not committed; `vendor/LLAMA_CPP_VERSION.txt` committed, like `STOCKFISH_VERSION.txt`). Tags
  are `bNNNN`, monotonic; **b11530** was current on 2026-10-09 [confirmed, the releases page]. P2a pins the tag it
  measured with; GGUF is at format version 3 and the spec promises nothing across versions, so the model is tested on
  the pinned tag and `models.json`'s `runtime` range says which tags may load it.
- `rephrase/src/main/cpp/CMakeLists.txt`: `add_subdirectory(vendor/llama.cpp)` with the options llama.cpp's own
  Android page documents [confirmed, https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md]:
  `GGML_NATIVE=OFF` (required when cross-compiling), `GGML_OPENMP=OFF` and `GGML_LLAMAFILE=OFF` ("unsupported on
  Android" per the docs; the in-tree example sets OpenMP ON, the two disagree, the docs win until P2b measures),
  `GGML_CPU_KLEIDIAI=ON` (optional for arm64; its kernels dispatch at runtime on the CPU's features),
  `LLAMA_OPENSSL=OFF`, `LLAMA_CURL=OFF`, `LLAMA_BUILD_TESTS/EXAMPLES/SERVER/COMMON=OFF`; `BUILD_SHARED_LIBS=OFF` so
  `llama` + `ggml` + our `jni_bridge.cpp` link into **one** `librephrase.so` (one `System.loadLibrary`, one keep rule,
  one file to align). The upstream example (`examples/llama.android`, now `lib/src/main/cpp/ai_chat.cpp`, minSdk 33,
  `GGML_BACKEND_DL=ON` + `GGML_CPU_ALL_VARIANTS=ON`: several `libggml-cpu-*.so` picked at runtime) is the reference
  for the JNI shape, **not** for packaging: its dlopen'ed variants are MODULE targets that a shared-only 16 KB linker
  flag does not reach, and they multiply the files to keep and align. `target_link_options(-Wl,-z,max-page-size=16384)`
  on our one target as in the engine; check `llvm-readelf -lW` (Align 0x4000) and `zipalign -c -P 16`. From
  **2027-02-01** updates without 16 KB support cannot be released [confirmed,
  https://developer.android.com/guide/practices/page-sizes].
- ABIs: **arm64-v8a** with `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` (ggml's variable, not a global `-march`, which
  the docs advise against; dotprod is on every arm64 phone of the last six years, and KleidiAI adds the i8mm/SME
  paths at runtime on the Pixel 8's cores). P2b also builds `+i8mm` as the baseline and measures; if it is materially
  faster and `GGML_CPU_ALL_VARIANTS` is the only way to get it safely, that is a P2b decision with the alignment
  caveat above. **x86_64** with AVX2 for the emulators (correctness only). **armeabi-v7a not built**: the module
  declares `abiFilters` arm64-v8a + x86_64 only, the v7a APK gets no `librephrase.so`, and `Rephraser.availability()`
  reports `UNSUPPORTED_ABI` ("Not available on this phone"). llama.cpp's documented Android platform is
  `android-28`; our minSdk is 26, so P2b builds at 26 and, if the build needs 28, gates the feature by SDK the same
  way. The universal APK grows by the two libraries; the Play download for arm64 by one (≈ +3-4 MB, estimate).
- JNI surface (`NativeRephrase`, `external fun`s, pinned by `rephrase/consumer-rules.pro` like `NativeBridge`):
  `load(path, nThreads, ctxTokens): Long`, `free(handle)`, `complete(handle, prompt, maxTokens, seed): String`,
  `cancel(handle)`, `tokenCount(handle, text): Int`. Greedy sampling (`temperature 0`, `top_k 1`, a fixed `seed`), a
  stop on the end-of-turn token and on `\n\n`, `maxTokens` = 2.0 × the original's token count + 16. **One model
  per process** (the same rule as the engine; `RephraseController` owns it, loads lazily, frees on memory pressure
  via `onTrimMemory` and after 60 s idle).
- R8: keep `net.palaya.chessanalyzer.rephrase.NativeRephrase` and its natives (symbol names
  `Java_net_palaya_chessanalyzer_rephrase_NativeRephrase_*`); nothing else is read by name. The release build must
  be run on a device, as CLAUDE.md says of every new JNI library.
- Memory: `llama_model_load_from_file` with `use_mmap=true` (the 1.1 GB file is paged in, not copied; Android's
  low-memory killer then counts it as file-backed), `n_ctx = 1024`, `n_batch = 512`, flash attention off. On a
  2-3 GB phone the load may still fail: `Rephraser.availability()` reports `LOW_MEMORY` when
  `ActivityManager.MemoryInfo.totalMem < 4 GB` and the setting explains it.
- Play policy: the `.so` is in the APK; only the GGUF is downloaded (data). PUBLISHING §3b's paragraph gets a third
  file. A GGUF holds no code (tensors and metadata); llama.cpp parses it in-process, so the signed manifest and the
  structural check guard it like the net (§1.4).

## 3. Gemini Nano

### 3.1 Dependency and API [confirmed, https://developers.google.com/ml-kit/genai/prompt/android/get-started]

- `implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")` (latest on Google Maven, 2026-07-21; alpha1
  2025-10-29, beta1 2026-01-28, beta3 2026-07-14 added system instructions, structured output, 4 K output tokens;
  beta4 fixed a `checkStatus()` exception on non-Pixel devices). **Beta: "not subject to any SLA or deprecation
  policy"**; a minor version may break the API.
- Min API 26 (ours). Not supported on an unlocked bootloader (error 606).
- `Generation.getClient()` → `GenerativeModel`; `checkStatus()` → `FeatureStatus.AVAILABLE | DOWNLOADABLE |
  DOWNLOADING | UNAVAILABLE`; `download()` → a `Flow<DownloadStatus>` (`DownloadStarted`,
  `DownloadProgress(totalBytesDownloaded)`, `DownloadCompleted`, `DownloadFailed(e)`; **no total size is reported**, so
  the UI shows bytes, not a percentage); `warmup()`; `generateContent(request)` / `generateContentStream`;
  `countTokens`. Request options: `temperature`, `topK`, `seed` ("enables stable, deterministic results", no
  guarantee stated), `candidateCount`, `maxOutputTokens`; `SystemInstruction` (**nano-v3 and later only**, keep
  under ~150 words). Input < 4 000 tokens; output cap 4 096. Model selection
  `modelConfig { releaseStage = STABLE }` for production (PREVIEW is dev-preview devices only).
- The quota: "AICore enforces a per-app inference quota" (no number published); `ErrorCode.BUSY` (9) on bursts
  (back off), `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` (27) on a daily allowance, and **`BACKGROUND_USE_BLOCKED` (30):
  inference is permitted only while the app is the top foreground application**, which includes a foreground
  service [confirmed, https://developers.google.com/android/reference/com/google/mlkit/genai/common/GenAiException.ErrorCode
  and the overview page].

### 3.2 Availability and the states

`NanoRephraser.availability()`, called once per Settings/first-use visit, never at launch:

| `checkStatus()` | What the app does |
|---|---|
| `AVAILABLE` | Setting enabled: "Uses Gemini Nano on this phone" |
| `DOWNLOADABLE` | Setting enabled, supporting text "Gemini Nano needs a one-time download by Android (size not reported). Wi-Fi recommended."; the switch's tap calls `download()` and shows the bytes; **the bytes go through Google's Private Compute Services APK, not our process** (§3.4), so our metered dialog cannot gate it; the design still shows our Wi-Fi advice line before the tap |
| `DOWNLOADING` | "Android is downloading Gemini Nano…" with the byte count, switch disabled |
| `UNAVAILABLE`, or any `GenAiException` from `checkStatus()` | The downloadable path (§1) is offered instead; the Nano row is not shown. A later `checkStatus()` may change the answer (AICore fetches its configuration lazily; error 606 while it is still downloading) |

Each `generateContent` call is wrapped: `BUSY` → exponential backoff (1, 2, 4 s, three tries) then the original
text; 27 (daily quota) → the whole job stops, the rest of the texts stay original, the Settings row says "Gemini Nano
has used its daily allowance; wording continues tomorrow" (an owner-approved wording to write in P3); 30 → the job
is only ever started from a foreground screen (§6.2), so this means the user left: pause, resume when the screen is
back. Policy-filter failures (11, 15) → the original text, counted as rejections.

### 3.3 Device list as of 2026-10-07 [confirmed, https://developers.google.com/ml-kit/genai, "Last updated 2026-10-07"]

The Prompt API is listed per Gemini Nano version: **nano-v3** (system instructions available): the Pixel 9 and
Pixel 10 families, Galaxy S26 / S26+ / S26 Ultra, and 2025-26 flagships of Honor (Magic 8 Pro), iQOO (15), Lenovo
(two tablets), Motorola (Signature), OnePlus (15, 15R), OPPO (Find X8/X9 series, Reno 14/15 Pro), realme (GT 7T),
Sharp, Sony (Xperia 1 VIII) and vivo (X200/X300 series); **nano-v4**: the Pixel 11 family, Galaxy Z Flip8 / Z Fold8;
**nano-v2** (no system instructions): Galaxy Z Fold7 / Z TriFold and 2024-25 flagships of Honor, iQOO, Motorola
(Razr Ultra), OnePlus (13, 13s), OPPO (Find N5), POCO, realme, vivo and Xiaomi (14T Pro, 15, 17 series, Pad Mini).
The page is the source of truth; P3 copies it on the day.

**Not listed: Pixel 8, 8a, 8 Pro, Galaxy S24, Galaxy S25** (the S25 appears only for the feature APIs, Rewriting
included, not for Prompt). The Pixel 8/8a got Gemini Nano through a developer option in 2024 (secondary sources,
e.g. https://9to5google.com/2024/05/29/pixel-8-android-aicore/), which predates ML Kit GenAI; **whether the Prompt
API works there is [not confirmed]**, and the owner's statement that his Pixel 8 cannot test it matches the official
list. In practice Nano reaches Pixel 9+ and recent flagships: a minority of installs for years.

The **Rewriting API** (`genai-rewriting:1.0.0-beta1`, `OutputType.REPHRASE | PROFESSIONAL | FRIENDLY | …`, input
< 256 tokens, English and six other languages, wider device list including the S25) was considered and
**rejected**: no custom instruction and no fact list can be given, so it cannot be told to keep "Better was Re1"
last or to keep the band words; its rephrasings would be checked by the same checker and rejected more often.
It remains a one-day experiment if P3 finds the Prompt API's device list too short.

### 3.4 Privacy, the no-network rule, Data safety

What Google states [confirmed, https://developer.android.com/ai/gemini-nano]: prompts are executed on the device
("eliminating server calls"); AICore "doesn't store any record of the input data or the resulting outputs after
processing them"; AICore "does not have direct internet access", every request including model downloads goes
through the open-source Private Compute Services APK; AICore distributes and updates the model, the app stores
nothing.

What the ML Kit data disclosure states [confirmed, https://developers.google.com/ml-kit/android-data-disclosure]:
**the ML Kit SDK in our process collects and sends to Google, over HTTPS, "for diagnostics and usage analytics":
device information (manufacturer, model, OS build, ML accelerators), application information (package name,
versions), performance metrics (latency), API configuration, input and output size (not content), feature version,
event type and error codes, and, for the GenAI features, "user, device or other identifiers" (per-installation
identifiers) and the configured languages.** The page does not say it can be switched off, does not say whether the
upload leaves from our process or through Play services, and says the developer "is solely responsible for deciding
how to respond to Google Play's Data safety section form". The GenAI terms add [confirmed,
https://developers.google.com/ml-kit/genai-terms]: the app must inform its users of Google's processing of
"metrics data"; users of the API must be 18 or older and the app must not be "directed at, or likely accessed by"
under-18s; Google's Generative AI Prohibited Use Policy applies; "Preview" services may not be used in production.

Consequences for this app, stated plainly for the owner:

1. **The no-network-on-its-own rule cannot hold on the Nano path as the app stands.** The SDK's analytics upload is a
   network call the user did not tap. `NetworkCallSitesTest` (a source scan) would not see it; `NoNetworkAfterSetupTest`
   might, depending on whether the SDK uploads from our process (ProxySelector/TrafficStats would catch it) or hands the
   batch to Play services (the D2f emoji-font case: only TrafficStats, and only if charged to our uid). P3 must measure
   it on a supported device with the same instruments. The rule would have to be re-stated as "no network call on its
   own **except Google's ML Kit diagnostics while the Natural wording setting is on**", in CLAUDE.md, the privacy policy
   and the Data safety form.
2. **Data safety** would gain: Collected → "Device or other IDs" (ML Kit's per-installation identifier) and "App info
   and performance: diagnostics / crash logs / other performance data", purpose "Analytics / app functionality",
   collected by a third-party SDK, encrypted in transit, optional (only with the setting on), not deletable by request
   (Google's data). The privacy policy gains a paragraph (§9.3). PLAY_CONSOLE_ANSWERS.md §Data safety changes.
3. **The 18+ clause** is incompatible with a chess app rated "Everyone" with no age gate. Reading the clause literally,
   the Prompt API cannot be used in this app at all unless the listing's audience is adults; this is a terms question
   for the owner (and, if he wants, a lawyer), not an engineering one. [not confirmed: whether Google enforces it on
   general-audience apps; the clause is quoted on the terms page.]
4. The Beta label: the terms forbid "Preview"/"Experimental Access" services in production; the Prompt API is labelled
   Beta, not Preview, and `ModelReleaseStage.STABLE` is the production model stage. Read as allowed [not confirmed].

**Recommendation.** Build P1 and P2 (the checker and the downloadable model) and ship them. Build P3 (Nano) only after
the owner has decided points 1-3 above; if he decides against, the downloadable model runs on every phone, Pixel 9
included, and the design loses nothing but Google's model. If he decides for, the setting's help text says "On this
phone Gemini Nano is used; Google receives anonymous usage statistics from the Android AI service while this is on",
and the policy texts follow §9.3.

## 4. Prompting

The same prompt serves both backends (llama.cpp gets it through the Qwen chat template; Nano gets the system part as
`SystemInstruction` on nano-v3+ and as the first `## Rules` block of the user turn on nano-v2). The prompt is
versioned (`rephrase.prompt.version`) and lives in `:core` as a constant string with its few-shot pairs, so the
host tests and the Python twin read the same text. ML Kit's prompt-design guidance (`##` delimiters, few-shot,
temperature ≈ 0.2 for deterministic tasks, keep it short) is followed; we go to temperature 0 / greedy on llama.cpp
and `temperature = 0f, topK = 1, seed = 7` on Nano.

**System prompt (draft, ~130 words; P2a tunes it against the dump):**

```
You polish chess commentary for a game-review app. Rewrite the text under ## Text so it reads like a calm,
professional commentator: natural, clear, varied sentences. Rules, in order of importance:
1. Keep every fact. Every move, square, piece, colour, number and chess term listed under ## Facts must appear,
   with the same meaning, and nothing may be added: no new tactic, threat, plan, praise, blame or evaluation.
2. Never say a move wins, loses, forces, threatens or traps anything unless the text already says so.
3. If the text ends with "Better was <move>", keep that sentence last and unchanged.
4. Keep "you" / "your opponent" / "White" / "Black" exactly as used.
5. Same length, give or take a quarter. Plain prose: no lists, no quotes, no headings, no preamble.
Output only the rewritten text. If you cannot keep every fact, output the text unchanged.
```

**User turn:**

```
## Facts
moves: Nxg7+, Ba6 | pieces: — | squares: — | players: Black, White | numbers: 5 | terms: forced mate
bands: clearly worse → decisively lost | surface: card
## Text
Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost. Better was Ba6.
```

The fact list is **generated, not hand-written**: `ClaimChecker.facts(original)` (§5.2) is the same extractor the
checker uses on the output, so the model sees exactly what it will be held to. Four few-shot pairs are fixed in
the prompt (a praised move with a motif and piece-on-square pairs; an error card with charge, band sentence and
"Better was"; a narration beat with spoken squares, "knight to h five"; and a card returned unchanged because any
rewrite would drop a fact), each pair's output passing the checker by construction (a host test asserts it).

Narration input differs in two ways the prompt states in the `surface` field: squares are spoken ("h five", via
`SpokenChess`), and the text is one beat that may be several sentences; the output is handed to `SpokenRespelling`
afterwards as today, so the respelling table is not in the prompt. Captions, chapter titles, the recap card and the
`GameSummarySentence` are never sent (§6.1).

## 5. The claim checker

### 5.1 What it can and cannot do

The C1 verifiers (`CommentaryClaimsTest`'s template catalogue, `scripts/audit_commentary.py`'s `verify_phrase`,
`NarrationClaimsTest`) prove a **template** sentence against the board. A rephrased sentence is free text; no
verifier can parse it, and the C1 rule "an unrecognised sentence is WRONG" would reject every rewrite. What free
text *can* be held to is **closure over the facts of a text that was already verified**: if the output carries
exactly the original's fact tokens and no token from the claim vocabulary that the original lacked, it cannot
claim more than the original did. The checker is therefore a comparator, not a verifier, and it is only sound
because the input is C1's verified text. It lives in `:core` (`core.text.ClaimChecker`, pure Kotlin, host-tested)
and reuses C1's vocabulary: the term list of `docs/COMMENTARY_STYLE.md` §1, the band words of
`CommentaryGenerator.standingWords`, the banned list of `CommentaryClaimsTest`, the piece and square grammar of
the catalogue regexes (moved from the test into a main-source `CommentaryVocabulary` object the test then imports).

### 5.2 The fact extraction (`ClaimChecker.facts(text, surface): Facts`)

Applied to the original and to the candidate alike, after normalisation (NFC, straight quotes, collapsed
whitespace, a trailing full stop added if missing). For `surface = NARRATION`, spoken squares ("h five", "a one")
are first folded back to `h5`, `a1` with the inverse of `SpokenChess` (the only place the two surfaces differ), and
"with check" / "checkmate" are kept as terms.

| Fact class | Extractor | Comparison |
|---|---|---|
| **Moves** | SAN regex `\b(O-O(-O)?|[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?)[+#]?\b`, plus spoken narration forms ("queen takes the rook on a one, with check" → the board's `TacticInstance.moveUci`/`san` supplied in the request, so the extractor compares against the supplied SAN) | multiset equality, `+`/`#` stripped |
| **Piece-on-square pairs** | `(pawn|knight|bishop|rook|queen|king)s? on ([a-h][1-8])` | set equality |
| **Bare squares** | `\b[a-h][1-8]\b` not inside a SAN move | multiset equality |
| **Piece words** | the six names, singular/plural folded | multiset equality |
| **Players** | `White`, `Black`, `you`, `your`, `your opponent`, `we`? (no: not in the vocabulary → banned) | multiset equality of `{White, Black, you/your, your opponent}` (so a side can never flip) |
| **Numbers** | `\d+(\.\d+)?`, and spelled numbers one..twenty, "a whole", "half" | multiset equality |
| **Terms** | the lemma list: fork, pawn fork, double attack, pin, absolute pin, relative pin, skewer, discovered attack, discovered check, double check, en prise, loose, trapped / no safe square, the exchange, zwischenzug / in-between move, overloaded, desperado, back-rank, smothered, forced mate / mate in, checkmate / mate, only move / only legal move, sacrifice / sacrifices / offers, deflect(ion), decoy / lures, clearance / clears, removing the defender / takes away, interference / cuts off, Greek gift, windmill, discovered, promotes / underpromotes, winning position, decisive advantage, en prise, undefended, more attackers than defenders, turning point, opening theory / book | **set equality** (every term kept, none added) |
| **Evaluation bands** | the nine `standingWords` plus "completely lost/won", "about level", "equal", "the evaluation moves" | the **ordered list** of band words must be equal (a swapped "from X to Y" is a different claim) |
| **Outcome verbs** | lemma classes: WIN (wins, picks up, gains, collects, nets), LOSE (loses, drops, hangs, gives away, throws away, costs), MATE (mates, forces mate), THREAT (threatens, threat), TRAP (traps), ATTACK (attacks, hits, piles up on), SAVE (saves, holds, defends) | set equality of classes present (an added "wins" or "threatens" is a new claim; a dropped one is a lost fact) |
| **"Better was"** | `Better was (\S+)` | present iff present in the original, same move, exactly once, the last sentence |
| **Charges** | "lets … play", "hands …", "can play", "allows" | the charge's beneficiary and move equal; "allows" itself is on the banned list and stays banned |

### 5.3 The rules (any failure = REJECT with a reason code)

1. `FACTS_DIFFER(class)`: any row of §5.2 unequal.
2. `BANNED(word)`: the C1 banned list (forces mate, allowed, drops the, sets up a, keeping material level, stunning,
   opens a discovered attack, the point becomes clear, probably, might, plan, idea, intends, strategic) plus hedges
   and generics the LLMs love: likely, perhaps, maybe, could, should, must, always, never, clearly (as an adverb
   outside "clearly better/worse"), obviously, brilliant, stunning, beautiful, crushing, devastating, dominant,
   initiative, tempo, development, control, pressure (outside the narration's own "the pressure is real"),
   strategic, positional, "the engine" (unless in the original), any piece name or square not in the facts (already
   rule 1), a `wins the <piece> on` phrase (the C1 `winsAPieceOnASquare` rule).
3. `SHAPE`: output words between 0.7× and 1.3× the original and at most original+12; sentence count ≤ original+1 and
   ≥ 1; no newline, no `#`, `*`, `"`, `:` followed by a list, no "Here is", "Sure", "Rewritten"; ASCII plus the
   en dash and the apostrophe; no sentence repeated; must differ from the original by at least one word (an
   unchanged output is accepted as `UNCHANGED`, not counted as a rejection: it is the model's "cannot keep the facts"
   answer).
4. `REGISTER`: never opens with the classification's name (Blunder, Mistake, Inaccuracy, Brilliant, Great, Best,
   Excellent, Good, Book, Forced, Miss); never in the first person plural; no exclamation marks on cards.
5. `SIDE` (regenerate invariance): for a card, `facts(rephrase(text_for_side))` must equal `facts(text_for_side)`;
   rephrasing happens per side text, so there is nothing to map, but `SideCommentaryMappingTest`'s shape test
   must still pass on the rephrased text: the charge, the beneficiary and "Better was X" last.

The reason codes are logged (diagnostic log, tag `rephrase`, text hashes only) and counted (§5.5).

### 5.4 Negative controls (the `mutate` principle, in Kotlin and in Python)

`ClaimCheckerTest` feeds the checker the 432 recorded card texts and the 18+ recorded narration beats as "rephrasings"
of themselves after one mutation each, and asserts REJECT with the expected code: a square changed (`a` ↔ `h`), a
piece renamed, a side flipped, "Better was" dropped / moved first / changed, a number changed (mate in 5 → 6), the
bands swapped, a term added ("…, a fork"), a term dropped (relative pin → "pin"), an outcome verb added ("and
wins a rook"), a hedge added ("probably"), a praise word added ("a brilliant idea"), the text doubled, a list
format, a preamble, a 2× longer text, the classification name prepended, "you" ↔ "White". Each mutation must be
caught, and the unmutated text must pass as UNCHANGED. The same table drives `scripts/audit_commentary.py rephrase
<jsonl>` (a Python re-implementation of §5.2-§5.3 with its own regexes, independent of the Kotlin one) and
`scripts/audit_commentary.py mutate-rephrase`, which exits non-zero on a miss: two implementations must agree on
every recorded text and every mutation. The LLM's **accepted** outputs from P2a are then appended to
`docs/COMMENTARY_AUDIT.md` as a fourth table with their reason codes.

### 5.5 The measurement for the owner's decision (P2a on the host, P2b on the Pixel 8)

`RephraseMeasurementDumpTest` (host, `:core`, needs the model and a host llama.cpp build via `pc/`) runs every
recorded card text (144 no-side + 288 side variants) and every narration beat of the five pacing games through the
real model and writes `core/build/rephrase/measure.jsonl` and a Markdown report
`docs/audit/rephrase_<modelId>_p<prompt>.md` with:

| Metric | Definition | Proposed bar (owner to confirm) |
|---|---|---|
| Rejection rate | REJECT / (REJECT + ACCEPT + UNCHANGED), per surface and per reason code | ≤ 25 % cards, ≤ 35 % narration |
| Unchanged rate | UNCHANGED / total | reported |
| Quality sample | 60 pairs (20 per recorded game, key moments first) original vs accepted rewrite, side by side, plus a blind A/B column the owner fills in | owner prefers the rewrite in ≥ 60 % of the pairs |
| Audit of the accepted texts | `audit_commentary.py rephrase`: 0 FACTS_DIFFER on the accepted set (a Kotlin ACCEPT the Python rejects is a checker bug, stop) | 0 |
| Latency | per text: prompt tokens, output tokens, ms to first token, ms total; median and p90 per surface; on the host (reference) and on the Pixel 8 (decision) | Pixel 8 median ≤ 4 s per card, ≤ 6 s per narration beat; a 30-card game's key moments (≤ 5) ≤ 25 s |
| Memory | `Debug.getNativeHeapAllocatedSize` and PSS before/after load, peak during a run (mmap'd weights show as file-backed) | ≤ 2.0 GB peak PSS delta on the 1.5B at `n_ctx` 1024 (the 8 Gen 3 reference saw 2.5 GB RSS at 2048) |
| Battery | `BatteryManager` charge delta over one full game on the Pixel 8 | reported |
| Size | GGUF bytes, `librephrase.so` per ABI, APK delta | reported |

The same test runs with the 0.5B Q8_0 and Qwen3-1.7B so the owner sees the three rows side by side.

## 6. Where it plugs in

### 6.1 The interface (lands in `:core`, independent of V4)

```kotlin
package net.palaya.chessanalyzer.core.text

enum class RephraseSurface { CARD, NARRATION }

data class RephraseRequest(
    val surface: RephraseSurface,
    val text: String,                 // the verified original, one card text or one narration beat
    val facts: Facts,                 // ClaimChecker.facts(text, surface), passed so the prompt and the check agree
    val sanHints: List<String> = emptyList(), // narration: the SAN of the moves the beat speaks, from the segment
)

sealed interface RephraseResult {
    data class Accepted(val text: String) : RephraseResult
    data object Unchanged : RephraseResult
    data class Rejected(val candidate: String, val reason: ClaimChecker.Reason) : RephraseResult
    data class Unavailable(val cause: String) : RephraseResult   // no model, quota, memory, cancelled
}

interface Rephraser {
    val id: String                                    // "qwen2.5-1.5b-instruct-q4_k_m@p1" or "gemini-nano@p1"
    suspend fun rephrase(request: RephraseRequest): RephraseResult
}
```

`:core` holds `ClaimChecker`, `RephrasePrompt` (the text of §4), `RephraseSurface`, a `FakeRephraser` (test
fixtures: identity, a fixed table, a mutating adversary) and `RephrasedScript`/`RephrasedReport` (pure functions
that apply a `Map<String, String>` of accepted texts to a `GameReport`'s `MoveAnnotation.text` and to a
`VideoScript`'s segments). The app layer holds `RephraseService` (cache + checker + backend selection + the job
runner), `LlamaRephraser` (in `:rephrase`), `NanoRephraser` (in `:app`). **Nothing in `VideoScriptGenerator`,
`CommentaryGenerator`, `ScriptBuilder` or the V4 segments changes.**

### 6.2 The two integration points and when they run

**Cards.** `AnalysisViewModel.uiReportFor` / `setUserSideForGame` build the side-specific report through
`CommentaryGenerator.regenerate` (byte-identical with analysis-time text). After that call, `RephraseService.
applyCached(report)` swaps in every annotation whose key is already in the cache; nothing is computed on the UI
path. The job: at the end of a successful analysis, if the setting is on and a backend is available, the Analysing
screen shows one more phase "Polishing the commentary (on-device AI)… 2 of 5" over the **key moments' texts for the
side the game currently has** (≤ 5 cards + the "Not me" variants = ≤ 10 texts; on the 1.5B ≈ 30-60 s, estimate),
with a **Skip** button; Skip or a failure leaves the originals (and the job continues lazily). Every other card is
rephrased lazily: when the Board or the Summary opens on a ply whose text is not cached, the original is shown and
a background job (one worker, FIFO by ply distance from the open ply) fills the cache; **a card already on screen
does not change under the user**: the rephrased text appears the next time that card is composed (next ply, next
visit). Choosing a side regenerates the texts ("you" / "your opponent"), which are different strings and so new
keys: there is no second blocking phase; the Summary shows the originals for the new side and the background job
refills the key moments first, then the rest, and the cards pick the rewrites up when they are next composed. Nano's
foreground-only rule holds: the job runs only while a screen of ours is resumed (`ProcessLifecycleOwner`) and
pauses otherwise; the llama.cpp job follows the same rule so the two backends behave alike.

**Narration.** `AnalysisViewModel.videoScriptFor` returns the finished, paced script. `RephraseService.
rephrasedScript(script)` is a post-pass: for each segment whose `kind` is in the allowlist (the prose beats:
move beats, reveals, payoffs, turning point, lessons, intro/outro prose; **not** captions, chapter cards, the
recap card, `CaptionBackToGame`, the V4 "Back to the game now" connective if V4 makes it a caption, and not a
segment whose `narration` equals its `caption`), split with `NarrationSynthesizer.splitIntoSentences` (the cache
unit), rephrase **the whole beat as one request** (sentences inside a beat refer to each other), then replace
`narration` and recompute `estimatedSpeechMs = VideoScriptGenerator.estimateSpeechMs(newText, wpm)`; `leadIn`,
`holdAfterMs`, `board`, `tactic`, `eval` untouched. Unknown `SegmentKind`s (V4 may add some) are **denied by
default**. The pass is a visible step on the Video screen: "Polishing the narration… 12 of 61" with Skip, before
"Prepare narration" and before Save video; the export reads the cache the pass filled and never calls a backend
(so the foreground service runs exactly as today, and Nano's rule 30 cannot bite). `NarrationCoordinator.
isFullyPrepared` and the player see the rephrased script because `videoScriptFor`'s cache entry is replaced by the
rephrased one under a key that includes the rephraser id (`"$gameId:$lang:$options:$rephraserId"`).

**The V3 pace and the §9.7 budget.** The pace is laid over the finished story (`ScriptBuilder.paced`), so the pass
sits after it and changes no segment count, no lead-in and no pace time. The story gets longer by the words the
rewrite adds: the per-text band (≤ 1.3×, ≤ +12 words) bounds a beat at +30 %, and the pass enforces a **script-level
cap**: Σ `estimatedSpeechMs` of the rephrased script ≤ 1.10 × the original `storyMs` and ≤ the §9.7 `budgetMs`; if
either is exceeded, beats are reverted to their originals from the largest delta down until both hold (deterministic
order: delta desc, then index). `PaceMeasurementDumpTest` gains a row per game "story after rephrase" so the
numbers are in the log, as C1 did.

**The narration cache key** needs no change: `NarrationStore.keyFor(text, provider, fingerprint)` already keys by
the sentence text, so a rephrased sentence is a new WAV and the original's WAV stays valid for the setting-off case.
Turning the setting off shows the originals at once (the cache is only read when the setting is on). A prompt-version
or model change moves every rephrase key, and the orphaned WAVs are the same kind of orphan D2e documented (cleared
by Settings › Clear).

### 6.3 Caching and determinism

`filesDir/rephrase/<modelId>/<key>.txt`, `key = sha256("1|$modelId|p$promptVersion|$surface|$text").take(24)`;
the file holds `A\n<text>` (accepted), `U` (unchanged) or `R <reason>` (rejected, kept so a rejected text is not
retried on every visit; a prompt-version bump retries everything). Atomic write (temp + rename), one directory per
model id so an update's purge is a `deleteRecursively`. Expected size: 2-4 KB per game. Settings › "Saved narration
audio" gains the line "Reworded text: N KB · Clear" in P4.

Determinism: on llama.cpp, greedy decoding with a fixed seed is deterministic for one model file on one device (the
same binary, the same thread count; floating-point order can differ across ABIs and thread counts, which is why the
thread count is part of the backend id). On Nano, `seed` is "stable" but not guaranteed and the base model differs
by device; the cache makes a result stable per install, which is what the narration cache and the audits need.
`Variety(ply)` stays: the original text (with its rotation) is the key, so two cards that C1 phrases differently get
different rewrites, and the "same game, same text" property holds per install.

### 6.4 Failure and fallback

Any exception, timeout (per request: 30 s on llama.cpp, 20 s on Nano), memory failure, a model file that fails its
hash or structural check, a backend that reports `Unavailable`: the original text, logged, counted. The job never
blocks a screen except the two explicit "Polishing…" phases, both skippable. A crash inside `librephrase.so` (a
native abort) would take the process down; the model is loaded only inside the two jobs, under a journal file
`rephrase/loading.json` written before `load()` and deleted after the first successful completion, and a journal
found at start marks the model file damaged (deleted, "Download again" in Settings) exactly as `ModelActivator.
recoverOnStartup()` does for the net.

## 7. Settings UX

Reference (owner's rule): chess.com's Settings groups rows under feature headers with a switch and a one-line
explanation, and its Game Review coach text is a plain toggle ("Show coach comments") with no per-comment badge
**[memory]**; Android's own Settings pattern for an on-device AI feature (Pixel's "AI features" rows: a switch, a
one-line "On this device" note, a download size before the tap) for the download row. Material 3 switch row.

A new header **Commentary** between the name field and Video (it affects cards and narration, so it belongs to
neither), one row:

| State | Row | Supporting text |
|---|---|---|
| Nano available (`AVAILABLE`) | switch **Natural wording (on-device AI)** | "Uses Gemini Nano on this phone. Sentences are reworded by an AI model; every fact is checked against the original and the original is kept if anything differs." |
| Nano `DOWNLOADABLE` | switch | "Uses Gemini Nano on this phone (Android downloads it once; Wi-Fi recommended)." Tapping the switch on calls `download()`; the row shows "Android is downloading Gemini Nano… 120 MB" |
| No Nano, model not installed | row, chevron | "Download the wording model (about 1.1 GB). Sentences are reworded by an AI model on your phone; every fact is checked against the original." Tap → a sheet like the update sheet: size, "Wi-Fi recommended", **Download**, metered dialog, progress/Pause/Cancel through `ModelDownloadService`; on "Installed." the switch appears, **on** |
| Model installed | switch | "Reworded on this phone by Qwen2.5 (1.1 GB). Every fact is checked against the original." plus a second line "Remove the model" (text button) |
| 32-bit ABI or < 4 GB RAM | row disabled | "Not available on this phone." |

**Default: off.** Reasons: the download is five times the whole setup; the API is Beta; the owner has not yet seen the
measured quality (§5.5) and the setting is the place a measured decision gets switched on; and the app's promise is
that every sentence is verified — the rewording keeps that promise through the checker, but it must be a choice the
user makes with the explanation in front of him. Recommend revisiting "on by default for Nano phones" after P3's
measurement, never for the 1.1 GB path.

How the user is told: the setting's supporting text (above), one line in About ("Wording: optionally reworded on your
phone by an AI model (Qwen2.5-1.5B-Instruct, Apache 2.0, or Gemini Nano); every fact is checked against the
app's own text."), the privacy policy (§9.3). No per-card "AI" badge: it would be on every card and say nothing the
setting does not; the facts are the same by construction. The video's recap card is never reworded and says nothing.

## 8. Testing plan

### 8.1 Host (`:core`, `:app` unit)

- `ClaimCheckerTest` (`:core`, ≈ 40): fact extraction on every recorded card text and narration beat (a text's facts
  equal its own; spoken squares fold); every §5.4 mutation rejected with its code; the few-shot outputs pass;
  UNCHANGED on identity; SHAPE, the banned list, the `Better was` rules, side invariance.
- `RephrasedScriptTest` (≈ 12): the allowlist, `estimatedSpeechMs` recomputed, lead-ins and holds untouched, the
  script-level cap reverting by delta order, an unknown (V4-style) kind denied. `RephrasedReportTest` (≈ 4).
  `RephrasePromptTest` (≈ 4): prompt version = the lock's; the fact line is the extractor's; few-shot pairs checker-clean.
- `:app` (≈ 30): `RephraseServiceTest` with `FakeRephraser` (cache, rejection cached, prompt bump retries, pause when
  not resumed, Skip, phase counts), `RephraseModelStoreTest` (GGUF header check, truncated file), `ModelCompatibilityTest`
  +3, `ModelManifestTest` +1, `ModelActivatorTest` +4 (trial, rollback, cache purge), `BackupRulesTest` +1;
  `NetworkCallSitesTest` (scans `:rephrase` too) and `ManifestPermissionsTest` unchanged.
- `scripts/audit_commentary.py rephrase` and `mutate-rephrase`; `after`, `lines`, `mutate` unchanged and green.
- Baselines (RUN_LOG, latest): `:core` 541/0/0, `:app` unit 505/0/0, `:desktop` 25/0/0, `lintDebug` 0 errors; V4 will
  have moved these, so each phase states its own baseline from the XML.

### 8.2 Instrumented (`:app`, `:rephrase`)

- `FakeRephraser` behind `ChessAnalyzerApplication.rephraserForTesting` (tests only, like `updateCheckerForTesting`):
  `RephraseSettingsInstrumentedTest` (the row's states; the download sheet against `FaultHttpServer` with a small
  stand-in GGUF carrying a valid header; metered dialog; "Installed."; Remove), `RephraseFlowInstrumentedTest` (the
  Analysing phase with Skip; a key-moment card shows the fake's text; a card on screen does not change; the Video
  pass's progress; the exported WAV keys are the rephrased sentences; setting off = originals at once);
  `NoNetworkAfterSetupTest` extended: the llama.cpp job makes zero `select()` calls and zero TrafficStats bytes.
- `:rephrase` `LlamaRephraserInstrumentedTest` on the **x86_64 emulators** with the real GGUF pushed once to
  `/data/local/tmp` (a 1.1 GB seed asset would push the androidTest APK past 1.3 GB): load, each few-shot input
  rephrased and accepted by the checker, determinism (two runs, same bytes), cancel mid-generation, a truncated and a
  corrupted GGUF refused without a crash, memory released after idle. Emulator speed is not a measurement.
- **The Pixel 8 (the decision device):** `RephraseMeasurementInstrumentedTest` (`@ManualEvidenceTool`, run with
  `am instrument` so Gradle does not uninstall) writes the §5.5 metrics, pulled to `docs/audit/`; the release APK end
  to end (download from the real tag, the Analysing phase, a Board card, a narrated export) with screenshots
  `docs/screenshots/c2_*`; `dumpsys meminfo` and `batterystats` during the run.

### 8.3 What can and cannot be verified for Nano without a supported device

Can (emulator + host): the `NanoRephraser` code path against a fake `GenerativeModel` behind an interface
(`checkStatus` states, download flow, BUSY backoff, quota 27 stop, error 30 pause, policy-filter rejection), the
Settings row's Nano states, the R8 build with the ML Kit AAR, the merged manifest's `androidx.startup` initializers
and any `<provider>`/`<service>` the AAR adds (read them: CLAUDE.md's D2f lesson), 16 KB alignment of any `.so` the
AAR brings, and `checkStatus()` returning `UNAVAILABLE` on chess36/chess34 without crashing. **Cannot**: a real
generation, its quality, latency, determinism, the daily quota, and above all **whether and how the ML Kit SDK's
analytics leave the device** (§3.4 point 1), which must be measured with `NoNetworkAfterSetupTest`'s instruments on a
listed device. P3 therefore ends with a RUN_LOG line "Nano path: compiled, gated and faked; real inference
**not verified** on hardware" unless the owner provides or borrows a Pixel 9+ (or a listed device) for one afternoon.

## 9. Licences, attribution, Play policy, privacy

### 9.1 Licences and About

- Qwen2.5-1.5B-Instruct: Apache 2.0; `app/src/main/assets/REPHRASE_MODEL_LICENSE.txt` with the Apache text and the
  model's NOTICE/attribution ("Qwen2.5 is licensed under the Apache License 2.0, Copyright Alibaba Cloud"), surfaced in
  About like the voice's. The release notes of the model tag name the file and its licence.
- llama.cpp: MIT; `rephrase/src/main/assets/LLAMA_CPP_LICENSE.txt`, surfaced in About; MIT is GPLv3-compatible.
- ML Kit GenAI: a proprietary Google SDK (Google APIs ToS + the GenAI Additional Terms), linked, not redistributed.
  **Linking a proprietary SDK into a GPLv3 program** is a licence question: the GPLv3 "System Libraries" exception does
  not obviously cover a Play-distributed AAR, and the FSF's view is that such linking violates the GPL unless the
  library qualifies, while many GPL Android apps link Play services regardless. The owner's call [not confirmed either
  way; §11].
- PUBLISHING.md §1 attribution list and §7's provider table gain the rows; §3b gains the GGUF.

### 9.2 Google Play

- **AI-Generated Content policy** [confirmed, https://support.google.com/googleplay/android-developer/answer/14094294,
  "Understanding Google Play's AI-Generated Content policy"]. It defines AI-generated content as "content that is
  created by generative AI models based on user prompts", names text-to-text chatbots, image generators and
  voice/video of real people as in scope, and requires such apps not to generate offensive content, to follow every
  other policy, and to offer in-app reporting or flagging that feeds moderation. It lists as out of scope "at this
  time": apps that merely host AI content, apps that only summarise non-AI content, and **"productivity apps that use
  AI to improve an existing feature"**. Palaya Chess has no user prompt, generates no facts, and rewords its own
  deterministic text under a checker that falls back to that text: **the reading is that the reporting requirement
  does not apply** (closest to the third exclusion). The exclusions are "at this time", so P4 records the reading and
  the date in PLAY_CONSOLE_ANSWERS.md, and the cheap insurance is built anyway: a "Report this wording" item on the
  card's overflow menu that writes the text pair to the diagnostic log and offers the share sheet (a report mechanism
  without a server), plus the checker as the output filter. The best-practices page
  (https://support.google.com/googleplay/android-developer/answer/16353813) is the one Play points to for safeguards.
- **Device and Network Abuse** [confirmed, https://support.google.com/googleplay/android-developer/answer/9888379]: an
  app "may not download executable code (such as dex, JAR, .so files) from a source other than Google Play"; the
  page has no rule on data files. The GGUF holds tensors and metadata, parsed by `librephrase.so` which ships in the
  bundle, like the net and the voice. The §3b paragraph of PUBLISHING.md gains the file; the FGS declaration for
  `dataSync` gains "and, optionally, the wording model". Never ship a newer llama.cpp as a download.
- **Data safety** (only if Nano ships, §3.4 point 2): "Device or other IDs" and "App info and performance" collected by a
  third-party SDK, optional, encrypted in transit. Without Nano: unchanged ("collects: No").
- **Content rating / target audience**: the GenAI terms' 18+ clause (§3.4 point 3) is the open blocker for Nano.

### 9.3 Privacy policy changes (`docs/PRIVACY_POLICY.md`, republished to GitHub Pages)

Without Nano: one bullet under "What the app does on your phone": "If you turn on Natural wording in Settings, the
app can download a language model (about 1.1 GB, from GitHub, after you tap Download) and uses it on your phone to
reword its commentary; nothing you do is sent anywhere." and the model in the "When the app uses the internet" list.
With Nano, additionally: "On phones that have Google's Gemini Nano, the app can use it instead. Gemini Nano runs on
your phone through Android's AI service (AICore); your text is not sent to Google. While this setting is on,
Google's ML Kit software inside the app sends Google anonymous usage statistics (device model, app version,
response times, error codes and an installation identifier), as described in Google's ML Kit data disclosure
(https://developers.google.com/ml-kit/android-data-disclosure). Turn the setting off and nothing is sent." That
sentence is the disclosure the GenAI terms require.

## 10. Phased implementation plan

Each phase is one agent, one worktree, one Gradle invocation at a time, counts read from the XML, 0 skipped.

| Phase | Scope | Files (new unless noted) | Acceptance |
|---|---|---|---|
| **P1 checker, interface, fake** (host only) | `ClaimChecker`, `CommentaryVocabulary` (regexes moved out of `CommentaryClaimsTest`, which imports them: no behaviour change), `RephrasePrompt` v1 with four few-shot pairs, `Rephraser` + results, `FakeRephraser`, `RephrasedScript`/`RephrasedReport`; `scripts/audit_commentary.py rephrase` + `mutate-rephrase`; `RephraseService` with cache and the two job runners against the fake; the setting key `rephrase_enabled` (no UI yet) | `core/.../text/{ClaimChecker,CommentaryVocabulary,RephrasePrompt,Rephraser,RephrasedScript,FakeRephraser}.kt`, `app/.../rephrase/{RephraseService,RephraseCache}.kt`, `app/.../data/SettingsRepository.kt` (modified), tests of §8.1 | `:core:test` baseline + ≈ 60, `:app:testDebugUnitTest` baseline + ≈ 20, 0 failures, 0 skipped; `audit_commentary.py after/lines/mutate` unchanged and green; `mutate-rephrase` 0 missed on ≥ 18 mutations; `CommentaryClaimsTest` unchanged in count and green after the move |
| **P2a model measurement** (host, `pc/`) | `scripts/fetch_models.sh` + `MODELS.lock` rephrase entries (three candidates fetched to `.cache/`, only the chosen one pinned); a host llama.cpp build in `pc/llama/` (CMake, the same tag); `RephraseMeasurementDumpTest` (`:core`, skipped-not-vacuous: it fails, not skips, when the model is absent and `-Prephrase.measure=true`); the report `docs/audit/rephrase_*.md` for Qwen2.5-1.5B Q4_K_M and Q4_0 (the KleidiAI question of §1.3), Qwen2.5-0.5B Q8_0, Qwen3-1.7B (community Q4_K_M, non-thinking); prompt v1 tuned (v2 if changed); the llama.cpp tag pinned | `scripts/fetch_models.sh`, `vendor/models/MODELS.lock`, `pc/llama/*`, `core/src/test/.../RephraseMeasurementDumpTest.kt`, `docs/audit/*` | The §5.5 table for three models on the host; the chosen model's pin written; **the owner's go/no-go on quality** recorded in RUN_LOG before P2b starts |
| **P2b `:rephrase` module and download** | `scripts/fetch_llama_cpp.sh`, `vendor/LLAMA_CPP_VERSION.txt`, the module (CMake, JNI, `NativeRephrase`, `LlamaRephraser`, consumer rules), `generateModelPins` rephrase fields, `RephraseModelStore` (GGUF check, install, damaged-journal), `ModelDownloadService` job `Rephrase`, `ModelCompatibility`/`ModelManifest`/`ModelActivator`/`ModelUpdateInstaller` third kind, `publish_models.sh` third file, backup rules, `settings.gradle.kts`, `app/build.gradle.kts` | `rephrase/**`, `app/.../data/models/*` (modified), `app/.../rephrase/LlamaBackend.kt`, scripts | Host: `:app` unit baseline + ≈ 15 green; `lintDebug` 0 errors. Emulator (x86_64, both AVDs): `LlamaRephraserInstrumentedTest` green with the real GGUF; a truncated/corrupt GGUF refused without a crash; `NoNetworkAfterSetupTest` 0 calls / 0 bytes with the job running. **Pixel 8**: the §5.5 metrics measured and in `docs/audit/`; release APK: download from the real tag (after `publish_models.sh models-2026.11`), `llvm-readelf` 0x4000 on `librephrase.so`, `zipalign -P 16` OK, R8 build runs the job on the device without `UnsatisfiedLinkError`; APK/AAB sizes recorded |
| **P3 Nano** (only after the owner's §3.4 decision) | `genai-prompt:1.0.0-beta4`, `NanoRephraser` behind a `NanoClient` interface with a fake, availability gating, BUSY/27/30 handling, system-instruction vs in-prompt rules by status, the merged-manifest review (initializers, providers, `.so` alignment), the privacy text | `app/.../rephrase/{NanoRephraser,NanoClient}.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro` (if the AAR needs rules), `docs/PRIVACY_POLICY.md`, `docs/play/PLAY_CONSOLE_ANSWERS.md` | Host + emulator: the fake-driven tests green, `checkStatus()` `UNAVAILABLE` on both AVDs without a crash, merged manifest reviewed and recorded, `NoNetworkAfterSetupTest` green with the SDK linked but the setting off. On a listed device, if one is available: one real rephrase accepted by the checker, the §5.5 metrics, and **the SDK's network behaviour measured** (ProxySelector + TrafficStats + `dumpsys netstats`) and written down. Without a device: RUN_LOG states "not verified on hardware" |
| **P4 UX and docs** | The Commentary header and row (§7), the download sheet, the Analysing phase and the Video pass with Skip, the Settings storage line, About entries, licence assets, strings, accessibility (heading, live regions polite, 48 dp, no `maxLines`, font 2.0, landscape), PUBLISHING/PRIVACY_POLICY/STORE_LISTING/README/CLAUDE.md/HANDOFF, the Play AI-policy reading quoted, instrumented UI tests of §8.2 | `app/.../ui/screens/SettingsScreen.kt`, `AnalysisProgressScreen.kt`, `VideoScreen.kt`, `AboutScreen.kt`, `strings.xml`, assets, docs, `app/src/androidTest/...` | `:app` connected baseline + ≈ 8, 0 failures, 0 skipped on chess36 and chess34; screenshots `docs/screenshots/c2_*` (row states, sheet, metered dialog, Analysing phase, Video pass, font 2.0, landscape); `lintDebug` 0 errors; the docs' facts (sizes, URLs, counts) measured, not copied from this design |

Order: P1 → P2a → (owner decision) → P2b → P4 → P3 (or P3 before P4 if the owner decides on Nano early). P1 and
P2a touch nothing V4 touches (`:core` `narration/` package and `app/ui/model/`) except the test catalogue move, which
is a pure refactor; P2b and P4 land after V4 has merged.

## 11. Open questions for the owner

1. **Nano's two blockers (§3.4):** accept Google's ML Kit analytics upload (and the privacy/Data-safety changes), and
   the GenAI terms' 18+ clause against an "Everyone" listing? If not, P3 is dropped and the downloadable model
   serves every phone.
2. **A 1.1 GB download** for a rewording feature: acceptable for the app's audience, or should the 0.5B (≈ 0.5 GB,
   weaker) be the ship candidate and the 1.5B the measured alternative? P2a's table is the basis; the design proposes
   the 1.5B unless it misses the latency bar.
3. **The quality bars of §5.5** (rejection ≤ 25 %, owner preference ≥ 60 %, Pixel 8 median ≤ 4 s): confirm or change
   before P2a, so the measurement is judged against numbers fixed in advance.
4. **Default off** (§7): confirm.
5. **The GPLv3 question of linking the ML Kit AAR** (§9.1): the owner's call; it is moot if question 1 is "no".
6. **Qwen3-1.7B instead of Qwen2.5-1.5B** if P2a shows it better at the same cost: pre-approve the swap before the
   pin is cut, or insist on Qwen2.5?
7. **Runtime fallback pre-approval**: if the NDK build of llama.cpp costs more than two days in P2b (the 16 KB
   handling of the CPU-variant libraries is the known trap), may the agent switch to LiteRT-LM (§2.1) with the
   `litert-community` Qwen3-0.6B-int4 file (332 MB, Apache 2.0, a third-party conversion) without coming back?
8. **Where the research disagreed with this document's numbers**: the P2a report supersedes every size and speed
   here; the licence verdicts stand unless a page says otherwise on the day.

---

## 12. Owner decisions (2026-10-09)

Answers to §11, given in chat on 2026-10-09. They override the recommendations above where they differ.

1. **Gemini Nano: dropped for now.** P3 is not built. The downloadable model is used on every phone, including the
   ones that have Nano. Reason: the ML Kit analytics upload would change "no data collected" and the GenAI terms'
   18+ clause conflicts with the Everyone rating. Revisit only if Google changes either.
2. **Ship model: Qwen2.5-1.5B-Instruct Q4_K_M (about 1.1 GB).** P2a still measures it on the emulators and the
   owner's Pixel 8 and reports the §5.5 numbers, but the 0.5B is no longer a ship candidate.
3. **Default: offered at first run.** The Setup screen offers the wording model as an optional third download next
   to the engine data and the voice (clearly optional, with its size; the app works fully without it). Settings
   keeps the on/off row and the download for users who skipped it. If the user downloads it at setup, the feature
   is on; otherwise it is off.
4. **Pre-approved:** switching the pin to **Qwen3-1.7B** (Apache 2.0) if P2a shows it clearly better. **Not
   pre-approved:** switching the runtime from llama.cpp to LiteRT-LM; if the NDK build of llama.cpp stalls, stop
   and report.
5. The §5.5 decision bars stand as written.

---

## 13. As built (C2 agent, 2026-10-09)

What the implementation does where it differs from §1-§10 (the owner decisions of §12 included). RUN_LOG "C2-P1",
"C2-P2a", "C2-P2b" and "C2-P4" hold the evidence and the numbers.

**Checker (§5), stricter than designed, each rule added because a real model output or a mutation needed it:**
- Moves keep their check marks (`Nxg7+` and `Nxg7` are different claims).
- Terms and outcome verbs are **counted**, not sets: "which is en prise" said twice is two claims, and "...with no safe
  square and wins it" added a second WIN to a card that already had one (a mutation the set rule accepted).
- Sides are compared as the **ordered sequence of mentions, consecutive repeats collapsed** (not a multiset): a swap
  inside one sentence ("White's bishop takes Black's rook" -> "Black's bishop takes White's rook") keeps the
  multiset and is still caught; legitimate merging of sentences ("White ... White ...") is not punished.
- New fact classes: names (proper nouns, and any capitalised word the original does not have), negations (count),
  **alternative-move markers** ("Instead", "was the move", "line", "would", "if", ...; the P2a run had "Rook takes the
  pawn on h seven was the move" reworded into a move that was never played), and the **order** of moves, squares,
  outcome classes and check/mate words ("This hands White d4, which hits the loose bishop on c5" reworded as "This
  hits the loose bishop on c5, giving White d4" changed who hits the bishop).
- The banned list is a count rule against the original (the original's own words are proven) and covers judgement
  words (good, strong, mistake, best, ...), any form of "allow" and "set up", and "a rook up"-style material claims.
- Narration: a candidate that writes notation is refused; bare squares are folded back to the spoken form first.
- Charges: the beneficiary is checked as "the side named right before the move" in the candidate, so any wording of
  the charge passes and a changed beneficiary does not.
- The Python twin (`scripts/rephrase_check.py`) was written by a separate agent from a prose spec without reading
  the Kotlin; every later rule was added to both with a mutation in both tables.

**Prompt v1 (§4):** the system prompt adds "keep every not and no", "never change who does what" and the narration
squares rule; four few-shot pairs (a praised card, an error card with charge, bands and "Better was", a narration
beat, a card left unchanged); ChatML for Qwen2.5 and Qwen3 (Qwen3 gets the empty think block). Prefix about 700
tokens, so **n_ctx is 2048**, not 1024 (2048 x 28 KB of f16 KV on the 1.5B = 57 MB).

**Runtime (§2):** llama.cpp **b11190** (commit fcc8915), the tag of the host binaries P2a measured with. KleidiAI is
**off** (its CMake downloads sources at configure time; K-quants do not use it anyway). x86_64 is built with AVX2/FMA/F16C
and gated at run time on /proc/cpuinfo. The debug variant is compiled -O3. One static library, six exported symbols.

**Download (§1.4, §12.3):** the Setup screen offers the model as an unticked third download (owner decision; this
supersedes "the Setup screen is untouched"); Settings has the same download. It goes on **its own release tag**
(`rephrase.release.tag`, `models-2026.11`) instead of re-uploading the net and the voice to a new tag:
publishing is one new release with one file and its LICENSE, and installs of 1.1 keep their URLs. The cache lives in
`filesDir/rephrase/cache/<model>/` (the model in `rephrase/models/`).

**Crash journal (§6.4):** a journal left at start does not delete the file at once (the low-memory killer is a likelier
cause of a death while loading 1.1 GB than a bad file): the file is re-hashed before its next use, and a second death in
a row turns the feature off.

**Update path (§1.4):** kind `rephrase-qwen` / compat `{"kind":"gguf","arch":...}` / runtime `llama.cpp bNNNN..bMMMM`;
offered **only to a phone that has the model installed** (an update never pushes an optional 1.1 GB file). Activation:
journal swapped -> trial (load + one checked rephrase) -> committed, rollback at once or at the next start, the old
model's cache folder purged. No manifest entry is published yet (there is no newer model).

**Integration (§6.2):** cards: the key moments of the current side at the end of the analysis ("Polishing the
commentary (on-device AI)… 2 of 5", Skip), then every other card in a background job (key moments first, then by ply);
a card on screen keeps its words until it is drawn again. The narration post-pass is implemented and tested in `:core`
(`RephrasedScript`) and exposed by `AnalysisViewModel` (`rephrasedVideoScriptFor`, `polishNarration`,
`narrationPolishPending`), but **not wired into the Video screen** (V4 owned those files): see the "V4 INTEGRATION
HOOK" comment. The "Report this wording" item (§9.2) is not built.

**Measurement (§5.5):** host CPU only (llama.cpp b11190, the corpus = every distinct card text of the three audited
games for three sides, 190, and every eligible narration beat of the five pacing games, 388). The other candidates
ran on all cards and every 4th beat. Their raw outputs are committed (`docs/audit/rephrase/raw_*.jsonl`) and
`RephraseMeasurementDumpTest` judges them on every build. The Pixel 8 numbers are the owner's to collect
(`RephraseMeasurementInstrumentedTest`, RUN_LOG "C2-P2b").
