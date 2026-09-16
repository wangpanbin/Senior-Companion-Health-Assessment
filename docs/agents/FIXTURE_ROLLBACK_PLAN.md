# 写路径 E2E 夹具与回滚策略

> 适用场景：前端 UI 全量巡检（`tools/e2e/run_ui_sweep.py`）在**真实写接口**上跑完之后，
> 如何把数据库与 Redis 恢复到「跑之前一模一样」的干净状态。
>
> 现状证据：`internal_message` 已被污染到 **3180 行**（种子 72 行），
> 即 BUG_LIST 的 **L1（唯一 P1 欠账）**，根因就是写路径 E2E 没有回滚。
>
> 结论先行：**采用「干净基线 + 逐轮轻量夹具（方案 B）」**，不做 mysqldump 全库快照。

---

## 1 · 为什么现有机制不够

`backend/sql/tools/jmeter_fixture.py` 是当前唯一的夹具脚本：

| 项 | 现状 |
|---|---|
| 机制 | 应用层 `SELECT` 存 JSON → `UPDATE` 回写（`:87-94` / `:115-129`）|
| 覆盖 | **1 张表 1 行**（`medication_task.id=20002` 的 7 列）|
| 令牌 | 走 API + Redis 旁路（`:60-83`）|
| 口令 | 硬编码 API 密码 `Nl@123456`（`:39`，非 DB 口令，合规无碍）|

四个硬伤：

1. **覆盖面差两个数量级** —— 8 条写路径实际触达 **14 张表**，它只管 1 张。
2. **不处理自增** —— 不删新增行、不重置 `AUTO_INCREMENT`。
3. **无幂等 / 无 teardown** —— 中途崩溃直接回不到干净态。
4. **完全不管副作用表** —— `order_status_log` / `internal_message` / `order_checkin` /
   `companion_track` / `sys_file` / `admin_oper_log` / `companion_profile` / `sys_user`
   一个都不清。**L1 就是这么来的。**

---

## 2 · 八条写路径 × 触达表矩阵

| # | 写流程 | 入口（Service:方法） | 触达表（读 / 写） |
|---|---|---|---|
| ① | 家属下单 | `OrderServiceImpl.create` | **W** `companion_order`(INSERT, `status=PENDING`,`version=0`, `:168`)、`order_status_log`(`:170`)、`internal_message`(`sendBatch :687`) |
| ② | 陪诊员接单 | `OrderServiceImpl.accept` | **W** `companion_order`(`status=ACCEPTED`,`companion_id`,`version+1`, `:355`)、`order_status_log`(`:361`)、`internal_message`(`:695`) |
| ③ | 陪诊员打卡 | `ExecutionServiceImpl.checkin` | **W** `order_checkin`(`:209`)、`companion_track`(`:218`)、`internal_message`(ORDER_PROGRESS, `:348`)；传照另 **W** `sys_file`(`FileStorageServiceImpl:99`) |
| ④ | 确认服药 | `MedicationServiceImpl.confirm` | **W** `medication_task`(`status=TAKEN`,`confirm_time`,`confirm_by`,`was_missed`, `:409-422`)；`createPlan` 另 **W** `medication_plan` + `medication_task` |
| ⑤ | 家属评价 | `ReviewServiceImpl.create` | **W** `order_review`(`:146`)、`companion_order`(→`REVIEWED`, `:155` / `OrderServiceImpl:548`)、`order_status_log`(`:557`)、`companion_profile`(`score`/`review_count`, `:277`)、**Redis 评分缓存** |
| ⑥ | 家属投诉 | `ComplaintServiceImpl.create` | **W** `complaint`(`:136`)、`internal_message`(COMPLAINT_SUBMITTED, `:239/:248`) |
| ⑦ | 管理员审核资质 | `AdminServiceImpl.decideAudit` | **W** `companion_audit_record`(`:215`)、`companion_profile`(`audit_status`/`work_status`, `:227`)、`sys_user`(**`role`→`COMPANION`**, `:264`)、`internal_message`(`:243`)、`admin_oper_log`(`:245`) |
| ⑧ | 管理员仲裁订单 | `AdminServiceImpl.arbitrate` | **W** `companion_order`(forceTerminal, `:432`)、`order_status_log`(`:607`)、`internal_message`(双方, `:443`)、`admin_oper_log`(`:446`) |

