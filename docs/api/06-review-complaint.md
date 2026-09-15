# 06 评价与投诉

> 覆盖模块：**M7 评价与投诉**
> 负责人：A（页面）+ B（接口与统计）
> 归属迭代：迭代三 · W10
> 前置依赖：M4 陪诊订单　全局约定见 [README.md](./README.md)

---

## 一、数据模型

### ReviewVO（评价）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 评价 ID |
| `orderId` | Long | 订单 ID |
| `orderNo` | String | 订单号 |
| `score` | Integer | 评分 1–5 星 |
| `tags` | Array | 标签列表，如 `["准时", "耐心", "沟通清楚"]` |
| `content` | String | 评价文字（≤ 500 字符） |
| `isAnonymous` | Boolean | 是否匿名 |
| `familyName` | String | 评价人姓名（匿名时为 `匿***`） |
| `companionId` | Long | 被评价陪诊员 ID |
| `companionName` | String | 陪诊员姓名（脱敏） |
| `companionReply` | String | 陪诊员回复（可选） |
| `createTime` | String | 评价时间 |

### CompanionScoreVO（评分聚合）

| 字段 | 类型 | 说明 |
|---|---|---|
| `companionId` | Long | 陪诊员 ID |
| `averageScore` | String | 平均分，两位小数，如 `"4.80"` |
| `totalCount` | Integer | 评价总数 |
| `starDistribution` | Object | 星级分布 `{ "5": 30, "4": 5, "3": 1, "2": 0, "1": 1 }` |
| `goodRate` | String | 好评率（4 星及以上占比），如 `"94.59%"` |

### ComplaintVO（投诉）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 投诉 ID |
| `orderId` | Long | 关联订单 ID |
| `orderNo` | String | 订单号 |
| `complainantId` | Long | 投诉人 ID |
| `complainantName` | String | 投诉人姓名（脱敏） |
| `targetUserId` | Long | 被投诉人 ID |
| `targetUserName` | String | 被投诉人姓名（脱敏） |
| `type` | String | 投诉类型，见下表 |
| `content` | String | 投诉内容（≤ 1000 字符） |
| `evidence` | Array | 证据材料 URL 列表 |
| `status` | String | `PENDING` / `PROCESSING` / `RESOLVED` / `REJECTED` |
| `statusLabel` | String | 待处理 / 处理中 / 已结案 / 已驳回 |
| `handleResult` | String | 处理结果说明（仅已结案时返回） |
| `createTime` | String | 提交时间 |
| `handleTime` | String | 处理时间 |

### 投诉类型

| 传值 | 中文 |
|---|---|
| `LATE` | 迟到 / 未按时到达 |
| `ATTITUDE` | 服务态度差 |
| `INCOMPLETE` | 服务未完成 |
| `FEE_DISPUTE` | 费用纠纷 |
| `PRIVACY` | 隐私泄露 |
| `OTHER` | 其他 |

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/api/review` | FAMILY | 提交评价 |
| 2 | GET | `/api/review/order/{orderId}` | 相关方 | 查询订单评价 |
| 3 | GET | `/api/review/companion/{companionId}` | 已登录 | 陪诊员评价列表 |
| 4 | GET | `/api/review/companion/{companionId}/score` | 已登录 | 陪诊员评分聚合 |
| 5 | POST | `/api/complaint` | 已登录 | 提交投诉 |
| 6 | GET | `/api/complaint` | 已登录 | 我的投诉列表 |
| 7 | GET | `/api/complaint/{id}` | 相关方 | 投诉详情 |

---

## 1. 提交评价

`POST /api/review`　权限：**FAMILY**（须为该订单下单人）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `orderId` | Long | ✓ | 订单状态须为 `COMPLETED` | 订单 ID |
| `score` | Integer | ✓ | 1–5 | 评分 |
| `tags` | Array | 否 | ≤ 5 个，每个 ≤ 10 字符 | 标签 |
| `content` | String | 否 | ≤ 500 字符，≥ 5 字符 | 评价文字 |
| `isAnonymous` | Boolean | 否 | 默认 `false` | 是否匿名 |

```json
{
  "orderId": 1001,
  "score": 5,
  "tags": ["准时", "耐心", "沟通清楚"],
  "content": "小李很耐心，全程陪着老人，取药排队也帮忙，家里人很放心。",
  "isAnonymous": false
}
```

### 响应

```json
{
  "code": 200,
  "message": "感谢您的评价",
  "data": {
    "reviewId": 2001,
    "orderStatus": "REVIEWED",
    "orderStatusLabel": "已评价"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 6001 | 订单尚未完成，不能评价（状态不是 `COMPLETED`） |
| 6002 | 该订单已评价 |
| 6003 | 内容包含敏感词 |
| 3004 | 无权评价该订单（不是本单下单人） |
| 403 | ELDER 角色调用 |

### 实现要点

1. 校验订单状态必须为 `COMPLETED`，否则 `6001`。
2. 唯一约束 `uk_order_review (order_id)`，重复评价返回 `6002`（**不能靠前端按钮禁用防重复**）。
3. 提交成功后**自动把订单推进到 `REVIEWED`**（`COMPLETED → REVIEWED`，这是状态机允许的正向流转）。
4. 内容敏感词过滤（"骗""垃圾""滚"等），命中返回 `6003`。
5. 同步更新陪诊员评分缓存（Redis），避免每次实时聚合。

---

## 2. 查询订单评价

`GET /api/review/order/{orderId}`　权限：订单相关方

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 2001,
    "orderId": 1001,
    "orderNo": "NL20260915000001",
    "score": 5,
    "tags": ["准时", "耐心", "沟通清楚"],
    "content": "小李很耐心，全程陪着老人，取药排队也帮忙，家里人很放心。",
    "isAnonymous": false,
    "familyName": "张三",
    "companionId": 10088,
    "companionName": "李*",
    "createTime": "2026-09-20 18:30:00"
  }
}
```

未评价时 `data` 为 `null`。

---

## 3. 陪诊员评价列表

`GET /api/review/companion/{companionId}`　权限：已登录

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `minScore` | Integer | 否 | 只看评分 ≥ N 星 |
| `hasContent` | Boolean | 否 | 只看带文字的评价 |

### 响应

结构同分页约定，`records` 为 `ReviewVO` 数组（匿名评价的 `familyName` 为 `匿***`）。

---

## 4. 陪诊员评分聚合

`GET /api/review/companion/{companionId}/score`　权限：已登录

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "companionId": 10088,
    "averageScore": "4.80",
    "totalCount": 37,
    "starDistribution": { "5": 30, "4": 5, "3": 1, "2": 0, "1": 1 },
    "goodRate": "94.59%"
  }
}
```

