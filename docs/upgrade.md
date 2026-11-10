# 在线升级

每次进入应用检查一次 `version.json`，有新版本就弹一页，**可以点「以后再说」跳过**。
只有明确检测到更高 `versionCode` 才提示；检查失败（没网、DNS 挂了、服务器 500）一律当成
「没有更新」，不在界面上留任何痕迹，只在 logcat 里记一行 `update_check_fail`。

设计取舍见 [ADR-9](architecture.md#adr-9-在线升级可取消失败静默只连一个地址)。

---

## 1. 服务端只要两个文件

正式位置（固定不变，客户端里就是写死这个目录）：

```
https://shitang.mianmiangou.cn/static/quizgate-tv/
├── version.json              ← 客户端靠它判断有没有新版
└── quizgate-1.2.3.apk
```

> **这个目录不支持列目录**（`GET 目录` 返回 404），客户端没法「看看文件夹里有什么」，
> 只能按固定文件名 `version.json` 去取。所以每次发版**必须**传 `version.json`，
> 只传 apk 等于没发。

`version.json`：

```json
{
  "versionCode": 10203,
  "versionName": "1.2.3",
  "apk": "quizgate-1.2.3.apk",
  "sha256": "完整的 64 位十六进制",
  "notes": "更新说明，会原样显示在升级页上，支持换行"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `versionCode` | ✅ | 必须**大于**已安装版本才会提示；算法 `MAJOR*10000+MINOR*100+PATCH`，1.2.3 = 10203 |
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
./tools/publish.sh 1.2.4 "这次改了什么"
```

脚本做的事：按版本号算 `versionCode` → 注入清单和 `BuildCfg.UPGRADE_BASE` →
`build.sh release`（release 签名，`keystore/release.jks`）→ 算 sha256 →
写 `dist/version.json`。

然后把**这两个文件**传到 `quizgate-tv/` 目录：

```
dist/quizgate-1.2.4.apk
dist/version.json
```

**先传 apk，最后传 version.json** —— 顺序反了会出现「清单说有新版，包还没传完」。

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
