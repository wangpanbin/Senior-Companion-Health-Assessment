# tools/verify_all.ps1 —— 收敛迭代总闸门（convergence-2026-09 §5.3）
# 用法：powershell -ExecutionPolicy Bypass -File tools\verify_all.ps1 [-WithLoad]
#   -WithLoad  追加 JMeter 50 并发抢单（需后端已启动 + JMETER_HOME）
param([switch]$WithLoad)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$results = [System.Collections.Generic.List[object]]::new()

function Add-Result([string]$Name, [bool]$Ok, [string]$Detail) {
    $results.Add([pscustomobject]@{ 阶段 = $Name; 结果 = $(if ($Ok) { 'PASS' } else { 'FAIL' }); 说明 = $Detail })
    Write-Host ("{0,-22} {1}  {2}" -f $Name, $(if ($Ok) { 'PASS' } else { 'FAIL' }), $Detail)
}

# ---- 0. 前置 ----
if (-not $env:MYSQL_PASSWORD) {
    Add-Result '前置' $false 'MYSQL_PASSWORD 未设置，后端测试必然挂'
    $results | Format-Table -AutoSize; exit 1
}
Add-Result '前置' $true 'MYSQL_PASSWORD 已设置'

# ---- 1. 前端 lint（0 警告）----
cmd /c "cd /d `"$root\frontend`" && pnpm.cmd lint"
Add-Result 'pnpm lint' ($LASTEXITCODE -eq 0) "EXIT=$LASTEXITCODE"

# ---- 2. 前端 build（必须排在 lint 之后：lint 不编译 SCSS，样式错误只有 build 能抓）----
cmd /c "cd /d `"$root\frontend`" && pnpm.cmd build"
Add-Result 'pnpm build' ($LASTEXITCODE -eq 0) "EXIT=$LASTEXITCODE"

# ---- 3. 后端全量测试（顺带产出 jacoco）----
cmd /c "cd /d `"$root\backend`" && mvn.cmd test"
Add-Result 'mvn test' ($LASTEXITCODE -eq 0) "EXIT=$LASTEXITCODE"

# ---- 4. 明文口令防扩散门禁（独立退出码，不并入 mvn）----
$env:PYTHONUTF8 = '1'; $env:PYTHONIOENCODING = 'utf-8'
python "$root\backend\sql\tools\check_seed_password.py"
Add-Result 'check_seed_password' ($LASTEXITCODE -eq 0) "EXIT=$LASTEXITCODE"

# ---- 5. Playwright 全量 E2E（前置：后端 8080 + dev server 5141 已就绪，
#         起法见 docs/agents/FRONTEND_CONTRACT.md §13）----
cmd /c "cd /d `"$root\frontend`" && pnpm.cmd exec playwright test"
Add-Result 'playwright' ($LASTEXITCODE -eq 0) "EXIT=$LASTEXITCODE"

# ---- 6. 覆盖率达标线 ----
$jacoco = "$root\backend\target\site\jacoco\jacoco.csv"
if (Test-Path $jacoco) {
    $rows = Import-Csv $jacoco
    $cov = ($rows | Measure-Object -Property LINE_COVERED -Sum).Sum
    $mis = ($rows | Measure-Object -Property LINE_MISSED -Sum).Sum
    $ratio = [math]::Round(100 * $cov / ($cov + $mis), 1)
    Add-Result 'coverage >= 60%' ($ratio -ge 60) "行覆盖 ${ratio}%"
} else {
    Add-Result 'coverage >= 60%' $false 'jacoco.csv 不存在（mvn test 是否真的跑过？）'
}

# ---- 7.（可选）JMeter 抢单，双条件判据 ----
if ($WithLoad) {
    if (-not $env:JMETER_HOME) {
        Add-Result 'jmeter 抢单' $false 'JMETER_HOME 未设置'
    } else {
        $jtl = "$root\backend\sql\tools\jmeter_m4_results.jtl"
        jmeter -n -t "$root\backend\sql\tools\jmeter_m4_accept.jmx" -JorderId=1001 -l $jtl -j "$root\backend\sql\tools\jmeter_verify.log"
        $exit = $LASTEXITCODE
        $lines = 0; $pass = 0
        if (Test-Path $jtl) {
            $all = Import-Csv $jtl
            $lines = $all.Count
            $pass = ($all | Where-Object { $_.success -eq 'true' }).Count
        }
        # 双条件：EXIT=0 且 JTL 行数 > 1（BASELINE §二之二：只看退出码会被假阳性骗过）
        $ok = ($exit -eq 0) -and ($lines -gt 1) -and ($pass -eq 1) -and ($lines -eq 50)
        Add-Result 'jmeter 抢单' $ok "EXIT=$exit 成功=$pass 总数=$lines"
        # 压测完把靶子订单复位，避免污染下一轮
        & "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p"$env:MYSQL_PASSWORD" nianglin -e "UPDATE companion_order SET status='PENDING', companion_id=NULL, accept_time=NULL, version=0 WHERE id=1001;" 2>$null
    }
}

# ---- 汇总 ----
Write-Host "`n========== VERIFY_ALL 汇总 =========="
$results | Format-Table -AutoSize
$failed = $results | Where-Object { $_.结果 -eq 'FAIL' }
if ($failed) { Write-Host "总闸门：FAIL（$($failed.Count) 段未过）" -ForegroundColor Red; exit 1 }
Write-Host '总闸门：PASS' -ForegroundColor Green; exit 0
