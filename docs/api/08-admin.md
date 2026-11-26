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
| `realName` | String | **脱敏**姓名（`李四` → `李*`） |
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
| `targetType` | String | 目标类型：`USER` / `ORDER` / `COMPANION` / `COMPLAINT` / `REVIEW`（E4） / `OPERATION_PARAM`（P2-3） |
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
| `REVIEW_RULING` | 评价有效性裁定（E4 评价公信力闭环） |
| `PUBLISH_NOTICE` | 发布系统公告 |
| `SETTLE_ORDER` | 订单结算核定（P2-1 结算闭环） |
| `UPDATE_OPERATION_PARAM` | 修改运营参数（P2-3 运营参数可调化） |

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
| 13 | POST | `/api/admin/review/{id}/validity` | ADMIN | 评价有效性裁定（E4） |
| 14 | GET | `/api/admin/fulfillment/summary` | ADMIN | 履约风险四档计数（V3.1） |
| 15 | GET | `/api/admin/fulfillment/risk-list` | ADMIN | 风险订单列表（V3.1） |
| 16 | POST | `/api/admin/order/{id}/reassign` | ADMIN | 改派陪诊员（V3.1） |
| 17 | GET | `/api/admin/operations/overview` | ADMIN | 平台运营概览（V3.5） |
| 18 | GET | `/api/admin/operations/exceptions` | ADMIN | 异常中心（V3.5） |
| 19 | GET | `/api/admin/operations/trend` | ADMIN | 运营趋势（V3.5） |
| 20 | GET | `/api/admin/operations/health` | ADMIN | 平台健康度（V3.5） |
| 21 | GET | `/api/admin/operations/gap` | ADMIN | 供给时段缺口分析（V3.6） |
| 22 | GET | `/api/admin/operations/effect` | ADMIN | 干预效果对比（V3.6） |
| 23 | GET | `/api/admin/metrics` | ADMIN | 指标字典只读导出（V3.6） |
| 24 | GET | `/api/admin/settlement` | ADMIN | 结算核对列表（P2-1） |
| 25 | POST | `/api/admin/settlement/{orderId}/confirm` | ADMIN | 单条结算核定（P2-1） |
| 26 | POST | `/api/admin/settlement/batch-confirm` | ADMIN | 批量结算核定（P2-1） |
| 27 | GET | `/api/admin/operation-param` | ADMIN | 运营参数列表（P2-3） |
| 28 | PUT | `/api/admin/operation-param/{code}` | ADMIN | 修改运营参数（P2-3） |
| 29 | GET | `/api/admin/operation-param/logs` | ADMIN | 参数变更留痕（P2-3） |
| 30 | GET | `/api/admin/review` | ADMIN | 评价巡查列表（E4） |

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
- 列表姓名**同样必须脱敏**（`MaskUtil.name`，`李四` → `李*`）——`/api/admin/**` 属于「一律脱敏」的适用范围（AGENTS.md §0.1）。
  - ⚠️ **不要跟着一起改**：`AuditApplicationVO.realName` 保持明文 —— 资质审核要核对证件原件，那是本文件已明写的场景。

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
| `newPassword` | String | 否 | 管理员指定的新密码，6-32 位且需同时包含字母与数字。不传则沿用部署配置的默认口令 |

> 传 `newPassword` 时，用户将用**该口令**登录；平台没有短信通道，口令由管理员线下/电话转交。
> 不传时用的是部署配置的默认口令（`nianglin.security.default-password`）—— 用户同样需要向管理员索取。

### 响应

```json
{
  "code": 200,
  "message": "密码已重置，请提醒用户使用新密码登录并在首次登录后修改",
  "data": { "userId": 10099, "needChangePassword": true }
}
```

> ⚠️ **响应体不含任何口令字段** —— 这是安全验收判据
> （`docs/security/安全评估报告-2026-10-18.md` §8.2 P0-3 / §10.2 F-04）。
> 管理员要转交口令给用户，由**前端**用管理员刚输入的值展示，服务端不回传、不记录。

### 实现要点

- 密码来源二选一：传了 `newPassword` 就用它，否则用部署配置 `nianglin.security.default-password`（环境变量 `DEFAULT_RESET_PASSWORD`）。两者都 BCrypt 加密入库。
- **强制用户下次登录后必须改密**（`need_change_password = 1`）。
- ⚠️ **响应体不含任何口令字段**（含默认口令与管理员自设口令）—— 详见上面「响应」段的安全判据说明。
- ⚠️ **站内信正文不含任何口令**（无论管理员自设还是默认口令）。站内信是落库数据，含口令等于把口令长期写进数据库。
- ⚠️ **平台没有短信通道**：前端**不得**出现「已通过短信发送给用户」这类文案 —— 此前正是这句假反馈，配合「站内信不含口令」，导致用户收到通知却永远不知道自己该用哪个密码登录，账号被永久锁死。现在由管理员亲手设置新口令并线下/电话转交。
- ⚠️ 本接口**绝不返回**原密码（数据库里是 BCrypt 哈希，本就不可逆）。
- ⚠️ 明文口令**不入日志**（`admin_oper_log.remark` 只记原因，不记口令）。
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

## 13. 评价有效性裁定（E4）

