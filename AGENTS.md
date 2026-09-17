# AGENT.md — 银龄伴诊 · AI 编码工具协作规范

> **本文件是 AI 编码助手（Claude Code、Cursor、Copilot、通义灵码等）在本仓库内生成、修改、补全代码时必须遵守的规范总览。**
>
> 它从 `plan.md`、`README.md`、`docs/api/README.md` 以及现有骨架代码中提炼而来，等同于"机读版项目宪法"。
> AI 在回答或写入本仓库前，必须先完整读取并理解本文件，再按其中的规则行事。
>
> 配套阅读：`README.md`（项目基线）、`plan.md`（模块拆分与验收标准）、`docs/api/README.md`（接口全局约定）。

---

## 0 · 适用范围与基本立场

| 项 | 约定 |
|---|---|
| 适用对象 | 任何在本仓库目录（`F:/test/Senior Companion Health Assessment/` 及子目录）下生成、修改、补全、审查代码的 AI 工具 |
| 项目性质 | 软件工程课程设计 · 4 人团队 · 17 周 6 迭代 · 老年人就医陪诊与用药协同管理 |
| 技术栈（不可改） | 前端 Vue 3.5 + Vite 6 + Element Plus 2.9 + Pinia + Vue Router 4 + Axios + Sass；后端 Spring Boot 3.3.5 + JDK 21 + MyBatis-Plus 3.5.7 + Spring Security + JJWT 0.12.6 + MySQL 8.0 + Redis + Flyway + Knife4j + EasyExcel + Hutool + Lombok |
| 包名 / 命名空间 | 后端统一 `org.company.nianglin`；前端模块入口 `@/...`（Vite alias 指向 `frontend/src`） |
| 前端包管理器 | **pnpm**（lockfile 为 `frontend/pnpm-lock.yaml`；禁止把 `package-lock.json` 重新引入仓库） |
| 接口前缀 | 所有后端接口统一 `/api` 前缀 |

### 0.1 三条不可逾越的合规红线（每条都是 plan.md 计划书"合规与隐私说明"里的硬约束）

1. **不做诊断、不开药方**。药品字典只返回通用信息（名称、规格、通用说明），接口与页面必须出现免责声明文案。**禁止**出现"建议服用""推荐剂量""诊断为""可能是 XX 病"等表述。
2. **隐私最小化**。密码 BCrypt 存储（值以 `$2a$` 开头）；接口返回时手机号 `138****8888`、身份证号 `110101********4567`、姓名按场景脱敏；本地存储（localStorage）**禁止**写入密码、身份证号、完整手机号。
3. **一期不做**：在线支付（改为线上记账 + 线下结算）、地图导航（改为文字地址 + 签到坐标）、IM 聊天（改为站内信）。任何 AI 输出涉及这三块都要主动拒绝或改写为替代方案。

### 0.2 AI 工具自我约束

- 任何代码改动落地前，**先说明思路与改动文件清单**，再开始改。
- **不要删除** `plan.md`、`README.md`、`docs/api/*`、`.gitignore`、`pom.xml`、`package.json`、根目录 `.workbuddy/`；这些是项目根骨。
- **不要执行破坏性命令**：`rm -rf`、强制 `git push --force` 到 `main` / `develop`、直接 drop database、`mvn clean install -DskipTests` 之外的危险 Maven 目标。对 `git commit`、`git push`、`git reset`、`git clean`、重启服务、删容器等动作必须**先确认**。
- **不要在仓库中留下明文密码 / 真实手机号 / 真实身份证号**。所有示例数据用合规占位串（如 `13800000000`、`110101199001010001`）。
- **不要"自创"技术栈**：不得引入 README / pom.xml / package.json 之外的依赖。需要新增依赖时，在回复中明确说明理由，由人工确认后再写。
- 不确定就问，不要瞎猜；宁可少写，不要乱写。

---

## 1 · 仓库结构与"在哪里写"

```
Senior Companion Health Assessment/
├── AGENT.md                         ← 本文件，AI 必读
├── README.md                        ← 项目基线
├── plan.md                          ← 模块拆分与验收标准
├── 银龄伴诊项目计划书.docx           ← 课程原始计划书（不要改）
├── docs/api/                        ← 接口文档（先于代码）
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
└── frontend/
    ├── package.json
    ├── vite.config.js
    ├── eslint.config.js
    ├── .prettierrc.json
    ├── .env.development / .env.production
    ├── index.html
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
```

