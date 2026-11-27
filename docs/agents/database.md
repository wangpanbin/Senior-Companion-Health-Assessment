# 5 · 数据库 / Flyway / 数据初始化（M1）

> 本文件是 `AGENTS.md` **§5** 的落地位置。
> 拆分原因：运行时给仓库根 `AGENTS.md` 的上下文预算是 **32 KB**，
> 拆分前它有 **54 KB** —— 超出预算的部分**每次会话都被静默丢弃**，
> 丢掉的恰好是订单状态机、Do/Don't 底线、验收清单这些最该被看见的内容。
> 拆开后 `AGENTS.md` 只留红线与导航，细则**按需加载**。
>
> ⚠️ 硬约束仍在 `AGENTS.md`（始终在上下文里）：§0 合规红线、§0.2 AI 自我约束、
> §10 Do/Don't 最低底线、§11 验收检查清单。本文件是细则，不覆盖那些硬约束。
>
> 小节编号（§5.1 …）**未改动**，仓库其它文档里的 `AGENTS.md §5.N` 引用仍然对得上。

---

- 用 Flyway 做版本化迁移；脚本放 **`backend/sql/`**（人工维护的唯一真源），
  命名 `V1__init_schema.sql` / `V2__seed_data.sql` / `V3__seed_boundary.sql`。
  构建时由 `backend/pom.xml` 的 `<resources>` 复制到 classpath 的 `db/migration/` 供 Flyway 读取，
  **同一份脚本不要写两遍**（`backend/src/main/resources/db/migration/` 下不再放脚本）。
- **已执行过的脚本不得再改**：`application-dev.yml` 中 `validate-on-migrate: true` 会校验 checksum，
  改动已执行脚本会导致下次启动迁移失败。追加数据一律新开版本号（`V4__xxx.sql`…）。
- 启用条件：`application-dev.yml` 中 `flyway.enabled` —— **M1 已置为 `true`**。
- **生成与校验工具在 `backend/sql/tools/`**：Entity/Mapper 生成（`gen_entity.py`）、
  种子生成（`gen_seed.py`）、数据库文档生成（`gen_db_docs.py`）、
  数据校验（`verify_data.sql`）、索引校验（`explain_index.sql`）。
  改完 `V1__init_schema.sql` 必须重跑 `gen_entity.py` 与 `gen_db_docs.py`，否则实体与 `docs/db/` 会和表结构漂移。
- 数据库设计文档见 **`docs/db/`**（5 篇：约定与表清单 / ER 图 / 数据字典 / 索引与 EXPLAIN / 种子数据说明），
  由 `gen_db_docs.py` 生成，禁止手工修改。
- 关键业务表至少：`sys_user` / `elder_profile` / `family_elder_relation` / `companion_profile` / `companion_order`（含 `version` 乐观锁） / `order_checkin` / `companion_track` / `medicine_dict` / `medication_plan` / `medication_task` / `order_review` / `complaint` / `internal_message` / `admin_oper_log`。
- 种子数据每张表 ≥ 30 条，覆盖空字段 / 超长文本 / 非常用药 / 跨天用药等边界场景。
- 关键查询必须走索引：订单按 `status + visit_time`、用药任务按 `elder_id + plan_date + status`、陪诊员按资质筛选；`EXPLAIN` 的 `type` 不能为 `ALL`。
- DDL 必须幂等（`CREATE TABLE IF NOT EXISTS` / `DROP TABLE IF EXISTS` + `CREATE TABLE`），不能因已存在表而中断。

## 5.1 唯一键与软删除：哪些表「不允许软删」（最终复盘 DB-02）

**问题背景**：库里有 5 组「业务键唯一」的 UNIQUE KEY，但都没有把 `deleted` 纳入列组合。
一旦对应行被**逻辑删除**，同键就再也插不进去 —— 而删除接口当时看起来是成功的。

**结论（不是「全都加 `deleted`」）**：加 `deleted` 会让唯一键失去约束力（`deleted=1` 的行可以无限重复），
而本项目的读取路径全靠 `deleted=0` 过滤，重复行会污染「按业务键取一条」的查询。
所以先按表定策略，**不允许软删的表只追加语义，需要软删的表将来单独迁移**。

| 表 | 唯一键 | 软删策略 | 当前是否可达 | 说明 |
|---|---|---|---|---|
| `family_elder_relation` | `uk_family_elder(family_id, elder_id)` | **不允许软删**（解绑只改 `status='UNBOUND'` + `unbind_time`，重绑走 UPDATE 复原） | 不可达 | 别改成「加 `deleted`」：会打断 `upsertBoundRelation` 的复原路径 |
| `order_review` | `uk_order_id(order_id)` | **不允许软删**（作废用 `is_valid=0`） | 不可达 | 一单一评；没有「重评」入口 |
| `medication_task` | `uk_plan_time(plan_id, plan_time)` | **不允许软删** | 不可达（目前无删除接口） | ⚠️ **将来加「纠错删除」会立刻咬人**：要么改唯一键，要么纠错走 UPDATE |
| `order_checkin` | `uk_order_node(order_id, node)` | **不允许软删** | 不可达 | 同上：加打卡纠错功能时必须先想清楚 |
| `sys_user` | `uk_username` / `uk_phone` | **不允许软删**（注销走去标识化 + 改写手机号） | 不可达 | 注销后手机号可复用是刻意设计 |
| `companion_order` | `uk_order_no` | 允许软删但订单号不复用（发号器只增） | — | 订单号一旦发出不再回收 |
| `sys_dict` / `sys_file` / `companion_profile` | `uk_type_code` / `uk_file_id` / `uk_user_id` | 走「停用」或「覆盖登记」，不软删 | — | 都是 1:1 的附属记录 |

**判据（新增唯一键前先答这三问）**：
1. 这张表**会不会**被逻辑删除？会 → 唯一键必须与 `deleted` 一起设计，并在删除方写明「删后同键不可重建」。
2. 同键重建是**业务需求**还是**误操作**？是误操作 → 不要加 `deleted`，让唯一键硬挡。
3. 删除接口返回成功但同键插不进去，**用户看到的是什么**？答不上来就别加唯一键。

## 5.2 CHECK 约束（V14 起，最终复盘 DB-01）

- V1–V13 全库 **0 个 CHECK**。V14 起只对「被代码按枚举分支处理的状态列」与「参与金额计算的金额列」加 CHECK，
  命名 `ck_<表>_<列>`。**不一次性全表加** —— 加错会挡住正常业务演进。
- 当前清单：`sys_user.status`、`elder_profile.bind_status`、`family_elder_relation.status`、
  `companion_order.status / payment_status / fee>=0 / actual_fee>=0(NULL 允许)`、
  `order_fee_item.item_type / amount>=0`、`medication_task.status`、`complaint.status`、`product_feedback.status`。
- **新增枚举列时**：先加常量类，再加 CHECK，最后改文档。三处漏一处就会出现「代码拒绝、库接受」或反之。
- ⚠️ `ALTER TABLE ... CHECK` 会校验存量数据。迁移红了说明库里有越界值，**先修数据再迁**，
  不要用 `NOT ENFORCED` 绕过去（那等于加了个注释）。
