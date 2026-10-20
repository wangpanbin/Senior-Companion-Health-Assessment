# 12 · 老人健康协同

> **模块**：M3 用户与档案管理（V3.2 老人健康协同中心）
> **相关**：[`02-elder-family.md`](./02-elder-family.md)（老人档案）· [`05-medication.md`](./05-medication.md)（服药日历）· [`11-order-chat.md`](./11-order-chat.md)（订单聊天）
> **口径字典**：[`../spec/metric-dictionary.md`](../spec/metric-dictionary.md)

---

## 〇、本模块的安全边界

健康数据是本项目里最敏感的一类：**就医记录、用药记录、漏服情况、就诊时间**都从这里出。

| 端点 | 角色门槛 | 归属校验 | 无关方 |
|---|---|---|---|
| `GET /api/family/health/summary` | FAMILY | 天然按当前家属的绑定关系取数 | — |
| `GET /api/family/elder/{elderId}/health/overview` | FAMILY | `ElderService.requireAccessible(elderId)` | `2006` |
| `GET /api/family/elder/{elderId}/health/timeline` | FAMILY | 同上 | `2006` |
| `GET /api/elder/health/overview` | ELDER | 老人档案 ID 从**登录态**取，不接受入参 | — |
| `GET /api/elder/health/timeline` | ELDER | 同上 | — |

⚠️ **角色注解只回答「你是这类角色吗」，回答不了「这条数据是不是你的」。**
一个 FAMILY 角色的家属去读别人绑定老人的健康数据，角色校验会放行，
只有归属校验能拦住 —— 返回 `2006`。

⚠️ **老人端不接受任何 `elderId` 入参。**
把 `elderId` 放进路径或查询参数，就等于开了一个「改一下参数就能看别人」的洞。
服务端从登录态直接取自己的档案 ID，这条路上没有可出错的地方。

### 合规红线

| 约束 | 落地 |
|---|---|
| 不做诊断、不开药方 | 全部文案是**事实陈述**（「今日有 1 次服药任务未确认」），不出现任何结论式判断 |
| 隐私最小化 | 老人姓名脱敏（`张*三`）；老人端不返回费用、催单、履约风险与家属姓名 |
| 聊天内容不进时间线 | 时间线只读 `internal_message`（站内信），**不读 `chat_message`**（M13 订单聊天）—— 两者天然隔离 |

---

## 一、数据模型

### 1.1 数据来源

| 语义 | 来源 | 说明 |
|---|---|---|
| 家属名下的老人 | `family_elder_relation` + `elder_profile` | 只取 `status = 'BOUND'` 的绑定关系 |
| 用药任务 | `medication_task` | **唯一自带 `elder_id` 的表**；三桶划分见 §1.2 |
| 陪诊订单 | `companion_order` | 走 `elder_id` 索引 |
| 陪诊打卡 | `order_checkin` | **没有 `elder_id`**，靠订单 ID 集合定位 |
| 站内信 | `internal_message` | **没有 `elder_id`**，靠 `biz_type` + `biz_id` 定位 |

### 1.2 用药三桶（唯一真源）

```
bucket = MISSED   当 was_missed = 1 或 status = 'MISSED'
bucket = PENDING  否则当 status = 'PENDING'
bucket = TAKEN    其余
```

- Java 侧：`MedicationBucket.of(status, wasMissed)`
- SQL 侧：`MedicationBucketSql.CASE_BUCKET`
- 两侧由 `MedicationBucketParityTest`（结构层）与 `StatisticsMapperMedicationFixTest`（真库层）双向锁死
- **三桶构成划分**：恒有 `taken + missed + pending == total`

> ⚠️ 这条口径在 V3.2 之前**有两套互相矛盾的实现**，
> M10 的统计甚至宣称「与服药日历一致」——那句话是错的。修正经过见 [ADR-0014](../adr/0014-medication-bucket-single-source.md)。

### 1.3 统计区间

| 项 | 约定 |
|---|---|
| 默认 | 最近 **30 天**（含今天） |
| 上限 | **366 天**，超出返回 `9002` |
| 形态 | **闭区间** `[开始日 00:00:00, 结束日 23:59:59]` |
| 落点 | `dto.DateRangeQuery#toRange()`，V3.2 / V3.3 / V3.5 与 M10 共用 |

---

## 二、接口列表

### 1. 家庭健康协同首页摘要

`GET /api/family/health/summary`　权限：FAMILY

无查询参数。

**响应**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "elderId": 401,
      "elderName": "张*海",
      "relationLabel": "儿子",
      "todayMedication": { "total": 3, "taken": 2, "pending": 1 },
      "recentVisitCount": 1,
      "pendingItemCount": 1,
      "lastActivityAt": "2026-09-16 09:30:00"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `elderId` | Long | 老人档案 ID |