**AI 写代码时**：先按上面这个目录找到归属位置，再决定用什么模板。**不要**在 `controller/` 下塞 Service、**不要**把 Entity 当 VO 返回、**不要**在前端 `views/` 下直接 `import request from '@/utils/request'` 之外的封装方式。

---

## 2 · 后端编码规范（Alibaba Java 规范为基线）

### 2.1 必须遵守的硬约束

- **JDK 21**，`pom.xml` 已锁定 `<java.version>21</java.version>`。
- **包名**统一 `org.company.nianglin.*`；新模块继续沿用 `constant / config / common / exception / util` 这些已有包，不要新建并列顶层包。
- **Lombok**已就绪：Entity / DTO / VO 用 `@Data`；链式 setter 用 `@Accessors(chain = true)`；Controller / Service 优先 `@RequiredArgsConstructor` + `final` 注入字段；日志统一 `@Slf4j`。**不要**手写 getter / setter / `private static final Logger log = ...`。
- **不要**在 Entity 上做业务校验，业务校验在 DTO 上用 Jakarta Validation（`@NotBlank`、`@NotNull`、`@Size`、`@Pattern`、`@Min`、`@Max`）并在 Controller 上加 `@Valid` / `@Validated`。
- **Entity 不得直接返回**。所有接口返回必须转 VO；VO 中若包含手机号 / 身份证号 / 地址，必须在转换时调用 `MaskUtil`。
- **业务失败统一抛 `BusinessException(ResultCode.X)`**，由 `GlobalExceptionHandler` 统一转为 `Result.fail(code, message)`。**不要**在 Controller 里 `try/catch` 后 `return Result.fail(...)`，那是异常处理的反模式。
- **日志**：调用 `MaskUtil` 之后再拼日志串，**禁止**直接 `log.info("phone={}", user.getPhone())`。

### 2.2 分层与命名

| 层 | 类后缀 | 注解 | 命名示例 |
|---|---|---|---|
| Controller | `XxxController` | `@RestController` + `@RequestMapping("/api/xxx")` | `OrderController`、`AuthController` |
| Service 接口 | `XxxService` | — | `OrderService` |
| Service 实现 | `XxxServiceImpl` | `@Service` | `OrderServiceImpl` |
| Mapper | `XxxMapper` | 继承 `BaseMapper<Xxx>` | `OrderMapper` |
| Entity | `Xxx` | 继承 `BaseEntity` | `CompanionOrder`、`ElderProfile` |
| DTO（入参） | `XxxDTO` 或 `XxxQuery` / `XxxCreateReq` / `XxxUpdateReq` | Bean Validation | `OrderCreateDTO`、`OrderQuery` |
| VO（出参） | `XxxVO` | — | `OrderVO`、`UserInfoVO` |
| 枚举 / 常量 | `Xxx` / `XxxConstants` | enum | `OrderStatus`、`RoleConstants.Role` |
| 工具 | `XxxUtil` | 静态方法 | `MaskUtil` |
| 异常 | `BusinessException` / `XxxException` | extends RuntimeException | — |

### 2.3 Controller 模板（AI 必须按此形态生成）

```java
@Slf4j
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Tag(name = "03-陪诊订单", description = "陪诊订单与状态机")
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "下单", description = "家属为指定老人创建陪诊订单")
    @PreAuthorize("hasRole('FAMILY')")
    @PostMapping
    public Result<OrderVO> createOrder(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success(orderService.createOrder(dto));
    }
}
```

要点：

- `@PreAuthorize("hasRole('FAMILY')")` 用 `RoleConstants` 里的枚举名（`FAMILY` / `ELDER` / `COMPANION` / `ADMIN`）。
- 写操作必须由家属 / 陪诊员 / 管理员承担；**老人账号（ELDER）默认只读**，所有写接口必须做服务端角色拦截，**不能只靠前端隐藏按钮**（计划书硬约束）。
- 路径 `/api/<模块>` 全小写，多单词用连字符；资源名用复数。
- 返回类型固定 `Result<T>` 或 `Result<PageResult<T>>`，**不要**直接返回裸对象。

