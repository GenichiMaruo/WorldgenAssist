[CmdletBinding()]
param([switch]$Execute,[string]$ReuseBuildRoot,[switch]$ClientRequestIngress)
# All production, harness and document edits must finish before this ONE batch.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base=Join-Path $workspace 'test-artifacts'
if($ClientRequestIngress -and $ReuseBuildRoot){throw 'New ingress implementation requires three fresh selected methods and three builds'}
$experiment=if($ClientRequestIngress){'client_request'}else{'batching'}
$version=if($ClientRequestIngress){'0.1.0-alpha.9-dev.2+mc26.3'}else{'0.1.0-alpha.9-dev.1+mc26.3'}
if(-not $Execute){@{experiment=$experiment;loaders=@('forge','neoforge');native_builds=$(if($ReuseBuildRoot){0}else{2});fabric_builds=[int][bool]$ClientRequestIngress;reused_build_root=$ReuseBuildRoot;new_junit=$(if($ClientRequestIngress){3}else{0});conditions=$(if($ClientRequestIngress){@('clientMAIN','clientNETWORK')}else{@('batchingOFF','batchingON')});both_remote_assisted=$true;view=32;players=2;warmup=1;measured=3;remote='gen1c@100.103.102.109';root='E:/WorldgenAssist/port26.3';scope='Same new native JAR per pair; original survival input/authoritative witnesses/stopped saved player, voxel and lighting. Marginal transport experiment, not general assistance or beta proof.'}|ConvertTo-Json;exit 0}
$root=Join-Path $base ($(if($ClientRequestIngress){'native-client-request-gate-'}else{'native-request-batch-gate-'})+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$steps=[Collections.Generic.List[object]]::new();$issues=[Collections.Generic.List[string]]::new()
$before=@();$artifacts=@();$results=@();$lock=$null;$reusePath=$null;$junit=$null
function Manifest {
    $files=@();foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts','docs')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -File -Recurse}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Step([string]$Name,[string]$Executable,[string[]]$Arguments,[int]$Seconds){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$workspace;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($arg in $Arguments){[void]$info.ArgumentList.Add($arg)}
    $p=[Diagnostics.Process]::new();$p.StartInfo=$info
    try {
        if(-not $p.Start()){throw ('Cannot start '+$Name)}
        $out=$p.StandardOutput.ReadToEndAsync();$err=$p.StandardError.ReadToEndAsync();$finished=$p.WaitForExit($Seconds*1000)
        if(-not $finished){$p.Kill($true);$p.WaitForExit()}
        $output=$out.GetAwaiter().GetResult();[IO.File]::WriteAllText((Join-Path $root ($Name+'-out.log')),$output);[IO.File]::WriteAllText((Join-Path $root ($Name+'-err.log')),$err.GetAwaiter().GetResult())
        $steps.Add(@{name=$Name;success=($finished -and $p.ExitCode -eq 0);exit_code=$p.ExitCode;timed_out=(-not $finished)})
        Write-Host "NATIVE_BATCH_STEP name=$Name success=$($steps[-1].success)"
        if(-not $steps[-1].success){throw ('Affected batch step failed: '+$Name+'; original logs retained')}
        return $output
    }finally{$p.Dispose()}
}
function Paired([double[]]$Baseline,[double[]]$Candidate){
    if($Baseline.Count -ne 3 -or $Candidate.Count -ne 3){throw 'Three exact matched repeats required'}
    $rows=@(for($i=0;$i -lt 3;$i++){if($Baseline[$i] -le 0 -or $Candidate[$i] -le 0 -or [double]::IsInfinity($Baseline[$i]) -or [double]::IsInfinity($Candidate[$i]) -or [double]::IsNaN($Baseline[$i]) -or [double]::IsNaN($Candidate[$i])){throw 'Finite positive metrics required'};$row=@{repeat=$i+1;ratio=$Candidate[$i]/$Baseline[$i]};if($ClientRequestIngress){$row.client_main=$Baseline[$i];$row.client_network=$Candidate[$i]}else{$row.batching_off=$Baseline[$i];$row.batching_on=$Candidate[$i]};$row})
    return @{rows=$rows;median_paired_percent=100*(@($rows.ratio|Sort-Object)[1]-1);lower_repeats=@($rows|Where-Object {$_.ratio -lt 1}).Count;definition=$(if($ClientRequestIngress){'Median of same-repeat ratios; three coordinates reused, MAIN first, batchingFALSE. Not opposite-order reproducibility.'}else{'Median of same-repeat ratios; three coordinates reused, batchingOFF first. Not opposite-order reproducibility.'})}
}
try {
    foreach($name in @('Run-NativeRequestBatchGate.ps1','Run-OrdinaryGameplayGate.ps1','NativeGameplayEvidence.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1','WorldgenGameplayServer.ps1')){
        $tokens=$null;$errors=$null;$null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors)
        if($errors.Count){throw ($name+': '+($errors.Message-join '; '))}
    }
    $before=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    $env:JAVA_HOME='C:/Program Files/Java/jdk-25.0.4'
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    if($ClientRequestIngress){
        $selected=@{'ClientConnectionIngress263Test'=@('capturedRequestStartsWithoutMainAndNeverRevivesOldAdmissions()');'ClientConnectionAdmission263Test'=@('networkAdmissionWorksWithoutPumpingMainAndRetainsQueueBound()','respawnFenceRejectsOldContextAndDelayedOldConnectionClose()')}
        $testArgs='gradlew.bat test --tests io.github.genichimaruo.worldgenassist.client.ClientConnectionIngress263Test.capturedRequestStartsWithoutMainAndNeverRevivesOldAdmissions --tests io.github.genichimaruo.worldgenassist.client.ClientConnectionAdmission263Test.networkAdmissionWorksWithoutPumpingMainAndRetainsQueueBound --tests io.github.genichimaruo.worldgenassist.client.ClientConnectionAdmission263Test.respawnFenceRejectsOldContextAndDelayedOldConnectionClose assemble --no-daemon --console=plain'
        $null=Step 'affected-tests-build-fabric' $env:ComSpec @('/d','/c',$testArgs) 1800
        $junit=@{tests=0;failures=0;errors=0;skipped=0;methods=@()}
        foreach($entry in $selected.GetEnumerator()){
            $file=Join-Path $workspace ('build/test-results/test/TEST-io.github.genichimaruo.worldgenassist.client.'+$entry.Key+'.xml')
            if((Get-Item -LiteralPath $file).LastWriteTimeUtc -lt (Get-Item -LiteralPath $root).CreationTimeUtc){throw 'Fresh selected test XML required'}
            [xml]$xml=Get-Content -LiteralPath $file
            if((($xml.testsuite.testcase.name|Sort-Object)-join ';') -cne (($entry.Value|Sort-Object)-join ';') -or [int]$xml.testsuite.tests -ne $entry.Value.Count){throw 'Only exact three selected methods required'}
            foreach($key in @('tests','failures','errors','skipped')){$junit[$key]+=[int]$xml.testsuite.GetAttribute($key)}
            $junit.methods+=@($xml.testsuite.testcase.name);Copy-Item -LiteralPath $file -Destination $root
        }
        if($junit.tests -ne 3 -or $junit.failures -or $junit.errors -or $junit.skipped){throw 'Affected client admission methods failed'}
        $fabric=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader fabric
        if($fabric.Version -ne $version){throw 'Exact Fabric compile artifact required'}
        Copy-Item -LiteralPath $fabric.Path -Destination $root
        $artifacts+=@{loader='fabric';path=$fabric.Path;sha256=(Get-FileHash -LiteralPath $fabric.Path).Hash;version=$fabric.Version;runtime='UNRUN; native hooks not registered on Fabric'}
    }
    $priorBuild=$null
    if($ReuseBuildRoot){
        $reusePath=(Resolve-Path -LiteralPath $ReuseBuildRoot).Path
        if(-not $reusePath.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Owned closed build evidence required'}
        $priorBuild=Get-Content -LiteralPath (Join-Path $reusePath 'summary.json') -Raw|ConvertFrom-Json
        if($priorBuild.schema -ne 'worldgen-assist.native-request-batch-gate.v1' -or $priorBuild.success -or $priorBuild.native_builds -ne 2 -or $priorBuild.steps.Count -ne 3 -or ($priorBuild.steps.name-join ';') -cne 'build-forge;build-neoforge;gameplay-forge' -or @($priorBuild.steps[0..1]|Where-Object {-not $_.success -or $_.exit_code -or $_.timed_out}).Count -or $priorBuild.steps[2].success -or $priorBuild.steps[2].exit_code -ne 1 -or $priorBuild.steps[2].timed_out -or $priorBuild.results.Count -or $priorBuild.artifacts.Count -ne 2){throw 'Only exact closed successful two-build/failed first gameplay parent can be reused'}
        $old=[IO.File]::ReadAllLines((Join-Path $reusePath 'source-manifest-before.sha256'))
        if(($old-join "`n") -cne ([IO.File]::ReadAllLines((Join-Path $reusePath 'source-manifest-after.sha256'))-join "`n")){throw 'Original build batch source/docs were not frozen'}
        $repair=@('scripts/Run-NativeRequestBatchGate.ps1','scripts/Run-OrdinaryGameplayGate.ps1','scripts/Run-WorldgenScenario.ps1','scripts/Remote-WorldgenScenarioServer.ps1','scripts/WorldgenGameplayServer.ps1','docs/AGENTS.md','docs/NATIVE_REQUEST_BATCHING.md','docs/MIXIN_TARGETS.md','docs/WORLDGEN_PIPELINE.md','docs/TEST_RESULTS_LATEST.md','docs/VALIDATION_MATRIX.md')
        if((@($old|Where-Object {$_.Substring(66) -notin $repair})-join "`n") -cne (@($before|Where-Object {$_.Substring(66) -notin $repair})-join "`n")){throw 'Only explicit geography/reuse harness/docs repairs allowed; every production/test/build input must match'}
        $output=Get-Content -LiteralPath (Join-Path $reusePath 'gameplay-forge-out.log') -Raw
        $match=[regex]::Match($output,'ORDINARY_GAMEPLAY_GATE success=False summary=(.+)');if(-not $match.Success){throw 'Original failed child identity missing'}
        $childPath=$match.Groups[1].Value.Trim();if(-not [IO.Path]::GetFullPath($childPath).StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Owned failed child required'}
        $failed=Get-Content -LiteralPath $childPath -Raw|ConvertFrom-Json
        if($failed.success -or -not $failed.native_batch_experiment -or $failed.loader -ne 'forge' -or $failed.runtime.Count -or $failed.steps.Count -ne 5 -or @($failed.steps[0..3]|Where-Object {-not $_.success -or $_.exit_code -or $_.timed_out}).Count -or $failed.steps[4].success){throw 'Exact pre-interaction failed Forge child required'}
        $scenarioPath=Join-Path (Split-Path -Parent $childPath) 'original/scenario-result.json'
        $failure=Get-Content -LiteralPath $scenarioPath -Raw|ConvertFrom-Json
        if($failure.success -or -not $failure.cleanup_safe -or $failure.failure -cne 'Gameplay client failure: ScenarioOwnerB / scan / java.lang.IllegalStateException: no bounded natural dry landing/mining patch' -or $failure.performance.measured.Count -ne 3){throw 'Exact closed natural scan failure required; failed runtime verdict is never reused'}
        Copy-Item -LiteralPath (Join-Path $reusePath 'summary.json') -Destination (Join-Path $root 'reused-build-parent.json')
    }
    # Only native transport changed. A geography-only repair reuses exact builds.
    foreach($nativeLoader in @('forge','neoforge')){
        if(-not $priorBuild){$null=Step ('build-'+$nativeLoader) $env:ComSpec @('/d','/c',('gradlew.bat -p loaders/'+$nativeLoader+' assemble --no-daemon --console=plain')) 1800}
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $nativeLoader
        if($artifact.Version -ne $version){throw 'Exact new candidate required'}
        $hash=(Get-FileHash -LiteralPath $artifact.Path).Hash
        if($priorBuild){$row=@($priorBuild.artifacts|Where-Object loader -eq $nativeLoader);if($row.Count -ne 1 -or $row[0].version -ne $artifact.Version -or $row[0].path -ne $artifact.Path -or $row[0].sha256 -ne $hash -or (Get-FileHash -LiteralPath (Join-Path $reusePath $artifact.FileName)).Hash -ne $hash){throw 'Retained/current exact candidate build identity differs'}}
        Copy-Item -LiteralPath $artifact.Path -Destination $root
        $artifacts+=@{loader=$nativeLoader;path=$artifact.Path;sha256=$hash;version=$artifact.Version}
    }
    $lock.Dispose();$lock=$null
    # Child gates acquire the SAME shared lock; no competing runtimes.
    . (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1')
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'),[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'Original receipt analyzer syntax invalid'}
    foreach($name in @('Value','ClientReceiptSamples')){$definition=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true));if($definition.Count -ne 1){throw 'Original receipt analyzer missing'};. ([scriptblock]::Create($definition[0].Extent.Text))}
    foreach($nativeLoader in @('forge','neoforge')){
        $childArgs=@('-NoProfile','-File',(Join-Path $PSScriptRoot 'Run-OrdinaryGameplayGate.ps1'),'-Execute','-NativeBatchExperiment','-Loader',$nativeLoader)
        if($ClientRequestIngress){$childArgs+='-NativeClientRequestExperiment'}
        $output=Step ('gameplay-'+$nativeLoader) (Get-Command pwsh.exe).Source $childArgs 5400
        $match=[regex]::Match($output,'ORDINARY_GAMEPLAY_GATE success=(True|False) summary=(.+)')
        if(-not $match.Success){throw 'Child terminal summary missing'}
        $path=$match.Groups[2].Value.Trim();if(-not [IO.Path]::GetFullPath($path).StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Owned child summary required'}
        $child=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
        if(-not $child.success -or -not $child.native_batch_experiment -or -not $child.both_conditions_assisted -or $child.measured_repeats -ne 3 -or $child.loader -ne $nativeLoader -or $child.issues.Count -or $child.runtime.Count -ne 2 -or @($child.steps|Where-Object {-not $_.success -or $_.exit_code -or $_.timed_out}).Count -or -not $child.saved_gameplay.success -or $child.saved_gameplay.players.Count -ne 4 -or -not $child.lighting.success -or $child.lighting.cases.Count -ne 2){throw 'Exact closed native batching/saved child required'}
        if($ClientRequestIngress -and (-not $child.native_client_request_experiment -or $child.native_transport_experiment -ne 'client_request')){throw 'Canonical native client comparison required'}
        $pair=@{};$receipts=@{};$metrics=@{}
        foreach($runtime in $child.runtime){
            $result=Get-Content -LiteralPath $runtime.result -Raw|ConvertFrom-Json
            $expectedBatching=if($ClientRequestIngress -or $runtime.condition -eq 'original'){'false'}else{'true'}
            $candidate=@($artifacts|Where-Object loader -eq $nativeLoader)[0]
            if(-not $result.success -or -not $result.cleanup_safe -or $result.mode -ne 'assisted' -or $result.artifact_sha256 -ne $candidate.sha256 -or $result.native_request_batching -ne $expectedBatching -or $result.performance.measured.Count -ne 3 -or $runtime.measured_features.Count -ne 3 -or $runtime.receipt_coverage.Count -ne 6 -or $runtime.batch_counts.Count -ne 6){throw 'Same candidate/three-repeat/per-owner proof missing'}
            if($ClientRequestIngress){$expectedIngress=if($runtime.condition -eq 'original'){'false'}else{'true'};if($result.native_transport_experiment -ne 'client_request' -or $result.native_client_request_ingress -ne $expectedIngress -or $runtime.request_paths.Count -ne 6 -or @($runtime.batch_counts|Where-Object packets -ne 0).Count){throw 'Actual client request selection/per-owner paths/batchingFALSE missing'}}
            $receipt=ClientReceiptSamples ([pscustomobject]@{path=$runtime.result;result=$result})
            if(-not $receipt.complete -or @($receipt.coverage|Where-Object {$_.expected_chunks_per_owner -ne 3461}).Count){throw 'Exact current client receipt metric missing'}
            $pair[$runtime.condition]=$result;$receipts[$runtime.condition]=$receipt
        }
        foreach($metric in @('server_full_region_ready_ms','server_cpu_ms','tick_p95_ms','rtt_mean_ms','apply_mean_ms')){$metrics[$metric]=Paired ([double[]]$pair.original.performance.measured.$metric) ([double[]]$pair.assisted.performance.measured.$metric)}
        $metrics.client_region_receipt_ms=Paired $receipts.original.samples $receipts.assisted.samples
        $results+=@{loader=$nativeLoader;experiment=$experiment;child_summary=$path;artifact_sha256=$pair.original.artifact_sha256;metrics=$metrics;scope='Controlled marginal native transport comparison, both assisted, same candidate and coordinates. Saved proof uses each condition own terrain; no new general vanilla parity or beta claim.'}
    }
}catch{$issues.Add($_.Exception.ToString())}
finally {
    try {$after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after);if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Source/docs changed during batch')};foreach($artifact in $artifacts){if((Get-FileHash -LiteralPath $artifact.path).Hash -ne $artifact.sha256 -or (Get-FileHash -LiteralPath (Join-Path $root ([IO.Path]::GetFileName($artifact.path)))).Hash -ne $artifact.sha256){$issues.Add('Candidate artifact changed during batch')}}}catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()}
    [ordered]@{schema=$(if($ClientRequestIngress){'worldgen-assist.native-client-request-gate.v1'}else{'worldgen-assist.native-request-batch-gate.v1'});experiment=$experiment;success=($issues.Count -eq 0 -and $results.Count -eq 2);root=$root;reused_build_root=$reusePath;artifacts=$artifacts;steps=@($steps);results=$results;issues=@($issues);junit=$junit;new_junit=$(if($junit){$junit.tests}else{0});fabric_builds=@($steps|Where-Object name -eq 'affected-tests-build-fabric').Count;native_builds=@($steps|Where-Object {$_.name -match '^build-'}).Count;beta_claim=$false}|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Host "NATIVE_BATCH_GATE success=$($issues.Count -eq 0 -and $results.Count -eq 2) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count -or $results.Count -ne 2){exit 1}
