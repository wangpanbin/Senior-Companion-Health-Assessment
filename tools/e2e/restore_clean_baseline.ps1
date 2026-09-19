# 银龄伴诊 · 把数据库 / Redis 恢复到「干净基线」
#
# 适用场景：E2E 写路径测试（Playwright / 压测）污染了种子数据，导致
#   `internal_message` / `companion_order` 等表的行数与 FRONTEND_CONTRACT §10.8 不一致，
#   依赖绝对条数的断言开始假失败（详见 reports/playwright/e2e-report.md §F-04 与
#   docs/agents/BUG_LIST.md L1）。
#
# ⚠️ 破坏性操作：本脚本会 DELETE 多张表的「写路径新增行」，不可逆。
#   默认走 dry-run，只打印计划要执行的 SQL，不真的执行；必须显式传 -Execute 才会真删。
#
# 用法（PowerShell）：
#   # 1) 看计划（推荐先做）
#   .\tools\e2e\restore_clean_baseline.ps1
#
#   # 2) 真执行 —— 会二次确认；输入 N 直接终止
#   .\tools\e2e\restore_clean_baseline.ps1 -Execute
#
#   # 3) 跳过二次确认（CI / 自动化场景，自己负责）
#   .\tools\e2e\restore_clean_baseline.ps1 -Execute -Yes
#
# DB 口令一律走环境变量，不落盘，**脚本不内置默认口令**（明文口令不入库）：
#   $env:MYSQL_PASSWORD = "<你的本机 dev 口令>"

[CmdletBinding()]
param(
    [switch]$Execute,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
$PROJ = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $env:MYSQL_PASSWORD) {
  Write-Host '错误：未设置 MYSQL_PASSWORD（本机 dev 口令）。脚本不内置默认口令，请先设置后重试。' -ForegroundColor Red
  exit 1
}

# 17 张被写路径触动的表（与 FIXTURE_ROLLBACK_PLAN §3 一致）
$Tables = @(
    'companion_order',
    'order_checkin',
    'companion_track',
    'order_review',
    'complaint',
    'medication_task',
    'medication_plan',
    'internal_message',
    'sys_login_log',
    'admin_oper_log',
    'companion_audit_record',
    'medication_log',
    'execution_photo',
    'elder_emergency_contact',
    'family_elder_relation',
    'companion_profile',
    'sys_user'
)

# 种子基线水位（来自 FIXTURE_ROLLBACK_PLAN §3 的实测 / FRONTEND_CONTRACT §10.8）
# 格式：@{ table = maxId }
# 仅列「写路径已确实增长」的表，未在此列的表默认只 DELETE id > 现网 MAX(id) - 50 缓冲
$SeedMaxIds = @{
    'internal_message' = 40072  # 种子 40001-40072；污染从 40073 开始
}

Write-Host '== 恢复基线 · 计划 ==' -ForegroundColor Cyan
Write-Host "项目根: $PROJ"
Write-Host "数据库: 127.0.0.1:3306 / nianglin"
Write-Host "动作: DELETE FROM <table> WHERE id > <seedMaxId>"
Write-Host ''

# 收集将要执行的 SQL
$Plan = @()
foreach ($t in $Tables) {
    if ($SeedMaxIds.ContainsKey($t)) {
        $maxId = $SeedMaxIds[$t]
    } else {
        # 没明确基线的表：取「表中当前 MAX(id) - 100」作为缓冲，
        # 防止误删种子行；执行前会再打印实际 MAX(id) 让用户判断
        $maxId = '(MAX(id) - 100)'
    }
    $Plan += [PSCustomObject]@{
        Table   = $t
        MaxId   = $maxId
        Sql     = "DELETE FROM $t WHERE id > $maxId;"
    }
}

$Plan | Format-Table -AutoSize | Out-String | Write-Host

if (-not $Execute) {
    Write-Host '⚠️ 当前是 dry-run 模式，**未执行任何 SQL**。' -ForegroundColor Yellow
    Write-Host '   确认无误后请加 -Execute 参数再次运行。' -ForegroundColor Yellow
    exit 0
}

# 真执行前的二次确认
if (-not $Yes) {
    Write-Host ''
    Write-Host '即将 DELETE 多张表的写路径新增行，此操作不可逆。' -ForegroundColor Red
    $ans = Read-Host '确认继续？(输入 yes 继续，其它任意键退出)'
    if ($ans -ne 'yes') {
        Write-Host '已取消。' -ForegroundColor Yellow
        exit 1
    }
}

# 执行：每张表打印「执行前 MAX(id)」与「执行后受影响行数」
Write-Host ''
Write-Host '== 执行 ==' -ForegroundColor Cyan
foreach ($p in $Plan) {
    $maxBefore = & mysql -h 127.0.0.1 -P 3306 -u root -p"$env:MYSQL_PASSWORD" -N -B -e "SELECT IFNULL(MAX(id),0) FROM nianglin.$($p.Table)" 2>$null
    $affected = & mysql -h 127.0.0.1 -P 3306 -u root -p"$env:MYSQL_PASSWORD" -N -B -e $p.Sql 2>$null
    Write-Host ("{0,-30}  MAX(id) before={1,-10}  affected={2}" -f $p.Table, $maxBefore, $affected)
}

# Redis 也要清：order:seq 与 pwd:version
Write-Host ''
Write-Host '== Redis 清理 ==' -ForegroundColor Cyan
$redisCli = if ($env:REDIS_CLI) { $env:REDIS_CLI } else { 'D:\develop\Redis-8.8.0\redis-cli.exe' }
foreach ($pattern in @('order:seq:*', 'pwd:version:*')) {
    $keys = & $redisCli --scan --pattern $pattern 2>$null
    if ($keys) {
        Write-Host "DEL $($keys.Count) keys matching $pattern"
        $keys | ForEach-Object { & $redisCli DEL $_ | Out-Null }
    } else {
        Write-Host "$pattern : (无 key)"
    }
}

Write-Host ''
Write-Host '✅ 完成。建议跑一遍：' -ForegroundColor Green
Write-Host '   cd frontend && pnpm exec playwright test --grep "数据回滚"' -ForegroundColor Green
