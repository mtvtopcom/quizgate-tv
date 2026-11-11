# D13 · 2026-11-11 · v1.3.0 正式发布线上验证与真实 OTA 验收

时间：2026-11-11 23:44–23:56（用户上传完生产源之后）。
设备：D1 `192.168.1.123:5555`（Android 11，发布前已刷最终包）、
D2 `192.168.1.88:5555`（Android 14，起点 **1.2.9 / 10209** 干净首装态）。

## 1. 线上生产源（真 GET，不用 HEAD）

```
本地 dist/quizgate-1.3.0.apk   ba8a51bf29b175673151051871f9a83d8ce62beffca2c5521e31c6f7fa623ce2   244647 字节
主目录 apk（真下载）            ba8a51bf29b175673151051871f9a83d8ce62beffca2c5521e31c6f7fa623ce2   244647 字节
主目录 version.json             versionCode 10300 / sha256 同上 / notes 四行
GitHub Release 附件             ba8a51bf…ce2（发布当晚已回下载核对）
```

`https://7789.cn/y/quizgate-tv/version.json` 返回 200、605 字节；
主目录还保留着旧的 `quizgate-1.2.9.apk`（239102 字节，200），不影响新清单。

### 旧升级目录已下线（用户确认永久下线）

```
https://shitang.mianmiangou.cn/                                200（12656 字节，站点首页）
https://shitang.mianmiangou.cn/static/                        404（207 字节错误页）
https://shitang.mianmiangou.cn/static/quizgate-tv/            404
https://shitang.mianmiangou.cn/static/quizgate-tv/version.json 404
```

域名解析与 TLS 正常，是 `/static/` 这一层不再提供。发布前（同一天早些时候）该路径
还能取到 10209 的 `version.json`，因此是本轮上传后新出现的状态，不是历史遗留。

影响范围（按 `docs/upgrade.md` 的版本分界）：

| 客户端 | 取清单的地址 | 能否收到 1.3.0 |
| --- | --- | --- |
| 1.3.0 | 只连主目录（无 fallback） | 能 |
| 1.2.9 | 主目录，失败才回落旧目录 | 能（本次 D2 实测） |
| ≤1.2.8（含已流出的 1.2.6） | 只认旧目录 | **不能**，需手动装 apk |

`docs/upgrade.md` 当时写过「等确认绝大多数电视升到 1.2.9 之后，旧目录才可以停止更新（但别删）」；
用户已确认本次为**永久下线、不再恢复**，文档与 Release 正文的处置见本文件第 4 节。

## 2. D2：1.2.9 → 1.3.0 真实 OTA

| # | 步骤 | 证据 |
| --- | --- | --- |
| 1 | 冷启动取清单：`update_check_src https://7789.cn/y/quizgate-tv/ remote=10300 (1.3.0)`、`update_check self=10209 remote=10300` | logcat |
| 2 | 首装无口令 → 设 6 位测试口令（输两遍）→ 展示恢复码 → 点「我已拍照保存」 | — |
| 3 | 再冷启动弹升级页：标题「发现新版本 1.3.0」、「当前 1.2.9 → 1.3.0」、四行说明首行即改名说明，两个按钮都在 1080p 屏内 | `01-d2-update-dialog.png` |
| 4 | 点「立即更新」：`update_downloaded bytes=244647 sha=ba8a51bf29b1`、`update_inspect ok pkg=app.quizgate.tv vc=10300`、`update_saved Download/quizgate-1.3.0.apk` | `02-d2-downloaded.png` |
| 5 | 系统安装页 `com.android.packageinstaller/.PackageInstallerActivity`：应用名「答题续时」、来源「答对再看 \| 1.3.0」、「您现有的数据不会丢失」、默认焦点在「取消更新」 | `03-d2-installer.png` |
| 6 | 按 RIGHT 选中「继续更新」→ OK → 「安装成功」；`dumpsys package` 变为 `versionCode=10300 / 1.3.0` | `04-d2-install-success.png` |
| 7 | 点「打开」：首页显示「答题续时」＋副标题「到点一题 · 答对续时」，守护状态「待机 · 授权齐全」，**没有**再弹设置口令页、**没有**红色卸载留痕提示；`update_check self=10300 remote=10300` 不再提示 | `05-d2-home-after-ota.png` |
| 8 | 进「设置」弹「家长口令 / 输入 6 位数字进入设置」，底部「忘记口令？用恢复码重置」完整可见；输入升级前的原口令直接通过 | `06-d2-pin-gate.png` |
| 9 | 设置页：本次时长 30 分钟、答对延长 +5 分钟、**题目年级「小学 · 3 年级 · 数学」**（1.3.0 三级设置迁移正确）、家长口令「已设置」、恢复码「已保存」、守护状态「待机 · 授权齐全」 | `07-d2-settings-after-ota.png` |

结论：清单 → 下载 → sha 校验 → 包信息/签名比对 → 落盘 → 系统安装器 → 覆盖安装，
链路完整；**口令、恢复码、时长与年级全部沿用**，改名后桌面/安装器/首页名称一致。

### 升级说明的显示限制（已知，非故障）

`UpgradeView` 限四行＋省略号。第 2 行较长会折成两个视觉行，导致 notes 的第 4 行
（「1.2.9 及以前仍需三条必需授权；升级不改口令、恢复码、时长与年级。」）在 1080p 上
被省略（截图里显示为「…锚点非回...」）。改名说明在第 1 行，完整可见；
完整四条说明在 Release 正文与 README。1.2.6 曾因不限行数把按钮顶出屏幕，故不放大上限。

## 3. D1：桌面名与图标缓存刷新

`am force-stop com.mitv.tvhome` 之后，HOME → 侧边栏「应用」→「我的应用」→「已安装应用」
滚到底，最右下角显示 **「答题续时」＋ v3 机器人图标**（黑底黄白机器人），
没有残留「答对再看」旧名或 `_v2` 旧图。见 `08-d1-launcher-name.png`。
换资源名绕开小米桌面按资源名缓存的做法在 1.3.0 同样有效。

> 桌面「已安装应用」用的是 `android:icon`；`android:banner` 只在 Leanback 类桌面使用，
> 本次没有单独取 banner 显示截图，包内资源已由 `aapt2 dump badging` 确认指向 `banner_v3`。

## 4. 处置结论（用户 23:56 确认）

- **旧目录确认永久下线、不再恢复**。README、`docs/upgrade.md`、CHANGELOG 与 Release 正文
  已统一改为「旧目录已下线，≤1.2.8 需手动覆盖安装 1.3.0 apk」；README 的装机步骤、
  故障表与「不做什么」第 4 条都补了手动升级路径。以后发版只传主目录两个文件。
- **双机保持 1.3.0**，本次不退回旧版（用户明确：下次再发版后恢复「留一台旧版」的约定）。
  下次发 1.3.1 时 1.3.0 → 1.3.1 仍是一次有效 OTA 验收。
