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
      （只是运行期参数覆盖，不改调度代码与生产默认节奏）
    - 频控上限不再用环境变量断言：P2-3 起运营参数（sys_dict 的
      sms.frequency.missed-dose-daily-cap）优先于 SmsProperties，
      NIANGLIN_SMS_FREQUENCY_MISSEDDOSEDAILYCAP 只在 sys_dict 无启用行时兜底。
      脚本改为运行时读取 sys_dict 的实际值，并按「当日剩余额度」断言。

红线：脚本只操作自己造的数据；不打印完整手机号；不写任何真实密钥。
"""
import atexit
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
DEFAULT_CAP = 3  # application.yml 默认值：sys_dict 无启用行时才用它

# 收尾状态：异常（含中断）时由 atexit 统一清理。半截运行留在库里的任务 / 回执
# 会占用当日频控额度，让后续回归出现「明明没发过却已满额」的假红。
STATE = {
    "task_ids": [],
    "plan_ids": [],
    "message_baseline": 0,
    "log_baseline": 0,
    "rel_id": None,
    "rel_owned": False,
    "rel_status_before": None,
    "prefs": (),
    "done": False,
}

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


def effective_cap():
    """频控上限的权威来源是 sys_dict 运营参数（P2-3 起优先于 SmsProperties）。

    启动环境变量只在 sys_dict 没有启用行时才是默认值来源，所以断言不能绑死它。
    """
    raw = cleanup_util.mysql_value(
        "SELECT `dict_value` FROM `sys_dict` WHERE `dict_type`='OPS_PARAM' "
        "AND `dict_code`='sms.frequency.missed-dose-daily-cap' AND `status`=1 LIMIT 1;")
    try:
        return int(raw)
    except ValueError:
        return DEFAULT_CAP


def today_sms_count(user_id):
    return int(cleanup_util.mysql_value(
        "SELECT COUNT(*) FROM `sms_send_log` WHERE `scene`='missed-dose' "
        "AND `user_id`=%d AND `create_time`>=CURDATE();" % user_id) or 0)


def purge_orphan_rows():
    """清掉历史失败运行留下的孤儿回执（biz_id 已无对应任务）。

    服务端频控已不再计孤儿行（任务删除即作废 + 计数只认有效任务），
    但本脚本的 today_sms_count 按原始行数统计；不清掉的话
    「从 0 开始计数」的前提不成立（实测残留 3 条就让 101 直接满额）。
    """
    where = ("FROM `sms_send_log` s WHERE s.`scene`='missed-dose' "
             "AND s.`user_id` IN (%d,%d) AND s.`create_time`>=CURDATE() "
             "AND NOT EXISTS (SELECT 1 FROM `medication_task` t WHERE t.`id`=s.`biz_id`)"
             % (FAMILY_ID, FAMILY_ID_2))
    removed = int(cleanup_util.mysql_value("SELECT COUNT(*) " + where) or 0)
    if removed:
        cleanup_util.mysql_raw("DELETE s " + where + ";")
        print("[提示] 清掉 %d 条孤儿漏服回执（历史失败运行残留，占用当日额度）" % removed)
    return removed


def do_cleanup():
    """脚本自身数据的统一收尾；幂等，正常结束与异常退出都调用一次。

    先删任务再删回执：扫描器若在「删回执」与「删任务」之间写入新回执，
    先删任务能让这类写入落空，避免再次留下孤儿回执行。
    """
    if STATE["done"]:
        return
    STATE["done"] = True
    task_ids, plan_ids = STATE["task_ids"], STATE["plan_ids"]
    if task_ids:
        ids = ",".join(str(i) for i in task_ids)
        cleanup_util.mysql_raw("DELETE FROM `medication_task` WHERE `id` IN (%s);" % ids)
        cleanup_util.mysql_raw("DELETE FROM `sms_send_log` WHERE `biz_id` IN (%s);" % ids)
    if plan_ids:
        cleanup_util.mysql_raw(
            "DELETE FROM `medication_plan` WHERE `id` IN (%s);"
            % ",".join(str(i) for i in plan_ids))
    if STATE["message_baseline"] > 0:
        cleanup_util.delete_since("internal_message", STATE["message_baseline"], "本次漏服站内信")
    if STATE["log_baseline"] > 0:
        cleanup_util.delete_since("sys_login_log", STATE["log_baseline"], "sys_login_log")
    cleanup_util.flush_unread_cache()

    rel_id = STATE["rel_id"]
    if rel_id is not None:
        if STATE["rel_owned"]:
            cleanup_util.mysql_raw("DELETE FROM `family_elder_relation` WHERE `id`=%d;" % rel_id)
            print("[清理] 已删除临时家属绑定关系 %d" % rel_id)
        elif STATE["rel_status_before"] is not None:
            cleanup_util.mysql_raw(
                "UPDATE `family_elder_relation` SET `status`='%s' WHERE `id`=%d;"
                % (STATE["rel_status_before"], rel_id))
            print("[清理] 绑定关系 %d 服务端原本就存在，恢复为 %s"
                  % (rel_id, STATE["rel_status_before"]))

    for uid, before in STATE["prefs"]:
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


atexit.register(do_cleanup)


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
    STATE["log_baseline"] = cleanup_util.max_id("sys_login_log")
    token = med.login_ok(med.ACC_FAMILY)
    token2 = med.login_ok(med.ACC_FAMILY_OTHER)
    message_baseline = cleanup_util.max_id("internal_message")
    STATE["message_baseline"] = message_baseline

    pref_before_1 = pref_snapshot(FAMILY_ID)
    pref_before_2 = pref_snapshot(FAMILY_ID_2)
    STATE["prefs"] = ((FAMILY_ID, pref_before_1), (FAMILY_ID_2, pref_before_2))
    rel_id, rel_owned, rel_status_before = bind_second_family()
    STATE["rel_id"] = rel_id
    STATE["rel_owned"] = rel_owned
    STATE["rel_status_before"] = rel_status_before

    task_ids = []
    plan_ids = []
    STATE["task_ids"] = task_ids
    STATE["plan_ids"] = plan_ids

    # 先清孤儿回执：否则它们会在 C 段就占满 101 的当日额度（频控不分 biz_id）
    purge_orphan_rows()

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

    # 阶段 D：单用户日频控。上限取 sys_dict 运营参数（P2-3 起优先于环境变量），
    # 并按「当日剩余额度」断言，避免历史残留回执把起点直接顶满。
    # 先删掉本脚本此前所有回执行（B 阶段 102 已收到 1 条，不清掉会占用其额度）。
    cleanup_util.mysql_raw(
        "DELETE FROM `sms_send_log` WHERE `biz_id` IN (%s);"
        % ",".join(str(i) for i in task_ids))
    cap = effective_cap()
    base_1 = today_sms_count(FAMILY_ID)
    base_2 = today_sms_count(FAMILY_ID_2)
    allowed_1 = max(0, cap - base_1)
    allowed_2 = max(0, cap - base_2)
    print("[提示] 频控上限=%d；本次起点 101=%d 102=%d" % (cap, base_1, base_2))
    cap_tasks = []
    for _ in range(cap + 1):
        tid = cleanup_util.max_id("medication_task") + 1
        pid = cleanup_util.max_id("medication_plan") + 1
        make_overdue_task(pid, tid)
        task_ids.append(tid)
        plan_ids.append(pid)
        cap_tasks.append(tid)

    scanned = all(wait_scanned(tid) for tid in cap_tasks)
    if scanned:
        # 额度内最后一条任务用于等发送完成；之后的第 cap+1 条应被频控拦下
        wait_index = min(len(cap_tasks), max(allowed_1, allowed_2)) - 1
        if wait_index >= 0:
            wait_sent(cap_tasks[wait_index], 2)
        time.sleep(5)  # 给扫描器处理被拦下的那条任务的时间
        cnt_1 = sms_count(cap_tasks, FAMILY_ID)
        cnt_2 = sms_count(cap_tasks, FAMILY_ID_2)
        expect_1 = min(len(cap_tasks), allowed_1)
        expect_2 = min(len(cap_tasks), allowed_2)
        check("D1 频控内每位家属按剩余额度各收到一条（上限 %d，起点 101=%d 102=%d）"
              % (cap, base_1, base_2),
              cnt_1 == expect_1 and cnt_2 == expect_2,
              "101=%d(期望%d) 102=%d(期望%d)" % (cnt_1, expect_1, cnt_2, expect_2))
        # 两位家属都超限的那些任务应零回执
        empty_expected = max(0, len(cap_tasks) - max(allowed_1, allowed_2))
        empty_tasks = [t for t in cap_tasks if sms_rows(t) == ""]
        check("D2 同人同日超限的新任务被拦（%d 条任务两位家属都无回执）" % empty_expected,
              len(empty_tasks) == empty_expected,
              "空回执任务=%s" % (empty_tasks or "(无)"))

    # 阶段 E：PUT 后 GET 读回一致
    for tok, uid, expects in ((token, FAMILY_ID, True), (token2, FAMILY_ID_2, True)):
        status, body = med.call("GET", "/message/notification-preference", token=tok)
        check("E1 家属 %d PUT 后 GET 读回一致" % uid,
              (body.get("data") or {}).get("smsMissedEnabled") is expects
              and (body.get("data") or {}).get("quietHoursEnabled") is False,
              str(body.get("data")))

    # 收尾：统一走 do_cleanup（与 atexit 同一份实现，异常退出也会执行）
    do_cleanup()

    failed = [r for r in results if not r[1]]
    print("\n===== e2e_sms_mock 结果：%d/%d 通过 =====" % (len(results) - len(failed), len(results)))
    return 0 if not failed else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        do_cleanup()
        print("[FATAL] %s" % exc)
        sys.exit(2)
