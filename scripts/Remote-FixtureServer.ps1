param([Parameter(Mandatory)][string]$Root, [ValidateSet('assisted','vanilla','link-drop')][string]$Mode, [switch]$WatchdogProbe)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$resolved = [IO.Path]::GetFullPath($Root)
if ([IO.Path]::GetDirectoryName($resolved) -ne [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') -or
    -not [IO.Path]::GetFileName($resolved).StartsWith('WorldgenAssist-')) { throw 'Not a dedicated fixture temp root' }
if (-not (Test-Path -LiteralPath $resolved -PathType Container)) { throw 'Prepared fixture root missing' }
foreach ($target in @($resolved, (Join-Path $resolved 'mods'), (Join-Path $resolved 'logs'), (Join-Path $resolved 'evidence'), (Join-Path $resolved 'server.properties'), (Join-Path $resolved 'fixture-run.lock'))) {
    if ((Test-Path -LiteralPath $target) -and ((Get-Item -LiteralPath $target -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Fixture target must not be a reparse point: $target"
    }
}
$lock = [IO.File]::Open((Join-Path $resolved 'fixture-run.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$case = $Mode + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
$evidence = Join-Path $resolved ('evidence/' + $case)
$process = $null
$success = $false
New-Item -ItemType Directory -Path $evidence | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $evidence 'runner.ps1')
$latest = Join-Path $resolved 'logs/latest.log'
function Log-Text { if (Test-Path -LiteralPath $latest) { return Get-Content -LiteralPath $latest -Raw }; return '' }
function Wait-Log([string]$Pattern, [int]$Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Log-Text) -match $Pattern) { return }
        if ($process.HasExited) { throw "Server exited before $Pattern" }
        Start-Sleep -Milliseconds 500
    }
    throw "Timeout: $Pattern"
}
function Send-Command([string]$Value) {
    $process.StandardInput.WriteLine($Value)
    $process.StandardInput.Flush()
    "$(Get-Date -Format o) $Value" | Add-Content -LiteralPath (Join-Path $evidence 'commands.txt')
}
try {
    if ((Get-Content -LiteralPath (Join-Path $resolved 'eula.txt') -Raw) -notmatch '(?m)^eula=true\s*$') { throw 'Existing EULA acceptance missing' }
    if (Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue) { throw 'Port already occupied' }
    @('server-ip=127.0.0.1','server-port=25585','online-mode=false',"level-name=$case",'level-seed=8675309',
      'view-distance=10','simulation-distance=3','max-players=1','gamemode=creative','difficulty=peaceful',
      'enable-rcon=false','enable-query=false','pause-when-empty-seconds=-1','spawn-protection=0') |
        Set-Content -LiteralPath (Join-Path $resolved 'server.properties')
    Copy-Item -LiteralPath (Join-Path $resolved 'server.properties') -Destination $evidence
    Get-ChildItem -LiteralPath (Join-Path $resolved 'mods') -Filter '*.jar' | ForEach-Object {
        [ordered]@{file=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'mods-sha256.json')
    [ordered]@{hostName=$env:COMPUTERNAME;cpu=(Get-CimInstance Win32_Processor).Name;os=(Get-CimInstance Win32_OperatingSystem).Caption} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'environment.json')
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = Join-Path $resolved 'java25/bin/java.exe'
    $info.Arguments = '-Xmx3G -jar "' + (Join-Path $resolved 'fabric-server-launch.jar') + '" nogui'
    $info.WorkingDirectory = $resolved
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE'] = 'false'
    $info.EnvironmentVariables['WORLDGEN_ASSIST_NOISE_DIGEST'] = 'true'
    $info.EnvironmentVariables['WORLDGEN_ASSIST_SEEDED_LEAF_PUBLIC_FIXTURE'] = if ($Mode -ne 'vanilla') { 'true' } else { 'false' }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    if (-not $process.Start()) { throw 'Server start failed' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    # An SSH session loss can terminate this controller without running finally.
    # A separate bounded watchdog owns ONLY this exact Java process instance.
    $watchCode = @'
$ErrorActionPreference='Stop'
$javaId=__JAVA_ID__; $ownerId=__OWNER_ID__
$javaStart=__JAVA_START__; $ownerStart=__OWNER_START__
$deadline=[DateTime]::UtcNow.AddSeconds(600)
$watchLog='__WATCH_LOG__'
"started java_pid=$javaId owner_pid=$ownerId" | Set-Content -LiteralPath $watchLog
while ($true) {
    $java=Get-Process -Id $javaId -ErrorAction SilentlyContinue
    if ($null -eq $java -or $java.StartTime.ToUniversalTime().Ticks -ne $javaStart) { 'java_already_exited' | Add-Content -LiteralPath $watchLog; break }
    $owner=Get-Process -Id $ownerId -ErrorAction SilentlyContinue
    if ($null -eq $owner -or $owner.StartTime.ToUniversalTime().Ticks -ne $ownerStart -or [DateTime]::UtcNow -ge $deadline) {
        if ($java.Path -eq '__JAVA_PATH__') {
            $java.Kill(); $java.WaitForExit()
            "owned_java_stopped owner_missing_or_changed=$($null -eq $owner -or $owner.StartTime.ToUniversalTime().Ticks -ne $ownerStart) deadline_reached=$([DateTime]::UtcNow -ge $deadline)" | Add-Content -LiteralPath $watchLog
        }
        break
    }
    Start-Sleep -Seconds 2
}
'@
    $watchCode=$watchCode.Replace('__JAVA_ID__', [string]$process.Id).Replace('__OWNER_ID__', [string]$PID).
        Replace('__JAVA_START__', [string]$process.StartTime.ToUniversalTime().Ticks).
        Replace('__OWNER_START__', [string](Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks).
        Replace('__JAVA_PATH__', $process.MainModule.FileName.Replace("'", "''")).
        Replace('__WATCH_LOG__', (Join-Path $evidence 'watchdog.log').Replace("'", "''"))
    $watchEncoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($watchCode))
    $watchdog=Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -WindowStyle Hidden -ArgumentList @('-NoProfile','-NonInteractive','-EncodedCommand',$watchEncoded) -RedirectStandardOutput (Join-Path $evidence 'watchdog-stdout.log') -RedirectStandardError (Join-Path $evidence 'watchdog-stderr.log') -PassThru
    "watchdog_pid=$($watchdog.Id); java_pid=$($process.Id); controller_pid=$PID; maximum_seconds=600" | Set-Content -LiteralPath (Join-Path $evidence 'process-ownership.txt')
    $watchdog.Dispose()
    # The prior latest.log may exist; require a fresh write before accepting its readiness.
    $freshDeadline = [DateTime]::UtcNow.AddSeconds(60)
    while (-not (Test-Path -LiteralPath $latest) -or (Get-Item -LiteralPath $latest).LastWriteTime -lt $process.StartTime) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $freshDeadline) { throw 'No fresh server log' }
        Start-Sleep -Milliseconds 500
    }
    Wait-Log 'Done \(' 180
    Write-Output "SERVER_READY $case"
    if ($WatchdogProbe) {
        'Intentional controller exit 97; watchdog must stop only its owned Java process.' | Set-Content -LiteralPath (Join-Path $evidence 'watchdog-probe.txt')
        [Environment]::Exit(97)
    }
    Wait-Log 'FixtureWorker joined the game' 180
    if ($Mode -ne 'vanilla') { Wait-Log 'seeded_fixture.handshake accepted=true' 30 }
    Send-Command 'tp FixtureWorker 16000 150 -32000'
    Start-Sleep -Seconds 5
    Send-Command 'tp FixtureWorker 16032 150 -32000'
    if ($Mode -eq 'assisted') { Wait-Log 'seeded_fixture.applied ' 45 }
    if ($Mode -eq 'link-drop') { Wait-Log 'seeded_fixture.result chunk=.* status=DISCONNECTED' 90 }
    # The baseline must finish asynchronous view generation before digest comparison.
    Start-Sleep -Seconds 45
    Send-Command 'save-all flush'
    Send-Command 'kick FixtureWorker Cross-PC fixture complete'
    Start-Sleep -Seconds 2
    Send-Command 'stop'
    if (-not $process.WaitForExit(60000) -or $process.ExitCode -ne 0) { throw 'Server stop failed' }
    if ((Log-Text) -match 'Mixin apply failed|Encountered an unexpected exception|Exception in server tick loop') { throw 'Server runtime error' }
    if ($Mode -eq 'vanilla' -and (Log-Text) -match 'seeded_fixture\.(sent|applied) ') { throw 'Unexpected assisted work in baseline' }
    if ($Mode -eq 'link-drop' -and (Log-Text) -match 'seeded_fixture\.applied ') { throw 'Fault client unexpectedly installed remote work' }
    $success = $true
} finally {
    if ($null -ne $process) {
        if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $stdout.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $evidence 'stdout.log')
        $stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $evidence 'stderr.log')
        $process.Dispose()
    }
    if (Test-Path -LiteralPath $latest) { Copy-Item -LiteralPath $latest -Destination (Join-Path $evidence 'latest.log') }
    @("mode=$Mode","success=$success",'transport=SSH tunnel over Tailscale; Minecraft loopback only','performance=NOT_EVALUATED') |
        Set-Content -LiteralPath (Join-Path $evidence 'summary.txt')
    $lock.Dispose()
    Write-Output "SERVER_COMPLETE $case success=$success"
}
