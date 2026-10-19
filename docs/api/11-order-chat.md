# 11 订单聊天（家属↔陪诊员）

> 覆盖模块：**M13 订单聊天（二期扩围）**
> 负责人：A（前端聊天界面）+ B（后端服务与实时通道）
> 归属迭代：二期 · R 系列之后
> 全局约定见 [README.md](./README.md)
> 决策依据：[ADR-0011](../adr/0011-order-chat.md)（一期红线放开的正式记录）

> **背景**：一期以站内信（[07-message.md](./07-message.md)）替代 IM，站内信是系统→用户的单向通知。
> 二期扩围后，家属与接单陪诊员可在**订单内**双向聊天（文字 / 图片 / mp4 视频），
> 老人（ELDER）对自己订单的会话**只读**，管理员（ADMIN）**仲裁只读**。
> 聊天室顶部必须展示免责声明：不做诊断、不开药方，紧急情况请拨打 120。

---

## 一、数据模型

### 会话身份

**没有会话表**。一个陪诊订单就是一个会话：

- 会话双方 = `companion_order.family_id`（下单家属）与 `companion_order.companion_id`（接单陪诊员）；
- `companion_id` 为空（`PENDING` 未接单）时会话不存在，所有读写返回 `7003`；
- 可聊状态 = 订单存在且已接单（`ACCEPTED / IN_SERVICE / COMPLETED / REVIEWED / CANCELLED` 均可，含取消后的交涉，见 ADR-0011 决策 D1）。

### ChatMessageVO（消息）

| 字段 | 类型 | 说明 |
|---|---|---|
| `messageId` | Long | 消息 ID（会话内游标锚点，单调递增） |
| `orderId` | Long | 订单 ID |
| `senderId` | Long | 发送人用户 ID |
| `senderRole` | String | 发送人角色快照：`FAMILY` / `COMPANION` |
| `msgType` | String | `TEXT` / `IMAGE` / `VIDEO` |
| `msgTypeLabel` | String | 中文：文字 / 图片 / 视频 |
| `content` | String | TEXT 正文（≤ 1000 字）；媒体消息可为空（说明文字） |
| `fileId` | String | 媒体消息必填，关联 `sys_file.file_id` |
| `fileUrl` | String | 媒体 URL（`/uploads/...`，静态直链） |
| `fileSize` | Long | 媒体字节数 |
| `createTime` | String | 发送时间 `yyyy-MM-dd HH:mm:ss` |

### ChatConversationVO（会话列表项）

| 字段 | 类型 | 说明 |
|---|---|---|
| `orderId` | Long | 订单 ID（进入聊天室的路径参数） |
| `orderNo` | String | 订单号 |
| `orderStatusLabel` | String | 订单状态中文名 |
| `peerUserId` | Long | 对方用户 ID |
| `peerName` | String | 对方姓名（**脱敏**，`MaskUtil.name()`） |
| `peerRole` | String | `FAMILY` / `COMPANION` |
| `hospital` | String | 就诊医院 |
| `visitTime` | String | 就诊时间 |
| `lastMessage` | Object | `{ msgType, msgTypeLabel, preview(≤30字), createTime }`，无消息时 `null` |
| `unreadCount` | Integer | 我的未读数（对方发来且未读的条数） |

> ⚠️ 会话列表与消息流**一律不返回手机号**。管理端查看器同样脱敏。
> （订单详情「全名豁免」不适用于聊天——聊天无「该看的人看不到该看的信息」的既定决策，默认脱敏。）

### 消息类型

