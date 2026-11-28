# P0-8 · 补记三口径统一（新增 `LATE_TAKEN`）

> 批次：**B（用药与文档域）** · 级别：P0 · 来源：`reports/business-closure-final-assessment-2026-11-28.md` §P0-8
> 错误码：**不新增**

## Problem Statement

老人早上 8:00 的药没被确认。30 分钟后系统扫描，把它判为「漏服」。家属 9:30 打开 App 补记「已服用」。

然后同一个任务在三个地方给出三个答案：

| 位置 | 显示 |
|---|---|
| 用药任务列表 | 「已服」 |
| 老人端首页「今日已服药」 | 没变（补记不增加已服数） |
| 健康中心「完成率」 | 计入漏服 |

系统里没有任何「补记」字样解释这个差异。家属会问：我明明补了，为什么还算漏服？

## Solution

把「补记」当成第三种状态显式建模，三层口径统一到它：

- 桶判定新增 `LATE_TAKEN`；
- 今日用药拆成 `taken / lateTaken / missed / pending` 四栏；
- 页面标签显示「补记」，并在指标字典里写明「补记计入漏服率、不计入按时完成率」。

## User Stories

1. 作为家属，我想在补记后看到任务标着「补记」而不是「已服」，这样我知道这条曾经漏过。
2. 作为家属，我想老人端首页的「今日已服药」把补记也算进去，这样数字不会停在 0。
3. 作为家属，我想完成率把补记单独说出来，这样我知道有多少次是补救的。
4. 作为家属，我想在补记时系统提示「这会被计入漏服统计」，这样我不会以为补了就没事。
5. 作为老人，我想首页的字大、数字准，这样我不会因为看到「0/3」而以为自己一颗都没吃。
6. 作为陪诊员，我想代确认时也能区分「按时」与「补记」，这样我的服务记录准确。
7. 作为运营，我想漏服率与按时完成率是两个不同的数，这样我汇报时不会混用。
8. 作为运营，我想指标字典里写明补记的归属，这样口径不会随实现漂移。
9. 作为管理员，我想在健康总览里看到四项拆分，这样我能判断这位老人的真实依从性。
10. 作为测试者，我想看到「补记后桶判定为 LATE_TAKEN」的用例。
11. 作为测试者，我想看到「today 四项之和等于 total」的用例。
12. 作为测试者，我想看到「SQL 侧与 Java 侧桶判定一致」的用例。
13. 作为开发者，我想桶判定仍然只有一处（Java 一个 `of()`，SQL 一个 `CASE`），这样两边不会打架。
14. 作为开发者，我想新增的桶不影响既有 `taken + missed + pending = total` 的等式（改为四项）。
15. 作为开发者，我想这次改动不动 `medication_task.status` 的取值域，这样不用迁移。

## Implementation Decisions

### 核心：桶判定增加第三态

- `MedicationBucket` 枚举新增 `LATE_TAKEN("补记")`。
- `MedicationBucket.of(status, wasMissed)` 判定顺序改为：
  1. `status = 'MISSED'` → `MISSED`
  2. `wasMissed = true`（且 `status = 'TAKEN'`）→ `LATE_TAKEN`
  3. `status = 'PENDING'` → `PENDING`
  4. 其余 → `TAKEN`
  - **关键**：先判 `status = MISSED`，再判 `wasMissed`。当前实现是「`wasMissed` 或 `MISSED` 都归漏服」，把补记吞掉了。
- `MedicationBucketSql.CASE_BUCKET` 同步改，顺序与 Java 一致：
  ```
  CASE WHEN status = 'MISSED' THEN 'MISSED'
       WHEN was_missed = 1 THEN 'LATE_TAKEN'
       WHEN status = 'PENDING' THEN 'PENDING'
       ELSE 'TAKEN' END
  ```
- **不变式改为**：`total == taken + lateTaken + missed + pending`。

### 聚合字段

- 所有派生 `taken` 的 SQL（`ElderHealthReadMapper` 里两处）增加 `lateTaken` 列，`taken` 不再包含补记。
- `MedicationTodayVO` 新增 `lateTaken` 字段；`medication.taken` 类指标同步。
- **破坏性变更提醒**：`taken` 的含义从「含补记」变成「不含补记」。前端所有用 `taken` 的地方都要确认——**这会让「今日已服药 2/3」变成「1/3 + 1 补记」，必须同时改前端展示，否则老人看到的数字变小了反而更困惑。**

### 指标字典

- `docs/spec/metric-dictionary.md` 新增/修订：
  - 「漏服率」分母 = `missed + lateTaken`（补记算漏过）。
  - 「按时完成率」分母不含 `lateTaken`。
  - 明确写出「补记计入漏服统计，这是刻意口径」。

### 前端

- `views/elder/medication.vue`：`calMap` 的桶判定改为四态（当前是 `wasMissed || MISSED → missed`）；首页「今日已服药」拆两栏或加「含补记 N 次」。
- `views/family/medication.vue`：任务标签显示「补记」。
- 老人端首页大字版：**数字一定要能自洽地讲清楚**（「已服 2 次（其中补记 1 次）」而不是给人一个更小的数）。

## Testing Decisions

### 好测试

- 桶判定：纯函数，直接断言 `of()` 的四组输入输出（`MedicationBucketTest` 已有结构，本单扩展）。
- SQL 侧：断言 `MedicationBucketSql.CASE_BUCKET` 在代表性行上给出与 Java 一致的结果（`MedicationBucketParityTest` 已有，本单扩展）。
- 聚合：断言 `todayMedication` 返回的 VO 四栏之和等于 total。

### 测试模块

扩展 `MedicationBucketTest`、`MedicationBucketParityTest`、`ElderHealthServiceTest`

### 垂直切片

1. **红**：`补记的桶判定应为LATE_TAKEN`。
2. 实现枚举 + `of()`。
3. **红**：`一直未确认的漏服仍为MISSED`（保护现状）。
4. **红**：`按时服用的仍为TAKEN`。
5. **红**：`SQL桶判定与Java判定一致_四种输入`。
6. 实现 SQL 常量。
7. **红**：`今日用药四项之和等于total`。
8. 实现聚合与 VO。
9. **红**：`前端日历四态判定`（若前端有测试则加，否则人工走查记录在 commit body）。

### 先例

`MedicationBucketTest`（已有 17 个用例的分组结构：`Buckets` / `DirtyData`）；`MedicationBucketParityTest`（Java ↔ SQL 一致性）。

## Out of Scope

- **不改 `medication_task.status` 取值域**（仍是 `PENDING / TAKEN / MISSED` 三值）——补记靠 `was_missed` 标记表达，不需要第四个状态值，也就**不需要迁移脚本**。
- 不改「补记」的判定条件（仍是「曾被判 `MISSED` 后又被确认」）。
- 不改漏服扫描的阈值与升级策略（属 P2-1）。
- 不改用药计划（属 P1-4 / P1-5）。

## Further Notes

- **`taken` 语义变更的连带影响必须逐一确认**：`grep -r "taken" frontend/src` 与 `grep -r "getTaken\|\.taken" backend/src`。已知消费方：老人端首页、家属端用药页、健康总览、统计报表。**任何一个漏改都会变成「老人看到的数字变小」**。
- 这条改动是「三处各说一套」的根因修复，比在页面上加一句解释更彻底。若团队时间紧张，**退路是只改前端标签（把补记显示为「补记」）并统一指标字典口径**，但服务端的 `taken` 仍含补记——那会留下一个「SQL 与展示不一致」的隐患，不推荐。
