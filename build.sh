#!/bin/bash
# 零 Gradle 构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 用法：./build.sh          调试包（debug 签名 + --debug-mode，可用 run-as 调试）
#       ./build.sh release  发布包（release 签名，不带 debuggable）
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-debug}"
VERSION_NAME="1.0.0"
VERSION_CODE="10000"

SDK="${ANDROID_HOME:-$HOME/android-dev/sdk}"
JAVA_HOME="${JAVA_HOME:-$HOME/android-dev/jdk17/Contents/Home}"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-35/android.jar"
OUT="build"

if [ "$MODE" = "release" ]; then
  KS="keystore/release.jks"
  KS_ALIAS="quizgate"
  KS_PASS="$(cat keystore/release.pass)"
  EXTRA_LINK_FLAGS=""
  APK_NAME="quizgate-${VERSION_NAME}.apk"
else
  KS="keystore/debug.keystore"
  KS_ALIAS="androiddebugkey"
  KS_PASS="android"
  EXTRA_LINK_FLAGS="--debug-mode"
  APK_NAME="quizgate-${VERSION_NAME}-debug.apk"
fi

for p in "$BT/aapt2" "$AJ" "$JAVA_HOME/bin/javac"; do
  [ -e "$p" ] || { echo "缺少: $p"; exit 1; }
done
# 调试包允许临时生成密钥（CI 里没有 keystore）；发布包必须用固定密钥，缺了就失败
if [ ! -f "$KS" ]; then
  if [ "$MODE" = "release" ]; then
    echo "缺少发布密钥: $KS（发布包不允许临时生成）"; exit 1
  fi
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=QuizGate Debug,O=QuizGate,C=CN" >/dev/null 2>&1
  echo "  已临时生成调试密钥"
fi
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/res.zip"

echo "[2/6] aapt2 link ($MODE)"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest app/src/main/AndroidManifest.xml \
  --java "$OUT/gen" \
  -A app/src/main/assets \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  $EXTRA_LINK_FLAGS \
  "$OUT/res.zip"

echo "[3/6] javac"
find app/src/main/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -encoding UTF-8 -nowarn -cp "$AJ" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$AJ" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/6] package + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign ($KS_ALIAS)"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT/$APK_NAME" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/$APK_NAME" | head -4
echo "----"
ls -lh "$OUT/$APK_NAME"
shasum -a 256 "$OUT/$APK_NAME"
