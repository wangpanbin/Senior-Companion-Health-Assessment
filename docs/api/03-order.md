# 03 陪诊订单

> 覆盖模块：**M4 陪诊订单与状态机**
> 负责人：B（后端主力）+ A（前端主力）
> 归属迭代：迭代二 · W5–W7（**W7 竞讲交付 v1.0**）
> 前置依赖：M3 用户与档案　全局约定见 [README.md](./README.md)
> 状态：**后端已实现并实测通过**（2026-09-15，单元测试 99 条 + 矩阵 51 条 + 端到端 78 条）

> ⚠️ **M4 是全局瓶颈模块**：M5 / M7 / M9 / M10 都依赖它。延期会连带拖垮后半程。

---

## 〇、本模块的安全边界（先读这一段）

M4 和 M3 一样，权限不是一个注解就能说完的，而是**三段式**：

| 层 | 由谁承担 | 回答的问题 | 失败码 |
|---|---|---|---|
| ① 认证 | `JwtAuthenticationFilter` | 你有没有登录 | HTTP `401` |
| ② 角色 | `@PreAuthorize` | 你是什么角色 | HTTP `403` |
| ③ **归属** | `OrderService.requireInvolved` 及各写方法内部判断 | **这一单跟不跟你有关** | `3004` / `4003` |

第 ③ 层是本模块真正的安全边界。`hasRole('FAMILY')` 对家属 A 和家属 B 一视同仁，
**在原理上无法表达**「这份订单是不是你的」，所以归属校验只能落在 Service 里。

> 📌 复用约定：M5（打卡 / 轨迹）、M7（评价）都要判断「这一单跟我有没有关系」，
> **一律复用 `OrderService#requireInvolved`**，不要各写一套 ——
> 三套归属判断里最松的那一套就是漏洞。

---

## 一、数据模型

### 1.1 状态枚举（`OrderStatus`）

| 传值 | 中文 | 排序 | 终态 |
|---|---|---|---|
| `PENDING` | 待接单 | 1 | |
| `ACCEPTED` | 已接单 | 2 | |
| `IN_SERVICE` | 服务中 | 3 | |
| `COMPLETED` | 已完成 | 4 | |
| `REVIEWED` | 已评价 | 5 | ✓ |
| `CANCELLED` | 已取消 | 9 | ✓ |

### 1.2 结算枚举（`PaymentStatus`）—— 一期不做在线支付

| 传值 | 中文 |
|---|---|
| `UNPAID` | 未结算 |
| `SETTLED` | 已结算 |

> 平台只做「线上记账 + 线下结算」，所以 `COMPLETED` + `UNPAID` 同时出现是**正常状态**，
> 不是 bug。**没有任何接口会因为 `UNPAID` 阻止后续流程**（例如评价）——
> 平台不碰钱，就不该拿钱当流程闸门。置为 `SETTLED` 的入口在 M9/M10。

### 1.3 OrderVO —— 三套口径，不是一个「字段齐全」的对象

| 字段 | 类型 | 我的订单 `ofList` | 大厅 `ofHall` | 详情 `ofDetail` |
|---|---|:--:|:--:|:--:|
| `id` | Long | ✓ | ✓ | ✓ |
| `orderNo` | String | ✓ | ✓ | ✓ |
| `elderName` | String | 脱敏 `张*海` | 脱敏 | **全名** |
| `elderAge` | Integer | ✓ | ✓ | ✓ |
| `hospital` / `department` | String | ✓ | ✓ | ✓ |
| `visitTime` | String | ✓ | ✓ | ✓ |
| `status` / `statusLabel` | String | ✓ | ✓ | ✓ |
| `fee` | String（两位小数） | ✓ | ✓ | ✓ |
| `createTime` | String | ✓ | ✓ | ✓ |
| `companionName` | String | 脱敏 | 脱敏 | **全名** |
| `address` | String | — | **✓** | ✓ |
| `remark` | String | — | — | ✓ |
| `elderId` / `familyId` / `companionId` | Long | — | — | ✓ |
| `paymentStatus` / `paymentStatusLabel` | String | — | — | ✓ |
| `actualFee` | BigDecimal | — | — | ✓ |
| `serviceSummary` | String | — | — | ✓ |
| `servicePhotos` | Array | — | — | ✓ |
| `acceptTime` / `startTime` / `finishTime` | String | — | — | ✓ |
| `cancelTime` / `cancelReason` | String | — | — | ✓ |
| ~~`version`~~ | Integer | **任何出口都不返回** | | |

