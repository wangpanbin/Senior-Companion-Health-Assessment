# -*- coding: utf-8 -*-
"""把 V3.4 的 V7 迁移同步进 docs/db 的三处索引/清单（表数、迁移表、索引清单）。

用法：python tools/patch_db_index_docs_v34.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "docs/db/README.md"
INDEX_DOC = ROOT / "docs/db/03-索引设计与EXPLAIN.md"
SEED_DOC = ROOT / "docs/db/04-种子数据说明.md"

MIGRATION_ROW = "| V6 | `V6__order_fulfillment.sql` | 履约事件流水表 + `companion_order` 增列与索引（ADR-0012） | 纯增量；`information_schema` 守卫可重复执行 |"
NEW_MIGRATION_ROW = (
    MIGRATION_ROW
    + "\n| V7 | `V7__matching_structured.sql` | 智能调度结构化改造：`companion_available_slot` 建表 + "
    "`companion_profile` 增坐标与区域码 + `companion_order` 增区域码（ADR-0016） | 纯增量建表 + 可空增列 + backfill |"
)

OLD_TABLE_COUNT = "| `02-数据字典.md` | 24 张表逐字段字典（类型 / 可空 / 默认值 / 说明） |"
NEW_TABLE_COUNT = "| `02-数据字典.md` | 25 张表逐字段字典（类型 / 可空 / 默认值 / 说明） |"

OLD_APPEND_NOTE = "追加数据一律新开版本号（V4、V5、V6…）。"
NEW_APPEND_NOTE = "追加数据一律新开版本号（V4、V5、V6、V7…）。"

OLD_SEED_COUNT = "共 24 张表、当前运行库 **5,960** 行"
NEW_SEED_COUNT = "共 25 张表、当前运行库 **5,960** 行"

INDEX_ROWS = """| `companion_available_slot` | `idx_companion_weekday` | 普通 | `companion_id`, `weekday`, `deleted` | 匹配引擎的时间维度：按陪诊员 + 星期取可服务时段（V3.4） |
| `companion_available_slot` | `idx_companion_id` | 普通 | `companion_id` | 按陪诊员取全部时段（时段为空时回退解析自由文本）（V3.4） |
"""


def main():
    changed = []

    # --- docs/db/README.md ---
    text = README.read_text(encoding="utf-8")
    if "V7__matching_structured.sql" not in text:
        if MIGRATION_ROW not in text:
            print("README：迁移表锚点未找到", file=sys.stderr)
            return 1
        text = text.replace(MIGRATION_ROW, NEW_MIGRATION_ROW, 1)
        changed.append("README 迁移表")
    if OLD_TABLE_COUNT in text:
        text = text.replace(OLD_TABLE_COUNT, NEW_TABLE_COUNT, 1)
        changed.append("README 表数")
    if OLD_APPEND_NOTE in text:
        text = text.replace(OLD_APPEND_NOTE, NEW_APPEND_NOTE, 1)
        changed.append("README 追加说明")
    README.write_text(text, encoding="utf-8")

    # --- docs/db/03-索引设计与EXPLAIN.md ---
    text = INDEX_DOC.read_text(encoding="utf-8")
    if "`companion_available_slot`" not in text:
        lines = text.splitlines(keepends=True)
        out = []
        inserted = False
        for line in lines:
            out.append(line)
            # 插到索引清单的最后一行表格行之后（表头分隔行之后任意位置均可，取首个非表格行前）
        text2 = "".join(out)
        # 索引清单是「## 一、索引清单」下的表格；在「## 二、」之前插入两行
        marker = "\n## 二、关键设计决策"
        if marker not in text2:
            print("索引文档：未找到「## 二、」锚点", file=sys.stderr)
            return 1
        text2 = text2.replace(marker, "\n" + INDEX_ROWS + marker, 1)
        INDEX_DOC.write_text(text2, encoding="utf-8")
        changed.append("索引清单")

    # --- docs/db/04-种子数据说明.md ---
    text = SEED_DOC.read_text(encoding="utf-8")
    if OLD_SEED_COUNT in text:
        text = text.replace(OLD_SEED_COUNT, NEW_SEED_COUNT, 1)
        SEED_DOC.write_text(text, encoding="utf-8")
        changed.append("种子表数")

    print("docs/db 已同步：" + ("、".join(changed) if changed else "已是最新"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
