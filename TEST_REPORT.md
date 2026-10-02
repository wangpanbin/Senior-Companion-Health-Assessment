# 银龄伴诊 · 前后端联调全功能测试报告

- **执行日期**：2026-10-02
- **被测版本**：`main` @ `ba5b831`（含 `4f4dbd1` 之后的全部提交）
- **执行方式**：四层判据（Playwright 166 条 / 接口级 10 脚本 / 写路径探针 / 内置浏览器探索复核）
- **结论**：**确认 1 个 A 类真实缺陷并已修复验证**（审计日志记错变更前状态）。另有 1 项初判为缺陷的（A-02 订单详情姓名全显），经深读 javadoc 与接口契约后**核实为刻意的场景豁免、非缺陷**，已转为补文档。其余失败全部为断言过时、脚本缺陷或环境漂移。

---

## 一、执行摘要

| 层 | 手段 | 规模 | 结果 |
|---|---|---|---|
| **L0** | 端口与服务身份闸门 | 5 项 | ✅ 5/5 |
| **L1** | Playwright 浏览器套件 | 13 spec / **166 条** | ✅ **166/166 全绿**，2.8 分钟，verify 全部回到基线 |
| **L2** | 接口级 E2E 脚本（M2–M10） | 10 个脚本 / 约 500 项断言 | ⚠️ 12 项失败 → 归类后 **1 个 A 类缺陷** |
| **L3** | 写路径探针 | `probe_api` 32 项 + 上传 6 项验收 | ✅ 32/32 + 6/6 |
| **L4** | 内置浏览器探索复核 | B1/B2/B5/B6/B7 | ✅ 通过（B3/B4 受环境限制，见下） |

**L1 是本轮最强的信号**：166 条用例此前从未执行过（上次运行是 2026-09-29 19:44，且之后当天又有 4 次提交落地），本轮首次执行即全绿。

---

## 二、环境与基线

### 运行环境

| 组件 | 状态 |
|---|---|
| MySQL 8.0.42（`MySQL80`） | ✅ Running，库 `nianglin`，Flyway V1–V4 全部 `success=1` |
| Redis 8.8.0 | ✅ Running，`redis-cli` 在 `D:\develop\Redis-8.8.0\redis-cli.exe` |
| 后端 Spring Boot | ✅ 8080，`application=nianglin` / `profiles=dev` / JDK 21.0.7 |
| 前端 Vite | ✅ 5141，vite 代理 → 8080 实探通 |
| 端口 8080 / 5141 | 跑前均空闲，**无端口冲突** |

### 关键：端口身份闸门（本轮新增）

上一轮存下的「45 条失败」全部是同一个错误 `取验证码失败：404 {"code":40400,"msg":"资源不存在"}`，而 `40400` / `资源不存在` / `msg` 字段在本仓库后端源码里**根本不存在**（`Result` 用的是 `message`）。根因是当时 8080 被本机另一应用占用。

`tools/e2e/start-services.ps1:40-47` 的逻辑是「8080 若已监听就跳过启动」，**不校验监听者是谁**，因此无法拦截这种情况。本轮在任何测试开始前加了身份断言：

```
{"code":200,"message":"操作成功","data":{"application":"nianglin","profiles":"dev",
 "javaVersion":"21.0.7","status":"UP"},"success":true}
```

5 项全过（`code==200` / `application==nianglin` / `profiles 含 dev` / `status==UP` / 字段名是 `message` 而非 `msg`）。**本轮未出现任何环境类假红。**

### 种子数据（已连库核实）

无需重新生成，V1–V4 已全部迁移。7 个 E2E 账号全部存在、口令为 BCrypt（`$2a$10$`）、`status=NORMAL`；订单 6 种状态、陪诊员 3 种审核状态均有样本。

> `order_fee_item` 表 0 行**不是缺陷** —— `V4__order_fee_item.sql` 头部注明权威来源是 `companion_order.actual_fee` 按 `SUM(amount)` 归集，空表是设计如此。

---

## 三、L1 · Playwright 浏览器套件（166/166）

```
Running 166 tests using 1 worker
166 passed (2.8m)
[verify] 全部回到基线 ✅
```

13 个 spec 全部通过：

