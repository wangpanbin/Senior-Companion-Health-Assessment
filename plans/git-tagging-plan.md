# 小功能级 Git 版本标签 · 规范与落地计划

> 状态：**待评审**（Q1–Q7 已对齐，粒度 / 命名 / 补打范围 / 推送授权均有结论）

## Context

用户诉求：**每完成一个小功能就打一个 git 版本标签**，并且要先制定计划再动手。

### 现状体检（全部为实测结论，命令见「验证」）

| 事实 | 实测结果 |
|---|---|
| 提交总数 | **405**（2026-09-15 → 2026-11-18，`main` 单线，`origin/main == main == efba25f`） |
| 现有标签 | **12 个**，全部是 **annotated tag**（`git cat-file -t` = `tag`）：`v1.0 v2.0 v3.0 v3.1 v3.2 v3.3 v3.4 v3.5 v3.6 v4.0 v5.0 v6.0` |
| 标签粒度 | **里程碑级**。除 `v5.0`(后挂 2 个提交) / `v6.0`(后挂 1 个) 外，每个标签后面挂着 **172–300 个提交** |
| 标签可达性 | 12 个标签**全部是 HEAD 的祖先**，无悬挂标签 |
| 远程状态 | **远程只有 6 个标签**（`v1.0 v2.0 v3.0 v4.0 v5.0 v6.0`）；**`v3.1`–`v3.6` 从未推送**，只存在本地 |
| 功能单元边界 | 全历史里有 **43 个清晰的「功能单元」边界**（`merge:` / `Merge branch 'feature/…'` / `Merge pull request #N`） |
| 编号漂移 | Git 标签与 CHANGELOG **off-by-one**：`git v4.0` = `[V3.7]`、`git v5.0` = `[V4.0]`、`git v6.0` = `[V5.0]` |
| 规范现状 | `docs/agents/git-workflow.md` §7.1–§7.3 **完全没有 tag 条文**，只规范分支与 commit message |
| 下游消费方 | `tools/repo_metrics.py --base` 默认值就是 `v3.0`；`docs/plan/archive/2026-09-28-convergence-iteration.md` Task F2 里有一次性打 v1/v2/v3 的旧脚本 |

### 要解决的问题

1. **里程碑标签太粗** —— `v4.0`(10-21) → `v5.0`(11-18) 之间 172 个提交只落一个标签，回滚或追溯某个小功能只能靠 `git log` 现翻。
2. **本地与远程不一致** —— `v3.1`–`v3.6` 没推，别人 clone 看不到。
3. **没有规范条文** —— 什么时候打、怎么命名、message 写什么、要不要推，全靠临时决定。

### 已对齐的决策

| # | 决策点 | 结论 |
|---|---|---|
| Q1 | 「小功能」粒度 | **双层**：保留 `vx.y` 里程碑标签，另加**功能单元级次版本号** `v6.1 / v6.2 …` |
| Q2 | 命名方案 | **纯语义化** `v6.1`，功能名写进 annotated tag 的 message，不混进标签名 |
| Q3 | 历史补打 | **不补**。只从当前 HEAD 往后开始；历史追溯仍用现有 12 个里程碑标签 |
| Q4 | 推送 | **每次都推**；并**补推** `v3.1`–`v3.6` 六个存量标签 |
| Q5 | 编号漂移 | **写映射表保持现状**，不做重命名、不 deprecated；下一个里程碑就叫 `v7.0` |
| Q6 | 触发判据 | 三条并集：**① feature/bugfix 分支合并** ② 直接落在 main 的 `feat`/`fix` 提交 ③ 改动了 `backend/src/main` 或 `frontend/src` |
| Q7 | 推送授权 | **每个标签单独确认**（符合 `AGENTS.md` §7.3：git 动作需明确授权） |

> Q6 的三条是并集，实际判据收敛为一句：**凡是让 `backend/src/main` 或 `frontend/src` 行为发生变化的落地点，都要有一个标签**。
> 伴随的 `docs` / `chore` / `ci` / `test` / 纯重构提交不单独打标签，随下一个标签一起被覆盖。

## Approach

### A. 标签体系（双层，向后兼容）

