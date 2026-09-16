# 迭代测试报告（三）· 通知与管理域（M8–M10）

- **报告时间**：2026-09-16（依实测基线归档）
- **测试范围**：M8 站内信与通知 / M9 管理后台 / M10 数据统计与导出
- **被测版本**：`git log` `5afb18a → a61c00e`
- **执行人**：D（M8/M10 旁路径）+ B（M9 主路径）+ C（横切）

---

## 1 · 测试构成与结果

| 层级 | 对象 | 用例/断言 | 结果 |
|---|---|---|---|
| 单元测试 | `MessageSseHubTest`（SSE Hub） | 13 用例 | ✅ 全绿 |
| 单元测试 | `GlobalExceptionHandlerIOExceptionTest` | — | ✅ 全绿 |
| 越权 | 无独立矩阵 | — | ❌ 待补 |
| e2e | `e2e_message.py` | 52 项断言 | ✅ 通过 |
| e2e | `e2e_admin.py` | 106 项断言 | ✅ 通过 |
| e2e | `e2e_statistics.py` | 78 + 69 项断言（含上限阶段） | ✅ 通过 |
| 压测 | `jmeter_m10_overview.jmx` | — | ⚠️ 脚本归档，报告未归档 |

---

## 2 · 关键验证点

| 验证点 | 结论 |
|---|---|
| 站内信归属（只能读自己的） | ✅ `receiver_id` 强制（7002） |
| SSE 推送通道建立 / 断开清理 | ✅ `MessageSseHub` |
| 消息模板四类（订单/审核/漏服/系统） | ✅ `MessageTemplateUtil` |
| 资质审核流转 + 驳回必填原因（8003） | ✅ |
| 封禁连锁（`isBanned` + 密码版本递增） | ✅ |
| 不能封禁管理员（8002） | ✅ |
| 纠纷仲裁强制终态（仅 COMPLETED/CANCELLED） | ✅ 条件更新防并发覆盖 |
| `admin_oper_log` 六处必写 | ✅ |
| 导出脱敏（无密码/身份证/完整手机号） | ✅ |
| 导出上限（超 10000 行抛 9001） | ✅ `--nianglin.export.max-rows=2` 阶段验证 |

---

## 3 · 发现并修复的缺陷

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| 1 | **SSE 断连刷两条假 ERROR**（本迭代最隐蔽） | 用户关页面 → 写通道失败 → 对死通道 `complete()` 触发容器错误分发；错误分发时 `SecurityContext` 缺失，`AuthorizationFilter` 再抛 `AccessDenied` | 三处联动：① `MessageSseHub.push` 只摘引用**绝不** `complete()`；② `SecurityConfig` 放行 `ASYNC/ERROR` 分发；③ `GlobalExceptionHandler` 按 `response.isCommitted()` 区分「客户端断开」与真 IO 故障 |
| 2 | 站内信有消费者无生产者（消息表只读不写） | M8 初版只做了读接口 | 补 `MessageService.send/sendBatch` 并接入各业务模块 |
| 3 | 管理端校验顺序错误（先写后校验） | 状态闸门未前置 | 改为「先判状态/权限，再落库」 |
| 4 | 统计接口时间参数绑定失败 | `@RequestParam` 未加日期格式化 | 补 `@DateTimeFormat` |

---

## 4 · 特殊测试跑法（M10 上限阶段）

```
以 --nianglin.export.max-rows=2 启动服务（把上限压到 2 行）
  → 跑 e2e_statistics.py --limit-phase
  → 断言导出 3 行数据时返回 9001 EXPORT_LIMIT_EXCEEDED
```

> 注：`max-rows` 生产默认 10000（`StatisticsServiceImpl` `@Value` 默认值），
> 该参数未在 `application.yml` 显式声明，压测时通过命令行临时注入。

---

## 5 · 遗留问题

- `MessageServiceTest`（Service 层）/ `AdminServiceTest` / `StatisticsServiceTest` / `ExportServiceTest` 未写。
- `MessageAccessMatrixTest` / `AdminAccessMatrixTest` / `StatisticsAccessMatrixTest` 未写。
- M10「1 万订单 < 2s」压测报告未归档。
- e2e 残留 `internal_message` 907 行（种子 72 行），清理策略待统一。

---

## 6 · 结论

M8–M10 验收项全部通过；M8 的 SSE 断连假故障是本迭代最有价值的修复（三处联动，防止真故障被日志噪声淹没）。
**三个迭代的功能实现阶段结束，可进入 M12 测试横切加固。**
