# 移动端 API 约定（App ↔ OriDesk 后端契约）

> 状态：**已冻结 v1.0**（2026-09-26 人工确认 D1–D11 全部采纳）
> 冻结对象：端点路径、请求/响应结构、鉴权与错误语义、ntfy 载荷、新增模型与设置项。
> 冻结之后，阶段 1（后端 `apps/notifications/`）、阶段 2（角标/订阅端点）、阶段 3（安卓工程）
> 三边同时以此为准；任何一方要改，先改本文档并重新过审。

## 0. 怎么读这份文档

- **§1–§4 是契约正文**，实现方（后端 / App）逐条对齐。
- **§6 列出新增数据模型与设置项**，按开发文档 §10.4 属于「需人工确认」范围。
- **§9 是决策汇总表（D1–D11，全部已决）**。正文中每个决策点上的【已决 Dx】标记指向该表。
  其余部分是可直接执行的事实与推理，不需要逐字审。

本文所有「代码事实」均于 2026-09-26 在 `/data/dsh/home/OriDesk` 源码上核对过，标注了出处。

---

## 1. 总则

### 1.1 为什么不是一套完整 REST API

决策 3（`01-决策记录.md`）已冻结：**最小必要，只做角标 + 通知相关端点**。

事实依据（`02-OriDesk后端侦察.md` §1）：全站唯一 JSON 端点是 `/healthz`。业务全部靠
Django 模板 + HTMX + session cookie 完成，**没有 DRF、没有 JWT、没有版本化 API 层**。
App 的 WebView 直接复用网页，因此：

- 「收工单、回工单、认领、改派、打标签」**不需要任何新端点**，网页已经能做。
- 新端点只为两件网页做不到的事服务：**给 App 一个数字（角标）**、
  **让 App 把设备注册成推送订阅**。

### 1.2 端点总览

| # | 方法 | 路径 | 用途 | 阶段 |
|---|---|---|---|---|
| E1 | `GET` | `/api/mobile/badge/` | 角标计数；顺带下发 CSRF cookie | 2 |
| E2 | `GET` | `/api/mobile/subscriptions/` | 本用户的订阅列表 | 2 |
| E3 | `POST` | `/api/mobile/subscriptions/` | 注册/续订本设备（幂等 upsert） | 2 |
| E4 | `PATCH` | `/api/mobile/subscriptions/<id>/` | 改 `enabled` / 免打扰时段 | 2 |
| E5 | `DELETE` | `/api/mobile/subscriptions/<id>/` | 吊销本设备订阅 | 2 |
| E6 | `POST` | `/api/mobile/subscriptions/<id>/test/` | 发一条测试推送 | 2 |

**挂载点**：`config/urls.py` 新增 `path("api/mobile/", include("apps.notifications.urls"))`，
**放在 `path("", include("apps.tickets.urls"))` 之前**，避免将来 tickets 新增通配路由时被抢先匹配。
`apps/notifications/urls.py` 自带 `app_name = "notifications"`。

**【已决 D7】**路径前缀 `/api/mobile/`（备选 `/api/v1/mobile/`、`/mobile/api/` 未采用）。

### 1.3 为什么带 `/mobile` 而不是通用 `/api/v1/`

这套端点是**为这一个 App 的形状定制的**（角标 + 设备订阅），不是通用资源面。
用 `/api/mobile/` 表达「它的消费方只有这个 App」，避免日后被误当成对外 API 而背上兼容包袱。
真要做通用 REST 面时另起（决策表已把「完整 `/api/v1/`」降级）。

### 1.4 零新增依赖（硬约束）

决策 4 已冻结**零新增 Python 依赖**。落地影响：

- 视图用 Django 原生 `JsonResponse`，**不引 DRF**。
- 后端调 ntfy 用 **stdlib `urllib.request`**，**不引 `requests` / `httpx`**
  （已核对 `requirements.txt`：两者都不在）。
- topic 随机生成用 **stdlib `secrets`**。
- ntfy token 加密复用**已有的** `apps/core/crypto.py`（Fernet，`cryptography` 已是依赖）。

---

## 2. 鉴权、会话与 CSRF

### 2.1 认证方式：复用 WebView 的 session cookie

**没有独立的 API 认证机制。** App 的所有 API 请求携带 WebView 登录后拿到的
`sessionid` cookie。

理由（决策 4）：WebView 用 session cookie 登录后，「认证问题不存在」——引 JWT 等于
为同一份身份造第二套吊销/过期/刷新逻辑，且要新增依赖。

**关键实现事实**（`config/settings.py` §安全，生产分支）：

