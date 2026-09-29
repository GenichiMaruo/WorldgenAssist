[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$root=Join-Path $workspace ('test-artifacts/curseforge-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $root|Out-Null
$log=Join-Path $root 'test.log'
& node --test (Join-Path $PSScriptRoot 'tests/publish-curseforge.test.mjs') *> $log
$passed=$LASTEXITCODE -eq 0
$summary=[ordered]@{success=$passed;scope='CurseForge publishing only';test_file='scripts/tests/publish-curseforge.test.mjs';tests=4;runtime='NOT_RUN';build='NOT_RUN';external_upload='NOT_RUN';log=$log}
[IO.File]::WriteAllText((Join-Path $root 'summary.json'),($summary|ConvertTo-Json))
Get-Content -LiteralPath $log -Tail 12
Write-Output "CURSEFORGE_GATE_COMPLETE summary=$root/summary.json success=$passed"
if(-not $passed){exit 1}
