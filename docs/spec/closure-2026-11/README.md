# 业务闭环收口 · 2026-11 · 规格总览

> 来源：[`reports/business-closure-final-assessment-2026-11-28.md`](../../../reports/business-closure-final-assessment-2026-11-28.md)（25 条缺口，两轮独立评审合并结论）
> 基线：`main @ 576efdf`（2026-11-27）
> 执行方式：**测试驱动开发**（红 → 绿 → 下一片），两个 agent 在**互斥文件集**的两个 worktree 中并行
> 状态：**24 张工单**（原始 25 条缺口，`DOC-4` 的两处注释/文档订正并入 `DOC-3` 一并做）

---

## 一、为什么分成两批

两个 agent 并行工作时，唯一能保证「不互相踩」的办法是**文件集互斥**，而不是「任务谁先谁后」。

| 批次 | Agent | 职责域 | 独占文件集（**另一批不得修改**） |
|---|---|---|---|
| **A · 订单与结算域** | MiniMax Code | 订单状态旁路、结算、评价、仲裁、封禁 | `service/impl/OrderServiceImpl.java`（除 `hall` / `accept` / `approvedCompanionUserIds` 三个方法）· `service/impl/AdminServiceImpl.java` · `service/impl/SettlementServiceImpl.java` · `service/support/OrderTransitionServiceImpl.java` · `service/impl/ReviewServiceImpl.java` · `service/impl/ComplaintServiceImpl.java` · `task/` 下**新增** `OrderTimeoutScheduler` / `ComplaintTimeoutScheduler` |
| **B · 用药与文档域** | OpenCode | 用药三口径、计划-任务同源、健康时间线、全部文档 | `service/impl/MedicationServiceImpl.java` · `constant/MedicationBucket*.java` · `constant/WorkStatus.java` · `mapper/ElderHealthReadMapper.java` · `service/impl/ElderHealthServiceImpl.java` · `service/impl/CompanionServiceImpl.java` · `service/impl/MatchingServiceImpl.java` · `OrderServiceImpl.java` 的**仅** `hall` / `accept` / `approvedCompanionUserIds` 三个方法 · `docs/**` · `frontend/**` |

### 唯一共享文件 `OrderServiceImpl.java` 的切法

它是唯一被两批同时需要的文件。按**方法级**切，且先后顺序固定：

1. **B 批先做 P0-2**（改 `hall` / `accept` / `approvedCompanionUserIds` 三处），提交并推送其分支。
2. **A 批再基于 B 批的分支做 rebase**，才动其余方法（`complete` / `forceTerminal` / `cancel`）。
3. A 批**不得**修改 `hall` / `accept` / `approvedCompanionUserIds`；B 批**不得**修改 `complete` / `forceTerminal` / `cancel`。

> 若两个 agent 都无视这条约束，合并时我按「方法归属」人工裁定，归属不清的改动直接回退。

---

## 二、测试 seam（必须遵守）

用户已确认：**沿用仓库现有 Service 层 Mockito 单测**（与 `AdminReassignTest` / `CompanionWorkStatusTest` / `MedicationDraftFromOrderTest` 同构）。不写真库集成测试、不改前端测试。

### 2.1 允许使用的 seam（按优先级）

| # | seam | 形态 | 适用条目 | 先例 |
|---|---|---|---|---|
| **S1** | **Service 公开方法** | `@ExtendWith(MockitoExtension.class)` + `@Mock` 全部依赖 + 断言「抛哪个 `ResultCode`」/「返回 VO 的哪个字段」/「Mapper 收到什么条件的 `LambdaUpdateWrapper`」 | 绝大多数 | `AdminReassignTest`、`CompanionWorkStatusTest`、`MedicationDraftFromOrderTest` |
| **S2** | **纯函数 / 枚举静态方法** | 直接调用，断言返回值 | P0-8（`MedicationBucket.of`）、P1-5（唯一键说明）、P0-2（`WorkStatus.isAccepting`） | `MedicationBucketTest`、`OrderStatusTest`、`MedicationBucketParityTest` |
| **S3** | **SQL 常量字符串** | 断言 `MedicationBucketSql.CASE_BUCKET` 的**行为**（喂代表性行 → 得期望桶），**不要**断言字符串字面量 | P0-8 SQL 侧一致性 | `MedicationBucketParityTest` |
| **S4** | **文档一致性门禁** | `python tools/ai_flow/gate_check.py`（`error-codes` / `page-codes` / `md`）+ 新增一条断言「同一事实在三份文档里说同一句话」的小脚本 | 全部 DOC 条目 | `tools/check_metric_dict_parity.py` |

