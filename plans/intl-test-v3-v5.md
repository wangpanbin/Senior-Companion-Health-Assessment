# V4 + V5 新增功能 · 前后端联调测试计划（ego-browser）

> 状态：**待评审**（三轮对齐完成，全部决策已收敛）

## Context

V4、V5 功能已合入 `main`，但 `CHANGELOG.md` 只写到 V3.7 —— V4/V5 变更散在 commit 与 `docs/spec/`、`docs/api/` 中，此前没有一轮**浏览器级联调**把它们串起来验证过（既有 e2e 是 HTTP/Playwright 脚本口径，不是 ego-browser 联调口径）。本计划：**启动五个进程，用 ego-browser 逐项走通 V4+V5 全部新增功能的主链路 + 关键异常，产出入库测试报告**。

### 已对齐的决策（三轮 grilling 结论）

| # | 决策 | 结论 |
|---|---|---|
| Q1 | 范围 | **仅 V4 + V5 新增**（V3.x 已有验收基线，不重测） |
| Q2 | 深度 | **主链路 + 关键异常**（越权 403、必填校验、错误码 11002/2011），约 40–60 断言点 |
| Q3 | 定时任务 | **造数 + 手动触发扫描**（缩短 cron 启动后端 + 定向 SQL 挪时间戳） |
| Q4 | 数据处置 | **跑完恢复基线**（`restore_clean_baseline.py --execute`，执行前再次确认） |
| Q5 | 后端启动 | **测试友好 cron**：环境变量覆盖 remind-cron / cert-expire-cron / missed-scan-interval，不改代码 |
| Q6 | 助手 | **启动 ai-service:8081**，验证 V5-B 新增 3 条检查准备 FAQ |
| Q7 | 注销对象 | **新注册账号**做注销，种子账号一个不碰 |
| Q8 | 产出 | **`docs/reports/` 联调测试报告**，完成后再确认开 `docs/` 分支提交 |
| Q9 | 缺陷处置 | **只记录不改**：报告列缺陷清单 + 复现步骤，修不修由用户决定 |
| Q10 | 写库授权 | **定向 UPDATE**（按主键/业务键精确定位，SQL 与影响行数留档），跑完恢复 |

### 功能清单（测试对象，事实来自 git log + docs/api + docs/spec）

**V4（8 项）**

| # | 功能 | 后端证据 | 前端页面 | 关键异常 |
|---|---|---|---|---|
| V4-1 | 账号注销 + 个人数据导出 | `POST /api/user/deactivate`、`GET /api/user/data-export` | `views/profile/index.vue` | 有进行中订单→拒绝；注销后旧 token 401；导出文件无身份证/完整手机号 |
| V4-2 | 漏服提醒触达闭环（当天陪诊员） | `MedicationServiceImpl` ReminderDispatcher、消息 `MEDICATION_MISSED` | `family/medication.vue` 说明区 | 无陪诊订单时只发家属（现状不回归） |
| V4-3 | 自助找回密码（去短信化，ADR-0021） | 验证码后端直接回传 | `views/login/forget.vue` | 错误验证码→报错提示；验证码后端回传可见 |
| V4-4 | 就诊小结转用药计划草稿（一单一转） | `MedicationService` 草稿、错误码 **11002** 重复转入 | `family/medication.vue` | 重复转入→11002 文案 |
| V4-5 | 结算闭环（管理员核定 + 导出） | settlement 端点 | `admin/settlement.vue` | 越权（非 admin 访问→403/2006） |
| V4-6 | 健康档案结构化（体征，三端） | `vitalSign` API、`GET /api/execution/{orderId}/vitals` | `family/health.vue`、`elder/health.vue`、`companion/execute.vue` | 老人账号不能写（403/405 口径）；越权看他人体征 |
| V4-7 | 运营参数可调化（sys_dict 热生效） | admin 运营参数端点 | `admin/operation-param.vue` | 越权；非法值校验 |
| V4-8 | 费用明细模型（ADR-0009） | `order_fee_item` 明细端点 | 订单详情费用明细区 | 明细金额与合计一致 |

**V5（3 项）**

