# 4 · 业务规则（核心约束，不是建议）

> 本文件是 `AGENTS.md` **§4** 的落地位置。
> 拆分原因：运行时给仓库根 `AGENTS.md` 的上下文预算是 **32 KB**，
> 拆分前它有 **54 KB** —— 超出预算的部分**每次会话都被静默丢弃**，
> 丢掉的恰好是订单状态机、Do/Don't 底线、验收清单这些最该被看见的内容。
> 拆开后 `AGENTS.md` 只留红线与导航，细则**按需加载**。
>
> ⚠️ 硬约束仍在 `AGENTS.md`（始终在上下文里）：§0 合规红线、§0.2 AI 自我约束、
> §10 Do/Don't 最低底线、§11 验收检查清单。本文件是细则，不覆盖那些硬约束。
>
> 小节编号（§4.1 …）**未改动**，仓库其它文档里的 `AGENTS.md §4.N` 引用仍然对得上。

---

## 4.1 陪诊订单状态机（最重要）

```
PENDING ──► ACCEPTED ──► IN_SERVICE ──► COMPLETED ──► REVIEWED
   │           │              │             │
   │           │              │             │
   └───────────┴──────────────┴─────────────┴──► CANCELLED
```

进入 `CANCELLED` 有**两条互不相同**的路径，不要混用：

| 路径 | 允许的起点 | 入口 |
|---|---|---|
| **家属取消** | **仅** `PENDING` | `PUT /api/order/{id}/cancel`（须为本单下单人） |
| **管理员强制** | `PENDING` / `ACCEPTED` / `IN_SERVICE` / `COMPLETED` | M9 纠纷处理 `arbitrate` |

管理员强制还可把 `PENDING` / `ACCEPTED` / `IN_SERVICE` → `COMPLETED`。
`REVIEWED` 是终态，**任何路径都不得再变更**（含管理员强制）。

铁律（违反即返回 `3002` / `ORDER_STATUS_ILLEGAL`）：

1. **禁止跳级**：待接单不能直接变已完成。
2. **禁止回退**：已接单不能退回待接单。
3. **终态不可再流转**：已评价 / 已取消之后无法变更（`REVIEWED` / `CANCELLED` 对管理员强制同样关闭）。
4. 仅 ADMIN 可强制改变终态，且必须写 `admin_oper_log` 并通知双方。
5. 每次状态变更写入状态流转日志，异常时整体回滚。

**状态流转的唯一入口是 `OrderTransitionService.transition(order, target, operator, remark)`**（`docs/adr/0010`），业务 Service **不得**自行 `order.setStatus(...)` 或用 `orderMapper.updateById` 改状态：

- 该入口**独占四项配套逻辑**：合法流转判定、角色门禁、乐观锁落库、流转日志双写。漏一项即测试红；
- **正向流转的唯一权威判断**是 `OrderStatus.canTransitTo(target)`，它在 service 包内**只有这一个调用点**；
- **管理员强制终态是明确豁免的独立路径**，由 `forceTerminal()` + `isAdminForceable()` + `isTerminal()` 承担，
  并被 `docs/api/08-admin.md` 记为"绕过状态机的正向流转规则"；
- `PENDING → CANCELLED`（家属取消）**不塞进 `TRANSITIONS`**，而由入口显式放行 —— 否则会丢掉
  "仅家属单方取消 / 仅管理员可强制进入 `CANCELLED`" 这层语义；
- 需要"先判状态再判归属"的场景（如待接单订单应回 3002 而非 4003），用
  `requireTransitionAllowed(from, target, operator)` **只判定不落库**，它与落库共用同一份规则。

> ✅ **现状（2026-09-28 落地，commit `7171ecd`）**：原 4 处硬编码 `from != X` 守卫与
> 终态守卫已清零，`canTransitTo` 有了真实调用点，6 处流转 + `forceTerminal` 全部改经该入口。
> **反向验证已实测**：删 `TRANSITIONS` 一条边 → `OrderStatusTest` 与 `OrderTransitionServiceTest`
> 双双变红；改回即恢复全绿。改转移表现在会让测试失败，这是收口前做不到的。

## 4.2 打卡节点（M5）

| 传值 | 中文 |
|---|---|
| `DEPART` | 出发 |
| `ARRIVE` | 到院 |
| `IN_CONSULT` | 就诊中 |
| `TAKE_MEDICINE` | 取药 |
| `LEAVE` | 离院 |
| `FINISH` | 完成 |

约束：节点顺序不可逆（可跳过，不可回退）；同一订单同一节点只能打一次（4002）；坐标偏离订单地址超过 2000 米（`nianglin.order.checkin-max-distance-meters`）判为无效（4001）。

## 4.3 老人账号规则

| 场景 | 允许角色 |
|---|---|
| 查询类接口（GET） | ELDER 可访问自己名下数据 |
| 写操作（POST/PUT/DELETE） | **ELDER 一律 403**，仅 FAMILY / COMPANION / ADMIN 按各自规则放行 |

实现要求：服务端拦截；前端隐藏按钮**只提升体验，不构成安全边界**。

## 4.4 字段命名 / 时间格式 / 字符集

| 项 | 约定 |
|---|---|
| JSON 字段 | 小驼峰（`createTime`），DB 字段下划线（`create_time`），由 MyBatis-Plus 自动映射 |
| 枚举 | 传字符串大写枚举名，同时返回 `xxxLabel` 中文 |
| 布尔 | `is` / `has` 前缀 |
| ID | 路径变量用 `{id}`；批量用 body 数组 |
| 时间格式 | 字符串 `yyyy-MM-dd HH:mm:ss`（Jackson 已配）；仅日期 `yyyy-MM-dd` |
| 时区 | `Asia/Shanghai`（GMT+8） |
| 金额 | 字符串、两位小数，如 `"128.00"`（避免 JS 浮点误差） |
| 主键 | `Long`，JSON 中为数字 |
| null 字段 | Jackson `non_null` 策略，可省略 |
| 路径命名 | 全部小写、连字符、资源名用复数（`/api/order/hall`、`/api/medication/plan`） |

## 4.5 文件上传（M3 / M9）

| 项 | 约定 |
|---|---|
| 请求类型 | `multipart/form-data`，字段名统一 `file` |
| 单文件上限 | 10 MB；**`bizType=CHAT` 为 50 MB**（M13 聊天专用，代码层按 bizType 分级，servlet 全局 50MB/52MB） |
| 单请求上限 | 52 MB（原 20 MB 随 servlet 提额上调；非聊天类型仍由代码守 10 MB） |
| 允许类型 | `image/jpeg`、`image/png`、`image/webp`、`application/pdf`；**`bizType=CHAT` 另允许 `video/mp4`** |
| 返回 | `{ fileId, url, size }` |
| 存储 | 一期落本地 `./uploads/{yyyyMM}/{uuid}.{ext}`，后续可替换为对象存储 |
| 校验 | 必须读文件头（magic bytes）二次确认，**禁止**信任前端传来的 `Content-Type`；50MB 走流式落盘（**禁止**整文件 `getBytes()` 读进堆） |