### 2.2 禁止的 seam

- ❌ **不连真库**（`MySQL` 只作生产验证，不进单测）。涉及 `@TableLogic` 与唯一键交互的 P1-5，用「断言 Service 在什么条件下会走哪条分支」来证明，不拉真库。
- ❌ **不断言私有方法**（`ReflectionTestUtils` 调私有方法视为违规）。
- ❌ **不断言 SQL 字符串字面量**（改个空格就红，等于把实现钉死在测试里）。
- ❌ **不写前端测试**（本次不动前端行为，只改按钮 `:disabled` 绑定 —— 由人工走查确认）。
- ❌ **不做横向切片**（不允许「先把 25 条测试全写完再写实现」）。每条工单内部是**垂直切片**：一个测试 → 一个实现 → 下一个测试。

### 2.3 每个 seam 抓什么 / 漏什么

| seam | 抓得住 | 抓不住 |
|---|---|---|
| S1 Service | 业务规则分支、错误码、事务内多写的顺序 | 真实 SQL 行为、并发、`@TableLogic` 隐式条件 |
| S2 纯函数 | 判定规则本身 | 规则有没有被调用 |
| S3 SQL 常量 | 判定规则在 SQL 侧是否与 Java 侧一致 | 真实执行计划、索引命中 |
| S4 文档门禁 | 文档互斥/漂移 | 文档说得对不对（人读） |

> **P1-5 的已知局限**：唯一键 + `@TableLogic` 的交互只能靠「读迁移脚本 + 人工确认」证明，单测覆盖到「重新启用时会重建任务、且不吞 `DuplicateKeyException`」为止。这一条我会在最终评审里单独复核。

---

## 三、工单索引

### A 批 · 订单与结算域（12 条，MiniMax Code）

| 工单 | 标题 | 级别 | 预估 |
|---|---|---|---|
| [P0-1](./P0-1-reassign-vs-grab.md) | 改派语义与抢单语义冲突 | P0 | 1d |
| [P0-3](./P0-3-complete-requires-checkin.md) | 完成服务须有打卡留痕 | P0 | 0.5d |
| [P0-4](./P0-4-disable-companion-orders.md) | 封禁陪诊员须处理在手订单 | P0 | 1d |
| [P0-5](./P0-5-settled-cannot-force-cancel.md) | 已结算单不可强制取消 | P0 | 0.5d |
| [P0-6](./P0-6-force-complete-fee.md) | 强制完成须走费用汇总 | P0 | 0.5d |
| [P0-7](./P0-7-no-companion-no-review.md) | 无陪诊员的单不可强制完成 | P0 | 0.5d |
| [P1-1](./P1-1-order-timeout.md) | 订单超时闭环 | P1 | 1.5d |
| [P1-2](./P1-2-review-invalid-exit.md) | 评价裁定无效后的正向出口 | P1 | 1d |
| [P1-3](./P1-3-arbitrate-flags.md) | 仲裁执法开关落结构化字段 | P1 | 1d |
| [P1-6](./P1-6-complaint-timeout-and-appeal.md) | 投诉超时升级 + 申诉占用按 type 隔离 | P1 | 1.5d |
| [P2-4](./P2-4-idempotency.md) | 下单与聊天幂等键 | P2 | 1d |
| [P2-5](./P2-5-cancel-downstream.md) | 取消不清理下游（明确为设计并留证） | P2 | 0.5d |

### B 批 · 用药与文档域（12 条，OpenCode）

