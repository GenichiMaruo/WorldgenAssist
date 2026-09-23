[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Root,
    [Parameter(Mandatory)][ValidateSet('overworld','the_nether','the_end')][string]$Dimension,
    [Parameter(Mandatory)][ValidateSet('vanilla','assisted')][string]$Mode,
    [Parameter(Mandatory)][ValidateRange(1,2)][int]$Players,
    [Parameter(Mandatory)][ValidateSet('correctness','performance')][string]$Purpose,
    [Parameter(Mandatory)][ValidateRange(0,256)][int]$CacheEntries,
    [Parameter(Mandatory)][ValidateSet('true','false')][string]$Prediction,
    [Parameter(Mandatory)][ValidateRange(0,64)][int]$ValidationCells,
    [Parameter(Mandatory)][long]$Seed,
    [ValidateRange(1,10)][int]$WarmupRuns = 1,
    [ValidateRange(1,10)][int]$MeasuredRepeats = 3
)

# Remote half of the all-dimension trusted-raw scenario.  It owns only the
# Java process it starts, binds Minecraft to loopback, and writes evidence
# below the already-dedicated remote fixture root.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$resolved = [IO.Path]::GetFullPath($Root)
$temp = [IO.Path]::GetFullPath($env:TEMP).TrimEnd([IO.Path]::DirectorySeparatorChar)
$dedicated = [IO.Path]::GetFullPath((Join-Path $temp 'WorldgenAssist-20260909'))
if ($resolved -ne [IO.Path]::GetFullPath((Join-Path $dedicated 'port26.3'))) {
    throw 'Root must be the dedicated WorldgenAssist 26.3 test child'
}
$javaExecutable = Join-Path $dedicated 'java25/bin/java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw 'Dedicated JDK 25 is missing' }
foreach ($target in @($resolved, (Join-Path $resolved 'mods'), (Join-Path $resolved 'logs'), (Join-Path $resolved 'evidence'))) {
    if ((Test-Path -LiteralPath $target) -and ((Get-Item -LiteralPath $target -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Scenario target must not be a reparse point: $target"
    }
}
$predictionEnabled = [bool]::Parse($Prediction)
if ($predictionEnabled -and $CacheEntries -eq 0) { throw 'Prediction requires CacheEntries greater than zero' }

$lock = [IO.File]::Open((Join-Path $resolved 'scenario-run.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$case = 'scenario-' + $Dimension + '-' + $Mode + '-p' + $Players + '-' + $Purpose + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
$evidence = Join-Path $resolved ('evidence/' + $case)
$latest = Join-Path $resolved 'logs/latest.log'
$process = $null; $stdout = $null; $stderr = $null; $success = $false
$ownerNames = @('ScenarioOwnerA', 'ScenarioOwnerB')[0..($Players - 1)]
$owners = @{}

function Log-Text {
    $value = [string](Get-Content -LiteralPath $latest -Raw -ErrorAction SilentlyContinue)
    # Windows PowerShell can suppress an empty pipeline result. Keep callers'
    # string operations valid while the server rolls over latest.log.
    if ([string]::IsNullOrEmpty($value)) { return ' ' }
    return $value
}
function Wait-Log([string]$Pattern, [int]$Seconds, [int]$Offset = 0) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $text = Log-Text
        if ($Offset -lt $text.Length -and $text.Substring($Offset) -match $Pattern) { return $text }
        if ($null -ne $process -and $process.HasExited) { throw "Server exited before log marker: $Pattern" }
        Start-Sleep -Milliseconds 200
    }
    throw "Timed out waiting for log marker: $Pattern"
}
function Send-Command([string]$Command) {
    $process.StandardInput.WriteLine($Command); $process.StandardInput.Flush()
    "$(Get-Date -Format o) $Command" | Add-Content -LiteralPath (Join-Path $evidence 'commands.txt')
}
function New-OwnerUuid([string]$Player) {
    $md5 = [Security.Cryptography.MD5]::Create()
    try { $bytes = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes('OfflinePlayer:' + $Player)) } finally { $md5.Dispose() }
    $bytes[6] = ($bytes[6] -band 0x0f) -bor 0x30; $bytes[8] = ($bytes[8] -band 0x3f) -bor 0x80
    $hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    return $hex.Substring(0,8)+'-'+$hex.Substring(8,4)+'-'+$hex.Substring(12,4)+'-'+$hex.Substring(16,4)+'-'+$hex.Substring(20,12)
}
function Find-Sent([string]$Owner, [int]$Offset = 0) {
    $text = Log-Text
    $tail = $text.Substring([Math]::Min($Offset, $text.Length))
    $escaped = [regex]::Escape($Owner)
    $matches = [regex]::Matches($tail, '(?m)^.*\[CAWG\] job\.sent id=(?<id>\S+) chunk=(?<chunk>-?\d+,-?\d+).*owner=' + $escaped + '\b.*$')
    if ($matches.Count -eq 0) { return $null }
    $match = $matches[$matches.Count - 1]
    return [pscustomobject]@{ id=$match.Groups['id'].Value; chunk=$match.Groups['chunk'].Value }
}
function Find-CompletedForOwner([string]$Owner, [int]$Offset = 0) {
    $completed = @(Find-AllCompletedForOwner $Owner $Offset)
    if ($completed.Count -eq 0) { return $null }
    return $completed[0]
}
function Find-AllCompletedForOwner([string]$Owner, [int]$Offset = 0) {
    $text = Log-Text
    $tail = $text.Substring([Math]::Min($Offset, $text.Length))
    $escaped = [regex]::Escape($Owner)
    $results = @()
    foreach ($sent in [regex]::Matches($tail, '(?m)^.*\[CAWG\] job\.sent id=(?<id>\S+) chunk=(?<chunk>-?\d+,-?\d+).*owner=' + $escaped + '\b.*$')) {
        $id = $sent.Groups['id'].Value
        $completed = [regex]::Match($tail, '(?m)^.*\[CAWG\] job\.complete id=' + [regex]::Escape($id) + '\b.*$')
        if ($completed.Success) {
            $results += [pscustomobject]@{ id=$id; chunk=$sent.Groups['chunk'].Value; sent_index=$sent.Index; completed_index=$completed.Index }
        }
    }
    return $results
}
function Find-OverlappingCompletedPair([string]$FirstOwner, [string]$SecondOwner, [int]$Offset = 0) {
    $first = @(Find-AllCompletedForOwner $FirstOwner $Offset)
    $second = @(Find-AllCompletedForOwner $SecondOwner $Offset)
    foreach ($left in $first) {
        foreach ($right in $second) {
            if ([Math]::Max($left.sent_index, $right.sent_index) -lt [Math]::Min($left.completed_index, $right.completed_index)) {
                return @($left, $right)
            }
        }
    }
    return @()
}
function Wait-CorrectnessComplete([int]$Offset, [bool]$Assisted) {
    $deadline = [DateTime]::UtcNow.AddSeconds(240)
    $quietSince = $null
    $lastDigestCount = -1
    while ([DateTime]::UtcNow -lt $deadline) {
        $text = Log-Text
        $tail = $text.Substring([Math]::Min($Offset, $text.Length))
        $digests = @([regex]::Matches($tail, '(?m)^.*\[CAWG\] stage\.digest stage=noise .* dimension=minecraft:' + [regex]::Escape($Dimension) + '\b.*$'))
        $centresPresent = $true
        for ($ownerIndex = 0; $ownerIndex -lt $ownerNames.Count; $ownerIndex++) {
            $x = if ($ownerIndex -eq 0) { 16000 } else { -16000 }
            $z = if ($ownerIndex -eq 0) { -32000 } else { 32000 }
            $chunk = ([int]($x / 16)).ToString() + ',' + ([int]($z / 16)).ToString()
            if ($tail -notmatch ('stage\.digest stage=noise chunk=' + [regex]::Escape($chunk) + ' .* dimension=minecraft:' + [regex]::Escape($Dimension) + '\b')) { $centresPresent = $false }
        }
        $ownersComplete = -not $Assisted
        if ($Assisted) {
            $ownersComplete = @($ownerNames | Where-Object { $null -eq (Find-CompletedForOwner $owners[$_] $Offset) }).Count -eq 0
        }
        if ($centresPresent -and $ownersComplete) {
            if ($digests.Count -ne $lastDigestCount) { $lastDigestCount = $digests.Count; $quietSince = [DateTime]::UtcNow }
            elseif ($null -ne $quietSince -and ([DateTime]::UtcNow - $quietSince).TotalSeconds -ge 3) { return }
        } else { $quietSince = $null; $lastDigestCount = $digests.Count }
        if ($process.HasExited) { throw 'Server exited while waiting for correctness generation' }
        Start-Sleep -Milliseconds 250
    }
    throw 'Timed out waiting for stable correctness digests and successful owner assistance'
}
function Wait-Idle([int]$Offset, [int]$Seconds, [int]$LocationIndex) {
    # A new tick containing completed worldgen proves work started; two quiet
    # seconds then delimit a bounded repeat without treating a fixed sleep as a result.
    Wait-Log 'tick\.complete .*noise_completed=[1-9]' $Seconds $Offset | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds); $lastCompletedTicks = -1; $quietSince = $null
    $required = @{}
    $baseX = 1000 + $LocationIndex * 256
    $baseZ = -2000 - $LocationIndex * 256
    for($ownerIndex=0;$ownerIndex -lt $ownerNames.Count;$ownerIndex++){
        $cx=if($ownerIndex -eq 0){$baseX}else{-$baseX}
        $cz=if($ownerIndex -eq 0){$baseZ}else{-$baseZ}
        for($dx=-4;$dx -le 4;$dx++){for($dz=-4;$dz -le 4;$dz++){$required[([string]($cx+$dx)+','+($cz+$dz))]=$true}}
    }
    while ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 250
        $text=Log-Text; $tail=$text.Substring([Math]::Min($Offset,$text.Length))
        foreach($match in [regex]::Matches($tail,'stage\.complete stage=noise chunk=(?<chunk>-?\d+,-?\d+)\b')){$required.Remove($match.Groups['chunk'].Value)}
        $completedTicks=@([regex]::Matches($tail,'tick\.complete .*noise_completed=[1-9]\d*')).Count
        $lastTick=[regex]::Matches($tail,'tick\.complete .*noise_active_end=(?<active>\d+)')
        $idle=$lastTick.Count -gt 0 -and $lastTick[$lastTick.Count-1].Groups['active'].Value -eq '0'
        if($completedTicks -ne $lastCompletedTicks){$lastCompletedTicks=$completedTicks;$quietSince=[DateTime]::UtcNow}
        elseif($idle -and $required.Count -eq 0 -and $null -ne $quietSince -and ([DateTime]::UtcNow-$quietSince).TotalSeconds -ge 2){return}
        if ($process.HasExited) { throw 'Server exited while waiting for worldgen idle' }
    }
    throw 'Timed out waiting for worldgen to become idle'
}
function Warm-AssistedOwners {
    Send-Command 'gamemode spectator @a'
    for ($ownerIndex = 0; $ownerIndex -lt $ownerNames.Count; $ownerIndex++) {
        $name = $ownerNames[$ownerIndex]
        $owner = $owners[$name]
        $ready = $false
        Send-Command ('gamemode creative ' + $name)
        $offset = (Log-Text).Length
        $magnitude = 24000 + ($ownerIndex * 12000)
        $x = if ($ownerIndex -eq 0) { $magnitude } else { -$magnitude }
        $z = if ($ownerIndex -eq 0) { $magnitude } else { -$magnitude }
        Send-Command ('execute in minecraft:' + $Dimension + ' run tp ' + $name + ' ' + $x + ' 150 ' + $z)
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        $relocateAt = [DateTime]::UtcNow.AddSeconds(20)
        $relocated = $false
        $diagnosticAt = [DateTime]::UtcNow.AddSeconds(5)
        $diagnosticCaptured = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($null -ne (Find-CompletedForOwner $owner $offset)) { $ready = $true; break }
            if ($process.HasExited) { throw "Server exited while warming assisted owner $name" }
            # Initial dimension arrival may finish local generation before the
            # client acknowledges its new world. Give ordinary in-dimension
            # movement one more fresh region; success still requires application.
            if (-not $relocated -and [DateTime]::UtcNow -ge $relocateAt) {
                $relocated = $true
                Send-Command ('execute in minecraft:' + $Dimension + ' run tp ' + $name + ' ' + ($x + 1024) + ' 150 ' + ($z + 1024))
            }
            if (-not $diagnosticCaptured -and [DateTime]::UtcNow -ge $diagnosticAt) {
                $diagnosticCaptured = $true
                $dumpInfo = [Diagnostics.ProcessStartInfo]::new()
                $dumpInfo.FileName = $javaExecutable
                $dumpInfo.Arguments = '-m jdk.jcmd/sun.tools.jcmd.JCmd ' + [string]$process.Id + ' Thread.print -l'
                $dumpInfo.UseShellExecute = $false
                $dumpInfo.CreateNoWindow = $true
                $dumpInfo.RedirectStandardOutput = $true
                $dumpInfo.RedirectStandardError = $true
                $dump = [Diagnostics.Process]::new()
                $dump.StartInfo = $dumpInfo
                try {
                    if ($dump.Start()) {
                        $dumpHandle = $dump.Handle
                        $dumpOut = $dump.StandardOutput.ReadToEndAsync()
                        $dumpErr = $dump.StandardError.ReadToEndAsync()
                        if (-not $dump.WaitForExit(10000)) { $dump.Kill(); $dump.WaitForExit() }
                        $dumpOut.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $evidence ('warmup-' + $name + '-threads.txt'))
                        $dumpErr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $evidence ('warmup-' + $name + '-threads-error.txt'))
                    }
                } catch {
                    $_.Exception.ToString() | Set-Content -LiteralPath (Join-Path $evidence ('warmup-' + $name + '-threads-error.txt'))
                } finally { $dump.Dispose() }
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $ready) { throw "Assisted owner did not become ready after bounded warm-up: $name" }
        Send-Command ('gamemode spectator ' + $name)
    }
}
function Get-TickStatistics([string]$Text, [double]$CpuMilliseconds, [double]$WallMilliseconds) {
    $ticks = @([regex]::Matches($Text, 'tick\.complete .*elapsed_ms=(?<elapsed>\d+(?:\.\d+)?).*noise_completed=(?<complete>\d+).*noise_failed=(?<failed>\d+).*local_fallbacks_delta=(?<fallback>\d+)') | ForEach-Object {
        [pscustomobject]@{ elapsed=[double]$_.Groups['elapsed'].Value; complete=[long]$_.Groups['complete'].Value; failed=[long]$_.Groups['failed'].Value; fallback=[long]$_.Groups['fallback'].Value }
    })
    $values = @($ticks | ForEach-Object { $_.elapsed } | Sort-Object)
    $mean = if ($values.Count) { ($values | Measure-Object -Average).Average } else { $null }
    $p95 = if ($values.Count) { $values[[Math]::Max(0, [Math]::Ceiling($values.Count * .95) - 1)] } else { $null }
    $completed = if ($ticks.Count) { ($ticks | Measure-Object complete -Sum).Sum } else { 0 }
    $failed = if ($ticks.Count) { ($ticks | Measure-Object failed -Sum).Sum } else { 0 }
    $fallback = if ($ticks.Count) { ($ticks | Measure-Object fallback -Sum).Sum } else { 0 }
    function Mean-LogValue([string]$Pattern) {
        $samples=@([regex]::Matches($Text,$Pattern) | ForEach-Object {[double]$_.Groups['value'].Value})
        if($samples.Count){return [Math]::Round([double](($samples|Measure-Object -Average).Average),6)}
        return $null
    }
    $resultReceived='job\.result_received .*'
    $attempted=$completed+$failed
    [ordered]@{
        server_cpu_ms=[Math]::Round($CpuMilliseconds,3); server_wall_ms=[Math]::Round($WallMilliseconds,3)
        tick_mean_ms=$mean; tick_p95_ms=$p95
        throughput_tasks_per_second=if($WallMilliseconds -gt 0){$completed*1000.0/$WallMilliseconds}else{$null}
        attempted_tasks=$attempted; completed_tasks=$completed
        timeouts=@([regex]::Matches($Text,'job\.timeout ')).Count; fallbacks=$fallback; failed_tasks=$failed
        client_compute_mean_ms=Mean-LogValue ($resultReceived+'client_compute_ms=(?<value>-?\d+(?:\.\d+)?)')
        client_encode_mean_ms=Mean-LogValue ($resultReceived+'client_encode_ms=(?<value>-?\d+(?:\.\d+)?)')
        rtt_mean_ms=Mean-LogValue ($resultReceived+'rtt_ms=(?<value>-?\d+(?:\.\d+)?)')
        server_decode_mean_ms=Mean-LogValue ($resultReceived+'server_decode_ms=(?<value>-?\d+(?:\.\d+)?)')
        encoded_bytes_mean=Mean-LogValue ($resultReceived+'encoded_bytes=(?<value>\d+)')
        validation_mean_ms=Mean-LogValue 'job\.validation_complete .*validation_ms=(?<value>-?\d+(?:\.\d+)?)'
        apply_mean_ms=Mean-LogValue 'job\.complete .*apply_ms=(?<value>-?\d+(?:\.\d+)?)'
        remote_total_mean_ms=Mean-LogValue 'job\.complete .*total_ms=(?<value>-?\d+(?:\.\d+)?)'
    }
}
function Start-Location([int]$Index) {
    # Distinct far-apart chunks avoid reuse of a prior repeat's generated region.
    $baseX = 16000 + ($Index * 4096); $baseZ = -32000 - ($Index * 4096)
    for ($index = 0; $index -lt $ownerNames.Count; $index++) {
        $x = if ($index -eq 0) { $baseX } else { -$baseX }
        $z = if ($index -eq 0) { $baseZ } else { -$baseZ }
        Send-Command ('execute in minecraft:' + $Dimension + ' run tp ' + $ownerNames[$index] + ' ' + $x + ' 150 ' + $z)
    }
}

