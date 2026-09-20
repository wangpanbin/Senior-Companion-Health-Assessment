# 后端实现核对报告 · 2026-09-16

> 核对依据：`docs/agents/PLAN_BACKEND.md`（W3–W17 共 15 周计划，M0–M10）
> 核对方法：逐文件核验 backend/src/main/java 全量源码 + 测试目录 + docs/api + ADR + git log + 关键合规点 grep
> 核对人：小斌
> 生成时间：2026-09-16

---

## 0 · 一句话结论

**M0–M10 的生产代码已全部落地并 commit，质量在线（状态机 / 乐观锁 / 合规红线 / 越权归属校验均到位）；M12 测试横切已于 2026-09-16 收尾（511 测试全绿、JaCoCo 门禁达标、9 个越权矩阵、4 个压测计划），剩余唯一 P1 是测试环境残留数据（不涉线上功能）。**

---

## 1 · 总体进度矩阵

| 模块 | 代码 | 单测 | 越权矩阵 | e2e | 接口文档 | 设计评审 | ADR | commit | 状态 |
|---|---|---|---|---|---|---|---|---|---|
| M0 骨架 | ✅ | — | — | — | ✅ | — | — | `6bc37c5` | 🟢 完成 |
| M1 建表 | ✅ V1–V3 | — | — | — | ✅ db/ | — | — | `6bc37c5` | 🟢 完成 |
| M2 认证 | ✅ | ✅ AuthServiceTest | ✅ PermissionMatrixTest | ✅ e2e_auth | ✅ 01 | ❌ | — | `0199770/f3b1416/f464fcd/c883c52` | 🟢 完成 |
| M3 档案 | ✅ | ✅ Elder/Companion ServiceTest | ✅ ElderOwnershipMatrixTest | ✅ e2e_user_profile | ✅ 02 | ❌ | — | `d17056a/666f108` | 🟢 完成 |
| M4 订单 | ✅ | ✅ OrderServiceTest | ✅ OrderAccessMatrixTest | ✅ e2e_order | ✅ 03 | ❌ | — | `666f108/c883c52/5afb18a` | 🟢 完成 |
| M5 执行打卡 | ✅ | ✅ ExecutionServiceTest | ✅ ExecutionAccessMatrixTest | ✅ e2e_execution | ✅ 04 | ✅ M5-execution | ✅ 0001 ACCEPTED | `5afb18a` | 🟢 完成 |
| M6 用药 | ✅ | ✅ MedicationServiceTest | ✅ MedicationAccessMatrixTest | ✅ e2e_medication | ✅ 05 | ✅ M6-medication | ✅ 0002 ACCEPTED | `5afb18a/122b943` | 🟢 完成 |
| M7 评价投诉 | ✅ | ✅ ReviewServiceTest / ComplaintServiceTest | ✅ ReviewAccessMatrixTest | ✅ e2e_review | ✅ 06 | ✅ M7-review | — | `5afb18a` | 🟢 完成 |
| M8 站内信 | ✅ | ✅ MessageServiceTest + MessageSseHubTest | ✅ MessageAccessMatrixTest | ✅ e2e_message | ✅ 07 | ✅ M8-message | — | `5afb18a` | 🟢 完成 |
| M9 管理后台 | ✅ | ✅ AdminServiceTest | ✅ AdminAccessMatrixTest | ✅ e2e_admin | ✅ 08 | ✅ M9-admin | — | `5afb18a` | 🟢 完成 |
| M10 统计导出 | ✅ | ✅ StatisticsServiceTest / ExportServiceTest | ✅ StatisticsAccessMatrixTest | ✅ e2e_statistics | ✅ 09 | ✅ M10-statistics | ✅ 0003 ACCEPTED | `5afb18a/a61c00e` | 🟢 完成 |
| M12 测试横切 | — | ✅ JaCoCo 门禁（Service 行覆盖 63.26% ≥ 60%） | ✅ 9 个矩阵（M2–M10 全覆盖） | ✅ 4 个 .jmx（M4/M5/M6/M10） | — | ✅ 3 份迭代报告 | — | — | 🟢 完成 |

