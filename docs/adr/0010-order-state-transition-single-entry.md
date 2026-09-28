# 0010. 订单状态流转收敛到单一入口

| | |
|---|---|
| 状态 | **已接受 (Accepted) · 2026-09-28 落地，commit `7171ecd`** |
| 日期 | 2026-09-28 |
| 决策者 | 项目负责人 |
| 关联模块 | M4 陪诊订单 / M9 管理后台纠纷处理 |
| 关联文档 | `AGENTS.md` §4.1 · `plan.md` §M4-3 · `docs/agents/designs/M4-order.md` |
| 相关 ADR | 0009（费用明细模型） |

---

## 背景

`plan.md` §M4 验收标准把订单状态机列为**全局瓶颈模块**（关键路径 `M0 → M1 → M2 → M3 → M4 → M9`），并给了三条铁律：

1. 禁止跳级：待接单不能直接变已完成
2. 禁止回退：已接单不能退回待接单
3. 终态不可再流转，仅 ADMIN 可强制改变（且须写 `admin_oper_log`）

`OrderStatus.java` 把这三条固化成了唯一权威——`TRANSITIONS` 转移表 + `canTransitTo()`：

```java
private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
        PENDING,    EnumSet.of(ACCEPTED),
        ACCEPTED,   EnumSet.of(IN_SERVICE),
        IN_SERVICE, EnumSet.of(COMPLETED),
        COMPLETED,  EnumSet.of(REVIEWED),
        REVIEWED,   EnumSet.noneOf(OrderStatus.class),
        CANCELLED,  EnumSet.noneOf(OrderStatus.class));
```

`AGENTS.md` §4.1 进一步规定：

> 实现已就绪：`OrderStatus.canTransitTo(target)` 是唯一权威判断，所有状态流转代码**必须**通过它，**禁止**硬编码字符串。

### 问题：规则写得对，但保护不住

2026-09-28 盘点发现：

- `canTransitTo` 在**生产代码中零调用**，只在 `OrderStatusTest`（86 行）里被测；
- `OrderServiceImpl` 改为逐方法硬编码 `from != X` 守卫，实测 **4 处**：

  | 行 | 守卫 | 拦截的非法流转 |
  |---|---|---|
  | `L316` | `from != PENDING` | 已接单后家属单方取消 |
  | `L359` | `from != PENDING` | 非待接单状态接单 |
  | `L442` | `from != ACCEPTED` | 非已接单状态进入服务中 |
  | `L480` | `from != IN_SERVICE` | 非服务中状态完成订单 |

  另有终态守卫 `L599`–`L607`（`current == target` / 已终态）与评价前置校验 `L563`，同样不走 `TRANSITIONS`；
- `AdminServiceImpl` 强制终态也只用 `isTerminal()` / `isAdminForceable()`，绕过 `TRANSITIONS`；
- **乐观锁已正确接线**：`L456` / `L518` 的 `rows == 0` 即 `@Version` 冲突分支——说明配套逻辑本身做了，只是**判定规则被复制了 4 份**，每份各自漂移。

**后果不是「现在有 bug」，而是「规则可以被静默破坏」：**

> 往 `TRANSITIONS` 里加一个 `PENDING → CANCELLED` 的边，删掉一条 `IN_SERVICE → COMPLETED` 的边，**`mvn test` 依然全绿**——因为测试在测一个没人用的方法，而被测的业务路径走的是另一套硬编码。

`mvn test` 绿 ≠ 状态机受保护。这是典型的「测试覆盖了工具，没覆盖行为」。

---

## 决策

**把订单状态流转收敛到 `OrderTransitionService.transition(order, target, operatorContext)` 这一个方法。**

### 契约

```
OrderTransitionService.transition(
    CompanionOrder order,
    OrderStatus target,
    TransitionOperator operator   // userId + role + 是否 ADMIN 强制
)
```

该方法内部**独占**以下四项，不允许业务代码各自实现：

