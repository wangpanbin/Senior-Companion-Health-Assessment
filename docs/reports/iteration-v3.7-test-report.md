# V3.7 家属智能助手 · 测试报告

| 项 | 值 |
|---|---|
| 版本 | V3.7 家属智能助手（M14 · `POST /api/assistant/chat` + 独立 `ai-service`） |
| 日期 | 2026-10-21 |
| 分支 | `feature/assistant-v37-ai-service`（从 `main` @ `4332b66` 切出，领先 3 个 commit） |
| 上游契约 | `docs/spec/V3.7-assistant-spec.md` §七 验收标准 · `docs/spec/V3.7-tickets.md` T1–T11 |
| 设计决策 | [ADR-0020](../adr/0020-assistant-boundary-and-recommendation-guardrail.md) |
| 评审报告 | [两轴评审报告](../review/2026-10-21-v3.7-assistant.md) |

---

## 一、执行摘要

| 门禁 | 结果 | 变化 |
|---|---|---|
| `python -m pytest ai-service/tests -q` | ✅ **187 / 187**（本机 cwd=`ai-service/`，读到 `.env` → LLM 冒烟真跑）<br>✅ **183 passed + 4 skipped**（CI / 仓库根：无 `.env` → 4 条 LLM 冒烟按设计跳过） | 基线 157 → **+30** |
| `mvn -B -o verify` | ✅ **1319 / 1319** · BUILD SUCCESS | 基线 1314 → **+5** |
| `pnpm run lint:check` | ✅ **0 警告** | `--max-warnings 0` |
| `pnpm exec vitest run --maxWorkers=2` | ✅ **230 / 230**（21 files） | 基线 228 → **+2** |
| `pnpm exec playwright test 21-assistant` | ✅ **18 / 18** | 新增 |
| `python tools/check_secrets.py` | ✅ 五条全过（960 文件） | — |
| `python tools/check_faq_anchors.py` | ✅ 30 条锚点全对得上 | 禁用词表改读**共享真源** |
| `python tools/check_metric_dict_parity.py` | ✅ 64 条指标一致 | — |
| 问法池覆盖门禁 | ✅ 召回 **97.2%** · 抢占 **0** | 新增 |

---

## 二、本轮最重要的两件事

### 1. 「LLM 从来没被真正接上过」—— 一个所有人都绿的故障

上一轮交接说「LLM 路径从未真正跑过，没配 LLM_BASE_URL」。**真实根因是代码，不是配置**：

```python
from langchain.prompts import PromptTemplate   # ← LangChain 1.x 已移除该模块
```

LangChain 1.x 把模板类搬到了 `langchain_core.prompts`；旧路径在 1.x 上必然 `ImportError`，
被 `build_provider` 兜成 `NullProvider` → 回答永远是 FAQ 原文、`degraded` 恒 `true`。

**三个放大因素让它躲过了所有检查**：

| 放大因素 | 后果 |
|---|---|
| 唯一的 LangChain 测试整组 `skipif`（没配 key 就跳过） | 这条路径**永远测不到** |
| `test_llm_smoke.py` 用 `os.getenv` 判定，而 pydantic-settings **不把 `.env` 注入 `os.environ`** | 按文档写 `.env` 反而让冒烟测试永远跳过 |
| 启动日志按**配置项**报告「已启用」 | 日志说已启用、行为是降级，**自相矛盾却没人看** |

**处置**：
- 导入路径改为 `from langchain_core.prompts import PromptTemplate`（0.x / 1.x 都成立的唯一稳定路径）
- 新增 `ai-service/tests/test_llm_provider.py`：**离线、无需网络无需 key** 断言「构造成功 ≠ 降级」
- 冒烟测试改走 `get_settings()`（与运行时同一份配置）
- 启动日志改为按**实际生效的 provider** 报告

> 教训：**「所有测试都绿」不等于「功能被跑过」**。
> 门禁必须断言「路径确实被执行」，而不是断言「结果看起来对」。

### 2. 关键词覆盖：34.4% → 97.2%，且验收由**确定性检索**决定

FAQ 检索是「keyword 子串命中 × 3.0 + 2-gram Jaccard × 2.0」，阈值 `MIN_SCORE = 2.0`。
2-gram Jaccard 最大 1.0 → 最多 2.0 分，**恰好等于阈值** ——
所以能稳定命中的只有「问题里包含某条 keyword」这一条路。
**keywords 就是命中率本身**，它不是调优项，是功能。

V3.7 联调已抓到两次关键词缺口（前端自己的首个预设问题「陪诊费怎么算？」答不上来；
下单条不认「约陪诊」），说明靠推导补词会一直漏。

| 项 | 回填前 | 回填后 |
|---|---|---|
| 问法池召回率 | 60/180 = **33.3%** | 174/179 = **97.2%** |
| 抢占（目标条目被别的条目抢走） | — | **0** |
| 关键词总数 | 140 | 268 |
| 单条目关键词数上限 | 6 | 14（实测扫描：压到 6 → 召回 86.7%） |

**验收不靠模型**：模型只负责造出像人话的提问；
「这条问法答不答得上来」由 `FaqIndex.best()` 说了算 ——
工具直接 import `app.knowledge` 的**真实检索实现**，不重写打分逻辑。

⚠️ **问法是模拟数据不是真实用户数据** —— PRD 风险第 5 条**保持未关闭**。
详见 [V3.7-faq-question-pool.md](../spec/V3.7-faq-question-pool.md)。

---

## 三、评审发现项的修复与复验（两轴 9 项，全部已修）

