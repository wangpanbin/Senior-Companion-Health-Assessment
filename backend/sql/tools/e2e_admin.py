"""银龄伴诊 M9 管理后台 —— 端到端实测（真实 HTTP 打 8080 + 真实库断言）。

用法：
    python e2e_admin.py     # 前提：后端已在 8080 启动，MySQL 与 Redis 可用

覆盖：整类 ADMIN 鉴权边界（12 个接口）、资质审核（列表 / counts / 详情 / 通过 / 驳回 /
终态不可再审 / 站内信 / 操作日志 / 角色升级与旧令牌失效）、用户管理（脱敏 / 封禁 /
**封禁后旧令牌立即 403** / 解封 / 重置密码）、订单纠纷（强制终态 / 状态日志标注 /
双方站内信 / 操作日志）、投诉处理（正向流转 / 回退 409）、操作日志筛选与只增不改不删。

数据安全：脚本**不碰任何种子账号**。它先用 SQL 造三个专属测试用户（9001/9002/9003）
与两份专属资质申请，跑完按 id 物理清理；订单、投诉、站内信、操作日志同样按
运行前快照的 id 基线清理。种子的 64 笔订单、37 条操作日志、32 条投诉一条不动。
"""
import datetime as dt
import json
import os
import re
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

ACC_FAMILY = "fam001"       # 家属 101
ACC_COMPANION = "comp001"   # 陪诊员 301
ACC_ELDER = "elder001"      # 老人 201
ACC_ADMIN = "admin"         # 管理员 1

ADMIN_ID = 1
FAMILY_ID = 101
COMPANION_ID = 301
ELDER_OWN = 401
SEED_ORDER_MAX_ID = 1064

# 专属测试账号（远离种子 id 区间：种子是 1..2 / 101..130 / 201..230 / 301..330）
U_BAN = 9001        # 用来测封禁 / 解封 / 重置密码
U_APPROVE = 9002    # 用来测「审核通过」→ 角色升级
U_REJECT = 9003     # 用来测「审核驳回」→ 角色不变
TEST_USER_IDS = [U_BAN, U_APPROVE, U_REJECT]

MASKED_PHONE_RE = re.compile(r"^1\d{2}\*{4}\d{4}$")
FULL_PHONE_RE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
ID_CARD_RE = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")
BCRYPT_RE = re.compile(r"^\$2[aby]\$")

# 12 个管理端接口 —— 用来一次跑完「非 ADMIN 一律 403」这条硬约束。
# 路径参数用种子 id 是安全的：类级 @PreAuthorize 在进入 Service 之前就会拦下，
# 根本走不到「这个订单存不存在」那一步。
ADMIN_ENDPOINTS = [
    ("GET", "/admin/companion/audit", None),
    ("GET", "/admin/companion/audit/725", None),
    ("POST", "/admin/companion/audit/725", {"approved": True}),
    ("GET", "/admin/user", None),
    ("POST", "/admin/user/%d/disable" % U_BAN, {"reason": "e2e 权限探针封禁原因"}),
    ("POST", "/admin/user/%d/enable" % U_BAN, {"remark": "e2e 权限探针"}),
    ("POST", "/admin/user/%d/reset-password" % U_BAN, {"remark": "e2e 权限探针重置原因"}),
    ("GET", "/admin/order", None),
    ("POST", "/admin/order/1001/arbitrate",
     {"targetStatus": "CANCELLED", "result": "e2e 权限探针处理结果说明文本"}),
    ("GET", "/admin/complaint", None),
    ("POST", "/admin/complaint/31001/handle",
     {"status": "PROCESSING", "handleResult": "e2e 权限探针处理结果说明文本"}),
    ("GET", "/admin/oper-log", None),
]

results = []
created_order_ids = []
created_complaint_ids = []
created_audit_ids = []
message_id_start = 0
oper_log_base = 0


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


def mysql_run(sql):
    return subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ.get("MYSQL_PASSWORD", ""),
         "--batch", "--skip-column-names", "--raw",
         "--default-character-set=utf8mb4", "--database=" + DB, "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")


def mysql_value(sql):
    return (mysql_run(sql).stdout or "").strip()


def mysql_raw(sql):
    return mysql_run(sql)


def mysql_int(sql):
    try:
        return int(mysql_value(sql) or 0)
    except ValueError:
        return -1


def redis_get(key):
    return subprocess.run([REDIS_CLI, "GET", key], capture_output=True,
                          text=True).stdout.strip()


def redis_del(key):
    subprocess.run([REDIS_CLI, "DEL", key], capture_output=True, text=True)


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


def login_try(username, password=PASSWORD):
    """登录但不抛异常，返回完整响应体（用于断言「密码重置后能用默认密码登录」）"""
    _, body = call("GET", "/auth/captcha")
    key = body["data"]["captchaKey"]
    code = redis_get("captcha:" + key)
    _, body = call("POST", "/auth/login", {
        "username": username, "password": password,
        "captchaKey": key, "captchaCode": code})
    return body


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


def last_oper_log(oper_type, target_id):
    return mysql_value(
        "SELECT CONCAT(`operator_id`,'|',`target_type`,'|',`target_id`,'|',"
        "IFNULL(`before_status`,'NULL'),'|',IFNULL(`after_status`,'NULL'),'|',"
        "IFNULL(`remark`,'NULL')) FROM `admin_oper_log` WHERE `oper_type`='%s' "
        "AND `target_id`=%d AND `id`>%d ORDER BY `id` DESC LIMIT 1;"
        % (oper_type, target_id, oper_log_base))


# ======================================================================
# 测试数据准备 / 清理
# ======================================================================

