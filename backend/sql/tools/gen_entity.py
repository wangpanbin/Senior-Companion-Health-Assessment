# -*- coding: utf-8 -*-
"""解析 V*.sql 的 CREATE TABLE，为 --tables 指定表生成实体与基础 Mapper。"""
import argparse
import glob
import os
import re

BASE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                     "..", ".."))
SQL_GLOB = os.path.join(BASE, "sql", "V*.sql")
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tables", nargs="+", required=True,
                        help="仅覆盖指定表的 Entity 与基础 Mapper；不删除其它文件")
    args = parser.parse_args()
    tables = {}
    for path in sorted(glob.glob(SQL_GLOB)):
        with open(path, "r", encoding="utf-8") as f:
            for table, comment, cols in parse_ddl(f.read()):
                tables[table] = (table, comment, cols)
    unknown = set(args.tables) - tables.keys()
    if unknown:
        parser.error("找不到建表定义：" + ", ".join(sorted(unknown)))
    os.makedirs(ENTITY_DIR, exist_ok=True)
    os.makedirs(MAPPER_DIR, exist_ok=True)
    for table in dict.fromkeys(args.tables):
        _, comment, cols = tables[table]
        cls, code = gen_entity(table, comment, cols)
        with open(os.path.join(ENTITY_DIR, cls + ".java"), "w", encoding="utf-8", newline="\n") as f:
            f.write(code)
        with open(os.path.join(MAPPER_DIR, cls + "Mapper.java"), "w", encoding="utf-8", newline="\n") as f:
            f.write(gen_mapper(table, comment))
        print("generated:", cls, cls + "Mapper")


if __name__ == "__main__":
    main()
