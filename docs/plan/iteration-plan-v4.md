# 银龄伴诊 · V4 迭代规划（作品集导向）

> **文档版本：** V4.0-draft
> **规划类型：** 产品迭代规划（承接 V3.x，定位「作品集长期展示」而非一次性验收）
> **规划基线：** 2026-11-02 代码（V3.7 家属智能助手收口）
> **与既有文档的关系：**
> - 上游产品宪法：`docs/prd/PRD.md`、`AGENTS.md`（三条红线不变）
> - 上一轮功能盘点：`docs/prd/功能拓展规划.md` v1.0（E1–E6 + G1–G8，本规划核对其落地状态后重排）
> - 工程实施规范：`docs/plan/plan.md`（5 件套交付物、状态机不变量、迁移冻结约定）
> - 接口契约：本文件 §6 为**草案**；落地前先按「接口先行」约定写 `docs/api/` 正式版，再动工。

---

## 1 · 定位变化（为什么优先级要重排）

| 维度 | V3.x 时代 | V4 时代 |
|---|---|---|
| 目标受众 | 课程竞讲 / 验收 | **作品集长期展示**（审查者会翻代码、看工程完整度） |
| 外部依赖 | 禁止引入（无短信 / 无真实 LLM / 无对象存储） | **允许引入** |
| 直接后果 | 找回密码（G7）、站外提醒（E1b）被阻塞，排到 P3 | 两个 P3 解锁，权重上调 |
| 直接后果 | 注销 / 数据导出（E6）、测试欠账（L1/L5/L6）权重一般 | **工程完整度成为评审素材**，权重上调 |

> **三条红线长期不变**（不做诊断 / 隐私最小化 / 一期不做在线支付·地图导航·自由 IM），外部依赖放开**不等于**放开这三条。

---

## 2 · V4 新优先级表

> 排序口径：**业务闭环价值 × 作品集说服力 ×（1/成本）**；迁移成本沿用「零迁移加分」的项目惯例。

| 排名 | 方向 | 来源 | 升降 | 理由 |
|---|---|---|---|---|
| **P0-1** | 账号注销 + 个人数据导出 | E6 | **P2 → P0** | 作品集定位决定：审查者会问「用户权利呢」，现在答不上来；零迁移、复用 `pwd:version` / EasyExcel / MaskUtil，性价比极高 |
| **P0-2** | 漏服提醒触达闭环（站内信 + 陪诊员代提醒） | E1a | **P2 → P0** | 用药链最后一公里，不补则「用药协同管理」名不副实；全程复用 A2/A3/A4 资产，零迁移 |
| **P0-3** | 测试欠账清零（L1 e2e 残留 + L5 定时任务覆盖） | BUG_LIST | **不上榜 → P0** | 作品集定位决定：L1 是 P1 缺陷、L5 是「静默失败重灾区」，审查者跑一遍测试就能发现；不补则后续每次重构都可能假绿 |
| **P1-1** | 自助找回密码（手机号 + 验证码） | G7 | **P3（阻塞）→ P1** | 外部依赖解锁后第一件事，用户旅程断头被接上；但它依赖短信通道，**排在内部可独立完成项之后** |
| **P1-2** | 陪诊→用药贯通：就诊小结转用药计划草稿 | 新方向 | **新入 P1** | 把两条主链缝成「一个产品」，演示叙事最强；但因动 `OrderService` × `MedicationService` 边界，回归面比 P0 三项大 |
| **P1-3** | 站外提醒（短信 / 语音外呼） | E1b | **P3 → P1** | 依赖解锁，但**阻塞于 P1-1 的短信通道接入**，同一通道一次接完 |
| **P2-1** | 结算闭环收口：管理员核定 `payment_status` + 结算单导出 | E3 残留 | 维持 | 「线上记账」红线最后一块；零迁移，顺手可做 |
| **P2-2** | 健康档案结构化（体征记录入健康中心） | 新方向 | 维持 | 给家属「每周打开一次」的理由；需 V8 迁移一张表，留到阶段二 |
| **P2-3** | 运营参数可调化（接通 `sys_dict`） | G8 | 维持 | 纯加分项，穿插在任一阶段顺手做 |

