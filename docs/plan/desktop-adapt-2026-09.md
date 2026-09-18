# 前端桌面端适配执行计划 · 2026-09-18

> **来源**:经过 grill-with-docs 三轮 12 题决议落地。
> **关联**:`CONTEXT.md` · `docs/adr/0007-desktop-form-factor.md` · `docs/adr/0008-mobile-only-pages.md` · `AGENTS.md`
> **目的**:把"全员响应式 + 居中限宽 720 + 形态解耦"做成可执行的 3 阶段计划。

---

## 1. 决策摘要(锁定 · 不再返工)

| # | 问题 | 决策 |
|---|---|---|
| Q1 | 适配范围 | **B** · F/E/C 全员 |
| Q2 | 内容策略 | **①** · 居中限宽 |
| Q3 | 形态切换策略 | **b** · 组件内响应式 |
| Q4 | 老人模式在桌面端 | **II** · 禁用 |
| Q5 | 居中宽度档位 | **β** · 720px(单档) |
| Q6 | 老人模式禁用落地 | **a+d** · 行为防御 + CSS 兜底 |
| Q7 | 范围分级 | **Ⅱ** · FAMILY 全 + COMPANION 3 高频 + ELDER 2 |
| Q8 | 形态切换实现 | **C** · `useDevice()` composable |
| Q9 | Desktop shell 内容 | **β** · 含简化顶栏(64px) |
| Q10 | loading/empty/error | **③** · view 自管 |
| Q11 | 宽度生效方式 | **11** · 直接写死在 NlDesktopShell |
| Q12 | 切换防抖 | **c** · `matchMedia` 监听断点跨越 |

---

## 2. 命名清单(一次性)

| 旧 | 新 | 操作 |
|---|---|---|
| `layouts/PhoneLayout.vue` | `layouts/MobileLayout.vue` | `git mv` + 内容基本不变 |
| (无) | `layouts/DesktopLayout.vue` | 新建 |
| `components/NlPhoneShell.vue` | 不变(它本来就是 mobile 壳) | — |
| (无) | `components/NlDesktopShell.vue` | 新建 |
| (无) | `composables/useDevice.js` | 新建 |
| (无) | `components/NlMobileOnlyNotice.vue` | 新建 |
| `routes.js` 的 `PhoneLayout` import | 改为 `MobileLayout` | grep + replace |

> 老 `PhoneLayout.vue` 在 Stage 2 末尾前保留;Stage 3 完成后 `git rm`。

---

## 3. 三阶段切分(总工作量 9-13 天,2 人并行)

```
Stage 1 ──▶ Stage 2 ──▶ Stage 3
[ 2-3 天 ] [ 5-7 天 ]  [ 2-3 天 ]
[ 1 人   ] [ 2 人   ]  [ 1 人   ]
架构落地   批量迁移    防御 + 收尾
```

---

### Stage 1 · 形态基础(2-3 天 · 1 人)

**目标**:把"Mobile/Desktop 形态与角色解耦"这条架构路线跑通,用 `family/home` 当示例。

**文件清单**:

| 文件 | 类型 | 说明 |
|---|---|---|
| `frontend/src/composables/useDevice.js` | 新建 | `useDevice()` 返回 `{ isMobile, isDesktop, deviceMode }`;内部用 `matchMedia('(max-width: 767px)')` 监听 |
| `frontend/src/layouts/MobileLayout.vue` | 新建(从 PhoneLayout 复制) | `git mv` 旧 PhoneLayout.vue |
| `frontend/src/layouts/DesktopLayout.vue` | 新建 | 路由进来后,根据 `useDevice().isMobile` 判断是否要 redirect 到 MobileLayout;否则渲染 router-view(由 view 内的 v-if 决定壳) |
| `frontend/src/components/NlDesktopShell.vue` | 新建 | `<div class="nl-desktop-shell">` 套 router-view;顶部 64px 顶栏(Logo + `route.meta.title` + `<slot name="actions">`);内容区 `max-width: 720px; margin: 0 auto; padding: 24px;` |
| `frontend/src/router/routes.js` | 改 import | `PhoneLayout` → `MobileLayout`(注意根 item 单行改) |
| `frontend/src/views/family/home.vue` | 改造 | 顶部加 `import { useDevice } from '@/composables/useDevice'`;在 `<NlPhoneShell>` 外层加 `<NlDesktopShell v-if="isDesktop">…桌面版简化…</NlDesktopShell>` 包住;`<NlPhoneShell v-else>` 保留手机版 |

