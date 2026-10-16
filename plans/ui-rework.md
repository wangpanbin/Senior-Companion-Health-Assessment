# 银龄伴诊 前端 UI 重构 · R4 / R5 / R6 实施计划

> 依据：`C:/Users/wang/AppData/Local/Temp/handoff-nianglin-ui-r4.md`（交接文档）+ 用户四轮拍板
> 约束：`UI美化.md`（阶段五·分区实施，每轮暂停等确认）、`AGENTS.md`、`design.md` v1.1、`docs/adr/0007`
> 已提交计划：`plans/ui-rework.md`（本文件即最终版，按 R4 → R5 → R6 顺序执行）

---

## Context

上一会话完成 R1（令牌收口）/ R2（语义色归位）/ R3（键盘可达与焦点），**49 文件 +388/−270 全部未提交**。
本轮继续推进剩余三轮：

| 轮次 | 内容 | 核心问题 |
|---|---|---|
| **R4** | Error/Empty 态分离 | 36 处数据加载 `catch` 把失败清成 `[] / null`，页面显示「暂无数据」——用户分不清「本来没数据」和「加载失败」 |
| **R5** | 方向 A 视觉落地 | `family/home` 的 `love-banner`（唯一 AI slop）、快捷入口 `desc` 副标题、卡片投影（17 处，违背 VISUAL_DENSITY=6） |
| **R6** | 桌面 ≥1280 双栏 | 全站桌面内容被 `NlDesktopShell` 锁死 720px 单列，1920 屏大量留白 |

已拍板的决策（本计划据此展开）：

- R4 粒度 = **分区级**（每个卡片/列表各自 `loadError` + 重试），不新增 composable，逐页手写 ref。
- R5 = **卡片去投影，靠 1px 边框分层**（`--nl-shadow-float` 保留给 Tab Bar / 弹层）。
- R6 = **壳层统一改**（只动 `NlDesktopShell` + 1 个宽度 token），≥1280 变两列。
- 验证 = `lint:check` + `vitest` + **E2E 子集**（需起后端，已获授权）。

红线（`UI美化.md` §2.1）：不改路由 / API / 表单语义 / props-emits 契约 / 状态管理机制 / 功能入口；
**不得覆盖上一会话未提交的 R1–R3 改动**；不自动 `git commit` / `push`。

---

## 现状勘察结论（已核对代码）

### R4 触点（脚本清点：catch 后清空数据的位点，行号为当前工作区状态）

| 文件 | 行号 → 加载函数 | 区块 |
|---|---|---|
| `views/family/medication.vue` | 150 loadElders / 163 loadToday / 192 loadCalendar / 207 loadPlans / **317 loadMedicineOptions** | 老人切换 + 今日 + 日历 + 计划 + 「添加计划」弹窗药品下拉 |
| `views/family/home.vue` | 60 loadElders / 82 loadOrders / 103 loadTodayTasks | 就诊人 + 最近订单 + 今日用药 |
| `views/elder/medication.vue` | 104 loadToday / 118 loadCalendar / 131 loadPlans | 同家属用药三区块 |
| `views/elder/home.vue` | 85 loadTodayMeds / 97 loadRecentOrders | 今日用药 + 最近订单 |
| `views/family/elder-list.vue` | 54 loadElders / **68 openDetail**（详情抽屉） | 列表 + 抽屉详情 |
| `views/family/order-complaint.vue` | 66 loadOrder / 79 loadHistory | 订单头 + 投诉历史 |
| `views/family/order-review.vue` | 72 loadOrder / 85 loadExistingReview | 订单头 + 已评价检测 |
| `views/family/order-list.vue` 55、`views/family/order-step1.vue` 40 | loadOrders / loadElders | 各 1 区块 |
| `views/companion/`（8 个文件） | entry 56 loadApplication / execute 113 loadAll / hall 83 loadHall / income 64 loadIncome / message-detail 69 loadMessage / message 72 load / order-list 41 loadOrders / reviews 41 load | 各 1 区块 |
| `views/admin/`（7 个文件） | companion-audit 38 / complaint 36 / oper-log 29 / order-dispute 33 / order 51 / user 37（均 loadList）+ dashboard 288 loadDashboard | 各 1 区块（dashboard 为整页多图） |

