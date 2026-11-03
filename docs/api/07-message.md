# 07 站内信与通知

> 覆盖模块：**M8 站内信与通知**
> 负责人：B（后端主导）+ A（顶栏红点与列表页）
> 归属迭代：W9–W12
> 全局约定见 [README.md](./README.md)

> **背景**：一期砍掉 IM 聊天，用站内信替代（计划书「范围控制」）。
> 站内信不做双向对话，只做**系统 → 用户**的单向通知。

---

## 一、数据模型

### MessageVO（站内信）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 消息 ID |
| `type` | String | 消息类型，见下表 |
| `typeLabel` | String | 中文类型名 |
| `title` | String | 标题（≤ 50 字符） |
| `content` | String | 正文（≤ 500 字符） |
| `bizType` | String | 关联业务类型：`ORDER` / `AUDIT` / `MEDICATION` / `COMPLAINT` / `SYSTEM` |
| `bizId` | Long | 关联业务 ID（用于点击跳转） |
| `linkUrl` | String | 前端跳转路径，如 `/family?orderId=1001` |
| `isRead` | Boolean | 是否已读 |
| `readTime` | String | 阅读时间 |
| `createTime` | String | 发送时间 |

### 消息类型

| 传值 | 中文 | 触发场景 | 接收人 |
|---|---|---|---|
| `ORDER_CREATED` | 新订单 | 家属下单成功 | 全部已审核陪诊员 |
| `ORDER_ACCEPTED` | 订单已接单 | 陪诊员接单 | 下单家属 |
| `ORDER_PROGRESS` | 陪诊进度 | 陪诊员打卡（M5） | 下单家属 |
| `ORDER_COMPLETED` | 服务已完成 | 陪诊员完成服务 | 下单家属 |
| `ORDER_CANCELLED` | 订单已取消 | 取消 / 纠纷处理 | 双方 |
| `AUDIT_RESULT` | 资质审核结果 | 管理员审核（M9） | 申请人 |
| `MEDICATION_REMIND` | 用药提醒 | 漏服扫描（M6） | 全部绑定家属 |
| `MEDICATION_MISSED` | 漏服提醒 | 漏服扫描（V4 · E1a） | 该老人**当天** `ACCEPTED` / `IN_SERVICE` 陪诊订单的接单陪诊员 |
| `COMPLAINT_SUBMITTED` | 新投诉待处理 | 家属提交投诉（M7） | **全部管理员** + 被投诉方 |
| `COMPLAINT_HANDLED` | 投诉处理结果 | 管理员处理（M9） | 投诉双方 |
| `REVIEW_REPLIED` | 评价被回复 | 陪诊员回复评价（E4） | 评价人（家属） |
| `REVIEW_APPEAL_SUBMITTED` | 收到评价申诉 | 陪诊员对评价申诉（E4） | 被申诉家属 + **全部管理员** |
| `REVIEW_INVALIDATED` | 评价被裁定无效 | 管理员裁定（E4） | 评价家属 + 陪诊员 |
| `FULFILLMENT_WARNING` | 订单匹配时间较长 | 风险升为「等待时间较长」（V3.1） | 下单家属 |
| `FULFILLMENT_URGENT` | 订单需要尽快安排陪诊员 | 风险升为「履约风险较高」（V3.1） | 下单家属 |
| `ORDER_URGED` | 有订单在等待陪诊员 | 家属催单（V3.1） | 全部资质已过审且处于接单中的陪诊员 |
| `ORDER_REASSIGNED` | 订单已改派陪诊员 | 管理员改派（V3.1） | 下单家属 + 目标陪诊员 + 原陪诊员（若有） |
| `SYSTEM_NOTICE` | 系统公告 | 管理员手动发布 | 全部用户 |

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | GET | `/api/message` | 已登录 | 消息列表（分页 + 按类型） |
| 2 | GET | `/api/message/unread-count` | 已登录 | 未读数 |
| 3 | GET | `/api/message/{id}` | 已登录 | 消息详情（**须为接收人**） |
| 4 | PUT | `/api/message/{id}/read` | 已登录 | 标记单条已读 |
| 5 | PUT | `/api/message/read-all` | 已登录 | 全部已读 |
| 6 | DELETE | `/api/message/{id}` | 已登录 | 删除消息 |
| 7 | GET | `/sse/message` | 已登录 | 未读数实时推送（**SSE 长连接**，事件名 `NEW_MESSAGE`；**不带 `/api` 前缀**，鉴权走 `Authorization` 头） |
| 8 | GET | `/api/message/notification-preference` | **仅 FAMILY** | 读取漏服站外短信 / 静默时段偏好 |
| 9 | PUT | `/api/message/notification-preference` | **仅 FAMILY** | 更新偏好（两个开关都必填） |

