# docs/ · 文档总索引

> 本目录是「银龄伴诊」的全部项目文档。**根目录只保留三个入口**：
> [`README.md`](../README.md)（对外基线）· [`AGENTS.md`](../AGENTS.md)（AI 协作规范）· [`GLOSSARY.md`](../GLOSSARY.md)（领域词汇表）。
> [`CHANGELOG.md`](../CHANGELOG.md)（版本变更记录）按通用惯例留在根目录。

## 按用途分区

| 目录 | 视角 | 内容 | 入口 |
|---|---|---|---|
| `prd/` | 产品 | PRD 逐版（V3.1 – V3.7 + E4）+ 功能拓展规划 | [`PRD.md`](prd/PRD.md) |
| `plan/` | 工程 | 项目计划书（模块拆分 + 验收标准）· 迭代规划 · 目录适配 / 收敛迭代存档 | [`plan.md`](plan/plan.md) |
| `api/` | 接口 | 全局约定 + 错误码表 + 13 个模块接口明细 | [`api/README.md`](api/README.md) |
| `spec/` | 契约 | 各版工程规格 + 工单 + 指标字典（人读面） | [`V3.x-roadmap-spec.md`](spec/V3.x-roadmap-spec.md) |
| `adr/` | 架构 | 架构决策记录（0020 止）· 背景 / 决策 / 后果 | [`adr/`](adr/) |
| `design/` | 视觉 | 前端设计规范（设计令牌 / 组件 / 交互，视觉唯一真源） | [`design.md`](design/design.md) |
| `db/` | 数据 | ER 图 / 数据字典 / 索引与 EXPLAIN / 种子说明 | [`db/README.md`](db/README.md) |
| `agents/` | 协作 | 前后端联调契约 · AI 领域文档消费规则 · 遗留 Bug 清单 · 模块设计评审 | [`FRONTEND_CONTRACT.md`](agents/FRONTEND_CONTRACT.md) |
| `reports/` | 验证 | 联调全功能测试报告 · 各迭代测试报告 · 验收基线 / 收尾复核 · 压测记录 | [`TEST_REPORT.md`](reports/TEST_REPORT.md) |
| `review/` | 评审 | 逐版本评审记录 | [`review/`](review/) |
| `security/` | 安全 | 安全评估报告（脱敏 / 越权 / 上传 / 配置） | [`security/`](security/) |
| `diagrams/` | 图示 | 架构图 / 用例图 / 原型图（含 PNG） | [`diagrams/`](diagrams/) |

## 阅读路径

| 你要做的事 | 先读 |
|---|---|
| 第一次接触项目 | [`../README.md`](../README.md) → [`prd/PRD.md`](prd/PRD.md) |
| 参与开发 | [`../AGENTS.md`](../AGENTS.md)（规范） → [`api/README.md`](api/README.md)（契约） → [`plan/plan.md`](plan/plan.md)（验收标准） |
| 接手某个模块 | [`spec/`](spec/) 里对应版本的 spec + tickets → [`agents/FRONTEND_CONTRACT.md`](agents/FRONTEND_CONTRACT.md) |
| 改前端界面 | [`design/design.md`](design/design.md) → [`../AGENTS.md`](../AGENTS.md) §3 |
| 查接口字段 | [`api/README.md`](api/README.md)（全局约定 + 错误码） → [`api/`](api/) 对应模块 |
| 理解某个决策为什么这么定 | [`adr/`](adr/) 对应编号 |
| 对齐术语口径 | [`../GLOSSARY.md`](../GLOSSARY.md) |
| 看验证证据 | [`reports/`](reports/) 对应版本 |

## 命名约定

- **接口契约**：`api/NN-<module>.md`（NN 两位序号，`api/README.md` 是全局约定）
- **架构决策**：`adr/NNNN-<slug>.md`（NNNN 四位序号，只增不改）
- **规格与工单**：`spec/V<major>.<minor>-<slug>-spec.md` + `spec/V<major>.<minor>-tickets.md`
- **产品需求**：`prd/PRD-V<major>.<minor>-<名称>.md`
- **报告**：`reports/<主题>-<日期>.md` 或 `reports/iteration-v<版本>-test-report.md`

## 维护约定

1. **接口先行**：接口变更先改 `api/<module>.md`，再改后端，再改前端（见 `../AGENTS.md` §8）。
2. **新增错误码**：`ResultCode.java` 与 `api/README.md` 错误码总表**两处必须同步**。
3. **`db/` 是生成物**：改 `backend/sql/tools/gen_db_docs.py`，不要手改 `db/` 下的文件。
4. **改术语**：只改 [`../GLOSSARY.md`](../GLOSSARY.md)，不要另建别名文件（技能按文件名硬编码读取）。
5. **改代码规范**：改 [`../AGENTS.md`](../AGENTS.md)，并同步本索引。
