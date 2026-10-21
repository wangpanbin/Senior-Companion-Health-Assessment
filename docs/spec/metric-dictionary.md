# 指标字典 · 人读面

> **版本**：v1.5 · 2026-10-21
> **机器面**：[`backend/src/main/resources/metrics/metric-dictionary.yml`](../../backend/src/main/resources/metrics/metric-dictionary.yml)（`MetricDictionary` 加载，`MetricDictionaryTest` 双向校验）
> **上游依据**：[`V3.x-roadmap-spec.md`](./V3.x-roadmap-spec.md) §4.2 决策 2.6、§4.6 决策 6.1 / 6.3 · [`迭代计划.md`](../plan/iteration-plan.md) §48 / §49
> **不在本文件范围**：各版的具体接口契约（见 `docs/api/`）· 订单状态机语义（见 ADR-0010）

---

## 〇、这份文件为什么存在

同一份数据，在家属端、陪诊员端、管理端各算一遍，是「数据看起来都对、合起来对不上」的**头号原因**。

而靠注释约束口径是最不可靠的一种约束——**注释不会在改动时报警**，只会在事后被人读错。

所以 V3.2 起把口径变成**可执行的登记**：

```
新指标
   ↓
先在指标字典登记（名称 / 范围 / 时间 / 来源 / 公式 五要素）
   ↓
才允许出现在任何 VO 里
   ↓
MetricDictionaryTest 三层核对：
   ① 字典 → VO：字典里写的字段必须真的存在
   ② VO → 字典：VO 里的每个字段要么是指标、要么被声明为结构字段
   ③ 可达性：被健康接口暴露出来的 VO（含 List<X> 这种泛型参数里的）
      必须出现在字典里 —— 这一条堵住「新增一个 VO 直接用，没人登记就溜过去」
```

任何一边单独改动，测试立刻变红。

> 第 ③ 条是补进来的：原先只有 ①②，而 `vos` 段本身就是**人工维护的清单** ——
> 新增一个 VO（比如待处理事项的 `HealthPendingItemVO`）只要不往清单里写，
> ①② 都碰不到它，「双向校验」就漏了。现在改成从已登记的 VO 出发**沿字段类型走**，
> 凡是被接口带出去的 VO 都必须登记。

**V3.3 / V3.5 只能「登记」不能「新定义」**——它们的新指标要先进这份表；
V3.6 退化为校验与补全（roadmap §7.1-3）。

---

## 一、五要素的填写要求

| 要素 | 含义 | 写不好的典型 |
|---|---|---|
| **指标名称** | 面向人的名字 | 写成字段名（`orderCount`）而不是「就医次数」 |
| **统计范围** | 算的是哪一批数据 | 不写 → 半年后没人说得清「次数」指什么 |
| **统计时间** | 用哪个时间字段、什么区间 | 只写「本月」→ 忘了到底按 `visit_time` 还是 `create_time` |
| **数据来源** | 表 + 字段 | 只写表名 → 不知道读的是哪一列 |
| **计算公式** | 怎么算出来的 | 写成「已完成订单数」→ 漏掉「已完成 = COMPLETED + REVIEWED」这条铁律 |

> 「已完成 = `COMPLETED` + `REVIEWED`」是 M10 起的铁律：
> 把已评价的单排除会让完成率随着用户评价而下降。
> 见 `StatisticsMapper` 的类注释。

---

## 二、指标总表

### 2.1 就医

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `elder.visit.order_count` | 就医次数 | 单个老人档案在统计区间内「已完成」的陪诊订单数 | 统计区间（默认 30 天、最多 366 天、闭区间） | `companion_order(elder_id, status, visit_time)` | `COUNT(status IN ('COMPLETED','REVIEWED'))` | 1 |
| `elder.visit.companion_count` | 陪诊人数 | 统计区间内服务过这位老人的陪诊员人数（去重） | 同上 | `companion_order(elder_id, status, companion_id)` | `COUNT(DISTINCT companion_id WHERE status IN ('COMPLETED','REVIEWED'))` | 1 |
| `elder.summary.recent_visit_count` | 近期就医次数 | 家庭健康协同首页里，该老人在统计区间内已完成的陪诊订单数 | 同上 | `companion_order(elder_id, status, visit_time)` | 同 `elder.visit.order_count` | 1 |

