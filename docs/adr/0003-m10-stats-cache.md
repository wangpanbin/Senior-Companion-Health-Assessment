# ADR 0003 · M10 统计接口缓存策略

- **状态**：待填（PROPOSED）
- **日期**：TBD
- **决策者**：D
- **关联模块**：M10 数据统计、可视化与导出
- **关联文档**：`docs/api/09-statistics-export.md`、`docs/agents/PLAN_BACKEND.md §11`

## 背景（待填）

M10 提供管理后台看板的统计接口：

- **订单量 / 完成率**：日 / 周 / 月维度
- **用户增长**：日 / 周 / 月维度
- **陪诊员接单排行**
- **漏服率**

验收要求（M10 plan.md §3）：「统计接口在 1 万条订单数据下响应时间 < 2s」。

**复用记忆教训**：在 `cinema-server /api/orders/my` 项目里，加 30s Redis 缓存 + KEYS/SCAN 写路径失效，**P99 从 102ms 涨到 228-373ms**；拆掉缓存后 P99 恢复到 19.8ms（记忆条目 2026-08-31）。

**统计接口特点**：
- 读远多于写（管理员/家属查询频率低）
- 但看板轮询可能 10-30s 一次（多端并发）
- 写路径（订单状态变更、用户注册）非常频繁

## 候选方案（待填）

1. **不加缓存，纯 SQL 索引优化**
   - 依赖：(a) `companion_order(status, visit_time)`、(b) `medication_task(elder_id, plan_date, status)`、(c) `sys_user(create_time)`、(d) `companion_profile(audit_status)`
   - 优点：实现最简；不存在缓存一致性问题
   - 风险：1 万订单聚合 SQL 可能 > 2s（取决于聚合维度数）

2. **Redis 短 TTL 缓存（30s-5min）**
   - 优点：减少重复计算；TTL 自动失效
   - 缺点：需要写路径主动失效（KEYS/SCAN 在高并发下是反模式）；存在短暂不一致

3. **物化视图 / 定时预聚合**
   - MySQL 物化视图（`MATERIALIZED VIEW` 8.0 支持但用得少）；或每天 00:30 Spring Task 预聚合到 `statistics_snapshot` 表
   - 优点：接口读路径极快（直接查快照表）
   - 缺点：数据有滞后；磁盘占用

4. **不缓存但用 SQL `WITH ROLLUP` + 索引覆盖**
   - 单次聚合查询走索引覆盖（不回表），加 GROUP BY WITH ROLLUP
   - 优点：实时数据 + 单次 SQL
   - 缺点：复杂 SQL 难维护

## 决策（待填）

TBD（**默认候选 1 + 候选 4 组合：先纯 SQL 索引优化，必要时加物化视图**）

## 后果（待填）

- 不引入 Redis 缓存层（避免 KEYS/SCAN 反模式）
- 1 万订单聚合必须 < 2s：依赖 V1/V2 已建索引 + EXPLAIN 验证
- 若 SQL 仍 > 2s：升级到候选 3（每天 00:30 预聚合）
- EasyExcel 导出走流式写入，不缓存全量数据到内存

## 验证（待填）

- EXPLAIN：所有统计 SQL `type` 不能为 `ALL`；优先 `range` / `ref`
- 压测：1 万订单数据下 JMeter 跑 50 并发统计请求，P95 < 2s
- 数据一致性：手动触发订单状态变更后，统计接口在 ≤ 1s 内反映新数据（因为不缓存）

## 相关 ADR

- `0001-m5-websocket-auth.md`（无直接关联）
- `0002-m6-redis-lock.md`（共用 Redis，但本 ADR 决策**不使用** Redis 缓存）
- `0004-m13-docker-fallback.md`（无直接关联）

## 经验引用

`C:\Users\wang\.minimax\agents\mavis\memory\MEMORY.md` 条目 2026-08-31：高并发下 Redis 写时失效缓存策略要谨慎。
