# tools/ai_flow —— AI 日常工作流的工具层

配套说明见 [docs/agents/daily-ai-workflow.md](../../docs/agents/daily-ai-workflow.md)。
这里是**执行层**：把那条规程里的判定变成可运行的脚本。

三个脚本，都只用 Python 标准库（仓库红线：不引入 pom.xml / package.json 之外的依赖）。

---

## impact_map.py —— 改动影响面 → 文档同步对账表

把 [docs/agents/doc-sync.md](../../docs/agents/doc-sync.md) 的联动矩阵变成可执行的对账表。

```bash
# 工作区（含未跟踪文件）vs HEAD
python tools/ai_flow/impact_map.py

# 某个区间
python tools/ai_flow/impact_map.py --base HEAD~1

# 审计单个历史提交
python tools/ai_flow/impact_map.py --base '051c4f5~1..051c4f5'

# 机器可读输出
python tools/ai_flow/impact_map.py --json
```

**输出四类结论**：

| 标记 | 含义 |
|---|---|
| `[已覆盖]` | 目标文档里已经出现过本次的接口路径 |
| `[真欠账]` | 有接口路径没被文档描述 —— 点名缺哪几条 |
| `[抽查]` | 语义类，机器判不了，需要人扫一眼 |
| `[未随本次改动更新]` | 本次没碰它，但不一定欠账（接口先行场景常见） |

**为什么是契约级**：本仓库执行接口先行，文档常常在更早的提交里就写好了。
若只比「目标文档有没有出现在本次 diff 里」，会把已写好的文档误报成欠账。
本脚本改为**解析 Controller / 前端封装里的接口路径，去文档里查出现过没有**。

退出码：`0` 无欠账 / `1` 有真欠账 / `2` 有需人工确认项（优先于 1）。

### 维护

新增 Controller 或新增前端封装模块时，**必须**登记到脚本顶部的
`CONTROLLER_API_DOC` / `DOC_FRONTEND_API` 两张表 —— 否则会被列为「需人工确认」。

---

## gate_check.py —— 静态门禁统一入口

```bash
python tools/ai_flow/gate_check.py
python tools/ai_flow/gate_check.py --skip-faq
python tools/ai_flow/gate_check.py --base HEAD~1
python tools/ai_flow/gate_check.py --only md --all-md
python tools/ai_flow/gate_check.py --strict-text
```

五项门禁：

| # | 门禁 | 来源 |
|---|---|---|
| 1 | 密钥体检 | 复用 `tools/check_secrets.py` |
| 2 | 指标字典人读面↔机器面 | 复用 `tools/check_metric_dict_parity.py` |
| 3 | FAQ 锚点 | 复用 `tools/check_faq_anchors.py` |
| 4 | **错误码双向一致** | 新增：`ResultCode.java` ↔ `docs/api/README.md` 码值集比对 |
| 5 | **Markdown 三项校验** | 新增：代码围栏成对 / 引用路径真实存在 |

第 1–3 项**只做编排**，规则本体仍在原脚本里（CI 调的是同一份，不复制第二份）。
第 4 项比对的是**码值集**，不是含义文字 —— 文字口径历史上本就有出入，按文字判会产生假红。
第 5 项默认只覆盖**本次改动**的 .md 文件（含尚未入库的新文件）；
`--all-md` 则扫仓库全部 .md（140 份，实测 1.5s）—— **CI 用的是 `--all-md`**，见下。

退出码：0 全过 / 1 有失败项。

### 第 5 项里的「悬空路径」怎么判

文档里反引号包着的路径，若**在仓库里不存在**且**不被 `.gitignore` 忽略**，才算悬空。

**「被 `.gitignore` 忽略」这一条是 2026-11-23 补的**（PR #33 的 CI 被它顶红）：
README 引用 `ai-service/.env` 说明本地怎么配环境变量，这个文件按仓库红线**刻意不入库**
（`.gitignore` 第 105 行），所以 CI 的全新检出里根本没有它 —— 早先的门禁把它判成
「文档引用了不存在的路径」，于是每次改 README 的 PR 都在 CI 上红，而本地因为
那个文件就在磁盘上所以是绿的。