> **副作用表总清单（漏清 = 回滚不干净）**：
> `order_status_log` · `internal_message` · `order_checkin` · `companion_track` ·
> `sys_file` · `admin_oper_log` · `companion_profile` · `sys_user`

---

## 3 · 被写表现状（2026-09-16 实测连库）

| 表 | 当前行数 | 主键 | 与种子对比 |
|---|---|---|---|
| `companion_order` | 64 | BIGINT AUTO_INCREMENT | 吻合 |
| `order_status_log` | 247 | BIGINT AUTO_INCREMENT | 吻合 |
| `order_reject_log` | 36 | BIGINT AUTO_INCREMENT | 吻合 |
| `order_checkin` | 272 | BIGINT AUTO_INCREMENT | 吻合 |
| `companion_track` | 544 | BIGINT AUTO_INCREMENT | 吻合 |
| `medication_plan` | 58 | BIGINT AUTO_INCREMENT | 吻合 |
| `medication_task` | 563 | BIGINT AUTO_INCREMENT | 吻合 |
| `order_review` | 31 | BIGINT AUTO_INCREMENT | 吻合 |
| `complaint` | 32 | BIGINT AUTO_INCREMENT | 吻合 |
| `internal_message` | **3180** | BIGINT AUTO_INCREMENT | **种子 72 → 污染 3108 行** |
| `sys_file` | 40 | BIGINT AUTO_INCREMENT | 吻合 |
| `admin_oper_log` | 37 | BIGINT AUTO_INCREMENT | 吻合 |
| `companion_profile` | 30 | BIGINT AUTO_INCREMENT | 吻合 |
| `companion_audit_record` | 34 | BIGINT AUTO_INCREMENT | 吻合 |
| `sys_user` | 92 | BIGINT AUTO_INCREMENT | 吻合 |
| `elder_profile` | 32 | BIGINT AUTO_INCREMENT | 吻合 |
| `family_elder_relation` | 31 | BIGINT AUTO_INCREMENT | 吻合 |
| `sys_login_log` | 652 | BIGINT AUTO_INCREMENT | **每次登录必增长，属预期** |

**结论**：除 `internal_message` 外全部与 `FRONTEND_CONTRACT.md §10.8` 的种子数吻合 →
**当前只有 `internal_message` 一张表是脏的**，污染 id 区间为 `40073+`（种子是 `40001–40072`）。

全部主键均为 `BIGINT AUTO_INCREMENT` ⇒ **可以统一用「记 max_id → 跑 → 删 id > max_id」清理**。

---

## 4 · 必须保真的种子资产（回滚白名单）

这些行**被写路径改过之后必须原值回写**，不能只删新增行：

| 资产 | id | 约束 |
|---|---|---|
| 订单 | `companion_order` 1001 / 1007 / 1019 / 1031 | 分别是 PENDING / ACCEPTED / COMPLETED / REVIEWED 四态样板；1001–1006 是「待接单」样本池 |
| 老人档案 | `elder_profile` 401（张德海）/ 431 | 401 绑定 fam101 且 `is_default=1`；431 是 UNBOUND 边界 |
| 用药 | `medication_task` 20002 / `medication_plan` 10001 | 20002 当前实测为 **`MISSED`**（漏服扫描已跑过），非计划文档写的 PENDING |
| 账号 | `sys_user` admin=1/2、fam001=101、comp001=301 | comp001 必须保持 `audit_status=APPROVED` + `work_status=AVAILABLE` + `score=4.00` 才能接单 |
| 老人账号 | `elder001–030` = 201–230 | 230 = `DISABLED`（禁用边界样本）|

**最容易被写路径改坏的**：`companion_order` 1001（接单会推进 `version`）、
`companion_profile` 301（评分/审核状态）、`sys_user` 301（角色可能被审核流程改写）。

---

## 5 · 方案选型

