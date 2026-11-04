# 银龄伴诊 · 停止 E2E 启动的后端（前端由 Playwright 自己回收）
#
# ⚠️ 只停 8080 上的 Java 进程，不动 MySQL / Redis 常驻服务。
#
# ⚠️ 2026-11-04：原来只靠 Get-NetTCPConnection 取 pid，本机会间歇性返回空表
#    （甚至给出 PID=0 Idle，Stop-Process 报「拒绝访问」后仍宣称已停止）。
#    改为 netstat 为主、进程命令行兜底，并且**只停 java/javaw**：
#    兜底曾把命令行里带 NIANGLIN_* 环境变量的 pwsh 一并杀掉（脚本把自己停了）。

$ErrorActionPreference = 'Continue'

$JavaNames = @('java', 'javaw')

function Get-BackendPids {
  $found = @()
  for ($i = 0; $i -lt 3; $i++) {
    $lines = netstat -ano | Select-String 'LISTENING' | Where-Object { $_ -match ':8080\s' }
    foreach ($l in $lines) {
      $parts = ($l.ToString() -split '\s+') | Where-Object { $_ }
      if ($parts.Length -ge 5) { $found += [int]$parts[-1] }
    }
    $found = $found | Where-Object {
      $_ -gt 4 -and (Get-Process -Id $_ -ErrorAction SilentlyContinue).ProcessName -in $JavaNames
    } | Sort-Object -Unique
    if ($found.Count -gt 0) { return $found }
    Start-Sleep -Milliseconds 500
  }
  # 兜底：端口枚举不可信时，按命令行找本项目后端（含 surefire 的测试 fork 不算）
  return Get-CimInstance Win32_Process |
    Where-Object {
      $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -and
      $_.CommandLine -match 'nianglin' -and $_.CommandLine -notmatch 'surefire'
    } |
    Select-Object -ExpandProperty ProcessId
}

$pids = Get-BackendPids
if (-not $pids) { Write-Host '8080 未在监听，无需停止'; exit 0 }

foreach ($p in $pids) {
  $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
  if ($proc) {
    Write-Host "停止 PID=$p ($($proc.ProcessName))"
    Stop-Process -Id $p -Force
  }
}

# 等端口真正释放，避免紧接着的启动脚本误判「8080 已在监听」
for ($i = 0; $i -lt 20; $i++) {
  if (-not (netstat -ano | Select-String 'LISTENING' | Where-Object { $_ -match ':8080\s' })) { break }
  Start-Sleep -Milliseconds 500
}
Write-Host '后端已停止'
