# -*- coding: utf-8 -*-
"""同步 V3.3 陪诊员工作台的两处文档：
1) docs/api/README.md —— 访问矩阵里补上 /api/statistics/companion/** 的 COMPANION 列；
2) docs/spec/metric-dictionary.md —— 人读面补 V3.3 的指标表与变更记录。

用法：python tools/patch_docs_v33.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API_README = ROOT / "docs/api/README.md"
DICT_DOC = ROOT / "docs/spec/metric-dictionary.md"

# 访问矩阵：统计类接口现在分成 ADMIN 段与 COMPANION 段
OLD_ROW = "| 数据统计 `/api/statistics/**` | — | — | — | 全部 |"
NEW_ROW = (
    "| 数据统计 `/api/statistics/**`（ADMIN 段） | — | — | — | 全部 |\n"
    "| 陪诊员工作台统计 `/api/statistics/companion/**` | — | — | **读（仅本人，companionId 入参被忽略）** | — |"
)

# 人读面新增章节
DICT_SECTION = """
### 2.4 陪诊员工作台（V3.3）

> ⚠️ 本节指标**分两套口径**：带「区间」的是统计区间内，带「累计」的是历史累计。
> 字段名自带口径（`total` 前缀 = 累计），接口契约见
> [`docs/api/09-statistics-export.md` §8](../api/09-statistics-export.md)。

#### 区间口径

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `companion.workbench.completed_order_count` | 区间完成单数 | 当前陪诊员在统计区间内「已完成」的陪诊订单数 | 统计区间 | `companion_order(companion_id, status, visit_time)` | `COUNT(status IN ('COMPLETED','REVIEWED'))` | 1 |
| `companion.workbench.service_duration` | 区间服务时长（分钟） | 当前陪诊员在统计区间内「已完成」订单的服务时长合计 | 统计区间 | `companion_order(companion_id, status, start_time, finish_time)` | `SUM(TIMESTAMPDIFF(MINUTE, start_time, finish_time))`，仅两端均非空 | 1 |
| `companion.workbench.completion_rate` | 区间完成率 | 当前陪诊员在统计区间内的完成率 | 统计区间 | `companion_order(companion_id, status, visit_time)` | `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)`，**拒单不计入分母** | 1 |
| `companion.workbench.period_income` | 区间收入 | 当前陪诊员在统计区间内「已完成」订单的结算金额合计 | 统计区间 | `companion_order(companion_id, status, fee, actual_fee, visit_time)` | `SUM(COALESCE(actual_fee, fee))` | 1 |
| `companion.trend.series_value` | 趋势序列值 | 当前陪诊员在统计区间内按天/周/月分桶的指标序列 | 统计区间 | `companion_order` + `order_review` | 按 `dimension` 取 ORDER=完成单数 / INCOME=区间收入 / RATING=有效评价平均分（**按条数加权**） | 1 |
| `companion.income.order_amount` | 区间订单金额 | 区间内「已完成」订单的合同服务费合计 | 统计区间 | `companion_order(companion_id, fee, status, visit_time)` | `SUM(fee)` | 1 |
| `companion.income.actual_income` | 区间实际收入 | 区间内「已完成」订单的结算金额合计 | 统计区间 | `companion_order(companion_id, fee, actual_fee, status, visit_time)` | `SUM(COALESCE(actual_fee, fee))` | 1 |
| `companion.income.unsettled_amount` | 区间待结算金额 | 区间内「已完成」且未线下结算的金额合计 | 统计区间 | `companion_order(..., payment_status)` | `SUM(COALESCE(actual_fee, fee)) WHERE payment_status = 'UNPAID'` | 1 |
| `companion.income.settled_amount` | 区间已结算金额 | 区间内「已完成」且已线下结算的金额合计 | 统计区间 | `companion_order(..., payment_status)` | `SUM(COALESCE(actual_fee, fee)) WHERE payment_status = 'SETTLED'` | 1 |
| `companion.income.advance_total` | 区间代垫合计 | 区间内「已完成」订单上代垫的费用合计 | 统计区间 | `order_fee_item(item_type, amount)` JOIN `companion_order` | `SUM(amount) WHERE item_type = 'ADVANCE'` | 1 |
| `companion.income.service_total` | 区间服务费合计 | 区间内「已完成」订单上记账的服务费合计 | 统计区间 | `order_fee_item(item_type, amount)` JOIN `companion_order` | `SUM(amount) WHERE item_type = 'SERVICE'` | 1 |

