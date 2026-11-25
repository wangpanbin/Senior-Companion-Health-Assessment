# 计划 · 交接文档遗留 7 类未完成项收口（E4 评价公信力闭环落地）

> 来源：`.scratch/handoff-senior-companion-20261125.md`（2026-11-25 交接）
> 基线：`main` = `abf8e1c`，领先 `origin/main` 3 提交，**未 push**（用户红线：不自动 push）
> 计划文件：`plans/e4-handoff-completion.md`

---

## Context

上一轮（文档路径对账）已完成并合并进 `main`：10 处悬空引用清零、文档门禁改全量。
本轮收掉交接文档遗留的 7 类未完成项，核心是**把 E4「评价公信力闭环」从「后端完整 / 前端零落点」补成端到端可用**。

### 用户已定的六项决策（grilling 两轮）

| # | 决策 |
|---|---|
| 1 | 本轮做**全部四项**：门禁两个缺陷（§4）、活文档 4 处悬空（§5.1）、E4 前端裁定入口（§2）、RTM 口径对齐（§1） |
| 2 | E4 裁定入口**两个都做**：`admin/complaint.vue` 申诉联动 + 新建巡查页 |
| 3 | `docs/reports/` 冒出的 4 处悬空**四处全修** |
| 4 | `tools/e2e/` 下的 `e2e_review_appeal.py` **交付完整 5 条断言** |
| 5 | 巡查页数据源 = **新增 `GET /api/admin/review` 端点**（动 `backend/src/main`） |
| 6 | e2e 第 5 条断言 = **限定业务目录 + 显式 allowlist**；**用户给口令，我现场跑通** |

### 分支划分（3 个，各自自带验证）

| 分支 | 内容 | 验证 |
|---|---|---|
| `docs/gate-and-path-fixes` | 门禁 2 缺陷 + 活文档 4 处 + `docs/reports/` 4 处 + RTM/口径文档 | `gate_check.py --only md --all-md` + 门禁自测 |
| `feature/review-ruling-ui` | 新后端端点 + `complaint.vue` 裁定入口 + 巡查页 + 前端单测 + API 文档 | `mvn -B verify` + `pnpm lint:check` + `pnpm test` |
| `feature/review-appeal-e2e` | e2e 脚本 + `tools/e2e/README.md` 补行 + spec/tickets 状态翻转 | 脚本 5 条断言全绿 + 退出码 0 + 库水位回基线 |

---

## 关键调研结论（决定实现形态，动手前已核实）

### A. 裁定入口零后端改动即可打通（complaint.vue 侧）

- `ComplaintVO` **无 `reviewId` 字段**，`complaint` 表**无 `review_id` 列**（V1 DDL；E4 零迁移承诺）。
- spec §3.3 原设计的 `getReviewByOrderAdmin(orderId)` **在仓库内不存在**（grep 零命中）—— spec 这条自身也没实现过。
- 但 `GET /api/review/order/{orderId}` → `reviewService.byOrder` → `orderService.requireInvolved`，
  **ADMIN 首分支直接放行**（`OrderServiceImpl#requireInvolved`：`if (me.isAdmin()) return order;`）。
- `ReviewVO` 已含 `id / isValid / score / content / companionReply / replyTime`（`ReviewVO.java:47-87`）。
- 前端已有 `getReviewByOrder(orderId)`（`api/review.js`）。
- → **complaint.vue 侧零后端改动**：拿 `row.orderId` 反查 `review.id` 即可。

### B. 巡查页必须新增后端端点

- `AdminController` 现有 19 个端点，**无任何 `/admin/review` 列表端点**。
- `GET /api/review/companion/{companionId}` 硬编码 `.eq(OrderReview::getIsValid, 1)`
  （`ReviewServiceImpl#byCompanion`）→ 管理员用它**永远看不到已裁定无效的评价**，不能当巡查用。
- 改现有 `/api/review/companion/**` 不可行：它不在 `/api/admin/**` 下，`SecurityConfig` 只对
  `/api/admin/**` 做 `hasRole(ADMIN)`，改它会扩大该端点可见性边界。

### C. 门禁缺陷实测复现

把 `reports` 改成**只在根层级生效**（`parts[0] == "reports"`）后重跑：

- 扫描文件数 **140 → 168**（`docs/reports/` 下 28 份 .md 回来）
- 悬空路径 **0 → 4**（围栏仍 0）
- 根 `reports/integration/2026-09-16/index.md` 的 2 处仍被正确跳过（`.gitignore:98 /reports/`，`git check-ignore` 已确认）→ **不要治**

### D. e2e 第 5 条断言当前必然假红

`建议服用|推荐剂量|诊断为` 实测命中 8 个文件，全是**合法反面样本**：

| 文件 | 为什么合法 |
|---|---|
| `backend/.../common/ResultCode.java` | 合规错误码文案常量 |
| `backend/.../util/ComplianceCheckUtil.java` | 违禁词表本体 |
| `ai-service/app/paths.py` | 合规守卫词表 |
| `frontend/src/utils/elderHealth.js` + `__tests__/elderHealth.test.js` | 药品免责声明 / 反例断言 |
| `frontend/src/views/elder/medication.vue` | 免责声明文案 |

