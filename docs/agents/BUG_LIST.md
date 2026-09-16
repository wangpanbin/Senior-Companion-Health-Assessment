# 遗留 Bug 清单

- **维护时间**：2026-09-16
- **范围**：银龄伴诊后端（`backend/`）
- **口径**：本清单只记**产品缺陷**（功能错误 / 安全漏洞 / 数据不一致）；
  测试缺口与文档缺口属**任务**，见 `docs/agents/AUDIT_BACKEND_2026-09-16.md` 待办清单，不在此重复。

---

## 1 · 已修复缺陷（归档）

| # | 严重度 | 缺陷 | 根因 | 修复 commit |
|---|---|---|---|---|
| 1 | P0 | **时序攻击**：可据登录响应时间枚举账号是否存在 | 密码比对在账号不存在时短路返回 | `f3b1416` |
| 2 | P0 | **JWT 密码版本未校验**：改密后旧令牌仍可用 | 令牌未携带 / 未比对密码版本 | `f3b1416` |
| 3 | P1 | **登出未真正失效令牌**：仅前端删 token，服务端仍认 | 无令牌黑名单 | `f3b1416` |
| 4 | P0 | **并发写入非原子**：先 SELECT 再无条件 UPDATE | 竞态 | `f3b1416` |
| 5 | P1 | **陪诊员越权读取无关老人的用药计划** | 归属判定放宽到所有老人 | `122b943` |
| 6 | P1 | **SSE 断连刷两条假 ERROR**（最隐蔽） | 对死通道 `complete()` 触发错误分发；错误分发无 `SecurityContext` 再抛 `AccessDenied` | `5afb18a` 期间（三处联动） |
| 7 | P1 | **站内信有消费者无生产者** | M8 初版只做读接口 | `5afb18a` |
| 8 | P2 | 管理端校验顺序错误（先写后校验） | 状态闸门未前置 | `5afb18a` |
| 9 | P2 | 统计接口时间参数绑定失败 | 缺 `@DateTimeFormat` | `a61c00e` |
| 10 | P2 | e2e 脚本读库返回值恒为空 | `mysql_value` 缺 `encoding='utf-8'` | `df1ddef` |
| 11 | P2 | **客户端错误被兜底记成 500**：上传缺文件 → HTTP 200 + `code=500` + ERROR 堆栈 | `MissingServletRequestPartException` 未单独处理，落进 `@ExceptionHandler(Exception.class)` | 本轮 M12 收尾 |
| 12 | P2 | **导出「取消下载」被记成 500**：`catch (IOException)` 实为死代码 | EasyExcel 在 `finish()` 用自己包装的异常顶掉原始异常，判据不该用异常类型 | 本轮 M12 收尾 |
| 13 | P2 | 响应已提交时 `response.reset()` 抛 `IllegalStateException` **直接逃出方法** | 缺前置 `isCommitted()` 判断；不受检异常绕过所有 catch | 本轮 M12 收尾 |

> 合计 13 个已修复缺陷，其中 P0 三个（时序攻击 / JWT 版本 / 并发非原子）。
> 第 11–13 条都由本轮补测试时**反向发现**（11 是越权矩阵用例假红后的深挖，12/13 是导出单测深挖），
> 详见 `AUDIT_BACKEND_2026-09-16.md` §4.5 / §4.6。
> 回归基线：`mvn verify` **511 测试全绿** + JaCoCo 门禁达标（Service 行覆盖 63.26%）。

> ⚠️ **一条测试侧的教训**（不是产品缺陷，但差点变成「假绿」）：
> `ExecutionAccessMatrixTest` / `AdminAccessMatrixTest` 里有 4 个用例用「不带 payload 的写请求」断言 403，
> 而缺 payload 会在**参数解析阶段**就抛异常（早于方法级 `@PreAuthorize`），
> 请求根本没走到鉴权。当时它们表现为「假红」；如果有人为了让它变绿而放宽断言，
> 就会变成「假绿」—— 从此再也测不到鉴权是否真的生效。
> 修法是补上合法 payload（见 `ExecutionAccessMatrixTest#jpeg()` 与 `AdminAccessMatrixTest` 的 JSON body）。

---

## 2 · 当前遗留 Bug

| # | 严重度 | 缺陷 | 影响 | 建议 | 负责 |
|---|---|---|---|---|---|
| L1 | **P1** | **e2e 脚本残留数据**：`internal_message` 907 行 vs 种子 72 行 | 重复跑 e2e 会污染环境，导致依赖计数的断言假失败 | 各 e2e 脚本补 teardown；或在 `bench_setup.py` 统一快照/回滚 | C |
| L2 | **P2** | **M7 平均分非原子**：Java 算 `avg` 后写回，非 SQL 子查询 | 高并发评价下依赖事务隔离，理论上有丢失更新的窗口 | 改 `UPDATE companion_profile SET score=(SELECT AVG(...)) WHERE ...` | B |
| L3 | **P3** | `nianglin.export.max-rows` 未在 `application.yml` 显式声明 | 靠代码默认值 10000 生效，运维调参与文档对齐不便 | yml 显式写出 | D |
| L4 | **P3** | 握手后无法强制下线 WebSocket 连接（JWT 过期/封禁不主动断开） | 已记录为接受的风险（ADR-0001） | 靠客户端心跳重连 + Hub 清理兜底；如需踢下线引入 STOMP | B |
| L5 | **P3** | **`Task(定时)` 行覆盖仅 4.35%**（`MedicationScheduler` / `MissedDoseScanner`） | 定时任务是「静默失败」重灾区：没跑只在次日漏服率上体现，没人会收到告警 | 用 `@SpringBootTest` 直接调 `generateDailyTasks` / `scanMissedTasks` + 断言 Redis 锁与批次上限 | B |
| L6 | **P3** | **Service 行覆盖 63.26%，仅高于门禁 3.26pp** | 门禁是 60%，余量太薄；再给 Service 加分支就有滑破风险 | 要么继续补测（优先 `Task`、Controller），要么把门禁调到 55% 并写明理由 | B |

---

## 3 · 状态汇总

| 严重度 | 遗留数量 | 目标 |
|---|---|---|
| P0 | **0** | — ✅ |
| P1 | **1**（L1 e2e 残留） | W16 前清零 |
| P2 | **1**（L2 平均分原子性） | 可选优化 |
| P3 | **4**（L3–L6） | 文档 / 可接受风险 / 覆盖率加固 |

**结论**：后端代码层面 **P0 缺陷为零**；唯一 P1 是测试环境残留问题，不涉及线上功能。
计划「遗留 Bug 清单 P0/P1 W16 前清零」的目标中，P0 已达成，P1 待 L1 清理。

**本轮（2026-09-16 M12 收尾）新增遗留**：L5、L6 是补覆盖率时**如实暴露**的自身短板
（定时任务几乎零覆盖、Service 覆盖余量偏薄），不是新引入的缺陷 ——
把它们列出来而不是藏起来，是因为这两条决定了「覆盖率门禁到底还能不能拦住回归」。
建议优先级：L1 > L5 > L6 > L2 > L3/L4。
