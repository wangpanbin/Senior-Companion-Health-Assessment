"""种子账号凭据单一真源（fixture credentials single source of truth）。

**为什么有这个文件**：本目录下 10 个夹具脚本（`e2e_*.py` / `bench_setup.py` /
`jmeter_fixture.py`）都需要同一批种子账号的口令。此前每个文件各自硬编码一份
`PASSWORD = "Nl@123456"`，改一次要动 11 个文件，漏改的那个就会在 CI 上莫名失败。
集中到这里之后，改口令只需改这一处。

**为什么默认��还是有值**：这是**测试夹具**凭据，不是生产密钥 ——
它对应的账号只存在于本机开发库（`V2__seed_data.sql` 种出来的），
且 `AGENTS.md` §0.1 要求示例数据用合规占位串。设 `NIANGLIN_SEED_PASSWORD`
环境变量可覆盖为本地实际值，避免真机连本地库时把仓库里的默认值当真口令用。

对应约束见 `docs/agents/reports/BASELINE_2026-09-28.md` §6.1。
"""

import os

#: 种子账号统一口令。可用环境变量 `NIANGLIN_SEED_PASSWORD` 覆盖。
PASSWORD = os.environ.get("NIANGLIN_SEED_PASSWORD", "Nl@123456")

#: 种子账号的 BCrypt 哈希（对应上面这个明文，已入库，**不要**改）
#: 改口令时需先重算哈希再改这里，参考 `GenSeedSecrets.java`。
PASSWORD_BCRYPT = "$2a$10$dTfTIBtoETZnreDYKJ6Re.rgpuLth.58Y0hgjohfzPE.xBaCZZCHi"

__all__ = ["PASSWORD", "PASSWORD_BCRYPT"]
