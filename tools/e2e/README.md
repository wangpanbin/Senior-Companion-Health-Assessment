# tools/e2e · 脚本索引

> 11 个脚本，没有统一的 README 之前，「哪个能跑、哪个会写库、哪个要口令」全靠逐个打开看。
> 这张表是那张索引。**动脚本前先看本表**，加脚本时同步补一行。

## 三条红线（先读这张表再看下面）

1. **产物不是脚本。** 所有脚本的**产出**落在 `reports/`，该目录已被 `.gitignore` 全局忽略 ——
   `reports/e2e/fixture/state.json` 之类含真实 JWT / storageState，是与 `jmeter_token.txt` 同级的红线。
   脚本本身入库，产出永不入库。
2. **口令一律走环境变量。** 任何脚本都不内置默认口令；`MYSQL_PASSWORD` 未设置时直接 `exit 1`。
3. **写库脚本默认 dry-run。** 破坏性脚本必须显式 `--execute`（或 `-Execute`）才真删，
   且删多表会二次确认。解析不出真实基线时**跳过并报错，不猜**。

## 索引表

| 脚本 | 作用 | 写库 | 需凭据 | 能进 CI |
|---|---|:---:|:---:|:---:|
| `check_api_imports.py` | 静态检查 `frontend/src` 的 `import { } from '@/api/xxx'` 引用完整性 | — | — | ✅ |
| `start-services.ps1` | 拉起后端（8080）。MySQL/Redis 走本机常驻，不负责起 | — | ✅ | — |
| `stop-services.ps1` | 停 8080 的 Java 进程 | — | — | — |
| `fixture.py` | 写路径 E2E 夹具：`snapshot` / `restore` / `verify`，让库回到跑之前 | ✅ | ✅ | — |
| `restore_clean_baseline.py` | 把库与 Redis 恢复到种子基线（删写路径新增行） | ✅ | ✅ | — |
| `probe_api.py` | 接口冒烟探测（与 Playwright 独立） | — | ✅ | — |
| `probe_access_matrix.py` | **A-1 越权矩阵**（25 端点 × 4 角色 = 100 次真实 HTTP，0 违规判据，见 TEST_REPORT §6.1） | — | ✅ | — |
| `probe_backend_gaps.py` | 后端功能缺口探测 | — | ✅ | — |
| `probe_ws_handshake.py` | WebSocket 握手探测（开跑先探测 8080/5141 是否有监听，进程没起会直接指路而非误归因） | — | — | — |
| `l0_identity_gate.py` | **端口与服务身份闸门**（跑任何 E2E 前必须先过） | — | — | — |
| `_probe_http.py` | 轻量 HTTP 客户端，复用 `probe_api` 的 http / login / BASE 逻辑 | — | ✅ | — |

`_` 前缀 = 内部模块，不作为独立入口调用。

## 「能进 CI」的判据

只有 `check_api_imports.py` 满足，其余全部排除，原因各不相同：

- **需要 MySQL / Redis / 后端进程** —— `fixture.py`、`restore_clean_baseline.py`、五个 `probe_*`、
  `l0_identity_gate.py`、两个 `*.ps1` 都是本机联调脚本，CI 环境没有这些常驻服务，跑必失败
- **写库** —— `fixture.py` 与 `restore_clean_baseline.py` 会 `DELETE` / `REPLACE INTO`，
  CI 上跑等于破坏共享环境
- **有副作用但无判据** —— `start/stop-services.ps1` 只管进程生命周期，没有退出码语义可判

⚠️ **闸门为什么也不进 CI**：`l0_identity_gate.py` 判的是「8080 / 8081 / 5141 上跑的**是不是本项目的进程**」，
前提是那三个端口已经被本机起起来了。CI 里没有这套常驻服务（后端 job 只起 MySQL / Redis 容器），
跑它只会得到「端口没人监听」的全红，判不出任何东西。它的正确用法是**本机跑 E2E 之前先跑一遍**。

⚠️ `.github/workflows/ci.yml` 目前**没有**接 `check_api_imports.py`（全仓 grep 该脚本名只在本
README 命中）：CI 的三个 job 是后端 `mvn verify`（带 MySQL / Redis service 容器）、
前端 `pnpm run lint:check` + `vitest run` + `build`、以及 JWT / 明文口令 / 手机号密钥扫描 ——
**没有一步等价于本脚本的 `@/api` 引用完整性检查**。它也不在 `tools/verify_all.ps1` 的段内，
是**本机手动门禁**（按下面的命令自己跑）。**新增能进 CI 的脚本时，在 workflow 里补上对应
step，并在这里改「能进 CI」列。**

