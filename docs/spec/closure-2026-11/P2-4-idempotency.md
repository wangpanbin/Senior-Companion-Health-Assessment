# P2-4 · 下单与聊天幂等键

> 批次：**A（订单与结算域）** · 级别：P2 · 来源：`reports/business-closure-final-assessment-2026-11-28.md` §P2-4
> 错误码：**不新增**（复用 `409 CONFLICT` 语义，或返回首次结果）

## Problem Statement

家属在弱网下点「提交订单」，前端超时重试，**产生两笔独立订单**。系统只靠订单号唯一性兜底，而订单号每次都是新生成的——两笔单都是合法的。

聊天同理：消息发送超时重试，同一条消息出现两次。

这两处都把重复的后果转嫁给了用户：家属要自己去取消多出来的订单，陪诊员要自己分辨哪条消息是真的。

## Solution

给两个写接口加幂等键。同一个键重复提交，返回第一次的结果，不产生第二笔。

## User Stories

1. 作为家属，我想网络超时后重试下单不会产生两笔订单，这样我不用自己去取消。
2. 作为家属，我想重试时看到的是同一笔订单，这样我确认提交成功了。
3. 作为家属，我想重复提交不会重复记账（线下结算场景下），这样我不担心多付。
4. 作为陪诊员，我想重复下单只在大厅出现一次，这样我不会重复抢同一单。
5. 作为陪诊员，我想聊天消息重试不会出现两条一样的，这样对话是干净的。
6. 作为家属，我想聊天图片上传重试不会产生两张一样的图，这样对方不会看到重复。
7. 作为管理员，我想能在订单详情看到「这是重试产生的同一笔」，这样我不必怀疑数据。
8. 作为运维，我想幂等键有过期时间，这样不会无限积累。
9. 作为运维，我想幂等键存储在 Redis 而不是新表，这样不增加迁移负担。
10. 作为测试者，我想看到「同键两次下单 → 只有一笔」的用例。
11. 作为测试者，我想看到「不同键两次下单 → 两笔」的用例（防止误合并）。
12. 作为测试者，我想看到「同键两次发消息 → 只有一条」的用例。
13. 作为开发者，我想幂等键是可选的，不传时行为与现在一致，这样老客户端不受影响。
14. 作为开发者，我想 Redis 不可用时降级为「放行」，这样不会因为缓存故障阻塞下单。
15. 作为开发者，我想这条实现照抄用药草稿里已有的 `SET NX` 范式。

## Implementation Decisions

### 下单（纯 Redis，无迁移）

- 请求头 `X-Idempotency-Key`（可选）。存在时：
  - Redis key = `order:idem:{familyId}:{key}`，`SET NX EX 10min`。
  - 抢到 → 正常下单，把 `{orderId}` 缓存到 `order:idem:result:{familyId}:{key}`（TTL 同）。
  - 未抢到 → 读结果缓存；有则返回**同一笔订单的 `OrderCreateResultVO`**；无（处理中）则返回 `409`「请求正在处理中，请稍候」。
- Redis 不可用 → `log.warn` + 放行（与 `buildDraftFromOrder` 的降级口径一致）。

### 聊天（数据库唯一键）

- `ChatSendDTO` 新增可选 `clientMsgId`（≤64 字符，需校验）。
- `chat_message` 加列 `client_msg_id VARCHAR(64) DEFAULT NULL` + 唯一键 `(order_id, sender_id, client_msg_id)`。
  - MySQL 唯一键对 NULL 不约束 → 「不传即不幂等」天然成立。
- 插入时捕获 `DuplicateKeyException` → 查出已存在那条并返回（幂等成功，不报错）。

### 数据变更

- 新迁移脚本加 `chat_message.client_msg_id` + 唯一键。**版本号与 P1-2 / P1-3 协商，先合先占号。**

### 接口变更

- `POST /api/order`：新增可选请求头 `X-Idempotency-Key`。
- `POST /api/chat/{orderId}/messages`：请求体新增可选 `clientMsgId`。
- `docs/api/03-order.md`、`docs/api/11-order-chat.md` 同步。

## Testing Decisions

### 好测试

断言「同一幂等键调用两次，第二次返回的 `orderId` 与第一次相同」。用 mock 的 `StringRedisTemplate` 模拟 `SET NX` 的两种返回。

### 测试模块

新建 `OrderIdempotencyTest`、扩展 `ChatServiceTest`

### 垂直切片

1. **红**：`同幂等键二次下单应返回同一订单`。
2. 实现。
3. **红**：`不同幂等键应产生两笔订单`。
4. **红**：`不传幂等键时行为不变`。
5. **红**：`Redis不可用时应放行而不是报错`。
6. **红**：`同clientMsgId二次发消息应只落一条`。
7. **红**：`不同clientMsgId应落两条`。

### 先例

`MedicationDraftFromOrderTest`（`SET NX` 判重 + 降级放行的完整骨架，几乎可照抄）；`VitalSignSecurityTest`（唯一键冲突的处理）。

## Out of Scope

- 不做「前端自动生成幂等键」（需前端工单，本单只保证服务端支持）。
- 不做请求体级去重（只认 header / 字段）。
- 不为其他写接口（打卡、评价）加幂等（它们天然有唯一约束）。
- 不做幂等键的可视化与清理工具（TTL 自动过期）。

## Further Notes

- **10 分钟 TTL 是权衡**：太短挡不住长重试，太长占内存。「弱网下用户重试」通常在 1 分钟内，10 分钟足够。
- 聊天侧用数据库唯一键而不是 Redis，因为消息本身要落库、Redis 挂了也不该丢消息。
- 若 `chat_message` 已有其他唯一键，新增时注意不要造成索引冗余。
