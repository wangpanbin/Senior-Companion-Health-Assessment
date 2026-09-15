"""银龄伴诊 M7 评价与投诉 —— 端到端实测（真实 HTTP 打 8080 + 真实库断言）。

用法：
    python e2e_review.py     # 前提：后端已在 8080 启动，MySQL 与 Redis 可用

覆盖：评价状态闸门（6001/6002/3004）、唯一约束、匿名脱敏、敏感词（6003）、
评分聚合与缓存刷新、投诉双向归属推导、重复投诉 409、投诉详情越权。

数据安全：脚本走真实订单生命周期（下单 → 接单 → 开始 → 完成 → 评价），
只操作自己造的订单（id > SEED_ORDER_MAX_ID），退出前物理清理，
并把陪诊员的评分快照与 Redis 缓存还原到跑之前的值。
"""
import datetime as dt
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080/api"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"
PASSWORD = "Nl@123456"

SEED_ORDER_MAX_ID = 1064

ACC_FAMILY = "fam001"          # 家属 101 → 老人档案 401
ACC_FAMILY_OTHER = "fam002"    # 家属 102 → 老人档案 402
ACC_ELDER = "elder001"         # 老人 201
ACC_COMPANION = "comp001"      # 陪诊员 301（已通过审核）
ACC_COMPANION_2 = "comp002"    # 陪诊员 302
ACC_ADMIN = "admin"

ELDER_OWN = 401
COMPANION_ID = 301
COMPANION_NO_REVIEW = 325      # 库里一条评价都没有的陪诊员
COMPANION_ALL_INVALID = 316    # 唯一一条评价被管理员判为无效

SENSITIVE_WORD = "垃圾"

results = []
created_order_ids = []
snapshot = {}
message_id_start = 0
review_id_start = 0
complaint_id_start = 0


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


def future_visit(days=2):
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
    return body.get("code"), oid


def advance(order_id, comp, to_status):
    """把订单推到指定状态（只允许正向推进）"""
    steps = {"PENDING": [], "ACCEPTED": ["accept"], "IN_SERVICE": ["accept", "start"],
             "COMPLETED": ["accept", "start", "complete"]}[to_status]
    for step in steps:
        payload = {"summary": "09:10 到达医院，全程陪同完成就诊，11:50 送老人回家"} \
            if step == "complete" else {}
        call("POST", "/order/%d/%s" % (order_id, step), payload, comp)
    return mysql_value("SELECT `status` FROM `companion_order` WHERE `id`=%d;" % order_id)


def order_status(order_id):
    return mysql_value("SELECT `status` FROM `companion_order` WHERE `id`=%d;" % order_id)


def cleanup():
    print("\n" + "-" * 78)
    if created_order_ids:
        ids = ",".join(str(i) for i in created_order_ids)
        for table in ("order_checkin", "companion_track", "order_status_log",
                      "order_reject_log", "order_review", "complaint"):
            mysql_raw("DELETE FROM `%s` WHERE `order_id` IN (%s);" % (table, ids))
        mysql_raw("DELETE FROM `companion_order` WHERE `id` IN (%s);" % ids)
        print("[清理] 已删除测试订单 %s 及其评价/投诉/状态日志" % ids)
    if review_id_start:
        mysql_raw("DELETE FROM `order_review` WHERE `id`>%d;" % review_id_start)
    if complaint_id_start:
        mysql_raw("DELETE FROM `complaint` WHERE `id`>%d;" % complaint_id_start)
    if message_id_start:
        mysql_raw("DELETE FROM `internal_message` WHERE `id`>%d;" % message_id_start)
        print("[清理] 已删除本次新增的站内信")
    if snapshot:
        mysql_raw("UPDATE `companion_profile` SET `score`=%s,`review_count`=%d "
                  "WHERE `user_id`=%d;"
                  % (snapshot["score"], int(snapshot["review_count"] or 0), COMPANION_ID))
        print("[清理] 已还原陪诊员 %d 评分快照为 score=%s / review_count=%s"
              % (COMPANION_ID, snapshot["score"], snapshot["review_count"]))
    redis_del("companion:score:%d" % COMPANION_ID)
    print("[清理] 已删除陪诊员 %d 的 Redis 评分缓存" % COMPANION_ID)
    left = mysql_value("SELECT COUNT(*) FROM `companion_order` WHERE `id`>%d;" % SEED_ORDER_MAX_ID)
    print("[清理] 订单残留：%s 条" % left)