合计 **35 个页面区块**（`family/medication` 的 `confirmTaken:246` 是提交动作，拦截器已弹 toast，**不改**）。

**已有范式**：`views/family/order-detail.vue` 的 `loadError` ref + 三段式 template（`NlSkeleton → NlEmpty(network) → 正文`）。
`components/NlEmpty.vue` 已内置 `type = empty | 403 | 404 | network`（network = 「网络开小差了」）+ `actionText` + `@action`，
`design.md` §3.6「状态三件套」与 `docs/agents/FRONTEND_CONTRACT.md` §6 本就要求错误态 —— **零新组件，只接线**。

**不在 R4 范围**：登录验证码、提交类 `catch`、未读轮询、`order-detail` 三个次级请求的内联 `.catch(() => [])`
（该页主请求已有错误态，次级降级是文档里写明的取舍）。

### R5 触点

- `views/family/home.vue`：`love-banner` 模板 333–342 + 样式 769+；`quickEntries` 125–127 的 `desc` 字段、
  移动端 `quickgrid__desc`（242 + 样式 668）、桌面端 `desktop-quick` 的 desc 行（420）。
- 卡片投影 17 处：`components/NlCard.vue:55`、`styles/index.scss:258`（全局 `.nl-card`）、
  `components/NlAdminTable.vue:138`、`components/NlMobileOnlyNotice.vue:88`、
  `views/family/home.vue:584,627`、`views/admin/dashboard.vue:437`、`views/companion/hall.vue:381`、
  `views/companion/income.vue:164`、`views/companion/reviews.vue:154`（SCSS 变量写法）、
  `views/login/index.vue:298,322,356`；另 `components/NlSection.vue:40`（死组件，见「范围外」）。
- `NlCard.vue` / `index.scss` 的 `.nl-card--hover` 目前靠 `--nl-shadow-hover` 提影 → 改边框变色。

### R6 触点

- 宽度唯一真源：`styles/variables.scss:144` `$nl-desktop-content-width: 720px`，
  被 `NlDesktopShell.vue:144`（顶栏 inner）与 `:227`（内容 `__content`）消费。
- `NlPageShell` 把正文透传进 `NlDesktopShell` 默认插槽；`routes.js` 里 F/E/C 全部页面在 ≥768 走这套壳。
- **实现要点**：`.nl-card` 等插槽子元素不带 `NlDesktopShell` 的 scope id，选择器必须走 `:deep()`。
- `frontend/e2e/specs/10-form-factor.spec.js` 只断言 `.nl-shell` / `.nl-desktop-shell` 的存在，**没有任何 720px 宽度断言**，
  R6 不会打红现有 E2E；`12-admin-responsive` 走的是 `AdminLayout`（独立布局，不受 R6 影响）。

---

## Approach

### R4 · 分区错误态（35 区块，纯增量）

每个数据区块在其「loading 骨架」与「空态」之间插入错误分支：

```js
// script：与既有 xxxLoading 并列，命名随区块
const todayError = ref(false)
async function loadToday() {
  todayLoading.value = true
  try {
    /* ...既有加载... */
    todayError.value = false          // ← 成功时复位，重试后能回到正常态
  } catch {
    todayTasks.value = []             // ← 保留既有清空逻辑（不改行为，只加标记）
    todayError.value = true
  } finally {
    todayLoading.value = false
  }
}
```

