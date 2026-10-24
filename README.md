# 银龄伴诊 — 老年人就医陪诊与用药协同管理平台

> 让不会用手机的老人，也能有人陪着把病看好。

软件工程课程设计项目 · 4 人团队 · 17 周 6 迭代

---

## 目录结构

```
Senior Companion Health Assessment/
├── README.md                        # 项目基线（本文件 · 对外入口）
├── AGENTS.md                        # AI 编码工具协作规范（机读版项目宪法）
├── GLOSSARY.md                      # 领域词汇表（唯一真源）
├── CHANGELOG.md                     # 版本变更记录
├── 银龄伴诊项目计划书.docx           # 课程原始计划书
├── docs/
│   ├── api/                         # 接口契约（全局约定 + 13 个模块）
│   ├── prd/                         # 产品需求（PRD 逐版 + 功能拓展规划）
│   ├── spec/                        # 工程规格 + 工单 + 指标字典
│   ├── adr/                         # 架构决策记录（0020 止）
│   ├── db/                          # ER 图 / 数据字典 / 索引 / 种子说明（脚本生成）
│   ├── design/                      # 前端设计规范（视觉与交互唯一真源）
│   ├── plan/                        # 项目计划书 + 迭代计划 + 存档
│   ├── agents/                      # AI 协作契约（前后端契约 / 领域文档规则）
│   ├── review/                      # 评审记录
│   ├── security/                    # 安全评估报告
│   └── reports/                     # 测试报告 / 验收基线 / 压测记录
├── backend/                         # Spring Boot 3.x 后端工程
│   ├── pom.xml
│   └── src/main/java/org/company/nianglin/
│       ├── NianglinApplication.java
│       ├── common/             # 统一响应、分页、基础实体
│       ├── exception/          # 业务异常 + 全局异常处理
│       ├── config/             # Knife4j / MyBatis-Plus / CORS / Security
│       ├── constant/           # 角色、订单状态等常量与枚举
│       ├── controller/         # 接口层（按模块分子包）
│       ├── service/            # 业务层
│       ├── mapper/             # 持久层
│       ├── entity/             # 数据库实体
│       ├── dto/ vo/            # 入参 / 出参对象
│       ├── security/           # JWT 签发解析、认证过滤器、鉴权注解与拦截器（M2 已交付）
│       ├── task/               # 定时任务（M6）
│       ├── websocket/          # 实时推送（M5 / M8）
│       └── util/               # 工具类
├── frontend/                        # Vue 3 + Vite 前端工程
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── main.js  App.vue
│       ├── api/                # 按模块拆分的接口封装
│       ├── router/             # 路由 + 多角色守卫（AUTH_ENABLED 已置 true）
│       ├── store/              # Pinia：用户登录态、适老化模式
│       ├── utils/              # Axios 封装、token 工具
│       ├── styles/             # 全局样式 + 适老化变量
│       ├── layouts/            # 布局组件
│       └── views/              # 页面（login / elder / family / companion / admin）
├── ai-service/                     # V3.7 AI 助手服务（独立 Python 进程，8081）
│   ├── app/                    # FastAPI 应用（护栏 / 意图 / 检索 / 叙述）
│   ├── knowledge/faq.json      # FAQ 知识库（每条带文档锚点）
│   ├── tests/                  # pytest 单测
│   └── requirements.txt        # 依赖清单（经人工逐项批准并冻结）
```

---

## 技术栈

| 层次 | 技术 |
|---|---|
| 前端框架 | Vue 3 + Vite |
| UI 组件 | Element Plus（骨架阶段全量引入，M11 可改按需引入）+ 适老化主题 |
| 前端路由 / 状态 | Vue Router 4（多角色守卫）+ Pinia |
| HTTP | Axios（统一拦截、Token 携带与刷新） |
| 可视化 | ECharts |
| 后端框架 | Spring Boot 3.3.x（JDK 21） |
| 持久层 | MyBatis-Plus 3.5+（分页插件、乐观锁、逻辑删除） |
| 数据库 | MySQL 8.0（utf8mb4） |
| 缓存 | Redis（验证码 / 字典缓存 / 限流 / 分布式锁） |
| 安全 | Spring Security + JWT |
| 接口文档 | Knife4j（OpenAPI 3）→ `http://localhost:8080/doc.html` |
| 报表导出 | EasyExcel |
| 构建 | Maven / Vite |
| 端到端测试 | Playwright（`@playwright/test`，套件在 `frontend/e2e/`，见 `docs/agents/FRONTEND_CONTRACT.md` §13） |
| AI 助手服务 | Python 3.12 + FastAPI + LangChain（独立进程，8081） |

---

## 快速开始

### 前置条件

