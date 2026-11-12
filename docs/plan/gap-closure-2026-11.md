# 计划书 vs 当前进度 · 缺口实现 + 验收审查计划

## Context

对照 `银龄伴诊项目计划书.md` 与实际代码（HEAD `445c5f0`，2026-11-12，工作区干净、与 origin/main 同步），找出未完成部分并实现，最后加载 code review 技能审查改动，达到可直接验收的标准。

**用户已拍板的范围决策（grilling 三轮结论）**：
1. 缺口范围 = 代码欠账 + 文档交付物 + 文档状态同步 + 待跑验证（**Docker 项经 Q4 确认放弃**，部署文档如实标注「二期展望」）；
2. code review 审查范围 = **v4.0..HEAD 增量 + 本轮新改动**；
3. 验收门禁 = **CI 同命令全绿 + Playwright e2e 全量 + L0 身份闸门**；
4. 用户手册 = 单文件 `docs/user-guide.md`；活动图 = **四条链全画**；部署文档 = 本机 dev + prod + 排障（方案 a）；
5. 验收证据 = 重跑同口径并**更新 `docs/reports/验收报告.md`**；Git = feature 分支开发 → 全绿 → 合并 main，**不 push**；
6. **PPT 与项目汇报视频不做**（用户明确排除）。

### 缺口核对表（已逐项实测核实）

| # | 缺口 | 现状证据 | 处置 |
|---|---|---|---|
| 1 | **L2**（P2）M7 平均分非原子：Java 读 `AVG` 再写快照，读写之间有丢失更新窗口 | `ReviewServiceImpl#refreshCompanionScore:296`（先 `aggregate()` 再 `update`） | **修**：改单条原子 SQL |
| 2 | **L3**（P3）`nianglin.export.max-rows` 未在 yml 显式声明（3 处 `@Value("${...:10000}")` 兜底） | `OperationParamServiceImpl:82` / `SettlementServiceImpl:106` / `StatisticsServiceImpl:95`；`application.yml` 无该键 | **修**：yml 显式声明 |
| 3 | **L6**（P3）覆盖率余量 —— BUG_LIST 数据已过期 | 实测 JaCoCo（今日报告）：service LINE **79.11%**、task **76.60%**（门禁 60%） | **只刷文档**：BUG_LIST 更新数字，欠账实质已解除 |
| 4 | **用户手册**（计划书迭代三/五交付物）不存在 | 全仓检索无果 | **写** `docs/user-guide.md` |
| 5 | **部署文档**（计划书迭代五交付物）不存在 | README 只有快速开始 | **写** `docs/deployment.md` |
| 6 | **活动图**（计划书迭代一交付物）不存在 | `docs/diagrams/` 只有 usecase/architecture/prototype | **写** 4 个 md（mermaid + 尽力渲染 PNG） |
| 7 | Docker 一键部署（计划书 §14 技术亮点）—— 用户裁定放弃 | 本机无 docker 命令 | 不实现；部署文档如实注明「二期」 |
| 8 | **文档状态漂移**：RTM 全写「待开发」（E1a/E2/E3/E4/E5/E6/G7 实际已落地）；`iteration-plan-v4` §7 验收清单全未勾；V4-P2-1/P2-2 spec 验收框全未勾；BUG_LIST L6 过期 | `docs/prd/功能拓展规划.md` 附录A；`docs/plan/iteration-plan-v4.md`；`docs/spec/V4-*.md` | **逐行核对代码后刷新** |
| 9 | **待跑验证**：`desktop-adapt-v2.md` 3 个 playwright spec 未跑（12/13/01） | `docs/spec/desktop-adapt-v2.md:270-272` | 全量 e2e 跑过后勾选 |
| 10 | 验收报告结论仍是「有条件通过」，Top 5 与 §11 未验证项未收口 | `验收报告.md` §1/§8/§11 | 按门禁 B 重跑同口径刷新 |

> 已确认**不是缺口**（避免重复做工）：M0–M12、V3.1–V3.7、V4 全部 8 项任务、验收报告 Top5 #2/#3/#5（README 回滚说明、CI 注释口径、probe_access_matrix.py 均已落地）、用例图/架构图/原型图/ER 图。

## Approach

按「代码欠账 → 文档交付物 → 状态同步 → 门禁验证 → 验收报告刷新 → code review 收口」顺序在 `feature/plan-gap-closure` 分支上完成；先修代码（让门禁先绿），再写文档、刷状态，最后统一跑验收并进 code review 循环。

