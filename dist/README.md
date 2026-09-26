# dist/ —— 构建产物

> APK 本身被 `.gitignore` 的 `*.apk` 排除，**不入库**；本说明入库，用来记录
> "这个包到底是哪次构建、什么签名状态"。

## 交付包：`OriDesk-1.0.1.apk`

| 项 | 值 |
|---|---|
| 文件 | `OriDesk-1.0.1.apk` |
| 大小 | 2.43 MB |
| SHA-256 | `470a9f612ff199c83584bd825ba344d9cbfd6c034b3036c1c624f828d8ee871d` |
| 包名 | `com.xinjiyuan.oridesk` |
| 版本 | versionName `1.0.1` · versionCode `10101` |
| minSdk / targetSdk | 26 / 35 |
| 签名 | ✅ **固定 release keystore**（v2 + v3），证书 SHA-256 `9422b809…8b1bbe` |

另有 `OriDesk-1.0.1-debug.apk`（3.09 MB，debug 构建，`debuggable=true`），
仅用于 `adb logcat` 排查；日常使用装上面那个。

### 这个包的签名是"正式"的

它用 `env/oridesk-release.p12`（固定 keystore）签名，**与将来 CI 配好 4 个
GitHub Secret 后产出的包签名完全一致**。含义：

- 装它之后，后续版本可以**直接覆盖升级**，不必卸载、不丢本地数据与推送订阅。
- 之前那个 debug 签名的包（`1.0.0`）签名不同，**从它换到本包需要卸载一次**。
  这是最后一次签名变更。

`versionCode` 与 Release workflow 从 tag 推导的编码保持一致
（`MAJOR*10000 + MINOR*100 + PATCH`），避免分支构建与 tag 构建互相"降级"。

## 本版修了什么

**Android 15 强制 edge-to-edge 导致状态栏压住顶部按钮**（真机反馈）。

根因不是主题写错，而是 targetSdk 35 的应用被系统强制 edge-to-edge，
窗口延伸到状态栏底下，网页的移动顶栏因此被压住。
修法是按窗口 insets 给根布局留边距（`UiInsets.kt`），并处理输入法 insets，
避免键盘盖住设置页的输入框。

刻意**没有**用 `windowOptOutEdgeToEdgeEnforcement` —— 那个开关只在 API 35
有效且会被后续版本移除，用它等于把必然复发的 bug 推给未来。

## 装之前需要知道的

1. **首次启动**：顶部引导条 →「设置」→ 填 `https://` 开头的服务器地址 → 保存 →
   「打开登录页」登录。
2. **推送需要后端先部署好**（见下），且后端「系统设置」里填好 ntfy 配置，
   否则 App 里点「启用推送」会明确提示配置缺失。

## 后端部署（推送链路的另一半）

后端代码已推送到 `ixiqiu/OriDesk`（`40d8f09`），包含：

- `apps/notifications/`：受众解析、@提及、聚合、ntfy 发布、三处钩子
- 端点 `E1–E6`（`/api/mobile/…`）
- `docker-compose.yml` 新增 ntfy 服务与数据卷
- **系统设置页新增推送配置区**（此前只加了设置项却没有 UI，管理员无处可填 ——
  这是真机验证前不会暴露的阻塞性缺口）

```bash
cd <OriDesk 部署目录>
git pull
docker compose up -d --build

# ntfy 默认 deny-all + 开登录，必须先建用户与令牌：
docker compose exec ntfy ntfy user add --role=admin oridesk      # 发布方（后端用）
docker compose exec ntfy ntfy token add --label=backend oridesk  # 记下 tk_…
docker compose exec ntfy ntfy user add phone                     # 订阅方（手机用）
docker compose exec ntfy ntfy access phone "oridesk-*" read-only
docker compose exec ntfy ntfy token add --label=phone phone      # 记下 tk_…
```

然后在 OriDesk「系统设置 → 移动端推送」填：

- 启用移动端推送 ✅
- ntfy 服务地址：**手机能访问到的 https 地址**（如 `https://ntfy.你的域名`）。
  填 `127.0.0.1` 或容器内网名会"后端发得出去、手机订阅不到"。
  填 `http://` 会被**拒绝**（客户端强制 HTTPS）。
- ntfy 访问令牌：后端那把（`--label=backend`）

最后在 App 设置页填 `--label=phone` 那把令牌，点「发送测试推送」——
通没通立刻就知道。

> ⚠️ 反代 ntfy 时记得设 `behind-proxy: true`（compose 里已默认开启），
> 否则所有手机共用一个限流桶，几台设备就能互相把对方限流掉。

## 已验证 / 未验证

**已在本机核实**（无 Android 工具链，靠静态解析与 aapt2）：

- 编译通过（CI run `36235953727` 及后续），产出结构合法的 APK
- `aapt2 dump badging`：包名、`versionCode=10101`/`versionName=1.0.1`、
  7 项权限（含 `FOREGROUND_SERVICE_SPECIAL_USE`、`RECEIVE_BOOT_COMPLETED`）
- `aapt2 dump xmltree`：`usesCleartextTraffic=false`、`oridesk://ticket` 深链、
  `foregroundServiceType=0x40000000`（SPECIAL_USE）、四个组件齐全
- **签名**：v2 + v3 校验通过，证书指纹与 keystore 一致
- keystore 本身：JDK 17 `keytool` 可读、`jarsigner` 可签、`apksigner` 可签可验

**已由真机确认**：能登录（WebView 壳与 session 复用正常）。

**仍无法验证**：推送实际到达、国产 ROM 后台存活、通知点击跳转、
覆盖升级、输入法 insets 的实际观感。