> `elder.visit.order_count` 与 `elder.summary.recent_visit_count` 是**同一个指标的两个出口**，
> 公式必须完全一致 —— 家属在首页看到的「近期就医 2 次」与点进详情看到的「就医次数 2」必须是同一个数。

### 2.2 用药

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `elder.medication.total` | 区间内用药任务总数 | 单个老人档案在统计区间内的服药任务总数（三桶的分母） | 统计区间 | `medication_task(elder_id, plan_date, status, was_missed)` | `COUNT(*)` | 1 |
| `elder.medication.taken` | 已服数量 | 统计区间内「已服且从未漏过」的服药任务数 | 统计区间 | 同上 | `COUNT(bucket = 'TAKEN')` | 1 |
| `elder.medication.missed` | 漏服数量 | 统计区间内漏服的服药任务数（含漏服后补记） | 统计区间 | 同上 | `COUNT(bucket = 'MISSED')` | 1 |
| `elder.medication.pending` | 待服数量 | 统计区间内尚未确认的服药任务数 | 统计区间 | 同上 | `COUNT(bucket = 'PENDING')` | 1 |
| `elder.medication.completion_rate` | 用药完成率 | 统计区间内的服药完成率；分母含漏服与待服 | 统计区间 | 同上 | `taken / total`；`total = 0` 时 `0.00%` | 1 |
| `elder.today.total` | 今日用药任务总数 | 该老人今日的服药任务总数 | **当天**（按 `plan_date` 精确匹配，不随统计区间浮动） | 同上 | `COUNT(*)` | 1 |
| `elder.today.taken` | 今日已服数量 | 该老人今日已服且从未漏过的任务数 | 当天 | 同上 | `COUNT(bucket = 'TAKEN')` | 1 |
| `elder.today.missed` | 今日漏服数量 | 该老人今日漏服的任务数（含漏服后补记） | 当天 | 同上 | `COUNT(bucket = 'MISSED')` | 1 |
| `elder.today.pending` | 今日待服数量 | 该老人今日尚未确认的任务数 | 当天 | 同上 | `COUNT(bucket = 'PENDING')` | 1 |

#### 三桶的唯一定义（`MedicationBucket`）

```
bucket = MISSED   当 was_missed = 1 或 status = 'MISSED'
bucket = PENDING  否则当 status = 'PENDING'
bucket = TAKEN    其余
```

**判定顺序不可调换**：漏服优先于当前状态 —— 否则补记过的任务会被算进「已服」，漏服率立刻被稀释。

**这一条曾经被写错过**：V3.2 之前 M10 的统计只认 `was_missed = 1`，
而漏服扫描只写 `status = 'MISSED'`、从不写 `was_missed`，
于是那批任务**既不 taken 也不 missed**，`taken + missed ≠ total`。
现在 Java 侧是 `MedicationBucket.of(...)`，SQL 侧是 `MedicationBucketSql.CASE_BUCKET`，
由 `MedicationBucketParityTest`（结构层）与 `StatisticsMapperMedicationFixTest`（真库层）双向锁死。

详见 [ADR-0014](../adr/0014-medication-bucket-single-source.md)。

### 2.3 待处理

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `elder.summary.pending_item_count` | 待处理事项条数 | 该老人当前需要家属处理的事项**类别**数（不是事项条数） | 当前状态（不受统计区间影响） | `medication_task(status)` + `companion_order(status, fulfillment_risk)` | `COUNT(满足任一待处理判定的类别)`：今日待服 / 待接单订单 / 待评价订单 / 高风险订单 | 1 |
| `elder.pending_item.count` | 单类待处理事项数量 | 某**一类**待处理事项的数量（同一时刻只出现一条该类事项） | 当前状态 | `medication_task(status)` 或 `companion_order(status, fulfillment_risk)`，按 `type` 判定 | 按 `type` 分别统计：`MEDICATION_PENDING` 今日待服 / `ORDER_WAITING` 待接单 / `ORDER_TO_REVIEW` 待评价 / `FULFILLMENT_RISK` 高风险 | 1 |

