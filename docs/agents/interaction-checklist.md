# 交互流程验收清单（前端改动的自查基线）

> **这份清单从哪来**：2026-10-25 的一次全量交互流程审查（`frontend/` 56 页面，4 角色 + 管理后台），
> 查出 **33 个问题**（2 合规红线 / 13 阻断主流程 / 18 体验一致性），其中 **4 条会在演示当场翻车**。
> 本文把那些**反复出现的坑**抽成可自查的判据 —— **不是问题清单，是「下次怎么不再犯」**。
>
> **什么时候读**：改 `frontend/` 任何交互（跳转 / 表单 / 空态 / 按钮反馈）前扫一遍；改完按 §5 逐条自查。
>
> ⚠️ **硬约束仍在 `AGENTS.md`**（始终在上下文里）：§0 合规红线、§10 Do/Don't 最低底线、
> §11 验收检查清单。本文是细则，**不覆盖**那些硬约束。
>
> 📌 配套细则：[`frontend-conventions.md`](frontend-conventions.md)（代码风格）·
> [`FRONTEND_CONTRACT.md`](FRONTEND_CONTRACT.md)（接口与夹具）·
> [`testing.md`](testing.md)（测试与门禁）

---

## 0 · 四条会当场翻车的（先看这四条）

这四条是本轮审查里**后果最重**的，共同点是：静态读代码看不出来，**只有真机点一遍才会暴露**。

### 0.1 回车提交会发两次请求 → 第二次必然失败

**症状**：演示时用回车登录，弹「验证码错误或已过期」，但密码明明是对的。

**根因**：`el-form` 上的 `@submit.prevent` 与输入框上的 `@keyup.enter` **同时**存在。
浏览器在文本框按回车会触发表单隐式提交（submit 事件），`keyup` 又再触发一次 →
`handleSubmit` 执行两次。而**验证码是一次性的**（后端 `CaptchaServiceImpl#validate`
用 Redis `getAndDelete`，取出即删），第二次请求必然拿不到值。

> 2026-10-25 前：`frontend/src/views/login/index.vue` 同时有两处触发。

**判据**：表单只有一个提交入口。若用了 `@submit.prevent`，输入框上**不要再挂** `@keyup.enter`；
反过来也一样。两者只能留一个。

**已修**：删掉 `@keyup.enter`，改 `@keydown.enter.prevent`（`.prevent` 顺带抑制浏览器的隐式提交），
并加 `submitting` 锁（见 §2.1）。

---

### 0.2 同路由换参数时组件被复用 → 页面显示上一单的数据

**症状**：订单列表点进 A 单 → 返回 → 点进 B 单，**URL 是 B，页面还是 A**；
在 A 的界面上点「去评价」，**评价提交给了 A**。

**根因**（两层，缺一不可）：
1. Vue Router 4 在「同一条路由记录只换 params」时**复用组件实例**，`onMounted` 不再触发 ——
   这是 vue-router 的语义，**与 `keep-alive` 无关**；
2. `keep-alive` 的 key 只含路由名时，命中的还是同一个缓存实例，屏幕上连 loading 都不闪。

**判据**：凡是**带 params 的路由**，一律
`const id = computed(() => route.params.id)` + `watch(id, reload)` + `onMounted(reload)`，三者都要有。
拿 `route.params.id` 直接当常量用的，一律是错的。

> 2026-10-25 前：`order-detail` / `order-review` / `order-complaint` / `companion/execute` /
> `companion/message-detail` 五个页面全是 setup 期快照。

**已修**：
- 数据层：五个页面统一 `computed` + `watch` 重拉，并丢弃**过期响应**（请求在途时 URL 又换了单，
  回来时先比对 id，不匹配就丢掉）。
- 缓存层：`frontend/src/layouts/MobileLayout.vue` 的 `cacheKey()` 把 params 纳入 key。
- 提交前：评价 / 投诉 / 打卡**再校验一次**「URL 的 id == 页面展示对象的 id」，不一致就拒绝提交。

