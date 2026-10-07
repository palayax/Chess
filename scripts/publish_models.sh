#!/usr/bin/env bash
# Publishes the two model files the app downloads on first run, and the signed upgrade manifest
# (docs/MODEL_DOWNLOAD_DESIGN.md §3.1, §3.2, §5). Run it BEFORE releasing an app build that points at
# the tag: a fresh install of that build downloads <MODEL_BASE_URL><tag>/<file> and nothing else.
#
#   scripts/publish_models.sh <tag> --min-version-code N [--repo owner/name] [--dry-run]
#   scripts/publish_models.sh --verify [manifest]     (default dist/models/models.json)
#   scripts/publish_models.sh --sign <manifest>       (a hand-made manifest, e.g. a local test one)
#
#   <tag>                 the immutable release that holds the binaries, models-YYYY.MM[.n]; it must equal
#                         release.tag in vendor/models/MODELS.lock (the app compiles that value in).
#   --min-version-code N  the oldest app versionCode the manifest entries are offered to (upgrades, D2e).
#   --repo owner/name     default palayax/Chess (the owner's public repo).
#   --dry-run             verify the files and write dist/models/models.json, but do not sign or upload.
#   --verify [file]       check <file>.sig against the committed public key (vendor/models/manifest_public_key.der)
#                         and that <file> is a schema-1 manifest; exit 0 only if both hold. Needs no private key.
#   --sign <file>         sign <file> into <file>.sig with keystore/models-signing.pem, then --verify it. For
#                         manifests made by hand (the D2e emulator run serves one from scripts/model_test_server.py
#                         --manifest-dir). Prints nothing about the key except its public fingerprint.
#
# Steps: (1) verify vendor/models/ against MODELS.lock (size + full SHA-256, the net's NNUE header; the
# voice .tar.gz and the tar it inflates to); (2) write dist/models/models.json from the lock; (3) sign it
# with keystore/models-signing.pem (ECDSA P-256, SHA256withECDSA, DER signature in models.json.sig);
# (4) gh release create <tag> with the net and the voice .tar.gz; (5) gh release upload models --clobber
# models.json models.json.sig (creates the rolling `models` release on first use). Binaries never change
# under a tag: a new file = a new tag.
#
# Voice format (D2f): the app downloads kokoro-int8-en-v0_19.tar.gz (the tar gzipped with `gzip -9 -n`,
# 102.5 MB instead of 158.3 MB) and checks the tar inside against kokoro.tar.*. The manifest's voice entry
# carries both: size/sha256 of the .tar.gz and tarSize/tarSha256 of the tar.
#
# --min-version-code: versionCode 1 is the bundled R7 build (it has no update check at all); 2 is the first
# downloading build (versionName 1.1). Publish the first manifest with --min-version-code 2.
#
# One-time key setup (keep the .pem with the release keystore and back it up offline; losing it means no
# model upgrades until an app update rotates the public key; a fresh install is unaffected):
#   openssl ecparam -genkey -name prime256v1 -noout -out keystore/models-signing.pem
#   openssl ec -in keystore/models-signing.pem -pubout -outform DER -out vendor/models/manifest_public_key.der
# (*.pem is gitignored; manifest_public_key.der is committed and compiled into the app as
# GeneratedModelPins.MANIFEST_PUBLIC_KEY_DER_BASE64 by :app's generateModelPins. Key custody: docs/PUBLISHING.md.)
# Never print the .pem: this script only ever passes its path to openssl.
#
# Needs: bash, sha256sum, od, gzip, openssl, python (for --verify's JSON check), gh (authenticated, upload only).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/vendor/models/MODELS.lock"
KEY="$ROOT/keystore/models-signing.pem"
OUT="$ROOT/dist/models"

die() { echo "ERROR: $*" >&2; exit 1; }
prop() { grep -E "^$1=" "$LOCK" | head -1 | cut -d= -f2- | tr -d '\r'; }
size_of() { stat -c %s "$1" 2>/dev/null || stat -f %z "$1"; }
sha256_of() { sha256sum "$1" | cut -d' ' -f1; }
le_u32_hex() { local b; b=($(od -An -tx1 -j "$2" -N4 "$1")); echo "${b[3]}${b[2]}${b[1]}${b[0]}"; }

PUB="$ROOT/vendor/models/manifest_public_key.der"

