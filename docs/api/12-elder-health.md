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
| `GET /api/family/elder/{elderId}/vitals` | FAMILY | `ElderService.requireAccessible(elderId)` | `2006` |
| `GET /api/family/elder/{elderId}/vitals/trend` | FAMILY | 同上 | `2006` |
| `POST /api/family/elder/{elderId}/vitals` | FAMILY | 同上 | `2006` |
| `DELETE /api/family/vitals/{recordId}` | FAMILY | 记录归属的老人可访问 **且** 录入人=当前家属 | `2006` / `2010` |
| `GET /api/elder/vitals` | ELDER | 老人档案 ID 从**登录态**取，不接受入参 | — |
| `GET /api/elder/vitals/trend` | ELDER | 同上 | — |
| `POST /api/execution/{orderId}/vitals` | COMPANION | 订单存在 `3001` → 本单陪诊员 `3004` → `IN_SERVICE` `3002` | `3001` / `3004` / `3002` |
| `GET /api/execution/{orderId}/vitals` | COMPANION | 订单存在 `3001` → 本单陪诊员 `3004` → `IN_SERVICE / COMPLETED / REVIEWED` `3002` | `3001` / `3004` / `3002` |
| `DELETE /api/execution/{orderId}/vitals/{recordId}` | COMPANION | 同上 + 记录须属本单且由本人代录 | 同上 / `2010` |

> **陪诊员链路刻意不走 `ElderService#requireAccessible`**：该方法对 COMPANION 一律返回 `2006`，
> 而陪诊员代录的正当性来自**订单**，不来自家庭绑定关系。因此校验顺序固定为
> 「订单存在 → 本单陪诊员 → 订单状态」，不引入第二次归属口径。
> 读与写的状态边界不同：**写 / 撤回要求「服务中」，读取允许「服务中 / 已完成 / 已评价」**，
> 服务结束后陪诊员仍要能回看本单记录。

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
| 体征只记录、不判断 | 体征接口只返回数值、单位、时间与录入来源；**不内置参考区间**，不返回分级 / 解释字段，不出现偏高 / 偏低 / 异常 / 建议就医等判断性文案 |

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
| 体征记录 | `vital_sign_record` | 单表装三类体征；`dedup_key` 为 STORED 生成列（`elder_id + vital_type + 分钟`），逻辑删除时置 `NULL` 释放唯一槽位 |

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
    "todayMedication": { "total": 3, "taken": 2, "missed": 0, "pending": 1 },
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
| `medication.*` | **统计区间**口径的三桶与完成率 |
| `todayMedication.*` | **当天**口径的三桶（按 `plan_date = 今天` 精确匹配，**不随统计区间浮动**）。<br>老人端的「今日已服药 / 还有 N 次没吃」用的是这一份 —— 用区间口径冒充会让家属/老人看到 30 天的数字被说成「今天」 |
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
    "truncated": false,
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
- `truncated`：任一类别触及取数上限（每类 200 条）时为 `true` —— **表示这段时间的记录没有全部返回**，
  前端必须如实提示，不能让用户以为看到的就是全部

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
| `MESSAGE` | 含订单进度提醒 | **不含订单进度提醒** —— 那是发给家属的站内信（`receiver_id` 是家属）。<br>**仍会有 `MESSAGE` 分类**，但只含发给老人本人的**用药提醒**（`MEDICATION_REMIND`） |
| 订单事件链接 | `/family/order/{id}` | **不返回** —— 老人端暂无订单详情页 |
| 待处理事项 | 四类 | 只有「今日待服任务」 |

**错误场景**

| 码 | 场景 |
|---|---|
| 2001 | 尚未建立老人档案 |
| 400 / 9002 | 参数不合法 |
| 401 / 403 | 未登录 / 非 ELDER 角色 |

---

### 6. 体征时间线（家属）

`GET /api/family/elder/{elderId}/vitals`　权限：FAMILY

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `elderId` | path | 是 | 老人档案 ID；不属于当前家属时 `2006` |
| `vitalType` | query | 否 | `BLOOD_PRESSURE` / `BLOOD_GLUCOSE` / `WEIGHT`；不传取全部 |
| `startDate` / `endDate` | query | 否 | `yyyy-MM-dd`，闭区间；默认最近 30 天，上限 366 天（`9002`） |
| `page` / `size` | query | 否 | 默认 `1 / 10`，`size` 上限 100 |

