"""LLM provider 抽象与降级。

## 三条设计纪律

1. **LangChain 的导入是惰性的** —— 只在真的要调模型时才 import。
   这样核心测试（分类 / 检索 / 护栏 / 降级路径）完全不依赖 LangChain，
   CI 上没装它也能跑通绝大部分断言。
2. **未配置模型不是错误状态** —— 是正常模式。服务照常启动、照常可用，
   只是回答直接给 FAQ 原文（``degraded=true``）。
   理由见 ADR-0020 决策 4：降级路径与主路径同构，不是补丁。
3. **调用失败一律降级，不抛异常** —— 模型超时、连接失败、返回空，
   对家属来说都只是「回答朴素一点」，不该变成一个错误页。
"""

from __future__ import annotations

import logging
from typing import Any, Protocol, runtime_checkable

from .prompts import render_facts, render_user

logger = logging.getLogger(__name__)


class LlmUnavailableError(RuntimeError):
    """LangChain 未安装或 provider 构造失败。"""


@runtime_checkable
class LlmProvider(Protocol):
    """LLM 抽象。``narrate`` 返回 ``None`` 表示「这次没叙述成，走降级」。"""

    def narrate(self, facts: dict[str, Any]) -> str | None: ...


class NullProvider:
    """降级 provider：恒返回 ``None``。

    未配置 ``LLM_BASE_URL`` / ``LLM_MODEL`` 时使用它 —— 这是**正常模式**。
    """

    def narrate(self, facts: dict[str, Any]) -> str | None:
        return None


class LangChainProvider:
    """OpenAI 兼容协议的 provider（云端 API / 本地 Ollama / vLLM 共用一套）。

    只用两件 LangChain 的东西：``ChatOpenAI`` 与 ``PromptTemplate``。
    **不使用** AgentExecutor / create_react_agent —— 决策链是代码里写死的。
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        timeout_seconds: int = 30,
    ) -> None:
        try:
            # ⚠️ 模板类从 langchain_core 导入，**不是** langchain.prompts ——
            #    LangChain 1.x 已移除 langchain.prompts 模块（模板类搬到 langchain_core.prompts），
            #    写旧路径在 1.x 上必然 ImportError，会被下面兜成 NullProvider，
            #    症状是「模型配得好好的，回答却永远是 FAQ 原文」——
            #    而且只在启动日志里留一条 WARN，很容易被忽略。
            #    langchain_core.prompts 在 0.x 与 1.x 上都可用，是唯一稳定路径。
            from langchain_core.prompts import PromptTemplate
            from langchain_openai import ChatOpenAI
        except ImportError as exc:  # pragma: no cover - 取决于安装环境
            raise LlmUnavailableError(
                "未安装 langchain-core / langchain-openai，无法启用大模型（不影响降级模式）"
            ) from exc

        self._model = ChatOpenAI(
            base_url=base_url,
            api_key=api_key,
            model=model,
            timeout=timeout_seconds,
            max_retries=0,
        )
        self._system_template = PromptTemplate.from_template(
            "{system}\n\n{user}"
        )
        self._model_name = model

    def narrate(self, facts: dict[str, Any]) -> str | None:
        """把结构化事实讲成人话。失败返回 ``None``（调用方走降级）。"""
        try:
            rendered = self._system_template.format(
                system=render_facts(facts.get("facts_text", "")),
                user=render_user(facts.get("question", "")),
            )
            result = self._model.invoke(rendered)
            text = getattr(result, "content", None)
            if not isinstance(text, str) or not text.strip():
                logger.warning("大模型返回空内容，走降级路径")
                return None
            return text.strip()
        except Exception as exc:  # noqa: BLE001 - 任何异常都必须降级而非冒泡
            logger.warning("大模型调用失败，走降级路径：%s", exc)
            return None


def build_provider(settings: Any) -> LlmProvider:
    """按配置构造 provider。

    未配置 ``LLM_BASE_URL`` 或 ``LLM_MODEL`` → :class:`NullProvider`。
    构造失败（LangChain 没装等）→ 也返回 :class:`NullProvider`，
    **不阻止服务启动**。
    """
    if not getattr(settings, "llm_enabled", False):
        logger.info("未配置大模型，助手以基础模式运行（回答直接给标准答案原文）")
        return NullProvider()
    try:
        return LangChainProvider(
            base_url=settings.llm_base_url,
            api_key=settings.llm_api_key or "not-needed",
            model=settings.llm_model,
            timeout_seconds=settings.llm_timeout_seconds,
        )
    except LlmUnavailableError as exc:
        logger.warning("%s —— 助手以基础模式运行", exc)
        return NullProvider()


__all__ = [
    "LangChainProvider",
    "LlmProvider",
    "LlmUnavailableError",
    "NullProvider",
    "build_provider",
]
