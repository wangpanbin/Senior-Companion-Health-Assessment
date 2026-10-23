# 2 · 后端编码规范（Alibaba Java 规范为基线）

> 本文件是 `AGENTS.md` **§2** 的落地位置。
> 拆分原因：运行时给仓库根 `AGENTS.md` 的上下文预算是 **32 KB**，
> 拆分前它有 **54 KB** —— 超出预算的部分**每次会话都被静默丢弃**，
> 丢掉的恰好是订单状态机、Do/Don't 底线、验收清单这些最该被看见的内容。
> 拆开后 `AGENTS.md` 只留红线与导航，细则**按需加载**。
>
> ⚠️ 硬约束仍在 `AGENTS.md`（始终在上下文里）：§0 合规红线、§0.2 AI 自我约束、
> §10 Do/Don't 最低底线、§11 验收检查清单。本文件是细则，不覆盖那些硬约束。
>
> 小节编号（§2.1 …）**未改动**，仓库其它文档里的 `AGENTS.md §2.N` 引用仍然对得上。

---

## 2.1 必须遵守的硬约束

- **JDK 21**，`pom.xml` 已锁定 `<java.version>21</java.version>`。
- **包名**统一 `org.company.nianglin.*`；新模块继续沿用 `constant / config / common / exception / util` 这些已有包，不要新建并列顶层包。
- **Lombok**已就绪：Entity / DTO / VO 用 `@Data`；链式 setter 用 `@Accessors(chain = true)`；Controller / Service 优先 `@RequiredArgsConstructor` + `final` 注入字段；日志统一 `@Slf4j`。**不要**手写 getter / setter / `private static final Logger log = ...`。
- **不要**在 Entity 上做业务校验，业务校验在 DTO 上用 Jakarta Validation（`@NotBlank`、`@NotNull`、`@Size`、`@Pattern`、`@Min`、`@Max`）并在 Controller 上加 `@Valid` / `@Validated`。
- **Entity 不得直接返回**。所有接口返回必须转 VO；VO 中若包含手机号 / 身份证号 / 地址，必须在转换时调用 `MaskUtil`。
- **业务失败统一抛 `BusinessException(ResultCode.X)`**，由 `GlobalExceptionHandler` 统一转为 `Result.fail(code, message)`。**不要**在 Controller 里 `try/catch` 后 `return Result.fail(...)`，那是异常处理的反模式。
- **日志**：调用 `MaskUtil` 之后再拼日志串，**禁止**直接 `log.info("phone={}", user.getPhone())`。

## 2.2 分层与命名

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

## 2.3 Controller 模板（AI 必须按此形态生成）

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

## 2.4 Service 模板

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

## 2.5 Entity / BaseEntity / 审计字段

- 所有业务表 Entity **必须**继承 `BaseEntity`（已含 `id / createTime / updateTime / deleted`）。
- 表名约定：下划线小写，复数或资源名（如 `companion_order`、`elder_profile`、`order_checkin`、`medication_task`）。
- 字段命名：数据库 `snake_case` ↔ Java `camelCase`（MyBatis-Plus 已配 `map-underscore-to-camel-case: true`，无需再单独 `@TableField` 映射）。
- 金额用 `BigDecimal`（数据库 `DECIMAL`）；时间统一 `LocalDateTime`（Jackson 已配 `yyyy-MM-dd HH:mm:ss`、`GMT+8`，**不要**再用 `Date`）。
- 主键：`@TableId(type = IdType.AUTO)`（MyBatis-Plus + DB 自增已开启）。
- 乐观锁字段：`@Version private Integer version;`（订单等并发敏感表必须）。
- 逻辑删除：依赖全局配置（`logic-delete-field: deleted` / `logic-delete-value: 1` / `logic-not-delete-value: 0`），**不要**再加 `@TableLogic`，也**不要**自己写 `WHERE deleted=0`。
- 字符集：所有表 `utf8mb4` / `utf8mb4_general_ci`（计划书硬约束）。

