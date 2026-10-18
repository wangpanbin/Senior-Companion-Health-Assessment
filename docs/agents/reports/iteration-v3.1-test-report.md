# V3.1 履约保障 · 测试报告

| 项 | 值 |
|---|---|
| 版本 | V3.1 履约保障中心（工作状态 / 履约风险 / 催单 / 管理员兜底 / 大厅排序） |
| 日期 | 2026-10-17 |
| 分支 | `feature/v3.1-fulfillment` |
| 上游契约 | `docs/spec/V3.1-fulfillment-spec.md` §七 测试矩阵 · `docs/spec/V3.1-tickets.md` T9 |
| 本报告范围 | V3.1 新增行为 + 全量回归；**不含** M0–M13 既有功能的功能性复测（由全量套件覆盖） |

---

## 一、执行摘要

| 门禁 | 结果 | 说明 |
|---|:--:|---|
| 后端 `mvn -B verify` | ✅ **890 / 890** | 含编译、单测、打包；V3.1 新增 **163** 条 |
| 前端 `pnpm run lint:check` | ✅ **0 警告** | `--max-warnings 0` |
| 前端 `pnpm run test` | ✅ **89 / 89** | V3.1 新增 **22** 条 |
| 前端 `pnpm run build` | ✅ | 533 modules |
| Playwright 全量套件（01–15） | ✅ **192 / 192** | 基线 176 条零修改通过 + V3.1 新增 16 条；含写路径夹具回滚校验 |
| Playwright V3.1 新增（15） | ✅ **16 / 16** | 新建 `15-fulfillment.spec.js` |
| 反向验证 · 状态机转移边 | ✅ | 删边后 3 条变红，还原后 18 条复绿 |
| 反向验证 · 风险阈值 | ✅ | 改坏 WARNING 阈值后 2 条变红，还原后复绿 |
| code-review（Standards + Spec 双轴） | ✅ | 两条轴各跑一遍；硬违规与契约偏差已修（见 §九） |

**结论**：V3.1 的新增行为与既有 166 条基线用例全部通过，无回归、无跳过、无重试放行。

---

## 二、环境

| 项 | 值 |
|---|---|
| JDK | 21 |
| Maven | 3.9.15（离线 `-o` 模式，依赖已本地化） |
| Node / pnpm | 22.10+ / lockfile 固定 |
| MySQL / Redis | 本机常驻（E2E 夹具自动 snapshot / restore） |
| 浏览器 | 本机 Chrome（Playwright `channel: 'chrome'`，不下载 Chromium） |
| 数据基线 | 每次 E2E 跑前 snapshot、跑后 restore + verify，**19 张表 count / max_id 全部回到基线** |

---

## 三、新增用例分布（后端 154 条）

| 测试类 | 条数 | 保护的性质 |
|---|:--:|---|
| `CompanionWorkStatusTest` | 17 | 三档切换成功；非法字面量 400；资质未过 2003；**切换后在手订单状态不变** |
| `FulfillmentRiskEvaluatorTest` | 26 | 阈值表驱动的边界（`warning-1` / `warning` / `urgent-1` …）；负值防御；**单调性遍历** |
| `FulfillmentPropertiesTest` | 6 | 阈值严格递增的启动期校验 |
| `FulfillmentServiceScanTest` | 10 | 按旧值认领；一次扫描只写一条事件；只升不降；批次上限 |
| `FulfillmentSchedulerTest` | 6 | 抢锁 / 未抢锁；异常自接住；开关关闭时不跑 |
| `FulfillmentServiceUrgeTest` | 16 | 资格 / 频率 / **并发穿透**（条件更新 0 行 → 3009 且不留事件）；合规词表 |
| `OrderVOFulfillmentTest` | 17 | 五个增量字段；**非 `PENDING` 一律收敛**；脏数据防御；既有字段零破坏 |
| `TimeUtilTest` | 16 | 等待时长随「现在」自增；截断而非四舍五入；负值钳 0；溢出钳 `MAX_VALUE` |
| `FulfillmentServiceDetailTest` | 17 | **事件流升序**（打乱桩数据验证）；空事件流返回数组；两类收敛 |
| `OrderServiceFulfillmentDetailTest` | 8 | 履约详情复用 `requireInvolved`；两类无关方 3004；不重复查库 |
| `HallSortTest` | 14 | 白名单拒绝 400（含配置值）；三级稳定排序；档案文本注入防御 |
| `AdminFulfillmentTest` | 17 | 四档计数恒定齐全；脏值并入 normal（四档之和 = 总数）；列表脱敏 |
| `AdminReassignTest` | 19 | 七类拒绝路径；**改派不改状态**；审计记前后陪诊员；抢单竞争失败干净失败 |
| `OrderTransitionServiceTest`（+4） | 14 | **离开 PENDING 时风险回落 NORMAL**（接单 / 取消 / 管理员强制），且搭同一条更新落库 |
| `UserAccessMatrixTest`（+3） | 17 | 切接单状态对 FAMILY / ELDER / ADMIN 一律 403；未登录 401；资质未过 2003 |

