# 02 · OriDesk 后端侦察

> 目的：让新会话不必重读 11.6k 行代码就能动手。以下均为**已核对源码**的事实。

## 1. 现状体检

| 项 | 事实 |
|---|---|
| 技术栈 | Django 5.2.17 / Python 3.12 / 模板 + HTMX + Quill / MariaDB 11（本地 SQLite）/ RQ + Redis / APScheduler |
| 代码规模 | `apps/` + `config/` 约 11,645 行 Python；31 个模板；6 个自有静态文件 |
| 认证 | Django session + CSRF；视图层装饰器鉴权 |
| **REST API** | **无**。全站唯一 JSON 端点是 `/healthz` |
| **推送基础** | **无**。没有 channels / WebSocket / VAPID / Notification 模型 |
| 实时性来源 | APScheduler 每 `imap_poll_interval_seconds`（默认 60s）轮询 IMAP |
| 移动端现状 | **网页已做窄屏适配**（移动顶栏 + 底部 Tab + 抽屉 + 卡片流 + 底部弹层） |

`config/urls.py`：`/admin/`、`/accounts/`、`/routing/`、`/autoresponder/`、`/audit/`、`/`（tickets）、`/healthz`。

## 2. 关键复用点（写通知与 API 都用这些，不要重写业务逻辑）

### 2.1 收信流水线 —— 通知的天然钩子

```python
# apps/mailboxes/pipeline.py
@dataclass
class InboundResult:
    status: str          # processed / skipped_loop / skipped_duplicate
    ticket: Ticket | None = None
    message: Message | None = None
    created: bool = False        # ← 新工单
    auto_replied: bool = False
    rule_actions: list[str] = field(default_factory=list)
    reason: str = ""
    @property
    def processed(self) -> bool: return self.status == "processed"

def process_inbound(mailbox: Mailbox, uid: int, raw_bytes: bytes, now=None) -> InboundResult
```

钩子挂在 `process_inbound` 的 `return InboundResult(...)` **之前**（源码约第 139 行），
且必须**包在 try/except 里**——通知失败绝不能影响收信（现有代码在 `apply_rule_actions`、
自动回复处已是这个防御风格，照抄）。

### 2.2 工单动作（`apps/tickets/services.py`）

```python
add_note(ticket, user, body_text: str, body_html: str = "") -> Message
claim_ticket(ticket, user) -> Ticket          # 幂等：已是本人则直接返回
unclaim_ticket(ticket, user) -> Ticket        # 非本人且非 sees_all_tickets → PermissionError
set_status(ticket, status: str, user=None) -> Ticket   # 关闭时自动清 is_awaiting_reply
touch_last_message(ticket, when=None) -> Ticket
add_tag(ticket, tag_or_name, user=None, source="manual", *, record_audit=True)
remove_tag(ticket, tag, user=None, *, record_audit=True) -> bool
timeline(ticket)
tags_available_for(group) / resolve_tag(...) / find_tag(...) / ticket_tag_names(ticket)
```

### 2.3 改派（`apps/routing/services.py`）

```python
reassign_ticket(ticket, group, user, reason="")   # ← 组级改派，不是指派给个人
```
失败抛 `ValueError`。成功后原组不再可见。

### 2.4 发信（`apps/mailboxes/services.py`）

```python
send_reply(ticket, user, body_text, body_html="", attachments=None, cc=None, now=None) -> Message
identity_mailbox_for(ticket)          # 组邮箱优先，无邮箱组走全局兜底
MailDeliveryError                     # 发送失败
max_upload_size_bytes() / mail_enabled()
reply_subject(ticket)
```

`send_reply` 内部会：以组身份发信 → 落 `Message`（`actual_sender` 记内部真实发件人）
→ 落附件 → `is_awaiting_reply=False` + 刷新 `last_message_at` → 写审计。
**回复端点应直接调它，不要自己拼 MIME。**

### 2.5 可见性与权限

```python
# apps/tickets/selectors.py
visible_tickets(user)          # 超管/组管理员/管理员组成员 → 全部；普通用户 → 本组
visible_ticket_or_none(user, ticket_id)
pending_for_user(user)         # 待回复队列（排除已关闭；认领后只有认领人可见）
claimable_for_user(user)
can_manage_routing(user)

# apps/core/permissions.py
login_required / superadmin_required / routing_manager_required / mailbox_admin_required
get_visible_ticket(user, pk) -> Ticket    # 不可见抛 404（不泄露存在性）
```

**可见性三档**（`User` 派生属性）：`is_superadmin` / `is_group_admin`（任一组内管理员）/
`is_admin_group_member`（属任一管理员组）→ 任一为真则 `sees_all_tickets = True`。

### 2.6 配置（`apps/audit/models.py::Setting`）

```python
DEFAULTS = {
  "sticky_window_days": "7", "first_contact_window_hours": "24",
  "fallback_group_id": "", "fallback_mailbox_id": "",
  "max_attachment_size_mb": "25", "imap_poll_interval_seconds": "60",
}
Setting.get(key, default) / get_int / get_bool / get_optional_int
Setting.set(key, value, user=...)      # 写审计
Setting.bulk_set(values, user=...)     # 逐项失效缓存
```

