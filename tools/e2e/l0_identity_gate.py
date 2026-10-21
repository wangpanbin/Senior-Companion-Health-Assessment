# -*- coding: utf-8 -*-
"""L0 · 端口与服务身份闸门（银龄伴诊 v3.0 后联调）

跑任何 E2E 之前**必须**先跑这个。它回答一个问题：

    8080 / 8081 / 5141 上跑的，到底是不是本项目的服务？

为什么不能只看健康响应
----------------------
2026-10-21 实测事故：并发跑 `mvn test` 时，Maven surefire fork 也会把一个 Spring 上下文
起在 8080 上，它的健康响应**与被测应用完全一样**（`application=nianglin` / `profiles=dev`）——
只查响应体的断言照样全绿，但打到它上面的是「测试上下文」而不是「被测应用」，
E2E 会全线假红（那轮 43 条红全是这么来的）。

8081 同理：本项目的 8081 是 **ai-service**，而接口文档里恰好有「后端换端口到 8081」的写法；
8081 上跑着别的服务时 HTTP 200 照样过，但助手相关的一切都探不到真东西。

所以身份必须靠 **「监听进程的命令行」+「应用自己的标识字段」双条件**判。

只读脚本：不写库、不改文件、不起停任何服务。纯标准库，无外部依赖。

用法：
    python tools/e2e/l0_identity_gate.py

退出码：
    0 = 全部通过
    1 = 有断言不过 —— **禁止**继续跑 E2E（否则会出现假红）

端口可用环境变量覆盖：NIANGLIN_BACKEND_PORT / NIANGLIN_AI_PORT / NIANGLIN_WEB_PORT
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

HOST = "127.0.0.1"
BACKEND_PORT = int(os.environ.get("NIANGLIN_BACKEND_PORT") or 8080)
AI_PORT = int(os.environ.get("NIANGLIN_AI_PORT") or 8081)
WEB_PORT = int(os.environ.get("NIANGLIN_WEB_PORT") or 5141)

BACKEND_URL = "http://%s:%d/api/health" % (HOST, BACKEND_PORT)
AI_URL = "http://%s:%d/healthz" % (HOST, AI_PORT)
WEB_URL = "http://%s:%d/" % (HOST, WEB_PORT)

# PowerShell 取「端口 -> 监听进程 pid + 命令行」。命令行是身份判定的唯一依据，
# 光看端口在不在、HTTP 200 不作数。
# ⚠️ 这里的 PowerShell 片段自带大量花括号，**不能用 str.format** —— 用字面量替换占位符。
_PS = (
    "$ports = __PORTS__; "
    "foreach ($p in $ports) { "
    "$c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue; "
    "if ($c) { "
    "$pr = Get-CimInstance Win32_Process -Filter \"ProcessId=$($c[0].OwningProcess)\" "
    "-ErrorAction SilentlyContinue; "
    "$cmd = if ($pr) { $pr.CommandLine } else { '<unknown>' }; "
    "Write-Output (\"$p`t\" + $c[0].OwningProcess + \"`t\" + $cmd) "
    "} else { Write-Output (\"$p`tDOWN`t\") } }"
)


def http_get(url: str, timeout: int = 8):
    """返回 (status, body_text)。status 为 None 表示连不上。"""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001 - 连不上也是要报告的事实，不是要吞掉的错
        return None, str(exc)


def port_map(ports):
    """端口 -> {"pid": str, "cmd": str}。取不到就返回空 dict（断言会因此判负）。"""
    ps = _PS.replace("__PORTS__", ",".join(str(p) for p in ports))
    try:
        out = subprocess.run(
            ["pwsh", "-NoProfile", "-Command", ps],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except FileNotFoundError:
        print("  [WARN] 找不到 pwsh，无法取「端口 -> 监听进程」映射；身份断言将判负。")
        return {}
    rows = {}
    for line in (out.stdout or "").splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        try:
            port = int(parts[0])
        except ValueError:
            continue
        rows[port] = {"pid": parts[1].strip(), "cmd": parts[2].strip()}
    return rows


def cmd_of(rows, port) -> str:
    row = rows.get(port)
    if not row:
        return ""
    return row.get("cmd", "")


def has_all(cmd: str, *needles: str) -> bool:
    """命令行同时包含全部关键字才算命中。"""
    if not cmd:
        return False
    low = cmd.lower()
    return all(n.lower() in low for n in needles)


def main() -> int:
    print("=== 端口 -> 监听进程（身份判定的唯一依据）===")
    rows = port_map([BACKEND_PORT, AI_PORT, WEB_PORT])
    for port in (BACKEND_PORT, AI_PORT, WEB_PORT):
        row = rows.get(port)
        if not row:
            print("  %5d  <取不到监听进程>" % port)
            continue
        cmd = row["cmd"]
        if len(cmd) > 160:
            cmd = cmd[:160] + " ..."
        print("  %5d  pid %-8s %s" % (port, row["pid"], cmd))

    checks = []  # (序号, 标签, 是否通过, 详情)

    def add(label: str, ok: bool, detail: str = "") -> None:
        checks.append((len(checks) + 1, label, bool(ok), detail))

    # ---------- 8080 后端 ----------
    print("\n=== 后端 %d ===" % BACKEND_PORT)
    status, body = http_get(BACKEND_URL)
    payload = {}
    if status is not None:
        try:
            payload = json.loads(body)
        except ValueError:
            payload = {}
    data = payload.get("data") or {}
    top_keys = set(payload.keys()) if isinstance(payload, dict) else set()
    cmd = cmd_of(rows, BACKEND_PORT)

    add("HTTP 200", status == 200, "实际 %s" % status)
    add("code == 200", payload.get("code") == 200, "实际 %r" % payload.get("code"))
    add("data.application == 'nianglin'", data.get("application") == "nianglin",
        "实际 %r" % data.get("application"))
    add("data.profiles 含 'dev'", "dev" in str(data.get("profiles") or ""),
        "实际 %r" % data.get("profiles"))
    add("data.status == 'UP'", data.get("status") == "UP", "实际 %r" % data.get("status"))
    add("顶层字段是 'message' 而非 'msg'",
        "message" in top_keys and "msg" not in top_keys, "实际顶层 %s" % sorted(top_keys))
    # ⚠️ 身份判定写成「是本项目的进程」+「不是 surefire fork」两条，
    #    而**不是**死要求 `-jar`：
    #    `tools/e2e/start-services.ps1` 拉起的后端是 `mvn spring-boot:run`，
    #    它的命令行里只有 classpath（**没有 `-jar`**）—— 死要求 `-jar`
    #    会把一个完全正常的后端误判成「端口被占」。
    is_ours = "nianglin" in cmd.lower()
    is_surefire = "surefire" in cmd.lower()
    add("监听者是本项目的进程（非 surefire 测试 fork）",
        is_ours and not is_surefire,
        "命令行未同时满足：含 'nianglin' 且不含 'surefire'")

    # ---------- 8081 ai-service ----------
    print("\n=== ai-service %d ===" % AI_PORT)
    ai_status, ai_body = http_get(AI_URL)
    ai_payload = {}
    if ai_status is not None:
        try:
            ai_payload = json.loads(ai_body)
        except ValueError:
            ai_payload = {}
    ai_cmd = cmd_of(rows, AI_PORT)

    add("8081 /healthz 的 service == 'nianglin-ai-assistant'",
        ai_status == 200 and ai_payload.get("service") == "nianglin-ai-assistant",
        "HTTP %s，实际 %r" % (ai_status, ai_payload.get("service")))
    add("8081 监听者是本项目的 uvicorn（app.main:app）",
        has_all(ai_cmd, "uvicorn", "app.main:app"),
        "命令行未同时含 'uvicorn' 与 'app.main:app'")

    # ---------- 5141 前端 ----------
    print("\n=== 前端 %d ===" % WEB_PORT)
    web_status, _ = http_get(WEB_URL)
    web_cmd = cmd_of(rows, WEB_PORT)

    add("5141 前端在线且监听者是 vite",
        web_status == 200 and has_all(web_cmd, "vite"),
        "HTTP %s；命令行含 'vite'：%s" % (web_status, has_all(web_cmd, "vite")))

    # ---------- 汇总 ----------
    print("\n=== L0 身份断言（%d 项）===" % len(checks))
    for idx, label, ok, detail in checks:
        mark = "PASS" if ok else "FAIL"
        suffix = ("  <- " + detail) if (not ok and detail) else ""
        print("  [%s] %2d. %s%s" % (mark, idx, label, suffix))
    passed = sum(1 for _, _, ok, _ in checks if ok)
    print("  -> %d/%d" % (passed, len(checks)))

    if passed != len(checks):
        print("\n结论：身份闸门未通过 —— 端口上可能不是本项目服务，**禁止进入 E2E**"
              "（否则会出现假红）。")
        print("  · 8080 若是 surefire fork：停掉并发跑的 `mvn test`，重启独立 jar 再验。")
        print("  · 8081 若是别的服务：确认谁占着它（接口文档里恰好有"
              "「后端换端口到 8081」的写法）。")
        return 1

    print("\n结论：L0 通过 —— %d/%d 身份断言全绿，服务身份正确，可以进入 E2E。" % (passed, len(checks)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
