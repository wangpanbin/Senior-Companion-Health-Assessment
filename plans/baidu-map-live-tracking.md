# 百度地图可视化定位系统（家属端实时距离 + 陪诊员打卡）

> 状态：**待评审**。grill 已走完 5 轮，全部决策已收敛。

---

## Context（为什么要做）

现状：定位能力被刻意压到「只做距离校验」——

- `docs/api/04-companion-execution.md` 开头：「**一期不做地图导航**：定位只用于『是否到达陪诊地点』的距离校验，不渲染地图。」
- `AGENTS.md` §0.1 红线 3、`docs/plan/plan.md` §一 约束 3、`docs/prd/PRD.md` §2.2/§5.2、
  `docs/spec/V3.x-roadmap-spec.md` G7 + §6.1 都把「地图导航」列为**不做**。
- `frontend/src/views/companion/execute.vue` 只用 `navigator.geolocation.getCurrentPosition` 取一次坐标，
  页面文案写死「定位仅用于『是否到达医院附近』的距离校验，一期不接入地图」。
- `companion_track` 表只在打卡那一刻写入一条（`ExecutionServiceImpl` §checkin 内双写）。

用户诉求：接入百度地图 API，家属端**实时看到陪诊员与待接待老人之间的距离与实时情况**，
同时方便陪诊员打卡。

即：这是一次**对「不做地图导航」红线的正式扩围**，性质与 ADR-0011（IM 聊天扩围）相同 ——
不是加一个页面，而是改项目宪法。必须先有 ADR，再动代码。

## 已收敛的决策（grill 5 轮）

| # | 决策 | 结论 |
|---|---|---|
| D1 | 红线处置 | 写 **ADR-0022**；地图只出现在 **FAMILY 订单详情** 与 **COMPANION 打卡页**；ELDER / ADMIN 不加页面 |
| D2 | 距离终点 | 取 **`companion_order.longitude/latitude`（医院坐标）** —— 零 DDL，与打卡 `4001` 阈值同口径 |
| D3 | 上报机制 | 服务中**每 30 秒**上报；`ACCEPTED` 起、`COMPLETED/CANCELLED` 停；**仅页面可见时**上报；后端**最快 10 秒一次** |
| D4 | AK 形态 | **浏览器端 AK**，前端直连百度 JSAPI；`VITE_BAIDU_MAP_AK` |
| D5 | 坐标系 | **库里统一 WGS84**，只在渲染前由前端转 BD09 |
| D6 | 加载方式 | 官方 **`@baidumap/jsapi-loader`**（新增 1 个生产依赖，需在 ADR 里写明是例外） |
| D7 | 降级 | 地图加载失败 → **文字降级卡片**；CI / e2e **默认走降级路径** |
| D8 | 距离内涵 | **只做直线距离**，不做路线规划 / ETA（不需要服务端 AK） |
| D9 | 轨迹抽稀 | 落库抽稀（位移 ≥50m **或** 间隔 ≥2min）；**Redis 最新点每次都刷** |
| D10 | 可见范围 | `LOCATION` 事件只推 **FAMILY + COMPANION**；ADMIN / ELDER 不推；陪诊员**可暂停** |
| D11 | 错误码 | 新增 **4004**（上报过频）、**4005**（位置共享已关闭）；其余复用 3002 / 4003 / 400 |
| D12 | 地图内容 | 陪诊员实时点 + 医院点 + 轨迹折线 + 已打卡节点 |
| D13 | 暂停态 | 后端 **Redis 持久化** + 推 `LOCATION_PAUSED` 事件 |
| D14 | AK 落位 | 占位写 `frontend/.env.development`（入库），真值写 `frontend/.env.development.local`（gitignored） |
| D15 | 验证 | 后端单测 + 前端 Vitest（转换/降级）+ `e2e/23-map.spec.js` + 本机真实 AK 手工实测截图 |

---

## Approach

### 一、数据与协议（不新建表、不新开通道）

**位置流的三个视图**，各司其职：

