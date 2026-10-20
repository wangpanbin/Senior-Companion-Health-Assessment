# Changelog

本文件记录每个版本的变更。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号对齐 `迭代计划.md` 的 Vx.y 里程碑（不是 Git 标签）。

> 与 `docs/api/README.md §变更记录` 的分工：那边只记**接口契约**的变更（端点、错误码、字段），
> 本文件记**全部**变更，包括内部重构、测试与文档。两者可交叉引用，但本文件不替代接口变更记录。

---

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