### 2.4 Service 模板

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    // 依赖其他 Service / 工具时继续 final 注入

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(OrderCreateDTO dto) {
        // 1. 业务校验（含资源归属、状态机合法性）
        // 2. DTO -> Entity
        // 3. 持久化（MyBatis-Plus）
        // 4. Entity -> VO（脱敏、附 label）
        // 5. 返回 VO
    }
}
```

要点：

- **`@Transactional(rollbackFor = Exception.class)`**（不要只写 `rollbackFor = RuntimeException.class`）。
- 状态变更、接单 / 取消等"可能被并发触发"的接口用 MyBatis-Plus **`@Version`** 乐观锁 + `OptimisticLockerInnerInterceptor`（`MybatisPlusConfig` 已开启），**不要**自己写 `UPDATE ... WHERE id=? AND status=?`。
- 多表写入必须校验：状态机合法性（如 `OrderStatus.PENDING.canTransitTo(OrderStatus.ACCEPTED)`）、资源归属（订单属于哪个家属 / 陪诊员）、老人账号的代操作关系。
- 失败一律 `throw new BusinessException(ResultCode.X)`；**不要**返回 `null` 让 Controller 判空。

### 2.5 Entity / BaseEntity / 审计字段

- 所有业务表 Entity **必须**继承 `BaseEntity`（已含 `id / createTime / updateTime / deleted`）。
- 表名约定：下划线小写，复数或资源名（如 `companion_order`、`elder_profile`、`order_checkin`、`medication_task`）。
- 字段命名：数据库 `snake_case` ↔ Java `camelCase`（MyBatis-Plus 已配 `map-underscore-to-camel-case: true`，无需再单独 `@TableField` 映射）。
- 金额用 `BigDecimal`（数据库 `DECIMAL`）；时间统一 `LocalDateTime`（Jackson 已配 `yyyy-MM-dd HH:mm:ss`、`GMT+8`，**不要**再用 `Date`）。
- 主键：`@TableId(type = IdType.AUTO)`（MyBatis-Plus + DB 自增已开启）。
- 乐观锁字段：`@Version private Integer version;`（订单等并发敏感表必须）。
- 逻辑删除：依赖全局配置（`logic-delete-field: deleted` / `logic-delete-value: 1` / `logic-not-delete-value: 0`），**不要**再加 `@TableLogic`，也**不要**自己写 `WHERE deleted=0`。
- 字符集：所有表 `utf8mb4` / `utf8mb4_general_ci`（计划书硬约束）。

### 2.6 统一响应、分页、错误码

- `Result<T>`：`{ code, message, data }`。成功用 `Result.success(data)` / `Result.success(msg, data)`；失败用 `Result.fail(ResultCode.X)` / `Result.fail(code, message)`。
- **业务错误一律 HTTP 200**（除 Spring Security 直接抛的 401 / 403 与容器返回的 404 / 405 外）。前端 Axios 拦截器已经按 `code` 拆包，**不要**把业务失败做成 HTTP 500。
- `PageResult<T>`：`{ total, page, size, pages, records }`。MyBatis-Plus `Page` 转 `PageResult.of(page, mapper::toVO)`。
- `PageQuery<T>`：`page / size / sortField / sortOrder`；`size` 上限 100（注解 `@Max(100)` + `MybatisPlusConfig.setMaxLimit(100L)` 双重保护）。
- 排序字段 `sortField` 必须**白名单**，在 Service 层校验（防 SQL 注入 / 索引失效）。

### 2.7 错误码（ResultCode）

错误码是 `ResultCode` 枚举（`org.company.nianglin.common.ResultCode`）与 `docs/api/README.md` 错误码表的镜像。新增错误码必须：

1. 在 `ResultCode` 枚举里按段位（1xxx–9xxx）添加，注释写清楚业务场景。
2. 同步更新 `docs/api/README.md` 的对应段位表格。
3. **不要**新增跨段位或杂糅语义的码（如把订单相关错误放到 1xxx）。

段位含义：`2xx/4xx/5xx` 通用；`1xxx` 认证账号；`2xxx` 用户档案；`3xxx` 陪诊订单；`4xxx` 陪诊执行打卡；`5xxx` 用药；`6xxx` 评价投诉；`7xxx` 站内信；`8xxx` 管理后台；`9xxx` 统计导出。

### 2.8 鉴权 / Spring Security

- 当前 `SecurityConfig` 是骨架版：`anyRequest().permitAll()`，**M2 起**收紧为角色规则。
- 接口鉴权用 `@PreAuthorize("hasRole('FAMILY')")`（注意 `hasRole` 会自动拼 `ROLE_` 前缀，与 `RoleConstants.ROLE_PREFIX = "ROLE_"` 对应）。
- JWT 规格（已写入 `application.yml` 的 `nianglin.jwt`）：`accessToken` 120 分钟，`refreshToken` 7 天，载荷 `sub / role / iat / exp`；登出走 Redis 黑名单。
- 老人账号写操作必须由家属代操作：服务端要做两层校验（角色 + 资源归属），**不要**只校验角色。

### 2.9 日志与脱敏

- 统一 `Logback`（`logback-spring.xml`），用 `<springProfile>` 分环境，**不要拆成两个 xml 文件**：
  - `dev`（默认）：业务包 `DEBUG`、框架 `INFO`、MyBatis-Plus `INFO`，root `INFO`；两个 appender 都启用，保留 30 / 60 天滚动。
  - `prod`：所有 logger 与 root 一律 `ERROR`；`nianglin.log` 的 `ThresholdFilter` 双保险收紧到 `ERROR`，避免 INFO/WARN 噪音污染磁盘。
  - 临时排查时**临时**改 `prod` 分块切回 INFO，**排查完必须恢复 ERROR**，不要把放宽后的配置提交进库。
- 业务代码打日志**先脱敏再拼接**，禁止先拼后脱敏（容易漏字段）。
- 现有脱敏工具 `MaskUtil`（`org.company.nianglin.util.MaskUtil`）：
  - `MaskUtil.phone(String)` → `138****8888`
  - `MaskUtil.idCard(String)` → `110101********4567`
  - `MaskUtil.name(String)` → `张*丰`
  - `MaskUtil.nameAll(String)` → `张**`
  - `MaskUtil.bankCard(String)` → 保留前 4 后 4
  - `MaskUtil.address(String)` → 门牌号用 `***`
  - `MaskUtil.generic(value, prefix, suffix)` → 通用
- **禁止**依赖 Hutool `StrUtil.hide` 在脱敏场景（早期静默不脱敏的 bug 已在 `MaskUtil` 的 javadoc 标注）。

### 2.10 配置与环境

- 配置三层：`application.yml`（共享） / `application-dev.yml`（开发） / `application-prod.yml`（生产）。
- **所有敏感配置（MySQL 密码、Redis 密码、JWT secret）必须走环境变量**；配置默认值只能是 `CHANGE_ME` 这类占位串，禁止写真实密码。
- 业务配置统一挂在 `nianglin.*` 命名空间下（如 `nianglin.order.auto-cancel-minutes`、`nianglin.security.login-fail-threshold`），**不要**散落到 `spring.*` 之下。
- CORS：开发期允许 `http://localhost:5173` / `http://127.0.0.1:5173`；生产由 `nianglin.cors.allowed-origins` 收紧为真实域名。

