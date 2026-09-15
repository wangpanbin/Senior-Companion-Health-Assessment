# ADR 0001 · M5 WebSocket 鉴权方案

- **状态**：待填（PROPOSED）
- **日期**：TBD
- **决策者**：B
- **关联模块**：M5 陪诊执行、打卡与实时进度
- **关联文档**：`docs/api/04-companion-execution.md`、`docs/agents/PLAN_BACKEND.md §11`

## 背景（待填）

M5 需要 WebSocket 双向通道：
- 上行：陪诊员打卡 / 上传节点
- 下行：家属端实时看到时间线进度

现有 M2 鉴权链路：HTTP `Authorization: Bearer <accessToken>` → `JwtAuthenticationFilter` → `SecurityContext`。

WebSocket 握手（HTTP Upgrade）阶段如何复用现有链路？

## 候选方案（待填）

1. **STOMP CONNECT 帧 Authorization header**
   - 客户端连 `/ws/order-progress` 后发 `CONNECT` 帧，header 携带 `Authorization: Bearer xxx`
   - 后端用 `ChannelInterceptor` 在 STOMP 层校验 token，写 `SecurityContext` 到握手 session
   - 优点：复用 JWT 校验逻辑；token 不落 URL 日志
   - 缺点：实现稍复杂，需要 STOMP + Spring Security 整合

2. **WebSocket 握手 URL 带 token**
   - `/ws/order-progress?token=xxx`
   - 优点：实现最简
   - 缺点：token 容易泄露在 Nginx access log / 浏览器 Referer；不推荐

3. **握手成功后客户端另发 STOMP CONNECT 单独携带 token**
   - 与方案 1 类似但时序不同
   - 实际就是方案 1

## 决策（待填）

TBD（默认候选 1：STOMP `CONNECT` 帧 `Authorization` header）

## 后果（待填）

- 客户端：连接后第一个 `CONNECT` 帧必须带 token；服务端 401 时关闭连接
- 服务端：实现 `StompAuthChannelInterceptor`，复用 `JwtTokenProvider`
- 测试：e2e 需覆盖 token 过期 / 角色错配 / 同一账号多端连接场景
- 日志：连接日志脱敏，只记录 userId，不记录 token

## 验证（待填）

- 端到端 e2e_websockets.py：
  - 合法 token 连接成功 → 收到推送
  - 过期 token → 收到 ERROR 帧 + 连接关闭
  - FAMILY token 订阅 COMPANION 专属频道 → 403
- JMeter WebSocket 插件：100 并发连接稳定性

## 相关 ADR

- `0002-m6-redis-lock.md`（M6 漏服推送走 M8 SSE，与本 ADR 鉴权路径不同）
- `0003-m10-stats-cache.md`（无直接关联）
- `0004-m13-docker-fallback.md`（无直接关联）