| 传值 | 中文 | 附件要求 |
|---|---|---|
| `TEXT` | 文字 | `content` 必填 ≤ 1000 字 |
| `IMAGE` | 图片 | `fileId` 必填，附件 ext ∈ `jpg / png / webp` |
| `VIDEO` | 视频 | `fileId` 必填，附件 ext = `mp4` |

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/api/chat/{orderId}/messages` | FAMILY / COMPANION（本单双方） | 发送消息 |
| 2 | GET | `/api/chat/{orderId}/messages` | 双方 + ELDER（只读）+ ADMIN | 游标倒序拉历史 |
| 3 | GET | `/api/chat/conversations` | FAMILY / COMPANION | 我的会话列表 |
| 4 | POST | `/api/chat/{orderId}/read` | FAMILY / COMPANION | 已读上报（幂等 upsert） |
| 5 | GET | `/api/chat/unread-total` | FAMILY / COMPANION | 未读总数（角标兜底轮询） |
| 6 | GET | `/api/admin/chat/orders` | ADMIN | 有聊天记录的订单检索（查看器左栏） |
| 7 | WS | `/ws/chat?token=&orderId=` | 双方 + ELDER + ADMIN | 实时接收（**复用 M5 握手鉴权**） |

> ELDER 调任何写接口（1 / 4）被 `ElderReadOnlyInterceptor` 直接 403；ADMIN 调写接口服务层回 `7008`。

---

## 1. 发送消息

`POST /api/chat/{orderId}/messages`　权限：FAMILY / COMPANION（**须为本单双方本人**）

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `msgType` | String | 是 | `TEXT` / `IMAGE` / `VIDEO` |
| `content` | String | TEXT 必填 | ≤ 1000 字；媒体消息可传说明文字（≤ 1000 字） |
| `fileId` | String | 媒体必填 | 先调 `POST /api/file/upload`（`bizType=CHAT`）拿到 |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "messageId": 90001,
    "orderId": 1001,
    "senderId": 2002,
    "senderRole": "FAMILY",
    "msgType": "TEXT",
    "msgTypeLabel": "文字",
    "content": "今天辛苦了，老人中午吃饭了吗？",
    "createTime": "2026-10-16 12:30:05"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| `3001` | 订单不存在 |
| `3004` | 不是本单相关方 |
| `7003` | 会话未建立（订单尚未接单，`companion_id` 为空） |
| `7004` | 文字为空 / 超 1000 个 Unicode 字符 / 命中合规词表（同 3007 口径） |
| `7005` | 媒体消息缺少 `fileId` |
| `7006` | 附件不存在、`biz_type` 不是 `CHAT`、或不属于当前发送人 |
| `7007` | 附件类型与消息类型不符（图片仅 jpg/png/webp，视频仅 mp4） |
| `7008` | 无发言权限（ELDER / ADMIN 等会话双方以外角色；ELDER 通常已被拦截器 403） |

### 实现要点

- 校验链（事务内，`ChatServiceImpl#send`）：`requireInvolved` → `requireChatWriter`（FAMILY/COMPANION 且是本单绑定人，否则 7008）→ 可聊校验（7003）→ 内容 / 附件校验 → 落库 → **事务提交后推送**（WS 双推 + SSE 对方角标；推送失败只记日志，不影响已提交响应）。
- 媒体消息的 `file_id` 必须满足 `sys_file.biz_type = 'CHAT' && uploader_id = 当前用户`，防止引用他人附件；`file_url` / `file_size` 落库为快照，展示不二次查附件表。
- 发送链路做敏感词校验（复用订单服务小结同源词表），聊天不是绕过 3007 合规红线的旁路。
- 服务端日志**先脱敏再拼接**：`log.info` 只打 `orderId / messageId / msgType / 字节数`，不打正文与姓名。

---

## 2. 拉取历史消息（游标倒序）