---

## 3 · 前端编码规范（ESLint 9 + Prettier 3 为基线）

### 3.1 风格基线（与 `.prettierrc.json` 一致）

| 项 | 值 |
|---|---|
| 分号 | 无（`"semi": false`） |
| 引号 | 单引号（`"singleQuote": true`） |
| 每行宽度 | 100（`"printWidth": 100`） |
| 缩进 | 2 空格（`"tabWidth": 2`、`useTabs: false`） |
| 尾逗号 | 无（`"trailingComma": "none"`） |
| 箭头函数括号 | 始终（`"arrowParens": "always"`） |
| Vue `<script>` / `<style>` 缩进 | 不缩进（`"vueIndentScriptAndStyle": false`） |
| 行尾 | LF（`"endOfLine": "lf"`） |

### 3.2 ESLint 9 扁平配置要点

- 已开 `eslint-plugin-vue` 推荐规则 + `eslint-config-prettier`（关闭与 Prettier 冲突的格式化规则）。
- `'vue/multi-word-component-names': 'off'`（允许 `Login.vue` / `Dashboard.vue`）。
- `no-unused-vars` 允许 `_` 前缀的形参。
- `no-console` 仅允许 `console.warn` / `console.error`。
- 跑 `pnpm lint`（已配 `--fix`）必须 0 警告；提交前先跑。

### 3.3 Vue 3 + Vite 项目约定

- **全部用 `<script setup>`**（Composition API），**不要**写 Options API。
- 组件文件名：PascalCase（`BasicLayout.vue` / `ModulePlaceholder.vue`），目录按业务归类。
- 路由用 `() => import('@/views/...')` 懒加载。
- **不要**在 `views/` 下直接写请求，**必须**走 `src/api/<module>.js` 封装好的函数。
- Pinia store 用 setup 风格或 options 风格皆可，但现有 `app.js` / `user.js` 是 options 风格，新增 store **保持一致**。
- 状态值与后端枚举保持一致（`ELDER` / `FAMILY` / `COMPANION` / `ADMIN`）。

### 3.4 SCSS / 样式

