# 迭代测试报告（二）· 陪诊执行与用药域（M5–M7）

- **报告时间**：2026-09-16（依实测基线归档）
- **测试范围**：M5 陪诊执行打卡 / M6 用药管理与漏服提醒 / M7 评价与投诉
- **被测版本**：`git log` `5afb18a → 122b943`
- **执行人**：B（M5/M7 主路径）+ D（M6 旁路径）+ C（横切）

---

## 1 · 测试构成与结果

| 层级 | 对象 | 用例/断言 | 结果 |
|---|---|---|---|
| 单元测试 | `ExecutionServiceTest` | 21 用例 | ✅ 全绿 |
| 单元测试 | `OrderProgressHubTest`（WS Hub） | 11 用例 | ✅ 全绿 |
| 单元测试 | `CheckinNodeTest` / `GeoUtilTest` | — | ✅ 全绿 |
| 越权 | 复用 `OrderAccessMatrixTest`（归属同源） | — | ⚠️ 无独立矩阵 |
| e2e | `e2e_execution.py` | 72 项断言 | ✅ 通过 |
| e2e | `e2e_medication.py` | 65 + 6 项断言（含两阶段） | ✅ 通过 |
| e2e | `e2e_review.py` | 64 项断言 | ✅ 通过 |

---

## 2 · 关键验证点

| 验证点 | 结论 |
|---|---|
| 打卡距离校验（超 2000m 判 4001） | ✅ `GeoUtil` + 阈值注入 |
| 打卡节点去重（重复打卡 4002） | ✅ 基于已打卡最大 `node_sort` |
| 打卡节点不可回退 | ✅ `CheckinNode` 单向 + `node_sort` 快照 |
| `order_checkin` + `companion_track` 双写 | ✅ 同事务 |
| WebSocket 握手鉴权（令牌 + 订单归属） | ✅ 401 / 403 分开回 |
| 漏服扫描判定 + 家属推送 | ✅ `was_missed=1` 语义锁定（C19） |
| 补记不洗白漏服率 | ✅ 补记后 `was_missed` 仍为 1 |
| 唯一索引 `uk_plan_time` 真能挡住重复插入 | ✅ e2e D1/D2 |
| 评价订单状态闸门（6001/6002） | ✅ |
| 敏感词过滤（6003） | ✅ |
| 平均分增量更新 | ✅ |

---

## 3 · 发现并修复的缺陷

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| 1 | **陪诊员可越权读取与自己无关老人的用药计划** | 归属判定放宽到「所有老人」 | `122b943` 收紧到「仅 IN_SERVICE 订单涉及的老人」 |
| 2 | **漏服扫描在测试中不可复现** | `initialDelay` 默认 5 分钟，测试窗口太短 | 引入 `--nianglin.medication.missed-scan-initial-delay-ms=5000` 分阶段跑法 |
| 3 | `e2e_*.py` mysql_value 返回空 | 未指定 `encoding='utf-8'` | `df1ddef` 补编码参数 |

---

## 4 · 特殊测试跑法（M6 两阶段）

漏服扫描是**后台定时任务**，无法在一次进程生命周期内同步验证，故 e2e 分两阶段：

```
阶段一：跑 e2e_medication.py --scan-phase 前的准备 → 埋探针任务（plan_time = now-3h，status=PENDING）
        → 停服务
阶段二：以 missed-scan-initial-delay-ms=5000 重启 → 启动即触发首轮扫描
        → 跑 e2e_medication.py --scan-phase → 断言探针已转 MISSED 且家属收到提醒
```

---

## 5 · 遗留问题

- `MedicationServiceTest` / `ReviewServiceTest` / `ComplaintServiceTest` 未写（M12 待补）。
- `MedicationAccessMatrixTest` / `ReviewAccessMatrixTest` 未写（M12 待补）。
- WS 重连压测（100 并发）未做。

---

## 6 · 结论

M5–M7 验收项全部通过，合规红线（用药字典 disclaimer、评价状态闸门）落实到位。
**可进入下一迭代（M8–M10）。**