| 方案 | 做法 | 评价 |
|---|---|---|
| **A** 全库 `mysqldump` 快照/还原 | 跑前 dump，跑后 source | 最干净、覆盖全部副作用；但慢、体积大、必须停掉并发写；重插带 id 与已消费 id 可能冲突 |
| **B** 逐表轻量夹具 ✅ **选用** | 记 14 表 `MAX(id)` + 快照白名单行的原值 → 跑 → `DELETE id > max` + `UPDATE` 回写 + 清 Redis | 快（秒级）、精准；代价是**必须人工维护表清单**（漏一张就重演 L1）|
| C 事务回滚 | 一个事务包住 | ❌ 不适用：E2E 跨进程多 HTTP 请求，事务边界对不上 |
| D 独立夹具租户 | 另造一整套账号/数据，零污染种子 | ❌ 与既有种子断言（`§10.7/§10.8`）割裂，等于把现有 e2e 脚本全废掉 |

**选 B。** 代价明确：表清单是维护点，所以清单必须由 §2 的矩阵驱动，且加新写接口时同步更新。

---

## 6 · 夹具设计（落地形态）

### 6.1 工具形态

新脚本 `tools/e2e/fixture.py`，三个子命令：

```
python tools/e2e/fixture.py snapshot   # 跑前：写 reports/e2e/fixture/state-<ts>.json
python tools/e2e/fixture.py restore    # 跑后：按 json 还原
python tools/e2e/fixture.py verify     # 校验：行数/关键字段是否回到基线
```

- **DB 口令一律读环境变量 `MYSQL_PASSWORD`**（项目铁律，禁止落盘）。
- 连接方式复用 `probe_api.py` 里已验证的 pymysql 连接参数。
- 状态文件落在 `reports/e2e/fixture/`（该目录已在 `reports/` 下，属可提交产物外的证据区）。

### 6.2 snapshot 阶段

```sql
-- 1) 记水位：14 张被写表的当前最大自增 id
SELECT COALESCE(MAX(id),0) FROM companion_order;
-- ... 对其余 13 张同理

-- 2) 快照白名单行原值（只这几行会被写路径改）
SELECT * FROM companion_order      WHERE id IN (1001,1007,1019,1031);
SELECT * FROM elder_profile        WHERE id IN (401,431);
SELECT * FROM medication_task      WHERE id = 20002;
SELECT * FROM medication_plan      WHERE id = 10001;
SELECT * FROM companion_profile    WHERE id = 301;
SELECT * FROM sys_user             WHERE id IN (1,2,101,201..230,301);
```

序列化成一个 JSON：`{ "watermarks": {...}, "rows": { "companion_order": [...] } }`。

### 6.3 restore 阶段（顺序敏感）

```sql
-- Step 1 删本跑新增的副作用行（子表优先，schema 无 FK 但对齐业务顺序）
DELETE FROM order_status_log   WHERE id > :wm_order_status_log;
DELETE FROM internal_message   WHERE id > :wm_internal_message;
DELETE FROM companion_track    WHERE id > :wm_companion_track;
DELETE FROM order_checkin      WHERE id > :wm_order_checkin;
DELETE FROM sys_file           WHERE id > :wm_sys_file;
DELETE FROM admin_oper_log     WHERE id > :wm_admin_oper_log;
DELETE FROM order_review       WHERE id > :wm_order_review;
DELETE FROM complaint          WHERE id > :wm_complaint;
DELETE FROM medication_task    WHERE id > :wm_medication_task;
DELETE FROM medication_plan    WHERE id > :wm_medication_plan;
DELETE FROM order_reject_log   WHERE id > :wm_order_reject_log;
DELETE FROM companion_order    WHERE id > :wm_companion_order;

-- Step 2 被改种子行原值回写（注意 version 也要回写，否则乐观锁漂移）
UPDATE companion_order SET status=?, companion_id=?, version=?, ... WHERE id=?;
UPDATE medication_task  SET status=?, confirm_time=?, confirm_by=?, confirm_remark=?, was_missed=? WHERE id=20002;
UPDATE companion_profile SET audit_status=?, work_status=?, score=?, review_count=? WHERE id=301;
UPDATE sys_user          SET role=? WHERE id=?;

-- Step 3 自增水位回落到水位值（可选，但能让 id 输出可复现）
ALTER TABLE order_status_log AUTO_INCREMENT = :wm_order_status_log + 1;
```

### 6.4 Redis 侧（易被忽略，必须一起清）

