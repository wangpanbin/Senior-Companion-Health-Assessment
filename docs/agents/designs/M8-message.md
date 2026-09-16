# M8 设计评审 · 站内信与通知

- **模块**：M8（旁路径 D）
- **评审时间**：2026-09-16（依落地代码反向补齐）
- **关联**：`docs/api/07-message.md`、`PLAN_BACKEND.md §8 M8`、`PLAN_BACKEND.md §3 实时推送架构`
- **代码**：`controller/message/MessageController`、`controller/message/MessageSseController`、`service/impl/MessageServiceImpl`、`websocket/MessageSseHub`、`util/MessageTemplateUtil`

---

## 1 · 接口表

| # | 方法 | 路径 | 角色 | 入参 | 出参 |
|---|---|---|---|---|---|
| 1 | GET | `/api/message` | 登录用户 | `MessageQuery` | `PageResult<MessageVO>` |
| 2 | GET | `/api/message/unread-count` | 登录用户 | — | `UnreadCountVO` |
| 3 | PUT | `/api/message/{id}/read` | 登录用户 | path `id` | `MessageReadResultVO` |
| 4 | PUT | `/api/message/read-all` | 登录用户 | `MessageReadAllDTO` | `MessageReadResultVO` |
| 5 | DELETE | `/api/message/{id}` | 登录用户 | path `id` | `Void` |
| 6 | GET | `/sse/message` | 登录用户 | Header `Authorization` | SSE 流（`text/event-stream`） |

> 五个 REST 接口都**不加 `@PreAuthorize`**：任何登录角色都有自己的收件箱。
> 「只能看自己的消息」由 Service 层 `receiver_id = 当前用户` 强制约束（7002 `MESSAGE_NO_PERMISSION`）。
> SSE 端点鉴权直接走 `Authorization: Bearer`（`EventSource` 无法自定义头 → 由前端用 fetch+stream 或 token 方案，见文档）。

---

## 2 · 数据流

```
【写入】任何业务事件
  OrderServiceImpl / MedicationServiceImpl / AdminServiceImpl / ...
    → MessageService.send(userId, template, params)
         ├─ MessageTemplateUtil 渲染文案（订单事件 / 审核结果 / 漏服提醒 / 系统公告）
         ├─ INSERT internal_message（is_read=0）
         └─ MessageSseHub.push(userId, NEW_MESSAGE, payload)   ← 实时推送

【读取】进页面 / 顶栏
  GET /api/message            → 分页列表（归属过滤）
  GET /api/message/unread-count → 顶栏红点数字

【实时】MessageSseHub（按 userId 分组）
  subscribe(userId)  → 建立 SseEmitter（超时 30min）
  push(userId, event, data) → 逐条写；写失败【只摘引用，绝不 complete()】

表 → Mapper → Service → Controller/Hub：
  internal_message ─→ InternalMessageMapper / MessageReadMapper
                          ↓
                    MessageServiceImpl ─→ MessageController（REST）
                          ↓
                    MessageSseHub ────→ /sse/message（SSE）
```

`internal_message` 由 `MessageService.send/sendBatch` 统一入口写入，各模块不直接操作 Mapper。

---

## 3 · 状态机（已读 / 未读）

```
UNREAD（is_read=0）──markRead / markAllRead──→ READ（is_read=1）
        └──delete──→ 逻辑删除（deleted=1）
```

| 非法情况 | 错误码 |
|---|---|
| 消息不存在 | `7001 MESSAGE_NOT_FOUND` |
| 查看他人消息 | `7002 MESSAGE_NO_PERMISSION` |

---

## 4 · 关键设计：SSE 断开不刷假 ERROR（本模块最重要的一处）

三个动作必须联动，缺一就会出现「用户关页面 → 服务端刷两条假 ERROR」：

1. `MessageSseHub.push` 写失败时**只摘引用，绝不调用 `complete()`**
   —— 对已写不通的响应调 `complete()` 会在 flush 阶段抛 `AsyncRequestNotUsableException`，
   容器随后对 `/sse/message` 再走一轮**错误分发**。
2. `SecurityConfig` 放行 `DispatcherType.ASYNC / ERROR` 分发
   —— 否则错误分发时 `SecurityContext` 已不在，`AuthorizationFilter` 抛 `AccessDenied`，再补一条 ERROR。
3. `GlobalExceptionHandler` 按 `response.isCommitted()` 区分
   —— 已提交说明是「客户端断开」（记 DEBUG），未提交才是真 IO 故障（记 ERROR 并返回统一响应体）。

三者关系：**① 减少触发源，② 让错误分发不再二次鉴权失败，③ 让残留的 IO 异常不被误报**。

---

## 5 · 验收清单

| # | 验收项 | 状态 |
|---|---|---|
| 1 | list / detail / read / read-all / unread-count 接口 | ✅（detail 并入 list，独立端点未单独开） |
| 2 | SSE 推送 `/sse/message` | ✅ `MessageSseHub` + `MessageSseController` |
| 3 | 消息模板（订单事件 / 审核 / 漏服 / 系统公告） | ✅ `MessageTemplateUtil` + `MessageType` 枚举 |
| 4 | 统一发消息入口 `MessageService.send/sendBatch` | ✅ 各业务模块复用 |
| 5 | SSE 断开不刷假 ERROR | ✅ 三处联动修复（见 §4） |
| 6 | 单测 / 越权 / e2e | ⚠️ 仅 `MessageSseHubTest`（Hub 层）/ ❌ `MessageServiceTest` 缺 / ❌ `MessageAccessMatrixTest` 缺 / ✅ `e2e_message.py` |

---

## 6 · 遗留与风险

- `MessageServiceTest`（Service 层）与 `MessageAccessMatrixTest` 缺 —— 现有 `MessageSseHubTest` 只覆盖 Hub，不覆盖 `send/sendBatch` 的归属与模板逻辑。
- e2e 残留：`internal_message` 907 行 vs 种子 72，需 M12 统一清理策略。
