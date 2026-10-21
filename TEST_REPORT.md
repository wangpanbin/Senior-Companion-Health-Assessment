# 银龄伴诊 · 前后端联调全功能测试报告

- **执行日期**：2026-10-21
- **被测版本**：`main` @ `17cf939`（V3.7 合并后）
- **覆盖范围**：git 标签 `v3.0` 之后的**全部提交** —— `v3.0..HEAD` = **99 个提交**（82 非合并 + 17 合并，含合并提交 `17cf939`），横跨 **V3.1 – V3.7** 七个版本 + M13 订单聊天
- **执行方式**：六层判据（端口身份闸门 / 浏览器套件 / 接口探针 / 写路径探针 / 访问专项 / 助手双模式）+ 单元级门禁
- **结论**：**未发现业务缺陷**。首轮出现的 43 条红灯经复跑证实**全部由环境事故引起**（并发 `mvn test` 抢占 8080 击穿后端），**重跑后 L1 261/261 全绿**；本轮另修复 **2 处工具脚本缺陷**、**1 处闸门漏洞**、**1 处基线污染**，并**重置了被历史跑测弄脏的开发库**。合规红线（医疗禁用词 / 派单措辞 / 越权 403）用例**全部通过**。

---

## 一、执行摘要

| 层 | 手段 | 规模 | 结果 |
|---|---|---|---|
| **L0** | 端口与服务身份闸门 | 10 项断言 | ✅ **10/10**（跑测时新增第 6 项，收口时扩到 8081 / 5141 的服务身份，见 §三） |
| **L1** | Playwright 浏览器套件 | 21 spec / **254 条唯一用例**（247 条 spec + 7 条 setup 登录） | ✅ **261/261 全绿**（分两批累计执行 261 次：批次1 168 条 178.3s + 批次2 93 条 90s；`auth.setup` 是 project 依赖，两批各跑一次） |
| **L2** | 接口级探针 | `probe_api` 32 项 + `probe_ws_handshake` 12 项 | ✅ **44/44** |
| **L3** | 写路径探针 | `probe_backend_gaps` 12 项（含文件上传 6 项验收，真实落盘 1 个文件） | ✅ **12/12** |
| **A-1** | 越权矩阵（端点 × 角色） | 25 端点 × 4 角色 = **100 次真实 HTTP** | ✅ **100/100，0 违规** |
| **A-2** | 路由可达性 | 11 页面 × 2 形态 = **22 项** | ✅ **22/22**（落地路径全对，无 console error、无 ≥400 接口） |
| **M1/M2** | V3.7 助手双模式 | 真实模型 + 基础降级，各 3 条断言 | ✅ **6/6**，已切回 M1 |
| **G** | 数据回滚校验 | 19 张写表 + 6 张白名单影子表 + 68 个 Redis 键 | ✅ **全部回到基线** |
| **H** | 单元级门禁 | `mvn verify` / `pnpm lint:check`+`test`+`build` / `pytest ai-service` | ✅ 见 §九 |

**L1 是本轮最强的信号**：254 条用例覆盖 v3.0 之后**全部八个新增功能域**（M13 聊天 / V3.1 履约 / V3.2 健康 / V3.3 工作台 / V3.4 调度 / V3.5 驾驶舱 / V3.6 数据闭环 / V3.7 助手），**一条未漏、一条未红**。

---

## 二、环境与基线

### 2.1 运行环境

| 组件 | 状态 |
|---|---|
| MySQL 8.0 | ✅ `nianglin` 库，Flyway V1–V4 全部 `success=1` |
| Redis 8.8.0 | ✅ `redis-cli` 在 `D:\develop\Redis-8.8.0\redis-cli.exe` |
| 后端 Spring Boot | ✅ 8080，`application=nianglin` / `profiles=dev` / JDK 21.0.7 |
| 前端 Vite | ✅ 5141（`VITE_PORT` 默认值），代理 → 8080 实探通 |
| ai-service (uvicorn) | ✅ 8081，只监听 `127.0.0.1`，`/healthz` UP |
| 工具链 | Maven 3.9.15 / Node 24.18.1 / pnpm 12.4.1 / Python 3.14.7 / MySQL 客户端在本机默认路径 |

### 2.2 基线复位（本轮的关键前置）

跑测前的开发库**已被历史跑测弄脏**，直接跑会得到大量"断言过时"的假红。按 `tools/e2e/restore_clean_baseline.py` 的 dry-run 计划逐表核对后复位：

| 表 | 基线 id | 复位前 MAX | 删除行 | 性质 |
|---|---|---|---|---|
| `medication_task` | 20520 | 20638 | 68 | 定时任务展开生成，非种子 |
| `medication_plan` | 10036 | 10087 | 30 | 测试残留 |
| `internal_message` | 40072 | 40240 | 160 | 测试残留 |
| `admin_oper_log` | 60037 | 60048 | 11 | 测试残留 |
| `sys_login_log` | 43040 | 48320 | 4697 | 跑测登录日志（种子仅 40 行） |
| `order_checkin` / `companion_track` | — | — | 1 / 1 | 测试残留 |
| `companion_profile` | 630 | 5030 | 30 | 压测批次 |
| `sys_user` | 330 | 5030 | **31** | 30 个 `bench*` 压测账号 + 1 个手建账号 |

> ⚠️ **两处刻意保留 / 精确处理**：
> ① `sys_user` id=331 `user1` 是**注册页手建的账号**（2026-09-19），不在压测批次内 —— **保留**（压测批次 `bench5001–5030` 单独按用户名精确删除）。
> ② 那 30 个 bench 陪诊员是 `APPROVED + AVAILABLE` 且**评分 5.00（满分）**，会把 V3.4 匹配候选池挤爆 —— 必须清，否则 `18-matching` 的候选排序断言会失真。
>
> 🔎 **顺带查清的一件事**：被删的 68 条 `medication_task` 并非种子数据。`medication_task` 只在 **V2 种子**里 INSERT（ids `20001–20520` = 520 条），V3/V4 都没碰过这张表 —— 真正的种子基线是 **520 条**，而 `FRONTEND_CONTRACT §10.8` 记的「563 条（MISSED 213 / PENDING 12 / TAKEN 338）」**从一开始就没写对过**：`backend/sql/V2__seed_data.sql` 自 `6bc37c5`（2026-09-15 骨架初始化）起只提交过一次、从未修改，基线一直是 520 条（见 §十·F-4）。

