# 0011. 订单内家属↔陪诊员聊天（M13 · 二期扩围）

| | |
|---|---|
| 状态 | 已接受 (Accepted) |
| 日期 | 2026-10-16 |
| 决策者 | 项目负责人 |
| 关联模块 | M13 订单聊天（二期扩围）/ M5 实时通道 / M8 站内信 / M9 管理后台 |
| 关联文档 | `docs/api/11-order-chat.md` · `docs/plan/plan.md` 模块表 M13 · `PRD.md` §5.2 |
| 相关 ADR | 0001（M5 WebSocket 鉴权）· 0006（PRD 版本锁）· 0009（费用明细模型）· 0010（订单流转单一入口） |

---

## 背景

一期范围控制（`docs/plan/plan.md` §一 硬性约束 3 / `README.md` 合规约束 3 / `PRD.md` §2.2）砍掉了 IM 聊天，用站内信（M8）替代——站内信是**系统 → 用户**的单向通知，没有双向对话能力。一期已于 2026-09-29 收官（E4 迭代收口）。

但真实场景里，家属对「老人此刻怎么样」的关心绕不开与陪诊员的双向沟通：就诊中途想问「医生怎么说」、老人不适想嘱托「帮我扶一下」、订单取消后要交涉费用。这些在站内信里只能单向留言，來回各一条还互不串成对话。

`ADR-0001`（M5 WebSocket 鉴权）当年就预留了扩展口：

> 后续可扩展家属→陪诊员对讲能力（M12+）。

`PRD.md` §5.2 也一直把「IM 聊天」列为二期设想。2026-10-16 项目负责人确认：**二期扩围启动，按订单落地家属↔陪诊员聊天**。本 ADR 记录范围变更与全部技术决策。

> **关于 PRD 版本锁（ADR-0006）**：`PRD.md` §1 愿景「不接 IM」与 §2.2「一期不做」描述的是一期事实，**正文不改**；仅在 §5.2 前瞻表 IM 行补「M13 已落地」标注。范围变更的正式记录以本 ADR 为准，红线表述的修订落在 `AGENTS.md` §0.1.3 / `README.md` / `docs/plan/plan.md`。

---

## 决策

**聊天以陪诊订单为会话身份，家属与接单陪诊员双向读写，老人只读，管理员仲裁只读；发送走 REST、接收走 WebSocket、角标提醒复用站内信 SSE 通道。**

### 权限模型

| 角色 | 权限 | 落点 |
|---|---|---|
| FAMILY（本单下单人） | 读写 | `requireInvolved` + `requireChatWriter` |
| COMPANION（本单接单人） | 读写 | 同上 |
| ELDER（绑定老人） | **只读** | `ElderReadOnlyInterceptor` 拦写（403）+ 服务层二次拦截（7008） |
| ADMIN | **仲裁只读**（任意会话） | `requireInvolved` admin 直通；服务层 `requireChatWriter` 拦写（7008）；`POST /api/file/upload` 的 `CHAT` 类型对 ADMIN 显式拒绝 |

### 关键决策点

**D1 · 可聊状态：`订单存在 && companion_id != null`，不新增会话状态机。**
仅 `PENDING` 不可聊（7003，接单前没有会话对方）；`ACCEPTED / IN_SERVICE / COMPLETED / REVIEWED / CANCELLED` 均可读写。理由：
1. `CANCELLED` 只能由管理员从 `ACCEPTED / IN_SERVICE` 强制进入，此时会话早已建立，**仲裁后的退款 / 责任交涉恰恰集中在这之后**——封写通道会把交涉逼回线下，架空管理端查看器的证据链价值；
2. 不引入「会话冻结」中间态，聊天生命周期完全寄生订单，不触碰 `OrderStatus.TRANSITIONS`（不违背 ADR-0010 的状态机收口）；
3. 骚扰风险由既有能力兜底：管理员可封禁账号（DISABLE_USER）。

**D2 · 通道组合：REST 发送 + WS 接收 + SSE 角标。**
- **发送走 REST** `POST /api/chat/{orderId}/messages`：复用 JwtAuthenticationFilter 鉴权、`@Valid` 校验、事务、`BusinessException→Result`、`ElderReadOnlyInterceptor` 整套管线。WS 上行若开业务写入口，等于在心跳通道旁再造第二套无事务、无统一异常的写接口——`OrderProgressHandler` 的先例明确「上行只认裸 ping」，该纪律延续。
- **接收走 WS** `/ws/chat?token=&orderId=`：1:1 双推，握手鉴权**原样复用** `JwtHandshakeInterceptor`（token 验签→access→黑名单→密码版本→`requireInvolved(orderId, loginUser)`，与 Handler 零耦合，ADR-0001 的决策直接生效）。
- **角标走 SSE** `/sse/message` 新增 `CHAT_MESSAGE` 事件：M5 是「双向 WS」、M8 是「单向 SSE」，两通道分工的教义不破坏——聊天**载荷投递**走 WS，SSE 只承载「有新消息」这一单向提醒（与站内信 `NEW_MESSAGE` 同构），供会话列表与未读角标消费。

**D3 · 数据模型：不建会话表，订单即会话。**
`companion_order.family_id + companion_id` 就是会话双方，单独的 conversation 表会造成「接单状态」双写同步，违反状态机唯一真源。会话列表 = 我的已接单订单 + 每单最后一条消息 + 未读计数。两张新表（`V5__chat_message.sql`，`V4` 已被 `order_fee_item` 占用）：