**范式**：[`frontend/src/views/family/elder-health.vue`](../../frontend/src/views/family/elder-health.vue) 的
`computed` + `watch`，以及 [`composables/useOrderInfo.js`](../../frontend/src/composables/useOrderInfo.js)
的 `watch(() => toValue(orderId), ...)`。

---

### 0.3 下单换选就诊人不生效 → 订单下给了旧老人

**症状**：step1 选老人 A → step2 → 返回 step1 改选老人 B → 再下一步，
**订单下给了 A**。不可逆。

**根因**：与 0.2 同源 —— step2 的 `elderId` 是 setup 期求值一次的常量，
组件被复用时它不会跟着 URL 变。而且 step2/step3 **都不显示当前就诊人**，
用户全程无从察觉。

**判据**：
- 带就诊人/订单 id 的步骤页，id 必须是**响应式**的；
- **每一步都要显示「当前是给谁下单」**，并提供「更换」入口；
- 提交前再核对一次「URL 的 id == 草稿里的 id」，不一致就退回上一步。

**已修**：`order-step2.vue` 改 `computed` + 就诊人条 + 「更换」入口；`order-step3.vue`
提交前核对 `route.query.elderId` 与 `draft.elderId`。

---

### 0.4 接口失败被显示成「暂无数据」→ 老人以为自己没药

**症状**：老人打开「用药管理」，首屏写着「暂无用药数据 · 您的用药计划由家属代为维护」——
而他其实有计划，只是接口还没回来或挂了；接口真挂了时页面**永久卡在这个空态、没有重试按钮**。

**根因**：空态判断只看了 `list.length === 0`，**没排除 loading，也没区分 error**。

**判据**：任何列表 / 详情页的空态判断必须满足

```
isEmpty = !loading && !error && 数据为空
```

且失败态要落 `NlEmpty type="network"` + `action-text="重试"`。

**已修**：`elder/medication.vue` 与 `elder/home.vue` 拆出 `profileLoading` / `profileError` 两个状态，
失败落可重试的错误态；空态文案按「加载中 / 真的没有 / 加载失败」三种情况分开写。

---

## 1 · 页面跳转路径

| # | 自查项 | 判据 |
|---|---|---|
| 1.1 | 带 params 的路由，id 是响应式的吗 | `computed(() => route.params.x)`，不是常量快照 |
| 1.2 | 同路由换 params 时数据会重拉吗 | `watch(id, reload)` **且** `onMounted(reload)`，两者都要 |
| 1.3 | 在途请求回来时 URL 又换了，数据会不会串 | 丢弃过期响应（比对请求发起时的 id 与当前 id） |
| 1.4 | 越权访问会静默弹走吗 | 落 `/error/403` 并带原路径；403 页要有「回首页」出口（`roleHomePath()` 是唯一真源，见 `router/index.js`） |
| 1.5 | 登录后的 `?redirect=` 校验了吗 | 必须过 `resolveRedirect(rawPath, role)`：站内绝对路径 + 路由存在 + 角色有权 |
| 1.6 | 401 登出后用户丢上下文了吗 | 登出跳转要带 `?redirect=<原路径>` |
| 1.7 | 刷新页面会丢半成品流程吗 | 草稿类流程要明确提示「刷新后已清空」（草稿只存内存是**刻意的**隐私取舍，见 `store/modules/orderDraft.js`） |
| 1.8 | 多步流程中途离开会静默丢选择吗 | 未完成时离开要二次确认；草稿只存内存 → 离开要 `reset` |
| 1.9 | 页面自己定了「跳到哪」的常量吗 | 角色主页只有 `router/index.js` 的 `ROLE_HOME` 一份，别处不许抄 |

---

## 2 · 按钮与状态反馈

### 2.1 in-flight 锁必须在**第一个 await 之前**同步置位

这是本轮出现次数最多的模式错误（13 处）。错误写法：

```js
async function submit() {
  const valid = await formRef.value.validate()   // ← await 在这里
  if (!valid) return
  submitting.value = true                          // ← 置位太晚，两次调用一起穿过 await
  ...
}
```

两次调用会**一起**穿过 `await`，锁形同虚设 → 重复提交 → 用户看到「成功 + 报错」双提示。

正确写法：

