# Tickets · E4 评价公信力闭环

> **版本**：v1.0 · 2026-09-25
> **依赖 Spec**：[`E4-review-credibility.md`](./E4-review-credibility.md)
> **流程约定**：每张 ticket 走 TDD（red → green → refactor）；本表已按依赖排序，串行执行

---

## T0 · 常量层与配置（无外部依赖，串行起点）

| 项 | 内容 |
|---|---|
| 范围 | `ResultCode.REVIEW_NOT_FOUND` (6005) + `REVIEW_ALREADY_REPLIED` (6006)；`ComplaintType.REVIEW_APPEAL`；`MessageType.REVIEW_REPLIED` / `REVIEW_APPEAL_SUBMITTED` / `REVIEW_INVALIDATED`；`OperType.REVIEW_RULING`；`OperTargetType.REVIEW`；`MessageTemplateUtil` 新增 3 个 case + `linkUrl` 3 条；`application.yml` 加 `nianglin.review.appeal-deadline-days: 15` |
| 验收 | 单元/集成测试不引用这些常量也能继续（仅定义），`mvn compile` 全绿 |
| 文件 | `backend/src/main/java/org/company/nianglin/{common/ResultCode.java,constant/{ComplaintType,MessageType,OperType,OperTargetType}.java,util/MessageTemplateUtil.java}`；`backend/src/main/resources/application.yml` |
| 工作流 | **直接实现**（非业务逻辑，无须 red-green）；完成后 `mvn compile` 验证 |

---

## T1 · FR-01 陪诊员回复评价（TDD 全程）

| 项 | 内容 |
|---|---|
| RED | `ReviewServiceReplyTest`（Mockito）覆盖：① 正常回复 ② 已回复 → `REVIEW_ALREADY_REPLIED` ③ 非本陪诊员 → `ORDER_NO_PERMISSION` ④ 敏感词 → `CONTENT_SENSITIVE` ⑤ 评价不存在 → `REVIEW_NOT_FOUND` ⑥ 并发穿透（lambdaUpdate 影响行 0）⑦ 通知被正确调用（verify MessageService.send with REVIEW_REPLIED + 30 字 digest）|
| GREEN | `ReviewService.reply(Long, ReviewReplyDTO)`：`reviewMapper.selectById` → 校验 → `update(null, lambdaUpdate.eq(id).isNull(companionReply).set(companionReply, content).set(replyTime, now))` → `messageService.send` |
| 新增文件 | `dto/ReviewReplyDTO.java`、`vo/ReviewReplyResultVO.java`、`service/impl/ReviewServiceImpl.reply`、`controller/review/ReviewController.reply`、`service/ReviewService.reply`（接口方法）|
| 越权矩阵扩展 | `security/ReviewAccessMatrixTest` 新增 8 条回复越权用例（参 spec §九）|
| REFACTOR | 抽出 `truncateForNotify(String, int)` 静态方法到 `MessageDigestUtil`（或内联在 Service，与既有代码风格保持一致；不抽以免引入新工具类）|
| 验收 | `mvn test -Dtest='ReviewServiceReplyTest,ReviewAccessMatrixTest'` 全绿 |

---

## T2 · FR-02 评价申诉通道（TDD 全程）

| 项 | 内容 |
|---|---|
| RED | `ComplaintServiceAppealTest`（Mockito）覆盖：① 正常申诉（`REVIEW_APPEAL` 类型 + reviewId 通过）② 评价与订单不一致 → `3004` ③ 评价不属于本陪诊员 → `3004` ④ 评价不存在 → `6005` ⑤ reviewId 缺失 → `400` ⑥ 超时 → `409` ⑦ 同 reviewId 已有未结案 → `409` ⑧ 通知文案参数正确（verify `notifyAdmins/notifyTarget` 收到 `submitterName=companion`, `typeLabel="评价申诉"`）|
| GREEN | `ComplaintCreateDTO.reviewId` 新增可选字段（仅 `REVIEW_APPEAL` 必填）；`ComplaintServiceImpl.create` 加 `if (type == REVIEW_APPEAL) { … }` 分支；`ComplaintMapper.countOpenAppealByReviewId` 新方法；`@Value("${nianglin.review.appeal-deadline-days:15}") int appealDeadlineDays` 注入 |
| 越权矩阵扩展 | 6 条申诉越权（注意带合法 payload：`{ orderId: 1001, type: "REVIEW_APPEAL", content: "...", reviewId: 1001 }`）|
| REFACTOR | 将申诉校验抽取为 `validateReviewAppeal(review, dto, complainantId)` 私有方法，控制嵌套层级 |
| 验收 | `mvn test -Dtest='ComplaintServiceAppealTest,ReviewAccessMatrixTest'` 全绿 |

---

## T3 · FR-03 管理员有效性裁定（TDD 全程）

