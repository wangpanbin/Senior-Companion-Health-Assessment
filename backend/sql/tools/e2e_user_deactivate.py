# -*- coding: utf-8 -*-
"""
银龄伴诊 —— V4 P0-1 账号注销 + 个人数据导出 端到端实测脚本

用真实 HTTP 请求打真实后端，逐条验证 docs/api/01-auth-user.md §8 / §9 的契约：
- 导出：仅 FAMILY / COMPANION，octet-stream + xlsx 真实文件；ELDER / ADMIN 403、未登录 401
- 注销：四道闸门（确认文本 → 密码 → 11001 进行中订单 → 成功），
  成功后旧令牌 401、重新登录 1001、库内去标识化（墓碑手机号 / 全隐姓名 / 逻辑删 + 禁用）

安全边界（与 e2e_order.py 同一纪律）：
- **绝不注销任何种子账号**。11001 闸门用 fam001 造单再取消验证（闸门在写库之前抛，无副作用）；
  「注销成功」全链路只在当轮注册的临时账号上跑。
- 清理统一走「基线 + finally」：main 开头记各表 max_id，退出前删自己造的行与 Redis 键，
  断言失败 / 中途异常同样清理。

用法：
    python e2e_user_deactivate.py    # 前提：后端已在 8080 启动，MySQL 与 Redis 可用
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


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fixture_credentials import PASSWORD as SEED_PASSWORD  # noqa: E402  种子口令单一真源
import cleanup_util  # noqa: E402  统一收尾：基线删除 + 未读缓存清理
PASSWORD = SEED_PASSWORD

ACC_FAMILY = "fam001"        # 家属 101，绑定老人 401（造单验证 11001，不注销）
ACC_ELDER = "elder001"       # 老人 201（只读角色）
ACC_ADMIN = "admin"
ELDER_OWN = 401

STAMP = str(int(time.time()))
ACC_NEW = "e2ed" + STAMP                 # 13 位，符合 ^[A-Za-z0-9_]{4,20}$
PHONE_NEW = "19" + STAMP[-9:]            # 11 位，符合 ^1[3-9]\d{9}$

results = []
created_order_ids = []
log_id_start = 0
message_id_start = 0
user_id_start = 0
new_user_id = None


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  -> " + detail) if detail else ""))
    sys.stdout.flush()


def url_of(path):
    """URL 编码非 ASCII 字符（中文查询参数会让 urllib 抛 UnicodeEncodeError）"""
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


def call_raw(method, path, token=None):
    """返回 (http_status, headers, raw_bytes)，给导出这类非 JSON 响应用"""
    req = urllib.request.Request(url_of(path), method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


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
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (out.stdout or "").strip()


def fresh_captcha():
    """取一张新验证码，并从 Redis 读回服务端生成的明文（唯一一处旁路）"""
    status, body = call("GET", "/auth/captcha")
    if status != 200 or body.get("code") != 200:
        raise RuntimeError("获取验证码失败：%s %s" % (status, body))
    key = body["data"]["captchaKey"]
    return key, redis_get("captcha:" + key)


def login_ok(username, password=PASSWORD):
    key, code = fresh_captcha()
    _, body = call("POST", "/auth/login", {
        "username": username, "password": password,
        "captchaKey": key, "captchaCode": code,
    })
    if body.get("code") != 200:
        raise RuntimeError("登录 %s 失败：%s" % (username, body))
    return body["data"]


def register(username, phone, password, nickname, role):
    key, code = fresh_captcha()
    return call("POST", "/auth/register", {
        "username": username, "phone": phone, "password": password,
        "nickname": nickname, "role": role, "captchaKey": key, "captchaCode": code,
    })


def cleanup():
    # 测试订单（11001 闸门用的那一单）按 e2e_order 同一手法物理删
    if created_order_ids:
        ids = ",".join(str(i) for i in created_order_ids)
        mysql_value("DELETE FROM `order_status_log` WHERE `order_id` IN (%s);" % ids)
        mysql_value("DELETE FROM `order_reject_log` WHERE `order_id` IN (%s);" % ids)
        mysql_value("DELETE FROM `companion_order` WHERE `id` IN (%s);" % ids)

    # 当轮注册的临时账号（注销只是逻辑删 + 更新，行还在，按基线物理删）
    cleanup_util.delete_since("sys_user", user_id_start, "sys_user")
    cleanup_util.delete_since("sys_login_log", log_id_start, "sys_login_log")
    cleanup_util.delete_since("internal_message", message_id_start, "internal_message")
    cleanup_util.flush_unread_cache()

    # Redis 侧残留：临时账号的密码版本键（无 TTL，不删会永久留着）与登录失败计数
    redis_del("pwd:version:" + str(new_user_id or 0), "login:fail:" + ACC_NEW)
    print("\n[清理] 已删除测试订单 %d 条、临时账号 %s 及日志基线后行，复位 Redis 键"
          % (len(created_order_ids), ACC_NEW))


def main():
    global log_id_start, message_id_start, user_id_start, new_user_id
    print("=" * 78)
    print("银龄伴诊 V4 P0-1 注销与数据导出 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    user_id_start = cleanup_util.max_id("sys_user")
    log_id_start = cleanup_util.max_id("sys_login_log")
    message_id_start = cleanup_util.max_id("internal_message")

    fam_token = login_ok(ACC_FAMILY)["accessToken"]
    elder_token = login_ok(ACC_ELDER)["accessToken"]
    admin_token = login_ok(ACC_ADMIN)["accessToken"]

    # ---------------- A. 数据导出契约 ----------------
    status, headers, raw = call_raw("GET", "/user/data-export", fam_token)
    content_type = headers.get("Content-Type") or ""
    disposition = headers.get("Content-Disposition") or ""
    check("A1 家属导出 → 200 + octet-stream + attachment + xlsx 真实文件（PK 头）",
          status == 200
          and content_type.startswith("application/octet-stream")
          and disposition.startswith("attachment; ")
          and len(raw) > 100 and raw[0:1] == b"P" and raw[1:2] == b"K",
          "http=%s contentType=%s bytes=%d" % (status, content_type, len(raw)))

    status, body = call("GET", "/user/data-export", None, elder_token)
    check("A2 老人导出 → 403（GET 不被只读拦截器拦，@PreAuthorize 显式排除 ELDER）",
          status == 403 and body.get("code") == 403,
          "http=%s code=%s" % (status, body.get("code")))

    status, body = call("GET", "/user/data-export", None, admin_token)
    check("A3 管理员导出 → 403（只能导出本人业务数据，管理员没有）",
          status == 403 and body.get("code") == 403,
          "http=%s code=%s" % (status, body.get("code")))

    status, body = call("GET", "/user/data-export")
    check("A4 未登录导出 → 401", status == 401 and body.get("code") == 401,
          "http=%s code=%s" % (status, body.get("code")))

    # ---------------- B. 注册临时账号 + 注销前三道闸门 ----------------
    _, body = register(ACC_NEW, PHONE_NEW, PASSWORD, "注销测试", "FAMILY")
    new_user_id = (body.get("data") or {}).get("userId")
    check("B1 注册临时家属 → 200（注销全链路只在这个账号上跑）",
          body.get("code") == 200 and bool(new_user_id),
          "code=%s userId=%s" % (body.get("code"), new_user_id))

    new_token = login_ok(ACC_NEW)["accessToken"]

    status, body = call("POST", "/user/deactivate",
                        {"password": PASSWORD, "confirmText": "我已知晓注销后果"})
    check("B2 未登录注销 → 401", status == 401 and body.get("code") == 401,
          "http=%s code=%s" % (status, body.get("code")))

    status, body = call("POST", "/user/deactivate",
                        {"password": PASSWORD, "confirmText": "我已知晓注销后果"}, elder_token)
    check("B3 老人注销 → 403 且提示「只读」（ElderReadOnlyInterceptor）",
          status == 403 and "只读" in (body.get("message") or ""),
          "http=%s message=%s" % (status, body.get("message")))

    status, body = call("POST", "/user/deactivate",
                        {"password": PASSWORD, "confirmText": "我已知晓注销后果"}, admin_token)
    check("B4 管理员注销 → 403（无自助注销入口，走后台封禁另一条路径）",
          status == 403 and body.get("code") == 403,
          "http=%s code=%s" % (status, body.get("code")))

    _, body = call("POST", "/user/deactivate",
                   {"password": PASSWORD, "confirmText": "我同意"}, new_token)
    check("B5 确认文本不符 → 400，提示语给出要原样输入的固定串",
          body.get("code") == 400 and "我已知晓注销后果" in (body.get("message") or ""),
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    _, body = call("POST", "/user/deactivate",
                   {"password": "WrongPass123", "confirmText": "我已知晓注销后果"}, new_token)
    check("B6 密码错误 → 1006（与修改密码同一口径）", body.get("code") == 1006,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ---------------- C. 进行中订单闸门（11001） ----------------
    # 用 fam001 造一单再取消：闸门在写库之前抛，对种子账号零副作用
    visit = (time.strftime("%Y-%m-%d ", time.localtime(time.time() + 2 * 86400)) + "10:00:00")
    status, body = call("POST", "/order", {
        "elderId": ELDER_OWN, "hospital": "海南省人民医院", "department": "心血管内科",
        "visitTime": visit, "address": "海南省海口市秀英区秀华路19号",
    }, fam_token)
    gate_order_id = (body.get("data") or {}).get("orderId")
    if gate_order_id:
        created_order_ids.append(int(gate_order_id))
    check("C1 fam001 下一笔待接单订单 → 200", body.get("code") == 200 and bool(gate_order_id),
          "code=%s orderId=%s" % (body.get("code"), gate_order_id))

    _, body = call("POST", "/user/deactivate",
                   {"password": PASSWORD, "confirmText": "我已知晓注销后果"}, fam_token)
    pending_count = ((body.get("data") or {}).get("pendingOrderCount"))
    check("C2 存在进行中订单 → 11001 且 data.pendingOrderCount ≥ 1",
          body.get("code") == 11001 and isinstance(pending_count, int) and pending_count >= 1,
          "code=%s pendingOrderCount=%s" % (body.get("code"), pending_count))

    row = mysql_value("SELECT CONCAT(`status`,'|',`deleted`) FROM `sys_user` WHERE `id`=101;")
    check("C3 11001 被闸门拦下：fam001 未被注销（NORMAL + 未删除）",
          row == "NORMAL|0", "row=%s" % row)

    _, body = call("PUT", "/order/%s/cancel" % gate_order_id,
                   {"reason": "11001 闸门验证完毕，取消测试订单"}, fam_token)
    check("C4 取消该订单 → 200（fam001 恢复原状）", body.get("code") == 200,
          "code=%s" % body.get("code"))

    # ---------------- D. 注销成功全链路（临时账号） ----------------
    _, body = call("POST", "/user/deactivate",
                   {"password": PASSWORD, "confirmText": "我已知晓注销后果"}, new_token)
    check("D1 临时账号注销 → 200 且 message「账号已注销」",
          body.get("code") == 200 and body.get("message") == "账号已注销",
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/user/profile", None, new_token)
    check("D2 注销后旧令牌再访问 → 401（密码版本 +1 踢全端，不是封禁的 403）",
          status == 401 and body.get("code") == 401,
          "http=%s code=%s" % (status, body.get("code")))

    status, _, _ = call_raw("GET", "/user/data-export", new_token)
    check("D3 注销后旧令牌调导出 → 同为 401", status == 401, "http=%s" % status)

    key, code = fresh_captcha()
    _, body = call("POST", "/auth/login", {
        "username": ACC_NEW, "password": PASSWORD, "captchaKey": key, "captchaCode": code,
    })
    check("D4 注销后重新登录 → 1001（逻辑删后与「账号不存在」同口径，不暴露注销事实）",
          body.get("code") == 1001, "code=%s message=%s" % (body.get("code"), body.get("message")))

    row = mysql_value("SELECT CONCAT(`status`,'|',`deleted`,'|',`phone`) "
                      "FROM `sys_user` WHERE `id`=%s;" % new_user_id)
    check("D5 落库：status=DISABLED + deleted=1 + 手机号墓碑串 d{userId}（uk_phone 不碰撞）",
          row == "DISABLED|1|d%s" % new_user_id, "row=%s" % row)

    row = mysql_value("SELECT CONCAT(COALESCE(`real_name`,'NULL'),'|',`nickname`,'|',`remark`) "
                      "FROM `sys_user` WHERE `id`=%s;" % new_user_id)
    cells = row.split("|", 2)
    check("D6 落库：姓名 / 昵称去标识化（全隐），remark 记录「自主注销」",
          len(cells) == 3
          and cells[0] == "NULL"
          and cells[1].startswith("注") and "*" in cells[1] and "测试" not in cells[1]
          and "自主注销" in cells[2],
          "row=%s" % row)

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
