# -*- coding: utf-8 -*-
"""
生成 M1 数据库设计文档（docs/db/）。

数据来源：
  backend/sql/V1__init_schema.sql   —— 表结构（列、类型、索引、注释）
  系统临时目录/nianglin_counts.tsv  —— 真实库行数（由 mysql CLI 导出）

用法：
  python gen_db_docs.py counts     # 生成 nianglin_counts.sql（供 mysql CLI 执行）
  python gen_db_docs.py render     # 读取 nianglin_counts.tsv，产出 docs/db/*.md

完整流程见同目录 README.md。
"""
import os
import re
import sys
import tempfile

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                     "..", "..", ".."))
DDL = os.path.join(ROOT, "backend", "sql", "V1__init_schema.sql")
# 中间产物（counts.sql / counts.tsv）落到系统临时目录，避免污染仓库
TMP = tempfile.gettempdir()
COUNTS_TSV = os.path.join(TMP, "nianglin_counts.tsv")
OUT_DIR = os.path.join(ROOT, "docs", "db")

BASE_COLS = ["id", "create_time", "update_time", "deleted"]

# ---------------------------------------------------------------- 域划分
DOMAINS = [
    ("一、账号与权限域", ["sys_user", "sys_login_log", "sys_dict", "sys_file", "admin_oper_log"]),
    ("二、老人档案与陪诊员资质域", ["elder_profile", "family_elder_relation",
                                    "companion_audit_record", "companion_profile"]),
    ("三、陪诊订单与执行域（系统核心）", ["companion_order", "order_status_log", "order_reject_log",
                                          "order_checkin", "companion_track"]),
    ("四、用药管理域", ["medicine_dict", "medication_plan", "medication_task"]),
    ("五、评价、投诉与消息域", ["order_review", "complaint", "internal_message"]),
]

MODULE_OF = {
    "sys_user": "M2/M3", "sys_login_log": "M2", "sys_dict": "M0", "sys_file": "M0/M3",
    "admin_oper_log": "M9", "elder_profile": "M3", "family_elder_relation": "M3",
    "companion_audit_record": "M9", "companion_profile": "M3/M9",
    "companion_order": "M4", "order_status_log": "M4", "order_reject_log": "M4",
    "order_checkin": "M5", "companion_track": "M5", "medicine_dict": "M6",
    "medication_plan": "M6", "medication_task": "M6", "order_review": "M7",
    "complaint": "M7", "internal_message": "M8",
}

TABLE_CN = {
    "sys_user": "用户主表（四类角色共用）", "sys_login_log": "登录日志表（只增不改）",
    "sys_dict": "数据字典表", "sys_file": "附件统一登记表", "admin_oper_log": "管理员操作日志表（只增不改不删）",
    "elder_profile": "老人档案表", "family_elder_relation": "家属-老人绑定关系表",
    "companion_audit_record": "陪诊员资质申请记录表", "companion_profile": "陪诊员业务资料表",
    "companion_order": "陪诊订单主表", "order_status_log": "订单状态流转日志表（只增不改）",
    "order_reject_log": "陪诊员拒单记录表", "order_checkin": "陪诊打卡记录表",
    "companion_track": "陪诊轨迹点表", "medicine_dict": "药品字典表",
    "medication_plan": "用药计划表", "medication_task": "每日服药任务表",
    "order_review": "订单评价表", "complaint": "投诉表", "internal_message": "站内信表",
}

# 表间逻辑关系（用于 ER 图与关系说明；不做物理外键，见索引文档）
RELATIONS = [
    ("sys_user", "companion_order", "||--o{", "family_id", "家属下单（一对多）"),
    ("sys_user", "companion_order", "||--o{", "companion_id", "陪诊员接单（一对多，可空）"),
    ("elder_profile", "companion_order", "||--o{", "elder_id", "老人被服务（一对多）"),
    ("sys_user", "elder_profile", "||--o|", "user_id", "老人账号关联档案（一对一，可空）"),
    ("sys_user", "companion_profile", "||--o|", "user_id", "陪诊员账号关联资料（一对一）"),
    ("sys_user", "family_elder_relation", "||--o{", "family_id", "家属绑定关系"),
    ("elder_profile", "family_elder_relation", "||--o{", "elder_id", "老人被绑定"),
    ("companion_order", "order_status_log", "||--o{", "order_id", "状态流转留痕"),
    ("companion_order", "order_checkin", "||--o{", "order_id", "打卡记录"),
    ("companion_order", "companion_track", "||--o{", "order_id", "轨迹点"),
    ("companion_order", "order_review", "||--o|", "order_id", "一单一评"),
    ("companion_order", "complaint", "||--o{", "order_id", "订单投诉"),
    ("elder_profile", "medication_plan", "||--o{", "elder_id", "老人用药计划"),
    ("medicine_dict", "medication_plan", "||--o{", "medicine_id", "药品被引用"),
    ("medication_plan", "medication_task", "||--o{", "plan_id", "计划生成每日任务"),
    ("sys_user", "internal_message", "||--o{", "receiver_id", "接收站内信"),
    ("sys_user", "companion_audit_record", "||--o{", "applicant_user_id", "陪诊员资质申请"),
]

