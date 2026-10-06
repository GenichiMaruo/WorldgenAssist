[CmdletBinding()]
param([switch]$Execute,[Parameter(Mandatory)][string]$GateEvidence)
# Current dev23 performance confirmation only. No unit/build/native reruns.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base=Join-Path $workspace 'test-artifacts'
function Evidence-Child([string]$Path){
    $resolved=[IO.Path]::GetFullPath($Path)
    if(-not $resolved.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Workspace evidence child required'}
    $resolved
}
$evidence=Evidence-Child $GateEvidence
$conditions=@(@{name='assisted';mode='assisted';backend='parallel'},@{name='original';mode='vanilla';backend='parallel'})
if(-not $Execute){
    [ordered]@{gate_evidence=$evidence;conditions=$conditions;new_units=0;new_builds=0;new_native=0;view_distance=32;warmups=1;repeats=3;remote_root='E:/WorldgenAssist/port26.3';source='Exact unchanged production/test inventory and all current/retained3JAR hashes';sequence='Complete this harness first; one reverse-order pair; original four unit methods and three-loader882 fixture evidence retained, not rerun';beta_claim=$false}|ConvertTo-Json -Depth 5
    exit 0
}
$root=Join-Path $base ('authoritative-biome-confirmation-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=[Collections.Generic.List[object]]::new()
$before=@();$artifacts=@();$performance=@();$lock=$null;$sourceHash=$null;$coordinateHash=$null;$combined=@{}
$AuthoritativeBiomeInputs=$true
$PublicationFence=$false
. (Join-Path $PSScriptRoot 'FeatureIntervalEvidence.ps1')
. (Join-Path $PSScriptRoot 'FeatureFixtureEvidence.ps1')
function Manifest{
    $files=@()
    foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -File -Recurse}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Step([string]$Name,[string]$Executable,[string[]]$Arguments,[int]$Seconds=4200){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$workspace
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in $Arguments){[void]$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(-not $process.Start()){throw "Could not start $Name"}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();$finished=$process.WaitForExit($Seconds*1000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$stderr.GetAwaiter().GetResult())
        $steps.Add([ordered]@{name=$Name;success=($finished -and $process.ExitCode -eq 0);exit_code=$process.ExitCode;timed_out=(-not $finished)})
        Write-Output "BIOME_CONFIRMATION_STEP name=$Name success=$($steps[-1].success)"
        if(-not $steps[-1].success){throw "Step failed: $Name (retained logs)"}
    }finally{$process.Dispose()}
}
function Script-Step([string]$Name,[string]$Script,[string[]]$Arguments,[int]$Seconds=4200){
    Step $Name (Get-Command pwsh.exe).Source (@('-NoProfile','-File',(Join-Path $PSScriptRoot $Script))+$Arguments) $Seconds
}
function Coordinates([string]$Log){
    $keys=@(foreach($repeat in 1..3){
        $window=[regex]::Match($Log,"(?s)CAWG_SCENARIO_MEASURED_BEGIN_$repeat\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_$repeat\b")
        if(-not $window.Success){throw 'Measured interval missing'}
        $positions=@([regex]::Matches($window.Groups['body'].Value,'stage\.complete stage=noise chunk=(-?\d+,-?\d+)\b')|ForEach-Object {$_.Groups[1].Value}|Sort-Object -Unique)
        if($positions.Count -ne 10658){throw 'Expected10658 actual task coordinates'}
        $positions -join ';'
    })
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($keys-join "`n"))))
}
try{
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $prior=Get-Content -LiteralPath (Join-Path $evidence 'summary.json') -Raw|ConvertFrom-Json
    if(-not $prior.success -or $prior.schema -ne 'worldgen-assist.feature-pipeline-gate.v1' -or -not $prior.authoritative_biome_inputs -or $prior.local_only -or $prior.issues.Count -or $prior.junit.tests -ne 4 -or $prior.junit.failures -or $prior.junit.errors -or $prior.junit.skipped -or $prior.assistance_use.Count -ne 3 -or $prior.performance.Count -ne 2 -or $prior.artifacts.Count -ne 3){throw 'Exact completed authoritative-input gate required'}
    if($prior.steps.Count -ne 16 -or @($prior.steps|Where-Object {-not $_.success -or $_.exit_code -ne 0 -or $_.timed_out}).Count){throw 'All original16 steps must pass'}
    $originalManifest=[IO.File]::ReadAllLines((Join-Path $evidence 'source-manifest-before.sha256'))
    if(($originalManifest-join "`n") -cne ([IO.File]::ReadAllLines((Join-Path $evidence 'source-manifest-after.sha256'))-join "`n")){throw 'Original batch inputs changed'}
    $before=Manifest
    $productionPattern='^([A-F0-9]{64})  (src/.+|loaders/(?:forge|neoforge)/src/.+|gradle\.properties|build\.gradle|settings\.gradle|loaders/(?:forge|neoforge)/build\.gradle)$'
    $savedProduction=@($originalManifest|Where-Object {$_ -match $productionPattern})
    $currentProduction=@($before|Where-Object {$_ -match $productionPattern})
    if(($savedProduction-join "`n") -cne ($currentProduction-join "`n")){throw 'Exact original production/test/build inventory differs'}
    # Analyzer groups changed only AFTER closed runtime; the new coordinator is
    # the only new harness. Every original execution/collector/helper stays exact.
    $savedScripts=@{};foreach($line in $originalManifest){if($line -match '^([A-F0-9]{64})  (scripts/.+)$'){$savedScripts[$Matches[2]]=$Matches[1]}}
    foreach($key in $savedScripts.Keys){
        if($key -eq 'scripts/AnalyzeWorldgenJfr.java'){continue}
        if((Get-FileHash -LiteralPath (Join-Path $workspace $key)).Hash -ne $savedScripts[$key]){throw "Original runtime/helper changed: $key"}
    }
    $expected=@{
        'common.AuthoritativeBiomeWindow263Test'='originalWindowSurvivesWireAndPrivateRestorationWithoutSharingMutableChunks()'
        'network.TerrainShapingPayload263Test'='boundedShapingSurvivesMixedBatchAndInvalidLengthIsRejectedBeforeReadingBody()'
        'server.CompleteTerrainPeer263Test'='agreementRequiresDistinctAssignmentsAndEveryTerrainComponentAndFailsPromptly()'
        'server.BiomePhase263Test'='distinctPeerPhaseIsBoundedRevocableAndConsistentWithFinalReplies()'
    }
    foreach($entry in $expected.GetEnumerator()){
        $file=Join-Path $evidence ('TEST-io.github.genichimaruo.worldgenassist.'+$entry.Key+'.xml')
        [xml]$xml=Get-Content -LiteralPath $file
        if([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or $xml.testsuite.testcase.name -cne $entry.Value){throw 'Exact prior affected-method XML proof differs'}
        Copy-Item -LiteralPath $file -Destination $root
    }
    foreach($loader in @('fabric','forge','neoforge')){
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $loader
        $old=@($prior.artifacts|Where-Object loader -eq $loader);$sha=(Get-FileHash -LiteralPath $artifact.Path).Hash
        if($old.Count -ne 1 -or $artifact.Version -ne '0.1.0-alpha.8-dev.23+mc26.3' -or $sha -ne $old[0].sha256 -or $sha -ne (Get-FileHash -LiteralPath (Join-Path $evidence $artifact.FileName)).Hash){throw 'Current/retained exact dev23 artifact differs'}
        $artifacts+=@{loader=$loader;path=$artifact.Path;sha256=$sha;version=$artifact.Version}
        Copy-Item -LiteralPath $artifact.Path -Destination $root
    }
    foreach($comparison in @('correctness/compare-assisted','native/forge/comparison','native/neoforge/comparison')){
        $proof=Get-Content -LiteralPath (Join-Path $evidence ($comparison+'/summary.json')) -Raw|ConvertFrom-Json
        if(-not $proof.success -or $proof.comparisons.Count -ne 4){throw 'Prior four-stage fixture proof incomplete'}
        foreach($stage in @('noise','decoration','saved_structures','saved_light')){
            $rows=@($proof.comparisons|Where-Object stage -eq $stage)
            if($rows.Count -ne 1 -or $rows[0].required -ne 882 -or $rows[0].equal -ne 882 -or $rows[0].missing.Count -or $rows[0].changed.Count){throw 'Prior full882 fixture proof changed'}
        }
    }
    $tokens=$null;$parseErrors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Run-FeaturePipelineGate.ps1'),[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count){throw 'Original gate helper syntax differs'}
    foreach($name in @('Check-Physical','Check-Assistance')){
        $definition=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true))
        if($definition.Count -ne 1){throw 'Original identity/coverage helper missing'}
        . ([scriptblock]::Create($definition[0].Extent.Text))
    }
    foreach($case in @('correctness/assisted','native/forge/assisted','native/neoforge/assisted')){$null=Check-Assistance (Join-Path $evidence $case) ($case.StartsWith('native/'))}
    foreach($condition in @($conditions[1],$conditions[0])){$null=Check-Physical (Join-Path $evidence ('performance/'+$condition.name)) $condition $false}
    $originalLog=Get-Content -LiteralPath (Join-Path $evidence 'performance/original/remote-evidence/latest.log') -Raw
    $coordinateHash=Coordinates $originalLog
    if($coordinateHash -ne (Coordinates (Get-Content -LiteralPath (Join-Path $evidence 'performance/assisted/remote-evidence/latest.log') -Raw))){throw 'Prior exact paired coordinates differ'}
    [IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    foreach($name in @('Run-AuthoritativeBiomeConfirmation.ps1','Run-FeaturePipelineGate.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1','Compare-WorldgenScenarioMatrix.ps1','FeatureIntervalEvidence.ps1','FeatureFixtureEvidence.ps1')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root}
    $remoteCode='$ErrorActionPreference="Stop";$env:TEMP="E:/WorldgenAssist/temp";$env:TMP=$env:TEMP;$taskCpu=Get-CimInstance Win32_Processor;$taskDrive=Get-CimInstance Win32_LogicalDisk -Filter "DeviceID=''E:''";[ordered]@{machine=$env:COMPUTERNAME;cpu=@($taskCpu|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors);e_free_bytes=$taskDrive.FreeSpace;runtime_exists=(Test-Path -LiteralPath "E:/WorldgenAssist/java25/bin/java.exe");root="E:/WorldgenAssist/port26.3"}|ConvertTo-Json -Depth 5'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($remoteCode))
    Step 'physical-environment' (Get-Command ssh.exe).Source @('-o','BatchMode=yes','-o','ConnectTimeout=15','gen1c@100.103.102.109','powershell -NoProfile -NonInteractive -EncodedCommand '+$encoded) 60
    $observed=Get-Content -LiteralPath (Join-Path $root 'physical-environment-out.log') -Raw|ConvertFrom-Json
    $oldEnvironment=Get-Content -LiteralPath (Join-Path $evidence 'physical-environment-out.log') -Raw|ConvertFrom-Json
    if(-not $observed.runtime_exists -or $observed.e_free_bytes -lt 20GB -or $observed.machine -ne $oldEnvironment.machine -or ($observed.cpu|ConvertTo-Json -Compress) -cne ($oldEnvironment.cpu|ConvertTo-Json -Compress)){throw 'Same weak E-host prerequisite differs'}
    Get-CimInstance Win32_Processor|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $root 'client-cpu.json')
    if(([IO.File]::ReadAllText((Join-Path $root 'client-cpu.json')).Trim()) -cne ([IO.File]::ReadAllText((Join-Path $evidence 'client-cpu.json')).Trim())){throw 'Same strong client PC required'}
    $common=@('-RemoteHost','gen1c@100.103.102.109','-RemoteRoot','E:/WorldgenAssist/port26.3','-Dimension','overworld','-Players','2','-CacheEntries','128','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','0','-ServerJvmProcessors','0','-NoiseBackend','cooperative','-WindowProfile','deep','-RemoteApplicationProfile','overlap','-PrefetchLookahead','0','-RemoteWorkKind','complete','-CompleteVerification','peer','-AuthoritativeBiomes','demand','-Purpose','performance','-Prediction','true','-ViewDistance','32','-MeasureFullView','-QuietRemoteTrace','-ServerFlightRecording','-WarmupRuns','1','-MeasuredRepeats','3')
    foreach($condition in $conditions){
        $case=Join-Path $root ('performance/'+$condition.name)
        Script-Step ('performance-'+$condition.name) 'Run-WorldgenScenario.ps1' ($common+@('-Mode',$condition.mode,'-FeatureBackend','parallel','-OutputRoot',$case))
        $result=Check-Physical $case $condition $false
        if($result.client_worker_threads -ne 4 -or $result.performance.warmup_runs -ne 1 -or $result.performance.measured_repeats -ne 3 -or $result.section_preparation -ne 'inline' -or $result.remote_biomes -ne 'off' -or $result.biome_digest){throw 'Same natural ordinary performance settings required'}
        if($sourceHash -and $sourceHash -ne $result.source_manifest_after_sha256){throw 'Source differs across new conditions'};$sourceHash=$result.source_manifest_after_sha256
        $log=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/latest.log') -Raw
        if((Coordinates $log) -ne $coordinateHash){throw 'New actual task coordinates differ from original three relocations'}
        if($condition.mode -eq 'assisted' -and ($log -notmatch 'job.full_terrain_accepted .*audited=true' -or $log -notmatch 'job.full_terrain_accepted .*audited=false' -or $log -notmatch 'job.peer_terrain_applied')){throw 'Actual peer/full-audit use missing'}
        $repeats=@()
        foreach($repeat in 1..3){
            $body=[regex]::Match($log,"(?s)CAWG_SCENARIO_MEASURED_BEGIN_$repeat\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_$repeat\b").Groups['body'].Value
            $interval=Get-FeatureIntervalEvidence $body
            if($interval.bodies -ne 10082 -or $interval.light_initializations -ne 10082 -or $interval.peak_bodies -ne 2 -or $interval.conflicting_pairs -or $interval.feature_light_conflicting_pairs -or $interval.light_light_conflicting_pairs -or -not $interval.overlapping_pairs){throw 'Exact feature/light workload or ownership differs'}
            $inputCount=[regex]::Matches($body,'job.authoritative_biomes_applied').Count
            if($condition.mode -eq 'assisted' -and -not $inputCount){throw 'Actual provided input use missing in measured repeat'}
            $repeats+=@{repeat=$repeat;features=$interval;provided_inputs_applied=$inputCount}
        }
        foreach($row in $result.performance.measured){if($row.completed_tasks -ne 10658 -or $row.failed_tasks -or $row.timeouts){throw 'Equal completed task requirement failed'}}
        $performance+=@{condition=$condition.name;result=(Join-Path $case 'scenario-result.json');repeats=$repeats}
    }
    $compare=Join-Path $root 'performance/comparison';New-Item -ItemType Directory -Path $compare|Out-Null
    @{runtime_and_performance_cases=@($conditions|ForEach-Object {@{id=$_.name;purpose='performance';dimension='overworld';mode=$_.mode;feature_backend='parallel';players=2;cache_entries=128;prediction=$true;validation_cells=8}})}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $compare 'matrix-plan.json')
    Script-Step 'compare-reverse' 'Compare-WorldgenScenarioMatrix.ps1' @('-RunRoot',$compare,'-BaselineResultPath',(Join-Path $root 'performance/original/scenario-result.json'),'-CandidateResultPath',(Join-Path $root 'performance/assisted/scenario-result.json')) 300
    $newAnalysis=Get-Content -LiteralPath (Join-Path $compare 'analysis/scenario-matrix-analysis.json') -Raw|ConvertFrom-Json
    $oldAnalysis=Get-Content -LiteralPath (Join-Path $evidence 'performance/compare-authoritative-client/analysis/scenario-matrix-analysis.json') -Raw|ConvertFrom-Json
    foreach($analysis in @($newAnalysis,$oldAnalysis)){if($analysis.status -ne 'COMPLETE' -or $analysis.issues.Count -or $analysis.performance_pairs.Count -ne 1 -or $analysis.performance_pairs[0].status -ne 'COMPLETE' -or -not $analysis.performance_pairs[0].equal_completed_work -or -not $analysis.performance_pairs[0].conditions_and_coordinates_match){throw 'Complete equal-work comparison required'}}
    foreach($metric in @('server_full_region_ready_ms','client_region_receipt_ms','server_cpu_ms','tick_p95_ms')){
        $rows=@();foreach($order in @(@{name='original-first';pair=$oldAnalysis.performance_pairs[0]},@{name='assisted-first';pair=$newAnalysis.performance_pairs[0]})){
            foreach($row in $order.pair.paired_repeat_metrics.$metric.rows){$rows+=@{order=$order.name;repeat=$row.repeat;baseline=$row.vanilla;candidate=$row.assisted;ratio=$row.ratio}}
        }
        if($rows.Count -ne 6){throw 'Six matched pairs required'};$ratios=@($rows.ratio|Sort-Object)
        $combined[$metric]=@{rows=$rows;median_paired_percent=100*(($ratios[2]+$ratios[3])/2-1);lower_pairs=@($rows|Where-Object {$_.ratio -lt 1}).Count;pairs=6}
    }
}catch{$issues.Add($_.Exception.ToString())}
finally{
    try{$after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after);if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Inputs changed during confirmation')};foreach($artifact in $artifacts){if($artifact.sha256 -ne (Get-FileHash -LiteralPath $artifact.path).Hash){$issues.Add('Artifact changed during confirmation')}}}catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()}
    [ordered]@{schema='worldgen-assist.authoritative-biome-confirmation.v1';success=($issues.Count -eq 0);root=$root;gate_evidence=$evidence;order='assisted-first';new_units=0;new_mod_builds=0;new_native=0;reused_unit_methods=4;artifacts=$artifacts;steps=@($steps);performance=$performance;combined_paired_metrics=$combined;coordinates_sha256=$coordinateHash;source_sha256=$sourceHash;issues=@($issues);beta_claim=$false;scope='Exact dev23 current/retained JARs and original production/test inputs; opposite-order ordinary weak E-server/view32/two same-strong-PC clients with identical parallel scheduler. Six pairs reuse three coordinates, not six independent seeds. Original four-method/build/three-loader882 fixture evidence retained; no new decoration/light/native/gameplay stability proof.'}|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Output "AUTHORITATIVE_BIOME_CONFIRMATION success=$($issues.Count -eq 0) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
