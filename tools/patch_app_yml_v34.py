# -*- coding: utf-8 -*-
"""往 application.yml 追加 nianglin.matching 配置段（V3.4）。

用法：python tools/patch_app_yml_v34.py
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
YML = ROOT / "backend/src/main/resources/application.yml"

ANCHOR = "    hall-default-sort: RECOMMEND\n"

BLOCK = """
  # 智能调度（V3.4 · T3）—— 匹配规则引擎的权重与量程
  #
  # ⚠️ 五个权重之和必须等于 1，否则应用启动失败（MatchingProperties#validate）。
  #    错误的权重不会报错，只会让排序悄悄变得不合理 —— 那是最难定位的一类故障，
  #    所以让它在启动的十秒内就暴露，而不是等运营抱怨「推荐不准」。
  matching:
    # 区域匹配权重：区域码相等 1.0 / 同市不同区 0.5 / 跨市 0
    region-weight: 0.30
    # 时间匹配权重：就诊时间落在可服务时段内 1.0 / 同日不同时段 0.5 / 其它 0
    time-weight: 0.25
    # 距离权重：Haversine 距离 / 满量程；陪诊员无坐标时该维度记 0 且不推断
    distance-weight: 0.20
    # 服务能力权重：该陪诊员在该科室的已完成单数 / 满量程
    skill-weight: 0.15
    # 评分权重：陪诊员平均评分 / 5
    rating-weight: 0.10
    # 距离归一化的满量程（米）：距离达到该值时得分归 0
    distance-max-meters: 30000
    # 服务能力归一化的满量程（单）：该科室完成数达到该值时得分归 1
    skill-max-orders: 20
    # 大厅候选集上限（超出时记 warn 日志，不静默截断）
    hall-candidate-limit: 500
    # 候选推荐端点的默认 / 最大返回条数
    recommend-limit: 10
    recommend-max-limit: 20
    # 档期冲突判定的时间窗（分钟）：一次陪诊从就诊到结束约 2–3 小时
    conflict-window-minutes: 180
"""


def main():
    text = YML.read_text(encoding="utf-8")
    if "matching:" in text:
        print("已存在 nianglin.matching 段，跳过")
        return 0
    anchor = ANCHOR.replace("\n", "\r\n") if "\r\n" in text else ANCHOR
    if anchor not in text:
        print("未找到锚点 hall-default-sort", file=sys.stderr)
        return 1
    text = text.replace(anchor, anchor + BLOCK.replace("\n", "\r\n" if "\r\n" in text else "\n"), 1)
    YML.write_text(text, encoding="utf-8")
    print("已写入 nianglin.matching 配置段")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