| spec | 覆盖 |
|---|---|
| 01-auth | 登录页渲染 / 未登录重定向 / 错误码 / 注册找回 / 4 角色角色主页 / `/auth/me` elderId 可见性 / 登出 / F-01 按设备拉黑 |
| 02-access-matrix | 4 角色门槛矩阵 / ELDER 写操作 100% 服务端 403 / 归属越权 / ADMIN 带非法体仍 403 / 未过审陪诊员 2003 |
| 03-elder | 老人端 5 页 / 路由落移动壳 / 老人模式切换 / 偏好持久化 / 触控区令牌 |
| 04-family-order | 家属端 9 页 / 订单契约 / 下单可取消 / 评价门禁 / 投诉 / 合规违禁词 |
| 05-companion | 陪诊端 6 页 / 大厅全 PENDING / 跳级 3002 / 6 打卡节点 / 距离 4001 / 重复 4002 / 排序单调 / 推送落库 / ElNotification |
| 06-medication | 用药计划 / 日历 / 药品字典仅通用信息 / 违禁词 |
| 07-message | 列表与未读真值 / 标记已读幂等 / **消息详情本人 200 他人 7002** |
| 08-admin | 后台 8 路由 / 资质审核过滤 / 四列表 / 操作日志非空 |
| 09-statistics | 5 个统计接口 <2s / ECharts 渲染 / Excel 导出（接口 + UI 下载） |
| 10-form-factor | 5 个断点的壳 / mobileOnly 提示页 / 老人模式开关仅移动形态 |
| 11-realtime | WS 建连 / 进度一致 / 未读轮询 / 打卡后未读 ≤ deadline+1 / 家属端 WS 收到 ORDER_PROGRESS |
| 12-admin-responsive | 3 档视口 × 8 页渲染 / sidebar 自适应 / KPI 卡 / 表单 max-width |
| 13-client-mobile | 5 档视口 × 4 页 / NlPhoneShell padding / 横屏紧凑 / el-dialog fullscreen |

回滚：17 张写表水位复原、6 张影子表逐列一致、Redis 63 键还原、上传目录 0 新增，`verify` 全 `[OK]`。

---

## 四、L2 · 接口级 E2E（M2–M10）

| 模块 | 脚本 | 结果 |
|---|---|---|
| M2 认证 | `e2e_auth.py` | **33/40** — 7 失败 |
| M3 档案 | `e2e_user_profile.py` | ✅ 全过 |
| M4 订单 | `e2e_order.py` | **77/78** — 1 失败 |
| M5 执行 | `e2e_execution.py` | ✅ 全过 |
| M6 用药 | `e2e_medication.py` 阶段一 | ✅ 65/65 |
| M6 用药 | `--scan-phase` 阶段二 | **4/5** — 1 失败 + 脚本 cleanup 崩溃 |
| M7 评价 | `e2e_review.py` | ✅ 64/64 |
| M8 站内信 | `e2e_message.py` | **50/52** — 2 失败 |
| M9 管理 | `e2e_admin.py` | **105/106 → 修复后 106/106** |
| M10 统计 | `e2e_statistics.py` | ✅ 78/78 |

12 项失败逐条取证后：**1 个 A 类真实缺陷，10 个断言/脚本过时，1 个环境漂移。**

---

## 五、Bug 清单

### 🔴 A-01 · 管理端审计日志记错变更前状态 —— ✅ 已修并验证

| 项 | 内容 |
|---|---|
| **现象** | `admin_oper_log.before_status` 记录的是变更**后**的状态。管理员把 `IN_SERVICE` 强制改成 `CANCELLED`，日志里却写成 `CANCELLED → CANCELLED` |
| **复现** | 建单 → 接单 → 开始服务（库中确认 `IN_SERVICE`）→ 管理员仲裁为 `CANCELLED` → 查 `admin_oper_log` |
| **预期** | `1\|ORDER\|<oid>\|IN_SERVICE\|CANCELLED` |
| **实际（修复前）** | `1\|ORDER\|17837\|CANCELLED\|CANCELLED` |
| **根因** | `AdminServiceImpl.arbitrate:412` 与 `OrderServiceImpl.forceTerminal:563` 在**同一个 `@Transactional`** 内各自调 `orderMapper.selectById(同一 id)`。MyBatis 一级缓存（`localCacheScope=SESSION`，默认开启）对「相同语句 + 相同参数」返回**同一个对象实例**；`forceTerminal` 随后把该实例的 status 改成目标状态，`arbitrate:443` 再读 `before.getStatus()` 拿到的已是新值 |
| **影响** | `admin_oper_log` 是纠纷取证的权威记录。before_status 记错 = 「管理员覆盖了哪个状态」这一关键信息丢失，证据链断在审计日志上 |
| **为何长期未被发现** | 库里现存两条 ARBITRATE_ORDER 种子记录（60032 / 60037）before/after **是正确的** —— 因为它们是 SQL 直接 INSERT 的，绕过了这条代码路径。缺陷只在真实 API 路径上出现 |
| **修复** | `AdminServiceImpl.arbitrate`：`selectById` 之后立刻把状态与单号快照成局部变量，不再等写日志时回读实体 |
| **验证** | 同一复现脚本修复后输出 `1\|ORDER\|17838\|IN_SERVICE\|CANCELLED` 判定 ✅；M9 全量重跑 **106/106，exit=0**，无回归 |
| **改动文件** | `backend/src/main/java/org/company/nianglin/service/impl/AdminServiceImpl.java`（1 处逻辑 + 1 处调用） |

