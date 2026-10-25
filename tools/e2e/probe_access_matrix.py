# -*- coding: utf-8 -*-
"""
银龄伴诊 · A-1 越权矩阵探针（V3.1–V3.7 新增端点 × 4 角色）
==========================================================

存在的理由：docs/reports/TEST_REPORT.md §6.1 的 A-1（25 端点 × 4 角色 =
100 次真实 HTTP，0 违规）当年跑在本机 `reports/run/access_matrix.py` ——
那个目录被 .gitignore 整体排除，**报告里承诺可复跑的数字在仓库里没有落点**，
老师问「这 100 次能再跑一遍吗」就答不上来（2026-10-25 验收 P2-4）。
本脚本把它收编进 tools/e2e/：数据与判据与原始 A-1 完全同口径，
HTTP 层复用 _probe_http.http（与 probe_api.py 同一套客户端）。

对每个端点 × 每个角色发起**真实 HTTP** 请求，判定两件事：

  ① 允许的角色 **不得** 被 403 拦下（可用性红线）
  ② 不允许的角色 **必须** 被 403 拦下（越权红线）

期望值来自源码里的 @PreAuthorize 注解（类级 + 方法级），不是猜的。
令牌来源：Playwright setup 项目落下的 reports/playwright/.auth/<account>.json
（真实 UI 登录所得的 accessToken），与正式 E2E 套件同源。

⚠️ 两种判据，不能混用：
  「role」端点 = 有 @PreAuthorize 角色门槛 → 不允许的角色必须 **403**；
  「resource」端点 = **只做资源归属**（OrderServiceImpl#requireInvolved），
      设计上就允许 ADMIN / 下单家属 / 接单陪诊员 / 该单老人，
      无关方拿的是业务码 **3004**（不是 403）—— 见 AGENTS.md §0.1 的场景豁免口径。
      `GET /api/order/{id}/fulfillment` 属此类：源码里它上面那行
      @PreAuthorize(FAMILY) 属于**上一个方法**，它自己并没有角色守卫
      （契约依据 docs/api/03-order.md §12）。

写路径说明：本脚本只有两个 POST（催单 urge 带空 body、助手提问），
与当年 A-1 的请求逐字相同；催单若命中真实写路径，跑完可按 tools/e2e/README.md
用 `fixture.py restore` 复位基线。

用法（PowerShell）：
    $env:PYTHONUTF8=1
    python tools/e2e/probe_access_matrix.py

前置：后端 8080 在线；必须先跑过 `pnpm -C frontend exec playwright test
      --project=setup` 让 reports/playwright/.auth/*.json 存在且未过期
      —— 见 tools/e2e/_probe_http.py
"""

import os
import socket
import sys
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _probe_http import BASE, http, read_token, TokenExpiredError  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# 角色 -> storageState 里的账号。
# COMPANION 必须用 **comp007**（订单 1007 的接单陪诊员），不是 comp001 ——
# fulfillment 端点的「相关方 200」断言依赖 1007 归属 fam001/comp007。
ROLES = [
    ("ADMIN", "admin"),
    ("FAMILY", "fam001"),
    ("ELDER", "elder001"),
    ("COMPANION", "comp007"),
]

# (版本, 方法, 路径, 允许角色, POST 体)
ENDPOINTS = [
    ("V3.1", "GET", "/order/1007/fulfillment", {"FAMILY"}, None),
    ("V3.1", "POST", "/order/1007/urge", {"FAMILY"}, {}),
    ("V3.1", "GET", "/admin/fulfillment/summary", {"ADMIN"}, None),
    ("V3.1", "GET", "/admin/fulfillment/risk-list", {"ADMIN"}, None),
    ("V3.2", "GET", "/elder/health/overview", {"ELDER"}, None),
    ("V3.2", "GET", "/elder/health/timeline", {"ELDER"}, None),
    ("V3.2", "GET", "/family/health/summary", {"FAMILY"}, None),
    ("V3.2", "GET", "/family/elder/401/health/overview", {"FAMILY"}, None),
    ("V3.2", "GET", "/admin/metrics", {"ADMIN"}, None),
    ("V3.3", "GET", "/statistics/companion/me", {"COMPANION"}, None),
    ("V3.3", "GET", "/statistics/companion/me/trend", {"COMPANION"}, None),
    ("V3.3", "GET", "/statistics/companion/me/profile", {"COMPANION"}, None),
    ("V3.3", "GET", "/statistics/companion/me/income", {"COMPANION"}, None),
    ("V3.4", "GET", "/order/1007/recommend-companions", {"FAMILY"}, None),
    ("V3.4", "GET", "/order/hall", {"COMPANION"}, None),
    ("V3.5", "GET", "/admin/operations/overview", {"ADMIN"}, None),
    ("V3.5", "GET", "/admin/operations/exceptions", {"ADMIN"}, None),
    ("V3.5", "GET", "/admin/operations/trend", {"ADMIN"}, None),
    ("V3.5", "GET", "/admin/operations/health", {"ADMIN"}, None),
    ("V3.6", "GET", "/admin/operations/gap", {"ADMIN"}, None),
    ("V3.6", "GET", "/admin/operations/effect", {"ADMIN"}, None),
    ("V3.6", "GET", "/statistics/overview", {"ADMIN"}, None),
    ("V3.6", "GET", "/statistics/order-trend", {"ADMIN"}, None),
    ("V3.6", "GET", "/statistics/companion-rank", {"ADMIN"}, None),
    ("V3.7", "POST", "/assistant/chat", {"FAMILY"}, {"question": "陪诊费怎么算？"}),
]