## 单脚本细节

### `check_api_imports.py`（唯一可直接在任意环境跑）

静态扫 `frontend/src/**/*.vue` 与 `*.js`，核对每个 `@/api/xxx` 引用都真实存在。
本机实测：73 条引用，全通，exit 0。**无外部依赖**，纯 AST 解析。

```powershell
python tools/e2e/check_api_imports.py
```

### `restore_clean_baseline.py`

**破坏性。** 基线从 `backend/sql/V*.sql` 解析真实种子水位，**不再用 `MAX(id)-100` 猜**。

> 历史教训：旧 `.sh` / `.ps1` 版对 16 张表统一减 100，实测会误删 **629 行种子数据**
> （`companion_order` 64 行种子订单整表清空）。已删除，勿再写回那种逻辑。

```powershell
python tools/e2e/restore_clean_baseline.py                      # dry-run，看计划
python tools/e2e/restore_clean_baseline.py --execute            # 真删（二次确认）
python tools/e2e/restore_clean_baseline.py --tables internal_message,companion_order
python tools/e2e/restore_clean_baseline.py --execute --yes --force   # 跳过确认 + 越过阈值守卫
```

`--force` 用于「单表待删超 30% 或 500 行」的守卫 —— 超阈值说明基线判断可能错了。
口令走 `MYSQL_PWD`（由 `MYSQL_PASSWORD` 转换），**不进 argv**，避免被 `tasklist` 看到。

### `fixture.py`

**破坏性。** 按 `docs/agents/FIXTURE_ROLLBACK_PLAN.md` 实现，白名单种子行用影子表
`CREATE TABLE _e2e_bak_<t> AS SELECT *` 整行备份，还原时 `REPLACE INTO` 回写 ——
列级零损耗，不用自己解析 NULL / datetime / decimal。

不依赖 pymysql（本机 conda 3.14 没有），直接驱动 `mysql.exe` 的 `-N -B` 批处理模式。

```powershell
python tools/e2e/fixture.py snapshot     # 备份白名单行 + 记录各表水位
python tools/e2e/fixture.py restore     # 还原
python tools/e2e/fixture.py verify      # 校验还原结果
```

水位写在 `reports/e2e/fixture/state.json`（gitignore 内），restore 后改名 `state.done.json`。

### `probe_api.py` / `probe_access_matrix.py` / `probe_backend_gaps.py` / `probe_ws_handshake.py`

四个探针，跑之前**必须先 `start-services.ps1`**，且**必须先跑过 Playwright 的 setup 项目**
（`pnpm -C frontend exec playwright test --project=setup`）—— 探针的令牌来自 setup 落下的
`reports/playwright/.auth/<account>.json`，**没有它探针一步都跑不了**。

⚠️ **令牌 TTL 只有 120 分钟**（`application.yml` 的 access token 有效期）。setup 落盘隔天或
隔几小时后再跑，100 次请求会全部 401、报错文案只说「未登录或登录已失效」，极易误判成
「鉴权回归」（2026-10-25 验收实测踩过）。现在 `_probe_http.read_token` 会解析 JWT `exp`
**提前识别过期**并报 `[EXPI]` 指路重跑 setup；拿不准就先单独自检一次：

```powershell
python tools/e2e/_probe_http.py    # 逐账号 [ OK ]/[EXPI]/[MISS]，并显示剩余有效期
```

Windows 下需先设 UTF-8：

```powershell
$env:PYTHONUTF8=1; $env:PYTHONIOENCODING="utf-8"
python tools/e2e/probe_backend_gaps.py
```

`probe_access_matrix.py` 复现 `docs/reports/TEST_REPORT.md §6.1` 的 A-1：25 个 V3.1–V3.7 新增
端点 × 4 角色（admin / fam001 / elder001 / **comp007**，comp007 是订单 1007 的接单陪诊员）=
100 次真实 HTTP。判据分两种、不能混用：有 `@PreAuthorize` 的「role 型」端点，非授权角色必须
HTTP 403；只做资源归属的 `/order/1007/fulfillment`，无关方是业务码 **3004** 而非 403。全绿
退出码 0，任一违规退出码 1，令牌缺失/后端不在线退出码 2。