- 设计令牌在 `frontend/src/styles/variables.scss`（编译期 SCSS 变量）。
- 运行期 CSS 自定义属性在 `frontend/src/styles/index.scss` 的 `:root` 里集中声明（`--nl-color-primary` 等）。
- **不要**在 `vite.config.js` 里配 SCSS `additionalData` 全局注入（项目刻意避免，会触发 Sass 模块循环错误）。每个 `.vue` / `.scss` 文件需要变量时显式：
  ```scss
  @use '@/styles/variables.scss' as *;
  ```
- Element Plus 主题打通通过 `index.scss` 里的 `--el-color-primary: var(--nl-color-primary);` 等覆盖实现。

### 3.5 适老化（老人模式）硬约束（M11 验收）

| 项 | 数值 / 实现 |
|---|---|
| 老人模式基线字号 | ≥ 18px（`--nl-font-base` 在 `elderly-mode` 下切到 `$elderly-font-min`） |
| 可点击元素最小高度 | ≥ 48px（`$elderly-touch-min`，按下 `--nl-touch-min` 切换） |
| 文字对比度 | ≥ 4.5:1（WCAG AA，`$elderly-contrast-ratio`） |
| 老人模式触发方式 | `<html>` 上加 class `elderly-mode`（由 `appStore.applyElderlyClass()` 维护，**不要**靠刷新页面） |
| 老人模式菜单 | 路由 `meta.elderlyHidden: true` 的项在老人模式下隐藏（`BasicLayout.vue` 已实现过滤） |
| 核心流程点击次数 | ≤ 3 次 |

新增样式时**必须考虑**老人模式下的可读性 / 可点击性。涉及字号、按钮、表格、菜单时，**先看** `elderly.scss` 是怎么改 CSS 变量的，跟着改而非重复写硬编码。

### 3.6 Axios 封装与接口调用

- 业务代码**只能** `import request from '@/utils/request'`，**不要**直接 `import axios from 'axios'`。
- 请求需要 token 时由 `request.js` 自动带 `Authorization: Bearer <token>`；公开接口（如 `/auth/login`、`/auth/captcha`、`/health`）传 `skipAuth: true`。
- 响应拦截器已经做了：
  - `code === 200` → 直接 `return data`（业务拿到的就是 `data` 字段）
  - 业务错误 → 弹 `ElMessage.error(message)` 并 `reject`
  - HTTP 401 → 尝试 `/api/auth/refresh` 换 token 并重放原请求（M2 实装后启用）；刷新失败清空登录态并跳 `/login`
  - 其他 HTTP 错误 → 用 `httpStatusMessage` 映射成中文提示
- 因此**业务代码不要再 `if (res.code !== 200)`**，直接 `await api.xxx()` 取数据。
- 拦截器已有并发刷新队列（`refreshing` / `pendingQueue`），**不要**自己再写一个。

### 3.7 Pinia 状态

- `userStore`：登录态（`token / userInfo / routesLoaded`）+ 角色相关 getter（`isLogin / role / isAdmin / isFamily / isCompanion / isElder`）。userInfo 只缓存 `id / username / nickname / role / avatar`，**禁止**写完整手机号 / 身份证号。
- `appStore`：UI 全局（`title / sidebarCollapsed / elderlyMode / loadingCount`），方法 `toggleSidebar / setElderlyMode / toggleElderlyMode / applyElderlyClass / initElderlyMode / startLoading / stopLoading`。
- 新增状态前先确认现有两个 store 不能复用，再决定是否新建 `store/modules/<name>.js`。

### 3.8 localStorage 读写

- 所有 key 集中在 `frontend/src/utils/auth.js`（`TOKEN_KEY` / `REFRESH_TOKEN_KEY` / `USER_INFO_KEY` / `ELDERLY_MODE_KEY`），**不要**在业务代码里再散写 `localStorage.setItem('xxx', ...)`。
- `clearAuth()` 是清空登录态的唯一入口，登出 / 401 都用它。
- **禁止**写入密码、身份证号、完整手机号。

### 3.9 路由与守卫

- 路由表集中在 `frontend/src/router/routes.js`，每个路由必须带 `meta.module`（对应 `plan.md` 的模块编号）。
- 角色守卫在 `router/index.js` 的 `beforeEach`，通过 `AUTH_ENABLED` 开关开启（M2 交付后置 `true`）。
- `meta.roles` 为空数组表示所有已登录角色可访问；不要在路由组件内部再判角色。
- 老人模式下 `meta.elderlyHidden: true` 的路由会从菜单中隐藏，但**仍可通过 URL 进入**——这是设计如此（老人模式下也需要排障入口）。