> **被刻意降级的项**：陪诊员收入服务端聚合（E3 主体）已在 V3.3 落地，不再重复；G4（接单开关）在 V3.1 履约保障中顺带交付，从 RTM 移除。

### 首批 3 件事

1. **P0-1 注销 + 数据导出**（合规闭环 + 作品集说服力最高）
2. **P0-2 E1a 提醒触达**（业务闭环判据，零迁移）
3. **P0-3 测试欠账清零**（作品集工程底色；且是后续所有重构的安全网）

**为什么这个顺序**：P0-3 看似「不面向用户」，但它保障 P0-1/P0-2 的改造不会假绿；作品集场景下「跑一遍测试全绿、覆盖率不滑破」本身就是交付物。

---

## 3 · 阶段划分

```
阶段一（P0 · 4 项能力）
  P0-3 测试欠账清零 ──┐
                      ├──► P0-1 注销+导出 ──► P0-2 E1a 提醒触达
  （先行，保障后续）──┘

阶段二（P1 · 外部依赖接入 + 业务贯通）
  P1-1 找回密码（短信通道接入）
       └──► P1-3 E1b 站外提醒（复用同一通道）
  P1-2 陪诊→用药贯通（独立推进，与短信线并行）

阶段三（P2 · 锦上添花）
  P2-1 结算闭环 ──► P2-2 健康档案结构化 ──► P2-3 sys_dict 接通
```

**依赖关系硬约束**：
- P1-3 **必须**晚于 P1-1（同一短信通道，一次接入两次复用）；
- 阶段一任意项可独立交付，但建议 P0-3 先行，否则 P0-1/P0-2 改造无法验证回归；
- 阶段二的 P1-2 与 P1-1 可并行（无共享资源）。

---

## 4 · 阶段一任务拆解（可直接排期）

> 每项任务给出：**改哪些文件 / 新增端点 / 迁移 / 验收标准 / 依赖**。先写 `docs/api/` 正式契约，再动代码（AGENTS.md §8 接口先行）。

### 任务 4.1 · P0-3 测试欠账清零（先行）

| 项 | 内容 |
|---|---|
| **改哪些文件** | `backend/sql/tools/e2e_*.py`（7 个脚本补 teardown 或快照回滚）；`backend/src/test/java/.../task/` 新增 `MedicationSchedulerTest`（`@SpringBootTest` 直接调 `generateDailyTasks` / `scanMissedTasks`）；`docs/agents/BUG_LIST.md` 状态更新 |
| **新增端点** | 无 |
| **迁移** | 无 |
| **验收标准** | ① 同一 e2e 脚本连跑 3 遍全绿（当前因 `internal_message` 残留第 2 遍会假失败）；② `mvn verify` 后 JaCoCo 报告 `task` 包行覆盖 ≥ 60%；③ Service 行覆盖不低于当前 63.26% |
| **依赖** | 无（最先做） |

### 任务 4.2 · P0-1 账号注销 + 个人数据导出

| 项 | 内容 |
|---|---|
| **改哪些文件** | `controller/user/UserController.java`（+2 端点）；`service/impl/UserServiceImpl.java`（注销校验 + 去标识化）；`service/AuthService`（复用 `pwd:version` 递增）；`utils/MaskUtil.java`（确认占位串）；`frontend/src/views/profile/index.vue`（+「注销账号」「导出我的数据」入口）；`frontend/src/api/user.js`；`docs/api/01-auth-user.md` 与 `02-elder-family.md` |
| **新增端点** | `POST /api/user/deactivate`、`GET /api/user/data-export`（契约见 §6.1 / §6.2） |
| **迁移** | 零（复用 `sys_user.deleted` / `status` / `remark`；如确需记注销时间，用 `remark` 记 JSON） |
| **验收标准** | ① 有进行中订单（`PENDING/ACCEPTED/IN_SERVICE`）时注销被拒并返回具体原因；② 注销后旧 token 立即 401（`pwd:version` 已递增）；③ 导出文件人工核验**不含**身份证号 / 完整手机号 / 密码；④ 订单/评价/日志保留但姓名、手机号、身份证去标识化；⑤ 新增越权矩阵：用户 A 不能导出用户 B 数据（403 / 2006） |
| **依赖** | 任务 4.1（回归保障） |