**前端新增 22 条**（`src/utils/__tests__/fulfillment.test.js`）：等级 → 视觉映射唯一性、
四档文案随后端下发、非待接单不渲染、`canUrge` 完全由后端驱动、列表正常等待不出标记。

---

## 四、Playwright 端到端

### 4.1 基线套件（01–14）：176 条全绿，零修改

> 工单写的基线是 166 条；M13 订单聊天合入后套件已增长到 **176 条**。
> 本次跑的正是这 176 条，**未修改任何既有 spec**。

覆盖：认证 / 越权矩阵 / 档案 / 家属下单 / 陪诊执行 / 用药 / 站内信 / 管理后台 /
统计导出 / 形态自适应 / 实时推送 / 管理端响应式 / 客户端多视口 / 订单聊天。

**夹具回滚校验**（每次跑完自动执行）：19 张表 `count` 与 `max_id` 全部回到基线，
6 张表白名单行逐列一致，`REVIEWED 订单 31 == order_review 31` 成对性成立。

### 4.2 V3.1 新增（15）：16 条全绿

| 分组 | 条数 | 覆盖 |
|---|:--:|---|
| 陪诊员接单状态自助控制 | 2 | 三档切换 + **在手订单状态逐单不变**；非法字面量 400 |
| 履约读口径 | 3 | 列表五个字段齐全 + 既有字段零破坏；事件流升序；两类无关方 3004 |
| 家属催单 | 3 | 非家属角色 403；结果集封闭在 `{200, 3008, 3009}`；合规词表拦截 |
| 管理员风险中心与改派 | 4 | 四计数恒定齐全；列表脱敏 + 非法等级 400；改派 3010 且状态不变；三角色越权 403（**带合法请求体**） |
| 大厅单一推荐序 | 2 | 三个未开放维度均 400；不传 `sortBy` 返回待接单且带履约字段 |
| 页面可达性 | 2 | 家属详情页渲染风险状态条；管理端风险中心四档计数卡渲染 |
| **合计** | **16** | — |

**写操作自清理**：唯一会改状态的用例（工作状态三档切换）在 `finally` 里恢复原值；
催单只走「必然被拒」或「允许成功」的路径，改派只走「必然被拒」的负向路径。

---

## 五、反向验证（证明测试不是假绿）

| 被改坏的东西 | 期望 | 实际 |
|---|---|---|
| `FulfillmentProperties` 的 WARNING 阈值 | 阈值边界用例变红 | ✅ 2 条变红，还原后复绿 |
| `OrderStatus.TRANSITIONS` 删掉 `PENDING → ACCEPTED` | 状态机用例变红 | ✅ 3 条变红（`OrderStatusTest` 1 + `OrderTransitionServiceTest` 2），还原后 18 条复绿 |

两次还原均刷新了文件 mtime —— 否则 Maven 增量编译会跳过重编，跑的还是坏 class（本机踩过一次）。

---

## 六、合规与隐私检查

| 检查项 | 结果 |
|---|---|
| 新增文案无「建议服用 / 推荐剂量 / 诊断为 / 可能是」 | ✅ 全仓扫描为空；催单备注与改派理由均过 `ComplianceCheckUtil` 词表（命中返回 `6003`） |
| 无真实手机号 / 身份证号 / 密码 | ✅ 全仓扫描为空 |
| 风险列表姓名与订单号脱敏 | ✅ `AdminFulfillmentTest` + E2E 双重断言；详情的全名豁免仅对已过归属校验的详情生效 |
| 老人账号写操作仍被拦 | ✅ 越权矩阵含 ELDER 角色，催单 / 改派 / 风险中心均为 403 |
| 适老化未降级 | ✅ 风险表达用「图标 + 文案 + 左侧色条」，未使用纯红 alarming 底色；催单按钮触控高度走 `--nl-touch-min` 令牌 |

---

## 七、遗留与建议