# ---------------------------------------------------------------- 解析
def parse_ddl(text):
    tables = []
    pattern = re.compile(
        r"CREATE TABLE IF NOT EXISTS `(\w+)`\s*\((.*?)\n\)\s*ENGINE=([^\n]*)", re.S)
    for table, body, tail in pattern.findall(text):
        tc = re.search(r"COMMENT='([^']*)'", tail)
        cols, indexes = [], []
        pending = ""
        for raw in body.split("\n"):
            line = raw.strip()
            if not line:
                continue
            if line.startswith("--"):
                pending = line.lstrip("-").strip()
                continue
            if not line.startswith("`"):
                m = re.match(r"(PRIMARY KEY|UNIQUE KEY|KEY)\s+(?:`(\w+)`\s*)?\(([^)]*)\)", line)
                if m:
                    kind = {"PRIMARY KEY": "PRIMARY", "UNIQUE KEY": "UNIQUE", "KEY": "NORMAL"}[m.group(1)]
                    iname = "PRIMARY" if kind == "PRIMARY" else m.group(2)
                    indexes.append({"kind": kind, "name": iname,
                                    "cols": re.findall(r"`(\w+)`", m.group(3)),
                                    "note": pending})
                    pending = ""
                continue
            cm = re.match(r"`(\w+)`\s+([A-Za-z]+(?:\([^)]*\))?)\s*(.*)$", line)
            if not cm:
                continue
            col, sql_type, rest = cm.group(1), cm.group(2), cm.group(3)
            ccm = re.search(r"COMMENT\s+'((?:[^']|'')*)'", rest)
            dm = re.search(r"\bDEFAULT\s+('(?:[^']*)'|[^\s,]+)", rest, re.I)
            cols.append({
                "name": col,
                "type": sql_type,
                "not_null": "NOT NULL" in rest.upper(),
                "default": dm.group(1) if dm else "",
                "comment": ccm.group(1).replace("''", "'") if ccm else "",
                "auto": "AUTO_INCREMENT" in rest.upper(),
            })
            pending = ""
        tables.append({"table": table, "comment": tc.group(1) if tc else "",
                       "cols": cols, "indexes": indexes})
    return tables


def load_counts():
    counts = {}
    if os.path.exists(COUNTS_TSV):
        with open(COUNTS_TSV, "r", encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) == 2 and parts[1].isdigit():
                    counts[parts[0]] = int(parts[1])
    return counts


# ---------------------------------------------------------------- 渲染工具
def key_of(tbl, name):
    for ix in tbl["indexes"]:
        if ix["kind"] == "PRIMARY" and name in ix["cols"]:
            return "PK"
        if ix["kind"] == "UNIQUE" and ix["cols"] == [name]:
            return "UK"
    return ""


def mm_type(sql_type):
    """mermaid erDiagram 的类型不允许括号，转成下划线风格。"""
    t = sql_type.lower()
    t = t.replace("(", "_").replace(")", "").replace(",", "_")
    return re.sub(r"_+", "_", t).strip("_")