> 📌 **状态更新（2026-09-16 收尾）**：上表 M5–M12 的绿点来自本报告的 §7「M12 收尾记录」，
> 含 511 个测试全绿、Service 覆盖率实测值、以及 M5/M6 两个实际跑过的 JMeter 压测。
> 另有 **3 处本报告初版的误判**在 §4.4 更正（`checkin-missed` 误标「存在」、
> 客户端错误误归 500、导出 `IOException` 分支实为死代码）。

---

## 2 · 已完成部分（按需求逐项核对）

### 2.1 横切基础设施（M0–M2 公共件）✅ 全部到位

| 需求项 | 实现位置 | 核验结论 |
|---|---|---|
| Result/ResultCode/PageResult/PageQuery/BaseEntity | `common/` | ✅ 五件套齐全 |
| GlobalExceptionHandler 统一异常 | `exception/GlobalExceptionHandler.java` | ✅ **含 M8 SSE 断开修复**：按 `response.isCommitted()` 区分「客户端断开」与「真 IO 故障」，避免日志刷假 ERROR |
| SecurityConfig 三层鉴权 | `config/SecurityConfig.java` | ✅ 认证(过滤器)→角色(@PreAuthorize)→只读(ElderReadOnlyInterceptor) 分层；放行 ASYNC/ERROR 分发（M8 SSE 必需） |
| JwtAuthenticationFilter | `security/JwtAuthenticationFilter.java` | ✅ **封禁黑名单前置**：`isBanned` 判定排在密码版本之前，避免被封账号反复登录反复被拒的体验陷阱 |
| TokenStore(Redis) | `security/TokenStore.java` | ✅ 黑名单/密码版本/封禁标记 |
| ElderReadOnlyInterceptor + @AllowElderWrite | `security/` | ✅ 老人账号只读产品规则落地 |
| Knife4j / MyBatis-Plus 配置 | `config/` | ✅ 分页+乐观锁+逻辑删除拦截器、MetaObjectHandler |
| BCrypt 密码 / 手机号身份证脱敏 / AES | `util/MaskUtil` `util/AesUtil` | ✅ 合规红线 2 落地 |

### 2.2 M4 陪诊订单与状态机 ✅ 完成且质量高

| 验收项 | 核验结论 |
|---|---|
| 9 个端点（下单/列表/大厅/详情/取消/接单/拒单/开始/完成/时间线） | ✅ OrderController + OrderServiceImpl 全覆盖 |
| 状态机 5 状态 + 1 终态，禁止跳级/回退 | ✅ `OrderStatus` + 每个流转方法先判状态(3002)再判身份(4003/3004) |
| 50 并发抢单乐观锁 | ✅ `CompanionOrder.version` `@Version` + `updateById` 带 version 条件，失败抛 3003 |
| 其余流转用 `WHERE status=期望值` 条件更新 | ✅ cancel/start/complete/markReviewed/forceTerminal 均条件更新，affectedRows=0 抛 3002 |
| OrderStatusLog 写入逻辑 | ✅ `writeStatusLog` 每次流转落库，operatorName 定格快照 |
| 合规红线（服务小结禁诊断/处方/用药建议） | ✅ `ComplianceCheckUtil.firstHit` 命中即拒并回显命中词 |
| 归属校验（家属 A 不能读家属 B 的订单） | ✅ `requireInvolved` 在 Service 层判定，非仅靠角色注解 |
| 订单号 Redis INCR 防并发重复 | ✅ `nextOrderNo` 用 Redis 自增 + 2 天 TTL 跨零点自动归 1 |

### 2.3 M5 陪诊执行、打卡与实时进度 ✅ 代码完成

| 验收项 | 核验结论 |
|---|---|
| checkin / timeline / progress / photo 端点 | ✅ ExecutionController（`/checkin`、`/checkins`、`/track`、`/progress`、`/photo`） |
| 距离校验（4001） | ✅ `GeoUtil.distanceMeters` + `@Value("${nianglin.order.checkin-max-distance-meters:2000}")` 阈值注入 |
| 节点去重（4002） | ✅ ExecutionServiceImpl 注释明确 6 节点不可回退 |
| 双写轨迹（order_checkin + companion_track） | ✅ `row.setDistance` 写 checkin + track 双写 |
| WebSocket 鉴权（ADR-0001） | ✅ **ADR-0001 已完整回填（ACCEPTED）**：握手 URL 带 token + 校验订单归属，SecurityConfig 放行 `/ws/**` |
| JwtHandshakeInterceptor / OrderProgressHandler / OrderProgressHub | ✅ 三件套齐全 |
| 重连补齐（断线不丢事件） | ⚠️ **本报告初版误标**：走 `GET /{orderId}/progress` + `GET /{orderId}/checkins` 重建时间线（`docs/api/04` §6 明确允许此方案），`PLAN_BACKEND.md` 里提到的 `/checkin-missed?since=` **未实现且接口文档从未定义**，属有意取舍，详见 §4.4 |