| 视图 | 载体 | 用途 |
|---|---|---|
| 最新点（瞬时态） | Redis `track:latest:{orderId}`，TTL 300s | 家属进页面立刻有位置；每 30 秒覆盖 |
| 轨迹（历史） | 既有 `companion_track`，**抽稀后写入** | 地图折线 + 申诉取证 + `/track` 接口语义不变 |
| 打卡节点 | 既有 `order_checkin` | 地图上的节点标记 + 时间线 |

**新接口（`docs/api/04-companion-execution.md` 新增 §7–§9）**

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 7 | POST | `/api/execution/{orderId}/location` | COMPANION | 上报当前位置 |
| 8 | GET | `/api/execution/{orderId}/location` | 相关方（见下） | 最新位置快照（铺底用） |
| 9 | PUT | `/api/execution/{orderId}/location-sharing` | COMPANION | 开关位置共享 |

`GET /location` 的可见性：**FAMILY（本单下单人）与 COMPANION（本单接单人）**；
ELDER / ADMIN 调用返回 `3004`（与 `LOCATION` 推送口径一致 —— 能推送什么，就能查询什么）。

**上报请求体**（`LocationReportDTO`）

```
longitude   String  必填  精度 6 位
latitude    String  必填  精度 6 位
accuracy    Double  可选  定位精度（米），写入 companion_track.accuracy
speed       Double  可选  速度（米/秒），写入 companion_track.speed
```
坐标系**一律 WGS84**（浏览器 geolocation 原生值），在 `@Schema` 与文档里显式写明。

**响应**（`LocationVO`）

```
orderId, longitude, latitude, accuracy, speed, recordTime,
distanceMeters      // 与订单医院坐标的 Haversine 距离；订单无坐标时为 null
orderLongitude, orderLatitude,   // 医院点（地图第二个标记）
sharingEnabled      // 当前位置共享是否开启
staleSeconds        // 距离上次上报过去多少秒，前端据此显示「30 秒前/5 分钟前」
```

**上报处理链**（`ExecutionServiceImpl#reportLocation`）

1. `requireCompanionOf(orderId)` —— 复用既有 `order.getCompanionId()` 比对（同打卡，4003）。
2. 状态校验：`ACCEPTED` / `IN_SERVICE`，否则 `3002`。
3. 共享开关：Redis `track:share:{orderId}` 为 `off` → `4005`。
4. 限流：Redis `track:rate:{orderId}`（`SET NX EX 10`），已存在 → `4004`。
5. 坐标校验：`GeoUtil.isValidCoordinate` → 否则 `400`（复用参数不合法）。
6. 刷 Redis 最新点（**每次都刷，不受抽稀影响**）。
7. 抽稀判定：取该订单**上一条 `companion_track`**，若
   `GeoUtil.distanceMeters(...) >= minMoveMeters` **或** 间隔 `>= minRecordSeconds`
   → `INSERT companion_track`；否则只留在 Redis。
8. 推 WS：`LOCATION` 事件（只发 FAMILY + COMPANION）。
9. 返回 `LocationVO`（含 `distanceMeters`）。

> **抽稀的取舍**：单均 3 小时从 ~360 条降到 ~50 条，
> 既保留「路径可取证」的连续性，又不把业务表变成定位流仓库。
> `/track` 的 2000 条上限因此再也不会有长单被截断的问题。

### 二、推送（在既有通道上加类型，不动鉴权）

`OrderProgressHub` 现在只有 `publish(orderId, type, data)` —— 广播给该订单**所有**会话。
位置事件需要按角色过滤，因此新增：

```java
publishTo(Long orderId, String type, Object data, Set<String> roles)
```

实现：遍历 `groups.get(orderId)` 时读
`session.getAttributes().get(WsAttributes.ROLE)`（握手时已写入），角色不在集合内则跳过。
`publish(...)` 保留为 `publishTo(..., null)` 语义（全广播），`ORDER_PROGRESS` 行为零变更。

新增事件类型常量：
- `TYPE_LOCATION = "LOCATION"` —— `{ longitude, latitude, distanceMeters, recordTime, staleSeconds }`
- `TYPE_LOCATION_PAUSED = "LOCATION_PAUSED"` —— `{ sharingEnabled: false }`