### 3.10 API 封装规则

- 每个后端模块对应一个 `frontend/src/api/<module>.js`，并在 `frontend/src/api/index.js` 用 `export * as xxxApi from './xxx'` 统一出口。
- 函数命名用动词：`getXxx` / `listXxx` / `createXxx` / `updateXxx` / `removeXxx` / `acceptXxx` / `cancelXxx` / ...
- 每个文件头注释要写：`对应文档：docs/api/<n>-<name>.md` + `负责模块：M<n> <模块名>`。
- **接口先行约定**：后端未交付前用 `Promise.resolve({...mock...})` 占位，但路径 / 参数 / 响应结构必须与 `docs/api/` 对齐，**不要**等后端写完再调。

---

## 4 · 业务规则（核心约束，不是建议）

### 4.1 陪诊订单状态机（最重要）

```
PENDING ──► ACCEPTED ──► IN_SERVICE ──► COMPLETED ──► REVIEWED
   │           │              │             │
   │           │              │             │
   └───────────┴──────────────┴─────────────┴──► CANCELLED
```

进入 `CANCELLED` 有**两条互不相同**的路径，不要混用：

| 路径 | 允许的起点 | 入口 |
|---|---|---|
| **家属取消** | **仅** `PENDING` | `PUT /api/order/{id}/cancel`（须为本单下单人） |
| **管理员强制** | `PENDING` / `ACCEPTED` / `IN_SERVICE` / `COMPLETED` | M9 纠纷处理 `arbitrate` |

管理员强制还可把 `PENDING` / `ACCEPTED` / `IN_SERVICE` → `COMPLETED`。
`REVIEWED` 是终态，**任何路径都不得再变更**（含管理员强制）。

铁律（违反即返回 `3002` / `ORDER_STATUS_ILLEGAL`）：

1. **禁止跳级**：待接单不能直接变已完成。
2. **禁止回退**：已接单不能退回待接单。
3. **终态不可再流转**：已评价 / 已取消之后无法变更（`REVIEWED` / `CANCELLED` 对管理员强制同样关闭）。
4. 仅 ADMIN 可强制改变终态，且必须写 `admin_oper_log` 并通知双方。
5. 每次状态变更写入状态流转日志，异常时整体回滚。

状态判断**必须**走 `OrderStatus` 枚举，**禁止**硬编码状态字符串：

- **正向流转的唯一权威判断**是 `OrderStatus.canTransitTo(target)`（`TRANSITIONS` 只承载正向流转，不含 `→CANCELLED`）；
- **管理员强制终态是明确豁免的独立路径**，由 `forceTerminal()` + `isAdminForceable()` + `isTerminal()` 承担，
  并被 `docs/api/08-admin.md` 记为"绕过状态机的正向流转规则"；
- 因此 `PENDING → CANCELLED`（家属取消）**必须**以 `canTransitTo` 表达，管理员强制边**不得**塞进 `TRANSITIONS`。

> ⚠️ **现状（2026-09-17 复核）**：`canTransitTo` 在 `OrderServiceImpl` 中**尚未被调用**，
> 6 处流转（`cancel` / `accept` / `reject` / `start` / `complete` / `markReviewed`）仍为硬编码等值判断。
> 修复方案与影响面见 `docs/review/2026-09-17-m2-auth.md`「口径 A」。

### 4.2 打卡节点（M5）

| 传值 | 中文 |
|---|---|
| `DEPART` | 出发 |
| `ARRIVE` | 到院 |
| `IN_CONSULT` | 就诊中 |
| `TAKE_MEDICINE` | 取药 |
| `LEAVE` | 离院 |
| `FINISH` | 完成 |

约束：节点顺序不可逆（可跳过，不可回退）；同一订单同一节点只能打一次（4002）；坐标偏离订单地址超过 2000 米（`nianglin.order.checkin-max-distance-meters`）判为无效（4001）。

### 4.3 老人账号规则

| 场景 | 允许角色 |
|---|---|
| 查询类接口（GET） | ELDER 可访问自己名下数据 |
| 写操作（POST/PUT/DELETE） | **ELDER 一律 403**，仅 FAMILY / COMPANION / ADMIN 按各自规则放行 |

实现要求：服务端拦截；前端隐藏按钮**只提升体验，不构成安全边界**。

### 4.4 字段命名 / 时间格式 / 字符集

