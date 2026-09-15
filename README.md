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
│       ├── security/           # JWT、鉴权注解与拦截器（M2 实现）
│       ├── task/               # 定时任务（M6）
│       ├── websocket/          # 实时推送（M5 / M8）
│       └── util/               # 工具类
└── frontend/                   # Vue 3 + Vite 前端工程
    ├── package.json
    ├── vite.config.js
    └── src/
        ├── main.js  App.vue
        ├── api/                # 按模块拆分的接口封装
        ├── router/             # 路由 + 多角色守卫
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
npm install                       # registry 已指向 npmmirror；也可用 pnpm
npm run dev                       # 启动，默认 5173，/api 自动代理到 8080
npm run build                     # 生产构建，产物在 dist/
npm run lint                      # ESLint 检查并自动修复
npm run format                    # Prettier 格式化
```

> 启动前端前请先启动后端，否则首页的「前后端链路连通性」会提示未连通（不影响页面浏览）。

---

## 端口规划

| 用途 | 端口 |
|---|---|
| MySQL | 3306 |
| Redis | 6379 |
| 后端 Spring Boot | 8080 |
| 前端 Vite Dev | 5173 |
| 前端生产（Nginx） | 80 |

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
- **站会**：每晚 22:00 群内同步「今天做了什么 / 遇到什么问题 / 明天计划做什么」。

---

## 文档索引

| 文档 | 说明 |
|---|---|
| [`plan.md`](plan.md) | 模块拆分、验收标准、环境与组件清单 |
| [`docs/api/README.md`](docs/api/README.md) | 接口全局约定与错误码 |
| `docs/api/01` ~ `09` | 各业务模块接口明细 |
