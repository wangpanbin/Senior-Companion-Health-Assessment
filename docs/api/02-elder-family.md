# 02 用户与档案

> 覆盖模块：**M3 用户与档案管理**
> 负责人：B（后端）+ D（数据库）
> 归属迭代：W2–W5 开发，W13 补资质审核
> 实现状态：**后端已交付并通过实测**（144 条单元/集成测试 + 86 项端到端断言全绿）
> 全局约定见 [README.md](./README.md)

---

## 〇、本模块的安全边界：角色管不到的那一层

M2 解决的是「**你是什么角色**」，本模块真正要解决的是「**这份数据是不是你的**」。

家属 A 和家属 B 的角色完全相同，都是 `FAMILY`，`@PreAuthorize("hasRole('FAMILY')")`
对两人一视同仁。如果只加角色注解，家属 A 拿自己的令牌请求 `/api/user/elder/402`
（家属 B 的老人）就会被放行 —— 这是一个**角色注解在原理上无法表达**的漏洞。

因此本模块的安全链条是三段式，缺一段都不成立：

| 层 | 承担者 | 回答的问题 | 失败返回 |
|---|---|---|---|
| 认证 | `JwtAuthenticationFilter` | 有没有身份 | HTTP 401 |
| 角色 | `@PreAuthorize` + `SecurityConfig` | 是不是家属 / 陪诊员 / 管理员 | HTTP 403 |
| **归属** | **`ElderService.requireAccessible` / `requireOwnedByFamily`** | **这份档案是不是你的** | **2006** |

**准入规则（写死在 Service，不看注解）：**

| 角色 | 读档案 | 写档案 |
|---|---|---|
| FAMILY | 仅**有效绑定**（`status=BOUND`）到自己名下的 | 仅**有效绑定**到自己名下的 |
| ELDER | 仅 `user_id` 等于自己用户 ID 的那条 | 一律拒绝 |
| ADMIN | **任意档案**（纠纷处理需要） | **不通过本模块接口写**（走 M9 后台并留 `admin_oper_log`） |
| COMPANION | 拒绝（403） | 拒绝（403） |

> ⚠️ 写操作的归属校验**特意不给 ADMIN 开后门**。管理员要改档案走管理后台，
> 那里有操作日志；让管理员借用家属接口改数据，等于把「谁改的」这条审计线索抹掉。

---

## 一、数据模型

### 1.1 ElderVO（老人档案）

**同一个 VO，列表与详情是两套口径**（`ElderVO.ofList` / `ElderVO.ofDetail`）：

| 字段 | 类型 | 列表 | 详情 | 说明 |
|---|:--:|:--:|:--:|---|
| `id` | Long | ✓ | ✓ | 档案 ID |
| `name` | String | `张*海` | **全名** `张德海` | 列表脱敏，详情给全名 |
| `gender` | String | ✓ | ✓ | `MALE` / `FEMALE` |
| `genderLabel` | String | ✓ | ✓ | 中文，如「男」 |
| `age` | Integer | ✓ | ✓ | **由出生日期实时计算，不落库**（避免跨年失效） |
| `birthDate` | String | ✓ | ✓ | `yyyy-MM-dd` |
| `idCard` | String | ✗ | ✓ | 脱敏串 `460101********0011`，**永不返回密文或明文** |
| `phone` | String | `139****0001` | 同左 | 脱敏 |
| `address` | String | ✗ | ✓ | 门牌号打码为 `***` |
| `emergencyContact` | String | ✓ | ✓ | 紧急联系人姓名 |
| `emergencyPhone` | String | ✗ | ✓ | 脱敏 |
| `medicalHistory` | String | ✗ | ✓ | 病史备注（**仅记录，不做诊断、不给用药建议**） |
| `allergyHistory` | String | ✗ | ✓ | 过敏史备注（仅记录） |
| `mobilityLevel` | String | ✗ | ✓ | `SELF` / `ASSIST` / `WHEELCHAIR` |
| `mobilityLevelLabel` | String | ✗ | ✓ | 中文，如「需搀扶」 |
| `favoriteHospital` | String | ✓ | ✓ | 常去医院 |
| `bindStatus` | String | ✓ | ✓ | `BOUND` / `UNBOUND` |
| `bindStatusLabel` | String | ✓ | ✓ | 中文，如「已绑定」 |
| `relation` | String | ✗ | ✓ | **当前登录家属**与该老人的关系中文，如「儿子」 |
| `createTime` | String | ✓ | ✓ | `yyyy-MM-dd HH:mm:ss` |

