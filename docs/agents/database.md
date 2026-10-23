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