| 配置 | 值 | 对 App 的影响 |
|---|---|---|
| `SESSION_COOKIE_HTTPONLY` | `True` | **不影响原生代码**。HttpOnly 只挡 JS 的 `document.cookie`；App 用 `CookieManager.getCookie(url)` 读的是原生 cookie 罐，**读得到**。 |
| `SESSION_COOKIE_SECURE` | `True` | 会话 cookie 只在 HTTPS 下发 → **App 必须用 HTTPS**，见 §7.3。 |
| `SESSION_COOKIE_SAMESITE` | `Lax` | SameSite 是浏览器概念；原生 HTTP 客户端显式带 cookie，不受影响。 |
| `CSRF_COOKIE_SECURE` | `True` | 同上，仅 HTTPS。 |
| `CSRF_COOKIE_HTTPONLY` | `False` | **刻意如此**（注释：HTMX 需要读取 csrftoken）→ 原生代码能从 `CookieManager` 读到 `csrftoken`。 |
| `CSRF_COOKIE_SAMESITE` | `Lax` | 同上。 |

> 注：以上均在 `elif not DEBUG:` 分支。本地 DEBUG 与单元测试（`UNDER_TEST`）下
> `*_COOKIE_SECURE` 为 `False`，`CSRF_COOKIE_HTTPONLY` 未显式设置（Django 默认 `False`）。

### 2.2 未登录必须返回 401 JSON，不能 302

**这是最容易写错的一处。** 既有 `apps/core/permissions.py::login_required` 是
`django.contrib.auth.decorators.login_required`，未登录时**302 跳登录页**——
对浏览器正确，对 App 是灾难（客户端会把 HTML 登录页当成 API 响应解析）。

**约定**：`apps/notifications` 的视图**不得**直接用 `@login_required`，
必须用新增的 `apps/core/permissions.py::api_login_required`：

- 未登录 / 会话过期 → **`401`** + `{"error": {"code": "unauthorized", ...}}`
- 已登录但无权访问该对象 → **`404`**（沿用可见性不变量，见 §2.4）
- 已登录但方法不允许 → **`405`**

**【已决 D8】**`api_login_required` 放在 `apps/core/permissions.py`（与既有装饰器同处），
不放 `apps/notifications/decorators.py`。

### 2.3 CSRF：保留，用 `X-CSRFToken` 头

写操作（E3/E4/E5/E6）**保留 CSRF 校验**，不走 `@csrf_exempt`。

理由：`@csrf_exempt` 会让「浏览器里的恶意页面替已登录用户注册推送订阅」成为可能；
安全清单（开发文档 §10.3）要求写操作 `@require_POST` + CSRF。
`SESSION_COOKIE_SAMESITE=Lax` 虽已挡住跨站 POST 带 cookie，但**纵深防御不留缺口**。

**App 侧流程**（契约的一部分）：

1. App 启动后先调 **E1 `GET /api/mobile/badge/`**。该视图带 `@ensure_csrf_cookie`，
   会在响应里下发 `csrftoken` cookie。
2. 原生代码从 `CookieManager.getCookie(server)` 取出 `csrftoken` 的值。
3. 所有写请求带两个东西：
   - Header `X-CSRFToken: <csrftoken 的值>`
   - Header `Cookie: sessionid=...; csrftoken=...`（原生客户端自己拼，因为不是浏览器）

**若 `csrftoken` 读不到**（例如用户从未打开过带表单的页面，且 E1 尚未调用过）：
App 必须先成功调用一次 E1 再重试；失败则提示「请重新打开并登录」。

**【已决 D9】**采用「用 `@ensure_csrf_cookie` 让 E1 顺带下发 CSRF cookie」这一设计，
**不新增** `GET /api/mobile/csrf/` 端点。

### 2.4 可见性与权限不变量（照抄既有语义，不得放宽）

引用 `02-OriDesk后端侦察.md` §4，其中两条直接约束本契约：

- **不可见一律 404，不是 403**（`get_visible_ticket`）。订阅端点访问**别人的** subscription
  也必须 404，不能 403 —— 否则「id 是否存在」被探测出来。
- **邮箱凭据 `secret_encrypted` 通知链路完全不接触**（`02` §4.3）。ntfy token 与邮箱凭据
  **是两回事**，不得互用密钥路径以外的任何共享。

可见性三档（`apps/tickets/selectors.py::visible_tickets`）：
`is_superadmin` / `is_group_admin` / `is_admin_group_member` → 全部；否则本组。

---

## 3. 端点契约

### 3.0 统一响应与错误格式

- **成功**：HTTP `200`（读）/ `201`（新建）/ `200`（幂等 upsert 命中已存在），
  body 为资源对象本身（**不套 `{"data": ...}` 信封**，保持简单）。