> **为什么列表和详情口径必须不一样**：列表页一次展示十几个老人，姓名被旁边人扫到是常态；
> 而详情页是家属主动点开自己家人的档案，「张*海」和「张*河」分不清会让页面直接没法用。
> 所以不是「越严越好」，而是**按场景给到刚刚够用的信息**。
>
> **未赋值的字段不是返回 `null`，而是整个消失**（全局 Jackson `non_null` 策略）。
> 前端按「字段不存在」判断即可，不需要区分「没权限看」和「本来就没填」。

### 1.2 CompanionProfileVO（陪诊员资料）

| 字段 | 类型 | 公开资料是否返回 | 说明 |
|---|:--:|:--:|---|
| `id` | Long | ✓ | **陪诊员用户 ID**（见下方警告） |
| `realName` | String | ✓ | **脱敏**，如「李*军」 |
| `serviceArea` | String | ✓ | 服务区域 |
| `availableTime` | String | ✓ | 可服务时段 |
| `auditStatus` | String | ✓ | 仅 `APPROVED` 的资料才会被返回 |
| `auditStatusLabel` | String | ✓ | 中文 |
| `workStatus` | String | ✓ | `AVAILABLE` / `REST` |
| `workStatusLabel` | String | ✓ | 中文，如「可接单」 |
| `score` | String | ✓ | **两位小数字符串**（`"4.00"`），避免 JS 浮点误差；无评分为 `null` 而非 `"0.00"` |
| `orderCount` | Integer | ✓ | 累计已完成订单数 |
| `idCard` | String | ✗ | 不返回 |
| `phone` | String | ✗ | 不返回 |
| `certificates` | Array | ✗ | 不返回（仅审核流程内部使用） |
| `rejectReason` | String | ✗ | 不返回（见 §5） |

> ⚠️ **`id` 是「用户 ID」，不是 `companion_profile` 表的主键。**
> 库里实测：`companion_profile.id` 是 601~630，`user_id` 是 301~330，两者不相等。
> 业务上真正被引用的键是 **用户 ID** —— `companion_order.companion_id`、评价、站内信
> 全部指向 `sys_user.id`。所以公开资料接口取的是 `userId`；
> 若用主键（601）当参数查，会得到 `2007`（这正是期望的失败方式）。

### 1.3 CompanionApplicationVO（我的申请状态）

| 字段 | 类型 | 说明 |
|---|---|---|
| `applicationId` | Long | `companion_audit_record.id` |
| `auditStatus` | String | `PENDING` / `APPROVED` / `REJECTED` |
| `auditStatusLabel` | String | 中文 |
| `rejectReason` | String | **仅 `REJECTED` 时返回**；其余状态即使库里有残留原因也不下发 |
| `submitTime` | String | `yyyy-MM-dd HH:mm:ss` |
| `auditTime` | String | 未审核时为 `null`（字段消失） |

### 1.4 枚举取值总表

| 枚举 | 取值 | 中文 |
|---|---|---|
| 性别 | `MALE` / `FEMALE` | 男 / 女 |
| 绑定状态 | `BOUND` / `UNBOUND` | 已绑定 / 未绑定 |
| 关系 | `SON` / `DAUGHTER` / `RELATIVE` / `OTHER` | 儿子 / 女儿 / 亲属 / 其他 |
| 绑定方式 | `PHONE` / `INVITE_CODE` / `CREATE` | 手机号 / 邀请码 / 家属代建档 |
| 行动能力 | `SELF` / `ASSIST` / `WHEELCHAIR` | 自理 / 需搀扶 / 轮椅 |
| 审核状态 | `PENDING` / `APPROVED` / `REJECTED` | 待审核 / 已通过 / 已驳回 |
| 接单状态 | `AVAILABLE` / `REST` | 可接单 / 休息中 |

> **枚举一律只接受大写枚举名**，传 `"male"` 返回 `400`（`normalizeEnum` 不做大小写宽容）。
> `CREATE` 只出现在数据库列里，**不能作为入参** —— 它由「家属代建档」流程内部写入。

