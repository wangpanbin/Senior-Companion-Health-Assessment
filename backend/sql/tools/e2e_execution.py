# -*- coding: utf-8 -*-
"""
银龄伴诊 —— M5 陪诊执行与打卡 端到端实测脚本

用真实 HTTP/WebSocket 请求打真实后端（默认 8080），逐条验证
docs/api/04-companion-execution.md「验收标准（M5）」以及
「六道关」顺序、双写一致性、距离校验、节点不可回退等硬约束。

与 e2e_auth.py / e2e_user_profile.py / e2e_order.py 同样的原则：
除验证码旁路外全部走正常接口；脚本自造订单（种子订单的就诊时间都在过去、
且状态多已定型），末尾把造出来的订单及其全部衍生行物理删除，
并删掉上传到磁盘的物理文件，使数据库与 uploads 回到跑前基线。

用法：
    python e2e_execution.py     # 前提：后端已在 8080 启动，MySQL 与 Redis 可用
"""
import base64
import datetime as dt
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "http://127.0.0.1:8080/api"
WS_HOST = "127.0.0.1"
WS_PORT = 8080
WS_PATH = "/ws/progress"

REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"
UPLOAD_ROOT = r"F:\test\Senior Companion Health Assessment\backend\uploads"

PASSWORD = "Nl@123456"

# 种子数据的最大订单 ID。本脚本只操作「自己造」的订单，跑前先扫一遍
# ID 大于它的残留（上一次跑到一半崩掉、或清理段被中断时留下的），
# 让脚本可以反复执行而不会越跑越脏。
SEED_ORDER_MAX_ID = 1064

ACC_FAMILY = "fam001"        # 家属 101，绑定老人 401
ACC_FAMILY_OTHER = "fam002"  # 家属 102，绑定老人 402
ACC_ELDER = "elder001"       # 老人 201 → 档案 401
ACC_ELDER_OTHER = "elder002"  # 老人 202 → 档案 402
ACC_COMPANION = "comp001"    # 陪诊员 301（已通过审核）
ACC_COMPANION_2 = "comp002"  # 陪诊员 302
ACC_COMPANION_3 = "comp003"  # 陪诊员 303
ACC_ADMIN = "admin"

ELDER_OWN = 401
COMPANION_ID = 301

# 医院坐标（种子数据里 1001 用的那一组），打卡时「在医院」与「在别处」都基于它
HOSPITAL_LNG = "110.311200"
HOSPITAL_LAT = "20.021500"
# 约 12.7 km 之外（海口市区另一侧），必定超出 2000 米阈值
FAR_LNG = "110.399900"
FAR_LAT = "20.099900"

MAX_DISTANCE_METERS = 2000

results = []
created_order_ids = []
log_id_start = 0
sys_file_id_start = 0
companion_score_before = {}


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


def multipart(field, filename, content, content_type="image/png"):
    boundary = "----nianglin" + uuid.uuid4().hex
    body = (("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
             "Content-Type: %s\r\n\r\n" % (boundary, field, filename, content_type)).encode()
            + content + ("\r\n--%s--\r\n" % boundary).encode())
    return boundary, body


def upload(path, content, filename="probe.png", token=None, content_type="image/png"):
    boundary, body = multipart("file", filename, content, content_type)
    req = urllib.request.Request(url_of(path), data=body, method="POST")
    req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
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