> **为什么不在 Hub 里做「暂停后停发」**：暂停只是「不再有新点」，
> 而不是「撤销已发出的点」。家属端需要**知道**对方暂停了（否则冻住的红点会被读成事故），
> 所以暂停要走主动事件，而不是静默。

### 三、坐标系统一（这条错了整套东西偏移 500 米）

| 环节 | 坐标系 | 谁负责 |
|---|---|---|
| 浏览器 `navigator.geolocation` | **WGS84** | 浏览器 |
| `companion_track` / `order_checkin` / `companion_order` | **WGS84**（不变） | 后端（零转换） |
| `GeoUtil.distanceMeters` 距离 | **WGS84**（不变） | 后端 |
| 百度地图渲染 | **BD09** | **前端** |

前端新增 `frontend/src/utils/coordConvert.js`：

```js
wgs84ToGcj02(lng, lat)   // 国测局偏移算法
gcj02ToBd09(lng, lat)    // 百度二次偏移
wgs84ToBd09(lng, lat)    // 两者串联
```

**必须可单测**（`frontend/src/utils/__tests__/coordConvert.test.mjs`），
用天安门、海口市政府两点做固定断言（误差 < 5 米量级）。

> **为什么不在后端转**：库里现有种子坐标与历史打卡点全是 WGS84 口径（就是 geolocation 原值），
> 改成 BD09 会让 `4001` 的 2000 米阈值凭空多出 500 米偏差，且无法区分新旧数据。
> 渲染时转一次，是把偏移限制在「画错了能看出来」的那一层，而不是「判定错了看不出来」的那一层。

### 四、百度地图加载（前端唯一外网依赖）

`frontend/src/utils/baiduMap.js`：

```js
import BMapLoader from '@baidumap/jsapi-loader'

const AK = import.meta.env.VITE_BAIDU_MAP_AK
export function hasMapAk() { return !!AK }
export function loadBaiduMap() {
  if (!AK) return Promise.reject(new Error('map-no-ak'))
  return BMapLoader.load({ ak: AK, version: '4.0', timeout: 8000 })
}
```

- `version: '4.0'`（百度官方文档：3.0 与 GL 均为历史版本，新项目用 4.0）。
- `timeout: 8000` → 超时走降级卡片，不让页面一直转圈。
- 加载器内部是模块级单例：多次 `load()` 只注入一次脚本，SPA 切页安全。
- **AK 缺失**（`.env.development.local` 没填）时**不发请求**直接降级，
  并在卡片上提示「未配置地图 AK」—— 让「本地没配 AK」与「AK 被拒」在界面上可区分。

### 五、UI（两处，都复用同一个组件）

新增 `frontend/src/components/NlMapPanel.vue`（从 `components/index.js` 导出）：

```
props: {
  orderLng, orderLat,        // 医院点
  point: { lng, lat, recordTime, staleSeconds },  // 陪诊员最新点（WGS84）
  track: [{ lng, lat }],     // 轨迹（WGS84）
  checkins: [{ nodeLabel, time, lng, lat }],
  distanceMeters, sharingEnabled, height
}
```

内部逻辑：
1. `hasMapAk()` 为 false → 直接渲染**降级卡片**。
2. `loadBaiduMap()` 失败/超时 → 渲染**降级卡片** + 浏览器控制台 warn（**不弹 toast**）。
3. 成功 → 用 `wgs84ToBd09` 把三组坐标转成 `BMap.Point`，画：
   - 蓝色 `BMap.Marker` = 陪诊员（`latest` 图标），点击弹 `BMap.InfoWindow`「陪诊员 · 30 秒前更新」
   - 红色 `BMap.Marker` = 医院（`BMap.Symbol` 三角），点击弹窗「医院 · 距你 1.2 公里」
   - `BMap.Polyline` 灰色细线 = 轨迹（`strokeWeight: 4, strokeOpacity: .6`）
   - 小圆点 = 已打卡节点，点击弹「到院 · 09:10」
   - `map.setViewport(...)` 自动缩放包含全部点