# ---------------------------------------------------------------- 各文档
def doc_readme(tables, counts):
    total = sum(counts.get(t["table"], 0) for t in tables)
    L = []
    L.append("# 银龄伴诊 · 数据库设计文档（M1）")
    L.append("")
    L.append("> 本文档由 `backend/sql/V1__init_schema.sql` 自动生成，**与 DDL 同源**，")
    L.append("> 凡改动建表脚本请重新生成本文档，禁止手工修改此处内容造成文档与代码漂移。")
    L.append("")
    L.append("## 一、文档清单")
    L.append("")
    L.append("| 文档 | 内容 |")
    L.append("|---|---|")
    L.append("| `README.md` | 本文：设计约定、表清单、Flyway 用法 |")
    L.append("| `01-ER图.md` | 分域 ER 图（mermaid）、核心关系说明、订单状态机 |")
    L.append("| `02-数据字典.md` | 20 张表逐字段字典（类型 / 可空 / 默认值 / 说明） |")
    L.append("| `03-索引设计与EXPLAIN.md` | 索引清单、设计理由、EXPLAIN 实测结论 |")
    L.append("| `04-种子数据说明.md` | 数据规模、边界场景覆盖、内置账号、加密脱敏 |")
    L.append("")
    L.append("## 二、设计约定（全表强制，验收项）")
    L.append("")
    L.append("| # | 约定 | 说明 |")
    L.append("|---|---|---|")
    L.append("| 1 | 主键 | `BIGINT AUTO_INCREMENT`，业务不使用 UUID 主键（页分裂、索引膨胀） |")
    L.append("| 2 | 金额 | `DECIMAL(10,2)`，**禁止 FLOAT / DOUBLE**（浮点误差不可用于记账） |")
    L.append("| 3 | 时间 | `DATETIME`，统一 GMT+8（连接串 `serverTimezone=Asia/Shanghai`） |")
    L.append("| 4 | 经纬度 | `DECIMAL(10,6)`，禁止 FLOAT（约 0.1 米精度，够打卡距离校验） |")
    L.append("| 5 | 审计字段 | 所有表含 `create_time` / `update_time` / `deleted` |")
    L.append("| 6 | 布尔字段 | `TINYINT`，0-否 1-是 |")
    L.append("| 7 | 枚举字段 | `VARCHAR` 存**大写英文枚举名**，禁止存中文（便于 i18n 与前端映射） |")
    L.append("| 8 | 逻辑删除 | 业务表一律 `deleted` 标记，**禁止物理删除**（历史订单仍需可追溯） |")
    L.append("| 9 | 字符集 | `utf8mb4` / `utf8mb4_general_ci`（支持 emoji，老人昵称常见） |")
    L.append("| 10 | 引擎 | `InnoDB`（事务 + 行锁，接单防超卖依赖事务） |")
    L.append("")
    L.append("### 命名规范")
    L.append("")
    L.append("| 对象 | 规范 | 示例 |")
    L.append("|---|---|---|")
    L.append("| 表名 | 小写下划线，模块前缀 `sys_` / 业务域直名 | `sys_user`、`companion_order` |")
    L.append("| 列名 | 小写下划线 | `visit_time`、`accept_time` |")
    L.append("| 主键索引 | `PRIMARY` | — |")
    L.append("| 唯一索引 | `uk_` + 语义 | `uk_order_no`、`uk_phone` |")
    L.append("| 普通索引 | `idx_` + 列或语义组合 | `idx_status_visit` |")
    L.append("")
    L.append("## 三、表清单")
    L.append("")
    L.append("共 **%d** 张表，总计 **%s** 行种子数据。" % (len(tables), format(total, ",")))
    L.append("")
    L.append("| # | 表名 | 中文名 | 模块 | 字段数 | 索引数 | 种子行数 |")
    L.append("|---|---|---|---|---|---|---|")
    n = 0
    for domain, names in DOMAINS:
        for name in names:
            t = next(x for x in tables if x["table"] == name)
            n += 1
            L.append("| %d | `%s` | %s | %s | %d | %d | %d |" % (
                n, t["table"], TABLE_CN.get(t["table"], t["comment"]), MODULE_OF.get(t["table"], "-"),
                len(t["cols"]), len(t["indexes"]), counts.get(t["table"], 0)))
    L.append("")
    L.append("> 索引数含主键索引。")
    L.append("")
    L.append("## 四、Flyway 迁移说明")
    L.append("")
    L.append("建表脚本放在 `backend/sql/`，构建时由 `pom.xml` 的 `<resources>` 映射进")
    L.append("classpath 的 `db/migration/`：")
    L.append("")
    L.append("```xml")
    L.append("<resource>")
    L.append("    <directory>sql</directory>")
    L.append("    <targetPath>db/migration</targetPath>")
    L.append("</resource>")
    L.append("```")
    L.append("")
    L.append("| 版本 | 文件 | 内容 | 幂等策略 |")
    L.append("|---|---|---|---|")
    L.append("| V1 | `V1__init_schema.sql` | 20 张表 DDL | `CREATE TABLE IF NOT EXISTS` |")
    L.append("| V2 | `V2__seed_data.sql` | 全量种子数据（20 表） | 先 `TRUNCATE` 再整体重灌 |")
    L.append("| V3 | `V3__seed_boundary.sql` | 边界场景补充（已取消订单 / 一星差评 / 已解绑关系） | 纯追加，id 段与 V2 不重叠 |")
    L.append("")
    L.append("> **已执行的脚本不得再改**：`validate-on-migrate: true` 会校验 checksum，")
    L.append("> 改动 V2 会导致下次启动迁移失败。追加数据一律新开版本号（V4、V5…）。")
    L.append("")
    L.append("**操作命令**（在 `backend/` 下执行）：")
    L.append("")
    L.append("```bash")
    L.append("# 库首次创建（仅一次）")
    L.append("mysql -uroot -p -e \"CREATE DATABASE nianglin DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;\"")
    L.append("")
    L.append("# 启动应用即自动迁移（flyway.enabled=true）")
    L.append("mvn spring-boot:run \"-Dspring-boot.run.arguments=--server.port=8080\"")
    L.append("```")
    L.append("")
    L.append("> 二次启动 Flyway 会输出 `Schema nianglin is up to date. No migration necessary.`，")
    L.append("> 说明脚本幂等，可放心重复执行。")
    L.append("")
    L.append("## 五、相关文档与工具")
    L.append("")
    L.append("| 位置 | 内容 |")
    L.append("|---|---|")
    L.append("| `backend/sql/tools/` | 本文档的生成脚本、数据校验 SQL、EXPLAIN 脚本（含 README） |")
    L.append("| `docs/api/` | 接口设计文档（9 篇，按模块拆分） |")
    L.append("| `AGENTS.md` | AI 协作与编码规范（项目宪法） |")
    L.append("| `plan.md` | 模块拆分与验收标准 |")
    L.append("")
    L.append("本文档**由脚本生成**（`python backend/sql/tools/gen_db_docs.py render`）。")
    L.append("改完 DDL 请重新生成，不要手工编辑本目录下的文件，否则会与代码漂移。")
    L.append("")
    return "\n".join(L)


