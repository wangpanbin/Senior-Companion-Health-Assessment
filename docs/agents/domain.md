# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root — **本仓库术语表的唯一真源**, or
- **`GLOSSARY.md`** at the repo root — 指向 `CONTEXT.md` 的别名指针(见下「别名与唯一真源」),只读入口,
- **`CONTEXT-MAP.md`** at the repo root if it exists: it points at one `CONTEXT.md` per context. Read each one relevant to the topic.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in. In multi-context repos, also check `src/<context>/docs/adr/` for context-scoped decisions.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## 别名与唯一真源

`CONTEXT.md` 与 `GLOSSARY.md` 指向**同一份**术语表:

- **`CONTEXT.md` 是正文**,`/domain-modeling`(写入方)就地更新它;
- **`GLOSSARY.md` 只是指针**,不含任何术语条目,供硬编码 `GLOSSARY.md` 的读取方(`/tdd`、`/diagnosing-bugs`、`/improve-codebase-architecture`、`/wait-what`、`/pr`)找到词汇表。

**只改 `CONTEXT.md`。** 不要往 `GLOSSARY.md` 里加术语条目 —— 两份术语表必然漂移;也不要把正文搬进 `GLOSSARY.md`(那会让 `/domain-modeling` 另建一个空 `CONTEXT.md`,术语从此分叉)。

## File structure

Single-context repo (most repos):

```
/
├── CONTEXT.md                          ← glossary: single source of truth
├── GLOSSARY.md                         ← alias pointer → CONTEXT.md (no terms of its own)
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

Multi-context repo (presence of `CONTEXT-MAP.md` at the root):

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← system-wide decisions
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← context-specific decisions
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_