`GET /api/chat/{orderId}/messages?cursor=&size=20`　权限：双方 + ELDER（自己订单）+ ADMIN

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `cursor` | Long | 否 | 上一次返回的 `nextCursor`；**不传或传 0 表示从最新开始** |
| `size` | Integer | 否 | 每页条数，默认 20，上限 100 |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "records": [
      { "messageId": 90001, "senderId": 2002, "senderRole": "FAMILY", "msgType": "TEXT",
        "msgTypeLabel": "文字", "content": "今天辛苦了", "createTime": "2026-10-16 12:30:05" }
    ],
    "nextCursor": 90001,
    "hasMore": true,
    "peerUserId": 3003,
    "peerName": "李*明",
    "peerRole": "COMPANION",
    "myRole": "FAMILY"
  }
}
```

> `records` 按 `messageId` **降序**（最新的在前），前端自行反转为正序展示。
> `nextCursor` = 本页最旧一条的 id；`hasMore=false` 表示已到最早一条。

### 错误场景

| code | 场景 |
|---|---|
| `3001` | 订单不存在 |
| `3004` | 不是本单相关方（ELDER 按 `family_elder_relation` 绑定档案放行，与订单详情同口径） |

### 实现要点

- SQL：`WHERE order_id=? AND id < #{cursor} ORDER BY id DESC LIMIT #{size}`（cursor=0 时去掉 id 条件），走 `idx_order_id_id`，纯索引序扫描。
- 只读接口，**不改变**已读游标；进入聊天室后由前端显式调接口 4 上报。

---

## 3. 我的会话列表

`GET /api/chat/conversations?page=&size=`　权限：FAMILY / COMPANION

### 请求参数

