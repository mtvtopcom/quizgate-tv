#!/bin/bash
# 发布一条新版：构建 release 包 + 生成「上传到服务器的那两个文件」。
#
#   ./tools/publish.sh 1.2.3 "修了什么，会显示在升级页上"
#
# 产物（都在 dist/，直接拖到服务器的 quizgate-tv 目录即可）：
#   dist/quizgate-<版本>.apk
#   dist/version.json        ← 客户端就靠它判断有没有新版
#
# 为什么必须传 version.json：服务器不支持列目录（GET 目录返回 404），
# 客户端没法"看看文件夹里有什么"，只能按固定文件名去取。
set -euo pipefail
cd "$(dirname "$0")/.."

VER="${1:-}"
NOTES="${2:-}"
if [ -z "$VER" ]; then
  echo "用法：./tools/publish.sh <版本号，如 1.2.3> [更新说明]"
  exit 1
fi
if ! echo "$VER" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "版本号格式应为 MAJOR.MINOR.PATCH，例如 1.2.3"
  exit 1
fi

MAJOR="${VER%%.*}"; REST="${VER#*.}"
MINOR="${REST%%.*}"; PATCH="${REST#*.}"
CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))

BASE="${QUIZGATE_UPGRADE_BASE:-https://shitang.mianmiangou.cn/static/quizgate-tv/}"

echo "== 构建 $VER ($CODE)，升级源 $BASE"
QUIZGATE_VERSION_NAME="$VER" QUIZGATE_VERSION_CODE="$CODE" \
QUIZGATE_UPGRADE_BASE="$BASE" ./build.sh release

APK="build/quizgate-${VER}.apk"
[ -f "$APK" ] || { echo "构建产物不在 $APK"; exit 1; }

mkdir -p dist
cp "$APK" "dist/quizgate-${VER}.apk"
SHA="$(shasum -a 256 "dist/quizgate-${VER}.apk" | cut -d' ' -f1)"

python3 - "$VER" "$CODE" "quizgate-${VER}.apk" "$SHA" "$NOTES" <<'PY'
import json, sys
ver, code, apk, sha, notes = sys.argv[1:6]
data = {
    "versionCode": int(code),
    "versionName": ver,
    "apk": apk,
    "sha256": sha,
    "notes": notes or "版本 %s" % ver,
}
open("dist/version.json", "w", encoding="utf-8").write(
    json.dumps(data, ensure_ascii=False, indent=2) + "\n")
PY

echo
echo "== 要上传的两个文件"
ls -lh "dist/quizgate-${VER}.apk" dist/version.json
echo
echo "sha256 = $SHA"
echo
echo "上传顺序：先 apk，最后 version.json —— 反了会出现「清单说有新版、包还没传完」"
echo "目标目录：$BASE"
echo
echo "== 正式发布别忘了同步 GitHub 上的 README.md（仓库首页展示的就是它）"
if grep -Fq "v${VER}" README.md; then
  echo "README.md 已含 v${VER}，仍请核对：当前版本区块 / apk 文件名 / sha256 / 授权与故障表"
else
  echo "!! README.md 里还没有 v${VER}：更新顶部「当前版本」区块后再 push main"
fi
