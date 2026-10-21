# ai-service · 家属智能助手服务

> V3.7 · M14 · 决策依据 [`ADR-0020`](../../docs/adr/0020-assistant-boundary-and-recommendation-guardrail.md)
> 契约 [`docs/api/13-assistant.md`](../../docs/api/13-assistant.md) · 工程规格 [`V3.7-assistant-spec.md`](../../docs/spec/V3.7-assistant-spec.md)

一个**独立进程**的 Python 服务，只做一件事：把 Java 门面预装好的结构化事实讲成人话。

---

## 一、它是什么，不是什么

| 它**是** | 它**不是** |
|---|---|
| 平台服务规则的自然语言问答器 | 医疗顾问（诊断 / 用药 / 剂量一律拒答） |
| 规则引擎推荐结果的**叙述者** | 排序器（排序永远是 V3.4 Java 规则引擎的活） |
| 无状态的纯计算 HTTP 服务 | 数据库客户端 / Java 客户端 / 持令牌的 Agent |
| **没配模型也能完整运行**的降级路径 | 依赖外网与 key 才能工作的功能 |

> ⚠️ 本服务**只监听 127.0.0.1**，不直接对浏览器开放。
> 对外契约是 Java 的 `POST /api/assistant/chat`；这里的 `/internal/**` 是内部通道。

---

## 二、启动

```bash
cd ai-service
pip install -r requirements.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8081
```

> ⚠️ `--host 127.0.0.1` **不要改成 0.0.0.0**。本服务没有鉴权，
> 它假定自己只在受信网络里被 Java 门面调用。暴露出去等于让任何人白用模型额度。

健康检查：`GET http://127.0.0.1:8081/healthz` → `{"status":"UP"}`

### 不配模型也能跑

**这是正常模式，不是故障。** 不设置任何 `LLM_*` 环境变量时：

- 意图分类、FAQ 检索、护栏、动作抽取 —— 全部照常工作（它们不依赖 LLM）；
- 回答退化为**标准答案原文**（`degraded = true`）；
- 页面会显示「当前为基础模式（未接入大模型），以下为标准答案」。

所以**演示、评审、CI 都不依赖外网、key 与配额**。

---

## 三、环境变量

全部可留空。留空即降级，不报错。

| 变量 | 默认 | 说明 |
|---|---|---|
| `LLM_BASE_URL` | 空 | OpenAI 兼容端点。**空 = 降级模式** |
| `LLM_API_KEY` | 空 | Ollama 场景填 `ollama` 即可 |
| `LLM_MODEL` | 空 | 模型名。留空时即使配了 base_url 也走降级 |
| `LLM_TIMEOUT_SECONDS` | `30` | 单次调用超时 |
| `NIANGLIN_COMPLIANCE_WORDS_PATH` | `../backend/src/main/resources/compliance/medical-advice-words.txt` | 共享合规词表路径 |
| `NIANGLIN_FAQ_PATH` | `knowledge/faq.json` | FAQ 路径 |

样例见 [`.env.example`](./.env.example)。

### 接本地 Ollama

```bash
LLM_BASE_URL=http://127.0.0.1:11434/v1
LLM_API_KEY=ollama
LLM_MODEL=qwen2.5:7b
```

### 接云端（OpenAI 兼容）

```bash
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_API_KEY=<你的 key>
LLM_MODEL=qwen-plus
```

> **模型名刻意没有代码默认值**：写死的默认值就是「配置错的默认值在生产静默生效」。

---

## 四、目录