def doc_er(tables, counts):
    L = []
    L.append("# 银龄伴诊 · ER 图与实体关系（M1）")
    L.append("")
    L.append("> mermaid 语法，支持 GitHub / VS Code / Typora 直接渲染。")
    L.append("")
    L.append("## 一、实体关系总览（逻辑模型）")
    L.append("")
    L.append("```mermaid")
    L.append("erDiagram")
    for a, b, card, col, desc in RELATIONS:
        L.append('    %s %s %s : "%s"' % (a, card, b, desc))
    L.append("```")
    L.append("")
    L.append("## 二、关系明细")
    L.append("")
    L.append("| 主表 | 从表 | 外键列 | 基数 | 说明 |")
    L.append("|---|---|---|---|---|")
    for a, b, card, col, desc in RELATIONS:
        c = "1 : 1 或 0" if card == "||--o|" else "1 : N"
        L.append("| `%s` | `%s` | `%s` | %s | %s |" % (a, b, col, c, desc))
    L.append("")
    L.append("> **不建物理外键**。原因：① 课程设计需演示并发接单、批量导入，外键会加剧锁竞争；")
    L.append("> ② 逻辑删除（`deleted=1`）与物理外键的 `ON DELETE` 语义冲突；")
    L.append("> ③ 参照完整性由 Service 层 + 唯一索引共同保证。详见 `03-索引设计与EXPLAIN.md`。")
    L.append("")
    L.append("## 三、分域 ER 图（物理模型，含字段）")
    L.append("")
    idx = 1
    for domain, names in DOMAINS:
        L.append("### 3.%d %s" % (idx, domain))
        L.append("")
        L.append("```mermaid")
        L.append("erDiagram")
        for name in names:
            t = next(x for x in tables if x["table"] == name)
            L.append("    %s {" % t["table"])
            for c in t["cols"]:
                k = key_of(t, c["name"])
                parts = [mm_type(c["type"]), c["name"]]
                if k:
                    parts.append(k)
                L.append("        " + " ".join(parts))
            L.append("    }")
        for a, b, card, col, desc in RELATIONS:
            if a in names and b in names:
                L.append('    %s %s %s : "%s"' % (a, card, b, col))
        L.append("```")
        L.append("")
        idx += 1
    L.append("## 四、订单状态机")
    L.append("")
    L.append("`companion_order.status` 的合法流转路径。**禁止跳级、禁止回退**，")
    L.append("仅管理员可强制改终态（写入 `order_status_log.is_force = 1`）。")
    L.append("")
    L.append("```mermaid")
    L.append("stateDiagram-v2")
    L.append("    [*] --> PENDING : 家属下单")
    L.append("    PENDING --> ACCEPTED : 陪诊员接单")
    L.append("    PENDING --> CANCELLED : 家属取消 / 管理员强制取消")
    L.append("    ACCEPTED --> IN_SERVICE : 陪诊员到院打卡")
    L.append("    ACCEPTED --> CANCELLED : 管理员强制取消（家属不可）")
    L.append("    IN_SERVICE --> COMPLETED : 陪诊员完成并提交小结")
    L.append("    IN_SERVICE --> CANCELLED : 管理员强制取消")
    L.append("    COMPLETED --> REVIEWED : 家属评价")
    L.append("    COMPLETED --> CANCELLED : 管理员强制取消")
    L.append("    REVIEWED --> [*]")
    L.append("    CANCELLED --> [*]")
    L.append("```")
    L.append("")
    L.append("> 取消有两条**互不相同**的路径：家属只能取消 `PENDING`；`ACCEPTED` 之后只能由管理员走纠纷处理强制取消。")
    L.append("> `REVIEWED` / `CANCELLED` 是终态，**不接收任何变更**——含管理员强制（`OrderStatus.isTerminal()`）。")
    L.append("")
    L.append("| 状态 | 枚举值 | 可执行操作 | 写入的日志 |")
    L.append("|---|---|---|---|")
    L.append("| 待接单 | `PENDING` | 陪诊员接单 / 家属取消 / 陪诊员拒单（不改状态） | `order_status_log` + `order_reject_log` |")
    L.append("| 已接单 | `ACCEPTED` | 陪诊员到院打卡 / 管理员强制取消 | `order_status_log` + `order_checkin` |")
    L.append("| 服务中 | `IN_SERVICE` | 节点打卡 / 上传轨迹 / 提交小结完成 / 管理员强制取消 | `order_status_log` + `order_checkin` + `companion_track` |")
    L.append("| 已完成 | `COMPLETED` | 家属评价 / 管理员强制取消 / 管理员核定结算 | `order_status_log` + `companion_profile` 聚合 |")
    L.append("| 已评价 | `REVIEWED` | 终态 | `order_review` |")
    L.append("| 已取消 | `CANCELLED` | 终态 | `order_status_log`（`is_force` 标记强制） |")
    L.append("")
    L.append("> 并发接单防超卖：`companion_order.version` 配 MyBatis-Plus `@Version` 乐观锁，")
    L.append("> 两名陪诊员同时接单只有一人 `UPDATE ... WHERE version = ?` 影响行数为 1。")
    L.append("")
    return "\n".join(L)


def doc_dict(tables, counts):
    L = []
    L.append("# 银龄伴诊 · 数据字典（M1）")
    L.append("")
    L.append("> 逐表逐字段说明。**可空**列标注 `Y`/`N`，`—` 表示无默认值。")
    L.append("")
    L.append("全域图例：`PK` 主键 · `UK` 唯一 · `I` 已建索引")
    L.append("")
    n = 0
    for domain, names in DOMAINS:
        L.append("## %s" % domain)
        L.append("")
        for name in names:
            t = next(x for x in tables if x["table"] == name)
            n += 1
            L.append("### %d. `%s` — %s" % (n, t["table"], TABLE_CN.get(t["table"], t["comment"])))
            L.append("")
            L.append("- 模块：`%s`　种子数据：**%d** 行" % (MODULE_OF.get(t["table"], "-"), counts.get(t["table"], 0)))
            L.append("")
            L.append("| 字段 | 类型 | 可空 | 默认值 | 说明 |")
            L.append("|---|---|---|---|---|")
            for c in t["cols"]:
                k = key_of(t, c["name"])
                mark = (" `%s`" % k) if k else ""
                d = c["default"] if c["default"] else "—"
                if c["auto"]:
                    d = "自增"
                cm = c["comment"].replace("|", "\\|") if c["comment"] else ""
                L.append("| `%s`%s | `%s` | %s | %s | %s |" % (
                    c["name"], mark, c["type"], "N" if c["not_null"] else "Y", d, cm))
            L.append("")
            L.append("**索引**")
            L.append("")
            L.append("| 类型 | 名称 | 列 | 说明 |")
            L.append("|---|---|---|---|")
            for ix in t["indexes"]:
                if ix["kind"] == "PRIMARY":
                    kind, cols = "主键", ", ".join("`%s`" % x for x in ix["cols"])
                    note = "自增主键"
                else:
                    kind = "唯一" if ix["kind"] == "UNIQUE" else "普通"
                    cols = ", ".join("`%s`" % x for x in ix["cols"])
                    note = ix["note"].replace("|", "\\|") if ix["note"] else ""
                L.append("| %s | `%s` | %s | %s |" % (kind, ix["name"], cols, note))
            L.append("")
    return "\n".join(L)


