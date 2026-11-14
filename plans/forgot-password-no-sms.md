# 找回密码去短信化 —— 后端直接下发验证码、前端自动填充

## Context

V4 · P1-1 的自助找回密码（`POST /auth/sms-code` → `POST /auth/forgot-password`）目前依赖短信通道（mock / aliyun）把 6 位验证码发到注册手机号。**用户决定不再接入短信服务提供商**：改为后端生成验证码后直接在响应里回传，前端拿到后自动填充到验证码输入框，用户照常点「重置密码」。

已确认的三个决策：

1. **安全口径**：接受「验证码明文回传」——任何知道手机号的人都能重置该账号密码；**保留全部三层频控**（60 秒 / 每 IP 每小时 20 次 / 每号每天 10 次）与 5 分钟 TTL、错 5 次作废等规则，并在契约文档与 ADR 中写明该口径变化。
2. **短信场景**：**删除 `SmsScene.VERIFICATION` 场景**（含 prod 启动校验中的 verification 模板要求）；**漏服提醒（missed-dose）短信基础设施完全不动**（`SmsSender` / `AliyunSmsSender` / `SmsReminderService` / `sms_send_log` / `SmsRetryScheduler` 全保留）。
3. **前端交互**：点「获取验证码」→ 后端返回码 → **自动填入 `smsCode` 输入框**（提示「验证码已自动填充」）→ 用户手动点「重置密码」。保留 60 秒倒计时（对应后端 60 秒频控）。

另确认：**单独写一篇 ADR**（`docs/adr/0021-forgot-password-code-direct-return.md`）存档这次安全姿态变更。

### 现状勘察结论

- 发码逻辑：`AuthServiceImpl#sendSmsCode`（`backend/src/main/java/org/company/nianglin/service/impl/AuthServiceImpl.java:386`）——生成 6 位码 → Redis `sms:reset:{phone}`（TTL 5 分钟）→ `smsSender.send(...)` 走通道；未注册手机号生成码但不发送。
- 控制器：`AuthController#sendSmsCode` 返回 `Result<Void>`，`data` 恒为 null，文案常量 `SMS_SENT_MESSAGE = "若该手机号已注册，验证码将发送至该号码"`。
- **找回密码的其余全部语义与通道无关，原样保留**：三层频控（1009）、验证码错误/过期/复用/错满 5 次（1008）、一次性消费、`pwd:version` 踢全端、防枚举同构响应（改为「人人拿到码」后同构自动成立）。
- `verification` 场景触点：`SmsScene.java`、`AuthServiceImpl:405`、`application-dev.yml:122`、`application-prod.yml:84`、`SmsProperties#validate`（遍历 `SmsScene.values()`，删枚举值后自动只要求 missed-dose）、`TestSmsProperties`、`SmsTemplateRouterTest`、`SmsSenderTest:48`、`docs/api/07-message.md:416`、`backend/sql/tools/README.md:290`。
- 受影响测试：`AuthForgotPasswordTest`（① 断言 `smsSender.send` 被调用；② 断言已注册/未注册响应**字节级相等** —— 响应里带上随机码后字节级相等必然破，需改为结构等价断言）、`SmsTemplateRouterTest`。
- e2e：`backend/sql/tools/e2e_forgot_password.py`（从 Redis 读码 + 断言 `SMS_SENT_MESSAGE`）。
- 前端：`frontend/src/views/login/forget.vue`（`sendCode` 只启动倒计时）、`frontend/src/api/auth.js:50`。
- 契约文档：`docs/api/01-auth-user.md` §10（防枚举口径 / 响应体 / 实现要点全部要改）、`docs/api/README.md`（错误码措辞 + 版本表）、`docs/user-guide.md:29,239`。

### 不改的东西（红线自查）

- **不改** `V8__sms_notification.sql`（Flyway 已应用，改 checksum 会炸；DB 列注释里的 `verification` 字样是历史，留着）。
- **不改** `SecurityConfig` 白名单、`SmsCodeDTO`、`ForgotPasswordDTO`、`RedisKeyConstants`、`ResultCode`（1008/1009 语义不变）。
- **不碰** 漏服提醒全链路；**不碰** `docs/plan/plan.md` / `README.md` / `pom.xml` / `package.json`。
- 历史计划文档（`docs/plan/iteration-plan-v4.md`、`docs/prd/功能拓展规划.md` R-G7 行）是当时状态的记录 —— R-G7 状态行**顺手更新措辞**（标注「V4 后改为验证码直发回传，见 ADR-0021」），iteration-plan 不动。

## Approach

### 后端：`sendSmsCode` 改为返回验证码字符串