- **失败**：非 2xx，body 恒为：

```json
{"error": {"code": "unauthorized", "message": "登录状态已失效，请重新登录。"}}
```

- `code` 是**给程序看的**稳定字符串，App 据此分支；`message` 是**给人看的中文**，可直接展示。
- `message` **绝不包含**邮箱凭据、ntfy token、内部路径等敏感信息。

错误码表：

| HTTP | `code` | 含义 | App 应有行为 |
|---|---|---|---|
| 400 | `bad_request` | 请求体不是合法 JSON / 缺字段 | 提示并上报，不重试 |
| 400 | `validation_error` | 字段值非法（如 topic 字符集） | 提示用户 |
| 401 | `unauthorized` | 未登录 / 会话过期 | **清本地态，跳登录页** |
| 403 | `csrf_failed` | CSRF 校验失败 | 重新调 E1 取 cookie 后**重试一次** |
| 404 | `not_found` | 对象不存在**或无权访问** | 从本地列表移除该条 |
| 405 | `method_not_allowed` | 方法不对 | 编程错误，上报 |
| 500 | `server_error` | 服务端异常 | 提示，指数退避重试 |

**【已决 D10】**错误码表以此为准。后续发现缺码是**加**不是**改**，不破坏兼容。

### 3.1 E1 `GET /api/mobile/badge/`

```json
{
  "awaiting": 3,
  "unassigned": 5,
  "mine": 2,
  "all": 41,
  "generated_at": "2026-09-26T16:20:31+08:00"
}
```

**口径复用（重要）**：这四个数字**必须**与收件箱页 chips 上的数字**逐字一致**。
既有实现是 `apps/tickets/views.py::_scope_counts(user)`，其 docstring 明确写着
「口径必须与 `inbox()` 里各 scope 的过滤条件**逐字一致**，否则会出现
"chip 显示 3 条、点进去只有 2 条"这种自相矛盾的界面」。

**【已决 D5】**：把该函数**逐字搬到** `apps/tickets/selectors.py::scope_counts(user)`，
`views._scope_counts` 改为调用 `selectors.scope_counts`（一行转发，行为不变）。
角标端点调用 `selectors.scope_counts`。
**理由**：绝不复制第二套口径——那正是既有代码用注释警告过的 bug。搬移是纯重构，
`inbox()` 的渲染路径与测试都不受影响；另补一个「两处结果一致」的回归测试。

**哪个数字做角标**：App 的启动器角标用 **`awaiting`**。
语义对齐：`pending_for_user()` = `is_awaiting_reply=True` + 排除 closed +
（未认领 **或** 认领人是自己）—— 与「未认领全组可见、认领后只有认领人可见」的
所有权模型（`03-通知与推送设计.md` §1）完全同源。
方案 B（§4.2）下角标由**我们自己的通知**承载，所以这个数字能准确落到我们的图标上。

**轮询策略（契约）**：App **不得**高频轮询。
- 收到推送后拉一次；
- App 回前台时拉一次；
- 后台兜底轮询间隔 **≥ 5 分钟**（配合 WorkManager 的最小周期）。
- 服务端**不加**限流（YAGNI）；若将来发现滥用再加，属于加码不动契约。

### 3.2 E2 `GET /api/mobile/subscriptions/`

```json
{"subscriptions": [
  {"id": 7, "topic": "oridesk-9fK2...Xw", "server": "https://ntfy.example.com",
   "device_label": "Pixel 7 · 小邱", "enabled": true,
   "dnd_start": null, "dnd_end": null,
   "last_seen_at": "2026-09-26T16:19:02+08:00", "created_at": "2026-09-20T09:11:00+08:00"}
]}
```

- 只返回**当前用户自己的**订阅；不含 `user` 字段（就是他自己）。
- **绝不返回 ntfy token**（它是服务端发布凭据，与订阅本身无关）。

### 3.3 E3 `POST /api/mobile/subscriptions/`（幂等 upsert）

请求：

```json
{"device_label": "Pixel 7 · 小邱"}
```

响应 `201`（新建）或 `200`（已存在，刷新 `last_seen_at`）：

```json
{"id": 7, "topic": "oridesk-9fK2...Xw", "server": "https://ntfy.example.com",
 "device_label": "Pixel 7 · 小邱", "enabled": true,
 "last_seen_at": "2026-09-26T16:19:02+08:00", "created_at": "2026-09-20T09:11:00+08:00"}
```

**关键约定：`topic` 与 `server` 由服务端生成/下发，App 不得自带。**