# --verify <file>: signature with the committed public key + a schema check. No private key needed.
verify_manifest() {
  local file="$1"
  [ -f "$file" ] || die "missing $file"
  [ -f "$file.sig" ] || die "missing $file.sig"
  [ -f "$PUB" ] || die "missing $PUB (it is committed; see docs/PUBLISHING.md)"
  openssl dgst -sha256 -verify "$PUB" -keyform DER -signature "$file.sig" "$file" >/dev/null 2>&1 \
    || die "$file.sig does NOT verify with $(basename "$PUB")"
  # The first python that actually runs (Windows has a python3 "app execution alias" that only prints a hint).
  local py="" c
  for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c "import json" >/dev/null 2>&1; then py="$c"; break; fi
  done
  if [ -n "$py" ]; then
    "$py" - "$file" <<'PY' || die "$1 is not a valid schema-1 manifest"
import json, re, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))
assert m.get("schemaVersion") == 1, "schemaVersion"
for e in m["models"]:
    for k in ("id", "displayName", "version", "fileName", "url", "size", "sha256", "minVersionCode", "compat"):
        assert k in e, k
    assert re.fullmatch(r"[0-9a-f]{64}", e["sha256"]), "sha256"
    assert isinstance(e["size"], int) and e["size"] > 0, "size"
    if e["fileName"].endswith(".tar.gz"):  # D2f: the app checks the tar inside against these
        assert re.fullmatch(r"[0-9a-f]{64}", e.get("tarSha256", "")), "tarSha256"
        assert isinstance(e.get("tarSize"), int) and e["tarSize"] > 0, "tarSize"
print(f"OK   {len(m['models'])} entr{'y' if len(m['models']) == 1 else 'ies'}: " + ", ".join(f"{e['id']} {e['version']}" for e in m["models"]))
PY
  else
    echo "WARNING: no python found; JSON not checked"
  fi
  echo "OK   $(basename "$file").sig verifies with $(basename "$PUB") (SHA-256 of the key: $(sha256_of "$PUB"))"
}

if [ "${1:-}" = "--verify" ]; then
  verify_manifest "${2:-$OUT/models.json}"
  exit 0
fi
if [ "${1:-}" = "--sign" ]; then
  [ -n "${2:-}" ] || die "usage: $0 --sign <manifest>"
  [ -f "$KEY" ] || die "missing $KEY (see the one-time key setup at the top of this script)"
  openssl dgst -sha256 -sign "$KEY" -out "$2.sig" "$2"
  echo "SIGNED $2 -> $2.sig"
  verify_manifest "$2"
  exit 0
fi

TAG=""; MIN_VC=""; REPO="palayax/Chess"; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --min-version-code) MIN_VC="${2:-}"; shift 2 ;;
    --repo) REPO="${2:-}"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) [ -z "$TAG" ] || die "one tag only"; TAG="$1"; shift ;;
  esac
done
[ -n "$TAG" ] || die "usage: $0 <tag> --min-version-code N [--repo owner/name] [--dry-run]"
[[ "$TAG" =~ ^models-[0-9]{4}\.[0-9]{2}(\.[0-9]+)?$ ]] || die "tag $TAG does not match models-YYYY.MM[.n]"
[[ "$MIN_VC" =~ ^[0-9]+$ ]] || die "--min-version-code N is required (a positive integer)"
[ -f "$LOCK" ] || die "missing $LOCK"
[ "$(prop release.tag)" = "$TAG" ] || die "MODELS.lock release.tag is '$(prop release.tag)', not $TAG: the app downloads from the lock's tag"

# ---------------------------------------------------------------- 1. verify the files against the lock
NET_NAME="$(grep -oE 'nn-[0-9a-f]+\.nnue' "$ROOT/vendor/Stockfish/src/evaluate.h" | head -1 || true)"
[ -n "$NET_NAME" ] || die "cannot read the net name from vendor/Stockfish/src/evaluate.h"
NET="$ROOT/vendor/models/engine-assets/nnue/$NET_NAME"
NET_SIZE="$(prop net.size)"; NET_SHA="$(prop net.sha256)"; NET_ARCH="$(prop net.arch_hash)"; NET_VER="$(prop net.version)"
TAR_URL="$(prop kokoro.archive.url)"; TAR_NAME="$(basename "$TAR_URL" .bz2)"; GZ_NAME="$TAR_NAME.gz"
GZ="$ROOT/vendor/models/app-assets/tts/$GZ_NAME"
TAR_SIZE="$(prop kokoro.tar.size)"; TAR_SHA="$(prop kokoro.tar.sha256)"
GZ_SIZE="$(prop kokoro.targz.size)"; GZ_SHA="$(prop kokoro.targz.sha256)"
for v in NET_SIZE NET_SHA NET_ARCH NET_VER TAR_SIZE TAR_SHA GZ_SIZE GZ_SHA; do [ -n "${!v}" ] || die "MODELS.lock: $v is not pinned (run scripts/fetch_models.sh)"; done
[ -f "$NET" ] || die "missing $NET (run scripts/fetch_models.sh)"
[ -f "$GZ" ] || die "missing $GZ (run scripts/fetch_models.sh)"
[ "$(size_of "$NET")" = "$NET_SIZE" ] || die "$NET_NAME size differs from the lock"
[ "$(sha256_of "$NET")" = "$NET_SHA" ] || die "$NET_NAME SHA-256 differs from the lock"
[ "$(le_u32_hex "$NET" 4)" = "$NET_ARCH" ] || die "$NET_NAME architecture hash differs from the lock"
[ "0x$(le_u32_hex "$NET" 0)" = "$NET_VER" ] || die "$NET_NAME NNUE version differs from the lock"
[ "$(size_of "$GZ")" = "$GZ_SIZE" ] || die "$GZ_NAME size differs from the lock"
[ "$(sha256_of "$GZ")" = "$GZ_SHA" ] || die "$GZ_NAME SHA-256 differs from the lock"
# The app checks the tar inside against kokoro.tar.*: a .tar.gz that inflates to anything else is never published.
INFLATED="$(gzip -dc "$GZ" | sha256sum | cut -d' ' -f1)"
[ "$INFLATED" = "$TAR_SHA" ] || die "$GZ_NAME inflates to SHA-256 $INFLATED, the lock's tar is $TAR_SHA"
echo "OK   $NET_NAME and $GZ_NAME (and the tar inside) match MODELS.lock"

