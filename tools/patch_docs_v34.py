# -*- coding: utf-8 -*-
"""同步 V3.4 智能调度到指标字典人读面 + CHANGELOG。

用法：python tools/patch_docs_v34.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DICT_DOC = ROOT / "docs/spec/metric-dictionary.md"
CHANGELOG = ROOT / "CHANGELOG.md"

DICT_ANCHOR = "\n---\n\n## 三、区间约定（所有指标共用）"

DICT_SECTION = """
### 2.5 智能调度匹配（V3.4）

> 维度得分是「0–1 的原始分 × 100 后的整数」；加权总分 = Σ(维度分 × 权重) × 100。
> 权重配置化于 `nianglin.matching.*.weight`，**启动期校验权重之和 = 1**。

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `matching.total_score` | 匹配总分 | 候选陪诊员对某个订单的加权匹配总分 | 单次匹配（订单与候选人的配对，无时间区间） | 匹配规则引擎 | `Σ(维度分 × 权重) × 100` | 1 |
| `matching.region_score` | 区域维度得分 | 候选陪诊员与订单的服务区域匹配程度 | 单次匹配 | `companion_profile(service_region_code, service_area)` + `companion_order(service_region_code, address)` | 区域码相等=100；同市不同区=50；跨市=0；缺码时回退自由文本包含式并降级标注 | 1 |
| `matching.time_score` | 时间维度得分 | 订单就诊时间与候选陪诊员可服务时段的重合程度 | 单次匹配 | `companion_available_slot` + `companion_profile(available_time)` | 落在时段内=100；同日不同时段=50；其它=0；无时段时记 0 并降级标注 | 1 |
| `matching.distance_score` | 距离维度得分 | 候选陪诊员与订单地址的直线距离匹配程度 | 单次匹配 | `companion_profile(longitude, latitude)` + `companion_order(longitude, latitude)` | `(1 − Haversine距离 / 满量程) × 100`；**无坐标时记 0 并标注「未填写坐标」，绝不按 (0,0) 推断** | 1 |
| `matching.skill_score` | 服务能力维度得分 | 候选陪诊员在订单科室上的历史服务能力 | 单次匹配（数据来自历史累计） | `companion_order(companion_id, department, status)` | `该科室已完成单数 / 满量程 × 100`，上限 100 | 1 |
| `matching.rating_score` | 评分维度得分 | 候选陪诊员的平均评分归一化后的得分 | 单次匹配（数据来自历史累计） | `companion_profile(score, user_id)` | `score / 5 × 100`；无有效评分时记 0 并降级标注 | 1 |

#### 两条容易被忽略的规则

1. **「没数据」与「不匹配」是两件事。**
   距离维度在陪诊员没填坐标时记 0，但它的含义是「这一维没法算」，
   不是「距离很远」—— 前者写在 `notes`（降级说明），后者才会写进 `reasons`（命中理由）。
   混在一起的话，运营会拿一个不存在的结论去调权重。
2. **候选推荐端点的 `total` 是「通过过滤链的候选总数」**，不是返回条数。
   两者混用会让人以为「只有 3 个候选人」，实际有 12 个只是没进 Top N。

---
"""

OLD_RECORD = "| 2026-10-20 | v1.1 | 登记 V3.3 陪诊员工作台的 18 条指标（区间 11 条 + 累计 7 条） | `companion_profile.order_count` 不再作为来源（运行期无人维护），累计完成单数改从 `companion_order` 现算 |"
NEW_RECORD = OLD_RECORD + "\n| 2026-10-20 | v1.2 | 登记 V3.4 智能调度的 6 条匹配指标（五个维度 + 加权总分） | 无 —— 新指标首次登记 |"

CHANGELOG_BLOCK = """## [V3.4] · 2026-10-20 · 智能调度

让平台**自动找到更合适的陪诊员**，并且每一条推荐都能说出「为什么是他」。
产品与工程契约见 [spec](docs/spec/V3.4-matching-spec.md) /
[tickets](docs/spec/V3.4-tickets.md) /
[ADR-0016](docs/adr/0016-matching-structured-and-explainable.md)。

### Added · 新增

**结构化改造（V7 迁移）**

