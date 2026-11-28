# DOC-2 · 权限矩阵 ELDER「确认服药」一格

> 批次：**B（用药与文档域）** · 级别：DOC · 来源：`reports/business-closure-final-assessment-2026-11-28.md` §DOC-2
> 错误码：**不新增**

## Problem Statement

`docs/api/README.md` §五 角色与权限矩阵里，用药管理那一行写的是：

```
| 用药管理 /api/medication/** | 读 / 确认 | 读写 / 确认 | 确认 | 读 |
```

ELDER 列写的是「读 / **确认**」——即老人本人可以确认服药。

但项目里另外四处全都说不行：

| 位置 | 说法 |
|---|---|
| `docs/api/README.md` §2.4 | 「**这是一条不可逾越的产品规则**：老人不做任何写操作，全部由家属代操作」 |
| `MedicationController` 类注释 | 「确认服药 … **老人本人不行**（只读账号）」 |
| `MedicationController#confirm` | `@PreAuthorize("hasAnyRole('FAMILY','COMPANION')")` |
| `docs/api/05-medication.md` §9 | 「权限：FAMILY / COMPANION（老人本人不可操作）」 |
| 前端 `elder/medication.vue` | 不渲染确认按钮，明写「服药确认请家属在『家属端 · 用药管理』完成」 |

**代码侧四处一致，孤立的只有矩阵那一格。**

## Solution

改矩阵一格：ELDER 列从「读 / 确认」改为「读」。

**同时补一句必要的口径说明**——这才是本单真正有价值的部分：

> `medication_task.confirm_by` 落库，但老人点不了，因此现有数据里 `confirm_by` **必然是 FAMILY / COMPANION**。这意味着「用药依从性」在当前数据模型下只能表达为「**家属/陪诊员代确认率**」，无法表达「老人实际服药率」。

这句话应写进 `docs/spec/metric-dictionary.md` 的用药指标段，作为指标解释的边界声明。答辩时主动讲清这一点，比被追问好。

## User Stories

1. 作为评审人，我想权限矩阵与项目宪法一致，这样我不会以为老人能写数据。
2. 作为评审人，我想矩阵与代码一致，这样我不会在验收时发现「文档说能做、实际 403」。
3. 作为前端开发者，我想按矩阵实现时不会给老人端加确认按钮，这样不会白做。
4. 作为前端开发者，我想知道老人端为什么没有确认入口，这样我不会以为是漏做。
5. 作为测试者，我想看到 ELDER 调确认接口返回 403，这样矩阵的表述能被验证。
6. 作为产品，我想「老人只读」这条规则在所有文档里说同一句话。
7. 作为产品，我想「依从性」指标的口径边界被写清楚，这样答辩时我能主动说明。
8. 作为运营，我想知道我的「漏服率」其实是「代确认口径」，这样我不会误读数据。
9. 作为运营，我想如果将来要给老人自己确认，知道要改哪几处。
10. 作为数据分析，我想知道 `confirm_by` 的取值范围，这样我的分析不会假设有 ELDER。
11. 作为开发者，我想矩阵改动不涉及代码，这样零回归。
12. 作为开发者，我想 `@PreAuthorize` 不用改（它是对的）。
13. 作为文档维护者，我想这次改动后 `gate_check.py` 仍绿。
14. 作为审计者，我想「这一格错了多久」不必追溯，改对即可。
15. 作为新成员，我想读矩阵就能知道谁能做什么，不踩坑。

## Implementation Decisions

### 改文档（两处）

1. `docs/api/README.md` §五 权限矩阵，用药管理行：
   ```
   | 用药管理 `/api/medication/**` | 读 | 读写 / 确认 | 确认 | 读 |
   ```
   并加脚注：`* ELDER 不可确认服药，见 §2.4 老人账号只读规则与 §4.6 错误码（401 场景）`。

2. `docs/spec/metric-dictionary.md` 的用药指标段，新增一条边界声明（不新增指标 key，只加说明）：
   > **口径边界**：`medication_task.confirm_by` 恒为 FAMILY / COMPANION（老人账号不可执行写操作）。因此「用药依从性 / 漏服率」实为「**代确认口径**」，反映的是家属/陪诊员的确认行为，不等于老人的实际服药行为。解读该指标时必须带上这个前提。

### 不改的东西

- **不改 `@PreAuthorize`**（`hasAnyRole('FAMILY','COMPANION')` 是对的）。
- **不改前端**（`elder/medication.vue` 已正确不渲染确认按钮）。
- 不改 `MedicationController` 的注释与 `@Operation`（都已正确）。

### 顺带核对（本单建议做，成本低）

- 扫描权限矩阵其余行，确认没有第二处同类错误。已知需要复核的：
  - 「体征记录」行：ELDER 写「读（仅自己档案，无写入口）」——与 `ElderHealthController` 的 `hasRole('ELDER')` + 只读拦截器一致 ✅；
  - 「评价与投诉」行：ELDER「读」（`POST /api/review` 会被只读拦截器拒）✅；
  - 「产品反馈」行：ELDER 写「—」，并说明被只读拦截器拒 ✅。

## Testing Decisions

### 好测试

文档改动无单测。验收：

1. `python tools/ai_flow/gate_check.py` 全绿（`md` + `error-codes`）。
2. **一条行为回归**（可选，但建议加）：`ELDER 调 POST /api/medication/task/{id}/confirm 应返回 403`——这条本来就该有，若 `MedicationAccessMatrixTest` 未覆盖则补上。
   - 这条测试的价值：让「矩阵那一格」有对应的可执行断言，下次再有人改错格会先被测试提醒。

### 测试模块

扩展 `MedicationAccessMatrixTest`（若已有 ELDER 403 断言则跳过）

### 垂直切片

1. 改矩阵一格。
2. 改指标字典加边界声明。
3. 跑 `gate_check.py`。
4. （可选）补 ELDER 403 行为测试。

## Out of Scope

- **不改产品设计**（不让老人自己确认）。若将来要改，需同步改：§2.4 只读规则、`ElderReadOnlyInterceptor` 的 `@AllowElderWrite`、`@PreAuthorize`、前端入口、指标字典口径——共 5 处，是一个独立决策。
- 不改指标计算公式。
- 不改前端。

## Further Notes

- **本单在初稿里被高估了**（定为「最影响答辩可信度的一条，需要跨三层决策」）。复核后确认：**代码侧四处一致，孤立的只有矩阵一格**，改一格即可。真正该补的是指标字典那句边界声明。
- 但「改一格」不等于「不紧急」：答辩现场翻到矩阵那一格，问「老人自己能不能点已服用」，答「不能，矩阵写错了」比「不能，因为老人只读」弱得多。
