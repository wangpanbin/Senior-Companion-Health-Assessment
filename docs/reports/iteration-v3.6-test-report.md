# V3.6 数据闭环 · 测试报告

| 项 | 值 |
|---|---|
| 版本 | V3.6 数据闭环（端点↔指标机器面 / 供给缺口分析 / 干预效果对比） |
| 日期 | 2026-10-20 |
| 分支 | `feature/v3.6-data-loop`（从 `feature/v3.5-operations-cockpit` @ `260301d` 切出） |
| 上游契约 | `docs/spec/V3.6-data-loop-spec.md` §五 测试矩阵 · `docs/spec/V3.6-tickets.md` T1–T9 |
| 设计决策 | [ADR-0017](../adr/0017-metric-endpoint-machine-contract.md) |

---

## 一、执行摘要

| 门禁 | 结果 | 变化 |
|---|---|---|
| 后端 `mvn -B -o clean verify` | ✅ **1230 / 1230** | 基线 1163 → **+67** |
| 前端 `pnpm run lint:check` | ✅ **0 警告** | `--max-warnings 0` |
| 前端 `pnpm run test` | ✅ **210 / 210** | 基线 191 → **+19** |
| 前端 `pnpm run build` | ✅ | 生产构建成功 |
| Playwright 全量套件（01–20） | ✅ **243 / 243** | 基线 237 → **+6** |
| 指标字典人读面 ↔ 机器面 | ✅ **64 条逐条一致** | 人读面 §2.7 改为**从机器面生成** |

---

## 二、本版最重要的两件事

### 1. 口径登记从「人读的表」升级成「机器校验的面」

| 机制 | 落点 | 校验什么 |
|---|---|---|
| `metrics/endpoints.yml` | 14 个端点 / 86 条「端点-指标」登记 | 哪个端点返回了哪些指标 |
| `MetricEndpointContractTest` | 三条双向校验 | ① yml 里的端点**必须真实存在**（反射扫描所有 `@RestController`）② yml 里的指标**必须在字典里** ③ 字典里每条指标的端点**必须在 yml 里出现** |
| `MetricCalculatorRegistry` | 13 个计算器 / 15 个 key | **同一个 key 只能有一个实现** —— 重复 key **启动失败**（`IllegalStateException`），绝不「后注册覆盖先注册」 |
| `tools/check_metric_dict_parity.py` | 人读面 ↔ 机器面 | 逐条比对指标 key；不一致即非零退出 |

**跨端共用同一份实现**（决策 6.2）：`operations.fulfillment_rate` ≡ `companion.workbench.completion_rate`、
`operations.active_companion_count` ≡ `operations.companion_count` —— 通过接口上的
`default List<String> aliasKeys()` 把别名登记到**同一个计算器对象**，
而不是各写一份公式。

### 2. 供给缺口分析的「供给」到底算谁 —— 一个开工时漏掉的判定

预写 spec §2.1 原文是「`availableCompanions` = 该时段工作状态为 `AVAILABLE` 且资质通过的陪诊员数」，
而开工时细化的 tickets 只写了「按可服务时段分桶」，**把「资质 + 工作状态」这层过滤漏掉了**。

> **这会让缺口被系统性低估**：没通过审核的陪诊员、休息 / 离线的陪诊员**根本接不了单**，
> 把他们算作供给，平台会以为「这个时段有人」，而实际上没人会接 ——
> 而那正是这个指标要回答的问题。

处置：给两条供给取数 SQL 补上 `audit_status = 'APPROVED' AND work_status = 'AVAILABLE'`，
并在真库测试里加一条**反向用例**（`ineligibleCompanionsShouldNotCountAsSupply`）：
任一桶的可服务陪诊员数**不得超过**「有资格接单」的陪诊员总数。

---

## 三、测试矩阵对照（tickets §八）

| 层 | 要求 | 落地 | 结果 |
|---|---|---|---|
| 端点↔指标校验 | 三条双向校验 | `MetricEndpointContractTest`（5 例）+ `OperationsEndpointContractTest`（5 例） | ✅ |
| 计算器注册表 | key 与字典一致；未注册被拒；重复 key 启动失败 | `MetricCalculatorRegistryTest`（9 例） | ✅ |
| 缺口分析 | 分桶边界；同一人多时段**每桶只计 1 次**；空时段为 0 而不是缺行；`gap` 的符号 | `OperationsGapTest`（15 例） | ✅ |
| 效果对比 | 两侧同口径；区间非法 → 9002；未注册指标 → 400；缺省对比区间**等长且紧邻** | `OperationsEffectTest`（15 例） | ✅ |
| 真库一致性 | 缺口表与逐条 Java 统计一致；用药漏服桶与服药日历一致 | `OperationsGapRealDbTest`（5 例）+ `OperationsMetricRealDbTest`（5 例） | ✅ |
| 越权矩阵 | 非 ADMIN 一律 403；未登录 401 | `OperationsAccessMatrixTest`（18 → **26 例**） | ✅ |
| 前端单测 | 缺口表 168 桶、空桶显示 0；`companionsWithoutSlots` 提示；效果对比两期值与 delta；单块失败只降级该块 | `data-loop.test.js`（**19 例**） | ✅ |
| E2E | 驾驶舱页出现两块；缺口表 168 桶；默认区间生效并显示两期值；换指标重发请求；空数据空态；越权被挡 | `20-data-loop.spec.js`（**6 例**） | ✅ |

