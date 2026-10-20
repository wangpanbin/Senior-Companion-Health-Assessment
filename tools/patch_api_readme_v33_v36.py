# -*- coding: utf-8 -*-
"""把 V3.3 – V3.6 的新端点登记进 docs/api/README.md 的权限矩阵与变更记录。

用法：python tools/patch_api_readme_v33_v36.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/api/README.md"

OLD_MATRIX = "| 陪诊员工作台统计 `/api/statistics/companion/**` | — | — | **读（仅本人，companionId 入参被忽略）** | — |"
NEW_MATRIX = (
    OLD_MATRIX
    + "\n| 候选陪诊员推荐 `/api/order/{id}/recommend-companions` | — | **读（仅本单下单家属；他人 3004）** | — | — |"
    + "\n| 运营驾驶舱 `/api/admin/operations/**` | — | — | — | 全部（概览 / 异常中心 / 趋势 / 健康度 / 供给缺口 / 干预效果） |"
)

OLD_LOG = "| v0.6.0 | 2026-10-18 | **安全加固（SEC-2026-10-18-001）**"
NEW_LOG = """| v0.7.0 | 2026-10-20 | **V3.3 – V3.6 四版迭代**（[总报告](../agents/reports/V3.3-V3.6-iteration-summary.md)）：<br>· **V3.3 陪诊员工作台** —— 新增 `GET /api/statistics/companion/me`、`/me/trend`、`/me/profile`、`/me/income`（仅 `COMPANION`，仅本人；`companionId` 入参被忽略）；`09-statistics-export.md` 新增 §8<br>· **V3.4 智能调度** —— 新增 `GET /api/order/{id}/recommend-companions`（仅下单家属，他人 `3004`）；`GET /api/order/hall?sortBy=` 开放 `DISTANCE` / `TIME_MATCH`（`INCOME` 仍 `400`）；`03-order.md` 新增 §12<br>· **V3.5 运营驾驶舱** —— 新增 `GET /api/admin/operations/overview`、`/exceptions`、`/trend`、`/health`（仅 `ADMIN`）；`08-admin.md` 新增 §17 – §20<br>· **V3.6 数据闭环** —— 新增 `GET /api/admin/operations/gap`、`/effect`（仅 `ADMIN`）；`08-admin.md` 新增 §21 – §23；新增端点↔指标机器面 `metrics/endpoints.yml` 与双向校验<br>**四版均零新增错误码**（复用 `200` / `400` / `401` / `403` / `3004` / `9002`） | — |
| v0.6.0 | 2026-10-18 | **安全加固（SEC-2026-10-18-001）**"""


def main():
    text = DOC.read_text(encoding="utf-8")
    changed = False

    if "| 候选陪诊员推荐 `/api/order/{id}/recommend-companions`" not in text:
        if OLD_MATRIX not in text:
            print("未找到权限矩阵锚点", file=sys.stderr)
            return 1
        text = text.replace(OLD_MATRIX, NEW_MATRIX, 1)
        changed = True

    if "| v0.7.0 | 2026-10-20 | **V3.3 – V3.6 四版迭代**" not in text:
        if OLD_LOG not in text:
            print("未找到变更记录锚点", file=sys.stderr)
            return 1
        text = text.replace(OLD_LOG, NEW_LOG, 1)
        changed = True

    DOC.write_text(text, encoding="utf-8")
    print("docs/api/README.md 已补 V3.3–V3.6 端点登记" + ("" if changed else "（已是最新）"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
