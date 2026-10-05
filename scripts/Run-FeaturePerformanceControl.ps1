[CmdletBinding()]
param([switch]$Execute,[Parameter(Mandatory)][string]$LightingRoot)
# Same verified JAR, reverse order and missing remote-on/feature-off control.
# Performance evidence only; an original saved-light discrepancy stays failed.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$base=Join-Path $workspace 'test-artifacts'
$conditions=@(@{name='combined';mode='assisted';backend='parallel'},@{name='client-only';mode='assisted';backend='off'},@{name='original';mode='vanilla';backend='off'})
if(-not $Execute){@{conditions=$conditions;view_distance=32;warmups=1;repeats=3;new_tests=0;new_builds=0;lighting_parent=$LightingRoot;scope='Reverse order performance only, retains failed original lighting control'}|ConvertTo-Json -Depth 5;exit 0}
$LightingRoot=(Resolve-Path -LiteralPath $LightingRoot).Path
if(-not $LightingRoot.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned lighting evidence required'}
$root=Join-Path $base ('feature-performance-control-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'));New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=@();$performance=@();$comparisons=@();$artifacts=@();$before=@();$lock=$null;$lighting=$null
. (Join-Path $PSScriptRoot 'FeatureIntervalEvidence.ps1')
function Manifest {
    $files=@();foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -Recurse -File}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Step([string]$Name,[string]$Executable,[string[]]$Arguments,[int]$Seconds=4200){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$workspace
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($arg in $Arguments){[void]$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(-not $process.Start()){throw "Could not start $Name"}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();$finished=$process.WaitForExit($Seconds*1000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$stdout.GetAwaiter().GetResult());[IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$stderr.GetAwaiter().GetResult())
        $script:steps+=@{name=$Name;success=($finished -and $process.ExitCode -eq 0);exit_code=$process.ExitCode;timed_out=(-not $finished)}
        Write-Host "FEATURE_CONTROL_STEP name=$Name success=$($script:steps[-1].success)"
        if(-not $script:steps[-1].success){throw "Step failed: $Name (see retained logs)"}
    }finally{$process.Dispose()}
}
function Script-Step([string]$Name,[string]$Script,[string[]]$Arguments,[int]$Seconds=4200){Step $Name (Get-Command pwsh.exe).Source (@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot $Script))+$Arguments) $Seconds}
try{
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $lighting=Get-Content -LiteralPath (Join-Path $LightingRoot 'summary.json') -Raw|ConvertFrom-Json
    $invariants=Get-Content -LiteralPath (Join-Path $LightingRoot 'lighting-invariants.json') -Raw|ConvertFrom-Json
    if($lighting.junit.tests -ne 1 -or $lighting.junit.failures -or $lighting.junit.errors -or $lighting.junit.skipped){throw 'Affected offline reader test must have passed'}
    # Do not claim the failed lighting gate passed. Permit only this recorded
    # original-only discrepancy to continue measurement of already-tested JARs.
    if($lighting.success -or $lighting.issues.Count -ne 1 -or $lighting.issues[0] -ne ('System.Management.Automation.RuntimeException: Step failed: saved-invariants; retained logs in '+$LightingRoot)){throw 'This coordinator is restricted to the diagnosed original-only lighting checkpoint'}
    if($invariants.success -or $invariants.cases.Count -ne 3){throw 'Exact original-only failed control expected'}
    foreach($name in @('parallel','assisted')){
        $item=@($invariants.cases|Where-Object case -eq $name)
        if($item.Count -ne 1 -or -not $item[0].success -or $item[0].compared_values -ne 10649600 -or $item[0].required_chunks -ne 50 -or $item[0].halo_chunks -ne 162 -or $item[0].sky_differences -or $item[0].block_differences){throw 'Candidate ordinary saved lighting must match completely'}
    }
    $control=@($invariants.cases|Where-Object case -eq 'original')
    if($control.Count -ne 1 -or $control[0].success -or $control[0].changed_chunks -ne 2 -or $control[0].sky_differences -ne 20 -or $control[0].block_differences -ne 0){throw 'Original discrepancy differs from retained reviewed evidence'}
    $old=[IO.File]::ReadAllLines((Join-Path $LightingRoot 'source-manifest-before.sha256'))
    if(($old-join "`n") -cne ([IO.File]::ReadAllLines((Join-Path $LightingRoot 'source-manifest-after.sha256'))-join "`n")){throw 'Lighting batch input identity changed'}
    $before=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    if(($old-join "`n") -cne (($before|Where-Object { $_.Substring(66) -ne 'scripts/Run-FeaturePerformanceControl.ps1' })-join "`n")){throw 'Only this new coordinator may differ from lighting checkpoint'}
    Copy-Item -LiteralPath $PSCommandPath -Destination $root
    $parent=Get-Content -LiteralPath (Join-Path $lighting.reuse_parent 'summary.json') -Raw|ConvertFrom-Json
    if(-not $parent.success -or $parent.issues.Count){throw 'Completed strict pipeline gate required'}
    foreach($artifact in $parent.artifacts){
        if($artifact.sha256 -ne (Get-FileHash -LiteralPath $artifact.path).Hash -or $artifact.sha256 -ne (Get-FileHash -LiteralPath (Join-Path $parent.root ([IO.Path]::GetFileName($artifact.path)))).Hash){throw 'All three current/retained artifacts must stay exact'}
        $artifacts+=$artifact
    }
    $code='$ErrorActionPreference="Stop";$env:TEMP="E:/WorldgenAssist/temp";$env:TMP=$env:TEMP;$cpu=Get-CimInstance Win32_Processor;$drive=Get-CimInstance Win32_LogicalDisk -Filter "DeviceID=''E:''";[ordered]@{machine=$env:COMPUTERNAME;cpu=@($cpu|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors);e_free_bytes=$drive.FreeSpace;runtime_exists=(Test-Path -LiteralPath "E:/WorldgenAssist/java25/bin/java.exe");root="E:/WorldgenAssist/port26.3"}|ConvertTo-Json -Depth 5'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
    Step 'physical-environment' (Get-Command ssh.exe).Source @('-o','BatchMode=yes','-o','ConnectTimeout=15','gen1c@100.103.102.109','powershell -NoProfile -NonInteractive -EncodedCommand '+$encoded) 60
    $profile=Get-Content -LiteralPath (Join-Path $root 'physical-environment-out.log') -Raw|ConvertFrom-Json
    $oldProfile=Get-Content -LiteralPath (Join-Path $parent.reuse_parent 'physical-environment-out.log') -Raw|ConvertFrom-Json
    if(-not $profile.runtime_exists -or $profile.e_free_bytes -lt 20GB -or $profile.machine -ne $oldProfile.machine -or (($profile.cpu|ConvertTo-Json -Compress) -cne ($oldProfile.cpu|ConvertTo-Json -Compress))){throw 'Natural weak E-host identity/capacity prerequisite differs'}
    Get-CimInstance Win32_Processor|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $root 'client-cpu.json')
    if(([IO.File]::ReadAllText((Join-Path $root 'client-cpu.json')).Trim()) -cne ([IO.File]::ReadAllText((Join-Path $parent.root 'client-cpu.json')).Trim())){throw 'Same strong client PC CPU required'}
    $common=@('-RemoteHost','gen1c@100.103.102.109','-RemoteRoot','E:/WorldgenAssist/port26.3','-Dimension','overworld','-Players','2','-CacheEntries','128','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','0','-ServerJvmProcessors','0','-NoiseBackend','cooperative','-WindowProfile','deep','-RemoteApplicationProfile','overlap','-PrefetchLookahead','0','-RemoteWorkKind','complete','-CompleteVerification','peer','-Purpose','performance','-Prediction','true','-ViewDistance','32','-MeasureFullView','-QuietRemoteTrace','-WarmupRuns','1','-MeasuredRepeats','3')
    foreach($condition in $conditions){
        $case=Join-Path $root ('performance/'+$condition.name)
        Script-Step ('performance-'+$condition.name) 'Run-WorldgenScenario.ps1' ($common+@('-Mode',$condition.mode,'-FeatureBackend',$condition.backend,'-OutputRoot',$case))
        $result=Get-Content -LiteralPath (Join-Path $case 'scenario-result.json') -Raw|ConvertFrom-Json
        if(-not $result.success -or -not $result.cleanup_safe -or -not $result.loopback_only -or $result.artifact_sha256 -ne $artifacts[0].sha256 -or $result.mode -ne $condition.mode -or $result.feature_backend -ne $condition.backend -or $result.feature_fixture -or $result.decoration_digest -or $result.source_manifest_before_sha256 -ne $result.source_manifest_after_sha256){throw 'Ordinary result identity/cleanup differs'}
        if($result.server_logical_processors -ne 0 -or $result.server_jvm_processors -ne 0 -or $result.client_worker_threads -ne 4 -or $result.performance.warmup_runs -ne 1 -or $result.performance.measured_repeats -ne 3 -or $result.remote_work_kind -ne 'complete' -or $result.complete_verification -ne 'peer'){throw 'Measured CPU/worker/verification profile differs'}
        $config=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/scenario-config.json') -Raw|ConvertFrom-Json
        $region=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/measurement-region.json') -Raw|ConvertFrom-Json
        if($config.view_distance -ne 32 -or $region.radius -ne 32 -or $region.shape -ne 'view' -or $region.expected_chunks_per_owner -ne 3461){throw 'Actual server view32 workload differs'}
        foreach($owner in 0..1){if(@(Select-String -LiteralPath (Join-Path $case "clients/owner-$owner/client/options.txt") -Pattern '^renderDistance:32$').Count -ne 1){throw 'Actual client view32 missing'}}
        $log=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/latest.log') -Raw
        if($log -match 'fixture.armed|fixture.feature_started|fixture.complete|fixture.failed|outside guarded stock|message reservation exceeded|Mixin apply failed|Encountered an unexpected exception|job.full_terrain_apply_rejected|peer_terrain_mismatch|worker.quarantine|Independent whole-terrain audit mismatch|Independent peer whole-terrain mismatch'){throw 'Runtime failure/fixture marker'}
        if($condition.mode -eq 'assisted' -and ($log -notmatch 'job.full_terrain_accepted .*audited=true' -or $log -notmatch 'job.full_terrain_accepted .*audited=false' -or $log -notmatch 'job.peer_terrain_applied')){throw 'Actual complete/peer use missing'}
        $repeats=@()
        foreach($repeat in 1..3){
            $window=[regex]::Match($log,"(?s)CAWG_SCENARIO_MEASURED_BEGIN_$repeat\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_$repeat\b")
            if(-not $window.Success){throw 'Measured window missing'}
            $interval=Get-FeatureIntervalEvidence $window.Groups['body'].Value
            if(-not $interval.bodies -or -not $interval.light_initializations -or $interval.conflicting_pairs -or ($condition.backend -eq 'parallel' -and ($interval.feature_light_conflicting_pairs -or $interval.light_light_conflicting_pairs -or -not $interval.overlapping_pairs))){throw 'Feature/light scope or conflict failure'}
            $repeats+=@{repeat=$repeat;features=$interval}
        }
        $performance+=@{condition=$condition.name;mode=$condition.mode;feature_backend=$condition.backend;result=(Join-Path $case 'scenario-result.json');repeats=$repeats}
    }
    foreach($pair in @(@{name='scheduler-on-clients';baseline='client-only';candidate='combined'},@{name='client-only';baseline='original';candidate='client-only'},@{name='combined';baseline='original';candidate='combined'})){
        $compare=Join-Path $root ('performance/compare-'+$pair.name);New-Item -ItemType Directory -Path $compare|Out-Null
        $plan=@($conditions|Where-Object {$_.name -in @($pair.baseline,$pair.candidate)}|ForEach-Object {@{id=$_.name;purpose='performance';dimension='overworld';mode=$_.mode;feature_backend=$_.backend;players=2;cache_entries=128;prediction=$true;validation_cells=8}})
        @{runtime_and_performance_cases=$plan}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $compare 'matrix-plan.json')
        Script-Step ('compare-'+$pair.name) 'Compare-WorldgenScenarioMatrix.ps1' @('-RunRoot',$compare,'-BaselineResultPath',(Join-Path $root "performance/$($pair.baseline)/scenario-result.json"),'-CandidateResultPath',(Join-Path $root "performance/$($pair.candidate)/scenario-result.json")) 300
        $analysis=Get-Content -LiteralPath (Join-Path $compare 'analysis/scenario-matrix-analysis.json') -Raw|ConvertFrom-Json
        if($analysis.status -ne 'COMPLETE' -or $analysis.issues.Count -or $analysis.performance_pairs.Count -ne 1 -or $analysis.performance_pairs[0].status -ne 'COMPLETE'){throw 'Equal-work comparison incomplete'}
        $comparisons+=@{name=$pair.name;analysis=(Join-Path $compare 'analysis/scenario-matrix-analysis.json')}
    }
}catch{$issues.Add($_.Exception.ToString())}
finally{
    try{$after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after);if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Inputs changed during batch')};foreach($artifact in $artifacts){if($artifact.sha256 -ne (Get-FileHash -LiteralPath $artifact.path).Hash){$issues.Add('Artifact changed during batch')}}}catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()}
    @{schema='worldgen-assist.feature-performance-control.v1';success=($issues.Count -eq 0);root=$root;lighting_parent=$LightingRoot;lighting_gate_passed=$false;retained_lighting_discrepancy='Original2chunks20SKY; both candidates zero on50interior/162halo; recorded failed gate retained, not beta';artifacts=$artifacts;steps=$steps;performance=$performance;comparisons=$comparisons;issues=@($issues);beta_claim=$false;scope='Same dev9 JAR ordinary reverse-order view32 two clients on strong PC/natural weak E-host; no new unit/build/correctness fixture. Performance only.'}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Output "FEATURE_PERFORMANCE_CONTROL success=$($issues.Count -eq 0) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
