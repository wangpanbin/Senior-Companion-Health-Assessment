# P0-7 · 无陪诊员的单不可强制完成

> 批次：**A（订单与结算域）** · 级别：P0 · 来源：`reports/business-closure-final-assessment-2026-11-28.md` §P0-7
> 错误码：复用 `3002 ORDER_STATUS_ILLEGAL` + 自定义 message（**不新增码**）

## Problem Statement

一张没人接的待接单订单，管理员可以「强制完成」（例如家属说线下已经解决了）。完成后，家属点「评价」，系统报「订单数据异常，请联系管理员」。

原因是评价要求订单有陪诊员，而这种单没有。`REVIEWED` 又是终态、不能跳过——这张单**永远停在「已完成」，永远评价不了**。评价率统计的分母从此与该单永久不匹配。

## Solution

没有陪诊员的订单，只允许强制取消，不允许强制完成。家属若确实线下解决了，走取消（并说明原因）。

**本单选择「拒绝」，而不是「改造评价让它允许无陪诊员」**——后者会让订单停在 `COMPLETED` 无法进入 `REVIEWED`，仍然破坏状态机。

## User Stories

1. 作为管理员，我想在强制完成一张无陪诊员的单时被拒绝，这样我不会造出一张永远无法评价的订单。
2. 作为管理员，我想被拒绝时看到「该单尚无陪诊员，只能取消」的提示，这样我知道该怎么做。
3. 作为管理员，我想对无陪诊员的单仍能强制取消，这样家属线下解决的场景仍有出路。
4. 作为家属，我想我的订单不会停在「已完成但无法评价」，这样我不会看到「订单数据异常」这种内部报错。
5. 作为家属，我想在管理员取消后能重新下单，这样线下没解决时我还有办法。
6. 作为运营，我想评价率的分子分母永远自洽，这样我拿这个数去汇报是可信的。
7. 作为运营，我想看到因为「无人接单」而被管理员取消的订单数，这样我能评估派单问题。
8. 作为测试者，我想看到「PENDING 无陪诊员 → 强制完成 → 被拒」的回归用例。
9. 作为测试者，我想看到「PENDING 无陪诊员 → 强制取消 → 成功」的用例，确认拒绝没有误伤取消。
10. 作为开发者，我想这条约束写在强制终态入口而不是控制器，这样所有调用方都受约束。
11. 作为开发者，我想错误信息能直接指向下一步动作，这样客服不必查文档。
12. 作为审计者，我想被拒绝的操作不写 `admin_oper_log`（因为它没发生），这样日志里不出现假动作。

## Implementation Decisions

### 规则

- `forceTerminal` 目标为 `COMPLETED` 时，若 `companion_id IS NULL` → 拒绝。
- **错误码**：复用 `3002 ORDER_STATUS_ILLEGAL`，自定义 message「该订单尚无陪诊员，无法强制完成，请改用强制取消」。
  - 理由：不新增码可避免前端错误码映射改动，message 已足够指向动作。若团队倾向语义清晰可新增 `3013 ORDER_COMPLETED_REQUIRES_COMPANION`——**需先在 README §五 错误码预算表登记**。
- 检查位置：`OrderTransitionServiceImpl#forceTerminal`（与 P0-5 同一入口，保持「强制终态的所有固有约束都在这一层」）。
- 目标为 `CANCELLED` 时**不检查**（无陪诊员的单本来就应该能取消）。

### 接口变更

- `POST /api/admin/order/{id}/arbitrate`：新增失败分支（`3002` + 自定义 message）。
- `docs/api/08-admin.md` 仲裁一节补说明。

### 数据变更

无。

## Testing Decisions

### 好测试

断言「在 `companion_id` 为空 / 非空两种情况下，强制完成分别抛码 / 成功」。

### 测试模块

扩展 `AdminArbitrateTest` + 新建 `OrderForceTerminalCompanionTest`

### 垂直切片

1. **红**：`无陪诊员的待接单订单强制完成应被拒`。
2. 实现。
3. **红**：`无陪诊员的待接单订单强制取消应成功`。
4. **红**：`有陪诊员的订单强制完成应成功`（保护现状）。
5. **红**：`被拒时不写admin_oper_log`。

### 先例

`AdminArbitrateTest`（已有「终态不可变更」等拒绝分支的写法）。

## Out of Scope

- **不改评价模块**（不允许跳过陪诊员评分）。
- 不清理库里已存在的 `COMPLETED AND companion_id IS NULL` 订单（Further Notes 给核查 SQL）。
- 不改前端仲裁页的选项列表（需另开工单：无陪诊员时禁用「强制完成」选项）。

## Further Notes

- **核查 SQL**：`SELECT id, order_no, status, companion_id FROM companion_order WHERE status = 'COMPLETED' AND companion_id IS NULL;`
- **前端配套建议**：仲裁弹窗里，若订单 `companionId` 为空，应直接把「强制完成」选项置灰并给出原因。这属前端工单，本单不实施，但要写进 `docs/api/08-admin.md` 让前端知道。
- 与 P0-5 在同一个人口（`forceTerminal`）加两条检查，建议同批提交，便于一起看「强制终态有哪些固有约束」。