`POST /api/admin/review/{id}/validity`　权限：**ADMIN**

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `isValid` | Boolean | ✓ | **仅接受 `false`**（恢复有效不在本期范围） | 裁定结论 |
| `reason` | String | ✓ | 10–200 字符 | 裁定理由（对双方可见） |

### 业务规则

1. 评价必须存在，否则 `6005`。
2. **单向裁定**：本接口只做「有效 → 无效」。恢复有效走线下沟通 + 备注记录。
3. 已裁定（`is_valid=0`）的评价再次裁定返回 `409`。
4. 同一事务内：① `order_review.is_valid = 0`（乐观条件 `eq(is_valid, 1)`）② Review module 内部重算陪诊员评分并删除 Redis 缓存 ③ 通过共享审计 module 写 `admin_oper_log`（`OperType.REVIEW_RULING` + `OperTargetType.REVIEW`）④ 向评价家属 + 陪诊员各发一条 `REVIEW_INVALIDATED` 站内信。
5. 裁定禁止修改 `score` / `content` / `tags` / `companion_reply` 中任何内容。
6. 不要求存在关联申诉（管理员可主动巡查裁定）。

### 响应

```json
{
  "code": 200,
  "message": "裁定已生效",
  "data": {
    "reviewId": 30001,
    "isValid": false,
    "companionId": 307
  }
}
```

---

## 14. 履约风险计数（V3.1 · T6）

`GET /api/admin/fulfillment/summary`　权限：**ADMIN**　只读，**不写操作日志**

### 响应

| 字段 | 类型 | 说明 |
|---|---|---|
| `manualRequired` | int | 需要人工介入的订单数 |
| `urgent` | int | 履约风险较高的订单数 |
| `warning` | int | 等待时间较长的订单数 |
| `normal` | int | 正常等待的订单数 |

```json
{ "code": 200, "message": "操作成功", "data": { "manualRequired": 1, "urgent": 3, "warning": 5, "normal": 12 } }
```

### 实现要点

- **四个字段恒定全部返回**，某档为 0 时返回 0 而不是省略字段。
  「这一档 0 单」和「这一档没统计」在卡片上必须能被区分，字段类型用原语 `int` 从根上杜绝省略。
- **只统计 `status = 'PENDING'`**，与 §15 的候选集共用同一处定义。
  已接单的订单风险已回落，计入等于凭空放大问题。
- 分组键做 `COALESCE` 兜底：列是 `NOT NULL DEFAULT 'NORMAL'`，正常数据不会有 NULL，
  但人工改库会；分出一个 NULL 组会让前端无键可取。
- 未匹配到枚举的脏值并入 `normal`，保证**四档之和 = 待接单总数**——
  对不上账的看板没人敢用。

---

## 15. 风险订单列表（V3.1 · T6）

`GET /api/admin/fulfillment/risk-list`　权限：**ADMIN**　只读，**不写操作日志**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|:--:|---|
| `page` / `size` | Long | 否 | 沿用分页基类，`size` 上限 100 |
| `risk` | String | 否 | `NORMAL` / `WARNING` / `URGENT` / `MANUAL_REQUIRED` 之一；**缺省返回全部等级** |

