# Spec · E4 评价公信力闭环（回复 / 申诉 / 有效性裁定）

> **版本**：v1.0 · 2026-09-25
> **PRD**：[`docs/prd/PRD-E4-评价公信力闭环.md`](../prd/PRD-E4-评价公信力闭环.md)
> **本 spec 范围**：把 PRD 翻译成**工程契约**，补足 PRD 未规定但实现必须的细节（错误码段位、配置命名、Mapper SQL 形态、状态机口径）；不复述业务背景与口径冲突判断（已在 PRD 中定）。
> **目标读者**：开发 B / 测试 C / 前端 A
> **状态**：待评审 → 进入开发

---

## 一、模块拆分（与 docs/plan/plan.md / AGENTS.md §0 一致）

| 子模块 | 内容 | 复用资产 | 新增 |
|---|---|---|---|
| M7-Reply | 陪诊员回复评价 | `order_review.companion_reply` / `reply_time`（V1 已建）、`SensitiveWordUtil` | `POST /api/review/{id}/reply`、`ReviewService.reply()`、`ReviewReplyDTO` |
| M7-Appeal | 评价申诉（复用投诉通道） | `complaint` 表、`ComplaintService.create` 推导逻辑、`FileBizType.COMPLAINT` | `ComplaintType.REVIEW_APPEAL`、`ComplaintCreateDTO.reviewId` 可选字段 |
| M7-Ruling | 管理员有效性裁定 | Review module 内部评分同步、`MessageService`（M8）、共享审计 module（M9） | `POST /api/admin/review/{id}/validity`、`ReviewService.reviewValidity()`、`ReviewRulingDTO` |
| M7-Notify | 三类通知 | `MessageTemplateUtil` | `MessageType.REVIEW_REPLIED` / `REVIEW_APPEAL_SUBMITTED` / `REVIEW_INVALIDATED` |
| M7-Read | 无效评价读口径固化 + ReviewVO 字段透出 | `ReviewVO` 既有 | `ReviewVO.isValid` / `replyTime` |
| Frontend | 4 个页面 | 既有 NlCard/NlPageShell | `companion/reviews.vue`、`family/order-detail.vue` 回复区（已交付）；`admin/review-audit.vue`、`admin/complaint.vue` 裁定入口与共用组件 `components/admin/ReviewRulingDialog.vue` **均已交付**（2026-11-25，见 §10.1） |

---

## 二、接口契约（PRD §七 + §11 展开）

### 2.1 POST /api/review/{id}/reply · 陪诊员回复评价

| 项 | 规格 |
|---|---|
| 角色门槛 | `@PreAuthorize("hasRole('COMPANION')")` |
| 路径变量 | `id` Long（评价 ID） |
| 请求体 | `{ "content": String, 5..200 chars }` |
| 成功响应 | `Result<{ reviewId, companionReply, replyTime }>` 简单 VO（复用既有 `ReviewCreateResultVO` 形态或新增 `ReviewReplyResultVO`）|
| 错误码 | `6005` 评价不存在 · `6006` 已回复 · `6003` 敏感词 · `3004` 非该评价的陪诊员 · `403` FAMILY/ADMIN（注解）· `403` ELDER（拦截器）|
| 事务边界 | `@Transactional(rollbackFor = Exception.class)`，事务内：① 校验评价 + 归属 ② 写 `companion_reply` + `reply_time` ③ 通知家属 |
| 通知 | `MessageType.REVIEW_REPLIED` → 评价家属；bizId=orderId；title=`"陪诊员已回复您的评价"`；content=`"陪诊员回复了您对订单 %s 的评价（%s…）"`，其中 `s…` 为回复前 30 字摘要 |
| 评分影响 | **不**触发 Review module 内部评分同步（回复不参与聚合）|

### 2.2 POST /api/complaint · 评价申诉（复用通道）