1. **合法流转判定** —— 唯一调用 `OrderStatus.canTransitTo(target)` 的地方
2. **角色门禁** —— 谁可以触发哪个目标状态
3. **乐观锁落库** —— `@Version` + `OptimisticLockerInnerInterceptor`，并发抢单只一人成功
4. **流转日志双写** —— 每次变更写状态流转日志，与订单同事务

### 业务代码的义务

- 业务 Service **不得** `order.setStatus(...)`；
- 业务 Service **不得** `orderMapper.updateById(order)` 来改状态；
- 需要改状态时，**只能**调 `transition(...)`。

`AGENTS.md` §4.1 相应从「必须通过 `canTransitTo`」升级为「**必须通过 `OrderTransitionService.transition`**」（待本 ADR 落地后同步）。

---

## 选项

### A. 逐处替换为 `canTransitTo()` 直接调用

- **优点**：改动最小，约 6 行；字面满足 `AGENTS.md` §4.1 现文。
- **缺点**：**仍然是约定而非结构**。下一个开发者在自己的 service 里 `order.setStatus(COMPLETED)` + `updateById`，没有任何机制拦住，`canTransitTo` 照样能被绕开。四项配套逻辑（角色门禁 / 乐观锁 / 流转日志）仍散落在各方法里，漏一处不会被发现。

### B. 收敛到单一入口 service ← **本次选**

- **优点**：让「合法流转」与「日志双写」「乐观锁」「角色门禁」四项**结构性绑定**。漏一项就测试红；想绕开就必须绕开整个 service，而那在 code review 里极其显眼。`canTransitTo` 从「一个可被忽略的工具方法」变成「唯一入口内部的事实来源」。
- **缺点**：新增一个 service；`OrderServiceImpl` 6 处 + `AdminServiceImpl` 强制终态需重构；`orderMapper.updateById` 不能再用于状态变更，Mapper 层用法需要收敛。

### C. 引入 Spring StateMachine 框架

- **优点**：状态机是一等公民，配置化声明，生态成熟。
- **缺点**：为一个 6 状态单链状态机引入整套框架，配置与现有 `@Version` 乐观锁、`MaskUtil` 脱敏、`ResultCode` 错误码体系都要重新对接；`plan.md` 技术栈清单里没有它，属于「自创技术栈」，违反 `AGENTS.md` §0.2「不得引入 pom.xml / package.json 之外的依赖，需人工确认」。**收益不抵成本。**

---

## 后果

### 正面

- 修掉「测试全绿但规则可被静默破坏」这个唯一的高危点。
- T2.1 的反向验证可执行：删 `TRANSITIONS` 一条边 → `OrderStatusTest` 必须失败。
- 后续新增流转边（例如二期加「改期」状态）只有一处要改。

### 负面

- `OrderServiceImpl` 是 931 行测试覆盖的核心类，重构需同步改 `OrderServiceTest`（931 行），**T2.1 的实际工作量比「替换 6 行」大**。
- 存在 `updateById` 被用于状态变更的漏网路径，需要在 code review checklist 里长期盯。

### 验证标准（落地后）

- [x] `grep -rn "setStatus" backend/src/main/java/.../service` 的命中全部落在 `OrderTransitionService` 内
- [x] `grep -rn "from != \|from == " backend/src/main/java/.../service` 返回空
- [x] `canTransitTo` 在 `service` 包内**恰好 1 处**调用点（即 `OrderTransitionService.transition`）——不是"多处都调用"，是"只有一处能调用"
- [x] **反向验证**：删 `TRANSITIONS` 一条边 → `mvn test -Dtest=OrderStatusTest` 必须失败
- [x] `mvn test` 全绿，`OrderServiceTest` 覆盖率不低于重构前

### 后续待办

- [ ] 本 ADR 落地后同步 `AGENTS.md` §4.1
- [ ] 同步 `docs/agents/designs/M4-order.md` 的状态机章节
- [ ] 评审 `orderMapper.updateById` 的其余调用点，确认没有绕过流转入口的路径