### 2.4 M6 用药管理与漏服提醒 ✅ 代码完成

| 验收项 | 核验结论 |
|---|---|
| medicine-dict / plan / task / confirm 端点 | ✅ MedicationController |
| **药品字典必返 disclaimer（合规红线 1）** | ✅ **典范实现**：`MedicineVO` 有 `disclaimer` 字段 + `@Schema("必返，前端必须展示")` + 兜底文案保证数据库为空也必有；Controller `@Operation` 注解明示"不含建议剂量/适应症判断/替代药" |
| MedicationTaskGenerator 每天 07:00 | ✅ `MedicationScheduler.generateDailyTasks` cron `${nianglin.medication.daily-generate-cron:0 0 7 * * ?}` |
| MissedDoseScanner 每 30 分钟 | ✅ `scanMissedTasks` fixedRate 1800000ms |
| Redis 分布式锁 | ✅ `RedisLockUtil.tryLock`，daily TTL 10min / missed TTL 5min（< 30min 间隔保证下轮可抢）；异常自己接住避免打断调度 |
| 漏服推送给家属（经 M8） | ✅ `messageService.sendBatch(familyIds, MessageType.MEDICATION_REMIND, ...)` |
| @ConditionalOnProperty 任务开关 | ✅ `scheduler-enabled` |
| 配置化阈值 | ✅ missed-threshold-minutes / miss-scan-batch / missed-scan-initial-delay-ms |

### 2.5 M7 评价与投诉 ✅ 代码完成

| 验收项 | 核验结论 |
|---|---|
| review / complaint 端点 | ✅ ReviewController + ComplaintController |
| 订单状态闸门（6001/6002） | ✅ `ReviewServiceImpl` 校验 `OrderStatus.COMPLETED`，非完成态抛 `ORDER_NOT_COMPLETED` |
| 敏感词过滤（6003） | ✅ `SensitiveWordUtil.firstHit` |
| 平均分增量更新 | ✅ `companionProfileMapper.update` set score=avgScore + reviewCount；**注：用 Java 算 avg 后写回，非计划描述的 SQL 子查询 `SET avg_rating=(SELECT AVG...)`，业务结果等价但实现略异** |

### 2.6 M8 站内信与通知 ✅ 代码完成

| 验收项 | 核验结论 |
|---|---|
| list / detail / read / read-all / unread-count 端点 | ✅ MessageController + MessageSseController |
| SSE 推送 `/sse/message` | ✅ `MessageSseHub` |
| **SSE 断开不刷假 ERROR（记忆中修复点）** | ✅ **典范修复**：`push` 写失败只摘引用**绝不调用 complete()**，详注解释错误分发链路；`SecurityConfig` 放行 ASYNC/ERROR；`GlobalExceptionHandler` 按 isCommitted 区分 |
| 消息模板（订单/审核/漏服/系统公告） | ✅ `MessageTemplateUtil` + `MessageType` 枚举 |
| 统一发消息入口 | ✅ `MessageService.send/sendBatch` |
| 各业务模块发消息复用入口 | ✅ OrderServiceImpl 已调 `messageService.send`（下单广播/接单/完成）；M6 漏服也调 |

### 2.7 M9 管理后台 ✅ 代码完成

| 验收项 | 核验结论 |
|---|---|
| 审核/用户管理/订单管理/操作日志 4 子模块 | ✅ AdminController |
| admin_oper_log 必写 | ✅ `writeOperLog` 在 AUDIT_COMPANION/DISABLE_USER/ENABLE_USER/RESET_PASSWORD/ARBITRATE_ORDER/HANDLE_COMPLAINT 六处调用 |
| 纠纷处理强制终态 | ✅ `arbitrate` → `orderService.forceTerminal`（只允许 COMPLETED/CANCELLED，条件更新防并发覆盖） |
| 封禁黑名单 | ✅ `disableUser` + JwtAuthenticationFilter `isBanned` 前置判定 |

