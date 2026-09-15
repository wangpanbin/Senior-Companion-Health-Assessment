# 后端开发计划 v1.0（W17 答辩前 · M4–M13）

> 生成时间：2026-09-15
> 范围：M4 收尾 → M13 部署（M0–M3 已交付，见 git log `6bc37c5 / 0199770 / d17056a / f464fcd / f3b1416`）
> 计划归宿：本文件 `docs/agents/PLAN_BACKEND.md`（单一真源）
> 上游约束：`AGENTS.md`（规范红线）、`plan.md`（模块验收标准）、`docs/api/README.md`（接口约定）
> 配套 ADR：`docs/adr/0001-m5-websocket-auth.md` / `0002-m6-redis-lock.md` / `0003-m10-stats-cache.md` / `0004-m13-docker-fallback.md`

---

## 1 · 计划基线

| 项 | 内容 |
|---|---|
| 时间窗口 | 2026-09-15（今天） → 2027-01-19（W17 答辩） |
| 当前状态 | M0/M1/M2/M3 已交付 + 单测全绿（144/144）+ e2e_user_profile 86 项断言通过 |
| M4 状态 | 代码写完未提交（工作区 untracked：OrderController/Service/DTOs/VOs/ComplianceCheckUtil/PaymentStatus 等） |
| 工作分支 | `feature/M2-auth`（分支名待重命名为 `feature/M4-order`） |
| 横切已完成 | Result/ResultCode/PageResult/BaseEntity、GlobalExceptionHandler、SecurityConfig + JwtAuthenticationFilter + ElderReadOnlyInterceptor + @AllowElderWrite、Knife4j、MyBatis-Plus 分页/乐观锁/逻辑删除、TokenStore(Redis)、CaptchaService |

---

## 2 · 团队分工（主/旁并行）

### 主路径（B 后端主力）
```
M4(收尾) → M5 → M7 → M9
```
- M4 → M5：依赖（M5 打卡只能在 IN_SERVICE 状态写入）
- M5 → M7：依赖（M7 评价只能在 COMPLETED 状态）
- M7 → M9：依赖（M9 纠纷处理调取评价数据）

### 旁路径（D DB + 项目管理）
```
M8 → M6 → M10(预写骨架) → M13
                ↓ M9 完成后
        M10 补管理后台维度
```
- M8 → M6：依赖（M6 漏服提醒经 M8 站内信推送给家属）
- M6 → M10 预写：M10 统计先接 M4 订单/用户表，骨架先跑
- M9 → M10 收口：B 的 M9 完成后，D 补"审核/封禁/纠纷"三个统计维度

### 公共横切（C 测试）
- M12 跨模块测试维护 + 越权用例矩阵扩展 + JMeter 压测脚本
- 注意：C 主要做前端 + UI，本计划仅就 C 与后端协作的接口部分列事项

---

## 3 · 实时推送架构（M5 + M8 共同）

| 模块 | 技术 | 方向 | 用途 |
|---|---|---|---|
| M5 | **WebSocket + STOMP** | 双向 | 陪诊员打卡上行（带 token）+ 家属端实时进度下行 |
| M8 | **SSE**（Server-Sent Events） | 单向 | 订单事件/审核/漏服提醒 → 顶栏红点推送 |

- **不混用**：M5 客户端连 `/ws/order-progress`，M8 客户端连 `/sse/message`
- **鉴权差异**：M5 握手时复用 `JwtAuthenticationFilter` 模式（M5 鉴权方案见 `docs/adr/0001-m5-websocket-auth.md`）；M8 SSE 直接走 `Authorization: Bearer` header
- **重连**：M5 由 STOMP 心跳 + 客户端自动重连；M8 由 EventSource 原生重连（浏览器内置）
- **离线补齐**：M5 重连后客户端拉 `/api/execution/checkin-missed?since={lastEventId}`；M8 同样支持 `Last-Event-ID`

---

## 4 · 横切测试节奏（每个模块同步）

每个模块交付包 = **5 件套**：

| # | 件 | 位置 | 责任人 |
|---|---|---|---|
| 1 | 实现（Controller/Service/Mapper/DTO/VO） | `backend/src/main/java/.../<module>/` | 主/旁各自 |
| 2 | Service 单测（Mockito 优先，≥ 60% 覆盖率） | `backend/src/test/java/.../service/<Module>ServiceTest.java` | 主/旁各自 |
| 3 | 归属/越权测试（4 角色 × 3 类接口） | `backend/src/test/java/.../security/<Module>AccessMatrixTest.java` | 主/旁各自 |
| 4 | e2e 脚本（真实 HTTP 打 8080，自带数据清理） | `backend/sql/tools/e2e_<module>.py` | 主/旁各自 |
| 5 | docs/api 同步（路径 / 参数 / 错误码 / 示例） | `docs/api/NN-<module>.md` | 主/旁各自 |