| 项 | 规格 |
|---|---|
| 角色门槛 | 无 `@PreAuthorize`（与现有投诉一致）|
| 请求体变更 | `ComplaintCreateDTO` 新增可选 `reviewId: Long`；`type=REVIEW_APPEAL` 时必填 |
| 业务规则 | `type=REVIEW_APPEAL` 走专门分支：① 评价存在 + `review.companionId == me` + `review.orderId == dto.orderId`（不匹配 `3004`）② 评价创建 ≤ 15 日（`@Value("${nianglin.review.appeal-deadline-days:15}")` 注入 AppealProperties，超时 `409 + "评价申诉须在评价提交后 15 日内发起"`）③ 同 reviewId 存在未结案 `REVIEW_APPEAL` → `409`（"该评价的申诉正在处理中"）|
| 通知 | 沿用 `notifyAdmins` + `notifyTarget`，但文案区分：`typeLabel` 取 `"评价申诉"`；`submitterName` 是陪诊员姓名（脱敏）|
| 状态机 | 完全复用 `ComplaintStatus`；裁定与申诉解耦（管理员先裁定，再单独结案投诉）|

### 2.3 POST /api/admin/review/{id}/validity · 管理员裁定

| 项 | 规格 |
|---|---|
| 角色门槛 | 类级 `@PreAuthorize("hasRole('ADMIN')")`（继承 AdminController）|
| 路径变量 | `id` Long（评价 ID）|
| 请求体 | `{ "isValid": Boolean（仅 false）, "reason": String, 10..200 chars }` |
| 成功响应 | `Result<{ reviewId, isValid, companionId }>` 用于前端跳转陪诊员评分页 |
| 错误码 | `6005` 评价不存在 · `409` 已裁定过（`is_valid=0`）· `400` 仅接受 `isValid=false`（防误操作恢复有效）· `400` reason < 10 字 |
| 事务边界 | 一个事务内：① `order_review.is_valid = 0`（带乐观条件 `eq(is_valid, 1)`）② Review module 内部评分快照刷新 + Redis 删除 ③ 通过共享审计 module 写 `admin_oper_log`（`OperType.REVIEW_RULING` + `OperTargetType.REVIEW`）④ 通知家属 + 陪诊员 |
| 通知 | `MessageType.REVIEW_INVALIDATED` → 家属 + 陪诊员；bizId=orderId；content=`"订单 %s 中的一条评价被平台裁定为无效（%s…）"` |
| 操作日志 | `target_type=REVIEW`、`target_id=reviewId`、`target_desc="评价 #" + reviewId`、`before="IS_VALID:1"`、`after="IS_VALID:0"`、`reason`（写入 reason 字段）|

### 2.4 GET /api/review/order/{orderId} / /api/review/companion/{id}（既有）

无接口路径变更，仅 `ReviewVO` 字段扩展（§四）；`byCompanion` 已有 `is_valid=1` 过滤（零改动）。

### 2.5 GET /api/admin/review · 评价巡查列表（2026-11-25 补做）

| 项 | 规格 |
|---|---|
| 角色门槛 | 类级 `@PreAuthorize("hasRole('ADMIN')")`（继承 AdminController）|
| 查询参数 | `companionId` / `minScore` / `maxScore` / `isValid`（三态）/ `keyword` / `startDate` / `endDate` + 分页 |
| 成功响应 | `Result<PageResult<AdminReviewVO>>`；`AdminReviewVO` 字段与 `ReviewVO` 同构，但有效性字段名统一为 `isValid` |
| **为什么要新端点** | `GET /api/review/companion/{id}` 是「公开口碑」链路，**硬编码只返回有效评价**（`ReviewServiceImpl#byCompanion` 里的 `eq(is_valid, 1)`）。管理员用它**永远看不到已被裁定无效的评价** —— 而「裁完就从列表里消失」的评价等于从来没被巡查过。该端点不在 `/api/admin/**` 下，**不能**改它（`SecurityConfig` 只对 `/api/admin/**` 做 `hasRole(ADMIN)`，改了会扩大公众可见性边界）|
| `isValid` 三态 | 不传 = 全部；`true` = 只看有效（口径为 `is_valid = 1` **或** `is_valid IS NULL`，与 `ReviewVO.of` 的老数据兜底一致）；`false` = 只看已被裁定无效 |
| 姓名脱敏 | 列表一律脱敏（订单详情那条「全名豁免」只对详情页成立）|
| 排序 | 固定「评价时间倒序 → id 倒序」，不开放 `sortField` |
| 零迁移 | 全部条件落在 `order_review` 既有列上 |
| 错误码 | `400` 日期区间倒置 / 评分越界 · `403` 非 ADMIN · `401` 未登录 |

