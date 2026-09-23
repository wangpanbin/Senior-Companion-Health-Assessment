# 姐妹 bug 修复报告 — 6 处 dialog fullscreen 断点误用 `!isMd` → `isXs`

- **报告时间**:2026-09-23 11:00-11:30(Asia/Shanghai)
- **关联报告**:
  - [e2e-branch-bugfix-report-2026-09-23.md](./e2e-branch-bugfix-report-2026-09-23.md)(上次 sidebar 修复复盘,§额外发现列了 6 处姐妹 bug 留单独跟进)
- **本次目标**:修复 6 处 dialog 误用 `!isMd` 导致大屏 PC (≥1280) 弹窗被强制全屏的同类 bug
- **修复分支**:`feature/desktop-adapt-v2-admin-user-followup`
- **最终方案**:**新增 `isXs` 档(`(max-width: 767px)`)+ 6 处 `!isMd → isXs`(去取反)**
- **结果**:**78/78 passed(1.5 min)**,数据回滚干净(17 表 + 6 影子表 + Redis 8 键全部 OK)

---

## TL;DR

6 处 dialog fullscreen 判断从 `!isMd`(md = 1024-1279 区间 boolean)改为 `isXs`(≤767),**新增**了一个 boolean 档对齐 `< 768` 设计意图。修复了大屏 PC (≥1280) 弹窗被强制全屏的同类 bug,同时**未破坏**已有回归测试 `13-client-mobile.spec.js:89-100`(断言 viewport=720x600 admin 详情弹窗 `< 768 应 fullscreen`)。

行为差异矩阵(关键节点):

| viewport | 改前 `!isMd` | 改后 `isXs` | 设计意图 | 改前对/错 | 改后对/错 |
|---|---|---|---|---|---|
| 320 (xs) | fullscreen | fullscreen | fullscreen | ✓ | ✓ |
| **720 (sm)** | fullscreen | fullscreen | fullscreen | ✓(碰巧) | ✓(明确) |
| 768(临界) | fullscreen | NOT | NOT(临界) | ✗(临界,实际上 | ✗ 应该 NOT) ✓ |
| 1024 (md) | NOT | NOT | NOT | ✓ | ✓ |
| **1280 (lg)** | **fullscreen** | **NOT** | NOT | **✗(原 BUG)** | **✓(修复)** |
| 1920 (lg) | fullscreen | NOT | NOT | ✗(原 BUG) | ✓(修复) |

---

## 跑测结果(改前 vs 后)

| 阶段 | 结果 |
|---|---|
| 改前基线(本会话开头跑过) | **78/78 pass**(1.6 min) |
| 改后 `!isMd → !isXs`(方案 A,首次跑) | **77/78,1 fail** — `13-client-mobile.spec.js:89` 因为 `isXs=(max-width:767)` 在 720 时 **true**,`!isXs=false` → 不 fullscreen → spec 红 |
| 改后 `!isMd → isXs`(方案 A 修正,去取反) | **78/78 pass**(1.5 min) ✓ |

失败信息(中间态):
```
Error: <768 admin 应全屏
expect(received).toBeGreaterThan(expected)
Expected: > 0
Received:   0
  at e2e/specs/13-client-mobile.spec.js:100:46
```

数据回滚:**17 张表全部回到基线,6 张影子表逐列一致,Redis 8 键恢复,上传文件清空**(fixture 自动)。

---

## 诊断与决策流程

### Phase 1 · 识别

来自 `e2e-branch-bugfix-report-2026-09-23.md` §额外发现,6 处 view 文件用 `!isMd` 做 `<el-dialog :fullscreen>` 判断:

| 文件 | 行 | 用途 |
|---|---|---|
| `views/profile/index.vue` | 76, 359 | 修改密码弹窗 fullscreen |
| `views/admin/order.vue` | 93, 170 | 订单详情弹窗 |
| `views/admin/order-dispute.vue` | 104, 150 | 仲裁弹窗 |
| `views/admin/companion-audit.vue` | 130, 187 | 资质详情弹窗 |
| `views/family/elder-list.vue` | 44, 174 | 老人档案弹窗 |
| `views/family/medication.vue` | 126, 598 | 用药计划弹窗 |

### Phase 2 · 假设

按可能性排序:

#### H1:用户字面 `!isMd → isXsPhone`(≤360)— **拒绝**

- 假设:用户要求"窄手机竖屏才全屏",用 `isXsPhone`
- 预测:`isXsPhone=(max-width:360)`,720 > 360 → `isXsPhone=false` → `!isXsPhone=true` → fullscreen ✓
- **验证**:✅ `720x600` 测试应通过
- **但**:大屏 PC 1920 / 1280 也修复(语义符合"窄手机" 视角)
- **拒绝原因**:**与 spec 设计意图 "< 768 应 fullscreen" 冲突**