## 2.6 统一响应、分页、错误码

- `Result<T>`：`{ code, message, data }`。成功用 `Result.success(data)` / `Result.success(msg, data)`；失败用 `Result.fail(ResultCode.X)` / `Result.fail(code, message)`。
- **业务错误一律 HTTP 200**（除 Spring Security 直接抛的 401 / 403 与容器返回的 404 / 405 外）。前端 Axios 拦截器已经按 `code` 拆包，**不要**把业务失败做成 HTTP 500。
- `PageResult<T>`：`{ total, page, size, pages, records }`。MyBatis-Plus `Page` 转 `PageResult.of(page, mapper::toVO)`。
- `PageQuery<T>`：`page / size / sortField / sortOrder`；`size` 上限 100（注解 `@Max(100)` + `MybatisPlusConfig.setMaxLimit(100L)` 双重保护）。
- 排序字段 `sortField` 必须**白名单**，在 Service 层校验（防 SQL 注入 / 索引失效）。

## 2.7 错误码（ResultCode）

错误码是 `ResultCode` 枚举（`org.company.nianglin.common.ResultCode`）与 `docs/api/README.md` 错误码表的镜像。新增错误码必须：

1. 在 `ResultCode` 枚举里按段位（1xxx–10xxx）添加，注释写清楚业务场景。
2. 同步更新 `docs/api/README.md` 的对应段位表格。
3. **不要**新增跨段位或杂糅语义的码（如把订单相关错误放到 1xxx）。

段位含义：`2xx/4xx/5xx` 通用；`1xxx` 认证账号；`2xxx` 用户档案；`3xxx` 陪诊订单；`4xxx` 陪诊执行打卡；`5xxx` 用药；`6xxx` 评价投诉；`7xxx` 站内信与订单聊天（M13）；`8xxx` 管理后台；`9xxx` 统计导出；**`10xxx` 智能助手（M14 · V3.7 新开）**。

## 2.8 鉴权 / Spring Security

- 当前 `SecurityConfig` 是骨架版：`anyRequest().permitAll()`，**M2 起**收紧为角色规则。
- 接口鉴权用 `@PreAuthorize("hasRole('FAMILY')")`（注意 `hasRole` 会自动拼 `ROLE_` 前缀，与 `RoleConstants.ROLE_PREFIX = "ROLE_"` 对应）。
- JWT 规格（已写入 `application.yml` 的 `nianglin.jwt`）：`accessToken` 120 分钟，`refreshToken` 7 天，载荷 `sub / role / iat / exp`；登出走 Redis 黑名单。
- 老人账号写操作必须由家属代操作：服务端要做两层校验（角色 + 资源归属），**不要**只校验角色。

## 2.9 日志与脱敏

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

## 2.10 配置与环境

- 配置三层：`application.yml`（共享） / `application-dev.yml`（开发） / `application-prod.yml`（生产）。
- **所有敏感配置（MySQL 密码、Redis 密码、JWT secret）必须走环境变量**；配置默认值只能是 `CHANGE_ME` 这类占位串，禁止写真实密码。
- 业务配置统一挂在 `nianglin.*` 命名空间下（如 `nianglin.order.auto-cancel-minutes`、`nianglin.security.login-fail-threshold`），**不要**散落到 `spring.*` 之下。
- CORS：开发期允许 `http://localhost:5141` / `http://127.0.0.1:5141`（前端 dev server 真实端口是 **5141**，由 `vite.config.js` 的 `VITE_PORT` 默认值与 `playwright.config.js` 的 `BASE_URL` 一致确定；`application-dev.yml` 用 `[*]` 通配放行）；生产由 `nianglin.cors.allowed-origins` 收紧为真实域名。
- ⚠️ **`SecurityConfig` 与 `WebMvcConfig` 两处 `@Value` 兜底默认值必须同步**（2026-09-29 收尾验证发现）：两者读同一个 `nianglin.cors.allowed-origins`，但各有各的兜底串。`application-dev.yml` 配了值所以开发期看不出差异，而 **`application-prod.yml` 根本没有 `nianglin.cors` 段** → 生产会静默走 `@Value` 兜底。当时只修了 `WebMvcConfig`、漏了 `SecurityConfig`，结果是同一进程内 Spring Security 层放行 5173、MVC 层放行 5141。**改端口时两处一起改，并确认目标 profile 是否真的会走到兜底。**