| 项 | 约定 |
|---|---|
| JSON 字段 | 小驼峰（`createTime`），DB 字段下划线（`create_time`），由 MyBatis-Plus 自动映射 |
| 枚举 | 传字符串大写枚举名，同时返回 `xxxLabel` 中文 |
| 布尔 | `is` / `has` 前缀 |
| ID | 路径变量用 `{id}`；批量用 body 数组 |
| 时间格式 | 字符串 `yyyy-MM-dd HH:mm:ss`（Jackson 已配）；仅日期 `yyyy-MM-dd` |
| 时区 | `Asia/Shanghai`（GMT+8） |
| 金额 | 字符串、两位小数，如 `"128.00"`（避免 JS 浮点误差） |
| 主键 | `Long`，JSON 中为数字 |
| null 字段 | Jackson `non_null` 策略，可省略 |
| 路径命名 | 全部小写、连字符、资源名用复数（`/api/order/hall`、`/api/medication/plan`） |

### 4.5 文件上传（M3 / M9）

| 项 | 约定 |
|---|---|
| 请求类型 | `multipart/form-data`，字段名统一 `file` |
| 单文件上限 | 10 MB |
| 单请求上限 | 20 MB |
| 允许类型 | `image/jpeg`、`image/png`、`image/webp`、`application/pdf` |
| 返回 | `{ fileId, url, size }` |
| 存储 | 一期落本地 `./uploads/{yyyyMM}/{uuid}.{ext}`，M13 可替换为对象存储 |
| 校验 | 必须读文件头（magic bytes）二次确认，**禁止**信任前端传来的 `Content-Type` |

---

## 5 · 数据库 / Flyway / 数据初始化（M1）

- 用 Flyway 做版本化迁移；脚本放 **`backend/sql/`**（人工维护的唯一真源），
  命名 `V1__init_schema.sql` / `V2__seed_data.sql` / `V3__seed_boundary.sql`。
  构建时由 `backend/pom.xml` 的 `<resources>` 复制到 classpath 的 `db/migration/` 供 Flyway 读取，
  **同一份脚本不要写两遍**（`backend/src/main/resources/db/migration/` 下不再放脚本）。
- **已执行过的脚本不得再改**：`application-dev.yml` 中 `validate-on-migrate: true` 会校验 checksum，
  改动已执行脚本会导致下次启动迁移失败。追加数据一律新开版本号（`V4__xxx.sql`…）。
- 启用条件：`application-dev.yml` 中 `flyway.enabled` —— **M1 已置为 `true`**。
- **生成与校验工具在 `backend/sql/tools/`**：Entity/Mapper 生成（`gen_entity.py`）、
  种子生成（`gen_seed.py`）、数据库文档生成（`gen_db_docs.py`）、
  数据校验（`verify_data.sql`）、索引校验（`explain_index.sql`）。
  改完 `V1__init_schema.sql` 必须重跑 `gen_entity.py` 与 `gen_db_docs.py`，否则实体与 `docs/db/` 会和表结构漂移。
- 数据库设计文档见 **`docs/db/`**（5 篇：约定与表清单 / ER 图 / 数据字典 / 索引与 EXPLAIN / 种子数据说明），
  由 `gen_db_docs.py` 生成，禁止手工修改。
- 关键业务表至少：`sys_user` / `elder_profile` / `family_elder_relation` / `companion_profile` / `companion_order`（含 `version` 乐观锁） / `order_checkin` / `companion_track` / `medicine_dict` / `medication_plan` / `medication_task` / `order_review` / `complaint` / `internal_message` / `admin_oper_log`。
- 种子数据每张表 ≥ 30 条，覆盖空字段 / 超长文本 / 非常用药 / 跨天用药等边界场景。
- 关键查询必须走索引：订单按 `status + visit_time`、用药任务按 `elder_id + plan_date + status`、陪诊员按资质筛选；`EXPLAIN` 的 `type` 不能为 `ALL`。
- DDL 必须幂等（`CREATE TABLE IF NOT EXISTS` / `DROP TABLE IF EXISTS` + `CREATE TABLE`），不能因已存在表而中断。

---

## 6 · 测试与质量

### 6.1 后端单测（JUnit5 + Mockito）

- 单测位置 `backend/src/test/java/org/company/nianglin/`，包结构与 main 镜像。
- 已有 `MaskUtilTest`、`OrderStatusTest`、`ResultTest`，覆盖**隐私脱敏**与**订单状态机**两个高风险点。新增单测**优先**补这两类回归测试。
- 测试方法命名：`methodNameShouldXxx`，类级别 `@DisplayName("中文场景")`。
- 业务 Service 优先用 Mockito 单测；Controller 层可以用 `@WebMvcTest` 做切片。
- 验收用例：M2 必须覆盖 **4 角色 × 3 类接口（只读 / 写 / 管理）= 12 条越权用例**。

