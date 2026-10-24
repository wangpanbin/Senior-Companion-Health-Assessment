# AGENT.md — 银龄伴诊 · AI 编码工具协作规范

> **本文件是 AI 编码助手（Claude Code、Cursor、Copilot、通义灵码等）在本仓库内生成、修改、补全代码时必须遵守的规范总览。**
>
> 它从 `docs/plan/plan.md`、`README.md`、`docs/api/README.md` 以及现有骨架代码中提炼而来，等同于"机读版项目宪法"。
> AI 在回答或写入本仓库前，必须先完整读取并理解本文件，再按其中的规则行事。
>
> 配套阅读：`README.md`（项目基线）、`docs/plan/plan.md`（模块拆分与验收标准）、`docs/api/README.md`（接口全局约定）。
>
> ⚠️ **本文件只装得下红线、导航、底线与验收清单**（运行时给它 32 KB 上下文预算）。
> 编码细则（后端 / 前端 / 业务规则 / 数据库 / 测试 / Git / 接口先行 / 文档同步）在
> `docs/agents/` 下，**索引与「什么时候该读哪一份」见 §0.3** —— 动手改对应模块前先读那一份。

---

## 0 · 适用范围与基本立场

| 项 | 约定 |
|---|---|
| 适用对象 | 任何在本仓库目录（`F:/test/Senior Companion Health Assessment/` 及子目录）下生成、修改、补全、审查代码的 AI 工具 |
| 项目性质 | 软件工程课程设计 · 4 人团队 · 17 周 6 迭代 · 老年人就医陪诊与用药协同管理 |
| 技术栈（不可改） | 前端 Vue 3.5 + Vite 6 + Element Plus 2.9 + Pinia + Vue Router 4 + Axios + Sass；后端 Spring Boot 3.3.5 + JDK 21 + MyBatis-Plus 3.5.7 + Spring Security + JJWT 0.12.6 + MySQL 8.0 + Redis + Flyway + Knife4j + EasyExcel + Hutool + Lombok；AI 助手服务 Python 3.12 + FastAPI + LangChain（独立进程 `ai-service/`，端口 8081） |
| 包名 / 命名空间 | 后端统一 `org.company.nianglin`；前端模块入口 `@/...`（Vite alias 指向 `frontend/src`） |
| 前端包管理器 | **pnpm**（lockfile 为 `frontend/pnpm-lock.yaml`；禁止把 `package-lock.json` 重新引入仓库） |
| 接口前缀 | 所有后端接口统一 `/api` 前缀 |

### 0.1 三条不可逾越的合规红线（每条都是 docs/plan/plan.md 计划书"合规与隐私说明"里的硬约束）

1. **不做诊断、不开药方**。药品字典只返回通用信息（名称、规格、通用说明），接口与页面必须出现免责声明文案。**禁止**出现"建议服用""推荐剂量""诊断为""可能是 XX 病"等表述。
2. **隐私最小化**。密码 BCrypt 存储（值以 `$2a$` 开头）；接口返回时手机号 `138****8888`、身份证号 `110101********4567`、姓名按场景脱敏；本地存储（localStorage）**禁止**写入密码、身份证号、完整手机号。

   > **「按场景脱敏」已记录的唯一豁免：订单详情返回全名。**
   > `GET /api/order/{id}` 的 `elderName` / `companionName` **不脱敏**，这是刻意设计而非疏漏，三层一致：
   > ① 访问由 `OrderService#requireInvolved` 把守，非相关方一律 `3004`，能打开详情的只有下单家属本人 / 接单陪诊员本人 / 就诊老人本人 / 管理员；
   > ② `OrderVO#ofDetail` 的 javadoc 写明理由——「该看的人看不到该看的信息」；
   > ③ `OrderController#detail` 的 `@Operation` 契约里明写「此处姓名返回全名」，Knife4j 文档可见。
   > 同 VO 的 `ofList` / `ofHall` 仍走 `MaskUtil.name()`，所以**列表脱敏、详情全显**是有意的不一致。
   > ⚠️ 改动前先确认自己没在推翻这条既定决策；确需调整须同时改 `ofDetail`、`OrderController#detail` 的
   > `@Operation` 描述与本条豁免说明，三处一起改。
   > 同一口径的其它位置（`GET /api/order` 列表、`/api/user/elder`、`/api/admin/**`）**一律脱敏**，
   > 不要拿本条豁免去类推其它接口。