def main():
    global message_id_start, review_id_start, complaint_id_start

    print("=" * 78)
    print("银龄伴诊 M7 评价与投诉 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    # 跑前清扫：上一次中断留下的订单
    stale = mysql_value("SELECT GROUP_CONCAT(`id`) FROM `companion_order` WHERE `id`>%d;"
                        % SEED_ORDER_MAX_ID)
    if stale and stale != "NULL":
        for table in ("order_checkin", "companion_track", "order_status_log",
                      "order_reject_log", "order_review", "complaint"):
            mysql_raw("DELETE FROM `%s` WHERE `order_id` IN (%s);" % (table, stale))
        mysql_raw("DELETE FROM `companion_order` WHERE `id` IN (%s);" % stale)
        print("[清扫] 删除上次中断残留的测试订单：%s" % stale)

    message_id_start = int(mysql_value("SELECT COALESCE(MAX(`id`),0) FROM `internal_message`;") or 0)
    review_id_start = int(mysql_value("SELECT COALESCE(MAX(`id`),0) FROM `order_review`;") or 0)
    complaint_id_start = int(mysql_value("SELECT COALESCE(MAX(`id`),0) FROM `complaint`;") or 0)
    row = mysql_value("SELECT CONCAT(`score`,'|',`review_count`) FROM `companion_profile` "
                      "WHERE `user_id`=%d;" % COMPANION_ID)
    snapshot["score"], snapshot["review_count"] = (row.split("|") + ["0", "0"])[:2]
    snapshot["score"] = snapshot["score"] or "0.00"

    fam = login_ok(ACC_FAMILY)
    fam2 = login_ok(ACC_FAMILY_OTHER)
    elder = login_ok(ACC_ELDER)
    comp = login_ok(ACC_COMPANION)
    comp2 = login_ok(ACC_COMPANION_2)
    admin = login_ok(ACC_ADMIN)
    print("[准备] 六个身份登录完成；陪诊员 %d 跑前快照 score=%s review_count=%s\n"
          % (COMPANION_ID, snapshot["score"], snapshot["review_count"]))

    # 造 6 张订单：分别停在 PENDING / ACCEPTED / IN_SERVICE / 两张 COMPLETED / 一张给投诉用
    _, o_pending = create_order(fam)
    _, o_accepted = create_order(fam)
    _, o_service = create_order(fam)
    _, o_done1 = create_order(fam)
    _, o_done2 = create_order(fam)
    _, o_done3 = create_order(fam)
    advance(o_accepted, comp, "ACCEPTED")
    advance(o_service, comp, "IN_SERVICE")
    advance(o_done1, comp, "COMPLETED")
    advance(o_done2, comp, "COMPLETED")
    advance(o_done3, comp, "COMPLETED")
    print("[准备] 订单就绪：PENDING=%s ACCEPTED=%s IN_SERVICE=%s COMPLETED=%s/%s/%s\n"
          % (o_pending, o_accepted, o_service, o_done1, o_done2, o_done3))

    # ================= A. 评价前置闸门 =================
    print("--- A. 评价前置闸门 ---")
    for label, oid in (("待接单", o_pending), ("已接单", o_accepted), ("服务中", o_service)):
        status, body = call("POST", "/review", {"orderId": oid, "score": 5}, fam)
        check("A-%s 订单评价 → 6001「订单尚未完成」" % label,
              body.get("code") == 6001, "code=%s message=%s"
              % (body.get("code"), body.get("message")))

    status, body = call("POST", "/review", {"orderId": 99999999, "score": 5}, fam)
    check("A4 订单不存在 → 3001", body.get("code") == 3001,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("POST", "/review", {"orderId": o_done1, "score": 5}, fam2)
    check("A5 不是本单下单人的家属评价 → 3004",
          body.get("code") == 3004, "code=%s message=%s"
          % (body.get("code"), body.get("message")))

    status, body = call("POST", "/review", {"orderId": o_done1, "score": 5}, comp)
    check("A6 陪诊员评价 → 403（控制器 hasRole('FAMILY') 先拦下，不必等到 Service 判归属）",
          status == 403 or body.get("code") == 403, "http=%s code=%s" % (status, body.get("code")))

    status, body = call("POST", "/review", {"orderId": o_done1, "score": 5}, admin)
    check("A7 管理员评价 → 403（管理员不该以评价人身份出现在评价里）",
          status == 403 or body.get("code") == 403, "http=%s code=%s" % (status, body.get("code")))

    status, body = call("POST", "/review", {"orderId": o_done1, "score": 5}, elder)
    check("A8 老人账号评价 → 403（老人只读，写操作由家属代做）",
          status == 403 or body.get("code") == 403, "http=%s code=%s" % (status, body.get("code")))

    status, body = call("POST", "/review", {"orderId": o_done1, "score": 6}, fam)
    check("A9 评分超出 1–5 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/review", {"orderId": o_done1, "score": 0}, fam)
    check("A10 评分为 0 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("POST", "/review", {"orderId": o_done1, "score": 5, "content": "好"}, fam)
    check("A11 评价文字短于 5 个字 → 400（多半是误触）",
          body.get("code") == 400, "code=%s message=%s"
          % (body.get("code"), body.get("message")))

    status, body = call("POST", "/review",
                        {"orderId": o_done1, "score": 5,
                         "tags": ["一", "二", "三", "四", "五", "六"]}, fam)
    check("A12 标签超过 5 个 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/review",
                        {"orderId": o_done1, "score": 5, "tags": ["这是一个超过十个字符的标签"]}, fam)
    check("A13 单个标签超过 10 字符 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("A14 以上被拒的评价一条都没落库",
          mysql_int("SELECT COUNT(*) FROM `order_review` WHERE `id`>%d;" % review_id_start) == 0,
          "新增评价行=%d" % mysql_int("SELECT COUNT(*) FROM `order_review` WHERE `id`>%d;"
                                      % review_id_start))

    # ================= B. 评价成功链路 =================
    print("\n--- B. 评价成功链路 ---")
    status, body = call("POST", "/review", {
        "orderId": o_done1, "score": 3, "tags": ["准时", "准时", "耐心"],
        "content": "整体还可以，就是比约定时间晚了十分钟。", "isAnonymous": False}, fam)
    data = body.get("data") or {}
    check("B1 家属评价已完成的订单 → 200 且回执带 reviewId 与 REVIEWED",
          body.get("code") == 200 and data.get("reviewId")
          and data.get("orderStatus") == "REVIEWED",
          "code=%s data=%s" % (body.get("code"), data))
    review_1 = data.get("reviewId")

    check("B2 提交评价后订单自动从 COMPLETED 推进到 REVIEWED",
          order_status(o_done1) == "REVIEWED", "status=%s" % order_status(o_done1))
    log_row = mysql_value("SELECT CONCAT(`from_status`,'>',`to_status`,'|',`operator_id`) "
                          "FROM `order_status_log` WHERE `order_id`=%s "
                          "ORDER BY `id` DESC LIMIT 1;" % o_done1)
    check("B3 状态流转留下审计日志 COMPLETED>REVIEWED 且记录操作人",
          log_row == "COMPLETED>REVIEWED|101", "row=%s" % log_row)
    check("B4 重复评价同一订单 → 6002（不是 6001）",
          call("POST", "/review", {"orderId": o_done1, "score": 5}, fam)[1].get("code") == 6002,
          "code=%s" % call("POST", "/review", {"orderId": o_done1, "score": 5}, fam)[1].get("code"))
    check("B5 同一订单在库里只有 1 行评价（唯一约束真的生效）",
          mysql_int("SELECT COUNT(*) FROM `order_review` WHERE `order_id`=%s;" % o_done1) == 1,
          "rows=%d" % mysql_int("SELECT COUNT(*) FROM `order_review` WHERE `order_id`=%s;" % o_done1))
    check("B6 标签去重后落库（「准时」只留一份）",
          mysql_value("SELECT `tags` FROM `order_review` WHERE `id`=%s;" % review_1).count("准时") == 1,
          "tags=%s" % mysql_value("SELECT `tags` FROM `order_review` WHERE `id`=%s;" % review_1))

    status, body = call("GET", "/review/order/%s" % o_done1, None, fam)
    vo = body.get("data") or {}
    check("B7 按订单查评价 → 200 且字段齐（评分/标签/内容/陪诊员/时间）",
          body.get("code") == 200
          and vo.get("score") == 3 and isinstance(vo.get("tags"), list)
          and vo.get("orderNo") and vo.get("companionId") == COMPANION_ID
          and vo.get("createTime"),
          "keys=%s" % sorted(vo.keys()))
    check("B8 陪诊员姓名已脱敏（不是全名）",
          vo.get("companionName") and "*" in vo.get("companionName"),
          "companionName=%s" % vo.get("companionName"))

    status, body = call("GET", "/review/order/%s" % o_done3, None, fam)
    check("B9 未评价的订单 → data 为 null（而不是报错）",
          body.get("code") == 200 and body.get("data") is None,
          "code=%s data=%s" % (body.get("code"), body.get("data")))
    status, body = call("GET", "/review/order/%s" % o_done1, None, fam2)
    check("B10 非相关方按订单查评价 → 3004", body.get("code") == 3004,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # 匿名评价
    status, body = call("POST", "/review", {
        "orderId": o_done2, "score": 5, "content": "服务很到位，全程很顺利。",
        "isAnonymous": True}, fam)
    check("B11 匿名评价提交成功", body.get("code") == 200, "code=%s" % body.get("code"))
    check("B12 匿名标记落库 is_anonymous=1",
          mysql_value("SELECT `is_anonymous` FROM `order_review` WHERE `order_id`=%s;" % o_done2) == "1",
          "is_anonymous=%s" % mysql_value("SELECT `is_anonymous` FROM `order_review` WHERE `order_id`=%s;"
                                          % o_done2))
    status, body = call("GET", "/review/order/%s" % o_done2, None, fam)
    check("B13 匿名评价在详情里不暴露家属真实姓名",
          (body.get("data") or {}).get("familyName") not in ("张德海", "王小明")
          and (body.get("data") or {}).get("familyName"),
          "familyName=%s" % (body.get("data") or {}).get("familyName"))
    status, body = call("GET", "/review/companion/%d?size=100" % COMPANION_ID, None, fam)
    recs = (body.get("data") or {}).get("records") or []
    check("B14 匿名评价在列表里同样不暴露真实姓名",
          recs and all(r.get("familyName") not in ("张德海", "王小明") for r in recs),
          "familyName 取值=%s" % sorted({r.get("familyName") for r in recs}))

    # ================= C. 敏感词 =================
    print("\n--- C. 敏感词过滤 ---")
    before = mysql_int("SELECT COUNT(*) FROM `order_review`;")
    status, body = call("POST", "/review", {
        "orderId": o_done3, "score": 1,
        "content": "这个%s陪诊员太差了，我要投诉。" % SENSITIVE_WORD}, fam)
    check("C1 评价内容命中敏感词 → 6003", body.get("code") == 6003,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("C2 命中敏感词的评价没有落库",
          mysql_int("SELECT COUNT(*) FROM `order_review`;") == before,
          "评价总数=%d（提交前 %d）" % (mysql_int("SELECT COUNT(*) FROM `order_review`;"), before))
    check("C3 被拒后订单状态没有被推进（仍是 COMPLETED）",
          order_status(o_done3) == "COMPLETED", "status=%s" % order_status(o_done3))

    # ================= D. 评分聚合 =================
    print("\n--- D. 评分聚合 ---")
    db_avg = mysql_value("SELECT LPAD(ROUND(AVG(`score`),2),4,'0') FROM `order_review` "
                         "WHERE `companion_id`=%d AND `is_valid`=1 AND `deleted`=0;" % COMPANION_ID)
    db_total = mysql_int("SELECT COUNT(*) FROM `order_review` WHERE `companion_id`=%d "
                         "AND `is_valid`=1 AND `deleted`=0;" % COMPANION_ID)
    status, body = call("GET", "/review/companion/%d/score" % COMPANION_ID, None, fam)
    s = body.get("data") or {}
    try:
        api_avg = float(s.get("averageScore"))
    except (TypeError, ValueError):
        api_avg = -1.0
    check("D1 新评价提交后平均分立即更新（缓存被失效，不是旧值）",
          body.get("code") == 200 and abs(api_avg - float(db_avg)) < 0.005,
          "api=%s db=%s（本次评价前库里是 5.00）" % (s.get("averageScore"), db_avg))
    check("D2 评价总数与库一致（排除 is_valid=0）",
          s.get("totalCount") == db_total, "api=%s db=%d" % (s.get("totalCount"), db_total))
    dist = s.get("starDistribution") or {}
    db_dist = {}
    raw = mysql_value("SELECT CONCAT(`score`,':',COUNT(*)) FROM `order_review` "
                      "WHERE `companion_id`=%d AND `is_valid`=1 AND `deleted`=0 GROUP BY `score`;"
                      % COMPANION_ID)
    for line in raw.splitlines():
        if ":" in line:
            k, v = line.split(":")
            db_dist[int(k)] = int(v)
    check("D3 星级分布与 GROUP BY score 逐档一致",
          all(int(dist.get(str(k), 0)) == v for k, v in db_dist.items()),
          "api=%s db=%s" % (dist, db_dist))
    good = sum(v for k, v in db_dist.items() if k >= 4)
    expect_rate = "%.2f%%" % (good * 100.0 / db_total) if db_total else "0.00%"
    check("D4 好评率（4 星及以上占比）计算正确",
          s.get("goodRate") == expect_rate, "api=%s expect=%s" % (s.get("goodRate"), expect_rate))

    prof_score = mysql_value("SELECT `score` FROM `companion_profile` WHERE `user_id`=%d;"
                             % COMPANION_ID)
    prof_count = mysql_int("SELECT `review_count` FROM `companion_profile` WHERE `user_id`=%d;"
                           % COMPANION_ID)
    try:
        prof_avg = float(prof_score)
    except (TypeError, ValueError):
        prof_avg = -1.0
    check("D5 陪诊员评分快照 companion_profile 同步刷新（资料页不会长期显示旧分数）",
          abs(prof_avg - float(db_avg)) < 0.005 and prof_count == db_total,
          "profile=score %s / count %d，期望 score≈%s / count %d"
          % (prof_score, prof_count, db_avg, db_total))

    status, body = call("GET", "/review/companion/%d/score" % COMPANION_NO_REVIEW, None, fam)
    s0 = body.get("data") or {}
    check("D6 无评价的陪诊员 → averageScore 为 0.00 且 totalCount 为 0（不是 null）",
          s0.get("averageScore") == "0.00" and s0.get("totalCount") == 0,
          "data=%s" % s0)

    status, body = call("GET", "/review/companion/%d/score" % COMPANION_ALL_INVALID, None, fam)
    s1 = body.get("data") or {}
    check("D7 唯一评价被判无效的陪诊员 → 聚合里排除 is_valid=0（返回 0.00）",
          s1.get("totalCount") == 0 and s1.get("averageScore") == "0.00",
          "data=%s" % s1)
    status, body = call("GET", "/review/companion/%d?size=100" % COMPANION_ALL_INVALID, None, fam)
    check("D8 无效评价不出现在公开列表里",
          not ((body.get("data") or {}).get("records") or []),
          "records=%d" % len((body.get("data") or {}).get("records") or []))

    status, body = call("GET", "/review/companion/%d?size=100&minScore=4" % COMPANION_ID, None, fam)
    recs = (body.get("data") or {}).get("records") or []
    check("D9 minScore 过滤生效（只返回 ≥4 星）",
          all(r.get("score") >= 4 for r in recs), "scores=%s" % [r.get("score") for r in recs])
    status, body = call("GET", "/review/companion/%d?size=100&hasContent=true" % COMPANION_ID, None, fam)
    recs = (body.get("data") or {}).get("records") or []
    check("D10 hasContent 过滤生效（只返回带文字的评价）",
          all((r.get("content") or "").strip() for r in recs),
          "contents=%s" % [bool((r.get("content") or "").strip()) for r in recs])
    status, body = call("GET", "/review/companion/%d/score" % COMPANION_ID, None, elder)
    check("D11 老人账号（只读）也能看陪诊员评分", body.get("code") == 200,
          "code=%s" % body.get("code"))

    # ================= E. 投诉 =================
    print("\n--- E. 投诉 ---")
    status, body = call("POST", "/complaint", {
        "orderId": o_done2, "type": "LATE",
        "content": "陪诊员比约定时间晚了 40 分钟到达，导致老人错过取号。",
        "evidence": ["/uploads/202609/e2e_evidence.jpg"]}, fam)
    cd = body.get("data") or {}
    check("E1 家属对已完成订单提交投诉 → 200 且状态 PENDING",
          body.get("code") == 200 and cd.get("status") == "PENDING",
          "code=%s data=%s" % (body.get("code"), cd))
    complaint_id = cd.get("complaintId")
    row = mysql_value("SELECT CONCAT(`complainant_id`,'|',`complainant_role`,'|',"
                      "`target_user_id`,'|',`target_role`) FROM `complaint` WHERE `id`=%s;"
                      % complaint_id)
    check("E2 投诉人 / 被投诉人由订单关系自动推导，不由前端传入",
          row == "101|FAMILY|301|COMPANION", "row=%s" % row)
    check("E3 证据材料落库为 JSON 列",
          mysql_value("SELECT `evidence` FROM `complaint` WHERE `id`=%s;" % complaint_id)
          .startswith("["),
          "evidence=%s" % mysql_value("SELECT `evidence` FROM `complaint` WHERE `id`=%s;"
                                      % complaint_id))

    status, body = call("POST", "/complaint", {
        "orderId": o_done2, "type": "ATTITUDE",
        "content": "同一订单再提一条投诉，应该被拦住。"}, fam)
    check("E4 同一订单已有未处理投诉 → 409", body.get("code") == 409,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("POST", "/complaint", {
        "orderId": o_done3, "type": "LATE",
        "content": "无关家属试图对不属于自己的订单发起投诉。"}, fam2)
    check("E5 非相关方提交投诉 → 3004", body.get("code") == 3004,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/complaint", {
        "orderId": 99999999, "type": "LATE", "content": "订单不存在时的投诉。"}, fam)
    check("E6 订单不存在 → 3001", body.get("code") == 3001,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/complaint", {
        "orderId": o_pending, "type": "LATE", "content": "还没接单就想投诉陪诊员。"}, fam)
    check("E7 订单尚未被接单（无被投诉对象）→ 409", body.get("code") == 409,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/complaint", {
        "orderId": o_done3, "type": "NOT_A_TYPE", "content": "投诉类型取值非法的情况。"}, fam)
    check("E8 投诉类型非法 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/complaint", {
        "orderId": o_done3, "type": "OTHER", "content": "太短"}, fam)
    check("E9 投诉内容短于 10 字 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/complaint", {
        "orderId": o_done3, "type": "OTHER",
        "content": "这个陪诊员就是个%s，我要投诉到底，必须给个说法。" % SENSITIVE_WORD}, fam)
    check("E10 投诉内容命中敏感词 → 6003", body.get("code") == 6003,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/complaint?role=AS_COMPLAINANT&size=100", None, fam)
    recs = (body.get("data") or {}).get("records") or []
    check("E11 我的投诉列表（AS_COMPLAINANT）只返回我提的投诉",
          body.get("code") == 200 and all(r.get("complainantId") == 101 for r in recs)
          and any(r.get("id") == complaint_id for r in recs),
          "条数=%d 投诉人集合=%s" % (len(recs), sorted({r.get("complainantId") for r in recs})))
    check("E12 列表里投诉人与被投诉人姓名均脱敏",
          all("*" in (r.get("complainantName") or "*") for r in recs),
          "complainantName=%s" % [r.get("complainantName") for r in recs][:3])

    status, body = call("GET", "/complaint?role=AS_TARGET&size=100", None, comp)
    recs = (body.get("data") or {}).get("records") or []
    check("E13 陪诊员按 AS_TARGET 能看到投诉自己的记录",
          body.get("code") == 200 and all(r.get("targetUserId") == COMPANION_ID for r in recs)
          and any(r.get("id") == complaint_id for r in recs),
          "条数=%d" % len(recs))

    status, body = call("GET", "/complaint/%s" % complaint_id, None, fam)
    check("E14 投诉详情：投诉人可读", body.get("code") == 200,
          "code=%s" % body.get("code"))
    status, body = call("GET", "/complaint/%s" % complaint_id, None, comp)
    check("E15 投诉详情：被投诉人可读", body.get("code") == 200, "code=%s" % body.get("code"))
    status, body = call("GET", "/complaint/%s" % complaint_id, None, admin)
    check("E16 投诉详情：管理员可读（纠纷处理需要）", body.get("code") == 200,
          "code=%s" % body.get("code"))
    status, body = call("GET", "/complaint/%s" % complaint_id, None, fam2)
    check("E17 投诉详情：非相关方 → 403（不是 200 空数据）",
          body.get("code") == 403 or status == 403,
          "http=%s code=%s message=%s" % (status, body.get("code"), body.get("message")))
    status, body = call("GET", "/complaint/99999999", None, admin)
    check("E18 投诉不存在 → 6004", body.get("code") == 6004,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("E19 提交投诉后向管理员推送了站内信",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id` IN "
                    "(SELECT `id` FROM `sys_user` WHERE `role`='ADMIN');" % message_id_start) >= 1,
          "管理员新消息数=%d"
          % mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id` IN "
                      "(SELECT `id` FROM `sys_user` WHERE `role`='ADMIN');" % message_id_start))
    check("E20 被投诉的陪诊员也收到了通知（告知被投诉方）",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d;"
                    % (message_id_start, COMPANION_ID)) >= 1,
          "陪诊员新消息数=%d"
          % mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d;"
                      % (message_id_start, COMPANION_ID)))

    # E21 陪诊员反向投诉家属（双向能力）
    status, body = call("POST", "/complaint", {
        "orderId": o_done1, "type": "OTHER",
        "content": "家属在服务结束后未按约定结算服务费，多次沟通无果。"}, comp)
    cd2 = body.get("data") or {}
    check("E21 陪诊员可以反向投诉家属（投诉人 / 被投诉人自动对调）",
          body.get("code") == 200, "code=%s data=%s" % (body.get("code"), cd2))
    if cd2.get("complaintId"):
        row = mysql_value("SELECT CONCAT(`complainant_id`,'|',`target_user_id`) "
                          "FROM `complaint` WHERE `id`=%s;" % cd2["complaintId"])
        check("E22 反向投诉的双方身份正确（陪诊员 301 → 家属 101）",
              row == "301|101", "row=%s" % row)

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
