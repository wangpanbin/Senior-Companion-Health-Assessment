# backend/sql/tools —— 数据库脚本生成与校验工具

> 「唯一真源」是 `backend/sql/` 下的 `V1` / `V2` / `V3` 三个 SQL 脚本。
> 本目录的脚本只负责**生成**与**校验**，不参与运行时，也不会被打进 jar
> （`pom.xml` 的 `<includes>*.sql</includes>` 不递归子目录，已实测确认）。

## 文件清单

| 文件 | 作用 | 产物 |
|---|---|---|
| `gen_entity.py` | 解析 `V1__init_schema.sql`，生成 MyBatis-Plus Entity 与 Mapper | `backend/src/main/java/org/company/nianglin/{entity,mapper}/*.java` |
| `gen_seed.py` | 生成种子数据（随机种子固定，结果可复现） | `backend/sql/V2__seed_data.sql` |
| `gen_db_docs.py` | 解析 DDL + 读取真实行数，生成数据库文档 | `docs/db/*.md` |
| `GenSeedSecrets.java` | 一次性工具：产出 BCrypt 密码哈希与 AES 身份证密文 | 控制台输出（粘贴进 `gen_seed.py`） |
| `verify_data.sql` | 校验行数、中文编码、密码/密文、边界场景、枚举分布 | 查询结果 |
| `explain_index.sql` | 7 条高频查询的 `EXPLAIN`，验证索引命中 | 查询结果 |
| `cleanup_orphan_sms_logs.sql` | **一次性存量清理**：把指向已删除 / 已停用用药任务的 `PENDING` / `FAILED` / `DEAD` 短信回执置 `VOIDED`；幂等、可重复执行，不参与 Flyway | 查询结果 / UPDATE 影响行数 |
| `e2e_auth.py` | **M2 端到端实测**：真实 HTTP 打 8080，40 项断言（错误码 / 越权 / 验证码防重放 / 登出黑名单 / 改密失效 / 锁定），自带测试数据清理 | 控制台 PASS/FAIL 汇总 |
| `e2e_user_profile.py` … `e2e_statistics.py` | M3–M10 各模块端到端实测（共 8 个，命名对应模块） | 控制台 PASS/FAIL 汇总 |
| `e2e_user_deactivate.py` | **V4 P0-1 账号注销 + 数据导出实测**：20 项断言（导出契约与四角色权限 / 注销四道闸门 / 11001 进行中订单拦截 / 注销后旧令牌 401、去标识化落库），注销全链路只在当轮注册的临时账号上跑 | 控制台 PASS/FAIL 汇总 |
| `e2e_order_medication_draft.py` | **V4 P1-2 订单转用药计划草稿实测**：成功转草稿且响应无 dosage / 未匹配药名 / 非本单家属 3004 / 未完成 409 / 重复 409 / 非 FAMILY 403 / elderId 不符 400，自带订单与 Redis 键清理 | 控制台 PASS/FAIL 汇总 |
| `e2e_sms_mock.py` | **V4 P1-3 通知偏好 + 漏服站外短信实测**：偏好读写与回读 / 关闭短信后只发站内信且无 SENT 行 / 同一任务两位家属各一条（幂等键含 userId）/ 单用户日频控超限被拦；造 5 条逾期任务、临时绑定 102→401、按 biz_id 清理、恢复偏好与关系快照 | 控制台 PASS/FAIL 汇总 |
| `e2e_settlement.py` | **V4 P2-1 结算闭环实测**：4 端点鉴权矩阵 / 台账口径与筛选 / 首次核定落库与留痕 / 重复核定 200 且不重复留痕 / 未完成 8004 与不存在 3001 / 批量去重保序与逐项成败 / 导出 12 列与脱敏；**会临时核定一笔种子订单**，`finally` 还原订单快照并按基线删除本轮 `admin_oper_log`、`sys_login_log` | 控制台 PASS/FAIL 汇总 |
| `cleanup_util.py` | **e2e 公共清理助手**（BUG_LIST L1）：`max_id` 记基线 / `delete_since` 按基线删行（基线≤0 拒绝执行）/ `flush_unread_cache` 清 `message:unread:*` | 被 9 个 e2e 脚本 import |
| `bench_setup.py` / `bench_seed_orders.py` / `bench_teardown.py` | JMeter 压测的数据准备 / 造 50 单 / 清理 | `bench_tokens.csv`、`bench_meta.json` |
| `jmeter_m4_accept.jmx` | M4 50 并发抢单（`@Version` 乐观锁防超卖） | `m4_*.jtl/csv` |
| `jmeter_m5_reconnect.jmx` | **M5 重连补齐风暴**：50 客户端并发拉 `/progress` + `/checkins` | `m5.jtl` |
| `jmeter_m6_concurrent_confirm.jmx` | **M6 并发确认服药**（同一任务 50 并发）+ 20 并发读日历 | `m6.jtl` |
| `jmeter_fixture.py` | M5/M6 压测夹具：快照 / 还原种子任务 20002、登录拿 token、核对结论 | `jmeter_fixture_snapshot.json`、`jmeter_token.txt` |
| `jmeter_m10_overview.jmx` | M10 统计总览并发读 | `m10_*.jtl/csv` |

