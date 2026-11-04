# Spec · 孤儿短信回执占额度修复（A）+ 运营参数优先级文档（B）

> 分支：`feature/p2-health`（不 push、不改写历史）
> 状态：待用户确认后执行

## 1 · 问题与根因

已在开发库确认：`sms_send_log` 存在 3 条 `PENDING` 回执（`biz_id` 20527–20529，
`user_id=101`），对应的 `medication_task` 已不存在；它们仍被
`SmsReminderService#isDailyCapReached` 计入当日额度，导致该家属当天静默少收提醒。

代码根因两处：

1. `MedicationServiceImpl#disablePlan` 只对「未到期的 PENDING 任务」做逻辑删除，
   没有同步作废这些任务下尚未发送的短信回执 → 产生孤儿回执。
2. `isDailyCapReached` 只按 `user_id + scene + create_time` 计数，
   不校验回执指向的任务是否仍有效 → 历史孤儿行继续占额度。

## 2 · 决策

### 2.1 新增回执状态 `VOIDED`（作废）

- 语义：**任务已删除 / 已停用，回执不再发送，也不占频控额度**；保留行用于审计。
- 存储：`status VARCHAR(16)` 足够，**不需要改表结构**；不改已执行的 `V8`，
  同步更新 `SmsSendLog` 实体注释与 `docs/api/07-message.md`。
- `last_error` 写 `voided:<原因>`，原因取值：`plan-disabled`、`task-not-found`。

### 2.2 删除路径同步作废（第一道保险）

`MedicationServiceImpl#disablePlan`：

1. 删除前先查出「本计划、PENDING、`plan_time > now`」的任务 ID 列表；
2. 执行原逻辑删除；
3. 调用 `SmsReminderService#voidUnsentForTasks(taskIds, "plan-disabled")`，
   把这些任务下 `PENDING / FAILED / DEAD` 的回执置 `VOIDED`，
   `SENT` 行保持不动（已经真实发送，保留审计）。

### 2.3 频控只计有效任务（第二道保险）

`SmsReminderService#isDailyCapReached` 的查询增加两个条件：

- `.ne(SmsSendLog::getStatus, VOIDED)`：作废行不计入；
- `.inSql(SmsSendLog::getBizId, "SELECT id FROM medication_task WHERE deleted = 0")`：
  只计仍存在（未逻辑删除）的任务回执。

即历史孤儿行即使没被一次性脚本处理，也不会再阻塞发送。
`scanDueRetries` 遇到 `task == null` 时改为 `VOIDED` + `voided:task-not-found`，
不再写成 `FAILED`（此前 FAILED 仍占额度）。

### 2.4 历史孤儿数据一次性清理

新增 `backend/sql/tools/cleanup_orphan_sms_logs.sql`（**不参与 Flyway**）：

- 先给 dry-run `SELECT`（按 `scene='missed-dose'`、任务不存在、状态非 `SENT`）；
- 再 `UPDATE ... SET status='VOIDED', last_error='voided:task-not-found'`；
- 只处理 `PENDING / FAILED / DEAD`，不动 `SENT`。

文档说明写入 `backend/sql/tools/README.md` 与 `docs/api/07-message.md`。

### 2.5 B · 运营参数优先级文档

明确一条：`sys_dict`（`dict_type='OPS_PARAM'`、`status=1`）**优先于**
`application.yml` / 启动参数 / 环境变量；字典行有启用值时，同名环境变量不再生效，
只有把该字典行 `status=0` 停用后环境变量才兜底。

落点：

- `docs/api/08-admin.md` §28.4「生效方式与缓存」；
- `docs/spec/p2-3-operation-params/spec.md` §3.3 后；
- 根 `README.md` 后端/部署段落（踩坑提示）；
- `backend/sql/tools/README.md` 坑 #19 已有的表述保持，补一句指向上述文档。

## 3 · 文件与函数级改动