> 「类别数」而不是「条数」：家属关心的是「有几件事要办」，
> 不是「有 12 次服药没确认」。同一类事情合并成一项。
> 概览页的 `pendingItems` 列表长度与本指标**恒等** —— 首页角标与详情不能对不上。


### 2.4 陪诊员工作台（V3.3）

> ⚠️ 本节指标**分两套口径**：带「区间」的是统计区间内，带「累计」的是历史累计。
> 字段名自带口径（`total` 前缀 = 累计），接口契约见
> [`docs/api/09-statistics-export.md` §8](../api/09-statistics-export.md)。

#### 区间口径

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `companion.workbench.completed_order_count` | 区间完成单数 | 当前陪诊员在统计区间内「已完成」的陪诊订单数 | 统计区间 | `companion_order(companion_id, status, visit_time)` | `COUNT(status IN ('COMPLETED','REVIEWED'))` | 1 |
| `companion.workbench.service_duration` | 区间服务时长（分钟） | 当前陪诊员在统计区间内「已完成」订单的服务时长合计 | 统计区间 | `companion_order(companion_id, status, start_time, finish_time)` | `SUM(TIMESTAMPDIFF(MINUTE, start_time, finish_time))`，仅两端均非空 | 1 |
| `companion.workbench.completion_rate` | 区间完成率 | 当前陪诊员在统计区间内的完成率 | 统计区间 | `companion_order(companion_id, status, visit_time)` | `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)`，**拒单不计入分母** | 1 |
| `companion.workbench.period_income` | 区间收入 | 当前陪诊员在统计区间内「已完成」订单的结算金额合计 | 统计区间 | `companion_order(companion_id, status, fee, actual_fee, visit_time)` | `SUM(COALESCE(actual_fee, fee))` | 1 |
| `companion.trend.series_value` | 趋势序列值 | 当前陪诊员在统计区间内按天/周/月分桶的指标序列 | 统计区间 | `companion_order` + `order_review` | 按 `dimension` 取 ORDER=完成单数 / INCOME=区间收入 / RATING=有效评价平均分（**按条数加权**） | 1 |
| `companion.income.order_amount` | 区间订单金额 | 区间内「已完成」订单的合同服务费合计 | 统计区间 | `companion_order(companion_id, fee, status, visit_time)` | `SUM(fee)` | 1 |
| `companion.income.actual_income` | 区间实际收入 | 区间内「已完成」订单的结算金额合计 | 统计区间 | `companion_order(companion_id, fee, actual_fee, status, visit_time)` | `SUM(COALESCE(actual_fee, fee))` | 1 |
| `companion.income.unsettled_amount` | 区间待结算金额 | 区间内「已完成」且未线下结算的金额合计 | 统计区间 | `companion_order(..., payment_status)` | `SUM(COALESCE(actual_fee, fee)) WHERE payment_status = 'UNPAID'` | 1 |
| `companion.income.settled_amount` | 区间已结算金额 | 区间内「已完成」且已线下结算的金额合计 | 统计区间 | `companion_order(..., payment_status)` | `SUM(COALESCE(actual_fee, fee)) WHERE payment_status = 'SETTLED'` | 1 |
| `companion.income.advance_total` | 区间代垫合计 | 区间内「已完成」订单上代垫的费用合计 | 统计区间 | `order_fee_item(item_type, amount)` JOIN `companion_order` | `SUM(amount) WHERE item_type = 'ADVANCE'` | 1 |
| `companion.income.service_total` | 区间服务费合计 | 区间内「已完成」订单上记账的服务费合计 | 统计区间 | `order_fee_item(item_type, amount)` JOIN `companion_order` | `SUM(amount) WHERE item_type = 'SERVICE'` | 1 |

