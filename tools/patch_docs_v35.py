# -*- coding: utf-8 -*-
"""同步 V3.5 运营驾驶舱到指标字典人读面 + CHANGELOG。

用法：python tools/patch_docs_v35.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DICT_DOC = ROOT / "docs/spec/metric-dictionary.md"
CHANGELOG = ROOT / "CHANGELOG.md"

DICT_ANCHOR = "\n---\n\n## 三、区间约定（所有指标共用）"

DICT_SECTION = """
### 2.6 平台运营驾驶舱（V3.5）

> 管理端口径与陪诊员端 / 家属端**共用同一批计算实现**（决策 6.2）：
> 同一个指标只有一个公式，绝不因为「哪个端点」而写第二遍。

#### 订单与履约

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.today_order_count` | 今日订单数 | 今日新增的陪诊订单数 | **当天**（按 `create_time`，不随统计区间浮动） | `companion_order(create_time)` | `COUNT(*)` | 1 |
| `operations.completed_order_count` | 区间完成订单数 | 统计区间内「已完成」的陪诊订单数 | 统计区间 | `companion_order(status, visit_time)` | `COUNT(status IN ('COMPLETED','REVIEWED'))` | 1 |
| `operations.in_service_count` | 区间服务中订单数 | 统计区间内处于服务中的订单数 | 统计区间 | `companion_order(status, visit_time)` | `COUNT(status = 'IN_SERVICE')` | 1 |
| `operations.pending_count` | 区间待接单订单数 | 统计区间内仍待接单的订单数 | 统计区间 | `companion_order(status, visit_time)` | `COUNT(status = 'PENDING')` | 1 |
| `operations.fulfillment_rate` | 履约率 | 统计区间内订单的最终履约比例 | 统计区间 | `companion_order(status, visit_time)` | `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)` | 1 |
| `operations.completion_rate` | 完成率（按时） | 统计区间内**已完成**订单里「没超时」的比例 | 统计区间 | `companion_order(status, visit_time, finish_time, start_time)` | 按时完成单数 / 已完成单数；「按时」= `finish_time ≤ visit_time + 履约时长窗口`（`nianglin.operations.fulfillment-window-minutes`，默认 240 分钟） | 1 |
| `operations.avg_accept_minutes` | 平均接单时间 | 统计区间内已接单订单的接单耗时 | 统计区间 | `companion_order(create_time, accept_time)` | `AVG(TIMESTAMPDIFF(MINUTE, create_time, accept_time))`，仅 `accept_time` 非空 | 1 |
| `operations.timeout_rate` | 超时率 | 统计区间内已完成订单里「超时」的比例 | 统计区间 | 同 `operations.completion_rate` | `1 − 完成率（按时）` | 1 |
| `operations.manual_intervention_rate` | 人工介入率 | 统计区间内需要管理员人工介入的订单占比 | 统计区间 | `companion_order(fulfillment_risk, visit_time)` | `COUNT(fulfillment_risk = 'MANUAL_REQUIRED') / COUNT(*)` | 1 |
| `operations.reassign_rate` | 改派率 | 统计区间内被管理员改派的订单占比 | 统计区间 | `admin_oper_log`（改派记录） | 改派订单数 / 区间订单数 | 1 |

#### 服务与陪诊员

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.complaint_rate` | 投诉率 | 统计区间内被投诉的订单占比 | 统计区间 | `complaint` + `companion_order` | 投诉订单数 / 区间订单数 | 1 |
| `operations.average_score` | 平均评分 | 统计区间内有效评价的平均分 | 统计区间 | `order_review(score, is_valid, create_time)` | `AVG(score)`，仅 `is_valid = 1` | 1 |
| `operations.service_duration_minutes` | 平均服务时长（分钟） | 统计区间内已完成订单的平均服务时长 | 统计区间 | `companion_order(start_time, finish_time, status)` | `AVG(TIMESTAMPDIFF(MINUTE, start_time, finish_time))` | 1 |
| `operations.active_companion_count` | 活跃陪诊员数 | 统计区间内有过接单的陪诊员人数（去重） | 统计区间 | `companion_order(companion_id, visit_time)` | `COUNT(DISTINCT companion_id)` | 1 |
| `operations.companion_count` | 陪诊员数 | 统计区间内有过接单的陪诊员人数（同活跃口径） | 统计区间 | 同上 | `COUNT(DISTINCT companion_id)` | 1 |