---

## 二、接口列表

| # | 方法 | 路径 | 权限（除角色外还要过归属校验） | 说明 |
|---|---|---|---|---|
| 1 | GET | `/api/user/profile` | 已登录 | 获取当前用户资料 |
| 2 | PUT | `/api/user/profile` | 已登录（**ELDER → 403**） | 更新当前用户资料 |
| 3 | POST | `/api/user/companion/apply` | 已登录（**ELDER → 403**） | 提交陪诊员资质申请 |
| 4 | GET | `/api/user/companion/application` | 已登录 | 查询自己的资质申请状态 |
| 5 | GET | `/api/user/companion/{id}` | 已登录 | 陪诊员公开资料（`{id}` 传**用户 ID**） |
| 6 | GET | `/api/user/elder` | **FAMILY** | 老人档案列表 |
| 7 | POST | `/api/user/elder` | **FAMILY** | 新增老人档案（建档即绑定） |
| 8 | GET | `/api/user/elder/{id}` | FAMILY（绑定人）/ ADMIN / ELDER（本人） | 老人档案详情 |
| 9 | PUT | `/api/user/elder/{id}` | FAMILY（绑定人） | 修改老人档案（局部更新） |
| 10 | DELETE | `/api/user/elder/{id}` | FAMILY（绑定人） | 删除老人档案（逻辑删除） |
| 11 | POST | `/api/user/elder/bind` | **FAMILY** | 绑定已存在的老人账号 |
| 12 | DELETE | `/api/user/elder/{id}/bind` | FAMILY（绑定人） | 解绑 |

> 「**ELDER → 403**」不是角色注解拦的，而是 `ElderReadOnlyInterceptor` ——
> 老人账号只读规则对所有非 GET 请求一律生效，写新接口时**不需要**为它单独加判断。

---

## 1. 获取当前用户资料

