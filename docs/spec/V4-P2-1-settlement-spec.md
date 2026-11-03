# V4 · P2-1 结算闭环 · 工程规格

> 状态：**待审批**（审批通过后才进入编码）
> 分支：`feature/p2-settlement-loop`（从 `feature/v4-phase1` 切出，单独提交、不 push）
> 依据：用户 P2-1 七条要求 + `docs/plan/iteration-plan-v4.md` + `AGENTS.md` 红线
> 关联文档：`docs/api/08-admin.md`、`docs/api/09-statistics-export.md`、`docs/api/README.md`

---

## 一、背景与现状核实结论（已完成，有据可查）

| 核实项 | 结论 | 证据 |
|---|---|---|
| `payment_status` 取值 | 仅 `UNPAID` / `SETTLED` | [PaymentStatus.java](../../backend/src/main/java/org/company/nianglin/constant/PaymentStatus.java) |
| 唯一写入点 | 下单时置 `UNPAID`；**全仓库没有任何入口改成 `SETTLED`** | `OrderServiceImpl` 下单分支；全量 grep `setPaymentStatus` |
| `actual_fee` 写入点 | **无**；统计/导出用 `COALESCE(actual_fee, fee)` 兜底 | `StatisticsServiceImpl#toOrderExportRows` 等 |
| 金额口径 | `fee` 为下单时服务费；`actual_fee` 为线下结算回填的实际金额，可空 | `V1__init_schema.sql` 行 308–310 |
| 结算时间字段 | **不存在**（表里只有 `finish_time` / `update_time`） | `companion_order` DDL |
| 索引 | 已有 `KEY idx_payment_status (payment_status)` | `V1__init_schema.sql` 行 334 |
| 管理端残留 | 无结算页面、无结算接口、无结算文档章节、无结算导出列 | `frontend/src/views/admin`、`api/*.js`、`docs/api` 全量检索 |
| 既有导出范式 | `ExportService.writeExcel(...)` + `@ExcelProperty` 列 VO + 超限 `9001` | `StatisticsServiceImpl` / `OrderExportVO` |
| 留痕范式 | `OperLogRecorder.record(OperType, OperTargetType, targetId, targetDesc, before, after, remark)` | `OperLogRecorderImpl` |

### 由现状反推的两条设计约束

1. **不需要新增迁移**。`payment_status`、`actual_fee`、`idx_payment_status` 均已存在（`V8` 及之前已应用，按规则不得原地修改）。结算时间不落新列，核定时间以 `admin_oper_log.oper_time` 为准（逐单留痕天然带时间）。
2. **金额只能服务端算**。核定时把 `actual_fee` 快照为 `COALESCE(actual_fee, fee)`，保证「核定那一刻的结算金额」被冻结；接口不接收任何金额字段。

---

## 二、范围与非目标

### 范围内

- 管理端结算核对列表（只看 `COMPLETED` / `REVIEWED`）
- 单条核定 + 批量核定（幂等、逐单留痕）
- 结算单 Excel 导出（按服务完成时间区间）
- 管理端页面（列表 + 核定 + 批量核定 + 导出）

### 明确不做（避免扩大范围）

- 不做退款、冲正、撤销核定（核定后不可回退，如需回退另立工单）
- 不做在线支付/收款渠道（红线不变，仅「线上记账 + 线下结算」）
- 不改 `OrderStatus` 状态机，不因 `UNPAID` 阻断任何既有流程
- 不新增依赖、不改 `V1`–`V8` 任何已应用脚本

---

## 三、关键决策

| # | 决策 | 理由 |
|---|---|---|
| D1 | 结算台账**只收** `COMPLETED` / `REVIEWED`，`CANCELLED` 永不进入 | 取消单没有可结算的服务，混进台账会让账面数字虚高 |
| D2 | 「周期」按 **`finish_time`（服务完成时间）** 闭区间，不按下单时间 | 结算依据是服务完成；下单时间属于订单统计口径，两者不能混用 |
| D3 | 重复核定 → HTTP 200 + `settledNow=false`，**不写第二条日志** | 幂等语义：客户端可安全重试；日志里不出现无意义空操作 |
| D4 | 非法状态（非 `COMPLETED/REVIEWED`）→ **新增 `8004`** | 不复用 `3002`：结算有自己的前置条件，排障时要能一眼区分 |
| D5 | 批量接口入参是**订单 ID 列表**（1–100 条，去重保序） | 单条用路径参数、批量用列表，语义不混；100 条是单次人工核对的合理上限 |
| D6 | 批量逐项返回结果，**业务失败不中断其余项**；数据库异常整批回滚 | 管理员一次勾选多单，个别不可结算不应让整批白做；系统级错误必须整体一致 |
| D7 | 每个真正变更的订单写**一条** `admin_oper_log`，不写批量汇总日志 | 审计要能按订单追溯「谁在何时核定了这一单」 |
| D8 | 导出复用 `ExportService.writeExcel`，超限抛既有 `9001` | 与既有订单/用户导出同一条路径，避免第二套导出实现 |
| D9 | 新增 `OperType.SETTLE_ORDER("订单结算核定")`，`OperTargetType` 复用 `ORDER` | 不新增目标类型，减少文档与枚举面 |
| D10 | 列表/导出姓名一律 `MaskUtil.name()`，不返回手机号/身份证 | 管理端列表口径一律脱敏，订单详情豁免不适用于本页 |

