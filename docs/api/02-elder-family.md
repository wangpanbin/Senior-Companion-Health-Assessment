# 02 用户与档案

> 覆盖模块：**M3 用户与档案管理**
> 负责人：B（后端）+ D（数据库）
> 归属迭代：W2–W5 开发，W13 补资质审核
> 全局约定见 [README.md](./README.md)

---

## 一、数据模型

### ElderVO（老人档案）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 档案 ID |
| `name` | String | 姓名（列表返回脱敏：`张*三`；详情返回全名，仅家属可见） |
| `gender` | String | `MALE` / `FEMALE` |
| `age` | Integer | 年龄 |
| `idCard` | String | **脱敏**身份证号，如 `110101********4567` |
| `phone` | String | **脱敏**手机号 |
| `address` | String | 常用地址（门牌号脱敏为 `***`） |
| `emergencyContact` | String | 紧急联系人姓名 |
| `emergencyPhone` | String | **脱敏**紧急联系人电话 |
| `medicalHistory` | String | 病史备注（**仅记录，不做诊断**） |
| `favoriteHospital` | String | 常去医院 |
| `bindStatus` | String | 绑定状态：`BOUND` / `UNBOUND` |
| `createTime` | String | 创建时间 |

### CompanionProfileVO（陪诊员资料）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 陪诊员用户 ID |
| `realName` | String | 真实姓名（脱敏） |
| `serviceArea` | String | 服务区域，如「海口市美兰区」 |
| `availableTime` | String | 可服务时段，如「周一至周五 08:00–18:00」 |
| `auditStatus` | String | `PENDING`（待审核）/ `APPROVED`（已通过）/ `REJECTED`（已驳回） |
| `auditStatusLabel` | String | 中文状态 |
| `rejectReason` | String | 驳回原因，仅 `REJECTED` 时返回 |
| `score` | String | 平均评分，如 `"4.80"` |
| `orderCount` | Integer | 累计完成订单数 |
| `certificates` | Array | 资质证件列表 `[{ name, url }]` |

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | GET | `/api/user/profile` | 已登录 | 获取当前用户资料 |
| 2 | PUT | `/api/user/profile` | 已登录 | 更新当前用户资料 |
| 3 | POST | `/api/user/companion/apply` | 已登录 | 提交陪诊员资质申请 |
| 4 | GET | `/api/user/companion/application` | 已登录 | 查询自己的资质申请状态 |
| 5 | GET | `/api/user/companion/{id}` | 已登录 | 陪诊员公开资料 |
| 6 | GET | `/api/user/elder` | FAMILY | 老人档案列表 |
| 7 | POST | `/api/user/elder` | FAMILY | 新增老人档案 |
| 8 | GET | `/api/user/elder/{id}` | FAMILY / ADMIN | 老人档案详情 |
| 9 | PUT | `/api/user/elder/{id}` | FAMILY | 修改老人档案 |
| 10 | DELETE | `/api/user/elder/{id}` | FAMILY | 删除老人档案（逻辑删除） |
| 11 | POST | `/api/user/elder/bind` | FAMILY | 绑定已存在的老人账号 |
| 12 | DELETE | `/api/user/elder/{id}/bind` | FAMILY | 解绑 |

---

## 1. 获取当前用户资料

