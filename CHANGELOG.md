# Changelog

本文件记录每个版本的变更。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号对齐 `迭代计划.md` 的 Vx.y 里程碑（不是 Git 标签）。

> 与 `docs/api/README.md §变更记录` 的分工：那边只记**接口契约**的变更（端点、错误码、字段），
> 本文件记**全部**变更，包括内部重构、测试与文档。两者可交叉引用，但本文件不替代接口变更记录。

---

## [V3.5] · 2026-10-20 · 平台运营驾驶舱

让管理员**在 30 秒内发现平台异常，并且能直接去处理**。
产品与工程契约见 [spec](docs/spec/V3.5-operations-cockpit-spec.md) /
[tickets](docs/spec/V3.5-tickets.md)。

### Added · 新增

**四个驾驶舱端点**

- `GET /api/admin/operations/overview` —— 平台概览八项指标
- `GET /api/admin/operations/exceptions` —— **异常中心**（四类异常，每类带 `linkUrl`）
- `GET /api/admin/operations/trend` —— 六条趋势（订单 / 陪诊员活跃 / 履约率 / 投诉 / 评价 / 用药任务）
  × 四个区间（今日 / 近 7 天 / 近 30 天 / 自定义）
- `GET /api/admin/operations/health` —— **平台健康度 = 六个客观指标，不给合成分**
- `config/OperationsProperties`（阈值配置化，启动期校验）+ `mapper/OperationsMapper`
  + `service/OperationsService(+Impl)` + 5 个 VO + `controller/admin/OperationsController`

**前端**

- `views/admin/operations.vue` —— 驾驶舱首页（概览 / 异常中心 / 趋势 / 健康度四块**独立三态**）
- 异常卡片的「去处理」**直接用后端下发的 `linkUrl`** 跳转，前端不拼路由
- 组件单测 + 20 例；E2E + 8 例（含「四类异常卡片逐个点开，落地页必须可达」）

### 口径决策

- **异常中心聚合 V3.1 风险中心，不另建一套风险判定**（决策 5.1）——
  `manualRequired` 与 `warning + urgent` 与 `/api/admin/fulfillment/summary` **同源**，
  由 `OperationsExceptionParityTest` 锁死
- **平台健康度是六个并列指标，不合成总分**（决策 5.2）——
  禁止「AI 平台健康指数 96」这类无法解释的数
- **履约率 ≠ 完成率（按时）**（偏差 D-3.5-1）：
  履约率看「最终有没有履约完」，完成率看「完成得及不及时」；
  两个名字一个数同样是口径分裂
- **每张异常卡片的 `linkUrl` 必须可达**（决策 5.4）——
  「看到了但处理不了」是本版最典型的假绿，E2E 逐个点开验证

### 门禁

- mvn -B verify（基线 1101 + 新增）
- pnpm lint:check / vitest 191/191（基线 171，+20）/ build
- playwright 全量（基线 229 + 新增 8）

---

## [V3.4] · 2026-10-20 · 智能调度

让平台**自动找到更合适的陪诊员**，并且每一条推荐都能说出「为什么是他」。
产品与工程契约见 [spec](docs/spec/V3.4-matching-spec.md) /
[tickets](docs/spec/V3.4-tickets.md) /
[ADR-0016](docs/adr/0016-matching-structured-and-explainable.md)。

### Added · 新增

**结构化改造（V7 迁移）**

- `backend/sql/V7__matching_structured.sql`：`companion_profile` 增坐标 + 服务区域码；
  `companion_order` 增服务区域码；新表 `companion_available_slot`（可服务时段）
- backfill：自由文本 → 区域码（8 个区县 / 4 个市，**映射不上的留空**）
- `backend/sql/tools/gen_region_codes.py` + `region_code_mapping.json` —— 映射生成器与校验器
  （`--check` 机械核对「迁移里手写的 CASE」与「生成器产出」逐字一致）

**匹配三件套（纯函数，无 Spring 上下文）**

- `service/support/matching/`：`MatchingRule` / `MatchingScore` / `MatchingCandidate` / `MatchTarget`
  + 五条规则（区域 / 时间 / 距离 / 服务能力 / 评分）+ `LegacySlotParser`（自由文本时段回退）
