# P2-3 规格：老人端读数强化 · 陪诊员快捷入口 · 运营参数可调化

> 状态：**已确认并执行完成**（Spec Mode），执行记录见 `tasks.md`。
> 分支：`feature/p2-health`（不新建分支、不 push；按项单独提交）。

## 0 · 目标与非目标

### 目标

1. 老人端「我的体征」在保持 12.5px 轴标签、不压缩绘图区的前提下，让**最近一次数值**以卡片化大字呈现，趋势缩略与关键读数同时可见。
2. 陪诊员订单执行页顶部提供跳到「本单体征记录」的快捷入口，不重排既有板块顺序。
3. 接通 `sys_dict` 做运营参数可调化：ADMIN 读写端点、带缓存、变更留痕、热生效、读取失败回落 `application.yml` 默认值。

### 非目标（明确不做）

- 不改趋势图绘图区尺寸、轴标签字号（维持 `--nl-font-micro`，老人模式 12.5px）。
- 不新增任何评估 / 判定 / 建议类文案（合规红线-1）。
- 不把连接串、密钥、JWT、限流结构性开关、调度开关等搬进字典。
- 不扩大到本规格未列出的配置项（仅 4 项，见 §3.3）。
- 不重构管理端既有页面，不改变 `admin_oper_log` 既有写入链路。

## 1 · 老人端「最近一次数值」卡片化

文件：`frontend/src/views/elder/health.vue`

- 新增 computed `latestReading`：取 `trend.value.points` 末项（后端趋势按 `measuredAt` 升序返回，末项即最近一次）；按第一个空格拆分 `valueText` 为 `{ value, suffix }`（如 `126/76 mmHg` → `126/76` + `mmHg`）。
- 在体征类型 tab 与 `VitalTrendChart` 之间渲染 `vital-latest` 区块，仅当 `latestReading` 非空时显示：
  - 标签：`最近一次 · {{ trend.vitalTypeLabel }}`
  - 数值：大号 `is-num`（`font-size: 40px`，`var(--nl-font-num)`，`var(--nl-primary)`）
  - 单位：`suffix`
  - 时间：复用 `eventTimeText(measuredAt)`
- 不新增按钮、不新增说明文案；趋势图、记录列表、空态保持原样。
- 样式只落在本文件 scoped style；不修改 `VitalTrendChart.vue` / `VitalTimeline.vue`。

## 2 · 陪诊员执行页快捷入口

文件：`frontend/src/views/companion/execute.vue`

- 给「本单体征记录」`NlCard` 增加 `id="order-vitals"`（`NlCard` 未关闭 attrs 继承，`id` 会落到根 `section`）。
- 在订单摘要卡片内新增按钮（仅 `canReadVitals` 为真时显示）：
  ```html
  <el-button v-if="canReadVitals" link type="primary" @click="jumpToVitals">
    ↓ 本单体征记录
  </el-button>
  ```
- `jumpToVitals()`：`document.getElementById('order-vitals')?.scrollIntoView({ behavior: 'smooth', block: 'start' })`；元素不存在时静默返回。
- 板块顺序、节点打卡叙事、既有按钮文案均不改动。

## 3 · 运营参数可调化（接通 sys_dict）

### 3.1 数据模型

沿用现有 `sys_dict`，新增一个字典类型，不改表结构：

| 字段 | 取值 |
|---|---|
| `dict_type` | `OPS_PARAM` |
| `dict_code` | 参数键（见 §3.3） |
| `dict_label` | 中文名 |
| `dict_value` | 当前生效值（字符串存数字） |
| `sort_no` | 1–4 |
| `status` | `1`（启用）；`0` 表示停用并回落默认值 |
| `remark` | 参数说明 + 可调范围 |

迁移脚本：`backend/sql/V10__operation_param.sql`（构建时由 pom 复制到 `classpath:db/migration`）。使用 `INSERT ... ON DUPLICATE KEY UPDATE` 兼容重复执行；只插 4 行，不改既有字典数据。

### 3.2 服务与缓存