```
ai-service/
├── requirements.txt      # 依赖清单（已人工逐项批准，越出需重新报批）
├── .env.example          # 环境变量样例（全部占位）
├── app/
│   ├── main.py           # FastAPI 入口；启动期 fail-fast 加载词表与 FAQ
│   ├── config.py         # 环境变量映射
│   ├── schemas.py        # 内部线缆模型（camelCase，与后端一致）
│   ├── paths.py          # 共享词表路径解析（读不到即启动失败）
│   ├── guard.py          # 三层护栏：输入分类 / 输出扫描
│   ├── intents.py        # 确定性意图分类（纯规则，不用 LLM）
│   ├── knowledge.py      # FAQ 加载与检索（关键词 + 2-gram，零依赖）
│   ├── llm.py            # provider 抽象与降级（LangChain 惰性导入）
│   ├── prompts.py        # prompt 模板（纯字符串，可 diff 可评审）
│   ├── actions.py        # 结构化动作抽取（正则，不用 LLM）
│   └── service.py        # 编排
├── knowledge/
│   └── faq.json          # 30 条 FAQ，每条带依据引用
└── tests/                # pytest（核心断言不依赖 LLM 与网络）
```

---

## 五、三条设计纪律

### 5.1 护栏在代码里，不在 prompt 里

`guard.py` 三层：输入侧意图分类（医疗意图**直接拒答，不经 LLM**）→ prompt 层约束 →
输出侧扫描（命中共享禁用词 → **整段替换**为拒答模板）。

**为什么不能只靠 prompt**：prompt 是「君子协定」，模型不保证遵守；
而合规红线一旦漏放，平台就成了医疗建议的发布渠道。

### 5.2 共享合规词表

Java（`backend/.../util/ComplianceCheckUtil.java`）与 Python（`app/paths.py`）读**同一个文件**：

```
backend/src/main/resources/compliance/medical-advice-words.txt
```

**读不到 / 读空 / 读错 → 服务拒绝启动。** 理由：词表读空 = 护栏静默失效 =
所有医疗回答放行，而且看起来一切正常 —— 这是最危险的一类故障。

生产部署时把**同一份文件**挂给两个服务。

### 5.3 降级路径与主路径同构

意图分类、检索、动作抽取**都不依赖 LLM**，所以「没配模型」时整条链路照跑，
只是最后的叙述退化为直接给 FAQ 原文。

不是「主路径 + 一个补丁」，而是**同一条路**。

---

## 六、测试

```bash
cd ai-service
python -m pytest tests -q
```

- **核心断言不依赖 LLM 与网络**（走降级路径），所以在 CI 与离线环境都必然可重复；
- `test_llm_smoke.py` 只在 `LLM_BASE_URL` 与 `LLM_MODEL` 都在时运行，否则整组跳过；
- 改了 FAQ 知识库还要跑仓库根的锚点校验：

```bash
python tools/check_faq_anchors.py
```

它会确认每条 FAQ 的 `source` 文件存在、`anchor` 标题逐字对得上、
且**答案不含任何合规禁用词**（命中就会被输出护栏整段拒答 —— 那条 FAQ 等于没有）。

---

## 七、常见问题

**Q：启动时报「合规词表资源缺失 / 词数异常」怎么办？**
词表路径解析到 `../backend/src/main/resources/compliance/medical-advice-words.txt`
（相对 `ai-service/`）。若部署时目录布局不同，用 `NIANGLIN_COMPLIANCE_WORDS_PATH` 指到实际位置。

**Q：为什么我配了 `LLM_BASE_URL` 但还是基础模式？**
`LLM_MODEL` 也要配 —— 只配一半会**静默降级**（半套配置比全空更难排查）。

**Q：助手回答里为什么会出现「未填写坐标，距离维度记 0」？**
因为陪诊员坐标目前全是空，距离维度对所有候选人恒为 0。这是**如实反映**「现在没有距离数据」，
必须原样转述 —— 说成「离得近」就是错的（ADR-0016 决策 3）。

**Q：为什么不用向量库？**
领域只有 30 条 FAQ，语义检索的收益远小于依赖成本（embedding 模型下载、CI 变慢、
评审机器装不上），而且语义检索**不可解释**。关键词 + 2-gram 检索说得清楚，也能直接显示给用户看。
