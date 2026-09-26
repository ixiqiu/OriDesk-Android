# dist/ —— 构建产物

> APK 本身被 `.gitignore` 的 `*.apk` 排除，**不入库**；本说明入库，用来记录
> "这个包到底是哪次构建、什么签名状态"。

## 本次产物

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `OriDesk-1.0.0-release.apk` | 2.43 MB | `98422af46b0d3cbf02f9fe33a7eae2f683f0c16d3be80c82c4fed87bb9f8c3fe` |
| `OriDesk-1.0.0-debug.apk` | 3.09 MB | `78475c0dff51f89c7295d87dab0c31f6f89c05a0b971de3a02310b11c1aa0d0c` |

- 来源：GitHub Actions run `36235000672`（提交 `f4fc5d4`）
- 应用包名：`com.xinjiyuan.oridesk` · minSdk 26 · targetSdk 35 · versionName 1.0.0

## ⚠️ 签名状态：**debug 签名**，不能覆盖升级

当前仓库**尚未配置** 4 个 keystore secret（`KEYSTORE_BASE64` /
`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），所以 CI 退回了 debug 签名。

这不是"也能用"，它有一个具体的后果：

> Android 的 debug keystore 是**每次 CI 运行现生成**的（GitHub 托管 runner
> 每次都是全新虚拟机）。因此**这一次构建的 APK 和下一次构建的 APK 签名不同**，
> 装新版本时必须先卸载旧版本 —— 卸载会清掉本地数据与 ntfy 订阅。

keystore 已经生成好了（`env/oridesk-release.p12`，口令见
`env/oridesk-release-info.txt`），把它配进 4 个 secret 之后重新跑一次
Release workflow，产出的 APK 就具备正常的覆盖升级能力（决策 8）。

## 装之前需要知道的两件事

1. **必须先配好服务器地址**：首次启动会显示引导条 → 进「设置」→ 填
   `https://` 开头的地址 → 「打开登录页」登录 → 再打开「启用推送」。
2. **推送要能工作，后端必须先部署**：本 APK 只包含客户端。
   推送链路还需要后端跑起 `apps/notifications`（提交 `5e8dbda`）与
   ntfy 服务（提交 `2f711a3`），并在后端「系统设置」里填 `ntfy_server_url`。

## 已验证 / 未验证

**已在本机核实**（无 Android 工具链，靠解析 APK）：

- 编译通过，产出结构合法的 APK（含 `AndroidManifest.xml` / `classes.dex` / `resources.arsc`）
- **v2/v3 签名块存在**（现代 AGP 默认不生成 `META-INF/CERT.RSA`，属正常）
- 清单里该有的都在：包名、`oridesk://ticket` 深链、`POST_NOTIFICATIONS`、
  `FOREGROUND_SERVICE_SPECIAL_USE`、`RECEIVE_BOOT_COMPLETED`、
  四个组件（MainActivity / SettingsActivity / NtfyService / BootReceiver）
- 资源表含通知图标与通知渠道名

**无法验证**（没有设备、没有 adb，`04-CICD与验证边界.md` §6 已列明）：

- 真机运行、界面实际表现
- **推送实际到达**（需要真实 ntfy 实例 + 后端部署）
- 国产 ROM 的后台存活（方案 B 的已知不可控项）
- 通知点击深链的实际跳转
- 覆盖升级
