# P2-3 任务清单（执行完成）

> 分支：`feature/p2-health` · 不 push · 按项单独提交。
> 执行结果：全部完成；验证证据见 `spec.md §6` 与最终回复。

## A · 老人端「最近一次数值」

- [x] A1 `frontend/src/views/elder/health.vue`：新增 `latestReading` computed（趋势 points 末项，拆分 value/suffix）。
- [x] A2 在 tab 与趋势图之间渲染 `vital-latest` 大字卡片（标签 + 数值 + 单位 + 时间）。
- [x] A3 scoped 样式：40px 数字、主色、正文/标题令牌字号；不新增评估文案。
- [x] A4 自检：绘图区与轴标签未改；卡片仅 `latestReading` 非空时渲染；空态与骨架屏分支不变；窄屏 flex 纵向布局不溢出。
- [x] A5 提交：`feat(elder-health): 体征最近一次读数卡片化展示`。

## B · 陪诊员执行页快捷入口

- [x] B1 `frontend/src/views/companion/execute.vue`：给体征卡片加 `id="order-vitals"`。
- [x] B2 订单摘要卡片内新增 `canReadVitals` 条件下的「↓ 本单体征记录」按钮。
- [x] B3 实现 `jumpToVitals()`（`scrollIntoView`，元素缺失静默返回）。
- [x] B4 确认板块顺序、节点叙事、既有按钮不变（仅摘要卡片末尾追加一个 link 按钮）。
- [x] B5 提交：`feat(companion-execute): 顶部增加本单体征记录快捷入口`。

## C · sys_dict 运营参数（后端）

- [x] C1 新增 `constant/OperationParam.java`（4 项 code/label/unit/min/max/description）。
- [x] C2 新增 `service/support/OperationParamCache.java`（TTL 快照 + 可注入时钟）。
- [x] C3 新增 `service/OperationParamService.java` + `impl/OperationParamServiceImpl.java`（懒加载、回落、列表、更新）。
- [x] C4 新增 `dto/OperationParamUpdateDTO.java`、`vo/OperationParamVO.java`。
- [x] C5 新增 `controller/admin/OperationParamController.java`（`/api/admin/operation-param`：GET 列表 / PUT `{code}` / GET logs，类级 ADMIN）。
- [x] C6 `constant/OperType.java` 加 `UPDATE_OPERATION_PARAM`；`constant/OperTargetType.java` 加 `OPERATION_PARAM`。
- [x] C7 `entity/AdminOperLog.java`、`entity/SysDict.java` 更新 `@Schema` 注释。
- [x] C8 `backend/sql/V10__operation_param.sql` 写入 4 行 `OPS_PARAM` 种子。
- [x] C9 `application.yml` 增加 `nianglin.operation-param.cache-ttl-ms: 60000` 与说明注释。
- [x] C10 `MedicationServiceImpl` / `FulfillmentServiceImpl` / `OrderServiceImpl` / `StatisticsServiceImpl` / `SettlementServiceImpl` / `SmsReminderService` 改为读取 `OperationParamService`。
- [x] C11 更新受影响的构造函数调用点与既有单测（可选注入，既有手工 Mock 构造不受影响）。
- [x] C12 提交：`feat(admin): 运营参数接入 sys_dict 并支持热生效`。

## D · 管理端参数页（前端）

- [x] D1 `frontend/src/api/admin.js` 新增 3 个函数。
- [x] D2 新增 `frontend/src/views/admin/operation-param.vue`（列表 + 编辑 + 留痕）。
- [x] D3 `frontend/src/router/routes.js` 新增 `/admin/operation-param`（`W-13` / `Setting`）。
- [x] D4 与既有管理页风格核对（`NlCard plain` / `NlAdminTable` / `NlSkeleton` / `NlEmpty` / 弹窗表单）。
- [x] D5 提交：`feat(admin): 新增运营参数配置页`。

## E · 测试

- [x] E1 `OperationParamCacheTest`：命中 / TTL / invalidate / 跳过空值。
- [x] E2 `OperationParamServiceTest`：命中只查一次、更新后失效、DB 异常回落、值非法/越界回落、列表失败回落。
- [x] E3 留痕断言：`UPDATE_OPERATION_PARAM` + `target_desc` + before/after + operator。
- [x] E4 `AdminAccessMatrixTest` 补：ADMIN 200；FAMILY/ELDER/COMPANION/未登录 403/401；越界 PUT → 业务码 400。
- [x] E5 提交：`test(admin): 覆盖运营参数缓存、越权、留痕与默认回落`。

## F · 文档与验收

- [x] F1 `docs/api/08-admin.md` 新增 §28（端点、权限表、操作类型枚举、缓存与生效说明、留痕字段）。
- [x] F2 `docs/api/README.md` 权限矩阵补一行并链到 §28。
- [x] F3 `python backend/sql/tools/gen_db_docs.py` 后核对 `docs/db` diff（无差异）。
- [x] F4 `mvn -B verify` 全绿（1516 tests，0 failures，0 errors；coverage check 通过）。
- [x] F5 `pnpm run lint:check`、`pnpm run test` 全绿（23 files / 251 tests）。
- [x] F6 `python tools/check_secrets.py` 五条全过（扫描 1090 个入库文件）。
- [x] F7 未新增 e2e 用例；`python tools/e2e/check_api_imports.py` 通过（127 处具名导入）。既有套件需 MySQL/Redis + 8080/5141 常驻服务，本次未拉起，故未复跑。
- [x] F8 提交：`docs(api): 补运营参数配置章节与数据字典`。

## 最终回复模板

- 改动清单（文件级）
- 字典项清单（键 / 默认值 / 生效方式）
- 缓存策略与失效时机
- 验证证据（命令 + 结果 + 覆盖率）
- 剩余风险
