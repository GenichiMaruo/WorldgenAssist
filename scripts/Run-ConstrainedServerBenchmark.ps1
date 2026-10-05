[CmdletBinding()]
param([switch]$Execute, [switch]$TwoLogicalOnly, [switch]$SingleLogicalOnly,[switch]$PhysicalServer,
    [ValidateRange(2,32)][int]$ViewDistance=10, [switch]$MeasureFullView,
    [ValidateSet(0,1,2,4)][int]$ServerJvmProcessors=0,[switch]$QuietRemoteTrace,[switch]$ServerFlightRecording,
    [ValidateSet('vanilla','cooperative')][string]$AssistedNoiseBackend='vanilla',
    [ValidateSet('vanilla','cooperative')][string]$VanillaNoiseBackend='vanilla',
    [ValidateSet('standard','wide','deep')][string]$WindowProfile='standard',
    [ValidateSet('vanilla-first','assisted-first')][string]$ConditionOrder='vanilla-first',
    [ValidateSet('ready','overlap')][string]$RemoteApplicationProfile='ready',
    [ValidateRange(0,64)][int]$PrefetchLookahead=0,
    [ValidateSet('grid','surface','density','block','decisions','complete')][string]$RemoteWorkKind='grid',
    [ValidateSet('server','peer')][string]$CompleteVerification='server')

# Six isolated two-owner scenarios in one bounded batch. No production build or
# unrelated JUnit suite is repeated for this harness-only experiment.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifacts = Join-Path $workspace 'test-artifacts'
$runner = Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'
$comparator = Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'
$pwsh = (Get-Command pwsh.exe -ErrorAction Stop).Source
$profiles = @(
    [ordered]@{name='unrestricted';logical_processors=0;modes=@('vanilla','assisted')},
    [ordered]@{name='four-logical';logical_processors=4;modes=@('assisted','vanilla')},
    [ordered]@{name='two-logical';logical_processors=2;modes=@('vanilla','assisted')}
)
if ($TwoLogicalOnly -and $SingleLogicalOnly) { throw 'Choose one CPU tier' }
if ($TwoLogicalOnly) { $profiles = @($profiles | Where-Object { $_.logical_processors -eq 2 }) }
if ($SingleLogicalOnly) { $profiles = @([ordered]@{name='one-logical';logical_processors=1;modes=@('vanilla','assisted')}) }
if($PhysicalServer){
    if($TwoLogicalOnly -or $SingleLogicalOnly -or $ServerJvmProcessors -ne 0){throw 'PhysicalServer compares the naturally slower PC without artificial CPU limits'}
    $profiles=@([ordered]@{name='physical-unrestricted';logical_processors=0;modes=@('vanilla','assisted')})
}
if($ConditionOrder -eq 'assisted-first') {
    if(-not $PhysicalServer){throw 'Explicit reversed condition order is supported only for the physical pair'}
    $profiles[0].modes=@('assisted','vanilla')
}
$remoteHost=if($PhysicalServer){'gen1c@100.103.102.109'}else{'gen1c@100.117.255.71'}
$remoteRoot=if($PhysicalServer){'E:/WorldgenAssist/port26.3'}else{'C:/Users/gen1c/AppData/Local/Temp/WorldgenAssist-20260909/port26.3'}
if (-not $Execute) {
    [ordered]@{schema='worldgen-assist.constrained-server-plan.v1';players=2;dimension='overworld';remote_host=$remoteHost;remote_root=$remoteRoot;physical_server=[bool]$PhysicalServer;view_distance=$ViewDistance;server_jvm_processors=$ServerJvmProcessors;measure_full_view=[bool]$MeasureFullView;warmup_runs=1;measured_repeats=3;window_profile=$WindowProfile;condition_order=$ConditionOrder;owner_window=$(switch($WindowProfile){'deep'{32} 'wide'{16} default{4}});total_window=$(switch($WindowProfile){'deep'{64} 'wide'{32} default{8}});profiles=$profiles;primary_proxy='server target-region NOISE/FULL completion and separately measured client receipt'} | ConvertTo-Json -Depth 6
    exit 0
}