**验收**:

1. `pnpm lint` 通过(0 警告)
3. 浏览器分别在 **360 / 768 / 1280 / 1920** 四个宽度下手测 `/family/home`:
   - 360 → 走手机壳,样式与原版完全一致
   - 768 → 走手机壳(因 `max-width: 767px`,断点 = 768 时 desktop)
   - 1280 / 1920 → 走 NlDesktopShell,内容居中限宽 720px,左右留白
5. 拖拽浏览器边缘从 360 拉到 1280,断点跨越瞬间切换(不闪)<sup>Q12 c</sup>
6. 老人模式开关在 1280 宽屏下被禁用 + 隐藏<sup>Q6 a+d</sup>

---

### Stage 2 · 批量迁移(5-7 天 · 2 人并行)

**目标**:把剩下的 16 个 view + 4 个 mobile-only 元数据补齐。

**子分组**:

- **2-A FAMILY 全员(12 view)**:home / elder-list / elder-bind / order-list / order-detail / order-review / order-complaint / order-step1 / order-step2 / order-step3 / medication / message
- **2-B COMPANION 高频(3 view)**:hall / order-list / income
- **2-C ELDER 高频(2 view)**:home / medication
- **2-D Mobile-only 元数据(4 路由)**:meta.mobileOnly + NlMobileOnlyNotice

**每个 view 的改造模板**(伪代码):

```vue
<script setup>
import { useDevice } from '@/composables/useDevice'
const { isMobile } = useDevice()
</script>

<template>
  <NlPhoneShell v-if="isMobile">
    <!-- 原手机版模板,完全不动 -->
  </NlPhoneShell>

  <NlDesktopShell v-else>
    <template #actions>
      <!-- 桌面版专用右上角按钮(可选) -->
    </template>
    <!-- 桌面版简化内容:可复用手机模板,也可重排 -->
    <div class="desktop-view">…</div>
  </NlDesktopShell>
</template>
```

**关键约束**(每人必须遵守):

1. **桌面版内容宽度永远不超过 720px**(由 NlDesktopShell 容器控制,view 内不要写死 width)
2. **不要引入新的 element-plus 复杂组件**(不要在桌面版用 `el-table` / `el-dialog` / `el-drawer`,降低风险;沿用 `NlCard` `NlListRow` 等成熟组件)
3. **view 自管 loading / empty / error**(Q10 ③),不要把这些状态提到 NlDesktopShell 里
4. **路由 meta.title 不动**(已经是上游约定),Desktop shell 直接 `route.meta.title` 渲染
5. **mobile-only 路由不要做 view 内部 v-if**,改在 NlDesktopShell 检测 meta 后整体替换为 NlMobileOnlyNotice

**2-D mobile-only 清单**:

| 路由 | view | 操作 |
|---|---|---|
| `/elder/message` | views/companion/message.vue | routes.js 加 `meta.mobileOnly: true` |
| `/companion/entry` | views/companion/entry.vue | 同上 |
| `/companion/execute/:id` | views/companion/execute.vue | 同上 |
| `/profile` | views/profile/index.vue | 同上 |

**NlMobileOnlyNotice 组件规范**:

- props: `routeTitle`(string)
- 渲染:居中卡片(420×320),标题 + 描述 + 二维码占位 + "继续查看" 按钮
- 按钮 click → `sessionStorage.setItem('force-mobile', 'true')` + `location.reload()`(刷新后 useDevice 强制 isMobile)

**验收**(Stage 2 整体):

1. 28 个 URL 在 360 / 1280 两档宽度下,前 24 个走 view 响应式、后 4 个走 mobile-only 提示页
2. `pnpm lint` 通过
3. 回归测试:`family/home` 在 1280 宽屏下点击"新增订单"流程,流程页(order-step1/2/3)同样能正常走完(不会出现 mobile-only 误判)

