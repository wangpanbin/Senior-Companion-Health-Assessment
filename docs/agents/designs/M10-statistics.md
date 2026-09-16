# M10 设计评审 · 数据统计、可视化与导出

- **模块**：M10（旁路径 D）
- **评审时间**：2026-09-16（依落地代码反向补齐）
- **关联**：`docs/api/09-statistics-export.md`、`docs/adr/0003-m10-stats-cache.md`、`docs/db/03-索引设计与EXPLAIN.md`、`PLAN_BACKEND.md §8 M10`
- **代码**：`controller/statistics/StatisticsController`、`service/impl/StatisticsServiceImpl`、`service/impl/ExportServiceImpl`、`mapper/StatisticsMapper`

---

## 1 · 接口表

**整类 `@PreAuthorize("hasRole('ADMIN')")`** —— 数据看板仅管理员可见。

| # | 方法 | 路径 | 入参 | 出参 |
|---|---|---|---|---|
| 1 | GET | `/api/statistics/overview` | `StatisticsQuery` | `StatisticsOverviewVO` |
| 2 | GET | `/api/statistics/order-trend` | `StatisticsQuery` | `ChartDataVO` |
| 3 | GET | `/api/statistics/order-status` | `StatisticsQuery` | `List<OrderStatusStatVO>` |
| 4 | GET | `/api/statistics/companion-rank` | `StatisticsQuery` | `List<CompanionRankVO>` |
| 5 | GET | `/api/statistics/medication-missed` | `StatisticsQuery` | `MedicationMissedVO` |
| 6 | GET | `/api/statistics/export/order` | `AdminOrderQuery` | Excel 流（`OrderExportVO`） |
| 7 | GET | `/api/statistics/export/user` | `AdminUserQuery` | Excel 流（`UserExportVO`） |

查询维度由枚举约束：`StatisticsGranularity`（日/周/月）、`CompanionRankMetric`（排行指标）。
时间区间非法 → `9002 STAT_RANGE_INVALID`。

---

## 2 · 数据流

```
GET /api/statistics/overview
  StatisticsController(@PreAuthorize ADMIN)
    → StatisticsServiceImpl.overview(query)
        ├─ 校验时间区间（9002）
        ├─ StatisticsMapper.xxx（聚合 SQL，走覆盖索引）
        └─ 组装 VO（不缓存）

GET /api/statistics/export/order
  → StatisticsServiceImpl.exportOrders(query, response)
      ① adminService.queryOrdersForExport(query, maxExportRows)
      ② if (rows.size() > maxExportRows) → 9001 EXPORT_LIMIT_EXCEEDED
      ③ 转 OrderExportVO（脱敏：不含密码 / 身份证 / 完整手机号）
      ④ exportService.writeExcel(...) → EasyExcel 流式写出

表 → Mapper → Service → Controller：
  companion_order   ─┐
  medication_task   ─┼→ StatisticsMapper（聚合 SQL）
  sys_user          ─┤        ↓
  companion_profile ─┘  StatisticsServiceImpl（区间校验 → 聚合 → VO）
                             ↓
                        StatisticsController → ECharts 数据 / ExportServiceImpl → Excel
```

---

## 3 · 状态机

**无**（统计是只读聚合，不产生状态流转）。

---

## 4 · 缓存策略（ADR-0003 摘要）

**决策：不加缓存，纯 SQL 索引优化。**

- 依据：统计接口**读少写多**，正是「写时失效缓存」最不划算的场景；
  记忆条目 2026-08-31 的实测教训（加 30s Redis 缓存后 P99 从 102ms 涨到 228–373ms，拆掉后恢复 19.8ms）直接适用。
- 后果：`StatisticsServiceImpl` 无任何 `@Cacheable` / 手动 Redis 读写；天然强一致（无 TTL 窗口）。
- 升级路径：若 1 万订单聚合 > 2s，升级到「每天 00:30 预聚合到快照表」（方案 3）。

---

## 5 · 验收清单

| # | 验收项 | 状态 |
|---|---|---|
| 1 | dashboard / trend / rank / export 端点 | ✅（7 个，含 order-status / medication-missed） |
| 2 | 聚合 SQL（订单量 / 完成率 / 漏服率 / 陪诊员排行） | ✅ `StatisticsMapper` |
| 3 | EasyExcel 导出 | ✅ `ExportServiceImpl`（`LongestMatchColumnWidthStyleStrategy`） |
| 4 | 导出脱敏（不含密码 / 身份证 / 完整手机号） | ✅ `OrderExportVO` / `UserExportVO`（详见 `docs/api/09`） |
| 5 | 1 万订单 < 2s | ⚠️ 脚本就绪（`jmeter_m10_overview.jmx`），**实测报告未归档** |
| 6 | 导出上限保护 | ✅ `max-rows` 默认 10000，超限抛 `9001` |
| 7 | 单测 / 越权 / e2e | ❌ `StatisticsServiceTest` / `ExportServiceTest` 缺 / ❌ `StatisticsAccessMatrixTest` 缺 / ✅ `e2e_statistics.py` |
| 8 | 索引质量（EXPLAIN 无 `ALL`） | ✅ `docs/db/03-索引设计与EXPLAIN.md` + `explain_index.sql` |

---

## 6 · 遗留与风险

- `StatisticsServiceTest` / `ExportServiceTest` / `StatisticsAccessMatrixTest` 缺（审计报告 P0 待办）。
- 1 万订单 < 2s 的压测结果未归档（脚本已有，缺一次真实运行与报告）。
- `application.yml` 未显式声明 `nianglin.export.max-rows`（靠代码默认值 10000），建议显式化（P3）。
