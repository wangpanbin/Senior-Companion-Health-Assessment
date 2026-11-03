"""银龄伴诊 V4 P2-1 结算闭环 —— 端到端实测（真实 HTTP 打 8080 + 真实库断言）。

用法：
    python e2e_settlement.py
        # 前提：后端已在 8080 启动（$env:SPRING_PROFILES_ACTIVE="dev"），MySQL 与 Redis 可用

覆盖（对应 docs/api/08-admin.md §25–§27、docs/api/09-statistics-export.md §9）：
  A 鉴权：4 个端点未登录 401、非 ADMIN 三类角色 403
  B 台账：只收 COMPLETED / REVIEWED；结算状态筛选；非法筛选值 400；
    时间区间起止颠倒 400；金额两位小数字符串；三个姓名列脱敏
  C 首次核定：200 + settledNow=true；DB 落 SETTLED 且 actual_fee 快照为 fee；
    admin_oper_log 恰好 1 条（UNPAID→SETTLED）
  D 重复核定：200 + settledNow=false；金额不变；日志不增加（不重复留痕）
  E 不可核定 / 不存在：PENDING → 8004 且 DB 不变；不存在 → 3001
  F 批量：去重保序、逐项成败（200 / 8004 / 3001），汇总计数自洽，不额外留痕
  G 导出：xlsx / RFC 5987 文件名 / 12 列表头 / 已结算行口径 / 姓名脱敏 /
    无手机号·身份证明文；非法日期 → JSON 400

数据安全：**只动一笔种子订单**（挑 COMPLETED + UNPAID），main 开头快照
`status / payment_status / actual_fee / finish_time`，__main__ 的 finally 里
原样还原；`admin_oper_log` 按 id 基线删除本轮新增行。断言失败 / 中途异常同样清理。
"""
import html
import io
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

BASE = "http://127.0.0.1:8080/api"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fixture_credentials import PASSWORD as SEED_PASSWORD  # noqa: E402  种子口令单一真源
import cleanup_util  # noqa: E402  统一收尾：日志基线删除

PASSWORD = SEED_PASSWORD
ACC_FAMILY = "fam001"
ACC_COMPANION = "comp001"
ACC_ELDER = "elder001"
ACC_ADMIN = "admin"

FULL_PHONE_RE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
ID_CARD_RE = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")
MONEY_RE = re.compile(r"^\d+\.\d{2}$")
MASKED_NAME_RE = re.compile(r"^.\*+.*$")

EXPORT_HEADERS = ["订单号", "家属姓名", "老人姓名", "陪诊员", "医院", "科室",
                  "就诊时间", "服务完成时间", "服务费(元)", "结算金额(元)",
                  "结算状态", "订单状态"]

ENDPOINTS = [
    ("GET", "/admin/settlement?page=1&size=1", None),
    ("POST", "/admin/settlement/1001/confirm", None),
    ("POST", "/admin/settlement/batch-confirm", {"orderIds": [1001]}),
    ("GET", "/settlement/export", None),
]

results = []
LOG_BASELINE = 0
LOGIN_LOG_BASELINE = 0
SETTLE_ORDER_ID = 0
PENDING_ORDER_ID = 0
ORDER_SNAPSHOT = None

# ======================================================================
# 基础设施（与 e2e_statistics.py 同套路）
# ======================================================================

def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  -> " + detail) if detail else ""))
    sys.stdout.flush()


def url_of(path):
    return BASE + urllib.parse.quote(path, safe="/?&=:%+,[]@!$'()*;")


def call(method, path, body=None, token=None, timeout=60):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url_of(path), data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw[:300]}


def call_file(path, token, timeout=120):
    """下载型接口：返回 (http, headers, 原始字节)。"""
    req = urllib.request.Request(url_of(path), method="GET")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def mysql_run(sql):
    return subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ.get("MYSQL_PASSWORD", ""),
         "--batch", "--skip-column-names", "--raw",
         "--default-character-set=utf8mb4", "--database=" + DB, "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")


def mysql_value(sql):
    return (mysql_run(sql).stdout or "").strip()


