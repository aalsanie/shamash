#!/usr/bin/env bash
set -euo pipefail

VERSION=${1:?version required}
DEST=${2:-"$HOME/.local/share/shamash/$VERSION"}
ZIP="shamash-cli-$VERSION.zip"
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t shamash-install)
trap 'rm -rf "$TMP"' EXIT

fetch_release_file() {
  local name=$1
  local out=$2
  local tag
  for tag in "v$VERSION" "$VERSION"; do
    local url="https://github.com/aalsanie/shamash/releases/download/$tag/$name"
    if curl --fail --silent --show-error --location "$url" --output "$out"; then
      return 0
    fi
  done
  echo "Unable to download $name for Shamash $VERSION (tried tags v$VERSION and $VERSION)." >&2
  return 1
}

fetch_release_file "$ZIP" "$TMP/$ZIP"
fetch_release_file "SHA256SUMS.txt" "$TMP/SHA256SUMS.txt"

EXPECTED=$(awk -v f="$ZIP" '$2 == f {print $1}' "$TMP/SHA256SUMS.txt")
test -n "$EXPECTED" || { echo "Checksum entry missing for $ZIP" >&2; exit 1; }

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$TMP/$ZIP" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL=$(shasum -a 256 "$TMP/$ZIP" | awk '{print $1}')
else
  echo "Neither sha256sum nor shasum is available." >&2
  exit 1
fi
[[ "$EXPECTED" == "$ACTUAL" ]] || { echo "Checksum mismatch for $ZIP" >&2; exit 1; }

rm -rf "$DEST"
mkdir -p "$DEST"
python - "$TMP/$ZIP" "$DEST" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    archive.extractall(sys.argv[2])
PY

BIN=$(find "$DEST" -type f -path '*/bin/shamash' -print -quit)
test -n "$BIN" || { echo "bin/shamash not found after extraction" >&2; exit 1; }
chmod +x "$BIN"
printf '%s\n' "$BIN"
