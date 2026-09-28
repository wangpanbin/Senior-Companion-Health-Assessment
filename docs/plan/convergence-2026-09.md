# 收口迭代计划（Convergence Iteration）

| | |
|---|---|
| 状态 | 执行中（PR-0 ✅ · PR-1 ✅ 闸门 1 达成，2026-09-28） |
| 立项日期 | 2026-09-28 |
| 关联 | `plan.md` 迭代四/五/六 · `AGENTS.md` · `docs/adr/0007` `0009` `0010` |
| 产出定位 | 一次性补齐 plan.md 迭代四/五/六的交付缺口，让「代码做完了」变成「可验收、可展示」 |
| 验收基线 | **`docs/agents/reports/BASELINE_2026-09-28.md`**（每个数字带复现命令） |

---

## 一、为什么现在做这件事

2026-09-28 的代码盘点结论：**代码完成度约 95%，但项目没有任何一处能证明它完成了。**

| 口径 | 数值 | 事实源 |
|---|---|---|
| 代码完成度 | M0–M12 约 95% | 后端 13 模块全落地；前端 38 个 view 零静态壳、零漏 import；`src/api/` 80 个函数 0 mock；`docs/api/` 抽样 74 端点与代码 100% 命中 |
| 验收清单完成度 | **0 / 78** | `plan.md` 的 78 个 `- [ ]` 一个都没勾 |
| 交付物完成度 | 测试报告 3/4，用例图 0，架构图 0，原型图 0 | 无任何 `.drawio` / `.puml`；`reports/` 被 gitignore，E2E 截图无法入库 |

**「完成度」有三个互不相干的口径，本文一律显式标注用哪个**，不再混用。

**触发原因（真实缺口，不是臆想）：**

1. **代码在两处违反项目宪法，而测试是绿的。**
   - `AGENTS.md` §4.1「所有状态流转必须通过 `canTransitTo`，禁止硬编码」→ 生产代码零调用，`OrderServiceImpl` 实测 **4 处**硬编码 `from != X` 守卫（L316 / L359 / L442 / L480）+ 终态守卫（L599–L607）。`OrderStatusTest` 86 行测的是一个没人调用的方法，转移表改了不会有任何测试失败。
   - `AGENTS.md` §3.5 老人模式硬约束 → 代码在 ≥768px 强制关闭（**此项已由 ADR-0007 Q4=II 决策**，是 `AGENTS.md` 未同步，见 §四）。

2. **交付缺口是被主动砍掉的，不是漏的。** commit `6150373`(2026-09-20)「docs: 清理文档中部署/PPT 撰写相关信息」把 `plan.md` 改成"专注代码编写，排除答辩交付物"。该决策本轮**推翻**。

3. **README 快速开始跑不通。** `application-dev.yml` 用 `${MYSQL_PASSWORD:CHANGE_ME}`，注释让建的 `application-local.yml` 根本不存在。新人照 README 跑到启动必挂 `Access denied`。

---

## 二、已拍板的五项决策

| # | 问题 | 决策 |
|---|---|---|
| Q1 | 本轮主线 | **A** 先验收 → 收口硬约束 → 补交付物 → 补功能 |
| Q2 | 老人模式 ≥768px 禁用怎么裁 | **B** 保留豁免，把 `AGENTS.md` 对齐到已决策状态（**非新决策，见 §四**） |
| Q3 | 答辩交付物是否在本轮范围 | **A** 全量复活，P0 = 测试报告 + 截图入库，P1 = 三张图 |
| Q4 | 收尾标准用什么形式 | **A + B** 每步绑可执行命令；通过的事实回填 `plan.md` checkbox |
| Q5 | git 模型怎么收敛 | **A** 就地收敛到 `main` / `develop` + tag |

**项目决策（用户拍板）：**

