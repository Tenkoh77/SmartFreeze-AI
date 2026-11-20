#!/usr/bin/env bash
# Compile and run the PURE-KOTLIN parts of the Android app (ML inference, feature processing,
# explanation, severity, alert policy) and their unit tests with a plain Kotlin compiler.
# This does not need the Android SDK. It cannot check Android-specific code
# (collectors, Room, Compose UI); use Android Studio / Gradle for that.
#
# Usage: KOTLINC=/path/to/kotlinc/bin/kotlinc scripts/verify_kotlin_core.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
APP="$ROOT/android-app/app/src"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

CORE=(
  "$APP/main/java/com/buse/smartfreeze/ml"
  "$APP/main/java/com/buse/smartfreeze/domain"
  "$APP/main/java/com/buse/smartfreeze/data/export/ExportSchema.kt"
)
# Fail fast if Android imports leak into the pure core.
if grep -rl "^import android" "${CORE[@]}" >/dev/null; then
  echo "error: android imports found in pure-Kotlin core:"; grep -rl "^import android" "${CORE[@]}"; exit 1
fi

"$KOTLINC" "${CORE[@]}" "$ROOT/scripts/jvm-verify/JUnitShim.kt" "$ROOT/scripts/jvm-verify/Runner.kt" \
  "$APP/test/java" -d "$OUT/classes" -nowarn 2>&1 | grep -v "^warning" || true
[ -d "$OUT/classes" ] || { echo "compilation failed"; exit 1; }

KOTLIN_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")"
java -cp "$OUT/classes:$APP/test/resources:$KOTLIN_HOME/lib/kotlin-stdlib.jar:$KOTLIN_HOME/lib/kotlin-reflect.jar" RunnerKt \
  com.buse.smartfreeze.ml.ModelParityTest \
  com.buse.smartfreeze.ml.ModelLoaderTest \
  com.buse.smartfreeze.domain.DomainLogicTest \
  com.buse.smartfreeze.ml.IsolationForestTrainerTest \
  com.buse.smartfreeze.SchemaContractTest