---

## 1. 消息列表

`GET /api/message`　权限：已登录（**只能看自己的消息**）

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页 |
| `type` | String | 否 | 消息类型筛选，可逗号分隔多值 |
| `isRead` | Boolean | 否 | `true` 已读 / `false` 未读，不传为全部 |
| `startDate` / `endDate` | String | 否 | 时间区间 |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 23,
    "page": 1,
    "size": 10,
    "pages": 3,
    "records": [
      {
        "id": 50001,
        "type": "ORDER_ACCEPTED",
        "typeLabel": "订单已接单",
        "title": "您的订单已被接单",
        "content": "陪诊员李师傅已接下订单 NL20260915000001（9月20日 09:30 海南省人民医院）。",
        "bizType": "ORDER",
        "bizId": 1001,
        "linkUrl": "/family?orderId=1001",
        "isRead": false,
        "createTime": "2026-09-15 17:02:11"
      }
    ]
  }
}
```

### 实现要点

- SQL 必须带 `receiver_id = 当前用户ID` 条件，并在 Service 层再校验一次归属（防越权，返回 `7002`）。
- 列表正文中的姓名一律脱敏，**不出现身份证号、完整手机号**。

---

## 2. 消息详情

`GET /api/message/{id}`　权限：已登录（须为接收人）

> **v1.1.0 新增**：前端消息详情页按 id 拉取本接口，取代旧版「列表页把整条消息
> base64 编码塞进 URL query」的做法（URL 超长易触发长度限制、复制链接即泄露消息内容）。

### 响应

`data` 为单条 MessageVO（结构同消息列表的 `records` 项）。

### 错误场景

| code | 场景 |
|---|---|
| `7001` | 消息不存在，或**已被接收人删除**（与列表口径一致：删了就查不到） |
| `7002` | 无权查看该消息（不是自己的消息） |

### 实现要点

- 复用 `markRead` / `delete` 的同一套归属校验（selectById 后比 `receiver_id`）。
- 只读接口，**不改变** `is_read`；标记已读仍走 `PUT /{id}/read`。

---

## 3. 未读数

`GET /api/message/unread-count`　权限：已登录

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 7,
    "byType": {
      "ORDER_ACCEPTED": 2,
      "ORDER_PROGRESS": 3,
      "MEDICATION_REMIND": 2
    }
  }
}
```

### 实现要点

- 用 Redis 缓存未读数（`message:unread:{userId}`），新消息时 `INCR`，已读时 `DECR`，定期与数据库校对。
- 前端顶栏铃铛红点只依赖本接口 + WebSocket 推送。

---

## 4. 标记单条已读

`PUT /api/message/{id}/read`　权限：已登录（须为接收人）

### 响应

```json
{ "code": 200, "message": "操作成功", "data": { "unreadCount": 6 } }
```

### 错误场景

| code | 场景 |
|---|---|
| 7001 | 消息不存在 |
| 7002 | 无权查看该消息（不是自己的消息） |

### 实现要点

- **幂等**：已读消息再次标记已读，不报错、不重复扣减未读数。

---

## 5. 全部已读

`PUT /api/message/read-all`　权限：已登录

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | 否 | 只标记某一类为已读，不传则全部 |

### 响应

```json
{ "code": 200, "message": "已全部标记为已读", "data": { "affected": 7, "unreadCount": 0 } }
```

---

## 6. 删除消息

`DELETE /api/message/{id}`　权限：已登录（须为接收人）

### 响应

```json
{ "code": 200, "message": "已删除", "data": null }
```

### 实现要点

- 逻辑删除，只对自己的视图生效（`receiver_deleted = 1`）。

---

## 7. 未读数实时推送（SSE）

`GET /sse/message`　权限：已登录（鉴权走 **`Authorization: Bearer <accessToken>` 请求头**）