| `elderName` | String | 老人姓名（**脱敏**） |
| `relationLabel` | String | 与当前家属的关系（儿子 / 女儿 / 亲属 / 其他） |
| `todayMedication.total` | Integer | 今日服药任务总数（按 `plan_date` 精确匹配当天） |
| `todayMedication.taken` | Integer | 今日已服数量 |
| `todayMedication.pending` | Integer | 今日待服数量 |
| `recentVisitCount` | Integer | 统计区间内已完成的陪诊次数 |
| `pendingItemCount` | Integer | 待处理事项**类别**数（不是事项条数） |
| `lastActivityAt` | String | 最近一次就诊时间；无则省略 |

**排序**：`lastActivityAt` 降序，其次 `elderId` 升序（稳定）。

**空数据**：没有绑定老人时返回 `data: []`（不报错、不返回 `null`）。

**实现要点**

- 摘要只包含**当前家属**名下 `status = 'BOUND'` 的绑定关系；解绑后自然消失。
- 「今日用药」是**当天**的事实，不随统计区间浮动 —— 与「本月用药完成率」是两个维度（见指标字典 §三）。
- `pendingItemCount` 与概览页 `pendingItems` 列表长度**恒等** —— 首页角标与详情不能对不上。

**错误场景**

| 码 | 场景 |
|---|---|
| 401 | 未登录 |
| 403 | 非 FAMILY 角色 |

---

### 2. 某位老人的健康概览

`GET /api/family/elder/{elderId}/health/overview`　权限：FAMILY

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `elderId` | Long | ✓ | 老人档案 ID（路径变量） |
| `startDate` | String | | `yyyy-MM-dd`，缺省 30 天前 |
| `endDate` | String | | `yyyy-MM-dd`，缺省今天 |

**响应**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "elder": {
      "id": 401,
      "name": "张*海",
      "gender": "MALE",
      "genderLabel": "男",
      "age": 82,
      "relationLabel": "儿子"
    },
    "range": { "startDate": "2026-09-21", "endDate": "2026-10-20", "days": 30 },
    "visit": {
      "orderCount": 2,
      "companionCount": 1,
      "lastVisitAt": "2026-10-16 09:30:00"
    },
    "medication": {
      "total": 30,
      "taken": 27,
      "missed": 2,
      "pending": 1,
      "completionRate": "90.00%"
    },
    "pendingItems": [
      {
        "type": "ORDER_WAITING",
        "typeLabel": "有订单在等待接单",
        "count": 1,
        "linkUrl": "/family/order"
      }
    ],
    "timelinePreview": [
      {
        "eventId": "ORDER:1031:COMPLETED",
        "category": "ORDER",
        "categoryLabel": "订单",
        "type": "COMPLETED",
        "typeLabel": "服务完成",
        "occurredAt": "2026-10-16 11:35:00",
        "title": "服务完成",
        "detail": "北京协和医院 · 心内科",
        "abnormal": null,
        "orderId": 1031,
        "medicationTaskId": null,
        "linkUrl": "/family/order/1031"
      }
    ]
  }
}
```

**口径**

| 指标 | 公式 |
|---|---|
| `visit.orderCount` | 区间内 `status IN ('COMPLETED','REVIEWED')` 的订单数（按 `visit_time` 落在区间） |
| `visit.companionCount` | 区间内服务过这位老人的陪诊员人数（去重） |
| `medication.completionRate` | `taken / total`；`total = 0` 时返回 `"0.00%"`（不是 `NaN%`） |
| `medication.*` | 三桶划分见 §1.2，恒有 `taken + missed + pending == total` |

**待处理事项（`pendingItems`）**

| type | 判定 | 链接 |
|---|---|---|
| `MEDICATION_PENDING` | 今日 `status = 'PENDING'` 的任务数 > 0 | `/family/medication` |
| `ORDER_WAITING` | 该老人 `status = 'PENDING'` 的订单数 > 0 | `/family/order` |
| `ORDER_TO_REVIEW` | `status = 'COMPLETED'` 且未评价的订单数 > 0 | `/family/order` |
| `FULFILLMENT_RISK` | `status = 'PENDING'` 且 `fulfillment_risk IN ('URGENT','MANUAL_REQUIRED')` | `/family/order` |

**错误场景**

| 码 | 场景 |
|---|---|
| 2001 | 老人档案不存在 |
| 2006 | 该老人不属于当前家属 |
| 9002 | 统计区间不合法（起止颠倒 / 超过 366 天） |
| 400 | 日期格式不是 `yyyy-MM-dd` |
| 401 / 403 | 未登录 / 非 FAMILY 角色 |

---

### 3. 某位老人的健康时间线

`GET /api/family/elder/{elderId}/health/timeline`　权限：FAMILY

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `elderId` | Long | ✓ | 老人档案 ID（路径变量） |
| `startDate` / `endDate` | String | | 同上，缺省最近 30 天 |
| `categories` | String | | 逗号分隔：`ORDER` / `CHECKIN` / `MEDICATION` / `MESSAGE`；不传取全部 |
| `limit` | Integer | | 单页条数，默认 50，最大 200 |
| `cursor` | String | | 游标，取上一页返回的 `nextCursor`；首页不传 |

**响应**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "elderId": 401,
    "elderName": "张*海",
    "range": { "startDate": "2026-09-21", "endDate": "2026-10-20", "days": 30 },
    "hasMore": true,
    "nextCursor": "MjAyNi0xMC0yMFQxMDowMDowMHwxMjM=",
    "items": [
      {
        "eventId": "MEDICATION:60003",
        "category": "MEDICATION",
        "categoryLabel": "用药",
        "type": "TAKEN",
        "typeLabel": "已服",
        "occurredAt": "2026-10-16 08:12:00",
        "title": "已服药",
        "detail": "苯磺酸氨氯地平片 · 1 片",
        "abnormal": null,
        "orderId": null,
        "medicationTaskId": 60003,
        "linkUrl": "/elder/medication"
      }
    ]
  }
}
```

