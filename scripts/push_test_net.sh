#!/usr/bin/env bash
# Downloads the official Stockfish NNUE net (if not already cached) and pushes it to the
# connected device/emulator so the :engine instrumented tests can run a real search.
#
# The net is NOT bundled in the APK: we build Stockfish with NNUE_EMBEDDING_OFF, and the file is
# ~79 MB compressed / 98.5 MB on disk. The app downloads it at runtime; tests use this instead so
# they neither download nor depend on the network.
#
# Usage: scripts/push_test_net.sh [cache_dir]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${1:-${TMPDIR:-/tmp}}"
ADB="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}/platform-tools/adb"

# Single source of truth: the net name is whatever the vendored Stockfish declares.
NET_NAME="$(grep -oE 'nn-[0-9a-f]+\.nnue' "$ROOT/vendor/Stockfish/src/evaluate.h" | head -1)"
if [ -z "$NET_NAME" ]; then
  echo "Could not read the net name from vendor/Stockfish/src/evaluate.h" >&2
  echo "Run scripts/fetch_stockfish.sh first." >&2
  exit 1
fi

LOCAL="$CACHE/$NET_NAME"
REMOTE="/data/local/tmp/$NET_NAME"

if [ ! -f "$LOCAL" ]; then
  echo "Downloading $NET_NAME ..."
  curl -fSL --progress-bar -o "$LOCAL" "https://tests.stockfishchess.org/api/nn/$NET_NAME"
fi

# The filename encodes the first 12 hex digits of the file's own SHA-256 — verify before use, so
# a truncated download is caught here rather than inside the engine.
EXPECTED="$(echo "$NET_NAME" | sed -E 's/^nn-([0-9a-f]+)\.nnue$/\1/')"
ACTUAL="$(sha256sum "$LOCAL" | cut -c1-${#EXPECTED})"
if [ "$EXPECTED" != "$ACTUAL" ]; then
  echo "Checksum mismatch: expected $EXPECTED, got $ACTUAL. Deleting corrupt download." >&2
  rm -f "$LOCAL"
  exit 1
fi

echo "Pushing $(du -h "$LOCAL" | cut -f1) to $REMOTE ..."
# MSYS_NO_PATHCONV stops Git Bash on Windows rewriting /data/local/tmp into a Windows path.
MSYS_NO_PATHCONV=1 "$ADB" push "$LOCAL" "$REMOTE"
MSYS_NO_PATHCONV=1 "$ADB" shell chmod 644 "$REMOTE"
echo "Done. Run: ./gradlew :engine:connectedDebugAndroidTest"