Standards 轴 2 条硬违规 + 7 条判断题；Spec 轴 3 + 1 + 4 条。逐条处置见[评审报告](../review/2026-10-21-v3.7-assistant.md)。
本报告只列**改变了行为**的几条：

| # | 缺陷 | 处置 |
|---|---|---|
| 1 | `check_faq_anchors.py` 内嵌**第三份**医疗禁用词表副本 → 违反「不要另抄一份词表」 | 改为读共享真源；读不到 → 门禁**变红**并说明来源 |
| 2 | 「距离近 / 就在附近」**只在 prompt 里有约束**，代码层零兜底 | `guard.scan_output` 新增距离措辞拦截与词语级纠正；两种纠正**不互相早退** |
| 3 | `history` 全链路通了却**零消费** —— 助手没有记忆 | 历史进事实包，单独成段、标题写明「仅供理解上下文，不是事实依据」 |
| 4 | `visitTime` 把用户没说的时刻补成 `09:00:00`（与契约「只含抽到的字段」冲突） | 只找到日期没找到时刻 → 留空，让家属在确认页自己补 |
| 5 | Java 本地兜底路径**漏了**派单措辞纠正（契约明写要过） | `guardAnswer` 补上词语级纠正 + 免责句；新增 `AssistantServiceImplGuardTest` |
| 6 | 降级提示文案把「服务故障」说成「没接模型」 | 文案只描述**事实**：「以下为平台标准答案原文（未经过大模型生成）」 |
| 7 | `code >= 1000` 让 **400** 这类业务错误掉进兜底文案 | 判据改成「`code` 是数字且 > 0」 |
| 8 | 8 处新测试方法名不符合 `methodNameShouldXxx` | 改名（`main` 上已有的 27 处先例不在本特性范围） |
| 9 | `intents.py` 关键词重复项 | 删除 |

---

## 四、降级路径的**真实进程**验证

上一轮交接点名：降级路径**从未在真实进程里验过**。本轮验了：

| 步骤 | 断言 | 结果 |
|---|---|---|
| 现状 | 8081 的监听者确实是本项目的 uvicorn（不是别人的进程） | ✅ |
| 挪走 `.env` → 重启 | 启动日志报「**未配置（基础模式…）**」，**不出现**「已启用」 | ✅ |
| 降级模式提问 | `degraded == true`、`sources` 非空、`answer` 非空 | ✅ |
| 还原 `.env` → 重启 | 日志回到「**已启用（模型=deepseek-chat）**」 | ✅ |
| 接模型后提问 | `degraded == false`（真的走了模型） | ✅ |
| 收尾 | `.env` 已还原、服务仍在跑 | ✅ |

> 脚本：`tools/` 之外的一次性验证脚本，`.env` 往返在 `finally` 里做 ——
> **还原失败 = 阻断项**。
> 这次验的正是「**日志与行为一致**」——而它恰恰是本轮修的最大缺陷那一类。

---

## 五、一条环境级的真实故障（**不是代码缺陷**）

首次 `mvn verify` 时 `MatchingRealDbTest.orderRegionCodeShouldMatchAddressText` **红了**：
订单 1065 地址含「秀英区」但 `service_region_code` 为 `null`。

- 该订单**不在种子数据里**（种子止于 1064），是上一轮 Playwright E2E 留下的脏行；
- `backend/sql/` 种子脚本**未改动**；
- 清理后重跑 `mvn verify` → **1314 全绿**。

**教训**：跑在真库上的测试会被 E2E 夹具污染。
E2E 跑完应当恢复基线（仓库自带 `tools/e2e/restore_clean_baseline.py`），
否则**下一次** `mvn verify` 会以一个与代码无关的红灯收场。

---

## 六、未验证 / 保持开放的事项

| # | 事项 | 状态 |
|---|---|---|
| 1 | **FAQ 覆盖未经真实用户验证** | **保持未关闭** —— 179 条是**模拟问法**。关闭条件：上线后拿真实的未命中问题回流 |
| 2 | 限流阈值 10 次/分钟 | 拍板值，无真实流量数据支撑。本轮**未改动**；E2E 侧用多账号轮换 + 2s 间隔绕开 |
| 3 | 本地 Ollama 档位 | **本轮未验**（显存空闲仅 6645MiB，7–8B 档较稳）。本轮用云端 DeepSeek |
| 4 | 6 条近义条目歧义问法 | **已登记**（`KNOWN_AMBIGUOUS`），不硬凑召回 |
| 5 | 问法池重新生成会换问法 | 模型有随机性；门禁不依赖具体问法内容，但**重新生成后必须复跑门禁** |
| 6 | `git push` / 合并回 `main` | **本轮明确不做**（AGENTS.md 红线），需用户另行明确要求 |

---

## 七、变更规模

| 面 | 规模 |
|---|---|
| 本特性（`main` → 工作区） | 77 文件 / 9480 增 / 49 删 |
| 本轮收口（相对本特性起点） | 评审修复 9 项 + FAQ 问法池（+128 关键词）+ 文档同步 7 处 |
| 新增文件 | `tools/gen_faq_questions.py`、`ai-service/knowledge/faq-question-pool.json`、`ai-service/tests/test_faq_coverage.py`、`docs/spec/V3.7-faq-question-pool.md`、`docs/reports/iteration-v3.7-test-report.md`、`docs/review/2026-10-21-v3.7-assistant.md`、`backend/.../impl/AssistantServiceImplGuardTest.java` |
| 新增依赖 | **零**（`langchain-core>=0.1` 是已批准的**显式声明**，不是新第三方库） |
| 新增错误码 | **零**（复用 10xxx 段） |
| 数据库迁移 | **零**（V3.7 无迁移） |
