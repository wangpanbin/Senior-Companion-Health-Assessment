# docs/plan/ · 工程计划区

> 工程的「做什么、按什么顺序做、做到什么算完」。产品视角在 [`../prd/`](../prd/)。

| 文件 | 内容 | 状态 |
|---|---|---|
| [`plan.md`](plan.md) | **项目开发计划书** —— 模块拆分（M0 – M15）+ 环境需求 + 验收标准。工程的「总纲」，与 [`../prd/PRD.md`](../prd/PRD.md) 的分层关系见 [ADR-0005](../adr/0005-prd-planmd-layers.md) | 长期有效 |
| [`iteration-plan.md`](iteration-plan.md) | **V3.x 产品迭代规划** —— 逐版本的产品定位、能力范围与节奏（原 `plans/迭代计划.md`） | 长期有效 |
| [`ui-rework.md`](ui-rework.md) | 前端 UI 重构 R4 / R5 / R6 实施计划（原 `plans/ui-rework.md`） | 已执行 |
| [`desktop-adapt-2026-09.md`](desktop-adapt-2026-09.md) | 桌面形态适配方案（Q1 – Q12 决策） | 已执行 |
| [`convergence-2026-09.md`](convergence-2026-09.md) | 收敛迭代计划（21 张 ticket + 收尾标准） | 已收尾 |
| [`archive/2026-09-28-convergence-iteration.md`](archive/2026-09-28-convergence-iteration.md) | 收敛迭代的**分步实施计划存档**（含逐步验证命令）。文件内 checkbox 保持计划书写时的原始形态，**不代表执行状态** —— 执行结果看 [`../reports/CLOSEOUT_2026-09-29.md`](../reports/CLOSEOUT_2026-09-29.md) | 存档 |

## 怎么用

- **验收某个模块做到什么程度** → `plan.md` 里对应 M 编号的章节
- **看某个版本的完整范围** → `iteration-plan.md` 里对应 § 编号
- **理解某次迭代为何这么拆** → 对应计划文件的「Context / 背景」段 + `../adr/` 里相关 ADR
- **确认某次迭代是否真的做完** → `../reports/` 里的收尾报告，**不要**拿计划文件的 checkbox 当执行记录
