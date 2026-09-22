# SPEC · 桌面 + 手机尺寸适配 v2

> **文档版本**:v2.0 · 2026-09-22
> **作者**:MATT grill-me + to-spec 流程产出
> **关联**:`docs/plan/desktop-adapt-2026-09.md`(v1,client view 居中限宽 720 + 形态解耦,已完成)
> `docs/adr/0007-desktop-form-factor.md`(形态与角色正交)
> `docs/adr/0008-mobile-only-pages.md`(mobile-only 路由)
> `AGENTS.md` §3.5(适老化硬约束,M11 验收)
> **覆盖**:admin 端电脑尺寸适配 + client 端手机尺寸适配
> **不在范围**:client 端 view 内部 desktop 分支(已在 v1 完成,本次只做手机端精修)

---

## 1. Problem Statement(用户视角的问题)

**1.1 管理端(AdminLayout)在中等屏与宽屏下不舒服**

管理员每天要在 1024 / 1280 / 1440 / 1920 四个常见 PC 尺寸上来回切。现状:

| 现状 | 问题 |
|---|---|
| AdminLayout 侧栏固定 220px 展开 | 1024 屏侧栏占 22%,主区被挤压到 800px |
| `dashboard` `kpi-grid` 固定 4 列 | 1024 屏 4 张 KPI 卡每张仅 200px 宽,数字与文字开始折行 |
| `dashboard` 图表 row 固定 `2fr 1fr` | 1280 屏折线图与圆环比例失衡,圆环被撑得过大 |
| `order / user / complaint / companion-audit / order-dispute` 直接用 `el-table`,8 列 | < 1280 屏横向滚动条一直存在,fixed=right 的操作列与表格主体错位 |
| `export` 表单单列 80+200 | 1440 屏右侧空一大片,毫无密度 |
| `oper-log` 无断点 | 同 `el-table` 问题 |

**1.2 客户端(走 MobileLayout 的 4 角色)在常见手机尺寸下不完美**

| 现状 | 问题 |
|---|---|
| 断点只有 `(max-width: 767px)` 一个 | 320 / 360 / 414 / 480 四个手机宽度同档处理;≤360 小屏 Android 字号偏大 padding 偏紧 |
| `NlPhoneShell` padding 固定 `--nl-gap-section` | 小屏与横屏没有差异化 |
| 弹窗 `el-dialog` 在 admin/order 等 560px 固定宽度 | 客户端 view 里 `< 480px` 的弹窗宽度超出可视 |
| `el-table` 仅 admin 用,client 端基本用卡片列表 | 已 OK;但 `family/medication` `elder-list` `profile` 仍用 `el-dialog` 弹详情,小屏需 fullscreen |
| 横屏模式(< 480 高) | 没有适配,顶栏 + Tab 占用屏幕高度过多 |

---

## 2. Solution(用户视角的解决方案)

**2.1 管理端**:在 3 档断点上重构 AdminLayout 与所有 admin view,让 1024 → 1920 五个常见尺寸都好看。

- **AdminLayout** 侧栏:< 1280 默认收起(64px icon-only),≥ 1280 展开(220px);保留手动切换
- **dashboard** kpi-grid 改 `repeat(auto-fit, minmax(180px, 1fr))`,窄屏 2 列、中屏 3 列、宽屏 4 列
- **dashboard** 图表 row:< 1024 改单列堆叠,≥ 1024 双列;比例用 `minmax(0, 2fr) minmax(280px, 1fr)` 防环图被撑爆
- **表格**(< 1280)→ **卡片化**:与 `family/order-list` 同款,字段折成行 + 「详情」按钮弹窗
- **表单**(export / oper-log)→ 大屏 max-width 居中 + 字段横排
- **主区 padding**:基于断点 (24/28/32/40)

**2.2 客户端**:在 v1 居中限宽 720 的基础上,精修手机尺寸的边角细节。