#### 累计口径

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `companion.average_score` | 平均评分 | 当前陪诊员的历史累计平均评分 | 历史累计 | `companion_profile(score, user_id)` | `companion_profile.score`（评价提交时于事务内刷新，**不重算**） | 1 |
| `companion.profile.total_completed_order_count` | 累计完成单数 | 当前陪诊员历史累计「已完成」的陪诊订单数 | 历史累计 | `companion_order(companion_id, status)` | `COUNT(status IN ('COMPLETED','REVIEWED'))` | 1 |
| `companion.profile.total_served_elder_count` | 累计服务老人数 | 当前陪诊员历史累计服务过的不同老人数 | 历史累计 | `companion_order(companion_id, elder_id, status)` | `COUNT(DISTINCT elder_id WHERE status IN ('COMPLETED','REVIEWED'))` | 1 |
| `companion.profile.punctuality_rate` | 累计准时率 | 当前陪诊员历史累计的准时到院比例 | 历史累计 | `order_checkin(node, checkin_time)` JOIN `companion_order(visit_time, accept_time)` | 分子=有 `ARRIVE` 打卡且 `checkin_time <= visit_time` 的订单数；分母=已接单订单数；**无打卡视为不准时** | 1 |
| `companion.profile.total_completion_rate` | 累计完成率 | 当前陪诊员历史累计的完成率 | 历史累计 | `companion_order(companion_id, status)` | `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)` | 1 |
| `companion.profile.total_service_duration` | 累计服务时长（分钟） | 当前陪诊员历史累计「已完成」订单的服务时长合计 | 历史累计 | `companion_order(companion_id, status, start_time, finish_time)` | `SUM(TIMESTAMPDIFF(MINUTE, start_time, finish_time))` | 1 |
| `companion.profile.expertise_order_count` | 擅长服务单数 | 当前陪诊员在某科室上「已完成」的订单数 | 历史累计 | `companion_order(companion_id, department, status)` | `COUNT(*) GROUP BY department`；**样本 < 5 单的科室不展示**，最多 TOP 5 | 1 |

#### 两条容易踩的坑

1. **区间按 `visit_time` 过滤，不是 `create_time`。**
   `elder.visit.order_count`（V3.2 家属端）与 `companion.workbench.completed_order_count`
   （本版陪诊员端）数的是**同一批已完成订单**，两端必须用同一个时间字段 ——
   否则决策 6.2「同一指标只有一个口径」就破了。

2. **`companion.profile.order_count` 不读。**
   该字段只在建档时置 0（`CompanionServiceImpl`），运行期没有任何代码维护它 ——
   只有种子脚本 `V3__seed_boundary.sql` 手工同步过。读它会得到一个永远是 0 的数。
   所以累计完成单数一律从 `companion_order` 现算。
   （`companion_profile.score` 不一样：它在评价提交时于事务内刷新，是维护得住的，所以沿用。）


### 2.5 智能调度匹配（V3.4）

> 维度得分是「0–1 的原始分 × 100 后的整数」；加权总分 = Σ(维度分 × 权重) × 100。
> 权重配置化于 `nianglin.matching.*.weight`，**启动期校验权重之和 = 1**。

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `matching.total_score` | 匹配总分 | 候选陪诊员对某个订单的加权匹配总分 | 单次匹配（订单与候选人的配对，无时间区间） | 匹配规则引擎 | `Σ(维度分 × 权重) × 100` | 1 |
| `matching.region_score` | 区域维度得分 | 候选陪诊员与订单的服务区域匹配程度 | 单次匹配 | `companion_profile(service_region_code, service_area)` + `companion_order(service_region_code, address)` | 区域码相等=100；同市不同区=50；跨市=0；缺码时回退自由文本包含式并降级标注 | 1 |
| `matching.time_score` | 时间维度得分 | 订单就诊时间与候选陪诊员可服务时段的重合程度 | 单次匹配 | `companion_available_slot` + `companion_profile(available_time)` | 落在时段内=100；同日不同时段=50；其它=0；无时段时记 0 并降级标注 | 1 |
| `matching.distance_score` | 距离维度得分 | 候选陪诊员与订单地址的直线距离匹配程度 | 单次匹配 | `companion_profile(longitude, latitude)` + `companion_order(longitude, latitude)` | `(1 − Haversine距离 / 满量程) × 100`；**无坐标时记 0 并标注「未填写坐标」，绝不按 (0,0) 推断** | 1 |
| `matching.skill_score` | 服务能力维度得分 | 候选陪诊员在订单科室上的历史服务能力 | 单次匹配（数据来自历史累计） | `companion_order(companion_id, department, status)` | `该科室已完成单数 / 满量程 × 100`，上限 100 | 1 |
| `matching.rating_score` | 评分维度得分 | 候选陪诊员的平均评分归一化后的得分 | 单次匹配（数据来自历史累计） | `companion_profile(score, user_id)` | `score / 5 × 100`；无有效评分时记 0 并降级标注 | 1 |

