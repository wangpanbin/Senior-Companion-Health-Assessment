# 13 家属智能助手

> 覆盖模块：**M14 家属智能助手**
> 负责人：B（后端主力）+ A（前端主力）
> 归属迭代：V3.7（2026-10-21 开工）
> 前置依赖：M4 陪诊订单与状态机（候选推荐）　M3 用户与档案（老人绑定）　全局约定见 [README.md](./README.md)
> 状态：**后端 + Python 服务 + 前端页面已实现**（Java 单测 50 条 · Python 125 条 · 前端 14 条）
> 决策依据：[ADR-0020](../adr/0020-assistant-boundary-and-recommendation-guardrail.md) · 工程契约：[V3.7-assistant-spec.md](../spec/V3.7-assistant-spec.md)

> ⚠️ **本模块是项目里第一个也是唯一一个 AI 能力模块**。
> 它的能力边界不是「产品口味」，而是**合规要求** ——
> `plans/迭代计划.md` §60「AI 使用边界」把「订单智能推荐 / 陪诊员匹配」列在**谨慎使用**档，
> 把「疾病诊断 / 疾病预测 / 治疗建议 / 药物推荐 / 药物剂量调整 / 医疗风险诊断」列在**禁止**档。
> 本模块落在谨慎使用档，**不触碰**禁止档。

---

## 〇、本模块的安全边界（先读这一段）

### 0.1 三段式权限

| 层 | 由谁承担 | 回答的问题 | 失败码 |
|---|---|---|---|
| ① 认证 | `JwtAuthenticationFilter` | 你有没有登录 | HTTP `401` |
| ② 角色 | `@PreAuthorize("hasRole('FAMILY')")` | 你是什么角色 | HTTP `403` |
| ③ **归属** | `OrderService#detail` / `ElderService#detail` | **这一单 / 这个老人跟你有没有关系** | `3004` / `2006` |

第 ③ 层由**被复用的既有服务**承担，不另写一套：
`orderId` 传了但不是本人下的 → `OrderService#detail` 抛 `3004`；
`elderId` 传了但不是本人绑定的 → `ElderService#detail` 抛 `2006`。

> 📌 老人账号（ELDER）**完全没有**本模块的入口：
> 路由 `meta.roles: ['FAMILY']` + 服务端 `@PreAuthorize` + `meta.elderlyHidden: true` 三处一致。
> 老人只读是项目硬规则，助手是家属代问的工具。

### 0.2 能力边界：能答什么、必须拒答什么

| 类别 | 例子 | 行为 |
|---|---|---|
| ✅ 平台服务规则 | 下单流程、费用口径、取消规则、陪诊员资质与接单机制、评价与投诉入口、订单状态 | 正常回答，**必须给依据引用**（`sources`） |
| ✅ 解释推荐结果 | 「这几位陪诊员为什么合适」 | 只转述规则引擎已算好的 `reasons` / `mismatches` / `notes`，**不改排序、不改分** |
| ⛔ 医疗判断 | 诊断、病情严重程度、该吃什么药、剂量、疗效、要不要停药 | **固定拒答** + 引导线下就医（服务端拦截，不经大模型） |
| ⛔ 违规内容 | 辱骂、平台外交易 / 私下联系 | `10003` |

**为什么拒答在服务端做**：前端做就等于「前端隐藏按钮」那一类 —— 换个客户端（脚本、篡改过的 App）照样能拿到医疗建议。合规红线必须在**服务端**拦。

### 0.3 三条硬边界

| # | 边界 | 违反后果 |
|---|---|---|
| B1 | **不改排序**：不得重排候选、不得改分、不得补充 `CompanionMatchVO` 之外的事实 | 违反 ADR-0016「不允许出现没有理由的分数」 |
| B2 | **不建订单**：只回填前端下单草稿，提交由用户在下单流程确认 | `PENDING` 订单会出现在陪诊员大厅，污染真实供给 |
| B3 | **不碰医疗** | 违反 `AGENTS.md` §0.1-1 与 `plans/迭代计划.md` §61.3 |

