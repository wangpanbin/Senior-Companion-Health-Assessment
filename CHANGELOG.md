# Changelog

本文件记录每个版本的变更。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号对齐 `迭代计划.md` 的 Vx.y 里程碑（不是 Git 标签）。

> 与 `docs/api/README.md §变更记录` 的分工：那边只记**接口契约**的变更（端点、错误码、字段），
> 本文件记**全部**变更，包括内部重构、测试与文档。两者可交叉引用，但本文件不替代接口变更记录。

---

## [V3.7] · 2026-10-21 · 家属智能助手

让家属**用自然语言**问到平台规则，并看懂「为什么推荐这位陪诊员」。
产品与工程契约见 [PRD](docs/prd/PRD-V3.7-家属智能助手.md) /
[spec](docs/spec/V3.7-assistant-spec.md) /
[ADR-0020](docs/adr/0020-assistant-boundary-and-recommendation-guardrail.md)。

### Added · 新增

**独立 `ai-service`（Python 3.12 + FastAPI + LangChain）**

- `ai-service/app/` —— FastAPI 应用（`main` / `intents` / `knowledge` / `guard` / `llm` / `prompts` / `actions` / `service`）
- 端口 **8081**，**只监听 `127.0.0.1`**；浏览器只与 `/api` 说话
- **不配置 LLM 也能跑**：未配置 `LLM_BASE_URL` 时降级为**基础模式**，回答直接给 FAQ 标准答案原文（`degraded=true`）
- 决策链**写死在代码里**（输入护栏 → 意图分类 → FAQ 检索 → 事实装配 → 叙述 → 输出护栏），**不使用自主 Agent**

**后端门面**

- `POST /api/assistant/chat` —— **仅 `FAMILY`**（ELDER / COMPANION / ADMIN → 403）
- 新开 **10xxx** 错误码段：`10001` 提问过于频繁 / `10002` 智能助手暂时不可用 / `10003` 问题内容包含违规表述
- `config/AssistantProperties` —— 配置化 + **启动期校验**（配错即启动失败）

**共享合规词表抽出**

- `backend/src/main/resources/compliance/medical-advice-words.txt` —— 禁用词从 `ComplianceCheckUtil` 硬编码抽出为**共享资源文件**
- Java 与 Python **读同一份**，两侧都**启动期 fail-fast**（读不到 / 词数过少 → 启动失败）
- **命中即拒，不打码后放行**

**FAQ 知识库**

- `ai-service/knowledge/faq.json` —— **30 条**，每条带 `source` + `anchor` 指向 `docs/api` 真实锚点
- `tools/check_faq_anchors.py` —— 校验条目格式、锚点是否仍存在、答案是否含禁用词（**FAQ 过期会静默失真，这道闸门是硬失败**）

**前端**

- `views/family/assistant.vue` + `api/assistant.js` —— 家属端 `/family/assistant` 页面
- 路由 `module: M14` / `code: M-33` / `elderlyHidden: true`
- 助手**只填下单草稿**，**永不自动建单**；推荐叙述**只转述** `reasons` / `mismatches` / `notes` 三段

**CI**

- `.github/workflows/ci.yml` 新增 `ai-service` job（`setup-python` + pip 缓存 + `pytest tests -q`）

### Fixed · 评审收口（2026-10-21 第二轮）

> 详见 [评审报告](docs/review/2026-10-21-v3.7-assistant.md)。两轴共 **9 项确认缺陷**，全部已修并被测试锁住。

- ★ **LLM 路径从未真正跑过** —— `app/llm.py` 从 `langchain.prompts` 导入，而 **LangChain 1.x 已移除该模块**
  （模板类搬到 `langchain_core.prompts`），导入失败被兜底逻辑吞成 `NullProvider`：
  模型配得好好的，回答却永远是 FAQ 原文，且**所有测试都绿、所有日志都说「已启用」**
- **`UNKNOWN` 不经大模型** —— 此前模型会拿着「没命中任何 FAQ」的事实**硬编一个答案**
  （实测编出五步下单流程，且 `sources` 为空）。改为固定澄清话术
- **推荐无候选不走模型** —— 此前模型会生成「**命中理由** - 现有事实中未提供具体陪诊员的画像…」这种三段空壳
- **输出护栏新增距离措辞拦截** —— 「离得近 / 就在附近 / 距离近」→ 纠正为「距离维度无法计算（陪诊员尚未填写坐标）」。
  该纪律原先只写在 prompt 里，**代码层零兜底**；而把「算不出来」说成「很近」会让**正在挑陪诊员的家属**据此做决定
