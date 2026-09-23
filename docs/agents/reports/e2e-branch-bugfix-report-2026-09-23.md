# Bug 诊断复盘 — desktop-adapt-v2 sidebar 断点反

- **诊断时间**:2026-09-23 10:30-10:45(Asia/Shanghai)
- **诊断人**:Mavis(root session)
- **触发**:用户要求"修复你发现的 bug" + 触发 `mattpocock-skills:diagnosing-bugs` skill
- **关联报告**:[e2e-branch-report-2026-09-23.md](./e2e-branch-report-2026-09-23.md)
- **修复分支**:`feature/desktop-adapt-v2-admin-user-followup`
- **改动的文件**(3 个):
  - `frontend/src/layouts/AdminLayout.vue` — 主修复(3 处 `isMd` → `isLg`)
  - `frontend/src/utils/sidebar.js` — 文档同步(注释 + 函数签名)
  - `frontend/e2e/specs/13-client-mobile.spec.js` — spec 自身 typo(`authFile('family')` → `authFile('fam001')`)
- **修复前**:**51/27**(27 unique failed,3 sidebar + 24 family.json ENOENT)
- **修复后**:**78/0**(78 passed,1.5 min,数据回滚干净)

---

## Phase 1 · Feedback loop(red-capable)

| 维度 | 评估 |
|---|---|
| Loop | `pnpm exec playwright test specs/12-admin-responsive.spec.js` |
| Red-capable | ✅ 12 spec 第 38 行直接断言 sidebar 宽度,3 档断点全败 |
| Deterministic | ✅ 每次跑都红(无 flake) |
| Fast | ✅ 单 spec 约 30s,全套 78 个 1.5 min |

**结论**:loop 满足所有 skill 要求,**已有,无需重建**。

---

## Phase 2 · 复现 + 最小化

失败数字(修复前):
| 断点 | 期望 | 实际 | 解读 |
|---|---|---|---|
| sm-1024 | `<80px` | 220px | 没收到 |
| md-1280 | `>200px` | 64px | 没展开 |
| lg-1920 | `>200px` | 64px | 没展开 |

**最小化**:已极简——单一断点断言足够,**无需进一步裁**。

---

## Phase 3 · Hypotheses(3 候选)

按可能性排序:

### H1:AdminLayout 用错断点变量(isMd 应为 isLg)— **确认**
- 假设:`isMd` 是 1024-1279 区间 boolean,实际意图是 ≥1280
- 预测:如果是真,把 `isMd.value` 改成 `isLg.value` 后,1024 显 64(收起),1280/1920 显 220(展开)
- **验证**:✅ 100% 吻合失败数字
- **预测成功** = H1 是真因

### H2:store 默认值 / localStorage 残留 → sidebarMode 不是 'auto'
- 检查 `appStore.sidebarMode` 默认值:`getSidebarMode()` 从 `localStorage.getItem('nianglin_admin_sidebar_mode')`,不在白名单时返回 `null` → 走 auto 分支
- 检查 `admin.json` storageState:localStorage 项**只有** token + userInfo,**没有** sidebar 键
- 预测:store 是 'auto',走 `isMd` 分支 → 与 H1 重合,**排除**

### H4:`el-aside :width` 没生效(参数绑定问题)
- 检查:同 spec 内 8 个表格页 + dashboard + export 24 个 test 全过 → `:width` 绑定正常工作
- **排除**

---

## Phase 4 · Instrument

不需额外 instrument——读源码已能 100% 确定根因。代码本身即是 instrument:

```js
// useResponsive.js:34-36
md: '(min-width: 1024px) and (max-width: 1279px)',   // ← 1024-1279 区间 boolean
lg: '(min-width: 1280px)'

// sidebar.js:46  (修复前)
effective = isMd ? SIDEBAR_MODE_EXPANDED : SIDEBAR_MODE_COLLAPSED
//              ↑ 1024-1279 → true → 220(展开)
//              ↑ ≥1280 / <1024 → false → 64(收起)
```

设计语义确认:`docs/spec/desktop-adapt-v2.md:45` 明确写"**AdminLayout 侧栏:< 1280 默认收起(64px icon-only),≥ 1280 展开(220px);保留手动切换**"。

---

## Phase 5 · Fix + regression test

**regression test 不需要新写** —— `specs/12-admin-responsive.spec.js:38` 已经是 red-capable 的回归测试:

```js
test('sidebar 自适应(< 1280 收起,≥ 1280 展开)', async ({ page }) => {
  ...
  if (vp.width < 1280) {
    expect(width, '1024 应收起(sidebar 64px)').toBeLessThan(80)
  } else {
    expect(width, '≥1280 应展开(sidebar 220px)').toBeGreaterThan(200)
  }
})
```

### Fix 1:AdminLayout.vue(主修复)

`useResponsive().isMd` → `useResponsive().isLg`,3 处调用点同步改:
- `:27` import 解构
- `:33` 传给 `resolveSidebarMode`
- `:60` 传给 `toggleSidebarMode`(handleToggleSidebar 内)

加注释说明命名误导。

### Fix 2:sidebar.js(文档同步)