`GET /api/user/profile`　权限：已登录

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 101,
    "username": "fam001",
    "nickname": "家属01",
    "role": "FAMILY",
    "roleLabel": "家属",
    "phone": "138****0001",
    "avatar": null,
    "createTime": "2026-08-20 09:00:00"
  }
}
```

> 密码与身份证号**不在返回范围内**（端到端断言直接检查响应原文里不含这两个字段名）。

---

## 2. 更新当前用户资料

`PUT /api/user/profile`　权限：已登录

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `nickname` | String | 否 | 2–20 字符 | 昵称 |
| `avatar` | String | 否 | URL | 头像地址 |

> 手机号、密码不通过本接口修改（手机号走绑定流程，密码走 `/api/auth/password`）。

### 响应

```json
{ "code": 200, "message": "保存成功", "data": null }
```

### 错误场景

| HTTP | code | 场景 |
|---|---|---|
| 403 | 403 | 老人账号（ELDER）调用 —— 「老人账号为只读模式，该操作请由家属代为完成」 |

### 实现要点

- 日志**只记录用户 ID 与被修改的字段名**，不记录昵称等具体值。
- Service 方法签名不接收 `userId` 参数，从 `SecurityUtils.currentUser()` 取，
  从根上杜绝「前端传 userId 越权改别人资料」。

---

## 3. 提交陪诊员资质申请

`POST /api/user/companion/apply`　权限：已登录（任意角色均可申请）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `realName` | String | ✓ | 2–20 字符 | 真实姓名 |
| `idCard` | String | ✓ | 18 位 | 身份证号（**AES-256-GCM 加密存储**） |
| `serviceArea` | String | ✓ | ≤ 100 字符 | 服务区域 |
| `availableTime` | String | ✓ | ≤ 100 字符 | 可服务时段 |
| `certificates` | Array | ✓ | **至少 1 项，每项 `name` 与 `url` 均校验** | 证件材料 `[{ name, url }]` |
| `remark` | String | 否 | ≤ 200 字符 | 补充说明 |

### 响应

```json
{
  "code": 200,
  "message": "提交成功，请等待管理员审核",
  "data": { "applicationId": 501, "auditStatus": "PENDING", "auditStatusLabel": "待审核" }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 400 | 证件材料为空或某一项缺少 `name` / `url` |
| 400 | 身份证号格式不正确 |
| 400 | **已有待审核申请**（不允许重复提交） |
| 400 | **已通过审核**（无需再申请） |
| 403 | 老人账号调用 |

### 实现要点

- **一次申请写两张表，同一事务**：
  - `companion_audit_record` —— 申请**流水**，每次提交新增一条，保留完整历史；
  - `companion_profile` —— 当前**快照**，一个账号一行（`audit_status` + `work_status`），
    列表/公开资料读它，避免每次 JOIN 流水表找最新一条。
- 提交后 `companion_profile.work_status` 置为 `REST` —— **未通过审核不解锁接单**。
- 被驳回后再次提交：**复用快照行**并清空上一轮的 `reject_reason`，不堆垃圾行。
- 提交申请**不改变 `sys_user.role`**，只有审核通过才升级为 `COMPANION`。
- 证件清单以 **JSON 列**写入（`JSON_VALID` 实测为 1），序列化异常时**不把证件内容写进异常信息**。

---

## 4. 查询自己的资质申请状态

`GET /api/user/companion/application`　权限：已登录

### 响应（已驳回）

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "applicationId": 501,
    "auditStatus": "REJECTED",
    "auditStatusLabel": "已驳回",
    "rejectReason": "身份证照片不清晰，请重新上传",
    "submitTime": "2026-09-10 09:00:00",
    "auditTime": "2026-09-11 14:30:00"
  }
}
```

无申请记录时 `data` 为 `null`（不是空对象）—— 前端判空更省事。

> 该账号从未申请过时，`data` 为 `null` 而不是 `{"auditStatus": null}`，
> 前端用一次 `if (!data)` 就能区分「没申请过」和「申请中」。

---

## 5. 陪诊员公开资料

`GET /api/user/companion/{id}`　权限：已登录

> `{id}` 是**陪诊员用户 ID**（如 301），不是 `companion_profile` 主键（601）。

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 301,
    "realName": "李*军",
    "serviceArea": "海口市美兰区",
    "availableTime": "周一至周五 08:00-18:00",
    "auditStatus": "APPROVED",
    "auditStatusLabel": "已通过",
    "workStatus": "AVAILABLE",
    "workStatusLabel": "可接单",
    "score": "4.00",
    "orderCount": 37
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 2007 | 陪诊员不存在**或尚未通过审核** |

> ⚠️ 本接口**不返回**身份证号、联系电话、证件图片 URL、驳回原因。
>
> **驳回原因为什么不返回**：它是给申请人的私人反馈，通过 §4 给本人看就够了。
> 挂到「任意登录用户都能访问」的公开资料上属于信息泄露。
> 虽然 §1.2 的字段表里列了 `rejectReason`（描述 VO 的容量），但公开接口的实现
> 从不给它赋值 —— **字段表描述的是 VO 能装什么，不是每个接口都会装满**。
>
> **未通过审核的陪诊员一律 2007**：不区分「不存在」与「没通过」，
> 避免用错误码反查询某人的审核结果。

---

## 6. 老人档案列表

`GET /api/user/elder`　权限：**FAMILY**（陪诊员与老人均 403）

### 请求参数（分页 + 筛选）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页，见全局约定 |
| `keyword` | String | 否 | 按**姓名**模糊搜索 |
| `bindStatus` | String | 否 | `BOUND` / `UNBOUND`（见下方已知限制） |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 1,
    "page": 1,
    "size": 10,
    "pages": 1,
    "records": [
      {
        "id": 401,
        "name": "张*海",
        "gender": "MALE",
        "genderLabel": "男",
        "age": 88,
        "birthDate": "1938-01-01",
        "phone": "139****0001",
        "emergencyContact": "张四",
        "favoriteHospital": "海南省人民医院",
        "bindStatus": "BOUND",
        "bindStatusLabel": "已绑定",
        "createTime": "2026-08-20 09:00:00"
      }
    ]
  }
}
```

### 实现要点

- **只返回当前登录家属有效绑定的老人**：先按 `family_id` 查 `family_elder_relation`
  取出 `status=BOUND` 的 `elder_id` 集合，再用 `IN` 查档案。
  绑定集合为空时**提前返回空页**，不拼 `IN ()`（那是语法错误）。