- **草稿只在真的抽到就诊信息时产出** —— `elderId` 是会话上下文自动补的，不算「抽到」
- **草稿不再凭空补 `09:00:00`** —— 只找到日期没找到时刻时留空，让家属在确认页自己补
- **回答统一去除 Markdown 标记** —— 前端是纯文本渲染，不解析 Markdown
- **Java 本地兜底路径同样过派单措辞护栏** —— 此前只做医疗词表整段替换，契约要求的护栏只兑现了一半
- **限流提示显示后端给的真实原因** —— 统一显示「助手暂时没有响应」会让用户以为服务挂了
- **`check_faq_anchors.py` 改为读共享合规词表** —— 原先内嵌一份副本，违反「不要另抄一份词表」；
  且真源加词时这道门禁**不会红**

### Added · FAQ 关键词覆盖

- `ai-service/knowledge/faq-question-pool.json` —— **179 条**模拟问法（30 条 FAQ × 6 条，剔除 1 条医疗意图问法）
- `tools/gen_faq_questions.py` —— 生成与回填工具（**默认不联网、不写文件**）
- `ai-service/tests/test_faq_coverage.py` —— 覆盖门禁（召回 / 不抢占 / 无医疗意图 / 关键词上限 / 易混对）
- 关键词回填后，问法池召回率 **34.4% → 97.2%**，抢占 **0**
- ⚠️ 问法是**大模型生成的模拟问法，不是真实用户数据** ——
  PRD 风险表第 5 条「FAQ 覆盖面未经真实用户验证」**保持未关闭**，
  只能靠上线后的未命中问题回流关闭

---

## [V3.3 – V3.6 评审收口] · 2026-10-21 · 代码评审发现项修复

对 `c735693..de994b3`（V3.3 – V3.6 四版）做 Standards / Spec 两轴评审后的收口。
**零新增错误码**，全部复用既有段位。

### Fixed · 评审发现项

**匹配可解释性拆三段**（`CompanionMatchVO` 新增 `mismatches`）

- `MatchingScore#reasons()` 收窄为「**得分大于 0** 的命中理由」——
  原来 0 分且非降级的维度（「跨城市」「超出服务半径」「未服务过该科室」）
  会被当成「推荐理由」渲染给家属，**淘汰理由被说成了推荐理由**
- 新增 `MatchingScore#mismatches()` 承载「该维度得 0 分的原因」，
  `CompanionMatchVO.mismatches` + 前端 `order-detail.vue` 分行渲染
- `notes`（降级说明，因数据缺失没算出来的维度）语义**不变** ——
  「没法算」与「没命中」这两件不同的事仍然是分开的

**指标字典口径精度**

- `operations.gap.available_companion_count` / `operations.gap.companions_without_slots`
  的「统计范围」措辞与实现对齐：universe 明确为**资质通过（APPROVED）且当前在线（AVAILABLE）**
  的陪诊员（`OperationsMapper` 的过滤本就是有意为之：休息 / 离线的人不接新单）
- **口径值未变**（`caliberVersion` 保持 1）：原措辞「全部陪诊员中」比实现**宽**，
  是文档写得比实现松，不是实现算错了
- 人读面 §2.7 表格由 `tools/gen_metric_dict_human_v36.py` 重新生成，两面逐条一致

**其它**

- 大厅 `total` 改为截断**前**的真实总数（`paginate` 增加 `trueTotal` 参数）
- `recommendCompanions` 的归属校验与条数归一化从 Controller 下沉到 Service（G4）
- `MatchingServiceImpl` 的 NPE 守卫移到解引用**之前**
- `OperationsMapper.todayCount` 改用独立子查询（不再被区间 `WHERE` 套住）
- 履约率 / 完成率(按时) / 投诉率 / 平均评分 / 平均接单时间收敛到 `MetricFormulas`
  **唯一一份实现**（ADR-0017 决策 2）

### Added · 评审补漏

- **`GET /api/admin/metrics`** —— 指标字典只读导出（仅 ADMIN）。
  兑现 `V3.6-data-loop-spec.md` §二 的合同级预写（此前 spec 有、实现无、tickets 无删减留痕）。
  导出的是**口径定义**而非统计值，故不进 `metrics/endpoints.yml`
- `tools/_metric_dict_reader.py` —— 指标字典机器面的**零依赖**读取器（CI 不承诺装 PyYAML）
- `tools/fix_line_endings.py` —— 本地辅助脚本：检查 / 统一工作区行尾为 CRLF
  （Windows 上 `core.autocrlf=true`，用编辑器新建的文件默认写成 LF）。
  **本地工具，不是 CI 门禁**
- `tools/check_metric_dict_parity.py` 升级为**字段级**矛盾检测（原来只比 key 集合）
- `.github/workflows/ci.yml` 新增 `metric-dict` job；`secrets` job 改为调 `tools/check_secrets.py`
  （**单一真源**，不在 workflow 里抄第二份规则）
