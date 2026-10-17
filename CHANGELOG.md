# Changelog

本文件记录每个版本的变更。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号对齐 `迭代计划.md` 的 Vx.y 里程碑（不是 Git 标签）。

> 与 `docs/api/README.md §变更记录` 的分工：那边只记**接口契约**的变更（端点、错误码、字段），
> 本文件记**全部**变更，包括内部重构、测试与文档。两者可交叉引用，但本文件不替代接口变更记录。

---

## [V3.1] · 2026-10-17 · 履约保障中心

把「下了单一直没人接」这段空白期变成**有反馈、有节奏、有人兜底**的时间。
产品与工程契约见 [PRD](docs/prd/PRD-V3.1-履约保障中心.md) /
[spec](docs/spec/V3.1-fulfillment-spec.md) / [tickets](docs/spec/V3.1-tickets.md)。

### Added · 新增

**陪诊员接单状态自助控制（T0）**

- `WorkStatus` 增加 `OFFLINE`，与 `REST` 区分「主动暂停」与「被动离线」
- `PUT /api/user/companion/work-status`：只写 `work_status` 一列，**不触碰在手订单**
- 陪诊员大厅新增接单状态开关（移动 + 桌面两个形态）

**履约风险模型与扫描（T1 / T2）**

- `companion_order` 增三列：`fulfillment_risk` / `urge_count` / `last_urge_at`（`V6__order_fulfillment.sql`，`information_schema` 守卫幂等）
- 新表 `order_fulfillment_event`：履约事件流水，只增不改
- 索引 `idx_risk_status_time (fulfillment_risk, status, create_time)`
- `FulfillmentRisk` / `FulfillmentEventType` / `TriggerSource` 枚举
- `FulfillmentRiskEvaluator`：**唯一测试接缝**，纯函数、无 Spring、无时钟依赖
- `FulfillmentProperties`：阈值配置化（`nianglin.fulfillment.*`），启动期校验严格递增
- `FulfillmentScheduler`：5 分钟一次，Redis 锁 TTL 4min，异常自接住

**家属催单（T3）**

- `POST /api/order/{id}/urge`：冷却 10 分钟、累计上限 3 次（均配置化）
- 资格判定与计数自增**合并为同一条条件更新**，不做「先查后写」

**履约读口径（T4 / T5）**

- `OrderVO` 五个只读字段：`fulfillmentRisk` / `fulfillmentRiskLabel` / `waitMinutes` / `urgeCount` / `canUrge`
- `GET /api/order/{id}/fulfillment`：风险等级 + 事件流水（升序）
- `TimeUtil.elapsedMinutes`：等待时长的**唯一钳制规则**，扫描 / 出参 / 详情三方共用
- 家属端风险状态条 + 订单列表轻量标记
- `frontend/src/utils/fulfillment.js`：等级 → 视觉的**唯一映射表**

**管理员风险中心与改派（T6 / T7）**

- `GET /api/admin/fulfillment/summary`：四档计数，恒定全返，为 0 时返回 0
- `GET /api/admin/fulfillment/risk-list`：固定「等级降序 → 等待时长降序 → id 降序」，姓名与订单号脱敏
- `POST /api/admin/order/{id}/reassign`：仅处理未接单订单，**不改变 `status`**
- 管理端新页 `履约风险`（W-10）：四档计数卡 + 等级筛选 + 列表 + 详情 / 改派弹窗
- `OperType.REASSIGN_ORDER` 操作审计类型

**大厅排序（T8）**

- `GET /api/order/hall` 新增可选参数 `sortBy`，白名单只放行 `RECOMMEND`，非法值 `400` 不静默降级
- 推荐序为三级稳定排序：`visit_time ASC` → 区域匹配优先 → `id ASC`

### Changed · 变更

- `MessageTemplateUtil`：新增 `ORDER_REASSIGNED` 模板（三类接收方靠 `audience` 占位符区分措辞）
- `backend/sql/tools/gen_entity.py`：新增 `parse_alters()`，修复增量列被静默丢弃的问题（顺手修复，超出本版范围但已验证）
- `OrderVO.ofList` / `ofHall` / `ofDetail` 三套口径共用 `base()` 装配，非 `PENDING` 时履约字段统一收敛

### Fixed · 修复

- `AdminArbitrateTest` 中一处既有损坏字符

### 文档

- ADR-0012 履约风险与订单状态分离 · ADR-0013 大厅单一推荐序
- `docs/prd/PRD-V3.1-履约保障中心.md`（独立版本 PRD，遵守 ADR-0006 版本锁，不动 `PRD.md`）
- API 契约：`02-elder-family.md` §13 · `03-order.md` §11/§12/§3 · `08-admin.md` §14–§16 · `07-message.md` 类型表补全 · `README.md` 错误码 `3008`/`3009`/`3010`
- 数据字典：`companion_order` 三列 + `order_fulfillment_event` 表

### 测试

- 后端新增 **174 条**用例（881 / 881 全绿）
- 前端新增 **16 条**单元测试（89 / 89 全绿）
- 反向验证：改坏阈值 → 2 条测试变红；删除状态机转移边 → 状态流转单测变红

### Compliance · 合规

- 催单备注过 `ComplianceCheckUtil` 词表（与服务小结 `3007` 同源）
- 新增文案无「建议服用 / 剂量 / 诊断」类表述
- 风险列表姓名与订单号脱敏；详情的全名豁免仅对已过归属校验的详情生效
