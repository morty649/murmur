#!/usr/bin/env bash
# Downloads the pinned sherpa-onnx Android AAR into app/libs and verifies its checksum.
set -euo pipefail
VERSION=2.0.1
SHA256=a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs/sherpa-onnx-$VERSION.aar"

mkdir -p "$(dirname "$DEST")"
if [ ! -f "$DEST" ]; then
  curl -fsSL -o "$DEST" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar"
fi
echo "$SHA256  $DEST" | shasum -a 256 -c -