### ✅ A-02 · 订单详情返回全名 —— 经核实为**刻意设计，非缺陷**（已补文档）

> **本条最初被我误判为缺陷，特此更正。** 初判依据是「`ofDetail` 漏了 `MaskUtil`」，
> 后续深读 javadoc 与接口契约后确认那是**三层一致的既定决策**，根因判断有误。

| 项 | 内容 |
|---|---|
| **初判现象** | 订单详情页显示完整老人姓名与陪诊员姓名，而同站其他位置都脱敏 |
| **证据** | 页面显示「就诊人 **张德海** · 88 岁」「操作记录：待接单 · **张伟**」「已接单 · **黄海涛**」 |
| **接口实测** | `GET /api/order/1001` → `elderName = "张德海"` |
| **核实结论** | **刻意设计，非疏漏。** 三层一致： |
| ① 访问控制 | `OrderService#requireInvolved` 把守，非相关方一律 `3004`。能打开详情的只有下单家属本人 / 接单陪诊员本人 / 就诊老人本人 / 管理员。同一判定还被 WebSocket 握手复用（`JwtHandshakeInterceptor`），注释写明「少掉第二个校验，任何一个登录用户都能订阅别人的陪诊进度 —— 那是老人全天行动轨迹级别的信息」 |
| ② VO 层 | `OrderVO#ofDetail` javadoc：「访问者已经过 requireInvolved 判定为该订单的相关方，此时再对姓名做脱敏会让页面变成『张\*海 的订单』——该看的人看不到该看的信息」 |
| ③ 契约层 | `OrderController#detail` 的 `@Operation` 明写「**此处姓名返回全名**」，Knife4j 文档可见 |
| **列表/详情不一致** | 同 VO 的 `ofList` / `ofHall` 走 `MaskUtil.name()`，`ofDetail` 不走 —— 这条不一致是**有意**的，不是遗漏 |
| **真正的缺口** | **AGENTS.md §0.1.2 只写了「姓名按场景脱敏」，没有记录这条已存在的场景豁免** —— 这正是本条被误判为缺陷的原因 |
| **处置** | ✅ **代码不动**（已确认是契约的一部分且访问受控）。已在 **AGENTS.md §0.1.2 补写该场景豁免**，把「按场景脱敏」落到具体接口，并注明「三处一起改」与「不要拿本条豁免类推其它接口」 |

**全站姓名字段脱敏扫描结果**（唯一「未脱敏」项即 A-02，已核实为刻意豁免）：

| 接口 | 字段 | 值 | 判定 |
|---|---|---|---|
| `GET /api/order/{id}` | `elderName` | `张德海` | ⚠️ **场景豁免（A-02）—— 已核实非缺陷** |
| `GET /api/order` | `elderName` / `companionName` | `张*海` / `黄*涛` | ✅ |
| `GET /api/user/elder` | `name` | `张*海` | ✅ |
| `GET /api/admin/order` | `elderName` / `companionName` | `冯*珍` / `刘*梅` | ✅ |
| `GET /api/admin/oper-log` | `operatorName` | `王*国` | ✅ |
| `GET /api/admin/complaint` | `complainantName` / `targetUserName` | `宋*` / `李*军` | ✅ |

