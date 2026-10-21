"""路径解析与**共享合规资产**的加载。

## 为什么这里的失败必须是「启动失败」

合规资产是「不做诊断、不开药方」这条红线在代码层的唯一落点。
读空 = 护栏静默失效 = 所有医疗回答放行 = 平台变成医疗建议发布渠道。

这种故障**不会报错**，只会让一切「看起来正常」。所以本模块在加载阶段就失败：
文件缺失、词数异常、关键词缺失，一律抛 :class:`ComplianceWordsError`，
由服务启动流程捕获 → 进程不启动。

这与 Java 侧 ``ComplianceCheckUtil`` / ``SensitiveWordUtil`` 的行为一致，
也与 ``MatchingProperties#validate()``「错误的配置要在启动的十秒内暴露」同源。

## 三份共享资产

| 资产 | 仓库位置 | Java 侧 | Python 侧 |
|---|---|---|---|
| 医疗建议禁用词 | ``backend/src/main/resources/compliance/medical-advice-words.txt`` | ``ComplianceCheckUtil`` | 本模块 |
| 辱骂 / 违规引流词 | ``backend/src/main/resources/compliance/sensitive-words.txt`` | ``SensitiveWordUtil`` | 本模块 |
| 固定拒答模板 | ``backend/src/main/resources/compliance/refusal-template.txt`` | ``ComplianceCheckUtil#refusalTemplate()`` | 本模块 |
"""

from __future__ import annotations

from pathlib import Path

from .config import get_settings

# ai-service/app/paths.py → ai-service/
BASE_DIR = Path(__file__).resolve().parent.parent
# ai-service/ → 仓库根
REPO_ROOT = BASE_DIR.parent

DEFAULT_COMPLIANCE_WORDS_RELPATH = (
    "backend/src/main/resources/compliance/medical-advice-words.txt"
)
DEFAULT_SENSITIVE_WORDS_RELPATH = (
    "backend/src/main/resources/compliance/sensitive-words.txt"
)
DEFAULT_REFUSAL_TEMPLATE_RELPATH = (
    "backend/src/main/resources/compliance/refusal-template.txt"
)
DEFAULT_FAQ_RELPATH = "knowledge/faq.json"

#: 词表至少要有这么多词才算「读到了」，低于此值一律视为读空。
MIN_COMPLIANCE_WORDS = 10
MIN_SENSITIVE_WORDS = 8

#: 必须存在的关键词。少一个就说明读到的不是那份词表（读错路径 / 被裁剪）。
#: 与 Java 侧 ``ComplianceCheckUtil`` / ``SensitiveWordUtil`` 的校验口径**必须一致** ——
#: 两边口径不同就会出现「Java 放行、Python 拒启动」的分叉。
REQUIRED_COMPLIANCE_WORDS = frozenset({"建议服用", "剂量"})
REQUIRED_SENSITIVE_WORDS = frozenset({"加微信", "扫码付款"})


class ComplianceWordsError(RuntimeError):
    """合规资产加载失败 —— 护栏不可信，服务必须拒绝启动。"""


def _configured_path(env_field: str, default_relpath: str) -> Path:
    """解析一个「环境变量可配、否则用仓库默认位置」的路径。

    ⚠️ 走 :class:`~app.config.Settings` 而不是直接读 ``os.environ``：
    pydantic-settings 会**同时**读环境变量与 ``.env`` 文件，而 ``.env`` **不会**
    注入 ``os.environ``。直接读 ``os.environ`` 会让「写在 .env 里的路径」静默失效 ——
    文档教用户写 .env，代码却看不见，症状是「配了没反应」。
    """
    configured = (getattr(get_settings(), env_field, "") or "").strip()
    if configured:
        return Path(configured).expanduser().resolve()
    if env_field == "nianglin_compliance_words_path":
        return (REPO_ROOT / DEFAULT_COMPLIANCE_WORDS_RELPATH).resolve()
    if env_field == "nianglin_sensitive_words_path":
        return (REPO_ROOT / DEFAULT_SENSITIVE_WORDS_RELPATH).resolve()
    if env_field == "nianglin_refusal_template_path":
        return (REPO_ROOT / DEFAULT_REFUSAL_TEMPLATE_RELPATH).resolve()
    return (REPO_ROOT / default_relpath).resolve()