> ⚠️ **本端点刻意不带 `/api` 前缀**：SSE 是长连接而非请求-响应接口，放在 `/api` 下会被前端统一
> Axios 封装的默认 30 秒超时掐断（见 `MessageSseController` 类注释）。前端必须显式用 `EventSource` 接入。
>
> ⚠️ **一期前端实际走轮询**：原生 `EventSource` **无法自定义请求头**，因此当前无法从浏览器直接订阅本端点。
> 前端 `startUnreadPolling` 每 60 秒调 `/api/message/unread-count` 兜底，功能完整。
> 若要拿到「秒级红点」，需后端把令牌改为支持 **query 参数**（与 `/ws/progress` 同一套做法）。

### 鉴权失败的行为（既定契约，勿当缺陷）

`/sse/**` 在 `SecurityConfig` 中是 `permitAll`（浏览器 `EventSource` 无法带请求头，令牌只能在建连后校验）。因此：

| 场景 | HTTP 状态 | 行为 |
|---|---|---|
| 未带令牌 / 令牌无效 / 令牌过期 | **200** | 立即下发一个空 `SseEmitter` 并关闭连接，**不下发任何事件**（既无 `CONNECTED` 也无 `NEW_MESSAGE`） |
| 令牌有效 | 200 | 保持长连接，先下发 `CONNECTED`，后续按 `NEW_MESSAGE` 推送 |

> 之所以不在握手阶段返回 401：SSE 客户端（含反向代理与监控探针）会把 401 记成错误并反复重试，
> 噪音淹没真实故障；「200 + 立即关闭」在客户端表现等价（收不到任何事件即视为未订阅成功）。
> **客户端判定口径**：收到 `CONNECTED` 才算订阅成功；否则按未订阅处理并回落 60 秒轮询。

### 推送消息格式

事件名 `NEW_MESSAGE`（`MessageSseHub.EVENT_NEW_MESSAGE`），`data` 为 JSON 字符串：

```json
{
  "messageId": 50002,
  "messageType": "ORDER_PROGRESS",
  "title": "陪诊进度更新",
  "content": "陪诊员已到达医院",
  "unreadCount": 8,
  "pushTime": "2026-09-20 09:10:05"
}
```

### 实现要点

- 服务端按 `userId` 维护 `Map<Long, Set<SseEmitter>>`，多端登录各自独立收推送。
- 连接超时 **30 分钟**，由浏览器 `EventSource` 自动重连；重连后先调 `/api/message/unread-count` 对齐数字，避免漏更新。
- 前端收到后：红点数字更新 + `ElNotification` 弹提示。
- **双通道读同一份数据库状态**：SSE 负责「3 秒内看到红点变化」，60 秒轮询负责在 Nginx 未配
  `proxy_buffering off`、企业代理掐长连接等环境下仍然可用。两条通道不会出现数字打架。

---

## 8. 通知偏好（V4 · P1-3）

`GET /api/message/notification-preference`　权限：**仅 FAMILY**
`PUT /api/message/notification-preference`　权限：**仅 FAMILY**

### 请求体（PUT）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `smsMissedEnabled` | Boolean | 是 | 是否接收漏服站外短信；关闭后**站内信仍然发送** |
| `quietHoursEnabled` | Boolean | 是 | 是否启用静默时段（22:00–08:00 不发送短信，顺延到次日 08:00） |

### 响应（GET / PUT 同构）

```json
{ "smsMissedEnabled": true, "quietHoursEnabled": true }
```

### 实现要点

- 偏好按「当前登录用户」读写，**入参不接收 userId**；FAMILY 之外的任何角色调用均返回 `403`。
- 从未设置过的用户返回两个 `true`（默认开启），**GET 不落库**，避免给全体家属回填无意义的行。
- 关闭站外短信只影响短信渠道：站内信仍按既有规则推送给全部绑定家属。
- PUT 成功文案「通知偏好已保存」，前端以返回值刷新本地开关，不再重复 GET。

---

## 9. 短信站外提醒编排（V4 · P1-3）

> 本节描述**非 HTTP 契约**：漏服扫描判定后，站内信照常发送，短信作为第二条渠道独立编排。

### 9.1 触发与失败隔离

1. `MedicationScheduler` 每 30 分钟扫描漏服（节奏与锁**不变**）。
2. `MedicationServiceImpl#scanMissedTasks` 判定漏服并写 `notify_sent`。
3. `InternalMessageReminderDispatcher#notifyFamily` 先发站内信，**之后**调用
   `SmsReminderService#enqueueMissedDose(task, familyIds)`。
4. 两个渠道各自 `try/catch`：站内信失败不阻断短信，短信失败不阻断站内信，也不影响漏服判定。

