# P0-2 · 接单开关：三处失效 + 一处假承诺

> 批次：**B（用药与文档域）** · 级别：P0 · 来源：`reports/business-closure-final-assessment-2026-11-28.md` §P0-2
> 错误码：复用 `2003 COMPANION_NOT_AUDITED` 或新增（见 Implementation Decisions）
> ⚠️ **本单先于 A 批合并**：A 批的 P0-1 会在同一文件的 `hall` / `accept` 上加条件，必须先落地本单再让 A 批 rebase。

## Problem Statement

陪诊员在页面上把「接单状态」从「可接单」切到「休息中」，页面明确承诺「新订单不会再出现在你的大厅里，已接的订单照常服务」。

实际上**三件承诺全是假的**：

1. **大厅照常返回**：`hall()` 不看接单状态，休息中的人照样看到所有待接单订单。
2. **抢单照常成功**：`accept()` 不看接单状态，休息中的人照样能抢到单。
3. **新单照样推送**：下单广播只筛「资质通过」，不筛「接单状态」，休息中的人照样被新单打扰。

而抢单按钮的禁用条件只绑了「正在请求」状态，不绑「是否可接单」——所以页面既说「不会出现在大厅里」，又把抢单按钮亮着。

## Solution

让「接单状态」在所有需要判断「这人现在能不能接单」的地方真正生效，收敛到 `WorkStatus.isAccepting()` 这一个判定入口。前端在不可接单时禁用抢单按钮并给出明确的引导。

## User Stories

1. 作为陪诊员，我想切到「休息中」后大厅里不再有新订单，这样我的承诺能被兑现。
2. 作为陪诊员，我想切到「休息中」后抢单按钮置灰，这样我不会误以为还能抢。
3. 作为陪诊员，我想在按钮置灰时看到「切回可接单才能抢单」的引导，这样我知道怎么恢复。
4. 作为陪诊员，我想切到「休息中」后不再收到新单推送，这样我不会被打扰。
5. 作为陪诊员，我想切回「可接单」后立刻能看到大厅订单，这样我不用等缓存刷新。
6. 作为陪诊员，我想「休息中」时仍能看到自己已接的订单并继续服务，这样我的在手工作不中断。
7. 作为陪诊员，我想「休息中」时仍能完成打卡与完成服务，这样服务不因我的接单状态被卡住。
8. 作为家属，我想推给陪诊员群里只有真正在接单的人，这样我的单更容易被接。
9. 作为家属，我想催单只发给在接单的人，这样催单不会石沉大海。
10. 作为管理员，我想改派时不能指派给休息中的陪诊员，这样指派不会被无视（**这条已实现，本单不动**）。
11. 作为管理员，我想看到「接单中」的陪诊员数量，这样我能评估运力。
12. 作为测试者，我想看到「休息中的陪诊员不能抢单」的用例。
13. 作为测试者，我想看到「休息中的陪诊员不在推送列表里」的用例。
14. 作为测试者，我想看到「离线状态同样不可抢单」的用例（不只是 REST）。
15. 作为开发者，我想三处判定都走 `isAccepting()` 而不是裸字符串比较，这样将来加新状态不会漏。
16. 作为开发者，我想这次改动不动订单状态机，这样风险可控。
17. 作为开发者，我想这次改动不动 `hall` / `accept` 以外的方法，这样 A 批 rebase 时冲突最小。

## Implementation Decisions

### 后端（三处）

1. **`OrderServiceImpl#hall`**：在 `requireApprovedCompanion` 之后、构造查询之前，加「当前陪诊员必须 `isAccepting()`，否则抛 `2003`」。
   - **为什么拒绝而不是返回空列表**：空列表会让用户以为「今天真的没单」，而真相是「你没开接单」。参照 `resolveHallSortKey` 的既有注释——「静默降级会让用户拿错误的前提做决定」。
   - 复用 `2003 COMPANION_NOT_AUDITED` 的语义不贴切（那是「资质未通过」）。**建议新增 `2004 COMPANION_NOT_ACCEPTING`「接单状态为休息中/离线，暂不可接单」**——需先在 README §五 错误码预算表登记。
