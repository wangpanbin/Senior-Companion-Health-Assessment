# 0022. 百度地图可视化定位（家属端实时距离 + 陪诊员打卡）

| | |
|---|---|
| 状态 | 已接受 (Accepted) |
| 日期 | 2026-11-27 |
| 决策者 | 项目负责人 |
| 关联模块 | M5 陪诊执行与打卡（可视化扩围）/ M4 订单 / M11 陪诊员打卡页 / M16 订单详情 |
| 关联文档 | `docs/api/04-companion-execution.md` §7–§9 · `docs/plan/plan.md` §一 约束 3 · `docs/prd/PRD.md` §5.2 · `docs/spec/V3.x-roadmap-spec.md` G7 |
| 相关 ADR | 0001（M5 WebSocket 鉴权）· 0008（移动端形态）· 0010（订单流转单一入口）· 0011（IM 聊天扩围，同类先例）· 0016（匹配距离口径） |

---

## 背景

一期范围控制（`docs/plan/plan.md` §一 硬性约束 3 / `AGENTS.md` §0.1 红线 3 / `PRD.md` §2.2）砍掉了地图导航，
用「文字地址 + 签到坐标」替代：

- `companion_order` 有 `longitude / latitude`，只用于 M5 打卡的距离校验（`GeoUtil.distanceMeters` vs `checkin-max-distance-meters`）。
- `companion_track` 有完整坐标列（含 `accuracy` / `speed`），但**只在打卡那一刻**由 `ExecutionServiceImpl#checkin` 双写一条。
- 前端 `views/companion/execute.vue` 用 `navigator.geolocation.getCurrentPosition` 取一次坐标，页面文案写死「一期不接入地图」。
- `docs/api/04-companion-execution.md` 开头明确「一期不做地图导航……不渲染地图」；`/track` 接口的注释写着「一期不渲染地图，数据用于路径文字摘要与异常打卡取证」。

真实场景里家属最焦虑的那段时间恰恰是「陪诊员已经接单、人还在路上」：
文字时间线上只有「出发 08:20」一行，家属不知道人到哪了、还要多久、会不会迟到。
陪诊员侧也有一半问题：打卡时无法确认「我是不是已经进了 2000 米阈值」，只能盲点提交再被 `4001` 打回。

`ADR-0011`（IM 聊天扩围）已经确立了同类先例：**红线不是不能改，但改必须留 ADR，且范围要收到最小**。
本次沿用同一姿态：只放开「可视化 + 直线距离」，**不放开路线规划与导航**。

---

## 决策

**地图只在 FAMILY 订单详情与 COMPANION 打卡页渲染；位置流复用既有 `companion_track` + `/ws/progress` 通道，
新增一条 30 秒周期的位置上报接口；库里坐标系保持 WGS84 不变，只在渲染前由前端转 BD09；直线距离继续由后端 `GeoUtil` 计算。**

### D1 · 范围：两张页面，两个角色，不做第三个入口

| 页面 | 角色 | 内容 |
|---|---|---|
| `M-16` 家属订单详情（`family/order-detail.vue`） | FAMILY | 「实时位置」卡：陪诊员点 + 医院点 + 轨迹折线 + 打卡节点 + 直线距离文案 |
| `M-11` 陪诊员打卡页（`companion/execute.vue`） | COMPANION | 定位卡升级为地图 + 「共享位置」开关 + 打卡前的距离参照 |

- **ELDER 不加**：老人端核心流程 ≤3 次点击、触控区 ≥48px（`docs/design/design.md` M-04/§适老化），
  百度地图默认控件与该约束直接冲突；老人真正需要的是「陪诊员已到院」这一句话，已在时间线里。
- **ADMIN 不加**：管理员要的是纠纷取证，`GET /api/execution/{orderId}/track` 的原始数据足够，
  给他一张实时地图等于把管理员变成隐性监视器（与 `AGENTS.md` §0.1「隐私最小化」不合）。

