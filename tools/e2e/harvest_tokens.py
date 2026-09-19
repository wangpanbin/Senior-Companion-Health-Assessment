# -*- coding: utf-8 -*-
"""
银龄伴诊 · E2E 令牌采集器
=========================

用途：为 Playwright 浏览器联调准备「四角色已登录态」。

为什么需要它：登录接口强制要求图形验证码（`CaptchaService.validate` 用
`getAndDelete` 一次性消费 Redis 键 `captcha:<captchaKey>`）。浏览器端靠人眼
识别，自动化里不可靠，所以这里直接：

    1. GET  /api/auth/captcha          → 拿 captchaKey（顺便把键写进 Redis）
    2. 读 Redis `captcha:<captchaKey>` → 拿到明文验证码（后端存的是大写）
    3. POST /api/auth/login            → 用真实验证码换真令牌

这样拿到的是**真实登录接口签发的令牌**，不是伪造的；同时绕开了 OCR 的不确定性。

输出 `reports/e2e/tokens.json`，供 `playwright-cli localstorage-set` 注入。

用法（PowerShell）：
    $env:PYTHONUTF8=1
    python tools/e2e/harvest_tokens.py
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("NIANGLIN_API", "http://localhost:8080/api")
REDIS_CLI = os.environ.get("REDIS_CLI", r"D:\develop\Redis-8.8.0\redis-cli.exe")
# 演示种子账号的统一口令：与 backend/sql/V2__seed_data.sql 的种子事实一致，
# 属**公开的演示数据**而非私密凭据（前端 e2e/helpers/accounts.js 同值）。
# 指向别的环境时用 NIANGLIN_DEMO_PASSWORD 覆盖。
PASSWORD = os.environ.get("NIANGLIN_DEMO_PASSWORD", "Nl@123456")

# 四类角色各取种子账号（见 backend/sql/V2__seed_data.sql）。
# 之所以每类多取一个，是因为 UI 巡检要覆盖「有数据」的分支：
#   fam001(101) 名下只有 1001(PENDING) 与 1031(REVIEWED)，**没有 COMPLETED**，
#   而「评价订单」页需要一条 COMPLETED 才算 happy path → 用 fam019(119)，它有 1019(COMPLETED)。
#   comp001(301) 名下没有在途订单，而「订单执行」页需要 ACCEPTED/IN_SERVICE
#   → 用 comp007(307)，它有 1007(ACCEPTED)。
ACCOUNTS = [
    ("admin", "ADMIN", 1),
    ("fam001", "FAMILY", 101),
    ("fam019", "FAMILY", 119),
    ("comp001", "COMPANION", 301),
    ("comp007", "COMPANION", 307),
    ("elder001", "ELDER", 201),
]

OUT_FILE = os.path.join("reports", "e2e", "tokens.json")


def http(method, path, body=None, token=None, timeout=20):
    """发一个 JSON 请求，返回 (status, parsed_body)。"""
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
    except Exception as e:  # 连接被拒等
        return 0, {"_error": f"{type(e).__name__}: {e}"}


def redis_get(key):
    """通过 redis-cli 读键（后端把验证码明文存在 Redis 里）。"""
    try:
        out = subprocess.run(
            [REDIS_CLI, "GET", key],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=10,
        )
    except Exception as e:
        raise RuntimeError(f"redis-cli 调用失败: {e}") from e
    val = (out.stdout or "").strip()
    if not val or val == "(nil)":
        raise RuntimeError(f"Redis 里没有 {key}（可能已过期或被消费）")
    return val.strip('"')


def login(username, role, user_id):
    """真实走一遍验证码 + 登录。"""
    st, body = http("GET", "/auth/captcha")
    if st != 200 or body.get("code") != 200:
        return None, f"取验证码失败 status={st} body={body}"
    captcha_key = (body.get("data") or {}).get("captchaKey")
    if not captcha_key:
        return None, f"验证码响应里没有 captchaKey: {body}"

    try:
        code = redis_get("captcha:" + captcha_key)
    except Exception as e:
        return None, str(e)

    st, body = http(
        "POST",
        "/auth/login",
        {"username": username, "password": PASSWORD, "captchaKey": captcha_key, "captchaCode": code},
    )
    if st != 200 or body.get("code") != 200:
        return None, f"登录失败 status={st} body={body}"

    data = body.get("data") or {}
    token = data.get("accessToken")
    if not token:
        return None, f"登录响应里没有 accessToken: {body}"

    return {
        "username": username,
        "role": role,
        "expectedUserId": user_id,
        "accessToken": token,
        "refreshToken": data.get("refreshToken") or "",
        "expiresIn": data.get("expiresIn"),
        "userInfo": data.get("userInfo") or {},
        "captchaSolved": code,
    }, None


def main():
    results = {}
    failures = {}

    for username, role, uid in ACCOUNTS:
        rec, err = login(username, role, uid)
        if err:
            failures[username] = err
            print(f"[FAIL] {username:<10} {role:<10} {err}")
            continue

        # 再打一次 /auth/me，验证令牌真的可用（而不是只拿到了字符串）
        st, body = http("GET", "/auth/me", token=rec["accessToken"])
        if st == 200 and body.get("code") == 200:
            rec["me"] = body.get("data")
            rec["meOk"] = True
        else:
            rec["meOk"] = False
            rec["meError"] = f"status={st} body={body}"
            failures[username + ":/auth/me"] = rec["meError"]

        results[username] = rec
        print(
            f"[ OK ] {username:<10} {role:<10} tokenLen={len(rec['accessToken']):<5} "
            f"me={rec['meOk']} nickname={(rec.get('userInfo') or {}).get('nickname')}"
        )

    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        json.dump({"base": BASE, "accounts": results, "failures": failures}, f, ensure_ascii=False, indent=2)

    print(f"\n写出 {OUT_FILE}")
    if failures:
        print(f"失败 {len(failures)} 项：{json.dumps(failures, ensure_ascii=False, indent=2)}")
        return 1
    print(f"{len(results)} 个账号令牌全部就绪 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