> `e2e_auth.py` 不属于数据库脚本范畴，放在这里是因为它同样需要
> 「连着真实的 MySQL / Redis 才跑得起来」—— 与其它工具的前置条件一致。
> 它读 Redis 只为拿验证码明文（图片识别不是测试该做的事），其余全部走正常接口。
>
> `cleanup_orphan_sms_logs.sql` 只用于**存量历史数据**：执行前先跑文件内第 1 段 dry-run SELECT，
> 第 2 段把孤儿回执置 `VOIDED`，第 3 段复核应为 0。新代码已把该行为内置到
> 「删除 / 停用任务」与「频控 SQL」两处，脚本不替代代码修复。

## 为什么用脚本而不是手写 SQL

20 张表、每张业务表 ≥ 30 行，且订单 / 打卡 / 服药任务之间存在 id 关联与
状态一致性约束，手写必然出错。脚本内所有随机数都固定 seed，
**同一版本脚本每次生成结果完全一致**。

## 关键约束（改脚本前必读）

1. **已由 Flyway 执行过的脚本不得再生成覆盖。**
   `application-dev.yml` 里 `validate-on-migrate: true`，改动 `V2__seed_data.sql`
   会导致下次启动 checksum 校验失败、应用起不来。
   追加数据一律新开版本号（`V4__xxx.sql`、`V5__xxx.sql`…）。
   `gen_seed.py` 目前只对应 V2，**若要再生成请先确认 V2 尚未在目标库执行**。
2. Entity / Mapper 是**生成物**，不要手工改。
   要改字段就先改对应 `V*.sql`，再用 `gen_entity.py --tables <表名>` 定向重生成，
   这样实体与表结构永远零偏差（M1 验收项）。脚本只覆盖指定表，**不删除其它实体或自定义 Mapper**。
3. `V*.sql` 改了就要同步重跑 `gen_db_docs.py`，否则 `docs/db/` 会与代码漂移。

## 用法

### 1. 改表结构后同步实体

```bash
# 只重生成本次改过 DDL 的表；例如 M13 聊天两表
python gen_entity.py --tables chat_message chat_read_cursor
```

### 2. 重新生成种子数据（⚠️ 见上方约束 1）

```bash
python gen_seed.py
```

### 3. 生成数据库文档

```bash
# 步骤 1：按当前 DDL 生成取行数的 SQL，输出到系统临时目录
python gen_db_docs.py counts

# 步骤 2：执行它，把真实行数导出为 TSV（文件名须为 nianglin_counts.tsv）
mysql --host=127.0.0.1 --port=3306 --user=root --password \
      --batch --skip-column-names --default-character-set=utf8mb4 \
      --database=nianglin < "$TEMP/nianglin_counts.sql" > "$TEMP/nianglin_counts.tsv"

# 步骤 3：渲染文档
python gen_db_docs.py render
```

> `mysql.exe` 请用**长参数形式**（`--host=...` 而不是 `-h...`）。
> 在 PowerShell 下短参数会被拆断，例如 `-h127.0.0.1` 会被解析成主机名 `127`。

### 4. 数据校验

```bash
mysql --host=127.0.0.1 --port=3306 --user=root --password \
      --database=nianglin --table < verify_data.sql

mysql --host=127.0.0.1 --port=3306 --user=root --password \
      --database=nianglin --table < explain_index.sql
```

校验通过标准：

- 行数与 `docs/db/04-种子数据说明.md` 一致；
- 每个 `EXPLAIN` 的 `type` 不为 `ALL`，且 `Extra` 中不含 `Using filesort`；
- 中文编码正常（`SELECT HEX(LEFT(name,1))` 对「张」应为 `E5BCA0`）；
- `sys_user.password` 全部以 `$2a$` 开头。