- 列表**不返回** `idCard` / `address` / `medicalHistory` / `allergyHistory` / `mobilityLevel` / `relation`。
- 列表**不接收 `familyId` 参数** —— 传了也不认，家属 ID 一律取自登录态。

### 已知限制（一期）

> `bindStatus=UNBOUND` 在本接口**筛不出任何数据**，恒定返回空集
> （实测 `total=0`）。原因是列表的数据源本身就是「已绑定关系」，
> 未绑定的老人在这个家属名下不存在对应关系行，自然不会被查出来。
>
> 这个参数保留是为了**接口契约稳定**（二期「我的解绑记录」会用到），
> 前端一期**不要**把它渲染成筛选项。真正想「找回已解绑的老人」，
> 走 §11 重新绑定即可。

---

## 7. 新增老人档案

`POST /api/user/elder`　权限：**FAMILY**

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `name` | String | ✓ | 2–20 字符 | 姓名 |
| `gender` | String | ✓ | `MALE` / `FEMALE` | 性别 |
| `birthDate` | String | ✓ | `yyyy-MM-dd`，**必须早于今天** | 出生日期（不接收 `age`） |
| `idCard` | String | 否 | 18 位 | 身份证号，**加密存储** |
| `phone` | String | 否 | `1[3-9]` 开头 11 位 | 手机号 |
| `address` | String | 否 | ≤ 200 字符 | 常用地址 |
| `emergencyContact` | String | 否 | ≤ 20 字符 | 紧急联系人 |
| `emergencyPhone` | String | 否 | 11 位 | 紧急联系人电话 |
| `medicalHistory` | String | 否 | ≤ 500 字符 | 病史备注（**仅记录**） |
| `allergyHistory` | String | 否 | ≤ 500 字符 | 过敏史备注（**仅记录**） |
| `mobilityLevel` | String | 否 | `SELF` / `ASSIST` / `WHEELCHAIR` | 行动能力 |
| `favoriteHospital` | String | 否 | ≤ 100 字符 | 常去医院 |
| `remark` | String | 否 | ≤ 255 字符 | 备注 |

> **不接收 `userId`**：家属代建档时老人可以没有登录账号（`elder_profile.user_id` 允许为 NULL）。
> 把已有账号挂在档案上走的是「注册 + 绑定」流程，不是建档接口。

### 响应

```json
{ "code": 200, "message": "添加成功", "data": { "elderId": 433 } }
```

### 错误场景

| code | 场景 |
|---|---|
| 400 | 姓名为空 / 出生日期晚于今天 / 手机号格式非法 / 行动能力枚举非法 |
| 403 | 老人账号或陪诊员调用 |

### 实现要点（**建档即绑定**）

一次建档做三件事，缺一不可：

1. 插 `elder_profile`，`bind_status` 直接写 `BOUND`，`create_by` 记当前家属 ID；
2. 插 `family_elder_relation`，`bind_type=CREATE`（家属代建档）、`status=BOUND`、
   `is_default=1`（建档人是主要联系人）；
3. 身份证号 AES-256-GCM 加密后入库（实测密文长度 **64 字符**，为 Base64 编码的
   `IV(12) + 密文(18) + 认证标签(16)`）。

**建完立刻能在列表里看到**，家属不需要再去点一次「绑定」——
这是端到端脚本专门验证的一条（建档后列表 `total` 从 1 变 2）。

---

## 8. 老人档案详情

`GET /api/user/elder/{id}`　权限：FAMILY（须为绑定人）/ ADMIN / ELDER（须为本人）

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 401,
    "name": "张德海",
    "gender": "MALE",
    "genderLabel": "男",
    "age": 88,
    "birthDate": "1938-01-01",
    "idCard": "460106********4321",
    "phone": "139****0001",
    "address": "海口市美兰区春晖小区***",
    "emergencyContact": "张四",
    "emergencyPhone": "138****0001",
    "medicalHistory": "高血压、2型糖尿病，长期服药",
    "allergyHistory": "青霉素过敏",
    "mobilityLevel": "ASSIST",
    "mobilityLevelLabel": "需搀扶",
    "favoriteHospital": "海南省人民医院",
    "bindStatus": "BOUND",
    "bindStatusLabel": "已绑定",
    "relation": "儿子",
    "createTime": "2026-08-20 09:00:00"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 2001 | 老人档案不存在（含已逻辑删除） |
