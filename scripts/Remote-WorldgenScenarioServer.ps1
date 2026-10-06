[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Root,
    [Parameter(Mandatory)][ValidateSet('overworld','the_nether','the_end','fixture')][string]$Dimension,
    [Parameter(Mandatory)][ValidateSet('vanilla','assisted')][string]$Mode,
    [Parameter(Mandatory)][ValidateRange(1,2)][int]$Players,
    [Parameter(Mandatory)][ValidateSet('correctness','performance')][string]$Purpose,
    [Parameter(Mandatory)][ValidateRange(0,256)][int]$CacheEntries,
    [Parameter(Mandatory)][ValidateSet('true','false')][string]$Prediction,
    [Parameter(Mandatory)][ValidateRange(0,64)][int]$ValidationCells,
    [Parameter(Mandatory)][long]$Seed,
    [ValidateRange(1,10)][int]$WarmupRuns = 1,
    [ValidateRange(1,10)][int]$MeasuredRepeats = 3,
    [ValidateSet(0,1,2,4)][int]$ServerLogicalProcessors = 0,
    [ValidateSet(0,1,2,4)][int]$ServerJvmProcessors = 0,
    [ValidateSet('current','prepared','prefetch')][string]$PipelineProfile = 'prefetch',
    [ValidateSet('relocation','continuous')][string]$Movement = 'relocation',
    [ValidateRange(5,1000)][int]$CorrectnessDemandWaitMs = 1000,
    [ValidateRange(2,32)][int]$ViewDistance = 10,
    [switch]$MeasureFullView,
    [switch]$QuietRemoteTrace,
    [switch]$ServerFlightRecording,
    [ValidateSet('vanilla','local','cooperative')][string]$NoiseBackend='vanilla',
    [ValidateSet('standard','wide','deep')][string]$WindowProfile='standard',
    [ValidateSet('ready','overlap')][string]$RemoteApplicationProfile='ready',
    [ValidateRange(0,64)][int]$PrefetchLookahead=0,
    [ValidateSet('grid','surface','density','block','decisions','complete')][string]$RemoteWorkKind='grid',
    [ValidateSet('server','peer')][string]$CompleteVerification='server',
    [ValidateSet('inline','decoder')][string]$SectionPreparation='inline',
    [ValidateSet('off','serial','parallel')][string]$FeatureBackend='off',
    [switch]$DecorationDigest,
    [switch]$FeatureFixture,
    [string]$FeatureReplaySha256
)

