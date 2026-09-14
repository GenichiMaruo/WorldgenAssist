[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Root,
    [ValidateSet('fixture', 'trusted-raw')][string]$Route = 'fixture',
    [ValidateSet('simultaneous', 'vanilla', 'disconnect')][string]$Mode = 'simultaneous'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$resolved = [IO.Path]::GetFullPath($Root)
if ([IO.Path]::GetDirectoryName($resolved) -ne [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\\') -or
    -not [IO.Path]::GetFileName($resolved).StartsWith('WorldgenAssist-')) { throw 'Not a dedicated fixture temp root' }
if (-not (Test-Path -LiteralPath $resolved -PathType Container)) { throw 'Prepared fixture root missing' }
foreach ($target in @($resolved, (Join-Path $resolved 'mods'), (Join-Path $resolved 'logs'), (Join-Path $resolved 'evidence'), (Join-Path $resolved 'server.properties'), (Join-Path $resolved 'fixture-run.lock'))) {
    if ((Test-Path -LiteralPath $target) -and ((Get-Item -LiteralPath $target -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Fixture target must not be a reparse point: $target"
    }
}
$lock = [IO.File]::Open((Join-Path $resolved 'fixture-run.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$case = 'two-client-' + $Route + '-' + $Mode + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
$evidence = Join-Path $resolved ('evidence/' + $case)
$latest = Join-Path $resolved 'logs/latest.log'
$process = $null
$processHandle = $null
$stdout = $null
$stderr = $null
$success = $false
$ownerA = $null
$ownerB = $null

function Log-Text { if (Test-Path -LiteralPath $latest) { return Get-Content -LiteralPath $latest -Raw }; return '' }
function Wait-Log([string]$Pattern, [int]$Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Log-Text) -match $Pattern) { return }
        if ($process.HasExited) { throw "Server exited before $Pattern" }
        Start-Sleep -Milliseconds 250
    }
    throw "Timeout: $Pattern"
}
function Send-Command([string]$Value) {
    $process.StandardInput.WriteLine($Value)
    $process.StandardInput.Flush()
    "$(Get-Date -Format o) $Value" | Add-Content -LiteralPath (Join-Path $evidence 'commands.txt')
}
function Owner-Pattern([string]$Owner) { return [regex]::Escape($Owner) }
function Handshaked-Owner([string]$Player) {
    $text = Log-Text
    $escaped = [regex]::Escape($Player)
    $patterns = @(
        ('seeded_fixture\.handshake accepted=true.*owner=(?<owner>[0-9a-f-]+).*player=' + $escaped + '\b'),
        ('seeded_fixture\.handshake accepted=true.*player=' + $escaped + '\b.*owner=(?<owner>[0-9a-f-]+)')
    )
    foreach ($pattern in $patterns) {
        $match = [regex]::Match($text, $pattern)
        if ($match.Success) { return $match.Groups['owner'].Value }
    }
    return $null
}
function Owner-Records([string]$Marker, [string]$Owner) {
    $escaped = [regex]::Escape($Owner)
    $records = [Collections.Generic.List[object]]::new()
    foreach ($entry in [regex]::Matches((Log-Text), '(?m)^.*$')) {
        $line = $entry.Value
        if ($line -notmatch $Marker -or $line -notmatch ('owner=' + $escaped + '\b')) { continue }
        $chunk = [regex]::Match($line, 'chunk=(?<chunk>-?\d+,-?\d+)')
        if (-not $chunk.Success) { continue }
        $id = [regex]::Match($line, 'id=(?<id>\S+)')
        $status = [regex]::Match($line, 'status=(?<status>\S+)')
        $records.Add([pscustomobject]@{index=$entry.Index;chunk=$chunk.Groups['chunk'].Value;id=if($id.Success){$id.Groups['id'].Value}else{$null};status=if($status.Success){$status.Groups['status'].Value}else{$null};line=$line})
    }
    return $records.ToArray()
}
function Id-Records([string]$Marker) {
    $records = [Collections.Generic.List[object]]::new()
    foreach ($entry in [regex]::Matches((Log-Text), '(?m)^.*$')) {
        $line = $entry.Value
        if ($line -notmatch $Marker) { continue }
        $id = [regex]::Match($line, 'id=(?<id>\S+)')
        if ($id.Success) { $records.Add([pscustomobject]@{index=$entry.Index;id=$id.Groups['id'].Value;line=$line}) }
    }
    return $records.ToArray()
}
function Find-OverlapPair([ValidateSet('fixture', 'trusted-raw')][string]$PairRoute, [switch]$CurrentOnly, [switch]$CompletedOnly) {
    $sentA = @(Owner-Records $(if($PairRoute -eq 'fixture'){'seeded_fixture\.sent'}else{'job\.sent'}) $ownerA | Where-Object { -not [string]::IsNullOrWhiteSpace($_.id) })
    $sentB = @(Owner-Records $(if($PairRoute -eq 'fixture'){'seeded_fixture\.sent'}else{'job\.sent'}) $ownerB | Where-Object { -not [string]::IsNullOrWhiteSpace($_.id) })
    $fixtureResultsA = if ($PairRoute -eq 'fixture') { @(Owner-Records 'seeded_fixture\.result' $ownerA) } else { @() }
    $fixtureResultsB = if ($PairRoute -eq 'fixture') { @(Owner-Records 'seeded_fixture\.result' $ownerB) } else { @() }
    $rawTerminals = if ($PairRoute -eq 'trusted-raw') { @(Id-Records 'job\.(?:complete|fallback_local)') } else { @() }
    $successes = @(Id-Records $(if ($PairRoute -eq 'fixture') { 'seeded_fixture\.applied' } else { 'job\.complete' }))
    foreach ($a in $sentA) {
        $aTerminal = @(if ($PairRoute -eq 'fixture') {
            @($fixtureResultsA | Where-Object { $_.chunk -eq $a.chunk -and $_.index -gt $a.index -and $_.status -ne 'BUSY' } | Sort-Object index | Select-Object -First 1)
        } else { @($rawTerminals | Where-Object { $_.id -eq $a.id -and $_.index -gt $a.index } | Sort-Object index | Select-Object -First 1) }
        )
        foreach ($b in $sentB) {
            $bTerminal = @(if ($PairRoute -eq 'fixture') {
                @($fixtureResultsB | Where-Object { $_.chunk -eq $b.chunk -and $_.index -gt $b.index -and $_.status -ne 'BUSY' } | Sort-Object index | Select-Object -First 1)
            } else { @($rawTerminals | Where-Object { $_.id -eq $b.id -and $_.index -gt $b.index } | Sort-Object index | Select-Object -First 1) }
            )
            if (($aTerminal.Count -eq 0 -or $b.index -lt $aTerminal[0].index) -and ($bTerminal.Count -eq 0 -or $a.index -lt $bTerminal[0].index)) {
                if ($CurrentOnly -and ($aTerminal.Count -ne 0 -or $bTerminal.Count -ne 0)) { continue }
                if ($CompletedOnly -and (@($successes | Where-Object { $_.id -eq $a.id }).Count -eq 0 -or @($successes | Where-Object { $_.id -eq $b.id }).Count -eq 0)) { continue }
                return [pscustomobject]@{route=$PairRoute;owner_a=$ownerA;owner_b=$ownerB;sent_a=$a;sent_b=$b;terminal_a=if($aTerminal.Count){$aTerminal[0]}else{$null};terminal_b=if($bTerminal.Count){$bTerminal[0]}else{$null};current=($aTerminal.Count -eq 0 -and $bTerminal.Count -eq 0)}
            }
        }
    }
    return $null
}
function Wait-OverlapPair([ValidateSet('fixture', 'trusted-raw')][string]$PairRoute, [int]$Seconds, [switch]$CurrentOnly, [switch]$CompletedOnly) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $pair = Find-OverlapPair $PairRoute -CurrentOnly:$CurrentOnly -CompletedOnly:$CompletedOnly
        if ($null -ne $pair) { return $pair }
        if ($process.HasExited) { throw 'Server exited before an overlapping owner pair appeared' }
        Start-Sleep -Milliseconds 100
    }
    throw 'No overlapping owner pair appeared before deadline'
}
function Save-OverlapPair([object]$Pair) {
    [ordered]@{
        route=$Pair.route; current=$Pair.current
        owner_a=@{owner=$Pair.owner_a;id=$Pair.sent_a.id;chunk=$Pair.sent_a.chunk;line_index=$Pair.sent_a.index;terminal_line_index=if($null -ne $Pair.terminal_a){$Pair.terminal_a.index}else{$null}}
        owner_b=@{owner=$Pair.owner_b;id=$Pair.sent_b.id;chunk=$Pair.sent_b.chunk;line_index=$Pair.sent_b.index;terminal_line_index=if($null -ne $Pair.terminal_b){$Pair.terminal_b.index}else{$null}}
        both_sent_before_matching_terminal=$true
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'dispatch-overlap.json')
}
function Offline-OwnerUuid([string]$Player) {
    $md5 = [Security.Cryptography.MD5]::Create()
    try {
        $bytes = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes('OfflinePlayer:' + $Player))
    } finally { $md5.Dispose() }
    $bytes[6] = ($bytes[6] -band 0x0f) -bor 0x30
    $bytes[8] = ($bytes[8] -band 0x3f) -bor 0x80
    $hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    return $hex.Substring(0,8)+'-'+$hex.Substring(8,4)+'-'+$hex.Substring(12,4)+'-'+$hex.Substring(16,4)+'-'+$hex.Substring(20,12)
}