| 2006 | 无权查看该档案（该老人未绑定到当前家属 / 老人查的不是自己） |
| 403 | 陪诊员调用 |

### 实现要点

- **身份证号的处理链路**：库里是密文 → Service 取出后立刻 `AesUtil.decrypt` 得到明文
  → **就地 `MaskUtil.idCard` 脱敏** → 只把脱敏串传给 VO。
  明文**从不作为返回值离开 Service 方法**（VO 的入参是 `maskedIdCard`，
  不接收密钥、不接收明文），因此不会被 `toString()` 或日志带走。
- 地址走 `MaskUtil.address`：遇到**第一个数字**即认为到门牌号，其后替换为 `***`。
- `age` 用 `Period.between(birthDate, today)` 实时算；`birthDate` 为空返回 `null`
  而不是 `0`（0 岁是个错误答案，不是「不知道」）。
- `relation` 是**当前登录家属与该老人的关系**，管理员/老人读时为 `null`（字段消失）。
- **已逻辑删除的档案查不到**：`deleted=1` 的档案在详情返回 2001（`@TableLogic` 自动过滤），
  但物理行仍在库里（历史订单还要 JOIN 它）。

---

## 9. 修改老人档案

`PUT /api/user/elder/{id}`　权限：FAMILY（须为绑定人）

请求体字段同 §7，**全部字段可选**。

### 局部更新语义（重要）

| 传值 | 行为 |
|---|---|
| 字段**不传**（`null`） | **不修改**，保持原值 |
| 字段传**空串** `""` | **清空**该字段（库里置为 `NULL`） |
| 字段传新值 | 覆盖 |
| `name` / `gender` / `birthDate` 传空串 | `400`（库里是 `NOT NULL` 列，清空没有意义） |

> **为什么必须区分「不传」与「空串」**：前端编辑表单里「用户没动这个字段」
> 和「用户把这段文字删干净了」是两件事。如果统一按 `null` 跳过，
> 用户就永远删不掉一个填错的地址。
>
> **实现上不能用 `updateById`** —— 它会跳过所有 `null` 字段，做不到「置为 NULL」。
> 必须用 `update(null, wrapper).set(column, null)` 显式指定要清空的列。
> 这条行为由端到端脚本 H2/H3 两条断言锁死（H3 直接查库确认 `address IS NULL`）。

### 响应

```json
{ "code": 200, "message": "保存成功", "data": null }
```

### 错误场景

| code | 场景 |
|---|---|
| 2001 | 档案不存在 |
| 2006 | 无权修改（非绑定人） |
| 400 | 姓名空串 / 枚举小写 / 手机号格式非法 |

### 实现要点

- 传入新的身份证号时**重新加密**再落库（同一明文两次加密结果不同是预期行为，
  AES-GCM 每次用随机 IV —— 这也意味着**不能用等值查询匹配密文**）。
- 每次修改前先跑一遍归属校验，**校验不通过时不产生任何 SQL**（端到端脚本 D2/D4 验证
  越权修改后库里地址原封不动）。

---

## 10. 删除老人档案

`DELETE /api/user/elder/{id}`　权限：FAMILY（须为绑定人）

### 响应

```json
{ "code": 200, "message": "已删除", "data": null }
```

### 错误场景

| code | 场景 |
|---|---|
| 2001 | 档案不存在 |
| 2006 | 无权删除 |
| **409** | **该老人存在进行中的陪诊订单**，请先处理完再操作 |

### 实现要点

- **逻辑删除**（`deleted=1`），**物理行必须保留** —— 历史订单、用药记录、评价都要 JOIN 它。
  实测：删除后 `deleted=1` 且物理行仍存在。
- 删除时**同步把绑定关系置为 `UNBOUND`** 并写 `unbind_time`，
  否则会留下一条「指向已删除档案的 BOUND 关系」，列表页会查出幽灵数据。
- **进行中订单闸门**：状态属于 `PENDING` / `ACCEPTED` / `IN_SERVICE` 时拒绝删除。
  拒绝必须发生在**任何写操作之前** —— 端到端脚本 F3 专门确认「被拒的删除没有留下任何痕迹」
  （`bind_status` 仍是 `BOUND`、`deleted` 仍是 `0`）。

