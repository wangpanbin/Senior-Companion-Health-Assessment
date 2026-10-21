# 迭代测试报告（一）· 骨架与账号域（M0–M4）

- **报告时间**：2026-09-16（依实测基线归档，原迭代报告未存档）
- **测试范围**：M0 骨架 / M1 建表 / M2 认证 / M3 用户档案 / M4 陪诊订单
- **被测版本**：`git log` `6bc37c5 → 666f108 / c883c52`
- **执行人**：B（后端）+ C（横切测试）

---

## 1 · 测试构成与结果

| 层级 | 对象 | 用例/断言 | 结果 |
|---|---|---|---|
| 单元测试 | `AuthServiceTest` | 集群内 | ✅ 全绿 |
| 单元测试 | `ElderServiceTest` / `CompanionServiceTest` | 集群内 | ✅ 全绿 |
| 单元测试 | `OrderServiceTest`（含 50 并发抢单） | 集群内 | ✅ 全绿 |
| 单元测试 | `OrderStatusTest` / `MaskUtilTest` / `ResultTest` / `ElderReadOnlyInterceptorTest` | 集群内 | ✅ 全绿 |
| 越权矩阵 | `PermissionMatrixTest`（4 角色 × 3 类接口） | ≥12 | ✅ 全绿 |
| 越权矩阵 | `ElderOwnershipMatrixTest` | — | ✅ 全绿 |
| 越权矩阵 | `OrderAccessMatrixTest` | — | ✅ 全绿 |
| e2e | `e2e_auth.py` | 40 项断言 | ✅ 通过 |
| e2e | `e2e_user_profile.py` | 86 项断言 | ✅ 通过 |
| e2e | `e2e_order.py` | 78 项断言 | ✅ 通过 |
| 压测 | `jmeter_m4_accept.jmx`（50 并发抢单） | — | ✅ 脚本归档，抢单仅 1 成功 |

**单元测试总量**：`mvn test` 291 用例全绿（该数字为全项目口径，跨迭代累计）。

---

## 2 · 关键验证点

| 验证点 | 结论 |
|---|---|
| 密码 BCrypt 存储（`$2a$` 前缀） | ✅ |
| 手机号 / 身份证脱敏返回 | ✅ `MaskUtil` |
| 老人账号只读（写操作服务端拦截） | ✅ `ElderReadOnlyInterceptor` + `@AllowElderWrite` |
| 资质未通过的陪诊员不能接单 | ✅ `COMPANION_NOT_AUDITED(2003)` |
| 家属 A 不能读家属 B 的档案 | ✅ Service 层归属校验 |
| 50 并发抢单仅 1 人成功 | ✅ `@Version` 乐观锁，失败方收 3003 |
| 服务小结拦截诊断/处方表述 | ✅ `ComplianceCheckUtil` 命中即拒 |
| 订单号并发不重复 | ✅ Redis INCR + 2 天 TTL |

---

## 3 · 发现并修复的缺陷

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| 1 | **时序攻击**：登录失败路径提前返回，可据响应时间枚举账号 | 密码比对短路 | `f3b1416` 统一走完整 BCrypt 比对 |
| 2 | **JWT 密码版本校验缺失**：改密后旧令牌仍可用 | 未比对 `ver` | `f3b1416` 令牌携带并校验密码版本 |
| 3 | **登出未真正失效令牌** | 仅前端删 token | `f3b1416` `TokenStore` 黑名单（jti） |
| 4 | **并发写入非原子**：先 SELECT 再无条件 UPDATE | 竞态 | `f3b1416` 改条件更新 / 乐观锁 |

---

## 4 · 遗留问题（转入后续迭代）

- e2e 脚本会残留测试数据（`e2e_order.py` 生成站内信未清理），需统一清理策略。
- 迭代一/二阶段未配置覆盖率门禁（留待 M12）。

---

## 5 · 结论

M0–M4 验收项全部通过；账号域安全边界（认证 → 角色 → 归属）三层到位。
**可进入下一迭代（M5–M7）。**
