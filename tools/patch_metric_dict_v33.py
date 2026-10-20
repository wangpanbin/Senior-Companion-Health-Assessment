# -*- coding: utf-8 -*-
"""往指标字典机器面追加 V3.3 陪诊员工作台的指标与 VO 声明。

用法：python tools/patch_metric_dict_v33.py
幂等：重复执行不会重复插入（以 key 存在与否判断）。
"""
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
YML = ROOT / "backend/src/main/resources/metrics/metric-dictionary.yml"

METRICS_BLOCK = """  # ==================== 陪诊员工作台（V3.3） ====================
  # 区间口径：统计区间默认 30 天、最多 366 天、闭区间；
  # 累计口径：历史累计，不随统计区间浮动。
  # ⚠️ 区间口径的订单一律按 visit_time 过滤（与 V3.2 家属端 elder.visit.order_count 同一时间字段）。
  - key: companion.workbench.completed_order_count
    name: 区间完成单数
    scope: 当前陪诊员在统计区间内「已完成」的陪诊订单数
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: companion_order(companion_id, status, visit_time)
    formula: "COUNT(status IN ('COMPLETED','REVIEWED'))"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me"
    bindings:
      - vo: CompanionWorkbenchOverviewVO
        field: completedOrderCount

  - key: companion.workbench.service_duration
    name: 区间服务时长（分钟）
    scope: 当前陪诊员在统计区间内「已完成」订单的服务时长合计
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: companion_order(companion_id, status, start_time, finish_time)
    formula: "SUM(TIMESTAMPDIFF(MINUTE, start_time, finish_time))，仅 start_time 与 finish_time 均非空"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me"
    bindings:
      - vo: CompanionWorkbenchOverviewVO
        field: serviceDurationMinutes

  - key: companion.workbench.completion_rate
    name: 区间完成率
    scope: 当前陪诊员在统计区间内的完成率
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: companion_order(companion_id, status, visit_time)
    formula: "(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)，拒单不计入分母"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me"
    bindings:
      - vo: CompanionWorkbenchOverviewVO
        field: completionRate

  - key: companion.workbench.period_income
    name: 区间收入
    scope: 当前陪诊员在统计区间内「已完成」订单的结算金额合计
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: companion_order(companion_id, status, fee, actual_fee, visit_time)
    formula: "SUM(COALESCE(actual_fee, fee))，仅 COMPLETED + REVIEWED"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me"
      - "GET /api/statistics/companion/me/income"
    bindings:
      - vo: CompanionWorkbenchOverviewVO
        field: periodIncome

  - key: companion.average_score
    name: 平均评分
    scope: 当前陪诊员的历史累计平均评分（评价聚合后的冗余字段，不重算）
    period: 历史累计（不随统计区间浮动）
    source: companion_profile(score, user_id)
    formula: "companion_profile.score（由 ReviewServiceImpl#refreshCompanionScore 在评价提交时于事务内刷新）"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me"
      - "GET /api/statistics/companion/me/profile"
    bindings:
      - vo: CompanionWorkbenchOverviewVO
        field: averageScore
      - vo: CompanionServiceProfileVO
        field: averageScore

  - key: companion.trend.series_value
    name: 趋势序列值
    scope: 当前陪诊员在统计区间内按天/周/月分桶的指标序列
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: companion_order(visit_time, status, fee, actual_fee) + order_review(create_time, score)
    formula: "按 dimension 取值：ORDER=区间完成单数 / INCOME=区间收入 / RATING=区间内有效评价的平均分（按条数加权）"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/trend"
    bindings:
      - vo: CompanionTrendVO$Series
        field: data

  - key: companion.profile.total_completed_order_count
    name: 累计完成单数
    scope: 当前陪诊员历史累计「已完成」的陪诊订单数
    period: 历史累计（不随统计区间浮动）
    source: companion_order(companion_id, status)
    formula: "COUNT(status IN ('COMPLETED','REVIEWED'))"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/profile"
    bindings:
      - vo: CompanionServiceProfileVO
        field: totalCompletedOrderCount

  - key: companion.profile.total_served_elder_count
    name: 累计服务老人数
    scope: 当前陪诊员历史累计服务过的不同老人数（去重）
    period: 历史累计（不随统计区间浮动）
    source: companion_order(companion_id, elder_id, status)
    formula: "COUNT(DISTINCT elder_id WHERE status IN ('COMPLETED','REVIEWED'))"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/profile"
    bindings:
      - vo: CompanionServiceProfileVO
        field: totalServedElderCount

  - key: companion.profile.punctuality_rate
    name: 累计准时率
    scope: 当前陪诊员历史累计的准时到院比例
    period: 历史累计（不随统计区间浮动）
    source: order_checkin(node, checkin_time) JOIN companion_order(visit_time, accept_time)
    formula: "分子=存在 ARRIVE 打卡且 checkin_time <= visit_time 的订单数；分母=已接单订单数（accept_time 非空）；无 ARRIVE 打卡视为不准时"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/profile"
    bindings:
      - vo: CompanionServiceProfileVO
        field: punctualityRate

  - key: companion.profile.total_completion_rate
    name: 累计完成率
    scope: 当前陪诊员历史累计的完成率
    period: 历史累计（不随统计区间浮动）
    source: companion_order(companion_id, status)
    formula: "(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)，拒单不计入分母"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/profile"
    bindings:
      - vo: CompanionServiceProfileVO
        field: totalCompletionRate

  - key: companion.profile.total_service_duration
    name: 累计服务时长（分钟）
    scope: 当前陪诊员历史累计「已完成」订单的服务时长合计
    period: 历史累计（不随统计区间浮动）
    source: companion_order(companion_id, status, start_time, finish_time)
    formula: "SUM(TIMESTAMPDIFF(MINUTE, start_time, finish_time))，仅 start_time 与 finish_time 均非空"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/profile"
    bindings:
      - vo: CompanionServiceProfileVO
        field: totalServiceDurationMinutes

  - key: companion.profile.expertise_order_count
    name: 擅长服务单数
    scope: 当前陪诊员在某个科室上「已完成」的订单数（擅长服务标签的取值来源）
    period: 历史累计（不随统计区间浮动）
    source: companion_order(companion_id, department, status)
    formula: "COUNT(*) GROUP BY department，样本 < 5 单的科室不展示，最多展示 TOP 5"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/profile"
    bindings:
      - vo: CompanionExpertiseVO
        field: orderCount

  - key: companion.income.order_amount
    name: 区间订单金额
    scope: 当前陪诊员在统计区间内「已完成」订单的合同服务费合计
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: companion_order(companion_id, fee, status, visit_time)
    formula: "SUM(fee)，仅 COMPLETED + REVIEWED"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/income"
    bindings:
      - vo: CompanionIncomeVO
        field: orderAmount

  - key: companion.income.actual_income
    name: 区间实际收入
    scope: 当前陪诊员在统计区间内「已完成」订单的结算金额合计
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: companion_order(companion_id, fee, actual_fee, status, visit_time)
    formula: "SUM(COALESCE(actual_fee, fee))，仅 COMPLETED + REVIEWED"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/income"
    bindings:
      - vo: CompanionIncomeVO
        field: actualIncome

  - key: companion.income.unsettled_amount
    name: 区间待结算金额
    scope: 当前陪诊员在统计区间内「已完成」且尚未线下结算的金额合计
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: companion_order(companion_id, fee, actual_fee, payment_status, status, visit_time)
    formula: "SUM(COALESCE(actual_fee, fee)) WHERE payment_status = 'UNPAID'"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/income"
    bindings:
      - vo: CompanionIncomeVO
        field: unsettledAmount

  - key: companion.income.settled_amount
    name: 区间已结算金额
    scope: 当前陪诊员在统计区间内「已完成」且已完成线下结算的金额合计
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: companion_order(companion_id, fee, actual_fee, payment_status, status, visit_time)
    formula: "SUM(COALESCE(actual_fee, fee)) WHERE payment_status = 'SETTLED'"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/income"
    bindings:
      - vo: CompanionIncomeVO
        field: settledAmount

  - key: companion.income.advance_total
    name: 区间代垫合计
    scope: 当前陪诊员在统计区间内「已完成」订单上代垫的费用合计（家属需还给陪诊员）
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: order_fee_item(item_type, amount) JOIN companion_order(companion_id, status, visit_time)
    formula: "SUM(amount) WHERE item_type = 'ADVANCE'"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/income"
    bindings:
      - vo: CompanionIncomeVO
        field: advanceTotal

  - key: companion.income.service_total
    name: 区间服务费合计
    scope: 当前陪诊员在统计区间内「已完成」订单上记账的服务费合计
    period: 统计区间（默认 30 天、最多 366 天、闭区间）
    source: order_fee_item(item_type, amount) JOIN companion_order(companion_id, status, visit_time)
    formula: "SUM(amount) WHERE item_type = 'SERVICE'"
    caliberVersion: 1
    endpoints:
      - "GET /api/statistics/companion/me/income"
    bindings:
      - vo: CompanionIncomeVO
        field: serviceTotal

"""