- **团队推进方式**：单人推进，后期拉人补文档与测试。测试报告与 E2E 报告需按此口径如实署名，不虚构分工。
- **JMeter**：由用户自行安装。T3.2 的前置条件是 `JMETER_HOME` 可用 + 5.6.3 解压到位；未就位则 T3.2 阻塞，**不得用自研脚本静默替代**（替代方案需先决策再执行）。
  **2026-09-28 已实测就位**：`F:\software\apache-jmeter-5.6.3`，见 §二之二。

---

### 二之二 · JMeter 环境实测（2026-09-28）

| 项 | 实测值 | 判定 |
|---|---|---|
| 安装目录 | `F:\software\apache-jmeter-5.6.3` | ✅ |
| `bin\jmeter.bat` / `lib\ext` / `bin\ApacheJMeter.jar` | 均存在 | ✅ |
| 非 GUI 端到端执行 | `jmeter -n -t smoke.jmx -l smoke.jtl -j run.log -f` → **EXIT=0**，JTL 与 `jmeter.log` 正常产出 | ✅ |
| 与 Java 21 兼容 | `JAVA_HOME=D:\develop\Java\jdk-21`，实际跑通 | ✅ |
| **`JMETER_HOME` 系统环境变量** | **空（未设置）** | ❌ **阻塞 T3.2** |
| 不设 `JMETER_HOME` 直接跑 | 报 `The JMETER_HOME environment variable is not defined correctly`，JTL **未生成**（EXIT 仍是 0，**不能靠退出码判断成败**） | ❌ |

**两处需要注意的坑：**

1. **不设 `JMETER_HOME` 会失败，但退出码仍是 0。** 自动化脚本若只看 `$LASTEXITCODE` 会误判成功，必须同时校验 JTL 是否生成。T3.2 的收尾标准据此写成「退出码 0 **且** JTL 行数 > 1」。
2. **`jmeter --version` 在 Java 21 下只输出到版权行**（缺 Java 版本 / OS 那段），属 5.6.3 + Java 21 的 stdout 瑕疵，**不影响实际执行**。不要拿 `--version` 的输出完整性当健康检查。

**T3.2 前置动作（需人工执行一次，属机器级配置变更）：**

```
setx JMETER_HOME "F:\software\apache-jmeter-5.6.3"
```

并把 `F:\software\apache-jmeter-5.6.3\bin` 加入 `PATH`。**这一步会改系统环境变量，未获明确授权前 AI 不代劳。**

---

## 三、核心原则：先拿到事实

现有 `iteration-test-report-1/2/3.md` 与 `e2e-report.md` **全部是 2026-09-16 同日补写归档的**，从未在本轮验证过。因此：

> **阶段 1 未产出实测 baseline 之前，阶段 2 及以后的每一条收尾标准都无法判定真假。**

跨阶段铁律：各阶段 PR **不合并不进下一阶段**。

---

## 四、前置纠正：Q2 其实不是新决策

盘点时发现 `AGENTS.md` §3.5 与代码冲突，追查后确认：

- `docs/adr/0007-desktop-form-factor.md` **已记录** Q4 = II（桌面端禁用老人模式），2026-09-18 用户拍板；
- `CONTEXT.md` §模式开关**已写明**「生效范围（本轮决策后）：仅 Mobile 形态生效」；
- 代码三重防御**已实装**：`app.js:53` action 直接 return、`applyElderlyClass()` 摘 class、`elderly.scss:155` ≥768px `!important` 兜底。

**唯一不同步的是 `AGENTS.md` §3.5。** 所以 Q2 的落点不是写新 ADR，而是：

| 动作 | 状态 |
|---|---|
| `AGENTS.md` §3.5 补「仅 Mobile 生效」豁免行 + 三重防御说明 + 引用 ADR-0007 | ✅ 本轮已落盘 |
| `docs/adr/0007` 状态 Proposed → Accepted + 补落地验证 | ✅ 本轮已落盘 |
| ~~新建 ADR 记录老人模式豁免~~ | ❌ 取消，会与 ADR-0007 重复 |

