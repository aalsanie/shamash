#!/usr/bin/env bash
set -euo pipefail

VERSION=\${1:?version required}
[[ "$VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || {
  echo "Invalid Shamash version: expected MAJOR.MINOR.PATCH." >&2
  exit 1
}
DEST=\${2:-"$HOME/.local/share/shamash/$VERSION"}
ZIP="shamash-cli-$VERSION.zip"

DEST=$(python3 - "$DEST" <<'PY'
import os, pathlib, sys
p = pathlib.Path(os.path.abspath(os.path.expanduser(sys.argv[1])))
home = pathlib.Path.home().resolve()
cwd = pathlib.Path.cwd().resolve()
if p == p.parent or p == home or p == cwd or p in cwd.parents or p in home.parents:
    sys.exit('Unsafe installation destination')
for ancestor in (p, *p.parents):
    if ancestor.is_symlink():
        sys.exit('Installation destination must not traverse a symbolic link')
print(p)
PY
) || exit 1
if [[ -e "$DEST" || -L "$DEST" ]]; then
  [[ -d "$DEST" && ! -L "$DEST" && -f "$DEST/.shamash-install" && ! -L "$DEST/.shamash-install" ]] || {
    echo "Refusing to replace a destination not owned by Shamash: $DEST" >&2
    exit 1
  }
  IFS= read -r marker < "$DEST/.shamash-install" || true
  [[ "$marker" =~ ^shamash-cli:(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || {
    echo "Refusing to replace an unrecognized Shamash installation: $DEST" >&2
    exit 1
  }
fi

mkdir -p "$(dirname "$DEST")"
TMP=$(mktemp -d)
STAGE=$(mktemp -d "$(dirname "$DEST")/.shamash-stage-XXXXXXXX")
BACKUP=""
cleanup() {
  local status=$?
  if (( status != 0 )) && [[ -n "$BACKUP" && -d "$BACKUP" && ! -e "$DEST" ]]; then
    mv -- "$BACKUP" "$DEST" || echo "Restore installation manually from $BACKUP" >&2
  fi
  rm -rf -- "$TMP" "$STAGE"
}
trap cleanup EXIT

fetch_release_file() {
  local name=$1 out=$2 tag
  for tag in "v$VERSION" "$VERSION"; do
    if curl --fail --silent --show-error --location \
      "https://github.com/aalsanie/shamash/releases/download/$tag/$name" --output "$out"; then
      return 0
    fi
  done
  echo "Unable to download $name for Shamash $VERSION." >&2
  return 1
}
fetch_release_file "$ZIP" "$TMP/$ZIP"
fetch_release_file "SHA256SUMS.txt" "$TMP/SHA256SUMS.txt"
EXPECTED=$(awk -v f="$ZIP" '$2 == f && $1 ~ /^[0-9a-fA-F]+$/ && length($1) == 64 {print tolower($1)}' "$TMP/SHA256SUMS.txt")
[[ "$EXPECTED" =~ ^[0-9a-f]{64}$ ]] || { echo "Missing or ambiguous checksum for $ZIP" >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$TMP/$ZIP" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL=$(shasum -a 256 "$TMP/$ZIP" | awk '{print $1}')
else
  echo "SHA-256 utility not found" >&2
  exit 1
fi
[[ "$EXPECTED" == "$ACTUAL" ]] || { echo "Checksum mismatch for $ZIP" >&2; exit 1; }

python3 - "$TMP/$ZIP" "$STAGE" "$VERSION" <<'PY'
import pathlib, shutil, stat, sys, zipfile
archive_path, stage, version = sys.argv[1:]
root = 'shamash-cli-' + version
stage_path = pathlib.Path(stage)
with zipfile.ZipFile(archive_path) as archive:
    entries = archive.infolist()
    if not entries or len(entries) > 10000 or sum(i.file_size for i in entries) > 2_000_000_000:
        sys.exit('Invalid distribution archive size')
    seen = set()
    for info in entries:
        name = info.filename
        parts = name.rstrip('/').split('/')
        mode = info.external_attr >> 16
        if (not name or '\\' in name or name.startswith('/') or
            parts[0] != root or any(part in ('', '.', '..') for part in parts) or
            stat.S_ISLNK(mode) or (mode & 0o170000 not in (0, stat.S_IFREG, stat.S_IFDIR))):
            sys.exit('Unsafe distribution archive entry')
        if len(parts) == 1 and not info.is_dir():
            sys.exit('Unexpected distribution root file')
        key = name.rstrip('/').lower()
        if key in seen:
            sys.exit('Duplicate distribution entry')
        seen.add(key)
    for info in entries:
        target = stage_path.joinpath(*info.filename.rstrip('/').split('/'))
        if info.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as src, target.open('xb') as dst:
                shutil.copyfileobj(src, dst)
launcher = stage_path / root / 'bin' / 'shamash'
windows_launcher = stage_path / root / 'bin' / 'shamash.bat'
lib = stage_path / root / 'lib'
if not launcher.is_file() or not windows_launcher.is_file() or not lib.is_dir() or not list(lib.glob('*.jar')):
    sys.exit('Distribution is missing its launcher or libraries')
launcher.chmod(launcher.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
PY
printf 'shamash-cli:%s\n' "$VERSION" > "$STAGE/.shamash-install"
if [[ -e "$DEST" ]]; then
  BACKUP=$(mktemp -d "$(dirname "$DEST")/.shamash-previous-XXXXXXXX")
  rmdir -- "$BACKUP"
  mv -- "$DEST" "$BACKUP"
fi
if ! mv -- "$STAGE" "$DEST"; then
  echo "Unable to publish Shamash installation" >&2
  exit 1
fi
if [[ -n "$BACKUP" ]]; then
  rm -rf -- "$BACKUP"
fi
printf '%s\n' "$DEST/shamash-cli-$VERSION/bin/shamash"