| 组件 | 版本要求 | 本机状态 |
|---|---|---|
| JDK | 21（Spring Boot 3.x 要求 17+） | 21.0.7 ✅ |
| Maven | 3.6+ | 3.9.15 ✅ |
| Node.js | 22.10+ | 22.22.2 ✅ |
| MySQL | 8.0 | 8.0.42 ✅（服务 MySQL80） |
| Redis | 5+ | 8.8.0 ✅（服务 Redis） |

> 首次使用请先确认 `C:\Users\wang\.m2\settings.xml` 已配置阿里云镜像，否则依赖下载极慢。
> Node 下限以 `frontend/package.json` 的 `engines.node` 为准（当前 22.10+）：低于该版本 pnpm 直接拒绝安装；
> 18 上 `jsdom` / `undici` 依赖的 `markAsUncloneable` 还不存在，vitest 会在启动期就崩掉。

### 数据库准备

```bash
# 建库（utf8mb4 是硬约束，见 AGENTS.md §2.5）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS nianglin DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
```

表结构与种子数据由 Flyway 自动执行（`backend/sql/` 是唯一真源，启动时迁移到
`classpath:db/migration`），无需手动导入 SQL。

### 配置数据库口令（必做，否则启动即 `Access denied`）

`application-dev.yml` 里写的是 `password: ${MYSQL_PASSWORD:CHANGE_ME}` —— `CHANGE_ME`
只是占位串，**不是合法口令**。不配置直接启动会挂在 datasource 初始化。三选一：

**方式 1 · 环境变量（PowerShell，仅当前窗口有效）**

```powershell
$env:MYSQL_USERNAME = 'root'
$env:MYSQL_PASSWORD = '你的口令'
```

**方式 2 · 本地私有配置文件（跨窗口有效，推荐日常开发）**

```powershell
cd backend/src/main/resources
Copy-Item application-local.yml.example application-local.yml
# 然后编辑 application-local.yml 填入真实口令
```

`application-local.yml` 已被 `.gitignore` 排除（`.gitignore:14` 的 `**/application-local.yml`），
不会误提交；`.example` 是模板，可以放心入库。

**方式 3 · IDEA**

Run/Debug Configurations → Environment variables：
`MYSQL_USERNAME=root;MYSQL_PASSWORD=你的口令`

> ⚠️ 真实口令只放环境变量或 `application-local.yml`。
> **不要**写进 `application-dev.yml` / `application-prod.yml` / README —— 那些是仓库内文件，会被提交。

### 后端

```bash
cd backend
mvn clean compile                                   # 编译
# ⚠️ 必须先激活 profile：密钥类配置在共享配置里无默认值，不激活会「缺失即启动失败」
#   PowerShell：  $env:SPRING_PROFILES_ACTIVE = 'dev'
#   IDEA：        Run 配置 → Environment variables → SPRING_PROFILES_ACTIVE=dev
mvn -Dspring-boot.run.profiles=dev spring-boot:run  # 启动，默认 8080
```

- 健康检查：`GET http://localhost:8080/api/health`
- 接口文档：`http://localhost:8080/doc.html`
- 生产部署：必须显式 `SPRING_PROFILES_ACTIVE=prod` 并注入 `JWT_SECRET` / `ID_CARD_AES_KEY` / `DEFAULT_RESET_PASSWORD` / `REDIS_PASSWORD`，缺失即启动失败（见 `docs/security/`）

### 前端

```bash
cd frontend
pnpm install                      # 包管理器：pnpm（lockfile 为 pnpm-lock.yaml）
pnpm dev                          # 启动，默认 5141，/api 自动代理到 8080
pnpm build                        # 生产构建，产物在 dist/
pnpm lint                         # ESLint 检查并自动修复
pnpm format                       # Prettier 格式化
pnpm exec playwright test         # E2E（前置：MySQL/Redis + 后端 8080 + tools/e2e/ 夹具，见契约 §13.1）
```

> 若环境只有 npm，可临时用 `npm install` / `npm run dev`，但 lockfile 以 `pnpm-lock.yaml` 为准。

> 启动前端前请先启动后端，否则首页的「前后端链路连通性」会提示未连通（不影响页面浏览）。

### AI 助手服务（V3.7 · 可选启动）

```bash
cd ai-service
pip install -r requirements.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8081
```

- **不配置 LLM 也能跑**：不设 `LLM_BASE_URL` / `LLM_MODEL` 时助手进入**基础模式**，回答直接给 FAQ 条目的**标准答案原文**（`degraded=true`），功能完整可用。
- 该服务**只监听 `127.0.0.1`**，不直接对浏览器开放；浏览器只与 `/api` 说话。
- 后端通过 `AI_SERVICE_URL` 找它（默认 `http://127.0.0.1:8081`）。**不启动它时后端会兜底**，本地 FAQ 也读不到才返回 `10002`。

