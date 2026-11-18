# 计划：撰写《UI 测试方案》与《功能测试方案》两份文档

## Context

项目（银龄伴诊：Spring Boot 3 后端 + Vue 3 前端 + Python AI 助手服务）已开发完成，需要安排 **两人分别执行 UI 测试与功能测试**。本次为**纯文档任务**：产出两份"测试方案 + 用例清单合一"的文档，详细说明各自要测哪些部分，并标注全部注意事项。

已通过 grilling 三轮确认的决策：

| # | 决策点 | 结论 |
|---|---|---|
| Q1 | 文档形态 | 测试方案 + 用例清单合一（执行者拿到一份即可干活） |
| Q2 | 范围 | 全量：四角色前端全流程 + 后端接口与权限 + 老人模式/适配/视觉 + AI 助手服务 |
| Q3 | 自动化定位 | 现有自动化作为**前置门禁**，手工测试只覆盖自动化测不到的部分 |
| Q4 | 存放与缺陷 | 文档放 `docs/reports/`；缺陷开 GitHub issue（needs-triage 等五标签流转） |
| Q5 | 分工切面 | **按关注点切分**：同一页面两人都进，但看的东西不同 |
| Q6 | 日程 | 写 4 天日程表 |
| Q7 | 环境 | 共用一套本地环境 + 错峰 + 每轮前重置基线 |
| Q8 | 输出物 | 执行记录模板 + 准出标准 + 截图存档要求 |
| Q10 | 粒度 | 中等：每模块 10–20 条用例，含前置/步骤/预期/优先级，两份合计约 150–250 条 |
| Q11 | AI 助手深度 | 降级模式为主（LLM_BASE_URL 留空照常可用），配了 LLM key 则追加"可选"用例 |
| Q12 | 与验收报告 | 并存互不修改，`docs/reports/验收报告.md` 不动 |
| Q13 | 提交 | 纯文档，经用户确认**免开分支**，直接在当前分支提交 |

## 交付物（Files to create）

1. `docs/reports/UI测试方案.md`
2. `docs/reports/功能测试方案.md`

（计划文件：`plans/test-docs-ui-func.md`，即本文件）

## 两份文档的共同骨架

1. **目标与范围** —— 明确本文档管什么、与另一份的分工切面（避免两人重复）。
2. **测试环境与前提** —— 启动/重置/账号/验证码/AI 服务降级（见下方"环境事实"）。
3. **前置门禁（自动化基线）** —— 全绿才允许开始手工测试：
   - 前端：`pnpm run lint:check`、`pnpm run test`、`pnpm run build`、`pnpm exec playwright test`（22 个 spec）
   - 后端：`mvn -B verify`（511 测试 + JaCoCo 门禁）
   - AI 服务：`python -m pytest ai-service/tests -q`
   - 静态门禁：`python tools/check_secrets.py`、`python tools/check_metric_dict_parity.py`
4. **分模块用例表** —— 中等粒度，列：用例编号 / 前置条件 / 步骤 / 预期结果 / 优先级(P0–P2) / 结果 / 缺陷链接 / 截图路径。
5. **注意事项**（各文档专属 + 共同合规红线）。
6. **缺陷记录流程** —— GitHub issue + 五标签 + P0–P3 严重度定义（P0 阻断/P1 主流程错误/P2 次要错误/P3 体验瑕疵）。
7. **4 天日程表 + 执行记录模板 + 准出标准**。

- 4 天日程：Day1 环境准备 + 前置门禁 + 冒烟；Day2–3 分模块执行；Day4 缺陷复测 + 交执行报告。
- 准出标准：前置门禁全绿、P0/P1 缺陷清零、用例通过率 ≥95%、P2 有处置结论、执行报告归档。
- 执行报告：测完各存 `docs/reports/UI测试执行报告-YYYY-MM-DD.md` / `功能测试执行报告-YYYY-MM-DD.md`（新文件，不回写验收报告）。

## 《UI 测试方案》切面（只看"长什么样、点起来什么感觉"）

- **视觉与设计规范**：`docs/design/` 规范、`--nl-*` 令牌与 `$variable`（无硬编码颜色/字号）、字体层级、间距对齐、卡片/按钮一致性。
- **布局与适配**：桌面宽屏、管理端响应式、移动端（对应 E2E `10-form-factor` / `12-admin-responsive` / `13-client-mobile` 覆盖面的人工复核）。
- **老人模式**：`<html class="elderly-mode">` 字号放大、触控区域 ≥44px、菜单隐藏（`elderlyHidden` 路由）、对比度。
- **交互一致性**：**逐条过 `docs/agents/interaction-checklist.md`**（空态/加载态/错误态/表单校验反馈/按钮防重复提交/跳转回跳/ElMessage 统一用法）。
- **浏览器兼容**：Chrome 主测，Edge 必测，Firefox/Safari 可选（标注为可选）。
- **视觉走查**：每页截图存 `docs/reports/screenshots/`，按用例编号命名。
- **不查**：状态机是否正确、数据对不对、权限是否越权 —— 那是功能测试者的活。

## 《功能测试方案》切面（只看"逻辑对不对、数据准不准、权限守没守"）

按 `docs/api/01–14` 与 `frontend/src/views/` 模块组织，每模块 10–20 条：

