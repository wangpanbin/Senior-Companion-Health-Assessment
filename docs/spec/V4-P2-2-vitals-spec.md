# V4 · P2-2 健康档案结构化（体征记录）· 工程规格

> 状态：**待审批**（审批通过后才进入编码）
> 分支：`feature/p2-health`（从 `feature/p2-settlement-loop` 切出，单独提交、不 push）
> 依据：用户 P2-2 三步要求 + `docs/plan/iteration-plan-v4.md` §5.5 + `AGENTS.md` 红线
> 关联文档：`docs/api/12-elder-health.md`、`docs/api/README.md`、`docs/design/design.md`

---

## 一、背景与现状核实结论

| 核实项 | 结论 | 证据 |
|---|---|---|
| 最新迁移版本 | `V8__sms_notification.sql` 已应用；**V9 是下一个可用版本** | `backend/sql/` 目录 |
| 既有健康模块 | 家属 / 老人各两个只读端点（概览、时间线），无任何写接口 | [FamilyHealthController.java](../../backend/src/main/java/org/company/nianglin/controller/family/FamilyHealthController.java)、[ElderHealthController.java](../../backend/src/main/java/org/company/nianglin/controller/elder/ElderHealthController.java) |
| 既有时间线口径 | 四类事件（ORDER / CHECKIN / MEDICATION / MESSAGE），游标翻页，按 `occurredAt DESC, eventId ASC` | `docs/api/12-elder-health.md` §3 |
| 老人只读 | `ElderReadOnlyInterceptor` 对 ELDER 的写请求默认 403，只有 `@AllowElderWrite` 放行 | [ElderReadOnlyInterceptor.java](../../backend/src/main/java/org/company/nianglin/security/ElderReadOnlyInterceptor.java) |
| 老人档案归属 | `ElderService#requireAccessible` 放行 FAMILY / ELDER / ADMIN，**显式拒绝 COMPANION** | [ElderServiceImpl.java](../../backend/src/main/java/org/company/nianglin/service/impl/ElderServiceImpl.java) |
| 陪诊员订单归属 | `OrderService#requireInvolved` 放行相关方；各写方法再收紧到「本单陪诊员」 | [OrderServiceImpl.java](../../backend/src/main/java/org/company/nianglin/service/impl/OrderServiceImpl.java) |
| 计划书旧口径 | 计划书写 `POST /api/elder/vital-sign`，但老人端按本项目铁律只读；本规格改为「家属 / 陪诊员写，老人读」 | 本文 §三 D1 |
| 既有依赖面 | 前端已有内联 SVG / CSS 图表能力，不新增 ECharts 或其它依赖 | `package.json` 不变 |

### 1.1 关键现状冲突与处理

1. **陪诊员不能调用 `requireAccessible`**：它会对 COMPANION 抛 `2006`。P2-2 的陪诊员写入**不走档案归属校验**，而是走「订单归属 + 订单状态 + 订单自带 `elder_id`」；不修改 `requireAccessible` 的既有规则，也不因此给陪诊员开放档案查看权限。

---

## 二、范围与非目标

### 2.1 范围内

- 三类体征：**血压**（收缩压 / 舒张压）、**血糖**（数值 + 空腹 / 餐后 2 小时 / 随机）、**体重**。
- 两种录入来源：**家属代录**、**陪诊员服务时代录**。
- 老人端**只读**列表与趋势。
- 家属端健康中心内新增「体征记录」区块：录入卡、趋势图、时间线。
- 陪诊员订单执行页新增「服务中代录」卡。
- 新增 `V9__vital_sign_record.sql` 一张表。

### 2.2 明确不做

| 不做 | 原因 |
|---|---|
| 任何解释、分级、提示、提醒 | 合规红线；本模块只保存和展示测量数值 |
| 任何区间 / 目标值字段或展示 | 展示区间本身就构成对数值的解释 |
| 心率、血氧、体温等其它体征 | 用户要求「至少」三类，本期只做三类，避免扩大范围 |
| 自由文本备注 | 不提供任何可以写判断的入口 |
| 修改接口（PUT / PATCH） | 采用「撤回 + 重录」，历史留痕 |
| 并入既有健康时间线四类事件 | 避免改动既有游标口径；体征使用独立时间线 |
| 统计报表 / 指标字典新增指标 | 本期不做聚合统计 |
| 管理端体征查询 | 管理端不参与个人健康数据 |
| 新依赖 | 趋势图用内联 SVG |