1. **`AuthService` 接口**：`void sendSmsCode(...)` → `String sendSmsCode(String phone, String clientIp)`，javadoc 改写（不再提 `SmsSender`）。
2. **`AuthServiceImpl#sendSmsCode`**：
   - 保留：`normalizePhone`、`checkSendFrequency`（三层频控）、生成 6 位码、写 Redis `sms:reset:{phone}`、删失败计数键。
   - 删除：`findUserByPhone` 调用、`smsSender.send` try/catch、`SmsScene.VERIFICATION` 解析；随之删除字段 `smsSender` / `smsTemplateRouter` 及 `SmsScene` / `SmsSender` / `SmsTemplateRouter` / `Map`（若无他用）import。
   - 未注册手机号不再需要区分处理 —— **人人都生成、人人拿得到**，同构响应自动成立。
   - 返回 `code`；日志不打码值（沿用「验证码不落日志」红线）。
3. **`AuthController#sendSmsCode`**：返回类型 `Result<Void>` → `Result<String>`，`data` 即 6 位验证码；文案常量 `SMS_SENT_MESSAGE` 改为 `"验证码已生成"`（旧文案「将发送至该号码」已失实）；`@Operation` 描述同步改写（删「短信通道」「防枚举侧信道」措辞，写明「验证码直接回传，频控仍生效」）。
4. **删 `SmsScene.VERIFICATION`** 枚举常量（`MISSED_DOSE` 留下，javadoc 同步），`SmsProperties#validate` 的模板循环与 javadoc 自动收敛为只要求 missed-dose。
5. **配置**：`application-prod.yml` 删 `verification:` 行 + 更新注释（「两个场景模板」→「missed-dose」）；`application-dev.yml` 删 `verification: SMS_DEV_VERIFICATION`；`application.yml:276` 注释同步。

### 前端：自动填充

6. **`forget.vue#sendCode`**：
   ```js
   const smsCode = await sendSmsCode({ phone: form.value.phone })
   form.value.smsCode = smsCode || ''        // 拦截器已拆好 envelope，data 就是码
   ElMessage.success('验证码已自动填充')
   startCountdown()
   ```
   - 改页头注释块、`NlNoticeBar` 文案（「验证码只发送到注册时填写的手机号」→「验证码由系统生成并自动填充」）、底部 fallback 卡片（去掉「收不到验证码」措辞）。
   - 1009 仍只启动倒计时（现状逻辑不动）。
7. **`api/auth.js#sendSmsCode`**：注释改为 `→ data 为 6 位验证码字符串（后端直接回传，不经短信通道）`。

### 测试与 e2e

8. **`AuthForgotPasswordTest`**：
   - ① 改断言：响应 `$.data` 是 6 位数字且与 Redis 值一致；`verify(smsSender, never()).send(...)`（保留 `@MockBean` 作回归护栏：找回密码绝不再调通道）。
   - ② 「字节级同构」改为**结构等价**：已注册/未注册两次响应均为 200 + 同一 message + 各自 6 位 `data`（随机码不同，不能比字节）。
   - `SMS_SENT_MESSAGE` 常量改新文案；`sendAndReadCode` 可直接读响应（顺带验证契约），Redis 断言保留在 ① 里锁 TTL。
   - 其余用例（1008/1009/踢全端/公开性）不受影响。
9. **`SmsTemplateRouterTest`**：用例① 改为只验 missed-dose；用例③（缺 verification 模板 fail-fast）改为缺 missed-dose fail-fast（与 `SmsSenderTest:48` 同语义，保留其一直接断言信息含环境变量）；`TestSmsProperties` 删 verification 假 Code。
10. **`e2e_forgot_password.py`**：`SMS_SENT_MESSAGE` 更新；B1 增加 `data.smsCode` 6 位断言（可继续读 Redis 交叉核对）；B2 同构改为 message 相等 + 双方都有 6 位 data。

### 文档同步

11. **ADR**：新建 `docs/adr/0021-forgot-password-code-direct-return.md`（状态/相关/影响范围头 + 背景、决策、后果三段式，对齐 ADR-0020 格式）——重点写清：安全姿态从「短信持有证明」降级为「仅频控」，凭手机号即可重置密码的取舍理由（课程演示环境、无短信服务商预算），以及「若二期接入真短信通道如何回退」。
12. **`docs/api/01-auth-user.md` §10**：响应体改 `data: "123456"`；「防枚举口径」段重写（同构仍成立但理由变了：不再有「发送成功/失败」差异，人人拿得到码）；「实现要点」删短信模板/通道条目；顶部表格第 10 行描述改「获取找回密码验证码（直接回传）」；引 ADR-0021。§11 的 `smsCode` 字段说明改为「由 §10 直接回传获得」。
13. **`docs/api/README.md`**：错误码 1008/1009 描述里「短信验证码」→「找回密码验证码」；版本表新增一行（v0.12.0：找回密码去短信化，`sms-code` 改为回传验证码，引用 ADR-0021）。
14. **`docs/api/07-message.md` §9.7**：模板表删 verification 行；「两个模板 Code 都必须非空」改为只 missed-dose。
15. **`backend/sql/tools/README.md:290`**：同上，prod 启动校验只要求 `ALIYUN_SMS_TEMPLATE_CODE_MISSED_DOSE`。
16. **`docs/user-guide.md:29-30,239`**：「手机号 + 短信验证码」→「点获取验证码后自动填充」；保留 5 分钟/错 5 次/1 分钟频控描述。

