# 08 管理后台

> 覆盖模块：**M9 管理后台**
> 负责人：A（前端）+ B（后端）+ D（操作日志表）
> 归属迭代：迭代四 W13 启动，迭代五 W14 完成（W13 竞讲交付「管理后台 v1」）
> 前置依赖：M3 用户与档案、M4 陪诊订单、M7 评价与投诉
> 全局约定见 [README.md](./README.md)

> ## 硬性约束
> 1. 本组接口**仅 `ADMIN` 可访问**，其他角色一律 `403`。
> 2. **每一次写操作都必须写入 `admin_oper_log`**，做到「谁 / 何时 / 对谁 / 做了什么」可追溯。
> 3. 只有管理员可通过「纠纷处理」强制改变订单终态。

---

## 一、数据模型

### AuditApplicationVO（资质申请）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 申请 ID |
| `userId` | Long | 申请人用户 ID |
| `username` | String | 用户名 |
| `realName` | String | 真实姓名 |
| `phone` | String | **脱敏**手机号 |
| `idCard` | String | **脱敏**身份证号（仅列表用；详情按需单独申请完整信息） |
| `serviceArea` | String | 服务区域 |
| `availableTime` | String | 可服务时段 |
| `certificates` | Array | 证件材料 `[{ name, url }]` |
| `auditStatus` | String | `PENDING` / `APPROVED` / `REJECTED` |
| `auditStatusLabel` | String | 中文状态 |
| `rejectReason` | String | 驳回原因 |
| `submitTime` | String | 提交时间 |
| `auditTime` | String | 审核时间 |
| `auditorName` | String | 审核人 |

### AdminUserVO（用户管理列表项）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 用户 ID |
| `username` | String | 用户名 |
| `nickname` | String | 昵称 |
| `phone` | String | **脱敏**手机号 |
| `role` | String | 角色 |
| `roleLabel` | String | 中文角色 |
| `status` | String | `NORMAL` / `DISABLED` |
| `orderCount` | Integer | 关联订单数 |
| `createTime` | String | 注册时间 |
| `lastLoginTime` | String | 最后登录时间 |

### OperLogVO（操作日志）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 日志 ID |
| `operatorId` | Long | 操作人 ID |
| `operatorName` | String | 操作人姓名 |
| `operType` | String | 操作类型，见下表 |
| `operTypeLabel` | String | 中文类型 |
| `targetType` | String | 目标类型：`USER` / `ORDER` / `COMPANION` / `COMPLAINT` |
| `targetId` | Long | 目标 ID |
| `targetDesc` | String | 目标描述（如订单号） |
| `beforeStatus` | String | 变更前状态 |
| `afterStatus` | String | 变更后状态 |
| `remark` | String | 操作备注 / 原因 |
| `ip` | String | 操作 IP |
| `operTime` | String | 操作时间 |

### 操作类型枚举

| 传值 | 中文 |
|---|---|
| `AUDIT_COMPANION` | 审核陪诊员资质 |
| `DISABLE_USER` | 封禁用户 |
| `ENABLE_USER` | 解封用户 |
| `RESET_PASSWORD` | 重置密码 |
| `ARBITRATE_ORDER` | 订单纠纷处理 |
| `HANDLE_COMPLAINT` | 处理投诉 |
| `PUBLISH_NOTICE` | 发布系统公告 |

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | GET | `/api/admin/companion/audit` | ADMIN | 资质申请列表（按状态） |
| 2 | GET | `/api/admin/companion/audit/{id}` | ADMIN | 资质申请详情 |
| 3 | POST | `/api/admin/companion/audit/{id}` | ADMIN | 审核（通过 / 驳回） |
| 4 | GET | `/api/admin/user` | ADMIN | 用户列表 |
| 5 | POST | `/api/admin/user/{id}/disable` | ADMIN | 封禁用户 |
| 6 | POST | `/api/admin/user/{id}/enable` | ADMIN | 解封用户 |
| 7 | POST | `/api/admin/user/{id}/reset-password` | ADMIN | 重置密码 |
| 8 | GET | `/api/admin/order` | ADMIN | 全部订单 |
| 9 | POST | `/api/admin/order/{id}/arbitrate` | ADMIN | 纠纷处理（强制终态） |
| 10 | GET | `/api/admin/complaint` | ADMIN | 投诉列表 |
| 11 | POST | `/api/admin/complaint/{id}/handle` | ADMIN | 处理投诉 |
| 12 | GET | `/api/admin/oper-log` | ADMIN | 操作日志查询 |