→ 断言必须限定扫描面 + 显式 allowlist（每行带 `why` 注释，将来新增词表文件忘了加会红并指路）。

### E. 环境现状

- MySQL80 / Redis 服务 **Running**；`mysql.exe` 在 `C:\Program Files\MySQL\MySQL Server 8.0.bin`；
  `redis-cli.exe` 在 `D:\develop\Redis-8.8.0\redis-cli.exe`。
- 8080 / 8081 / 5141 **当前都没监听** → 跑 e2e 前必须 `tools/e2e/start-services.ps1`（需 `MYSQL_PASSWORD`）。
- Python 侧**没有 `redis` 包** → 验证码走 `redis-cli.exe`（与 `frontend/e2e/fixtures/captcha.js` 同法）。
- 种子口令 `Nl@123456`（`frontend/e2e/helpers/accounts.js:19`，可用 `NIANGLIN_SEED_PASSWORD` 覆盖）；
  账号 `admin`(1) / `fam001`(101) / `comp001`(301) / `comp007`(307) / `elder001`(201)。
- 工具链齐备：mvn 3.9.15 / JDK 21.0.7 / pnpm / node；`frontend/node_modules` 已装。
- `order_review` 种子：30001–30006 全 `is_valid=1` 且**已回复**；**30003 未回复**（`companion_reply=NULL`）；
  1031 是 fam001 名下 REVIEWED 订单。

---

## Approach

### 分支 1 · `docs/gate-and-path-fixes`（纯文档 + 脚本，无业务代码）

**1a · 门禁两个缺陷**（`tools/ai_flow/gate_check.py`）

- `MD_SKIP_DIRS` 的 `"reports"` 改为**根层级锚定**：`if parts[0] == "reports": continue`，
  其余目录名（`node_modules` / `target` / `dist` / `__pycache__` / `.venv` / `.pytest_cache` / `.idea`）保持任意层级匹配。
  → 拆成 `MD_SKIP_ROOT_DIRS` + `MD_SKIP_ANY_DIRS` 两个常量，语义显式。
- 删 `main()` 尾部 **440–451 行 14 行不可达代码**（438 行已 `return 0`）。
- `tools/ai_flow/test_gate_check.py` 补 **2 条回归用例**：
  ① `docs/reports/` 下的 .md **必须**被 `all_md_files()` 扫到；
  ② 根 `reports/` 下的 .md **必须**被跳过。
  → 现有 15 例 → 17 例。

**1b · 活文档 4 处悬空**

| 位置 | 改法 |
|---|---|
| `docs/prd/PRD.md:306` | `frontend/src/i18n/` → 「预留（当前未建目录，本期不实装多语言）」 |
| `docs/design/design.md:419` | `frontend/src/components/icons/` → 改成「图标**内联在组件内**（`NlIconBox.vue` 用 `:deep(svg)`），本仓不设独立 SVG 目录」 |
| `GLOSSARY.md:43,47` | `PhoneLayout.vue` → `MobileLayout.vue`（ADR-0007 已重命名；GLOSSARY 是唯一真源） |
| `docs/adr/0016-...md:68` | `gen_region_codes.py` → `backend/sql/tools/gen_region_codes.py` |

**1c · `docs/reports/` 4 处（修完门禁自动冒出来的）**

| 位置 | 改法 |
|---|---|
| `docs/reports/验收报告.md:179` | `frontend/src/api/` 下的 `realtime.js`（该文件从未存在） → `frontend/src/api/message.js:102`（前者**从未存在**，SSE 实证在 `message.js` 的 `new EventSource`） |
| `docs/reports/script.md:177/308/309/361` | `docs/prd/` 下的 `PRD-V3.7` → `docs/prd/PRD-V3.7-家属智能助手.md`（4 处） |
| `docs/reports/script.md:196` | `backend/sql/` 下的 `V4`…`V7` → 逐个列 `backend/sql/V4` / `V5` / `V6` / `V7`（`RANGE_SEGMENT_RE` 不认 `..`） |
| `docs/reports/iteration-v3.1-test-report.md:133` | 「仅记录未修」→ 已修（`tools/scan_bad_chars.py:3` 现在写的就是新名），该行改为「已修复（2026-11-25 核对）」 |

**1d · 口径对齐（§1）**

本轮把两个前端入口都补齐后，`docs/prd/功能拓展规划.md:433` 的 RTM R-E4 行 `✅ 已落地`
与 spec §10.1 / tickets T5 的 `❌ 未交付` **可以同时翻成「已交付」**，不必二选一。
但**前提是巡查页真的把「主动裁定」做成可用功能**，不能拿 RTM 去凑 spec。

- `docs/prd/功能拓展规划.md:433` → `✅ **已落地**（后端裁定 + 管理端两个入口：申诉联动 / 巡查页，2026-11-25 交付）`
- `docs/spec/E4-review-credibility.md` §10.1 / §10.2 → 状态列改 `✅ 已交付`
- `docs/spec/E4-tickets.md` T5 → `✅ 已交付`；T7 → 随分支 3 翻