复位后分布与种子基线**逐项吻合**：订单 64 条（6 种状态 6/8/4/12/31/3）、`companion_profile` 30（22 APPROVED+AVAILABLE / 3 REST / 3 PENDING / 2 REJECTED）、`sys_user` 93（92 种子 + 1 手建）、`medication_task` 520、`complaint` 32、`internal_message` 72。

---

## 三、L0 · 端口与服务身份闸门

**本轮把闸门从 5 项加到 6 项** —— 第 6 项是这次事故的直接产物；**收口时再扩到 10 项**，把 8081 / 5141 的**服务身份**也纳入硬断言：

| # | 断言 | 结果 |
|---|---|---|
| 1 | `code == 200` | ✅ |
| 2 | `data.application == "nianglin"` | ✅ |
| 3 | `data.profiles` 含 `dev` | ✅ |
| 4 | `data.status == "UP"` | ✅ |
| 5 | 顶层字段名是 `message` 而非 `msg` | ✅ |
| 6 | **8080 监听者是本项目的进程（非 surefire 测试 fork）** | ✅ 新增 |
| 7 | **8081 `/healthz` 的 `service == "nianglin-ai-assistant"`** | ✅ 收口时新增 |
| 8 | **8081 监听者是本项目的 uvicorn（命令行含 `uvicorn` + `app.main:app`）** | ✅ 收口时新增 |
| 9 | **5141 前端在线** | ✅ 收口时新增 |
| 10 | **5141 监听者是 vite** | ✅ 收口时新增 |

> **为什么必须加第 6 项**：「端口在监听」和「健康响应是 200」**都证明不了这个端口上跑的是谁**。仓库里 26 处 `@SpringBootTest` 全是裸注解（默认 `webEnvironment = MOCK`，不起 Tomcat），测试本身不会抢端口；但 `logback-spring.xml` 的 `LOG_HOME` 是相对路径 `./logs`，**surefire fork 与被测应用写的是同一个日志文件**（`backend/logs/nianglin.log` 里现存 4088 处 surefire 栈帧）—— 只看日志极易误判成「surefire 抢了端口」。真正会击穿后端的是**并发 `mvn test` 重编译 `backend/target/classes`**（详见 §十·F-1）。所以第 6 项断言的是「**监听进程是本项目的、且不是测试 fork**」—— 判据是**进程命令行**，既不是日志、也不是响应体。
>
> **为什么 8081 也要硬断言**：本项目的 8081 是 **ai-service**，而接口文档里恰好有"后端换端口到 8081"的写法 —— 同机再跑一个别的服务占住 8081 时，HTTP 200 照样通过，但那不是助手服务。8081 的命令行实测为 `python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8081`（**不含 `ai-service` 字面量**），所以身份判定靠**命令行子串 + `/healthz` 的 `service` 字段**双条件。

闸门脚本：`tools/e2e/l0_identity_gate.py`（本轮入库，只读，不落库）。

---

## 四、L1 · Playwright 浏览器套件

**批次 1（01–13，基线对照 / 回归判定）** —— 168 条 **全绿**，178.3 秒：

| spec | 条数 | spec | 条数 |
|---|---|---|---|
| 01-auth | 16 ✅ | 08-admin | 11 ✅ |
| 02-access-matrix | 13 ✅ | 09-statistics-export | 9 ✅ |
| 03-elder | 8 ✅ | 10-form-factor | 10 ✅ |
| 04-family-order | 14 ✅ | 11-realtime | 5 ✅ |
| 05-companion | 15 ✅ | 12-admin-responsive | 27 ✅ |
| 06-medication | 4 ✅ | 13-client-mobile | 25 ✅ |
| 07-message | 4 ✅ | auth.setup | 7 ✅ |

**批次 2（14–21，v3.0 之后新增的八个功能域）** —— 93 条 **全绿**，90 秒：

| spec | 功能域 | 条数 | 覆盖要点 |
|---|---|---|---|
| 14-chat | M13 订单聊天 | 13 ✅ | 双端实时互发、图片附件、老人只读、管理员仲裁只读、导航层入口、信息面板、窄屏折叠卡 |
| 15-fulfillment | V3.1 履约保障 | 16 ✅ | 工作状态自助、履约读口径、事件流升序、催单、风险中心、改派、大厅推荐序 |
| 16-elder-health | V3.2 老人健康 | 13 ✅ | 摘要只含本人绑定的老人、三桶之和=总数、区间越界 9002、时间线分页、越权 2006/403 |
| 17-companion-workbench | V3.3 工作台 | 10 ✅ | 四块内容、区间明示、收入页不自算、趋势缺桶补 0、companionId 入参被忽略 |
| 18-matching | V3.4 智能调度 | 9 ✅ | 排序维度白名单、INCOME 仍 400、候选限流、**候选姓名必须脱敏**、越权 3004/403 |
| 19-operations | V3.5 驾驶舱 | 8 ✅ | 八项指标、四类异常卡落地页可达、趋势维度切换、自定义区间、空数据空态 |
| 20-data-loop | V3.6 数据闭环 | 6 ✅ | 缺口表 168 个桶（7 天×24 小时）、效果对比两期值、指标切换、空态 |
| 21-assistant | V3.7 家属助手 | 11 ✅ | 常驻免责声明、费用数字 128/158 不跑偏、依据引用、下单草稿卡、**医疗问题固定拒答**、**派单措辞红线**、非 FAMILY 403 |
| auth.setup | 账号登录 setup | 7 ✅ | 四个角色的登录态准备（`auth.setup` 是 project 依赖，**批次 1、2 各跑一次**） |

> **93 = 8 个 spec 的 86 条 + setup 的 7 条**。两批合计执行 261 次，但**唯一用例是 254 条**（247 条 spec + 7 条 setup）—— 7 条 setup 在两批各跑一遍，不是有 261 条不同用例。

**合规红线专条**（红线红了不算"可归因"，直接算缺陷 —— 本轮全过）：

