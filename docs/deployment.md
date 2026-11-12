# 银龄伴诊 · 部署文档

> **适用范围**：课程验收场景的三种形态 —— ① 本机开发部署（dev，必考）、
> ② 生产形态部署（prod，fail-closed 配置核验）、③ AI 助手服务部署。
> 启动命令与 [`../README.md`](../README.md)「快速开始」同源，本文只做**部署视角**的收口。
>
> **Docker 一键部署说明**：计划书 §14 把「Docker + docker-compose + deploy.sh」列为技术亮点，
> 但本机（Windows 11）未安装 Docker，**未经实跑验证的 compose 交付物在验收现场第一次
> `up` 大概率翻车**，因此本轮**刻意不交付** Docker 资产（决策记录见 `docs/reports/验收报告.md` §3.0 与 `CHANGELOG.md`），
> 如需容器化建议列入二期并配套实测验证。

---

## 1 · 环境要求

| 组件 | 版本 | 用途 | 说明 |
|---|---|---|---|
| JDK | **21** | 后端 | `pom.xml` `<java.version>21</java.version>`；本机装有 8/11/17/21/25 多版本时注意 `JAVA_HOME` 指向 21 |
| Maven | 3.6+ | 构建 | 建议配置阿里云镜像（`~/.m2/settings.xml`），否则首次拉依赖极慢 |
| Node.js | **22.10+** | 前端 | `package.json` `engines.node` 下限；低版本 pnpm 直接拒绝安装 |
| pnpm | 10+ | 前端 | lockfile 为 `pnpm-lock.yaml`，不要用 npm 重装 |
| MySQL | 8.0 | 数据库 | 字符集 **utf8mb4 / utf8mb4_general_ci**（硬约束） |
| Redis | 5+ | 缓存 / 锁 / 验证码 | Windows 移植版可用；以 `redis-cli ping` → `PONG` 为准判断存活 |
| Python | 3.12 | AI 助手（可选） | 依赖清单 `ai-service/requirements.txt` 已人工批准冻结 |

端口：MySQL 3306 · Redis 6379 · 后端 8080 · 助手 8081 · 前端 5141。

---

## 2 · 形态一：本机开发部署（dev，演示 / 跑测用）

### 2.1 一次性准备

```powershell
# ① 建库（utf8mb4 硬约束）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS nianglin DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

# ② 配置数据库口令（三选一，详见 README「配置数据库口令」）
#    推荐：backend/src/main/resources/application-local.yml（已被 .gitignore 排除）
$env:MYSQL_USERNAME = 'root'
$env:MYSQL_PASSWORD = '<本机 root 口令>'

# ③ 验证 Redis 真实存活（Windows 服务状态不可信）
redis-cli ping          # 期望 PONG；不是则启动 redis-server.exe --port 6379
```

表结构与种子数据由 **Flyway** 在后端启动时自动迁移（脚本真源 `backend/sql/`，
**不要**手工 `mysql < V*.sql` 后又手工 DROP 表 —— 会造成「迁移历史说有、实物没有」）。

### 2.2 五个进程的启动顺序

| # | 进程 | 命令 | 健康判据 |
|---|---|---|---|
| 1 | MySQL | Windows 服务 `MySQL80` | `mysql -u root -p -e "SELECT 1"` |
| 2 | Redis | Windows 服务 / 独立进程 | `redis-cli ping` → `PONG` |
| 3 | 后端 | `$env:SPRING_PROFILES_ACTIVE='dev'` 然后 `mvn -Dspring-boot.run.profiles=dev spring-boot:run` | `GET http://localhost:8080/api/health` → `UP` |
| 4 | 前端 | `cd frontend; pnpm dev` | `http://localhost:5141` 可打开 |
| 5 | 助手（可选） | `cd ai-service; python -m uvicorn app.main:app --host 127.0.0.1 --port 8081` | `GET http://localhost:8081/healthz`；未启动时后端自动兜底 |

> ⚠️ **`spring.profiles.active` 刻意不给默认值**（`application.yml`）。
> 漏配 profile 的表现是「打完 banner 静默 exit 1、stderr 全空」，极难排查 ——
> 启动前务必确认 `SPRING_PROFILES_ACTIVE` 已设置。

一键起停（推荐）：

```powershell
# 脚本只负责 MySQL/Redis + 后端 8080；前端由 Playwright webServer 拉起，助手需按上表另行启动
.\tools\e2e\start-services.ps1      # 确保依赖服务在线并启动后端
python tools/e2e/l0_identity_gate.py  # 10 项服务身份闸门，必须 10/10
.\tools\e2e\stop-services.ps1        # 停止后端
```

### 2.3 部署后自检

1. `l0_identity_gate.py` **10/10**（确认 8080/8081/5141 上是本项目进程，不是别的服务）。
2. 四角色各登录一次（`admin` / `fam001` / `comp009` / `elder001`，口令见
   `backend/sql/tools/fixture_credentials.py`）。
3. 接口文档 `http://localhost:8080/doc.html` 可打开（dev 才开）。

---

## 3 · 形态二：生产部署（prod，fail-closed）

### 3.1 密钥与环境变量（缺失即启动失败，不是默认值兜底）