> ⚠️ 口径翻转属**分支 3 的收尾**（e2e 交付后一起翻），分支 1 不动 spec 状态列 ——
> 否则会出现「文档说已交付、代码还没合」的中间态骗人。

### 分支 2 · `feature/review-ruling-ui`（后端 + 前端）

**2a · 后端新增 `GET /api/admin/review`**

- 新增 `dto/AdminReviewQuery.java`（仿 `AdminComplaintQuery`）：
  `companionId` / `minScore` / `maxScore` / `isValid`（Boolean，null=不限）/ `startDate` / `endDate` / `keyword`。
  继承 `PageQuery<OrderReview>`。
- 新增 `vo/AdminReviewVO.java`：复用 `ReviewVO` 的字段口径但**独立 VO**
  （`ReviewVO` 是「评价人视角」，巡查页要 `isValid=false` 也可见 → 不能直接复用它的语义）。
  字段：`id / orderId / orderNo / score / tags / content / familyName(脱敏) / companionId / companionName / companionReply / replyTime / isValid / createTime`。
  姓名脱敏走 `MaskUtil.name(...)`（`ReviewVO.of` 已是这个口径，参照它）。
- `AdminService#reviewList(AdminReviewQuery)` + `AdminServiceImpl` 实现：
  - `OrderReviewMapper`（`BaseMapper` 够用，无需新方法）；
  - 批量取姓名用现成的 `UserNameResolver#resolveAll`（避免 N+1）；
  - tags 解析参照 `ReviewServiceImpl#parseTags`；
  - `isValid` 过滤：null=不限，`true` → `is_valid=1`，`false` → `is_valid=0`；
  - 排序：`create_time DESC, id DESC`。
- `AdminController#reviewList`：`@GetMapping("/review")`，类级 `@PreAuthorize` 已覆盖 ADMIN。
- **零 DDL / 零迁移**（`order_review` 既有列够用）。
- 后端单测：新增 `AdminControllerReviewListTest`（契约：委托 + 参数透传）、
  `AdminServiceReviewListTest`（筛选组合 + 姓名脱敏 + `isValid` 三态）。
  参照现有 `AdminControllerReviewTest`（Mockito 风格，`AdminController` 构造器注入 3 个 mock）。
- **文档**：`docs/api/08-admin.md` §二 接口列表表加一行（序号 30）+
  新增 `## 29. 评价巡查列表`（请求参数 / 响应字段表 / 业务规则）；
  `docs/api/README.md` 若有端点计数表需同步（本次实测未找到独立计数表，以实际改动为准）。

**2b · 前端 `admin/complaint.vue` 裁定入口**

- 引入 `rulingReview`（`@/api/admin`）、`getReviewByOrder`（`@/api/review`）。
- 仅 `row.type === 'REVIEW_APPEAL'` 且该投诉**未终态**时，在操作区显示「裁定无效」按钮。
- 点击 → 打开 dialog：
  - 先 `getReviewByOrder(row.orderId)` 拉关联评价，展示摘要（评分 / 内容 / 陪诊员回复 / `isValid`）；
    拉不到（尚未评价）→ 空态提示，不给裁。
  - 理由输入框：10–200 字，前端先拦一道（后端 `ReviewRulingDTO` 同约束）。
  - 明确提示「**单向裁定：仅『有效 → 无效』，不可撤销**」（PRD §FR-03 规则 2）。
- 确认走现有的 `confirmThen(() => ElMessageBox.confirm(...))`（`@/utils/confirm`，本文件已在用）。
- in-flight 锁模式照抄本文件既有 `handleSubmitting`（**锁必须在第一个 `await` 之前同步置位**，见文件内 P1-5 注释）。
- 成功后 `reloadAfterMutate()`（`usePagedList`）并 `ElMessage.success`；错误由 `request.js` 拦截器统一弹，本处不重复弹。
- 已裁定过（后端 409）→ 拦截器提示，列表该行按钮应消失（后端 `isValid=0` 后反查得到 `isValid=false`，据此禁用按钮）。

**2c · 前端新建 `admin/review-audit.vue`（巡查页）**

- 路由 `frontend/src/router/routes.js`：新增 `AdminReviewAudit`，path `/admin/review-audit`，
  `meta: { title: '评价巡查', icon: 'Star', roles: ['ADMIN'], module: 'M9', code: 'W-14' }`
  （`W-01`…`W-13` 已占用，见 `routes.js` 现状）。
  `AdminLayout` 侧栏自动消费 `routes` 的 `children` → **无需改侧栏代码**。
- `frontend/src/api/admin.js` 新增 `listAdminReviews(params)`（`GET /admin/review`）。
- 页面结构照 `admin/complaint.vue` 范式：
  - 筛选区：陪诊员（数字输入 / 关键词）、评分区间、`isValid`（全部 / 有效 / 已裁定无效）、时间区间；
  - `NlAdminTable`（双形态：桌面 `el-table` / 窄屏卡片，`mobileFields` 取评分 + 有效性 + 时间）；
  - 行内「裁定无效」按钮 → **复用 2b 的同一个 dialog 组件**（抽成 `frontend/src/components/admin/` 下的 `ReviewRulingDialog.vue`，
    `complaint.vue` 与 `review-audit.vue` 共用，避免两处复制粘贴）。
