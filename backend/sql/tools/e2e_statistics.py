"""银龄伴诊 M10 数据统计与导出 —— 端到端实测（真实 HTTP 打 8080 + 真实库断言）。

用法：
    python e2e_statistics.py            # 前提：后端已在 8080 启动，MySQL 与 Redis 可用
    python e2e_statistics.py --limit-phase
        # 额外验证「导出行数上限 → 9001」这条分支。
        # 需要后端**以 --nianglin.export.max-rows=N（N 很小，如 2）启动**，
        # 否则 1 万行上限在这份种子数据上永远触发不到。

覆盖（对应 docs/api/09-statistics-export.md）：
  A 整类 ADMIN 鉴权边界（7 个接口，未登录 401 / 三类角色 403）
  B 总览口径（订单 / 用户 / 陪诊员 / 用药四段，逐项与 SQL 对拍；区间默认值、
    区间上限 366 天、起止颠倒、非法日期、空区间不报错）
  C 订单趋势（补 0、长度对齐、三条序列求和回总览、DAY/WEEK/MONTH 分桶与标签、
    区间外数据不混入）
  D 状态分布（6 种状态齐全、count 对拍、percent 求和 100%、空区间全 0）
  E 陪诊员排行（只含已通过审核者、orderCount 与 SQL 对拍、降序、limit 边界、
    metric=SCORE、姓名脱敏）
  F 漏服率（was_missed 口径、summary 与序列自洽、elderId 过滤）
  G 导出（Content-Type / RFC 5987 中文文件名 / xlsx 可解析 / 列定义与顺序 /
    行数与页面筛选一致 / **不含密码·身份证·完整手机号** / 姓名脱敏）

数据安全：**本脚本只读**，不插入、不更新、不删除任何数据，因此没有清理动作。
所有断言都对着种子数据与 SQL 现算值，不使用写死的期望数字。
"""
import datetime as dt
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
from decimal import Decimal, ROUND_HALF_UP

BASE = "http://127.0.0.1:8080/api"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"
PASSWORD = "Nl@123456"

ACC_FAMILY = "fam001"
ACC_COMPANION = "comp001"
ACC_ELDER = "elder001"
ACC_ADMIN = "admin"

ELDER_OWN = 401

MASKED_PHONE_RE = re.compile(r"^1\d{2}\*{4}\d{4}$")
FULL_PHONE_RE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
ID_CARD_RE = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")
MASKED_NAME_RE = re.compile(r"^.\*+.*$")

# 7 个统计/导出接口 —— 一次跑完「非 ADMIN 一律 403」。
ADMIN_ENDPOINTS = [
    ("GET", "/statistics/overview", None),
    ("GET", "/statistics/order-trend", None),
    ("GET", "/statistics/order-status", None),
    ("GET", "/statistics/companion-rank", None),
    ("GET", "/statistics/medication-missed", None),
    ("GET", "/statistics/export/order", None),
    ("GET", "/statistics/export/user", None),
]

# 导出订单的列定义（docs/api/09 §6 表格顺序）
ORDER_EXPORT_HEADERS = ["订单号", "老人姓名", "老人年龄", "家属姓名", "陪诊员", "医院",
                        "科室", "就诊时间", "订单状态", "服务费", "结算状态",
                        "下单时间", "接单时间", "完成时间"]
# 导出用户的列定义（docs/api/09 §7 表格顺序）
USER_EXPORT_HEADERS = ["用户 ID", "用户名", "昵称", "手机号", "角色", "账号状态",
                       "关联订单数", "注册时间", "最后登录时间"]

results = []


# ======================================================================
# 基础设施
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
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw[:300]}


def call_file(path, token, timeout=120):
    """下载型接口：返回 (http, headers, 原始字节)。二进制流不能用 json.loads 解。"""
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


def mysql_rows(sql):
    """多行结果 → [[col, col, ...], ...]（制表符分隔）"""
    out = mysql_value(sql)
    if not out:
        return []
    return [line.split("\t") for line in out.splitlines()]


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
# 小工具：日期 / 百分数 / xlsx
# ======================================================================

def sod(d):
    return d.strftime("%Y-%m-%d 00:00:00")


def eod(d):
    return d.strftime("%Y-%m-%d 23:59:59")


def isodate(d):
    return d.strftime("%Y-%m-%d")


def pct(numerator, denominator):
    """与后端 ChartDataVO.percent 同口径：HALF_UP、两位小数、带 %"""
    if denominator <= 0:
        return "0.00%"
    value = (Decimal(numerator) * 100 / Decimal(denominator)).quantize(
        Decimal("0.01"), rounding=ROUND_HALF_UP)
    return str(value) + "%"


def iso_week_start(d):
    """ISO 周：周一为一周第一天（与 StatisticsGranularity.WEEK 一致）"""
    return d - dt.timedelta(days=d.isoweekday() - 1)


def range_dates(start, end):
    days = (end - start).days
    return [start + dt.timedelta(days=i) for i in range(days + 1)]


CELL_RE = re.compile(r"<c\b([^>]*?)(?:/>|>(.*?)</c>)", re.S)


