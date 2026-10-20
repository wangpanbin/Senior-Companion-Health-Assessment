# V3.4 智能调度 · 测试报告

| 项 | 值 |
|---|---|
| 版本 | V3.4 智能调度（结构化改造 + 匹配规则引擎 + 大厅排序升级 + 候选推荐） |
| 日期 | 2026-10-20 |
| 分支 | `feature/v3.4-matching`（从 `main` @ `23bd591` 切出） |
| 上游契约 | `docs/spec/V3.4-matching-spec.md` §五 测试矩阵 · `docs/spec/V3.4-tickets.md` T1–T10 |
| 设计决策 | [ADR-0016](../../adr/0016-matching-structured-and-explainable.md) |

---

## 一、执行摘要

| 门禁 | 结果 | 变化 |
|---|---|---|
| 后端 `mvn -B -o verify` | ✅ **1101 / 1101** | 基线 1032 → **+69**（HallSortTest 重写净 +4） |
| 前端 `pnpm run lint:check` | ✅ **0 警告** | `--max-warnings 0` |
| 前端 `pnpm run test` | ✅ **171 / 171** | 基线 155 → **+16** |
| 前端 `pnpm run build` | ✅ | 生产构建成功 |
| Playwright 全量套件（01–18） | ✅ **229 / 229** | 基线 220 → **+9** |
| 迁移 | ✅ V7 自动应用 | 21 张表 → 22 张表 |
| 反向验证 | ✅ | 见 §四 |

**结论**：V3.4 的新增行为与既有套件全部通过，无回归。

---

## 二、环境

| 项 | 值 |
|---|---|
| JDK / Maven | 21.0.7 / 3.9.15（离线 `-o`） |
| Node / pnpm | 22.10+ / lockfile 固定 |
| MySQL / Redis | 本机常驻 |
| 后端实例 | `127.0.0.1:8080`（`java -jar target/nianglin-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev`） |

> ⚠️ **一个环境坑**：`mvn verify` 的 `repackage` 阶段会**覆盖** `target/*.jar`。
> 如果此时有一个 `java -jar` 的后端实例在跑，Windows 会锁住 jar，
> 表现为 `repackage` 失败 —— 与代码无关。跑 verify 前先停掉后端实例。

---

## 三、测试矩阵对照（spec §五）

| 层 | spec 要求 | 落地 | 结果 |
|---|---|---|---|
| `MatchingRuleTest`（纯函数） | 每条规则的支持判定与得分边界；无坐标时距离维度记 0 且不推断 | 7 个嵌套类（Region / Time / Distance / Skill / Rating / Weighted / LegacySlots），**33 例** | ✅ |
| `MatchingWeightConfigTest` | 权重之和 = 1 的启动期校验；改坏权重 → 变红 | 11 例 | ✅ |
| `MatchingServiceTest` | 过滤链顺序与短路；可解释性字段 | 14 例 | ✅ |
| `DistanceMatchRuleTest` | Haversine 边界 | 并入 `MatchingRuleTest$Distance`（6 例，复用 `GeoUtil`） | ✅ |
| 回归 | V3.1 的 `HallSortTest` 需按新排序语义更新 | **重写**（15 条用例） | ✅ |
| 真库测试 | 聚合 SQL 与逐条 Java 统计一致 | `MatchingRealDbTest`（**8 例**） | ✅ |
| 端点↔指标校验 | —— | 本版只登记指标，端点机器面在 V3.6 | — |

---

## 四、反向验证（防「假实现」）

