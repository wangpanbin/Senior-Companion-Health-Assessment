# Git hooks（需手动安装）

> `AGENTS.md` §0.2 的一条硬约束——「禁止直接在 `main` / `develop` 上编写或提交代码」——
> 是**机械规则**，本该由确定性检查把关，而不是靠每次开工前想起来。
> 本目录放这类 hook。`~/.git/hooks` 目录本身不入库，所以需要**每个人手动装一次**。

---

## 安装（每个 clone 一次）

```bash
# Git Bash / macOS / Linux
cp tools/hooks/pre-commit .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

```powershell
# Windows PowerShell
Copy-Item tools/hooks/pre-commit .git/hooks/pre-commit
```

验证：

```bash
ls -l .git/hooks/pre-commit    # 文件存在即生效，git 每次 commit 前会调用它
```

---

## 装了之后会怎样

在 `main` / `master` / `develop` 上 `git commit` 会被拒绝，并打印分支约定的三条命令。
确需在受保护分支上提交（合并提交、机器人、紧急修复）时：

```bash
git commit --no-verify
```

**`--no-verify` 是唯一的绕过口，而且它写在命令里** —— 这比「某天顺手在 main 上提交了」
好得多：绕过是一次显式的、会留在 shell 历史里的决定。

---

## 为什么不用 husky / pre-commit 框架

`AGENTS.md` §0.2 明确「不要自创技术栈」，而这条判断只需要读一次 `.git/HEAD`。
加一个前端依赖去跑几行 shell，是把机械规则外包给一个新的维护面。

---

## 与 CI 的分工

| 检查 | 本地位置 | CI |
|---|---|---|
| 受保护分支拦截 | 本目录的 `pre-commit` | —（CI 只看 commit 落在哪个 ref，不判断分支名） |
| 静态门禁六项（secrets / 指标字典 / FAQ / 错误码 / 页面编号 / Markdown） | `tools/verify_all.ps1` **第 1 步** | `ci.yml` 的 5 个 job |
| 后端 `mvn verify` | `tools/verify_all.ps1` 第 4 步 | `ci.yml` 后端 job |
| 前端 lint + vitest + build | `tools/verify_all.ps1` 第 2–3 步 | `ci.yml` 前端 job |

本地和 CI 走的是**同一份脚本**（`tools/ai_flow/gate_check.py`）—— 与
`AGENTS.md` §0.2「规则只改脚本，不要在 workflow 里再抄一遍」纪律一致。
