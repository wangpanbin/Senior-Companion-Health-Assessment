# 05 用药管理

> 覆盖模块：**M6 用药管理与漏服提醒**
> 负责人：D（建表）+ B（定时任务与推送）+ A（日历页）
> 归属迭代：迭代三 · W9–W10
> 前置依赖：M1 数据库、M8 站内信　全局约定见 [README.md](./README.md)

> ## ⚠️ 合规红线（本模块最容易踩线）
>
> 1. **不做诊断、不开药方。** 全模块文案定位为「记录与提醒」。
> 2. 药品字典**只返回通用信息**（通用名、规格、通用说明），**不提供剂量建议、不提供适应症判断、不提供替代药推荐**。
> 3. 所有药品详情响应必须携带 `disclaimer` 字段（免责声明），前端**必须展示**。
> 4. 全站代码与文案中禁止出现「建议服用」「推荐剂量」「可替代」「对症」等表述 —— 这是 M6 的验收检查项（全局搜索需为空）。

---

## 一、数据模型

### MedicineVO（药品字典）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 药品 ID |
| `name` | String | 通用名 |
| `tradeName` | String | 商品名，可为空 |
| `specification` | String | 规格，如 `5mg × 28 片` |
| `dosageForm` | String | 剂型：`TABLET` / `CAPSULE` / `INJECTION` / `LIQUID` / `OTHER` |
| `commonUsage` | String | 通用服用说明（**仅通用信息，如「口服，具体用法请遵医嘱」**） |
| `precautions` | String | 注意事项（如「可能引起嗜睡」「需避光保存」） |
| `storage` | String | 储存条件 |
| `disclaimer` | String | **免责声明**（必返） |

### MedicationPlanVO（用药计划）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 计划 ID |
| `elderId` | Long | 老人 ID |
| `elderName` | String | 老人姓名（脱敏） |
| `medicineId` | Long | 药品 ID |
| `medicineName` | String | 药品名 |
| `dosage` | String | 单次用量，如 `1 片`（**由家属按医嘱填写，系统不生成**） |
| `frequency` | Integer | 每日次数，1–4 |
| `timePoints` | Array | 服药时间点 `["08:00", "12:00", "18:00"]` |
| `startDate` | String | 开始日期 `yyyy-MM-dd` |
| `endDate` | String | 结束日期 `yyyy-MM-dd`，为空表示长期 |
| `mealRelation` | String | `BEFORE_MEAL` / `AFTER_MEAL` / `ANY` |
| `status` | String | `ACTIVE` / `DISABLED` |
| `remark` | String | 备注（如「医生让吃两周」） |

### MedicationTaskVO（服药任务）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 任务 ID |
| `planId` | Long | 所属计划 |
| `elderId` | Long | 老人 ID |
| `medicineName` | String | 药品名 |
| `dosage` | String | 单次用量 |
| `planTime` | String | 计划服药时间 `yyyy-MM-dd HH:mm:ss` |
| `status` | String | `PENDING` / `TAKEN` / `MISSED` |
| `statusLabel` | String | 待服 / 已服 / 漏服 |
| `confirmTime` | String | 实际确认时间 |
| `confirmBy` | Long | 确认人 ID |
| `confirmByName` | String | 确认人姓名 |

---

## 二、定时任务设计（本模块的技术亮点）

| 任务 | Cron | 频率 | 说明 |
|---|---|---|---|
| 生成当日服药任务 | `0 0 7 * * ?` | 每天 07:00 | 扫描所有 `ACTIVE` 计划，生成当天全部 `medication_task` |
| 漏服扫描与推送 | 每 30 分钟 | `FIXED_RATE` | 把超过阈值仍未确认的任务置为 `MISSED` 并推送家属 |

**Redis 分布式锁**（防止多实例重复执行）：

```
key   : nianglin:lock:medication:daily
命令  : SET key <uuid> NX PX 300000
释放  : Lua 脚本比对 uuid 后 DEL（不要直接 DEL，避免删掉别人的锁）
```

> ⚠️ 一期用 `SET NX PX` + Lua 即可，**不要依赖 Redisson 的高级特性**（本机 Redis 是 Windows 移植版，部分特性不可用）。

