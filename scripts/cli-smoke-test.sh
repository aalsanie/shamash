#!/usr/bin/env bash
set -euo pipefail

ZIP=${1:?usage: cli-smoke-test.sh <shamash-cli.zip>}
ZIP=$(ls $ZIP | head -1)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t shamash-smoke)
trap 'rm -rf "$TMP"' EXIT

PY_ZIP="$ZIP"
PY_TMP="$TMP"
if [[ "${OS:-}" == "Windows_NT" ]]; then
  PY_ZIP=$(cygpath -aw "$ZIP")
  PY_TMP=$(cygpath -aw "$TMP")
fi

python - "$PY_ZIP" "$PY_TMP" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    z.extractall(sys.argv[2])
PY

ROOT=$(find "$TMP" -maxdepth 1 -type d -name 'shamash-*' -print -quit)
test -n "$ROOT"

if [[ "${OS:-}" == "Windows_NT" ]]; then
  BAT=$(cygpath -aw "$ROOT/bin/shamash.bat")
  cmd.exe /d /s /c "\"$BAT\" version" | grep -q 'shamash-cli'
else
  chmod +x "$ROOT/bin/shamash"
  "$ROOT/bin/shamash" version | grep -q 'shamash-cli'
fi

mkdir -p "$TMP/fixture/build/classes/java/main/com/example"
cat > "$TMP/fixture/App.java" <<'JAVA'
package com.example; public class App { public static void main(String[] args) {} }
JAVA
javac -d "$TMP/fixture/build/classes/java/main" "$TMP/fixture/App.java"

set +e
if [[ "${OS:-}" == "Windows_NT" ]]; then
  OUTPUT=$(cd "$TMP/fixture" && cmd.exe /d /s /c "\"$BAT\" scan" 2>&1)
else
  OUTPUT=$(cd "$TMP/fixture" && "$ROOT/bin/shamash" scan 2>&1)
fi
STATUS=$?
set -e
[[ $STATUS -eq 0 ]] || { echo "$OUTPUT"; exit $STATUS; }
grep -qi 'discovery scan' <<<"$OUTPUT"
grep -qi 'classes scanned' <<<"$OUTPUT"
test ! -e "$TMP/fixture/shamash"
test ! -e "$TMP/fixture/.shamash"

echo "Packaged CLI smoke test passed."
