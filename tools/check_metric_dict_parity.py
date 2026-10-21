# -*- coding: utf-8 -*-
"""校验指标字典的「人读面」与「机器面」是否一致（V3.5 起建立，V3.6 收口）。

用法：python tools/check_metric_dict_parity.py
不一致时非零退出。

零依赖（只用标准库）
--------------------
CI 的 job 里没有承诺装 PyYAML，而 ci.yml 顶部明写「不要引入 pom.xml / package.json
之外的依赖」。所以机器面用 `tools/_metric_dict_reader.py` 里的极小读取器解析，
不 import yaml。详见那个模块的注释。

比什么
------
① key 集合：机器面有、人读面没有 / 人读面有、机器面没有（V3.5 起就有）
② **数据来源的口径矛盾**：人读面「数据来源」列里提到的**每一个字段名**，
   机器面的 source 里必须也提到。

②为什么不是「逐字相等」：人读面允许合法简写 —— 「同上」、只写表名、
写成 `companion_order(..., payment_status)`。逐字相等会报 20+ 条噪声，
真漂移反而被淹没。

②为什么不是「逐字相等」的另一头（机器面多写的列不算错）：机器面写得更精确是常态
（如 `companion_order(status, create_time, deleted)`），人读面省略不算漂移。
所以规则是**单向包含**：人读面提到的列 ⊆ 机器面提到的列。

这个方向能抓住真实漂移：V3.6 收口时人读面写 `visit_time`、机器面与 SQL 都是
`create_time` —— 人读面提了一个机器面没有的列，正是「矛盾」。

名字/范围/时间三列带大量人工措辞（「**当天**（按 `create_time`…）」），
逐字比会天天误报，所以不比；它们靠「同一份字典 + 代码注释 + ADR」约束。
"""
import io
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _metric_dict_reader import load_metrics, mentioned_columns  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/spec/metric-dictionary.md"

# 只认**指标表行**里的 key（行首是 `| \`key\` |`）——
# 正文里也会出现 `companion_profile.score` 这样的**列名**，那不是指标 key。
TABLE_KEY_PATTERN = re.compile(r"^\|\s*`([a-z][a-z0-9_]*\.[a-z0-9_.]+)`\s*\|", re.M)

# 人读面表格行：| `key` | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
TABLE_ROW_PATTERN = re.compile(
    r"^\|\s*`(?P<key>[a-z][a-z0-9_]*\.[a-z0-9_.]+)`\s*\|(?P<rest>.*)$", re.M)


def strip_md(text):
    """去掉 Markdown 强调与行内代码，让两边能按「纯文本」比。"""
    text = text.replace("**", "")
    text = text.replace("`", "")
    return text.strip()


def load_human_sources():
    """从人读面表格里取出每个 key 的「数据来源」列 → {key: source}。"""
    sources = {}
    for m in TABLE_ROW_PATTERN.finditer(io.open(DOC, encoding="utf-8").read()):
        key = m.group("key")
        # 表格行是 `| key | 名称 | 范围 | 时间 | 来源 | 公式 | 版本 |`
        cells = [c.strip() for c in m.group("rest").split("|")]
        # rest 是 key 之后的剩余列：名称/范围/时间/来源/公式/版本 → 第 4 格是「数据来源」
        if len(cells) < 4:
            continue
        sources.setdefault(key, strip_md(cells[3]))
    return sources


def main():
    machine = load_metrics()
    machine_keys = set(machine)
    human = load_human_sources()
    human_keys = set(human)

    missing = sorted(machine_keys - human_keys)   # 机器面有、人读面没有
    extra = sorted(human_keys - machine_keys)     # 人读面有、机器面没有

    # 数据来源口径矛盾：人读面提到的字段，机器面必须也提到
    drifted = []
    for key in sorted(machine_keys & human_keys):
        want_cols = mentioned_columns(machine[key].get("source", ""))
        got_cols = mentioned_columns(human[key])
        if not got_cols:
            # 人读面没提任何字段（「同上」/ 只写表名）→ 无从比对，跳过
            continue
        conflict = sorted(got_cols - want_cols)
        if conflict:
            drifted.append((key, machine[key].get("source", ""), human[key], conflict))

    if missing:
        print("以下指标在机器面已登记，但人读面没有：", file=sys.stderr)
        for k in missing:
            print("  " + k, file=sys.stderr)
    if extra:
        print("以下指标在人读面出现，但机器面没有登记：", file=sys.stderr)
        for k in extra:
            print("  " + k, file=sys.stderr)
    if drifted:
        print("以下指标的「数据来源」人读面与机器面口径矛盾：", file=sys.stderr)
        for key, want, got, conflict in drifted:
            print("  {}\n    机器面：{}\n    人读面：{}\n    矛盾字段：{}".format(
                key, want, got, ", ".join(conflict)), file=sys.stderr)

    if missing or extra or drifted:
        return 1
    print("[OK] 指标字典人读面 ↔ 机器面一致：{} 条指标的 key 与数据来源均无矛盾".format(len(machine_keys)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
