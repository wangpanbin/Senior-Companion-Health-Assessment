# -*- coding: utf-8 -*-
"""把 V3.3 – V3.6 的交付状态回写到总纲 docs/spec/V3.x-roadmap-spec.md。

用法：python tools/patch_roadmap_delivery_v33_v36.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/spec/V3.x-roadmap-spec.md"

NOTES = {
    # (锚点, 交付说明)
    "### 4.4 V3.4 · 智能调度": None,  # 特殊处理：锚点后面追加
}

V33_ANCHOR = "**依赖**：无强依赖。\n\n### 4.4 V3.4 · 智能调度"
V33_NOTE = """**依赖**：无强依赖。

> ✅ **V3.3 已于 2026-10-20 交付**。落地后的偏差见 [`V3.3-tickets.md`](./V3.3-tickets.md) §〇
> 与 [`V3.3-companion-workbench-spec.md`](./V3.3-companion-workbench-spec.md)。
> 开工时相对本总纲的四处**实质性调整**（均已回写至上表）：
> ① 「本月」措辞改为「统计区间」——「本月」是固定日历月，与可传参的区间不是一回事（决策 3.5 优先）；
> ② 概览（区间口径）与画像（累计口径）用**不同字段名**表达，避免接口层面埋口径分裂；
> ③ 收入页「累计收入」→「区间收入」——旧页的「累计」是拉全量订单 `reduce()` 算的，违反 G8 且无法用区间表达；
> ④ **累计完成单数不再读 `companion_profile.order_count`** —— 该字段运行期无人维护（只在建档时置 0），
> 读它会永远返回 0，改为从 `companion_order` 现算。

### 4.4 V3.4 · 智能调度"""

V34_ANCHOR = "**依赖**：候选人与排序输入依赖 **V3.1** 的工作状态与履约事件；替换 V3.1 的大厅排序。"
V34_NOTE = """**依赖**：候选人与排序输入依赖 **V3.1** 的工作状态与履约事件；替换 V3.1 的大厅排序。

> ✅ **V3.4 已于 2026-10-20 交付**。落地后的偏差见 [`V3.4-tickets.md`](./V3.4-tickets.md) §〇
> 与 [ADR-0016](../adr/0016-matching-structured-and-explainable.md)。
> 开工时相对本总纲的四处**实质性调整**（均已回写至上表）：
> ① 预写 spec §五的匹配模型**五个维度里有三个没有数据基础**（区域与时间是自由文本、距离无坐标）——
>    故本版**先做结构化改造（V7 迁移，expand–contract）再建规则引擎**（决策 4.3 补充）；
> ② 区域码采用「`<CITY>_<DISTRICT>`」两级，城市前缀即「区」这一层（码相等 1.0 / 同市不同区 0.5 / 跨市 0）；
> ③ 大厅 `RECOMMEND` 改为「Java 侧五维打分 + 内存分页」——
>    「无坐标 → 距离记 0 且不推断」需要一条分支，SQL 的 CASE 分不出「没数据」与「距离为零」；
> ④ `DISTANCE` / `TIME_MATCH` 开放，`INCOME` **仍 400**（缺真实结算金额口径，决策 D8）。
>
> **每条推荐都必须可解释**：各维度得分 + 命中理由（`reasons`）+ 降级说明（`notes`）三者缺一不可，
> 且「降级」与「不匹配」必须分开表达。"""

V35_ANCHOR = "**依赖**：强依赖 **V3.1**（异常中心的订单风险部分）。"
V35_NOTE = """**依赖**：强依赖 **V3.1**（异常中心的订单风险部分）。

> ✅ **V3.5 已于 2026-10-20 交付**。落地后的偏差见 [`V3.5-tickets.md`](./V3.5-tickets.md) §〇。
> 开工时相对本总纲的两处**实质性调整**（均已回写至上表）：
> ① 迭代计划 §42 / §45 同时列了「履约率」与「完成率」—— 若都按「完成 ÷（完成+取消）」算，
>    那是**同一个数字挂了两个名字**（决策 6.2 禁止的另一种形态）。
>    处置：履约率 = 最终履约比例；完成率（按时）= 已完成订单里「`finish_time ≤ visit_time + 窗口`」的占比；
> ② 异常卡片的 `linkUrl` 必须是**真实存在的前端路由**（决策 5.4）——
>    「看到了但处理不了」是本版最典型的假绿，E2E 逐个点开验证落地页可达。
>
> 异常中心**聚合** V3.1 风险中心（直接取 `AdminService#fulfillmentSummary()`），
> **不另建一套风险判定**（决策 5.1）；同源关系由 `OperationsExceptionParityTest` 锁死。"""

V36_ANCHOR = "**依赖**：收敛 V3.2 / V3.3 / V3.5 的口径。"
V36_NOTE = """**依赖**：收敛 V3.2 / V3.3 / V3.5 的口径。

> ✅ **V3.6 已于 2026-10-20 交付**。落地后的偏差见 [`V3.6-tickets.md`](./V3.6-tickets.md) §〇
> 与 [ADR-0017](../adr/0017-metric-endpoint-machine-contract.md)。
> 开工时相对本总纲的一处**实质性调整**（已回写至上表）：
> ① 缺口分析的「供给数」若按预写 spec 字面（「工作状态 AVAILABLE 且资质通过的陪诊员数」），
>    每个桶的供给数都是同一个值 —— 那是一个**没有信息量**的指标。
>    处置：按**可服务时段**分桶；另设 `companionsWithoutSlots` 如实披露
>    「有多少陪诊员没有可解析的可服务时段、因而不参与供给统计」。
>
> 本版把「口径登记」从**人读的表**升级成**机器校验的面**：
> `metrics/endpoints.yml` 声明「哪个端点返回了哪些指标」，
> `MetricEndpointContractTest` 做三条双向校验；
> `MetricCalculatorRegistry` 保证「同一个指标 key 只能有一个实现」（重复即启动失败）。"""


def main():
    text = DOC.read_text(encoding="utf-8")
    changed = False

    for anchor, note in ((V33_ANCHOR, V33_NOTE), (V34_ANCHOR, V34_NOTE),
                         (V35_ANCHOR, V35_NOTE), (V36_ANCHOR, V36_NOTE)):
        # 用**整段说明**判定是否已插入 —— 逐行取 split 结果会拿到空串，
        # 而 `"" in text` 恒为真，会让所有替换被静默跳过。
        if note.strip() in text:
            continue
        if anchor not in text:
            print("锚点未找到：" + anchor[:40], file=sys.stderr)
            return 1
        text = text.replace(anchor, note, 1)
        changed = True

    # 总纲头部状态
    old_status = "> **状态**：待评审 → 进入开发（V3.1）"
    new_status = "> **状态**：**V3.1 – V3.6 六版全部交付**（2026-10-20）· 总报告见 [`V3.3-V3.6-iteration-summary.md`](../agents/reports/V3.3-V3.6-iteration-summary.md)"
    if old_status in text:
        text = text.replace(old_status, new_status, 1)
        changed = True

    DOC.write_text(text, encoding="utf-8")
    print("总纲已回写 V3.3 – V3.6 交付状态" + ("" if changed else "（已是最新）"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