- 新增 `useResponsive(formFactor)` composable,统一封装断点监听 + viewport 状态
- 弹窗 `< 768px` 全屏(fullscreen);`≥ 768` 维持
- `NlPhoneShell` 新增 `(max-width: 360px)` 小屏断点:padding 收紧、字号保底 14
- 横屏 `(max-height: 480px) and (orientation: landscape)`:顶栏与 Tab 紧凑
- `iOS safe-area-inset-top` 状态栏高度(env 已支持 bottom,top 验证)

---

## 3. User Stories

### 3.1 管理员(ADMIN)

1. As an **ADMIN**, I want the **sidebar collapsed by default on 1024 screens**, so that the main content area gets enough horizontal space
2. As an **ADMIN**, I want the **KPI grid to be 2/3/4 columns based on viewport**, so that cards never feel cramped or too sparse
3. As an **ADMIN**, I want the **charts row to stack vertically on < 1024**, so that narrow screens don't have squeezed side charts
4. As an **ADMIN viewing tables on < 1280**, I want them rendered **as card lists** (status / name / phone / actions), so that I can see all rows without horizontal scrolling
5. As an **ADMIN viewing tables on ≥ 1280**, I want them to stay as **el-table with all columns visible**, so that dense information is preserved
6. As an **ADMIN on a 1920 screen**, I want the **content container to have wider max-width** (e.g. 1440px) with breathing room, so that data doesn't look lost in whitespace
7. As an **ADMIN filtering orders**, I want the **filter bar to wrap naturally** when the screen is narrow, so that the layout doesn't break
9. As an **ADMIN exporting data**, I want the **form to center at max-width 640px on large screens**, so that it doesn't stretch uncomfortably
10. As an **ADMIN viewing operation logs**, I want the same **card / table fallback** as other admin tables
11. As an **ADMIN switching between pages**, I want the **sidebar collapse state to persist across navigation** so I don't have to collapse it every time

### 3.2 业务角色(ELDER / FAMILY / COMPANION)在手机尺寸

12. As a **FAMILY user on a 360px Android phone**, I want the **page padding tightened and font not breaking 14px**, so that one screen shows enough content
13. As a **COMPANION user opening a dialog on a 414px phone**, I want the **dialog to go fullscreen**, so that buttons and form fields are easy to tap
14. As an **ELDER user in landscape orientation (< 480 high)**, I want the **navbar + tabbar to be more compact**, so that the content area gets enough vertical space
15. As a **FAMILY user on a 320px small Android**, I want the **buttons / cards not to overflow horizontally**, so that I can still tap them
16. As a **COMPANION user on iOS with notch**, I want the **status bar to respect safe-area-inset-top**, so that the navbar isn't covered by the notch
17. As any **client user**, I want the **senior's keyboard-popped layout** to not push the submit button off-screen, so that I can complete forms
18. As any **client user**, I want the **tabbar to be flush with the home indicator on iOS**, so that tapping feels native

### 3.3 跨形态一致

19. As any **user switching form factor** (rotating tablet / resizing window), I want **no flash / no jank** during breakpoint transition, so that the experience feels stable
20. As any **user**, I want the **elderly mode behavior unchanged** (only mobile triggers elderly mode), so that this work doesn't regress the v1 contract

---

## 4. Implementation Decisions

### 4.1 模块新增 / 修改清单