```vue
<!-- template：插在 loading 与 empty 之间 -->
<NlSkeleton v-if="todayLoading" :count="3" />
<NlEmpty
  v-else-if="todayError"
  type="network"
  title="今日用药加载失败"
  action-text="重试"
  @action="loadToday"
/>
<NlEmpty v-else-if="!todayTasks.length" type="empty" title="…" … />
<ul v-else …>
```

统一口径：

1. 错误态一律 `type="network"`（组件自带「请检查网络后点击重试」副标题），`title` 用「&lt;区块名&gt;加载失败」；
   `action-text="重试"`，`@action` 指向**该区块自己的** load 函数（不做整页重载）。
2. 弹窗 / 抽屉类区块（`elder-list` 详情抽屉、`medication` 药品下拉）同样插入 `NlEmpty type="network"`，就地重试。
3. **不改**任何请求参数、不清除既有 `catch` 内的清空语句、不新增弹 toast（拦截器已弹，`FRONTEND_CONTRACT §6.3` 口径）。
4. 老人模式兼容：`NlEmpty` 已按 `--nl-*` 令牌排版，无需额外处理。

### R5 · 方向 A 视觉落地（去 slop + 去投影）

1. 删 `family/home.vue` 的 `love-banner`（模板 + 样式 + 相关注释）。
2. `quickEntries` 去 `desc`：数组删字段、移动端删 `quickgrid__desc` 行与样式、桌面端删 desc 行（label 保留，触控区仍 ≥48px 不受影响）。
3. **卡片去投影**：所有卡片级 `box-shadow: var(--nl-shadow-card)` 删除，靠既有 1px `--nl-border` 分层；
   `.nl-card--hover`（`NlCard.vue` + `index.scss` + `login/index.vue` 角色 chip）的 hover 提影改为
   `border-color: var(--nl-primary)`（按压态 `translateY(1px)` 保留）。
   - `--plain` 卡片（无边框无底）本来就没投影，不动。
   - `--nl-shadow-card` 令牌**保留定义**（design.md §2.4 三档仍成立），但在 design.md 标注「卡片不再使用，仅小浮层（头像环等）可用」；
     家属首页头像环（home.vue:627）本轮一并去投影。
4. `login/index.vue` 的 hero 阴影（298）删除（渐变底已足够分层）。

### R6 · 桌面 ≥1280 双栏（壳层统一）

在 `NlDesktopShell.vue` 增加 `@media (min-width: 1280px)`：

```scss
&__inner  { max-width: $nl-desktop-content-wide; }   // 顶栏与正文同宽对齐
&__content {
  max-width: $nl-desktop-content-wide;               // 新 token：1120px（720 保留给 768–1279 档）
  display: flex;
  flex-flow: row wrap;
  gap: $nl-space-5;
  :deep(> *)        { flex: 1 1 100%;  min-width: 0; }   // 默认通栏：筛选条/列表/骨架/空态/免责/CTA
  :deep(> .nl-card) { flex: 1 1 calc(50% - #{$nl-space-5} / 2); } // 卡片两两成行
}
```

选 flex-wrap 而非 grid 的原因：**同一线内剩余空间按 flex-grow 均分** —— 孤立的一张卡片会自动撑满整行，
不会出现「半宽卡片 + 右侧空洞」；相邻两张卡片自动并排。这样全站 25 个桌面页面一次性生效，
不需要逐页加通栏标记，读序（从左到右、从上到下）也保持不变。

配套改动：

- `styles/variables.scss`：新增 `$nl-desktop-content-wide: 1120px`（注释写清两档语义）。
- 注释同步：`NlDesktopShell.vue`（720 相关注释）、`NlPageShell.vue`（「720 居中列」注释）、
  `views/family/home.vue` 等 view 里写死「720px」的说明文字。
- **ADR-0007 追加「2026-10 变更」小节**：记录 ≥1280 双栏决策、flex-wrap 配对规则、720 保留为 768–1279 档；
  按 ADR 尾注的规矩，决策源变更必须与实现同一个 PR 落地。