- **不做**的：不建独立巡查页的第二套后端；不引入新的筛选维度（超出 PRD 范围）。

**2d · 前端单测**

- 新增 `frontend/src/components/admin/__tests__/` 下的 `ReviewRulingDialog.test.js`：
  - 理由 <10 字 → 不发请求（前端拦截）；
  - 理由合法 → 调 `rulingReview(id, {isValid:false, reason})`；
  - `isValid=true` 不会出现（按钮写死单向）；
  - in-flight 锁：连点两次只发一次请求。
- 参照 `frontend/src/components/__tests__/NlAdminTable.test.js` 的 mock 手法
  （`vi.mock('@/composables/useResponsive')` + element-plus stubs）。

**2e · 文档**

- `docs/spec/E4-review-credibility.md` §10.1 / §10.2 状态列 → `✅ 已交付`（附本轮日期）。
- `docs/spec/E4-tickets.md` T5 → `✅ 已交付`。
- `docs/prd/功能拓展规划.md:433` RTM R-E4 → 标注管理端两个入口已交付。
- `docs/api/08-admin.md` → 新增 §29 + 接口列表表加行。
- `CHANGELOG.md` `[Unreleased]` 补一条。
- `docs/agents/interaction-checklist.md` —— 若引入了清单未收录的新陷阱（如「表格行内二次 dialog 的 in-flight 锁」），
  补进清单（AGENTS.md §11 硬性要求）。

### 分支 3 · `feature/review-appeal-e2e`

**3a · `tools/e2e/` 下的 `e2e_review_appeal.py`**

- 风格：照 `tools/e2e/probe_*.py`（`# -*- coding: utf-8 -*-` + 模块 docstring + `sys.path.insert` + 复用 `_probe_http`）。
- **复用**：`from _probe_http import BASE, read_token, http`（已封装 JWT 过期识别 `[EXPI]` 指路）。
- **登录**：脚本自己真登录（`POST /api/auth/login` + `GET /api/auth/captcha` +
  `redis-cli.exe GET captcha:<key>` 取明文验证码 —— Python 无 `redis` 包，走 CLI，与 `frontend/e2e/fixtures/captcha.js` 同法；
  `REDIS_CLI` 环境变量可覆盖路径）。
- **5 条断言**（PRD §13.3）：

  | # | 断言 | 判据 |
  |---|---|---|
  | 1 | 回复 → 家属端 ≤ 3 秒收到站内信 | 陪诊员 `POST /api/review/{id}/reply` 后，轮询 `GET /api/message`（家属令牌）命中 `REVIEW_REPLIED` 且 **≤3s** |
  | 2 | 裁定 → 立即查 score 与手工 SQL 一致 | 裁定后 `GET /api/review/companion/{id}/score` 的均分 == `SELECT ROUND(AVG(score),2) ... WHERE is_valid=1 AND companion_id=?` |
  | 3 | 裁定后公开列表条数 −1、家属订单详情带标注 | `GET /api/review/companion/{id}` total −1；`GET /api/review/order/{orderId}` 的 `isValid=false` |
  | 4 | 陪诊员 A 用 B 的评价 ID 回复 → `3004` | comp001 拿 30003（属 companion 309）→ 业务码 `3004` |
  | 5 | 全站 grep 违禁词 → 空 | 扫 `backend/src/main` + `frontend/src` + `ai-service/app`，**排除显式 allowlist**（`ComplianceCheckUtil` / `ResultCode` / `ai-service/app/paths.py` / `elderHealth.js` 及其单测 / `medication.vue`），allowlist 每条带 `why` 注释 |

- **数据自建自清**：脚本自己造夹具（一条「未回复 + 属于某陪诊员」的评价），
  跑完删除（事务回滚或显式 `DELETE`），**不依赖种子数据的当前状态**。
  ⚠️ `tools/e2e/fixture.py` 的 `WRITE_TABLES` 已含 `order_review` / `complaint` → 走既有快照/还原机制最省事。
- 退出码：全绿 0，任一断言失败 1，前置缺失（令牌/服务未起）2（与 `probe_access_matrix.py` 口径一致）。
- **验收方式（用户已授权）**：用户提供 `MYSQL_PASSWORD`（只进环境变量，不写仓库）→
  `start-services.ps1` → `fixture.py snapshot` → 跑脚本 → `fixture.py restore` → `stop-services.ps1` →
  判据：5 条全绿 + 退出码 0 + 库水位回基线。
  ⚠️ 跑前必须先过 `tools/e2e/l0_identity_gate.py`（端口身份闸门）。

**3b · 文档同步**

- `tools/e2e/README.md` 索引表加一行（`e2e_review_appeal.py` | 作用 | 写库 ✅ | 需凭据 ✅ | 能进 CI ❌）
  + 「单脚本细节」小节（与其它脚本同款：前置、命令、判据、退出码）。