**幂等保证**：`medication_task` 上加唯一索引 `uk_plan_date_time (plan_id, plan_time)`，
即使定时任务被重复触发也不会产生重复行。

---

## 三、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | GET | `/api/medication/dict` | 已登录 | 药品字典分页搜索 |
| 2 | GET | `/api/medication/dict/{id}` | 已登录 | 药品详情（含免责声明） |
| 3 | GET | `/api/medication/plan` | 相关方 | 用药计划列表 |
| 4 | POST | `/api/medication/plan` | FAMILY | 新增用药计划 |
| 5 | PUT | `/api/medication/plan/{id}` | FAMILY | 修改用药计划 |
| 6 | DELETE | `/api/medication/plan/{id}` | FAMILY | 停用用药计划 |
| 7 | GET | `/api/medication/task/calendar` | 相关方 | 服药日历 |
| 8 | GET | `/api/medication/task/today` | 相关方 | 今日待服任务 |
| 9 | POST | `/api/medication/task/{id}/confirm` | FAMILY / COMPANION | 确认服药 |

---

## 1. 药品字典分页搜索

`GET /api/medication/dict`　权限：已登录

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `keyword` | String | 否 | 按通用名 / 商品名模糊搜索 |
| `dosageForm` | String | 否 | 按剂型筛选 |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 156,
    "page": 1,
    "size": 10,
    "pages": 16,
    "records": [
      {
        "id": 9001,
        "name": "苯磺酸氨氯地平片",
        "tradeName": "络活喜",
        "specification": "5mg × 28 片",
        "dosageForm": "TABLET",
        "commonUsage": "口服，具体用法用量请遵医嘱",
        "disclaimer": "本信息仅为药品通用资料，不构成任何用药建议。具体用法用量请遵医嘱或咨询药师。"
      }
    ]
  }
}
```

---

## 2. 药品详情

`GET /api/medication/dict/{id}`　权限：已登录

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 9001,
    "name": "苯磺酸氨氯地平片",
    "tradeName": "络活喜",
    "specification": "5mg × 28 片",
    "dosageForm": "TABLET",
    "commonUsage": "口服，具体用法用量请遵医嘱",
    "precautions": "可能引起头晕、踝部水肿；如出现不适请及时就医",
    "storage": "遮光，密封，在 30℃ 以下保存",
    "disclaimer": "本信息仅为药品通用资料，不构成任何用药建议。具体用法用量请遵医嘱或咨询药师。"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 5001 | 药品不存在 |

> ⚠️ 响应中**没有**、也不允许新增 `suggestedDosage`（建议剂量）、`indications`（适应症判断）、`alternatives`（替代药）等字段。

---

## 3. 用药计划列表

`GET /api/medication/plan`　权限：家属（自己绑定的老人）/ 陪诊员（**仅“正在陪诊”的老人**）/ 管理员

### 实现要点

- 陪诊员在跨上表接单、出发、到院、就诊、只问“老人现在吃的是哪个药、什么时间吃”，
  这是“陪诊服务过程中的正常信息需求”。一旦服务完成（订单变 `COMPLETED` / `REVIEWED` / `CANCELLED`），
  陪诊员与老人之间已没有“陪诊关系”，
  不应该还能查老人用药计划。详情见下面 §3.1 “陪诊员的访问边界”。

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `elderId` | Long | ✓ | 老人 ID（须有权限） |
| `status` | String | 否 | `ACTIVE` / `DISABLED` |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 3,
    "page": 1,
    "size": 10,
    "pages": 1,
    "records": [
      {
        "id": 7001,
        "elderId": 301,
        "elderName": "张*三",
        "medicineId": 9001,
        "medicineName": "苯磺酸氨氯地平片",
        "dosage": "1 片",
        "frequency": 1,
        "timePoints": ["08:00"],
        "startDate": "2026-09-01",
        "endDate": "2026-12-31",
        "mealRelation": "AFTER_MEAL",
        "status": "ACTIVE",
        "remark": "医生让每天早饭吃一片"
      }
    ]
  }
}
```

---

### 3.1 陪诊员的访问边界（仅 ACTIVE 状态的订单）

`MedicationServiceImpl.requireElderAccess(elderId)` 是本模块**唯一**的访问门控点
（同时被 `planPage` / `calendarPage` / `planDetail` / `taskConfirm` 调用）。