| # | 功能 | 后端证据 | 前端现状 | 触发方式 |
|---|---|---|---|---|
| V5-A | 漏服三级升级（30min 家属 / 2h 陪诊员 / 24h 管理员） | `ReminderLevelPolicy`、`notify_level` 列、`MEDICATION_ESCALATED`、短信按级去重+日频控 | 消息列表展示 | 定向 UPDATE `plan_time`→25h 前 + 缩短 `missed-scan-interval-ms` → 一轮扫描连升三级 |
| V5-B | 检查准备提醒 | `GET /api/exam-prep`（6 条字典）+ `ExamPrepScheduler` 每小时 `ORDER_EXAM_PREP` | ⚠️ 无页面消费 `listExamPrep()`（规格明说入口是后续迭代）→ 浏览器侧用 `page.fetch('/api/exam-prep')` 验契约 + 消息页验站内信；助手 FAQ 在 `family/assistant` 页面验 | 订单 `visit_time`→明天 + 缩短 `remind-cron` |
| V5-C | 健康证到期校验与提醒 | 接单链路 **2011**、申请录入 `healthCertExpire`、`HEALTH_CERT_EXPIRING` 站内信 | `companion/entry.vue` 有效期录入 | `health_cert_expire` 设过期 + 缩短 `cert-expire-cron`；接单被拒 2011 |

## Approach

**五阶段流水线**，ego-browser 一个 taskSpace 贯穿全程（每阶段一个 `.mjs` 轮次文件，落盘 `C:/Users/<name>/ego/nl-intl-test-<date>/`），截图证据存同目录，报告引用：

1. **备环境**：静态门禁 → 起服务（测试友好 cron）→ L0 身份闸门 → 四角色登录冒烟。
2. **V4 主链路**（8 项，按依赖排序：V4-3 找回密码 → V4-1 注销导出（新账号）→ V4-4 草稿转入 → V4-6 体征三端 → V4-8 费用明细 → V4-5 结算 → V4-7 运营参数 → V4-2 漏服触达）。
3. **V5 定时链路**（造数 → 等扫描 → 浏览器验证消息到达 → 逐项恢复数据）：V5-A → V5-B（含助手 FAQ）→ V5-C。
4. **收尾**：全量缺陷汇总 → 恢复基线（先向用户确认）→ 默认配置重启后端 → 写报告。
5. **提交**：报告完成后再向用户确认是否开 `docs/` 分支提交。

**关键复用（不新造轮子）**：
- 服务起停：`tools/e2e/start-services.ps1` / `stop-services.ps1`（只认 `/api/health` 探活）
- 身份闸门：`python tools/e2e/l0_identity_gate.py`（10/10 才开测）
- 凭据真源：`backend/sql/tools/fixture_credentials.py`（`admin`/`fam001`/`comp009`/`elder001`，口令 `Nl@123456`）
- 恢复基线：`python tools/e2e/restore_clean_baseline.py --execute`（三层水位对账，执行前确认）
- 定时任务触发全走**环境变量**，零代码改动：
  - `NIANGLIN_EXAM_PREP_REMIND_CRON="*/30 * * * * ?"`
  - `NIANGLIN_COMPANION_CERT_EXPIRE_CRON="*/30 * * * * ?"`
  - `NIANGLIN_MEDICATION_MISSED_SCAN_INTERVAL_MS=15000` + `..._INITIAL_DELAY_MS=5000`
- ego-browser 用法：`ego-browser skill` 已读取；PowerShell 下写 UTF-8 `.mjs` 文件 + `ego-browser nodejs -e "await import('file:///...')"` 入口。

## Steps

- [x] **P0 · 备环境**
  - [ ] `python tools/check_secrets.py`、`python tools/check_metric_dict_parity.py` 静态门禁过（与 CI 同命令）
  - [ ] 确认 MySQL/Redis 在线（已探明 3306/6379 LISTENING）
  - [ ] 设置 `MYSQL_PASSWORD`（向用户要，不落盘）、`SPRING_PROFILES_ACTIVE=dev` + 测试 cron 环境变量，启动后端
  - [ ] `cd frontend; pnpm dev` 启动 5141；启动 `ai-service` 8081（`uvicorn app.main:app --host 127.0.0.1 --port 8081`）
  - [ ] `python tools/e2e/l0_identity_gate.py` → **10/10**
  - [ ] ego-browser 新建 taskSpace，四角色各登录一次冒烟（admin / fam001 / comp009 / elder001）
