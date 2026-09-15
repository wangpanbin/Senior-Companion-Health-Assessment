# ADR 0004 · M13 Docker 部署兜底策略

- **状态**：待填（PROPOSED）
- **日期**：TBD
- **决策者**：D + C
- **关联模块**：M13 部署与交付物
- **关联文档**：`plan.md §4.3-① §4.4-①`、`docs/agents/PLAN_BACKEND.md §11`

## 背景（待填）

M13 一键部署需要 `Dockerfile` + `docker-compose.yml` + `deploy.sh`。

**环境缺口（plan.md §4.3-①）**：本机未安装 Docker Desktop，无 WSL2/Hyper-V 启用记录。

**风险（R6）**：竞讲环境兜底；Docker 安装受阻（WSL2 需管理员权限 + 重启 + BIOS 虚拟化开启）。

## 候选方案（待填）

1. **强装 Docker Desktop**
   - 步骤：BIOS 启用虚拟化 → 启用 WSL2 → 安装 Docker Desktop
   - 优点：完整方案，答辩可演示 `docker compose up -d`
   - 缺点：可能需要重启 + BIOS 配置 + 管理员权限；不可控

2. **保留 Docker 文件，本机启动兜底**
   - 步骤：`Dockerfile` / `docker-compose.yml` / `deploy.sh` 文件照写（commit 进 git，答辩展示用），实际部署用本机：
     - `java -jar backend.jar`
     - 本机 MySQL 8.0 + Redis 已在运行
     - Nginx 反代托管前端 `dist/`
   - 优点：保证 W17 答辩时 100% 可演示
   - 缺点：缺真实 docker-compose 演示

3. **Docker Toolbox（旧方案）**
   - 用 VirtualBox 启动 Linux VM
   - 不推荐：性能差；已 deprecated

4. **云服务器远程演示**
   - 用阿里云学生机 + Docker
   - 优点：与本机无关；演示环境干净
   - 缺点：plan.md §4.3-⑦ 标 P3 可选；需要额外费用

## 决策（待填）

TBD（**默认候选 2 + 候选 1 并行：先写所有 Docker 文件，同时尝试本机安装**）

## 后果（待填）

- 文件 100% 提交：`Dockerfile`（多阶段构建：builder + runtime）+ `docker-compose.yml`（含 MySQL + Redis + 后端健康检查）+ `deploy.sh`（幂等）
- 本机兜底脚本：`deploy-local.sh`（启 MySQL/Redis 检查 + 后端 jar + Nginx 配置）
- 部署文档：先讲 Docker 方案，再附"若无 Docker 走 deploy-local.sh"分支
- 演示视频：录一段本机启动全过程（兜底方案）

## 验证（待填）

- **若 Docker 安装成功**：
  - 新机器按部署文档 3 步内拉起完整系统
  - `docker compose ps` 三个服务健康
- **若安装受阻**：
  - 本机启动同样达到 3 步内
  - 答辩现场用录屏兜底

## 相关 ADR

- `0001-m5-websocket-auth.md`（无直接关联）
- `0002-m6-redis-lock.md`（无直接关联）
- `0003-m10-stats-cache.md`（无直接关联）

## 环境约束来源

- `plan.md §4.3-①`：Docker 是 P0 缺口
- `plan.md §4.4-①`：Docker 安装受阻的退路方案已写明（本机 java -jar + Nginx）
