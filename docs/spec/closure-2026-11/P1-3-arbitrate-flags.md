# P1-3 · 仲裁执法开关落结构化字段

> 批次：**A（订单与结算域）** · 级别：P1 · 来源：`reports/business-closure-final-assessment-2026-11-28.md` §P1-3
> 错误码：**不新增**

## Problem Statement

管理员在订单仲裁时有两个执法开关：「标记退费给家属」和「对陪诊员计违规」。

点了之后，系统做的事是——往备注文本里追加一句话。仅此而已：

- `companion_order` 没有「已退费」标记列；
- `companion_profile` 没有违规计数；
- `arbitrate_flag` 这一列虽然存在，但全仓只在**下单时写成 0**，**没有任何地方写 1**。

结果是：管理员以为「系统记下了这个陪诊员违规」，实际上除了那一行自由文本，什么都查不到。「累计违规 N 次应限制接单」这类治理动作没有数据基础。

## Solution

把两个开关落成结构化字段，使「这笔仲裁判了谁有责任」成为可查询的事实：

- 订单侧记录「已标记退费」与「已判定陪诊员违规」；
- 陪诊员侧能统计到违规次数（通过订单侧聚合，不新增计数列，避免双写不一致）。

`arbitrate_flag` 这一列接上写入逻辑，不再是一个只写 0 的死列。

## User Stories

1. 作为管理员，我想点了「计违规」之后能在陪诊员档案里看到违规次数，这样我知道该怎么处置。
2. 作为管理员，我想在仲裁列表里筛出「已判定违规」的订单，这样我能批量跟进。
3. 作为管理员，我想在陪诊员详情页看到他的历史违规订单，这样我审核时能判断风险。
4. 作为财务，我想在结算台账里看到「已标记退费」的订单，这样线下退款时不会漏。
5. 作为财务，我想知道哪些订单的退费是仲裁判定的，这样对账有依据。
6. 作为陪诊员，我想看到自己有几笔被判违规，这样我能申诉或改进。
7. 作为陪诊员，我想违规记录不是一句话而是能查的事实，这样它不会随风消失。
8. 作为家属，我想我的纠纷结果在订单上有明确标记，这样我能确认平台真的判了。
9. 作为运营，我想统计「被判违规的陪诊员占比」，这样我能评估服务质量。
10. 作为运营，我想后续能基于违规次数做「限制接单」，这样治理有抓手。
11. 作为审计者，我想 `arbitrate_flag` 列真的被写入，这样审计脚本不会读到恒为 0 的假数据。
12. 作为测试者，我想看到「计违规 → 订单标 1 → 陪诊员违规次数 +1」的用例。
13. 作为开发者，我想违规次数从订单表聚合而不是维护计数列，这样不会出现双写不一致。
14. 作为开发者，我想列的语义有注释说明，这样后人不至于把 `arbitrate_flag` 当成「是否经过仲裁」。
15. 作为开发者，我想这次改动不触碰状态机。

## Implementation Decisions

### 数据变更（新迁移脚本）

- `companion_order.arbitrate_flag`：**已存在**，本单**接上写入逻辑**。语义定为「该订单的仲裁判定结果为对服务方不利」（即 `penaltyToCompanion = true` 时置 1）。**必须补列注释**（`ALTER TABLE ... MODIFY COLUMN`），否则后人误读为「是否经过仲裁」。
- `companion_order` 新增 `refund_flag TINYINT NOT NULL DEFAULT 0 COMMENT '仲裁判定需退费给家属：0-否 1-是（线下执行）'`。
- **不新增违规计数列**：违规次数通过 `SELECT COUNT(*) FROM companion_order WHERE companion_id = ? AND arbitrate_flag = 1 AND deleted = 0` 聚合。

### 规则

- `AdminServiceImpl#arbitrate`：`penaltyToCompanion = true` → `arbitrate_flag = 1`；`refundToFamily = true` → `refund_flag = 1`。**与 `forceTerminal` 同一事务**。
- 备注文本**保留**（人读需要），但不再作为唯一事实来源。
- 新增只读能力：
  - `GET /api/admin/order` 增加 `arbitrateFlag` 筛选参数（比新开端点便宜）。
  - 陪诊员档案 VO 增加 `penaltyCount` 字段（由聚合查询填充）。

### 接口变更

- `GET /api/admin/order?arbitrateFlag=true`：新增可选筛选。
- `GET /api/user/companion/{id}`（`CompanionProfileVO`）：新增 `penaltyCount`。
- `docs/api/08-admin.md`、`docs/api/01-auth-user.md` 同步。

## Testing Decisions

### 好测试

断言「仲裁请求带 `penaltyToCompanion` 时，订单更新包里含 `arbitrate_flag = 1`」。用 `ArgumentCaptor` 抓 `LambdaUpdateWrapper`——这是通过公开方法可观察的写入意图。

### 测试模块

扩展 `AdminArbitrateTest` + 新建 `CompanionPenaltyAggregationTest`

### 垂直切片

1. **红**：`仲裁判计违规时订单arbitrateFlag应为1`。
2. 实现。
3. **红**：`仲裁判退费时订单refundFlag应为1`。
4. **红**：`两个开关都不选时两列都应为0`。
5. **红**：`陪诊员档案应包含违规次数`。
6. **红**：`管理员订单列表可按arbitrateFlag筛选`。

### 先例

`AdminArbitrateTest`（仲裁的完整断言骨架）；`MetricDictionaryEndpointTest`（VO 字段断言）。

## Out of Scope

- **不做「违规达 N 次自动限制接单」**（需产品定义阈值与申诉机制，另开工单）。
- 不做退费的实际执行（线下）。
- 不做违规申诉（投诉线的 `penalty_to_target` 已覆盖服务纠纷，本单只做订单仲裁线）。
- 不改前端仲裁页（现有两个开关已存在，本单让它们真的生效）。

## Further Notes

- **`arbitrate_flag` 的语义必须先定死再写代码**。当前它是「只写 0 的死列」，团队里可能有两种理解（「是否经过仲裁」vs「是否判定违规」）。本单定为后者并补注释；若团队要前者，应新增列而不是复用。
- 迁移脚本版本号与 P1-2 / P2-4 协商，先合先占号。