- `docs/spec/E4-review-credibility.md` §9.3 → `✅ 已交付`（列出 5 条实际断言口径，
  **特别是第 5 条的扫描面 + allowlist 口径与 PRD §13.3 原文的差异要写明**）。
- `docs/spec/E4-tickets.md` T7 → `✅ 已交付`。
- `docs/prd/PRD-E4-评价公信力闭环.md` §13.3 → 第 5 条补注「扫描面限定 + allowlist」。
- `CHANGELOG.md` `[Unreleased]` 补一条。

---

## Files to modify

### 分支 1（docs + 脚本）

| 文件 | 改动 |
|---|---|
| `tools/ai_flow/gate_check.py` | `MD_SKIP_DIRS` 拆根/任意两级；删 440–451 不可达代码 |
| `tools/ai_flow/test_gate_check.py` | +2 回归用例（15 → 17） |
| `docs/prd/PRD.md` | :306 i18n 预留改写 |
| `docs/design/design.md` | :419 图标约定改写 |
| `GLOSSARY.md` | :43,47 `PhoneLayout` → `MobileLayout` |
| `docs/adr/0016-matching-structured-and-explainable.md` | :68 补全 `backend/sql/tools/` 前缀 |
| `docs/reports/验收报告.md` | :179 realtime.js → message.js:102 |
| `docs/reports/script.md` | 4 行 PRD-V3.7 补后缀；:196 `V4..V7` 逐个列 |
| `docs/reports/iteration-v3.1-test-report.md` | :133 「未修」→ 已修 |

### 分支 2（后端 + 前端）

| 文件 | 改动 |
|---|---|
| `backend/src/main/java/org/company/nianglin/dto/` 下的 `AdminReviewQuery.java` | **新增** |
| `backend/src/main/java/org/company/nianglin/vo/` 下的 `AdminReviewVO.java` | **新增** |
| `backend/src/main/java/org/company/nianglin/service/AdminService.java` | +`reviewList` |
| `backend/src/main/java/org/company/nianglin/service/impl/AdminServiceImpl.java` | +`reviewList` 实现 |
| `backend/src/main/java/org/company/nianglin/controller/admin/AdminController.java` | +`GET /review` |
| `backend/src/test/java/.../controller/admin/AdminControllerReviewListTest.java` | **新增** |
| `backend/src/test/java/.../service/AdminServiceReviewListTest.java` | **新增** |
| `frontend/src/api/admin.js` | +`listAdminReviews` |
| `frontend/src/components/admin/` 下的 `ReviewRulingDialog.vue` | **新增**（共用裁定 dialog） |
| `frontend/src/components/admin/__tests__/` 下的 `ReviewRulingDialog.test.js` | **新增** |
| `frontend/src/views/admin/complaint.vue` | REVIEW_APPEAL 行裁定入口 |
| `frontend/src/views/admin/` 下的 `review-audit.vue` | **新增**（巡查页） |
| `frontend/src/router/routes.js` | +`AdminReviewAudit`（`/admin/review-audit`, `W-14`） |
| `docs/api/08-admin.md` | §二 表 +1 行；新增 §29 |
| `docs/spec/E4-review-credibility.md` | §10.1/§10.2 状态 |
| `docs/spec/E4-tickets.md` | T5 状态 |
| `docs/prd/功能拓展规划.md` | :433 RTM R-E4 |
| `docs/agents/interaction-checklist.md` | 新陷阱补录（若有） |
| `CHANGELOG.md` | `[Unreleased]` 补记 |

### 分支 3（e2e）

| 文件 | 改动 |
|---|---|
| `tools/e2e/` 下的 `e2e_review_appeal.py` | **新增** |
| `tools/e2e/README.md` | 索引表 +1 行 + 细节小节 |
| `docs/spec/E4-review-credibility.md` | §9.3 状态 + 5 条实际口径 |
| `docs/spec/E4-tickets.md` | T7 状态 |
| `docs/prd/PRD-E4-评价公信力闭环.md` | §13.3 第 5 条补注 |
| `CHANGELOG.md` | `[Unreleased]` 补记 |

---

## Reuse（不重写已有实现）

