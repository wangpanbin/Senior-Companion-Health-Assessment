# V3.3 陪诊员工作台 · 测试报告

| 项 | 值 |
|---|---|
| 版本 | V3.3 陪诊员工作台（工作台概览 / 趋势 / 服务画像 / 收入中心） |
| 日期 | 2026-10-20 |
| 分支 | `feature/v3.3-companion-workbench`（从 `main` @ `c735693` 切出） |
| 上游契约 | `docs/spec/V3.3-companion-workbench-spec.md` §三 测试矩阵 · `docs/spec/V3.3-tickets.md` T1–T10 |
| 本报告范围 | V3.3 新增行为 + 全量回归；**不含** M0–M13 既有功能的功能性复测（由全量套件覆盖） |

---

## 一、执行摘要

| 门禁 | 结果 | 变化 |
|---|---|---|
| 后端 `mvn -B verify` | ✅ **1032 / 1032** | 基线 992 → **+40** |
| 前端 `pnpm run lint:check` | ✅ **0 警告** | `--max-warnings 0` |
| 前端 `pnpm run test` | ✅ **155 / 155** | 基线 139 → **+16** |
| 前端 `pnpm run build` | ✅ | 生产构建成功 |
| Playwright 全量套件（01–17） | ✅ **220 / 220** | 基线 210 → **+10**（V3.3 新增 spec 17） |
| 夹具回滚校验 | ✅ | 19 张表 count / max_id 全部回到基线，6 张表白名单行逐列一致 |
| 反向验证 | ✅ | 见 §四 |

**结论**：V3.3 的新增行为与既有套件全部通过，无回归、无跳过、夹具干净回滚。

---

## 二、环境

| 项 | 值 |
|---|---|
| JDK | 21.0.7 |
| Maven | 3.9.15（离线 `-o`） |
| Node / pnpm | 22.10+ / lockfile 固定 |
| MySQL / Redis | 本机常驻（E2E 夹具自动 snapshot / restore） |
| 后端实例 | `127.0.0.1:8080`（`SPRING_PROFILES_ACTIVE=dev`，用 `mvn verify` 产出的 jar 启动） |
| 浏览器 | 本机 Chrome（Playwright `channel: 'chrome'`） |

> ⚠️ **启动方式**：本机 `mvn spring-boot:run` 因插件解析阶段报
> `ChecksumFailureException`（离线仓库缺 spring-boot-maven-plugin 的校验和）而失败。
> 改为直接跑 `mvn verify` 产出的 `target/nianglin-0.1.0-SNAPSHOT.jar`，
> 行为等价且少一层 Maven 插件解析。

---

## 三、测试矩阵对照（spec §三）

| 层 | spec 要求 | 落地 | 结果 |
|---|---|---|---|
| Service 单测 | 每条口径的公式；拒单不计入分母；准时率的分子分母；擅长标签的样本门槛 | `CompanionStatisticsServiceTest`（18 例） | ✅ |
| 真库测试 | 聚合 SQL 能跑且与逐条 Java 统计一致 | `CompanionStatisticsRealDbTest`（3 例） | ✅ |
| 越权矩阵 | 陪诊员不能通过任何入参看同行的数据 | `CompanionStatisticsAccessMatrixTest`（16 例）+ E2E 越权组 | ✅ |
| Vitest | 前端不再出现 `reduce()` 口径计算 | `companion-workbench.test.js`（静态扫描 6 例，含 3 条扫描器自证） | ✅ |
| E2E | 工作台四块内容渲染；`income.vue` 的数字与后端一致 | `17-companion-workbench.spec.js`（10 例） | ✅ |
| 指标字典 | 每个端点返回的指标都在字典里 | `MetricDictionaryTest` 扩展 3 例 | ✅ |

---

## 四、反向验证（防「假实现」）

| 机制 | 反向验证动作 | 期望 | 实测 |
|---|---|---|---|
| 完成率分母不含拒单 | 种子陪诊员 301 拒过 **6** 单；真库测试断言「分母 < 完成数 + 拒单数」且接口返回的分母与逐条 Java 统计一致 | 拒单被拉进分母 → 断言红 | ✅ 红/绿双向成立 |
| 「已完成」= COMPLETED + REVIEWED | 单测把 REVIEWED 单计入完成数（+2） | 断言红 | ✅ |
| 收入用 `COALESCE(actual_fee, fee)` | 单测用 mapper 聚合值断言 `actualIncome` | 断言红 | ✅ |
| 待结算 + 已结算 = 实际收入 | 单测 + 真库 + E2E 三处各断言一次 | 三处都红 | ✅ |
| 擅长服务样本门槛 5 单 | 单测塞入样本 4 / 1 的科室 | 被挡掉 → 断言红 | ✅ |
| 评分趋势按条数加权 | 周粒度下断言 4.20（日均值的平均会得 3.00） | 断言红 | ✅ |
| 非法维度不静默降级 | 单测断言抛 `BusinessException(400)` 且**没查库** | 断言红 | ✅ |
| `companionId` 入参被忽略 | E2E 断言注入前后 `completedOrderCount` / `periodIncome` 相同 | 断言红 | ✅ |
| 指标字典双向一致 | `MetricDictionaryTest` 三层校验（字典→VO / VO→字典 / 可达性） | 任一边改动 → 红 | ✅ |
| 前端零自算 | 静态扫描 `reduce(` / `.filter(` / `groupBy`；另有 3 条**扫描器自证**用例（合成违规片段必须被抓） | 扫描器恒过 → 自证用例红 | ✅ |

