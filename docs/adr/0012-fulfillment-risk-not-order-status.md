# 0012. 履约风险是独立字段，不是订单状态机的新状态

| | |
|---|---|
| 状态 | **已接受 (Accepted) · 2026-10-17 落地** |
| 日期 | 2026-10-17 |
| 决策者 | 项目负责人 |
| 关联模块 | M4 陪诊订单 / M9 管理后台 / V3.1 履约保障（FR-Risk / FR-Read / FR-Admin） |
| 关联文档 | `docs/spec/V3.1-fulfillment-spec.md` §1/§3 · `AGENTS.md` §4.1 · `backend/sql/V6__order_fulfillment.sql` |
| 相关 ADR | 0010（订单状态流转单一入口）· 0009（费用明细模型，同为「不动状态机的补强」） |

---

## 背景

V3.1 要回答产品侧的一个问题：**「下了单一直没人接，家属怎么知道？平台怎么兜底？」**

原实现里没有任何字段能回答这件事。订单只有 `status`，而 `status` 的取值集合
（`PENDING` → `ACCEPTED` → `IN_SERVICE` → `COMPLETED` → `REVIEWED` / `CANCELLED`）
描述的是「这笔单走到哪一步了」，不是「这笔单有没有可能接不成」。

于是出现了必须回答的两个问题：

1. 要不要给订单状态机加一个新状态（如 `AT_RISK`）来表达「等太久」？
2. 风险升级这类事件，要不要写进 `order_status_log`？

---

## 决策

**风险是一列可打标的字段（`companion_order.fulfillment_risk`），不是订单状态；
风险事件落在独立的 `order_fulfillment_event` 表，不复用 `order_status_log`。**

```java
// 风险等级：NORMAL / WARNING / URGENT / MANUAL_REQUIRED，只在 status='PENDING' 时有意义
companion_order.fulfillment_risk VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
```

两条口径写进 `V6` 迁移脚本顶部，并被 `FulfillmentRisk` 枚举的 javadoc 复述：

- `status` 与 `fulfillment_risk` **永不互相推导**；
- 离开 `PENDING`（被接单 / 被取消）时风险一律回落 `NORMAL` 并停止扫描。

---

## 理由一：为什么不用新状态

把风险做成状态机的新状态，会**同时破坏三件事**：

| 被破坏的对象 | 破坏方式 |
|---|---|
| 订单时间线 | `order_status_log` 记的是「状态从 X 变到 Y」。加一个 `AT_RISK`，家属的时间线里就会出现「待接单 → 等太久 → 待接单」这种来回跳的记录，而订单其实**根本没变过状态** |
| 状态机转移表 | `TRANSITIONS` 要新增「进」和「出」两条边。而「等太久」没有对应的合法动作 —— 谁能把订单从 `AT_RISK` 变回 `PENDING`？扫描任务？那状态就变成了后台定时任务的函数 |
| 完备性判据 | `OrderStatusTest` 靠「每个状态的出边集合已穷举」这条性质锁住铁律。状态集合一变，全部用例要重写，而重写后的判据比原来弱（因为没人知道新状态该出现在哪些出边里） |

更根本的一点：**`PENDING + WARNING` 这个组合本来就是合法的**。
「还在等人接」和「等得有点久了」是两个正交的事实，
硬塞进一个枚举只能表达其中一个 —— 而这正是 ADR-0009 处理费用明细时遇到的同构问题
（`paymentStatus` 表达不了「哪些项目是代垫」），当时的结论同样是「加字段，不动状态机」。

## 理由二：为什么不用状态流转日志表

`order_status_log` 的语义是「订单状态发生了一次合法流转」。
它的每一行都隐含一个断言：**上一行的 `to_status` 等于这一行的 `from_status`**。

