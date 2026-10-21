# E2E 跑测报告 — desktop-adapt-v2 分支回归

- **执行时间**:2026-09-23 10:25(Asia/Shanghai)
- **执行人**:Mavis(root session)
- **分支**:`feature/desktop-adapt-v2-admin-user-followup`
- **触发**:"playwright 测一下" → 工作流 §4 完整版,范围用户拍板 = "当前分支相关 4 个 spec"
- **跑测范围**:`03-elder` + `08-admin` + `12-admin-responsive` + `13-client-mobile`
- **总耗时**:2.4 min(78 个 unique test)
- **环境**:MySQL80 ✅ Running / Redis ✅ Running / 后端 8080 ✅ HEALTH OK(profiles=dev)/ 前端 5141 由 Playwright `webServer` 自起 / `fixture.py` snapshot → restore → verify 全通过,数据库无污染
- **详细产物**:`reports/playwright/results.json` + `tests/test-results/`(trace + error-context)

---

## 1 · 总览

| 文件 | pass | fail | 备注 |
|---|---:|---:|---|
| `fixtures/auth.setup.js` | 7 | 0 | 7 账号全部登录态建立 |
| `specs/03-elder.spec.js` | 8 | 0 | ✅ 全绿 |
| `specs/08-admin.spec.js` | 11 | 0 | ✅ 全绿 |
| `specs/12-admin-responsive.spec.js` | 24 | **3** | ❌ sidebar 自适应 3 档断点全败 |
| `specs/13-client-mobile.spec.js` | 1 | **24** | ❌ 全败(根因 = spec 自身 bug,见 §3) |
| **合计** | **51** | **27** | 78 unique test |

---

## 2 · 真实回归 — `12-admin-responsive` sidebar 自适应(3 档断点全败)

`authFile('admin')` 加载 OK → 不是 storageState 问题 → **真实 CSS 视觉回归**。

| 断点 | 期望 | 实际 | 状态 |
|---|---|---|---|
| `sm-1024 (1024×768)` | `<1280 应收起(sidebar < 80px)` | **220 px** | ❌ 没收到(还是 220 展开态) |
| `md-1280 (1280×800)` | `≥1280 应展开(sidebar > 200px)` | **64 px** | ❌ 没展开(变成 64 收起态) |
| `lg-1920 (1920×1080)` | `≥1280 应展开(sidebar > 200px)` | **64 px** | ❌ 没展开(变成 64 收起态) |

**异常模式**:1024 显 220,1280/1920 显 64 — **断点接反了**,不是渐变式失效,是一个明确的 CSS 类挂载错位。

**调查起点**(不修,只点方向):
- 12 spec `:34` 用的 `storageState: authFile('admin')` 正确 → 排除登录态。
- `:41` `await page.waitForSelector('.admin__aside', { state: 'visible' })` 通过 → aside 元素存在。
- `:44-48` 断言拿到的是 `aside.getBoundingClientRect().width`,**不是 CSS 变量**,所以是**最终盒模型宽度**,不是理论宽度 → 真的是渲染结果。
- 期望值是硬数字(80 / 200),不是变量断言 → 是 css 类没切换,不是 CSS 变量没切。
- 大概率:`@media (min-width: 1280px)` 的类切换没生效,或 js 里 `v-if` 反了,或 Pinia store 的 `sidebarCollapsed` 默认 true,sm-1024 时应该被响应式钩子设 false 但没设。

**12 其余 24 个 test 全通过**(订单/用户/投诉/订单纠纷/陪诊员资质/操作日志 × 3 断点 + dashboard × 3 + export × 3)= **NLAdminTable 双形态切换 KPI / 表单 max-width 都 OK**,问题**只**在 sidebar 这一个东西。

---

## 4 · Spec 自身 bug — `13-client-mobile` 全军覆没(24/25 败)

**不是代码回归,是 spec 文件写错了**:

- `:32` 写 `storageState: authFile('family')` → 期望读 `.auth/family.json`
- 但 `e2e/helpers/accounts.js` 的 `ACCOUNTS` 列表里 FAMILY 角色叫 `fam001` / `fam019`,**没有叫 `family` 的账号**
- `auth.setup.js` 跑出来的是 `fam001.json` + `fam019.json`,**不会**生成 `family.json`
- 所以 Playwright 加载 `family.json` 时直接抛 `ENOENT`,新页面都建不了 → 24 个 test × retry 全 immediate failure(3-5ms)

**修复(一行)**:`specs/13-client-mobile.spec.js:32` 改 `authFile('fam001')` 或 `authFile('fam019')`(任选其一,spec 只读不写)。

**唯一通过的 test**:`el-dialog fullscreen 行为`(`specs/13-client-mobile.spec.js:85`),它用的是 `authFile('admin')` → 正常加载 → 测了 admin 详情弹窗 < 768 走 fullscreen ✅。这个 test 严格说应该归 12 但放 13 里(可能是 T-09 写 spec 时复用了 setup 数据),**逻辑上没问题,只是 spec 归属不当**。

---

## 5 · 数据完整性 ✅

`tools/e2e/fixture.py` snapshot → 跑测 → restore → verify **全链路干净**:
- 17 张被写表水位记录 → 删除 id 超水位的行
- 6 张影子表(对复合 id 边界行)→ REPLACE INTO 回写
- Redis 8 个键(`order:seq` / `pwd:version`)→ 还原
- 上传文件 0 新增 → 无删除
- 最终 `state.json` → `state.done.json` 改名 = 干净凭证

`reports/e2e/fixture/state.done.json` 已落盘。

---

## 6 · 处置建议(按你拍板)

| # | 项目 | 类型 | 推荐动作 | 阻塞性 |
|---|---|---|---|---|
| 1 | 12 sidebar 断点反了 | **真实 CSS 回归** | 走 §3 诊断工作流(修) | 阻塞 12 spec 全绿 |
| 2 | 13 spec 写错 storageState 名字 | spec 编写错(分支引入) | 一行修改:`authFile('family')` → `authFile('fam001')` | 阻塞 13 spec 全绿(其他 12 个 spec 不受影响) |
| 3 | el-dialog fullscreen 归属 | 标签建议 | 把 `:85` 这个 test 从 13 挪到 12(它用 admin) | 不阻塞 |

**全部修完后**:重跑这 4 个 spec,预期 78/78 全绿。

---

## 7 · 产物索引

| 路径 | 内容 |
|---|---|
| `reports/playwright/results.json` | 跑测结构化结果 |
| `reports/playwright/html/index.html` | HTML 报告 |
| `reports/playwright/.auth/` | 7 个 storageState(`admin` / `fam001` / `fam019` / `comp001` / `comp007` / `comp025` / `elder001`),**含真实 JWT,严禁进库** |
| `reports/playwright/test-results/` | 失败用例的 trace.zip + error-context.md + screenshot |
| `reports/e2e/fixture/state.done.json` | 数据回滚干净证明 |
| `backend/logs/run.out` | 后端 spring-boot:run 全程日志(后台启动) |

> AGENTS.md §0 / .gitignore:`reports/` 已在根目录被忽略,产物**不会进库**。