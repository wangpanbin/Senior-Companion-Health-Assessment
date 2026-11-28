# P2-3 · 收入口径 key 冗余收敛

> 批次：**B（用药与文档域）** · 级别：P2 · 来源：`reports/business-closure-final-assessment-2026-11-28.md` §P2-3
> 错误码：**不新增**

## Problem Statement

陪诊员的「区间收入」在指标字典里占了**两个 key**：

| key | 名称 | 公式 |
|---|---|---|
| `companion.workbench.period_income` | 区间收入 | `SUM(COALESCE(actual_fee, fee))` |
| `companion.income.actual_income` | 区间实际收入 | `SUM(COALESCE(actual_fee, fee))` |

**公式逐字相同、含义相同、时间字段相同（都按 `visit_time`）、状态条件相同（都是 `COMPLETED` / `REVIEWED`）。**

同一条金额被写成两个 key 的后果：

- 指标字典的「双向一致性」检查（`check_metric_dict_parity.py`）无法判断它们是否应当相等，只能都登记；
- 后续任何口径调整都要改两处，一处漏改就出现「工作台说 3680，收入页说 3680」但某天突然不一致；
- 读者会以为这是两个不同的数（比如「应收」vs「实收」），而实际上不是。

## Solution

收敛成一个 key，另一个改为引用/别名。同时把「未结算 / 已结算」的拆分口径在工作台页面也用上——那才是用户真正想看的区分。

## User Stories

1. 作为陪诊员，我想工作台和收入页显示同一个数，这样我不会怀疑哪个是对的。
2. 作为陪诊员，我想看到「已结算 / 未结算」的拆分，这样我知道还有多少钱没拿到。
3. 作为陪诊员，我想「区间收入」这个词只对应一个定义，这样我不用猜。
4. 作为财务，我想指标字典里一个口径只有一个 key，这样我核对时不会漏。
5. 作为财务，我想 `unsettled + settled = actual_income` 这个等式有文档保证，这样对账有依据。
6. 作为运营，我想指标字典的 key 数量不虚高，这样看板配置清晰。
7. 作为运营，我想调整口径时只改一处，这样不会出现两个页面不一致。
8. 作为测试者，我想看到「两个入口的区间收入相等」的用例。
9. 作为测试者，我想看到「unsettled + settled = actual」的用例。
10. 作为测试者，我想看到指标字典双向校验仍通过（`check_metric_dict_parity.py`）。
11. 作为开发者，我想删掉重复 key 时同步删掉它的 calculator 登记，这样校验不会红。
12. 作为开发者，我想保留 `period_income` 作为别名不破坏既有看板，这样不用大改。
13. 作为开发者，我想这次改动不动金额公式本身，这样风险为零。
14. 作为审计者，我想指标的「人读面」和「机器面」仍一一对应。
15. 作为读者，我想指标字典里不再有两个看起来一样但名字不同的条目。

## Implementation Decisions

### 指标字典收敛

- **保留** `companion.income.actual_income`（更准确的命名），**把 `companion.workbench.period_income` 标记为别名**：
  - 在 `docs/spec/metric-dictionary.md` 里把 `period_income` 一行改为「同 `companion.income.actual_income`，别名保留以兼容既有看板配置」；
  - 机器面（`calculatorKey` 登记处）**保留两个 key 但指向同一个 calculator**，或在字典里声明 alias 关系。
  - **不要直接删 key**：`check_metric_dict_parity.py` 是双向校验，删了会红；且看板配置可能引用。

### 工作台页面补结算拆分（可选但强烈建议）

- `CompanionStatisticsServiceImpl#income` 已返回 `unsettledAmount` / `settledAmount`。工作台页若未展示，补上。
- 这一条属前端工单，**本单只做「文档 + 后端确保两处取数同源」**。

### 后端：确保同源

- 检查 `CompanionStatisticsMapper` 里 `selectOrderStats`（工作台）与 `selectDailyIncome`（趋势）以及收入页的取数，是否都用 `COALESCE(actual_fee, fee)` 且都不看 `payment_status`。
  - **现状已同源**（`CompanionStatisticsMapper` 三处一致），本单只需把这条写进文档，并在代码处补注释指明「口径唯一，见指标字典 `companion.income.actual_income`」。

### 接口变更

无。纯文档 + 注释。

## Testing Decisions

### 好测试

- `MetricDictionaryEndpointTest`（若存在）扩展一条：同一陪诊员同一区间，`GET /api/statistics/companion/me` 的 `periodIncome` 与 `GET /api/statistics/companion/me/income` 的 `actualIncome` **相等**。这是行为断言，能锁住「两个入口不该分叉」。
- 加一条 `unsettled + settled = actual` 的断言。

### 测试模块

扩展 `MetricDictionaryEndpointTest` 或新建 `CompanionIncomeConsistencyTest`

### 垂直切片

1. **红**：`工作台区间收入与收入页实际收入应相等`。
2. 若红 → 说明两处取数已分叉，先修同源；若绿 → 保留为回归保护。
3. **红**：`unsettled加settled应等于actual`。
4. 文档改动后跑 `python tools/ai_flow/gate_check.py --only metric-dict` 必须绿。

### 先例

`MetricDictionaryEndpointTest`、`CompanionStatisticsServiceTest`。

## Out of Scope

- 不改金额公式。
- 不改 `payment_status` 的语义。
- 不改结算台账的取数口径（那属 P0-5 的 Open Question）。
- 不改前端工作台（需另开工单：展示结算拆分）。

## Further Notes

- **本单在初稿里被误判为「指标字典没有一处说明哪个是实收」——事实错误。** 复核确认 `docs/spec/metric-dictionary.md` 已明确定义 `actual_income` / `unsettled_amount` / `settled_amount`，且 `unsettled + settled = actual_income` 自洽。真正的问题**只是 key 冗余**。
- 因此本单的价值不在「补定义」，而在「消除两个 key 造成的误读」，以及把「工作台展示的是应收还是实收」这层区分显式化。
