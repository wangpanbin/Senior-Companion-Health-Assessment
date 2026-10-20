# -*- coding: utf-8 -*-
"""往指标字典机器面追加 V3.4 智能调度的匹配指标与 VO 声明。

用法：python tools/patch_metric_dict_v34.py
幂等：重复执行不会重复插入。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
YML = ROOT / "backend/src/main/resources/metrics/metric-dictionary.yml"

METRICS_BLOCK = """  # ==================== 智能调度匹配（V3.4） ====================
  # 维度得分是「0–1 的原始分 × 100 后的整数」，加权总分 = Σ(维度分 × 权重)。
  # ⚠️ 权重配置化于 nianglin.matching.*.weight，且启动期校验权重之和 = 1。
  - key: matching.total_score
    name: 匹配总分
    scope: 候选陪诊员对某个订单的加权匹配总分（五维加权）
    period: 单次匹配（订单与候选人的配对，无时间区间）
    source: 匹配规则引擎（companion_profile / companion_order / order_checkin / companion_available_slot）
    formula: "Σ(维度分 × 权重) × 100，权重：区域 30% / 时间 25% / 距离 20% / 服务能力 15% / 评分 10%（配置化于 nianglin.matching.*.weight）"
    caliberVersion: 1
    endpoints:
      - "GET /api/order/{id}/recommend-companions"
    bindings:
      - vo: CompanionMatchVO
        field: totalScore

  - key: matching.region_score
    name: 区域维度得分
    scope: 候选陪诊员与订单的服务区域匹配程度
    period: 单次匹配
    source: companion_profile(service_region_code, service_area) + companion_order(service_region_code, address)
    formula: "区域码相等 = 100；同市不同区 = 50；跨市 = 0；缺区域码时回退自由文本包含式并降级标注"
    caliberVersion: 1
    endpoints:
      - "GET /api/order/{id}/recommend-companions"
    bindings:
      - vo: CompanionMatchVO
        field: regionScore

  - key: matching.time_score
    name: 时间维度得分
    scope: 订单就诊时间与候选陪诊员可服务时段的重合程度
    period: 单次匹配
    source: companion_available_slot(weekday, start_time, end_time) + companion_profile(available_time)
    formula: "就诊时间落在可服务时段内 = 100；同日但不在时段内 = 50；其它 = 0；无可服务时段时记 0 并降级标注"
    caliberVersion: 1
    endpoints:
      - "GET /api/order/{id}/recommend-companions"
    bindings:
      - vo: CompanionMatchVO
        field: timeScore

  - key: matching.distance_score
    name: 距离维度得分
    scope: 候选陪诊员与订单地址的直线距离匹配程度
    period: 单次匹配
    source: companion_profile(longitude, latitude) + companion_order(longitude, latitude)
    formula: "(1 - Haversine距离 / 满量程) × 100，满量程 nianglin.matching.distance-max-meters（默认 30000 米）；陪诊员无坐标时记 0 并标注「未填写坐标」，绝不按 (0,0) 推断"
    caliberVersion: 1
    endpoints:
      - "GET /api/order/{id}/recommend-companions"
    bindings:
      - vo: CompanionMatchVO
        field: distanceScore

  - key: matching.skill_score
    name: 服务能力维度得分
    scope: 候选陪诊员在订单科室上的历史服务能力
    period: 单次匹配（数据来自历史累计）
    source: companion_order(companion_id, department, status)
    formula: "该陪诊员在该科室的已完成单数 / 满量程 × 100，满量程 nianglin.matching.skill-max-orders（默认 20 单），上限 100"
    caliberVersion: 1
    endpoints:
      - "GET /api/order/{id}/recommend-companions"
    bindings:
      - vo: CompanionMatchVO
        field: skillScore

  - key: matching.rating_score
    name: 评分维度得分
    scope: 候选陪诊员的平均评分归一化后的得分
    period: 单次匹配（数据来自历史累计）
    source: companion_profile(score, user_id)
    formula: "score / 5 × 100；无有效评分时记 0 并降级标注"
    caliberVersion: 1
    endpoints:
      - "GET /api/order/{id}/recommend-companions"
    bindings:
      - vo: CompanionMatchVO
        field: ratingScore

"""

VOS_BLOCK = """
  - class: OrderRecommendVO
    structural:
      - orderId
      - total
      - candidates

  - class: CompanionMatchVO
    structural:
      - companionId
      - companionName
      - reasons
      - notes
"""


def main() -> int:
    text = YML.read_text(encoding="utf-8")

    if "matching.total_score" in text:
        print("已存在 V3.4 指标，跳过")
        return 0

    # 版本号与更新时间
    text = re.sub(r"^version: \d+$", "version: 3", text, count=1, flags=re.M)
    text = re.sub(r'^updatedAt: ".*"$', 'updatedAt: "2026-10-20"', text, count=1, flags=re.M)

    marker = "\nvos:\n"
    if marker not in text:
        print("未找到 vos: 段，终止", file=sys.stderr)
        return 1
    text = text.replace(marker, "\n" + METRICS_BLOCK + "vos:\n", 1)

    text = text.rstrip("\n") + "\n" + VOS_BLOCK
    YML.write_text(text, encoding="utf-8")
    print("指标字典已追加 V3.4 匹配指标与 VO 声明")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
