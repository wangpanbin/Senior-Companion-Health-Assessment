# -*- coding: utf-8 -*-
"""
银龄伴诊 —— M3 用户与档案 端到端实测脚本

用真实 HTTP 请求打真实后端，逐条验证 docs/api/02-elder-family.md
「三、验收标准（M3）」以及归属校验（越权）、脱敏、AES 落库等硬约束。

与 e2e_auth.py 同样的原则：除了两处必要的旁路，其余全部走正常接口。
    ① 验证码是图片，从 Redis 读回明文（否则无法自动登录）；
    ② 「凭手机号认领已有老人账号」需要先有一个「已注册账号 + 已有档案」的老人，
       种子数据里的老人全部已被绑定，所以脚本用 SQL 造一个干净的测试老人，
       随后所有断言仍然走 HTTP 接口。造出来的数据在末尾全部物理删除。

用法：
    python e2e_user_profile.py      # 前提：后端已在 8080 启动，MySQL 与 Redis 可用
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

PASSWORD = "Nl@123456"

# 种子账号
ACC_FAMILY = "fam001"          # 家属 101，绑定老人 401（BOUND）与 431（UNBOUND）
ACC_FAMILY_OTHER = "fam002"    # 家属 102，绑定老人 402
ACC_ELDER = "elder001"         # 老人 201 → 档案 401
ACC_ELDER_OTHER = "elder002"   # 老人 202 → 档案 402
ACC_COMPANION = "comp001"      # 陪诊员 301（快照 id=601）
ACC_ADMIN = "admin"

ELDER_OWN = 401                # 家属 101 的老人，有一条 PENDING 订单
ELDER_OTHER = 402              # 家属 102 的老人
ELDER_UNBOUND = 431            # 家属 101 名下关系为 UNBOUND

STAMP = str(int(time.time()))
ACC_NEW_ELDER = "e2eel" + STAMP          # 新注册的老人账号
PHONE_NEW_ELDER = "17" + STAMP[-9:]
ACC_NEW_FAMILY = "e2efam" + STAMP        # 新注册的家属账号（用于资质申请）
PHONE_NEW_FAMILY = "16" + STAMP[-9:]

PLAIN_ID_CARD = "460101194803120011"
PLAIN_ID_CARD_APPLY = "460101199001010011"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  -> " + detail) if detail else ""))
    sys.stdout.flush()


def url_of(path):
    """URL 编码非 ASCII 字符。

    查询串里带中文（如 keyword=张）时，urllib 会把请求行按 ASCII 编码并直接抛
    UnicodeEncodeError —— 必须在拼 URL 前先转义。保留 :/?#[]@!$&'()*+,;= 等
    结构性字符，只转义中文与空格。
    """
    return BASE + urllib.parse.quote(path, safe="/?&=:%+,[]@!$'()*;")


def call(method, path, body=None, token=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url_of(path), data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
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
        with urllib.request.urlopen(req, timeout=20) as resp:
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


def register(username, phone, nickname, role):
    key, code = fresh_captcha()
    return call("POST", "/auth/register", {
        "username": username, "phone": phone, "password": PASSWORD,
        "nickname": nickname, "role": role, "captchaKey": key, "captchaCode": code,
    })


def main():
    print("=" * 78)
    print("银龄伴诊 M3 端到端实测   (base=%s)" % BASE)
    print("=" * 78)

    fam = login_ok(ACC_FAMILY)
    fam_token = fam["accessToken"]
    fam_id = fam["userInfo"]["id"]
    fam2_token = login_ok(ACC_FAMILY_OTHER)["accessToken"]
    elder_token = login_ok(ACC_ELDER)["accessToken"]
    elder2_token = login_ok(ACC_ELDER_OTHER)["accessToken"]
    comp_token = login_ok(ACC_COMPANION)["accessToken"]
    admin_token = login_ok(ACC_ADMIN)["accessToken"]

    # ================= A. 当前用户资料 =================
    status, body = call("GET", "/user/profile", None, fam_token)
    data = body.get("data") or {}
    check("A1 GET /user/profile → 200 + 角色/脱敏手机号",
          status == 200 and data.get("role") == "FAMILY"
          and re.fullmatch(r"\d{3}\*{4}\d{4}", data.get("phone") or "") is not None,
          "role=%s phone=%s" % (data.get("role"), data.get("phone")))

    _, raw = raw_body("GET", "/user/profile", None, fam_token)
    check("A2 资料响应体不含 password / idCard 字段",
          "password" not in raw and "idCard" not in raw, "len=%d" % len(raw))

    status, body = call("PUT", "/user/profile", {"nickname": "家属01改"}, fam_token)
    check("A3 PUT /user/profile 改昵称 → 200", body.get("code") == 200,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    # 改回去，别把种子数据改脏
    call("PUT", "/user/profile", {"nickname": "家属01"}, fam_token)

    status, body = call("PUT", "/user/profile", {"nickname": "只读测试"}, elder_token)
    check("A4 老人账号 PUT /user/profile → 403 且提示「只读」",
          status == 403 and "只读" in (body.get("message") or ""),
          "http=%s message=%s" % (status, body.get("message")))

    # ================= B. 老人档案列表 =================
    status, body = call("GET", "/user/elder", None, fam_token)
    data = body.get("data") or {}
    records = data.get("records") or []
    check("B1 GET /user/elder → 200，只含有效绑定的老人（数量 1）",
          status == 200 and data.get("total") == 1 and len(records) == 1,
          "total=%s" % data.get("total"))
    check("B2 列表姓名脱敏为「张*海」", records and records[0].get("name") == "张*海",
          "name=%s" % (records[0].get("name") if records else None))
    check("B3 列表手机号脱敏为 139****0001",
          records and records[0].get("phone") == "139****0001",
          "phone=%s" % (records[0].get("phone") if records else None))
    check("B4 列表不返回身份证号 / 地址 / 病史字段",
          records and not any(k in records[0] for k in ("idCard", "address", "medicalHistory")),
          "keys=%s" % (sorted(records[0].keys()) if records else None))

    status, body = call("GET", "/user/elder?keyword=张", None, fam_token)
    check("B5 列表支持姓名模糊搜索", body.get("code") == 200
          and (body.get("data") or {}).get("total") == 1,
          "total=%s" % (body.get("data") or {}).get("total"))

    status, body = call("GET", "/user/elder?bindStatus=UNBOUND", None, fam_token)
    check("B6 列表传 bindStatus=UNBOUND 返回空集（列表只含已绑定老人，见文档已知限制）",
          body.get("code") == 200 and (body.get("data") or {}).get("total") == 0,
          "total=%s" % (body.get("data") or {}).get("total"))

    # ================= C. 档案详情与脱敏 =================
    status, body = call("GET", "/user/elder/%d" % ELDER_OWN, None, fam_token)
    data = body.get("data") or {}
    check("C1 绑定人读详情 → 200 + 全名「张德海」",
          status == 200 and data.get("name") == "张德海", "name=%s" % data.get("name"))
    check("C2 详情身份证为脱敏串（6+8+4），不是库里的 64 位密文",
          re.fullmatch(r"[0-9]{6}\*{8}[0-9Xx]{4}", data.get("idCard") or "") is not None,
          "idCard=%s len=%s" % (data.get("idCard"), len(data.get("idCard") or "")))
    check("C3 详情地址门牌号打码为「海口市美兰区春晖小区***」",
          data.get("address") == "海口市美兰区春晖小区***", "address=%s" % data.get("address"))
    check("C4 详情含病史与过敏史（绑定家属可见，不因谨慎而抹掉）",
          bool(data.get("medicalHistory")), "medicalHistory=%s" % data.get("medicalHistory"))
    check("C5 详情返回与当前家属的关系中文「儿子」",
          data.get("relation") == "儿子", "relation=%s" % data.get("relation"))
    check("C6 详情 createTime 为 yyyy-MM-dd HH:mm:ss",
          re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}", data.get("createTime") or "") is not None,
          "createTime=%s" % data.get("createTime"))

    # ================= D. 归属校验（本模块的安全边界） =================
    status, body = call("GET", "/user/elder/%d" % ELDER_OWN, None, fam2_token)
    check("D1 家属 B 读家属 A 的老人 → 2006（角色合法但无权）",
          body.get("code") == 2006, "http=%s code=%s" % (status, body.get("code")))

    status, body = call("PUT", "/user/elder/%d" % ELDER_OWN,
                        {"address": "越权改的地址"}, fam2_token)
    check("D2 家属 B 改家属 A 的老人 → 2006", body.get("code") == 2006,
          "code=%s" % body.get("code"))

    status, body = call("DELETE", "/user/elder/%d" % ELDER_OWN, None, fam2_token)
    check("D3 家属 B 删家属 A 的老人 → 2006", body.get("code") == 2006,
          "code=%s" % body.get("code"))

    address_now = mysql_value("SELECT `address` FROM `elder_profile` WHERE `id`=%d;" % ELDER_OWN)
    check("D4 越权修改未落库（库里地址未被改动）",
          "越权改的地址" not in address_now, "address=%s" % address_now)

    status, body = call("GET", "/user/elder/%d" % ELDER_UNBOUND, None, fam_token)
    check("D5 读已被解绑的档案（431）→ 2006（UNBOUND 不算有效绑定）",
          body.get("code") == 2006, "code=%s" % body.get("code"))

    status, body = call("GET", "/user/elder/%d" % ELDER_OWN, None, elder_token)
    check("D6 老人读自己的档案 → 200", body.get("code") == 200, "code=%s" % body.get("code"))

    status, body = call("GET", "/user/elder/%d" % ELDER_OWN, None, elder2_token)
    check("D7 老人读别人的档案 → 2006", body.get("code") == 2006, "code=%s" % body.get("code"))

    status, body = call("GET", "/user/elder/%d" % ELDER_OWN, None, admin_token)
    check("D8 管理员无需绑定关系即可读任意档案 → 200",
          body.get("code") == 200, "code=%s" % body.get("code"))

    status, body = call("GET", "/user/elder/999999", None, fam_token)
    check("D9 档案不存在 → 2001", body.get("code") == 2001, "code=%s" % body.get("code"))

    # ================= E. 角色门槛与老人只读 =================
    for name, method, path, token, expect in [
        ("E1  陪诊员读档案列表 → 403", "GET", "/user/elder", comp_token, 403),
        ("E2  陪诊员读档案详情 → 403", "GET", "/user/elder/%d" % ELDER_OWN, comp_token, 403),
        ("E3  老人读档案列表 → 403", "GET", "/user/elder", elder_token, 403),
        ("E4  无令牌读档案详情 → 401", "GET", "/user/elder/%d" % ELDER_OWN, None, 401),
        ("E5  老人新增档案 → 403", "POST", "/user/elder", elder_token, 403),
        ("E6  老人删除档案 → 403", "DELETE", "/user/elder/%d" % ELDER_OWN, elder_token, 403),
        ("E7  老人绑定老人 → 403", "POST", "/user/elder/bind", elder_token, 403),
        ("E8  老人解绑老人 → 403", "DELETE", "/user/elder/%d/bind" % ELDER_OWN, elder_token, 403),
        ("E9  老人申请陪诊员资质 → 403", "POST", "/user/companion/apply", elder_token, 403),
    ]:
        status, body = call(method, path, {}, token)
        check(name, status == expect, "http=%s code=%s" % (status, body.get("code")))

    # ================= F. 进行中订单闸门 =================
    status, body = call("DELETE", "/user/elder/%d" % ELDER_OWN, None, fam_token)
    check("F1 老人有进行中订单时删除档案 → 409",
          body.get("code") == 409 and "进行中" in (body.get("message") or ""),
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("DELETE", "/user/elder/%d/bind" % ELDER_OWN, None, fam_token)
    check("F2 老人有进行中订单时解绑 → 409", body.get("code") == 409, "code=%s" % body.get("code"))

    still = mysql_value("SELECT CONCAT(`bind_status`,'|',`deleted`) FROM `elder_profile` WHERE `id`=%d;"
                        % ELDER_OWN)
    check("F3 被拒的删除/解绑没有留下任何痕迹（仍是 BOUND 且未删除）",
          still == "BOUND|0", "row=%s" % still)

    status, body = call("DELETE", "/user/elder/%d/bind" % ELDER_OTHER, None, fam_token)
    check("F4 解绑不存在的关系 → 2005", body.get("code") == 2005, "code=%s" % body.get("code"))

    # ================= G. 新增档案（真实写入 + 落库校验） =================
    create_payload = {
        "name": "端到端测试老人", "gender": "MALE", "birthDate": "1948-03-12",
        "idCard": PLAIN_ID_CARD, "phone": "13911112222",
        "address": "海南省海口市美兰区人民大道12号3栋501",
        "emergencyContact": "张四", "emergencyPhone": "13899998888",
        "medicalHistory": "高血压、2型糖尿病，长期服药", "allergyHistory": "青霉素过敏",
        "mobilityLevel": "ASSIST", "favoriteHospital": "海南省人民医院", "remark": "端到端测试",
    }
    status, body = call("POST", "/user/elder", create_payload, fam_token)
    new_elder_id = (body.get("data") or {}).get("elderId")
    check("G1 POST /user/elder → 200 且返回 elderId",
          body.get("code") == 200 and bool(new_elder_id),
          "code=%s elderId=%s" % (body.get("code"), new_elder_id))

    row = mysql_value(
        "SELECT CONCAT(`bind_status`,'|',`create_by`,'|',CHAR_LENGTH(`id_card`),'|',"
        "`id_card`='%s') FROM `elder_profile` WHERE `id`=%s;" % (PLAIN_ID_CARD, new_elder_id))
    check("G2 新建档案落库：已绑定 + 建档人为当前家属 + 身份证为 64 位密文（非明文）",
          row.startswith("BOUND|%d|64|0" % fam_id), "row=%s" % row)

    rel = mysql_value("SELECT CONCAT(`bind_type`,'|',`status`,'|',`is_default`) "
                      "FROM `family_elder_relation` WHERE `elder_id`=%s AND `deleted`=0;"
                      % new_elder_id)
    check("G3 建档即绑定：写入 bind_type=CREATE / status=BOUND 的关系行",
          rel == "CREATE|BOUND|1", "row=%s" % rel)

    status, body = call("GET", "/user/elder/%s" % new_elder_id, None, fam_token)
    data = body.get("data") or {}
    check("G4 新档案详情：全名 + 身份证脱敏 + 行动能力中文",
          data.get("name") == "端到端测试老人"
          and re.fullmatch(r"[0-9]{6}\*{8}[0-9Xx]{4}", data.get("idCard") or "") is not None
          and data.get("mobilityLevelLabel") == "需搀扶",
          "name=%s idCard=%s mobility=%s" % (data.get("name"), data.get("idCard"),
                                             data.get("mobilityLevelLabel")))

    status, body = call("GET", "/user/elder", None, fam_token)
    check("G5 新档案立即出现在列表中（建档即绑定，不需额外操作）",
          (body.get("data") or {}).get("total") == 2,
          "total=%s" % (body.get("data") or {}).get("total"))

    # ================= H. 修改档案：不修改 / 清空 的区分 =================
    status, body = call("PUT", "/user/elder/%s" % new_elder_id,
                        {"favoriteHospital": "海口市人民医院"}, fam_token)
    check("H1 局部更新只改传入字段 → 200", body.get("code") == 200, "code=%s" % body.get("code"))

    row = mysql_value("SELECT CONCAT(`favorite_hospital`,'|',`address` IS NOT NULL) "
                      "FROM `elder_profile` WHERE `id`=%s;" % new_elder_id)
    check("H2 未传的字段保持原值（地址未被清空）",
          row == "海口市人民医院|1", "row=%s" % row)

    status, body = call("PUT", "/user/elder/%s" % new_elder_id, {"address": ""}, fam_token)
    row = mysql_value("SELECT CONCAT(`address` IS NULL) FROM `elder_profile` WHERE `id`=%s;"
                      % new_elder_id)
    check("H3 传空串表示清空该字段（库里置为 NULL，updateById 做不到这件事）",
          body.get("code") == 200 and row == "1", "code=%s addressIsNull=%s"
          % (body.get("code"), row))

    status, body = call("PUT", "/user/elder/%s" % new_elder_id, {"name": ""}, fam_token)
    check("H4 姓名传空串 → 400（库里是 NOT NULL 列）", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("PUT", "/user/elder/%s" % new_elder_id, {"gender": "male"}, fam_token)
    check("H5 性别传小写 → 400（只接受大写枚举名）", body.get("code") == 400,
          "code=%s" % body.get("code"))

    # ================= I. 删除档案（逻辑删除 + 保留关联） =================
    status, body = call("DELETE", "/user/elder/%s" % new_elder_id, None, fam_token)
    check("I1 无进行中订单时删除档案 → 200", body.get("code") == 200, "code=%s" % body.get("code"))

    row = mysql_value("SELECT CONCAT(`deleted`,'|',COUNT(*)) FROM `elder_profile` "
                      "WHERE `id`=%s;" % new_elder_id)
    check("I2 逻辑删除：deleted=1 但物理行仍在（历史订单关联不能断）",
          row == "1|1", "row=%s" % row)

    rel = mysql_value("SELECT CONCAT(`status`,'|',`unbind_time` IS NOT NULL) "
                      "FROM `family_elder_relation` WHERE `elder_id`=%s AND `deleted`=0;"
                      % new_elder_id)
    check("I3 删除后绑定关系同步置为 UNBOUND", rel == "UNBOUND|1", "row=%s" % rel)

    status, body = call("GET", "/user/elder/%s" % new_elder_id, None, fam_token)
    check("I4 删除后详情查不到 → 2001", body.get("code") == 2001, "code=%s" % body.get("code"))

    status, body = call("GET", "/user/elder", None, fam_token)
    check("I5 删除后列表回到 1 条", (body.get("data") or {}).get("total") == 1,
          "total=%s" % (body.get("data") or {}).get("total"))

    # ================= J. 绑定 / 解绑完整闭环 =================
    _, body = register(ACC_NEW_ELDER, PHONE_NEW_ELDER, "端到端老人", "ELDER")
    new_elder_user_id = (body.get("data") or {}).get("userId")
    check("J1 注册一个测试老人账号用于绑定用例",
          body.get("code") == 200 and bool(new_elder_user_id),
          "code=%s userId=%s" % (body.get("code"), new_elder_user_id))

    # 该账号的档案由脚本造（接口层没有「给已有账号建档」的入口，那是管理员的活）
    mysql_value("INSERT INTO `elder_profile` (`user_id`,`name`,`gender`,`birth_date`,"
                "`bind_status`,`create_time`,`update_time`,`deleted`) "
                "VALUES (%s,'端到端老人档案','MALE','1945-06-06','UNBOUND',NOW(),NOW(),0);"
                % new_elder_user_id)
    bind_elder_id = mysql_value("SELECT `id` FROM `elder_profile` WHERE `user_id`=%s;"
                                % new_elder_user_id)

    status, body = call("POST", "/user/elder/bind",
                        {"bindType": "PHONE", "bindValue": PHONE_NEW_ELDER, "relation": "DAUGHTER"},
                        fam_token)
    check("J2 家属凭手机号认领老人账号 → 200 且返回 elderId",
          body.get("code") == 200 and (body.get("data") or {}).get("elderId") == int(bind_elder_id),
          "code=%s data=%s" % (body.get("code"), body.get("data")))

    rel = mysql_value("SELECT CONCAT(`family_id`,'|',`relation`,'|',`bind_type`,'|',`status`) "
                      "FROM `family_elder_relation` WHERE `elder_id`=%s AND `deleted`=0;"
                      % bind_elder_id)
    check("J3 绑定关系落库：family_id / DAUGHTER / PHONE / BOUND",
          rel == "%d|DAUGHTER|PHONE|BOUND" % fam_id, "row=%s" % rel)

    status, body = call("POST", "/user/elder/bind",
                        {"bindType": "PHONE", "bindValue": PHONE_NEW_ELDER, "relation": "SON"},
                        fam2_token)
    check("J4 同一个老人被第二个家属绑定 → 2002", body.get("code") == 2002,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("POST", "/user/elder/bind",
                        {"bindType": "PHONE", "bindValue": PHONE_NEW_ELDER, "relation": "SON"},
                        fam_token)
    check("J5 已绑定到自己的账号再次绑定 → 409（不重复建关系）", body.get("code") == 409,
          "code=%s" % body.get("code"))

    status, body = call("DELETE", "/user/elder/%s/bind" % bind_elder_id, None, fam_token)
    check("J6 解绑 → 200", body.get("code") == 200, "code=%s" % body.get("code"))

    row = mysql_value("SELECT CONCAT(`bind_status`) FROM `elder_profile` WHERE `id`=%s;"
                      % bind_elder_id)
    check("J7 解绑后档案 bind_status 回到 UNBOUND", row == "UNBOUND", "row=%s" % row)

    status, body = call("POST", "/user/elder/bind",
                        {"bindType": "PHONE", "bindValue": PHONE_NEW_ELDER, "relation": "SON"},
                        fam2_token)
    check("J8 解绑后另一个家属可以绑定（关系行被复用而不是堆出垃圾行）",
          body.get("code") == 200, "code=%s" % body.get("code"))

    rows = mysql_value("SELECT COUNT(*) FROM `family_elder_relation` "
                       "WHERE `elder_id`=%s AND `deleted`=0;" % bind_elder_id)
    check("J9 反复绑定/解绑只留一行关系记录", rows == "2", "rows=%s" % rows)

    status, body = call("POST", "/user/elder/bind",
                        {"bindType": "INVITE_CODE", "bindValue": "ABC123", "relation": "SON"},
                        fam_token)
    check("J10 绑定方式 INVITE_CODE → 501（一期未开放，DDL 无邀请码字段）",
          body.get("code") == 501, "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("POST", "/user/elder/bind",
                        {"bindType": "PHONE", "bindValue": "12345678901", "relation": "SON"},
                        fam_token)
    check("J11 手机号格式不合法 → 400", body.get("code") == 400, "code=%s" % body.get("code"))

    status, body = call("POST", "/user/elder/bind",
                        {"bindType": "PHONE", "bindValue": "13500000000", "relation": "SON"},
                        fam_token)
    check("J12 手机号没有对应老人账号 → 2001", body.get("code") == 2001, "code=%s" % body.get("code"))

    # ================= K. 陪诊员资质申请 =================
    _, body = register(ACC_NEW_FAMILY, PHONE_NEW_FAMILY, "端到端家属", "FAMILY")
    applicant_id = (body.get("data") or {}).get("userId")
    applicant_token = login_ok(ACC_NEW_FAMILY)["accessToken"]

    apply_payload = {
        "realName": "测试陪诊员", "idCard": PLAIN_ID_CARD_APPLY,
        "serviceArea": "海口市美兰区", "availableTime": "周一至周五 08:00-18:00",
        "certificates": [
            {"name": "健康证", "url": "/uploads/202609/health.jpg"},
            {"name": "身份证正面", "url": "/uploads/202609/idcard.jpg"},
        ],
        "remark": "有 3 年陪诊经验",
    }
    status, body = call("POST", "/user/companion/apply", apply_payload, applicant_token)
    application_id = (body.get("data") or {}).get("applicationId")
    check("K1 提交资质申请 → 200 + PENDING + 返回申请号",
          body.get("code") == 200 and (body.get("data") or {}).get("auditStatus") == "PENDING"
          and bool(application_id),
          "code=%s data=%s" % (body.get("code"), body.get("data")))

    row = mysql_value(
        "SELECT CONCAT(`audit_status`,'|',CHAR_LENGTH(`id_card`),'|',"
        "JSON_VALID(`certificates`),'|',JSON_LENGTH(`certificates`)) "
        "FROM `companion_audit_record` WHERE `id`=%s;" % application_id)
    check("K2 申请流水落库：PENDING + 身份证密文 + 证件为合法 JSON（2 项）",
          row == "PENDING|64|1|2", "row=%s" % row)

    row = mysql_value("SELECT CONCAT(`audit_status`,'|',`work_status`) FROM `companion_profile` "
                      "WHERE `user_id`=%s AND `deleted`=0;" % applicant_id)
    check("K3 快照同步为 PENDING + REST（未审核通过不接单）", row == "PENDING|REST", "row=%s" % row)

    status, body = call("POST", "/user/companion/apply", apply_payload, applicant_token)
    check("K4 已有待审核申请时重复提交 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    status, body = call("GET", "/user/companion/application", None, applicant_token)
    data = body.get("data") or {}
    check("K5 查询自己的申请状态 → PENDING + 无驳回原因",
          data.get("auditStatus") == "PENDING" and data.get("rejectReason") is None,
          "data=%s" % data)
    check("K5b 时间字段为 yyyy-MM-dd HH:mm:ss（JacksonConfig 生效，不是 ISO 带 T）",
          re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}", data.get("submitTime") or "") is not None,
          "submitTime=%s" % data.get("submitTime"))

    status, body = call("GET", "/user/companion/application", None, fam_token)
    check("K6 从未申请过的账号查询 → data 为 null", body.get("data") is None,
          "data=%s" % body.get("data"))

    # 角色不会因为提交申请而改变
    role_now = mysql_value("SELECT `role` FROM `sys_user` WHERE `id`=%s;" % applicant_id)
    check("K7 提交申请不改变角色（审核通过才升级为 COMPANION）",
          role_now == "FAMILY", "role=%s" % role_now)

    _, body = register("e2eadm" + STAMP, "15" + STAMP[-9:], "端到端管理员", "ADMIN")
    check("K8 注册 role=ADMIN 仍被拒绝 → 400（申请入口不可能产出管理员）",
          body.get("code") == 400, "code=%s" % body.get("code"))

    # ================= L. 陪诊员公开资料 =================
    status, body = call("GET", "/user/companion/301", None, fam_token)
    data = body.get("data") or {}
    check("L1 公开资料 id 取用户 ID（301，与订单表一致）",
          data.get("id") == 301, "id=%s" % data.get("id"))
    check("L2 姓名脱敏为「李*军」，评分两位小数字符串 4.00",
          data.get("realName") == "李*军" and data.get("score") == "4.00",
          "realName=%s score=%s" % (data.get("realName"), data.get("score")))
    check("L3 资质状态中文「已通过」，接单状态中文「可接单」",
          data.get("auditStatusLabel") == "已通过" and data.get("workStatusLabel") == "可接单",
          "audit=%s work=%s" % (data.get("auditStatusLabel"), data.get("workStatusLabel")))

    _, raw = raw_body("GET", "/user/companion/301", None, fam_token)
    leaked = [k for k in ("idCard", "phone", "certificates", "rejectReason", "introduction")
              if k in raw]
    check("L4 公开资料不含身份证 / 电话 / 证件 / 驳回原因", not leaked, "leaked=%s" % leaked)

    status, body = call("GET", "/user/companion/601", None, fam_token)
    check("L5 用 companion_profile 主键（601）当参数查不到 → 2007",
          body.get("code") == 2007, "code=%s" % body.get("code"))

    status, body = call("GET", "/user/companion/999999", None, fam_token)
    check("L6 陪诊员不存在 → 2007", body.get("code") == 2007, "code=%s" % body.get("code"))

    # ================= M. 参数校验 =================
    for name, payload, path in [
        ("M1  新增档案缺姓名 → 400", {"gender": "MALE", "birthDate": "1948-03-12"}, "/user/elder"),
        ("M2  出生日期晚于今天 → 400",
         {"name": "测试老人", "gender": "MALE", "birthDate": "2099-01-01"}, "/user/elder"),
        ("M3  手机号格式非法 → 400",
         {"name": "测试老人", "gender": "MALE", "birthDate": "1948-03-12",
          "phone": "12345"}, "/user/elder"),
        ("M4  行动能力枚举非法 → 400",
         {"name": "测试老人", "gender": "MALE", "birthDate": "1948-03-12",
          "mobilityLevel": "RUN"}, "/user/elder"),
        ("M5  证件清单为空 → 400",
         {"realName": "测试陪诊员", "idCard": PLAIN_ID_CARD_APPLY,
          "serviceArea": "海口市", "availableTime": "随时", "certificates": []},
         "/user/companion/apply"),
        ("M6  身份证号格式非法 → 400",
         {"realName": "测试陪诊员", "idCard": "123", "serviceArea": "海口市",
          "availableTime": "随时", "certificates": [{"name": "健康证", "url": "/uploads/a.jpg"}]},
         "/user/companion/apply"),
    ]:
        token = fam_token if path == "/user/elder" else applicant_token
        status, body = call("POST", path, payload, token)
        check(name, body.get("code") == 400, "code=%s message=%s"
              % (body.get("code"), body.get("message")))

    # ================= 清理测试产物 =================
    mysql_value("DELETE FROM `family_elder_relation` WHERE `elder_id` IN "
                "(SELECT `id` FROM `elder_profile` WHERE `name` IN ('端到端测试老人','端到端老人档案') "
                "OR `remark`='端到端测试');")
    mysql_value("DELETE FROM `elder_profile` WHERE `name` IN ('端到端测试老人','端到端老人档案') "
                "OR `remark`='端到端测试';")
    mysql_value("DELETE FROM `companion_audit_record` WHERE `applicant_user_id`=%s;" % (applicant_id or 0))
    mysql_value("DELETE FROM `companion_profile` WHERE `user_id`=%s;" % (applicant_id or 0))
    mysql_value("DELETE FROM `sys_user` WHERE `username` LIKE 'e2e%%';")
    mysql_value("DELETE FROM `sys_login_log` WHERE `username` LIKE 'e2e%%';")
    redis_del("pwd:version:" + str(applicant_id or 0),
              "pwd:version:" + str(new_elder_user_id or 0))
    # 复位被改过的种子昵称
    mysql_value("UPDATE `sys_user` SET `nickname`='家属01' WHERE `username`='fam001';")
    print("\n[清理] 已删除 e2e* 账号 / 档案 / 绑定关系 / 资质申请，并复位 fam001 昵称")

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