**排序固定为「风险等级降序 → 等待时长降序 → id 降序」，不开放 `sortField`**：
这一页面的唯一正确读法就是先看最坏的几单。

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 9,
    "records": [
      {
        "orderId": 1001,
        "orderNo": "NL20**********0001",
        "elderName": "张*海",
        "hospital": "市第一人民医院",
        "department": "心内科",
        "visitTime": "2026-10-18 09:00:00",
        "fulfillmentRisk": "URGENT",
        "fulfillmentRiskLabel": "履约风险较高",
        "waitMinutes": 45,
        "urgeCount": 2,
        "adminCoordinated": false,
        "createTime": "2026-10-17 09:30:00"
      }
    ]
  }
}
```

### 错误场景

| code | 触发条件 |
|---|---|
| 400 | `risk` 不是四档之一（**不静默返回全量**） |

### 实现要点

- **姓名与订单号一律脱敏**：订单详情的全名豁免只适用于已过归属校验的详情页。
  列表是可被反复扫读的界面，一次投屏就把多名老人的姓名连同订单号带走了。
  需要核对全名时走行内「查看详情」（订单详情接口，相关方口径）。
- **排序整段走 `last()`**：MyBatis-Plus 的 `orderBy*` 只接受列，
  塞不进 `FIELD(...)` 这样的表达式；而「既 orderBy 又 last」会产出两个 ORDER BY 的非法 SQL。
  因此这一查询**不能再调 `orderBy*`**。
- **等待时长降序 = `create_time` 升序**：等得越久下单越早，等待时长本身不落库。
- 不在四档内的脏值在 `FIELD` 中返回 0，会排在**最前**——异常数据优先暴露。
- `adminCoordinated` 由事件流水批量判定（`trigger_source = 'ADMIN'`），
  覆盖协调与改派两类事件，将来新增管理员动作时不必改判定条件。

---

## 16. 改派陪诊员（V3.1 · T7）

`POST /api/admin/order/{id}/reassign`　权限：**ADMIN**

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|:--:|---|---|
| `companionId` | Long | ✓ | 目标陪诊员的用户 ID | 改派给谁 |
| `reason` | String | ✓ | 10–200 字符，且过合规词表 | 改派理由（写入审计与三方通知） |

### 响应

```json
{
  "code": 200,
  "message": "已改派",
  "data": { "orderId": 1001, "fromCompanionId": null, "toCompanionId": 302, "fulfillmentRisk": "URGENT" }
}
```

### 前置校验（顺序固定）

| # | 条件 | 失败码 |
|---|---|---|
| ① | 理由过合规词表 | `6003`（`CONTENT_SENSITIVE`） |
| ② | 订单存在 | `3001` |
| ③ | `status == PENDING` 且 `companionId IS NULL` | `3010` |
| ④ | 目标陪诊员存在 | `2007` |
| ⑤ | 目标 `auditStatus == APPROVED` | `2003` |
| ⑥ | 目标健康证未过期（`health_cert_expire ≥ 今天`，未补录放行） | `2011` |
| ⑦ | 目标 `workStatus == AVAILABLE` | `3010` |
| ⑧ | 目标未拒过该单 | `3010` |

### 边界（改动前必读）

**本端点只处理「还没人接」的订单。** 已 `ACCEPTED` / `IN_SERVICE` 的订单要换人属纠纷处理，
走 §9 的管理员仲裁通道（强制终态并通知双方），**不在本端点处理** ——
同一件事只允许存在一条改变订单归属的路径，否则事后没人说得清这笔单是怎么换的人。

### 事务边界

同一事务内四件事，任一步失败整体回滚：

1. **条件更新** `companion_id`，条件是「`status = PENDING` **且** `companion_id IS NULL`」；
2. 写履约事件 `ORDER_REASSIGNED`（`trigger_source = ADMIN`，理由入备注）；
3. 写操作审计（`OperType.REASSIGN_ORDER`，**before / after 记的是陪诊员**而不是订单状态 ——
   本操作不改状态，两栏都写 `PENDING` 会让这条日志读起来像「什么都没发生」）；
4. 发三方站内信：下单家属 / 目标陪诊员 / 原陪诊员（`fromCompanionId` 非空时）。

### 状态机

**本操作不改变 `status`**（改完仍是 `PENDING`），因此**不经过订单状态流转唯一入口**（ADR-0010）。
`status` 仍由陪诊员接单时经该入口变更。

### 并发

与陪诊员抢单竞争同一行：改派靠上面的条件更新，抢单走既有乐观锁。
数据库保证只有一个能改成功 —— 败者分别得到 `3010` / `3003`，
且改派败者**不留事件、不发通知、不写审计**（回滚干净）。

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

---

## 17. 平台运营概览（V3.5）

`GET /api/admin/operations/overview`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` | String | 否 | `yyyy-MM-dd`，闭区间，默认最近 30 天 |
| `endDate` | String | 否 | `yyyy-MM-dd`，闭区间，默认今天 |

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "periodStart": "2026-09-21", "periodEnd": "2026-10-20",
    "todayOrderCount": 128,
    "completedOrderCount": 113,
    "inServiceCount": 9,
    "pendingCount": 6,
    "fulfillmentRate": "96.40%",
    "avgAcceptMinutes": 6,
    "complaintRate": "1.20%",
    "averageScore": "4.82"
  }
}
```

| 字段 | 口径 |
|---|---|
| `todayOrderCount` | 今日新增订单数（**不随统计区间浮动** —— 它是「今天」这个事实，与区间是两个维度；口径同 `operations.today_order_count`） |
| `completedOrderCount` | 区间内完成（`COMPLETED` + `REVIEWED`） |
| `inServiceCount` | 区间内服务中（`IN_SERVICE`） |
| `pendingCount` | 区间内待接单（`PENDING`） |
| `fulfillmentRate` | **履约率** = `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)` |
| `avgAcceptMinutes` | 平均接单时间（分钟，**整数**）；无已接单订单时 `0` |
| `complaintRate` | 投诉率 = 投诉订单数 / 区间订单数 |
| `averageScore` | 平均评分（两位小数字符串） |

---

## 18. 异常中心（V3.5）

`GET /api/admin/operations/exceptions`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` | String | 否 | `yyyy-MM-dd`，闭区间，默认最近 30 天；超 366 天返 `9002` |
| `endDate` | String | 否 | `yyyy-MM-dd`，闭区间，默认今天；起止颠倒返 `9002` |