| 变量 | 说明 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | **必须**显式设为 `prod`；不设 = 静默退出 |
| `JWT_SECRET` | JWT 签名密钥 |
| `ID_CARD_AES_KEY` | 身份证加密密钥 |
| `DEFAULT_RESET_PASSWORD` | 管理员重置密码的初始值（首次登录强制改密） |
| `REDIS_PASSWORD` | Redis 口令 |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 数据库凭据 |
| `UPLOAD_DIR` | 附件落盘目录；**务必指到独立数据盘**（部署目录重装会连文件清掉） |
| `AI_SERVICE_URL` | 助手地址（默认 `http://127.0.0.1:8081`，可留空则后端兜底） |

prod profile 行为（配置审阅结论，见 `docs/security/`）：

- **Knife4j 关闭**（`doc.html` 不可达）；
- CORS 只放行占位的正式域名（需按实际域名修改）；
- SQL 明文日志关闭；
- 任一密钥缺失 → **启动失败**（fail-closed，宁可不起也不带默认密钥跑）。

### 3.2 部署步骤（Linux / Windows 通用）

```bash
# ① 构建（CI 同命令：测试与门禁已过再打包）
cd backend && mvn -B verify
mvn -B -DskipTests package            # 产物 target/nianglin-0.1.0-SNAPSHOT.jar

# ② 前端静态产物
cd frontend && pnpm install --frozen-lockfile && pnpm build   # 产物 dist/

# ③ 注入环境变量并启动（示例）
export SPRING_PROFILES_ACTIVE=prod
export JWT_SECRET=... ID_CARD_AES_KEY=... DEFAULT_RESET_PASSWORD=...
export REDIS_PASSWORD=... MYSQL_USERNAME=... MYSQL_PASSWORD=...
export UPLOAD_DIR=/data/nianglin/uploads
java -jar target/nianglin-0.1.0-SNAPSHOT.jar

# ④ 助手服务（可选，只监听 127.0.0.1）
cd ai-service && pip install -r requirements.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8081
```

- Flyway 随后端启动自动迁移；**迁移脚本只增不改**（`V*__*.sql` 一经发布冻结）。
- 前端 `dist/` 由任意静态服务器托管（nginx / IIS），需把 `/api`、`/ws` 反代到 8080。
  > ⚠️ 本课程环境**未实跑** Linux + nginx 形态（验收报告 §11 如实标注为未验证项）。
- 运行账号建议非 root；防火墙只放行 80 / 443，**8080/8081 不对公网开放**（助手尤其只能本机访问）。

### 3.3 运营参数优先级（部署后调参必读）

`sys_dict` 中 `dict_type='OPS_PARAM'` 且 `status=1` 的值**优先于** `application.yml`、
启动参数与环境变量。配错时先查字典，不要先怀疑配置文件：

```sql
SELECT dict_code, dict_value, status FROM sys_dict WHERE dict_type='OPS_PARAM';
```

---

## 4 · 数据备份与恢复

| 场景 | 手段 |
|---|---|
| 日常备份 | `mysqldump` 全库 + `UPLOAD_DIR` 附件目录 |
| E2E 跑测后恢复 | `python tools/e2e/fixture.py restore`（影子表逐列回写，19 表 + Redis 键回基线） |
| 按种子水位清理 | `python tools/e2e/restore_clean_baseline.py`（**删除操作，先看输出计划**） |
| 演示造数回滚 | `python presentation/scripts/seed-hall-demo.py --rollback`（只还原 `visit_time` 一列） |

> **测试态与演示态是同一个库的两种合法状态**：跑 `mvn -B verify` 前先 `--rollback`，
> 跑完演示再应用。详见 README「本机演示数据与跑测顺序」。

---

## 5 · 排障表

| 症状 | 根因 | 处理 |
|---|---|---|
| 后端打完 banner 就退出、无报错 | `SPRING_PROFILES_ACTIVE` 未设置（见 §2.2 ⚠️） | 显式设置 `dev` / `prod` |
| 启动报 `Access denied for user` | `MYSQL_PASSWORD` 还是占位串 `CHANGE_ME` | 按 README 三种方式之一注入真实口令 |
| 所有 `@SpringBootTest` 报 `Unable to connect to Redis` | Windows Redis 服务壳「Running」但进程已死 | `redis-cli ping` 验证；无响应则直接起 `redis-server.exe --port 6379` |
| `mvn verify` 有 2 条真实库测试红 | 库处于**演示态**（`visit_time` 被挪出断言窗口） | `python presentation/scripts/seed-hall-demo.py --rollback` 后重跑 |
| 陪诊大厅是空的 | 库处于**种子基线**态，待接单订单就诊时间在过去 | 演示前应用 `seed-hall-demo.py` |
| `doc.html` 404 | 以 prod profile 启动（Knife4j 生产关闭） | 属预期；本地调试用 dev |
| 前端页面能开但数据空白 | 后端 / 助手未起，或 `/api` 代理目标 8080 无进程 | 跑 `l0_identity_gate.py` 定位哪个进程缺席 |
| 测试栈帧混进 `nianglin-error.log` | surefire fork 与应用共写相对路径 `./logs` | 已知现象（非缺陷）；日志已配按天 + 50MB 分卷、30/60 天保留、总量 2GB 上限 |
| 运营参数改了不生效 | `sys_dict` OPS_PARAM 已有启用值压过配置文件 | 见 §3.3；停用字典行后环境变量才兜底 |

---

## 6 · 二期展望

- **Docker + docker-compose 一键起 MySQL/Redis/app**（计划书 §14 技术亮点）：
  落地时应包含 compose 编排、健康检查、`deploy.sh` 与一次真实的 `up → 冒烟 → down` 验证记录。
- Linux 反向代理（nginx）与 systemd 托管章节：待有真实服务器环境后补实测版。
