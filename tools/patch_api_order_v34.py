# -*- coding: utf-8 -*-
"""把 V3.4 的智能调度契约同步到 docs/api/03-order.md。

用法：python tools/patch_api_order_v34.py
幂等：重复执行不重复追加。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/api/03-order.md"

# 1) 接口列表：改大厅那行 + 加候选推荐那一行
OLD_LIST = "| 3 | GET | `/api/order/hall` | COMPANION | 待接单订单大厅（需资质 `APPROVED`；可选 `sortBy`，V3.1 仅 `RECOMMEND`） |"
NEW_LIST = (
    "| 3 | GET | `/api/order/hall` | COMPANION | 待接单订单大厅（需资质 `APPROVED`；可选 `sortBy`，V3.4 开放 `RECOMMEND` / `DISTANCE` / `TIME_MATCH`） |"
)

# 2) sortBy 参数说明
OLD_PARAM = "| `sortBy` | String | 否 | 排序维度，**白名单**。V3.1 只开放 `RECOMMEND`；缺省取配置 `nianglin.fulfillment.hall-default-sort`（缺省 `RECOMMEND`） |"
NEW_PARAM = (
    "| `sortBy` | String | 否 | 排序维度，**白名单**。V3.4 开放 `RECOMMEND`（五维加权）/ `DISTANCE`（距离最近）/ "
    "`TIME_MATCH`（时间匹配）；`INCOME` 仍不开放。缺省取配置 `nianglin.fulfillment.hall-default-sort`（缺省 `RECOMMEND`） |"
)

# 3) 错误码
OLD_ERR = "| 400 | `sortBy` 不在白名单内 —— 含 V3.4 才开放的 `DISTANCE` / `TIME_MATCH` / `INCOME`，以及任何其它字面量。**错误消息中说明该维度将在后续版本开放** |"
NEW_ERR = (
    "| 400 | `sortBy` 不在白名单内 —— 含仍不开放的 `INCOME`，以及任何其它字面量。"
    "**错误消息中回显传进来的取值**；`INCOME` 单独给一句人话「收益优先需要真实结算金额口径，本版未开放」 |"
)

# 4) 排序实现说明段（V3.1 的三级键 → V3.4 的五维加权）
OLD_SORT_NOTE = """> **范围声明（V3.1）**：本版**只实现一种排序**。距离 / 时间匹配 / 收益优先三个维度
> 在 V3.4 的加权规则引擎中随权重配置一并开放 —— 现在给出四种排序必然在 V3.4 被推翻。
> 因此大厅页在只有一个排序项时**不显示排序选择器**，改为一行说明「更多排序方式后续开放」。"""

NEW_SORT_NOTE = """### 排序实现（V3.4 · 五维加权）

排序**不再由 SQL 的 `ORDER BY` 决定**，而是由匹配引擎在 Java 侧算分后排序：

| 维度 | 权重（配置化 `nianglin.matching.*.weight`） | 得分 |
|---|---|---|
| 区域 | 30% | 区域码相等 = 1.0；同市不同区 = 0.5；跨市 = 0；缺区域码时回退自由文本包含式 |
| 时间 | 25% | 就诊时间落在可服务时段内 = 1.0；同日但时段外 = 0.5；其它 = 0 |
| 距离 | 20% | `1 − Haversine距离 / 满量程`；**陪诊员无坐标时记 0 并标注「未填写坐标」，绝不按 (0,0) 推断** |
| 服务能力 | 15% | 该陪诊员在该科室的已完成单数 / 满量程 |
| 评分 | 10% | 平均评分 / 5 |

- **权重之和必须等于 1**，否则应用启动失败（`MatchingProperties#validate`）——
  错误的权重不会报错，只会让排序悄悄变得不合理。
- 同分时的兜底键：**就诊时间升序 → ID 升序**（保证分页不重不漏）。
- **响应结构零变更**：`total` / `page` / `size` / `pages` / `records` 的结构与语义都不变，
  `total` 仍是「满足大厅筛选条件的待接单订单数」；变的只有「哪几条排在前面」。
- 候选集整批加载后**内存分页**，上限由 `nianglin.matching.hall-candidate-limit`（默认 500）控制，
  超限时服务端记 warn 日志（**不静默截断**）。
