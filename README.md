# 银龄伴诊 — 老年人就医陪诊与用药协同管理平台

> 让不会用手机的老人，也能有人陪着把病看好。

软件工程课程设计项目 · 4 人团队 · 17 周 6 迭代

---

## 目录结构

```
Senior Companion Health Assessment/
├── plan.md                     # 项目开发计划书（14 个模块拆分 + 环境需求 + 验收标准）
├── 银龄伴诊项目计划书.docx       # 课程原始计划书
├── docs/
│   └── api/                    # 接口设计文档（全局约定 + 9 个模块）
├── backend/                    # Spring Boot 3.x 后端工程
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
└── frontend/                   # Vue 3 + Vite 前端工程
    ├── package.json
    ├── vite.config.js
    └── src/
        ├── main.js  App.vue
        ├── api/                # 按模块拆分的接口封装
        ├── router/             # 路由 + 多角色守卫（AUTH_ENABLED 已置 true）
        ├── store/              # Pinia：用户登录态、适老化模式
        ├── utils/              # Axios 封装、token 工具
        ├── styles/             # 全局样式 + 适老化变量
        ├── layouts/            # 布局组件
        └── views/              # 页面（login / elder / family / companion / admin）
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

---

## 快速开始

### 前置条件

| 组件 | 版本要求 | 本机状态 |
|---|---|---|
| JDK | 21（Spring Boot 3.x 要求 17+） | 21.0.7 ✅ |
| Maven | 3.6+ | 3.9.15 ✅ |
| Node.js | 18+ | 22.22.2 ✅ |
| MySQL | 8.0 | 8.0.42 ✅（服务 MySQL80） |
| Redis | 5+ | 8.8.0 ✅（服务 Redis） |

> 首次使用请先确认 `C:\Users\wang\.m2\settings.xml` 已配置阿里云镜像，否则依赖下载极慢。

### 后端

```bash
cd backend
mvn clean compile                 # 编译
mvn spring-boot:run               # 启动，默认 8080
```

- 健康检查：`GET http://localhost:8080/api/health`
- 接口文档：`http://localhost:8080/doc.html`

### 前端

```bash
cd frontend
pnpm install                      # 包管理器：pnpm（lockfile 为 pnpm-lock.yaml）
pnpm dev                          # 启动，默认 5173，/api 自动代理到 8080
pnpm build                        # 生产构建，产物在 dist/
pnpm lint                         # ESLint 检查并自动修复
pnpm format                       # Prettier 格式化
pnpm exec playwright test         # E2E（前置：MySQL/Redis + 后端 8080 + tools/e2e/ 夹具，见契约 §13.1）
```

> 若环境只有 npm，可临时用 `npm install` / `npm run dev`，但 lockfile 以 `pnpm-lock.yaml` 为准。

> 启动前端前请先启动后端，否则首页的「前后端链路连通性」会提示未连通（不影响页面浏览）。

---

## 端口规划

| 用途 | 端口 |
|---|---|
| MySQL | 3306 |
| Redis | 6379 |
| 后端 Spring Boot | 8080 |
| 前端 Vite Dev | 5173 |

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

---

## 开发规范

- **分支模型**：`main`（保护）← `develop` ← `feature/xxx`，每周五合并到 `develop`。
- **提交信息**：`feat|fix|docs|refactor|test|chore(模块): 描述`。
- **代码规范**：前端 ESLint + Prettier；后端 Alibaba Java 规范插件，不通过不允许提交。
- **接口先行**：后端先出接口文档与 Mock 数据，前端用 Mock 开发，不等后端写完。
- ~~**站会**：每晚 22:00 群内同步「今天做了什么 / 遇到什么问题 / 明天计划做什么」。~~ — ⏸️ 现阶段按个人节奏推进，不需要每日站会节奏。

---

## 文档索引

| 文档 | 视角 | 说明 |
|---|---|---|
| [`docs/prd/PRD.md`](docs/prd/PRD.md) | **产品视角（顶层）** | 愿景、用户画像、用户旅程、NFR、业务规则、成功度量、风险与依赖。版本锁对齐 W7/W12/W17 三个竞讲节点（详见 [ADR-0006](docs/adr/0006-prd-version-locking.md)） |
| [`plan.md`](plan.md) | 工程视角 | 模块拆分、验收标准、范围调整（§〇） |
| [`docs/api/README.md`](docs/api/README.md) | 接口契约 | 接口全局约定与错误码 |
| `docs/api/01` ~ `09` | 接口契约 | 各业务模块接口明细 |
| `docs/agents/designs/M4` ~ `M10` | 模块设计评审 | 7 个核心模块（M4 订单 / M5 执行 / M6 用药 / M7 评价 / M8 站内信 / M9 管理 / M10 统计） |
| `docs/adr/0001` ~ `0003` / `0005` ~ `0006` | 架构决策记录 | 5 个 ADR（WebSocket 鉴权 / Redis 锁 / 统计缓存 / **PRD 与 plan.md 分层** / **PRD 版本锁**） |
| `docs/db/` | 数据视角 | ER 图 / 数据字典 / EXPLAIN / 种子说明 |

> PRD 与 plan.md 的分层关系：PRD 是产品视角的顶层入口，plan.md 是工程实施细节。两文档通过散跳链互引，详见 [ADR-0005](docs/adr/0005-prd-planmd-layers.md)。