- [x] **P1 · V4 主链路**（每项：主链路 + 关键异常，截图存证）
  - [ ] V4-3 找回密码：`/forget` → 后端回传验证码 → 自动填充 → 重置成功 → 新密码登录
  - [ ] V4-1 注销导出：拒绝路径用 **fam001**（先造一笔进行中订单 → 注销被拒且提示具体原因，订单保留）；成功路径用**新注册账号**（无订单 → 注销成功 → 旧 token 401 → 重新登录失败）；导出文件人工核验不含身份证号/完整手机号/密码
  - [ ] V4-4 小结转用药草稿：家属端转入 → 成功；重复转入 → **11002** 文案
  - [ ] V4-6 体征三端：家属录入 → 老人页回显卡片 → 陪诊员执行页回显/按单查询；异常：老人账号写入被拒
  - [ ] V4-8 费用明细：订单详情明细项与合计核对
  - [ ] V4-5 结算：admin 核定 payment_status + 导出下载；异常：非 admin 访问被拒
  - [ ] V4-7 运营参数：改一个参数 → 热生效回读；异常：非法值校验
  - [ ] V4-2 漏服触达：造当天漏服任务 → 扫描 → 家属+当天陪诊员各收到 `MEDICATION_MISSED`
- [x] **P2 · V5 定时链路**
  - [ ] V5-A：定向 UPDATE `medication_task.plan_time` → 25h 前（记 SQL+行数）→ 等一轮扫描 → 消息页依次出现家属/陪诊员/管理员三级升级 + `MEDICATION_ESCALATED`；验证 `notify_level` 只升不降、同级不重发
  - [ ] V5-B：订单 `visit_time` → 明天 → 等扫描 → 家属+老人各收 `ORDER_EXAM_PREP`（同日不重发）；`page.fetch('/api/exam-prep')` 验 6 条、remark 含来源+免责；助手页问「胃镜前能吃饭吗」→ 命中 FAQ 静态答案（降级模式）
  - [ ] V5-C：新注册陪诊员/comp009 档案 `health_cert_expire` → 过期日 → 等扫描 → 收 `HEALTH_CERT_EXPIRING`；接单 → **2011** 拒绝
  - [ ] 每项测完立即把造数行 UPDATE 回原值（留 SQL 记录）
- [ ] **P3 · 收尾**
  - [ ] 汇总缺陷清单（只记录不改，含复现步骤 + 截图路径）
  - [ ] 向用户确认后执行 `restore_clean_baseline.py --execute`
  - [ ] 默认配置重启后端（去掉测试 cron 环境变量）
  - [ ] 写 `docs/reports/` 联调测试报告（逐项通过/失败表、断言点、缺陷清单、造数 SQL 台账）
  - [ ] 向用户确认是否开 `docs/` 分支提交报告

## Verification

- **过程判据**：每阶段结束打印通过/失败计数；L0 闸门 10/10 是开测前提；所有登录冒烟通过才进 P1。
- **完成判据**：V4 8 项 + V5 3 项 ×（主链路 + 关键异常）全部有截图证据与结论；缺陷清单落报告。
- **环境判据**：测完后 `restore_clean_baseline` 恢复基线（对账通过）、后端以默认 cron 重启、5141/8080/8081 可用。
- **不做的事**：不改任何业务代码（Q9）、不碰种子账号密码（Q7）、不 push、不在 main 上提交（报告提交需用户确认后开 `docs/` 分支）。

## 风险与备注

- `MYSQL_PASSWORD` 需用户提供（脚本与 Flyway 都不内置口令）。
- 注销/体征/结算等写路径跑完依赖 `restore_clean_baseline` 收口；若恢复脚本对某表报 ⚠（仅提示不改行为），在报告中如实记录。
- V5-B 前端无消费页面是**规格既定现状**，不算缺陷；报告中按「契约 + 消息 + 助手 FAQ」三面验证并注明。
- ego-browser 每轮是新 Node 进程：每阶段写独立 `.mjs`，脚本内不依赖上一轮 JS 变量。