> 与 §17 概览共用同一套区间入参（`dto.DateRangeQuery`）：
> 不传即最近 30 天（含今天）。区间**只影响「用药漏服异常」这一项**
> （按 `medication_task.plan_time` 落区间内统计）；
> 「需人工介入订单」「超匹配预警时间订单」来自 V3.1 风险中心，是**当前状态**（与区间无关）；
> 「投诉率异常陪诊员」按**运营配置的回溯期**判定
> （`nianglin.operations.complaint-lookback-days`，默认 30 天），**不随请求区间变化**。

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "total": 16,
    "items": [
      { "type": "MANUAL_REQUIRED", "typeLabel": "需人工介入订单", "count": 3,
        "level": "DANGER", "linkUrl": "/admin/fulfillment", "referenceId": null,
        "description": "履约风险已达人工介入阈值，需要管理员协调" },
      { "type": "MATCH_WARNING", "typeLabel": "超匹配预警时间订单", "count": 6,
        "level": "WARNING", "linkUrl": "/admin/fulfillment", "referenceId": null,
        "description": "等待时间超过预警阈值，系统正在扩大匹配范围" },
      { "type": "COMPANION_COMPLAINT_RATE", "typeLabel": "投诉率异常陪诊员", "count": 2,
        "level": "WARNING", "linkUrl": "/admin/complaint", "referenceId": null,
        "description": "回溯期内投诉率超过阈值，需关注服务质量" },
      { "type": "MEDICATION_MISSED", "typeLabel": "用药漏服异常", "count": 5,
        "level": "WARNING", "linkUrl": "/admin/order", "referenceId": 1019,
        "description": "区间内漏服任务数超过阈值，最近一条见最近订单" }
    ]
  }
}
```

| 字段 | 说明 |
|---|---|
| `total` | 四类异常的数量之和 |
| `items[].type` | `MANUAL_REQUIRED` / `MATCH_WARNING` / `COMPANION_COMPLAINT_RATE` / `MEDICATION_MISSED` |
| `items[].typeLabel` | 中文名 |
| `items[].count` | 数量 |
| `items[].level` | `DANGER` / `WARNING` / `INFO` |
| `items[].linkUrl` | **真实存在**的处理页路由（前端**直接用这个值跳转**，不要自己拼） |
| `items[].referenceId` | 可选定位 ID（如最近异常订单 ID）；无则 `null` |
| `items[].description` | 一句话说明 |

### 同源铁律（决策 5.1）

前两项的数量**必须等于** V3.1 的 `/api/admin/fulfillment/summary`：

| 异常 | 对应字段 |
|---|---|
| 需人工介入订单 | `manualRequired` |
| 超匹配预警时间订单 | `warning + urgent` |

异常中心**聚合** V3.1 的风险中心，**不另建一套风险判定** ——
两套判定必然漂移（这正是 V3.2 在用药三桶上踩过的坑）。
该同源关系由 `OperationsExceptionParityTest` 锁死。

### 可达性（决策 5.4）

每个 `linkUrl` 都必须指向**真实存在**的前端路由。
「看到了但处理不了」是本版最典型的假绿 —— E2E 逐个点开验证。

---

## 19. 运营趋势（V3.5）

`GET /api/admin/operations/trend`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `dimension` | String | 否 | `ORDER` / `COMPANION_ACTIVE` / `FULFILLMENT_RATE` / `COMPLAINT` / `REVIEW` / `MEDICATION`；缺省 `ORDER`；非法值 `400` |
| `range` | String | 否 | `TODAY` / `LAST_7D` / `LAST_30D` / `CUSTOM`；缺省 `LAST_30D`；非法值 `400` |
| `startDate` / `endDate` | String | 否 | `range = CUSTOM` 时必填；闭区间，上限 366 天（超限 `9002`） |

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "dimension": "ORDER", "range": "LAST_7D", "granularity": "DAY",
    "periodStart": "2026-10-14", "periodEnd": "2026-10-20",
    "categories": ["2026-10-14", "2026-10-15"],
    "series": [ { "name": "订单量", "data": [12.00, 9.00] } ]
  }
}
```

- `categories` 与每条 `series.data` 的长度**严格一致**，**无数据的桶补 0**
  （ECharts 遇到长度不齐的数据不报错，只是折线图静默跳段 —— 最难查的一类假绿）。
- 六条维度的口径全部登记在指标字典（`docs/spec/metric-dictionary.md`）。

---

## 20. 平台健康度（V3.5）

`GET /api/admin/operations/health`　权限：**ADMIN**

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "fulfillmentRate": "96.40%",
    "avgAcceptMinutes": "6",
    "completionRate": "88.20%",
    "complaintRate": "1.20%",
    "averageScore": "4.82",
    "activeCompanionCount": 24
  }
}
```

**六个并列指标，不给合成分**（决策 5.2）：

| 字段 | 含义 |
|---|---|
| `fulfillmentRate` | 履约率 |
| `avgAcceptMinutes` | 接单速度（分钟，**字符串**） |
| `completionRate` | 完成率（**按时**：完成时间不晚于就诊时间 + 履约时长窗口） |
| `complaintRate` | 投诉率 |
| `averageScore` | 平均评分 |
| `activeCompanionCount` | 活跃陪诊员数 |

> ⚠️ **同名不同型**：概览的 `avgAcceptMinutes` 是 Integer，本接口的是 String。
> 前端**不要**写一个通用的取值函数。
>
> ⚠️ **禁止**出现「AI 平台健康指数 96」这类无法解释的数 ——
> 除非将来存在明确的书面计算公式并登记进指标字典。

### 错误码（四个端点共用）

| 码 | 含义 |
|---|---|
| 400 | `dimension` / `range` 取值非法 |
| 401 | 未登录 |
| 403 | 非管理员 |
| 9002 | 统计区间不合法（超 366 天或起止颠倒） |

---

## 21. 供给时段缺口分析（V3.6）

`GET /api/admin/operations/gap`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `range` | String | 否 | `LAST_7D` / `LAST_30D`（缺省 `LAST_30D`）/ `CUSTOM`；非法值 `400` |
| `startDate` / `endDate` | String | 否 | `range = CUSTOM` 时必填；闭区间，上限 366 天（超限 `9002`） |

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "periodStart": "2026-09-21", "periodEnd": "2026-10-20",
    "total": 168,
    "companionsWithoutSlots": 7,
    "items": [
      { "weekday": 6, "hour": 14, "pendingUnaccepted": 3, "availableCompanions": 1, "gap": 2 }
    ]
  }
}
```

