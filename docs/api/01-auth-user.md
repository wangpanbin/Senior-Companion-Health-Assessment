# 01 认证与账号

> 覆盖模块：**M2 认证与多角色鉴权**
> 负责人：B（后端主力）
> 归属迭代：迭代一末–迭代二初（W3–W4）
> 全局约定见 [README.md](./README.md)

---

## 一、数据模型

### UserInfoVO（登录返回的用户信息）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 用户 ID |
| `username` | String | 用户名 |
| `nickname` | String | 昵称 |
| `role` | String | 角色枚举名：`ELDER` / `FAMILY` / `COMPANION` / `ADMIN` |
| `roleLabel` | String | 角色中文名 |
| `phone` | String | **脱敏**手机号，如 `138****8888` |
| `avatar` | String | 头像 URL，可为空 |
| `status` | String | 账号状态：`NORMAL` / `DISABLED` |
| `createTime` | String | 注册时间，`yyyy-MM-dd HH:mm:ss` |

> ⚠️ `UserInfoVO` 不包含密码、身份证号、完整手机号。
> 由 `UserInfoVO.of(SysUser)` 工厂方法统一转换 —— 手机号脱敏焊死在里面，
> 调用方不可能"忘记调 MaskUtil"。

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | GET | `/api/auth/captcha` | 公开 | 获取图形验证码 |
| 2 | POST | `/api/auth/register` | 公开 | 注册 |
| 3 | POST | `/api/auth/login` | 公开 | 登录 |
| 4 | POST | `/api/auth/refresh` | 公开 | 刷新 accessToken |
| 5 | POST | `/api/auth/logout` | 已登录 | 登出 |
| 6 | GET | `/api/auth/me` | 已登录 | 获取当前用户信息 |
| 7 | PUT | `/api/auth/password` | 已登录 | 修改密码 |
| 8 | POST | `/api/user/deactivate` | FAMILY / COMPANION | 注销账号（V4 · P0-1） |
| 9 | GET | `/api/user/data-export` | FAMILY / COMPANION | 导出我的个人数据（V4 · P0-1） |

---

## 1. 获取图形验证码

`GET /api/auth/captcha`　权限：**公开**

### 请求参数

无。

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "captchaKey": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "captchaImage": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg...",
    "expiresIn": 300
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `captchaKey` | String | 验证码标识，登录时原样回传 |
| `captchaImage` | String | base64 图片，前端直接放 `<img :src>` |
| `expiresIn` | Integer | 有效期（秒），默认 300 |

### 实现要点

- 验证码明文存 Redis：`captcha:{captchaKey}` → 验证码文本，TTL 300 秒。
- **验证成功后必须立即删除**，防止同一验证码重放。
- 校验失败统一返回 `1003`，不区分「过期」与「错误」。

---

## 2. 注册

`POST /api/auth/register`　权限：**公开**

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `username` | String | ✓ | 4–20 位，字母数字下划线 | 用户名 |
| `phone` | String | ✓ | 11 位手机号 | 手机号，唯一 |
| `password` | String | ✓ | 6–32 位，需同时包含字母与数字 | 密码，**BCrypt 加密后入库** |
| `nickname` | String | ✓ | 2–20 字符 | 昵称（建议用「张大爷」「李阿姨」这类称呼） |
| `role` | String | ✓ | `ELDER` / `FAMILY` / `COMPANION` | 注册角色，**不允许注册为 ADMIN** |
| `captchaKey` | String | ✓ | — | 验证码标识 |
| `captchaCode` | String | ✓ | 4–6 位 | 验证码 |

```json
{
  "username": "zhangsan",
  "phone": "13812348888",
  "password": "abc123456",
  "nickname": "张三",
  "role": "FAMILY",
  "captchaKey": "a1b2c3d4-...",
  "captchaCode": "8f3k"
}
```

### 响应