def _backend_listening():
    """后端端口是否有进程在监听 —— 不在线时 100 次请求只会得到 100 个连接失败。"""
    parsed = urlparse(BASE)
    host = parsed.hostname or "localhost"
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        with socket.create_connection((host, port), timeout=2):
            return True
    except Exception:
        return False


def describe(status, payload):
    if status == 0:
        return "连接失败 " + str(payload.get("_error", ""))
    code = payload.get("code")
    msg = payload.get("message") or payload.get("msg") or ""
    if code is not None:
        return "HTTP %s / 业务码 %s / %s" % (status, code, msg)
    return "HTTP %s" % status


def main():
    if not _backend_listening():
        print("[FATAL] 后端未监听（BASE=%s 无连接）—— 请先启动后端再跑本探针。" % BASE)
        return 2

    tokens = {}
    for role, account in ROLES:
        try:
            tokens[role] = read_token(account)
        except TokenExpiredError as exc:
            print("[EXPI] 取不到 %s(%s) 的令牌：%s" % (role, account, exc))
            return 2
        except (FileNotFoundError, KeyError) as exc:
            print("[FATAL] 取不到 %s(%s) 的令牌：%s" % (role, account, exc))
            print("        请先跑过 Playwright 的 setup 项目（它会落 storageState）。")
            return 2

    print("=== A-1 越权矩阵（端点 × 角色）===")
    print("判据：允许角色不得 403；不允许角色必须 403。\n")

    violations = []
    rows = 0
    out_lines = []
    for ver, method, path, allowed, body in ENDPOINTS:
        # 该端点是「只做资源归属」型：允许 ADMIN 与该单的接单陪诊员，无关方拿 3004。
        resource_scoped = path == "/order/1007/fulfillment"
        for role, _account in ROLES:
            status, payload = http(method, path, body=body, token=tokens[role])
            rows += 1
            denied = status == 403
            business_code = payload.get("code")
            should_allow = role in allowed
            if resource_scoped:
                # 期望：ADMIN 与接单陪诊员(comp007) 拿到 200；下单家属(fam001) 与
                # 老人(elder001) 不是这一单的当事人 → 3004（业务码，不是 403）。
                if role in ("ADMIN", "COMPANION"):
                    ok = business_code == 200
                    verdict = "ok" if ok else "VIOLATION · 相关方应读到 200"
                else:
                    ok = business_code == 3004
                    verdict = "ok" if ok else "VIOLATION · 无关方应被资源归属拦成 3004"
                if not ok:
                    violations.append((ver, method, path, role, describe(status, payload)))
            else:
                if should_allow and denied:
                    verdict = "VIOLATION · 允许的角色被 403 拦下"
                    violations.append((ver, method, path, role, describe(status, payload)))
                elif (not should_allow) and not denied:
                    verdict = "VIOLATION · 不允许的角色没被 403 拦下"
                    violations.append((ver, method, path, role, describe(status, payload)))
                else:
                    verdict = "ok"
            flag = "放行" if should_allow else "应拒"
            line = ("  [%s] %-6s %-6s %-46s %-9s -> %-34s %s"
                    % ("PASS" if verdict == "ok" else "FAIL", ver, role, path, flag,
                       describe(status, payload), "" if verdict == "ok" else verdict))
            print(line)
            out_lines.append(line)

    summary = "\n合计 %d 次请求，越权违规 %d 条" % (rows, len(violations))
    if violations:
        summary += "\n\n=== 违规明细 ==="
        for v in violations:
            summary += "\n  %s %s %s  角色=%s  %s" % v
    else:
        summary += "\n✅ 越权矩阵全绿：该放行的放行，该拦的拦住。"
    print(summary)
    out_lines.append(summary)

    out = os.path.join(ROOT, "reports", "e2e", "access-matrix-probe.txt")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("# A-1 越权矩阵探针（25 端点 × 4 角色）\n\n")
        f.write("\n".join(out_lines) + "\n")
    try:
        print("\n写出 %s" % out)
    except UnicodeEncodeError:
        print("[console 编码不支持中文，详见 %s]" % out)
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
