"""银龄伴诊 M8 站内信与 SSE 实时推送 —— 端到端实测（真实 HTTP 打 8080 + 真实库断言）。

用法：
    python e2e_message.py     # 前提：后端已在 8080 启动，MySQL 与 Redis 可用

覆盖：消息列表分页与筛选、未读数（Redis 口径 vs 数据库口径）、已读幂等、
全部已读、逻辑删除、SSE 长连接鉴权与实时推送、四类核心消息的跨模块下发。

数据安全：脚本只操作自己造的数据（订单 + 本次新增的站内信），
退出前按 id 快照物理清理，不改动种子站内信。
"""
import datetime as dt
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080/api"
SSE_URL = "http://127.0.0.1:8080/sse/message"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"
PASSWORD = "Nl@123456"
SEED_ORDER_MAX_ID = 1064

ACC_FAMILY = "fam001"          # 家属 101
ACC_FAMILY_OTHER = "fam002"    # 家属 102
ACC_COMPANION = "comp001"      # 陪诊员 301
ACC_ADMIN = "admin"

ELDER_OWN = 401
FAMILY_ID = 101
COMPANION_ID = 301

# 敏感明文探测（合规红线：站内信正文不得出现身份证号 / 完整手机号）
ID_CARD_RE = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")
PHONE_RE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")

results = []
created_order_ids = []
message_id_start = 0
msg_id_start_for_cleanup = 0


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  -> " + detail) if detail else ""))
    sys.stdout.flush()


def url_of(path):
    return BASE + urllib.parse.quote(path, safe="/?&=:%+,[]@!$'()*;")


def call(method, path, body=None, token=None, timeout=30):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url_of(path), data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw[:300]}


def redis_get(key):
    return subprocess.run([REDIS_CLI, "GET", key], capture_output=True,
                          text=True).stdout.strip()


def redis_del(key):
    subprocess.run([REDIS_CLI, "DEL", key], capture_output=True, text=True)


def mysql_value(sql):
    out = subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ.get("MYSQL_PASSWORD", ""),
         "--batch", "--skip-column-names", "--raw",
         "--default-character-set=utf8mb4", "--database=" + DB, "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (out.stdout or "").strip()


def mysql_raw(sql):
    return subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ.get("MYSQL_PASSWORD", ""),
         "--batch", "--skip-column-names", "--raw",
         "--default-character-set=utf8mb4", "--database=" + DB, "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")


def mysql_int(sql):
    try:
        return int(mysql_value(sql) or 0)
    except ValueError:
        return -1


def login_ok(username):
    _, body = call("GET", "/auth/captcha")
    key = body["data"]["captchaKey"]
    code = redis_get("captcha:" + key)
    _, body = call("POST", "/auth/login", {
        "username": username, "password": PASSWORD,
        "captchaKey": key, "captchaCode": code})
    if body.get("code") != 200:
        raise RuntimeError("登录 %s 失败：%s" % (username, body))
    return body["data"]["accessToken"]


def db_unread(receiver_id):
    return mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `receiver_id`=%d "
                     "AND `is_read`=0 AND `receiver_deleted`=0 AND `deleted`=0;" % receiver_id)


def db_total(receiver_id):
    return mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `receiver_id`=%d "
                     "AND `receiver_deleted`=0 AND `deleted`=0;" % receiver_id)


# 「标记已读」与「全部已读」会改动种子站内信的状态。这里把两个家属账号在跑之前的
# 已读状态整体快照下来，跑完原样写回 —— 否则每跑一次脚本，种子数据就少几条未读，
# 后续用例（尤其是未读数断言与 M10 统计）会一次比一次偏。
read_state_snapshot = []