| 字段 | 说明 |
|---|---|
| `total` | 返回的桶数（7 天 × 24 小时 = 168，**空桶也返回**） |
| `companionsWithoutSlots` | **没有可解析可服务时段**的陪诊员数（不参与供给统计，如实披露数据质量） |
| `items[].weekday` | 1=周一 … 7=周日 |
| `items[].hour` | 0–23 |
| `items[].pendingUnaccepted` | 该时段内下单但至今无人接的订单数（`PENDING`，按 `visit_time` 落桶） |
| `items[].availableCompanions` | 该时段**可服务**的陪诊员数（按其可服务时段分桶） |
| `items[].gap` | `pendingUnaccepted − availableCompanions`（**> 0 即为缺口**） |

### 三条口径规则

1. **桶的粒度固定为「周内第几天 × 小时」**，与查询区间的长度无关 ——
   只有这样，「这一周和上一周的同一时段」才可比（迭代计划 §47 的闭环数据基础）。
2. **同一陪诊员在多个时段各计 1 次，但每个桶里只计 1 次**（不是按单数计）。
3. **供给数按「可服务时段」分桶**，不是按「当前 AVAILABLE 的总人数」——
   后者会让每个桶的供给数都一样，指标没有信息量。
   没能解析出时段的陪诊员**不进任何桶**，并由 `companionsWithoutSlots` 如实披露。

> 这正是迭代计划 §47 那个闭环的数据基础：
> 「订单无人接 → 统计发现 14:00–16:00 陪诊员数量不足 → 管理员调整排班 → 下一周期重新统计 → 履约率提升」。

---

## 22. 干预效果对比（V3.6）

`GET /api/admin/operations/effect`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `metric` | String | 是 | 指标 key，**必须已在计算器注册表里**；未注册 `400` |
| `from` / `to` | String | 是 | **后**一期的区间（闭区间，上限 366 天 → `9002`） |
| `compareFrom` / `compareTo` | String | 否 | **前**一期的区间；不传时自动取「与后一期等长、紧邻其前」的区间 |

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "metric": "operations.fulfillment_rate",
    "metricName": "履约率",
    "current":  { "from": "2026-10-01", "to": "2026-10-15", "value": "96.40%" },
    "previous": { "from": "2026-09-16", "to": "2026-09-30", "value": "92.10%" },
    "delta": "+4.30%"
  }
}
```

| 字段 | 说明 |
|---|---|
| `metric` | 指标 key |
| `metricName` | 指标中文名（**从指标字典取**，不另写一份名表） |
| `current` / `previous` | 两期的区间与取值 |
| `delta` | 差值（带正负号）；两值格式不同（如 `%` vs 计数）时返回 `"—"` |

### 同口径铁律（决策 6.2）

两期数值由**同一个计算器**算两遍得到 ——
用两个各写一遍的公式对比，比出来的差值可能全是公式差异。

未注册的指标 key 返回 `400`（**不许「猜一个算法算出来」**）。

---

## 23. 端点 ↔ 指标机器面（V3.6）

V3.6 起，「哪个端点返回了哪些指标」由
`backend/src/main/resources/metrics/endpoints.yml` 声明，
并由 `MetricEndpointContractTest` 做**三条双向校验**：

1. yml 里声明的端点**必须真实存在**（反射扫描所有 `@RestController` 的映射方法）；
2. yml 里声明的指标**必须在指标字典里**；
3. 指标字典里每条指标声明的端点**必须在 yml 里出现**。

指标字典的「人读面」与「机器面」是否逐条一致，由
`tools/check_metric_dict_parity.py` 机械校验（不一致即非零退出）。

---

## 24. 指标字典只读导出（V3.6）

`GET /api/admin/metrics`　权限：**ADMIN**

### 请求参数

无。本端点不接收任何业务参数 —— 口径是静态登记的，不随区间变化。

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "version": 5,
    "total": 64,
    "metrics": [
      {
        "key": "operations.fulfillment_rate",
        "name": "履约率",
        "scope": "统计区间内进入终态的陪诊订单",
        "period": "统计区间（闭区间，默认最近 30 天）",
        "source": "companion_order(status, create_time)",
        "formula": "(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)",
        "caliberVersion": 1,
        "endpoints": ["GET /api/admin/operations/overview", "GET /api/admin/operations/health"]
      }
    ]
  }
}
```

| 字段 | 说明 |
|---|---|
| `version` | 字典版本（机器面 `version`）；口径变更即 +1 |
| `total` | 登记的指标条数 |
| `metrics[].key` | 指标 key（全局唯一） |
| `metrics[].name` | 指标名称 |
| `metrics[].scope` | 统计范围 |
| `metrics[].period` | 统计时间 |
| `metrics[].source` | 数据来源（表与字段） |
| `metrics[].formula` | 计算公式 |
| `metrics[].caliberVersion` | 该指标的口径版本 |
| `metrics[].endpoints` | 返回该指标的端点（端点 ↔ 指标机器面） |

