# 前后端联调契约（前端页面接入真实接口 · 唯一真源）

> 本文件是「银龄伴诊」前端页面从假数据切换到真实接口时的**唯一事实来源**。
> 写任何页面之前先读完本文件；本文件与本仓库后端代码冲突时，**以后端代码为准**，并回来修正本文件。
>
> 后端已全部交付（M0–M10 已 commit，`mvn verify` 511 测试全绿）。
> 前端骨架已按 design.md 完成视觉，但**除登录/注册/下单三步/订单列表/订单详情外，其余页面仍是硬编码假数据**。
> 本轮任务就是把这些页面接到真实接口上。

---

## 0 · 铁律（违反即返工）

1. **禁止修改共享文件。** 以下文件由负责人统一维护，worker 一律**只读不写**：
   - `frontend/src/api/*.js`（接口封装层）
   - `frontend/src/utils/*.js`（`request.js` / `format.js` / `realtime.js` / `auth.js`）
   - `frontend/src/components/**`（组件库）
   - `frontend/src/store/*`、`frontend/src/router/*`、`frontend/vite.config.js`
   - 后端一切文件
   **缺接口 / 缺工具函数 → 在汇报里写明「需要新增 XXX，签名建议 XXX」，不要自己加。**
   理由：多个 worker 并行改同一文件会静默丢改动（本仓库踩过这个坑）。
2. **只改分配给你的 `.vue` 文件。** 不要顺手重构别人的页面。
3. **不许留假数据。** 交付标准是「页面上的每个数字都能在数据库里找到出处」。
   删掉 `mockXxx` / `const list = [{...}]` 之类的硬编码数组；确实没有后端来源的字段，
   **整个 UI 块删掉**，不要编一个数字糊上去。
4. **不要跑 `npm run build` / `npm run dev` / `mvn`。** dev server 已在跑（5173），Vite 会热更新；
   你的改动是否语法正确，由 `node --check` 无法验证 `.vue`，所以**写完后必须自己 Read 一遍确认括号闭合、模板标签成对**。
   真有编译错误，负责人会统一收口。
5. **不要执行 git 命令**，不要 commit。负责人统一提交。
6. **Shell 一律用 PowerShell**（本机 Git Bash 的 PATH 是坏的，`ls/cat/grep` 全部 not found）。
   PowerShell 的 stdout 经常被吞，**结果写文件再读**。

---

## 1 · 数据契约（最常踩，先看这里）

### 1.1 `@/utils/request` 的行为

```js
import { listMyOrders } from '@/api/order'
const data = await listMyOrders({ page: 1, size: 20 })   // 直接拿到 data，不是 AxiosResponse
```

- 成功（`code === 200`）→ **拦截器直接返回 `body.data`**，调用方拿到的是业务数据本身。
- 业务失败 → 拦截器**已经弹过 `ElMessage.error`** 并 reject。所以页面里 `catch` 里**不要再弹一次错误提示**，
  只需负责把 `loading` 关掉、把列表置空。**重复弹提示 = 双份报错**。
- HTTP 401 → 拦截器自动尝试 `refresh` 换新 token 并重放；刷新失败才登出。页面**不要**自己处理 401。
- Blob 下载（导出）→ 响应体没有 `code` 字段，拦截器**原样透传**，拿到的是 `Blob`。
- `skipAuth: true` 的接口（验证码/登录/注册/刷新）不带 token。

### 1.2 分页

- **查询参数是 `page` / `size`**，不是 `pageNum` / `pageSize`。从 1 开始。
- **`size` 上限 100**，传 200 会被后端参数校验打回 400。
- **响应结构**（后端 `PageResult`）：

```json
{ "total": 137, "page": 1, "size": 20, "pages": 7, "records": [ ... ] }
```

页面统一写 `data?.records || []`、`data?.total || 0`。

### 1.3 空字段会**整个消失**（重要）

后端 Jackson 全局配了 `NON_NULL`。所以：

- `{"rejectReason": null}` 会变成 `{}` —— 前端 `obj.rejectReason` 是 `undefined`，
  模板里 `v-if` / `??` / 可选链必须能扛住 `undefined`。
- **不要写 `res.field.length`**、`res.field.toFixed(2)` 这类会炸的表达式。
- 判断「有没有值」用 `!= null`（同时排除 `null` 与 `undefined`），不要用 `if (v)` 去判断数字 0 或空串。

### 1.4 时间与金额格式

后端出参统一：

| 类型 | 格式 | 示例 |
|---|---|---|
| `LocalDateTime` | `yyyy-MM-dd HH:mm:ss` | `"2026-09-16 09:30:00"` |
| `LocalDate` | `yyyy-MM-dd` | `"2026-09-16"` |
| 金额 | 两位小数字符串 | `"128.00"` |

**必须用 `@/utils/format` 里的函数，禁止 `new Date('2026-09-16 09:30:00')`** ——
这种带空格的格式在 Safari / 旧 iOS 上返回 `Invalid Date`，而本项目目标用户大量用 iOS，
`Invalid Date` 会直接显示到老人脸上。

```js
import {
  formatDateTime, formatDate, formatTime, formatVisitTime, formatMoney,
  today, addDays, lastNDays, now, labelOf,
  ORDER_STATUS_TEXT, CHECKIN_NODE_TEXT, CHECKIN_NODE_ORDER, PAYMENT_STATUS_TEXT,
  MEAL_RELATION_TEXT, AUDIT_STATUS_TEXT, COMPLAINT_STATUS_TEXT,
  MEDICATION_PLAN_STATUS_TEXT, MEDICATION_TASK_STATUS_TEXT,
  BIND_STATUS_TEXT, ACCOUNT_STATUS_TEXT, RELATION_TEXT, GENDER_TEXT, MOBILITY_TEXT
} from '@/utils/format'
```

