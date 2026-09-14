[CmdletBinding()]
param(
    [ValidateSet('assisted', 'vanilla', 'wrong-seed', 'timeout', 'malformed', 'pending-reload', 'pending-disconnect')]
    [string]$Mode = 'assisted',
    [ValidateRange(30, 240)]
    [int]$StartupTimeoutSeconds = 180
)

# Isolated functional test, NOT a performance benchmark. Never reuse a ledger/world.
# The old second-player rejection scenario was replaced by Run-TwoClientFixture.ps1
# when alpha.2 added concurrent owner support. It must no longer assert rejection.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$jdk = 'C:\Program Files\Java\jdk-25.0.4'
$root = Join-Path $workspace ('test-artifacts/runtime-' + $Mode + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$serverDir = Join-Path $root 'server'
$clientDir = Join-Path $root 'client'
$serverLog = Join-Path $serverDir 'logs/latest.log'
$clientLog = Join-Path $clientDir 'logs/latest.log'
$eula = Join-Path $workspace 'run/eula.txt'
if (-not (Test-Path -LiteralPath "$jdk/bin/java.exe")) { throw 'Required JDK 25.0.4 missing' }
if (-not (Test-Path -LiteralPath $eula) -or (Get-Content -LiteralPath $eula -Raw) -notmatch '(?m)^eula=true\s*$') {
    throw 'Existing user EULA acceptance is required; this script does not accept it on your behalf.'
}
if (Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Fixture port 25585 is already in use; no existing process will be stopped.'
}
New-Item -ItemType Directory -Path $serverDir, $clientDir | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $root 'runner.ps1')
Copy-Item -LiteralPath $eula -Destination (Join-Path $serverDir 'eula.txt')
$seed = if ($Mode -eq 'wrong-seed') { '8675310' } else { '8675309' }
$maxPlayers = 1
@('server-ip=127.0.0.1', 'server-port=25585', 'online-mode=false', 'level-name=fixture', "level-seed=$seed",
  'view-distance=10', 'simulation-distance=3', "max-players=$maxPlayers", 'gamemode=creative', 'difficulty=peaceful',
  'enable-rcon=false', 'enable-query=false', 'pause-when-empty-seconds=-1', 'spawn-protection=0') |
    Set-Content -LiteralPath (Join-Path $serverDir 'server.properties')
# Generated 26.2 Options reads these persisted booleans. Only this fresh test
# profile bypasses first-launch UI; no normal client options are read or changed.
@('onboardAccessibility:false', 'skipMultiplayerWarning:true', 'tutorialStep:none', 'renderDistance:10',
  'maxFps:30', 'enableVsync:false', 'soundCategory_master:0.0') |
    Set-Content -LiteralPath (Join-Path $clientDir 'options.txt')
Get-ChildItem -LiteralPath (Join-Path $workspace 'src') -File -Recurse | Sort-Object FullName | ForEach-Object {
    "$( (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash )  $($_.FullName.Substring($workspace.Length + 1))"
} | Set-Content -LiteralPath (Join-Path $root 'source-sha256.txt')

