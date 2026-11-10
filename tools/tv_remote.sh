#!/usr/bin/env bash
# 电视遥控：远程看画面 + 遥控操作（纯 adb，不需要 scrcpy / ffmpeg）
#
#   ./tools/tv_remote.sh shot              # 截一张图，存 /tmp/tv_screen.png
#   ./tools/tv_remote.sh live              # 起本地网页实时看画面（浏览器自动刷新）
#   ./tools/tv_remote.sh key ok            # 按确认键
#   ./tools/tv_remote.sh key back          # 返回
#   ./tools/tv_remote.sh tap 960 540       # 点屏幕坐标
#   ./tools/tv_remote.sh text "1234"       # 输入文字
#   ./tools/tv_remote.sh ui                # 打印当前界面的控件树（文字 + 坐标）
#   ./tools/tv_remote.sh find 开始计时      # 按文字找控件并点击
#   ./tools/tv_remote.sh log               # 实时看 QuizGate 日志
#
# 换电视：TV_IP=192.168.1.88 ./tools/tv_remote.sh shot
set -uo pipefail

# ComputerName 含中文会让 adb 的 mDNS 组件 panic，导致 server 自杀
export ADB_MDNS=0

ADB="$HOME/android-dev/sdk/platform-tools/adb"
IP="${TV_IP:-192.168.1.100}"
SER="${IP}:5555"
PKG="app.quizgate.tv"

die() { echo "✗ $*" >&2; exit 1; }

ensure() {
  [ -x "${ADB}" ] || die "找不到 adb: ${ADB}"
  ${ADB} start-server >/dev/null 2>&1
  ${ADB} connect "${SER}" >/dev/null 2>&1
  local st
  st="$(${ADB} -s "${SER}" get-state 2>&1)"
  [ "${st}" = "device" ] || die "未连接到 ${SER}（state=${st}）。先跑 ./tools/adb_lan_install.sh"
}

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

[ $# -eq 0 ] && usage
CMD="$1"; shift || true

case "${CMD}" in

  shot)
    ensure
    OUT="${1:-/tmp/tv_screen.png}"
    ${ADB} -s "${SER}" exec-out screencap -p > "${OUT}" 2>/dev/null \
      || { ${ADB} -s "${SER}" shell screencap -p /sdcard/_s.png && \
           ${ADB} -s "${SER}" pull /sdcard/_s.png "${OUT}" >/dev/null && \
           ${ADB} -s "${SER}" shell rm -f /sdcard/_s.png; }
    [ -s "${OUT}" ] || die "截图失败（部分电视 DRM 保护层会返回黑图）"
    echo "已保存 ${OUT}  ($(du -h "${OUT}" | cut -f1))"
    ;;

  live)
    ensure
    PORT="${1:-8899}"
    DIR="/tmp/tv_live"
    mkdir -p "${DIR}"
    cat > "${DIR}/index.html" <<'HTML'