```json
{
  "code": 200,
  "message": "注册成功",
  "data": { "userId": 10023 }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 400 | 参数校验失败（提示具体字段） |
| 1003 | 验证码错误或已过期 |
| 1007 | 该手机号已被注册 |
| 400 | `role` 传了 `ADMIN` |

### 实现要点

- `ADMIN` 角色只能由数据库初始化脚本创建，注册接口必须显式拒绝。
- 密码使用 `BCryptPasswordEncoder.encode()`，**禁止任何形式的可逆加密或明文**。
- 数据库中 `password` 字段值必须以 `$2a$` 开头（这是 M2 的验收检查项）。

---

## 3. 登录

`POST /api/auth/login`　权限：**公开**

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | String | ✓ | 手机号或用户名 |
| `password` | String | ✓ | 密码 |
| `captchaKey` | String | ✓ | 验证码标识 |
| `captchaCode` | String | ✓ | 验证码 |

### 响应

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 7200,
    "tokenType": "Bearer",
    "userInfo": {
      "id": 10023,
      "username": "zhangsan",
      "nickname": "张三",
      "role": "FAMILY",
      "roleLabel": "家属",
      "phone": "138****8888",
      "status": "NORMAL"
    }
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 1001 | 账号或密码错误 |
| 1002 | 账号已被封禁 |
| 1003 | 验证码错误或已过期 |
| 1004 | 登录失败次数过多，请 15 分钟后再试 |

### 实现要点

- **账号不存在与密码错误返回同一个提示**（`1001`），防止账号枚举。
- 失败计数存 Redis：`login:fail:{username}`，达到 5 次锁定 15 分钟。
- 登录成功后清除失败计数。
- 登录成功后写入登录日志（IP、时间），**不记录密码**。
- 封禁用户即使密码正确也拒绝（`1002`），且后续所有请求立即失效。

---

## 4. 刷新 accessToken

`POST /api/auth/refresh`　权限：**公开**（凭 refreshToken）

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `refreshToken` | String | ✓ | 登录时下发的 refreshToken |

### 响应

```json
{
  "code": 200,
  "message": "刷新成功",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 7200
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 1005 | refreshToken 已失效，请重新登录 |
| 1002 | 用户已被封禁 |

### 实现要点

- refreshToken 校验通过后，旧 accessToken 加入 Redis 黑名单（TTL = 原剩余有效期）。
- 前端拦截器收到 401 时自动调用本接口并**重放原请求**，用户无感知。
- 本接口自身不能触发刷新（避免死循环）：前端对 `/auth/refresh` 的 401 直接登出。

---

## 5. 登出

`POST /api/auth/logout`　权限：已登录

### 请求体（可选）

访问令牌通过 `Authorization` 请求头传递，**不放在 body 里**。body 只承载可选的 refreshToken：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `refreshToken` | String | — | 传入则该令牌一并作废；不传则只作废访问令牌 |

```json
{ "refreshToken": "eyJhbGciOiJIUzI1NiJ9..." }
```

> 该字段刻意**不做必填校验**：登出是用户的退路，不能因为少传一个附属令牌就让用户退不出去。

### 响应

```json
{ "code": 200, "message": "已退出登录", "data": null }
```

### 实现要点

- 当前 accessToken 加入 Redis 黑名单：`token:blacklist:{jti}` → `1`，TTL = 剩余有效期
  （过期后 Redis 自动清理，黑名单不会无限增长）。
- 请求体里带了 refreshToken 则一并拉黑；带了但已失效则忽略，**登出仍然成功**。
- ⚠️ 老人账号（ELDER）调用本接口不被只读规则拦截
  —— 该接口标注了 `@AllowElderWrite("登出必须由本人完成")`。
  若不豁免，老人账号登录后将无法退出。
- 前端清理 localStorage（token / refreshToken / userInfo）。

### 多设备并发登录的语义

采用**按 jti 拉黑**（而非按用户全员失效）：

- 同一账号在手机 / 网页 / 桌面端并发登录，各端持有不同 jti 的 accessToken。
- 一台设备点「退出登录」，只把当前请求的 accessToken / refreshToken 的 jti 拉黑；
  其它设备的 jti 不在黑名单里，**保持在线**，符合用户预期。
- 如果用户主动「修改密码」或管理员「封禁账号」，该走
  {@link org.company.nianglin.security.TokenStore#bumpPasswordVersion(Long)}，
  那才是「我就是要让这个人的全部会话立刻退出」的强动作——登出<b>不</b>走这条路径。

> 设计取舍参考：`docs/reports/e2e-report.md §F-01`（入库快照；原文件生成于 `reports/playwright/`，该目录被 .gitignore 忽略）。
> 早期实现这里也调 `bumpPasswordVersion`，会出现「同一账号连登两次得 T1/T2，
> 用 T1 登出后 T2 也返 401」的歧义行为，在 e2e 套件里甚至引发令牌互相污染。

---

## 6. 获取当前用户信息

`GET /api/auth/me`　权限：已登录（所有角色）

### 请求参数

无（从 token 解析用户 ID）。

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 10023,
    "username": "zhangsan",
    "nickname": "张三",
    "role": "FAMILY",
    "roleLabel": "家属",
    "phone": "138****8888",
    "avatar": null,
    "status": "NORMAL",
    "createTime": "2026-09-01 10:20:30"
  }
}
```

### 用途

前端刷新页面后恢复登录态（`router.beforeEach` 中发现 Pinia 无 userInfo 时调用）。

---

## 7. 修改密码

`PUT /api/auth/password`　权限：已登录

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `oldPassword` | String | ✓ | 6–32 位 | 原密码 |
| `newPassword` | String | ✓ | 6–32 位，需含字母与数字 | 新密码 |
| `confirmPassword` | String | ✓ | 与 `newPassword` 一致 | 确认新密码 |

### 响应

```json
{ "code": 200, "message": "密码修改成功，请重新登录", "data": null }
```

### 错误场景

| code | 场景 |
|---|---|
| 1006 | 原密码不正确 |
| 400 | 两次输入的新密码不一致 |
| 400 | 新密码与原密码相同 |

### 实现要点

- 修改成功后**强制所有已签发 token 失效**（Redis 记录 `password:version:{userId}`，token 中带版本号比对），前端跳登录页。
- 新密码同样 BCrypt 加密。

---

## 8. 注销账号（V4 · P0-1）

`POST /api/user/deactivate`　权限：**FAMILY / COMPANION**

> - ELDER：被 `ElderReadOnlyInterceptor` 拦截（403，「老人账号为只读模式」），与请求体是否合法无关。
> - ADMIN：被 `@PreAuthorize` 拒绝（403）—— 管理员停用账号走 `POST /api/admin/user/{id}/disable` 后台封禁路径（08-admin.md §7），不走自助注销。
> - 契约草案见 `docs/plan/iteration-plan-v4.md` §6.1。

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `password` | String | ✓ | 当前密码（复验，防他人拿着未锁屏设备操作） |
| `confirmText` | String | ✓ | 二次确认文本，必须**原样**输入 `我已知晓注销后果`（前端弹窗要求手打，后端校验固定串防误触） |

```json
{
  "password": "abc123456",
  "confirmText": "我已知晓注销后果"
}
```

### 响应

```json
{ "code": 200, "message": "账号已注销", "data": null }
```

### 错误场景

| code | 场景 |
|---|---|
| 400 | 参数校验失败（缺密码 / 缺确认文本）；`confirmText` 与固定串不匹配 |
| 1006 | 原密码不正确 |
| 11001 | 存在进行中订单（`PENDING` / `ACCEPTED` / `IN_SERVICE`，作为下单家属**或**接单陪诊员均计入）；`data` 返回 `{ "pendingOrderCount": 2 }` |
| 401 | 未登录 |
| 403 | ELDER（只读拦截器）/ ADMIN 调用 |

### 副作用（成功路径，同一事务）

1. `sys_user.deleted = 1`、`status = 'DISABLED'`，`remark` 记注销时间；
2. **去标识化**（订单 / 评价 / 日志物理保留，只把可识别字段抹掉）：
   - `sys_user.real_name` / `nickname` → `MaskUtil.nameAll`（只留姓氏，空值保持空）；
   - `sys_user.phone` → 墓碑占位串 `"d" + userId`（如 `d10023`）。
     ⚠️ **刻意偏离 `MaskUtil.phone`**：`uk_phone` 唯一约束仍在，而脱敏输出只有 11 位
     （`138****8888`），两个不同原号可能撞同一占位串，第二次注销会被唯一索引拒掉。
     `"d" + userId` ≤ 20 字符、必然唯一、且永远不可能与真实手机号（11 位数字）冲突；
   - 陪诊员：`companion_profile.real_name` → `MaskUtil.nameAll`、`phone` → `MaskUtil.phone`
     （该表 phone 无唯一约束）、`id_card` → **置 NULL**（AES 密文脱敏无意义，真去标识化即清除）；
   - 家属：`family_elder_relation` 绑定行**逻辑删除**（`deleted = 1`），
     老人档案 `elder_profile` 保留（账号注销不影响被照护人的档案存续）；
3. `pwd:version:{userId}` 递增（`TokenStore#bumpPasswordVersion`）——
   该账号**全部已签发令牌立即失效**，再次请求返回 **401**。
   ⚠️ 注销**不**调 `markBanned`：那是「封禁」语义（过滤器就地 403 + 封禁文案），
   注销要的是「未登录」语义（401），与「账号已不存在」的产品事实一致；