| 工单 | 标题 | 级别 | 预估 |
|---|---|---|---|
| [P0-2](./P0-2-work-status-gate.md) | 接单开关：三处失效 + 一处假承诺 | P0 | 1d |
| [P0-8](./P0-8-late-taken-bucket.md) | 补记三口径统一（新增 `LATE_TAKEN`） | P0 | 1.5d |
| [P0-9](./P0-9-summary-missing-draft.md) | 服务小结缺失时降级而非拒绝 | P0 | 0.5d |
| [P1-4](./P1-4-plan-task-sync.md) | 用药计划创建/修改与已生成任务同源 | P1 | 1.5d |
| [P1-5](./P1-5-reenable-plan.md) | 停药可重新启用（先改唯一键） | P1 | 1.5d |
| [P2-1](./P2-1-escalation-floor.md) | 漏服升级加时间下界 | P2 | 0.5d |
| [P2-2](./P2-2-vital-dedup-key.md) | 体征去重键含录入人 | P2 | 0.5d |
| [P2-3](./P2-3-income-key.md) | 收入口径 key 冗余收敛 | P2 | 0.5d |
| [DOC-1](./DOC-1-sse-last-event-id.md) | SSE 补齐口径与 PRD 对齐 | DOC | 0.5d |
| [DOC-2](./DOC-2-elder-confirm-matrix.md) | 权限矩阵 ELDER 确认服药一格 | DOC | 0.5d |
| [DOC-3](./DOC-3-order-known-limits.md) | `03-order.md` §五 整节重写（含 DOC-4） | DOC | 0.5d |
| [DOC-5](./DOC-5-metric-naming.md) | 完成率/区间字段命名收敛 | DOC | 0.5d |

---

## 四、每张工单的统一要求

1. **先开分支**：`feature/closure-2026-11-a` / `feature/closure-2026-11-b`（从 `main @ 576efdf`）。**禁止在 `main` 上提交。**
2. **接口先行**：动 HTTP 契约的先把 `docs/api/*.md` 改完。动错误码的**必须**同步 `ResultCode.java` + `docs/api/README.md` 错误码总表（`gate_check.py --only error-codes` 会拦）。
3. **红 → 绿**：先写一个失败的测试，跑出红，再写刚够通过它的实现。**不做横向切片。**
4. **一次一提交**：每个工单一个 commit，message 符合 `feat|fix|test|docs(模块): 描述`。**不 push**，不合并，不留脏工作区。
5. **跑绿再交**：`mvn -o -B -DskipTests compile` + 本工单相关测试类 + 全量 `mvn -o -B test`（只跑与你改的模块相关的包即可，但要跑过）。动文档的跑 `python tools/ai_flow/gate_check.py`。
6. **不改别人独占的文件**（见 §一）。需要跨批改动时，**停下来在 ISSUE 里留言**，不要自己动手。
7. **不做范围外的顺手重构**。看到别的问题记录在 commit body 里，不实施。

---

## 五、错误码预算

新增错误码**必须**先在此登记，再改 `ResultCode.java`（否则 `error-codes` 门禁会红）：

| 建议码 | 名称 | 用途 | 工单 |
|---|---|---|---|
| `3011` | `ORDER_SETTLED_CANNOT_CANCEL` | 已结算订单不可强制取消（P0-5） | P0-5 |
| `3012` | `ORDER_CHECKIN_REQUIRED` | 完成服务前须有打卡（P0-3） | P0-3 |
| `2012` | `COMPANION_HAS_ACTIVE_ORDER` | 封禁陪诊员时其名下有在手订单，须先处理（P0-4） | P0-4 |

> 其余工单**不得新增错误码**，复用既有码并在正文说明复用哪个。

---

## 六、跨批依赖

```
B. P0-2 (hall/accept/approvedCompanionUserIds)
        │  先合，A 再 rebase
        ▼
A. P0-1 (reassign 语义)  ──►  A. P0-4 (封禁回 PENDING 需清 companion_id，与 P0-1 同一套语义)
A. P0-5 (settled)  ──►  A. P0-6 (fee 汇总)  ──►  P2-3 (口径，B 批)
B. P1-5 (唯一键 → 重新启用)  必须同批，不可拆
A. P1-2 (评价无效出口)  ◄──  A. P1-6 (申诉占用按 type 隔离，是其前置)
```

- **P0-4 依赖 P0-1**：封禁后「自动回 PENDING + 清 `companion_id`」必须与 P0-1 的改派语义一致，否则会造出第二种「有 `companion_id` 的 PENDING 单」。
- **P1-2 依赖 P1-6**：申诉占用不按 `type` 隔离时，评价无效裁定的入口本身就可能被 409 挡住。
- **P1-5 内部不可拆**：先改唯一键，再补启用端点。反了就是 100% 静默失败。

---

## 七、完成判据（两个批次共同）

- [ ] 每张工单都有**先红后绿**的证据（commit body 里贴测试名与红/绿两次运行的结论）
- [ ] `mvn -o -B test` 在本批改动的模块上全绿
- [ ] 动文档的：`python tools/ai_flow/gate_check.py` 全绿
- [ ] `git status` 干净，每单一个 commit，未 push
- [ ] 未触碰另一批的独占文件（我会用 `git diff --stat` 对照 §一 的清单核验）