---

### Stage 3 · 防御 + 收尾(2-3 天 · 1 人)

**目标**:老人模式防御、matchMedia 切防抖、清理旧文件、文档收尾。

**任务清单**:

1. **Q6 a+d 落地**:
   - `store/modules/app.js` 的 `setElderlyMode()` 增加 `if (window.innerWidth >= 768) return`
   - `applyElderlyClass()` 内 `if (window.innerWidth >= 768) classList.remove('elderly-mode')`(即使 localStorage 残留了 true 也不挂 class)
   - `styles/elderly.scss` 顶部加兜底:`@media (min-width: 768px) { :root { --nl-font-body: 16px !important; --nl-touch-min: 44px !important; } }`(以防 CSS class 真的挂上去了)
   - 视图层:`family/home.vue` 的老人模式开关按钮在 desktop 下 `v-if="isMobile"` 隐藏

2. **Q12 c matchMedia 落地**:`useDevice.js` 内部用 `matchMedia('(max-width: 767px)')`;注册 `change` 事件,触发 reactive ref 翻转;只在断点跨越时切换,resize 不闪

3. **清理旧文件**:
   - `git rm frontend/src/layouts/PhoneLayout.vue`
   - `grep -r PhoneLayout frontend/src` 必须为 0 结果

4. **文档收尾**:
   - 在 `AGENTS.md` §3 新增"§3.11 形态与响应式"小节,描述 Mobile/Desktop 形态
   - 在 `CONTEXT.md` 末尾补一节"v1 落地状态"(Stage 3 完成后填)
   - 给 28 个 view 各打一份"已响应式 / mobile-only" 状态标签

5. **测试**:
   - `useDevice.js` 单测(Vitest):jsdom + mock matchMedia,断点跨越触发 ref 翻转
   - `NlDesktopShell.vue` 单测:渲染正确,顶栏 / 内容区 slot 正确
   - `NlMobileOnlyNotice.vue` 单测:按钮点击触发 sessionStorage 写入

**验收**:

1. 360 → 1280 拖拽浏览器,断点跨越**不闪**
2. localStorage 手动塞 `elderly-mode=true`,在 1280 宽屏下刷新,老人模式 CSS 不挂 class、字号正常
3. mobile-only 路由在桌面下访问,看到 NlMobileOnlyNotice;点"继续查看",sessionStorage 有 `force-mobile=true`,刷新后变成手机版
4. `pnpm lint` + `vitest run` 全绿
5. `git grep PhoneLayout frontend/` = 0

---

## 4. 工作量与人力

| Stage | 工作量 | 周期 | 人力 |
|---|---|---|---|
| Stage 1 形态基础 | 2-3 天 | 第 1 周 | 1 人 |
| Stage 2 批量迁移 | 5-7 天 | 第 2 周 | 2 人并行 |
| Stage 3 防御收尾 | 2-3 天 | 第 3 周初 | 1 人 |
| **合计** | **9-13 天** | **2-3 周** | **2 人并行峰值** |

---

## 5. 沟通节奏

- **每日**:阶段内部无需强制同步,有 blocker 立刻拉
- **每周二 / 周五 17:00**:项目群同步进度截图(进度看板 + 当周 blocker)
- **Stage 1 完成时**:演示 1 次给团队 + 用户(录一段 360 → 1920 拖拽视频)
- **Stage 2 完成后**:内测一轮(挑 FAMILY 真实账号 + COMPANION 真实账号各 1 个)
- **Stage 3 完成后**:正式合入 `develop` 分支

---

## 6. 风险与回退

| 风险 | 概率 | 回退 |
|---|---|---|
| Stage 1 跑不通(架构路线证伪) | 低 | 撤回 Plan,改走路由分离方案(Q3 c);影响范围仅本计划文档与 ADR-0007 |
| 17 个 view 迁移中某个 view 改不动 | 中 | 该 view 临时加入 mobile-only 清单,Stage 3 末尾回头处理 |
| 老人模式兜底不彻底 | 中 | 兜底代码加 `!important` 防御;若仍撑爆,临时把 720px 提到 800px(Q5 γ 是 v2 升级路径) |
| matchMedia 兼容性问题 | 极低 | 改用 Q12 b debounce 200ms,语义降级但功能等价 |