function Complete-CorrectnessRegion {
    # After owner-assistance has been proved, ensure the independent vanilla
    # run contains all potential applied coordinates, not only a quiet prefix.
    $required = @{}
    for ($ownerIndex = 0; $ownerIndex -lt $ownerNames.Count; $ownerIndex++) {
        $cx = if ($ownerIndex -eq 0) { 1000 } else { -1000 }
        $cz = if ($ownerIndex -eq 0) { -2000 } else { 2000 }
        # Four rectangles keep each command below vanilla's 256-chunk limit.
        foreach($xs in @(@(-10,0),@(1,10))){foreach($zs in @(@(-10,0),@(1,10))){
            Send-Command ('execute in minecraft:' + $Dimension + ' run forceload add ' + (($cx+$xs[0])*16) + ' ' + (($cz+$zs[0])*16) + ' ' + (($cx+$xs[1])*16) + ' ' + (($cz+$zs[1])*16))
        }}
        for ($dx=-10; $dx -le 10; $dx++) { for ($dz=-10; $dz -le 10; $dz++) { $required[([string]($cx+$dx)+','+($cz+$dz))] = $true } }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($match in [regex]::Matches((Log-Text), 'stage\.digest stage=noise chunk=(?<chunk>-?\d+,-?\d+) .* dimension=minecraft:'+[regex]::Escape($Dimension)+'\b')) {
            $required.Remove($match.Groups['chunk'].Value)
        }
        if ($required.Count -eq 0) { return }
        if ($process.HasExited) { throw 'Server exited before the comparison region completed' }
        Start-Sleep -Milliseconds 500
    }
    throw "Missing $($required.Count) comparison-region NOISE digests"
}

