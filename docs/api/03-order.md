# 03 陪诊订单

> 覆盖模块：**M4 陪诊订单与状态机**
> 负责人：B（后端主力）+ A（前端主力）
> 归属迭代：迭代二 · W5–W7（**W7 竞讲交付 v1.0**）
> 前置依赖：M3 用户与档案　全局约定见 [README.md](./README.md)

> ⚠️ **M4 是全局瓶颈模块**：M5 / M7 / M9 / M10 都依赖它。延期会连带拖垮后半程。

---

## 一、数据模型

### OrderVO（订单）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 订单 ID |
| `orderNo` | String | 订单号，格式 `NL` + `yyyyMMdd` + 6 位序列，如 `NL20260915000001` |
| `elderId` | Long | 就诊老人 ID |
| `elderName` | String | 老人姓名（脱敏） |
| `elderAge` | Integer | 老人年龄 |
| `familyId` | Long | 下单家属 ID |
| `companionId` | Long | 陪诊员 ID，未接单时为 `null` |
| `companionName` | String | 陪诊员姓名（脱敏），未接单时为 `null` |
| `hospital` | String | 医院名称 |
| `department` | String | 科室 |
| `visitTime` | String | 就诊时间 `yyyy-MM-dd HH:mm:ss` |
| `address` | String | 医院地址（**文字地址，一期不做地图导航**） |
| `remark` | String | 备注（如「老人听力不好，请大声沟通」） |
| `status` | String | 状态枚举名，见下表 |
| `statusLabel` | String | 中文状态 |
| `fee` | String | 服务费，两位小数字符串，如 `"128.00"` |
| `paymentStatus` | String | `UNPAID`（未结算）/ `SETTLED`（已结算）—— **线下结算** |
| `version` | Integer | 乐观锁版本号（**防超卖接单**） |
| `createTime` | String | 下单时间 |
| `acceptTime` | String | 接单时间 |
| `startTime` | String | 开始服务时间 |
| `finishTime` | String | 完成时间 |

### 状态枚举

| 传值 | 中文 | 排序 |
|---|---|---|
| `PENDING` | 待接单 | 1 |
| `ACCEPTED` | 已接单 | 2 |
| `IN_SERVICE` | 服务中 | 3 |
| `COMPLETED` | 已完成 | 4 |
| `REVIEWED` | 已评价 | 5 |
| `CANCELLED` | 已取消 | 9 |

---

## 二、状态机（本模块的核心）

```
                    ┌─────────────────────────────────────────────┐
                    │                                             │
   PENDING ──①──► ACCEPTED ──②──► IN_SERVICE ──③──► COMPLETED ──④──► REVIEWED
   待接单          已接单            服务中             已完成           已评价
      │              │                 │                 │               │
      └──────────────┴─────────────────┴─────────────────┘               │
                              │（仅 ADMIN 纠纷处理）                       │
                              ▼                                          ▼
                          CANCELLED ◄──────────────────────────────────────
                           已取消
```

| 编号 | 流转 | 触发接口 | 允许角色 |
|---|---|---|---|
| ① | 待接单 → 已接单 | `POST /api/order/{id}/accept` | COMPANION |
| ② | 已接单 → 服务中 | `POST /api/order/{id}/start` | COMPANION（须为本单陪诊员） |
| ③ | 服务中 → 已完成 | `POST /api/order/{id}/complete` | COMPANION（须为本单陪诊员） |
| ④ | 已完成 → 已评价 | `POST /api/review`（M7） | FAMILY（须为本单下单人） |
| ⑤ | 任意 → 已取消 | `POST /api/admin/order/{id}/arbitrate`（M9） | **仅 ADMIN** |
| 取消 | 待接单 → 已取消 | `PUT /api/order/{id}/cancel` | FAMILY（须为本单下单人） |

### 铁律（违反即返回 `3002`）

1. **禁止跳级**：`待接单` 不能直接变 `已完成`。
2. **禁止回退**：`已接单` 不能退回 `待接单`。
3. **终态锁死**：`已评价` / `已取消` 之后不可再变更。
4. 每次流转写入 `order_status_log`，异常时整体回滚。

> 状态判断**必须使用后端 `OrderStatus` 枚举**（位于 `org.company.nianglin.constant`），禁止硬编码状态字符串。

