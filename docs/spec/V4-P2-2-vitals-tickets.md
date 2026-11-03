# V4 · P2-2 体征记录 · 工单

> 上游规格：`docs/spec/V4-P2-2-vitals-spec.md`（**待审批**）
> 分支：`feature/p2-health`
> 执行约定：按顺序推进；每张工单自测通过后单独提交；不 push；发现规格与代码冲突先停回报。

---

## 依赖图

```
T1 V9 迁移
  └─► T2 枚举 / 实体 / DTO / VO
        └─► T3 家属读写 + 去重 / 撤回
              ├─► T4 陪诊员服务中代录
              └─► T5 控制器 + 错误码
                    └─► T6 前端 API / 图表 / 家属页
                          ├─► T7 老人只读 + 陪诊员执行页
                          └─► T8 测试 / e2e / 文档 / 数据字典 / 全量验证
```

---

## T1 · V9 迁移

| 项 | 内容 |
|---|---|
| 文件 | `backend/sql/V9__vital_sign_record.sql` |
| 内容 | 规格 §4.1 DDL：表、生成列 `dedup_key`、唯一键、4 个普通索引、回滚注释 |
| 自测 | 在本地库执行一次；`SHOW INDEX` 确认 `uk_vital_dedup`；插入同分钟同类第二条应冲突；逻辑删除后可重录 |
| 完成判据 | 表存在、索引齐全、回滚语句写在文件头、V1–V8 未改 |
| 提交 | `feat(vitals): V9 体征记录表与去重唯一键` |

---

## T2 · 枚举 / 实体 / DTO / VO

| 项 | 内容 |
|---|---|
| 新建 | `constant/VitalType.java`、`constant/VitalSource.java`、`constant/GlucoseContext.java`、`entity/VitalSignRecord.java`、`mapper/VitalSignRecordMapper.java`、`dto/VitalSignCreateDTO.java`、`dto/VitalSignQuery.java`、`dto/VitalTrendQuery.java`、`vo/VitalSignVO.java`、`vo/VitalTrendVO.java`、`vo/VitalTrendPointVO.java`、`vo/VitalWithdrawVO.java` |
| 约束 | 枚举带中文标签与单位；实体继承 `BaseEntity`；DTO 不接受 `sourceType / operatorUserId / orderId / elderId` |
| 自测 | 编译通过；`VitalSignVO.of()` 对三类记录都能拼出正确 `valueText` |
| 完成判据 | 无硬编码状态字符串；字段名与规格 §5 一致 |
| 提交 | `feat(vitals): 体征枚举、实体与出入参` |

---

## T3 · 家属读写 + 去重 / 撤回

| 项 | 内容 |
|---|---|
| 文件 | `service/VitalSignService.java`、`service/impl/VitalSignServiceImpl.java` |
| 实现 | `listForFamily`、`trendForFamily`、`createForFamily`、`withdrawByFamily` |
| 校验 | 类型字段互斥、数值上下限、`diastolic < systolic`、时间不晚于 now+5min、截断秒 |
| 去重 | 捕获 `DuplicateKeyException` → `2008` |
| 归属 | `ElderService#requireAccessible` → `2001` / `2006` |
| 撤回 | 仅 `FAMILY_MANUAL` 且 `operatorUserId = 当前用户`，否则 `2010` |
| 自测 | 规格 §11.1 的 1–11、15、16 |
| 完成判据 | 单测全绿；列表倒序、趋势升序、500 点截断正确 |
| 提交 | `feat(vitals): 家属体征录入、趋势与撤回` |

---

## T4 · 陪诊员服务中代录

| 项 | 内容 |
|---|---|
| 文件 | 同 T3 的 Service，新增 `createForCompanion`、`withdrawByCompanion` |
| 校验顺序 | 订单存在 `3001` → 非本单 `3004` → 非 `IN_SERVICE` `3002` → 数值校验 |
| 写入 | `elderId = order.elderId`、`sourceType=COMPANION_SERVICE`、`orderId` |
| 撤回 | 记录须属于本单、本人、且订单仍在 `IN_SERVICE` |
| 自测 | 规格 §11.1 的 12–14 |
| 完成判据 | 不调用 `requireAccessible`；不修改既有 `ElderService` 规则 |
| 提交 | `feat(vitals): 陪诊员服务中代录与撤回` |

