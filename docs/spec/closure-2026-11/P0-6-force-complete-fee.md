# P0-6 · 强制完成须走费用汇总

> 批次：**A（订单与结算域）** · 级别：P0 · 来源：`reports/business-closure-final-assessment-2026-11-28.md` §P0-6
> 错误码：**不新增**

## Problem Statement

陪诊员正常完成服务时，系统会把「服务费」写成一条费用明细，并重算订单的 `actual_fee`。但管理员**强制完成**时什么都不算——`actual_fee` 保持空。

而结算金额取的是 `COALESCE(actual_fee, fee)`。所以一张有代垫费用（陪诊员先垫了挂号费、药费）的订单被强制完成后，结算只算申报的 `fee`，陪诊员垫的钱**不进结算**。系统一声不响，陪诊员少收钱。

## Solution

强制完成与正常完成走同一套费用汇总：把申报服务费落成费用明细，并重算 `actual_fee`。这样无论订单从哪条路径结束，结算金额都是同一份算法算出来的。

## User Stories

1. 作为陪诊员，我想我代垫的费用不会因为「管理员强制完成」而丢失，这样我垫的钱能拿回来。
2. 作为陪诊员，我想强制完成的订单和正常完成的订单结算金额口径一致，这样我不必分辨订单是怎么结束的。
3. 作为财务，我想所有已完成订单都经过同一套费用汇总，这样我对账时只有一份口径。
4. 作为财务，我想看到强制完成的订单上代垫费用的明细仍在，这样我能核对金额构成。
5. 作为管理员，我想强制完成时如果陪诊员没申报服务费，系统不会凭空造一个 0 元明细，这样数据是干净的。
6. 作为管理员，我想强制完成时不会重复插入服务费明细，这样多次操作不会导致金额翻倍。
7. 作为管理员，我想强制完成失败时费用也不会被写一半，这样不会出现金额不一致。
8. 作为家属，我想强制完成的订单金额与我看到的费用明细一致，这样我不会质疑平台算错。
9. 作为审计者，我想强制完成的订单在费用明细表里留下记录，这样我能追溯金额来源。
10. 作为测试者，我想看到「有代垫明细 + 强制完成 → actual_fee 含代垫」的用例，这样这条修复不会退回去。
11. 作为开发者，我想费用汇总只有一个实现，这样不会出现「两条完成路径两种算法」。
12. 作为开发者，我想这条修复不改结算侧，这样影响面最小。

## Implementation Decisions

### 规则

- `OrderServiceImpl#forceTerminal` 在目标为 `COMPLETED` 时，调用 `orderFeeItemService.applyOnComplete(orderId, null)`。
  - 传 `null` 而不是 0：`applyOnComplete` 在 `declaredFee == null` 时不会插入服务费明细，只重算已存在明细的合计。这正好符合「管理员强制完成时陪诊员可能没申报服务费」的现实。
- 保持 `applyOnComplete` 的幂等：已有 `SERVICE` 明细时不重复插入（现有逻辑已如此，本单只负责调用）。
- **调用位置**：在状态写入**之后**（与正常完成一致），同一事务内。

### 接口变更

- `POST /api/admin/order/{id}/arbitrate`（`targetStatus = COMPLETED`）：行为变更——现在会写费用明细并更新 `actual_fee`。
- `docs/api/03-order.md` §12「履约详情」与 `docs/api/08-admin.md` 仲裁一节补充说明。

### 数据变更

无新表；`order_fee_item` 与 `companion_order.actual_fee` 的写入路径增加一处。

## Testing Decisions

### 好测试

断言「强制完成是否调用了 `applyOnComplete`」以及「调用参数是 null」。这是通过公开方法可观察的行为（协作者收到的调用），不是私有实现细节。

### 测试模块

扩展 `AdminArbitrateTest`（已有强制终态的测试骨架）+ 新建 `OrderForceCompleteFeeTest`

### 垂直切片

1. **红**：`强制完成应调用费用汇总`（`verify(orderFeeItemService).applyOnComplete(ORDER_ID, null)`）。
2. 实现：加调用。
3. **红**：`强制取消不应调用费用汇总`（防止误伤）。
4. **红**：`强制完成时actual_fee应被重算`（`@Mock` 环境下断言调用）。
5. **红**：`费用汇总抛异常时状态变更应回滚`。

### 先例

`OrderFeeItemTest`（`applyOnComplete` 的幂等与口径）；`AdminArbitrateTest`（强制终态的事务与日志）。

## Out of Scope

- 不改结算金额公式（仍 `COALESCE(actual_fee, fee)`）。
- 不改 P2-3 的收入口径 key。
- 不回溯修复历史强制完成订单的 `actual_fee`（如需，另开数据修复脚本）。

## Further Notes

- 与 P0-5 同批验证最有效：一张「有代垫 + 强制完成 + 核定结算」的完整链路能同时验证 P0-5 与 P0-6。
- 若团队后续把结算金额改成「`fee_item` 汇总优先」，本单的价值仍在（费用明细本身就是凭证）。
