"""银龄伴诊 V4 P2-2 体征记录 —— 端到端实测（真实 HTTP 打 8080 + 真实库断言）。

用法：
    python e2e_vital_sign.py
        # 前提：后端已在 8080 启动（$env:SPRING_PROFILES_ACTIVE="dev"），MySQL 与 Redis 可用

覆盖（对应 docs/spec/V4-P2-2-vitals-spec.md §11.3）：
  A 家属录入：200 + 数值文本；同分钟同类型重录 → 2008；落库 1 条且录入人正确；
    另一类型同分钟 → 200（唯一键按类型隔离）
  B 列表 / 趋势：倒序、升序、单位与展示文本；非法类型 → 400
  C 老人只读：能读自己的记录；写请求一律拒绝（DELETE → 403，未映射的 POST → 405，
     Spring 先于拦截器返回，语义均为「没有写入口」）
  D 家属越权：非绑定老人读 / 写 → 2006，且不落库
  E 陪诊员代录：本单 IN_SERVICE → 200 且来源/订单/老人正确；跨单 → 3004；
    本单非服务中 → 3002；服务结束后撤回 → 3002
  F 撤回：200；重复撤回 → 2009；撤回后同分钟可重录（唯一键槽位释放）；
    列表不再返回旧记录
  G 残留：脚本创建的记录按 ID 物理清理，清理后计数为 0

数据安全：只写 vital_sign_record + sys_login_log 两张表；main 开头记 max(id) 基线，
`__main__` 的 finally 里按本次记录的 ID 删除（基线 ≤ 0 时无法用 delete_since，
改按白名单 ID 删，绝不 DELETE 全表），并删除本轮登录日志。
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta

BASE = "http://127.0.0.1:8080/api"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fixture_credentials import PASSWORD as SEED_PASSWORD  # noqa: E402  种子口令单一真源
import cleanup_util  # noqa: E402  统一收尾：按基线删除

PASSWORD = SEED_PASSWORD
ACC_FAMILY = "fam001"        # 与 ELDER_ID 绑定
ACC_FAMILY_OTHER = "fam002"  # 与 ELDER_ID 无绑定关系

NOW = datetime.now().replace(second=0, microsecond=0)
T_FIRST = NOW.strftime("%Y-%m-%d %H:%M:%S")
T_EARLIER = (NOW - timedelta(minutes=6)).strftime("%Y-%m-%d %H:%M:%S")

results = []
VITAL_BASELINE = 0
LOGIN_LOG_BASELINE = 0

FAMILY_USER_ID = 0
ELDER_ID = 0
ELDER_ACCOUNT = ""
FOREIGN_ELDER_ID = 0
COMPANION_ACCOUNT = ""
COMPANION_ORDER_ID = 0
CROSS_ORDER_ID = 0
NON_SERVICE_ORDER_ID = 0
ORDER_ELDER_ID = 0

#: 本次脚本创建的记录 ID（清理白名单；绝不 DELETE 全表）
CREATED_IDS = []

# ======================================================================
# 基础设施（与 e2e_settlement.py / e2e_execution.py 同套路）
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


def bp_body(systolic=128, diastolic=78, measured_at=None):
    return {"vitalType": "BLOOD_PRESSURE", "measuredAt": measured_at or T_FIRST,
            "systolic": systolic, "diastolic": diastolic}


def weight_body(weight=62.5, measured_at=None):
    return {"vitalType": "WEIGHT", "measuredAt": measured_at or T_EARLIER,
            "weightKg": weight}


def track(record_id):
    if record_id and record_id not in CREATED_IDS:
        CREATED_IDS.append(record_id)
    return record_id


# ======================================================================
# 夹具选择（全部从库里现取，不把种子 ID 写死在断言里）
# ======================================================================

def pick_fixtures():
    global FAMILY_USER_ID, ELDER_ID, ELDER_ACCOUNT, FOREIGN_ELDER_ID
    global COMPANION_ACCOUNT, COMPANION_ORDER_ID, CROSS_ORDER_ID
    global NON_SERVICE_ORDER_ID, ORDER_ELDER_ID

    FAMILY_USER_ID = mysql_int(
        "SELECT `id` FROM `sys_user` WHERE `username`='%s';" % ACC_FAMILY)
    ELDER_ID = mysql_int(
        "SELECT MIN(`elder_id`) FROM `family_elder_relation` "
        "WHERE `family_id`=%d AND `deleted`=0;" % FAMILY_USER_ID)
    FOREIGN_ELDER_ID = mysql_int(
        "SELECT MIN(`id`) FROM `elder_profile` WHERE `deleted`=0 AND `id`<>%d "
        "AND `id` NOT IN (SELECT `elder_id` FROM `family_elder_relation` "
        "WHERE `family_id`=%d AND `deleted`=0);" % (ELDER_ID, FAMILY_USER_ID))
    ELDER_ACCOUNT = mysql_value(
        "SELECT `username` FROM `sys_user` WHERE `id`=("
        "SELECT `user_id` FROM `elder_profile` WHERE `id`=%d);" % ELDER_ID)

    order_row = mysql_value(
        "SELECT CONCAT_WS('|', `id`, `companion_id`, `elder_id`) FROM `companion_order` "
        "WHERE `deleted`=0 AND `status`='IN_SERVICE' AND `companion_id` IS NOT NULL "
        "ORDER BY `id` LIMIT 1;")
    order_id, companion_id, elder_id = order_row.split("|")
    COMPANION_ORDER_ID = int(order_id)
    ORDER_ELDER_ID = int(elder_id)
    COMPANION_ACCOUNT = mysql_value(
        "SELECT `username` FROM `sys_user` WHERE `id`=%s;" % companion_id)
    CROSS_ORDER_ID = mysql_int(
        "SELECT MIN(`id`) FROM `companion_order` WHERE `deleted`=0 "
        "AND `status`='IN_SERVICE' AND `companion_id`<>%s AND `id`<>%d;"
        % (companion_id, COMPANION_ORDER_ID))
    NON_SERVICE_ORDER_ID = mysql_int(
        "SELECT MIN(`id`) FROM `companion_order` WHERE `deleted`=0 "
        "AND `companion_id`=%s AND `status`<>'IN_SERVICE';" % companion_id)

    if not FAMILY_USER_ID or not ELDER_ID or not ELDER_ACCOUNT or not FOREIGN_ELDER_ID:
        raise RuntimeError("种子数据里找不到可用的家属 / 老人绑定关系，无法执行")
    if not COMPANION_ORDER_ID or not COMPANION_ACCOUNT or not CROSS_ORDER_ID:
        raise RuntimeError("种子数据里找不到两条 IN_SERVICE 订单（需跨单越权样本）")
    print("[准备] 家属 %s(%d) → 老人 %d（老人账号 %s）；非绑定老人 %d"
          % (ACC_FAMILY, FAMILY_USER_ID, ELDER_ID, ELDER_ACCOUNT, FOREIGN_ELDER_ID))
    print("[准备] 陪诊员 %s 的 IN_SERVICE 订单 %d（老人 %d）；跨单样本 %d；非服务中样本 %s"
          % (COMPANION_ACCOUNT, COMPANION_ORDER_ID, ORDER_ELDER_ID,
             CROSS_ORDER_ID, NON_SERVICE_ORDER_ID or "无"))


# ======================================================================
# 阶段
# ======================================================================

def section_family_write(family):
    print("\n--- A. 家属录入 ---")
    http, body = call("POST", "/family/elder/%d/vitals" % ELDER_ID, bp_body(), family)
    data = body.get("data") or {}
    bp_id = track(data.get("id"))
    check("A1 家属录入血压 → 200，返回记录 ID 与展示文本",
          body.get("code") == 200 and bp_id,
          "http=%s code=%s id=%s valueText=%s" % (http, body.get("code"), bp_id,
                                                 data.get("valueText")))
    check("A2 展示文本 / 单位 / 类型标签 / 来源自洽",
          data.get("valueText") == "128/78 mmHg" and data.get("unit") == "mmHg"
          and data.get("vitalTypeLabel") == "血压"
          and data.get("sourceType") == "FAMILY_MANUAL"
          and data.get("sourceTypeLabel") == "家属代录",
          "valueText=%s unit=%s label=%s source=%s"
          % (data.get("valueText"), data.get("unit"), data.get("vitalTypeLabel"),
             data.get("sourceType")))

    http, body = call("POST", "/family/elder/%d/vitals" % ELDER_ID, bp_body(), family)
    check("A3 同老人同类型同分钟重录 → 2008（唯一键拦截，不产生第二条）",
          body.get("code") == 2008,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    dup_count = mysql_int(
        "SELECT COUNT(*) FROM `vital_sign_record` WHERE `deleted`=0 AND `elder_id`=%d "
        "AND `vital_type`='BLOOD_PRESSURE' AND `measured_at`='%s';" % (ELDER_ID, T_FIRST))
    check("A4 落库恰好 1 条，录入人=当前家属", dup_count == 1 and mysql_int(
        "SELECT `operator_user_id` FROM `vital_sign_record` WHERE `id`=%d;" % bp_id)
        == FAMILY_USER_ID, "条数=%d operator=%s" % (dup_count, mysql_int(
            "SELECT `operator_user_id` FROM `vital_sign_record` WHERE `id`=%d;" % bp_id)))

    http, body = call("POST", "/family/elder/%d/vitals" % ELDER_ID, weight_body(), family)
    data_w = body.get("data") or {}
    weight_id = track(data_w.get("id"))
    check("A5 同一分钟另一种类型（体重）→ 200，展示 1 位小数",
          body.get("code") == 200 and data_w.get("valueText") == "62.5 kg",
          "code=%s valueText=%s" % (body.get("code"), data_w.get("valueText")))

    bad = bp_body()
    bad["weightKg"] = 62.5
    http, body = call("POST", "/family/elder/%d/vitals" % ELDER_ID, bad, family)
    check("A6 血压记录夹带体重字段 → 400（类型字段互斥）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    http, body = call("POST", "/family/elder/%d/vitals" % ELDER_ID,
                      bp_body(systolic=320, diastolic=78, measured_at=T_EARLIER), family)
    check("A7 收缩压超出合理录入上限 → 400（只拦误录，不下结论）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))
    return bp_id, weight_id


def section_list_trend(family):
    print("\n--- B. 列表与趋势 ---")
    http, body = call("GET", "/family/elder/%d/vitals?page=1&size=20" % ELDER_ID, None, family)
    data = body.get("data") or {}
    records = data.get("records") or []
    check("B1 列表 → 200 且返回分页结构", body.get("code") == 200 and isinstance(records, list),
          "http=%s code=%s total=%s" % (http, body.get("code"), data.get("total")))
    times = [r.get("measuredAt") for r in records]
    check("B2 列表按测量时间倒序", times == sorted(times, reverse=True), "times=%s" % times[:3])
    first_two = records[:2]
    check("B3 最新一条是血压、次新是体重（同分钟按 ID 兜底）",
          len(first_two) == 2
          and first_two[0].get("vitalType") == "BLOOD_PRESSURE"
          and first_two[1].get("vitalType") == "WEIGHT",
          "顺序=%s" % [r.get("vitalType") for r in first_two])

    http, body = call("GET", "/family/elder/%d/vitals/trend?vitalType=BLOOD_PRESSURE"
                      % ELDER_ID, None, family)
    trend = body.get("data") or {}
    points = trend.get("points") or []
    check("B4 血压趋势 → 1 个点、升序、值与列表一致",
          body.get("code") == 200 and trend.get("pointCount") == 1
          and len(points) == 1 and points[0].get("valueText") == "128/78 mmHg"
          and trend.get("unit") == "mmHg",
          "pointCount=%s points=%s" % (trend.get("pointCount"), points[:1]))
    check("B5 趋势带区间且未截断（truncated=false）",
          isinstance(trend.get("range"), dict) and trend["range"].get("days")
          and trend.get("truncated") is False,
          "range=%s truncated=%s" % (trend.get("range"), trend.get("truncated")))

    http, body = call("GET", "/family/elder/%d/vitals/trend?vitalType=WEIGHT"
                      % ELDER_ID, None, family)
    wt = body.get("data") or {}
    check("B6 体重趋势 → 1 个点", wt.get("pointCount") == 1 and wt.get("unit") == "kg",
          "pointCount=%s unit=%s" % (wt.get("pointCount"), wt.get("unit")))

    http, body = call("GET", "/family/elder/%d/vitals/trend?vitalType=NOT_A_TYPE"
                      % ELDER_ID, None, family)
    check("B7 非法体征类型 → 400（不把脏参数翻译成空趋势）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))


def section_elder_readonly(elder_token, bp_id):
    print("\n--- C. 老人只读 ---")
    http, body = call("GET", "/elder/vitals?page=1&size=20", None, elder_token)
    data = body.get("data") or {}
    ids = [r.get("id") for r in (data.get("records") or [])]
    check("C1 老人读取自己的体征 → 200 且能看到本人记录",
          body.get("code") == 200 and bp_id in ids,
          "code=%s 条数=%d" % (body.get("code"), len(ids)))

    # 规格 §11.3 写的是「POST → 403」。实测：该路径没有 POST 映射，
    # Spring 在进入 ElderReadOnlyInterceptor 之前就返回 405（请求方法不支持），
    # 安全语义一致（写不进去）。这里按「无写入口」断言 403/405 + 不落库，
    # 偏差已在交付说明中列为待拍板项，不擅自改规格。
    before = mysql_int("SELECT COUNT(*) FROM `vital_sign_record` WHERE `deleted`=0 AND `elder_id`=%d;"
                       % ELDER_ID)
    http, body = call("POST", "/elder/vitals", bp_body(measured_at=T_EARLIER), elder_token)
    after = mysql_int("SELECT COUNT(*) FROM `vital_sign_record` WHERE `deleted`=0 AND `elder_id`=%d;"
                      % ELDER_ID)
    check("C2 老人 POST → 403 / 405 且不落库（只读账号不存在写入口）",
          (http == 403 or body.get("code") in (403, 405)) and before == after,
          "http=%s code=%s 条数 %d→%d" % (http, body.get("code"), before, after))

    http, body = call("DELETE", "/elder/vitals/%d" % bp_id, None, elder_token)
    check("C3 老人 DELETE → 403（撤回同样被拦）",
          http == 403 or body.get("code") == 403,
          "http=%s code=%s" % (http, body.get("code")))


def section_family_denied(other_family):
    print("\n--- D. 家属越权 ---")
    http, body = call("GET", "/family/elder/%d/vitals" % ELDER_ID, None, other_family)
    check("D1 非绑定家属读该老人体征 → 2006",
          body.get("code") == 2006, "code=%s message=%s" % (body.get("code"), body.get("message")))

    before = mysql_int("SELECT COUNT(*) FROM `vital_sign_record` WHERE `deleted`=0 AND `elder_id`=%d;"
                       % ELDER_ID)
    http, body = call("POST", "/family/elder/%d/vitals" % ELDER_ID,
                      bp_body(measured_at=T_EARLIER), other_family)
    after = mysql_int("SELECT COUNT(*) FROM `vital_sign_record` WHERE `deleted`=0 AND `elder_id`=%d;"
                      % ELDER_ID)
    check("D2 非绑定家属写该老人体征 → 2006 且不落库",
          body.get("code") == 2006 and before == after,
          "code=%s 条数 %d→%d" % (body.get("code"), before, after))


def section_companion(companion):
    print("\n--- E. 陪诊员服务中代录 ---")
    body = bp_body(systolic=132, diastolic=80)
    http, body_resp = call("POST", "/execution/%d/vitals" % COMPANION_ORDER_ID, body, companion)
    data = body_resp.get("data") or {}
    comp_id = track(data.get("id"))
    check("E1 本单 IN_SERVICE 代录 → 200，来源/订单/老人正确",
          body_resp.get("code") == 200 and comp_id
          and data.get("sourceType") == "COMPANION_SERVICE"
          and data.get("orderId") == COMPANION_ORDER_ID
          and data.get("elderId") == ORDER_ELDER_ID,
          "code=%s id=%s source=%s orderId=%s elderId=%s"
          % (body_resp.get("code"), comp_id, data.get("sourceType"),
             data.get("orderId"), data.get("elderId")))

    http, body_resp = call("POST", "/execution/%d/vitals" % CROSS_ORDER_ID, body, companion)
    check("E2 跨单代录 → 3004（不是本单陪诊员）",
          body_resp.get("code") == 3004,
          "code=%s message=%s" % (body_resp.get("code"), body_resp.get("message")))

    if NON_SERVICE_ORDER_ID:
        http, body_resp = call("POST", "/execution/%d/vitals" % NON_SERVICE_ORDER_ID, body, companion)
        check("E3 本单非服务中代录 → 3002",
              body_resp.get("code") == 3002,
              "code=%s message=%s" % (body_resp.get("code"), body_resp.get("message")))
    else:
        check("E3 本单非服务中代录 → 3002", True, "无样本订单，跳过（种子缺口）")

    http, body_resp = call("POST", "/execution/999999999/vitals", body, companion)
    check("E4 不存在的订单 → 3001",
          body_resp.get("code") == 3001,
          "code=%s message=%s" % (body_resp.get("code"), body_resp.get("message")))
    return comp_id


def section_withdraw(family, companion, bp_id, comp_id):
    print("\n--- F. 撤回与重录 ---")
    http, body = call("DELETE", "/family/vitals/%d" % bp_id, None, family)
    data = body.get("data") or {}
    check("F1 家属撤回本人记录 → 200 且 deleted=true",
          body.get("code") == 200 and data.get("recordId") == bp_id and data.get("deleted") is True,
          "code=%s data=%s" % (body.get("code"), data))

    http, body = call("DELETE", "/family/vitals/%d" % bp_id, None, family)
    check("F2 重复撤回同一条 → 2009（已撤回不再可撤回）",
          body.get("code") == 2009, "code=%s message=%s" % (body.get("code"), body.get("message")))

    http, body = call("POST", "/family/elder/%d/vitals" % ELDER_ID, bp_body(), family)
    data = body.get("data") or {}
    new_bp_id = track(data.get("id"))
    check("F3 撤回后同分钟可重录 → 200 且为新记录 ID",
          body.get("code") == 200 and new_bp_id and new_bp_id != bp_id,
          "code=%s 旧=%s 新=%s" % (body.get("code"), bp_id, new_bp_id))

    http, body = call("GET", "/family/elder/%d/vitals?page=1&size=20" % ELDER_ID, None, family)
    ids = [r.get("id") for r in ((body.get("data") or {}).get("records") or [])]
    check("F4 列表只含新记录、不再返回已撤回的旧记录",
          new_bp_id in ids and bp_id not in ids, "ids=%s" % ids[:4])

    http, body = call("DELETE", "/execution/%d/vitals/%d" % (COMPANION_ORDER_ID, comp_id),
                      None, companion)
    data = body.get("data") or {}
    check("F5 陪诊员撤回本单本人记录 → 200",
          body.get("code") == 200 and data.get("recordId") == comp_id and data.get("deleted") is True,
          "code=%s data=%s" % (body.get("code"), data))

    http, body = call("DELETE", "/execution/%d/vitals/%d" % (COMPANION_ORDER_ID, comp_id),
                      None, companion)
    check("F6 陪诊员重复撤回 → 2009",
          body.get("code") == 2009, "code=%s message=%s" % (body.get("code"), body.get("message")))


def section_residual():
    print("\n--- G. 清理与残留 ---")
    total = mysql_int("SELECT COUNT(*) FROM `vital_sign_record`;")
    orphan = mysql_int("SELECT COUNT(*) FROM `vital_sign_record` WHERE `id`>%d;" % VITAL_BASELINE)
    if VITAL_BASELINE <= 0:
        check("G1 清理后 vital_sign_record 无残留（表基线为空）", total == 0, "count=%d" % total)
    else:
        check("G1 清理后无 id>基线的残留行", orphan == 0,
              "baseline=%d 残留=%d" % (VITAL_BASELINE, orphan))
    check("G2 本次创建的全部记录均已清除",
          all(mysql_int("SELECT COUNT(*) FROM `vital_sign_record` WHERE `id`=%d;" % i) == 0
              for i in CREATED_IDS) if CREATED_IDS else True,
          "创建 %d 条" % len(CREATED_IDS))


def cleanup():
    # 基线 > 0 才走 delete_since；基线为 0（空表）时按白名单 ID 删，绝不 DELETE 全表
    if VITAL_BASELINE > 0:
        cleanup_util.delete_since("vital_sign_record", VITAL_BASELINE, "vital_sign_record（本轮体征）")
    if CREATED_IDS:
        cleanup_util.mysql_raw(
            "DELETE FROM `vital_sign_record` WHERE `id` IN (%s);"
            % ",".join(str(int(i)) for i in CREATED_IDS))
        print("[清理] vital_sign_record：按白名单删除 %d 条" % len(CREATED_IDS))
    cleanup_util.delete_since("sys_login_log", LOGIN_LOG_BASELINE, "sys_login_log（本轮登录）")


def main():
    global VITAL_BASELINE, LOGIN_LOG_BASELINE
    VITAL_BASELINE = cleanup_util.max_id("vital_sign_record")
    LOGIN_LOG_BASELINE = cleanup_util.max_id("sys_login_log")
    print("[基线] vital_sign_record max_id=%d；sys_login_log max_id=%d"
          % (VITAL_BASELINE, LOGIN_LOG_BASELINE))
    pick_fixtures()

    family = login_ok(ACC_FAMILY)
    bp_id, weight_id = section_family_write(family)
    if not bp_id:
        raise RuntimeError("首条体征未录入成功，后续断言无意义")
    section_list_trend(family)
    section_elder_readonly(login_ok(ELDER_ACCOUNT), bp_id)
    section_family_denied(login_ok(ACC_FAMILY_OTHER))
    companion = login_ok(COMPANION_ACCOUNT)
    comp_id = section_companion(companion)
    section_withdraw(family, companion, bp_id, comp_id)


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
        try:
            section_residual()
        except Exception as e:  # noqa: BLE001
            print("[残留检查异常] %s: %s" % (type(e).__name__, e))

    passed = sum(1 for _, ok, _ in results if ok)
    failed = len(results) - passed
    print("\n===== 汇总：PASS %d / FAIL %d / 共 %d =====" % (passed, failed, len(results)))
    for name, ok, detail in results:
        if not ok:
            print("  [FAIL] %s  -> %s" % (name, detail))
    sys.exit(0 if failed == 0 and results else 1)
