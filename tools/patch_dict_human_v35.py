# -*- coding: utf-8 -*-
"""把指标字典人读面的 V3.5 章节对齐到机器面实际登记的 15 条。

用法：python tools/patch_dict_human_v35.py
幂等：重复执行不重复改。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DICT_DOC = ROOT / "docs/spec/metric-dictionary.md"

START = "### 2.6 平台运营驾驶舱（V3.5）"
END = "\n---\n\n## 三、区间约定（所有指标共用）"

NEW_SECTION = """### 2.6 平台运营驾驶舱（V3.5）

> 管理端口径与陪诊员端 / 家属端**共用同一批计算实现**（决策 6.2）：
> 同一个指标只有一个公式，绝不因为「哪个端点」而写第二遍。
>
> ⚠️ 本节**逐条对应机器面** `metrics/metric-dictionary.yml` 里已登记的 15 条
> （`operations.*`）。人读面与机器面**必须一致** —— 两边对不上就是口径漂移本身。

#### 订单与履约

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.today_order_count` | 今日订单数 | 今日新增的陪诊订单数 | **当天**（按 `create_time`，不随统计区间浮动） | `companion_order(create_time)` | `COUNT(*)` | 1 |
| `operations.completed_order_count` | 区间完成订单数 | 统计区间内「已完成」的陪诊订单数 | 统计区间 | `companion_order(status, visit_time)` | `COUNT(status IN ('COMPLETED','REVIEWED'))` | 1 |
| `operations.in_service_count` | 区间服务中订单数 | 统计区间内处于服务中的订单数 | 统计区间 | `companion_order(status, visit_time)` | `COUNT(status = 'IN_SERVICE')` | 1 |
| `operations.pending_count` | 区间待接单订单数 | 统计区间内仍待接单的订单数 | 统计区间 | `companion_order(status, visit_time)` | `COUNT(status = 'PENDING')` | 1 |
| `operations.fulfillment_rate` | 履约率 | 统计区间内订单的最终履约比例 | 统计区间 | `companion_order(status, visit_time)` | `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)` | 1 |
| `operations.completion_rate` | 完成率（按时） | 统计区间内**已完成**订单里「没超时」的比例 | 统计区间 | `companion_order(status, visit_time, finish_time)` | 按时完成单数 / 已完成单数；「按时」= `finish_time ≤ visit_time + 履约时长窗口`（`nianglin.operations.fulfillment-window-minutes`，默认 240 分钟） | 1 |
| `operations.avg_accept_minutes` | 平均接单时间 | 统计区间内已接单订单的接单耗时 | 统计区间 | `companion_order(create_time, accept_time)` | `AVG(TIMESTAMPDIFF(MINUTE, create_time, accept_time))`，仅 `accept_time` 非空 | 1 |

#### 服务与陪诊员

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.complaint_rate` | 投诉率 | 统计区间内被投诉的订单占比 | 统计区间 | `complaint` + `companion_order` | 投诉订单数 / 区间订单数 | 1 |
| `operations.average_score` | 平均评分 | 统计区间内有效评价的平均分 | 统计区间 | `order_review(score, is_valid, create_time)` | `AVG(score)`，仅 `is_valid = 1` | 1 |
| `operations.active_companion_count` | 活跃陪诊员数 | 统计区间内有过接单的陪诊员人数（去重） | 统计区间 | `companion_order(companion_id, visit_time)` | `COUNT(DISTINCT companion_id)` | 1 |

#### 异常中心

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.exception_manual_required_count` | 需人工介入订单数 | 当前处于人工介入阈值的待接单订单数 | **当前状态**（不受统计区间影响） | `companion_order(fulfillment_risk, status)` | `COUNT(fulfillment_risk = 'MANUAL_REQUIRED' AND status = 'PENDING')` | 1 |
| `operations.exception_match_warning_count` | 超匹配预警时间订单数 | 当前处于预警 / 紧急阈值的待接单订单数 | **当前状态** | 同上 | `COUNT(fulfillment_risk IN ('WARNING','URGENT') AND status = 'PENDING')` | 1 |
| `operations.exception_complaint_rate_count` | 投诉率异常陪诊员数 | 回溯期内投诉率超过阈值的陪诊员人数 | 回溯期（默认 30 天） | `complaint` + `companion_order` | 投诉率 > `nianglin.operations.complaint-rate-threshold`（默认 20%）且接单量 ≥ 最小样本 | 1 |
| `operations.exception_medication_missed_count` | 用药漏服异常数 | 统计区间内漏服桶的任务数 | 统计区间 | `medication_task(status, was_missed, plan_time)` | 漏服桶计数（`MedicationBucketSql` 唯一真源，与服药日历同源） | 1 |

#### 趋势

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.trend.series_value` | 趋势序列值 | 运营趋势各维度按天/周/月分桶的序列 | 统计区间 | `companion_order` + `complaint` + `order_review` + `medication_task` | 按 `dimension` 取值：订单量 / 陪诊员活跃数 / 履约率 / 投诉数 / 评价数 / 用药任务数 | 1 |

#### 三条必须记住的规则

1. **异常中心的前两项与 V3.1 风险中心同源**（决策 5.1）：
   `manualRequired` 与 `warning + urgent` 必须与
   `GET /api/admin/fulfillment/summary` 的字段**完全一致**。
   异常中心**聚合**风险中心，**不另建一套风险判定** ——
   两套判定必然漂移，这正是 V3.2 在用药三桶上踩过的坑。
   该同源关系由 `OperationsExceptionParityTest` 锁死。
2. **履约率与完成率（按时）不是同一个数**（偏差 D-3.5-1）：
   履约率看「最终有没有履约完」，完成率看「完成得及不及时」。
   两个名字一个数是口径分裂的另一种形态，同样禁止。
3. **同一个指标只有一个公式**：`operations.fulfillment_rate` 同时出现在
   概览与健康度两个出口，**共用同一个计算实现**（决策 6.2）。
   两处各写一遍公式，迟早漂移。

> **待登记（V3.6 补登）**：超时率 / 人工介入率 / 改派率 / 平均服务时长 /
> 陪诊员数 / 用药任务数 / 漏服率 —— 这几条的公式尚未在机器面登记，
   留到 V3.6「指标字典校验与补全」时统一处理（见 `docs/spec/V3.6-tickets.md` T6）。

"""


def main():
    text = DICT_DOC.read_text(encoding="utf-8")
    start = text.find(START)
    end = text.find(END)
    if start < 0 or end < 0 or end < start:
        print("找不到 V3.5 章节，跳过", file=sys.stderr)
        return 1
    text = text[:start] + NEW_SECTION + text[end:]

    # 变更记录条数对齐机器面
    text = text.replace(
        "| 2026-10-20 | v1.3 | 登记 V3.5 运营驾驶舱的 20 条管理端指标（订单/履约/服务/陪诊员/异常五域） | 无 —— 新指标首次登记 |",
        "| 2026-10-20 | v1.3 | 登记 V3.5 运营驾驶舱的 **15** 条管理端指标（订单/履约/服务/陪诊员/异常/趋势六域），与机器面逐条对齐 | 超时率 / 人工介入率 / 改派率等留给 V3.6 补登 |",
        1,
    )
    DICT_DOC.write_text(text, encoding="utf-8")
    print("指标字典人读面已对齐到机器面的 15 条")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