```
v1.0 ─ v2.0 ─ v3.0 ─ v3.1 … v3.6 ─ v4.0 ─ v5.0 ─ v6.0 ─ v6.1 ─ v6.2 ─ … ─ v7.0
└──────────────── 里程碑（Vx.0，大版本收尾） ───────────────┘└ 功能单元（Vx.y, y≥1） ┘
```

- **`vX.0` = 里程碑**：一个产品迭代收尾（对应 `docs/plan/iteration-plan.md` 的 Vx 大版），沿用现有 12 个标签的做法。
- **`vX.Y` (Y ≥ 1) = 功能单元**：一个完整功能落地即打，从 **`v6.1`** 开始。
- 下一个里程碑 = **`v7.0`**（标签线自然 +1，不等同 CHANGELOG 的 V6.0）。

### B. message 模板（沿用 `v3.1`–`v6.0` 的三件套写法）

```
v6.1 · <功能一句话主题>
<影响面 / 关键产物，一行>

例：v3.3 · 陪诊员工作台：四个自助端点 + 收入页去前端自算 · 后端 1032 / 前端 155 / E2E 220 全绿
```

- 强制：`vX.Y · <主题>`，与既有 12 个标签同风格。
- 测试数字**可选**（Q6 未把「门禁全绿」列为硬门槛）；**一旦写进 message 就必须现取**（`tools/repo_metrics.py` 或实跑输出），不得手抄上一份文档 —— `AGENTS.md` §11 末条。

### C. 打标签的动作序列（每个功能单元一次）

```powershell
# 1) 标签锚定在「功能落地的那次提交」（通常是 merge commit 或直接 feat/fix 提交）
git tag -a v6.1 <commit> -m "v6.1 · <主题>" -m "<影响面一行>"

# 2) 用户点头后单独推这一个标签
git push origin v6.1

# 3) 核对
git tag -l "v6.*" ; git ls-remote --tags origin | Select-String "v6.1"
```

一次性补齐远程缺口（**只在用户当次明确授权时执行**）：

```powershell
git push origin v3.1 v3.2 v3.3 v3.4 v3.5 v3.6
```

### D. 编号映射表（写进规范，消解 off-by-one）

| Git 标签 | 锚点提交 | `CHANGELOG.md` 章节 | 说明 |
|---|---|---|---|
| `v3.0` | `db10898` | —（收敛迭代） | plan.md 79/79 全绿 |
| `v3.1`–`v3.6` | `5020c4a`…`dc4119e` | `[V3.1]`…`[V3.6]` | 一一对应 |
| `v4.0` | `499b46d` | `[V3.7] 家属智能助手` | **标签 +0.3** |
| `v5.0` | `b20ce78` | `[V4.0] 作品集化迭代` | **标签 +1.0** |
| `v6.0` | `81f8702` | `[V5.0] 业务收口与工程加固` | **标签 +1.0** |
| `v6.1`+ | 本次起新增 | 下一个 `[V6.0]` 会对应 `v6.1`…`v7.0` 区间 | 标签只标代码快照，CHANGELOG 记产品版本 |

> 规范里明写一句：**Git 标签是代码快照锚点，CHANGELOG 是产品版本口径，两者不要求同号**；引用数字时注明是哪一种口径。

## Files to modify

| 文件 | 改动 | 说明 |
|---|---|---|
| `docs/agents/git-workflow.md` | **新增 §7.4「版本标签」** | 层级定义、触发判据三条、message 模板、推送授权、映射表、标签不可改名告警 |
| `docs/agents/doc-sync.md` | 联动矩阵加一行 | 「改 tag 命名/粒度 → 同步 `git-workflow.md` §7.4 + `CHANGELOG.md` 映射表」 |
| `AGENTS.md` §11 验收清单 | 加一条 checkbox | 「本功能单元是否已打标签（`vX.Y`）并向用户报出建议标签名」 |
| `CHANGELOG.md` | 文档头部加一段标签口径说明 | 指向映射表，避免下次再有人对不上号 |
| `tools/ai_flow/README.md` | **不修改** | 可选脚本未采纳，README 无需改动 |
| 可选的 tag_helper.py（拟放 tools/ai_flow/，**未落地**） | **本次不实现**（用户已确认跳过） | 计划里的只读辅助脚本；§7.4.2 的判据用 `git diff --name-only` 一条命令即可复现，不必新增脚本 |