---

## 7. 不在本计划范围

- 真正的桌面产品(Q2 风格 ② 横向扩展) — Q1 拍板时一致否决,作为 v2 路线
- 路由按 UA 跳转 — Q3=b 不需要
- ECharts 图表桌面适配 — 仅 admin/dashboard 已用 grid,其余 view 没图表;真出现时再单独立案
- 后端 API 改动 — 0;本计划纯前端

---

## 8. 验收签字栏

| 角色 | 姓名 | 日期 | 备注 |
|---|---|---|---|
| 计划起草 | Mavis(AI) | 2026-09-18 | — |
| 用户确认 | (待填) | (待填) | — |
| Stage 1 通过 | AI(deepseek) | 2026-09-18 | 已通过，证据见 §9.4 |
| Stage 2 通过 | AI(deepseek) | 2026-09-18 | 18/18 条响应式路由已迁移完成（另加 2 个公开页）；15 条复用手机正文，观感代价见 §9.5 第 2 条 |
| Stage 3 通过 | AI(deepseek) | 2026-09-18 | 防御 + 兜底 + 清理已完成；Vitest 单测未做（§9.3 第 8 条）。**另修掉 1 个本轮引入的白屏回归（§9.6）与 1 个既有溢出缺陷（§9.7）** |

---

> **本计划已通过 grill-with-docs 12 题决议,所有大方向 + 工程细节均经用户拍板。**
> **可立即分发执行。**

---

## 9. 落地记录（2026-09-18 · 实施 + 验证）

> 本节由实施者追加，记录**计划与代码实际的差异**、**本轮做到哪一步**、**验证证据**。
> §1 决策摘要与 §2 命名清单的大方向未作改动；下面只纠正与代码不符的事实项。

### 9.1 本轮实施范围

**第一轮**：用户选定「Stage 1 完整 + Stage 3 防御/兜底」。
**第二轮**：用户追加「继续完成 Stage 2 批量迁移」+「修掉 `.elder-track` 既有溢出缺陷」。
两轮合并后，**计划承诺的 18 条 F/E/C 响应式路由已全部迁移完毕**（并额外覆盖 2 个公开页）。

**新增文件（7 个）**

| 文件 | 说明 |
|---|---|
| `frontend/src/composables/useDevice.js` | 模块级单例；`matchMedia('(max-width: 767px)')` + `change` 事件；`force-mobile` 逃生舱 |
| `frontend/src/layouts/MobileLayout.vue` | 由旧手机端布局更名而来（旧文件已 `git rm`） |
| `frontend/src/layouts/DesktopLayout.vue` | 可选入口，**不挂在主链路**（原因见 §9.3 第 6 条） |
| `frontend/src/components/NlDesktopShell.vue` | 64px 顶栏 + 内容区 `max-width: 720px` 居中；检测 `meta.mobileOnly` |
| `frontend/src/components/NlMobileOnlyNotice.vue` | 420×320 居中提示卡 + 二维码占位 + "继续查看" |
| `frontend/src/components/NlMobileOnlyPage.vue` | ⚠️ **计划中没有，必须新增**（原因见 §9.3 第 5 条） |
| `frontend/src/components/NlPageShell.vue` | ⚠️ **计划中没有**：形态自适应页面壳，让 view 正文只写一份（原因见 §9.6） |

**改动文件**

- `router/routes.js`：`PhoneLayout` → `MobileLayout`；4 条路由加 `meta.mobileOnly`
- `store/modules/app.js`：`setElderlyMode()` / `applyElderlyClass()` 加桌面禁用（Q6 = a）
- `styles/elderly.scss`：`@media (min-width: 768px)` 兜底块（Q6 = d）
- `styles/variables.scss`：新增 `$nl-desktop-content-width: 720px` 作宽度单一真源
- `layouts/AdminLayout.vue`：老人模式开关置 `disabled`；内容区补 Transition 单根包装（§9.6）
- `layouts/MobileLayout.vue`：内容区补 Transition 单根包装（§9.6，**这条是本轮最关键的修复**）
- `components/index.js`：导出 5 个新组件
- `components/NlPhoneShell.vue`、`components/NlNavBar.vue`：见 §9.2
- `views/family/home.vue`：形态二分 + 桌面版专排版；**并修掉 `.elder-track` 既有横向溢出缺陷**（§9.7）