- `topic` = `"oridesk-" + secrets.token_urlsafe(24)` → 32 字符。
  字符集恰为 `[-_A-Za-z0-9]`，**正好落在 ntfy 允许的字符集内**
  （ntfy 规定 topic 只允许 `[-_A-Za-z0-9]`、最长 64 字符，已核官方文档）。
  服务端生成的理由：① 保证随机强度与字符集合规；② 服务端要防重名。
- `server` 来自服务端 `Setting`（见 §6.2）——**App 不填**。App 必须订阅服务端下发的
  这个地址，否则后端发布到 A 而手机订的是 B，链路静默断裂。
- `device_label`：可选，App 传机型 + 用户名便于用户在设置里辨认并吊销；服务端截断到 64 字符。

**幂等键**：`(user, topic)`。App 重试/重装后重复调用不会产生多条记录。
App **应把返回的 `topic`/`server`/`id` 持久化**，重启后不重复注册。

**【已决 D2】**`Subscription` 新模型经人工确认（字段见 **§5.1**）——开发文档 §10.4
「新增模型需人工确认」这道门已过。

### 3.4 E4 `PATCH /api/mobile/subscriptions/<id>/`

```json
{"enabled": false, "dnd_start": "22:00", "dnd_end": "08:00"}
```

- 只允许改 `enabled` / `dnd_start` / `dnd_end` / `device_label`；**`topic` 不可改**
  （topic 是安全边界，改了等于静默换频道，必须走 E5 吊销 + E3 重注册）。
- `dnd_start`/`dnd_end` 为 `"HH:MM"` 24 小时制，或 `null` 表示不启用。
- 非本人的 `<id>` → `404`。

### 3.5 E5 `DELETE /api/mobile/subscriptions/<id>/`

- 成功 → `200` + `{"deleted": true}`；重复删除 → `404`。
- **服务端不做任何 ntfy 侧的动作**（ntfy 无「删除 topic」的授权模型，topic 是发布时
  即时创建的）。吊销的语义是：**从 `Subscription` 表里消失 → 后端不再往它推送**。
  已经躺在 ntfy 缓存里的旧消息无法撤回，这一点必须写进用户文档。
- `user` 被删除时 `Subscription` 随 `on_delete=CASCADE` 一并消失。

**【已决 D11】**接受「吊销后旧消息仍在 ntfy 缓存中」。缓解手段：见 §4.3，改 topic 即换频道。

### 3.6 E6 `POST /api/mobile/subscriptions/<id>/test/`

发一条固定内容的测试推送，返回 `{"sent": true, "mode": "queued"}` 或
`{"sent": false, "mode": "inline", "error": "..."}`。

**存在的理由**（直接服务于 `03-通知与推送设计.md` §7 的落地顺序）：
该文档要求「后端阶段 1 完成后，先在服务器上手动 `curl` 打通 ntfy，再写 App」。
有了这个端点，「打通链路」从手工 curl 变成 App 里一个按钮，**把「推送不通」与
「App 有问题」彻底分开**。测试推送**不写 `NotificationLog`**（避免污染真实统计）。

**【已决 D6】**加这个端点。它严格说超出「角标 + 订阅」的字面范围，但用途明确（见上）。

---

## 4. ntfy 推送约定

### 4.1 发布（后端 → ntfy）：HTTP 契约

已核 ntfy 官方文档（`docs.ntfy.sh/publish/`）：发布是 `POST/PUT /<topic>`，
元数据走 header，正文是消息体。

```
POST {ntfy_server}/{topic}          HTTP/1.1
Authorization: Bearer {ntfy_token}   ← 仅当配置了 token（见 §6.2）
Title: T#123 有新来信
Priority: default
Tags: oridesk
Click: https://{外部域名}/tickets/123/
Content-Type: text/plain; charset=utf-8

<工单主题，截断至 120 字>
```

- 实现用 `urllib.request`，**必须设超时**（建议 5s），**必须包 try/except**——
  推送失败绝不能影响收信流水线（`02` §2.1 的防御风格）。
- `Title`/`Tags`/`Click` 承载中文时，ntfy 文档提示部分库对 UTF-8 header 支持不佳，
  可用 RFC 2047 编码。**App/后端联调时必须实测中文标题**，见 §8。
- **【已决 D3】**`Click` 需要**绝对 URL**（见 §4.5），因此新增设置项 `mobile_public_base_url`。

### 4.2 订阅（App ← ntfy）—— **已决：方案 B（App 自己收）**

> **决策（2026-09-26，人工确认）**：采用**方案 B** —— 在自己的 App 内实现 ntfy 订阅，
> 自己弹通知、自己管角标。**ntfy 保留**（自托管，Apache-2.0，免费，见 §4.2.3）。