新增：

- `constant/OperationParam.java`：枚举 4 个参数，含 `code/label/unit/min/max/description`。
- `service/support/OperationParamCache.java`：进程内 TTL 快照缓存。
  - `volatile Map<String,String> snapshot` + `volatile long loadedAtMillis`；
  - `long ttlMillis` 默认 60_000，来自 `nianglin.operation-param.cache-ttl-ms`；
  - `LongSupplier clock`（默认 `System::currentTimeMillis`）便于单测推进时间；
  - `get(code)` / `isFresh()` / `putAll(map)` / `invalidate()`。
- `service/OperationParamService.java` + `service/impl/OperationParamServiceImpl.java`：
  - `int getInt(OperationParam param)`：缓存命中直接返回；不新鲜则同步重载；DB 异常时保留上一份快照，无快照则用默认值；解析失败记 warn 并回落默认值；**任何情况都不抛异常**。
  - `List<OperationParamVO> list()`：读取 DB 行（含 `update_time`）与默认值合并；DB 异常时以默认值列出，状态标记为「读取失败，使用默认值」。
  - `void update(OperationParamUpdateDTO dto)`：校验 → upsert → 写 `admin_oper_log` → `cache.invalidate()` 并尽力重载。
  - 默认值来源（不复制 YAML）：
    - `@Value("${nianglin.medication.missed-threshold-minutes:60}")`
    - `FulfillmentProperties#getUrgeCooldownMinutes()`
    - `@Value("${nianglin.export.max-rows:10000}")`
    - `SmsProperties#getFrequency()#getMissedDoseDailyCap()`
  - **启动不访问 DB**：懒加载，无 `@PostConstruct`；SQL 缺失或 DB 不可用时业务仍按默认值运行。

缓存策略与失效时机（必须写进代码注释与接口文档）：

| 场景 | 行为 |
|---|---|
| 命中 | TTL 内直接返回快照，不查库 |
| TTL 到期 | 下一次 `getInt` 同步重载一次（`synchronized` 防击穿） |
| 管理端修改成功 | 事务提交后立即 `invalidate()` + 重载 → 本实例下一次业务调用即生效 |
| 修改失败 / 回滚 | 不失效，保持旧快照 |
| DB 读取失败 | 保留上一份快照；无快照则使用 `application.yml` 默认值 |
| 多实例 | 其他实例最多 60s 后收敛（本期单实例部署） |

### 3.3 迁移的字典项（4 项）

| dict_code | 中文名 | 默认值 | 范围 | 单位 | 读取点 |
|---|---|---|---|---|---|
| `medication.missed-threshold-minutes` | 漏服判定阈值 | 60 | 1–1440 | 分钟 | `MedicationServiceImpl#scanMissed` |
| `fulfillment.urge-cooldown-minutes` | 催单最小间隔 | 10 | 1–1440 | 分钟 | `FulfillmentServiceImpl`、`OrderServiceImpl#urgeGate` |
| `export.max-rows` | 单次导出条数上限 | 10000 | 100–50000 | 行 | `StatisticsServiceImpl`、`SettlementServiceImpl` |
| `sms.frequency.missed-dose-daily-cap` | 漏服短信单用户日上限 | 3 | 1–100 | 条 | `SmsReminderService` |

生效方式：**读取点每次调用实时取缓存值**；管理端修改成功后本实例立即生效，无需重启或发版。导出的「单次」语义为每个导出请求读取一次。

**优先级（P2-3 硬约束）**：`sys_dict` 的 `OPS_PARAM` 行（`status=1`）**优先于**
`application.yml`、启动参数与环境变量。字典行有值且启用时，同名环境变量
（如 `NIANGLIN_SMS_FREQUENCY_MISSEDDOSEDAILYCAP`、`NIANGLIN_EXPORT_MAXROWS`）
**不再生效**；只有把该字典行 `status=0` 停用后，环境变量/启动参数才兜底。
排查配置不生效时，先查 `sys_dict` 再查配置文件；完整说明见
[`docs/api/08-admin.md` §28.4](../../api/08-admin.md)。

