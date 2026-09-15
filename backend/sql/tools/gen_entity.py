# -*- coding: utf-8 -*-
"""
从 V1__init_schema.sql 解析表结构，生成 MyBatis-Plus Entity 与 Mapper。
直接解析 DDL 而不是手写，保证「实体字段 ↔ 数据库列」零偏差（M1 验收项）。

输出：
  backend/src/main/java/org/company/nianglin/entity/*.java
  backend/src/main/java/org/company/nianglin/mapper/*.java
"""
import os
import re

BASE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                     "..", ".."))
DDL = os.path.join(BASE, "sql", "V1__init_schema.sql")
ENTITY_DIR = os.path.join(BASE, "src", "main", "java", "org", "company", "nianglin", "entity")
MAPPER_DIR = os.path.join(BASE, "src", "main", "java", "org", "company", "nianglin", "mapper")

# 由 BaseEntity 提供，实体类中不再重复声明
BASE_COLS = {"id", "create_time", "update_time", "deleted"}

# SQL 类型 -> Java 类型
TYPE_MAP = [
    (r"^BIGINT", "Long"),
    (r"^SMALLINT", "Integer"),
    (r"^TINYINT", "Integer"),
    (r"^INT", "Integer"),
    (r"^INTEGER", "Integer"),
    (r"^DECIMAL", "BigDecimal"),
    (r"^NUMERIC", "BigDecimal"),
    (r"^DATE$", "LocalDate"),
    (r"^DATETIME", "LocalDateTime"),
    (r"^TIMESTAMP", "LocalDateTime"),
    (r"^JSON", "String"),
    (r"^VARCHAR", "String"),
    (r"^CHAR", "String"),
    (r"^TEXT", "String"),
    (r"^LONGTEXT", "String"),
]


def java_type(sql_type):
    t = sql_type.upper().strip()
    for pattern, jt in TYPE_MAP:
        if re.match(pattern, t):
            return jt
    return "String"


def camel(col):
    parts = col.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def pascal_table(table):
    return "".join(p.capitalize() for p in table.split("_"))


def parse_ddl(text):
    """返回 [(table, table_comment, [(col, sql_type, not_null, comment), ...]), ...]"""
    tables = []
    # 去掉行注释，逐 CREATE TABLE 块解析
    blocks = re.findall(
        r"CREATE TABLE IF NOT EXISTS `(\w+)`\s*\((.*?)\n\)\s*ENGINE", text, re.S)
    for table, body in blocks:
        table_comment = ""
        m = re.search(r"COMMENT='([^']*)'\s*;?\s*$", text)
        # 表注释在 ) ENGINE=... COMMENT='...' 里，单独再抓一次
        m2 = re.search(
            r"CREATE TABLE IF NOT EXISTS `" + table + r"`.*?\n\)\s*ENGINE=[^\n]*?COMMENT='([^']*)'",
            text, re.S)
        if m2:
            table_comment = m2.group(1)
        cols = []
        for line in body.split("\n"):
            line = line.strip()
            if not line.startswith("`"):
                continue
            cm = re.match(r"`(\w+)`\s+([A-Za-z]+(?:\([^)]*\))?)\s*(.*)$", line)
            if not cm:
                continue
            col, sql_type, rest = cm.group(1), cm.group(2), cm.group(3)
            not_null = "NOT NULL" in rest.upper()
            ccm = re.search(r"COMMENT\s+'((?:[^']|'')*)'", rest)
            comment = ccm.group(1).replace("''", "'") if ccm else ""
            cols.append((col, sql_type, not_null, comment))
        tables.append((table, table_comment, cols))
    return tables


