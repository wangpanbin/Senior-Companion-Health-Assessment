# 15 · 产品反馈（用户建议 → 管理端处理）

> **负责模块**：M2（客户端入口）+ M9（管理端处理）
> **页面编号**：`M-34`（客户端 `/feedback`）/ `W-15`（后台 `/admin/feedback`）
> **对应代码**：`controller/feedback/FeedbackController`、`service/impl/FeedbackServiceImpl`、
> `constant/FeedbackType`、`constant/FeedbackStatus`、`constant/FileBizType#FEEDBACK`
> **全局约定**：`docs/api/README.md`、文件上传见 [10-file-upload.md](./10-file-upload.md)

---

## 〇、为什么要有这条通道（先读这一段）

平台本来有两条反馈通道，**两条都绑死在订单上**：

| 通道 | 绑定 | 记录到哪 |
|---|---|---|
| 评价 | `uk_order_review (order_id)`，一单一评 | `order_review`，进陪诊员评分聚合 |
| 投诉 | `complaint.order_id` / `target_user_id` 均 `NOT NULL` | `complaint`，进纠纷仲裁与违规记录 |

用户真正想提的往往是**产品本身**（"订单列表的字有点小"、"希望支持多老人一键下单"）。
这类建议若走投诉，会被当成服务纠纷走仲裁、写进陪诊员的违规记录；
若走评价，会污染评分聚合。所以需要**第三条与订单完全解耦**的通道：

- 客户端：家属 / 陪诊员选分类 + 写文字 + 可选截图，提交后能在「我的反馈」里看进度；
- 管理端：汇总、筛选、按状态机处理，终态时给提交人发站内信。

### 三条硬边界

1. **不做诊断、不开药方（红线-1）**。用户输入的正文**只过敏感词**
   （`SensitiveWordUtil`）—— 用户写「希望用药页能提醒别吃错」是正当建议，
   套医疗词表会被误杀。但**管理员写的处理说明过两套词表**
   （`SensitiveWordUtil` + `ComplianceCheckUtil`），因为那句话会经站内信原样推给用户，
   属于平台产出。命中一律 `6003`。
2. **老人（ELDER）不提反馈**。项目硬规则「老人账号只读」，本模块**不开** `@AllowElderWrite` 豁免：
   POST 被 `ElderReadOnlyInterceptor` 直接 403，`/feedback` 路由 `roles` 也不含 ELDER。
3. **不采集任何设备 / 版本指纹**。隐私最小化（AGENTS.md §0.1-2）：客户端指纹属敏感数据，
   收益远小于风险。反馈里只有「分类 + 文字 + 截图三板斧 + 提交人 ID」。

---

## 一、数据模型

### FeedbackVO（对外唯一出口）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 反馈 ID |
| `submitterId` | Long | 提交人用户 ID |
| `submitterName` | String | 提交人姓名（**脱敏**，`MaskUtil.name`） |
| `submitterRole` | String | `FAMILY` / `COMPANION` |
| `type` | String | `FEATURE` / `EXPERIENCE` / `DEFECT` / `OTHER` |
| `typeLabel` | String | 分类中文名 |
| `content` | String | 反馈正文（10–1000 字） |
| `images` | Array | 截图 URL 列表（≤3 张；`/uploads/...`，可 `<img>` 直连）。上传类型收窄为 jpg/png/webp —— pdf 被服务端类型闸门拒绝 |
| `status` | String | `PENDING` / `PROCESSING` / `RESOLVED` / `REJECTED` |
| `statusLabel` | String | 待处理 / 处理中 / 已采纳 / 未采纳 |
| `handleResult` | String | 处理说明（**仅终态返回**，见 §三） |
| `createTime` | String | 提交时间 |
| `handleTime` | String | 处理时间（未处理时字段被 Jackson `non_null` 省略） |

### 分类（`FeedbackType`）