### 6.2 前端

- 暂无强制单测要求；M12 起补 Pinia store 与工具类的 Vitest 单测。

### 6.3 静态检查

- 前端：`pnpm lint`（ESLint + Prettier），0 警告；`pnpm format` 批量格式化。
- 后端：Alibaba Java 规范插件在 `git commit` 前必须过（计划书要求），AI 输出 Java 时按以下硬要求自查：
  - 类、字段、方法注释齐全；
  - `static final` 常量命名 `UPPER_SNAKE_CASE`；
  - `Long` 比较必须 `L` 后缀；
  - 集合初始化必须指定容量；
  - `@Resource` / `@Autowired` 用构造注入（Lombok `@RequiredArgsConstructor` + `final`）。

---

## 7 · Git 与协作

### 7.1 分支模型

```
main（受保护）← develop ← feature/<module>-<name>
                          bugfix/<module>-<name>
                          docs/<name>
```

- **禁止**直接向 `main` / `develop` 推 push。
- 每周五合并到 `develop`。
- `main` 设为受保护分支，PR 必须有 Code Review。

### 7.2 提交信息格式

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

### 7.3 AI 输出提交相关动作的硬约束

- **AI 只能** 在用户明确授权后才执行 `git commit` / `git push`。
- AI **必须** 生成符合上述格式的提交信息再 commit。
- AI **禁止** `git push --force` 到任何受保护分支。
- AI **禁止** 直接修改 `main` / `develop`，必须基于 `feature/...` 分支。

---

## 8 · 接口先行（这是本项目的开发模式）

> **约定**：后端先按 `docs/api/` 出接口与 Mock 数据；前端用 Mock 开发，不等后端写完。
> 接口一旦变更，必须在群内提前通知前端，并**同步更新 `docs/api/` 与 Knife4j 注解**。

AI 在任何"新增 / 修改 / 删除接口"的场景下，必须：

1. 先改 `docs/api/<n>-<module>.md`（路径、方法、权限、请求体、响应、错误码、字段说明、示例）。
2. 再改后端 Controller / Service / DTO / VO，并补 `@Operation` / `@Tag` / `@Schema` 注解（Knife4j 自动生成）。
3. 再改前端 `frontend/src/api/<module>.js` 与对应页面，**不要**让前端先发现接口变了。
4. 若新增错误码：先更新 `ResultCode` 枚举，再更新 `docs/api/README.md` 错误码总表，**两处必须保持一致**。

---

## 9 · 文档同步清单（每次代码改动要联动更新的文档）

| 改动内容 | 必联动更新 |
|---|---|
| 新增 / 修改接口 | `docs/api/<module>.md` + Knife4j 注解 + `frontend/src/api/<module>.js` |
| 新增 / 修改错误码 | `ResultCode.java` + `docs/api/README.md` 错误码总表 |
| 新增 / 修改状态机流转 | `OrderStatus.java` 的 `TRANSITIONS` + `OrderStatusTest` + `docs/api/03-order.md` 状态机章节 |
| 新增 / 修改依赖 | `pom.xml` / `package.json` + README 技术栈表 |
| 新增 / 修改菜单 / 路由 | `frontend/src/router/routes.js` + `BasicLayout.vue`（若需要老人模式隐藏） |
| 新增 / 修改样式令牌 | `frontend/src/styles/variables.scss`（编译期） + `index.scss` 的 `:root`（运行期） + `elderly.scss`（老人模式覆盖） |
| 新增 / 修改数据库表 | Flyway 脚本 + `ResultCode`（若有新业务码） + `docs/api/<module>.md` 数据模型小节 |
| 新增 / 修改合规相关（脱敏、加密、日志） | `MaskUtil` 单测 + `GlobalExceptionHandler` 日志策略 + README 合规约束章节 |

---

## 10 · Do / Don't 速查（AI 工具的最低底线）

### ✅ DO

- 先读本文件 + `README.md` + `plan.md` + 对应 `docs/api/<module>.md`，再动手。
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
- [ ] 没有改 `plan.md` / `README.md` / `.gitignore` / `pom.xml` / `package.json` / `.workbuddy/` 之外的非业务文件（除非用户明确要求）。
- [ ] 没有出现"建议服用 / 推荐剂量 / 诊断为 / 可能是 XX 病"等违反合规的文案。

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

Single-context layout: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.