## 2.11 Python 侧（ai-service）编码规范

`ai-service/` 是 V3.7 起引入的**独立 Python 进程**（FastAPI，端口 8081，只监听 `127.0.0.1`）。它不是「后端的一个模块」，而是**第二个技术栈** —— 写它之前先读 `AGENTS.md` §0 的技术栈表与 §0.1 的已记录例外。

- **纯函数优先**：意图分类（`intents.py`）、FAQ 检索（`knowledge.py`）、护栏（`guard.py`）**都是不依赖 LLM 的纯函数** —— 不碰网络、不碰时钟、不依赖全局状态。
  **判定逻辑一旦依赖模型就没法写单测**，所以「先分类 → 再检索 → 最后叙述」的顺序不能改，LLM 只允许出现在最后一步的**叙述**上。
- **降级路径与主路径同构**：未配置 `LLM_BASE_URL`、断网、超时、模型报错，**四种情况走同一条降级路径**（直接返回 FAQ 条目原文，`degraded=true`）。不要写成「主路径 + 一个补丁」；也**不要**把「没配模型」当成错误状态。降级出来的文本**同样要过输出护栏**。
- **护栏写在代码里，不写在 prompt 里**：prompt 是「请求模型遵守」的约定，代码是「模型不遵守也拦得住」的保证。医疗意图的输入侧拦截、禁用词的输出侧替换，都必须在 `guard.py` 里用确定性的字符串匹配实现；prompt 里的对应文字只是第二层。
- **共享合规资产读同一份文件**：`backend/src/main/resources/compliance/` 下三份 —— `medical-advice-words.txt`（医疗建议禁用词，Java `ComplianceCheckUtil`）、`sensitive-words.txt`（辱骂 / 违规引流词，Java `SensitiveWordUtil`）、`refusal-template.txt`（固定拒答模板）。Python 侧 `app/paths.py` **读同样的文件**（路径可用 `NIANGLIN_*_PATH` 覆盖）。**读不到 / 词数过少 / 缺关键词 → 启动失败**，不许「读空继续跑」——词表读空等于护栏静默失效。**不要在 Python 侧另抄一份词表。**
  - 拒答模板也共享，是因为**它本身不得含任何禁用词** —— 两侧各写一份，早晚有一侧把自己拦下。
- **FAQ 用 JSON，不引 PyYAML**：`knowledge/faq.json` 由 Python 标准库直接读，零依赖。**要改 YAML 必须先报批**（`PyYAML` 不在已批准的依赖清单里）。
- **依赖清单冻结**：`ai-service/requirements.txt` 是**经人工逐项批准**的清单，**不擅自加一行**；需要新依赖时先说明理由、报批，再改清单。已刻意不引入：向量库 / embedding 模型 / 分词库 / redis-py / PyYAML。
- **不引入自主 Agent**：决策链写死在代码里（输入护栏 → 意图分类 → FAQ 检索 → 事实装配 → 叙述 → 输出护栏），不用 `AgentExecutor` / `create_react_agent`。
- **无状态、无鉴权、无数据库连接**：助手服务不查库、不调 Java、不持有任何令牌；对话历史由前端 `sessionStorage` 保管，后端不落库。
- **测试口径**：核心断言**不依赖 LLM 与网络**；`python -m pytest ai-service/tests -q` 必须在**没有配模型**的环境里全绿。需要真实模型的用例（如 `test_llm_smoke.py`）在缺 `LLM_BASE_URL` 时用 `skipif` 跳过。