- `backend/sql/V7__matching_structured.sql`：`companion_profile` 增坐标 + 服务区域码；
  `companion_order` 增服务区域码；新表 `companion_available_slot`（可服务时段）
- backfill：自由文本 → 区域码（8 个区县 / 4 个市，**映射不上的留空**）
- `backend/sql/tools/gen_region_codes.py` + `region_code_mapping.json` —— 映射生成器与校验器
  （`--check` 机械核对「迁移里手写的 CASE」与「生成器产出」逐字一致）

**匹配三件套（纯函数，无 Spring 上下文）**

- `service/support/matching/`：`MatchingRule` / `MatchingScore` / `MatchingCandidate` / `MatchTarget`
  + 五条规则（区域 / 时间 / 距离 / 服务能力 / 评分）+ `LegacySlotParser`（自由文本时段回退）
- `config/MatchingProperties` —— 五个权重配置化，**启动期校验权重之和 = 1**（不等于就启动失败）
- `service/MatchingService(+Impl)` + `mapper/MatchingMapper` —— 过滤链（工作状态 → 资质 → 区域 → 时段 → 档期冲突）

**接口**

- `GET /api/order/{id}/recommend-companions` —— 家属端候选推荐（Top N + 可解释性）
- `GET /api/order/hall?sortBy=` —— 开放 `DISTANCE` / `TIME_MATCH`；`RECOMMEND` 换成五维加权

### Changed · 变更

- `OrderServiceImpl#hall`：从 SQL 三级键改为 **Java 侧五维打分 + 内存分页**
  （响应结构与 `total` 语义**零变更**，变的只有「哪几条排在前面」）
- 指标字典登记 6 条匹配指标（v1.0 → v1.2）
- `docs/api/03-order.md` —— 补排序语义与候选推荐端点
- `docs/adr/0016-*.md` —— 新 ADR：为什么必须先做结构化改造 + 为什么每条推荐都必须可解释
- 前端：大厅加排序选择器；家属端订单详情加候选展示（标注「推荐 ≠ 派单」）

### 口径决策

- **区域码两级**（`<CITY>_<DISTRICT>`）：码相等 = 1.0；同市不同区 = 0.5；跨市 = 0
- **「缺数据」与「不匹配」分开**：没填坐标 / 没登记时段 → 放行 + 降级标注，**不淘汰**
- **`INCOME` 排序仍不开放**：缺真实结算金额口径（决策 D8）
- **推荐 ≠ 派单**：端点不产生任何订单状态变更，接单权始终在陪诊员手里

---

"""

ANCHOR_V33 = "## [V3.3] · 2026-10-20 · 陪诊员工作台"


def main():
    ok = True

    # 1) 指标字典人读面
    text = DICT_DOC.read_text(encoding="utf-8")
    if "### 2.5 智能调度匹配（V3.4）" in text:
        print("指标字典：V3.4 章节已存在，跳过")
    else:
        if DICT_ANCHOR not in text:
            print("指标字典：未找到章节锚点", file=sys.stderr)
            ok = False
        else:
            text = text.replace(DICT_ANCHOR, "\n" + DICT_SECTION + "\n## 三、区间约定（所有指标共用）", 1)
            text = text.replace(OLD_RECORD, NEW_RECORD, 1)
            text = text.replace("> **版本**：v1.1 · 2026-10-20", "> **版本**：v1.2 · 2026-10-20", 1)
            DICT_DOC.write_text(text, encoding="utf-8")
            print("指标字典：人读面已补 V3.4")

    # 2) CHANGELOG
    cl = CHANGELOG.read_text(encoding="utf-8")
    if "## [V3.4] · 2026-10-20 · 智能调度" in cl:
        print("CHANGELOG：[V3.4] 已存在，跳过")
    else:
        if ANCHOR_V33 not in cl:
            print("CHANGELOG：未找到锚点", file=sys.stderr)
            ok = False
        else:
            cl = cl.replace(ANCHOR_V33, CHANGELOG_BLOCK + ANCHOR_V33, 1)
            CHANGELOG.write_text(cl, encoding="utf-8")
            print("CHANGELOG：已插入 [V3.4]")

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
