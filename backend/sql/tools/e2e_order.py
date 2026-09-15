# -*- coding: utf-8 -*-
"""
银龄伴诊 —— M4 陪诊订单 端到端实测脚本

用真实 HTTP 请求打真实后端，逐条验证 docs/api/03-order.md「验收标准（M4）」
以及状态机单向流转、归属校验、乐观锁防超卖、合规红线等硬约束。

与 e2e_auth.py / e2e_user_profile.py 同样的原则：除验证码旁路外全部走正常接口。
本脚本额外需要「造订单」—— 种子里的 PENDING 订单就诊时间都在过去（大厅会过滤掉），
且已被 comp001~comp006 拒过，无法用来验证接单。因此脚本用接口真实下单，
末尾把造出来的订单（含状态日志、拒单记录）全部物理删除。

用法：
    python e2e_order.py      # 前提：后端已在 8080 启动，MySQL 与 Redis 可用
"""
import concurrent.futures
import datetime as dt
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080/api"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"

PASSWORD = "Nl@123456"

ACC_FAMILY = "fam001"        # 家属 101，绑定老人 401
ACC_FAMILY_OTHER = "fam002"  # 家属 102，绑定老人 402
ACC_ELDER = "elder001"       # 老人 201 → 档案 401
ACC_ELDER_OTHER = "elder002"  # 老人 202 → 档案 402
ACC_COMPANION = "comp001"    # 陪诊员 301（已通过审核）
ACC_COMPANION_2 = "comp002"  # 陪诊员 302
ACC_COMPANION_PENDING = "comp025"  # 陪诊员 325（待审核）
ACC_ADMIN = "admin"

ELDER_OWN = 401
ELDER_OTHER = 402
COMPANION_ID = 301
COMPANION_ID_2 = 302

# 并发接单用到的陪诊员（comp001 ~ comp005，均已通过审核）
CONCURRENT_LOGINS = ["comp001", "comp002", "comp003", "comp004", "comp005"]
CONCURRENT_REQUESTS = 50

STAMP = str(int(time.time()))

results = []
created_order_ids = []
log_id_start = 0


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  -> " + detail) if detail else ""))
    sys.stdout.flush()


def url_of(path):
    """URL 编码非 ASCII 字符（中文查询参数会让 urllib 抛 UnicodeEncodeError）"""
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


def raw_body(method, path, body=None, token=None):
    """返回原始响应文本，用于检查「字段是否根本没出现」"""
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url_of(path), data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


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


def future_visit(days_ahead=2, hour=10, weekend=None):
    """算一个未来的就诊时间。

    hour >= 18 触发夜间加价；weekend=True 要求落在周六/周日（周末加价）。
    两个都没命中就是工作日上午 —— 基础价。
    """
    d = dt.datetime.now() + dt.timedelta(days=days_ahead)
    d = d.replace(hour=hour, minute=0, second=0, microsecond=0)
    if weekend is True:
        while d.weekday() < 5:
            d += dt.timedelta(days=1)
    elif weekend is False:
        while d.weekday() >= 5:
            d += dt.timedelta(days=1)
    return d.strftime("%Y-%m-%d %H:%M:%S")


def create_order(token, elder_id=ELDER_OWN, visit=None, hospital="海南省人民医院",
                 department="心血管内科", address="海南省海口市秀英区秀华路19号", fee=None):
    payload = {
        "elderId": elder_id,
        "hospital": hospital,
        "department": department,
        "visitTime": visit or future_visit(),
        "address": address,
    }
    if fee is not None:
        payload["fee"] = fee
    status, body = call("POST", "/order", payload, token)
    oid = (body.get("data") or {}).get("orderId")
    if oid:
        created_order_ids.append(int(oid))
    return status, body