### 任务 4.3 · P0-2 漏服提醒触达闭环（E1a）

| 项 | 内容 |
|---|---|
| **改哪些文件** | `service/impl/MedicationServiceImpl.java`（抽 `ReminderDispatcher` 策略接口，站内信为默认实现）；`service/impl/MedicationServiceImpl#notifyFamily` 拆为 `notifyFamily` + `notifyOnDutyCompanion`；`service/MessageService.java`（+1 消息模板）；`docs/api/05-medication.md` 与 `07-message.md` |
| **新增端点** | 无（纯后端逻辑强化；前端只需在用药计划页加「提醒方式」只读说明） |
| **迁移** | 零（不开新渠道字段；「当天有陪诊订单」靠 `companion_order` 关系现查） |
| **验收标准** | ① 当天有 `ACCEPTED/IN_SERVICE` 订单时，漏服提醒**同时**投递给该单陪诊员（消息类型 `MEDICATION_MISSED`，`link_url` 指向订单详情）；② 无陪诊订单时行为与现状一致（只发家属）；③ 30 分钟扫描节奏与 Redis 锁粒度**不变**（既有压测结论不失效）；④ 提醒文案过合规词表（禁含剂量） |
| **依赖** | 任务 4.1 |

---

## 5 · 阶段二 / 三任务拆解（粒度到模块级，排期用）

### 任务 5.1 · P1-1 自助找回密码（短信通道接入）

| 项 | 内容 |
|---|---|
| **改哪些文件** | `controller/auth/AuthController.java`（+1 端点）；`service/AuthService`；**新增** `service/sms/SmsService.java`（渠道抽象 + 第一个实现）；`config/SmsProperties`（启动期校验）；`frontend/src/views/login/forget.vue`（从引导页改为可用表单）；`docs/api/01-auth-user.md` |
| **新增端点** | `POST /api/auth/forgot-password`（契约见 §6.3） |
| **迁移** | 零 |
| **验收标准** | ① 验证码 Redis 存储，5 分钟过期，错 5 次作废；② 手机号不存在时**与存在同响应**（防枚举）；③ 重置成功后 `pwd:version` 递增，旧 token 全失效；④ 未配置短信通道时**启动失败**而非静默降级（安全基线 §十 同款取舍） |
| **依赖** | 任务 4.1 |

### 任务 5.2 · P1-3 站外提醒（短信 / 语音）

| 项 | 内容 |
|---|---|
| **改哪些文件** | `service/impl/MedicationServiceImpl` 的 `ReminderDispatcher`（+第二渠道实现）；`service/sms/SmsService` 复用；`docs/api/05-medication.md` |
| **新增端点** | 无 |
| **迁移** | `V8__medication_reminder_channel.sql`（`medication_plan` 加 `reminder_channel` / `reminder_advance_minutes` 2 字段） |
| **验收标准** | ① 外呼文案**只含药名 + 时间**，禁含剂量（合规词表硬拦截）；② 单次服药外呼 ≤ 2 次，失败回落站内信；③ 渠道按 `medication_plan` 逐条配置，家属可关 |
| **依赖** | 任务 5.1（同一短信通道） |

### 任务 5.3 · P1-2 陪诊→用药贯通