**已完成响应式迁移的 view（18 条路由 / 16 个 view 文件）**

| 分组 | 路由 |
|---|---|
| FAMILY（12） | `home` `elder` `elder/bind` `medication` `message` `order` `order/:id` `order/:id/review` `order/:id/complaint` `order/step1` `order/step2` `order/step3` |
| COMPANION（4） | `hall` `order` `income` `message` |
| ELDER（2） | `home` `medication` |
| 公开页（额外 2） | `register` `forget`（`login` 本就自带 420px 居中卡，无需改） |

其中 3 条首页（`family/home`、`elder/home`、`companion/hall`）走**显式双壳 + 桌面专属排版**；
其余 15 条走 `NlPageShell` 复用手机正文。两种做法都在计划「可复用手机模板，也可重排」的许可范围内。

**已接入 mobile-only 守卫的 view（4 个文件覆盖 4 条路由）**

`views/profile/index.vue`、`views/companion/entry.vue`、`views/companion/execute.vue`、
`views/companion/message.vue`（**仅** `/elder/message` 生效，见 §9.3 第 2 条）

### 9.2 前置阻塞项（计划未识别，但会卡住验收）

1. **既有 lint error**：`NlPhoneShell.vue` 把两个 `<template>` 分发到同一个 `#right` 具名插槽
   （`vue/valid-v-slot`）→ `eslint` 直接报 error。计划 Stage 1 验收 1「`pnpm lint` 通过」在此状态下
   **不可能达成**。已修：保留其中一个 —— 被删的那个判的是 `nav.$slots`，而 `nav` 是普通对象，
   该分支永远为假。
2. **`NlNavBar` 右侧槽位对称性**：修掉上一条后，右侧容器在无内容时仍渲染一个
   `min-width: var(--nl-touch-min)` 的空 div，会把标题挤偏。已补 `v-if="$slots.right"` + 占位 span。
3. **`pnpm` 命令在本机不可用**：`pnpm lint` / `pnpm format` 会被包装层拦在
   `ERR_PNPM_IGNORED_BUILDS`（`@parcel/watcher` / `esbuild` 的 build script 未批准）。
   本轮改用 `node node_modules/eslint/bin/eslint.js` 与 `node node_modules/vite/bin/vite.js` 直调。
   **计划里所有 `pnpm xxx` 验收命令都需按此改口径。**

### 9.3 计划勘误（与代码不符的事实项）

| # | 计划中的说法 | 代码实际 | 影响 |
|---|---|---|---|
| 1 | 「28 个 URL」「前 24 个响应式 + 后 4 个 mobile-only」 | 根路由下**共 22 条**：18 条待响应式 + 4 条 mobile-only | 统计口径错；4 条 mobile-only 清单本身**正确** |
| 2 | Stage 2 的 2-A/2-B/2-C 合计 16 个 view | FAMILY 12 ✓、ELDER 2 ✓、COMPANION **4**（计划写 3） | 漏了 `/companion/message`；因它与 `/family/message` **共用同一 view 文件**，工作量并未漏，但清单不全 |
| 3 | 未提及 `/admin/*` | `AdminLayout` 下有 **8 条**路由 | 「28 个 URL」口径没算 admin / login / error 页 |
| 4 | **ADR-0007 决策 6：「routes.js 不改」** | 计划 Stage 1 却要求改 import + 加 4 处 `meta` | **两份文档自相矛盾**；本轮按计划改了 `routes.js`，ADR 该条应订正 |
| 5 | 「mobile-only 由 NlDesktopShell 检测 meta 后整体替换」（ADR-0008 同） | mobile-only 的 view **永远不会渲染 NlDesktopShell**（它们只有手机版模板），`meta.mobileOnly` 形同虚设 | **必须新增 `NlMobileOnlyPage`** 在更外层兜住。实测未加时，1280 宽屏打开 `/profile` 仍是被拉满的大号手机 |
| 6 | Stage 1：「`DesktopLayout` 根据 `isMobile` 判断是否 redirect 到 `MobileLayout`」 | Vue 单文件组件**不能把自身递归渲染成另一种形态**（`MobileLayout` 渲染 view，view 再渲染 `DesktopLayout` → 无限递归） | 未采用 redirect 路线，改为 view 内 `isMobile` 二分（与 Q3 = b「组件内响应式」一致）；`DesktopLayout` 降级为可选入口 |
| 7 | §2 关键约束 2：「不要引入 element-plus 复杂组件（不要用 `el-table`）」 | `CONTEXT.md` 视图层级一节写「Desktop 形态可直接用 `el-table`」 | **文档冲突**；本轮遵守计划（未用 `el-table`，沿用 `NlCard` + 手写列表行），`CONTEXT.md` 该句应订正 |
| 8 | Stage 3：「`useDevice.js` / `NlDesktopShell.vue` / `NlMobileOnlyNotice.vue` 单测（Vitest）」 | `frontend/package.json` **没有 vitest / jsdom**，且 AGENTS.md §0.2 禁止擅自加依赖 | **未做**，需先获批加依赖 |