1. **认证与账号**（01）：登录/图形验证码/改密/忘记密码/登出令牌失效/登录日志。
2. **家属-老人**（02）：绑定/解绑/UNBOUND 边界/默认老人/档案脱敏。
3. **订单全流程**（03）：64 条种子订单状态分布下 PENDING→ACCEPTED→IN_SERVICE→COMPLETED→REVIEWED/CANCELLED 全流转 + 非法流转拒绝（`OrderStatus.canTransitTo`）+ 乐观锁并发。
4. **陪诊执行**（04）：接单/签到打卡/执行记录。
5. **用药**（05）：计划/任务 520 条分布（MISSED 193/TAKEN 327）/漏服扫描/阈值。
6. **评价投诉**（06）：评分快照、投诉 PENDING 9/PROCESSING 7 流转。
7. **站内信 + 订单聊天**（07/11）：ELDER 只读、ADMIN 仲裁只读、敏感词拦截、M13 边界（不做自由加好友 IM）。
8. **管理端**（08）：资质审核、封禁（`elder030` DISABLED 反向用例）、仲裁、运营参数。
9. **统计与导出**（09）：时间参数、导出 max-rows 10000、取消下载。
10. **文件上传**（10）：类型/大小/缺文件错误码。
11. **老人健康档案**（12）。
12. **AI 助手**（13）：意图识别、FAQ 30 条锚点、guardrail 建议拦截（红线1）、免责声明、SSE 流式；LLM key 未配则降级模式测 FAQ 原文回答，标"可选"用例。
13. **考试准备**（14 exam-prep）。
14. **权限矩阵**：4 角色 × 只读/写/管理 = 12 条越权用例口径（`docs/agents/testing.md` §6.1），用 probe 或接口手工核对。
15. **错误码核对**：接口返回码与 `docs/api/README.md` 一致。
- **不查**：视觉/适配/老人模式观感 —— 归 UI 测试者。

## 环境事实（写进两份文档的"前提"章节）

- 启动/停止：`tools/e2e/start-services.ps1` / `stop-services.ps1`；每轮测试前 `python tools/e2e/restore_clean_baseline.py` 重置数据库（README 说明是否写库/口令）。
- 种子账号（`docs/agents/FRONTEND_CONTRACT.md` §10.7）：`admin`/`superadmin`(1/2)、`fam001`(101)、`elder001–elder030`(201 起，**230 为封禁账号**)、`comp001`(301，APPROVED/AVAILABLE 可接单)；口令统一，明文由 `NIANGLIN_SEED_PASSWORD` 提供，默认值见 `backend/sql/tools/fixture_credentials.py`，前端演示预填读 `frontend/.env.development` 的 `VITE_DEMO_PASSWORD`。
- 登录必须带图形验证码：`GET /api/auth/captcha`，Redis 键 `captcha:<key>`（TTL 300s）直取，**不做 OCR**。
- 种子数据条数以 `docs/agents/FRONTEND_CONTRACT.md` §10.8 为准（真源 `backend/sql/V2__seed_data.sql`），**核对用只读 SQL，勿照抄旧数**。
- AI 服务 8081 只监听 127.0.0.1；`LLM_BASE_URL`/`LLM_MODEL` 留空 = 降级模式照常可用。

## 注意事项（两份文档都必须写，共同章节）

1. **合规红线（AGENTS.md §0.1）**：不做诊断不开药方（发现"建议服用/推荐剂量/诊断为"文案即为 P0 缺陷）；隐私脱敏（手机号/身份证/姓名，localStorage 禁存敏感信息）；一期不做支付/导航（发现即提缺陷）。
2. **数据污染**：两人共用一套环境，**错峰执行**，每轮前重置基线；写操作集中在功能测试者，UI 测试者以只读/低风险操作为主。
3. **假绿教训**（BUG_LIST 记录）：不要为了让用例变绿放宽断言；发现"假红"先查请求是否真的到达鉴权层。
4. **缺陷不入库明文**：issue 里不贴真实手机号/密码/身份证。
5. **改 FAQ 知识库**才需要跑 `python tools/check_faq_anchors.py`（测试者一般不改，只报缺陷）。
6. **截图**：UI 测试者每页/每异常态截图存 `docs/reports/screenshots/`，文件名对应用例编号。
7. 测试者动手前必读：`AGENTS.md`、`docs/agents/interaction-checklist.md`（UI）、`docs/agents/testing.md`、对应 `docs/api/*.md`（功能）。

## Steps（实现清单）

- [x] 写 `docs/reports/UI测试方案.md`：共同骨架 + UI 切面用例（视觉/适配/老人模式/交互清单逐条/浏览器/截图），约 80–120 条用例。
- [x] 写 `docs/reports/功能测试方案.md`：共同骨架 + 功能切面用例（上列 15 个模块），约 90–140 条用例。
- [x] 两份文档交叉引用分工切面与对方路径，避免重复。
- [x] 核对文档内所有路径/命令真实存在（`tools/e2e/README.md`、spec 文件名、api 文档编号、§10.7/§10.8 引用）。
- [x] 检查无合规违禁文案、无明文密码（占位口令只写"见 NIANGLIN_SEED_PASSWORD 默认值"）。
- [x] 经用户确认后免开分支，直接在当前分支提交两个 .md（提交前需再次确认）。

## Verification

- [x] 两份文档互相不重叠：UI 文档不含数据正确性断言，功能文档不含视觉/适配用例。
- [x] 引用的每条自动化命令与 CI（`.github/workflows/ci.yml`）口径一致。
- [x] 用例编号、记录表列、准出标准在两份文档中格式一致。
- [x] `python tools/check_secrets.py` 通过（文档不引入密钥字样告警）。