---

## 三、数据契约（PRD §十 + 字段表）

### 3.1 数据库（**零迁移**，沿用 V1 已建字段）

| 表 | 字段 | 类型 | 用法 |
|---|---|---|---|
| `order_review` | `companion_reply` | text | 回复正文（首期写入）|
| `order_review` | `reply_time` | datetime | 回复时间 |
| `order_review` | `is_valid` | tinyint | `1`=有效 / `0`=管理员裁定无效 |
| `complaint` | `type` | varchar | 增加枚举值 `REVIEW_APPEAL`（无 DDL）|

### 3.2 `ReviewVO` 增量字段

| 字段 | 类型 | 何时填 | 来源 |
|---|---|---|---|
| `companionReply`（已有）| String | 始终 | `entity.getCompanionReply()` |
| `replyTime` | LocalDateTime | 始终（null 表示未回复）| `entity.getReplyTime()` |
| `isValid` | Boolean | 始终 | `entity.getIsValid() == 0 ? false : true` |

> 不存在的字段（如未回复时 `replyTime`）走 `Jackson non_null` 自动省略，前端按字段缺失判断。

### 3.3 `ComplaintVO` 增量字段（**不新增**）

`complaint` 关联评价采用**反查方案**：管理端看申诉详情时，根据 `orderId + type=REVIEW_APPEAL + complainant_id` 通过 `OrderReviewMapper.selectOne` 反查一条评价；评价摘要走新增 `getReviewByOrderAdmin(orderId)` Service 方法（管理员可见），无需 `V4` 加列。

> 选择反查的理由：零 DDL 与「E4 零迁移」承诺一致；reviewId 与 complaintId 在 `complaint.orderId + complainant_role=COMPANION` 唯一确定，单次 SELECT O(1) 命中。

---

## 四、常量与配置

### 4.1 ResultCode（`common/ResultCode.java`）

```java
REVIEW_NOT_FOUND(6005, "评价不存在"),
REVIEW_ALREADY_REPLIED(6006, "该评价已回复，不能重复回复"),
```

插入位置：`/ 6xxx 评价与投诉 /` 段 `COMPLAINT_NOT_FOUND` 之后。

### 4.2 ComplaintType（`constant/ComplaintType.java`）

```java
REVIEW_APPEAL("评价申诉"),
```

插入位置：放在 `OTHER` 之前（让 `OTHER` 仍保持兜底；详见 enum）。

### 4.3 MessageType（`constant/MessageType.java`）

```java
REVIEW_REPLIED("评价被回复", MessageBizType.ORDER),
REVIEW_APPEAL_SUBMITTED("收到评价申诉", MessageBizType.COMPLAINT),
REVIEW_INVALIDATED("评价被裁定无效", MessageBizType.ORDER),
```

插入位置：放在 `COMPLAINT_HANDLED` 之后、`SYSTEM_NOTICE` 之前。

### 4.4 OperType（`constant/OperType.java`）

```java
REVIEW_RULING("评价有效性裁定"),
```

### 4.5 OperTargetType（`constant/OperTargetType.java`）

```java
REVIEW("评价"),
```

### 4.6 MessageTemplateUtil

新增 3 case（详见 §五），并补 `linkUrl` 路由：
- `REVIEW_REPLIED` → `/family?orderId={bizId}`
- `REVIEW_APPEAL_SUBMITTED` → `/admin?complaintId={bizId}`
- `REVIEW_INVALIDATED` → `/family?orderId={bizId}`

### 4.7 application.yml

```yaml
nianglin:
  review:
    # 评价提交后多少日内可发起申诉（REVIEW_APPEAL）
    appeal-deadline-days: 15
```

注入方式：`@ConfigurationProperties("nianglin.review")` 单字段类 `ReviewProperties`，或直接 `@Value` 注入 `ReviewServiceImpl` / `ComplaintServiceImpl`（**推荐 `@Value`**：仅一个字段，无须单独 properties 类）。

---

## 五、通知文案（`MessageTemplateUtil`）

### 5.1 REVIEW_REPLIED

```java
case REVIEW_REPLIED -> "陪诊员回复了您对订单 %s 的评价（%s…），点击查看完整回复。".formatted(
    value(p, "orderNo"), truncate(emptyIfMissing(p, "replyDigest"), 30));
```