### 2.8 M10 数据统计、可视化与导出 ✅ 代码完成

| 验收项 | 核验结论 |
|---|---|
| dashboard / trend / rank / export 端点 | ✅ StatisticsController |
| 聚合 SQL（订单量/完成率/漏服率/陪诊员排行） | ✅ `StatisticsMapper` + `StatisticsServiceImpl` |
| EasyExcel 导出 | ✅ `ExportServiceImpl` 用 `EasyExcel.write` + `LongestMatchColumnWidthStyleStrategy` |
| 导出 VO（OrderExportVO/UserExportVO） | ✅ 两个导出专用 VO（合规：不含密码/身份证/完整手机号，需复核 VO 字段） |

---

## 3 · 缺失或未实现的功能

### 3.1 M12 测试与质量保障 ✅ 已收尾（原为 🔴 严重残缺）

| 计划要求 | 实际状态 | 结论 |
|---|---|---|
| JaCoCo 覆盖率门禁 W11 上线（Service ≥ 60%） | ✅ `pom.xml` 已配 `jacoco-maven-plugin 0.8.12`，`verify` 阶段硬卡；实测 **Service 实现层行覆盖 63.26%**、全量行覆盖 63.66% | ✅ 达标（余量 3.26pp，偏薄，见 §7.4） |
| 4 角色 × 3 类接口越权矩阵扩到全部模块 | ✅ **9 个矩阵**覆盖 M2–M10（新增 Execution 15 / Medication 25 / Review 31 / Message 21 / Admin 13 / Statistics 16 = **121 项**，与既有 105 项合计 **226 项**） | ✅ 达标 |
| 各模块 Service 单测 ≥ 60% 覆盖 | ✅ M6–M10 单测补齐（Medication 23 / Review 12 / Complaint 13 / Message 12 / Admin 13 / Statistics 11 / Export 6） | ✅ 达标 |
| JMeter 压测脚本（M4 50 并发 / M5 WS 重连 / M6 并发任务） | ✅ 4 个 `.jmx`；**M5/M6 两个为本轮新增并已实际跑过**（300 / 150 样本，Err 均 0.00%），见 §7.3 | ✅ 达标（WS 握手本身未压，理由见压测记录 §6） |
| 三个迭代测试报告归档 | ✅ `docs/agents/reports/iteration-test-report-1/2/3.md` | ✅ 达标 |
| 遗留 Bug 清单 P0/P1 W16 前清零 | ✅ `docs/agents/BUG_LIST.md`（P0 = 0；唯一 P1 是测试环境残留数据，见 L1） | 🟡 P1 仍剩 1 项 |

### 3.3 设计评审文档 🔴 全缺

| 计划要求（§5、§12） | 实际状态 | 缺口 |
|---|---|---|
| `docs/agents/designs/M4-order.md` ~ `M10-statistics.md` 共 7 份 | ❌ `docs/agents/designs/` 目录不存在 | **P1**：7 份模块设计评审文档全缺（计划要求每个模块开工前用 dsh-plan 生成 ≤1 页设计文档） |

### 3.4 ADR 三份待填

| ADR | 主题 | 状态 | 缺口 |
|---|---|---|---|
| 0001 | M5 WebSocket 鉴权 | ✅ ACCEPTED（完整） | — |
| 0002 | M6 Redis 分布式锁 | ❌ PROPOSED 待填 | **P1**：代码已用 `RedisLockUtil`（手写 SET NX PX），但 ADR 未写决策记录 |
| 0003 | M10 统计缓存策略 | ❌ PROPOSED 待填 | **P1**：代码实际"不缓存"（无 @Cacheable），与 §11 默认决策一致，但 ADR 未记录 |

---

## 4 · 不符合需求或存在逻辑缺陷之处

### 4.1 导出上限保护 —— ✅ 已实现（本条已更正）

> ⚠️ **本报告初版误报，现予更正**：初版称"导出无行数上限保护"，系 grep 关键词大小写不匹配所致
> （搜 `limit` 未命中 `EXPORT_LIMIT_EXCEEDED`，搜 `max` 未命中 `maxExportRows`）。复核确认**该功能已完整实现**。