| Key 形态 | 来源 | 处理 |
|---|---|---|
| 评分缓存 | `ReviewServiceImpl:275-280` 写 | 按业务 key 删，或整库 `FLUSHDB` |
| 未读计数 | `internal_message` 推送时更新 | 同上 |
| JWT 版本 / 令牌 | 登录、改密 | 只删与本次账号相关的；**别 FLUSHDB 掉 captcha 以外的一切**会打断并发登录 |
| `captcha:*` | 一次性，TTL 300s | 无需处理，自动过期 |

**建议**：E2E 专用 Redis DB（如 `db=15`）是最彻底的解 —— 跑完直接 `FLUSHDB` 该库。
是否可行取决于 `application-dev.yml` 的 Redis 配置是否支持按 profile 切库。

### 6.5 幂等与崩溃保护

- `snapshot` 前若已存在未 `restore` 的状态文件 → **拒绝覆盖**并提示（防「脏基线再快照」）。
- `restore` 成功后把状态文件重命名为 `.done`，重复执行 `restore` 直接短路。
- `verify` 子命令比对当前行数 vs 快照水位，输出差异表 —— **这是唯一能证明「回滚干净了」的手段**。

---

## 7 · 最危险的三个点

### ① 副作用表漏清 → 重演 L1
**证据**：`internal_message` 实测 3180 行 vs 种子 72 行，差 3108 行。
**缓解**：清表清单**必须**从 §2 矩阵自动推导，不允许手写；新增写接口时同步更新矩阵。
`verify` 子命令必须逐表比对，任一表超水位就报红。

### ② 半写中断 + 乐观锁 `version`
**证据**：接单走 `@Version` 乐观锁并 `version+1`（`OrderServiceImpl:355`）。
崩在中间态时，**只 DELETE 新增行不够** —— 被改的种子行（如 1001）必须连 `version` 一起原值回写，
否则下一次 e2e 会因 version 漂移拿到 409，误报成业务 bug。
**缓解**：白名单行 = **全列快照**，不做字段级选择性回写。

### ③ 统计口径 + `AUTO_INCREMENT`
**证据**：`companion_profile.score`/`review_count` 由 `ReviewServiceImpl:275-280` 与 `order_review` **成对**更新；
`medication_task.was_missed` 直接决定漏服率。
只还原一半 ⇒ 统计接口（`/api/statistics`）数字对不上，且很久以后才会被发现。
`AUTO_INCREMENT` 若不动，mysqldump 式重插会与已消费 id 撞车。
**缓解**：成对字段必须同批还原；`verify` 里加一条「成对性断言」（订单评价数 == `order_review` 行数）。

**利好**：V1 schema **未定义 FOREIGN KEY 约束**（`V1__init_schema.sql:24,614`），
还原不会触发外键报错；但也意味着**没有数据库帮你兜底**，孤儿行只能靠业务键顺序手工防。

---

## 8 · 落地步骤

| 步 | 动作 | 风险 |
|---|---|---|
| 0 | **重建干净基线**：把 `internal_message` 削回 72 行（`DELETE FROM internal_message WHERE id > 40072`），或直接按 Flyway V1–V3 重新初始化库 | 删数据操作，**执行前需确认** |
| 1 | 写 `tools/e2e/fixture.py`（snapshot / restore / verify 三子命令） | 低 |
| 2 | 把 `jmeter_fixture.py` 的 `medication_task 20002` 逻辑合并进来，避免两套夹具 | 低 |
| 3 | 在 `run_ui_sweep.py` 最前面接 `snapshot`、最后面接 `restore` + `verify`，并把 `verify` 结果写进巡检报告 | 低 |
| 4 | 在 `FRONTEND_CONTRACT.md` 增补一节「写路径 E2E 必须先 snapshot」，把「先清 localStorage」同级的硬规则补上 | 低 |

---

## 9 · 明确不做的事

- **不做** mysqldump 全库快照（慢且需停并发写，收益不匹配课程设计规模）。
- **不做**独立夹具租户（会废掉现有全部种子断言型 e2e）。
- **不做**事务级回滚（跨进程 E2E 结构上不支持）。
- **不把** DB 口令写进任何脚本/配置文件 —— 一律 `MYSQL_PASSWORD` 环境变量。