---

## 11. 绑定老人账号

`POST /api/user/elder/bind`　权限：**FAMILY**

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `bindType` | String | ✓ | `PHONE`（按手机号）/ `INVITE_CODE`（按邀请码，**一期返回 501**） |
| `bindValue` | String | ✓ | 手机号或邀请码 |
| `relation` | String | ✓ | 与老人关系：`SON` / `DAUGHTER` / `RELATIVE` / `OTHER` |

### 响应

```json
{ "code": 200, "message": "绑定成功", "data": { "elderId": 434 } }
```

### 错误场景

| code | 场景 |
|---|---|
| 400 | 手机号格式不正确 / 关系枚举非法 |
| 403 | 老人账号或陪诊员调用 |
| 2001 | 该手机号没有对应的老人账号 |
| 2002 | 该老人已被**其他**家属绑定 |
| **409** | 该老人**已经绑定到你自己**名下（不重复建关系） |
| **501** | `bindType=INVITE_CODE` |

> **2002 与 409 的区别**：前者是「被别人占了」，用户应该去联系对方或走纠纷；
> 后者是「本来就绑着」，用户刷新一下页面就行。错误码分开前端才能给出正确引导。

### 实现要点

- **一个老人只允许被一位主要家属绑定**（避免操作权纠纷），
  多人协助走二期规划。
- **关系行复用，不堆垃圾行**：解绑后重新绑定（含换个家属来绑）会
  **`UPDATE` 原来那一行**而不是 `INSERT` 一条新的。
  实测反复「绑 → 解 → 换个家属绑」之后，库里该老人的有效关系行数仍是 **2 行**
  （家属 A 的 `UNBOUND` 行 + 家属 B 的 `BOUND` 行），而不是越试越多。
- 查询关系行时用 `selectList` + `orderByAsc(id)` 取首行，**不用 `selectOne`** ——
  历史数据万一有两行会让 `selectOne` 抛 `TooManyResultsException`，
  把一个数据问题升级成 500。
- **被封禁账号不可绑定**（`sys_user.status` 为 `DISABLED` 时返回 2004）。

### 为什么 `INVITE_CODE` 返回 501 而不是先假装支持

`elder_profile` 与 `sys_user` 两张表里**都没有邀请码字段**（已实查 DDL）。
要真做就得新增 Flyway 迁移 `V4__add_invite_code.sql`，
而 M1 的表结构是「Entity 由 `gen_entity.py` 从 V1 生成」的生成物链条 ——
为了一个一期用不到的功能去动 V1/V4，会让 M1 已验收的 Entity 与表结构产生漂移。
所以一期**明确返回 501**（「邀请码绑定暂未开放，请改用手机号绑定」），
把接口契约先占住，实现留到二期。

---

## 12. 解绑

`DELETE /api/user/elder/{id}/bind`　权限：FAMILY（须为绑定人）

### 响应

```json
{ "code": 200, "message": "已解绑", "data": null }
```

### 错误场景

| code | 场景 |
|---|---|
| 2001 | 档案不存在 |
| 2005 | 绑定关系不存在（本来就没绑，或绑的是**别的家属**） |
| 2006 | 无权解绑（该老人不在你名下） |
| **409** | 该老人存在进行中的订单，暂不能解绑 |

### 实现要点

- 解绑写三处：关系行 `status=UNBOUND` + `unbind_time`，档案 `bind_status=UNBOUND`。
- **解绑 + 重新绑定不会丢数据**：档案本身（含病史）保留，
  换家属绑上之后新家属立刻能看到完整档案。

---

## 三、验收标准（M3）

### 3.1 计划书验收项

- [x] 家属绑定 1 位老人后，可在下单页选择该老人
      （列表只返回有效绑定老人，`total` 与订单页选人范围一致）
- [x] 老人档案身份证字段在数据库中为密文（不可直视）
      —— 实测 `CHAR_LENGTH(id_card)=64`，且 `id_card = 明文` 比较结果为 `0`
- [x] 接口返回的身份证号与手机号为脱敏串
      —— 身份证 `460106********4321`，手机 `139****0001`
- [x] 绑定关系不存在时返回 `2005`
- [x] 无权操作他人绑定的老人时返回 `2006`（越权测试用例）
- [x] 删除老人档案后，普通查询不可见，但历史订单仍可正常查看
      —— 逻辑删除，物理行保留