New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$runRoot = Join-Path $artifacts ('csb-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$lockPath = Join-Path $artifacts 'validation-matrix.lock'
$lock = $null
$results = @()
$issues = @()
$runtimeSafe = $true
$jarHash = $null
. (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1')
$measurementRadius=if($MeasureFullView){$ViewDistance}else{4}
$measurementShape=if($MeasureFullView){'view'}else{'square'}
$expectedChunks=@(Get-WorldgenMeasurementOffsets $measurementRadius $measurementShape).Count
function Get-MeasuredCoordinateSignature([string]$CaseRoot) {
    $log = Get-Content -LiteralPath (Join-Path $CaseRoot 'remote-evidence/latest.log') -Raw
    $repeats = @()
    for ($repeat=1; $repeat -le 3; $repeat++) {
        $match = [regex]::Match($log,"(?s)CAWG_SCENARIO_MEASURED_BEGIN_$repeat\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_$repeat\b")
        if (-not $match.Success) { throw "Missing measured repeat $repeat in $CaseRoot" }
        $coordinates = @([regex]::Matches($match.Groups['body'].Value,'stage\.complete stage=noise chunk=(?<chunk>-?\d+,-?\d+)\b') | ForEach-Object {$_.Groups['chunk'].Value} | Sort-Object -Unique)
        if ($coordinates.Count -eq 0) { throw "No NOISE coordinates in $CaseRoot repeat $repeat" }
        $repeats += ($coordinates -join ';')
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($repeats -join "`n"))
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
}
function Invoke-Quiet([string[]]$Arguments,[string]$OutputPath,[string]$ErrorPath) {
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$pwsh
    foreach($argument in $Arguments){[void]$start.ArgumentList.Add($argument)}
    $start.WorkingDirectory=$workspace
    $start.UseShellExecute=$false
    $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true
    $start.RedirectStandardError=$true
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start
    try {
        if(-not $process.Start()){throw 'Could not start PowerShell child'}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(4200000)){$process.Kill($true);$process.WaitForExit(30000)|Out-Null;throw 'Child exceeded 70-minute limit'}
        $stdout.GetAwaiter().GetResult() | Set-Content -LiteralPath $OutputPath
        $stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath $ErrorPath
        return $process.ExitCode
    } finally {$process.Dispose()}
}
try {
    New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
    try { $lock = [IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
    catch { throw "Another runtime batch owns $lockPath" }
    $lock.SetLength(0)
    $bytes = [Text.Encoding]::UTF8.GetBytes("pid=$PID`nstarted=$((Get-Date).ToString('o'))`nrun_root=$runRoot`n")
    $lock.Write($bytes,0,$bytes.Length);$lock.Flush()
    $artifact = & (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Workspace $workspace
    if ($artifact.Minecraft -ne '26.3' -or -not(Test-Path -LiteralPath $artifact.Path -PathType Leaf)) { throw 'Exact 26.3 Fabric JAR is missing' }
    $jarHash = (Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash
    foreach ($profile in $profiles) {
        $tierCode=if($profile.logical_processors -eq 0){'u'}else{'c'+$profile.logical_processors}
        $tierRoot = Join-Path $runRoot $tierCode
        New-Item -ItemType Directory -Force -Path $tierRoot | Out-Null
        $planCases = @($profile.modes | ForEach-Object {
            [ordered]@{id="performance-overworld-$_-p2-cache_prediction_validation";purpose='performance';dimension='overworld';mode=$_;players=2;cache_entries=128;prediction=$true;validation_cells=8;warmup_runs=1;measured_repeats=3;server_logical_processors=$profile.logical_processors}
        })
        [ordered]@{schema='worldgen-assist.validation-matrix-plan.v1';scope='two-owner constrained-server performance pair';generated_at=(Get-Date).ToString('o');runtime_and_performance_cases=$planCases} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $tierRoot 'matrix-plan.json') -Encoding utf8
        foreach ($mode in $profile.modes) {
            $caseRoot = Join-Path $tierRoot $mode
            if (-not $runtimeSafe) { $results += [ordered]@{tier=$profile.name;mode=$mode;status='SKIPPED';reason='Prior scenario cleanup safety was not proved';path=$caseRoot};continue }
            New-Item -ItemType Directory -Force -Path $caseRoot | Out-Null
            $arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',$runner,'-Dimension','overworld','-Mode',$mode,'-Players','2','-Purpose','performance','-CacheEntries','128','-Prediction','true','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors',[string]$profile.logical_processors,'-OutputRoot',$caseRoot)
            $arguments += @('-ViewDistance',[string]$ViewDistance)
            $arguments += @('-RemoteHost',$remoteHost,'-RemoteRoot',$remoteRoot)
            $arguments += @('-ServerJvmProcessors',[string]$ServerJvmProcessors)
            if($MeasureFullView){$arguments += '-MeasureFullView'}
            if($QuietRemoteTrace){$arguments += '-QuietRemoteTrace'}
            if($ServerFlightRecording){$arguments += '-ServerFlightRecording'}
            $requestedBackend=if($mode -eq 'assisted'){$AssistedNoiseBackend}else{$VanillaNoiseBackend}
            $arguments += @('-NoiseBackend',$requestedBackend)
            $arguments += @('-WindowProfile',$WindowProfile)
            $arguments += @('-RemoteApplicationProfile',$RemoteApplicationProfile)
            $arguments += @('-PrefetchLookahead',[string]$PrefetchLookahead)
            $arguments += @('-RemoteWorkKind',$RemoteWorkKind)
            $arguments += @('-CompleteVerification',$CompleteVerification)
            $started = Get-Date
            $exitCode = Invoke-Quiet $arguments (Join-Path $caseRoot 'batch-run.log') (Join-Path $caseRoot 'batch-error.log')
            $resultPath = Join-Path $caseRoot 'scenario-result.json'
            $status = 'FAILED'; $reason = "runner exit=$exitCode"
            if (Test-Path -LiteralPath $resultPath -PathType Leaf) {
                try {
                    $scenario = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
                    $runtimeSafe = [bool]$scenario.cleanup_safe
					$generationLog=Get-Content -LiteralPath (Join-Path $caseRoot 'remote-evidence/latest.log') -Raw
					if($generationLog -match 'worker\.quarantined|job\.peer_terrain_difference|job\.full_terrain_apply_rejected|Independent whole-terrain audit mismatch|Independent peer whole-terrain mismatch') { throw 'Generation verification failed; clean completion cannot establish valid performance' }
                    $configuration=Get-Content -LiteralPath (Join-Path $caseRoot 'remote-evidence/scenario-config.json') -Raw|ConvertFrom-Json
                    $verification=Get-Content -LiteralPath (Join-Path $caseRoot 'remote-evidence/remote-work-kind-config.json') -Raw|ConvertFrom-Json
                    if($verification.complete_verification -ne $CompleteVerification -or $scenario.complete_verification -ne $CompleteVerification){throw 'Complete verification differs from requested mode'}
                    $jvmCpu=Get-Content -LiteralPath (Join-Path $caseRoot 'remote-evidence/server-jvm-cpu-limit.json') -Raw|ConvertFrom-Json
                    if($jvmCpu.requested_active_processor_count -ne $ServerJvmProcessors){throw 'Server JVM processor limit differs from requested limit'}
                    if($ServerJvmProcessors -gt 0 -and $jvmCpu.observed_available_processors -ne $ServerJvmProcessors){throw 'Actual JVM processor count differs from requested limit'}
                    if($ServerJvmProcessors -gt 0 -and $jvmCpu.arguments -notmatch ('-XX:ActiveProcessorCount='+$ServerJvmProcessors+'\b')){throw 'JVM processor limit argument is missing'}
                    if($configuration.view_distance -ne $ViewDistance){throw 'Server view distance differs from requested distance'}
                    if([bool]$configuration.quiet_remote_trace -ne [bool]$QuietRemoteTrace){throw 'Remote tracing differs from requested mode'}
                    $backend=Get-Content -LiteralPath (Join-Path $caseRoot 'remote-evidence/noise-backend-config.json') -Raw|ConvertFrom-Json
                    if($backend.noise_backend -ne $requestedBackend){throw 'Terrain backend differs from requested candidate'}
                    $window=Get-Content -LiteralPath (Join-Path $caseRoot 'remote-evidence/pipeline-window-config.json') -Raw|ConvertFrom-Json
                    $application=Get-Content -LiteralPath (Join-Path $caseRoot 'remote-evidence/remote-application-config.json') -Raw|ConvertFrom-Json
                    if($application.profile -ne $RemoteApplicationProfile -or [bool]$application.ready_surface_only -ne ($RemoteApplicationProfile -eq 'ready') -or $application.base_wait_ms -ne 100 -or $application.maximum_wait_ms -ne 200){throw 'Remote application profile differs from requested candidate'}
                    $expectedOwner=switch($WindowProfile){'deep'{32} 'wide'{16} default{4}}
                    $expectedTotal=switch($WindowProfile){'deep'{64} 'wide'{32} default{8}}
                    if($window.profile -ne $WindowProfile -or $window.owner_window -ne $expectedOwner -or $window.total_window -ne $expectedTotal -or $window.lookahead -ne $PrefetchLookahead){throw 'Remote window profile differs from requested candidate'}
                    foreach($owner in 0..1){
                        $savedOption=@(Select-String -LiteralPath (Join-Path $caseRoot "clients/owner-$owner/client/options.txt") -Pattern '^renderDistance:(\d+)$')
                        if($savedOption.Count -ne 1 -or [int]$savedOption.Matches[0].Groups[1].Value -ne $ViewDistance){throw 'Actual saved client render distance differs from request'}
                    }
                    $cpuPath = Join-Path $caseRoot 'remote-evidence/server-cpu-limit.json'
                    if (Test-Path -LiteralPath $cpuPath -PathType Leaf) {
                        $cpu = Get-Content -LiteralPath $cpuPath -Raw | ConvertFrom-Json
                        if ([int]$cpu.requested_logical_processors -ne [int]$profile.logical_processors) { $reason = 'Recorded CPU limit differs from requested limit' }
                        elseif ($exitCode -eq 0 -and $scenario.success -and $scenario.cleanup_safe -and $scenario.artifact_sha256 -eq $jarHash) { $status='PASSED';$reason=$null }
                    } else { $reason='CPU-limit application evidence is missing' }
                } catch { $runtimeSafe=$false;$reason="Invalid scenario evidence: $($_.Exception.Message)" }
            } else { $runtimeSafe=$false;$reason='Scenario result and cleanup proof are missing' }
            $results += [ordered]@{tier=$profile.name;mode=$mode;status=$status;reason=$reason;duration_seconds=[Math]::Round(((Get-Date)-$started).TotalSeconds,3);path=$caseRoot}
        }
        $passed = @($results | Where-Object { $_.tier -eq $profile.name -and $_.status -eq 'PASSED' }).Count
        if ($passed -eq 2) {
            $analysisExit = Invoke-Quiet @('-NoProfile','-ExecutionPolicy','Bypass','-File',$comparator,'-RunRoot',$tierRoot) (Join-Path $tierRoot 'analysis-run.log') (Join-Path $tierRoot 'analysis-error.log')
            $analysisPath = Join-Path $tierRoot 'analysis/scenario-matrix-analysis.json'
            if ($analysisExit -ne 0 -or -not(Test-Path -LiteralPath $analysisPath -PathType Leaf)) { $issues += "$($profile.name): paired comparison failed; see analysis-error.log" }
        } else { $issues += "$($profile.name): fewer than two valid scenarios" }
    }
    $baseSignature = $null
    $baseCoordinates = $null
    $baseManifest = $null
    foreach ($profile in $profiles) {
        $tierCode=if($profile.logical_processors -eq 0){'u'}else{'c'+$profile.logical_processors}
        $tierRoot=Join-Path $runRoot $tierCode
        $analysisPath = Join-Path $tierRoot 'analysis/scenario-matrix-analysis.json'
        if (-not(Test-Path -LiteralPath $analysisPath -PathType Leaf)) { continue }
        $analysis = Get-Content -LiteralPath $analysisPath -Raw | ConvertFrom-Json
        if ($analysis.status -ne 'COMPLETE' -or @($analysis.performance_pairs).Count -ne 1) { $issues += "$($profile.name): analysis status=$($analysis.status)"; continue }
        $pair = $analysis.performance_pairs[0]
        $vanilla = Get-Content -LiteralPath (Join-Path $tierRoot 'vanilla/scenario-result.json') -Raw | ConvertFrom-Json
        $assisted = Get-Content -LiteralPath (Join-Path $tierRoot 'assisted/scenario-result.json') -Raw | ConvertFrom-Json
        if ($null -eq $baseManifest) { $baseManifest=$vanilla.source_manifest_after_sha256 }
        elseif ($vanilla.source_manifest_after_sha256 -cne $baseManifest -or $assisted.source_manifest_after_sha256 -cne $baseManifest) { $issues += "$($profile.name): source snapshot differs across CPU profiles" }
        $signature = @($vanilla.performance.measured | ForEach-Object { [string]$_.completed_tasks }) -join ','
        if ($null -eq $baseSignature) { $baseSignature=$signature } elseif ($signature -cne $baseSignature) { $issues += "$($profile.name): completed workload differs across CPU profiles" }
        $vanillaRoot = Join-Path $tierRoot 'vanilla'
        $coordinateSignature = Get-MeasuredCoordinateSignature $vanillaRoot
        if ($null -eq $baseCoordinates) { $baseCoordinates=$coordinateSignature } elseif ($coordinateSignature -cne $baseCoordinates) { $issues += "$($profile.name): measured NOISE coordinates differ across CPU profiles" }
        if ($null -eq $pair.metrics.server_region_ready_ms -or $null -eq $pair.metrics.client_region_receipt_ms) { $issues += "$($profile.name): server-ready or client-receipt metric is missing"; continue }
        $results += [ordered]@{tier=$profile.name;mode='pair';status='COMPLETE';server_logical_processors=$profile.logical_processors;coordinates_sha256=$coordinateSignature;region_ready_ms_vanilla=$pair.metrics.server_region_ready_ms.vanilla.median;region_ready_ms_assisted=$pair.metrics.server_region_ready_ms.assisted.median;client_receipt_ms_vanilla=$pair.metrics.client_region_receipt_ms.vanilla.median;client_receipt_ms_assisted=$pair.metrics.client_region_receipt_ms.assisted.median;throughput_vanilla=$pair.metrics.throughput_tasks_per_second.vanilla.median;throughput_assisted=$pair.metrics.throughput_tasks_per_second.assisted.median;tick_p95_vanilla=$pair.metrics.tick_p95_ms.vanilla.median;tick_p95_assisted=$pair.metrics.tick_p95_ms.assisted.median;server_cpu_ms_vanilla=$pair.metrics.server_cpu_ms.vanilla.median;server_cpu_ms_assisted=$pair.metrics.server_cpu_ms.assisted.median;analysis=$analysisPath}
    }
} catch { $issues += $_.Exception.ToString() }
finally {
    if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $runRoot -PathType Container) {
        $complete=@($results | Where-Object {$_.mode -eq 'pair' -and $_.status -eq 'COMPLETE'}).Count
        $summary=[ordered]@{schema='worldgen-assist.constrained-server-benchmark.v1';status=if($issues.Count -eq 0 -and $complete -eq $profiles.Count){'COMPLETE'}else{'INCOMPLETE'};artifact_sha256=$jarHash;view_distance=$ViewDistance;server_jvm_processors=$ServerJvmProcessors;measurement_shape=$measurementShape;measurement_radius=$measurementRadius;expected_chunks_per_owner=$expectedChunks;client_visible_wait_measured=($issues.Count -eq 0 -and $complete -eq $profiles.Count);server_region_ready_definition='Elapsed from scripted relocation until both owners complete the configured NOISE target region; excludes client delivery';client_receipt_definition='Elapsed from each client receiving the repeat BEGIN message until both clients load every chunk in the configured target region; excludes rendering';results=$results;issues=$issues;run_root=$runRoot}
        $summary.window_profile=$WindowProfile
        $summary.condition_order=if($PhysicalServer){$ConditionOrder}else{'tier-specific'}
        $summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $runRoot 'summary.json') -Encoding utf8
        Write-Output "CONSTRAINED_SERVER_BENCHMARK status=$($summary.status) summary=$(Join-Path $runRoot 'summary.json')"
        if ($summary.status -ne 'COMPLETE') { exit 1 }
    }
}