---

## 五、执行计划：6 阶段 / 21 ticket

### PR-0 · 机械前置（10 分钟）

> **唯一的顺序强约束**：改名是机械操作，但 PR-1~PR-5 都要建在分支上。先改名 = 后续 5 个 PR 建在新模型上；放最后 = 5 个 PR 全部 rebase。

| # | ticket | 收尾标准 |
|---|---|---|
| 0.1 | cherry-pick `feature/docs-m2-state-machine-review` 那个未合并提交 | 该提交内容已入主线，`git log --all` 中不再有孤岛 |
| 0.2 | 删 5 个 stale 分支（4 个 0 ahead，确认无用后删） | `git branch -a` 只剩 `master` |
| 0.3 | `master` → `main`，建 `develop` 并推 | `git branch -a` 显示 `main` + `develop` |

---

### 阶段 1 · 验收基线（0.5–1 天）— 拿到事实

先修断点：`application-dev.yml` 的 `${MYSQL_PASSWORD:CHANGE_ME}` 没有出口。

| # | ticket | 收尾标准（可执行） |
|---|---|---|
| 1.1 | 补 `application-local.yml.example` + README 补 export 步骤 | `mvn test` 退出码 0，输出含 `Failures: 0, Errors: 0, Skipped: 0` |
| 1.2 | 后端构建基线 | `mvn -q clean package` 成功；`java -jar` 启动后 `GET /api/health` 返回 200 且 body 含 `"code":200` |
| 1.3 | 前端基线 | `pnpm lint` 0 警告 → `pnpm build` 成功 → dev server `/` 返回 200 |
| 1.4 | **E2E 基线（最重）** | 按 `docs/agents/FRONTEND_CONTRACT.md` §13 起服务 → `pnpm exec playwright test`；**若红，停下来定位，不往后走** |
| 1.5 | 基线报告落盘 | `docs/agents/reports/BASELINE_2026-09-28.md` 存在，每个数字后面跟着能复现它的命令 |

> **⚠️ PowerShell + Maven 陷阱**：任何 `-Dkey=value` 形态参数（`-Dtest=`、`-Dmaven.repo.local=`、`-DskipTests`）在 PowerShell 下会被解析成赋值，必须用 `cmd /c 'cd /d <backend> && mvn.cmd ...'` 包装。

**🚧 总闸门 1**：baseline 文件存在且每个数字可复现。**没有它，阶段 2 不启动。**

#### ✅ 阶段 1 实测结果（2026-09-28，commit `70e2d65`）