### 0.4 令牌与数据流向

```
浏览器 ──Bearer JWT──► Java 门面 ──自包含 payload──► Python 服务
                          │                            │
                          │                            └─ 不查库、不调 Java、
                          │                               不持有任何令牌
                          └─ Redis 限流计数
```

- **Python 服务不持有用户令牌**：门面把令牌解析成 `userId` / 上下文后，只传业务字段过去。
  避免令牌在第二个服务里二次流转（隐私最小化）。
- **对话历史不落库**：历史由浏览器 `sessionStorage` 保存，随请求带上；后端不建会话表、不落库。
  本版**无数据库迁移**。

---

### 0.5 三份共享合规资产

合规红线在代码层只有一个落点，且**Java 与 Python 读同一份文件**：

| 文件 | 内容 | Java 侧 | Python 侧 |
|---|---|---|---|
| `backend/src/main/resources/compliance/medical-advice-words.txt` | 医疗建议禁用词（17 词） | `ComplianceCheckUtil` | `app/paths.py` |
| `backend/src/main/resources/compliance/sensitive-words.txt` | 辱骂 / 违规引流词（14 词） | `SensitiveWordUtil` | `app/paths.py` |
| `backend/src/main/resources/compliance/refusal-template.txt` | 固定拒答模板 | `ComplianceCheckUtil#refusalTemplate()` | `app/guard.py` |

- **读不到 / 读空 / 读错 → 两侧都启动失败**（词表读空 = 护栏静默失效 = 所有医疗回答放行）。
- 拒答模板也共享，是因为**它本身不得含任何禁用词** —— 两侧各写一份，早晚有一侧把自己拦下。
- 生产部署时把**同一份文件**挂给两个服务。

---

## 一、数据模型

### 1.1 请求（`AssistantChatDTO`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `question` | String | ✓ | 家属的问题，最长 500 字符 |
| `history` | List\<AssistantHistoryTurn\> | | 历史消息，最多 **12 条**（1 轮 = user 1 条 + assistant 1 条 = 2 条；6 轮 = 12 条） |
| `orderId` | Long | | 当前订单 ID；给了则装配该单上下文与候选陪诊员 |
| `elderId` | Long | | 就诊老人档案 ID；给了则校验归属 |

`AssistantHistoryTurn`：`role`（`user` / `assistant`，≤20 字符）、`content`（≤1000 字符）。

> ⚠️ **没有 `userId` 字段**：身份一律取自令牌。带 `userId` 等于允许家属以别人的名义提问，
> 而提问会带出该家属的老人列表与订单摘要。同一纪律见 `03-order.md` 的 `familyId` 说明。

### 1.2 响应（`AssistantChatVO`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `answer` | String | 自然语言回答（**已过合规输出护栏**） |
| `intent` | String | 命中的意图，见下表 |
| `actions` | List\<AssistantActionVO\> | 结构化动作 |
| `sources` | List\<String\> | **答案的依据引用**，如 `docs/api/03-order.md §13` |
| `degraded` | Boolean | 是否走了降级路径（未接入大模型，直接给标准答案原文） |

`intent` 取值：

| 值 | 含义 |
|---|---|
| `FAQ_PLATFORM` | 命中平台规则问答 |
| `RECOMMEND_COMPANION` | 推荐陪诊员 / 解释候选 |
| `ORDER_STATUS` | 订单状态与进度 |
| `REFUSE_MEDICAL` | 医疗类提问，已固定拒答 |
| `UNKNOWN` | 没听懂，返回澄清话术 |

`AssistantActionVO`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | String | `FILL_ORDER_DRAFT` / `NAVIGATE` |
| `payload` | Object | 动作参数，**只含助手确实抽取到的字段** |