> 分支：本次改动以文档为主 → 走 `docs/git-tagging-rules` 分支（`AGENTS.md` §0.2）。
> 因可选脚本未采纳，**不需要** `feature/ai-flow-tag-helper` 分支。

## Reuse（已存在、直接复用，不新造）

- `tools/repo_metrics.py --base <ref>` —— 标签一旦打上即可当基线 ref；message 里的规模数字从这里现取。
- `tools/ai_flow/gate_check.py` —— 打标签前跑一次静态门禁（错误码 / 文档三项 / FAQ / 密钥），与 CI 同一份规则。
- `tools/ai_flow/handoff.py` —— 交接卡；标签可以作为 L3「提交准备」环节的收尾动作挂上去。
- `docs/plan/archive/2026-09-28-convergence-iteration.md` Task F2 —— 已有的 `git tag -a` + `git push` 命令模板，直接沿用格式。
- 既有 12 个 annotated tag 的 message 风格 —— 三件套（编号 · 主题 · 数字）照抄即可，无需另设规范。

## Steps

- [x] 1. 建 `docs/git-tagging-rules` 分支（改动含代码时改 `feature/ai-flow-tag-helper`）
- [x] 2. 在 `docs/agents/git-workflow.md` 写 §7.4：层级定义 + 三条触发判据 + message 模板 + 推送授权 + 映射表 + 「标签推送后不可改名」告警
- [x] 3. `docs/agents/doc-sync.md` 联动矩阵加一行；`AGENTS.md` §11 加一条 checkbox；`CHANGELOG.md` 头部加标签口径说明
- [x] 4. ~~（可选）实现 tag_helper.py~~ —— **用户已确认跳过**，§7.4.2 用 `git diff --name-only` 一条命令即可复现判据
- [x] 5. 一次性补推存量标签 `v3.1`–`v3.6` —— 已获用户授权并推送，`git ls-remote --tags origin` 可见 6 个新 tag ref
- [ ] 6. 下一个功能单元落地时，按新规范产出「建议标签 `v6.1` + message + 门禁结果」，**报给用户确认**后再 `git tag -a` 与 `git push origin v6.1`
- [ ] 7. 跑门禁 + 自查 §11 清单

## Verification

```powershell
# 标签清单与排序（应为 12 + 新增功能标签）
git tag -l "v*"
git for-each-ref --sort=creatordate `
  --format='%(refname:short) | %(creatordate:short) | %(contents:subject)' refs/tags

# 标签在历史线上的位置是否合理（不应出现同一点两个标签）
git log --tags --simplify-by-decoration --oneline

# 远程与本地对齐（补推后 v1.0–v6.0 + 新标签全部可见）
git ls-remote --tags origin

# 新标签确实锚在功能落地提交上
git rev-list -n 1 v6.1
git rev-list --count v6.0..v6.1          # 该功能单元包含多少提交

# 基线 ref 可用（不是空标签）
python tools/repo_metrics.py --base v6.1

# 静态门禁（与 CI 同一套）
python tools/ai_flow/gate_check.py
```

人工验收点：

1. 每条新标签的 message 符合 `vX.Y · <主题>` 且带了影响面。
2. `git describe --tags` 能给出可读的最近标签。
3. `git push --dry-run origin v6.1` 只涉及单个标签 ref，不夹带分支。

## 风险与边界

| 风险 | 处理 |
|---|---|
| 标签推送后**不可改名**（改名 = 删除 + 重推，属破坏性操作） | 规范里明确：标签名与 message 在 `git tag -a` **之前**给用户确认 |
| off-by-one 继续扩大 | 已用映射表固化口径；下一个里程碑固定 `v7.0` |
| 功能单元判据被人为放宽，标签泛滥 | 判据写成「改动 `backend/src/main` / `frontend/src`」这种**可机器校验**的形式，不用「感觉是个功能」 |
| 不补打历史 → 旧功能仍难追溯 | 已确认接受：历史靠 12 个里程碑标签 + `git log --merges` 定位 |
| 与 `repo_metrics.py --base v3.0` 默认值冲突 | 无冲突：v3.0 仍存在且仍是 HEAD 祖先 |
