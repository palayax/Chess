# Research: local LLM for the commentary script

Round 12, task 51. This came from a research subagent and has been lightly edited. **All tok/s figures for this laptop are
estimates until the spike (task 55) measures them.**

Target machine: i9-10885H with 32 GB DDR4 and a GTX 1650 Ti with 4 GB of VRAM.

## colibri: rejected, even for overnight use

colibri's own benchmarks for GLM-5.2 int4 on 32 GB machines:
- i5-12600K on Windows: 0.08 tok/s.
- Ryzen AI 365 with DDR5: 0.19–0.24 tok/s.

([benchmarks](https://github.com/JustVugg/colibri/blob/main/docs/benchmarks.md))

A 3,500-token script would take about 12 hours at 0.08 tok/s, before counting the time to read the prompt. It also needs 372 GB of disk.

## Candidates (llama.cpp GGUF)

| Model | Licence | Size | Notes |
|---|---|---|---|
| **Gemma 4 26B-A4B** (MoE, 3.8B active) | Apache-2.0 | Q4_K_M 16.9 GB | 256K context. EQ-Bench creative-writing v3 rubric 16.03, Elo 1287 ([HF](https://huggingface.co/unsloth/gemma-4-26B-A4B-it-GGUF)) |
| **Gemma 4 31B** (dense) | Apache-2.0 | ~17–20 GB at Q4 | Best small open creative writer: rubric 16.01, Elo 1408 ([Unsloth](https://unsloth.ai/docs/models/gemma-4)) |
| Qwen3.6-35B-A3B (MoE) | Apache-2.0 | UD-Q4_K_M 22.1 GB | Thinking can be turned off. No creative-writing score found |
| Qwen3.8-27B (dense) | Apache-2.0 | Q4_K_M 17.1 GB | Released Aug 2026 |
| gpt-oss-20b | Apache-2.0 | MXFP4 12.1 GB | Fast. Prose quality not verified |
| Gemma 4 12B (dense) | Apache-2.0 | ~7–8 GB | Fallback |

### Speed

**Measured on similar machines:**
- Gemma 4 26B-A4B Q4 runs at about 7 tok/s on CPU alone (i5-8500, 32 GB DDR4) ([source](https://terminalbytes.com/run-gemma-4-mini-pc-without-gpu/)).
- Qwen3.6-35B-A3B on a laptop with a 4 GB GPU and 32 GB RAM went from about 8 to about 19 tok/s when the expert weights were kept on the CPU (`--cpu-moe`) ([source](https://sukhbinder.wordpress.com/2026/09/05/running-a-35b-moe-model-on-4gb-vram-laptop/)).

**Estimates for this laptop:**

| Model type | Generation speed | Time for a 3,500-token script |
|---|---|---|
| MoE | 8–14 tok/s | about 5–10 min |
| Dense ~30B | 1.5–2.5 tok/s | about 25–40 min |

### Context size

A game of about 80 plies needs 5–7K tokens of facts. Add about 1.5K for the persona and 3–4K for the output. Use a 16K context, or write the script section by section.

## Grounding: how the model is kept from inventing chess

Research findings:
- Without tools, frontier models get 22% of chess claims wrong and small open models over 40% ([arXiv 2608.04240](https://arxiv.org/abs/2608.04240)).
- Concept-guided commentary cut illegal-move mentions from 46% to 20% by adding engine evaluations and concepts ([arXiv 2410.20811](https://arxiv.org/html/2410.20811v1)).

The design:
1. **A JSON fact sheet for each move**, computed by `:core` and Stockfish. The model is only told what the code knows.
2. **Hard rules in the prompt:** mention no move, square or piece that isn't in the facts, and never calculate variations. Jokes are about drama, never new chess claims.
3. **Placeholder tokens** (`{M23}`, `{BEST23}`), which the code swaps for the real SAN. The model cannot type a wrong move.
4. **Structured output** through llama-server's `json_schema` / `response_format`.
5. **A validation pass:** find any leftover SAN or square, check it against the allowed set, regenerate up to N times, then fall back to the template sentence.
6. **A persona style guide** with original examples, a ban-list of real creators' catchphrases, and temperature 0.8–1.0.

## Hebrew

- In the DictaLM paper, Gemma 3 was the strongest non-Hebrew open baseline ([arXiv 2602.02104](https://arxiv.org/html/2602.02104)).
- DictaLM-3.0-24B-Thinking (Apache-2.0, Q4_K_M 14.3 GB) is the Hebrew specialist. It is dense and always thinks, so expect 30–60+ minutes per script (estimate).
- Gemma 4's Hebrew quality has **not been benchmarked**. It has to be tested by hand.

## Recommendation

- **Default model:** Gemma 4 26B-A4B-it Q4_K_M with thinking off. The alternative is Qwen3.6-35B-A3B.
- **Quality mode:** Gemma 4 31B dense. For Hebrew, DictaLM-3.0-24B.
- **Runtime:** `llama-server` (the llama.cpp CUDA 12.4 build from GitHub releases, plus cudart). It is OpenAI-compatible, supports JSON-schema output and `--cpu-moe`, and its `-hf` download needs no account. We prefer it to Ollama.

```
llama-server -hf unsloth/gemma-4-26B-A4B-it-GGUF:Q4_K_M --jinja -c 16384 -ngl 99 --cpu-moe -fa on -ctk q8_0 -ctv q8_0 --port 8080
```
