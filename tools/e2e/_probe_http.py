# -*- coding: utf-8 -*-
"""
银龄伴诊 · E2E 探针 HTTP 客户端
================================

本模块是 **tools/e2e/probe_*.py** 的共享 HTTP 客户端 + storageState 读取器。

# 角色：替代原 `tools/e2e/harvest_tokens.py` 的 http / login / BASE 三件套
#
# 原来探针自己起一个独立的「真登录拿令牌」流程 (harvest_tokens.py)，与
# Playwright 的 `auth.setup.js` 重复维护「账号矩阵 + 验证码绕过 + Redis 取值」。
# 实际上 Playwright 的 setup 已经把真令牌 + 真 userInfo 落进
# `reports/playwright/.auth/<account>.json`(storageState native 格式),
# 探针直接读它就好——避免两套并行维护账号密码 / 验证码 / 登录流程。
#
# 本模块就两个东西:
#   1) read_token(account)  —— 解析 Playwright storageState JSON,取 accessToken
#   2) http(method, path, ...)  —— 复用 harvest_tokens 的 urllib 实现,保持探针 baseline 一致
#
# 关于 storageState JSON 格式 (Playwright native):
#   {
#     "cookies": [...],
#     "origins": [
#       { "origin": "http://127.0.0.1:5141",
#         "localStorage": [{"name": "nianglin_access_token", "value": "<jwt>"}, ...] }
#     ]
#   }
#
# 前置: 必须先跑过 `pnpm -C frontend exec playwright test --project=setup`
#       或 `E2E_FIXTURE=0 pnpm -C frontend exec playwright test --project=setup`
#       让 .auth/<account>.json 存在,本模块的 read_token() 才有东西可读。

用法（PowerShell）：
    cd F:\\test\\Senior Companion Health Assessment
    python tools/e2e/probe_api.py
"""

import json
import os
import sys
import urllib.error
import urllib.request

# 后端 API 根路径(与 harvest_tokens.py 一致);NIANGLIN_API 环境变量可覆盖
BASE = os.environ.get("NIANGLIN_API", "http://localhost:8080/api")

# storageState JSON 文件所在目录(与 frontend/e2e/helpers/paths.js 的 AUTH_DIR 同源)
# 这里写死相对路径,因为这是 Python 工具,与前端 ESM 模块解耦
_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
AUTH_DIR = os.path.join(_REPO_ROOT, "reports", "playwright", ".auth")

# 与 frontend/src/utils/auth.js 的 TOKEN_KEY 常量保持字面一致
# (如果改这里,要同步改 frontend/src/utils/auth.js 的 TOKEN_KEY)
_ACCESS_TOKEN_KEY = "nianglin_access_token"


def _storage_state_path(account):
    """某个账号对应的 storageState JSON 路径。"""
    return os.path.join(AUTH_DIR, "%s.json" % account)


def read_token(account):
    """
    从 Playwright storageState 文件读 accessToken。

    Raises:
        FileNotFoundError: 没有对应账号的 .auth/<account>.json(通常意味着 setup 项目没跑过)
        KeyError: 文件存在但 localStorage 里没有 nianglin_access_token 键(setup 流程异常)
    """
    path = _storage_state_path(account)
    if not os.path.exists(path):
        raise FileNotFoundError(
            "找不到 storageState: %s\n"
            "  请先跑 `pnpm -C frontend exec playwright test --project=setup` 让 setup 项目落 storageState。\n"
            "  只读巡检跳过 fixture 时: `E2E_FIXTURE=0 pnpm -C frontend exec playwright test --project=setup`"
            % path
        )
    with open(path, "r", encoding="utf-8") as f:
        state = json.load(f)
    # Playwright native: origins[*].localStorage[*].name/value
    for origin in state.get("origins") or []:
        for entry in origin.get("localStorage") or []:
            if entry.get("name") == _ACCESS_TOKEN_KEY:
                value = entry.get("value")
                if value:
                    return value
    raise KeyError(
        "storageState %s 里没有 localStorage[%s] —— setup 流程异常"
        % (path, _ACCESS_TOKEN_KEY)
    )


def http(method, path, body=None, token=None, timeout=20):
    """发一个 JSON 请求,返回 (status, parsed_body)。与原 harvest_tokens.http 完全等价。"""
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json;charset=UTF-8"
    if token:
        headers["Authorization"] = "Bearer " + token

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, {"_raw": raw[:400]}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:400]}
    except Exception as e:
        return 0, {"_error": "%s: %s" % (type(e).__name__, e)}


# 允许 `python tools/e2e/_probe_http.py` 单独跑,做 storageState 健全性检查(诊断用)
if __name__ == "__main__":
    target_accounts = ["admin", "fam001", "comp001", "elder001"]
    ok = bad = 0
    for acc in target_accounts:
        try:
            tok = read_token(acc)
            # token 头部(alg.payload. 前的部分)足够辨认,不打印完整 token 避免日志泄露
            head = tok.split(".", 1)[0] if tok else ""
            print("[ OK ] %-10s token 前缀=%s (len=%d)" % (acc, head, len(tok)))
            ok += 1
        except FileNotFoundError as e:
            print("[MISS] %-10s %s" % (acc, e))
            bad += 1
        except KeyError as e:
            print("[FAIL] %-10s %s" % (acc, e))
            bad += 1
    print("\n合计: ok=%d bad=%d" % (ok, bad))
    sys.exit(0 if bad == 0 else 1)