### 9.2 偏好与退订

- 每位家属读 `notification_preference`；**无记录视为默认开启**。
- `sms_missed_enabled=0` → 跳过短信入队与发送，站内信不受影响。
- `quiet_hours_enabled=0` → 该家属不受静默时段约束，立即发送；它与全局
  `nianglin.sms.quiet-hours.enabled` 是「与」关系：只有两者都开启才会顺延。

### 9.3 幂等键与单用户日频控

```
dedup_key = "medication-missed:" + taskId + ":" + userId + ":" + yyyy-MM-dd
```

- 先 `INSERT`，命中 `uk_dedup_key` 唯一键即视为该家属已入队，跳过该家属、继续处理下一位。
- 键是**任务 + 家属 + 日期**级：同一漏服任务下，每位开启了短信的家属**各收到一条**。
  `uk_dedup_key` 唯一索引结构与 V8 迁移**不变**，只是键值里多一段 `userId`。
- 冲突跳过只影响当前家属，不再 `return` 中断整个家属列表。

**单用户日频控**（防止一位家属被多个任务轰炸）：

- 入队前统计「当前用户 + `scene=missed-dose` + 当日（`create_time >= 今天 0 点`）」的行数，
  达到上限即跳过该用户，**不写回执行**、不调用短信通道；站内信不受影响。
- 计数口径是**行数**而非成功数：`PENDING`（静默顺延 / 待重试）、`SENT`、`DEAD`
  都占额度，避免「夜间排队 + 次日补偿」把上限放大成 2N。
- 阈值 `nianglin.sms.frequency.missed-dose-daily-cap`，默认 **3**，
  环境变量 `NIANGLIN_SMS_FREQUENCY_MISSEDDOSEDAILYCAP`；`< 1` 启动失败（fail-fast）。

### 9.4 静默时段（跨零点）

- 默认 `22:00–08:00`，可配置、可开关。
- 落在静默段内：写入 `status=PENDING`、`next_retry_at=次日 08:00`，**延后不丢**。
- 跨零点判定：`start > end` 时，`now >= start || now < end` 即为静默；
  `08:00` 这一分钟视为**非静默**（`[start, end)` 半开区间）。
- 静默时段只影响短信，站内信仍即时入库 / 推送。

### 9.5 发送、退避与死信

| 状态 | 含义 |
|---|---|
| `PENDING` | 待发送 / 待重试（静默顺延或退避中） |
| `SENT` | 已发送，写 `sent_at` |
| `FAILED` | 永久失败不重试（用户已注销、无手机号、任务已不存在） |
| `DEAD` | 死信：重试耗尽或合规词拦截 |

- 文案复用 `MessageTemplateUtil.content(MessageType.MEDICATION_REMIND, params)` 并先过
  `ComplianceCheckUtil.firstHit`；命中 → `DEAD` + `last_error=compliance:<词>`，**不静默丢弃**。
- 短信模板参数只放中性变量：`elderName`（已脱敏）+ `medicineName` + `planTime`；
  **禁止** dosage / 剂量 / 诊断 / 建议类词。
- 失败退避：第 1 次 `baseDelay`，第 2 次 `2 × baseDelay`，第 `n` 次 `baseDelay × 2^(n-1)`，
  上限 `maxDelay`。
- `attempt_count >= max-attempts` → `DEAD`，`last_error` 记录原因（截断 500 字）。
- `SmsRetryScheduler` 独立扫描 `status=PENDING && next_retry_at <= now`，Redis 锁
  `nianglin:lock:sms:retry`，**不复用** `MedicationScheduler` 的锁与节奏。

### 9.6 审计表 `sms_send_log`

| 字段 | 说明 |
|---|---|
| `biz_type` / `biz_id` | 业务类型（`MEDICATION_MISSED`）与 `medication_task.id` |
| `user_id` | 接收家属 ID |
| `phone_masked` | **只存脱敏号**（`138****8888`），禁止存完整手机号 |
| `scene` / `template_code` | `missed-dose` 与模板 Code 快照 |
| `status` / `attempt_count` / `last_error` | 状态机与失败原因 |
| `dedup_key` | 唯一键，见 9.3 |
| `scheduled_at` / `sent_at` / `next_retry_at` | 入队、成功、下次重试时间 |
| `create_time` / `update_time` / `deleted` | 审计与逻辑删除 |

> 日志只允许打印**脱敏手机号**与 `taskId`，禁止打印完整手机号、验证码或短信正文。

