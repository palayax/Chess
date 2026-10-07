#!/usr/bin/env bash
# Fetches and verifies the two model files the app downloads on first run (docs/MODEL_DOWNLOAD_DESIGN.md).
# They are NOT in the APK any more (D2a); this script is the developer/test/publishing fetcher:
#
#   vendor/models/engine-assets/nnue/<EvalFileDefaultName>      the Stockfish NNUE net
#   vendor/models/app-assets/tts/kokoro-int8-en-v0_19.tar.gz    the Kokoro voice as the app downloads it (D2f):
#                                                               the upstream .tar.bz2 bunzip2'd, then `gzip -9 -n`
#   vendor/models/.cache/kokoro-int8-en-v0_19.tar               that plain tar (an intermediate; can be deleted)
#
# Who needs them: scripts/publish_models.sh (uploads them to the GitHub release), scripts/model_test_server.py
# (serves them to a debug build), and the instrumented tests (D2d seeds them into the test APKs). A plain
# `./gradlew assembleDebug` does NOT need them: the build compiles the pins from vendor/models/MODELS.lock.
#
# The files are ~201 MB (plus the cache), are NOT committed (vendor/models/* is gitignored), and are pinned by
# vendor/models/MODELS.lock. The net's name comes from vendor/Stockfish/src/evaluate.h so there is
# exactly one source of truth for it (CLAUDE.md engine gotcha 5).
#
# On its first successful run it pins what is not pinned yet: net.sha256, net.arch_hash and net.version
# (read from the net's NNUE header), kokoro.tar.sha256/size (the bunzip2 output) and kokoro.targz.sha256/size
# (its `gzip -9 -n` output). Commit MODELS.lock then.
#
# The .tar.gz must be byte-identical to the pinned one. GNU gzip with -9 -n is reproducible (no name, no time
# stamp; checked with gzip 1.14); another gzip implementation (e.g. a BSD/zlib one) may compress differently,
# and then this script stops: take the published file from the GitHub release named in MODELS.lock instead
# (<MODEL_BASE_URL><release.tag>/kokoro-int8-en-v0_19.tar.gz) and put it in vendor/models/app-assets/tts/.
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

NET_SHA_PIN="$(prop net.sha256)"

net_ok() {
  [ -f "$NET_FILE" ] || return 1
  [ "$(size_of "$NET_FILE")" = "$NET_SIZE" ] || return 1
  local got
  got="$(sha256_of "$NET_FILE")"
  [ "$(echo "$got" | cut -c1-${#NET_PREFIX})" = "$NET_PREFIX" ] || return 1
  # Once pinned, the full hash must match too (the name only encodes 48 bits of it).
  [ -z "$NET_SHA_PIN" ] || [ "$got" = "$NET_SHA_PIN" ] || return 1
}

# Little-endian uint32 at byte offset $2 of file $1, as 8 lower-case hex digits.
le_u32_hex() {
  local b
  b=($(od -An -tx1 -j "$2" -N4 "$1"))
  [ "${#b[@]}" = 4 ] || return 1
  echo "${b[3]}${b[2]}${b[1]}${b[0]}"
}

# Writes net.sha256 / net.arch_hash / net.version into MODELS.lock if they are still empty, and checks
# them if they are not. Called once the net itself is verified.
pin_net() {
  local sha arch ver
  sha="$(sha256_of "$NET_FILE")"
  arch="$(le_u32_hex "$NET_FILE" 4)" || die "cannot read the net header"
  ver="0x$(le_u32_hex "$NET_FILE" 0)" || die "cannot read the net header"
  local pin_sha pin_arch pin_ver
  pin_sha="$(prop net.sha256)"; pin_arch="$(prop net.arch_hash)"; pin_ver="$(prop net.version)"
  if [ -z "$pin_sha" ] || [ -z "$pin_arch" ] || [ -z "$pin_ver" ]; then
    grep -qE '^net\.sha256=' "$LOCK" || echo "net.sha256=" >> "$LOCK"
    grep -qE '^net\.arch_hash=' "$LOCK" || echo "net.arch_hash=" >> "$LOCK"
    grep -qE '^net\.version=' "$LOCK" || echo "net.version=" >> "$LOCK"
    sed -i "s|^net\.sha256=.*|net.sha256=$sha|; s|^net\.arch_hash=.*|net.arch_hash=$arch|; s|^net\.version=.*|net.version=$ver|" "$LOCK"
    echo "PINNED net.sha256=$sha  net.arch_hash=$arch  net.version=$ver  (written to vendor/models/MODELS.lock; commit that file)"
  else
    [ "$sha" = "$pin_sha" ]   || die "net SHA-256 $sha does not match the pin $pin_sha"
    [ "$arch" = "$pin_arch" ] || die "net architecture hash $arch does not match the pin $pin_arch"
    [ "$ver" = "$pin_ver" ]   || die "net version $ver does not match the pin $pin_ver"
  fi
}

# Remove stale nets left behind by an earlier Stockfish version, so a bump never ships two nets.
for f in "$NET_DIR"/nn-*.nnue; do
  [ -e "$f" ] || continue
  [ "$f" = "$NET_FILE" ] || { echo "Removing stale net $(basename "$f")"; rm -f "$f"; }
done

if net_ok; then
  pin_net
  echo "OK   net  $NET_NAME ($NET_SIZE bytes)"
