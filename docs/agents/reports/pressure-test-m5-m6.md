# M5 / M6 并发压测记录

> 执行时间：2026-09-16　执行人：小斌
> 环境：Windows 11 / JDK 21 / MySQL 8.0 本机 / Redis 8.8 本机 / JMeter 5.6.3
> 被测：`backend` 以 `mvn spring-boot:run --server.port=8080` 单实例运行
> 脚本：`backend/sql/tools/jmeter_m5_reconnect.jmx`、`jmeter_m6_concurrent_confirm.jmx`
> 夹具：`backend/sql/tools/jmeter_fixture.py`（快照 → 跑 → 核对 → 还原）

---

## 0 · 一句话结论

**两个计划均实测通过：M5 重连补齐 300 请求 0 错误（447.8 req/s）；M6 同一服药任务 50 并发确认，
恰好 1 次生效、其余全部是可预期的业务失败，没有任何 500 —— 并发写入的条件更新防线成立。**

---

## 1 · M5 重连补齐风暴

### 场景

`docs/api/04-companion-execution.md` §6「实现要点」给了两条「断线重连不丢事件」的实现路径：

| 路径 | 做法 | 本项目 |
|---|---|---|
| A | 服务端为每个订单缓存最近 N 条事件 | ❌ 未采用 |
| B | 前端重连后先调 `/progress` 与 `/checkins` 补齐 | ✅ **采用** |

因此本计划压的是 B —— **50 个客户端同时重连、各自拉两个读接口**，
而不是一个并不存在的 `/checkin-missed` 端点
（`PLAN_BACKEND.md §M5` 里写过该端点，但接口文档从未定义它，实现也没做；
审计报告初版曾把它标成「端点存在」，属误报，已在 §4 更正）。

### 配置与结果

| 项 | 值 |
|---|---|
| 线程数 | 50（ramp-up = 0，真正的同时） |
| 循环 | 3（每轮 2 个请求：`/progress` + `/checkins`） |
| 账号 | 种子 `fam001`（订单 1001 的下单家属） |
| 样本数 | **300**（150 + 150） |
| 耗时 | 00:00:01 |
| 吞吐 | **447.8 req/s** |
| 平均 / 最小 / 最大 | 75 ms / 5 ms / 206 ms |
| 错误 | **0（0.00%）** |

断言口径：每个请求同时断言 `HTTP 200` **且** `$.code == 200`。
只断言 HTTP 会被业务错误骗过去 —— 令牌过期、归属不符时 HTTP 可能仍是 200
（项目约定业务错误走 HTTP 200 + `code`），那样「重连风暴里一半人拿不到数据」会被误判成成功。

---

## 2 · M6 并发确认服药

### 场景

同一服药任务被 50 个请求同时确认（用户狂点 / 客户端重发 / 多端同开）。
**必须只有 1 个成功**：M6 用「`UPDATE ... WHERE status = 期望值`」的条件更新保证原子性，
唯一索引作为并发穿透的最后防线。

### 靶子与夹具

种子任务 `medication_task.id = 20002`（老人 401 / 计划 10001，家属 101 有绑定关系）。

> 跑之前的实际状态是 `MISSED`（漏服扫描器已经跑过），
> 这正好顺带覆盖了「漏服后补记」这条路径 —— 结果 `was_missed = 1` 印证了这一点。

### 配置与结果

| 项 | 值 |
|---|---|
| 线程组 1 | 50 线程 × 1 轮 → `POST /api/medication/task/20002/confirm` |
| 线程组 2 | 20 线程 × 5 轮 → `GET /api/medication/task/calendar?elderId=401&…` |
| 账号 | 种子 `fam001` |
| 样本数 | **150**（50 确认 + 100 日历） |
| 吞吐 | 363.2 req/s（第二轮）／309.3 req/s（首轮） |
| 平均 / 最小 / 最大 | 47 ms / 16 ms / 99 ms |
| 错误 | **0（0.00%）** |

### 结论核对

断言写成 `HTTP 200` **且** `$.code ∈ {200, 5003, 409}`：

| code | 含义 | 出现次数 |
|---|---|---|
| 200 | 本次抢到 | **1** |
| 5003 | 已确认（先到者已提交） | 49 |
| 409 | 唯一约束冲突（两个请求同时通过「未确认」检查，由数据库兜底） | 0 |
| 500 | 系统异常 = 防线被击穿 | **0** |

回库核对（`python jmeter_fixture.py verify`）：