**为什么大厅反而要返回地址**：陪诊员接单前必须知道「去哪儿」，否则列表对他毫无决策价值。
地址是**医院**地址而不是老人住址，本就是公开信息。
反观「我的订单」列表：家属在手机上划自己的订单，姓名全显没有必要，一屏就能被旁边的人看走。

**为什么详情页 `ofDetail` 反而给全名**（这一条是刻意为之，不是漏脱敏，改动前请先读完）：
能拿到详情的调用方必须已通过 `OrderService#requireInvolved` —— 即本人是该订单的家属 / 陪诊员 / 管理员，
否则直接 `3004`。对一个已经确认身份的相关方再脱敏，页面只会变成「张\*海 的订单」「李\*军的排班」，
该看的人看不到该看的信息；陪诊员上门对接、家属核对就诊人也会失去依据。
**脱敏的边界是「不相关的第三方」与「列表这类可能被旁人一眼扫到的场景」，不是「凡出现姓名就打码」。**
新增订单相关出口时按同一把尺子选口径：列表 / 大厅脱敏，详情（已过归属校验）给全名。

**为什么 `version` 不返回**：它是乐观锁内部字段。对外暴露只会诱导客户端自己传一个版本来「保证」接单成功，
而正确做法是让服务端读当前版本。数据库里 `version` 的变化由端到端脚本直接查库验证，不经过接口。

**为什么 `fee` 是字符串**：JS 的 `Number` 表示 `128.00` 会变成 `128`，
前端再格式化就有出现浮点误差的机会 —— 而对账场景下「差一分钱」是要被追问的。

---

## 二、状态机（本模块的核心）

```
   PENDING ──①──► ACCEPTED ──②──► IN_SERVICE ──③──► COMPLETED ──④──► REVIEWED
   待接单          已接单            服务中             已完成           已评价
      │              │                 │                 │              │
      │              │                 │                 │        （终态锁死）
      └──────────────┴─────────────────┴─────────────────┘
                              │ ⑤ 仅 ADMIN 纠纷处理（M9）
                              ▼
                          CANCELLED
                           已取消
```

| 编号 | 流转 | 触发接口 | 允许角色 | M4 状态 |
|---|---|---|---|---|
| ① | 待接单 → 已接单 | `POST /api/order/{id}/accept` | COMPANION（资质 APPROVED） | ✅ 已实现 |
| ② | 已接单 → 服务中 | `POST /api/order/{id}/start` | COMPANION（须为本单陪诊员） | ✅ 已实现 |
| ③ | 服务中 → 已完成 | `POST /api/order/{id}/complete` | COMPANION（须为本单陪诊员） | ✅ 已实现 |
| ④ | 已完成 → 已评价 | `POST /api/review` | FAMILY（须为本单下单人） | M7 |
| ⑤ | 待接单 / 已接单 / 服务中 / 已完成 → 已取消 **或** 已完成 | `POST /api/admin/order/{id}/arbitrate` | **仅 ADMIN** | M9 |
| 取消 | 待接单 → 已取消 | `PUT /api/order/{id}/cancel` | FAMILY（须为本单下单人） | ✅ 已实现 |

> ⑤ 的起点是 **`PENDING` / `ACCEPTED` / `IN_SERVICE` / `COMPLETED`**（即"任意非终态"），
> **不含** `已评价` / `已取消`——这两者为终态，调用 `arbitrate` 返回 `3002`。
> 终点可为 `CANCELLED` 或 `COMPLETED`（对应 `OrderStatus.isAdminForceable()`）。详见 `docs/api/08-admin.md` §9。

### 铁律（违反即返回 `3002`）

1. **禁止跳级**：`待接单` 不能直接变 `已完成`。
2. **禁止回退**：`已接单` 不能退回 `待接单`。
3. **终态锁死**：`已评价` / `已取消` 之后不可再变更——该锁对**管理员强制路径同样生效**（`OrderStatus.isTerminal()` 直接拒绝）。
4. **家属取消与管理员强制是两条不同路径**：家属只能取消 `待接单`；`已接单` 之后的取消只能由 ADMIN 走纠纷处理。
5. 每次流转写入 `order_status_log`，异常时整体回滚（`@Transactional(rollbackFor = Exception.class)`）。

> 状态判断**必须使用后端 `OrderStatus` 枚举**（`org.company.nianglin.constant`），禁止硬编码状态字符串。
> 正向流转走 `canTransitTo(...)`；**管理员强制终态是明确豁免的独立路径**
> （`forceTerminal()` + `isAdminForceable()` + `isTerminal()`），其边**不进入** `TRANSITIONS`。

