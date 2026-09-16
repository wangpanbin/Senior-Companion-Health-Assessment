# M6 设计评审 · 用药管理与漏服提醒

- **模块**：M6（旁路径 D）
- **评审时间**：2026-09-16（依落地代码反向补齐）
- **关联**：`docs/api/05-medication.md`、`docs/adr/0002-m6-redis-lock.md`、`PLAN_BACKEND.md §8 M6`
- **代码**：`controller/medication/MedicationController`、`service/impl/MedicationServiceImpl`、`task/MedicationScheduler`、`util/RedisLockUtil`、`vo/MedicineVO`

---

## 1 · 接口表

| # | 方法 | 路径 | 角色 | 入参 | 出参 |
|---|---|---|---|---|---|
| 1 | GET | `/api/medication/dict` | 登录用户 | `MedicineQuery` | `PageResult<MedicineVO>` |
| 2 | GET | `/api/medication/dict/{id}` | 登录用户 | path `id` | `MedicineVO` |
| 3 | GET | `/api/medication/plan` | 登录用户 | `MedicationPlanQuery` | `PageResult<MedicationPlanVO>` |
| 4 | POST | `/api/medication/plan` | FAMILY | `MedicationPlanCreateDTO` | `MedicationPlanIdVO` |
| 5 | PUT | `/api/medication/plan/{id}` | FAMILY | `MedicationPlanUpdateDTO` | `Void` |
| 6 | DELETE | `/api/medication/plan/{id}` | FAMILY | path `id` | `Void`（逻辑停用） |
| 7 | GET | `/api/medication/task/calendar` | 登录用户 | `MedicationCalendarQuery` | `MedicationCalendarVO` |
| 8 | GET | `/api/medication/task/today` | 登录用户 | query `elderId` | `List<MedicationTaskVO>` |
| 9 | POST | `/api/medication/task/{id}/confirm` | FAMILY / COMPANION | `MedicationTaskConfirmDTO` | `MedicationConfirmVO` |

**合规红线（接口 1/2）**：`MedicineVO` **必含 `disclaimer`**（`@Schema("必返，前端必须展示")` +
数据库为空时的兜底文案），响应**不含**建议剂量、适应症判断、替代药字段。

---

## 2 · 数据流

```
【定时任务】每日 07:00  generateDailyTasks
  MedicationScheduler
    → RedisLockUtil.tryLock("medication:daily", 10min)   ← 单实例执行
    → MedicationServiceImpl.generateDailyTasks(today)
         ├─ 查所有 ACTIVE 用药计划（end_date 有效）
         ├─ 按 time_points 展开为 N 条 medication_task（status=PENDING）
         └─ 唯一索引 uk_plan_time(plan_id, plan_time) 兜底防重复

【定时任务】每 30 分钟  scanMissedTasks
  MedicationScheduler
    → RedisLockUtil.tryLock("medication:missed", 5min)   ← TTL < 间隔，下轮可抢
    → MedicationServiceImpl.scanMissedTasks()
         ├─ 查 status=PENDING 且 plan_time < now - threshold 的任务（batch 上限 200）
         ├─ UPDATE status=MISSED
         └─ MessageService.sendBatch(家属 ids, MEDICATION_REMIND)  ← 经 M8 推送

【实时】新增/停用计划
  POST /plan → 立即为「今天」补生成任务（不等 07:00）
  DELETE /plan/{id} → 未到点的 PENDING 任务清理（已过点的不动，留给漏服扫描判）

表 → Mapper → Service → Controller：
  medicine_dict   ─┐
  medication_plan ─┼→ *Mapper → MedicationServiceImpl → MedicationController
  medication_task ─┘                ↑
                          MedicationScheduler（Spring Task + Redis 锁）
```

**Redis 锁的不对称性**（ADR-0002）：这两类任务「重复」的代价远低于「丢失」——
生成任务漏跑当天就没药可吃，漏服扫描漏跑只是晚 30 分钟通知。故锁策略是**宁可不执行，也不并发执行**。

---

## 3 · 状态机（服药任务）

```
PENDING（待服）──confirm──→ TAKEN（已服）
      │
      └──扫描超时──→ MISSED（漏服）──补记 confirm──→ TAKEN（was_missed 保持 1）
```

| 非法情况 | 错误码 |
|---|---|
| 药品不存在 | `5001 MEDICINE_NOT_FOUND` |
| 计划时间区间不合法 | `5002 MEDICATION_PLAN_INVALID` |
| 重复确认已服任务 | `5003 MEDICATION_TASK_CONFIRMED` |

**实现要点**
- `was_missed` 一旦置 1，**补记后仍保持 1** —— 否则漏服率统计会被「补记」洗白（`e2e_medication.py` C19 锁定该语义）。
- `notify_sent` 标记防重复推送（同一漏服只通知一次）。
- 计划状态 `ACTIVE / DISABLED`；停用是**逻辑状态变更**，行仍保留（历史记录不可物理删）。

---

## 4 · 验收清单

| # | 验收项 | 状态 |
|---|---|---|
| 1 | medicine-dict / plan / task / confirm 四类接口 | ✅ |
| 2 | 每日 07:00 生成任务（cron 可配） | ✅ `${nianglin.medication.daily-generate-cron:0 0 7 * * ?}` |
| 3 | 每 30 分钟漏服扫描 | ✅ `fixedRate 1800000ms`，batch 上限 200 |
| 4 | 多实例只有 1 个执行（Redis 锁） | ✅ `RedisLockUtil`（单实例验证待补，见 ADR-0002） |
| 5 | 漏服经 M8 推送给家属 | ✅ `MessageService.sendBatch(MEDICATION_REMIND)` |
| 6 | 并发启动 2 实例仅 1 个执行 | ⚠️ 未自动化，建议纳入 M12 |
| 7 | 合规：药品字典必返 disclaimer | ✅ `MedicineVO` 兜底文案 + Controller 注解 |
| 8 | 定时任务开关（压测时关闭） | ✅ `@ConditionalOnProperty(scheduler-enabled)` |

---

## 5 · 遗留与风险

- **重定向单测缺失**：`MedicationServiceTest` 未写（审计报告 P0 待办）。
- **越权矩阵缺失**：`MedicationAccessMatrixTest` 未写。
- 多实例锁验证未自动化。