- `formatDateTime('2026-09-16 09:30:00')` → `"09-16 09:30"`
- `formatDate(...)` → `"2026-09-16"`
- `formatMoney('128.00')` → `"¥128.00"`；`formatMoney('128.00', false)` → `"128.00"`
- `today()` → `"2026-09-16"`；`lastNDays(7)` → `{ startDate, endDate }`；`now()` → `"2026-09-16 11:58:00"`

---

## 2 · 枚举真值表（**别猜，照抄**）

后端唯一真源是 `org.company.nianglin.constant` 下的枚举类。以下为核对结果：

| 域 | 取值 | 中文 |
|---|---|---|
| 订单状态 `OrderStatus` | `PENDING` / `ACCEPTED` / `IN_SERVICE` / `COMPLETED` / `REVIEWED` / `CANCELLED` | 待接单 / 已接单 / 服务中 / 已完成 / 已评价 / 已取消 |
| 打卡节点 | `DEPART` / `ARRIVE` / `IN_CONSULT` / `TAKE_MEDICINE` / `LEAVE` / `FINISH` | 出发 / 到院 / 就诊中 / 取药 / 离院 / 完成 |
| 结算状态 `PaymentStatus` | `UNPAID` / `SETTLED` | 未结算 / **已结算** |
| 资质审核 `AuditStatus` | `PENDING` / `APPROVED` / `REJECTED` | 待审核 / 已通过 / 已驳回 |
| 投诉 `ComplaintStatus` | `PENDING` / `PROCESSING` / `RESOLVED` / `REJECTED` | 待处理 / 处理中 / **已结案** / 已驳回 |
| 服药任务 | `PENDING` / `TAKEN` / `MISSED` | 待服 / 已服 / 漏服 |
| 用药计划 | `ACTIVE` / `DISABLED` | 进行中 / 已停用 |
| 账号状态 | `NORMAL` / `DISABLED` | 正常 / 已封禁 |
| 绑定状态 | `BOUND` / `UNBOUND` | 已绑定 / 未绑定 |
| 服药时间关系 | `BEFORE_MEAL` / `AFTER_MEAL` / `ANY` | 饭前 / 饭后 / 不限 |
| 性别 | `MALE` / `FEMALE` | 男 / 女 |
| 行动能力 | `SELF` / `ASSIST` / `WHEELCHAIR` | 可自理 / 需搀扶 / 需轮椅 |
| 亲属关系 | `SON` / `DAUGHTER` / `RELATIVE` / `OTHER` | 儿子 / 女儿 / 亲属 / 其他 |
| 角色 | `ELDER` / `FAMILY` / `COMPANION` / `ADMIN` | — |

**三个高频错误（骨架里原本就写错了，别再犯）：**
- 结算状态是 **`SETTLED`，不是 `PAID`**。
- 投诉终态是 **`RESOLVED`，不是 `CLOSED`**。
- 账号状态是 `NORMAL`，**没有 `ACTIVE` / `BANNED`**。

**展示状态一律优先用后端下发的 `xxxLabel` 字段**（如 `statusLabel`、`auditStatusLabel`），
本地枚举表只做兜底。组件 `<NlStatusChip>` 已内置分域映射，用法：

```vue
<NlStatusChip status="PENDING" />                      <!-- 域=order：待接单 -->
<NlStatusChip scope="audit"     status="PENDING" />    <!-- 待审核 -->
<NlStatusChip scope="complaint" status="RESOLVED" />   <!-- 已结案 -->
<NlStatusChip scope="task"      status="TAKEN" />      <!-- 已服 -->
<NlStatusChip scope="plan"      status="ACTIVE" />     <!-- 进行中 -->
<NlStatusChip scope="user"      status="NORMAL" />     <!-- 正常 -->
<NlStatusChip scope="payment"   status="SETTLED" />    <!-- 已结算 -->
<NlStatusChip :text="o.statusLabel" :status="o.status" /> <!-- 已有后端 label 时这样用 -->
```
props：`status`（枚举值）、`scope`（域，默认 `order`）、`text`（自定义文本，优先级最高）、`tone`、`dot`。

---

## 3 · 接口清单（前端已封装，直接 import 用）

`@/api` 统一出口按域导出：`healthApi authApi userApi orderApi executionApi
medicationApi reviewApi messageApi adminApi statisticsApi`。
**也可以直接从具体文件 import**（现有页面都是这么写的，推荐）：

```js
import { listMyOrders, getOrder } from '@/api/order'
```

### `@/api/auth`
| 函数 | 端点 | 说明 |
|---|---|---|
| `getCaptcha()` | `GET /auth/captcha` | → `{ captchaKey, captchaImage }`，验证码**必填** |
| `register(data)` | `POST /auth/register` | → `{ userId }`，**不返回 token**，注册后要去登录页 |
| `login(data)` | `POST /auth/login` | → `{ accessToken, refreshToken, expiresIn, userInfo }` |
| `refreshToken(data)` | `POST /auth/refresh` | — |
| `logout(refreshToken)` | `POST /auth/logout` | — |
| `getCurrentUser()` | `GET /auth/me` | → `UserInfoVO` |
| `changePassword(data)` | `PUT /auth/password` | 改密码（已登录） |

### `@/api/user`
| 函数 | 端点 | 说明 |
|---|---|---|
| `getProfile()` | `GET /user/profile` | 当前用户资料 |
| `updateProfile(data)` | `PUT /user/profile` | — |
| `applyCompanion(data)` | `POST /user/companion/apply` | 提交资质申请 |
| `getMyCompanionApplication()` | `GET /user/companion/application` | 我的资质申请状态 |
| `getCompanionProfile(id)` | `GET /user/companion/{id}` | 陪诊员公开资料（评分、接单数） |
| `listElder(params)` | `GET /user/elder` | 我绑定的老人列表（分页） |
| `createElder(data)` | `POST /user/elder` | 新建老人档案 |
| `getElder(id)` | `GET /user/elder/{id}` | — |
| `updateElder(id, data)` | `PUT /user/elder/{id}` | — |
| `removeElder(id)` | `DELETE /user/elder/{id}` | — |
| `bindElder(data)` | `POST /user/elder/bind` | 绑定老人 |
| `unbindElder(id)` | `DELETE /user/elder/{id}/bind` | 解绑 |