- **JaCoCo 覆盖率门禁**：W11 加 Maven `jacoco-maven-plugin` + `verify` 阶段硬卡 ≥ 60%
- **跨模块越权矩阵**：每模块完成后扩展 `PermissionMatrixTest`，保持 4 角色 × 3 类接口 ≥ 12 条用例
- **JMeter 压测**：M4 收尾时跑 50 并发抢单（验收硬指标），M12 扩展到 M5/M6/M9

---

## 5 · 模块开工前设计评审

每个模块开工前用 `dsh-plan` 技能生成 **≤ 1 页** 设计文档，内容固定 4 段：

```
1. 接口表：列出本模块所有 REST 端点（方法 / 路径 / 角色 / 入参 / 出参）
2. 数据流：表 → Mapper → Service → Controller 的链路图（mermaid / 文字）
3. 状态机（如有）：状态 + 合法转移 + 非法转移的错误码
4. 验收清单：从 plan.md §三 抽出该模块的验收项，逐条勾选
```

设计评审产出物存于 `docs/agents/designs/<M-id>-<module>.md`，PR 时附在描述中。

---

## 6 · ADR 拆分范围

| ADR | 模块 | 主题 | 状态 |
|---|---|---|---|
| `0001-m5-websocket-auth.md` | M5 | WebSocket 握手鉴权方案选型 | **待填**（M5 开工时细化） |
| `0002-m6-redis-lock.md` | M6 | Redis 分布式锁选型（手写 SET NX + Lua vs Redisson） | **待填**（M6 开工时细化） |
| `0003-m10-stats-cache.md` | M10 | 统计接口缓存策略（不缓存 / 短 TTL / 物化视图） | **待填**（M10 开工时细化） |
| `0004-m13-docker-fallback.md` | M13 | Docker 安装受阻时的兜底部署方案 | **待填**（M13 开工时细化） |

ADR 仅写"不可逆 + 跨模块 + 真实权衡"的决策，结构遵循 `mattpocock-skills` 的 ADR-FORMAT。

---

## 7 · 模块排期（W3 → W17，共 15 周）

> 时间基准：以今天（2026-09-15）为起点；W3 = 当前周（W3 of 项目 17 周节奏）。B/D/C 站会默认 22:00。

| 周次 | 日期范围 | 主路径 (B) | 旁路径 (D) | 横切 (C) | 里程碑 |
|---|---|---|---|---|---|
| **W3–W4** | 9/15 – 9/28 | **M4 收尾**（OrderServiceTest/OrderAccessMatrixTest 全绿 + e2e_order.py 通过 + commit + PR 合入 develop） | M8 设计评审 + 站内信表接口骨架 | — | **M4 验收（计划书 W7 前的里程碑）** |
| **W4–W5** | 9/29 – 10/12 | **M5 设计评审 + 实现**（OrderCheckin + CompanionTrack + WebSocket 配置 + JwtHandshakeInterceptor） | **M8 实现**（InternalMessage CRUD + SSE 配置 + 顶栏红点推送） | — | — |
| **W5–W6** | 10/13 – 10/26 | **M5 续**（打卡接口 + 距离校验 + 时间线 + 重连补齐）+ 50 并发压测 | **M8 续**（消息模板：订单事件 / 审核 / 漏服 / 系统公告）+ 端到端 | — | — |
| **W7** | 10/27 – 11/2 | M5 收口 + e2e_websockets | — | **JMeter 压测报告 v1** | **W7 竞讲交付**（订单 + 接单 + 打卡） |
| **W8–W9** | 11/3 – 11/16 | **M7 实现**（OrderReview + Complaint + 敏感词过滤 + 平均分聚合） | **M6 设计评审 + 实现**（MedicineDict + MedicationPlan + Spring Task + Redis 锁） | — | — |
| **W10–W11** | 11/17 – 11/30 | **M7 收口 + e2e_review.py** + **M9 设计评审 + 实现起步**（审核列表 + 封禁/解封 + admin_oper_log） | **M6 续**（每日 07:00 生成 medication_task + 30min 漏服扫描 + 家属推送） | JaCoCo 覆盖率门禁上线 | — |
| **W12** | 12/1 – 12/7 | **M9 实现**（订单管理 + 纠纷处理强制终态） | **M10 设计评审 + 骨架**（统计 SQL + ECharts 模板 + EasyExcel 导出骨架） | — | **W12 竞讲交付**（评价 + 用药 + 通知闭环） |
| **W13** | 12/8 – 12/14 | **M9 收口 + e2e_admin.py** | **M10 骨架续** + **M13 设计评审 + Docker 安装尝试** | — | **W13 竞讲交付**（管理后台 + 数据看板） |
| **W14–W15** | 12/15 – 12/28 | M9 与 M10 数据维度对接 + 横切测试加固 | **M10 数据维度收口**（审核 / 封禁 / 纠纷）+ **M13 实现**（Dockerfile / docker-compose / deploy.sh） | 跨模块 e2e 全绿 | — |
| **W16** | 12/29 – 1/4 | 全链路演练 + JMeter 压测报告 v2 | **M13 收口**（部署文档 + 用户手册 5 张截图 + 演示视频） | 测试报告归档 | **W16 完整系统交付** |
| **W17** | 1/5 – 1/19 | 答辩 PPT + 答辩准备 | 答辩 PPT + 演示视频剪辑 | 遗留 Bug 清单 P0/P1 清零 | **W17 最终答辩** |