4. **不写** `admin_oper_log`：注销是用户自助行为，不是管理员操作；
5. 订单、评价、投诉、站内信、日志**物理保留**（合规审计需要），
   但其中显示名经 `UserNameResolver` 读到的已是去标识化后的值。

### 实现要点

- 校验顺序：参数校验 → `confirmText` 固定串 → 密码复验 → 进行中订单闸门 → 落库。
  密码复验用 `passwordEncoder.matches`，错误统一 `1006`，与修改密码同一口径。
- 注销成功后该账号无法登录（逻辑删除后按手机号 / 用户名均查不到，`1001`）。
- 前端成功后必须清空本地登录态并跳登录页。

---

## 9. 导出我的个人数据（V4 · P0-1）

`GET /api/user/data-export`　权限：**FAMILY / COMPANION**（仅本人；`userId` 从令牌取，**禁止入参指定**）

> GET 请求不触发老人只读拦截器，因此 ELDER / ADMIN 的排除由 `@PreAuthorize` 显式承担（403）。
> 契约草案见 `docs/plan/iteration-plan-v4.md` §6.2。

### 请求参数

无。

### 响应

`application/octet-stream`（xlsx 二进制流，EasyExcel 多 sheet 写出，**不走统一响应结构**）。

| sheet | 内容 | 口径 |
|---|---|---|
| 个人资料 | 用户 ID / 用户名 / 昵称 / 真实姓名 / 手机号 / 角色 / 账号状态 / 注册时间 | 姓名 `MaskUtil.name`、手机号 `MaskUtil.phone` |
| 绑定老人 | 老人档案 ID / 姓名 / 与老人关系 / 绑定方式 / 关系状态 / 绑定时间 | 仅 FAMILY 有数据；老人姓名 `MaskUtil.name` |
| 订单记录 | 订单号 / 我的角色 / 医院 / 科室 / 就诊时间 / 订单状态 / 服务费 / 结算状态 / 下单时间 | 作为下单家属或接单陪诊员的全部订单 |
| 评价记录 | 订单号 / 我的角色 / 评分 / 评价内容 / 是否匿名 / 评价时间 | 我发出的（家属）或评价我的（陪诊员） |
| 消息记录 | 标题 / 消息类型 / 正文 / 是否已读 / 接收时间 | 我的站内信收件箱（正文发信时已由后端模板脱敏） |