def mysql_int(sql):
    try:
        return int(mysql_value(sql) or 0)
    except ValueError:
        return -1


def redis_get(key):
    return subprocess.run([REDIS_CLI, "GET", key], capture_output=True, text=True).stdout.strip()


def login_ok(username, password=PASSWORD):
    _, body = call("GET", "/auth/captcha")
    key = body["data"]["captchaKey"]
    code = redis_get("captcha:" + key)
    _, body = call("POST", "/auth/login", {
        "username": username, "password": password,
        "captchaKey": key, "captchaCode": code})
    if body.get("code") != 200:
        raise RuntimeError("登录 %s 失败：%s" % (username, body))
    return body["data"]["accessToken"]


# ======================================================================
# xlsx 最小解析（与 e2e_statistics.py 同实现：unzip + sharedStrings）
# ======================================================================

CELL_RE = re.compile(r"<c\b([^>]*?)(?:/>|>(.*?)</c>)", re.S)


def parse_xlsx(raw):
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            sx = z.read("xl/sharedStrings.xml").decode("utf-8")
            for si in re.findall(r"<si>(.*?)</si>", sx, re.S):
                shared.append(html.unescape("".join(
                    re.sub(r"<[^>]+>", "", t)
                    for t in re.findall(r"<t[^>]*>(.*?)</t>", si, re.S))))
        sheet = z.read("xl/worksheets/sheet1.xml").decode("utf-8")
    rows = []
    for row_xml in re.findall(r"<row\b[^>]*>(.*?)</row>", sheet, re.S):
        cells = []
        for attrs, inner in CELL_RE.findall(row_xml):
            inner = inner or ""
            kind = re.search(r't="([^"]+)"', attrs)
            kind = kind.group(1) if kind else None
            value = re.search(r"<v>(.*?)</v>", inner, re.S)
            if kind == "s" and value:
                idx = int(value.group(1))
                cells.append(shared[idx] if idx < len(shared) else "")
            elif kind == "inlineStr":
                text = re.search(r"<t[^>]*>(.*?)</t>", inner, re.S)
                cells.append(html.unescape(text.group(1)) if text else "")
            elif value:
                cells.append(html.unescape(value.group(1)))
            else:
                cells.append("")
        rows.append(cells)
    return rows


def try_parse(raw):
    try:
        return parse_xlsx(raw)
    except Exception:  # noqa: BLE001 - 非 xlsx 字节（如 400 的 JSON 错误体）
        return []


# ======================================================================
# 数据快照 / 还原
# ======================================================================

def pick_orders():
    """挑一笔可核定的种子订单 + 一笔不可核定（PENDING）的订单。"""
    global SETTLE_ORDER_ID, PENDING_ORDER_ID, ORDER_SNAPSHOT
    settle_id = mysql_int(
        "SELECT `id` FROM `companion_order` WHERE `deleted`=0 "
        "AND `status` IN ('COMPLETED','REVIEWED') AND `payment_status`='UNPAID' "
        "ORDER BY `id` LIMIT 1;")
    pending_id = mysql_int(
        "SELECT `id` FROM `companion_order` WHERE `deleted`=0 AND `status`='PENDING' "
        "ORDER BY `id` LIMIT 1;")
    if settle_id <= 0 or pending_id <= 0:
        raise RuntimeError("种子数据里找不到 COMPLETED+UNPAID 与 PENDING 订单，无法执行")
    SETTLE_ORDER_ID = settle_id
    PENDING_ORDER_ID = pending_id
    row = mysql_value(
        "SELECT CONCAT_WS('|', `status`, `payment_status`, IFNULL(`actual_fee`,'~'), "
        "`finish_time`, `order_no`, `fee`) FROM `companion_order` WHERE `id`=%d;" % settle_id)
    status, payment, actual_fee, finish_time, order_no, fee = row.split("|", 5)
    ORDER_SNAPSHOT = {
        "status": status, "payment_status": payment, "actual_fee": actual_fee,
        "finish_time": finish_time, "order_no": order_no, "fee": fee,
    }
    print("[准备] 核定样本订单 %d（%s）；不可核定样本 %d"
          % (settle_id, order_no, pending_id))