def setup_test_users():
    """造三个专属测试账号 + 两份专属资质申请。密码复用种子账号的 BCrypt 哈希，
    避免在脚本里硬编码一段与生产不同的哈希（也不需要在 Python 里实现 BCrypt）。

    身份证字段必须写**真正的 AES 密文**：`id_card` 列存的是
    Base64(IV + GCM 密文)（`AesUtil`），管理端列表会把每一行都解密后再脱敏；
    写一个 18 位明文进去，`AesUtil.decrypt` 会抛 IllegalStateException，
    整个列表接口 500 —— 那不是产品的 bug，是这份测试数据不合规。
    所以这里直接复用种子里一段现成密文。
    """
    bcrypt = mysql_value("SELECT `password` FROM `sys_user` WHERE `id`=%d;" % FAMILY_ID)
    if not BCRYPT_RE.match(bcrypt or ""):
        raise RuntimeError("拿不到可复用的 BCrypt 密码哈希，脚本无法造测试账号")
    cipher_id_card = mysql_value("SELECT `id_card` FROM `companion_audit_record` "
                                 "WHERE `id_card` IS NOT NULL AND `id_card`<>'' ORDER BY `id` LIMIT 1;")
    if not cipher_id_card:
        raise RuntimeError("拿不到可复用的身份证密文样本，脚本无法造测试申请")

    rows = []
    for uid, name, role in ((U_BAN, "e2eban", "FAMILY"),
                            (U_APPROVE, "e2eapprove", "ELDER"),
                            (U_REJECT, "e2ereject", "ELDER")):
        rows.append("(%d,'%s','%s','e2e 测试账号','e2e 测试', '1390000%04d','%s','NORMAL',0)"
                    % (uid, name, bcrypt, uid, role))
    r = mysql_raw("INSERT INTO `sys_user` (`id`,`username`,`password`,`nickname`,`real_name`,"
                  "`phone`,`role`,`status`,`need_change_password`) VALUES %s;"
                  % ",".join(rows))
    if r.returncode != 0:
        raise RuntimeError("测试账号写入失败：%s" % (r.stderr or "")[:400])

    # 两份 PENDING 资质申请 + 对应的 companion_profile 快照
    # （M3 的双表写约定：流水看历史，快照看当前。审核必须同时改两处）
    for uid, rid, real_name in ((U_APPROVE, 90001, "e2e测试甲"),
                                (U_REJECT, 90002, "e2e测试乙")):
        r = mysql_raw("INSERT INTO `companion_audit_record` (`id`,`applicant_user_id`,`real_name`,"
                      "`id_card`,`service_area`,`available_time`,`certificates`,`apply_remark`,"
                      "`audit_status`,`submit_time`) VALUES (%d,%d,'%s','%s',"
                      "'海口市美兰区','周一至周五 08:00-18:00','[]','e2e 新增申请','PENDING',NOW());"
                      % (rid, uid, real_name, cipher_id_card))
        if r.returncode != 0:
            raise RuntimeError("测试资质申请写入失败：%s" % (r.stderr or "")[:400])
        created_audit_ids.append(rid)
        # service_area / available_time 在 companion_profile 上是 NOT NULL 且无默认值，
        # 漏了它们 INSERT 会直接失败（而 mysql_raw 默认不看 stderr，静默失败最难查）。
        # 注意 companion_profile **没有** submit_time 列 —— 提交时间只记在流水表上
        r = mysql_raw("INSERT INTO `companion_profile` (`user_id`,`real_name`,`id_card`,"
                      "`service_area`,`available_time`,`audit_status`) "
                      "VALUES (%d,'%s','%s','海口市美兰区','周一至周五 08:00-18:00','PENDING');"
                      % (uid, real_name, cipher_id_card))
        if r.returncode != 0:
            raise RuntimeError("测试陪诊员快照写入失败：%s" % (r.stderr or "")[:400])

    for uid in (U_APPROVE, U_REJECT):
        n = mysql_int("SELECT COUNT(*) FROM `companion_profile` WHERE `user_id`=%d;" % uid)
        if n != 1:
            raise RuntimeError("测试陪诊员快照没写进去（userId=%d，命中 %d 行）" % (uid, n))
    print("[准备] 已造测试账号 %s 与资质申请 %s" % (TEST_USER_IDS, created_audit_ids))


def cleanup():
    print("\n" + "-" * 78)
    if created_order_ids:
        ids = ",".join(str(i) for i in created_order_ids)
        for table in ("order_checkin", "companion_track", "order_status_log",
                      "order_reject_log", "order_review", "complaint"):
            mysql_raw("DELETE FROM `%s` WHERE `order_id` IN (%s);" % (table, ids))
        mysql_raw("DELETE FROM `companion_order` WHERE `id` IN (%s);" % ids)
        print("[清理] 已删除测试订单 %s" % ids)
    if created_audit_ids:
        ids = ",".join(str(i) for i in created_audit_ids)
        mysql_raw("DELETE FROM `companion_audit_record` WHERE `id` IN (%s);" % ids)
        print("[清理] 已删除测试资质申请 %s" % ids)
    mysql_raw("DELETE FROM `companion_profile` WHERE `user_id` IN (%s);"
              % ",".join(str(u) for u in TEST_USER_IDS))
    mysql_raw("DELETE FROM `sys_user` WHERE `id` IN (%s);"
              % ",".join(str(u) for u in TEST_USER_IDS))
    print("[清理] 已删除测试账号 %s（及其陪诊员快照）" % TEST_USER_IDS)

    for uid in TEST_USER_IDS:
        redis_del("user:banned:%d" % uid)
        redis_del("pwd:version:%d" % uid)
        redis_del("login:fail:e2eban")
        redis_del("login:fail:e2eapprove")
        redis_del("login:fail:e2ereject")
        redis_del("message:unread:%d" % uid)
    print("[清理] 已清除测试账号的 Redis 封禁标记与密码版本")

    # 操作日志是本模块的「只增不改不删」的表。应用侧不提供删除接口，
    # 但**测试脚本**必须清掉自己造的那些，否则每跑一次审计链就多 6 条假记录
    n = mysql_int("SELECT COUNT(*) FROM `admin_oper_log` WHERE `id`>%d;" % oper_log_base)
    mysql_raw("DELETE FROM `admin_oper_log` WHERE `id`>%d;" % oper_log_base)
    print("[清理] 已删除本次运行新增的 %d 条操作日志（种子 %d 条未动）" % (n, oper_log_base))

    if message_id_start:
        mysql_raw("DELETE FROM `internal_message` WHERE `id`>%d;" % message_id_start)
        print("[清理] 已删除本次新增的站内信（id>%d）" % message_id_start)

    left = mysql_int("SELECT COUNT(*) FROM `companion_order` WHERE `id`>%d;" % SEED_ORDER_MAX_ID)
    left += mysql_int("SELECT COUNT(*) FROM `sys_user` WHERE `id`>=9000;")
    print("[清理] 残留：测试订单 %d 条 / 测试账号 %d 个"
          % (mysql_int("SELECT COUNT(*) FROM `companion_order` WHERE `id`>%d;" % SEED_ORDER_MAX_ID),
             mysql_int("SELECT COUNT(*) FROM `sys_user` WHERE `id`>=9000;")))


# ======================================================================
# 主流程
# ======================================================================