| 类型 | 文件 | 说明 |
|---|---|---|
| 新增 | `frontend/src/composables/useResponsive.js` | 统一封装 `useDevice` + breakpoint tier + formFactor-specific queries |
| 修改 | `frontend/src/styles/_breakpoints.scss` | SCSS 变量:$bp-sm 1024 / $bp-md 1280 / $bp-lg 1600 |
| 修改 | `frontend/src/styles/variables.scss` | 暴露上述变量给 SCSS 模块 |
| 修改 | `frontend/src/styles/index.scss` | 注册 `@media (max-width: 360px)` 的 .phone-shell 紧凑规则 |
| 修改 | `frontend/src/styles/elderly.scss` | 与新断点协同(老人模式覆盖) |
| 修改 | `frontend/src/components/NlPhoneShell.vue` | 加 small-screen 与 landscape 适配 |
| 修改 | `frontend/src/layouts/AdminLayout.vue` | 加断点驱动的 sidebar 折叠(< 1280 默认 64px);保留 toggle |
| 修改 | `frontend/src/store/modules/app.js` | 加 `setSidebarMode('collapsed' / 'expanded' / 'auto')` |
| 修改 | `frontend/src/components/NlDialog.vue`(新建)或 Element Plus 全局配置 | < 768 全屏;`>= 768` 维持 |
| 修改 | `frontend/src/views/admin/dashboard.vue` | kpi-grid auto-fit;图表 row 断点堆叠 |
| 新增 | `frontend/src/components/NlAdminTable.vue` | 统一封装「卡片化 fallback」模式,8 个表格 view 复用 |
| 修改 | `frontend/src/views/admin/order.vue` | 用 NlAdminTable 替代 el-table |
| 修改 | `frontend/src/views/admin/user.vue` | 同上 |
| 修改 | `frontend/src/views/admin/complaint.vue` | 同上 |
| 修改 | `frontend/src/views/admin/order-dispute.vue` | 同上 |
| 修改 | `frontend/src/views/admin/companion-audit.vue` | 同上 |
| 修改 | `frontend/src/views/admin/oper-log.vue` | 同上 |
| 修改 | `frontend/src/views/admin/export.vue` | 表单 max-width + label/input 列 宽响应 |
| 修改 | `frontend/src/views/family/medication.vue` | el-dialog 小屏 fullscreen |
| 修改 | `frontend/src/views/family/elder-list.vue` | el-dialog 小屏 fullscreen |
| 修改 | `frontend/src/views/profile/index.vue` | el-dialog 小屏 fullscreen |

### 4.2 架构决策

- **断点定义**:复用现有 `useDevice()` 的 matchMedia 监听,新增 `useResponsive()` 在其基础上加 tier 计算;**不**重起 matchMedia 实例,避免多个监听器竞争
- **响应式策略**:view 内部仍按 v1 约定的"isMobile + isDesktop"二元为主;admin 端引入 tier(3 档),client 端继续二元
- **Element Plus 弹窗 fullscreen**:`el-dialog` 提供 `fullscreen` prop;通过 `useResponsive()` 判断 `< 768` 时绑 `fullscreen=true`;在 `< 768` 时也可以走 `width="92vw"` 作为降级
- **侧栏状态持久化**:`localStorage.nl_admin_sidebar_mode`;reload 后读出。`auto` 模式(默认)按断点折叠
- **不**改老人模式相关 CSS:`elderly.scss` 与本次断点正交;`html.elderly-mode` 的字号覆盖优先级高于本文档

### 4.3 SCSS 变量

```scss
// _breakpoints.scss 暴露的公共变量
$bp-sm: 1024px;   // sm: 平板(横屏) / 小 PC
$bp-md: 1280px;   // md: 标准 PC(主流笔记本)
$bp-lg: 1600px;   // lg: 大屏

$bp-phone-xs: 360px;        // 小屏 Android
$bp-phone-landscape: 480px; // 横屏宽度阈值
```

### 4.4 composable 形态

```js
// useResponsive.js 暴露的接口(纯函数 + 模块级单例,延续 useDevice 的约定)
const { isSm, isMd, isLg, isXsPhone, isLandscapePhone } = useResponsive()
```

### 4.5 API / 数据契约

**不改动任何后端接口**。本次纯前端样式 + 组件组合改造,后端接口与数据结构零修改。

### 4.6 文件格式