ntfy 提供三种订阅方式（已核 `docs.ntfy.sh/subscribe/api/`）：
`/<topic>/json`（ndjson 流，**官方推荐，我们采用**）、`/<topic>/sse`、`/<topic>/ws`。

**落地要点（阶段 3）**：

- 连接 `GET {ntfy_server}/{topic}/json` 按行读 ndjson；只处理 `event == "message"`，
  忽略 `open` / `keepalive`。
- ⚠️ **重连必须带 `since=<上一条消息 id>`**，否则重连后收不到期间错过的推送。
  这正是选 ntfy 而非裸 WebSocket 的主要收益（§4.2.2 ③），**必须实现**，
  不能只做「连上就收新的」。
- 前台服务 + 心跳 + 指数退避重连 + 开机自启，全部由我们实现。
- 用 `NotificationManager` + 通知渠道；点击深链进 WebView 对应工单（§4.5）。
- **启动器角标由我们自己发的通知承载**，数字准确可控——这正是选 B 的首要原因。

#### 4.2.1 为什么没选 A（依赖官方 ntfy App）

| | 方案 A：官方 ntfy Android App | **方案 B：App 自己收（已选）** |
|---|---|---|
| 要装几个 APK | **2 个**（我们的 + 官方 ntfy） | **1 个** |
| 我们要写的代码 | 几乎为零 | 前台服务、心跳、重连、开机自启、通知渠道 |
| 国内可用性 | 官方 APK 需从 GitHub Releases 手动下载（国内可达性未验证） | 只需我们的 APK |
| **我们 App 图标的角标** | ❌ **做不到**——别的 App 发的通知只给**它的**图标加角标 | ✅ 完全可控 |

**决定性因素是角标。** 决策 3 明确要「角标」，而 Android 的通知角标是**按 App 图标**的：
通知由 ntfy App 发出，角标就只长在 ntfy 的图标上，我们的 App 图标永远是空的。
「单 APK」是第二位收益。

#### 4.2.2 与 `01-决策记录.md` §3「否决自建 WebSocket + 前台服务」的关系（**必须记录**）

`01` §3 曾把「自建 WebSocket + 前台服务」降级为「仅作 App 内实时，不作离线通道」，
理由是「耗电、被国产 ROM 杀后台」。**方案 B 把前台服务重新用作了离线通道**，
因此这是对该条的一次**有依据的翻案，不是疏漏**。依据：

1. **天花板本来就在那里，绕不开。** 国内无 GMS 环境下任何推送方案都要靠常驻后台进程；
   唯一真正改变天花板的是厂商推送 SDK（系统级长连接），而它已被决策表**后置**
   （5 个 SDK + 5 个开发者账号，成本不对等）。既然绕不开，「不用前台服务」就不是
   可选项，只是把代价藏起来。
2. **A 并不比 B 好，只是把代价外包。** 官方 ntfy App 的「即时投递」同样是前台服务 +
   WebSocket，照样被 ROM 杀。而 A 连角标都换不来。
3. **ntfy 比当初被否决的「自建 WebSocket」多了两样实质东西**：
   ① **服务端**不必自建——决策 4 冻结零新增依赖，自建 WebSocket 需 ASGI + Channels
   （全是新依赖）；退一步用 SSE/长轮询则每个手机占住一个 gunicorn worker，小部署直接被打死。
   ② **消息缓存与断线补齐**——ntfy 默认缓存 12h，客户端可用 `since=` 拉回错过的消息；
   自建 WebSocket 没有离线队列，得自己写。**在「进程被杀是常态」的环境里，
   「能补齐」比「够实时」重要。**
4. **代价如实记录**：耗电；需引导用户把 App 加进电池优化白名单；国产 ROM 下延迟仍不可控。
   这是明知的取舍。

#### 4.2.3 成本（已核）

**ntfy 免费，自托管没有付费墙。**

- 服务端 `binwiederhier/ntfy` 与 Android App 均为 **Apache-2.0**。
- 文档里的 "ntfy Pro" / Tiers 是**默认不存在**的机制（原文 "By default, there are no
  pre-defined tiers"），用途是让**你**向自己的用户收费（需自行接入 Stripe）——**不是功能付费墙**。
- 唯一被 Pro 限制的功能是**附件保存时长**（默认 3h），而我们的推送**不含附件**（§4.3）。
- 默认限流不影响正常使用（原文 "During normal usage, you shouldn't encounter these limits"）。
- ntfy.sh 公共服务器有免费额度，但决策 2 已定自建，**不使用它**。
- 真实成本：一个容器（Go 单二进制 + 默认 SQLite）+ 一个子域与 TLS 证书（反代，
  Let's Encrypt 免费）+ **方案 B 的客户端工程量**。