- `chat_message`：`order_id / sender_id / sender_role / msg_type(TEXT|IMAGE|VIDEO) / content(≤1000) / file_id / file_url / file_size`；索引 `idx_order_id_id(order_id, id)` 支撑游标倒序分页、未读计数、会话最后一条；`idx_sender(sender_id, create_time)` 支撑审计。
- `chat_read_cursor`：`uk_order_user(order_id, user_id)`，已读上报 upsert；未读数 = `id > 游标 AND sender_id != me` 计数。

**D4 · 上传分级限额：聊天 50MB，其余业务 10MB 防线不破。**
servlet `max-file-size` 全局提到 50MB（`max-request-size` 52MB，必须大于文件上限，否则 multipart 表单开销必触发超限异常）。代码层在 `FileStorageServiceImpl#store` 按 bizType 分级：`CHAT ≤ 50MB`，其余 `≤ 10MB`——servlet 提额后由代码守住原防线。新增 `video/mp4`（文件头 offset 4 的 `ftyp` box 判据）；`store()` 由整文件 `getBytes()` 改为流式落盘（50MB 读进堆不可接受）。`CHAT` 附件仅 FAMILY / COMPANION 可传（ADMIN 被拒，仲裁只读不因通用端点破防）。

**D5 · 合规边界。**
聊天是用户间沟通，平台不对内容做医疗背书：聊天室顶部固定免责声明（不做诊断、不开药方，紧急情况请拨打 120）；会话列表与消息流**不返回双方手机号**（姓名按场景脱敏，列表脱敏）；发送链路沿用 3007 同源的合规词表拦截（Service 层复用敏感词校验，命中回 7004）。管理端仲裁查看器为纠纷处理提供取证能力，查阅属管理员既有职权范围。

---

## 选项

### A. 维持站内信单向通知（现状）

- **优点**：零改动。
- **缺点**：家属与陪诊员无法双向沟通，用户只能把聊天逼到平台外（微信），过程不可追溯、纠纷无凭证；ADR-0001 与 PRD §5.2 的预留长期悬空。

### B. 全局点对点会话（家属↔陪诊员加好友式）

- **优点**：沟通不依附订单，长期关系可维系。
- **缺点**：需新增关系管理（谁能发起？历史订单双方？）与授权规则，隐私面显著扩大（陪诊员可主动触达家属即骚扰面）；与「关心老人本次就诊」的真实场景错位。

### C. 按订单建会话，权限复用订单归属 ← **本次选**

- **优点**：`requireInvolved` 一套校验管到底（WS 握手 / REST / 管理端直通全覆盖）；会话天然对应「本次就诊」上下文；越权面最小；零新关系表。
- **缺点**：跨订单无连续对话（同一对家属/陪诊员换订单要换会话）——按订单建会话 + 跨单聚合视图可作为后续增量，本期不做（见非目标）。

---

## 后果

### 正面

- 家属「关心老人实时情况」的核心诉求有了合规的产品内通道，沟通留痕、纠纷可仲裁。
- ADR-0001「M12+ 对讲」预留兑现；`/ws/progress` 的握手鉴权资产直接复用，新增实时能力边际成本极低。
- 上传链路完成流式改造，为后续大文件场景（如二期对象存储）铺路。

### 负面

- **三处红线表述被修订**（AGENTS.md / README / docs/plan/plan.md），历史文档读者需靠本 ADR 理解口径变迁；PRD 正文因版本锁不动，存在短期口径差（§5.2 已标注）。
- `chat_message` 是新增的用户生成内容存储，管理端必须能查（已含 `GET /api/admin/chat/orders` + 查看器），后续若接监管要求（内容审计）需另行决策。
- 50MB 提额后，恶意上传大文件的带宽 / 磁盘风险上升——由分级限额 + 既有全局异常兜底，一期先接受。
- WS 双推按「跳过发送方自己」设计，同用户多端登录的实时性由各自 REST 轮询兜底，极端场景消息上屏延迟到下次拉取。

### 验证标准

- [ ] `mvn -B verify` 全绿，含 ChatServiceTest（状态矩阵 / 四角色读写矩阵 / 附件三态）与 WS Hub/Handler 单测
- [ ] `frontend/e2e/specs/14-chat.spec.js` 全绿：双账号 ≤3s 互达、ELDER 写 403、ADMIN 写 7008、ADMIN 读 200
- [ ] 12MB 图片传 `COMPLAINT` 得 7009；mp4 传 `CHAT` 成功；改名伪装文件被 ftyp 判型拒绝
- [ ] 非相关方 WS 握手被拒（requireInvolved → 关闭连接）
- [ ] 文档五处联动（11-order-chat.md / api-README / AGENTS.md / README / docs/plan/plan.md）与本 ADR 一致

### 非目标（明确排除，防蔓延）

消息撤回/编辑；「对方已读」回执；「正在输入」提示；离线站内信兜底推送；语音与音视频通话（WebRTC 仍留 PRD 设想）；端到端加密；群聊 / 跨单聚合会话；会话置顶/免打扰/删除；聊天专用敏感词库（沿用既有词表）；消息全文检索；ELDER 专属会话列表页；对象存储迁移；TabBar 红点改造。
