# 04 陪诊执行与打卡

> 覆盖模块：**M5 陪诊执行、打卡与实时进度**
> 负责人：A（前端打卡页与时间线）+ B（后端接口与推送）
> 归属迭代：迭代三 · W8–W9（W12 竞讲交付 v2.0）
> 前置依赖：M4 陪诊订单　全局约定见 [README.md](./README.md)

> **定位用途（一期）**：只用于「是否到达陪诊地点」的距离校验，不渲染地图。
>
> **二期变更（2026-11-27，ADR-0022）—— 地图可视化放开。**
> 家属端（M-16 订单详情）与陪诊员端（M-11 打卡页）渲染百度地图，**仅直线距离，不做路线规划与导航**；
> 老人端与管理端**不渲染**。为此新增 §7（位置上报）/ §8（最新位置快照）/ §9（位置共享开关）三个接口，
> 并给 §6 的 WS 通道加 `LOCATION` / `LOCATION_PAUSED` 两个事件类型。
> 红线表述的修订见 `docs/adr/0022-map-live-tracking.md`。

---

## 一、数据模型

### CheckinVO（打卡记录）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 打卡记录 ID |
| `orderId` | Long | 订单 ID |
| `node` | String | 节点枚举名，见下表 |
| `nodeLabel` | String | 中文节点名 |
| `longitude` | String | 经度（字符串避免精度丢失，如 `"110.331200"`） |
| `latitude` | String | 纬度 |
| `address` | String | 打卡时的反向地理描述（**一期为可选，可由前端传入或留空**） |
| `distance` | Integer | 与订单地址的直线距离（米），用于判断有效性 |
| `isAbnormal` | Boolean | 是否异常打卡（超出阈值） |
| `photos` | Array | 现场照片 URL 列表 |
| `operatorId` | Long | 打卡人 ID |
| `operatorName` | String | 打卡人姓名（脱敏） |
| `checkinTime` | String | 打卡时间 |

### 打卡节点枚举

| 传值 | 中文 | 排序 | 说明 |
|---|---|---|---|
| `DEPART` | 出发 | 1 | 陪诊员从出发点前往医院 |
| `ARRIVE` | 到院 | 2 | 到达医院 |
| `IN_CONSULT` | 就诊中 | 3 | 陪同就诊 |
| `TAKE_MEDICINE` | 取药 | 4 | 取药 / 缴费 |
| `LEAVE` | 离院 | 5 | 离开医院 |
| `FINISH` | 完成 | 6 | 送达并交接完成 |

**顺序约束**：可跳过（如不需要取药），**不可回退**（不能先打「取药」再打「到院」）。

### TrackPointVO（轨迹点）

