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
| `e2e_auth.py` | **M2 端到端实测**：真实 HTTP 打 8080，40 项断言（错误码 / 越权 / 验证码防重放 / 登出黑名单 / 改密失效 / 锁定），自带测试数据清理 | 控制台 PASS/FAIL 汇总 |
| `e2e_user_profile.py` … `e2e_statistics.py` | M3–M10 各模块端到端实测（共 9 个，命名对应模块） | 控制台 PASS/FAIL 汇总 |
| `bench_setup.py` / `bench_seed_orders.py` / `bench_teardown.py` | JMeter 压测的数据准备 / 造 50 单 / 清理 | `bench_tokens.csv`、`bench_meta.json` |
| `jmeter_m4_accept.jmx` | M4 50 并发抢单（`@Version` 乐观锁防超卖） | `m4_*.jtl/csv` |
| `jmeter_m5_reconnect.jmx` | **M5 重连补齐风暴**：50 客户端并发拉 `/progress` + `/checkins` | `m5.jtl` |
| `jmeter_m6_concurrent_confirm.jmx` | **M6 并发确认服药**（同一任务 50 并发）+ 20 并发读日历 | `m6.jtl` |
| `jmeter_fixture.py` | M5/M6 压测夹具：快照 / 还原种子任务 20002、登录拿 token、核对结论 | `jmeter_fixture_snapshot.json`、`jmeter_token.txt` |
| `jmeter_m10_overview.jmx` | M10 统计总览并发读 | `m10_*.jtl/csv` |

> `e2e_auth.py` 不属于数据库脚本范畴，放在这里是因为它同样需要
> 「连着真实的 MySQL / Redis 才跑得起来」—— 与其它工具的前置条件一致。
> 它读 Redis 只为拿验证码明文（图片识别不是测试该做的事），其余全部走正常接口。

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
   要改字段就先改 `V1__init_schema.sql`，再重跑 `gen_entity.py`，
   这样实体与表结构永远零偏差（M1 验收项）。
3. `V1` 改了就要同步重跑 `gen_db_docs.py`，否则 `docs/db/` 会与代码漂移。

## 用法

### 1. 改表结构后同步实体

```bash
python gen_entity.py
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
密码 BCrypt 落库、登录日志不含密码串。共 40 项断言，跑完自动清理测试账号与 Redis 键。

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
- 实测数据见 `docs/agents/reports/pressure-test-m5-m6.md`。

### 8. M3–M10 端到端实测

```bash
# 与 e2e_auth.py 同一套路：真实 HTTP 打 8080，跑完各自清理
python e2e_user_profile.py    # M3 档案与老人只读矩阵
python e2e_order.py           # M4 状态机全流转 + 越权
python e2e_execution.py       # M5 打卡 / 距离 / 节点去重
python e2e_medication.py      # M6 计划 / 任务 / 漏服扫描（特殊跑法见 docs/agents/PLAN_BACKEND.md）
python e2e_review.py          # M7 评价与投诉
python e2e_message.py         # M8 站内信与 SSE
python e2e_admin.py           # M9 审核 / 封禁 / 纠纷
python e2e_statistics.py      # M10 统计与导出（含导出上限的特殊跑法）
```

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

## 生成物与手工物的边界

| 路径 | 性质 | 能不能手改 |
|---|---|---|
| `backend/sql/V*.sql` | **人工维护的真源** | ✅ 但已执行过的不能再改 |
| `backend/src/main/java/**/entity/` `mapper/` | 生成物 | ❌ 改 `V1` 后重跑 `gen_entity.py` |
| `docs/db/*.md` | 生成物 | ❌ 重跑 `gen_db_docs.py render` |
| `backend/sql/tools/*` | 人工维护 | ✅ |
| `backend/target/**` | 构建产物 | ❌