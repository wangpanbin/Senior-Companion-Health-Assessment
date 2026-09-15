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

## 生成物与手工物的边界

| 路径 | 性质 | 能不能手改 |
|---|---|---|
| `backend/sql/V*.sql` | **人工维护的真源** | ✅ 但已执行过的不能再改 |
| `backend/src/main/java/**/entity/` `mapper/` | 生成物 | ❌ 改 `V1` 后重跑 `gen_entity.py` |
| `docs/db/*.md` | 生成物 | ❌ 重跑 `gen_db_docs.py render` |
| `backend/sql/tools/*` | 人工维护 | ✅ |
| `backend/target/**` | 构建产物 | ❌
