# M4 设计评审 · 陪诊订单与状态机

- **模块**：M4（主路径 B）
- **评审时间**：2026-09-16（依落地代码反向补齐，原开工前评审未存档）
- **关联**：`docs/api/03-order.md`、`docs/agents/PLAN_BACKEND.md §8 M4`
- **代码**：`controller/order/OrderController`、`service/impl/OrderServiceImpl`、`constant/OrderStatus`、`util/ComplianceCheckUtil`

---

## 1 · 接口表

| # | 方法 | 路径 | 角色 | 入参 | 出参 |
|---|---|---|---|---|---|
| 1 | GET | `/api/order` | 登录用户 | `OrderQuery` | `PageResult<OrderVO>` |
| 2 | GET | `/api/order/hall` | COMPANION | `OrderHallQuery` | `PageResult<OrderVO>` |
| 3 | GET | `/api/order/{id}` | 登录用户 | path `id` | `OrderVO` |
| 4 | GET | `/api/order/{id}/timeline` | 登录用户 | path `id` | `List<OrderTimelineVO>` |
| 5 | POST | `/api/order` | FAMILY | `OrderCreateDTO` | `OrderCreateResultVO` |
| 6 | PUT | `/api/order/{id}/cancel` | FAMILY | `OrderCancelDTO` | `Void` |
| 7 | POST | `/api/order/{id}/accept` | COMPANION | path `id` | `OrderAcceptResultVO` |
| 8 | POST | `/api/order/{id}/reject` | COMPANION | `OrderRejectDTO` | `Void` |
| 9 | POST | `/api/order/{id}/start` | COMPANION | path `id` | `OrderFlowResultVO` |
| 10 | POST | `/api/order/{id}/complete` | COMPANION | `OrderCompleteDTO` | `OrderFlowResultVO` |

> 计划写「9 个端点」，实际 10 个（多出 `/timeline`，为家属实时进度页所需）。
> 查询类端点（1/3/4）**不加 `@PreAuthorize`**：家属与陪诊员都要读，谁能读哪条由 Service 的归属校验决定。

---

## 2 · 数据流

```
POST /api/order（下单）
  Controller(create, @PreAuthorize FAMILY)
    → OrderServiceImpl.create()
        ├─ 归属校验：家属 ↔ 老人绑定关系（family_elder_relation）
        ├─ Redis INCR 生成 order_no（2 天 TTL，跨零点归 1）
        ├─ INSERT companion_order（status=PENDING）
        ├─ INSERT order_status_log（operatorName 定格快照）
        └─ MessageService.send(...) 广播给在线陪诊员（M8）

POST /api/order/{id}/accept（接单）
  Controller(accept, @PreAuthorize COMPANION)
    → OrderServiceImpl.accept()
        ├─ 资质校验：companion_profile.audit_status = PASSED
        ├─ UPDATE companion_order SET status=ACCEPTED, companion_id=?
        │    WHERE id=? AND status='PENDING' AND version=?
        │    （@Version 乐观锁；affectedRows=0 → 3003 已被抢）
        ├─ INSERT order_status_log
        └─ MessageService.send(...) 通知家属（M8）

表 → Mapper → Service → Controller：
  companion_order ─┐
  order_status_log ├→ CompanionOrderMapper / OrderStatusLogMapper
  order_reject_log ┤        ↓
  family_elder_relation ────┘  OrderServiceImpl（状态机闸门 + 归属校验 + 乐观锁）
                                   ↓
                              OrderController（Result<...>）
```

---

## 3 · 状态机

```
PENDING ──accept──→ ACCEPTED ──start──→ IN_SERVICE ──complete──→ COMPLETED ──review──→ REVIEWED
   │                    │
   └──cancel────────────┘
                    （均落到 CANCELLED 终态）

ADMIN 专属：forceTerminal（仅允许 COMPLETED / CANCELLED）—— 纠纷仲裁路径（M9）
```

| 非法转移 | 错误码 |
|---|---|
| 非 PENDING 接单 / 非 ACCEPTED 开始 / 非 IN_SERVICE 完成 | `3002 ORDER_STATUS_ILLEGAL` |
| 已完成订单取消 | `3006 ORDER_CANNOT_CANCEL` |
| 就诊时间早于当前 | `3005 ORDER_TIME_INVALID` |
| 服务小结含诊断/处方/用药建议 | `3007 ORDER_SUMMARY_ILLEGAL` |
| 并发抢单失败 | `3003 ORDER_ALREADY_TAKEN` |
| 非订单相关方访问 | `3004 ORDER_NO_PERMISSION` |

**实现要点**
- 每个流转方法**先判状态（3002）再判身份（4003/3004）**，保证「状态闸门先于任何写操作」。
- 接单走 `@Version` 乐观锁；其余流转走 `UPDATE ... WHERE status = 期望值` 条件更新，`affectedRows=0` 即抛 3002。
- 每次流转写 `order_status_log`，`operatorName` 取快照（不随用户改名而变）。

---

## 4 · 验收清单

| # | 验收项 | 状态 |
|---|---|---|
| 1 | `OrderServiceTest` 全绿（含 50 并发抢单） | ✅ |
| 2 | `OrderAccessMatrixTest` 全绿（4 角色 × 关键接口） | ✅ |
| 3 | `e2e_order.py` 跑通：注册→登录→下单→接单→服务中→完成→评价 | ✅ |
| 4 | 状态机 5 状态 + 1 终态，禁止跳级/回退 | ✅ 条件更新保证 |
| 5 | 合规红线：服务小结拦截诊断/处方/用药建议 | ✅ `ComplianceCheckUtil` 命中即拒并回显命中词 |
| 6 | 归属校验：家属 A 不能读家属 B 的订单 | ✅ `requireInvolved` 在 Service 层 |
| 7 | 订单号并发不重复 | ✅ Redis INCR + 2 天 TTL |
| 8 | commit 落地 | ✅ `666f108 / c883c52 / 5afb18a` |

---

## 5 · 遗留与风险

- `e2e_order.py` 会残留数据（站内信 907 行 vs 种子 72），需在 M12 统一清理策略。