### `@/api/order`
| 函数 | 端点 | 说明 |
|---|---|---|
| `createOrder(data)` | `POST /order` | 下单（`fee` 由后端算，**不要传**） |
| `listMyOrders(params)` | `GET /order` | 按当前角色自动区分（家属看自己下的 / 陪诊员看接的） |
| `listOrderHall(params)` | `GET /order/hall` | 接单大厅（陪诊员） |
| `getOrder(id)` | `GET /order/{id}` | 详情（含地址、备注、陪诊员信息） |
| `cancelOrder(id, data)` | `PUT /order/{id}/cancel` | 仅 `PENDING`，`reason` 必填 |
| `acceptOrder(id)` | `POST /order/{id}/accept` | 接单（乐观锁，并发下只有 1 次成功） |
| `rejectOrder(id, data)` | `POST /order/{id}/reject` | 拒单，需原因 |
| `startService(id)` | `POST /order/{id}/start` | `ACCEPTED → IN_SERVICE` |
| `completeService(id, data)` | `POST /order/{id}/complete` | `IN_SERVICE → COMPLETED` |
| `getOrderTimeline(id)` | `GET /order/{id}/timeline` | 状态流转时间线 |

列表 VO 与详情 VO **口径不同**：列表不返回地址/备注，姓名已脱敏。这不是接口缺字段，别去补。

⚠️ **还有一个更容易中招的差别（2026-09-16 实测）**：列表 VO（`OrderVO.ofList`）**连两个 ID 都没有** ——
`elderId` / `familyId` / `companionId` 只有详情 VO（`ofDetail`）才给。
写列表页时若想拿 `records[0].elderId` 去筛，会静默拿到 `undefined`（NON_NULL 会让字段整个消失），
不报错、不告警 —— 见 §11。

### `@/api/execution`
| 函数 | 端点 | 说明 |
|---|---|---|
| `checkin(orderId, data)` | `POST /execution/{id}/checkin` | 打卡（含坐标，服务端做距离校验） |
| `listCheckins(orderId)` | `GET /execution/{id}/checkins` | 打卡记录 |
| `getTrack(orderId)` | `GET /execution/{id}/track` | 轨迹点 |
| `getProgress(orderId)` | `GET /execution/{id}/progress` | 进度快照（当前节点 + 下一节点） |
| `uploadExecutionPhoto(orderId, formData)` | `POST /execution/{id}/photo` | 现场照/取药凭证（multipart） |
| `connectProgressSocket(orderId, handlers)` | `WS /ws/progress` | 实时进度推送，见 §5 |

### `@/api/file`（2026-09-16 新增）
| 函数 | 端点 | 说明 |
|---|---|---|
| `uploadFile(file, bizType)` | `POST /file/upload` | **通用文件上传**（multipart：`file` + `bizType`）|

- **`bizType` 白名单**：`COMPANION_CERT`（资质证件）/ `COMPLAINT`（投诉证据）/ `AVATAR`（头像）。
  传别的（含 `CHECKIN`）→ `code 400`，提示里会列出允许取值。
  `CHECKIN` 被**刻意拒绝** —— 打卡照片必须走 `POST /execution/{id}/photo`（那里有订单归属校验），
  否则可以拿通用端点往任意订单塞文件。
- **不接收 `bizId`**：客户端传的 bizId 服务端无从校验，写进 `sys_file.biz_id` 就是脏数据。
  业务关联由业务侧持 `url` 自己完成。
- **服务端按文件头魔数**判定类型，只收 jpg / png / webp / pdf；单文件 ≤ 10MB。
- 返回 `{ fileId, url, size }`，`url` 形如 `/uploads/202609/xxx.png`（站内相对路径，可直接塞进
  `certificates[].url` / `complaint.evidence[]`；后端 `ValidationPatterns.FILE_URL` 校验该形态）。
- 允许角色 FAMILY / COMPANION / ADMIN；**ELDER 传 → 403**（`ElderReadOnlyInterceptor` 默认拒写）。
- 前端预校验（大小/类型）由调用方负责，**超限不要发请求**（`design.md §2.6`）。
  已在 `companion/entry.vue`（资质）与 `family/order-complaint.vue`（证据，上限 6 张）落地。

### `@/api/medication`
| 函数 | 端点 | 说明 |
|---|---|---|
| `listMedicineDict(params)` | `GET /medication/dict` | 药品字典（**只给通用信息**） |
| `getMedicine(id)` | `GET /medication/dict/{id}` | — |
| `listMedicationPlans(params)` | `GET /medication/plan` | 用药计划列表 |
| `createMedicationPlan(data)` | `POST /medication/plan` | — |
| `updateMedicationPlan(id, data)` | `PUT /medication/plan/{id}` | — |
| `disableMedicationPlan(id)` | `DELETE /medication/plan/{id}` | 停用（不物理删） |
| `getMedicationCalendar(params)` | `GET /medication/task/calendar` | 参数 `{ elderId, startDate, endDate }` |
| `getTodayTasks(elderId)` | `GET /medication/task/today` | 今日待服 |
| `confirmMedication(taskId, data)` | `POST /medication/task/{id}/confirm` | 确认服药 |

⚠️ 备注列叫 **`confirm_remark`**（不是 `remark`）。字典接口**必须**展示 `disclaimer` 免责声明。