- 函数签名 `resolveSidebarMode(storedMode, isMd)` → `resolveSidebarMode(storedMode, isLg)`
- 函数签名 `toggleSidebarMode(storedMode, isMd)` → `toggleSidebarMode(storedMode, isLg)`
- 函数体 `isMd ? ... : ...` → `isLg ? ... : ...`
- 顶部注释里的"按 useResponsive().isMd 翻转" → "按 useResponsive().isLg 翻转",加 ⚠️ 警示

### Fix 3:13-client-mobile.spec.js(spec typo)

`:32` `authFile('family')` → `authFile('fam001')`(ACCOUNTS 里 family 角色具体账号是 `fam001` / `fam019`)。

---

## Phase 6 · Cleanup

- [x] 原始 repro 不再 reproduce(78/78 全过)
- [x] Regression test 通过(12 spec 3 档断点 sidebar 全过)
- [x] 无 `[DEBUG-...]` instrumentation(我没加临时 log,直接读代码定位)
- [x] 无 throwaway prototype(只有 commit-ready fix)
- [x] 假设写进 commit message(见下方 Commit 信息模板)

---

## Commit 信息模板

```
fix(desktop-adapt-v2): admin sidebar 断点误用 isMd → isLg

T-09 跑测发现 12-admin-responsive.spec.js 3 档断点 sidebar 全败:
- sm-1024: 期望收起(<80px),实际 220px(展开)
- md-1280: 期望展开(>200px),实际 64px(收起)
- lg-1920: 期望展开(>200px),实际 64px(收起)

根因: useResponsive.js 中 md = (min-width:1024 and max-width:1279)
是 1024-1279 区间 boolean,不是 "中等屏" boolean。AdminLayout.vue
误用 isMd 做 sidebar 收起判断,导致断点完全反了:
- isMd=true (1024-1279)  → 220px 展开 ✗
- isMd=false (≥1280)    → 64px 收起 ✗

设计语义见 docs/spec/desktop-adapt-v2.md §2.1: <1280 收起 / ≥1280 展开。
正解: 用 isLg (= ≥1280)。

修法: AdminLayout.vue 三处 isMd → isLg;sidebar.js 注释 + 函数签名同步。
13-client-mobile.spec.js 一并修 storageState typo(family.json 不存在)。

⚠️ 命名误导: useResponsive 5 档中 md 不等于"中等屏"。建议下个
refactor 把 QUERIES 改成 {minSm: 'max-width:1023', minMd: 'min-width:1024 and max-width:1279', minLg: 'min-width:1280'},
返回 isBoolMinXxx 让语义自解释;但本次保持 minimal,不进入范围。
```

---

## 额外发现(不属于本次修复范围)

`isMd` 在仓库里有 **6 处其它误用**(同名问题,但语义是另一回事):

| 文件 | 行 | 用途 | 设计意图 | 当前 `!isMd` 实际行为 |
|---|---|---|---|---|
| `views/profile/index.vue` | 76, 359 | 修改密码弹窗 fullscreen | < 1024 全屏 | ≥1280 时也 fullscreen=true ✗ |
| `views/admin/order.vue` | 93, 170 | 订单详情弹窗 | 同上 | 同上 ✗ |
| `views/admin/order-dispute.vue` | 104, 150 | 仲裁弹窗 | 同上 | 同上 ✗ |
| `views/admin/companion-audit.vue` | 130, 187 | 资质详情弹窗 | 同上 | 同上 ✗ |
| `views/family/elder-list.vue` | 44, 174 | 老人档案弹窗 | 同上 | 同上 ✗ |
| `views/family/medication.vue` | 126, 598 | 用药计划弹窗 | 同上 | 同上 ✗ |

**真相是 `isMd` 在 < 1024 时是 false),这些 mount 在 ≥1280 时 isMd=true ⇒ 全屏 = false**(非全屏,这恰好是想要的行为)。

但是 `1280 ≤ w < 1024` 不存在(数学上 false),所以这个区间跳过。

`1024 ≤ w < 1280` 时 isMd=true → `!isMd = false` → 不全屏 ✓
`w ≥ 1280` 时 isMd=false → `!ismd = true` → **全屏 ✗**(设计意图:不全屏)

所以 **大屏 PC(≥1280)上,这些 dialog 会被强制全屏**。

**修复建议(下次单独工作)**:这 6 处 `!isMd` → `!isXsPhone`(即 "xsPhone 之外才全屏")或 `isXsPhone`(看设计意图反着)。或者更准确:用 `< 1024` 区间 boolean `isSm`。

按工作流 §3「不捎带重构」,**本次未修**,留作单独 PR 跟进。

---

## 经验教训(写入 Agent Memory)

写到 `C:\Users\wang\.minimax\agents\mavis\memory\MEMORY.md`:

- **命名误导**:`useResponsive` 这种 5 档断点 composable 的 `isMd`/`isLg`/`isSm` 不是"中等屏"/"大屏"/"小屏",而是**是否在该档区间**的 boolean。看到 `isXxx` 必须先读 `QUERIES` 定义确认实际含义。
- **spec 复盘**:E2E spec 写错 storageState 名字(`authFile('family')` 而非具体账号)会**完全 silently fail**(immediate ENOENT,不像 timeout)。spec 改完要 grep 所有 `authFile()` 调用,确认每个 account 名都在 ACCOUNTS 列表里。