#!/bin/bash
# 零 Gradle 构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 用法：./build.sh          调试包（debug 签名 + --debug-mode，可用 run-as 调试）
#       ./build.sh release  发布包（release 签名，不带 debuggable）
#
# 在线升级的源地址默认就是生产目录；要换源或做离线包用环境变量覆盖：
#   QUIZGATE_UPGRADE_BASE=https://example.com/quizgate/ ./build.sh release
#   QUIZGATE_UPGRADE_BASE= ./build.sh release    # 完全离线，一个字节都不发
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-debug}"
# 版本号默认值随发布走；做真机升级验证时用环境变量临时覆盖，
# 免得为了「造一个更新包」去改仓库里的版本号。
VERSION_NAME="${QUIZGATE_VERSION_NAME:-1.2.3}"
VERSION_CODE="${QUIZGATE_VERSION_CODE:-10203}"

# 升级源：默认就是生产目录（发版后用户能自动收到更新）。
# 想完全离线：QUIZGATE_UPGRADE_BASE= ./build.sh ...
# 想做真机验证：QUIZGATE_UPGRADE_BASE=http://127.0.0.1:8765/ ./build.sh debug
DEFAULT_BASE="https://shitang.mianmiangou.cn/static/quizgate-tv/"
BASE="${QUIZGATE_UPGRADE_BASE-$DEFAULT_BASE}"
if [ -n "$BASE" ]; then
  case "$BASE" in */) ;; *) BASE="$BASE/" ;; esac
  if [ "$MODE" = "release" ] && [ "${BASE%%:*}" = "http" ]; then
    echo "发布包的升级源必须是 https（当前：$BASE）"; exit 1
  fi
fi

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

# 把升级源写进 BuildCfg，编译完归还源文件（trap 保证编译失败也会还原）
BC="app/src/main/java/app/quizgate/tv/BuildCfg.java"
if [ -n "$BASE" ]; then
  BC_BAK="$OUT/BuildCfg.java.bak"
  cp "$BC" "$BC_BAK"
  trap 'cp "$BC_BAK" "$BC" 2>/dev/null || true' EXIT
  python3 - "$BASE" <<'PY'
import sys, re
p = 'app/src/main/java/app/quizgate/tv/BuildCfg.java'
s = open(p, encoding='utf-8').read()
s = re.sub(r'UPGRADE_BASE = "[^"]*"', 'UPGRADE_BASE = "%s"' % sys.argv[1], s, count=1)
open(p, 'w', encoding='utf-8').write(s)
PY
  echo "  升级源：$BASE"
else
  echo "  升级源：未配置（纯离线，不检查更新）"
  if [ "$MODE" = "release" ]; then
    echo "  ⚠️  发布包没有升级源 = 用户永远收不到后续修复，确认这是你要的吗？"
  fi
fi

# 版本号 + （调试包的）明文 HTTP 一次性注入到临时清单。
# 必须改清单：aapt2 的 --version-code/--version-name 只在清单里**没有**这两个属性时才生效，
# 清单写死 10200 的话，build.sh 里的 10201 只影响文件名 —— 这正是「version.json 说 10201、
# 装上的包还是 10200」那类事故的来源。单一可信源放在 build.sh，清单只做被改写方。
MANIFEST_RAW="app/src/main/AndroidManifest.xml"
MANIFEST="$OUT/AndroidManifest.xml"
CLEAR="false"
if [ "$MODE" != "release" ] && [ "${BASE%%:*}" = "http" ]; then
  CLEAR="true"
  echo "  [dev] 已允许明文 HTTP（仅调试包）"
fi
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERSION_CODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" \
    -e "s/android:usesCleartextTraffic=\"[a-z]*\"/android:usesCleartextTraffic=\"$CLEAR\"/" \
    "$MANIFEST_RAW" > "$MANIFEST"
grep -q "android:versionCode=\"$VERSION_CODE\"" "$MANIFEST" \
  || { echo "清单版本号注入失败（$VERSION_CODE）"; exit 1; }
echo "  版本：$VERSION_NAME ($VERSION_CODE)"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/res.zip"

echo "[2/6] aapt2 link ($MODE)"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest "$MANIFEST" \
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
