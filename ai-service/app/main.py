"""FastAPI 入口（V3.7 · M14）。

## 启动期必须 fail-fast

服务启动时**先**加载共享合规词表与 FAQ，两处任一失败都让进程**起不来**：

- 词表读空 → 护栏静默失效 → 所有医疗回答放行（最危险的一类故障）；
- FAQ 读不到 → 降级路径没有内容可给 → 助手等于没有「基础模式」。

这与 Java 侧 ``ComplianceCheckUtil`` 的行为一致，也与 ``MatchingProperties#validate``
「错误的配置要在启动的十秒内暴露」同源。

## 暴露面

本服务**只监听 127.0.0.1**（启动命令里指定），不直接对浏览器开放。
对外契约是 Java 的 ``/api/assistant/chat``；这里的 ``/internal/**`` 是内部通道。

OpenAPI 文档**关闭** —— 它不对外暴露，也没必要。
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from . import knowledge
from .config import get_settings
from .guard import IllegalQuestion, medical_advice_words
from .llm import LangChainProvider, build_provider
from .paths import resolve_faq_path
from .schemas import ChatRequest, ChatResponse
from .service import AssistantService

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [ai-service] %(message)s",
)
logger = logging.getLogger(__name__)

#: 内部错误码（与 Java 门面约定）。
ERR_ILLEGAL_QUESTION = "ILLEGAL_QUESTION"
ERR_INTERNAL = "INTERNAL_ERROR"


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """启动期装配。**任何一步失败都让进程起不来**，不做「降级启动」。"""
    # ① 共享合规词表 —— 读不到 / 读空 / 读错文件 → 抛错 → 启动失败
    words = medical_advice_words()
    logger.info("合规词表已加载：%d 词", len(words))

    # ② FAQ —— 读不到 → 降级路径就没有内容 → 启动失败
    faq_index = knowledge.load_faq(resolve_faq_path())
    logger.info("FAQ 已加载：%d 条", len(faq_index.entries))

    # ③ LLM provider —— 未配置不是错误，走 NullProvider（基础模式）
    settings = get_settings()
    provider = build_provider(settings)
    # ⚠️ 这里报告的是**实际生效的 provider**，不是配置项。
    #    旧写法只看 settings.llm_enabled，于是「配了但构造失败」时会先打一条
    #    WARN 说已回退基础模式、再打一条 INFO 说已启用 —— 两句自相矛盾，
    #    读日志的人只会以为模型确实在跑。
    if isinstance(provider, LangChainProvider):
        llm_desc = "已启用（模型={}）".format(settings.llm_model)
    elif settings.llm_enabled:
        llm_desc = "已配置但不可用，已回退基础模式（回答直接给标准答案原文）"
    else:
        llm_desc = "未配置（基础模式，回答直接给标准答案原文）"
    logger.info("大模型：%s", llm_desc)

    app.state.service = AssistantService(faq_index, provider)
    yield
    logger.info("ai-service 已停止")


app = FastAPI(
    title="银龄伴诊 · 家属智能助手",
    version="1.0.0",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


@app.get("/healthz")
def healthz() -> dict[str, str]:
    """存活探针。不返回任何配置细节。"""
    return {"status": "UP", "service": "nianglin-ai-assistant"}


@app.post("/internal/assistant/chat", response_model=ChatResponse)
def chat(request: ChatRequest, http_request: Request) -> ChatResponse | JSONResponse:
    """内部问答通道。

    错误语义：
    - **400 + ``ILLEGAL_QUESTION``** → 问题命中辱骂 / 违规引流词，Java 门面转 ``10003``；
    - 其它异常 → **500**，Java 门面走兜底（FAQ 原文）而不是把错误透给家属。
    """
    service: AssistantService = http_request.app.state.service
    try:
        return service.chat(request)
    except IllegalQuestion as exc:
        return JSONResponse(
            status_code=400,
            content={"error": {"code": ERR_ILLEGAL_QUESTION, "message": str(exc)}},
        )
    except Exception:  # noqa: BLE001 - 内部错误不外泄细节
        logger.exception("助手处理失败")
        return JSONResponse(
            status_code=500,
            content={"error": {"code": ERR_INTERNAL, "message": "助手内部错误"}},
        )


__all__ = ["app"]
