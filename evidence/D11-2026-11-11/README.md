# D11 · 2026-11-11 · v1.2.9 真实 OTA 验收（D1：1.2.6 → 1.2.9）

## 前置：服务器三方一致

用户把 `dist/quizgate-1.2.9.apk` 与 `dist/version.json` 传到两个目录之后：

```
本地 dist        50fe7f845bce3ffb3ee20f780f4711a1298b4cc94f9edfa25aac0af0ab197bc8
旧目录           50fe7f845bce3ffb3ee20f780f4711a1298b4cc94f9edfa25aac0af0ab197bc8
新目录           50fe7f845bce3ffb3ee20f780f4711a1298b4cc94f9edfa25aac0af0ab197bc8
Release 附件      50fe7f845bce3ffb3ee20f780f4711a1298b4cc94f9edfa25aac0af0ab197bc8
```

两个目录的 `version.json` 都是 `versionCode 10209`，apk 都是 239102 字节。

## OTA 过程（D1 / Android 11，起点 1.2.6 / 10206）

1. 应用冷启动 → `update_check self=10206 remote=10209 (1.2.9)`，确认拿到线上清单。
2. 首装没有口令 → 先设 6 位口令（`123456`），生成恢复码并展示。
3. 升级页 → 下载 239 KB → 校验 sha256 → 存到「下载」目录 → 拉起系统安装器。
4. 安装器来源显示 **「答对再看 | 1.2.9」**（`01_d1_installer_source_129.png`）；
   选中「继续更新」按 OK 开始安装。
5. **安装成功**（`02_d1_install_success.png`）。
6. 打开新版：首页正常（`03_d1_home_after_ota.png`），
   进设置先弹「家长口令」而不是首装页（`04_d1_pin_gate_after_ota.png`）——
   说明 `pinHash` 跨升级保留，用原口令 `123456` 能进。
7. 设置页（`05_d1_settings_after_ota.png`）：本次时长 30 分钟、答对延长 +5 分钟、
   题目年级「小学 · 3 年级」、家长口令「已设置」、恢复码「待拍照」、守护状态「待机 · 授权齐全」。
8. 新版再次检查更新：`update_check_src https://7789.cn/y/quizgate-tv/ remote=10209`，
   `self=10209` 不再提示。

## 关于「恢复码：待拍照」

不是丢码。`待拍照` 表示 `rcHash` 还在、只是家长没在展示页点「我已拍照保存」。
丢码会显示「未生成」。这次展示页是被升级提示盖住后直接跳过确认的，所以标记没置上。

## 顺带确认：主升级源证书已补齐

```
curl -v https://7789.cn/y/quizgate-tv/version.json
*  subject: CN=7789.cn
*  issuer:  C=US; O=Let's Encrypt; CN=YR1
*  expire date: Feb  9 06:57:19 2027 GMT
*  SSL certificate verify ok.
```

所以 1.2.9 这次是**直连新地址**取到清单的（日志里是 `update_check_src`，
没有先出现 `update_check_fail`）。旧目录仍保留同版本文件，作为 ≤1.2.8 的迁移入口。

## 结论

- 下载 → 校验 → 落盘 → 系统安装器 → 覆盖安装 → **口令与恢复码保留**，链路完整。
- 升级后不再提示 1.2.9（`self == remote`）。
- 授权（悬浮窗 / 通知使用权 / 电池白名单）在覆盖升级后仍然齐全（首页显示「待机 · 授权齐全」）。