### `@/api/review`
| 函数 | 端点 |
|---|---|
| `createReview(data)` | `POST /review` |
| `getReviewByOrder(orderId)` | `GET /review/order/{id}` |
| `listCompanionReviews(companionId, params)` | `GET /review/companion/{id}` |
| `getCompanionScore(companionId)` | `GET /review/companion/{id}/score` |
| `createComplaint(data)` | `POST /complaint` |
| `listMyComplaints(params)` | `GET /complaint` |
| `getComplaint(id)` | `GET /complaint/{id}` |

### `@/api/message`
| 函数 | 端点 | 说明 |
|---|---|---|
| `listMessages(params)` | `GET /message` | 分页 + `type` 筛选 |
| `getUnreadCount()` | `GET /message/unread-count` | → **`{ total, byType }`**（见下方警告） |
| `markRead(id)` | `PUT /message/{id}/read` | — |
| `markAllRead()` | `PUT /message/read-all` | — |
| `removeMessage(id)` | `DELETE /message/{id}` | — |
| `startUnreadPolling(ms, onChange)` | 轮询 | → `{ stop(), refresh() }`；**页面卸载必须 `stop()`** |

⚠️ **未读数的字段是 `total`，不是 `unreadCount`**（后端 `UnreadCountVO` = `{ Long total, Map<String,Long> byType }`）。
写成 `res.unreadCount` 会**恒为 0**（红点永远不亮），而且不抛任何错误 —— 属于最难发现的一类缺陷。
`api/message.js` 里的 `startUnreadPolling` 已改为兼容两种口径：`Number(data?.total ?? data?.unreadCount ?? 0)`。

SSE 端点 `/sse/message` 要 `Authorization` 头，浏览器原生 `EventSource` 设不了头，
**所以前端走轮询**（见 `api/message.js` 注释）。不要试图改成 EventSource。

### `@/api/admin`（仅 ADMIN）
| 函数 | 端点 |
|---|---|
| `listAuditApplications(params)` | `GET /admin/companion/audit` |
| `getAuditDetail(id)` | `GET /admin/companion/audit/{id}` ← 详情才返回 `rejectReason`/`auditRemark` |
| `auditCompanion(id, data)` | `POST /admin/companion/audit/{id}`，`{ approved, reason }`，驳回时 `reason` 必填 |
| `listUsers(params)` | `GET /admin/user` |
| `disableUser(id, data)` | `POST /admin/user/{id}/disable` |
| `enableUser(id)` | `POST /admin/user/{id}/enable` |
| `resetUserPassword(id)` | `POST /admin/user/{id}/reset-password` |
| `listAllOrders(params)` | `GET /admin/order` |
| `arbitrateOrder(id, data)` | `POST /admin/order/{id}/arbitrate` |
| `listComplaints(params)` | `GET /admin/complaint` |
| `handleComplaint(id, data)` | `POST /admin/complaint/{id}/handle` |
| `listOperLogs(params)` | `GET /admin/oper-log` |

### `@/api/statistics`
`getOverview` `GET /statistics/overview` ｜ `getOrderTrend` `GET /statistics/order-trend`
｜ `getOrderStatusDistribution` `GET /statistics/order-status` ｜ `getCompanionRank` `GET /statistics/companion-rank`
｜ `getMedicationMissedStat` `GET /statistics/medication-missed`
｜ `exportOrders(params)` `GET /statistics/export/order`（**Blob**）
｜ `exportUsers(params)` `GET /statistics/export/user`（**Blob**）

**Blob 下载的正确姿势**（导出页必看）：

```js
const res = await exportOrders({ startDate, endDate })
// 注意：响应体若实际是 JSON 错误，也会被当 blob 返回 —— 必须自己校验类型
if (!(res instanceof Blob) || res.type.includes('application/json')) {
  ElMessage.error('导出失败：' + (await res.text?.() ?? '服务端未返回文件'))
  return
}
const url = URL.createObjectURL(res)
const a = document.createElement('a')
a.href = url
a.download = `订单数据_${today()}.xlsx`
a.click()
URL.revokeObjectURL(url)
```

⚠️ 后端导出有行数上限，超限会拒绝而不是截断 —— 页面上要给出日期区间提示。

---

## 4 · 组件契约

```js
import {
  NlPhoneShell, NlCard, NlStatusChip, NlListRow, NlEmpty, NlSkeleton,
  NlStatusBar, NlNavBar, NlTabBar, NlStepHeader, NlSection,
  NlComplianceBar, NlNoticeBar, NlAvatar, NlIconBox, NlTimeline
} from '@/components'
```

| 组件 | props | 备注 |
|---|---|---|
| `NlPhoneShell` | `nav`(`{title, back, transparent}`\|null)、`hasTabs`、`hasCta`、`statusInverse`、`statusTime`、`bodyBg`、插槽 `nav-right` / `cta` | 移动端页面**最外层一律用它** |
| `NlCard` | `title`、`plain`、`hover`、`extra`、`padding`、插槽 `title`/`extra` | — |
| `NlSkeleton` | **`count`**（不是 `rows`！）、`height`、`card` | 加载态占位 |
| `NlEmpty` | `type`(`empty`/`403`/`404`/`network`)、`title`、`description`、`actionText`、事件 `@action` | 空态/错误态 |
| `NlListRow` | `title`、`subtitle`、`chevron`、`divider`、`tone`、插槽 `icon`/`extra` | — |
| `NlSection` | 见组件源码 | 分组小标题 |
| `NlNoticeBar` | 见组件源码 | 提示条（合规声明用） |
| `NlComplianceBar` | 见组件源码 | 底部合规声明条 |
| `NlTimeline` | `steps`：`[{ key, label, status: 'done'\|'current'\|'todo', time, description }]`，事件 `@click`，插槽 `s.key` / `current` | — |
| `NlAvatar` | `src`、`fallback`、`size`、`tone`、`badge` | — |
| `NlTabBar` / `NlNavBar` / `NlStatusBar` / `NlStepHeader` / `NlIconBox` | 见组件源码 | 不确定就 Read 组件文件 |

