# P1-6 · 投诉超时升级 + 申诉占用按 type 隔离

> 批次：**A（订单与结算域）** · 级别：P1 · 来源：`reports/business-closure-final-assessment-2026-11-28.md` §P1-6
> 错误码：**不新增**

## Problem Statement

两个独立的治理闭环缺口：

1. **`PROCESSING` 可以永久停留**。管理员点了「处理中」之后不点结案，投诉就永远挂在处理中，用户看到的是「正在处理」——一个可以无限期成立的承诺。全仓 5 个定时任务，没有一个扫投诉或产品反馈。

2. **申诉被普通投诉占位**。陪诊员想对一条差评申诉时，如果家属先就同一订单提了普通投诉，系统按「同一订单已有未结案投诉」直接 409 拒绝。陪诊员申诉不了，而申诉正是 E4 的核心能力。

## Solution

1. 给投诉与产品反馈加「处理中超时」升级：超过 N 天仍在处理中，自动提醒管理员并在列表标出超时（**不改状态**，只升级可见性）。
2. 申诉的占用判定按类型隔离：评价申诉只看「同一评价的未结案申诉」，不被同订单的普通投诉挡住；反过来，普通投诉也不被申诉挡住。

## User Stories

1. 作为家属，我想投诉不会永远停在「处理中」，这样我不会被一个假承诺拖住。
2. 作为家属，我想在投诉长时间未结案时收到一条进展说明，这样我知道平台没有忘记。
3. 作为家属，我想投诉最终有一个明确的结论，这样我能判断是否要另想办法。
4. 作为陪诊员，我想在有人对我提了服务投诉的同时仍能对差评申诉，这样我的两种维权路径不互相阻塞。
5. 作为陪诊员，我想申诉的占用判定只看同一条评价，这样我不会因为无关的投诉被拒。
6. 作为陪诊员，我想被明确告知「你已对该评价申诉过」，这样我知道拒绝的真实原因。
7. 作为管理员，我想在列表里看到「超时未结案」的投诉，这样我能优先处理。
8. 作为管理员，我想看到超时天数，这样我能判断紧急程度。
9. 作为管理员，我想超时只升级可见性不改状态，这样我的处置权不被系统抢走。
10. 作为管理员，我想产品反馈也受同一套超时机制约束，这样两条治理链口径一致。
11. 作为运营，我想统计超时投诉数量，这样我能评估客服产能。
12. 作为测试者，我想看到「处理中超 N 天 → 被标记超时」的用例。
13. 作为测试者，我想看到「同订单有普通投诉 + 陪诊员申诉 → 申诉成功」的用例。
14. 作为开发者，我想超时扫描器能在投诉与反馈之间复用同一份逻辑。
15. 作为开发者，我想这条改动不触碰投诉的状态机（只加可见性标记）。

## Implementation Decisions

### A. 超时升级（**聚合计算，不加列**）

- 判定：`status = 'PROCESSING'` 且 `update_time < now - N days` → 视为超时。
  - `N` 可配：`nianglin.complaint.processing-timeout-days`（默认 3）、`nianglin.feedback.processing-timeout-days`（默认 7）。
- **不加数据库列**：`overdue` 与 `overdueDays` 由 service 层计算后放进 VO。
- 新类 `task/GovernanceTimeoutScheduler`（一个类扫两条链，避免两个壳），`@Scheduled(fixedRateString)` + Redis 锁，照抄 `FulfillmentScheduler` 范式。
- 超时行为：
  - 给管理员批量发站内信（新增 `MessageType.COMPLAINT_TIMEOUT`）；
  - **同一档位只提醒一次**：Redis `SET NX`，key = `governance:timeout:{type}:{id}:{tier}`，TTL 覆盖一个档位周期；
  - **不改状态、不改 `update_time`**（避免把「未处理」伪装成「刚处理」）。

### B. 申诉占用按 type 隔离

- `ComplaintServiceImpl#create` 的「同订单未结案」检查改为：
  - 若 `type = REVIEW_APPEAL` → 只查「同一 `reviewId` 的未结案申诉」（`countOpenAppealByReviewId` 已存在）。
  - 若 `type != REVIEW_APPEAL` → 只查「同一订单的未结案**非申诉**投诉」（加 `type != 'REVIEW_APPEAL'` 条件）。
  - 两边都不再互相占位。

### 接口变更

- `GET /api/admin/complaint`、`GET /api/admin/feedback`：VO 新增 `overdue`（Boolean）与 `overdueDays`（Integer）。
- `POST /api/complaint`：行为变更——申诉不再被普通投诉挡住。
- `docs/api/06-review-complaint.md`、`docs/api/15-product-feedback.md`、`docs/api/07-message.md`（新增消息类型）同步。

## Testing Decisions

### 好测试

- 超时：断言「给定 `status` 与 `updateTime`，`list` 返回的 `overdue` 是否为 true」。**不测调度器壳**，测承载逻辑的 service。
- 隔离：断言「在不同前置投诉组合下，`create` 抛 409 还是成功」。

### 测试模块

新建 `GovernanceTimeoutTest`、扩展 `ComplaintServiceAppealTest`

### 垂直切片

1. **红**：`同订单有普通投诉时陪诊员申诉应成功`。
2. 实现 B 部分。
3. **红**：`同评价已有未结案申诉时再次申诉应409`。
4. **红**：`同订单已有未结案普通投诉时再提普通投诉应409`（保护现状）。
5. **红**：`处理中超3天的投诉overdue应为true`。
6. 实现 A 部分。
7. **红**：`处理中未超3天的投诉overdue应为false`。
8. **红**：`超时提醒同一档位只发一次`。
9. **红**：`超时扫描不修改投诉状态与update_time`。

### 先例

`ComplaintServiceAppealTest`（申诉校验骨架，本单在其上扩展）；`FulfillmentServiceScanTest`（扫描 + 去重 + 幂等）。

## Out of Scope

- 不做「超时自动升级状态」（如超时自动驳回）——状态变更必须有人负责。
- 不做「超时后自动向投诉人推送进展」（涉及打扰度权衡，另议）。
- 不做投诉结案时联动评价有效性（那是 P1-2 的职责，本单只清障）。
- 不改前端列表页（需另开工单：`overdue` 标红）。

## Further Notes

- **两个子问题耦合度低，可分两次提交**，但同单交付（同属治理链）。
- 与 P1-2 的顺序：本单先合，P1-2 才能保证「评价无效裁定」的入口不被 409 挡住。
