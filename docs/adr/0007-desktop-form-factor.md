# 0007. 引入 Desktop 形态 · form factor 与角色解耦

| | |
|---|---|
| 状态 | 提议 (Proposed) |
| 日期 | 2026-09-18 |
| 关联 | `AGENTS.md` §3.5 · `CONTEXT.md` |

## 背景

### 旧现状(问题陈述)

`frontend/src/layouts/` 当前只有两个 layout 文件:

- `PhoneLayout.vue` — 包裹 F/E/C 三个角色的所有页面
- `AdminLayout.vue` — 仅供 `/admin/*` 路由使用(角色 ADMIN)

代码现状中"形态"和"角色"是耦合的:`AdminLayout` 是个桌面端侧栏布局,但因为它只能给 ADMIN 用,所以**"桌面形态"成了 ADMIN 角色的属性**。F/E/C 三个角色即便在 1920 显示器上打开,也仍然走 `PhoneLayout`,页面会被无限拉宽,顶栏 / Tab Bar / 字号都是手机基准的,视觉非常糟糕。

### 触发事件

2026-09 用户提出"前端页面只适配了手机端,想适配电脑布局"。在第一轮方案讨论中,经用户拍板:

- Q1 = B(F/E/C 全员适配)
- Q2 = ①(居中限宽策略)
- Q3 = b(组件内响应式,不分离路由)
- Q4 = II(桌面端禁用老人模式)

这意味着需要一种"形态层和角色层正交"的实现方式。

## 决策

引入 **form factor** 一词作为形态层的正交概念,与角色(Role)完全解耦。落地为:

1. **新增 `NlDesktopShell.vue`** 组件,作为 Desktop 形态的页面壳(取代 AdminLayout 之外"无桌面壳可用"的空白)。
2. **新增 `useDevice()` composable**,提供 `isMobile / isDesktop`,所有 view 通过该 composable 切换形态。
3. **新增 `DesktopLayout.vue`**,承担"≥ 768px 走 NlDesktopShell"职责。
5. **旧 `PhoneLayout.vue` 重命名为 `MobileLayout.vue`**,语义和形态一致。
6. **routes.js 不改**,保持 Q3=b 选定的"组件内响应式"路径。
7. **AdminLayout 保留**,作为角色级桌面产品(数据看板、订单管理等后台),与 `DesktopLayout` 不在同抽象层。

形态与角色的关系用一张图表达:

```
                ┌─────────────────────────────────────────────┐
                │              Form Factor                    │
                ├──────────────────┬──────────────────────────┤
                │     Mobile       │         Desktop          │
                ├──────────────────┼──────────────────────────┤
                │  F  E  C  A*     │   F  E* C*  A (admin)    │
                │  (* 部分支持)    │   (* 见 ADR-0008)        │
                └──────────────────┴──────────────────────────┘
```

## 选项

### A. 维持现状(不引入 Desktop 形态)

F/E/C 维持手机壳,Admin 巧合占桌面壳。

- **优点**:零工作量。
- **缺点**:违背 Q1=B 用户决定;1920 屏看 family/home 像放大 4 倍的手机截图。

### B. 引入 Desktop 形态(form factor 解耦) ← **本次选**

新增 NlDesktopShell + useDevice + DesktopLayout,view 内响应式。

- **优点**:与"全员响应式"决策对齐;形态层 / 角色层语义清晰;view 模板改动局部可控。
- **缺点**:新增约 500 行代码(2 个 layout + 1 个 shell + 1 个 composable);所有 view 要补 v-if/v-else。

### C. 完全路由分离(`/desktop/family/home` 等)

URL 区分手机 / 桌面产品。

- **优点**:两套产品独立演进。
- **缺点**:与 Q3=b 用户决定冲突;同一份功能两份代码;违反"渐进式"原则。

## 后果

### 正面

- `PhoneLayout` 误导性命名消失,后续看代码的人立刻理解 Mobile/Desktop 是形态概念。
- F/E/C 三个角色在 1920 屏上视觉合理(居中限宽 720px)。
- 老人模式在桌面端被禁用后,不会因 18px 字号撑爆 720px 容器(Q4=II 的实现前提)。

### 负面

- 旧 `PhoneLayout.vue` 重命名需要更新 `routes.js` 的 import 和所有 `PhoneLayout` 字面量引用;**需要 grep 全项目 + git mv 一次到位**。
- `useDevice()` 需要在 mount 时初始化(`window.innerWidth`),SSR 场景下不存在,本项目是纯 CSR Vue 应用,不受影响。
- 浏览器宽度跨越断点(768px)时需要响应式切换;防抖策略见后续 ADR(占位)。

### 兼容

- 旧 `PhoneLayout.vue` 文件保留一个迭代周期,新代码 import 切到 `MobileLayout.vue` 后再删。
- `AdminLayout.vue` 不动,继续仅供 ADMIN 角色使用。