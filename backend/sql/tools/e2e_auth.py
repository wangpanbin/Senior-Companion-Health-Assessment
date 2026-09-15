# -*- coding: utf-8 -*-
"""
银龄伴诊 —— M2 认证与多角色鉴权 端到端实测脚本

用真实 HTTP 请求打真实后端（不是 Mock），逐条验证 docs/api/01-auth-user.md
「三、验收标准（M2）」里的每一项。

验证码是图片，测试不识别图片；脚本从 Redis 读回服务端生成的明文验证码，
这是唯一一处「测试直接读取服务端状态」，其余全部走正常接口。

用法：
    python e2e_auth.py        # 前提：后端已在 8080 启动，MySQL 与 Redis 可用
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080/api"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"

PASSWORD = "Nl@123456"

# 种子账号（V2__seed_data.sql）
ACC_ELDER = "elder001"
ACC_ELDER_DISABLED = "elder030"
ACC_FAMILY = "fam001"
ACC_COMPANION = "comp001"
ACC_ADMIN = "admin"

STAMP = str(int(time.time()))
ACC_NEW = "e2e" + STAMP                  # 13 位，符合 ^[A-Za-z0-9_]{4,20}$
PHONE_NEW = "19" + STAMP[-9:]            # 11 位，符合 ^1[3-9]\d{9}$
ACC_LOCK = "e2elock" + STAMP
NEW_PASSWORD = "e2ePass123456"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  -> " + detail) if detail else ""))
    sys.stdout.flush()


def call(method, path, body=None, token=None):
    """返回 (http_status, json_body)"""
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw[:300]}


def redis_get(key):
    return subprocess.run([REDIS_CLI, "GET", key], capture_output=True, text=True).stdout.strip()


def redis_del(*keys):
    subprocess.run([REDIS_CLI, "DEL"] + list(keys), capture_output=True, text=True)


def mysql_value(sql):
    out = subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ.get("MYSQL_PASSWORD", ""),
         "--batch", "--skip-column-names", "--raw",
         "--default-character-set=utf8mb4", "--database=" + DB, "-e", sql],
        capture_output=True, text=True)
    return (out.stdout or "").strip()


def fresh_captcha():
    """取一张新验证码，并从 Redis 读回服务端生成的明文"""
    status, body = call("GET", "/auth/captcha")
    if status != 200 or body.get("code") != 200:
        raise RuntimeError("获取验证码失败：%s %s" % (status, body))
    key = body["data"]["captchaKey"]
    return key, redis_get("captcha:" + key), body["data"]


def login(username, password=PASSWORD, captcha_override=None):
    """每次登录都取新验证码 —— 验证码一次性，复用会被 1003 掩盖真实结果"""
    key, code, _ = fresh_captcha()
    if captcha_override is not None:
        code = captcha_override
    return call("POST", "/auth/login", {
        "username": username, "password": password,
        "captchaKey": key, "captchaCode": code,
    })


def login_ok(username, password=PASSWORD):
    _, body = login(username, password)
    if body.get("code") != 200:
        raise RuntimeError("登录 %s 失败：%s" % (username, body))
    return body["data"]


def register(username, phone, password, nickname, role):
    key, code, _ = fresh_captcha()
    return call("POST", "/auth/register", {
        "username": username, "phone": phone, "password": password,
        "nickname": nickname, "role": role, "captchaKey": key, "captchaCode": code,
    })


def main():
    print("=" * 78)
    print("银龄伴诊 M2 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    # ---------------- A. 公开接口 ----------------
    status, body = call("GET", "/health")
    check("A1 健康检查免登录可访问", status == 200 and body.get("code") == 200,
          "http=%s code=%s" % (status, body.get("code")))

    _, _, captcha_data = fresh_captcha()
    check("A2 验证码：返回 key / base64 图片 / 有效期 300 秒",
          bool(captcha_data.get("captchaKey"))
          and captcha_data.get("captchaImage", "").startswith("data:image/png;base64,")
          and captcha_data.get("expiresIn") == 300,
          "expiresIn=%s imgLen=%s" % (captcha_data.get("expiresIn"),
                                      len(captcha_data.get("captchaImage", ""))))

    # ---------------- B. 登录 ----------------
    _, body = login(ACC_FAMILY, captcha_override="ZZZZ")
    check("B1 验证码错误 → code 1003", body.get("code") == 1003,
          "code=%s" % body.get("code"))

    fam = login_ok(ACC_FAMILY)
    check("B2 登录成功：双令牌 + expiresIn=7200 + tokenType=Bearer",
          bool(fam.get("accessToken")) and bool(fam.get("refreshToken"))
          and fam.get("expiresIn") == 7200 and fam.get("tokenType") == "Bearer",
          "expiresIn=%s tokenType=%s" % (fam.get("expiresIn"), fam.get("tokenType")))

    phone = fam["userInfo"].get("phone", "")
    check("B3 登录响应手机号已脱敏（合规红线，形如 138****8888）",
          re.fullmatch(r"\d{3}\*{4}\d{4}", phone) is not None,
          "phone=%s" % phone)

    # 同一验证码连续提交两次
    k2, c2, _ = fresh_captcha()
    payload = {"username": ACC_FAMILY, "password": PASSWORD, "captchaKey": k2, "captchaCode": c2}
    _, first = call("POST", "/auth/login", payload)
    _, second = call("POST", "/auth/login", payload)
    check("B4 同一验证码二次提交 → 第一次 200、第二次 1003（防重放）",
          first.get("code") == 200 and second.get("code") == 1003,
          "first=%s second=%s" % (first.get("code"), second.get("code")))

    _, body = login(ACC_ELDER_DISABLED)
    check("B5 已封禁账号（密码正确）→ code 1002", body.get("code") == 1002,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    _, body = login(ACC_FAMILY, "WrongPass999")
    check("B6 密码错误 → code 1001", body.get("code") == 1001, "code=%s" % body.get("code"))
    msg_bad_pwd = body.get("message")

    _, body = login("no_such_account_xyz")
    check("B7 账号不存在 → 同为 1001，且 message 与 B6 完全一致（防账号枚举）",
          body.get("code") == 1001 and body.get("message") == msg_bad_pwd,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    fam_token = fam["accessToken"]
    fam_refresh = fam["refreshToken"]

    # ---------------- C. 认证接口 ----------------
    status, body = call("GET", "/auth/me", None, fam_token)
    data = body.get("data") or {}
    check("C1 GET /auth/me：200 + 手机号脱敏 + 响应体无 password 字段",
          status == 200 and data.get("role") == "FAMILY" and data.get("roleLabel") == "家属"
          and "password" not in json.dumps(body, ensure_ascii=False)
          and "****" in (data.get("phone") or ""),
          "role=%s roleLabel=%s phone=%s" % (data.get("role"), data.get("roleLabel"), data.get("phone")))

    status, body = call("GET", "/auth/me")
    check("C2 不带令牌访问受保护接口 → HTTP 401 + body.code 401",
          status == 401 and body.get("code") == 401,
          "http=%s code=%s" % (status, body.get("code")))

    status, body = call("GET", "/auth/me", None, "not-a-jwt-at-all")
    check("C3 伪造令牌 → HTTP 401", status == 401, "http=%s" % status)

    # ---------------- D. 权限矩阵（4 角色 × 3 类接口） ----------------
    admin_token = login_ok(ACC_ADMIN)["accessToken"]
    elder_token = login_ok(ACC_ELDER)["accessToken"]
    comp_token = login_ok(ACC_COMPANION)["accessToken"]
    READ = "/common/perm-probe/authenticated"
    ADMIN = "/common/perm-probe/admin"
    WRITE = "/common/perm-probe/elder-write"

    for name, method, path, token, expect in [
        ("D1  只读接口 · ELDER → 200", "GET", READ, elder_token, 200),
        ("D2  只读接口 · FAMILY → 200", "GET", READ, fam_token, 200),
        ("D3  只读接口 · COMPANION → 200", "GET", READ, comp_token, 200),
        ("D4  只读接口 · ADMIN → 200", "GET", READ, admin_token, 200),
        ("D5  管理接口 · ADMIN → 200", "GET", ADMIN, admin_token, 200),
        ("D6  管理接口 · ELDER → 403", "GET", ADMIN, elder_token, 403),
        ("D7  管理接口 · FAMILY → 403", "GET", ADMIN, fam_token, 403),
        ("D8  管理接口 · COMPANION → 403", "GET", ADMIN, comp_token, 403),
        ("D9  写接口 · COMPANION → 403", "POST", WRITE, comp_token, 403),
        ("D10 写接口 · FAMILY → 403", "POST", WRITE, fam_token, 403),
        ("D11 写接口 · ADMIN → 403", "POST", WRITE, admin_token, 403),
    ]:
        status, body = call(method, path, None, token)
        check(name, status == expect, "http=%s code=%s" % (status, body.get("code")))

    status, body = call("POST", WRITE, None, elder_token)
    check("D12 写接口 · ELDER → 403 且提示「只读」（服务端拦截，非前端隐藏按钮）",
          status == 403 and "只读" in (body.get("message") or ""),
          "http=%s message=%s" % (status, body.get("message")))

    # ---------------- E. 老人账号白名单写操作 ----------------
    status, body = call("POST", "/auth/logout", None, elder_token)
    check("E1 老人账号调 POST /auth/logout → 不被只读拦截（@AllowElderWrite 生效）",
          status != 403, "http=%s code=%s" % (status, body.get("code")))

    status, body = call("GET", "/auth/me", None, elder_token)
    check("E2 登出后原令牌立即失效 → 401（Redis 黑名单生效）", status == 401, "http=%s" % status)

    # ---------------- F. 刷新令牌 ----------------
    _, body = call("POST", "/auth/refresh", {"refreshToken": fam_refresh})
    new_token = (body.get("data") or {}).get("accessToken")
    check("F1 refreshToken 换新 accessToken → 200", body.get("code") == 200,
          "code=%s" % body.get("code"))

    status, body = call("GET", "/auth/me", None, new_token)
    check("F2 用刷新得到的令牌访问 → 200", status == 200 and body.get("code") == 200,
          "http=%s code=%s" % (status, body.get("code")))

    _, body = call("POST", "/auth/refresh", {"refreshToken": new_token})
    check("F3 拿 accessToken 冒充 refreshToken → 1005", body.get("code") == 1005,
          "code=%s" % body.get("code"))

    # ---------------- G. 注册 → 改密 → 令牌全失效 ----------------
    _, body = register("e2eadm" + STAMP, "18" + STAMP[-9:], NEW_PASSWORD, "越权测试", "ADMIN")
    check("G1 注册 role=ADMIN → 400（注册接口不可能产出管理员）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    _, body = register(ACC_NEW, PHONE_NEW, PASSWORD, "端到端测试", "FAMILY")
    new_user_id = (body.get("data") or {}).get("userId")
    check("G2 正常注册 → 200 且返回 userId",
          body.get("code") == 200 and bool(new_user_id),
          "code=%s userId=%s" % (body.get("code"), new_user_id))

    _, body = register("e2edup" + STAMP, PHONE_NEW, PASSWORD, "重复手机号", "FAMILY")
    check("G3 手机号重复注册 → 1007", body.get("code") == 1007,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    token_a = login_ok(ACC_NEW)["accessToken"]
    _, body = call("PUT", "/auth/password", {
        "oldPassword": PASSWORD, "newPassword": NEW_PASSWORD, "confirmPassword": NEW_PASSWORD,
    }, token_a)
    check("G4 修改密码 → 200", body.get("code") == 200,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, _ = call("GET", "/auth/me", None, token_a)
    check("G5 改密后原令牌立即失效 → 401（密码版本递增）", status == 401, "http=%s" % status)

    _, body = login(ACC_NEW, NEW_PASSWORD)
    check("G6 用新密码可登录 → 200", body.get("code") == 200, "code=%s" % body.get("code"))

    _, body = login(ACC_NEW, PASSWORD)
    check("G7 用旧密码登录 → 1001", body.get("code") == 1001, "code=%s" % body.get("code"))

    # ---------------- H. 登录失败锁定 ----------------
    codes = [login(ACC_LOCK, "WrongPass999")[1].get("code") for _ in range(6)]
    check("H1 连续失败 5 次后第 6 次 → 1004（前 5 次均为 1001）",
          codes[:5] == [1001] * 5 and codes[5] == 1004, "codes=%s" % codes)

    # ---------------- I. 数据库侧校验 ----------------
    row = mysql_value("SELECT CONCAT(LEFT(`password`,4),'|',`role`,'|',`status`) "
                      "FROM `sys_user` WHERE `username`='%s' AND `deleted`=0;" % ACC_NEW)
    check("I1 注册用户密码以 $2a$ 开头（BCrypt，禁止明文/可逆加密）",
          row.startswith("$2a$"), "row=%s" % row)

    leaked = mysql_value("SELECT COUNT(*) FROM `sys_login_log` "
                         "WHERE `fail_reason` LIKE '%Nl@%' OR `fail_reason` LIKE '%e2ePass%';")
    check("I2 登录日志的 fail_reason 不含任何密码串", leaked in ("0", ""), "matches=%s" % leaked)

    rows = mysql_value("SELECT COUNT(*) FROM `sys_login_log` WHERE `username`='%s';" % ACC_FAMILY)
    check("I3 登录日志已落库（可按账号维度追溯）",
          rows.isdigit() and int(rows) > 0, "rows=%s" % rows)

    # ---------------- 清理测试产物 ----------------
    mysql_value("DELETE FROM `sys_user` WHERE `username` LIKE 'e2e%';")
    mysql_value("DELETE FROM `sys_login_log` WHERE `username` LIKE 'e2e%';")
    redis_del("login:fail:" + ACC_LOCK, "pwd:version:" + str(new_user_id or 0))
    print("\n[清理] 已删除测试账号 %s 与 e2e* 登录日志，并清理 Redis 锁计数 / 密码版本" % ACC_NEW)

    # ---------------- 汇总 ----------------
    total, passed = len(results), sum(1 for _, ok, _ in results if ok)
    print("=" * 78)
    print("结果：%d/%d 通过" % (passed, total))
    if passed != total:
        print("失败项：")
        for name, ok, detail in results:
            if not ok:
                print("  - %s   (%s)" % (name, detail))
    print("=" * 78)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
