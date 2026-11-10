#!/usr/bin/env bash
# 局域网 adb 直装（小米电视实测版）
#
#   ./tools/adb_lan_install.sh            # 装到 $TV_IP（默认 192.168.1.100，请改成你自己的电视 IP）
#   ./tools/adb_lan_install.sh 192.168.1.88
#   ./tools/adb_lan_install.sh --list     # 列出局域网里的电视
#
# 所有 adb 调用都在同一进程内串完，避免 adb server 在命令之间被回收导致
# 每条命令都重新 "daemon not running"。
# 注意：本脚本里的变量一律用 ${VAR} 花括号形式——macOS 的 bash 3.2 在
# "$VAR）" 这种「变量紧跟全角标点」的写法上会把标点字节吃进变量名。
set -uo pipefail

# ★ 关键：本机 ComputerName 是「Y的Mac Mini」，含中文。adb 内置的 mDNS
# zero-config 组件会用它构造 DNS 服务名，非 ASCII 字符触发 Rust panic → SIGABRT，
# adb server 启动约 8 秒后自杀，表现为每条命令都重新 "daemon not running"。
# 关掉 mDNS 即可（我们用 IP 直连，不需要 mDNS 发现）。
# 已实测：默认启动 10 秒后 server 消失；ADB_MDNS=0 启动 10 秒后仍在监听。
export ADB_MDNS=0

ADB="$HOME/android-dev/sdk/platform-tools/adb"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${ROOT}/dist/quizgate-0.1.0.apk"
PKG="app.quizgate.tv"
DEFAULT_IP="192.168.1.100"

[ -x "${ADB}" ] || { echo "找不到 adb: ${ADB}"; exit 1; }

# ---------- TCP 探测：返回 OPEN / errno:原因 ----------
probe() {
  python3 - "$1" "$2" <<'PY'
import socket, sys
ip, port = sys.argv[1], int(sys.argv[2])
s = socket.socket()
s.settimeout(2.5)
try:
    s.connect((ip, port))
    print("OPEN")
except OSError as e:
    print("%s:%s" % (e.errno, e.strerror))
PY
}

# ---------- --list ----------
if [ "${1:-}" = "--list" ]; then
  echo "局域网内的电视（AirPlay 广播，3 秒）："
  (dns-sd -B _airplay._tcp local. > /tmp/_tv_list.txt 2>&1 &)
  sleep 3
  pkill -f "dns-sd -B _airplay" 2>/dev/null
  grep "Add" /tmp/_tv_list.txt | grep -v "Mac" | awk -F'_airplay._tcp.       ' '{print "  " $2}'
  rm -f /tmp/_tv_list.txt
  exit 0
fi

IP="${1:-${DEFAULT_IP}}"
[ -f "${APK}" ] || { echo "找不到 APK: ${APK}"; exit 1; }

echo "═══ 目标 ${IP} ═══"
MAC="$(arp -an 2>/dev/null | grep "(${IP})" | awk '{for(i=1;i<=NF;i++) if($i=="at"){print $(i+1); exit}}')"
case "${MAC}" in
  "(incomplete)"|"") echo "ARP: 无（该地址当前无人应答）" ;;
  *)                  echo "MAC: ${MAC}" ;;
esac

# ---------- 1. 探测 5555，按 errno 分流 ----------
RES="$(probe "${IP}" 5555)"
case "${RES}" in
  OPEN)
    echo "✓ 5555 已开放，可以安装"
    ;;
  61:*)
    echo "✗ ${IP}:5555 连接被拒绝 —— 主机在线，但端口没开。"
    echo "  电视端「网络调试 / ADB 调试」没打开，或电视重启后开关复位了。"
    echo "  小米电视：设置 → 关于 → 连点「产品型号」7 次 → 返回"
    echo "           设置 → 账号与安全 → ADB 调试 → 打开（需登录小米账号）"
    exit 3
    ;;
  65:*|60:*)
    echo "✗ ${IP} 主机不可达（${RES}）—— 电视当前不在线。"
    echo "  待机/息屏时电视会掉线，先真正开机并保持亮屏。"
    echo "  查 IP：电视 → 设置 → 网络 → 网络信息"
    exit 2
    ;;
  *)
    echo "✗ 探测失败: ${RES}"
    exit 2
    ;;
esac

# ---------- 2. 修密钥：权限必须 600，否则 adb 拒绝使用，每次生成临时密钥 ----------
KEY="${HOME}/.android/adbkey"
if [ -f "${KEY}" ]; then
  PERM="$(stat -f "%Lp" "${KEY}" 2>/dev/null)"
  if [ "${PERM}" != "600" ]; then
    chmod 600 "${KEY}" && echo "✓ 已修正 adbkey 权限 ${PERM} → 600"
  fi
  [ -f "${KEY}.pub" ] || echo "· adbkey.pub 缺失，adb 会自动从私钥重新导出"
fi

# ---------- 3. connect ----------
${ADB} start-server >/dev/null 2>&1
echo "→ adb connect"
OUT="$(${ADB} connect "${IP}:5555" 2>&1)"
echo "  ${OUT}"

case "${OUT}" in
  *"protocol fault"*)
    echo "  本机 adb server 掉线，重启后重试一次"
    ${ADB} kill-server >/dev/null 2>&1
    ${ADB} start-server >/dev/null 2>&1
    OUT="$(${ADB} connect "${IP}:5555" 2>&1)"
    echo "  ${OUT}"
    ;;
esac

case "${OUT}" in
  *"failed to authenticate"*)
    echo
    echo "✗ RSA 密钥认证被拒。电视上已授权的密钥和本机当前密钥对不上。"
    echo "  处理顺序："
    echo "   1) 电视：设置 → 账号与安全 → 开发者选项 → 「撤销 USB 调试授权」"
    echo "   2) 本机：rm -f ~/.android/adbkey ~/.android/adbkey.pub"
    echo "   3) 重跑本脚本，电视弹框时遥控器点「允许」并勾选「一律允许」"
    exit 6
    ;;
esac

sleep 1
STATE="$(${ADB} -s "${IP}:5555" get-state 2>&1)"
if [ "${STATE}" != "device" ]; then
  echo "✗ 连接未就绪，state=${STATE}"
  echo "  电视屏幕上应弹出「允许 USB 调试」，用遥控器点「允许」并勾选「一律允许」。"
  exit 4
fi
echo "✓ 已连接"

# ---------- 4. install ----------
echo "→ 安装"
if ! ${ADB} -s "${IP}:5555" install -r "${APK}"; then
  echo "✗ 安装失败。"
  echo "  USER_RESTRICTED → 开发者选项里再打开「USB 安装」"
  echo "  签名冲突        → 不要删 keystore/debug.keystore，先 adb uninstall ${PKG}"
  exit 5
fi

# ---------- 5. launch ----------
echo "→ 启动"
${ADB} -s "${IP}:5555" shell monkey -p "${PKG}" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo
echo "完成。抓日志："
echo "  ${ADB} -s ${IP}:5555 logcat -v monotonic -s QuizGate:I"