VOS_BLOCK = """
  - class: CompanionWorkbenchOverviewVO
    structural:
      - periodStart
      - periodEnd

  - class: CompanionTrendVO
    structural:
      - dimension
      - granularity
      - periodStart
      - periodEnd
      - categories
      - series

  - class: CompanionTrendVO$Series
    structural:
      - name

  - class: CompanionServiceProfileVO
    structural:
      - expertise

  - class: CompanionExpertiseVO
    structural:
      - department

  - class: CompanionIncomeVO
    structural:
      - periodStart
      - periodEnd
"""


def main() -> int:
    text = YML.read_text(encoding="utf-8")

    if "companion.workbench.completed_order_count" in text:
        print("已存在 V3.3 指标，跳过")
        return 0

    # 1) 版本号与更新时间
    text = re.sub(r"^version: \d+$", "version: 2", text, count=1, flags=re.M)
    text = re.sub(r'^updatedAt: ".*"$', 'updatedAt: "2026-10-20"', text, count=1, flags=re.M)

    # 2) 指标块插到 vos: 之前
    marker = "\nvos:\n"
    if marker not in text:
        print("未找到 vos: 段，终止", file=sys.stderr)
        return 1
    text = text.replace(marker, "\n" + METRICS_BLOCK + "vos:\n", 1)

    # 3) VO 段追加到文件末尾
    text = text.rstrip("\n") + "\n" + VOS_BLOCK

    YML.write_text(text, encoding="utf-8")
    print("指标字典已追加 V3.3 指标与 VO 声明")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
