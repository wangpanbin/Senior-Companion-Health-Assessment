# -*- coding: utf-8 -*-
"""校验指标字典的「人读面」与「机器面」是否逐条一致（V3.5 起建立）。

用法：python tools/check_metric_dict_parity.py
不一致时非零退出。
"""
import io
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
YML = ROOT / "backend/src/main/resources/metrics/metric-dictionary.yml"
DOC = ROOT / "docs/spec/metric-dictionary.md"

# 只认**指标表行**里的 key（行首是 `| \`key\` |`）——
# 正文里也会出现 `companion_profile.score` 这样的**列名**，那不是指标 key。
TABLE_KEY_PATTERN = re.compile(r"^\|\s*`([a-z][a-z0-9_]*\.[a-z0-9_.]+)`\s*\|", re.M)


def main():
    data = yaml.safe_load(io.open(YML, encoding="utf-8"))
    machine = set(m["key"] for m in data.get("metrics", []))
    human = set(TABLE_KEY_PATTERN.findall(io.open(DOC, encoding="utf-8").read()))

    missing = sorted(machine - human)   # 机器面有、人读面没有
    extra = sorted(human - machine)     # 人读面有、机器面没有

    if missing:
        print("以下指标在机器面已登记，但人读面没有：", file=sys.stderr)
        for k in missing:
            print("  " + k, file=sys.stderr)
    if extra:
        print("以下指标在人读面出现，但机器面没有登记：", file=sys.stderr)
        for k in extra:
            print("  " + k, file=sys.stderr)

    if missing or extra:
        print("\n人读面与机器面不一致 —— 这本身就是口径漂移（决策 6.2）。", file=sys.stderr)
        return 1

    print("[OK] 人读面与机器面逐条一致：{n} 条指标".format(n=len(machine)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
