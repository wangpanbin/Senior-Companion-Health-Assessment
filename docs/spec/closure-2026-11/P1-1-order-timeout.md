# P1-1 · 订单超时闭环

> 批次：**A（订单与结算域）** · 级别：P1 · 来源：`reports/business-closure-final-assessment-2026-11-28.md` §P1-1
> 错误码：**不新增**

## Problem Statement

订单只有「人在操作」时才会前进。一旦没人操作：

- 就诊时间已过的待接单订单，只是从大厅消失，状态仍是「待接单」，永久占着家属列表；
- 「已接单」的订单，陪诊员再也没出现，没人发现；
- 「服务中」的订单，陪诊员忘了点完成（或账号异常），永远停在服务中。

全仓 5 个定时任务，没有一个是处理订单状态的。

## Solution

给订单加两个超时出口：

1. **待接单超时**：就诊时间过去 N 小时后，标记风险并通知家属。自动取消是独立开关，默认关闭（避免误伤仍在等服务的家属）。
2. **服务中超时**：进入「服务中」超过 N 小时仍未完成，升级到履约风险中心，让管理员看到。**不自动改状态**（服务可能真的还在进行）。

## User Stories

1. 作为家属，我想我的订单在就诊时间过去之后不会再假装「待接单」，这样我不用手动去取消。
2. 作为家属，我想在订单过期时收到通知，这样我知道该重新下单了。
3. 作为家属，我想在陪诊员进了医院却一直没点完成时能看到订单「异常」，这样我不会以为服务还在进行。
4. 作为管理员，我想在风险中心看到所有「超时未完成」的订单，这样我能主动介入。
5. 作为管理员，我想超时订单被明确标出来，这样我不用逐张翻列表。
6. 作为管理员，我想自动取消是可配置的开关，这样上线初期可以先只告警不自动处理。
7. 作为管理员，我想自动取消的订单在取消原因里写明「超时未接单」，这样事后能区分。
8. 作为陪诊员，我想我的单不会因为我忘了点「完成」就被自动取消，这样我的报酬不会丢。
9. 作为运维，我想这个调度器有分布式锁，这样多实例部署时不会重复处理同一批订单。
10. 作为运维，我想调度器出错时不影响下一轮，这样不会因为一次异常停摆。
11. 作为运维，我想看到每轮的扫描数量与处理数量，这样我能判断配置是否合理。
12. 作为测试者，我想看到「过期未接单 → 被标记 / 取消」的用例，这样这条闭环不会缺失。
13. 作为测试者，我想看到「服务中超时 → 风险升级」的用例。
14. 作为开发者，我想这两条规则各有独立开关，这样可以分别上线。
15. 作为开发者，我想实现照抄现有调度器的锁粒度范式，这样不需要新的运维知识。

## Implementation Decisions

### A. 待接单超时（PENDING）

- 扫描条件：`status = 'PENDING'` 且 `visit_time < now - graceHours`。
- 默认 `graceHours = 2`，配置项 `nianglin.order.pending-timeout-hours`。
- **默认行为**：只做「标记」——写履约事件 `ORDER_TIMEOUT`，风险升级到 `MANUAL_REQUIRED`，通知家属「该订单已过就诊时间且无人接单，建议取消并重新下单」。
- **自动取消**由独立开关 `nianglin.order.pending-auto-cancel`（默认 **false**）控制；打开时走 `forceTerminal → CANCELLED`，`cancel_reason` = 「就诊时间已过且无人接单，系统自动取消」。
  - PENDING 单不可能已结算，不会与 P0-5 的检查冲突。

### B. 服务中超时（IN_SERVICE）

- 扫描条件：`status = 'IN_SERVICE'` 且 `start_time < now - inServiceHours`。
- 默认 `inServiceHours = 12`，配置项 `nianglin.order.in-service-timeout-hours`。
- 行为：写履约事件 `ORDER_IN_SERVICE_TIMEOUT`、风险升级到 `URGENT`、通知家属与管理员（**不自动改状态**）。

### 调度器形态（照抄现有范式）

- 新类 `task/OrderTimeoutScheduler`，`@Scheduled(fixedRateString = "${nianglin.order.timeout-scan-interval-ms:600000}")`（默认 10 分钟）。
- `RedisLockUtil.tryLock` 按轮加锁，TTL < 间隔。
- 方法体自 catch 异常，不打断后续轮次（照抄 `FulfillmentScheduler` 的注释与写法）。
- 单轮批量上限可配（`nianglin.order.timeout-scan-batch`，默认 200）。
- 幂等：先用条件更新「认领」（`update ... where status = 期望值`），再写事件；同类型事件按单去重（照抄 `MedicationServiceImpl#scanMissedTasks` 的认领手法）。

### 新增枚举值

- `FulfillmentEventType` 增 `ORDER_TIMEOUT`、`ORDER_IN_SERVICE_TIMEOUT`。
- `MessageType` 增 `ORDER_TIMEOUT`（模板文案不含诊断、不含金额建议）。
- 两者都要同步 `docs/api/03-order.md` 与 `docs/api/07-message.md` 的枚举表。

### 接口变更

- 无新端点。风险中心与履约详情会自动多出这两种事件类型。
- `docs/api/03-order.md` §五「已知限制」删除「没有自动取消过期订单的任务」，改为描述本机制。

## Testing Decisions

### 好测试

调度器壳**不写测试**。测 `OrderTimeoutService`（新建，承载扫描逻辑）：给定 mock 的 `orderMapper` 返回一批订单，断言「哪些被更新、更新成什么、发了什么通知」。

### 测试模块

`OrderTimeoutServiceTest`（新建）

### 垂直切片

1. **红**：`过期未接单且开关关闭时_只标记不改状态`。
2. 实现。
3. **红**：`过期未接单且开关打开时_应强制取消`（`verify(orderService).forceTerminal(..., CANCELLED)`）。
4. **红**：`未过期的待接单订单不应被处理`。
5. **红**：`服务中超时订单应升级风险且不改状态`。
6. **红**：`已完成的订单不应被扫描`。
7. **红**：`同一订单重复扫描不重复写事件`（幂等）。
8. **红**：`单轮处理量受批量上限约束`。

### 先例

`FulfillmentServiceScanTest`（扫描 + 升级 + 幂等的完整骨架，几乎可整体照抄）；`MedicationScheduler` 的锁粒度注释。

## Out of Scope

- 不做「催单后仍无人接单 → 自动加价」（涉及金额策略）。
- 不做「ACCEPTED 超时未开始服务」的第三个出口（可由同一框架后续扩展）。
- 不改前端（风险中心已有展示超时事件的列表）。
- 不做历史数据回溯扫描（首次上线只处理 `visit_time` 在近 N 天内的）。

## Further Notes

- **默认不自动取消是刻意的**：自动取消会让「家属其实还想等服务」的订单消失，代价高于「多挂一会儿」。上线后看数据再决定是否打开。
- 与 P0-4 的交互：封禁陪诊员时已把 `IN_SERVICE` 单放回 `PENDING`，此后由本单的待接单超时兜底——两条链正好接上。