#### 异常中心

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.exception_manual_required_count` | 需人工介入订单数 | 当前处于人工介入阈值的待接单订单数 | **当前状态**（不受统计区间影响） | `companion_order(fulfillment_risk, status)` | `COUNT(fulfillment_risk = 'MANUAL_REQUIRED' AND status = 'PENDING')` | 1 |
| `operations.exception_match_warning_count` | 超匹配预警时间订单数 | 当前处于预警 / 紧急阈值的待接单订单数 | **当前状态** | 同上 | `COUNT(fulfillment_risk IN ('WARNING','URGENT') AND status = 'PENDING')` | 1 |
| `operations.exception_complaint_rate_count` | 投诉率异常陪诊员数 | 回溯期内投诉率超过阈值的陪诊员人数 | 回溯期（默认 30 天） | `complaint` + `companion_order` | 投诉率 > `nianglin.operations.complaint-rate-threshold`（默认 20%）且接单量 ≥ 最小样本 | 1 |
| `operations.exception_medication_missed_count` | 用药漏服异常数 | 统计区间内漏服桶的任务数 | 统计区间 | `medication_task(status, was_missed, plan_time)` | 漏服桶计数（`MedicationBucketSql` 唯一真源，与服药日历同源） | 1 |
| `operations.medication_task_count` | 用药任务数 | 统计区间内的服药任务总数 | 统计区间 | `medication_task(plan_time)` | `COUNT(*)` | 1 |
| `operations.medication_missed_rate` | 漏服率 | 统计区间内漏服任务占比 | 统计区间 | 同上 | 漏服桶 / 任务总数 | 1 |

#### 两条必须记住的规则

1. **异常中心的前两项与 V3.1 风险中心同源**（决策 5.1）：
   `manualRequired` 与 `warning + urgent` 必须与
   `GET /api/admin/fulfillment/summary` 的字段**完全一致**。
   异常中心**聚合**风险中心，**不另建一套风险判定** ——
   两套判定必然漂移，这正是 V3.2 在用药三桶上踩过的坑。
   该同源关系由 `OperationsExceptionParityTest` 锁死。
2. **履约率与完成率（按时）不是同一个数**（偏差 D-3.5-1）：
   履约率看「最终有没有履约完」，完成率看「完成得及不及时」。
   两个名字一个数是口径分裂的另一种形态，同样禁止。

---
"""

OLD_RECORD = "| 2026-10-20 | v1.2 | 登记 V3.4 智能调度的 6 条匹配指标（五个维度 + 加权总分） | 无 —— 新指标首次登记 |"
NEW_RECORD = OLD_RECORD + "\n| 2026-10-20 | v1.3 | 登记 V3.5 运营驾驶舱的 20 条管理端指标（订单/履约/服务/陪诊员/异常五域） | 无 —— 新指标首次登记 |"

CHANGELOG_BLOCK = """## [V3.5] · 2026-10-20 · 平台运营驾驶舱

让管理员**在 30 秒内发现平台异常，并且能直接去处理**。
产品与工程契约见 [spec](docs/spec/V3.5-operations-cockpit-spec.md) /
[tickets](docs/spec/V3.5-tickets.md)。

### Added · 新增

**四个驾驶舱端点**

- `GET /api/admin/operations/overview` —— 平台概览八项指标
- `GET /api/admin/operations/exceptions` —— **异常中心**（四类异常，每类带 `linkUrl`）
- `GET /api/admin/operations/trend` —— 六条趋势（订单 / 陪诊员活跃 / 履约率 / 投诉 / 评价 / 用药任务）
  × 四个区间（今日 / 近 7 天 / 近 30 天 / 自定义）
- `GET /api/admin/operations/health` —— **平台健康度 = 六个客观指标，不给合成分**
- `config/OperationsProperties`（阈值配置化，启动期校验）+ `mapper/OperationsMapper`
  + `service/OperationsService(+Impl)` + 5 个 VO + `controller/admin/OperationsController`

**前端**

- `views/admin/operations.vue` —— 驾驶舱首页（概览 / 异常中心 / 趋势 / 健康度四块**独立三态**）
- 异常卡片的「去处理」**直接用后端下发的 `linkUrl`** 跳转，前端不拼路由
- 组件单测 + 20 例；E2E + 8 例（含「四类异常卡片逐个点开，落地页必须可达」）

### 口径决策

- **异常中心聚合 V3.1 风险中心，不另建一套风险判定**（决策 5.1）——
  `manualRequired` 与 `warning + urgent` 与 `/api/admin/fulfillment/summary` **同源**，
  由 `OperationsExceptionParityTest` 锁死
- **平台健康度是六个并列指标，不合成总分**（决策 5.2）——
  禁止「AI 平台健康指数 96」这类无法解释的数
- **履约率 ≠ 完成率（按时）**（偏差 D-3.5-1）：
  履约率看「最终有没有履约完」，完成率看「完成得及不及时」；
  两个名字一个数同样是口径分裂
- **每张异常卡片的 `linkUrl` 必须可达**（决策 5.4）——
  「看到了但处理不了」是本版最典型的假绿，E2E 逐个点开验证

### 门禁

- mvn -B verify（基线 1101 + 新增）
- pnpm lint:check / vitest 191/191（基线 171，+20）/ build
- playwright 全量（基线 229 + 新增 8）

---

"""

ANCHOR_V34 = "## [V3.4] · 2026-10-20 · 智能调度"


def main():
    ok = True

    text = DICT_DOC.read_text(encoding="utf-8")
    if "### 2.6 平台运营驾驶舱（V3.5）" in text:
        print("指标字典：V3.5 章节已存在，跳过")
    else:
        if DICT_ANCHOR not in text:
            print("指标字典：未找到章节锚点", file=sys.stderr)
            ok = False
        else:
            text = text.replace(DICT_ANCHOR, "\n" + DICT_SECTION + "\n## 三、区间约定（所有指标共用）", 1)
            text = text.replace(OLD_RECORD, NEW_RECORD, 1)
            text = text.replace("> **版本**：v1.2 · 2026-10-20", "> **版本**：v1.3 · 2026-10-20", 1)
            DICT_DOC.write_text(text, encoding="utf-8")
            print("指标字典：人读面已补 V3.5")

    cl = CHANGELOG.read_text(encoding="utf-8")
    if "## [V3.5] · 2026-10-20 · 平台运营驾驶舱" in cl:
        print("CHANGELOG：[V3.5] 已存在，跳过")
    else:
        if ANCHOR_V34 not in cl:
            print("CHANGELOG：未找到锚点", file=sys.stderr)
            ok = False
        else:
            cl = cl.replace(ANCHOR_V34, CHANGELOG_BLOCK + ANCHOR_V34, 1)
            CHANGELOG.write_text(cl, encoding="utf-8")
            print("CHANGELOG：已插入 [V3.5]")

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