| 文件 | 改动 |
|---|---|
| `backend/src/main/java/org/company/nianglin/service/sms/SmsReminderService.java` | 新增 `STATUS_VOIDED`；`isDailyCapReached` 加 `ne(status, VOIDED)` + `inSql(... valid task ...)`；新增 `public int voidUnsentForTasks(Collection<Long> taskIds, String reason)`；新增 `markVoided`；`scanDueRetries` 的 task-null 分支改走 `markVoided`；类注释频控口径同步 |
| `backend/src/main/java/org/company/nianglin/service/impl/MedicationServiceImpl.java` | 新增构造依赖 `SmsReminderService`；`disablePlan` 先收集未到期任务 ID，删除后调用 `voidUnsentForTasks(ids, "plan-disabled")`，日志补 `voided` 数 |
| `backend/src/main/java/org/company/nianglin/entity/SmsSendLog.java` | 状态注释增加 `VOIDED` |
| `backend/src/test/java/org/company/nianglin/service/sms/SmsReminderServiceTest.java` | 新增：①频控查询只计有效任务且排除 VOIDED（抓 `LambdaQueryWrapper` 断言 SQL 片段）；②`voidUnsentForTasks` 只作废未发送状态并写 `voided:*`；③重试扫描任务不存在 → `VOIDED` 且不再发送 |
| `backend/src/test/java/org/company/nianglin/service/MedicationServiceTest.java` | 构造器补 `smsReminderService`；新增「停用计划 → 删除未到期任务后调用作废」单测 |
| `backend/sql/tools/cleanup_orphan_sms_logs.sql` | 新增一次性清理脚本（dry-run + UPDATE） |
| `backend/sql/tools/README.md` | 文件清单加脚本；清理说明；坑位补一句 |
| `docs/api/07-message.md` | 回执状态增加 `VOIDED`；频控口径改为「只计有效任务 + 排除作废」；孤儿清理说明 |
| `docs/api/08-admin.md` | §28.4 增加「运营参数优先于环境变量」 |
| `docs/spec/p2-3-operation-params/spec.md` | 同步优先级说明 |
| `README.md` | 后端/部署段落增加运营参数优先级踩坑 |

## 4 · 验证

1. `mvn -B verify`（后端全量单测，预期在 1516 基础上新增 4 个左右）。
2. 前端无代码改动，仍跑 `pnpm run lint:check` + `pnpm test` 作为收尾确认。
3. e2e 回归（单脚本 240s 上限）：
   - 启动后端（加速漏服扫描环境变量）→ `python e2e_medication.py` + `--scan-phase`；
   - `python e2e_sms_mock.py`（预期 13/13；脚本内 `purge_orphan_rows` 保留为兜底）；
   - `python e2e_statistics.py`（确认统计无回归）。
   - 若断言需要调整，以「频控只计有效任务」为准同步修改并在提交说明中写明依据。
4. 跑前 / 跑后基线对比，目标 28 张表 diff 为 0。
5. 关停服务、恢复运营参数默认值、确认工作区干净。

## 5 · 提交与推送

- A（代码 + 测试 + 清理脚本 + 回执文档）单独提交：
  `fix(sms): 用药任务删除时作废未发送回执，频控只计有效任务`
- B（优先级文档）单独提交：
  `docs(admin): 说明运营参数优先于 application.yml 与环境变量`
- 收尾后按用户最新指令执行推送：
  1. `git remote -v` 核对远端名/URL；
  2. `git fetch` 后核对默认分支与远端提交；
  3. 判断 `feature/p2-health` 与默认分支关系：能 fast-forward 则快进默认分支并推；空仓则直推并设 upstream；**分叉/无关历史立即停下回报**；
  4. 同时推 `feature/p2-health`；`v4-phase1` / `p2-settlement-loop` 视其与默认分支关系决定，并在结果中说明依据；
  5. 推前跑 `python tools/check_secrets.py`、确认 `.gitignore` 覆盖本地配置；
  6. 认证失败立即回报，不重试、不绕过。
- **禁止 force push、禁止改写历史。**

## 6 · 影响面与风险

- `SmsReminderService` 构造签名不变（只新增方法），`MedicationServiceImpl` 构造新增一个参数，
  仅 `MedicationServiceTest` 需要同步（已列入）。
- 新 SQL 条件使用 `inSql` 子查询，`medication_task.deleted = 0` 必须保留，
  否则逻辑删除的任务会被误判为有效。
- 不改 `V8` 已执行迁移；`VOIDED` 仅是新状态值，无 DDL 结构变更。
- e2e_sms_mock 的 `purge_orphan_rows()` 保留：它是测试隔离手段，不替代生产代码修复。
