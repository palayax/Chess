#!/usr/bin/env bash
# Downloads both neural-TTS voice-model archives (if not already cached) and pushes them to the
# connected device/emulator, so :app's instrumented tests exercise the real models without
# re-downloading ~120 MB on every run.
#
# Exactly the pattern scripts/push_test_net.sh uses for the Stockfish NNUE net, and for the same
# reason. NeuralTtsProviderInstrumentedTest deliberately FAILS (it does not `assumeTrue`-skip)
# when an archive is missing, so forgetting this is loud rather than a vacuous pass.
#
# Usage: scripts/push_voice_models.sh [cache_dir]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${1:-${TMPDIR:-/tmp}}"
ADB="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}/platform-tools/adb"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

# name  sha256 — both mirrored from VoiceModelProvisioner.PIPER_SPEC / KOKORO_SPEC. If they ever
# disagree, the code is the source of truth and this script is stale.
MODELS=(
  "vits-piper-en_US-ljspeech-medium-int8.tar.bz2 24dc3bd77dd48c291e52c297878d3437c9492f245d823d7f6a06c4bbb67f4b6b"
  "kokoro-int8-en-v0_19.tar.bz2 c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd"
)

for entry in "${MODELS[@]}"; do
  NAME="${entry%% *}"
  EXPECTED="${entry##* }"
  LOCAL="$CACHE/$NAME"
  REMOTE="/data/local/tmp/$NAME"

  if [ ! -f "$LOCAL" ]; then
    echo "Downloading $NAME ..."
    curl -fSL --progress-bar -o "$LOCAL" "$BASE/$NAME"
  fi

  # Same SHA-256 the app pins and verifies before extracting — checked here too so a truncated
  # download is caught on the host rather than surfacing as a confusing on-device failure.
  ACTUAL="$(sha256sum "$LOCAL" | cut -d' ' -f1)"
  if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "Checksum mismatch for $NAME: expected $EXPECTED, got $ACTUAL. Deleting corrupt download." >&2
    rm -f "$LOCAL"
    exit 1
  fi

  echo "Pushing $(du -h "$LOCAL" | cut -f1) to $REMOTE ..."
  # MSYS_NO_PATHCONV stops Git Bash on Windows rewriting /data/local/tmp into a Windows path.
  MSYS_NO_PATHCONV=1 "$ADB" push "$LOCAL" "$REMOTE"
  MSYS_NO_PATHCONV=1 "$ADB" shell chmod 644 "$REMOTE"
done

echo "Done. Run: ./gradlew :app:connectedDebugAndroidTest"