3. **一期不做**：在线支付（改为线上记账 + 线下结算）、地图导航（改为文字地址 + 签到坐标）、IM 聊天（改为站内信）。
   > **二期变更（2026-10-16，ADR-0011）**：IM 聊天已按 **M13 订单聊天** 形态放开——仅限**按陪诊订单**的家属↔陪诊员双向聊天（`/api/chat/**`），ELDER 只读、ADMIN 仲裁只读，**不做**自由加好友式 IM；在线支付与地图导航红线**不变**，涉及仍须主动拒绝或改写。聊天沿用全部合规约束（不做诊断、敏感词拦截、脱敏、免责声明），契约见 `docs/api/11-order-chat.md`。

   > **已记录的例外（V3.7 · 2026-10-21）——第二个技术栈。**
   > 项目自 V3.7 起含**第二个技术栈**：Python 助手服务（`ai-service/`，Python 3.12 + FastAPI + LangChain，独立进程、端口 8081，只监听 `127.0.0.1`）。
   > 该栈的依赖清单由**人工逐项批准**并冻结在 `ai-service/requirements.txt`；**越出该清单需重新报批**（连同理由一并说明），不得擅自添加。
   > 这条豁免**只对已批准的清单生效**，不适用于其它任何新增依赖；决策依据见 [ADR-0020](docs/adr/0020-assistant-boundary-and-recommendation-guardrail.md)。

### 0.2 AI 工具自我约束

- 任何代码改动落地前，**先说明思路与改动文件清单**，再开始改。
- **新增功能 / 编写代码必须在独立分支上完成**：动手写代码前先新建分支（功能用 `feature/<module>-<name>`、修 bug 用 `bugfix/<module>-<name>`、改文档用 `docs/<name>`），全程在该分支上提交，**禁止直接在 `main` / `develop` 上编写或提交代码**；任务完成、检查通过后再按 `docs/agents/git-workflow.md` 的分支模型合并回主分支并删除该分支。仅改动文档、配置且不涉及代码生成时可经用户确认后免开分支。
- **不要删除** `docs/plan/plan.md`、`README.md`、`docs/api/*`、`.gitignore`、`pom.xml`、`package.json`、根目录 `.workbuddy/`；这些是项目根骨。
- **不要执行破坏性命令**：`rm -rf`、强制 `git push --force` 到 `main` / `develop`、直接 drop database、`mvn clean install -DskipTests` 之外的危险 Maven 目标。对 `git commit`、`git push`、`git reset`、`git clean`、重启服务、删容器等动作必须**先确认**。
- **不要在仓库中留下明文密码 / 真实手机号 / 真实身份证号**。所有示例数据用合规占位串（如 `13800000000`、`110101199001010001`）。
- **不要"自创"技术栈**：不得引入 README / pom.xml / package.json 之外的依赖。需要新增依赖时，在回复中明确说明理由，由人工确认后再写。（V3.7 的 Python 助手服务依赖清单是**已记录的例外**，见 §0.1。）
- **提交前跑该跑的检查**。仓库已有 CI（`.github/workflows/ci.yml`，push / PR 到 `main` 时触发），覆盖后端 `mvn verify`、前端 `lint:check` + `vitest` + `build`、AI 助手服务 `pytest`、密钥体检、指标字典人读面↔机器面一致性。**改后端就跑 `mvn -B verify`，改 `frontend/` 就跑 `pnpm run lint:check` 与 `pnpm run test`** —— 与 CI 同命令，自己先红总比推送后红好。新增检查项时同步改 workflow。
- **改 `ai-service/` 就跑 `python -m pytest ai-service/tests -q`；改 FAQ 知识库（`ai-service/knowledge/faq.json`）就跑 `python tools/check_faq_anchors.py`。** 助手核心断言**不依赖 LLM 与网络**（没配模型时也要能跑绿）；CI 的 `ai-service` job 跑的是同一套测试（工作目录 `ai-service/`）。
- **本地能复跑 CI 的两个静态门禁**（纯 Python 标准库，不需要装任何依赖）：
  - `python tools/check_secrets.py` —— 密钥体检五条（**CI secrets job 调的就是这一份**，规则本体只有一处）
  - `python tools/check_metric_dict_parity.py` —— 指标字典人读面↔机器面一致性
  改这两处的规则**只改脚本**，CI 与本地共用同一份；**不要在 workflow 里再抄一遍** —— 抄两份必然漂移（2026-10-21 review 实测：抄出来的两份在 Windows 上白名单语义不同，本地绿、CI 也绿，但其中一份其实已经失效）。
