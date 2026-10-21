# -*- coding: utf-8 -*-
"""FAQ 问法池：批量生成 → 确定性验收 → 回填 keywords。

## 为什么要有这个工具

FAQ 的检索是「**keyword 子串命中** × 3.0 + 2-gram Jaccard × 2.0」，阈值 `MIN_SCORE = 2.0`。
Jaccard 最大 1.0 → 最多 2.0 分，**恰好等于阈值** ——
所以能稳定命中的只有「问题里包含某条 keyword」这一条路。

这意味着 **keywords 就是命中率本身**，它不是「调优项」，是「功能」。

V3.7 联调实测已经抓到两次关键词缺口：
前端自己的首个预设问题「陪诊费怎么算？」答不上来（费用条关键词不含「陪诊费」），
下单条也不认「约陪诊」。靠人工推导补词会一直漏 —— 本工具把「补词」变成可重复的动作。

## 三种模式

```bash
python tools/gen_faq_questions.py                 # 计划（默认，不联网、不写文件）
python tools/gen_faq_questions.py --live          # 调大模型生成问法池
python tools/gen_faq_questions.py --backfill      # 算回填补丁（不写文件）
python tools/gen_faq_questions.py --backfill --apply   # 真改写 faq.json
```

**默认不联网、不写文件** —— 生成要花钱、回填要改运行时资产，两件事都得显式点头。

## 验收为什么由「检索」决定而不是由「模型」决定

模型只负责**造出像人话的提问**；「这条问法答不答得上来」由 `FaqIndex.best()` 说了算。
本脚本的 `--backfill` 直接 import `ai-service/app/knowledge.py`（**真实检索实现**），
不重写一遍打分逻辑 —— 重写就会漂，漂了就等于用另一套标准验收。

回填时逐个候选关键词**试加 + 全池复测**：
只要加了它会让池里任何一条问法答错条目，这个关键词就不要。
（关键词是子串匹配，抢占是真实存在的：同分时按 id 字典序取胜者。）

## 边界

- 问法**只造平台服务规则类**提问 —— 诊断 / 药物 / 剂量 / 症状类一律不造。
  那些问法会被输入护栏拦成 `REFUSE_MEDICAL`，根本到不了 FAQ 检索，
  放进池子里是纯噪声。（这条由 `ai-service/tests/test_faq_coverage.py` 再兜一次。）
- **只读** `ai-service/.env` 取模型配置；`${VAR}` 引用从环境变量展开。
  key 不落盘、不进日志、不进产物文件。
- 零第三方依赖（标准库 + 仓库内 `app.knowledge`）—— 与 `tools/check_*.py` 同口径。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AI_SERVICE = ROOT / "ai-service"
FAQ_PATH = AI_SERVICE / "knowledge" / "faq.json"
POOL_PATH = AI_SERVICE / "knowledge" / "faq-question-pool.json"
ENV_PATH = AI_SERVICE / ".env"

#: 每个 FAQ 条目生成的问法条数。
QUESTIONS_PER_ENTRY = 6

#: 关键词长度上限（与 spec「判别性短语」口径一致 —— 太长就变成整句了）。
MAX_KEYWORD_LEN = 6

#: 人工剔除的关键词（**带理由**）。
#:
#: 这些词本身**在问法池里没有造成抢占**（确定性门禁放行了它们），
#: 但人工过目时发现：**池子外的问法**里它们会误命中别的条目 ——
#: 而池子外的问法恰恰是我们**看不见**的那部分。
#:
#: 判据一句话：**这个词出现在一个明显属于别的条目的问法里，是否说得通？**
#:
#: ⚠️ 剔除是有代价的（召回会掉），所以只剔真正说得通的那些。
DROP_KEYWORDS: dict[str, str] = {
    "推荐": "「推荐医院 / 推荐科室」会误命中陪诊员推荐条目",
    "还有": "功能词；「还有别的订单吗」会误命中费用条目",
    "节点": "「打卡有哪些节点」是打卡条目的题眼，会误命中订单状态条目",
    "频繁": "泛用副词；「访问太频繁」与催单无关",
    "咋吃": "属医疗问法（会被输入护栏拦下），且「中午咋吃饭」会误命中",
    "算不算": "泛用；「这算不算超时」与推荐无关",
    "怎么建": "「怎么建档」属老人建档条目",
    "咋回事": "泛用；「订单咋回事」会误命中聊天条目",
    "家属端": "导航词，跨场景出现（订单在哪看、密码在哪改）",
}


#: 每个条目的关键词总数上限。
#:
#: ⚠️ 仓库自己的 `tools/check_faq_anchors.py` 建议「3–6 个口语短语」——
#: 那是**手工设计**关键词时的口径（原始 140 个词 / 30 条 = 4.7 个）。
#: 实测：把上限压到 6，召回率掉到 86.7%，达不到 95% 的目标线。
#: 关键词是**子串匹配**，覆盖面就得靠数量堆 —— 这是覆盖与误命中之间的取舍，
#: 不是「随手多写几个」。
#:
#: 上限 14 的依据（实测扫描）：
#:   上限  8 → 召回 86.7%　上限 10 → 94.4%　上限 12 → 95.6%　上限 14 → 96.7%
#: 14 与「不限」同召回，还能少 5 个关键词 —— 白砍。
MAX_KEYWORDS_PER_ENTRY = 14


#: 人工剔除的问法（**带理由**）。
#:
#: 剔除标准：这条问法**永远到不了 FAQ 检索** ——
#: 输入护栏（``guard.scan_input``）会先把它判成医疗意图并直接拒答。
#: 这种问法留在池子里只会**虚增分母**，让召回率看起来比实际高。
#:
#: ⚠️ ``ai-service/tests/test_faq_coverage.py`` 有一条断言
#: 「问法池里没有医疗意图提问」—— 重新生成后若又混进新的，那道门禁会先变红，
#: 由人把新的一条补进这里。
DROP_QUESTIONS: dict[str, str] = {
    "药品用法用量你们能查不": "含「用量」→ 输入护栏判为医疗意图（REFUSE_MEDICAL），"
                              "永远到不了 FAQ 检索",
}


def apply_question_drops(pool: list[dict]) -> list[dict]:
    """剔除永远到不了检索的问法，返回被剔除的条数。"""
    dropped = 0
    kept = []
    for item in pool:
        if item["question"] in DROP_QUESTIONS:
            dropped += 1
            continue
        kept.append(item)
    pool[:] = kept
    return dropped


def keyword_is_dropped(word: str) -> bool:
    """是否在人工剔除清单里。"""
    return word in DROP_KEYWORDS


#: 人工修正的标签（生成后、验收前）。
#:
#: 生成器是「从某条目出发造问法」，但偶尔会造出一条**更像另一个条目**的问法。
#: 这种情况下 LLM 标的 expectId 是错的，而**检索给出的答案才是对的** ——
#: 强行让错误标签胜出，就得往条目里硬塞两个关键词，那是「按噪声调参」。
#:
#: ⚠️ 修正标签**不改变任何测量结果**：这些问法本来就命中了检索给出的那条，
#: 修正只是让「声明的目标」与「实际命中」一致。每条都写明理由。
LABEL_CORRECTIONS: dict[str, tuple[str, str]] = {
    "陪诊员资质咋查": (
        "faq-companion-audit-01",
        "问的是「资质」，属审核口径；且 audit-01 的 2-gram 重合度更高"
        "（0.154 vs 0.143），检索判给 audit-01 是对的",
    ),
    "我想知道老人现在走到哪一步了 订单里能看到吗": (
        "faq-order-status-02",
        "问的是「订单里能看到吗」—— 订单进度时间线，"
        "不是「打卡是什么」；检索判给 order-status-02 是对的",
    ),
}

#: 关键词数量上下限。
MIN_KEYWORDS = 1
MAX_KEYWORDS = 4

#: `${VAR}` 引用（`.env` 里只写引用，真实 key 在环境变量里）。
_ENV_REF = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


# ──────────────────────────────────────────────────────────────────────
# 配置读取
# ──────────────────────────────────────────────────────────────────────


def load_env_file(path: Path) -> dict[str, str]:
    """读 `.env`，并把 `${VAR}` 展开成环境变量的值。

    与 python-dotenv 的行为一致（`${VAR}` 引用从环境变量取）。
    真实 key 因此只存在于进程环境里，磁盘上不落明文。
    """
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")

        def _expand(match: "re.Match[str]") -> str:
            return os.environ.get(match.group(1), "")

        values[key] = _ENV_REF.sub(_expand, value)
    return values


def resolve_model_config() -> tuple[str, str, str]:
    """返回 (base_url, api_key, model)。

    优先级：环境变量 > `.env`（与 pydantic-settings 一致）。
    """
    env = load_env_file(ENV_PATH)

    def pick(name: str) -> str:
        return os.environ.get(name) or env.get(name, "")

    base_url = pick("LLM_BASE_URL").strip()
    api_key = pick("LLM_API_KEY").strip()
    model = pick("LLM_MODEL").strip()
    if not base_url or not model:
        raise SystemExit(
            "未配置 LLM_BASE_URL / LLM_MODEL —— 请在 ai-service/.env 或环境变量里补上。\n"
            "（没有模型也能用助手：那是降级模式，只是回答直接给标准答案原文。）"
        )
    return base_url.rstrip("/"), api_key, model


# ──────────────────────────────────────────────────────────────────────
# 真实检索实现（**不重写打分逻辑**）
# ──────────────────────────────────────────────────────────────────────


# ──────────────────────────────────────────────────────────────────────
# 生成
# ──────────────────────────────────────────────────────────────────────

SYSTEM_PROMPT = """你是「银龄伴诊」平台客服助手的需求分析师。
任务：为一条平台服务规则 FAQ 生成 6 条**真实家属可能打出来的问法**。