- **实际实现**：`StatisticsServiceImpl` L91–92 `@Value("${nianglin.export.max-rows:10000}") private int maxExportRows;`；
  L396–411 在**订单导出**与**用户导出**两处均做上限判定，超限抛
  `BusinessException(ResultCode.EXPORT_LIMIT_EXCEEDED, "本次筛选结果超过 N 行，请缩小筛选范围后再导出")`。
- **结论**：健壮性达标，默认上限 1 万行，**P2 待办撤销**。
- **可选增强（P3）**：`application.yml` 未显式声明 `nianglin.export.max-rows`，当前靠代码默认值 `10000` 兜底。
  建议在 yml 显式写出，便于运维调参与文档对齐。

### 4.2 M7 平均分聚合实现与计划描述略异 ⚠️ P3（可接受）

- **现象**：计划 §8 M7 写"`CompanionProfile.avgRating` 增量更新（用 SQL `UPDATE ... SET avg_rating = (SELECT AVG...)`）"，实际 `ReviewServiceImpl` 用 Java 算 `score.getAverageScore()` 后 `lambdaUpdate().set(score, ...)` 写回。
- **影响**：业务结果等价（都能正确更新平均分），但 Java 内存计算在评价并发下需靠 `@Transactional` + 后续读保证一致性，不如 SQL 子查询原子。当前可接受，未来高并发场景考虑改 SQL 子查询。

### 4.3 工作记忆过时（非代码缺陷，但需纠正）ℹ️

- 记忆记录"M5–M10 实测全绿（尚未 commit）"，**实际 git log 显示已全部 commit**（`5afb18a feat(M5-M9)` + `a61c00e` + `a753072` + `122b943` + `df1ddef` + `feecbd2`，共 13 个 commit，最新 `feecbd2`）。记忆需更新。

### 4.4 本报告初版的三处误判（2026-09-16 收尾时更正）⚠️

| # | 初版结论 | 实际情况 | 依据 |
|---|---|---|---|
| 1 | §2.3「重连补齐（`checkin-missed?since=`）✅ 端点存在」 | **不存在**。全仓库 grep 只在 `PLAN_BACKEND.md`/设计文档里出现该字符串，`ExecutionController` 与 `docs/api/04` 都没有它。实际补齐走 `/progress` + `/checkins`，且 `docs/api/04` §6 明确把这条列为合法方案 → **属有意取舍，不是缺口**，但结论写错 | `grep -r "checkin-missed"` 源码零命中 |
| 2 | §4.1 已更正过一次（导出上限） | — | 见 §4.1 |
| 3 | 未发现「客户端错误被记成 500」 | **真缺陷**，见 §4.5 | `GlobalExceptionHandlerClientErrorTest` |

> 教训：矩阵里给某一项打 ✅ 之前，必须让**源码**给出证据（grep 到实现或测试到行为），
> 不能以「计划书写过 + 设计文档里也写了」作为推断依据 —— 后两者是同一份想法的两次转述，
> 不构成独立证据。这条误判就是这样产生的，并且一度被 `designs/M5-execution.md` 复制。

### 4.5 客户端错误被兜底吞成 500（P2，已修）🐛

- **现象**：`POST /api/execution/{orderId}/photo` 不带文件部分时，返回 HTTP 200 + `code=500`，
  并在日志里打一条 **ERROR 级系统异常堆栈**。
- **根因**：`MultipartFile` 参数缺失抛的是 `MissingServletRequestPartException`，
  `GlobalExceptionHandler` 没有单独处理它，落进了 `@ExceptionHandler(Exception.class)` 兜底分支
  （记 ERROR + 返回 `SYSTEM_ERROR`）。这类「调用方拼错参数」的错误被当成服务端故障。
- **影响**：日志噪声 + 前端看到「服务器开小差了」而真实原因是自己漏传文件，只会原样重试；
  更隐蔽的是**该异常在参数解析阶段抛出，早于方法级 `@PreAuthorize`** ——
  越权矩阵若用「不带文件的上传请求」断言 403，会拿到 200 而误判成「鉴权失效」
  （本轮 `ExecutionAccessMatrixTest.nonCompanionShouldNotUploadPhoto` 就是这么假红的）。