- 不确定就问，不要瞎猜；宁可少写，不要乱写。

---

### 0.3 · 细则文件索引（AGENTS.md 装不下的细则在这里）

**为什么拆开**：运行时给本文件的上下文预算是 **32 KB**，而拆分前它有 **54 KB** ——
超出的部分**每次会话都被静默丢弃**，丢掉的恰好包括 §4.1 订单状态机、§10 Do/Don't 底线、
§11 验收清单这些最该被看见的内容。现在本文件只留**红线、导航、底线、验收清单**，
细则按需加载。

| 原章节 | 内容现位置 | 什么时候读 |
|---|---|---|
| §2 · 后端编码规范 | [`docs/agents/backend-conventions.md`](docs/agents/backend-conventions.md) | 改 `backend/` 任何 Java 代码前 |
| §3 · 前端编码规范 | [`docs/agents/frontend-conventions.md`](docs/agents/frontend-conventions.md) | 改 `frontend/` 前端代码前 |
| §4 · 业务规则 | [`docs/agents/business-rules.md`](docs/agents/business-rules.md) | 改订单状态机 / 打卡 / 老人账号 / 上传前 |
| §5 · 数据库 / Flyway / 数据初始化 | [`docs/agents/database.md`](docs/agents/database.md) | 改 `backend/sql/` 迁移脚本或数据初始化前 |
| §6 · 测试与质量 | [`docs/agents/testing.md`](docs/agents/testing.md) | 写或改测试前 |
| §7 · Git 与协作 | [`docs/agents/git-workflow.md`](docs/agents/git-workflow.md) | 开分支、提交、合并前 |
| §8 · 接口先行 | [`docs/agents/api-first.md`](docs/agents/api-first.md) | 新增或改动任何 HTTP 接口前 |
| §9 · 文档同步清单 | [`docs/agents/doc-sync.md`](docs/agents/doc-sync.md) | 改完代码收尾时（哪些文档要同步） |

**仓库其它文档里的 `AGENTS.md §N.N` 引用仍然有效** —— 查上表定位到文件后，看同名小节，
小节编号一个没改（例：`AGENTS.md §4.1` → `docs/agents/business-rules.md` 的 §4.1）。

---

## 1 · 仓库结构与"在哪里写"