| 项 | 内容 |
|---|---|
| RED | `ReviewRulingServiceTest`（Mockito）覆盖：① 正常裁定（isValid=false + reason=20 字）② 已裁定过 → `409` ③ isValid=true → `400` ④ reason < 10 字 → `400` ⑤ 评价不存在 → `6005` ⑥ 乐观条件失败（update rows=0）→ `409` ⑦ `refreshCompanionScore` 被调用（verify）⑧ 双收件人通知（verify messageService.send × 2 with REVIEW_INVALIDATED）⑨ `admin_oper_log` 被写入（verify `writeOperLog` with `OperType.REVIEW_RULING, OperTargetType.REVIEW, target_id=reviewId, reason=...`）|
| GREEN | `dto/ReviewRulingDTO.java`（`@AssertTrue isValid=false` + `reason 10..200`）；`vo/ReviewRulingResultVO.java`；`AdminService.reviewValidity` + `AdminServiceImpl.reviewValidity`；`AdminController.reviewValidity` |
| REFACTOR | 把 "取评价 + 乐观更新 + 重算 + 日志 + 通知" 5 步封装成 1 个事务方法，私有方法清晰分块 |
| 越权矩阵扩展 | 4 条裁定越权（FAMILY/COMPANION/ELDER 调 `/api/admin/review/{id}/validity` → 403；ADMIN 重复裁定 → 409）|
| 验收 | `mvn test -Dtest='ReviewRulingServiceTest,ReviewAccessMatrixTest'` 全绿 |

---

## T4 · ReviewVO 字段扩展与读口径固化

| 项 | 内容 |
|---|---|
| 范围 | `ReviewVO` 加 `replyTime: LocalDateTime` + `isValid: Boolean`；`ReviewVO.of(...)` 装配这两个字段；既有 3 处 `is_valid=1` 过滤（`ReviewServiceImpl:193`、`ReviewReadMapper:49,61`、`StatisticsMapper:193,195`）**零改动**（已对齐终态口径，固化契约即可）|
| 验收 | `ReviewVOTest`（新增）覆盖：① 默认 `isValid=true` ② `isValid=0` 时返回 `false` ③ `replyTime` null 时 Jackson `non_null` 省略 ④ `replyTime` 非空时正确序列化 |
| 文件 | `vo/ReviewVO.java`、`service/impl/ReviewServiceImpl.toVO/buildVO`（无新增字段方法，零改动；既有 buildVO 直接走 of 的扩展装配）|
| 工作流 | 写测试 → 改 ReviewVO → mvn test |

---

## T5 · 前端 4 页 + api 封装

| 项 | 内容 |
|---|---|
| 范围 | `api/review.js` 加 `replyReview(id, {content})` + `rulingReview(id, {isValid, reason})`；新增 `views/companion/reviews.vue`（陪诊员收到的评价 + 行内回复表单，5–200 字）；新增 `views/admin/review-audit.vue`（评价巡查列表 + 主动裁定对话框）；`views/family/order-detail.vue` 在评价卡片下加回复区 + 无效标注；`views/admin/complaint.vue` 在 `REVIEW_APPEAL` 行加关联评价摘要 + 「裁定无效」按钮；`router/routes.js` 加 `companion/reviews` + `admin/review-audit` |
| 工作流 | **前端先 mock，后端就绪后切真接口**（按 AGENTS.md §3.10）；Component 走 `<script setup>` + Element Plus；适老化在 `elderly.scss` 已有的可点击高度 ≥ 48px 基础上复用 |
| 验收 | `pnpm lint` 0 警告；手动走查 4 个页面；不在 dev 服务器跑通即可交付（演示场景）|

---

## T6 · 文档同步

| 项 | 内容 |
|---|---|
| 范围 | `docs/api/06-review-complaint.md`（投诉类型表 + ComplaintCreateDTO 字段 + ReviewVO 字段 + 错误码 6005/6006 + POST /api/review/{id}/reply 章节）；`docs/api/08-admin.md`（评价裁定端点 + OperType/OperTargetType 枚举）；`docs/api/07-message.md`（3 个消息类型 + 3 条文案）；`docs/api/README.md` §4.7 错误码表 |
| 工作流 | 与 T0–T4 同步；commit 前一次性同步 |
| 验收 | `rg "REVIEW_APPEAL|REVIEW_REPLIED|REVIEW_INVALIDATED|6005|6006|REVIEW_RULING" docs/api` 命中预期位置 |

---

## T7 · e2e 演示脚本

| 项 | 内容 |
|---|---|
| 范围 | `tools/e2e/e2e_review_appeal.py`（Python + requests，覆盖 PRD §13.3 5 条断言）；自带数据清理（事务或 delete）；与既有 `e2e_*.py` 同目录同风格 |
| 工作流 | 后端全绿后写；本地 MySQL/Redis 起服务即可跑 |
| 验收 | 5 条断言全绿；脚本耗时 < 30s |

---

## T8 · 收口与回归

| 项 | 内容 |
|---|---|
| 范围 | `mvn verify` 全量跑（确认 517 + 新增 ≥ 全绿）；code-review skill 走一遍（commit 前自检）；按本仓库 git 规范分 3–4 个 commit（常量 / Service / Controller+Test / Docs+FE），commit message 用 PowerShell here-string + `git commit -F -` |
| 工作流 | `mvn verify` → `git diff --stat` → `code-review` skill → 分 commit |
| 验收 | 全部 commit `main` 分支可合入；CI 全绿 |

---

## 总览依赖图

```
T0 常量 ─┬─> T1 回复 ──┐
        ├─> T2 申诉 ──┼─> T4 VO 字段 ──> T5 前端 ──> T6 文档 ──> T7 e2e ──> T8 收口
        └─> T3 裁定 ──┘
```

T1 / T2 / T3 之间独立可并行（不同 Service），但为节省 review effort 串行；T4 在 T1-T3 全绿后做（VO 字段统一）。