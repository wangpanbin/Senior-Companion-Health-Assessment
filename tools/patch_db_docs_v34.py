# -*- coding: utf-8 -*-
"""把 V3.4 的 V7 结构化改造同步进 docs/db（数据字典 + ER 图）。

用法：python tools/patch_db_docs_v34.py
幂等：重复执行不重复追加。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DICT_DOC = ROOT / "docs/db/02-数据字典.md"
ER_DOC = ROOT / "docs/db/01-ER图.md"

# ---------- 数据字典：companion_profile 的三个新列 ----------
PROFILE_ANCHOR = "| `audit_status` | `VARCHAR(20)` | N | 'PENDING' | 资质状态：PENDING-待审核 / APPROVED-已通过 / REJECTED-已驳回 |"

PROFILE_COLS = (
    "| `service_region_code` | `VARCHAR(32)` | Y | NULL | **服务区域码（V3.4 新增）**：`<CITY>_<DISTRICT>`（全大写拼音）；城市前缀（`_` 之前）是「区」这一层。NULL = 未映射，该维度按「无数据」处理（记 0 并标注），不猜 |"
    "\n| `longitude` | `DECIMAL(10,7)` | Y | NULL | **陪诊员档案经度（V3.4 新增）**：一期自填或管理员代录；NULL = 无坐标，距离维度记 0 并标注「未填写坐标」，**绝不按 (0,0) 推断** |"
    "\n| `latitude` | `DECIMAL(10,7)` | Y | NULL | **陪诊员档案纬度（V3.4 新增）**：同上 |"
)

# ---------- 数据字典：companion_order 的新列 ----------
ORDER_ANCHOR = "| `version` | `INT` | N | 0 | 乐观锁版本号（**接单防超卖**，MyBatis-Plus @Version） |"

ORDER_COL = (
    "| `service_region_code` | `VARCHAR(32)` | Y | NULL | **订单服务区域码（V3.4 新增）**：`<CITY>_<DISTRICT>`；"
    "由订单地址的区县文本 backfill 而来，映射不上时为 NULL |"
)

# ---------- 数据字典：新增第 25 张表 ----------
SLOT_SECTION = """
### 25. `companion_available_slot` — 陪诊员可服务时段表（V3.4 结构化改造，只增不改）

- 模块：`M4`　种子数据：**0** 行（表为 V3.4 新增，存量陪诊员走 `companion_profile.available_time` 自由文本回退解析）
- 迁移：`backend/sql/V7__matching_structured.sql`（expand 阶段，全部可空，不影响存量数据）

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` `PK` | `BIGINT` | N | 自增 | 时段 ID |
| `companion_id` | `BIGINT` | N | — | 陪诊员用户 ID |
| `weekday` | `TINYINT` | N | — | 周内第几天：1=周一 … 7=周日（与 Java `WeekFields.ISO` 对齐） |
| `start_time` | `TIME` | N | — | 可服务起始时刻 |
| `end_time` | `TIME` | N | — | 可服务结束时刻（小于起始时刻 = 跨零点，如 22:00–06:00） |
| `create_time` | `DATETIME` | N | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `DATETIME` | N | CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `TINYINT` | N | 0 | 逻辑删除：0-未删除 1-已删除 |

**索引**

| 类型 | 名称 | 列 | 说明 |
|---|---|---|---|
| 主键 | `PRIMARY` | `id` | 自增主键 |
| 普通 | `idx_companion_weekday` | `companion_id`, `weekday`, `deleted` | 按陪诊员 + 星期筛时段（匹配引擎的时间维度走这条） |
| 普通 | `idx_companion_id` | `companion_id` | 按陪诊员取全部时段 |

> **为什么用表不用 JSON 列**：时段要参与**筛选**（订单就诊时间落在哪个时段里），
> 必须可索引、可 JOIN。JSON 列做不到「按星期 + 时刻范围」的高效筛选，
> 而 V3.4 时间匹配维度恰恰要按这个条件过滤候选。
>
> **与 `companion_profile.available_time` 的关系**：自由文本列**保留**（不删），
> 读路径**优先**用本表；本表没有记录时回退解析 `available_time`
> （`LegacySlotParser`，只认几种明确写法，解析不出就记 0 并标注，不猜）。

"""


def patch_dict():
    text = DICT_DOC.read_text(encoding="utf-8")
    changed = False

    if "| `service_region_code` | `VARCHAR(32)` | Y | NULL | **服务区域码（V3.4 新增）**" not in text:
        if PROFILE_ANCHOR not in text:
            print("数据字典：companion_profile 锚点未找到", file=sys.stderr)
            return None
        text = text.replace(PROFILE_ANCHOR, PROFILE_ANCHOR + "\n" + PROFILE_COLS, 1)
        changed = True

    if "**订单服务区域码（V3.4 新增）**" not in text:
        if ORDER_ANCHOR not in text:
            print("数据字典：companion_order 锚点未找到", file=sys.stderr)
            return None
        text = text.replace(ORDER_ANCHOR, ORDER_ANCHOR + "\n" + ORDER_COL, 1)
        changed = True

    if "### 25. `companion_available_slot`" not in text:
        text = text.rstrip("\n") + "\n" + SLOT_SECTION
        changed = True

    if changed:
        DICT_DOC.write_text(text, encoding="utf-8")
    return changed


def patch_er():
    text = ER_DOC.read_text(encoding="utf-8")
    if "companion_available_slot" in text:
        return False
    # ER 图是 mermaid/ASCII 结构；在文末补一段关系说明
    block = """
---

## 附录 · V3.4 结构化改造新增的表与字段（V7）

| 变更 | 对象 | 内容 | 说明 |
|---|---|---|---|
| 新增表 | `companion_available_slot` | 陪诊员可服务时段（`companion_id` / `weekday` / `start_time` / `end_time`） | 与 `companion_profile` 一对多；时段要参与筛选，必须可索引可 JOIN |
| 增列（可空） | `companion_profile` | `longitude` / `latitude` / `service_region_code` | 坐标与区域码；老数据为 NULL，不影响存量 |
| 增列（可空） | `companion_order` | `service_region_code` | 订单区域码，由地址的区县文本 backfill 而来 |

**关系补充**

```
companion_profile 1 ──< companion_available_slot
    （一个陪诊员可登记多个时段；weekday 1=周一…7=周日）

companion_profile ── service_region_code ──┐
companion_order   ── service_region_code ──┴── 区域码匹配（V3.4 匹配引擎的区域维度）
```

> 详见 [ADR-0016](../adr/0016-matching-structured-and-explainable.md) 与
> `backend/sql/V7__matching_structured.sql`。
"""
    ER_DOC.write_text(text.rstrip("\n") + "\n" + block, encoding="utf-8")
    return True


def main():
    a = patch_dict()
    b = patch_er()
    print("docs/db 已同步 V3.4 结构化改造（数据字典={}，ER 图={}）".format(
        "已改" if a else "已最新", "已改" if b else "已最新"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