- ⚠️ 配置坑：置于反代之后**必须设 `behind-proxy`**，否则所有访客被当成同一 IP 共用一个限流桶。

### 4.3 topic 安全

- topic **本质就是密码**（ntfy 无注册，谁猜到谁收）：每用户每设备一个，
  长度 32 字符随机（§3.3），**不可枚举**。
- ntfy 实例若对外开放，**必须开鉴权**；token 见 §6.2，存 `EncryptedSharedPreferences`
  （App 侧）/ Fernet 密文（服务端侧）。
- **访问令牌对「发布」与「订阅」都有效**（已核官方文档：authenticate "when you publish
  **or subscribe** to topics"，`Authorization: Bearer tk_...`）。
  因此 **App 的订阅长连接也必须带这个 token**——不是只有后端发布才需要。
- `behind-proxy` 未设时，反代后所有手机共用一个限流桶（§4.2.3），运维需注意。
- **推送内容只放工单号与主题，绝不放邮件正文**（决策+隐私：邮件正文经服务器转发，
  即使是自建也不外泄）。点击后回 App 看全文。

### 4.4 载荷与文案（冻结）

| 事件 | `Title` | 正文 |
|---|---|---|
| 新工单 | `T#123 新工单` | 工单主题（截断 120 字） |
| 客户新来信 | `T#123 有新来信` | 工单主题 |
| 改派到本组 | `T#123 已改派到本组` | 工单主题 |
| 内部备注 @我 | `T#123 有人提到了你` | 工单主题 |

公文案细节见 `03-通知与推送设计.md` §1；受众规则、聚合去重、@解析规则**沿用该文档 §1–§3**
（那是已确认结论），本契约只补一条实现约束：

- 聚合窗口（默认 60s）内同工单多事件合并为一条，正文改成 `T#123 收到 3 封新来信`。
- 聚合**必须异步**（走 `apps/mailboxes/tasks.py::dispatch`），不阻塞收信；
  Redis 不可用时 `dispatch` 自动同步降级，这是既有机制，直接复用。
- 遵循 `03` §1「三条全局规则」：不推发起人自己 / 同一人不重复推 / `status="closed"` 不推。

### 4.5 `Click` 深链

- 后端产出**绝对 URL**：`https://{外部地址}/tickets/{id}/`。
  「外部地址」来自新增设置项 `mobile_public_base_url`（§6.2）。
- 为什么需要它：`Click` 必须是绝对 URL；而本仓库**公开**，
  决策 10 要求**不硬编码服务器地址**，所以只能走设置项。
- 该设置为空时：**省略 `Click` 头**，通知只作提示，点开进 App 首页。不报错。
- App 侧（**【已决 D3】**）：方案 B 下 `Click` 由**我们自己**处理，所以**注册自定义 scheme**，
  优先用 `oridesk://ticket/{id}` 直接唤起 App 并跳进 WebView 到该工单，**不绕浏览器**
  （绕浏览器会让用户再登录一次，或依赖浏览器里另一套会话）。
  `mobile_public_base_url` 仍必须配：`Click` 头需要一个合法绝对 URL 作为兜底，
  且 scheme 唤起失败时系统会退回到它。

---

## 5. 新增数据模型（需人工确认，开发文档 §10.4）

### 5.1 `Subscription`（`apps/notifications/models.py`）

按 `03-通知与推送设计.md` §6 草案，补全到可实现的精度：

| 字段 | 类型 | 说明 |
|---|---|---|
| `user` | `FK(User, on_delete=CASCADE, related_name="push_subscriptions")` | 归属；用户删除则订阅消失 |
| `topic` | `CharField(max_length=64, unique=True)` | 服务端生成的随机 topic；**全局唯一**（防串频道） |
| `server` | `CharField(max_length=255)` | 冗余记录发布时的 ntfy 地址，便于排查「换域名后旧订阅失效」 |
| `device_label` | `CharField(max_length=64, blank=True)` | 用户可辨认并吊销 |
| `enabled` | `BooleanField(default=True)` | 关掉后后端不推 |
| `dnd_start` / `dnd_end` | `TimeField(null=True, blank=True)` | 免打扰时段；两端都非空才生效 |
| `last_seen_at` | `DateTimeField(null=True, blank=True)` | E3 刷新；用于「这台设备还活着吗」 |
| `created_at` | `DateTimeField(auto_now_add=True)` | |

- `Meta.constraints`：`UniqueConstraint(fields=["user", "topic"])`（配合 topic 全局唯一
  是双保险；主幂等键是 `(user, topic)`）。