def resolve_compliance_words_path() -> Path:
    """解析医疗建议禁用词表路径（``NIANGLIN_COMPLIANCE_WORDS_PATH`` 可配）。"""
    return _configured_path("nianglin_compliance_words_path", DEFAULT_COMPLIANCE_WORDS_RELPATH)


def resolve_sensitive_words_path() -> Path:
    """解析辱骂 / 违规引流词表路径（``NIANGLIN_SENSITIVE_WORDS_PATH`` 可配）。"""
    return _configured_path("nianglin_sensitive_words_path", DEFAULT_SENSITIVE_WORDS_RELPATH)


def resolve_refusal_template_path() -> Path:
    """解析固定拒答模板路径（``NIANGLIN_REFUSAL_TEMPLATE_PATH`` 可配）。"""
    return _configured_path("nianglin_refusal_template_path", DEFAULT_REFUSAL_TEMPLATE_RELPATH)


def resolve_faq_path() -> Path:
    """解析 FAQ 路径（``NIANGLIN_FAQ_PATH`` 可配）。"""
    configured = (get_settings().nianglin_faq_path or "").strip()
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


def _read_text(path: Path, label: str) -> str:
    if not path.is_file():
        raise ComplianceWordsError(
            f"{label}文件不存在：{path} —— 护栏会静默失效，服务拒绝启动"
        )
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:  # pragma: no cover - 极端 IO 故障
        raise ComplianceWordsError(f"{label}读取失败：{path}（{exc}）") from exc


def _load_words(path: Path, label: str, minimum: int, required: frozenset[str]) -> list[str]:
    words = parse_word_lines(_read_text(path, label))
    if len(words) < minimum:
        raise ComplianceWordsError(
            f"{label}词数异常：{len(words)} < {minimum}（来源 {path}）"
            " —— 护栏会静默失效，服务拒绝启动"
        )
    missing = required - set(words)
    if missing:
        raise ComplianceWordsError(
            f"{label}缺少关键词：{sorted(missing)}（来源 {path}）"
            " —— 可能读错了文件，服务拒绝启动"
        )
    return words


def load_compliance_words(path: Path | None = None) -> list[str]:
    """加载并校验医疗建议禁用词表。

    :raises ComplianceWordsError: 文件缺失 / 读不出 / 词数不足 / 关键词缺失。
    """
    target = path or resolve_compliance_words_path()
    return _load_words(
        target, "合规词表", MIN_COMPLIANCE_WORDS, REQUIRED_COMPLIANCE_WORDS
    )


def load_sensitive_words(path: Path | None = None) -> list[str]:
    """加载并校验辱骂 / 违规引流词表。

    :raises ComplianceWordsError: 文件缺失 / 读不出 / 词数不足 / 关键词缺失。
    """
    target = path or resolve_sensitive_words_path()
    return _load_words(
        target, "敏感词表", MIN_SENSITIVE_WORDS, REQUIRED_SENSITIVE_WORDS
    )


def load_refusal_template(path: Path | None = None) -> str:
    """加载固定拒答模板。

    ⚠️ 本模板本身**不得含任何禁用词** —— 否则输出护栏会把拒答模板自己拦下，
    用户看到的是一段莫名其妙的提示。Java 侧 ``ComplianceCheckUtilTest`` 与
    Python 侧 ``test_guard.py`` 各有一条断言专门守这个。
    """
    target = path or resolve_refusal_template_path()
    text = _read_text(target, "拒答模板")
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        raise ComplianceWordsError(f"拒答模板为空：{target}")
    return " ".join(lines)
