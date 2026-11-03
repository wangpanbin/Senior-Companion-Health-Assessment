# -*- coding: utf-8 -*-
"""
银龄伴诊 —— V4 P1-1 自助找回密码（短信通道）端到端实测脚本

用真实 HTTP 请求打真实后端，逐条验证 docs/api/01-auth-user.md §10 / §11：
- 发码：已注册 / 未注册手机号响应完全同构；60 秒内第二次 1009；参数错误 400
- 找回：验证码错误 1008；正确码 200；旧令牌 401（pwd:version 踢全端）；
  旧密码登录 1001、新密码登录 200；验证码复用 1008
- 未注册手机号忘记密码：与成功完全同构，且不产生任何用户

前提：
- 后端以 dev profile 启动（`nianglin.sms.provider=mock`，验证码不落日志）
- 本脚本从 Redis `sms:reset:{phone}` 读验证码（mock 通道唯一的测试旁路）
- MySQL / Redis 可用；redis-cli 与 mysql.exe 路径见下（与 cleanup_util 一致）

安全边界：
- **绝不动种子账号**。全链路只在当轮注册的临时 FAMILY 账号上跑，
  结束按基线物理删除；临时账号的 Redis 键一并清理。
- 清理走「基线 + finally」，断言失败 / 异常同样执行。

用法：
    python e2e_forgot_password.py
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080/api"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"

SMS_SENT_MESSAGE = "若该手机号已注册，验证码将发送至该号码"
PASSWORD_RESET_MESSAGE = "密码已重置，请重新登录"

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fixture_credentials import PASSWORD as SEED_PASSWORD  # noqa: E402  种子口令单一真源
import cleanup_util  # noqa: E402  统一收尾：基线删除 + 未读缓存清理

OLD_PASSWORD = SEED_PASSWORD
NEW_PASSWORD = "E2eNewPass456"

STAMP = str(int(time.time()))
ACC_NEW = "e2efp" + STAMP                 # 14 位，符合 ^[A-Za-z0-9_]{4,20}$
PHONE_NEW = "199" + STAMP[-8:]            # 11 位，符合 ^1[3-9]\d{9}$
PHONE_GHOST = "198" + STAMP[-8:]          # 未注册手机号
PHONE_ELDER = "197" + STAMP[-8:]          # 老人令牌发码用

results = []
user_id_start = 0
log_id_start = 0
new_user_id = None


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  -> " + detail) if detail else ""))
    sys.stdout.flush()


def url_of(path):
    return BASE + urllib.parse.quote(path, safe="/?&=:%+,[]@!$'()*;")


def call(method, path, body=None, token=None):
    """返回 (http_status, json_body)"""
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url_of(path), data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw[:300]}


def redis_get(key):
    return subprocess.run([REDIS_CLI, "GET", key], capture_output=True, text=True).stdout.strip()


def redis_ttl(key):
    out = subprocess.run([REDIS_CLI, "TTL", key], capture_output=True, text=True).stdout.strip()
    return int(out or -1)


def redis_del(*keys):
    if keys:
        subprocess.run([REDIS_CLI, "DEL"] + list(keys), capture_output=True, text=True)


def clear_all_sms_keys():
    """只清本功能命名空间 sms:*，不影响其它缓存。"""
    out = subprocess.run([REDIS_CLI, "KEYS", "sms:*"], capture_output=True, text=True)
    keys = [k for k in (out.stdout or "").splitlines() if k.strip()]
    if keys:
        redis_del(*keys)
    return len(keys)


def fresh_captcha():
    status, body = call("GET", "/auth/captcha")
    if status != 200 or body.get("code") != 200:
        raise RuntimeError("获取验证码失败：%s %s" % (status, body))
    key = body["data"]["captchaKey"]
    return key, redis_get("captcha:" + key)


def login(username, password):
    key, code = fresh_captcha()
    return call("POST", "/auth/login", {
        "username": username, "password": password,
        "captchaKey": key, "captchaCode": code,
    })


def login_ok(username, password=OLD_PASSWORD):
    _, body = login(username, password)
    if body.get("code") != 200:
        raise RuntimeError("登录 %s 失败：%s" % (username, body))
    return body["data"]


def register(username, phone, password, nickname, role):
    key, code = fresh_captcha()
    return call("POST", "/auth/register", {
        "username": username, "phone": phone, "password": password,
        "nickname": nickname, "role": role, "captchaKey": key, "captchaCode": code,
    })


def sms(phone, token=None):
    return call("POST", "/auth/sms-code", {"phone": phone}, token)


def forgot(phone, code, new_password=NEW_PASSWORD, token=None):
    return call("POST", "/auth/forgot-password", {
        "phone": phone, "smsCode": code, "newPassword": new_password,
    }, token)


def cleanup():
    cleanup_util.delete_since("sys_user", user_id_start, "sys_user")
    cleanup_util.delete_since("sys_login_log", log_id_start, "sys_login_log")
    if new_user_id:
        redis_del("pwd:version:" + str(new_user_id))
    cleared = clear_all_sms_keys()
    print("\n[清理] 临时账号 %s（id=%s）+ 日志基线后行；sms:* 键 %d 个；pwd:version"
          % (ACC_NEW, new_user_id, cleared))


def main():
    global user_id_start, log_id_start, new_user_id
    print("=" * 78)
    print("银龄伴诊 V4 P1-1 自助找回密码 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    # 上一轮失败可能留下 sms:ip 计数（IP 窗口共享），先清干净避免假性 1009
    clear_all_sms_keys()
    user_id_start = cleanup_util.max_id("sys_user")
    log_id_start = cleanup_util.max_id("sys_login_log")

    # ---------------- A. 注册临时账号 ----------------
    _, body = register(ACC_NEW, PHONE_NEW, OLD_PASSWORD, "找回测试", "FAMILY")
    new_user_id = (body.get("data") or {}).get("userId")
    check("A1 注册临时家属 → 200（全链路只在这个账号上跑）",
          body.get("code") == 200 and bool(new_user_id),
          "code=%s userId=%s" % (body.get("code"), new_user_id))
    old_token = login_ok(ACC_NEW)["accessToken"]

    # ---------------- B. 发码：同构 / 频控 / 参数 ----------------
    status, body = sms(PHONE_NEW)
    code = redis_get("sms:reset:" + PHONE_NEW)
    check("B1 已注册手机号发码 → 200 同构 + Redis 6 位码 + 5 分钟 TTL",
          status == 200 and body.get("code") == 200
          and body.get("message") == SMS_SENT_MESSAGE
          and len(code) == 6 and code.isdigit() and redis_ttl("sms:reset:" + PHONE_NEW) > 0,
          "http=%s code=%s sms=%s ttl=%s"
          % (status, body.get("code"), code, redis_ttl("sms:reset:" + PHONE_NEW)))

    status, ghost_sms = sms(PHONE_GHOST)
    check("B2 未注册手机号发码 → 响应与已注册完全同构（防枚举）",
          status == 200 and ghost_sms.get("code") == 200
          and ghost_sms.get("message") == SMS_SENT_MESSAGE,
          "http=%s body=%s" % (status, ghost_sms))

    status, again = sms(PHONE_NEW)
    check("B3 同手机号 60 秒内第二次 → 1009", again.get("code") == 1009,
          "http=%s code=%s message=%s" % (status, again.get("code"), again.get("message")))

    status, bad = call("POST", "/auth/sms-code", {"phone": ""})
    check("B4 参数格式错误 → 400（仅此一处响应不同）",
          bad.get("code") == 400, "http=%s code=%s" % (status, bad.get("code")))

    # ---------------- C. 找回密码：错误 / 成功 / 踢全端 ----------------
    wrong = "111111" if code != "111111" else "000000"
    status, body = forgot(PHONE_NEW, wrong)
    check("C1 验证码错误 → 1008", body.get("code") == 1008,
          "http=%s code=%s message=%s" % (status, body.get("code"), body.get("message")))

    status, body = forgot(PHONE_NEW, code)
    check("C2 正确码 → 200 且「密码已重置，请重新登录」",
          status == 200 and body.get("code") == 200
          and body.get("message") == PASSWORD_RESET_MESSAGE,
          "http=%s code=%s message=%s" % (status, body.get("code"), body.get("message")))

    status, body = call("GET", "/auth/me", None, old_token)
    check("C3 改密后旧令牌访问 /auth/me → 401（pwd:version 递增踢全端）",
          status == 401 and body.get("code") == 401,
          "http=%s code=%s" % (status, body.get("code")))

    _, body = login(ACC_NEW, OLD_PASSWORD)
    check("C4 旧密码登录 → 1001（与账号不存在同口径）", body.get("code") == 1001,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    _, body = login(ACC_NEW, NEW_PASSWORD)
    ok_new = body.get("code") == 200
    check("C5 新密码登录 → 200", ok_new, "code=%s" % body.get("code"))
    new_token = (body.get("data") or {}).get("accessToken") if ok_new else None

    status, body = forgot(PHONE_NEW, code, token=new_token)
    check("C6 成功用过的验证码再复用 → 1008（一次性）", body.get("code") == 1008,
          "http=%s code=%s" % (status, body.get("code")))

    row = cleanup_util.mysql_value(
        "SELECT CONCAT(`password` LIKE '$2a$%%','|',`need_change_password`) "
        "FROM `sys_user` WHERE `id`=%s;" % new_user_id)
    check("C7 落库：BCrypt（$2a$ 前缀）+ need_change_password=0",
          row == "1|0", "row=%s" % row)

    # ---------------- D. 未注册手机号忘记密码：同构 ----------------
    ghost_code = redis_get("sms:reset:" + PHONE_GHOST)
    status, body = forgot(PHONE_GHOST, ghost_code)
    check("D1 未注册手机号忘记密码 → 与成功完全同构（200 + 同一文案）",
          status == 200 and body.get("code") == 200
          and body.get("message") == PASSWORD_RESET_MESSAGE,
          "http=%s code=%s message=%s" % (status, body.get("code"), body.get("message")))

    ghost_id = cleanup_util.mysql_value(
        "SELECT COALESCE(MAX(`id`),0) FROM `sys_user` WHERE `phone`='%s';" % PHONE_GHOST)
    check("D2 未注册手机号未产生任何用户", ghost_id == "0", "max(id)=%s" % ghost_id)

    # ---------------- E. 老人账号不被只读拦截器误拦 ----------------
    elder_token = login_ok("elder001")["accessToken"]
    _, body = sms(PHONE_ELDER, elder_token)
    check("E1 ELDER 令牌发码 → 200（@AllowElderWrite 放行）", body.get("code") == 200,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    elder_code = redis_get("sms:reset:" + PHONE_ELDER)
    elder_wrong = "111111" if elder_code != "111111" else "000000"
    _, body = forgot(PHONE_ELDER, elder_wrong, token=elder_token)
    check("E2 ELDER 令牌找回密码 → 1008 而非 403（未被只读拦截器误拦）",
          body.get("code") == 1008,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

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
    code = 1
    try:
        code = main()
    except Exception:
        import traceback
        traceback.print_exc()
        code = 1
    finally:
        try:
            cleanup()
        except Exception:
            import traceback
            traceback.print_exc()
    sys.exit(code)