2. **`OrderServiceImpl#accept`**：同样加 `isAccepting()` 判定，抛同一码。
3. **`OrderServiceImpl#approvedCompanionUserIds`**（下单广播）：查询加 `.eq(CompanionProfile::getWorkStatus, WorkStatus.AVAILABLE.name())`。
   - **已有现成写法可抄**：`FulfillmentServiceImpl#notifyAvailableCompanions`（催单广播）就是这么写的。

### 收敛裸字符串比较

- `MatchingServiceImpl` 里的 `WorkStatus.AVAILABLE.name().equals(...)` 改为 `WorkStatus.of(...).isAccepting()`，兑现枚举注释「唯一判定入口」的承诺。
  - **纪律**判断：若 `WorkStatus.of` 对未知字面量返回 null，需要 null 安全写法（`WorkStatus s = of(x); s != null && s.isAccepting()`）。

### 前端

- `views/companion/hall.vue`：抢单按钮 `:disabled` 增加 `!isAccepting` 条件；不可接单时给明确引导文案（替换现有的「新订单不会再出现在你的大厅里」——那句话只有在后端真正过滤后才成立，本单落地后它才为真）。
- **注意**：本单落地后那句文案就变成真的了，所以可以保留，但要补一句「切回『可接单』即可看到新订单」。

### 接口变更

- `GET /api/order/hall`：非 AVAILABLE 时返回 `2004` 而不是列表。
- `POST /api/order/{id}/accept`：非 AVAILABLE 时返回 `2004`。
- `docs/api/03-order.md` §3 大厅、§6 接单补充错误场景；`docs/api/README.md` 2xxx 错误码表新增。

## Testing Decisions

### 好测试

断言「在 `workStatus = REST / AVAILABLE` 两种前置下，`hall` 与 `accept` 分别抛码 / 正常返回」。用 `@Mock CompanionProfileMapper` 控制档案返回值。

### 测试模块

新建 `CompanionAcceptingGateTest`（`CompanionWorkStatusTest` 测的是「切换状态」这件事，本单测的是「状态生效」，分开更清楚）

### 垂直切片

1. **红**：`休息中陪诊员进大厅应返回2004`。
2. 实现 hall 判定。
3. **红**：`离线陪诊员进大厅应返回2004`（证明不只判 REST）。
4. **红**：`可接单陪诊员进大厅应正常返回`（保护现状）。
5. **红**：`休息中陪诊员抢单应返回2004`。
6. 实现 accept 判定。
7. **红**：`可接单陪诊员抢单应成功`（保护现状）。
8. **红**：`下单广播只发给可接单的陪诊员`（断言 broadcast 查询含 workStatus 条件）。
9. **红**：`MatchingServiceImpl对未知workStatus应安全跳过`。

### 先例

`CompanionWorkStatusTest`（`isAccepting()` 的既有断言风格）；`FulfillmentServiceUrgeTest`（`notifyAvailableCompanions` 的写法可直接参照）。

## Out of Scope

- **不动 `AdminServiceImpl#reassign`**（改派时已判 `isAccepting()`，是对的）。
- 不动「休息中能否接已被指派的单」——**已有答案**：`AdminServiceImpl#reassign` 已定「休息中不可被改派」，本单按同一口径。
- 不动订单状态机。
- 不动 `complete` / `forceTerminal` / `cancel`（属 A 批）。

## Further Notes

- **与 A 批 P0-1 的冲突点明确**：A 批要在 `hall` 加 `companion_id IS NULL`、在 `accept` 加「已被指派」判定。本单要在同样两个方法里加 `isAccepting()` 判定。**本单先合**，A 批 rebase 时两边改动应落在相邻行、冲突可解；若 A 批已先动，则本单 rebase 后必须重跑全部测试。
- **前端那句文案是本单的验收锚点**：「新订单不会再出现在你的大厅里」——修完之后它才第一次为真。建议在验收时让测试员实际点一遍。
- 「按当前实现，休息中的陪诊员去大厅会被拒绝，那他想看看有没有单怎么办」——**答案是他必须切回可接单**。这个取舍要在 `docs/api/03-order.md` 里写清，避免被认为是 bug。
