"""路径解析与**共享合规词表**的加载。

## 为什么这里的失败必须是「启动失败」

合规词表是「不做诊断、不开药方」这条红线在代码层的唯一落点。
词表读空 = 护栏静默失效 = 所有医疗回答放行 = 平台变成医疗建议发布渠道。

这种故障**不会报错**，只会让一切「看起来正常」。所以本模块在加载阶段就失败：
文件缺失、词数异常、关键词缺失，一律抛 :class:`ComplianceWordsError`，
由服务启动流程捕获 → 进程不启动。

这与 Java 侧 ``ComplianceCheckUtil`` 的行为一致（读不到 classpath 资源即启动失败），
也与 ``MatchingProperties#validate()``「错误的配置要在启动的十秒内暴露」同源。
"""

from __future__ import annotations

import os
from pathlib import Path

# ai-service/app/paths.py → ai-service/
BASE_DIR = Path(__file__).resolve().parent.parent
# ai-service/ → 仓库根
REPO_ROOT = BASE_DIR.parent

DEFAULT_COMPLIANCE_WORDS_RELPATH = (
    "backend/src/main/resources/compliance/medical-advice-words.txt"
)
DEFAULT_FAQ_RELPATH = "knowledge/faq.json"

#: 词表至少要有这么多词才算「读到了」，低于此值一律视为读空。
MIN_COMPLIANCE_WORDS = 10

#: 必须存在的关键词。少一个就说明读到的不是那份词表（读错路径 / 被裁剪）。
REQUIRED_COMPLIANCE_WORDS = frozenset({"建议服用", "剂量"})


class ComplianceWordsError(RuntimeError):
    """合规词表加载失败 —— 护栏不可信，服务必须拒绝启动。"""


def resolve_compliance_words_path() -> Path:
    """解析合规词表路径。

    环境变量 ``NIANGLIN_COMPLIANCE_WORDS_PATH`` 优先；留空则用仓库默认位置。
    生产部署时两个服务的代码目录布局未必与仓库一致，所以做成可配置。
    """
    configured = os.environ.get("NIANGLIN_COMPLIANCE_WORDS_PATH", "").strip()
    if configured:
        return Path(configured).expanduser().resolve()
    return (REPO_ROOT / DEFAULT_COMPLIANCE_WORDS_RELPATH).resolve()


def resolve_faq_path() -> Path:
    """解析 FAQ 路径。环境变量 ``NIANGLIN_FAQ_PATH`` 优先。"""
    configured = os.environ.get("NIANGLIN_FAQ_PATH", "").strip()
    if configured:
        return Path(configured).expanduser().resolve()
    return (BASE_DIR / DEFAULT_FAQ_RELPATH).resolve()


def parse_word_lines(text: str) -> list[str]:
    """解析词表文本：忽略空行与 ``#`` 注释行，其余按行取词。"""
    words: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        words.append(line)
    return words


def load_compliance_words(path: Path | None = None) -> list[str]:
    """加载并校验合规词表。

    :raises ComplianceWordsError: 文件缺失 / 读不出 / 词数不足 / 关键词缺失。
    """
    target = path or resolve_compliance_words_path()
    if not target.is_file():
        raise ComplianceWordsError(
            f"合规词表文件不存在：{target} —— 护栏会静默失效，服务拒绝启动"
        )
    try:
        text = target.read_text(encoding="utf-8")
    except OSError as exc:  # pragma: no cover - 极端 IO 故障
        raise ComplianceWordsError(f"合规词表读取失败：{target}（{exc}）") from exc

    words = parse_word_lines(text)
    if len(words) < MIN_COMPLIANCE_WORDS:
        raise ComplianceWordsError(
            f"合规词表词数异常：{len(words)} < {MIN_COMPLIANCE_WORDS}（来源 {target}）"
            " —— 护栏会静默失效，服务拒绝启动"
        )
    missing = REQUIRED_COMPLIANCE_WORDS - set(words)
    if missing:
        raise ComplianceWordsError(
            f"合规词表缺少关键词：{sorted(missing)}（来源 {target}）"
            " —— 可能读错了文件，服务拒绝启动"
        )
    return words