严格边界（违反任何一条都算严重错误）：
1. 只生成「平台服务规则类」提问：怎么下单、服务费多少、怎么取消或改约、怎么催单、
   陪诊员怎么推荐、陪诊员怎么审核、怎么评价、怎么投诉、怎么打卡签到、怎么建档、
   怎么绑定老人账号、用药提醒怎么用、怎么和陪诊员聊天、会收到哪些通知、
   登录登出怎么运作、平台能做什么不能做什么。
2. 绝不生成医疗类提问：不提疾病、不提症状、不提诊断、不提药物与用法、
   不提剂量、不提检查结果、不提「我该吃什么药」「这个能不能停」这类问题。
   家属问「我该吃什么药」不归这个助手管。
3. 问法必须像真实用户在聊天框里打出来的：口语、短、不用书面语、不用客服腔、
   结尾不加标点。
4. 6 条问法之间要有明显差异，分别覆盖这些变体：
   ① 口语化短句  ② 近义说法（同一件事换个说法）  ③ 行业黑话或行话
   ④ 带常见错别字或打字错误  ⑤ 长一点的完整句子  ⑥ 极简短语（2~6 个字）

输出要求（**只输出 JSON**，不要任何解释文字、不要 markdown 代码块）：
{"questions": [{"question": "家属的问法", "keywords": ["判别词1", "判别词2"]}, ...]}