`probe_backend_gaps.py` 之前缺 `BASE` 常量（上传 / CHECKIN / 魔数等写路径探针全被 `NameError` 卡住）——
**F-2 已修（2026-10-21）**：`from _probe_http import BASE, read_token, http`。
`probe_ws_handshake.py` 之前写死旧前端端口 5410（项目早已改到 5141）——**F-3 已修（2026-10-21）**：
改读 `VITE_PORT`（缺省 5141），标签与归因文案一并参数化；**2026-10-25 再补**：归因前先探测
8080 / 5141 是否有监听 —— vite 进程没起时不再输出「代理没生效」的误导归因，而是直接指路 `pnpm dev`。

### `l0_identity_gate.py`（跑任何 E2E 之前**必须**先过）

**只读**脚本：只做 HTTP GET + WMI 进程查询，**不写库、不改文件、不起停任何服务**。纯标准库，无外部依赖。

```powershell
python tools/e2e/l0_identity_gate.py
```

它回答一个问题：**这三个端口上跑的到底是不是本项目的服务？**（共 **10 项断言**，逐条 PASS/FAIL）

| # | 端口 | 断言 |
|---|---|---|
| 1 | 8080 | HTTP 200 |
| 2 | 8080 | `code == 200` |
| 3 | 8080 | `data.application == "nianglin"` |
| 4 | 8080 | `data.profiles` 含 `dev` |
| 5 | 8080 | `data.status == "UP"` |
| 6 | 8080 | 顶层字段是 `message` 而非 `msg` |
| 7 | 8080 | **监听进程命令行含 `nianglin` 且不含 `surefire`**（是本项目的进程、不是测试 fork） |
| 8 | 8081 | `/healthz` 的 `service == "nianglin-ai-assistant"` |
| 9 | 8081 | **监听进程命令行含 `uvicorn` 且含 `app.main:app`** |
| 10 | 5141 | 前端在线 **且** **监听进程命令行含 `vite`** |

> ⚠️ **为什么不能只看健康响应**：「端口在监听」和「HTTP 200」**都证明不了这个端口上跑的是谁**。
> 仓库里 26 处 `@SpringBootTest` 全是裸注解（默认 `webEnvironment = MOCK`，**不起 Tomcat**），
> 所以测试本身不会抢端口；但 `logback-spring.xml` 的 `LOG_HOME` 是相对路径 `./logs`，
> **surefire fork 与被测应用写的是同一个日志文件**（`backend/logs/nianglin.log` 里现存 4088 处 surefire 栈帧）
> —— 只看日志极易误判成「surefire 抢了端口」。
> 真正会击穿后端的是**并发 `mvn test` 重编译 `backend/target/classes`**，把从 `target/classes` 运行的后端打断。
> **所以身份只能靠「监听进程的命令行」判。**
>
> ⚠️ 第 7 项**没有**死要求 `-jar`：本仓库的 `start-services.ps1` 用 `mvn spring-boot:run` 拉后端，
> 它的命令行里只有 classpath、**没有 `-jar`** —— 死要求 `-jar` 会把一个完全正常的后端误判成「端口被占」。
>
> 任一断言不过 → 退出码 1，并提示「端口上可能不是本项目服务，禁止进入 E2E」。

端口可用环境变量覆盖：`NIANGLIN_BACKEND_PORT` / `NIANGLIN_AI_PORT` / `NIANGLIN_WEB_PORT`。

### `start-services.ps1`（起后端；**必须显式给 profile**）

```powershell
$env:MYSQL_PASSWORD = "<你的本机 dev 口令>"     # 不设直接 exit 1
.\tools\e2e\start-services.ps1
```

⚠️ **`SPRING_PROFILES_ACTIVE` 必须给**：`application.yml` 的 `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:}`
**刻意不给默认值**（留 `dev` 兜底会让漏配的生产直接以开发配置启动 —— SQL 明文日志 + 接口文档全开 + 开发默认密钥，三条高危同时落地）。
漏配时的表现是**应用打完 Spring Boot banner 就以退出码 1 静默结束，stderr 全空、无堆栈**（此时日志系统还没接管，异常被吞），排查极难。

本脚本**只用于本机联调**（会拉起 MySQL / Redis 的 Windows 服务、用 `mvn spring-boot:run`），
所以它在用户没设时**兜底 `dev`** 并打印实际值（用户已设则沿用、不覆盖）。
> 🔒 **生产环境绝不能依赖这个兜底** —— 生产必须显式 `SPRING_PROFILES_ACTIVE=prod`。

## 编码约定

本机 PowerShell 读这些脚本的输出会乱码，除非设 `$env:PYTHONUTF8=1`。
脚本内部靠 `# -*- coding: utf-8 -*-` 只管**读文件**，管不了**打控制台**。