---

## 三、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/api/order` | FAMILY | 创建订单 |
| 2 | GET | `/api/order` | 已登录 | 我的订单列表（按角色自动区分） |
| 3 | GET | `/api/order/hall` | COMPANION | 待接单订单大厅 |
| 4 | GET | `/api/order/{id}` | 已登录（须为相关方） | 订单详情 |
| 5 | PUT | `/api/order/{id}/cancel` | FAMILY | 取消订单 |
| 6 | POST | `/api/order/{id}/accept` | COMPANION | 接单（乐观锁） |
| 7 | POST | `/api/order/{id}/reject` | COMPANION | 拒单 |
| 8 | POST | `/api/order/{id}/start` | COMPANION | 开始服务 |
| 9 | POST | `/api/order/{id}/complete` | COMPANION | 完成服务 |
| 10 | GET | `/api/order/{id}/timeline` | 已登录（须为相关方） | 状态流转时间线 |

---

## 1. 创建订单

`POST /api/order`　权限：**FAMILY**（ELDER 调用返回 403）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `elderId` | Long | ✓ | 须为当前家属绑定的老人 | 就诊老人 |
| `hospital` | String | ✓ | ≤ 100 字符 | 医院名称 |
| `department` | String | ✓ | ≤ 50 字符 | 科室 |
| `visitTime` | String | ✓ | `yyyy-MM-dd HH:mm:ss`，**必须晚于当前时间** | 就诊时间 |
| `address` | String | ✓ | ≤ 200 字符 | 医院地址（文字描述） |
| `remark` | String | 否 | ≤ 500 字符 | 备注 |
| `fee` | String | 否 | 两位小数 | 服务费，不传则由后端按规则计算 |

```json
{
  "elderId": 301,
  "hospital": "海南省人民医院",
  "department": "心血管内科",
  "visitTime": "2026-09-20 09:30:00",
  "address": "海南省海口市秀英区秀华路19号 门诊大楼3楼",
  "remark": "老人听力不好，请大声沟通；需协助取药",
  "fee": "128.00"
}
```

### 响应

```json
{
  "code": 200,
  "message": "下单成功，正在为您匹配陪诊员",
  "data": {
    "orderId": 1001,
    "orderNo": "NL20260915000001",
    "status": "PENDING",
    "statusLabel": "待接单"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 400 | 参数校验失败 |
| 3005 | 就诊时间早于当前时间 |
| 2006 | 该老人不在当前家属名下 |
| 403 | ELDER 角色调用（老人只读） |

### 实现要点

- 订单号生成：`NL` + 日期 + Redis 自增序列，保证唯一且可读。
- 创建后自动向所有已通过审核的陪诊员推送一条站内信（M8）。
- 初始 `version = 0`。

---

## 2. 我的订单列表

`GET /api/order`　权限：已登录

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `status` | String | 否 | 状态枚举名，可多值逗号分隔，如 `PENDING,ACCEPTED` |
| `startDate` | String | 否 | 下单日期起 `yyyy-MM-dd` |
| `endDate` | String | 否 | 下单日期止 |
| `elderId` | Long | 否 | 按老人筛选（FAMILY） |
| `keyword` | String | 否 | 订单号 / 医院模糊搜索 |

> **按角色自动区分数据范围**：
> - FAMILY → 自己下的单
> - COMPANION → 自己接的单
> - ELDER → 自己作为就诊人的订单（**只读**）
> - ADMIN → 全部订单（但建议用 `/api/admin/order`）

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
    "records": [
      {
        "id": 1001,
        "orderNo": "NL20260915000001",
        "elderName": "张*三",
        "elderAge": 78,
        "hospital": "海南省人民医院",
        "department": "心血管内科",
        "visitTime": "2026-09-20 09:30:00",
        "status": "ACCEPTED",
        "statusLabel": "已接单",
        "companionName": "李*",
        "fee": "128.00",
        "createTime": "2026-09-15 16:40:00"
      }
    ]
  }
}
```

---

## 3. 待接单订单大厅

`GET /api/order/hall`　权限：**COMPANION**（且资质必须为 `APPROVED`）

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `area` | String | 否 | 服务区域筛选 |
| `startDate` / `endDate` | String | 否 | 就诊日期区间 |
| `hospital` | String | 否 | 医院关键字 |

