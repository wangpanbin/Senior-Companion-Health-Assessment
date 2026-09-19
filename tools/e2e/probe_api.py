# -*- coding: utf-8 -*-
"""
银龄伴诊 · 接口真值探针（绕过浏览器，直接打后端）
================================================

存在的理由：Playwright 巡检只证明「页面没报错、有文字」，
**不证明页面上是真数据**。一个永远显示「暂无数据」的页面同样能判绿。
所以联调验收必须有两层：

    第 1 层（pnpm exec playwright test）  11 个 spec 覆盖页面渲染 / API 200 / 状态机
    第 2 层（本脚本）                  每个页面依赖的接口**确实返回了种子数据**

本脚本用 Playwright 已落的 storageState 拿 accessToken,直连接口,把关键字段
单独拎出来打印 —— 这样「前端为什么不显示」能立刻定位到「后端没给」还是
「前端没取」。

用法（PowerShell）：
    $env:PYTHONUTF8=1
    python tools/e2e/probe_api.py

前置：必须先跑过 `pnpm -C frontend exec playwright test --project=setup`
      让 reports/playwright/.auth/*.json 存在 —— 见 tools/e2e/_probe_http.py

补充：本脚本只打**读接口**（不污染数据）。写接口（通用文件上传）的验证在
`tools/e2e/probe_backend_gaps.py`，它会上传 1 个真实文件，**跑完须按提示清理**。
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _probe_http import read_token, http  # noqa: E402  共享 storageState + HTTP

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def dig(obj, path):
    """按 'a.b.0.c' 取值；取不到返回 None。"""
    cur = obj
    for seg in path.split("."):
        if cur is None:
            return None
        if seg.isdigit():
            idx = int(seg)
            if not isinstance(cur, list) or idx >= len(cur):
                return None
            cur = cur[idx]
        else:
            if not isinstance(cur, dict):
                return None
            cur = cur.get(seg)
    return cur


def short(v, n=110):
    s = json.dumps(v, ensure_ascii=False) if not isinstance(v, str) else v
    return s if len(s) <= n else s[:n] + "…"


# ============================================================
# 探针表：账号 → [(说明, 路径, [{断言: 取值路径 或 期望值}])]
# 断言形式： "字段路径" 表示「该路径必须有值」；("路径", 期望值) 表示「必须等于」
# ============================================================
PROBES = [
    ("elder001", [
        # ⚠️ 2026-09-16 起 elderId 由 UserInfoVO 直接下发（仅 ELDER 有值），
        # 老人端已不再需要「列表取 id → 详情读 elderId」的两步绕法。
        # 见 docs/agents/FRONTEND_CONTRACT.md §11。
        ("当前用户资料（须含 elderId=401）", "/user/profile",
         ["id", "role", "nickname", "elderId", ("elderId", 401)]),
        # ⚠️ 列表 VO（OrderVO.ofList）**有意不带 elderId / familyId / companionId / 地址**，
        # 只给脱敏 elderName + elderAge（OrderAccessMatrixTest 有硬断言）。
        # 2026-09-16 新增：结算字段 paymentStatus / paymentStatusLabel / actualFee 已下发。
        ("我的订单（列表 VO 无内部 id，但含结算字段）", "/order?page=1&size=5",
         ["total", "records.0.id", "records.0.status", "records.0.elderName",
          "records.0.paymentStatus", "records.0.paymentStatusLabel"]),
        ("老人自己对订单详情的可见性（注意：OrderVO.detail 里 elderId 是被 requireInvolved 放行后才给的）",
         "/order/1001", ["id", "elderId"]),
        ("今日服药任务（elderId 现取自 /user/profile）", "/medication/task/today?elderId=401", []),
        ("消息未读数", "/message/unread-count", ["total"]),
    ]),
    ("fam001", [
        ("当前用户资料", "/user/profile", ["id", "role"]),
        ("我的老人档案", "/user/elder?page=1&size=10", ["total", "records.0.name", "records.0.id"]),
        ("我的订单", "/order?page=1&size=10",
         ["total", "records.0.id", "records.0.status",
          "records.0.paymentStatus", "records.0.paymentStatusLabel", "records.0.actualFee"]),
        ("订单详情 1001", "/order/1001", ["id", ("status", "PENDING"), "elderId"]),
        ("订单时间线 1001", "/order/1001/timeline", []),
        ("老人 401 今日任务", "/medication/task/today?elderId=401", []),
        ("老人 401 用药计划", "/medication/plan?elderId=401&page=1&size=10", ["total"]),
        ("服药日历（本月）", "/medication/task/calendar?elderId=401&startDate=2026-09-01&endDate=2026-09-30", []),
        ("消息未读数", "/message/unread-count", ["total"]),
    ]),
    ("fam019", [
        ("我的订单（应有 COMPLETED 1019）", "/order?page=1&size=20", ["total"]),
        ("订单详情 1019", "/order/1019", ["id", ("status", "COMPLETED")]),
    ]),
    ("comp007", [
        ("当前用户资料", "/user/profile", ["id", "role"]),
        ("我的订单（应有 ACCEPTED 1007）", "/order?page=1&size=20", ["total"]),
        ("订单详情 1007", "/order/1007", ["id", ("status", "ACCEPTED"), "elderId"]),
        ("接单大厅", "/order/hall?page=1&size=5", ["total"]),
        ("我的陪诊员资质", "/user/companion/application", []),
    ]),
    ("comp001", [
        ("接单大厅", "/order/hall?page=1&size=5", ["total"]),
    ]),
    ("admin", [
        # 注意：统计接口挂在 /api/statistics（StatisticsController 类级 @PreAuthorize ADMIN），
        # **不是** /api/admin/statistics/* —— 早期探针写错前缀会拿到 404，误判成「接口缺失」。
        ("数据看板总览", "/statistics/overview", []),
        ("订单趋势", "/statistics/order-trend", ["categories"]),
        ("订单状态分布", "/statistics/order-status", []),
        ("陪诊员排行", "/statistics/companion-rank", []),
        ("漏服率统计", "/statistics/medication-missed", []),
        ("待审资质列表", "/admin/companion/audit?page=1&size=10", ["total"]),
        ("用户管理", "/admin/user?page=1&size=10", ["total"]),
        ("订单管理", "/admin/order?page=1&size=10", ["total"]),
        ("投诉管理", "/admin/complaint?page=1&size=10", ["total"]),
        ("操作日志", "/admin/oper-log?page=1&size=10", ["total"]),
    ]),
]


def check(body, assertions):
    """返回 (ok_count, problems)。"""
    problems = []
    for a in assertions:
        if isinstance(a, tuple):
            path, expect = a
            got = dig(body, path)
            if got != expect:
                problems.append("%s 期望 %r 实际 %r" % (path, expect, got))
        else:
            got = dig(body, a)
            if got is None or got == "" or got == [] or got == {}:
                problems.append("%s 为空（%r）" % (a, got))
    return len(assertions) - len(problems), problems


def main():
    total_ok = total_bad = 0
    lines = []
    for username, probes in PROBES:
        try:
            tok = read_token(username)
        except FileNotFoundError as e:
            lines.append("[MISS] %s —— %s" % (username, e))
            continue
        except KeyError as e:
            lines.append("[FAIL] %s —— %s" % (username, e))
            continue
        # role 仅用于报告打印,从 helpers/accounts.js 注释里的对应关系读
        role_for_account = {
            "admin": "ADMIN", "fam001": "FAMILY", "fam019": "FAMILY",
            "comp001": "COMPANION", "comp007": "COMPANION", "elder001": "ELDER",
            "comp025": "COMPANION",
        }.get(username, "?")
        lines.append("")
        lines.append("=" * 78)
        lines.append("账号 %s  (role=%s)" % (username, role_for_account))
        lines.append("=" * 78)
        for label, path, asserts in probes:
            status, body = http("GET", path, token=tok)
            code = dig(body, "code")
            data = dig(body, "data")
            payload = data if data is not None else body
            if status != 200 or code != 200:
                total_bad += 1
                lines.append("  [FAIL] %-34s HTTP=%s code=%s msg=%s"
                             % (label, status, code, short(dig(body, "message"), 80)))
                continue
            ok, probs = check(payload, asserts)
            if probs:
                total_bad += 1
                lines.append("  [WARN] %-34s %s" % (label, path))
                for p in probs:
                    lines.append("         - %s" % p)
                lines.append("         body: %s" % short(payload, 200))
            else:
                total_ok += 1
                lines.append("  [ OK ] %-34s %s" % (label, short(payload, 150)))

    lines.append("")
    lines.append("合计：OK %d / 异常 %d" % (total_ok, total_bad))

    out = "\n".join(lines) + "\n"
    dst = os.path.join(ROOT, "reports", "e2e", "api-probe.txt")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(out)
    try:
        print(out)
    except UnicodeEncodeError:
        print("[console 编码不支持中文，详见 %s]" % dst)
    return 1 if total_bad else 0


sys.exit(main())