| # | 事项 | 影响 | 建议 |
|---|---|---|---|
| 1 | `last()` + `selectPage` 的排序组合已在真库跑过（`@SpringBootTest` 用例覆盖），但**分页统计 SQL** 的 `ORDER BY` 剥离只在这一次验证 | 低 | 大厅 / 风险列表各加一条「翻到第 2 页」的 E2E |
| 2 | 扫描任务的**真实多实例竞争**未在 E2E 环境复现（单测是 Mockito） | 低 | 有条件时起两个实例压一次，确认 Redis 锁 + 条件更新配合无误 |
| 3 | `change log` 首次建立（`CHANGELOG.md`），此前无版本化变更记录 | 低 | 后续每版同步更新 |
| 4 | 既有 E2E 套件基线从 166 涨到 176，工单里的数字未同步 | 无 | 已在本报告注明 |
| 5 | `FulfillmentEventType.MATCHING_STARTED` 与 `ADMIN_COORDINATED` 目前无生产写入点 | 低 | 枚举值是 spec §3.5 列的八种之一，本期只落了六种；V3.2 若引入「开始匹配」事件再启用 |
| 6 | 前端三档风险色调 SCSS 在四处重复 | 低 | 可抽成 SCSS partial，但四处所属组件分属不同模块，强行抽会引入跨模块耦合 |
| 7 | `AdminReassignTest` 的 `@DisplayName` 仍写「→ 3007」，断言已是 `CONTENT_SENSITIVE(6003)` | 低 | **仅记录未修**：该测试文件不在本轮文档侧改动范围。文档侧（javdoc / 前端注释 / E2E 断言 / 接口文档四处）已按 §八 同步为 6003，仅剩这处 `@DisplayName` 描述滞后，会让读用例名的人以为断言的是 3007 |
| 8 | `tools/scan_bad_chars.py` 的 docstring 用法示例仍写旧文件名 `tools/tmp_scan_bad.py` | 低 | **仅记录未修**：该脚本不在 `docs/` 下、不在本轮改动范围。照着示例敲命令会报「文件不存在」，脚本本体功能正常 |
| 9 | `FulfillmentProperties` 类注释称「本版有 9 个配置键」，实为 **10** 个（多一个 `scan-batch`） | 低 | **仅记录未修**：Java 文件不在本轮改动范围。`docs/spec/V3.1-fulfillment-spec.md` §4.4 的配置表已补齐到 10 项，与字段数对齐 |
| 10 | `nianglin.fulfillment.scan-batch` 当前为 `200`，spec §4.4 按此记录 | 低 | 若后续修复分页插件静默截断导致该配置失效的问题，取值可能调整，改动时需同步回 spec §4.4 |

---

## 八、code-review 处置记录（Standards + Spec 双轴）

| 轴 | 发现 | 处置 |
|---|---|---|
| Spec | spec §5.3 要求「离开 `PENDING` 时 `fulfillment_risk` 回落 `NORMAL`，在流转入口内、与状态变更同事务」，实现只有读侧收敛 | ✅ **已修**：`OrderTransitionServiceImpl` 在离开 `PENDING` 时把风险置为 `NORMAL`，搭同一条乐观锁更新的车（不额外发 UPDATE），+4 条单测 |
| Spec | spec §2.2 明写 `SET urge_count = urge_count + 1`「并发核心，必须精确实现」，实现用的是预读值 +1 | ✅ **已修**：改用 `setSql("urge_count = urge_count + 1")`，自增原子性交回数据库 |
| Spec | spec §7.3 / T0 RED 要求切接单状态对 FAMILY / ELDER / ADMIN 各一条 403 | ✅ **已修**：补 3 条（含未登录 401、资质未过 2003） |
| Standards | 改派理由的错误码引用三处写成 3007，实际是 `CONTENT_SENSITIVE(6003)` | ✅ **已修**：javdoc、前端注释、E2E 断言、接口文档四处同步 |
| Standards | `eventTypeOf` 的 javadoc 与 switch `default` 行为自相矛盾 | ✅ **已修**：补明 `NORMAL` 分支不可达、`default` 是脏数据兜底 |
| Standards | `writeReassignEvent` 与 `FulfillmentServiceImpl#writeEvent` 字段构造高度重复 | ⚠️ 保留：两处分别属「改派」与「扫描」两个模块，抽出公共方法会让履约模块反向依赖管理端；已在两处 javadoc 注明同一套字段语义 |
| Standards | `FulfillmentRisk.of(...) == null → NORMAL` 的兜底散落 6 处 | ⚠️ 保留：每处兜底的理由不同（读侧收敛 / 脏值计数 / 事件字段），合并成一个 `ofOrDefault` 会掩盖这些差异；已在 `FulfillmentRisk` javadoc 记录统一口径 |
| Standards | `MATCHING_STARTED` / `ADMIN_COORDINATED` 无生产写入点 | ⚠️ 保留：spec §3.5 列的八种事件本期只落六种，见 §七 第 5 条 |

---

## 九、产物索引

| 产物 | 路径 |
|---|---|
| 后端测试报告 | `backend/target/surefire-reports/` |
| Playwright HTML 报告 | `reports/playwright/html/` |
| Playwright JSON 结果 | `reports/playwright/results.json` |
| 数据回滚校验 | 每次 E2E 结束自动输出（见 §4.1） |
| 新增 E2E 用例 | `frontend/e2e/specs/15-fulfillment.spec.js` |
| 数据库设计文档 | `docs/db/*.md`（由 `backend/sql/tools/gen_db_docs.py render` 生成，已随 V6 重新生成） |
