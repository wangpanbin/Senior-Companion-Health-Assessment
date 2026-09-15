# 银龄伴诊 · 数据库设计文档（M1）

> 本文档由 `backend/sql/V1__init_schema.sql` 自动生成，**与 DDL 同源**，
> 凡改动建表脚本请重新生成本文档，禁止手工修改此处内容造成文档与代码漂移。

## 一、文档清单

| 文档 | 内容 |
|---|---|
| `README.md` | 本文：设计约定、表清单、Flyway 用法 |
| `01-ER图.md` | 分域 ER 图（mermaid）、核心关系说明、订单状态机 |
| `02-数据字典.md` | 20 张表逐字段字典（类型 / 可空 / 默认值 / 说明） |
| `03-索引设计与EXPLAIN.md` | 索引清单、设计理由、EXPLAIN 实测结论 |
| `04-种子数据说明.md` | 数据规模、边界场景覆盖、内置账号、加密脱敏 |

## 二、设计约定（全表强制，验收项）

| # | 约定 | 说明 |
|---|---|---|
| 1 | 主键 | `BIGINT AUTO_INCREMENT`，业务不使用 UUID 主键（页分裂、索引膨胀） |
| 2 | 金额 | `DECIMAL(10,2)`，**禁止 FLOAT / DOUBLE**（浮点误差不可用于记账） |
| 3 | 时间 | `DATETIME`，统一 GMT+8（连接串 `serverTimezone=Asia/Shanghai`） |
| 4 | 经纬度 | `DECIMAL(10,6)`，禁止 FLOAT（约 0.1 米精度，够打卡距离校验） |
| 5 | 审计字段 | 所有表含 `create_time` / `update_time` / `deleted` |
| 6 | 布尔字段 | `TINYINT`，0-否 1-是 |
| 7 | 枚举字段 | `VARCHAR` 存**大写英文枚举名**，禁止存中文（便于 i18n 与前端映射） |
| 8 | 逻辑删除 | 业务表一律 `deleted` 标记，**禁止物理删除**（历史订单仍需可追溯） |
| 9 | 字符集 | `utf8mb4` / `utf8mb4_general_ci`（支持 emoji，老人昵称常见） |
| 10 | 引擎 | `InnoDB`（事务 + 行锁，接单防超卖依赖事务） |

### 命名规范

| 对象 | 规范 | 示例 |
|---|---|---|
| 表名 | 小写下划线，模块前缀 `sys_` / 业务域直名 | `sys_user`、`companion_order` |
| 列名 | 小写下划线 | `visit_time`、`accept_time` |
| 主键索引 | `PRIMARY` | — |
| 唯一索引 | `uk_` + 语义 | `uk_order_no`、`uk_phone` |
| 普通索引 | `idx_` + 列或语义组合 | `idx_status_visit` |

## 三、表清单

共 **20** 张表，总计 **2,294** 行种子数据。

| # | 表名 | 中文名 | 模块 | 字段数 | 索引数 | 种子行数 |
|---|---|---|---|---|---|---|
| 1 | `sys_user` | 用户主表（四类角色共用） | M2/M3 | 16 | 6 | 92 |
| 2 | `sys_login_log` | 登录日志表（只增不改） | M2 | 12 | 4 | 40 |
| 3 | `sys_dict` | 数据字典表 | M0 | 11 | 3 | 45 |
| 4 | `sys_file` | 附件统一登记表 | M0/M3 | 15 | 4 | 36 |
| 5 | `admin_oper_log` | 管理员操作日志表（只增不改不删） | M9 | 17 | 5 | 37 |
| 6 | `elder_profile` | 老人档案表 | M3 | 20 | 6 | 32 |
| 7 | `family_elder_relation` | 家属-老人绑定关系表 | M3 | 12 | 4 | 31 |
| 8 | `companion_audit_record` | 陪诊员资质申请记录表 | M9 | 17 | 4 | 34 |
| 9 | `companion_profile` | 陪诊员业务资料表 | M3/M9 | 25 | 6 | 30 |
| 10 | `companion_order` | 陪诊订单主表 | M4 | 30 | 10 | 64 |
| 11 | `order_status_log` | 订单状态流转日志表（只增不改） | M4 | 13 | 4 | 246 |
| 12 | `order_reject_log` | 陪诊员拒单记录表 | M4 | 8 | 3 | 36 |
| 13 | `order_checkin` | 陪诊打卡记录表 | M5 | 16 | 4 | 272 |
| 14 | `companion_track` | 陪诊轨迹点表 | M5 | 12 | 3 | 544 |
| 15 | `medicine_dict` | 药品字典表 | M6 | 16 | 5 | 61 |
| 16 | `medication_plan` | 用药计划表 | M6 | 16 | 5 | 36 |
| 17 | `medication_task` | 每日服药任务表 | M6 | 19 | 6 | 520 |
| 18 | `order_review` | 订单评价表 | M7 | 16 | 6 | 31 |
| 19 | `complaint` | 投诉表 | M7 | 18 | 6 | 32 |
| 20 | `internal_message` | 站内信表 | M8 | 15 | 5 | 75 |

> 索引数含主键索引。

## 四、Flyway 迁移说明

建表脚本放在 `backend/sql/`，构建时由 `pom.xml` 的 `<resources>` 映射进
classpath 的 `db/migration/`：

```xml
<resource>
    <directory>sql</directory>
    <targetPath>db/migration</targetPath>
</resource>
```

| 版本 | 文件 | 内容 | 幂等策略 |
|---|---|---|---|
| V1 | `V1__init_schema.sql` | 20 张表 DDL | `CREATE TABLE IF NOT EXISTS` |
| V2 | `V2__seed_data.sql` | 全量种子数据（20 表） | 先 `TRUNCATE` 再整体重灌 |
| V3 | `V3__seed_boundary.sql` | 边界场景补充（已取消订单 / 一星差评 / 已解绑关系） | 纯追加，id 段与 V2 不重叠 |

> **已执行的脚本不得再改**：`validate-on-migrate: true` 会校验 checksum，
> 改动 V2 会导致下次启动迁移失败。追加数据一律新开版本号（V4、V5…）。

**操作命令**（在 `backend/` 下执行）：

```bash
# 库首次创建（仅一次）
mysql -uroot -p -e "CREATE DATABASE nianglin DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;"

# 启动应用即自动迁移（flyway.enabled=true）
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080"
```

> 二次启动 Flyway 会输出 `Schema nianglin is up to date. No migration necessary.`，
> 说明脚本幂等，可放心重复执行。

## 五、相关文档与工具

| 位置 | 内容 |
|---|---|
| `backend/sql/tools/` | 本文档的生成脚本、数据校验 SQL、EXPLAIN 脚本（含 README） |
| `docs/api/` | 接口设计文档（9 篇，按模块拆分） |
| `AGENTS.md` | AI 协作与编码规范（项目宪法） |
| `plan.md` | 模块拆分与验收标准 |

本文档**由脚本生成**（`python backend/sql/tools/gen_db_docs.py render`）。
改完 DDL 请重新生成，不要手工编辑本目录下的文件，否则会与代码漂移。