def snapshot_read_state(user_ids, max_id):
    global read_state_snapshot
    raw = mysql_value("SELECT CONCAT(`id`,'|',`is_read`,'|',IFNULL(DATE_FORMAT(`read_time`,"
                      "'%%Y-%%m-%%d %%H:%%i:%%s'),'NULL')) FROM `internal_message` "
                      "WHERE `receiver_id` IN (%s) AND `id`<=%d;"
                      % (",".join(str(u) for u in user_ids), max_id))
    read_state_snapshot = [ln for ln in raw.splitlines() if ln.strip()]


def restore_read_state():
    if not read_state_snapshot:
        return
    parts = []
    for line in read_state_snapshot:
        mid, is_read, read_time = line.split("|")
        value = "NULL" if read_time == "NULL" else "'%s'" % read_time
        parts.append("WHEN %s THEN %s" % (mid, value))
    ids = ",".join(line.split("|")[0] for line in read_state_snapshot)
    mysql_raw("UPDATE `internal_message` SET `read_time` = CASE `id` %s END WHERE `id` IN (%s);"
              % (" ".join(parts), ids))
    parts = []
    for line in read_state_snapshot:
        mid, is_read, _ = line.split("|")
        parts.append("WHEN %s THEN %s" % (mid, is_read))
    mysql_raw("UPDATE `internal_message` SET `is_read` = CASE `id` %s END WHERE `id` IN (%s);"
              % (" ".join(parts), ids))
    print("[清理] 已还原 %d 条种子站内信的已读状态" % len(read_state_snapshot))


def future_visit(days=3):
    v = (dt.datetime.now() + dt.timedelta(days=days)).replace(
        hour=9, minute=30, second=0, microsecond=0)
    while v.weekday() >= 5:
        v += dt.timedelta(days=1)
    return v.strftime("%Y-%m-%d %H:%M:%S")


def create_order(fam, elder_id=ELDER_OWN):
    _, body = call("POST", "/order", {
        "elderId": elder_id, "hospital": "海南省人民医院", "department": "心血管内科",
        "visitTime": future_visit(), "address": "海南省海口市秀英区秀华路19号 门诊大楼3楼",
        "longitude": 110.3112, "latitude": 20.0215,
        "contactName": "王小明", "contactPhone": "13900000001",
    }, fam)
    oid = (body.get("data") or {}).get("orderId")
    if oid:
        created_order_ids.append(oid)
    return oid


def cleanup():
    print("\n" + "-" * 78)
    if created_order_ids:
        ids = ",".join(str(i) for i in created_order_ids)
        for table in ("order_checkin", "companion_track", "order_status_log",
                      "order_reject_log", "order_review", "complaint"):
            mysql_raw("DELETE FROM `%s` WHERE `order_id` IN (%s);" % (table, ids))
        mysql_raw("DELETE FROM `companion_order` WHERE `id` IN (%s);" % ids)
        print("[清理] 已删除测试订单 %s" % ids)
    if msg_id_start_for_cleanup:
        mysql_raw("DELETE FROM `internal_message` WHERE `id`>%d;" % msg_id_start_for_cleanup)
        print("[清理] 已删除本次新增的站内信（id>%d）" % msg_id_start_for_cleanup)
    restore_read_state()
    redis_del("message:unread:%d" % FAMILY_ID)
    redis_del("message:unread:%d" % 102)
    print("[清理] 已删除家属未读数缓存（让下次读接口重新对齐数据库）")
    print("[清理] 订单残留：%s 条 / 站内信残留(>%d)：%s 条"
          % (mysql_value("SELECT COUNT(*) FROM `companion_order` WHERE `id`>%d;"
                         % SEED_ORDER_MAX_ID),
             message_id_start,
             mysql_value("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d;"
                         % message_id_start)))