### L2 修复设计（核心代码改动）

- 现状：`refreshCompanionScore` 先 `aggregate(companionId)`（查 `order_review` 得 AVG + COUNT），再 `companionProfileMapper.update(...)` 写 `companion_profile` 快照 —— 两条语句之间并发评价可丢失更新；BUG_LIST 建议 `UPDATE ... SET score=(SELECT AVG(...))`。
- 改法：新建**手写** mapper（仓库惯例：自定义 SQL 放独立手写 mapper，如 `ReviewReadMapper`/`StatisticsMapper`，不改 `gen_entity.py` 生成的文件），单条 `@Update`：
  ```sql
  UPDATE companion_profile
  SET score = COALESCE((SELECT ROUND(AVG(score),2) FROM order_review
                        WHERE companion_id = #{companionId} AND is_valid = 1 AND deleted = 0), 0),
      review_count = (SELECT COUNT(*) FROM order_review
                      WHERE companion_id = #{companionId} AND is_valid = 1 AND deleted = 0)
  WHERE user_id = #{companionId}
  ```
  口径与 `ReviewReadMapper.selectAverageScore/selectScoreDistribution`（`is_valid=1 AND deleted=0`）**完全一致**；目标表与子查询表不同，无 MySQL 1093 限制。
- `ReviewServiceImpl#refreshCompanionScore` 改为调用该 update；**保留**：事务内语义、Redis 缓存失效（TTL 兜底）、日志。
- 受影响测试：`ReviewServiceTest` / `ReviewServiceReplyTest` / `ReviewRulingServiceTest` 中对 `companionProfileMapper.update` 的 mock 断言改到新 mapper，并补一条「单条语句原子写快照」的断言。

## Files to modify

**代码**
- `backend/src/main/java/org/company/nianglin/mapper/` — 新增手写 mapper（L2 原子快照更新）
- `backend/src/main/java/org/company/nianglin/service/impl/ReviewServiceImpl.java` — `refreshCompanionScore` 改调原子更新
- `backend/src/test/java/org/company/nianglin/service/ReviewService*.java` 等 — 断言迁移 + 新增
- `backend/src/main/resources/application.yml` — `nianglin.export.max-rows: 10000` 显式声明（L3，注释说明 OPS_PARAM 可在线覆盖、此处为回落默认值）

**新文档**
- `docs/user-guide.md` — 四角色（家属/陪诊员/老人/管理员）操作手册，复用 `docs/reports/screenshots/` 15 张截图
- `docs/deployment.md` — 环境要求 / 本机 dev 5 进程启动 / prod profile fail-closed 部署 / 排障表；Docker 注明二期
- `docs/diagrams/activity-order.md` · `activity-medication.md` · `activity-auth.md` · `activity-admin.md` — 四条链活动图（mermaid，沿用 `architecture.md` 风格；PNG 尽力渲染）

**状态刷新（先核对代码再勾）**
- `docs/prd/功能拓展规划.md` — 附录 A RTM 状态逐行更新（E1a/E2/E3/E4/E5/E6/G7 → 已落地；G4 核实后如实标注）
- `docs/plan/iteration-plan-v4.md` — §7 验收清单逐项核验勾选
- `docs/spec/V4-P2-1-settlement-spec.md` · `V4-P2-2-vitals-spec.md` — 验收清单核验勾选
- `docs/agents/BUG_LIST.md` — L2/L3 标记已修复（附 commit）、L6 刷新为实测 79.11%/76.60%
- `docs/spec/desktop-adapt-v2.md` — §8 三个 spec 跑绿后勾选
- `docs/README.md` — 索引加 user-guide / deployment 两行
- `验收报告.md` — 重跑同口径刷新（结论、数字、Top 5 状态、§11 未验证项）
- `CHANGELOG.md` — 本轮变更记录

## Reuse

- 评分聚合口径：`backend/.../mapper/ReviewReadMapper.java`（`is_valid=1 AND deleted=0`，javadoc 说明为何显式写 deleted）
- 回滚/基线工具：`presentation/scripts/seed-hall-demo.py --rollback`（**跑 `mvn verify` 前必跑**，README:213 已固化）
- e2e 起停与闸门：`tools/e2e/start-services.ps1` / `stop-services.ps1` / `l0_identity_gate.py` / `fixture.py restore`
- 门禁脚本：`tools/check_secrets.py`、`tools/check_metric_dict_parity.py`（CI 同源）
- 截图素材：`docs/reports/screenshots/`（15 张）；原型文档引用方式见 `docs/diagrams/prototype.md`
- 部署事实来源：`README.md` 快速开始、`application.yml` profile fail-closed 注释、`docs/security/`