- `db_table = "push_subscriptions"`（与既有 `user_groups` / `settings` 命名风格一致）。
- **不存 `secret_encrypted` 之外的东西**：本表**不含**任何 ntfy token 字段，
  token 是**实例级**配置（§6.2），不是每设备一份。

### 5.2 迁移

**必须提交迁移文件**。`02-OriDesk后端侦察.md` §5：CI 关卡含
`makemigrations --check --dry-run`，不交迁移 CI 直接红。

---

## 6. 新增设置项（`apps/audit/models.py::Setting.DEFAULTS`）

### 6.1 新增键

| key | 默认值 | 说明 |
|---|---|---|
| `ntfy_enabled` | `"false"` | 总开关。默认关——**未配置就不该发**，避免装完就报错 |
| `ntfy_server_url` | `""` | 如 `https://ntfy.example.com`；空=禁用 |
| `ntfy_token` | `""` | **Fernet 密文**（见 D4），空=实例不鉴权 |
| `ntfy_topic_prefix` | `"oridesk"` | topic 前缀，便于在 ntfy 侧辨认 |
| `notify_aggregate_seconds` | `"60"` | 聚合窗口，与 IMAP 轮询间隔同量级 |
| `mobile_public_base_url` | `""` | `Click` 用的外部地址（§4.5）；空=省略 Click |

**注意缓存陷阱**（`02` §6）：`Setting` 有 30s LocMemCache，**不得用 `QuerySet.update()` 改值**
（不触发 `save()`、缓存不失效）。一律走 `Setting.set()` / `bulk_set()`。
测试里 `conftest.py` 有 autouse `cache.clear()`，新增依赖 Setting 的功能要注意这点。

### 6.2 ntfy token 的存储位置

**【已决 D4】存 Fernet 密文，不放 `Setting.value` 明文。**

- `03-通知与推送设计.md` §5 原话是「配置项进 `Setting.DEFAULTS`：ntfy server URL /
  可选 token / 是否启用 / 聚合窗口秒数」。按字面实现，token 会**明文落库**。
- 但本项目的安全姿态是「凭据只以 Fernet 密文存储」`02` §4.3。
  邮箱凭据这么做，ntfy token（具备实例发布权）**没有理由降级**。
- 已有可复用件：`apps/core/crypto.py`（`cryptography` 已是依赖，零新增）。
- 落地：`ntfy_token` 的 value 存 `encrypt_secret(...)` 的密文；读取时解密。
  界面上永不回显明文（照抄邮箱凭据的密码框做法）。

---

## 7. 部署与运维约束

### 7.1 `ALLOWED_HOSTS` 与 `CSRF_TRUSTED_ORIGINS`（**容易漏**）

- 默认 `ALLOWED_HOSTS = localhost,127.0.0.1`，**绝不允许 `"*"`**。
  用户运行时填的服务器地址（决策 10）**必须**同时进 `DJANGO_ALLOWED_HOSTS`，
  否则手机请求会被 Django 以 `400 DisallowedHost` 拒掉 —— 而 App 侧只会看到
  「请求失败」，极难自查。**部署指南必须显著写明这一条。**
- 若走反代 + HTTPS，`DJANGO_CSRF_TRUSTED_ORIGINS` 需含 `https://{外部地址}`。
  （既有 `CSRF_TRUSTED_ORIGINS = env_list("DJANGO_CSRF_TRUSTED_ORIGINS")`。）

### 7.2 ntfy 服务

作为新服务加进 `docker-compose.yml`（决策 2），含持久化卷。
现有 compose 已有 `web` / `worker` / `scheduler` / `db` / `redis` 与
`db_data` / `redis_data` 两个卷，新增服务与 `ntfy_data` 卷沿用同一风格。

### 7.3 强制 HTTPS

`SESSION_COOKIE_SECURE = True` / `CSRF_COOKIE_SECURE = True`（生产分支）意味着
**明文 HTTP 下 cookie 根本不会下发**，App 会表现为「永远登录不上」。
App 侧必须在用户填地址时即拒绝 `http://`（仅允许 `https://` 或明确提示风险）。
`03` §4 亦要求「传输强制 HTTPS」。

---

## 8. 验证边界（阶段 0 阶段就必须讲清）

沿用 `04-CICD与验证边界.md` §6 的诚实边界，具体到本契约：

### ✅ 能被自动验证（pytest）

- 四个端点的状态码、响应结构、错误码契约（含 **401 而非 302** 这条）
- 订阅 upsert 幂等性、非本人 id 返回 404、`topic` 字符集与长度合法
- `scope_counts` 与 `inbox()` 口径一致（D5 采纳后的回归测试）
- ntfy 发布**请求构造**（打桩 `urllib`，断言 URL / header / body 正确）
- @提及解析（`03` §3 要求的 **`a@b.com` 不被误判**用例）
- 通知失败不影响收信（钩子抛异常时 `process_inbound` 仍返回 `processed`）