- [ ] 未审核陪诊员（`auditStatus = PENDING`）无法接单 —— **归 M4**（订单模块接单时校验）
      ；M3 已完成的**前置条件**：申请提交后 `work_status` 置为 `REST`，公开资料返回 2007
- [ ] 审核通过后同一账号立即可以接单，无需重新登录 —— **归 M9**（管理员审核入口）
      ；M3 已完成的**前置条件**：`companion_profile` 快照与流水同事务写入
- [ ] 驳回时未填原因返回 `8003` —— **归 M9**（管理员审核入口）

### 3.2 本模块额外锁定（代码层面已强制）

- [x] 家属 A 无法读/改/删家属 B 的老人（`2006`），且**越权请求不产生任何 SQL**
- [x] 老人账号只能读自己的档案（读别人的 → `2006`），且所有写操作 `403`
- [x] 陪诊员无法通过家属接口访问任何老人档案（`403`）
- [x] 管理员可读任意档案，但**不能通过家属接口写入**（归属校验不放行 ADMIN）
- [x] 进行中订单拦截删除与解绑（`409`），且被拒后库里状态未被改动
- [x] 列表不返回身份证 / 地址 / 病史 / 过敏史 / 行动能力
- [x] 建档即绑定（`bind_type=CREATE`、`is_bound=1`、`create_by=当前家属`）
- [x] 局部更新的「不传 = 不改、空串 = 清空」语义
- [x] `INVITE_CODE` 返回 `501`；`bindType=PHONE` 手机号不合法返回 `400`
- [x] 一老人一主要家属；解绑后换人绑不新增垃圾关系行
- [x] 时间字段统一 `yyyy-MM-dd HH:mm:ss`（由 `config/JacksonConfig.java` 强制）

### 3.3 实测记录（2026-09-15）

| 层 | 产物 | 结果 |
|---|---|---|
| 单元测试 | `service/ElderServiceTest.java` | 29/29 通过 |
| 单元测试 | `service/CompanionServiceTest.java` | 10/10 通过 |
| 集成测试 | `security/ElderOwnershipMatrixTest.java`（真实 JWT + 真实库） | 38/38 通过 |
| 集成测试 | `config/JacksonDateTimeFormatTest.java` | 7/7 通过 |
| 回归 | M2 的 `PermissionMatrixTest` / `ElderReadOnlyInterceptorTest` / `AuthServiceTest` | 全绿 |
| **合计** | `mvn test` | **144/144 通过，BUILD SUCCESS** |
| 端到端 | `backend/sql/tools/e2e_user_profile.py`（真实 HTTP 打 8080） | **86/86 通过** |

> 端到端脚本从登录拿真令牌开始，全程走 HTTP 接口，脚本末尾**物理清理**造出来的
> 测试账号/档案/绑定关系/资质申请，跑完种子数据与跑前一致，可反复执行。

### 3.4 已知限制汇总（一期）

| # | 限制 | 原因 | 影响 |
|---|---|---|---|
| 1 | `bindStatus=UNBOUND` 列表筛选恒为空 | 列表数据源本身是「已绑定关系」 | 前端一期不要渲染该筛选项 |
| 2 | `bindType=INVITE_CODE` 返回 `501` | 表结构无邀请码字段（见 §11） | 前端只放「手机号绑定」 |
| 3 | 一个老人只能被一位家属绑定 | 避免操作权纠纷（产品决策） | 多人协助走二期 |
| 4 | 管理员不能通过本模块接口改档案 | 审计需要（管理员操作走 M9 并留日志） | 无 |

---

## 四、变更记录

| 版本 | 日期 | 变更内容 |
|---|---|---|
| v0.1.0 | 2026-09-15 | M0 骨架阶段初版 |
| v0.2.0 | 2026-09-15 | **M3 实现落地**：补齐 `allergyHistory` / `mobilityLevel` / `remark` 字段；修正 `CompanionProfileVO.id` 语义为用户 ID；`INVITE_CODE` 改为 `501`；错误码 `2001 → 2007`（陪诊员不存在）；补充列表/详情两套脱敏口径、局部更新语义、归属校验三层说明与实测记录 |