## Steps

**阶段 0 · 准备**
- [x] 新建分支 `feature/plan-gap-closure`（自 main；AGENTS §0.2）
- [x] 记录基线：`git log -1`、当前 JaCoCo 数字、测试计数（验收报告要引用）

**阶段 1 · 代码欠账（L2 + L3）**
- [x] L2：新建手写 mapper 的原子 `@Update`，改写 `ReviewServiceImpl#refreshCompanionScore`，迁移/新增单测
- [x] L3：`application.yml` 增加 `nianglin.export.max-rows: 10000` + 注释
- [x] `cd backend && mvn -B verify` 全绿（先回滚演示造数）

**阶段 2 · 文档交付物**
- [x] `docs/user-guide.md`（四角色分区，步骤 + 现有截图引用）
- [x] `docs/deployment.md`（本机 dev + prod + 排障；Docker 二期说明）
- [ ] ~~四张活动图 `docs/diagrams/activity-*.md`~~ **已取消**（2026-11-12 用户裁定：只专心文档与代码维护，活动图不生成）
- [ ] ~~PNG 渲染~~ **随活动图一并取消**
- [x] `docs/README.md` 索引同步

**阶段 3 · 状态漂移修正（逐项先核对代码/测试再勾）**
- [x] RTM 逐行核对（含 G4 `work-status` 实际状态）并更新
- [x] `iteration-plan-v4.md` §7 验收清单核验勾选
- [x] V4-P2-1 / V4-P2-2 spec 验收清单核验勾选
- [x] `BUG_LIST.md`：L2/L3 已修复（附本轮 commit）、L6 数字刷新
- [x] `desktop-adapt-v2.md` §8 待跑项（在阶段 4 跑绿后勾选）

**阶段 4 · 验收门禁（口径 B）**
- [x] 静态门禁：`python tools/check_secrets.py` + `python tools/check_metric_dict_parity.py`
- [x] 回滚演示造数 → `cd backend && mvn -B verify`（全绿）
- [x] `cd frontend && pnpm run lint:check` / `pnpm run test` / `pnpm run build`
- [x] `python -m pytest ai-service/tests -q`
- [x] `tools/e2e/start-services.ps1` → `l0_identity_gate.py` 10/10 → Playwright **全量 22 specs** → `fixture.py restore` 回基线

**阶段 5 · 验收报告刷新**
- [x] 按原报告同口径更新 `验收报告.md`：硬门禁表、规模数字（`tools/repo_metrics.py` 现取）、Top 5 逐项状态、问题清单（结转本轮新改动）、§11 未验证项如实保留（CVE/prod 实启/JMeter 仍列未验证）

**阶段 6 · Code Review 收口**
- [x] 读取 code review 技能 `C:\Users\wang\.agents\skills\code-review\SKILL.md`，按其流程审 **v4.0..HEAD 增量 + 本轮改动**（Standards + Spec 双轴）
- [ ] 修复 review 发现的问题 → 复跑对应门禁 → 直至无阻断项

**阶段 7 · 收尾**
- [x] `CHANGELOG.md` 记录本轮变更
- [ ] 全绿后**向用户确认** → 合并回 main、删除分支（**不 push**，AGENTS/git-workflow 约定）

## Verification

1. **门禁全绿**：`mvn -B verify`、`pnpm lint:check`/`test`/`build`、`pytest`、两个静态门禁 —— 与 CI 同命令。
2. **e2e 全量**：L0 闸门 10/10 + Playwright 22 specs 全绿（含 desktop-adapt-v2 待跑的 12/13/01 三个），跑后 `fixture.py restore` 数据回基线。
3. **L2 行为验证**：单测断言「快照刷新为单条 SQL 原子完成、口径含 `is_valid=1 AND deleted=0`、并发窗口消除」；既有评分聚合断言不回归。
4. **文档核验**：四张活动图流程与代码一一对应（状态机/调度/鉴权/管理路径）；手册步骤按页面实际走查一遍；部署文档启动命令按 README 实测口径。
5. **状态一致性**：RTM / 两份 spec 验收清单 / BUG_LIST / 验收报告之间数字与状态互不矛盾（验收报告数字用 `repo_metrics.py` 现取）。
6. **Code review 通过**：v4.0..HEAD + 本轮改动的 Standards/Spec 双轴审查无 P0/P1 级发现，结论写入执行记录。