### 9.4 验证证据

工具（均为临时脚本，`.playwright-cli/` 已在 `.gitignore` 中）：
真实后端 8080 + 前端 dev 5141 + 真实接口签发的四角色令牌（`tools/e2e/harvest_tokens.py`），
浏览器用系统 Edge（本机 `ms-playwright` 无 chromium 二进制，故 `channel: 'msedge'`）。

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `.playwright-cli/deskcheck.mjs` | Stage 1 + Stage 3 全部验收项 | **48 / 48 通过** |
| `.playwright-cli/migrate-sweep.mjs` | 22 条 F/E/C 路由 + 2 个公开页 × 390/1280 两档 + 完整下单三步 + admin 8 条 SPA 跳转 | **177 / 177 通过** |

两个套件**全流程 0 条 console error / pageerror**。

| 计划验收项 | 结果 |
|---|---|
| Stage 1-3：360 / 768 / 1280 / 1920 四档形态归属 | ✅ 767 及以下走手机壳、768 及以上走桌面壳 |
| Stage 1-3：桌面内容居中限宽 720 + 左右留白 | ✅ 768→left 24 / 1280→left 280 / 1920→left 600，均宽 720 |
| Stage 1-5（Q12 c）：拖拽跨越断点实时切换、不闪 | ✅ 767→900 实时翻转，**URL 不变、未刷新** |
| Stage 1-6 / Stage 3-2（Q6 a+d）：老人模式桌面禁用 | ✅ 残留 `true` 也不挂 class；`setElderlyMode(true)` 被拦截；兜底样式生效（正文 14px / touch 44px）；390 宽下老人模式**未被误伤**（18px 正常） |
| Stage 3-3：mobile-only 路由见提示页 | ✅ `/profile`、`/companion/entry`、`/elder/message`、`/companion/execute/:id` 四条全部命中，提示卡实测 420×320 |
| Stage 3-3：点"继续查看" → sessionStorage → 刷新变手机版 | ✅ 四条路由全部通过，且**无刷新循环** |
| **Stage 2-1：18 条响应式路由在 390 / 1280 两档形态正确** | ✅ 逐条断言「确实停留在本路由（未被重定向）」「390 走手机壳」「1280 走桌面壳 + 限宽 720」 |
| **Stage 2-3：1280 下真的走完下单三步** | ✅ 点 family/home「新增订单」→ step1 → step2 → step3，每步都是桌面壳 + 内联 CTA + 限宽 720；390 下同流程仍是手机壳 + 底部固定 CTA |
| 未迁移/不该迁移的路由没被带跑 | ✅ 4 条 mobile-only 在 1280 下仍渲染提示页；`/family/message`、`/companion/message`（与 `/elder/message` 共用 view 但非 mobile-only）在 1280 下走桌面壳、提示页未出现 |
| 桌面子页不会走进"无返回入口"死路 | ✅ 15 条二级页在 1280 下都有返回按钮；3 条首页按设计无返回 |
| **无横向溢出** | ✅ 22 条路由 × 390/1280 全部 `scrollWidth <= clientWidth` |
| **admin 8 条路由 SPA 跳转无回归** | ✅ 逐条点击侧栏，url / 布局 / DOM 体积 / 文本量全部正常（见 §9.6） |
| Stage 3-5：`git grep PhoneLayout frontend/` = 0 | ✅ 0 结果（注释里的字面量也已清除） |
| 静态检查 | ✅ `eslint` **0 error**（余 9 条 warning 全部是我改动前就存在的，分布在 `realtime.js` 与 4 个我未改动的 view 里）；`vite build` 通过 |