<!doctype html><meta charset="utf-8">
<title>电视画面</title>
<style>
  body{margin:0;background:#111;color:#ccc;font:13px -apple-system,sans-serif;
       display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh}
  img{max-width:96vw;max-height:88vh;border-radius:6px;box-shadow:0 8px 40px #000}
  #s{margin-top:10px;opacity:.6}
</style>
<img id="i" src="">
<div id="s">连接中…</div>
<script>
let n = 0;
function tick(){
  const im = document.getElementById('i');
  const url = '/shot.jpg?t=' + Date.now();
  const probe = new Image();
  probe.onload = () => { im.src = url; document.getElementById('s').textContent = '已更新 ' + (++n) + ' 帧'; };
  probe.onerror = () => { document.getElementById('s').textContent = '抓帧失败，重试中…'; };
  probe.src = url;
}
tick(); setInterval(tick, 900);
</script>
HTML
    # 抓帧循环
    ( while true; do
        ${ADB} -s "${SER}" exec-out screencap -p > "${DIR}/_raw.png" 2>/dev/null
        if [ -s "${DIR}/_raw.png" ]; then
          sips -Z 1280 -s format jpeg -s formatOptions 65 "${DIR}/_raw.png" \
               --out "${DIR}/shot.jpg" >/dev/null 2>&1 \
            || cp "${DIR}/_raw.png" "${DIR}/shot.jpg"
        fi
        sleep 0.4
      done ) &
    LOOP=$!
    trap 'kill ${LOOP} 2>/dev/null' EXIT INT TERM
    echo "实时画面已启动： http://127.0.0.1:${PORT}"
    echo "（保持这个窗口开着，Ctrl+C 结束）"
    ( sleep 1.2; open "http://127.0.0.1:${PORT}" 2>/dev/null ) &
    cd "${DIR}" && python3 -m http.server "${PORT}" --bind 127.0.0.1
    ;;

  key)
    ensure
    case "${1:-ok}" in
      up)     K=DPAD_UP ;;
      down)   K=DPAD_DOWN ;;
      left)   K=DPAD_LEFT ;;
      right)  K=DPAD_RIGHT ;;
      ok)     K=DPAD_CENTER ;;
      back)   K=BACK ;;
      home)   K=HOME ;;
      menu)   K=MENU ;;
      power)  K=POWER ;;
      play)   K=MEDIA_PLAY_PAUSE ;;
      volup)  K=VOLUME_UP ;;
      voldown) K=VOLUME_DOWN ;;
      *)      K="$1" ;;
    esac
    ${ADB} -s "${SER}" shell input keyevent "${K}"
    echo "→ ${K}"
    ;;

  tap)
    ensure
    [ $# -ge 2 ] || die "用法: tap X Y"
    ${ADB} -s "${SER}" shell input tap "$1" "$2"
    echo "→ tap $1 $2"
    ;;

  text)
    ensure
    [ $# -ge 1 ] || die "用法: text \"要输入的内容\""
    ${ADB} -s "${SER}" shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')"
    echo "→ 已输入: $1"
    ;;

  ui)
    ensure
    ${ADB} -s "${SER}" shell uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1
    ${ADB} -s "${SER}" shell cat /sdcard/_ui.xml 2>/dev/null > /tmp/_ui.xml
    [ -s /tmp/_ui.xml ] || die "拿不到 UI 树（个别电视禁用了 uiautomator）"
    python3 - /tmp/_ui.xml "${2:-}" <<'PY'
import sys, re
import xml.etree.ElementTree as ET
xml, keyword = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else ""
try:
    root = ET.parse(xml).getroot()
except Exception as e:
    print("解析失败:", e); sys.exit(1)
rows = []
for n in root.iter("node"):
    t = (n.get("text") or "").strip()
    d = (n.get("content-desc") or "").strip()
    label = t or d
    if not label: continue
    b = n.get("bounds") or ""
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    if not m: continue
    x1,y1,x2,y2 = map(int, m.groups())
    rows.append((label, n.get("clickable"), n.get("focused"),
                 (x1+x2)//2, (y1+y2)//2, n.get("resource-id") or "", b))
if keyword:
    rows = [r for r in rows if keyword in r[0]]
if not rows:
    print("（没匹配到带文字的控件）"); sys.exit(0)
print(f"{'文字':28} {'可点':4} {'焦点':4} {'中心坐标':12} {'bounds'}")
print("-"*78)
for label, ck, fc, cx, cy, rid, b in rows:
    print(f"{label[:26]:28} {ck:4} {fc:4} ({cx:4},{cy:4})   {b}")
PY
    ;;

  find)
    ensure
    [ $# -ge 1 ] || die "用法: find 文字"
    KW="$1"
    ${ADB} -s "${SER}" shell uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1
    ${ADB} -s "${SER}" shell cat /sdcard/_ui.xml 2>/dev/null > /tmp/_ui.xml
    XY="$(python3 - /tmp/_ui.xml "${KW}" <<'PY'
import sys, re
import xml.etree.ElementTree as ET
xml, kw = sys.argv[1], sys.argv[2]
root = ET.parse(xml).getroot()
for n in root.iter("node"):
    label = (n.get("text") or n.get("content-desc") or "").strip()
    if kw in label:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m:
            x1,y1,x2,y2 = map(int, m.groups())
            print(f"{(x1+x2)//2} {(y1+y2)//2}"); sys.exit(0)
sys.exit(1)
PY
)" || die "界面上找不到「${KW}」"
    set -- ${XY}
    ${ADB} -s "${SER}" shell input tap "$1" "$2"
    echo "→ 点中「${KW}」 ($1,$2)"
    ;;

  log)
    ensure
    echo "实时日志（Ctrl+C 退出）…"
    ${ADB} -s "${SER}" logcat -v monotonic -s QuizGate:I
    ;;

  *) usage ;;
esac