def restore_order():
    """把样本订单还原为快照（null / 数值都按原样写回）。"""
    if not ORDER_SNAPSHOT:
        return
    actual = "NULL" if ORDER_SNAPSHOT["actual_fee"] == "~" else ORDER_SNAPSHOT["actual_fee"]
    cleanup_util.mysql_raw(
        "UPDATE `companion_order` SET `status`='%s', `payment_status`='%s', "
        "`actual_fee`=%s, `finish_time`='%s' WHERE `id`=%d;"
        % (ORDER_SNAPSHOT["status"], ORDER_SNAPSHOT["payment_status"],
           actual, ORDER_SNAPSHOT["finish_time"], SETTLE_ORDER_ID))
    print("[清理] 订单 %d 已还原为 status=%s / payment_status=%s / actual_fee=%s"
          % (SETTLE_ORDER_ID, ORDER_SNAPSHOT["status"],
             ORDER_SNAPSHOT["payment_status"], actual))


def cleanup():
    restore_order()
    cleanup_util.delete_since("admin_oper_log", LOG_BASELINE, "admin_oper_log（本轮核定留痕）")
    cleanup_util.delete_since("sys_login_log", LOGIN_LOG_BASELINE, "sys_login_log（本轮登录）")


def settle_log_count():
    return mysql_int(
        "SELECT COUNT(*) FROM `admin_oper_log` WHERE `deleted`=0 AND `id`>%d "
        "AND `oper_type`='SETTLE_ORDER' AND `target_type`='ORDER' AND `target_id`=%d;"
        % (LOG_BASELINE, SETTLE_ORDER_ID))


# ======================================================================
# 阶段
# ======================================================================

def section_auth():
    print("\n--- A. 鉴权矩阵（未登录 401 / 非 ADMIN 403）---")
    for method, path, body in ENDPOINTS:
        http, resp = call(method, path, body)
        check("A1 %s %s 未登录 → 401" % (method, path.split("?")[0]),
              http == 401 or resp.get("code") == 401,
              "http=%s code=%s" % (http, resp.get("code")))

    tokens = {
        "FAMILY": login_ok(ACC_FAMILY),
        "COMPANION": login_ok(ACC_COMPANION),
        "ELDER": login_ok(ACC_ELDER),
    }
    for role, token in tokens.items():
        for method, path, body in ENDPOINTS:
            http, resp = call(method, path, body, token)
            check("A2 %s 访问 %s %s → 403" % (role, method, path.split("?")[0]),
                  http == 403 or resp.get("code") == 403,
                  "http=%s code=%s" % (http, resp.get("code")))
    return tokens


def section_list(admin):
    print("\n--- B. 结算核对台账 ---")
    http, body = call("GET", "/admin/settlement?page=1&size=20", None, admin)
    data = body.get("data") or {}
    records = data.get("records") or []
    check("B1 ADMIN 列表 → 200 且返回分页结构（records 数组）",
          body.get("code") == 200 and isinstance(records, list),
          "http=%s code=%s total=%s" % (http, body.get("code"), data.get("total")))
    check("B2 台账只收服务已完成订单（COMPLETED / REVIEWED）",
          bool(records) and all(r.get("status") in ("COMPLETED", "REVIEWED") for r in records),
          "样本=%s" % sorted({r.get("status") for r in records}))
    check("B3 结算状态与标签自洽（UNPAID / SETTLED 且 label 非空）",
          all(r.get("paymentStatus") in ("UNPAID", "SETTLED") and r.get("paymentStatusLabel")
              for r in records),
          "样本=%s" % sorted({r.get("paymentStatus") for r in records}))
    check("B4 金额一律两位小数字符串（前端 / Excel 都不做二次格式化）",
          all(MONEY_RE.match(str(r.get("fee"))) and MONEY_RE.match(str(r.get("settleAmount")))
              for r in records),
          "fee=%s settle=%s" % (records[0].get("fee"), records[0].get("settleAmount")
                                if records else ""))
    names = [n for r in records for n in
             (r.get("elderName"), r.get("familyName"), r.get("companionName")) if n]
    check("B5 三个姓名列一律脱敏（台账不外泄全名）",
          bool(names) and all(MASKED_NAME_RE.match(n) for n in names),
          "样本=%s" % names[:3])

    http, body = call("GET", "/admin/settlement?paymentStatus=UNPAID&size=20", None, admin)
    rec_u = (body.get("data") or {}).get("records") or []
    check("B6 paymentStatus=UNPAID 筛选只返回未结算",
          body.get("code") == 200 and all(r.get("paymentStatus") == "UNPAID" for r in rec_u),
          "条数=%d" % len(rec_u))

    http, body = call("GET", "/admin/settlement?paymentStatus=NOT_A_STATUS&size=1", None, admin)
    check("B7 非法结算状态 → 400（不允许把脏参数翻译成空结果）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    http, body = call("GET", "/admin/settlement?startDate=2026-10-01&endDate=2026-09-01&size=1",
                      None, admin)
    check("B8 完成时间区间起止颠倒 → 400",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    http, body = call("GET", "/admin/settlement?startDate=2026-13-01&size=1", None, admin)
    check("B9 非法日期格式 → 400",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))


