#!/usr/bin/env bash
# Reproducibly (re-)fetch the vendored llama.cpp source used by :rephrase (C2, docs/LLM_REPHRASE_DESIGN.md §2.2).
#
# vendor/llama.cpp/ is gitignored (~200 MB, MIT upstream); this script is the canonical way to materialize it.
# Pinned to the tag/commit in vendor/LLAMA_CPP_VERSION.txt, which must equal rephrase.runtime.tag in
# vendor/models/MODELS.lock (the :rephrase build checks both).
#
# Usage:
#   scripts/fetch_llama_cpp.sh
set -euo pipefail

REPO_URL="https://github.com/ggml-org/llama.cpp.git"
TAG="b11190"
EXPECTED_COMMIT="fcc891545b0f06de346d8f67d1e6c61f9bf0e777"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST="${ROOT_DIR}/vendor/llama.cpp"

echo "Fetching llama.cpp ${TAG} (${EXPECTED_COMMIT}) into ${DEST} ..."

rm -rf "${DEST}"
git clone --depth 1 --branch "${TAG}" "${REPO_URL}" "${DEST}"

ACTUAL_COMMIT="$(git -C "${DEST}" rev-parse HEAD)"
if [ "${ACTUAL_COMMIT}" != "${EXPECTED_COMMIT}" ]; then
    echo "ERROR: fetched commit ${ACTUAL_COMMIT} does not match pinned commit ${EXPECTED_COMMIT}" >&2
    echo "The '${TAG}' tag upstream may have moved. Investigate before building." >&2
    exit 1
fi

echo "OK: vendor/llama.cpp is at ${TAG} (${ACTUAL_COMMIT})"