### 5. 重新产出密钥材料（仅在需要换密码 / 换加密密钥时）

```bash
java GenSeedSecrets.java
```

### 6. M2 认证端到端实测

```bash
# 前提：后端已在 8080 启动（mvn spring-boot:run），MySQL 与 Redis 可用
python e2e_auth.py
```

覆盖：验证码防重放、`1001~1007` 全部错误码、4 角色 × 3 类接口越权、
老人只读拦截、登出黑名单即时生效、改密后旧令牌失效、连续失败锁定、
密码 BCrypt 落库、登录日志不含密码串。共 40 项断言；清理在 `__main__` 的
`finally` 里统一执行（main 开头记基线、退出前删自己造的行与 Redis 键），
断言失败 / 中途异常同样会清理，不会污染下一轮。

> ⚠️ 脚本会写 `sys_user` / `sys_login_log` 并读 Redis，**只在开发库跑**。

### 7. JMeter 压测（M4 / M5 / M6 / M10）

```bash
# 前提：后端已在 8080 启动，MySQL 与 Redis 可用，JMeter 5.6.x 可用
# M4 抢单：先造数据（30 个临时陪诊员 + 1 个 PENDING 单 + 50 个 token）
python bench_setup.py
jmeter -n -t jmeter_m4_accept.jmx -JorderId=<上一步输出的 orderId> -l m4.jtl
python bench_teardown.py

# M5 重连补齐 / M6 并发确认：先备夹具（快照任务 20002 + 拿 fam001 的 token）
python jmeter_fixture.py setup
jmeter -n -t jmeter_m5_reconnect.jmx    -Jtoken=<jmeter_token.txt 内容> -JorderId=1001 -l m5.jtl
jmeter -n -t jmeter_m6_concurrent_confirm.jmx -Jtoken=<同上> -JtaskId=20002 -JelderId=401 -l m6.jtl
python jmeter_fixture.py verify     # 看「是否恰好确认一次」
python jmeter_fixture.py restore    # 把任务还原回快照，别在种子数据上留痕
```

判读口径：

- **M5**：只看拥堵程度（吞吐、P95）与 `Err`。断言已锁 `HTTP 200` **且** `code == 200`，
  所以「重连风暴里有人拿到 401/3004」会直接计为失败样本，不会被 HTTP 200 掩盖。
- **M6**：断言写成 `code ∈ {200, 5003, 409}` —— 换句话说，
  **`Err > 0` 就等于「出现了 500」**，这是并发写入被击穿的信号（正常只会有 1 个 200）。
  「到底成功了几个」不能靠 jtl 数（见坑 11），要 `python jmeter_fixture.py verify` 回库看。
- 实测数据见 `docs/reports/pressure-test-m5-m6.md`。

### 8. M3–M10 端到端实测

```bash
# 与 e2e_auth.py 同一套路：真实 HTTP 打 8080，清理统一走「基线 + finally」模式
# （见 cleanup_util.py）：main 开头记各表 max_id 基线，__main__ 的 finally 里
# 调 cleanup() 删掉 id>基线 的行与 message:unread:* 缓存 —— 失败中断也不留残留。
python e2e_user_profile.py    # M3 档案与老人只读矩阵
python e2e_order.py           # M4 状态机全流转 + 越权
python e2e_execution.py       # M5 打卡 / 距离 / 节点去重
python e2e_medication.py      # M6 计划 / 任务 / 漏服扫描（特殊跑法见 docs/agents/PLAN_BACKEND.md）
python e2e_review.py          # M7 评价与投诉
python e2e_message.py         # M8 站内信与 SSE
python e2e_admin.py           # M9 审核 / 封禁 / 纠纷
python e2e_statistics.py      # M10 统计与导出（含导出上限的特殊跑法）

# V4 P1-2：订单 → 用药计划草稿（需要后端显式 dev profile，否则 application.yml 无默认 profile 会静默退出）
$env:SPRING_PROFILES_ACTIVE="dev"
python e2e_order_medication_draft.py

# V4 P1-3：通知偏好 + 漏服站外短信（mock 通道，不真实外发；靠 sms_send_log 回库核对）
# 漏服扫描默认 5 分钟首跑 + 30 分钟节奏，e2e 用运行期覆盖加速（不改生产代码默认值）：
$env:NIANGLIN_MEDICATION_MISSED_SCAN_INITIAL_DELAY_MS="5000"
$env:NIANGLIN_MEDICATION_MISSED_SCAN_INTERVAL_MS="10000"
# 日频控上限不再用环境变量：P2-3 起 sys_dict 的 sms.frequency.missed-dose-daily-cap
# 优先于 NIANGLIN_SMS_FREQUENCY_MISSEDDOSEDAILYCAP，脚本运行时读运营参数按剩余额度断言
python e2e_sms_mock.py

# V4 P2-1：结算闭环（会临时核定一笔 COMPLETED+UNPAID 种子订单，退出前还原并删本轮日志）
python e2e_settlement.py
```

