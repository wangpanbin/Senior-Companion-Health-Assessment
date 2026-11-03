# V4 · P2-1 结算闭环 · 工单

> 配套规格：[V4-P2-1-settlement-spec.md](./V4-P2-1-settlement-spec.md)
> 执行纪律：串行、每项自测、每项单独提交、**不 push**；不得修改已应用的 `V1`–`V8`。

---

## T1 · 开分支

- `git switch -c feature/p2-settlement-loop`（从 `feature/v4-phase1` 切出）
- 验收：`git branch --show-current` = `feature/p2-settlement-loop`

## T2 · 接口先行（文档）

文件：

- `docs/api/08-admin.md`
  - §一「操作类型枚举」表补 `SETTLE_ORDER | 订单结算核定`
  - §二 接口列表补 3 行（GET `/api/admin/settlement`、POST `/{orderId}/confirm`、POST `/batch-confirm`）
  - 新增 §19 结算核对列表、§20 单条核定、§21 批量核定
- `docs/api/09-statistics-export.md`
  - §二 接口列表补 `GET /api/settlement/export`
  - 新增 §9 导出结算单（12 列 + 9001 + 权限）
- `docs/api/README.md`
  - 8xxx 表补 `8004 | 当前订单状态不可结算核对`

验收：章节号连续、契约含幂等与错误码、无禁用词。

## T3 · 后端常量与鉴权底座

文件：

- `constant/OperType.java` → `SETTLE_ORDER("订单结算核定")`
- `common/ResultCode.java` → `SETTLEMENT_STATUS_ILLEGAL(8004, "当前订单状态不可结算核对")`
- `config/SecurityConfig.java` → `/api/settlement/**` 与 `/api/admin/**` 同级 `hasRole(ADMIN)`

验收：`mvn -q -DskipTests compile` 通过；枚举 `of/labelOf` 无需改动即可用。

## T4 · DTO / VO

文件：

- `dto/AdminSettlementQuery.java`（`paymentStatus/keyword/startDate/endDate` + `toMpPage()`）
- `dto/SettlementBatchConfirmDTO.java`（`1..100`、去重保序在 Service 层做）
- `vo/SettlementOrderVO.java`
- `vo/SettlementConfirmVO.java`
- `vo/SettlementBatchConfirmVO.java`
- `vo/SettlementBatchItemVO.java`
- `vo/SettlementExportVO.java`（12 列 `@ExcelProperty(index=…)`）

验收：字段与规格 §4 完全一致；所有字段带 `@Schema`/javadoc。

## T5 · SettlementService 实现

文件：

- `service/SettlementService.java`
- `service/impl/SettlementServiceImpl.java`

实现要点：

- `list`：固定 `status IN (COMPLETED,REVIEWED)`；`paymentStatus` 可选；`finish_time` 区间；`finish_time DESC, id DESC`；姓名 `MaskUtil.name()`
- `confirm`：条件更新（`payment_status='UNPAID'` + 合法状态）→ 0 行回查分流 `3001/8004/已结算`；`actual_fee=COALESCE(actual_fee, fee)`；写一条日志
- `batchConfirm`：`@Transactional`；去重保序；逐项 `try/catch BusinessException`；汇总计数
- `export`：复用行数上限配置与 `ExportService.writeExcel`，超限 `9001`

验收：`mvn -q -DskipTests compile`；单测见 T7。

## T6 · 控制器

文件：

- `controller/admin/AdminController.java`（3 个端点，`@Operation` 写明幂等与 `8004`）
- `controller/settlement/SettlementExportController.java`（`GET /api/settlement/export`，类级 ADMIN，`void` 直写响应）

验收：Knife4j 可见 4 个新端点；导出返回二进制而非 `Result`。

## T7 · 后端单测 + 越权测试

文件：

- `src/test/java/org/company/nianglin/service/SettlementServiceImplTest.java`（规格 §7 的 9 项）
- `src/test/java/org/company/nianglin/controller/SettlementSecurityTest.java`（非 ADMIN → 403）

验收：`mvn -B verify` 全绿；JaCoCo 报告包含 `SettlementServiceImpl`。

## T8 · e2e 脚本

文件：

- `backend/sql/tools/e2e_settlement.py`
- `backend/sql/tools/README.md`（补脚本说明：作用/是否写库/是否需口令）

流程：ADMIN 登录 → 首次核定 → 重复核定 → 非 ADMIN 403 → 导出 → **finally 还原数据并删除本轮日志**。

验收：脚本退出码 0，输出逐项 PASS；二次执行也通过（幂等可重复）。

## T9 · 前端

文件：

- `frontend/src/api/admin.js`（`listSettlement` / `confirmSettlement` / `batchConfirmSettlement`）
- `frontend/src/api/statistics.js`（`exportSettlement`，blob 下载）
- `frontend/src/views/admin/settlement.vue`
- `frontend/src/router/routes.js`（`/admin/settlement`，ADMIN）
- `frontend/src/api/index.js`（如有统一出口）

验收：`pnpm run lint:check` 0 警告；`pnpm run test` 全绿；页面文案无禁用词；非 ADMIN 不显示入口。

## T10 · 文档收尾与全量验证

- 运行 `docs/db` 生成脚本，确认 `companion_order` 字段说明同步
- `mvn -B verify`
- `pnpm run lint:check && pnpm run test`
- `python tools/check_secrets.py`
- 禁用词扫描：新增文件 `提现|打款|到账|支付` 零命中
- 提交（单独一次），**不 push**

验收：规格 §8 全部勾选；回复中给出命令、结果、覆盖率与红线自检。
