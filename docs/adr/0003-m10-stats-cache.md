# ADR 0003 · M10 统计接口缓存策略

- **状态**：已接受（ACCEPTED）
- **日期**：2026-09-16（本 ADR 回填；决策随 M10 实现一同落地）
- **决策者**：D（B 复核）
- **关联模块**：M10 数据统计、可视化与导出
- **关联文档**：`docs/api/09-statistics-export.md`、`docs/agents/PLAN_BACKEND.md §11`、
  `docs/db/03-索引设计与EXPLAIN.md`、`backend/sql/tools/jmeter_m10_overview.jmx`

---

## 背景

M10 提供管理后台看板的统计接口：

- **订单量 / 完成率**：日 / 周 / 月维度（`StatisticsGranularity`）
- **用户增长**
- **陪诊员接单排行**（`CompanionRankMetric`）
- **漏服率**（`medication_task` 的 `was_missed`）

验收要求（plan.md M10 §3）：「统计接口在 1 万条订单数据下响应时间 < 2s」。

**复用记忆教训**：在 `cinema-server /api/orders/my` 项目中，加 30s Redis 缓存 + `KEYS`/`SCAN` 写路径失效，
**P99 从 102ms 涨到 228–373ms**；拆掉缓存后 P99 恢复到 19.8ms（记忆条目 2026-08-31）。

**统计接口的特点**：
- 读远多于写（管理员查询频率低）
- 但看板可能 10–30s 轮询一次，多端并发
- 而写路径（订单状态变更、用户注册）非常频繁

也就是说：**读少、写多**。这正是「写时失效缓存」最不划算的场景 —— 每次写都要失效，
而读本来就不多，缓存命中率低、失效开销高。

---

## 候选方案

### 方案 1：不加缓存，纯 SQL 索引优化（**采用**）

- 依赖索引：(a) `companion_order(status, visit_time)` / (b) `medication_task(elder_id, plan_date, status)` /
  (c) `sys_user(create_time)` / (d) `companion_profile(audit_status)`
- 优点：实现最简；**不存在缓存一致性问题**；数据实时
- 风险：1 万订单聚合可能 > 2s（取决于聚合维度数）

### 方案 2：Redis 短 TTL 缓存（30s–5min）（**未采用**）

- 优点：减少重复计算；TTL 自动失效
- **未采用理由**：需要写路径主动失效，而 `KEYS`/`SCAN` 在高并发下是反模式；
  本项目读少写多，命中率低、失效成本高 —— 与记忆条目 2026-08-31 的教训完全同构。

### 方案 3：物化视图 / 定时预聚合（**未采用，保留为升级路径**）

- 做法：每天 00:30 用 Spring Task 预聚合到 `statistics_snapshot` 表，接口直接查快照
- 优点：读路径极快
- **未采用理由**：数据有滞后（管理看板要求近实时）；引入新表与新任务，复杂度不划算。
  **保留为「若方案 1 达不到 2s」时的一级升级路径。**

### 方案 4：`GROUP BY WITH ROLLUP` + 覆盖索引（**部分采用**）

- 单次聚合走覆盖索引（不回表），必要时用 `WITH ROLLUP` 合并小计
- 优点：实时数据 + 单次 SQL
- 采用情况：聚合 SQL 在 `StatisticsMapper` 中已按「尽量走覆盖索引」编写，
  未强上 `ROLLUP`（当前维度拆分已够用，`ROLLUP` 会增加 SQL 维护成本）

---

## 决策

**采用方案 1（不加缓存，纯 SQL 索引优化），必要时升级到方案 3。**

不引入 Redis 缓存层 —— 这是本 ADR 的核心结论，也是最容易被「看到统计就想到加缓存」的直觉带偏的一步。

---

## 后果

### 代码产物

- `StatisticsServiceImpl` **不含任何 `@Cacheable` / `@CacheConfig` / 手动 Redis 读写**；
  它只做「组装查询参数 → 调 `StatisticsMapper` → 转 VO」。
- 聚合 SQL 集中在 `mapper/StatisticsMapper`（非 XML，注解/Mapper 方法形式）。
- 统计维度由枚举约束：`StatisticsGranularity`（日/周/月）、`CompanionRankMetric`（排行指标）。

### 索引依赖

- 统计性能**完全押在索引上**，索引设计与 EXPLAIN 验证见 `docs/db/03-索引设计与EXPLAIN.md`。
- 复核脚本：`backend/sql/tools/explain_index.sql`（逐条 EXPLAIN 关键查询，确认 `type` 不出现 `ALL`）。

### 缓存一致性

- 无需处理 —— 天然强一致。订单状态变更后，统计接口**下一次请求即反映新数据**，无 TTL 窗口。

### 导出

- `StatisticsServiceImpl` 的导出方法先做 **`max-rows` 上限判定**（默认 10000，超限抛 `EXPORT_LIMIT_EXCEEDED`），
  再由 `ExportServiceImpl` 用 EasyExcel 流式写出，**不把全量数据常驻内存做缓存**。

---

## 验证

### 索引（已做）

- `backend/sql/tools/explain_index.sql` 对统计相关查询逐条 EXPLAIN，
  要求 `type` 为 `range` / `ref`（**不得为 `ALL`**）；结果归档于 `docs/db/03-索引设计与EXPLAIN.md`。

### e2e（已做）

- `backend/sql/tools/e2e_statistics.py`：校验统计接口返回值与库内明细逐项一致
  （如漏服率 = `was_missed=1` 占比、任务总数与日历接口一致等）。

### 压测（脚本就绪，结果待归档）

- `backend/sql/tools/jmeter_m10_overview.jmx`：1 万订单量级下并发请求总览接口，验证 P95 < 2s。
- **当前状态**：脚本已归档（commit `a753072`），**实测报告尚未归档** ——
  建议纳入 M12 迭代测试报告（见 `AUDIT_BACKEND_2026-09-16.md` P1 待办）。

### 一致性（设计保证）

- 因不缓存，无需专门的一致性验证；这也消除了「统计数字与明细对不上」这一类最难排查的问题。

---

## 相关 ADR

- `0001-m5-websocket-auth.md`（无直接关联）
- `0002-m6-redis-lock.md`（共用同一 Redis 实例，但本 ADR 决策**不使用** Redis 做缓存；
  该 ADR 用 Redis 做分布式锁，用途正交）
- `0004-m13-docker-fallback.md`（无直接关联）

---

## 经验引用

记忆条目 2026-08-31（`cinema-server /api/orders/my`）：高并发下「写时失效」的 Redis 缓存策略要谨慎 ——
加缓存后 P99 反而从 102ms 涨到 228–373ms，拆掉后恢复到 19.8ms。本 ADR 据此选择不缓存。