**四类事件**

| category | 来源 | 事件 |
|---|---|---|
| `ORDER` | `companion_order` 的时间戳 + `order_review` | 已下单 / 已接单 / 开始服务 / 服务完成 / 已取消 / 已评价 |
| `CHECKIN` | `order_checkin` | 六个打卡节点（出发 / 到院 / 就诊中 / 取药 / 离院 / 完成）；异常打卡 `abnormal = true` |
| `MEDICATION` | `medication_task` | 已服（按 `confirm_time`）、漏服（按 `plan_time`） |
| `MESSAGE` | `internal_message` | 订单进度提醒、用药提醒；**只返回发给当前读者本人的** |

**排序与分页**

- 排序：`occurredAt` 降序，同一时刻按 `eventId` 升序（稳定）
- 游标：`base64(occurredAt|eventId)`，翻页与排序共用同一套比较器，不会漏条或重复
- `hasMore`：多取一条再裁剪

**实现要点**

- **待服不是事件**：`status = 'PENDING'` 的任务属于「待处理事项」，不出现在时间线上。
- 每类事件的取数有上限（200 条 / 类），超出按时间倒序截断。
- 站内信按 `biz_type` + `biz_id` 定位：订单类要求 `biz_id` 落在该老人的订单 ID 集合内；用药类要求 `biz_id = elderId`。

**错误场景**

| 码 | 场景 |
|---|---|
| 2001 | 老人档案不存在 |
| 2006 | 该老人不属于当前家属 |
| 400 | `categories` 取值不合法 / `limit` 越界 / 游标不合法 |
| 9002 | 统计区间不合法 |
| 401 / 403 | 未登录 / 非 FAMILY 角色 |

---

### 4. 我的健康概览（老人本人）

`GET /api/elder/health/overview`　权限：ELDER

**无任何入参** —— 老人档案 ID 从登录态取。

**响应**：结构与 §2 相同，但 `elder.relationLabel` **不返回**（老人端不展示家属关系）。

**错误场景**

| 码 | 场景 |
|---|---|
| 2001 | 尚未建立老人档案 |
| 401 / 403 | 未登录 / 非 ELDER 角色 |

---

### 5. 我的健康时间线（老人本人）

`GET /api/elder/health/timeline`　权限：ELDER

**无任何入参**（区间 / 分类 / 分页参数与 §3 相同）。

**与家属端的差异**

| 项 | 家属端 | 老人端 |
|---|---|---|
| 事件范围 | 四类全有 | 订单 / 打卡 / 用药 |
| `MESSAGE` | 含订单进度提醒 | **不含** —— 那是发给家属的站内信（`receiver_id` 是家属） |
| 订单事件链接 | `/family/order/{id}` | **不返回** —— 老人端暂无订单详情页 |
| 待处理事项 | 四类 | 只有「今日待服任务」 |

**错误场景**

| 码 | 场景 |
|---|---|
| 2001 | 尚未建立老人档案 |
| 400 / 9002 | 参数不合法 |
| 401 / 403 | 未登录 / 非 ELDER 角色 |

---

## 三、验收标准（V3.2）

### 3.1 计划书验收项（迭代计划 §29）

- [x] 家属可以进入老人健康协同中心
- [x] 能查看老人基础信息
- [x] 能查看用药汇总
- [x] 能查看就医记录
- [x] 能查看陪诊记录
- [x] 能查看健康时间线
- [x] 时间线数据来自真实业务数据
- [x] 不生成医疗诊断结论
- [x] 不改变现有用药业务规则
- [x] 原有 E2E 全部通过

### 3.2 本模块额外锁定（代码层面已强制）

- [x] 用药三桶之和恒等于总数（真库 parity 测试锁住）
- [x] 家属读他人老人 → 2006（越权矩阵 26 条）
- [x] 老人端不接受 `elderId` 入参
- [x] 老人端不返回费用 / 催单 / 履约风险 / 家属姓名
- [x] 时间线按发生时间倒序，游标翻页不重不漏
- [x] 非法 `categories` / 越界区间 → 400 / 9002，不静默降级
- [x] 每个指标都在指标字典里登记（`MetricDictionaryTest` 双向校验）

---

## 四、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-10-20 | v1.0 | 新增 V3.2 老人健康协同五个端点 |