### ❌ 无法被自动验证（必须人工/真机）

- **ntfy 实际送达手机**（需真实服务端 + 手机）
- 中文 `Title` 经 ntfy 的**实际显示效果**（§4.1 的 RFC 2047 隐患只有实测能确认）
- 国产 ROM 后台存活与通知延迟（方案 B 下由**我们的前台服务**承担，这是本方案最大的
  **已知不可控项**；只能靠引导用户加电池白名单缓解，§4.2.2 ④）
- ntfy `since=` 断线补齐的**真实效果**（消息缓存默认 12h，但被杀死过久仍会丢）
- 通知点击深链的实际跳转
- APK 真机运行、升级覆盖安装

---

## 9. 决策汇总（D1–D11，**全部已决**）

> 2026-09-26 人工确认。**D1–D11 均已采纳**，契约据此冻结（v1.0）。
> 本表是溯源记录：正文中任何【已决 Dx】标记都可回到这里看当时的取舍。

| # | 决策 | 决议 | 影响 / 备注 |
|---|---|---|---|
| **D1** | 推送接收方：A 官方 ntfy App / B App 自己收 | ✅ **方案 B**（§4.2） | ntfy 保留；前台服务重新用作离线通道，翻案依据见 §4.2.2 |
| D2 | 新增 `Subscription` 模型（§5.1 字段表） | ✅ 采纳 | 开发文档 §10.4「新增模型需人工确认」已过 |
| D3 | `Click` 绝对 URL + `mobile_public_base_url`；`oridesk://` scheme | ✅ 都加 | 不加设置项则通知无法直达工单页；scheme 直接唤起 App，不绕浏览器 |
| D4 | ntfy token 存 Fernet 密文而非 `Setting` 明文 | ✅ **存密文** | ⚠️ 与 `03` §5 字面表述有意偏离，理由见 §6.2 |
| D5 | 把 `_scope_counts` 上移为 `selectors.scope_counts` 供角标复用 | ✅ 采纳 | 避免第二套计数口径（既有代码注释警告过的 bug） |
| D6 | 加 E6「测试推送」端点 | ✅ 加 | 超出「角标+订阅」字面范围；但直接服务 `03` §7 的验证顺序 |
| D7 | 路径前缀 `/api/mobile/` | ✅ 采纳 | 改名成本低，尽早定 |
| D8 | `api_login_required` 放 `core/permissions.py` | ✅ 放 core | 与既有权限装饰器同处 |
| D9 | E1 用 `@ensure_csrf_cookie` 顺带下发 CSRF cookie | ✅ 采纳 | 省一个端点 |
| D10 | 错误码表够用 | ✅ 够用 | 后续只加不改 |
| D11 | 吊销订阅后 ntfy 缓存中的旧消息不可撤回 | ✅ 接受 | 缓解：吊销后再注册会换新 topic |

### 两处必须修正的上游文档（待办，尚未执行）

1. `04-CICD与验证边界.md` §2.4 写「Python 3.12.14（`.venv`）」——事实无误，但该 `.venv`
   位于**后端仓库** `/data/dsh/home/OriDesk/.venv`，**不在** `OriDesk-Android` 工作区
   （本工作区连 `python3` 都没有）。另外 `requirements.txt` 中**没有** `requests`/`httpx`，
   这是 §1.4「零新增依赖」的直接依据。
2. `03-通知与推送设计.md` §5 把 ntfy token 列入 `Setting.DEFAULTS`，与 **D4** 的决议
   （存 Fernet 密文）不一致，需按 D4 更正措辞。

---

## 附录 A：本契约与既有文档的关系

| 文档 | 关系 |
|---|---|
| `01-决策记录.md` | 上位。本契约不得与之冲突；若冲突（见 D1）需先改 01 再改本文 |
| `02-OriDesk后端侦察.md` | 事实来源。§2/§3/§4/§6 被本文多处直接引用 |
| `03-通知与推送设计.md` | §1–§3（事件/受众、聚合、@解析）**原样冻结**进本契约；§5/§6（设置项、Subscription）在此细化为 §5/§6 |
| `04-CICD与验证边界.md` | §6 验证边界被 §8 细化；§2.4 有一处需更正（见 §9 末） |

**下游**：阶段 1（后端 `apps/notifications/`）按 §1.2 / §4 / §5 / §6 实现；
阶段 2 按 §3 实现端点；阶段 3 的 App 按 §2 / §3.1 / §4.2 实现客户端。