```js
async function submit() {
  if (submitting.value) return
  submitting.value = true          // ← 同步置位，在任何 await 之前
  try {
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    ...
  } finally {
    submitting.value = false       // ← finally 复位，校验失败也要解锁
  }
}
```

**判据**：函数第一行是 `if (loading.value) return`，第二行就是 `loading.value = true`。
按钮上绑 `:loading`。

### 2.2 其它自查项

| # | 自查项 | 判据 |
|---|---|---|
| 2.2 | 破坏性操作有二次确认吗 | `ElMessageBox.confirm`；且**取消必须静默返回**（不发请求、不弹错） |
| 2.2 | 确认框的取消分支被捕获了吗 | 用 [`utils/confirm.js`](../../frontend/src/utils/confirm.js) 的 `confirmThen` / `promptThen`；裸 `await ElMessageBox.*` 会产生未捕获的 Promise 拒绝 |
| 2.3 | 按钮的视觉层级分得清吗 | 破坏性 → `type="danger"`；主 CTA 只能有一个 → `type="primary"`；次要 → `plain` / `text` |
| 2.4 | 成功反馈会被下一次 toast 顶掉吗 | 不可逆动作（提交评价 / 提交投诉）的反馈不该只有 3 秒 toast |
| 2.5 | 按钮在操作中会被重复点吗 | `:loading` + `:disabled`，且两者都由**同步**置位的锁驱动 |
| 2.6 | 反馈渠道统一吗 | 统一 `import { ElMessage } from 'element-plus'`；**禁止** `this.$message`（Options API 写法） |

---

## 3 · 表单填写与出错提示

| # | 自查项 | 判据 |
|---|---|---|
| 3.1 | 校验错误落在字段上吗 | 用 `el-form` + `rules`，错误落在字段下方；**不要**用「一串 `if` + `ElMessage.warning`」——一次只报一个错、不知道该改哪个字段、toast 3 秒就没了 |
| 3.2 | 规则与后端一致吗 | 共享常量放 [`constants/validation.js`](../../frontend/src/constants/validation.js)，与后端 `ValidationPatterns` 同口径；不要每页各写一份正则 |
| 3.3 | 正则比后端松吗 | 身份证后端查地址码 + 日期段；`^\d{17}[\dXx]$` 这种「只数长度」的松式会让非法值一路走到后端才被拒 |
| 3.4 | 手机号/身份证的键盘对吗 | 手机号 `type="tel"` + `inputmode="numeric"`；**身份证不能加 `inputmode="numeric"`** —— 末位可能是 X，数字键盘打不出字母 |
| 3.5 | 后端的字段级错误码落字段了吗 | 如 `1006`（原密码不正确）→ `setFieldError('oldPassword')`；定位不到字段的才走全局兜底 toast |
| 3.6 | 错误提示重复了吗 | 字段级错误已承接时，**撤掉**全局兜底 toast（`ElMessage.closeAll()`），否则同一条错误弹两次 |
| 3.7 | 提交后表单状态清了吗 | 成功后 `reset` + `clearValidate()`；失败时**保留**用户输入 |

---

## 4 · 空状态与异常分支

| # | 自查项 | 判据 |
|---|---|---|
| 4.1 | 空态判断排除了 loading 和 error 吗 | `isEmpty = !loading && !error && 数据为空` |
| 4.2 | 「真的没有」和「加载失败」分得开吗 | 失败 → `NlEmpty type="network"` + 「重试」；空 → `NlEmpty type="empty"` + 说明**为什么**空 + 下一步动作 |
| 4.3 | 空态文案提的交互存在吗 | 全站**没有下拉刷新**（`pull-to-refresh` 零命中）→ 文案不得提「下拉刷新试试」 |
| 4.4 | 空态有下一步吗 | 至少一个 `action-text` + `@action`（去绑定 / 去下单 / 去刷新） |
| 4.5 | 推送断开会静默吗 | WS/SSE 断开要有 `NlNoticeBar` 明说「进度可能有延迟」；**不要**承诺不存在的轮询 |
| 4.6 | 空态实现统一吗 | 统一用 `NlEmpty`；不要自绘裸 `<p>暂无…</p>`，也不要拿 `NlStatusChip` 冒充空态 |
| 4.7 | 组件 prop / 插槽写错会静默吗 | `NlEmpty` / `NlSkeleton` 有开发期自检（[`utils/componentGuard.js`](../../frontend/src/utils/componentGuard.js)）：未声明的 prop 会落到根元素、**未声明的插槽会被 Vue 直接丢弃**（403 页曾因此一个按钮都没有） |
| 4.8 | 分页回退判据对吗 | 「**本页被掏空**」才回退，**不是**「刚处理的那条不在本页」——后者会在本页还有别的行时也跳页，把用户从正在看的内容上拽走。判据见 [`utils/pagination.js`](../../frontend/src/utils/pagination.js) 的 `shouldRollbackPage` |
| 4.9 | 列表会被截断吗 | 显示「共 N 条」就要能翻到第 N 条；固定 `size: 50` 却不给分页 = 假可达 |