def main():
    global log_id_start

    print("=" * 78)
    print("银龄伴诊 M4 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    log_id_start = int(mysql_value("SELECT COALESCE(MAX(`id`),0) FROM `sys_login_log`;") or 0)

    fam_token = login_ok(ACC_FAMILY)["accessToken"]
    fam2_token = login_ok(ACC_FAMILY_OTHER)["accessToken"]
    elder_token = login_ok(ACC_ELDER)["accessToken"]
    elder2_token = login_ok(ACC_ELDER_OTHER)["accessToken"]
    comp_token = login_ok(ACC_COMPANION)["accessToken"]
    comp2_token = login_ok(ACC_COMPANION_2)["accessToken"]
    comp_pending_token = login_ok(ACC_COMPANION_PENDING)["accessToken"]
    admin_token = login_ok(ACC_ADMIN)["accessToken"]

    # ================= A. 创建订单：订单号、费用规则、参数校验 =================
    visit_weekday = future_visit(days_ahead=2, hour=10, weekend=False)
    status, body = create_order(fam_token, visit=visit_weekday)
    order_main = (body.get("data") or {}).get("orderId")
    check("A1 家属为绑定老人下单 → 200 + 初始状态 PENDING",
          body.get("code") == 200 and (body.get("data") or {}).get("status") == "PENDING"
          and bool(order_main),
          "code=%s data=%s" % (body.get("code"), body.get("data")))
    check("A2 订单号格式为 NL + yyyyMMdd + 6 位当日序列",
          re.fullmatch(r"NL\d{14}", (body.get("data") or {}).get("orderNo") or "") is not None,
          "orderNo=%s" % (body.get("data") or {}).get("orderNo"))
    check("A3 状态中文「待接单」", (body.get("data") or {}).get("statusLabel") == "待接单",
          "label=%s" % (body.get("data") or {}).get("statusLabel"))

    fee = mysql_value("SELECT `fee` FROM `companion_order` WHERE `id`=%s;" % order_main)
    check("A4 工作日白天服务费按规则计为 128.00（不传 fee 时后端自己算）",
          fee == "128.00", "fee=%s visit=%s" % (fee, visit_weekday))

    _, body = create_order(fam_token, visit=future_visit(days_ahead=3, hour=10, weekend=True))
    fee = mysql_value("SELECT `fee` FROM `companion_order` WHERE `id`=%s;"
                      % (body.get("data") or {}).get("orderId"))
    check("A5 周末就诊加价 30 → 158.00", fee == "158.00", "fee=%s" % fee)

    _, body = create_order(fam_token, visit=future_visit(days_ahead=2, hour=20, weekend=False))
    fee = mysql_value("SELECT `fee` FROM `companion_order` WHERE `id`=%s;"
                      % (body.get("data") or {}).get("orderId"))
    check("A6 夜间（20:00）就诊加价 30 → 158.00", fee == "158.00", "fee=%s" % fee)

    status, body = create_order(fam_token, elder_id=ELDER_OWN, visit="2020-01-01 09:00:00")
    check("A7 就诊时间早于当前时间 → 3005", body.get("code") == 3005,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = create_order(fam_token, elder_id=ELDER_OTHER)
    check("A8 给别人的老人下单 → 2006（归属校验）", body.get("code") == 2006, "code=%s" % body.get("code"))

    status, body = create_order(fam_token, elder_id=999999)
    check("A9 给不存在的老人下单 → 2001", body.get("code") == 2001, "code=%s" % body.get("code"))

    # ================= B. 详情与脱敏口径 =================
    status, body = call("GET", "/order/%d" % order_main, None, fam_token)
    data = body.get("data") or {}
    check("B1 下单家属读详情 → 200 + 老人全名 + 地址返回",
          status == 200 and data.get("elderName") == "张德海" and bool(data.get("address")),
          "elderName=%s address=%s" % (data.get("elderName"), data.get("address")))
    check("B2 详情未接单时无陪诊员字段，结算状态为 UNPAID / 未结算",
          "companionId" not in data and "companionName" not in data
          and data.get("paymentStatus") == "UNPAID" and data.get("paymentStatusLabel") == "未结算",
          "keys=%s payment=%s" % (sorted(data.keys()), data.get("paymentStatus")))
    check("B3 响应时间格式为 yyyy-MM-dd HH:mm:ss（不是 ISO 带 T）",
          re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}", data.get("visitTime") or "") is not None
          and "T" not in (data.get("visitTime") or ""),
          "visitTime=%s" % data.get("visitTime"))

    _, raw = raw_body("GET", "/order/%d" % order_main, None, fam_token)
    check("B4 详情不暴露乐观锁字段 version", "version" not in raw, "len=%d" % len(raw))

    status, body = call("GET", "/order/%d" % order_main, None, fam2_token)
    check("B5 另一个家属读别人的订单 → 3004", body.get("code") == 3004, "code=%s" % body.get("code"))

    status, body = call("GET", "/order/%d" % order_main, None, comp_token)
    check("B6 未接单的订单，任何陪诊员都不是相关方 → 3004",
          body.get("code") == 3004, "code=%s" % body.get("code"))

    status, body = call("GET", "/order/%d" % order_main, None, elder_token)
    check("B7 就诊老人本人读订单 → 200", body.get("code") == 200, "code=%s" % body.get("code"))

    status, body = call("GET", "/order/%d" % order_main, None, elder2_token)
    check("B8 别的老人读不是自己就诊的订单 → 3004", body.get("code") == 3004, "code=%s" % body.get("code"))

    status, body = call("GET", "/order/%d" % order_main, None, admin_token)
    check("B9 管理员读任意订单 → 200（纠纷处理需要）", body.get("code") == 200, "code=%s" % body.get("code"))

    status, body = call("GET", "/order/999999", None, fam_token)
    check("B10 订单不存在 → 3001", body.get("code") == 3001, "code=%s" % body.get("code"))

    # 列表：家属只看自己的单，且列表不返回地址 / 备注 / 内部 ID
    status, body = call("GET", "/order", None, fam_token)
    records = (body.get("data") or {}).get("records") or []
    check("B11 我的订单列表 → 200，首条姓名脱敏且不含地址/备注/familyId/elderId",
          body.get("code") == 200 and bool(records)
          and records[0].get("elderName") == "张*海"
          and not any(k in records[0] for k in ("address", "remark", "familyId", "elderId", "version")),
          "elderName=%s keys=%s" % (records[0].get("elderName") if records else None,
                                    sorted(records[0].keys()) if records else None))

    status, body = call("GET", "/order?status=PENDING", None, fam_token)
    recs = (body.get("data") or {}).get("records") or []
    check("B12 状态筛选 status=PENDING 只返回待接单",
          body.get("code") == 200 and bool(recs) and all(r.get("status") == "PENDING" for r in recs),
          "n=%d" % len(recs))

    status, body = call("GET", "/order?status=PENDNG", None, fam_token)
    check("B13 状态筛选取值非法 → 400（不静默返回全量）", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/order/%d/timeline" % order_main, None, fam_token)
    tl = body.get("data") or []
    check("B14 下单后时间线含 1 个节点：PENDING / FAMILY / 操作人姓名快照",
          body.get("code") == 200 and len(tl) == 1 and tl[0].get("status") == "PENDING"
          and tl[0].get("operatorRole") == "FAMILY" and bool(tl[0].get("operatorName")),
          "tl=%s" % tl)

    status, body = call("GET", "/order/%d/timeline" % order_main, None, fam2_token)
    check("B15 另一个家属读时间线 → 3004", body.get("code") == 3004, "code=%s" % body.get("code"))

    # ================= C. 大厅与陪诊员资质 =================
    status, body = call("GET", "/order/hall", None, comp_token)
    halls = (body.get("data") or {}).get("records") or []
    ours = [h for h in halls if h.get("id") == order_main]
    check("C1 已通过的陪诊员看大厅 → 200，能看到刚下的单",
          body.get("code") == 200 and len(ours) == 1, "n=%d" % len(halls))
    check("C2 大厅返回医院地址（陪诊员必须先知道去哪儿）",
          bool(ours) and bool(ours[0].get("address")), "address=%s"
          % (ours[0].get("address") if ours else None))
    check("C3 大厅姓名同样脱敏", bool(ours) and ours[0].get("elderName") == "张*海",
          "elderName=%s" % (ours[0].get("elderName") if ours else None))

    status, body = call("GET", "/order/hall", None, comp_pending_token)
    check("C4 资质待审核的陪诊员看大厅 → 2003（角色对、资质没到）",
          body.get("code") == 2003, "code=%s" % body.get("code"))

    valid_create = {
        "elderId": ELDER_OWN, "hospital": "海南省人民医院", "department": "心血管内科",
        "visitTime": future_visit(), "address": "海南省海口市秀英区秀华路19号",
    }
    for name, method, path, payload, token, expect in [
        ("C5  家属看大厅 → 403", "GET", "/order/hall", None, fam_token, 403),
        ("C6  老人看大厅 → 403", "GET", "/order/hall", None, elder_token, 403),
        # 注意必须给一份合法请求体：参数解析早于 @PreAuthorize，
        # 传空体会先撞上 400，于是这条用例就测不到「角色门槛」了
        ("C7  陪诊员下单 → 403", "POST", "/order", valid_create, comp_token, 403),
        ("C8  管理员下单 → 403", "POST", "/order", valid_create, admin_token, 403),
        ("C9  无令牌读订单详情 → 401", "GET", "/order/%d" % order_main, None, None, 401),
    ]:
        status, body = call(method, path, payload, token)
        check(name, status == expect, "http=%s code=%s" % (status, body.get("code")))

    # ================= D. 状态机：跳级一律 3002 =================
    status, body = call("POST", "/order/%d/start" % order_main, None, comp_token)
    check("D1 对「待接单」直接开始服务 → 3002", body.get("code") == 3002, "code=%s" % body.get("code"))

    status, body = call("POST", "/order/%d/complete" % order_main,
                        {"summary": "直接完成"}, comp_token)
    check("D2 对「待接单」直接完成 → 3002（是 3002 而不是 4003）",
          body.get("code") == 3002, "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= E. 接单（乐观锁）与后续流转 =================
    status, body = call("POST", "/order/%d/accept" % order_main, None, comp_token)
    check("E1 陪诊员接单 → 200 + ACCEPTED + 返回接单时间",
          body.get("code") == 200 and (body.get("data") or {}).get("status") == "ACCEPTED"
          and bool((body.get("data") or {}).get("acceptTime")),
          "data=%s" % body.get("data"))

    row = mysql_value("SELECT CONCAT(`status`,'|',`companion_id`,'|',`version`) "
                      "FROM `companion_order` WHERE `id`=%d;" % order_main)
    check("E2 接单落库：ACCEPTED + companion_id=301 + version 自增到 1",
          row == "ACCEPTED|301|1", "row=%s" % row)

    status, body = call("POST", "/order/%d/accept" % order_main, None, comp2_token)
    check("E3 已被接走的单再被接 → 3002（状态已不是待接单）",
          body.get("code") == 3002, "code=%s" % body.get("code"))

    status, body = call("POST", "/order/%d/start" % order_main, None, comp2_token)
    check("E4 非本单陪诊员开始服务 → 4003（状态对、人不对）",
          body.get("code") == 4003, "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("POST", "/order/%d/start" % order_main, None, comp_token)
    check("E5 本单陪诊员开始服务 → 200 + 服务中",
          body.get("code") == 200 and (body.get("data") or {}).get("status") == "IN_SERVICE",
          "data=%s" % body.get("data"))

    # 合规红线：服务小结含「建议服用 / 剂量」类表述 → 3007，且状态不变
    status, body = call("POST", "/order/%d/complete" % order_main,
                        {"summary": "医生建议服用阿司匹林，每日一次，剂量 100mg"}, comp_token)
    check("E6 服务小结含用药建议 → 3007（合规红线）",
          body.get("code") == 3007, "code=%s message=%s" % (body.get("code"), body.get("message")))
    still = mysql_value("SELECT `status` FROM `companion_order` WHERE `id`=%d;" % order_main)
    check("E7 被拒的完成请求没有改变订单状态（仍是 IN_SERVICE）", still == "IN_SERVICE", "status=%s" % still)

    status, body = call("POST", "/order/%d/complete" % order_main,
                        {"summary": "09:10 到达医院，全程陪同完成心血管内科就诊，已协助缴费与取药，11:50 送老人回家",
                         "photos": ["/uploads/202609/e2e_1.jpg", "/uploads/202609/e2e_2.jpg"],
                         "fee": 128.00}, comp_token)
    check("E8 合规小结 + 照片 + 实收金额 → 200 + 已完成",
          body.get("code") == 200 and (body.get("data") or {}).get("status") == "COMPLETED",
          "data=%s" % body.get("data"))
    check("E9 完成不等于已结算（一期走线下结算，完成时仍是 UNPAID）",
          (body.get("data") or {}).get("paymentStatus") == "UNPAID"
          and (body.get("data") or {}).get("paymentStatusLabel") == "未结算",
          "data=%s" % body.get("data"))

    row = mysql_value("SELECT CONCAT(`status`,'|',`version`,'|',JSON_LENGTH(`service_photos`),'|',`actual_fee`) "
                      "FROM `companion_order` WHERE `id`=%d;" % order_main)
    # version 停在 1 而不是 3：只有「接单」走 updateById（带 @Version），
    # 开始服务 / 完成服务走的是 `WHERE status = 期望状态` 的条件更新，不碰 version。
    # 两种写法都保证并发安全，区别只是接单需要区分「被谁抢先」(3003)。
    check("E10 完成落库：COMPLETED + version=1 + 2 张照片 + 实收 128.00",
          row == "COMPLETED|1|2|128.00", "row=%s" % row)

    status, body = call("GET", "/order/%d" % order_main, None, comp_token)
    data = body.get("data") or {}
    check("E11 接单陪诊员读详情 → 200 + 本单陪诊员全名",
          body.get("code") == 200 and data.get("companionId") == COMPANION_ID
          and bool(data.get("companionName")),
          "companionId=%s companionName=%s" % (data.get("companionId"), data.get("companionName")))

    status, body = call("GET", "/order/%d" % order_main, None, comp2_token)
    check("E12 别的陪诊员读这一单 → 3004", body.get("code") == 3004, "code=%s" % body.get("code"))

    status, body = call("GET", "/order/%d/timeline" % order_main, None, fam_token)
    tl = body.get("data") or []
    check("E13 时间线累积 3 个节点且按时间升序（PENDING → ACCEPTED → IN_SERVICE → COMPLETED）",
          body.get("code") == 200 and [n.get("status") for n in tl] ==
          ["PENDING", "ACCEPTED", "IN_SERVICE", "COMPLETED"],
          "stages=%s" % [n.get("status") for n in tl])

    # ================= F. 拒单：不改状态、不再出现在大厅 =================
    _, body = create_order(fam_token)
    order_reject = (body.get("data") or {}).get("orderId")

    status, body = call("POST", "/order/%d/reject" % order_reject,
                        {"reason": "当天已有其他订单，时间冲突"}, comp2_token)
    check("F1 陪诊员拒单 → 200", body.get("code") == 200, "code=%s" % body.get("code"))

    row = mysql_value("SELECT CONCAT(`status`) FROM `companion_order` WHERE `id`=%s;" % order_reject)
    check("F2 拒单不改变订单状态（仍是 PENDING，等别的陪诊员接）", row == "PENDING", "status=%s" % row)

    rl = mysql_value("SELECT CONCAT(`companion_id`,'|',`reason`) FROM `order_reject_log` "
                     "WHERE `order_id`=%s;" % order_reject)
    check("F3 拒单记录落库", rl.startswith("302|"), "row=%s" % rl)

    slog = mysql_value("SELECT COUNT(*) FROM `order_status_log` "
                       "WHERE `order_id`=%s AND `to_status`='REJECTED';" % order_reject)
    check("F4 拒单不往状态日志里塞记录（状态没变，塞了会把时间线搞乱）", slog == "0", "rows=%s" % slog)

    status, body = call("GET", "/order/hall", None, comp2_token)
    halls = (body.get("data") or {}).get("records") or []
    check("F5 拒过的单不再出现在该陪诊员的大厅里",
          all(h.get("id") != int(order_reject) for h in halls), "n=%d" % len(halls))

    status, body = call("GET", "/order/hall", None, comp_token)
    halls = (body.get("data") or {}).get("records") or []
    check("F6 但对没拒过它的陪诊员依然可见",
          any(h.get("id") == int(order_reject) for h in halls), "n=%d" % len(halls))

    status, body = call("POST", "/order/%d/reject" % order_reject,
                        {"reason": "再拒一次"}, comp2_token)
    check("F7 重复拒单 → 409", body.get("code") == 409, "code=%s message=%s"
          % (body.get("code"), body.get("message")))

    status, body = call("POST", "/order/%d/reject" % order_main,
                        {"reason": "想拒一个已完成的单"}, comp2_token)
    check("F8 对已完成的订单拒单 → 3002", body.get("code") == 3002, "code=%s" % body.get("code"))

    # ================= G. 取消订单 =================
    _, body = create_order(fam_token)
    order_cancel = (body.get("data") or {}).get("orderId")

    status, body = call("PUT", "/order/%s/cancel" % order_cancel,
                        {"reason": "越权取消"}, fam2_token)
    check("G1 另一个家属取消别人的订单 → 3004（且状态不变）", body.get("code") == 3004,
          "code=%s" % body.get("code"))
    row = mysql_value("SELECT `status` FROM `companion_order` WHERE `id`=%s;" % order_cancel)
    check("G2 越权取消未落库", row == "PENDING", "status=%s" % row)

    status, body = call("PUT", "/order/%s/cancel" % order_cancel,
                        {"reason": "老人临时身体不适，改天再去"}, fam_token)
    check("G3 下单家属取消待接单订单 → 200", body.get("code") == 200, "code=%s" % body.get("code"))

    row = mysql_value("SELECT CONCAT(`status`,'|',`cancel_by`,'|',`cancel_time` IS NOT NULL,'|',"
                      "`cancel_reason`) FROM `companion_order` WHERE `id`=%s;" % order_cancel)
    check("G4 取消落库：CANCELLED + cancel_by=101 + 取消时间与原因",
          row == "CANCELLED|101|1|老人临时身体不适，改天再去", "row=%s" % row)

    status, body = call("PUT", "/order/%s/cancel" % order_cancel,
                        {"reason": "再取消一次"}, fam_token)
    check("G5 已取消的订单再取消 → 3006", body.get("code") == 3006, "code=%s" % body.get("code"))

    status, body = call("PUT", "/order/%d/cancel" % order_main,
                        {"reason": "已完成还想取消"}, fam_token)
    check("G6 已接单（乃至已完成）的订单家属取消 → 3006（要走管理员纠纷处理）",
          body.get("code") == 3006, "code=%s" % body.get("code"))

    # ================= H. 并发接单：只有 1 条成功、version 仅 +1 =================
    _, body = create_order(fam_token)
    order_race = (body.get("data") or {}).get("orderId")

    race_tokens = [login_ok(name)["accessToken"] for name in CONCURRENT_LOGINS]
    print("    [并发接单] %d 个请求同时抢订单 %s ..." % (CONCURRENT_REQUESTS, order_race))

    ready = threading.Event()
    lock = threading.Lock()
    outcomes = []

    def grab(i):
        tk = race_tokens[i % len(race_tokens)]
        ready.wait()
        st, bd = call("POST", "/order/%s/accept" % order_race, None, tk)
        with lock:
            outcomes.append(bd.get("code"))

    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENT_REQUESTS) as pool:
        futures = [pool.submit(grab, i) for i in range(CONCURRENT_REQUESTS)]
        time.sleep(0.4)          # 等人齐了再一起放行，尽量制造真并发
        ready.set()
        concurrent.futures.wait(futures)

    ok_count = sum(1 for c in outcomes if c == 200)
    conflict = sum(1 for c in outcomes if c == 3003)
    illegal = sum(1 for c in outcomes if c == 3002)
    check("H1 %d 个并发接单请求中只有 1 条成功" % CONCURRENT_REQUESTS, ok_count == 1,
          "ok=%d 3003=%d 3002=%d other=%d" % (ok_count, conflict, illegal,
                                              len(outcomes) - ok_count - conflict - illegal))
    check("H2 失败的请求都返回「已被接单」(3003) 或「状态不允许」(3002)，没有 500",
          conflict + illegal == CONCURRENT_REQUESTS - 1,
          "3003=%d 3002=%d" % (conflict, illegal))

    row = mysql_value("SELECT CONCAT(`status`,'|',`version`) FROM `companion_order` "
                      "WHERE `id`=%s;" % order_race)
    check("H3 数据库状态为 ACCEPTED 且 version 恰好为 1（乐观锁只让一个人写成功）",
          row == "ACCEPTED|1", "row=%s" % row)

    winner = mysql_value("SELECT `companion_id` FROM `companion_order` WHERE `id`=%s;" % order_race)
    check("H4 接单人是参与并发的陪诊员之一（301~305）",
          winner in ("301", "302", "303", "304", "305"), "winner=%s" % winner)

    acc_logs = mysql_value("SELECT COUNT(*) FROM `order_status_log` WHERE `order_id`=%s "
                           "AND `to_status`='ACCEPTED';" % order_race)
    check("H5 只写入 1 条接单状态日志（失败请求没有留下副作用）", acc_logs == "1", "rows=%s" % acc_logs)

    # ================= I. 老人只读 =================
    for name, method, path, expect in [
        ("I1  老人下单 → 403", "POST", "/order", 403),
        ("I2  老人取消订单 → 403", "PUT", "/order/%d/cancel" % order_main, 403),
        ("I3  老人接单 → 403", "POST", "/order/%d/accept" % order_main, 403),
        ("I4  老人拒单 → 403", "POST", "/order/%d/reject" % order_main, 403),
        ("I5  老人开始服务 → 403", "POST", "/order/%d/start" % order_main, 403),
        ("I6  老人完成服务 → 403", "POST", "/order/%d/complete" % order_main, 403),
    ]:
        status, body = call(method, path, {}, elder_token)
        check(name, status == expect and "只读" in (body.get("message") or ""),
              "http=%s message=%s" % (status, body.get("message")))

    # ================= J. 参数校验 =================
    for name, method, path, payload, token in [
        ("J1  下单未选老人 → 400", "POST", "/order",
         {"hospital": "海南省人民医院", "department": "心血管内科",
          "visitTime": future_visit(), "address": "海口市秀英区"}, fam_token),
        ("J2  取消原因留空 → 400", "PUT", "/order/%s/cancel" % order_cancel,
         {"reason": ""}, fam_token),
        ("J3  拒单原因留空 → 400", "POST", "/order/%s/reject" % order_reject,
         {"reason": ""}, comp_token),
        ("J4  服务照片超过 6 张 → 400", "POST", "/order/%d/complete" % order_main,
         {"photos": ["/uploads/e%d.jpg" % i for i in range(7)]}, comp_token),
        ("J5  服务费为负数 → 400", "POST", "/order",
         {"elderId": ELDER_OWN, "hospital": "海南省人民医院", "department": "心血管内科",
          "visitTime": future_visit(), "address": "海口市秀英区", "fee": -1}, fam_token),
    ]:
        status, body = call(method, path, payload, token)
        check(name, body.get("code") == 400, "code=%s message=%s"
              % (body.get("code"), body.get("message")))

    # ================= 清理测试产物 =================
    if created_order_ids:
        ids = ",".join(str(i) for i in created_order_ids)
        mysql_value("DELETE FROM `order_status_log` WHERE `order_id` IN (%s);" % ids)
        mysql_value("DELETE FROM `order_reject_log` WHERE `order_id` IN (%s);" % ids)
        mysql_value("DELETE FROM `companion_order` WHERE `id` IN (%s);" % ids)
    redis_del("order:seq:" + dt.date.today().strftime("%Y%m%d"))
    if log_id_start:
        mysql_value("DELETE FROM `sys_login_log` WHERE `id` > %d;" % log_id_start)

    left = mysql_value("SELECT COUNT(*) FROM `companion_order` WHERE `id` IN (%s);"
                       % (",".join(str(i) for i in created_order_ids) or "0"))
    print("\n[清理] 已删除 %d 条测试订单（含状态日志与拒单记录），残留 %s 条"
          % (len(created_order_ids), left))

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