**响应**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 2,
    "page": 1,
    "size": 10,
    "pages": 1,
    "records": [
      {
        "id": 10,
        "elderId": 401,
        "vitalType": "BLOOD_PRESSURE",
        "vitalTypeLabel": "血压",
        "measuredAt": "2026-11-04 16:34:00",
        "valueText": "128/78 mmHg",
        "unit": "mmHg",
        "systolic": 128,
        "diastolic": 78,
        "sourceType": "FAMILY_MANUAL",
        "sourceTypeLabel": "家属代录"
      }
    ]
  }
}
```

| 字段 | 说明 |
|---|---|
| `valueText` | 服务端拼装的展示文本：血压 `128/78 mmHg`、血糖 `6.2 mmol/L · 空腹`、体重 `62.5 kg`；三端一致 |
| `systolic` / `diastolic` | 仅血压返回 |
| `glucoseValue` / `glucoseContext` / `glucoseContextLabel` | 仅血糖返回；场景为 `FASTING` / `POSTPRANDIAL` / `RANDOM` |
| `weightKg` | 仅体重返回 |
| `sourceType` / `sourceTypeLabel` | `FAMILY_MANUAL`（家属代录）/ `COMPANION_SERVICE`（陪诊员服务代录） |
| `orderId` | 仅陪诊员代录时返回 |

**排序**：`measured_at DESC, id DESC`（同分钟多条时按 ID 兜底，翻页稳定）。

**不返回**：录入人姓名 / 手机号、任何区间、分级或解释性字段。

**错误场景**

| 码 | 场景 |
|---|---|
| 2006 | 该老人不属于当前家属 |
| 400 | `vitalType` 超过 24 字符 / `page` / `size` 越界 |
| 9002 | 统计区间不合法 |
| 401 / 403 | 未登录 / 非 FAMILY 角色 |

---

### 7. 体征趋势（家属）

`GET /api/family/elder/{elderId}/vitals/trend`　权限：FAMILY

参数同 §6，差异：**`vitalType` 必填**，不接受 `page` / `size`（趋势一次取全）。

**响应**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "vitalType": "BLOOD_PRESSURE",
    "vitalTypeLabel": "血压",
    "unit": "mmHg",
    "range": { "startDate": "2026-10-06", "endDate": "2026-11-04", "days": 30 },
    "pointCount": 1,
    "truncated": false,
    "points": [
      {
        "measuredAt": "2026-11-04 16:34:00",
        "valueText": "128/78 mmHg",
        "systolic": 128,
        "diastolic": 78
      }
    ]
  }
}
```

| 字段 | 说明 |
|---|---|
| `range` | 本次取数的闭区间与天数（含首尾） |
| `pointCount` | `points` 的实际长度 |
| `truncated` | 区间内超过 **500** 条时为 `true`，只返回最近 500 条并如实告知 |
| `points` | 按测量时间**升序**；无数据时为空数组 |

**错误场景**

| 码 | 场景 |
|---|---|
| 400 | `vitalType` 缺失或取值不合法 |
| 2006 / 9002 / 401 / 403 | 同 §6 |

---

### 8. 代录体征（家属）

