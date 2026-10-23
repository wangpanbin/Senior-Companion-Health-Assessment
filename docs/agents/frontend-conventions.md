# 3 · 前端编码规范（ESLint 9 + Prettier 3 为基线）

> 本文件是 `AGENTS.md` **§3** 的落地位置。
> 拆分原因：运行时给仓库根 `AGENTS.md` 的上下文预算是 **32 KB**，
> 拆分前它有 **54 KB** —— 超出预算的部分**每次会话都被静默丢弃**，
> 丢掉的恰好是订单状态机、Do/Don't 底线、验收清单这些最该被看见的内容。
> 拆开后 `AGENTS.md` 只留红线与导航，细则**按需加载**。
>
> ⚠️ 硬约束仍在 `AGENTS.md`（始终在上下文里）：§0 合规红线、§0.2 AI 自我约束、
> §10 Do/Don't 最低底线、§11 验收检查清单。本文件是细则，不覆盖那些硬约束。
>
> 小节编号（§3.1 …）**未改动**，仓库其它文档里的 `AGENTS.md §3.N` 引用仍然对得上。

---

## 3.1 风格基线（与 `.prettierrc.json` 一致）

| 项 | 值 |
|---|---|
| 分号 | 无（`"semi": false`） |
| 引号 | 单引号（`"singleQuote": true`） |
| 每行宽度 | 100（`"printWidth": 100`） |
| 缩进 | 2 空格（`"tabWidth": 2`、`useTabs: false`） |
| 尾逗号 | 无（`"trailingComma": "none"`） |
| 箭头函数括号 | 始终（`"arrowParens": "always"`） |
| Vue `<script>` / `<style>` 缩进 | 不缩进（`"vueIndentScriptAndStyle": false`） |
| 行尾 | LF（`"endOfLine": "lf"`） |

## 3.2 ESLint 9 扁平配置要点

- 已开 `eslint-plugin-vue` 推荐规则 + `eslint-config-prettier`（关闭与 Prettier 冲突的格式化规则）。
- `'vue/multi-word-component-names': 'off'`（允许 `Login.vue` / `Dashboard.vue`）。
- `no-unused-vars` 允许 `_` 前缀的形参。
- `no-console` 仅允许 `console.warn` / `console.error`。
- 跑 `pnpm lint`（已配 `--fix`）必须 0 警告；提交前先跑。

## 3.3 Vue 3 + Vite 项目约定

- **全部用 `<script setup>`**（Composition API），**不要**写 Options API。
- 组件文件名：PascalCase（`BasicLayout.vue` / `ModulePlaceholder.vue`），目录按业务归类。
- 路由用 `() => import('@/views/...')` 懒加载。
- **不要**在 `views/` 下直接写请求，**必须**走 `src/api/<module>.js` 封装好的函数。
- Pinia store 用 setup 风格或 options 风格皆可，但现有 `app.js` / `user.js` 是 options 风格，新增 store **保持一致**。
- 状态值与后端枚举保持一致（`ELDER` / `FAMILY` / `COMPANION` / `ADMIN`）。

## 3.4 SCSS / 样式

- 设计令牌在 `frontend/src/styles/variables.scss`（编译期 SCSS 变量）。
- 运行期 CSS 自定义属性在 `frontend/src/styles/index.scss` 的 `:root` 里集中声明（`--nl-color-primary` 等）。
- **不要**在 `vite.config.js` 里配 SCSS `additionalData` 全局注入（项目刻意避免，会触发 Sass 模块循环错误）。每个 `.vue` / `.scss` 文件需要变量时显式：
  ```scss
  @use '@/styles/variables.scss' as *;
  ```
- Element Plus 主题打通通过 `index.scss` 里的 `--el-color-primary: var(--nl-color-primary);` 等覆盖实现。

## 3.5 适老化（老人模式）硬约束（M11 验收）

| 项 | 数值 / 实现 |
|---|---|
| 老人模式基线字号 | ≥ 18px（`--nl-font-body` 在 `elderly-mode` 下切到 `$elderly-font-min`） |
| 可点击元素最小高度 | ≥ 48px（`$elderly-touch-min`，按下 `--nl-touch-min` 切换） |
| 文字对比度 | ≥ 4.5:1（WCAG AA，`$elderly-contrast-ratio`） |
| 老人模式触发方式 | `<html>` 上加 class `elderly-mode`（由 `appStore.applyElderlyClass()` 维护，**不要**靠刷新页面） |
| 老人模式菜单 | 路由 `meta.elderlyHidden: true` 的项在老人模式下隐藏（`BasicLayout.vue` 已实现过滤） |
| 核心流程点击次数 | ≤ 3 次 |
| 老人模式生效范围 | **仅 Mobile 形态（视口 < 768px）**；Desktop 形态下开关禁用并隐藏 —— 见下方「桌面端豁免」与 `docs/adr/0007` |