### D2 · 距离终点：`companion_order` 的医院坐标，不是老人住址

需求原文是「陪诊员与待接待老人之间的距离」。但 `elder_profile` 表**只有文字 `address`，没有任何坐标列**
（`V1__init_schema.sql` §6）。三个选项：

| 方案 | 代价 | 结论 |
|---|---|---|
| 取 `companion_order.longitude/latitude` | 零 DDL；与打卡 `4001` 同口径 | **选它** |
| `elder_profile` 加坐标列 | V6 迁移 + 档案编辑页取点 + 种子回填 + 一单出现两个距离 | 不做 |
| 两个都显示 | 上一行的全部代价 + UI 更难懂 | 不做 |

**这条必须在 UI 文案上诚实**：一律写「距医院直线约 x 公里」，**不得**写「距老人」。
少写这一句，界面就在说谎 —— 而说谎的界面比没有地图更糟。

### D3 · 上报：30 秒周期 + 仅页面可见 + 后端 10 秒限流

- **窗口**：`ACCEPTED`（已接单，在路上）起，`COMPLETED / CANCELLED` 止。
  只做 `IN_SERVICE` 会把「路上」这段砍掉，而那正是家属最关心的部分。
- **频率**：前端 30 秒一次；后端 `SET NX EX 10` 限流，重复/连点回 `4004`。
- **仅页面可见**：`document.visibilitychange` 隐藏即暂停。恢复可见后**立刻补报一次**再继续，
  避免「恢复后要等 30 秒才有位置」。
- **硬边界**：手机锁屏 / 浏览器切后台时 Web 无法持续取定位，这是平台限制而非实现缺陷。
  家属端在 `staleSeconds > 180` 时显示「位置已 x 分钟未更新（可能是手机锁屏或网络中断）」，
  **不允许**把陈旧点渲染成「在线」。

### D4 · AK 形态：浏览器端 AK，前端直连 JSAPI

百度 JSAPI 的鉴权模型就是浏览器直连（脚本、瓦片、检索全部打到 `api.map.baidu.com`），
所以必须申请**浏览器端**应用类型的 AK，安全边界在 **Referer 白名单**，不在 AK 保密。

- 环境变量 `VITE_BAIDU_MAP_AK`；占位写 `frontend/.env.development`（入库，空值 + 申请指引注释），
  真值写 `frontend/.env.development.local`（`.gitignore` 的 `*.local` 已忽略）。
- **不放服务端**：把浏览器 AK 藏到后端再下发，本质上仍是公开值（DevTools 一眼可见），
  只是多一个未鉴权接口暴露面，**净收益为负**。
- **不申请服务端 AK**：本次不做逆地理编码与路线规划（D8），一个浏览器端 AK 足够。

### D5 · 坐标系：库里统一 WGS84，渲染时前端转 BD09

| 环节 | 坐标系 |
|---|---|
| `navigator.geolocation` | WGS84 |
| `companion_track` / `order_checkin` / `companion_order`（存库） | **WGS84（不变）** |
| `GeoUtil.distanceMeters`（打卡 4001 / 匹配距离分） | **WGS84（不变）** |
| 百度地图渲染 | BD09（前端 `utils/coordConvert.js` 转换） |

**反向方案（落库改存 BD09）被否**：现有种子坐标与历史打卡点全是 WGS84 口径，且**无标记**，
改口径后新旧数据无法区分；而 WGS84→BD09 的偏移约 **500 米**，是打卡阈值 2000 米的 25% ——
`4001` 判定会凭空出错，且错得看不出来（地图是对的，判定是错的）。
渲染层偏移错了，肉眼能看见；判定层偏移错了，只有纠纷时才被翻出来。

### D6 · 加载方式：官方 `@baidumap/jsapi-loader`（**依赖例外，已批准**）

`package.json` 新增生产依赖 `@baidumap/jsapi-loader`（MIT，官方 author `Baidu Maps`，v1.0.0，5 个文件约 15KB）。