def ws_handshake(path, timeout=8, read_push=0):
    """发一次原始 WebSocket 升级请求，返回 (HTTP 状态码, 状态行, 首帧原文)。

    只验「握手能不能过 + 首帧能不能收到」，不做完整帧编解码 ——
    一期该通道的鉴权才是风险点，帧协议本身由 OrderProgressHubTest 单测覆盖。

    <p>关于 {@code Sec-WebSocket-Key}：必须是 <b>base64 编码的 16 字节随机数</b>
    （24 个字符，尾巴带 {@code =}）。早期版本这里写的是 {@code uuid4().hex} —— 32 个
    十六进制字符，看着像随机串，但既不是合法 base64 长度、解码后也不是 16 字节，
    Tomcat 的 {@code UpgradeUtil} 直接判非法并回 <b>400</b>。
    这跟鉴权无关，纯粹是「请求根本没构成一次合法的 WebSocket 升级」。
    所以这个失败的报错长得像服务端 bug，实际是脚本 bug。</p>

    @param read_push 握手成功后额外等待的秒数，用于收服务端下发的首帧（如 CONNECTED）
    """
    key = base64.b64encode(os.urandom(16)).decode()
    req = ("GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
           "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nOrigin: http://127.0.0.1:5173\r\n\r\n"
           % (path, WS_HOST, WS_PORT, key))
    sock = socket.create_connection((WS_HOST, WS_PORT), timeout=timeout)
    try:
        sock.sendall(req.encode())
        raw = sock.recv(4096).decode("utf-8", "replace")
        if read_push > 0:
            time.sleep(read_push)
            try:
                sock.settimeout(2)
                raw += sock.recv(4096).decode("utf-8", "replace")
            except socket.timeout:
                pass
        return int(raw.split(" ")[1]), raw.split("\r\n")[0], raw
    finally:
        sock.close()


def redis_get(key):
    return subprocess.run([REDIS_CLI, "GET", key], capture_output=True, text=True).stdout.strip()


def mysql_value(sql):
    out = subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ.get("MYSQL_PASSWORD", ""),
         "--batch", "--skip-column-names", "--raw",
         "--default-character-set=utf8mb4", "--database=" + DB, "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
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


def create_order(token, elder_id=ELDER_OWN, with_point=True, days_ahead=3, hospital="海南省人民医院"):
    visit = (dt.datetime.now() + dt.timedelta(days=days_ahead)).replace(
        hour=10, minute=0, second=0, microsecond=0)
    # 落到工作日，避免周末加价干扰费用断言
    while visit.weekday() >= 5:
        visit += dt.timedelta(days=1)
    payload = {
        "elderId": elder_id,
        "hospital": hospital,
        "department": "心血管内科",
        "visitTime": visit.strftime("%Y-%m-%d %H:%M:%S"),
        "address": "海南省海口市秀英区秀华路19号 门诊大楼3楼",
    }
    if with_point:
        payload["longitude"] = HOSPITAL_LNG
        payload["latitude"] = HOSPITAL_LAT
    status, body = call("POST", "/order", payload, token)
    oid = (body.get("data") or {}).get("orderId")
    if oid:
        created_order_ids.append(int(oid))
    return status, body


def checkin(token, order_id, node, lng=HOSPITAL_LNG, lat=HOSPITAL_LAT, **extra):
    payload = {"node": node, "longitude": lng, "latitude": lat}
    payload.update(extra)
    return call("POST", "/execution/%s/checkin" % order_id, payload, token)


def rows_of(table, order_id, extra_where=""):
    """某张订单衍生表的行数（int；空结果按 0）"""
    raw = mysql_value("SELECT COUNT(*) FROM `%s` WHERE `order_id`=%s %s;"
                      % (table, order_id, extra_where))
    try:
        return int(raw)
    except ValueError:
        return -1


