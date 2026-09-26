# 04 · CI/CD 与验证边界

## 1. 为什么用 GitHub 构建

本机**没有 Android 工具链**（java / javac / gradle / sdkmanager / adb / kotlinc 全部缺失，
`ANDROID_HOME` 未设）。所以云端构建是唯一能真正验证「代码能编译、能产出 APK」的途径。

**公开仓库的 Actions 分钟数免费**——Android Gradle 构建每次 3～8 分钟，
私库会吃 2000 分钟/月配额，公开库不花钱。

## 2. 实测数据（2026-09-26 核实）

### 2.1 GitHub 托管 runner 自带 Android SDK

来源：`actions/runner-images` 的 `images/ubuntu/Ubuntu2404-Readme.md`

| 项 | 实测值 |
|---|---|
| SDK 路径 | `/usr/local/lib/android/sdk` |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | 均已设置 |
| Build-tools | 34.0.0 / 35.0.0 / 35.0.1 / 36.0.0 / 36.1.0 / 37.0.0 |
| Platforms | android-34 ～ android-37（含 34-ext8/10/11/12、35-ext14/15、36、36.1、37.x） |
| Platform-tools | 37.0.1 |
| Command Line Tools | 12.0 |
| NDK | 27.3.13750724（默认）/ 28.2.13676358 / 29.0.14206865 |
| CMake | 3.31.5 / 4.1.2 |

→ **`compileSdk 35` 开箱即用，workflow 里不需要任何 SDK 安装步骤。**

### 2.2 Gradle Wrapper 可获取

`gradle-wrapper.jar` 从 `raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar`
可下载（43,504 字节），校验为合法 jar（33 条目，含 `GradleWrapperMain`）。
→ 工程可自包含 `./gradlew`，不依赖 runner 预装 Gradle。

### 2.3 Actions API：匿名 vs 带 token

| 端点 | 匿名 | 带 token |
|---|---|---|
| `/repos/{o}/{r}/actions/runs` | ✅ 200 | ✅ 200 |
| `/repos/{o}/{r}/actions/runs/{id}/jobs` | ✅ 200 | ✅ 200 |
| `/repos/{o}/{r}/commits/{ref}/check-runs` | ✅ 200 | ✅ 200 |
| `/repos/{o}/{r}/actions/runs/{id}/logs` | ❌ **403** | ✅ **200** |

→ **2026-09-26 实测更正**：匿名看不到日志，但**带 token 可以下载**（实测拿到 22KB 的
日志 zip）。原结论"看不到日志，这直接决定迭代方式"只在匿名前提下成立。

这条差异很关键：它意味着**编译失败时能直接读日志定位**，不必靠"猜 + 再推一次"。
阶段 3 的第一轮失败（XML 注释里出现 `--`）就是靠下载日志一眼定位的。
因此"最小权限 token"不只是为了 push，也是为了**可诊断性**。

（另注：`secrets` 相关的端点即使有 admin 权限也可能 403 —— 本会话用的 token
就没有 `secrets:write`，因此 4 个 keystore secret 只能由人工配置。）

### 2.4 本机环境

| 项 | 状态 |
|---|---|
| Python | 3.12.14，位于**后端仓库** `/data/dsh/home/OriDesk/.venv`（**不在**本工作区） |
| pip | 后端 venv 内可用 |
| 网络 | pypi 200 / github.com 200 / api.github.com 200 |
| Android 工具链 | **全缺**（java / gradle / sdkmanager / adb / kotlinc 均无） |
| git 凭据 | 无 credential helper；push 需显式带 token |
| 仓库可见性 | `ixiqiu/OriDesk` 是 **public**，分支 `master` |

**2026-09-26 补充：本机不要跑 Android 构建。** 尝试用本地 JDK + 手工拼装的 SDK
跑 `./gradlew assembleRelease` 时把主机拖死（Gradle 首次构建要下 Gradle 发行版 +
AGP 依赖并编译，内存与 CPU 峰值远超本机容量）。
**Android 编译一律走 GitHub Actions。**
保留了 JDK 与 build-tools 仅用于**单次低开销**的签名/校验
（`keytool` / `jarsigner` / `apksigner` / `aapt2`），不用于构建。

## 3. Workflow 设计

| Workflow | 仓库 | 触发 | 产出 |
|---|---|---|---|
| `ci.yml`（原有，未动） | OriDesk | 所有 push + PR | pytest（unit SQLite + mariadb 两道作业） |
| `android.yml`（新增） | OriDesk-Android | `paths: android/**` + `workflow_dispatch` | Debug/Release APK artifact；`selftest_signing` 可验证签名流水线 |
| `android-release.yml`（新增） | OriDesk-Android | `v*` tag + `workflow_dispatch` | 签名 Release APK → GitHub Release |
| `docker-publish.yml`（新增） | OriDesk | `v*` tag + `workflow_dispatch` | 镜像推 `ghcr.io` |

要点：
- **`paths` 过滤是必要的**，否则每次改后端都白跑一遍 Gradle
- Gradle 依赖需联网下载，首次慢，之后靠缓存
- Release APK 公开可下载——因为仓库公开。APK 内不含密钥（服务器地址运行时填），可接受
- **`android.yml` 与 `android-release.yml` 拆成两个文件**（原计划是一个）：
  `paths` 与 `tags` 同处一个 push 触发器时的交互语义在官方文档里没有直白说明，
  赌错的后果是"打了 tag 却不发 Release"这种静默失效。拆开后两边触发条件都无歧义。
- `docker-publish.yml` 里有一处容易误判的坑：**ghcr.io 镜像路径必须全小写**，
  而仓库名 `ixiqiu/OriDesk` 含大写。用 `${GITHUB_REPOSITORY,,}` 转换，
  否则报 "invalid reference format"（很像权限问题，会误导排查方向）。