| 字段 | 类型 | 说明 |
|---|---|---|
| `longitude` | String | 经度 |
| `latitude` | String | 纬度 |
| `node` | String | 关联的打卡节点 |
| `recordTime` | String | 记录时间 |

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/api/execution/{orderId}/checkin` | COMPANION | 打卡 |
| 2 | GET | `/api/execution/{orderId}/checkins` | 相关方 | 打卡记录列表 |
| 3 | GET | `/api/execution/{orderId}/track` | 相关方 | 轨迹点列表 |
| 4 | GET | `/api/execution/{orderId}/progress` | 相关方 | 进度快照 |
| 5 | POST | `/api/execution/{orderId}/photo` | COMPANION | 上传现场照片 |
| 6 | WS | `/ws/progress?token=&orderId=` | 相关方 | 实时进度推送 |

---

## 1. 打卡

`POST /api/execution/{orderId}/checkin`　权限：**COMPANION**（须为本单陪诊员）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `node` | String | ✓ | 节点枚举名 | 打卡节点 |
| `longitude` | String | ✓ | 精度保留 6 位 | 当前经度 |
| `latitude` | String | ✓ | 精度保留 6 位 | 当前纬度 |
| `address` | String | 否 | ≤ 200 字符 | 当前位置描述 |
| `photos` | Array | 否 | ≤ 6 张 | 照片 URL 列表 |
| `remark` | String | 否 | ≤ 200 字符 | 备注 |

```json
{
  "node": "ARRIVE",
  "longitude": "110.331200",
  "latitude": "20.031500",
  "address": "海南省人民医院 门诊大楼",
  "photos": ["/uploads/202609/abc123.jpg"],
  "remark": "已到达医院，正在取号"
}
```

### 响应

```json
{
  "code": 200,
  "message": "打卡成功",
  "data": {
    "checkinId": 8001,
    "node": "ARRIVE",
    "nodeLabel": "到院",
    "distance": 128,
    "isAbnormal": false,
    "checkinTime": "2026-09-20 09:10:00"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 4003 | 您不是该订单的陪诊员 |
| 3002 | 订单状态不允许打卡（须为 `ACCEPTED` 或 `IN_SERVICE`） |
| 4001 | 未到达陪诊地点，打卡无效（距离超过阈值） |
| 4002 | 该节点已打卡，请勿重复提交 |
| 400 | 节点顺序回退（如先 `TAKE_MEDICINE` 再 `ARRIVE`） |

### 实现要点

1. **距离校验**：计算打卡坐标与订单地址坐标的直线距离（Haversine 公式），
   超过 `nianglin.order.checkin-max-distance-meters`（默认 2000 米）判为无效（`4001`）。
2. **去重**：同一订单 + 同一节点唯一索引，重复提交返回 `4002`，不产生重复记录。
3. **落库双写**：同时写入 `order_checkin` 与 `companion_track`，且**同一事务**。
4. **节点顺序**：取该订单已打卡的最大排序值，新节点排序值必须大于它，否则返回 `400`。
5. **推送**：打卡成功后通过 WebSocket 向该订单的家属与陪诊员推送事件（见接口 6）。
6. 打卡 `FINISH` 时建议自动把订单推进到 `COMPLETED`（或提示陪诊员去调用完成接口）。

---

## 2. 打卡记录列表

`GET /api/execution/{orderId}/checkins`　权限：订单相关方（家属 / 陪诊员 / 就诊老人 / 管理员）

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "id": 8001,
      "node": "DEPART",
      "nodeLabel": "出发",
      "address": "海口市美兰区某小区",
      "distance": 3200,
      "isAbnormal": false,
      "photos": [],
      "operatorName": "李*",
      "checkinTime": "2026-09-20 08:20:00"
    },
    {
      "id": 8002,
      "node": "ARRIVE",
      "nodeLabel": "到院",
      "address": "海南省人民医院 门诊大楼",
      "distance": 128,
      "isAbnormal": false,
      "photos": ["/uploads/202609/abc123.jpg"],
      "operatorName": "李*",
      "checkinTime": "2026-09-20 09:10:00"
    }
  ]
}
```

### 实现要点

- 按 `checkin_time` **升序**返回（与前端时间线渲染顺序一致）。
- 列表按节点排序展示；若发现顺序错乱，说明写入有问题，需要修正。

---

## 3. 轨迹点列表

`GET /api/execution/{orderId}/track`　权限：订单相关方

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    { "longitude": "110.311200", "latitude": "20.021500", "node": "DEPART", "recordTime": "2026-09-20 08:20:00" },
    { "longitude": "110.331200", "latitude": "20.031500", "node": "ARRIVE", "recordTime": "2026-09-20 09:10:00" }
  ]
}
```

> 本接口数据用于：
> ① 生成「陪诊路径文字摘要」；② 异常打卡申诉取证；③ 家属端 / 陪诊员端地图的轨迹折线（ADR-0022）；
> ④ 答辩时展示轨迹原始数据。
>
> ⚠️ §7 位置上报会对轨迹做**抽稀**（位移 ≥ 50 米或间隔 ≥ 120 秒才落库），
> 因此**打卡点（`node` 非空）永不抽稀**，本接口返回的点既包含 6 个打卡锚点，也包含抽稀后的位置点。

---

## 4. 进度快照

