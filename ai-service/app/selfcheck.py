"""演示前自检：合规资产、FAQ、LLM 配置三件套一键体检。

## 为什么要有这个模块

演示现场的故障都「安静」：词表路径指错（护栏静默失效）、FAQ 被改坏（回答漂移）、
模型连不通（回答全变成标准答案原文）。这些在 ``/healthz`` 上都是 ``UP`` ——
健康检查只回答「进程活着」，不回答「演示要用的东西都在不在」。

::

    python -m app.selfcheck          # 合规资产 + FAQ（不联网，秒回）
    python -m app.selfcheck --llm    # 追加大模型连通性实测（消耗一次调用）

退出码：资产检查失败 → ``1``（护栏没把握就别开演示）；
LLM 连不通 → 仍是 ``0``（降级模式本来就是正常模式，只打 WARN 提示）。

## 为什么不 import ``app.guard``

guard 在 import 时就加载三份词表、失败直接抛错 —— 那是**运行时**想要的
fail-fast，但自检要的是**可读的错误报告**而不是一屏 traceback。
所以这里直接用 :mod:`app.paths` 的加载函数，逐项 try、逐项报告。
"""

from __future__ import annotations

import argparse
import sys
import time

from .config import get_settings
from .knowledge import load_faq
from .paths import (
    ComplianceWordsError,
    load_compliance_words,
    load_refusal_template,
    load_sensitive_words,
    resolve_faq_path,
)

#: FAQ 条目数下限（与 tests/test_knowledge.py 同口径）。
MIN_FAQ_ENTRIES = 25


def _report(ok: bool, label: str, detail: str) -> None:
    mark = "✅" if ok else "❌"
    print(f"{mark} {label}：{detail}")


def check_compliance_assets() -> bool:
    """三份共享合规资产逐项加载。失败返回 ``False``。"""
    ok = True
    checks = (
        ("医疗建议禁用词表", load_compliance_words),
        ("辱骂 / 违规引流词表", load_sensitive_words),
    )
    for label, loader in checks:
        try:
            words = loader()
            _report(True, label, f"{len(words)} 词")
        except ComplianceWordsError as exc:
            _report(False, label, str(exc))
            ok = False
    try:
        template = load_refusal_template()
        _report(True, "固定拒答模板", f"{len(template)} 字符")
    except ComplianceWordsError as exc:
        _report(False, "固定拒答模板", str(exc))
        ok = False
    return ok


def check_faq() -> bool:
    """FAQ 知识库加载与最小规模。失败返回 ``False``。"""
    try:
        index = load_faq(resolve_faq_path())
    except Exception as exc:  # noqa: BLE001 - 自检要把任何加载失败变成可读报告
        _report(False, "FAQ 知识库", str(exc))
        return False
    missing_anchor = [e.id for e in index.entries if not (e.source and e.anchor)]
    detail = f"{len(index.entries)} 条"
    if missing_anchor:
        detail += f"；⚠️ {len(missing_anchor)} 条缺 source/anchor：{missing_anchor}"
    _report(len(index.entries) >= MIN_FAQ_ENTRIES, "FAQ 知识库", detail)
    return len(index.entries) >= MIN_FAQ_ENTRIES


def check_llm(live: bool) -> None:
    """LLM 配置状态（以及可选的连通性实测）。**不影响退出码**。"""
    settings = get_settings()
    if not settings.llm_enabled:
        print("ℹ️ 大模型：未配置 —— 基础模式（回答直接给标准答案原文），这是正常模式")
        return
    print(f"ℹ️ 大模型：已配置（{settings.llm_model} @ {settings.llm_base_url}）")
    if not live:
        print("ℹ️ 未实测连通性；要实测请加 --llm（会消耗一次模型调用）")
        return
    from .llm import build_provider

    provider = build_provider(settings)
    started = time.monotonic()
    try:
        text = provider.narrate(
            {"facts_text": "（自检：平台只提供陪诊服务记录，不做医疗判断。）", "question": "请回复「自检通过」"}
        )
        elapsed = time.monotonic() - started
        if text:
            print(f"✅ 大模型连通：{elapsed:.1f}s 内返回")
        else:
            print(f"⚠️ 大模型调用失败（{elapsed:.1f}s）—— 演示会走降级路径（标准答案原文），功能不受影响")
    except Exception as exc:  # noqa: BLE001 - 连不通也要把原因讲清楚
        print(f"⚠️ 大模型调用失败：{exc} —— 演示会走降级路径（标准答案原文），功能不受影响")


def main() -> int:
    parser = argparse.ArgumentParser(description="ai-service 演示前自检")
    parser.add_argument("--llm", action="store_true", help="实测大模型连通性（消耗一次调用）")
    args = parser.parse_args()

    print("── ai-service 演示前自检 ──")
    assets_ok = check_compliance_assets()
    faq_ok = check_faq()
    check_llm(args.llm)

    if not (assets_ok and faq_ok):
        print("── 结论：自检未通过，护栏或知识库没把握，不要开始演示 ──")
        return 1
    print("── 结论：合规资产与 FAQ 就绪，可以开始演示 ──")
    return 0


if __name__ == "__main__":
    sys.exit(main())