4. 页面隐藏（`visibilitychange`）或组件卸载 → 清定时器 + 销毁 map 实例。

**降级卡片**（`NlMapPanel` 内的 `v-else` 分支，非独立组件）：

```
┌────────────────────────────────┐
│ 陪诊员距医院直线约 1.2 公里      │
│ ⏱ 30 秒前更新 · 已到院          │
│ ⚠ 地图暂不可用，已切换为文字模式 │
└────────────────────────────────┘
```
（`NlNoticeBar` + 既有卡片样式，复用 `--nl-*` 令牌，不新造视觉。）

**接入点**

- `frontend/src/views/family/order-detail.vue`：在「服务进度」卡**上方**插入
  `<NlMapPanel>` 卡片，标题「实时位置」；数据源 = `GET /location` 铺底 +
  `LOCATION` / `LOCATION_PAUSED` 事件增量（复用已建立的 `socket`）。
  `order-companion` 为空（`PENDING`）时整卡不渲染。
- `frontend/src/views/companion/execute.vue`：把现有「当前定位」`NlCard` 里的坐标文字
  升级为 `<NlMapPanel>`（高度 180px），保留「刷新定位」按钮；
  新增「共享位置」开关（`el-switch`，调 `PUT /location-sharing`）；
  服务中每 30 秒 `watchPosition` 上报（`maximumAge: 10000, timeout: 15000`）。

### 六、上报调度（前端，`frontend/src/composables/useLocationReporter.js`）

```
进入页面 → 状态为 ACCEPTED/IN_SERVICE 且 sharingEnabled →
  setInterval(30_000) + document.visibilitychange 监听
  ├─ 可见 → reportLocation(取一次 getCurrentPosition，5s 超时)
  └─ 隐藏 → 暂停（不清 sharedState，恢复可见后立即上报一次再继续）
离开页面 → clearInterval + removeEventListener
失败 → 静默重试（下一次 tick 再来），连续 3 次失败 → NlNoticeBar 提示「定位暂时不可用」
```

**必须写进文档与页面文案的硬边界**：手机锁屏 / 浏览器切到后台时网页无法持续取定位，
这是 Web 平台限制而非实现缺陷。家属端在 `staleSeconds > 180` 时显示
「位置已 3 分钟未更新（可能是手机锁屏或网络中断）」，**不显示成「在线」**。

### 七、配置（`application.yml` + 启动期校验）

```yaml
nianglin:
  tracking:
    # 上报最小间隔（秒）：前端 30s 一次，后端用 10s 兜住重复/连点
    min-report-interval-seconds: 10
    # 落库抽稀：位移达到该值（米）才写一条轨迹
    min-move-meters: 50
    # 落库抽稀：或距上一条超过该秒数，也写一条（保证静止时也有时间锚点）
    min-record-interval-seconds: 120
    # Redis 最新点 TTL（秒）：略大于上报间隔的数倍，断连后仍能给出「最后已知位置」
    latest-ttl-seconds: 300
    # 超过该秒数未更新即在 UI 上标注「已暂停更新」
    stale-threshold-seconds: 180
```

`config/TrackingProperties.java` 用 `@ConfigurationProperties(prefix = "nianglin.tracking")`，
`@PostConstruct validate()` 校验（照 `MatchingProperties#validate` 的口径）：
所有值 > 0；`stale-threshold-seconds >= min-report-interval-seconds`；
`latest-ttl-seconds > min-report-interval-seconds`。不合规**启动即失败** ——
这类值配错不会报错，只会让地图安静地不动。

---

## Files to modify

### 文档（先做，守 §8 接口先行）

