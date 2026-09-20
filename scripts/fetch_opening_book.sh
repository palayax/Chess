#!/usr/bin/env bash
# Rebuilds app/src/main/assets/openings.tsv from lichess-org/chess-openings (CC0).
# Usage: scripts/fetch_opening_book.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/openings.tsv"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
for f in a b c d e; do
  curl -fsSL -o "$TMP/$f.tsv" "https://raw.githubusercontent.com/lichess-org/chess-openings/master/$f.tsv"
done
{ printf 'eco\tname\tpgn\n'; for f in a b c d e; do tail -n +2 "$TMP/$f.tsv"; done; } > "$OUT"
echo "Wrote $(wc -l < "$OUT") lines to $OUT"