def doc_index(tables, counts):
    L = []
    L.append("# 银龄伴诊 · 索引设计与 EXPLAIN 实测（M1）")
    L.append("")
    L.append("## 一、索引清单")
    L.append("")
    L.append("| 表 | 索引名 | 类型 | 列 | 设计目的 |")
    L.append("|---|---|---|---|---|")
    purpose = {
        "uk_username": "登录按用户名查询，且防重名",
        "uk_phone": "手机号登录/找回，且防一号多账号",
        "idx_role_status": "后台按角色 + 状态筛选用户列表",
        "idx_nickname": "后台按昵称模糊前缀检索",
        "idx_user_time": "按用户查登录历史（安全审计）",
        "idx_status_time": "按结果查失败登录（风控）",
        "uk_type_code": "同一字典类型下编码唯一",
        "idx_type_sort": "下拉项按类型取出并有序返回",
        "uk_file_id": "上传接口按 fileId 反查",
        "idx_biz": "按业务类型 + 业务 ID 批量取附件",
        "idx_uploader": "统计某人上传文件",
        "idx_operator_time": "按管理员查操作记录",
        "idx_oper_type_time": "按操作类型统计（M10）",
        "idx_target": "按目标对象追溯全部处置记录",
        "idx_oper_time": "按时间倒序分页",
        "idx_user_id": "由老人账号反查档案",
        "idx_bind_status": "筛选未绑定档案（家属认领）",
        "idx_create_by": "按建档家属查其代建档案",
        "idx_name": "档案按姓名检索",
        "idx_phone": "按电话匹配档案",
        "uk_family_elder": "同一家属与同一老人只有一条关系",
        "idx_elder_status": "查某老人的有效绑定人",
        "idx_family_status": "查某家属绑定的全部老人",
        "idx_applicant_status": "申请人查自己的申请进度",
        "idx_status_submit": "后台待审核队列（按提交时间）",
        "idx_real_name": "按姓名检索申请",
        "uk_user_id": "一账号一份陪诊员资料",
        "idx_audit_work": "筛选「已通过且在岗」的可接单陪诊员",
        "idx_service_area": "按服务区域匹配陪诊员",
        "idx_score": "按评分排序（选人页）",
        "idx_order_count": "按接单量排序（榜单）",
        "uk_order_no": "订单号唯一 + 按订单号查询",
        "idx_status_create": "按状态 + 下单时间分页",
        "idx_status_visit": "订单大厅：status='PENDING' AND visit_time>NOW() ORDER BY visit_time",
        "idx_family_create": "家属「我的订单」按时间倒序",
        "idx_companion_status": "陪诊员「我的接单」按状态筛选",
        "idx_elder_id": "按老人查历史订单",
        "idx_visit_time": "按就诊时间范围查询",
        "idx_payment_status": "结算状态筛选（线下对账）",
        "idx_create_time": "时间倒序分页 / 按日统计",
        "idx_order_time": "订单时间线按时间正序",
        "idx_to_status": "统计各状态到达量（漏斗）",
        "idx_operator": "按操作人追溯",
        "uk_order_companion": "同一陪诊员对同一单只拒一次",
        "idx_companion_id": "查某陪诊员的全部相关记录",
        "uk_order_node": "同一订单同一节点只允许打卡一次（幂等）",
        "idx_elder_status2": "",
        "idx_name2": "",
        "idx_start_end": "按日期区间筛有效计划",
        "idx_created_by": "按创建家属查计划",
        "uk_plan_time": "定时任务重复触发不产生重复行（幂等）",
        "idx_elder_date_status": "老人某日服药任务（今日用药卡片）",
        "idx_status_plan_time": "漏服扫描：status='PENDING' AND plan_time<NOW()",
        "idx_plan_id": "按计划查任务",
        "idx_plan_date": "按日聚合用",
        "uk_order_id": "一单一评，防重复评价",
        "idx_companion_valid": "评分聚合：按陪诊员 + 是否有效",
        "idx_family_id": "家属查看自己发出的评价",
        "idx_score2": "",
        "idx_complainant": "查某用户发起的投诉",
        "idx_target_user": "查某用户被投诉记录（违规累计）",
        "idx_type2": "",
        "idx_receiver_read": "未读消息数 + 消息列表（覆盖索引）",
        "idx_receiver_type": "按类型筛选消息",
        "idx_biz": "",
    }
    for domain, names in DOMAINS:
        for name in names:
            t = next(x for x in tables if x["table"] == name)
            for ix in t["indexes"]:
                if ix["kind"] == "PRIMARY":
                    continue
                kind = "唯一" if ix["kind"] == "UNIQUE" else "普通"
                cols = ", ".join("`%s`" % x for x in ix["cols"])
                why = purpose.get(ix["name"], ix["note"] or "支撑业务查询条件")
                if not why:
                    why = ix["note"] or "支撑业务查询条件"
                L.append("| `%s` | `%s` | %s | %s | %s |" % (t["table"], ix["name"], kind, cols, why))
    L.append("")
    L.append("## 二、关键设计决策")
    L.append("")
    L.append("### 1. 订单大厅为什么单独加 `idx_status_visit`")
    L.append("")
    L.append("大厅查询是 `WHERE status='PENDING' AND visit_time > NOW() ORDER BY visit_time LIMIT 20`。")
    L.append("仅有 `idx_status_create(status, create_time)` 时，MySQL 会先用它过滤出所有")
    L.append("`PENDING` 订单，再回表排序 `visit_time`，EXPLAIN 出现 **Using filesort**。")
    L.append("补上 `(status, visit_time)` 复合索引后，排序键与索引顺序一致，")
    L.append("`type=range` 且 **filesort 消失**。")
    L.append("")
    L.append("### 2. 复合索引的最左前缀")
    L.append("")
    L.append("`idx_elder_date_status(elder_id, plan_date, status)` 的顺序不是随便定的：")
    L.append("查询条件是「某老人 + 某天 + 某状态」，等值列按**区分度**排列，")
    L.append("`elder_id`（基数高）在前、`status`（基数仅 3）在后，")
    L.append("这样 `(elder_id, plan_date)` 也能被 `(elder_id)` 前缀复用。")
    L.append("")
    L.append("### 3. 用唯一索引替代外键做幂等")
    L.append("")
    L.append("三处唯一索引同时承担**业务约束 + 幂等保证**：")
    L.append("")
    L.append("| 唯一索引 | 约束 | 防的场景 |")
    L.append("|---|---|---|")
    L.append("| `uk_order_node(order_id, node)` | 同单同节点只打一次卡 | 网络重试导致重复打卡 |")
    L.append("| `uk_plan_time(plan_id, plan_time)` | 同计划同时间点只有一条任务 | 定时任务重复触发产生重复服药任务 |")
    L.append("| `uk_order_id(order_id)` | 一单一评 | 家属连点两次提交按钮 |")
    L.append("")
    L.append("### 4. 冗余字段是有意为之")
    L.append("")
    L.append("`medication_task` 冗余了 `medicine_name` / `dosage` / `meal_relation`，")
    L.append("`order_review` 冗余了 `order_no` —— 目的是让**历史记录不受主数据变更影响**：")
    L.append("药品字典改名后，三个月前那条服药任务仍应显示当时的药名。")
    L.append("")
    L.append("### 5. 为什么没有物理外键")
    L.append("")
    L.append("① 本课程设计需演示**并发接单**，外键会加剧行锁竞争；")
    L.append("② 逻辑删除与 `ON DELETE` 语义冲突，`deleted=1` 的行仍被外键视为「存在」；")
    L.append("③ 参照完整性由 Service 层校验 + 唯一索引兜底。")
    L.append("")
    L.append("## 三、EXPLAIN 实测结论")
    L.append("")
    L.append("对 7 类高频查询执行 `EXPLAIN`，**全部命中索引，无 `type=ALL` 全表扫描，无 `filesort`**：")
    L.append("")
    L.append("| # | 查询场景 | 命中索引 | 结论 |")
    L.append("|---|---|---|---|")
    L.append("| 1 | 待接单按时间倒序 | `idx_status_create` | type=ref，无 filesort |")
    L.append("| 2 | 老人某日待服药任务 | `idx_elder_date_status` | type=ref（三列全用） |")
    L.append("| 3 | 可接单陪诊员列表 | `idx_audit_work` | type=ref |")
    L.append("| 4 | 订单大厅 | `idx_status_visit` | type=range，**无 filesort** |")
    L.append("| 5 | 未读消息数 | `idx_receiver_read` | type=ref，覆盖索引 |")
    L.append("| 6 | 漏服扫描 | `idx_status_plan_time` | type=range |")
    L.append("| 7 | 评分聚合 | `idx_companion_valid` | type=ref，聚合前已过滤 |")
    L.append("")
    L.append("### 已知未走索引的查询（有意暂不优化）")
    L.append("")
    L.append("查询「`status='CANCELLED'` 且按 `cancel_time` 倒序」时，EXPLAIN 出现")
    L.append("`Using filesort` —— 因为 `idx_status_create(status, create_time)` 与")
    L.append("`idx_status_visit(status, visit_time)` 的排序键都不是 `cancel_time`。")
    L.append("")
    L.append("**暂不建索引的理由**：取消单目前仅 3 条，排序开销可忽略；且「按取消时间排序」")
    L.append("是临时试出来的查询，不在 `docs/api/` 的既有接口里，为一个未确认的需求加索引属于过度设计。")
    L.append("")
    L.append("**现状约定**：订单列表（含已取消、已评价等终态）**统一按 `create_time` 排序**，")
    L.append("复用 `idx_status_create`，可避免 filesort。若 M9 管理端确实需要按取消时间排序，")
    L.append("再加 `KEY idx_status_cancel (status, cancel_time)` 即可。")
    L.append("")
    L.append("## 四、后续优化预案（M10 之后）")
    L.append("")
    L.append("数据量增大后优先考虑：① `companion_order` 按月分区；")
    L.append("② `order_review` 评分聚合改由异步任务冗余到 `companion_profile`（已预留字段）；")
    L.append("③ `companion_track` 轨迹点属于时序数据，可迁到 Redis / 时序库并 TTL 淘汰；")
    L.append("④ 按需补 `idx_status_cancel(status, cancel_time)` 等终态列表索引。")
    L.append("")
    return "\n".join(L)


