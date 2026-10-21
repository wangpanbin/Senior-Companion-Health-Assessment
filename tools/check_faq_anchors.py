# -*- coding: utf-8 -*-
"""校验 FAQ 知识库（`ai-service/knowledge/faq.json`）的格式与文档锚点。

用法：python tools/check_faq_anchors.py
退出码：0 = 全部通过；1 = 存在失败项（失败明细以 ❌ 打印）。

查什么
------
① FAQ 文件存在，且顶层是合法 JSON 数组
② 条目数 ≥ 25（领域就那么大，条目太少说明覆盖没做完）
③ 每条必需字段齐全（id / intent / question / keywords / answer / source / anchor）
④ id 全局唯一、格式为小写短横线
⑤ source 指向的文件真实存在
⑥ **anchor 的标题文本能在 source 文件里逐字找到**（本脚本的核心价值，见下）
⑦ answer 里不含医疗建议类禁用词
⑧ keywords 非空，且不超过每条目上限（见 MAX_KEYWORDS_PER_ENTRY）

为什么要校验 anchor
------------------
FAQ 的每条答案都配一个 `source`（仓库相对路径）+ `anchor`（该文件里真实存在的
标题行原文）。这两列是 FAQ 与接口文档之间的**引用**：`sources` 字段会原样展示
给用户，读者靠它回到文档核对「平台到底是不是这么规定的」。

没有这道校验的话，文档一改标题（`## 5. 取消订单` 改成 `## 5. 取消订单（V3.2）`），
FAQ 还在原地，但引用已经指不到任何地方 —— 家属点进去找不到依据，FAQ 就在
**没有任何报错、没有任何告警**的情况下变成过期答案。FAQ 是助手在降级模式下
唯一的答案来源，答案过期 = 助手整条链路静默失真。

所以第 ⑥ 项是硬失败：anchor 找不到就是漂移，必须改 FAQ 或改文档，不许「先放着」。

与其它门禁的关系
----------------
第 ⑦ 项的禁用词表**直接读**单一真源
`backend/src/main/resources/compliance/medical-advice-words.txt`
（Java 侧 `ComplianceCheckUtil` 与 Python 侧 `ai-service/app/paths.py`
读的是同一个文件）。

⚠️ **本脚本不再内嵌词表副本。**
内嵌副本看起来「离线可跑」，实际是埋了一个静默分叉：改了真源、这份副本没改，
FAQ 里就会出现能被输出护栏整段拦掉的答案 —— 降级模式下家属什么也拿不到，
而且是从自己写的答案里被拦掉的，**而这道门禁还显示通过**。
词表是合规红线，宁可让门禁红，也不让它查一份过期副本。

零依赖
------
只用 Python 标准库（json / pathlib / re / sys），与仓库既有
`tools/check_*.py` 一致：CI 的 job 没有承诺安装任何第三方包。
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FAQ_PATH = ROOT / "ai-service" / "knowledge" / "faq.json"

#: 条目数下限。
MIN_ENTRY_COUNT = 25

#: 每条必需的字段。
REQUIRED_FIELDS = ("id", "intent", "question", "keywords", "answer", "source", "anchor")

#: 每条目关键词数上限。
#:
#: ⚠️ 这里原先是「3–6 个」—— 那是**手工设计**关键词时的口径（原始 140 词 / 30 条 = 4.7 个）。
#: 2026-10-21 引入大模型生成的问法池之后，关键词要覆盖 179 条口语化问法，
#: 3–6 个压不住：实测把上限压到 6，问法池召回率掉到 **86.7%**（达不到 95% 目标线）。
#:
#: 关键词是**子串匹配** —— 多一个词就多一次误命中的机会，所以要设上限而不是放开。
#: 实测扫描（180 条问法池）：
#:   上限  8 → 召回 86.7%　10 → 94.4%　12 → 95.6%　14 → 96.7%（与不限同值）
#: 取 14：既过 95% 目标线，又比「不限」少 5 个关键词。
#: 详见 ``docs/spec/V3.7-faq-question-pool.md``。
MAX_KEYWORDS_PER_ENTRY = 14

#: id 必须是小写短横线（faq-order-cancel-01 这种）。
ID_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")

#: 医疗建议类禁用词的**单一真源**（Java `ComplianceCheckUtil` /
#: Python `ai-service/app/paths.py` 读的都是这一个文件）。
#:
#: 格式：一行一个词；空行与 `#` 开头的行被忽略 —— 与两侧的解析口径一致。
COMPLIANCE_WORDS_PATH = (
    ROOT / "backend" / "src" / "main" / "resources" / "compliance" / "medical-advice-words.txt"
)

#: 词数下限 —— 低于此值说明读到了错文件（与 Java / Python 侧同口径）。
MIN_COMPLIANCE_WORDS = 10

#: 助手输出里同样不得出现的派单措辞（与 ADR-0016 决策 4.4 一致）。
#: 属于产品边界而非合规红线，但 FAQ 答案同样会被输出护栏处理。
ASSIGNMENT_FORBIDDEN_WORDS = (
    "已指派",
    "已分配",
    "自动派单",
)


#: 失败项收集（main() 统一打印）。
failures = []


def fail(message):
    """登记一条失败项。"""
    failures.append(message)


def load_compliance_words(path):
    """读**共享词表**（单一真源），返回词元组。

    读不到 / 词数异常 → 抛错。这道门禁自己读不到词表时**必须红**，
    否则它会「什么都不查还显示通过」—— 那比没有这道门禁更危险。
    """
    if not path.is_file():
        raise FileNotFoundError("合规词表不存在：{}".format(path))
    words = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        words.append(line)
    if len(words) < MIN_COMPLIANCE_WORDS:
        raise ValueError(
            "合规词表词数异常：{} < {}".format(len(words), MIN_COMPLIANCE_WORDS))
    return tuple(words)


def load_entries():
    """读取 FAQ 文件，返回 (entries, error)。"""
    if not FAQ_PATH.is_file():
        return None, "FAQ 文件不存在：{}".format(FAQ_PATH.relative_to(ROOT).as_posix())
    try:
        raw = json.loads(FAQ_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return None, "FAQ 文件不是合法 JSON：{}（{}）".format(
            FAQ_PATH.relative_to(ROOT).as_posix(), exc)
    if not isinstance(raw, list):
        return None, "FAQ 文件顶层必须是数组：{}".format(
            FAQ_PATH.relative_to(ROOT).as_posix())
    return raw, None


def source_lines(source_path):
    """读 source 文件的所有行（失败返回 None）。"""
    try:
        return source_path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return None


def main():
    entries, error = load_entries()
    if error:
        print("❌ {}".format(error))
        return 1

    try:
        medical_words = load_compliance_words(COMPLIANCE_WORDS_PATH)
    except (OSError, ValueError) as exc:
        print("❌ 共享合规词表读取失败：{}".format(exc))
        print("   来源：{}".format(COMPLIANCE_WORDS_PATH.relative_to(ROOT).as_posix()))
        print("   这道门禁必须读到真源才有效 —— 读不到就红，绝不跳过。")
        return 1

    print("✅ ① FAQ 文件存在且是合法 JSON 数组（{} 条）".format(len(entries)))
    print("   ⑦ 的禁用词表读自共享真源（{} 词）".format(len(medical_words)))

    if len(entries) < MIN_ENTRY_COUNT:
        fail("FAQ 条目数不足：{} 条 < 下限 {} 条".format(len(entries), MIN_ENTRY_COUNT))
    else:
        print("✅ ② 条目数 {} ≥ {}".format(len(entries), MIN_ENTRY_COUNT))

    seen_ids = {}
    source_cache = {}
    checked_anchor = 0

    for index, item in enumerate(entries):
        tag = "第 {} 条".format(index + 1)

        # ③ 必需字段齐全
        if not isinstance(item, dict):
            fail("{} 不是对象".format(tag))
            continue

        missing = [key for key in REQUIRED_FIELDS if not item.get(key)]
        if missing:
            fail("{} 缺字段：{}".format(tag, "、".join(missing)))
            continue

        entry_id = str(item["id"])
        tag = "{}（{}）".format(tag, entry_id)

        # ④ id 唯一 + 格式
        if entry_id in seen_ids:
            fail("{} id 与第 {} 条重复：{}".format(tag, seen_ids[entry_id] + 1, entry_id))
        else:
            seen_ids[entry_id] = index
        if not ID_PATTERN.match(entry_id):
            fail("{} id 不是小写短横线格式：{}".format(tag, entry_id))

        # ⑧ keywords 非空
        keywords = item["keywords"]
        if not isinstance(keywords, list) or not keywords:
            fail("{} keywords 必须是非空数组".format(tag))
        elif not all(isinstance(kw, str) and kw.strip() for kw in keywords):
            fail("{} keywords 里存在空值或非字符串".format(tag))
        elif len(keywords) > MAX_KEYWORDS_PER_ENTRY:
            print("💡 {} keywords 有 {} 个，超过上限 {}".format(
                tag, len(keywords), MAX_KEYWORDS_PER_ENTRY))

        # ⑦ answer 不含禁用词（词表读自共享真源）
        answer = str(item["answer"])
        hits = [word for word in medical_words if word in answer]
        if hits:
            fail("{} answer 命中医疗建议禁用词：{}".format(tag, "、".join(hits)))
        hits = [word for word in ASSIGNMENT_FORBIDDEN_WORDS if word in answer]
        if hits:
            fail("{} answer 命中派单措辞禁用词：{}".format(tag, "、".join(hits)))

        # ⑤ source 文件存在
        source = str(item["source"])
        source_path = (ROOT / source).resolve()
        if not source_path.is_file():
            fail("{} source 指向的文件不存在：{}".format(tag, source))
            continue

        # ⑥ anchor 标题行逐字命中
        anchor = str(item["anchor"])
        if source not in source_cache:
            source_cache[source] = source_lines(source_path)
        lines = source_cache[source]
        if lines is None:
            fail("{} source 文件读取失败：{}".format(tag, source))
            continue
        if not any(line.strip() == anchor for line in lines):
            if any(anchor in line for line in lines):
                fail("{} anchor 不是完整的标题行：{}".format(tag, anchor))
            else:
                fail("{} anchor 在 {} 里找不到（文档已漂移？）".format(tag, source))
        else:
            checked_anchor += 1

    if seen_ids:
        print("✅ ③ 必需字段齐全　④ id 唯一且为小写短横线（{} 个）".format(len(seen_ids)))
    print("✅ ⑤ source 文件均存在　⑥ anchor 标题行逐字命中（{} 条）".format(checked_anchor))
    print("✅ ⑦ answer 未命中禁用词　⑧ keywords 均非空")

    if failures:
        print("")
        print("❌ FAQ 校验未通过，共 {} 项：".format(len(failures)))
        for message in failures:
            print("  - " + message)
        return 1

    print("")
    print("✅ FAQ 知识库校验全过：{} 条，锚点全部对得上，答案未被护栏禁用词拦截".format(
        len(entries)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
