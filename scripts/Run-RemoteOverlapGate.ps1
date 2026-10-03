[CmdletBinding()]
param([switch]$Execute,[ValidateRange(2,32)][int]$ViewDistance=10)

# Configuration-only experiment: the unchanged protocol-6 artifact already
# has a bounded asynchronous demand continuation. No build or JUnit rerun.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$expectedHash='97187A759E3AE7D2DA74E00BB50AF3EC70FD7AAF15248D7AAE8DEFEB5CC1672D'
if(-not $Execute){
    [ordered]@{artifact_sha256=$expectedHash;builds='NOT_RUN: unchanged JAR';unit_tests='NOT_RUN: unchanged MOD';correctness='two-owner Overworld pair; cooperative both; wide window; asynchronous application, base100ms/max200ms';performance="full-view $ViewDistance pair; warmup1 + measured3";sequence='finish implementation, then one sequential batch'}|ConvertTo-Json
    exit 0
}
$root=Join-Path $workspace ('test-artifacts/remote-overlap-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $root|Out-Null
$pwsh=(Get-Command pwsh.exe).Source
$steps=@();$issues=[Collections.Generic.List[string]]::new();$lock=$null;$performancePath=$null
function Step([string]$Name,[string[]]$Arguments,[int]$Seconds){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$pwsh;$info.WorkingDirectory=$workspace
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in $Arguments){[void]$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(-not $process.Start()){throw "Could not start $Name"}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        $finished=$process.WaitForExit($Seconds*1000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$stderr.GetAwaiter().GetResult())
        $record=[ordered]@{name=$Name;status=if($finished -and $process.ExitCode -eq 0){'PASSED'}else{'FAILED'};exit_code=$process.ExitCode}
        Write-Host "REMOTE_OVERLAP_STEP name=$Name status=$($record.status)"
        return $record
    }finally{$process.Dispose()}
}
try{
    $lock=[IO.File]::Open((Join-Path $workspace 'test-artifacts/validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    foreach($name in @('Run-RemoteOverlapGate.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1','Compare-WorldgenScenarioMatrix.ps1','Run-ConstrainedServerBenchmark.ps1')){
        $tokens=$null;$parseErrors=$null
        [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$parseErrors)
        if($parseErrors.Count){throw "$name syntax: $(($parseErrors.Message)-join '; ')"}
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $root $name)
    }
    $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1')
    if((Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash -ne $expectedHash){throw 'Configuration experiment requires the exact dev.12 JAR'}
    Copy-Item -LiteralPath $artifact.Path -Destination $root
    $correctRoot=Join-Path $root 'correctness';New-Item -ItemType Directory -Force -Path $correctRoot|Out-Null
    $cases=@()
    foreach($mode in @('vanilla','assisted')){
        $directory=Join-Path $correctRoot $mode
        $steps+=Step "correctness-$mode" @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-Dimension','overworld','-Mode',$mode,'-Players','2','-Purpose','correctness','-CacheEntries','128','-Prediction','false','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','2','-ServerJvmProcessors','2','-CorrectnessDemandWaitMs','100','-NoiseBackend','cooperative','-WindowProfile','wide','-RemoteApplicationProfile','overlap','-OutputRoot',$directory) 1200
        if($steps[-1].status -ne 'PASSED'){throw "Affected runtime failed: $mode"}
        $result=Get-Content -LiteralPath (Join-Path $directory 'scenario-result.json') -Raw|ConvertFrom-Json
        if(-not $result.success -or -not $result.cleanup_safe -or $result.artifact_sha256 -ne $expectedHash){throw 'Runtime cleanup/artifact mismatch'}
        $application=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/remote-application-config.json') -Raw|ConvertFrom-Json
        if($application.profile -ne 'overlap' -or $application.ready_surface_only -or $application.base_wait_ms -ne 100 -or $application.maximum_wait_ms -ne 200){throw 'Application configuration mismatch'}
        $cases += [ordered]@{id="correctness-overworld-$mode-p2-cache";dimension='overworld';mode=$mode;players=2;purpose='correctness';cache_entries=128;prediction=$false;validation_cells=8}
    }
    [IO.File]::WriteAllText((Join-Path $correctRoot 'matrix-plan.json'),([ordered]@{runtime_and_performance_cases=$cases}|ConvertTo-Json -Depth 5))
    $steps+=Step 'correctness-comparison' @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'),'-RunRoot',$correctRoot) 300
    $comparison=Get-Content -LiteralPath (Join-Path $correctRoot 'analysis/scenario-matrix-analysis.json') -Raw|ConvertFrom-Json
    if($steps[-1].status -ne 'PASSED' -or $comparison.status -ne 'COMPLETE'){throw 'Correctness comparison incomplete'}
    $assistedRoot=Join-Path $correctRoot 'assisted'
    $log=Get-Content -LiteralPath (Join-Path $assistedRoot 'remote-evidence/latest.log') -Raw
    $applied=[Collections.Generic.HashSet[string]]::new();$waited=[Collections.Generic.HashSet[string]]::new()
    foreach($entry in [regex]::Matches($log,'job\.complete id=(\S+) .*work_kind=GRID_AND_SURFACE .*grid_samples=[1-9]\d*')){[void]$applied.Add($entry.Groups[1].Value)}
    foreach($entry in [regex]::Matches($log,'demand\.wait id=(\S+) adaptive=true .*maximum_ms=200')){[void]$waited.Add($entry.Groups[1].Value)}
    $owners=Get-Content -LiteralPath (Join-Path $assistedRoot 'remote-evidence/owner-map.json') -Raw|ConvertFrom-Json
    $use=@()
    foreach($owner in $owners.PSObject.Properties){
        $jobs=@([regex]::Matches($log,'job\.sent id=(\S+) .*owner='+[regex]::Escape([string]$owner.Value)+'\b') | ForEach-Object {$_.Groups[1].Value} | Where-Object {$applied.Contains($_) -and $waited.Contains($_)})
        if($jobs.Count -eq 0){throw "No bounded asynchronous grid application for $($owner.Name)"}
        $use += [ordered]@{owner=$owner.Name;waited_and_applied_jobs=$jobs.Count}
    }
    [IO.File]::WriteAllText((Join-Path $root 'overlap-use.json'),($use|ConvertTo-Json))
    $lock.Dispose();$lock=$null
    $steps+=Step 'performance-pair' @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-ConstrainedServerBenchmark.ps1'),'-Execute','-TwoLogicalOnly','-ViewDistance',[string]$ViewDistance,'-MeasureFullView','-ServerJvmProcessors','2','-QuietRemoteTrace','-AssistedNoiseBackend','cooperative','-VanillaNoiseBackend','cooperative','-WindowProfile','wide','-RemoteApplicationProfile','overlap') 7200
    $output=Get-Content -LiteralPath (Join-Path $root 'performance-pair-out.log') -Raw
    $match=[regex]::Match($output,'CONSTRAINED_SERVER_BENCHMARK status=COMPLETE summary=(?<path>[^\r\n]+)')
    if($steps[-1].status -ne 'PASSED' -or -not $match.Success){throw 'Performance pair incomplete'}
    $performancePath=$match.Groups['path'].Value.Trim()
    $performance=Get-Content -LiteralPath $performancePath -Raw|ConvertFrom-Json
    if($performance.status -ne 'COMPLETE' -or $performance.artifact_sha256 -ne $expectedHash){throw 'Performance identity mismatch'}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    if($null -ne $lock){$lock.Dispose();Remove-Item -LiteralPath (Join-Path $workspace 'test-artifacts/validation-matrix.lock') -Force -ErrorAction SilentlyContinue}
    $summary=[ordered]@{schema='worldgen-assist.remote-overlap-gate.v1';success=($issues.Count -eq 0);artifact_sha256=$expectedHash;mod_changed=$false;builds='NOT_RUN';unit_tests='NOT_RUN';remote_application_profile='overlap';steps=$steps;issues=@($issues);root=$root;performance_summary=$performancePath}
    $path=Join-Path $root 'summary.json';[IO.File]::WriteAllText($path,($summary|ConvertTo-Json -Depth 8))
    Write-Output "REMOTE_OVERLAP_GATE_COMPLETE summary=$path success=$($summary.success)"
    if(-not $summary.success){exit 1}
}
