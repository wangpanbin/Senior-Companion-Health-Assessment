# 银龄伴诊 · 项目术语表

> 用途:统一团队与 AI 协作时的词汇。任何"xx 是什么"的歧义都先回到本文件。
> 维护规则:术语仅当跨页面/跨模块复用时才收录;只在某 view 里用的局部名词不写。
> **本文档刻意不写实现细节**(用什么组件 / 什么 props / 哪个 CSS 变量);那些归 ADR 和代码注释。

---

## 角色与形态

### 角色 (Role)

系统的四类用户身份,与具体页面正交。

- **ELDER** — 老人。`sys_user.role = ELDER`
- **FAMILY** — 家属(老人的子女 / 监护人)
- **COMPANION** — 陪诊员
- **ADMIN** — 后台管理员

### 形态 (Form Factor)

页面最终呈现的终端形态,**与角色正交**。

- **Mobile** — 移动端壳:`NlPhoneShell`(状态栏 + 顶部 NavBar + 内容区 + 底部 Tab Bar / CTA)。设计基线 390 × 844。
- **Desktop** — 桌面端壳:`NlDesktopShell`(见 ADR-0007)。设计基线 ≥ 768 宽屏。

> **关键纠正**:`PhoneLayout` 这个旧名字把"形态"和"角色"耦合在一起,导致 admin 巧合占用了桌面壳。本次重命名后,**形态是形态,角色是角色**;一个 FAMILY 用户的 home 页在 390 屏走 Mobile,在 1280 屏走 Desktop,URL 不变。

### Layout 命名(重命名后)

- `layouts/MobileLayout.vue` — 替代原 `PhoneLayout.vue`,只负责"小于 768 时挂 NlPhoneShell"。
- `layouts/DesktopLayout.vue` — 新增,只负责"≥ 768 时挂 NlDesktopShell + 路由分流"。
- `layouts/AdminLayout.vue` — 保留,仅供 `/admin/*` 路由(角色级桌面产品)使用,与 `DesktopLayout` 是不同概念。

---

## 模式开关

### 老人模式 (Elderly Mode)

`<html class="elderly-mode">` 触发的全局放大模式。约束见 `AGENTS.md` §3.5:

- 正文字号 ≥ 18px
- 可点击元素高度 ≥ 48px(按钮 56px)
- 文字对比度 ≥ 4.5:1

**生效范围(本轮决策后)**:**仅 Mobile 形态生效**;Desktop 形态下开关被禁用并隐藏。

### 侧边栏折叠 (Sidebar Collapsed)

仅 `AdminLayout` 的左侧菜单项宽度在 220px / 64px 之间的状态。与 Desktop 形态无关。

---

## 视图层级

### 页面壳 (PageShell)

包住一个完整页面的容器组件,**不包含业务内容**。

- `NlPhoneShell` — Mobile 形态的页面壳
- `NlDesktopShell` — Desktop 形态的页面壳(本次新增)
- `AdminLayout` — admin 后台的"页面壳 + 业务导航"组合,与上面两个不在同一抽象层

### 区块 (Block / Section)

页面壳之内的内容片段,通常以 `NlCard` 包裹,例如"今日用药提醒"、"最近订单"。

### 列表行 (ListRow)

`NlListRow` 组件实例;Mobile 形态的列表用 `<ul><NlListRow>` 手写,Desktop 形态可直接用 `el-table` 表格(不归入 ListRow 概念)。

---

## 不在本表收录

- 各 view 文件路径、组件 props、SCSS 变量值、接口字段 — 归代码注释与 `docs/api/`
- 业务术语(订单状态、陪诊节点等)— 归 `AGENTS.md` §4
- 设计令牌具体数值(颜色 / 间距 / 字号)— 归 `docs/design.md`