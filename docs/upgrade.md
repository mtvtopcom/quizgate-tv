# 在线升级

每次进入应用检查一次 `version.json`，有新版本就弹一页，**可以点「以后再说」跳过**。
只有明确检测到更高 `versionCode` 才提示；检查失败（没网、DNS 挂了、服务器 500）一律当成
「没有更新」，不在界面上留任何痕迹，只在 logcat 里记一行 `update_check_fail`。

设计取舍见 [ADR-9](architecture.md#adr-9-在线升级可取消失败静默只连一个地址)。

---

## 1. 服务端只要两个文件

正式位置（固定不变，客户端里就是写死这个目录）：

```
https://7789.cn/y/quizgate-tv/
├── version.json              ← 客户端靠它判断有没有新版
└── quizgate-1.3.0.apk
```

> **这个目录不支持列目录**（`GET 目录` 返回 404），客户端没法「看看文件夹里有什么」，
> 只能按固定文件名 `version.json` 去取。所以每次发版**必须**传 `version.json`，
> 只传 apk 等于没发。

### 1.1 主地址的证书（已补齐；1.3.0 起单地址）

**现状（2026-11-11 16:10 起）**：`7789.cn` 已是自己的证书，1.2.9 直连主地址即可。

```bash
curl -v https://7789.cn/y/quizgate-tv/version.json
# *  subject: CN=7789.cn
# *  issuer:  C=US; O=Let's Encrypt; CN=YR1
# *  expire date: Feb  9 06:57:19 2027 GMT
# *  SSL certificate verify ok.
```

真机日志（D1 升级到 1.2.9 后）：

```
I QuizGate: update_check_src https://7789.cn/y/quizgate-tv/ remote=10209 (1.2.9)
```

**历史**：1.2.9 构建当天该端口出示过 `CN=*.mianmiangou.cn` 的证书，
TLS 校验失败（`SSLPeerUnverifiedException: Hostname 7789.cn not verified`），
所以 1.2.9 带了过渡期兜底 `BuildCfg.UPGRADE_FALLBACK`（旧目录）。
证书补齐后，**1.3.0 已把 `BuildCfg.UPGRADE_FALLBACK` 与 `build.sh` /
`tools/publish.sh` 的 fallback 一起删掉**，恢复「只连一个地址」：主地址连不上就静默放弃，
不会再请求第二个地址，日志里只有一行 `update_check_fail <base> -> <原因>`。
CI 仍断言 `\.openConnection()` 恰好 2 处。

### 1.2 旧升级目录已下线，≤1.2.8 怎么升级

**客户端的 `base` 是编译进包里的。** ≤1.2.6（以及未对外发布的 1.2.7 / 1.2.8）只会去
旧目录 `https://shitang.mianmiangou.cn/static/quizgate-tv/` 取 `version.json`；1.2.9 起
改用主目录 `https://7789.cn/y/quizgate-tv/`。

2026-11-11 v1.3.0 发布后，旧域名根路径仍可访问（200），但 `/static/` 及其下的
`quizgate-tv/version.json` 整体返回 404。用户已确认这是**永久下线**、不再恢复：

| 客户端 | 取清单地址 | 1.3.0 提示 |
| --- | --- | --- |
| 1.3.0 | 只连主目录 | 自动（无兜底） |
| 1.2.9 | 主目录；失败才回落旧目录（现回落必 404） | 自动，主地址正常时不受影响 |
| ≤1.2.8 | 只认旧目录 | **不会提示** |

所以停在 ≤1.2.8 的电视只能**手动覆盖安装** `quizgate-1.3.0.apk`（adb 或 U 盘）：
包名 `app.quizgate.tv` 与正式签名都没变，口令 / 恢复码 / 时长 / 年级全部保留；
装完按 1.3.0 装机清单补齐两条必需授权（旧版那条电池白名单 1.3.0 已取消）。

> 历史：1.2.9 发布时旧目录还保留同版本清单作为迁移入口，当时要求「发版必须同步
> 两个目录」。这个要求到此为止——以后发版只需上传主目录的 apk ＋ `version.json`。
> 1.3.0 真实 OTA（D2：1.2.9 → 1.3.0）与线上三方验 sha 见
> `evidence/D13-2026-11-11/`。

`version.json`：

```json
{
  "versionCode": 10300,
  "versionName": "1.3.0",
  "apk": "quizgate-1.3.0.apk",
  "sha256": "完整的 64 位十六进制",
  "notes": "更新说明，会原样显示在升级页上，支持换行"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `versionCode` | ✅ | 必须**大于**已安装版本才会提示；算法 `MAJOR*10000+MINOR*100+PATCH`，1.3.0 = 10300 |
| `versionName` | ✅ | 显示用 |
| `apk` | ✅ | 相对路径会拼在 base 后面；也可以直接写完整 `https://` 地址 |
| `sha256` | ✅ | 64 位小写十六进制。只防传输损坏，不防篡改（见下） |
| `notes` | | 更新说明 |

客户端还会拿包里的**真实** `versionCode` 和 `versionName` 跟这份 json 对一遍，
对不上直接拒装（防止「json 写了 10203、文件其实还是 10202」这种发布事故）。

**不需要** `force`、`minSupported` 之类的字段——这个版本没有强制升级，也不打算加。

---

## 2. 发布流程

```bash
# 一条命令：构建 release 包 + 生成要上传的两个文件（在 dist/）
./tools/publish.sh 1.3.0 "这次改了什么"
```

脚本做的事：按版本号算 `versionCode` → 注入清单和 `BuildCfg.UPGRADE_BASE` →
`build.sh release`（release 签名，`keystore/release.jks`）→ 算 sha256 →
写 `dist/version.json`。

然后把**这两个文件**传到 `quizgate-tv/` 目录：

```
dist/quizgate-1.3.0.apk
dist/version.json
```

**先传 apk，最后传 version.json** —— 顺序反了会出现「清单说有新版，包还没传完」。

### 发版同步清单：GitHub 上的 README

**每次正式发布都要同步 `README.md` 并 push 到 `main`**（GitHub 仓库首页展示的就是它，
只改本地不改远端等于没发版）。至少检查这四处：

1. 顶部「当前版本」区块：版本号 / `versionCode` / 发布日 / apk 文件名 / sha256 / 更新日志链接；
2. 「10 分钟装好」「安装路径 A」里的 apk 文件名、授权条数、操作步骤；
   1.2.6–1.2.9 区分「三条必需授权」与「一条只用于卸载留痕的可选授权」，别混成「四条缺一不可」；
   **1.3.0 起电池白名单已取消**，装机是「两条必需 + 一条可选」，别再写「三条缺一不可」，
   也要说明「1.2.9 及以前仍需三条」，免得旧版用户按新文档装机漏了白名单；
3. 有新截图或新兼容性结论时，换图、补矩阵；
4. 「常见故障」表补上这一版新修的现象，`shasum` 示例别停在老版本文件名上。

推完 `main` 后比对远端与本地，两个指纹相同才算同步完成：

```bash
curl -fsSL https://raw.githubusercontent.com/mtvtopcom/quizgate-tv/main/README.md | shasum -a 256
shasum -a 256 README.md
```

> `main` 别名的 raw 有 CDN 缓存，push 后短时间内可能仍返回**旧副本**，别据此判断「没同步」。
> 复核用 `raw.githubusercontent.com/<owner>/<repo>/<本次commitSHA>/README.md`，
> 或直接看仓库的 README 页面；`git ls-remote origin main` 也能立刻确认远端 HEAD。

完整顺序：

```text
./tools/publish.sh X.Y.Z "改了什么"          # 产物（每次重跑都会重签、sha 会变）
改 README / CHANGELOG / docs → git commit
git push origin main && git push origin vX.Y.Z
上传 apk → 上传 version.json → 本地/CDN/Release 三方验 sha
建 GitHub Release → 把 README 的下载链换成 /releases/tag/vX.Y.Z
双机留一旧：一台刷最终包，另一台留上一版本供真机走 OTA 升级
```

### 双机验收：一新版一旧版（每次发版必做）

**别把两台电视都刷成最终 `dist` 包**（2026-11-11 用户要求）。发版后固定留一台上一版本，
用户自己从旧版点「下载 → 立即安装」走一遍真实 OTA 升级路径，这才验得到
「清单解析 → 下载落盘 → 签名比对 → 拉起安装器 → 覆盖升级保留口令与设置」这条链路；
直接 adb 刷包是验不到的，线上 `version.json` 换了也不代表旧用户真能升上来。

| 机器 | 角色 | 发版后状态 |
| --- | --- | --- |
| D1 `192.168.1.123:5555`（Android 11） | 新版验收机 | 刷本次最终 `dist` 包，恢复干净首装交付态 |
| D2 `192.168.1.88:5555`（Android 14） | 旧版待升级机 | 装回 **v1.2.9** 正式包，首装态，等用户走 OTA 升级 |

> 每次发版可以轮换角色，让两套固件都轮流测过升级安装器；别长期只在一台上测
> （同型号不同 Android 大版本的安装器行为不能互相套结论）。

**退回旧版的顺序**（顺序错了会被 1.2.6 的卸载留痕误判成一次卸载重装，红字污染测试）：

```bash
ADB=$HOME/android-dev/sdk/platform-tools/adb; D=192.168.1.88:5555
$ADB -s $D shell settings delete secure quizgate_install_anchor
$ADB -s $D shell settings delete system quizgate_install_anchor
$ADB -s $D uninstall app.quizgate.tv
$ADB -s $D install dist/quizgate-1.2.9.apk
$ADB -s $D shell appops set app.quizgate.tv SYSTEM_ALERT_WINDOW allow
$ADB -s $D shell appops set app.quizgate.tv REQUEST_INSTALL_PACKAGES allow
$ADB -s $D shell cmd notification allow_listener app.quizgate.tv/app.quizgate.tv.NotifProbe
# 1.3.0 起不需要电池白名单；退回 1.2.9 及更早版本时才加下面这条
# $ADB -s $D shell dumpsys deviceidle whitelist +app.quizgate.tv
$ADB -s $D shell am start -n app.quizgate.tv/.MainActivity
```

- 卸载会清掉通知使用权（1.2.9 及以前还会清电池白名单），必需授权要补回来；
  只补 appops 会复现 D5 的「到点不出题」。1.3.0 起是两条必需，1.2.9 及以前是三条。
- **别给旧版机预授权 `WRITE_SECURE_SETTINGS`**。1.3.0 起没卸过就不显示留痕提示，
  正好验证「覆盖升级不自动获得可选授权」；1.2.9 及以前设置页会有「未启用」红字。
- 复核 logcat 出现 `update_check self=<旧 versionCode> remote=<新 versionCode>`，
  说明旧版机确实读到了线上清单、具备升级条件；截图前先查 `mResumedActivity`，别拿桌面图当证据。
- 旧版机停在「设置 6 位家长口令」即可：`checkUpdate()` 拿到更高 versionCode 后，
  `maybeShowUpdate()` 会被口令门禁挡住；设完口令时 `hidePinOverlay()` 补弹升级提示
  （恢复码面板短暂盖在上层，确认后即露出）。万一网络慢没赶上，退出重进一次，
  `onResume()` 也会补弹。

**用户走完升级后要验的点**：口令与恢复码沿用、时长/年级不变、`versionName` 变成新版、
设置页授权自检状态正确、以及**没有**出现「曾被卸载 N 次」红字（覆盖升级不该误报）。
测完再按交付纪律清掉测试口令、恢复两台干净首装态。

**签名必须一致，而且换钥匙的代价很大。** 升级包和已装版本用不同 keystore 签，
系统直接 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；而唯一的解法是卸载重装 ——
口令、恢复码、计时设置全丢。客户端现在会在下载后、安装前先比对签名，
不一致就拦下并说清原因，不会让你走到「卸载」那一步。

> 现状：`keystore/debug.keystore`（早期调试包）与 `keystore/release.jks`（正式包）
> 是**两把不同的钥匙**。装过调试包的电视，换正式包必须先卸载一次。

---

## 3. 客户端会怎么做

```
进入应用
   └─ GET version.json（2.5s 连接 / 2.5s 读，失败即放弃）
        └─ versionCode > 本机？
             ├─ 否 → 什么都不显示
             └─ 是 → 口令门禁通过后弹升级页
                     ├─ 以后再说 / 稍后再说 → 本次不再弹；下次冷启动还会弹
                     └─ 立即更新（第一步：只下载，不动系统）
                           ├─ 下载到 cacheDir/update.apk
                           ├─ 校验 sha256 —— 不匹配就删掉，绝不把半个包递出去
                           ├─ 校验包名 + versionCode —— 防止「源上放错包」「json 和包版本对不上」
                           └─ 存进系统「下载」目录 → 页面显示 Download/quizgate-x.y.z.apk
                                 └─ 立即安装（第二步：用户点了才动系统）
                                       ├─ 有「安装未知应用」权限 → ACTION_INSTALL_PACKAGE
                                       │    + content:// + 读权限 → 系统安装页
                                       ├─ 没权限 → 引导去设置页（包已经下好了，回来再点即可）
                                       └─ 拉不起来 → 提示用「文件管理」打开那个文件
```

### 升级不会碰口令和设置

覆盖安装（新版 APK 顶掉旧版）走的是 Android 的「更新」路径，`/data/data/<包名>/shared_prefs`
原样保留，所以：

| 场景 | 口令 | 恢复码 | 时长 / 年级 |
| --- | --- | --- | --- |
| 覆盖升级（同一个签名） | 沿用，打开输原口令 | 沿用 | 沿用 |
| 首次安装 / 卸载后重装 | **没有默认口令**，必须自己设 6 位 | 设完口令立刻生成一枚新码 | 默认值（30 分钟 / 3 年级） |

`android:allowBackup="false"`，卸载重装不会从系统备份里把旧口令捞回来 ——
重装就是彻底回到新机状态。「必须让用户自己设」这件事没有别的开关，
靠的就是「库里没有 `pinHash` 就先走设置流程」这一条判断，代码里不存在任何默认口令常量。

实测（v1.2.3）：覆盖安装后仍是「家长口令」界面、输 `123456` 被拒、输原口令进得去；
`adb uninstall` 后再装，直接出现「设置 6 位家长口令」，设完立刻弹出新的恢复码。

### 为什么拆成两步

不是流程啰嗦，是三个具体的坑逼出来的：

1. **不能替用户按「安装」**：普通应用只能把 APK 交给系统安装器，最后那一下必须人点。
   既然无论如何要人点，就不如在应用里先问清「现在就装吗」，而不是下完直接把安装页糊到脸上。
2. **下载这一步不该要求任何系统权限**：先落盘 → 用户随时可以慢慢装，
   中途被电话/待机打断也不丢；反过来「先申请权限再下载」等于逼家长在没网的时候去改设置。
3. **包落在公共目录是可审计的**：家长能在电视的「下载」目录里看到这个文件，
   知道自己装了什么，而不是"应用偷偷干了什么"。

### 同名旧包会被替换

下载前先删掉「下载」目录里的同名 `quizgate-x.y.z.apk`（只会删自己建的那条 MediaStore 记录），
否则 MediaStore 会自动改成 `quizgate-1.2.1 (1).apk`，越升级越乱。

### 进程被系统回收后仍然认得已下好的包

电视上进程随时会被回收，内存里那个「已下载」标记会没。所以升级页在初始化时会去
MediaStore 查 `Download/quizgate-<版本>.apk` 在不在 —— **以磁盘为真相**，
查到就直接进「已下载，点安装」的状态，不让家长白下一遍。

### 设备支持矩阵（实测）

| 设备 | 结果 |
| --- | --- |
| MiTV-MFTR0 / Android 11 | **能拉起系统安装页**：`PackageInstallerActivity`（实测 v1.2.1→1.2.2、v1.2.3→1.2.4 两次成功） |
| 原生 Android TV | 同上，走 `PackageInstallerActivity` |

> **历史更正**：早期版本的文档写过「小米电视系统里根本没有 APK 安装器，只能落盘」。
> 那是**探测方法错了**：`resolve-activity` 只给 action+mimeType、不给 data scheme，
> 匹配不到 `InstallStart` 的 filter，返回 "No activity found" 是正常的。
> 实测 `com.android.packageinstaller` 存在且启用，用 `content://` URI + 读权限就能拉起。
> 详细复盘见 [`evidence/D3-2026-11-10/06_installer_probe.txt`](../evidence/D3-2026-11-10/06_installer_probe.txt)。

两个仍然成立的前提：

- 必须用 `content://`（MediaStore 给的 URI），`file://` 从 Android 7 起直接抛 `FileUriExposedException`
- 必须拿到「安装未知应用」授权（`canRequestPackageInstalls()`）。
  **重装应用会重置这个授权** —— 用户重装后会看到"请先授权"的提示，这是正常的

**不要试图做静默安装。** 任何声称能在小米电视上无提示安装的第三方应用，要么是系统应用、
要么是 Device Owner（要求恢复出厂），要么在骗人。这里的定位是「下载好、把最后一下留给用户」。

---

## 4. 隐私边界

加了 `INTERNET` 之后，能验证的不再是「没有网络权限」，而是「网络只被用在一个地方」：

```bash
grep -rn "openConnection" app/src/main/java/
# 只应该出现 2 处，且都在 Updater.java
```

- 只有两个请求：`GET {base}version.json`、`GET {base}quizgate-x.y.z.apk`
  （1.3.0 起 `base` 只有主地址一个，失败即放弃、不再换地址，见 1.1）
- 裸 GET：无 query、无 body、无 cookie、无自定义 header（除固定的 `User-Agent: QuizGate`）
- **不传设备信息**：默认 UA 是 `Dalvik/2.1.0 (Linux; U; Android 11; ...)`，
  会把系统版本和机型报出去，所以强制改成 `QuizGate`
- 服务器能看到的只有：你的 IP、请求时间、以及「有人在查这个版本」
- 不配 `QUIZGATE_UPGRADE_BASE` 构建时，一次请求都不发（`Updater.enabled()` 直接返回 false）

---

## 5. 自测清单

用一个临时目录起服务，`adb reverse` 绕开局域网和防火墙：

```bash
mkdir -p /tmp/upd && cd /tmp/upd
# 放 version.json 和 apk，versionCode 写得比本机高
python3 -m http.server 8765 --bind 127.0.0.1

adb reverse tcp:8765 tcp:8765
QUIZGATE_UPGRADE_BASE=http://127.0.0.1:8765/ ./build.sh
adb install -r build/quizgate-*-debug.apk
```

预期 logcat：

```
I QuizGate: update_check self=10203 remote=10204 (1.2.4)
I QuizGate: update_downloaded bytes=71088 sha=7b7c9b6d5877
I QuizGate: update_inspect ok pkg=app.quizgate.tv vc=10204
I QuizGate: update_saved Download/quizgate-1.2.4.apk uri=content://media/external/downloads/14144
I QuizGate: installer_opened
I QuizGate: install_open_ok path=Download/quizgate-1.2.4.apk
```

### 造「更新包」不用改仓库里的版本号

```bash
QUIZGATE_VERSION_NAME=1.2.4 QUIZGATE_VERSION_CODE=10204 \
QUIZGATE_UPGRADE_BASE=http://127.0.0.1:8765/ ./build.sh debug
```

版本号在 `build.sh` 里注入临时清单（`aapt2` 的 `--version-code` 只在清单**没有**该属性时生效，
所以必须改写清单，否则会出现「json 说 10204、装上的包还是 10203」）。

### 电视上点按钮的两个小坑

- 遥控器「按一下」有时只是移动焦点，蓝色高亮的是**焦点**而不是已按下。
  安装页的「继续更新」要再按一次 OK 才真装（真机上实测）。
- 装完系统会顺手删掉 `Download` 里的那个包（安装器的 `DeleteStagedFileOnResult`），
  所以「装完再去下载目录找安装包」是找不到的，这是正常行为。

调试包会自动允许明文 HTTP（`build.sh` 里判断 base 是否以 `http` 开头），
**发布包不接受 http 的 base，构建会直接失败**。

---

## 6. 升级到 1.2.6 的额外一步（卸载留痕授权）

1.2.6 新增 `WRITE_SETTINGS` / `WRITE_SECURE_SETTINGS` 两个权限用于写系统设置锚点。
`WRITE_SECURE_SETTINGS` 是系统级权限，**覆盖升级不会自动获得**，从 1.2.5 升上来的
电视也不会。不补的后果仅是留痕不启用：应用照常计时、出题、升级，设置页「授权自检」
第五项显示红字 `✗ 卸载留痕：缺失` 并给出命令，不会崩溃。

补法（D1 / Android 11、D2 / Android 14 双机实测生效的是第一条）：

```bash
adb shell pm grant app.quizgate.tv android.permission.WRITE_SECURE_SETTINGS
# 个别固件若拒绝，再补这条兜底：
adb shell appops set app.quizgate.tv WRITE_SETTINGS allow
adb shell settings get secure quizgate_install_anchor   # 补完重进应用，应能看到一条 JSON
```

两个要点要在发布说明里讲清楚，别造成误解：

1. **锚点从授权生效那一刻才建立，1.2.6 之前的卸载历史查不到。** 这是留痕，不是回溯取证。
2. **卸载重装会重置授权状态**（和通知使用权同理），重装后请重新核对
   必需授权（1.3.0 起两条、1.2.9 及以前三条）+ 这条可选授权；锚点本身在
   `Settings.Secure` 里不会被清，所以重装后的「曾被卸载 N 次」告警照常显示。
   **1.3.0 起没卸过就不显示任何留痕提示**（旧版会显示「未启用」红字）。

留痕只记录时间戳与次数，不含口令 / 恢复码 / 任何凭据派生值；只告警、不拦截，
重装仍由用户自己设 6 位口令。双机取证见 [`evidence/D7-2026-11-11/`](../evidence/D7-2026-11-11/README.md)。

### `pm clear` 会假装成一次卸载

`adb shell pm clear app.quizgate.tv` 会同时清掉 `/data/data` 里的 `anchorInit` 和 `pinHash`，
但**不会动系统设置里的锚点**。应用下次启动看到「锚点在 + 没口令 + 没首装标记」，
与真卸载重装无法区分，会累加一次卸载计数。这是判据的固有边界（`pm clear` 需要 adb，
孩子用遥控器做不到），但做完测试要恢复干净首装态时，必须**先删锚点再 clear**：

```bash
adb shell settings delete secure quizgate_install_anchor
adb shell settings delete system quizgate_install_anchor
adb shell pm clear app.quizgate.tv
```

删完重进应用，日志应出现 `anchor_init enabled=true` 且 `uninstalls=0`。