| ticket | 判定 | 实测 |
|---|---|---|
| 1.1 | ✅ | 575 tests / 0 failures / 0 errors / 0 skipped |
| 1.2 | ✅ | package EXIT=0（84.5MB jar）· `/api/health` HTTP 200 body `"code":200` · Flyway 1/2/3 全 success |
| 1.3 | ✅ | lint 0 警告 · build EXIT=0 · dev server 5141 返回 200 |
| 1.4 | ✅ | 首跑 163 passed/**1 failed** → 定位修复 → **164 passed** |
| 1.5 | ✅ | `docs/agents/reports/BASELINE_2026-09-28.md`（347 行） |

**闸门 1 达成，阶段 2 可启动。** 本阶段暴露 3 个此前无人知晓的阻断项（均已修复）：

1. **🔴 `pnpm build` 失败** —— `reviews.vue` / `order-detail.vue` 引用了 `variables.scss` 中不存在的
   `$nl-radius-md` / `$nl-radius-sm` / `$nl-shadow-1`（共 5 处）。**lint 不编译 SCSS、E2E 不截图，
   所以此前完全不可见** —— 这是「代码完成度 95%」口径的盲区。
2. **🔴 E2E 首跑即红** —— 根因是 `comp025` 夹具在 **2026-09-19** 被某次管理端运行审批通过，
   脏数据残留（种子脚本声明 `PENDING`，Flyway 已执行不重播）。**恢复夹具而非改已执行的 `V2` 脚本**。
   与 `FIXTURE_ROLLBACK_PLAN.md` 记载的「L1 不可重跑」同源。
3. **🟡 端口文档漂移 5173→5141** —— 代码是 5141，5 处文档写 5173；
   **额外发现真实代码缺陷**：`WebMvcConfig:30` 的 `@Value` 兜底默认值也是 5173。

**覆盖率基线（T3.1 前置）**：行 **65.9%** / 分支 **49.6%**，M12 硬指标 60% 达标。
但 `AdminServiceImpl` 25.8%（450 行最大类）、`UserServiceImpl` 28.0%、`CaptchaServiceImpl` 5.3%
是三个明显空洞 —— T3.1 应如实列出，**不要用总量 65.9% 掩盖**。

> **⚠️ 遗留卡点（需人工）**：远端 `master` 删不掉 —— GitHub 拒绝删除默认分支。
> 需在 Settings → Branches 把默认分支 `master` 改为 `main`，之后
> `git push origin --delete master` 即可生效。本地与 `origin/HEAD` 均已指向 `main`。

---

### 阶段 2 · 硬约束收口（1 天）— 让代码兑现 AGENTS.md

| # | ticket | 收尾标准（可执行） |
|---|---|---|
| 2.1 | **订单状态流转收口**（依 `docs/adr/0010`） | ① `grep -rn "setStatus" backend/src/main/java/.../service` 命中**全部**落在 `OrderTransitionService` 内<br>② `grep -rn "from != \|from == " backend/src/main/java/.../service` 返回空（硬编码守卫清零）<br>③ `canTransitTo` 在 `service` 包内**恰好 1 处**调用点（即 `OrderTransitionService.transition`）<br>④ **反向验证**：删 `OrderStatus.TRANSITIONS` 一条边，`mvn test -Dtest=OrderStatusTest` **必须失败** → 改回 |
| 2.2 | 老人模式文档对齐 | ✅ **已完成**（见 §四） |
| 2.3 | 越权面补齐 + 探针退场 | ① 陪诊员入驻 4 端点有 `@PreAuthorize`；越权测试类 3 → ≥4，`mvn test` 绿<br>② `grep -rn "PermissionProbeController\|TODO(W16)" backend/src` 返回空<br>③ **12 条越权用例（4 角色 × 3 类）在正式测试类里可定位到** —— 这是删探针的前置条件 |
| 2.4 | 仓库卫生 + 明文口令 | ① `python backend/sql/tools/check_seed_password.py` 退出码 0（白名单外无扩散）<br>② `git status --porcelain` 返回空<br>③ `docs/db/` 中文文件名：**先确认磁盘实际编码再动**；改文件名必须同步改 `gen_db_docs.py`，否则下次重跑又变回去 |
| 2.5 | 费用明细模型落地（依 `docs/adr/0009`） | `V4__*.sql` + entity + mapper + 端点齐备；`validate-on-migrate` 通过 |

#### ✅ 阶段 2 实测结果（进行中）

| ticket | 判定 | 说明 |
|---|---|---|
| 2.1 | ✅ | 新增 `OrderTransitionService` 单一入口，6 处流转 + `forceTerminal` 全部改经此入口；**反向验证实测通过**（删 `TRANSITIONS` 一条边 → 两个测试类双双变红）；`mvn test` **585 tests / 0 failures**。ADR-0010 置 Accepted。 |
| 2.2 | ✅ | 见 §四 |
| 2.3 | ⏳ | 探针退场的前置条件是 12 条正式越权用例，未开始 |
| 2.4 | ✅ | 见下方处理结论 |
| 2.5 | ⏳ | 未开始 |

##### T2.4 明文口令处理结论（32 处 → 白名单 13 处）

实测 32 处比原计划设想广得多。按「**是否参与生产**」重新分类，而不是无差别替换：

| 类别 | 处数 | 处理 |
|---|---|---|
| 夹具脚本（`e2e_*.py` / `bench_setup` / `jmeter_fixture`） | 11 | **收敛到单一真源** `fixture_credentials.py`，可用 `NIANGLIN_SEED_PASSWORD` 覆盖。改口令从「动 11 个文件」变成「动 1 个」 |
| 生产代码 `AdminServiceImpl` | 1 | 改读 `SecurityProperties#getDefaultPassword()`（投产由 `DEFAULT_RESET_PASSWORD` 覆盖）。**保留固定值是有意设计**：现实中是电话里念给用户听，随机串必然念错，安全性靠「首次登录强制修改」 |
| 生成器（`GenSeedSecrets.java` / `gen_seed.py`） | 2 | 改读环境变量，明文不落源码 |
| 前端演示预填 | 1 | 改读 `.env.development` 的 `VITE_DEMO_PASSWORD`（仅 DEV 生效，不进生产构建） |
| Swagger `@Schema` 示例 | 2 | 改为 `********`，避免接口文档直接教人用默认口令 |
| 单测固定输入 | 2 | 保留（不进生产） |
| `V2__seed_data.sql` | 1 | ⚠️ **已执行的 Flyway 脚本，不得修改**（checksum 校验）。改口令需新开 `V5__*.sql` 走 UPDATE |
| 文档 | 4 | 改为指向 `fixture_credentials.py` / 环境变量 |

**新增防扩散门禁** `backend/sql/tools/check_seed_password.py`：把「允许出现在哪里」写成显式白名单
（含每条的原因），白名单外出现即退出码 1。**已做反向验证** —— 故意造一处扩散，脚本确实报 `BAD` 并 EXIT=1；
删除后恢复 `PASS` / EXIT=0。没有这个脚本，手工清理只能管到当次。

> 途中踩过一个真实的坑：曾误改 `V2__seed_data.sql` 的注释（想加说明），
> 立刻意识到这会触发 Flyway checksum 校验失败，已 `git checkout` 回滚。
> **已执行脚本连注释都不能动** —— 这是 AGENTS.md §5 的硬约束。

---

### 阶段 3 · 交付物（1–1.5 天）— 让竞讲有东西可展示

| # | ticket | 收尾标准（可执行） |
|---|---|---|
| 3.1 | **迭代五完整测试报告（P0）** | ① `target/site/jacoco/index.html` 存在，核心 Service 覆盖率 ≥ 60%（`plan.md` M12 硬指标）<br>② 报告每个数字后跟一条复现命令<br>③ 遗留 Bug 按 P0/P1/P2 排序，**P0/P1 清零**（M12 硬指标） |
| 3.2 | 压测（P0，**JMeter 已就位**） | `JMETER_HOME` 已设 → 50 并发抢单报告：成功数 = 1、失败数 = 49。<br>**收尾判据必须双条件**：`EXIT=0` **且** 产出的 JTL 行数 > 1（只判退出码会被 0 假阳性骗过，见 §二之二） |
| 3.3 | 解禁截图入库（P0） | `reports/` 迁到 `docs/reports/screenshots/` 并放开 gitignore；`git ls-files docs/reports/screenshots/ \| wc -l` ≥ 10，每张能对上 spec 名 |
| 3.4 | 三张图（P1） | `docs/diagrams/` 下 3 组 `.md`(mermaid) + 3 个 `.png`；每张 mermaid 源码能渲染成功；原型图用真实页面截图而非手绘 |

**🚧 总闸门 3**：`iteration-5-test-report.md` 存在 + P0/P1 清零 + 3 组图齐全 + 截图 ≥ 10 张。

---

### 阶段 4 · 功能残项（1 天）— 优先级最低，最后做

| # | ticket | 收尾标准（可执行） |
|---|---|---|
| 4.1 | M4 费用明细页面（实体部分已在 T2.5 落地） | 陪诊员记账页与家属订单详情页均能展示 ≥2 条明细行，且区分代垫 / 服务费 |
| 4.2 | M6 周视图 | 月历 offset 算法从 `elder/medication.vue` + `family/medication.vue` 两处重复上提为 composable（两处均复用）；周视图 7 列；数据与 `SELECT * FROM medication_task WHERE user_id=? AND date=?` 逐条一致 |
| 4.3 | M5 `ElNotification` + M8 SSE 未读数 | 打卡后 3 秒内弹出通知（playwright 断言通知 DOM 出现，**不靠肉眼**）；未读数改 SSE，`api/message.js` 不再是轮询 |

**🚧 总闸门 4**：`mvn test` + 13 spec 全绿。

---

### 阶段 5 · 收口（0.5 天）

| # | ticket | 收尾标准 |
|---|---|---|
| 5.1 | 回填 `plan.md` 78 个 checkbox | `Select-String plan.md '^- \[ \]' \| Measure-Object` → **0** |
| 5.2 | 补 `v1.0/v2.0/v3.0` tag + origin 同步 | `git tag -l` 3 个；`origin/main..main` → 0 |
| 5.3 | **最终总闸门脚本** | `tools/verify_all.ps1` 一条命令串联 lint / test / build / playwright / coverage / 压测，输出 PASS/FAIL 汇总 |

---

## 六、排期与 PR 切分

| PR | 内容 | 预估 | 依赖 |
|---|---|---|---|
| PR-0 | `chore/git-master-to-main` | 10 min | — |
| PR-1 | `chore/verification-baseline`（阶段 1） | 0.5–1 d | PR-0 |
| PR-2 | `fix/hard-constraint-convergence`（阶段 2） | 1 d | 闸门 1 |
| PR-3 | `docs/competition-deliverables`（阶段 3） | 1–1.5 d | 闸门 2 |
| PR-4 | `feat/residual-features`（阶段 4） | 1 d | 闸门 3 |
| PR-5 | `chore/convergence-closeout`（阶段 5） | 0.5 d | 闸门 4 |

**合计约 4–5 个工作日。** 最大变量：

- **T1.4 E2E 从未在本轮跑过，红了要翻倍**；
- **T3.2 JMeter 安装**（用户自装，未就位则阻塞）。

---

## 七、风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| E2E 从未跑过，阶段 1 暴露大量失败 | 全线延期 | 总闸门 1 就是为此设的：红了就停在阶段 1，不带病推进 |
| 覆盖率 < 60% 导致 M12 硬指标不达标 | 阶段 3 阻塞 | T1.1 先测出真实覆盖率基线，不达标则提前补测而非事后凑 |
| JMeter 未就位 | T3.2 阻塞 | 二进制已就位（§二之二）；**剩余唯一缺口是 `JMETER_HOME` 环境变量**，需人工 `setx` 一次 |
| 单人推进导致 `plan.md` 覆盖的「4 人团队」叙事失真 | 评审扣分 | 测试报告如实署名，不虚构分工 |

---

## 八、遗留未决项

- [x] T3.2 的 JMeter 安装确认 —— ✅ 2026-09-28 实测：`F:\software\apache-jmeter-5.6.3` 非 GUI 端到端跑通、Java 21 兼容。**剩 `JMETER_HOME` 需人工 `setx` 一次**（详见 §二之二）
- [ ] `docs/db/` 中文文件名的磁盘实际编码待确认（影响 T2.4 的第 ③ 项）
- [x] `docs/agents/designs/M4-order.md` 同步引用 ADR-0009 / 0010 —— ✅ 2026-09-28 已落盘：§3 加「状态机权威未接线」修订块（含 4 处守卫行号表）、§4 验收项 4 由 ✅ 修正为 ⚠️、§5 补两条遗留（状态机未接线 + 费用明细缺失）
