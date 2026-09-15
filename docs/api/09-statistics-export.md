# 09 数据统计与导出

> 覆盖模块：**M10 数据统计、可视化与导出**
> 负责人：A（ECharts 看板）+ B（统计与导出接口）
> 归属迭代：迭代五 · W14–W15
> 前置依赖：M4 陪诊订单、M9 管理后台　全局约定见 [README.md](./README.md)

> 性能要求：统计接口在 **1 万条订单**数据下响应时间 < 2s。
> 若达不到，先 `EXPLAIN` 看是否走索引，再考虑加汇总表（一期不建议上预聚合）。

---

## 一、通用统计约定

| 项目 | 约定 |
|---|---|
| 时间粒度 | `granularity` = `DAY` / `WEEK` / `MONTH` |
| 时间区间 | `startDate` / `endDate`（`yyyy-MM-dd`，闭区间），默认最近 30 天 |
| 区间上限 | 不超过 366 天，超过返回 `9002` |
| 空数据 | 返回空数组或 0，**不返回 `null`，不报错** |
| 数值精度 | 比率类字段返回字符串百分数，两位小数，如 `"94.59%"` |
| 权限 | 全部接口仅 `ADMIN` |

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | GET | `/api/statistics/overview` | ADMIN | 总览指标 |
| 2 | GET | `/api/statistics/order-trend` | ADMIN | 订单趋势（折线图） |
| 3 | GET | `/api/statistics/order-status` | ADMIN | 订单状态分布（饼图） |
| 4 | GET | `/api/statistics/companion-rank` | ADMIN | 陪诊员接单排行（柱状图） |
| 5 | GET | `/api/statistics/medication-missed` | ADMIN | 漏服率统计 |
| 6 | GET | `/api/statistics/export/order` | ADMIN | 导出订单 Excel |
| 7 | GET | `/api/statistics/export/user` | ADMIN | 导出用户 Excel |

---

## 1. 总览指标

`GET /api/statistics/overview`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` | String | 否 | `yyyy-MM-dd`，默认 30 天前 |
| `endDate` | String | 否 | `yyyy-MM-dd`，默认今天 |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "startDate": "2026-08-16",
    "endDate": "2026-09-15",
    "order": {
      "totalCount": 137,
      "completedCount": 118,
      "cancelledCount": 9,
      "inProgressCount": 10,
      "completedRate": "86.13%",
      "cancelRate": "6.57%"
    },
    "user": {
      "totalCount": 213,
      "newCount": 18,
      "growthRate": "9.23%",
      "elderCount": 64,
      "familyCount": 121,
      "companionCount": 26,
      "adminCount": 2
    },
    "companion": {
      "activeCount": 17,
      "avgAcceptMinutes": 12
    },
    "medication": {
      "taskTotalCount": 1580,
      "takenCount": 1421,
      "missedCount": 89,
      "missedRate": "5.63%"
    }
  }
}
```

### 验收对照

| 字段 | 核对方式 |
|---|---|
| `order.totalCount` | `SELECT COUNT(*) FROM companion_order WHERE create_time BETWEEN ? AND ?` |
| `order.completedCount` | `... WHERE status IN ('COMPLETED','REVIEWED')` |
| `order.completedRate` | `completedCount / totalCount`，保留 2 位小数的百分数 |
| `user.newCount` | `SELECT COUNT(*) FROM sys_user WHERE create_time BETWEEN ? AND ?` |

### 实现要点

- `completedRate` 的分母是**区间内全部订单**（含已取消），口径必须写进接口注释，避免答辩时被追问口径不一致。
- 注意：`COMPLETED` 与 `REVIEWED` 都算「已完成」（评价只是后续动作）。

---

## 2. 订单趋势（折线图）