#### 两条容易被忽略的规则

1. **「没数据」与「不匹配」是两件事。**
   距离维度在陪诊员没填坐标时记 0，但它的含义是「这一维没法算」，
   不是「距离很远」—— 前者写在 `notes`（降级说明），后者才会写进 `reasons`（命中理由）。
   混在一起的话，运营会拿一个不存在的结论去调权重。
2. **候选推荐端点的 `total` 是「通过过滤链的候选总数」**，不是返回条数。
   两者混用会让人以为「只有 3 个候选人」，实际有 12 个只是没进 Top N。


### 2.6 平台运营驾驶舱（V3.5）

> 管理端口径与陪诊员端 / 家属端**共用同一批计算实现**（决策 6.2）：
> 同一个指标只有一个公式，绝不因为「哪个端点」而写第二遍。
>
> ⚠️ 本节**逐条对应机器面** `metrics/metric-dictionary.yml` 里已登记的 15 条
> （`operations.*`）。人读面与机器面**必须一致** —— 两边对不上就是口径漂移本身。

#### 订单与履约

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.today_order_count` | 今日订单数 | 今日新增的陪诊订单数 | **当天**（按 `create_time`，不随统计区间浮动） | `companion_order(create_time)` | `COUNT(*)` | 1 |
| `operations.completed_order_count` | 区间完成订单数 | 统计区间内**创建**的陪诊订单中，当前状态为「已完成」的订单数 | 统计区间 | `companion_order(status, create_time)` | `COUNT(status IN ('COMPLETED','REVIEWED'))` | 1 |
| `operations.in_service_count` | 区间服务中订单数 | 统计区间内**创建**的陪诊订单中，当前状态为「服务中」的订单数 | 统计区间 | `companion_order(status, create_time)` | `COUNT(status = 'IN_SERVICE')` | 1 |
| `operations.pending_count` | 区间待接单订单数 | 统计区间内**创建**的陪诊订单中，当前状态为「待接单」的订单数 | 统计区间 | `companion_order(status, create_time)` | `COUNT(status = 'PENDING')` | 1 |
| `operations.fulfillment_rate` | 履约率 | 统计区间内最终履约（完成）的订单占「完成 + 取消」的比例 | 统计区间 | `companion_order(status, create_time)` | `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)` | 1 |
| `operations.completion_rate` | 完成率（按时） | 统计区间内**已完成**订单里「没超时」的比例 | 统计区间 | `companion_order(status, visit_time, finish_time, create_time)` | 按时完成单数 / 已完成单数；「按时」= `finish_time ≤ visit_time + 履约时长窗口`（`nianglin.operations.fulfillment-window-minutes`，默认 240 分钟） | 1 |
| `operations.avg_accept_minutes` | 平均接单时间 | 统计区间内已接单订单的接单耗时 | 统计区间 | `companion_order(create_time, accept_time)` | `AVG(TIMESTAMPDIFF(MINUTE, create_time, accept_time))`，仅 `accept_time` 非空 | 1 |

#### 服务与陪诊员

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.complaint_rate` | 投诉率 | 统计区间内被投诉的订单占比 | 统计区间 | `complaint` + `companion_order` | 投诉订单数 / 区间订单数 | 1 |
| `operations.average_score` | 平均评分 | 统计区间内有效评价的平均分 | 统计区间 | `order_review(score, is_valid, create_time)` | `AVG(score)`，仅 `is_valid = 1` | 1 |
| `operations.active_companion_count` | 活跃陪诊员数 | 统计区间内**创建**的订单中接过单的陪诊员人数（去重） | 统计区间 | `companion_order(companion_id, create_time)` | `COUNT(DISTINCT companion_id)` | 1 |

