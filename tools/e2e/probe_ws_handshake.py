# -*- coding: utf-8 -*-
"""
银龄伴诊 · WebSocket 握手探针
=============================

为什么单独做：`/ws/progress` 的鉴权与订单归属校验在 `JwtHandshakeInterceptor`
（不是 HTTP 过滤器），握手失败时**浏览器只会报一句没头没尾的
`WebSocket connection to 'ws://...' failed`**，不带任何状态码，
根本分不清是「vite 没转发」「令牌过期」「订单不属于我」还是「后端没起」。

本脚本用**裸 socket** 发一次 Upgrade 请求，直接读回 HTTP 状态行 ——
并**同时打后端 8080 与前端 dev server 端口**，做差分：

  - 8080 通、代理端口不通  → vite 的 '/ws' 代理没生效（配置问题）
  - 两边都 403/401        → 鉴权/归属问题（令牌或订单数据问题）
  - 两边都 101            → 后端与代理都正常，巡检报错另有原因

用法（PowerShell）：
    $env:PYTHONUTF8=1
    python tools/e2e/probe_ws_handshake.py

前置：必须先跑过 `pnpm -C frontend exec playwright test --project=setup`
      让 reports/playwright/.auth/*.json 存在 —— 见 tools/e2e/_probe_http.py
"""

import os
import socket
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _probe_http import read_token  # noqa: E402  共享 storageState 读取

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# 被测样本：(账号, 订单 id)。
# 多准备几组做**归因差分**：如果只有 comp007/1007 挂，那是数据问题；
# 如果全挂，那是握手拦截器/安全链的系统性问题。
CASES = [
    ("comp007", 1007),   # ACCEPTED，companion_id=307（comp007 本人名下）
    ("comp007", 1055),   # comp007 名下另一单（从列表接口取到的首条）
    ("fam001", 1001),    # PENDING，family_id=101（fam001 本人下单）
]

# 目标：(说明, host, port, Origin 头发什么)。
# ORIGIN 是本次排查的关键变量 —— Spring 的握手默认做**同源校验**
# （Origin 的 host:port 必须与请求的 host:port 一致），浏览器一定带 Origin，
# 而 vite 代理又开了 changeOrigin（把 Host 改成 8080），
# 两者叠加就可能恒 403。用「不带 Origin」这一组做对照即可判定。
#   Origin 不变 → 校验的是浏览器来源，与代理无关
#   Origin 一去就 101 → 纯粹是同源校验在拦（服务端要配 allowedOriginPatterns）
TARGETS = [
    ("后端直连 8080 / 带 Origin:5410", "127.0.0.1", 8080, "http://localhost:5410"),
    ("后端直连 8080 / 带 Origin:8080", "127.0.0.1", 8080, "http://localhost:8080"),
    ("后端直连 8080 / 不带 Origin", "127.0.0.1", 8080, None),
    ("vite 代理 5410 / 带 Origin:5410", "127.0.0.1", 5410, "http://localhost:5410"),
]


def handshake(host, port, path, origin, timeout=8):
    """发一次原始 WebSocket Upgrade，返回 (状态行, 关键响应头)。"""
    req = (
        f"GET {path} HTTP/1.1\r\n"
        # Host 头统一写 localhost:{port}，与「同源」那组 Origin 严格一致
        # （否则 localhost / 127.0.0.1 的字面差异会污染结论）
        f"Host: localhost:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
        "Sec-WebSocket-Version: 13\r\n"
    )
    if origin:
        req += f"Origin: {origin}\r\n"
    req += "\r\n"
    try:
        with socket.create_connection((host, port), timeout=timeout) as s:
            s.sendall(req.encode())
            s.settimeout(timeout)
            chunk = s.recv(4096).decode("utf-8", "replace")
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"

    lines = chunk.split("\r\n")
    status = lines[0] if lines else "(empty)"
    # 握手成功的标志是 101 + Sec-WebSocket-Accept
    has_accept = any(l.lower().startswith("sec-websocket-accept") for l in lines)
    return status, ("Sec-WebSocket-Accept ✅" if has_accept else "无 Sec-WebSocket-Accept")


def main():
    out_lines = []
    bad = 0
    for username, order_id in CASES:
        try:
            token = read_token(username)
        except FileNotFoundError as e:
            print("[FAIL] %s —— %s" % (username, e))
            print("       请先跑 `pnpm -C frontend exec playwright test --project=setup` 让 storageState 落下来")
            bad += 1
            continue
        except KeyError as e:
            print("[FAIL] %s —— %s" % (username, e))
            bad += 1
            continue
        path = f"/ws/progress?token={token}&orderId={order_id}"
        verdicts = {}
        for label, host, port, origin in TARGETS:
            status, extra = handshake(host, port, path, origin)
            ok = bool(status) and "101" in status
            verdicts[label] = ok
            line = f"[{'OK  ' if ok else 'FAIL'}] {username} order={order_id} {label:<28} {status} | {extra}"
            print(line)
            out_lines.append(line)

        # ---- 差分归因（按证据强弱排序）----
        no_origin = verdicts.get("后端直连 8080 / 不带 Origin")
        same_origin = verdicts.get("后端直连 8080 / 带 Origin:8080")
        cross_origin = verdicts.get("后端直连 8080 / 带 Origin:5410")
        via_proxy = verdicts.get("vite 代理 5410 / 带 Origin:5410")

        if no_origin and not same_origin:
            conclusion = "→ 根因＝**同源校验**：Spring 握手比对 Origin 与请求 host:port，跨源一律 403。" \
                         "浏览器必带 Origin，而 vite 代理开了 changeOrigin（Host 改成 8080），" \
                         "于是 Origin(5410) ≠ Host(8080) 恒不匹配 ⇒ WS 永远连不上。" \
                         "修法：后端 WebSocket 注册处配 allowedOriginPatterns（对应前端 dev 端口），" \
                         "或代理不 changeOrigin。"
            bad += 1
        elif no_origin and same_origin and not cross_origin:
            conclusion = "→ 根因＝**跨源被拒**（同源可以、跨源不行），同上：服务端未放行前端来源。" \
                         "修法：后端 allowedOriginPatterns 加入前端地址。"
            bad += 1
        elif same_origin and not via_proxy:
            conclusion = "→ 后端 OK、代理不通：vite 的 '/ws' 代理没生效" \
                         "（检查 server.proxy['/ws'].ws=true，并重启 dev server）。"
            bad += 1
        elif not no_origin:
            conclusion = "→ 后端**连不带 Origin 都拒**：看 JwtHandshakeInterceptor 的拒绝分支" \
                         "（令牌 / 归属 / 订单不存在），或 SecurityConfig 是否真把 /ws/** 放了 permitAll。"
            bad += 1
        else:
            conclusion = "→ 四种组合都通过，巡检的 WS 报错另有原因（如页面连接时机/账号上下文）。"
        print("   " + conclusion)
        out_lines.append("   " + conclusion)

    out = os.path.join(ROOT, "reports", "e2e", "ws-handshake-probe.txt")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("# WebSocket 握手探针（/ws/progress）\n\n")
        f.write("\n".join(out_lines) + "\n")
    print(f"\n写出 {out}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