| 传值 | 中文 | 典型内容 |
|---|---|---|
| `FEATURE` | 功能建议 | 「希望支持一次给两位老人下单」 |
| `EXPERIENCE` | 使用体验 | 「订单列表的字有点小，老人看不清」 |
| `DEFECT` | 缺陷问题 | 「点提交之后页面卡住，返回才看到已提交」 |
| `OTHER` | 其他 | 归不进上面三类 |

> 分类是 **Java 枚举**，不入 `sys_dict` —— 与 `ComplaintType` 同款做法。
> 分类一旦改动就要同步数据库里的历史值，放进字典表会让人不敢动它。

### 状态机（`FeedbackStatus`，**只可正向流转**）

```
  PENDING(待处理) ──► PROCESSING(处理中) ──► RESOLVED(已采纳)
       │                     │
       └─────────────────────┴──► REJECTED(未采纳)
```

| 传值 | 中文 | 说明 |
|---|---|---|
| `PENDING` | 待处理 | 刚提交 |
| `PROCESSING` | 处理中 | 管理员已接手（**必须**写处理说明） |
| `RESOLVED` | 已采纳 | 终态 |
| `REJECTED` | 未采纳 | 终态 |

- **不允许回退**，也不允许 `PENDING → RESOLVED` 跳级（理由同 `ComplaintStatus`：
  驳回/结案等价于「我判定过了」，需要理由也需要留痕，多一次「接手」动作换来每份结论背后
  都有一个明确的操作时刻）。