### ⚠️ 判断顺序：先状态、后身份（改动前必读）

每个流转方法都是「**先判状态（`3002`）→ 再判身份（`4003` / `3004`）**」。

反过来写的话，对一张 `PENDING` 的单子调用「完成服务」会得到「您不是该订单的陪诊员」——
而待接单的订单根本没有陪诊员，这句话既没有信息量，
也把验收标准里的「跳级调用返回 3002」悄悄变成了 4003。
端到端用例 `D2` 专门锁死了这一点。

### 并发下的两种写入策略（验收项）

| 场景 | 写法 | 失败码 | 为什么 |
|---|---|---|---|
| **接单** | `updateById(entity)`，实体带 `@Version` | `3003` | 失败要区分「被谁抢先」 |
| 取消 / 开始 / 完成 | `UPDATE ... WHERE id=? AND status=期望状态 [AND companion_id=?]` | `3002` | 失败统一就是「状态不对」 |

两者都是**原子的**，都**不允许**退化成「先 SELECT 判断、再按 id 无条件 UPDATE」——
那在并发下必然写出错误状态。

> 注意：只有**接单**会给 `version` +1。开始服务 / 完成服务走条件更新，**不碰 `version`**。
> 所以一张完整走完的单子 `version` 停在 `1`（不是 3）。两种写法都安全，区别只在于要不要区分被谁抢先。

---

## 三、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/api/order` | FAMILY | 创建订单 |
| 2 | GET | `/api/order` | 已登录 | 我的订单列表（按角色自动区分范围） |
| 3 | GET | `/api/order/hall` | COMPANION | 待接单订单大厅（需资质 `APPROVED`） |
| 4 | GET | `/api/order/{id}` | 已登录（须为相关方） | 订单详情 |
| 5 | PUT | `/api/order/{id}/cancel` | FAMILY（本单下单人） | 取消订单 |
| 6 | POST | `/api/order/{id}/accept` | COMPANION（资质 `APPROVED`） | 接单（乐观锁防超卖） |
| 7 | POST | `/api/order/{id}/reject` | COMPANION（资质 `APPROVED`） | 拒单 |
| 8 | POST | `/api/order/{id}/start` | COMPANION（本单陪诊员） | 开始服务 |
| 9 | POST | `/api/order/{id}/complete` | COMPANION（本单陪诊员） | 完成服务（含合规校验） |
| 10 | GET | `/api/order/{id}/timeline` | 已登录（须为相关方） | 状态流转时间线 |

> **路径顺序**：`/hall` 必须注册在 `/{id}` 之前，否则 `hall` 会被当成订单 ID 解析。

---

## 1. 创建订单

`POST /api/order`　权限：**FAMILY**（ELDER / COMPANION / ADMIN 调用返回 403）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `elderId` | Long | ✓ | **须为当前家属绑定的老人**（`elder_profile.id`，不是 `user_id`） | 就诊老人 |
| `hospital` | String | ✓ | ≤ 100 字符 | 医院名称 |
| `department` | String | ✓ | ≤ 50 字符 | 科室 |
| `visitTime` | String | ✓ | `yyyy-MM-dd HH:mm:ss`，**必须晚于当前时间** | 就诊时间 |
| `address` | String | ✓ | ≤ 200 字符 | 医院地址（文字描述） |
| `remark` | String | 否 | ≤ 500 字符 | 备注 |
| `fee` | BigDecimal | 否 | ≥ 0，最多两位小数 | 服务费，不传则由后端按规则计算 |

**刻意没有 `familyId`，也没有 `status`**：下单人一律取自令牌，初始状态由服务端写死为 `PENDING`。
把「谁是下单人」交给前端决定，等于允许家属以别人名义下单；
把「初始状态」交给前端决定，等于允许直接下一笔「已完成」的订单去刷单。

```json
{
  "elderId": 401,
  "hospital": "海南省人民医院",
  "department": "心血管内科",
  "visitTime": "2026-09-20 09:30:00",
  "address": "海南省海口市秀英区秀华路19号 门诊大楼3楼",
  "remark": "老人听力不好，请大声沟通；需协助取药"
}
```

### 响应

```json
{
  "code": 200,
  "message": "下单成功，正在为您匹配陪诊员",
  "data": {
    "orderId": 1065,
    "orderNo": "NL20260915000001",
    "status": "PENDING",
    "statusLabel": "待接单"
  }
}
```

