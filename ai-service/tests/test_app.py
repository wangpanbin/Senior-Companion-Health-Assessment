"""内部 HTTP 接口测试（V3.7）。

用 ``TestClient`` 起真实的 ASGI 应用，走完整的 lifespan（启动期 fail-fast
加载词表与 FAQ），打真实的请求。

**不启模型、不连数据库** —— Python 侧本来就不需要它们。

## ⚠️ 这一组强制「降级模式」的原因（V3.7 联调实测）

本组断言的是**不接大模型时**的确定性行为。但 lifespan 会照着配置装 provider：

- 本机若在 ``.env`` 里配了 ``LLM_*``，provider 就是真的 → ``degraded`` 变 ``False``；
- CI 上没配 → provider 是 ``NullProvider`` → ``degraded`` 是 ``True``。

同一份断言在两种环境下结论相反，说明**断言写错了而不是实现错了**。
所以夹具显式换回 ``NullProvider``：这一组与「本机是否配了模型」彻底解耦，
LLM 路径由 ``test_llm_smoke.py`` 负责（那边需要网络）。
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.knowledge import load_faq
from app.llm import NullProvider
from app.main import app
from app.paths import resolve_faq_path
from app.service import AssistantService


@pytest.fixture()
def client():
    """起一个真实的应用实例（lifespan 会跑，词表与 FAQ 都会被校验加载）。

    lifespan 之后**显式换回降级 provider** —— 见模块 docstring。
    """
    with TestClient(app) as test_client:
        app.state.service = AssistantService(
            load_faq(resolve_faq_path()), provider=NullProvider()
        )
        yield test_client


def test_healthz(client: TestClient) -> None:
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_chat_returns_envelope(client: TestClient) -> None:
    """问一句平台规则 → 拿到完整响应体。"""
    response = client.post(
        "/internal/assistant/chat",
        json={"question": "陪诊费多少钱", "history": []},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["answer"]
    assert body["intent"] == "FAQ_PLATFORM"
    assert body["degraded"] is True
    assert body["sources"]


def test_medical_question_is_refused_without_llm(client: TestClient) -> None:
    """医疗问题 → 固定拒答，**不经 LLM**。"""
    response = client.post(
        "/internal/assistant/chat",
        json={"question": "这个药一天吃几次"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["intent"] == "REFUSE_MEDICAL"
    assert "医疗" in body["answer"]


def test_abusive_question_returns_400(client: TestClient) -> None:
    """辱骂 / 引流 → 400 + ILLEGAL_QUESTION（Java 门面转 10003）。"""
    response = client.post(
        "/internal/assistant/chat",
        json={"question": "你是骗子，加我微信私下转账"},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "ILLEGAL_QUESTION"


def test_recommend_with_candidates_keeps_three_segments(client: TestClient) -> None:
    """候选陪诊员的**三段式结构**不许被合并（ADR-0016 决策 3）。

    ## 测试数据刻意让三段**都有内容**

    ``mismatches`` 传的是**空数组**时，「未命中说明」这一段本来就没有内容 ——
    降级路径省略它（与前端 ``order-detail.vue`` 的 ``v-if="c.mismatches?.length"``
    同一口径），LLM 路径显示成「无」。两种表现都不是缺陷，只是「空段怎么处理」的两种说法。

    但**空段测不出「三段不许合并」**。所以这里的候选三段都填了内容，
    断言才真的守得住「命中理由 / 未命中说明 / 降级说明 各自成段、
    不许把降级说成未命中」这条红线。

    ## 断言为什么写成路径无关

    原文「未填写坐标，距离维度记 0」是**降级路径**的逐字输出；
    接了模型之后，prompt 约束 5 **明确要求**把它表达成
    「距离维度无法计算（陪诊员尚未填写坐标）」——
    模型照做是对的，反而是「必须逐字出现原文」这条断言写死了降级路径。

    真正要守的是：

    1. 三段结构都在，且**不合并**；
    2. 降级说明必须**可辨认**（原文或合规改写二选一）；
    3. 一句「距离近 / 离得近」都不许出现 —— 这才是这条断言的立身之本。
    """
    response = client.post(
        "/internal/assistant/chat",
        json={
            "question": "推荐个陪诊员",
            "order": {"id": 1001, "status": "PENDING", "statusLabel": "待接单"},
            "candidates": [
                {
                    "companionId": 301,
                    "companionName": "李*军",
                    "totalScore": 64,
                    "regionScore": 100,
                    "timeScore": 100,
                    "distanceScore": 0,
                    "skillScore": 0,
                    "ratingScore": 96,
                    "reasons": ["同服务区域", "就诊时间在可服务时段内"],
                    "mismatches": ["未服务过该科室"],
                    "notes": ["未填写坐标，距离维度记 0"],
                }
            ],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["intent"] == "RECOMMEND_COMPANION"
    # ① 三段结构都在，且不合并（降级说明不许被塞进未命中说明）
    assert "命中理由" in body["answer"]
    assert "未命中说明" in body["answer"]
    assert "降级说明" in body["answer"]
    # ② 降级说明可辨认：原文（降级路径）或合规改写（LLM 路径）二选一
    assert (
        "未填写坐标，距离维度记 0" in body["answer"]
        or "距离维度无法计算" in body["answer"]
    )
    # ③ 合规禁令：一句都不许出现（ADR-0016 决策 3）
    for banned in ("距离近", "离得近", "就在附近", "离您很近", "距离较近"):
        assert banned not in body["answer"]


def test_openapi_is_disabled(client: TestClient) -> None:
    """OpenAPI 文档不对外暴露（内部服务）。"""
    assert client.get("/openapi.json").status_code == 404
    assert client.get("/docs").status_code == 404
