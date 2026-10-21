"""LangChain provider 的**离线**回归测试。

## 为什么单独一组

这一组**不需要网络、不需要 API Key、不需要模型**，只验一件事：

    「装了 LangChain 的环境里，provider 真的能被构造出来。」

## 为什么必须单独有这一组（V3.7 联调实测）

上一版只有 ``test_llm_smoke.py`` 碰 LangChain，而它是**整组 skipif**：
没配 ``LLM_BASE_URL`` / ``LLM_MODEL`` 就全部跳过。于是下面这个错误
**没有任何测试能发现**：

    from langchain.prompts import PromptTemplate

LangChain **1.x 已移除** ``langchain.prompts`` 模块（模板类搬到
``langchain_core.prompts``）。在 1.x 上这行必然 ``ImportError``，
被 :func:`~app.llm.build_provider` 兜成 ``NullProvider`` ——
服务照常启动、接口照常返回，只是回答永远是 FAQ 原文，
``degraded`` 恒为 ``true``。

症状极具迷惑性：**配置全对、key 有效、网络通**，只有回答朴素。
而 ``requirements.txt`` 写的是 ``langchain>=0.2``（不锁上界），
任何人今天重新装依赖都会拿到 1.x 并踩中。

所以这一组的断言是：**构造成功 ≠ 降级**。
"""

from __future__ import annotations

import pytest

from app.llm import LangChainProvider, NullProvider, build_provider

# 装了就跑，没装就跳过 —— 核心断言仍然不依赖 LangChain。
pytest.importorskip("langchain_core.prompts")
pytest.importorskip("langchain_openai")


class _ConfiguredSettings:
    """「配置齐全」的假设置对象。不含真实 key。"""

    llm_enabled = True
    llm_base_url = "http://127.0.0.1:1/v1"
    llm_api_key = "dummy-key-not-used"
    llm_model = "dummy-model"
    llm_timeout_seconds = 5


def test_langchain_provider_can_be_constructed() -> None:
    """构造 provider **不该**抛 ImportError。

    构造阶段不发任何 HTTP 请求（``ChatOpenAI`` 只校验配置），
    所以这条断言在离线环境下也成立。
    """
    provider = LangChainProvider(
        base_url="http://127.0.0.1:1/v1",
        api_key="dummy-key-not-used",
        model="dummy-model",
        timeout_seconds=5,
    )
    assert isinstance(provider, LangChainProvider)
    assert not isinstance(provider, NullProvider)


def test_build_provider_does_not_silently_degrade() -> None:
    """配置齐全时，``build_provider`` **必须**返回真 provider 而不是降级。

    这是上一版那个 bug 的直接回归点：导入路径写错时，
    ``build_provider`` 会安静地返回 ``NullProvider``，
    表现为「配好了模型却永远在降级」。
    """
    provider = build_provider(_ConfiguredSettings())
    assert isinstance(provider, LangChainProvider)
    assert not isinstance(provider, NullProvider)


def test_build_provider_still_degrades_when_not_configured() -> None:
    """反向断言：没配模型时仍然降级（不许被上面的修复带偏）。"""

    class _Unconfigured:
        llm_enabled = False
        llm_base_url = ""
        llm_api_key = ""
        llm_model = ""
        llm_timeout_seconds = 30

    assert isinstance(build_provider(_Unconfigured()), NullProvider)
