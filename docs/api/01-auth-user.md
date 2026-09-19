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
| `password` | String | ✓ | 6–32 位，需含字母与数字 | 密码，**BCrypt 加密后入库** |
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

> 设计取舍参考：`docs/agents/reports/e2e-report.md §F-01`（入库快照；原文件生成于 `reports/playwright/`，该目录被 .gitignore 忽略）。
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
| 权限矩阵 | `PermissionMatrixTest`（SpringBootTest + 真实 JWT，16 例） | 16/16 通过 |
| 老人只读 | `ElderReadOnlyInterceptorTest`（7 例） | 7/7 通过 |
| 端到端 | `backend/sql/tools/e2e_auth.py` 真实 HTTP 打 8080，40 项断言 | 40/40 通过 |

端到端脚本覆盖的错误码：`1001` / `1002` / `1003` / `1004` / `1005` / `1006` / `1007` / `400` / `401` / `403`，并校验了验证码防重放、登出黑名单即时生效、改密后旧令牌立即失效、登录日志不含密码串。

> 📌 关于 HTTP 状态码：`401` / `403` 返回**真实 HTTP 状态码**（而非"业务错误一律 200"），
> 因为前端拦截器要靠状态码决定「去刷新令牌」还是「提示无权限」。其余业务错误仍为 HTTP 200。
