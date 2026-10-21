"""pytest 夹具。

## 核心测试**不依赖 LLM**

默认夹具用的是 :class:`~app.llm.NullProvider`（降级路径），
所以「意图分类 / 检索 / 护栏 / 降级路径」这些断言**不需要网络、不需要 key**。
接了模型的冒烟测试在 ``test_llm_smoke.py`` 里，用 ``skipif`` 自动跳过。
"""

from __future__ import annotations

import pytest

from app import knowledge
from app.paths import resolve_faq_path
from app.service import AssistantService


@pytest.fixture(scope="session")
def faq_index() -> knowledge.FaqIndex:
    """FAQ 索引（整个测试会话复用）。"""
    return knowledge.load_faq(resolve_faq_path())


@pytest.fixture()
def service(faq_index: knowledge.FaqIndex) -> AssistantService:
    """**降级模式**的助手（无 LLM）。这是核心测试用的那个。"""
    return AssistantService(faq_index)


@pytest.fixture()
def medical_words() -> tuple[str, ...]:
    """共享合规词表。"""
    from app import guard

    return guard.medical_advice_words()
