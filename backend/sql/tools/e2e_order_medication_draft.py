# -*- coding: utf-8 -*-
"""银龄伴诊 —— V4 P1-2「陪诊 → 用药贯通：服务小结转用药计划草稿」端到端实测。

覆盖 docs/api/05-medication.md §10：
    1. 下单家属对 COMPLETED + 有服务小结的订单转草稿成功；
    2. 响应**不含** dosage / 剂量字段，药名命中药品字典时 matched=true；
    3. 非本单家属 → 3004；
    4. 订单未完成 → 409；
    5. 30 分钟内重复调用 → 11002（一单一转）；
    6. 非 FAMILY 角色 → 403。

与其它 e2e 脚本同一原则：除验证码旁路外全部走正常接口；
main 开头记基线，__main__ 的 finally 统一清理（见 cleanup_util.py）。

用法（PowerShell，后端必须显式 dev profile，application.yml 刻意无默认 profile）：
    $env:SPRING_PROFILES_ACTIVE="dev"
    python e2e_order_medication_draft.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from e2e_order import (  # noqa: E402  复用登录 / 下单 / 订单流转与清理样板
    ACC_ADMIN,
    ACC_COMPANION,
    ACC_FAMILY,
    ACC_FAMILY_OTHER,
    ELDER_OWN,
    call,
    create_order,
    login_ok,
    mysql_value,
    raw_body,
)
import cleanup_util  # noqa: E402

DRAFT_KEY_TEMPLATE = "medication:draft:from-order:%s"
# 种子药品字典里的第一条：苯磺酸氨氯地平片
KNOWN_DRUG_NAME = "苯磺酸氨氯地平片"

results = []
order_completed = None
order_pending = None
created_order_ids = []
log_id_start = 0
message_id_start = 0


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  -> " + detail) if detail else ""))
    sys.stdout.flush()


def redis_del(key):
    import subprocess
    from cleanup_util import REDIS_CLI
    subprocess.run([REDIS_CLI, "DEL", key], capture_output=True, text=True)


def draft_body(elder_id, items=None):
    return {
        "elderId": elder_id,
        "items": items or [
            {"drugName": KNOWN_DRUG_NAME, "frequency": 1, "timePoint": "08:00"},
            {"drugName": "未收录的测试药名", "frequency": 2, "timePoint": "12:00"},
        ],
    }


def complete_order(fam_token, comp_token):
    """造一张真实走完流程的 COMPLETED 订单（含合规服务小结）。"""
    status, body = create_order(fam_token)
    order_id = (body.get("data") or {}).get("orderId")
    if not order_id:
        raise RuntimeError("下单失败：%s %s" % (status, body))
    created_order_ids.append(int(order_id))
    for path in ("accept", "start"):
        _, resp = call("POST", "/order/%d/%s" % (order_id, path), None, comp_token)
        if resp.get("code") != 200:
            raise RuntimeError("%s 失败：%s" % (path, resp))
    _, resp = call("POST", "/order/%d/complete" % order_id, {
        "summary": "09:10 到达医院，全程陪同完成心血管内科就诊，已协助缴费与取药，11:50 送老人回家",
        "fee": 128.00,
    }, comp_token)
    if resp.get("code") != 200:
        raise RuntimeError("complete 失败：%s" % resp)
    return int(order_id)


def cleanup():
    ids = ",".join(str(i) for i in created_order_ids) or "0"
    for oid in created_order_ids:
        redis_del(DRAFT_KEY_TEMPLATE % oid)
    if created_order_ids:
        mysql_value("DELETE FROM `order_fee_item` WHERE `order_id` IN (%s);" % ids)
        mysql_value("DELETE FROM `order_fulfillment_event` WHERE `order_id` IN (%s);" % ids)
        mysql_value("DELETE FROM `order_status_log` WHERE `order_id` IN (%s);" % ids)
        mysql_value("DELETE FROM `order_reject_log` WHERE `order_id` IN (%s);" % ids)
        mysql_value("DELETE FROM `companion_order` WHERE `id` IN (%s);" % ids)
    cleanup_util.delete_since("sys_login_log", log_id_start, "sys_login_log")
    cleanup_util.delete_since("internal_message", message_id_start, "internal_message")
    cleanup_util.flush_unread_cache()

    left = mysql_value("SELECT COUNT(*) FROM `companion_order` WHERE `id` IN (%s);" % ids)
    print("\n[清理] 已删除 %d 条测试订单（含费用明细 / 状态日志 / 草稿 Redis 键），残留 %s 条"
          % (len(created_order_ids), left))


def main():
    global order_completed, order_pending, log_id_start, message_id_start

    print("=" * 78)
    print("银龄伴诊 V4 P1-2 订单转用药计划草稿 端到端实测")
    print("=" * 78)

    log_id_start = cleanup_util.max_id("sys_login_log")
    message_id_start = cleanup_util.max_id("internal_message")

    fam_token = login_ok(ACC_FAMILY)["accessToken"]
    fam2_token = login_ok(ACC_FAMILY_OTHER)["accessToken"]
    comp_token = login_ok(ACC_COMPANION)["accessToken"]
    admin_token = login_ok(ACC_ADMIN)["accessToken"]

    # ================= A. 成功路径：COMPLETED + 服务小结 + 无剂量 =================
    plan_count_before = mysql_value("SELECT COUNT(*) FROM `medication_plan` WHERE `deleted`=0;")
    order_completed = complete_order(fam_token, comp_token)
    redis_del(DRAFT_KEY_TEMPLATE % order_completed)

    status, raw = raw_body("POST", "/medication/plan/from-order/%d" % order_completed,
                           draft_body(ELDER_OWN), fam_token)
    payload = {}
    try:
        import json
        payload = json.loads(raw)
    except ValueError:
        pass
    data = payload.get("data") or {}
    items = data.get("items") or []

    check("A1 下单家属对 COMPLETED 订单转草稿 → 200", payload.get("code") == 200,
          "http=%s body=%s" % (status, raw[:180]))
    check("A2 响应不含 dosage / 剂量字段（硬红线）",
          "dosage" not in raw.lower() and "剂量" not in raw and "用量" not in raw,
          "raw=%s" % raw[:180])
    check("A3 药品名命中字典 → matched=true 且回填 medicineId",
          bool(items) and items[0].get("matched") is True and items[0].get("medicineId") is not None,
          "item0=%s" % (items[0] if items else None))
    check("A4 未命中药名 → matched=false 且保留原文，不报错",
          len(items) > 1 and items[1].get("matched") is False
          and items[1].get("medicineId") is None
          and items[1].get("medicineName") == "未收录的测试药名",
          "item1=%s" % (items[1] if len(items) > 1 else None))
    check("A5 draftTtlMinutes=30", data.get("draftTtlMinutes") == 30, "data=%s" % data)
    plan_count_after = mysql_value("SELECT COUNT(*) FROM `medication_plan` WHERE `deleted`=0;")
    check("A6 转草稿不落库（用药计划表行数前后一致）",
          plan_count_after == plan_count_before,
          "before=%s after=%s" % (plan_count_before, plan_count_after))

    # ================= B. 归属：非本单家属 → 3004 =================
    _, body = call("POST", "/medication/plan/from-order/%d" % order_completed,
                   draft_body(ELDER_OWN), fam2_token)
    check("B1 非本单家属调用 → 3004", body.get("code") == 3004,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= C. 状态：未完成订单 → 409 =================
    status, body = create_order(fam_token)
    order_pending = (body.get("data") or {}).get("orderId")
    if order_pending:
        created_order_ids.append(int(order_pending))
    _, body = call("POST", "/medication/plan/from-order/%s" % order_pending,
                   draft_body(ELDER_OWN), fam_token)
    check("C1 订单未完成（PENDING）→ 409", body.get("code") == 409,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= D. 一单一转：30 分钟内重复 → 11002 =================
    _, body = call("POST", "/medication/plan/from-order/%d" % order_completed,
                   draft_body(ELDER_OWN), fam_token)
    check("D1 30 分钟内重复转草稿 → 11002", body.get("code") == 11002,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= E. 角色：非 FAMILY → 403 =================
    _, body = call("POST", "/medication/plan/from-order/%d" % order_completed,
                   draft_body(ELDER_OWN), comp_token)
    check("E1 陪诊员调用 → 403", body.get("code") == 403,
          "code=%s message=%s" % (body.get("code"), body.get("message")))
    _, body = call("POST", "/medication/plan/from-order/%d" % order_completed,
                   draft_body(ELDER_OWN), admin_token)
    check("E2 管理员调用 → 403", body.get("code") == 403,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    # ================= F. 老人档案不一致 → 400 =================
    _, body = call("POST", "/medication/plan/from-order/%d" % order_completed,
                   draft_body(402), fam_token)
    check("F1 elderId 与订单不一致 → 400", body.get("code") == 400,
          "code=%s message=%s" % (body.get("code"), body.get("message")))

    failed = [r for r in results if not r[1]]
    print("\n" + "=" * 78)
    print("结果：%d 项通过 / %d 项失败" % (len(results) - len(failed), len(failed)))
    for name, _, detail in failed:
        print("  FAIL: %s  -> %s" % (name, detail))
    print("=" * 78)
    return 0 if not failed else 1


if __name__ == "__main__":
    code = 1
    try:
        code = main()
    finally:
        cleanup()
    sys.exit(code)
