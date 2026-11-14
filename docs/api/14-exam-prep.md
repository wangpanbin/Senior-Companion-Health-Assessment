# 14 · 检查准备提醒（V5 · B）

> **模块定位**：把「听不懂检查前要做什么」变成**静态、可查、带来源**的就医须知转述。
>
> **合规定位（红线-1 的边界论证）**：本模块内容为**医院公开检查须知的静态转述**，
> 依据《医疗广告认定指南》（2025）第 5 条「公开诊疗流程等就医必需信息不构成广告」、
> 第 7 条「合规科普不构成广告」——**不推介、不承诺疗效、逐条带来源 + 免责声明**。
> 内容**不由 LLM 生成**（禁食时长等准备要求属医疗范畴，模型生成 = 医疗建议风险），
> 助手侧仅以 FAQ 静态条目应答（`ai-service/knowledge/faq.json`，
> 词表与锚点受 `tools/check_faq_anchors.py` 门禁约束）。
>
> **数据真源**：`sys_dict` 新增类型 `EXAM_PREP`（`V12__exam_prep_dict.sql` 种子）。

---

## 1. 检查准备须知查询

`GET /api/exam-prep`　权限：已登录

按类型返回全部启用的检查准备条目（静态字典，无分页、无参数）。

### 响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "code": "FASTING_BLOOD",
      "name": "空腹血糖 / 静脉采血",
      "requirement": "空腹 8–12 小时",
      "remark": "检查前空腹 8–12 小时，期间可少量饮白开水；来源：……；就医须知转述，不构成医疗建议，以就诊医院要求为准"
    }
  ]
}
```

| 字段 | 来源列 | 说明 |
|---|---|---|
| `code` | `dict_code` | 类型内唯一 |
| `name` | `dict_label` | 检查项目名（≤100） |
| `requirement` | `dict_value` | 一句话核心要求（≤100） |
| `remark` | `remark` | 完整转述 + **来源 URL** + 免责声明（≤255） |

> **错误场景**：无（静态字典查询，空结果返回 `data: []`）。

---

## 2. 就诊前一天站内信提醒（定时任务，非 HTTP）

- **触发**：**每小时**扫描（`ExamPrepScheduler`，沿用 `MedicationScheduler` 三件套：
  `@Scheduled` cron + `RedisLockUtil` 分布式锁 + `@ConditionalOnProperty` 开关 + 方法内吞异常）。
  评审定案：不用「每天 09:00」—— 09:00 后当天下单、次日就诊的单永远进不了每日一次的窗口，
  发送失败也需要同日重试机会；幂等键保证高频重扫不重发（扫描走 `status + visit_time` 索引，成本可忽略）。
- **对象**：`visit_time` 落在**明天**的订单（`ACCEPTED` / `IN_SERVICE` / `PENDING` 均算 ——
  还没接单的单也要提醒家属做准备）。
- **收件人**：下单家属 + 就诊老人本人（ELDER 账号）；`bizId=orderId`。
- **内容（按 audience 分流）**：固定事实句「明天（{visitDate}）{hospital}（{department}）有就诊安排」
  + 尾句 —— **家属侧**含「可在智能助手中查询「检查准备」」（助手仅 FAMILY 可用），
  **老人侧**落「具体以就诊医院要求为准」（不给老人一个进不去的入口）。
  模板出站前两侧各自过 `ComplianceCheckUtil` 词表（命中即拦截，宁可不发）。
  ⚠️ 本规格原稿承诺的「字典摘要 + 页面入口」随评审收敛为现状口径：
  当前无页面消费 `listExamPrep()`，入口为后续迭代（api 模块已就位）。
- **幂等与补偿**：Redis `exam-prep:remind:{orderId}:{visitDate}` SET NX（TTL 48 小时），
  同一订单同一就诊日只提醒一次；**键先占后发，发送失败立即释放** ——
  否则一次 DB 抖动就让这一单在当天窗口内永久丢失，释放后下一轮重扫即可补发。
  站内信**不受静默时段约束**（静默只管短信，本模块不发短信）。
- **失败隔离**：单订单发送失败只记日志，不中断扫描。

---

## 3. 数据模型与内容（sys_dict · EXAM_PREP）

`V12__exam_prep_dict.sql` 种子 6 条（id 段 44901+，`ON DUPLICATE KEY UPDATE` 幂等，
不覆盖运营后续调整）：

| id | dict_code | dict_label | dict_value（核心要求） |
|---|---|---|---|
| 44901 | `FASTING_BLOOD` | 空腹血糖 / 静脉采血 | 空腹 8–12 小时 |
| 44902 | `ABDOMINAL_US` | 腹部 B 超 | 空腹 8–12 小时，盆腔项目需憋尿 |
| 44903 | `GASTROSCOPY` | 胃镜 | 空腹 ≥8 小时，携带既往检查资料 |
| 44904 | `COLONOSCOPY` | 肠镜 | 检查前 1–3 天低渣饮食，方案以就诊医院告知为准 |
| 44905 | `URINE_TEST` | 尿常规 | 留取中段尿，避开月经期 |
| 44906 | `CHEST_CT` | 胸部 CT | 无空腹要求，去除金属饰品 |

- 每条 `remark` 均含**来源 URL**（医院公开检查须知）+ 免责声明后缀
  「就医须知转述，不构成医疗建议，以就诊医院要求为准」。
- **内容由 AI起草占位、待人工审校**（2026-11-14 交付口径）；替换内容时
  保持 remark 的「来源 + 免责」两段结构，改完重跑 `python tools/check_secrets.py`。
- 来源样例：厦门大学附属中山医院检查须知 `https://www.xmuzsh.cn/ksjs/csk/jcqzb.htm`；
  上海市第七人民医院体检须知 `https://www.hosno7.com/shsqyy/tjxz/9070.jhtml`。

### FAQ 对接（助手降级路径）

`ai-service/knowledge/faq.json` 三条（`faq-exam-prep-01/02/03`，intent 均为 `FAQ_PLATFORM`，
不新增 intent、不改 `ai-service` 代码与依赖）；问法池 `faq-question-pool.json` 同步 +15 条，
受 `tools/check_faq_anchors.py` 与 `ai-service/tests/test_faq_coverage.py` 双门禁。

---

## 4. 合规红线（本模块专属）

1. **不由 LLM 生成内容**：字典与 FAQ 均为静态文本，助手只做检索应答（`FAQ_PLATFORM`）。
2. **逐条带来源 + 免责**：`remark` 缺来源或缺免责声明的条目视为不合格种子。
3. **禁词**：全部文本不得命中 `backend/src/main/resources/compliance/medical-advice-words.txt`
   （`check_faq_anchors.py` 第 ⑦ 项自动校验 FAQ；站内信发送前过 `ComplianceCheckUtil`）。
4. **不发短信**：本模块只有站内信，绕开短信频控与静默的全部复杂度。

---

## 5. 验收标准（V5 · B）

- [ ] `GET /api/exam-prep` 返回 6 条启用条目，`remark` 均含来源与免责声明
- [ ] 明天有订单 → 家属与老人各收到 1 条站内信；同日重复扫描不重发（Redis NX）
- [ ] 无明天订单 → 扫描零发送；单订单失败不影响整批
- [ ] 全文命中医疗建议禁用词 → `check_faq_anchors.py` 与发送前词表双重拦截
- [ ] `python tools/check_faq_anchors.py`、`python -m pytest ai-service/tests -q` 全绿
- [ ] Knife4j 可见 `GET /api/exam-prep` 契约
