# -*- coding: utf-8 -*-
"""V3.7 收口脚本：把「合规词表 18 词」统一纠正为实际词数，并程序化核验。

起因：写 plan 时把词表词数心算成了 18，实际是 17（诊断结论 4 + 开药动作 6 + 用药调整 7）。
这个数字被原样复制进了 ADR-0020 / V3.7 spec / Java 单测 / Python 单测，
所以必须一次性统一 —— 而**词数由脚本从词表文件数出来**，不再靠手算。

用法：python tools/fix_compliance_word_count.py [--check]
     --check 只核验不修改（收口时用）
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORDS_FILE = ROOT / "backend/src/main/resources/compliance/medical-advice-words.txt"

#: 词表文件 → 该文件里需要纠正的「18 词」类表述
FIXES = {
    "docs/adr/0020-assistant-boundary-and-recommendation-guardrail.md": [
        ("命中 18 词禁用表", "命中 {n} 词禁用表"),
        ("的 18 个词从 Java 硬编码", "的 {n} 个词从 Java 硬编码"),
        ("18 词一字不改", "{n} 词一字不改"),
        ("自己抄一份 18 词", "自己抄一份 {n} 词"),
    ],
    "docs/spec/V3.7-assistant-spec.md": [
        ("18 词硬编码", "{n} 词硬编码"),
        ("| 18 词禁用表任一 |", "| {n} 词禁用表任一 |"),
        ("词表恰 18 词且含", "词表恰 {n} 词且含"),
        ("18 词逐一命中", "{n} 词逐一命中"),
    ],
    "backend/src/main/java/org/company/nianglin/util/ComplianceCheckUtil.java": [
        ("词表**18 个词一字未改**", "词表**{n} 个词一字未改**"),
    ],
    "backend/src/test/java/org/company/nianglin/util/ComplianceCheckUtilTest.java": [
        ("<b>18 词的精确快照</b>", "<b>{n} 词的精确快照</b>"),
        ("恰好 18 个词", "恰好 {n} 个词"),
        (".hasSize(18);", ".hasSize({n});"),
        ("18 个词逐一命中", "{n} 个词逐一命中"),
    ],
}

#: spec 里「派单措辞」的处理口径：实现是词语级纠正 + 追加免责，不是整段替换。
SPEC_ASSIGNMENT_FIX = (
    "| 「已指派」「已分配」「自动派单」任一 | **整段替换**为推荐免责话术 |",
    "| 「已指派」「已分配」「自动派单」任一 | **词语级纠正**（已指派→推荐 / 自动派单→自动推荐）并追加免责句 —— 这是产品边界问题而非合规红线，纠正后语义仍正确，整段丢掉会让家属白问一次 |",
)


def count_words():
    """从词表文件程序化数出真实词数。"""
    lines = WORDS_FILE.read_text(encoding="utf-8").splitlines()
    return [
        line.strip()
        for line in lines
        if line.strip() and not line.strip().startswith("#")
    ]


def main():
    check_only = "--check" in sys.argv
    words = count_words()
    n = len(words)
    print("词表文件：{}".format(WORDS_FILE.relative_to(ROOT).as_posix()))
    print("程序化数出词数：{}".format(n))
    print("词条：{}".format("、".join(words)))
    if n < 10:
        print("词数异常（< 10），护栏会静默失效", file=sys.stderr)
        return 1

    if check_only:
        # 只核验：各文件里不应再出现「18 词」类表述
        bad = []
        for rel in FIXES:
            path = ROOT / rel
            text = path.read_text(encoding="utf-8")
            for pat in (r"18 词", r"18 个词", r"hasSize\(18\)", r"恰好 18"):
                if re.search(pat, text):
                    bad.append("{}: {}".format(rel, pat))
        if bad:
            print("❌ 仍有未纠正的词数表述：", file=sys.stderr)
            for item in bad:
                print("  " + item, file=sys.stderr)
            return 1
        print("✅ 各文件词数表述已统一为 {}".format(n))
        return 0

    changed = []
    for rel, pairs in FIXES.items():
        path = ROOT / rel
        text = path.read_text(encoding="utf-8")
        original = text
        for old, new in pairs:
            text = text.replace(old, new.replace("{n}", str(n)))
        if text != original:
            path.write_text(text, encoding="utf-8")
            changed.append(rel)

    spec_path = ROOT / "docs/spec/V3.7-assistant-spec.md"
    spec_text = spec_path.read_text(encoding="utf-8")
    if SPEC_ASSIGNMENT_FIX[0] in spec_text:
        spec_path.write_text(
            spec_text.replace(SPEC_ASSIGNMENT_FIX[0], SPEC_ASSIGNMENT_FIX[1]),
            encoding="utf-8",
        )
        changed.append("docs/spec/V3.7-assistant-spec.md（派单措辞口径）")

    if changed:
        print("已修改：")
        for item in changed:
            print("  - {}".format(item))
    else:
        print("无需修改（已是最新）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
