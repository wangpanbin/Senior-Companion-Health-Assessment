# -*- coding: utf-8 -*-
"""V4 · P1-3 e2e：通知偏好 + 漏服站外短信（mock 通道）。

验证四件事：
    1. 偏好接口可读可写，PUT 后 GET 读回一致；
    2. 关闭站外短信后，漏服只发站内信，sms_send_log 没有任何 SENT 行；
    3. 开启站外短信后，mock 通道产生 SENT 行（不联网、真实手机不会收到）；
    4. 清理本脚本自己造的行（计划 / 任务 / 短信回执 / 站内信 / 偏好快照恢复）。

依赖：
    - 后端以 dev profile 起在 8080（$env:SPRING_PROFILES_ACTIVE="dev"）；
    - 为了让 30 分钟一轮的漏服扫描在脚本里可等，启动时应额外覆盖：
        $env:NIANGLIN_MEDICATION_MISSED_SCAN_INITIAL_DELAY_MS="5000"
        $env:NIANGLIN_MEDICATION_MISSED_SCAN_INTERVAL_MS="10000"
      （这只是运行期参数覆盖，不改 MedicationScheduler 的代码与生产默认节奏）

红线：脚本只操作自己造的数据；不打印完整手机号；不写任何真实密钥。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cleanup_util  # noqa: E402
import e2e_medication as med  # noqa: E402

FAMILY_ID = 101
ELDER_ID = 401
FULL_PHONE = "13900100001"  # 只用于断言「未落库」，不打印

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name,
                        ("  -> " + detail) if detail else ""))
    sys.stdout.flush()


def wait_scanned(task_id, timeout=180):
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        last = cleanup_util.mysql_value(
            "SELECT CONCAT(`status`,`notify_sent`) FROM `medication_task` WHERE `id`=%d;" % task_id)
        if last.startswith("MISSED1"):
            return True
        time.sleep(3)
    check("任务 %d 被漏服扫描判定并完成通知" % task_id, False, "最后状态=%s" % (last or "(无)"))
    return False


def sms_rows(task_id):
    return cleanup_util.mysql_value(
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(`status`,'|',`scene`,'|',`phone_masked`) "
        "SEPARATOR ';'),'') FROM `sms_send_log` WHERE `biz_id`=%d;" % task_id) or ""


def make_overdue_task(plan_id, task_id):
    target = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() - 90 * 60))
    plan_date = target[:10]
    cleanup_util.mysql_raw(
        "INSERT INTO `medication_plan` "
        "(`id`,`elder_id`,`medicine_id`,`medicine_name`,`dosage`,`frequency`,`time_points`,"
        " `start_date`,`end_date`,`meal_relation`,`status`,`remark`,`created_by`,`create_time`) VALUES "
        "(%d,%d,801,'E2E漏服短信测试药','1 片',1,'[\"08:00\"]','%s',NULL,'AFTER_MEAL','ACTIVE',"
        "'e2e-sms-mock',%d,NOW());"
        % (plan_id, ELDER_ID, plan_date, FAMILY_ID))
    cleanup_util.mysql_raw(
        "INSERT INTO `medication_task` "
        "(`id`,`plan_id`,`elder_id`,`medicine_id`,`medicine_name`,`dosage`,`meal_relation`,"
        " `plan_date`,`plan_time`,`status`,`was_missed`,`notify_sent`,`create_time`) VALUES "
        "(%d,%d,%d,801,'E2E漏服短信测试药','1 片','AFTER_MEAL','%s','%s','PENDING',0,0,NOW());"
        % (task_id, plan_id, ELDER_ID, plan_date, target))


def main():
    token = med.login_ok(med.ACC_FAMILY)
    message_baseline = cleanup_util.max_id("internal_message")

    # 偏好快照：脚本结束要恢复，不能把开发库里的真实设置改坏
    pref_before = cleanup_util.mysql_value(
        "SELECT CONCAT(`sms_missed_enabled`,'|',`quiet_hours_enabled`) "
        "FROM `notification_preference` WHERE `user_id`=%d ORDER BY `id` DESC LIMIT 1;" % FAMILY_ID)
    pref_existed = bool(pref_before)

    task_ids = []
    plan_ids = []

    status, body = med.call("GET", "/message/notification-preference", token=token)
    check("A1 家属读取通知偏好", status == 200 and body.get("code") == 200,
          "status=%s body=%s" % (status, body.get("code")))
    check("A2 未设置过时默认两个开关开启",
          (body.get("data") or {}).get("smsMissedEnabled") is True
          and (body.get("data") or {}).get("quietHoursEnabled") is True,
          str(body.get("data")))

    # 阶段 B：关闭站外短信
    status, body = med.call("PUT", "/message/notification-preference",
                            {"smsMissedEnabled": False, "quietHoursEnabled": False}, token)
    check("B1 家属关闭站外短信 / 静默",
          status == 200 and body.get("code") == 200
          and (body.get("data") or {}).get("smsMissedEnabled") is False,
          str(body.get("data")))

    task_b = cleanup_util.max_id("medication_task") + 1
    plan_b = cleanup_util.max_id("medication_plan") + 1
    make_overdue_task(plan_b, task_b)
    task_ids.append(task_b)
    plan_ids.append(plan_b)

    if wait_scanned(task_b):
        rows = sms_rows(task_b)
        check("B2 关闭后漏服只发站内信（sms_send_log 无记录）", rows == "", "sms=%s" % (rows or "(空)"))
        msg_count = int(cleanup_util.mysql_value(
            "SELECT COUNT(*) FROM `internal_message` WHERE `receiver_id`=%d AND `id`>%d "
            "AND `type` LIKE 'MEDICATION%%';" % (FAMILY_ID, message_baseline)) or 0)
        check("B3 关闭站外短信不影响站内信", msg_count >= 1, "站内信=%d" % msg_count)

    # 阶段 C：开启站外短信（静默关闭，避免测试时段落在 22:00–08:00）
    status, body = med.call("PUT", "/message/notification-preference",
                            {"smsMissedEnabled": True, "quietHoursEnabled": False}, token)
    check("C1 家属开启站外短信",
          status == 200 and (body.get("data") or {}).get("smsMissedEnabled") is True,
          str(body.get("data")))

    task_c = cleanup_util.max_id("medication_task") + 1
    plan_c = cleanup_util.max_id("medication_plan") + 1
    make_overdue_task(plan_c, task_c)
    task_ids.append(task_c)
    plan_ids.append(plan_c)

    if wait_scanned(task_c):
        deadline = time.time() + 60
        rows = ""
        while time.time() < deadline:
            rows = sms_rows(task_c)
            if "SENT" in rows:
                break
            time.sleep(2)
        check("C2 开启后 mock 通道产生 SENT 行", "SENT|missed-dose|" in rows, "sms=%s" % rows)
        check("C3 回执只存脱敏手机号", "****" in rows and FULL_PHONE not in rows, "sms=%s" % rows)

    status, body = med.call("GET", "/message/notification-preference", token=token)
    check("D1 PUT 后 GET 读回一致",
          (body.get("data") or {}).get("smsMissedEnabled") is True
          and (body.get("data") or {}).get("quietHoursEnabled") is False,
          str(body.get("data")))

    # 清理：只删本脚本 biz_id 对应的回执；计划 / 任务按显式 id 删；站内信按基线删
    cleanup_util.mysql_raw(
        "DELETE FROM `sms_send_log` WHERE `biz_id` IN (%s);"
        % ",".join(str(i) for i in task_ids))
    cleanup_util.mysql_raw(
        "DELETE FROM `medication_task` WHERE `id` IN (%s);"
        % ",".join(str(i) for i in task_ids))
    cleanup_util.mysql_raw(
        "DELETE FROM `medication_plan` WHERE `id` IN (%s);"
        % ",".join(str(i) for i in plan_ids))
    cleanup_util.delete_since("internal_message", message_baseline, "本次漏服站内信")
    cleanup_util.flush_unread_cache()

    if pref_existed:
        sms_flag, quiet_flag = pref_before.split("|")
        cleanup_util.mysql_raw(
            "UPDATE `notification_preference` SET `sms_missed_enabled`=%s, `quiet_hours_enabled`=%s "
            "WHERE `user_id`=%d;" % (sms_flag, quiet_flag, FAMILY_ID))
        print("[清理] 通知偏好已恢复为 %s" % pref_before)
    else:
        cleanup_util.mysql_raw(
            "DELETE FROM `notification_preference` WHERE `user_id`=%d;" % FAMILY_ID)
        print("[清理] 通知偏好：删除脚本新增行（原本不存在）")

    failed = [r for r in results if not r[1]]
    print("\n===== e2e_sms_mock 结果：%d/%d 通过 =====" % (len(results) - len(failed), len(results)))
    return 0 if not failed else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print("[FATAL] %s" % exc)
        sys.exit(2)
