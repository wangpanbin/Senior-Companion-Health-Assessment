# -*- coding: utf-8 -*-
"""往 CHANGELOG.md 插入 [V3.3] 段落。

用法：python tools/patch_changelog_v33.py
幂等：重复执行不重复插入。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = ROOT / "CHANGELOG.md"

BLOCK = """## [V3.3] · 2026-10-20 · 陪诊员工作台

把陪诊员端散落的「接单大厅 / 订单列表 / 订单执行 / 收入 / 评价」收成**一个工作台**，
并把**收入页的前端自算搬回后端**。
产品与工程契约见 [spec](docs/spec/V3.3-companion-workbench-spec.md) /
[tickets](docs/spec/V3.3-tickets.md) /
[指标字典](docs/spec/metric-dictionary.md#24-陪诊员工作台v33)。

### Added · 新增

**陪诊员自助统计（T2–T3）**

- `GET /api/statistics/companion/me` —— 区间经营概览（完成单数 / 服务时长 / 完成率 / 平均评分 / 区间收入）
- `GET /api/statistics/companion/me/trend` —— 趋势（维度 `ORDER` / `INCOME` / `RATING` × 粒度 `DAY` / `WEEK` / `MONTH`）
- `GET /api/statistics/companion/me/profile` —— 服务画像（**累计口径**：累计完成单数 / 服务老人数 / 准时率 / 累计完成率 / 累计服务时长 / 擅长服务标签）
- `GET /api/statistics/companion/me/income` —— 收入中心（订单金额 / 实际收入 / 待结算 / 已结算 / 代垫合计 / 服务费合计）
- `controller/statistics/CompanionStatisticsController` —— **独立 Controller 类**（类级 `@PreAuthorize("hasRole('COMPANION')")`）；
  不挂在 `StatisticsController`（类级 ADMIN）下面，避免「靠方法级注解覆盖类级注解」这种漏一个就泄露的写法
- `mapper/CompanionStatisticsMapper` + `service/CompanionStatisticsService(+Impl)` + 5 个 VO + `dto/CompanionTrendQuery`
- `constant/CompanionTrendDimension` —— 趋势维度枚举（与 `CompanionRankMetric` 同范式）
- **四个端点都不接收 `companionId` 入参**：陪诊员身份从登录态取，
  请求里带 `?companionId=别人的ID` 会被完全忽略（不是靠 if 挡住，是压根没有读它的地方）

**指标字典登记（T4）**

- 登记 **18 条** V3.3 指标（区间 11 条 + 累计 7 条）到 `metrics/metric-dictionary.yml`（机器面）
  与 `docs/spec/metric-dictionary.md`（人读面，v1.0 → v1.1）
- `MetricDictionaryTest` 扩展 3 条断言：覆盖 V3.3 的六个 VO / 四个端点都登记过 /
  同一指标的多个出口共用一条公式

**前端（T6–T9）**

- `views/companion/workbench.vue` —— **陪诊员工作台首页**（经营指标 / 趋势图 / 服务画像 / 收入摘要四块）
  - 四块用 `Promise.allSettled` 并行拉取，**单块失败只降级该块**，不拖垮整页
  - 趋势图的配色**运行时读 `--nl-*` 设计令牌**（canvas 不解析 CSS 变量，JS 里不留硬编码色值）
- `views/companion/income.vue` —— **去 `reduce()` 化**：KPI 改吃 `/me/income`，
  文案「累计收入」→「区间收入」，页面明示统计区间；账单明细列表保留
- `api/statistics.js` —— 4 个工作台 API 封装
- `router/routes.js` —— 新增 `companion/workbench`（`code: M-32`；`M-22` 已被「我的」占用）
- `__tests__/companion-workbench.test.js` —— **16 例**（静态扫描 6 例 + 组件渲染 10 例），
  含 3 条**扫描器自证用例**（防止静态扫描变成恒过的空转）

### Changed · 变更

- `docs/api/09-statistics-export.md` —— 新增 §8「陪诊员工作台统计（COMPANION）」，
  含请求 / 响应 / 口径表 / 错误码表；接口列表补 4 行
- `docs/api/README.md` —— 访问矩阵把 `/api/statistics/**` 拆成 ADMIN 段与 COMPANION 段两行
- `docs/spec/metric-dictionary.md` —— v1.1：新增 §2.4 陪诊员工作台（区间 11 条 + 累计 7 条）
- `docs/spec/V3.3-tickets.md` —— **新建**：开工复核结论 + 10 张工单

### Fixed · 修复

- **「累计完成单数」不再读 `companion_profile.order_count`**：
  该字段只在建档时置 0（`CompanionServiceImpl`），**运行期没有任何代码维护它**，
  只有种子脚本 `V3__seed_boundary.sql` 手工同步过 —— 读它会得到一个永远是 0 的数。
  改为从 `companion_order` 现算。
  （`companion_profile.score` 不一样：它在评价提交时于事务内刷新，是维护得住的，所以沿用。）
- **评分趋势在周 / 月粒度下按评价条数加权**：日均值不能直接平均成周均值
  （5 条 5 星 + 1 条 1 星 → 日均值的平均是 3.00，真实周均值是 4.33）

### 口径决策 · 开工复核的三处偏差

| # | 偏差 | 理由 |
|---|---|---|
| D-3.3-1 | 「本月」措辞改为「统计区间」 | 决策 3.5 要求沿用 M10 的区间约定（默认 30 天）；「本月」是固定日历月，与可传参的区间不是一回事 |
| D-3.3-2 | 概览（区间）与画像（累计）用**不同字段名** | 两边都有「完成率」，同名就是接口层面的口径分裂。字段名自带口径（`total` 前缀 = 累计） |
| D-3.3-3 | 收入页「累计收入」→「区间收入」 | 决策 3.4 要求两页口径一致、G8 要求前端零自算；旧页的「累计」是拉全量订单 `reduce()` 算的 |

### 验证 · 本版门禁结果

| 检查 | 结果 |
|---|---|
| `mvn -B verify` | ✅ **1032** 条全绿（基线 992 → 新增 40） |
| `pnpm run lint:check` | ✅ 0 error / 0 warning |
| `pnpm run test` | ✅ 16 个文件 / **155** 条全绿（基线 139 → 新增 16） |
| `pnpm run build` | ✅ 生产构建成功 |
| 越权矩阵 | ✅ ELDER / FAMILY / ADMIN 访问 4 端点一律 403；未登录 401；陪诊员本人 200 |
| 跨账号隔离 | ✅ `?companionId=别人` 返回的数据逐字不变 |
| 真库一致性 | ✅ 聚合 SQL 与逐条 Java 统计一致（完成单数 / 时长 / 收入 / 完成率 / 结算拆分） |
| 拒单不入分母 | ✅ 真库验证：种子里 301 拒过 6 单，分母不含它们 |

"""

ANCHOR = "## [V3.2] · 2026-10-20 · 老人健康协同中心"


def main() -> int:
    text = CHANGELOG.read_text(encoding="utf-8")
    if "## [V3.3] · 2026-10-20 · 陪诊员工作台" in text:
        print("已存在 [V3.3] 段落，跳过")
        return 0
    if ANCHOR not in text:
        print("未找到锚点", file=sys.stderr)
        return 1
    text = text.replace(ANCHOR, BLOCK + ANCHOR, 1)
    CHANGELOG.write_text(text, encoding="utf-8")
    print("CHANGELOG 已插入 [V3.3]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