### 5.2 REVIEW_APPEAL_SUBMITTED

```java
case REVIEW_APPEAL_SUBMITTED -> "陪诊员 %s 就订单 %s 发起评价申诉（%s），请及时处理。".formatted(
    value(p, "submitterName"), value(p, "orderNo"), value(p, "typeLabel"));
```

参数：`submitterName`=陪诊员脱敏名（调用方负责，工具类不再脱敏），`typeLabel`= `"评价申诉"`。

### 5.3 REVIEW_INVALIDATED

```java
case REVIEW_INVALIDATED -> "平台裁定订单 %s 中的一条评价为无效（%s…），评分已重算。".formatted(
    value(p, "orderNo"), truncate(emptyIfMissing(p, "reasonDigest"), 30));
```

`reasonDigest` 由 ReviewService.reviewValidity 传 `reason.substring(0, Math.min(30, reason.length()))`。

---

## 六、Mapper 变更

### 6.1 OrderReviewMapper（已有，**零新增方法**）

回复 / 裁定都通过 `update(null, lambdaUpdate.set(...).eq(id).eq(条件))` 模式，与现有 `create()` 同款，无需新增方法。

### 6.2 ComplaintMapper（**新增 1 个方法**）

```java
/** 同一 reviewId 已有未结案 (PENDING/PROCESSING) 的 REVIEW_APPEAL 数 */
long countOpenAppealByReviewId(@Param("reviewId") Long reviewId);
```

SQL：

```sql
SELECT COUNT(*) FROM complaint
WHERE type = 'REVIEW_APPEAL'
  AND status IN ('PENDING', 'PROCESSING')
  AND order_id = (SELECT order_id FROM order_review WHERE id = #{reviewId})
```

> 反查评价→订单的写法避免在 `complaint` 表加 `review_id` 列，与「零迁移」一致；
> 子查询命中 `order_review.id` 主键 + `order_id` 索引，O(1) 一次扫描。

### 6.3 共享审计 module（已有 `AdminOperLogMapper`，新增 `OperLogRecorder` seam）

通过 `OperLogRecorder` 统一组装并写入 `admin_oper_log`；Admin 原有动作与 Review 裁定共用该 module。

---

## 七、Service 内部实现要点

### 7.1 ReviewServiceImpl.reply(Long reviewId, ReviewReplyDTO dto)

伪代码：

```java
@Transactional(rollbackFor = Exception.class)
public ReviewReplyResultVO reply(Long reviewId, ReviewReplyDTO dto) {
    LoginUser me = SecurityUtils.currentUser();
    OrderReview review = reviewMapper.selectById(reviewId);
    if (review == null) throw new BusinessException(REVIEW_NOT_FOUND);
    if (!Objects.equals(review.getCompanionId(), me.userId()))
        throw new BusinessException(ORDER_NO_PERMISSION);
    if (StringUtils.hasText(review.getCompanionReply()))
        throw new BusinessException(REVIEW_ALREADY_REPLIED);
    String content = dto.getContent().trim();
    String hit = SensitiveWordUtil.firstHit(content);
    if (hit != null) throw new BusinessException(CONTENT_SENSITIVE, ...);

    LocalDateTime now = LocalDateTime.now();
    int rows = reviewMapper.update(null, Wrappers.<OrderReview>lambdaUpdate()
            .eq(OrderReview::getId, reviewId)
            .isNull(OrderReview::getCompanionReply)   // 并发穿透防御
            .set(OrderReview::getCompanionReply, content)
            .set(OrderReview::getReplyTime, now));
    if (rows == 0) throw new BusinessException(REVIEW_ALREADY_REPLIED); // 抢锁失败

    // 通知家属
    Map<String, Object> params = new HashMap<>();
    params.put("orderNo", review.getOrderNo());
    params.put("replyDigest", content.length() > 30 ? content.substring(0, 30) + "…" : content);
    messageService.send(review.getFamilyId(), MessageType.REVIEW_REPLIED, review.getOrderId(), params);

    log.info("评价已回复 | reviewId={} | companionId={}", reviewId, me.userId());
    return ReviewReplyResultVO.of(reviewId, content, now);
}
```

