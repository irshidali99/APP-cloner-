#!/usr/bin/env bash
#
# Compiles runtime-src/ (the patch that is injected into a clone) into a single dex file that ships as
# app/src/main/assets/runtime/patch.dex.
#
# The dex is built with the tools of the Android SDK (javac + d8), so it is byte code for the exact API
# level the clones need. CI runs this before every build; run it by hand when the patch sources change.
#
#   ANDROID_HOME=/path/to/sdk bash scripts/build-runtime-dex.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT="$ROOT/app/src/main/assets/runtime/patch.dex"
MIN_API="${MIN_API:-21}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
ANDROID_JAR="${ANDROID_JAR:-$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)}"
if [ -z "${ANDROID_JAR:-}" ] || [ ! -f "$ANDROID_JAR" ]; then
  echo "error: no android.jar found (set ANDROID_HOME or ANDROID_JAR)" >&2
  exit 1
fi

D8="${D8:-}"
if [ -z "$D8" ]; then
  D8="$(ls "$SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "$D8" ] || [ ! -x "$D8" ]; then
  echo "error: d8 not found (set D8 or install build-tools)" >&2
  exit 1
fi

echo "android.jar: $ANDROID_JAR"
echo "d8:          $D8"

mkdir -p "$WORK/classes" "$WORK/dex"
# Android's own classes are the compile classpath: the patch only uses framework APIs.
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$WORK/classes" \
  $(find "$ROOT/runtime-src" -name '*.java' | sort)

# d8 turns the class files into a single dex. No app classes on the classpath: the patch must stay
# independent of the app it is injected into.
"$D8" --min-api "$MIN_API" --lib "$ANDROID_JAR" --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class' | sort)

DEX="$(ls "$WORK"/dex/*.dex | head -1)"
mkdir -p "$(dirname "$OUTPUT")"
cp "$DEX" "$OUTPUT"

# The runtime reads this header to be sure the asset is really a dex file.
MAGIC="$(head -c 8 "$OUTPUT" | od -An -tx1 | tr -d ' \n')"
echo "wrote $OUTPUT ($(wc -c < "$OUTPUT") bytes, magic $MAGIC)"
