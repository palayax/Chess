#!/usr/bin/env bash
# C2: publishes the optional wording model on its own immutable GitHub release (docs/LLM_REPHRASE_DESIGN.md §1.4).
#
#   scripts/publish_rephrase_model.sh [--dry-run]
#
# The tag is rephrase.release.tag in vendor/models/MODELS.lock (the app compiles it in: first-run URL =
# MODEL_BASE_URL + tag + "/" + rephrase.model.file). The release holds two files: the GGUF, byte-identical to Qwen's
# official file (size and SHA-256 checked against the lock, GGUF magic checked), and the model's Apache-2.0 LICENSE.
# The net and the voice stay on release.tag, untouched (binaries under a tag never change, PUBLISHING §4c), and the
# signed manifest is not touched: there is no update for the wording model yet (the update path is built and tested
# for when there is).
#
# Owner-only: needs `gh` authenticated as the repository owner. Fetch the files first:
#   scripts/fetch_models.sh --rephrase
#   (the LICENSE: vendor/models/rephrase-assets/LICENSE.qwen2.5-1.5b-instruct-gguf.txt, from the same Qwen commit)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/vendor/models/MODELS.lock"
REPO="palayax/Chess"
DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

die() { echo "ERROR: $*" >&2; exit 1; }
prop() { grep -E "^$1=" "$LOCK" | head -1 | cut -d= -f2-; }
size_of() { stat -c %s "$1" 2>/dev/null || stat -f %z "$1"; }

TAG="$(prop rephrase.release.tag)"
FILE="$ROOT/vendor/models/rephrase-assets/$(prop rephrase.model.file)"
LICENSE="$ROOT/vendor/models/rephrase-assets/LICENSE.qwen2.5-1.5b-instruct-gguf.txt"
SIZE="$(prop rephrase.model.size)"; SHA="$(prop rephrase.model.sha256)"

[ -f "$FILE" ] || die "missing $FILE (scripts/fetch_models.sh --rephrase)"
[ -f "$LICENSE" ] || die "missing $LICENSE (the LICENSE file of the Qwen repository at the pinned commit)"
[ "$(size_of "$FILE")" = "$SIZE" ] || die "size differs from the lock"
[ "$(sha256sum "$FILE" | cut -d' ' -f1)" = "$SHA" ] || die "SHA-256 differs from the lock"
[ "$(head -c 4 "$FILE")" = "GGUF" ] || die "not a GGUF file"
echo "OK   $(basename "$FILE") ($SIZE bytes, sha256 $SHA) for release $TAG"
if [ "$DRY" = 1 ]; then echo "Dry run: nothing uploaded."; exit 0; fi

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 && die "release $TAG already exists: binaries under a tag never change; bump rephrase.release.tag"
cp "$LICENSE" "$ROOT/vendor/models/rephrase-assets/LICENSE-Qwen2.5-1.5B-Instruct.txt"
gh release create "$TAG" --repo "$REPO" --title "Wording model $TAG" \
  --notes "Optional wording model for Palaya Chess (Natural wording): Qwen2.5-1.5B-Instruct Q4_K_M GGUF by the Qwen team (Alibaba Cloud), Apache License 2.0, unmodified copy of https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF at commit 91cad51170dc346986eccefdc2dd33a9da36ead9. SHA-256 $SHA, $SIZE bytes. Pinned in vendor/models/MODELS.lock." \
  "$FILE" "$ROOT/vendor/models/rephrase-assets/LICENSE-Qwen2.5-1.5B-Instruct.txt"
echo "Published $TAG."
