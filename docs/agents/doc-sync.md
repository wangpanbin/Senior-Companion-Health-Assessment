# 9 · 文档同步清单（每次代码改动要联动更新的文档）

> 本文件是 `AGENTS.md` **§9** 的落地位置。
> 拆分原因：运行时给仓库根 `AGENTS.md` 的上下文预算是 **32 KB**，
> 拆分前它有 **54 KB** —— 超出预算的部分**每次会话都被静默丢弃**，
> 丢掉的恰好是订单状态机、Do/Don't 底线、验收清单这些最该被看见的内容。
> 拆开后 `AGENTS.md` 只留红线与导航，细则**按需加载**。
>
> ⚠️ 硬约束仍在 `AGENTS.md`（始终在上下文里）：§0 合规红线、§0.2 AI 自我约束、
> §10 Do/Don't 最低底线、§11 验收检查清单。本文件是细则，不覆盖那些硬约束。
>
> 小节编号（§9.1 …）**未改动**，仓库其它文档里的 `AGENTS.md §9.N` 引用仍然对得上。

---

| 改动内容 | 必联动更新 |
|---|---|
| 新增 / 修改接口 | `docs/api/<module>.md` + Knife4j 注解 + `frontend/src/api/<module>.js` |
| 新增 / 修改错误码 | `ResultCode.java` + `docs/api/README.md` 错误码总表 |
| 新增 / 修改状态机流转 | `OrderStatus.java` 的 `TRANSITIONS` + `OrderStatusTest` + `docs/api/03-order.md` 状态机章节 |
| 新增 / 修改依赖 | `pom.xml` / `package.json` + README 技术栈表 |
| 新增 / 修改菜单 / 路由 | `frontend/src/router/routes.js` + `BasicLayout.vue`（若需要老人模式隐藏） |
| 新增 / 修改样式令牌 | `frontend/src/styles/variables.scss`（编译期） + `index.scss` 的 `:root`（运行期） + `elderly.scss`（老人模式覆盖） |
| 新增 / 修改数据库表 | Flyway 脚本 + `ResultCode`（若有新业务码） + `docs/api/<module>.md` 数据模型小节 |
| 新增 / 修改合规相关（脱敏、加密、日志） | `MaskUtil` 单测 + `GlobalExceptionHandler` 日志策略 + README 合规约束章节 |
| 新增 / 修改 `ai-service/`（Python 助手服务） | `ai-service/requirements.txt`（依赖清单需重新报批） + `AGENTS.md` §0 技术栈表与 `docs/agents/backend-conventions.md` §2.11 + `docs/api/13-assistant.md` + `docs/spec/V3.7-assistant-spec.md` |
| 新增 / 修改 FAQ 知识库（`ai-service/knowledge/faq.json`） | 逐条核对 `source` + `anchor` 指向真实锚点 + `python tools/check_faq_anchors.py` 通过 + 答案不含合规禁用词 |
| 新增 / 修改共享合规词表 | `backend/src/main/resources/compliance/medical-advice-words.txt` + Java `ComplianceCheckUtilTest` + Python `test_guard.py`（**两侧快照必须逐字相同**） |
| 新增 / 修改 `docs/db/*` | ⚠️ **改生成器 `backend/sql/tools/gen_db_docs.py`，不要直接改 `docs/db/` 下的生成物** —— 那些文件由脚本整篇覆盖，下次 render 会把手写内容冲掉 |