| 陪诊员与该老人之间的订单状态 | 能否读 / 改 |
|---|---|
| `PENDING` | ✅ 能（陪诊员在接单大厅看到这个单子，准备接） |
| `ACCEPTED` | ✅ 能（已接单，陪诊服务进行中） |
| `IN_SERVICE` | ✅ 能（已到场、就诊中、需查用药计划应答老人提问） |
| `COMPLETED` / `REVIEWED` | ❌ **不能**（陪诊服务已结束，账号与老人之间没有“陪诊关系”） |
| `CANCELLED` | ❌ **不能**（订单被取消，从未发生实质服务） |
| 没有任何订单 | ❌ **不能**（返 `2006`） |

> **为什么仅 ACTIVE 才算“陪诊关系”**：
> 1. 老人隐私。任职“6 个月前接过的陪诊员”仍能查老人用药计划，本质上是一道隐藏的隐私后门。
> 2. 业务一致性。COMPLETED 后陪诊员不会出现在老人的任何业务路径上，也不该出现在其数据路径上。
> 3. 错误信息可读。返回 `2006 无权操作该老人档案` 比“返回空列表”要诚实地多——后者会让代维误以为“老人没有计划”。

### 错误场景

| code | 场景 |
|---|---|
| 2001 | 老人档案不存在 |
| 2006 | 陪诊员无 ACTIVE 订单 / 家属未绑定 / 老人读非本人 / 跨家属读 |

> ⚠️ 2001 vs 2006 不拆分返回原因：老人 ID 只是自增数，不含业务含义，
> 泄露“某个 ID 是否存在”没有实际收益。

---

## 4. 新增用药计划

`POST /api/medication/plan`　权限：**FAMILY**（ELDER 调用返回 403）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `elderId` | Long | ✓ | 须为当前家属绑定 | 老人 ID |
| `medicineId` | Long | ✓ | 须存在 | 药品 ID |
| `dosage` | String | ✓ | ≤ 50 字符 | 单次用量（**家属按医嘱填写**） |
| `frequency` | Integer | ✓ | 1–4 | 每日次数 |
| `timePoints` | Array | ✓ | 长度须等于 `frequency` | 时间点，格式 `HH:mm` |
| `startDate` | String | ✓ | `yyyy-MM-dd` | 开始日期 |
| `endDate` | String | 否 | ≥ `startDate` | 结束日期，空表示长期 |
| `mealRelation` | String | ✓ | `BEFORE_MEAL` / `AFTER_MEAL` / `ANY` | 与饭点关系 |
| `remark` | String | 否 | ≤ 200 字符 | 备注 |

```json
{
  "elderId": 301,
  "medicineId": 9001,
  "dosage": "1 片",
  "frequency": 1,
  "timePoints": ["08:00"],
  "startDate": "2026-09-16",
  "endDate": "2026-12-31",
  "mealRelation": "AFTER_MEAL",
  "remark": "医生让每天早饭吃一片"
}
```

### 响应

```json
{ "code": 200, "message": "添加成功", "data": { "planId": 7001 } }
```

### 错误场景

| code | 场景 |
|---|---|
| 5001 | 药品不存在 |
| 5002 | 用药计划时间区间不合法（结束日期早于开始日期） |
| 400 | `timePoints` 长度与 `frequency` 不一致 |
| 403 | ELDER 角色调用 |

### 实现要点

- 新增计划后**不需要**立即生成历史任务；只生成从今天起的任务（由定时任务或即时补生成处理）。
- 系统**不校验也不建议**用量是否合理 —— 那是医嘱的范畴，系统只做记录。

---

## 5. 修改用药计划

`PUT /api/medication/plan/{id}`　权限：FAMILY（须为该老人绑定人）

请求体同「新增」，所有字段可选。

### 响应

```json
{ "code": 200, "message": "保存成功", "data": null }
```

### 实现要点

- 修改 `timePoints` 或 `frequency` 后，**只影响未来未生成的任务**，已生成的历史任务不变（保留服药记录的真实性）。

---

## 6. 停用用药计划

`DELETE /api/medication/plan/{id}`　权限：FAMILY

### 响应

```json
{ "code": 200, "message": "已停用", "data": null }
```

### 实现要点

