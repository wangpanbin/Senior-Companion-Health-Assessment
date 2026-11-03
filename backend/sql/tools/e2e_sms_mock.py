# -*- coding: utf-8 -*-
"""V4 · P1-3 e2e：通知偏好 + 漏服站外短信（mock 通道）。

验证五件事：
    1. 偏好接口可读可写，PUT 后 GET 读回一致；
    2. 关闭站外短信后，漏服只发站内信，sms_send_log 没有任何 SENT 行；
    3. 同一漏服任务下，两位开启短信的家属各收到一条（幂等键含 userId）；
    4. 单用户日频控：同一家属当日达到上限后，新任务不再产生回执行；
    5. 清理本脚本自己造的行（计划 / 任务 / 短信回执 / 站内信 / 临时绑定关系 / 偏好快照恢复）。

依赖：
    - 后端以 dev profile 起在 8080（$env:SPRING_PROFILES_ACTIVE="dev"）；
    - 为了让 30 分钟一轮的漏服扫描在脚本里可等，启动时应额外覆盖：
        $env:NIANGLIN_MEDICATION_MISSED_SCAN_INITIAL_DELAY_MS="5000"
        $env:NIANGLIN_MEDICATION_MISSED_SCAN_INTERVAL_MS="10000"
    - 频控阶段断言 cap=2，启动时还必须覆盖：
        $env:NIANGLIN_SMS_FREQUENCY_MISSEDDOSEDAILYCAP="2"
      （都只是运行期参数覆盖，不改调度代码与生产默认节奏 / 默认阈值）

红线：脚本只操作自己造的数据；不打印完整手机号；不写任何真实密钥。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cleanup_util  # noqa: E402
import e2e_medication as med  # noqa: E402

FAMILY_ID = 101
FAMILY_ID_2 = 102
ELDER_ID = 401
FULL_PHONE_1 = "13800100001"  # 只用于断言「未落库」，不打印
FULL_PHONE_2 = "13800100002"
MASKED_PHONE_1 = "138****0001"
MASKED_PHONE_2 = "138****0002"
E2E_CAP = 2  # 必须与启动时的 NIANGLIN_SMS_FREQUENCY_MISSEDDOSEDAILYCAP 一致

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
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(`status`,'|',`scene`,'|',`user_id`,'|',"
        "`phone_masked`,'|',`dedup_key`) SEPARATOR ';'),'') "
        "FROM `sms_send_log` WHERE `biz_id`=%d;" % task_id) or ""


def wait_sent(task_id, expected, timeout=60):
    """等待 mock 通道把任务的消息发完（回执行数达到 expected）。"""
    deadline = time.time() + timeout
    rows = ""
    while time.time() < deadline:
        rows = sms_rows(task_id)
        if rows.count("SENT|") >= expected:
            return rows
        time.sleep(2)
    return rows


def sms_count(task_ids, user_id):
    ids = ",".join(str(i) for i in task_ids)
    return int(cleanup_util.mysql_value(
        "SELECT COUNT(*) FROM `sms_send_log` WHERE `biz_id` IN (%s) AND `user_id`=%d;"
        % (ids, user_id)) or 0)


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


def bind_second_family():
    """临时把 fam002(102) 绑到老人 401，让同一漏服任务有两个家属收件人。

    返回 (关系 id, 是否脚本新建, 原状态或 None)。清理时按原状态还原。
    """
    row = cleanup_util.mysql_value(
        "SELECT IFNULL(CONCAT(`id`,'|',`status`),'') FROM `family_elder_relation` "
        "WHERE `family_id`=%d AND `elder_id`=%d LIMIT 1;" % (FAMILY_ID_2, ELDER_ID))
    if row:
        rel_id, status_before = row.split("|")
        cleanup_util.mysql_raw(
            "UPDATE `family_elder_relation` SET `status`='BOUND' WHERE `id`=%s;" % rel_id)
        return int(rel_id), False, status_before
    rel_id = cleanup_util.max_id("family_elder_relation") + 1
    cleanup_util.mysql_raw(
        "INSERT INTO `family_elder_relation` "
        "(`id`,`family_id`,`elder_id`,`relation`,`bind_type`,`is_default`,`status`,`bind_time`) VALUES "
        "(%d,%d,%d,'DAUGHTER','PHONE',0,'BOUND',NOW());" % (rel_id, FAMILY_ID_2, ELDER_ID))
    return rel_id, True, None


def pref_snapshot(user_id):
    before = cleanup_util.mysql_value(
        "SELECT CONCAT(`sms_missed_enabled`,'|',`quiet_hours_enabled`) "
        "FROM `notification_preference` WHERE `user_id`=%d ORDER BY `id` DESC LIMIT 1;" % user_id)
    return before


def main():
    token = med.login_ok(med.ACC_FAMILY)
    token2 = med.login_ok(med.ACC_FAMILY_OTHER)
    message_baseline = cleanup_util.max_id("internal_message")

    pref_before_1 = pref_snapshot(FAMILY_ID)
    pref_before_2 = pref_snapshot(FAMILY_ID_2)
    rel_id, rel_owned, rel_status_before = bind_second_family()

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
        # 注意：102 此时已临时绑定且默认开启短信，所以只看 101 自己的回执
        own_rows = cleanup_util.mysql_value(
            "SELECT IFNULL(GROUP_CONCAT(CONCAT(`status`,'|',`scene`,'|',`user_id`) SEPARATOR ';'),'') "
            "FROM `sms_send_log` WHERE `biz_id`=%d AND `user_id`=%d;" % (task_b, FAMILY_ID)) or ""
        check("B2 关闭后本人漏服只发站内信（无本人回执行）", own_rows == "",
              "sms=%s" % (own_rows or "(空)"))
        msg_count = int(cleanup_util.mysql_value(
            "SELECT COUNT(*) FROM `internal_message` WHERE `receiver_id`=%d AND `id`>%d "
            "AND `type` LIKE 'MEDICATION%%';" % (FAMILY_ID, message_baseline)) or 0)
        check("B3 关闭站外短信不影响站内信", msg_count >= 1, "站内信=%d" % msg_count)

    # 阶段 C：两位家属都开启站外短信（静默关闭，避免测试时段落在 22:00–08:00）
    for tok, uid in ((token, FAMILY_ID), (token2, FAMILY_ID_2)):
        status, body = med.call("PUT", "/message/notification-preference",
                                {"smsMissedEnabled": True, "quietHoursEnabled": False}, tok)
        check("C1 家属 %d 开启站外短信" % uid,
              status == 200 and (body.get("data") or {}).get("smsMissedEnabled") is True,
              str(body.get("data")))

    task_c = cleanup_util.max_id("medication_task") + 1
    plan_c = cleanup_util.max_id("medication_plan") + 1
    make_overdue_task(plan_c, task_c)
    task_ids.append(task_c)
    plan_ids.append(plan_c)

    if wait_scanned(task_c):
        rows = wait_sent(task_c, 2)
        check("C2 同一任务两位家属各收到一条",
              rows.count("SENT|") == 2 and ("|%d|" % FAMILY_ID) in rows
              and ("|%d|" % FAMILY_ID_2) in rows,
              "sms=%s" % rows)
        check("C3 幂等键含 userId 且只存脱敏手机号",
              ("medication-missed:%d:%d:" % (task_c, FAMILY_ID)) in rows
              and ("medication-missed:%d:%d:" % (task_c, FAMILY_ID_2)) in rows
              and MASKED_PHONE_1 in rows and MASKED_PHONE_2 in rows
              and FULL_PHONE_1 not in rows and FULL_PHONE_2 not in rows,
              "sms=%s" % rows)

    # 阶段 D：单用户日频控（启动时 cap=2）。先删掉本脚本此前所有回执行，让计数从 0 开始
    # （B 阶段 102 已收到 1 条，若不清掉会占用其当日额度，导致 D1 计数不公平）
    cleanup_util.mysql_raw(
        "DELETE FROM `sms_send_log` WHERE `biz_id` IN (%s);"
        % ",".join(str(i) for i in task_ids))
    cap_tasks = []
    for _ in range(E2E_CAP + 1):
        tid = cleanup_util.max_id("medication_task") + 1
        pid = cleanup_util.max_id("medication_plan") + 1
        make_overdue_task(pid, tid)
        task_ids.append(tid)
        plan_ids.append(pid)
        cap_tasks.append(tid)

    scanned = all(wait_scanned(tid) for tid in cap_tasks)
    if scanned:
        # 前 cap 个任务：每位家属各 1 条；第 cap+1 个任务：两人都超限，零回执
        wait_sent(cap_tasks[0], 2)
        wait_sent(cap_tasks[1], 2)
        time.sleep(5)  # 给扫描器处理第 cap+1 个任务的时间
        cnt_1 = sms_count(cap_tasks, FAMILY_ID)
        cnt_2 = sms_count(cap_tasks, FAMILY_ID_2)
        check("D1 频控内每位家属各 %d 条（每个任务各 1 条）" % E2E_CAP,
              cnt_1 == E2E_CAP and cnt_2 == E2E_CAP,
              "101=%d 102=%d" % (cnt_1, cnt_2))
        # 三条任务里应恰好有一条「两位家属都没有回执」——即超限后被拦的那条
        empty_tasks = [t for t in cap_tasks if sms_rows(t) == ""]
        check("D2 同人同日超限的新任务被拦（两位家属都不产生回执）",
              len(empty_tasks) == 1,
              "空回执任务=%s" % (empty_tasks or "(无)"))

    # 阶段 E：PUT 后 GET 读回一致
    for tok, uid, expects in ((token, FAMILY_ID, True), (token2, FAMILY_ID_2, True)):
        status, body = med.call("GET", "/message/notification-preference", token=tok)
        check("E1 家属 %d PUT 后 GET 读回一致" % uid,
              (body.get("data") or {}).get("smsMissedEnabled") is expects
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

    if rel_owned:
        cleanup_util.mysql_raw("DELETE FROM `family_elder_relation` WHERE `id`=%d;" % rel_id)
        print("[清理] 已删除临时家属绑定关系 %d" % rel_id)
    else:
        cleanup_util.mysql_raw(
            "UPDATE `family_elder_relation` SET `status`='%s' WHERE `id`=%d;"
            % (rel_status_before, rel_id))
        print("[清理] 绑定关系 %d 服务端原本就存在，恢复为 %s" % (rel_id, rel_status_before))

    for uid, before in ((FAMILY_ID, pref_before_1), (FAMILY_ID_2, pref_before_2)):
        if before:
            sms_flag, quiet_flag = before.split("|")
            cleanup_util.mysql_raw(
                "UPDATE `notification_preference` SET `sms_missed_enabled`=%s, `quiet_hours_enabled`=%s "
                "WHERE `user_id`=%d;" % (sms_flag, quiet_flag, uid))
            print("[清理] 通知偏好 %d 已恢复为 %s" % (uid, before))
        else:
            cleanup_util.mysql_raw(
                "DELETE FROM `notification_preference` WHERE `user_id`=%d;" % uid)
            print("[清理] 通知偏好 %d：删除脚本新增行（原本不存在）" % uid)

    failed = [r for r in results if not r[1]]
    print("\n===== e2e_sms_mock 结果：%d/%d 通过 =====" % (len(results) - len(failed), len(results)))
    return 0 if not failed else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print("[FATAL] %s" % exc)
        sys.exit(2)