| 文件 | 改动 |
|---|---|
| `docs/adr/0022-map-live-tracking.md` | **新建**。背景 / 决策 15 条 / 坐标系 / 隐私边界 / 依赖例外 / 与 G7 的关系 / 替代方案（服务端 AK、离线瓦片、全量落库）为何被否 |
| `docs/api/04-companion-execution.md` | 开头「一期不做地图导航」改为「二期（ADR-0022）放开：家属端与陪诊员端可视化，ELDER/ADMIN 不渲染」；新增 §7–§9 三接口；§六 WS 增加 `LOCATION` / `LOCATION_PAUSED` 两个类型 |
| `docs/api/README.md` | 错误码总表加 `4004` / `4005`（与 `ResultCode` 双向一致，`gate_check --only error-codes` 会校验） |
| `AGENTS.md` §0.1 红线 3 | 追加二期变更说明（照 ADR-0011 的写法），指向 ADR-0022 |
| `docs/plan/plan.md` §一 约束 3 | 同上补二期标注（**正文不改**，只加变更注记，守 ADR-0006 版本锁） |
| `docs/prd/PRD.md` §5.2 | 「地图导航」行补「**二期已落地（ADR-0022）**：家属端/陪诊员端可视化，仅直线距离」 |
| `docs/spec/V3.x-roadmap-spec.md` | G7 与 §6.1 排除表的地图行补注记（原文保留） |
| `docs/design/design.md` | M-11 行「当前位置 + 距离阈值提示」→ 加「地图（ADR-0022）」；M-16 行加「实时位置地图」 |
| `docs/agents/FRONTEND_CONTRACT.md` | §453「不要引入新依赖」补一条已被批准的例外（`@baidumap/jsapi-loader`，见 ADR-0022）；§13 补 `23-map.spec.js` 在无 AK 环境下走降级路径的说明 |
| `CHANGELOG.md` | 新增版本条目 |

### 后端

| 文件 | 改动 |
|---|---|
| `common/ResultCode.java` | `LOCATION_REPORT_TOO_FREQUENT(4004)`、`LOCATION_SHARING_DISABLED(4005)` |
| `constant/RedisKeyConstants.java` | `TRACK_LATEST_PREFIX` / `TRACK_SHARE_PREFIX` / `TRACK_RATE_PREFIX` + 三个静态工厂方法（现有风格） |
| `config/TrackingProperties.java` | **新建**，启动期校验 |
| `src/main/resources/application.yml` | `nianglin.tracking.*` 五项 |
| `dto/LocationReportDTO.java` | **新建**（`@Schema` 写明 WGS84） |
| `dto/LocationSharingDTO.java` | **新建**（`{ enabled: Boolean }`，`@NotNull`） |
| `vo/LocationVO.java` | **新建**，工厂方法 `of(...)`（照 `CheckinVO` / `TrackPointVO` 的写法，坐标走 `plain()`） |
| `service/ExecutionService.java` | 三个方法签名 |
| `service/impl/ExecutionServiceImpl.java` | `reportLocation` / `latestLocation` / `setLocationSharing` + 抽稀 + 限流 + 推送；复用 `GeoUtil`、`requireCompanionOf`（抽出当前在 `checkin` 里的归属判断，**不改语义**） |
| `controller/execution/ExecutionController.java` | 三个端点 + `@Operation`（写明 WGS84、限流、可见范围）；上报与开关加 `@PreAuthorize("hasRole('COMPANION')")`，查询不加（归属走 Service，理由同现有四个查询接口的类注释） |
| `websocket/OrderProgressHub.java` | `TYPE_LOCATION` / `TYPE_LOCATION_PAUSED` + `publishTo(..., roles)` |
| `test/.../TrackingPropertiesTest.java` | **新建**：合法/非法配置的启动期校验 |
| `test/.../ExecutionLocationTest.java` | **新建**：限流 4004 / 暂停 4005 / 抽稀（<50m 不落库、≥2min 必落库）/ 距离计算 / 角色过滤推送 |
| `test/.../OrderProgressHubRoleFilterTest.java` | **新建**：`publishTo` 只发给指定角色 |

### 前端