---

## 5 · 自查清单（可勾选）

改完前端交互，逐条过：

```markdown
- [ ] 带 params 的页面：id 是 computed + watch 重拉 + 提交前二次校验（§0.2 / §1.1–1.3）
- [ ] 写操作函数：锁在第一个 await 之前同步置位，finally 复位（§2.1）
- [ ] 破坏性操作有确认框，取消分支静默返回且不发请求（§2.2）
- [ ] 表单：el-form + rules，错误落字段；规则用共享常量（§3.1–3.3）
- [ ] 手机号 type="tel"；身份证**不加** inputmode="numeric"（§3.4）
- [ ] 空态：排除 loading/error，失败态可重试，文案不承诺不存在的交互（§4.1–4.3）
- [ ] 越权落 403 且有回首页出口；redirect 过白名单（§1.4–1.5）
- [ ] 共享组件的 prop / 插槽名与声明一致（§4.7）
- [ ] 新增的交互类陷阱已补进本文（见 §6 的规则）
```

---

## 6 · 护栏在哪（自动化）

改了前端交互，**顺手把回归护栏补上** —— 本轮新增的：

| 护栏 | 位置 | 覆盖 |
|---|---|---|
| e2e 交互护栏 | [`frontend/e2e/specs/22-ux-interaction.spec.js`](../../frontend/e2e/specs/22-ux-interaction.spec.js) | 换单刷新 / 换单评价目标 / 换就诊人下单 / 老人端三态 / 连点防重 / 越权 403 / 陪诊员入口 / 姓名脱敏 / 重置密码响应面 / 分页控件 / 空态文案 / 推送断线 / 确认取消 / 履约重试 |
| 分页判据单测 | [`frontend/src/__tests__/use-paged-list.test.js`](../../frontend/src/__tests__/use-paged-list.test.js) | `shouldRollbackPage` 真值表 + `usePagedList` 的回退/重载次数（**带变异自检**，证明断言不是恒真） |
| 前端契约文档 | [`docs/agents/FRONTEND_CONTRACT.md`](FRONTEND_CONTRACT.md) | 路由常量、E2E 夹具与账号口令出处 |

⚠️ **写路径 e2e 的靶子必须在 `tools/e2e/fixture.py` 的白名单内**
（`sys_user` 白名单 = id `1,2,101,201-230,301`），
否则 `globalTeardown` 的 restore 不会把它改回去 → **污染本地开发库**。
优先选「白名单内 **且** 没有其它 spec 在用」的账号（如 `elder002` id=202）。

---

## 7 · 这份清单怎么长大

本文不是一次性文档。**改动过程中撞见的新坑，补进对应小节**（写清「症状 → 根因 → 判据」三段），
并在 [`doc-sync.md`](doc-sync.md) 的表里已经有对应行：

> 新增 / 修改前端交互流程 → 自查本文；**若引入了本文还没收录的陷阱，补进本文**。

补的时候记得带上**真实的文件与行号** —— 没位置的条目下次没人用得上。

---

> 审查原始记录：2026-10-25，PR #28（`feature/ux-interaction-audit` → `main`）。
> 33 个问题的完整清单与修复过程见该 PR 描述。