> 注：以上日期为计划估算，受 W7/W12/W13/W16 四个竞讲节点倒推；如遇压测阻塞或需求变更，由 daily sync 决定调整。

---

## 8 · 每个模块的 WBS（5 件套清单）

### M4 陪诊订单与状态机（**收尾**）

- [ ] OrderServiceTest 全绿（含 50 并发抢单测试）
- [ ] OrderAccessMatrixTest 全绿（4 角色 × 关键接口）
- [ ] e2e_order.py 全跑通：注册 → 登录 → 下单 → 接单 → 服务中 → 完成 → 评价
- [ ] 分支重命名 `feature/M2-auth` → `feature/M4-order`
- [ ] commit message：`feat(M4): 陪诊订单状态机与乐观锁接单落地`
- [ ] 合入 develop
- [ ] **M4 设计评审**：接口表（9 个端点）/ 数据流（OrderStatusLog 写入逻辑）/ 状态机（5 状态 + 1 终态）/ 验收清单 7 条

### M5 陪诊执行、打卡与实时进度（B 主路径）

- [ ] **M5 设计评审**：接口表（checkin / track / timeline / WebSocket subscribe）/ 数据流（order_checkin + companion_track 双写）/ 状态机（6 个打卡节点不可回退）/ 验收清单 6 条
- [ ] **M5 ADR-0001**：WebSocket 鉴权方案确定
- [ ] Entity / Mapper 已有，复用 OrderCheckin / CompanionTrack
- [ ] Controller: `ExecutionController`（`/api/execution/checkin`、`/api/execution/timeline/{orderId}`、`/api/execution/checkin-missed`）
- [ ] WebSocket: `WebSocketConfig` + `OrderProgressHandler` + `JwtHandshakeInterceptor`（按 ADR-0001）
- [ ] Service: `ExecutionService.checkin()` 含距离校验（`4001`）+ 节点去重（`4002`）+ 双写轨迹
- [ ] 单测 + 越权 + e2e（含 30s 断网重连补齐）
- [ ] 50 并发抢单压测 JMeter 脚本归档

### M6 用药管理与漏服提醒（D 旁路径）

- [ ] **M6 设计评审**：接口表（medicine-dict / plan / task / confirm）/ 数据流（Spring Task + Redis 锁 + M8 推送）/ 状态机（待服/已服/漏服）/ 验收清单 6 条
- [ ] **M6 ADR-0002**：Redis 分布式锁方案确定
- [ ] Entity / Mapper 已有，复用 MedicineDict / MedicationPlan / MedicationTask
- [ ] Controller: `MedicationController`
- [ ] 定时任务: `MedicationTaskGenerator`（每天 07:00） + `MissedDoseScanner`（每 30 分钟）
- [ ] 分布式锁：`RedisLockTemplate`（按 ADR-0002）
- [ ] 合规词表（与 OrderServiceImpl 的 `ComplianceCheckUtil` 共用）
- [ ] 单测 + 越权 + e2e（手动改时间触发漏服）

### M7 评价与投诉（B 主路径）