### 响应

结构同「我的订单列表」，`records` 中额外返回 `address`（陪诊员需要知道去哪）。

### 错误场景

| code | 场景 |
|---|---|
| 2003 | 陪诊员资质尚未通过审核 |
| 403 | 非 COMPANION 角色调用 |

### 实现要点

- 必须**双重拦截**：角色为 COMPANION **且** `companion_profile.audit_status = 'APPROVED'`。
- 只返回 `status = 'PENDING'` 的订单，且就诊时间未过期。

---

## 4. 订单详情

`GET /api/order/{id}`　权限：已登录，且须为该订单的相关方

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 1001,
    "orderNo": "NL20260915000001",
    "elderId": 301,
    "elderName": "张三",
    "elderAge": 78,
    "familyId": 10023,
    "companionId": 10088,
    "companionName": "李四",
    "hospital": "海南省人民医院",
    "department": "心血管内科",
    "visitTime": "2026-09-20 09:30:00",
    "address": "海南省海口市秀英区秀华路19号 门诊大楼3楼",
    "remark": "老人听力不好，请大声沟通；需协助取药",
    "status": "ACCEPTED",
    "statusLabel": "已接单",
    "fee": "128.00",
    "paymentStatus": "UNPAID",
    "createTime": "2026-09-15 16:40:00",
    "acceptTime": "2026-09-15 17:02:11"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3004 | 无权操作该订单（既不是下单家属，也不是接单陪诊员） |

### 权限判定逻辑

```
当前用户 ID == order.familyId  → 允许
当前用户 ID == order.companionId → 允许
当前用户 ID == order.elderId（就诊人本人）→ 允许（只读）
当前角色 == ADMIN → 允许
其余 → 3004
```

---

## 5. 取消订单

`PUT /api/order/{id}/cancel`　权限：FAMILY（须为本单下单人）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `reason` | String | ✓ | ≤ 200 字符 | 取消原因 |

### 响应

```json
{ "code": 200, "message": "订单已取消", "data": null }
```

### 错误场景

| code | 场景 |
|---|---|
| 3006 | 当前状态不允许取消（仅「待接单」可取消） |
| 3004 | 无权操作该订单 |

### 实现要点

- 只有 `PENDING` 可取消。已接单的订单需取消必须走 ADMIN 纠纷处理（M9），避免陪诊员白跑。
- 取消后站内信通知（若已有陪诊员）。

---

## 6. 接单（乐观锁防超卖）

`POST /api/order/{id}/accept`　权限：COMPANION（且资质 `APPROVED`）

### 请求参数

无（陪诊员 ID 从 token 取）。

### 响应（成功）

```json
{
  "code": 200,
  "message": "接单成功",
  "data": {
    "orderId": 1001,
    "status": "ACCEPTED",
    "statusLabel": "已接单",
    "acceptTime": "2026-09-15 17:02:11"
  }
}
```

### 响应（被抢单）

```json
{
  "code": 3003,
  "message": "手慢了，该订单已被其他陪诊员接单",
  "data": null
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3002 | 当前订单状态不允许该操作（不是 `PENDING`） |
| 3003 | 已被其他陪诊员接单（乐观锁失败） |
| 2003 | 陪诊员资质未通过审核 |
| 403 | 非 COMPANION 角色 |

### 实现要点（**这是 M4 最重要的验收点**）

```sql
-- 依赖 MySQL 的原子更新，靠 version 字段做乐观锁
UPDATE companion_order
SET status = 'ACCEPTED',
    companion_id = ?,
    accept_time = NOW(),
    version = version + 1
WHERE id = ?
  AND status = 'PENDING'
  AND version = ?;      -- 旧版本号