**这是对 `docs/agents/frontend-conventions.md` / `FRONTEND_CONTRACT.md`「不要引入新依赖」的显式例外**，
理由：

1. 百度 JSAPI **不支持本地打包**，必须从 `api.map.baidu.com` 动态加载脚本；
2. 官方 loader 已处理三件容易写错的事 —— **脚本单次注入**（SPA 多组件挂载）、
   **超时**（`timeout` 参数）、**命名空间版本差异**（3.0 / gl / 4.0 分别是 `BMap` / `BMapGL`）；
3. 备选「自注入 `<script>` + `callback`」约 40 行，能work但把「单例 / 超时 / 重复加载」三件事
   重新实现一遍；loader 已把这些写成 Promise 语义，且是我们的测试能 mock 的唯一接缝。

**回退预案**：若评审认为「新的 1.0.0 依赖不可接受」，`utils/baiduMap.js` 里只需替换 3 行
（`BMapLoader.load(...)` → 自写 `loadScript()`），组件与业务代码零改动。该文件的唯一职责就是这个接缝。

### D7 · 降级：加载失败 → 文字卡片，不弹窗、不白屏

三级降级，**每一级都必须能说清「为什么没地图」**：

| 条件 | UI |
|---|---|
| `VITE_BAIDU_MAP_AK` 为空 | 文字卡片 + 「未配置地图 AK」 |
| AK 无效 / Referer 被拒 / 8 秒超时 | 文字卡片 + 「地图暂不可用，已切换为文字模式」 |
| 加载成功 | 地图 |

**CI / e2e 默认走降级路径**（CI 无网络、无 AK，本地开发者多数也不配 AK）——
这不是「顺便测到」，而是刻意的：降级路径是**唯一在评委机器上 100% 会被执行**的分支。

### D8 · 距离内涵：只做直线距离，不做路线规划 / ETA

- 距离一律 `GeoUtil.distanceMeters`（Haversine），与打卡判定、匹配引擎 `DistanceMatchRule` **同一个函数**。
- UI 文案固定「直线约 x 公里（仅供参考）」。
- **不做**路线规划：百度 `Driving` 是驾车语义，陪诊员可能步行或公交，估时反而不准；
  且需要服务端 AK + 配额 + 缓存 + 超时降级，收益不抵复杂度。

### D9 · 位置流三视图：Redis 最新点 / 抽稀轨迹 / 打卡节点

| 视图 | 载体 | TTL / 上限 | 用途 |
|---|---|---|---|
| 最新点（瞬时态） | Redis `track:latest:{orderId}` | 300s | 家属进页面立刻有位置；**每次上报都覆盖** |
| 轨迹（历史） | `companion_track`，**抽稀写入** | `/track` 已有 2000 上限 | 地图折线 + 申诉取证 |
| 打卡节点 | `order_checkin` | — | 地图节点标记 + 时间线 |

**抽稀规则**：与上一条轨迹点比，**位移 ≥ 50 米 或 间隔 ≥ 120 秒**才落库，否则只刷 Redis。
单均 3 小时从 ~360 条降到 ~50 条；「静止也有 2 分钟一个时间锚点」保证「他在某处停了 40 分钟」可取证。
/`track` 的 2000 条上限也因此再不会被长单截断。

### D10 · 可见范围：位置事件只推 FAMILY + COMPANION

`/ws/progress` 的握手鉴权是 `requireInvolved`（FAMILY / COMPANION / ELDER / ADMIN 都能订阅）——
那对**打卡进度**是对的，对**一个人的实时位置**不对。

`OrderProgressHub` 新增 `publishTo(orderId, type, data, roles)`：遍历会话时读
`session.getAttributes().get(WsAttributes.ROLE)`（握手时已写入），角色不在集合内则跳过。
`publish(...)` 语义保持「全广播」，`ORDER_PROGRESS` 行为零变更。