新增样式时**必须考虑**老人模式下的可读性 / 可点击性。涉及字号、按钮、表格、菜单时，**先看** `elderly.scss` 是怎么改 CSS 变量的，跟着改而非重复写硬编码。

**桌面端豁免（`docs/adr/0007` Q4 = II，2026-09-18 决策）**

上表的 ≥18px 字号与 ≥48px 触控区是**按 390 宽手机屏定制的基线**，不是全站通用值。老人模式**只在 Mobile 形态生效**：

- 18px 正文 + 56px 按钮套进 Desktop 的 720px 居中列会破坏信息密度，而 Desktop 形态本身不是老人主场景；
- 因此视口 ≥ 768px 时**禁用并隐藏**开关，Desktop 形态不需要满足上表数值。

三重防御，任何新增老人模式代码都**不得绕过**：

1. `appStore.setElderlyMode()` 在 `isElderlyModeDisabled()` 为真时**直接 return**，且**不写 localStorage**——避免「宽屏误点 → 手机端打开变成大字」的串味；
2. `appStore.applyElderlyClass()` 在桌面形态下摘掉 `<html>` 上的 class（localStorage 残留 `true` 也不生效）；
3. `styles/elderly.scss` 在 ≥ 768px 用 `!important` 兜底。

不要在 Desktop 形态下「顺手支持」老人模式，也不要为了绕过豁免而在 `elderly.scss` 里写媒体查询。

## 3.6 Axios 封装与接口调用

- 业务代码**只能** `import request from '@/utils/request'`，**不要**直接 `import axios from 'axios'`。
- 请求需要 token 时由 `request.js` 自动带 `Authorization: Bearer <token>`；公开接口（如 `/auth/login`、`/auth/captcha`、`/health`）传 `skipAuth: true`。
- 响应拦截器已经做了：
  - `code === 200` → 直接 `return data`（业务拿到的就是 `data` 字段）
  - 业务错误 → 弹 `ElMessage.error(message)` 并 `reject`
  - HTTP 401 → 尝试 `/api/auth/refresh` 换 token 并重放原请求（M2 实装后启用）；刷新失败清空登录态并跳 `/login`
  - 其他 HTTP 错误 → 用 `httpStatusMessage` 映射成中文提示
- 因此**业务代码不要再 `if (res.code !== 200)`**，直接 `await api.xxx()` 取数据。
- 拦截器已有并发刷新队列（`refreshing` / `pendingQueue`），**不要**自己再写一个。

## 3.7 Pinia 状态

- `userStore`：登录态（`token / userInfo / routesLoaded`）+ 角色相关 getter（`isLogin / role / isAdmin / isFamily / isCompanion / isElder`）。userInfo 只缓存 `id / username / nickname / role / avatar`，**禁止**写完整手机号 / 身份证号。
- `appStore`：UI 全局（`title / sidebarCollapsed / elderlyMode / loadingCount`），方法 `toggleSidebar / setElderlyMode / toggleElderlyMode / applyElderlyClass / initElderlyMode / startLoading / stopLoading`。
- 新增状态前先确认现有两个 store 不能复用，再决定是否新建 `store/modules/<name>.js`。

## 3.8 localStorage 读写

- 所有 key 集中在 `frontend/src/utils/auth.js`（`TOKEN_KEY` / `REFRESH_TOKEN_KEY` / `USER_INFO_KEY` / `ELDERLY_MODE_KEY`），**不要**在业务代码里再散写 `localStorage.setItem('xxx', ...)`。
- `clearAuth()` 是清空登录态的唯一入口，登出 / 401 都用它。
- **禁止**写入密码、身份证号、完整手机号。

## 3.9 路由与守卫

- 路由表集中在 `frontend/src/router/routes.js`，每个路由必须带 `meta.module`（对应 `docs/plan/plan.md` 的模块编号）。
- 角色守卫在 `router/index.js` 的 `beforeEach`，通过 `AUTH_ENABLED` 开关开启（M2 交付后置 `true`）。
- `meta.roles` 为空数组表示所有已登录角色可访问；不要在路由组件内部再判角色。
- 老人模式下 `meta.elderlyHidden: true` 的路由会从菜单中隐藏，但**仍可通过 URL 进入**——这是设计如此（老人模式下也需要排障入口）。

## 3.10 API 封装规则

- 每个后端模块对应一个 `frontend/src/api/<module>.js`，并在 `frontend/src/api/index.js` 用 `export * as xxxApi from './xxx'` 统一出口。
- 函数命名用动词：`getXxx` / `listXxx` / `createXxx` / `updateXxx` / `removeXxx` / `acceptXxx` / `cancelXxx` / ...
- 每个文件头注释要写：`对应文档：docs/api/<n>-<name>.md` + `负责模块：M<n> <模块名>`。
- **接口先行约定**：后端未交付前用 `Promise.resolve({...mock...})` 占位，但路径 / 参数 / 响应结构必须与 `docs/api/` 对齐，**不要**等后端写完再调。