- 全部用 `<script setup>`(AGENTS.md §3.3)
- SCSS 通过 `@use '@/styles/variables.scss' as *` 显式引入;**不**走 vite.config.js additionalData(AGENTS.md §3.4)
- ESLint 0 警告(AGENTS.md §3.2 / §6.2)

---

## 5. Testing Decisions

### 5.1 测试范围与策略

**5.1.1 单测(Vitest,AGENTS.md §6.2 提到 M12 起补):本次最小单测覆盖**

- `useResponsive.test.js`:断点判定 + tier 计算 + formFactor-specific query(纯函数逻辑)
- `NlAdminTable.test.js`:在 mock viewport 下渲染卡片 fallback / 表格 fallback

**5.1.2 E2E(Playwright,AGENTS.md §6.2):本次需要回归**

| Spec | 覆盖 |
|---|---|
| `01-auth.spec.js` | F-01 两会话回归(不变) |
| `02-admin-responsive.spec.js`(新增) | 1024 / 1280 / 1920 三个尺寸下 admin 端 8 个 view 渲染 + 切换 sidebar + 卡片/表格切换 |
| `04-client-mobile.spec.js`(新增) | 320 / 360 / 414 / 480 + 横屏(414×320)下 client 端 4 个 view 渲染 + 弹窗 fullscreen |

### 5.2 "好的测试"判据

- 只测外部行为(渲染结果、DOM 结构、class)
- 不测实现细节(不写死内部 component 名字)
- viewport 切换用 `await page.setViewportSize({ width, height })`,**不**写 CSS 变量断言

### 5.3 已有先例

- `frontend/e2e/specs/01-auth.spec.js`:F-01 两会话登录回归,viewport 切换 + localStorage 断言可复用
- `frontend/src/composables/useDevice.js` 是本次的同类先例(模块级单例 + matchMedia 监听)

---

## 6. Out of Scope

1. **client 端 view 内部 desktop 分支继续优化**:v1 已完成,本次只做手机端精修;desktop 端 view 内部细节不重写
2. **Element Plus 主题 / 全局变量调整**:不碰(AGENTS.md §3.4 提到主题改了会牵动老人模式)
3. **新加路由 / 新加接口**:不动 v1 路由表与 docs/api/
4. **admin 端新增功能(导出 / 日志 等加列、加字段)**:不动业务,纯 UI 适配
5. **登录页 / 错误页**:`login/index.vue` `error/403` `error/404` 已经在 mobile/desktop 双形态合理呈现,本次不动

---

## 7. Further Notes

### 7.1 风险与缓解

| 风险 | 缓解 |
|---|---|
| AdminLayout 侧栏 collapse 默认改后,管理员习惯被打破 | 默认 auto(按断点);保留手动 toggle |
| 8 个 admin view 改 NlAdminTable 一次性改太多,出 bug 难定位 | **分批**:先骨架(Layout + 断点系统 + composable + NlAdminTable),review 通过后再批量迁移 8 个 view |
| el-dialog fullscreen 后弹窗内表单布局挤压 | fullscreen 弹窗内仍用 NlCard 模式,padding 用 mobile 友好的 16px |
| 老人模式覆盖与新断点冲突 | 老年模式优先级 > 本文断点;测试覆盖 elderly-mode + 1024 边角 |

### 7.2 验收(M2 / M9 / M11 / M12 共 4 个竞讲节点都要测)

- `pnpm lint` 0 警告
- `pnpm exec playwright test specs/02-admin-responsive.spec.js` 全绿
- `pnpm exec playwright test specs/04-client-mobile.spec.js` 全绿
- 浏览器手动测:1024 / 1280 / 1440 / 1920 / 360 / 414 / 320 + 横屏
- 老人模式(只在 mobile 触发)与新断点并存验证
- 暗色 / 亮色主题(如有)在新断点下不破

### 7.3 后续工作(本次不做)

- 暗色主题(plan §3.2 提到的迭代)
- PWA / 离线缓存(plan §13 之后的扩展)
- Electron 打包(plan §15 之后的扩展)