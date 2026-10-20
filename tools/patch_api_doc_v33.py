# -*- coding: utf-8 -*-
"""往 docs/api/09-statistics-export.md 追加 V3.3 陪诊员工作台的接口契约。

用法：python tools/patch_api_doc_v33.py
幂等：重复执行不重复追加。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/api/09-statistics-export.md"

SECTION = """
---

## 8. 陪诊员工作台统计（V3.3）

> 覆盖模块：**V3.3 陪诊员工作台** · 契约见 [`docs/spec/V3.3-companion-workbench-spec.md`](../spec/V3.3-companion-workbench-spec.md)
>
> **与本文档其它接口的差别**：§1 ~ §7 是**管理员视角的全平台统计**，
> §8 是**陪诊员视角的自己的经营数据**。两者用**两个独立的 Controller 类**：
> `StatisticsController`（类级 ADMIN）与 `CompanionStatisticsController`（类级 COMPANION）。
> 不混在同一个类里，是因为「靠方法级注解覆盖类级注解」这种写法漏一个方法就是一次数据泄露。

### 8.0 通用约定

| 项目 | 约定 |
|---|---|
| 权限 | 全部接口仅 `COMPANION`（类级 `@PreAuthorize("hasRole('COMPANION')")`） |
| 陪诊员身份 | **从登录态取**，四个端点都**不接收 `companionId` 入参**；请求里带 `?companionId=xxx` 会被完全忽略 |
| 时间区间 | `startDate` / `endDate`（`yyyy-MM-dd`，闭区间），默认最近 30 天，上限 366 天，超限 `9002` |
| 空数据 | 返回 0 或空数组，**不返回 `null`，不报错** |
| 比率 | 百分数字符串，两位小数，如 `"93.00%"` |
| 金额 | 两位小数字符串，如 `"3680.00"` |
| 「已完成」 | `COMPLETED` + `REVIEWED`（评价只是完成之后的后续动作，把已评价的单排除会让完成率随着用户评价而下降） |
| 区间口径 | 概览 / 趋势 / 收入 = **统计区间内**；画像 = **历史累计**（字段名带 `total` 前缀） |
| 时间字段 | 区间口径的订单一律按 **`visit_time`** 过滤（与 V3.2 家属端 `elder.visit.order_count` 同一时间字段，两端必须同源） |

### 8.1 工作台概览

`GET /api/statistics/companion/me`　权限：**COMPANION**

#### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` | String | 否 | `yyyy-MM-dd`，默认 30 天前（含） |
| `endDate` | String | 否 | `yyyy-MM-dd`，默认今天（含） |

#### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "periodStart": "2026-09-21",
    "periodEnd": "2026-10-20",
    "completedOrderCount": 28,
    "serviceDurationMinutes": 2520,
    "completionRate": "93.00%",
    "averageScore": "4.80",
    "periodIncome": "3680.00"
  }
}
```

| 字段 | 口径 |
|---|---|
| `completedOrderCount` | 区间内 `COMPLETED` + `REVIEWED` 的订单数 |
| `serviceDurationMinutes` | 区间内已完成订单的 `finish_time − start_time` 之和（分钟） |
| `completionRate` | `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)`，**拒单不计入分母** |
| `averageScore` | `companion_profile.score`（**累计口径，不重算**） |
| `periodIncome` | 区间内已完成订单的 `SUM(COALESCE(actual_fee, fee))` |

### 8.2 工作台趋势

`GET /api/statistics/companion/me/trend`　权限：**COMPANION**

#### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` / `endDate` | String | 否 | 同上 |
| `dimension` | String | 否 | `ORDER`（默认）/ `INCOME` / `RATING`；非法值返回 `400` |
| `granularity` | String | 否 | `DAY`（默认）/ `WEEK` / `MONTH`；非法值返回 `400` |

#### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "dimension": "ORDER",
    "granularity": "DAY",
    "periodStart": "2026-09-21",
    "periodEnd": "2026-10-20",
    "categories": ["2026-09-21", "2026-09-22"],
    "series": [
      { "name": "完成订单", "data": [3.00, 0.00] }
    ]
  }
}
```

| 维度 | 序列值 |
|---|---|
| `ORDER` | 区间内完成订单数 |
| `INCOME` | 区间内收入（`SUM(COALESCE(actual_fee, fee))`） |
| `RATING` | 区间内有效评价的平均分；**周 / 月粒度按评价条数加权**（日均值不能直接平均） |

> `categories` 与每条 `series.data` 的长度**严格一致**，无数据的桶**补 0** ——
> ECharts 遇到长度不齐的数据不报错，只是折线图静默跳段，是最难查的一类假绿。

### 8.3 服务画像

`GET /api/statistics/companion/me/profile`　权限：**COMPANION**

无请求参数。**历史累计口径，不随统计区间浮动。**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "averageScore": "4.80",
    "totalCompletedOrderCount": 128,
    "totalServedElderCount": 46,
    "punctualityRate": "92.00%",
    "totalCompletionRate": "94.81%",
    "totalServiceDurationMinutes": 8640,
    "expertise": [
      { "department": "普通门诊", "orderCount": 12 }
    ]
  }
}
```