`GET /api/execution/{orderId}/progress`　权限：订单相关方

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "orderId": 1001,
    "orderStatus": "IN_SERVICE",
    "orderStatusLabel": "服务中",
    "currentNode": "IN_CONSULT",
    "currentNodeLabel": "就诊中",
    "nextNode": "TAKE_MEDICINE",
    "nextNodeLabel": "取药",
    "progressPercent": 50,
    "finishedNodes": ["DEPART", "ARRIVE", "IN_CONSULT"],
    "lastUpdateTime": "2026-09-20 09:45:00"
  }
}
```

### 用途

家属端进入页面时先拉一次快照，之后靠 WebSocket 增量更新，避免页面空白。

---

## 5. 上传现场照片 / 取药凭证

`POST /api/execution/{orderId}/photo`　权限：COMPANION（须为本单陪诊员）

### 请求

`multipart/form-data`，字段名 `file`。

### 响应

```json
{
  "code": 200,
  "message": "上传成功",
  "data": { "fileId": "f_8001", "url": "/uploads/202609/def456.jpg", "size": 204800 }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 400 | 文件超过 10 MB |
| 400 | 文件类型不允许（需为 jpg / png / webp / pdf） |
| 4003 | 您不是该订单的陪诊员 |

### 实现要点

- 必须读取文件头（magic bytes）校验真实类型，**不能信任前端传来的 `Content-Type`**。
- 文件名用 UUID 重命名，防止路径穿越与覆盖。

---

## 6. 实时进度推送（WebSocket）

`WS /ws/progress?token=<accessToken>&orderId=<orderId>`　权限：订单相关方

### 连接约定

| 项目 | 约定 |
|---|---|
| 协议 | WebSocket（反向代理层需透传 `Upgrade` / `Connection` 头） |
| 鉴权 | query 参数 `token`（连接时校验 JWT 与订单归属，失败直接关闭连接） |
| 心跳 | 客户端每 30 秒发一次 `ping`，服务端回 `pong`；超时 60 秒断开 |
| 重连 | 前端指数退避重连（1s / 2s / 4s / 8s，上限 30s） |

### 推送消息格式

```json
{
  "type": "ORDER_PROGRESS",
  "orderId": 1001,
  "data": {
    "node": "ARRIVE",
    "nodeLabel": "到院",
    "orderStatus": "IN_SERVICE",
    "orderStatusLabel": "服务中",
    "message": "陪诊员已到达医院",
    "operatorName": "李*",
    "pushTime": "2026-09-20 09:10:05"
  }
}
```

### 消息类型

| `type` | 触发时机 |
|---|---|
| `ORDER_PROGRESS` | 打卡成功、订单状态变更 |
| `ORDER_ACCEPTED` | 陪诊员接单 |
| `MEDICATION_REMIND` | 漏服提醒（M6，走同一通道可复用） |
| `LOCATION` | 陪诊员上报位置（ADR-0022，**只发 FAMILY / COMPANION**） |
| `LOCATION_PAUSED` | 陪诊员暂停位置共享（ADR-0022，**只发 FAMILY / COMPANION**） |
| `PONG` | 心跳响应 |

#### 按角色过滤的推送（ADR-0022）

`ORDER_PROGRESS` / `ORDER_ACCEPTED` 继续广播给该订单的全部相关方（家属 / 陪诊员 / 老人 / 管理员）。
但**位置类事件**只推 `FAMILY` 与 `COMPANION` —— 位置是一个真实工作者的行踪信息，
管理员与老人看到「打卡到哪了」就够了，不需要看到「人现在在哪」。

```json
{
  "type": "LOCATION",
  "orderId": 1001,
  "data": {
    "longitude": "110.331200",
    "latitude": "20.031500",
    "distanceMeters": 1200,
    "sharingEnabled": true,
    "recordTime": "2026-09-20 09:10:00",
    "staleSeconds": 5,
    "pushTime": "2026-09-20 09:10:01"
  }
}
```

```json
{
  "type": "LOCATION_PAUSED",
  "orderId": 1001,
  "data": { "sharingEnabled": false, "pushTime": "2026-09-20 09:20:00" }
}
```

> **坐标一律 WGS84**（浏览器 `navigator.geolocation` 原生值），与 `companion_track` / `order_checkin` 同口径。
> 百度地图用 BD09，**转换在前端**（`utils/coordConvert.js`），后端不做任何坐标转换。

### 实现要点

- 服务端按 `orderId` 维护会话分组（`Map<Long, Set<Session>>`），推送时只发给该订单的订阅者。
- **断线重连不丢事件**：服务端为每个订单缓存最近 N 条事件（或前端重连后先调 `/progress` 与 `/checkins` 补齐）。
- 前端用 `ElNotification` 弹提示。
- 连接前必须校验：当前用户是该订单的家属 / 陪诊员 / 就诊人，否则拒绝（防越权监听）。

### 备选方案（SSE）

若 WebSocket 时间紧张，可用 SSE 替代：
`GET /api/execution/{orderId}/progress/stream`（`text/event-stream`），
单向推送足够满足「家属看进度」的需求。**二选一即可，不要都做。**

---

## 7. 上报当前位置（ADR-0022）

`POST /api/execution/{orderId}/location`　权限：**COMPANION**（须为本单陪诊员）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `longitude` | String | ✓ | 精度 6 位，`-180 ~ 180` | 当前经度（**WGS84**） |
| `latitude` | String | ✓ | 精度 6 位，`-90 ~ 90` | 当前纬度（**WGS84**） |
| `accuracy` | Double | 否 | ≥ 0 | 定位精度（米），写入 `companion_track.accuracy` |
| `speed` | Double | 否 | ≥ 0 | 速度（米/秒），写入 `companion_track.speed` |

```json
{
  "longitude": "110.331200",
  "latitude": "20.031500",
  "accuracy": 18.5,
  "speed": 1.2
}
```

### 响应

```json
{
  "code": 200,
  "message": "位置已更新",
  "data": {
    "orderId": 1001,
    "longitude": "110.331200",
    "latitude": "20.031500",
    "accuracy": 18.5,
    "speed": 1.2,
    "recordTime": "2026-09-20 09:10:00",
    "distanceMeters": 1200,
    "orderLongitude": "110.311200",
    "orderLatitude": "20.021500",
    "sharingEnabled": true,
    "staleSeconds": 0
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3002 | 订单状态不允许上报（须为 `ACCEPTED` 或 `IN_SERVICE`） |
| 400 | 坐标非法（超出取值范围） |
| 4003 | 您不是该订单的陪诊员 |
| 4004 | 位置上报过于频繁（同一订单小于 `min-report-interval-seconds`，默认 10 秒） |
| 4005 | 位置共享已关闭（陪诊员已暂停，见 §9） |

### 实现要点

1. **限流**：Redis `track:rate:{orderId}`，`SET NX EX min-report-interval-seconds`（默认 10 秒）。
   已存在即 `4004`。**不静默忽略** —— 静默会让「为何位置不动」变成无法排查的问题。
2. **暂停判定**：Redis `track:share:{orderId}` 为 `off` 时 `4005`。
3. **最新点**：每次上报**都刷** Redis `track:latest:{orderId}`（TTL `latest-ttl-seconds`，默认 300 秒），
   **不受**下一步的抽稀影响 —— 家属看到的永远是最后一次上报的位置。
4. **落库抽稀**：与上一条 `companion_track` 比较，
   位移 ≥ `min-move-meters`（默认 50 米）**或** 距上一条 ≥ `min-record-interval-seconds`（默认 120 秒）
   才 `INSERT`；否则只留 Redis。单均 3 小时从 ~360 条降到 ~50 条。
5. **打卡点不抽稀**：`node` 非空的轨迹点由 §1 打卡写入，永远落库。
6. **推送**：成功后向该订单推 `LOCATION` 事件（**只发 FAMILY / COMPANION**，见 §6）。
7. **距离**：`distanceMeters` 由 `GeoUtil.distanceMeters` 计算，与 §1 打卡的 `4001` 判定、
   匹配引擎的 `DistanceMatchRule` **同一个函数**；订单无坐标时为 `null`（不是 0）。

---

## 8. 最新位置快照（ADR-0022）

`GET /api/execution/{orderId}/location`　权限：**仅本单 FAMILY 与 COMPANION**

进页面时先拉一次铺底，之后靠 §6 的 `LOCATION` 事件增量更新 —— 与 `/progress` 的
「先快照、后推送」约定完全一致。

### 响应

结构同 §7 的 `data`，额外多一项：

| 字段 | 类型 | 说明 |
|---|---|---|
| `recordTime` | String | 最后一次上报时间；**从未上报时为 `null`** |
| `staleSeconds` | Integer | 距 `recordTime` 的秒数；由服务端盖章，不信客户端时钟 |

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3004 | 无权查看该订单位置（**ELDER / ADMIN 也走这个码**） |

### 可见性（与 §6 推送口径一致）

| 角色 | `GET /location` | `LOCATION` 推送 |
|---|---|---|
| FAMILY（本单下单人） | ✓ | ✓ |
| COMPANION（本单接单人） | ✓ | ✓ |
| ELDER（就诊老人） | `3004` | ✗ |
| ADMIN | `3004` | ✗ |

> 原则：**能查到什么，就能推送什么；能推送什么，才能查到什么。**
> 管理员需要纠纷取证时用 §3 `/track`（`requireInvolved` 已包含 admin），不需要实时地图。

### 从未上报过的订单

`recordTime` / `longitude` / `latitude` 均为 `null`，HTTP 仍返回 `200`。
前端据此渲染「陪诊员尚未开始共享位置」，**不要**当成错误处理。

---

## 9. 位置共享开关（ADR-0022）

`PUT /api/execution/{orderId}/location-sharing`　权限：**COMPANION**（须为本单陪诊员）

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `enabled` | Boolean | ✓ | `true` 恢复共享，`false` 暂停共享 |

```json
{ "enabled": false }
```

### 响应

```json
{
  "code": 200,
  "message": "已暂停位置共享",
  "data": { "orderId": 1001, "sharingEnabled": false }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3002 | 订单状态不允许（须为 `ACCEPTED` 或 `IN_SERVICE`） |
| 4003 | 您不是该订单的陪诊员 |

### 实现要点

1. 状态落 Redis `track:share:{orderId}` = `on` / `off`，**不设 TTL** ——
   共享状态应与订单同寿，而不是 5 分钟后「自己又打开了」。
2. 关闭时推 `LOCATION_PAUSED`（只发 FAMILY / COMPANION），恢复时若 Redis 有最新点则补推一条 `LOCATION`。
3. **为什么不静默停发**：冻住的红点会被家属读成「出事了」。暂停必须是**可解释的空白**。
4. 暂停**不影响打卡**：§1 的打卡写入不受 `track:share` 影响，
   位置共享是「是否给家属看实时位置」，不是「是否记录履约证据」。

---

## 三、验收标准（M5）

- [ ] 打卡成功后 `order_checkin` 与 `companion_track` 同时新增记录，且 `orderId` 一致
- [ ] 家属端在**不刷新页面**的前提下，3 秒内收到陪诊进度推送
- [ ] 关闭网络 30 秒再恢复，WebSocket 自动重连，重连后**不丢事件、不重复渲染**
- [ ] 打卡坐标超出阈值 → 返回 `4001`，记录不写入或标记 `isAbnormal = true`
- [ ] 同一陪诊员对同一订单重复提交同一节点 → 返回 `4002`，数据库无重复行
- [ ] 节点顺序回退（先取药再到院）→ 返回 `400`
- [ ] 打卡记录列表顺序与 `checkin_time` 升序完全一致
- [ ] 非本单陪诊员调用打卡接口 → `4003`
- [ ] 非订单相关方连接 WebSocket → 连接被拒绝

### ADR-0022（地图可视化）附加验收

- [ ] 家属端（`ACCEPTED` / `IN_SERVICE`）订单详情出现「实时位置」卡；`PENDING` 不渲染
- [ ] 无 AK / AK 无效 / 加载超时 8 秒 → 渲染文字降级卡片，**不白屏、不弹错、不出现空白框**
- [ ] 降级卡片文案含「直线约」与「前更新」，**不出现**「预计到达 / 导航 / 路线」
- [ ] 陪诊员上报位置后，家属端**不刷新页面**收到 `LOCATION` 并更新距离
- [ ] 同一订单 10 秒内二次上报 → `4004`；暂停共享后上报 → `4005`
- [ ] 位移 < 50 米且间隔 < 120 秒的上报**不落** `companion_track`，但 Redis 最新点仍被刷新
- [ ] ELDER / ADMIN 订阅同一订单 WS **收不到** `LOCATION`；`GET /location` 对他们回 `3004`
- [ ] `staleSeconds > 180` 时 UI 明示「位置已 x 分钟未更新」，**不显示成「在线」**
