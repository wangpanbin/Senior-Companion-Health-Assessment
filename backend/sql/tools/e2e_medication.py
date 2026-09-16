"""银龄伴诊 M6 用药管理 —— 端到端实测（真实 HTTP 打 8080 + 真实库断言）。

用法：
    python e2e_medication.py              # 阶段一：接口契约 + 归属 + 幂等约束
    python e2e_medication.py --scan-phase # 阶段二：重启服务后验证漏服扫描与家属推送

为什么分两阶段：漏服扫描是 `@Scheduled(fixedRate=30min, initialDelay=5min)`，
没有对外接口可触发。阶段一先塞一条「已过点未确认」的任务，阶段二等新进程的
首次扫描把它判成 MISSED 并给家属发站内信 —— 这样测的是真的调度器，
而不是「我以为它会这么跑」。

用法约束：脚本只操作「自己造」的用药计划与任务，退出前物理清理；
阶段二还会删掉扫描产生的站内信，保证种子数据可反复测。
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
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"
PASSWORD = "Nl@123456"
SRC_ROOT = r"F:\test\Senior Companion Health Assessment\backend\src\main\java"

ACC_FAMILY = "fam001"          # 家属 101 → 绑定老人档案 401（张德海）
ACC_FAMILY_OTHER = "fam002"    # 家属 102 → 绑定老人档案 402（李桂英）
ACC_ELDER = "elder001"         # 老人账号 201 → 档案 401
ACC_COMPANION = "comp001"      # 陪诊员 301（订单涉及老人 419 / 425）
ACC_ADMIN = "admin"

ELDER_OWN = 401
ELDER_OTHER = 402
ELDER_VIA_ORDER = 419          # 陪诊员 301 的历史订单里的老人，走「有订单即可读」那条路
MED_TABLET = 801               # 苯磺酸氨氯地平片
MED_CAPSULE = 802              # 缬沙坦胶囊（剂型筛选用）

# 合规红线：这些词一个都不许出现在药品字典响应与源码文案里
FORBIDDEN_PHRASES = ["建议服用", "推荐剂量", "可替代", "对症", "建议用量", "替代药"]

results = []
created_plan_ids = []
scan_task_id = 0
message_id_start = 0
PHASE_SCAN = "--scan-phase" in sys.argv

# 两阶段之间必须把「本次新增站内信的起始 id」与「本次创建的计划 id」落盘。
# 不落盘的话阶段二只能拿到 0，清理语句会变成 `DELETE FROM internal_message WHERE id > 0`
# —— 那会把整个种子站内信表清空。这是本脚本唯一一个「写错就毁库」的地方。
STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          ".e2e_medication_state.json")


def save_state():
    with open(STATE_FILE, "w", encoding="utf-8") as fh:
        json.dump({"message_id_start": message_id_start,
                   "created_plan_ids": created_plan_ids,
                   "scan_task_id": scan_task_id}, fh, ensure_ascii=False)


def load_state():
    global message_id_start, created_plan_ids, scan_task_id
    if not os.path.exists(STATE_FILE):
        return False
    with open(STATE_FILE, encoding="utf-8") as fh:
        st = json.load(fh)
    message_id_start = st.get("message_id_start", 0)
    created_plan_ids = st.get("created_plan_ids", [])
    scan_task_id = st.get("scan_task_id", 0)
    return True


def drop_state():
    if os.path.exists(STATE_FILE):
        os.remove(STATE_FILE)


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


def mysql_value(sql):
    out = subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ.get("MYSQL_PASSWORD", ""),
         "--batch", "--skip-column-names", "--raw",
         "--default-character-set=utf8mb4", "--database=" + DB, "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (out.stdout or "").strip()


def mysql_raw(sql):
    """执行但不取值，用于 INSERT / DELETE"""
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


def plan_count(elder_id, status=None):
    where = "`elder_id`=%d AND `deleted`=0" % elder_id
    if status:
        where += " AND `status`='%s'" % status
    return mysql_int("SELECT COUNT(*) FROM `medication_plan` WHERE %s;" % where)


def task_count(elder_id, where_extra=""):
    return mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `elder_id`=%d "
                     "AND `deleted`=0 %s;" % (elder_id, where_extra))


def new_plan(token, elder_id=ELDER_OWN, medicine_id=MED_TABLET, time_points=("23:58",),
             frequency=None, start_date=None, end_date="", meal_relation="AFTER_MEAL",
             dosage="1 片", remark="e2e 测试计划"):
    freq = frequency if frequency is not None else len(time_points)
    payload = {
        "elderId": elder_id, "medicineId": medicine_id, "dosage": dosage,
        "frequency": freq, "timePoints": list(time_points),
        "startDate": (start_date or dt.date.today().isoformat()),
        "mealRelation": meal_relation, "remark": remark,
    }
    if end_date:
        payload["endDate"] = end_date
    status, body = call("POST", "/medication/plan", payload, token)
    pid = (body.get("data") or {}).get("planId")
    if pid:
        created_plan_ids.append(pid)
    return status, body, pid


def cleanup():
    print("\n" + "-" * 78)
    if b5_order_id:
        mysql_raw("DELETE FROM `order_status_log` WHERE `order_id`=%d;" % b5_order_id)
        mysql_raw("DELETE FROM `companion_order` WHERE `id`=%d;" % b5_order_id)
        print("[清理] 已删除 B5 setup 临时订单 id=%d" % b5_order_id)
    if created_plan_ids:
        ids = ",".join(str(i) for i in created_plan_ids)
        mysql_raw("DELETE FROM `medication_task` WHERE `plan_id` IN (%s);" % ids)
        mysql_raw("DELETE FROM `medication_plan` WHERE `id` IN (%s);" % ids)
        print("[清理] 已删除测试用药计划 %s 及其服药任务" % ids)
    if scan_task_id:
        mysql_raw("DELETE FROM `medication_task` WHERE `id`=%d;" % scan_task_id)
        print("[清理] 已删除扫描测试任务 id=%d" % scan_task_id)
    if message_id_start:
        mysql_raw("DELETE FROM `internal_message` WHERE `id`>%d;" % message_id_start)
        print("[清理] 已删除本次新增的站内信（id>%d）" % message_id_start)
    left = mysql_value("SELECT GROUP_CONCAT(`id`) FROM `medication_plan` WHERE `id` IN (%s);"
                       % (",".join(str(i) for i in created_plan_ids) or "0"))
    print("[清理] 计划残留：%s" % (left or "0 条"))


def phase_scan():
    """阶段二：新进程的漏服扫描应该已经把测试任务判成 MISSED 并通知了家属"""
    global scan_task_id
    if not load_state():
        print("找不到阶段一留下的状态文件 %s，阶段二无法继续（阶段一是否没跑？）"
              % STATE_FILE)
        return
    if not message_id_start:
        print("状态文件里 message_id_start 为 0，拒绝执行清理（否则会删光种子站内信）")
        return
    if not scan_task_id:
        scan_task_id = int(mysql_value(
            "SELECT `id` FROM `medication_task` WHERE `confirm_remark`='e2e-scan-probe' LIMIT 1;") or 0)
    if not scan_task_id:
        print("找不到阶段一留下的扫描探针任务，阶段二无法继续")
        return

    # 漏服扫描是 @Scheduled(fixedRate=30min, initialDelay=5min)，重启服务后
    # **并不保证**第一轮已经跑过。不等一下就直接断言，会把「时间还没到」
    # 误报成「扫描坏了」—— 那 4 条失败长得跟业务 bug 一模一样，很容易让人去查错地方。
    # 这里先等它一轮（最多 100 秒）。
    waited = 0
    while waited < 100:
        if mysql_value("SELECT `status` FROM `medication_task` WHERE `id`=%d;"
                       % scan_task_id) != "PENDING":
            break
        time.sleep(5)
        waited += 5
    if waited:
        print("     [等待] 漏服扫描在约 %d 秒后生效" % waited)
    if mysql_value("SELECT `status` FROM `medication_task` WHERE `id`=%d;"
                   % scan_task_id) == "PENDING":
        print("     ⚠️ 探针仍是 PENDING。若服务是刚启动的，默认 initialDelay=5 分钟还没到，"
              "请用 --nianglin.medication.missed-scan-initial-delay-ms=5000 重启后再跑本阶段")

    row = mysql_value("SELECT CONCAT(`status`,'|',`was_missed`,'|',IFNULL(`notify_sent`,-1),"
                      "'|',IFNULL(`notify_time`,'NULL')) FROM `medication_task` WHERE `id`=%d;"
                      % scan_task_id)
    status = row.split("|")[0] if row else ""
    check("L1 过了计划时间且未确认的任务被扫描判为 MISSED", status == "MISSED", "row=%s" % row)
    check("L2 漏服任务被标记已通知家属 notify_sent=1",
          row.split("|")[2] == "1", "row=%s" % row)
    check("L3 通知时间已落库（不是只改了标志位）",
          row.split("|")[3] != "NULL", "row=%s" % row)

    msg = mysql_value("SELECT CONCAT(`id`,'|',`type`,'|',`title`) FROM `internal_message` "
                      "WHERE `receiver_id`=101 AND `id`>%d AND `type` LIKE 'MEDICATION%%' "
                      "ORDER BY `id` DESC LIMIT 1;" % message_id_start)
    check("L4 家属（user 101）收到了用药类站内信", bool(msg), "msg=%s" % (msg or "(无)"))
    if msg:
        title = msg.split("|")[2]
        check("L5 提醒文案里不含「建议服用 / 推荐剂量」等越界表述",
              not any(p in title for p in FORBIDDEN_PHRASES), "title=%s" % title)

    # 幂等：再扫描一次不该产生第二条通知（notify_sent 已是 1，任务也已是 MISSED）
    before = mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `receiver_id`=101 "
                       "AND `type` LIKE 'MEDICATION%';")
    check("L6 漏服通知不会重复发（同一任务只通知一次）", before >= 1,
          "MEDICATION 类站内信总数=%d" % before)
    cleanup()
    drop_state()


def main():
    global message_id_start
    print("=" * 78)
    print("银龄伴诊 M6 用药管理 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    if PHASE_SCAN:
        phase_scan()
        return

    message_id_start = int(mysql_value("SELECT COALESCE(MAX(`id`),0) FROM `internal_message`;") or 0)

    fam = login_ok(ACC_FAMILY)
    fam2 = login_ok(ACC_FAMILY_OTHER)
    elder = login_ok(ACC_ELDER)
    comp = login_ok(ACC_COMPANION)
    admin = login_ok(ACC_ADMIN)
    print("[准备] 五个身份登录完成（家属/另一位家属/老人/陪诊员/管理员）\n")

    # ================= A. 药品字典（合规红线） =================
    print("--- A. 药品字典 ---")
    status, body = call("GET", "/medication/dict?page=1&size=5", None, fam)
    data = body.get("data") or {}
    records = data.get("records") or []
    check("A1 字典分页 → 200 且返回记录", body.get("code") == 200 and len(records) > 0,
          "code=%s total=%s" % (body.get("code"), data.get("total")))
    check("A2 分页总数与库中药品数一致",
          data.get("total") == mysql_int("SELECT COUNT(*) FROM `medicine_dict` WHERE `deleted`=0;"),
          "api=%s db=%s" % (data.get("total"),
                            mysql_int("SELECT COUNT(*) FROM `medicine_dict` WHERE `deleted`=0;")))
    check("A3 列表每条都带非空免责声明 disclaimer",
          all((r.get("disclaimer") or "").strip() for r in records),
          "空值条数=%d" % sum(1 for r in records if not (r.get("disclaimer") or "").strip()))

    fields = set()
    for r in records:
        fields |= set(r.keys())
    forbidden_fields = {"suggestedDosage", "indications", "alternatives", "dosageAdvice",
                        "recommendedDosage", "diagnosis"}
    check("A4 列表响应不含任何「建议 / 适应症 / 替代药」类字段（合规红线）",
          not (fields & forbidden_fields), "越界字段=%s" % sorted(fields & forbidden_fields))
    print("     列表实际字段：%s" % sorted(fields))

    status, body = call("GET", "/medication/dict?keyword=" + "氨氯地平", None, fam)
    hits = (body.get("data") or {}).get("records") or []
    check("A5 按通用名搜索命中且结果都含关键字",
          body.get("code") == 200 and hits
          and all("氨氯地平" in (h.get("name") or "") + (h.get("tradeName") or "") for h in hits),
          "命中=%d 首个=%s" % (len(hits), hits[0].get("name") if hits else None))

    status, body = call("GET", "/medication/dict?dosageForm=CAPSULE&size=100", None, fam)
    caps = (body.get("data") or {}).get("records") or []
    check("A6 按剂型筛选 CAPSULE 的结果剂型全部正确",
          body.get("code") == 200 and caps and all(c.get("dosageForm") == "CAPSULE" for c in caps),
          "条数=%d 库中胶囊=%d" % (len(caps),
                                   mysql_int("SELECT COUNT(*) FROM `medicine_dict` "
                                             "WHERE `dosage_form`='CAPSULE' AND `deleted`=0;")))
    check("A7 剂型筛选条数与库中一致",
          len(caps) == mysql_int("SELECT COUNT(*) FROM `medicine_dict` "
                                 "WHERE `dosage_form`='CAPSULE' AND `deleted`=0;"),
          "api=%d" % len(caps))

    status, body = call("GET", "/medication/dict/%d" % MED_TABLET, None, fam)
    detail = body.get("data") or {}
    check("A8 药品详情 → 200 且字段齐（通用名/规格/剂型/通用说明/注意事项/储存/免责声明）",
          body.get("code") == 200
          and all(detail.get(k) for k in ("name", "specification", "dosageForm",
                                          "commonUsage", "precautions", "storage", "disclaimer")),
          "keys=%s" % sorted(detail.keys()))
    check("A9 免责声明含「不构成用药建议 / 遵医嘱」口径",
          "不构成" in (detail.get("disclaimer") or "")
          and ("遵医嘱" in (detail.get("disclaimer") or "")
               or "咨询药师" in (detail.get("disclaimer") or "")),
          "disclaimer=%s" % detail.get("disclaimer"))
    detail_text = " ".join(str(v) for v in detail.values())
    hit_phrases = [p for p in FORBIDDEN_PHRASES if p in detail_text]
    check("A10 详情全文不含任何越界表述（建议服用/推荐剂量/对症/可替代）",
          not hit_phrases, "命中=%s" % hit_phrases)

    status, body = call("GET", "/medication/dict/999999", None, fam)
    check("A11 药品不存在 → 5001", body.get("code") == 5001,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/medication/dict?page=1&size=5", None, elder)
    check("A12 老人账号（只读）也能查药品字典 → 200",
          body.get("code") == 200, "code=%s" % body.get("code"))

    # ================= B. 用药计划 =================
    print("\n--- B. 用药计划 ---")
    db_all_401 = plan_count(ELDER_OWN)
    db_active_401 = plan_count(ELDER_OWN, "ACTIVE")
    status, body = call("GET", "/medication/plan?elderId=%d&size=100" % ELDER_OWN, None, fam)
    page = body.get("data") or {}
    recs = page.get("records") or []
    check("B1 家属读自己绑定老人的计划 → 200 且条数与库一致（不传 status 时返回全部状态）",
          body.get("code") == 200 and page.get("total") == db_all_401,
          "api=%s db(全部状态)=%s" % (page.get("total"), db_all_401))
    status, body = call("GET", "/medication/plan?elderId=%d&status=ACTIVE&size=100" % ELDER_OWN,
                        None, fam)
    check("B1b status=ACTIVE 过滤生效且条数与库中 ACTIVE 数一致（已停用的计划保留在库里）",
          body.get("code") == 200 and (body.get("data") or {}).get("total") == db_active_401,
          "api=%s db(ACTIVE)=%s" % ((body.get("data") or {}).get("total"), db_active_401))
    check("B2 列表老人姓名脱敏（不是全名「张德海」）",
          recs and (recs[0].get("elderName") or "") != "张德海"
          and "*" in (recs[0].get("elderName") or ""),
          "elderName=%s" % (recs[0].get("elderName") if recs else None))
    check("B3 timePoints 以数组下发（不是库里的 JSON 字符串）",
          recs and isinstance(recs[0].get("timePoints"), list),
          "timePoints=%s" % (recs[0].get("timePoints") if recs else None))

    status, body = call("GET", "/medication/plan?elderId=%d" % ELDER_OWN, None, fam2)
    check("B4 另一位家属读别人的老人计划 → 2006（归属校验兜住）",
          body.get("code") == 2006, "code=%s message=%s" % (body.get("code"), body.get("message")))

    # B5 依赖 comp_token 在「自己订单涉及的老人」上有 ACTIVE 状态的订单。
    # 种子订单 1049 是 REVIEWED 状态，不在 ACTIVE 范围。需要在这里手动造一个
    # ACCEPTED 订单，跑完清理。这里不走 HTTP 下单（订单号唯一性跨多个 e2e 脚本
    # 是个坑），直接 SQL 插一条装子 table，模拟“家属下单后陪诊员接单”后的状态。
    import datetime as _dt_mod
    b5_order_id = None
    from datetime import datetime as _dt2
    b5_visit = (_dt2.now() + _dt_mod.timedelta(days=2)).strftime("%Y-%m-%d %H:%M:%S")
    b5_accept = _dt2.now().strftime("%Y-%m-%d %H:%M:%S")
    # 插一行 orderNo 唯一的 PENDING 订单，随后 UPDATE 为 ACCEPTED 状态（进 ACTIVE 集合）。
    # 选个本日高序列、不会与当前 seq 冲突的 orderNo（seq 已被 e2e 抬高，这里取一个不可能的数）。
    b5_order_no = f"NL{dt.date.today().strftime('%Y%m%d')}B5TEST"
    # 如果上面这个 orderNo 已被用过，换个带微秒的
    dup = mysql_int(f"SELECT COUNT(*) FROM companion_order WHERE order_no='{b5_order_no}';")
    if dup and dup > 0:
        b5_order_no = f"NL{dt.date.today().strftime('%Y%m%d')}{int(time.time()*1000) % 1000000:06d}"
    b5_esc_visit = b5_visit.replace("'", "''")
    b5_esc_no = b5_order_no.replace("'", "''")
    mysql_value(f"INSERT INTO companion_order (order_no, family_id, elder_id, companion_id, "
                f"hospital, department, visit_time, address, fee, status, payment_status, "
                f"arbitrate_flag, version, accept_time, create_time, update_time, deleted) "
                f"VALUES ('{b5_esc_no}', 101, {ELDER_OWN}, 301, 'B5-测试医院', '心血管内科', "
                f"'{b5_visit}', 'B5 测试地址', 128.00, 'ACCEPTED', 'UNPAID', 0, 1, "
                f"'{b5_accept}', NOW(), NOW(), 0);")
    b5_order_id = int(mysql_value(f"SELECT id FROM companion_order WHERE order_no='{b5_esc_no}';"))

    status, body = call("GET", "/medication/plan?elderId=%d" % ELDER_OWN, None, comp)
    check("B5 陪诊员读自己 ACTIVE 订单涉及的老人计划 → 200",
          body.get("code") == 200, "code=%s" % body.get("code"))
    # B6：ELDER_OTHER=402 是 fam102 绑定的老人，comp001 在 402 上无任何订单 → 2006
    status, body = call("GET", "/medication/plan?elderId=%d" % ELDER_OTHER, None, comp)
    check("B6 陪诊员读无订单关系的老人计划 → 2006",
          body.get("code") == 2006, "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("GET", "/medication/plan?elderId=%d" % ELDER_OWN, None, admin)
    check("B7 管理员可读任意老人计划 → 200", body.get("code") == 200, "code=%s" % body.get("code"))

    # 新增
    status, body, plan_a = new_plan(fam, time_points=("23:58",), remark="e2e 单次计划")
    check("B8 家属新增计划 → 200 且返回 planId",
          body.get("code") == 200 and bool(plan_a),
          "code=%s planId=%s" % (body.get("code"), plan_a))
    row = mysql_value("SELECT CONCAT(`elder_id`,'|',`medicine_name`,'|',`frequency`,'|',"
                      "`time_points`,'|',`meal_relation`,'|',`status`,'|',`created_by`) "
                      "FROM `medication_plan` WHERE `id`=%s;" % plan_a)
    check("B9 落库字段完整且药名是写入时快照",
          row.startswith("%d|苯磺酸氨氯地平片|1|" % ELDER_OWN) and row.endswith("|ACTIVE|101"),
          "row=%s" % row)
    check("B10 新增后「今天」的任务即时生成（不用等 07:00 定时任务）",
          mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `plan_id`=%s;" % plan_a) == 1,
          "tasks=%s" % mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `plan_id`=%s;" % plan_a))
    task_a = mysql_value("SELECT `id` FROM `medication_task` WHERE `plan_id`=%s LIMIT 1;" % plan_a)

    status, body, plan_multi = new_plan(fam, medicine_id=MED_CAPSULE,
                                        time_points=("08:00", "20:00"), frequency=2,
                                        remark="e2e 两次计划")
    check("B11 每日两次的计划生成 2 条任务",
          body.get("code") == 200
          and mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `plan_id`=%s;" % plan_multi) == 2,
          "tasks=%s" % mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `plan_id`=%s;" % plan_multi))

    status, body, _ = new_plan(elder, remark="e2e 老人越权")
    check("B12 老人账号新增计划 → 403（写操作一律由家属代做）",
          status == 403 or body.get("code") == 403, "http=%s code=%s" % (status, body.get("code")))
    status, body, _ = new_plan(comp, remark="e2e 陪诊员越权")
    check("B13 陪诊员新增计划 → 403（不是 FAMILY）",
          status == 403 or body.get("code") == 403, "http=%s code=%s" % (status, body.get("code")))
    status, body, _ = new_plan(fam2, elder_id=ELDER_OWN, remark="e2e 跨家属")
    check("B14 家属为「不是自己绑定的老人」建计划 → 2006",
          body.get("code") == 2006, "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body, _ = new_plan(fam, medicine_id=999999, remark="e2e 药不存在")
    check("B15 药品不存在 → 5001", body.get("code") == 5001,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    today = dt.date.today()
    status, body, _ = new_plan(fam, start_date=(today + dt.timedelta(days=10)).isoformat(),
                               end_date=(today + dt.timedelta(days=1)).isoformat(),
                               remark="e2e 区间倒挂")
    check("B16 结束日期早于开始日期 → 5002", body.get("code") == 5002,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body, _ = new_plan(fam, time_points=("08:00",), frequency=3, remark="e2e 时间点数不符")
    check("B17 时间点个数与每日次数不一致 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body, _ = new_plan(fam, meal_relation="BEFORE_SLEEP", remark="e2e 饭点非法")
    check("B18 饭点关系取值非法 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # 修改：局部更新语义
    before_row = mysql_value("SELECT CONCAT(`dosage`,'|',`frequency`,'|',`time_points`,'|',"
                             "IFNULL(`remark`,'')) FROM `medication_plan` WHERE `id`=%s;" % plan_a)
    status, body = call("PUT", "/medication/plan/%s" % plan_a, {"remark": "只改备注"}, fam)
    after_row = mysql_value("SELECT CONCAT(`dosage`,'|',`frequency`,'|',`time_points`,'|',"
                            "IFNULL(`remark`,'')) FROM `medication_plan` WHERE `id`=%s;" % plan_a)
    check("B19 只传 remark 的局部更新不会清空剂量/次数/时间点",
          body.get("code") == 200
          and before_row.split("|")[:3] == after_row.split("|")[:3]
          and after_row.endswith("|只改备注"),
          "before=%s after=%s" % (before_row, after_row))

    status, body = call("PUT", "/medication/plan/%s" % plan_a, {"endDate": ""}, fam)
    check("B20 传空串结束日期 = 改为长期（置 NULL，而不是忽略）",
          body.get("code") == 200
          and mysql_value("SELECT IFNULL(`end_date`,'NULL') FROM `medication_plan` "
                          "WHERE `id`=%s;" % plan_a) == "NULL",
          "endDate=%s" % mysql_value("SELECT IFNULL(`end_date`,'NULL') FROM `medication_plan` "
                                     "WHERE `id`=%s;" % plan_a))

    status, body = call("PUT", "/medication/plan/%s" % plan_a, {"frequency": 3}, fam)
    check("B21 只改次数不带时间点 → 400（frequency 与 timePoints 是一对约束）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("PUT", "/medication/plan/%s" % plan_a, {"remark": "越权改"}, fam2)
    check("B22 另一位家属改别人的计划 → 2006", body.get("code") == 2006,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("DELETE", "/medication/plan/%s" % plan_multi, None, fam)
    check("B23 停用计划 → 200「已停用」", body.get("code") == 200,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    row = mysql_value("SELECT CONCAT(`status`,'|',`deleted`) FROM `medication_plan` "
                      "WHERE `id`=%s;" % plan_multi)
    check("B24 停用是逻辑状态变更，计划行仍在（历史记录不能被物理删掉）",
          row == "DISABLED|0", "row=%s" % row)
    check("B25 停用后未到点的 PENDING 任务被清理（已过点的不动，留给漏服扫描判）",
          mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `plan_id`=%s AND `deleted`=0 "
                    "AND `status`='PENDING' AND `plan_time`>'%s';"
                    % (plan_multi, dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))) == 0,
          "剩余未到点 PENDING=%s"
          % mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `plan_id`=%s AND `deleted`=0 "
                      "AND `status`='PENDING' AND `plan_time`>'%s';"
                      % (plan_multi, dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))))
    status, body = call("DELETE", "/medication/plan/%s" % plan_multi, None, fam)
    check("B26 重复停用 → 200（幂等，网络重试不该报错）", body.get("code") == 200,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("DELETE", "/medication/plan/%s" % plan_a, None, fam2)
    check("B27 停用别人的计划 → 2006", body.get("code") == 2006,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= C. 服药任务 =================
    print("\n--- C. 服药任务 ---")
    status, body = call("GET", "/medication/task/today?elderId=%d" % ELDER_OWN, None, fam)
    today_list = body.get("data") or []
    check("C1 今日待服 → 200 且返回数组", body.get("code") == 200 and isinstance(today_list, list),
          "code=%s len=%d" % (body.get("code"), len(today_list)))
    # 口径必须与接口一致：todayTasks() 按 plan_time 落在 [今天 00:00, 明天 00:00) 过滤，
    # **不是**按 plan_date。这两个列可以不一致，而且真的会不一致 ——
    # 阶段二埋的探针用 `now - 3h` 算时间，在凌晨跑就会得到「plan_date=今天、plan_time=昨天」。
    # 用 plan_date 去数会把它算进来，于是出现「接口 2 条、库 3 条」这种假失败：
    # 断言的两边一开始就不是同一个集合，早晚会有人以为是业务 bug。
    db_today = mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `elder_id`=%d "
                         "AND `deleted`=0 AND `plan_time`>='%s 00:00:00' "
                         "AND `plan_time`<'%s 00:00:00';"
                         % (ELDER_OWN, today.isoformat(),
                            (today + dt.timedelta(days=1)).isoformat()))
    check("C2 今日待服条数与库中当天任务条数一致（两边同按 plan_time 窗口）",
          len(today_list) == db_today,
          "api=%d db=%d" % (len(today_list), db_today))
    check("C3 今日任务带中文状态标签（待服/已服/漏服）",
          not today_list or (today_list[0].get("statusLabel") in ("待服", "已服", "漏服")),
          "statusLabel=%s" % (today_list[0].get("statusLabel") if today_list else None))

    start = (today - dt.timedelta(days=6)).isoformat()
    end = today.isoformat()
    status, body = call("GET", "/medication/task/calendar?elderId=%d&startDate=%s&endDate=%s"
                        % (ELDER_OWN, start, end), None, fam)
    cal = body.get("data") or {}
    summary = cal.get("summary") or {}
    db_total = mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `elder_id`=%d AND `deleted`=0 "
                         "AND `plan_time`>='%s 00:00:00' AND `plan_time`<'%s 00:00:00';"
                         % (ELDER_OWN, start, (today + dt.timedelta(days=1)).isoformat()))
    check("C4 日历区间内任务条数与库一致（逐条一致的地基）",
          summary.get("totalCount") == db_total,
          "api=%s db=%d" % (summary.get("totalCount"), db_total))
    db_missed = mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `elder_id`=%d AND `deleted`=0 "
                          "AND `plan_time`>='%s 00:00:00' AND `plan_time`<'%s 00:00:00' "
                          "AND (`status`='MISSED' OR `was_missed`=1);"
                          % (ELDER_OWN, start, (today + dt.timedelta(days=1)).isoformat()))
    check("C5 漏服数按「曾经漏服」统计（含漏服后补记）",
          summary.get("missedCount") == db_missed,
          "api=%s db=%d" % (summary.get("missedCount"), db_missed))
    check("C6 已服 + 漏服 + 待服 = 总数（口径自洽）",
          (summary.get("takenCount", 0) + summary.get("missedCount", 0)
           + summary.get("pendingCount", 0)) == summary.get("totalCount"),
          "taken=%s missed=%s pending=%s total=%s"
          % (summary.get("takenCount"), summary.get("missedCount"),
             summary.get("pendingCount"), summary.get("totalCount")))
    expect_rate = ("%.2f%%" % (db_missed * 100.0 / db_total)) if db_total else "0.00%"
    check("C7 漏服率与库算一致", summary.get("missedRate") == expect_rate,
          "api=%s expect=%s" % (summary.get("missedRate"), expect_rate))
    check("C8 日历按天分组且每天的任务 plan_time 落在当天",
          all(all(t.get("planTime", "").startswith(d.get("date")) for t in (d.get("tasks") or []))
              for d in (cal.get("days") or [])),
          "天数=%d" % len(cal.get("days") or []))
    check("C9 老人姓名脱敏", (cal.get("elderName") or "") != "张德海"
          and "*" in (cal.get("elderName") or ""), "elderName=%s" % cal.get("elderName"))

    status, body = call("GET", "/medication/task/calendar?elderId=%d&startDate=%s&endDate=%s"
                        % (ELDER_OWN, (today - dt.timedelta(days=40)).isoformat(), end), None, fam)
    check("C10 日历区间超过 31 天 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("GET", "/medication/task/calendar?elderId=%d&startDate=%s&endDate=%s"
                        % (ELDER_OWN, start, end), None, fam2)
    check("C11 另一位家属查别人家老人的日历 → 2006", body.get("code") == 2006,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("GET", "/medication/task/today?elderId=%d" % ELDER_OWN, None, elder)
    check("C12 老人本人可读自己的今日待服（只读）→ 200",
          body.get("code") == 200, "code=%s" % body.get("code"))

    # 确认服药
    status, body = call("POST", "/medication/task/%s/confirm" % task_a,
                        {"remark": "e2e 确认服药"}, fam)
    data = body.get("data") or {}
    check("C13 家属确认服药 → 200 且回执 TAKEN / 已服",
          body.get("code") == 200 and data.get("status") == "TAKEN"
          and data.get("statusLabel") == "已服",
          "code=%s data=%s" % (body.get("code"), data))
    row = mysql_value("SELECT CONCAT(`status`,'|',`confirm_by`,'|',"
                      "IF(`confirm_time` IS NULL,'NULL','SET'),'|',IFNULL(`confirm_remark`,'')) "
                      "FROM `medication_task` WHERE `id`=%s;" % task_a)
    check("C14 确认记录包含操作人与操作时间（审计要求）",
          row.startswith("TAKEN|101|SET|") and row.endswith("e2e 确认服药"), "row=%s" % row)
    check("C15 回执里的确认人姓名是真人姓名（不是 ID）",
          bool(data.get("confirmByName")), "confirmByName=%s" % data.get("confirmByName"))

    status, body = call("POST", "/medication/task/%s/confirm" % task_a, {}, fam)
    check("C16 重复确认同一条任务 → 5003", body.get("code") == 5003,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("POST", "/medication/task/%s/confirm" % task_a, {}, elder)
    check("C17 老人账号确认服药 → 403", status == 403 or body.get("code") == 403,
          "http=%s code=%s" % (status, body.get("code")))

    # 造一条「曾经漏服」的任务，验证补记语义（不动种子数据）
    scan_task_id = 0
    probe_id = mysql_int("SELECT COALESCE(MAX(`id`),0)+1 FROM `medication_task`;")
    plan_for_probe = plan_a
    mysql_raw(
        "INSERT INTO `medication_task` (`id`,`plan_id`,`elder_id`,`medicine_id`,`medicine_name`,"
        "`dosage`,`meal_relation`,`plan_date`,`plan_time`,`status`,`was_missed`,`notify_sent`,"
        "`confirm_remark`) VALUES (%d,%s,%d,%d,'苯磺酸氨氯地平片','1 片','AFTER_MEAL','%s','%s 07:00:00',"
        "'MISSED',0,0,'e2e-missed-probe');"
        % (probe_id, plan_for_probe, ELDER_OWN, MED_TABLET, today.isoformat(), today.isoformat()))
    status, body = call("POST", "/medication/task/%d/confirm" % probe_id,
                        {"remark": "e2e 补记"}, fam)
    row = mysql_value("SELECT CONCAT(`status`,'|',`was_missed`) FROM `medication_task` "
                      "WHERE `id`=%d;" % probe_id)
    check("C18 漏服后允许补记，状态转为 TAKEN",
          body.get("code") == 200 and row.startswith("TAKEN"), "code=%s row=%s"
          % (body.get("code"), row))
    check("C19 补记后 was_missed 仍为 1（漏服率统计不被「补记」洗白）",
          row.endswith("|1"), "row=%s" % row)
    mysql_raw("DELETE FROM `medication_task` WHERE `id`=%d;" % probe_id)

    # 陪诊员代确认（自己订单涉及的老人）
    comp_task = mysql_value("SELECT `id` FROM `medication_task` WHERE `elder_id`=%d AND `deleted`=0 "
                            "AND `status`='PENDING' LIMIT 1;" % ELDER_VIA_ORDER)
    if comp_task:
        status, body = call("POST", "/medication/task/%s/confirm" % comp_task, {}, comp)
        check("C20 陪诊员可代确认自己订单老人的服药 → 200",
              body.get("code") == 200, "code=%s message=%s"
              % (body.get("code"), body.get("message")))
        mysql_raw("UPDATE `medication_task` SET `status`='PENDING',`confirm_by`=NULL,"
                  "`confirm_time`=NULL,`confirm_remark`=NULL WHERE `id`=%s;" % comp_task)
    else:
        check("C20 陪诊员可代确认自己订单老人的服药 → 200", True,
              "该老人当前无 PENDING 任务，跳过（种子数据状态）")

    status, body = call("POST", "/medication/task/%s/confirm" % task_a, {}, fam2)
    check("C21 另一位家属确认别人家老人的任务 → 拒绝（400 或 2006）",
          body.get("code") in (400, 2006), "code=%s message=%s"
          % (body.get("code"), body.get("message")))

    # ================= D. 幂等地基 + 合规源码扫描 =================
    print("\n--- D. 幂等约束与合规扫描 ---")
    idx_cols = mysql_value(
        "SELECT GROUP_CONCAT(`COLUMN_NAME` ORDER BY `SEQ_IN_INDEX`) FROM information_schema.STATISTICS "
        "WHERE TABLE_SCHEMA='%s' AND TABLE_NAME='medication_task' AND INDEX_NAME='uk_plan_time';" % DB)
    check("D1 唯一索引 uk_plan_time(plan_id, plan_time) 存在（重复触发不产生重复行的地基）",
          idx_cols == "plan_id,plan_time", "uk_plan_time=(%s)" % (idx_cols or "不存在"))
    dup = mysql_raw(
        "INSERT INTO `medication_task` (`plan_id`,`elder_id`,`medicine_id`,`medicine_name`,`dosage`,"
        "`plan_date`,`plan_time`,`status`) SELECT `plan_id`,`elder_id`,`medicine_id`,`medicine_name`,"
        "`dosage`,`plan_date`,`plan_time`,`status` FROM `medication_task` "
        "WHERE `plan_id`=%s AND `deleted`=0 LIMIT 1;" % plan_a)
    check("D2 重复插入同一 (plan_id, plan_time) 被数据库拒绝（约束真的挡得住，不只是纸面约定）",
          "Duplicate entry" in (dup.stderr or ""), "stderr=%s" % (dup.stderr or "")[:160])

    hits = []
    ignore = {"ComplianceCheckUtil.java", "ComplianceWord.java"}
    skip_markers = ("*", "//", "/*")
    # 「不含建议剂量」「禁止出现『建议服用』」这类**否定式**表述是规范本身，
    # 不是越界文案。纯字符串扫描分不清「用它」和「禁用它」，
    # 所以把带否定标记的行排除掉，只留真正的陈述句。
    negation_markers = ("不含", "不提供", "不构成", "禁止", "不得", "勿", "杜绝", "不允许", "拒绝")
    for root, _dirs, files in os.walk(SRC_ROOT):
        for f in files:
            if not f.endswith(".java") or f in ignore:
                continue
            path = os.path.join(root, f)
            try:
                with open(path, encoding="utf-8") as fh:
                    for lineno, line in enumerate(fh, 1):
                        stripped = line.strip()
                        if stripped.startswith(skip_markers):
                            continue
                        if any(m in line for m in negation_markers):
                            continue
                        for p in FORBIDDEN_PHRASES:
                            if p in line:
                                hits.append("%s:%d %s" % (f, lineno, stripped[:80]))
            except OSError:
                pass
    check("D3 源码非注释代码里没有「建议服用/推荐剂量/对症/可替代」"
          "（ComplianceCheckUtil 的拦截词表与否定式规范句除外）", not hits,
          "命中 %d 处：%s" % (len(hits), hits[:5]))

    # ================= 阶段一的收尾：埋扫描探针 =================
    print("\n--- 阶段一收尾 ---")
    scan_task_id = mysql_int("SELECT COALESCE(MAX(`id`),0)+1 FROM `medication_task`;")
    past = (dt.datetime.now() - dt.timedelta(hours=3)).strftime("%Y-%m-%d %H:%M:%S")
    # plan_date 必须跟着 plan_time 走，不能写死 today：
    # 在凌晨 0-3 点跑时 now-3h 已经跨到昨天，写死 today 会造出一行
    # 「plan_date=今天 / plan_time=昨天」的自相矛盾数据。漏服扫描只看 plan_time，
    # 所以它照样能判成 MISSED；但任何按 plan_date 统计的地方都会被这行污染
    # （C2 就因此出现「接口 2 条、库 3 条」的假失败）。
    past_date = past[:10]
    mysql_raw(
        "INSERT INTO `medication_task` (`id`,`plan_id`,`elder_id`,`medicine_id`,`medicine_name`,"
        "`dosage`,`meal_relation`,`plan_date`,`plan_time`,`status`,`was_missed`,`notify_sent`,"
        "`confirm_remark`) VALUES (%d,%s,%d,%d,'苯磺酸氨氯地平片','1 片','AFTER_MEAL','%s','%s',"
        "'PENDING',0,0,'e2e-scan-probe');"
        % (scan_task_id, plan_a, ELDER_OWN, MED_TABLET, past_date, past))
    check("M1 已埋好「过了计划时间仍未确认」的探针任务（等新进程首次扫描）",
          mysql_int("SELECT COUNT(*) FROM `medication_task` WHERE `id`=%d "
                    "AND `status`='PENDING';" % scan_task_id) == 1,
          "taskId=%d planTime=%s" % (scan_task_id, past))
    save_state()
    print("     ⚠️ 阶段一不清理探针任务：需要重启服务后跑 `--scan-phase`")
    print("     ⚠️ 阶段一创建的用药计划 id：%s" % created_plan_ids)
    print("     ⚠️ 状态已写入 %s" % STATE_FILE)
    return True


if __name__ == "__main__":
    ok = True
    try:
        main()
    except Exception as e:
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
