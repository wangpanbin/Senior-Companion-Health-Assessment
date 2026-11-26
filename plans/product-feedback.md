# 产品反馈（用户建议 → 管理端处理）— 实施计划

> 状态：**口径已全部确认（grilling 3 轮）**，待评审。
> 分支：`feature/feedback-product-suggestion`（AGENTS.md §0.2 硬约束，禁止在 main 上写代码）。

## Context（为什么做）

客户端目前只有两条反馈通道，且**两条都绑死在订单上**：

- `order_review`：`uk_order_review(order_id)`，一单一评，结果进陪诊员评分聚合；
- `complaint`：`order_id` / `target_user_id` 均 `NOT NULL`，语义是「A 就某笔订单投诉 B」。

用户想提的是**产品本身**（"订单页按钮太小"、"希望支持多老人一键下单"）。
这类建议走投诉会被当成服务纠纷走仲裁、写进陪诊员的违规记录；走评价会污染评分。
所以需要第三条**与订单完全解耦**的通道：

- 客户端（家属 / 陪诊员）：选分类 + 写文字 + 可选 3 张截图，提交后可在「我的反馈」里看进度；
- 管理端：新页面汇总、筛选、按状态机处理，结案时给提交人发站内信。

## 已确认的决策（3 轮 grilling 结论）

| # | 决策 |
|---|---|
| Q1 | 提交人 = **FAMILY + COMPANION**。ELDER 不提（不碰 `@AllowElderWrite` 白名单） |
| Q2 | **新建表 `product_feedback`**，不动 `complaint` 一行 |
| Q3 | 上传白名单新增 `bizType=FEEDBACK`（jpg/png/webp，≤10MB） |
| Q4 | 分类单选必填：`FEATURE` / `EXPERIENCE` / `DEFECT` / `OTHER` |
| Q5 | 状态机仿投诉：`PENDING → PROCESSING → RESOLVED / REJECTED`，终态发站内信 |
| Q6 | 入口在「我的」页 → 独立页 `/feedback`（`NlPageShell` 自适应） |
| Q7 | 用户能看到「我的反馈」列表与结案后的处理说明 |
| Q8 | **不做限流**，只做长度校验 + 敏感词 |
| Q9 | 提交时不通知管理员；**只在终态**给提交人发 `FEEDBACK_HANDLED` |
| Q10 | `handleResult` 每次流转必填 10–500 字，**仅终态下发**给用户 |
| Q11 | 管理端筛选：`status` / `type` / `keyword` / `startDate` / `endDate` |
| Q12 | **不做多次回复**，单一最终结论 |
| Q13 | 不支持撤回 / 编辑 |
| Q14 | 入参**只过敏感词**；处理说明**过敏感词 + 医疗红线词表**两套 |
| Q15 | 后端单测 + 权限矩阵 + 前端自查；e2e 脚本列为可选收尾 |

另定的两条：截图 **≤3 张**；**不采集任何设备 / 版本指纹**（隐私最小化红线）。

## 复用的既有零件（已核对源码）

| 用途 | 复用对象 | 位置 |
|---|---|---|
| 落盘 + magic bytes + `sys_file` 登记 | `FileStorageService#store` | `service/FileStorageService.java` |
| 上传白名单与错误提示（`allowedBizTypes()` 自动拼枚举，加枚举即生效） | `FileBizType` + `FileController#upload` | `constant/FileBizType.java` |
| 截图 `<img>` 直连 | 无需改动 —— `UploadAccessGuardFilter` 只拦 `COMPANION_CERT`，`FEEDBACK` 走公开直连 | `security/UploadAccessGuardFilter.java:128` |
| 状态机范式（`canTransitTo` / `isTerminal` / `labelOf`） | 照抄 `ComplaintStatus` | `constant/ComplaintStatus.java` |
| 分类枚举范式 | 照抄 `ComplaintType` | `constant/ComplaintType.java` |
| 管理端写操作留痕 | `OperLogRecorder#record` | `service/support/OperLogRecorder.java` |
| 站内信发送 | `MessageService#send` + `MessageTemplateUtil` | `service/MessageService.java` |
| 敏感词 / 医疗红线词表 | `SensitiveWordUtil.firstHit` / `ComplianceCheckUtil.firstHit` | `util/` |
| 姓名脱敏 | `MaskUtil.name` | `util/MaskUtil.java` |
| 分页查询基类 | `PageQuery<T>`（`page/size` 上限 100） | `common/PageQuery.java` |
| 管理端分页页范式 | `usePagedList` + `NlAdminTable` + `NlEmpty` 三态 | `composables/usePagedList.js`、`views/admin/complaint.vue` |
| 移动端页面骨架 | `NlPageShell` / `NlCard` / `NlStatusChip` / `NlEmpty` / `NlSkeleton` | `components/` |