---

## 四、反向验证（防「假实现」）

| 机制 | 反向验证动作 | 期望 | 实测 |
|---|---|---|---|
| 端点↔指标机器面 | 从 `endpoints.yml` 删掉一个端点 | 契约测试变红 | ✅ 实跑变红 |
| 缺口按**可服务时段**分桶 | 把供给改成「总数」不分桶 | 缺口测试变红 | ✅ 实跑变红（5 条） |
| 效果对比两期**等长** | 把前一期改成不等长 | 效果测试变红 | ✅ 实跑变红（2 条） |
| 供给只算**有资格接单**的 | 去掉 `audit_status` / `work_status` 过滤 | 真库用例 `ineligibleCompanionsShouldNotCountAsSupply` 变红 | ✅ |
| 一个 key 只能有一个实现 | 注册两个同 key 的计算器 | **启动失败**（`IllegalStateException`） | ✅ 两条单测锁住（主 key 撞主 key、别名撞主 key） |
| 未注册指标 | 效果端点传一个没注册的 key | 400（不猜一个算法算出来） | ✅ |
| 桶形状 | 去掉补零 | `categories` / `items` 数量或长度不一致 → 断言变红 | ✅ |
| 指标字典**人读面 ↔ 机器面** | 在人读面加一条机器面没有的指标 | `check_metric_dict_parity.py` 非零退出 | ✅ 本版把 §2.7 改为**从机器面生成**，从结构上消除漂移 |

---

## 五、E2E

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `e2e/specs/20-data-loop.spec.js`（新建） | 6 | 驾驶舱页出现「供给缺口」「效果对比」两块；缺口表渲染 168 桶（`weekday` 1–7 / `hour` 0–23）；效果对比两期**等长且紧邻**、显示 delta；换指标重新请求；空数据走空态；非管理员被挡 |

> 缺口与效果两条 E2E **直连真后端 8080**（非 mock），断言的是后端返回的真实桶结构。
> 只有「空数据走空态」一条用 `page.route` 换成 `data: null` —— 真后端没有能触发前端空态分支的入参。

---

## 六、已知的「诚实披露」（不是缺陷）

| 项 | 说明 |
|---|---|
| `companionsWithoutSlots = 30` | 种子里 `companion_available_slot` 表**是空的**，全部走自由文本回退；而 30 个陪诊员的 `available_time` 写得**刻意不猜**（「工作日全天」），`LegacySlotParser` 解析不出来 → 他们不进任何桶。响应里如实披露了这个数 |
| 缺口可能为**负** | 供给多于待接量时 `gap < 0`，**不截断成 0** —— 富余就是富余 |
| 改派率的分子分母**时间字段不同源** | 分子按 `admin_oper_log.create_time`（操作时间）、分母按 `companion_order.create_time`（下单时间）—— 订单表里没有被改派过的痕迹。已写进字典公式，不是藏着 |
| `operations.companion_count` 与 `operations.active_companion_count` **同值** | 两者公式逐字相同，共用同一计算器（alias）。这是**语义重复的登记**，已在字典里写明；建议后续只留一条 |
| 跨端共用的计算器**没有 companion 维度** | `companion.workbench.completion_rate` 的字典 scope 是「当前陪诊员」，而效果对比端点是**管理端视角**，只能给平台口径值。已在字典 scope 里写明 |

---

## 七、结论

V3.6 把「口径登记」从**靠人记得写**升级成**机器逼你写**：
端点 ↔ 指标的映射是机器可查的机器面，
同一个指标物理上只有一份计算实现，
供给缺口分析能真正回答「哪个时段没人接单」，
干预效果对比能真正回答「这次干预有没有用」。

> ⚠️ 但这一版的**核心产出是机制，不是页面** ——
> 缺口表与效果对比只是机制的第一个使用者。
> 后面每加一个统计端点，都要同时在 `endpoints.yml` 与指标字典里登记，
> 漏了哪一边，测试立刻变红。
