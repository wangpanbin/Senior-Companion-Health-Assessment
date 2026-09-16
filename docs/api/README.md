# 银龄伴诊 · 接口设计文档

> 老年人就医陪诊与用药协同管理平台 —— 后端 REST API 设计说明
>
> 版本：v0.2.0（M2 认证鉴权 + M3 用户与档案 已交付）　最后更新：2026-09-15
>
> 在线文档（后端启动后）：`http://localhost:8080/doc.html`

---

## 目录

| 文档 | 覆盖模块 | 负责模块编号 |
|---|---|---|
| **README.md**（本文） | 全局约定、错误码、权限矩阵 | — |
| [01-auth-user.md](./01-auth-user.md) | 认证、登录、当前用户 | M2 |
| [02-elder-family.md](./02-elder-family.md) | 老人档案、家属绑定、陪诊员资质 | M3 |
| [03-order.md](./03-order.md) | 陪诊订单与状态机 | M4 |
| [04-companion-execution.md](./04-companion-execution.md) | 陪诊打卡、轨迹、实时进度 | M5 |
| [05-medication.md](./05-medication.md) | 药品字典、用药计划、服药任务 | M6 |
| [06-review-complaint.md](./06-review-complaint.md) | 评价与投诉 | M7 |
| [07-message.md](./07-message.md) | 站内信与通知 | M8 |
| [08-admin.md](./08-admin.md) | 管理后台（审核 / 用户 / 订单 / 日志） | M9 |
| [09-statistics-export.md](./09-statistics-export.md) | 数据统计、可视化、Excel 导出 | M10 |

> **接口先行约定**：后端先按本文档出接口与 Mock 数据，前端用 Mock 开发，不等后端写完。
> 接口一旦变更，必须在群内提前通知前端，并同步更新本文档与 Knife4j 注解。

---

## 一、通用约定

### 1.1 基础信息

| 项目 | 约定 |
|---|---|
| 基础路径 | `/api` |
| 协议 | HTTP/1.1（~~生产环境由 Nginx 终止 HTTPS~~ — ⏸️ 现阶段不做部署上线） |
| 请求编码 | UTF-8 |
| 请求内容类型 | `application/json;charset=UTF-8`（文件上传用 `multipart/form-data`） |
| 响应内容类型 | `application/json;charset=UTF-8`（文件下载用 `application/octet-stream`） |
| 时间格式 | 字符串 `yyyy-MM-dd HH:mm:ss`；仅日期用 `yyyy-MM-dd`（由 `config/JacksonConfig.java` 强制，`spring.jackson.date-format` 对 `LocalDateTime` **无效**） |
| 时区 | `Asia/Shanghai`（GMT+8） |
| 金额格式 | 字符串，两位小数，单位元，如 `"128.00"`（避免 JS 浮点误差） |
| 主键类型 | `Long`，JSON 中为数字 |

### 1.2 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| `Authorization` | 需鉴权接口必填 | `Bearer <accessToken>`，注意 `Bearer` 与 token 之间有且只有一个空格 |
| `Content-Type` | POST/PUT 必填 | `application/json;charset=UTF-8` |
| `X-Request-Id` | 否 | 前端生成的请求追踪 ID，便于定位问题（可选） |

---

## 二、鉴权

### 2.1 流程

```
① 前端 GET  /api/auth/captcha            → 拿到 captchaKey + captchaImage(base64)
② 前端 POST /api/auth/login              → 提交 username / password / captchaKey / captchaCode
③ 后端返回 accessToken + refreshToken    → 前端存入 localStorage
④ 后续请求带 Authorization: Bearer <accessToken>
⑤ accessToken 过期（HTTP 401 或 code=401）→ 前端用 refreshToken 调 /api/auth/refresh
⑥ 刷新成功 → 重放原请求；刷新失败 → 清空登录态并跳登录页
```

### 2.2 Token 规格

| 项目 | 约定 |
|---|---|
| 类型 | JWT（HS256） |
| `accessToken` 有效期 | 120 分钟 |
| `refreshToken` 有效期 | 7 天 |
| 载荷字段 | `sub`（用户 ID）、`role`（角色）、`jti`（令牌唯一标识）、`ver`（密码版本）、`iat`、`exp` |
| 登出 | ① 把当前令牌的 `jti` 写入 Redis 黑名单直至自然过期；② **该用户密码版本 `+1`**，名下所有已签发令牌立即失效 |

