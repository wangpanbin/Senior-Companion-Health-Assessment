# 迭代五测试报告（2026-09-28）

> 推进方式：单人推进（convergence §二已拍板），本报告如实署名，不虚构分工。
> 原则：每个数字后面跟着能复现它的命令（同 BASELINE_2026-09-28.md 口径）。
> 统计范围：收敛迭代 T2.3（探针退场）/ T2.5（费用明细 ADR-0009）落地之后的全量复测。

## 1 · 结论速览

| 指标 | 数值 | 复现命令 | 达标 |
|---|---|---|---|
| 后端单测 | 592 tests / 0 fail / 0 error / 0 skipped | `cd backend && mvn.cmd test` | ✅ |
| 行覆盖总量 | 67.6%（基线 65.9%） | `Import-Csv backend\target\site\jacoco\jacoco.csv`（mvn test 后） | ✅（≥60%，较基线 +1.7pp） |
| 分支覆盖 | 50.9% | 同上（BRANCH_COVERED / BRANCH_MISSED） | 记录值 |
| E2E | 见 `docs/agents/reports/e2e-report.md` 与 F3 总闸门记录 | `pnpm exec playwright test` | ✅ |

测试数从基线 585 → 592：+12（用户模块越权矩阵 `UserAccessMatrixTest`）−16（探针矩阵 `PermissionMatrixTest` 退场）+11（费用明细 `OrderFeeItemTest`）+2（OrderServiceTest 构造适配后原有用例数不变）。

## 2 · 覆盖率明细

`mvn.cmd test` 后执行：

```powershell
$csv = Import-Csv "backend\target\site\jacoco\jacoco.csv"
$csv | Where-Object { $_.CLASS -match 'ServiceImpl$' } |
    ForEach-Object { [pscustomobject]@{ 类=$_.CLASS; 覆盖=("{0:P1}" -f ([double]$_.LINE_COVERED / ([double]$_.LINE_COVERED + [double]$_.LINE_MISSED))) } } |
    Sort-Object 覆盖
```

ServiceImpl 类（升序，2026-09-28 实测）：

| 覆盖率 | 类（covered/total） |
|---|---|
| 5.3% | `CaptchaServiceImpl` (1/19) |
| 25.8% | `AdminServiceImpl` (116/450) |
| 40.4% | `StatisticsServiceImpl` (137/339) |
| 61.6% | `MedicationServiceImpl` (244/396) |
| 69.5% | `OrderTransitionServiceImpl` (66/95) |
| 72.9% | `FileStorageServiceImpl` (35/48) |
| 78.7% | `OrderServiceImpl` (352/447) |
| 83.1% | `MessageServiceImpl` (128/154) |
| 84.0% | `UserServiceImpl` (21/25) |
| 84.2% | `ElderServiceImpl` (219/260) |
| 86.6% | `AuthServiceImpl` (149/172) |
| 86.8% | `ComplaintServiceImpl` (132/152) |
| 87.6% | `ReviewServiceImpl` (190/217) |
| 89.8% | `ExecutionServiceImpl` (158/176) |
| 90.0% | `ExportServiceImpl` (27/30) |
| 95.0% | `CompanionServiceImpl` (76/80) |
| 98.8% | `OrderFeeItemServiceImpl` (82/83) ← 本迭代新增 |

### 已识别空洞（如实列出，不用总量掩盖）

- `CaptchaServiceImpl` 5.3% —— 基线 5.3%，图形验证码生成强依赖 Redis/图像，Mockito 收益低，列为遗留
- `AdminServiceImpl` 25.8%（450 行最大类）—— 基线 25.8%，管理后台分支多、单测收益待排期
- `StatisticsServiceImpl` 40.4% —— 基线未单列（当时混在统计里），本轮如实列出
- `UserServiceImpl` 84.0% —— 基线 28.0%，本轮已由用户模块矩阵与资产业务测试拉起（**已关闭的空洞**）

## 3 · 遗留 Bug 清单（P0/P1/P2，P0/P1 必须为 0）

| 级别 | 描述 | 状态 |
|---|---|---|
| P0 | 无 | —— |
| P1 | 无 | —— |
| P2 | 三个覆盖率空洞（见 §2：Captcha 5.3% / Admin 25.8% / Statistics 40.4%） | 跟踪 |
| P2 | E2E 夹具无自动重建工具（复现依赖手工恢复 SQL，见 BASELINE §4.3） | 跟踪 |

本轮已修复并关闭的（证据链）：

- SCSS 变量缺失 → build 红 + 端口文档漂移 5173→5141：commit `5e808d8`
- comp025 夹具漂移：commit `53039c7`（E2E P1 收口）
- 明文口令 32 处扩散：commit `edbbc1f`（T2.4 白名单 13 处 + `check_seed_password.py` 防扩散门禁）
- 订单状态机权威未接线（可被静默破坏）：commit `7171ecd`（T2.1 / ADR-0010 单一入口）+ 反向验证实测
- 权限探针退场后回归网迁移：commit `038723a`（T2.3，8 个 `*AccessMatrixTest` 承接）
- 费用明细缺失（线下结算无对账单元）：commit `397df7a`（T2.5 / ADR-0009）

## 4 · 复现命令汇总

```powershell
# 后端全量测试 + 覆盖率
cmd /c 'cd /d "F:\test\Senior Companion Health Assessment\backend" && mvn.cmd test'
$csv = Import-Csv "backend\target\site\jacoco\jacoco.csv"
($csv | Measure-Object -Property LINE_COVERED -Sum).Sum
($csv | Measure-Object -Property LINE_MISSED -Sum).Sum

# 前端 lint / build（顺序：先 lint 后 build）
cmd /c 'cd /d "F:\test\Senior Companion Health Assessment\frontend" && pnpm.cmd lint'
cmd /c 'cd /d "F:\test\Senior Companion Health Assessment\frontend" && pnpm.cmd build'

# E2E（前置：后端 + dev server，见 docs/agents/FRONTEND_CONTRACT.md §13）
cmd /c 'cd /d "F:\test\Senior Companion Health Assessment\frontend" && pnpm.cmd exec playwright test'
```
