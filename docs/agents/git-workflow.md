# 7 · Git 与协作

> 本文件是 `AGENTS.md` **§7** 的落地位置。
> 拆分原因：运行时给仓库根 `AGENTS.md` 的上下文预算是 **32 KB**，
> 拆分前它有 **54 KB** —— 超出预算的部分**每次会话都被静默丢弃**，
> 丢掉的恰好是订单状态机、Do/Don't 底线、验收清单这些最该被看见的内容。
> 拆开后 `AGENTS.md` 只留红线与导航，细则**按需加载**。
>
> ⚠️ 硬约束仍在 `AGENTS.md`（始终在上下文里）：§0 合规红线、§0.2 AI 自我约束、
> §10 Do/Don't 最低底线、§11 验收检查清单。本文件是细则，不覆盖那些硬约束。
>
> 小节编号（§7.1 …）**未改动**，仓库其它文档里的 `AGENTS.md §7.N` 引用仍然对得上。

---

## 7.1 分支模型

```
main← develop ← feature/<module>-<name>
                          bugfix/<module>-<name>
                          docs/<name>
```

- 可以直接向 `main` / `develop` 推 push,但是必须先review。
- 每周五合并到 `develop`。
- `main` 设为受保护分支，PR 必须有 Code Review。
- **AI 新增功能 / 编写代码时必须先新建分支，并在该独立分支上完成全部改动与提交**：不得检出 `main` / `develop` 后直接写代码，也不允许"先在主分支改完再补分支"；改动全部落地且 §11 检查通过后，再合并回主分支并删除功能分支（见 §7.3）。

## 7.2 提交信息格式

```
<type>(<scope>): <subject>

<body 可选>

<footer 可选>
```

- `type`：`feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `perf` / `style`。
- `scope`：模块编号或包名，如 `M4` / `auth` / `order` / `elderly`。
- 示例：
  - `feat(M4): 实现订单状态机下单接口`
  - `fix(auth): 修复 401 刷新 token 后未重放请求的 bug`
  - `docs(api): 同步 03-order.md 错误码 3005`

## 7.3 AI 输出提交相关动作的硬约束

- AI **必须** 生成符合上述格式的提交信息再 commit。
- AI **禁止** `git push --force` 到任何受保护分支。
- AI **禁止** 直接修改 `main` / `develop`，必须基于 `feature/...` 分支——即**写第一行代码前就先新建独立分支**，所有编辑与提交都发生在该分支上，不允许在主分支上改完再倒签补分支。

---

## 7.4 版本标签（tag）

> 起源：2026-11-18 用户要求「每完成一个小功能就打一个 git 版本标签」。
> 本条落地时的基线实测（`git log --oneline | wc -l` / `git tag -l`）：405 个提交、
> 12 个标签，全是里程碑级，`v4.0`→`v5.0` 之间 172 个提交只落一个标签。

### 7.4.1 双层标签

```
v1.0 ─ v2.0 ─ v3.0 ─ v3.1 … v3.6 ─ v4.0 ─ v5.0 ─ v6.0 ─ v6.1 ─ v6.2 ─ … ─ v7.0
└────────────── 里程碑（Vx.0）──────────────┘└──── 功能单元（Vx.Y，Y ≥ 1）────┘
```

| 层级 | 形式 | 何时打 |
|---|---|---|
| **里程碑** | `vX.0` | 一个产品迭代收尾（对应 [`docs/plan/iteration-plan.md`](../plan/iteration-plan.md) 的 Vx 大版）。沿用既有 12 个标签的做法，不新增要求 |
| **功能单元** | `vX.Y`（**Y ≥ 1**） | **一个完整功能落地即打**。自 2026-11-18 起从 **`v6.1`** 开始 `+0.1` 递增；下一个里程碑固定为 **`v7.0`** |

- 标签**一律 annotated**（`git tag -a`），**禁止 lightweight tag** —— 既有 12 个全是 annotated（`git cat-file -t` = `tag`），message 是追溯时的第一现场。
- **不补打历史**：`v6.0` 之前的 405 个提交不再追加功能级标签，历史追溯用 12 个里程碑标签 + `git log --merges`。

### 7.4.2 触发判据（三条并集，命中任一条即打）

1. **feature / bugfix 分支合并时** —— 合并 commit（`merge: …` / `Merge branch 'feature/…'` / `Merge pull request #N`）即功能单元的标签锚点。
2. **直接落在 `main` 的 `feat` / `fix` 提交** —— 仓库有大量直接提交（如 `fix(auth): 找回密码去短信化`），没有 merge 边界，这类提交本身即锚点。
3. **改动了 `backend/src/main/` 或 `frontend/src/`** —— 真发生了行为变更就说明是功能单元。

收敛成一句可机器校验的判据：

