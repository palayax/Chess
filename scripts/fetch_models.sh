#!/usr/bin/env bash
# Fetches and verifies the two models that are bundled into the APK, so the app works offline
# from its first launch (RUN_PLAN Round 13, docs/BUNDLED_MODELS_DESIGN.md):
#
#   vendor/models/engine-assets/nnue/<EvalFileDefaultName>      the Stockfish NNUE net   (:engine assets)
#   vendor/models/app-assets/tts/kokoro-int8-en-v0_19.tar       the Kokoro voice, bunzip2'd (:app assets)
#
# The files are ~257 MB, are NOT committed (vendor/models/* is gitignored), and are pinned by
# vendor/models/MODELS.lock. The net's name comes from vendor/Stockfish/src/evaluate.h so there is
# exactly one source of truth for it (CLAUDE.md engine gotcha 5).
#
# Safe to re-run: anything already present and verified is left alone; anything that fails
# verification is deleted and the script exits non-zero.
#
# Usage: scripts/fetch_models.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/vendor/models/MODELS.lock"
NET_DIR="$ROOT/vendor/models/engine-assets/nnue"
TTS_DIR="$ROOT/vendor/models/app-assets/tts"
CACHE="$ROOT/vendor/models/.cache"

die() { echo "ERROR: $*" >&2; exit 1; }

[ -f "$LOCK" ] || die "missing $LOCK"
prop() { grep -E "^$1=" "$LOCK" | head -1 | cut -d= -f2-; }
size_of() { stat -c %s "$1" 2>/dev/null || stat -f %z "$1"; }
sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

mkdir -p "$NET_DIR" "$TTS_DIR" "$CACHE"

# ---------------------------------------------------------------- NNUE net
NET_NAME="$(grep -oE 'nn-[0-9a-f]+\.nnue' "$ROOT/vendor/Stockfish/src/evaluate.h" | head -1 || true)"
[ -n "$NET_NAME" ] || die "cannot read the net name from vendor/Stockfish/src/evaluate.h (run scripts/fetch_stockfish.sh first)"
NET_FILE="$NET_DIR/$NET_NAME"
NET_SIZE="$(prop net.size)"
NET_URL="$(prop net.url.base)$NET_NAME"
# The filename encodes the first 12 hex digits of the file's own SHA-256.
NET_PREFIX="$(echo "$NET_NAME" | sed -E 's/^nn-([0-9a-f]+)\.nnue$/\1/')"

net_ok() {
  [ -f "$NET_FILE" ] || return 1
  [ "$(size_of "$NET_FILE")" = "$NET_SIZE" ] || return 1
  [ "$(sha256_of "$NET_FILE" | cut -c1-${#NET_PREFIX})" = "$NET_PREFIX" ] || return 1
}

# Remove stale nets left behind by an earlier Stockfish version, so a bump never ships two nets.
for f in "$NET_DIR"/nn-*.nnue; do
  [ -e "$f" ] || continue
  [ "$f" = "$NET_FILE" ] || { echo "Removing stale net $(basename "$f")"; rm -f "$f"; }
done

if net_ok; then
  echo "OK   net  $NET_NAME ($NET_SIZE bytes)"
else
  echo "Downloading $NET_NAME ..."
  rm -f "$NET_FILE" "$NET_FILE.part"
  curl -fSL --retry 5 --retry-all-errors --retry-delay 5 -o "$NET_FILE.part" "$NET_URL"
  mv "$NET_FILE.part" "$NET_FILE"
  if ! net_ok; then
    rm -f "$NET_FILE"
    die "net failed verification (size must be $NET_SIZE, SHA-256 must start with $NET_PREFIX); deleted"
  fi
  echo "OK   net  $NET_NAME ($NET_SIZE bytes)"
fi