def parse_xlsx(raw):
    """不依赖 openpyxl 的最小 xlsx 解析：返回 [[cell, ...], ...]（含表头行）。"""
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
            elif kind == "str" and value:
                cells.append(html.unescape(value.group(1)))
            elif value:
                cells.append(html.unescape(value.group(1)))
            else:
                cells.append("")
        rows.append(cells)
    return rows


def try_parse(raw):
    """解析失败返回空列表：断言要看到的是「失败」而不是脚本崩掉。"""
    try:
        return parse_xlsx(raw)
    except Exception:  # noqa: BLE001 - 非 xlsx 字节（例如 9001 的 JSON 错误体）
        return []


def today():
    return dt.date.today()


# ======================================================================
# SQL 现算值（与 StatisticsMapper 的口径逐条对齐）
# ======================================================================

def sql_order_count(start, end, statuses=None):
    where = ""
    if statuses:
        where = " AND status IN (%s)" % ",".join("'%s'" % s for s in statuses)
    return mysql_int("SELECT COUNT(*) FROM `companion_order` WHERE `deleted`=0%s "
                     "AND `create_time` BETWEEN '%s' AND '%s';" % (where, sod(start), eod(end)))


def sql_medication(start, end, elder_id=None):
    extra = "" if elder_id is None else " AND `elder_id`=%d" % elder_id
    row = mysql_value(
        "SELECT CONCAT(COUNT(*),'|',"
        "IFNULL(SUM(CASE WHEN `status`='TAKEN' THEN 1 ELSE 0 END),0),'|',"
        "IFNULL(SUM(CASE WHEN `was_missed`=1 THEN 1 ELSE 0 END),0)) "
        "FROM `medication_task` WHERE `deleted`=0%s "
        "AND `plan_time` BETWEEN '%s' AND '%s';" % (extra, sod(start), eod(end)))
    if not row:
        return 0, 0, 0
    total, taken, missed = row.split("|")
    return int(total), int(taken), int(missed)


def sql_companion_orders(start, end, companion_id):
    return mysql_int(
        "SELECT COUNT(*) FROM `companion_order` o "
        "JOIN `companion_profile` p ON p.`user_id`=o.`companion_id` "
        "  AND p.`deleted`=0 AND p.`audit_status`='APPROVED' "
        "WHERE o.`deleted`=0 AND o.`companion_id`=%d "
        "AND o.`create_time` BETWEEN '%s' AND '%s';" % (companion_id, sod(start), eod(end)))


# ======================================================================
# 主流程
# ======================================================================