# Remote half of the all-dimension trusted-raw scenario.  It owns only the
# Java process it starts, binds Minecraft to loopback, and writes evidence
# below the already-dedicated remote fixture root.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$resolved = [IO.Path]::GetFullPath($Root)
$temp = [IO.Path]::GetFullPath($env:TEMP).TrimEnd([IO.Path]::DirectorySeparatorChar)
$legacyDedicated = [IO.Path]::GetFullPath((Join-Path $temp 'WorldgenAssist-20260909'))
$dedicated = if($resolved -eq [IO.Path]::GetFullPath('E:/WorldgenAssist/port26.3')){[IO.Path]::GetFullPath('E:/WorldgenAssist')}else{$legacyDedicated}
if ($resolved -ne [IO.Path]::GetFullPath((Join-Path $dedicated 'port26.3'))) {
    throw 'Root must be the dedicated WorldgenAssist 26.3 test child'
}
$javaExecutable = Join-Path $dedicated 'java25/bin/java.exe'
if ($RemoteApplicationProfile -eq 'overlap' -and $PipelineProfile -ne 'prefetch') { throw 'Overlap measurement requires the bounded prefetch pipeline' }
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw 'Dedicated JDK 25 is missing' }
foreach ($target in @([IO.Path]::GetPathRoot($dedicated),$dedicated,$resolved, (Join-Path $resolved 'mods'), (Join-Path $resolved 'logs'), (Join-Path $resolved 'evidence'),(Join-Path $dedicated 'temp'))) {
    if ((Test-Path -LiteralPath $target) -and ((Get-Item -LiteralPath $target -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Scenario target must not be a reparse point: $target"
    }
}
$taskTemp=Join-Path $dedicated 'temp'
New-Item -ItemType Directory -Force -Path $taskTemp|Out-Null
$env:TEMP=$taskTemp
$env:TMP=$taskTemp
$predictionEnabled = [bool]::Parse($Prediction)
$dimensionId = if ($Dimension -eq 'fixture') { 'worldgen_assist:fixture' } else { 'minecraft:' + $Dimension }
if ($predictionEnabled -and $CacheEntries -eq 0) { throw 'Prediction requires CacheEntries greater than zero' }
if($DecorationDigest -and ($Purpose -ne 'correctness' -or $Dimension -ne 'overworld')){throw 'Decoration comparison is only the affected Overworld correctness fixture; disabled for performance'}
if($FeatureFixture -and (-not $DecorationDigest -or $Players -ne 2 -or $Seed -ne 8675309 -or $ViewDistance -gt 10)){throw 'Bounded diagnostic fixture required'}
$featureReplay=''
if($FeatureReplaySha256){
    $featureReplay=Join-Path $resolved 'scenario-staging/feature-replay.json'
    if(-not $FeatureFixture -or $FeatureReplaySha256 -notmatch '^[a-f0-9]{64}$' -or (Get-Item -LiteralPath $featureReplay).Length -gt 131072 -or (Get-FileHash -LiteralPath $featureReplay).Hash.ToLowerInvariant() -ne $FeatureReplaySha256){throw 'Replay identity differs'}
}

$lock = [IO.File]::Open((Join-Path $resolved 'scenario-run.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$case = 'scenario-' + $Dimension + '-' + $Mode + '-p' + $Players + '-' + $Purpose + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
$evidence = Join-Path $resolved ('evidence/' + $case)
$latest = Join-Path $resolved 'logs/latest.log'
$process = $null; $stdout = $null; $stderr = $null; $success = $false
$ownerNames = @('ScenarioOwnerA', 'ScenarioOwnerB')[0..($Players - 1)]
$owners = @{}
. (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1')
. (Join-Path $PSScriptRoot 'WorldgenScenarioConsole.ps1')
$measurementRadius=if($MeasureFullView){$ViewDistance}else{4}
$measurementShape=if($MeasureFullView){'view'}else{'square'}
$measurementOffsets=@(Get-WorldgenMeasurementOffsets $measurementRadius $measurementShape)

function Log-Text {
    return Read-WorldgenConsoleText $stdout
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
    # Index completions once. Re-scanning the complete log per sent job is
    # quadratic and can exceed the fixture deadline when assistance scales up.
    $completionIndices=@{}
    foreach($entry in [regex]::Matches($tail,'(?m)^.*\[CAWG\] job\.complete id=(?<id>\S+)\b.*$')){
        $completionIndices[$entry.Groups['id'].Value]=$entry.Index
    }
    foreach ($sent in [regex]::Matches($tail, '(?m)^.*\[CAWG\] job\.sent id=(?<id>\S+) chunk=(?<chunk>-?\d+,-?\d+).*owner=' + $escaped + '\b.*$')) {
        $id = $sent.Groups['id'].Value
        if ($completionIndices.ContainsKey($id)) {
            $results += [pscustomobject]@{ id=$id; chunk=$sent.Groups['chunk'].Value; sent_index=$sent.Index; completed_index=$completionIndices[$id] }
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
        $digests = @([regex]::Matches($tail, '(?m)^.*\[CAWG\] stage\.digest stage=noise .* dimension=' + [regex]::Escape($dimensionId) + '\b.*$'))
        $centresPresent = $true
        for ($ownerIndex = 0; $ownerIndex -lt $ownerNames.Count; $ownerIndex++) {
            $x = if ($ownerIndex -eq 0) { 16000 } else { -16000 }
            $z = if ($ownerIndex -eq 0) { -32000 } else { 32000 }
            $chunk = ([int]($x / 16)).ToString() + ',' + ([int]($z / 16)).ToString()
            if ($tail -notmatch ('stage\.digest stage=noise chunk=' + [regex]::Escape($chunk) + ' .* dimension=' + [regex]::Escape($dimensionId) + '\b')) { $centresPresent = $false }
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
function Wait-Idle([int]$Offset, [int]$Seconds, [int]$LocationIndex, [Diagnostics.Stopwatch]$RunClock) {
    # A new tick containing completed worldgen proves work started; two quiet
    # seconds then delimit a bounded repeat without treating a fixed sleep as a result.
    Wait-Log 'tick\.complete .*noise_completed=[1-9]' $Seconds $Offset | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds); $lastCompletedTicks = -1; $quietSince = $null
    $required = @{}
    $requiredFull = @{}
    $fullReadyMs = $null
    $ownerRegions = @()
    $ownerReadyMs = New-Object object[] $ownerNames.Count
    $baseX = 1000 + $LocationIndex * 256
    $baseZ = -2000 - $LocationIndex * 256
    for($ownerIndex=0;$ownerIndex -lt $ownerNames.Count;$ownerIndex++){
        $ownerRegion = @{}
        $cx=if($ownerIndex -eq 0){$baseX}else{-$baseX}
        $cz=if($ownerIndex -eq 0){$baseZ}else{-$baseZ}
        foreach($regionOffset in $measurementOffsets){$key=[string]($cx+$regionOffset.x)+','+($cz+$regionOffset.z);$required[$key]=$true;$ownerRegion[$key]=$true}
        $ownerRegions += $ownerRegion
    }
    foreach($key in $required.Keys){$requiredFull[$key]=$true}
    while ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 250
        $text=Log-Text; $tail=$text.Substring([Math]::Min($Offset,$text.Length))
        foreach($match in [regex]::Matches($tail,'stage\.complete stage=noise chunk=(?<chunk>-?\d+,-?\d+)\b')){$chunk=$match.Groups['chunk'].Value;[void]$required.Remove($chunk);foreach($region in $ownerRegions){[void]$region.Remove($chunk)}}
        foreach($match in [regex]::Matches($tail,'chunk\.full_ready chunk=(?<chunk>-?\d+,-?\d+)\b')){[void]$requiredFull.Remove($match.Groups['chunk'].Value)}
        if($null -eq $fullReadyMs -and $requiredFull.Count -eq 0){$fullReadyMs=[Math]::Round($RunClock.Elapsed.TotalMilliseconds,3)}
        for($ownerIndex=0;$ownerIndex -lt $ownerRegions.Count;$ownerIndex++){if($null -eq $ownerReadyMs[$ownerIndex] -and $ownerRegions[$ownerIndex].Count -eq 0){$ownerReadyMs[$ownerIndex]=[Math]::Round($RunClock.Elapsed.TotalMilliseconds,3)}}
        $completedTicks=@([regex]::Matches($tail,'tick\.complete .*noise_completed=[1-9]\d*')).Count
        $lastTick=[regex]::Matches($tail,'tick\.complete .*noise_active_end=(?<active>\d+)')
        $idle=$lastTick.Count -gt 0 -and $lastTick[$lastTick.Count-1].Groups['active'].Value -eq '0'
        if($completedTicks -ne $lastCompletedTicks){$lastCompletedTicks=$completedTicks;$quietSince=[DateTime]::UtcNow}
        elseif($idle -and $required.Count -eq 0 -and $requiredFull.Count -eq 0 -and $null -ne $quietSince -and ([DateTime]::UtcNow-$quietSince).TotalSeconds -ge 2){return [ordered]@{all_ms=($ownerReadyMs|Measure-Object -Maximum).Maximum;per_owner_ms=@($ownerReadyMs);full_ms=$fullReadyMs}}
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
        Send-Command ('execute in ' + $dimensionId + ' run tp ' + $name + ' ' + $x + ' 150 ' + $z)
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
                Send-Command ('execute in ' + $dimensionId + ' run tp ' + $name + ' ' + ($x + 1024) + ' 150 ' + ($z + 1024))
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
    # Java's Double.toString uses scientific notation for short durations.
    # Consume the whole token; accepting its mantissa alone inflates microseconds.
    $number='-?\d+(?:\.\d+)?(?:[Ee][+-]?\d+)?(?=\s|$)'
    $ticks = @([regex]::Matches($Text, ('tick\.complete .*elapsed_ms=(?<elapsed>'+$number+').*noise_completed=(?<complete>\d+).*noise_failed=(?<failed>\d+).*local_fallbacks_delta=(?<fallback>\d+)')) | ForEach-Object {
        [pscustomobject]@{ elapsed=[double]::Parse($_.Groups['elapsed'].Value,[Globalization.CultureInfo]::InvariantCulture); complete=[long]$_.Groups['complete'].Value; failed=[long]$_.Groups['failed'].Value; fallback=[long]$_.Groups['fallback'].Value }
    })
    $values = @($ticks | ForEach-Object { $_.elapsed } | Sort-Object)
    $mean = if ($values.Count) { ($values | Measure-Object -Average).Average } else { $null }
    $p95 = if ($values.Count) { $values[[Math]::Max(0, [Math]::Ceiling($values.Count * .95) - 1)] } else { $null }
    $completed = if ($ticks.Count) { ($ticks | Measure-Object complete -Sum).Sum } else { 0 }
    $failed = if ($ticks.Count) { ($ticks | Measure-Object failed -Sum).Sum } else { 0 }
    $fallback = if ($ticks.Count) { ($ticks | Measure-Object fallback -Sum).Sum } else { 0 }
    function Mean-LogValue([string]$Pattern) {
        $samples=@([regex]::Matches($Text,$Pattern) | ForEach-Object {[double]::Parse($_.Groups['value'].Value,[Globalization.CultureInfo]::InvariantCulture)})
        if($samples.Count){return [Math]::Round([double](($samples|Measure-Object -Average).Average),6)}
        return $null
    }
    $resultReceived='job\.result_received .*'
    $value='(?<value>'+$number+')'
    $attempted=$completed+$failed
    [ordered]@{
        server_cpu_ms=[Math]::Round($CpuMilliseconds,3); server_wall_ms=[Math]::Round($WallMilliseconds,3)
        tick_mean_ms=$mean; tick_p95_ms=$p95
        throughput_tasks_per_second=if($WallMilliseconds -gt 0){$completed*1000.0/$WallMilliseconds}else{$null}
        attempted_tasks=$attempted; completed_tasks=$completed
        timeouts=@([regex]::Matches($Text,'job\.timeout ')).Count; fallbacks=$fallback; failed_tasks=$failed
        client_compute_mean_ms=Mean-LogValue ($resultReceived+'client_compute_ms='+$value)
        client_encode_mean_ms=Mean-LogValue ($resultReceived+'client_encode_ms='+$value)
        rtt_mean_ms=Mean-LogValue ($resultReceived+'rtt_ms='+$value)
        server_decode_mean_ms=Mean-LogValue ($resultReceived+'server_decode_ms='+$value)
        encoded_bytes_mean=Mean-LogValue ($resultReceived+'encoded_bytes=(?<value>\d+)')
        validation_mean_ms=Mean-LogValue ('job\.validation_complete .*validation_ms='+$value)
        apply_mean_ms=Mean-LogValue ('job\.complete .*apply_ms='+$value)
        remote_total_mean_ms=Mean-LogValue ('job\.complete .*total_ms='+$value)
        validation_prepare_mean_ms=Mean-LogValue ('job\.validation_ready .*prepare_ms='+$value)
        validation_compare_mean_ms=Mean-LogValue ('job\.validation_complete .*compare_ms='+$value)
        decode_queue_mean_ms=Mean-LogValue ('job\.result_decoded .*decode_queue_ms='+$value)
        registration_mean_ms=Mean-LogValue ('job\.registered .*registration_ms='+$value)
        request_queue_mean_ms=Mean-LogValue ('job\.request_dispatch .*request_queue_ms='+$value)
        ingress_queue_mean_ms=Mean-LogValue ('job\.result_ingress .*ingress_queue_ms='+$value)
        result_claim_mean_ms=Mean-LogValue ('job\.result_ingress .*claim_ms='+$value)
        network_ingress_count=if($QuietRemoteTrace){$null}else{@([regex]::Matches($Text,'job\.result_ingress .*path=network\b')).Count}
        request_batch_count=@([regex]::Matches($Text,'jobs\.batch_sent ')).Count
        prefetch_sent=@([regex]::Matches($Text,'job\.sent .*source=prefetch\b')).Count
        prefetch_applied=@([regex]::Matches($Text,'job\.complete .*source=prefetch\b')).Count
        ready_cache_used=@([regex]::Matches($Text,'cache\.hit ')).Count
        demand_wait_fallbacks=@([regex]::Matches($Text,'job\.fallback_local .*reason=TimeoutException')).Count
    }
}
function Start-Location([int]$Index) {
    # Distinct far-apart chunks avoid reuse of a prior repeat's generated region.
    $baseX = 16000 + ($Index * 4096); $baseZ = -32000 - ($Index * 4096)
    for ($index = 0; $index -lt $ownerNames.Count; $index++) {
        $x = if ($index -eq 0) { $baseX } else { -$baseX }
        $z = if ($index -eq 0) { $baseZ } else { -$baseZ }
        $startX = if($Movement -eq 'continuous'){$x-128}else{$x}
        Send-Command ('execute in ' + $dimensionId + ' run tp ' + $ownerNames[$index] + ' ' + $startX + ' 150 ' + $z)
    }
    if($Movement -eq 'continuous'){
        for($step=1;$step -le 16;$step++){
            Start-Sleep -Milliseconds 250
            for($index=0;$index -lt $ownerNames.Count;$index++){
                $targetX=if($index -eq 0){$baseX}else{-$baseX};$targetZ=if($index -eq 0){$baseZ}else{-$baseZ}
                Send-Command ('execute in '+$dimensionId+' run tp '+$ownerNames[$index]+' '+($targetX-128+$step*8)+' 150 '+$targetZ)
            }
        }
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
        if(-not $FeatureFixture){foreach($xs in @(@(-10,0),@(1,10))){foreach($zs in @(@(-10,0),@(1,10))){
            Send-Command ('execute in ' + $dimensionId + ' run forceload add ' + (($cx+$xs[0])*16) + ' ' + (($cz+$zs[0])*16) + ' ' + (($cx+$xs[1])*16) + ' ' + (($cz+$zs[1])*16))
        }}}
        for ($dx=-10; $dx -le 10; $dx++) { for ($dz=-10; $dz -le 10; $dz++) { $required[([string]($cx+$dx)+','+($cz+$dz))] = $true } }
    }
    $decorationRemaining=if($DecorationDigest){$required.Clone()}else{@{}}
    if($DecorationDigest){@($required.Keys|Sort-Object)|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'decoration-required-chunks.json')}
    $deadline = [DateTime]::UtcNow.AddSeconds($(if($DecorationDigest){300}else{120}))
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($match in [regex]::Matches((Log-Text), 'stage\.digest stage=noise chunk=(?<chunk>-?\d+,-?\d+) .* dimension='+[regex]::Escape($dimensionId)+'\b')) {
            $required.Remove($match.Groups['chunk'].Value)
        }
        if($DecorationDigest){foreach($entry in [regex]::Matches((Log-Text),'stage\.digest stage=decoration chunk=(-?\d+,-?\d+) .* dimension='+[regex]::Escape($dimensionId)+'\b')){$decorationRemaining.Remove($entry.Groups[1].Value)}}
        if ($required.Count -eq 0 -and $decorationRemaining.Count -eq 0 -and (-not $FeatureFixture -or (Log-Text) -match 'fixture.complete ')) { return }
        if ($process.HasExited) { throw 'Server exited before the comparison region completed' }
        Start-Sleep -Milliseconds 500
    }
    throw "Missing $($required.Count) comparison-region NOISE and $($decorationRemaining.Count) final-decoration digests"
}

try {
    New-Item -ItemType Directory -Force -Path $evidence | Out-Null
    Write-Output "SERVER_CASE $case"
    Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $evidence 'runner.ps1')
    if ((Get-Content -LiteralPath (Join-Path $resolved 'eula.txt') -Raw) -notmatch '(?m)^eula=true\s*$') { throw 'Existing EULA acceptance is required' }
    if (Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue) { throw 'Loopback fixture port 25585 is already occupied' }
    $viewDistance = $ViewDistance
    @('server-ip=127.0.0.1','server-port=25585','online-mode=false','white-list=false','enforce-whitelist=false',("level-name=$case"),("level-seed=$Seed"),("view-distance=$viewDistance"),'simulation-distance=3',("max-players=$Players"),'gamemode=spectator','difficulty=peaceful','enable-rcon=false','enable-query=false','pause-when-empty-seconds=-1','spawn-protection=0') | Set-Content -LiteralPath (Join-Path $resolved 'server.properties')
    if ($Dimension -eq 'fixture') {
        $pack = Join-Path $resolved ($case + '/datapacks/worldgenassist-fixture')
        $definition = Join-Path $pack 'data/worldgen_assist/dimension'
        New-Item -ItemType Directory -Force -Path $definition | Out-Null
        '{"pack":{"description":"Worldgen Assist custom dimension fixture","min_format":121,"max_format":121}}' |
            Set-Content -LiteralPath (Join-Path $pack 'pack.mcmeta')
        '{"type":"minecraft:overworld","generator":{"type":"minecraft:noise","settings":"minecraft:overworld","biome_source":{"type":"minecraft:fixed","biome":"minecraft:plains"}}}' |
            Set-Content -LiteralPath (Join-Path $definition 'fixture.json')
        Add-Content -LiteralPath (Join-Path $resolved 'server.properties') -Value 'initial-enabled-packs=vanilla,file/worldgenassist-fixture'
    }
    [ordered]@{dimension=$Dimension;mode=$Mode;players=$Players;purpose=$Purpose;cache_entries=$CacheEntries;prediction=$predictionEnabled;validation_cells=$ValidationCells;seed=$Seed;warmup_runs=$WarmupRuns;measured_repeats=$MeasuredRepeats;view_distance=$viewDistance;timeout_ms=30000;server_logical_processors=$ServerLogicalProcessors;pipeline_profile=$PipelineProfile;movement=$Movement;quiet_remote_trace=[bool]$QuietRemoteTrace;listener='127.0.0.1:25585'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'scenario-config.json')
    [ordered]@{radius=$measurementRadius;shape=$measurementShape;expected_chunks_per_owner=$measurementOffsets.Count} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'measurement-region.json')
    Copy-Item -LiteralPath (Join-Path $resolved 'server.properties') -Destination (Join-Path $evidence 'server.properties')
    [ordered]@{base_ms=if($Purpose -eq 'correctness'){$CorrectnessDemandWaitMs}else{100};adaptive=($PipelineProfile -ne 'current');maximum_ms=if($PipelineProfile -eq 'current'){30000}elseif($Purpose -eq 'correctness'){[Math]::Min(1000,$CorrectnessDemandWaitMs*2)}else{200}} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'demand-wait-config.json')
    $start = [Diagnostics.ProcessStartInfo]::new(); $start.FileName = $javaExecutable; $start.Arguments = $(if($MeasureFullView){'-Xmx6G'}else{'-Xmx3G'})+' -Djava.io.tmpdir="'+$taskTemp+'" -Dworldgen_assist.remote.diagnostics=true -jar "' + (Join-Path $resolved 'fabric-server-launch.jar') + '" nogui'; $start.WorkingDirectory = $resolved
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true; $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    if($ServerJvmProcessors -gt 0){$start.Arguments='-XX:ActiveProcessorCount='+$ServerJvmProcessors+' '+$start.Arguments}
    if($QuietRemoteTrace){$start.Arguments='-Dworldgen_assist.remote.trace_jobs=false '+$start.Arguments}
    if($ServerFlightRecording){
        $profilePath=(Join-Path $evidence 'server.jfr').Replace('\','/')
        $jfcPath=(Join-Path $resolved 'scenario-staging/profile.jfc').Replace('\','/')
        Copy-Item -LiteralPath $jfcPath -Destination (Join-Path $evidence 'profile.jfc')
        $start.Arguments='-XX:StartFlightRecording="filename='+$profilePath+',settings='+$jfcPath+',dumponexit=true,maxsize=256m" '+$start.Arguments
    }
    [ordered]@{enabled=[bool]$ServerFlightRecording;warmup_runs=$WarmupRuns;measured_repeats=$MeasuredRepeats} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'flight-recording-config.json')
    [ordered]@{noise_backend=$NoiseBackend;worker_override=$null;queue_override=$null} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'noise-backend-config.json')
    $start.EnvironmentVariables['WORLDGEN_ASSIST_NOISE_BACKEND']=$NoiseBackend
    $start.EnvironmentVariables['WORLDGEN_ASSIST_FEATURE_BACKEND']=$FeatureBackend
    $start.EnvironmentVariables['WORLDGEN_ASSIST_DECORATION_DIGEST']=([bool]$DecorationDigest).ToString().ToLowerInvariant()
    $start.EnvironmentVariables['WORLDGEN_ASSIST_FEATURE_FIXTURE']=([bool]$FeatureFixture).ToString().ToLowerInvariant()
    $start.EnvironmentVariables['WORLDGEN_ASSIST_FEATURE_REPLAY']=$featureReplay
    if($featureReplay){Copy-Item -LiteralPath $featureReplay -Destination (Join-Path $evidence 'feature-replay.json')}
    [ordered]@{mode=$FeatureBackend;workers=switch($FeatureBackend){'serial'{1} 'parallel'{2} default{0}};capacity=128;message_reservation=18;decoration_digest=[bool]$DecorationDigest} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'feature-backend-config.json')
    foreach($setting in @('WORLDGEN_ASSIST_LOCAL_WORKERS','WORLDGEN_ASSIST_LOCAL_QUEUE_PER_WORKER')){$start.EnvironmentVariables.Remove($setting)}
    $assisted = $Mode -eq 'assisted'
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE'] = if($assisted){'true'}else{'false'}
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE'] = if($assisted){'trusted_raw'}else{'deny'}
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS'] = '30000'
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT'] = switch($WindowProfile){'deep'{'64'} 'wide'{'32'} default{'8'}}
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_OWNER_WINDOW'] = switch($WindowProfile){'deep'{'32'} 'wide'{'16'} default{'4'}}
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_PREFETCH_LOOKAHEAD'] = [string]$PrefetchLookahead
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_READY_SURFACE_ONLY'] = ($RemoteApplicationProfile -eq 'ready').ToString().ToLowerInvariant()
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_WORK_KIND'] = $RemoteWorkKind
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_ALLOW_TERRAIN_DECISIONS'] = ($RemoteWorkKind -eq 'decisions').ToString().ToLowerInvariant()
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_ALLOW_COMPLETE_TERRAIN'] = ($RemoteWorkKind -eq 'complete').ToString().ToLowerInvariant()
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_COMPLETE_VERIFICATION'] = $CompleteVerification
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_PREPARE_SECTIONS'] = ($SectionPreparation -eq 'decoder').ToString().ToLowerInvariant()
    [ordered]@{mode=$SectionPreparation;existing_decoder_workers=2;new_executor=$false;world_access=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'section-preparation-config.json')
    [ordered]@{selected_work_kind=$RemoteWorkKind;terrain_decisions_allowed=($RemoteWorkKind -eq 'decisions');complete_terrain_allowed=($RemoteWorkKind -eq 'complete');complete_verification=$CompleteVerification;complete_initial_audits=2;complete_server_audit_denominator=8;complete_peer_audit_denominator=64;complete_audit_policy=if($CompleteVerification -eq 'peer'){'first_two_successful_per_owner_then_private_one_in_64_if_distinct_trusted_peer_available_otherwise_one_in_8;peer_admission_race_forces_server_full_audit;every_peer_result_requires_entire_agreement'}else{'first_two_successful_then_private_one_in_eight'}} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'remote-work-kind-config.json')
    [ordered]@{profile=$RemoteApplicationProfile;ready_surface_only=($RemoteApplicationProfile -eq 'ready');base_wait_ms=if($PipelineProfile -eq 'current'){30000}elseif($Purpose -eq 'correctness'){$CorrectnessDemandWaitMs}else{100};maximum_wait_ms=if($PipelineProfile -eq 'current'){30000}elseif($Purpose -eq 'correctness'){[Math]::Min(1000,$CorrectnessDemandWaitMs*2)}else{200}} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'remote-application-config.json')
    [ordered]@{profile=$WindowProfile;owner_window=[int]$start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_OWNER_WINDOW'];total_window=[int]$start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT'];lookahead=$PrefetchLookahead} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'pipeline-window-config.json')
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES'] = [string]$CacheEntries; $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_PREDICTION'] = $predictionEnabled.ToString().ToLowerInvariant(); $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS'] = [string]$ValidationCells
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_PREFETCH'] = ($PipelineProfile -eq 'prefetch').ToString().ToLowerInvariant()
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_PREPARE_VALIDATION'] = ($PipelineProfile -ne 'current').ToString().ToLowerInvariant()
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_ADAPTIVE_DEMAND_WAIT'] = ($PipelineProfile -ne 'current').ToString().ToLowerInvariant()
    $start.EnvironmentVariables['WORLDGEN_ASSIST_REMOTE_DEMAND_WAIT_MS'] = if($Purpose -eq 'correctness'){[string]$CorrectnessDemandWaitMs}else{'100'}
    $start.EnvironmentVariables['WORLDGEN_ASSIST_NOISE_DIGEST'] = if($Purpose -eq 'correctness'){'true'}else{'false'}
    $start.EnvironmentVariables['WORLDGEN_ASSIST_TICK_BENCHMARK'] = if($Purpose -eq 'performance'){'true'}else{'false'}
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
    if (-not $process.Start()) { throw 'Server did not start' }
    $processHandle = $process.Handle # Hold immediately: Windows PowerShell 5 can lose it after an early exit.
    $availableMask = $process.ProcessorAffinity.ToInt64()
    $appliedMask = $availableMask
    if ($ServerLogicalProcessors -gt 0) {
        $appliedMask = [long]0
        $selected = 0
        $availableCount = 0
        for ($bit = 0; $bit -lt 63 -and $selected -lt $ServerLogicalProcessors; $bit++) {
            $candidate = [long]1 -shl $bit
            if (($availableMask -band $candidate) -ne 0) { $appliedMask = $appliedMask -bor $candidate; $selected++ }
        }
        if ($selected -ne $ServerLogicalProcessors) { throw "Only $selected eligible logical processors are available" }
        for ($bit = 0; $bit -lt 63; $bit++) { if (($availableMask -band ([long]1 -shl $bit)) -ne 0) { $availableCount++ } }
        if ($availableCount -le $ServerLogicalProcessors) { throw 'CPU affinity would not reduce available logical processors' }
        $process.ProcessorAffinity = [IntPtr]::new($appliedMask)
        $process.Refresh()
        if ($process.ProcessorAffinity.ToInt64() -ne $appliedMask) { throw 'Server CPU affinity was not applied' }
    }
    [ordered]@{requested_logical_processors=$ServerLogicalProcessors;available_affinity_mask=$availableMask;applied_affinity_mask=$process.ProcessorAffinity.ToInt64();java_pid=$process.Id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'server-cpu-limit.json')
    $stdout = Start-WorldgenConsoleCapture $process (Join-Path $evidence 'stdout.log'); $stderr = $process.StandardError.ReadToEndAsync()
    [ordered]@{requested_active_processor_count=$ServerJvmProcessors;arguments=$start.Arguments} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'server-jvm-cpu-limit.json')
    "java_pid=$($process.Id); java_handle=$processHandle; controller_pid=$PID" | Set-Content -LiteralPath (Join-Path $evidence 'process-ownership.txt')
    $freshDeadline=[DateTime]::UtcNow.AddSeconds(60)
    while($true){
        $freshLog=Get-Item -LiteralPath $latest -ErrorAction SilentlyContinue
        if($null -ne $freshLog -and $freshLog.LastWriteTime -ge $process.StartTime){break}
        if($process.HasExited -or [DateTime]::UtcNow -gt $freshDeadline){throw 'No fresh server log'}
        Start-Sleep -Milliseconds 250
    }
    Wait-Log 'Done \(' 180 | Out-Null
    $cpuContext=[regex]::Match((Log-Text),'server\.cpu_context available_processors=(\d+)')
    if(-not $cpuContext.Success -and $ServerJvmProcessors -gt 0){throw 'Actual JVM availableProcessors evidence is missing'}
    $observedProcessors=if($cpuContext.Success){[int]$cpuContext.Groups[1].Value}else{0}
    if($ServerJvmProcessors -gt 0 -and $observedProcessors -ne $ServerJvmProcessors){throw 'Actual JVM processor count differs from requested limit'}
    [ordered]@{requested_active_processor_count=$ServerJvmProcessors;observed_available_processors=$observedProcessors;arguments=$start.Arguments} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'server-jvm-cpu-limit.json')
    Send-Command 'gamerule minecraft:spectators_generate_chunks false'; Write-Output "SERVER_READY $case"
    foreach($name in $ownerNames){ Wait-Log ([regex]::Escape($name)+' joined the game') 180 | Out-Null; if($assisted){$owners[$name]=New-OwnerUuid $name; Wait-Log ('worker\.register owner='+[regex]::Escape($owners[$name])+' status=ACCEPTED') 60 | Out-Null} }
    $owners | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'owner-map.json')
    if ($assisted) { Warm-AssistedOwners }
    $fixtureOffset=(Log-Text).Length
    if($FeatureFixture){Send-Command 'worldgenassist_feature_fixture_start';Wait-Log 'fixture.armed tickets=882 features=1458 spawns=1250 frozen=true' 30|Out-Null}
    if($Purpose -eq 'correctness') {
        if($FeatureFixture){$offset=$fixtureOffset}else{$offset=(Log-Text).Length; Start-Location 0; Send-Command 'gamemode creative @a'}
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
        foreach($match in [regex]::Matches($text,'(?m)^.*\[CAWG\] stage\.digest stage=noise chunk=(?<chunk>-?\d+,-?\d+) .* digest=(?<digest>[0-9a-fA-F]+).* dimension='+([regex]::Escape($dimensionId))+'\b')) {
            $digestMap[$match.Groups['chunk'].Value]=$match.Groups['digest'].Value.ToUpperInvariant()
        }
        $digests=@($digestMap.Keys | Sort-Object | ForEach-Object {[ordered]@{dimension=$Dimension;chunk=$_;digest=$digestMap[$_]}})
        if($digests.Count -eq 0){throw "No $Dimension digests were recorded"}
        $requiredRows=@($required | ForEach-Object {if(-not $digestMap.ContainsKey($_.chunk)){throw "Applied digest missing for $Dimension/$($_.chunk)"};[ordered]@{dimension=$_.dimension;chunk=$_.chunk;digest=$digestMap[$_.chunk];owner=$_.owner;id=$_.id}})
        [ordered]@{required_applied_chunks=$requiredRows;noise_digests=$digests;concurrent_owners=$concurrentOwners} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'correctness.json')
    } else {
        $warm=@();$measured=@();$profileWindows=@();$total=$WarmupRuns+$MeasuredRepeats
        for($run=1;$run -le $total;$run++){
            Write-Output "SERVER_REPEAT_BEGIN location=$run"
            $offset=(Log-Text).Length; $cpuBefore=$process.TotalProcessorTime.TotalMilliseconds; $wall=[Diagnostics.Stopwatch]::StartNew(); $phase=if($run -le $WarmupRuns){'warmup'}else{'measured'}; $number=if($phase -eq 'warmup'){$run}else{$run-$WarmupRuns}
            $profileStart=[DateTimeOffset]::UtcNow.ToString('o')
            Send-Command ('say CAWG_SCENARIO_'+$phase.ToUpperInvariant()+'_BEGIN_'+$number); Start-Location $run; if($run -eq 1){Send-Command 'gamemode creative @a'}; $regionReady=Wait-Idle $offset $(if($MeasureFullView){600}else{120}) $run $wall; Send-Command ('say CAWG_SCENARIO_'+$phase.ToUpperInvariant()+'_END_'+$number); $wall.Stop(); $slice=(Log-Text).Substring($offset); $record=Get-TickStatistics $slice ($process.TotalProcessorTime.TotalMilliseconds-$cpuBefore) $wall.Elapsed.TotalMilliseconds; $record.server_region_ready_ms=$regionReady.all_ms; $record.server_full_region_ready_ms=$regionReady.full_ms; $record.server_owner_region_ready_ms=$regionReady.per_owner_ms; $record.repeat=$number
            if($phase -eq 'warmup'){$warm += $record}else{$measured += $record}
            $profileWindows+= [ordered]@{phase=$phase;repeat=$number;start_utc=$profileStart;end_utc=[DateTimeOffset]::UtcNow.ToString('o')}
            if($ServerFlightRecording){$profileWindows | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'flight-recording-windows.json')}
            [ordered]@{warmup_runs=$WarmupRuns;measured_repeats=$MeasuredRepeats;warmup=@($warm);measured=@($measured)} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'performance.json')
            Write-Output "SERVER_REPEAT_COMPLETE phase=$phase repeat=$number full_ms=$($record.server_full_region_ready_ms) tasks=$($record.completed_tasks)"
            if($MeasureFullView -and $phase -eq 'measured'){
                Write-Output "SERVER_RECEIPT_WAIT $number $run"
                $receiptDeadline=[DateTime]::UtcNow.AddSeconds(300)
                while(-not(Test-Path -LiteralPath (Join-Path $evidence "receipt-$number.ack"))){
                    if($process.HasExited -or [DateTime]::UtcNow -gt $receiptDeadline){throw "Client full-view receipt missing for repeat $number"}
                    Start-Sleep -Milliseconds 1000
                }
            }
        }
        [ordered]@{warmup_runs=$WarmupRuns;measured_repeats=$MeasuredRepeats;warmup=@($warm);measured=@($measured)} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'performance.json')
    }
    # Generated 26.3 PlayerList.removeAll indexes a list that a synchronous
    # disconnect may shrink. Close these owned fixture clients individually
    # before stop, after every measurement and receipt has been recorded.
    foreach($name in $ownerNames){
        $disconnectOffset=(Log-Text).Length
        Send-Command ('kick '+$name+' Fixture measurements complete')
        Wait-Log ([regex]::Escape($name)+' lost connection:') 30 $disconnectOffset | Out-Null
    }
    [ordered]@{owners=$ownerNames;all_disconnects_observed=$true;phase='after_measurement_before_save_stop'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'fixture-disconnects.json')
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
    if($null -ne $process){if(-not $process.HasExited){try{$process.Kill();$process.WaitForExit(30000)}catch{$cleanupSafe=$false}};if(-not $process.HasExited){$cleanupSafe=$false};if($null -ne $stdout){Save-WorldgenConsoleEvidence $stdout $latest (Join-Path $evidence 'latest.log')};if($null -ne $stderr){$stderr.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $evidence 'stderr.log')};$process.Dispose()}
    $listener=@(Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue).Count -eq 0
    $cleanupSafe=$cleanupSafe -and $listener
    [ordered]@{success=$success;cleanup_safe=$cleanupSafe;loopback_only=$true;case=$case;dimension=$Dimension;mode=$Mode;players=$Players;purpose=$Purpose;prediction=$predictionEnabled} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'remote-result.json')
    $lock.Dispose(); Write-Output "SERVER_COMPLETE $case success=$success cleanup_safe=$cleanupSafe"
}