```
Senior Companion Health Assessment/
├── AGENTS.md                        ← 本文件，AI 必读（协作规范）
├── README.md                        ← 项目基线（对外入口）
├── GLOSSARY.md                      ← 领域词汇表（唯一真源）
├── CHANGELOG.md                     ← 版本变更记录
├── 银龄伴诊项目计划书.docx           ← 课程原始计划书（不要改）
├── docs/
│   ├── api/                         ← 接口文档（先于代码）
│   ├── prd/                         ← 产品需求（PRD 逐版）
│   ├── spec/                        ← 工程规格 + 工单 + 指标字典
│   ├── adr/                         ← 架构决策记录
│   ├── db/                          ← 数据字典 / ER 图 / 索引（由脚本生成）
│   ├── design/                      ← 前端设计规范
│   ├── plan/                        ← 项目计划书 + 迭代计划
│   ├── agents/                      ← AI 协作契约（前端契约 / 领域文档规则）
│   ├── review/                      ← 评审记录
│   ├── security/                    ← 安全评估
│   └── reports/                     ← 测试报告 / 验收基线 / 压测
├── backend/
│   ├── pom.xml
│   ├── sql/                          ← Flyway 脚本唯一真源
│   │   ├── V1__init_schema.sql       20 张表 DDL
│   │   ├── V2__seed_data.sql         种子数据
│   │   ├── V3__seed_boundary.sql     边界场景补充
│   │   └── tools/                    生成器 + 校验 SQL（不参与构建，见其 README）
│   └── src/
│       ├── main/
│       │   ├── java/org/company/nianglin/
│       │   │   ├── NianglinApplication.java
│       │   │   ├── common/         Result / PageResult / PageQuery / BaseEntity / ResultCode
│       │   │   ├── config/          SecurityConfig / Knife4jConfig / MybatisPlusConfig / WebMvcConfig / MyMetaObjectHandler
│       │   │   ├── constant/        RoleConstants / OrderStatus
│       │   │   ├── controller/      按模块子包：auth / user / order / execution / medication / review / message / admin / statistics / common
│       │   │   ├── dto/             入参对象
│       │   │   ├── entity/          数据库实体（继承 BaseEntity，由 gen_entity.py 生成）
│       │   │   ├── exception/       BusinessException / GlobalExceptionHandler
│       │   │   ├── mapper/          MyBatis-Plus Mapper 接口（由 gen_entity.py 生成）
│       │   │   ├── security/        JWT 过滤器、注解、鉴权工具（M2）
│       │   │   ├── service/ (+impl/) 业务层
│       │   │   ├── task/            定时任务（M6）
│       │   │   ├── util/            工具类（MaskUtil / AesUtil 等）
│       │   │   ├── vo/              出参对象
│       │   │   └── websocket/       实时推送（M5 / M8）
│       │   └── resources/
│       │       ├── application.yml           主配置（环境无关）
│       │       ├── application-dev.yml       开发环境（含 MySQL / Redis 连接）
│       │       ├── application-prod.yml      生产环境
│       │       ├── logback-spring.xml
│       │       ├── db/migration/             构建产物目录（源在 backend/sql/，勿手工放脚本）
│       │       └── mapper/                   MyBatis XML（如有）
│       └── test/java/org/company/nianglin/   JUnit5 单测
├── frontend/
    ├── package.json
    ├── vite.config.js
    ├── playwright.config.js        正式 E2E 套件配置（本机 Chrome；video 关闭，不依赖 ffmpeg）
    ├── eslint.config.js
    ├── .prettierrc.json
    ├── .env.development / .env.production
    ├── index.html
    ├── e2e/                        Playwright E2E（fixtures / helpers / specs，见 docs/agents/FRONTEND_CONTRACT.md §13）
    └── src/
        ├── main.js / App.vue
        ├── api/                  每个模块一个文件，对应 docs/api/ 下的文档
        │   └── index.js          统一出口
        ├── router/
        │   ├── index.js          路由实例 + 守卫
        │   └── routes.js         路由表（含 meta：title / icon / roles / public / elderlyHidden / module）
        ├── store/
        │   ├── index.js          Pinia 汇总
        │   └── modules/
        │       ├── user.js       登录态
        │       └── app.js        全局 UI（含 elderlyMode）
        ├── utils/
        │   ├── request.js        Axios 封装
        │   └── auth.js           localStorage 读写
        ├── styles/
        │   ├── variables.scss    设计令牌（编译期）
        │   ├── index.scss        全局样式（运行期 CSS 自定义属性）
        │   └── elderly.scss      老人模式覆盖
        ├── layouts/
        │   └── BasicLayout.vue   主布局
        ├── views/
        │   ├── login/index.vue
        │   ├── dashboard/index.vue
        │   ├── elder / family / companion / admin / profile / error
        └── components/
            └── ModulePlaceholder.vue   骨架阶段占位组件
├── ai-service/                    ← V3.7 AI 助手服务（独立 Python 进程，8081；只监听 127.0.0.1）
│   ├── requirements.txt           ← 依赖清单（经人工逐项批准并冻结，改动需重新报批）
│   ├── app/                       ← FastAPI 应用：main / config / schemas / paths / intents / knowledge / guard / llm / prompts / actions / service
│   ├── knowledge/faq.json         ← FAQ 知识库（30 条，每条带 source + anchor 指向 docs/api 真实锚点）
│   ├── tests/                     ← pytest 单测（核心断言不依赖 LLM 与网络）
│   └── .env.example               ← 环境变量样例（只有占位符，.env 不入库）
└── tools/e2e/                      E2E 夹具 / 服务脚本 / 探针（9 个脚本，随仓库入库；**每个脚本的作用、是否写库、是否要口令见 `tools/e2e/README.md`**，见 FRONTEND_CONTRACT §12–§13）
```

**AI 写代码时**：先按上面这个目录找到归属位置，再决定用什么模板。**不要**在 `controller/` 下塞 Service、**不要**把 Entity 当 VO 返回、**不要**在前端 `views/` 下直接 `import request from '@/utils/request'` 之外的封装方式。

---

## 10 · Do / Don't 速查（AI 工具的最低底线）

### ✅ DO

- 先读本文件 + `README.md` + `docs/plan/plan.md` + 对应 `docs/api/<module>.md`，再动手。
- 接口改动先文档、后代码、再前端。
- 业务失败一律 `throw new BusinessException(ResultCode.X)`，不要在 Controller 吞异常。
- 脱敏**先**调 `MaskUtil`，**再**拼字符串。
- 用 `BaseEntity` + `@TableLogic` + `@Version`（按需） + `OptimisticLockerInnerInterceptor`（已开启）。
- 前端接口封装走 `@/api/<module>.js`，组件内只调函数，不写 `request(...)`。
- 样式令牌用 `--nl-*` 与 `$variable`，不要硬编码颜色 / 字号。
- 提交前跑 `pnpm lint` / `pnpm format` 与 `mvn -q clean package`（编译）。