- **修复**：`GlobalExceptionHandler` 增加 `MissingServletRequestPartException` 分支 → `PARAM_ERROR` + WARN；
  新增 `GlobalExceptionHandlerClientErrorTest`（9 项）把「客户端错误不进兜底 / 真缺陷仍是 500」两侧都锁死。
- **同类**：`MaxUploadSizeExceededException` 早已单独处理，本次是补上漏网的那一个。

### 4.6 导出服务里两处「写了但不会生效」的代码（P2，已修）🐛

| # | 问题 | 实测结论 | 修复 |
|---|---|---|---|
| 1 | `catch (IOException)` 注释写着「客户端中途断开只记日志」，指望它挡住取消下载 | **接不到**。EasyExcel 在 `finish()` 阶段用自己包装的异常顶掉原始异常（消息固定为 `Can not close IO`，cause 才是 IOException），原始类型已丢失 → 之前「用户取消下载」会走 `RuntimeException` 分支，**记 ERROR 并返回 500** | 判据改为 `response.isCommitted()`（与 `GlobalExceptionHandler` 对 IOException 的处理同一套逻辑）：已提交 ⇒ WARN + 不抛；未提交 ⇒ 500 |
| 2 | 方法开头的 `response.reset()` | 响应已提交时 `reset()` 抛 `IllegalStateException: Cannot reset buffer - response is already committed`，而这个**不受检异常会整个逃出方法**落到兜底处理器，响应又早已发出 → 又是一串无意义 ERROR（M8 同款噪声） | 开头加 `if (response.isCommitted()) { WARN; return; }` |

- **验证**：新增 `ExportServiceTest`（6 项），用「取流即失败 + 取流时把响应标记为已提交」的自定义
  `MockHttpServletResponse` 精确复现两种失败，并锁死「进来时就已提交 ⇒ 一个字节都不写」。

---

## 5 · 按优先级的待办清单

### P0（影响交付阻塞的项）—— ✅ 全部清零

1. （无）
2. **M12 JaCoCo 覆盖率门禁** → ✅ **已完成**：`jacoco-maven-plugin 0.8.12` + `verify` 硬卡 ≥ 60%，实测 Service 63.26%。
3. **M6–M10 五个模块 Service 单测** → ✅ **已完成**：MedicationServiceTest / ReviewServiceTest / ComplaintServiceTest / MessageServiceTest / AdminServiceTest / StatisticsServiceTest / ExportServiceTest 共 **90 项**断言。
4. **M5–M10 六个模块越权矩阵测试** → ✅ **已完成**：Execution 15 / Medication 25 / Review 31 / Message 21 / Admin 13 / Statistics 16，共 **121 项**断言，每个都超过「4 角色 × 3 类接口 ≥ 12 条」的下限（9 个矩阵合计 226 项）。

### P1（影响评分的项）—— 仅剩 1 项

5. **模块设计评审文档** → ✅ 已完成：`docs/agents/designs/M4–M10` 共 7 份。
6. **ADR 回填** → ✅ 已完成：0002/0003 转 `ACCEPTED`。
7. **JMeter M5/M6 压测脚本** → ✅ 已完成且**已实际运行**：`jmeter_m5_reconnect.jmx`、`jmeter_m6_concurrent_confirm.jmx`
   （`jmeter_m5_websocket` 这个命名改为按真实接口命名，理由见 §4.4；WS 握手本身的压测仍需插件，已记入压测记录 §6）。
8. **三个迭代测试报告归档** → ✅ 已完成：`reports/iteration-test-report-1/2/3.md` + `reports/pressure-test-m5-m6.md`。
9. **遗留 Bug 清单** → ✅ 已完成：`docs/agents/BUG_LIST.md`。
10. **e2e 残留数据（BUG_LIST L1）** → ⏳ **仍开放**：`internal_message` 行数超种子基线，重复跑 e2e 会污染环境。

### P2（健壮性优化，非硬性违约）

