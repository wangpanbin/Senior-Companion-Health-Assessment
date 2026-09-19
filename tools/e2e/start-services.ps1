# 银龄伴诊 · 启动 E2E 依赖的服务
#
# 说明：MySQL(3306) / Redis(6379) 通常是常驻服务；本脚本确保它们在跑，
#       然后启动后端（8080）。前端由 playwright.config.js 的 webServer 自己拉起。
#
# 用法（PowerShell）：
#   .\tools\e2e\start-services.ps1
#
# ⚠️ DB 口令一律走环境变量，不落盘，**脚本不内置默认口令**（明文口令不入库）：
#    $env:MYSQL_PASSWORD = "<你的本机 dev 口令>"

$ErrorActionPreference = 'Continue'
$PROJ = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # tools/e2e -> 仓库根

if (-not $env:MYSQL_PASSWORD) {
  Write-Host '错误：未设置 MYSQL_PASSWORD（本机 dev 口令）。脚本不内置默认口令，请先设置后重试。' -ForegroundColor Red
  exit 1
}

Write-Host "== 1) 依赖服务 ==" -ForegroundColor Cyan
foreach ($svc in @('MySQL80', 'Redis')) {
  $s = Get-Service $svc -ErrorAction SilentlyContinue
  if ($null -eq $s) { Write-Warning "  未找到服务 $svc（若用其他方式运行请忽略）"; continue }
  if ($s.Status -ne 'Running') {
    Write-Host "  启动 $svc ..."
    Start-Service $svc
  }
  Write-Host "  $svc = $((Get-Service $svc).Status)"
}

Write-Host "== 2) 后端（8080） ==" -ForegroundColor Cyan
# ⚠️ 必须 clean：target/ 可能残留 Eclipse(ECJ) 编译的、缺 Lombok 生成的 .class，
#    表现为启动报 `Unresolved compilation problems: The blank final field ... not initialized`。
#    mvn compile 会误判「Nothing to compile」而不修 —— 只有 clean 才能重编。
Push-Location (Join-Path $PROJ 'backend')
if (-not (Test-Path 'target\classes')) { mvn -q clean compile }

$logDir = Join-Path $PROJ 'backend\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
  Start-Process -FilePath 'mvn.cmd' -ArgumentList 'spring-boot:run' -WorkingDirectory (Get-Location) `
    -RedirectStandardOutput (Join-Path $logDir 'run.out') `
    -RedirectStandardError (Join-Path $logDir 'run.err') -WindowStyle Hidden
  Write-Host "  已后台启动 mvn spring-boot:run，日志：backend\logs\run.out"
} else {
  Write-Host "  8080 已在监听，跳过"
}
Pop-Location

Write-Host "== 3) 等待 /api/health ==" -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds(180); $ok = $false
while ((Get-Date) -lt $deadline) {
  try {
    $r = Invoke-RestMethod 'http://127.0.0.1:8080/api/health' -TimeoutSec 5
    if ($r.code -eq 200) { Write-Host "  HEALTH OK (profiles=$($r.data.profiles))" -ForegroundColor Green; $ok = $true; break }
  } catch { }
  Start-Sleep -Seconds 3
}
if (-not $ok) { Write-Warning '  等待超时，请查 backend\logs\run.err' }

Write-Host "== 就绪 ==" -ForegroundColor Green
Write-Host "  后端 http://127.0.0.1:8080/api/health"
Write-Host "  前端由 Playwright webServer 拉起（5141）"
Write-Host "  跑测试： cd frontend; pnpm exec playwright test"
