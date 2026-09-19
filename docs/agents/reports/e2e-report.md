> 📌 **入库快照说明**
>
> 本文件是 E2E 首轮跑测报告的**逐字快照**,原文件由 Playwright 套件生成于
> `reports/playwright/e2e-report.md` —— 注意 `reports/` 被根 `.gitignore` 忽略
> （那里还有 `tokens.json` / `storageState` 等**真实 JWT**，属入库红线），
> 所以干净 clone 上看不到原文件。本副本放在 `docs/agents/reports/` 下，
> 与 `iteration-test-report-*.md` 同级，便于 clone 内直接阅读 §4 的 F-01…F-06 条目。
> 各 spec / 代码注释里的「报告出处：reports/playwright/e2e-report.md §F-xx」指的就是本文件。

---
# 银龄伴诊 · 全功能 Playwright E2E 测试报告

> 执行日期：2026-09-19 · 主机：BIN · 仓库：`F:\test\Senior Companion Health Assessment`
> 套件：`frontend/e2e/`（`@playwright/test` 1.63.0）· 浏览器：本机 Chrome 153（`channel: chrome`，不下载 Chromium）
> 结果：**104 passed / 0 failed**（含 7 条账号登录 setup）· 数据回滚 `verify` **全部回到基线 ✅**

---

## 1 · 结论

以真实 UI 登录（验证码走 Redis 旁路）、真实种子数据、真实写接口，把 4 角色全部页面与两条核心闭环跑通，
并在跑完把数据库与 Redis 还原到跑前状态。**未发现阻断级缺陷**，但记录 6 项值得处理的发现（见 §4）。

| 模块 | 用例数 | 结果 |
|---|---|---|
| setup（7 账号真实登录） | 7 | ✅ |
| 01 M2 认证 / 登录页 / 注销 | 15 | ✅ |
| 02 M2·M12 越权矩阵 | 13 | ✅ |
| 03 M11 老人端 + 适老化 | 9 | ✅ |
| 04 M4 订单 + M7 评价投诉 | 12 | ✅ |
| 05 M4 接单 + M5 执行 | 9 | ✅ |
| 06 M6 用药 + 合规 | 4 | ✅ |
| 07 M8 站内信 | 3 | ✅ |
| 08 M9 管理后台 | 11 | ✅ |
| 09 M10 统计 / 导出 | 8 | ✅ |
| 10 形态与角色正交（ADR-0007/0008） | 9 | ✅ |
| 11 M5/M8 实时通道 | 4 | ✅ |
| **合计** | **104** | **✅ 全绿** |

---

## 2 · 怎么跑

```powershell
# 1) 起依赖（MySQL/Redis 常驻；本脚本确保在跑并起后端 8080）
.\tools\e2e\start-services.ps1

# 2) 跑套件（Playwright 自己拉起前端 5141；自动 snapshot → 跑 → restore → verify）
cd frontend
pnpm exec playwright test

# 3) 看报告
pnpm exec playwright show-report ../reports/playwright/html
# 收尾
.\tools\e2e\stop-services.ps1
```

前置：`MYSQL_PASSWORD`（夹具用它连库）；`redis-cli`（默认 `D:\develop\Redis-8.8.0\redis-cli.exe`，可用 `REDIS_CLI` 覆盖）。
只读巡检（不做数据回滚）：`$env:E2E_FIXTURE="0"`。

**产物**（`reports/` 已被 `.gitignore` 忽略 —— storageState 含真实 JWT）：
`reports/playwright/html/`（报告）、`reports/playwright/test-results/`（失败截图 / 视频 / trace）、`reports/playwright/results.json`。

---

## 3 · 覆盖了什么（关键判据）

- **防假绿**：每个页面巡检都断言「落地路径 === 目标路由」。这是对 `FRONTEND_CONTRACT §12.1` 那次
  「33/33 全绿但全是老人首页」事故的正式防线；重定向一旦把矩阵变成同一页复读，立刻判红。
- **越权矩阵**：4 角色 × 4 探针（`/api/common/perm-probe/*`）+ ELDER 写接口全 403 + 非 admin 访问 `/api/admin/**` 403
  （且**非法请求体也是 403 而非 400**）。**全部通过**。
- **角色 / 归属分离**：FAMILY 不能接单、COMPANION 不能下单（403）；fam001 读 fam019 的订单 1019 拿不到详情。
- **状态机**：对 `PENDING` 订单调 `/start` → **3002**（验证「先状态后身份」铁律）。
- **资质门禁**：未过审 `comp025` 访问大厅 / 接单 → **2003**（业务码，非 403）。
- **隐私最小化**：订单列表 VO **不含** `familyId / elderId / companionId / address / version`。
- **合规红线**：药品字典响应与用药页面全文均**不含**「建议服用 / 推荐剂量 / 诊断为」。
- **适老化**：老人模式切换给 `<html>` 加 `elderly-mode`、`--nl-font-body` 与 body 实际字号 ≥18px、`--nl-touch-min` ≥48px、无需刷新。
- **形态正交**：390/768/1280/1920 四档宽度下 `.nl-shell` 与 `.nl-desktop-shell` 切换正确；mobile-only 路由在桌面给出 `NlMobileOnlyNotice`。
- **实时**：陪诊执行页确实建立 `/ws/` 连接；家属首页确实轮询 `unread-count`。
- **导出**：`/api/statistics/export/order` 返回二进制（非 JSON）且 >1KB；UI 点击导出触发下载事件（文件名 `*.xlsx`）。
- **数据回滚**：17 张被写表按 `MAX(id)` 水位比对 + 6 张表白名单行逐列一致 + 成对性断言（REVIEWED 订单数 == `order_review` 行数）+ Redis `order:seq` / `pwd:version` 还原。