# ---------------------------------------------------------------- Kokoro voice
ARCHIVE_URL="$(prop kokoro.archive.url)"
ARCHIVE_SHA="$(prop kokoro.archive.sha256)"
ARCHIVE_SIZE="$(prop kokoro.archive.size)"
TAR_SHA="$(prop kokoro.tar.sha256)"
TAR_SIZE="$(prop kokoro.tar.size)"
ARCHIVE="$CACHE/kokoro-int8-en-v0_19.tar.bz2"
TAR="$TTS_DIR/kokoro-int8-en-v0_19.tar"

tar_ok() {
  [ -f "$TAR" ] || return 1
  [ -n "$TAR_SHA" ] && [ -n "$TAR_SIZE" ] || return 1       # not pinned yet: must be (re)built and pinned
  [ "$(size_of "$TAR")" = "$TAR_SIZE" ] || return 1
  [ "$(sha256_of "$TAR")" = "$TAR_SHA" ]
}

if tar_ok; then
  echo "OK   voice kokoro-int8-en-v0_19.tar ($TAR_SIZE bytes)"
else
  if [ ! -f "$ARCHIVE" ] || [ "$(sha256_of "$ARCHIVE")" != "$ARCHIVE_SHA" ]; then
    echo "Downloading the Kokoro archive ..."
    rm -f "$ARCHIVE" "$ARCHIVE.part"
    curl -fSL --retry 5 --retry-all-errors --retry-delay 5 -o "$ARCHIVE.part" "$ARCHIVE_URL"
    mv "$ARCHIVE.part" "$ARCHIVE"
  fi
  [ "$(size_of "$ARCHIVE")" = "$ARCHIVE_SIZE" ] || { rm -f "$ARCHIVE"; die "archive size is not $ARCHIVE_SIZE; deleted"; }
  [ "$(sha256_of "$ARCHIVE")" = "$ARCHIVE_SHA" ] || { rm -f "$ARCHIVE"; die "archive SHA-256 mismatch; deleted"; }
  echo "OK   archive verified ($ARCHIVE_SIZE bytes, SHA-256 matches the pin)"

  echo "Decompressing to a plain .tar (bz2 is slow on a phone, so the APK ships the tar) ..."
  rm -f "$TAR" "$TAR.part"
  if command -v bunzip2 >/dev/null 2>&1; then
    bunzip2 -c "$ARCHIVE" > "$TAR.part"
  else
    python -c "import bz2,shutil,sys; shutil.copyfileobj(bz2.open(sys.argv[1],'rb'), open(sys.argv[2],'wb'))" "$ARCHIVE" "$TAR.part"
  fi
  mv "$TAR.part" "$TAR"

  GOT_SHA="$(sha256_of "$TAR")"; GOT_SIZE="$(size_of "$TAR")"
  # The decompressed tar must be a readable tar with the files the app requires.
  tar tf "$TAR" | grep -q 'model.int8.onnx$' || { rm -f "$TAR"; die "tar has no model.int8.onnx; deleted"; }
  tar tf "$TAR" | grep -q 'voices.bin$'      || { rm -f "$TAR"; die "tar has no voices.bin; deleted"; }

  if [ -z "$TAR_SHA" ] || [ -z "$TAR_SIZE" ]; then
    # First successful run: pin the hash of what bunzip2 produced (verified-archive in, deterministic out).
    sed -i "s|^kokoro.tar.sha256=.*|kokoro.tar.sha256=$GOT_SHA|; s|^kokoro.tar.size=.*|kokoro.tar.size=$GOT_SIZE|" "$LOCK"
    echo "PINNED kokoro.tar.sha256=$GOT_SHA  kokoro.tar.size=$GOT_SIZE  (written to vendor/models/MODELS.lock; commit that file)"
  elif [ "$GOT_SHA" != "$TAR_SHA" ] || [ "$GOT_SIZE" != "$TAR_SIZE" ]; then
    rm -f "$TAR"
    die "tar does not match the pin (got sha256=$GOT_SHA size=$GOT_SIZE); deleted"
  fi
  echo "OK   voice kokoro-int8-en-v0_19.tar ($GOT_SIZE bytes)"
fi

echo
echo "All models present and verified under vendor/models/. (The .cache/ archive can be deleted.)"