**不确定 props 就 Read 组件源码**（`frontend/src/components/Xxx.vue` 顶部 `defineProps`），别猜。

---

## 5 · 实时通道

### 5.1 陪诊进度（WebSocket，已可用）

```js
import { connectProgressSocket } from '@/api/execution'

let socket = null
socket = connectProgressSocket(orderId, {
  onEvent: (msg) => { if (msg.type === 'ORDER_PROGRESS') reload() },
  onStatus: (status) => { connected.value = status === 'open' }
})
onBeforeUnmount(() => socket?.close())   // 必须关，否则换页后还在重连
```

- 端点 `/ws/progress?token=<accessToken>&orderId=<id>`，令牌走 **query 参数**（浏览器 WebSocket 设不了头）。
- 内部已实现：30s 心跳（裸字符串 `"ping"`）、指数退避重连（上限 30s、5 次后放弃）。
- 重连时会重新 `getToken()`，所以不会用到过期快照。
- **订阅前必须先拉一次 `getProgress(orderId)` 快照铺底**，否则连接建立前页面是空白。
- 推来的是增量事件，不是完整对象 —— 收到推送后重新拉详情/进度最稳。

### 5.2 未读消息（轮询）

```js
import { startUnreadPolling } from '@/api/message'
let polling = null
onMounted(() => { polling = startUnreadPolling(60000, (n) => { unread.value = n }) })
onBeforeUnmount(() => polling?.stop())
```

---

## 6 · 页面骨架要求（每个页面都要有）

1. **加载态**：`loading.value = true` → 渲染 `<NlSkeleton :count="3" />`，不要白屏。
2. **空态**：`<NlEmpty type="empty" title="..." description="..." action-text="..." @action="..." />`。
3. **异常**：`catch` 里把列表置空、`loading=false`，**不重复弹提示**（拦截器已弹）。
4. **无假数据**：所有数字来自接口。没有接口来源的模块直接删除。
5. **合规文案**：涉及药品、健康信息的页面必须有免责声明（`NlNoticeBar` 或 `NlComplianceBar`）。
6. **样式**：色值/字号一律走 CSS 变量（前缀 `--nl-`），**禁止硬编码色值**。
   `@use '@/styles/variables.scss' as *;` 在每个 `<style scoped>` 顶部。
7. **不要引入新依赖**，不要用 Element Plus 之外的 UI 库。
8. **样式不得大改**：本轮是「接数据」，视觉已按 design.md 定稿，只允许为「有/无数据」补状态样式。
   现有的 class 名与布局保持，不要把页面重画一遍。

**参考样板：`frontend/src/views/family/order-list.vue`** ——
它已经按本文件全部要求写好（分页、`NlSkeleton :count`、`NlEmpty`、`catch` 不重复弹、枚举用后端 label、
格式化走 `@/utils/format`）。**动手前先读它**，照它的路子写。

---

## 7 · 路由与角色对照（`frontend/src/router/routes.js`）

| 路由 | 文件 | 角色 |
|---|---|---|
| `/elder/home` | `elder/home.vue` | ELDER |
| `/elder/medication` | `elder/medication.vue` | ELDER |
| `/elder/message` | **`companion/message.vue`** | ELDER |
| `/family/home` | `family/home.vue` | FAMILY |
| `/family/elder` | `family/elder-list.vue` | FAMILY |
| `/family/elder/bind` | `family/elder-bind.vue` | FAMILY |
| `/family/medication` | `family/medication.vue` | FAMILY |
| `/family/message` | **`companion/message.vue`** | FAMILY |
| `/family/order/step1..3` | `family/order-step{1,2,3}.vue` | FAMILY（**已完成**） |
| `/family/order` | `family/order-list.vue` | FAMILY（**已完成**） |
| `/family/order/:id` | `family/order-detail.vue` | FAMILY（**已完成**） |
| `/family/order/:id/review` | `family/order-review.vue` | FAMILY |
| `/family/order/:id/complaint` | `family/order-complaint.vue` | FAMILY |
| `/companion/hall` | `companion/hall.vue` | COMPANION |
| `/companion/entry` | `companion/entry.vue` | COMPANION |
| `/companion/execute/:id` | `companion/execute.vue` | COMPANION |
| `/companion/order` | `companion/order-list.vue` | COMPANION |
| `/companion/income` | `companion/income.vue` | COMPANION |
| `/companion/message` | `companion/message.vue` | COMPANION |
| `/profile` | `profile/index.vue` | 全角色共用 |
| `/admin/*`（8 页） | `admin/*.vue` | ADMIN |

⚠️ **`elder/message.vue` 与 `family/message.vue` 没有被任何路由引用（死文件），不要动它们。**
消息页只有一份：`companion/message.vue`，且它被 3 个角色共用 → **必须按当前登录角色渲染**（读 `useUserStore().role`）。

⚠️ `/profile` 也是全角色共用 → 同样要按角色区分展示。

---

## 8 · 合规红线（不可逾越，违规即打回）

1. **不做诊断、不开药方。** 药品字典只展示通用信息 + `disclaimer`。
   全站严禁出现：「建议服用」「推荐剂量」「对症」「可替代」「诊断为」「可能是 XX 病」。
2. **隐私最小化。** 手机号 / 身份证在接口层已脱敏（`138****8888`、`110101********4567`）；
   **前端禁止把完整手机号、身份证号、密码写进 localStorage**。头像/昵称展示直接用脱敏值。
3. **一期砍掉**：在线支付（改为线上记账 + 线下结算，**不要出现「在线支付」「原路退回」字样**）、
   地图导航（改为文字地址 + 坐标，**不要引入地图 SDK**）、IM 聊天（改为站内信）。