-- affectedRows == 0 → 说明被抢先，返回 3003
```

- 实体上必须标注 `@Version`（`MyBatisPlusConfig` 已注册 `OptimisticLockerInnerInterceptor`）。
- **不要用「先 SELECT 再 UPDATE」的写法**，那样在并发下必然超卖。
- 整个方法加 `@Transactional`。

---

## 7. 拒单

`POST /api/order/{id}/reject`　权限：COMPANION

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `reason` | String | ✓ | ≤ 200 字符 | 拒单原因 |

### 响应

```json
{ "code": 200, "message": "已拒单", "data": null }
```

### 实现要点

- 拒单**不改变订单状态**（仍是 `PENDING`），只是记录一条拒单记录，避免该陪诊员再次看到本单。
- 记录写入 `order_reject_log`，用于后续统计与风控。

---

## 8. 开始服务

`POST /api/order/{id}/start`　权限：COMPANION（须为本单陪诊员）

### 响应

```json
{
  "code": 200,
  "message": "已开始服务",
  "data": { "status": "IN_SERVICE", "statusLabel": "服务中", "startTime": "2026-09-20 09:10:00" }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3002 | 当前状态不允许（必须为 `ACCEPTED`） |
| 4003 | 您不是该订单的陪诊员 |

### 实现要点

- 建议同时要求先打过 `DEPART` 或 `ARRIVE` 打卡（M5），否则返回 `3002`。
- 状态变更后通过 WebSocket 推送家属端（M5）。

---

## 9. 完成服务

`POST /api/order/{id}/complete`　权限：COMPANION（须为本单陪诊员）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `summary` | String | 否 | ≤ 500 字符 | 服务小结（**只记录过程，不含诊断与用药建议**） |
| `photos` | Array | 否 | ≤ 6 张 | 取药凭证 / 现场照片 URL 列表 |
| `fee` | String | 否 | 两位小数 | 实际结算服务费（线下结算记录） |

### 响应

```json
{
  "code": 200,
  "message": "服务已完成",
  "data": {
    "status": "COMPLETED",
    "statusLabel": "已完成",
    "finishTime": "2026-09-20 12:05:00",
    "paymentStatus": "UNPAID"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3002 | 当前状态不允许（必须为 `IN_SERVICE`） |
| 4003 | 您不是该订单的陪诊员 |

### 实现要点

- 完成后触发：给家属发站内信（提示可评价）、订单进入可评价状态（M7）。
- 完成后**不自动变成 REVIEWED**，需要家属提交评价后由 M7 触发 `COMPLETED → REVIEWED`。
- `summary` 字段必须做敏感词过滤与「不含诊断建议」的合规校验。

---

## 10. 状态流转时间线

`GET /api/order/{id}/timeline`　权限：已登录，且须为该订单相关方

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "status": "PENDING",
      "statusLabel": "待接单",
      "operatorName": "张三",
      "operatorRole": "FAMILY",
      "remark": "下单成功",
      "operateTime": "2026-09-15 16:40:00"
    },
    {
      "status": "ACCEPTED",
      "statusLabel": "已接单",
      "operatorName": "李四",
      "operatorRole": "COMPANION",
      "remark": "已接单",
      "operateTime": "2026-09-15 17:02:11"
    },
    {
      "status": "IN_SERVICE",
      "statusLabel": "服务中",
      "operatorName": "李四",
      "operatorRole": "COMPANION",
      "remark": "已到达医院，开始陪诊",
      "operateTime": "2026-09-20 09:10:00"
    }
  ]
}
```

### 实现要点

- 数据来源 `order_status_log`，按 `operate_time` 升序。
- 打卡节点（M5）也合并进时间线，前端统一渲染。

---

## 四、验收标准（M4）

> 这是 M4 的竞讲交付清单（W7），逐条必须可复现。

- [ ] **JMeter 50 并发同时接同一订单 → 数据库只有 1 条成功接单记录，`version` 仅 +1**
- [ ] 跳级调用「待接单 → 已完成」→ 返回 `3002`，数据库状态不变
- [ ] 「已接单」的订单再次调用接单 → 返回 `3002`
- [ ] 非订单归属陪诊员调用「开始服务」→ 返回 `4003`
- [ ] 分页查询 `page=1&size=10&status=PENDING` 条数与 `SELECT COUNT(*)` 手工核对一致
- [ ] 状态变更接口抛异常时事务回滚，订单状态与 `order_status_log` 保持一致
- [ ] FAMILY 先下单 → COMPANION 接单 → 开始 → 完成，全流程可跑通
- [ ] ELDER 账号调用创建订单接口 → `403`
- [ ] `/doc.html` 中订单模块 10 个接口齐全，含请求 / 响应示例
- [ ] 就诊时间早于当前时间 → `3005`
