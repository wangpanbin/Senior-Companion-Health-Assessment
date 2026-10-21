# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root — **本仓库术语表的唯一真源**（银龄伴诊 · 领域词汇表）
- **`GLOSSARY-MAP.md`** at the repo root if it exists: it points at one `GLOSSARY.md` per context. Read each one relevant to the topic.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in. In multi-context repos, also check `src/<context>/docs/adr/` for context-scoped decisions.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## 唯一真源

`GLOSSARY.md` 是**唯一**的术语表，**没有别名文件**：

- **写入方** `/domain-modeling` 就地更新 `GLOSSARY.md`；
- **读取方** `/tdd`、`/diagnosing-bugs`、`/improve-codebase-architecture`、`/wait-what`、`/pr`、`/codebase-design` 按硬编码文件名读 `GLOSSARY.md`。

读写两端指向同一个名字，所以不存在第二份术语表，也就不存在两份漂移。

> ⚠️ **不要**另建 `CONTEXT.md` 之类的别名文件：写入方找不到 `GLOSSARY.md` 会**新建一个空文件**，读取方则静默退回通用词汇（技能提示写的是 "if it exists"，不会报错）——术语从此分叉且无人察觉。

**只改 `GLOSSARY.md`。**

## File structure

Single-context repo (most repos):

```
/
├── GLOSSARY.md                          ← glossary: single source of truth
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

Multi-context repo (presence of `GLOSSARY-MAP.md` at the root):

```
/
├── GLOSSARY-MAP.md
├── docs/adr/                          ← system-wide decisions
└── src/
    ├── ordering/
    │   ├── GLOSSARY.md
    │   └── docs/adr/                  ← context-specific decisions
    └── billing/
        ├── GLOSSARY.md
        └── docs/adr/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `GLOSSARY.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_
