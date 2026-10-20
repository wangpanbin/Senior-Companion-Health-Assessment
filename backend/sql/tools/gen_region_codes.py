# -*- coding: utf-8 -*-
"""V3.4 区域码映射的生成 / 校验器。

区域码格式：<CITY>_<DISTRICT>（全大写拼音），城市前缀（_ 之前）是「区」这一层：

    区域码相等          → 1.0
    同市不同区          → 0.5
    跨市                → 0

用法
----
    python gen_region_codes.py            # 打印 backfill SQL
    python gen_region_codes.py --check    # 校验 V7 迁移里的 CASE 与本生成器逐字一致

「逐字一致」是刻意的：迁移文件是手写的，映射表是 JSON 驱动的，
两者一旦漂移，backfill 出来的数据就会与代码里的期望对不上 ——
而这种漂移**不会报错**，只会让匹配结果悄悄变差。

不参与构建（见 backend/sql/tools/README.md）。
"""
import argparse
import io
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
MAPPING = HERE / "region_code_mapping.json"
MIGRATION = HERE.parent / "V7__matching_structured.sql"

# 两张表各自的「文本列 → 区域码列」
TARGETS = [
    ("companion_profile", "service_area"),
    ("companion_order", "address"),
]


def load_regions():
    with io.open(MAPPING, encoding="utf-8") as f:
        data = json.load(f)
    regions = data.get("regions") or []
    if not regions:
        raise SystemExit("映射表为空：" + str(MAPPING))
    seen = set()
    for r in regions:
        code = r.get("code")
        if not code or "_" not in code:
            raise SystemExit("区域码格式不对（应为 <CITY>_<DISTRICT>）：" + str(r))
        if code in seen:
            raise SystemExit("区域码重复：" + code)
        seen.add(code)
    return regions


def city_of(code):
    """城市前缀 —— 「区」这一层的判定依据"""
    return code.split("_", 1)[0]


def backfill_lines(regions):
    """生成 backfill 的 UPDATE 语句（与 V7 迁移里的写法逐字一致）"""
    lines = []
    for table, text_col in TARGETS:
        for r in regions:
            keyword = r["keywords"][0]
            lines.append(
                "UPDATE `{table}` SET `service_region_code` = '{code}'  "
                "WHERE `service_region_code` IS NULL AND `{col}` LIKE '%{kw}%';".format(
                    table=table, code=r["code"], col=text_col, kw=keyword
                )
            )
    return lines


def build_expected(regions):
    """V7 迁移里应该出现的 backfill 行（逐字）"""
    return backfill_lines(regions)


def check(regions):
    if not MIGRATION.exists():
        raise SystemExit("找不到迁移文件：" + str(MIGRATION))
    text = MIGRATION.read_text(encoding="utf-8")
    expected = build_expected(regions)
    missing = [line for line in expected if line not in text]
    if missing:
        print("V7 迁移里的 backfill 与生成器不一致，缺以下行：", file=sys.stderr)
        for line in missing:
            print("  " + line, file=sys.stderr)
        print("请重新生成并同步 V7__matching_structured.sql", file=sys.stderr)
        return 1

    # 反向检查：迁移里出现 service_region_code 的 UPDATE 行数应当与期望一致
    actual = [
        line.strip()
        for line in text.splitlines()
        if re.match(r"^UPDATE `?(companion_profile|companion_order)`? SET `service_region_code`", line.strip())
    ]
    if len(actual) != len(expected):
        print(
            "backfill 行数不一致：迁移里 {a} 行，生成器 {e} 行".format(a=len(actual), e=len(expected)),
            file=sys.stderr,
        )
        return 1

    print("[OK] V7 迁移的 backfill 与生成器逐字一致（{n} 行）".format(n=len(expected)))
    return 0


def apply(regions):
    """用生成器产出的 backfill 覆盖 V7 迁移里的 backfill 段。

    这样「迁移文件里手写的 CASE」与「JSON 映射」之间就不可能漂移 ——
    漂移的唯一来源是人手改了一边而忘了另一边。
    """
    if not MIGRATION.exists():
        raise SystemExit("找不到迁移文件：" + str(MIGRATION))
    text = MIGRATION.read_text(encoding="utf-8")
    lines = backfill_lines(regions)

    start_marker = "-- 4.1 陪诊员服务区域"
    end_marker = "-- 4.2 订单服务区域"
    if start_marker not in text:
        raise SystemExit("迁移文件里找不到 4.1 段落标记")

    head, rest = text.split(start_marker, 1)
    # 4.2 之后到文件末尾的内容全部丢弃 —— backfill 段是文件的最后一段，
    # 保留旧内容会与新生成的内容重复（曾经踩过：切分点选在标记行中间，
    # 结果旧的 4.2 段落被留在了新段后面，生成器与迁移从此不一致）。
    tail = ""

    block = "\n".join(lines)
    new_text = (
        head
        + start_marker
        + "（service_area 形如「海口市美兰区」）\n"
        + "\n".join(l for l in lines if "companion_profile" in l)
        + "\n\n"
        + end_marker
        + "（address 形如「海南省海口市美兰区人民大道43号」）\n"
        + "\n".join(l for l in lines if "companion_order" in l)
        + "\n"
        + tail
    )
    MIGRATION.write_text(new_text, encoding="utf-8")
    print("已用生成器重写 V7 的 backfill 段（{n} 行）".format(n=len(lines)))
    return 0


def main():
    parser = argparse.ArgumentParser(description="V3.4 区域码映射生成 / 校验器")
    parser.add_argument("--check", action="store_true", help="校验 V7 迁移与生成器是否一致")
    parser.add_argument("--apply", action="store_true", help="用生成器重写 V7 迁移的 backfill 段")
    args = parser.parse_args()

    regions = load_regions()

    if args.check:
        return check(regions)
    if args.apply:
        return apply(regions)

    print("-- 区域码清单 --")
    for r in regions:
        print("  {code:<18} 城市={city:<10} 区县={district:<10} 关键词={kw}".format(
            code=r["code"], city=city_of(r["code"]), district=r["district"], kw=r["keywords"][0]
        ))
    print()
    print("-- backfill SQL --")
    for line in backfill_lines(regions):
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