def main():
    limit_phase = "--limit-phase" in sys.argv

    print("=" * 78)
    print("银龄伴诊 M10 数据统计与导出 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    admin = login_ok(ACC_ADMIN)
    fam = login_ok(ACC_FAMILY)
    elder = login_ok(ACC_ELDER)
    comp = login_ok(ACC_COMPANION)
    print("[准备] 四个身份登录完成\n")

    t = today()
    default_start = t - dt.timedelta(days=29)
    default_end = t

    # ================= A. 鉴权边界 =================
    print("--- A. ADMIN 鉴权边界 ---")
    status, body = call("GET", "/statistics/overview", None, None)
    check("A1 未登录访问统计接口 → 401", status == 401 or body.get("code") == 401,
          "http=%s" % status)

    for label, token, acc in (("家属", fam, ACC_FAMILY),
                              ("老人", elder, ACC_ELDER),
                              ("陪诊员", comp, ACC_COMPANION)):
        bad = []
        for method, path, payload in ADMIN_ENDPOINTS:
            st, bd = call(method, path, payload, token)
            if st != 403 and bd.get("code") != 403:
                bad.append("%s %s → http=%s code=%s" % (method, path, st, bd.get("code")))
        check("A2 %s（%s）访问 7 个统计接口全部 403" % (label, acc), not bad,
              "越权通过 %d 个：%s" % (len(bad), bad[:3]))

    # ================= B. 总览 =================
    print("\n--- B. 总览指标 ---")
    status, body = call("GET", "/statistics/overview", None, admin)
    ov = body.get("data") or {}
    check("B1 总览 → 200 且四段结构齐全",
          body.get("code") == 200 and all(k in ov for k in
                                          ("startDate", "endDate", "order", "user",
                                           "companion", "medication")),
          "keys=%s" % sorted(ov.keys()))

    check("B2 不传区间时默认最近 30 天（含今天）",
          ov.get("startDate") == isodate(default_start) and ov.get("endDate") == isodate(default_end),
          "start=%s end=%s（期望 %s ~ %s）" % (ov.get("startDate"), ov.get("endDate"),
                                              isodate(default_start), isodate(default_end)))

    order = ov.get("order") or {}
    db_total = sql_order_count(default_start, default_end)
    db_completed = sql_order_count(default_start, default_end, ["COMPLETED", "REVIEWED"])
    db_cancelled = sql_order_count(default_start, default_end, ["CANCELLED"])
    db_progress = sql_order_count(default_start, default_end, ["PENDING", "ACCEPTED", "IN_SERVICE"])

    check("B3 订单总数与 SQL 对拍",
          order.get("totalCount") == db_total, "api=%s sql=%s" % (order.get("totalCount"), db_total))
    check("B4 完成数 = COMPLETED + REVIEWED（评价只是完成后的动作，不能排除）",
          order.get("completedCount") == db_completed,
          "api=%s sql=%s" % (order.get("completedCount"), db_completed))
    check("B5 取消数与 SQL 对拍",
          order.get("cancelledCount") == db_cancelled,
          "api=%s sql=%s" % (order.get("cancelledCount"), db_cancelled))
    check("B6 进行中数 = PENDING + ACCEPTED + IN_SERVICE",
          order.get("inProgressCount") == db_progress,
          "api=%s sql=%s" % (order.get("inProgressCount"), db_progress))
    check("B7 完成率与手算一致（分母是区间内全部订单，含已取消）",
          order.get("completedRate") == pct(db_completed, db_total),
          "api=%s 手算=%s（%s/%s）" % (order.get("completedRate", ""),
                                       pct(db_completed, db_total), db_completed, db_total))
    check("B8 取消率与手算一致",
          order.get("cancelRate") == pct(db_cancelled, db_total),
          "api=%s 手算=%s" % (order.get("cancelRate", ""), pct(db_cancelled, db_total)))

    user = ov.get("user") or {}
    db_user_total = mysql_int("SELECT COUNT(*) FROM `sys_user` WHERE `deleted`=0;")
    db_new = mysql_int("SELECT COUNT(*) FROM `sys_user` WHERE `deleted`=0 "
                       "AND `create_time` BETWEEN '%s' AND '%s';" % (sod(default_start), eod(default_end)))
    check("B9 用户总数是**全量**（不受时间区间影响）",
          user.get("totalCount") == db_user_total,
          "api=%s sql=%s" % (user.get("totalCount"), db_user_total))
    check("B10 区间新增用户数与 SQL 对拍",
          user.get("newCount") == db_new, "api=%s sql=%s" % (user.get("newCount"), db_new))
    check("B11 增长率分母是「区间开始前的用户数」= 总数 - 新增（用总数当分母会让基数越大增长越假）",
          user.get("growthRate") == pct(db_new, max(0, db_user_total - db_new)),
          "api=%s 手算=%s（%s/(%s-%s)）" % (user.get("growthRate", ""),
                                           pct(db_new, max(0, db_user_total - db_new)),
                                           db_new, db_user_total, db_new))

    role_ok = True
    role_detail = []
    for role, field in (("ELDER", "elderCount"), ("FAMILY", "familyCount"),
                        ("COMPANION", "companionCount"), ("ADMIN", "adminCount")):
        db_role = mysql_int("SELECT COUNT(*) FROM `sys_user` WHERE `deleted`=0 AND `role`='%s';" % role)
        if user.get(field) != db_role:
            role_ok = False
        role_detail.append("%s=%s/%s" % (role, user.get(field), db_role))
    check("B12 四种角色数与 SQL 对拍", role_ok, " ".join(role_detail))

    companion = ov.get("companion") or {}
    db_active = mysql_int("SELECT COUNT(DISTINCT `companion_id`) FROM `companion_order` "
                          "WHERE `deleted`=0 AND `companion_id` IS NOT NULL "
                          "AND `create_time` BETWEEN '%s' AND '%s';"
                          % (sod(default_start), eod(default_end)))
    check("B13 活跃陪诊员数 = 区间内有过接单的去重人数",
          companion.get("activeCount") == db_active,
          "api=%s sql=%s" % (companion.get("activeCount"), db_active))

    db_avg = mysql_value("SELECT IFNULL(ROUND(AVG(TIMESTAMPDIFF(MINUTE, `create_time`, `accept_time`))),0) "
                         "FROM `companion_order` WHERE `deleted`=0 AND `accept_time` IS NOT NULL "
                         "AND `create_time` BETWEEN '%s' AND '%s';"
                         % (sod(default_start), eod(default_end)))
    check("B14 平均接单耗时与 SQL 一致（只算已接单的，未接单的会把均值拉垮）",
          str(companion.get("avgAcceptMinutes")) == str(int(db_avg or 0)),
          "api=%s sql=%s" % (companion.get("avgAcceptMinutes"), db_avg))

    medication = ov.get("medication") or {}
    m_total, m_taken, m_missed = sql_medication(default_start, default_end)
    check("B15 用药四项与 SQL 对拍（漏服口径 was_missed=1，不是 status=MISSED）",
          medication.get("taskTotalCount") == m_total
          and medication.get("takenCount") == m_taken
          and medication.get("missedCount") == m_missed
          and medication.get("missedRate") == pct(m_missed, m_total),
          "api=%s/%s/%s/%s sql=%s/%s/%s/%s"
          % (medication.get("taskTotalCount"), medication.get("takenCount"),
             medication.get("missedCount"), medication.get("missedRate"),
             m_total, m_taken, m_missed, pct(m_missed, m_total)))

    # 区间外数据不混入：只查今天
    status, body = call("GET", "/statistics/overview?startDate=%s&endDate=%s"
                        % (isodate(t), isodate(t)), None, admin)
    narrow = (body.get("data") or {}).get("order") or {}
    db_today = sql_order_count(t, t)
    check("B16 自定义窄区间（只查今天）与 SQL 一致，且小于 30 天区间的值（区间外不混入）",
          body.get("code") == 200 and narrow.get("totalCount") == db_today
          and db_today < db_total,
          "今天=%s sql=%s 30天=%s" % (narrow.get("totalCount"), db_today, db_total))

    # 空区间：未来某天
    future = t + dt.timedelta(days=10)
    status, body = call("GET", "/statistics/overview?startDate=%s&endDate=%s"
                        % (isodate(future), isodate(future)), None, admin)
    empty = body.get("data") or {}
    empty_order = empty.get("order") or {}
    empty_med = empty.get("medication") or {}
    check("B17 无数据的区间 → 全部 0 且比率为 \"0.00%\"（不是 NaN / null / 报错）",
          body.get("code") == 200 and empty_order.get("totalCount") == 0
          and empty_order.get("completedRate") == "0.00%"
          and empty_order.get("cancelRate") == "0.00%"
          and empty_med.get("missedRate") == "0.00%"
          and empty.get("user", {}).get("newCount") == 0,
          "order=%s rate=%s/%s" % (empty_order.get("totalCount"),
                                   empty_order.get("completedRate"),
                                   empty_order.get("cancelRate")))

    status, body = call("GET", "/statistics/overview?startDate=%s&endDate=%s"
                        % (isodate(t), isodate(t - dt.timedelta(days=5))), None, admin)
    check("B18 起止日期颠倒 → 9002（SQL 层不会报错，只会静默返回空数据，所以必须显式拦）",
          body.get("code") == 9002, "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/statistics/overview?startDate=%s&endDate=%s"
                        % (isodate(t - dt.timedelta(days=365)), isodate(t)), None, admin)
    check("B19a 恰好 366 天的区间是合法的（边界含等于）",
          body.get("code") == 200, "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("GET", "/statistics/overview?startDate=%s&endDate=%s"
                        % (isodate(t - dt.timedelta(days=366)), isodate(t)), None, admin)
    check("B19b 367 天 → 9002", body.get("code") == 9002,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/statistics/overview?startDate=2026/09/01", None, admin)
    check("B20 日期格式不对 → 400（而不是当成没有这个参数悄悄忽略）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= C. 订单趋势 =================
    print("\n--- C. 订单趋势 ---")
    span = isodate(default_start) + "&endDate=" + isodate(default_end)
    status, body = call("GET", "/statistics/order-trend?startDate=" + span, None, admin)
    trend = body.get("data") or {}
    check("C1 订单趋势 → 200 且三条序列（新增 / 完成 / 取消）",
          body.get("code") == 200 and trend.get("granularity") == "day"
          and len(trend.get("series") or []) == 3,
          "granularity=%s series=%s" % (trend.get("granularity"),
                                        [s.get("name") for s in (trend.get("series") or [])]))

    cats = trend.get("categories") or []
    series = trend.get("series") or []
    check("C2 categories 覆盖区间每一天（无数据的日期补 0，而不是缺席）",
          len(cats) == 30 and cats[0] == isodate(default_start) and cats[-1] == isodate(default_end),
          "条数=%d 首=%s 末=%s" % (len(cats), cats[0] if cats else "-", cats[-1] if cats else "-"))
    check("C3 三条序列长度与 categories 严格一致（不一致前端折线图会静默跳段）",
          all(len(s.get("data") or []) == len(cats) for s in series),
          "长度=%s" % [len(s.get("data") or []) for s in series])
    check("C4 categories 升序",
          cats == sorted(cats), "")

    sum_new = sum(series[0].get("data") or [])
    sum_done = sum(series[1].get("data") or [])
    sum_cancel = sum(series[2].get("data") or [])
    check("C5 三条序列求和回总览（同一区间三个数字必须自洽）",
          sum_new == db_total and sum_done == db_completed and sum_cancel == db_cancelled,
          "新增=%s/%s 完成=%s/%s 取消=%s/%s"
          % (sum_new, db_total, sum_done, db_completed, sum_cancel, db_cancelled))

    busy = {r[0] for r in mysql_rows("SELECT DISTINCT DATE_FORMAT(`create_time`,'%%Y-%%m-%%d') "
                                     "FROM `companion_order` WHERE `deleted`=0 "
                                     "AND `create_time` BETWEEN '%s' AND '%s';"
                                     % (sod(default_start), eod(default_end))) if r and r[0]}
    quiet = next((isodate(d) for d in range_dates(default_start, default_end)
                  if isodate(d) not in busy), None)
    if quiet is None:
        check("C6 无订单的日期在趋势里补 0", True, "区间内每天都有订单，跳过")
    else:
        idx = cats.index(quiet)
        check("C6 无订单的日期在趋势里补 0（不是缺这个点，也不是 null）",
              all((s.get("data") or [])[idx] == 0 for s in series),
              "%s 位置=%s" % (quiet, [(s.get("data") or [])[idx] for s in series]))

    # WEEK / MONTH
    status, body = call("GET", "/statistics/order-trend?startDate=%s&granularity=WEEK" % span, None, admin)
    wt = body.get("data") or {}
    wcats = wt.get("categories") or []
    expect_weeks = sorted({iso_week_start(d) for d in range_dates(default_start, default_end)})
    check("C7 WEEK 分桶数 = 区间覆盖的 ISO 周数（周一为起）",
          body.get("code") == 200 and wt.get("granularity") == "week"
          and len(wcats) == len(expect_weeks),
          "api=%d 期望=%d" % (len(wcats), len(expect_weeks)))
    check("C8 WEEK 标签是「桶起始日 + 当周」且起始日都是周一（周日归本周，不是下周）",
          all(c.endswith(" 当周") for c in wcats)
          and all(dt.date.fromisoformat(c[:10]).isoweekday() == 1 for c in wcats),
          "样本=%s" % wcats[:3])
    check("C9 WEEK 三条序列长度仍与 categories 一致",
          all(len(s.get("data") or []) == len(wcats) for s in (wt.get("series") or [])), "")
    check("C10 粒度切换后求和不变（分桶只改横轴，不改总量）",
          sum((wt.get("series") or [{}])[0].get("data") or []) == db_total,
          "新增求和=%s sql=%s" % (sum((wt.get("series") or [{}])[0].get("data") or []), db_total))

    status, body = call("GET", "/statistics/order-trend?startDate=%s&granularity=MONTH" % span, None, admin)
    mt = body.get("data") or {}
    mcats = mt.get("categories") or []
    expect_months = sorted({("%04d-%02d" % (d.year, d.month)) for d in range_dates(default_start, default_end)})
    check("C11 MONTH 分桶数 = 区间覆盖的自然月数，标签为 yyyy-MM",
          body.get("code") == 200 and mcats == expect_months,
          "api=%s 期望=%s" % (mcats, expect_months))

    status, body = call("GET", "/statistics/order-trend?granularity=QUARTER", None, admin)
    check("C12 非法粒度 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/statistics/order-trend?startDate=%s&endDate=%s"
                        % (isodate(t), isodate(t)), None, admin)
    one = body.get("data") or {}
    check("C13 只查今天时横轴收敛为 1 个点（区间外数据一个都不许混进来）",
          body.get("code") == 200 and len(one.get("categories") or []) == 1
          and (one.get("categories") or [""])[0] == isodate(t),
          "categories=%s" % (one.get("categories") or [])[:3])

    # ================= D. 状态分布 =================
    print("\n--- D. 订单状态分布 ---")
    status, body = call("GET", "/statistics/order-status?startDate=" + span, None, admin)
    stats = body.get("data") or []
    codes = [r.get("status") for r in stats]
    check("D1 六种状态全部返回（数量为 0 的也要返回，否则饼图图例少一块颜色）",
          body.get("code") == 200 and codes == ["PENDING", "ACCEPTED", "IN_SERVICE",
                                                "COMPLETED", "REVIEWED", "CANCELLED"],
          "status=%s" % codes)
    check("D2 每条都带中文状态名", all(r.get("statusLabel") for r in stats), "")

    d_ok = True
    d_detail = []
    for r in stats:
        db = sql_order_count(default_start, default_end, [r.get("status")])
        if r.get("count") != db:
            d_ok = False
        d_detail.append("%s=%s/%s" % (r.get("status"), r.get("count"), db))
    check("D3 各状态数量与 SQL 对拍", d_ok, " ".join(d_detail))

    check("D4 count 求和 = 总览订单总数",
          sum(r.get("count") or 0 for r in stats) == db_total,
          "求和=%s sql=%s" % (sum(r.get("count") or 0 for r in stats), db_total))

    percent_sum = sum(Decimal(str(r.get("percent", "0")).rstrip("%")) for r in stats)
    check("D5 percent 之和 = 100.00%（允许四舍五入 0.05 的误差）",
          abs(percent_sum - Decimal("100")) <= Decimal("0.05"),
          "求和=%s" % percent_sum)
    check("D6 percent 格式统一为两位小数 + %",
          all(re.match(r"^\d+\.\d{2}%$", str(r.get("percent"))) for r in stats),
          "样本=%s" % [r.get("percent") for r in stats[:3]])

    status, body = call("GET", "/statistics/order-status?startDate=%s&endDate=%s"
                        % (isodate(future), isodate(future)), None, admin)
    empty_stats = body.get("data") or []
    check("D7 空区间 → 仍是 6 条全 0，percent 全 \"0.00%\"（不 NaN、不 null、不报错）",
          body.get("code") == 200 and len(empty_stats) == 6
          and all(r.get("count") == 0 and r.get("percent") == "0.00%" for r in empty_stats), "")

    # ================= E. 陪诊员排行 =================
    print("\n--- E. 陪诊员排行 ---")
    status, body = call("GET", "/statistics/companion-rank?startDate=" + span, None, admin)
    rank = body.get("data") or []
    check("E1 排行 → 200 且非空", body.get("code") == 200 and bool(rank), "条数=%d" % len(rank))
    check("E2 rank 从 1 起连续编号",
          [r.get("rank") for r in rank] == list(range(1, len(rank) + 1)),
          "rank=%s" % [r.get("rank") for r in rank][:6])
    counts = [r.get("orderCount") or 0 for r in rank]
    check("E3 按接单数降序", counts == sorted(counts, reverse=True), "前五=%s" % counts[:5])

    e_ok = True
    e_detail = []
    for r in rank:
        db = sql_companion_orders(default_start, default_end, r.get("companionId"))
        if r.get("orderCount") != db:
            e_ok = False
        e_detail.append("%s=%s/%s" % (r.get("companionId"), r.get("orderCount"), db))
    check("E4 每条接单数与 SQL 对拍", e_ok, " ".join(e_detail[:4]))

    check("E5 接单数为 0 的不进榜", all((r.get("orderCount") or 0) > 0 for r in rank), "")

    not_approved = [r.get("companionId") for r in rank
                    if mysql_value("SELECT `audit_status` FROM `companion_profile` "
                                   "WHERE `user_id`=%d AND `deleted`=0;" % r.get("companionId"))
                    != "APPROVED"]
    check("E6 榜上只出现资质已通过审核的陪诊员（只按 role=COMPANION 会混进未通过的人）",
          not not_approved, "混入=%s" % not_approved)

    check("E7 姓名已脱敏（含 * 且不是完整姓名）",
          all(MASKED_NAME_RE.match(str(r.get("companionName") or "")) for r in rank),
          "样本=%s" % [r.get("companionName") for r in rank[:3]])

    status, body = call("GET", "/statistics/companion-rank?startDate=%s&limit=3" % span, None, admin)
    check("E8 limit=3 生效", body.get("code") == 200 and len(body.get("data") or []) == 3,
          "条数=%d" % len(body.get("data") or []))
    status, body = call("GET", "/statistics/companion-rank?limit=51", None, admin)
    check("E9 limit=51 超过上限 50 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("GET", "/statistics/companion-rank?limit=0", None, admin)
    check("E10 limit=0 → 400（不能让「返回 0 条」伪装成一次成功的查询）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/statistics/companion-rank?startDate=%s&metric=SCORE" % span, None, admin)
    score_rank = body.get("data") or []
    scores = [str(r.get("score")) for r in score_rank]
    check("E11 metric=SCORE 时按评分降序",
          body.get("code") == 200 and scores == sorted(scores, key=lambda s: Decimal(s)
                                                      if s not in ("None", "") else Decimal("-1"),
                                                      reverse=True),
          "前五=%s" % scores[:5])
    status, body = call("GET", "/statistics/companion-rank?metric=AVG_SCORE", None, admin)
    check("E12 非法排行指标 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/statistics/companion-rank?startDate=%s&endDate=%s"
                        % (isodate(future), isodate(future)), None, admin)
    check("E13 空区间 → 空数组（不报错、不返回 null）",
          body.get("code") == 200 and body.get("data") in ([], None),
          "data=%s" % body.get("data"))

    # ================= F. 漏服率 =================
    print("\n--- F. 漏服率统计 ---")
    status, body = call("GET", "/statistics/medication-missed?startDate=" + span, None, admin)
    md = body.get("data") or {}
    # 响应形状是 { chart: {granularity,categories,series}, summary: {...} } ——
    # 图与汇总必须在同一个响应里（见 docs/api/09 §5 实现要点），
    # 拆成两个接口会让「两次查询之间数据已变」变成常态
    chart = md.get("chart") or {}
    check("F1 漏服率 → 200，且响应形状为 chart + summary（不是把图字段摊平在 data 上）",
          body.get("code") == 200 and set(md.keys()) == {"chart", "summary"}
          and len(chart.get("series") or []) == 3,
          "keys=%s series=%s" % (sorted(md.keys()),
                                 [s.get("name") for s in (chart.get("series") or [])]))
    mcats = chart.get("categories") or []
    check("F2 chart.categories 覆盖区间每一天（补 0 口径与订单趋势一致）",
          len(mcats) == 30 and chart.get("granularity") == "day", "条数=%d" % len(mcats))
    check("F3 三条序列长度与 categories 一致",
          all(len(s.get("data") or []) == len(mcats) for s in (chart.get("series") or [])), "")

    summary = md.get("summary") or {}
    f_total, f_taken, f_missed = sql_medication(default_start, default_end)
    check("F4 summary 与 SQL 对拍（total / taken / missed / rate）",
          summary.get("taskTotalCount") == f_total and summary.get("takenCount") == f_taken
          and summary.get("missedCount") == f_missed
          and summary.get("missedRate") == pct(f_missed, f_total),
          "api=%s/%s/%s/%s sql=%s/%s/%s/%s"
          % (summary.get("taskTotalCount"), summary.get("takenCount"),
             summary.get("missedCount"), summary.get("missedRate"),
             f_total, f_taken, f_missed, pct(f_missed, f_total)))

    s_sum = [sum(s.get("data") or []) for s in (chart.get("series") or [])]
    check("F5 三条序列求和 = summary（图与数字必须自洽，否则截图就是 bug）",
          s_sum == [f_total, f_taken, f_missed], "序列求和=%s sql=%s"
          % (s_sum, [f_total, f_taken, f_missed]))

    f8_total, _, _ = sql_medication(default_start, default_end, ELDER_OWN)
    status, body = call("GET", "/statistics/medication-missed?startDate=%s&elderId=%d"
                        % (span, ELDER_OWN), None, admin)
    ed = body.get("data") or {}
    ed_sum = ed.get("summary") or {}
    check("F6 elderId 过滤生效（与 SQL 对拍，且不大于全量）",
          body.get("code") == 200 and ed_sum.get("taskTotalCount") == f8_total
          and f8_total <= f_total,
          "老人 %d=%s sql=%s 全量=%s" % (ELDER_OWN, ed_sum.get("taskTotalCount"), f8_total, f_total))

    status, body = call("GET", "/statistics/medication-missed?startDate=%s&endDate=%s"
                        % (isodate(future), isodate(future)), None, admin)
    empty_md = body.get("data") or {}
    empty_sum = empty_md.get("summary") or {}
    check("F7 空区间 → 全 0 且漏服率 \"0.00%\"",
          body.get("code") == 200 and empty_sum.get("taskTotalCount") == 0
          and empty_sum.get("missedCount") == 0 and empty_sum.get("missedRate") == "0.00%",
          "summary=%s" % empty_sum)

    # ================= G. 导出 =================
    if limit_phase:
        # 上限阶段（后端以极小的 nianglin.export.max-rows 启动）：
        # 任何全量导出都会被 9001 拦下，此时没有 xlsx 字节可验，
        # 硬跑文件级断言只会得到一串假失败。这一阶段改验
        # 「上限真的拦得住，且拦下时给的是可读的 JSON 错误体，不是半截 xlsx」。
        print("\n--- G. Excel 导出行数上限（--limit-phase）---")
        section_export_limit(admin)
    else:
        print("\n--- G. Excel 导出 ---")
        section_export(admin)
    return report()


def section_export(admin):
    status, headers, raw = call_file("/statistics/export/order", admin)
    ctype = (headers.get("Content-Type") or "")
    check("G1 导出订单 → 200 且 Content-Type 是 xlsx（不能是 application/json）",
          status == 200 and ctype.startswith(
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
          "http=%s content-type=%s" % (status, ctype))

    disposition = headers.get("Content-Disposition") or ""
    decoded_name = ""
    match = re.search(r"filename\*=UTF-8''([^;]+)", disposition)
    if match:
        decoded_name = urllib.parse.unquote(match.group(1))
    check("G2 中文文件名用 RFC 5987 filename*=UTF-8'' 下发（只给裸中文会让部分浏览器乱码）",
          bool(match) and re.match(r"^订单数据_\d{8}_\d{6}\.xlsx$", decoded_name),
          "Content-Disposition=%s" % disposition[:110])

    rows = []
    zip_ok = True
    try:
        rows = parse_xlsx(raw)
    except Exception as e:  # noqa: BLE001 - 解析失败本身就是断言失败
        zip_ok = False
        check("G3 下载到的字节是合法 xlsx（可解压、可解析）", False, str(e)[:120])
    if zip_ok:
        check("G3 下载到的字节是合法 xlsx（可解压、可解析）",
              bool(rows) and raw[:2] == b"PK", "行数=%d 字节=%d" % (len(rows), len(raw)))

    header_row = rows[0] if rows else []
    check("G4 表头 14 列且顺序与文档一致",
          len(header_row) == len(ORDER_EXPORT_HEADERS) and header_row == ORDER_EXPORT_HEADERS,
          "实际=%s" % header_row)

    page_status, page_body = call("GET", "/admin/order?status=CANCELLED&size=1", None, admin)
    api_total = (page_body.get("data") or {}).get("total")
    status, _, raw_cancel = call_file("/statistics/export/order?status=CANCELLED", admin)
    rows_cancel = try_parse(raw_cancel) if status == 200 else []
    check("G5 导出行数 = 页面同一筛选条件下的记录数（导出必须等于所见）",
          status == 200 and len(rows_cancel) - 1 == api_total,
          "导出行数=%d 页面 total=%s" % (len(rows_cancel) - 1, api_total))

    blob = json.dumps(rows, ensure_ascii=False)
    check("G6 导出内容不含完整手机号 / 身份证明文（合规红线：文件会流出平台之外）",
          not FULL_PHONE_RE.search(blob) and not ID_CARD_RE.search(blob),
          "手机号命中=%s 身份证命中=%s"
          % (bool(FULL_PHONE_RE.search(blob)), bool(ID_CARD_RE.search(blob))))
    check("G7 导出里不含 password 字段名",
          "password" not in blob.lower(), "")

    name_idx = ORDER_EXPORT_HEADERS.index("老人姓名")
    names = [r[name_idx] for r in rows_cancel[1:] if len(r) > name_idx and r[name_idx]]
    check("G8 导出里的姓名已脱敏", bool(names) and all("*" in n for n in names),
          "样本=%s" % names[:3])

    status, headers, raw_user = call_file("/statistics/export/user", admin)
    u_rows = parse_xlsx(raw_user) if status == 200 else []
    u_header = u_rows[0] if u_rows else []
    check("G9 导出用户表头 9 列、无身份证列、顺序与文档一致",
          status == 200 and u_header == USER_EXPORT_HEADERS
          and not any("身份证" in h for h in u_header),
          "实际=%s" % u_header)

    status, page_body = call("GET", "/admin/user?size=1", None, admin)
    u_api_total = (page_body.get("data") or {}).get("total")
    check("G10 用户导出行数 = 页面记录数",
          len(u_rows) - 1 == u_api_total,
          "导出行数=%d 页面 total=%s" % (len(u_rows) - 1, u_api_total))

    u_blob = json.dumps(u_rows, ensure_ascii=False)
    phone_idx = USER_EXPORT_HEADERS.index("手机号")
    phones = [r[phone_idx] for r in u_rows[1:] if len(r) > phone_idx and r[phone_idx]]
    check("G11 用户导出手机号一律脱敏，且全文无完整手机号 / 身份证 / 密码哈希",
          bool(phones) and all(MASKED_PHONE_RE.match(p) for p in phones)
          and not FULL_PHONE_RE.search(u_blob) and not ID_CARD_RE.search(u_blob)
          and "$2a$" not in u_blob,
          "样本=%s" % phones[:3])

    status, page_body = call("GET", "/admin/user?keyword=%s&size=1" % ACC_FAMILY, None, admin)
    kw_total = (page_body.get("data") or {}).get("total")
    status, _, raw_kw = call_file("/statistics/export/user?keyword=%s" % ACC_FAMILY, admin)
    kw_rows = parse_xlsx(raw_kw) if status == 200 else []
    check("G12 keyword 筛选在导出侧同样生效（导出不是「无脑导全表」）",
          status == 200 and kw_total == 1 and len(kw_rows) - 1 == kw_total,
          "页面 total=%s 导出行数=%d" % (kw_total, len(kw_rows) - 1))

    status, body = call("GET", "/statistics/export/order?status=NOT_A_STATUS", None, admin)
    check("G13 导出侧非法筛选值同样 400（导出与列表共用同一套参数校验）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

def call_lenient(path, token):
    """导出类接口返回 JSON 还是 xlsx 取决于业务分支。

    直接 json.loads 会在「没触发上限」时抛 UnicodeDecodeError，把整个阶段掀翻 ——
    而那恰恰是最需要看清原因的时候（是跑法不对，还是功能坏了）。
    这里解析不了就返回 None，把判断权交回调用方。
    """
    status, headers, raw = call_file(path, token)
    if raw[:2] == b"PK":
        return status, None, raw
    try:
        return status, json.loads(raw.decode("utf-8")), raw
    except (ValueError, UnicodeDecodeError):
        return status, None, raw


def section_export_limit(admin):
    status, headers, raw = call_file("/statistics/export/order", admin)
    ctype = (headers.get("Content-Type") or "")
    hit_row_cap = ctype.startswith("application/json") and raw[:2] != b"PK"
    check("H1 超过 max-rows 上限 → 9001，且 Content-Type 是 JSON 而不是 xlsx"
          "（半截 xlsx 比报错更难排查）",
          hit_row_cap,
          "http=%s content-type=%s" % (status, ctype))
    if not hit_row_cap:
        # 上限定的是 1 万行，而这份种子数据只有几十条订单 —— 用默认配置跑本阶段
        # 永远触发不到。必须把「跑法不对」说清楚，否则有人会去导出代码里找一个不存在的 bug。
        reason = ("上限未触发：后端不是以 --nianglin.export.max-rows=2 启动的"
                  "（本阶段的前提，见文件头用法说明）")
        print("     ⚠️ " + reason)
        check("H2 订单导出超限的 code = 9001 且提示里给出「缩小筛选范围」的出路", False, reason)
        check("H3 用户导出同样受上限保护 → 9001（合规导出不能只保护一个入口）", False, reason)
    else:
        status, body, _ = call_lenient("/statistics/export/order", admin)
        code, message = (body or {}).get("code"), (body or {}).get("message")
        check("H2 订单导出超限的 code = 9001 且提示里给出「缩小筛选范围」的出路",
              code == 9001 and "缩小" in str(message),
              "http=%s code=%s message=%s" % (status, code, message))
        status, body, _ = call_lenient("/statistics/export/user", admin)
        check("H3 用户导出同样受上限保护 → 9001（合规导出不能只保护一个入口）",
              (body or {}).get("code") == 9001,
              "http=%s code=%s" % (status, (body or {}).get("code")))

    # H4 验的是「缩到 1 条就恢复可用」，与 max-rows 大小无关，任何时候都该跑
    sample_no = mysql_value("SELECT `order_no` FROM `companion_order` WHERE `deleted`=0 "
                            "ORDER BY `id` LIMIT 1;")
    status, _, raw_one = call_file("/statistics/export/order?keyword=%s" % sample_no, admin)
    one_rows = try_parse(raw_one)
    check("H4 把筛选范围缩到 1 条后导出恢复可用（上限是「提示缩小范围」，不是「功能不可用」）",
          status == 200 and len(one_rows) - 1 == 1,
          "keyword=%s http=%s 行数=%d" % (sample_no, status, max(0, len(one_rows) - 1)))


def report():
    print()
    print("=" * 78)
    failed = [r[0] for r in results if not r[1]]
    total = len(results)
    print("结果：%d/%d 通过" % (total - len(failed), total))
    if failed:
        print("失败项：")
        for name in failed:
            print("  - %s" % name)
    print("=" * 78)
    return not failed


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