> **教训（已记入本报告）**：本条最初被判为 A 类缺陷，依据是「grep 到 `ofDetail` 没调 `MaskUtil`」。
> 但 grep 只能看到**调用点**，看不到**理由**——真正的设计意图写在 javadoc、`@Operation` 契约和
> 访问控制三层里。**判定「谁漏了」之前必须先问「是不是有人刻意这样写并写下了理由」**，
> 否则会把既定决策当成疏漏去"修"，等于用一次未读文档的结论推翻别人的设计。

---

## 六、失败归类明细（非产品缺陷）

### B 类 · 断言 / 脚本过时（10 项）

| 编号 | 失败项 | 归类依据 |
|---|---|---|
| B-01~06 | `e2e_auth.py` D6–D11 权限矩阵 | 测试打的 `/common/perm-probe/admin`、`/common/perm-probe/elder-write` 端点**在后端根本不存在**（全量检索确认）。覆盖面未丢失 —— Playwright `02-access-matrix.spec.js` 用真实端点做了同样的矩阵并通过 |
| B-07~08 | `e2e_message.py` F1/F2「SSE 未授权应 401」 | **不是安全漏洞**。`MessageSseController` 刻意设计：无令牌时返回 `new SseEmitter(0L)` 并立即 `complete()` —— HTTP 200 开流但**零字节即刻关闭**（实测读到 0 字符），前端 `EventSource` 走 `onerror` 回落轮询。注释与 javadoc 已写明不用 `completeWithError` 是为避开异常日志噪音。`/sse` 不带 `/api` 前缀同样是文档化的刻意例外（避免 Axios 30s 超时掐断长连接） |
| B-09 | `e2e_order.py` E10 断言 `version=1` | 实测 `version=3`。测试注释描述的是旧实现（`WHERE status=?` 条件更新），而 **AGENTS.md §2.4 明确要求用 `@Version` 乐观锁且禁止自己写条件 UPDATE** —— 当前行为才是合规的，断言过时 |
| B-10 | `e2e_medication.py --scan-phase` cleanup 崩溃 | `cleanup():194` 引用 `b5_order_id`，该变量只在阶段一代码路径（`:396`/`:415`）赋值；模块级初始化（`:54-56`）漏了它。`--scan-phase` 下未定义 → `NameError` → **整个清理中断**，探针数据未清掉。属脚本自身一行疏漏 |

### C 类 · 环境漂移（1 项）

| 编号 | 失败项 | 取证过程 |
|---|---|---|
| C-01 | `e2e_auth.py` B5「已封禁账号应返回 1002」 | 种子 `V2__seed_data.sql:113` 明确把 `elder030` 定义为 `DISABLED`（备注"演示用：封禁账号，用来验证鉴权"），`FRONTEND_CONTRACT.md:515` 亦同 —— 但**线上库是 `NORMAL`**，且全库零个非 NORMAL 账号。**自证**：临时置为 `DISABLED` → 登录立即返回 `code=1002 账号已被封禁` → 产品校验完全正常 → 随即还原。B5 纯属库漂移 |

---

## 七、L3 · 写路径探针

### `probe_api.py` — 32/32 全部 OK，异常 0

证明页面依赖的接口**确实返回种子数据**，而不是「暂无数据」也判绿。

### `probe_backend_gaps.py` — 脚本缺陷，已手工补做

该脚本**第 68 行 `post_multipart` 引用了未定义的 `BASE`**（全脚本没有任何 URL 常量定义），从第一个 multipart 调用起即 `NameError` 崩溃，后续写路径探针（CHECKIN 拒绝 / ELDER 403 / 魔数校验）**一次都没跑到**。前 4 项走其它 helper 故通过。

按你的决定未擅自修改脚本，改用手工探针补齐 `docs/api/10-file-upload.md §三` 的 6 条验收标准：

| # | 验收项 | 结果 |
|---|---|---|
| 1 | 合法 PNG + `COMPANION_CERT` | ✅ 200，`fileId=f_0d749e7a…`，`url=/uploads/202610/…`，`size=67`；`sys_file` 落库 `COMPANION_CERT\|probe-real.png\|67` |
| 2 | `bizType=CHECKIN`（白名单外） | ✅ 400「不支持的业务类型 bizType，允许取值：COMPANION_CERT / COMPLAINT / AVATAR」 |
| 3 | 伪装 `.jpg`（魔数不符） | ✅ 400「文件类型不允许（仅支持 jpg / png / webp / pdf）」 |
| 3b | 伪装 `.png`（内容是 PHP） | ✅ 400 同上 —— **magic bytes 校验确实不信任扩展名与 Content-Type** |
| 4 | 11 MB 超限 | ✅ 400「文件超过 10 MB 上限，请压缩后重试」 |
| 5 | ELDER 上传 | ✅ **HTTP 403** 服务端拦截（PRD §2.3 硬约束） |
| 6 | 返回 url 可直接访问 | ✅ 200，67 字节 |