- `LOCATION` → `{FAMILY, COMPANION}`
- `LOCATION_PAUSED` → `{FAMILY, COMPANION}`

`GET /api/execution/{orderId}/location` 同口径：ELDER / ADMIN 回 `3004`。
原则：**能收到推送的，才能查询；能查询的，才能收到推送。**

### D11 · 错误码：新增 4004 / 4005，其余复用

| 码 | 含义 |
|---|---|
| `4004` | 位置上报过于频繁（最小间隔 `min-report-interval-seconds`，默认 10 秒） |
| `4005` | 位置共享已关闭（陪诊员主动暂停） |

坐标非法复用 `400`（参数不合法）；非本单陪诊员复用 `4003`；状态非 `ACCEPTED` / `IN_SERVICE` 复用 `3002`。
**不静默忽略**：静默让「为何位置不动」变成无法排查的问题，与仓库「静默截断一律禁」的既有风格相反。

### D12 · 传递状态：Redis 持久化 + 主动事件

`PUT /api/execution/{orderId}/location-sharing { enabled }`：

- 写 Redis `track:share:{orderId}` = `on` / `off`（随订单生命周期自然过期，不设 TTL —— 位置共享状态应与订单同寿，
  而不是 5 分钟后「自己又打开了」）。
- 关闭时推 `LOCATION_PAUSED` 事件；恢复时推一条 `LOCATION`（若有最新点）。

**为什么不静默停发**：冻住的红点会被家属读成「出事了」。暂停必须是**可解释的空白**。

### D13 · 配置全部启动期校验

`nianglin.tracking.*` 五项进 `application.yml`，`TrackingProperties#validate()` 在 `@PostConstruct` 校验：

```
min-report-interval-seconds      默认 10
min-move-meters                  默认 50
min-record-interval-seconds      默认 120
latest-ttl-seconds               默认 300
stale-threshold-seconds          默认 180
```

约束：全部 `> 0`；`stale-threshold-seconds >= min-report-interval-seconds`；
`latest-ttl-seconds > min-report-interval-seconds`。不合规**启动即失败** ——
这类值配错不会报错，只会让地图安静地不动（与 `MatchingProperties` / `OperationsProperties` 同口径）。

### D14 · 打卡与位置共用来源

打卡（`/checkin`）与位置上报（`/location`）是**同一次定位的两个视图**：
前者是「阶段性里程碑」，后者是「连续轨迹」。两者都写 `companion_track`，但语义不同：

- 打卡点**永远落库**（`node` 非空的轨迹点不可抽稀），否则时间线上的「到院 09:10」在地图上找不到锚点；
- 位置点**可抽稀**（`node` 为 `NULL`）。

### D15 · 合规

位置数据属于个人行踪信息，沿用 `AGENTS.md` §0.1 的隐私最小化口径：

- `LOCATION` 事件**只含坐标、距离、时间**，不含姓名 / 手机号 / 身份证；
- 后端日志只记 `orderId` / `node` / `distance`，**不打印坐标原值**；
- 地图与卡片文案**不得**出现「建议服用 / 推荐剂量 / 诊断为 / 可能是 XX 病」；
- 页面不出现「导航 / 路线 / 预计到达」—— 那会暗示我们提供导航服务（一期红线原文）；
- 陪诊员可随时暂停共享（D12）。

---

## 选项

### A. 维持现状（只做距离校验，不渲染地图）

- **优点**：零改动、零新依赖、红线不动。
- **缺点**：家属对「人在哪」完全无感知；陪诊员打卡前无距离参照，只能被 `4001` 打回再重试；
  `companion_track` 的坐标列长期只有 6 条/单，「轨迹取证」名不副实。

### B. 全平台地图（含老人端、管理端、大厅）

- **优点**：能力最完整。
- **缺点**：老人端与 §适老化约束直接冲突（48px 触控 / 18px 字号 / ≤3 次点击）；管理端变成隐性监视器；
  大厅把陪诊员实时位置暴露给所有已过审陪诊员，是隐私事故。**范围必须收窄。**