def section_confirm(admin):
    print("\n--- C. 首次核定 ---")
    http, body = call("POST", "/admin/settlement/%d/confirm" % SETTLE_ORDER_ID, None, admin)
    data = body.get("data") or {}
    fee = ORDER_SNAPSHOT["fee"]
    check("C1 首次核定 → 200 且 settledNow=true",
          body.get("code") == 200 and data.get("settledNow") is True,
          "http=%s code=%s settledNow=%s" % (http, body.get("code"), data.get("settledNow")))
    check("C2 响应结算金额 = 服务费快照（两位小数字符串）",
          data.get("settleAmount") == "%.2f" % float(fee),
          "settleAmount=%s fee=%s" % (data.get("settleAmount"), fee))
    check("C3 响应结算状态已变为 SETTLED",
          data.get("paymentStatus") == "SETTLED" and data.get("paymentStatusLabel"),
          "paymentStatus=%s" % data.get("paymentStatus"))

    db = mysql_value(
        "SELECT CONCAT(`payment_status`,'|',IFNULL(`actual_fee`,'~')) "
        "FROM `companion_order` WHERE `id`=%d;" % SETTLE_ORDER_ID)
    check("C4 落库：payment_status=SETTLED 且 actual_fee 已快照为服务费",
          db == "SETTLED|%.2f" % float(fee), "db=%s 期望=SETTLED|%.2f" % (db, float(fee)))

    log = mysql_value(
        "SELECT CONCAT(`operator_id`,'|',`before_status`,'|',`after_status`,'|',IFNULL(`remark`,'')) "
        "FROM `admin_oper_log` WHERE `deleted`=0 AND `id`>%d AND `oper_type`='SETTLE_ORDER' "
        "AND `target_id`=%d ORDER BY `id` DESC LIMIT 1;" % (LOG_BASELINE, SETTLE_ORDER_ID))
    admin_id = mysql_int("SELECT `id` FROM `sys_user` WHERE `username`='%s';" % ACC_ADMIN)
    parts = log.split("|", 3)
    check("C5 留痕 1 条：操作人=管理员、UNPAID→SETTLED、备注记录金额",
          len(parts) == 4 and int(parts[0]) == admin_id
          and parts[1] == "UNPAID" and parts[2] == "SETTLED" and "结算核定" in parts[3],
          "log=%s" % log)


