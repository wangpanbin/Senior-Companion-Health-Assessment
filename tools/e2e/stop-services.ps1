# 银龄伴诊 · 停止 E2E 启动的后端（前端由 Playwright 自己回收）
#
# ⚠️ 只停 8080 上的 Java 进程，不动 MySQL / Redis 常驻服务。

$ErrorActionPreference = 'Continue'

$conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if (-not $conn) { Write-Host '8080 未在监听，无需停止'; exit 0 }

$pids = $conn | Select-Object -ExpandProperty OwningProcess -Unique
foreach ($p in $pids) {
  $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
  if ($proc) {
    Write-Host "停止 PID=$p ($($proc.ProcessName))"
    Stop-Process -Id $p -Force
  }
}
Write-Host '后端已停止'