## 设计与实现

### 1. 数据模型（`backend/sql/V13__product_feedback.sql`）

```sql
CREATE TABLE IF NOT EXISTS `product_feedback` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '反馈 ID',
  `submitter_id`  BIGINT       NOT NULL COMMENT '提交人用户 ID',
  `submitter_role` VARCHAR(20) NOT NULL COMMENT '提交人角色：FAMILY / COMPANION',
  `type`          VARCHAR(20)  NOT NULL COMMENT '分类：FEATURE / EXPERIENCE / DEFECT / OTHER',
  `content`       VARCHAR(1000) NOT NULL COMMENT '反馈内容，10-1000 字',
  `images`        JSON                  DEFAULT NULL COMMENT '截图 URL 数组，最多 3 张',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                  COMMENT 'PENDING / PROCESSING / RESOLVED / REJECTED（只可正向流转）',
  `handle_admin_id` BIGINT              DEFAULT NULL COMMENT '处理管理员 ID',
  `handle_result` VARCHAR(500)          DEFAULT NULL COMMENT '处理说明，10-500 字；仅终态对提交人可见',
  `handle_time`   DATETIME              DEFAULT NULL COMMENT '处理时间',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_create` (`status`, `create_time`),
  KEY `idx_submitter` (`submitter_id`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='产品反馈表';

-- 保持 sys_file 列注释诚实（只改注释元数据，不动类型与数据）
ALTER TABLE `sys_file` MODIFY COLUMN `biz_type` VARCHAR(20) DEFAULT NULL
  COMMENT '业务类型：COMPANION_CERT / CHECKIN / COMPLAINT / AVATAR / REVIEW / CHAT / FEEDBACK-产品反馈截图';
```

> 无种子数据：反馈是用户产出，预置假数据只会在演示时出现在真实列表里。

### 2. 后端新增文件

| 文件 | 内容 |
|---|---|
| `constant/FeedbackType.java` | `FEATURE/FEATURE_LABEL`… + `of()` / `labelOf()`，照抄 `ComplaintType` |
| `constant/FeedbackStatus.java` | `PENDING/PROCESSING/RESOLVED/REJECTED` + `TRANSITIONS` + `canTransitTo` / `isTerminal` / `of` / `labelOf`，照抄 `ComplaintStatus` |
| `constant/FileBizType.java` | **改**：新增 `FEEDBACK("产品反馈截图")`，并更新类 javadoc（说明它是第 5 个放行值、≤10MB、jpg/png/webp） |
| `constant/MessageType.java` | **改**：新增 `FEEDBACK_HANDLED("反馈处理结果", MessageBizType.FEEDBACK)` |
| `constant/MessageBizType.java` | **改**：新增 `FEEDBACK = "FEEDBACK"` |
| `constant/OperType.java` | **改**：新增 `HANDLE_FEEDBACK("处理产品反馈")` |
| `constant/OperTargetType.java` | **改**：新增 `FEEDBACK("产品反馈")` |
| `entity/ProductFeedback.java` | 继承 `BaseEntity`，`@TableName("product_feedback")` |
| `mapper/ProductFeedbackMapper.java` | 空接口 `extends BaseMapper<ProductFeedback>` |
| `dto/FeedbackCreateDTO.java` | `type`(@NotBlank) / `content`(@NotBlank @Size(10,1000)) / `images`(@Size(max=3)) |
| `dto/FeedbackQuery.java` | 客户端「我的反馈」：`page` / `size` / `status` |
| `dto/AdminFeedbackQuery.java` | 管理端：`status` / `type` / `keyword` / `startDate` / `endDate`，照抄 `AdminComplaintQuery` |
| `dto/FeedbackHandleDTO.java` | `status`(@NotBlank) / `handleResult`(@NotBlank @Size(10,500)) |
| `vo/FeedbackVO.java` | `of(entity, submitterName, images)`；**仅终态**下发 `handleResult`（照抄 `ComplaintVO` 的做法）；姓名走 `MaskUtil.name` |
| `vo/FeedbackCreateResultVO.java` | `feedbackId` / `status` / `statusLabel` |
| `vo/FeedbackHandleResultVO.java` | `feedbackId` / `status` / `statusLabel` / `handleTime` |
| `service/FeedbackService.java` + `service/impl/FeedbackServiceImpl.java` | 见下 |
| `controller/feedback/FeedbackController.java` | 客户端 3 个端点 |
| `controller/admin/AdminController.java` | **改**：追加 2 个端点（与投诉 `complaint` 相邻，沿用同文件既有分组风格） |

### 3. 接口契约（先写 `docs/api/15-product-feedback.md`，再写代码）

| # | 方法 | 路径 | 权限 |
|---|---|---|---|
| 1 | POST | `/api/feedback` | FAMILY / COMPANION |
| 2 | GET | `/api/feedback/mine` | FAMILY / COMPANION（只看自己） |
| 3 | GET | `/api/feedback/{id}` | 提交人本人 / ADMIN（他人 12001） |
| 4 | GET | `/api/admin/feedback` | ADMIN |
| 5 | POST | `/api/admin/feedback/{id}/handle` | ADMIN |

`POST /api/feedback` 请求体：

```json
{ "type": "EXPERIENCE", "content": "订单列表的字有点小，老人看不清。",
  "images": ["/uploads/202611/abc.jpg"] }
```

服务端动作顺序：`type` 合法性 → `content` 敏感词（`SensitiveWordUtil`）→
`images` 数量 ≤3 → 落库 `PENDING` → 返回 `{ feedbackId, status, statusLabel }`。
**不**解析图片归属（`sys_file.uploader_id` 的校验留给后续版本，与投诉 `evidence` 同口径）。

`POST /api/admin/feedback/{id}/handle` 服务端动作顺序：
`12001` 不存在 → `FeedbackStatus.of` 合法性（`400`）→ 同态 `409` → `canTransitTo` 不通过 `409`
→ **`handleResult` 过 `SensitiveWordUtil` + `ComplianceCheckUtil` 两套词表**（命中 → `6003`）
→ 条件更新（`.eq(status, current)` 乐观闸门）→ **终态**发 `FEEDBACK_HANDLED` 站内信
→ `operLogRecorder.record(HANDLE_FEEDBACK, FEEDBACK, id, ...)`。

### 4. 错误码

| code | 含义 | 位置 |
|---|---|---|
| `12001` | 反馈记录不存在 | `ResultCode` 新开 12xxx 段（10xxx 助手 / 11xxx V4 已占） |
| 复用 `400` | 分类 / 状态取值非法 | — |
| 复用 `409` | 状态不可流转 / 已处于该状态 | 与投诉同口径 |
| 复用 `6003` | 内容或处理说明含敏感词 / 违规表述 | — |
| 复用 `403` | ELDER 调用写接口（拦截器兜底，无需额外代码） | — |

> `docs/api/README.md` 错误码总表**必须**同步 —— `tools/ai_flow/gate_check.py --only error-codes`
> 双向校验 `ResultCode` ↔ 文档，漏一处 CI 红。

### 5. 站内信（`MessageTemplateUtil` 三个 switch 都要加）

`FEEDBACK_HANDLED`：

- `title()` → `"反馈处理结果"`
- `content()` → `"您提交的产品反馈已处理：%s"`（`handleResult` 由调用方截断到 100 字内，模板不再二次截断）
- `linkUrl()` → `"/feedback"`
- 接收人：`submitter_id`；`bizId = feedbackId`

⚠️ 三个 switch 是穷举式（无 `default`），漏加会**编译失败**而非运行时静默 —— 这是好事，别为了绕过而加 `default`。

### 6. 前端

| 文件 | 改动 |
|---|---|
| `api/feedback.js` | **新建**：`createFeedback` / `listMyFeedback` / `getFeedback` |
| `api/admin.js` | **改**：`listAdminFeedback` / `handleFeedback` |
| `api/file.js` | **改**：JSDoc 的 `bizType` 联合类型加 `FEEDBACK` |
| `router/routes.js` | **改**：移动端 `{ path: 'feedback', name: 'ProductFeedback', meta: { title:'产品反馈', icon:'ChatLineSquare', roles:['FAMILY','COMPANION'], module:'M2', elderlyHidden:true, code:'M-34' } }`；后台 `/admin/feedback`，`meta: { title:'产品反馈', icon:'ChatLineSquare', roles:['ADMIN'], module:'M9', code:'W-15' }` |
| `views/feedback/index.vue` | **新建**：上半「提交反馈」（分类 `el-radio-group` + 正文 `el-input textarea` + 截图 3 格上传，**敏感词命中落字段级错误**）；下半「我的反馈」分页列表（`NlStatusChip scope="feedback"`、结案后展示 `handleResult`） |
| `views/admin/feedback.vue` | **新建**：照抄 `views/admin/complaint.vue` 的骨架 —— `usePagedList` 三件套 + `NlAdminTable` + `NlSkeleton`/`NlEmpty` 三态 + 处理弹窗（`el-radio-group` 目标状态 + 说明 textarea + **in-flight 锁**） |
| `views/profile/index.vue` | **改**：新增一节「帮助与反馈」，一条 `NlListRow` 跳 `/feedback`（仅 `isFamily \|\| isCompanion`） |
| `components/NlStatusChip.vue` | **改**：`SCOPES` 新增 `feedback` 表（`PENDING` 待处理 / `PROCESSING` 处理中 / `RESOLVED` 已采纳 / `REJECTED` 未采纳） |
| `utils/format.js` | **改**：`FEEDBACK_STATUS_TEXT`、`FEEDBACK_TYPE_TEXT` |

**不动** `components/NlAppTabBar.vue`（一级 tab 硬编码，加第 6 格会在 375px 挤爆，且老人模式更糟）。

### 7. 文档同步（`docs/agents/doc-sync.md` 口径）

- 新建 `docs/api/15-product-feedback.md`（数据模型 / 接口 / 错误码 / 验收标准 / 变更记录）
- `docs/api/README.md`：文档索引表加一行、错误码表加 `12001`、权限矩阵加「产品反馈」一行
- `docs/api/10-file-upload.md`：§一白名单表加 `FEEDBACK` 行、§二接口表脚注、变更记录加一条
- `docs/design/design.md`：页面清单补 `M-34` / `W-15`
- `GLOSSARY.md`：新增「产品反馈」词条（与「投诉」「评价」区分）
- `CHANGELOG.md`：本迭代条目
- `docs/agents/FRONTEND_CONTRACT.md` §3 接口清单加三行、§2 枚举真值表加两组
- 是否需要 `docs/adr/` 决策记录：本次决策链已完整落在 `docs/api/15-product-feedback.md` §〇 与
  `plans/product-feedback.md` 的决策表里，**不再单开 ADR**（避免同一套理由维护两份）。

## Files to modify（汇总）

```
backend/sql/V13__product_feedback.sql                        [新]
backend/src/main/java/org/company/nianglin/
  constant/{FeedbackType,FeedbackStatus}.java                 [新]
  constant/{FileBizType,MessageType,MessageBizType,OperType,OperTargetType}.java  [改]
  entity/ProductFeedback.java                                 [新]
  mapper/ProductFeedbackMapper.java                           [新]
  dto/{FeedbackCreateDTO,FeedbackQuery,AdminFeedbackQuery,FeedbackHandleDTO}.java [新]
  vo/{FeedbackVO,FeedbackCreateResultVO,FeedbackHandleResultVO}.java              [新]
  service/FeedbackService.java + impl/FeedbackServiceImpl.java                    [新]
  controller/feedback/FeedbackController.java                                     [新]
  controller/admin/AdminController.java                                           [改]
  common/ResultCode.java                                                          [改]
  util/MessageTemplateUtil.java                                                   [改]
frontend/src/
  api/feedback.js                                             [新]
  api/{admin.js,file.js}                                      [改]
  router/routes.js                                            [改]
  views/feedback/index.vue                                    [新]
  views/admin/feedback.vue                                    [新]
  views/profile/index.vue                                     [改]
  components/NlStatusChip.vue                                 [改]
  utils/format.js                                             [改]
docs/api/{15-product-feedback.md[新],README.md[改],10-file-upload.md[改]}
docs/design/design.md  GLOSSARY.md  CHANGELOG.md  docs/agents/FRONTEND_CONTRACT.md [改]
backend/src/test/java/org/company/nianglin/
  constant/FeedbackStatusTest.java                            [新]
  service/FeedbackServiceTest.java                            [新]
  security/AdminAccessMatrixTest.java                         [改]  ← 加 ADMIN 端点越权用例
  security/PermissionMatrixTest.java                          [改]  ← 加 4 角色 × 反馈端点
```

## Steps

- [x] 0. 切分支 `feature/feedback-product-suggestion`
- [x] 1. 写 `docs/api/15-product-feedback.md`（接口先行，AGENTS.md §8）+ 同步 `docs/api/README.md` 索引与错误码表
- [x] 2. `V13__product_feedback.sql`（建表 + `sys_file.biz_type` 注释），确认 Flyway 能跑通
- [x] 3. 常量层：`FeedbackType` / `FeedbackStatus` / `FileBizType.FEEDBACK` / `MessageType.FEEDBACK_HANDLED` / `MessageBizType.FEEDBACK` / `OperType.HANDLE_FEEDBACK` / `OperTargetType.FEEDBACK` / `ResultCode.FEEDBACK_NOT_FOUND(12001)`
- [x] 4. 实体 + Mapper + DTO + VO
- [x] 5. `FeedbackServiceImpl`：create / myList / detail / adminList / handle（含敏感词与医疗红线词表、条件更新闸门、站内信、oper log）
- [x] 6. Controller 两个（客户端 + `AdminController` 追加），补 `@Operation` / `@Tag` / `@Schema`
- [x] 7. `MessageTemplateUtil` 三个 switch 补 `FEEDBACK_HANDLED`
- [x] 8. 后端测试：`FeedbackStatusTest`（状态机矩阵）、`FeedbackServiceTest`（敏感词 / 图片上限 / 归属 / 终态才发信）、`PermissionMatrixTest` + `AdminAccessMatrixTest` 增补
- [x] 9. 前端 `api/feedback.js` + `api/admin.js` + `api/file.js` JSDoc
- [x] 10. `views/feedback/index.vue`（提交 + 我的反馈）
- [x] 11. `views/admin/feedback.vue`（列表 + 处理弹窗，照抄 complaint.vue 骨架）
- [x] 12. `routes.js` 两条路由 + `profile/index.vue` 入口 + `NlStatusChip` scope + `format.js` 文案表
- [x] 13. 文档收尾：`10-file-upload.md`、`design.md`、`GLOSSARY.md`、`CHANGELOG.md`、`FRONTEND_CONTRACT.md`
- [x] 14. 过 `docs/agents/interaction-checklist.md`（空态 / 加载态 / 错误态 / 字段级校验 / in-flight 锁 / ElMessage 统一）
- [x] 15. 跑全部检查（见下）
- [ ] 16. 向用户报出建议标签名 `vX.Y` + message，**等确认后**再 `git tag -a` / `git push origin`

## Verification

```bash
# 后端（与 CI 同命令）
cd backend && mvn -B verify

# 前端（与 CI 同命令）
cd frontend && pnpm run lint:check && pnpm run test && pnpm run build

# 静态门禁（纯标准库，不装依赖）
python tools/ai_flow/gate_check.py --only error-codes
python tools/ai_flow/gate_check.py --only md --all-md
python tools/check_secrets.py          # 截图示例 URL 不得含真实路径 / 文件名
```

手工验收（本地起 MySQL + Redis + 后端 8080 + 前端 5173）：

1. 家属登录 → 「我的」→「产品反馈」→ 选分类 + 10 字以上正文 + 传 2 张截图 → 提交成功 → 出现在「我的反馈」`PENDING`
2. 正文写「垃圾」「滚」→ 提交被拒 `6003`，错误落在正文字段下方（不是顶部 toast）
3. 传第 4 张截图 → 前端拦下并提示「最多 3 张」
4. 传 `.txt` 改名为 `.jpg` → `400`（magic bytes 判定失败）
5. 陪诊员登录 → 同样路径可提交；**老人登录 → 路由守卫拦截，`/feedback` 不可达**
6. 管理员后台 →「产品反馈」→ 筛选 `PENDING` → 处理为「处理中」（不填说明 → 字段级报错）
7. 处理说明写「建议您调整剂量」→ `6003`（医疗红线词表命中，**这是本功能最关键的一条**）
8. 处理为「已采纳」+ 合法说明 → 提交人收到站内信「反馈处理结果」，点击跳 `/feedback`
9. 提交人的「我的反馈」里该条状态变 `RESOLVED` 并**显示处理说明**；`PROCESSING` 阶段**不显示**
10. 对已结案记录再点处理 → `409`（状态不可回退）
11. 其他用户 `GET /api/feedback/{他人id}` → `12001`
12. 操作日志页可见 `HANDLE_FEEDBACK` 记录，含旧值 → 新值与操作人

## 风险与权衡（已接受的代价）

- **`handleResult` 每次流转覆盖**：`PROCESSING` 时写的说明会被终态覆盖，属刻意 —— 用户只看最终结论，中间草稿不外泄（与投诉 `ComplaintVO` 同款）。
- **不支持撤回 / 编辑**：用户打错字只能重提，管理端可能出现两条近似反馈。换来的是「处理永远基于一个不会变的文本」。
- **不做限流**：恶意刷屏靠人工处理。低频功能，限流基建的成本高于收益。
- **`keyword` 搜提交人姓名要两段查**（先 `sys_user` 模糊查 id 集合，再进 `product_feedback`），与 `AdminComplaintQuery` 同一手法，会在 `idx_submitter` 上走索引。
- **监管空白**：`images` 不做 `sys_file.uploader_id` 归属校验（与投诉 `evidence` 同口径）。有人可以填他人 URL；收益是零额外代码，风险是伪造截图 —— 但截图本就是用户自证材料，仲裁时同样要人工核验。