#### 异常中心

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.exception_manual_required_count` | 需人工介入订单数 | 当前处于人工介入阈值的待接单订单数 | **当前状态**（不受统计区间影响） | `companion_order(fulfillment_risk, status)` | `COUNT(fulfillment_risk = 'MANUAL_REQUIRED' AND status = 'PENDING')` | 1 |
| `operations.exception_match_warning_count` | 超匹配预警时间订单数 | 当前处于预警 / 紧急阈值的待接单订单数 | **当前状态** | 同上 | `COUNT(fulfillment_risk IN ('WARNING','URGENT') AND status = 'PENDING')` | 1 |
| `operations.exception_complaint_rate_count` | 投诉率异常陪诊员数 | 回溯期内投诉率超过阈值的陪诊员人数 | 回溯期（默认 30 天） | `complaint` + `companion_order` | 投诉率 > `nianglin.operations.complaint-rate-threshold`（默认 20%）且接单量 ≥ 最小样本 | 1 |
| `operations.exception_medication_missed_count` | 用药漏服异常数 | 统计区间内漏服桶的任务数 | 统计区间 | `medication_task(status, was_missed, plan_time)` | 漏服桶计数（`MedicationBucketSql` 唯一真源，与服药日历同源） | 1 |

#### 趋势

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.trend.series_value` | 趋势序列值 | 运营趋势各维度按天/周/月分桶的序列 | 统计区间 | `companion_order` + `complaint` + `order_review` + `medication_task` | 按 `dimension` 取值：订单量 / 陪诊员活跃数 / 履约率 / 投诉数 / 评价数 / 用药任务数 | 1 |

#### 三条必须记住的规则

1. **异常中心的前两项与 V3.1 风险中心同源**（决策 5.1）：
   `manualRequired` 与 `warning + urgent` 必须与
   `GET /api/admin/fulfillment/summary` 的字段**完全一致**。
   异常中心**聚合**风险中心，**不另建一套风险判定** ——
   两套判定必然漂移，这正是 V3.2 在用药三桶上踩过的坑。
   该同源关系由 `OperationsExceptionParityTest` 锁死。
2. **履约率与完成率（按时）不是同一个数**（偏差 D-3.5-1）：
   履约率看「最终有没有履约完」，完成率看「完成得及不及时」。
   两个名字一个数是口径分裂的另一种形态，同样禁止。
3. **同一个指标只有一个公式**：`operations.fulfillment_rate` 同时出现在
   概览与健康度两个出口，**共用同一个计算实现**（决策 6.2）。
   两处各写一遍公式，迟早漂移。

> **人读面与机器面的一致性由 `tools/check_metric_dict_parity.py` 机械校验**：
> ① key 集合必须一致；② 人读面「数据来源」里提到的每个字段，
> 机器面的 source 里必须也提到（单向包含 —— 机器面写得更精确不算漂移，
> 人读面提到一个机器面没有的字段才算矛盾）。
> 这道校验已接进 CI（`.github/workflows/ci.yml`），不一致即非零退出。

> **待登记（V3.6 补登）**：超时率 / 人工介入率 / 改派率 / 平均服务时长 /
> 陪诊员数 / 用药任务数 / 漏服率 —— 这 7 条已在 §2.7 补登（v1.4）。



### 2.7 数据闭环补登（V3.6）

> V3.6 的第一张票是**校验与补全**指标字典（决策 2.8 已把字典从 V3.6 提前到 V3.2 建好）。
> 下列指标是迭代计划 §48 清单里**此前缺失、但已有可算口径**的部分 ——
> **只为已有业务事实补登记，不新造业务事实**。
>
> ⚠️ **本节的表格由 `tools/gen_metric_dict_human_v36.py` 从机器面直接生成**，
> 不要手改 —— 手改就会与机器面漂移（V3.5 修过的那次真实漂移就是这么来的）。
> 两边的 key 集合与「数据来源」口径由 `tools/check_metric_dict_parity.py` 机械校验。