判断方式直接问 git（`git check-ignore`），**不在脚本里再抄一份忽略清单** ——
抄两份必然漂移，而漂移的表现恰恰是「本地绿、CI 红」。

### 为什么 CI 改成了全量（2026-11-24）

增量口径的问题是**问题会累积**：旧文档里引用了已删除 / 改名的文件，那行永远不进任何 diff，
于是永远碰不到 PR。实测时就这么漂了 10 处（某份从未落地的 `10-settlement` 接口文档、
某个最终没建的 `NlDialog.vue`、某个只存在于 spec 的 `review-audit.vue`）。
全量扫描 140 份 .md 只要 1.5 秒，成本可忽略；门禁的价值是不让欠账堆积，不只是挡新引入的。

代价是必须开两处**目录级**豁免，且必须窄 —— 只有**快照类**文档豁免路径判定：

| 路径 | 为什么算快照 |
|---|---|
| `CHANGELOG.md` | 历史流水账 |
| `docs/plan/archive/` | 计划存档，含「当时 `git rm` 了哪些文件」的执行步骤 |
| `docs/review/` | 评审记录，引用被评审那天的代码位置 |

它们引用的是「当时」的路径，文件后来改名 / 删除是文档的**性质**不是错误；让快照随今天
改名而失效等于篡改历史。正确做法是**新文档**指向现在的位置。围栏检查对它们照抓。
普通文档（spec / api / agents 等**活文档**）**不豁免**。

> 写「该建而未建」的文件时怎么办：把路径拆成「目录 + 文件名」两段写在正文里，
> 或者说明现状而不是裸引路径。硬写完整路径会被全量门禁报悬空 —— 这是刻意设计，
> 提醒你：要么去建它，要么如实标注它不存在。

### gate_check.py 自己的回归测试

```bash
python -m unittest tools.ai_flow.test_gate_check -v
```

纯标准库，不连 MySQL / Redis / 网络。CI 的 `static-gates` job 会在跑完上面两项
**之后**跑它 —— 门禁有 bug 时门禁自己也会放行，只有「先验证门禁本身可信，
再让它去验仓库」才成立。

### 在 CI 里怎么用

`.github/workflows/ci.yml` 的 `static-gates` job 只跑 CI 尚未覆盖的两项，
与既有 job 分工不重叠（密钥体检 / FAQ 锚点 / 指标字典 parity 已在别的 job 里跑）：

| 命令 | 口径 |
|---|---|
| `python3 tools/ai_flow/gate_check.py --only error-codes` | 全量，与 diff 无关 |
| `python3 tools/ai_flow/gate_check.py --only md --all-md` | 全量，扫仓库所有 .md |
| `python3 -m unittest tools.ai_flow.test_gate_check -v` | 门禁脚本自身的回归测试 |

`--only` 取值：`secrets` / `metric-dict` / `faq` / `error-codes` / `md`；
`--skip` 同理（逗号分隔），`--skip-faq` 是 `--skip faq` 的别名。

> 2026-11-24 前的 CI 跑的是 `--only md --base "$BASE..HEAD"`（增量），靠一段
> 「`base.sha` → `before` → `HEAD~1` 三级兜底」拿基线。那段兜底本身容易静默走
> `exit 0`，表现是「门禁看着绿其实什么都没验」，已与 `fetch-depth: 0` 一并删除。
> `--base` 选项本身**保留**（本地想只查自己动的几份 .md 时仍可用）。

> 全量门禁（含 mvn / playwright / 覆盖率）仍走
> [tools/verify_all.ps1](../verify_all.ps1) —— 需要 MySQL / Redis 常驻 + 前端 dev server。

---

## handoff.py —— 跨环节交接卡

```bash
python tools/ai_flow/handoff.py new --loop L1 \
  --summary "补了 ExamPrep 接口文档" \
  --verdict "gate_check 全绿，欠账 0" \
  --next "跑 L2 门禁" \
  --risks "R13 交互清单未抽查"

python tools/ai_flow/handoff.py latest
python tools/ai_flow/handoff.py list
```

`--loop` 取值：`L1` 文档同步环 / `L2` 门禁定位环 / `L3` 提交准备环 / `L4` 项目切换环。

卡片落在 `.workbuddy/handoff/`，已被 .gitignore 忽略，不入库。
