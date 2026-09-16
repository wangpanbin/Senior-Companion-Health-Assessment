# M9 设计评审 · 管理后台

- **模块**：M9（主路径 B）
- **评审时间**：2026-09-16（依落地代码反向补齐）
- **关联**：`docs/api/08-admin.md`、`PLAN_BACKEND.md §8 M9`
- **代码**：`controller/admin/AdminController`、`service/impl/AdminServiceImpl`、`mapper/AdminReadMapper`、`constant/OperType`、`constant/OperTargetType`

---

## 1 · 接口表

**整类 `@PreAuthorize("hasRole('ADMIN')")` 写在类上** —— 管理后台没有「部分接口开放给其他角色」的例外，
逐个方法标反而容易漏。

| # | 子模块 | 方法 | 路径 | 入参 | 出参 |
|---|---|---|---|---|---|
| 1 | 资质审核 | GET | `/api/admin/companion/audit` | `AdminAuditQuery` | `AuditPageVO` |
| 2 | 资质审核 | GET | `/api/admin/companion/audit/{id}` | path | `AuditApplicationVO` |
| 3 | 资质审核 | POST | `/api/admin/companion/audit/{id}` | `AuditDecisionDTO` | `AuditDecisionResultVO` |
| 4 | 用户管理 | GET | `/api/admin/user` | `AdminUserQuery` | `PageResult<AdminUserVO>` |
| 5 | 用户管理 | POST | `/api/admin/user/{id}/disable` | `UserDisableDTO` | `UserStatusResultVO` |
| 6 | 用户管理 | POST | `/api/admin/user/{id}/enable` | `UserEnableDTO` | `UserStatusResultVO` |
| 7 | 用户管理 | POST | `/api/admin/user/{id}/reset-password` | `ResetPasswordDTO` | `ResetPasswordResultVO` |
| 8 | 订单管理 | GET | `/api/admin/order` | `AdminOrderQuery` | `PageResult<AdminOrderVO>` |
| 9 | 订单管理 | POST | `/api/admin/order/{id}/arbitrate` | `ArbitrateDTO` | `ArbitrateResultVO` |
| 10 | 投诉处理 | GET | `/api/admin/complaint` | `AdminComplaintQuery` | `PageResult<ComplaintVO>` |
| 11 | 投诉处理 | POST | `/api/admin/complaint/{id}/handle` | `ComplaintHandleDTO` | `ComplaintHandleResultVO` |
| 12 | 操作日志 | GET | `/api/admin/oper-log` | `OperLogQuery` | `PageResult<OperLogVO>` |

---

## 2 · 数据流

```
所有写操作统一形态：
  AdminController(@PreAuthorize ADMIN)
    → AdminServiceImpl.xxx
        ① 参数与状态校验
        ② 业务表变更（条件更新，防并发覆盖）
        ③ **writeOperLog(operType, targetType, targetId, ...)** ← 必写，六处调用
        ④ MessageService.send(...) 通知当事用户（M8 双发）

纠纷仲裁（接口 9）的特殊路径：
  AdminServiceImpl.arbitrate()
    ├─ 先读一次拿 beforeStatus（用于日志）
    ├─ orderService.forceTerminal(id, target, remark)
    │     仅允许 COMPLETED / CANCELLED；条件更新防并发覆盖
    ├─ writeOperLog(ARBITRATE_ORDER, ORDER, ...)
    └─ M8 双发消息（通知家属 + 陪诊员）

表 → Mapper → Service → Controller：
  companion_audit_record ─┐
  sys_user               ─┤
  companion_order        ─┼→ AdminReadMapper + 各业务 Mapper
  complaint              ─┤        ↓
  internal_message       ─┘   AdminServiceImpl（校验 → 变更 → 日志 → 通知）
  admin_oper_log ─────────────────┘（只增不改）
```

**`writeOperLog` 调用点**（六处，覆盖全部写操作）：
`AUDIT_COMPANION`（审核）· `DISABLE_USER`（封禁）· `ENABLE_USER`（解封）·
`RESET_PASSWORD`（重置密码）· `ARBITRATE_ORDER`（仲裁）· `HANDLE_COMPLAINT`（处理投诉）。

---

## 3 · 状态机

### 3.1 资质审核（`AuditStatus`）

```
PENDING（待审）──decide──→ APPROVED（通过） / REJECTED（驳回）
```

| 非法情况 | 错误码 |
|---|---|
| 对非 PENDING 记录再审核 | `8001 AUDIT_STATUS_ILLEGAL` |
| 驳回未填原因 | `8003 AUDIT_REASON_REQUIRED` |

### 3.2 账号封禁（`SysUser`）

```
正常 ──disable──→ 已封禁（isBanned=1 + 密码版本递增）
  ↑                              │
  └────────enable────────────────┘
```

| 非法情况 | 错误码 |
|---|---|
| 封禁管理员账号 | `8002 CANNOT_DISABLE_ADMIN` |

**封禁的连锁反应**：`disableUser` 同时做两件事 —— 打封禁标记（`isBanned`）与递增密码版本。
`JwtAuthenticationFilter` 中 **封禁判定必须排在密码版本之前**，否则被封用户会落进「版本过期」分支
按未认证放行，前端只提示「请重新登录」，用户反复登录反复被拒却永远不知道原因。

### 3.3 订单强制终态（M9 专属路径）

```
任意非终态 ──arbitrate──→ COMPLETED / CANCELLED（仅此二者）
```

条件更新（`WHERE status = 期望值`）；`affectedRows=0` 抛 3002。这是**唯一**允许 ADMIN 改订单状态的入口（计划 R-M9）。

---

## 4 · 验收清单

| # | 验收项 | 状态 |
|---|---|---|
| 1 | 审核 / 用户管理 / 订单管理 / 操作日志 四子模块 | ✅（+投诉处理共五块） |
| 2 | `admin_oper_log` 必写 | ✅ 六处 `writeOperLog` |
| 3 | 纠纷处理强制终态 | ✅ `arbitrate` → `forceTerminal` |
| 4 | 封禁 + JWT 黑名单 | ✅ `disableUser` + `isBanned` 前置判定 |
| 5 | 双发消息（家属 + 陪诊员） | ✅ `MessageService` |
| 6 | 单测 / 越权 / e2e | ❌ `AdminServiceTest` 缺 / ❌ `AdminAccessMatrixTest` 缺 / ✅ `e2e_admin.py` |
| 7 | 不能封禁管理员 | ✅ `CANNOT_DISABLE_ADMIN(8002)` |

---

## 5 · 遗留与风险

- `AdminServiceTest` / `AdminAccessMatrixTest` 缺（审计报告 P0 待办）。
- 强制终态路径的**并发保护**已用条件更新，但缺单测锁定，建议优先补。
