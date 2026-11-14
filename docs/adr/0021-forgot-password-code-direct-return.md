# ADR-0021 · 找回密码去短信化：验证码由后端直接回传、前端自动填充

> **状态**：已接受 · 2026-11-13
> **相关**：[ADR-0011](0011-order-chat.md)（同类：红线边界的取舍记录）· `docs/api/01-auth-user.md` §10 – §11 · `docs/plan/iteration-plan-v4.md` P1-1 · [ADR-0020](0020-assistant-boundary-and-recommendation-guardrail.md)（格式参照）
> **影响范围**：`backend/.../service/AuthService.java` · `backend/.../service/impl/AuthServiceImpl.java` · `backend/.../controller/auth/AuthController.java` · `backend/.../constant/SmsScene.java` · `backend/src/main/resources/application-{dev,prod}.yml` · `frontend/src/views/login/forget.vue` · `frontend/src/api/auth.js` · `POST /api/auth/sms-code` · `backend/sql/tools/e2e_forgot_password.py`

---

## 一、背景

V4 · P1-1 的自助找回密码原本是这样工作的：

```
用户点「获取验证码」→ 后端生成 6 位码存 Redis → 经短信通道（mock / aliyun）发到注册手机号
→ 用户手输验证码 → 后端比对 Redis → 改密 + pwd:version 递增踢全端
```

这套设计的核心是**短信持有证明**：只有真正持有该手机号的人才能收到码，验证码因此是身份凭证。

但项目落地时遇到两个现实约束：

1. **不接入任何短信服务商**。课程演示环境没有阿里云短信预算，也没有签名 / 模板的审核流程；`mock` 通道只打日志、不真实外发 —— 意味着原设计下**没有任何人能收到验证码**，找回密码功能在演示中根本走不通。
2. **功能要保留**。找回密码是 PRD §六 阶段四的验收项（R-G7），登录页入口、`POST /auth/forgot-password` 契约、`pwd:version` 踢全端语义都已落地并有测试覆盖，整体下线不可接受。

两难之下必须改掉「码怎么到用户手里」这一环，其余语义全部保留。

---

## 二、决策

### 决策 1：验证码由后端在响应中明文回传，前端自动填充

`POST /api/auth/sms-code` 的响应从 `data: null` 改为 `data: "123456"`（6 位数字字符串）。
前端 `forget.vue` 拿到后直接填入验证码输入框并提示「验证码已自动填充」，用户照常点「重置密码」。

```json
{ "code": 200, "message": "验证码已生成", "data": "123456" }
```

**这明确放弃了「短信持有证明」**：任何知道手机号的人都能拿到有效验证码并重置该账号密码。
这是刻意接受的取舍（见「三、后果」），不是疏漏。

### 决策 2：删除 `SmsScene.VERIFICATION` 场景，漏服提醒短信完全不动

短信基础设施有两个场景，本次只砍找回密码这一个：

| 场景 | 处置 | 理由 |
|---|---|---|
| `VERIFICATION`（找回密码验证码） | **删除** —— 枚举值、`AuthServiceImpl` 发送调用、`application-dev/prod.yml` 模板配置、相关测试用例一并移除 | 找回密码已不发短信，留着就是死配置；`provider=aliyun` 启动校验还要求它非空，会变成无意义的部署阻塞 |
| `MISSED_DOSE`（漏服站外提醒） | **完全保留** —— `SmsSender` / `AliyunSmsSender` / `SmsReminderService` / `sms_send_log` / `SmsRetryScheduler` 一律不动 | 漏服提醒是 V4 · P1-3 独立功能，家属真实需要站外触达；它不依赖找回密码 |

删枚举值后 `SmsProperties#validate` 遍历 `SmsScene.values()` 自动收敛为只要求 `missed-dose` 模板 —— **prod 启动校验从「两个模板都非空」变为「只校验 `ALIYUN_SMS_TEMPLATE_CODE_MISSED_DOSE`」**，`ALIYUN_SMS_TEMPLATE_CODE_VERIFICATION` 环境变量作废。

### 决策 3：与通道无关的语义全部原样保留

以下规则**一条不改**（它们本来就与「码怎么送达」无关）：

- 三层频控（同手机号 60 秒 1 次 / 每 IP 每小时 20 次 / 同手机号每天 10 次，超限 `1009`）；
- 验证码 5 分钟 Redis TTL、错 5 次作废、验证通过即一次性消费（`1008`）；
- 改密后 `pwd:version` 递增、该账号全部已签发令牌立即失效；
- 手机号不存在时 `forgot-password` 与成功路径**完全同构**（不真正改密）；
- BCrypt 存储、验证码不落日志、手机号日志脱敏（`MaskUtil`）。

防枚举同构在新设计下**自动成立**：不再有「发送成功 / 失败」「发了 / 没发」的差异，人人拿得到码，响应结构天然一致。

### 决策 4：单独记录本 ADR

安全姿态从「短信验证身份」降级为「仅频控」是**不可逆地削弱了认证强度**。契约文档 §10 只能描述「是什么」，说不清「为什么曾经不是这样、以及二期如何回退」——所以按仓库 ADR 惯例单独立档。

---

## 三、后果

### 接受的风险

- **凭手机号即可重置任意账号密码**。缓解手段只剩三层频控（暴力枚举 6 位码在 10 次/天/号、20 次/小时/IP 下不现实），以及 5 分钟 TTL。这比原设计（需持有手机）弱一个量级，**仅适用于课程演示环境**。
- **验证码在 HTTP 响应中明文传输**。要求部署时走 HTTPS（现有部署约束已覆盖）；日志侧仍不打码值。

### 正面收益

- 找回密码在演示环境**真正可用**（原来收不到短信，功能等于死的）；
- 少一个短信场景 → `SmsProperties` 启动校验、dev/prod 模板配置、路由测试全部收敛，`ALIYUN_SMS_TEMPLATE_CODE_VERIFICATION` 环境变量作废；
- e2e 脚本不再依赖「mock 通道 + 读 Redis」这条测试旁路，直接断言响应契约。

### 二期若接入真短信通道，如何回退

1. `SmsScene` 加回 `VERIFICATION("verification", "ALIYUN_SMS_TEMPLATE_CODE_VERIFICATION")`；
2. `AuthServiceImpl#sendSmsCode` 恢复 `smsSender.send(...)` 调用（**不再把码放进 `data`**，`AuthController` 改回 `Result<Void>` + 旧文案）；
3. `application-dev/prod.yml` 补回 `verification` 模板行，`TestSmsProperties` / `SmsTemplateRouterTest` 恢复对应用例；
4. `forget.vue` 去掉自动填充，回到「手输 + 倒计时」；
5. 同步 `docs/api/01-auth-user.md` §10 与本 ADR 状态（改为「已取代 · 见 ADR-00xx」）。

**回退的前提是拿到真实短信服务商预算与签名 / 模板审核** —— 在那之前，本 ADR 的安全口径是当前基线，不要「顺手」把码塞回 Redis 日志或前端持久化存储（本地存储写验证码同样违反隐私最小化红线）。
