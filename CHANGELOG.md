# Changelog

本文件记录每个版本的变更。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号对齐 `docs/plan/iteration-plan.md` 的 Vx.y 里程碑（**不是 Git 标签**）。

> **Git 标签 vs 本文件：**两者**是两套口径**，且历史上已经 off-by-one——
> `git v4.0` = `[V3.7]`、`git v5.0` = `[V4.0]`、`git v6.0` = `[V5.0]`。
> 完整映射表与口径声明在 [`docs/agents/git-workflow.md`](docs/agents/git-workflow.md) §7.4.4：
> Git 标签是**代码快照锚点**，本文件是**产品版本口径**，两者**不要求同号**；
> 引用版本数字时必须注明是哪一种口径（「Git 标签 v6.0」 vs 「CHANGELOG V5.0」）。
> 自 2026-11-18 起 Git 标签改为**双层**：`vX.0` 里程碑 + `vX.Y`（Y ≥ 1）功能单元，从 `v6.1` 开始。

> 与 `docs/api/README.md §变更记录` 的分工：那边只记**接口契约**的变更（端点、错误码、字段），
> 本文件记**全部**变更，包括内部重构、测试与文档。两者可交叉引用，但本文件不替代接口变更记录。

---

## [Unreleased]

### Added · E4 评价公信力闭环：管理端两个裁定入口落地

上一轮做文档路径对账时如实标注了一个功能缺口：后端 `POST /api/admin/review/{id}/validity`
（评价有效性裁定）早已上线，`frontend/src/api/admin.js` 也封了 `rulingReview`，
但**全仓没有任何 .vue 调用它** —— 管理员看得见评价，却没有一个入口说「这条评价不算数」。
本轮把它补成端到端可用。

**新增后端端点 · `GET /api/admin/review`（评价巡查列表）**

- `docs/api/08-admin.md` §29 · 权限 ADMIN · 只读不写操作日志 · **零迁移**
- **为什么要新端点**：`GET /api/review/companion/{id}` 是「公开口碑」链路，
  硬编码只返回**有效**评价，管理员用它**永远看不到已被裁定无效的** ——
  而「裁完就从列表里消失」的评价等于从来没被巡查过。
  该端点不在 `/api/admin/**` 下，**不能**改它（`SecurityConfig` 只对 `/api/admin/**`
  做 `hasRole(ADMIN)`，改了会扩大公众可见性边界）
- `isValid` 三态：不传 = 全部 / `true` = 只看有效 / `false` = 只看已被裁定无效。
  「只看有效」的口径是 `is_valid = 1` **或** `is_valid IS NULL`，与 `ReviewVO.of` 的老数据兜底一致
- 新增 `AdminReviewQuery` / `AdminReviewVO`；`AdminReviewVO` 字段名与 `ReviewVO` 刻意对齐
  （两个入口把评价对象喂给同一个对话框组件，字段名分叉就得写两套取值判断）
- 姓名一律脱敏 —— 订单详情那条「全名豁免」只对详情页成立

**新增前端 · 两个裁定入口，共用一个对话框组件**

- `frontend/src/components/admin/ReviewRulingDialog.vue` —— 共用组件
  （口径 / 10–200 字校验 / in-flight 锁 / 单向裁定提示只此一份）
- `frontend/src/views/admin/complaint.vue` —— `REVIEW_APPEAL` 行加「裁定无效」按钮。
  评价 ID 的来源：`complaint` 表**没有** `review_id` 列（E4 零迁移），
  用 `complaint.orderId` 反查 —— `GET /api/review/order/{orderId}` 的归属校验
  `requireInvolved` 对 ADMIN 直接放行
- `frontend/src/views/admin/review-audit.vue` —— 评价巡查页（陪诊员 / 评分 / 有效性 / 时间四类筛选）
- `frontend/src/router/routes.js` —— `admin/review-audit` 一条（`W-14`）

**测试**

- 后端 `AdminServiceReviewListTest`（12 例）+ `AdminControllerReviewListTest`（2 例）；
  `AdminServiceImpl` 新增构造器参数，4 个既有测试类同步适配
- 前端 `ReviewRulingDialog.test.js`（7 例）
- `mvn -B verify` → **1564 例 / 0 失败**；`pnpm run lint:check` → 0 警告；
  `pnpm run test` → **27 文件 / 269 例全绿**

**文档同步**

- `docs/api/08-admin.md` §二 端点表 +1 行、新增 §29
- `docs/spec/E4-review-credibility.md` §2.5（新增端点契约）、§8.3（端点签名）、
  §10.1 / §10.2（前端交付状态）、§十的「未交付」警示块改为「已补齐」
- `docs/spec/E4-tickets.md` T5 → ✅ 已交付
- `docs/prd/功能拓展规划.md` 附录 A · RTM R-E4 → 标注管理端两个入口已交付
- `docs/agents/interaction-checklist.md` §2.7 / §2.8 / §4.10 三条新陷阱
  （多入口口径收敛 / 单向操作不给反向选项 / Boolean 筛选的「不限」怎么传）

### Fixed · 文档路径对账：10 处悬空引用清零 + 门禁改全量

一次性清掉仓库里 10 处「文档引用已删除 / 改名 / 从未落地的文件」。它们都是历史遗账，
不在任何 diff 里，因此增量口径的 CI 永远碰不到 —— 单独一轮处理。

**A 类 · 真漂移（改引用，指向现在的位置）**

- `docs/plan/README.md` —— 「原 `plans/迭代计划.md`」「原 `plans/ui-rework.md`」两处括注：
  `plans/` 下这两个文件在文档收敛（`4d6b4f0`）时删除，说法改为「前身是迭代计划」而不裸引路径
- `docs/prd/功能拓展规划.md` §6.3 —— 交付物「新增 `docs/api/10-settlement.md`」从未落地：
  结算接口最终**并入既有文件**（`08-admin.md` §25–§27 + `09-statistics-export.md` §9），
  `10-` 编号后来给了 `10-file-upload.md`，文档已改正
- `docs/spec/desktop-adapt-v2.md` §4.1 —— `NlDialog.vue` 计划里写了却**最终没建**：
  改用 EP 原生 `el-dialog` + `useResponsive().isXs` 逐页绑 `:fullscreen`，表格里如实标注
- `tools/ai_flow/README.md` —— 补充「为什么 CI 改全量」的理由与两处目录级豁免

**B 类 · 历史快照（不动原文，改为目录级豁免）**

- `docs/plan/archive/2026-09-28-convergence-iteration.md`（3 处）—— 计划存档里
  `git rm PermissionProbeController.java` 的**执行步骤原文**；探针确实在 `038723a` 删了
- `docs/review/2026-09-17-m2-auth.md`（1 处）—— 评审记录引用被评审那天的代码位置

> 判据与既有的 `CHANGELOG.md` 豁免同源：快照类文档引用的是「当时」的路径，
> 让它随今天改名而失效等于篡改历史。**正确做法是新文档指向现在的位置。**

**C 类 · 功能缺口（文档如实标注，代码留待立项）**

- `docs/spec/E4-review-credibility.md` + `E4-tickets.md` —— 后端
  `POST /api/admin/review/{id}/validity` 已上线、`api/admin.js` 也封了 `rulingReview`，
  但**全仓没有任何 .vue 调用它**：`admin/review-audit.vue` 从未存在（`git log --all` 零命中），
  `admin/complaint.vue` 也无裁定入口 —— 「管理员裁定」当前只有 API 可达，管理端 UI 为零
- 同一处标注 `tools/e2e/e2e_review_appeal.py` **未交付**（spec T7 承诺的 5 条断言）；
  现有覆盖靠 `ReviewRulingServiceTest` / `AdminControllerReviewTest` / `ReviewAccessMatrixTest`，
  缺的恰是「真 HTTP + 跨进程」那层
- `docs/plan/gap-closure-2026-11.md` —— 四条链活动图**本轮不交付**（用户裁定，
  `CHANGELOG` 早有记录），原文按「新文档」列着却从未产出，已改为划线 + 决策说明

**门禁：增量 → 全量**

- `tools/ai_flow/gate_check.py` —— 新增 `PATH_CHECK_EXEMPT_DIRS = ("docs/plan/archive/", "docs/review/")`
  与 `path_check_exempt()`；**只豁免路径判定，围栏检查照旧**。`CHANGELOG.md` 的单文件豁免并入同一函数
- `.github/workflows/ci.yml` 的 `static-gates` job —— 文档校验从
  `--only md --base "$BASE..HEAD"` 改为 **`--only md --all-md`**；连带删掉
  「`base.sha` → `before` → `HEAD~1` 三级兜底」那段（它容易静默走 `exit 0`，
  表现是「门禁看着绿其实什么都没验」）与 `fetch-depth: 0`。全量 140 份 .md 实测 **1.5s**
- `tools/ai_flow/test_gate_check.py` —— 新增 `PathCheckExemptTest`（6 例）：
  存档 / 评审目录豁免、`CHANGELOG.md` 旧豁免不被误伤、普通文档不豁免、
  `docs/reviewsomething/` 不被前缀咒语命中、端到端确认存档文件的悬空不进结果。
  回归测试 **9 → 15 例**
- 门禁现状：全量 md 扫描 **140 份 / 悬空 0 处 / 围栏 0 处不闭合** · 门禁自测 **15 / 15**