- 非法流转 / 同态重复处理一律 `409`。

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/api/feedback` | FAMILY / COMPANION | 提交反馈 |
| 2 | GET | `/api/feedback/mine` | FAMILY / COMPANION | 我的反馈列表（分页） |
| 3 | GET | `/api/feedback/{id}` | 提交人本人 / ADMIN | 反馈详情 |
| 4 | GET | `/api/admin/feedback` | ADMIN | 管理端反馈列表 |
| 5 | POST | `/api/admin/feedback/{id}/handle` | ADMIN | 处理反馈 |

---

## 1. 提交反馈

`POST /api/feedback`　权限：**FAMILY / COMPANION**（ELDER 被只读拦截器 403）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `type` | String | ✓ | 见分类枚举 | 反馈分类 |
| `content` | String | ✓ | 10–1000 字符（`trim` 后） | 反馈正文 |
| `images` | Array | 否 | ≤3 个，每个 ≤255 字符 | 截图 URL 列表 |

```json
{
  "type": "EXPERIENCE",
  "content": "订单列表的字有点小，老人模式下也还是偏小，希望能再大一号。",
  "images": ["/uploads/202611/9f8e7d6c5b4a39281706fedcba987654.jpg"]
}
```

### 响应

```json
{
  "code": 200,
  "message": "反馈已提交，感谢您的建议",
  "data": { "feedbackId": 5001, "status": "PENDING", "statusLabel": "待处理" }
}
```

### 错误场景

| code | HTTP | 场景 |
|---|---|---|
| `400` | 200 | `type` 取值不合法（响应带允许取值）；`content` 长度不合规（Bean Validation + 服务层按 **trim 后**复核） |
| `400` | 200 | `images` 超过 3 张 |
| `6003` | 200 | `content` 命中敏感词 |
| `403` | 403 | ELDER 调用（只读拦截器）；未登录 / token 失效 |

### 实现要点

1. **截图先上传再提交**：图片走通用上传端点 `POST /api/file/upload`，
   `bizType=FEEDBACK`（见 [10-file-upload.md §一](./10-file-upload.md)），拿到 `url` 后随本接口一起提交。
   本接口**只收 URL 数组**，不收 multipart —— 一个接口同时收 JSON 与二进制会让
   `type` 之类的枚举校验静默失效（与 `ComplaintCreateDTO` 同一取舍）。
2. **不做图片归属校验**：`images` 里的 URL 不校验 `sys_file.uploader_id`
   （与投诉 `evidence` 同口径）。截图本就是用户自证材料，仲裁 / 采纳时同样要人工核验；
   为它加一次 `sys_file` 反查只换来「伪造他人 URL 的成本高一点」。
3. **只过敏感词，不过医疗词表**（见 §〇 边界 1）。
4. **提交时不通知管理员**：反馈是低频、非紧急、非当事人纠纷，塞进管理员站内信只会制造噪音；
   管理员有侧栏入口和专门页面。站内信的方向仍保持 M8 的「系统 → 用户」单向。
5. **不做限流**：正常用户一辈子提不了 5 条；低价值复杂度留给真正有流量的地方。
   刷屏靠管理员处理。
6. **不支持撤回 / 编辑**：反馈不是合同，提交成本极低，重提一条更简单；
   而「提交后还能改」会让管理端的处理永远基于一个可能正在变的文本。
7. 落库时 `submitter_id` / `submitter_role` 取自登录态，**不接受前端传入**（防伪造）。

---

## 2. 我的反馈列表

`GET /api/feedback/mine`　权限：**FAMILY / COMPANION**（只看自己的，`submitter_id` 从令牌取）

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Long | 否 | 分页，`size` 上限 100 |
| `status` | String | 否 | 状态筛选 |

### 响应

结构同分页约定（`{ total, page, size, pages, records }`），`records` 为 `FeedbackVO` 数组，
按 `create_time DESC` 排序。

> `handleResult` 在 `PENDING` / `PROCESSING` 阶段**不返回**（不是返回空串）——
> 中间草稿一旦被前端渲染出去，用户会以为建议已经有结论了。与 `ComplaintVO` 同款处理。

---

## 3. 反馈详情

`GET /api/feedback/{id}`　权限：**提交人本人 / ADMIN**（其余 `12001`）

> 本端点的方法级注解是 `@PreAuthorize("isAuthenticated()")` —— **宽于类级**。
> 原因：管理员的入口在 `/api/admin/**`，但他在「产品反馈」页点开某条记录看原文与截图时
> 走的就是本端点；而「这条反馈是不是你的」注解表达不了，只能由 Service 层判定。
> 因此角色注解在这里只做「要登录」，可见性由 `FeedbackServiceImpl#detail` 把关。
> `POST /api/feedback` 与 `GET /api/feedback/mine` 仍为 FAMILY / COMPANION 专属。

### 响应

同 `FeedbackVO`。

### 错误场景

| code | 场景 |
|---|---|
| `12001` | 反馈不存在，或不属于当前用户（**刻意不区分**，避免按 ID 枚举他人反馈是否存在） |

> 产品反馈没有「双方」概念，所以不像投诉那样有投诉人 / 被投诉人两个视角，
> 也没有相关的站内信跳转。管理员走 §4 / §5。

---

## 4. 管理端反馈列表

`GET /api/admin/feedback`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Long | 否 | 分页，`size` 上限 100 |
| `status` | String | 否 | 状态筛选 |
| `type` | String | 否 | 分类筛选 |
| `keyword` | String | 否 | 正文 / 提交人姓名模糊搜索（管理端界面已暴露） |
| `startDate` | String | 否 | 提交时间起（`yyyy-MM-dd`；管理端界面用日期区间控件暴露） |
| `endDate` | String | 否 | 提交时间止（`yyyy-MM-dd`，含当天；管理端界面用日期区间控件暴露） |

### 响应

结构同分页约定，`records` 为 `FeedbackVO` 数组，**待处理优先**排前
（`status` 排序权重，`PENDING` 最先），同状态内按 `create_time DESC`。

> 排序权重序是 `PENDING → PROCESSING → RESOLVED → REJECTED`，**不是 `status` 的字典序** ——
> 字典序会把「未采纳」（`REJECTED`）排在「已采纳」（`RESOLVED`）前面。
> 实现走 `FIELD(status, ...)`（整段 ORDER BY 塞进 `last()`，与
> `AdminServiceImpl#FULFILLMENT_RISK_LIST_ORDER_BY` 同一手法），参数顺序从
> `FeedbackStatus.fieldOrder()` 取，权重只在枚举里写一份。

### 实现要点

- `keyword` 命中提交人姓名时需要两段查：先按姓名模糊查 `sys_user` 拿 id 集合，
  再进 `product_feedback`（与 `AdminComplaintQuery` 同一手法，走 `idx_submitter`）。
- 管理端**能看到全部反馈**，不做收窄 —— 这与客户端「只看自己」是两套不同的 DTO，
  不共用（避免把「必须限定提交人」这条安全约束藏进一个既能全看又能收窄的类里）。

---

## 5. 处理反馈

`POST /api/admin/feedback/{id}/handle`　权限：**ADMIN**

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `status` | String | ✓ | 只能是 `PROCESSING` / `RESOLVED` / `REJECTED` | 目标状态 |
| `handleResult` | String | ✓ | 10–500 字符 | 处理说明（**每次流转必填**） |

```json
{ "status": "RESOLVED", "handleResult": "已排入下一版：订单列表与用药页字号 +2px，老人模式同步放大。" }
```

### 响应

```json
{
  "code": 200,
  "message": "反馈已处理",
  "data": {
    "feedbackId": 5001,
    "status": "RESOLVED",
    "statusLabel": "已采纳",
    "handleTime": "2026-11-20 10:30:00"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| `12001` | 反馈记录不存在 |
| `400` | `status` 取值不合法；`handleResult` 长度不合规（Bean Validation + 服务层按 **trim 后**复核） |
| `409` | 已处于该状态；或状态不可从当前值流转到目标值（含回退、跳级） |
| `6003` | 处理说明命中敏感词，**或命中医疗红线词表**（如「建议您调整剂量」） |

### 实现要点

1. 动作顺序：`12001` 不存在 → `status` 合法性 → 同态 `409` → `canTransitTo` 不通过 `409`
   → **`handleResult` 过两套词表**（敏感词 + 医疗红线）→ 条件更新 → 终态发站内信 → 落 `admin_oper_log`。
2. **`handleResult` 每次流转都会覆盖写入**，但**只有终态才下发**给提交人（§二 注）。
   中间态的草稿会被终态覆盖 —— 这是刻意的，用户只看最终结论。
3. **条件更新闸门**：`UPDATE ... WHERE id = ? AND status = <读到的旧状态>`，
   并发下第二个管理员拿到 0 行影响即按 `409` 处理（与 `handleComplaint` 同一手法）。
4. **只有终态发站内信**：`PROCESSING` 也发的话，用户会收到一条「已处理完成」，
   而实际处理还在进行。
5. 每次处理都落 `admin_oper_log`：`OperType.HANDLE_FEEDBACK` + `OperTargetType.FEEDBACK`，
   记录旧值 → 新值与操作人（M9 硬性约束 2）。

---

## 三、站内信（`MessageType.FEEDBACK_HANDLED`）

| 项 | 值 |
|---|---|
| 触发 | 处理走到终态（`RESOLVED` / `REJECTED`） |
| 接收人 | `submitter_id`（单个，不群发） |
| `bizType` | `FEEDBACK` |
| `bizId` | `feedbackId` |
| 标题 | 反馈处理结果 |
| 正文 | `您提交的产品反馈已处理：{handleResult 前 100 字}` |
| 跳转 | `/feedback` |

- 正文里的 `handleResult` 摘要由**调用方预先截断到 100 字以内**（含省略号），
  模板只负责拼接，不做二次截断 —— 与 `REVIEW_REPLIED` 的 `replyDigest` 同款约定。
- `MessageTemplateUtil` 的 `title()` / `content()` / `linkUrl()` 三个 switch 都是**穷举式**（无 `default`），
  新增枚举值漏加会**编译失败**而非运行时静默 —— 这是好事，不要为了绕过而加 `default`。

---

## 四、数据表（`V13__product_feedback.sql`）

```sql
CREATE TABLE IF NOT EXISTS `product_feedback` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '反馈 ID',
  `submitter_id`    BIGINT        NOT NULL                COMMENT '提交人用户 ID',
  `submitter_role`  VARCHAR(20)   NOT NULL                COMMENT '提交人角色：FAMILY / COMPANION',
  `type`            VARCHAR(20)   NOT NULL                COMMENT '分类：FEATURE / EXPERIENCE / DEFECT / OTHER',
  `content`         VARCHAR(1000) NOT NULL                COMMENT '反馈内容，10-1000 字',
  `images`          JSON                   DEFAULT NULL   COMMENT '截图 URL 数组，最多 3 张',
  `status`          VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSING / RESOLVED / REJECTED（只可正向流转）',
  `handle_admin_id` BIGINT                 DEFAULT NULL   COMMENT '处理管理员 ID',
  `handle_result`   VARCHAR(500)           DEFAULT NULL   COMMENT '处理说明，10-500 字；仅终态对提交人可见',
  `handle_time`     DATETIME               DEFAULT NULL   COMMENT '处理时间',
  `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`         TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_status_create` (`status`, `create_time`),
  KEY `idx_submitter` (`submitter_id`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='产品反馈表';
```

**无种子数据**：反馈是用户产出，预置假数据只会在演示时混进真实列表。

> 同一迁移里顺带更新 `sys_file.biz_type` 的**列注释**（只改元数据，不动类型与数据），
> 让它与 `FileBizType` 的取值集合保持一致。

---

## 五、验收标准

- [ ] 家属选分类 + 10 字以上正文 + 2 张截图提交 → `200`，`status=PENDING`，出现在「我的反馈」
- [ ] 正文含敏感词（如「垃圾」「滚」）→ `6003`，错误**落在正文字段下方**而非顶部 toast
- [ ] 传第 4 张截图 → 前端拦下提示「最多 3 张」，且后端 `images` 超 3 也返回 `400`
- [ ] `.txt` 改名为 `.jpg` 上传 → `400`（magic bytes 判定失败，`bizType=FEEDBACK` 仍走同一套校验）
- [ ] 陪诊员可提交；**ELDER 调用 POST → 403**，且 `/feedback` 路由守卫拦掉老人
- [ ] 管理员筛选 `PENDING` → 处理为「处理中」但**不填说明** → 字段级校验失败，不发请求
- [ ] 处理说明写「建议您调整剂量」→ `6003`（医疗红线词表命中）
- [ ] 处理为「已采纳」+ 合法说明 → 提交人收到站内信，点击跳 `/feedback`
- [ ] `PROCESSING` 阶段提交人的「我的反馈」**不显示**处理说明；终态后显示
- [ ] 对已终态记录再处理 → `409`
- [ ] 他人 `GET /api/feedback/{id}` → `12001`
- [ ] 操作日志页可见 `HANDLE_FEEDBACK` / `FEEDBACK` 记录，含旧值 → 新值与操作人
- [ ] `python tools/ai_flow/gate_check.py --only error-codes` 通过（`12001` 两侧一致）

---

## 六、变更记录

| 版本 | 日期 | 变更内容 |
|---|---|---|
| v0.1.0 | 2026-11-20 | 首版：产品反馈通道（客户端提交 / 我的反馈 / 管理端列表与处理）、`bizType=FEEDBACK`、`MessageType.FEEDBACK_HANDLED`、错误码 `12001` |
| v0.2.0 | 2026-11-26 | 两轴评审整改：管理端列表改按状态**权重**排序（`FIELD(status, ...)`，此前按字典序会把「未采纳」排在「已采纳」前）；分类非法时错误信息补上允许取值；正文 / 处理说明在服务层按 **trim 后**复核长度（`@Size` 按原始串计数，可被空白绕过）；管理端补日期区间筛选控件；客户端「我的反馈」补分页控件（此前固定取前 20 条，第 21 条起不可达）；`bizType=FEEDBACK` 上传类型收窄为 jpg/png/webp |