### 3.4 管理端接口（仅 ADMIN）

新增 `controller/admin/OperationParamController.java`，类级 `@PreAuthorize("hasRole('ADMIN')")`：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/operation-param` | 参数列表：键、名称、当前值、默认值、范围、单位、是否被覆盖、最近更新时间、是否回落默认 |
| PUT | `/api/admin/operation-param/{code}` | 修改单个参数；body `{ value, remark }` |
| GET | `/api/admin/operation-param/logs` | 参数变更留痕（`operType=UPDATE_OPERATION_PARAM`，分页） |

- `GET /logs` 复用 `AdminOperLogMapper` + `OperLogQuery`（`operType` 固定），返回 `PageResult<OperLogVO>`；不改既有 `/api/admin/oper-log`。
- 修改校验：`code` 必须在枚举内；`value` 必须为整数且在 `[min,max]`；`remark` ≤ 200 字。非法抛 `BusinessException(ResultCode.PARAM_ERROR, 具体原因)`。
- 留痕字段：`oper_type=UPDATE_OPERATION_PARAM`、`target_type=OPERATION_PARAM`、`target_id=sys_dict 行 id`、`target_desc=dict_code`、`before_status=旧值`、`after_status=新值`、`remark=键 + 旧值 + 新值`；操作人由 `OperLogRecorder` 自动写入 `operator_id/operator_name`。
- 新增枚举：
  - `constant/OperType.java`：`UPDATE_OPERATION_PARAM("修改运营参数")`
  - `constant/OperTargetType.java`：`OPERATION_PARAM("运营参数")`
  - 同步更新 `entity/AdminOperLog.java`、`entity/SysDict.java` 的 `@Schema` 注释。

### 3.5 读取点改造（热生效）

- `MedicationServiceImpl`：移除 `@Value` 字段，`scanMissed` 与日志改为 `operationParamService.getInt(MEDICATION_MISSED_THRESHOLD_MINUTES)`。
- `FulfillmentServiceImpl`：3 处 `properties.getUrgeCooldownMinutes()` → `operationParamService.getInt(URGE_COOLDOWN_MINUTES)`。
- `OrderServiceImpl`：`urgeGate` 的 cooldown → 同上；`urgeMaxTimes` 保持不变（不在本次迁移范围）。
- `StatisticsServiceImpl` / `SettlementServiceImpl`：`maxExportRows` → `operationParamService.getInt(EXPORT_MAX_ROWS)`，每个导出请求读取一次。
- `SmsReminderService`：2 处 `properties.getFrequency().getMissedDoseDailyCap()` → `operationParamService.getInt(SMS_MISSED_DOSE_DAILY_CAP)`；其余短信配置不变。

### 3.6 管理端页面

- 新增 `frontend/src/views/admin/operation-param.vue`：
  - 参数表格：名称、键、当前值、默认值、可调范围、最近更新、操作「编辑」。
  - 编辑弹窗：数字输入 + 备注；提交后刷新列表与留痕。
  - 留痕区：表格展示 `操作人 / 时间 / 键 / 旧值 / 新值 / 备注`，数据来自 `GET /api/admin/operation-param/logs`。
  - 风格复用 `views/admin` 既有表格 / 弹窗 / 空态写法，不新造组件。
- `frontend/src/api/admin.js` 新增：`getOperationParams()`、`updateOperationParam(code, data)`、`listOperationParamLogs(params)`。
- `frontend/src/router/routes.js`：新增 `/admin/operation-param`，`meta.title='参数配置'`、`icon='Setting'`、`module='M9'`、`code='W-13'`。
- 不新增 e2e 场景；既有 e2e 全量复跑确认无残留。

## 4 · 逐文件改动清单

### 新增（后端）

- `backend/src/main/java/org/company/nianglin/constant/OperationParam.java`
- `backend/src/main/java/org/company/nianglin/service/OperationParamService.java`
- `backend/src/main/java/org/company/nianglin/service/impl/OperationParamServiceImpl.java`
- `backend/src/main/java/org/company/nianglin/service/support/OperationParamCache.java`
- `backend/src/main/java/org/company/nianglin/controller/admin/OperationParamController.java`
- `backend/src/main/java/org/company/nianglin/dto/OperationParamUpdateDTO.java`
- `backend/src/main/java/org/company/nianglin/vo/OperationParamVO.java`
- `backend/sql/V10__operation_param.sql`

### 修改（后端）

- `constant/OperType.java`、`constant/OperTargetType.java`
- `entity/AdminOperLog.java`、`entity/SysDict.java`（仅注释）
- `service/impl/MedicationServiceImpl.java`
- `service/impl/FulfillmentServiceImpl.java`
- `service/impl/OrderServiceImpl.java`
- `service/impl/StatisticsServiceImpl.java`
- `service/impl/SettlementServiceImpl.java`
- `service/sms/SmsReminderService.java`
- `src/main/resources/application.yml`（新增 `nianglin.operation-param.cache-ttl-ms: 60000` 及注释；不放业务默认值副本）

### 新增（前端）

- `frontend/src/views/admin/operation-param.vue`

### 修改（前端）

- `frontend/src/views/elder/health.vue`
- `frontend/src/views/companion/execute.vue`
- `frontend/src/api/admin.js`
- `frontend/src/router/routes.js`

### 文档

- `docs/api/08-admin.md`：新增 §14「运营参数配置」，补权限表一行、操作类型枚举新增值。
- `docs/api/README.md`：如存在接口索引，补一行（实现时核对）。
- `docs/db/`：运行 `python backend/sql/tools/gen_db_docs.py` 后核对 diff。

## 5 · 测试计划（必须覆盖）

- `OperationParamCacheTest`：命中不重载；TTL 到期需重载；`invalidate` 后不命中。
- `OperationParamServiceTest`：
  - 缓存命中：连续两次 `getInt` 只查库一次；
  - 失效：`update` 后下一次读取拿到新值；
  - 回落：Mapper 抛异常 / 键缺失 / 值非数字 / 值越界 → 返回默认值，不抛异常；
  - 留痕：`update` 后写入 `UPDATE_OPERATION_PARAM`，`target_desc=code`，`before_status/after_status` 正确，操作人来自 `SecurityUtils`；
  - 越权：沿用 `AdminAccessMatrixTest` 的矩阵风格，新增 ADMIN 200、FAMILY/ELDER/COMPANION/未登录 403。
- 前端：`pnpm run lint:check` + `pnpm run test` 全量；不新增前端单测（本次前端改动为纯展示，既有测试覆盖回归）。

## 6 · 验证命令与证据

1. `mvn -B verify`（后端全量；需 MySQL/Redis）。
2. `frontend> pnpm run lint:check`
3. `frontend> pnpm run test`
4. `python tools/check_secrets.py`
5. `python backend/sql/tools/gen_db_docs.py`，`git diff -- docs/db` 核对。
6. e2e 既有套件复跑（若环境可用），`git status --short` 确认无残留文件。

## 7 · 剩余风险（写入最终回复）

- 多实例部署时缓存最长 60s 收敛；本期单实例。
- `sys_dict` 被扩展为「业务参数」用途；现有下拉字典（HOSPITAL/DEPARTMENT/...）不受影响，但后续新增类型需沿用 `dict_type` 隔离。
- 参数值下调可能使既有导出 / 催单行为立刻收紧，默认值与范围校验已限制极端值。
- 若 DB 不可用，参数读取静默回落默认值（按需求），但管理端会显示「读取失败，使用默认值」以便发现。

## 8 · 待确认事项

- [ ] 确认 4 个迁移项与默认值 / 范围（§3.3）。
- [ ] 确认缓存 TTL 60s 与单实例前提。
- [ ] 确认管理端页面 code 使用 `W-13`。
- [ ] 确认本规格不含「催单次数上限」（`urge-max-times`）与「漏服扫描批次」等未列出项。
</content>