try {
    New-Item -ItemType Directory -Force -Path $evidence | Out-Null
    Write-Output "SERVER_CASE $case"
    Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $evidence 'runner.ps1')
    if ((Get-Content -LiteralPath (Join-Path $resolved 'eula.txt') -Raw) -notmatch '(?m)^eula=true\s*$') { throw 'Existing EULA acceptance is required' }
    if (Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue) { throw 'Loopback fixture port 25585 is already occupied' }
    $viewDistance = 10
    @('server-ip=127.0.0.1','server-port=25585','online-mode=false','white-list=false','enforce-whitelist=false',("level-name=$case"),("level-seed=$Seed"),("view-distance=$viewDistance"),'simulation-distance=3',("max-players=$Players"),'gamemode=spectator','difficulty=peaceful','enable-rcon=false','enable-query=false','pause-when-empty-seconds=-1','spawn-protection=0') | Set-Content -LiteralPath (Join-Path $resolved 'server.properties')
    [ordered]@{dimension=$Dimension;mode=$Mode;players=$Players;purpose=$Purpose;cache_entries=$CacheEntries;prediction=$predictionEnabled;validation_cells=$ValidationCells;seed=$Seed;warmup_runs=$WarmupRuns;measured_repeats=$MeasuredRepeats;view_distance=$viewDistance;timeout_ms=30000;listener='127.0.0.1:25585'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'scenario-config.json')
    $start = [Diagnostics.ProcessStartInfo]::new(); $start.FileName = $javaExecutable; $start.Arguments = '-Xmx3G -jar "' + (Join-Path $resolved 'fabric-server-launch.jar') + '" nogui'; $start.WorkingDirectory = $resolved
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true; $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $assisted = $Mode -eq 'assisted'
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE'] = if($assisted){'true'}else{'false'}
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE'] = if($assisted){'trusted_raw'}else{'deny'}
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS'] = '30000'; $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT'] = '8'
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES'] = [string]$CacheEntries; $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_PREDICTION'] = $predictionEnabled.ToString().ToLowerInvariant(); $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS'] = [string]$ValidationCells
    $start.EnvironmentVariables['WORLDGEN_ASSIST_NOISE_DIGEST'] = if($Purpose -eq 'correctness'){'true'}else{'false'}
    $start.EnvironmentVariables['WORLDGEN_ASSIST_TICK_BENCHMARK'] = if($Purpose -eq 'performance'){'true'}else{'false'}
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
    if (-not $process.Start()) { throw 'Server did not start' }
    $processHandle = $process.Handle # Hold immediately: Windows PowerShell 5 can lose it after an early exit.
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    "java_pid=$($process.Id); java_handle=$processHandle; controller_pid=$PID" | Set-Content -LiteralPath (Join-Path $evidence 'process-ownership.txt')
    $freshDeadline=[DateTime]::UtcNow.AddSeconds(60)
    while($true){
        $freshLog=Get-Item -LiteralPath $latest -ErrorAction SilentlyContinue
        if($null -ne $freshLog -and $freshLog.LastWriteTime -ge $process.StartTime){break}
        if($process.HasExited -or [DateTime]::UtcNow -gt $freshDeadline){throw 'No fresh server log'}
        Start-Sleep -Milliseconds 250
    }
    Wait-Log 'Done \(' 180 | Out-Null; Send-Command 'gamerule minecraft:spectators_generate_chunks false'; Write-Output "SERVER_READY $case"
    foreach($name in $ownerNames){ Wait-Log ([regex]::Escape($name)+' joined the game') 180 | Out-Null; if($assisted){$owners[$name]=New-OwnerUuid $name; Wait-Log ('worker\.register owner='+[regex]::Escape($owners[$name])+' status=ACCEPTED') 60 | Out-Null} }
    $owners | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'owner-map.json')
    if ($assisted) { Warm-AssistedOwners }
    if($Purpose -eq 'correctness') {
        $offset=(Log-Text).Length; Start-Location 0; Send-Command 'gamemode creative @a'
        Wait-CorrectnessComplete $offset $assisted
        $required=@()
        if ($assisted) {
            $selected=@()
            if ($Players -eq 2) {
                $selected=@(Find-OverlappingCompletedPair $owners[$ownerNames[0]] $owners[$ownerNames[1]] $offset)
                if($selected.Count -ne 2){throw 'The two owners did not have overlapping successful assistance jobs'}
            } else {
                $selected=@(Find-CompletedForOwner $owners[$ownerNames[0]] $offset)
            }
            for ($ownerIndex=0; $ownerIndex -lt $ownerNames.Count; $ownerIndex++) {
                $name=$ownerNames[$ownerIndex]; $completed=$selected[$ownerIndex]
                if($null -eq $completed){throw "No successfully applied job for $name"}
                $required += [pscustomobject][ordered]@{dimension=$Dimension;chunk=$completed.chunk;owner=$owners[$name];id=$completed.id;sent_index=$completed.sent_index;completed_index=$completed.completed_index}
            }
        }
        $concurrentOwners = $Players -eq 1
        if ($assisted -and $Players -eq 2) {
            $concurrentOwners = (($required | Measure-Object sent_index -Maximum).Maximum -lt ($required | Measure-Object completed_index -Minimum).Minimum)
            if (-not $concurrentOwners) { throw 'Selected assistance jobs did not overlap' }
        }
        Complete-CorrectnessRegion
        $text=Log-Text
        $digestMap=@{}
        foreach($match in [regex]::Matches($text,'(?m)^.*\[CAWG\] stage\.digest stage=noise chunk=(?<chunk>-?\d+,-?\d+) .* digest=(?<digest>[0-9a-fA-F]+).* dimension=minecraft:'+([regex]::Escape($Dimension))+'\b')) {
            $digestMap[$match.Groups['chunk'].Value]=$match.Groups['digest'].Value.ToUpperInvariant()
        }
        $digests=@($digestMap.Keys | Sort-Object | ForEach-Object {[ordered]@{dimension=$Dimension;chunk=$_;digest=$digestMap[$_]}})
        if($digests.Count -eq 0){throw "No $Dimension digests were recorded"}
        $requiredRows=@($required | ForEach-Object {if(-not $digestMap.ContainsKey($_.chunk)){throw "Applied digest missing for $Dimension/$($_.chunk)"};[ordered]@{dimension=$_.dimension;chunk=$_.chunk;digest=$digestMap[$_.chunk];owner=$_.owner;id=$_.id}})
        [ordered]@{required_applied_chunks=$requiredRows;noise_digests=$digests;concurrent_owners=$concurrentOwners} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'correctness.json')
    } else {
        $warm=@();$measured=@();$total=$WarmupRuns+$MeasuredRepeats
        for($run=1;$run -le $total;$run++){
            $offset=(Log-Text).Length; $cpuBefore=$process.TotalProcessorTime.TotalMilliseconds; $wall=[Diagnostics.Stopwatch]::StartNew(); $phase=if($run -le $WarmupRuns){'warmup'}else{'measured'}; $number=if($phase -eq 'warmup'){$run}else{$run-$WarmupRuns}
            Send-Command ('say CAWG_SCENARIO_'+$phase.ToUpperInvariant()+'_BEGIN_'+$number); Start-Location $run; if($run -eq 1){Send-Command 'gamemode creative @a'}; Wait-Idle $offset 120 $run; Send-Command ('say CAWG_SCENARIO_'+$phase.ToUpperInvariant()+'_END_'+$number); $wall.Stop(); $slice=(Log-Text).Substring($offset); $record=Get-TickStatistics $slice ($process.TotalProcessorTime.TotalMilliseconds-$cpuBefore) $wall.Elapsed.TotalMilliseconds; $record.repeat=$number
            if($phase -eq 'warmup'){$warm += $record}else{$measured += $record}
        }
        [ordered]@{warmup_runs=$WarmupRuns;measured_repeats=$MeasuredRepeats;warmup=@($warm);measured=@($measured)} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'performance.json')
    }
    Send-Command 'save-all flush'; Send-Command 'stop'
    if(-not $process.WaitForExit(30000)){
        # The dedicated Java image has jdk.jcmd, but no jcmd.exe launcher.
        # Capture this owned server's stack before the bounded cleanup kills it.
        $dumpInfo=[Diagnostics.ProcessStartInfo]::new()
        $dumpInfo.FileName=$javaExecutable
        $dumpInfo.Arguments='-m jdk.jcmd/sun.tools.jcmd.JCmd '+$process.Id+' Thread.print -l'
        $dumpInfo.UseShellExecute=$false;$dumpInfo.CreateNoWindow=$true
        $dumpInfo.RedirectStandardOutput=$true;$dumpInfo.RedirectStandardError=$true
        $dump=[Diagnostics.Process]::new();$dump.StartInfo=$dumpInfo
        try{
            if($dump.Start()){
                $dumpHandle=$dump.Handle
                $dumpOut=$dump.StandardOutput.ReadToEndAsync();$dumpErr=$dump.StandardError.ReadToEndAsync()
                if(-not $dump.WaitForExit(10000)){$dump.Kill();$dump.WaitForExit()}
                $dumpOut.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $evidence 'shutdown-threads.txt')
                $dumpErr.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $evidence 'shutdown-threads-error.txt')
            }
        }catch{$_.Exception.ToString()|Set-Content -LiteralPath (Join-Path $evidence 'shutdown-diagnostic-error.txt')}finally{$dump.Dispose()}
    }
    if(-not $process.WaitForExit(30000) -or $process.ExitCode -ne 0){throw 'Server did not stop cleanly'}
    if((Log-Text) -match 'Mixin apply failed|Encountered an unexpected exception|Exception in server tick loop'){throw 'Server logged a runtime error'}
    $success=$true
} catch {
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $evidence 'failure.txt')
    $_.ScriptStackTrace | Add-Content -LiteralPath (Join-Path $evidence 'failure.txt')
    throw
} finally {
    $cleanupSafe=$true
    if($null -ne $process){if(-not $process.HasExited){try{$process.Kill();$process.WaitForExit(30000)}catch{$cleanupSafe=$false}};if(-not $process.HasExited){$cleanupSafe=$false};if($null -ne $stdout){$stdout.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $evidence 'stdout.log')};if($null -ne $stderr){$stderr.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $evidence 'stderr.log')};$process.Dispose()}
    if(Test-Path -LiteralPath $latest){Copy-Item -LiteralPath $latest -Destination (Join-Path $evidence 'latest.log')}
    $listener=@(Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue).Count -eq 0
    $cleanupSafe=$cleanupSafe -and $listener
    [ordered]@{success=$success;cleanup_safe=$cleanupSafe;loopback_only=$true;case=$case;dimension=$Dimension;mode=$Mode;players=$Players;purpose=$Purpose;prediction=$predictionEnabled} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'remote-result.json')
    $lock.Dispose(); Write-Output "SERVER_COMPLETE $case success=$success cleanup_safe=$cleanupSafe"
}