---

## T5 · 控制器 + 错误码

| 项 | 内容 |
|---|---|
| 修改 | `controller/family/FamilyHealthController.java`（4 端点）、`controller/elder/ElderHealthController.java`（2 端点）、`controller/execution/ExecutionController.java`（2 端点）、`common/ResultCode.java`（2008/2009/2010） |
| 注解 | `@PreAuthorize` 角色；`@Operation` 写明「只记录数值」与错误码 |
| 自测 | MockMvc 鉴权矩阵（规格 §11.2） |
| 完成判据 | 老人 POST 403；家属越权 2006；陪诊员越权 3004；不新增 SecurityConfig 规则 |
| 提交 | `feat(vitals): 体征端点与错误码` |

---

## T6 · 前端 API / 图表 / 家属页

| 项 | 内容 |
|---|---|
| 新建 | `frontend/src/api/vitalSign.js`、`components/health/VitalTrendChart.vue`、`components/health/VitalSignForm.vue`、`components/health/VitalTimeline.vue` |
| 修改 | `views/family/elder-health.vue` 新增「体征记录」区块（录入卡 + 趋势卡 + 时间线卡） |
| 风格 | 只用 `$nl-*` / `--nl-*` 令牌；内联 SVG；不新增依赖；文案禁止解释性词汇 |
| 交互 | 类型切换字段、提交 loading、`2008` 就地提示、撤回二次确认、空态、失败重试 |
| 自测 | `pnpm run lint:check` + `pnpm run test`；390px 画布目视 |
| 提交 | `feat(vitals): 家属端体征录入、趋势与时间线` |

---

## T7 · 老人只读 + 陪诊员执行页

| 项 | 内容 |
|---|---|
| 修改 | `views/elder/health.vue` 只读区块（≥18px、≥48px、无按钮）；`views/companion/execute.vue` 在 `IN_SERVICE` 显示代录卡 |
| 交互 | 老人端无录入 / 撤回；陪诊员页在其它状态隐藏整卡；提交成功后刷新本单记录 |
| 自测 | 老人模式字号与触控自查；陪诊员状态切换自查 |
| 完成判据 | 不存在任何老人写入口；文案无判断性词汇 |
| 提交 | `feat(vitals): 老人只读视图与陪诊员代录入口` |

---

## T8 · 测试 / e2e / 文档 / 全量验证

| 项 | 内容 |
|---|---|
| 单测 | `VitalSignServiceImplTest`、`VitalSignSecurityTest`（规格 §11.1 / §11.2） |
| e2e | `backend/sql/tools/e2e_vital_sign.py`（规格 §11.3），清理后无残留 |
| 文档 | `docs/api/12-elder-health.md` §6–§9；`docs/api/README.md` 错误码 + 权限矩阵；`docs/db/01/02/03` 重新生成 / 补齐 |
| 数据字典 | 新表字段、索引、生成列说明 |
| 全量 | `mvn -B verify`、`pnpm run lint:check`、`pnpm run test`、e2e、`python tools/check_secrets.py` |
| 合规 | 对新增页面 / 接口文案跑禁用词扫描，命中必须为 0 |
| 提交 | `test(vitals): 体征单测、e2e 与文档同步` |

---

## 完成对照模板（交付时填写）

| 工单 | 状态 | 提交 | 验证 |
|---|---|---|---|
| T1 | 待开始 | — | — |
| T2 | 待开始 | — | — |
| T3 | 待开始 | — | — |
| T4 | 待开始 | — | — |
| T5 | 待开始 | — | — |
| T6 | 待开始 | — | — |
| T7 | 待开始 | — | — |
| T8 | 待开始 | — | — |
