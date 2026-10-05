[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunRoot, [string]$AnalysisDirectory, [string[]]$ScenarioResultPath = @(),
    [string]$BaselineResultPath,[string]$CandidateResultPath)

# Read-only final aggregation. The validation runner invokes this only after all
# scenario processes finish; this script never launches a game or tails logs.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
$root = (Resolve-Path -LiteralPath $RunRoot -ErrorAction Stop).Path
$explicitPair= -not [string]::IsNullOrWhiteSpace($BaselineResultPath) -or -not [string]::IsNullOrWhiteSpace($CandidateResultPath)
if($explicitPair){
    if(-not $BaselineResultPath -or -not $CandidateResultPath -or $ScenarioResultPath.Count){throw 'Explicit comparison requires exactly baseline and candidate paths'}
    $BaselineResultPath=(Resolve-Path -LiteralPath $BaselineResultPath).Path
    $CandidateResultPath=(Resolve-Path -LiteralPath $CandidateResultPath).Path
    if($BaselineResultPath -eq $CandidateResultPath){throw 'Cannot compare a scenario to itself'}
    $ScenarioResultPath=@($BaselineResultPath,$CandidateResultPath)
}
$output = if ([string]::IsNullOrWhiteSpace($AnalysisDirectory)) { Join-Path $root 'analysis' } else { [IO.Path]::GetFullPath($AnalysisDirectory) }
. (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1')
if (Test-Path -LiteralPath $output) { throw "Refusing to overwrite analysis evidence: $output" }
New-Item -ItemType Directory -Force -Path $output | Out-Null

function Value([object]$Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}
function Issue([Collections.Generic.List[object]]$List, [string]$Code, [string]$Message, [string]$Path) {
    $List.Add([ordered]@{code=$Code;message=$Message;path=$Path})
}
function Number([object]$Value) {
    if ($null -eq $Value) { return $null }
    try { $n=[double]::Parse([string]$Value,$culture); if ([double]::IsNaN($n) -or [double]::IsInfinity($n)) { return $null }; return $n } catch { return $null }
}
function Median([double[]]$Values) {
    $sorted=@($Values|Sort-Object); if($sorted.Count -eq 0){return $null}; $middle=[Math]::Floor($sorted.Count/2)
    if($sorted.Count%2 -eq 0){return ($sorted[$middle-1]+$sorted[$middle])/2.0}; return $sorted[$middle]
}
function P95([double[]]$Values) {
    $sorted=@($Values|Sort-Object); if($sorted.Count -eq 0){return $null}; return $sorted[[Math]::Max(0,[Math]::Ceiling($sorted.Count*.95)-1)]
}
function Stats([double[]]$Values) {
    if($Values.Count -eq 0){return $null}; return [ordered]@{count=$Values.Count;median=(Median $Values);p95=(P95 $Values);minimum=($Values|Measure-Object -Minimum).Minimum;maximum=($Values|Measure-Object -Maximum).Maximum}
}
function PairedStats([double[]]$Left,[double[]]$Right,[bool]$HigherIsBetter=$false) {
    $rows=@();$ratios=@();$improved=0
    for($i=0;$i -lt $Left.Count;$i++){
        $ratio=if($Left[$i] -gt 0){$Right[$i]/$Left[$i]}else{$null}
        if($null -ne $ratio){$ratios+=$ratio}
        if($(if($HigherIsBetter){$Right[$i] -gt $Left[$i]}else{$Right[$i] -lt $Left[$i]})){$improved++}
        $rows+=[ordered]@{repeat=$i+1;vanilla=$Left[$i];assisted=$Right[$i];delta=$Right[$i]-$Left[$i];ratio=$ratio}
    }
    return [ordered]@{definition='Same repeat/coordinates compared before aggregation; separate from ratio of condition medians';rows=$rows;ratios=(Stats ([double[]]$ratios));improved_repeats=$improved;repeats=$Left.Count}
}
function Key([object]$Result) {
    return @([string](Value $Result 'dimension'),[string](Value $Result 'players'),[string](Value $Result 'purpose'),[string](Value $Result 'cache_entries'),[string](Value $Result 'prediction'),[string](Value $Result 'validation_cells')) -join '|'
}
function DigestMap([object]$Result,[Collections.Generic.List[object]]$Issues,[string]$Path) {
    $map=@{}; $correctness=Value $Result 'correctness'
    foreach($row in @(Value $correctness 'noise_digests')) {
        $dimension=[string](Value $row 'dimension'); $chunk=[string](Value $row 'chunk'); $digest=[string](Value $row 'digest')
        if($dimension -notin @('overworld','the_nether','the_end','fixture') -or $chunk -notmatch '^-?\d+,-?\d+$' -or $digest -notmatch '^[A-Fa-f0-9]{64}$'){ Issue $Issues 'INVALID_DIGEST' 'A digest row is malformed.' $Path; continue }
        $key="$dimension|$chunk"; $digest=$digest.ToLowerInvariant()
        if($map.ContainsKey($key) -and $map[$key] -ne $digest){Issue $Issues 'CONFLICTING_DIGEST' "Conflicting digest values for $key." $Path;continue};$map[$key]=$digest
    };return $map
}
function AppliedChunks([object]$Result,[Collections.Generic.List[object]]$Issues,[string]$Path) {
    $chunks=@();$correctness=Value $Result 'correctness'
    foreach($row in @(Value $correctness 'required_applied_chunks')){
        $dimension=[string](Value $row 'dimension');$chunk=[string](Value $row 'chunk')
        if($dimension -notin @('overworld','the_nether','the_end','fixture') -or $chunk -notmatch '^-?\d+,-?\d+$'){Issue $Issues 'INVALID_APPLIED_CHUNK' 'An applied chunk row is malformed.' $Path;continue};$chunks+="$dimension|$chunk"
    };return @($chunks|Sort-Object -Unique)
}
function Samples([object]$Result,[Collections.Generic.List[object]]$Issues,[string]$Path) {
    $performance=Value $Result 'performance';if($null -eq $performance){Issue $Issues 'MISSING_PERFORMANCE' 'Performance scenario has no performance object.' $Path;return $null}
    $warmup=Value $performance 'warmup_runs';$repeatCount=Value $performance 'measured_repeats';$samples=@(Value $performance 'measured')
    if($null -eq (Number $warmup) -or [int]$warmup -lt 1){Issue $Issues 'INVALID_WARMUP_COUNT' 'At least one excluded warm-up is required.' $Path}
    if($null -eq (Number $repeatCount) -or [int]$repeatCount -lt 1){Issue $Issues 'INVALID_REPEAT_COUNT' 'Measured repeat count is absent.' $Path;return $null}
    if($samples.Count -ne [int]$repeatCount){Issue $Issues 'MEASURED_REPEAT_MISMATCH' 'Measured rows do not equal measured_repeats.' $Path}
    $ids=@($samples|ForEach-Object{Value $_ 'repeat'});if(@($ids|Sort-Object -Unique).Count -ne $samples.Count){Issue $Issues 'DUPLICATE_REPEAT' 'Measured repeat IDs are not unique.' $Path}
    return $samples
}

function ClientReceiptSamples([object]$Record) {
    $directory=Split-Path -Parent $Record.path
    $regionPath=Join-Path $directory 'remote-evidence/measurement-region.json'
    $region=if(Test-Path -LiteralPath $regionPath){Get-Content -LiteralPath $regionPath -Raw|ConvertFrom-Json}else{[pscustomobject]@{radius=4;shape='square';expected_chunks_per_owner=81}}
    $offsets=@(Get-WorldgenMeasurementOffsets $region.radius $region.shape)
    if($offsets.Count -ne $region.expected_chunks_per_owner){throw 'Measurement-region count disagrees with its geometry'}
    $repeatCount=[int](Value (Value $Record.result 'performance') 'measured_repeats')
    $warmupCount=[int](Value (Value $Record.result 'performance') 'warmup_runs')
    $playerCount=[int](Value $Record.result 'players')
    $ready=@();$coverage=@()
    for($repeat=1;$repeat -le $repeatCount;$repeat++){
        $ownerReady=@();$ownerCoverage=@()
        $location=$warmupCount+$repeat
        $baseX=1000+$location*256;$baseZ=-2000-$location*256
        for($owner=0;$owner -lt $playerCount;$owner++){
            $log=Get-Content -LiteralPath (Join-Path $directory "clients/owner-$owner/client/logs/latest.log") -Raw
            $begin=[regex]::Match($log,"benchmark\.client_marker phase=BEGIN repeat=$repeat nanos=(?<nanos>\d+)")
            $centreX=if($owner -eq 0){$baseX}else{-$baseX}
            $centreZ=if($owner -eq 0){$baseZ}else{-$baseZ}
            $expected=@{};foreach($offset in $offsets){$expected[([string]($centreX+$offset.x)+','+($centreZ+$offset.z))]=$true}
            $chunks=@{};$latest=0L
            if($begin.Success){
                $started=[long]$begin.Groups['nanos'].Value
                foreach($match in [regex]::Matches($log,"benchmark\.chunk_received repeat=$repeat chunk=(?<x>-?\d+),(?<z>-?\d+) nanos=(?<nanos>\d+)")){
                    $x=[int]$match.Groups['x'].Value;$z=[int]$match.Groups['z'].Value;$nanos=[long]$match.Groups['nanos'].Value
                    if($expected.ContainsKey("$x,$z") -and $nanos -ge $started -and -not $chunks.ContainsKey("$x,$z")){
                        $chunks["$x,$z"]=$true
                        if($nanos -gt $latest){$latest=$nanos}
                    }
                }
            }
            $ownerCoverage+=$chunks.Count
            if($chunks.Count -eq $offsets.Count){$ownerReady+=($latest-$started)/1000000.0}
        }
        $coverage+=,[ordered]@{repeat=$repeat;owner_chunk_counts=$ownerCoverage;expected_chunks_per_owner=$offsets.Count}
        if($ownerReady.Count -eq $playerCount){$ready+=($ownerReady|Measure-Object -Maximum).Maximum}
    }
    return [pscustomobject]@{complete=($ready.Count -eq $repeatCount);samples=[double[]]$ready;coverage=$coverage}
}

function MeasurementConditions([object]$Record) {
    $directory=Split-Path -Parent $Record.path
    $config=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/scenario-config.json') -Raw | ConvertFrom-Json
    $signature=@()
    $regionPath=Join-Path $directory 'remote-evidence/measurement-region.json'
    $region=if(Test-Path -LiteralPath $regionPath){Get-Content -LiteralPath $regionPath -Raw|ConvertFrom-Json}else{[pscustomobject]@{radius=4;shape='square';expected_chunks_per_owner=81}}
    $signature+='measurement_region='+$region.shape+':'+$region.radius+':'+$region.expected_chunks_per_owner
    foreach($key in @('dimension','players','seed','warmup_runs','measured_repeats','view_distance','timeout_ms')){
        $value=Value $config $key
        if($null -eq $value){throw "Missing scenario condition: $key"}
        $signature+="$key=$value"
    }
    $logicalProcessors=Value $config 'server_logical_processors'
    $logicalProcessors=if($null -eq $logicalProcessors){0}else{[int]$logicalProcessors}
    $signature+='server_logical_processors='+$logicalProcessors
    $jvmLimitPath=Join-Path $directory 'remote-evidence/server-jvm-cpu-limit.json'
    $jvmCount=if(Test-Path -LiteralPath $jvmLimitPath){(Get-Content -LiteralPath $jvmLimitPath -Raw|ConvertFrom-Json).requested_active_processor_count}else{0}
    $signature+='server_jvm_processors='+$jvmCount
    $signature+='quiet_remote_trace='+[bool](Value $config 'quiet_remote_trace')
    $applicationPath=Join-Path $directory 'remote-evidence/remote-application-config.json'
    if(Test-Path -LiteralPath $applicationPath){
        $application=Get-Content -LiteralPath $applicationPath -Raw|ConvertFrom-Json
        foreach($key in @('profile','ready_surface_only','base_wait_ms','maximum_wait_ms')){
            $signature+='remote_application/'+$key+'='+(Value $application $key)
        }
    }
    $flightPath=Join-Path $directory 'remote-evidence/flight-recording-config.json'
    $windowPath=Join-Path $directory 'remote-evidence/pipeline-window-config.json'
    if(Test-Path -LiteralPath $windowPath){
        $window=Get-Content -LiteralPath $windowPath -Raw|ConvertFrom-Json
        foreach($key in @('profile','owner_window','total_window','lookahead')){
            $signature+='pipeline_window/'+$key+'='+(Value $window $key)
        }
    }
    $kindPath=Join-Path $directory 'remote-evidence/remote-work-kind-config.json'
    $signature+='remote_work_kind='+$(if(Test-Path -LiteralPath $kindPath){(Get-Content -LiteralPath $kindPath -Raw|ConvertFrom-Json).selected_work_kind}else{'historical-unspecified'})
    $signature+='complete_verification='+$(if(Test-Path -LiteralPath $kindPath){$kind=Get-Content -LiteralPath $kindPath -Raw|ConvertFrom-Json;if($kind.PSObject.Properties.Name -contains 'complete_verification'){$kind.complete_verification}else{'historical-server'}}else{'historical-unspecified'})
    $signature+='terrain_decisions_allowed='+$(if(Test-Path -LiteralPath $kindPath){[bool](Value (Get-Content -LiteralPath $kindPath -Raw|ConvertFrom-Json) 'terrain_decisions_allowed')}else{$false})
    $signature+='server_flight_recording='+[bool]$(if(Test-Path -LiteralPath $flightPath){(Get-Content -LiteralPath $flightPath -Raw|ConvertFrom-Json).enabled}else{$false})
    for($owner=0;$owner -lt [int](Value $Record.result 'players');$owner++){
        $options=@{}
        foreach($line in Get-Content -LiteralPath (Join-Path $directory "clients/owner-$owner/client/options.txt")){
            $parts=$line.Split(':',2);if($parts.Count -eq 2){$options[$parts[0]]=$parts[1]}
        }
        foreach($key in @('maxFps','renderDistance','graphicsPreset','prioritizeChunkUpdates','maxAnisotropyBit','textureFiltering','cloudRange','enableVsync')){
            if(-not $options.ContainsKey($key)){throw "Missing client condition: $key"}
            $signature+="owner-$owner/$key=$($options[$key])"
        }
        $clientLog=Join-Path $directory "clients/owner-$owner/client/logs/latest.log"
        if(Select-String -LiteralPath $clientLog -SimpleMatch 'Failed to load options' -Quiet){throw 'Client options failed to load'}
    }
    $log=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/latest.log') -Raw
    $coordinates=@()
    $outcomes=@()
    foreach($sample in @(Value (Value $Record.result 'performance') 'measured')){
        $repeat=[int](Value $sample 'repeat')
        $match=[regex]::Match($log,"(?s)CAWG_SCENARIO_MEASURED_BEGIN_$repeat\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_$repeat\b")
        if(-not $match.Success){throw "Missing measured log boundaries: $repeat"}
        $keys=@([regex]::Matches($match.Groups['body'].Value,'stage\.complete stage=noise chunk=(?<chunk>-?\d+,-?\d+)\b')|ForEach-Object {$_.Groups['chunk'].Value}|Sort-Object -Unique)
        if($keys.Count -eq 0){throw "Missing measured chunk coordinates: $repeat"}
        $coordinates+=($keys -join ';')
        $body=$match.Groups['body'].Value
        $sent=@{};$completed=@{}
        foreach($job in [regex]::Matches($body,'job\.sent id=(?<id>\S+)')){$sent[$job.Groups['id'].Value]=$true}
        foreach($job in [regex]::Matches($body,'job\.complete id=(?<id>\S+) source=remote\b')){$completed[$job.Groups['id'].Value]=$true}
        $matched=@($sent.Keys|Where-Object {$completed.ContainsKey($_)}).Count
        $outcomes+=[ordered]@{repeat=$repeat;sent_jobs=$sent.Count;sent_jobs_completed_in_window=$matched;completion_rate=if($sent.Count){$matched/[double]$sent.Count}else{$null};remote_completions_from_other_windows=@($completed.Keys|Where-Object {-not $sent.ContainsKey($_)}).Count;cache_completions=[regex]::Matches($body,'job\.complete id=\S+ source=cache\b').Count}
    }
    return [pscustomobject]@{signature=($signature -join ';');coordinates=$coordinates;outcomes=$outcomes}
}

$issues=[Collections.Generic.List[object]]::new();$records=@()
$files=if($ScenarioResultPath.Count){@($ScenarioResultPath|ForEach-Object {Get-Item -LiteralPath $_ -ErrorAction Stop}|Sort-Object FullName)}else{@(Get-ChildItem -LiteralPath $root -Filter 'scenario-result.json' -File -Recurse|Sort-Object FullName)}
if($files.Count -eq 0){Issue $issues 'NO_SCENARIO_RESULTS' 'No scenario-result.json files were found.' $root}
foreach($file in $files){
    try{
        $result=Get-Content -LiteralPath $file.FullName -Raw|ConvertFrom-Json
        foreach($field in @('schema','success','cleanup_safe','loopback_only','artifact_sha256','source_manifest_before_sha256','source_manifest_after_sha256','dimension','mode','players','purpose','cache_entries','prediction','validation_cells')){if($null -eq (Value $result $field)){Issue $issues 'MISSING_FIELD' "Scenario is missing '$field'." $file.FullName}}
        if((Value $result 'schema') -ne 'worldgen-assist.scenario-result.v1'){Issue $issues 'UNSUPPORTED_SCHEMA' 'Expected worldgen-assist.scenario-result.v1.' $file.FullName}
        if(-not [bool](Value $result 'success')){Issue $issues 'SCENARIO_NOT_SUCCESSFUL' 'Scenario reported success=false.' $file.FullName}
        if(-not [bool](Value $result 'cleanup_safe') -or -not [bool](Value $result 'loopback_only')){Issue $issues 'UNSAFE_RUNTIME' 'Scenario did not prove cleanup-safe loopback execution.' $file.FullName}
        $before=[string](Value $result 'source_manifest_before_sha256');$after=[string](Value $result 'source_manifest_after_sha256')
        if($before -notmatch '^[A-Fa-f0-9]{64}$' -or $after -notmatch '^[A-Fa-f0-9]{64}$' -or $before -ne $after){Issue $issues 'SOURCE_SNAPSHOT_CHANGED' 'Source/build/script manifest hashes are missing or differ.' $file.FullName}
        if([string](Value $result 'artifact_sha256') -notmatch '^[A-Fa-f0-9]{64}$'){Issue $issues 'INVALID_ARTIFACT_HASH' 'Exact JAR SHA-256 is absent.' $file.FullName}
        if([string](Value $result 'dimension') -notin @('overworld','the_nether','the_end','fixture') -or [string](Value $result 'mode') -notin @('vanilla','assisted') -or [string](Value $result 'purpose') -notin @('correctness','performance')){Issue $issues 'INVALID_IDENTITY' 'Dimension, mode, or purpose is invalid.' $file.FullName}
        $records+=[pscustomobject]@{path=$file.FullName;result=$result;key=(Key $result)}
    }catch{Issue $issues 'INVALID_SCENARIO_RESULT' $_.Exception.Message $file.FullName}
}
$byKey=@{};foreach($record in $records){if(-not $byKey.ContainsKey($record.key)){$byKey[$record.key]=@{}};$mode=if($explicitPair){if($record.path -eq $BaselineResultPath){'vanilla'}else{'assisted'}}else{[string](Value $record.result 'mode')};if($byKey[$record.key].ContainsKey($mode)){Issue $issues 'DUPLICATE_SCENARIO' "Duplicate $mode evidence for $($record.key)." $record.path}else{$byKey[$record.key][$mode]=$record}}
$correctness=@();$performance=@()
foreach($identity in @($byKey.Keys|Sort-Object)){
    $pair=$byKey[$identity];if(-not $pair.ContainsKey('vanilla') -or -not $pair.ContainsKey('assisted')){Issue $issues 'MISSING_BASELINE_PAIR' "No complete vanilla/assisted pair for $identity." $root;continue}
    $vanilla=$pair.vanilla;$assisted=$pair.assisted;$purpose=[string](Value $vanilla.result 'purpose')
    if($explicitPair -and $purpose -ne 'performance'){Issue $issues 'INVALID_EXPLICIT_PAIR' 'Explicit roles are available only for performance comparisons.' $root;continue}
    if($purpose -ne [string](Value $assisted.result 'purpose')){Issue $issues 'PURPOSE_MISMATCH' "Purposes differ for $identity." $root;continue}
    if([string](Value $vanilla.result 'artifact_sha256') -ne [string](Value $assisted.result 'artifact_sha256')){Issue $issues 'ARTIFACT_MISMATCH' "JAR hashes differ for $identity." $root}
    if([string](Value $vanilla.result 'source_manifest_after_sha256') -ne [string](Value $assisted.result 'source_manifest_after_sha256')){Issue $issues 'SOURCE_MANIFEST_MISMATCH' "Source manifests differ for $identity." $root}
    if($purpose -eq 'correctness'){
        $left=DigestMap $vanilla.result $issues $vanilla.path;$right=DigestMap $assisted.result $issues $assisted.path;$required=@(AppliedChunks $assisted.result $issues $assisted.path)
        if($required.Count -eq 0){Issue $issues 'NO_ASSISTED_APPLICATION' "Assisted case $identity installed no required result." $assisted.path}
        if([int](Value $assisted.result 'players') -gt 1 -and -not [bool](Value (Value $assisted.result 'correctness') 'concurrent_owners')){Issue $issues 'NO_CONCURRENT_OWNERS' "The two-player assisted case did not prove overlapping owner work for $identity." $assisted.path}
        $requiredRows=@($required|ForEach-Object{[ordered]@{key=$_;vanilla=$left[$_];assisted=$right[$_];equal=($left.ContainsKey($_) -and $right.ContainsKey($_) -and $left[$_]-eq $right[$_])}})
        $shared=@($left.Keys|Where-Object{$right.ContainsKey($_)});if($shared.Count -eq 0){Issue $issues 'NO_SHARED_DIGESTS' "No shared digest exists for $identity." $root};$mismatch=@($shared|Where-Object{$left[$_] -ne $right[$_]})
        if(@($requiredRows|Where-Object{-not $_.equal}).Count -gt 0){Issue $issues 'REQUIRED_DIGEST_MISMATCH' "Required installed chunks differ for $identity." $root};if($mismatch.Count -gt 0){Issue $issues 'SHARED_DIGEST_MISMATCH' "Shared NOISE digests differ for $identity." $root}
        $correctness+=[ordered]@{identity=$identity;status=if($required.Count -gt 0 -and $shared.Count -gt 0 -and $mismatch.Count -eq 0 -and @($requiredRows|Where-Object{-not $_.equal}).Count -eq 0){'PASS'}else{'FAILED'};required_applied_chunks=$requiredRows;shared_digest_count=$shared.Count;shared_mismatch_count=$mismatch.Count}
    }else{
        $left=Samples $vanilla.result $issues $vanilla.path;$right=Samples $assisted.result $issues $assisted.path;$summary=[ordered]@{identity=$identity;status='COMPLETE';warmup_excluded=$true;metrics=@{};remote_assistance=@{};reliability=@{}}
        $implementationSettings=[ordered]@{}
        foreach($role in @('vanilla','assisted')){
            $member=$pair[$role]
            $backendPath=Join-Path (Split-Path -Parent $member.path) 'remote-evidence/noise-backend-config.json'
            $implementationSettings[$role]=if(Test-Path -LiteralPath $backendPath){Get-Content -LiteralPath $backendPath -Raw|ConvertFrom-Json}else{[ordered]@{noise_backend='vanilla';historical_default=$true}}
        }
        $summary.implementation_settings=$implementationSettings
        $summary.comparison_scope=if($implementationSettings.vanilla.noise_backend -ne $implementationSettings.assisted.noise_backend){'Combined terrain backend and remote assistance; not isolated client assistance'}else{'Remote assistance with matching terrain backend'}
        if($explicitPair){
            $summary.comparison_scope='Explicit baseline/candidate conditions; inspect actual condition roles. Legacy metric keys vanilla/assisted mean baseline/candidate only.'
            $summary.condition_roles=[ordered]@{baseline=[ordered]@{path=$vanilla.path;mode=$vanilla.result.mode;feature_backend=$vanilla.result.feature_backend};candidate=[ordered]@{path=$assisted.path;mode=$assisted.result.mode;feature_backend=$assisted.result.feature_backend};raw_results_modified=$false}
        }
        $conditionsMatch=$false
        try{
            $leftConditions=MeasurementConditions $vanilla;$rightConditions=MeasurementConditions $assisted
            $summary.remote_job_outcomes=[ordered]@{definition='Jobs sent and completed within the same measured window; excludes warm-up and lists cross-window/cache completions separately';vanilla=$leftConditions.outcomes;assisted=$rightConditions.outcomes}
            $conditionsMatch=$leftConditions.signature -ceq $rightConditions.signature -and $leftConditions.coordinates.Count -eq $rightConditions.coordinates.Count
            if($conditionsMatch){for($i=0;$i -lt $leftConditions.coordinates.Count;$i++){if($leftConditions.coordinates[$i] -cne $rightConditions.coordinates[$i]){$conditionsMatch=$false}}}
            if(-not $conditionsMatch){Issue $issues 'MEASUREMENT_CONDITIONS_MISMATCH' "Runtime/client settings or measured coordinates differ for $identity." $root}
        }catch{Issue $issues 'MISSING_MEASUREMENT_CONDITIONS' $_.Exception.Message $root}
        $summary.conditions_and_coordinates_match=$conditionsMatch
        $summary.evidence=@{vanilla=$vanilla.path;assisted=$assisted.path}
        $sameWork = $null -ne $left -and $null -ne $right -and @($left).Count -eq @($right).Count
        if($sameWork){for($i=0;$i -lt @($left).Count;$i++){
            if((Value $left[$i] 'completed_tasks') -ne (Value $right[$i] 'completed_tasks') -or (Value $left[$i] 'repeat') -ne ($i+1) -or (Value $right[$i] 'repeat') -ne ($i+1)){$sameWork=$false}
        }}
        if(-not $sameWork){Issue $issues 'WORKLOAD_MISMATCH' "Completed task counts differ between paired repeats for $identity; ratios cannot establish performance." $root;$summary.status='INCOMPLETE'}
        $summary.equal_completed_work=$sameWork
        $sameWork=$sameWork -and $conditionsMatch -and (Value $vanilla.result 'artifact_sha256') -eq (Value $assisted.result 'artifact_sha256') -and (Value $vanilla.result 'source_manifest_after_sha256') -eq (Value $assisted.result 'source_manifest_after_sha256')
        if(-not $sameWork){$summary.status='INCOMPLETE'}
        $comparedMetrics=@('server_cpu_ms','tick_mean_ms','tick_p95_ms','throughput_tasks_per_second')
        $summary.paired_repeat_metrics=[ordered]@{}
        if(@($left|Where-Object{$null -eq (Value $_ 'server_region_ready_ms')}).Count -eq 0 -and @($right|Where-Object{$null -eq (Value $_ 'server_region_ready_ms')}).Count -eq 0){$comparedMetrics+='server_region_ready_ms'}
        if(@($left|Where-Object{$null -eq (Value $_ 'server_full_region_ready_ms')}).Count -eq 0 -and @($right|Where-Object{$null -eq (Value $_ 'server_full_region_ready_ms')}).Count -eq 0){$comparedMetrics+='server_full_region_ready_ms'}
        foreach($metric in $comparedMetrics){
            $leftValues=[double[]]@($left|ForEach-Object{$value=Number (Value $_ $metric);if($null -ne $value){$value}});$rightValues=[double[]]@($right|ForEach-Object{$value=Number (Value $_ $metric);if($null -ne $value){$value}})
            if($null -eq $left -or $null -eq $right -or $leftValues.Count -ne $left.Count -or $rightValues.Count -ne $right.Count){Issue $issues 'MISSING_PERFORMANCE_METRIC' "Metric '$metric' is missing/non-finite for $identity." $root;$summary.status='INCOMPLETE';$summary.metrics[$metric]=$null}else{$leftStats=Stats $leftValues;$rightStats=Stats $rightValues;$summary.metrics[$metric]=[ordered]@{vanilla=$leftStats;assisted=$rightStats;median_delta=if($sameWork){$rightStats.median-$leftStats.median}else{$null};median_ratio=if(-not $sameWork -or $leftStats.median -eq 0){$null}else{$rightStats.median/$leftStats.median}}}
            if($sameWork -and $null -ne $summary.metrics[$metric]){
                $summary.paired_repeat_metrics[$metric]=PairedStats $leftValues $rightValues ($metric -eq 'throughput_tasks_per_second')
            }
        }
        $leftReceipt=ClientReceiptSamples $vanilla;$rightReceipt=ClientReceiptSamples $assisted
        $summary.client_receipt_coverage=[ordered]@{vanilla=$leftReceipt.coverage;assisted=$rightReceipt.coverage}
        if($leftReceipt.complete -and $rightReceipt.complete){
            $leftStats=Stats $leftReceipt.samples;$rightStats=Stats $rightReceipt.samples
            $summary.metrics.client_region_receipt_ms=[ordered]@{vanilla=$leftStats;assisted=$rightStats;median_delta=if($sameWork){$rightStats.median-$leftStats.median}else{$null};median_ratio=if($sameWork -and $leftStats.median -gt 0){$rightStats.median/$leftStats.median}else{$null}}
            if($sameWork){$summary.paired_repeat_metrics.client_region_receipt_ms=PairedStats $leftReceipt.samples $rightReceipt.samples}
        }else{
            Issue $issues 'INCOMPLETE_CLIENT_RECEIPT' "Client target-region receipt coverage is incomplete for $identity." $root
            $summary.status='INCOMPLETE'
        }
        if($assisted.result.mode -eq 'assisted'){
        foreach($metric in @('client_compute_mean_ms','client_encode_mean_ms','rtt_mean_ms','server_decode_mean_ms','encoded_bytes_mean','apply_mean_ms','remote_total_mean_ms')){
            $values=[double[]]@($right|ForEach-Object{$value=Number (Value $_ $metric);if($null -ne $value){$value}})
            if($null -eq $right -or $values.Count -ne $right.Count){Issue $issues 'MISSING_REMOTE_METRIC' "Assisted metric '$metric' is missing/non-finite for $identity." $assisted.path;$summary.status='INCOMPLETE';$summary.remote_assistance[$metric]=$null}else{$summary.remote_assistance[$metric]=Stats $values}
        }
        if([int](Value $assisted.result 'validation_cells') -gt 0){
            $values=[double[]]@($right|ForEach-Object{$value=Number (Value $_ 'validation_mean_ms');if($null -ne $value){$value}})
            if($null -eq $right -or $values.Count -ne $right.Count){Issue $issues 'MISSING_REMOTE_METRIC' "Assisted validation timing is missing for $identity." $assisted.path;$summary.status='INCOMPLETE';$summary.remote_assistance.validation_mean_ms=$null}else{$summary.remote_assistance.validation_mean_ms=Stats $values}
        }
        }
        foreach($metric in @('attempted_tasks','completed_tasks','timeouts','fallbacks','failed_tasks')){
            $leftValues=@($left|ForEach-Object{Number (Value $_ $metric)});$rightValues=@($right|ForEach-Object{Number (Value $_ $metric)})
            if($null -eq $left -or $null -eq $right -or @($leftValues|Where-Object{$null -eq $_ -or $_ -lt 0}).Count -gt 0 -or @($rightValues|Where-Object{$null -eq $_ -or $_ -lt 0}).Count -gt 0){Issue $issues 'MISSING_RELIABILITY_METRIC' "Metric '$metric' is missing/invalid for $identity." $root;$summary.status='INCOMPLETE';continue};$summary.reliability[$metric]=[ordered]@{vanilla=[double]($leftValues|Measure-Object -Sum).Sum;assisted=[double]($rightValues|Measure-Object -Sum).Sum}
        }
        if($summary.reliability.Contains('attempted_tasks') -and $summary.reliability.attempted_tasks.vanilla -gt 0 -and $summary.reliability.attempted_tasks.assisted -gt 0){foreach($metric in @('timeouts','fallbacks','failed_tasks')){if($summary.reliability.Contains($metric)){$summary.reliability[($metric+'_rate')]=[ordered]@{vanilla=$summary.reliability[$metric].vanilla/$summary.reliability.attempted_tasks.vanilla;assisted=$summary.reliability[$metric].assisted/$summary.reliability.attempted_tasks.assisted}}}}else{Issue $issues 'ZERO_ATTEMPTED_TASKS' "No valid task denominator for $identity." $root;$summary.status='INCOMPLETE'}
        $summary.scenario_client_load=[ordered]@{window='Whole client process through server completion; includes join/warm-up, separate from measured repeats';vanilla=@(Value $vanilla.result 'scenario_client_load');assisted=@(Value $assisted.result 'scenario_client_load')}
        foreach($member in @($vanilla,$assisted)){
            $loads=@(Value $member.result 'scenario_client_load')
            if($loads.Count -ne [int](Value $member.result 'players')){Issue $issues 'MISSING_CLIENT_LOAD' "Client load owner count differs for $identity." $member.path;$summary.status='INCOMPLETE'}
            foreach($load in $loads){foreach($metric in @('cpu_ms','wall_ms','average_cpu_cores','peak_working_set_bytes')){
                $n=Number (Value $load $metric)
                if($null -eq $n -or $n -lt 0 -or ($metric -eq 'wall_ms' -and $n -eq 0)){Issue $issues 'INVALID_CLIENT_LOAD' "Client load '$metric' is absent/invalid for $identity." $member.path;$summary.status='INCOMPLETE'}
            }}
        }
        $performance+=$summary
    }
}
$plan=Join-Path $root 'matrix-plan.json';if(Test-Path -LiteralPath $plan){try{foreach($expected in @((Get-Content -LiteralPath $plan -Raw|ConvertFrom-Json).runtime_and_performance_cases)){$key=Key $expected;$mode=[string](Value $expected 'mode');$found=if($explicitPair){@($records|Where-Object {$_.key -eq $key -and $_.result.mode -eq $mode -and $_.result.feature_backend -eq $expected.feature_backend}).Count -gt 0}else{$byKey.ContainsKey($key) -and $byKey[$key].ContainsKey($mode)};if(-not $found){Issue $issues 'EXPECTED_SCENARIO_MISSING' "Planned scenario '$($expected.id)' has no result." $root}}}catch{Issue $issues 'INVALID_MATRIX_PLAN' $_.Exception.Message $plan}}else{Issue $issues 'MISSING_MATRIX_PLAN' 'Expected coverage cannot be determined without matrix-plan.json.' $plan}
$fatal=@($issues|Where-Object{$_.code -in @('REQUIRED_DIGEST_MISMATCH','SHARED_DIGEST_MISMATCH','UNSAFE_RUNTIME','SCENARIO_NOT_SUCCESSFUL')});$status=if($fatal.Count -gt 0){'FAILED'}elseif($issues.Count -gt 0){'INCOMPLETE'}else{'COMPLETE'}
$report=[ordered]@{schema='worldgen-assist.scenario-matrix-analysis.v1';run_root=$root;created_at=(Get-Date).ToString('o');status=$status;performance_claim=if($status -eq 'COMPLETE' -and $performance.Count -gt 0 -and @($performance|Where-Object status -eq 'INCOMPLETE').Count -eq 0){'DESCRIPTIVE_ONLY'}else{'NOT_EVALUATED_INCOMPLETE_EVIDENCE'};correctness_pairs=$correctness;performance_pairs=$performance;issues=$issues.ToArray()}
$report|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $output 'scenario-matrix-analysis.json') -Encoding utf8
$rows=@($performance|ForEach-Object{$pair=$_;foreach($metric in $pair.metrics.Keys){$value=$pair.metrics[$metric];[pscustomobject][ordered]@{identity=$pair.identity;status=$pair.status;metric=$metric;vanilla_median=if($null -eq $value){$null}else{$value.vanilla.median};assisted_median=if($null -eq $value){$null}else{$value.assisted.median};median_delta=if($null -eq $value){$null}else{$value.median_delta};vanilla_p95=if($null -eq $value){$null}else{$value.vanilla.p95};assisted_p95=if($null -eq $value){$null}else{$value.assisted.p95}}}});$rows|Export-Csv -LiteralPath (Join-Path $output 'performance-pairs.csv') -NoTypeInformation -Encoding utf8
$markdown=@('# Scenario matrix analysis','',"- Status: $status","- Performance: $($report.performance_claim)","- Correctness pairs: $($correctness.Count)","- Performance pairs: $($performance.Count)","- Evidence issues: $($issues.Count)",'','## Evidence issues','');if($issues.Count -eq 0){$markdown+='None.'}else{foreach($issue in $issues){$markdown+="- $($issue.code): $($issue.message) ($($issue.path))"}};$markdown|Set-Content -LiteralPath (Join-Path $output 'scenario-matrix-analysis.md') -Encoding utf8
Write-Output "SCENARIO_MATRIX_ANALYSIS_COMPLETE status=$status report=$(Join-Path $output 'scenario-matrix-analysis.json') issues=$($issues.Count)"
if($status -ne 'COMPLETE'){exit 1}