### 本机演示数据与跑测顺序（汇报前必读）

仓库带一个**演示造数脚本** `presentation/scripts/seed-hall-demo.py`（`presentation/` 目录已被
`.gitignore` 排除，仅存在于本机）：它把 6 条 PENDING 订单的 `visit_time` 移到未来，让陪诊大厅、
履约风险条有内容可演示。**它只动 `visit_time` 一列**，可原样回滚：

```powershell
cd presentation
$env:MYSQL_PASSWORD = '<本机 root 口令>'
python scripts/seed-hall-demo.py            # 应用演示态
python scripts/seed-hall-demo.py --rollback # 回滚到种子基线
```

⚠️ **跑 `mvn -B verify` 前必须先 `--rollback`**：两条真实库测试
（`OrderAccessMatrixTest.timeShouldUseDocumentedFormat`、
`OperationsGapRealDbTest.seedDataShouldActuallyContainPendingOrders`）断言的是**种子基线**的
`visit_time`，演示态会把它们移出断言窗口 → BUILD FAILURE（2 红）。这不是测试不稳，恰恰是测试
在锁真数据；一句话解释：「演示态与测试态是同一个库的两种合法状态，一条命令来回切」。

**演示前 10 分钟自检顺序**：

1. `python tools/e2e/l0_identity_gate.py` → 必须 **10/10**（确认 5 个进程在线且 8080/8081/5141 上是本项目进程）。
2. 重新应用 `seed-hall-demo.py`（若此前跑过测试回滚了造数）。
3. 浏览器预登录 4 个角色（`admin` / `fam001` / `comp009` / `elder001`），确认 `/api/health` 返回 `UP`。

**日志轮转与清理指引**：`logback-spring.xml` 已配置滚动（按天 + 50MB 分卷，全量日志保留 30 天 /
错误日志 60 天 / 总量上限 2GB），不需要手工轮转。`backend/logs/` 偶发膨胀的主因是 **surefire
fork 与应用共写相对路径 `./logs`**（测试栈帧混进 `nianglin-error.log`，属已知现象、非产品缺陷）。
演示前若要展示日志目录，可手动清空 `backend/logs/`（**属删除操作，请自行确认后执行**；
另外跑测会在 `sys_login_log` / `sys_file` 两张只增审计表留痕，如需绝对干净可用
`python tools/e2e/restore_clean_baseline.py` 按种子水位复位，同样是删除操作、执行前先看它的输出计划）。

---

## 端口规划

| 用途 | 端口 |
|---|---|
| MySQL | 3306 |
| Redis | 6379 |
| 后端 Spring Boot | 8080 |
| AI 助手服务 | 8081 |
| 前端 Vite Dev | 5141 |

---

## 环境变量

V3.7 智能助手涉及的环境变量（**全部可留空**：留空即基础模式，不是错误状态）：

| 变量 | 归属 | 占位值示例 | 说明 |
|---|---|---|---|
| `AI_SERVICE_URL` | Java 后端 | `http://127.0.0.1:8081` | Python 助手服务地址；后端连不上时会兜底，最终返回 `10002` |
| `LLM_BASE_URL` | ai-service | `https://<your-endpoint>/v1` | OpenAI 兼容端点；**留空 = 基础模式**（回答直接给标准答案原文） |
| `LLM_API_KEY` | ai-service | `CHANGE_ME` | 模型服务 key；本地 Ollama 填任意占位串即可。**不要写真实 key** |
| `LLM_MODEL` | ai-service | `CHANGE_ME` | 模型名；**刻意不给代码默认值**，留空即走基础模式 |
| `NIANGLIN_COMPLIANCE_WORDS_PATH` | ai-service | `../backend/src/main/resources/compliance/medical-advice-words.txt` | 共享**医疗建议禁用词**路径（Java 与 Python 读同一份）；**读不到即启动失败** |
| `NIANGLIN_SENSITIVE_WORDS_PATH` | ai-service | `../backend/src/main/resources/compliance/sensitive-words.txt` | 共享**辱骂 / 违规引流词**路径（同上） |
| `NIANGLIN_REFUSAL_TEMPLATE_PATH` | ai-service | `../backend/src/main/resources/compliance/refusal-template.txt` | 共享**固定拒答模板**路径（同上） |

> ⚠️ 真实 key / 口令只放环境变量或本地未入库文件，**不要**写进本文件或任何入库文件。
>
> 📌 路径类变量写在 `ai-service/.env` 里**同样有效**（经 pydantic-settings 读取，
> `.env` 与环境变量都会被读）—— 但写 `.env` 只对本机有效，生产仍应走环境变量。

