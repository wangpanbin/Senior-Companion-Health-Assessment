# -*- coding: utf-8 -*-
"""把 V3.3 / V3.5 的新路由补进 docs/agents/FRONTEND_CONTRACT.md 的路由表。

用法：python tools/patch_frontend_contract_v33_v35.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/agents/FRONTEND_CONTRACT.md"

OLD_INCOME = "| `/companion/income` | `companion/income.vue` | COMPANION |"
NEW_INCOME = (
    OLD_INCOME
    + "\n| `/companion/workbench` | `companion/workbench.vue` | COMPANION（**V3.3** 陪诊员工作台；运营指标 / 趋势 / 服务画像 / 收入摘要四块） |"
)

OLD_ADMIN = "| `/admin/*`（8 页） | `admin/*.vue` | ADMIN |"
NEW_ADMIN = (
    "| `/admin/*`（9 页） | `admin/*.vue` | ADMIN（其中 `/admin/operations` 是 **V3.5** 运营驾驶舱："
    "概览 / 异常中心 / 趋势 / 健康度 / 供给缺口 / 干预效果） |"
)


def main():
    text = DOC.read_text(encoding="utf-8")
    changed = False

    if "| `/companion/workbench` |" not in text:
        if OLD_INCOME not in text:
            print("未找到 /companion/income 锚点", file=sys.stderr)
            return 1
        text = text.replace(OLD_INCOME, NEW_INCOME, 1)
        changed = True

    if "| `/admin/*`（9 页）" not in text:
        if OLD_ADMIN not in text:
            print("未找到 /admin/* 锚点", file=sys.stderr)
            return 1
        text = text.replace(OLD_ADMIN, NEW_ADMIN, 1)
        changed = True

    DOC.write_text(text, encoding="utf-8")
    print("FRONTEND_CONTRACT 已补 V3.3 / V3.5 新路由" + ("" if changed else "（已是最新）"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