def gen_entity(table, table_comment, cols):
    cls = pascal_table(table)
    fields = [c for c in cols if c[0] not in BASE_COLS]
    imports = set()
    for _, sql_type, _, _ in fields:
        jt = java_type(sql_type)
        if jt == "BigDecimal":
            imports.add("java.math.BigDecimal")
        elif jt == "LocalDate":
            imports.add("java.time.LocalDate")
        elif jt == "LocalDateTime":
            imports.add("java.time.LocalDateTime")

    out = []
    # 仅当存在 is_ 前缀列时才需要显式列名注解，避免产生未使用的 import
    need_table_field = any(f[0].startswith("is_") for f in fields)

    out.append("package org.company.nianglin.entity;")
    out.append("")
    if need_table_field:
        out.append("import com.baomidou.mybatisplus.annotation.TableField;")
    out.append("import com.baomidou.mybatisplus.annotation.TableName;")
    if "version" in [f[0] for f in fields]:
        out.append("import com.baomidou.mybatisplus.annotation.Version;")
    out.append("import io.swagger.v3.oas.annotations.media.Schema;")
    out.append("import lombok.Data;")
    out.append("import lombok.EqualsAndHashCode;")
    out.append("import org.company.nianglin.common.BaseEntity;")
    for imp in sorted(imports):
        out.append("import %s;" % imp)
    out.append("")
    out.append("/**")
    out.append(" * %s —— 对应表 {@code %s}。" % (cls, table))
    out.append(" *")
    if table_comment:
        out.append(" * <p>%s</p>" % table_comment)
        out.append(" *")
    out.append(" * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>")
    out.append(" *")
    out.append(" * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转 VO，")
    out.append(" * 避免密码、身份证号、完整手机号等敏感字段外泄。</p>")
    out.append(" *")
    out.append(" * @since M1")
    out.append(" */")
    out.append("@Data")
    out.append("@EqualsAndHashCode(callSuper = true)")
    out.append('@TableName("%s")' % table)
    out.append("public class %s extends BaseEntity {" % cls)
    out.append("")
    for col, sql_type, not_null, comment in fields:
        jt = java_type(sql_type)
        if comment:
            out.append("    /**")
            out.append("     * %s" % comment.replace("\n", " "))
            out.append("     */")
        if col == "version":
            out.append("    @Version")
        # 以 is 开头的字段显式声明列名，避免 MyBatis-Plus 命名策略产生歧义
        if col.startswith("is_"):
            out.append('    @TableField("%s")' % col)
        out.append('    @Schema(description = "%s")' % (comment.replace('"', "'") or col))
        out.append("    private %s %s;" % (jt, camel(col)))
        out.append("")
    out.append("}")
    out.append("")
    return cls, "\n".join(out)


def gen_mapper(table, table_comment):
    cls = pascal_table(table)
    out = []
    out.append("package org.company.nianglin.mapper;")
    out.append("")
    out.append("import com.baomidou.mybatisplus.core.mapper.BaseMapper;")
    out.append("import org.apache.ibatis.annotations.Mapper;")
    out.append("import org.company.nianglin.entity.%s;" % cls)
    out.append("")
    out.append("/**")
    out.append(" * %s 数据访问接口。" % cls)
    out.append(" *")
    if table_comment:
        out.append(" * <p>%s</p>" % table_comment)
        out.append(" *")
    out.append(" * <p>通用 CRUD 由 {@link BaseMapper} 提供；本接口只声明复杂查询，")
    out.append(" * 复杂 SQL 写在 {@code resources/mapper/%sMapper.xml} 中。</p>" % cls)
    out.append(" *")
    out.append(" * @since M1")
    out.append(" */")
    out.append("@Mapper")
    out.append("public interface %sMapper extends BaseMapper<%s> {" % (cls, cls))
    out.append("}")
    out.append("")
    return "\n".join(out)


def main():
    with open(DDL, "r", encoding="utf-8") as f:
        text = f.read()
    tables = parse_ddl(text)
    os.makedirs(ENTITY_DIR, exist_ok=True)
    os.makedirs(MAPPER_DIR, exist_ok=True)
    # 清掉占位文件与旧的生成结果
    for d in (ENTITY_DIR, MAPPER_DIR):
        for name in os.listdir(d):
            if name == ".gitkeep" or name.endswith(".java"):
                os.remove(os.path.join(d, name))
    for table, tc, cols in tables:
        cls, code = gen_entity(table, tc, cols)
        with open(os.path.join(ENTITY_DIR, cls + ".java"), "w", encoding="utf-8") as f:
            f.write(code)
        with open(os.path.join(MAPPER_DIR, cls + "Mapper.java"), "w", encoding="utf-8") as f:
            f.write(gen_mapper(table, tc))
    print("tables parsed =", len(tables))
    print("entities =", len(os.listdir(ENTITY_DIR)))
    print("mappers  =", len(os.listdir(MAPPER_DIR)))
    for table, tc, cols in tables:
        print("  %-26s -> %-26s fields=%d" % (table, pascal_table(table), len(cols) - len(BASE_COLS)))


main()
