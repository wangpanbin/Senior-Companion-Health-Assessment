"""配置（环境变量 → pydantic-settings）。

## 为什么所有大模型配置都**没有代码默认值**

`LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` 留空即表示「降级模式」——
服务照常启动、照常可用，只是回答直接给 FAQ 原文。

这不是偷懒，而是本仓库的一条既有纪律：`application.yml` 对
`SPRING_PROFILES_ACTIVE` 刻意不给默认值，理由是「留 dev 兜底会让漏配的生产
直接以开发配置启动」。同理，**写死的默认值就是「配置错的默认值在生产静默生效」**。
所以模型名由部署环境决定，代码里一个默认值都不给。
"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """环境变量映射。字段名小写下划线 → 环境变量大写下划线（pydantic-settings 默认行为）。"""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ── 大模型（全部可留空 = 降级模式）────────────────────────────────
    llm_base_url: str = ""
    """OpenAI 兼容端点。留空则不启用 LLM。"""

    llm_api_key: str = ""
    """API Key。Ollama 场景填任意占位串即可。"""

    llm_model: str = ""
    """模型名。留空时即使配了 base_url 也走降级（避免半套配置静默空转）。"""

    llm_timeout_seconds: int = 30
    """单次调用超时（秒）。"""

    # ── 共享资源路径 ──────────────────────────────────────────────────
    nianglin_compliance_words_path: str = ""
    """医疗建议禁用词表路径。留空用仓库默认位置；生产由部署时挂载同一份文件。"""

    nianglin_sensitive_words_path: str = ""
    """辱骂 / 违规引流词表路径。留空用仓库默认位置。"""

    nianglin_refusal_template_path: str = ""
    """固定拒答模板路径。留空用仓库默认位置。"""

    nianglin_faq_path: str = ""
    """FAQ 路径。留空用 knowledge/faq.json。"""

    @property
    def llm_enabled(self) -> bool:
        """是否启用 LLM。

        注意是 **base_url 与 model 都在** 才算启用 —— 只配一半会静默空转，
        那比明确降级更难排查。
        """
        return bool(self.llm_base_url.strip() and self.llm_model.strip())


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """进程内单例。测试可通过 ``get_settings.cache_clear()`` 重置。"""
    return Settings()