```
任务 20002 现状：20002 | TAKEN | 101 | 2026-09-16 10:22:19 | 1
✓ 恰好一次确认生效：status=TAKEN、confirm_by=101
```

即：`status` 只前进了一步、`confirm_by` 是当前登录家属（不是前端伪造的）、
`was_missed = 1` 说明补记被正确标记为「漏服后补记」（漏服率统计仍需计为漏服）。

> **为什么「成功 1 条」不写成 JMeter 断言**：一旦断言绑定「必须恰好 1 个成功」，
> 计划就不可重跑（第二次跑时任务已被确认，必然 0 个成功）。
> 可重跑的写法是「只断言没有意外码」，把「恰好一次」交给回库核对。

---

## 3 · 夹具的可重跑性

压测会改数据，所以 `jmeter_fixture.py` 走「快照 → 跑 → 还原」：

```
python jmeter_fixture.py setup     # 快照任务 20002 + 登录 fam001 写令牌到文件
jmeter -n -t jmeter_m6_concurrent_confirm.jmx -Jtoken=<文件内容> -JtaskId=20002 -JelderId=401 -l m6.jtl
python jmeter_fixture.py verify    # 回库看结论
python jmeter_fixture.py restore   # 按快照还原（只回写 status/confirm_*/was_missed/notify_*）
```

实测还原后行内容与快照逐列一致（`MISSED | NULL | NULL | NULL | 0 | 1 | 2026-09-16 00:35:26`）。
**压测不在种子数据上留痕**，别人复现时看到的是同一个起点。

---

## 4 · 本次压测顺带发现/更正的三件事

| # | 事项 | 说明 |
|---|---|---|
| 1 | 审计报告误标 `/checkin-missed` 为「端点存在」 | 该端点既未在接口文档定义、也未实现；重连补齐实际走 `/progress` + `/checkins`（接口文档明确允许）。已在 `AUDIT_BACKEND_2026-09-16.md` 更正为 ⚠️，并同步 `designs/M5-execution.md` |
| 2 | jtl 里拿不到业务 code | `-l` 参数生成的 jtl 由全局 `jmeter.save.saveservice.*` 决定列，计划内监听器的 `saveConfig` 不生效；`-Jjmeter.save.saveservice.response_data=true` 在本机 5.6.3 上也无效。结论核对改为回库，已写入计划注释与 `sql/tools/README.md` 坑表 11 |
| 3 | `medication_task` 的备注列是 `confirm_remark` | 首版夹具脚本按 `remark` 写，MySQL 直接报 `Unknown column`。已写入坑表 12（写 SQL 前先 `SHOW COLUMNS`） |

---

## 5 · 复现步骤（完整）

```bash
# 0. 前提：MySQL / Redis 已起，MYSQL_PASSWORD 环境变量已设
cd backend
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080"

# 1. M5
cd sql/tools
python jmeter_fixture.py setup
jmeter -n -t jmeter_m5_reconnect.jmx -Jtoken=<jmeter_token.txt> -JorderId=1001 -l m5.jtl
#   期望：300 样本 / Err 0.00%

# 2. M6
jmeter -n -t jmeter_m6_concurrent_confirm.jmx -Jtoken=<jmeter_token.txt> -JtaskId=20002 -JelderId=401 -l m6.jtl
#   期望：150 样本 / Err 0.00%
python jmeter_fixture.py verify      # 期望：TAKEN + confirm_by=101
python jmeter_fixture.py restore     # 还原

# 3. 收尾：停掉 8080 上的服务，不留后台进程
```

---

## 6 · 未覆盖的范围（诚实交代）

| 项 | 状态 | 原因 |
|---|---|---|
| WebSocket 握手/推送本身的并发压测 | ❌ 未做 | JMeter 核心没有 WebSocket 支持，需要额外的 WebSocket Samplers 插件；本机未装。M5 真正容易被压垮的是重连后的 HTTP 补齐（已覆盖） |
| M6 定时任务（`MedicationScheduler` / `MissedDoseScanner`）的并发 | ❌ 未做 | 它们是服务端 `@Scheduled` 任务，触发方式不是 HTTP；用 Redis 锁保证多实例只有一个执行，属单元测试与日志核验范畴（`Task(定时)` 包覆盖率 4.35%，见审计报告 §7） |
| 长时间稳定性（内存/连接泄漏） | ❌ 未做 | 单轮 1 秒级压测回答不了这个问题 |
| 前端页面渲染性能 | ❌ 不适用 | 一期不推进前端 |