- [ ] **M7 设计评审**：接口表（review / complaint / score-aggregation）/ 数据流（OrderReview 写 + 触发平均分重算）/ 状态机（订单状态闸门 `6001` `6002`）/ 验收清单 5 条
- [ ] Entity / Mapper 已有
- [ ] Controller: `ReviewController` + `ComplaintController`
- [ ] Service: `ReviewService.create()` 含订单状态校验 + 敏感词过滤（`6003`）
- [ ] 聚合查询：`CompanionProfile.avgRating` 增量更新（用 SQL `UPDATE ... SET avg_rating = (SELECT AVG...)`）
- [ ] 单测 + 越权 + e2e

### M8 站内信与通知（D 旁路径）

- [ ] **M8 设计评审**：接口表（list / detail / read / read-all / unread-count）/ 数据流（事件 → 消息表 → SSE 推送）/ 状态机（已读/未读）/ 验收清单 5 条
- [ ] Entity / Mapper 已有，复用 InternalMessage
- [ ] Controller: `MessageController`
- [ ] SSE: `SseConfig` + `MessageEmitter` + `/sse/message` 端点
- [ ] 消息模板: `MessageTemplate`（订单事件 / 审核结果 / 漏服提醒 / 系统公告）
- [ ] 各业务模块发消息的统一入口：`MessageService.send(userId, template, params)`
- [ ] 单测 + 越权 + e2e

### M9 管理后台（B 主路径）

- [ ] **M9 设计评审**：接口表（审核 / 用户管理 / 订单管理 / 操作日志）/ 数据流（admin_oper_log 必写 + 业务表变更）/ 状态机（资质审核状态 + 封禁状态）/ 验收清单 6 条
- [ ] Entity / Mapper 已有，复用 AdminOperLog / CompanionAuditRecord / SysUser
- [ ] Controller: `AdminController`（4 个子模块）
- [ ] 纠纷处理：订单状态机特殊路径（强制终态），写 admin_oper_log + M8 双发消息
- [ ] 封禁：SysUser.isBanned 字段 + JwtAuthenticationFilter 黑名单
- [ ] 单测 + 越权 + e2e（含强制终态路径）

### M10 数据统计、可视化与导出（D 旁路径，预写骨架 + M9 后收口）

- [ ] **M10 设计评审**：接口表（dashboard / trend / rank / export）/ 数据流（聚合 SQL + 缓存策略 + EasyExcel）/ 状态机（无）/ 验收清单 6 条
- [ ] **M10 ADR-0003**：缓存策略确定
- [ ] Controller: `StatisticsController`（预写骨架用 M4 数据先跑通）
- [ ] SQL 聚合：`OrderStatisticsMapper`（订单量、完成率、漏服率、陪诊员排行）
- [ ] EasyExcel 导出：`ExportService`（按当前筛选条件导出订单 / 用户）
- [ ] M9 完成后 D 补：审核维度、封禁维度、纠纷维度
- [ ] 单测 + 越权 + e2e（含 1 万订单 < 2s 性能验证）

### M12 测试与质量保障（C 横切 + B 配合）

- [ ] JaCoCo 覆盖率门禁 W11 上线：Service ≥ 60%
- [ ] 4 角色 × 3 类接口越权矩阵扩到全部模块
- [ ] JMeter 压测脚本归档：M4（50 并发抢单）、M5（WS 重连）、M6（并发任务生成）
- [ ] 三个迭代测试报告归档（迭代二 / 三 / 五）
- [ ] 遗留 Bug 清单 P0/P1 W16 前清零

### M13 部署与交付物（D 主导 + C 配合）

- [ ] **M13 ADR-0004**：Docker 兜底策略确定
- [ ] 优先尝试 Docker Desktop 安装（环境 P0 缺口）
  - [ ] 若成功：编写 `Dockerfile` + `docker-compose.yml` + `deploy.sh`
  - [ ] 若失败：保留文件照写 + 用本机 `java -jar` + Nginx 兜底
- [ ] 部署文档：环境要求 / 启动步骤 / FAQ
- [ ] 用户手册：4 角色 × 5 张截图
- [ ] 演示视频：每迭代一段（3 段）
- [ ] 答辩 PPT：每迭代一份（3 份）
- [ ] W16 前 24 小时全链路演练

---

## 9 · 风险与兜底