- E2E 补一条断言（`10-form-factor.spec.js`）：1280×800 下 `/family/home` 的前两张相邻 `.nl-card`
  boundingBox 同行（`y` 相同、`x` 不同），防回归。

---

## Files to modify

**R4**（35 区块 / 19 文件，全部在 `frontend/src/views/`）：
`family/medication.vue`、`family/home.vue`、`family/elder-list.vue`、`family/order-complaint.vue`、
`family/order-review.vue`、`family/order-list.vue`、`family/order-step1.vue`、
`elder/medication.vue`、`elder/home.vue`、
`companion/{entry,execute,hall,income,message,message-detail,order-list,reviews}.vue`、
`admin/{companion-audit,complaint,dashboard,oper-log,order,order-dispute,user}.vue`

**R5**：`views/family/home.vue`、`views/login/index.vue`、`views/admin/dashboard.vue`、
`views/companion/{hall,income,reviews}.vue`、`components/NlCard.vue`、`components/NlAdminTable.vue`、
`components/NlMobileOnlyNotice.vue`、`styles/index.scss`

**R6**：`components/NlDesktopShell.vue`、`styles/variables.scss`、`components/NlPageShell.vue`（注释）、
`docs/adr/0007-desktop-form-factor.md`、`frontend/e2e/specs/10-form-factor.spec.js`、注释涉及的 view

**文档同步**：`design.md`（§3.2 卡片公式去投影、§2.4 阴影档位注记、版本记录 → v1.2）、
`docs/agents/FRONTEND_CONTRACT.md` §6.3（异常条目从「catch 清空列表」改为「清空 + 分区错误态」）

## Reuse

- `components/NlEmpty.vue`：`type="network"` + `actionText` + `@action`（不新增组件）
- `components/NlSkeleton.vue`：加载态（禁整页转圈）
- 范式来源：`views/family/order-detail.vue`（`loadError` ref 三段式）
- 断点基建：`composables/useDevice.js`（形态判定不动；R6 只加纯 CSS 媒体查询，不新增 JS 断点）
- 验证基建：`frontend/e2e/`（`pageAudit.js` 落地路径断言 + 7 个种子账号 + 验证码自动旁路）、`tools/e2e/start-services.ps1`

## Steps

### R4（先做，每完成一个角色组跑一次 lint + test）

- [x] R4-1 家属端：`family/medication`（4 页内区块 + 弹窗下拉）、`family/home`（3）、`family/elder-list`（2，含抽屉）、
      `family/order-complaint`（2）、`family/order-review`（2）、`family/order-list`、`family/order-step1`
- [x] R4-2 老人端 + 陪诊员端：`elder/medication`（3）、`elder/home`（2）、`companion/` 8 文件各 1
- [x] R4-3 管理端：`admin/` 7 文件（`dashboard` 为整页级 `loadDashboard` 错误态）
- [x] R4-4 文档同步：`FRONTEND_CONTRACT.md` §6.3 改写为「catch 清空 + `xxxError` 分区错误态 + 重试」
- [x] R4-5 汇报本轮（改动文件、区块清单、验证结果、风险），**暂停等确认**

### R5

- [x] R5-1 删 `family/home.vue` 的 `love-banner`（模板 + 样式 + 注释）
- [x] R5-2 `quickEntries` 去 `desc`（数组 + 移动端 + 桌面端 + 样式）
- [x] R5-3 卡片去投影：`NlCard.vue`、`index.scss`、`NlAdminTable.vue`、`NlMobileOnlyNotice.vue`
      及 `family/home` / `admin/dashboard` / `companion/hall|income|reviews` / `login/index` 的散落处；
      hover 提影改 `border-color: var(--nl-primary)`
- [x] R5-4 `design.md` → v1.2（§3.2 卡片公式、§2.4 注记、版本记录）
- [x] R5-5 汇报本轮并**暂停等确认**

### R6

