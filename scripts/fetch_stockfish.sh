#!/usr/bin/env bash
# Reproducibly (re-)fetch the vendored Stockfish source used by :engine.
#
# vendor/Stockfish/ is gitignored (source is ~50 MB and GPLv3 upstream, not
# something we want duplicated in our repo history), so this script is the
# canonical way to (re)materialize it — including in CI or a clean checkout.
#
# Usage:
#   scripts/fetch_stockfish.sh
#
# Pinned to the exact tag/commit recorded in vendor/STOCKFISH_VERSION.txt.
set -euo pipefail

REPO_URL="https://github.com/official-stockfish/Stockfish.git"
TAG="sf_19"
EXPECTED_COMMIT="edb0d9db6731067ec50ce619ff372b463bc4dd5d"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST="${ROOT_DIR}/vendor/Stockfish"

echo "Fetching Stockfish ${TAG} (${EXPECTED_COMMIT}) into ${DEST} ..."

rm -rf "${DEST}"
git clone --depth 1 --branch "${TAG}" "${REPO_URL}" "${DEST}"

ACTUAL_COMMIT="$(git -C "${DEST}" rev-parse HEAD)"
if [ "${ACTUAL_COMMIT}" != "${EXPECTED_COMMIT}" ]; then
    echo "ERROR: fetched commit ${ACTUAL_COMMIT} does not match pinned commit ${EXPECTED_COMMIT}" >&2
    echo "The 'sf_19' tag upstream may have moved. Investigate before building." >&2
    exit 1
fi

echo "OK: vendor/Stockfish is at ${TAG} (${ACTUAL_COMMIT})"