- **不物理删除**，状态改为 `DISABLED`，历史服药记录必须保留。
- 停用后不再生成新的服药任务；已生成的未确认任务标记为失效。

---

## 7. 服药日历

`GET /api/medication/task/calendar`　权限：相关方

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `elderId` | Long | ✓ | 老人 ID |
| `startDate` | String | ✓ | `yyyy-MM-dd`，区间不超过 31 天 |
| `endDate` | String | ✓ | `yyyy-MM-dd` |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "elderId": 301,
    "elderName": "张*三",
    "startDate": "2026-09-14",
    "endDate": "2026-09-20",
    "summary": {
      "totalCount": 7,
      "takenCount": 5,
      "missedCount": 1,
      "pendingCount": 1,
      "missedRate": "14.29%"
    },
    "days": [
      {
        "date": "2026-09-15",
        "tasks": [
          {
            "id": 60001,
            "medicineName": "苯磺酸氨氯地平片",
            "dosage": "1 片",
            "planTime": "2026-09-15 08:00:00",
            "status": "TAKEN",
            "statusLabel": "已服",
            "confirmTime": "2026-09-15 08:12:00",
            "confirmByName": "张三"
          }
        ]
      }
    ]
  }
}
```

### 实现要点

- `summary.missedRate` 是本模块的量化指标，用于答辩展示「漏服率从 X% 降到 Y%」。
- 日历数据必须与 `SELECT * FROM medication_task WHERE elder_id=? AND plan_time BETWEEN ? AND ?` 逐条一致。

---

## 8. 今日待服任务

`GET /api/medication/task/today`　权限：相关方

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `elderId` | Long | ✓ | 老人 ID |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "id": 60003,
      "medicineName": "苯磺酸氨氯地平片",
      "dosage": "1 片",
      "planTime": "2026-09-15 08:00:00",
      "status": "MISSED",
      "statusLabel": "漏服",
      "mealRelation": "AFTER_MEAL",
      "mealRelationLabel": "饭后"
    }
  ]
}
```

### 用途

老人端大字版首页直接渲染这个列表（**只读**），家属端在此提供「已服用」按钮。

---

## 9. 确认服药

`POST /api/medication/task/{id}/confirm`　权限：FAMILY / COMPANION（老人本人不可操作）

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `confirmTime` | String | 否 | 实际服药时间，不传取当前时间 |
| `remark` | String | 否 | 备注，如「今天外出，晚了 1 小时」 |

### 响应

```json
{
  "code": 200,
  "message": "已记录",
  "data": {
    "taskId": 60003,
    "status": "TAKEN",
    "statusLabel": "已服",
    "confirmTime": "2026-09-15 09:05:00",
    "confirmByName": "张三"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 5003 | 该服药任务已确认 |
| 400 | 无权确认（非绑定家属，且非该老人订单的陪诊员） |
| 403 | ELDER 角色调用 |

### 实现要点

- 允许补确认（漏服后补记），此时 `status` 置为 `TAKEN`，同时保留 `wasMissed = true` 标记，便于统计。
- 记录 `confirm_by`（操作人）与 `confirm_time`，满足审计要求。

---

## 四、验收标准（M6）

- [ ] 手动触发定时任务两次，`medication_task` **不出现重复行**（唯一索引 + 分布式锁双重保障）
- [ ] 把某条任务时间设为过去时间，下一次扫描后状态自动变为 `MISSED` 并推送到位
- [ ] 日历页显示结果与 `SELECT * FROM medication_task WHERE elder_id=? AND plan_time BETWEEN ? AND ?` 逐条一致
- [ ] 并发启动 2 个后端实例，定时任务**仅 1 个实例实际执行**（日志可证）
- [ ] 全站搜索「建议服用」「推荐剂量」「对症」「可替代」→ **结果为空**
- [ ] 药品详情响应包含 `disclaimer` 字段，前端页面已展示免责声明
- [ ] 服药确认记录中包含操作人与操作时间
- [ ] 停用计划后不再生成新任务，历史记录完整保留
- [ ] ELDER 角色调用新增计划 / 确认服药 → `403`
- [ ] 陪诊员读「无 ACTIVE 订单」的老人计划 → `2006`（不限 COMPLETED / REVIEWED / CANCELLED 状态）