### 它导出的是「口径」，不是「值」

⚠️ 本端点**不返回任何统计值**。它回答的是「这个数**怎么算**」，
供运营对账与开发排查；**看板上的数在各自的统计端点**。

因此它**不进** `backend/src/main/resources/metrics/endpoints.yml` ——
那张表登记的是「哪个端点返回了哪些指标**值**」，
而本端点返回的是指标**定义**（ADR-0017 §待观察的边界：
「这个字段是不是一个有口径的统计量」）。

### 只读

本端点**不提供修改口径的入口**。口径变更只能通过
`metrics/metric-dictionary.yml` 的变更 + 字典版本升级走代码评审 ——
否则同一时刻两个看板可能读到两份口径。

### 错误码

| 码 | 含义 | 触发条件 |
|---|---|---|
| 401 | 未登录 | 无令牌 |
| 403 | 无权限 | 角色不是 `ADMIN` |

---

## 25. 结算核对列表（P2-1）

`GET /api/admin/settlement`　权限：**ADMIN**

> 结算台账**只收**服务已完成的订单（`COMPLETED` / `REVIEWED`）；
> `CANCELLED` 永不进入台账。
> 周期口径是**服务完成时间 `finish_time`**，与订单统计的「下单时间」不是同一口径。

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `paymentStatus` | String | 否 | `UNPAID`（未结算）/ `SETTLED`（已结算）；不传为全部 |
| `keyword` | String | 否 | 订单号 / 医院 / 科室模糊匹配 |
| `startDate` / `endDate` | String | 否 | `yyyy-MM-dd`，按 `finish_time` 闭区间（含当天） |
| `page` / `size` | Integer | 否 | 分页，默认 1 / 10 |

固定条件：`status IN (COMPLETED, REVIEWED)`，排序 `finish_time DESC, id DESC`。

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "total": 128, "page": 1, "size": 10, "pages": 13,
    "records": [
      {
        "id": 1001,
        "orderNo": "NL20260915000001",
        "familyName": "张*",
        "elderName": "李*",
        "companionName": "王*",
        "hospital": "市第一人民医院",
        "department": "心内科",
        "visitTime": "2026-09-15 09:00:00",
        "finishTime": "2026-09-15 12:30:00",
        "fee": "120.00",
        "settleAmount": "120.00",
        "paymentStatus": "UNPAID",
        "paymentStatusLabel": "未结算",
        "status": "COMPLETED",
        "statusLabel": "已完成"
      }
    ]
  }
}
```

| 字段 | 说明 |
|---|---|
| `fee` | 下单时的服务费，只读；两位小数字符串 |
| `settleAmount` | **服务端计算**：`actual_fee` 非空取之，否则取 `fee`；两位小数字符串。前端不得回传该值 |
| `paymentStatus` | `UNPAID` / `SETTLED`，中文名在 `paymentStatusLabel` |
| 姓名三列 | 一律 `MaskUtil.name()` 脱敏；本页不适用订单详情的全名豁免 |

---

## 26. 单条结算核定（P2-1）

`POST /api/admin/settlement/{orderId}/confirm`　权限：**ADMIN**

请求体：**无**。金额不接受入参 —— 核定时由服务端把 `actual_fee` 快照为
`COALESCE(actual_fee, fee)`，「核定那一刻的结算金额」自此冻结。

### 结果语义

| 场景 | code | 结果 |
|---|---|---|
| 订单不存在 | `3001` | 失败 |
| 订单状态不在 `COMPLETED` / `REVIEWED` | `8004` | 失败 |
| 已是 `SETTLED` | `200` | 成功，`settledNow=false`，**不重复写操作日志** |
| `UNPAID` 且状态合法 | `200` | 成功，`settledNow=true`，写一条 `admin_oper_log` |

留痕内容：`operType=SETTLE_ORDER`、`targetType=ORDER`、`targetId=orderId`、
`targetDesc=orderNo`、`beforeStatus=UNPAID`、`afterStatus=SETTLED`、
备注含服务费与结算金额；操作人、IP、时间由留痕组件自动补齐。

### 响应

```json
{
  "code": 200, "message": "已核定",
  "data": {
    "orderId": 1001,
    "orderNo": "NL20260915000001",
    "paymentStatus": "SETTLED",
    "paymentStatusLabel": "已结算",
    "settleAmount": "120.00",
    "settledNow": true
  }
}
```

> 幂等语义：同一订单重复调用始终返回 `200`，第二次起 `settledNow=false`，
> 不会再产生第二条日志。客户端可安全重试。

---

## 27. 批量结算核定（P2-1）

`POST /api/admin/settlement/batch-confirm`　权限：**ADMIN**

### 请求体

```json
{ "orderIds": [1001, 1002, 1003] }
```

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `orderIds` | Long[] | 是 | 1–100 条；为空或超限返回 `400`；重复 ID 去重并保留首次出现顺序 |

### 响应

```json
{
  "code": 200, "message": "批量核定完成",
  "data": {
    "total": 3,
    "settledCount": 1,
    "alreadySettledCount": 1,
    "failedCount": 1,
    "items": [
      { "orderId": 1001, "orderNo": "NL...0001", "success": true,
        "settledNow": true, "code": 200, "message": "已核定", "settleAmount": "120.00" },
      { "orderId": 1002, "orderNo": "NL...0002", "success": true,
        "settledNow": false, "code": 200, "message": "该单已结算", "settleAmount": "80.00" },
      { "orderId": 1003, "orderNo": null, "success": false,
        "settledNow": false, "code": 8004, "message": "当前订单状态不可结算核对", "settleAmount": null }
    ]
  }
}
```

- 逐项独立成败：业务失败不中断其余项
- 只有真正发生 `UNPAID → SETTLED` 的订单才各写一条日志（不写批量汇总日志）
- 数据库层异常整批回滚，走全局异常处理
- 汇总口径：`total = settledCount + alreadySettledCount + failedCount`

### 错误码

| 码 | 含义 | 触发条件 |
|---|---|---|
| 400 | 请求参数错误 | `orderIds` 为空或超过 100 条 |
| 403 | 无权限 | 角色不是 `ADMIN` |
| 8004 | 当前订单状态不可结算核对 | 单项订单不是 `COMPLETED` / `REVIEWED` |
| 3001 | 订单不存在 | 单项订单 ID 不存在 |

---

## 28. 运营参数配置（P2-3）

> 目标：让运营能在线调整 4 个阈值，改完**不发版即生效**；读取失败时回落
> `application.yml` 默认值，**不允许因为调参把主流程拖挂**。

### 28.1 参数列表

`GET /api/admin/operation-param`　权限：**ADMIN**　只读，**不写操作日志**

响应 `data` 为数组（固定 4 项，顺序同下表）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | String | 字典键（`sys_dict.dict_code`） |
| `label` | String | 中文名 |
| `value` | Integer | 当前生效值 |
| `defaultValue` | Integer | `application.yml` 默认值 |
| `min` / `max` | Integer | 允许区间 |
| `unit` | String | 单位 |
| `description` | String | 参数说明 |
| `status` | Integer | 字典状态：`0` 停用 / `1` 启用；无字典行时为 `null` |
| `overridden` | Boolean | 是否已被字典覆盖（与默认值不同） |
| `usingDefault` | Boolean | 是否正在使用默认值（字典缺失、停用或读库失败） |
| `updateTime` | String | 字典行最近更新时间 |

### 28.2 迁移的 4 个参数

| `code` | 中文名 | 默认值 | 范围 | 单位 | 读取点 |
|---|---|---|---|---|---|
| `medication.missed-threshold-minutes` | 漏服判定阈值 | 60 | 1–1440 | 分钟 | `MedicationServiceImpl#scanMissedTasks` |
| `fulfillment.urge-cooldown-minutes` | 催单最小间隔 | 10 | 1–1440 | 分钟 | `FulfillmentServiceImpl` / `OrderServiceImpl#urgeGate` |
| `export.max-rows` | 单次导出条数上限 | 10000 | 100–50000 | 行 | `StatisticsServiceImpl` / `SettlementServiceImpl` |
| `sms.frequency.missed-dose-daily-cap` | 漏服短信单用户日上限 | 3 | 1–100 | 条 | `SmsReminderService` |

