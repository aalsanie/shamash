#!/usr/bin/env bash
set -euo pipefail

ZIP=${1:?usage: cli-smoke-test.sh <shamash-cli.zip>}
ZIP=$(ls $ZIP | head -1)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t shamash-smoke)
trap 'rm -rf "$TMP"' EXIT

python - "$ZIP" "$TMP" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    z.extractall(sys.argv[2])
PY

ROOT=$(find "$TMP" -maxdepth 1 -type d -name 'shamash-*' -print -quit)
test -n "$ROOT"

if [[ "${OS:-}" == "Windows_NT" ]]; then
  CMD=(cmd.exe /c "$(cygpath -w "$ROOT/bin/shamash.bat")")
else
  chmod +x "$ROOT/bin/shamash"
  CMD=("$ROOT/bin/shamash")
fi

"${CMD[@]}" version | grep -q 'shamash-cli'

mkdir -p "$TMP/fixture/build/classes/java/main/com/example"
cat > "$TMP/fixture/App.java" <<'JAVA'
package com.example; public class App { public static void main(String[] args) {} }
JAVA
javac -d "$TMP/fixture/build/classes/java/main" "$TMP/fixture/App.java"

set +e
OUTPUT=$(cd "$TMP/fixture" && "${CMD[@]}" scan 2>&1)
STATUS=$?
set -e
[[ $STATUS -eq 0 ]] || { echo "$OUTPUT"; exit $STATUS; }
grep -qi 'discovery scan' <<<"$OUTPUT"
grep -qi 'classes scanned' <<<"$OUTPUT"
test ! -e "$TMP/fixture/shamash"
test ! -e "$TMP/fixture/.shamash"

echo "Packaged CLI smoke test passed."
