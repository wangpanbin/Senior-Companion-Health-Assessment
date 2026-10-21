# docs/reports/ · 验证证据区

> **这里只放「跑过什么、结果是什么」的记录**，不放计划（计划在 [`../plan/`](../plan/)）。
> 每份报告的数字后面都应跟着能复现它的命令 —— 拿不出复现步骤的数字不算证据。

## 联调总报告

| 文件 | 内容 |
|---|---|
| [`TEST_REPORT.md`](TEST_REPORT.md) | 前后端联调**全功能**测试报告（六层判据：端口身份闸门 / 浏览器套件 / 接口探针 / 写路径探针 / 访问专项 / 助手双模式 + 单元级门禁） |

## 逐迭代测试报告

`iteration-v3.1-test-report.md` … `iteration-v3.7-test-report.md` —— 每个 V3.x 版本交付时的测试记录。
另有 `iteration-test-report-1/2/3.md` 与 `iteration-5-test-report.md`（早期迭代）。

## 基线与收尾

| 文件 | 内容 |
|---|---|
| [`BASELINE_2026-09-28.md`](BASELINE_2026-09-28.md) | 收敛迭代前的**实测基线** —— 每个数字带复现命令。**凭据单一真源**（§6.1） |
| [`CLOSEOUT_2026-09-29.md`](CLOSEOUT_2026-09-29.md) | 五阶段收敛后的**收尾复核** + 本轮修复清单 |
| [`V3.3-V3.6-iteration-summary.md`](V3.3-V3.6-iteration-summary.md) | V3.3 – V3.6 四版迭代的合并总报告 |

## E2E 与压测

| 文件 | 内容 |
|---|---|
| [`e2e-report.md`](e2e-report.md) | E2E 执行快照（原生成于被 gitignore 的 `reports/playwright/`，此处为入库副本） |
| `e2e-branch-report-2026-09-23.md` · `e2e-branch-bugfix-report-2026-09-23.md` · `e2e-sisterbug-fix-report-2026-09-23.md` | 分支级 E2E 回归记录 |
| [`pressure-test-m4-accept.md`](pressure-test-m4-accept.md) · [`pressure-test-m5-m6.md`](pressure-test-m5-m6.md) | 压测记录（接单并发 / 消息与费用） |

## 截图

[`screenshots/`](screenshots/) —— 15 张关键页面截图（真实页面，非手绘）。
