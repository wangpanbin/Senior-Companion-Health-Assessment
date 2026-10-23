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

## 编码细则（从仓库根 `AGENTS.md` 拆出）

> 拆分原因：运行时给根 `AGENTS.md` 的上下文预算只有 **32 KB**，拆分前它有 **54 KB**，
> 超出部分**每次会话都被静默丢弃**（丢掉的恰好是 §4.1 订单状态机、§10 Do/Don't 底线、
> §11 验收清单）。现在 `AGENTS.md` 只留红线 / 导航 / 底线 / 验收清单，细则按需加载。
>
> **小节编号一个没改** —— 仓库其它文档里的 `AGENTS.md §N.N` 引用仍然对得上，
> 查 `AGENTS.md` §0.3 的索引表定位到文件后看同名小节。

| 文件 | 原章节 | 什么时候读 |
|---|---|---|
| [`backend-conventions.md`](backend-conventions.md) | §2 后端编码规范 | 改 `backend/` 任何 Java 代码前 |
| [`frontend-conventions.md`](frontend-conventions.md) | §3 前端编码规范 | 改 `frontend/` 前端代码前 |
| [`business-rules.md`](business-rules.md) | §4 业务规则 | 改订单状态机 / 打卡 / 老人账号 / 上传前 |
| [`database.md`](database.md) | §5 数据库 / Flyway / 数据初始化 | 改 `backend/sql/` 迁移脚本或数据初始化前 |
| [`testing.md`](testing.md) | §6 测试与质量 | 写或改测试前 |
| [`git-workflow.md`](git-workflow.md) | §7 Git 与协作 | 开分支、提交、合并前 |
| [`api-first.md`](api-first.md) | §8 接口先行 | 新增或改动任何 HTTP 接口前 |
| [`doc-sync.md`](doc-sync.md) | §9 文档同步清单 | 改完代码收尾时（哪些文档要同步） |

⚠️ **硬约束不在这里** —— §0 合规红线、§0.2 AI 自我约束、§10 Do/Don't、§11 验收清单
仍在仓库根 `AGENTS.md`，那份始终在上下文里。

## 与其它区的关系

```
GLOSSARY.md（术语表唯一真源，根目录）
        ↓
docs/agents/domain.md（怎么读它）
        ↓
docs/adr/（术语背后的架构决策）
```

**术语与决策是一体的**：术语表说「什么是履约风险」，ADR 说「为什么履约风险不是订单状态」。改术语时先确认没有 ADR 依赖旧说法。
