# GLOSSARY.md

> **本文件是别名指针,不是术语表正文。**
> 本仓库的术语表正文唯一位于 [`CONTEXT.md`](./CONTEXT.md)(银龄伴诊 · 项目术语表)。

## 为什么有两个名字

同一套 Matt Pocock 工程技能存在两个版本目录,各自把术语表文件名写死在技能里:

| 技能 | 硬编码的文件名 | 归属 |
| --- | --- | --- |
| `/domain-modeling`(术语表的**写入方**,被 `/grill-with-docs` 调用) | `CONTEXT.md` | 插件版本 |
| `/tdd`、`/diagnosing-bugs`、`/improve-codebase-architecture`、`/wait-what`、`/pr`(术语表的**读取方**) | `GLOSSARY.md` | 本地版本 |

两边都硬编码,只改一端会断:

- 只把正文改名成 `GLOSSARY.md` → `/domain-modeling` 找不到 `CONTEXT.md`,会**新建一个空文件**,术语从此分叉;
- 只把正文改名成 `CONTEXT.md` → 读取方找不到术语表,静默退回通用词汇(技能提示里说"if it exists",不会报错)。

所以:**正文留在 `CONTEXT.md`,本文件作为读取方的入口指针。** 新增 / 修改术语一律改 `CONTEXT.md`。

## 维护规则

1. **只改 `CONTEXT.md`。** 本文件不含任何术语条目 —— 两份术语表必然漂移。
2. `/domain-modeling` 在讨论中就地更新 `CONTEXT.md`,本文件**永远不需要同步**。
3. 本文件只负责把读者引到 `CONTEXT.md`。
