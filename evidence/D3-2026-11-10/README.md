# D3 真机证据（2026-11-10，MiTV-MFTR0 / Android 11）

本目录是 v1.2.x 两个新功能（SAFE 熔断、在线升级）的真机验收记录。

## 一、SAFE 熔断

| 文件 | 内容 |
| --- | --- |
| `01_safe_mode_banner.png` | 首页红色横幅「守护已停止（安全模式）」 |
| `02_safe_mode_dialog.png` | 点横幅后的恢复对话框（含崩溃信息） |
| `03_safe_mode_recovered.png` | 恢复守护后横幅消失 |

链路：连续 3 次服务崩溃 → `crashCount=3`、`safeMode=true` → `onStartCommand` 返回
`START_NOT_STICKY` 并 `stopSelf()`（12 秒内无新 `safe_mode` 日志，确认不再重启循环）→
手动恢复后 `crashCount=0`，再开会话 `service_create ... safe=off`。

## 二、在线升级（两段式：下载落盘 → 用户点安装）

| 文件 | 内容 |
| --- | --- |
| `04_upgrade_panel.png` | 旧版一页式升级页（v1.2.0 形态，留档对比） |
| `05_upgrade_saved_to_download.png` | 旧版「已保存到 Download」提示（留档对比） |
| `07_upgrade_ready.png` | 新版第一步：说明「下载后存到下载目录，装不装由你决定」+「立即更新」 |
| `08_upgrade_downloaded.png` | 新版第二步：显示 `Download/quizgate-1.2.4.apk` +「立即安装」/「稍后再说」 |
| `09_downloaded_after_restart.png` | `am force-stop` 模拟系统回收后重进，仍直接认出已下好的包 |
| `10_installer_page.png` | 点「立即安装」后拉起的系统安装页（`PackageInstallerActivity`，「继续更新」） |
| `06_installer_probe.txt` | 安装器能力探针 —— **含一次重大误判的复盘** |

### 实测链路（两轮，均成功）

```
update_check self=10201 remote=10202 (1.2.2)
update_downloaded bytes=71088 sha=9ef31638e4b5
update_inspect ok pkg=app.quizgate.tv vc=10202
update_saved Download/quizgate-1.2.2.apk uri=content://media/external/downloads/14142
（点「立即安装」）
mCurrentFocus=com.android.packageinstaller/.PackageInstallerActivity
→ dumpsys package: versionName=1.2.2   ← 装上了

第二轮（含进程回收场景）：1.2.3 → 1.2.4，同样通过
```

### 未完成/被推翻的结论

- **「小米电视系统里没有 APK 安装器，只能手动落盘」是错的**，见 `06_installer_probe.txt`。
  错因是 `resolve-activity` 探测时没给 data scheme；实际安装器存在，用 `content://` 能拉起。
- 结论已同步修正到：`README.md`、`CHANGELOG.md`、`SECURITY.md`、
  `docs/upgrade.md`、`docs/architecture.md`（ADR-9）。

### 环境备注

- 测试期口令由测试者临时自设（**不是产品默认值**，安装包里没有默认口令），恢复码见当次截图；
  验收结束后已 `pm clear`，这些凭据在设备上已不存在
- **验收结束后已 `pm clear` 回到全新状态**：库里没有口令，打开就是「设置 6 位家长口令」，
  口令由使用者自己设。交付时电视上不存在任何测试凭据
- 正式包签名与调试包签名不同（`release.jks` vs `debug.keystore`），
  装过调试包的机器换正式包需先卸载 —— 这是 v1.2.3 加「安装前比对签名」的直接原因

## 三、口令归属：首装自设 / 升级沿用

用户要求：**第一次安装（含卸载重装）必须让用户自己设口令；覆盖升级则沿用原来的密码。**
代码里没有默认口令常量，全靠 `Prefs.hasPin()` 一个判断（`MainActivity.gate()`）：
无 `pinHash` → 走设置流程（输两遍 → 生成并展示新恢复码）；有 `pinHash` → 只做校验。

| 文件 | 内容 |
| --- | --- |
| `11_upgrade_keeps_pin.png` | `adb install -r` 覆盖安装后仍是「家长口令 / 输入 6 位数字进入设置」，不是设置页 |
| `12_wrong_pin_rejected.png` | 同一界面输 `123456` → 红字「口令不正确」，说明校验的是旧口令 |
| `13_settings_state_kept.png` | 输原口令进入后：家长口令「已设置」、恢复码「已保存」、守护「正常」——数据全保留 |
| `14_fresh_install_set_pin.png` | `adb uninstall` + 重装后 → 「设置 6 位家长口令」，旧口令无处可用 |
| `15_first_run_new_recovery.png` | 首装设完口令 → 立刻弹出**新**恢复码（与升级前那枚不同，旧码随即作废） |

`android:allowBackup="false"`，所以卸载重装不会从系统备份里把旧口令捞回来。