清理已完成：磁盘文件已删、`sys_file` 回到基线 90 行。

---

## 八、L4 · 内置浏览器探索复核

| 编号 | 复核项 | 结论 |
|---|---|---|
| B1 | 家属端渲染 | ✅ 工作台 / 订单 / 用药 / 消息 / 我的 5 页均正常。订单 2 条覆盖「已评价 + 待接单」双分支；消息 8 条 6 未读，类型标签齐全 |
| B2 | 老人模式真实观感 | ✅ `<html class="elderly-mode">` 生效；开关文案变「**已开启老人模式**」（明确状态反馈）；`el-switch__core` 触控目标 **40×20 → 50×24**（+25%/+20%）；容器内边距两侧各 +4px；关闭后 `class=""` 完全复原 |
| B3 | Mobile/Desktop 形态切换 | ⚠️ **未覆盖** —— 内置浏览器真实视口为 **639×782（< 768px）**，应用恒处于 Mobile 形态。`/profile` 渲染业务内容是**正确行为**而非缺陷（老人模式开关在 Mobile 下本就应可见）。桌面形态行为由 Playwright `10-form-factor` 用真实 1280×720 验证并通过 |
| B4 | 业务闭环走查 | ⚠️ **按计划不执行** —— 下单/接单/打卡/评价/投诉均为写操作，已全部由 Playwright 套件覆盖且带自动回滚，L4 阶段刻意不重复 |
| B5 | 站内信 / 消息详情 | ✅ **新增端点 `GET /api/message/{id}` 端到端可用**：类型标签 / 时间 / 消息内容 / **关联信息**区块 + 「查看关联事项」；页脚「站内信仅用于系统通知，不支持在线回复」正对应一期不做 IM 的约束 |
| B6 | 控制台 / 网络 | ✅ 消息详情页 `failed`/`4xx`/`5xx` 查询返回 0 条。全站控制台无 error 由 Playwright `pageAudit` 覆盖（166 条全绿） |
| B7 | 合规红线抽查 | ✅ **通过**。用药页双层免责声明：「剂量由家属按医嘱录入，**系统不提供任何用药建议**」+「本页仅提供药品通用信息，**不构成任何用药建议，请遵医嘱**」。全站无 `建议服用/推荐剂量/诊断为/可能是`。手机号、姓名脱敏见 A-02 |

**视觉质量判断**：订单卡、消息卡、用药页、我的页四屏均无布局错位、无溢出、无骨架屏卡死；状态徽章配色语义一致（已评价=灰蓝、待接单=橙点）；空态有引导 CTA（「今天没有服药任务 / 去『计划』tab 新建用药计划」）。

---

## 九、数据状态

`fixture.py verify` → **全部回到基线 ✅**（exit=0）。

| 表 | 当前行数 | 说明 |
|---|---|---|
| `sys_user` | 123 | 含 30 个 JMeter 压测残留账号（`bench5026`~`bench5030` 等，见下） |
| `companion_order` | 64 | 6 种状态齐备 |
| `internal_message` | 231 | = 基线 |
| `sys_file` | 90 | = 基线 |
| `order_checkin` / `order_review` / `complaint` | 273 / 31 / 32 | = 基线 |
| `medication_plan` / `medication_task` | 66 / 588 | = 基线 |
| `_e2e_bak_*` 影子表 | **0** | 已随 verify 清理 |
| `backend/uploads` | 20 个文件 | = 基线 |

### 与开跑前的差异（如实说明）

`companion_order` 开跑前 68 条、现 64 条，**少 4 条**；`order_status_log` 少 7 条。

原因：Phase 1 采用了你选定的「跳过破坏性清理」方案，基线取自被历史测试污染的库。L2 脚本在清理自己的测试订单时，一并删掉了 4 条低于水位、且**不在影子表白名单内**的既有订单 —— `fixture.py` 的回滚只能重插影子表里的白名单行，无法恢复这 4 条。