`GET /api/user/profile`　权限：已登录

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
    "createTime": "2026-09-01 10:20:30"
  }
}
```

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

---

## 3. 提交陪诊员资质申请

`POST /api/user/companion/apply`　权限：已登录（任意角色均可申请）

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `realName` | String | ✓ | 2–20 字符 | 真实姓名 |
| `idCard` | String | ✓ | 18 位 | 身份证号（**加密存储**） |
| `serviceArea` | String | ✓ | ≤ 100 字符 | 服务区域 |
| `availableTime` | String | ✓ | ≤ 100 字符 | 可服务时段 |
| `certificates` | Array | ✓ | 至少 1 项 | 证件材料 `[{ name, url }]`，url 来自文件上传接口 |
| `remark` | String | 否 | ≤ 200 字符 | 补充说明 |

### 响应

```json
{
  "code": 200,
  "message": "提交成功，请等待管理员审核",
  "data": { "applicationId": 501, "auditStatus": "PENDING" }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 400 | 证件材料为空 |
| 400 | 已有待审核申请（不允许重复提交） |

### 实现要点

- 身份证号必须**加密存储**（AES 或等价方案），密钥走配置项，不硬编码。
- 提交后用户角色仍为原角色，**只有审核通过才升级为 `COMPANION`**。

---

## 4. 查询自己的资质申请状态

`GET /api/user/companion/application`　权限：已登录

### 响应

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

无申请记录时 `data` 为 `null`。

---

## 5. 陪诊员公开资料

`GET /api/user/companion/{id}`　权限：已登录

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 10088,
    "realName": "李*",
    "serviceArea": "海口市美兰区",
    "availableTime": "周一至周五 08:00–18:00",
    "auditStatus": "APPROVED",
    "auditStatusLabel": "已通过",
    "score": "4.80",
    "orderCount": 37
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 2001 | 陪诊员不存在 |

> ⚠️ 本接口**不返回**身份证号、联系电话、证件图片 URL，保护陪诊员隐私。

---

## 6. 老人档案列表

`GET /api/user/elder`　权限：FAMILY

### 请求参数（分页 + 筛选）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `size` | Integer | 否 | 分页，见全局约定 |
| `keyword` | String | 否 | 按姓名模糊搜索 |
| `bindStatus` | String | 否 | `BOUND` / `UNBOUND` |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 2,
    "page": 1,
    "size": 10,
    "pages": 1,
    "records": [
      {
        "id": 301,
        "name": "张*三",
        "gender": "MALE",
        "age": 78,
        "phone": "139****6677",
        "favoriteHospital": "海南省人民医院",
        "bindStatus": "BOUND",
        "createTime": "2026-09-02 10:00:00"
      }
    ]
  }
}
```

### 实现要点

- **只返回当前登录家属绑定的老人**，SQL 必须带 `family_id = 当前用户ID` 条件。
- 列表页姓名、手机号脱敏；详情页对已绑定家属返回全名。

---

## 7. 新增老人档案

`POST /api/user/elder`　权限：FAMILY

### 请求体

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `name` | String | ✓ | 2–20 字符 | 姓名 |
| `gender` | String | ✓ | `MALE` / `FEMALE` | 性别 |
| `birthDate` | String | ✓ | `yyyy-MM-dd` | 出生日期（年龄由后端计算，不接收 age） |
| `idCard` | String | 否 | 18 位 | 身份证号，加密存储 |
| `phone` | String | 否 | 11 位 | 手机号 |
| `address` | String | 否 | ≤ 200 字符 | 常用地址 |
| `emergencyContact` | String | 否 | ≤ 20 字符 | 紧急联系人 |
| `emergencyPhone` | String | 否 | 11 位 | 紧急联系人电话 |
| `medicalHistory` | String | 否 | ≤ 500 字符 | 病史备注（**仅记录**） |
| `favoriteHospital` | String | 否 | ≤ 100 字符 | 常去医院 |

### 响应

```json
{ "code": 200, "message": "添加成功", "data": { "elderId": 301 } }
```

---

## 8. 老人档案详情

`GET /api/user/elder/{id}`　权限：FAMILY（须为绑定人）/ ADMIN

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 301,
    "name": "张三",
    "gender": "MALE",
    "age": 78,
    "idCard": "110101********4567",
    "phone": "139****6677",
    "address": "海南省海口市美兰区人民大道***",
    "emergencyContact": "张四",
    "emergencyPhone": "138****1234",
    "medicalHistory": "高血压、2型糖尿病，长期服药",
    "favoriteHospital": "海南省人民医院",
    "bindStatus": "BOUND"
  }
}
```

### 错误场景

| code | 场景 |
|---|---|
| 2001 | 老人档案不存在 |
| 2006 | 无权操作该老人档案（该老人未绑定到当前家属） |

---

## 9. 修改老人档案

`PUT /api/user/elder/{id}`　权限：FAMILY（须为绑定人）

请求体同「新增老人档案」，所有字段可选，只传要修改的。

### 响应

```json
{ "code": 200, "message": "保存成功", "data": null }
```

---

## 10. 删除老人档案

`DELETE /api/user/elder/{id}`　权限：FAMILY（须为绑定人）

### 响应

```json
{ "code": 200, "message": "已删除", "data": null }
```

### 实现要点

- **逻辑删除**（`deleted = 1`），保留历史订单与用药记录的关联，不物理删除。
- 存在进行中的订单时不允许删除，返回 `409`。

---

## 11. 绑定老人账号

`POST /api/user/elder/bind`　权限：FAMILY

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `bindType` | String | ✓ | `PHONE`（按手机号）/ `INVITE_CODE`（按邀请码） |
| `bindValue` | String | ✓ | 手机号或邀请码 |
| `relation` | String | ✓ | 与老人关系：`SON` / `DAUGHTER` / `RELATIVE` / `OTHER` |

### 响应

```json
{ "code": 200, "message": "绑定成功", "data": { "elderId": 302 } }
```

### 错误场景

| code | 场景 |
|---|---|
| 2001 | 未找到该老人账号 |
| 2002 | 该老人已被其他家属绑定 |
| 400 | 邀请码已过期 |

### 实现要点

- 一个老人只允许被**一位主要家属**绑定（避免操作权纠纷），如需多人协助走二期规划。
- 绑定关系写入 `family_elder_relation`，记录 `family_id` / `elder_id` / `relation` / `bind_time`。

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
| 2005 | 绑定关系不存在 |
| 409 | 该老人存在进行中的订单，暂不能解绑 |

---

## 三、验收标准（M3）

- [ ] 家属绑定 1 位老人后，可在下单页选择该老人
- [ ] 老人档案身份证字段在数据库中为密文（不可直视）
- [ ] 接口返回的身份证号与手机号为脱敏串
- [ ] 绑定关系不存在时返回 `2005`
- [ ] 无权操作他人绑定的老人时返回 `2006`（越权测试用例）
- [ ] 删除老人档案后，普通查询不可见，但历史订单仍可正常查看
- [ ] 未审核陪诊员（`auditStatus = PENDING`）无法接单
- [ ] 审核通过后同一账号立即可以接单，无需重新登录
- [ ] 驳回时未填原因返回 `8003`