---

## 四、接口契约（先落文档，后写代码）

### 4.1 `GET /api/admin/settlement` · 结算核对列表

权限：**ADMIN**（`AdminController` 类级 `@PreAuthorize` + `SecurityConfig` 路径规则双保险）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `paymentStatus` | String | 否 | `UNPAID` / `SETTLED`；空=全部 |
| `keyword` | String | 否 | 订单号 / 医院 / 科室 模糊匹配 |
| `startDate` | String | 否 | `yyyy-MM-dd`，按 `finish_time` 起（含当天） |
| `endDate` | String | 否 | `yyyy-MM-dd`，按 `finish_time` 止（含当天） |
| `pageNum` | Integer | 否 | 默认 1 |
| `pageSize` | Integer | 否 | 默认 10，上限沿用 `PageQuery` |

服务端固定条件：`status IN (COMPLETED, REVIEWED)`，`deleted = 0`，排序 `finish_time DESC, id DESC`。

响应 `PageResult<SettlementOrderVO>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 订单 ID |
| `orderNo` | String | 订单号 |
| `familyName` | String | 家属姓名（脱敏） |
| `elderName` | String | 老人姓名（脱敏） |
| `companionName` | String | 陪诊员姓名（脱敏，可为空） |
| `hospital` / `department` | String | 医院 / 科室 |
| `visitTime` | String | 就诊时间 `yyyy-MM-dd HH:mm:ss` |
| `finishTime` | String | 服务完成时间，可空 |
| `fee` | BigDecimal | 服务费（只读） |
| `settleAmount` | BigDecimal | **服务端计算**：`actual_fee` 非空取 `actual_fee`，否则取 `fee`；两位小数 |
| `paymentStatus` / `paymentStatusLabel` | String | `UNPAID/未结算`、`SETTLED/已结算` |
| `status` / `statusLabel` | String | `COMPLETED/已完成`、`REVIEWED/已评价` |

### 4.2 `POST /api/admin/settlement/{orderId}/confirm` · 单条核定

权限：**ADMIN**。请求体：无。

前置校验与结果：

| 场景 | 结果 |
|---|---|
| 订单不存在 | `3001` |
| 订单状态不在 `COMPLETED/REVIEWED` | `8004` |
| 已是 `SETTLED` | 200，`settledNow=false`，**不写日志** |
| `UNPAID` 且状态合法 | 200，`settledNow=true`，条件更新 + 写日志 |

事务内动作：

1. `UPDATE companion_order SET payment_status='SETTLED', actual_fee=COALESCE(actual_fee, fee) WHERE id=? AND payment_status='UNPAID' AND status IN ('COMPLETED','REVIEWED') AND deleted=0`
2. 影响行数 1 → 成功；0 → 回查区分「已结算/非法状态/不存在」
3. `operLogRecorder.record(SETTLE_ORDER, ORDER, orderId, orderNo, "UNPAID", "SETTLED", "结算核定，服务费 X 元，结算金额 Y 元")`

响应：

```json
{ "orderId": 1001, "orderNo": "NL20260915000001",
  "paymentStatus": "SETTLED", "paymentStatusLabel": "已结算",
  "settleAmount": 120.00, "settledNow": true }
```

### 4.3 `POST /api/admin/settlement/batch-confirm` · 批量核定

权限：**ADMIN**。请求体：

```json
{ "orderIds": [1001, 1002, 1003] }
```

校验：非空、`1..100` 条；为空或超限 → `400`；重复 ID 去重并保留首次出现顺序。

响应：

```json
{ "total": 3, "settledCount": 2, "alreadySettledCount": 1,
  "failedCount": 0,
  "items": [
    { "orderId": 1001, "orderNo": "NL...0001", "success": true,
      "settledNow": true, "code": 200, "message": "已核定", "settleAmount": 120.00 },
    { "orderId": 1002, "orderNo": "NL...0002", "success": true,
      "settledNow": false, "code": 200, "message": "该单已结算", "settleAmount": 80.00 },
    { "orderId": 1003, "orderNo": null, "success": false,
      "settledNow": false, "code": 8004, "message": "当前订单状态不可结算核对", "settleAmount": null }
  ] }