**为什么只收这 4 项**：连接串、密钥、JWT、调度开关、权重配比属于部署 / 结构配置，
放进字典会同时失去「谁能改」与「改错怎么回滚」两道保护，因此**一律不收**。
字典类型 `OPS_PARAM` 与既有下拉字典（`HOSPITAL` / `DEPARTMENT` / ...）按 `dict_type` 隔离。

### 28.3 修改参数

`PUT /api/admin/operation-param/{code}`　权限：**ADMIN**

请求体：

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `value` | Integer | ✓ | 必须落在该参数的 `[min, max]` 内 | 新值 |
| `remark` | String | 否 | ≤ 200 字 | 修改原因，写入 `admin_oper_log.remark` |

响应：`{ "code": 200, "message": "操作成功", "data": null }`

错误场景：

| code | 触发条件 |
|---|---|
| 400 | `code` 不在 4 个枚举内 / `value` 为空 / 越界 / `remark` 超长 |
| 403 | 非 ADMIN |
| 401 | 未登录 |

### 28.4 生效方式与缓存

- **优先级（排查第一现场）**：`sys_dict` 中 `dict_type='OPS_PARAM'` 且 `status=1` 的行
  **优先于** `application.yml`、启动参数（`--nianglin.xxx`）与环境变量（`NIANGLIN_XXX`）。
  字典里一旦有值且启用，同名配置项的环境变量/启动参数**不再生效**；只有把该行
  `status=0` 停用后，环境变量/启动参数才作为兜底。配错时先查这张表，不要先怀疑配置文件：
  `SELECT dict_code, dict_value, status FROM sys_dict WHERE dict_type='OPS_PARAM';`
  已知受影响键：`sms.frequency.missed-dose-daily-cap`、`export.max-rows`、
  `medication.missed-threshold-minutes`、`fulfillment.urge-cooldown-minutes`。