`FILL_ORDER_DRAFT` 的 payload 键：`elderId` / `elderName` / `hospital` / `address` / `department` / `visitTime`。
`elderId` **只在当前家属恰好只有一位绑定老人时**才带（多位时由用户选，猜错人比不填更糟）。

---

## 二、接口列表

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | `POST` | `/api/assistant/chat` | **FAMILY** | 智能助手问答 |

---

## 三、接口详情

### 3.1 智能助手问答

`POST /api/assistant/chat`　权限：**FAMILY**（ELDER / COMPANION / ADMIN 一律 `403`）

#### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `question` | String | 是 | 家属的问题，≤500 字符 |
| `history` | Array | 否 | 历史消息，≤**12 条**（6 轮）；只透传不落库 |
| `orderId` | Long | 否 | 当前订单 ID |
| `elderId` | Long | 否 | 就诊老人档案 ID |

```json
{
  "question": "下周三去省人民医院心内科，帮我看看有没有合适的陪诊员",
  "history": [
    { "role": "user", "content": "陪诊费多少钱" },
    { "role": "assistant", "content": "工作日白天 128.00 元，夜间或周末 158.00 元。" }
  ],
  "orderId": 1001,
  "elderId": 401
}
```

#### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "answer": "按你的就诊信息，推荐了以下陪诊员（推荐仅供参考，最终由陪诊员本人确认接单）：\n\n1. 李*军 —— 匹配度 86 分\n   命中理由：同服务区域；服务过该科室 12 单\n   降级说明：未填写坐标，距离维度记 0",
    "intent": "RECOMMEND_COMPANION",
    "actions": [
      {
        "type": "FILL_ORDER_DRAFT",
        "payload": {
          "elderId": 401,
          "elderName": "张*",
          "hospital": "海南省人民医院",
          "address": "海南省海口市秀英区秀华路19号",
          "department": "心血管内科",
          "visitTime": "2026-10-28 09:30:00"
        }
      },
      { "type": "NAVIGATE", "payload": { "path": "/family/order/step2" } }
    ],
    "sources": [
      "docs/api/03-order.md §13",
      "docs/api/03-order.md §1"
    ],
    "degraded": false
  }
}
```

| 字段 | 说明 |
|---|---|
| `answer` | 回答正文。`answer` 里的换行是有意义的（候选的三段式结构靠换行分段） |
| `sources` | **必须非空**（`FAQ_PLATFORM` / `RECOMMEND_COMPANION` / `ORDER_STATUS` 三类）。没有依据的回答在评审现场站不住 |
| `degraded` | `true` = 未接入大模型，直接给标准答案原文。**注意**：`REFUSE_MEDICAL` 的 `degraded` 是 `false` —— 拒答是正确路径，不是降级 |
| `actions` | 命中 `FILL_ORDER_DRAFT` 时前端展示「已为你填好下单信息」卡片；**用户点按钮后**才写草稿并跳转 |

#### 错误场景

| code | 触发条件 |
|---|---|
| `400` | `question` 为空 / 超长；`history` 超 6 轮；历史项字段非法 |
| `401` | 未登录 |
| `403` | 非 FAMILY 角色（ELDER / COMPANION / ADMIN） |
| `10001` | 提问过于频繁（超过 `nianglin.assistant.rate-limit-per-minute`，默认每分钟 10 次） |
| `10002` | 助手服务不可用**且**本地 FAQ 兜底也失败 |
| `10003` | 问题内容命中辱骂 / 违规引流词 |
| `2006` | `elderId` 不是当前家属绑定的老人 |
| `3004` | `orderId` 不是当前家属下的订单 |

#### 实现要点

- **候选陪诊员原样取自** `GET /api/order/{id}/recommend-companions`（`OrderRecommendVO`），
  **零改造、零重算**。本模块**不重排、不改分、不补充**任何字段。
- **`notes` 必须原样转述**。陪诊员坐标目前全是空（ADR-0016「待观察」），
  所以距离维度对所有候选人恒为 0，`notes` 里会出现「未填写坐标，距离维度记 0」。
  把它说成「距离近」就是 ADR-0016 明令禁止的那类错误 —— 单测锁死了这条。
- **降级路径**：Python 服务不可用时，Java 门面本地读**同一份** FAQ 文件回答，
  `degraded = true`。**Python 挂了助手不会整体不可用。**
- **限流**：Redis 按分钟分桶计数。**Redis 不可用时放行** —— 限流是成本保护措施，
  不是安全边界；Redis 挂了不该让助手整体不可用。
- **`answer` 必须过输出护栏**：命中医疗建议类禁用词 → 整段替换为固定拒答模板；
  命中「已指派 / 已分配 / 自动派单」→ 词语级纠正并追加免责句。
  **本地兜底路径同样要过这道护栏**（Java 侧 `AssistantServiceImpl#guardAnswer`）——
  否则「Python 挂了就没人管」恰好成了绕过护栏的路径。