```

- 逐项独立成败，业务失败不中断其余项
- 数据库异常 → 整批回滚并走全局异常处理（`500`）
- 每单真正变更时写一条独立日志

### 4.4 `GET /api/settlement/export` · 导出结算单

权限：**ADMIN**（新增 `SecurityConfig` 路径规则 + 控制器类级 `@PreAuthorize`）

- 入参与 4.1 完全一致（不含分页），同样固定 `COMPLETED/REVIEWED`
- 返回 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 二进制流，不走 `Result`
- 超出行数上限 → `9001`
- 列定义（12 列，全部服务端生成）：订单号 / 家属姓名 / 老人姓名 / 陪诊员姓名 / 医院 / 科室 / 就诊时间 / 服务完成时间 / 服务费(元) / 结算金额(元) / 结算状态 / 订单状态
- 金额 `setScale(2, HALF_UP).toPlainString()`；姓名 `MaskUtil.name()`；不含手机号、身份证号

---

## 五、后端实现清单（文件级）

### 新建

| 文件 | 关键内容 |
|---|---|
| `backend/src/main/java/org/company/nianglin/dto/AdminSettlementQuery.java` | `extends PageQuery<CompanionOrder>`；`paymentStatus/keyword/startDate/endDate`；`toMpPage()` |
| `backend/src/main/java/org/company/nianglin/dto/SettlementBatchConfirmDTO.java` | `@NotEmpty @Size(max=100) List<Long> orderIds` |
| `backend/src/main/java/org/company/nianglin/vo/SettlementOrderVO.java` | 4.1 字段，`@Schema` 全量描述 |
| `backend/src/main/java/org/company/nianglin/vo/SettlementConfirmVO.java` | 4.2 响应 |
| `backend/src/main/java/org/company/nianglin/vo/SettlementBatchConfirmVO.java` | 汇总 + `List<SettlementBatchItemVO>` |
| `backend/src/main/java/org/company/nianglin/vo/SettlementBatchItemVO.java` | 单条结果 |
| `backend/src/main/java/org/company/nianglin/vo/SettlementExportVO.java` | `@ExcelProperty(index=0..11)` + `@ColumnWidth` |
| `backend/src/main/java/org/company/nianglin/service/SettlementService.java` | `list / confirm / batchConfirm / export` |
| `backend/src/main/java/org/company/nianglin/service/impl/SettlementServiceImpl.java` | 核心实现；`@Transactional`；复用 `OperLogRecorder`、`ExportService` |
| `backend/src/main/java/org/company/nianglin/controller/settlement/SettlementExportController.java` | `@RequestMapping("/api/settlement")` + 类级 ADMIN + `GET /export` |

### 修改

| 文件 | 改动 |
|---|---|
| `controller/admin/AdminController.java` | 注入 `SettlementService`；新增 3 个端点（§19/§20/§21），`@Operation` 写清幂等与 8004 |
| `constant/OperType.java` | 新增 `SETTLE_ORDER("订单结算核定")` |
| `common/ResultCode.java` | 新增 `SETTLEMENT_STATUS_ILLEGAL(8004, "当前订单状态不可结算核对")` |
| `config/SecurityConfig.java` | 在 `/api/admin/**` 旁新增 `/api/settlement/**` → `hasRole(ADMIN)` |
| `docs/api/08-admin.md` | §一 操作类型表 + §二 接口列表 + 新增 §19/§20/§21 |
| `docs/api/09-statistics-export.md` | §二 接口列表 + 新增 §9 导出结算单 |
| `docs/api/README.md` | 8xxx 错误码表补 `8004` |
| `docs/db/*` | 运行既有生成脚本重新生成，确认 `payment_status` / `actual_fee` 说明同步 |

### 实现要点（写代码时的硬约束）

- `status`、`paymentStatus` 一律走 `OrderStatus` / `PaymentStatus` 枚举，不硬编码字符串
- 条件更新用 `LambdaUpdateWrapper` + `setSql("actual_fee = COALESCE(actual_fee, fee)")`；不接收前端金额
- 0 行时回查订单再判定，避免把「已结算」误报成 `8004`
- 批量方法自身 `@Transactional`，内部逐项 `try/catch BusinessException`；DB 异常向上抛
- 日志 `beforeStatus=UNPAID`、`afterStatus=SETTLED`、`targetDesc=orderNo`
- 所有 VO 的 javadoc 引用 `docs/api/08-admin.md §19~§21` / `09 §9`

---

## 六、前端实现清单

| 文件 | 改动 |
|---|---|
| `frontend/src/api/admin.js` | `listSettlement(params)`、`confirmSettlement(orderId)`、`batchConfirmSettlement(data)` |
| `frontend/src/api/statistics.js` | `exportSettlement(params)`（`responseType: 'blob'`，复用既有下载范式） |
| `frontend/src/views/admin/settlement.vue` | 新增页面：筛选（结算状态/日期区间/关键字）、表格、勾选、单条核定、批量核定、导出 |
| `frontend/src/router/routes.js` | 新增 `/admin/settlement`，`meta: { title: '结算核对', roles: ['ADMIN'], module: ... }` |
| `frontend/src/api/index.js` | 若已有统一出口则同步导出新函数 |

页面规则：

- 文案只允许「结算核对 / 记账 / 核定 / 未结算 / 已结算 / 导出结算单」
- 禁止出现「提现 / 支付 / 打款 / 到账」
- 核定前二次确认对话框明确「金额由服务端按记账口径计算，核定后不可撤销」
- 批量核定展示逐项结果（成功/已结算/失败及原因）
- 空态、loading、按钮禁用与既有管理页一致；`ElMessage` 统一从 `element-plus` 引入
- 仅 ADMIN 可见（路由 `roles` + 既有守卫，不另写权限判断）

---

## 七、测试计划

### 后端单测 `SettlementServiceImplTest`

| # | 用例 | 断言 |
|---|---|---|
| 1 | 未结算 + `COMPLETED` → 核定 | `payment_status=SETTLED`、`actual_fee` 快照、`settledNow=true` |
| 2 | 重复核定 | `settledNow=false`、`OperLogRecorder.record` 仅调用 1 次 |
| 3 | `PENDING` 订单核定 | 抛 `8004`，不更新、不写日志 |
| 4 | 订单不存在 | 抛 `3001` |
| 5 | 批量混合（成功/已结算/非法） | 逐项结果正确、汇总计数正确、只对成功项写日志 |
| 6 | 批量空列表 / 超过 100 | `400` |
| 7 | `actual_fee` 已有值 | 保留原值，不被 `fee` 覆盖 |
| 8 | 导出超限 | `9001` |
| 9 | 导出金额与姓名 | 金额两位小数、姓名为脱敏串 |

### 越权测试

`SettlementSecurityTest`（MockMvc）：非 ADMIN 调三个管理端点 + 导出，均 **403**，且请求体可为空（证明在参数解析前被拦，避免「假绿」）。

### e2e `backend/sql/tools/e2e_settlement.py`

1. ADMIN 登录，挑一条 `COMPLETED` 且 `UNPAID` 的订单
2. 记下核定前 `payment_status` / `actual_fee`
3. 第一次核定 → 断言 200、`settledNow=true`、DB 状态与金额
4. 第二次核定 → 断言 200、`settledNow=false`、`admin_oper_log` 只多 1 行
5. 非 ADMIN 调用 → 403
6. 导出 → 断言响应为 xlsx 且行数/列头正确
7. **teardown 还原**：恢复 `payment_status` / `actual_fee`，删除本轮新增的 `admin_oper_log`

### 门禁

- `mvn -B verify`（含 JaCoCo）
- `pnpm run lint:check` + `pnpm run test`
- `python tools/check_secrets.py`
- 新增文件禁用词扫描：`提现|打款|到账|支付`

---

## 八、验收判据

- [ ] 三条接口与导出契约已先写入 `docs/api`，Knife4j 注解齐全
- [ ] `payment_status` 有且仅有本模块一个 `SETTLED` 入口
- [ ] 重复核定不产生第二条日志；批量逐项幂等
- [ ] `CANCELLED/PENDING` 不能结算，返回 `8004`
- [ ] 无新增迁移；`V1`–`V8` 未被修改
- [ ] 导出金额全部服务端计算，接口无金额入参
- [ ] 管理端页面仅 ADMIN 可见，文案无禁用词
- [ ] 单测覆盖 9 项 + 越权 403；e2e 完整核定 + 重复核定
- [ ] `mvn verify`、前端 lint/test 全绿，覆盖率报告含 `SettlementServiceImpl`
- [ ] 单独提交、不 push

---

## 九、风险与回滚

| 风险 | 应对 |
|---|---|
| e2e 改库残留 | 脚本 teardown 显式还原 `payment_status` / `actual_fee` 并删除本轮日志；异常退出也走 `finally` |
| 并发两名管理员核定同一单 | 条件更新 `payment_status='UNPAID'` 保证只有一个成功，另一个得到 `settledNow=false` |
| 导出数据量大 | 复用既有行数上限与 `9001`，不新造分页导出 |
| 文档漂移 | 接口先文档后代码；错误码同步 `ResultCode` 与 `docs/api/README.md` |
| 回滚 | 纯代码特性，无迁移；回滚即 `git revert` 本提交，数据无结构性变化 |

---

## 十、待确认（默认取本规格，用户可按需否决）

1. 「周期」按 `finish_time`（服务完成时间）—— 若希望按下单时间，请在审批时说明。
2. 核定后不可撤销 —— 若需要撤销/冲正，另立工单，不并入本项。
