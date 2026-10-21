# -*- coding: utf-8 -*-
"""从指标字典**机器面**生成人读面的 V3.6 章节（§2.7），杜绝两边漂移。

用法：python tools/gen_metric_dict_human_v36.py

人读面里「指标表」这一段是**生成**出来的，不是手写的 ——
手写就意味着迟早与机器面对不上（V3.5 修过的那次真实漂移就是这么来的）。

零依赖：机器面用 `tools/_metric_dict_reader.py` 的极小读取器解析，
不 import yaml —— CI 的 job 里没有承诺装 PyYAML。
"""
import io
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _metric_dict_reader import load_metrics  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/spec/metric-dictionary.md"

START = "### 2.7 数据闭环补登（V3.6）"
END = "\n---\n\n## 三、区间约定（所有指标共用）"

# 本节收录的指标前缀（V3.6 新登记的）
PREFIXES = ("operations.gap.",)
EXACT = {
    "operations.timeout_rate",
    "operations.manual_intervention_rate",
    "operations.reassign_rate",
    "operations.service_duration_minutes",
    "operations.companion_count",
    "operations.medication_task_count",
    "operations.medication_missed_rate",
}

HEADER = """### 2.7 数据闭环补登（V3.6）

> V3.6 的第一张票是**校验与补全**指标字典（决策 2.8 已把字典从 V3.6 提前到 V3.2 建好）。
> 下列指标是迭代计划 §48 清单里**此前缺失、但已有可算口径**的部分 ——
> **只为已有业务事实补登记，不新造业务事实**。
>
> ⚠️ **本节的表格由 `tools/gen_metric_dict_human_v36.py` 从机器面直接生成**，
> 不要手改 —— 手改就会与机器面漂移（V3.5 修过的那次真实漂移就是这么来的）。
> 两边的 key 集合与「数据来源」口径由 `tools/check_metric_dict_parity.py` 机械校验。

#### 供给缺口分析

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
"""

TAIL = """
#### 其它补登

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
"""

# 变更记录里的 v1.4 行 —— 与 docs/spec/metric-dictionary.md 必须逐字一致，
# 否则这段正则会匹配不上（正则里 `**\d+**` 是条数占位，不是通配）。
CHANGE_ROW = (
    "| 2026-10-20 | v1.4 | 登记 V3.6 数据闭环的 **11** 条指标"
    "（供给缺口 4 条 + 其它补登 7 条），本节表格由脚本从机器面生成 "
    "| 超时率 / 人工介入率 / 改派率等补齐迭代计划 §48 清单；"
    "§2.6 运营端 5 条指标的「数据来源」勘误为 `create_time`"
    "（机器面与 SQL 一直是 `create_time`，是人读面滞后） |"
)


def render(metrics):
    def row(m):
        return "| `{key}` | {name} | {scope} | {period} | {source} | {formula} | {v} |".format(
            key=m["key"], name=m["name"], scope=m["scope"], period=m["period"],
            source=m["source"], formula=m["formula"], v=m.get("caliberVersion", 1),
        )

    gap = [m for m in metrics if m["key"].startswith(PREFIXES)]
    other = [m for m in metrics if m["key"] in EXACT]
    out = HEADER + "\n".join(row(m) for m in gap) + "\n" + TAIL + "\n".join(row(m) for m in other) + "\n"
    return out


def main():
    metrics = list(load_metrics().values())
    section = render(metrics)

    text = DOC.read_text(encoding="utf-8")
    start = text.find(START)
    end = text.find(END)
    if start < 0 or end < 0 or end < start:
        print("找不到 V3.6 章节锚点", file=sys.stderr)
        return 1
    text = text[:start] + section + text[end:]

    # 变更记录：条数与机器面对齐
    text = re.sub(
        r"\| 2026-10-20 \| v1\.4 \| 登记 V3\.6 数据闭环的 \*\*\d+\*\* 条指标[^|]*\|[^|]*\|",
        CHANGE_ROW.replace("\\", "\\\\"),
        text,
    )
    DOC.write_text(text, encoding="utf-8")
    print("指标字典人读面 V3.6 章节已按机器面重新生成")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