#### 供给缺口分析

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.gap.pending_unaccepted_count` | 时段待接未接单数 | 统计区间内仍处于待接单的陪诊订单数，按周内第几天与小时分桶 | 统计区间（默认 30 天、最多 366 天、闭区间）；桶固定 7×24 = 168 个，与区间长度无关 | companion_order(status, visit_time) | COUNT(status = 'PENDING')，按 visit_time 的 ISO 周内序号（1=周一…7=周日）与小时落桶 | 1 |
| `operations.gap.available_companion_count` | 时段可服务陪诊员数 | 该时段可服务的陪诊员人数（去重）：只统计资质通过（APPROVED）且当前在线（AVAILABLE）的陪诊员 —— 休息 / 离线的人不接新单，算作供给会让缺口被低估；优先读结构化表 companion_available_slot，表里没有时回退解析存量自由文本 companion_profile.available_time | 当前可服务时段（按周模板统计，不随统计区间浮动） | companion_available_slot(companion_id, weekday, start_time, end_time) + companion_profile(user_id, available_time) | 同一陪诊员在同一桶内只计 1 次（按人计，不按单计）；整点落在时段内即算该小时可服务；完全解析不出时段的陪诊员不进任何桶（见 operations.gap.companions_without_slots） | 1 |
| `operations.gap.supply_gap` | 时段供给缺口 | 该时段的待接未接单数减去可服务陪诊员数；大于 0 即为缺口 | 同 operations.gap.pending_unaccepted_count | companion_order + companion_available_slot | pendingUnaccepted − availableCompanions（可为负：供给多于待接量，不做截断） | 1 |
| `operations.gap.companions_without_slots` | 无可解析可服务时段的陪诊员数 | 与供给统计同一批人（资质通过 APPROVED 且当前在线 AVAILABLE）中，既没有结构化可服务时段、又无法从自由文本解析出时段的人数；这批人正是「因不参与供给统计而看不出缺口」的那部分，如实计数披露 | 当前状态（不随统计区间浮动） | companion_profile(user_id, available_time) + companion_available_slot(companion_id) | COUNT(陪诊员 WHERE 资质=APPROVED AND 工作状态=AVAILABLE AND 结构化时段为空 AND 自由文本解析结果为空) | 1 |

#### 其它补登

| 指标 key | 名称 | 统计范围 | 统计时间 | 数据来源 | 计算公式 | 口径版本 |
|---|---|---|---|---|---|---|
| `operations.timeout_rate` | 超时率 | 已完成订单中未在承诺时长内完成的单数占已完成单数的比例 | 统计区间（默认 30 天、最多 366 天、闭区间） | companion_order(status, visit_time, finish_time, create_time) | (已完成单数 − 按时完成单数) / 已完成单数；按时 = finish_time <= visit_time + nianglin.operations.fulfillment-window-minutes（默认 240 分钟）；分母为 0 时记 0.00% | 1 |
| `operations.manual_intervention_rate` | 人工介入率 | 统计区间内订单中履约风险已达需人工介入的比例 | 统计区间（默认 30 天、最多 366 天、闭区间） | companion_order(fulfillment_risk, create_time) | COUNT(fulfillment_risk = 'MANUAL_REQUIRED') / 区间订单数；判定沿用 V3.1 的履约风险分级，不另建一套；分母为 0 时记 0.00% | 1 |
| `operations.reassign_rate` | 改派率 | 统计区间内被管理员改派的订单占区间订单总数的比例 | 统计区间（默认 30 天、最多 366 天、闭区间） | admin_oper_log(oper_type, target_id, create_time) + companion_order(create_time) | COUNT(DISTINCT admin_oper_log.target_id WHERE oper_type = 'REASSIGN_ORDER') / 区间订单数；同一单被改派多次只算一单；注意分子按操作日志时间过滤、分母按下单时间过滤（订单表里没有被改派过的痕迹）；分母为 0 时记 0.00% | 1 |
| `operations.service_duration_minutes` | 平均服务时长（分钟） | 统计区间内已完成订单的服务时长均值 | 统计区间（默认 30 天、最多 366 天、闭区间） | companion_order(status, start_time, finish_time, create_time) | AVG(TIMESTAMPDIFF(MINUTE, start_time, finish_time))，仅 COMPLETED + REVIEWED 且起止时间均非空；无数据时记 0.00 | 1 |
| `operations.companion_count` | 区间陪诊员数 | 统计区间内服务过订单的陪诊员人数（去重）；与 operations.active_companion_count 同口径同实现（决策 6.2） | 统计区间（默认 30 天、最多 366 天、闭区间） | companion_order(companion_id, create_time) | COUNT(DISTINCT companion_id)，companion_id 非空 | 1 |
| `operations.medication_task_count` | 区间用药任务数 | 统计区间内的服药任务总数（漏服率的分母） | 统计区间（默认 30 天、最多 366 天、闭区间） | medication_task(plan_time, status, was_missed) | COUNT(*)，三桶判定与 MedicationBucketSql.CASE_BUCKET 同源（ADR-0014） | 1 |
| `operations.medication_missed_rate` | 漏服率 | 统计区间内漏服任务占用药任务总数的比例 | 统计区间（默认 30 天、最多 366 天、闭区间） | medication_task(plan_time, status, was_missed) | 漏服桶数 / 任务总数；漏服桶由 MedicationBucketSql.CASE_BUCKET 唯一裁定（status = 'MISSED' 或 was_missed = 1），与服药日历同源；分母为 0 时记 0.00% | 1 |

---

## 三、区间约定（所有指标共用）

| 项 | 约定 |
|---|---|
| 默认 | 最近 **30 天**（含今天） |
| 上限 | **366 天**，超出返回 `9002` |
| 形态 | **闭区间** `[开始日 00:00:00, 结束日 23:59:59]` |
| 落点 | `dto.DateRangeQuery#toRange()` —— V3.2 / V3.3 / V3.5 与 M10 共用同一份实现 |