> ⚠️ **登出 = 全端下线**：因为登出会递增密码版本，同一账号在 A 设备登出后，B 设备手里的令牌会立刻验票失败（HTTP 401）。一期按「安全优先」接受这一行为，多设备并行登录的需求留到 M12 再评估（与 `refreshToken` 滚动续签一并处理）。

> **`ver` 必须等于 Redis 的 `pwd:version:{userId}`**，否则认证过滤器直接判为无效令牌（HTTP 401）。
> 该键**没有 TTL**：首次登录时由 `ensurePasswordVersion` 显式写入 `0`（防止「Redis 被清空后旧 `ver=0` 令牌复活」），登出 / 改密时 `+1`。
> 因此**写测试或调试脚本时必须读取实时版本号再签令牌**，不能臆测它是 `0` —— 见 `test/support/TestTokens.java` 与下方「测试约定」。

### 2.3 无状态与越权防护

- 服务端不使用 Session，`SessionCreationPolicy.STATELESS`。
- **所有权限判断必须在服务端完成**。前端隐藏按钮只提升体验，不构成安全边界。
- 涉及资源归属的接口（订单、老人档案、站内信），除角色校验外还必须校验**资源归属关系**。
- 角色鉴权统一使用 `@PreAuthorize("hasRole('FAMILY')")`（`hasRole` 会自动补 `ROLE_` 前缀）。
  单个方法需要限定时在注解里列出多个角色，如 `@PreAuthorize("hasAnyRole('FAMILY','ADMIN')")`。
- 「老人账号只读」**不靠 `@PreAuthorize` 表达**（它只能判断角色对不对，表达不了"这个角色只能读"）。
  由 `ElderReadOnlyInterceptor` 统一拦截：所有非 GET 请求对 ELDER 一律 403，
  仅显式标注 `@AllowElderWrite("原因")` 的接口放行（如登出、修改自己的密码）。
  > 这样新增接口默认是安全的，不会因为漏写一行角色限制就把老人账号变成可写。

### 2.4 老人账号（ELDER）只读规则

> **这是一条不可逾越的产品规则**：老人不做任何写操作，全部由家属代操作。

| 场景 | 允许角色 |
|---|---|
| 查询类接口（GET） | ELDER 可访问自己名下的数据 |
| 写操作（POST/PUT/DELETE） | **ELDER 一律拒绝（403）**，仅 FAMILY / COMPANION / ADMIN 按各自规则放行 |

实现要求：服务端拦截，不能只靠前端隐藏入口。

---

## 三、统一响应结构