> **凡是让 `backend/src/main` 或 `frontend/src` 行为发生变化的落地点，都要有一个标签。**

```powershell
# 判定「上一个标签以来是否发生过行为变更」
git diff --name-only v6.0..HEAD | Select-String "^(backend/src/main|frontend/src)/"
```

- 伴随的 `docs` / `chore` / `ci` / `test` / 纯重构提交**不单独打标签**，跟随下一个标签被覆盖。
- **不用「感觉是个功能」当判据** —— 判据必须是 `git diff --name-only` 能复现的路径规则，否则标签必然泛滥或漏打。

### 7.4.3 message 模板

沿用 `v3.1`–`v6.0` 已有的「三件套」写法，不要另立风格：

```
vX.Y · <功能一句话主题>
<影响面 / 关键产物，一行>
```

真实样例（既有标签原文）：

```
v3.3 · 陪诊员工作台：四个自助端点 + 收入页去前端自算 · 后端 1032 / 前端 155 / E2E 220 全绿
```

- **强制**：首行 `vX.Y · <主题>`，与既有 12 个标签同风格。
- **测试数字可选**：本条不要求「门禁全绿」才允许打标签（见 §7.4.5）；但**一旦写进 message 就必须现取** ——
  跑 `python tools/repo_metrics.py` 或照抄实跑输出，注明基线 ref，**不得手抄上一份文档**（`AGENTS.md` §11 末条）。
- 标签**推送后不可改名**（改名 = 删除 + 重推，属破坏性操作）。因此标签名与 message
  **必须在 `git tag -a` 之前**报给用户确认，不能打完再改。

### 7.4.4 编号映射表（Git 标签 ↔ CHANGELOG）

Git 标签与 `CHANGELOG.md` 是**两套口径**，历史上已经 off-by-one：

| Git 标签 | 锚点提交 | `CHANGELOG.md` 章节 | 偏差 |
|---|---|---|---|
| `v3.0` | `db10898` | —（收敛迭代，plan.md 79/79 全绿） | — |
| `v3.1`–`v3.6` | `5020c4a` … `dc4119e` | `[V3.1]` … `[V3.6]` | 一一对应 |
| `v4.0` | `499b46d` | `[V3.7]` 家属智能助手 | 标签 **+0.3** |
| `v5.0` | `b20ce78` | `[V4.0]` 作品集化迭代 | 标签 **+1.0** |
| `v6.0` | `81f8702` | `[V5.0]` 业务收口与工程加固 | 标签 **+1.0** |
| `v6.1`+ | 2026-11-18 起新增 | 下一个 `[V6.0]` 覆盖 `v6.1`…`v7.0` 区间 | — |

> **口径声明**：Git 标签是**代码快照锚点**，`CHANGELOG.md` 是**产品版本口径**，两者**不要求同号**。
> 引用版本数字时**必须注明是哪一种口径**（「Git 标签 v6.0」 vs 「CHANGELOG V5.0」），否则必然误读。
> 本表**只做映射，不做重命名** —— `v3.0`–`v6.0` 已推送远程，改名会破坏他人本地 clone。

### 7.4.5 动作序列与推送授权

```powershell
# 1) 锚定在「功能落地的那次提交」（merge commit 或直接 feat/fix 提交）
git tag -a v6.1 <commit> -m "v6.1 · <主题>" -m "<影响面一行>"

# 2) 用户点头后，单独推这一个标签
git push origin v6.1

# 3) 核对
git tag -l "v6.*" ; git ls-remote --tags origin | Select-String "v6.1"
```

- **推送必须每个标签单独确认**（`AGENTS.md` §7.3：git 动作需用户明确授权）。
  AI 报出「建议标签名 + message + 门禁结果」，用户回一句「打」之后才 `git tag -a` 与 `git push`。
- 允许 `git push --dry-run origin v6.1` 预演；**禁止** `git push --force` / `git push --tags`（会夹带意外 ref）。
- **门禁不是打标签的前置门槛**：标签记录的是代码快照，历史上有标签落在门禁修复之前的提交上。
  但用户明确要「门禁全绿」时按用户要求办，并把结论写进 message。

### 7.4.6 自查

- [ ] 标签是 annotated（`git cat-file -t vX.Y` 输出 `tag`）。
- [ ] 首行符合 `vX.Y · <主题>`，影响面单独一行。
- [ ] 锚点提交确实命中 §7.4.2 判据（`git diff --name-only <上个标签>..<锚点>` 里有 `backend/src/main` 或 `frontend/src`）。
- [ ] 没有和既有标签共用同一个 commit（`git log --tags --simplify-by-decoration --oneline` 检查）。
- [ ] 推送前已获用户逐标签授权；推完 `git ls-remote --tags origin` 可见。