---

## 四、与前端页面的对应

| 前端 | 位置 | 说明 |
|---|---|---|
| API 封装 | `frontend/src/api/assistant.js` | `chatAssistant(payload)`，**按请求覆写超时 60s**（不改 `request.js` 的全局 15s） |
| 页面 | `frontend/src/views/family/assistant.vue` | 独立页面；顶部常驻免责声明，每条回答下方再带一行 |
| 路由 | `/family/assistant` | `roles: ['FAMILY']` · `module: 'M14'` · `code: 'M-33'` · `elderlyHidden: true` |
| 入口 1 | 家属工作台快捷入口 | 6 格中的第 6 格 |
| 入口 2 | 订单详情页「问助手」 | 带上 `?orderId=`，助手才能解释这一单的候选 |

**助手永不自动下单**：`FILL_ORDER_DRAFT` 只把值填进 `orderDraft` store，
提交由用户在下单三步流程里确认。

---

## 五、已知限制（一期）

1. **推荐是订单锚定的**。`MatchTarget` 的七个字段（`orderId` / `regionCode` / `address` /
   `visitTime` / `longitude` / `latitude` / `department`）全部来自订单 ——
   没有订单就没有科室、没有就诊时间、没有坐标、没有区域码，**五维里塌陷四维**。
   所以「还没下单就想看陪诊员」这件事，本版**不支持**，助手会引导用户先成单。
2. **家属端没有可浏览的陪诊员列表**。`/api/order/hall` 是陪诊员专属，
   家属能看到的陪诊员信息只有订单维度的 Top N 候选与单个公开资料页。
3. **距离维度当前恒为 0**。陪诊员坐标全是空（结构化列刚加，还没数据），
   这是**如实反映「现在没有距离数据」**，不是缺陷。等陪诊员开始填坐标，排序自动生效。
4. **FAQ 时效性靠人工维护**。FAQ 逐条标注了依据引用（`source` + `anchor`），
   并由 `tools/check_faq_anchors.py` 校验锚点真实存在 —— 但**只校验标题行**，
   标题下的正文数字（如服务费 128.00）不在校验范围内。
5. **限流阈值 10 次/分钟是拍板值**，无真实流量数据支撑。
6. **助手不查库**，工具数据是本次请求的快照。
7. **降级模式下回答是 FAQ 原文**，不做口语化润色 —— 读起来像文档而不是聊天。
8. **未接模型时不报错**。`LLM_BASE_URL` 没配是**正常模式**（基础模式），
   不是故障；但也意味着演示时看不到大模型的措辞能力。

---

## 六、变更记录

| 版本 | 日期 | 变更内容 | 变更人 |
|---|---|---|---|
| v1.0.0 | 2026-10-21 | V3.7 首版：新增 `POST /api/assistant/chat`（仅 FAMILY）；新开 10xxx 错误码段（`10001` / `10002` / `10003`）；独立 `ai-service` Python 服务（LangChain + FastAPI，8081）；合规词表抽出为 Java / Python 共享资源文件 | — |