`POST /api/family/elder/{elderId}/vitals`　权限：FAMILY

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vitalType` | String | 是 | 三类之一 |
| `measuredAt` | DateTime | 是 | 分钟精度；服务端截断到分钟，允许 5 分钟以内的设备时钟偏差 |
| `systolic` / `diastolic` | Integer | 血压时必填 | 收缩压 50–300、舒张压 30–200，且舒张压 < 收缩压 |
| `glucoseValue` | Decimal | 血糖时必填 | 1.0–33.3，最多 1 位小数 |
| `glucoseContext` | String | 血糖时必填 | `FASTING` / `POSTPRANDIAL` / `RANDOM` |
| `weightKg` | Decimal | 体重时必填 | 10.0–300.0，最多 1 位小数 |

> **这些上下限是「合法性校验」，不是医学参考区间。** 目的是拦住 `1280/78`、`体重 6.25` 这类误录，
> 接口与文案不据此给出任何结论。
>
> 三类字段**互斥**：填了当前类型用不到的字段一律 `400`。
> 请求体**刻意不含** `elderId` / `sourceType` / `operatorUserId` / `orderId` / 备注 ——
> 归属与来源由路径和登录态决定，备注则不给判断性描述留入口。

**唯一性**：同一老人 + 同一类型 + **同一分钟**只允许一条，重复录入返回 `2008`。
去重由数据库唯一键（STORED 生成列）实现，不依赖「先查再插」；撤回（逻辑删除）后该分钟槽位自动释放。

**响应**：`201` 风格的业务成功 —— `code=200`，`data` 为本次创建的记录（结构见 §6 列表元素）。

**错误场景**

| 码 | 场景 |
|---|---|
| 2006 | 该老人不属于当前家属 |
| 2008 | 同一分钟同类型已有记录 |
| 400 | 字段互斥 / 数值超界 / 小数位超 1 / 未来时间超过容差 / 类型非法 |

---

### 9. 撤回体征记录（家属）

`DELETE /api/family/vitals/{recordId}`　权限：FAMILY

**响应**

```json
{ "code": 200, "message": "记录已撤回", "data": { "recordId": 6, "deleted": true } }
```

逻辑删除，记录从列表与趋势中消失；同一分钟可立即重新录入。

| 码 | 场景 |
|---|---|
| 2009 | 记录不存在或已撤回 |
| 2006 | 记录所属老人不属于当前家属 |
| 2010 | 记录不是本人代录（家属只能撤回自己录的） |

---

### 10. 我的体征时间线（老人本人）

`GET /api/elder/vitals`　权限：ELDER

**无 `elderId` 入参**：档案从登录态取；参数与响应结构同 §6。

**写请求一律被拒**：老人账号为只读模式，前端不提供任何录入 / 撤回入口。

> **403 与 405 的语义说明（刻意保留，不是缺陷）**
>
> 老人端的写请求会因请求形态不同落到两条不同的拒绝路径：
>
> | 请求形态 | 拒绝方 | 状态码 |
> |---|---|---|
> | 已映射的写方法（如 `DELETE /api/family/vitals/{recordId}`、`POST /api/family/elder/{elderId}/vitals`） | `ElderReadOnlyInterceptor` | `403` |
> | 老人端**未映射**的写方法（如 `POST /api/elder/vitals`，该路径只定义了 GET） | Spring MVC（在拦截器之前完成映射判断） | `405` |
>
> 两者**均无业务入口、均不落库**：403 是拦截器在处理器执行前拒绝，405 是根本没有对应的处理器可执行。
> 本期**刻意不**在 Filter 里把 405 统一改写为 403 —— 那会改动全局安全链，只为让状态码看起来一致，
> 收益为零、回归风险不为零。前端不渲染任何老人端写入口，正常用户不会触发这两条路径。

---

### 11. 我的体征趋势（老人本人）

`GET /api/elder/vitals/trend`　权限：ELDER

**无 `elderId` 入参**；参数与响应结构同 §7。

---

### 12. 服务中代录体征（陪诊员）

`POST /api/execution/{orderId}/vitals`　权限：COMPANION

**请求体**：同 §8（不含 `elderId` / `orderId`；老人取订单关联档案）。

**校验顺序**（顺序固定，先失败者先返回）：

1. 订单不存在 → `3001`
2. 当前用户不是本单陪诊员 → `3004`
3. 订单不是「服务中」→ `3002`
4. 数值 / 去重校验 → `400` / `2008`

**响应**：`data` 为本次记录，`sourceType=COMPANION_SERVICE`，`orderId` 为当前订单。

---

### 13. 撤回服务中代录的体征（陪诊员）

`DELETE /api/execution/{orderId}/vitals/{recordId}`　权限：COMPANION

校验顺序同上；另加两条归属判定：记录不属于本单 → `3004`；不是本人代录 → `2010`。

**响应**：同 §9。

---

### 14. 按订单查询本单体征（陪诊员）

`GET /api/execution/{orderId}/vitals`　权限：COMPANION

**查询参数**：同 §6（`vitalType` / `startDate` / `endDate` / `page` / `size`，均可选），
默认按测量时间倒序分页，返回结构与 §6 一致。

**校验顺序**（顺序固定，先失败者先返回）：

1. 订单不存在 → `3001`
2. 当前用户不是本单陪诊员 → `3004`
3. 订单状态不在 `IN_SERVICE / COMPLETED / REVIEWED` 内 → `3002`

**只返回本订单关联的代录记录**：按 `order_id` 过滤，不按老人档案过滤 ——
同一位老人可能有多单，按老人取会把别的订单代录的记录混进本单。

**用途**：陪诊员执行页首屏与刷新后回显本单已录入的体征；服务结束后仍可回看，
但不提供录入 / 撤回入口（那是 §12 / §13，且仅限服务中）。

> 边界说明：`PENDING / ACCEPTED / CANCELLED` 一律 `3002` —— 尚未进入服务时没有「本单记录」可看，
> 已取消的订单也不再向陪诊员开放。

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

### 3.3 体征记录（V4 · P2-2）

- [x] 三端读：家属 / 老人 / 陪诊员（陪诊员可按订单回看本单已录入的记录）
- [x] 唯一键按「老人 + 类型 + 分钟」去重，重复返回 `2008`，撤回后槽位释放
- [x] 三类数值字段互斥；上下限只做合法性校验，不内置参考区间
- [x] 老人端只读：写请求被 `ElderReadOnlyInterceptor` 拒绝，且无 `elderId` 入参
- [x] 陪诊员代录走「订单存在 → 本单陪诊员 → 服务中」链路，错误码 `3001 / 3004 / 3002`
- [x] 趋势按时间升序、超过 500 点以 `truncated=true` 如实告知
- [x] 新增后端 / 前端文件禁用词扫描命中数为 0（禁用词口径见 `docs/spec/V4-P2-2-vitals-spec.md` §8.2）
- [x] e2e `backend/sql/tools/e2e_vital_sign.py` 全量检查通过，清理后无残留（检查项数以脚本实跑输出为准，不手抄）

---

## 四、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-10-20 | v1.0 | 新增 V3.2 老人健康协同五个端点 |
| 2026-11-04 | v1.1 | 新增 V4 · P2-2 体征记录八个端点（家属 4 / 老人 2 / 陪诊员 2）与错误码 `2008 / 2009 / 2010` |
| 2026-11-04 | v1.2 | 新增 §14 陪诊员按订单查询体征（第 9 个端点）；§10 补充老人写请求 403 / 405 语义说明 |