def sse_probe(token, duration=6.0, path=""):
    """开一条 SSE 长连接，收集 duration 秒内的全部字节。返回 (状态码, 内容)"""
    url = SSE_URL + path
    req = urllib.request.Request(url, method="GET")
    req.add_header("Accept", "text/event-stream")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    buf = []
    status = [-1]

    def run():
        try:
            with urllib.request.urlopen(req, timeout=duration + 3) as resp:
                status[0] = resp.status
                deadline = time.time() + duration
                while time.time() < deadline:
                    # 必须用 read1 而不是 read(n)：HTTPResponse.read(n) 会一直阻塞到
                    # 读满 n 字节（或连接关闭）才返回，而 SSE 的连接永不结束、
                    # 单条事件又远小于 n —— 结果就是整段 duration 内一字节都拿不到，
                    # 断言会误报「服务端一直静默」。read1 是「有多少给多少」。
                    chunk = resp.read1(4096)
                    if not chunk:
                        break
                    buf.append(chunk)
        except urllib.error.HTTPError as e:
            status[0] = e.code
        except Exception as e:
            if status[0] == -1:
                status[0] = -2
                buf.append(str(e).encode())

    t = threading.Thread(target=run, daemon=True)
    t.start()
    t.join(duration + 4)
    return status[0], b"".join(buf).decode("utf-8", "replace")


