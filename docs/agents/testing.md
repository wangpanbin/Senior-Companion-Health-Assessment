# 6 · 测试与质量

> 本文件是 `AGENTS.md` **§6** 的落地位置。
> 拆分原因：运行时给仓库根 `AGENTS.md` 的上下文预算是 **32 KB**，
> 拆分前它有 **54 KB** —— 超出预算的部分**每次会话都被静默丢弃**，
> 丢掉的恰好是订单状态机、Do/Don't 底线、验收清单这些最该被看见的内容。
> 拆开后 `AGENTS.md` 只留红线与导航，细则**按需加载**。
>
> ⚠️ 硬约束仍在 `AGENTS.md`（始终在上下文里）：§0 合规红线、§0.2 AI 自我约束、
> §10 Do/Don't 最低底线、§11 验收检查清单。本文件是细则，不覆盖那些硬约束。
>
> 小节编号（§6.1 …）**未改动**，仓库其它文档里的 `AGENTS.md §6.N` 引用仍然对得上。

---

## 6.1 后端单测（JUnit5 + Mockito）

- 单测位置 `backend/src/test/java/org/company/nianglin/`，包结构与 main 镜像。
- 已有 `MaskUtilTest`、`OrderStatusTest`、`ResultTest`，覆盖**隐私脱敏**与**订单状态机**两个高风险点。新增单测**优先**补这两类回归测试。
- 测试方法命名：`methodNameShouldXxx`，类级别 `@DisplayName("中文场景")`。
- 业务 Service 优先用 Mockito 单测；Controller 层可以用 `@WebMvcTest` 做切片。
- 验收用例：M2 必须覆盖 **4 角色 × 3 类接口（只读 / 写 / 管理）= 12 条越权用例**。

## 6.2 前端

- 单测落在 `frontend/src/__tests__/`（工具类 / store / 组合式函数）与
  `frontend/src/components/__tests__/`（共享组件）。**页面级不写单测**，靠下面的 Playwright 套件覆盖。
  CI 里 `vitest` 是硬门槛（`pnpm run test`，当前 27 个文件 / 269 个用例）。
- **E2E（Playwright）**：套件在 `frontend/e2e/`，跑法与前置见 `docs/agents/FRONTEND_CONTRACT.md` §13。
  改动登录态 / 路由守卫 / 页面主流程后，先跑 `pnpm exec playwright test specs/01-auth.spec.js`
  （含 F-01 两会话回归），再按影响面跑对应 spec。`playwright.config.js` 里 `video: 'off'`，**不需要 ffmpeg**。

## 6.2b 新增实体的两处登记（测试会挡）

新建带 `@TableName` 的实体后有两处必须同步，两处都已被自动检查覆盖，漏了会在 `mvn test` 阶段直接红：

| 登记处 | 不登记的现象 | 守它的测试 |
|---|---|---|
| `backend/sql/V*.sql`（建表） + `docs/db/`（重跑 `gen_db_docs.py`） | 库里有表、字典里没有 | 无自动检查；`docs/agents/database.md` 有约定 |
| `test/.../support/MybatisLambdaCache.ENTITY_CLASSES` | 纯 Mockito 单测**单独跑红、全量跑绿**（假绿，靠执行顺序决定成败） | `MybatisLambdaCacheCompletenessTest` |

## 6.3 静态检查

- 前端：`pnpm lint`（ESLint + Prettier），0 警告；`pnpm format` 批量格式化。
- 后端：Alibaba Java 规范插件在 `git commit` 前必须过（计划书要求），AI 输出 Java 时按以下硬要求自查：
  - 类、字段、方法注释齐全；
  - `static final` 常量命名 `UPPER_SNAKE_CASE`；
  - `Long` 比较必须 `L` 后缀；
  - 集合初始化必须指定容量；
  - `@Resource` / `@Autowired` 用构造注入（Lombok `@RequiredArgsConstructor` + `final`）。
