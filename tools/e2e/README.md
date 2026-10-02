# tools/e2e · 脚本索引

> 9 个脚本，没有统一的 README 之前，「哪个能跑、哪个会写库、哪个要口令」全靠逐个打开看。
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
| `probe_backend_gaps.py` | 后端功能缺口探测 | — | ✅ | — |
| `probe_ws_handshake.py` | WebSocket 握手探测 | — | — | — |
| `_probe_http.py` | 轻量 HTTP 客户端，复用 `probe_api` 的 http / login / BASE 逻辑 | — | ✅ | — |

`_` 前缀 = 内部模块，不作为独立入口调用。

## 「能进 CI」的判据

只有 `check_api_imports.py` 满足，其余全部排除，原因各不相同：

- **需要 MySQL / Redis / 后端进程** —— `fixture.py`、`restore_clean_baseline.py`、四个 `probe_*`、
  两个 `*.ps1` 都是本机联调脚本，CI 环境没有这些常驻服务，跑必失败
- **写库** —— `fixture.py` 与 `restore_clean_baseline.py` 会 `DELETE` / `REPLACE INTO`，
  CI 上跑等于破坏共享环境
- **有副作用但无判据** —— `start/stop-services.ps1` 只管进程生命周期，没有退出码语义可判

`.github/workflows/ci.yml` 目前只接了 `check_api_imports.py` 的等价检查
（走 `pnpm run lint:check` 覆盖 eslint，未单独调此脚本）。**新增能进 CI 的脚本时，
在 workflow 里补上对应 step，并在这里改「能进 CI」列。**

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

### `probe_api.py` / `probe_backend_gaps.py` / `probe_ws_handshake.py`

三个探针，跑之前**必须先 `start-services.ps1`**。Windows 下需先设 UTF-8：

```powershell
$env:PYTHONUTF8=1; $env:PYTHONIOENCODING="utf-8"
python tools/e2e/probe_backend_gaps.py
```

`probe_backend_gaps.py` 目前缺 `BASE` 常量（见 `TEST_REPORT.md` §建议 4），
导致上传 / CHECKIN / 魔数等写路径探针解锁不了。

## 编码约定

本机 PowerShell 读这些脚本的输出会乱码，除非设 `$env:PYTHONUTF8=1`。
脚本内部靠 `# -*- coding: utf-8 -*-` 只管**读文件**，管不了**打控制台**。