def main():
    global log_id_start, sys_file_id_start

    print("=" * 78)
    print("银龄伴诊 M5 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    log_id_start = int(mysql_value("SELECT COALESCE(MAX(`id`),0) FROM `sys_login_log`;") or 0)
    sys_file_id_start = int(mysql_value("SELECT COALESCE(MAX(`id`),0) FROM `sys_file`;") or 0)

    # 跑前清扫：上一次中断留下的测试订单
    stale = mysql_value("SELECT GROUP_CONCAT(`id`) FROM `companion_order` WHERE `id`>%d;"
                        % SEED_ORDER_MAX_ID)
    if stale:
        for table in ("order_checkin", "companion_track", "order_status_log",
                      "order_reject_log", "order_review", "complaint"):
            mysql_value("DELETE FROM `%s` WHERE `order_id` IN (%s);" % (table, stale))
        mysql_value("DELETE FROM `companion_order` WHERE `id` IN (%s);" % stale)
        print("[清扫] 删除上次中断残留的测试订单：%s" % stale)

    for uid in (COMPANION_ID, 302, 303):
        companion_score_before[uid] = mysql_value(
            "SELECT `score` FROM `companion_profile` WHERE `user_id`=%d;" % uid)

    fam_token = login_ok(ACC_FAMILY)["accessToken"]
    fam2_token = login_ok(ACC_FAMILY_OTHER)["accessToken"]
    elder_token = login_ok(ACC_ELDER)["accessToken"]
    elder2_token = login_ok(ACC_ELDER_OTHER)["accessToken"]
    comp_token = login_ok(ACC_COMPANION)["accessToken"]
    comp2_token = login_ok(ACC_COMPANION_2)["accessToken"]
    comp3_token = login_ok(ACC_COMPANION_3)["accessToken"]
    admin_token = login_ok(ACC_ADMIN)["accessToken"]

    # ================= A. 下单携带医院坐标 =================
    status, body = create_order(fam_token)
    order_a = (body.get("data") or {}).get("orderId")
    check("A1 家属下单（带医院经纬度）→ 200 + PENDING",
          body.get("code") == 200 and (body.get("data") or {}).get("status") == "PENDING"
          and bool(order_a),
          "code=%s data=%s" % (body.get("code"), body.get("data")))

    point = mysql_value("SELECT CONCAT(`longitude`,'|',`latitude`) FROM `companion_order` "
                        "WHERE `id`=%s;" % order_a)
    check("A2 医院坐标按 DECIMAL(10,6) 原样落库（不再写成 NULL —— 这是距离校验的前提）",
          point == "%s|%s" % (HOSPITAL_LNG, HOSPITAL_LAT), "row=%s" % point)

    # 不传坐标的订单，用于验证「无坐标时跳过距离校验」与「节点不可回退」
    status, body = create_order(fam_token, with_point=False)
    order_no_point = (body.get("data") or {}).get("orderId")
    check("A3 不传坐标下单同样成功（坐标两列可选）",
          body.get("code") == 200 and bool(order_no_point),
          "code=%s orderId=%s" % (body.get("code"), order_no_point))
    check("A4 不传坐标时两列均为 NULL",
          mysql_value("SELECT CONCAT(IFNULL(`longitude`,'NULL'),'|',IFNULL(`latitude`,'NULL')) "
                      "FROM `companion_order` WHERE `id`=%s;" % order_no_point) == "NULL|NULL")

    # 只传经度 → 视为都没传（半对坐标无法参与距离计算）
    visit_payload = {
        "elderId": ELDER_OWN, "hospital": "海南省中医院", "department": "中医科",
        "visitTime": (dt.datetime.now() + dt.timedelta(days=4)).replace(
            hour=10, minute=0, second=0, microsecond=0).strftime("%Y-%m-%d %H:%M:%S"),
        "address": "海南省海口市龙华区和平路2号", "longitude": HOSPITAL_LNG,
    }
    _, body = call("POST", "/order", visit_payload, fam_token)
    order_half = (body.get("data") or {}).get("orderId")
    if order_half:
        created_order_ids.append(int(order_half))
    check("A5 只传经度 → 视为未提供坐标（避免半对坐标在库里变成算不出距离的假定位）",
          body.get("code") == 200
          and mysql_value("SELECT CONCAT(IFNULL(`longitude`,'NULL'),'|',IFNULL(`latitude`,'NULL')) "
                          "FROM `companion_order` WHERE `id`=%s;" % order_half) == "NULL|NULL",
          "orderId=%s point=%s" % (order_half, mysql_value(
              "SELECT CONCAT(IFNULL(`longitude`,'NULL'),'|',IFNULL(`latitude`,'NULL')) "
              "FROM `companion_order` WHERE `id`=%s;" % order_half)))

    _, body = call("POST", "/order", {
        "elderId": ELDER_OWN, "hospital": "海南省人民医院", "department": "心血管内科",
        "visitTime": (dt.datetime.now() + dt.timedelta(days=5)).replace(
            hour=10, minute=0, second=0, microsecond=0).strftime("%Y-%m-%d %H:%M:%S"),
        "address": "海口市秀英区秀华路19号", "longitude": "999.000000", "latitude": HOSPITAL_LAT,
    }, fam_token)
    check("A6 经度 999 超出 ±180 → 400",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= 推进订单 A 到 IN_SERVICE =================
    call("POST", "/order/%s/accept" % order_a, {}, comp_token)
    call("POST", "/order/%s/start" % order_a, {}, comp_token)

    # ================= B. 距离校验与双写 =================
    ck_before = rows_of("order_checkin", order_a)
    tk_before = rows_of("companion_track", order_a)

    status, body = checkin(comp_token, order_a, "DEPART", FAR_LNG, FAR_LAT,
                           address="海口市美兰区某小区门口")
    check("B1 打卡坐标距医院约 12.7 km（阈值 2000 m）→ 返回 4001「打卡无效」",
          body.get("code") == 4001, "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("B2 4001 的提示里带上了实际距离与阈值，用户能自己判断该怎么做",
          "米" in (body.get("message") or "") and str(MAX_DISTANCE_METERS) in (body.get("message") or ""),
          "message=%s" % body.get("message"))
    check("B3 超阈值打卡不落库（order_checkin 与 companion_track 都没有新增）",
          rows_of("order_checkin", order_a) == ck_before and rows_of("companion_track", order_a) == tk_before,
          "checkin %s→%s track %s→%s" % (ck_before, rows_of("order_checkin", order_a),
                                          tk_before, rows_of("companion_track", order_a)))

    status, body = checkin(comp_token, order_a, "DEPART",
                           address="海南省人民医院 门诊大楼",
                           photos=["/uploads/202609/e2e_depart.jpg"], remark="已出发")
    data = body.get("data") or {}
    check("B4 阈值内打卡 → 200 + 打卡成功",
          body.get("code") == 200 and bool(data.get("checkinId")),
          "code=%s data=%s" % (body.get("code"), data))
    check("B5 响应含 distance 且小于阈值",
          isinstance(data.get("distance"), int) and 0 <= data["distance"] < MAX_DISTANCE_METERS,
          "distance=%s" % data.get("distance"))
    check("B6 响应含 nodeLabel 中文「出发」", data.get("nodeLabel") == "出发",
          "nodeLabel=%s" % data.get("nodeLabel"))
    check("B7 isAbnormal 为 false（阈值内不算异常）", data.get("isAbnormal") is False,
          "isAbnormal=%s" % data.get("isAbnormal"))

    check("B8 打卡同时写入 order_checkin 与 companion_track（各 +1 行）",
          rows_of("order_checkin", order_a) == ck_before + 1
          and rows_of("companion_track", order_a) == tk_before + 1,
          "checkin=%s track=%s" % (rows_of("order_checkin", order_a),
                                   rows_of("companion_track", order_a)))
    check("B9 两条记录的 order_id 都是本单",
          mysql_value("SELECT COUNT(*) FROM `order_checkin` WHERE `order_id`=%s;" % order_a)
          == mysql_value("SELECT COUNT(*) FROM `companion_track` WHERE `order_id`=%s;" % order_a))
    check("B10 库里 distance 与接口返回一致（同一份坐标算出来的同一个数）",
          mysql_value("SELECT `distance` FROM `order_checkin` WHERE `order_id`=%s AND `node`='DEPART';"
                      % order_a) == str(data.get("distance")),
          "db=%s api=%s" % (mysql_value("SELECT `distance` FROM `order_checkin` "
                                        "WHERE `order_id`=%s AND `node`='DEPART';" % order_a),
                            data.get("distance")))
    check("B11 打卡照片落库为 JSON 列",
          (mysql_value("SELECT `photos` FROM `order_checkin` WHERE `order_id`=%s AND `node`='DEPART';"
                       % order_a) or "").find("e2e_depart.jpg") >= 0)

    # ================= C. 去重与节点顺序 =================
    status, body = checkin(comp_token, order_a, "DEPART")
    check("C1 同一订单重复打同一节点 → 4002",
          body.get("code") == 4002, "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("C2 重复打卡不产生重复行（唯一约束生效）",
          rows_of("order_checkin", order_a, "AND `node`='DEPART'") == 1,
          "depart_rows=%s" % rows_of("order_checkin", order_a, "AND `node`='DEPART'"))

    status, body = checkin(comp_token, order_a, "ARRIVE")
    check("C3 顺序前进（出发 → 到院）→ 200", body.get("code") == 200,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # 回退用「从未打过的大排序节点之后再打小排序节点」验证：
    # 订单 B 先打「就诊中」(3)，再打「到院」(2) —— 节点本身都没打过，
    # 所以 4002 不会抢先命中，命中 400 的只能是顺序判断
    call("POST", "/order/%s/accept" % order_no_point, {}, comp3_token)
    call("POST", "/order/%s/start" % order_no_point, {}, comp3_token)
    status, body = checkin(comp3_token, order_no_point, "IN_CONSULT")
    check("C4 跳过中间节点直接打「就诊中」→ 200（允许跳过，只是不能回退）",
          body.get("code") == 200, "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = checkin(comp3_token, order_no_point, "ARRIVE")
    check("C5 已到「就诊中」后再打「到院」→ 400（顺序不能回退）",
          body.get("code") == 400 and "回退" in (body.get("message") or ""),
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("C6 被拒的回退打卡没有落库",
          rows_of("order_checkin", order_no_point, "AND `node`='ARRIVE'") == 0)

    status, body = checkin(comp3_token, order_no_point, "NOT_A_NODE")
    check("C7 非法节点名 → 400",
          body.get("code") == 400 and "不合法" in (body.get("message") or ""),
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= D. 归属与角色门槛 =================
    status, body = checkin(comp2_token, order_a, "IN_CONSULT")
    check("D1 非本单陪诊员打卡 → 4003",
          body.get("code") == 4003, "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = checkin(elder_token, order_a, "IN_CONSULT")
    check("D2 老人账号打卡 → 403（老人只读，写操作一律由家属/陪诊员承担）",
          status == 403 and "只读" in (body.get("message") or ""),
          "http=%s message=%s" % (status, body.get("message")))

    status, body = checkin(fam_token, order_a, "IN_CONSULT")
    check("D3 家属调用打卡接口 → 403（打卡只属于陪诊员）",
          status == 403, "http=%s code=%s" % (status, body.get("code")))

    for name, path in [("D4 非相关方读打卡列表 → 3004", "/execution/%s/checkins" % order_a),
                       ("D5 非相关方读轨迹 → 3004", "/execution/%s/track" % order_a),
                       ("D6 非相关方读进度 → 3004", "/execution/%s/progress" % order_a)]:
        status, body = call("GET", path, None, fam2_token)
        check(name, body.get("code") == 3004, "code=%s message=%s"
              % (body.get("code"), body.get("message")))

    status, body = call("GET", "/execution/%s/checkins" % order_a, None, admin_token)
    check("D7 ADMIN 可读任意订单的打卡列表（纠纷处理需要）",
          body.get("code") == 200 and isinstance(body.get("data"), list),
          "code=%s len=%s" % (body.get("code"), len(body.get("data") or [])))

    # ================= E. 列表、轨迹与进度 =================
    status, body = call("GET", "/execution/%s/checkins" % order_a, None, fam_token)
    records = body.get("data") or []
    times = [r.get("checkinTime") for r in records]
    check("E1 打卡列表按 checkin_time 升序",
          times == sorted(times), "times=%s" % times)
    check("E2 打卡列表节点顺序与 node_sort 一致（出发 → 到院）",
          [r.get("node") for r in records] == ["DEPART", "ARRIVE"],
          "nodes=%s" % [r.get("node") for r in records])
    check("E3 列表返回脱敏的打卡人姓名（李*军 这类，不是全名）",
          all((r.get("operatorName") or "").find("*") >= 0 for r in records),
          "names=%s" % [r.get("operatorName") for r in records])
    check("E4 列表回显 photos 与 distance",
          any(r.get("photos") for r in records) and all(r.get("distance") is not None for r in records),
          "photos=%s distances=%s" % ([r.get("photos") for r in records],
                                      [r.get("distance") for r in records]))

    status, body = call("GET", "/execution/%s/track" % order_a, None, fam_token)
    track = body.get("data") or []
    check("E5 轨迹点数量与打卡记录数量一致",
          len(track) == len(records), "track=%d checkin=%d" % (len(track), len(records)))
    check("E6 轨迹点坐标与打卡坐标逐点一致",
          all(t.get("longitude") == r.get("longitude") and t.get("latitude") == r.get("latitude")
              for t, r in zip(track, records)),
          "track=%s" % track)

    status, body = call("GET", "/execution/%s/progress" % order_a, None, fam_token)
    prog = body.get("data") or {}
    check("E7 进度快照：当前节点「到院」、下一节点「就诊中」",
          prog.get("currentNode") == "ARRIVE" and prog.get("nextNode") == "IN_CONSULT",
          "current=%s next=%s" % (prog.get("currentNode"), prog.get("nextNode")))
    check("E8 进度百分比 = 已完成节点数 / 6（2/6 ≈ 33%）",
          prog.get("progressPercent") == 33, "percent=%s" % prog.get("progressPercent"))
    check("E9 已完成节点列表按顺序返回",
          prog.get("finishedNodes") == ["DEPART", "ARRIVE"],
          "finished=%s" % prog.get("finishedNodes"))
    check("E10 进度快照带订单状态中文",
          prog.get("orderStatus") == "IN_SERVICE" and prog.get("orderStatusLabel") == "服务中",
          "status=%s label=%s" % (prog.get("orderStatus"), prog.get("orderStatusLabel")))

    # ================= F. 现场照片上传 =================
    png = (b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\rIHDR" + b"\x00" * 24
           + b"\x00\x00\x00\x00IEND\xaeB`\x82")
    webp = b"RIFF" + b"\x00\x00\x00\x00" + b"WEBPVP8 " + b"\x00" * 16
    pdf = b"%PDF-1.4\n" + b"x" * 40
    jpg = b"\xff\xd8\xff\xe0" + b"\x00" * 32

    for name, content, filename in [("F1 PNG", png, "shot.png"),
                                    ("F2 JPEG", jpg, "shot.jpg"),
                                    ("F3 WebP", webp, "shot.webp"),
                                    ("F4 PDF", pdf, "report.pdf")]:
        status, body = upload("/execution/%s/photo" % order_a, content, filename, comp_token)
        d = body.get("data") or {}
        check("%s 上传 → 200 + 返回 url" % name,
              body.get("code") == 200 and (d.get("url") or "").startswith("/uploads/"),
              "code=%s data=%s" % (body.get("code"), d))

    total_files = int(mysql_value("SELECT COUNT(*) FROM `sys_file` WHERE `id`>%d;" % sys_file_id_start) or 0)
    check("F5 4 次上传各写 1 条 sys_file 记录", total_files == 4, "rows=%d" % total_files)

    status, body = upload("/execution/%s/photo" % order_a, b"this is definitely not an image at all",
                          "fake.png", comp_token)
    check("F6 扩展名与 Content-Type 都伪装成 png，但文件头是文本 → 400（只认 magic bytes）",
          body.get("code") == 400 and "类型" in (body.get("message") or ""),
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = upload("/execution/%s/photo" % order_a, b"\x89PNG\r\n\x1a\n", "tiny.png", comp_token)
    check("F7 不足 12 字节的文件 → 400（无法判定类型，不猜）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = upload("/execution/%s/photo" % order_a, png, "shot.png", comp2_token)
    check("F8 非本单陪诊员上传 → 4003", body.get("code") == 4003,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = upload("/execution/%s/photo" % order_a, png, "shot.png", elder_token)
    check("F9 老人账号上传 → 403（老人只读）", status == 403, "http=%s code=%s" % (status, body.get("code")))

    # ================= G. 订单状态闸门 =================
    _, body = create_order(fam_token)
    order_pending = (body.get("data") or {}).get("orderId")
    status, body = checkin(comp_token, order_pending, "DEPART")
    check("G1 订单还是 PENDING（未接单）时打卡 → 3002，且提示里说明当前状态",
          body.get("code") == 3002 and "待接单" in (body.get("message") or ""),
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("G2 状态闸门先于身份判断：非陪诊员对 PENDING 单打卡同样得到 3002 而不是 4003",
          checkin(comp2_token, order_pending, "DEPART")[1].get("code") == 3002)

    status, body = checkin(comp_token, 99999999, "DEPART")
    check("G3 订单不存在 → 3001", body.get("code") == 3001,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= H. 参数校验 =================
    for name, payload in [
        ("H1 缺 node", {"longitude": HOSPITAL_LNG, "latitude": HOSPITAL_LAT}),
        ("H2 缺经纬度", {"node": "IN_CONSULT"}),
        ("H3 纬度 999 越界", {"node": "IN_CONSULT", "longitude": HOSPITAL_LNG, "latitude": "999"}),
        ("H4 经度非数字", {"node": "IN_CONSULT", "longitude": "abc", "latitude": HOSPITAL_LAT}),
    ]:
        status, body = call("POST", "/execution/%s/checkin" % order_a, payload, comp_token)
        check("%s → 400" % name, body.get("code") == 400,
              "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/execution/notanumber/checkins", None, fam_token)
    check("H5 orderId 非数字 → 400（不是 500）", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= I. 不存在的路径必须回 404 =================
    status, body = call("GET", "/no-such-endpoint-at-all", None, fam_token)
    check("I1 访问不存在的接口 → 404（而不是 500「服务器开小差了」）",
          body.get("code") == 404, "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("GET", "/execution/%s/no-such-sub" % order_a, None, fam_token)
    check("I2 已存在资源下的未知子路径同样 → 404", body.get("code") == 404,
          "code=%s" % body.get("code"))

    # ================= J. WebSocket 握手鉴权 =================
    # 注意用 read_push 收首帧：连接一建立服务端就会推一条 CONNECTED，
    # 「握手 101 但没有首帧」和「握手 101 且有首帧」是两回事，后者才说明
    # 会话真的进了 OrderProgressHub 的按订单分组表。
    code_fam, line, raw_fam = ws_handshake("%s?token=%s&orderId=%s" % (WS_PATH, fam_token, order_a),
                                           read_push=1)
    check("J1 下单家属的连接方订单 → 握手成功（101）", code_fam == 101,
          "status=%s line=%s" % (code_fam, line))
    check("J1b 握手成功后服务端推首帧 CONNECTED 且带回 orderId", "CONNECTED" in raw_fam
          and ("\"orderId\":%s" % order_a) in raw_fam,
          "raw=%s" % raw_fam[-160:].replace("\r\n", " | "))

    code_comp, line, _ = ws_handshake("%s?token=%s&orderId=%s" % (WS_PATH, comp_token, order_a))
    check("J2 本单陪诊员的连接 → 握手成功（101）", code_comp == 101,
          "status=%s line=%s" % (code_comp, line))

    code_other, line, _ = ws_handshake("%s?token=%s&orderId=%s" % (WS_PATH, fam2_token, order_a))
    check("J3 非相关方连接同一订单 → 握手被拒（不是 101）", code_other != 101,
          "status=%s line=%s" % (code_other, line))

    code_bad, line, _ = ws_handshake("%s?token=not-a-real-token&orderId=%s" % (WS_PATH, order_a))
    check("J4 伪造令牌连接 → 握手被拒", code_bad != 101, "status=%s line=%s" % (code_bad, line))

    code_none, line, _ = ws_handshake("%s?orderId=%s" % (WS_PATH, order_a))
    check("J5 不带令牌连接 → 握手被拒", code_none != 101, "status=%s line=%s" % (code_none, line))

    # ================= K. 全流程收口 =================
    for node in ["IN_CONSULT", "TAKE_MEDICINE", "LEAVE", "FINISH"]:
        status, body = checkin(comp_token, order_a, node)
        if body.get("code") != 200:
            check("K-%s 打卡「%s」→ 200" % (node, node), False,
                  "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/execution/%s/progress" % order_a, None, fam_token)
    prog = body.get("data") or {}
    check("K1 六个节点全部打卡后进度 100%",
          prog.get("progressPercent") == 100, "percent=%s" % prog.get("progressPercent"))
    check("K2 当前节点为「完成」且没有下一节点",
          prog.get("currentNode") == "FINISH" and prog.get("nextNode") is None,
          "current=%s next=%s" % (prog.get("currentNode"), prog.get("nextNode")))
    check("K3 已打卡节点共 6 条且不重复",
          rows_of("order_checkin", order_a) == 6 and rows_of("companion_track", order_a) == 6,
          "checkin=%s track=%s" % (rows_of("order_checkin", order_a),
                                   rows_of("companion_track", order_a)))
    check("K4 打卡记录顺序与 checkin_time 升序完全一致（顺序错乱说明写入有问题）",
          [r.get("node") for r in (call("GET", "/execution/%s/checkins" % order_a, None, fam_token)[1]
                                   .get("data") or [])]
          == ["DEPART", "ARRIVE", "IN_CONSULT", "TAKE_MEDICINE", "LEAVE", "FINISH"])

    # 无坐标订单：C 段已由 comp3 接单并打过 IN_CONSULT，这里必须继续用 comp3，
    # 换 comp_token 会先被「你不是该订单的陪诊员」(4003) 拦掉，测不到想测的东西。
    # 同时断言库里的 distance 为 NULL —— 坐标缺失时不是「算出 0 米」，而是「没算」。
    status, body = checkin(comp3_token, order_no_point, "LEAVE")
    check("K5 无坐标订单打卡仍可成功（距离校验跳过，但记 WARN 日志）",
          body.get("code") == 200, "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("K5b 无坐标订单的打卡记录 distance 为 NULL（没算过，不是算成 0）",
          mysql_value("SELECT IFNULL(`distance`,'NULL') FROM `order_checkin` "
                      "WHERE `order_id`=%s AND `node`='LEAVE';" % order_no_point) == "NULL",
          "distance=%s" % mysql_value("SELECT IFNULL(`distance`,'NULL') FROM `order_checkin` "
                                      "WHERE `order_id`=%s AND `node`='LEAVE';" % order_no_point))

    # ================= 清理测试产物 =================
    print("\n" + "-" * 78)
    new_files = mysql_value("SELECT GROUP_CONCAT(IFNULL(`store_path`,'')) FROM `sys_file` "
                           "WHERE `id`>%d;" % sys_file_id_start)
    if new_files:
        for path in new_files.split(","):
            path = path.strip()
            if path:
                try:
                    full = path if os.path.isabs(path) else os.path.join(
                        r"F:\test\Senior Companion Health Assessment\backend", path)
                    if os.path.exists(full):
                        os.remove(full)
                except OSError as e:
                    print("  跳过删除 %s：%s" % (path, e))
    mysql_value("DELETE FROM `sys_file` WHERE `id`>%d;" % sys_file_id_start)

    if created_order_ids:
        ids = ",".join(str(i) for i in created_order_ids)
        for table in ("order_checkin", "companion_track", "order_status_log",
                      "order_reject_log", "order_review", "complaint"):
            mysql_value("DELETE FROM `%s` WHERE `order_id` IN (%s);" % (table, ids))
        mysql_value("DELETE FROM `companion_order` WHERE `id` IN (%s);" % ids)

    redis_del_keys = ["order:seq:" + dt.date.today().strftime("%Y%m%d")]
    subprocess.run([REDIS_CLI, "DEL"] + redis_del_keys, capture_output=True, text=True)

    if log_id_start:
        mysql_value("DELETE FROM `sys_login_log` WHERE `id` > %d;" % log_id_start)
    for uid, score in companion_score_before.items():
        if score:
            mysql_value("UPDATE `companion_profile` SET `score`=%s WHERE `user_id`=%d;" % (score, uid))

    left = mysql_value("SELECT COUNT(*) FROM `companion_order` WHERE `id` IN (%s);"
                       % (",".join(str(i) for i in created_order_ids) or "0"))
    print("[清理] 已删除 %d 条测试订单及其打卡/轨迹/日志，%d 个上传文件；订单残留 %s 条"
          % (len(created_order_ids), total_files, left))

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