## 3. 数据模型要点（通知受众解析会用到）

| 模型 | 关键字段 |
|---|---|
| `User` | 继承 `AbstractUser` + `is_superadmin`；派生 `group_ids` / `is_group_admin` / `is_admin_group_member` / `sees_all_tickets` / `display_name` |
| `Group` | `name`、`mailbox`(OneToOne 可空)、`is_admin_group`；派生 `identity_email` / `identity_mailbox` |
| `UserGroup` | `user`、`group`、`is_admin`；唯一约束 (user, group) |
| `Mailbox` | `email`、IMAP/SMTP 配置、`secret_encrypted`(Fernet)、`last_uid` / `uidvalidity`、`is_fallback`(全局唯一)、`is_active` |
| `Ticket` | `mailbox`、`group`、`assignee`(可空, SET_NULL)、`subject`、`normalized_subject`、`status`(open/pending/closed)、`is_awaiting_reply`、`customer_email`、`last_message_at`；派生 `last_message_id` / `references_header` / `identity_mailbox` / `pending_label` |
| `Message` | `direction`(in/out)、`type`(message/note)、`from_addr` / `to_addr` / `cc_addr`、`body_text` / `body_html`、`actual_sender`、`is_auto_reply`、`sent_at`；派生 `is_note` / `preview` |
| `Attachment` | `filename`、`mime`、`size`、`path`；派生 `normalized_filename` / `extension` / `is_dangerous` / `effective_mime` / `is_previewable` / `size_display` |
| `Tag` / `TicketTag` | 标签字典（`group` 为空=全局）+ 关联（`added_by`、`source` rule/manual）；单工单上限 `MAX_TAGS_PER_TICKET=20` |
| `AuditLog` | `user`、`action`(reply/forward/claim/unclaim/config_change/login)、`ticket`、`group`、`identity_email`、`detail`(JSON) |
| `Setting` | 键值配置，带 30s 缓存 |

**`assignee` 是 `on_delete=SET_NULL`** —— 用户被删除后工单自动回到"未认领"，
通知受众自动退回全组广播。这点对通知规则很重要。

## 4. 安全不变量（绝对不能破坏）

1. **附件分级**：`DANGEROUS_EXTENSIONS` 只可下载不可预览；预览只允许
   `PREVIEWABLE_MIME_TYPES` 白名单（PNG/JPEG/GIF/WebP/BMP/txt/PDF）。
   HTML/SVG/XHTML/XML 一律只能下载。预览响应带 `nosniff` + 严格 CSP。
   `normalized_filename` 会剥离尾部空格与点，防 `tool.exe.` 绕过——**不要绕过这个属性**。
2. **HTML 净化**：用户富文本入库前必须 `apps.mailboxes.sanitizer.sanitize_html`；
   纯文本用 `apps.core.utils.html_to_text`。
3. **邮箱凭据**：只以 Fernet 密文存储（`secret_encrypted`），页面与日志中永不出现明文。
   **通知链路绝不能接触它。**
4. **可见性先于一切**：工单相关视图必须先做可见性校验，不可见一律 404（不是 403，避免泄露存在性）。
5. **写操作**：`@require_POST` + CSRF。
6. **所有视图都要有权限装饰器**（安全清单 §10.3 硬要求）。

## 5. 测试与 CI 门槛

```ini
# pytest.ini
DJANGO_SETTINGS_MODULE = config.settings
testpaths = apps tests
addopts = -q --tb=short
```

- `conftest.py` 有 **autouse fixture 在每个用例前后 `cache.clear()`** —— 因为 `Setting` 用
  LocMemCache，而 Django 测试只回滚数据库不清缓存，不清会跨用例串味。
  **新增依赖 Setting 的功能要注意这点。**
- 测试文件既有 `apps/*/tests/` 也有顶层 `tests/`，命名 `test_*.py`。
- CI（`.github/workflows/ci.yml`）触发于**所有分支的 push 与 PR**，作业：`unit`(SQLite) 与 `mariadb`。
- 关卡：`manage.py check`、`check --deploy`、`makemigrations --check --dry-run`、`pytest --cov=apps`。

**新增模型必须提交迁移文件**，否则 `makemigrations --check` 会让 CI 红。

## 6. 已知陷阱

| 陷阱 | 说明 |
|---|---|
| 改派是组级 | 见 `01-决策记录.md` §4① |
| 无 @ 功能 | 见 `01-决策记录.md` §4② |
| `Setting` 缓存 30s | 不要用 `QuerySet.update()` 改配置值——不触发 `save()`、缓存不失效，最多 30s 读到旧值 |
| `add_note` 不做净化 | 净化在**视图层**做（`views.create_note` 里 `html_to_text(sanitize_html(...))`）。新调用方必须自己做 |
| 收件箱计数口径 | `_scope_counts()` 刻意重复了 `inbox()` 的过滤条件（注释说明是为避免"chip 显示 3 条点进去 2 条"）。改一边必须同步改另一边 |
| 列表页 N+1 | 用 `select_related` + `prefetch_related("ticket_tags__tag__group")`；通知受众解析也要注意 |
| 邮件循环防护 | `is_loop_mail()` 会丢弃自动回复/退信等；通知派发不要在丢弃路径上做 |