### 9. 全量 e2e 回归（跑法与依赖顺序）

全量回归必须**串行**跑，且按下面的阶段划分 —— 阶段之间要重启后端或改运营参数，
顺序错了会出现「对空后端起跑」「扫描节奏没生效」「导出上限没生效」这类假红。

**阶段 0 · 服务与基线**

```powershell
$env:MYSQL_PASSWORD = "<本机 dev 口令>"
pwsh -File tools\e2e\start-services.ps1          # 显式 dev；以 /api/health 为就绪判据
python tools\e2e\l0_identity_gate.py             # 身份闸门，10/10 才继续
# 跑前基线：关键表 count / max(id) 存一份（工具自选，如 reports/e2e_baseline.py before）
```

**阶段 1 · 普通后端（不重启）**

顺序固定为「认证 → 档案 → 订单 → 履约 → 评价 → 站内信 → 管理 → 结算 → 草稿 → 统计」：

```powershell
python e2e_auth.py
python e2e_user_profile.py
python e2e_order.py
python e2e_execution.py
python e2e_review.py
python e2e_message.py
python e2e_admin.py
python e2e_settlement.py
python e2e_order_medication_draft.py
python e2e_statistics.py
```

订单类脚本会造订单，后续履约 / 评价 / 站内信 / 结算依赖这些订单形态，所以不能倒序；
`e2e_statistics` 放在最后，避免其它脚本的临时订单被统计口径算进去。

**阶段 2 · 加速漏服扫描（需重启后端一次）**

```powershell
pwsh -File tools\e2e\stop-services.ps1
$env:SPRING_PROFILES_ACTIVE="dev"
$env:NIANGLIN_MEDICATION_MISSED_SCAN_INITIAL_DELAY_MS="5000"
$env:NIANGLIN_MEDICATION_MISSED_SCAN_INTERVAL_MS="10000"
# 重新 start-services 后跑：
python e2e_medication.py                 # 阶段一：埋探针，故意不清理
python e2e_medication.py --scan-phase    # 阶段二：断言新进程首次扫描并统一清理
python e2e_sms_mock.py                   # 复用同一套扫描节奏与频控运营参数
```

两阶段之间**必须重启后端**：`e2e_medication` 验证的就是「新进程首次扫描」，
不重启的话阶段二会等不到扫描结果。

**阶段 3 · 导出行数上限（需临时改运营参数 + 重启一次）**

```powershell
# 后台不能直接 --nianglin.export.max-rows=2：P2-3 起 sys_dict 的 export.max-rows
# 优先于启动参数，且允许区间是 [100, 50000]；种子数据只有 65 单 / 93 用户，
# 凑不到 100 行。先把该字典行停用（status=0），启动参数才有机会生效。
mysql ... -e "UPDATE sys_dict SET status=0 WHERE dict_type='OPS_PARAM' AND dict_code='export.max-rows';"
# 停掉后端，用 mvn spring-boot:run -Dspring-boot.run.arguments=--nianglin.export.max-rows=2 启动
python e2e_statistics.py --limit-phase
# 跑完恢复：
mysql ... -e "UPDATE sys_dict SET status=1 WHERE dict_type='OPS_PARAM' AND dict_code='export.max-rows';"
```

**阶段 4 · 跑后基线与收尾**

- 再取一次关键表 count / max(id)，与阶段 0 对比，**diff 为 0** 才算各脚本清理有效；
- 非 0 的差异先分清是「脚本漏清」还是「有业务写入未按基线删」，不要直接手删了事；
- `pwsh -File tools\e2e\stop-services.ps1` 停后端；前端由 Playwright 自己回收。

**超时口径**：单脚本建议 240–300s 硬超时（`e2e_sms_mock` 在扫描节奏生效时约 35s、
最长不超过 60s；曾出现 210s 是频控被历史残留顶满后的重复等待）。卡住即杀掉并记录，
不要无限等待；同一脚本连续失败两次就先查环境，不要盲目重跑。