`page` / `size`（通用分页，默认按最后一条消息 id 倒序；无消息的会话按订单 id 倒序垫底）。

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
        "orderId": 1001,
        "orderNo": "NL20261015000001",
        "orderStatusLabel": "服务中",
        "peerUserId": 3003,
        "peerName": "李*明",
        "peerRole": "COMPANION",
        "hospital": "海南省人民医院",
        "visitTime": "2026-10-16 09:30:00",
        "lastMessage": { "msgType": "TEXT", "msgTypeLabel": "文字", "preview": "今天辛苦了，老人中午…", "createTime": "2026-10-16 12:30:05" },
        "unreadCount": 2
      }
    ]
  }
}
```

### 实现要点

- 数据源 = 我作为 `family_id` 或 `companion_id` 的**已接单**订单，左联每单 `MAX(messageId)` 与未读计数（`chat_read_cursor` 游标差集）。
- 附件类最后消息的预览固定文案：图片 →「[图片]」，视频 →「[视频]」。
- 媒体消息带 `fileId` 的，`preview` 不含用户输入说明文字（防长文本撑爆列表行）。

---

## 4. 已读上报

`POST /api/chat/{orderId}/read`　权限：FAMILY / COMPANION（本单双方）

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `lastMessageId` | Long | 是 | 当前已读到的最大消息 id（前端进入聊天室 / 收到推送时上报） |

### 响应

```json
{ "code": 200, "message": "操作成功", "data": { "unreadCount": 0 } }
```

### 错误场景

| code | 场景 |
|---|---|
| `3001` / `3004` | 订单不存在 / 非相关方 |
| `7003` | 会话未建立 |
| `7008` | 无权限（ELDER / ADMIN；ELDER 通常已被拦截器 403） |

### 实现要点

- `INSERT ... ON DUPLICATE KEY UPDATE`（`uk_order_user`），天然幂等；游标只前进不后退（新值小于旧值时忽略）。
- 上报 `lastMessageId` 大于会话最大 id 时按最大 id 收敛（防脏数据撑大游标）。

---

## 5. 未读总数

`GET /api/chat/unread-total`　权限：FAMILY / COMPANION

### 响应

```json
{ "code": 200, "message": "操作成功", "data": { "total": 5 } }
```

### 实现要点

- 跨所有我会话的未读求和，供「消息」页入口卡与角标兜底轮询（与站内信 60s 轮询同节奏）。

---

## 6. 管理端：聊天订单检索

`GET /api/admin/chat/orders?keyword=&page=&size=`　权限：ADMIN

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `keyword` | String | 否 | 模糊匹配订单号 / 医院 |
| `page` / `size` | Integer | 否 | 通用分页，按最后消息时间倒序 |

### 响应

`records` 项：`{ orderId, orderNo, orderStatusLabel, familyName(脱敏), companionName(脱敏), hospital, messageCount, lastMessageTime }`。

### 实现要点

- 只读查询，不落 `admin_oper_log`（写操作才落，M9 口径）。
- 查看器打开某单后的历史拉取与 WS 订阅**复用接口 2 / 7**（`requireInvolved` 对 admin 直通）；管理端**不发消息**（写一律 7008）。
- 入口：投诉管理（W-06）行操作「查看沟通记录」、订单纠纷仲裁（W-03）、订单管理（W-05）。

---

## 7. 实时接收（WebSocket）

`WS /ws/chat?token=<accessToken>&orderId=<orderId>`　权限：本单相关方（双方 / ELDER / ADMIN）

> **与 `/ws/progress`（[04-companion-execution.md §6](./04-companion-execution.md)）同一套握手鉴权**
> （`JwtHandshakeInterceptor`：token 验签 → access → 黑名单 → 密码版本 → `requireInvolved(orderId)`）。
> 非相关方握手直接关闭；`/ws/**` 已在 SecurityConfig 白名单，浏览器无需额外鉴权头。

### 连接约定

- 客户端每 **30s** 发裸文本 `ping`，服务端回 `PONG`；其余上行业务消息一律忽略（聊天写入不存在第二条通道，见 ADR-0011 D2）。
- 断线由前端 `realtime.js` 指数退避自动重连（封顶 30s，5 次放弃后提示「实时连接已断开，消息仍会保存」）；首次连接及每次重连成功后从 `cursor=0` 的最新页向前连续翻页，直到覆盖本地已知的最新消息或历史耗尽；按 `messageId` 去重，补齐超过一页的断线缺口。

### 下行事件（JSON：`{ type, orderId, data: { ..., pushTime } }`）

| type | data | 说明 |
|---|---|---|
| `CONNECTED` | `{ orderId, userId, role, pushTime }` | 握手成功首帧 |
| `PONG` | `{ pushTime }` | 心跳应答 |
| `CHAT_MESSAGE` | 完整 ChatMessageVO 字段 + `pushTime` | 新消息（双方都收；**发送方自己的连接不推**，发送方以 REST 200 响应上屏） |

### 实现要点

- `ChatMessageHub` 按 `orderId` 分组维护会话（仿 `OrderProgressHub`），死连接在推送异常时摘除。
- 发送方跳过逻辑按 WebSocket 会话归属判断；同用户多端登录时，SSE 角标只推给会话对方，不替代同用户其他端的 WS 收帧，发送方其他端可刷新记录兜底。

---

## 8. 角标提醒（SSE，复用站内信通道）

`GET /sse/message` 新增事件 **`CHAT_MESSAGE`**（`MessageSseHub.EVENT_CHAT_MESSAGE`）：

```json
{
  "orderId": 1001,
  "msgType": "TEXT",
  "preview": "今天辛苦了，老人中午吃饭了吗？",
  "pushTime": "2026-10-16 12:30:06"
}
```

- 语义与站内信 `NEW_MESSAGE` 同构：**单向「服务器→用户」提醒**，不投递聊天载荷本体（载荷走 WS / REST），不破坏 M5/M8 两通道分工（ADR-0011 D2）。
- 前端收到后：请求 `GET /api/chat/unread-total` 刷新权威未读总数，入口卡红点亮起；推送失败服务端只记日志。

---

## 四、验收标准（M13）

- [ ] 家属与接单陪诊员互发文字，双方聊天室 ≤ 3 秒内看到对方消息（WS 实时）
- [ ] `PENDING` 订单发消息返回 `7003`；接单后可发；`CANCELLED` 后仍可互发（交涉场景）
- [ ] ELDER 账号 POST 发消息 / 已读上报被 `ElderReadOnlyInterceptor` 拦截返回 403，GET 历史成功且页面呈只读态
- [ ] ADMIN 调发送 / 已读接口返回 `7008`；GET 历史与管理端检索成功（仲裁只读不变量）
- [ ] 非本单相关方（另一家属）访问历史 → `3004`；WS 握手被拒
- [ ] 文字为空 / 超 1000 字 / 命中合规词表 → `7004`；聊天室顶部展示免责声明
- [ ] 图片（jpg/png/webp）与 mp4 视频经 `bizType=CHAT` 上传后可发送、对端可预览 / 播放
- [ ] 引用他人 `fileId`（非本人上传或非 CHAT 类型）→ `7006`；`IMAGE` 消息配 mp4 附件 → `7007`
- [ ] 12MB 文件传非 CHAT bizType → `400 PARAM_ERROR`（10MB 防线未破，且不占 7xxx 段位）；超 50MB 传 CHAT → `7009`；50MB 内 mp4 传 CHAT 成功
- [ ] 已读上报幂等：重复上报不报错，未读数不重复扣减
- [ ] 游标分页：`cursor=0` 取最新；翻到最早一条时 `hasMore=false`；触顶加载不重复不丢条
- [ ] 断线重连后增量补洞：断线窗口内对方发的消息在重连后可见
- [ ] 会话列表不出现手机号；`peerName` 脱敏；前端 vitest 与 `e2e/specs/14-chat.spec.js` 全绿

### 4.1 聊天室 UI 层说明（v1.1.0 追加，接口契约不变）

订单信息面板与消息流是**兄弟节点**，面板任何一块请求失败都不影响聊天收发。面板数据全部来自既有接口，**不新增任何后端接口**：

| 面板区块 | 数据来源 | 权限 |
|---|---|---|
| 就诊概览（医院 / 科室 / 就诊时间 / 老人） | `GET /api/order/{id}` | 本单相关方（`requireInvolved`） |
| 服务进度（当前 / 下一节点、百分比、最后更新） | `GET /api/execution/{orderId}/progress` | 同上 |
| 最近打卡（时间 / 地点 / 异常标记） | `GET /api/execution/{orderId}/checkins` | 同上 |
| 陪诊员资料（评分 / 接单数 / 资质 / 服务区域 / 时段 / 状态） | `GET /api/user/companion/{companionId}` | 已登录；对**未过审**陪诊员抛 `3005`，前端按「暂未开放资料」降级 |

**姓名口径**：聊天头部与气泡头像统一取 `GET /api/order/{id}` 的详情口径全名，不再使用本文档 §1 的 `peerName`（脱敏值）。理由见 AGENTS.md §0.1 的既定豁免——该豁免本就在 `OrderVO#ofDetail` 上，前端只是又调用了一个已授权接口，未新增脱敏放宽。**陪诊员端例外**：订单详情不返回家属姓名，此处回落到 `peerName`（脱敏）。

**实时性**：聊天室在 `/ws/chat` 之外另开一条 `/ws/progress` 订阅打卡进度（两条连接各自独立重连，不共享实例），并保留 60 秒轮询覆盖 WS 断线窗口。

**刻意不展示**：陪诊员头像 / 电话 / 个人简介（后端一律不返回）、老人病史与行动能力（`ElderServiceImpl` 对 COMPANION 显式 `2006`，展示会导致两种角色渲染出字段残缺的不同版本）、用药任务（语义属老人健康档案而非陪诊进度）。

---

## 五、变更记录

| 版本 | 日期 | 变更内容 | 变更人 |
|---|---|---|---|
| v1.1.0 | 2026-10-18 | 聊天室 UI 重构：三栏布局 + 订单信息面板（复用 `GET /api/order/{id}`、`/api/execution/{orderId}/progress`、`/checkins`、`/api/user/companion/{id}`）、第二条 `/ws/progress` 订阅、图片/视频拆为两个独立入口。**接口契约零变更** | — |
| v1.0.0 | 2026-10-16 | M13 初版：按订单聊天（文字/图片/视频）、ELDER 只读、ADMIN 仲裁只读、WS `/ws/chat`、SSE `CHAT_MESSAGE` 角标、上传 `CHAT` 类型 50MB 分级限额（ADR-0011） | — |