#### 累计口径

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `companion.average_score` | 平均评分 | 当前陪诊员的历史累计平均评分 | 历史累计 | `companion_profile(score, user_id)` | `companion_profile.score`（评价提交时于事务内刷新，**不重算**） | 1 |
| `companion.profile.total_completed_order_count` | 累计完成单数 | 当前陪诊员历史累计「已完成」的陪诊订单数 | 历史累计 | `companion_order(companion_id, status)` | `COUNT(status IN ('COMPLETED','REVIEWED'))` | 1 |
| `companion.profile.total_served_elder_count` | 累计服务老人数 | 当前陪诊员历史累计服务过的不同老人数 | 历史累计 | `companion_order(companion_id, elder_id, status)` | `COUNT(DISTINCT elder_id WHERE status IN ('COMPLETED','REVIEWED'))` | 1 |
| `companion.profile.punctuality_rate` | 累计准时率 | 当前陪诊员历史累计的准时到院比例 | 历史累计 | `order_checkin(node, checkin_time)` JOIN `companion_order(visit_time, accept_time)` | 分子=有 `ARRIVE` 打卡且 `checkin_time <= visit_time` 的订单数；分母=已接单订单数；**无打卡视为不准时** | 1 |
| `companion.profile.total_completion_rate` | 累计完成率 | 当前陪诊员历史累计的完成率 | 历史累计 | `companion_order(companion_id, status)` | `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)` | 1 |
| `companion.profile.total_service_duration` | 累计服务时长（分钟） | 当前陪诊员历史累计「已完成」订单的服务时长合计 | 历史累计 | `companion_order(companion_id, status, start_time, finish_time)` | `SUM(TIMESTAMPDIFF(MINUTE, start_time, finish_time))` | 1 |
| `companion.profile.expertise_order_count` | 擅长服务单数 | 当前陪诊员在某科室上「已完成」的订单数 | 历史累计 | `companion_order(companion_id, department, status)` | `COUNT(*) GROUP BY department`；**样本 < 5 单的科室不展示**，最多 TOP 5 | 1 |

#### 两条容易踩的坑

1. **区间按 `visit_time` 过滤，不是 `create_time`。**
   `elder.visit.order_count`（V3.2 家属端）与 `companion.workbench.completed_order_count`
   （本版陪诊员端）数的是**同一批已完成订单**，两端必须用同一个时间字段 ——
   否则决策 6.2「同一指标只有一个口径」就破了。

2. **`companion.profile.order_count` 不读。**
   该字段只在建档时置 0（`CompanionServiceImpl`），运行期没有任何代码维护它 ——
   只有种子脚本 `V3__seed_boundary.sql` 手工同步过。读它会得到一个永远是 0 的数。
   所以累计完成单数一律从 `companion_order` 现算。
   （`companion_profile.score` 不一样：它在评价提交时于事务内刷新，是维护得住的，所以沿用。）

---
"""

OLD_RECORD = """| 2026-10-20 | v1.0 | 首次建立。登记 V3.2 的 12 条指标 | — |"""

NEW_RECORD = """| 2026-10-20 | v1.0 | 首次建立。登记 V3.2 的 12 条指标 | — |
| 2026-10-20 | v1.1 | 登记 V3.3 陪诊员工作台的 18 条指标（区间 11 条 + 累计 7 条） | `companion_profile.order_count` 不再作为来源（运行期无人维护），累计完成单数改从 `companion_order` 现算 |"""


def main() -> int:
    ok = True

    # ---- 1) API README 访问矩阵 ----
    api_text = API_README.read_text(encoding="utf-8")
    if "/api/statistics/companion/**" in api_text:
        print("API README：V3.3 行已存在，跳过")
    elif OLD_ROW in api_text:
        api_text = api_text.replace(OLD_ROW, NEW_ROW, 1)
        API_README.write_text(api_text, encoding="utf-8")
        print("API README：访问矩阵已补 V3.3 行")
    else:
        print("API README：未找到锚点", file=sys.stderr)
        ok = False

    # ---- 2) 指标字典人读面 ----
    dict_text = DICT_DOC.read_text(encoding="utf-8")
    if "### 2.4 陪诊员工作台（V3.3）" in dict_text:
        print("指标字典：V3.3 章节已存在，跳过")
    else:
        anchor = "\n---\n\n## 三、区间约定（所有指标共用）"
        if anchor not in dict_text:
            print("指标字典：未找到章节锚点", file=sys.stderr)
            ok = False
        else:
            dict_text = dict_text.replace(anchor, "\n" + DICT_SECTION + "\n## 三、区间约定（所有指标共用）", 1)

    if OLD_RECORD in dict_text:
        dict_text = dict_text.replace(OLD_RECORD, NEW_RECORD, 1)
    else:
        print("指标字典：未找到变更记录锚点", file=sys.stderr)
        ok = False

    dict_text = dict_text.replace("> **版本**：v1.0 · 2026-10-20", "> **版本**：v1.1 · 2026-10-20", 1)
    DICT_DOC.write_text(dict_text, encoding="utf-8")
    print("指标字典：人读面已补 V3.3")

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