> 遗留（本轮不动，待立项）：E4 裁定入口的前端落点、`tools/e2e/` 的跨进程 e2e 脚本。

### Changed · UI 走查三项待决项定案（N1 / N2 / N3）

- **N1 触控区取 44px（普通形态）** —— 44px 是 WCAG 2.2 / Apple HIG 的通行下限；
  48px 会把列表每行再撑高 24px，与 VISUAL_DENSITY=6 冲突。
  **老人模式的 ≥48px（主按钮 56px）是另一条硬约束，不变。**
  规范同步到 [`docs/design/design.md`](docs/design/design.md) v1.3 与
  [`docs/agents/frontend-conventions.md`](docs/agents/frontend-conventions.md)
- **N2 表格操作列点击区** —— 窄屏 <768px 提到 **44px**（`NlAdminTable` 卡片操作区）；
  桌面 ≥1280 **维持 EP 默认 24px**：想在不改行高的前提下提到 32px 做不到
  （`.el-table .cell` 的 `overflow: hidden` 会把外扩的命中区一起裁掉），
  真要 32px 只能让 13 个管理端页面的表格整体行高 +8px，密度代价过大
- **N3 管理端在手机形态的定位** —— **桌面优先 + 窄屏兜底不破版 + 一次性提示**，
  不硬拦截、不做移动端专用重排：管理员在门诊现场用手机处理投诉 / 仲裁是真实路径，
  拦截会直接堵死业务。窄屏提示条已落地（可关闭，localStorage 记忆）
- 落地文件：`frontend/src/components/NlAdminTable.vue` ·
  `frontend/src/layouts/AdminLayout.vue` · `frontend/src/utils/auth.js`
- 新增单测：`frontend/src/utils/__tests__/auth.test.js`（4 例，提示条关闭状态读写）
- 门禁：`pnpm run lint:check` 0 警告 · vitest **262 / 262** 全绿（26 个文件）

### Fixed · 移动端触控区与管理端窄屏溢出（PR #37）

- 管理端顶栏在 390px 下撑出视口（13 个页面全中，溢出 +10~17px）→ 无溢出；
  窄屏隐藏老人模式开关与用户昵称 / 角色标签，页面标题超长省略
- 老人档案卡「解绑 / 删除」由 24px 竖排小按钮改为卡片底部整行 44px 等分操作栏
- 家属 / 陪诊员订单列表筛选项 31px → 44px，补右侧渐隐提示；陪诊员端补 `overflow-x`
- 全局移动端规则补 `.el-button--small` 的 44px 兜底 —— 原规则刻意豁免 `--small`，
  列表里的「删除 / 查看 / 抢单」普遍只有 24px
- 走查记录：[`docs/reports/UI快速走查与修复记录-2026-11-23.md`](docs/reports/UI快速走查与修复记录-2026-11-23.md)
  （51 路由 × 2 视口 = 102 快照）

### Changed · 前端依赖升级

- `axios` 1.20.0 → **1.21.1**（PR #32）
- `vitest` 5.0.1 → **5.1.0**（PR #30）
- `@vitejs/plugin-vue` 5.2.4 → **6.0.11**（PR #31）

三项均由 Dependabot 提 PR，经人工审查后合并：改动只落在 `frontend/package.json`
与 `frontend/pnpm-lock.yaml`，不触碰任何业务代码。合并后按合并后的 lockfile 实跑
CI 同款命令：`lint:check` 0 警告 · vitest **258 / 258** 全绿 · `build` 成功。

### Fixed · Node 下限声明与依赖实况对齐

- `frontend/package.json` 的 `engines.node`：**22.10.0 → 22.12.0**
- README 前置条件表与 `docs/deployment.md` §1 环境要求表同步为 **22.12+**
- 原因：`@vitejs/plugin-vue@6.0.11` 要求 `^20.19.0 || >=22.12.0`，
  `vitest@5.1.0` 要求 `^22.12.0 || ^24.0.0 || >=26.0.0` —— **22.12 是两者的共同下限**。
  原声明 22.10 会让 22.10 / 22.11 的本地环境装上跑不起来的组合；CI 跑 Node 24 所以一直是绿的，
  问题只在本地装依赖后运行时才暴露
