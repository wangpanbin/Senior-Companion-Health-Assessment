# -*- coding: utf-8 -*-
"""同步 V3.6 数据闭环到 CHANGELOG + 指标字典人读面。

用法：python tools/patch_docs_v36.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = ROOT / "CHANGELOG.md"
DICT_DOC = ROOT / "docs/spec/metric-dictionary.md"

CHANGELOG_BLOCK = """## [V3.6] · 2026-10-20 · 数据闭环

让平台**通过数据持续提高服务质量**，而不是「统计完了就放着」。
产品与工程契约见 [spec](docs/spec/V3.6-data-loop-spec.md) /
[tickets](docs/spec/V3.6-tickets.md) /
[ADR-0017](docs/adr/0017-metric-endpoint-machine-contract.md)。

### Added · 新增

**端点 ↔ 指标机器面**

- `backend/src/main/resources/metrics/endpoints.yml` —— 声明「哪个端点返回了哪些指标」
- `util/MetricEndpointRegistry` —— 加载器
- `MetricEndpointContractTest` —— **三条双向校验**：
  ① yml 里的端点必须真实存在（反射扫描所有 `@RestController`）
  ② yml 里的指标必须在指标字典里
  ③ 指标字典里每条指标的端点必须在 yml 里出现
- `tools/check_metric_dict_parity.py` —— 指标字典**人读面 ↔ 机器面**逐条一致性校验

**指标计算器注册表**

- `service/support/metrics/MetricCalculator` + `MetricCalculatorRegistry`
  —— **同一个指标 key 只能有一个实现**；未注册的指标在效果对比里返回 400

**两个新端点**

- `GET /api/admin/operations/gap` —— 供给时段缺口分析（「周内第几天 × 小时」分桶）
- `GET /api/admin/operations/effect` —— 干预效果对比（同一指标两期值 + 差值）

### 口径决策

- **桶的粒度固定为「周内第几天 × 小时」**，与区间长度无关 ——
  只有这样「这周和上周的同一时段」才可比（迭代计划 §47 的闭环数据基础）
- **供给数按「可服务时段」分桶**，不是按「当前 AVAILABLE 总人数」——
  后者每个桶都一样，指标没有信息量；
  没能解析时段的陪诊员不进任何桶，并由 `companionsWithoutSlots` **如实披露**
- **效果对比两侧走同一个计算器** —— 用两个公式对比，比出来的是公式差异
- **同一指标跨端共用一份实现**（决策 6.2）—— 物理上只有一份，就不可能漂移

### 门禁

- mvn -B verify（基线 1162 + 新增）
- pnpm lint:check / vitest / build
- playwright 全量

---

"""

ANCHOR_V35 = "## [V3.5] · 2026-10-20 · 平台运营驾驶舱"

DICT_ANCHOR = "\n---\n\n## 三、区间约定（所有指标共用）"

DICT_SECTION = """
### 2.7 数据闭环补登（V3.6）

> V3.6 的第一张票是**校验与补全**指标字典（决策 2.8 已把字典从 V3.6 提前到 V3.2 建好）。
> 下列指标是迭代计划 §48 清单里**此前缺失、但已有可算口径**的部分 ——
> **只为已有业务事实补登记，不新造业务事实**。

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.timeout_rate` | 超时率 | 统计区间内已完成订单里「超时」的比例 | 统计区间 | `companion_order(status, visit_time, finish_time)` | `1 − 完成率（按时）` | 1 |
| `operations.manual_intervention_rate` | 人工介入率 | 统计区间内需要管理员人工介入的订单占比 | 统计区间 | `companion_order(fulfillment_risk, visit_time)` | `COUNT(fulfillment_risk = 'MANUAL_REQUIRED') / COUNT(*)` | 1 |
| `operations.reassign_rate` | 改派率 | 统计区间内被管理员改派的订单占比 | 统计区间 | `admin_oper_log`（改派记录） | 改派订单数 / 区间订单数 | 1 |
| `operations.service_duration_minutes` | 平均服务时长（分钟） | 统计区间内已完成订单的平均服务时长 | 统计区间 | `companion_order(start_time, finish_time, status)` | `AVG(TIMESTAMPDIFF(MINUTE, start_time, finish_time))` | 1 |
| `operations.companion_count` | 陪诊员数 | 统计区间内有过接单的陪诊员人数 | 统计区间 | `companion_order(companion_id, visit_time)` | `COUNT(DISTINCT companion_id)` | 1 |
| `operations.medication_task_count` | 用药任务数 | 统计区间内的服药任务总数 | 统计区间 | `medication_task(plan_time)` | `COUNT(*)` | 1 |
| `operations.medication_missed_rate` | 漏服率 | 统计区间内漏服任务占比 | 统计区间 | `medication_task(status, was_missed, plan_time)` | 漏服桶 / 任务总数（`MedicationBucketSql` 唯一真源） | 1 |

> ⚠️ **没有为「还没有业务事实」的指标硬造条目** ——
> 例如「陪诊员满意度」没有数据源，就不进字典。
> 字典是**已有口径的登记处**，不是愿望清单。

---
"""


def main():
    ok = True

    cl = CHANGELOG.read_text(encoding="utf-8")
    if "## [V3.6] · 2026-10-20 · 数据闭环" in cl:
        print("CHANGELOG：[V3.6] 已存在，跳过")
    else:
        if ANCHOR_V35 not in cl:
            print("CHANGELOG：未找到锚点", file=sys.stderr)
            ok = False
        else:
            cl = cl.replace(ANCHOR_V35, CHANGELOG_BLOCK + ANCHOR_V35, 1)
            CHANGELOG.write_text(cl, encoding="utf-8")
            print("CHANGELOG：已插入 [V3.6]")

    text = DICT_DOC.read_text(encoding="utf-8")
    if "### 2.7 数据闭环补登（V3.6）" in text:
        print("指标字典：V3.6 章节已存在，跳过")
    else:
        if DICT_ANCHOR not in text:
            print("指标字典：未找到章节锚点", file=sys.stderr)
            ok = False
        else:
            text = text.replace(DICT_ANCHOR, "\n" + DICT_SECTION + "\n## 三、区间约定（所有指标共用）", 1)
            DICT_DOC.write_text(text, encoding="utf-8")
            print("指标字典：人读面已补 V3.6")

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