- 医疗建议禁用词：`21-assistant` 遍历 `FORBIDDEN_MEDICAL_PHRASES` 全部未出现；`06-medication` 药品字典全文无剂量/诊断表述；`15-fulfillment` 催单备注命中词表被拦。
- 派单措辞：助手回答不出现「已指派 / 已分配 / 自动派单」，且必须说明「由陪诊员本人确认接单」。
- 越权 403：ELDER 写操作 100% 服务端 403；非 ADMIN 访问 `/api/admin/**` 一律 403（带合法请求体）。

---

## 五、L2 / L3 · 接口与写路径探针

| 探针 | 项数 | 结果 | 备注 |
|---|---|---|---|
| `probe_api.py` | 32 | ✅ 32/32 | 4 角色 × 各自档案/订单/用药/消息 + admin 8 个后台接口 |
| `probe_ws_handshake.py` | 12 | ✅ 12/12 | 3 样本 × 4 组合（后端直连 ×3 + vite 代理 ×1）全部 `101 Switching Protocols` |
| `probe_backend_gaps.py` | 12 | ✅ 12/12 | 三处缺口 + 文件上传 4 项（成功 / CHECKIN 被拒 / 魔数非法被拒 / ELDER 403） |

其中写路径**真实落盘 1 个 PNG**（`/uploads/202610/4aed5710….png`），跑后由夹具按上传目录清单删除。

**探针顺手验到的三处业务事实**（均为正确行为，非缺陷）：

1. 列表 VO 的隐私红线未破：`records[0]` 的 key 里**没有** `familyId / elderId / companionId / address / remark / version`，只有脱敏名 `张*海`。
2. 结算字段口径一致：列表 `paymentStatus=SETTLED / actualFee=98.0` == 详情值。
3. 家庭健康摘要只含本人绑定的老人，已解绑关系（431）不出现。

---

## 六、访问维度专项（Q2 决策）

### 6.1 A-1 · 越权矩阵（100 次真实 HTTP，0 违规）

判据：**允许的角色不得被 403 拦下；不允许的角色必须被 403 拦下**。期望值取自源码的 `@PreAuthorize`（类级 + 方法级），不是猜的。

| 域 | 端点 | 允许角色 | 实测 |
|---|---|---|---|
| V3.1 | `/api/order/{id}/fulfillment` | 资源归属制 | ADMIN 200 / 接单陪诊员 200 / 无关方 3004 |
| V3.1 | `/api/order/{id}/urge` | FAMILY | 非 FAMILY 一律 403；ELDER 403（只读拦截器） |
| V3.1 | `/api/admin/fulfillment/summary`、`/risk-list` | ADMIN | 非 ADMIN 一律 403 |
| V3.2 | `/api/elder/health/overview`、`/timeline` | ELDER | 非 ELDER 一律 403 |
| V3.2 | `/api/family/health/summary`、`/family/elder/{id}/health/overview` | FAMILY | 非 FAMILY 一律 403 |
| V3.2 | `/api/admin/metrics` | ADMIN | 非 ADMIN 一律 403 |
| V3.3 | `/api/statistics/companion/me`、`/me/trend`、`/me/profile`、`/me/income` | COMPANION | 非 COMPANION 一律 403 |
| V3.4 | `/api/order/{id}/recommend-companions` | FAMILY | 非 FAMILY 一律 403；家属查他人单 3004 |
| V3.4 | `/api/order/hall` | COMPANION | 非 COMPANION 一律 403 |
| V3.5 | `/api/admin/operations/{overview,exceptions,trend,health}` | ADMIN | 非 ADMIN 一律 403 |
| V3.6 | `/api/admin/operations/{gap,effect}` | ADMIN | 非 ADMIN 一律 403 |
| V3.6 | `/api/statistics/{overview,order-trend,companion-rank}` | ADMIN | 非 ADMIN 一律 403 |
| V3.7 | `/api/assistant/chat` | FAMILY | 非 FAMILY 一律 403；ELDER 403（只读） |

> 📌 **一处口径差异（设计如此，非缺陷）**：`GET /api/order/{id}/fulfillment` **没有角色守卫**，只做资源归属 —— `OrderServiceImpl#requireInvolved` 明确放行 ADMIN / 下单家属 / 接单陪诊员 / 该单老人，其余抛 `3004`。而同族的 `/recommend-companions` 是 FAMILY 专属。两个端点口径不同，但**契约里已经写明了这一点**（`docs/api/03-order.md` §12 明确「可见性与订单详情**完全一致**」，后端 `OrderController#fulfillmentDetail` 的 `@Operation` 同一句）—— 见 §十·F-6。

### 6.2 A-2 · 路由可达性（22 项，0 失败）

判据同 `helpers/pageAudit.js#sweep` 四条：落地路径 === 目标路由 / 页面有实质内容 / 无 console error / 无 ≥400 接口响应。

11 个代表页面 × 桌面 1280×900 + 移动 390×844 —— **全部落地到目标路由**，无 console error、无 ≥400 接口。截图落 `reports/playwright/a2-shots/`（22 张）。

| 域 | 页面 | 落地路径（两形态一致） |
|---|---|---|
| V3.1 | `/admin/fulfillment`、`/family/order/1001` | ✅ 一致 |
| V3.2 | `/family/health`、`/family/elder/401/health`、`/elder/health` | ✅ 一致 |
| V3.3 | `/companion/workbench`、`/companion/income` | ✅ 一致 |
| V3.4 | `/family/order/1001`（候选陪诊员推荐区） | ✅ 一致 |
| V3.5 / V3.6 | `/admin/operations` | ✅ 一致 |
| V3.7 | `/family/assistant` | ✅ 一致 |

脚本：`frontend/e2e/a2-reachability.mjs`（本轮新增，可复跑）。

---

## 七、V3.7 助手双模式（Q4 决策）

切换方式：**重启进程 + 空串环境变量覆盖**（`LLM_BASE_URL=` / `LLM_API_KEY=` / `LLM_MODEL=`）。
覆盖语义**实测确认**（不靠记忆）：pydantic-settings 的环境变量优先级高于 `.env`，空串也能盖过 → `llm_enabled=False`，**全程不动 `.env` 文件**。

| 模式 | 启动日志 | 断言 | 结果 |
|---|---|---|---|
| **M1 真实模型** | `大模型：已启用（模型=deepseek-chat）` | 回答非 FAQ 原文、`degraded=False` | ✅ |
| **M2 基础降级** | `未配置大模型，助手以基础模式运行` | A：`degraded=True`、回答为 FAQ 原文（含 128）<br>B：**医疗问题仍走固定拒答模板**<br>C：Java 门面 `/api/assistant/chat` 透传 `degraded=True` | ✅ |