---

## 1. 资质申请列表

`GET /api/admin/companion/audit`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `auditStatus` | String | 否 | `PENDING` / `APPROVED` / `REJECTED`，不传为全部 |
| `keyword` | String | 否 | 姓名 / 手机号模糊搜索 |
| `startDate` / `endDate` | String | 否 | 提交时间区间 |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 12,
    "page": 1,
    "size": 10,
    "pages": 2,
    "counts": { "PENDING": 5, "APPROVED": 6, "REJECTED": 1 },
    "records": [
      {
        "id": 501,
        "userId": 10099,
        "username": "lisi",
        "realName": "李四",
        "phone": "139****6677",
        "idCard": "4601**********1234",
        "serviceArea": "海口市美兰区",
        "availableTime": "周一至周五 08:00–18:00",
        "certificates": [{ "name": "身份证正面", "url": "/uploads/202609/id1.jpg" }],
        "auditStatus": "PENDING",
        "auditStatusLabel": "待审核",
        "submitTime": "2026-09-10 09:00:00"
      }
    ]
  }
}
```

### 实现要点

- 额外返回 `counts` 字段，让管理端「三种状态及数量」一次拿全（M9 验收项）。

---

## 2. 资质申请详情

`GET /api/admin/companion/audit/{id}`　权限：**ADMIN**

### 响应

同列表项，额外包含完整证件列表与 `remark`。

### 实现要点

- **查看完整身份证号属于敏感操作**：建议单独接口 + 记录日志；
  若时间紧张，本接口返回脱敏串，完整信息由管理员在看证件图片时人工核对（更安全的做法）。

---

## 3. 审核陪诊员资质

`POST /api/admin/companion/audit/{id}`　权限：**ADMIN**

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `approved` | Boolean | ✓ | — | `true` 通过 / `false` 驳回 |
| `rejectReason` | String | 驳回时必填 | 5–200 字符 | 驳回原因 |
| `remark` | String | 否 | ≤ 200 字符 | 内部备注 |

```json
{ "approved": false, "rejectReason": "身份证照片不清晰，请重新上传", "remark": "第二次提交仍模糊" }
```

### 响应

```json
{
  "code": 200,
  "message": "已驳回",
  "data": { "applicationId": 501, "auditStatus": "REJECTED" }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 8003 | 驳回时未填写原因 |
| 8001 | 资质审核状态不合法（已是终态不可再审核） |

### 实现要点

1. 驳回必须填原因，否则 `8003`。
2. 审核**通过**时：把用户角色升级为 `COMPANION`，写入 `companion_profile`，`audit_status = 'APPROVED'`。
3. 审核**驳回**时：用户角色不变，`reject_reason` 落库。
4. 无论通过还是驳回，都向申请人发站内信（`AUDIT_RESULT`，M8）。
5. **必须写 `admin_oper_log`**：`operType = AUDIT_COMPANION`，`beforeStatus` / `afterStatus` 记录状态变化。

---

## 4. 用户列表

`GET /api/admin/user`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `role` | String | 否 | 角色筛选 |
| `status` | String | 否 | `NORMAL` / `DISABLED` |
| `keyword` | String | 否 | 用户名 / 昵称 / 手机号搜索 |
| `startDate` / `endDate` | String | 否 | 注册时间区间 |

### 响应

结构同分页约定，`records` 为 `AdminUserVO` 数组。

### 实现要点

- 列表手机号**必须脱敏**（验收项）。

---

## 5. 封禁用户

`POST /api/admin/user/{id}/disable`　权限：**ADMIN**

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `reason` | String | ✓ | 5–200 字符 | 封禁原因 |

### 响应

```json
{ "code": 200, "message": "已封禁该用户", "data": { "userId": 10099, "status": "DISABLED" } }
```

### 错误场景

| code | 场景 |
|---|---|
| 8002 | 不能封禁管理员账号 |
| 2004 | 该用户已处于封禁状态 |

### 实现要点（M9 验收关键项）

- 封禁后**该用户已签发的 token 必须立即失效**，不能等 token 自然过期。
  实现方式二选一：
  ① token 中包含 `user:version`，封禁时 `INCR user:version:{userId}`，过滤器中比对不一致即拒绝；
  ② 封禁时把该用户所有在线 token 的 `jti` 批量加入 Redis 黑名单。
- 封禁时向该用户发站内信说明原因。
- 写 `admin_oper_log`：`DISABLE_USER`，`beforeStatus = NORMAL`，`afterStatus = DISABLED`。

---

## 6. 解封用户

`POST /api/admin/user/{id}/enable`　权限：**ADMIN**

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `remark` | String | 否 | 备注 |

### 响应

```json
{ "code": 200, "message": "已解封该用户", "data": { "userId": 10099, "status": "NORMAL" } }
```

---

## 7. 重置密码

`POST /api/admin/user/{id}/reset-password`　权限：**ADMIN**

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `remark` | String | ✓ | 重置原因（如「用户来电请求重置」） |

### 响应

```json
{
  "code": 200,
  "message": "密码已重置为默认密码，请提醒用户首次登录后修改",
  "data": { "defaultPassword": "Nl@123456" }
}
```

### 实现要点

- 默认密码同样 BCrypt 加密入库。
- **强制用户下次登录后必须改密**（`need_change_password = 1`）。
- ⚠️ 本接口**绝不返回**原密码（数据库里是 BCrypt 哈希，本就不可逆）。
- 写 `admin_oper_log`：`RESET_PASSWORD`。

---

## 8. 全部订单

`GET /api/admin/order`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `status` | String | 否 | 状态筛选，可多值 |
| `keyword` | String | 否 | 订单号 / 医院 / 姓名 |
| `startDate` / `endDate` | String | 否 | 下单时间区间 |
| `hasComplaint` | Boolean | 否 | 只看有投诉的订单（纠纷优先） |

### 响应

结构同分页约定，`records` 为 `OrderVO`，额外包含 `hasComplaint` 与 `complaintId`。

---

## 9. 纠纷处理（强制终态）

`POST /api/admin/order/{id}/arbitrate`　权限：**ADMIN**

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `targetStatus` | String | ✓ | `COMPLETED` / `CANCELLED` | 强制进入的终态 |
| `result` | String | ✓ | 10–500 字符 | 处理结果说明 |
| `refundToFamily` | Boolean | 否 | — | 是否退费给家属（**线下结算记录，不做在线退款**） |
| `penaltyToCompanion` | Boolean | 否 | — | 是否对陪诊员计违规 |

```json
{
  "targetStatus": "CANCELLED",
  "result": "经核实陪诊员迟到 40 分钟且未提前告知，本次订单取消，服务费不结算。",
  "refundToFamily": true,
  "penaltyToCompanion": true
}
```

### 响应

```json
{
  "code": 200,
  "message": "纠纷已处理",
  "data": {
    "orderId": 1001,
    "status": "CANCELLED",
    "statusLabel": "已取消",
    "handleTime": "2026-09-21 10:00:00"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3002 | 订单**已是终态**（`REVIEWED` / `CANCELLED`），不可再处理 |
| 400 | `targetStatus` 不是 `COMPLETED` / `CANCELLED` |

### 实现要点（M9 验收关键项）

1. `targetStatus` 只允许 `COMPLETED` 或 `CANCELLED`（对应 `OrderStatus.isAdminForceable()`）。
2. **起点限「任意非终态」**：`PENDING` / `ACCEPTED` / `IN_SERVICE` / `COMPLETED` 四者。
   `REVIEWED` / `CANCELLED` 是终态，调用返回 `3002`（`OrderStatus.isTerminal()`）。
   其中 `COMPLETED → CANCELLED` 是**合法**的强制边（服务已完成但事后判定有责、需标记线下退费），
   不要因为起点是"已完成"就误判为非法。
3. 强制终态**绕过状态机的正向流转规则**，但必须：
   - 写入 `order_status_log`，`remark` 标注「管理员强制变更」；
   - **写入 `admin_oper_log`**，含 `beforeStatus` / `afterStatus`；
   - 向家属与陪诊员**各发一条站内信**（`ORDER_CANCELLED` / 结果通知）。
4. 已被强制终态化的订单，后续不能再走正向流转。

---

## 10. 投诉列表

`GET /api/admin/complaint`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `status` | String | 否 | `PENDING` / `PROCESSING` / `RESOLVED` / `REJECTED` |
| `type` | String | 否 | 投诉类型 |
| `startDate` / `endDate` | String | 否 | 提交时间区间 |

### 响应

结构同分页约定，`records` 为 `ComplaintVO`。

---

## 11. 处理投诉

`POST /api/admin/complaint/{id}/handle`　权限：**ADMIN**

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `status` | String | ✓ | `PROCESSING` / `RESOLVED` / `REJECTED` | 目标状态 |
| `handleResult` | String | ✓ | 10–500 字符 | 处理结果说明 |
| `penaltyToTarget` | Boolean | 否 | — | 是否对被投诉人计违规 |

### 响应

```json
{
  "code": 200,
  "message": "投诉已处理",
  "data": { "complaintId": 3001, "status": "RESOLVED", "handleTime": "2026-09-21 10:30:00" }
}
```

### 实现要点

- 写 `admin_oper_log`：`HANDLE_COMPLAINT`。
- 向投诉人与被投诉人各发一条站内信（`COMPLAINT_HANDLED`）。
- 状态只能正向流转：`PENDING → PROCESSING → RESOLVED / REJECTED`，不可回退。

---

## 12. 操作日志查询

`GET /api/admin/oper-log`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `operatorId` | Long | 否 | 按操作人筛选 |
| `operType` | String | 否 | 按操作类型筛选 |
| `targetType` / `targetId` | String / Long | 否 | 按目标对象筛选 |
| `startTime` / `endTime` | String | 否 | 操作时间区间（精确到秒），格式 `yyyy-MM-dd HH:mm:ss`；两个边界**都含端点**。也兼容 `yyyy-MM-ddTHH:mm:ss`、`yyyy-MM-dd HH:mm` 与纯日期 `yyyy-MM-dd` |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 42,
    "page": 1,
    "size": 10,
    "pages": 5,
    "records": [
      {
        "id": 9001,
        "operatorId": 10001,
        "operatorName": "管理员",
        "operType": "ARBITRATE_ORDER",
        "operTypeLabel": "订单纠纷处理",
        "targetType": "ORDER",
        "targetId": 1001,
        "targetDesc": "NL20260915000001",
        "beforeStatus": "IN_SERVICE",
        "afterStatus": "CANCELLED",
        "remark": "经核实陪诊员迟到 40 分钟且未提前告知",
        "ip": "192.168.1.10",
        "operTime": "2026-09-21 10:00:00"
      }
    ]
  }
}
```

### 实现要点

- 日志表**只增不改不删**：代码层面不提供 UPDATE / DELETE 接口。
- 时间区间与操作类型组合筛选结果必须正确（验收项）。

---

## 三、验收标准（M9）

- [ ] 审核列表能正确显示待审核 / 已通过 / 已驳回三种状态及数量（`counts` 字段）
- [ ] 封禁用户后，该用户任意接口请求**立即**返回 403（不必等 token 过期）
- [ ] 封禁管理员返回 `8002`
- [ ] 驳回未填原因返回 `8003`
- [ ] 每次审核 / 封禁 / 纠纷处理，`admin_oper_log` 都新增一条记录（含操作人 ID、目标 ID、动作、时间）
- [ ] 纠纷处理可把订单从任意**非终态**（`PENDING` / `ACCEPTED` / `IN_SERVICE` / `COMPLETED`）强制置为 `COMPLETED` 或 `CANCELLED`，并给双方各发一条站内信
- [ ] 对已是终态的订单（`REVIEWED` / `CANCELLED`）调用纠纷处理 → `3002`
- [ ] 非 ADMIN 角色访问任意管理端接口 → `403`
- [ ] 日志页支持按时间区间 + 操作类型筛选，结果正确
- [ ] 用户列表手机号已脱敏
- [ ] 投诉状态只能正向流转，回退返回 `409`