> 只回 4 个字段：前端下单成功后要做的只是「把订单号告诉用户 + 跳详情」。
> 也刻意不回陪诊员信息 —— 刚下单时本来就没有人接单，
> 返回一个 `null` 字段不如让它直接不存在。

### 错误场景

| code | 场景 |
|---|---|
| 400 | 参数校验失败（缺老人 / 医院 / 科室 / 时间 / 地址） |
| 2001 | `elderId` 对应的档案不存在 |
| 2006 | 该老人不在当前家属名下（**归属校验**） |
| 3005 | 就诊时间早于当前时间 |
| 403 | 非 FAMILY 角色 / ELDER 老人只读拦截 |

### 实现要点

- **订单号**：`NL` + `yyyyMMdd` + 6 位当日序列（如 `NL20260915000001`）。
  用 Redis `INCR` 而不是「查当天最大订单号 + 1」：后者在并发下两个请求会读到同一个最大值，
  然后一起撞上 `uk_order_no` 唯一索引 —— 下单接口在高峰期随机失败，且失败原因对用户完全不可理解。
  序列 key `order:seq:{yyyyMMdd}` 按天分桶并设 2 天 TTL，跨零点自动从 1 重新开始，不需要清理任务。
- **服务费规则**（`fee` 不传时）：工作日 08:00–18:00 收 `128.00`；
  **夜间（18 点后或 8 点前）或周末加 `30.00`** → `158.00`。
  规则写在后端而不是前端：价格若由前端决定，改价就得等前端发版，而且用户改一个请求体就能改价。
- 初始 `version = 0`，`payment_status = 'UNPAID'`，`arbitrate_flag = 0`。
- 创建成功写一条 `order_status_log`（`null → PENDING`，备注「下单成功」）。
- **未实现**：向陪诊员推送站内信属于 M8，M4 不含。

---

## 2. 我的订单列表

`GET /api/order`　权限：已登录

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Long | 否 | 分页，默认 1 / 10，`size` 上限 100 |
| `status` | String | 否 | 状态枚举名，可多值逗号分隔，如 `PENDING,ACCEPTED` |
| `startDate` / `endDate` | String | 否 | **下单日期**（`create_time`）闭区间，`yyyy-MM-dd` |
| `elderId` | Long | 否 | 按老人筛选，**仅在当前用户可见范围内缩小结果集** |
| `keyword` | String | 否 | 订单号 / 医院名称模糊搜索 |
| `sortField` | String | 否 | 白名单：`visit_time` / `fee` / `status` / `id` |
| `sortOrder` | String | 否 | `asc` / `desc`，默认 `desc` |

> **按角色自动区分数据范围**：
> - FAMILY → 自己下的单（`family_id = 当前用户`）
> - COMPANION → 自己接的单（`companion_id = 当前用户`）
> - ELDER → 自己作为就诊人的订单（经 `elder_profile.user_id` 关联，**只读**）
> - ADMIN → 全部订单（但建议用 M9 的 `/api/admin/order`）
>
> 参数里**没有** `familyId` / `companionId`：「查谁的订单」由令牌决定，传了也不认。
> `elderId` 是例外 —— 它是**范围内筛选**而不是范围本身：去掉它，结果依然是安全的。

**`status` 取值非法直接 400，而不是忽略**：前端传了 `PENDING` 拼错成 `PENDNG`
却拿到全量列表，会以为筛选生效了，然后拿这份数据做出错误的产品判断。

**排序字段不在白名单内不报错、直接回落默认排序**：这里控制的是「列表怎么排」，
不是「能不能拿到数据」，为此让整个列表 400 对用户是莫名其妙的失败。

### 响应（`ofList` 口径：无地址、无备注、姓名脱敏）

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 12,
    "page": 1,
    "size": 10,
    "pages": 2,
    "records": [
      {
        "id": 1065,
        "orderNo": "NL20260915000001",
        "elderName": "张*海",
        "elderAge": 78,
        "hospital": "海南省人民医院",
        "department": "心血管内科",
        "visitTime": "2026-09-17 10:00:00",
        "status": "PENDING",
        "statusLabel": "待接单",
        "fee": "128.00",
        "createTime": "2026-09-15 23:26:53"
      }
    ]
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 400 | `status` 取值非法 / 日期格式非法 / 开始日期晚于结束日期 |
| 401 | 未登录 |

---

## 3. 待接单订单大厅