**为什么「今日用药」不跟随区间**：它是「今天」的事实，
与「统计区间」是两个维度。区间浮动的指标与当天事实混在一张卡上，
会让「本月用药完成率」和「今日吃了 2 次」看上去像同一口径。

---

## 四、变更记录

| 日期 | 版本 | 变更 | 影响 |
|---|---|---|---|
| 2026-10-20 | v1.0 | 首次建立。登记 V3.2 的 12 条指标 | — |
| 2026-10-20 | v1.1 | 登记 V3.3 陪诊员工作台的 18 条指标（区间 11 条 + 累计 7 条） | `companion_profile.order_count` 不再作为来源（运行期无人维护），累计完成单数改从 `companion_order` 现算 |
| 2026-10-20 | v1.2 | 登记 V3.4 智能调度的 6 条匹配指标（五个维度 + 加权总分） | 无 —— 新指标首次登记 |
| 2026-10-20 | v1.3 | 登记 V3.5 运营驾驶舱的 **15** 条管理端指标（订单/履约/服务/陪诊员/异常/趋势六域），与机器面逐条对齐 | 超时率 / 人工介入率 / 改派率等留给 V3.6 补登 |
| 2026-10-20 | v1.4 | 登记 V3.6 数据闭环的 **11** 条指标（供给缺口 4 条 + 其它补登 7 条），本节表格由脚本从机器面生成 | 超时率 / 人工介入率 / 改派率等补齐迭代计划 §48 清单；§2.6 运营端 5 条指标的「数据来源」勘误为 `create_time`（机器面与 SQL 一直是 `create_time`，是人读面滞后） |
| 2026-10-21 | v1.5 | 供给缺口 2 条指标的「统计范围」措辞精度修正：universe 明确为**资质通过（APPROVED）且当前在线（AVAILABLE）**的陪诊员，与实现一致；新增 `GET /api/admin/metrics` 指标字典只读导出端点 | **口径值未变**（`caliberVersion` 保持 1）—— 原措辞「全部陪诊员中」与实现的过滤条件不符，是**文档写得比实现宽**，实现侧 `OperationsMapper` 的过滤是有意为之（休息 / 离线的人不接新单，算作供给会让缺口被低估）；新端点导出的是**口径定义**而非统计值，故不进 `metrics/endpoints.yml` |

> 口径变更 = 改公式 + 升 `caliberVersion` + 在本表留一行。
> **不改写历史数据**（V3.6 范围外条款）。
>
> v1.4 里的 §2.6 勘误属于**人读面写错**（机器面与 SQL 一直是对的），
> 所以没有升 `caliberVersion` —— 口径本身没变，改的是把口径抄错的那一栏。
>
> v1.5 同理：计算逻辑一个字没动，只是把**两个面都写宽了**的 universe 收回成实现真正使用的那一批人。
