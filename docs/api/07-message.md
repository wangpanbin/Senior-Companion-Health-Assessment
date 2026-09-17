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
| `MEDICATION_REMIND` | 用药提醒 | 漏服扫描（M6） | 下单家属 / 绑定家属 |
| `COMPLAINT_SUBMITTED` | 新投诉待处理 | 家属提交投诉（M7） | **全部管理员** + 被投诉方 |
| `COMPLAINT_HANDLED` | 投诉处理结果 | 管理员处理（M9） | 投诉双方 |
| `SYSTEM_NOTICE` | 系统公告 | 管理员手动发布 | 全部用户 |

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | GET | `/api/message` | 已登录 | 消息列表（分页 + 按类型） |
| 2 | GET | `/api/message/unread-count` | 已登录 | 未读数 |
| 3 | PUT | `/api/message/{id}/read` | 已登录 | 标记单条已读 |
| 4 | PUT | `/api/message/read-all` | 已登录 | 全部已读 |
| 5 | DELETE | `/api/message/{id}` | 已登录 | 删除消息 |
| 6 | GET | `/sse/message` | 已登录 | 未读数实时推送（**SSE 长连接**，事件名 `NEW_MESSAGE`；**不带 `/api` 前缀**，鉴权走 `Authorization` 头） |

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

## 2. 未读数

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

## 3. 标记单条已读

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

## 4. 全部已读

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

## 5. 删除消息

`DELETE /api/message/{id}`　权限：已登录（须为接收人）

### 响应

```json
{ "code": 200, "message": "已删除", "data": null }
```

### 实现要点

- 逻辑删除，只对自己的视图生效（`receiver_deleted = 1`）。

---

## 6. 未读数实时推送（SSE）

`GET /sse/message`　权限：已登录（鉴权走 **`Authorization: Bearer <accessToken>` 请求头**）

> ⚠️ **本端点刻意不带 `/api` 前缀**：SSE 是长连接而非请求-响应接口，放在 `/api` 下会被前端统一
> Axios 封装的默认 30 秒超时掐断（见 `MessageSseController` 类注释）。前端必须显式用 `EventSource` 接入。
>
> ⚠️ **一期前端实际走轮询**：原生 `EventSource` **无法自定义请求头**，因此当前无法从浏览器直接订阅本端点。
> 前端 `startUnreadPolling` 每 60 秒调 `/api/message/unread-count` 兜底，功能完整。
> 若要拿到「秒级红点」，需后端把令牌改为支持 **query 参数**（与 `/ws/progress` 同一套做法）。

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

## 三、消息模板（统一在后端维护，禁止前端拼文案）

| 类型 | 标题模板 | 正文模板 |
|---|---|---|
| `ORDER_CREATED` | 新陪诊订单 | 有一笔新订单 {orderNo}（{visitTime} {hospital}），请及时接单。 |
| `ORDER_ACCEPTED` | 订单已接单 | 陪诊员 {companionName} 已接下订单 {orderNo}。 |
| `ORDER_PROGRESS` | 陪诊进度更新 | 备注为空：陪诊员已完成「{nodeLabel}」打卡（订单 {orderNo}）。<br>备注非空：陪诊员已完成「{nodeLabel}」打卡：{remark}（订单 {orderNo}）。 |
| `ORDER_COMPLETED` | 服务已完成 | 订单 {orderNo} 已完成，感谢您的信任，欢迎评价。 |
| `ORDER_CANCELLED` | 订单已取消 | 订单 {orderNo} 已取消，原因：{reason}。 |
| `AUDIT_RESULT` | 资质审核结果 | 您的陪诊员资质申请{result}。{rejectReason} |
| `MEDICATION_REMIND` | 用药提醒 | {elderName} 的「{medicineName}」在 {planTime} 未确认服用，请及时关注。 |
| `COMPLAINT_SUBMITTED` | 新投诉待处理 | {submitterName} 就订单 {orderNo} 提交了投诉（{typeLabel}），请及时处理。 |
| `COMPLAINT_HANDLED` | 投诉处理结果 | 您提交的投诉（{orderNo}）已处理完成：{handleResult} |
| `SYSTEM_NOTICE` | 系统公告 | {content} |

> 模板占位符由后端填充并脱敏，**前端只负责展示**。
>
> ⚠️ `ORDER_PROGRESS` 的 `{remark}` 是**可选**占位符：陪诊员打卡时大多不写备注
> （6 个节点里通常只有一两个会填）。若按其他模板的规则把缺失值渲染成 `—`，
> 家属收到的就是「出发：—（订单 NL2026…）」——一个破折号当正文。
> 因此该类型单独判定：**备注为空时改用不带冒号的句式**，而不是套用缺省占位符。

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
