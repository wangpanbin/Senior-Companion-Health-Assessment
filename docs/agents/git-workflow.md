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

- **AI 只能** 在用户明确授权后才执行 `git commit` / `git push`。
- AI **必须** 生成符合上述格式的提交信息再 commit。
- AI **禁止** `git push --force` 到任何受保护分支。
- AI **禁止** 直接修改 `main` / `develop`，必须基于 `feature/...` 分支——即**写第一行代码前就先新建独立分支**，所有编辑与提交都发生在该分支上，不允许在主分支上改完再倒签补分支。