### 错误场景

| code | 场景 |
|---|---|
| 401 | 未登录 |
| 403 | ELDER / ADMIN 调用 |

### 实现要点（合规红线）

- 导出文件**不含**身份证号、完整手机号、密码哈希（红线-2）——
  与 `UserExportVO` 同一纪律：`password` 类字段**根本不定义**在导出行里；
- 所有姓名 / 手机号在装配行对象时过 `MaskUtil`，**先脱敏再写入**，不依赖事后扫描；
- 文件名带时间戳（`个人数据_yyyyMMdd_HHmmss.xlsx`），Content-Disposition 同时给
  `filename`（ASCII 回退）与 `filename*`（RFC 5987）。

---

## 三、验收标准（M2）

- [x] 不带 token 访问受保护接口 → `401`
- [x] 带 FAMILY token 访问 ADMIN 接口 → `403`
- [x] 老人账号（ELDER）调用任意写接口 → `403`（服务端拦截，绕过前端也无效）
- [x] token 过期后前端自动刷新并重放原请求，页面不跳登录页
- [x] 数据库 `sys_user.password` 值以 `$2a$` 开头
- [x] 响应体手机号已脱敏；日志中 grep 不到完整身份证号
- [x] 权限测试覆盖 4 角色 × 3 类接口 = 12 条用例，全部通过
- [x] 同一验证码连续提交两次，第二次返回 `1003`
- [x] 密码连续输错 5 次后，第 6 次返回 `1004`

### 实测记录（2026-09-15）

| 层面 | 手段 | 结果 |
|---|---|---|
| 单元测试 | `AuthServiceTest`（Mockito，16 例） | 16/16 通过 |
| 权限矩阵 | `UserAccessMatrixTest`（SpringBootTest + 真实 JWT，12 例；原探针矩阵 `PermissionMatrixTest` 已于 T2.3 退场，由本类与各模块 `*AccessMatrixTest` 承接） | 12/12 通过 |
| 老人只读 | `ElderReadOnlyInterceptorTest`（7 例） | 7/7 通过 |
| 端到端 | `backend/sql/tools/e2e_auth.py` 真实 HTTP 打 8080，40 项断言 | 40/40 通过 |

端到端脚本覆盖的错误码：`1001` / `1002` / `1003` / `1004` / `1005` / `1006` / `1007` / `400` / `401` / `403`，并校验了验证码防重放、登出黑名单即时生效、改密后旧令牌立即失效、登录日志不含密码串。

> 📌 关于 HTTP 状态码：`401` / `403` 返回**真实 HTTP 状态码**（而非"业务错误一律 200"），
> 因为前端拦截器要靠状态码决定「去刷新令牌」还是「提示无权限」。其余业务错误仍为 HTTP 200。
