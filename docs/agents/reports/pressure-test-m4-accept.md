# M4 抢单 50 并发压测报告（2026-09-28）

## 1 · 场景

- **被测接口**：`POST /api/order/{id}/accept`（接单，乐观锁防超卖 —— M4 最重要的验收点）
- **并发模型**：50 线程（ramp-up=0 瞬时齐发）× 1 循环，同抢**同一张** PENDING 订单
- **靶子订单**：`1001`（V2 种子，PENDING / 无陪诊员）
- **令牌**：`bench_tokens.csv` 50 个真实登录 accessToken（由 `backend/sql/tools/bench_setup.py` 签发，30 个临时陪诊员 5001-5030 + 20 个种子陪诊员）
- **脚本**：`backend/sql/tools/jmeter_m4_accept.jmx`（JMeter 5.6.3，`JMETER_HOME=F:\software\apache-jmeter-5.6.3`）

## 2 · 结果（双条件判据：EXIT=0 **且** JTL 行数 > 1）

| 指标 | 数值 |
|---|---|
| 退出码 | EXIT=0 |
| JTL 行数 | 50（表头外 50 条样本） |
| **成功（抢到）** | **1** |
| **失败（未抢到）** | **49** |
| 平均响应 | 80 ms |
| 最小 / 最大 | 68 ms / 100 ms |
| 吞吐（JMeter 汇总） | 153.4/s |

`success=1 / fail=49` 即验收期望的 **1/49**：50 个并发请求里乐观锁恰好放行 1 个赢家，
其余 49 个拿到 `3003`（已被抢先）——库中该单 `companion_id` 落定为唯一赢家，`version` +1。

断言口径（铁律 3）：业务失败也是 HTTP 200，所以脚本里除了 HTTP 状态码断言外，
新增了**业务码断言**（`"code":200` 含于响应体）。只看 HTTP 200 会把 49 个失败全部记成"成功"。

## 3 · 复现命令

```powershell
# 0. 前置：后端已启动（/api/health 200）；JMETER_HOME 已设置
#    不要用 jmeter --version 的输出完整性当健康检查（Java 21 下 stdout 瑕疵，BASELINE §二之二）

# 1. 夹具：重新签发 50 个新鲜令牌（旧 CSV 令牌会因密码版本 bump 而 401）
cd backend\sql\tools
python bench_setup.py

# 2. 复位靶子订单
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p"$env:MYSQL_PASSWORD" nianglin `
  -e "UPDATE companion_order SET status='PENDING', companion_id=NULL, accept_time=NULL, version=0 WHERE id=1001;"

# 3. 跑压测（注意：jmx 属性名是小写 orderId，-JORDER_ID 不生效——会被默认值遮蔽）
jmeter -n -t jmeter_m4_accept.jmx -JorderId=1001 `
  -l jmeter_m4_results.jtl `
  -j jmeter_m4_run.log
$exit = $LASTEXITCODE
$jtl = "jmeter_m4_results.jtl"
$rows = Import-Csv $jtl
$pass = ($rows | Where-Object { $_.success -eq 'true' }).Count
$fail = ($rows | Where-Object { $_.success -eq 'false' }).Count
"EXIT=$exit 成功=$pass 失败=$fail"

# 4. 还原夹具（把靶子复位，避免污染下一轮）
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p"$env:MYSQL_PASSWORD" nianglin `
  -e "UPDATE companion_order SET status='PENDING', companion_id=NULL, accept_time=NULL, version=0 WHERE id=1001;"
```

## 4 · 双条件判据说明

只看退出码会被假阳性骗过（BASELINE §二之二）：JMeter 在「全部请求业务失败」时依然 EXIT=0。
因此本报告与 `tools/verify_all.ps1` 的 jmeter 段都按**双条件**判定：

1. `EXIT=0`（JMeter 正常跑完）；
2. JTL 行数 = 50 且 `成功=1`（业务语义成立：恰好一个赢家）。

## 5 · 本轮排查记录（夹具漂移两处）

1. **令牌失效**：仓库里旧的 `bench_tokens.csv` 因密码版本被后续登录/登出 bump 而全部 401 →
   按 Global Constraint 2 重跑 `bench_setup.py` 重签（修夹具，不改测试）。
2. **属性名遮蔽**：jmx 的 UDV 写的是 `${__P(orderId,1228)}`（小写 `orderId`），
   计划给的 `-JORDER_ID=1001` 大写不匹配，导致压测实际打到历史订单 1228 →
   jmx 默认值改为 1001，调用统一用 `-JorderId=1001`。
