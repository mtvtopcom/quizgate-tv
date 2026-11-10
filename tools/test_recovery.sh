#!/usr/bin/env bash
# 恢复码纯逻辑自检：不需要 Android SDK，只要 jdk。
#   ./tools/test_recovery.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JC="$JAVA_HOME/bin/javac"; JU="$JAVA_HOME/bin/java"
elif [ -x "$HOME/android-dev/jdk17/Contents/Home/bin/javac" ]; then
  JC="$HOME/android-dev/jdk17/Contents/Home/bin/javac"
  JU="$HOME/android-dev/jdk17/Contents/Home/bin/java"
else
  JC=javac; JU=java
fi

OUT="build/rtest"
rm -rf "$OUT"; mkdir -p "$OUT"
"$JC" --release 11 -encoding UTF-8 -nowarn -d "$OUT" \
  app/src/main/java/app/quizgate/tv/Recovery.java tools/RecoveryTest.java
"$JU" -cp "$OUT" RecoveryTest