要点：
- `isNull(companionReply)` 兜住并发穿透（与 `create()` 的 DuplicateKey 风格一致）。
- 事务内调用 `messageService.send`：阶段一保持当前异常外抛与事务回滚语义；通知可靠性调整留待阶段二。

### 7.2 ComplaintServiceImpl.create() · REVIEW_APPEAL 分支

伪代码（在现有 `create()` 中插入，仅 `type=REVIEW_APPEAL` 时执行）：

```java
if (type == ComplaintType.REVIEW_APPEAL) {
    Long reviewId = dto.getReviewId();
    if (reviewId == null) throw new BusinessException(PARAM_ERROR, "评价申诉必须传入 reviewId");
    OrderReview review = orderReviewMapper.selectById(reviewId);
    if (review == null) throw new BusinessException(REVIEW_NOT_FOUND);
    if (!Objects.equals(review.getCompanionId(), complainantId))
        throw new BusinessException(ORDER_NO_PERMISSION);
    if (!Objects.equals(review.getOrderId(), order.getId()))
        throw new BusinessException(ORDER_NO_PERMISSION);
    if (review.getCreateTime().plusDays(appealDeadlineDays).isBefore(LocalDateTime.now()))
        throw new BusinessException(CONFLICT, "评价申诉须在评价提交后 " + appealDeadlineDays + " 日内发起");

    long openAppeal = complaintMapper.countOpenAppealByReviewId(reviewId);
    if (openAppeal > 0)
        throw new BusinessException(CONFLICT, "该评价的申诉正在处理中");
}
```

依赖注入：`OrderReviewMapper orderReviewMapper`（新增 final 字段）+ `@Value("${nianglin.review.appeal-deadline-days:15}") int appealDeadlineDays`。

### 7.3 ReviewServiceImpl.reviewValidity(Long reviewId, ReviewRulingDTO dto)

伪代码：

```java
@Transactional(rollbackFor = Exception.class)
public ReviewRulingResultVO reviewValidity(Long reviewId, ReviewRulingDTO dto) {
    LoginUser currentUser = SecurityUtils.currentUser();
    if (!RoleConstants.ADMIN.equals(currentUser.role()))
        throw new BusinessException(FORBIDDEN);
    if (Boolean.TRUE.equals(dto.getIsValid()))
        throw new BusinessException(PARAM_ERROR, "本接口仅支持裁定为无效，恢复有效请走线下流程");
    String reason = dto.getReason().trim();
    if (reason.length() < 10 || reason.length() > 200)
        throw new BusinessException(PARAM_ERROR, "裁定理由长度应为 10–200 个字符");

    OrderReview review = orderReviewMapper.selectById(reviewId);
    if (review == null) throw new BusinessException(REVIEW_NOT_FOUND);
    if (Integer.valueOf(0).equals(review.getIsValid()))
        throw new BusinessException(CONFLICT, "该评价已被裁定无效");

    int rows = orderReviewMapper.update(null, Wrappers.<OrderReview>lambdaUpdate()
            .eq(OrderReview::getId, reviewId)
            .eq(OrderReview::getIsValid, 1)              // 乐观条件
            .set(OrderReview::getIsValid, 0));
    if (rows == 0) throw new BusinessException(CONFLICT, "该评价已被裁定无效（并发抢占）");

    refreshCompanionScore(review.getCompanionId());

    operLogRecorder.record(OperType.REVIEW_RULING, OperTargetType.REVIEW, reviewId,
            "评价 #" + reviewId, "IS_VALID:1", "IS_VALID:0", reason);

    String digest = truncateDigest(reason);
    Map<String, Object> params = new HashMap<>();
    params.put("orderNo", review.getOrderNo());
    params.put("reasonDigest", digest);
    messageService.send(review.getFamilyId(), MessageType.REVIEW_INVALIDATED, review.getOrderId(), params);
    messageService.send(review.getCompanionId(), MessageType.REVIEW_INVALIDATED, review.getOrderId(), params);

    log.info("评价已被裁定无效 | reviewId={} | companionId={} | adminId={}",
            reviewId, review.getCompanionId(), currentUser.userId());
    return ReviewRulingResultVO.of(reviewId, false, review.getCompanionId());
}
```

