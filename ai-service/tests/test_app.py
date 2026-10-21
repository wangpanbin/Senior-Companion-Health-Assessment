"""内部 HTTP 接口测试（V3.7）。

用 ``TestClient`` 起真实的 ASGI 应用，走完整的 lifespan（启动期 fail-fast
加载词表与 FAQ），打真实的请求。

**不启模型、不连数据库** —— Python 侧本来就不需要它们。
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture()
def client():
    """起一个真实的应用实例（lifespan 会跑，词表与 FAQ 都会被校验加载）。"""
    with TestClient(app) as test_client:
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
    """候选陪诊员的三段式结构原样出现在事实里。"""
    response = client.post(
        "/internal/assistant/chat",
        json={
            "question": "推荐个陪诊员",
            "order": {"id": 1001, "status": "PENDING", "statusLabel": "待接单"},
            "candidates": [
                {
                    "companionId": 301,
                    "companionName": "李*军",
                    "totalScore": 86,
                    "regionScore": 100,
                    "timeScore": 100,
                    "distanceScore": 0,
                    "skillScore": 60,
                    "ratingScore": 96,
                    "reasons": ["同服务区域"],
                    "mismatches": [],
                    "notes": ["未填写坐标，距离维度记 0"],
                }
            ],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["intent"] == "RECOMMEND_COMPANION"
    # 降级路径也要把「未填写坐标」原样带出来
    assert "未填写坐标，距离维度记 0" in body["answer"]
    assert "距离近" not in body["answer"]
    assert "离得近" not in body["answer"]


def test_openapi_is_disabled(client: TestClient) -> None:
    """OpenAPI 文档不对外暴露（内部服务）。"""
    assert client.get("/openapi.json").status_code == 404
    assert client.get("/docs").status_code == 404