**B 是本轮最关键的一条**：降级模式下**护栏没有一起降级** —— 医疗问题仍然返回固定拒答话术并引导线下就医，不是"因为没模型所以随便答"。跑完已**切回 M1** 并复验 `degraded=False`。

---

## 八、数据回滚校验（G）

`fixture.py restore` → `verify`，**19 张被写表 + 6 张白名单影子表 + 68 个 Redis 键全部回到基线**：

- 逐表 count / max_id 一致：`companion_order 64/64`、`internal_message 72/72`、`medication_task 520/520`、`medication_plan 36/36`、`companion_profile 30/30`、`sys_user 93/93`、`chat_message 0/0`、`chat_read_cursor 0/0` …
- 白名单行**逐列一致**（含 `version`）：`companion_order` 4 行、`elder_profile` 2 行、`medication_task` 1 行、`medication_plan` 1 行、`companion_profile` 3 行、`sys_user` 34 行。
- 成对性断言：`REVIEWED 订单 31 == order_review 31`。
- 跑测期间**真实上传的 1 个文件已删除**，上传目录回到 20 个基线文件。

---

## 九、单元级门禁（H）

| 命令 | 结果 |
|---|---|
| `cd backend && mvn -B verify` | ✅ **Tests run: 1319, Failures: 0, Errors: 0, Skipped: 0 —— BUILD SUCCESS** |
| `cd frontend && pnpm run lint:check` | ✅ exit 0，0 warning |
| `cd frontend && pnpm run test` | ✅ **230 passed / 21 files**（5.94s） |
| `cd frontend && pnpm run build` | ✅ built in 7.47s |
| `python -m pytest ai-service/tests -q` | ✅ **187 passed**（2.37s） |

> 单元级门禁**刻意排在 E2E 之后**：后端全量单测会在共享开发库留下**不回滚的 `sys_file` 行**，先跑单测会让 E2E 的 `verify` 报 `sys_file` 水位 +1，误判成"数据漂移"。

---

## 十、缺陷与异常分类

按「真缺陷 / 断言过时 / 环境漂移 / 脚本缺陷」四分类归因。

### F-1 · 【环境】并发 `mvn test` 击穿后端 —— 首轮 43 条红全部由此而来

- **现象**：首轮批次 1 报 43 条失败（`09` 1 条 + `11` 5 条 + `12` 21 条 + `13` 16 条），错误形如 `connect ECONNREFUSED 127.0.0.1:8080`、页面 `订单数据加载失败·请检查网络后点击重试`、管理端表格形态数 = 0。
- **证据链**：
  1. 失败全部集中在 **16:52:45 之后**，而 16:52:45 之前跑过的 125 条**全绿**；
  2. 后端日志显示 `2026-10-21 16:53:40.661 [SpringApplicationShutdownHook] ... Shutdown initiated` —— 后端是**被优雅关停**的，之后再没起来（8080 至今无人监听）；
  3. 日志里混进了 **surefire 的 JUnit 栈帧**（`org.apache.maven.surefire.junitplatform.JUnitPlatformProvider`），且 `backend/target/surefire-reports/` 的写入时间**恰好是 16:53:40** —— 证明**有一个 `mvn test` 与本轮 E2E 并发跑**。
- ⚠️ **收口时更正了根因（原归因有误）**：原先记的是「surefire fork 的 Spring 上下文也把 Tomcat 起在 8080 上，与被测应用争端口」。**这条站不住**：
  - 仓库里 26 处 `@SpringBootTest` **全是裸注解**（默认 `webEnvironment = MOCK`，**不起 Tomcat**），测试源码中**没有** `webEnvironment` / `@LocalServerPort` / `SpringApplication.run` / `TestRestTemplate` —— **没有任何测试会监听真实端口**，surefire fork 不可能抢走 8080；
  - 日志里那条 `Tomcat started on port 8080`（`reports/run/backend.log` L118，时间 22:59:30）是**被测应用自己**的启动行，是本轮重启后拉起的那一次；
  - surefire 栈帧之所以会出现在「后端日志」里，是因为 `logback-spring.xml` 的 `LOG_HOME` 是**相对路径** `${LOG_HOME:-./logs}` —— surefire fork 的工作目录是 `backend/`，它和被测应用**写的是同一个 `backend/logs/nianglin.log`**（该文件里现存 4088 处 surefire 栈帧）。**日志被共用，不是端口被抢占。**
  - 真正的击穿路径是：`mvn test` 会**重编译 `backend/target/classes`**，而当时的后端正是从 `target/classes` 运行的（`mvn spring-boot:run`）—— classloader 被打断后应用优雅关停。**防御办法不变：别和 E2E 并发跑 `mvn`。**
- **结论**：**环境事故，非产品缺陷**。复跑（后端与并发构建错开）→ **168/168 全绿**。
- **已落地的防复发措施**：L0 闸门加第 6 项断言 —— **8080 的监听者必须是本项目的进程、且不能是 surefire 测试 fork**。收口时闸门扩到 10 项，8081 / 5141 的服务身份也纳入硬断言（见 §三）。
  > 断言写成「**是本项目的进程** + **不是 surefire fork**」两条，而不是死要求 `-jar`：
  > `tools/e2e/start-services.ps1` 拉起的后端是 `mvn spring-boot:run`（命令行里只有 classpath，**没有 `-jar`**），
  > 死要求 `-jar` 会把一个完全正常的后端误判成「端口被占」。

### F-2 · 【脚本缺陷】`probe_backend_gaps.py` 用了 `BASE` 却没 import

- **现象**：上传探针一跑就 `NameError: name 'BASE' is not defined`（`post_multipart()` 第 68 行用 `BASE + path`，但第 37 行只 import 了 `read_token, http`）。
- **影响**：L3 层的「文件上传 6 项验收」**整层解锁不了**（`tools/e2e/README.md` 里已记过这个坑）。
- **修复**：`from _probe_http import BASE, read_token, http`（1 行）。修复后 L3 **12/12 全绿**，上传链路真实跑通。