### ❌ DON'T

- 不要新增 README / pom.xml / package.json 之外的依赖。
- 不要把 Entity 直接当 VO 返回。
- 不要硬编码订单状态字符串，必须走 `OrderStatus.canTransitTo(...)`。
- 不要把手机号 / 身份证号 / 密码写进日志或 localStorage。
- 不要在 SQL 字符串里拼接用户输入（用 MyBatis-Plus `LambdaQueryWrapper` / `@Select` 注解参数绑定）。
- 不要绕过 `GlobalExceptionHandler` 在 Controller 里返回 `Result.fail(...)`。
- 不要在 `main` / `develop` 直接 commit / push。
- 不要 `rm -rf`、不要 `git push --force` 到受保护分支、不要 `mvn clean` 之外的破坏性目标（除非用户明确授权）。
- 不要"先拼后脱敏"、不要依赖 Hutool `StrUtil.hide` 做隐私脱敏。
- 不要在前端 Element Plus 中混用 `this.$message`（Options API 写法）和 `ElMessage`（Composition API 写法），统一用 `import { ElMessage } from 'element-plus'` 后调用。
- 不要在 `.vue` / `.scss` 文件里依赖 Vite SCSS `additionalData`（项目刻意未配），需要变量时显式 `@use '@/styles/variables.scss' as *;`。
- 不要在 `views/` 下直接 `import request from '@/utils/request'`，必须走 `@/api/<module>.js`。

---

## 11 · 验收检查清单（AI 完成一轮代码改动后，自查这一份）

- [ ] 改动文件清单已列明，所有改动都在 `feature/<...>` 分支上完成。
- [ ] 接口相关改动已在 `docs/api/<module>.md` 体现；Knife4j 注解已加。
- [ ] 错误码改动已在 `ResultCode.java` 与 `docs/api/README.md` 同步。
- [ ] 没有硬编码状态字符串；新增状态流转都过了 `OrderStatusTest`。
- [ ] 脱敏字段都走 `MaskUtil`；日志不含身份证号 / 完整手机号 / 密码。
- [ ] 前端 `request.js` 的拦截器行为没有被绕过；业务代码没有再写 `if (res.code !== 200)`。
- [ ] 老人模式（`<html class="elderly-mode">`）下字号 / 触控区域 / 菜单隐藏行为符合预期。
- [ ] 后端 `mvn -q -DskipTests compile` 通过；前端 `pnpm lint` 0 警告。
- [ ] 没有把 `.env*` / `application-local.*` / 含真实密码的配置提交。
- [ ] 没有改 `docs/plan/plan.md` / `README.md` / `.gitignore` / `pom.xml` / `package.json` / `.workbuddy/` 之外的非业务文件（除非用户明确要求）。
- [ ] 没有出现"建议服用 / 推荐剂量 / 诊断为 / 可能是 XX 病"等违反合规的文案。
- [ ] 引用仓库规模数字（提交 / 文件 / 测试 / 端点数等）前先跑 `python tools/repo_metrics.py` 现取，注明基线 ref；surefire / `playwright --list` 是实跑口径，`@Test` / `test(` 是静态口径，不要混用或手抄上一份文档。

---

## 12 · 反馈与更新

- 本文件由项目维护者根据代码实际演进迭代。任何对规范的修改必须先在群里讨论，再直接改本文件并提交。
- AI 在生成代码时若发现本文件与现实代码冲突（如新模块已突破某条硬约束），**以现实代码为准并在回复中提示冲突**，但不要擅自"修复"现实代码。

> 📌 **核心一句话**：本项目是给老年人用的合规产品，技术栈与目录结构已固定，AI 工具的角色是"严格按本规范补全模块、补全测试、补全文档"，不是"自由发挥"。

---

## Agent skills

### Issue tracker

Issues live as GitHub issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five canonical roles: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: one `GLOSSARY.md` (glossary — single source of truth) + `docs/adr/` at the repo root. There is **no** second glossary and no alias file — the writing side (`/domain-modeling`) and the reading side (`/tdd`, `/diagnosing-bugs`, `/improve-codebase-architecture`, `/wait-what`, `/pr`) both hardcode `GLOSSARY.md`. Add terms to `GLOSSARY.md` only. See `docs/agents/domain.md`.