function Start-Fixture([string]$Side, [string]$ProfileRoot=$root, [string]$Username='FixtureWorker') {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = "$env:SystemRoot\System32\cmd.exe"
    foreach ($argument in @('/d', '/c', (Join-Path $workspace 'gradlew.bat'), "runFixture$Side", "-PfixtureRunRoot=$ProfileRoot", "-PfixtureUsername=$Username", '--no-daemon', '--console=plain')) {
        $info.ArgumentList.Add($argument)
    }
    $info.WorkingDirectory = $workspace
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.Environment['JAVA_HOME'] = $jdk
    $info.Environment['Path'] = "$jdk\bin;$($info.Environment['Path'])"
    $info.Environment['WORLDGEN_ASSIST_REMOTE'] = 'false'
    $info.Environment['WORLDGEN_ASSIST_NOISE_BACKEND'] = 'vanilla'
    $info.Environment['WORLDGEN_ASSIST_NOISE_DIGEST'] = 'true'
    $info.Environment['WORLDGEN_ASSIST_SEEDED_LEAF_PUBLIC_FIXTURE'] = if ($Side -eq 'Server' -and $Mode -ne 'vanilla') { 'true' } else { 'false' }
    $info.Environment['WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_PUBLIC_FIXTURE'] = if ($Side -eq 'Client' -and $Mode -ne 'vanilla') { 'true' } else { 'false' }
    $info.Environment['WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_FIXTURE_FAULT'] = switch ($Mode) {
        'timeout' { 'timeout' }
        'malformed' { 'malformed' }
        'pending-reload' { 'timeout' }
        'pending-disconnect' { 'timeout' }
        default { 'none' }
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    if (-not $process.Start()) { throw "Could not start fixture $Side" }
    return @{ Process = $process; Stdout = $process.StandardOutput.ReadToEndAsync(); Stderr = $process.StandardError.ReadToEndAsync(); Side = "$Side-$Username" }
}
function Read-ServerLog {
    if (Test-Path -LiteralPath $serverLog) { return Get-Content -LiteralPath $serverLog -Raw }
    return ''
}
function Read-ClientLog {
    if (Test-Path -LiteralPath $clientLog) { return Get-Content -LiteralPath $clientLog -Raw }
    return ''
}
function Wait-ServerLog([string]$Pattern, [int]$Seconds) {
    $until = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $until) {
        if ((Read-ServerLog) -match $Pattern) { return }
        if ($server.Process.HasExited) { throw "Server exited waiting for $Pattern" }
        if ($null -ne $client -and $client.Process.HasExited) { throw "Client exited waiting for $Pattern" }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for $Pattern"
}
function Wait-ClientLog([string]$Pattern, [int]$Seconds) {
    $until = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $until) {
        if ((Read-ClientLog) -match $Pattern) { return }
        if ($server.Process.HasExited) { throw "Server exited waiting for client $Pattern" }
        if ($client.Process.HasExited) { throw "Client exited waiting for $Pattern" }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for client $Pattern"
}
function ServerMarkerCount([string]$Pattern) {
    return @([regex]::Matches((Read-ServerLog), $Pattern)).Count
}
function Wait-HeldJob {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        $clientText = Read-ClientLog
        $serverText = Read-ServerLog
        $held = [regex]::Matches($clientText, 'seeded_fixture.client_fault_held id=(?<id>\S+) mode=timeout')
        if ($held.Count -gt 0) {
            $candidate = $held[$held.Count-1]
            $id = $candidate.Groups['id'].Value
            $sent = [regex]::Match($serverText, ('seeded_fixture.sent id='+[regex]::Escape($id)+' chunk=(?<chunk>-?\d+,-?\d+)'))
            if ($sent.Success -and $clientText.Substring($candidate.Index) -notmatch ('client_cancelled id='+[regex]::Escape($id)) -and
                $serverText.Substring($sent.Index) -notmatch ('seeded_fixture.result chunk='+[regex]::Escape($sent.Groups['chunk'].Value)+' status=')) {
                [ordered]@{id=$id;chunk=$sent.Groups['chunk'].Value;observed=(Get-Date -Format o)} | ConvertTo-Json |
                    Set-Content -LiteralPath (Join-Path $root 'held-job.json')
                return $sent.Groups['chunk'].Value
            }
        }
        if ($server.Process.HasExited -or $client.Process.HasExited) { throw 'Process exited before a pending held job' }
        Start-Sleep -Milliseconds 100
    }
    throw 'No presently pending client-held job observed'
}
function Command([string]$Command) {
    $server.Process.StandardInput.WriteLine($Command)
    $server.Process.StandardInput.Flush()
    "$(Get-Date -Format o) $Command" | Add-Content -LiteralPath (Join-Path $root 'commands.txt')
}
function Wait-HeldCancellation {
    $heldEvidence = Get-Content -LiteralPath (Join-Path $root 'held-job.json') -Raw | ConvertFrom-Json
    Wait-ClientLog ('seeded_fixture.client_cancelled id='+[regex]::Escape($heldEvidence.id)+'\b') 15
}
$server = $null
$client = $null
$guest = $null
$success = $false
try {
    Write-Output "evidence=$root"
    $server = Start-Fixture 'Server'
    Wait-ServerLog 'Done \(' $StartupTimeoutSeconds
    Write-Output 'server=ready; starting isolated Minecraft client'
    if ($Mode -eq 'wrong-seed') { Wait-ServerLog 'seeded_fixture.blocked reason=world_not_public_fixture' 5 }
    $client = Start-Fixture 'Client'
    Wait-ServerLog 'FixtureWorker joined the game' $StartupTimeoutSeconds
    if ($Mode -ne 'vanilla' -and $Mode -ne 'wrong-seed') { Wait-ServerLog 'seeded_fixture.handshake accepted=true' 30 }
    if ($Mode -eq 'wrong-seed') { Wait-ServerLog 'seeded_fixture.handshake accepted=false' 30 }
    Command 'tp FixtureWorker 16000 150 -32000'
    if ($Mode -in @('timeout', 'malformed', 'pending-reload', 'pending-disconnect')) {
        # Allow the immutable owner-demand snapshot to move before requesting new chunks.
        Start-Sleep -Seconds 5
        Command 'tp FixtureWorker 16032 150 -32000'
    }
    switch ($Mode) {
        'vanilla' {
            # Match the assisted route and allow asynchronous view chunks to finish.
            Start-Sleep -Seconds 5
            Command 'tp FixtureWorker 16032 150 -32000'
            Start-Sleep -Seconds 45
        }
        'assisted' {
            # A second position after the owner-demand snapshot advances exercises newly needed chunks.
            Start-Sleep -Seconds 5
            Command 'tp FixtureWorker 16032 150 -32000'
            Wait-ServerLog 'seeded_fixture.applied ' 45
            Start-Sleep -Seconds 10
            Command 'reload'
            Start-Sleep -Seconds 5
        }
        'timeout' {
            $timeoutDeadline = [DateTime]::UtcNow.AddSeconds(90)
            do {
                $heldChunk = Wait-HeldJob
                $terminalPattern = 'seeded_fixture.result chunk='+[regex]::Escape($heldChunk)+' status=(?<status>\w+)'
                Wait-ServerLog $terminalPattern 30
                $terminal = [regex]::Match((Read-ServerLog), $terminalPattern).Groups['status'].Value
                # Vanilla synchronous waits may legitimately cancel an observed held job.
                # Retry a new currently pending job; never count cancellation as timeout.
                if ($terminal -notin @('TIMED_OUT', 'CANCELLED')) { throw "Unexpected held-job terminal status $terminal" }
                if ([DateTime]::UtcNow -ge $timeoutDeadline) { throw 'No held job completed by timeout before deadline' }
            } while ($terminal -ne 'TIMED_OUT')
            Wait-HeldCancellation
        }
        'malformed' {
            Wait-ClientLog 'seeded_fixture.client_fault_malformed id=.* encoding=deflate' 45
            Wait-ServerLog 'seeded_fixture.result chunk=.* status=RESULT_REJECTED' 30
            $sendsBeforeProbe = ServerMarkerCount 'seeded_fixture.sent id='
            Command 'tp FixtureWorker 16032 150 -32000'
            Start-Sleep -Seconds 5
            if ((ServerMarkerCount 'seeded_fixture.sent id=') -ne $sendsBeforeProbe) {
                throw 'Malformed-result quarantine did not prevent a later fixture dispatch'
            }
        }
        'pending-reload' {
            $heldChunk = Wait-HeldJob
            Command 'reload'
            Wait-ServerLog 'seeded_fixture.reload generation=' 60
            Wait-ServerLog ('seeded_fixture.result chunk='+[regex]::Escape($heldChunk)+' status=STALE_CONTEXT') 30
            Wait-HeldCancellation
        }
        'pending-disconnect' {
            $heldChunk = Wait-HeldJob
            Command 'kick FixtureWorker Fixture pending-disconnect test'
            Wait-ServerLog ('seeded_fixture.result chunk='+[regex]::Escape($heldChunk)+' status=DISCONNECTED') 30
        }
        default {
            Start-Sleep -Seconds 10
        }
    }
    Command 'save-all flush'
    if ($Mode -ne 'pending-disconnect') {
        Command 'kick FixtureWorker Fixture functional test complete'
        Start-Sleep -Seconds 2
    }
    Command 'stop'
    if (-not $server.Process.WaitForExit(60000)) { throw 'Server did not exit after stop' }
    if ($server.Process.ExitCode -ne 0) { throw "Server exit code $($server.Process.ExitCode)" }
    if ((Read-ServerLog) -match 'Mixin apply failed|Exception in server tick loop|Encountered an unexpected exception') {
        throw 'Runtime fatal error in server log'
    }
    if ($Mode -in @('vanilla', 'wrong-seed') -and (Read-ServerLog) -match 'seeded_fixture\.(sent|applied) ') {
        throw 'Negative/local mode unexpectedly dispatched or installed fixture work'
    }
    if ($Mode -in @('timeout', 'malformed', 'pending-reload', 'pending-disconnect') -and (Read-ServerLog) -match 'seeded_fixture\.applied ') {
        throw 'Adversarial fixture mode unexpectedly installed a remote result'
    }
    if ($Mode -eq 'wrong-seed') {
        $ledgers = @(Get-ChildItem -LiteralPath $serverDir -Filter 'seeded_leaf_disclosure_budget*' -Recurse -File)
        if ($ledgers.Count -ne 0) { throw 'Wrong-seed mode unexpectedly created a fixture ledger' }
    }
    if ((Read-ClientLog) -match 'Mixin apply failed|Encountered an unexpected exception') {
        throw 'Runtime fatal error in client log'
    }
    if ($null -ne $guest -and (Get-Content -LiteralPath (Join-Path $root 'guest/client/logs/latest.log') -Raw) -match 'Mixin apply failed|Encountered an unexpected exception') {
        throw 'Runtime fatal error in guest client log'
    }
    $success = $true
} finally {
    foreach ($running in @($guest, $client, $server)) {
        if ($null -eq $running) { continue }
        # Only the process tree started by THIS invocation, never a discovered user/daemon PID.
        if (-not $running.Process.HasExited) { $running.Process.Kill($true); $running.Process.WaitForExit() }
        $running.Stdout.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root ($running.Side + '-stdout.log'))
        $running.Stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root ($running.Side + '-stderr.log'))
        $running.Process.Dispose()
    }
    @("mode=$Mode", "success=$success", 'performance=NOT_EVALUATED', 'client_shutdown=owned_process_tree_terminated_after_server_disconnect') |
        Set-Content -LiteralPath (Join-Path $root 'summary.txt')
    Write-Output "evidence=$root success=$success"
}