要点：
- **三件套同事务**（置位/重算/日志/通知）：Review implementation 内部评分同步与外层事务共同保证原子性；阶段一保持当前行为，Message module 的落库异常会向外传播并触发回滚，阶段二再单独处理通知可靠性。
- 乐观条件 `.eq(isValid, 1)` 与响应中 `if (rows == 0)` 翻译 `409`：挡住「在我之前已经有人裁定过」的并发场景。

---

## 八、Controller 端点（完整签名）

### 8.1 ReviewController（既有，加 1 个）

```java
@PreAuthorize("hasRole('COMPANION')")
@PostMapping("/{id}/reply")
public Result<ReviewReplyResultVO> reply(
        @PathVariable("id") Long reviewId,
        @Valid @RequestBody ReviewReplyDTO dto) {
    return Result.success("回复已提交", reviewService.reply(reviewId, dto));
}
```

### 8.2 ComplaintController（**零新增**）

`POST /api/complaint` 既有即可，仅 DTO 增加字段。

### 8.3 AdminController（既有，加 2 个）

```java
@PostMapping("/review/{id}/validity")
public Result<ReviewRulingResultVO> reviewValidity(
        @PathVariable("id") Long reviewId,
        @Valid @RequestBody ReviewRulingDTO dto) {
    return Result.success("裁定已生效", reviewService.reviewValidity(reviewId, dto));
}
```

```java
// 2026-11-25 补做：评价巡查列表（§2.5）
@GetMapping("/review")
public Result<PageResult<AdminReviewVO>> reviewList(@Valid AdminReviewQuery query) {
    return Result.success(adminService.reviewList(query));
}
```

---

## 九、测试矩阵（PRD §11.2 落地）

### 9.1 单测

| 类 | 覆盖 |
|---|---|
| `ReviewServiceReplyTest`（新）| 正常回复、已回复 `6006`、非本陪诊员 `3004`、敏感词 `6003`、评价不存在 `6005`、并发穿透（lambdaUpdate 返回 0 → `6006`）、通知发送（mock MessageService 收到 REVIEW_REPLIED）|
| `ReviewRulingServiceTest`（Review interface）| 正常裁定、已裁定 `409`、isValid=true `400`、reason <10 字符 `400`、评分刷新、审计记录、双收件人通知、通知异常传播特征 |
| `ComplaintServiceAppealTest`（新）| 正常申诉、超期 `409`、同 reviewId 已有未结案 `409`、reviewId 缺失 `400`、review 与订单不一致 `3004`、review 不属于本陪诊员 `3004`、评价不存在 `6005`|

### 9.2 越权矩阵（`ReviewAccessMatrixTest` 扩展）

| 用例组 | 现有 | 新增 | 验证点 |
|---|---|---|---|
| 回复越权 | 0 | **8 条** | 陪诊员 A 回复 B 的评价 `3004`；FAMILY/ELDER/ADMIN 调用 → 403（带合法 payload）|
| 申诉越权 | 0 | **6 条** | 同上 + 超时 + 重复申诉（各 1 条）|
| 裁定越权 | 0 | **4 条** | 三个业务角色打 `/api/admin/review/{id}/validity` 一律 403（过滤链预检，不依赖请求体）；重复裁定 `409`|

> 「带合法 payload」是 §11.2 / RK-05 的硬性要求：FAMILY/ELDER 用例必须提供能通过 `@Valid` 的合法 body（reviewId/非空 content），避免死在参数校验拿不到 403 假绿。

### 9.3 e2e 脚本（`tools/e2e/`）

> ✅ **已交付（2026-11-25）**：`tools/e2e/` 下的 e2e 脚本（**跨进程 + 真 HTTP + 真 MySQL + 真 Redis**）。
>
> 它补的是后端三个测试类（`ReviewRulingServiceTest` 裁定事务四件事 /
> `AdminControllerReviewTest` 裁定 HTTP 契约 / `ReviewAccessMatrixTest` 越权矩阵）
> **全是进程内**、盖不住的那一层：鉴权是真的、事务是真的、通知是真的、越权是真的 ——
> 尤其「回复 → 家属端 ≤ 3 秒收到站内信」这条，单测根本测不到。

按 PRD §13.3 演示清单 5 条断言（**实际实现的口径**）：