### C. 家属端 + 陪诊员端，仅直线距离，位置事件按角色过滤 ← **本次选**

- **优点**：命中真实诉求（家属关心「在路上」、陪诊员要「够不够近」）；复用 `companion_track` 与
  `/ws/progress`，**零 DDL、零新通道**；距离与打卡 `4001`、匹配引擎同一个 `GeoUtil`，不会出两套口径。
- **缺点**：距离终点是医院而非老人住址（D2 已说明为何接受）；Web 平台无法后台持续定位（D3 硬边界）。

---

## 后果

### 正面

- 家属端「陪诊进度」从纯文字时间线升级为「看得见的位置」，这是本项目在答辩中最直观的一屏。
- 陪诊员打卡前能看到「距医院还有多远」，`4001` 从「盲试错误」变成「可预判的提示」。
- 位置流复用既有表与既有 WS 通道，新增边际成本低（一个接口 + 一个 Hub 方法 + 一个前端组件）。
- 抽稀让 `companion_track` 从「只会被打卡写 6 次」变成「有连续轨迹且体量可控」。

### 负面

- **红线表述被修订**（`AGENTS.md` §0.1.3 / `docs/plan/plan.md` §一 / `PRD.md` §5.2 / `V3.x-roadmap-spec.md` G7），
  历史文档读者需靠本 ADR 理解口径变迁；PRD 正文因版本锁（ADR-0006）不改，存在短期口径差（§5.2 已标注）。
- 新增一个前端生产依赖（`@baidumap/jsapi-loader`，D6 已列为例外）。
- 引入一个不可控外网依赖（`api.map.baidu.com`）：百度侧故障 / 配额耗尽 / 白名单配错都会让地图变文字卡片。
  已用三级降级兜住，但「地图没了」这件事从此有可能发生。
- 位置数据是敏感个人行踪信息：只推两角色 + 可暂停 + 不进日志，仍是**能力扩围**而非无风险。
- `LOCATION` 事件 30 秒一条，单订单 WS 流量从「打卡时几条」变成「服务期内 ~360 条」——
  在 `OrderProgressHub` 的同步发送模型下可接受（一单一组、组内通常 2 个会话），但不宜再加频率。

### 验证标准

- [ ] `mvn -B verify` 全绿，含 `TrackingPropertiesTest` / `ExecutionLocationTest` / `OrderProgressHubRoleFilterTest`
- [ ] `pnpm run lint:check` + `pnpm run test`（含 `coordConvert.test.mjs` / `NlMapPanel.test.mjs`）+ `pnpm run build`
- [ ] `frontend/e2e/specs/23-map.spec.js` 在**无 AK 环境**全绿（断言降级卡片，不白屏、不弹错）
- [ ] `python tools/ai_flow/gate_check.py --all-md` 与 `python tools/scan_bad_chars.py` 全绿（含错误码 4004/4005 双向一致）
- [ ] 真实 AK 手工实测：地图出瓦片、蓝点/红点/折线/节点齐全；错误 AK 8 秒内降级；双端联动距离下降（截图存 `docs/reports/assets/`）
- [ ] ELDER / ADMIN 订阅同一订单 WS 收不到 `LOCATION`；`GET /location` 对他们回 `3004`
- [ ] 陪诊员关闭共享后，家属端显示「已暂停共享」，且继续上报回 `4005`

### 非目标（明确排除，防蔓延）

路线规划 / 预计到达时间（ETA）；语音导航 / 偏航提醒；逆地理编码（打卡 `address` 继续由前端传或留空）；
老人住址坐标与「距老人」距离；老人端与管理端地图；离线瓦片 / 自建地图服务；
百度鹰眼轨迹托管；位置分享给第三方 / 家属之间互相可见；历史轨迹回放播放器；
地图上的实时多人头像；`@baidumap/jsapi-loader` 之外的任何地图 SDK。