- [x] R6-1 `variables.scss` 加 `$nl-desktop-content-wide: 1120px`；`NlDesktopShell` 加 ≥1280 flex-wrap 双栏（`:deep()` 规则）
- [x] R6-2 ADR-0007 追加「2026-10 变更」小节；`NlPageShell` / `NlDesktopShell` / 相关 view 的 720 注释同步
- [x] R6-3 `10-form-factor.spec.js` 补「≥1280 两张相邻卡片同行」断言
- [x] R6-4 浏览器排版巡检：`family/home`、`companion/hall`、`order-detail`、`family/medication`、
      `order-list`（列表页应保持通栏）在 1280 / 1440 / 1920 三档下的观感，逐页修正异常配对
- [x] R6-5 汇报本轮并**暂停等确认**

## Verification

1. **每轮固定**：`pnpm -C frontend run lint:check`（0 警告）+ `pnpm -C frontend run test`（4 文件 / 34 用例）。
2. **E2E 子集**（R6 收尾后执行，需先起服务）：
   ```powershell
   $env:MYSQL_PASSWORD = "<按本机 MySQL 口令>"
   .\tools\e2e\start-services.ps1          # MySQL/Redis + 后端 8080
   cd frontend
   pnpm exec playwright test specs/10-form-factor.spec.js specs/03-elder.spec.js `
        specs/04-family-order.spec.js specs/05-companion.spec.js specs/08-admin.spec.js `
        specs/12-admin-responsive.spec.js
   ```
   说明：E2E 会 snapshot → 跑 → restore 种子库；只想只读巡检可设 `E2E_FIXTURE=0`。
3. **错误态人工验证**（浏览器）：停掉后端或断网后访问 `/family/home`、`/family/medication`、
   `/admin/dashboard` —— 必须出现「××加载失败 / 重试」，且重试按钮恢复后端后能回到正文；
   同一页面在后端正常时的空列表仍显示「暂无数据」（两种态不混淆）。
4. **老人模式抽查**：390×844 + `localStorage.nianglin_elderly_mode='true'`，确认新增的错误态文案 ≥18px、
   重试按钮 ≥48px（`elderly.scss` 覆盖对 `NlEmpty` 内按钮生效）。
5. **如实披露**：未跑的项一律标「未验证」，不宣称未执行的测试（`UI美化.md` §10）。

## 已知风险

- R6 的 flex 配对是**全站生效**：如果某页正文恰好是「一张卡片 + 一段通栏文本」，卡片会撑满整行（符合预期）；
  但若出现「卡片 + 通栏 + 卡片」，两张卡片会分处两行 —— R6-4 巡检时按页微调（必要时给该页加一行
  通栏包裹，仍是 view 内部样式，不碰契约）。
- R4 逐页手写 `xxxError` 有约 35 处样板重复，靠 lint + 人眼 review 保证一致（用户已明确不要 composable）。
- 工作区里 R1–R3 的 49 个文件改动**尚未提交**，本轮改动会叠加其上；`git diff` 复核时注意区分。
- 行尾：`styles/*.scss` 是 CRLF、`.vue` 是 LF，编辑时按文件保留（交接文档 §4.4）。

## 范围外（本轮不做，已知待办）

- 死代码清理：`NlStatusBar` / `NlSection` / `ModulePlaceholder` / `PagePlaceholder` /
  `views/{elder,family}/message.vue`（路由已直连 `companion/message.vue`，两文件确为 0 引用）、
  `$bp-tablet` / `$bp-desktop` —— **删文件属红线操作，需用户单独授权**。
- `prefers-reduced-motion` 全站零处理（`UI美化.md` §9 要求，属收尾小项）。
- 4 组命名不一致（「抢单」vs「接单」、「预约挂号」指向陪诊下单等）——改文案涉及入口语义，需单独拍板。
- 多根组件根元素 `@click` 的键盘可达补漏。