def section_repeat(admin):
    print("\n--- D. 重复核定（幂等，不重复留痕）---")
    http, body = call("POST", "/admin/settlement/%d/confirm" % SETTLE_ORDER_ID, None, admin)
    data = body.get("data") or {}
    check("D1 重复核定 → 200 且 settledNow=false（不是 409 / 500）",
          body.get("code") == 200 and data.get("settledNow") is False,
          "http=%s code=%s settledNow=%s" % (http, body.get("code"), data.get("settledNow")))
    check("D2 重复核定金额不变", data.get("settleAmount") == "%.2f" % float(ORDER_SNAPSHOT["fee"]),
          "settleAmount=%s" % data.get("settleAmount"))
    check("D3 重复核定不重复留痕（SETTLE_ORDER 日志仍为 1 条）",
          settle_log_count() == 1, "条数=%d" % settle_log_count())

    before = mysql_value("SELECT CONCAT(`status`,'|',`payment_status`) "
                         "FROM `companion_order` WHERE `id`=%d;" % PENDING_ORDER_ID)
    http, body = call("POST", "/admin/settlement/%d/confirm" % PENDING_ORDER_ID, None, admin)
    after = mysql_value("SELECT CONCAT(`status`,'|',`payment_status`) "
                        "FROM `companion_order` WHERE `id`=%d;" % PENDING_ORDER_ID)
    check("D4 PENDING 订单核定 → 8004 且 DB 不变（未完成不得核定）",
          body.get("code") == 8004 and before == after,
          "code=%s message=%s db=%s→%s" % (body.get("code"), body.get("message"), before, after))

    http, body = call("POST", "/admin/settlement/999999999/confirm", None, admin)
    check("D5 不存在的订单 → 3001", body.get("code") == 3001,
          "code=%s message=%s" % (body.get("code"), body.get("message")))