### F-3 · 【脚本缺陷】`probe_ws_handshake.py` 写死了旧前端端口 5410

- **现象**：代理组 3 条报 `ConnectionRefusedError 127.0.0.1:5410`，并给出**误导性结论**「vite 的 '/ws' 代理没生效（检查 ws=true 并重启 dev server）」。
- **根因**：项目的 vite dev 端口早已从 5173 改到 **5141**（`vite.config.js` 注释：5173 落在 Windows Hyper-V 排除端口段 5152-5251），探针里却还写死 5410。
- **修复**：改为读 `VITE_PORT`（默认 5141），标签与归因文案一并参数化。修复后 **12/12 全绿**，四种组合全部 `101 Switching Protocols`。

### F-4 · 【文档漂移】`FRONTEND_CONTRACT §10.8` 的种子分布**记错了**

用**只读 SELECT** 直查当前库（已复位到 V2 种子基线），并与 `backend/sql/V2__seed_data.sql` 源文件双向核对，文档与实况**四处**不一致：

| 项 | 文档记的 | 实测（当前 V2 种子） |
|---|---|---|
| `medication_task` | 563 条（MISSED 213 / PENDING 12 / TAKEN 338） | **520 条**（MISSED 193 / PENDING **0** / TAKEN 327） |
| 陪诊员档案 | 30 条（APPROVED 24 / PENDING 4 / REJECTED 2） | **30 条**（APPROVED **25** = AVAILABLE 22 + REST 3 / PENDING **3** / REJECTED 2） |
| `complaint` | PENDING 10 / PROCESSING 6 / RESOLVED 11 / REJECTED 5 | **PENDING 9 / PROCESSING 7** / RESOLVED 11 / REJECTED 5（共 32） |
| 待接单订单 1001–1006 的费用 | 98 / 128 / 158 / 188 / 218（6 个订单只列了 5 个费用） | 98 / 128 / 158 / 188 / 218 / **248**（6 个订单 6 个费用） |
| 老人 401 的服药任务 | 「在 2026-09-16 的任务**全是 `MISSED`**」 | **MISSED 7 / TAKEN 5**（共 12 条） |

⚠️ **成因不是「快照漂移」**：曾以为这是「2026-09-16 的旧快照、后来种子变了」—— **不对**。`backend/sql/V2__seed_data.sql` 自 `6bc37c5`（2026-09-15 骨架初始化）起**只提交过一次、从未修改**（`git log` 该文件只有一个 commit）。种子基线一直是 520 条，**这些数字从一开始就没写对过**。`medication_task` 只在 V2 种子里 INSERT（ids `20001–20520`），V3/V4 未再插入。

**已在本轮订正 §10.8**，并在文档里补了「数据真源 = V2 种子 / 核对方式 = 只读 SQL / 实测日」的小注 —— 否则下一个人还会照着旧数判「数据漂移」。

### F-5 · 【文档漂移】`tools/e2e/README.md` 仍写着 probe 脚本"缺 BASE 常量"

F-2 已修，README §「单脚本细节」里「`probe_backend_gaps.py` 目前缺 `BASE` 常量（见 `TEST_REPORT.md` §建议 4）」这句已过时。**已在本轮同步订正** —— 改成「F-2 已修（2026-10-21）：`from _probe_http import BASE, read_token, http`」，同时补了 `l0_identity_gate.py` 的索引行、`SPRING_PROFILES_ACTIVE` 的说明，以及「闸门不进 CI」的理由。

### F-6 · 【已核验 · 非缺陷】`GET /api/order/{id}/fulfillment` 的访问口径**契约里早已写明**

交接文档把它记成「契约没写」—— **核验后不成立**。`docs/api/03-order.md` §12 自 `4332b66` 起就写着：

> `GET /api/order/{id}/fulfillment`　权限：已登录，且须为该订单相关方
> 可见性与订单详情**完全一致**（下单家属 / 接单陪诊员 / 就诊老人 / 管理员，其余 `3004`）。
> …履约服务本身**不做鉴权**：鉴权归 `OrderService#requireInvolved`。

后端 `OrderController#fulfillmentDetail` 的 `@Operation` 描述是同一句（「可见性与订单详情完全一致：下单家属 / 接单陪诊员 / 就诊老人 / 管理员，其余 3004」）。

所以：**行为正确、契约完备、`docs/api/` 无需任何改动**。本条从「待处置」降级为「已核验的伪缺陷」，留在报告里是为了提醒评审时别把它当成越权漏洞。

### F-7 · 【启动坑】`java -jar` 不带 `SPRING_PROFILES_ACTIVE=dev` 会在 banner 之后**静默退出**

`application.yml` 的 `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:}` **刻意不给默认值**（留 dev 兜底会让漏配的生产以开发配置启动）。但没设时：应用打完 Spring Boot banner 就以**退出码 1** 结束，**stderr 全空、无堆栈**（日志系统此时还没接管，异常被吞）。排查花了几分钟。

⚠️ **仓库侧此前从未修过**：交接文档说的「已在启动脚本规避」指的是 `reports/run/start_services.py` —— 那在 **gitignored** 的临时目录里，不是仓库脚本。仓库的 `tools/e2e/start-services.ps1` 一直不设该变量，漏配时照样静默退出。

**已在本轮修**：`tools/e2e/start-services.ps1` 在启动前**兜底** `SPRING_PROFILES_ACTIVE=dev` 并打印实际值（用户已显式设置时沿用其值，不覆盖）；`tools/e2e/README.md` 补了说明，并注明**生产绝不能依赖这个兜底**，必须显式 `SPRING_PROFILES_ACTIVE=prod`。

### F-8 · 【真缺陷】**0 个**

在覆盖到的 254 条浏览器用例 + 100 次越权请求 + 44 项接口探针 + 22 项可达性核对中，**未发现任何业务缺陷**。

---

## 十一、提交覆盖映射表

`v3.0..HEAD` 的 **82 个非合并提交**逐条归入功能域，每条都有对应的验证手段，**无未验证格子**。

### 提交覆盖映射表