keywords 要求：
- 2~4 个，每个**不超过 6 个字**。
- 必须是这条问法里**能判定它属于该 FAQ** 的判别性短语。
- **不要写整句问法** —— 关键词是子串匹配，写整句会导致家属换个说法就匹配不上。
- **不要写与其它 FAQ 共用的泛词**（如「怎么算」「多少钱」「怎么办」这类谁都能沾上的词）。
  泛词会让这条问法被别的条目抢走。
- 如果某个判别短语本来就出现在该 FAQ 已有关键词里，就换一个。"""


def build_user_prompt(entry: dict) -> str:
    """针对单条 FAQ 的生成提示。"""
    return (
        "目标 FAQ：\n"
        "问题：{question}\n"
        "标准答案：{answer}\n"
        "已有关键词：{keywords}\n\n"
        "请生成 6 条问法，覆盖上面列的 6 种变体。"
    ).format(
        question=entry["question"],
        answer=entry["answer"],
        keywords="、".join(entry.get("keywords", [])) or "（无）",
    )


def call_llm(base_url: str, api_key: str, model: str, user_prompt: str,
              timeout: int = 60) -> str:
    """调一次 OpenAI 兼容的 chat completions，返回原始文本。"""
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": 0.7,
    }
    request = urllib.request.Request(
        "{}/chat/completions".format(base_url),
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer {}".format(api_key),
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    return body["choices"][0]["message"]["content"]


def parse_questions(raw: str) -> list[dict]:
    """从模型返回里抽出问法列表。

    模型偶尔会包 markdown 代码块或前后带话 —— 这里做**宽松但严格**的解析：
    找不到合法 JSON 就抛错，不静默接受半截结果。
    """
    text = raw.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"```\s*$", "", text).strip()
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("模型返回里找不到 JSON 对象：{}".format(text[:200]))
    data = json.loads(text[start : end + 1])
    questions = data.get("questions")
    if not isinstance(questions, list):
        raise ValueError("模型返回的 JSON 里没有 questions 数组")
    return questions


def clean_question(text: str) -> str:
    """清洗问法：去空白、去尾部标点。"""
    value = re.sub(r"\s+", " ", str(text or "")).strip()
    return value.rstrip("。？！?!.,，、；;")


def clean_keywords(words) -> list[str]:
    """清洗关键词：去空白、去尾部标点、限长、限个数、去重。"""
    result: list[str] = []
    for word in words or []:
        value = re.sub(r"\s+", "", str(word or "")).strip()
        value = value.rstrip("。？！?!.,，、；;：:")
        if not value or len(value) > MAX_KEYWORD_LEN:
            continue
        if value not in result:
            result.append(value)
    return result[:MAX_KEYWORDS]


# ──────────────────────────────────────────────────────────────────────
# 回填
# ──────────────────────────────────────────────────────────────────────


def build_index(entries: list[dict]):
    """用真实实现构造 FaqIndex（keywords 可覆盖）。

    `ai-service/app/knowledge.py` 只依赖标准库（json / dataclasses / pathlib），
    `app/__init__.py` 也不拉 pydantic —— 所以 `tools/` 里可以零依赖地复用它，
    **不重写一遍打分逻辑**（重写就会漂，漂了就等于用另一套标准验收）。
    """
    if str(AI_SERVICE) not in sys.path:
        sys.path.insert(0, str(AI_SERVICE))
    from app.knowledge import FaqEntry, FaqIndex  # noqa: PLC0415

    return FaqIndex(
        entries=[
            FaqEntry(
                id=str(item["id"]),
                intent=str(item.get("intent", "FAQ_PLATFORM")),
                question=str(item["question"]),
                keywords=tuple(item.get("keywords", [])),
                answer=str(item["answer"]),
                source=str(item.get("source", "")),
                anchor=str(item.get("anchor", "")),
            )
            for item in entries
        ]
    )


def failure_keys(failures: list[dict]) -> set:
    """失败明细的稳定标识（用于比较「有没有新增失败」）。"""
    return {(item["question"], item["expectId"]) for item in failures}


def keyword_is_discriminating(word: str, entry_id: str, pool: list[dict]) -> bool:
    """这个关键词在问法池里**是否具有判别性**。

    一个词如果同时出现在**三个以上**条目的问法里，它就是个泛词 ——
    家属问出含这个词的话，命中的很可能是别人的条目。

    ⚠️ 阈值为什么是 3 而不是 1：
    「催单」同时出现在「能催一下陪诊员吗」和「催单能催几次」的问法里 ——
    但它是**催促域的专有词**，不是泛词。而「还有」「怎么查」这类词
    才是真正的泛词。
    """
    owners = {
        item["expectId"]
        for item in pool
        if word in item["question"]
    }
    return len(owners) < 3 and (not owners or entry_id in owners)


def keyword_is_occupied(word: str, entry_id: str, entries: list[dict]) -> bool:
    """这个关键词**是否已被别的条目占用**（仅用于人工复核提示，不直接拒绝）。

    ⚠️ 这里**不能**直接拒绝 —— 关键词是子串匹配，两个条目共用一个关键词，
    看起来会「同分靠 id 决胜」，但**2-gram Jaccard 项就是那个裁决者**：
    问法越接近某条目的标准问法，它越该赢。

    真实例子：「催单能催几次」这条问法里，
    「催单」同时是「能催一下陪诊员吗」和「催单能催几次」的关键词 ——
    加上它之后，urge-02 的得分 = 3.0 + 2.0×0.714 = 4.43，
    urge-01 = 3.0 + 2.0×0.077 = 3.15，**urge-02 正确胜出**。
    所以「被占用」只是提示，不是拒绝理由。
    """
    for entry in entries:
        if entry["id"] == entry_id:
            continue
        if word in entry.get("keywords", []):
            return True
    return False


def evaluate(entries: list[dict], pool: list[dict]) -> tuple[int, list[dict]]:
    """用真实检索跑问法池，返回 (命中数, 失败明细)。"""
    index = build_index(entries)
    hits = 0
    failures: list[dict] = []
    for item in pool:
        found = index.best(item["question"])
        if found is not None and found[0].id == item["expectId"]:
            hits += 1
            continue
        actual = found[0].id if found is not None else None
        score = round(found[1], 2) if found is not None else 0.0
        failures.append({
            "question": item["question"],
            "expectId": item["expectId"],
            "actualId": actual,
            "score": score,
        })
    return hits, failures


def plan_backfill(entries: list[dict], pool: list[dict]) -> tuple[list[dict], list[dict]]:
    """算出要并入哪些关键词。

    **两轮**：

    ① **只收有可测量召回收益的** —— 加了它，问法池的命中数必须**变多**。
       贪心顺序上覆盖问法多的词先试，所以冗余的（与已采纳词重叠）会因零收益被淘汰。
       这一轮保证「每一个留下的关键词都有实测收益」，不会全局膨胀。

    ② **只对仍有没有命中问法的条目**定点补零收益的关键词。
       限定在有缺口的条目上，所以「为了池子外的问法多覆盖一点」不会变成全局加词。

    硬约束（两轮都适用）：**不许制造任何新的失败** —— 池里每条问法仍然答到它声明的条目。

    返回 (新 entries, 采纳记录)。
    """
    from copy import deepcopy  # noqa: PLC0415

    new_entries = deepcopy(entries)

    # 候选关键词：按问法声明的目标条目归拢
    candidates: dict[str, list[str]] = {}
    for item in pool:
        for word in item.get("keywords", []):
            bucket = candidates.setdefault(item["expectId"], [])
            if word not in bucket:
                bucket.append(word)

    def try_add(current, entry_id, word, require_gain):
        """试加一个关键词。返回 (新 entries, 是否采纳, 命中数)。"""
        target = next(e for e in current if e["id"] == entry_id)
        if word in target["keywords"]:
            return current, False, 0
        # 守卫：每条目关键词总数上限
        if len(target["keywords"]) >= MAX_KEYWORDS_PER_ENTRY:
            return current, False, 0
        # 守卫：人工剔除清单（池子外会误命中的泛词）
        if keyword_is_dropped(word):
            return current, False, 0
        # 守卫：横跨三个以上条目的泛词 → 不具判别性，不要
        if not keyword_is_discriminating(word, entry_id, pool):
            return current, False, 0
        trial = deepcopy(current)
        trial_target = next(e for e in trial if e["id"] == entry_id)
        trial_target["keywords"] = list(trial_target["keywords"]) + [word]

        current_hits, current_failures = evaluate(current, pool)
        trial_hits, trial_failures = evaluate(trial, pool)
        if failure_keys(trial_failures) - failure_keys(current_failures):
            return current, False, 0  # 制造了新的失败 → 会抢占，不要
        if trial_hits < current_hits:
            return current, False, 0  # 反而变差 → 不要
        if require_gain and trial_hits <= current_hits:
            # ⚠️ 零收益的关键词第一轮不要 —— 它不带来任何**可测量**的召回收益，
            # 却要多占一个子串匹配位（多一次在池子外问法上误命中的机会）。
            return current, False, 0
        return trial, True, trial_hits

    accepted: list[dict] = []

    # ── 第一轮：只收有可测量召回收益的 ──────────────────────────────
    for entry_id, words in candidates.items():
        if not any(e["id"] == entry_id for e in new_entries):
            continue
        ordered = sorted(
            words,
            key=lambda w: -sum(1 for it in pool if w in it["question"]),
        )
        for word in ordered:
            new_entries, ok, hits = try_add(new_entries, entry_id, word, True)
            if ok:
                accepted.append({
                    "id": entry_id, "keyword": word,
                    "hits": hits, "total": len(pool), "round": 1,
                })

    # ── 第二轮：只对仍有没有命中问法的条目补零收益关键词 ──────────────
    _, failures = evaluate(new_entries, pool)
    gap_entries = sorted({item["expectId"] for item in failures})
    for entry_id in gap_entries:
        for word in candidates.get(entry_id, []):
            new_entries, ok, hits = try_add(new_entries, entry_id, word, False)
            if ok:
                accepted.append({
                    "id": entry_id, "keyword": word,
                    "hits": hits, "total": len(pool), "round": 2,
                })
    return new_entries, accepted


# ──────────────────────────────────────────────────────────────────────
# 入口
# ──────────────────────────────────────────────────────────────────────


def dump_faq(entries: list[dict]) -> str:
    """按仓库原有排版输出 FAQ（``keywords`` 数组保持单行）。

    ⚠️ 为什么要自己排版：`json.dumps(indent=2)` 会把 keywords 拆成多行，
    于是**每个**条目的 diff 都变成「整段重排」—— 30 条全变，
    评审时根本看不出哪些关键词是真的新增的、哪些只是换了个排版。
    保持原排版，diff 里就只剩真正变了的条目。
    """
    text = json.dumps(entries, ensure_ascii=False, indent=2)

    def _collapse(match: "re.Match[str]") -> str:
        items = [
            line.strip().rstrip(",")
            for line in match.group(1).splitlines()
            if line.strip()
        ]
        return '"keywords": [' + ", ".join(items) + "]"

    text = re.sub(r'"keywords": \[\s*(.*?)\s*\]', _collapse, text, flags=re.DOTALL)
    return text + "\n"


def load_faq_entries() -> list[dict]:
    if not FAQ_PATH.is_file():
        raise SystemExit("FAQ 文件不存在：{}".format(FAQ_PATH))
    return json.loads(FAQ_PATH.read_text(encoding="utf-8"))


def apply_label_corrections(pool: list[dict]) -> list[dict]:
    """按 :data:`LABEL_CORRECTIONS` 修正标签，返回修正条数。"""
    corrected = 0
    for item in pool:
        fix = LABEL_CORRECTIONS.get(item["question"])
        if fix and item["expectId"] != fix[0]:
            item["expectId"] = fix[0]
            item["labelCorrected"] = True
            item["labelCorrectionReason"] = fix[1]
            corrected += 1
    return corrected


def load_pool() -> list[dict]:
    if not POOL_PATH.is_file():
        raise SystemExit("问法池不存在：{}（先跑 --live 生成）".format(POOL_PATH))
    pool = json.loads(POOL_PATH.read_text(encoding="utf-8"))
    apply_label_corrections(pool)
    apply_question_drops(pool)
    return pool


def fix_pool() -> int:
    """把标签修正与问法剔除**落到问法池文件里**。

    修正只存在于内存里是不够的 —— `ai-service/tests/test_faq_coverage.py`
    读的是文件，不读内存。
    """
    if not POOL_PATH.is_file():
        raise SystemExit("问法池不存在：{}".format(POOL_PATH))
    pool = json.loads(POOL_PATH.read_text(encoding="utf-8"))
    before = len(pool)
    corrected = apply_label_corrections(pool)
    dropped = apply_question_drops(pool)
    POOL_PATH.write_text(
        json.dumps(pool, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("问法池已更新：{} → {} 条".format(before, len(pool)))
    print("  标签修正 {} 条；剔除问法 {} 条".format(corrected, dropped))
    for item in pool:
        if item.get("labelCorrected"):
            print("  · 「{}」→ {}".format(item["question"], item["expectId"]))
            print("    {}".format(item.get("labelCorrectionReason", "")))
    return 0


def do_plan() -> int:
    entries = load_faq_entries()
    print("== FAQ 问法池 · 计划 ==")
    print("FAQ 条目：{} 条".format(len(entries)))
    print("每条生成：{} 条问法 → 合计 {} 条".format(
        QUESTIONS_PER_ENTRY, len(entries) * QUESTIONS_PER_ENTRY))
    print("问法池输出：{}".format(POOL_PATH.relative_to(ROOT).as_posix()))
    print("回填目标：{}".format(FAQ_PATH.relative_to(ROOT).as_posix()))
    print("")
    print("下一步：")
    print("  1) python tools/gen_faq_questions.py --live      # 调模型生成（花钱）")
    print("  2) python tools/gen_faq_questions.py --backfill  # 算回填补丁（不写文件）")
    print("  3) python tools/gen_faq_questions.py --backfill --apply  # 真改写 faq.json")
    print("")
    print("⚠️ 问法来源是**大模型生成的模拟问法**，不是真实用户数据 ——")
    print("   PRD 风险表第 5 条「FAQ 覆盖面未经真实用户验证」据此**保持未关闭**。")
    return 0


def do_live() -> int:
    entries = load_faq_entries()
    base_url, api_key, model = resolve_model_config()
    print("== 生成问法池 ==")
    print("端点：{}　模型：{}".format(base_url, model))
    print("条目：{} 条 × {} 条问法".format(len(entries), QUESTIONS_PER_ENTRY))
    print("")

    pool: list[dict] = []
    seen: set[str] = set()
    for number, entry in enumerate(entries, start=1):
        try:
            raw = call_llm(base_url, api_key, model, build_user_prompt(entry))
            candidates = parse_questions(raw)
        except (urllib.error.URLError, KeyError, ValueError, json.JSONDecodeError) as exc:
            print("  ✗ {} ({}) 生成失败：{}".format(number, entry["id"], exc))
            raise SystemExit("生成中断 —— 问法池不完整，不写文件")

        for candidate in candidates[:QUESTIONS_PER_ENTRY]:
            question = clean_question(candidate.get("question", ""))
            keywords = clean_keywords(candidate.get("keywords"))
            if not question or len(question) < 2 or question in seen:
                continue
            seen.add(question)
            pool.append({
                "question": question,
                "expectId": entry["id"],
                "keywords": keywords,
            })
        print("  ✓ [{:2d}/{}] {} → 累计 {} 条".format(
            number, len(entries), entry["id"], len(pool)))

    if not pool:
        raise SystemExit("没有生成出任何问法 —— 不写文件")

    corrected = apply_label_corrections(pool)
    dropped = apply_question_drops(pool)
    if corrected:
        print("")
        print("标签修正 {} 条（理由见 LABEL_CORRECTIONS）：".format(corrected))
        for item in pool:
            if item.get("labelCorrected"):
                print("  · 「{}」→ {}".format(item["question"], item["expectId"]))
                print("    {}".format(item.get("labelCorrectionReason", "")))
    if dropped:
        print("")
        print("剔除问法 {} 条（永远到不了 FAQ 检索，见 DROP_QUESTIONS）：".format(dropped))
        for question, reason in DROP_QUESTIONS.items():
            print("  · 「{}」—— {}".format(question, reason))

    POOL_PATH.write_text(
        json.dumps(pool, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("")
    print("已写入：{}（{} 条）".format(POOL_PATH.relative_to(ROOT).as_posix(), len(pool)))
    print("下一步：python tools/gen_faq_questions.py --backfill")
    return 0


def do_backfill(apply_changes: bool) -> int:
    entries = load_faq_entries()
    pool = load_pool()

    base_hits, base_failures = evaluate(entries, pool)
    print("== 回填前 ==")
    print("问法池：{} 条，命中 {} 条（{:.1f}%）".format(
        len(pool), base_hits, 100.0 * base_hits / max(len(pool), 1)))
    if base_failures:
        print("未命中明细（前 10 条）：")
        for item in base_failures[:10]:
            print("  · 「{}」期望 {} → 实际 {}（{} 分）".format(
                item["question"], item["expectId"], item["actualId"], item["score"]))

    new_entries, accepted = plan_backfill(entries, pool)
    new_hits, new_failures = evaluate(new_entries, pool)
    print("")
    print("== 回填后 ==")
    print("命中 {} → {} 条（{:.1f}%）".format(
        base_hits, new_hits, 100.0 * new_hits / max(len(pool), 1)))
    print("采纳关键词：{} 个".format(len(accepted)))
    for item in accepted:
        print("  + {} ← {}".format(item["id"], item["keyword"]))
    if new_failures:
        print("仍未命中 {} 条：".format(len(new_failures)))
        for item in new_failures[:10]:
            print("  · 「{}」期望 {} → 实际 {}（{} 分）".format(
                item["question"], item["expectId"], item["actualId"], item["score"]))

    # 应用前人工复核清单：确定性门禁只能看到**池子里有的证据**，
    # 池子外的问法没人能替我们把关 —— 短词与高频词最危险，列出来逐条过目。
    warn = []
    for item in accepted:
        word = item["keyword"]
        hits_in_pool = sum(1 for it in pool if word in it["question"])
        occupied = keyword_is_occupied(word, item["id"], new_entries)
        if len(word) <= 3 or hits_in_pool >= 3 or occupied:
            warn.append((word, item["id"], len(word), hits_in_pool, occupied))
    if warn:
        print("")
        print("⚠️ 需人工过目的关键词（短词 / 池内高频 / 与其它条目共用）：")
        for word, entry_id, length, count, occupied in sorted(
                warn, key=lambda x: (x[2], -x[3])):
            marks = []
            if occupied:
                marks.append("与其它条目共用")
            print("  · 「{}」（{} 字，池内出现 {} 次{}）→ {}".format(
                word, length, count,
                "，" + "、".join(marks) if marks else "",
                entry_id))
        print("  这些词最容易在**池子外**的问法上抢占 —— 应用前逐条确认。")

    if not apply_changes:
        print("")
        print("（未写文件。确认无误后加 --apply 真正改写 faq.json）")
        return 0

    if new_hits < base_hits:
        raise SystemExit("回填后命中率下降 —— 拒绝写入")
    FAQ_PATH.write_text(dump_faq(new_entries), encoding="utf-8")
    print("")
    print("已写入：{}".format(FAQ_PATH.relative_to(ROOT).as_posix()))
    print("⚠️ FAQ 是**启动时加载**的 —— 改完必须重启 ai-service 才生效。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="FAQ 问法池：生成 / 验收 / 回填")
    parser.add_argument("--live", action="store_true",
                        help="调大模型生成问法池（会联网、会产生费用）")
    parser.add_argument("--backfill", action="store_true",
                        help="用问法池计算并回填 keywords")
    parser.add_argument("--apply", action="store_true",
                        help="配合 --backfill：真正改写 faq.json")
    parser.add_argument("--fix-pool", action="store_true",
                        help="把标签修正与问法剔除落到问法池文件里")
    args = parser.parse_args()

    if args.live and args.backfill:
        parser.error("--live 与 --backfill 不能同时用")
    if args.live:
        return do_live()
    if args.backfill:
        return do_backfill(args.apply)
    if args.fix_pool:
        return fix_pool()
    if args.apply:
        parser.error("--apply 只能配合 --backfill")
    return do_plan()


if __name__ == "__main__":
    raise SystemExit(main())
