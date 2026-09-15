# ADR 0002 · M6 Redis 分布式锁方案

- **状态**：待填（PROPOSED）
- **日期**：TBD
- **决策者**：D
- **关联模块**：M6 用药管理与漏服提醒
- **关联文档**：`docs/api/05-medication.md`、`docs/agents/PLAN_BACKEND.md §11`

## 背景（待填）

M6 有两个定时任务在多实例部署时必须单实例执行（plan.md M6 验收："并发启动 2 个后端实例，定时任务仅 1 个实例实际执行（日志可证）"）：

1. **每日 07:00**：`MedicationTaskGenerator` 生成当日 `medication_task`
2. **每 30 分钟**：`MissedDoseScanner` 扫描漏服任务

本机 Redis 是 Windows 移植版（环境风险 4.4-② 明确要求：**不要依赖 Redisson 高级特性**）。

## 候选方案（待填）

1. **手写 Redis SET NX PX + Lua 释放脚本**
   - 加锁：`SET lockKey instanceId NX PX 30000`
   - 释放：`EVAL "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"`
   - 优点：无新依赖；控制力强；符合项目环境约束
   - 缺点：需要自己写 `RedisLockTemplate` 工具类

2. **Redisson 客户端**
   - 优点：成熟、看门狗自动续期、可重入锁
   - 缺点：新增依赖；高级特性（`RLock`、Redisson Fair Lock）在 Windows 移植版行为未验证

3. **Spring Integration RedisLockRegistry**
   - 优点：Spring 原生；中等复杂度
   - 缺点：抽象层较厚；配置较复杂

4. **数据库唯一索引兜底**（与锁配合）
   - `medication_task` 表加唯一索引 `(elder_id, plan_id, plan_date)`
   - 即使锁失效，并发生成也会因唯一约束失败
   - 作为兜底，与锁方案 1/2/3 任一配合

## 决策（待填）

TBD（**默认候选 1：手写 SET NX PX + Lua + 数据库唯一索引兜底**）

## 后果（待填）

- 新增 `util/RedisLockTemplate.java`：封装加锁 / 释放 / 看门狗（如需要）
- `medication_task` 表加唯一索引（DDL V4__medication_unique_index.sql）
- 定时任务入口：
  ```java
  boolean locked = redisLock.tryLock("medication:gen:" + LocalDate.now(), 60_000);
  if (!locked) return;
  try { ... } finally { redisLock.unlock(); }
  ```
- 测试：
  - e2e_medication.py：手动触发两次生成任务，验证不重复
  - 启动 2 个后端实例（不同端口），观察日志只有 1 个实例生成任务

## 验证（待填）

- 单元测试：mock RedisLockTemplate，验证 tryLock / unlock 行为
- 集成测试：手动启 2 个 Spring Boot 实例（不同 server.port），同时触发
- 验收：M6 plan.md 验收项第 4 条「并发启动 2 个后端实例，定时任务仅 1 个实例实际执行」

## 相关 ADR

- `0001-m5-websocket-auth.md`（无直接关联）
- `0003-m10-stats-cache.md`（共用 Redis，但用途不同）
- `0004-m13-docker-fallback.md`（无直接关联）

## 环境约束来源

`plan.md §4.4-②`：本机 Redis 是 Windows 移植版，无法真正 fork，**不要依赖 Redisson 高级特性**。
