# 0014 · 用药三桶口径的唯一真源

| | |
|---|---|
| 状态 | 已接受 (Accepted) |
| 日期 | 2026-10-20 |
| 决策者 | 项目负责人 |
| 关联模块 | M6 用药管理 / M10 数据统计 / V3.2 老人健康协同中心 |
| 关联文档 | `docs/api/05-medication.md` · `docs/api/12-elder-health.md` · `docs/spec/metric-dictionary.md` |
| 相关 ADR | 0003（M10 统计缓存）· 0009（费用明细模型）· 0010（订单状态流转收口） |

---

## 背景

V3.2 设计时发现：仓库里「漏服」这件事有**两套判定**，而且**两处注释都宣称「与服药日历口径一致」——那两句话是错的**。

| 位置 | 漏服判定 | 三桶是否闭合 |
|---|---|---|
| M6 `MedicationCalendarVO.of` | `was_missed = 1` **或** `status = 'MISSED'` | ✅ `taken + missed + pending == total` |
| M10 `StatisticsMapper.selectDailyMedicationCounts` | 只有 `was_missed = 1` | ❌ |

### 为什么会漂移

漏服扫描任务（`MedicationServiceImpl#scanMissedTasks`）**只写 `status = 'MISSED'`，从不写 `was_missed`**。
`was_missed = 1` 只在「漏服之后补记」的路径上被写（补记后 `status` 已变成 `TAKEN`，只有 `was_missed` 还留着痕迹）。

于是 M10 那套判定：

- 真实漏服（`status='MISSED'` 且 `was_missed=0`）——**占绝大多数**——一条都不进统计；
- 补记过的任务（`status='TAKEN'` 且 `was_missed=1`）——**taken 与 missed 双重计入**。

### 实测（2026-10-20，本机种子库）

```
status   was_missed   条数
MISSED   0             179      ← 既不 taken 也不 missed
MISSED   1              55
TAKEN    0             342
```

M10 旧口径下：`taken = 342`、`missed = 55`、`total = 576` → **342 + 55 = 397 ≠ 576**。
179 条真实漏服**整批消失**。

而 M6 服药日历因为判定正确，一直是对的 —— 同一个指标，两个页面给出不同的数。

### 为什么「注释写着一致」不管用

`StatisticsMapper` 的 javadoc 与 `StatisticsController` 的 `@Operation` 都说「与服药日历口径一致」。
**注释不会在改动时报警**。它是给人读的，而读它的人（未来的我）会默认它成立。

---

## 决策

### 1. 判定规则只写一份

新增 `constant/MedicationBucket`（枚举）与 `constant/MedicationBucketSql`（SQL 表达式常量）：

```
bucket = MISSED   当 was_missed = 1 或 status = 'MISSED'
bucket = PENDING  否则当 status = 'PENDING'
bucket = TAKEN    其余
```

- **Java 侧**：`MedicationBucket.of(status, wasMissed)`
- **SQL 侧**：`MedicationBucketSql.CASE_BUCKET`（编译期常量，可直接注入 `@Select` 注解）

### 2. 一条 SQL 里只出现一次判定

`selectDailyMedicationCounts` 改成「内层子查询算 `bucket`，外层从同一个 `bucket` 派生三个计数」：

```sql
SELECT d, COUNT(*) AS total,
       SUM(CASE WHEN bucket = 'TAKEN' THEN 1 ELSE 0 END) AS taken,
       SUM(CASE WHEN bucket = 'MISSED' THEN 1 ELSE 0 END) AS missed,
       SUM(CASE WHEN bucket = 'PENDING' THEN 1 ELSE 0 END) AS pending
FROM (
  SELECT DATE_FORMAT(plan_time, '%Y-%m-%d') AS d, <CASE_BUCKET> AS bucket
  FROM medication_task WHERE ...
) t GROUP BY d
```

判定文本全 SQL **只出现一次**，三个桶不可能彼此打架。

### 3. 用测试锁住，而不是用注释

| 测试 | 层 | 锁住什么 |
|---|---|---|
| `MedicationBucketTest` | 纯函数 | 每一种状态组合的落桶；脏值兜底 |
| `MedicationBucketParityTest` | 结构 | 反射读出**真正下发的 SQL 文本**：共享表达式恰好出现一次；三个计数都从 `bucket` 派生；旧写法已清除 |
| `StatisticsMapperMedicationFixTest` | 真库 | SQL 聚合 == Java 逐条分桶；`三桶之和 == 总数` |

### 4. 口径变更的规矩

改口径 = 改公式 + 升 `caliberVersion` + 在指标字典的变更记录留一行，**不改写历史数据**。

---

## 被否决的方案

| 方案 | 否决理由 |
|---|---|
| **只改注释，不动代码** | 注释不报警。M10 的统计仍然是错的，只是错得「有据可查」 |
| **以 M10 为准，改服药日历** | M10 那套本身有漏计（179 条）；而服药日历是 M6 已交付的功能，改它影响面更大。**等于把 bug 固化成标准** |
| **V3.2 另建第三套口径** | 矛盾从两处变成三处，直接违反 roadmap 决策 6.2「禁止同一指标在不同角色端出现不同口径」 |
| **在业务代码里手写判定分支** | 就是现在 M6 VO 的做法 —— 分支散在每一处，每多一处就多一次漂移的机会 |

---

## 影响

| 项 | 影响 |
|---|---|
| M6 服药日历 | **行为不变**（它本来就是对的），只是判定改为调用 `MedicationBucket` |
| M10 漏服统计 | **数值变化**：漏服数上升、已服数下降，总数不变。这是修正，不是回归 |
| V3.2 健康中心 | 用药三桶直接复用，天然与 M6 / M10 一致 |
| 数据库 | **零迁移** —— 判定逻辑在代码与 SQL 文本里，不在表结构里 |

---

## 验证

**反向验证**（roadmap §5.4，防假绿）：

| 改坏什么 | 期望 |
|---|---|
| 删掉 `MedicationBucket.of` 的 MISSED 分支 | `MedicationBucketTest` 5 条 + `StatisticsMapperMedicationFixTest` 1 条变红，报 `expected: <576> but was: <342>` |
| 把 SQL 的 `CASE_BUCKET` 换回旧写法 | `MedicationBucketParityTest` 变红（共享表达式不再出现 / 旧写法检出） |

实测：删分支后真库 parity 测试报 **`expected: <576> but was: <342>`** —— 正是那 234 条（179 + 55）漏服行。闸门是真的。