---

## 五、新增测试清单

### 5.1 后端（+40 例）

| 类 | 例数 | 覆盖 |
|---|---|---|
| `service/CompanionStatisticsServiceTest` | 18 | 完成率（含反向）、收入 COALESCE、结算拆分、费用构成、准时率、擅长门槛（5 单 / TOP 5）、趋势补 0 与长度一致、评分加权（周/日）、非法维度与粒度、登录态取 ID |
| `service/CompanionStatisticsRealDbTest` | 3 | 聚合 SQL vs 逐条 Java 统计、拒单不入分母、收入拆分恒等式 |
| `security/CompanionStatisticsAccessMatrixTest` | 16 | ELDER / FAMILY / ADMIN × 4 端点 → 403；未登录 → 401；陪诊员本人 → 200；`?companionId` 被忽略；两个陪诊员互不可见 |
| `util/MetricDictionaryTest`（扩展） | +3 | V3.3 的六个 VO 已登记 / 四个端点都登记过 / 同一指标多出口共用一条公式 |

### 5.2 前端（+16 例）

| 文件 | 例数 | 覆盖 |
|---|---|---|
| `src/__tests__/companion-workbench.test.js` | 16 | 静态扫描 6 例（含 3 例扫描器自证）+ 组件渲染 10 例（正常路径 / 空态 / 错误态 / 数字直接来自后端） |

### 5.3 E2E（+10 例）

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `e2e/specs/17-companion-workbench.spec.js` | 10 | 工作台四块渲染 / 明示统计区间 / **收入页「待结算」== 后端口径** / 四端点响应结构 / 趋势长度一致 / 待结算+已结算=实际收入 / 越权 403 / `companionId` 被忽略 / 非法维度 400 / 区间超限 9002 |

---

## 六、开工复核发现的三处口径问题（已修）

| # | 问题 | 影响 | 处置 |
|---|---|---|---|
| 1 | **`companion_profile.order_count` 运行期无人维护** —— 只在建档时置 0，只有种子脚本 `V3__seed_boundary.sql` 手工同步过 | 预写 spec 要求「累计完成单数」读它，实际会永远返回 0 | 改为从 `companion_order` 现算；指标字典公式同步改写 |
| 2 | 「本月」措辞与「可传参的统计区间」不是一回事 | 同一数字会被解释成两个口径 | 统一用「区间」，走共享 `DateRangeQuery` |
| 3 | 概览（区间）与画像（累计）都有「完成率」 | 接口层面埋着口径分裂 | 字段名自带口径（`total` 前缀 = 累计），字典里两条各占一个 key |

> 另有一处**本版之外的发现**（未修，记档）：`companion_profile.order_count` 的陈旧问题
> 同样影响陪诊员「我的资料」页展示的累计单数（`CompanionProfileVO#orderCount`）——
> 那是既有页面的数据陈旧问题，不属 V3.3 范围，建议单开一条缺陷处理。

---

## 七、已知限制

| 项 | 说明 |
|---|---|
| 画像的「累计」不随区间浮动 | 有意设计：画像回答「我到底什么水平」，概览回答「这段时间怎么样」。字段名与指标字典两处都写明了 |
| 收入页的「区间收入」不是「累计收入」 | 旧页的「累计」是拉全量订单 `reduce()` 算的，违反决策 G8 且无法用统计区间表达。改为区间口径并在页面明示区间 |
| 陪诊员坐标缺失 | 属 V3.4 结构化改造范围，V3.3 不涉及 |
| E2E 未覆盖「陪诊员零订单」的分支 | 种子陪诊员均有历史订单，空态由前端单测覆盖 |

---

## 八、门禁命令与结果

```powershell
# 后端
cd backend; mvn -B -o verify
#   → Tests run: 1032, Failures: 0, Errors: 0, Skipped: 0
#   → BUILD SUCCESS（JaCoCo check 通过）

# 前端
cd frontend
pnpm run lint:check      # → 0 error / 0 warning，EXIT=0
pnpm exec vitest run --maxWorkers=2
#   → Test Files 16 passed (16) | Tests 155 passed (155)
pnpm run build           # → ✓ built in 8.10s，EXIT=0

# E2E
pnpm exec playwright test
#   → 220 passed (3.4m)
#   → [restore] 19 张表全部回到基线；[verify] 全部回到基线 ✅
```

---

## 九、结论

V3.3 陪诊员工作台**功能完整、口径统一、边界清晰**：
四个端点的每个数字都有明确的公式与登记位置，前端零自算，
越权矩阵与真库一致性均通过，原有套件零回归。
