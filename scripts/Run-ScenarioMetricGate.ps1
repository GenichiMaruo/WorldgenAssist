[CmdletBinding()]
param([switch]$Execute,[Parameter(Mandatory)][string[]]$BenchmarkRoot)
# Offline only. Load just the collector function AST, never the server script.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base=Join-Path $workspace 'test-artifacts'
if(-not $Execute){
    [ordered]@{scope='One affected numeric-parser control and existing measured logs only';
        benchmarks=$BenchmarkRoot;new_world_runs=0;new_builds=0}|ConvertTo-Json
    exit 0
}
$root=Join-Path $base ('scenario-metric-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$rows=[Collections.Generic.List[object]]::new()
$inputs=[Collections.Generic.List[object]]::new();$lock=$null;$controlPassed=$false
$collector=Join-Path $PSScriptRoot 'Remote-WorldgenScenarioServer.ps1'
try{
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $collectorHash=(Get-FileHash -LiteralPath $collector).Hash
    $tokens=$null;$parseErrors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($collector,[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count){throw 'Collector has syntax errors'}
    $functions=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Get-TickStatistics'},$true))
    if($functions.Count -ne 1){throw 'Exactly one collector function required'}
    . ([scriptblock]::Create($functions[0].Extent.Text))
    $safeWorkspace=$workspace.Replace('\','/')
    $oldSource=(& git -c "safe.directory=$safeWorkspace" show 'c0d8ede:scripts/Remote-WorldgenScenarioServer.ps1') -join "`n"
    if($LASTEXITCODE){throw 'Committed original collector unavailable'}
    $oldAst=[Management.Automation.Language.Parser]::ParseInput($oldSource,[ref]$tokens,[ref]$parseErrors)
    $oldFunctions=@($oldAst.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Get-TickStatistics'},$true))
    if($parseErrors.Count -or $oldFunctions.Count -ne 1){throw 'Original collector invalid'}
    . ([scriptblock]::Create(($oldFunctions[0].Extent.Text -replace '^function Get-TickStatistics','function Get-OriginalTickStatistics')))
    $QuietRemoteTrace=$true
    $durationFields=@('client_compute_mean_ms','client_encode_mean_ms','rtt_mean_ms','server_decode_mean_ms',
        'validation_mean_ms','apply_mean_ms','remote_total_mean_ms','validation_prepare_mean_ms',
        'validation_compare_mean_ms','decode_queue_mean_ms','registration_mean_ms','request_queue_mean_ms',
        'ingress_queue_mean_ms','result_claim_mean_ms')
    $fixture=[Text.StringBuilder]::new()
    foreach($number in @('9.0E-4','1.0E+2','1.25','3.0e-5','1.2Ebroken')){
        [void]$fixture.AppendLine("tick.complete elapsed_ms=$number noise_completed=1 noise_failed=0 local_fallbacks_delta=0")
        [void]$fixture.AppendLine("job.result_received id=control rtt_ms=$number client_compute_ms=$number client_encode_ms=$number server_decode_ms=$number encoded_bytes=42")
        [void]$fixture.AppendLine("job.validation_complete id=control validation_ms=$number compare_ms=$number")
        [void]$fixture.AppendLine("job.complete id=control apply_ms=$number total_ms=$number")
        [void]$fixture.AppendLine("job.validation_ready id=control prepare_ms=$number")
        [void]$fixture.AppendLine("job.result_decoded id=control decode_queue_ms=$number")
        [void]$fixture.AppendLine("job.registered id=control registration_ms=$number")
        [void]$fixture.AppendLine("job.request_dispatch id=control request_queue_ms=$number")
        [void]$fixture.AppendLine("job.result_ingress id=control ingress_queue_ms=$number claim_ms=$number")
    }
    $priorCulture=[Globalization.CultureInfo]::CurrentCulture
    try{
        [Globalization.CultureInfo]::CurrentCulture=[Globalization.CultureInfo]::GetCultureInfo('fr-FR')
        $control=Get-TickStatistics $fixture.ToString() 123.5 1000
        $expectedMean=(0.0009+100+1.25+0.00003)/4
        foreach($field in $durationFields){if($control[$field] -ne [Math]::Round($expectedMean,6)){throw "Numeric control failed: $field"}}
        if($control.completed_tasks -ne 4 -or [Math]::Abs($control.tick_mean_ms-$expectedMean) -gt 1e-10 -or
            $control.tick_p95_ms -ne 100 -or $control.encoded_bytes_mean -ne 42 -or $control.server_cpu_ms -ne 123.5){throw 'Tick/counter control failed'}
        $empty=Get-TickStatistics '' 0 0
        foreach($field in $durationFields){if($null -ne $empty[$field]){throw 'Missing measurement must remain null'}}
        $witness=Get-TickStatistics 'job.validation_complete id=actual validation_ms=9.0E-4 compare_ms=7.0E-4' 0 0
        if($witness.validation_mean_ms -ne 0.0009 -or $witness.validation_compare_mean_ms -ne 0.0007){throw 'Actual microsecond witness failed'}
    }finally{[Globalization.CultureInfo]::CurrentCulture=$priorCulture}
    $controlPassed=$true
    Write-Host 'SCENARIO_METRIC_CONTROL success=True'
    foreach($benchmark in $BenchmarkRoot){
        $resolved=[IO.Path]::GetFullPath($benchmark)
        if(-not $resolved.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Workspace evidence child required'}
        $summary=Get-Content -LiteralPath (Join-Path $resolved 'summary.json') -Raw|ConvertFrom-Json
        if($summary.status -ne 'COMPLETE' -or $summary.issues.Count -or
            $summary.artifact_sha256 -ne '953C146A3D264C4316095683B68F322A4154B2AF42CBC7A9F7C504EDD0523D64'){throw 'Completed exact dev15 evidence required'}
        foreach($mode in @('vanilla','assisted')){
            $evidence=Join-Path $resolved "u/$mode/remote-evidence"
            $log=Join-Path $evidence 'latest.log';$performance=Join-Path $evidence 'performance.json'
            $logHash=(Get-FileHash -LiteralPath $log).Hash;$performanceHash=(Get-FileHash -LiteralPath $performance).Hash
            $inputs.Add([ordered]@{log=$log;log_sha256=$logHash;original_performance=$performance;performance_sha256=$performanceHash})
            $original=Get-Content -LiteralPath $performance -Raw|ConvertFrom-Json
            if($original.measured.Count -ne 3){throw 'Exactly three original measured intervals required'}
            $repeat=0;$closed=0;$text=[Text.StringBuilder]::new()
            foreach($line in [IO.File]::ReadLines($log)){
                if($line.Contains('CAWG_SCENARIO_MEASURED_BEGIN_') -and $line -match 'CAWG_SCENARIO_MEASURED_BEGIN_(\d+)'){
                    if($repeat){throw 'Overlapping measured intervals'}
                    $repeat=[int]$Matches[1];[void]$text.Clear();continue
                }
                if(-not $repeat){continue}
                [void]$text.AppendLine($line)
                if($line.Contains('CAWG_SCENARIO_MEASURED_END_') -and $line -match 'CAWG_SCENARIO_MEASURED_END_(\d+)'){
                    if([int]$Matches[1] -ne $repeat){throw 'Mismatched interval end'}
                    $prior=@($original.measured|Where-Object repeat -eq $repeat)
                    if($prior.Count -ne 1){throw 'Missing original measured record'}
                    $corrected=Get-TickStatistics $text.ToString() $prior[0].server_cpu_ms $prior[0].server_wall_ms
                    $old=Get-OriginalTickStatistics $text.ToString() $prior[0].server_cpu_ms $prior[0].server_wall_ms
                    foreach($field in @('tick_mean_ms','tick_p95_ms','completed_tasks','failed_tasks','timeouts','fallbacks')){
                        if([Math]::Abs($corrected[$field]-$old[$field]) -gt 1e-8){throw "Unexpected stable metric change: $mode/$repeat/$field"}
                    }
                    $changes=[ordered]@{}
                    foreach($field in $durationFields){$changes[$field]=[ordered]@{published_original=$prior[0].$field;old_same_window=$old[$field];corrected=$corrected[$field]}}
                    $rows.Add([ordered]@{benchmark=$resolved;order=$summary.condition_order;mode=$mode;repeat=$repeat;
                        completed_tasks=$corrected.completed_tasks;metrics=$changes})
                    $closed++;$repeat=0;[void]$text.Clear()
                }
            }
            if($repeat -or $closed -ne 3){throw 'Expected three closed measured intervals'}
            if((Get-FileHash -LiteralPath $log).Hash -ne $logHash -or (Get-FileHash -LiteralPath $performance).Hash -ne $performanceHash){throw 'Original evidence changed'}
            Write-Host "SCENARIO_METRIC_OFFLINE order=$($summary.condition_order) mode=$mode intervals=$closed"
        }
    }
    if((Get-FileHash -LiteralPath $collector).Hash -ne $collectorHash){throw 'Collector changed during batch'}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    if($lock){$lock.Dispose()}
    $report=[ordered]@{schema='worldgen-assist.scenario-metric-gate.v1';success=($issues.Count -eq 0);numeric_control_passed=$controlPassed;
        collector=$collector;inputs=$inputs.ToArray();measured=$rows.ToArray();issues=$issues.ToArray();new_world_runs=0;new_builds=0;
        definition='Whole Java numeric tokens parsed with invariant culture; old c0d8ede and corrected collectors use the EXACT SAME closed marker window. Published originals are retained separately because their live read offsets can include adjacent events. Original frozen logs/metrics preserved. CPU/FULL/receipt measurements unchanged. Duration events are not disjoint CPU work or critical-path proof.'}
    [IO.File]::WriteAllText((Join-Path $root 'summary.json'),($report|ConvertTo-Json -Depth 9))
    Write-Output "SCENARIO_METRIC_GATE success=$($report.success) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