- **ADR-0018**（V3.3 陪诊员工作台：口径必须自带区间、字段名必须自带口径）
- **ADR-0019**（V3.5 平台运营驾驶舱：不给合成分、异常必须复用而不是另建）
  —— 补齐 roadmap G9「每版收口同步六处文档」里的 ADR 项

### Docs · 文档收口

- `docs/api/08-admin.md` §18 补「请求参数」小节（异常中心实际接收 `DateRangeQuery`）；
  §17 `todayOrderCount` 补「不随统计区间浮动」；新增 §24（指标字典导出）
- `docs/api/03-order.md` §13 补 `mismatches` 字段与三段语义说明；
  修正 v1.2.0 变更行误置于文件首行（标题之前）的排版缺陷
- `docs/spec/metric-dictionary.md` v1.4 → v1.5
- **V3.1 / V3.3 / V3.4 / V3.5 / V3.6 五份 spec 的状态头勘误** ——
  此前仍写着「待评审 / 未开工」，而四版早已交付
- `docs/db/*` 按真实库**重新生成**（25 表，字段数 / 索引数逐表零差异）
- `docs/agents/FRONTEND_CONTRACT.md`：`/admin/*` 9 页 → **11 页**；
  运营端 6 个端点从 `@/api/statistics` 迁到 `@/api/admin`（AGENTS.md §3.10）

### 门禁

- mvn -B verify（基线 1230 条测试 + 本轮新增）
- pnpm lint:check / vitest / build
- `python tools/check_secrets.py` · `python tools/check_metric_dict_parity.py`

---

## [V3.6] · 2026-10-20 · 数据闭环

让平台**通过数据持续提高服务质量**，而不是「统计完了就放着」。
产品与工程契约见 [PRD](docs/prd/PRD-V3.6-数据闭环.md) /
[spec](docs/spec/V3.6-data-loop-spec.md) /
[tickets](docs/spec/V3.6-tickets.md) /
[ADR-0017](docs/adr/0017-metric-endpoint-machine-contract.md)。

### Added · 新增

**端点 ↔ 指标机器面**

- `backend/src/main/resources/metrics/endpoints.yml` —— 声明「哪个端点返回了哪些指标」
- `util/MetricEndpointRegistry` —— 加载器
- `MetricEndpointContractTest` —— **三条双向校验**：
  ① yml 里的端点必须真实存在（反射扫描所有 `@RestController`）
  ② yml 里的指标必须在指标字典里
  ③ 指标字典里每条指标的端点必须在 yml 里出现
- `tools/check_metric_dict_parity.py` —— 指标字典**人读面 ↔ 机器面**逐条一致性校验

**指标计算器注册表**

- `service/support/metrics/MetricCalculator` + `MetricCalculatorRegistry`
  —— **同一个指标 key 只能有一个实现**；未注册的指标在效果对比里返回 400

**两个新端点**

- `GET /api/admin/operations/gap` —— 供给时段缺口分析（「周内第几天 × 小时」分桶）
- `GET /api/admin/operations/effect` —— 干预效果对比（同一指标两期值 + 差值）

### 口径决策

- **桶的粒度固定为「周内第几天 × 小时」**，与区间长度无关 ——
  只有这样「这周和上周的同一时段」才可比（迭代计划 §47 的闭环数据基础）
- **供给数按「可服务时段」分桶**，不是按「当前 AVAILABLE 总人数」——
  后者每个桶都一样，指标没有信息量；
  没能解析时段的陪诊员不进任何桶，并由 `companionsWithoutSlots` **如实披露**
- **效果对比两侧走同一个计算器** —— 用两个公式对比，比出来的是公式差异
- **同一指标跨端共用一份实现**（决策 6.2）—— 物理上只有一份，就不可能漂移

### 门禁

- mvn -B verify（基线 1162 + 新增）
- pnpm lint:check / vitest / build
- playwright 全量

---

## [V3.5] · 2026-10-20 · 平台运营驾驶舱

让管理员**在 30 秒内发现平台异常，并且能直接去处理**。
产品与工程契约见 [PRD](docs/prd/PRD-V3.5-平台运营驾驶舱.md) /
[spec](docs/spec/V3.5-operations-cockpit-spec.md) /
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
产品与工程契约见 [PRD](docs/prd/PRD-V3.4-智能调度.md) /
[spec](docs/spec/V3.4-matching-spec.md) /
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
产品与工程契约见 [PRD](docs/prd/PRD-V3.3-陪诊员工作台.md) /
[spec](docs/spec/V3.3-companion-workbench-spec.md) /
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