def doc_seed(tables, counts):
    L = []
    L.append("# 银龄伴诊 · 种子数据说明（M1）")
    L.append("")
    L.append("> 脚本：`backend/sql/V2__seed_data.sql`")
    L.append("")
    L.append("## 一、数据规模")
    L.append("")
    total = sum(counts.get(t["table"], 0) for t in tables)
    L.append("共 %d 张表、**%s** 行数据，业务表每张 **≥ 30 行**（满足验收最低要求）。" % (len(tables), format(total, ",")))
    L.append("")
    L.append("| 表 | 中文名 | 行数 | 说明 |")
    L.append("|---|---|---|---|")
    note = {
        "sys_user": "92 个账号：老人 30 / 家属 30 / 陪诊员 30 / 管理员 2（含 1 个已封禁的 `elder030`）",
        "sys_login_log": "成功 32 / 失败 8；失败原因覆盖「账号或密码错误 / 验证码错误或已过期 / 账号已被封禁 / 登录失败次数过多已锁定」4 类",
        "sys_dict": "7 类：医院 10 / 科室 15 / 服务类型 3 / 投诉类型 6 / 行动能力 3 / 订单状态 6 / 结算状态 2",
        "sys_file": "4 类 biz_type：打卡照片 12 / 资质证件 12 / 投诉证据 6 / 评价图片 6，含 pdf 证书",
        "admin_oper_log": "36 条 + V3 补 1 条；6 种操作类型（资质审核 24 / 封禁 4 / 重置密码 3 / 投诉处理 3 / 纠纷仲裁 2 / 公告 1）",
        "elder_profile": "32 份档案，其中 2 份 `bind_status=UNBOUND` 且 `user_id` 为空（家属代建未绑定：431 / 432）",
        "family_elder_relation": "30 条有效绑定 + V3 补 1 条已解绑（`status=UNBOUND`，带 `unbind_time`）",
        "companion_audit_record": "APPROVED 25 / PENDING 6 / REJECTED 3，驳回记录带驳回原因",
        "companion_profile": "含待审核 / 已驳回 / 健康证已过期 2 人；评分区间 0.00–4.90",
        "companion_order": "原 60 条覆盖 5 种状态；V3 补 3 条 CANCELLED，**6 种状态全覆盖**",
        "order_status_log": "232 条 + V3 补 14 条；每个订单至少 1 条，已完成订单有完整 5 段流转链路",
        "order_reject_log": "仅针对 PENDING 订单，36 条且 `(order_id, companion_id)` 无重复",
        "order_checkin": "272 条，6 个节点齐全（出发 54 / 到院 46 / 就诊中 46 / 取药 42 / 离院 42 / 完成 42）；13 条异常打卡",
        "companion_track": "544 个轨迹点，按订单 × 时间递增，含定位精度与速度",
        "medicine_dict": "61 种药，常用药 49 种；剂型 5 类（片剂 46 / 胶囊 8 / 口服液 3 / 其他 3 / 注射剂 1）；每条带免责声明",
        "medication_plan": "36 条，18 条长期用药（`end_date IS NULL`）；6 条含 `00:30` 跨零点时点；36/36 满足 `JSON_LENGTH(time_points) = frequency`",
        "medication_task": "520 条：TAKEN 327 / MISSED 120 / PENDING 73；55 条 MISSED 且 `was_missed=1`（漏服后补记）",
        "order_review": "30 条评分 2–5 星；含 6 条匿名、1 条管理员判定无效（`is_valid=0`）；V3 补 1 条 1 星差评",
        "complaint": "32 条：PENDING 10 / RESOLVED 11 / PROCESSING 6 / REJECTED 5；6 类投诉类型齐全；11 条 `penalty_to_target=1`；最长正文 440 字",
        "internal_message": "72 条 + V3 补 3 条；9 种消息类型（原每类 8 条）；已读 25 / 未读 50",
    }
    for domain, names in DOMAINS:
        for name in names:
            t = next(x for x in tables if x["table"] == name)
            L.append("| `%s` | %s | %d | %s |" % (
                t["table"], TABLE_CN.get(t["table"], t["comment"]),
                counts.get(t["table"], 0), note.get(t["table"], "")))
    L.append("")
    L.append("## 二、边界场景覆盖（答辩演示用）")
    L.append("")
    L.append("种子数据刻意制造了下列边界，方便演示系统的健壮性：")
    L.append("")
    L.append("| 场景 | 数据表现 | 演示价值 |")
    L.append("|---|---|---|")
    L.append("| 未绑定家属的老人档案 | `elder_profile` 431 / 432：`bind_status='UNBOUND'`，`user_id`、`phone` 均为空 | 家属「认领老人」流程 |")
    L.append("| 已解绑的绑定关系 | V3：`family_elder_relation` id 531 `status='UNBOUND'`，有 `unbind_time` | 解绑后历史订单仍可追溯 |")
    L.append("| 订单被取消（三种成因） | V3：1061 待接单时取消 / 1062 接单后取消 / 1063 管理员强制终止（`arbitrate_flag=1`） | 订单终态 + 管理员纠纷处理 |")
    L.append("| 一星差评 | V3：`order_review` id 30031，`score=1` | 差评 → 投诉 → 违规累计链路 |")
    L.append("| 资质被驳回 | `companion_audit_record` 3 条 `REJECTED`，带驳回原因 | 陪诊员重新提交申请 |")
    L.append("| 健康证过期 | `companion_profile` 2 人 `health_cert_expire < CURDATE()` | 系统自动置为不可接单 |")
    L.append("| 账号已被封禁 | `sys_user` id 230（`elder030`）`status='DISABLED'` | 登录被拦截 + 失败日志落库 |")
    L.append("| 拒单后不再显示 | `order_reject_log` 36 条，订单本身仍为 PENDING | 「拒单不改变订单状态」 |")
    L.append("| 异常打卡 | `order_checkin` 13 条 `is_abnormal=1`，`distance` 远超阈值 | 定位校验与异常告警 |")
    L.append("| 跨零点服药 | `medication_plan` 6 条 `time_points` 含 `00:30` | 跨日任务生成 |")
    L.append("| 长期用药 | `medication_plan` 18 条 `end_date IS NULL` | 无终止日期的计划 |")
    L.append("| 漏服与补记 | `medication_task` 120 条 MISSED，其中 55 条 `was_missed=1` | 漏服提醒与漏服率统计 |")
    L.append("| 无效评价 | `order_review` 1 条 `is_valid=0` | 无效评价不计入评分聚合 |")
    L.append("| 匿名评价 | `order_review` 6 条 `is_anonymous=1` | 对外不暴露家属姓名 |")
    L.append("| 超长文本 | 病史 433/500 字、订单备注 268/500 字、用药计划备注 184/200 字、投诉正文 440/1000 字 | 长文本渲染与字段上限 |")
    L.append("| 空值容错 | `elder_profile`：无手机号 2 条、无过敏史 8 条、无病史 3 条、无身份证号 20 条 | 脱敏与空值渲染 |")
    L.append("")
    L.append("## 三、内置演示账号")
    L.append("")
    L.append("**统一初始密码：`Nl@123456`**（库中为 BCrypt 哈希，`$2a$10$` 开头）")
    L.append("")
    L.append("| 角色 | 用户名 | 数量 | 说明 |")
    L.append("|---|---|---|---|")
    L.append("| 老年患者 `ELDER` | `elder001` ~ `elder030` | 30 | 默认只读，写操作须由家属代做；其中 `elder030` 已被封禁 |")
    L.append("| 家属 `FAMILY` | `fam001` ~ `fam030` | 30 | 可下单、代老人操作、查看进度 |")
    L.append("| 陪诊员 `COMPANION` | `comp001` ~ `comp030` | 30 | 需审核通过才可接单（含待审核 6 人 / 已驳回 3 人） |")
    L.append("| 管理员 `ADMIN` | `admin`、`superadmin` | 2 | 资质审核、用户封禁、订单纠纷、数据统计 |")
    L.append("")
    L.append("> 三个角色各 30 个账号，便于演示「同角色多人竞争同一订单」的并发接单场景。")
    L.append("")
    L.append("## 四、冗余聚合字段的一致性")
    L.append("")
    L.append("`companion_profile` 的 `score` / `review_count` / `order_count` / `accept_count`")
    L.append("是**有意冗余**的聚合值（避免列表页实时聚合）。V3 新增订单与评价后已同步更新：")
    L.append("")
    L.append("| 陪诊员 | 变化 | 原因 |")
    L.append("|---|---|---|")
    L.append("| 311 徐文斌 | `accept_count` +1 | 订单 1062 被接单（虽后取消，接单数仍计入） |")
    L.append("| 312 孙玉兰 | `accept_count` +1 | 订单 1063 被接单 |")
    L.append("| 313 马晓东 | `accept_count` / `order_count` / `review_count` 各 +1，`score` 4.09 → 4.04 | 订单 1064 完成并收到 1 星评价，按加权平均重算 |")
    L.append("")
    L.append("> `score` 的重算按 `(旧均分 × 旧评价数 + 新增评分) / (旧评价数 + 1)`；")
    L.append("> 注意 MySQL 的 `UPDATE` 中后置赋值会读到已更新的值，故脚本里 `score` 写在 `review_count` 之前。")
    L.append("")
    L.append("### 为什么 `review_count` 与 `order_review` 的行数对不上")
    L.append("")
    L.append("`companion_profile` 的 `review_count` / `order_count` / `accept_count` 表示")
    L.append("该陪诊员的**平台累计值**（含种子数据窗口之前的更早历史），")
    L.append("而 `order_review` 只落了最近 **31 条**评价样本。两者不相等是**有意为之**：")
    L.append("")
    L.append("- 若严格按 `order_review` 重算，每个陪诊员只剩 1–2 条评价，")
    L.append("  评分会退化成「1.0 / 3.0 / 5.0」几个离散值，**失去区分度**，")
    L.append("  榜单、评分排序、优质陪诊员筛选这些页面就没法演示；")
    L.append("- 真实系统里累计值本就由**异步任务**随订单完成逐步累加，")
    L.append("  而不是每次从明细表现聚合（这正是该字段冗余存在的原因）。")
    L.append("")
    L.append("> 因此 M10 做数据统计时，口径要明确：**陪诊员维度的累计指标读 `companion_profile`，")
    L.append("> 评价明细与舆情分析读 `order_review`**，不要把两者混用。")
    L.append("")
    L.append("## 五、敏感数据处理")
    L.append("")
    L.append("| 字段 | 处理方式 | 说明 |")
    L.append("|---|---|---|")
    L.append("| `sys_user.password` | BCrypt 哈希 | `$2a$10$` 前缀，不可逆；禁止明文/可逆加密 |")
    L.append("| `elder_profile.id_card` | AES-256-GCM 密文 | 密钥由 `ID_CARD_AES_KEY` 环境变量注入，密钥经 SHA-256 派生 |")
    L.append("| `companion_profile.id_card` | AES-256-GCM 密文 | 同上 |")
    L.append("| `companion_audit_record.id_card` | AES-256-GCM 密文 | 同上 |")
    L.append("| `*.phone` | 明文入库，**接口返回脱敏** | Service 层统一 `138****8888` 处理后返回 VO |")
    L.append("| `*.address` | 明文入库，门牌号打码后返回 | 同上 |")
    L.append("")
    L.append("> 加密工具：`backend/src/main/java/org/company/nianglin/util/AesUtil.java`。")
    L.append("> 密钥未配置时使用开发默认值，**生产环境必须通过环境变量覆盖**。")
    L.append("")
    return "\n".join(L)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "render"
    with open(DDL, "r", encoding="utf-8") as f:
        text = f.read()
    tables = parse_ddl(text)

    if mode == "counts":
        lines = []
        for i, t in enumerate(tables):
            prefix = "SELECT" if i == 0 else "UNION ALL SELECT"
            lines.append("%s '%s' AS t, COUNT(*) AS c FROM nianglin.`%s`" % (prefix, t["table"], t["table"]))
        lines.append("UNION ALL SELECT 'table_total', COUNT(*) FROM information_schema.tables"
                     " WHERE table_schema='nianglin' AND table_name NOT LIKE 'flyway%'")
        lines.append("ORDER BY t;")
        os.makedirs(TMP, exist_ok=True)
        out_sql = os.path.join(TMP, "nianglin_counts.sql")
        with open(out_sql, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        print("counts sql written ->", out_sql)
        print("next:")
        print("  mysql --batch --skip-column-names <  \"%s\" > \"%s\"" % (out_sql, COUNTS_TSV))
        return

    counts = load_counts()
    os.makedirs(OUT_DIR, exist_ok=True)
    files = {
        "README.md": doc_readme(tables, counts),
        "01-ER图.md": doc_er(tables, counts),
        "02-数据字典.md": doc_dict(tables, counts),
        "03-索引设计与EXPLAIN.md": doc_index(tables, counts),
        "04-种子数据说明.md": doc_seed(tables, counts),
    }
    for name, content in files.items():
        with open(os.path.join(OUT_DIR, name), "w", encoding="utf-8") as f:
            f.write(content)
        print("  %-30s %6d chars" % (name, len(content)))
    print("docs written to", OUT_DIR)
    missing = [t["table"] for t in tables if t["table"] not in counts]
    if missing:
        print("!! 缺少行数的表:", ", ".join(missing))


main()