else
  echo "Downloading $NET_NAME ..."
  rm -f "$NET_FILE" "$NET_FILE.part"
  curl -fSL --retry 5 --retry-all-errors --retry-delay 5 -o "$NET_FILE.part" "$NET_URL"
  mv "$NET_FILE.part" "$NET_FILE"
  if ! net_ok; then
    rm -f "$NET_FILE"
    die "net failed verification (size must be $NET_SIZE, SHA-256 must start with $NET_PREFIX${NET_SHA_PIN:+ and equal $NET_SHA_PIN}); deleted"
  fi
  pin_net
  echo "OK   net  $NET_NAME ($NET_SIZE bytes)"
fi

# ---------------------------------------------------------------- Kokoro voice
ARCHIVE_URL="$(prop kokoro.archive.url)"
ARCHIVE_SHA="$(prop kokoro.archive.sha256)"
ARCHIVE_SIZE="$(prop kokoro.archive.size)"
TAR_SHA="$(prop kokoro.tar.sha256)"
TAR_SIZE="$(prop kokoro.tar.size)"
GZ_SHA="$(prop kokoro.targz.sha256)"
GZ_SIZE="$(prop kokoro.targz.size)"
ARCHIVE="$CACHE/kokoro-int8-en-v0_19.tar.bz2"
TAR="$CACHE/kokoro-int8-en-v0_19.tar"
GZ="$TTS_DIR/kokoro-int8-en-v0_19.tar.gz"

# Before D2f the plain tar lived in app-assets/tts/ (and was what the app downloaded): move it to the cache.
if [ -f "$TTS_DIR/kokoro-int8-en-v0_19.tar" ]; then
  echo "Moving the plain tar out of app-assets/tts/ into .cache/ (the app downloads the .tar.gz since D2f)"
  mv -f "$TTS_DIR/kokoro-int8-en-v0_19.tar" "$TAR"
fi

gz_ok() {
  [ -f "$GZ" ] || return 1
  [ -n "$GZ_SHA" ] && [ -n "$GZ_SIZE" ] || return 1          # not pinned yet: must be (re)built and pinned
  [ "$(size_of "$GZ")" = "$GZ_SIZE" ] || return 1
  [ "$(sha256_of "$GZ")" = "$GZ_SHA" ]
}

tar_ok() {
  [ -f "$TAR" ] || return 1
  [ -n "$TAR_SHA" ] && [ -n "$TAR_SIZE" ] || return 1       # not pinned yet: must be (re)built and pinned
  [ "$(size_of "$TAR")" = "$TAR_SIZE" ] || return 1
  [ "$(sha256_of "$TAR")" = "$TAR_SHA" ]
}

if gz_ok; then
  echo "OK   voice kokoro-int8-en-v0_19.tar.gz ($GZ_SIZE bytes)"
elif tar_ok; then
  echo "OK   voice kokoro-int8-en-v0_19.tar ($TAR_SIZE bytes, in .cache/)"
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

  echo "Decompressing to a plain .tar (bz2 is slow on a phone; the app downloads the tar gzipped) ..."
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
  echo "OK   voice kokoro-int8-en-v0_19.tar ($GOT_SIZE bytes, in .cache/)"
fi

# ---------------------------------------------------------------- the .tar.gz the app downloads (D2f)
if ! gz_ok; then
  command -v gzip >/dev/null 2>&1 || die "gzip is needed to make $GZ"
  echo "Compressing the tar with gzip -9 -n (what the app downloads) ..."
  rm -f "$GZ" "$GZ.part"
  gzip -9 -n -c "$TAR" > "$GZ.part"
  INFLATED="$(gzip -dc "$GZ.part" | sha256sum | cut -d' ' -f1)"
  [ "$INFLATED" = "$TAR_SHA" ] || { rm -f "$GZ.part"; die "the .tar.gz does not inflate to the pinned tar; deleted"; }
  mv "$GZ.part" "$GZ"
  GOT_SHA="$(sha256_of "$GZ")"; GOT_SIZE="$(size_of "$GZ")"
  if [ -z "$GZ_SHA" ] || [ -z "$GZ_SIZE" ]; then
    grep -qE '^kokoro\.targz\.sha256=' "$LOCK" || echo "kokoro.targz.sha256=" >> "$LOCK"
    grep -qE '^kokoro\.targz\.size=' "$LOCK" || echo "kokoro.targz.size=" >> "$LOCK"
    sed -i "s|^kokoro.targz.sha256=.*|kokoro.targz.sha256=$GOT_SHA|; s|^kokoro.targz.size=.*|kokoro.targz.size=$GOT_SIZE|" "$LOCK"
    echo "PINNED kokoro.targz.sha256=$GOT_SHA  kokoro.targz.size=$GOT_SIZE  (written to vendor/models/MODELS.lock; commit that file)"
  elif [ "$GOT_SHA" != "$GZ_SHA" ] || [ "$GOT_SIZE" != "$GZ_SIZE" ]; then
    rm -f "$GZ"
    die "this gzip ($(gzip --version | head -1)) made a different .tar.gz (sha256=$GOT_SHA size=$GOT_SIZE) than the pin; deleted. Take the published one from the release in MODELS.lock (see the header of this script)."
  fi
  echo "OK   voice kokoro-int8-en-v0_19.tar.gz ($GOT_SIZE bytes)"
fi

echo
echo "All models present and verified under vendor/models/. (The .cache/ archive and tar can be deleted.)"