`GET /api/order/hall`　权限：**COMPANION**（且 `companion_profile.audit_status = 'APPROVED'`）

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Long | 否 | 分页 |
| `area` | String | 否 | **对医院地址文本做模糊匹配**（不是行政区划筛选） |
| `hospital` | String | 否 | 医院关键字模糊匹配 |
| `startDate` / `endDate` | String | 否 | **就诊日期**（`visit_time`）区间，`yyyy-MM-dd` |

### 响应

结构同「我的订单」，但 `records` 中**额外返回 `address`**（陪诊员必须先知道去哪儿）。

### 错误场景

| code | 场景 |
|---|---|
| 2003 | 陪诊员资质尚未通过审核（含「没有资质快照行」的情况） |
| 403 | 非 COMPANION 角色调用 |

### 实现要点

- **双重拦截**：角色为 COMPANION 由注解保证，**资质已审核只能在 Service 里查**
  （`@PreAuthorize` 表达不了「查库确认资质状态」）。两者缺一不可。
- 只返回 `status = 'PENDING'` **且 `visit_time > now()`** 的订单 ——
  就诊时间已过的单子不再挂在大厅上：家属没取消，但陪诊员也接不了了。
- **排除自己已拒过的单**（`not in (select order_id from order_reject_log where companion_id = 我)`）。
- 排序固定为 `visit_time asc, id asc`（陪诊员按住哪天有空来接单）。

---

## 4. 订单详情

`GET /api/order/{id}`　权限：已登录，且须为该订单的**相关方**

### 相关方判定逻辑（`requireInvolved`）

```
当前角色 == ADMIN                              → 允许
当前用户 ID == order.familyId（下单家属）        → 允许
当前用户 ID == order.companionId（接单陪诊员）    → 允许
当前角色 == ELDER 且 order.elderId 的档案 owner 是当前用户 → 允许（只读）
其余                                           → 3004
```

> ⚠️ 常见误解：老人的判定**不是**比较 `order.elderId == 当前用户 ID`。
> `order.elderId` 是 `elder_profile.id`（如 401），而登录用户 ID 是 `sys_user.id`（如 201）。
> 必须先由档案取 `elder_profile.user_id` 再比较。

### 响应（`ofDetail` 口径：全名、地址、备注、结算、服务记录、各节点时间）

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 1065,
    "orderNo": "NL20260915000001",
    "elderId": 401,
    "elderName": "张德海",
    "elderAge": 78,
    "familyId": 101,
    "hospital": "海南省人民医院",
    "department": "心血管内科",
    "visitTime": "2026-09-17 10:00:00",
    "address": "海南省海口市秀英区秀华路19号",
    "status": "PENDING",
    "statusLabel": "待接单",
    "fee": "128.00",
    "paymentStatus": "UNPAID",
    "paymentStatusLabel": "未结算",
    "createTime": "2026-09-15 23:26:53"
  }
}
```

未接单时 `companionId` / `companionName` 等字段**直接消失**（全局 Jackson `non_null` 策略）。

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3004 | 无权操作该订单（不是任何意义上的相关方） |

---

## 5. 取消订单

`PUT /api/order/{id}/cancel`　权限：FAMILY（须为本单下单人）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `reason` | String | ✓ | ≤ 200 字符 | 取消原因 |

> 取消原因**必填**：这是唯一一处家属可以单方面终止订单的入口，
> 原因会写进取消记录并出现在双方可见的时间线里。留空的话，
> 陪诊员只看到「订单被取消了」却不知道为什么，纠纷处理时也没有依据。

### 响应

```json
{ "code": 200, "message": "订单已取消", "data": null }
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3004 | 不是本单下单家属 |
| 3006 | 当前状态不允许取消（仅「待接单」可取消） |
| 400 | `reason` 留空 |

### 实现要点

- 只有 `PENDING` 可取消。已接单的订单需取消必须走 ADMIN 纠纷处理（M9）——
  陪诊员可能已经在路上，单方面取消会让人白跑。
- 实现用条件更新 `WHERE id=? AND status='PENDING'`，并发重复取消时只有一条能成功。
- 落库字段：`status='CANCELLED'`、`cancel_time`、`cancel_reason`、`cancel_by`。
- **未实现**：取消后站内信通知属于 M8。

---

## 6. 接单（乐观锁防超卖）—— **M4 最重要的验收点**

`POST /api/order/{id}/accept`　权限：COMPANION（且资质 `APPROVED`）

### 请求参数

无（陪诊员 ID 从令牌取）。