4. **老人账号（ELDER）默认只读。** 写操作由家属代做。前端的隐藏按钮只是体验，不是安全边界 ——
   但**页面也不必为 ELDER 渲染写操作入口**，否则用户点了必然 403。

---

## 9 · 常见陷阱清单（本仓库真实踩过）

1. `NlSkeleton` 的 props 是 **`count`**，不是 `rows`。写 `:rows="3"` 会什么都不显示。
2. `page`/`size` 不是 `pageNum`/`pageSize`；`size` ≤ 100。
3. `SETTLED` 不是 `PAID`；`RESOLVED` 不是 `CLOSED`；账号态没有 `BANNED`。
4. 后端 `non_null`：空字段直接消失，模板里要能扛 `undefined`。
5. `catch` 里不要再弹错误提示（拦截器已弹），否则用户看到两条一样的报错。
6. WebSocket 令牌走 query，**用 header 会静默连不上**，控制台只说「握手失败」。
7. `onBeforeUnmount` 必须关 socket / 停轮询，否则换页后后台还在重连、还在打接口。
8. 不要用 `new Date('yyyy-MM-dd HH:mm:ss')`，走 `@/utils/format`。
9. 导出接口返回 Blob，**要校验 `res.type`**，否则 JSON 错误会被下载成损坏的 xlsx。
10. 本地 Maven/Node 都已配好；**不要新增依赖**。
11. 页面里拿角色：`import { useUserStore } from '@/store/modules/user'` → `userStore.role` / `userStore.userInfo`。
    动手前先 Read 这个 store 确认真实字段名。

---

## 10 · 实测数据库结构与种子事实（**已连库核对，不是推测**）

> 直接 `SHOW COLUMNS` / `SELECT COUNT(*)` 得出来的，比读代码更可信。**猜列名必错，照这里抄。**

### 10.1 表清单（库 `nianglin`，共 20 张业务表）

`admin_oper_log`、`companion_audit_record`、`companion_order`、`companion_profile`、`companion_track`、
`complaint`、`elder_profile`、`family_elder_relation`、`internal_message`、`medication_plan`、
`medication_task`、`medicine_dict`、`order_checkin`、`order_reject_log`、`order_review`、
`order_status_log`、`sys_dict`、`sys_file`、`sys_login_log`、`sys_user`。

⚠️ 站内信表叫 **`internal_message`** —— 不是 `message`，也不是 `user_message`（这两个都不存在）。

### 10.2 `elder_profile`（老人档案）

`id` / `user_id` / **`name`**（⚠️ **不是 `real_name`**）/ `gender` / `birth_date` / `id_card` / `phone` /
`address` / `emergency_contact` / `emergency_phone` / `medical_history` / `allergy_history` /
`mobility_level` / `favorite_hospital` / `bind_status` / `create_by` / `remark` / 审计字段

### 10.3 `companion_profile`（陪诊员资料）

`user_id` / `audit_status`(`PENDING`/`APPROVED`/`REJECTED`) / **`work_status`** / `score` / `review_count` /
`order_count` / `accept_count` / `reject_reason` / `audit_admin_id` / `audit_time` / 健康证字段…

**`work_status` 实测取值 `AVAILABLE` / `REST`**（可接单 / 休息中）—— 这是骨架里完全没提过的枚举，
资质/接单页要用到先以后端 VO 实际返回为准。

### 10.4 `medication_task`（服药任务）

`id` / `plan_id` / `elder_id` / `medicine_id` / `medicine_name` / `dosage` / `meal_relation` /
`plan_date`(**date**) / `plan_time`(**datetime**) / `status` / `was_missed` / `confirm_time` / `confirm_by` /
**`confirm_remark`**（⚠️ **不是 `remark`**）/ `notify_sent` / `notify_time`

取值：`status` ∈ `PENDING`/`TAKEN`/`MISSED`；`meal_relation` ∈ `BEFORE_MEAL`/`AFTER_MEAL`/`ANY`。

### 10.5 `medication_plan`（用药计划）

`id` / `elder_id` / `medicine_id` / `medicine_name` / `dosage` / `frequency`(**tinyint**) /
`time_points`(**varchar**) / `start_date` / `end_date` / `meal_relation` / `status`(`ACTIVE`/`DISABLED`) /
`remark` / `created_by`

### 10.6 `companion_order`（订单）

`id` / `order_no` / `family_id` / `elder_id` / `companion_id`(未接单为 NULL) / `hospital` / `department` /
`visit_time` / `fee` / `actual_fee` / `status` / `payment_status` / `version`(乐观锁) / `arbitrate_flag` …
列表 VO 不含 `address`/`remark`，详情 VO 才给。

### 10.7 种子账号与口令（E2E 用）

| 角色 | 账号 | user_id | 说明 |
|---|---|---|---|
| ADMIN | `admin` / `superadmin` | 1 / 2 | — |
| FAMILY | `fam001` … | 101 起 | — |
| ELDER | `elder001` … `elder030` | 201 起 | `elder030`(230) 是 **`DISABLED` 封禁账号**，用于越权反向用例 |
| COMPANION | `comp001` … | 301 起 | — |

口令统一 **`Nl@123456`**（所有种子用户的 bcrypt 哈希相同）。

**登录必须带图形验证码**：`GET /api/auth/captcha` → 拿到 `captchaKey` 后，
验证码明文存在 Redis 键 **`captcha:<captchaKey>`**（大写，TTL 300 秒，校验时 `getAndDelete` 一次性消费）。
自动化里用 `redis-cli GET captcha:<key>` 直取，不要做 OCR。

### 10.8 种子数据分布（用于判断页面「该显示几条」）

