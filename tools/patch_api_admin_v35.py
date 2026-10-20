# -*- coding: utf-8 -*-
"""把 V3.5 运营驾驶舱的接口契约同步到 docs/api/08-admin.md。

用法：python tools/patch_api_admin_v35.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/api/08-admin.md"

OLD_LIST = "| 16 | POST | `/api/admin/order/{id}/reassign` | ADMIN | 改派陪诊员（V3.1） |"
NEW_LIST = (
    "| 16 | POST | `/api/admin/order/{id}/reassign` | ADMIN | 改派陪诊员（V3.1） |\n"
    "| 17 | GET | `/api/admin/operations/overview` | ADMIN | 平台运营概览（V3.5） |\n"
    "| 18 | GET | `/api/admin/operations/exceptions` | ADMIN | 异常中心（V3.5） |\n"
    "| 19 | GET | `/api/admin/operations/trend` | ADMIN | 运营趋势（V3.5） |\n"
    "| 20 | GET | `/api/admin/operations/health` | ADMIN | 平台健康度（V3.5） |"
)

SECTION = """
---

## 17. 平台运营概览（V3.5）

`GET /api/admin/operations/overview`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` | String | 否 | `yyyy-MM-dd`，闭区间，默认最近 30 天 |
| `endDate` | String | 否 | `yyyy-MM-dd`，闭区间，默认今天 |

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "periodStart": "2026-09-21", "periodEnd": "2026-10-20",
    "todayOrderCount": 128,
    "completedOrderCount": 113,
    "inServiceCount": 9,
    "pendingCount": 6,
    "fulfillmentRate": "96.40%",
    "avgAcceptMinutes": 6,
    "complaintRate": "1.20%",
    "averageScore": "4.82"
  }
}
```

| 字段 | 口径 |
|---|---|
| `todayOrderCount` | 今日新增订单数 |
| `completedOrderCount` | 区间内完成（`COMPLETED` + `REVIEWED`） |
| `inServiceCount` | 区间内服务中（`IN_SERVICE`） |
| `pendingCount` | 区间内待接单（`PENDING`） |
| `fulfillmentRate` | **履约率** = `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)` |
| `avgAcceptMinutes` | 平均接单时间（分钟，**整数**）；无已接单订单时 `0` |
| `complaintRate` | 投诉率 = 投诉订单数 / 区间订单数 |
| `averageScore` | 平均评分（两位小数字符串） |

---

## 18. 异常中心（V3.5）

