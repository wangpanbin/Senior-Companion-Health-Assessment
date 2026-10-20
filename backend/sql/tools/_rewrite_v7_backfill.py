# -*- coding: utf-8 -*-
"""用生成器重写 V7 迁移的 backfill 段（修掉 apply 的切分 bug）。"""
import io
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gen_region_codes import load_regions, backfill_lines, MIGRATION  # noqa: E402


def main():
    regions = load_regions()
    lines = backfill_lines(regions)
    text = MIGRATION.read_text(encoding="utf-8")

    marker = "-- 4.1 陪诊员服务区域"
    idx = text.find(marker)
    if idx < 0:
        raise SystemExit("找不到 4.1 段落")

    head = text[:idx]
    profile_lines = [l for l in lines if "companion_profile" in l]
    order_lines = [l for l in lines if "companion_order" in l]

    new_text = (
        head
        + marker
        + "（service_area 形如「海口市美兰区」）\n"
        + "\n".join(profile_lines)
        + "\n\n"
        + "-- 4.2 订单服务区域（address 形如「海南省海口市美兰区人民大道43号」）\n"
        + "\n".join(order_lines)
        + "\n"
    )
    MIGRATION.write_text(new_text, encoding="utf-8")
    print("已重写 backfill 段（{n} 行）".format(n=len(lines)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