| 编号 | 风险 | 触发条件 | 兜底 | 责任 |
|---|---|---|---|---|
| R-M4 | 50 并发抢单压测不过 | MySQL 乐观锁性能 / 索引未命中 | 检查 `companion_order` 索引 + 调大连接池 | B |
| R-M5 | WebSocket 鉴权复杂，复用 JwtAuthenticationFilter 模式遇阻 | 握手时机不支持 | 退路：握手 URL 带 token（不安全但可跑） | B / ADR-0001 |
| R-M6 | Redis 锁在 Windows 移植版行为差异 | SET NX PX 兼容性 | Lua 脚本手动释放 + 看门狗 | D / ADR-0002 |
| R-M9 | 纠纷处理强制终态破坏状态机不变量 | 业务边界没理清 | M9 设计评审时拉 B + D 一起过 | B |
| R-M10 | 1 万订单聚合 > 2s | 索引 + 缓存策略 | 先上 SQL 索引优化，不行再上物化视图 | D / ADR-0003 |
| R-M13 | Docker Desktop 安装受阻（WSL2 / Hyper-V 权限） | 管理员权限 / BIOS 虚拟化未开 | 本机 `java -jar` + Nginx 演示 + 录屏兜底 | D / ADR-0004 |
| R-env | Chrome / JMeter / PlantUML 未装 | 用户未授权安装 | Edge 代替 Chrome；JUnit5 并发压测代替 JMeter 临时；PlantUML 用 IDEA 插件 | B |

---

## 10 · 里程碑与同步节奏

| 节奏 | 动作 | 工具 |
|---|---|---|
| 每个模块完成 | 合并到 `develop`（完成即合并，不等周五） | `git` + GitHub PR |
| 每周五 22:00 | 周报 + 集成分支清理 + develop fast-forward 主分支 | `git` + 群消息 |
| 每天 22:00 | daily standup：今天做了什么 / 遇到什么问题 / 明天计划 | 群消息（计划书 §开发规范） |
| 每个模块开工前 | `dsh-plan` 1 页设计评审，存 `docs/agents/designs/` | 技能 + PR 描述 |
| 每个 ADR 决策点 | 写 ADR + 在 PR 描述里引用 `docs/adr/NNNN-*.md` | Git |
| 关键路径 B 阻塞 ≥ 1 天 | 升群同步 + 拉 D/C 协调 | 群 |

---

## 11 · 已决策的开放项（执行中可能再 grill）

| 项 | 决策 | 重审时机 |
|---|---|---|
| WebSocket 鉴权方式 | 默认 STOMP `CONNECT` 帧 `Authorization` header（复用 JwtAuthenticationFilter） | M5 设计评审时确认 |
| Redis 分布式锁 | 默认手写 SET NX PX + Lua | M6 设计评审时确认 |
| 统计缓存策略 | 默认不缓存 + 索引优化（验收 1 万 < 2s 是 SQL 层目标） | M10 设计评审时确认 |
| Docker 兜底 | 默认先尝试安装，受阻则本机 `java -jar` 兜底 | M13 设计评审时确认 |
| 分支重命名 | 默认 `feature/M4-order`（替代过时的 `feature/M2-auth`） | M4 commit 前 |

---

## 12 · 配套文件清单（待 M3+ 开工时创建）

| 路径 | 用途 | 创建时机 |
|---|---|---|
| `docs/agents/PLAN_BACKEND.md` | **本文档** | ✅ 已建 |
| `docs/agents/designs/M4-order.md` | M4 设计评审 | M4 commit 前 |
| `docs/agents/designs/M5-execution.md` | M5 设计评审 | M5 开工前 |
| `docs/agents/designs/M6-medication.md` | M6 设计评审 | M6 开工前 |
| `docs/agents/designs/M7-review.md` | M7 设计评审 | M7 开工前 |
| `docs/agents/designs/M8-message.md` | M8 设计评审 | M8 开工前 |
| `docs/agents/designs/M9-admin.md` | M9 设计评审 | M9 开工前 |
| `docs/agents/designs/M10-statistics.md` | M10 设计评审 | M10 开工前 |
| `docs/agents/designs/M13-deploy.md` | M13 设计评审 | M13 开工前 |
| `docs/adr/0001-m5-websocket-auth.md` | M5 WebSocket 鉴权 ADR | M5 开工前 |
| `docs/adr/0002-m6-redis-lock.md` | M6 Redis 锁 ADR | M6 开工前 |
| `docs/adr/0003-m10-stats-cache.md` | M10 缓存策略 ADR | M10 开工前 |
| `docs/adr/0004-m13-docker-fallback.md` | M13 Docker 兜底 ADR | M13 开工前 |

---

> 📌 **核心一句话**：本计划覆盖 W3–W17 共 15 周，B 主路径（M4→M5→M7→M9）+ D 旁路径（M8→M6→M10→M13）双线并行；每个模块交付 = 5 件套；M5/M6/M10/M13 在开工前再 grill 一次并落 ADR。