| # | PRD 原文 | 脚本里的判据 |
|---|---|---|
| 1 | 回复 → 家属端 ≤ 3 秒收到站内信 | 家属令牌轮询 `GET /api/message?type=REVIEW_REPLIED`，命中 `bizId=订单 ID` 的那条且耗时 ≤3s（实测 0.04s）|
| 2 | 裁定 → 立即查 score，与手工 SQL `AVG(score) WHERE is_valid=1 AND companion_id=?` 一致 | 接口 `averageScore` / `totalCount` **逐字同构**对手工 SQL（口径 `is_valid=1 AND deleted=0`）|
| 3 | 裁定后公开列表条数 −1，订单详情该评价带标注 | `GET /api/review/companion/{id}` 的 `total` 减 1；`GET /api/review/order/{id}` 的 `isValid=false` |
| 4 | 陪诊员 A 用 B 的评价 ID 回复 → `3004` | 用别的陪诊员令牌打本陪诊员的评价，**业务码**必须是 `3004`（不是 HTTP 状态码 —— 后端对这类越权返 HTTP 200 + 信封里的业务码）|
| 5 | 全站 grep 违禁词 → 空 | **扫描面限定**为 `backend/src/main` + `frontend/src` + `ai-service/app`，减去脚本内的**显式 allowlist**（每条带 why）|

> ⚠️ **第 5 条的口径与 PRD §13.3 原文不同（2026-11-25 交付时改的，PRD 已同步补注）**：
> PRD 原文写「全站 grep」，但词表本体（`medical-advice-words.txt`）、
> 守卫器（`ComplianceCheckUtil`）、反面断言用例（`elderHealth.test.js` / `data-loop.test.js`）
> **本身就含这些词** —— 逐字按「全站 grep → 空」实现必然假红。
> 所以改成「限定业务扫描面 + 显式 allowlist」。
> allowlist 是**文件级清单**而非「按目录跳过」：新增一个词表文件却忘了加进去，脚本会红并指路；
> 隐式豁免会让这条断言悄悄失效 —— 而它恰是防「医疗建议表述漏进产品」的最后一道网。

**夹具与自清**：脚本**自己造夹具**（往 `order_review` 直接插一条「未回复 + 属于某陪诊员」的评价，
挂在一条 COMPLETED 且**尚无评价**的订单上），跑完把这条及其副作用**全部删掉**
（夹具评价行、本次产生的站内信与审计日志行、`companion_profile` 的评分快照、
Redis 的评分/未读缓存、本次登录产生的 `sys_login_log` 行）。

> 不依赖种子数据的当前状态 —— 种子评价早已被回复过，拿种子行当「未回复」用，
> 脚本第二次跑就会撞上 `6006 已回复`。

**退出码**：0 = 五条断言全过；1 = 有断言失败；2 = 前置缺失（后端没起 / 口令没设 / 账号不存在）。

---

## 十、前端工程契约

### 10.1 新增文件

| 路径 | 角色 | 说明 | 状态 |
|---|---|---|---|
| `frontend/src/api/review.js` 扩展 | — | `replyReview(id, {content})` | ✅ 已交付 |
| `frontend/src/api/admin.js`（非 `review.js`） | — | `rulingReview(id, {isValid, reason})` —— **实际落在 `admin.js`**，与其它 `/api/admin/**` 封装同处 | ✅ 已交付 |
| `frontend/src/views/companion/reviews.vue` | 陪诊员 | 收到的评价列表 + 未回复的评价行内表单（5–200 字） | ✅ 已交付 |
| `frontend/src/router/routes.js` 扩展 | — | `companion/reviews` 一条 | ✅ 已交付 |
| `frontend/src/views/admin/review-audit.vue` | 管理员 | 评价巡查（按陪诊员 / 评分 / 有效性 / 时间筛选）+ 主动裁定对话框 | ✅ **已交付**（2026-11-25） |
| `frontend/src/router/routes.js` 扩展 | — | `admin/review-audit` 一条 | ✅ **已交付**（2026-11-25） |
| `frontend/src/components/admin/ReviewRulingDialog.vue` | — | **裁定对话框共用组件**（申诉联动与巡查页共用同一份）| ✅ **已交付**（2026-11-25） |