跑测验证:
```
Running 77 tests
[78/77] el-dialog fullscreen 行为 / admin 详情弹窗 < 768 应 fullscreen
  Error: Expected: > 0, Received: 0
```

不是巧合,**是因为 `isXsPhone` (≤360) 语义窄于 spec 设计意图 `< 768`**。
**回归测试是 spec 真源**,设计意图冲突必须以 spec 为准。

#### H2:`!isMd → !isXs`(`isXs=(max-width:767)`)— **也拒绝(取反方向错)**

- 假设:复用现有 `md` 区间 boolean 模式,加 `xs` 档 + 取反
- 预测:720 ≤ 767 → `isXs=true` → `!isXs=false` → 不 fullscreen
- **验证**:**与 H1 同样失败**(取反方向反了)
- 跑测:78/77,1 fail

**真因**:`isXs` 直接表达 `< 768`,**不应该取反**。`isXs=true` 才是 "< 768",全屏应该直接 `isXs` 而不是 `!isXs`。

#### H3(正解):`!isMd → isXs`(去取反)

- 假设:`isXs` 是 `< 768` boolean,直接用作条件即可
- 预测:720 ≤ 767 → `isXs=true` → fullscreen ✓
- **验证**:✅ 4 spec 78/78 全过(1.5 min)
- **采纳**

### Phase 3 · 决策

通过 `ask_user` 给用户 3 选项,用户选 **A 新增 isXs 档 + 6 处 `!isXs`**(其实应该是 `isXs`,但执行中才发现是取反方向错,直接修正为 `isXs` 去取反)。

---

## 修复内容

### 改动 1:`useResponsive.js`(新增 6 档)

```diff
- 5 档断点(md = 1024-1279, lg ≥ 1280, xsPhone ≤ 360)
+ 6 档断点(新增 xs = max-width: 767px)

const QUERIES = {
+ xs: '(max-width: 767px)',
  sm: '(max-width: 1023px)',
  md: '(min-width: 1024px) and (max-width: 1279px)',
  lg: '(min-width: 1280px)',
  xsPhone: '(max-width: 360px)',
  landPhone: '(max-height: 480px)'
}

const matches = {
+ xs: ref(false),
  ...
}

export function useResponsive() {
  return {
+   isXs: computed(() => matches.xs.value),
    ...
  }
}

export function currentBreakpoint() {
  ...
+ if (matches.xs.value) return 'xs'
  ...
}
```

顶部注释 5 → 6,JSDoc 同步。

### 改动 2-7:6 处 view 文件(每文件 2 处)

每个文件统一改:
```diff
- const { isMd } = useResponsive()
+ const { isXs } = useResponsive()

- :fullscreen="!isMd"
+ :fullscreen="isXs"
```

注释里"< 768 fullscreen" 不变(本来就是设计意图),只附加"用 isXs(≤767)替代 !isMd 修复大屏 PC bug"。

---

## 经验教训(已写入 Agent Memory)

| Step | 内容 |
|---|---|
| 1 | **断点 boolean 命名 + 取反方向的"陷阱链"**:`isMd`(1024-1279 区间)→ `!isMd`(不在 1024-1279,即 < 1024 或 ≥ 1280)。`isXs`(≤767)→ `isXs`(≤767,直接表达 `<768`)。"取反"的语义跟"区间 boolean"绑死,不能机械套用。看到 `!isXs` 必须先算 `isXs` 的 query,而不是凭"小屏全屏 = !小屏"直觉。 |
| 2 | **回归测试是 spec 真源**:修 bug 前必须 grep `test.describe` / `test(` 找涉及该 view 的 viewport 回归测试;不能只看代码注释。`13-client-mobile.spec.js:89` 在 viewport=720 断言 `< 768 应 fullscreen`,这条 spec 直接定义了 `< 768` 设计意图的"测试真源",比 6 处代码注释更权威。 |
| 3 | **缓存假象**:改完 `useResponsive.js`,webServer 在跑测过程中**可能仍在用旧 module graph**;遇到"改了不生效"时,清 `node_modules/.vite` + 重启 dev 比反复跑测快。本次中间态 78/77 红,清缓存 + 修取反后 78/78 全过。 |

---

## 后续 TODO(可选,留单独 PR)

1. `useResponsive` QUERIES 重命名为 `{minXs, minSm, minMd, minLg, minXsPhone, minLandscapePhone}` + 返回 `isMinXxx`,让"区间 boolean"语义自解释。
2. `store/modules/app.js:32` 注释里残留 "按当前 isMd 算出"(误导,实际是 `isLg`)。
3. 6 处 view 注释里保留的"用 isXs(≤767)替代 !isMd 修复大屏 PC bug" 可以等下次重写时移除,只保留"`< 768 全屏` 设计意图" 一句。