## 4. 签名方案（已确认）

**固定 release keystore 存 GitHub Secrets。**

| 方案 | 配置成本 | 代价 |
|---|---|---|
| Debug APK | 零 | 每次构建签名不同 → **每次升级必须卸载重装**，本地数据丢、ntfy 订阅要重设 |
| **固定 release keystore** | 一次性：本地生成 keystore → base64 → 存 4 个 secret | 之后可覆盖升级，正常 |

需要的 4 个 secret：`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。

> 公开仓库下 Secrets 是安全的：fork 的 PR 拿不到 secrets；只有仓库内分支与手动触发的运行能用。

**keystore 已生成**（2026-09-26）：`env/oridesk-release.p12`（PKCS12，RSA 2048，
有效期 30 年），口令与别名在 `env/oridesk-release-info.txt`（两者都在 `.gitignore` 内）。
它已通过三重验证：JDK 17 `keytool` 可读、`jarsigner` 可签可验、`apksigner`
可签出 v1=off/v2=on/v3=on 的 APK（证书 SHA-256 `9422b809…8b1bbe`）。

**签名流水线本身已在 CI 端到端验证**（不是在本地）：
`android.yml` 的 `selftest_signing` 派发参数会在没有 secret 时用 runner 上现生成的
一次性 keystore 走完完全相同的路径。实测步骤全部 success ——
`校验签名密钥`（keytool）、`编译 Release APK`、`验证 Release APK 的签名`（apksigner
输出 `Verifies`，v2=true，证书指纹与 keytool 一致）。

> 这一步值得单独记：**签名自检第一次跑就失败了**（`校验签名密钥` 读的是
> `secrets.KEYSTORE_PASSWORD`，而自检模式下它是空的），而这条路径在配置 secret 前
> 一直是 `skipped`。这正是"没跑过的代码等于没有的代码"的实例 ——
> 顺带还发现「4 个 secret 只配一半」会带着空口令继续跑、产出看起来正常的包，
> 已改为响亮失败。

> 剩余的人工动作只有一步：把 4 个值配进仓库 Secrets。Actions secrets API 需要
> `secrets:write`，本会话的 token 没有该权限（403），因此**无法代配**。
> 在配置完成前，CI 会退回 debug 签名；`selftest_signing` 可先验证签名流水线本身是通的。
>
> 若不想动 Secrets：也可以用 `env/oridesk-release.p12` + `apksigner` 手工签 CI 产物
> （`dist/OriDesk-1.0.1.apk` 就是这么来的），签名与 CI 配好 secret 后产的包**完全一致**，
> 升级路径不受影响。

## 5. 迭代方式（依赖 §2.3 的发现）

| 方式 | 迭代体验 |
|---|---|
| **最小权限 token**（本仓库，`contents:write` + `actions:read`） | 能 push、**能下载日志**、能自己改到编译通过。**最快**，阶段 3 实际用的就是这条 |
| 用户 push，失败后把日志贴回来 | 可行，但每轮都要用户参与 |
| workflow 失败时用内置 `GITHUB_TOKEN` 把 Gradle 错误尾巴发成 commit comment | 不用额外 token，但仓库里会出现临时评论，修好后需删除。**阶段 3 未用到**（token 能读日志） |

阶段 3 实测迭代轮次：**3 轮**（首轮 XML 注释非法 → 二轮通过 → 后续为版本号/文档）。
靠"下载日志 + 本机资源预检脚本"把轮次压了下来，而不是靠盲推。

## 6. 验证边界（必须如实告知用户）

### ✅ 我能验证

- 后端：`pytest` 全绿、`manage.py check`、`check --deploy`、`makemigrations --check --dry-run`
- 后端：通知受众解析、聚合、@解析（含邮箱 `@` 陷阱）、ntfy 请求构造（打桩）
- 后端：设置页确实渲染出推送配置输入框（**"能配置"与"不能配置"的分界**）
- Android：**云端编译通过、产出 APK**（经 GitHub Actions）
- Android：APK 的清单内容与签名——`aapt2 dump badging/xmltree` 核对包名、版本、
  权限、深链、前台服务类型；`apksigner verify` 核对签名方案与证书指纹
- keystore 本身可用（`keytool` / `jarsigner` / `apksigner` 三重）
- 文档正确性

### ❌ 我无法验证

- APK **真机运行**（无设备、无 adb）——**真机由用户代验**，阶段 3 已确认"能登录"
- **ntfy 实际推送到达**（需真实服务端与手机）
- 国产 ROM 后台存活行为
- 通知点击深链的实际跳转
- 升级覆盖安装
- **本机不能跑 Android 构建**（会拖死主机，见 §2.4）——构建验证只能走云端

## 7. 风险清单

| # | 风险 | 缓解 |
|---|---|---|
| 1 | APK 无法本机编译，安卓部分必然要来回几轮 | 云端 CI 编译验证 + 依赖最少化（只 2 个第三方依赖）+ 本机资源预检脚本 |
| 2 | 国产 ROM 杀后台导致推送延迟 | 引导用户加白名单；文档写明；无法根除 |
| 3 | 公开仓库泄露内部信息 | 服务器地址运行时填写；密钥绝不入库；ntfy topic 随机化 |
| 4 | 新增模型导致 `makemigrations --check` 红 | 提交迁移文件；CI 把关 |
| 5 | 通知风暴 | 聚合 + 去重 + 不推发起人 + 关闭工单不推 |
| 6 | 聊天中泄露的 PAT | **用户需吊销** |
| 7 | 自动部署打断收信流水线 | 部署手动触发（已确认） |
