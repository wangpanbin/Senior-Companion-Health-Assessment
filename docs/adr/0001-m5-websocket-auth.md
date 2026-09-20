# ADR 0001 · M5 WebSocket 鉴权方案

- **状态**：已接受（ACCEPTED）
- **日期**：2026-09-16
- **决策者**：B + C（前端侧确认浏览器 WebSocket 限制）
- **关联模块**：M5 陪诊执行、打卡与实时进度
- **关联文档**：`docs/api/04-companion-execution.md`、`backend/src/main/java/org/company/nianglin/websocket/JwtHandshakeInterceptor.java`、`OrderProgressHandler.java`、`OrderProgressHub.java`

---

## 背景

M5 陪诊执行需要双向实时通道：

- **上行**：陪诊员打卡（HTTP POST `/api/execution/{orderId}/checkin`，非 WebSocket）
- **下行**：家属端「陪诊进度」页 3 秒内看到时间线变化

下行通道选了 WebSocket（vs SSE）的原因是：后续可扩展家属 → 陪诊员的"对讲"能力（M12+），SSE 是单向的、WebSocket 双向更通用。

现有 M2 鉴权链路：HTTP `Authorization: Bearer <accessToken>` → `JwtAuthenticationFilter` → `SecurityContextHolder`。
WebSocket 握手（HTTP Upgrade）阶段如何复用这条链路？

---

## 候选方案

### 方案 1：STOMP CONNECT 帧 Authorization header（**未采用**）

- 客户端连 `/ws/order-progress` 后发 `STOMP CONNECT` 帧，header 携带 `Authorization: Bearer xxx`
- 后端用 `ChannelInterceptor` 在 STOMP 层校验 token，写 `SecurityContext` 到握手 session
- **未采用理由**：项目推送需求是"一单一组、组内广播"，STOMP 引入 `SimpMessagingTemplate` / Broker 配置 / 订阅目的地约定，换来的能力（复杂路由、多种消息语义）一个都用不上。注释见 `OrderProgressHub` 类 javadoc。

### 方案 2：WebSocket 握手 URL 带 token（**采用**）

- `/ws/progress?token=xxx&orderId=1001`
- 服务端 `HandshakeInterceptor.beforeHandshake()` 校验 token + 订单归属
- 鉴权失败直接 `response.setStatusCode(401/403)` + `return false`，连接根本不建立

### 方案 3：先连上再发认证帧（**未采用**）

- 连上后客户端另发一帧带 token，服务端校验后再激活订阅
- **未采用理由**：服务端必须先接受一个"未认证"的连接再判断身份，这段中间态就是漏洞面。握手阶段直接拒掉更安全。

---

## 决策

**采用方案 2**：原生 Spring WebSocket + 自定义 `HandshakeInterceptor`，token 走 URL query 参数（`?token=xxx&orderId=1001`）。

### 关键实现点（见 `JwtHandshakeInterceptor.java` javadoc）

1. **为什么 token 必须走 query 而不能走 header**
   浏览器原生 `WebSocket` 构造函数**不允许自定义请求头**，前端拿不到写 `Authorization` 的机会。
   备选是「先连上、再发一帧带令牌的认证消息」，但那意味着要先接受一个未认证连接，握手阶段直接拒掉更安全。

2. **必须同时校验两件事**
   - **令牌本身**：`JwtTokenProvider.parse()` → 类型 → 黑名单 → 密码版本
   - **订单归属**：`OrderService.requireInvolved(orderId, loginUser)` — 复用 REST 接口的归属判定

   只校验令牌不够：任何一个登录用户都能订阅别人的陪诊进度，那是老人全天行动轨迹级别的信息。

3. **401 与 403 分开回**
   - 401（令牌无效） → 前端据此走"刷新令牌"流程
   - 403（非相关方）→ 前端据此提示"别白试了"

4. **身份放在 WebSocketSession 属性，不放 SecurityContext**
   - 握手是一次 HTTP 请求，会话建立后帧处理跑在别的线程，`SecurityContextHolder` 早被清空
   - 把 `LoginUser` 放进 `WebSocketSession.getAttributes()`，由 `OrderProgressHandler.afterConnectionEstablished` 取出

5. **连接日志脱敏**
   - 只记 `userId` / `role` / `orderId`，**不记 token 本身**
   - 反向代理 / 网关层必须配置过滤 `token=` 参数，避免 token 进入访问日志

---

## 后果

### 客户端约束

- 连接 URL：`ws(s)://host/ws/progress?token=<accessToken>&orderId=<orderId>`
- 令牌过期时连接被拒（401），前端拦截后跳登录 / 刷新令牌
- 同一账号可多端连，每个端是独立的 `WebSocketSession`，服务端按 `sessionId` 分组广播

### 服务端约束

- `SecurityConfig` 必须放行 `/ws/**`（已在 `PUBLIC_ENDPOINTS` 配置）
- 放行依据：浏览器 WebSocket 不允许自定义请求头，握手阶段 JwtAuthenticationFilter 看不到 token，会把升级请求判成未认证。**JwtHandshakeInterceptor 才是真正的鉴权点**，Security 这一层放行是必需的。
- ASYNC / ERROR 分发也必须放行（`SecurityConfig` 已配）— 否则 SSE 长连接断开会在日志刷 ERROR。

### 已知局限

- **握手后无法强制下线**：JWT 过期 / 拉黑 / 改密后，已建立的 WebSocket 连接不会主动断开。当前接受该风险，靠客户端心跳重连 + 后端 `OrderProgressHub` 清理机制兜底。
- **token 落访问日志**：反向代理 / 网关层必须明确过滤 `token=` 参数。

---

## 验证

### 自动化测试（已通过）

| 测试 | 位置 | 状态 |
|---|---|---|
| `OrderProgressHubTest` 11 个用例 | `backend/src/test/.../websocket/OrderProgressHubTest.java` | ✅ |
| `MessageSseHubTest` 13 个用例（SSE 同源 Hub 复用） | `backend/src/test/.../websocket/MessageSseHubTest.java` | ✅ |
| `ExecutionServiceTest` 21 个用例（Hub 在打卡流程里被调用） | `backend/src/test/.../service/ExecutionServiceTest.java` | ✅ |

### e2e（待 dev 环境联跑）

`backend/sql/tools/e2e_execution.py` 已包含：
- 合法 token + 归属订单 → 收到 `ORDER_PROGRESS` 事件
- 过期 token → 握手 401，连接关闭
- FAMILY token 订阅不属于他的订单 → 403，连接关闭
- 跨账号：FAMILY-A 订阅 FAMILY-B 的订单 → 403

### 压测（待 JMeter 接入后）

100 并发 WebSocket 连接稳定性、断开重连补齐事件不丢失 / 不重复。

---

## 后续待办

- [ ] 反向代理 / 网关层配置过滤 `token=` 参数，避免 token 进入访问日志
- [ ] M12+ 接入 JMeter WebSocket 插件做 100 并发稳定性压测
- [ ] 如未来需要「踢下线」能力，引入 STOMP 走方案 1（参见 ADR 0003 缓存策略）

---

## 相关 ADR

- `0002-m6-redis-lock.md`（M6 漏服推送走 M8 SSE，鉴权路径与本 ADR 同源）
- `0003-m10-stats-cache.md`（无直接关联）