- `config/MatchingProperties` —— 五个权重配置化，**启动期校验权重之和 = 1**（不等于就启动失败）
- `service/MatchingService(+Impl)` + `mapper/MatchingMapper` —— 过滤链（工作状态 → 资质 → 区域 → 时段 → 档期冲突）

**接口**

- `GET /api/order/{id}/recommend-companions` —— 家属端候选推荐（Top N + 可解释性）
- `GET /api/order/hall?sortBy=` —— 开放 `DISTANCE` / `TIME_MATCH`；`RECOMMEND` 换成五维加权

### Changed · 变更

- `OrderServiceImpl#hall`：从 SQL 三级键改为 **Java 侧五维打分 + 内存分页**
  （响应结构与 `total` 语义**零变更**，变的只有「哪几条排在前面」）
- 指标字典登记 6 条匹配指标（v1.0 → v1.2）
- `docs/api/03-order.md` —— 补排序语义与候选推荐端点
- `docs/adr/0016-*.md` —— 新 ADR：为什么必须先做结构化改造 + 为什么每条推荐都必须可解释
- 前端：大厅加排序选择器；家属端订单详情加候选展示（标注「推荐 ≠ 派单」）

### 口径决策

- **区域码两级**（`<CITY>_<DISTRICT>`）：码相等 = 1.0；同市不同区 = 0.5；跨市 = 0
- **「缺数据」与「不匹配」分开**：没填坐标 / 没登记时段 → 放行 + 降级标注，**不淘汰**
- **`INCOME` 排序仍不开放**：缺真实结算金额口径（决策 D8）
- **推荐 ≠ 派单**：端点不产生任何订单状态变更，接单权始终在陪诊员手里

---

## [V3.3] · 2026-10-20 · 陪诊员工作台