| 复用物 | 位置 | 用途 |
|---|---|---|
| `rulingReview(reviewId, {isValid, reason})` | `frontend/src/api/admin.js:102` | 裁定调用（已存在，**全仓零调用** → 本轮接上） |
| `getReviewByOrder(orderId)` | `frontend/src/api/review.js` | complaint.vue 反查 review id（免新增后端方法） |
| `requireInvolved` 的 ADMIN 放行 | `OrderServiceImpl` | `/api/review/order/{id}` 对管理员可用 |
| `confirmThen` | `frontend/src/utils/confirm` | 裁定二次确认（complaint.vue 已在用） |
| `usePagedList` | `frontend/src/composables/usePagedList.js` | 分页 + 写后回退 |
| `NlAdminTable` / `NlCard` / `NlEmpty` / `NlSkeleton` / `NlStatusChip` | `frontend/src/components/` | 双形态表格与空态（`admin/complaint.vue` 已用） |
| `UserNameResolver#resolveAll` | `backend/.../service/support/` | 批量姓名（避免 N+1） |
| `MaskUtil.name(...)` | `backend/.../util/MaskUtil.java` | 姓名脱敏（`ReviewVO.of` 同口径） |
| `ReviewVO.of` / `ReviewServiceImpl#parseTags` | 后端 | tags 解析口径参照 |
| `PageResult` / `PageQuery` | `backend/.../common/` | 分页结构与查询基类 |
| `AdminComplaintQuery` | `backend/.../dto/` | 新 DTO 的形态模板 |
| `_probe_http`（`BASE`/`read_token`/`http`） | `tools/e2e/_probe_http.py` | e2e HTTP 层 + JWT 过期识别 |
| `frontend/e2e/fixtures/captcha.js` 的思路 | Redis 明文验证码 | e2e 登录取码（Python 走 `redis-cli.exe`） |
| `fixture.py` / `restore_clean_baseline.py` | `tools/e2e/` | 写库前快照 / 跑完还原（`WRITE_TABLES` 已含 `order_review`/`complaint`） |
| `l0_identity_gate.py` | `tools/e2e/` | 跑 e2e 前的端口身份闸门 |
| `tools/scan_bad_chars.py` | `tools/` | 全仓 grep 违禁词时顺带扫损坏字符 |

---

## Steps

### 分支 1 · `docs/gate-and-path-fixes`（✅ 已提交，未 push）

- [x] 从 `main` 切 `docs/gate-and-path-fixes`
- [x] `gate_check.py`：`MD_SKIP_DIRS` 拆成 `MD_SKIP_ROOT_DIRS` / `MD_SKIP_ANY_DIRS`，`reports` 只在根层级生效
- [x] `gate_check.py`：删 `main()` 尾部 440–451 行不可达代码
- [x] `test_gate_check.py`：+3 用例（`docs/reports/` 被扫到 / 根 `reports/` 被跳过 / 构建产物任意层级都跳）→ **18 例**
- [x] 修 4 处活文档悬空（PRD.md / design.md / GLOSSARY.md / adr-0016）
- [x] 修 `docs/reports/` 4 处（验收报告 / script.md ×5 行 / iteration-v3.1）
- [x] 跑 `python tools/ai_flow/gate_check.py --only md --all-md` → **168 份 / 悬空 0 / 围栏 0**
- [x] 跑 `python -m unittest tools.ai_flow.test_gate_check -v` → **18/18**
- [x] 跑 `python tools/check_secrets.py`（1147 文件）+ `python tools/check_metric_dict_parity.py`（64 条）→ 全 PASS
- [x] 提交 → **不 push**

> 提交：`c45c60b` fix(gate) / `aa85e3f` docs

### 分支 2 · `feature/review-ruling-ui`（✅ 已提交，未 push）

- [x] 从 `main` 切 `feature/review-ruling-ui`
- [x] 后端：`AdminReviewQuery` / `AdminReviewVO` / `AdminService#reviewList` / `AdminServiceImpl#reviewList` / `AdminController#reviewList`
- [x] 后端单测：`AdminControllerReviewListTest`（2 例）+ `AdminServiceReviewListTest`（12 例）
- [x] `docs/api/08-admin.md`：§二 表 +1 行、新增 §29
- [x] 前端：`api/admin.js` + `listAdminReviews`
- [x] 前端：新增 `components/admin/ReviewRulingDialog.vue`（含 in-flight 锁、10–200 前置校验、单向裁定提示）
- [x] 前端：`admin/complaint.vue` 接 REVIEW_APPEAL 裁定入口
- [x] 前端：新增 `views/admin/review-audit.vue`（巡查页，筛选 + 表格 + 复用同一 dialog）
- [x] `router/routes.js` + `AdminReviewAudit`（`W-14`）
- [x] 前端单测：`ReviewRulingDialog.test.js`（7 例）
- [x] `mvn -B verify` → **1564 例 / 0 失败**（含 4 个因新构造器参数而适配的既有测试类）
- [x] `pnpm -C frontend run lint:check` → **0 警告**
- [x] `pnpm -C frontend run test` → **27 文件 / 269 例全绿**
- [x] 文档同步：spec §10.1/§10.2、tickets T5、RTM R-E4、`CHANGELOG`、interaction-checklist §2.7/§2.8/§4.10
- [x] 提交（拆 3 个 commit）→ **不 push**
- [x] ⚠️ 命中 `git-workflow.md` §7.4.2 判据 ③ → 建议标签名 **`v6.5`**，**待用户确认后再打**

> 提交：`fd8a20b` feat(admin) / `bb5da50` feat(admin) / `e131ff2` docs

### 分支 3 · `feature/review-appeal-e2e`（✅ 已提交，未 push）

- [x] 从**分支 2** 切 `feature/review-appeal-e2e`（计划允许的「分支 2 合入后」选项；
      直接从 `main` 切则两个分支都改 `CHANGELOG.md` 的 `[Unreleased]` 顶部，合并必冲突）