### 响应（成功）

```json
{
  "code": 200,
  "message": "接单成功",
  "data": {
    "orderId": 1065,
    "status": "ACCEPTED",
    "statusLabel": "已接单",
    "acceptTime": "2026-09-15 23:26:53"
  }
}
```

### 响应（被抢单）

```json
{
  "code": 3003,
  "message": "手慢了，该订单已被其他陪诊员接单",
  "data": null
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3002 | 当前状态不是 `PENDING`（可能已被别人接走） |
| 3003 | 乐观锁未命中（读到了 `PENDING`，但写的时候 `version` 已变） |
| 2003 | 陪诊员资质未通过审核 |
| 403 | 非 COMPANION 角色 |

### 实现要点

实体上标注 `@Version`（`MybatisPlusConfig` 已注册 `OptimisticLockerInnerInterceptor`），
调 `updateById(entity)`，MyBatis-Plus 生成：

```sql
UPDATE companion_order
SET status = 'ACCEPTED',
    companion_id = ?,
    accept_time = ?,
    version = version + 1
WHERE id = ?
  AND version = ?;      -- 读取时的旧版本号
-- affectedRows == 0 → 说明被抢先，返回 3003
```

- **不要用「先 SELECT 再 UPDATE」的写法**，那样在并发下必然超卖。
- 整个方法加 `@Transactional(rollbackFor = Exception.class)`。
- **实测结论**：50 个并发请求抢同一订单 →
  仅 1 条成功，其余返回 `3002`（读到时已非 PENDING）或 `3003`（乐观锁未命中），
  数据库 `version` 恰好为 `1`，`ACCEPTED` 状态日志恰好 1 条。

---

## 7. 拒单

`POST /api/order/{id}/reject`　权限：COMPANION（且资质 `APPROVED`）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `reason` | String | ✓ | ≤ 200 字符 | 拒单原因 |

### 响应

```json
{ "code": 200, "message": "已拒单", "data": null }
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3002 | 订单不是 `PENDING`（已被接走或已取消，拒单没有意义） |
| 409 | 该陪诊员已经拒过这一单 |
| 2003 | 资质未通过 |
| 400 | `reason` 留空 |

### 实现要点

- 拒单**不改变订单状态**（仍是 `PENDING`），只是记录一条 `order_reject_log`，
  让该订单不再出现在**这位**陪诊员的大厅里，其他陪诊员照常可见。
- **刻意不写 `order_status_log`**：拒单没有改变订单状态，
  往状态日志里塞一条「状态没变」的记录会把时间线搞乱。
- `order_reject_log` 上有唯一索引 `uk_order_companion`；先查一次是为了给出
  「您已经拒过该订单了」这样的人话提示，而不是让用户看到一个数据库约束异常。
- 一期**没有**「拒单次数上限」或「拒单率风控」的自动处置，只记录数据供运营观察（M9）。

---

## 8. 开始服务

`POST /api/order/{id}/start`　权限：COMPANION（须为本单陪诊员）

### 响应