`GET /api/admin/operations/exceptions`　权限：**ADMIN**

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "total": 16,
    "items": [
      { "type": "MANUAL_REQUIRED", "typeLabel": "需人工介入订单", "count": 3,
        "level": "DANGER", "linkUrl": "/admin/fulfillment", "referenceId": null,
        "description": "履约风险已达人工介入阈值，需要管理员协调" },
      { "type": "MATCH_WARNING", "typeLabel": "超匹配预警时间订单", "count": 6,
        "level": "WARNING", "linkUrl": "/admin/fulfillment", "referenceId": null,
        "description": "等待时间超过预警阈值，系统正在扩大匹配范围" },
      { "type": "COMPANION_COMPLAINT_RATE", "typeLabel": "投诉率异常陪诊员", "count": 2,
        "level": "WARNING", "linkUrl": "/admin/complaint", "referenceId": null,
        "description": "回溯期内投诉率超过阈值，需关注服务质量" },
      { "type": "MEDICATION_MISSED", "typeLabel": "用药漏服异常", "count": 5,
        "level": "WARNING", "linkUrl": "/admin/order", "referenceId": 1019,
        "description": "区间内漏服任务数超过阈值，最近一条见最近订单" }
    ]
  }
}
```

| 字段 | 说明 |
|---|---|
| `total` | 四类异常的数量之和 |
| `items[].type` | `MANUAL_REQUIRED` / `MATCH_WARNING` / `COMPANION_COMPLAINT_RATE` / `MEDICATION_MISSED` |
| `items[].typeLabel` | 中文名 |
| `items[].count` | 数量 |
| `items[].level` | `DANGER` / `WARNING` / `INFO` |
| `items[].linkUrl` | **真实存在**的处理页路由（前端**直接用这个值跳转**，不要自己拼） |
| `items[].referenceId` | 可选定位 ID（如最近异常订单 ID）；无则 `null` |
| `items[].description` | 一句话说明 |

### 同源铁律（决策 5.1）

前两项的数量**必须等于** V3.1 的 `/api/admin/fulfillment/summary`：

| 异常 | 对应字段 |
|---|---|
| 需人工介入订单 | `manualRequired` |
| 超匹配预警时间订单 | `warning + urgent` |

异常中心**聚合** V3.1 的风险中心，**不另建一套风险判定** ——
两套判定必然漂移（这正是 V3.2 在用药三桶上踩过的坑）。
该同源关系由 `OperationsExceptionParityTest` 锁死。

### 可达性（决策 5.4）

每个 `linkUrl` 都必须指向**真实存在**的前端路由。
「看到了但处理不了」是本版最典型的假绿 —— E2E 逐个点开验证。

---

## 19. 运营趋势（V3.5）

`GET /api/admin/operations/trend`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `dimension` | String | 否 | `ORDER` / `COMPANION_ACTIVE` / `FULFILLMENT_RATE` / `COMPLAINT` / `REVIEW` / `MEDICATION`；缺省 `ORDER`；非法值 `400` |
| `range` | String | 否 | `TODAY` / `LAST_7D` / `LAST_30D` / `CUSTOM`；缺省 `LAST_30D`；非法值 `400` |
| `startDate` / `endDate` | String | 否 | `range = CUSTOM` 时必填；闭区间，上限 366 天（超限 `9002`） |

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "dimension": "ORDER", "range": "LAST_7D", "granularity": "DAY",
    "periodStart": "2026-10-14", "periodEnd": "2026-10-20",
    "categories": ["2026-10-14", "2026-10-15"],
    "series": [ { "name": "订单量", "data": [12.00, 9.00] } ]
  }
}
```

- `categories` 与每条 `series.data` 的长度**严格一致**，**无数据的桶补 0**
  （ECharts 遇到长度不齐的数据不报错，只是折线图静默跳段 —— 最难查的一类假绿）。
- 六条维度的口径全部登记在指标字典（`docs/spec/metric-dictionary.md`）。

---

## 20. 平台健康度（V3.5）

`GET /api/admin/operations/health`　权限：**ADMIN**

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "fulfillmentRate": "96.40%",
    "avgAcceptMinutes": "6",
    "completionRate": "88.20%",
    "complaintRate": "1.20%",
    "averageScore": "4.82",
    "activeCompanionCount": 24
  }
}
```

**六个并列指标，不给合成分**（决策 5.2）：

| 字段 | 含义 |
|---|---|
| `fulfillmentRate` | 履约率 |
| `avgAcceptMinutes` | 接单速度（分钟，**字符串**） |
| `completionRate` | 完成率（**按时**：完成时间不晚于就诊时间 + 履约时长窗口） |
| `complaintRate` | 投诉率 |
| `averageScore` | 平均评分 |
| `activeCompanionCount` | 活跃陪诊员数 |

> ⚠️ **同名不同型**：概览的 `avgAcceptMinutes` 是 Integer，本接口的是 String。
> 前端**不要**写一个通用的取值函数。
>
> ⚠️ **禁止**出现「AI 平台健康指数 96」这类无法解释的数 ——
> 除非将来存在明确的书面计算公式并登记进指标字典。

### 错误码（四个端点共用）

| 码 | 含义 |
|---|---|
| 400 | `dimension` / `range` 取值非法 |
| 401 | 未登录 |
| 403 | 非管理员 |
| 9002 | 统计区间不合法（超 366 天或起止颠倒） |
"""


def main():
    text = DOC.read_text(encoding="utf-8")
    if "/api/admin/operations/overview" in text:
        print("已存在驾驶舱章节，跳过")
        return 0
    if OLD_LIST not in text:
        print("未找到接口列表锚点", file=sys.stderr)
        return 1
    text = text.replace(OLD_LIST, NEW_LIST, 1)
    text = text.rstrip("\n") + "\n" + SECTION
    DOC.write_text(text, encoding="utf-8")
    print("docs/api/08-admin.md 已同步 V3.5 契约")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