---

## 三、关键决策

| # | 决策 | 理由 |
|---|---|---|
| D1 | 写入角色只有 FAMILY / COMPANION；ELDER 只读；ADMIN 不开放 | 老人账号为只读模式；家属与陪诊员是代录人 |
| D2 | 表名 `vital_sign_record`，迁移 `V9__vital_sign_record.sql` | 与实体 `VitalSignRecord` 对齐，符合计划书命名 |
| D3 | 三类体征共用一张表，按 `vital_type` 区分，数值列可空 | 一套时间线、一套趋势；避免三张表三套分页 |
| D4 | 去重键 = `elder_id + vital_type + measured_at(分钟)`；有效记录唯一 | 同一人同一分钟同类测量只应有一条；撤回后释放槽位 |
| D5 | `measured_at` 落库前截断到分钟（秒固定 `00`） | 去重键稳定；前端选择器只到分钟 |
| D6 | 不提供编辑；家属可撤回**本人代录**，陪诊员可撤回**本人在服务中**的代录 | 更正通过「撤回 + 重录」完成；服务结束后留痕锁定 |
| D7 | 陪诊员写入必须满足：订单存在、`companion_id = 当前用户`、`status = IN_SERVICE` | 落实「仅限自己在服务中的订单关联老人」 |
| D8 | 数值范围只做**输入合法性**校验，错误文案不带医学含义 | 例如收缩压 50–300 是防误录，不是医学口径 |
| D9 | 时间线 `measured_at DESC, id DESC`；趋势 `measured_at ASC, id ASC` | 时间线与趋势共用同一比较器，翻页不重不漏 |
| D10 | 趋势最多返回**最近 500 个点**，超出 `truncated=true` | 避免一次拉爆；如实告知未全部展示 |
| D11 | 响应返回服务端拼好的 `valueText` 与 `unit` | 三端展示一致，前端不重复拼字符串 |
| D12 | 新增错误码 `2008` / `2009` / `2010`，不滥用 `400` | 重复、记录不存在、不可撤回是业务冲突，需要精确排障 |

---

## 四、数据模型与 V9 迁移

### 4.1 `V9__vital_sign_record.sql`

源文件：`backend/sql/V9__vital_sign_record.sql`（构建时复制到 `resources/db/migration`，**不手工放第二份**）。