| 项 | 内容 |
|---|---|
| **改哪些文件** | `service/impl/OrderServiceImpl.java`（服务小结提交时**不直接写**用药计划，只返回「可转入」标记）；`service/impl/MedicationServiceImpl.java`（+草稿接口）；`frontend/src/views/family/order-detail.vue`（+「转入用药计划」按钮）；`frontend/src/views/family/medication.vue`（+草稿态）；`frontend/src/store/modules/orderDraft.js`（复用草稿模式）；`docs/api/03-order.md` 与 `05-medication.md` |
| **新增端点** | `POST /api/medication/plan/from-order/{orderId}`（契约见 §6.4） |
| **迁移** | 零（草稿态由前端 orderDraft 承载，落库即正式计划） |
| **验收标准** | ① 小结含合规词时 3007 拦截（既有）；② 转入草稿**不自动填剂量**，仅药品名 + 频次建议（家属手工确认）；③ 草稿未确认前不产生任何 `medication_task`；④ 一单一转（重复转入返回 409） |
| **依赖** | 任务 4.1；与 5.1 可并行 |

### 任务 5.4 · P2-1 结算闭环收口

| 项 | 内容 |
|---|---|
| **改哪些文件** | `controller/admin/AdminController.java`（+核定端点）；`service/impl/SettlementServiceImpl`（新建）；`frontend/src/views/admin/order.vue` 或新建 `settlement.vue`（批量核定界面）；`docs/api/08-admin.md` |
| **新增端点** | `POST /api/admin/settlement/confirm`、`GET /api/settlement/export` |
| **迁移** | 零 |
| **验收标准** | ① 核定写 `admin_oper_log`；② 导出列不含敏感字段；③ 金额口径 `actual_fee` 缺失回退 `fee` 写入接口文档；④ 文案无「提现/支付」字样 |

### 任务 5.5 · P2-2 健康档案结构化

| 项 | 内容 |
|---|---|
| **改哪些文件** | 新建 `entity/VitalSignRecord` + mapper + service + controller（挂 `ElderHealthController`）；`frontend/src/views/elder/health.vue` 与 `family/elder-health.vue`（+体征录入/趋势）；`docs/api/12-elder-health.md` |
| **新增端点** | `POST /api/elder/vital-sign`、`GET /api/elder/vital-sign/trend` |
| **迁移** | `V9__vital_sign_record.sql`（1 张表） |
| **验收标准** | ① 只做记录 + 趋势展示，**无任何评估/预警文案**；② 老人只读规则生效（ELDER 写 403）；③ 归属校验下沉 Service（家属 A 不能录家属 B 的老人） |

### 任务 5.6 · P2-3 运营参数可调化

| 项 | 内容 |
|---|---|
| **改哪些文件** | `service/impl/SysDictServiceImpl`（新建，带缓存）；`controller/admin/SysDictController`（新建）；`frontend/src/views/admin/`（+字典管理页）；把漏服阈值 / 催单限频 / 导出上限 3 项迁为字典 |
| **新增端点** | `GET /api/admin/dict`、`PUT /api/admin/dict/{key}` |
| **迁移** | 零（`sys_dict` 表已存在） |
| **验收标准** | ① 字典变更留审计；② 缓存 TTL ≤ 60s；③ 不搬「技术配置」（DB 连接等），只搬运营阈值 |

---

## 6 · 接口契约草案（V4 新增端点）

> **状态：草案**。落地前先按 AGENTS.md §8「接口先行」约定，把本节内容升格为 `docs/api/` 正式文档并通知前端；错误码段位沿用 `docs/api/README.md` §四（1xxx–9xxx 已用满，新段从 11xxx 起）。

### 6.1 · 注销账号（P0-1）

```
POST /api/user/deactivate
权限：FAMILY / COMPANION（ELDER 只读拦截器 403；ADMIN 由后台封禁走另一路径）
请求体：
{
  "password": "当前密码（复验）",
  "confirmText": "我已知晓注销后果"   // 前端二次确认，后端校验固定串防误触
}
响应（成功）：
{ "code": 200, "message": "账号已注销", "data": null }
错误码：
  400  PARAM_ERROR        confirmText 不匹配 / 缺密码
  1006 原密码不正确
  11001 存在进行中订单     // 新增 11xxx 段；data 返回 { "pendingOrderCount": 2 }
副作用：
  - sys_user.deleted = 1，status = 'DISABLED'
  - pwd:version 递增（该账号全部 token 立即失效）
  - 姓名 / 手机号 / 身份证去标识化（保留订单与日志的去标识化关联）
```