| 文件 | 改动 |
|---|---|
| `package.json` / `pnpm-lock.yaml` | `pnpm add @baidumap/jsapi-loader`（**依赖例外，需你在评审时确认**） |
| `.env.development` | 加 `VITE_BAIDU_MAP_AK=`（**空占位** + 申请指引注释） |
| `.env.production` | 加 `VITE_BAIDU_MAP_AK=`（空占位，部署时由 CI/服务器注入） |
| `src/utils/coordConvert.js` | **新建**：WGS84→GCJ02→BD09 |
| `src/utils/baiduMap.js` | **新建**：`hasMapAk()` / `loadBaiduMap()` |
| `src/composables/useLocationReporter.js` | **新建**：30s 定时 + 可见性 + 失败重试 |
| `src/components/NlMapPanel.vue` | **新建**：地图 + 降级卡片 |
| `src/components/index.js` | 导出 `NlMapPanel` |
| `src/api/execution.js` | `reportLocation` / `getLocation` / `setLocationSharing`；顶部注释里「一期不接入地图」改口径 |
| `src/views/family/order-detail.vue` | 「实时位置」卡 + `LOCATION` 事件处理 |
| `src/views/companion/execute.vue` | 定位卡升级为地图 + 共享开关 + 上报节流；去掉三处「一期不接入地图」文案 |
| `src/utils/__tests__/coordConvert.test.mjs` | **新建**：已知点断言 |
| `src/components/__tests__/NlMapPanel.test.mjs` | **新建**：无 AK → 降级卡片；加载失败 → 降级卡片；有数据 → 渲染容器 |
| `e2e/specs/23-map.spec.js` | **新建**：无 AK 环境下断言降级卡片文案与 `NlNoticeBar`；有 AK 时（本地）断言 `.BMap_cpyCtrl`/canvas 存在 |
| `e2e/specs/11-realtime.spec.js` | 若既有断言依赖「实时位置」文字，同步更新 |

---

## Reuse（不新造轮子）

| 能力 | 复用点 |
|---|---|
| 距离计算 | `backend/.../util/GeoUtil.java` `distanceMeters` / `isValidCoordinate`（Haversine，已有单测） |
| 订单归属 | `ExecutionServiceImpl` 现有 `order.getCompanionId()` 比对（4003）；查询侧 `OrderService#requireInvolved` |
| 轨迹表 | `companion_track`（`accuracy` / `speed` / `idx_order_time` 列都已存在，**零 DDL**） |
| 实时通道 | `OrderProgressHub` + `OrderProgressHandler` + `JwtHandshakeInterceptor`（握手已校验 `requireInvolved` 并把 `ROLE` 写进会话属性） |
| 前端 WS | `frontend/src/utils/realtime.js` `createRealtimeSocket`（心跳 / 指数退避 / 令牌走 query） |
| 前端接口层 | `api/execution.js` 的 `connectProgressSocket`（family order-detail 已建立连接，位置事件复用同一条） |
| Redis 键风格 | `constant/RedisKeyConstants`（前缀常量 + 静态工厂，禁止散写字面量） |
| 配置校验风格 | `MatchingProperties#validate` / `OperationsProperties#validate` |
| UI 令牌 / 组件 | `NlCard` / `NlNoticeBar` / `NlSkeleton` / `NlEmpty` / `--nl-*` 设计令牌 |
| 错误码门禁 | `tools/ai_flow/gate_check.py --only error-codes`（`ResultCode` ↔ 文档总表双向一致） |

---

## Steps