`GET /api/statistics/order-trend`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` / `endDate` | String | 否 | 时间区间，默认最近 30 天 |
| `granularity` | String | 否 | `DAY`（默认）/ `WEEK` / `MONTH` |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "granularity": "DAY",
    "categories": ["2026-09-13", "2026-09-14", "2026-09-15"],
    "series": [
      { "name": "新增订单", "data": [12, 18, 15] },
      { "name": "完成订单", "data": [10, 15, 13] },
      { "name": "取消订单", "data": [1, 2, 0] }
    ]
  }
}
```

### 实现要点

- **必须补齐无数据的日期为 0**，否则前端折线图会跳段（这是常见坑，写进验收项）。
- `categories` 长度必须与每个 `series[].data` 长度完全一致。

---

## 3. 订单状态分布（饼图）

`GET /api/statistics/order-status`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` / `endDate` | String | 否 | 时间区间 |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    { "status": "PENDING", "statusLabel": "待接单", "count": 3, "percent": "2.19%" },
    { "status": "ACCEPTED", "statusLabel": "已接单", "count": 5, "percent": "3.65%" },
    { "status": "IN_SERVICE", "statusLabel": "服务中", "count": 2, "percent": "1.46%" },
    { "status": "COMPLETED", "statusLabel": "已完成", "count": 96, "percent": "70.07%" },
    { "status": "REVIEWED", "statusLabel": "已评价", "count": 22, "percent": "16.06%" },
    { "status": "CANCELLED", "statusLabel": "已取消", "count": 9, "percent": "6.57%" }
  ]
}
```

### 实现要点

- 六种状态**全部返回**，数量为 0 的也返回（`count: 0`），保证饼图图例完整。
- `percent` 之和应为 100.00%（允许因四舍五入有 0.01 的误差）。

---

## 4. 陪诊员接单排行（柱状图）

`GET /api/statistics/companion-rank`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` / `endDate` | String | 否 | 时间区间 |
| `limit` | Integer | 否 | 返回条数，默认 10，最大 50 |
| `metric` | String | 否 | 排序指标：`ORDER_COUNT`（默认）/ `SCORE` |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    { "rank": 1, "companionId": 10088, "companionName": "李*", "orderCount": 22, "completedCount": 21, "score": "4.91" },
    { "rank": 2, "companionId": 10092, "companionName": "王*", "orderCount": 18, "completedCount": 17, "score": "4.72" }
  ]
}
```

### 实现要点

- 只统计已通过审核的陪诊员。
- 接单数为 0 的陪诊员不进入排行。

---

## 5. 漏服率统计

`GET /api/statistics/medication-missed`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` / `endDate` | String | 否 | 时间区间 |
| `granularity` | String | 否 | `DAY` / `WEEK` / `MONTH` |
| `elderId` | Long | 否 | 只看某位老人 |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "granularity": "DAY",
    "categories": ["2026-09-13", "2026-09-14", "2026-09-15"],
    "series": [
      { "name": "任务总数", "data": [48, 52, 50] },
      { "name": "已服用", "data": [44, 49, 46] },
      { "name": "漏服", "data": [4, 3, 4] }
    ],
    "summary": {
      "taskTotalCount": 150,
      "takenCount": 139,
      "missedCount": 11,
      "missedRate": "7.33%"
    }
  }
}
```

### 用途

答辩量化指标：「用定时任务 + 升级通知把漏服率从 X% 降到 Y%」——本接口是这句话的数据来源。

---

## 6. 导出订单 Excel

`GET /api/statistics/export/order`　权限：**ADMIN**

### 请求参数

筛选条件与 `/api/admin/order` 完全一致（`status` / `keyword` / `startDate` / `endDate` 等）。
**导出的数据必须与页面上当前筛选结果一致**（验收项）。

### 响应

