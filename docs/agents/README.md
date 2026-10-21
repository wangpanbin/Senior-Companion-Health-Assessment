# docs/agents/ · AI 协作契约区

> 给**在仓库里协作的 AI 工具**看的约定。与产品 / 工程文档（`../prd/`、`../plan/`）不同，这里回答的是「AI 该怎么读文档、怎么留痕迹」。

| 文件 | 作用 | 消费方 |
|---|---|---|
| [`domain.md`](domain.md) | **领域文档消费规则** —— 探索代码前先读哪些文件、术语表的唯一真源在哪、什么情况下该记 ADR | `/domain-modeling`、`/improve-codebase-architecture`、`/tdd` 等技能 |
| [`FRONTEND_CONTRACT.md`](FRONTEND_CONTRACT.md) | **前后端联调契约** —— 页面接真实接口时的唯一事实来源：字段、鉴权、错误码、E2E 夹具与账号口令出处 | 前端实现 · E2E 套件 · 后端联调 |
| [`issue-tracker.md`](issue-tracker.md) | Issue 管理约定（走 `gh` CLI） | AI 建 / 查 issue 时 |
| [`triage-labels.md`](triage-labels.md) | 分诊标签的五个规范角色 | AI 分诊时 |
| [`designs/`](designs/) | **模块设计评审** —— M4 订单 / M5 执行 / M6 用药 / M7 评价 / M8 站内信 / M9 管理 / M10 统计 | 改对应模块前先读 |
| [`PLAN_BACKEND.md`](PLAN_BACKEND.md) | 后端开发计划 v1.0（M4 – M10） | 历史归档，验收口径以 `../plan/plan.md` 为准 |
| [`AUDIT_BACKEND_2026-09-16.md`](AUDIT_BACKEND_2026-09-16.md) | 后端全量审计报告 | 历史归档 |
| [`BUG_LIST.md`](BUG_LIST.md) | 遗留 Bug 清单（已修复项归档 + 未修项） | 排查问题时查 |
| [`FIXTURE_ROLLBACK_PLAN.md`](FIXTURE_ROLLBACK_PLAN.md) | E2E 夹具写库路径与回滚方案 | 跑 E2E 前读 |

## 与其它区的关系

```
GLOSSARY.md（术语表唯一真源，根目录）
        ↓
docs/agents/domain.md（怎么读它）
        ↓
docs/adr/（术语背后的架构决策）
```

**术语与决策是一体的**：术语表说「什么是履约风险」，ADR 说「为什么履约风险不是订单状态」。改术语时先确认没有 ADR 依赖旧说法。