### 实现要点

- `averageScore` 必须与 `SELECT ROUND(AVG(score), 2) FROM order_review WHERE companion_id = ?` 一致（验收项）。
- 无评价时返回 `averageScore = "0.00"`、`totalCount = 0`（不是 `null`）。
- 排除被管理员判定为无效的评价（`is_valid = 0`）。

---

## 5. 提交投诉

`POST /api/complaint`　权限：已登录（家属投诉陪诊员 / 陪诊员投诉家属）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `orderId` | Long | ✓ | 须为当前用户相关订单 | 关联订单 ID |
| `type` | String | ✓ | 见投诉类型枚举 | 投诉类型 |
| `content` | String | ✓ | 10–1000 字符 | 投诉内容 |
| `evidence` | Array | 否 | ≤ 6 张 | 证据材料 URL 列表 |

```json
{
  "orderId": 1001,
  "type": "LATE",
  "content": "陪诊员比约定时间晚了 40 分钟到达，导致老人错过取号。",
  "evidence": ["/uploads/202609/ghi789.jpg"]
}
```

### 响应

```json
{
  "code": 200,
  "message": "投诉已提交，我们会尽快处理",
  "data": { "complaintId": 3001, "status": "PENDING", "statusLabel": "待处理" }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3004 | 无权投诉该订单 |
| 6003 | 内容包含敏感词 |
| 409 | 同一订单已存在未处理的投诉 |

### 实现要点

- 投诉人 / 被投诉人由订单关系自动推导，**不由前端传入**（防伪造）。
- 提交后向管理员推送站内信（M8），同时告知被投诉方。

---

## 6. 我的投诉列表

`GET /api/complaint`　权限：已登录

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `status` | String | 否 | 状态筛选 |
| `role` | String | 否 | `AS_COMPLAINANT`（我投诉的）/ `AS_TARGET`（投诉我的） |

### 响应

结构同分页约定，`records` 为 `ComplaintVO` 数组。

---

## 7. 投诉详情

`GET /api/complaint/{id}`　权限：投诉人 / 被投诉人 / ADMIN

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 3001,
    "orderId": 1001,
    "orderNo": "NL20260915000001",
    "complainantName": "张*",
    "targetUserName": "李*",
    "type": "LATE",
    "typeLabel": "迟到 / 未按时到达",
    "content": "陪诊员比约定时间晚了 40 分钟到达，导致老人错过取号。",
    "evidence": ["/uploads/202609/ghi789.jpg"],
    "status": "PROCESSING",
    "statusLabel": "处理中",
    "createTime": "2026-09-20 19:00:00"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 6004 | 投诉记录不存在 |
| 403 | 非相关方查看（越权） |

---

## 三、验收标准（M7）

- [ ] 「待接单 / 已接单 / 服务中」状态订单调用评价接口 → 返回 `6001`
- [ ] 同一订单重复评价 → 返回 `6002`（唯一约束生效，数据库无重复行）
- [ ] 陪诊员平均分 = `SELECT ROUND(AVG(score), 2)` 手工核对一致
- [ ] 提交评价后订单状态自动从 `COMPLETED` 变为 `REVIEWED`
- [ ] 匿名评价在列表与详情中均不暴露家属真实姓名
- [ ] 命中敏感词的评价 / 投诉无法提交，返回 `6003`
- [ ] 投诉提交后管理后台可见，状态可流转为「处理中 → 已结案」，每步记录操作人与时间
- [ ] 非相关方查看投诉详情 → `403`
- [ ] 无评价的陪诊员评分返回 `0.00` 而非 `null` 或报错