```sql
-- =============================================================================
-- V9 · 体征记录（V4 · P2-2）
--
-- 一张表装三类体征（血压 / 血糖 / 体重），按 vital_type 区分。
-- 三条硬约束：
--   1. 只存测量数值，不存任何分级或区间字段；
--   2. dedup_key 为「有效记录」唯一键：老人 + 类型 + 分钟；
--      逻辑删除后生成列自动变 NULL，释放唯一索引槽位，支持撤回后重录；
--   3. 不提供自由文本备注，杜绝在数据层写入判断性描述。
--
-- 回滚（仓库无独立 rollback 目录，按本文件头部注释手工执行）：
--   DROP TABLE IF EXISTS `vital_sign_record`;
--
-- @since V4 · P2-2
-- =============================================================================

CREATE TABLE IF NOT EXISTS `vital_sign_record` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录 ID',
  `elder_id`         BIGINT      NOT NULL                COMMENT '老人档案 ID',
  `vital_type`       VARCHAR(24) NOT NULL                COMMENT '体征类型：BLOOD_PRESSURE-血压 / BLOOD_GLUCOSE-血糖 / WEIGHT-体重',
  `measured_at`     DATETIME    NOT NULL                COMMENT '测量时间（分钟精度，秒固定 00）',
  `systolic`         SMALLINT             DEFAULT NULL   COMMENT '收缩压（mmHg），仅 BLOOD_PRESSURE',
  `diastolic`        SMALLINT             DEFAULT NULL   COMMENT '舒张压（mmHg），仅 BLOOD_PRESSURE',
  `glucose_value`    DECIMAL(4,1)         DEFAULT NULL   COMMENT '血糖值（mmol/L），仅 BLOOD_GLUCOSE',
  `glucose_context`  VARCHAR(24)          DEFAULT NULL   COMMENT '血糖场景：FASTING-空腹 / POSTPRANDIAL-餐后2小时 / RANDOM-随机',
  `weight_kg`        DECIMAL(4,1)         DEFAULT NULL   COMMENT '体重（kg），仅 WEIGHT',
  `source_type`      VARCHAR(24) NOT NULL                COMMENT '录入来源：FAMILY_MANUAL-家属代录 / COMPANION_SERVICE-陪诊员服务时代录',
  `operator_user_id` BIGINT      NOT NULL                COMMENT '录入人用户 ID（家属或陪诊员）',
  `order_id`         BIGINT               DEFAULT NULL   COMMENT '陪诊订单 ID；仅陪诊员代录时有值',
  `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已撤回',
  `dedup_key`        VARCHAR(80) GENERATED ALWAYS AS (
      CASE WHEN `deleted` = 0
           THEN CONCAT(`elder_id`, ':', `vital_type`, ':', DATE_FORMAT(`measured_at`, '%Y%m%d%H%i'))
           ELSE NULL
      END
  ) STORED COMMENT '有效记录去重键；逻辑删除后自动置 NULL',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vital_dedup` (`dedup_key`),
  KEY `idx_elder_type_time` (`elder_id`, `vital_type`, `measured_at`),
  KEY `idx_elder_time` (`elder_id`, `measured_at`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_operator` (`operator_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='体征记录表（只记录测量数值）';
```

### 4.2 回滚要点

| 项 | 内容 |
|---|---|
| 回滚语句 | `DROP TABLE IF EXISTS vital_sign_record;` |
| 影响面 | 仅本表；不涉及 `elder_profile` / `companion_order` / 既有索引 |
| 数据 | 回滚即丢失全部体征记录，不可恢复；仅限开发 / 演示环境 |
| 依赖 | V9 之后无其它迁移依赖本表，回滚不需要先回滚其它脚本 |
| 禁止 | 不修改 V1–V8 任何已应用脚本；不新增第二份回滚脚本目录 |

### 4.3 字段约束

| 字段 | 约束 |
|---|---|
| `elder_id` | 必须存在且未逻辑删除；由路径 / 订单推导，**不由请求体传入** |
| `vital_type` | 枚举 `BLOOD_PRESSURE` / `BLOOD_GLUCOSE` / `WEIGHT` |
| `measured_at` | 不得晚于「当前时间 + 5 分钟」；落库截断到分钟 |
| `systolic` | 仅血压必填；整数 50–300 |
| `diastolic` | 仅血压必填；整数 30–200；且必须小于 `systolic` |
| `glucose_value` | 仅血糖必填；1.0–33.3 mmol/L，1 位小数 |
| `glucose_context` | 仅血糖必填；`FASTING` / `POSTPRANDIAL` / `RANDOM` |
| `weight_kg` | 仅体重必填；10.0–300.0 kg，1 位小数 |
| 互斥 | 三种类型只允许出现本类型的数值字段；多余字段一律 `400` |
| `source_type` | 服务端写入，请求体不接受 |
| `operator_user_id` | 服务端从登录态写入，请求体不接受 |
| `order_id` | 仅 `COMPANION_SERVICE` 写入，必须等于路径订单 |

> 上述上下限写进 Service 常量与测试，仅用于输入合法性；**不通过接口返回、不在前端展示、不命名为任何区间**。

---

## 五、接口契约

统一前缀 `/api`。响应包裹、时间格式、错误码遵循 `docs/api/README.md`。

### 5.1 家属端：体征时间线

`GET /api/family/elder/{elderId}/vitals`

权限：FAMILY；归属：`ElderService#requireAccessible(elderId)`（失败 `2006` / `2001`）。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `elderId` | Long | ✓ | 路径变量 |
| `vitalType` | String | 否 | `BLOOD_PRESSURE` / `BLOOD_GLUCOSE` / `WEIGHT`；空=全部 |
| `startDate` / `endDate` | String | 否 | `yyyy-MM-dd`，缺省最近 30 天，最大 366 天，闭区间 |
| `page` / `size` | Long | 否 | 默认 1 / 10，`size` 上限 100（沿用 `PageQuery`） |

响应 `Result<PageResult<VitalSignVO>>`，按 `measured_at DESC, id DESC`。

`VitalSignVO`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 记录 ID |
| `elderId` | Long | 老人档案 ID |
| `vitalType` / `vitalTypeLabel` | String | `BLOOD_PRESSURE` / `血压` 等 |
| `measuredAt` | String | `yyyy-MM-dd HH:mm:ss` |
| `valueText` | String | `128/78 mmHg` / `6.2 mmol/L · 空腹` / `62.5 kg` |
| `unit` | String | `mmHg` / `mmol/L` / `kg` |
| `systolic` / `diastolic` | Integer | 血压时返回，其它为 `null` |
| `glucoseValue` | BigDecimal | 血糖时返回，其它为 `null` |
| `glucoseContext` / `glucoseContextLabel` | String | `FASTING` / `空腹` 等 |
| `weightKg` | BigDecimal | 体重时返回，其它为 `null` |
| `sourceType` / `sourceTypeLabel` | String | `FAMILY_MANUAL` / `家属代录`、`COMPANION_SERVICE` / `陪诊员服务代录` |
| `orderId` | Long | 陪诊员代录时返回，否则 `null` |

**不返回**：录入人姓名、手机号、身份证号、任何解释性字段、任何区间字段。

### 5.2 家属端：体征趋势

`GET /api/family/elder/{elderId}/vitals/trend`

权限 / 归属同上。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vitalType` | String | ✓ | 缺省或非法 → `400` |
| `startDate` / `endDate` | String | 否 | 同上，缺省 30 天，最大 366 天 |

响应 `Result<VitalTrendVO>`：

```json
{
  "vitalType": "BLOOD_PRESSURE",
  "vitalTypeLabel": "血压",
  "unit": "mmHg",
  "range": { "startDate": "2026-10-05", "endDate": "2026-11-03", "days": 30 },
  "pointCount": 12,
  "truncated": false,
  "points": [
    {
      "measuredAt": "2026-10-06 08:10:00",
      "valueText": "126/76 mmHg",
      "systolic": 126, "diastolic": 76,
      "glucoseValue": null, "glucoseContext": null, "glucoseContextLabel": null,
      "weightKg": null
    }
  ]
}
```

- `points` 按 `measured_at ASC, id ASC`。
- 区间内超过 500 条时，取**最近 500 条**再按升序返回，`truncated=true`。
- 无数据：`points: []`、`pointCount: 0`，不报错。

### 5.3 家属端：新增体征

`POST /api/family/elder/{elderId}/vitals`

权限 FAMILY；归属 `requireAccessible`。请求体 `VitalSignCreateDTO`：

```json
{
  "vitalType": "BLOOD_PRESSURE",
  "measuredAt": "2026-11-03 08:10:00",
  "systolic": 128,
  "diastolic": 78,
  "glucoseValue": null,
  "glucoseContext": null,
  "weightKg": null
}
```

- `sourceType=FAMILY_MANUAL`、`operatorUserId=当前用户`、`orderId=null` 由服务端写入。
- 成功返回 `Result<VitalSignVO>`（同 5.1 单条结构）。

**错误**：`2001` 档案不存在；`2006` 非本人绑定老人；`2008` 同一分钟同类记录已存在；`400` 类型 / 字段 / 数值 / 时间不合法。

### 5.4 家属端：撤回体征

`DELETE /api/family/vitals/{recordId}`

权限 FAMILY。规则：

1. 记录不存在或已撤回 → `2009`；
2. 记录所属老人不可访问 → `2006`（不泄漏记录是否存在）；
3. `sourceType != FAMILY_MANUAL` 或 `operatorUserId != 当前用户` → `2010`；
4. 通过 → 逻辑删除，返回 `Result<VitalWithdrawVO>`：`{ "recordId": 123, "deleted": true }`。

重复撤回按第 1 条返回 `2009`（不幂等）。

### 5.5 老人端：我的体征

| 端点 | 权限 | 说明 |
|---|---|---|
| `GET /api/elder/vitals` | ELDER | 参数同 5.1（**不含 `elderId`**），服务端从登录态取档案 |
| `GET /api/elder/vitals/trend` | ELDER | 参数同 5.2，**不含 `elderId`** |

- 无档案 → `2001`。
- 老人端响应与家属端**完全同构**（不含家属关系、费用等无关字段）。
- ELDER 的 `POST / DELETE` 由 `ElderReadOnlyInterceptor` 统一 403，不新增写端点。

### 5.6 陪诊员端：服务中代录

`POST /api/execution/{orderId}/vitals`

权限 COMPANION；请求体同 5.3。

服务端校验顺序：

1. 订单不存在 → `3001`；
2. `order.companionId != 当前用户` → `3004`；
3. `order.status != IN_SERVICE` → `3002`；
4. 请求体校验（同 5.3）；
5. 写入：`elderId = order.elderId`、`sourceType=COMPANION_SERVICE`、`orderId=orderId`、`operatorUserId=当前用户`。

成功返回 `Result<VitalSignVO>`。

### 5.7 陪诊员端：服务中撤回

`DELETE /api/execution/{orderId}/vitals/{recordId}`

校验：订单归属与状态同 5.6；记录 `orderId` 必须等于路径订单，`sourceType=COMPANION_SERVICE`，`operatorUserId=当前用户`，否则 `3004` / `2010`；记录不存在 → `2009`。

服务完成后（订单离开 `IN_SERVICE`）不可撤回，返回 `3002`。

### 5.8 错误码

| 码 | 文案 | 场景 |
|---|---|---|
| `2008` | 同一时间的同类体征记录已存在 | 唯一键冲突 |
| `2009` | 体征记录不存在或已撤回 | 记录 ID 不存在 / 已逻辑删除 |
| `2010` | 该体征记录不可撤回 | 来源为陪诊员代录 / 非本人录入 |

其余复用：`2001`、`2006`、`3001`、`3002`、`3004`、`400`、`401`、`403`、`9002`。

---

## 六、权限矩阵

| 操作 | ELDER | FAMILY | COMPANION | ADMIN |
|---|---|---|---|---|
| 读本人体征 | ✅（登录态取 ID） | — | — | — |
| 读绑定老人体征 | — | ✅（`requireAccessible`） | ❌（`2006`） | ❌ |
| 家属代录 | ❌ 403 | ✅ | ❌ | ❌ |
| 服务中代录 | ❌ 403 | ❌ | ✅（本单 + `IN_SERVICE`） | ❌ |
| 撤回家属代录 | ❌ 403 | ✅（仅本人录入） | ❌ | ❌ |
| 撤回陪诊员代录 | ❌ 403 | ❌ | ✅（本单 + `IN_SERVICE`） | ❌ |

安全落点：

- `@PreAuthorize` 管角色；
- `ElderService#requireAccessible` 管家属归属；
- `OrderService` 语义的「订单归属 + 状态」管陪诊员，**不调用** `requireAccessible`；
- `ElderReadOnlyInterceptor` 兜底老人只读；
- 请求体**不接受** `elderId`、`sourceType`、`operatorUserId`、`orderId`。

---

## 七、去重、修改与时间线口径

### 7.1 去重

- 唯一键：`elder_id + vital_type + measured_at(分钟)`，仅对 `deleted=0` 生效。
- 冲突 → `2008`，提示「同一时间的同类体征记录已存在」。
- 撤回后 `dedup_key` 自动变 `NULL`，同一分钟可重新录入。
- 不同类型同一分钟不算重复（如 08:10 同时录血压和体重）。

### 7.2 修改

- 不提供编辑接口。
- 家属只能撤回**本人**的 `FAMILY_MANUAL` 记录；撤回后可重录。
- 陪诊员只能撤回**本人**的 `COMPANION_SERVICE` 记录，且订单必须仍在 `IN_SERVICE`。
- 服务结束后陪诊员记录锁定；家属也不能撤回他人的代录记录（`2010`）。
- 逻辑删除保留原行，`deleted=1`；时间线与趋势默认只查 `deleted=0`。

### 7.3 排序与分页

| 用途 | 排序 | 分页 |
|---|---|---|
| 时间线 | `measured_at DESC, id DESC` | `PageQuery`，默认 10，上限 100 |
| 趋势 | `measured_at ASC, id ASC` | 不分页；最近 500 条，`truncated` 如实返回 |
| 区间 | 闭区间 `[startDate 00:00:00, endDate 23:59:59]` | 复用 `DateRangeQuery#toRange()` |

---

## 八、红线自检

### 8.1 本模块的红线

1. **只记录与展示数值**：只有测量数值、时间、来源，没有解释性文案。
2. **不内置、不返回、不展示任何区间 / 目标值**。
3. **不提供自由文本**：表结构无备注列，DTO 无备注字段。
4. **不用颜色表达好坏**：趋势图只用主色系区分「收缩压 / 舒张压」两条线，不做区间底色。
5. **输入合法性 ≠ 医学口径**：上下限只拦截误录，错误文案不带「高 / 低」等词。
6. **老人端只读**：没有任何老人写端点。

### 8.2 禁用词登记（仅本节出现，供扫描核对）

以下词只作为**禁用清单**登记，不得出现在接口文案、页面文案、错误提示、图表标注：

```
偏高 偏低 异常 超标 建议就医 注意 参考区间 诊断 预警 评估 判定
```

扫描口径：

- `docs/design/p2-2-vitals-design.html`：命中数必须为 **0**；
- 本规格文件之外的其它新增文件：命中数必须为 **0**；
- 本文件命中仅允许出现在 §8.2 的词表代码块内。

### 8.3 页面文案口径

允许：「测量时间」「收缩压」「舒张压」「血糖」「空腹」「餐后 2 小时」「随机」「体重」「家属代录」「陪诊员服务代录」「撤回」「保存记录」「仅记录测量数值」。

禁止：任何解释、任何比较、任何「好 / 坏」暗示。

---

## 九、前端设计约束

| 项 | 约束 |
|---|---|
| 风格 | 沿用 `docs/design/design.md` 与 `variables.scss` 令牌：`#1D6FF2` 主色、`#F4F7FB` 页面底、14px 卡片圆角、`0 2px 8px rgba(23,43,77,.06)` 阴影 |
| 布局 | 家属端在 `family/elder-health.vue` 内新增「体征记录」区块（录入卡 + 趋势卡 + 时间线卡） |
| 老人端 | `elder/health.vue` 新增只读区块：字号 ≥18px、点击区 ≥48px、无输入、无撤回按钮 |
| 陪诊员端 | `companion/execute.vue` 在 `IN_SERVICE` 时显示代录卡；订单不在服务中时整卡隐藏 |
| 图表 | 内联 SVG 折线图，两个组件内自绘；**不新增依赖**；血压两条线仅用主色与浅主色区分 |
| 空态 | 文案「还没有体征记录」，家属端附「上方可直接录入」；老人端附「可由家属代为记录」 |
| 错误 | `2008` 在表单下方就地提示；其余走既有 `ElMessage` |
| 撤回 | 二次确认「撤回后该记录不再显示，且可重新录入」；成功后从列表移除 |
| 响应式 | 390px 移动画布优先；桌面沿用既有 `NlDesktopShell` 内容宽度 |

详细视觉见 `docs/design/p2-2-vitals-design.html`。

---

## 十、实施清单（文件级）

### 10.1 后端新建

| 文件 | 内容 |
|---|---|
| `backend/sql/V9__vital_sign_record.sql` | 见 §4.1 |
| `entity/VitalSignRecord.java` | 继承 `BaseEntity`；`@TableName("vital_sign_record")`；`@TableLogic` 字段由基类提供 |
| `mapper/VitalSignRecordMapper.java` | `BaseMapper<VitalSignRecord>` |
| `constant/VitalType.java` | `BLOOD_PRESSURE` / `BLOOD_GLUCOSE` / `WEIGHT` + 中文标签 + `unit` |
| `constant/VitalSource.java` | `FAMILY_MANUAL` / `COMPANION_SERVICE` + 中文标签 |
| `constant/GlucoseContext.java` | `FASTING` / `POSTPRANDIAL` / `RANDOM` + 中文标签 |
| `dto/VitalSignCreateDTO.java` | 类型、时间、三类数值字段；Bean Validation |
| `dto/VitalSignQuery.java` | 继承 `DateRangeQuery`，加 `vitalType` + `page/size`（或组合 `PageQuery`） |
| `dto/VitalTrendQuery.java` | 继承 `DateRangeQuery`，`vitalType` 必填 |
| `vo/VitalSignVO.java` | §5.1 字段；静态工厂 `of(VitalSignRecord)` |
| `vo/VitalTrendVO.java` | §5.2 外层 |
| `vo/VitalTrendPointVO.java` | §5.2 单点 |
| `vo/VitalWithdrawVO.java` | `recordId` + `deleted` |
| `service/VitalSignService.java` | 8 个方法（见 10.3） |
| `service/impl/VitalSignServiceImpl.java` | 归属、校验、去重、趋势、撤回 |

### 10.2 后端修改

| 文件 | 改动 |
|---|---|
| `common/ResultCode.java` | 新增 `2008` / `2009` / `2010` |
| `controller/family/FamilyHealthController.java` | 注入 `VitalSignService`；新增 4 个端点 |
| `controller/elder/ElderHealthController.java` | 注入 `VitalSignService`；新增 2 个只读端点 |
| `controller/execution/ExecutionController.java` | 注入 `VitalSignService`；新增 2 个陪诊员端点 |
| `docs/api/12-elder-health.md` | 新增 §6–§9（家属 / 老人 / 陪诊员 / 错误） |
| `docs/api/README.md` | 2xxx 表补 3 个码；权限矩阵补 3 行 |
| `docs/db/02-数据字典.md`、`03-索引设计与EXPLAIN.md`、`01-ER图.md` | 按既有生成 / 补齐流程加表与索引说明 |

**不新增** `SecurityConfig` 路径规则：三个控制器均已有 `@PreAuthorize`，且 `/api/execution` 非管理路径。

### 10.3 Service 方法签名

```java
PageResult<VitalSignVO> listForFamily(Long elderId, VitalSignQuery query);
VitalTrendVO trendForFamily(Long elderId, VitalTrendQuery query);
VitalSignVO createForFamily(Long elderId, VitalSignCreateDTO dto);
VitalWithdrawVO withdrawByFamily(Long recordId);

PageResult<VitalSignVO> listForElder(VitalSignQuery query);
VitalTrendVO trendForElder(VitalTrendQuery query);

VitalSignVO createForCompanion(Long orderId, VitalSignCreateDTO dto);
VitalWithdrawVO withdrawByCompanion(Long orderId, Long recordId);
```

实现要点：

- `normalizeMeasuredAt`：截断秒、校验未来 5 分钟；
- `validateValueFields`：按类型校验字段互斥与范围；血压额外校验 `diastolic < systolic`；
- `create`：组装实体 → `insert` → 捕获 `DuplicateKeyException` → `2008`；
- `trend`：先按 `measured_at DESC` 取 501 条，判断 `truncated` 后裁剪 500 并反转为升序；
- `withdrawByFamily`：先查记录（逻辑删除过滤），再 `requireAccessible`，再校验来源与录入人；
- `createForCompanion`：先查订单，校验 `companionId` / `status`，再走与家属相同的数值校验；
- 所有状态字符串走枚举，不硬编码。

### 10.4 前端

**新建**

| 文件 | 内容 |
|---|---|
| `frontend/src/api/vitalSign.js` | 8 个函数（家属 4 / 老人 2 / 陪诊员 2） |
| `frontend/src/components/health/VitalTrendChart.vue` | 内联 SVG 折线图；props：`points`、`vitalType`、`unit`；血压双线 |
| `frontend/src/components/health/VitalSignForm.vue` | 类型切换、动态字段、提交；props：`mode`（family / companion）、`elderId` / `orderId` |
| `frontend/src/components/health/VitalTimeline.vue` | 列表 + 来源标签 + 撤回；props：`items`、`readonly` |

**修改**

| 文件 | 改动 |
|---|---|
| `frontend/src/views/family/elder-health.vue` | 引入录入卡、趋势卡、时间线卡；录入成功后刷新列表与趋势 |
| `frontend/src/views/elder/health.vue` | 只读体征区块（大字、无按钮） |
| `frontend/src/views/companion/execute.vue` | `IN_SERVICE` 时展示代录卡；成功后刷新本单已录列表（若页面已有记录区） |
| `frontend/src/styles/elderly.scss`（按需） | 如新增区块需要老人模式字号覆盖，仅补体征相关选择器 |

**不新增**路由：所有入口挂在既有页面内。

---

## 十一、测试计划

### 11.1 后端单测 `VitalSignServiceImplTest`

| # | 用例 | 断言 |
|---|---|---|
| 1 | 家属录入血压 | `sourceType=FAMILY_MANUAL`、`orderId=null`、`valueText=128/78 mmHg` |
| 2 | 同一老人同一类型同一分钟再录 | `2008`，第二次不入库 |
| 3 | 同一分钟不同类型 | 两条均成功 |
| 4 | 撤回后同分钟重录 | 成功（唯一键槽位已释放） |
| 5 | 血压缺舒张压 / 血糖缺场景 / 体重带血压字段 | `400` |
| 6 | 收缩压 1280 / 血糖 0 / 体重 999 | `400` |
| 7 | 舒张压 ≥ 收缩压 | `400` |
| 8 | `measuredAt` 晚于当前 10 分钟 | `400` |
| 9 | 家属撤回本人代录 | `deleted=true`，列表不再返回 |
| 10 | 家属撤回家属 B 记录 | `2006` |
| 11 | 家属撤回陪诊员代录 | `2010` |
| 12 | 陪诊员录入非本单订单 | `3004` |
| 13 | 陪诊员录入 `COMPLETED` 订单 | `3002` |
| 14 | 陪诊员服务完成后撤回 | `3002` |
| 15 | 趋势排序 / 500 条截断 | 升序、`truncated=true` |
| 16 | 趋势 `vitalType` 缺失 | `400` |

### 11.2 鉴权 `VitalSignSecurityTest`（MockMvc）

| 请求 | 期望 |
|---|---|
| FAMILY 读他人老人列表 / 趋势 / 录入 | `2006` |
| COMPANION 调家属端点 | `403` |
| ADMIN 调家属录入 | `403` |
| ELDER POST 家属 / 陪诊员端点 | `403`（只读拦截器） |
| 未登录任意端点 | `401` |

### 11.3 e2e `backend/sql/tools/e2e_vital_sign.py`

1. FAMILY 登录，选一条绑定老人，录入血压；
2. 同分钟重录 → `2008`；
3. 列表返回该条且按时间倒序；
4. 趋势返回同一点且升序；
5. ELDER 登录 → 能读自己的记录，POST → `403`；
6. 另一位 FAMILY 读该老人 → `2006`；
7. COMPANION 取一条 `IN_SERVICE` 订单代录 → 成功；换非本单订单 → `3004`；
8. 清理：按记录 ID 撤回 / 直接逻辑删除，恢复基线；输出残留检查。

### 11.4 前端

- `pnpm run lint:check`
- `pnpm run test`（新增 `VitalTrendChart` / `VitalSignForm` 的渲染与类型切换用例）
- 老人模式自查：字号 ≥18px、撤回按钮不存在、触控区 ≥48px

### 11.5 全量验证

```powershell
# backend
mvn -B verify

# frontend
pnpm run lint:check
pnpm run test

# e2e（仓库根目录，沿用既有环境变量与清理约定）
python backend/sql/tools/e2e_vital_sign.py

# 合规扫描（新增文件，排除本规格 §8.2 词表）
python tools/check_secrets.py
```

---

## 十二、验收标准

- [ ] 家属可对绑定老人录入三类体征；非绑定老人 `2006`。
- [ ] 陪诊员仅能在本单 `IN_SERVICE` 时录入；其它情况 `3002` / `3004`。
- [ ] 老人端只能读自己的记录，写请求 `403`。
- [ ] 同一分钟同类记录重复 → `2008`；撤回后可重录。
- [ ] 时间线倒序、趋势升序、分页不重不漏、500 点截断如实告知。
- [ ] 页面与接口无任何解释性文案或区间字段。
- [ ] 表结构无自由文本字段。
- [ ] `mvn -B verify`、`pnpm lint:check`、`pnpm test`、e2e 全绿。
- [ ] `docs/api/12-elder-health.md`、`docs/api/README.md`、`docs/db/*` 同步。
- [ ] V9 可回滚（单表 DROP），V1–V8 未改。

---

## 十三、待用户拍板

1. 血糖场景是否就保留「空腹 / 餐后 2 小时 / 随机」三项（不加睡前、凌晨）。
2. 撤回规则是否接受：家属只能撤回本人代录，陪诊员只能撤回服务中的代录，服务结束后锁定。
3. 输入合法性上下限（收缩压 50–300、舒张压 30–200、血糖 1.0–33.3、体重 10.0–300.0）是否认可为「防误录」而非医学口径。
4. 趋势默认 30 天、最大 366 天、最多 500 点，是否认可。
5. 体征是否**不并入**既有健康时间线四类事件，保持独立列表。

---

## 十四、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-11-03 | v0.1 | P2-2 初稿：范围、V9、接口、权限、去重 / 撤回、排序、红线、测试与验收 |