- 同步点见 [README 快速开始](README.md#快速开始) 与 [部署文档](docs/deployment.md)

### Fixed · 静态门禁「悬空路径」误判（本地绿 / CI 红）

- `tools/ai_flow/gate_check.py` —— 删掉手抄 `.gitignore` 的白名单
  （`GITIGNORED_PATH_PREFIXES`），改为 `git check-ignore` **问 git 本身**；
  同一路径多次引用只报一次
- 连带修掉一个只会在 Windows 复现的隐蔽坑：`git check-ignore --stdin` 的输入若带 CRLF，
  git 会把行尾 `\r` 当成路径的一部分导致匹配失败 —— 而 Python 的 `text=True`
  恰好在 Windows 上把 `\n` 翻成 `\r\n`。实现改走**字节**输入
- **影响面**：README 引用 `ai-service/.env`（按红线刻意不入库）此前会被判成「悬空路径」，
  于是**任何改动 README 的 PR 都在 CI 上红、而本地绿**
- **新增** `tools/ai_flow/test_gate_check.py` —— 门禁脚本自己的回归测试（9 例，纯 stdlib）：
  被忽略的文件不算悬空 / 真·不存在的路径仍然要报 / Windows 换行不能吃掉匹配
- `.github/workflows/ci.yml` 的 `static-gates` job 在跑完原有两项**之后**跑该回归测试 ——
  门禁自己有 bug 时门禁自己也会放行，顺序必须是「先验证门禁本身可信，再让它去验仓库」
- 规则本体仍然只有一处（就是 `.gitignore`），CI 与本地共用同一份脚本

### Docs · 版本标签规范落地

- `docs/agents/git-workflow.md` §7.4 —— 双层标签（`vX.0` 里程碑 + `vX.Y` 功能单元）
  + 触发判据 + message 模板 + 编号映射表
- `plans/git-tagging-plan.md` —— 标签方案的执行计划

### 门禁与规模

- 静态门禁全绿：密钥体检（1144 个入库文件）· 指标字典 parity（64 条）· 错误码双向一致（72 个码值）·
  文档三项校验 · **门禁脚本自测 9 / 9**
- 前端 vitest **262 / 262**（26 个文件）+ lint 0 警告 + build 成功
- 后端 surefire 实跑 **1550** 条全绿；AI 助手 pytest 全绿
- 规模数字取数：`python tools/repo_metrics.py --base v6.0`（基线 ref = **v6.0**）——
  端点 **138** / 表 **29** / Controller **25** / 后端主代码 **484** / 前端路由 **54** /
  E2E 用例实跑 **272**（23 个文件）

---

## [V4.0] · 2026-11-13 · 作品集化迭代：九项能力落地

把 V3.x 时代被「禁止引入外部依赖」阻塞的三件事 —— **找回密码**、**站外提醒**、**注销与数据导出** —— 解开，
并补上工程底色：测试欠账清零、漏服触达闭环、结算与体征、运营参数可调化。
规划见 [V4 迭代规划](docs/plan/iteration-plan-v4.md)；本段按**实际落地**记，不按草案记。

> 本段对应提交窗口 `v4.0..817a7c5`（131 个提交，2026-10-21 → 2026-11-13）。

### Added · P0-1 账号注销与个人数据导出

- `POST /api/user/deactivate` —— 家属 / 陪诊员；需**当前密码复验** + `confirmText` 固定串二次确认
- `GET /api/user/data-export` —— EasyExcel 流式导出（资料 / 绑定老人 / 订单 / 评价 / 消息），全过 `MaskUtil`；
  **仅本人**，userId 取自令牌，**禁止入参指定**
- **有进行中订单时拒绝注销** —— 新错误码 `11001`，`data` 带 `pendingOrderCount`
- 注销副作用：`sys_user.deleted=1` + `status=DISABLED`、`pwd:version` 递增（**旧 token 立即 401**）、
  姓名 / 手机号 / 身份证去标识化、家属绑定关系逻辑删除（老人档案保留）
- **实导实核**（2026-11-12）：`fam001` 导出 7202 字节 xlsx，解包全 XML 扫描 —— 完整手机号 **0**、
  身份证号 **0**、BCrypt 哈希 **0**；正向对照脱敏手机号 `138****0001`、脱敏姓名 `张*海` 存在

### Added · P0-2 漏服提醒触达闭环（E1a）

- 抽 `ReminderDispatcher` 策略接口，`InternalMessageReminderDispatcher` 为默认实现（站内信）
- `notifyFamily` 拆出 `notifyOnDutyCompanion` —— 当天有 `ACCEPTED / IN_SERVICE` 订单时，
  漏服提醒**同时**投递该单陪诊员；无陪诊订单时行为与现状一致
- 新消息类型 `MEDICATION_MISSED`，`link_url` 指向订单详情
- 30 分钟扫描节奏与 Redis 锁粒度**不变**（既有压测结论不失效）；提醒文案过合规词表（禁含剂量）

### Added · P0-3 测试欠账清零

- 新增 `MedicationSchedulerTest` 补 `task` 包覆盖 —— 2026-11-12 实测 `task` LINE **76.60%**、
  `service` LINE **79.10%**（门禁 60%）
- e2e 脚本统一清理收尾 —— 修 `internal_message` 残留导致**连跑第 2 遍假失败**（BUG_LIST **L1**）
- E10 乐观锁 `version` 断言口径漂移订正（BUG_LIST **L7** → 已修复 #14）

### Added · P1-1 自助找回密码（短信通道接入）

- `POST /api/auth/sms-code` + `POST /api/auth/forgot-password`
- 新错误码 `1008`（验证码错误或已过期）/ `1009`（操作过于频繁）
- 验证码存 Redis、**5 分钟过期**、连错 **5 次**作废；手机号不存在与存在**同响应**（防枚举）
- 重置成功 → `pwd:version` 递增，旧 token 全失效
- 通道抽象 `SmsSender`（`AliyunSmsSender` / `MockSmsSender`）+ `SmsTemplateRouter` 按场景路由；
  `SmsProperties` **启动期校验**，模板配错即启动失败（不静默降级）

### Added · P1-2 陪诊 → 用药贯通（就诊小结转用药计划草稿）

- `POST /api/medication/plan/from-order/{orderId}` —— 仅本单下单家属（他人 `3004`）
- 草稿**不含剂量**（红线-1）：只带药品名 + 频次 + 计划时间，剂量由家属在正式计划页手工录入
- 草稿**不落 `medication_plan` 正式表**（复用前端 `orderDraft`），家属确认后才调 `POST /api/medication/plan`
- 重复转入改用专用错误码 `11002` + Redis **30 分钟**判重（草稿不落库，故未做永久性一单一转）
- 放开 `REVIEWED` 订单转入；小结含合规词时仍由 `3007` 拦截

### Added · P1-3 站外提醒（短信）

- `V8__sms_notification.sql` —— 偏好 / 发送回执 / 幂等 / 死信四表
- 提醒开关落在**按用户**的 `notification_preference`（`GET` / `PUT /api/message/notification-preference`）——
  经 2026-11-12 复核，粒度定为**按用户全局开关**，不实现按计划逐条配置与语音外呼
- 短信编排：幂等键（含 userId）/ 静默时段 / 指数退避 / 死信 / 日频控；
  **站内信发送失败不阻断站外短信**
- 静默时段同时尊重家属个人开关（与全局开关取**与**）；`SmsRetryScheduler` 负责退避重试
- 用药任务删除时作废未发送回执，频控只计有效任务

### Added · P2-1 结算闭环收口

- `GET /api/admin/settlement`（核对台账）· `POST /api/admin/settlement/{orderId}/confirm`（单条核定）·
  `POST /api/admin/settlement/batch-confirm`（批量逐项）· `GET /api/settlement/export`（结算单导出）
- 新错误码 `8004`（订单状态不可结算核对）；核定写 `admin_oper_log`（新增操作类型）
- 导出列不含敏感字段；文案无「提现 / 支付」字样（**在线支付红线**不变）
- 金额口径 `actual_fee` 缺失时回退 `fee`（已写进接口文档）
- spec：[V4-P2-1 结算闭环](docs/spec/V4-P2-1-settlement-spec.md)

### Added · P2-2 健康档案结构化（体征记录）

- `V9__vital_sign_record.sql` —— 1 张表 + 去重唯一键
- 家属 4 端点：`GET` / `POST /api/family/elder/{elderId}/vitals`、
  `GET /api/family/elder/{elderId}/vitals/trend`、`DELETE /api/family/vitals/{recordId}`
- 老人 2 端点（**只读**）：`GET /api/elder/vitals`、`GET /api/elder/vitals/trend`
- 陪诊员 3 端点：`GET` / `POST /api/execution/{orderId}/vitals`、
  `DELETE /api/execution/{orderId}/vitals/{recordId}`
- 权限口径：ELDER 写请求 **403**、未映射写方法 **405**；
  归属校验下沉 Service（家属 A 不能录家属 B 的老人）
- **只做记录 + 趋势展示，无任何评估 / 预警文案**（红线-1）
- 前端：`components/health/{VitalSignForm,VitalTimeline,VitalTrendChart}.vue` + 家属 / 老人 / 陪诊员三端入口；
  静态设计稿 [p2-2-vitals-design](docs/design/p2-2-vitals-design.html) + 浏览器验收截图
- spec：[V4-P2-2 体征](docs/spec/V4-P2-2-vitals-spec.md)

### Added · P2-3 运营参数可调化

- `V10__operation_param.sql` —— 只写 `sys_dict` 种子行，**不改表结构**（`ON DUPLICATE KEY` 幂等）
- `GET /api/admin/operation-param` · `PUT /api/admin/operation-param/{code}` ·
  `GET /api/admin/operation-param/logs`（留痕分页）
- `OperationParamCache` 缓存 TTL **≤ 60s** 热生效；**优先于 `application.yml` 与环境变量**
- 迁字典 **4 项**：漏服阈值 / 催单限频 / 导出上限 / 短信日频控
- 只搬运营阈值，不搬技术配置（数据库连接等）；字典变更留审计

### Changed · V3.7 之后的工程基建轮（2026-10-21 – 10-25）

> 助手侧（`ai-service`）的评审修复与 FAQ 问法池已并入上方 **[V3.7]** 段落，此处不重复记账。

- **UX 交互审计轮** —— 真实「记住我」、共享校验与二次确认助手（`utils/validation.js` / `confirm.js`）、
  路由守卫与 keep-alive key + 导航徽标、家属下单流程、陪诊员大厅 / 执行 / 工作台、老人页三态与空态、
  管理端列表分页与空态逻辑抽共享（`composables/usePagedList.js` / `utils/pagination.js`）；配套 Playwright 回归守卫
- **e2e 工具化** —— L0 服务身份闸门、A-2 可达性脚本、A-1 越权矩阵探针、探针令牌过期识别、
  ws 归因前置探测、captcha 竞态容忍
- **`tools/repo_metrics.py`** —— 仓库规模指标一键取数（引用规模数字前先现取，勿手抄）
- **`AGENTS.md` 拆分** —— 根目录只留红线 / 导航 / 底线 / 验收清单，细则落到 `docs/agents/`（11 份主题文件）
- **安全修正** —— 管理员用户列表姓名脱敏（`AdminUserVO`）；密码重置结果如实回显（`ResetPasswordResultVO`），不吞失败

### Changed · 根目录文档收敛（2026-11-12）

- **根目录只保留四个文件**：`README.md` · `AGENTS.md` · `GLOSSARY.md` · `CHANGELOG.md`。其余文档收敛进 `docs/`：
  `银龄伴诊项目计划书.md`（含 `_files/` 配图）→ `docs/plan/`、`验收报告.md` → `docs/reports/`、
  `PLAN.md` → `docs/plan/gap-closure-2026-11.md`、`script.md`（本地汇报脚本）→ `docs/reports/script.md`。
- 同步修正引用与索引：`AGENTS.md` / `README.md` 目录树、`docs/README.md`、`docs/plan/plan.md`、
  `docs/deployment.md`、验收报告自引用、`presentation/` 本地脚本引用。

### Fixed · 代码欠账收口（2026-11-12 · `feature/plan-gap-closure`）

- **BUG_LIST L2**：陪诊员评分快照改为**单条原子 `UPDATE`**（新增 `CompanionScoreSnapshotMapper`，
  `AVG`/`COUNT` 子查询与写入同语句，口径 `is_valid=1 AND deleted=0`），消除「先读后写」丢失更新窗口；
  评价三个测试类断言同步迁移（commit `7889b68`）。
- **BUG_LIST L3**：`application.yml` 显式声明 `nianglin.export.max-rows: 10000`，与三处 `@Value` 回落值对齐
  并注明 OPS_PARAM 在线优先（commit `13ba01f`）。
- **BUG_LIST L6**：解除 —— 2026-11-12 实测 `service/**` LINE **79.10%**、`task` **76.60%**（门禁 60%）。

### Added · 计划书交付物（缺口轮）

- **`docs/user-guide.md`** —— 用户手册：家属 / 陪诊员 / 老人 / 管理员四角色操作步骤 + 现有截图，
  含合规声明与 FAQ（计划书迭代三/五交付物，此前全仓缺失）。
- **`docs/deployment.md`** —— 部署文档：本机 dev 五进程启动 / prod fail-closed 配置 / 备份恢复 / 排障表
  （计划书迭代五交付物）；Docker 一键部署经确认**不交付**，如实列为二期展望。

### Changed · 文档状态与验收收口（2026-11-12）

- `docs/prd/功能拓展规划.md` 附录 A RTM 逐条对代码核验：**8 条全部已落地**（v1.1，含 G4/G7 解锁）。
- `docs/plan/iteration-plan-v4.md` §7、`V4-P2-1` / `V4-P2-2` spec 验收清单逐项核验勾选（证据入注）。
- `docs/spec/desktop-adapt-v2.md` 三个待跑 playwright spec 实跑勾选；人工手测项如实标注未做。
- `.github/workflows/ci.yml` 注释口径对齐当前实测（surefire **1520** / vitest **251·23 文件** /
  `@SpringBootTest` **29 处** / **113** 个 `*Test.java`）。
- 验收报告（当时在根目录，现 `docs/reports/验收报告.md`）刷新复核轮：硬门禁全绿（L0 10/10、Playwright 272、越权矩阵 100 次 0 违规、
  导出文件敏感字段扫描 0 命中），结论升为**通过**（影片/PPT 相关项标注范围外）。
- `docs/README.md` 索引新增 user-guide / deployment 两行。

> 注：计划书迭代一交付物「活动图」经用户裁定**本轮不交付**，不计入缺口。

把散落在仓库各处的文档收敛到 **根目录三入口 + `docs/` 分区** 的结构。

### Changed · 文档归档

- **根目录只保留三个入口**：`README.md`（对外基线）· `AGENTS.md`（AI 协作规范）· `GLOSSARY.md`（领域词汇表）。
  `CHANGELOG.md` 按通用惯例留在根目录。
- **`docs/plan/plan.md`** —— 原根目录 `plan.md`（模块拆分 + 验收标准），与 PRD 同层归入工程计划区。
- **`docs/design/design.md`** —— 原根目录 `design.md`（前端设计规范，视觉唯一真源）。
- **`docs/reports/`** —— 原根目录 `TEST_REPORT.md` + 原 `docs/agents/reports/`（21 份测试 / 基线 / 压测报告）合并为单一报告区。
- **`docs/plan/iteration-plan.md`** —— 原 `plans/迭代计划.md`（V3.x 迭代规划）。
- **`docs/plan/ui-rework.md`** —— 原 `plans/ui-rework.md`（R4 / R5 / R6 UI 重构计划）。
- **`docs/plan/archive/`** —— 原 `docs/superpowers/plans/`（已执行完毕的收敛迭代实施计划存档）。
- **`docs/README.md`** —— 新增：文档总索引（分区表 + 阅读路径 + 命名约定 + 维护约定）。

### Changed · 术语表唯一真源

- **`GLOSSARY.md` 现在是术语表正文**，原 `CONTEXT.md` 的全部术语内容已并入，**`CONTEXT.md` 予以删除**。
- 原因：本机安装的工程技能（`/domain-modeling` 写入方与 `/tdd`、`/diagnosing-bugs`、`/improve-codebase-architecture`、`/wait-what`、`/pr`、`/codebase-design` 读取方）**读写两端都硬编码 `GLOSSARY.md`**。原先「`CONTEXT.md` 为正文、`GLOSSARY.md` 为别名指针」的安排已与实际技能契约漂移 —— 读取方拿到的是指针而非术语。
- `docs/agents/domain.md` 与 `AGENTS.md` §Agent skills 同步改为「唯一真源，无别名文件」。

### Fixed · 引用一致性

- 全仓文档引用随文件移动同步重写（Markdown 链接按相对位置几何重算，非字符串替换）。
- 顺带修好 14 处历史断链（原指向 `plans/迭代计划.md`、`docs/agents/reports/` 的失效链接）。
- `backend/sql/tools/`、`frontend/`、`ai-service/` 中指向文档的注释与路径字面量同步更新。

> ⚠️ **未改动**：`docs/api/**`、`docs/spec/metric-dictionary.md`（CI 门禁真源）、`docs/db/**`（脚本生成物）——
> 这三处被 CI 与后端工具硬编码引用，迁移会静默失效。

---

## [V5.0] · 2026-11-16 · 三阶段提醒闭环与工程工具链

在 V4 的九项能力之上补三条**主动触达**链路 —— 漏服多级升级 / 检查准备提醒 / 健康证到期 ——
并把「找回密码去短信化」这条 ADR 决策落地，最后接上 AI 日常工作流工具层与静态门禁。

> 本段对应提交窗口 `817a7c5..d1d7f43`（37 个提交）。

### Added · 阶段 A · F2 漏服多级升级（V5 · A1-A4）

- `V11__medication_notify_level.sql` —— `medication_task` 增 `notify_level` 列
- **三级阶梯**：30 分钟 → 家属 · 2 小时 → 陪诊员 · 24 小时 → 管理员
- 新消息类型 `MEDICATION_ESCALATED`；升级级别从 Java 侧解析，**不开新列**
- 短信**按级去重**：`dedup_key = medication-missed:{taskId}:{level}:{userId}:{yyyy-MM-dd}`
- 每级日上限默认 **3**（每级各一份）；运营参数启用时**优先于**代码默认值
- 门禁全绿 **1540 / 1540**

### Added · 阶段 B · F1 检查准备提醒（V5 · B）

- `V12__exam_prep_dict.sql` —— `EXAM_PREP` 静态字典种子 **6** 条
- `GET /api/exam-prep` —— 契约见 [14-exam-prep](docs/api/14-exam-prep.md)
- 新消息类型 `ORDER_EXAM_PREP`；就诊**前一天**站内信提醒（`ExamPrepScheduler`）
- FAQ 补 **3** 条、问法池补 **15** 条（确定性检索验收全绿）
- 评审修复轮：LIMIT 下推、失败释放幂等键、每小时重扫、按受众文案
- 门禁全绿 **1550 / 1550**

### Added · 阶段 C · F3 健康证到期校验与提醒（V5 · C1）

- 新错误码 `2011`（健康证已过期）；陪诊员申请可录入到期日 `healthCertExpire`
- 新消息类型 `HEALTH_CERT_EXPIRING` + `CompanionCertExpireScheduler` 到期前站内信提醒

### Changed · 找回密码去短信化（ADR-0021）

- 验证码由**后端直接回传**、前端自动填充 —— 流程不再依赖真实短信通道
- 删除 `SmsScene.VERIFICATION` 与 `ALIYUN_SMS_TEMPLATE_CODE_VERIFICATION` 两处短信依赖
- 验证码 TTL **5 分钟**、连错 **5 次**作废
- 频控三层：同手机号 **60 秒 1 次** / 同 IP **1 小时 20 次** / 同手机号 **1 自然日 10 次**
- 决策记录：[ADR-0021](docs/adr/0021-forgot-password-code-direct-return.md)（已接受 · 2026-11-13）
- 门禁全绿 **1550 / 1550** + e2e **16 / 16**

### Fixed · V4 + V5 联调三缺陷

- **undefined 请求** · **注销笔数缺失** · **401 弹窗堆叠** —— 逐条修复并复验
- ego-browser 实测 **11 项全通过**，3 缺陷记录在案：
  [联调测试报告](docs/reports/intl-test-v4v5-2026-11-14.md)
- 门禁全绿 **258 / 258** + lint + build

### Added · 两份测试方案文档

- [UI 测试方案](docs/reports/UI测试方案.md) · [功能测试方案](docs/reports/功能测试方案.md)

### Added · AI 日常工作流工具层与 static-gates CI job

- `tools/ai_flow/` —— `gate_check.py`（静态门禁）· `handoff.py`（交接）· `impact_map.py`（影响面）
  + [README](tools/ai_flow/README.md)
- `.github/workflows/ci.yml` 新增 `static-gates` job —— 错误码双向一致（`ResultCode` ↔ 接口文档总表）
  + 文档三项校验（代码围栏成对 / 引用路径真实存在）
- **规则本体只有一处**：CI 与本地跑的是**同一份** `tools/ai_flow/gate_check.py`
- `restore_clean_baseline` 水位定案 **40075 / 48572** + 对账文案收口 + 回归测试
- 种子口令白名单补 **2** 条

### Changed · 依赖升级

- `vue` 3.5.42 → **3.5.44**
- `@playwright/test` 1.63.0 → **1.64.1**
- `echarts` 5.6.0 → **6.1.0**
- `actions/setup-python` 5 → **7**

### Docs · 文档同步

- 新增 `docs/api/14-exam-prep.md`；`docs/api/` 01 / 02 / 03 / 05 / 07 / 08 与 `README.md` 同步
- 新增 [ADR-0021](docs/adr/0021-forgot-password-code-direct-return.md) 与
  [AI 日常工作流契约](docs/agents/daily-ai-workflow.md)
- `docs/db/**` 按真实库重新生成

### 门禁与规模

- 后端 surefire 实跑 **1550** 条全绿；前端 vitest **258** 条全绿 + lint + build
- `python tools/check_secrets.py` · `python tools/check_metric_dict_parity.py` ·
  `python tools/ai_flow/gate_check.py` 全绿
- 规模数字取数：`python tools/repo_metrics.py --base v3.0`（基线 ref = **v3.0**）——
  端点 **138** / 表 **29** / Controller **25** / 后端主代码 **484** / 前端路由 **54** /
  E2E 用例实跑 **272**（23 个文件）

### Known · 遗留（本轮未改）

- `.github/workflows/ci.yml` 文件头注释仍写「1520 条后端测试 / 251 条前端单测 / 113 个 `*Test.java` /
  28 处 `@SpringBootTest`」，而 V5 阶段实测已到 surefire **1550**、vitest **258**、
  `*Test.java` **117**、`@SpringBootTest` **29**
  （取数 `python tools/repo_metrics.py --base v3.0`）—— **CI 注释与真实口径漂移**，
  属 CI 配置文件，本轮改动范围未含，留待单独一轮处理
- `check_seed_password` 仍只在本地总闸门，**CI 未覆盖**

---

## [V3.7] · 2026-10-21 · 家属智能助手

让家属**用自然语言**问到平台规则，并看懂「为什么推荐这位陪诊员」。
产品与工程契约见 [PRD](docs/prd/PRD-V3.7-家属智能助手.md) /
[spec](docs/spec/V3.7-assistant-spec.md) /
[ADR-0020](docs/adr/0020-assistant-boundary-and-recommendation-guardrail.md)。

### Added · 新增

**独立 `ai-service`（Python 3.12 + FastAPI + LangChain）**

- `ai-service/app/` —— FastAPI 应用（`main` / `intents` / `knowledge` / `guard` / `llm` / `prompts` / `actions` / `service`）
- 端口 **8081**，**只监听 `127.0.0.1`**；浏览器只与 `/api` 说话
- **不配置 LLM 也能跑**：未配置 `LLM_BASE_URL` 时降级为**基础模式**，回答直接给 FAQ 标准答案原文（`degraded=true`）
- 决策链**写死在代码里**（输入护栏 → 意图分类 → FAQ 检索 → 事实装配 → 叙述 → 输出护栏），**不使用自主 Agent**

**后端门面**

- `POST /api/assistant/chat` —— **仅 `FAMILY`**（ELDER / COMPANION / ADMIN → 403）
- 新开 **10xxx** 错误码段：`10001` 提问过于频繁 / `10002` 智能助手暂时不可用 / `10003` 问题内容包含违规表述
- `config/AssistantProperties` —— 配置化 + **启动期校验**（配错即启动失败）

**共享合规词表抽出**

- `backend/src/main/resources/compliance/medical-advice-words.txt` —— 禁用词从 `ComplianceCheckUtil` 硬编码抽出为**共享资源文件**
- Java 与 Python **读同一份**，两侧都**启动期 fail-fast**（读不到 / 词数过少 → 启动失败）
- **命中即拒，不打码后放行**

**FAQ 知识库**

- `ai-service/knowledge/faq.json` —— **30 条**，每条带 `source` + `anchor` 指向 `docs/api` 真实锚点
- `tools/check_faq_anchors.py` —— 校验条目格式、锚点是否仍存在、答案是否含禁用词（**FAQ 过期会静默失真，这道闸门是硬失败**）

**前端**

- `views/family/assistant.vue` + `api/assistant.js` —— 家属端 `/family/assistant` 页面
- 路由 `module: M14` / `code: M-33` / `elderlyHidden: true`
- 助手**只填下单草稿**，**永不自动建单**；推荐叙述**只转述** `reasons` / `mismatches` / `notes` 三段

**CI**

- `.github/workflows/ci.yml` 新增 `ai-service` job（`setup-python` + pip 缓存 + `pytest tests -q`）

### Fixed · 评审收口（2026-10-21 第二轮）

> 详见 [评审报告](docs/review/2026-10-21-v3.7-assistant.md)。两轴共 **9 项确认缺陷**，全部已修并被测试锁住。

- ★ **LLM 路径从未真正跑过** —— `app/llm.py` 从 `langchain.prompts` 导入，而 **LangChain 1.x 已移除该模块**
  （模板类搬到 `langchain_core.prompts`），导入失败被兜底逻辑吞成 `NullProvider`：
  模型配得好好的，回答却永远是 FAQ 原文，且**所有测试都绿、所有日志都说「已启用」**
- **`UNKNOWN` 不经大模型** —— 此前模型会拿着「没命中任何 FAQ」的事实**硬编一个答案**
  （实测编出五步下单流程，且 `sources` 为空）。改为固定澄清话术
- **推荐无候选不走模型** —— 此前模型会生成「**命中理由** - 现有事实中未提供具体陪诊员的画像…」这种三段空壳
- **输出护栏新增距离措辞拦截** —— 「离得近 / 就在附近 / 距离近」→ 纠正为「距离维度无法计算（陪诊员尚未填写坐标）」。
  该纪律原先只写在 prompt 里，**代码层零兜底**；而把「算不出来」说成「很近」会让**正在挑陪诊员的家属**据此做决定
- **草稿只在真的抽到就诊信息时产出** —— `elderId` 是会话上下文自动补的，不算「抽到」
- **草稿不再凭空补 `09:00:00`** —— 只找到日期没找到时刻时留空，让家属在确认页自己补
- **回答统一去除 Markdown 标记** —— 前端是纯文本渲染，不解析 Markdown
- **Java 本地兜底路径同样过派单措辞护栏** —— 此前只做医疗词表整段替换，契约要求的护栏只兑现了一半
- **限流提示显示后端给的真实原因** —— 统一显示「助手暂时没有响应」会让用户以为服务挂了
- **`check_faq_anchors.py` 改为读共享合规词表** —— 原先内嵌一份副本，违反「不要另抄一份词表」；
  且真源加词时这道门禁**不会红**

### Added · FAQ 关键词覆盖

- `ai-service/knowledge/faq-question-pool.json` —— **179 条**模拟问法（30 条 FAQ × 6 条，剔除 1 条医疗意图问法）
- `tools/gen_faq_questions.py` —— 生成与回填工具（**默认不联网、不写文件**）
- `ai-service/tests/test_faq_coverage.py` —— 覆盖门禁（召回 / 不抢占 / 无医疗意图 / 关键词上限 / 易混对）
- 关键词回填后，问法池召回率 **34.4% → 97.2%**，抢占 **0**
- ⚠️ 问法是**大模型生成的模拟问法，不是真实用户数据** ——
  PRD 风险表第 5 条「FAQ 覆盖面未经真实用户验证」**保持未关闭**，
  只能靠上线后的未命中问题回流关闭

---

## [V3.3 – V3.6 评审收口] · 2026-10-21 · 代码评审发现项修复

对 `c735693..de994b3`（V3.3 – V3.6 四版）做 Standards / Spec 两轴评审后的收口。
**零新增错误码**，全部复用既有段位。

### Fixed · 评审发现项

**匹配可解释性拆三段**（`CompanionMatchVO` 新增 `mismatches`）

- `MatchingScore#reasons()` 收窄为「**得分大于 0** 的命中理由」——
  原来 0 分且非降级的维度（「跨城市」「超出服务半径」「未服务过该科室」）
  会被当成「推荐理由」渲染给家属，**淘汰理由被说成了推荐理由**
- 新增 `MatchingScore#mismatches()` 承载「该维度得 0 分的原因」，
  `CompanionMatchVO.mismatches` + 前端 `order-detail.vue` 分行渲染
- `notes`（降级说明，因数据缺失没算出来的维度）语义**不变** ——
  「没法算」与「没命中」这两件不同的事仍然是分开的

**指标字典口径精度**

- `operations.gap.available_companion_count` / `operations.gap.companions_without_slots`
  的「统计范围」措辞与实现对齐：universe 明确为**资质通过（APPROVED）且当前在线（AVAILABLE）**
  的陪诊员（`OperationsMapper` 的过滤本就是有意为之：休息 / 离线的人不接新单）
- **口径值未变**（`caliberVersion` 保持 1）：原措辞「全部陪诊员中」比实现**宽**，
  是文档写得比实现松，不是实现算错了
- 人读面 §2.7 表格由 `tools/gen_metric_dict_human_v36.py` 重新生成，两面逐条一致

**其它**

- 大厅 `total` 改为截断**前**的真实总数（`paginate` 增加 `trueTotal` 参数）
- `recommendCompanions` 的归属校验与条数归一化从 Controller 下沉到 Service（G4）
- `MatchingServiceImpl` 的 NPE 守卫移到解引用**之前**
- `OperationsMapper.todayCount` 改用独立子查询（不再被区间 `WHERE` 套住）
- 履约率 / 完成率(按时) / 投诉率 / 平均评分 / 平均接单时间收敛到 `MetricFormulas`
  **唯一一份实现**（ADR-0017 决策 2）

### Added · 评审补漏

- **`GET /api/admin/metrics`** —— 指标字典只读导出（仅 ADMIN）。
  兑现 `V3.6-data-loop-spec.md` §二 的合同级预写（此前 spec 有、实现无、tickets 无删减留痕）。
  导出的是**口径定义**而非统计值，故不进 `metrics/endpoints.yml`
- `tools/_metric_dict_reader.py` —— 指标字典机器面的**零依赖**读取器（CI 不承诺装 PyYAML）
- `tools/fix_line_endings.py` —— 本地辅助脚本：检查 / 统一工作区行尾为 CRLF
  （Windows 上 `core.autocrlf=true`，用编辑器新建的文件默认写成 LF）。
  **本地工具，不是 CI 门禁**
- `tools/check_metric_dict_parity.py` 升级为**字段级**矛盾检测（原来只比 key 集合）
- `.github/workflows/ci.yml` 新增 `metric-dict` job；`secrets` job 改为调 `tools/check_secrets.py`
  （**单一真源**，不在 workflow 里抄第二份规则）
- **ADR-0018**（V3.3 陪诊员工作台：口径必须自带区间、字段名必须自带口径）
- **ADR-0019**（V3.5 平台运营驾驶舱：不给合成分、异常必须复用而不是另建）
  —— 补齐 roadmap G9「每版收口同步六处文档」里的 ADR 项

### Docs · 文档收口

- `docs/api/08-admin.md` §18 补「请求参数」小节（异常中心实际接收 `DateRangeQuery`）；
  §17 `todayOrderCount` 补「不随统计区间浮动」；新增 §24（指标字典导出）
- `docs/api/03-order.md` §13 补 `mismatches` 字段与三段语义说明；
  修正 v1.2.0 变更行误置于文件首行（标题之前）的排版缺陷
- `docs/spec/metric-dictionary.md` v1.4 → v1.5
- **V3.1 / V3.3 / V3.4 / V3.5 / V3.6 五份 spec 的状态头勘误** ——
  此前仍写着「待评审 / 未开工」，而四版早已交付
- `docs/db/*` 按真实库**重新生成**（25 表，字段数 / 索引数逐表零差异）
- `docs/agents/FRONTEND_CONTRACT.md`：`/admin/*` 9 页 → **11 页**；
  运营端 6 个端点从 `@/api/statistics` 迁到 `@/api/admin`（AGENTS.md §3.10）

### 门禁

- mvn -B verify（基线 1230 条测试 + 本轮新增）
- pnpm lint:check / vitest / build
- `python tools/check_secrets.py` · `python tools/check_metric_dict_parity.py`

---

## [V3.6] · 2026-10-20 · 数据闭环

让平台**通过数据持续提高服务质量**，而不是「统计完了就放着」。
产品与工程契约见 [PRD](docs/prd/PRD-V3.6-数据闭环.md) /
[spec](docs/spec/V3.6-data-loop-spec.md) /
[tickets](docs/spec/V3.6-tickets.md) /
[ADR-0017](docs/adr/0017-metric-endpoint-machine-contract.md)。

### Added · 新增

**端点 ↔ 指标机器面**

- `backend/src/main/resources/metrics/endpoints.yml` —— 声明「哪个端点返回了哪些指标」
- `util/MetricEndpointRegistry` —— 加载器
- `MetricEndpointContractTest` —— **三条双向校验**：
  ① yml 里的端点必须真实存在（反射扫描所有 `@RestController`）
  ② yml 里的指标必须在指标字典里
  ③ 指标字典里每条指标的端点必须在 yml 里出现
- `tools/check_metric_dict_parity.py` —— 指标字典**人读面 ↔ 机器面**逐条一致性校验

**指标计算器注册表**

- `service/support/metrics/MetricCalculator` + `MetricCalculatorRegistry`
  —— **同一个指标 key 只能有一个实现**；未注册的指标在效果对比里返回 400

**两个新端点**

- `GET /api/admin/operations/gap` —— 供给时段缺口分析（「周内第几天 × 小时」分桶）
- `GET /api/admin/operations/effect` —— 干预效果对比（同一指标两期值 + 差值）

### 口径决策

- **桶的粒度固定为「周内第几天 × 小时」**，与区间长度无关 ——
  只有这样「这周和上周的同一时段」才可比（迭代计划 §47 的闭环数据基础）
- **供给数按「可服务时段」分桶**，不是按「当前 AVAILABLE 总人数」——
  后者每个桶都一样，指标没有信息量；
  没能解析时段的陪诊员不进任何桶，并由 `companionsWithoutSlots` **如实披露**
- **效果对比两侧走同一个计算器** —— 用两个公式对比，比出来的是公式差异
- **同一指标跨端共用一份实现**（决策 6.2）—— 物理上只有一份，就不可能漂移

### 门禁

- mvn -B verify（基线 1162 + 新增）
- pnpm lint:check / vitest / build
- playwright 全量

---

## [V3.5] · 2026-10-20 · 平台运营驾驶舱

让管理员**在 30 秒内发现平台异常，并且能直接去处理**。
产品与工程契约见 [PRD](docs/prd/PRD-V3.5-平台运营驾驶舱.md) /
[spec](docs/spec/V3.5-operations-cockpit-spec.md) /
[tickets](docs/spec/V3.5-tickets.md)。

### Added · 新增

**四个驾驶舱端点**

- `GET /api/admin/operations/overview` —— 平台概览八项指标
- `GET /api/admin/operations/exceptions` —— **异常中心**（四类异常，每类带 `linkUrl`）
- `GET /api/admin/operations/trend` —— 六条趋势（订单 / 陪诊员活跃 / 履约率 / 投诉 / 评价 / 用药任务）
  × 四个区间（今日 / 近 7 天 / 近 30 天 / 自定义）
- `GET /api/admin/operations/health` —— **平台健康度 = 六个客观指标，不给合成分**
- `config/OperationsProperties`（阈值配置化，启动期校验）+ `mapper/OperationsMapper`
  + `service/OperationsService(+Impl)` + 5 个 VO + `controller/admin/OperationsController`

**前端**

- `views/admin/operations.vue` —— 驾驶舱首页（概览 / 异常中心 / 趋势 / 健康度四块**独立三态**）
- 异常卡片的「去处理」**直接用后端下发的 `linkUrl`** 跳转，前端不拼路由
- 组件单测 + 20 例；E2E + 8 例（含「四类异常卡片逐个点开，落地页必须可达」）

### 口径决策

- **异常中心聚合 V3.1 风险中心，不另建一套风险判定**（决策 5.1）——
  `manualRequired` 与 `warning + urgent` 与 `/api/admin/fulfillment/summary` **同源**，
  由 `OperationsExceptionParityTest` 锁死
- **平台健康度是六个并列指标，不合成总分**（决策 5.2）——
  禁止「AI 平台健康指数 96」这类无法解释的数
- **履约率 ≠ 完成率（按时）**（偏差 D-3.5-1）：
  履约率看「最终有没有履约完」，完成率看「完成得及不及时」；
  两个名字一个数同样是口径分裂
- **每张异常卡片的 `linkUrl` 必须可达**（决策 5.4）——
  「看到了但处理不了」是本版最典型的假绿，E2E 逐个点开验证

### 门禁

- mvn -B verify（基线 1101 + 新增）
- pnpm lint:check / vitest 191/191（基线 171，+20）/ build
- playwright 全量（基线 229 + 新增 8）

---

## [V3.4] · 2026-10-20 · 智能调度

让平台**自动找到更合适的陪诊员**，并且每一条推荐都能说出「为什么是他」。
产品与工程契约见 [PRD](docs/prd/PRD-V3.4-智能调度.md) /
[spec](docs/spec/V3.4-matching-spec.md) /
[tickets](docs/spec/V3.4-tickets.md) /
[ADR-0016](docs/adr/0016-matching-structured-and-explainable.md)。

### Added · 新增

**结构化改造（V7 迁移）**

- `backend/sql/V7__matching_structured.sql`：`companion_profile` 增坐标 + 服务区域码；
  `companion_order` 增服务区域码；新表 `companion_available_slot`（可服务时段）
- backfill：自由文本 → 区域码（8 个区县 / 4 个市，**映射不上的留空**）
- `backend/sql/tools/gen_region_codes.py` + `region_code_mapping.json` —— 映射生成器与校验器
  （`--check` 机械核对「迁移里手写的 CASE」与「生成器产出」逐字一致）

**匹配三件套（纯函数，无 Spring 上下文）**

- `service/support/matching/`：`MatchingRule` / `MatchingScore` / `MatchingCandidate` / `MatchTarget`
  + 五条规则（区域 / 时间 / 距离 / 服务能力 / 评分）+ `LegacySlotParser`（自由文本时段回退）
- `config/MatchingProperties` —— 五个权重配置化，**启动期校验权重之和 = 1**（不等于就启动失败）
- `service/MatchingService(+Impl)` + `mapper/MatchingMapper` —— 过滤链（工作状态 → 资质 → 区域 → 时段 → 档期冲突）

**接口**

- `GET /api/order/{id}/recommend-companions` —— 家属端候选推荐（Top N + 可解释性）
- `GET /api/order/hall?sortBy=` —— 开放 `DISTANCE` / `TIME_MATCH`；`RECOMMEND` 换成五维加权

### Changed · 变更

- `OrderServiceImpl#hall`：从 SQL 三级键改为 **Java 侧五维打分 + 内存分页**
  （响应结构与 `total` 语义**零变更**，变的只有「哪几条排在前面」）
- 指标字典登记 6 条匹配指标（v1.0 → v1.2）
- `docs/api/03-order.md` —— 补排序语义与候选推荐端点
- `docs/adr/0016-*.md` —— 新 ADR：为什么必须先做结构化改造 + 为什么每条推荐都必须可解释
- 前端：大厅加排序选择器；家属端订单详情加候选展示（标注「推荐 ≠ 派单」）

### 口径决策

- **区域码两级**（`<CITY>_<DISTRICT>`）：码相等 = 1.0；同市不同区 = 0.5；跨市 = 0
- **「缺数据」与「不匹配」分开**：没填坐标 / 没登记时段 → 放行 + 降级标注，**不淘汰**
- **`INCOME` 排序仍不开放**：缺真实结算金额口径（决策 D8）
- **推荐 ≠ 派单**：端点不产生任何订单状态变更，接单权始终在陪诊员手里

---

## [V3.3] · 2026-10-20 · 陪诊员工作台

把陪诊员端散落的「接单大厅 / 订单列表 / 订单执行 / 收入 / 评价」收成**一个工作台**，
并把**收入页的前端自算搬回后端**。
产品与工程契约见 [PRD](docs/prd/PRD-V3.3-陪诊员工作台.md) /
[spec](docs/spec/V3.3-companion-workbench-spec.md) /
[tickets](docs/spec/V3.3-tickets.md) /
[指标字典](docs/spec/metric-dictionary.md#24-陪诊员工作台v33)。

### Added · 新增

**陪诊员自助统计（T2–T3）**

- `GET /api/statistics/companion/me` —— 区间经营概览（完成单数 / 服务时长 / 完成率 / 平均评分 / 区间收入）
- `GET /api/statistics/companion/me/trend` —— 趋势（维度 `ORDER` / `INCOME` / `RATING` × 粒度 `DAY` / `WEEK` / `MONTH`）
- `GET /api/statistics/companion/me/profile` —— 服务画像（**累计口径**：累计完成单数 / 服务老人数 / 准时率 / 累计完成率 / 累计服务时长 / 擅长服务标签）
- `GET /api/statistics/companion/me/income` —— 收入中心（订单金额 / 实际收入 / 待结算 / 已结算 / 代垫合计 / 服务费合计）
- `controller/statistics/CompanionStatisticsController` —— **独立 Controller 类**（类级 `@PreAuthorize("hasRole('COMPANION')")`）；
  不挂在 `StatisticsController`（类级 ADMIN）下面，避免「靠方法级注解覆盖类级注解」这种漏一个就泄露的写法
- `mapper/CompanionStatisticsMapper` + `service/CompanionStatisticsService(+Impl)` + 5 个 VO + `dto/CompanionTrendQuery`
- `constant/CompanionTrendDimension` —— 趋势维度枚举（与 `CompanionRankMetric` 同范式）
- **四个端点都不接收 `companionId` 入参**：陪诊员身份从登录态取，
  请求里带 `?companionId=别人的ID` 会被完全忽略（不是靠 if 挡住，是压根没有读它的地方）

**指标字典登记（T4）**

- 登记 **18 条** V3.3 指标（区间 11 条 + 累计 7 条）到 `metrics/metric-dictionary.yml`（机器面）
  与 `docs/spec/metric-dictionary.md`（人读面，v1.0 → v1.1）
- `MetricDictionaryTest` 扩展 3 条断言：覆盖 V3.3 的六个 VO / 四个端点都登记过 /
  同一指标的多个出口共用一条公式

**前端（T6–T9）**

- `views/companion/workbench.vue` —— **陪诊员工作台首页**（经营指标 / 趋势图 / 服务画像 / 收入摘要四块）
  - 四块用 `Promise.allSettled` 并行拉取，**单块失败只降级该块**，不拖垮整页
  - 趋势图的配色**运行时读 `--nl-*` 设计令牌**（canvas 不解析 CSS 变量，JS 里不留硬编码色值）
- `views/companion/income.vue` —— **去 `reduce()` 化**：KPI 改吃 `/me/income`，
  文案「累计收入」→「区间收入」，页面明示统计区间；账单明细列表保留
- `api/statistics.js` —— 4 个工作台 API 封装
- `router/routes.js` —— 新增 `companion/workbench`（`code: M-32`；`M-22` 已被「我的」占用）
- `__tests__/companion-workbench.test.js` —— **16 例**（静态扫描 6 例 + 组件渲染 10 例），
  含 3 条**扫描器自证用例**（防止静态扫描变成恒过的空转）

### Changed · 变更

- `docs/api/09-statistics-export.md` —— 新增 §8「陪诊员工作台统计（COMPANION）」，
  含请求 / 响应 / 口径表 / 错误码表；接口列表补 4 行
- `docs/api/README.md` —— 访问矩阵把 `/api/statistics/**` 拆成 ADMIN 段与 COMPANION 段两行
- `docs/spec/metric-dictionary.md` —— v1.1：新增 §2.4 陪诊员工作台（区间 11 条 + 累计 7 条）
- `docs/spec/V3.3-tickets.md` —— **新建**：开工复核结论 + 10 张工单

### Fixed · 修复

- **「累计完成单数」不再读 `companion_profile.order_count`**：
  该字段只在建档时置 0（`CompanionServiceImpl`），**运行期没有任何代码维护它**，
  只有种子脚本 `V3__seed_boundary.sql` 手工同步过 —— 读它会得到一个永远是 0 的数。
  改为从 `companion_order` 现算。
  （`companion_profile.score` 不一样：它在评价提交时于事务内刷新，是维护得住的，所以沿用。）
- **评分趋势在周 / 月粒度下按评价条数加权**：日均值不能直接平均成周均值
  （5 条 5 星 + 1 条 1 星 → 日均值的平均是 3.00，真实周均值是 4.33）

### 口径决策 · 开工复核的三处偏差

| # | 偏差 | 理由 |
|---|---|---|
| D-3.3-1 | 「本月」措辞改为「统计区间」 | 决策 3.5 要求沿用 M10 的区间约定（默认 30 天）；「本月」是固定日历月，与可传参的区间不是一回事 |
| D-3.3-2 | 概览（区间）与画像（累计）用**不同字段名** | 两边都有「完成率」，同名就是接口层面的口径分裂。字段名自带口径（`total` 前缀 = 累计） |
| D-3.3-3 | 收入页「累计收入」→「区间收入」 | 决策 3.4 要求两页口径一致、G8 要求前端零自算；旧页的「累计」是拉全量订单 `reduce()` 算的 |

### 验证 · 本版门禁结果

| 检查 | 结果 |
|---|---|
| `mvn -B verify` | ✅ **1032** 条全绿（基线 992 → 新增 40） |
| `pnpm run lint:check` | ✅ 0 error / 0 warning |
| `pnpm run test` | ✅ 16 个文件 / **155** 条全绿（基线 139 → 新增 16） |
| `pnpm run build` | ✅ 生产构建成功 |
| 越权矩阵 | ✅ ELDER / FAMILY / ADMIN 访问 4 端点一律 403；未登录 401；陪诊员本人 200 |
| 跨账号隔离 | ✅ `?companionId=别人` 返回的数据逐字不变 |
| 真库一致性 | ✅ 聚合 SQL 与逐条 Java 统计一致（完成单数 / 时长 / 收入 / 完成率 / 结算拆分） |
| 拒单不入分母 | ✅ 真库验证：种子里 301 拒过 6 单，分母不含它们 |

## [V3.2] · 2026-10-20 · 老人健康协同中心

把「订单 / 陪诊 / 用药 / 消息」四类散落的信息，合成**一位老人一个视图**。
产品与工程契约见 [PRD](docs/prd/PRD-V3.2-老人健康协同中心.md) /
[spec](docs/spec/V3.2-elder-health-spec.md) / [tickets](docs/spec/V3.2-tickets.md)。

### Added · 新增

**指标字典（跨版本地基，T0）**

- `docs/spec/metric-dictionary.md`（人读）+ `backend/src/main/resources/metrics/metric-dictionary.yml`（机器面）
- `util/MetricDictionary` 加载器 + `MetricDictionaryTest`：**字典 ↔ VO 双向校验** —— 字典里写的字段必须真的存在，VO 里的字段必须真的登记过
- `common/DateRange` + `dto/DateRangeQuery`：跨版本共享统计区间（默认 30 天 / 上限 366 天 / 闭区间），**M10 一并改用**
- 首批登记 **12 条**指标（就医 / 陪诊 / 用药 / 待处理）

**用药三桶口径统一（T1）**

- `constant/MedicationBucket`（唯一真源）+ `constant/MedicationBucketSql`（共享 CASE 表达式）
- **修 M10 的历史漂移**：`StatisticsMapper.selectDailyMedicationCounts` 原先只认 `was_missed=1`，
  而漏服扫描只写 `status='MISSED'` 从不写 `was_missed` —— 本机种子里 **179 条真实漏服既不 taken 也不 missed**，
  `taken+missed ≠ total`。现改为「内层算 bucket、外层派生三个计数」，判定全 SQL 只出现一次
- 同步修正 `StatisticsMapper` javadoc 与 `StatisticsController` `@Operation` 里**错误的**「与服药日历一致」宣称
- 详见 [ADR-0014](docs/adr/0014-medication-bucket-single-source.md)

**健康协同五个端点（T2–T5）**

- `GET /api/family/health/summary` —— 家庭健康协同首页摘要（每位老人一栏）
- `GET /api/family/elder/{elderId}/health/overview` —— 某位老人的健康概览（六项客观指标 + 待处理事项）
- `GET /api/family/elder/{elderId}/health/timeline` —— 健康时间线（订单 / 打卡 / 用药 / 提醒四类，游标翻页）
- `GET /api/elder/health/overview` · `GET /api/elder/health/timeline` —— 老人端，**不接受任何 `elderId` 入参**
- 新增 `ElderHealthReadMapper`（聚合只读查询，手写不落生成器产物）+ `ElderHealthService(Impl)` + 4 个 VO
- **零迁移、零新增错误码**（全部复用 2001 / 2006 / 9002 / 400）—— 见 [ADR-0015](docs/adr/0015-elder-health-aggregation-no-new-table.md)

**前端（T6–T8）**

- `api/elderHealth.js`（**不叫 `health.js`** —— 已被 `/health` 健康检查占用）+ `utils/elderHealth.js`（分类 → 视觉的唯一映射表）
- 三页：`views/family/health.vue`（家庭健康首页）· `views/family/elder-health.vue`（某老人健康中心）· `views/elder/health.vue`（老人端）
- 路由 M-29 / M-30 / M-31；入口落在**既有分组**内，底部 TabBar 不加项
- 老人端大字版：56px 主数字、概览只留 4 项、时间线只显示「图标 + 一句话 + 时间」

### Fixed · 修复

- `UploadAccessGuardFilter`（安全加固引入）把**种子给全体用户填的占位头像** `/uploads/avatar/default.png`
  当成证件挡掉 —— 老人端与「我的」页稳定产生一条 403 console error，把 **6 条既有 E2E 打成红的**。
  该路径根本不是上传产物（上传一律写进 `/uploads/{yyyyMM}/{uuid}.{ext}`），收窄守卫范围到落盘布局后
  **证件保护一点没弱化**，6 条 E2E 复绿

**code-review（Standards + Spec 双轴）后的修正**：

- 老人端「今日已服药 / 还有 N 次没吃」原先用的是**统计区间**口径（30 天的数字被说成「今天」）→
  新增 `MedicationTodayVO`（当天口径三桶），概览增 `todayMedication` 字段
- 指标字典的「双向校验」对**未登记的新 VO 失效**（`vos` 段是人工维护的清单）→ 增加第三层
  「可达性」校验：从已登记的 VO 沿字段类型走，凡被接口带出去的 VO 必须登记
- 时间线**订单类未受 200 条上限约束**（200 单能产出 1000 条事件）且响应无截断标记 →
  改按事件条数封顶，新增 `truncated` 标志，前端如实提示「记录较多，仅显示最近部分」
- 三页的内容型文字用 `--nl-text-3`（白底 2.62:1，低于 4.5:1）与 caption / micro 档
  （老人模式 15px / 12.5px，低于 18px 基线）→ 分别提到 `--nl-text-2` 与 `--nl-font-body`
- 上传落盘布局被写了两遍（落盘服务与守卫各一份）→ 新增 `constant/UploadPathLayout` 作单一真源
- 新增测试的方法名不符合 `methodNameShouldXxx` → 15 处订正
- **E2E 条数订正**：本版新增实为 **13 条**（此前写的 20 条把 `auth.setup` 的 7 条登录用例也算进去了）

### Changed · 变更

- `StatisticsQuery` 改为继承 `DateRangeQuery`（区间判定只写一份）；`DateRangeQuery` **不用链式 setter**
  —— 链式会让继承来的 setter 返回父类型，既有调用点签名会变
- `MedicationCalendarVO.of` 改为调用 `MedicationBucket.of(...)`（**行为不变**，它本来就是对的）

### 文档

- ADR-0014 用药三桶唯一真源 · ADR-0015 老人健康聚合零迁移
- `docs/prd/PRD-V3.2-老人健康协同中心.md`（独立版本 PRD，遵守 ADR-0006 版本锁，不动 `PRD.md`）
- `docs/spec/V3.2-elder-health-spec.md` · `docs/spec/V3.2-tickets.md` · `docs/spec/metric-dictionary.md`
- API 契约：新增 `docs/api/12-elder-health.md`，更新 `README.md` 目录与 2xxx 段说明 · `05-medication.md` 口径说明
- `docs/spec/V3.x-roadmap-spec.md` 回写 V3.2 落地后的偏差

### 测试

- 后端新增 **86 条**用例（902 → 988，全量 **988 / 988** 全绿）
- 前端新增 **17 条**单元测试（122 → 139，全量 **139 / 139** 全绿）
- Playwright：新增 **13 条**（`e2e/specs/16-elder-health.spec.js`），全量 **210 / 210** 全绿，夹具回滚校验通过
- 反向验证：删掉 `MedicationBucket.of` 的 MISSED 分支 → 真库 parity 测试报 `expected: <576> but was: <342>` 后复绿

### Compliance · 合规

- 健康中心全部文案为**事实陈述**，不出现任何结论式判断；禁词表由前端单测与全仓扫描共用
- 老人姓名脱敏；老人端不返回费用 / 催单 / 履约风险 / 家属姓名
- **订单聊天内容不进健康时间线**（时间线只读 `internal_message`，不读 `chat_message`）

---

## [V3.1] · 2026-10-17 · 履约保障中心

把「下了单一直没人接」这段空白期变成**有反馈、有节奏、有人兜底**的时间。
产品与工程契约见 [PRD](docs/prd/PRD-V3.1-履约保障中心.md) /
[spec](docs/spec/V3.1-fulfillment-spec.md) / [tickets](docs/spec/V3.1-tickets.md)。

### Added · 新增

**陪诊员接单状态自助控制（T0）**

- `WorkStatus` 增加 `OFFLINE`，与 `REST` 区分「主动暂停」与「被动离线」
- `PUT /api/user/companion/work-status`：只写 `work_status` 一列，**不触碰在手订单**
- 陪诊员大厅新增接单状态开关（移动 + 桌面两个形态）

**履约风险模型与扫描（T1 / T2）**

- `companion_order` 增三列：`fulfillment_risk` / `urge_count` / `last_urge_at`（`V6__order_fulfillment.sql`，`information_schema` 守卫幂等）
- 新表 `order_fulfillment_event`：履约事件流水，只增不改
- 索引 `idx_risk_status_time (fulfillment_risk, status, create_time)`
- `FulfillmentRisk` / `FulfillmentEventType` / `TriggerSource` 枚举
- `FulfillmentRiskEvaluator`：**唯一测试接缝**，纯函数、无 Spring、无时钟依赖
- `FulfillmentProperties`：阈值配置化（`nianglin.fulfillment.*`），启动期校验严格递增
- `FulfillmentScheduler`：5 分钟一次，Redis 锁 TTL 4min，异常自接住

**家属催单（T3）**

- `POST /api/order/{id}/urge`：冷却 10 分钟、累计上限 3 次（均配置化）
- 资格判定与计数自增**合并为同一条条件更新**，不做「先查后写」

**履约读口径（T4 / T5）**

- `OrderVO` 五个只读字段：`fulfillmentRisk` / `fulfillmentRiskLabel` / `waitMinutes` / `urgeCount` / `canUrge`
- `GET /api/order/{id}/fulfillment`：风险等级 + 事件流水（升序）
- `TimeUtil.elapsedMinutes`：等待时长的**唯一钳制规则**，扫描 / 出参 / 详情三方共用
- 家属端风险状态条 + 订单列表轻量标记
- `frontend/src/utils/fulfillment.js`：等级 → 视觉的**唯一映射表**

**管理员风险中心与改派（T6 / T7）**

- `GET /api/admin/fulfillment/summary`：四档计数，恒定全返，为 0 时返回 0
- `GET /api/admin/fulfillment/risk-list`：固定「等级降序 → 等待时长降序 → id 降序」，姓名与订单号脱敏
- `POST /api/admin/order/{id}/reassign`：仅处理未接单订单，**不改变 `status`**
- 管理端新页 `履约风险`（W-10）：四档计数卡 + 等级筛选 + 列表 + 详情 / 改派弹窗
- `OperType.REASSIGN_ORDER` 操作审计类型

**大厅排序（T8）**

- `GET /api/order/hall` 新增可选参数 `sortBy`，白名单只放行 `RECOMMEND`，非法值 `400` 不静默降级
- 推荐序为三级稳定排序：`visit_time ASC` → 区域匹配优先 → `id ASC`

### Changed · 变更

- `MessageTemplateUtil`：新增 `ORDER_REASSIGNED` 模板（三类接收方靠 `audience` 占位符区分措辞）
- `backend/sql/tools/gen_entity.py`：新增 `parse_alters()`，修复增量列被静默丢弃的问题（顺手修复，超出本版范围但已验证）
- `OrderVO.ofList` / `ofHall` / `ofDetail` 三套口径共用 `base()` 装配，非 `PENDING` 时履约字段统一收敛

### Fixed · 修复

- `AdminArbitrateTest` 中一处既有损坏字符

### 文档

- ADR-0012 履约风险与订单状态分离 · ADR-0013 大厅单一推荐序
- `docs/prd/PRD-V3.1-履约保障中心.md`（独立版本 PRD，遵守 ADR-0006 版本锁，不动 `PRD.md`）
- API 契约：`02-elder-family.md` §13 · `03-order.md` §11/§12/§3 · `08-admin.md` §14–§16 · `07-message.md` 类型表补全 · `README.md` 错误码 `3008`/`3009`/`3010`
- 数据字典：`companion_order` 三列 + `order_fulfillment_event` 表

### 测试

- 后端新增 **210 条**用例（680 → 890，全量 890 / 890 全绿）
- 前端新增 **22 条**单元测试（67 → 89，全量 89 / 89 全绿）
- Playwright：基线 176 条零修改通过 + 新增 16 条（2e/specs/15-fulfillment.spec.js），合计 192 条全绿
- code-review（Standards + Spec 双轴）：契约偏差已修（离开 PENDING 的库层风险回落、SQL 侧催单自增、工作状态越权矩阵）
- 反向验证：改坏阈值 → 2 条测试变红；删除状态机转移边 → 3 条测试变红，还原后复绿

### Compliance · 合规

- 催单备注与改派理由过 `ComplianceCheckUtil` 词表（与服务小结 `3007` 同一份，命中返回 `6003`）
- 新增文案无「建议服用 / 剂量 / 诊断」类表述
- 风险列表姓名与订单号脱敏；详情的全名豁免仅对已过归属校验的详情生效