---

## 接口约定速查

- 统一前缀：`/api`
- 鉴权：请求头 `Authorization: Bearer <token>`
- 统一响应结构：`{ "code": 200, "message": "success", "data": ... }`
- 分页响应：`{ "code": 200, "data": { "total": 0, "page": 1, "size": 10, "records": [] } }`
- 角色：`ELDER` / `FAMILY` / `COMPANION` / `ADMIN`
- 订单状态机：`待接单 → 已接单 → 服务中 → 已完成 → 已评价`（禁止跳级、禁止回退）

完整约定与全部接口见 [`docs/api/README.md`](docs/api/README.md)。

---

## 合规约束（不可逾越）

1. 不做诊断、不开药方；药品信息仅为通用资料，页面必须展示免责声明。
2. 密码 BCrypt 加密；手机号脱敏展示；接口最小化返回；日志禁止打印身份证号。
3. 一期不做在线支付、地图导航、IM 聊天（分别用线上记账 + 线下结算、文字地址 + 签到坐标、站内信替代）。
   **二期变更（2026-10-16）**：IM 聊天已按 ADR-0011 以「按订单的家属↔陪诊员聊天」放开（M13，老人只读、管理员仲裁只读）；在线支付与地图导航红线不变。

---

## 开发规范

- **分支模型**：`main`（保护）← `develop` ← `feature/xxx`，每周五合并到 `develop`。
- **提交信息**：`feat|fix|docs|refactor|test|chore(模块): 描述`。
- **代码规范**：前端 ESLint + Prettier；后端 Alibaba Java 规范插件，不通过不允许提交。
- **接口先行**：后端先出接口文档与 Mock 数据，前端用 Mock 开发，不等后端写完。
- ~~**站会**：每晚 22:00 群内同步「今天做了什么 / 遇到什么问题 / 明天计划做什么」。~~ — ⏸️ 现阶段按个人节奏推进，不需要每日站会节奏。

---

## 文档索引

> 三个入口：**本文件**（对外基线）· [`AGENTS.md`](AGENTS.md)（AI 协作规范）· [`GLOSSARY.md`](GLOSSARY.md)（领域词汇表）。
> 其余文档按用途归入 `docs/` 下的子目录。

| 目录 | 视角 | 内容 |
|---|---|---|
| [`docs/prd/`](docs/prd/PRD.md) | 产品 | PRD 逐版（V3.1 – V3.7 + E4）+ 功能拓展规划。顶层入口 [`PRD.md`](docs/prd/PRD.md) |
| [`docs/plan/`](docs/plan/plan.md) | 工程 | [`plan.md`](docs/plan/plan.md) 模块拆分与验收标准 · [`iteration-plan.md`](docs/plan/iteration-plan.md) V3.x 迭代规划 · 目录适配 / 收敛迭代存档 |
| [`docs/api/`](docs/api/README.md) | 接口 | 全局约定 + 错误码 + 13 个模块接口明细（`13` = V3.7 家属智能助手） |
| [`docs/spec/`](docs/spec/V3.x-roadmap-spec.md) | 契约 | 各版工程规格 + 工单 + [`metric-dictionary.md`](docs/spec/metric-dictionary.md) 指标字典（人读面） |
| [`docs/adr/`](docs/adr/) | 架构 | 架构决策记录（0020 止），每条含背景 / 决策 / 后果 |
| [`docs/design/`](docs/design/design.md) | 视觉 | 前端设计规范（设计令牌 / 组件 / 交互，视觉唯一真源） |
| [`docs/db/`](docs/db/README.md) | 数据 | ER 图 / 数据字典 / 索引与 EXPLAIN / 种子说明（**由 `backend/sql/tools/gen_db_docs.py` 生成**） |
| [`docs/agents/`](docs/agents/FRONTEND_CONTRACT.md) | 协作 | 前后端联调契约 + AI 领域文档消费规则 + 遗留 Bug 清单 |
| [`docs/reports/`](docs/reports/) | 验证 | 联调全功能测试报告 · 各迭代测试报告 · 验收基线 / 收尾复核 · 压测记录 |
| [`docs/review/`](docs/review/) | 评审 | 逐版本评审记录 |
| [`docs/security/`](docs/security/) | 安全 | 安全评估报告（脱敏 / 越权 / 上传 / 配置） |

> PRD 与 `docs/plan/plan.md` 的分层关系：PRD 是产品视角的顶层入口，`plan.md` 是工程实施细节。
> 两文档通过散跳链互引，详见 [ADR-0005](docs/adr/0005-prd-planmd-layers.md)。