## Files to modify

| 文件 | 改动 |
|---|---|
| `backend/src/main/java/org/company/nianglin/service/AuthService.java` | `sendSmsCode` 返回 `String`，javadoc 改写 |
| `backend/src/main/java/org/company/nianglin/service/impl/AuthServiceImpl.java` | 删发送调用与相关字段/import，返回码 |
| `backend/src/main/java/org/company/nianglin/controller/auth/AuthController.java` | `Result<String>`、新文案、`@Operation` 改写 |
| `backend/src/main/java/org/company/nianglin/constant/SmsScene.java` | 删 `VERIFICATION` |
| `backend/src/main/resources/application-{dev,prod}.yml`、`application.yml` | 删 verification 模板配置与注释 |
| `backend/src/test/.../AuthForgotPasswordTest.java` | 断言重写（never/结构同构/新文案） |
| `backend/src/test/.../sms/SmsTemplateRouterTest.java`、`support/TestSmsProperties.java` | 删 verification 场景用例 |
| `backend/sql/tools/e2e_forgot_password.py` | 新文案 + `data.smsCode` 断言 |
| `frontend/src/views/login/forget.vue` | 自动填充 + 文案 |
| `frontend/src/api/auth.js` | 注释 |
| `docs/adr/0021-forgot-password-code-direct-return.md` | **新建** ADR |
| `docs/api/01-auth-user.md`、`docs/api/README.md`、`docs/api/07-message.md` | 契约同步 |
| `docs/user-guide.md`、`backend/sql/tools/README.md`、`docs/prd/功能拓展规划.md` | 措辞同步 |

## Reuse

- 频控 / 校验 / 消费逻辑全部复用现有 `checkSendFrequency`、`recordWrongCode`、`RedisKeyConstants.smsReset*`，**零新增**。
- 拦截器 `request.js` 已把 envelope 拆成 `data` 直接返回 —— 前端一行拿到码，无需新工具函数。
- `MaskUtil.phone` 继续用于日志脱敏；`SecureRandom` 生成逻辑不动。

## Steps

- [x] 0. 按 AGENTS §0.2 开分支 `feature/auth-forgot-password-no-sms`（提交前需用户确认）
- [x] 1. 后端：`AuthService` / `AuthServiceImpl` / `AuthController` 改返回码 + 删发送调用
- [x] 2. 后端：删 `SmsScene.VERIFICATION` + 三份 yml 配置收敛
- [x] 3. 后端测试：`AuthForgotPasswordTest`、`SmsTemplateRouterTest`、`TestSmsProperties` 重写
- [x] 4. 前端：`forget.vue` 自动填充 + 文案，`api/auth.js` 注释
- [x] 5. e2e：`e2e_forgot_password.py` 更新
- [x] 6. 文档：ADR-0021 新建 → `01-auth-user.md` §10 → `README.md` 版本表/错误码 → `07-message.md` → `user-guide.md` → `sql/tools/README.md` → PRD R-G7 行
- [x] 7. 跑检查（见下），按 `docs/agents/git-workflow.md` 收尾

## Verification

1. **后端单测（与 CI 同命令）**：`mvn -B verify`（至少 `mvn -B test -Dtest='AuthForgotPasswordTest,SmsTemplateRouterTest,SmsSenderTest'`）—— 重点看 ① 的 `never()` 护栏与 ② 的结构同构。
2. **前端**：`pnpm run lint:check` 与 `pnpm run test`（在 `frontend/`）。
3. **静态门禁**：`python tools/check_secrets.py`、`python tools/check_metric_dict_parity.py`。
4. **e2e 实跑**（需 MySQL/Redis + dev profile 后端）：`python backend/sql/tools/e2e_forgot_password.py` —— B1 必须看到响应 `data.smsCode` 与 Redis 一致、D 组找回全流程绿。
5. **手工链路**：起前后端 → `/login` →「忘记密码」→ 输手机号 → 点「获取验证码」→ **输入框被自动填满**且提示「验证码已自动填充」→ 改密码 → 旧密码登录 1001 / 新密码 200 → 再点获取验证码 60 秒内被 1009 拦（倒计时已覆盖）。
6. **回归确认漏服短信未受伤**：`SmsReminderServiceTest` 在 `mvn verify` 中通过；`application-prod.yml` 下 provider=aliyun 只校验 missed-dose 模板。