> ⚠️ 截图已**人工目视复核**（`.playwright-cli/deskcheck/*.png`、`.playwright-cli/shots/*.png`）：
> 1920 桌面版是居中限宽卡片列；390 手机版与改造前逐像素一致；
> mobile-only 提示卡、老人端只读桌面版、下单三步内联 CTA 均符合设计意图。

**⚠️ 证据可信度自我更正（重要）**

第一轮报告里写的「1280 下 `order-step1/2/3` 均非提示页」是**假阳性**：
`order-step2` / `order-step3` 依赖 Pinia 里的 `orderDraft`，draft 不完整时会
`router.replace('/family/order/step1')`，所以直接敲这两个 URL 拿到的其实是 **step1**，
那条断言只验到了 step1。同理，第一轮 50 条断言**全部使用 `page.goto`（整页加载）**，
因此完全没有覆盖 SPA 客户端跳转 —— 而 §9.6 那个会让整页白屏的回归恰恰只在 SPA 跳转时触发。
现已补上「URL 断言」与「点击式走完三步」，并新增 admin 侧栏跳转对照组。

### 9.5 未完成 / 已知限制

1. **Stage 3 单测未做**（缺 vitest / jsdom 依赖，见 §9.3 第 8 条）。这是**计划内唯一未交付项**。
2. **15 条路由的桌面正文是「手机版正文 + 720 居中列」**，不是桌面专属重排 ——
   这符合计划 Stage 2「可复用手机模板」的许可，但有两处观感代价，如实记录：
   - 分段控件（`今日/月历/计划`、订单状态筛选 chips）在 720px 下被拉得偏宽；
   - 详情页的 `width: 100%` 按钮在桌面下仍是通栏。
   若后续要精修，只需给该 view 换成 `family/home.vue` 那样的**显式双壳 + 桌面专属模板**，
   不必动 `NlPageShell`。
3. **`force-mobile` 降级后的观感**：点"继续查看"后手机壳会**铺满整个宽屏**
   （见 `.playwright-cli/deskcheck/mobileonly_*-forced.png`），正是 ADR-0007 想消除的
   「放大 4 倍的手机截图」。ADR-0008 §负面已有预期，本轮按契约保留。
   若要 v2 优化：给 `html.force-mobile` 加类 → `.nl-shell` 限宽 390 居中，
   但**必须同时**把 `position: fixed` 的 `__cta` / `__tabbar` 改成
   `left: 50%; transform: translateX(-50%); width: 390px`，否则底栏会横贯全屏 —— 故未纳入本轮。
4. **`pnpm lint` / `pnpm format` 在本机不可用**（§9.2 第 3 条），验收命令需改用直调 node。

### 9.6 本轮踩到并修掉的**回归**：`<Transition mode="out-in">` + 多根组件 = 整页白屏

**这是本轮最有价值的一条记录，后来者务必读完再动布局。**

形态自适应的直接后果是：view 的根节点普遍变成 `v-if="isMobile" / v-else` 双分支
（`NlPageShell`、`NlMobileOnlyPage`、`family/home`、`elder/home`、`companion/hall` 都是），
而 Vue 会把这种模板编译成 **Fragment**（多根）。

`MobileLayout`（以及 `AdminLayout`）里原有的：

```html
<router-view v-slot="{ Component }">
  <transition name="fade" mode="out-in">
    <component :is="Component" />
  </transition>
</router-view>
```