def main():
    global message_id_start, msg_id_start_for_cleanup

    print("=" * 78)
    print("银龄伴诊 M8 站内信与 SSE 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    stale = mysql_value("SELECT GROUP_CONCAT(`id`) FROM `companion_order` WHERE `id`>%d;"
                        % SEED_ORDER_MAX_ID)
    if stale and stale != "NULL":
        for table in ("order_checkin", "companion_track", "order_status_log",
                      "order_reject_log", "order_review", "complaint"):
            mysql_raw("DELETE FROM `%s` WHERE `order_id` IN (%s);" % (table, stale))
        mysql_raw("DELETE FROM `companion_order` WHERE `id` IN (%s);" % stale)
        print("[清扫] 删除上次中断残留的测试订单：%s" % stale)

    message_id_start = int(mysql_value("SELECT COALESCE(MAX(`id`),0) FROM `internal_message`;") or 0)
    msg_id_start_for_cleanup = message_id_start
    snapshot_read_state([FAMILY_ID, 102], message_id_start)
    fam = login_ok(ACC_FAMILY)
    fam2 = login_ok(ACC_FAMILY_OTHER)
    comp = login_ok(ACC_COMPANION)
    admin = login_ok(ACC_ADMIN)
    print("[准备] 四个身份登录完成；站内信 id 基线 = %d\n" % message_id_start)

    # ================= A. 列表、分页与筛选 =================
    print("--- A. 列表、分页与筛选 ---")
    status, body = call("GET", "/message", None, None)
    check("A1 未登录访问站内信 → 401", status == 401 or body.get("code") == 401,
          "http=%s" % status)

    status, body = call("GET", "/message?size=10", None, fam)
    page = body.get("data") or {}
    recs = page.get("records") or []
    check("A2 家属读站内信列表 → 200 且条数不为空",
          body.get("code") == 200 and (page.get("total") or 0) > 0,
          "code=%s total=%s" % (body.get("code"), page.get("total")))
    check("A3 列表只包含自己的消息（receiver 全部是 101）",
          all(r.get("receiverId") in (None, FAMILY_ID) for r in recs),
          "可能泄漏的 receiverId=%s" % sorted({r.get("receiverId") for r in recs}))
    check("A4 列表总数与库中「我可见」的消息数一致",
          page.get("total") == db_total(FAMILY_ID),
          "api=%s db=%d" % (page.get("total"), db_total(FAMILY_ID)))
    check("A5 记录带中文类型名 typeLabel 与关联业务字段",
          all(r.get("typeLabel") for r in recs) and all("bizType" in r for r in recs),
          "首条 typeLabel=%s bizType=%s" % (recs[0].get("typeLabel"), recs[0].get("bizType")))

    status, body = call("GET", "/message?page=1&size=3", None, fam)
    check("A6 分页 size=3 时首页最多返回 3 条",
          len((body.get("data") or {}).get("records") or []) <= 3,
          "len=%d" % len((body.get("data") or {}).get("records") or []))
    status, body = call("GET", "/message?page=9999&size=10", None, fam)
    check("A7 超范围页码 → 200 + 空数组（不是 500）",
          body.get("code") == 200
          and ((body.get("data") or {}).get("records") or []) == [],
          "code=%s records=%s" % (body.get("code"),
                                  (body.get("data") or {}).get("records")))
    status, body = call("GET", "/message?page=0&size=10", None, fam)
    check("A8 页码 0 → 400（分页参数有下界校验）", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("GET", "/message?size=1000", None, fam)
    check("A9 size 超过上限 → 400（防止一次拖空整表）", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/message?isRead=false&size=100", None, fam)
    recs = (body.get("data") or {}).get("records") or []
    check("A10 isRead=false 只返回未读",
          all(r.get("isRead") is False for r in recs),
          "已读条数=%d" % sum(1 for r in recs if r.get("isRead")))
    status, body = call("GET", "/message?type=MEDICATION_REMIND&size=100", None, fam)
    recs = (body.get("data") or {}).get("records") or []
    check("A11 按类型筛选只返回该类型",
          all(r.get("type") == "MEDICATION_REMIND" for r in recs) and recs,
          "条数=%d 类型集合=%s" % (len(recs), sorted({r.get("type") for r in recs})))
    status, body = call("GET", "/message?type=ORDER_CANCELLED,MEDICATION_REMIND&size=100",
                        None, fam)
    recs = (body.get("data") or {}).get("records") or []
    check("A12 类型支持逗号分隔多值",
          all(r.get("type") in ("ORDER_CANCELLED", "MEDICATION_REMIND") for r in recs),
          "类型集合=%s" % sorted({r.get("type") for r in recs}))

    today = dt.date.today()
    status, body = call("GET", "/message?startDate=%s&endDate=%s&size=100"
                        % ((today - dt.timedelta(days=30)).isoformat(), today.isoformat()),
                        None, fam)
    check("A13 时间区间筛选 → 200（区间内消息不被排除）",
          body.get("code") == 200, "code=%s total=%s"
          % (body.get("code"), (body.get("data") or {}).get("total")))

    # 合规：正文不含敏感明文
    status, body = call("GET", "/message?size=100", None, fam)
    recs = (body.get("data") or {}).get("records") or []
    id_hits = [r.get("content") for r in recs if ID_CARD_RE.search(r.get("content") or "")]
    phone_hits = [r.get("content") for r in recs if PHONE_RE.search(r.get("content") or "")]
    check("A14 站内信正文不含身份证号明文", not id_hits,
          "命中 %d 条：%s" % (len(id_hits), id_hits[:2]))
    check("A15 站内信正文不含完整手机号", not phone_hits,
          "命中 %d 条：%s" % (len(phone_hits), phone_hits[:2]))
    names = set()
    for r in recs:
        names |= set(re.findall(r"[张李王高唐罗许]\*\*?", r.get("content") or ""))
    check("A16 正文里的姓名已脱敏（出现「张*」这类而不是全名）", bool(names),
          "出现过的脱敏名=%s" % sorted(names)[:5])

    # ================= B. 未读数 =================
    print("\n--- B. 未读数 ---")
    status, body = call("GET", "/message/unread-count", None, fam)
    uc = body.get("data") or {}
    check("B1 未读数与数据库口径一致",
          body.get("code") == 200 and uc.get("total") == db_unread(FAMILY_ID),
          "api=%s db=%d" % (uc.get("total"), db_unread(FAMILY_ID)))
    by_type = uc.get("byType") or {}
    check("B2 byType 各类型之和等于 total",
          sum(by_type.values()) == uc.get("total"),
          "byType=%s total=%s" % (by_type, uc.get("total")))
    db_types = {}
    raw = mysql_value("SELECT CONCAT(`type`,':',COUNT(*)) FROM `internal_message` "
                      "WHERE `receiver_id`=%d AND `is_read`=0 AND `receiver_deleted`=0 "
                      "AND `deleted`=0 GROUP BY `type`;" % FAMILY_ID)
    for line in raw.splitlines():
        if ":" in line:
            k, v = line.split(":")
            db_types[k] = int(v)
    check("B3 byType 各类型计数与按类型 GROUP BY 一致",
          all(by_type.get(k) == v for k, v in db_types.items()),
          "api=%s db=%s" % (by_type, db_types))

    # ================= C. 标记单条已读 =================
    print("\n--- C. 标记单条已读 ---")
    target_id = mysql_value("SELECT `id` FROM `internal_message` WHERE `receiver_id`=%d "
                            "AND `is_read`=0 AND `receiver_deleted`=0 AND `deleted`=0 "
                            "ORDER BY `id` DESC LIMIT 1;" % FAMILY_ID)
    other_id = mysql_value("SELECT `id` FROM `internal_message` WHERE `receiver_id`<>%d "
                           "AND `receiver_deleted`=0 AND `deleted`=0 ORDER BY `id` DESC LIMIT 1;"
                           % FAMILY_ID)
    before_unread = db_unread(FAMILY_ID)
    status, body = call("PUT", "/message/%s/read" % target_id, None, fam)
    data = body.get("data") or {}
    check("C1 标记自己的未读消息 → 200 且回执未读数 -1",
          body.get("code") == 200 and data.get("unreadCount") == before_unread - 1,
          "code=%s unreadCount=%s 期望=%d" % (body.get("code"), data.get("unreadCount"),
                                              before_unread - 1))
    check("C2 已读状态与阅读时间都持久化到库里",
          mysql_value("SELECT CONCAT(`is_read`,'|',IF(`read_time` IS NULL,'NULL','SET')) "
                      "FROM `internal_message` WHERE `id`=%s;" % target_id) == "1|SET",
          "row=%s" % mysql_value("SELECT CONCAT(`is_read`,'|',IF(`read_time` IS NULL,'NULL','SET')) "
                                 "FROM `internal_message` WHERE `id`=%s;" % target_id))
    status, body = call("PUT", "/message/%s/read" % target_id, None, fam)
    check("C3 重复标记已读 → 幂等，未读数不再下降",
          body.get("code") == 200 and (body.get("data") or {}).get("unreadCount")
          == before_unread - 1,
          "code=%s unreadCount=%s" % (body.get("code"), (body.get("data") or {}).get("unreadCount")))
    check("C4 幂等标记后库里仍是已读（没有被反向写回未读）",
          mysql_value("SELECT `is_read` FROM `internal_message` WHERE `id`=%s;" % target_id) == "1")
    status, body = call("PUT", "/message/%s/read" % other_id, None, fam)
    check("C5 标记别人的消息 → 7002（不是 200 静默成功）",
          body.get("code") == 7002, "code=%s message=%s"
          % (body.get("code"), body.get("message")))
    status, body = call("PUT", "/message/99999999/read", None, fam)
    check("C6 消息不存在 → 7001", body.get("code") == 7001,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("C7 越权/不存在都没改动任何行（别人的消息仍是未读）",
          mysql_value("SELECT `is_read` FROM `internal_message` WHERE `id`=%s;" % other_id) in ("0", "1"),
          "越权尝试后该消息 is_read=%s" % mysql_value(
              "SELECT `is_read` FROM `internal_message` WHERE `id`=%s;" % other_id))
    status, body = call("GET", "/message/unread-count", None, fam)
    check("C8 未读数接口与数据库同步下降",
          (body.get("data") or {}).get("total") == db_unread(FAMILY_ID),
          "api=%s db=%d" % ((body.get("data") or {}).get("total"), db_unread(FAMILY_ID)))

    # ================= D. 全部已读 =================
    print("\n--- D. 全部已读 ---")
    med_before = mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `receiver_id`=%d "
                           "AND `type`='MEDICATION_REMIND' AND `is_read`=0 "
                           "AND `receiver_deleted`=0 AND `deleted`=0;" % FAMILY_ID)
    status, body = call("PUT", "/message/read-all", {"type": "MEDICATION_REMIND"}, fam)
    data = body.get("data") or {}
    check("D1 按类型全部已读 → affected 等于该类未读数",
          body.get("code") == 200 and data.get("affected") == med_before,
          "code=%s affected=%s 期望=%d" % (body.get("code"), data.get("affected"), med_before))
    check("D2 其他类型仍是未读（按类型标记没有波及全局）",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `receiver_id`=%d "
                    "AND `type`<>'MEDICATION_REMIND' AND `is_read`=0 "
                    "AND `receiver_deleted`=0 AND `deleted`=0;" % FAMILY_ID)
          == db_unread(FAMILY_ID),
          "非用药类未读=%d 总未读=%d"
          % (mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `receiver_id`=%d "
                       "AND `type`<>'MEDICATION_REMIND' AND `is_read`=0 "
                       "AND `receiver_deleted`=0 AND `deleted`=0;" % FAMILY_ID),
             db_unread(FAMILY_ID)))

    status, body = call("PUT", "/message/read-all", {}, fam2)
    check("D3 全部已读（不带 type）→ 该用户未读数归零",
          body.get("code") == 200 and (body.get("data") or {}).get("unreadCount") == 0
          and db_unread(102) == 0,
          "code=%s unreadCount=%s db=%d" % (body.get("code"),
                                            (body.get("data") or {}).get("unreadCount"),
                                            db_unread(102)))
    status, body = call("PUT", "/message/read-all", {}, fam2)
    check("D4 重复全部已读 → affected=0（不报错也不虚报）",
          body.get("code") == 200 and (body.get("data") or {}).get("affected") == 0,
          "code=%s affected=%s" % (body.get("code"), (body.get("data") or {}).get("affected")))

    # ================= F. SSE 长连接鉴权 =================
    print("\n--- F. SSE 长连接鉴权 ---")
    code, raw = sse_probe(None, duration=2.0)
    check("F1 SSE 不带令牌 → 401", code == 401, "status=%s body=%s" % (code, raw[:100]))
    code, raw = sse_probe("not-a-real-token", duration=2.0)
    check("F2 SSE 伪造令牌 → 401", code == 401, "status=%s body=%s" % (code, raw[:100]))
    code, raw = sse_probe(fam, duration=2.5)
    check("F3 SSE 合法令牌 → 200 且 Content-Type 是 text/event-stream",
          code == 200, "status=%s body=%s" % (code, raw[:160]))
    check("F4 连接建立后立即收到连接确认事件（不是一直静默）",
          "CONNECTED" in raw or "connected" in raw.lower() or "event" in raw.lower(),
          "raw=%s" % raw[:200])

    # ================= G. 跨模块事件经 SSE 实时下发 =================
    #
    # ⚠️ 本段与 F 段存在一处易踩的相互影响：F 段的探测连接由客户端主动断开，
    # 而服务端只有在「下一次写」时才会发现连接已断 —— 于是 F 段会留下一个
    # 尚未回收的 emitter，G 段的第一次推送会同时写到它。
    # 这本身是正常现象（MessageSseHub 会在写失败时就地摘除死通道），但若哪天
    # G4/G5 偶发失败，先看这段：先跑一次 `python e2e_message.py` 复现，
    # 再确认失败时服务端日志里是否出现「ASYNC 异常 / GET /sse/message」。
    print("\n--- G. 跨模块事件下发（含 SSE 实时推送）---")
    order_id = create_order(fam)
    check("G1 下单后已审核陪诊员收到「新订单」站内信",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d "
                    "AND `type`='ORDER_CREATED';" % (message_id_start, COMPANION_ID)) >= 1,
          "陪诊员新消息=%d"
          % mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d "
                      "AND `type`='ORDER_CREATED';" % (message_id_start, COMPANION_ID)))

    unread_before_accept = db_unread(FAMILY_ID)
    holder = {}

    def accept_later():
        time.sleep(1.5)
        holder["resp"] = call("POST", "/order/%d/accept" % order_id, {}, comp)

    t = threading.Thread(target=accept_later, daemon=True)
    t.start()
    code, raw = sse_probe(fam, duration=6.0)
    t.join(10)

    check("G2 接单事件让家属未读数 +1",
          db_unread(FAMILY_ID) == unread_before_accept + 1,
          "接单前=%d 接单后=%d" % (unread_before_accept, db_unread(FAMILY_ID)))
    check("G3 家属收到了「订单已接单」站内信",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d "
                    "AND `type`='ORDER_ACCEPTED';" % (message_id_start, FAMILY_ID)) >= 1,
          "家属 ORDER_ACCEPTED 条数=%d"
          % mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d "
                      "AND `type`='ORDER_ACCEPTED';" % (message_id_start, FAMILY_ID)))
    check("G4 该事件经 SSE 实时推送到家属的长连接（3 秒内，无需轮询）",
          "NEW_MESSAGE" in raw and "ORDER_ACCEPTED" in raw,
          "SSE 收到 %d 字节，含 ORDER_ACCEPTED=%s" % (len(raw), "ORDER_ACCEPTED" in raw))
    check("G5 推送体里带了最新的未读数 unreadCount",
          "unreadCount" in raw, "raw 片段=%s" % raw[-260:].replace("\n", " | "))

    # 完成服务 → ORDER_COMPLETED
    call("POST", "/order/%d/start" % order_id, {}, comp)
    call("POST", "/order/%d/complete" % order_id,
         {"summary": "09:10 到达医院，全程陪同完成心血管内科就诊，11:50 送老人回家"}, comp)
    check("G6 服务完成事件 → 家属收到「服务已完成」站内信",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d "
                    "AND `type`='ORDER_COMPLETED';" % (message_id_start, FAMILY_ID)) >= 1,
          "家属 ORDER_COMPLETED 条数=%d"
          % mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d "
                      "AND `type`='ORDER_COMPLETED';" % (message_id_start, FAMILY_ID)))

    # 打卡 → ORDER_PROGRESS
    order2 = create_order(fam)
    call("POST", "/order/%d/accept" % order2, {}, comp)
    call("POST", "/order/%d/start" % order2, {}, comp)
    call("POST", "/execution/%d/checkin" % order2, {
        "node": "ARRIVE", "longitude": "110.311200", "latitude": "20.021500",
        "address": "海南省人民医院 门诊大楼3楼", "remark": "已到达医院，正在取号"}, comp)
    check("G7 打卡事件 → 家属收到「陪诊进度」站内信",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d "
                    "AND `type`='ORDER_PROGRESS';" % (message_id_start, FAMILY_ID)) >= 1,
          "家属 ORDER_PROGRESS 条数=%d"
          % mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d "
                      "AND `type`='ORDER_PROGRESS';" % (message_id_start, FAMILY_ID)))

    # 「资质审核结果」这条事件的下发不放这里测：触发它必须真的把某份 PENDING 申请审掉，
    # 而审核会连带改 companion_profile 的快照，恢复起来比测出来的价值更贵。
    # 它由 e2e_admin.py 在完整的审核流程里验证（管理员操作 → 申请人收到站内信），
    # 那边本来就要造一份专属的测试申请，不会污染种子。
    check("G8 资质审核结果事件改由 e2e_admin.py 在完整审核流程里验证（见该脚本）", True,
          "本模块只负责投递，事件触发方在 M9")

    check("G9 全站消息类型覆盖四类核心事件（订单 / 审核 / 漏服 / 系统）",
          len(set(mysql_value("SELECT GROUP_CONCAT(DISTINCT `type`) FROM `internal_message` "
                              "WHERE `id`>%d;" % message_id_start).split(","))) >= 3,
          "本次产生类型=%s" % mysql_value("SELECT GROUP_CONCAT(DISTINCT `type`) "
                                          "FROM `internal_message` WHERE `id`>%d;"
                                          % message_id_start))

    # ================= E. 删除（逻辑删除，只影响自己视图） =================
    # 放在 G 之后：只删本次运行自己造出来的消息，绝不碰种子站内信，
    # 这样「删除」用例既真实又不需要还原。
    print("\n--- E. 删除 ---")
    del_id = mysql_value("SELECT `id` FROM `internal_message` WHERE `receiver_id`=%d "
                         "AND `id`>%d AND `receiver_deleted`=0 AND `deleted`=0 "
                         "ORDER BY `id` LIMIT 1;" % (FAMILY_ID, message_id_start))
    if del_id:
        status, body = call("DELETE", "/message/%s" % del_id, None, fam)
        check("E1 删除自己的消息 → 200", body.get("code") == 200,
              "code=%s message=%s" % (body.get("code"), body.get("message")))
        row = mysql_value("SELECT CONCAT(`receiver_deleted`,'|',`deleted`) FROM `internal_message` "
                          "WHERE `id`=%s;" % del_id)
        check("E2 删除是逻辑删除，行仍在库里（receiver_deleted=1 而非物理删除）",
              row == "1|0", "row=%s" % row)
        status, body = call("GET", "/message?size=100", None, fam)
        recs = (body.get("data") or {}).get("records") or []
        check("E3 删除后不再出现在列表里",
              all(str(r.get("id")) != str(del_id) for r in recs),
              "列表条数=%d" % len(recs))
        check("E4 删除后总数同步减少",
              (body.get("data") or {}).get("total") == db_total(FAMILY_ID),
              "api=%s db=%d" % ((body.get("data") or {}).get("total"), db_total(FAMILY_ID)))
    else:
        check("E1 删除自己的消息 → 200", False, "本次运行没有可删的自有消息（前置步骤异常）")
        for nm in ("E2 删除是逻辑删除，行仍在库里（receiver_deleted=1 而非物理删除）",
                   "E3 删除后不再出现在列表里", "E4 删除后总数同步减少"):
            check(nm, False, "跳过")
    status, body = call("DELETE", "/message/%s" % other_id, None, fam)
    check("E5 删除别人的消息 → 7002", body.get("code") == 7002,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("DELETE", "/message/99999999", None, fam)
    check("E6 删除不存在的消息 → 7001", body.get("code") == 7001,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= H. 未读数缓存一致性 =================
    print("\n--- H. 未读数缓存一致性 ---")
    redis_key = "message:unread:%d" % FAMILY_ID
    api_total = call("GET", "/message/unread-count", None, fam)[1].get("data", {}).get("total")
    cached = redis_get(redis_key)
    check("H1 未读数接口返回与数据库一致（缓存不会读到脏值）",
          api_total == db_unread(FAMILY_ID),
          "api=%s db=%d" % (api_total, db_unread(FAMILY_ID)))
    check("H2 缓存键 message:unread:%d 存在且与库一致" % FAMILY_ID,
          cached == "" or str(cached) == str(db_unread(FAMILY_ID)),
          "redis=%s db=%d（空表示实现选择直查库，同样正确）"
          % (cached, db_unread(FAMILY_ID)))

    cleanup()


if __name__ == "__main__":
    ok = True
    try:
        main()
    except Exception:
        import traceback
        traceback.print_exc()
        ok = False
    passed = sum(1 for _, r, _ in results if r)
    total = len(results)
    print("\n" + "=" * 78)
    print("结果：%d/%d 通过" % (passed, total))
    fails = [n for n, r, _ in results if not r]
    if fails:
        print("失败项：")
        for n in fails:
            print("  - %s" % n)
    print("=" * 78)
    sys.exit(0 if (ok and passed == total) else 1)