try {
    New-Item -ItemType Directory -Path $evidence | Out-Null
    Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $evidence 'runner.ps1')
    if ((Get-Content -LiteralPath (Join-Path $resolved 'eula.txt') -Raw) -notmatch '(?m)^eula=true\s*$') { throw 'Existing EULA acceptance missing' }
    if (Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue) { throw 'Port already occupied' }
    @('server-ip=127.0.0.1', 'server-port=25585', 'online-mode=false', "level-name=$case", 'level-seed=8675309',
      'view-distance=10', 'simulation-distance=3', 'max-players=2', 'gamemode=spectator', 'difficulty=peaceful',
      'enable-rcon=false', 'enable-query=false', 'pause-when-empty-seconds=-1', 'spawn-protection=0') |
        Set-Content -LiteralPath (Join-Path $resolved 'server.properties')
    Copy-Item -LiteralPath (Join-Path $resolved 'server.properties') -Destination $evidence
    Get-ChildItem -LiteralPath (Join-Path $resolved 'mods') -Filter '*.jar' | ForEach-Object {
        [ordered]@{file=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'mods-sha256.json')
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = Join-Path $resolved 'java25/bin/java.exe'
    $info.Arguments = '-Xmx3G -jar "' + (Join-Path $resolved 'fabric-server-launch.jar') + '" nogui'
    $info.WorkingDirectory = $resolved
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE'] = if ($Route -eq 'trusted-raw' -and $Mode -ne 'vanilla') { 'true' } else { 'false' }
    $info.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE'] = if ($Route -eq 'trusted-raw' -and $Mode -ne 'vanilla') { 'trusted_raw' } else { 'deny' }
    $info.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS'] = if ($Route -eq 'trusted-raw' -and $Mode -ne 'vanilla') { '10000' } else { '2000' }
    $info.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT'] = if ($Route -eq 'trusted-raw' -and $Mode -ne 'vanilla') { '8' } else { '8' }
    $info.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS'] = if ($Route -eq 'trusted-raw' -and $Mode -ne 'vanilla') { '8' } else { '0' }
    $info.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES'] = if ($Route -eq 'trusted-raw' -and $Mode -ne 'vanilla') { '0' } else { '16' }
    $info.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_PREDICTION'] = 'false'
    $info.EnvironmentVariables['WORLDGEN_ASSIST_NOISE_DIGEST'] = 'true'
    $info.EnvironmentVariables['WORLDGEN_ASSIST_SEEDED_LEAF_PUBLIC_FIXTURE'] = if ($Route -eq 'fixture' -and $Mode -ne 'vanilla') { 'true' } else { 'false' }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
    if (-not $process.Start()) { throw 'Server start failed' }
    # Hold the process handle before any wait/read. Windows PowerShell 5 can
    # otherwise lose an already-exited redirected process's handle.
    $processHandle = $process.Handle
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    # SSH loss can end this controller before its finally block. This hidden
    # watcher owns only this exact JVM identity and never discovers/kills a
    # general Java process.
    $watchCode = @'
$ErrorActionPreference='Stop'
$javaId=__JAVA_ID__; $ownerId=__OWNER_ID__; $javaStart=__JAVA_START__; $ownerStart=__OWNER_START__
$deadline=[DateTime]::UtcNow.AddSeconds(600); $watchLog='__WATCH_LOG__'
"started java_pid=$javaId owner_pid=$ownerId" | Set-Content -LiteralPath $watchLog
while ($true) {
    $java=Get-Process -Id $javaId -ErrorAction SilentlyContinue
    if ($null -eq $java -or $java.StartTime.ToUniversalTime().Ticks -ne $javaStart) { 'java_already_exited' | Add-Content -LiteralPath $watchLog; break }
    $owner=Get-Process -Id $ownerId -ErrorAction SilentlyContinue
    if ($null -eq $owner -or $owner.StartTime.ToUniversalTime().Ticks -ne $ownerStart -or [DateTime]::UtcNow -ge $deadline) {
        if ($java.Path -eq '__JAVA_PATH__') { $java.Kill(); $java.WaitForExit(); 'owned_java_stopped owner_missing_or_changed_or_deadline' | Add-Content -LiteralPath $watchLog }
        break
    }
    Start-Sleep -Seconds 2
}
'@
    $watchCode = $watchCode.Replace('__JAVA_ID__', [string]$process.Id).Replace('__OWNER_ID__', [string]$PID).
        Replace('__JAVA_START__', [string]$process.StartTime.ToUniversalTime().Ticks).
        Replace('__OWNER_START__', [string](Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks).
        Replace('__JAVA_PATH__', $process.MainModule.FileName.Replace("'", "''")).
        Replace('__WATCH_LOG__', (Join-Path $evidence 'watchdog.log').Replace("'", "''"))
    $watchEncoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($watchCode))
    $watchdog = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -WindowStyle Hidden -ArgumentList @('-NoProfile', '-NonInteractive', '-EncodedCommand', $watchEncoded) -RedirectStandardOutput (Join-Path $evidence 'watchdog-stdout.log') -RedirectStandardError (Join-Path $evidence 'watchdog-stderr.log') -PassThru
    "watchdog_pid=$($watchdog.Id); java_pid=$($process.Id); controller_pid=$PID; maximum_seconds=600" | Set-Content -LiteralPath (Join-Path $evidence 'process-ownership.txt')
    $watchdog.Dispose()
    $freshDeadline = [DateTime]::UtcNow.AddSeconds(60)
    while (-not (Test-Path -LiteralPath $latest) -or (Get-Item -LiteralPath $latest).LastWriteTime -lt $process.StartTime) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $freshDeadline) { throw 'No fresh server log' }
        Start-Sleep -Milliseconds 250
    }
    Wait-Log 'Done \(' 180
    # Generated 26.2 GameRules confirms this namespaced key. Spectators do not
    # generate chunks, and fixture manager ignores them until both far-area
    # teleports finish and the single creative-mode command admits both owners.
    Send-Command 'gamerule minecraft:spectators_generate_chunks false'
    Write-Output "SERVER_READY $case"
    Wait-Log 'FixtureOwnerA joined the game' 180
    Wait-Log 'FixtureOwnerB joined the game' 180
    if ($Route -eq 'fixture' -and $Mode -ne 'vanilla') {
        $ownerDeadline = [DateTime]::UtcNow.AddSeconds(45)
        while (($null -eq $ownerA -or $null -eq $ownerB) -and [DateTime]::UtcNow -lt $ownerDeadline) {
            $ownerA = Handshaked-Owner 'FixtureOwnerA'
            $ownerB = Handshaked-Owner 'FixtureOwnerB'
            if ($null -eq $ownerA -or $null -eq $ownerB) { Start-Sleep -Milliseconds 250 }
        }
        if ($null -eq $ownerA -or $null -eq $ownerB) { throw 'Accepted handshake did not map both fixture player names to owner UUIDs' }
        [ordered]@{FixtureOwnerA=$ownerA;FixtureOwnerB=$ownerB} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'owner-map.json')
    } elseif ($Route -eq 'trusted-raw' -and $Mode -ne 'vanilla') {
        $ownerA = Offline-OwnerUuid 'FixtureOwnerA'
        $ownerB = Offline-OwnerUuid 'FixtureOwnerB'
        Wait-Log ('worker\.register owner=' + (Owner-Pattern $ownerA) + ' status=ACCEPTED') 45
        Wait-Log ('worker\.register owner=' + (Owner-Pattern $ownerB) + ' status=ACCEPTED') 45
        [ordered]@{FixtureOwnerA=$ownerA;FixtureOwnerB=$ownerB;mapping='offline_name_uuid_v3'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'owner-map.json')
    }
    Send-Command 'tp FixtureOwnerA 16000 150 -32000'
    Send-Command 'tp FixtureOwnerB -16000 150 32000'
    Send-Command 'gamemode creative @a'
    if ($Route -eq 'fixture' -and $Mode -ne 'vanilla') {
        Wait-Log ('seeded_fixture\.admitted .*owner=' + (Owner-Pattern $ownerA)) 90
        Wait-Log ('seeded_fixture\.admitted .*owner=' + (Owner-Pattern $ownerB)) 90
        Wait-Log 'seeded_fixture\.admitted .*active=(?:[2-9]|[1-9][0-9]+)' 30
        Wait-Log ('seeded_fixture\.sent .*owner=' + (Owner-Pattern $ownerA)) 30
        Wait-Log ('seeded_fixture\.sent .*owner=' + (Owner-Pattern $ownerB)) 30
        $pair = Wait-OverlapPair 'fixture' 90 -CurrentOnly:($Mode -eq 'disconnect') -CompletedOnly:($Mode -eq 'simultaneous')
        Save-OverlapPair $pair
        Write-Output 'OWNERS_ADMITTED active_at_least=2'
        if ($Mode -eq 'disconnect') {
            Send-Command 'kick FixtureOwnerA Two-client owner-isolation fixture'
            Write-Output 'OWNER_A_KICKED'
            Wait-Log ('seeded_fixture\.result .*status=DISCONNECTED.*owner=' + (Owner-Pattern $ownerA)) 45
            Wait-Log ('seeded_fixture\.applied (?=.*owner=' + (Owner-Pattern $ownerB) + ')(?=.*chunk=' + [regex]::Escape($pair.sent_b.chunk) + ')') 60
        } else {
            Wait-Log ('seeded_fixture\.applied .*owner=' + (Owner-Pattern $ownerA)) 60
            Wait-Log ('seeded_fixture\.applied .*owner=' + (Owner-Pattern $ownerB)) 60
        }
    } elseif ($Route -eq 'trusted-raw' -and $Mode -ne 'vanilla') {
        Wait-Log ('job\.sent .*owner=' + (Owner-Pattern $ownerA)) 90
        Wait-Log ('job\.sent .*owner=' + (Owner-Pattern $ownerB)) 90
        $pair = Wait-OverlapPair 'trusted-raw' 90 -CurrentOnly:($Mode -eq 'disconnect') -CompletedOnly:($Mode -eq 'simultaneous')
        Save-OverlapPair $pair
        $rawA = $pair.sent_a; $rawB = $pair.sent_b
        if ($Mode -eq 'disconnect') {
            Send-Command 'kick FixtureOwnerA Two-client owner-isolation fixture'
            Write-Output 'OWNER_A_KICKED'
            Wait-Log ('worker\.disconnect owner=' + (Owner-Pattern $ownerA) + ' cancelled_jobs=') 45
            Wait-Log ('job\.fallback_local id=' + [regex]::Escape($rawA.id) + '\b') 60
            Wait-Log ('job\.complete id=' + [regex]::Escape($rawB.id) + '\b') 60
        } else {
            Wait-Log ('job\.complete id=' + [regex]::Escape($rawA.id) + '\b') 60
            Wait-Log ('job\.complete id=' + [regex]::Escape($rawB.id) + '\b') 60
        }
    } else { Start-Sleep -Seconds 45 }
    Send-Command 'save-all flush'
    Send-Command 'kick FixtureOwnerA Two-client fixture complete'
    Send-Command 'kick FixtureOwnerB Two-client fixture complete'
    Start-Sleep -Seconds 2
    Send-Command 'stop'
    if (-not $process.WaitForExit(60000) -or $process.ExitCode -ne 0) { throw 'Server stop failed' }
    $log = Log-Text
    if ($log -match 'Mixin apply failed|Encountered an unexpected exception|Exception in server tick loop') { throw 'Server runtime error' }
    if ($Mode -eq 'vanilla' -and $log -match '(?:seeded_fixture\.(?:sent|applied)|job\.sent) ') { throw 'Unexpected assisted work in baseline' }
    if ($Route -eq 'fixture' -and $Mode -eq 'disconnect' -and $log -notmatch ('seeded_fixture\.result .*status=DISCONNECTED.*owner=' + (Owner-Pattern $ownerA))) { throw 'Owner A did not become disconnected while pending' }
    if ($Route -eq 'trusted-raw' -and $Mode -eq 'disconnect' -and $log -notmatch ('worker\.disconnect owner=' + (Owner-Pattern $ownerA) + ' cancelled_jobs=')) { throw 'Trusted-raw owner A disconnect did not cancel a pending job' }
    $success = $true
} finally {
    if ($null -ne $process) {
        if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        if ($null -ne $stdout) { $stdout.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $evidence 'stdout.log') }
        if ($null -ne $stderr) { $stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $evidence 'stderr.log') }
        $process.Dispose()
    }
    if (Test-Path -LiteralPath $latest) { Copy-Item -LiteralPath $latest -Destination (Join-Path $evidence 'latest.log') }
    @("route=$Route", "mode=$Mode", "success=$success", 'transport=SSH tunnel over Tailscale; Minecraft loopback only', 'performance=NOT_EVALUATED') |
        Set-Content -LiteralPath (Join-Path $evidence 'summary.txt')
    $lock.Dispose()
    Write-Output "SERVER_COMPLETE $case success=$success"
}