### 6.2 · 个人数据导出（P0-1）

```
GET /api/user/data-export
权限：FAMILY / COMPANION（ELDER 只读拦截器 403；仅本人，userId 从令牌取，禁止入参指定）
响应：application/octet-stream（EasyExcel 流式导出）
  - 个人资料、绑定老人列表、订单记录、评价记录、消息记录（全部过 MaskUtil）
错误码：
  401 未登录
  403 ELDER / ADMIN 调用
说明：文件不含身份证号、完整手机号、密码哈希（红线-2）。
```

### 6.3 · 自助找回密码（P1-1）

```
POST /api/auth/forgot-password
权限：公开
请求体：
{
  "phone": "13800000000",
  "smsCode": "123456",
  "newPassword": "新密码（BCrypt 强度校验同注册）"
}
响应（成功）：
{ "code": 200, "message": "密码已重置，请重新登录", "data": null }
响应（手机号不存在时，防枚举，与成功同构）：
{ "code": 200, "message": "密码已重置，请重新登录", "data": null }   // 但服务端不真正改密
错误码：
  1003 验证码错误或已过期
  429  操作过于频繁       // 同手机号 1 分钟 ≤ 1 次发送
副作用：重置成功 → pwd:version 递增，旧 token 全失效
前置：短信验证码另走 POST /api/auth/sms-code（契约同既有图形验证码模式，此处略）
```

### 6.4 · 就诊小结转用药计划草稿（P1-2）

```
POST /api/medication/plan/from-order/{orderId}
权限：FAMILY（须为本单下单家属；他人 3004）
请求体：
{
  "elderId": 123,
  "items": [
    { "drugName": "苯磺酸氨氯地平片", "frequency": "每日1次", "planTime": "08:00" }
    // ⚠️ 不含 dose / dosage 字段（红线-1，剂量由家属在正式计划页手工录入）
  ]
}
响应（成功）：
{ "code": 200, "message": "草稿已生成，请确认后生效", "data": { "draftId": "xxx", "orderId": 456 } }
错误码：
  3001 订单不存在
  3004 无权操作该订单（非本单家属）
  3007 服务小结不能包含诊断、处方或用药建议（小结过合规词表）
  409  该订单已生成过草稿（一单一转）
副作用：
  - 仅生成草稿（不落 medication_plan 正式表，复用前端 orderDraft 模式）
  - 家属在前端确认后才调既有 POST /api/medication/plan 正式落库
```

---

## 7 · 验收清单（V4 阶段一收口时自查）

- [ ] `docs/api/01-auth-user.md` / `02-elder-family.md` / `05-medication.md` / `07-message.md` 已含 §6 全部端点的正式版
- [ ] `ResultCode.java` 新增 `11001`（存在进行中订单），`docs/api/README.md` 错误码总表同步
- [ ] 注销 / 导出 / 提醒触达三条链路的越权矩阵新增用例全绿
- [ ] `mvn verify` 测试数 ≥ 592 + 新增用例数；`task` 包覆盖 ≥ 60%
- [ ] e2e 脚本连跑 3 遍全绿
- [ ] 导出文件人工核验无敏感字段
- [ ] 提醒文案过合规词表（禁剂量）；注销页面文案如实说明「订单/日志保留但去标识化」
- [ ] 三条红线逐条对照未破（无诊断建议、无完整手机号/身份证出口、无在线支付/地图/自由 IM）

---

## 版本记录

| 版本 | 日期 | 关键变更 |
|---|---|---|
| V4.0-draft | 2026-11-02 | 基于外部依赖放开 + 作品集定位重排优先级；P0 锁定「注销导出 / E1a 提醒触达 / 测试欠账清零」；给出 6 项任务拆解与 4 个接口契约草案 |