**运营参数优先（P2-3 起）**：`sms.frequency.missed-dose-daily-cap`、
`export.max-rows`、`medication.missed-threshold-minutes`、`fulfillment.urge-cooldown-minutes`
在 `sys_dict` 有启用行时**一律以字典为准**，启动环境变量只在字典行停用时兜底。
回归前先确认这四行的值与脚本断言前提一致。

## 环境要求

- Python 3.11+（脚本只用标准库，无第三方依赖）
- JDK 21（仅 `GenSeedSecrets.java` 需要，用单文件源码模式直接运行）

## 踩过的坑（照抄结论即可，别重踩）

| # | 现象 | 原因 / 结论 |
|---|---|---|
| 1 | `mysql -h127.0.0.1` 报 `ERROR 2005 Unknown MySQL server host '127'` | PowerShell 会把 `-h127.0.0.1` 拆断，**一律用长参数** `--host=127.0.0.1` |
| 2 | `mvn -q ...` 没有任何输出，分不清成功还是失败 | PowerShell 会吞掉子进程 stdout。**输出重定向到文件再看**；`-q` 模式下空日志就是成功 |
| 3 | 解析 DDL 时主键丢失，`PRIMARY` 索引抓不到 | `PRIMARY KEY (\`id\`)` 没有索引名，正则要用 `(?:`(\w+)`\s*)?\(...\)` 让名字可选 |
| 4 | 加权平均分算出来分母多 1 | MySQL 同一条 `UPDATE` 中，**后面的赋值会读到前面已更新的值**。`score` 必须写在 `review_count` 之前 |
| 5 | 担心 `tools/*.sql` 被 Flyway 当成迁移脚本 | 已实测：Maven `<includes>*.sql</includes>` **不递归子目录**，`tools/` 下的 SQL 不会被复制进 `db/migration` |
| 6 | `Remove-Item` 抛 `SAFE_DELETE_BULK_CONFIRM_REQUIRED` 且后续命令不执行 | 单轮删除超过 50 个文件会触发确认并**中止整条链式命令**。批量清理放到命令最后，或分轮做 |
| 7 | 改了 `V2` 之后应用启动报 Flyway checksum 错 | `validate-on-migrate: true` 会校验已执行脚本。**改了就要 `flyway repair`，不如直接新开 `V4`** |
| 8 | `verify(sysUserMapper, never()).insert(any())` 编译报「对 insert 的引用不明确」 | MyBatis-Plus `BaseMapper` 同时有 `insert(T)` 与 `insert(Collection<T>)`，`any()` 无类型。写 `any(SysUser.class)` |
| 9 | 软删账号用**用户名**仍能登录 | `.eq(A).or().eq(B)` 时 MP 把 `AND deleted = 0` 追加在末尾，被解析成 `A OR (B AND deleted=0)`。必须 `.and(w -> w.eq(A).or().eq(B))` 显式分组 |
| 10 | PowerShell `*>` 重定向后中文乱码 | 编码在子进程与重定向之间不一致。要看中文就 `PYTHONIOENCODING=utf-8` 且别用 `*>`（直接输出即可），或只看 PASS/FAIL 与数值 |
| 11 | 想在 jtl 里 `grep '"code":200'` 数「成功了几条」，结果永远是 0 | `jmeter -l x.jtl` 生成的 jtl 由全局 `jmeter.save.saveservice.*` 决定列，**计划里监听器的 saveConfig 不生效**；实测（5.6.3）**连计划内监听器按自己 `filename` 写出的那个文件也不含响应体列**，且 `-Jjmeter.save.saveservice.response_data=true` 同样无效。业务 code 要靠回库核对或改用断言表达 |
| 12 | `SELECT remark FROM medication_task` 报 `Unknown column` | 该表的备注列叫 **`confirm_remark`**（药名 / 剂量另有 `dosage`）。写 SQL 前先 `SHOW COLUMNS`，别按实体字段名猜 |
| 13 | 跑完 JMeter，仓库根目录多出 `jmeter_m5_results.jtl` / `jmeter_m6_aggregate.csv` | 计划里监听器的 `filename` 是**相对启动时 cwd** 的；用 `-l` 只是另加一个收集器，不会关掉计划内的监听器。要么在计划里写绝对路径，要么把这些产物加进 `.gitignore`（已加） |
| 14 | e2e 跑一轮后 `internal_message` 多出几百行，越跑越脏（BUG_LIST L1） | 造单 / 接单会触发 ORDER_CREATED 等站内信，而旧清理**不碰** `internal_message` 且不在 `finally` 里（断言失败就跳过清理）。统一改为：`main()` 开头 `cleanup_util.max_id(...)` 记基线，`__main__` 的 `finally` 里 `cleanup()` 按基线删行 + `flush_unread_cache()` |
| 15 | cleanup() 一跑就把整张种子表删光 | `DELETE ... WHERE id > 0` 等价于清空。`cleanup_util.delete_since` 在**基线 ≤ 0 时拒绝执行**——宁可漏清一轮测试垃圾，也不许在基线缺失时动手 |
| 16 | e2e_medication 阶段二报 `NameError: b5_order_id` | cleanup() 引用的变量只在 main() 局部赋值，阶段二（`--scan-phase` 新进程）根本读不到。**cleanup() 要读的每个变量都必须是模块级全局**（applicant_id / new_elder_user_id / b5_order_id 同理） |
| 17 | 本地起后端「静默退出」，退出码 1 但几乎看不到错误日志 | `application.yml` 刻意**不给** `spring.profiles.active` 默认值（`${SPRING_PROFILES_ACTIVE:}`，防止生产漏配时兜底成 dev）。当前环境没设该变量 → profile 为空 → `application-dev.yml` 不加载（数据源缺失启动失败），且 `logback-spring.xml` 的 `<springProfile>` 块不生效、错误日志被完全吞掉。**本地启动 / 跑 e2e 前先 `$env:SPRING_PROFILES_ACTIVE="dev"`** |
| 18 | 本地 e2e 看到 `sms_send_log` 有 `SENT` 行，就以为真的发出去了；或生产切 aliyun 后启动直接失败，报缺模板 | **mock 通道不会真实外发**，`SENT` 只代表「编排层认为发送成功」，手机不会收到短信；生产 `provider=aliyun` 时 `verification` / `missed-dose` 两个模板 Code **都必须非空**（`ALIYUN_SMS_TEMPLATE_CODE_VERIFICATION` / `ALIYUN_SMS_TEMPLATE_CODE_MISSED_DOSE`），缺任一即启动失败，这是刻意 fail-fast 而不是 bug；静默时段默认 `22:00–08:00` **跨零点**，判定用 `now >= start || now < end`（`08:00` 整点视为非静默），`next_retry_at` 要按「当前时刻早于 end → 当天 08:00，否则次日 08:00」计算，别写成 `now + 8h` |
| 19 | 用 `NIANGLIN_SMS_FREQUENCY_MISSEDDOSEDAILYCAP=2` 起后端，e2e 却仍收到 3 条；`--nianglin.export.max-rows=2` 也不生效 | P2-3 起运营参数（`sys_dict` 的 `sms.frequency.missed-dose-daily-cap` / `export.max-rows` 等）**优先于**启动参数与环境变量；环境变量只在对应字典行 `status=0` 时兜底。改断言前先读字典当前值，别假设启动参数生效。排查口径见根 `README.md` 部署段与 `docs/api/08-admin.md` §28.4 |
| 20 | 订单删了，跑后基线仍多出 `order_fulfillment_event` / `order_fee_item`；家属短信频控无故满额 | 新增表后各脚本的删除清单没跟上（V3.1 履约事件、V4 费用明细、漏服扫描写出的 `sms_send_log`）。订单类脚本删单时要连带删这两张表；用药脚本删任务前要先按 `plan_id` 反查并删 `sms_send_log`（静默时段是 `PENDING` 行，同样占频控额度） |
| 21 | 删了用药任务，家属当天仍收不到漏服短信 | 旧版频控按行数统计，指向已删除任务的孤儿回执会继续占额度。**删除 / 停用任务会归还短信额度**（回执置 `VOIDED`，且频控只计仍存在的任务），这是刻意行为不是漏发。存量孤儿数据执行 `backend/sql/tools/cleanup_orphan_sms_logs.sql` |

## 生成物与手工物的边界

| 路径 | 性质 | 能不能手改 |
|---|---|---|
| `backend/sql/V*.sql` | **人工维护的真源** | ✅ 但已执行过的不能再改 |
| `backend/src/main/java/**/entity/` `mapper/` | 生成物 | ❌ 改 `V1` 后重跑 `gen_entity.py` |
| `docs/db/*.md` | 生成物 | ❌ 重跑 `gen_db_docs.py render` |
| `backend/sql/tools/*` | 人工维护 | ✅ |
| `backend/target/**` | 构建产物 | ❌