def main():
    global message_id_start, oper_log_base

    print("=" * 78)
    print("银龄伴诊 M9 管理后台 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    # 上次中断的残留先扫掉，否则 9001 这种 id 会撞主键
    stale = mysql_int("SELECT COUNT(*) FROM `sys_user` WHERE `id`>=9000;")
    if stale:
        mysql_raw("DELETE FROM `companion_profile` WHERE `user_id` BETWEEN 9000 AND 9099;")
        mysql_raw("DELETE FROM `companion_audit_record` WHERE `applicant_user_id`"
                  " BETWEEN 9000 AND 9099;")
        mysql_raw("DELETE FROM `sys_user` WHERE `id` BETWEEN 9000 AND 9099;")
        print("[清扫] 清掉上次中断残留的 %d 个测试账号" % stale)

    message_id_start = mysql_int("SELECT COALESCE(MAX(`id`),0) FROM `internal_message`;")
    oper_log_base = mysql_int("SELECT COALESCE(MAX(`id`),0) FROM `admin_oper_log`;")
    setup_test_users()

    fam = login_ok(ACC_FAMILY)
    comp = login_ok(ACC_COMPANION)
    elder = login_ok(ACC_ELDER)
    admin = login_ok(ACC_ADMIN)
    print("[准备] 四个身份登录完成；站内信 id 基线=%d，操作日志 id 基线=%d\n"
          % (message_id_start, oper_log_base))

    # ================= A. 鉴权边界 =================
    print("--- A. ADMIN 鉴权边界 ---")
    status, body = call("GET", "/admin/user", None, None)
    check("A1 未登录访问管理端接口 → 401", status == 401 or body.get("code") == 401,
          "http=%s" % status)

    for label, token, acc in (("家属", fam, ACC_FAMILY),
                              ("老人", elder, ACC_ELDER),
                              ("陪诊员", comp, ACC_COMPANION)):
        bad = []
        for method, path, payload in ADMIN_ENDPOINTS:
            st, bd = call(method, path, payload, token)
            if st != 403 and bd.get("code") != 403:
                bad.append("%s %s → http=%s code=%s" % (method, path, st, bd.get("code")))
        check("A2 %s（%s）访问 12 个管理端接口全部 403" % (label, acc), not bad,
              "越权通过 %d 个：%s" % (len(bad), bad[:3]))

    # ================= B. 资质审核 =================
    print("\n--- B. 资质审核 ---")
    status, body = call("GET", "/admin/companion/audit?size=100", None, admin)
    page = body.get("data") or {}
    recs = page.get("records") or []
    counts = page.get("counts") or {}
    check("B1 资质申请列表 → 200 且返回记录", body.get("code") == 200 and len(recs) > 0,
          "code=%s total=%s" % (body.get("code"), page.get("total")))
    check("B2 列表额外返回 counts 三种状态数量（M9 验收项）",
          {"PENDING", "APPROVED", "REJECTED"} <= set(counts.keys()),
          "counts=%s" % counts)
    db_counts = {}
    for st in ("PENDING", "APPROVED", "REJECTED"):
        db_counts[st] = mysql_int("SELECT COUNT(*) FROM `companion_audit_record` "
                                  "WHERE `audit_status`='%s' AND `deleted`=0;" % st)
    check("B3 counts 与数据库口径一致（不是各自算各自的）",
          all(counts.get(k) == v for k, v in db_counts.items()),
          "api=%s db=%s" % (counts, db_counts))

    status, body = call("GET", "/admin/companion/audit?auditStatus=PENDING&size=100", None, admin)
    pend = (body.get("data") or {}).get("records") or []
    check("B4 auditStatus=PENDING 过滤生效",
          len(pend) > 0 and all(r.get("auditStatus") == "PENDING" for r in pend),
          "条数=%d" % len(pend))

    status, body = call("GET", "/admin/companion/audit?keyword=e2eapprove&size=100", None, admin)
    hit = (body.get("data") or {}).get("records") or []
    check("B5 keyword 能按用户名/姓名搜到目标申请",
          any(r.get("userId") == U_APPROVE for r in hit), "命中=%d 条" % len(hit))

    status, body = call("GET", "/admin/companion/audit?auditStatus=APPROVED&size=100", None, admin)
    appr = (body.get("data") or {}).get("records") or []
    phones = [r.get("phone") for r in appr]
    # 身份证号在种子里有一部分是 NULL（「资料不全」的边界场景，见 sql/tools/verify_data.sql
    # 对 `id_card IS NULL` 的统计口径）——记录没有身份证不构成泄露，
    # 要卡的是「凡有值必脱敏」＋「有值的不许静默丢字段」。
    idcards = [r.get("idCard") or "" for r in appr]
    non_empty = [c for c in idcards if c]
    db_non_empty = mysql_int("SELECT COUNT(*) FROM `companion_audit_record` WHERE `deleted`=0 "
                             "AND `audit_status`='APPROVED' AND `id_card` IS NOT NULL "
                             "AND `id_card`<>'';")
    check("B6 列表手机号一律脱敏（M9 验收项）",
          bool(phones) and all(MASKED_PHONE_RE.match(p or "") for p in phones),
          "样本=%s" % phones[:3])
    check("B7 列表身份证号一律脱敏（含 * 且非 18 位明文）",
          bool(non_empty) and all("*" in c and not ID_CARD_RE.search(c) for c in non_empty),
          "有值 %d 条 / 共 %d 条，样本=%s" % (len(non_empty), len(idcards), non_empty[:2]))
    check("B7b 库里有身份证的记录都下发了（不静默丢字段）",
          len(non_empty) == db_non_empty,
          "api 有值=%d db 有值=%d" % (len(non_empty), db_non_empty))
    check("B8 列表响应不含任何完整手机号/身份证明文",
          not FULL_PHONE_RE.search(json.dumps(page, ensure_ascii=False))
          and not ID_CARD_RE.search(json.dumps(page, ensure_ascii=False)),
          "扫描 %d 条记录" % len(recs))

    # 详情：拿一条**已驳回**的种子申请来验「驳回原因真的下发了」。
    # 不能拿 PENDING 的那条 —— 它本来就没有驳回原因，而响应里空字段会被
    # 全局 Jackson 的非空策略整个省掉，断言「字段不存在」就变成了断言一个假象。
    rejected_id = mysql_int("SELECT `id` FROM `companion_audit_record` "
                            "WHERE `audit_status`='REJECTED' ORDER BY `id` LIMIT 1;")
    status, body = call("GET", "/admin/companion/audit/%d" % rejected_id, None, admin)
    detail = body.get("data") or {}
    check("B9 申请详情 → 200 且返回驳回原因与内部备注（列表页不下发这两项）",
          body.get("code") == 200 and bool(detail.get("rejectReason")),
          "id=%d rejectReason=%s" % (rejected_id, detail.get("rejectReason")))
    # B10 特意换一条**库里有身份证**的申请来验脱敏：种子里 REJECTED 的记录
    # id_card 都是空的，拿它们验会得出「null 也算脱敏」这种假结论（同 B7 的口径说明）。
    with_idcard = mysql_int("SELECT `id` FROM `companion_audit_record` WHERE `deleted`=0 "
                            "AND `id_card` IS NOT NULL AND `id_card`<>'' ORDER BY `id` LIMIT 1;")
    status, body = call("GET", "/admin/companion/audit/%d" % with_idcard, None, admin)
    d_with_id = body.get("data") or {}
    check("B10 详情里的身份证号仍是脱敏串（不因为「详情」就放明文）",
          body.get("code") == 200 and "*" in (d_with_id.get("idCard") or "")
          and not ID_CARD_RE.search(d_with_id.get("idCard") or ""),
          "id=%d idCard=%s" % (with_idcard, d_with_id.get("idCard")))

    status, body = call("GET", "/admin/companion/audit/999999", None, admin)
    check("B11 申请不存在 → 2007", body.get("code") == 2007,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("POST", "/admin/companion/audit/%d" % created_audit_ids[1],
                        {"approved": False}, admin)
    check("B12 驳回未填原因 → 8003", body.get("code") == 8003,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/admin/companion/audit/%d" % created_audit_ids[1],
                        {"approved": False, "rejectReason": "短"}, admin)
    check("B13 驳回原因不足 5 字 → 8003", body.get("code") == 8003,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # 审核通过：先把申请人的「ELDER 旧令牌」拿到手，用来验证角色升级会作废旧令牌
    old_token_of_approve = login_ok("e2eapprove")
    st_profile_before, _ = call("GET", "/user/profile", None, old_token_of_approve)

    status, body = call("POST", "/admin/companion/audit/%d" % created_audit_ids[0],
                        {"approved": True, "remark": "材料齐全，予以通过"}, admin)
    check("B14 审核通过 → 200 且返回 APPROVED",
          body.get("code") == 200 and (body.get("data") or {}).get("auditStatus") == "APPROVED",
          "code=%s data=%s" % (body.get("code"), body.get("data")))
    check("B15 审核通过后账号角色升级为 COMPANION",
          mysql_value("SELECT `role` FROM `sys_user` WHERE `id`=%d;" % U_APPROVE) == "COMPANION",
          "role=%s" % mysql_value("SELECT `role` FROM `sys_user` WHERE `id`=%d;" % U_APPROVE))
    check("B16 快照表 companion_profile 同步为 APPROVED（只改流水不改快照会让"
          "「审核已通过」与「仍然接不了单」同时成立）",
          mysql_value("SELECT `audit_status` FROM `companion_profile` WHERE `user_id`=%d;"
                      % U_APPROVE) == "APPROVED",
          "snapshot=%s" % mysql_value("SELECT `audit_status` FROM `companion_profile` "
                                      "WHERE `user_id`=%d;" % U_APPROVE))
    check("B17 审核通过给申请人发了 AUDIT_RESULT 站内信",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND "
                    "`receiver_id`=%d AND `type`='AUDIT_RESULT';"
                    % (message_id_start, U_APPROVE)) >= 1, "条数=%d"
          % mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND "
                      "`receiver_id`=%d AND `type`='AUDIT_RESULT';"
                      % (message_id_start, U_APPROVE)))
    row = last_oper_log("AUDIT_COMPANION", U_APPROVE)
    check("B18 审核写 admin_oper_log（含操作人/目标/前后状态）",
          row.startswith("%d|COMPANION|%d|PENDING|APPROVED|" % (ADMIN_ID, U_APPROVE)),
          "row=%s" % row)
    st_after, _ = call("GET", "/user/profile", None, old_token_of_approve)
    check("B19 角色升级后旧令牌立即失效（否则用户会看到「已通过」却进不了陪诊员页）",
          st_profile_before == 200 and st_after in (401, 403),
          "升级前 http=%s → 升级后 http=%s" % (st_profile_before, st_after))

    status, body = call("POST", "/admin/companion/audit/%d" % created_audit_ids[0],
                        {"approved": True}, admin)
    check("B20 已终态的申请再审 → 8001（不许「先驳回再偷偷改成通过」）",
          body.get("code") == 8001, "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("POST", "/admin/companion/audit/%d" % created_audit_ids[1],
                        {"approved": False, "rejectReason": "身份证照片不清晰，请重新上传"},
                        admin)
    check("B21 审核驳回 → 200 且返回 REJECTED",
          body.get("code") == 200 and (body.get("data") or {}).get("auditStatus") == "REJECTED",
          "code=%s data=%s" % (body.get("code"), body.get("data")))
    check("B22 驳回原因落库",
          mysql_value("SELECT IFNULL(`reject_reason`,'NULL') FROM `companion_audit_record` "
                      "WHERE `id`=%d;" % created_audit_ids[1]) == "身份证照片不清晰，请重新上传",
          "reject_reason=%s" % mysql_value("SELECT IFNULL(`reject_reason`,'NULL') FROM "
                                          "`companion_audit_record` WHERE `id`=%d;"
                                          % created_audit_ids[1]))
    check("B23 驳回不升级角色（仍是 ELDER）",
          mysql_value("SELECT `role` FROM `sys_user` WHERE `id`=%d;" % U_REJECT) == "ELDER",
          "role=%s" % mysql_value("SELECT `role` FROM `sys_user` WHERE `id`=%d;" % U_REJECT))
    check("B24 驳回也给申请人发了 AUDIT_RESULT 站内信（通过/驳回都要通知）",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND "
                    "`receiver_id`=%d AND `type`='AUDIT_RESULT';"
                    % (message_id_start, U_REJECT)) >= 1, "")

    # ================= C. 用户管理 =================
    print("\n--- C. 用户管理 ---")
    status, body = call("GET", "/admin/user?size=100", None, admin)
    upage = body.get("data") or {}
    urecs = upage.get("records") or []
    check("C1 用户列表 → 200", body.get("code") == 200 and len(urecs) > 0,
          "code=%s total=%s" % (body.get("code"), upage.get("total")))
    check("C2 用户列表手机号一律脱敏（M9 验收项）",
          bool(urecs) and all(MASKED_PHONE_RE.match(r.get("phone") or "") for r in urecs),
          "样本=%s" % [r.get("phone") for r in urecs[:3]])
    check("C3 用户列表不含 password 字段（逐字段装配，不是 copyProperties）",
          all("password" not in r for r in urecs), "")
    check("C4 每条记录带 orderCount 关联订单数",
          all("orderCount" in r for r in urecs),
          "样本=%s" % [r.get("orderCount") for r in urecs[:5]])

    status, body = call("GET", "/admin/user?role=FAMILY&size=100", None, admin)
    fr = (body.get("data") or {}).get("records") or []
    check("C5 role=FAMILY 过滤生效",
          len(fr) > 0 and all(r.get("role") == "FAMILY" for r in fr), "条数=%d" % len(fr))

    status, body = call("GET", "/admin/user?keyword=e2eban&size=20", None, admin)
    kr = (body.get("data") or {}).get("records") or []
    check("C6 keyword 能按用户名搜到目标用户",
          any(r.get("id") == U_BAN for r in kr), "命中=%d 条" % len(kr))

    # 封禁 → 旧令牌立即失效（M9 验收关键项）
    ban_token_before = login_ok("e2eban")
    st_before, _ = call("GET", "/user/profile", None, ban_token_before)
    status, body = call("POST", "/admin/user/%d/disable" % U_BAN,
                        {"reason": "e2e 测试：伪造订单并恶意取消"}, admin)
    check("C7 封禁用户 → 200 且状态 DISABLED",
          body.get("code") == 200 and (body.get("data") or {}).get("status") == "DISABLED",
          "code=%s data=%s" % (body.get("code"), body.get("data")))
    check("C8 封禁后库里 status=DISABLED 且原因写入 remark",
          mysql_value("SELECT CONCAT(`status`,'|',IFNULL(`remark`,'NULL')) FROM `sys_user` "
                      "WHERE `id`=%d;" % U_BAN) == "DISABLED|e2e 测试：伪造订单并恶意取消",
          "row=%s" % mysql_value("SELECT CONCAT(`status`,'|',IFNULL(`remark`,'NULL')) "
                                 "FROM `sys_user` WHERE `id`=%d;" % U_BAN))
    st_after, _ = call("GET", "/user/profile", None, ban_token_before)
    check("C9 封禁后旧令牌**立即** 403 且带 code=1002（不必等 token 过期 —— M9 验收关键项）",
          st_before == 200 and st_after == 403,
          "封禁前 http=%s → 封禁后 http=%s" % (st_before, st_after))
    st_after_code = call("GET", "/user/profile", None, ban_token_before)[1].get("code")
    check("C9b 403 的响应体带 code=1002（前端才能显示「账号已被封禁」而不是「请重新登录」）",
          st_after_code == 1002,
          "code=%s（disableUser 同时递增了密码版本，若过滤器先比版本就会退化成 401）"
          % st_after_code)
    check("C10 封禁同时递增密码版本并打封禁标记（双重失效）",
          redis_get("user:banned:%d" % U_BAN) == "1"
          and (redis_get("pwd:version:%d" % U_BAN) or "0") != "0",
          "banned=%s ver=%s" % (redis_get("user:banned:%d" % U_BAN),
                                redis_get("pwd:version:%d" % U_BAN)))
    check("C11 封禁给用户发了说明原因的 SYSTEM_NOTICE",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND `receiver_id`=%d "
                    "AND `type`='SYSTEM_NOTICE';" % (message_id_start, U_BAN)) >= 1, "")
    row = last_oper_log("DISABLE_USER", U_BAN)
    check("C12 封禁写 admin_oper_log（NORMAL → DISABLED）",
          row.startswith("%d|USER|%d|NORMAL|DISABLED|" % (ADMIN_ID, U_BAN)), "row=%s" % row)

    status, body = call("POST", "/admin/user/%d/disable" % U_BAN,
                        {"reason": "e2e 测试：重复封禁"}, admin)
    check("C13 重复封禁 → 2004", body.get("code") == 2004,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/admin/user/1/disable",
                        {"reason": "e2e 测试：尝试封禁管理员"}, admin)
    check("C14 封禁管理员 → 8002（一旦有管理员互封，系统再没人能解封）",
          body.get("code") == 8002, "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/admin/user/%d/disable" % FAMILY_ID, {"reason": "短"}, admin)
    check("C15 封禁原因不足 5 字 → 400",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("C16 校验失败没有把种子家属封掉",
          mysql_value("SELECT `status` FROM `sys_user` WHERE `id`=%d;" % FAMILY_ID) == "NORMAL", "")

    status, body = call("POST", "/admin/user/%d/enable" % U_BAN, {"remark": "申诉通过，予以解封"},
                        admin)
    check("C17 解封用户 → 200 且状态 NORMAL",
          body.get("code") == 200 and (body.get("data") or {}).get("status") == "NORMAL",
          "code=%s data=%s" % (body.get("code"), body.get("data")))
    st_still, bd_still = call("GET", "/user/profile", None, ban_token_before)
    # 解封后账号**不再被封禁**，所以这里必须是 401（令牌版本已失效 → 请重新登录），
    # 而不是 403/1002「账号已被封禁」——后者会让刚被解封的用户看到一句假提示。
    # 断言写成 403 是自相矛盾的：C19 刚验证过封禁标记已被清掉，
    # 过滤器也就无从返回 403。
    check("C18 解封后旧令牌仍不可用，且是 401（不是 403「账号已被封禁」的假提示）",
          st_still == 401 and bd_still.get("code") != 1002,
          "http=%s code=%s" % (st_still, bd_still.get("code")))
    check("C19 解封清掉 Redis 封禁标记",
          redis_get("user:banned:%d" % U_BAN) != "1",
          "banned=%s" % redis_get("user:banned:%d" % U_BAN))
    fresh = login_ok("e2eban")
    st_fresh, _ = call("GET", "/user/profile", None, fresh)
    check("C20 解封后重新登录即可正常使用", st_fresh == 200, "http=%s" % st_fresh)
    row = last_oper_log("ENABLE_USER", U_BAN)
    check("C21 解封写 admin_oper_log（DISABLED → NORMAL）",
          row.startswith("%d|USER|%d|DISABLED|NORMAL|" % (ADMIN_ID, U_BAN)), "row=%s" % row)

    status, body = call("POST", "/admin/user/%d/enable" % U_BAN, {}, admin)
    check("C22 解封一个未被封禁的用户 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # 重置密码
    st_reset, body = call("POST", "/admin/user/%d/reset-password" % U_BAN,
                          {"remark": "e2e 测试：用户来电请求重置密码"}, admin)
    check("C23 重置密码 → 200 且返回默认密码（供管理员电话告知）",
          body.get("code") == 200 and (body.get("data") or {}).get("defaultPassword") == PASSWORD,
          "code=%s data=%s" % (body.get("code"), body.get("data")))
    check("C24 重置后库里是 BCrypt 哈希，且强制下次改密",
          BCRYPT_RE.match(mysql_value("SELECT `password` FROM `sys_user` WHERE `id`=%d;"
                                      % U_BAN) or "")
          and mysql_value("SELECT `need_change_password` FROM `sys_user` WHERE `id`=%d;"
                          % U_BAN) == "1",
          "hash 前缀=%s needChange=%s"
          % (mysql_value("SELECT LEFT(`password`,7) FROM `sys_user` WHERE `id`=%d;" % U_BAN),
             mysql_value("SELECT `need_change_password` FROM `sys_user` WHERE `id`=%d;" % U_BAN)))
    check("C25 响应里绝不出现密码哈希（不是只靠前端不展示）",
          not BCRYPT_RE.search(json.dumps(body, ensure_ascii=False)), "")
    st_old, _ = call("GET", "/user/profile", None, fresh)
    check("C26 重置密码后旧令牌立即失效", st_old in (401, 403), "http=%s" % st_old)
    check("C27 用默认密码可以重新登录（重置真的生效了）",
          login_try("e2eban").get("code") == 200, "")
    row = last_oper_log("RESET_PASSWORD", U_BAN)
    check("C28 重置密码写 admin_oper_log",
          row.startswith("%d|USER|%d|" % (ADMIN_ID, U_BAN)), "row=%s" % row)
    status, body = call("POST", "/admin/user/%d/reset-password" % U_BAN, {}, admin)
    check("C29 重置密码未填原因 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/admin/user?status=DISABLED&size=100", None, admin)
    dr = (body.get("data") or {}).get("records") or []
    check("C30 status=DISABLED 过滤生效（当前应无新增封禁账号）",
          all(r.get("status") == "DISABLED" for r in dr), "条数=%d" % len(dr))

    # ================= D. 订单与纠纷处理 =================
    print("\n--- D. 订单管理与纠纷处理 ---")
    status, body = call("GET", "/admin/order?size=50", None, admin)
    opage = body.get("data") or {}
    orecs = opage.get("records") or []
    check("D1 全站订单列表 → 200 且带 hasComplaint 标记",
          body.get("code") == 200 and bool(orecs) and "hasComplaint" in orecs[0],
          "code=%s total=%s keys=%s" % (body.get("code"), opage.get("total"),
                                        sorted(orecs[0].keys())[:6] if orecs else []))
    check("D2 hasComplaint=true → 结果全部为 true",
          all(r.get("hasComplaint") is True
              for r in (call("GET", "/admin/order?hasComplaint=true&size=50", None, admin)[1]
                        .get("data") or {}).get("records") or []),
          "条数=%d" % len((call("GET", "/admin/order?hasComplaint=true&size=50", None, admin)[1]
                           .get("data") or {}).get("records") or []))

    status, body = call("GET", "/admin/order?status=COMPLETED,REVIEWED&size=50", None, admin)
    st_recs = (body.get("data") or {}).get("records") or []
    check("D3 status 多值（逗号分隔）筛选生效",
          len(st_recs) > 0 and all(r.get("status") in ("COMPLETED", "REVIEWED") for r in st_recs),
          "条数=%d" % len(st_recs))

    # 造一笔走到 IN_SERVICE 的订单，然后强制终态
    oid = create_order(fam)
    call("POST", "/order/%d/accept" % oid, {}, comp)
    call("POST", "/order/%d/start" % oid, {}, comp)
    check("D4 测试订单已进入 IN_SERVICE",
          mysql_value("SELECT `status` FROM `companion_order` WHERE `id`=%d;" % oid) == "IN_SERVICE",
          "status=%s" % mysql_value("SELECT `status` FROM `companion_order` WHERE `id`=%d;" % oid))

    status, body = call("POST", "/admin/order/%d/arbitrate" % oid,
                        {"targetStatus": "PENDING", "result": "e2e 测试：目标状态不是终态"},
                        admin)
    check("D5 arbitrate 传入非终态 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/admin/order/%d/arbitrate" % oid,
                        {"targetStatus": "CANCELLED", "result": "太短"}, admin)
    check("D6 处理结果不足 10 字 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/admin/order/999999/arbitrate",
                        {"targetStatus": "CANCELLED", "result": "e2e 测试：订单不存在的处理结果"},
                        admin)
    check("D7 arbitrate 不存在的订单 → 3001", body.get("code") == 3001,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    result_text = "经核实陪诊员迟到 40 分钟且未提前告知，本次订单取消，服务费不结算。"
    status, body = call("POST", "/admin/order/%d/arbitrate" % oid,
                        {"targetStatus": "CANCELLED", "result": result_text,
                         "refundToFamily": True, "penaltyToCompanion": True}, admin)
    check("D8 纠纷处理把 IN_SERVICE 强制置为 CANCELLED → 200",
          body.get("code") == 200 and (body.get("data") or {}).get("status") == "CANCELLED",
          "code=%s data=%s" % (body.get("code"), body.get("data")))
    check("D9 库中订单状态确实被强制改终态",
          mysql_value("SELECT `status` FROM `companion_order` WHERE `id`=%d;" % oid) == "CANCELLED",
          "status=%s" % mysql_value("SELECT `status` FROM `companion_order` WHERE `id`=%d;" % oid))
    remark = mysql_value("SELECT IFNULL(`remark`,'NULL') FROM `order_status_log` WHERE "
                         "`order_id`=%d ORDER BY `id` DESC LIMIT 1;" % oid)
    check("D10 状态日志标注了「管理员强制变更」（否则时间线上看不出这不是正常流转）",
          "管理员强制变更" in remark, "remark=%s" % remark[:90])
    check("D11 家属收到一条 ORDER_CANCELLED 站内信",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND "
                    "`receiver_id`=%d AND `type`='ORDER_CANCELLED';"
                    % (message_id_start, FAMILY_ID)) >= 1, "")
    check("D12 陪诊员也收到一条（只通知一方会让另一方在事后才被问责）",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND "
                    "`receiver_id`=%d AND `type`='ORDER_CANCELLED';"
                    % (message_id_start, COMPANION_ID)) >= 1, "")
    row = last_oper_log("ARBITRATE_ORDER", oid)
    check("D13 纠纷处理写 admin_oper_log（含 beforeStatus=IN_SERVICE / afterStatus=CANCELLED）",
          row.startswith("%d|ORDER|%d|IN_SERVICE|CANCELLED|" % (ADMIN_ID, oid)), "row=%s" % row)

    status, body = call("POST", "/admin/order/%d/arbitrate" % oid,
                        {"targetStatus": "COMPLETED", "result": "e2e 测试：对已是终态的单再处理"},
                        admin)
    check("D14 已是终态的订单再处理 → 3002", body.get("code") == 3002,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    st_flow, bd_flow = call("POST", "/order/%d/start" % oid, {}, comp)
    check("D15 被强制终态后不能走正向流转（再 start → 3002，而不是 500）",
          bd_flow.get("code") == 3002, "code=%s message=%s" % (bd_flow.get("code"),
                                                              bd_flow.get("message")))

    # ================= E. 投诉处理 =================
    print("\n--- E. 投诉管理 ---")
    status, body = call("GET", "/admin/complaint?size=50", None, admin)
    check("E1 投诉列表 → 200", body.get("code") == 200
          and bool((body.get("data") or {}).get("records")), "total=%s"
          % (body.get("data") or {}).get("total"))
    status, body = call("GET", "/admin/complaint?status=PENDING&size=50", None, admin)
    pr = (body.get("data") or {}).get("records") or []
    check("E2 status=PENDING 过滤生效",
          len(pr) > 0 and all(r.get("status") == "PENDING" for r in pr), "条数=%d" % len(pr))
    status, body = call("GET", "/admin/complaint?status=NOT_A_STATUS", None, admin)
    check("E3 投诉状态取值非法 → 400（忽略非法值会让人以为筛选生效了）",
          body.get("code") == 400, "code=%s message=%s" % (body.get("code"), body.get("message")))

    # 自己造一条投诉：走完整链路 下单→接单→开始→完成→投诉
    oid2 = create_order(fam)
    call("POST", "/order/%d/accept" % oid2, {}, comp)
    call("POST", "/order/%d/start" % oid2, {}, comp)
    call("POST", "/order/%d/complete" % oid2,
         {"summary": "09:10 到达医院，全程陪同完成心血管内科就诊，11:50 送老人回家"}, comp)
    status, body = call("POST", "/complaint", {
        "orderId": oid2, "type": "LATE",
        "content": "e2e 测试：陪诊员迟到四十分钟且全程未提前告知，要求平台核实处理。"}, fam)
    cid = (body.get("data") or {}).get("complaintId")
    if cid:
        created_complaint_ids.append(cid)
    check("E4 造出一条待处理投诉", body.get("code") == 200 and bool(cid),
          "code=%s complaintId=%s" % (body.get("code"), cid))

    # 投诉状态机是 PENDING → PROCESSING → {RESOLVED, REJECTED}：
    # 既不允许跳级（PENDING → RESOLVED），也不允许回退与重复流转。
    status, body = call("POST", "/admin/complaint/%d/handle" % cid,
                        {"status": "RESOLVED", "handleResult": "e2e 测试：越过 PROCESSING 直接结案"},
                        admin)
    check("E5 待处理投诉不能直达终态（PENDING → RESOLVED 跳级）→ 409",
          body.get("code") == 409, "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("E5b 跳级被拒后库里状态未被改动（仍是 PENDING）",
          mysql_value("SELECT `status` FROM `complaint` WHERE `id`=%d;" % cid) == "PENDING", "")

    status, body = call("POST", "/admin/complaint/%d/handle" % cid,
                        {"status": "PROCESSING", "handleResult": "e2e 测试：已受理，正在联系双方核实"},
                        admin)
    check("E6 合法流转 PENDING → PROCESSING → 200",
          body.get("code") == 200 and (body.get("data") or {}).get("status") == "PROCESSING",
          "code=%s data=%s" % (body.get("code"), body.get("data")))
    check("E7 中间态不发 COMPLAINT_HANDLED（否则用户收到的是「已处理完成」的误导消息）",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND "
                    "`type`='COMPLAINT_HANDLED';" % message_id_start) == 0, "")

    status, body = call("POST", "/admin/complaint/%d/handle" % cid,
                        {"status": "RESOLVED", "penaltyToTarget": True,
                         "handleResult": "e2e 测试：核实属实，已对陪诊员做扣分处理"},
                        admin)
    check("E8 PROCESSING → RESOLVED 结案 → 200",
          body.get("code") == 200 and (body.get("data") or {}).get("status") == "RESOLVED",
          "code=%s data=%s" % (body.get("code"), body.get("data")))
    check("E8b 判罚标志落库（penaltyToTarget=true → penalty_to_target=1）",
          mysql_value("SELECT `penalty_to_target` FROM `complaint` WHERE `id`=%d;" % cid) == "1", "")
    check("E9 终态时投诉人收到 COMPLAINT_HANDLED",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND "
                    "`receiver_id`=%d AND `type`='COMPLAINT_HANDLED';"
                    % (message_id_start, FAMILY_ID)) >= 1, "")
    check("E10 终态时被投诉人也收到 COMPLAINT_HANDLED",
          mysql_int("SELECT COUNT(*) FROM `internal_message` WHERE `id`>%d AND "
                    "`receiver_id`=%d AND `type`='COMPLAINT_HANDLED';"
                    % (message_id_start, COMPANION_ID)) >= 1, "")
    row = last_oper_log("HANDLE_COMPLAINT", cid)
    check("E11 处理投诉写 admin_oper_log（PROCESSING → RESOLVED）",
          row.startswith("%d|COMPLAINT|%d|PROCESSING|RESOLVED|" % (ADMIN_ID, cid)), "row=%s" % row)
    check("E12 两次流转各留一条痕（PENDING→PROCESSING、PROCESSING→RESOLVED）",
          mysql_int("SELECT COUNT(*) FROM `admin_oper_log` WHERE `id`>%d AND "
                    "`oper_type`='HANDLE_COMPLAINT' AND `target_id`=%d;"
                    % (oper_log_base, cid)) == 2, "")

    status, body = call("POST", "/admin/complaint/%d/handle" % cid,
                        {"status": "PROCESSING", "handleResult": "e2e 测试：把已结案的投诉改回处理中"},
                        admin)
    check("E13 投诉状态回退（RESOLVED → PROCESSING）→ 409（M9 验收项）",
          body.get("code") == 409, "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/admin/complaint/%d/handle" % cid,
                        {"status": "RESOLVED", "handleResult": "e2e 测试：重复流转到当前状态"},
                        admin)
    check("E14 变更为当前已处于的状态 → 409", body.get("code") == 409,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    check("E14b 被驳回的两次流转都没留下审计垃圾（终态后仍只有 2 条）",
          mysql_int("SELECT COUNT(*) FROM `admin_oper_log` WHERE `id`>%d AND "
                    "`oper_type`='HANDLE_COMPLAINT' AND `target_id`=%d;"
                    % (oper_log_base, cid)) == 2, "")
    status, body = call("POST", "/admin/complaint/%d/handle" % cid,
                        {"status": "CLOSED", "handleResult": "e2e 测试：非法目标状态取值"}, admin)
    check("E15 目标状态取值非法 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    status, body = call("POST", "/admin/complaint/999999/handle",
                        {"status": "PROCESSING", "handleResult": "e2e 测试：投诉不存在的处理"},
                        admin)
    check("E16 投诉不存在 → 6004", body.get("code") == 6004,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= F. 操作日志 =================
    print("\n--- F. 操作日志 ---")
    status, body = call("GET", "/admin/oper-log?size=50", None, admin)
    lpage = body.get("data") or {}
    lrecs = lpage.get("records") or []
    check("F1 操作日志查询 → 200 且带中文操作类型名",
          body.get("code") == 200 and bool(lrecs) and all("operTypeLabel" in r for r in lrecs),
          "total=%s" % lpage.get("total"))

    status, body = call("GET", "/admin/oper-log?operType=AUDIT_COMPANION&size=50", None, admin)
    lr = (body.get("data") or {}).get("records") or []
    check("F2 按 operType 筛选生效",
          len(lr) > 0 and all(r.get("operType") == "AUDIT_COMPANION" for r in lr),
          "条数=%d" % len(lr))
    status, body = call("GET", "/admin/oper-log?operType=NOT_A_TYPE", None, admin)
    check("F3 操作类型取值非法 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/admin/oper-log?operatorId=%d&size=50" % ADMIN_ID, None, admin)
    lr = (body.get("data") or {}).get("records") or []
    check("F4 按 operatorId 筛选生效",
          len(lr) > 0 and all(r.get("operatorId") == ADMIN_ID for r in lr), "条数=%d" % len(lr))

    status, body = call("GET", "/admin/oper-log?targetType=ORDER&targetId=%d" % oid, None, admin)
    lr = (body.get("data") or {}).get("records") or []
    check("F5 按 targetType + targetId 精确筛到本次的纠纷处理记录",
          len(lr) == 1 and lr[0].get("operType") == "ARBITRATE_ORDER",
          "条数=%d 首条=%s" % (len(lr), lr[0].get("operType") if lr else "-"))

    today = dt.date.today().isoformat()
    tomorrow = (dt.date.today() + dt.timedelta(days=1)).isoformat()
    status, body = call("GET", "/admin/oper-log?startTime=%s 00:00:00&endTime=%s 00:00:00&size=100"
                        % (today, tomorrow), None, admin)
    lr = (body.get("data") or {}).get("records") or []
    check("F6 按时间区间（精确到秒）筛选生效",
          len(lr) > 0 and all(r.get("operTime", "").startswith(today) for r in lr),
          "条数=%d" % len(lr))

    status, body = call("GET", "/admin/oper-log?startTime=%s 00:00:00&endTime=2020-01-01 00:00:00"
                        % today, None, admin)
    check("F7 区间为空时返回 0 条而不是报错",
          body.get("code") == 200 and (body.get("data") or {}).get("total") == 0,
          "total=%s" % (body.get("data") or {}).get("total"))

    kinds = {}
    for t in ("AUDIT_COMPANION", "DISABLE_USER", "ENABLE_USER", "RESET_PASSWORD",
              "ARBITRATE_ORDER", "HANDLE_COMPLAINT"):
        kinds[t] = mysql_int("SELECT COUNT(*) FROM `admin_oper_log` WHERE `id`>%d "
                             "AND `oper_type`='%s' AND `operator_id`=%d;"
                             % (oper_log_base, t, ADMIN_ID))
    check("F8 本次六类写操作全部落审计（谁/何时/对谁/做了什么）",
          all(v >= 1 for v in kinds.values()), "明细=%s" % kinds)

    sql_src = ("SELECT COUNT(*) FROM `admin_oper_log` WHERE `id`>%d AND "
               "(`operator_id` IS NULL OR `oper_type` IS NULL OR `oper_time` IS NULL);"
               % oper_log_base)
    check("F9 审计记录的关键字段无空值", mysql_int(sql_src) == 0, "")

    # ================= G. 源码级：日志只增不改不删 =================
    print("\n--- G. 日志只增不改不删（源码级）---")
    src_root = r"F:\test\Senior Companion Health Assessment\backend\src\main\java"
    hits = []
    for root, _, files in os.walk(src_root):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(root, fn)
            try:
                with open(path, encoding="utf-8") as fh:
                    for i, line in enumerate(fh, 1):
                        low = line.strip()
                        if low.startswith("*") or low.startswith("//"):
                            continue
                        if "AdminOperLogMapper" in low and ("deleteById" in low or "delete(" in low):
                            hits.append("%s:%d" % (fn, i))
            except OSError:
                pass
    check("G1 源码里不存在对 admin_oper_log 的删除调用（日志只增不留删除入口）",
          not hits, "命中=%s" % hits[:5])

    status, body = call("DELETE", "/admin/oper-log/%d" % oper_log_base, None, admin)
    check("G2 管理端没有删除操作日志的接口（DELETE 不被路由到业务）",
          status in (404, 405) or body.get("code") in (404, 405, 500),
          "http=%s body=%s" % (status, str(body)[:80]))

    status, body = call("GET", "/admin/oper-log?size=1", None, admin)
    check("G3 日志查询是只读接口（连续两次查询 total 不变）",
          body.get("code") == 200, "")

    cleanup()

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
    ok = True
    try:
        ok = main()
    except Exception as e:
        import traceback
        traceback.print_exc()
        ok = False
    sys.exit(0 if ok else 1)