### 9.7 模板 Code 与配置项

| 场景 | Spring 配置键 | 生产环境变量 | dev 假值 |
|---|---|---|---|
| 找回密码 | `nianglin.sms.templates.verification` | `ALIYUN_SMS_TEMPLATE_CODE_VERIFICATION` | `SMS_DEV_VERIFICATION` |
| 漏服提醒 | `nianglin.sms.templates.missed-dose` | `ALIYUN_SMS_TEMPLATE_CODE_MISSED_DOSE` | `SMS_DEV_MISSED_DOSE` |

> `provider=aliyun` 时两个模板 Code 都必须非空，缺任一即**启动失败**（`SmsProperties#validate`）。
> 不再保留单 `templateCode` 字段，也不提供兼容层。

| 配置项 | 默认 | 说明 |
|---|---|---|
| `nianglin.sms.quiet-hours.enabled` | `true` | 静默时段总开关 |
| `nianglin.sms.quiet-hours.start` | `22:00` | 静默开始（HH:mm） |
| `nianglin.sms.quiet-hours.end` | `08:00` | 静默结束（HH:mm，跨零点） |
| `nianglin.sms.retry.max-attempts` | `3` | 最大尝试次数，达到后 `DEAD` |
| `nianglin.sms.retry.base-delay-ms` | `60000` | 首次退避 |
| `nianglin.sms.retry.max-delay-ms` | `3600000` | 单次退避上限 |
| `nianglin.sms.retry.scan-interval-ms` | `60000` | 重试扫描间隔 |
| `nianglin.sms.retry.batch-size` | `100` | 单轮扫描上限 |
| `nianglin.sms.frequency.missed-dose-daily-cap` | `3` | 单用户单日漏服短信上限（≥1；环境变量 `NIANGLIN_SMS_FREQUENCY_MISSEDDOSEDAILYCAP`） |

---

## 三、消息模板（统一在后端维护，禁止前端拼文案）

| 类型 | 标题模板 | 正文模板 |
|---|---|---|
| `ORDER_CREATED` | 新陪诊订单 | 有一笔新订单 {orderNo}（{visitTime} {hospital}），请及时接单。 |
| `ORDER_ACCEPTED` | 订单已接单 | 陪诊员 {companionName} 已接下订单 {orderNo}。 |
| `ORDER_PROGRESS` | 陪诊进度更新 | 备注为空：陪诊员已完成「{nodeLabel}」打卡（订单 {orderNo}）。<br>备注非空：陪诊员已完成「{nodeLabel}」打卡：{remark}（订单 {orderNo}）。 |
| `ORDER_COMPLETED` | 服务已完成 | 备注为空：订单 {orderNo} 已完成，感谢您的信任，欢迎评价。<br>**仲裁路径**（M9 强制完成，`reason` 非空）：订单 {orderNo} 已由平台仲裁完成（仲裁说明：{reason}）。感谢您的信任，欢迎评价。 |
| `ORDER_CANCELLED` | 订单已取消 | 订单 {orderNo} 已取消，原因：{reason}。 |
| `AUDIT_RESULT` | 资质审核结果 | 您的陪诊员资质申请{result}。{rejectReason} |
| `MEDICATION_REMIND` | 用药提醒 | {elderName} 的「{medicineName}」在 {planTime} 未确认服用，请及时关注。 |
| `MEDICATION_MISSED` | 漏服提醒 | 您正在陪诊的 {elderName} 在 {planTime} 未确认服用「{medicineName}」，请留意。 |
| `COMPLAINT_SUBMITTED` | 新投诉待处理 | {submitterName} 就订单 {orderNo} 提交了投诉（{typeLabel}），请及时处理。 |
| `COMPLAINT_HANDLED` | 投诉处理结果 | 您提交的投诉（{orderNo}）已处理完成：{handleResult} |
| `SYSTEM_NOTICE` | 系统公告 | {content} |

