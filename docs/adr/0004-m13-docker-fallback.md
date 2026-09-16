# ADR 0004 · M13 Docker 部署兜底策略

- **状态**：已搁置（DEFERRED）
- **日期**：2026-09-16（回填状态）
- **决策者**：D + C
- **关联模块**：M13 部署与交付物
- **关联文档**：`plan.md §4.3-① §4.4-①`、`docs/agents/PLAN_BACKEND.md §0 §11`

---

## 状态说明（为什么是 DEFERRED 而不是 ACCEPTED）

**2026-09-16 决策**：M13「部署与交付物」现阶段不纳入交付范围（详见 `PLAN_BACKEND.md §0`），
因此本 ADR **暂缓裁决**，而非「已决策但未实施」。

搁置的理由：

| 项 | 说明 |
|---|---|
| Dockerfile / compose / deploy.sh | 课程验收在本机演示，容器化不产生验收价值 |
| 部署文档 / 用户手册 | 无部署动作、无前端界面，文档没有使用对象 |
| 答辩 PPT / 演示视频 | W17 才使用，现在做会随代码变动反复返工 |

**替代兜底**：答辩演示改用本机 `mvn spring-boot:run` + Knife4j 接口文档（`/doc.html`），
不依赖容器与前端页面。**该兜底不产生任何待建文件，故本 ADR 也不需要决策。**

**重启条件**：若后续决定交付 Docker 化部署，本 ADR 的候选方案与倾向性结论（候选 2 + 候选 1 并行）
仍然有效，可直接转为 ACCEPTED 并落地。

---

## 背景

M13 一键部署需要 `Dockerfile` + `docker-compose.yml` + `deploy.sh`。

**环境缺口（plan.md §4.3-①）**：本机未安装 Docker Desktop，无 WSL2/Hyper-V 启用记录。

**风险（R6）**：竞讲环境兜底；Docker 安装受阻（WSL2 需管理员权限 + 重启 + BIOS 虚拟化开启）。

---

## 候选方案（保留，供重启时参考）

### 方案 1：强装 Docker Desktop

- 步骤：BIOS 启用虚拟化 → 启用 WSL2 → 安装 Docker Desktop
- 优点：完整方案，答辩可演示 `docker compose up -d`
- 缺点：可能需要重启 + BIOS 配置 + 管理员权限；不可控

### 方案 2：保留 Docker 文件，本机启动兜底

- 步骤：`Dockerfile` / `docker-compose.yml` / `deploy.sh` 文件照写（commit 进 git，答辩展示用），
  实际部署用本机：`java -jar backend.jar` + 本机 MySQL 8.0 + Redis + Nginx 反代
- 优点：保证 W17 答辩时 100% 可演示
- 缺点：缺真实 docker-compose 演示

### 方案 3：Docker Toolbox（旧方案）

- 用 VirtualBox 启动 Linux VM
- 不推荐：性能差；已 deprecated

### 方案 4：云服务器远程演示

- 用云主机 + Docker
- 优点：与本机无关；演示环境干净
- 缺点：plan.md §4.3-⑦ 标 P3 可选；需要额外费用

---

## 倾向性结论（未生效）

**若重启 M13**：采用方案 2 + 方案 1 并行 —— 先写全部 Docker 文件，同时尝试本机安装。

预期产物（重启后需创建）：`Dockerfile`（多阶段 builder + runtime）、
`docker-compose.yml`（MySQL + Redis + 后端健康检查）、`deploy.sh`（幂等）、`deploy-local.sh`（本机兜底）。

---

## 相关 ADR

- `0001-m5-websocket-auth.md`（无直接关联）
- `0002-m6-redis-lock.md`（无直接关联）
- `0003-m10-stats-cache.md`（无直接关联）

---

## 环境约束来源

- `plan.md §4.3-①`：Docker 是 P0 缺口
- `plan.md §4.4-①`：Docker 安装受阻的退路方案已写明（本机 `java -jar` + Nginx）