| 字段 | 口径 |
|---|---|
| `totalCompletedOrderCount` | 累计 `COMPLETED` + `REVIEWED` 订单数 |
| `totalServedElderCount` | 累计服务过的**不同**老人数（`COUNT(DISTINCT elder_id)`） |
| `punctualityRate` | 分子 = 存在 `ARRIVE` 打卡且 `checkin_time <= visit_time` 的订单数；分母 = 已接单订单数。**没有 `ARRIVE` 打卡视为不准时**（没打卡本身就是履约问题） |
| `totalCompletionRate` | 累计 `(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)` |
| `totalServiceDurationMinutes` | 累计服务时长（分钟） |
| `expertise` | 科室维度 TOP 5；**样本 < 5 单的科室不展示**（接过 1 单就自称「擅长」是噪声） |

### 8.4 收入中心

`GET /api/statistics/companion/me/income`　权限：**COMPANION**

#### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startDate` / `endDate` | String | 否 | 同上 |

#### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "periodStart": "2026-09-21",
    "periodEnd": "2026-10-20",
    "orderAmount": "4000.00",
    "actualIncome": "3680.00",
    "unsettledAmount": "1200.00",
    "settledAmount": "2480.00",
    "advanceTotal": "150.00",
    "serviceTotal": "3650.00"
  }
}
```

| 字段 | 口径 |
|---|---|
| `orderAmount` | 区间内已完成订单的 `SUM(fee)`（合同服务费） |
| `actualIncome` | 区间内已完成订单的 `SUM(COALESCE(actual_fee, fee))`（未结算时回退合同服务费） |
| `unsettledAmount` | `payment_status = 'UNPAID'` 的合计 |
| `settledAmount` | `payment_status = 'SETTLED'` 的合计 |
| `advanceTotal` | `order_fee_item` 中 `item_type = 'ADVANCE'`（代垫）的合计 |
| `serviceTotal` | `order_fee_item` 中 `item_type = 'SERVICE'`（服务费）的合计 |

**恒等式**：`unsettledAmount + settledAmount == actualIncome`。

> ⚠️ 本接口**没有「平台服务费」这个字段** —— 现有费用模型里只有 `fee`（合同服务费）、
> `actualFee`（结算金额）与费用项 `ADVANCE` / `SERVICE` 两型（ADR-0009）。
> 一期只有**线上记账 + 线下结算**，不涉及在线支付 / 提现。

### 8.5 错误码

| 码 | 含义 | 触发条件 |
|---|---|---|
| `400` | 请求参数错误 | `dimension` / `granularity` 取值非法 |
| `401` | 未登录 | 无令牌 |
| `403` | 无权限 | 角色不是 `COMPANION` |
| `9002` | 统计时间区间不合法 | 区间长度 > 366 天，或开始日晚于结束日 |
"""

TABLE_ROWS = """| 7 | GET | `/api/statistics/export/user` | ADMIN | 导出用户 Excel |
"""

NEW_TABLE_ROWS = """| 7 | GET | `/api/statistics/export/user` | ADMIN | 导出用户 Excel |
| 8 | GET | `/api/statistics/companion/me` | COMPANION | 陪诊员工作台概览（V3.3） |
| 9 | GET | `/api/statistics/companion/me/trend` | COMPANION | 陪诊员工作台趋势（V3.3） |
| 10 | GET | `/api/statistics/companion/me/profile` | COMPANION | 陪诊员服务画像（V3.3） |
| 11 | GET | `/api/statistics/companion/me/income` | COMPANION | 陪诊员收入中心（V3.3） |
"""


def main() -> int:
    text = DOC.read_text(encoding="utf-8")

    if "/api/statistics/companion/me/profile" in text:
        print("已存在 V3.3 章节，跳过")
        return 0

    if TABLE_ROWS not in text:
        print("未找到接口列表锚点，终止", file=sys.stderr)
        return 1
    text = text.replace(TABLE_ROWS, NEW_TABLE_ROWS, 1)

    # 权限行补一句：本文档含 ADMIN 与 COMPANION 两类
    text = text.replace(
        "| 权限 | 全部接口仅 `ADMIN` |",
        "| 权限 | §1 ~ §7 仅 `ADMIN`；§8（V3.3 陪诊员工作台）仅 `COMPANION` |",
        1,
    )

    text = text.rstrip("\n") + "\n" + SECTION
    DOC.write_text(text, encoding="utf-8")
    print("已追加 V3.3 接口契约")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
