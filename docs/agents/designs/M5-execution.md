# M5 设计评审 · 陪诊执行、打卡与实时进度

- **模块**：M5（主路径 B）
- **评审时间**：2026-09-16（依落地代码反向补齐）
- **关联**：`docs/api/04-companion-execution.md`、`docs/adr/0001-m5-websocket-auth.md`、`PLAN_BACKEND.md §8 M5`
- **代码**：`controller/execution/ExecutionController`、`service/impl/ExecutionServiceImpl`、`websocket/*`、`constant/CheckinNode`、`util/GeoUtil`

---

## 1 · 接口表

| # | 方法 | 路径 | 角色 | 入参 | 出参 |
|---|---|---|---|---|---|
| 1 | POST | `/api/execution/{orderId}/checkin` | COMPANION | `CheckinCreateDTO` | `CheckinResultVO` |
| 2 | POST | `/api/execution/{orderId}/photo` | COMPANION | `MultipartFile` | `FileUploadVO` |
| 3 | GET | `/api/execution/{orderId}/checkins` | 登录用户 | path `orderId` | `List<CheckinVO>` |
| 4 | GET | `/api/execution/{orderId}/track` | 登录用户 | path `orderId` | `List<TrackPointVO>` |
| 5 | GET | `/api/execution/{orderId}/progress` | 登录用户 | path `orderId` | `ProgressVO` |
| 6 | WS | `/ws/progress?token=&orderId=` | 订单相关方 | query | 服务端推 `ORDER_PROGRESS` 事件 |

> 查询类（3/4/5）**不加 `@PreAuthorize`**：家属与陪诊员都要读，归属由 Service 判定
> —— 否则家属看不到自己订单的陪诊进度。WebSocket 端点由 `JwtHandshakeInterceptor` 单独鉴权（ADR-0001）。

---

## 2 · 数据流

```
POST /{orderId}/checkin
  ExecutionController(checkin, @PreAuthorize COMPANION)
    → ExecutionServiceImpl.checkin()
        ① 订单存在 & 状态为 IN_SERVICE
        ② 我是该订单的陪诊员（4003 NOT_ORDER_COMPANION）
        ③ 节点合法（CheckinNode.of）
        ④ 不是重复打卡（4002 CHECKIN_DUPLICATED）
        ⑤ 距离校验：GeoUtil.distanceMeters 与
           ${nianglin.order.checkin-max-distance-meters:2000} 比较（4001 NOT_IN_CHECKIN_RANGE）
        ⑥ 双写：
             INSERT order_checkin（含 distance / node_sort 快照）
             INSERT companion_track（轨迹点）
        ⑦ OrderProgressHub.push(orderId, ORDER_PROGRESS) → WebSocket 广播给订单相关方

表 → Mapper → Service → Controller：
  order_checkin   ─┐
  companion_track ─┼→ OrderCheckinMapper / CompanionTrackMapper
  companion_order ─┘        ↓
                       ExecutionServiceImpl（5 道闸门 + 双写 + 推流）
                            ↓
                       ExecutionController / OrderProgressHandler（WS）
```

**重连补齐**：客户端重连后拉 `GET /api/execution/{orderId}/progress` 与
`GET /api/execution/{orderId}/checkins`，用「进度快照 + 全量打卡记录」重建时间线，
避免断网期间的事件丢失。

> 这一步刻意**不复用** `PLAN_BACKEND.md §M5` 里提到的
> `/api/execution/checkin-missed?since={lastEventId}`：
> ① `docs/api/04-companion-execution.md` §6 明确把「前端重连后先调 `/progress` 与 `/checkins` 补齐」
> 列为与「服务端缓存最近 N 条事件」并列的合法方案；
> ② `since` 语义要求服务端有单调递增的事件 id 序列，而本项目的推送是「状态快照 + 事件类型」
> 而非编号事件流，硬造一个 id 只会让「补齐」和「快照」两套真相并存。
> 因此计划书里那个端点**未实现**（属有意取舍，不是遗漏），审计报告初版误标为「端点存在」已更正。
> 并发重连风暴已按真实路径压测，见 `docs/agents/reports/pressure-test-m5-m6.md`。

---

## 3 · 状态机（打卡节点）

```
出发(1) → 到院(2) → 就诊中(3) → 取药(4) → 离院(5) → 完成(6)
              ├─ 可跳过（如本次不需要取药）
              └─ 不可回退（不能先打「取药」再打「到院」）
```

| 非法情况 | 错误码 |
|---|---|
| 未到陪诊地点 | `4001 NOT_IN_CHECKIN_RANGE` |
| 该节点已打卡 | `4002 CHECKIN_DUPLICATED` |
| 非该订单陪诊员 | `4003 NOT_ORDER_COMPANION` |

**实现要点**
- 顺序值 `node_sort` **单独存列**，不靠 `ordinal()` —— 枚举声明顺序一旦调整，历史数据不会错位。
- 节点校验基于「已打卡最大 sort」，只允许前进。
- `OrderCheckin` 与 `CompanionTrack` **双写在同一事务**，任一失败整体回滚。

---

## 4 · WebSocket 鉴权（ADR-0001 摘要）

- 握手 URL 带 `token` + `orderId`（浏览器 `WebSocket` 不允许自定义请求头）。
- `JwtHandshakeInterceptor` 在 `beforeHandshake` 校验**两件事**：令牌本身（类型/黑名单/密码版本）
  + **订单归属**（`OrderService.requireInvolved`）。只验令牌不够 —— 否则任何登录用户都能订阅别人的陪诊进度。
- 鉴权失败：401（令牌无效，前端走刷新）/ 403（非相关方，别白试），握手阶段直接拒，连接不建立。
- 身份存 `WebSocketSession.attributes`（握手在 A 线程、帧处理在 B 线程，`SecurityContextHolder` 早被清空）。
- 连接日志只记 `userId`/`role`/`orderId`，**不记 token**；反向代理 / 网关层需过滤 `token=` 参数，避免 token 进入访问日志。

---

## 5 · 验收清单

| # | 验收项 | 状态 |
|---|---|---|
| 1 | checkin / track / timeline / WS subscribe 四类接口可用 | ✅ |
| 2 | 距离校验（4001）+ 节点去重（4002） | ✅ `GeoUtil` + 已打卡最大 sort 判定 |
| 3 | 双写轨迹（`order_checkin` + `companion_track`） | ✅ |
| 4 | 6 节点不可回退、可跳过 | ✅ `CheckinNode` + `node_sort` 快照 |
| 5 | WebSocket 鉴权（ADR-0001） | ✅ 三件套 + 归属校验 |
| 6 | 30s 断网重连补齐 | ✅ 走 `/progress` + `/checkins`（**不是** `checkin-missed`，取舍理由见 §2） |
| 7 | 单测 / 越权 / e2e | ✅ `ExecutionServiceTest` 21 项 / `ExecutionAccessMatrixTest` 15 项 / `e2e_execution.py` |
| 8 | 并发压测脚本归档 | ✅ `jmeter_m4_accept.jmx`（抢单）+ `jmeter_m5_reconnect.jmx`（重连补齐，实测 300 样本 Err 0.00%） |

---

## 6 · 遗留与风险

- **握手后无法强制下线**：JWT 过期/拉黑/改密后已建立的 WS 连接不会主动断开，靠客户端心跳重连 + Hub 清理兜底（ADR-0001 已记录）。
- WS 重连压测（100 并发）未做，建议纳入 M12 待办。