- `DISTANCE` 在**没有陪诊员填坐标**时所有候选距离分并列 → 回落就诊时间升序。
  这是如实反映「现在没有距离数据」，等陪诊员开始填坐标即自动生效，不需要改代码。

> 详见 [ADR-0016](../adr/0016-matching-structured-and-explainable.md)。"""

# 5) 新增候选推荐端点章节
NEW_SECTION = """
---

## 12. 候选陪诊员推荐（V3.4 · 智能调度）

`GET /api/order/{id}/recommend-companions`　权限：**FAMILY**（仅下单家属）

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | Long | 是 | 订单 ID（路径参数） |
| `limit` | Integer | 否 | 返回条数，默认 10，上限 20；非法值（< 1）落到默认值 |

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "orderId": 1001,
    "total": 12,
    "candidates": [
      {
        "companionId": 301,
        "companionName": "李*军",
        "totalScore": 86,
        "regionScore": 100,
        "timeScore": 100,
        "distanceScore": 0,
        "skillScore": 60,
        "ratingScore": 96,
        "reasons": ["同服务区域", "就诊时间在可服务时段内", "服务过该科室 12 单"],
        "notes": ["未填写坐标，距离维度记 0"]
      }
    ]
  }
}
```

| 字段 | 说明 |
|---|---|
| `total` | **通过过滤链的候选总数**（不只是返回的 Top N） |
| `candidates` | Top N 候选，按 `totalScore` 降序，同分按 `companionId` 升序 |
| `companionName` | **脱敏姓名**（`李建军` → `李*军`）；**不返回**手机号 / 身份证号 |
| `totalScore` | 加权总分（0–100 整数） |
| `regionScore` / `timeScore` / `distanceScore` / `skillScore` / `ratingScore` | 各维度得分（0–100 整数） |
| `reasons` | **命中理由**（面向人）；不允许出现「没有理由的分数」 |
| `notes` | **降级说明**（因数据缺失而没算出来的维度），没有则为空数组 |

### 过滤链（顺序固定、逐级短路）

```
1. 工作状态 = AVAILABLE
2. 资质 = APPROVED
3. 区域匹配（区域码 ∩；缺区域码时回退自由文本包含式）
4. 时段可服务（就诊时间落在可服务时段内；未登记时段 → 降级放行，不淘汰）
5. 档期冲突（该时段已有在手订单）
```

> 「缺资料」与「不匹配」是分开的：只是没填区域码 / 没登记时段的候选人会被**放行并降级**，
> 而不是被淘汰 —— 淘汰一个只是没填资料的人，等于让「资料填得全」变成接单的前提。

### 错误码

| 码 | 含义 | 触发条件 |
|---|---|---|
| 400 | 请求参数错误 | `limit` 非法（落到默认值，不报错）—— 实际不触发 |
| 401 | 未登录 | 无令牌 |
| 403 | 无权限 | 角色不是 `FAMILY` |
| 3001 | 订单不存在 | — |
| 3004 | 无权操作该订单 | **不是自己下的单**（即使是家属角色） |

> ⚠️ **推荐 ≠ 派单**：本接口**不产生任何订单状态变更**，接单权始终在陪诊员手里
> （roadmap 决策 4.4）。界面必须明确标注「推荐仅供参考，最终由陪诊员本人确认接单」。
"""


def main():
    text = DOC.read_text(encoding="utf-8")
    if "/recommend-companions" in text:
        print("已存在候选推荐章节，跳过")
        return 0

    changed = False
    for old, new in ((OLD_LIST, NEW_LIST), (OLD_PARAM, NEW_PARAM), (OLD_ERR, NEW_ERR),
                     (OLD_SORT_NOTE, NEW_SORT_NOTE)):
        if old in text:
            text = text.replace(old, new, 1)
            changed = True
        else:
            print("⚠️ 锚点未找到（跳过）：" + old.splitlines()[0][:60], file=sys.stderr)

    text = text.rstrip("\n") + "\n" + NEW_SECTION
    DOC.write_text(text, encoding="utf-8")
    print("docs/api/03-order.md 已同步 V3.4 契约" + ("" if changed else "（锚点部分未命中）"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