这 4 条属于历史压测/测试产生的高位污染单（`companion_order` 开跑前 max_id 为 17804，种子区间是 1001–1064），不是种子数据。当前库处于**比开跑前更干净**的状态，订单 6 态与所有 `SEED` 关键 id（1001/1007/1019/1031/20002/401/431）均可查。

---

## 十、遗留风险与建议

| # | 项 | 级别 | 建议 |
|---|---|---|---|
| 1 | ~~A-02 订单详情姓名未脱敏~~ | ✅ 已关闭 | 经核实为**刻意设计**（访问受 `requireInvolved` 把守 + 契约明写「此处姓名返回全名」）。已在 **AGENTS.md §0.1.2 补写该场景豁免**，代码不动 |
| 2 | ~~`restore_clean_baseline.ps1` 三处缺陷~~ | ✅ 已修 | 已用 **Python 重写**为 `tools/e2e/restore_clean_baseline.py` 并删除 `.ps1`。三处缺陷逐条修掉：① 兜底 SQL 改为**从种子 SQL 解析真实基线**，解析不出就跳过不猜；② 运行时查 `information_schema` 与候选表取交集，3 张不存在的表自动剔除；③ stderr 原样打印、**失败即 exit 非 0**。另加阈值守卫（单表待删超 30% 或 500 行需 `--force`）与 `MYSQL_PWD`（口令不进 argv）。`.sh` 版**已删除**（2026-10-02）：与 Python 版功能重复，且经实测其 `MAX(id)-100` 兜底会误删 **629 行种子数据**（`companion_order` 64 行种子订单、`order_review` 31 条、`complaint` 32 条、`admin_oper_log` 37 条等整表清空），保留它唯一的价值就是被误执行 |
| 3 | `start-services.ps1` 不校验端口身份 | 🟠 | 这是 2026-09-29 那 45 条假红的根因。建议把本次的 5 项身份断言固化进去 |
| 4 | `probe_backend_gaps.py` 缺 `BASE` 常量 | 🟡 | 一行修复，即可解锁上传/CHECKIN/魔数等全部写路径探针 |
| 5 | `e2e_medication.py` `cleanup()` 缺 `b5_order_id` 初始化 | 🟡 | 模块级补 `b5_order_id = None`；当前会导致 `--scan-phase` 清理中断、数据泄漏 |
| 6 | `e2e_auth.py` D6–D11 打不存在的 perm-probe 端点 | 🟡 | 改打真实端点，或删除（Playwright `02-access-matrix` 已覆盖） |
| 7 | 库中 30 个 JMeter 压测账号 | 🟡 | `bench5001`~`bench5030` 会出现在管理后台用户列表与家属消息里（如 `陪****1`）。属数据卫生问题，非脱敏缺陷（`MaskUtil.name("陪诊员5001")` 行为正确） |
| 8 | `elder030` 库漂移为 `NORMAL` | 🟡 | 种子定义为 `DISABLED`。产品校验已自证正常，但 `e2e_auth.py` B5 会持续失败，直到库与种子对齐 |
| 9 | `MOBILE_ONLY_ROUTES` 常量残缺 | 🟢 | `e2e/helpers/constants.js:89` 只列 2 条，`routes.js` 实际 6 条，且该常量**无任何消费方**。当前不影响产品（读的是 `meta.mobileOnly`），但日后谁拿它驱动断言会静默漏 4 条 |
| 10 | 文档口径待更新 | 🟢 | `FRONTEND_CONTRACT` §12.3 写「166 条」，本轮实测确认为 166 条（静态 `test()` 计数 155，差值来自循环生成）—— 口径正确，可补一句说明 |

---

## 十一、产物索引

| 路径 | 内容 |
|---|---|
| `reports/playwright/results.json` | L1 逐条结果（166 条） |
| `reports/playwright/html/` | L1 HTML 报告，`pnpm exec playwright show-report ../reports/playwright/html` |
| `reports/playwright/test-results/` | 失败取证 trace / screenshot（本轮无失败） |
| `reports/e2e/fixture/state.PRE-RUN-BASELINE.json` | Phase 1 开跑前基线备份 |
| `backend/src/main/java/org/company/nianglin/service/impl/AdminServiceImpl.java` | A-01 修复 |

> `reports/` 含真实 JWT，已被 `.gitignore` 忽略，**严禁入库**。