- [x] `tools/e2e/` 下的 e2e 脚本：登录（captcha 走 redis-cli）+ 自建夹具 + 5 条断言 + 自清
- [x] 第 5 条断言：限定扫描面 + 显式 allowlist（每条带 `why`）
- [x] `tools/e2e/README.md`：索引表 +1 行 + 单脚本细节
- [x] `MYSQL_PASSWORD` **环境里已就位**（无需再向用户索取）
- [x] `python tools/e2e/l0_identity_gate.py` → **10/10**（为此额外起了 ai-service 8081 与 vite 5141）
- [x] `python tools/e2e/fixture.py snapshot`
- [x] 起后端（`tools/e2e/start-services.ps1`，显式 `SPRING_PROFILES_ACTIVE=dev`）
- [x] `python tools/e2e/e2e_review_appeal.py` → **5 条断言全绿 + 退出码 0**
- [x] `python tools/e2e/fixture.py restore` + `verify` → **全部回到基线**（成对性 31 == 31）
- [x] `python tools/e2e/stop-services.ps1`（含额外起的 ai-service / vite）
- [x] 文档同步：spec §9.3、tickets T7、PRD §13.3 第 5 条补注、`CHANGELOG`
- [x] 提交 → **不 push**

> 提交：`289a624` test(e2e) / `b2198e3` docs

### 收尾

- [ ] 三分支分别报出合并建议，**等用户明确授权后再合并 / push**（红线：不自动 push）
- [ ] 清理 `docs/path-reconciliation` 分支（交接文档遗留）——**需用户确认**
- [ ] 更新 `plans/e4-handoff-completion.md`（本文件）→ 实际落地结果

---

## Verification

### 门禁 / 静态检查

```powershell
python tools/ai_flow/gate_check.py --only md --all-md      # 期望：168 份 / 悬空 0 / 围栏 0
python -m unittest tools.ai_flow.test_gate_check -v          # 期望：17/17
python tools/check_secrets.py                                # 期望：PASS
python tools/check_metric_dict_parity.py                     # 期望：PASS
python tools/e2e/check_api_imports.py                        # 期望：exit 0（新增 api/admin.js 引用后）
```

### 后端

```powershell
cd backend; mvn -B verify        # 期望：BUILD SUCCESS，含新增 2 个测试类
```

### 前端

```powershell
pnpm -C frontend run lint:check  # 期望：0 警告
pnpm -C frontend run test        # 期望：全绿（含新增 ReviewRulingDialog.test.js）
pnpm -C frontend run build       # 期望：构建通过
```

### e2e（本轮唯一需要用户配合的验证）

```powershell
$env:MYSQL_PASSWORD = "<用户提供>"          # 只进环境变量
$env:SPRING_PROFILES_ACTIVE = "dev"
python tools/e2e/l0_identity_gate.py          # 端口身份闸门
python tools/e2e/fixture.py snapshot
.\tools\e2e\start-services.ps1
python tools/e2e/e2e_review_appeal.py         # 期望：5 条断言全绿 + 退出码 0
python tools/e2e/fixture.py restore
python tools/e2e/fixture.py verify
.\tools\e2e\stop-services.ps1
```

### 人工自查（浏览器）

- 管理端 → 投诉管理 → 找到 `REVIEW_APPEAL` 行 → 「裁定无效」→ 填 10–200 字理由 → 确认
  → 列表刷新，该行不再出现裁定按钮。
- 管理端 → 评价巡查 → 筛选「已裁定无效」→ 能看到 `isValid=false` 的记录（**这是新端点的关键验证点**）
  → 点「裁定无效」→ 同一条记录消失 / 有效性翻转。
- 家属端 → 订单详情 → 评价卡片带「已裁定无效」标注（`family/order-detail.vue` 已有 `review.isValid === false` 分支，
  **本轮不改**，只需确认后端状态能透出）。

### 标签判据（AGENTS.md §11 最后一项）

`git diff --name-only main..HEAD | Select-String "^(backend/src/main|frontend/src)/"` 命中
→ 分支 2 合并后**需打标签**，建议 `v6.5`（`v6.1` 起为功能单元标签），**待用户确认后执行**。

---

## 实际落地结果（2026-11-25）

### 三个分支的提交

| 分支 | 提交 | 内容 |
|---|---|---|
| `docs/gate-and-path-fixes` | `c45c60b` | 门禁跳过目录改根层级锚定 + 删 14 行不可达代码（+3 回归用例，15→18）|
| | `aa85e3f` | 8 处文档悬空清零（活文档 4 + `docs/reports/` 4）|
| `feature/review-ruling-ui` | `fd8a20b` | 新增 `GET /api/admin/review` 端点（DTO/VO/Service/Controller + 14 例测试）|
| | `bb5da50` | 两个管理端裁定入口 + 共用对话框组件（+7 例前端单测）|
| | `e131ff2` | 口径收敛：spec §2.5/§8.3/§10.1/§10.2、tickets T5、RTM R-E4、08-admin §29、CHANGELOG、interaction-checklist 3 条新陷阱 |
| `feature/review-appeal-e2e` | `289a624` | `tools/e2e/` 下的 e2e 脚本（5 条断言 + 自建夹具 + 自清）|
| | `b2198e3` | spec §9.3、tickets T7、PRD §13.3 第 5 条口径注记、e2e README |