> 模板占位符由后端填充并脱敏，**前端只负责展示**。
>
> ⚠️ `ORDER_PROGRESS` 的 `{remark}` 是**可选**占位符：陪诊员打卡时大多不写备注
> （6 个节点里通常只有一两个会填）。若按其他模板的规则把缺失值渲染成 `—`，
> 家属收到的就是「出发：—（订单 NL2026…）」——一个破折号当正文。
> 因此该类型单独判定：**备注为空时改用不带冒号的句式**，而不是套用缺省占位符。
> `ORDER_COMPLETED` 的 `{reason}` 同为可选占位符，仅 M9 仲裁强制完成的路径传入。
>
> **短信站外提醒（V4 · P1-3）** 不新增正文模板，短信发送前用同一份 `MEDICATION_REMIND`
> 文案做合规检查；实际下发到短信模板的变量只有 `elderName`（脱敏）、`medicineName`、
> `planTime` 三个中性变量。阿里云控制台的两个模板正文也必须遵守「不做诊断、不开药方」，
> 不得出现剂量 / 诊断 / 建议类表述。

---

## 四、验收标准（M8）

- [ ] 触发订单接单事件后，家属账号站内信列表新增对应消息，未读数 +1
- [ ] 点击消息后未读数 -1，刷新页面状态不复原（已持久化）
- [ ] 未读数变化在 3 秒内推送到前端，无需刷新页面
- [ ] 分页接口在 100+ 条数据下返回正确页数；首页 / 末页 / 超范围页均不报错
- [ ] 站内信内容不包含身份证号等敏感明文
- [ ] 非本人消息调用「标记已读」→ 返回 `7002`
- [ ] 同一消息重复标记已读 → 幂等，不重复扣减未读数
- [ ] 四类核心消息（订单事件、审核结果、漏服提醒、系统公告）均能正常下发
- [ ] 消息详情（v1.1.0）：本人调用 `GET /api/message/{id}` 返回 200 且正文与列表一致，非本人 → `7002`（`MessageServiceTest#getDetailShouldReturnOwnMessage` / `#getDetailShouldReport7002ForOthersMessage`、`frontend/e2e/specs/07-message.spec.js`「消息详情（v1.1.0）：本人 200，别人的消息 7002（越权走服务端拦截）」）
- [ ] 消息不存在或已被接收人删除 → 详情接口返回 `7001`，且详情为只读（不写 `is_read`）（`MessageServiceTest#getDetailShouldReport7001WhenMissing` / `#getDetailShouldHideReceiverDeletedMessage`）
- [ ] 通知偏好（V4 · P1-3）：FAMILY 可读写两个开关，非 FAMILY 一律 `403`；从未设置过返回两个 `true`
- [ ] 站外短信（V4 · P1-3）：同一任务同一天、同一家属重复入队只落 1 行、只发 1 次；同一任务的两位家属各落 1 行；`sms_send_log.phone_masked` 不含完整手机号
- [ ] 单用户日频控：同一家属当日达到 `missed-dose-daily-cap` 后，新任务不再产生回执行；关闭开关的家属不产生任何行
- [ ] 关闭站外短信后漏服只发站内信，`sms_send_log` 无 `SENT` 行；静默时段内入队为 `PENDING` 且 `next_retry_at=次日 08:00`

---

## 五、变更记录

| 版本 | 日期 | 变更内容 | 变更人 |
|---|---|---|---|
| v1.0.0 | — | M8 初版 | — |
| v1.1.0 | 2026-09-29 | 新增 `GET /api/message/{id}` 消息详情（前端详情页按 id 拉取，废弃 base64 snapshot 传参）；`ORDER_COMPLETED` 模板增加可选 `{reason}`（M9 仲裁说明） | — |
| v1.2.0 | 2026-11-02 | V4 阶段 P0-2（E1a）：新增 `MEDICATION_MISSED` 漏服提醒类型（接收人为当天陪诊订单的接单陪诊员，`linkUrl` 指向 `/companion/execute/{orderId}`）；`MEDICATION_REMIND` 接收人口径收敛为「全部绑定家属」 | — |
| v1.2.1 | 2026-11-03 | 补写 §7「鉴权失败的行为」既定契约：无令牌/无效令牌返回 **200 + 立即关闭空 emitter**、不下发任何事件；客户端以收到 `CONNECTED` 为订阅成功判据（文档补记，行为未改） | — |
| v1.2.2 | 2026-11-03 | §9.3：`dedup_key` 加入 `userId`（同一任务每位家属各一条，唯一索引不变）；新增单用户单日频控 `nianglin.sms.frequency.missed-dose-daily-cap`（默认 3） | — |
| v1.3.0 | 2026-11-03 | V4 · P1-3：新增通知偏好接口（仅 FAMILY）与「短信站外提醒编排」章节；模板配置改为按场景路由（`verification` / `missed-dose`），生产新增两个模板 Code 环境变量 | — |