---

## 4 · 发现

### F-01 · 登出是「按用户」生效的，不是「按会话」〔行为，中〕
一次登出会作废该账号的**全部**令牌。实测：同一账号连登两次得 T1/T2，用 T1 登出后，
`/auth/me` 用 **T1 与 T2 都返回 401**；同时 Redis `pwd:version:101` 由 `6 → 7`。

- 影响：多设备用户在一台设备点「退出登录」，其它设备会被一起踢下线。若这是有意设计（单会话模型），
  建议在 `docs/api/01-auth-user.md` 明确；若不是，需要改为按令牌（jti）拉黑。
- 这是本次唯一直接导致测试互相干扰的原因（见 §5）。

### F-02 · `/complete` 缺失请求体时处理不一致〔健壮性，低〕
`POST /api/order/{id}/complete` 的 `OrderCompleteDTO` 被 `@Valid @RequestBody` 标为**必填**。
缺体时后端出现两种结果：

- `nianglin-error.log` L341/342：`请求体解析失败 ... Required request body is missing`（被 `GlobalExceptionHandler` 兜住，返回业务错误码）；
- 同文件 L357：`ERROR GlobalExceptionHandler - 系统异常 | POST /api/order/1001/complete`（返回 `code=500`）。

同类「缺必填请求体」应统一为可预期的业务错误，不应出现 500。建议核对该接口的异常处理路径。

### F-03 · 文档与实现的令牌名漂移〔文档，低〕
PRD §2.1 与 `AGENTS.md` §3.5 写适老化基线令牌是 `--nl-font-base`；
实现里叫 **`--nl-font-body`**（`frontend/src/styles/elderly.scss` L26）。二者不符，按实现为准，建议同步文档。

### F-04 · 种子事实已被历史写路径污染〔环境 / 数据债，中〕
`FRONTEND_CONTRACT.md §10.8` 的「该显示几条」与实测已不符：

| 项 | 文档（种子） | 实测 |
|---|---|---|
| `internal_message` 行数 | 72 | **4423** |
| `companion_order` 行数 | 64 | 65 |
| fam001 名下订单 | 2（1001 / 1031） | **3**（多出遗留 PENDING `17801`） |
| comp001 大厅待接单 | 6 | **0**（该陪诊员有拒单记录，被大厅剔除） |
| `sys_user` 行数 | 92 | 93 |

即 `BUG_LIST` 的 **L1 欠账仍在，且已从 `internal_message` 扩散到 `companion_order`**。
本轮夹具保证「不再产生**净新增**」，但不负责把库修回种子态 —— 需要一次「重建干净基线」的运维动作
（见 `FIXTURE_ROLLBACK_PLAN.md §8 步 0`，属破坏性操作，需单独确认）。

> 测试侧已按此调整：凡与绝对条数相关的断言，改为断言**不变量**（如「大厅返回项全为 PENDING」），
> 并在用例内注明原因，避免把环境债伪装成代码缺陷。

### F-05 · 业务接口的「参数校验先于角色校验」〔一致性，低〕
越权角色 + **非法体**调用业务接口（如 COMPANION `POST /api/order`）返回的是**参数校验业务错误（HTTP 200）**，
而不是 403；只有**合法体**才会走到 `@PreAuthorize` 拿到 403。
`/api/admin/**` 已通过把角色校验下沉到**过滤链**规避了这一点（`AGENTS.md §2.8` 说明的正是该动机），
但其它业务接口仍是 AOP 语义。不构成越权（业务错误不含数据），但口径与文档的「角色 → 403」不完全一致，供评审。

### F-06 · 遗留 lint 警告〔代码卫生，低〕
`pnpm lint` 有 2 条 `no-unused-vars` 警告，均在**应用源码**且先于本次改动存在：
`src/utils/realtime.js:115`、`src/views/family/order-review.vue:48`。本次新增的 `frontend/e2e/**` 已排除在应用 lint 之外，零警告。

---

## 5 · 测试过程中修复的两个自身问题（值得记录）

1. **跨 spec 的令牌污染**：最初把「注销」用例挂在 `fam001` 的共享 `storageState` 上 —— 由 F-01，登出直接作废了
   fam001 的全部令牌，导致其后 8 个复用该登录态的页面巡检全部 401 假红。
   → 改为**专用账号 `comp025`** + 空 `storageState` 内新建登录；并把 `pwd:version:*` 纳入夹具的 Redis 还原。
2. **断言写错而非产品有问题**：`/order/{id}/cancel`、`/complete`、`/reject` 都要求请求体
   （`OrderCancelDTO.reason` 还是 `@NotBlank`）；最初漏传体，得到的是参数校验错误而不是预期的状态机码 3002。
   改用无请求体的 `/start` 验证「禁止跳级」，才是干净的判据。

---

## 6 · 未覆盖 / 后续

- 50 并发抢单（乐观锁 1 成功 49 失败）、1 万订单统计 <2s：属 JMeter 压测与 `backend/sql/tools/e2e_*.py` 的职责，本轮不重复。
- 打卡坐标 >2000 米无效（4001）、重复打卡（4002）、资质审核 / 仲裁 / 封禁的**写**动作端到端：接口层已具备，
  但 UI 交互层本轮只做了渲染 + 读契约，写动作留待下一轮（需先补 §F-04 的干净基线）。
- 跨浏览器（WebKit / Firefox）：本轮先以本机 Chrome 跑通；如需矩阵再引入。