风险事件不满足这个断言：一笔订单可以 `NORMAL → WARNING → URGENT → MANUAL_REQUIRED`
一路升上去，期间 `status` 始终是 `PENDING`。把这些塞进 `order_status_log`，
要么出现 `PENDING → PENDING` 的空转行（时间线渲染要额外过滤，且「过滤掉」意味着日志不再是完整事实），
要么 `from_status` 被写成上一条风险事件的值，让状态机时间线彻底失真。

因此新增 `order_fulfillment_event`：**只增不改**，语义是「履约过程中发生了什么」，
与 `order_status_log`（「订单状态从哪变到哪」）是两张表、两种语义、两个消费者。

---

## 后果

### 收益

- (+) 订单状态机的**铁律零改动** —— T9 的验收判据「不破坏现有订单状态机」由此变成可自动验证的事实，而不是需要人肉复核的承诺；
- (+) 风险等级可被索引（`(fulfillment_risk, status, create_time)`），风险中心的四档计数与列表是两条索引扫描，不需 JOIN 状态日志；
- (+) 风险**永不降级**这条规则只有一处落点（扫描任务的条件更新），不存在「谁把风险改回去了」的歧义。

### 代价

- (-) 多一张表、多一列 —— V6 迁移因此是「三列 + 一表 + 三索引」而不是零成本；
- (-) 读者需要同时理解两张日志表的分工 —— 缓解 = 两张表的 javadoc 各自写明「与另一张的区别」，且 `V6` 顶部用注释重申；
- (-) 「离开 PENDING 即回落 NORMAL」需要一处**显式落点** —— 已落在订单状态流转唯一入口
  （spec §5.3）：`OrderTransitionServiceImpl` 在离开 `PENDING` 时把风险置为 `NORMAL`，
  **搭同一条乐观锁更新的车**，不额外发一条 UPDATE，因此与状态变更天然同事务、无中间态。
  读侧的强制收敛（`OrderVO#base`）仍保留，作为脏数据的第二道防线。

---

## 验证

- ✅ `V6__order_fulfillment.sql` 已落地，`information_schema` 守卫保证幂等；
- ✅ `OrderStatus` 的 `TRANSITIONS` 与 `canTransitTo` **零改动**（`git diff` 可证）；
- ✅ `OrderStatusTest` 86 条既有判据全绿，未因本决策改写任何断言；
- ✅ 风险只在 `PENDING` 时对外可见：`OrderVOFulfillmentTest` / `FulfillmentServiceDetailTest` 各有一组「非待接单一律收敛」用例钉住；
- ✅ 离开 `PENDING` 时风险在流转入口回落 `NORMAL`：`OrderTransitionServiceTest` 四条用例覆盖接单 / 取消 / 管理员强制 / 已接单后的流转。

---

## 附：与 `AGENTS.md` §2.4 的字面张力（豁免说明）

§2.4 写的是「并发敏感的接口用 `@Version` 乐观锁，**不要**自己写 `UPDATE ... WHERE id=? AND status=?`」。
本版的三处条件更新（风险升级、催单计数、管理员改派）看起来与之冲突，实际不冲突：

| | `@Version` 乐观锁 | 本版的条件更新 |
|---|---|---|
| 保护的是 | **整行**的并发写（谁改了这一行） | **某个业务前提**是否仍然成立（还是不是待接单 / 计数有没有超限） |
| 败者拿到什么 | `3003` 或回滚 | 由业务语义决定（`3008` / `3009` / `3010`） |
| 能否表达「必须仍是 PENDING」 | 不能 —— version 不知道状态 | 能 |

抢单这条路径**仍然走 `@Version`**（`OrderServiceImpl#accept`），改派与抢单竞争同一行时，
数据库保证只有一个能改成功，败者分别拿到 `3010` / `3003`。
换句话说：**行的独占权归乐观锁，业务前提的判定归条件更新**，两者互补而非替代。

---

## 相关 ADR

- `0010-order-state-transition-single-entry.md`（状态流转唯一入口 —— 本决策的前提，V3.1 不新增第二条路径）
- `0009-order-fee-item-model.md`（同为「不动状态机的补强」的先例）