```json
{
  "code": 200,
  "message": "已开始服务",
  "data": { "status": "IN_SERVICE", "statusLabel": "服务中", "startTime": "2026-09-17 09:10:00" }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3002 | 当前状态不是 `ACCEPTED` |
| 4003 | 您不是该订单的陪诊员 |
| 2003 | 资质未通过 |

### 实现要点

- 条件更新 `WHERE id=? AND status='ACCEPTED' AND companion_id=?`，原子生效。
- **不修改 `version`**（条件更新已保证原子性，且这里的失败不需要区分「被谁抢先」）。
- **未实现**：文档早期设想的「先打过 `DEPART`/`ARRIVE` 打卡才允许开始服务」属于 M5，
  M4 刻意不依赖 M5，以免把两个模块的交付绑死。

---

## 9. 完成服务

`POST /api/order/{id}/complete`　权限：COMPANION（须为本单陪诊员）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `summary` | String | 否 | ≤ 500 字符 | 服务小结（**只记录过程，不含诊断与用药建议**） |
| `photos` | Array | 否 | ≤ 6 个，每项须为合法 URL | 取药凭证 / 现场照片 URL 列表 |
| `fee` | BigDecimal | 否 | ≥ 0，最多两位小数 | 实际结算服务费（线下结算记录） |

三个字段都可空：陪诊员可能只想先把订单走完、事后补填记录。但一旦填写，就必须合法。

### 合规红线（`3007`）

`summary` 命中「诊断 / 处方 / 用药建议」类表述时**直接拒绝入库**，并把命中的词回给用户：

```
服务小结不能包含「建议服用」这类诊断或用药建议，请改为记录过程
```

词表分三类：诊断结论（`诊断` `确诊` `疑似` `病情判断`）、
开药动作（`处方` `开具` `开药` `配药` `建议服用` `建议使用`）、
用药调整（`换药` `停药` `加量` `减量` `剂量` `服用方法` `用药方案`）。

- 刻意**不含**「药」「医院」「治疗」这类过于宽泛的词 —— 「已协助取药」是完全合法的过程记录。
- 是「命中即拒」而不是「打码后放行」：打码会把「建议服用阿司匹林」变成「建议**阿司匹林」，
  读者仍能猜出原意，拦不住风险却增加维护成本。
- 词表**宁可误拒**：被拒时用户能看到具体命中的词，改写成本很低；
  而漏放一条「建议加量」的代价无法挽回。M7 的评价内容校验应**复用同一个词表**，不要另起一份。

### 响应

```json
{
  "code": 200,
  "message": "服务已完成",
  "data": {
    "status": "COMPLETED",
    "statusLabel": "已完成",
    "finishTime": "2026-09-17 12:05:00",
    "paymentStatus": "UNPAID",
    "paymentStatusLabel": "未结算"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 3001 | 订单不存在 |
| 3002 | 当前状态不是 `IN_SERVICE` |
| 3007 | 服务小结命中诊断 / 用药建议词 |
| 4003 | 您不是该订单的陪诊员 |
| 400 | 参数校验失败（照片超 6 张 / 金额为负 / 照片 URL 非法） |

### 实现要点

- 完成后**不自动变成 `REVIEWED`**，需家属提交评价后由 M7 触发 `COMPLETED → REVIEWED`。
- 完成后**不自动变成 `SETTLED`**：一期线下结算，`paymentStatus` 如实读库返回，
  不写死 `UNPAID` —— 否则 M9 支持线下回填后写死就是一句谎话。
- `photos` 为空列表时按「没填」处理，存 `null` 而不是 `[]`。
- **未实现**：完成后的站内信 / WebSocket 推送属于 M8 / M5。

---

## 10. 状态流转时间线

`GET /api/order/{id}/timeline`　权限：已登录，且须为该订单相关方

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "status": "PENDING",
      "statusLabel": "待接单",
      "operatorName": "张伟",
      "operatorRole": "FAMILY",
      "remark": "下单成功",
      "operateTime": "2026-09-15 23:26:53"
    },
    {
      "status": "ACCEPTED",
      "statusLabel": "已接单",
      "operatorName": "李建军",
      "operatorRole": "COMPANION",
      "remark": "已接单",
      "operateTime": "2026-09-15 23:26:53"
    }
  ]
}
```

### 实现要点

- 数据来源 `order_status_log`，按 `operate_time asc, id asc` 排序。
- **`operatorName` 是写入时定格的快照**，不是实时 JOIN `sys_user`。
  实时查询有两个问题：一是「老婆改过昵称之后，三个月前那笔订单的操作人变成了新昵称」，
  历史记录就不成其为历史；二是操作人账号被删（或陪诊员资质被清）之后，时间线上会出现空白节点。
  陪诊员取资质快照里的真实姓名，其余取昵称，都没有再退到用户名。
- **`fromStatus` 刻意不下发**：前端渲染时间线只需要「这一步是什么状态、谁在什么时候做的、备注是什么」，
  上一步是什么状态是由上一条记录表达出来的。多下发一个字段就等于多一个前端可能渲染错的地方。
- 打卡节点（M5）计划合并进时间线，前端统一渲染。

---

## 四、验收标准（M4）

> 这是 M4 的竞讲交付清单（W7），逐条必须可复现。

| # | 验收项 | 状态 | 验证方式 |
|---|---|:--:|---|
| 1 | **50 并发同时接同一订单 → 只有 1 条成功，`version` 仅 +1** | ✅ | `e2e_order.py` §H（实测 ok=1，`version=1`，接单日志 1 条） |
| 2 | 跳级调用「待接单 → 已完成」→ `3002`，数据库状态不变 | ✅ | `e2e_order.py` D2 / `OrderAccessMatrixTest` 「跳级」用例 |
| 3 | 「已接单」的订单再次调用接单 → `3002` | ✅ | `e2e_order.py` E3 / 矩阵「终态不可回退」 |
| 4 | 非订单归属陪诊员调用「开始服务」→ `4003` | ✅ | `e2e_order.py` E4 / 矩阵 `notAssignedCompanionStartShouldReturn4003` |
| 5 | 分页查询 `page=1&size=10&status=PENDING` 条数与手工核对一致 | ✅ | `e2e_order.py` B12（返回值逐条校验状态） |
| 6 | 状态变更接口抛异常时事务回滚，订单状态与 `order_status_log` 保持一致 | ✅ | `e2e_order.py` E7（合规拒绝后状态仍为 `IN_SERVICE`）、G2（越权取消未落库） |
| 7 | FAMILY 下单 → COMPANION 接单 → 开始 → 完成，全流程可跑通 | ✅ | `e2e_order.py` A1 → E1 → E5 → E8，E13 校验 4 段状态日志 |
| 8 | ELDER 账号调用创建订单接口 → `403` | ✅ | `e2e_order.py` I1 / 矩阵「老人只读」参数化用例 |
| 9 | `/doc.html` 中订单模块 10 个接口齐全，含请求 / 响应示例 | ✅ | `OrderController` 10 个方法均带 `@Operation` |
| 10 | 就诊时间早于当前时间 → `3005` | ✅ | `e2e_order.py` A7 / 矩阵 `createWithPastVisitTimeShouldReturn3005` |
| 11 | 服务小结含诊断 / 用药建议 → `3007`，且状态不变 | ✅ | `e2e_order.py` E6 + E7 |
| 12 | 拒单不改变订单状态，且不再出现在该陪诊员的大厅 | ✅ | `e2e_order.py` F2 / F5 / F6 |
| 13 | 非相关方读详情 / 时间线 → `3004` | ✅ | `e2e_order.py` B5 / B6 / B8 / B12 / B15 |
| 14 | 列表不泄露地址、备注与内部 ID | ✅ | `e2e_order.py` B11 |

### 实测记录（2026-09-15）

| 层 | 命令 | 结果 |
|---|---|---|
| 单元测试（Mockito） | `mvn test -Dtest=OrderServiceTest` | **48/48** |
| 全量测试 | `mvn test` | **243/243，BUILD SUCCESS** |
| 归属 / 状态机矩阵 | `OrderAccessMatrixTest`（真实 JWT + 真库） | **51/51** |
| 端到端（真实 HTTP 打 8080） | `python backend/sql/tools/e2e_order.py` | **78/78** |
| 回归 · M2 | `python backend/sql/tools/e2e_auth.py` | **40/40** |
| 回归 · M3 | `python backend/sql/tools/e2e_user_profile.py` | **86/86** |

---

## 五、已知限制（一期）

1. **大厅的 `area` 是地址文本模糊匹配，不是地理筛选**。
   订单表只有家属填写的医院地址文本，没有「服务区域」字段。陪诊员输入「美兰区」能否筛到，
   依赖家属填地址时写全了。这是一期用文字地址替代地图导航的直接后果，不假装它是准确的地理筛选。
2. **管理员强制改终态未实现**（M9）。因此「已接单之后家属想取消」目前无解，
   返回 `3006` 并提示走纠纷处理，但纠纷处理入口在 M9 才交付。
3. **没有自动取消过期订单的任务**。就诊时间已过的 `PENDING` 单只是从大厅消失，
   状态仍停在 `PENDING`，需要 M9 的定时任务或管理员介入。
4. **拒单无风控**：不限制拒单次数、不计算拒单率，只记录数据。
5. **站内信 / WebSocket 推送未实现**（M8 / M5）：下单、接单、完成都不会触发任何通知。
6. **`version` 只在接单时递增**。开始服务 / 完成服务用条件更新，不改 `version`。
   若将来把这两个流转也改成 `updateById`，`version` 会变成 3 ——
   端到端用例 `E10` 会因此失败，那时需要**有意**更新断言，而不是让它悄悄变化。
7. **取消 / 纠纷原因只做长度校验，不做内容合规校验**。合规词表目前只用在服务小结上。

---

## 六、变更记录

| 版本 | 日期 | 变更内容 | 变更人 |
|---|---|---|---|
| v0.1.0 | 2026-09-15 | 骨架阶段初版：接口与状态机设计 | — |
| v1.0.0 | 2026-09-15 | 按 M4 实测口径重写：三套 VO 口径表、安全边界三段式、判断顺序说明、两种并发写入策略、`version` 不对外返回、拒单单列一节、已知限制汇总、验收打勾与实测记录 | — |
