# Intentional controller crash in a NEW disposable world; never use on a live server.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$remoteHost='gen1c@100.117.255.71'
$remoteRoot='C:/Users/gen1c/AppData/Local/Temp/WorldgenAssist-20260909'
$root=Join-Path (Split-Path -Parent $PSScriptRoot) ('test-artifacts/multipc-watchdog-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $root 'runner.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Remote-FixtureServer.ps1') -Destination $root
function Invoke-Remote([string]$Code) {
    $code='$ErrorActionPreference="Stop"; $ProgressPreference="SilentlyContinue"; '+$Code
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
    & ssh -o BatchMode=yes -o ConnectTimeout=10 $remoteHost "powershell -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand $encoded"
}
$success=$false
try {
    Write-Output "evidence=$root"
    Invoke-Remote ('if (Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue) { throw "Fixture listener already active" }; if (Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -like "*WorldgenAssist-20260909*" }) { throw "Fixture process already active" }')
    if ($LASTEXITCODE -ne 0) { throw 'Preflight failed' }
    & scp -q (Join-Path $PSScriptRoot 'Remote-FixtureServer.ps1') ($remoteHost+':'+$remoteRoot+'/')
    if ($LASTEXITCODE -ne 0) { throw 'Helper transfer failed' }
    # Keep the SSH session host alive after the CHILD controller exits. Otherwise
    # SSH session teardown can also terminate the watchdog before its final log.
    $childCode='& "'+$remoteRoot+'/Remote-FixtureServer.ps1" -Root "'+$remoteRoot+'" -Mode vanilla -WatchdogProbe'
    $childEncoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCode))
    $probeName=Split-Path -Leaf $root
    $outer=@'
$info=Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -WindowStyle Hidden -ArgumentList @('-NoProfile','-NonInteractive','-EncodedCommand','__ENCODED__') -RedirectStandardOutput '__ROOT__/__PROBE__-controller-out.log' -RedirectStandardError '__ROOT__/__PROBE__-controller-err.log' -PassThru
# Cache the handle before waiting: Windows PowerShell 5 Start-Process can otherwise
# lose ExitCode after the short-lived process is reaped.
$ownedHandle=$info.Handle
if (-not $info.WaitForExit(240000)) { $info.Kill(); Start-Sleep -Seconds 5; throw 'Probe controller exceeded startup deadline' }
$controllerCode=$info.ExitCode
if ($null -eq $controllerCode) { throw 'Controller exit code unavailable' }
Start-Sleep -Seconds 5
Get-Content -LiteralPath '__ROOT__/__PROBE__-controller-out.log'
Get-Content -LiteralPath '__ROOT__/__PROBE__-controller-err.log'
$info.Dispose()
exit $controllerCode
'@
    $outer=$outer.Replace('__ENCODED__',$childEncoded).Replace('__ROOT__',$remoteRoot).Replace('__PROBE__',$probeName)
    $lines=@(Invoke-Remote $outer | Tee-Object -FilePath (Join-Path $root 'remote-runner.log'))
    $controllerExit=$LASTEXITCODE
    if ($controllerExit -ne 97) { throw "Expected intentional controller exit 97, received $controllerExit" }
    $ready=@($lines | Where-Object { $_ -match '^SERVER_READY vanilla-\d{8}-\d{6}-\d{3}$' })
    if ($ready.Count -ne 1) { throw 'Missing exact ready marker' }
    $case=$ready[0].Substring('SERVER_READY '.Length)
    $check=@'
$deadline=[DateTime]::UtcNow.AddSeconds(20)
$watchPath='__ROOT__/evidence/__CASE__/watchdog.log'
do {
    $text=if(Test-Path -LiteralPath $watchPath){Get-Content -LiteralPath $watchPath -Raw}else{''}
    $owned=@(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -like '*WorldgenAssist-20260909*' })
    $listeners=@(Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue)
    if ($text -match 'owned_java_stopped owner_missing_or_changed=True deadline_reached=False' -and $owned.Count -eq 0 -and $listeners.Count -eq 0) {
        'WATCHDOG_PASS owner_exit_97; owned_java=0; fixture_listeners=0'; exit 0
    }
    Start-Sleep -Seconds 1
} while ([DateTime]::UtcNow -lt $deadline)
throw 'Watchdog cleanup not proven; inspect exact owned processes before retry'
'@
    Invoke-Remote ($check.Replace('__ROOT__',$remoteRoot).Replace('__CASE__',$case)) | Tee-Object -FilePath (Join-Path $root 'cleanup-check.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Watchdog verification failed' }
    & scp -q -r ($remoteHost+':'+$remoteRoot+'/evidence/'+$case) (Join-Path $root 'remote-evidence')
    if ($LASTEXITCODE -ne 0) { throw 'Evidence download failed' }
    & scp -q ($remoteHost+':'+$remoteRoot+'/logs/latest.log') (Join-Path $root 'server-before-forced-stop.log')
    if ($LASTEXITCODE -ne 0) { throw 'Server log download failed' }
    $success=$true
} finally {
    @("success=$success",'test=controller exits 97; independent watchdog must stop exact owned Java','graceful_server_shutdown=NOT_TESTED','performance=NOT_EVALUATED') | Set-Content -LiteralPath (Join-Path $root 'summary.txt')
    Write-Output "evidence=$root success=$success"
}