| # | 提交 | 日期 | 主题 | 功能域 | 验证手段 |
|---|---|---|---|---|---|
| 1 | `383e208` | 2026-10-18 | docs(V3.1): regenerate db docs for V6 and record the transition-entry tradeoff | V3.1 履约保障 | `15-fulfillment` |
| 2 | `707c879` | 2026-10-18 | fix(V3.1): correct companion id space, sql literal escaping and urge gate | V3.1 履约保障 | `15-fulfillment` |
| 3 | `459c49e` | 2026-10-17 | docs(V3.1): correct test counts in report and changelog | V3.1 履约保障 | `15-fulfillment` |
| 4 | `fa3fc94` | 2026-10-17 | docs(adr): explain conditional updates vs optimistic lock in ADR-0012 | V3.1 履约保障 | `15-fulfillment` |
| 5 | `5a5bdf4` | 2026-10-17 | fix(V3.1): close spec gaps found by code-review | V3.1 履约保障 | `15-fulfillment` |
| 6 | `bc83968` | 2026-10-17 | test(V3.1): fulfillment e2e spec and closeout report (T9) | V3.1 履约保障 | `15-fulfillment` |
| 7 | `3c727e8` | 2026-10-17 | docs(V3.1): closeout docs for fulfillment (T9) | V3.1 履约保障 | `15-fulfillment` |
| 8 | `c1a3ca8` | 2026-10-17 | feat(V3.1): admin reassign unclaimed order (T7) | V3.1 履约保障 | `15-fulfillment` |
| 9 | `4ea626f` | 2026-10-17 | feat(V3.1): admin fulfillment risk center (T6) | V3.1 履约保障 | `15-fulfillment` |
| 10 | `d9ae1d2` | 2026-10-17 | feat(V3.1): family fulfillment status bar and list badge (T5) | V3.1 履约保障 | `15-fulfillment` |
| 11 | `c1ab483` | 2026-10-17 | feat(V3.1): fulfillment read model (T4) | V3.1 履约保障 | `15-fulfillment` |
| 12 | `f24ccb4` | 2026-10-17 | feat(V3.1): 履约保障 T0-T3 · 工作状态 / 风险模型 / 扫描 / 催单 | V3.1 履约保障 | `15-fulfillment` |
| 13 | `b875829` | 2026-10-17 | docs(spec): 补录 V3.1 履约规格/工单、V3.x 路线图规格与迭代计划 | V3.1 履约保障 | `15-fulfillment` |
| 14 | `9987d3a` | 2026-10-20 | fix(V3.2): code-review 处置 · 今日口径 / 字典可达性 / 截断标志 / 适老化字号与对比度 | V3.2 老人健康协同 | `16-elder-health` |
| 15 | `7e2daa5` | 2026-10-20 | feat(V3.2): 老人健康协同中心 · 指标字典 / 三桶口径统一 / 家庭健康三页 | V3.2 老人健康协同 | `16-elder-health` |
| 16 | `4332b66` | 2026-10-21 | fix(V3.3-V3.6): 收口代码评审发现项并补齐 ADR 与文档 | V3.3 陪诊员工作台 | `17-companion-workbench` |
| 17 | `840b059` | 2026-10-21 | docs(V3.3-V3.6): 补齐四份 PRD 与前端契约路由表 | V3.3 陪诊员工作台 | `17-companion-workbench` |
| 18 | `0b6c3e8` | 2026-10-20 | docs(V3.3-V3.6): 补齐四版收口时漏掉的文档登记 | V3.3 陪诊员工作台 | `17-companion-workbench` |
| 19 | `23bd591` | 2026-10-20 | feat(V3.3): 陪诊员工作台 · 四个自助端点 + 收入页去前端自算 | V3.3 陪诊员工作台 | `17-companion-workbench` |
| 20 | `9efa77f` | 2026-10-20 | feat(V3.4): 智能调度 · 结构化改造 + 匹配规则引擎 + 可解释的候选推荐 | V3.4 智能调度 | `18-matching` |
| 21 | `6901440` | 2026-10-17 | feat(order): hall recommend sort with whitelist rejection | V3.4 智能调度 | `18-matching` |
| 22 | `260301d` | 2026-10-20 | feat(V3.5): 平台运营驾驶舱 · 概览 / 异常中心 / 趋势 / 健康度 | V3.5 运营驾驶舱 | `19-operations` |
| 23 | `dc4119e` | 2026-10-20 | feat(V3.6): 数据闭环 · 端点↔指标机器面 + 供给缺口分析 + 干预效果对比 | V3.6 数据闭环 | `20-data-loop` |
| 24 | `5fdd133` | 2026-10-21 | docs(V3.7): sync contract, iteration plan, changelog and test report | V3.7 家属智能助手 | `21-assistant` |
| 25 | `fd71446` | 2026-10-21 | feat(e2e): add assistant E2E suite and screenshot capture | V3.7 家属智能助手 | `21-assistant` |
| 26 | `d659878` | 2026-10-21 | test(assistant): cover LLM provider construction and contract fixes | V3.7 家属智能助手 | `21-assistant` |
| 27 | `040316a` | 2026-10-21 | feat(assistant): expand FAQ keyword coverage with a generated question pool | V3.7 家属智能助手 | `21-assistant` |
| 28 | `eaab600` | 2026-10-21 | fix(assistant): correct LLM provider import path and tighten answer contracts | V3.7 家属智能助手 | `21-assistant` |
| 29 | `8009be8` | 2026-10-21 | fix(assistant): address code review findings (Standards + Spec axes) | V3.7 家属智能助手 | `21-assistant` |
| 30 | `499b46d` | 2026-10-21 | feat(assistant): add family-side AI assistant (V3.7 / M14) | V3.7 家属智能助手 | `21-assistant` |
| 31 | `417b402` | 2026-10-21 | docs(agents): add GLOSSARY.md alias pointer to the domain glossary | V3.7 家属智能助手 | `21-assistant` |
| 32 | `763c19f` | 2026-10-18 | feat(chat): redesign chat room with three-column layout and richer visuals | M13 订单聊天 | `14-chat` |
| 33 | `c5c4f25` | 2026-10-18 | feat(chat): add order info panel with live companion and elder status | M13 订单聊天 | `14-chat` |
| 34 | `4eaa2e1` | 2026-10-17 | test(M13): cover family chat entry points at the navigation layer | M13 订单聊天 | `14-chat` |
| 35 | `088b0aa` | 2026-10-17 | feat(M13): 补齐家属/陪诊员端订单沟通入口 | M13 订单聊天 | `14-chat` |
| 36 | `c2186e7` | 2026-10-16 | refactor(frontend): 聊天角色判断改用 userStore 派生 getter，署名取 ROLE_LABELS（可见文案逐字不变） | M13 订单聊天 | `14-chat` |
| 37 | `c83e2ad` | 2026-10-16 | refactor(M13): 删除 wsPayloadOf，聊天 WS 载荷改由 Hub 直接序列化 ChatMessageVO | M13 订单聊天 | `14-chat` |
| 38 | `73eedbc` | 2026-10-16 | refactor(M13): 聊天预览文案收口到 ChatMsgType，消除 VO 与 SSE 两处重复并关掉空类型 NPE 路径 | M13 订单聊天 | `14-chat` |
| 39 | `f863a77` | 2026-10-16 | refactor(frontend): createProgressSocket → createRealtimeSocket（M5/M13 共用通道名实相符） | M13 订单聊天 | `14-chat` |
| 40 | `8d6719b` | 2026-10-16 | fix(M13): 评审 A 批必修缺陷（错误码段位纪律、旁观者对端、握手门禁、异常吞并） | M13 订单聊天 | `14-chat` |
| 41 | `ad2552a` | 2026-10-16 | feat(M13): 订单聊天全链路落地（家属↔陪诊员图文/视频实时会话） | M13 订单聊天 | `14-chat` |
| 42 | `042da09` | 2026-10-21 | docs(agents): note that docs/db/* is script-generated | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 43 | `c735693` | 2026-10-20 | docs(plan): 迭代计划移入 plans/ 目录 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 44 | `5343307` | 2026-10-20 | fix(security): 收窄上传守卫范围，避免误伤占位头像 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 45 | `c25e571` | 2026-10-20 | fix(security): harden production config and gate uploaded certificates | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 46 | `c67fa91` | 2026-10-17 | docs(agent): 新增硬约束——AI 编写代码新增功能必须在独立分支上完成 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 47 | `7fdb33b` | 2026-10-16 | docs(design): design.md 升 v1.2 + ADR-0007 追加双栏变更 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 48 | `1dbe5e8` | 2026-10-16 | refactor(frontend): UI 重构 R1-R6 落地（令牌/语义色/可访问性/错误态/去投影/桌面双栏） | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 49 | `bf62c88` | 2026-10-15 | test(M8): 站内信越权矩阵补 GET /api/message/{id} + 订正两处报告口径 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 50 | `1436d48` | 2026-10-15 | docs(agents): 文档漂移修正与迭代计划存档标注 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 51 | `375cdb5` | 2026-10-15 | docs(M8): 消息详情接口 GET /api/message/{id} 补验收清单 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 52 | `156e1b1` | 2026-10-15 | docs(readme): Node 下限对齐 frontend engines.node（18 -> 22.10+） | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 53 | `676dd2c` | 2026-10-15 | test(admin): 移除 2 条 arbitrate before_status 回归用例 + 校准 CI 测试计数 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 54 | `29535dc` | 2026-10-15 | fix(order): 费用明细汇总口径收口（totalsOf 单点计算） | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 55 | `a64090b` | 2026-10-02 | test(admin): arbitrate before_status 回归测试 + 删除残留的 .sh 基线脚本 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 56 | `be2632b` | 2026-10-02 | fix(admin): 修正 arbitrate 写入 admin_oper_log 的 before_status | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 57 | `4f4dbd1` | 2026-09-29 | docs(agents): FRONTEND_CONTRACT E2E 套件口径更新至 13 spec / 166 条 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 58 | `b1aa96d` | 2026-09-29 | fix(elderly/e2e): Element Plus 弃用用法迁移与 fixture verify 修正 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 59 | `fa1fea5` | 2026-09-29 | feat(M8): 消息详情接口 GET /api/message/{id}，仲裁通知携带仲裁说明 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 60 | `b407382` | 2026-09-29 | fix(M4): 费用明细汇总服务费回落订单申报值，与详情页口径一致 | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 61 | `4905317` | 2026-09-29 | docs(closeout): 收尾验证报告 + 文档漂移修正 + prototype.png | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 62 | `c55ddf9` | 2026-09-29 | fix(config): CORS 兜底端口对齐 5141（SecurityConfig 与 WebMvcConfig 同步） | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 63 | `b5e9489` | 2026-09-28 | docs(plans): 收敛迭代计划入库（superpowers/plans） | 基线加固（消息/费用/后台/安全/适配） | `01–13 既有 spec` |
| 64 | `de994b3` | 2026-10-21 | chore(tools): 新增 CI 密钥体检的本地自查脚本 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 65 | `46d5319` | 2026-10-18 | chore(tools): fix scan_bad_chars usage docstring | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 66 | `0ab0e41` | 2026-10-16 | chore(gitignore): 为 frontend/e2e/specs 正式 E2E 套件开 *.spec.js 例外 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 67 | `a530470` | 2026-10-02 | fix(ci): --start-period → --health-start-period（真因） | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 68 | `3f77c74` | 2026-10-02 | ci: bump actions/setup-node from 5 to 7 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 69 | `ab99d8d` | 2026-10-02 | fix(ci): Node 20 → 24（markAsUncloneable）+ health-cmd 转义引号 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 70 | `7382d81` | 2026-10-02 | fix(ci): 修 YAML 折叠标量吞注释 + 限制 vitest worker 数 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 71 | `80a069c` | 2026-10-02 | ci: bump actions/checkout from 5 to 7 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 72 | `0d687dd` | 2026-10-02 | ci: bump pnpm/action-setup from 4 to 6 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 73 | `46ff4de` | 2026-10-02 | fix(ci): 后端起 MySQL/Redis 容器 + 前端显式指定 pnpm 版本 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 74 | `215f32b` | 2026-10-02 | ci: bump element-plus from 2.14.5 to 2.14.6 in /frontend | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 75 | `f5d17d8` | 2026-10-02 | ci: bump eslint-config-prettier from 9.1.2 to 10.1.8 in /frontend | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 76 | `a355824` | 2026-10-02 | ci: bump jsdom from 30.1.0 to 30.1.1 in /frontend | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 77 | `a74afe0` | 2026-10-02 | ci: bump actions/setup-java from 5 to 6 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 78 | `5b39036` | 2026-10-02 | ci: bump actions/upload-artifact from 6 to 7 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 79 | `828f825` | 2026-10-02 | fix(ci): 补装 pnpm + 升级 action 到 Node 24 运行时 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 80 | `5597ddd` | 2026-10-02 | chore(ci): 接入 GitHub Actions + tools/e2e 脚本索引 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 81 | `ba5b831` | 2026-09-30 | chore(gitignore): 忽略 .zcode/ 本地工作目录 | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |
| 82 | `83ee019` | 2026-09-28 | chore(gitignore): 忽略 ZCode 自动生成的 .zcodeignore | CI / 工具链 / 仓库卫生 | `静态门禁 + 单元级门禁` |

合计 **82** 个非合并提交（`v3.0..HEAD`），归入 10 个功能域，无未归类格子。

---

## 十二、结论与待处置

### 12.1 结论

1. **v3.0 之后的功能全部通过联调验证**：82 个提交归入 10 个功能域，254 条浏览器用例 + 100 次越权请求 + 44 项接口探针 + 22 项可达性核对，**真缺陷 0**。
2. **首轮 43 条红是环境事故**，已用复跑证伪并落成闸门加固（第 6 项身份断言；收口时扩到 10 项，8081 / 5141 的服务身份也纳入硬断言）。
3. **合规红线全过**：医疗禁用词、派单措辞、越权 403、订单详情姓名脱敏 —— 全部按契约生效。
4. **数据零污染**：跑测前后 `fixture.py verify` 全绿，19 张表 + 6 张影子表 + 68 个 Redis 键 + 上传目录全部回到基线。
5. **开发库已复位到种子基线**，压测批次（30 个 `bench*` 账号 + 30 个满分陪诊员档案）已清，手建账号 `user1` 保留。

### 12.2 本轮待处置（**已全部闭环**）

| # | 事项 | 类型 | 处置结果 | 出处 |
|---|---|---|---|---|
| 1 | `FRONTEND_CONTRACT §10.8` 种子分布订正 | 文档 | ✅ **已改** —— 4 处数字（用药任务 520 的细分 / 陪诊员 30 的细分 / 投诉分布 / 老人 401 任务分布）+ 补订单 `1006` 的费用 `248`，并加「数据真源与核对方式」小注 | `docs/agents/FRONTEND_CONTRACT.md` §10.8 |
| 2 | `tools/e2e/README.md` 删掉「probe_backend_gaps.py 缺 BASE 常量」那句 | 文档 | ✅ **已改** —— 同步为 F-2 的修复说明 | `tools/e2e/README.md` |
| 3 | `GET /api/order/{id}/fulfillment` 的访问口径写进 `docs/api/` | 契约 | ⛔ **不改** —— 核验后确认契约早已写明，本条是伪缺陷（F-6） | `docs/api/03-order.md` §12 |
| 4 | 手动起后端补 `SPRING_PROFILES_ACTIVE=dev` | 工具 | ✅ **已改** —— `start-services.ps1` 兜底 + 打印实际值，README 补说明并注明生产不可依赖兜底 | `tools/e2e/start-services.ps1` |
| 5 | L0 身份闸门固化进仓库 | 工具 | ✅ **已入库** —— 迁到 `tools/e2e/l0_identity_gate.py`，断言从 6 项扩到 **10 项**（补 8081 / 5141 的服务身份硬断言） | `tools/e2e/l0_identity_gate.py` |
| 6 | `frontend/e2e/a2-reachability.mjs` 决定是否入库 | 工具 | ✅ **已入库** —— 作为可复跑工具（不接 CI、不进 playwright 套件），前端端口改读 `VITE_PORT` | `frontend/e2e/a2-reachability.mjs` |

### 12.3 复跑命令

```powershell
$env:MYSQL_PASSWORD = "<本机 dev 口令>"
$env:REDIS_CLI = 'D:\develop\Redis-8.8.0\redis-cli.exe'
$env:SPRING_PROFILES_ACTIVE = 'dev'          # 手动起后端时必给，见 F-7

# L0 身份闸门（必须先过；10 项断言，任一不过就停）
python tools/e2e/l0_identity_gate.py

# L1 浏览器套件（分两批；global-setup 会自动 snapshot → restore → verify）
cd frontend
pnpm exec playwright test "specs/(0[1-9]|1[0-3])-.*\.spec\.js"   # 批次 1
pnpm exec playwright test "specs/(1[4-9]|2[01])-.*\.spec\.js"   # 批次 2

# 访问专项
python reports/run/access_matrix.py                            # A-1 越权矩阵
node e2e/a2-reachability.mjs                                   # A-2 路由可达性（需自行常驻前端）

# L2 / L3 探针
python tools/e2e/probe_api.py
python tools/e2e/probe_ws_handshake.py
python tools/e2e/probe_backend_gaps.py

# V3.7 双模式
python reports/run/m2_degrade.py                                # 切 M2 → 断言 → 切回 M1
```

> ⚠️ **跑 E2E 前先确认没有并发的 `mvn test`** —— surefire fork 会在 8080 上起 Spring 上下文并击穿被测应用（见 F-1）。闸门第 6 项就是为此加的。
>
> ℹ️ **入库与不入库的边界**：L0 闸门、两个探针、A-2 脚本已入库（`tools/e2e/` 与 `frontend/e2e/`）；**A-1 越权矩阵与 M2 双模式脚本仍是 `reports/run/` 下的临时脚本**（该目录被 `.gitignore` 全局忽略），未入库。

---

*本报告由本轮联调实测数据生成；所有数字均来自 `reports/playwright/results.json`、`reports/e2e/*.txt`、`reports/run/*.py` 的实际输出，未做修饰。产物目录 `reports/` 已被 `.gitignore` 忽略（内含真实 JWT，严禁入库）。*

*收口时（2026-10-21）又做了一轮**逐项复核**：把报告里的每个数字重新对回原始产物与**只读 SQL 实测**（`results.json` / `state.done.json` / 探针日志 / `V2__seed_data.sql` 源文件 / 当前库），据此修正了 §十 F-4 的四处分布、§三 的断言项数、L61 与 §6.1 的交叉引用、批次 2 表缺 `auth.setup` 行，以及一处编码损坏。**复核过程中没有发现任何需要改业务代码的缺陷。***