def section_batch(admin):
    print("\n--- F. 批量核定（去重保序、逐项返回）---")
    payload = {"orderIds": [SETTLE_ORDER_ID, PENDING_ORDER_ID, 999999999, SETTLE_ORDER_ID]}
    http, body = call("POST", "/admin/settlement/batch-confirm", payload, admin)
    data = body.get("data") or {}
    items = data.get("items") or []
    check("F1 批量 → 200，重复 ID 去重后 total=3（保序）",
          body.get("code") == 200 and data.get("total") == 3
          and [i.get("orderId") for i in items] == [SETTLE_ORDER_ID, PENDING_ORDER_ID, 999999999],
          "total=%s items=%s" % (data.get("total"), [i.get("orderId") for i in items]))
    check("F2 汇总口径自洽：total = 新核定 + 已结算 + 失败",
          (data.get("settledCount") or 0) + (data.get("alreadySettledCount") or 0)
          + (data.get("failedCount") or 0) == data.get("total"),
          "settled=%s already=%s failed=%s" % (data.get("settledCount"),
                                               data.get("alreadySettledCount"),
                                               data.get("failedCount")))
    check("F3 逐项结果：已结算项 200/settledNow=false，PENDING 项 8004，不存在项 3001",
          len(items) == 3
          and items[0].get("success") is True and items[0].get("code") == 200
          and items[0].get("settledNow") is False
          and items[1].get("success") is False and items[1].get("code") == 8004
          and items[2].get("success") is False and items[2].get("code") == 3001,
          "codes=%s" % [i.get("code") for i in items])
    check("F4 批量不额外留痕（仍只有首次核定的 1 条）",
          settle_log_count() == 1, "条数=%d" % settle_log_count())

    http, body = call("POST", "/admin/settlement/batch-confirm", {"orderIds": []}, admin)
    check("F5 空列表 → 400（不做「全部核定」兜底）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    http, body = call("POST", "/admin/settlement/batch-confirm",
                      {"orderIds": list(range(1, 102))}, admin)
    check("F6 超过 100 条 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))


def section_export(admin):
    print("\n--- G. 结算单导出 ---")
    http, headers, raw = call_file("/settlement/export", admin)
    ctype = headers.get("Content-Type") or ""
    check("G1 导出 → 200 且 Content-Type 是 xlsx（不是 application/json）",
          http == 200 and ctype.startswith(
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
          "http=%s content-type=%s" % (http, ctype))

    disposition = headers.get("Content-Disposition") or ""
    match = re.search(r"filename\*=UTF-8''([^;]+)", disposition)
    decoded = urllib.parse.unquote(match.group(1)) if match else ""
    check("G2 中文文件名 RFC 5987 下发（结算单_yyyyMMdd_HHmmss.xlsx）",
          bool(match) and re.match(r"^结算单_\d{8}_\d{6}\.xlsx$", decoded),
          "Content-Disposition=%s" % disposition[:110])

    rows = try_parse(raw)
    header_row = rows[0] if rows else []
    check("G3 合法 xlsx 且表头 12 列、顺序与文档一致",
          bool(rows) and raw[:2] == b"PK" and header_row == EXPORT_HEADERS,
          "行数=%d 表头=%s" % (len(rows), header_row))

    idx_no = EXPORT_HEADERS.index("订单号")
    idx_amount = EXPORT_HEADERS.index("结算金额(元)")
    idx_payment = EXPORT_HEADERS.index("结算状态")
    target = [r for r in rows[1:] if len(r) > idx_no and r[idx_no] == ORDER_SNAPSHOT["order_no"]]
    check("G4 已核定订单出现在导出里，结算状态=已结算、金额两位小数",
          bool(target) and target[0][idx_payment] == "已结算"
          and MONEY_RE.match(target[0][idx_amount]),
          "行=%s" % (target[0] if target else None))

    name_cols = [EXPORT_HEADERS.index("家属姓名"), EXPORT_HEADERS.index("老人姓名"),
                 EXPORT_HEADERS.index("陪诊员")]
    names = [r[c] for r in rows[1:] for c in name_cols if len(r) > c and r[c]]
    blob = json.dumps(rows, ensure_ascii=False)
    check("G5 导出姓名一律脱敏（文件流出平台后仍不暴露全名）",
          bool(names) and all("*" in n for n in names), "样本=%s" % names[:3])
    check("G6 导出不含完整手机号 / 身份证号明文",
          not FULL_PHONE_RE.search(blob) and not ID_CARD_RE.search(blob),
          "手机号命中=%s 身份证命中=%s" % (bool(FULL_PHONE_RE.search(blob)),
                                          bool(ID_CARD_RE.search(blob))))

    http, body = call("GET", "/admin/settlement?keyword=%s&size=1"
                      % ORDER_SNAPSHOT["order_no"], None, admin)
    api_total = (body.get("data") or {}).get("total")
    http, _, raw_kw = call_file("/settlement/export?keyword=%s" % ORDER_SNAPSHOT["order_no"], admin)
    kw_rows = try_parse(raw_kw)
    check("G7 导出与列表同源：keyword 命中 1 条，页面 total 与导出行数一致",
          api_total == 1 and len(kw_rows) - 1 == 1,
          "page total=%s 导出行数=%d" % (api_total, max(0, len(kw_rows) - 1)))

    http, _, raw_bad = call_file("/settlement/export?startDate=2026-10-01&endDate=2026-09-01", admin)
    bad = {}
    try:
        bad = json.loads(raw_bad.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        pass
    check("G8 非法日期区间导出 → JSON 400（不是半截 xlsx）",
          bad.get("code") == 400 and raw_bad[:2] != b"PK",
          "code=%s" % bad.get("code"))


def main():
    global LOG_BASELINE, LOGIN_LOG_BASELINE
    LOG_BASELINE = cleanup_util.max_id("admin_oper_log")
    LOGIN_LOG_BASELINE = cleanup_util.max_id("sys_login_log")
    print("[基线] admin_oper_log max_id=%d；sys_login_log max_id=%d"
          % (LOG_BASELINE, LOGIN_LOG_BASELINE))
    pick_orders()

    admin = login_ok(ACC_ADMIN)
    section_auth()
    section_list(admin)
    section_confirm(admin)
    section_repeat(admin)
    section_batch(admin)
    section_export(admin)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001 - 脚本自身异常也算失败，但要先清理
        print("[异常] %s: %s" % (type(e).__name__, e))
    finally:
        try:
            cleanup()
        except Exception as e:  # noqa: BLE001 - 清理失败不能掩盖断言结果
            print("[清理异常] %s: %s" % (type(e).__name__, e))

    passed = sum(1 for _, ok, _ in results if ok)
    failed = len(results) - passed
    print("\n===== 汇总：PASS %d / FAIL %d / 共 %d =====" % (passed, failed, len(results)))
    for name, ok, detail in results:
        if not ok:
            print("  [FAIL] %s  -> %s" % (name, detail))
    sys.exit(0 if failed == 0 and results else 1)
