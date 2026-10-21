"""结构化动作抽取。

## 为什么用正则而不是 LLM

抽取的是**结构化字段**（医院、科室、时间、老人），而这些字段的形态是确定的：
「去 XX 医院」「XX 科」「下周三上午9点」。用正则有几个好处：

- **确定性**：同一句话永远抽出同样的结果，可被单测逐条锁死；
- **不需要模型**：降级路径下也能抽，助手在无 LLM 时照样能把草稿填好；
- **不引入幻觉**：LLM 抽出来的「海南省人民医院」可能是「海南省人民」，正则不会。

## 抽取不到就留空

``FILL_ORDER_DRAFT`` 的 payload **只带抽到的字段**。前端只写存在的字段 ——
所以「我只说了科室没说医院」时，草稿里就只有科室，剩下让用户自己补。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Any

#: 科室关键词。**长的排前面** —— 「心血管内科」必须先于「内科」命中。
DEPARTMENT_KEYWORDS: tuple[str, ...] = (
    "心血管内科", "神经内科", "呼吸内科", "消化内科", "肾内科", "内分泌科",
    "血液内科", "血液科", "肿瘤科", "神经外科", "普通外科", "泌尿外科",
    "妇产科", "儿科", "骨科", "眼科", "口腔科", "耳鼻喉科", "皮肤科",
    "康复科", "中医科", "精神科", "急诊科", "门诊", "内科", "外科",
)

#: 抽取医院名时的**回退停止字**。
#:
#: 遇到这些字就停 —— 「去海南省人民医院」在「去」处停下，
#: 避免把「帮我找个陪诊员去」也吞进医院名。
_HOSPITAL_STOP_CHARS = set("的要去到在想帮请找给和与让从就这那还也再很太可想准备约")
_MAX_HOSPITAL_LEN = 18
_MAX_DEPARTMENT_LEN = 10

#: 抽取科室名时的**前缀回退停止字**。
#:
#: 比医院名多停了「医」「院」两个 —— 科室名里不可能出现这两个字，
#: 而「海南省人民医院心内科」里的「民医院」正是被它们挡住的：
#: 没有它们，前缀会一路回退到「民医院心内科」。
_DEPARTMENT_STOP_CHARS = _HOSPITAL_STOP_CHARS | {"医", "院"}

_WEEKDAY_INDEX = {
    "一": 0, "二": 1, "三": 2, "四": 3, "五": 4, "六": 5, "日": 6, "天": 6,
}

_DATE_PATTERNS = (
    # 2026-10-28 / 2026/10/28
    (re.compile(r"(\d{4})[-/](\d{1,2})[-/](\d{1,2})"), "ymd"),
    # 10月28日 / 10 月 28 号
    (re.compile(r"(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]"), "md"),
    # 下周三 / 这周三 / 周三 / 星期三
    (re.compile(r"(下下|下个|下|这|本)?\s*(?:周|星期)\s*([一二三四五六日天])"), "weekday"),
)

_TIME_PATTERN = re.compile(
    r"(上午|早上|早晨|中午|下午|傍晚|晚上)?\s*(\d{1,2})\s*[点:：]\s*(半|\d{1,2}\s*分?)?"
)


@dataclass
class DraftExtraction:
    """从问题里抽出的下单草稿片段。**只含抽到的字段。**"""

    hospital: str | None = None
    department: str | None = None
    visit_time: str | None = None
    elder_id: int | None = None
    elder_name: str | None = None
    extra: dict[str, Any] = field(default_factory=dict)

    def is_empty(self) -> bool:
        return not any(
            [
                self.hospital,
                self.department,
                self.visit_time,
                self.elder_id,
            ]
        )

    def to_payload(self) -> dict[str, Any]:
        """转成 ``FILL_ORDER_DRAFT`` 的 payload（只含非空字段）。"""
        payload: dict[str, Any] = {}
        for key in ("elderId", "elderName", "hospital", "department", "visitTime"):
            value = getattr(self, _PAYLOAD_ATTRS[key])
            if value:
                payload[key] = value
        return payload


#: payload 键 → DraftExtraction 属性名
_PAYLOAD_ATTRS = {
    "elderId": "elder_id",
    "elderName": "elder_name",
    "hospital": "hospital",
    "department": "department",
    "visitTime": "visit_time",
}


def _is_name_char(ch: str) -> bool:
    """是否是名称里可能出现的字符（中日韩文字、字母、数字）。"""
    return ch.isalnum() or "一" <= ch <= "鿿"


def extract_hospital(text: str) -> str | None:
    """抽取医院名。

    「下周三去海南省人民医院心内科」→ ``海南省人民医院``
    """
    if not text:
        return None
    index = text.find("医院")
    if index < 0:
        return None
    # 「医院」两字本身归入名称
    end = index + 2
    start = index
    while start > 0 and end - start < _MAX_HOSPITAL_LEN:
        ch = text[start - 1]
        if not _is_name_char(ch) or ch in _HOSPITAL_STOP_CHARS:
            break
        start -= 1
    name = text[start:end].strip()
    return name or None


def extract_department(text: str) -> str | None:
    """抽取科室名。

    「海南省人民医院心内科」→ ``心内科``
    """
    if not text:
        return None
    best: tuple[int, str] | None = None
    for keyword in DEPARTMENT_KEYWORDS:
        index = text.find(keyword)
        if index < 0:
            continue
        # 取关键词前最多 4 个字作为前缀（「心血管内科」的「心血管」）
        prefix_start = index
        while prefix_start > 0 and index - prefix_start < 4:
            ch = text[prefix_start - 1]
            if not _is_name_char(ch) or ch in _DEPARTMENT_STOP_CHARS:
                break
            prefix_start -= 1
        candidate = text[prefix_start : index + len(keyword)]
        if len(candidate) > _MAX_DEPARTMENT_LEN:
            candidate = candidate[-_MAX_DEPARTMENT_LEN:]
        if best is None or index < best[0]:
            best = (index, candidate)
    return best[1] if best else None


def _resolve_date(text: str, today: date) -> date | None:
    """从文本里解析日期。"""
    for pattern, kind in _DATE_PATTERNS:
        match = pattern.search(text)
        if not match:
            continue
        if kind == "ymd":
            year, month, day = (int(g) for g in match.groups())
        elif kind == "md":
            month, day = int(match.group(1)), int(match.group(2))
            year = today.year
        else:
            prefix, weekday_ch = match.group(1), match.group(2)
            target = _WEEKDAY_INDEX[weekday_ch]
            if prefix in ("下", "下个", "下下"):
                # 下周 X：本周同 weekday 的下下周
                delta = (target - today.weekday()) % 7
                return today + timedelta(days=delta + 7)
            delta = (target - today.weekday()) % 7
            return today + timedelta(days=delta)
        try:
            return date(year, month, day)
        except ValueError:
            continue
    return None


def _resolve_time(text: str) -> tuple[int, int, int] | None:
    """从文本里解析时刻，返回 ``(时, 分, 秒)``。"""
    match = _TIME_PATTERN.search(text)
    if not match:
        return None
    period, hour_raw, minute_raw = match.groups()
    hour = int(hour_raw)
    if period in ("下午", "傍晚", "晚上") and hour < 12:
        hour += 12
    if period == "中午" and hour < 11:
        hour += 12
    if hour > 23:
        return None
    if minute_raw is None:
        minute = 0
    elif minute_raw == "半":
        minute = 30
    else:
        digits = re.sub(r"\D", "", minute_raw)
        if not digits:
            minute = 0
        else:
            minute = int(digits)
            if minute > 59:
                return None
    return hour, minute, 0


def extract_visit_time(text: str, today: date | None = None) -> str | None:
    """抽取就诊时间，输出 ``yyyy-MM-dd HH:mm:ss``。

    **日期与时刻必须同时存在**才算抽到 —— 只知道「周三」不知道几点，
    猜一个时间比留空更危险（用户会以为平台已经安排好了）。

    只找到时刻没找到日期 → 返回 ``None``。
    只找到日期没找到时刻 → 默认 09:00:00（工作日上午的常识默认值）。
    """
    if not text:
        return None
    anchor = today or date.today()
    day = _resolve_date(text, anchor)
    moment = _resolve_time(text)
    if day is None:
        return None
    if moment is None:
        moment = (9, 0, 0)
    hour, minute, second = moment
    return f"{day.isoformat()} {hour:02d}:{minute:02d}:{second:02d}"


def extract_draft(
    text: str,
    elders: list[Any] | None = None,
    today: date | None = None,
) -> DraftExtraction:
    """从问题里抽取下单草稿片段。

    ``elders`` 是当前家属绑定的老人列表（``{"id":..., "name":...}``）。
    **只有恰好一位老人时才自动填 elderId** —— 多位时由前端让用户选，
    猜错人比不填更糟。
    """
    extraction = DraftExtraction(
        hospital=extract_hospital(text),
        department=extract_department(text),
        visit_time=extract_visit_time(text, today=today),
    )
    if elders and len(elders) == 1:
        elder = elders[0]
        elder_id = getattr(elder, "id", None)
        if elder_id is None and isinstance(elder, dict):
            elder_id = elder.get("id")
        elder_name = getattr(elder, "name", None)
        if elder_name is None and isinstance(elder, dict):
            elder_name = elder.get("name")
        extraction.elder_id = elder_id
        extraction.elder_name = elder_name
    return extraction


__all__ = [
    "DEPARTMENT_KEYWORDS",
    "DraftExtraction",
    "extract_department",
    "extract_draft",
    "extract_hospital",
    "extract_visit_time",
]