11. ~~**导出加 max-rows 上限保护**~~ → **撤销**：复核确认 `StatisticsServiceImpl` 已实现（上限默认 10000，见 §4.1）。
12. **M7 平均分改 SQL 子查询**：`UPDATE companion_profile SET score=(SELECT AVG(score) FROM order_review WHERE companion_id=?) WHERE user_id=?`，提升并发原子性（可选，对应 BUG_LIST L2）。
13. ~~**客户端错误误归 500**~~ → ✅ **已修**（见 §4.5）。
14. ~~**导出服务的死代码与 `reset()` 逃逸**~~ → ✅ **已修**（见 §4.6）。

### P3（文档对齐）

15. **`application.yml` 显式声明 `nianglin.export.max-rows`**：当前靠代码默认值 `10000` 生效，显式化便于运维与文档对齐（对应 BUG_LIST L3）。
16. **更新工作记忆** → ✅ 已完成（13 commit + M12 收尾状态）。
17. **提高 `Task(定时)` 与 `Controller` 覆盖率**：分别是 4.35% 与 36.09% 行覆盖，是全量覆盖率的两处洼地（见 §7.4）。

---

## 6 · 附录：核验证据

### 6.1 git 提交历史（13 个 commit）

```
feecbd2 docs(api): 同步 M6 鉴权收紧规则
df1ddef fix(test): e2e_*.py mysql_value 缺 encoding='utf-8'
122b943 fix(M6): 陪诊员读老人计划收紧到 ACTIVE 订单
a753072 chore: ADR 0001 回填 + benchmark 脚本与 JMeter 测试计划
a61c00e docs(api): 同步 08-admin/09-statistics-export；test: 补充单测与 e2e
5afb18a feat(M5-M9): 打卡执行/评价投诉/站内信/管理后台/统计导出 模块落地
c883c52 feat(M2): 安全配置与 JWT 过滤收紧，订单服务补齐鉴权
666f108 feat(M3): 陪诊订单模块首版落地
f3b1416 fix(security): code-review fixes for timing attack, JWT ver, logout, concurrency
d17056a feat(M3): 用户档案与陪诊员资质落地
f464fcd chore(M2): 错误码、安全配置与 Jackson 注释微调
0199770 feat(M2): 认证模块首版落地
6bc37c5 chore(init): 项目骨架初始化
```

### 6.2 文件统计

- 后端 Java 源码：约 190 个文件（controller 11 个模块 / service 14 个 / entity 20 个 / mapper 含 ReadMapper / dto / vo / security / config / util / task / websocket）
- 测试 Java：**34 个**（Service 单测 12 + 越权矩阵 9 + Hub/拦截器/异常 5 + 工具/常量/common 5 + 配置 1 + support 2），**511 项测试全绿**
- e2e 脚本：9 个（auth/user_profile/order/execution/medication/review/message/admin/statistics）+ 3 个 bench + 1 个 jmeter_fixture
- JMeter：**4 个 .jmx**（M4 抢单 / M5 重连补齐 / M6 并发确认 / M10 统计），其中 M4/M5/M6 已实际运行过
- SQL：V1–V3 三套（init/seed/boundary）+ 2 个 tools 校验脚本
- 接口文档：9 份（01-auth ~ 09-statistics）+ README
- ADR：3 份（0001/0002/0003 ACCEPTED）
- 数据库文档：4 份（ER图/数据字典/索引设计/种子数据）
- 设计评审：7 份（`docs/agents/designs/M4–M10`）
- 测试报告：4 份（3 份迭代报告 + 1 份压测记录）

### 6.3 关键合规点核验记录

| 红线 | 核验命令 | 命中文件 | 结论 |
|---|---|---|---|
| 药品字典必返 disclaimer | grep disclaimer | MedicineDict / MedicationController / MedicineVO | ✅ 三处落地 + 兜底文案 |
| 密码 BCrypt | SecurityConfig.passwordEncoder | BCryptPasswordEncoder bean | ✅ |
| 不做诊断/处方 | ComplianceCheckUtil.firstHit | OrderServiceImpl.complete 调用 | ✅ 命中即拒并回显 |
| 封禁黑名单 | JwtAuthenticationFilter.isBanned 前置 | disableUser + isBanned | ✅ |
| 乐观锁防超卖 | CompanionOrder @Version + updateById | OrderServiceImpl.accept | ✅ |
| SSE 断开不刷假 ERROR | MessageSseHub.push 不调 complete() | + SecurityConfig 放行 ASYNC/ERROR + GlobalExceptionHandler isCommitted | ✅ 三处联动 |
| 导出上限保护 | grep `EXPORT_LIMIT_EXCEEDED` | StatisticsServiceImpl L91-92 + L396-411 | ✅ 默认 10000 行，超限抛 9001（初版误报已更正） |

