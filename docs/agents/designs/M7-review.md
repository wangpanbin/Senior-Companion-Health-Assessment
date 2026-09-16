# M7 设计评审 · 评价与投诉

- **模块**：M7（主路径 B）
- **评审时间**：2026-09-16（依落地代码反向补齐）
- **关联**：`docs/api/06-review-complaint.md`、`PLAN_BACKEND.md §8 M7`
- **代码**：`controller/review/ReviewController`、`controller/review/ComplaintController`、`service/impl/ReviewServiceImpl`、`service/impl/ComplaintServiceImpl`、`util/SensitiveWordUtil`

---

## 1 · 接口表

### 评价（`/api/review`）

| # | 方法 | 路径 | 角色 | 入参 | 出参 |
|---|---|---|---|---|---|
| 1 | POST | `/api/review` | FAMILY | `ReviewCreateDTO` | `ReviewCreateResultVO` |
| 2 | GET | `/api/review/order/{orderId}` | 登录用户 | path | `ReviewVO` |
| 3 | GET | `/api/review/companion/{companionId}` | 登录用户 | `ReviewQuery` | `PageResult<ReviewVO>` |
| 4 | GET | `/api/review/companion/{companionId}/score` | 登录用户 | path | `CompanionScoreVO` |

### 投诉（`/api/complaint`）

| # | 方法 | 路径 | 角色 | 入参 | 出参 |
|---|---|---|---|---|---|
| 5 | POST | `/api/complaint` | 登录用户 | `ComplaintCreateDTO` | `ComplaintCreateResultVO` |
| 6 | GET | `/api/complaint` | 登录用户 | `ComplaintQuery` | `PageResult<ComplaintVO>` |
| 7 | GET | `/api/complaint/{id}` | 登录用户 | path | `ComplaintVO` |

> 投诉三个接口都**不加 `@PreAuthorize`**：家属投诉陪诊员、陪诊员投诉家属，双方都能发起；
> 谁能看哪条由 Service 归属校验决定。

---

## 2 · 数据流

```
POST /api/review（提交评价）
  ReviewController(create, @PreAuthorize FAMILY)
    → ReviewServiceImpl.create()
        ① 订单存在
        ② 第 1 道状态闸门：status = COMPLETED（否则 6001 ORDER_NOT_COMPLETED）
        ③ 第 2 道闸门：未被评价过（否则 6002 ORDER_ALREADY_REVIEWED）
        ④ 敏感词过滤：SensitiveWordUtil.firstHit(content) 命中 → 6003 CONTENT_SENSITIVE
        ⑤ INSERT order_review（含 order_no 冗余，历史记录不随订单变更）
        ⑥ INSERT order_status_log（COMPLETED → REVIEWED）
        ⑦ 聚合重算：companionProfileMapper.update(...)
              set score = avgScore, review_count = totalCount
        ⑧ MessageService.send(...) 通知陪诊员（M8）

表 → Mapper → Service → Controller：
  order_review     ─┐
  companion_order  ─┼→ OrderReviewMapper / ReviewReadMapper / CompanionProfileMapper
  companion_profile ┘        ↓
                        ReviewServiceImpl（状态闸门 + 敏感词 + 聚合重算）
                             ↓
                        ReviewController
```

---

## 3 · 状态机

评价本身没有独立状态机，它由**订单状态机**约束：

```
COMPLETED ──review──→ REVIEWED
```

| 非法情况 | 错误码 |
|---|---|
| 订单非 COMPLETED 就评价 | `6001 ORDER_NOT_COMPLETED` |
| 同一订单重复评价 | `6002 ORDER_ALREADY_REVIEWED` |
| 评价内容含敏感词 | `6003 CONTENT_SENSITIVE` |
| 投诉记录不存在 | `6004 COMPLAINT_NOT_FOUND` |

**投诉状态机**（`ComplaintStatus`）：`PENDING → PROCESSING → RESOLVED / REJECTED`（由 M9 管理端驱动）。

---

## 4 · 验收清单

| # | 验收项 | 状态 |
|---|---|---|
| 1 | review / complaint / score-aggregation 接口 | ✅ |
| 2 | 订单状态闸门 6001 / 6002 | ✅ `ReviewServiceImpl` 先判状态再写 |
| 3 | 敏感词过滤 6003 | ✅ `SensitiveWordUtil.firstHit` |
| 4 | 平均分聚合（`CompanionProfile.score` 增量更新） | ✅ `companionProfileMapper.update` set score + reviewCount |
| 5 | 单测 / 越权 / e2e | ❌ `ReviewServiceTest` 缺 / ❌ `ReviewAccessMatrixTest` 缺 / ✅ `e2e_review.py` |
| 6 | 匿名评价不暴露家属姓名 | ✅ `is_anonymous` 字段（种子数据 6 条匿名） |
| 7 | 无效评价不计入聚合 | ✅ `is_valid` 字段（管理员可判无效） |

---

## 5 · 与计划描述的差异（可接受）

- 计划 §8 M7 写「聚合用 SQL `UPDATE ... SET score=(SELECT AVG...)`」，实际实现是
  **Java 先算 `score.getAverageScore()` 再写回**。业务结果等价，但并发下依赖事务隔离，
  不如 SQL 子查询原子。当前可接受（P3），高并发场景建议改 SQL 子查询。

---

## 6 · 遗留与风险

- `ReviewServiceTest` / `ComplaintServiceTest` / `ReviewAccessMatrixTest` 均缺（审计报告 P0 待办）。