- **订单**（共 64）：`PENDING` 6 / `ACCEPTED` 8 / `IN_SERVICE` 4 / `COMPLETED` 12 / `REVIEWED` 31 / `CANCELLED` 3。
  待接单样本 id `1001`–`1006`，费用 98 / 128 / 158 / 188 / 218。
  陪诊员端「待接单」应显示 **6 条**。
- **fam001(101)** 只绑定了老人 **401**（`张德海`，MALE，`BOUND`，`SELF`，`is_default=1`）。
  另有一条 **`UNBOUND`** 关系指向 431（解绑边界样本）。
  fam001 名下订单只有 **2 条**：`1031`(REVIEWED) 与 `1001`(PENDING)。
- **comp001(301)**：`audit_status=APPROVED`、`work_status=AVAILABLE`、`score=4.00`、`order_count=18` → **可以接单**。
- **服药任务**：`MISSED` 213 / `PENDING` 12 / `TAKEN` 338。老人 401 在 `2026-09-16` 的任务**全是 `MISSED`**
  （漏服扫描器已跑过，不是缺陷）。
- **投诉**：`PENDING` 10 / `PROCESSING` 6 / `RESOLVED` 11 / `REJECTED` 5。
- **资质申请**：`companion_profile` 24 APPROVED / 4 PENDING / 2 REJECTED。

### 10.9 连库自查（PowerShell，密码走环境变量）

```powershell
$sql = "SHOW COLUMNS FROM medication_task;"
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -h 127.0.0.1 -P 3306 -uroot "-p$env:MYSQL_PASSWORD" `
  -D nianglin --default-character-set=utf8mb4 -t -e "$sql"
```

⚠️ **写任何 SQL 之前先 `SHOW COLUMNS`** —— 本仓库已经因为「以为备注列叫 `remark`」踩过一次坑。
⚠️ PowerShell 捕获 mysql 的 UTF-8 中文会乱码（显示成「寮犲痉娴?」这种），
**不影响数据正确性**，要看清中文就 `... | Out-File x.txt -Encoding utf8` 再用文件读取工具看。
（或者干脆只查 ASCII：数字、枚举名、id —— 判据放在这些上面最稳。）

---

## 11 · ELDER 怎么拿到「自己的 elderId」

> **状态：缺口已补齐（2026-09-16）**。此前 ELDER 拿不到自己的 `elderId`，
> 前端被迫走「列表取 id → 详情读 elderId」两步绕法；现在 `UserInfoVO` 直接下发，
> 两步绕法**已从 `elder/home.vue` / `elder/medication.vue` 中删除**。

### 11.1 正解

`GET /api/auth/me` 与 `GET /api/user/profile` 返回的 `UserInfoVO` 新增字段：

| 字段 | 类型 | 规则 |
|---|---|---|
| `elderId` | Long | **仅 ELDER 且有已建档（未逻辑删除）的老人档案时返回**；其余角色该字段在 JSON 中**整个消失** |

- 取值来源：`ElderService#elderIdOf(SysUser)`（`ElderServiceImpl`）。
  非 ELDER 直接返回 `null`（**不查库**）；ELDER 按 `elder_profile.id` **升序取首条**，
  保证一人多档案时结果确定、绝不随机。
- 后端落地：`AuthServiceImpl.currentUser()` / `buildLoginVO()`、`UserServiceImpl.getProfile()`。
- 前端用法：`const elderId = computed(() => profile.value?.elderId ?? null)`，
  `null` 时降级为「入口引导」空态，**绝不伪造 id**。

### 11.2 ❌ 不要试图给 `OrderVO.ofList` 补 `elderId`

这条曾经被当成「一行上游正解」写进本文件，**是错的**。
`OrderAccessMatrixTest#listShouldMaskNameAndHideAddress`（`:451-466`）**硬断言**列表 VO
不得包含 `familyId` / `elderId` / `companionId` / `address` / `remark` / `version`
—— 这是隐私最小化的红线。**用户自己的 id 走 `UserInfoVO`，他人的 id 一律不给**，
两者的取舍完全不同，别混。

### 11.3 各接口的 elderId 可见性（现状）

| 接口 | 是否给 elderId | 说明 |
|---|---|---|
| `GET /api/auth/me`、`GET /api/user/profile` | ✅ **自己的** | `UserInfoVO`，仅 ELDER |
| `GET /api/order`（列表） | ❌ | `OrderVO.ofList` 有意脱敏，只给 `elderName`/`elderAge` |
| `GET /api/order/{id}`（详情） | ✅ | `ofDetail` 带；且需过 `requireInvolved`（`OrderServiceImpl:641`）|
| `GET /api/user/elder` | ❌ 但也不需要 | 类级只认 **FAMILY**，ELDER 调是 403 |
| `GET /api/medication/task/today`、`/task/calendar`、`/plan` | ⚠️ `elderId` 是**必填入参** | 不存在「按当前用户自动收敛」，所以必须先用 11.1 拿到 id |

---

## 12 · Playwright 联调巡检（`tools/e2e/`）——踩过的坑与两条硬判据

脚本：`harvest_tokens.py`（取真令牌）→ `probe_api.py`（直连接口验真数据）→ `run_ui_sweep.py`（浏览器逐页巡检）。

### 12.1 ⚠️ 最严重的一次**假绿**：换账号不清 localStorage

现象：报告「33/33 全绿」，但吐出来的 8 张 admin 截图 + 9 张 fam001 截图 **MD5 完全一致**，
内容全是**老人端首页**。

根因链条：
1. `frontend/src/utils/auth.js` 把 `nianglin_user_info`（**含 role**）持久化在 localStorage；
2. `frontend/src/router/index.js:70` 的补拉条件是 `if (!userStore.userInfo) fetchCurrentUser()`
   —— **有缓存就不补拉**；
3. 巡检只 `localstorage-set nianglin_access_token`（换 token 不换 userInfo）→
   第 2 个账号开始，守卫一直拿着上一个账号的 `role`；