> 分支 3 建在**分支 2 之上**（计划允许的「分支 2 合入后」选项）——
> 两者都改 `CHANGELOG.md` 的 `[Unreleased]` 顶部，从 `main` 平切将来合并必冲突。

### 验证实测

| 项 | 结果 |
|---|---|
| 文档门禁 `--only md --all-md` | **168 份**（分支 1 修好后）· 悬空 0 · 围栏 0 |
| 门禁自测 | **18/18** 全绿 |
| 密钥体检 / 指标字典 | 1147 文件 / 64 条 全 PASS |
| `mvn -B verify` | **1564 例 / 0 失败** |
| `pnpm run lint:check` | **0 警告** |
| `pnpm run test` | **27 文件 / 269 例**全绿 |
| `l0_identity_gate.py` | **10/10** 全绿 |
| e2e 脚本 | **5 条断言全绿、退出码 0**（第 1 条实测耗时 **0.04s**）|
| `fixture.py restore` + `verify` | **全部回到基线**（成对性 `REVIEWED 订单 31 == order_review 31`）|

### 与计划的偏差（都是实测驱动的修正，不是缩水）

1. **门禁回归用例 +3 而非 +2** —— 多补了一条「构建产物目录名在任意层级都要跳」。
2. **e2e 脚本的自清改为按 id 水位划界**（计划未细说）：按 `biz_id` 删站内信会连带删掉
   该订单的**种子**站内信（ORDER_CREATED / ORDER_COMPLETED 等）→ 改成水位 + type 双限定。
3. **e2e 第 5 条的 allowlist 实际 9 个文件**（计划预估 5 个）：实测逐文件枚举后发现
   词表本体（`medical-advice-words.txt`）、提示组件（`NlMobileOnlyNotice.vue`）、
   反面断言（`data-loop.test.js`）也含违禁词 → 全部入册，每条带 `why`。
4. **e2e 脚本不用 `requests`**（T7 工单原文写「Python + requests」）：
   仓库其它 e2e 脚本一律用 `urllib`（`_probe_http.py`），本机 Python 也**没有** `requests` 依赖在册。
   沿用既有助手 = 不引新依赖、不装包。
5. **e2e 第 1 条用 REST 轮询而非直连 SSE**：消息落库与 SSE 推送在同一次
   `messageService.send` 调用中发出，「消息对家属可见」与「SSE 已推送」是同一时刻的两种观测；
   SSE 通道自身的连通性由 `frontend/e2e/specs/11-realtime.spec.js` 覆盖。

### 执行中发现但**不在本轮范围**的问题（已如实记录，未擅自修）

1. **`MatchingRealDbTest` 的一次既有失败**：用 `git stash` 对照证实**先于本轮改动就存在**。
   根因是本机库的**数据漂移**（订单 1066 是 2026-11-19 写路径新增的行，
   V7 的 backfill 迁移在它之前执行完，后插的行不会被回填）。
   CI 用全新 MySQL 容器 + Flyway → 不存在这行，**CI 是绿的**。
   已用 `restore_clean_baseline.py` 清掉写路径残留后转绿。
2. **真实产品缺口**：`OrderServiceImpl` 的下单路径**从不写** `service_region_code`
   → 运行期创建的订单该字段恒为 NULL，靠 V7 注释里「结构化列缺失时回退自由文本匹配」兜住。
   **这是 V3.4 匹配「区域」维度对新订单失效的隐患**，值得单独立项。
3. **陈旧的 `fixture.py` 状态文件**：`reports/e2e/fixture/state.json` 是 2026-11-19 那轮
   留下的（跑完没 restore），且它引用的影子表已被清理 → 它的 `restore` 会在删完行之后
   **中途失败**（影子表不存在）。已归档为 `state.stale-20261119.json` 后重新取基线。

---

## 风险与遗留

| 风险 | 缓解 |
|---|---|
| 新端点 `/api/admin/review` 扩大了评价可见面 | 只在 `/api/admin/**` 下（`SecurityConfig` 已 `hasRole(ADMIN)`）；姓名脱敏；`isValid=false` 的评价本来就对公众不可见，管理员看到的是「已被裁定」的记录 —— 不是新泄露 |
| e2e 写库污染共享环境 | 先 `fixture.py snapshot` → 跑完 `restore` + `verify`；脚本自带清理；口令只进环境变量 |
| 巡查页可能成为「没人用的大模块」 | spec 原设计有它；本轮只做「筛选 + 列表 + 裁定入口」三件事，不加维度 |
| 口径翻转早于代码合入 | spec 状态列在分支 3 统一翻，分支 1 不动 |
| `docs/reports/` 修完可能冒出新的悬空 | 修完立刻跑 `--all-md`，以实测为准，不预设 |