- **生效方式**：读取点每次调用实时从进程内快照取当前值。
  管理端修改成功后，事务**提交后**立即刷新本实例缓存 → 下一次业务调用即生效，无需重启或发版。
- **缓存 TTL**：默认 60 秒（`nianglin.operation-param.cache-ttl-ms`）。
  TTL 到期后由下一次读取同步重载，`synchronized` 防击穿。
- **多实例**：其他实例最多 60 秒收敛（本期单实例部署）。
- **失败回落**：读库异常时保留上一份成功快照；无快照则使用 `application.yml` 默认值。
  字典值非数字或越界同样回落默认值，**读取路径不抛异常**。
- **启动**：懒加载，不在 `@PostConstruct` 访问数据库；SQL 缺失或 DB 不可用时业务仍按默认值运行。
- **事务边界**：修改参数与写日志在同一事务；回滚时缓存保持旧值。

### 28.5 留痕

每次修改写 `admin_oper_log`：

| 字段 | 值 |
|---|---|
| `oper_type` | `UPDATE_OPERATION_PARAM` |
| `target_type` | `OPERATION_PARAM` |
| `target_id` | `sys_dict` 行 ID |
| `target_desc` | 字典键（如 `medication.missed-threshold-minutes`） |
| `before_status` / `after_status` | 旧值 / 新值 |
| `remark` | `键=...；旧值=...；新值=...；备注=...` |
| `operator_id` / `operator_name` | 由 `OperLogRecorder` 自动写入 |

留痕查询：`GET /api/admin/operation-param/logs?page=1&size=10`　权限：**ADMIN**，
返回 `PageResult<OperLogVO>`，`operType` 固定为 `UPDATE_OPERATION_PARAM`，按操作时间倒序。

---

## 29. 评价巡查列表（E4）

`GET /api/admin/review`　权限：**ADMIN**　只读，**不写操作日志**

### 请求参数

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `page` | Integer | 否 | ≥ 1 | 页码，默认 1 |
| `size` | Integer | 否 | 1–100 | 每页条数，默认 10 |
| `companionId` | Long | 否 | — | 只看某位陪诊员收到的评价 |
| `minScore` | Integer | 否 | 1–5 | 评分下限 |
| `maxScore` | Integer | 否 | 1–5 | 评分上限 |
| `isValid` | Boolean | 否 | — | **不传 = 全部**；`true` = 只看有效；`false` = 只看已被裁定无效 |
| `keyword` | String | 否 | — | 评价正文 / 订单号模糊搜索 |
| `startDate` | String | 否 | `yyyy-MM-dd` | 评价时间起（含当天） |
| `endDate` | String | 否 | `yyyy-MM-dd` | 评价时间止（含当天） |

### 为什么需要这个端点（与 `GET /api/review/companion/{id}` 的区别）

后者是**公开口碑**链路，硬编码只返回**有效**评价（`ReviewServiceImpl#byCompanion` 里
写死了 `eq(is_valid, 1)`）。管理员用它**永远看不到已被裁定无效的评价** ——
而「裁完就从列表里消失」的评价等于从来没被巡查过。

本端点用 `isValid` 三态把两种都露出来，让管理员能主动巡查
（PRD FR-03 规则 6：「不要求存在关联申诉，管理员可主动巡查裁定」）。

### 响应

`data` 为标准分页结构 `{ total, page, size, pages, records }`，`records` 元素：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 评价 ID |
| `orderId` | Long | 关联订单 ID |
| `orderNo` | String | 订单号 |
| `score` | Integer | 评分（1–5 星） |
| `tags` | String[] | 标签列表 |
| `content` | String | 评价正文 |
| `isAnonymous` | Boolean | 是否匿名评价（与 `ReviewVO` / `docs/api/06-review-complaint.md` 同名字段） |
| `familyName` | String | 评价家属姓名（**脱敏**；匿名时为固定串 `匿***`） |
| `companionId` | Long | 陪诊员用户 ID |
| `companionName` | String | 陪诊员姓名（**脱敏**） |
| `companionReply` | String | 陪诊员回复正文（未回复时为 `null`，不是空串） |
| `replyTime` | String | 陪诊员回复时间（未回复时为 `null`） |
| `isValid` | Boolean | **是否有效**：`false` = 已被管理员裁定无效（与 `ReviewVO.isValid` 同名，两个入口共用同一个裁定对话框） |
| `createTime` | String | 评价时间 |

### 业务规则

1. 姓名一律**脱敏** —— 订单详情那条「全名豁免」只对详情页成立，不适用于列表。
2. `is_valid IS NULL` 的历史数据**视为有效**，与 `ReviewVO` 的兜底口径一致；
   因此 `isValid=true` 的筛选条件是「`is_valid = 1` **或** `is_valid IS NULL`」。
3. 排序固定为「评价时间倒序 → id 倒序」，不开放 `sortField`。
4. 日期区间倒置（开始晚于结束）返回 `400`，不静默忽略。
5. 只读端点，**不写** `admin_oper_log`。
6. 零迁移：全部条件落在 `order_review` 既有列上。

### 错误场景

| code | 触发条件 |
|---|---|
| 400 | 日期区间倒置 / `minScore` 或 `maxScore` 越界（不在 1–5） |
| 403 | 非 ADMIN |
| 401 | 未登录 |