---

## 7 · M12 收尾记录（2026-09-16）

> 本节记录 §5 P0/P1 清零的**实测证据**，不是计划。所有数字来自
> `mvn -B verify` 的 surefire 汇总与 `target/site/jacoco/jacoco.csv`。

### 7.1 测试总量

| 项 | 数值 |
|---|---|
| 测试方法总数 | **511** |
| 失败 / 错误 / 跳过 | **0 / 0 / 0** |
| 构建结果 | `BUILD SUCCESS`（`verify` 阶段，含 JaCoCo 门禁） |
| 耗时 | 35.7 s |

### 7.2 覆盖率（JaCoCo 实测，163 个被统计类）

| 分类 | 类数 | 指令% | 分支% | **行%** | 方法% |
|---|---|---|---|---|---|
| **Service 实现层**（门禁对象） | 25 | 60.81% | 48.45% | **63.26%** | 74.73% |
| Service 接口 / 支撑 | 1 | 85.49% | 60.71% | 76.92% | 80.00% |
| Security | 12 | 66.12% | 47.06% | 65.84% | 70.00% |
| WebSocket | 4 | 46.61% | 38.37% | 43.52% | 53.33% |
| Controller | 14 | 32.92% | 25.00% | 36.09% | 40.40% |
| Exception | 2 | 67.24% | 41.67% | 57.89% | 62.96% |
| Task（定时任务） | 1 | 4.55% | 0.00% | **4.35%** | 33.33% |
| 其它（common/entity/dto/vo/config/util） | 104 | 71.65% | 47.53% | 70.41% | 76.47% |
| **合计** | **163** | **62.78%** | **47.57%** | **63.66%** | **69.54%** |

### 7.3 压测实测（真实跑过，不是「脚本已归档」）

| 计划 | 并发 | 样本 | 吞吐 | Err | 结论 |
|---|---|---|---|---|---|
| `jmeter_m5_reconnect.jmx` | 50 × 3 轮 | 300 | 447.8 req/s | **0.00%** | 重连补齐风暴下 `code` 全为 200 |
| `jmeter_m6_concurrent_confirm.jmx` | 50 + 20×5 | 150 | 309~363 req/s | **0.00%** | 同一任务 50 并发：**恰好 1 次生效**，其余全为 5003，零 500 |

完整记录（含夹具快照/还原、复现步骤、未覆盖范围）见 `docs/agents/reports/pressure-test-m5-m6.md`。

### 7.4 仍然偏薄的地方（如实标注）

1. **Service 行覆盖 63.26% 只比门禁高 3.26pp**。门禁是 60%，余量很薄 ——
   再动 Service 代码（尤其加分支）时很容易滑破门禁。要么把门禁降到 55% 并写明理由，
   要么继续补测。
2. **`Task(定时)` 行覆盖 4.35%**：`MedicationScheduler` / `MissedDoseScanner` 的
   触发逻辑（Redis 锁、异常自吞、批次上限）几乎没有测试覆盖。这两个类是「静默失败」的重灾区
   —— 任务没跑不会有人知道，只会在第二天的漏服率上体现。
3. **`Controller` 行覆盖 36.09%**：注解与参数绑定基本只靠越权矩阵顺带覆盖，
   正向成功路径覆盖少。优先级低于 1、2。
4. **WS 握手本身的压测**：需要 JMeter WebSocket Samplers 插件，本机未装，未做。

---

> **核心判断（更新）**：M0–M12 的**代码与测试交付已闭环** —— 511 测试全绿、覆盖率门禁达标、
> 9 个越权矩阵覆盖全部业务模块、4 个压测计划中 3 个真实跑过且零错误。
> 本阶段唯一未闭环的是 BUG_LIST L1（e2e 残留数据，不涉线上功能）。
> 下一步若继续投入，按收益排序：① 补 `Task(定时)` 与 Service 分支覆盖（7.4 第 1、2 条）；
> ② 清 L1 残留数据；③ BUG_LIST L2/L3 的可选优化。
