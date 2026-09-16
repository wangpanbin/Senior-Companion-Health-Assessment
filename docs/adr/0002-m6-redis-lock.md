# ADR 0002 · M6 Redis 分布式锁方案

- **状态**：已接受（ACCEPTED）
- **日期**：2026-09-16（本 ADR 回填；决策随 M6 实现一同落地）
- **决策者**：D（B 复核）
- **关联模块**：M6 用药管理与漏服提醒
- **关联文档**：`docs/api/05-medication.md`、`docs/agents/PLAN_BACKEND.md §11`、
  `backend/src/main/java/org/company/nianglin/util/RedisLockUtil.java`、
  `backend/src/main/java/org/company/nianglin/task/MedicationScheduler.java`

---

## 背景

M6 有两个定时任务在多实例部署时必须单实例执行（plan.md M6 验收：「并发启动 2 个后端实例，定时任务仅 1 个实例实际执行（日志可证）」）：

1. **每日 07:00**：`MedicationScheduler.generateDailyTasks` 生成当日 `medication_task`
2. **每 30 分钟**：`MedicationScheduler.scanMissedTasks` 扫描漏服任务

本机 Redis 是 Windows 移植版（环境风险 §4.4-② 明确要求：**不要依赖 Redisson 高级特性**）。

关键约束是一个**不对称性**：这两个任务对「重复」的容忍度远高于「丢失」——
生成任务若漏执行，当天就没有待服任务、老人不知道该吃药（不可接受）；
而漏服扫描漏执行一次只是晚 30 分钟通知（下一次扫描会补上）。
所以锁要满足的策略是：**宁可不执行，也绝不并发执行**。

---

## 候选方案

### 方案 1：手写 `SET NX PX` + Lua 释放脚本（**采用**）

- 加锁：`SET key <uuid> NX PX <ttl>`
- 释放：`EVAL "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"`
- 优点：无新增依赖；实现透明可控；完全符合「Windows 移植版 Redis」的约束
- 缺点：需自己写工具类；无自动续期

### 方案 2：Redisson 客户端（**未采用**）

- 优点：成熟、看门狗自动续期、可重入
- **未采用理由**：新增依赖；其高级特性（`RLock`、看门狗续期、非 Lua 脚本通道）在 Windows 移植版 Redis 上行为未经验证，与 §4.4-② 的约束直接冲突

### 方案 3：Spring Integration `RedisLockRegistry`（**未采用**）

- 优点：Spring 原生；中等复杂度
- **未采用理由**：抽象层较厚，配置成本高于收益；本项目锁的用途单一，用不上它的注册表能力

### 方案 4：数据库唯一索引兜底（**采用，与方案 1 配合**）

- `medication_task` 表建唯一索引 `uk_plan_time(plan_id, plan_time)`
- 即使锁失效，并发生成也会因唯一约束失败，不会产生重复行
- 定位：**第二道防线**，不是替代锁 —— 唯一索引只能挡住「插入重复」，挡不住「两个实例都跑一遍扫描」

---

## 决策

**采用方案 1 + 方案 4**：手写 `SET NX PX` + Lua 释放，叠加数据库唯一索引 `uk_plan_time` 兜底。

### 关键实现点（见 `RedisLockUtil` javadoc）

1. **释放必须用 Lua 比对 uuid**
   直接 `DEL` 会删掉别人的锁：A 拿锁 → A 任务超时 → 锁自动过期 → B 拿锁 → A 回来 `DEL` 删掉的是 **B 的锁**，
   之后 C 也能拿到锁，临界区里同时有两个实例。比对 uuid 后，A 只在「锁还是我的」时才删。

2. **刻意不做锁续期**
   任务执行时间远小于锁 TTL（默认 5 分钟），而续期逻辑一旦写错代价是死锁 —— 得不偿失。

3. **加锁不自旋等待**
   定时任务的重叠不是「需要排队」，而是「另一个实例正在跑，这次跳过即可」。

4. **try-with-resources 句柄**
   `RedisLockUtil.tryLock(...)` 返回 `Lock implements AutoCloseable`，
   任何异常路径都会走到释放；`lock.acquired()` 为 `false` 时直接 `return`。

5. **Redis 不可用时按「未取到」处理并放行**
   读锁异常时记 ERROR 但不抛：任务本身幂等，且「因为 Redis 挂了就完全不生成服药任务」
   比「可能重复生成（有唯一索引兜底）」糟糕得多。

6. **TTL 与扫描间隔的关系**
   漏服扫描锁 TTL 5 分钟 < 扫描间隔 30 分钟，保证下一轮能正常抢到锁；
   生成任务锁 TTL 10 分钟，留足跨天补跑余量。

---

## 后果

### 代码产物

- `util/RedisLockUtil.java`：静态工具类（非 Spring Bean），`tryLock(StringRedisTemplate, biz, Duration)` + `Lock` 句柄。
  **注：本 ADR 早期草稿写作 `RedisLockTemplate`，实际落地类名为 `RedisLockUtil`。**
- 锁 key 统一经 `RedisKeyConstants.lock(biz)` 加前缀，避免与其他业务 key 冲突。
- 定时任务入口（`MedicationScheduler`）：
  ```java
  try (RedisLockUtil.Lock lock = RedisLockUtil.tryLock(redisTemplate, LOCK_DAILY, Duration.ofMinutes(10))) {
      if (!lock.acquired()) { return; }
      int created = medicationService.generateDailyTasks(today);
  }
  ```
- 任务方法内自接异常：`@Scheduled` 抛出未捕获异常会打断当前调度轮次，让「下次 30 分钟后再来」变得不确定。

### 数据库产物

- 唯一索引 `uk_plan_time(plan_id, plan_time)` **已在 `V1__init_schema.sql` 建表时定义**
  （见该文件 `medication_task` 段注释）。
  **注：本 ADR 早期草稿预留的 `V4__medication_unique_index.sql` 并未创建，也不需要 —— 索引从一开始就在 V1 里。**

### 已知局限

- 无自动续期：若任务执行时间超过 TTL，锁会在任务中途失效（当前任务为秒级，TTL 分级设置后风险可忽略）。
- 依赖本机时钟：锁 TTL 由 Redis 侧计时，不受应用时钟影响。

---

## 验证

### 自动化验证（e2e）

`backend/sql/tools/e2e_medication.py` D 段（幂等约束与合规扫描）：

| 用例 | 断言 | 说明 |
|---|---|---|
| D1 | `uk_plan_time` 索引存在且列为 `plan_id, plan_time` | 查 `information_schema.STATISTICS` 确认约束真实存在，而非纸面约定 |
| D2 | 重复插入同一 `(plan_id, plan_time)` 被数据库拒绝（`Duplicate entry`） | 证明第二道防线挡得住 |

### 待补验证（多实例）

- 启动 2 个后端实例（不同 `server.port`），观察日志只有 1 个实例生成任务 ——
  **当前未自动化**，建议纳入 M12 横切测试（见 `AUDIT_BACKEND_2026-09-16.md` P1 待办）。

---

## 相关 ADR

- `0001-m5-websocket-auth.md`（无直接关联）
- `0003-m10-stats-cache.md`（共用 Redis，但用途不同：本 ADR 用锁，该 ADR 决策**不用**缓存）
- `0004-m13-docker-fallback.md`（无直接关联）

---

## 环境约束来源

`plan.md §4.4-②`：本机 Redis 是 Windows 移植版，无法真正 fork，**不要依赖 Redisson 高级特性**。