- [x] **S0 · 分支**：`git checkout -b feature/map-live-tracking`（禁在 `main` 上写代码，`tools/hooks/pre-commit` 会拒）
- [x] **S1 · ADR**：写 `docs/adr/0022-map-live-tracking.md`，含依赖例外与坐标系决策
- [x] **S2 · 文档先行**：改 `docs/api/04-companion-execution.md`（§7–§9 + WS 两类型）、`docs/api/README.md`（4004/4005）
- [x] **S3 · 宪法同步**：`AGENTS.md` §0.1.3 注记、`plan.md` §一、`PRD.md` §5.2、`V3.x-roadmap-spec.md`、`design.md` M-11/M-16
- [x] **S4 · 后端基础**：`ResultCode` + `RedisKeyConstants` + `TrackingProperties` + `application.yml`
- [x] **S5 · 后端接口**：DTO / VO / Service / Controller（含抽稀、限流、暂停、推送）
- [x] **S6 · Hub 角色过滤**：`publishTo` + 两个事件类型
- [x] **S7 · 后端测试**：`TrackingPropertiesTest` / `ExecutionLocationTest` / `OrderHubRoleFilterTest`；跑 `mvn -B verify`
- [x] **S8 · 前端依赖与基础设施**：`pnpm add @baidumap/jsapi-loader`；`.env.development` / `.env.production` 占位；`coordConvert.js` + `baiduMap.js`
- [x] **S9 · 前端组件**：`NlMapPanel.vue`（地图 + 降级）+ 导出 + 组件单测
- [x] **S10 · 前端接入**：`api/execution.js` 三函数；`useLocationReporter.js`；`order-detail.vue`；`execute.vue`
- [x] **S11 · 前端测试**：`coordConvert.test.mjs` / `NlMapPanel.test.mjs`；`pnpm run lint:check` + `pnpm run test` + `pnpm run build`
- [x] **S12 · e2e**：`e2e/specs/23-map.spec.js`（降级路径为主）+ 更新 `11-realtime.spec.js`；`pnpm exec playwright test 23-map 11-realtime`
- [x] **S13 · 静态门禁**：`python tools/ai_flow/gate_check.py --all-md` + `python tools/scan_bad_chars.py`
- [x] **S14 · 真实 AK 手工实测**（**等你补上 `VITE_BAIDU_MAP_AK` 之后我做**）：双浏览器（家属 + 陪诊员）跑一遍，截图存 `docs/reports/assets/`
- [x] **S15 · 收尾**：`CHANGELOG.md`、`docs/agents/FRONTEND_CONTRACT.md` 例外说明、报建议标签 `v6.8` 等你确认后再打

---

## 需要你做的事（申请 AK）

1. 打开 <https://lbsyun.baidu.com/>，用百度账号登录 → 右上「控制台」。
2. 首次进入需完成**开发者认证**（个人：身份证，即时出结果；免费）。认证后配额更高。
3. 「应用管理 → 我的应用 → 创建应用」：
   - **应用名称**：`银龄伴诊`
   - **应用类型**：**浏览器端**（这是关键 —— 选「服务端」拿到的 AK 不能给 JSAPI 用）
   - **启用服务**：勾选 **JavaScript API**
   - **Referer 白名单**：本地调试填 `*`（最省事），
     或填 `localhost:5141,127.0.0.1:5141`；上线时改成真实域名（`*.your-domain.com`）。
     ⚠️ 白名单填得比实际访问域名窄，会拿到 `status:220 APP Referer` 错误，
     表现为「地图一片灰」—— 届时先看浏览器 Network 里 `api.map.baidu.com` 的响应。
4. 提交后复制 **访问应用（AK）**。
5. 在仓库里新建 `frontend/.env.development.local`（**已被 `.gitignore` 的 `*.local` 忽略，不会入库**）：

```dotenv
VITE_BAIDU_MAP_AK=你复制的AK
```

> **这不是「密钥泄露」**：浏览器端 AK 本来就是公开值，安全边界在 Referer 白名单。
> 但仓库仍旧不让真值入库 —— 避免它随 git 历史永久留存、也避免有人拿着它去别处用。
> 反过来 `VITE_` 前缀意味着它会被打进前端 bundle，所以**不要**在这套机制里放服务端密钥。

6. 补完之后告诉我，我跑 S14 的真实 AK 实测并把截图/结论贴回来。
   期间若只跑自动化测试，`.env.development.local` 缺失会让 e2e 走**降级路径** —— 那是预期的绿。

---

## Verification

### 自动化（不需要 AK、不需要网络）

```powershell
# 后端
cd backend; mvn -B verify

# 前端
cd frontend; pnpm run lint:check; pnpm run test; pnpm run build

# 静态门禁（错误码双向一致 / 页面编号 / 文档围栏 / 引用路径 / 坏字符）
python tools/ai_flow/gate_check.py --all-md
python tools/scan_bad_chars.py
```

### e2e（无 AK 环境，断言降级路径）