> ✅ **管理端 UI 缺口已补齐（2026-11-25）**：上一版这里记的是「未交付」——
> 后端 `POST /api/admin/review/{id}/validity` 已上线，`frontend/src/api/admin.js` 也封装了 `rulingReview`，
> 但**全仓没有任何 .vue 调用它**，管理端 UI 为零。本轮补齐两个入口：
>
> 1. **申诉联动**：`admin/complaint.vue` 的 `REVIEW_APPEAL` 行 → 「裁定无效」按钮。
>    评价 ID 的来源：`complaint` 表**没有** `review_id` 列（E4 零迁移），
>    用 `complaint.orderId` 反查 —— `GET /api/review/order/{orderId}` 的归属校验
>    `requireInvolved` 对 ADMIN 直接放行，管理员能查到该订单的评价。
> 2. **主动巡查**：新建 `admin/review-audit.vue`，走**新端点** `GET /api/admin/review`
>    （`docs/api/08-admin.md` §29）。为什么不能复用 `GET /api/review/companion/{id}`：
>    那条链路是「公开口碑」，硬编码只返回**有效**评价，管理员用它**永远看不到已被裁定无效的**——
>    而「裁完就从列表里消失」的评价等于从来没被巡查过。
>
> 两个入口的裁定动作**共用同一个对话框组件**（`components/admin/ReviewRulingDialog.vue`）——
> 裁定的口径、10–200 字校验、in-flight 锁必须两处完全一致，
> 否则管理员会靠回忆去判断哪个入口才是对的。

### 10.2 修改文件

| 路径 | 改动 |
|---|---|
| `frontend/src/views/family/order-detail.vue` | 评价卡片下渲染回复区（`v-if="review.companionReply"`）+ 无效标注（`v-if="review.isValid === false"`）| ✅ 已交付 |
| `frontend/src/views/admin/complaint.vue` | `REVIEW_APPEAL` 行展开关联评价摘要 + 「裁定无效」按钮（弹 dialog 调 `rulingReview`）| ✅ **已交付**（2026-11-25） |

### 10.3 mock 优先

按 `AGENTS.md §3.10`，新页面先用 `Promise.resolve({...})` mock 出响应结构，与后端契约对齐；后端就绪后切到真实接口即可。

---

## 十一、文档同步（PRD §13.1 step 7）

| 文档 | 改动 |
|---|---|
| `docs/api/06-review-complaint.md` | §一投诉类型表加 `REVIEW_APPEAL`；§五提交投诉请求体加 `reviewId`；§评价侧新增 POST /api/review/{id}/reply；§ReviewVO 字段加 `isValid` / `replyTime`；§错误码表加 6005/6006 |
| `docs/api/08-admin.md` | §10 投诉管理后追加 §X 评价裁定（POST /api/admin/review/{id}/validity）；§一操作类型枚举加 `REVIEW_RULING`；§一OperTargetType 加 `REVIEW` |
| `docs/api/07-message.md` | §一消息类型加 3 值；§三文案模板加 3 条 |
| `docs/api/README.md` | §4.7 错误码表加 6005/6006（与 ResultCode 同步）|

---

## 十二、不做什么（与 PRD §4.2 一致，单点列出落地点）

| 禁止 | 实现拦截点 |
|---|---|
| 物理删除或改写评价内容 | 不提供 DELETE / PUT 评价接口；Service 不开放 `setContent` / `setScore` |
| 恢复有效入口 | `reviewValidity` 接口 `isValid=true` 直接 `400` |
| 多次回复 | 一评一回复，`companion_reply` 非空即拒 |
| ELDER 写接口 | `ElderReadOnlyInterceptor` 拦截所有 POST/PUT/DELETE（既有）|
| IM / 多轮对话 | 不创建 `review_message` / `review_thread` 类表 |

---

## 十三、风险与回归点（PRD §14 落地）

- **RK-05（假红假绿）**：所有 403 用例必须携带合法 payload（`content: "abcdefghij..."` / `reviewId: 1001`），已在 §九硬性要求。
- **RK-06（评价↔申诉关联加列破坏零迁移）**：选反查方案（§3.3），评审如推翻需走 V4 + 拓展规划同步。
- **回归 `mvn verify`**：期望现有 517 测试 + 新增单测 + 越权扩展全绿；任何红测试必须修，不允许跳过。

---

> 本 spec 为开发执行输入。具体业务口径以 PRD 为准；本 spec 不修改 PRD，只补 PRD 未定的工程细节。