| 机制 | 反向验证动作 | 期望 | 实测 |
|---|---|---|---|
| 距离维度「无坐标 → 记 0」 | 把 `NULL` 坐标当成 `(0,0)` 去算 | 距离分不再为 0、note 消失 → 断言红 | ✅ |
| 权重之和 = 1 | 把 region 权重改成 0.50（和变 1.20） | 启动失败（`IllegalStateException`） | ✅ |
| 权重为负 | region = −0.10（先把和凑回 1） | 启动失败，且错误消息是「不能为负」而不是被和的判定先拦下 | ✅ |
| 「降级」与「不匹配」分开 | 把降级维度的 reason 也放进 reasons | reasons 数量对不上 → 断言红 | ✅ |
| 「已完成」= COMPLETED + REVIEWED | —— | 统计口径统一沿用 M10 铁律 | ✅ |
| `INCOME` 排序仍 400 | 把它加进白名单 | 收益优先会给出没有口径支撑的排序 | ✅ 保持 400 |
| 非法 `sortBy` 不静默降级 | 传 `recommend_v2` | 400 且回显取值 | ✅ |
| 资质判定优先于排序判定 | 资质 PENDING + 非法 sortBy | 先报 2003 | ✅ |
| SQL 里不再拼排序片段 | —— | 大厅排序发生在 Java 侧，SQL 无 `ORDER BY` 片段 | ✅ 断言红 |
| 响应结构与分页语义不变 | —— | `total` = 候选订单数；`pages = ceil(total / size)` | ✅ |
| 区域码 backfill 正确 | —— | 陪诊员与订单的区域码非空且取值都在映射表内 | ✅ 真库断言 |
| 区域码与自由文本一致 | —— | `serviceArea` 含「美兰区」→ 区域码 `HAIKOU_MEILAN` | ✅ 真库断言 |

---

## 五、E2E

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `e2e/specs/18-matching.spec.js`（新建） | 9（全绿） | 大厅 `DISTANCE` / `TIME_MATCH` **不再 400**；`INCOME` **仍 400**；排序选择器渲染且**不含收益优先**；候选端点响应结构；`limit` 生效；非本人订单 → 3004；非家属角色 → 403；`companionName` **必须脱敏** |
| `e2e/specs/15-fulfillment.spec.js`（改） | 1 处断言 | V3.1 旧断言「DISTANCE / TIME_MATCH / INCOME 三者都 400」已按 V3.4 收窄为「只留 INCOME」 |

---

## 六、开工复核的四处偏差（已回写 spec / tickets）

| # | 偏差 | 理由 |
|---|---|---|
| D-3.4-1 | 区域码采用「市_区」两级，跨市记 0 | 预写 spec 没说「区」怎么界定；种子的 8 个区县分属 4 个市，同市不同区判 0.5 才合理 |
| D-3.4-2 | hall 的 `RECOMMEND` 改为「Java 侧打分 + 内存分页」 | 五维里有三件事 SQL 做不了或做了会错（Haversine / 归一化子查询 / 「无坐标」分支） |
| D-3.4-3 | `DISTANCE` 在无坐标时退化为并列 | 今天没有陪诊员填坐标 —— 如实反映「没有距离数据」，等数据有了自动生效 |
| D-3.4-4 | 累计完成单数类指标的来源（V3.3 已记录） | `companion_profile.order_count` 运行期无人维护 |

---

## 七、已知限制

| 项 | 说明 |
|---|---|
| 距离维度目前恒为 0 | 陪诊员坐标是 V3.4 新增的列，存量全空。这是**如实反映**，不是缺陷；坐标一旦填写，排序自动生效 |
| `DISTANCE` 排序当前无区分度 | 同上 —— 全部并列后回落到就诊时间升序 |
| `companionsWithoutSlots` 未在本版统计 | V3.6 的缺口分析会给出这个计数（哪���陪诊员没有可解析的可服务时段） |
| 大厅候选集整批加载 | 上限 `nianglin.matching.hall-candidate-limit`（默认 500），超限记 warn 日志不静默截断 |
| 回退解析只认几种明确写法 | `LegacySlotParser` 认「周一至周五 08:00-18:00」这类常见写法；写得随意的排班文本仍会落到「时间维度记 0 + 标注」 |

---

## 八、结论

V3.4 把「智能调度」从**概念**做成了**有数据基础、有可解释性、可被测试锁死**的工程能力：
结构化改造让五个维度第一次都有真实数据来源，
规则是纯函数所以边界可被毫秒级测试，
每条推荐都能说出「为什么是他」以及「哪一维因为缺数据没算」。
