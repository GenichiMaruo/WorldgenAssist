[CmdletBinding()]
param([switch]$Execute,[Parameter(Mandatory)][string]$ReusedAssistedResult,[string]$ReusedVanillaResult='')

# Recover the failed baseline and isolate client contribution. Never replace the original failed batch or
# silently reuse results produced by a different artifact/source/harness.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(-not $Execute){Write-Output 'Two view32 baselines (vanilla and cooperative, both without assistance), compared with unchanged successful cooperative/assisted evidence; no builds or JUnit.';exit 0}
$reusedPath=(Resolve-Path -LiteralPath $ReusedAssistedResult).Path
$testRoot=[IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts')).TrimEnd('\')+'\'
if(-not $reusedPath.StartsWith($testRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Reuse must refer to workspace test evidence'}
$root=Join-Path $testRoot ('cooperative32-recovery-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root | Out-Null
$lock=$null;$issues=@();$exitCode=$null;$analyses=@();$steps=@()
try{
    $lock=[IO.File]::Open((Join-Path $testRoot 'validation-matrix.lock'),'OpenOrCreate','ReadWrite','None')
    $reused=Get-Content -LiteralPath $reusedPath -Raw|ConvertFrom-Json
    $reusedRoot=Split-Path -Parent $reusedPath
    $config=Get-Content -LiteralPath (Join-Path $reusedRoot 'remote-evidence/scenario-config.json') -Raw|ConvertFrom-Json
    $backend=Get-Content -LiteralPath (Join-Path $reusedRoot 'remote-evidence/noise-backend-config.json') -Raw|ConvertFrom-Json
    $jvm=Get-Content -LiteralPath (Join-Path $reusedRoot 'remote-evidence/server-jvm-cpu-limit.json') -Raw|ConvertFrom-Json
    $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1')
    $hash=(Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash
    if(-not $reused.success -or -not $reused.cleanup_safe -or $reused.artifact_sha256 -ne $hash -or $reused.mode -ne 'assisted' -or $reused.players -ne 2 -or $reused.dimension -ne 'overworld' -or $reused.purpose -ne 'performance'){throw 'Successful exact-artifact assisted evidence is required'}
    if($reused.source_manifest_before_sha256 -ne $reused.source_manifest_after_sha256){throw 'Reused source changed during its scenario'}
    if($config.view_distance -ne 32 -or $config.server_logical_processors -ne 2 -or -not $config.quiet_remote_trace -or $config.warmup_runs -ne 1 -or $config.measured_repeats -ne 3 -or $backend.noise_backend -ne 'cooperative' -or $jvm.observed_available_processors -ne 2){throw 'Reused scenario settings differ from the intended full32 candidate'}
    foreach($baselineBackend in @('vanilla','cooperative')){
    $baselineRoot=Join-Path $root $baselineBackend
    $reuseBaseline=$baselineBackend -eq 'vanilla' -and -not [string]::IsNullOrWhiteSpace($ReusedVanillaResult)
    if($reuseBaseline){
        $baselinePath=(Resolve-Path -LiteralPath $ReusedVanillaResult).Path
        if(-not $baselinePath.StartsWith($testRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Baseline reuse must refer to workspace test evidence'}
        $baselineRoot=Split-Path -Parent $baselinePath
        $exitCode=0
    }else{
    $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-Dimension','overworld','-Mode','vanilla','-Players','2','-Purpose','performance','-CacheEntries','128','-Prediction','true','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','2','-ServerJvmProcessors','2','-ViewDistance','32','-MeasureFullView','-QuietRemoteTrace','-NoiseBackend',$baselineBackend,'-OutputRoot',$baselineRoot)
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=(Get-Command pwsh.exe).Source;$info.WorkingDirectory=$workspace;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in $arguments){[void]$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(-not $process.Start()){throw 'Could not start baseline recovery'}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(4200000)){$process.Kill($true);$process.WaitForExit();throw 'Recovery exceeded 70-minute limit'}
        $exitCode=$process.ExitCode
        [IO.File]::WriteAllText((Join-Path $root ($baselineBackend+'-out.log')),$stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $root ($baselineBackend+'-error.log')),$stderr.GetAwaiter().GetResult())
    }finally{$process.Dispose()}
    $baselinePath=Join-Path $baselineRoot 'scenario-result.json'
    }
    $baseline=Get-Content -LiteralPath $baselinePath -Raw|ConvertFrom-Json
    $recordedBackend=Get-Content -LiteralPath (Join-Path $baselineRoot 'remote-evidence/noise-backend-config.json') -Raw|ConvertFrom-Json
    $steps += [ordered]@{backend=$baselineBackend;exit_code=$exitCode;result=$baselinePath;success=$baseline.success;cleanup_safe=$baseline.cleanup_safe;reused=$reuseBaseline}
    if($exitCode -ne 0 -or -not $baseline.success -or -not $baseline.cleanup_safe -or $baseline.mode -ne 'vanilla' -or $recordedBackend.noise_backend -ne $baselineBackend -or $baseline.artifact_sha256 -ne $hash -or $baseline.source_manifest_after_sha256 -ne $reused.source_manifest_after_sha256){throw 'Recovered baseline failed or differs from the reused source/JAR/backend identity'}
    $cases=@('vanilla','assisted'|ForEach-Object{[ordered]@{id="performance-overworld-$_-p2-cache_prediction_validation";purpose='performance';dimension='overworld';mode=$_;players=2;cache_entries=128;prediction=$true;validation_cells=8}})
    $comparisonRoot=Join-Path $root ($baselineBackend+'-comparison')
    New-Item -ItemType Directory -Path $comparisonRoot | Out-Null
    [IO.File]::WriteAllText((Join-Path $comparisonRoot 'matrix-plan.json'),([ordered]@{runtime_and_performance_cases=$cases}|ConvertTo-Json -Depth 6))
    & (Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1') -RunRoot $comparisonRoot -ScenarioResultPath @($baselinePath,$reusedPath)
    $analysis=Join-Path $comparisonRoot 'analysis/scenario-matrix-analysis.json'
    $analyses += $analysis
    $comparison=Get-Content -LiteralPath $analysis -Raw|ConvertFrom-Json
    if($comparison.status -ne 'COMPLETE' -or @($comparison.performance_pairs).Count -ne 1){throw 'Recovery comparison is incomplete'}
    }
}catch{$issues+=$_.Exception.ToString()}
finally{
    if($null -ne $lock){$lock.Dispose()}
    $summary=[ordered]@{schema='worldgen-assist.cooperative32-recovery.v1';status=if($issues.Count -eq 0){'COMPLETE'}else{'INCOMPLETE'};reused_assisted_result=$reusedPath;steps=$steps;analyses=$analyses;issues=$issues;root=$root;order='Existing cooperative assisted scenario, then fresh vanilla and cooperative baselines without assistance; descriptive comparison only'}
    [IO.File]::WriteAllText((Join-Path $root 'summary.json'),($summary|ConvertTo-Json -Depth 7))
    Write-Output "COOPERATIVE32_RECOVERY status=$($summary.status) summary=$(Join-Path $root 'summary.json')"
    if($issues.Count -gt 0){exit 1}
}