`<Transition>` **只能作用于单根子节点**。子节点是 Fragment 时，`mode="out-in"`
等不到「离场完成」，于是**新页面永远不会被插入**。故障表现极其隐蔽：

- URL 正常变化（`router.push` / 点击都会变）
- `#app` 里只剩 `<div class="mobile-layout">` 加一个空注释占位 —— **整页空白**
- **没有任何 JS 报错、没有任何 Vue 警告**（`console.error` / `pageerror` 都是 0 条）
- 等 5 秒以上也不会恢复；`page.goto` 直接打开同一路由却**完全正常**

因此它只在「SPA 客户端跳转」这一条路径上出现 —— 而第一轮的验证脚本全用 `page.goto`，
把它整个漏掉了。是第二轮补做「点击 family/home 的『新增订单』」时才暴露出来的。

**判定过程**（脚本保留在 `.playwright-cli/diag-spa-compare.mjs`）：

| 场景 | 跳转前 | 跳转后 |
|---|---|---|
| A. admin 侧栏跳转（view 都是单根） | html=15905 | html=29147 ✅ 正常 |
| B. 手机端 family/home 点「预约挂号」 | html=13368 | html=**59**、文本 0 字 ❌ 白屏 |
| C. 手机端 family/home 点底部 Tab「订单」 | html=13368 | html=**59** ❌ 白屏 |
| D. 程序化 `router.push('/family/order/step1')` | html=13368 | html=**59** ❌ 白屏 |

A 组正常、B/C/D 全挂，且 D 绕过了 DOM 点击 —— 直接把范围收敛到「离开 family/home 这个多根组件」。

**修复**：给 `<Transition>` 一个**明确的单根元素**并加 key，让离场/入场都有确定目标：

```html
<router-view v-slot="{ Component, route }">
  <transition name="fade" mode="out-in">
    <div :key="route.path" class="mobile-layout__page">
      <component :is="Component" />
    </div>
  </transition>
</router-view>
```

`MobileLayout` 与 `AdminLayout` 都已按此改（`AdminLayout` 当前虽然没坏，但埋着同一颗雷：
只要将来某个 admin view 写成 `v-if / v-else` 双分支就会同样白屏且无报错）。

> ⚠️ 写这条注释时还踩了个小坑：`<template>` 里的 HTML 注释**不能包含 `--`**，
> 我一度把 `<!---->` 字面量写进注释，导致注释提前闭合、模板编译失败、
> `MobileLayout.vue` 直接 404（`Failed to fetch dynamically imported module`）。
> 注释里描述这种空占位时请用文字，别贴字面量。

### 9.7 修掉的既有缺陷：`.elder-track` 横向溢出

`views/family/home.vue` 的就诊人横滑卡原本是：

```scss
.elder-track {
  margin: 0 calc(var(--nl-space-2) - var(--nl-gutter)) var(--nl-gap-section); // 左右各出血 8px
  &__list { padding: 0 var(--nl-gutter); overflow-x: auto; }
}
```

负 margin 让分区盒宽超出屏幕 16px，在 390 宽下把 `document.scrollWidth` 撑到 **398**，
产生 8px 横向滚动。**这是本轮之前就存在的缺陷**（已用 `git stash` 回改动前复测，结果一致：
同样 398px、同样 `.elder-track` / `.elder-track__list` 两个越界元素）。

修法：**分区只负责装订线，滚动仍由 `__list` 承担**：

```scss
.elder-track {
  margin: 0 0 var(--nl-gap-section);
  padding: 0 var(--nl-gutter);
  &__list { padding: 0; overflow-x: auto; }
}
.elder-track__guide { padding: 0; }   // 装订线已上移，避免双倍缩进
```

实测结果：390 宽溢出归零；首项左边缘 = 16px（正好落在装订线上）；`__list` 仍是
`overflow-x: auto` 的滚动容器。

> 走弯路记录：中途试过把 `overflow-x: auto` 挪到 `.elder-track` 分区上（让分区当滚动容器），
> **结果是横滑彻底失效** —— 因为那样 `__list` 不再是滚动容器，flex 子项会直接溢出 `ul` 盒。
> 已在代码注释里标注"别这么改"。