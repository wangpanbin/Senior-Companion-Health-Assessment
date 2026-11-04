# Tasks · 孤儿短信回执占额度修复（A）+ 运营参数优先级文档（B）

> 执行顺序固定；A 与 B 分开提交；全部验证通过后再推送。

## A · 代码修复

- [x] A1 `SmsReminderService`
  - [x] 新增 `STATUS_VOIDED = "VOIDED"`，更新类注释频控口径
  - [x] `isDailyCapReached`：加 `.ne(status, VOIDED)` 与 `.inSql(bizId, "SELECT id FROM medication_task WHERE deleted = 0")`
  - [x] 新增 `public int voidUnsentForTasks(Collection<Long> taskIds, String reason)`：
        `scene='missed-dose'`、`status IN (PENDING, FAILED, DEAD)` → `VOIDED` + `voided:<reason>` + `next_retry_at=null`
  - [x] `scanDueRetries` 的 `task == null` 分支改 `markVoided(row, "task-not-found")`
  - [x] 新增 `markVoided`（不累加 attempt，写 `voided:*`）
- [x] A2 `MedicationServiceImpl#disablePlan`
  - [x] 构造注入 `SmsReminderService`
  - [x] 删除前收集未到期 PENDING 任务 ID；删除后 `voidUnsentForTasks(ids, "plan-disabled")`
  - [x] 日志输出 `removed` 与 `voided` 数
- [x] A3 `SmsSendLog` 实体注释增加 `VOIDED`
- [x] A4 单测 `SmsReminderServiceTest`
  - [x] 频控 SQL 只计有效任务 + 排除 VOIDED（抓 wrapper 断言片段）
  - [x] `voidUnsentForTasks` 状态/原因正确，SENT 不在作废集合
  - [x] 重试扫描任务不存在 → `VOIDED`，不调用发送
- [x] A5 单测 `MedicationServiceTest`
  - [x] 构造器补 `smsReminderService`（同时修 `MedicationDraftFromOrderTest`）
  - [x] 停用计划删除未到期任务后调用 `voidUnsentForTasks`
- [x] A6 历史数据清理
  - [x] 新增 `backend/sql/tools/cleanup_orphan_sms_logs.sql`（dry-run + UPDATE）
  - [x] `backend/sql/tools/README.md` 文件清单与说明
- [x] A7 回执契约文档 `docs/api/07-message.md`（VOIDED、频控口径、清理脚本）
- [x] A8 提交 A：`fix(sms): 用药任务删除时作废未发送回执，频控只计有效任务`

## B · 文档

- [x] B1 `docs/api/08-admin.md` §28.4 增加「运营参数优先于 application.yml / 环境变量」
- [x] B2 `docs/spec/p2-3-operation-params/spec.md` §3.3 同步优先级说明
- [x] B3 根 `README.md` 后端/部署段落增加踩坑提示
- [x] B4 `backend/sql/tools/README.md` 坑 #19 补指向上述文档
- [x] B5 提交 B：`docs(admin): 说明运营参数优先于 application.yml 与环境变量`

## C · 验证

- [x] C1 `mvn -B verify`（BUILD SUCCESS，1520 tests / 0 failure / 0 error / 0 skipped）
- [x] C2 后端启动（`NIANGLIN_MEDICATION_MISSED_SCAN_INITIAL_DELAY_MS=5000` / `INTERVAL=10000`）
- [x] C3 `python e2e_medication.py` + `--scan-phase`（65/65 + 6/6，240s 上限内）
- [x] C4 `python e2e_sms_mock.py`（13/13，240s 上限内）
- [x] C5 `python e2e_statistics.py`（78/78；`--limit-phase` 69/69）
- [x] C6 跑后基线对比（28 张表 diff 0；`sms_send_log` 0/0）
- [x] C7 `pnpm run lint:check`（0 warning）+ `pnpm test`（23 files / 251 tests）
- [x] C8 关停 8080/5141/8081，恢复运营参数默认值（`export.max-rows=10000 status=1`），工作区干净

## D · 推送（需按用户指令执行）

- [ ] D1 `git remote -v` 核对远端名/URL
- [ ] D2 `git fetch` + 核对默认分支与远端提交
- [ ] D3 判断 ff / 空仓 / 分叉；分叉或无关历史 → 停下回报
- [ ] D4 `python tools/check_secrets.py`；确认 `.gitignore` 覆盖本地配置
- [ ] D5 推默认分支（ff 或空仓直推）与 `feature/p2-health`；`v4-phase1` / `p2-settlement-loop` 按关系判断并说明
- [ ] D6 认证失败立即回报，不重试、不绕过
- [ ] D7 回报：远端核对结论、策略、分支清单与提交范围、认证结果、工作区状态、推送前后验证数字