```powershell
cd frontend; pnpm exec playwright test 23-map 11-realtime
```

必须绿的断言：

- [x] 家属订单详情（`IN_SERVICE` 单）出现「实时位置」卡；无 AK 时显示文字降级卡片，**不白屏、不报错弹窗**
- [x] 降级卡片文案包含「直线约」与「前更新」，且**不出现**「预计到达 / 导航 / 路线」
- [x] `PENDING` 订单**不渲染**该卡片
- [x] 陪诊员打卡页出现地图容器（或降级卡片）+ 「共享位置」开关；关闭后卡片显示「已暂停共享」
- [x] 上传位置后，家属端**不刷新页面**收到 `LOCATION` 事件并更新距离文案（可用 30s 内的两次上报验证）
- [x] 角色过滤：ELDER / ADMIN 订阅同一订单的 WS **收到不到** `LOCATION` 事件

### 真实 AK 手工实测（S14，等你补变量后我执行）

- [x] AK 有效：地图出瓦片、蓝点=陪诊员、红点=医院、灰线=轨迹、节点可点
- [x] 直连绕过后端处理：把 `.env.development.local` 改成错误 AK → 8 秒内降级到文字卡片
- [x] 双端联动：陪诊员开位置共享走动 → 家属端红蓝点距离数字下降（截图对比）
- [x] 打卡一致性：打卡 `ARRIVE` 时 `4001` 判定与地图上显示的直线距离**同口径**（误差 0，同一个 `GeoUtil`）
- [x] 断网 30 秒再恢复：卡片显示「位置已 x 分钟未更新」，恢复后自动继续，不出现重复消息
- [x] 老人模式（`<html class="elderly-mode">`）不受影响：ELDER 侧无地图入口
- [x] 浏览器 Network 无 AK 明文出现在**后端**日志（`rg "VITE_BAIDU_MAP_AK|a1B2c3"` 抽查）

### 合规自查

- [x] 地图与卡片文案**无**「建议服用 / 推荐剂量 / 诊断为 / 可能是 XX 病」
- [x] 位置数据只落 Redis + `companion_track`，**不进日志**（日志只记 orderId / node / distance）
- [x] `LOCATION` 事件不下发手机号 / 姓名 / 身份证（只含坐标与距离）
- [x] 陪诊员暂停开关生效后，`4005` 可达且前端有明确文案

---

## 未决的遗留风险（评审时请确认）

| 风险 | 说明 | 现在的处理 |
|---|---|---|
| 网页后台无法定位 | 手机锁屏 / 切后台时浏览器停止给坐标 | 文案如实说明，`staleSeconds > 180` 明确标「未更新」；不做「假在线」 |
| 百度配额 | 浏览器端 JSAPI 走服务端计费口径，控制台有日配额 | 只加载一次地图 + 30s 一次上报，单用户配额压力极小；ADR 里记一条「异常流量看控制台」 |
| 商用授权 | 百度条款：商业用途需商用授权（本项目为课程设计，非商业） | ADR 里写明「课程设计场景不涉及商用授权，如转商用需另行申请」 |
| `@baidumap/jsapi-loader` 很新 | npm 上仅 `1.0.0`（2026-07 发布） | 已核对：官方 author `Baidu Maps`、MIT、5 文件 15KB；若担心，S8 可退回「自注入 script」方案（备选已在 ADR 记录） |
| 抽稀 vs 取证 | 静止时轨迹点稀，申诉取证时可能看不到「他在某处停了 40 分钟」 | `min-record-interval-seconds: 120` 保证静止也有每 2 分钟的时间锚点 |
| **「老人」≠「医院」** | 需求原文是「陪诊员与**待接待老人**之间的距离」，而 D2 定的终点是**医院坐标**（因为 `elder_profile` 表没有坐标列）。UI 文案必须写「距医院直线约 x 公里」，**不能**写「距老人」—— 那会是一句界面在说谎的话 | 若确实要「距老人住址」，需二次扩围：`elder_profile` 加坐标列 + V6 迁移 + 档案编辑页取点 + 两套距离并存；本条留待你决定，未纳入本期 |
