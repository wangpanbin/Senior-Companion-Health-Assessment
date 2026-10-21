# -*- coding: utf-8 -*-
"""本地复跑 CI 的「密钥体检」五条规则（CI 的 secrets job 调的就是这一份）。

用法：python tools/check_secrets.py
用途：提交前自查，避免 CI 的 secrets job 变红。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# ① 疑似真实 JWT（eyJ 开头的 base64 头）
JWT = re.compile(r"eyJ[A-Za-z0-9_-]{20,}")
# ② 疑似明文口令赋值
# ⚠️ **大小写敏感**，与 CI 的 `grep -E` 一致 —— CI 的模式只含小写 `password|passwd|secret`，
# 所以 `RAW_PASSWORD = "..."` 这类大写写法**不会**被 CI 命中。
# 本工具刻意不加大写：它的职责是回答「CI 的 secrets job 会不会红」，
# 判得比 CI 严只会产生误报噪音。
PASSWORD = re.compile(r"(?:password|passwd|secret)\s*[=:]\s*[\"'][^\"']{6,}[\"']")
# ③ 疑似真实手机号
PHONE = re.compile(r"\b1[3-9][0-9]{9}\b")
# ④ 疑似真实身份证号
ID_CARD = re.compile(r"\b[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])[0-9]{2}[0-9]{3}[0-9Xx]\b")
# ⑤ 疑似模型服务 API Key 明文赋值（V3.7 引入 ai-service 后新增）
#
# 为什么单列一条而不是并进 ②：② 拦的是 `password|passwd|secret`，
# 而模型服务的 key 习惯叫 `api_key` / `LLM_API_KEY` / `access_key`。
# 并进 ② 会让 ② 的语义变糊（它现在专门指「口令」），而且 ② 的最小长度是 6，
# key 通常更长 —— 两条规则的阈值本来就不同。
#
# 覆盖三种形态：
#   a) 带引号的赋值      api_key = "……"
#   b) .env 风格无引号   API_KEY=sk-xxxxxxxx
#   c) 已知 key 前缀     无论出现在哪一行（sk- / ghp_ / AKIA / AIza / xoxb-）
#   漏掉 (b)(c) 的后果是「key 写在没引号的 .env 里就没人管」——
#   而 .env 恰恰是最容易被人直接写进仓库文件的形态。
#
# ⚠️ **大小写敏感**：只认小写 `api_key`/`apikey`/`api-key` 与大写 `API_KEY`
#    这两种实际写法；`apiKey` 这种驼峰刻意不认（Java 侧一律用驼峰，
#    而 Java 侧不通过环境变量名直接写 key）。
API_KEY = re.compile(
    r"(?:api[_-]?key|apikey|API_KEY|access[_-]?key|secret[_-]?key"
    r"|access[_-]?token|auth[_-]?token)\s*[=:]\s*"
    r"(?:[\"'][^\"']{8,}[\"']|(?:sk|ghp|gsk|xoxb)[-_][A-Za-z0-9_\-]{12,})"
    r"|\b(?:sk-[A-Za-z0-9\-_]{20,}|ghp_[A-Za-z0-9]{30,}|gsk_[A-Za-z0-9]{20,}"
    r"|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z\-_]{30,})\b"
)

# ② 的白名单（与 CI 一致）
PASSWORD_WHITELIST = re.compile(
    r"CHANGE_ME|your-|your_|占位|示例|dummy|example|\$\{|os\.environ|getenv|__tests__|/test/|GenSeedSecrets")
# ③ 的白名单
PHONE_WHITELIST = re.compile(
    r"1380000|138001|137001|138123|139001|139111|138999|139000|135000|xxxx")
# ④ 的白名单
ID_WHITELIST = re.compile(r"11010119|46010119|4601011948|46[0-9]{4}(?:19|20)[0-9]{2}")
# ⑤ 的白名单
#
# ⚠️ `not-needed` / `ollama` 是**占位**：`ai-service` 在没有真实 key 时
#    给 OpenAI 兼容客户端传的就是这两个串（Ollama 不校验 key）。
#    `Field(` / `alias=` 是 pydantic-settings 的字段声明形态。
API_KEY_WHITELIST = re.compile(
    r"CHANGE_ME|your-|your_|占位|示例|dummy|example|\$\{|os\.environ|getenv"
    r"|not-needed|ollama|Field\(|alias=|sk-xxx|xxxx"
)


def tracked_files():
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True)
    return [ROOT / line for line in out.stdout.splitlines() if line.strip()]


def main():
    files = tracked_files()
    failures = []

    def scan(label, pattern, whitelist=None):
        hits = []
        for path in files:
            try:
                text = path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            for lineno, line in enumerate(text.splitlines(), 1):
                if not pattern.search(line):
                    continue
                # ⚠️ CI 的白名单作用在 `git grep` 的**整行输出**上
                # （即 `路径:行号:内容`），不是只作用在内容上 ——
                # 差这一点会让 `/test/` 目录下的单测夹具、`GenSeedSecrets.java`
                # 这类文件被误判成明文口令。
                #
                # ⚠️ 路径必须用 **正斜杠**：CI 的 `git grep` 输出的是 `backend/src/test/...`，
                # 而 `WindowsPath` 的 str() 是 `backend\src\test\...` ——
                # 白名单里的 `/test/` 永远匹配不上，于是 Windows 上每次都误报。
                # `.as_posix()` 把分隔符统一成正斜杠，与 CI 对齐。
                out_line = "{}:{}: {}".format(path.relative_to(ROOT).as_posix(), lineno, line.strip())
                if whitelist and whitelist.search(out_line):
                    continue
                hits.append("  " + out_line[:150])
        if hits:
            failures.append(label)
            print("--- {} ---".format(label))
            print("\n".join(hits[:20]))
        else:
            print("[OK] {}".format(label))

    scan("① 疑似真实 JWT", JWT)
    scan("② 疑似明文口令", PASSWORD, PASSWORD_WHITELIST)
    scan("③ 疑似真实手机号", PHONE, PHONE_WHITELIST)
    scan("④ 疑似真实身份证号", ID_CARD, ID_WHITELIST)
    scan("⑤ 疑似模型服务 API Key", API_KEY, API_KEY_WHITELIST)

    print("扫描了 {} 个入库文件".format(len(files)))
    if failures:
        print("\n❌ 密钥体检未通过：" + "、".join(failures))
        return 1
    print("✅ 五条全过 —— CI 的 secrets job 不会红")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
