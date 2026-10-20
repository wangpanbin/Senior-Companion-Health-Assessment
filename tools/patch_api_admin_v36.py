# -*- coding: utf-8 -*-
"""把 V3.6 数据闭环的两个新端点同步到 docs/api/08-admin.md。

用法：python tools/patch_api_admin_v36.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/api/08-admin.md"

OLD_LIST = "| 20 | GET | `/api/admin/operations/health` | ADMIN | 平台健康度（V3.5） |"
NEW_LIST = (
    "| 20 | GET | `/api/admin/operations/health` | ADMIN | 平台健康度（V3.5） |\n"
    "| 21 | GET | `/api/admin/operations/gap` | ADMIN | 供给时段缺口分析（V3.6） |\n"
    "| 22 | GET | `/api/admin/operations/effect` | ADMIN | 干预效果对比（V3.6） |"
)

SECTION = """
---

## 21. 供给时段缺口分析（V3.6）

`GET /api/admin/operations/gap`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `range` | String | 否 | `LAST_7D` / `LAST_30D`（缺省 `LAST_30D`）/ `CUSTOM`；非法值 `400` |
| `startDate` / `endDate` | String | 否 | `range = CUSTOM` 时必填；闭区间，上限 366 天（超限 `9002`） |

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "periodStart": "2026-09-21", "periodEnd": "2026-10-20",
    "total": 168,
    "companionsWithoutSlots": 7,
    "items": [
      { "weekday": 6, "hour": 14, "pendingUnaccepted": 3, "availableCompanions": 1, "gap": 2 }
    ]
  }
}
```

| 字段 | 说明 |
|---|---|
| `total` | 返回的桶数（7 天 × 24 小时 = 168，**空桶也返回**） |
| `companionsWithoutSlots` | **没有可解析可服务时段**的陪诊员数（不参与供给统计，如实披露数据质量） |
| `items[].weekday` | 1=周一 … 7=周日 |
| `items[].hour` | 0–23 |
| `items[].pendingUnaccepted` | 该时段内下单但至今无人接的订单数（`PENDING`，按 `visit_time` 落桶） |
| `items[].availableCompanions` | 该时段**可服务**的陪诊员数（按其可服务时段分桶） |
| `items[].gap` | `pendingUnaccepted − availableCompanions`（**> 0 即为缺口**） |

### 三条口径规则

1. **桶的粒度固定为「周内第几天 × 小时」**，与查询区间的长度无关 ——
   只有这样，「这一周和上一周的同一时段」才可比（迭代计划 §47 的闭环数据基础）。
2. **同一陪诊员在多个时段各计 1 次，但每个桶里只计 1 次**（不是按单数计）。
3. **供给数按「可服务时段」分桶**，不是按「当前 AVAILABLE 的总人数」——
   后者会让每个桶的供给数都一样，指标没有信息量。
   没能解析出时段的陪诊员**不进任何桶**，并由 `companionsWithoutSlots` 如实披露。

> 这正是迭代计划 §47 那个闭环的数据基础：
> 「订单无人接 → 统计发现 14:00–16:00 陪诊员数量不足 → 管理员调整排班 → 下一周期重新统计 → 履约率提升」。

---

## 22. 干预效果对比（V3.6）

`GET /api/admin/operations/effect`　权限：**ADMIN**

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `metric` | String | 是 | 指标 key，**必须已在计算器注册表里**；未注册 `400` |
| `from` / `to` | String | 是 | **后**一期的区间（闭区间，上限 366 天 → `9002`） |
| `compareFrom` / `compareTo` | String | 否 | **前**一期的区间；不传时自动取「与后一期等长、紧邻其前」的区间 |

### 响应

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "metric": "operations.fulfillment_rate",
    "metricName": "履约率",
    "current":  { "from": "2026-10-01", "to": "2026-10-15", "value": "96.40%" },
    "previous": { "from": "2026-09-16", "to": "2026-09-30", "value": "92.10%" },
    "delta": "+4.30%"
  }
}
```

| 字段 | 说明 |
|---|---|
| `metric` | 指标 key |
| `metricName` | 指标中文名（**从指标字典取**，不另写一份名表） |
| `current` / `previous` | 两期的区间与取值 |
| `delta` | 差值（带正负号）；两值格式不同（如 `%` vs 计数）时返回 `"—"` |

### 同口径铁律（决策 6.2）

两期数值由**同一个计算器**算两遍得到 ——
用两个各写一遍的公式对比，比出来的差值可能全是公式差异。

未注册的指标 key 返回 `400`（**不许「猜一个算法算出来」**）。

---

## 23. 端点 ↔ 指标机器面（V3.6）

V3.6 起，「哪个端点返回了哪些指标」由
`backend/src/main/resources/metrics/endpoints.yml` 声明，
并由 `MetricEndpointContractTest` 做**三条双向校验**：

1. yml 里声明的端点**必须真实存在**（反射扫描所有 `@RestController` 的映射方法）；
2. yml 里声明的指标**必须在指标字典里**；
3. 指标字典里每条指标声明的端点**必须在 yml 里出现**。

指标字典的「人读面」与「机器面」是否逐条一致，由
`tools/check_metric_dict_parity.py` 机械校验（不一致即非零退出）。
"""

OLD_ROW = "| 20 | GET | `/api/admin/operations/health` | ADMIN | 平台健康度（V3.5） |"


def main():
    text = DOC.read_text(encoding="utf-8")
    if "/api/admin/operations/gap" in text:
        print("已存在 V3.6 章节，跳过")
        return 0
    if OLD_ROW not in text:
        print("未找到接口列表锚点", file=sys.stderr)
        return 1
    text = text.replace(OLD_ROW, NEW_LIST, 1)
    text = text.rstrip("\n") + "\n" + SECTION
    DOC.write_text(text, encoding="utf-8")
    print("docs/api/08-admin.md 已同步 V3.6 契约")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
