# OriDesk Android 客户端 · 工作区

> 状态：**阶段 0/1/2/3 已完成**（2026-09-26）；**阶段 4 待指令**
> 安卓工程在 `android/`，构建产物在 `dist/`。后端改动已落在 OriDesk 原仓库（提交 `5e8dbda`、`2f711a3`）。

## 这是什么

为 [ixiqiu/OriDesk](https://github.com/ixiqiu/OriDesk)（自部署的多用户共享邮箱与工单系统）
配套的**安卓客户端**。本目录独立于后端仓库。

## 仓库规划

| 仓库 | 放什么 | 说明 |
|---|---|---|
| `ixiqiu/OriDesk`（已有，**public**） | 后端改动：`apps/notifications/`、角标端点、pipeline 钩子、ntfy 配置、docker-compose | 直接在原仓库改 |
| `ixiqiu/OriDesk-Android`（新建，**public**） | 安卓工程 + 本目录的设计文档 | 公开仓库 Actions 分钟数免费，Android 构建慢，这点很重要 |

**注意**：后端改动落在 OriDesk 原仓库，**不在本目录**。本目录只放安卓工程与设计文档。

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/01-决策记录.md](docs/01-决策记录.md) | 本次对话锁定的全部决策与理由，含被否决的方案 |
| [docs/02-OriDesk后端侦察.md](docs/02-OriDesk后端侦察.md) | 后端代码事实：可复用点、钩子位置、权限语义、模型字段、已知陷阱 |
| [docs/03-通知与推送设计.md](docs/03-通知与推送设计.md) | 事件→受众规则、聚合、@解析、安全 |
| [docs/04-CICD与验证边界.md](docs/04-CICD与验证边界.md) | GitHub Actions 实测数据、workflow 设计、签名、**我能验什么/不能验什么** |
| [docs/移动端API约定.md](docs/移动端API约定.md) | **阶段 0 已冻结契约 v1.0**：端点、鉴权/CSRF、ntfy 载荷、新增模型与设置项、决策 D1–D11 |

## 目录约定

```
.
├── README.md
├── docs/                 设计文档（已就绪）
├── env/secrets.env       凭据（600 权限，已 gitignore，绝不入库）
└── android/              安卓工程（待创建）
```

## 下一步（按顺序）

1. ~~把 `GITHUB_TOKEN` 从 `env/secrets.env` 读入环境变量，验证有效性~~ ✅ 已核实（2026-09-26）
2. ~~新建 GitHub 仓库 `ixiqiu/OriDesk-Android` 并推送本目录~~ ✅ 已完成（`master` @ `27502b4`）
3. ~~**阶段 0**：写 `docs/移动端API约定.md`，冻结契约~~ ✅ **已冻结 v1.0.1**（D1–D11 全部采纳，2026-09-26）
4. ~~**阶段 1**：在 OriDesk 仓库实现 `apps/notifications/`（模型 + 受众解析 + 聚合 + ntfy 发布 + pipeline 钩子）+ pytest~~ ✅ **已完成**（OriDesk `5e8dbda`）
5. **等你验证**：后端阶段 1 已完成，**先手动 `curl` 打通 ntfy**，确认推送链路，再写 App
   （需要真实 ntfy 实例 + 手机；也可先用 E6 测试推送端点，不必手写 curl）
6. ~~**阶段 2**：角标端点 + 订阅端点 + 设置项~~ ✅ **已完成**（同上提交，端点 E1–E6）
7. ~~**阶段 3**：安卓工程（WebView 壳 + ntfy 订阅 + 前台服务）~~ ✅ **已完成**
   （`android/`，CI run `36235000672` 编译通过并产出 APK；详见 `dist/README.md`）
8. **阶段 4**：部署指南 + 签名密钥配置 —— **等指令**

> **阶段 0 冻结的要点**
> - **决策 D1 = 方案 B**：App 内自己实现 ntfy 订阅，自己弹通知、自己管角标。
>   官方 ntfy App 发的通知只会给它**自己的**图标加角标，拿不到我们要的角标。
> - **ntfy 保留**：自托管，Apache-2.0，无付费墙（"ntfy Pro" 是给"你向自己用户收费"用的，
>   默认不存在）。代价是方案 B 的客户端工程量 + 前台服务耗电，明确接受。
> - D4 有意偏离 `03` 文档：ntfy token 存 **Fernet 密文**，不放 `Setting` 明文。
> - 完整决议见 [docs/移动端API约定.md](docs/移动端API约定.md) §9；
>   翻案留痕见 [docs/01-决策记录.md](docs/01-决策记录.md) §4③。

## 凭据

`env/secrets.env` 保存 `GITHUB_TOKEN`，权限 600，已被 `.gitignore` 排除。

用法（每条命令内临时加载，不要持久 export）：

```bash
set -a; . env/secrets.env; set +a
curl -H "Authorization: Bearer $GITHUB_TOKEN" https://api.github.com/user
```

**安全提醒**：该 token 曾在聊天中以明文出现，且 `ixiqiu/OriDesk` 是公开仓库。
建议尽快吊销并换成**仅限本仓库、最小权限**（`contents:write` + `actions:read`）的细粒度 token。
