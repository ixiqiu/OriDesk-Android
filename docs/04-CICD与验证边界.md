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

### 2.3 匿名 API 能读到什么

| 端点 | 匿名可读 |
|---|---|
| `/repos/{o}/{r}/actions/runs` | ✅ 200 |
| `/repos/{o}/{r}/actions/runs/{id}/jobs` | ✅ 200 |
| `/repos/{o}/{r}/commits/{ref}/check-runs` | ✅ 200 |
| `/repos/{o}/{r}/actions/runs/{id}/logs` | ❌ **403** |

→ 匿名只能看**成功/失败**，**看不到日志**。这直接决定迭代方式（见 §5）。

### 2.4 本机环境

| 项 | 状态 |
|---|---|
| Python | 3.12.14（`.venv`） |
| pip | **缺失**（`python -m ensurepip` 可用，版本 25.0.1） |
| 网络 | pypi 200 / github.com 200 / api.github.com 200 |
| Android 工具链 | **全缺** |
| git 凭据 | 无 credential helper、无 `~/.git-credentials`，**无法 push**（需 token） |
| 仓库可见性 | `ixiqiu/OriDesk` 是 **public**，分支 `master`，工作树干净 |

## 3. Workflow 设计

| Workflow | 触发 | 产出 |
|---|---|---|
| `ci.yml`（现有，不动） | 所有 push + PR | pytest（unit SQLite + mariadb 两道作业） |
| `android.yml`（新增） | `paths: android/**` + `workflow_dispatch` | `assembleDebug` → APK artifact；打 tag → GitHub Release 附带 APK |
| `docker-publish.yml`（新增） | 打 tag | 镜像推 `ghcr.io` |

要点：
- **`paths` 过滤是必要的**，否则每次改后端都白跑一遍 Gradle
- Gradle 依赖需联网下载，首次慢，之后靠缓存
- Release APK 公开可下载——因为仓库公开。APK 内不含密钥（服务器地址运行时填），可接受

## 4. 签名方案（已确认）

**固定 release keystore 存 GitHub Secrets。**

| 方案 | 配置成本 | 代价 |
|---|---|---|
| Debug APK | 零 | 每次构建签名不同 → **每次升级必须卸载重装**，本地数据丢、ntfy 订阅要重设 |
| **固定 release keystore** | 一次性：本地生成 keystore → base64 → 存 4 个 secret | 之后可覆盖升级，正常 |

需要的 4 个 secret：`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。

> 公开仓库下 Secrets 是安全的：fork 的 PR 拿不到 secrets；只有仓库内分支与手动触发的运行能用。

## 5. 迭代方式（依赖 §2.3 的发现）

| 方式 | 迭代体验 |
|---|---|
| **最小权限 token**（仅本仓库，`contents:write` + `actions:read`） | 能 push、能下载日志、能自己改到编译通过。**最快** |
| 用户 push，失败后把日志贴回来 | 可行，但每轮都要用户参与；Android 首次构建通常要来回几轮 |
| workflow 失败时用内置 `GITHUB_TOKEN` 把 Gradle 错误尾巴发成 commit comment（公开可匿名读） | 不用额外 token，但仓库里会出现临时评论，修好后需删除 |

## 6. 验证边界（必须如实告知用户）

### ✅ 我能验证

- 后端：`pytest` 全绿、`manage.py check`、`check --deploy`、`makemigrations --check --dry-run`
- 后端：通知受众解析、聚合、@解析（含邮箱 `@` 陷阱）、ntfy 请求构造（打桩）
- Android：**云端编译通过、产出 APK**（经 GitHub Actions）
- 文档正确性

### ❌ 我无法验证

- APK **真机运行**（无设备、无 adb）
- **ntfy 实际推送到达**（需真实服务端与手机）
- 国产 ROM 后台存活行为
- 通知点击深链的实际跳转
- 升级覆盖安装

## 7. 风险清单

| # | 风险 | 缓解 |
|---|---|---|
| 1 | APK 无法本机编译，安卓部分必然要来回几轮 | 云端 CI 编译验证 + 依赖最少化 |
| 2 | 国产 ROM 杀后台导致推送延迟 | 引导用户加白名单；文档写明；无法根除 |
| 3 | 公开仓库泄露内部信息 | 服务器地址运行时填写；密钥绝不入库；ntfy topic 随机化 |
| 4 | 新增模型导致 `makemigrations --check` 红 | 提交迁移文件；CI 把关 |
| 5 | 通知风暴 | 聚合 + 去重 + 不推发起人 + 关闭工单不推 |
| 6 | 聊天中泄露的 PAT | **用户需吊销** |
| 7 | 自动部署打断收信流水线 | 部署手动触发（已确认） |
