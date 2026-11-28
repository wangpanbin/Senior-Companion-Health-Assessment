# 一次性脚本：把 docs/spec/closure-2026-11/*.md 开成 GitHub Issue
# 用法：pwsh -File docs/spec/closure-2026-11/publish-issues.ps1 [-DryRun]
param([switch]$DryRun)

$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $dir '../../..')
Push-Location $repoRoot

# 批次归属（与 README §一 的独占文件集一致）
$batchA = @('P0-1','P0-3','P0-4','P0-5','P0-6','P0-7','P1-1','P1-2','P1-3','P1-6','P2-4','P2-5')

$files = Get-ChildItem $dir -Filter '*.md' | Where-Object { $_.Name -match '^(P\d-\d|DOC-\d)' } | Sort-Object Name

$created = @()
foreach ($f in $files) {
    $code = if ($f.Name -match '^(P\d-\d|DOC-\d)') { $Matches[1] } else { $f.Name }
    $batch = if ($batchA -contains $code) { 'A · MiniMax Code' } else { 'B · OpenCode' }

    $lines = Get-Content $f.FullName -Encoding UTF8
    $heading = ($lines | Where-Object { $_ -match '^#\s' } | Select-Object -First 1) -replace '^#\s+',''
    # 去掉标题里的重复编号（『P0-1 · 改派…』→『改派…』）
    $heading = $heading -replace '^(P\d-\d|DOC-\d)\s+·\s+',''
    $title = "[闭环 $code] $heading"

    $body = @"
<!-- batch: $batch -->
<!-- spec: docs/spec/closure-2026-11/$($f.Name) -->

> **批次归属**：$batch
> **规格全文**：[``docs/spec/closure-2026-11/$($f.Name)``](docs/spec/closure-2026-11/$($f.Name))
> **总览与执行纪律**：[``docs/spec/closure-2026-11/README.md``](docs/spec/closure-2026-11/README.md)
> **缺口来源**：[``reports/business-closure-final-assessment-2026-11-28.md``](reports/business-closure-final-assessment-2026-11-28.md)

---

$(($lines -join "`n"))
"@

    if ($DryRun) {
        Write-Host "[dry-run] $title"
        continue
    }

    $bodyFile = Join-Path $env:TEMP "gh-issue-$([guid]::NewGuid().ToString('N')).md"
    Set-Content -Path $bodyFile -Value $body -Encoding UTF8 -NoNewline
    $url = gh issue create --title $title --body-file $bodyFile --label 'ready-for-agent' 2>&1
    Remove-Item $bodyFile -ErrorAction SilentlyContinue
    if ($LASTEXITCODE -ne 0) { Write-Error "创建失败：$title`n$url"; continue }
    Write-Host "OK  $title`n    $url"
    $created += [pscustomobject]@{ code = $code; title = $title; url = "$url".Trim() }
}

if (-not $DryRun) {
    $created | Export-Csv -Path (Join-Path $dir 'issues.csv') -NoTypeInformation -Encoding UTF8
    Write-Host "`n已创建 $($created.Count) 个 issue，清单写入 issues.csv"
}

Pop-Location