4. `router/index.js:83-85` 角色不匹配 → 全部弹回 `ROLE_HOME[上一个角色]` = `/elder/home`；
5. 而 `elder/home` 文本很长，躲过了「文本过短」判据 → **判绿**。

**两条防线（缺一不可）**：
- 每个账号开始前 `localStorage.clear()` + `sessionStorage.clear()`，再由守卫从
  `/auth/me` 重新取角色（`run_ui_sweep.py` 的 `RESET_JS`）；
- **每页断言 `new URL(page.url()).pathname === 目标路由`**，不一致直接判红
  （`rec.pathMismatch`）。少了这条，「重定向」能把整个矩阵变成同一页的复读而没人发现。
- 跑完再按账号比对截图 MD5，重复即报警（`duplicateShots`）—— 这是自动哨兵，比人眼可靠。

> **教训**：「页面能渲染、无 JS 报错、文本丰富」**不等于「你测的就是那个页面」**。
> 任何 UI 巡检都必须带一条「我落在哪个 URL」的断言。

### 12.2 其余 Windows/CLI 坑（详见脚本头部注释）

- 必须调 `playwright-cli.cmd`（npm 全局 shim 的 `.ps1` 版本，Python `subprocess` 走 CreateProcess 不认 → WinError 2）。
- `.cmd` 经由 cmd.exe，**单条命令 8191 字符上限** → 页面必须**逐页** `run-code`，不能整批塞。
- `run-code` 的函数体骨架**必须纯 ASCII**，且不内联带双引号的 JSON（会被 cmd 吃掉 → 只报
  `SyntaxError: Unexpected token ')'`，看不出真因）；`<` 有重定向语义也不能用。
- `run-code` 沙箱里**没有 `Buffer` / `TextDecoder` / `atob`** → 任何「在 JS 里解 base64」的方案都不成立。
- 整个矩阵共用一个浏览器 session（每账号新建 session 会让首次 `goto` 必超时），
  账号隔离靠 12.1 的清空来做。
- 导航用 `waitUntil:'commit'` + 单独 `waitForLoadState('domcontentloaded')`；
  直接 `goto(..., 'domcontentloaded')` 实测偶发 30s 超时。
- ⚠️ **5173 可能起不来，且报错与前端代码毫无关系**：Windows 上 Hyper-V/WSL 会动态保留
  一批 TCP 端口（自查 `netsh int ipv4 show excludedportrange protocol=tcp`，本机曾把
  `5152–5251` 整段占掉），落在保留段里的端口绑定直接 `EACCES: permission denied`
  → vite 起不来 → 巡检报「浏览器 session 预热失败」，很容易被误判成前端改坏了。
  **换端口即可**：`npm run dev -- --host 127.0.0.1 --port 5410`
  + `$env:NIANGLIN_APP="http://localhost:5410"`（`run_ui_sweep.py` 已支持该环境变量覆盖）。
- ⚠️ **换前端端口后，如果只有 WebSocket 红、HTTP 接口全绿，先查 CORS 放行来源**：
  浏览器对 `ws://` 升级**必带 `Origin`**，跨源会被 CORS 拒掉（**403，且落在鉴权拦截器之前，
  连订单归属的 WARN 日志都不会打**）；而页面上的普通 XHR 走 vite 代理是**同源**、压根不带 Origin，
  于是 HTTP 全绿、唯独 WS 红 —— 这种「一半绿一半红」最容易被误判成「前端 WS 写错了」。
  **真正生效的放行名单在 `SecurityConfig#corsConfigurationSource()`**，
  它显式交给了 Security 的 CORS 过滤器，所以 `WebMvcConfig#addCorsMappings` 对经过安全链的请求
  **不起作用**（以前这个值硬编码在 SecurityConfig 里，改配置文件和改 WebMvcConfig 都看不出变化）。
  **2026-09-16 已统一**：两处都读同一个属性 `nianglin.cors.allowed-origins`；
  `application-dev.yml` 里配成 `http://localhost:[*],http://127.0.0.1:[*]`
  （本机任意端口，**外网来源仍然 403**，prod profile 不覆盖、走严格默认值）。
  所以换前端端口**不需要**再动后端。
- 定位工具：`tools/e2e/probe_ws_handshake.py`。裸 socket 打一次 Upgrade 请求读回 HTTP 状态行，
  四组合差分（直连带 Origin / 直连同源 Origin / 直连不带 Origin / 走 vite 代理），
  一条命令分清算谁的账 —— 浏览器控制台那句 `WebSocket connection failed` 是**不带状态码**的，
  没有这个探针只能靠猜。
- 跑巡检前先确认**两个**服务都在：后端 8080（`probe_api.py` 用）+ 前端 dev server（`run_ui_sweep.py` 用）。
  只起后端跑巡检，必然预热失败。

### 12.3 判据分层（别只看一层）

| 层 | 脚本 | 证明什么 |
|---|---|---|
| 1 | `run_ui_sweep.py` | 页面能渲染、无 JS 错、无 ≥400、**落地路径正确** |
| 2 | `probe_api.py` | 页面依赖的接口**确实返回了种子数据**（不是「暂无数据」也能判绿） |
| 3 | `probe_backend_gaps.py` | **写接口**真的能写（通用上传成功 / CHECKIN 被拒 / ELDER 403 / 魔数非法被拒）|

只做第 1 层会漏掉「页面永远显示空态」；只做第 2 层会漏掉「接口对但前端接错字段」。
`getUnreadCount` 读 `unreadCount`（真值 `total`）就是第 1 层漏、只有回库/直连才发现的例子。
第 3 层是**唯一会改数据的**，跑完必须按脚本提示清理 `sys_file` 行与磁盘文件
（详见 `docs/agents/FIXTURE_ROLLBACK_PLAN.md`）。