- `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- `Content-Disposition: attachment; filename="订单数据_20260915_104500.xlsx"`
- Body 为二进制流（**不走统一响应结构，前端拦截器已做透传处理**）

### 导出列定义

| 列名 | 字段 | 说明 |
|---|---|---|
| 订单号 | orderNo | |
| 老人姓名 | elderName | 脱敏 |
| 老人年龄 | elderAge | |
| 家属姓名 | familyName | 脱敏 |
| 陪诊员 | companionName | 脱敏 |
| 医院 | hospital | |
| 科室 | department | |
| 就诊时间 | visitTime | `yyyy-MM-dd HH:mm:ss` |
| 订单状态 | statusLabel | 中文 |
| 服务费 | fee | |
| 结算状态 | paymentStatus | 中文 |
| 下单时间 | createTime | |
| 接单时间 | acceptTime | |
| 完成时间 | finishTime | |

### 错误场景

| code | 场景 |
|---|---|
| 9001 | 导出数据量超过上限（默认 10000 行），请缩小筛选范围 |
| 9002 | 统计时间区间不合法（超过 366 天或起止颠倒） |

### 实现要点

- 用 **EasyExcel** 流式写出，避免一次性把 1 万行加载进内存。
- 文件名为中文时必须 URL 编码，否则部分浏览器乱码：
  `filename*=UTF-8''` + `URLEncoder.encode(name, UTF_8)`。
- 导出**必须有行数上限**，防止误点导致 OOM。
- 命名规范：`订单数据_yyyyMMdd_HHmmss.xlsx`。

---

## 7. 导出用户 Excel

`GET /api/statistics/export/user`　权限：**ADMIN**

### 请求参数

同 `/api/admin/user`（`role` / `status` / `keyword` 等）。

### 导出列定义

| 列名 | 字段 | 说明 |
|---|---|---|
| 用户 ID | id | |
| 用户名 | username | |
| 昵称 | nickname | |
| 手机号 | phone | **脱敏** |
| 角色 | roleLabel | 中文 |
| 账号状态 | statusLabel | 中文 |
| 关联订单数 | orderCount | |
| 注册时间 | createTime | |
| 最后登录时间 | lastLoginTime | |

> ⚠️ 导出的 Excel **绝不包含**密码、身份证号、完整手机号。这是合规红线，也是验收项。

---

## 三、前端看板渲染约定

| 图表 | 使用接口 | 图表类型 |
|---|---|---|
| 核心指标卡 | `/overview` | `el-statistic` 数字卡 |
| 订单趋势 | `/order-trend` | ECharts 折线图 |
| 状态分布 | `/order-status` | ECharts 饼图（环形） |
| 接单排行 | `/companion-rank` | ECharts 横向柱状图 |
| 漏服率 | `/medication-missed` | ECharts 堆叠柱状图 |

### 实现要点

- 时间区间切换是**全局筛选器**，切换后所有图表联动刷新（一个筛选器，多图联动）。
- 组件销毁时**必须 `dispose()` ECharts 实例**，否则切页面会内存泄漏（这是常见的低级 bug）。
- 无数据时显示 `<el-empty>` 空状态，不显示报错或空白。
- 自适应：监听窗口 `resize` 调用 `chart.resize()`。

---

## 四、验收标准（M10）

- [ ] 看板「订单总量」与 `SELECT COUNT(*) FROM companion_order` 手工核对一致
- [ ] 「完成率」= 已完成 / 总订单，与 SQL 计算值一致（保留 2 位小数）
- [ ] 切换时间区间后图表数据随之变化，且**区间外数据不混入**
- [ ] 订单趋势的 `categories` 与 `series[].data` 长度一致，**无数据日期补 0**
- [ ] 状态分布返回全部 6 种状态（数量为 0 的也返回）
- [ ] 导出的 `.xlsx` 文件可正常打开，行数 = 当前筛选条件下的记录数，无乱码
- [ ] 导出的 Excel **不含**密码、身份证号、完整手机号
- [ ] 统计接口在 1 万条订单数据下响应时间 < 2s
- [ ] 无数据时图表显示空状态而非报错或空白
- [ ] 切换页面后 ECharts 实例被正确 `dispose()`，无内存泄漏
