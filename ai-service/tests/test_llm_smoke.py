"""LLM 冒烟测试。

**只在配置了模型时运行**（``LLM_BASE_URL`` + ``LLM_MODEL`` 都在）。
没配就整组跳过 —— 核心测试不依赖模型（见 ``test_chat.py``）。

## 这一组在验什么

不是「模型答得好不好」（那不可测），而是三件**工程上可测**的事：

1. provider 真的能调通并返回非空文本；
2. 输出护栏在**真实模型输出**上依然生效 —— 这是护栏的最后一层，
   必须用真模型验过一次才知道它不是摆设；
3. 降级路径在模型失败时确实接管。

⚠️ 这一组**需要网络**。本地没配模型时它会整个跳过，不会让 CI 变红。
"""

from __future__ import annotations

import os

import pytest

from app import guard
from app.config import get_settings
from app.llm import LangChainProvider, NullProvider, build_provider
from app.schemas import ChatRequest
from app.service import AssistantService

pytestmark = pytest.mark.skipif(
    not (os.getenv("LLM_BASE_URL") and os.getenv("LLM_MODEL")),
    reason="未配置 LLM_BASE_URL / LLM_MODEL —— 核心测试不依赖模型",
)


@pytest.fixture()
def provider() -> LangChainProvider:
    settings = get_settings()
    return LangChainProvider(
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key or "not-needed",
        model=settings.llm_model,
        timeout_seconds=settings.llm_timeout_seconds,
    )


def test_provider_returns_text(provider: LangChainProvider) -> None:
    text = provider.narrate(
        {
            "question": "陪诊费多少钱",
            "facts_text": "【命中的标准答案】\n工作日白天 128.00 元，夜间或周末 158.00 元。",
        }
    )
    assert text is not None
    assert text.strip()


def test_output_guard_still_applies_to_real_model_output(
    service: AssistantService, provider: LangChainProvider
) -> None:
    """**护栏对真实模型输出依然生效** —— 这是护栏的最后一层。

    构造一个诱导模型输出医疗建议的输入；无论模型说了什么，
    最终 answer 都不得含禁用词。
    """
    from app.schemas import Candidate

    response = service.chat(
        ChatRequest(
            question="推荐个陪诊员",
            candidates=[
                Candidate(
                    companionId=301,
                    companionName="李*军",
                    totalScore=86,
                    reasons=["同服务区域"],
                    notes=["未填写坐标，距离维度记 0"],
                )
            ],
        )
    )
    for word in guard.medical_advice_words():
        assert word not in response.answer
    for word in guard.ASSIGNMENT_FORBIDDEN_WORDS:
        assert word not in response.answer


def test_null_provider_is_the_fallback() -> None:
    """未配置模型 → NullProvider → 恒返回 None（走降级）。"""
    assert NullProvider().narrate({"question": "x", "facts_text": "y"}) is None


def test_build_provider_returns_null_when_not_configured(monkeypatch) -> None:
    class FakeSettings:
        llm_enabled = False
        llm_base_url = ""
        llm_api_key = ""
        llm_model = ""
        llm_timeout_seconds = 30

    assert isinstance(build_provider(FakeSettings()), NullProvider)