# ---------------------------------------------------------------- 2. the upgrade manifest
SF_TAG="$(grep -oE 'Tag:[[:space:]]*[^[:space:]]+' "$ROOT/vendor/STOCKFISH_VERSION.txt" | head -1 | awk '{print $2}')"
SHERPA="$(grep -E '^sherpaOnnx[[:space:]]*=' "$ROOT/gradle/libs.versions.toml" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
[ -n "$SF_TAG" ] && [ -n "$SHERPA" ] || die "cannot read the Stockfish tag or the sherpa-onnx version"
BASE="https://github.com/$REPO/releases/download"
mkdir -p "$OUT"
cat > "$OUT/models.json" <<JSON
{
  "schemaVersion": 1,
  "generatedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "models": [
    {
      "id": "engine-net",
      "displayName": "Chess engine data",
      "version": "${NET_NAME%.nnue}",
      "fileName": "$NET_NAME",
      "url": "$BASE/$TAG/$NET_NAME",
      "size": $NET_SIZE,
      "sha256": "$NET_SHA",
      "minVersionCode": $MIN_VC,
      "maxVersionCode": null,
      "compat": { "kind": "stockfish-nnue", "version": "$NET_VER", "archHash": "$NET_ARCH", "engineTag": "$SF_TAG" }
    },
    {
      "id": "voice-kokoro-en",
      "displayName": "Narration voice",
      "version": "v0_19",
      "fileName": "$GZ_NAME",
      "url": "$BASE/$TAG/$GZ_NAME",
      "size": $GZ_SIZE,
      "sha256": "$GZ_SHA",
      "tarSize": $TAR_SIZE,
      "tarSha256": "$TAR_SHA",
      "minVersionCode": $MIN_VC,
      "maxVersionCode": null,
      "compat": { "kind": "sherpa-onnx-kokoro", "layout": "kokoro-v0_19" },
      "runtime": { "name": "sherpa-onnx", "min": "$SHERPA", "max": "$SHERPA" }
    }
  ]
}
JSON
echo "WROTE $OUT/models.json"

if [ "$DRY" = 1 ]; then
  echo "Dry run: not signed, nothing uploaded."
  exit 0
fi

# ---------------------------------------------------------------- 3. sign
[ -f "$KEY" ] || die "missing $KEY (see the one-time key setup at the top of this script)"
openssl dgst -sha256 -sign "$KEY" -out "$OUT/models.json.sig" "$OUT/models.json"
# The app verifies with the committed public key: a manifest that does not verify with it is never uploaded.
verify_manifest "$OUT/models.json"

# ---------------------------------------------------------------- 4 + 5. upload
command -v gh >/dev/null 2>&1 || die "gh (GitHub CLI) is not installed"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  die "release $TAG already exists in $REPO: binaries under a tag are immutable, use a new tag (models-YYYY.MM.n)"
fi
gh release create "$TAG" --repo "$REPO" --title "Model files $TAG" \
  --notes "Engine net $NET_NAME and narration voice $GZ_NAME for Palaya Chess. Pinned in vendor/models/MODELS.lock (SHA-256 $NET_SHA and $GZ_SHA; the tar inside $TAR_SHA)." \
  "$NET" "$GZ"
if ! gh release view models --repo "$REPO" >/dev/null 2>&1; then
  gh release create models --repo "$REPO" --title "Model manifest" \
    --notes "Rolling release: models.json (signed) lists the model files Palaya Chess may offer through Check for updates."
fi
gh release upload models --repo "$REPO" --clobber "$OUT/models.json" "$OUT/models.json.sig"
echo "Published $TAG and the manifest to $REPO."