把陪诊员端散落的「接单大厅 / 订单列表 / 订单执行 / 收入 / 评价」收成**一个工作台**，
并把**收入页的前端自算搬回后端**。
产品与工程契约见 [spec](docs/spec/V3.3-companion-workbench-spec.md) /
[tickets](docs/spec/V3.3-tickets.md) /
[指标字典](docs/spec/metric-dictionary.md#24-陪诊员工作台v33)。

### Added · 新增

**陪诊员自助统计（T2–T3）**

- `GET /api/statistics/companion/me` —— 区间经营概览（完成单数 / 服务时长 / 完成率 / 平均评分 / 区间收入）
- `GET /api/statistics/companion/me/trend` —— 趋势（维度 `ORDER` / `INCOME` / `RATING` × 粒度 `DAY` / `WEEK` / `MONTH`）
- `GET /api/statistics/companion/me/profile` —— 服务画像（**累计口径**：累计完成单数 / 服务老人数 / 准时率 / 累计完成率 / 累计服务时长 / 擅长服务标签）
- `GET /api/statistics/companion/me/income` —— 收入中心（订单金额 / 实际收入 / 待结算 / 已结算 / 代垫合计 / 服务费合计）
- `controller/statistics/CompanionStatisticsController` —— **独立 Controller 类**（类级 `@PreAuthorize("hasRole('COMPANION')")`）；
  不挂在 `StatisticsController`（类级 ADMIN）下面，避免「靠方法级注解覆盖类级注解」这种漏一个就泄露的写法
- `mapper/CompanionStatisticsMapper` + `service/CompanionStatisticsService(+Impl)` + 5 个 VO + `dto/CompanionTrendQuery`
- `constant/CompanionTrendDimension` —— 趋势维度枚举（与 `CompanionRankMetric` 同范式）
- **四个端点都不接收 `companionId` 入参**：陪诊员身份从登录态取，
  请求里带 `?companionId=别人的ID` 会被完全忽略（不是靠 if 挡住，是压根没有读它的地方）

**指标字典登记（T4）**

- 登记 **18 条** V3.3 指标（区间 11 条 + 累计 7 条）到 `metrics/metric-dictionary.yml`（机器面）
  与 `docs/spec/metric-dictionary.md`（人读面，v1.0 → v1.1）
- `MetricDictionaryTest` 扩展 3 条断言：覆盖 V3.3 的六个 VO / 四个端点都登记过 /
  同一指标的多个出口共用一条公式

**前端（T6–T9）**

- `views/companion/workbench.vue` —— **陪诊员工作台首页**（经营指标 / 趋势图 / 服务画像 / 收入摘要四块）
  - 四块用 `Promise.allSettled` 并行拉取，**单块失败只降级该块**，不拖垮整页
  - 趋势图的配色**运行时读 `--nl-*` 设计令牌**（canvas 不解析 CSS 变量，JS 里不留硬编码色值）
- `views/companion/income.vue` —— **去 `reduce()` 化**：KPI 改吃 `/me/income`，
  文案「累计收入」→「区间收入」，页面明示统计区间；账单明细列表保留
- `api/statistics.js` —— 4 个工作台 API 封装
- `router/routes.js` —— 新增 `companion/workbench`（`code: M-32`；`M-22` 已被「我的」占用）
- `__tests__/companion-workbench.test.js` —— **16 例**（静态扫描 6 例 + 组件渲染 10 例），
  含 3 条**扫描器自证用例**（防止静态扫描变成恒过的空转）

### Changed · 变更

- `docs/api/09-statistics-export.md` —— 新增 §8「陪诊员工作台统计（COMPANION）」，
  含请求 / 响应 / 口径表 / 错误码表；接口列表补 4 行
- `docs/api/README.md` —— 访问矩阵把 `/api/statistics/**` 拆成 ADMIN 段与 COMPANION 段两行
- `docs/spec/metric-dictionary.md` —— v1.1：新增 §2.4 陪诊员工作台（区间 11 条 + 累计 7 条）
- `docs/spec/V3.3-tickets.md` —— **新建**：开工复核结论 + 10 张工单

### Fixed · 修复

- **「累计完成单数」不再读 `companion_profile.order_count`**：
  该字段只在建档时置 0（`CompanionServiceImpl`），**运行期没有任何代码维护它**，
  只有种子脚本 `V3__seed_boundary.sql` 手工同步过 —— 读它会得到一个永远是 0 的数。
  改为从 `companion_order` 现算。
  （`companion_profile.score` 不一样：它在评价提交时于事务内刷新，是维护得住的，所以沿用。）
- **评分趋势在周 / 月粒度下按评价条数加权**：日均值不能直接平均成周均值
  （5 条 5 星 + 1 条 1 星 → 日均值的平均是 3.00，真实周均值是 4.33）

### 口径决策 · 开工复核的三处偏差

| # | 偏差 | 理由 |
|---|---|---|
| D-3.3-1 | 「本月」措辞改为「统计区间」 | 决策 3.5 要求沿用 M10 的区间约定（默认 30 天）；「本月」是固定日历月，与可传参的区间不是一回事 |
| D-3.3-2 | 概览（区间）与画像（累计）用**不同字段名** | 两边都有「完成率」，同名就是接口层面的口径分裂。字段名自带口径（`total` 前缀 = 累计） |
| D-3.3-3 | 收入页「累计收入」→「区间收入」 | 决策 3.4 要求两页口径一致、G8 要求前端零自算；旧页的「累计」是拉全量订单 `reduce()` 算的 |

### 验证 · 本版门禁结果

| 检查 | 结果 |
|---|---|
| `mvn -B verify` | ✅ **1032** 条全绿（基线 992 → 新增 40） |
| `pnpm run lint:check` | ✅ 0 error / 0 warning |
| `pnpm run test` | ✅ 16 个文件 / **155** 条全绿（基线 139 → 新增 16） |
| `pnpm run build` | ✅ 生产构建成功 |
| 越权矩阵 | ✅ ELDER / FAMILY / ADMIN 访问 4 端点一律 403；未登录 401；陪诊员本人 200 |
| 跨账号隔离 | ✅ `?companionId=别人` 返回的数据逐字不变 |
| 真库一致性 | ✅ 聚合 SQL 与逐条 Java 统计一致（完成单数 / 时长 / 收入 / 完成率 / 结算拆分） |
| 拒单不入分母 | ✅ 真库验证：种子里 301 拒过 6 单，分母不含它们 |

## [V3.2] · 2026-10-20 · 老人健康协同中心

把「订单 / 陪诊 / 用药 / 消息」四类散落的信息，合成**一位老人一个视图**。
产品与工程契约见 [PRD](docs/prd/PRD-V3.2-老人健康协同中心.md) /
[spec](docs/spec/V3.2-elder-health-spec.md) / [tickets](docs/spec/V3.2-tickets.md)。

### Added · 新增

**指标字典（跨版本地基，T0）**

- `docs/spec/metric-dictionary.md`（人读）+ `backend/src/main/resources/metrics/metric-dictionary.yml`（机器面）
- `util/MetricDictionary` 加载器 + `MetricDictionaryTest`：**字典 ↔ VO 双向校验** —— 字典里写的字段必须真的存在，VO 里的字段必须真的登记过
- `common/DateRange` + `dto/DateRangeQuery`：跨版本共享统计区间（默认 30 天 / 上限 366 天 / 闭区间），**M10 一并改用**
- 首批登记 **12 条**指标（就医 / 陪诊 / 用药 / 待处理）

**用药三桶口径统一（T1）**

- `constant/MedicationBucket`（唯一真源）+ `constant/MedicationBucketSql`（共享 CASE 表达式）
- **修 M10 的历史漂移**：`StatisticsMapper.selectDailyMedicationCounts` 原先只认 `was_missed=1`，
  而漏服扫描只写 `status='MISSED'` 从不写 `was_missed` —— 本机种子里 **179 条真实漏服既不 taken 也不 missed**，
  `taken+missed ≠ total`。现改为「内层算 bucket、外层派生三个计数」，判定全 SQL 只出现一次
- 同步修正 `StatisticsMapper` javadoc 与 `StatisticsController` `@Operation` 里**错误的**「与服药日历一致」宣称
- 详见 [ADR-0014](docs/adr/0014-medication-bucket-single-source.md)

**健康协同五个端点（T2–T5）**

- `GET /api/family/health/summary` —— 家庭健康协同首页摘要（每位老人一栏）
- `GET /api/family/elder/{elderId}/health/overview` —— 某位老人的健康概览（六项客观指标 + 待处理事项）
- `GET /api/family/elder/{elderId}/health/timeline` —— 健康时间线（订单 / 打卡 / 用药 / 提醒四类，游标翻页）
- `GET /api/elder/health/overview` · `GET /api/elder/health/timeline` —— 老人端，**不接受任何 `elderId` 入参**
- 新增 `ElderHealthReadMapper`（聚合只读查询，手写不落生成器产物）+ `ElderHealthService(Impl)` + 4 个 VO
- **零迁移、零新增错误码**（全部复用 2001 / 2006 / 9002 / 400）—— 见 [ADR-0015](docs/adr/0015-elder-health-aggregation-no-new-table.md)

**前端（T6–T8）**

- `api/elderHealth.js`（**不叫 `health.js`** —— 已被 `/health` 健康检查占用）+ `utils/elderHealth.js`（分类 → 视觉的唯一映射表）
- 三页：`views/family/health.vue`（家庭健康首页）· `views/family/elder-health.vue`（某老人健康中心）· `views/elder/health.vue`（老人端）
- 路由 M-29 / M-30 / M-31；入口落在**既有分组**内，底部 TabBar 不加项
- 老人端大字版：56px 主数字、概览只留 4 项、时间线只显示「图标 + 一句话 + 时间」

### Fixed · 修复

- `UploadAccessGuardFilter`（安全加固引入）把**种子给全体用户填的占位头像** `/uploads/avatar/default.png`
  当成证件挡掉 —— 老人端与「我的」页稳定产生一条 403 console error，把 **6 条既有 E2E 打成红的**。
  该路径根本不是上传产物（上传一律写进 `/uploads/{yyyyMM}/{uuid}.{ext}`），收窄守卫范围到落盘布局后
  **证件保护一点没弱化**，6 条 E2E 复绿

**code-review（Standards + Spec 双轴）后的修正**：

- 老人端「今日已服药 / 还有 N 次没吃」原先用的是**统计区间**口径（30 天的数字被说成「今天」）→
  新增 `MedicationTodayVO`（当天口径三桶），概览增 `todayMedication` 字段
- 指标字典的「双向校验」对**未登记的新 VO 失效**（`vos` 段是人工维护的清单）→ 增加第三层
  「可达性」校验：从已登记的 VO 沿字段类型走，凡被接口带出去的 VO 必须登记
- 时间线**订单类未受 200 条上限约束**（200 单能产出 1000 条事件）且响应无截断标记 →
  改按事件条数封顶，新增 `truncated` 标志，前端如实提示「记录较多，仅显示最近部分」
- 三页的内容型文字用 `--nl-text-3`（白底 2.62:1，低于 4.5:1）与 caption / micro 档
  （老人模式 15px / 12.5px，低于 18px 基线）→ 分别提到 `--nl-text-2` 与 `--nl-font-body`
- 上传落盘布局被写了两遍（落盘服务与守卫各一份）→ 新增 `constant/UploadPathLayout` 作单一真源
- 新增测试的方法名不符合 `methodNameShouldXxx` → 15 处订正
- **E2E 条数订正**：本版新增实为 **13 条**（此前写的 20 条把 `auth.setup` 的 7 条登录用例也算进去了）

### Changed · 变更

- `StatisticsQuery` 改为继承 `DateRangeQuery`（区间判定只写一份）；`DateRangeQuery` **不用链式 setter**
  —— 链式会让继承来的 setter 返回父类型，既有调用点签名会变
- `MedicationCalendarVO.of` 改为调用 `MedicationBucket.of(...)`（**行为不变**，它本来就是对的）

### 文档

- ADR-0014 用药三桶唯一真源 · ADR-0015 老人健康聚合零迁移
- `docs/prd/PRD-V3.2-老人健康协同中心.md`（独立版本 PRD，遵守 ADR-0006 版本锁，不动 `PRD.md`）
- `docs/spec/V3.2-elder-health-spec.md` · `docs/spec/V3.2-tickets.md` · `docs/spec/metric-dictionary.md`
- API 契约：新增 `docs/api/12-elder-health.md`，更新 `README.md` 目录与 2xxx 段说明 · `05-medication.md` 口径说明
- `docs/spec/V3.x-roadmap-spec.md` 回写 V3.2 落地后的偏差

### 测试

- 后端新增 **86 条**用例（902 → 988，全量 **988 / 988** 全绿）
- 前端新增 **17 条**单元测试（122 → 139，全量 **139 / 139** 全绿）
- Playwright：新增 **13 条**（`e2e/specs/16-elder-health.spec.js`），全量 **210 / 210** 全绿，夹具回滚校验通过
- 反向验证：删掉 `MedicationBucket.of` 的 MISSED 分支 → 真库 parity 测试报 `expected: <576> but was: <342>` 后复绿

### Compliance · 合规

- 健康中心全部文案为**事实陈述**，不出现任何结论式判断；禁词表由前端单测与全仓扫描共用
- 老人姓名脱敏；老人端不返回费用 / 催单 / 履约风险 / 家属姓名
- **订单聊天内容不进健康时间线**（时间线只读 `internal_message`，不读 `chat_message`）

---

## [V3.1] · 2026-10-17 · 履约保障中心

把「下了单一直没人接」这段空白期变成**有反馈、有节奏、有人兜底**的时间。
产品与工程契约见 [PRD](docs/prd/PRD-V3.1-履约保障中心.md) /
[spec](docs/spec/V3.1-fulfillment-spec.md) / [tickets](docs/spec/V3.1-tickets.md)。

### Added · 新增

**陪诊员接单状态自助控制（T0）**

- `WorkStatus` 增加 `OFFLINE`，与 `REST` 区分「主动暂停」与「被动离线」
- `PUT /api/user/companion/work-status`：只写 `work_status` 一列，**不触碰在手订单**
- 陪诊员大厅新增接单状态开关（移动 + 桌面两个形态）

**履约风险模型与扫描（T1 / T2）**

- `companion_order` 增三列：`fulfillment_risk` / `urge_count` / `last_urge_at`（`V6__order_fulfillment.sql`，`information_schema` 守卫幂等）
- 新表 `order_fulfillment_event`：履约事件流水，只增不改
- 索引 `idx_risk_status_time (fulfillment_risk, status, create_time)`
- `FulfillmentRisk` / `FulfillmentEventType` / `TriggerSource` 枚举
- `FulfillmentRiskEvaluator`：**唯一测试接缝**，纯函数、无 Spring、无时钟依赖
- `FulfillmentProperties`：阈值配置化（`nianglin.fulfillment.*`），启动期校验严格递增
- `FulfillmentScheduler`：5 分钟一次，Redis 锁 TTL 4min，异常自接住

**家属催单（T3）**

- `POST /api/order/{id}/urge`：冷却 10 分钟、累计上限 3 次（均配置化）
- 资格判定与计数自增**合并为同一条条件更新**，不做「先查后写」

**履约读口径（T4 / T5）**

- `OrderVO` 五个只读字段：`fulfillmentRisk` / `fulfillmentRiskLabel` / `waitMinutes` / `urgeCount` / `canUrge`
- `GET /api/order/{id}/fulfillment`：风险等级 + 事件流水（升序）
- `TimeUtil.elapsedMinutes`：等待时长的**唯一钳制规则**，扫描 / 出参 / 详情三方共用
- 家属端风险状态条 + 订单列表轻量标记
- `frontend/src/utils/fulfillment.js`：等级 → 视觉的**唯一映射表**

**管理员风险中心与改派（T6 / T7）**

- `GET /api/admin/fulfillment/summary`：四档计数，恒定全返，为 0 时返回 0
- `GET /api/admin/fulfillment/risk-list`：固定「等级降序 → 等待时长降序 → id 降序」，姓名与订单号脱敏
- `POST /api/admin/order/{id}/reassign`：仅处理未接单订单，**不改变 `status`**
- 管理端新页 `履约风险`（W-10）：四档计数卡 + 等级筛选 + 列表 + 详情 / 改派弹窗
- `OperType.REASSIGN_ORDER` 操作审计类型

**大厅排序（T8）**

- `GET /api/order/hall` 新增可选参数 `sortBy`，白名单只放行 `RECOMMEND`，非法值 `400` 不静默降级
- 推荐序为三级稳定排序：`visit_time ASC` → 区域匹配优先 → `id ASC`

### Changed · 变更

- `MessageTemplateUtil`：新增 `ORDER_REASSIGNED` 模板（三类接收方靠 `audience` 占位符区分措辞）
- `backend/sql/tools/gen_entity.py`：新增 `parse_alters()`，修复增量列被静默丢弃的问题（顺手修复，超出本版范围但已验证）
- `OrderVO.ofList` / `ofHall` / `ofDetail` 三套口径共用 `base()` 装配，非 `PENDING` 时履约字段统一收敛

### Fixed · 修复

- `AdminArbitrateTest` 中一处既有损坏字符

### 文档

- ADR-0012 履约风险与订单状态分离 · ADR-0013 大厅单一推荐序
- `docs/prd/PRD-V3.1-履约保障中心.md`（独立版本 PRD，遵守 ADR-0006 版本锁，不动 `PRD.md`）
- API 契约：`02-elder-family.md` §13 · `03-order.md` §11/§12/§3 · `08-admin.md` §14–§16 · `07-message.md` 类型表补全 · `README.md` 错误码 `3008`/`3009`/`3010`
- 数据字典：`companion_order` 三列 + `order_fulfillment_event` 表

### 测试

- 后端新增 **210 条**用例（680 → 890，全量 890 / 890 全绿）
- 前端新增 **22 条**单元测试（67 → 89，全量 89 / 89 全绿）
- Playwright：基线 176 条零修改通过 + 新增 16 条（2e/specs/15-fulfillment.spec.js），合计 192 条全绿
- code-review（Standards + Spec 双轴）：契约偏差已修（离开 PENDING 的库层风险回落、SQL 侧催单自增、工作状态越权矩阵）
- 反向验证：改坏阈值 → 2 条测试变红；删除状态机转移边 → 3 条测试变红，还原后复绿

### Compliance · 合规

- 催单备注与改派理由过 `ComplianceCheckUtil` 词表（与服务小结 `3007` 同一份，命中返回 `6003`）
- 新增文案无「建议服用 / 剂量 / 诊断」类表述
- 风险列表姓名与订单号脱敏；详情的全名豁免仅对已过归属校验的详情生效