### 3.1 成功

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { }
}
```

### 3.2 失败

```json
{
  "code": 3003,
  "message": "手慢了，该订单已被其他陪诊员接单",
  "data": null
}
```

> **重要**：除「未登录（HTTP 401）」「无权限（HTTP 403）」「路由不存在（HTTP 404）」等由 Spring Security / 容器直接返回的场景外，
> **业务错误一律返回 HTTP 200，通过 `code` 区分**。
> 这样做的好处是前端拦截器只需处理一层判断，避免业务错误与网络错误混在一起。

> ⚠️ 关于 401 / 403 的实现细节：这两个码是由服务端返回的**真实 HTTP 状态码**，且响应体仍是
> 统一结构 `{ code: 401|403, message, data }`。前端拦截器靠 HTTP 状态码决定
> 「去刷新令牌」还是「提示无权限」，靠 `message` 决定展示什么文案。
> 若把它们也做成 HTTP 200，前端就只能猜，容易漏判。

### 3.3 分页响应

`data` 结构固定为：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 137,
    "page": 1,
    "size": 10,
    "pages": 14,
    "records": [ ]
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `total` | Long | 总记录数 |
| `page` | Long | 当前页码，从 **1** 开始 |
| `size` | Long | 每页条数 |
| `pages` | Long | 总页数 |
| `records` | Array | 当前页数据，无数据时为 `[]`（不是 `null`） |

### 3.4 分页请求参数

所有列表接口统一支持以下 query 参数：

| 参数 | 类型 | 默认 | 约束 | 说明 |
|---|---|---|---|---|
| `page` | Integer | 1 | ≥ 1 | 页码 |
| `size` | Integer | 10 | 1–100 | 每页条数，**超过 100 会被截断到 100** |
| `sortField` | String | — | 白名单字段 | 排序字段（下划线命名，如 `create_time`） |
| `sortOrder` | String | desc | `asc` / `desc` | 排序方向 |

> `sortField` 只接受白名单字段，防止 SQL 注入与索引失效。白名单由各模块在 Service 层声明。

---

## 四、错误码总表

> 本表是后端 `org.company.nianglin.common.ResultCode` 枚举的镜像，**两处必须保持一致**。

### 4.1 通用（2xx / 4xx / 5xx）

| code | 含义 | 前端建议处理 |
|---|---|---|
| 200 | 成功 | 正常取 `data` |
| 400 | 请求参数错误 | 展示 `message`，定位到具体表单项 |
| 401 | 未登录或登录已失效 | 尝试刷新 token，失败则跳登录页 |
| 403 | 没有操作权限 | 提示无权操作，返回上一页 |
| 404 | 资源不存在 | 展示空状态 |
| 405 | 请求方法不支持 | 前端 bug，报错给开发 |
| 409 | 当前状态不允许该操作 | 展示 `message`，刷新数据 |
| 429 | 操作过于频繁 | 提示稍后重试 |
| 500 | 服务器内部错误 | 通用错误提示 + 联系管理员 |
| 501 | 功能开发中 | 骨架阶段占位接口会返回此码 |

### 4.2 1xxx 认证与账号（M2）

| code | 含义 |
|---|---|
| 1001 | 账号或密码错误 |
| 1002 | 账号已被封禁，请联系管理员 |
| 1003 | 验证码错误或已过期 |
| 1004 | 登录失败次数过多，请稍后再试 |
| 1005 | 登录状态已失效，请重新登录 |
| 1006 | 原密码不正确 |
| 1007 | 该手机号已被注册 |

### 4.3 2xxx 用户与档案（M3）

| code | 含义 |
|---|---|
| 2001 | 老人档案不存在 |
| 2002 | 该老人已被其他家属绑定（**预留槽位，当前不会返回**；绑定流程出于防手机号枚举统一回 `2001`，见 [02-elder-family.md §11](./02-elder-family.md)） |
| 2003 | 陪诊员资质尚未通过审核 |
| 2004 | 用户已被封禁 |
| 2005 | 绑定关系不存在 |
| 2006 | 无权操作该老人档案 |
| 2007 | 陪诊员不存在（含尚未通过审核） |

### 4.4 3xxx 陪诊订单（M4）

| code | 含义 |
|---|---|
| 3001 | 订单不存在 |
| 3002 | 当前订单状态不允许该操作 |
| 3003 | 手慢了，该订单已被其他陪诊员接单 |
| 3004 | 无权操作该订单 |
| 3005 | 就诊时间不能早于当前时间 |
| 3006 | 当前状态不允许取消订单 |
| 3007 | 服务小结不能包含诊断、处方或用药建议（**合规红线**，词表见 [03-order.md §9](./03-order.md)） |

### 4.5 4xxx 陪诊执行与打卡（M5）

| code | 含义 |
|---|---|
| 4001 | 未到达陪诊地点，打卡无效 |
| 4002 | 该节点已打卡，请勿重复提交 |
| 4003 | 您不是该订单的陪诊员 |

### 4.6 5xxx 用药管理（M6）

| code | 含义 |
|---|---|
| 5001 | 药品不存在 |
| 5002 | 用药计划时间区间不合法 |
| 5003 | 该服药任务已确认 |

### 4.7 6xxx 评价与投诉（M7）

| code | 含义 |
|---|---|
| 6001 | 订单尚未完成，不能评价 |
| 6002 | 该订单已评价 |
| 6003 | 内容包含敏感词，请修改后重试 |
| 6004 | 投诉记录不存在 |

### 4.8 7xxx 站内信（M8）

| code | 含义 |
|---|---|
| 7001 | 消息不存在 |
| 7002 | 无权查看该消息 |

### 4.9 8xxx 管理后台（M9）

| code | 含义 |
|---|---|
| 8001 | 资质审核状态不合法 |
| 8002 | 不能封禁管理员账号 |
| 8003 | 驳回时必须填写原因 |

### 4.10 9xxx 数据统计与导出（M10）

| code | 含义 |
|---|---|
| 9001 | 导出数据量超过上限，请缩小筛选范围 |
| 9002 | 统计时间区间不合法 |

---

## 五、角色与权限矩阵

| 接口分组 | ELDER | FAMILY | COMPANION | ADMIN |
|---|:--:|:--:|:--:|:--:|
| 认证 `/api/auth/**` | ○ | ○ | ○ | ○ |
| 个人资料 `/api/user/profile` | 读 | 读写 | 读写 | 读写 |
| 老人档案 `/api/user/elder/**` | 读（**仅自己档案的详情**，列表 403） | 读写（**仅绑定到自己名下的**） | — | 读（任意档案，**不可写**） |
| 陪诊员资质 `/api/user/companion/**` | 读（查自己的申请） | 读写（申请 / 查状态） | 读写 | 审核（M9） |
| 订单 `/api/order/**` | 读（自己） | 下单 / 取消 / 读 | 接单 / 流转 / 读 | 读 / 强制终态 |
| 陪诊执行 `/api/execution/**` | 读 | 读 | 打卡 / 上传 | 读 |
| 用药管理 `/api/medication/**` | 读 / 确认 | 读写 / 确认 | 确认 | 读 |
| 评价与投诉 `/api/review`、`/api/complaint` | 读 | 写 | 读 | 读 / 处理 |
| 站内信 `/api/message/**` | ○ | ○ | ○ | ○ |
| 管理后台 `/api/admin/**` | — | — | — | 全部 |
| 数据统计 `/api/statistics/**` | — | — | — | 全部 |

图例：○ = 全部权限；读 = 仅 GET；读写 = 全部；— = 无权限。

> **本表只表达「角色」这一层。** 加粗的限定语（如「仅绑定到自己名下的」、「须为本单陪诊员」）
> 是 `@PreAuthorize` **无法表达**的部分，由 Service 层的归属校验承担：
> 家属 A 与家属 B 角色完全相同，靠注解分不开，必须查 `family_elder_relation`
> 才知道档案归谁；陪诊员 A 与陪诊员 B 同理，必须比对 `companion_order.companion_id`。
> 详见 [02-elder-family.md §〇](./02-elder-family.md) 与 [03-order.md §〇](./03-order.md)。

### 权限测试要求（M12）

必须覆盖 **4 角色 × 3 类接口（只读 / 写 / 管理）= 12 条越权用例**，全部通过才算 M2 / M12 完成。

M2 已交付该测试并全部通过：

| 用例层 | 位置 | 覆盖 |
|---|---|---|
| 权限矩阵（12 条 + 4 条认证补充） | `backend/src/test/java/.../security/PermissionMatrixTest.java` | 用**真实签发的 JWT** 走完整过滤器链，而非 `@WithMockUser` |
| 老人只读规则 | `.../security/ElderReadOnlyInterceptorTest.java` | GET 放行 / POST·PUT·DELETE 拦截 / `@AllowElderWrite` 放行 |
| 端到端 | `backend/sql/tools/e2e_auth.py` | 真实 HTTP 打 8080，40 项断言 |

M3 补齐了**角色矩阵管不到的另一半** —— 同一角色内部的数据归属：

| 用例层 | 位置 | 覆盖 |
|---|---|---|
| 归属校验矩阵 | `.../security/ElderOwnershipMatrixTest.java` | 38 条：越权 2006 / 角色 403 / 老人只读 403 / 订单闸门 409 / 脱敏出口 / 陪诊员 2007 / 绑定 2001·501 |
| 时间格式 | `.../config/JacksonDateTimeFormatTest.java` | 7 条：`LocalDateTime` 必须是 `yyyy-MM-dd HH:mm:ss` |
| 端到端 | `backend/sql/tools/e2e_user_profile.py` | 真实 HTTP 打 8080，**86 项断言**，脚本自带数据清理 |

M4 继续追加**订单状态机与相关方边界**：

| 用例层 | 位置 | 覆盖 |
|---|---|---|
| 订单归属与状态机矩阵 | `.../security/OrderAccessMatrixTest.java` | 51 条：角色门槛 403 / 资质双层拦截 2003 / 相关方 3004 / 跳级 3002 / 非本单陪诊员 4003 / 老人只读 403 / 三套脱敏口径 / 参数校验 |
| 订单服务单测 | `.../service/OrderServiceTest.java` | 48 条：费用规则、订单号生成、状态机流转、合规词表 |
| 端到端 | `backend/sql/tools/e2e_order.py` | 真实 HTTP 打 8080，**78 项断言**（含 50 线程并发抢单），脚本自带清理 |

> `mvn test` 当前 **243/243 全绿**。跑端到端前先按 `application-dev.yml` 起好
> MySQL 3306 与 Redis 6379，再把后端起在 **8080**。

### 测试约定（踩过坑，务必遵守）

1. **令牌必须用「实时密码版本」签发**，不要写死 `ver=0`。
   登出 / 改密会把 `pwd:version:{userId}` 递增且**永不过期**；一旦 `e2e_auth.py` 的登出用例跑过，
   任何硬编码 `ver=0` 的用例都会拿到 **401（验票失败）而不是预期的 403**，现象极具迷惑性。
   统一走 `test/support/TestTokens.java`。
2. **纯 Mockito 单测须预热 MyBatis-Plus 的 lambda 缓存**（`test/support/MybatisLambdaCache.warmUp()`），
   否则 `LambdaUpdateWrapper.set(...)` 会抛 `can not find lambda cache for this entity`。
   这个问题只在「单独跑某一个纯单测类」时暴露，混在全量里跑会因为前面已有 `@SpringBootTest`
   建好缓存而**假绿**。判定方式：`mvn test -Dtest=A,B,C -DreuseForks=false -DforkCount=1`。
3. **端到端脚本必须清理它碰过的共享状态**（MySQL 行 + Redis 键）。
   MySQL 与 Redis 由端到端和集成测试共用，残留会让另一侧的用例无辜变红。

> M2 阶段业务接口尚未落地，因此提供了一组**只有角色门槛、没有业务逻辑**的探针接口
> `/api/common/perm-probe/**` 作为测试靶子；各业务模块完成后该控制器可整体删除。

---

## 六、核心业务规则

### 6.1 陪诊订单状态机（最重要）

```
待接单 PENDING ──► 已接单 ACCEPTED ──► 服务中 IN_SERVICE ──► 已完成 COMPLETED ──► 已评价 REVIEWED
     │                  │                   │
     └──────────────────┴───────────────────┴──► 已取消 CANCELLED（仅管理员纠纷处理可进入）
```

**铁律：**

1. **禁止跳级**：`待接单` 不能直接变 `已完成`（返回 3002）。
2. **禁止回退**：`已接单` 不能退回 `待接单`（返回 3002）。
3. **终态不可再流转**：`已评价` / `已取消` 之后无法再变更（管理员强制终态除外）。
4. **仅管理员可强制改变终态**，且必须写 `admin_oper_log` 并通知双方。
5. 每次状态变更写入状态流转日志，异常时整体回滚。

状态取值（JSON 中传字符串枚举名）：

| 接口传值 | 中文 | 排序 |
|---|---|---|
| `PENDING` | 待接单 | 1 |
| `ACCEPTED` | 已接单 | 2 |
| `IN_SERVICE` | 服务中 | 3 |
| `COMPLETED` | 已完成 | 4 |
| `REVIEWED` | 已评价 | 5 |
| `CANCELLED` | 已取消 | 9 |

### 6.2 打卡节点

| 传值 | 中文 | 说明 |
|---|---|---|
| `DEPART` | 出发 | 陪诊员前往医院 |
| `ARRIVE` | 到院 | 到达医院 |
| `IN_CONSULT` | 就诊中 | 陪同就诊 |
| `TAKE_MEDICINE` | 取药 | 取药 / 缴费 |
| `LEAVE` | 离院 | 离开医院 |
| `FINISH` | 完成 | 送达并交接完成 |

约束：节点顺序不可逆（可跳过，不可回退）；同一订单同一节点只能打一次（4002）；坐标偏离订单地址超过阈值判为无效（4001）。

### 6.3 合规红线（所有接口共同遵守）

1. **不做诊断、不开药方**：药品字典只返回通用信息（名称、规格、通用说明），必须携带免责声明字段；接口文档与文案中禁止出现「建议服用」「推荐剂量」等表述。
2. **隐私最小化**：接口只返回必要字段。手机号返回脱敏串（`138****8888`）；身份证号返回脱敏串（`110101********4567`）；密码任何情况下不返回。
3. **日志脱敏**：服务端日志禁止打印身份证号、密码、完整手机号、完整地址门牌号。
4. **一期不做**：在线支付（改线上记账 + 线下结算）、地图导航（改文字地址 + 签到坐标）、IM 聊天（改站内信）。

---

## 七、字段命名与数据约定

| 约定项 | 规则 | 示例 |
|---|---|---|
| 路径命名 | 全部小写，多单词用连字符；资源名用复数不用动词 | `/api/order/hall`、`/api/medication/plan` |
| 字段命名 | JSON 用 **小驼峰**，数据库用下划线 | `createTime` ↔ `create_time` |
| 枚举 | 传字符串大写枚举名，同时返回 `xxxLabel` 中文 | `"status": "PENDING"`, `"statusLabel": "待接单"` |
| 布尔字段 | 用 `is` / `has` 前缀 | `isAnonymous`、`hasComplaint` |
| 空值 | `null` 字段可省略（Jackson `non_null` 策略） | — |
| ID 传参 | 路径变量用 `{id}`，查询用 query，批量用 body 数组 | `/api/order/1001` |
| 日期区间查询 | `startDate` / `endDate`，闭区间 | `?startDate=2026-09-01&endDate=2026-09-30` |

### 通用分页查询字段命名

| 场景 | 参数名 |
|---|---|
| 按时间范围 | `startTime` / `endTime`（精确到秒）或 `startDate` / `endDate`（精确到天） |
| 按状态 | `status`（枚举名） |
| 按关键字 | `keyword`（模糊匹配姓名 / 手机号 / 订单号） |
| 按用户 | `elderId`、`familyId`、`companionId` |

---

## 八、通用接口

### 8.1 健康检查

`GET /api/health`　权限：**公开**

**响应**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "application": "nianglin",
    "profiles": "dev",
    "javaVersion": "21.0.7",
    "serverTime": "2026-09-15 16:40:00",
    "status": "UP"
  }
}
```

**用途**：前端探活、健康检查（~~部署脚本健康检查、`docker-compose` healthcheck~~ — ⏸️ 现阶段不做部署上线）。

---

## 九、文件上传约定

| 项目 | 约定 |
|---|---|
| 请求类型 | `multipart/form-data` |
| 字段名 | 统一 `file` |
| 单文件上限 | 10 MB |
| 单请求上限 | 20 MB |
| 允许类型 | `image/jpeg`、`image/png`、`image/webp`、`application/pdf` |
| 返回 | `{ "fileId": "...", "url": "/uploads/xxx.jpg", "size": 102400 }` |
| 存储 | 一期落本地磁盘 `./uploads/{yyyyMM}/{uuid}.{ext}`，路径写入数据库；~~M13~~ 二期可替换为对象存储 |

⚠️ 上传接口必须校验文件类型与大小，禁止信任前端传来的 `Content-Type`，需读取文件头（magic bytes）二次确认。

---

## 十、变更记录

| 版本 | 日期 | 变更内容 | 变更人 |
|---|---|---|---|
| v0.1.0 | 2026-09-15 | 骨架阶段初版：全局约定、错误码、10 份模块文档 | — |
| v0.2.0 | 2026-09-15 | M2（认证与多角色鉴权）、M3（用户与档案）落地：新增错误码 `2007`；权限矩阵补充「归属校验」层说明与实测结果；明确时间格式由 `JacksonConfig` 强制 | — |
| v0.3.0 | 2026-09-15 | M4（陪诊订单与状态机）落地：新增错误码 `3007`；权限矩阵细化订单行并标注「须为本单下单人 / 陪诊员」；补充 M4 测试落点、并发接单实测结论、纯单测 lambda 缓存预热约定 | — |
| v0.3.1 | 2026-09-15 | 修正 `2002` 说明（绑定冲突统一回 `2001` 防枚举，`2002` 为预留槽位）；补全 Token 载荷字段（`jti` / `ver`）与**登出会递增密码版本**这一行为；新增三条测试约定（实时密码版本 / lambda 缓存预热 / 端到端自清理） | — |
