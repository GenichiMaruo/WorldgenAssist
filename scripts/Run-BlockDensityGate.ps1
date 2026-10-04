[CmdletBinding()]
param([switch]$Execute,[switch]$LocalOnly,[ValidateRange(2,32)][int]$ViewDistance=32,
    [ValidateSet('block','decisions','complete')][string]$RemoteWorkKind='block',
    [ValidateSet('ready','overlap')][string]$RemoteApplicationProfile='overlap',
    [ValidateRange(0,64)][int]$PrefetchLookahead=0,
    [ValidateSet('wide','deep')][string]$WindowProfile='wide',
    [ValidateSet('vanilla-first','assisted-first')][string]$ConditionOrder='vanilla-first',
    [ValidateSet('transport','scheduling','prefetch','admission','capacity','complete','complete-timing','complete-biomes','complete-capacity','complete-preparation','complete-peer','complete-shaping')][string]$TestProfile='transport',
    [string]$ReuseBuildEvidence,[string]$ReuseCorrectnessEvidence)
# Finish all implementation first; affected units/builds/runtime/performance are sequential.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if($ReuseCorrectnessEvidence -and (-not $ReuseBuildEvidence -or $LocalOnly)){throw 'Correctness reuse requires matching saved build evidence and the performance batch'}
$selections=@(
    'io.github.genichimaruo.worldgenassist.server.BlockDensity263Test',
    'io.github.genichimaruo.worldgenassist.server.RemoteDensityValidator263Test.acceptsExactVolumeAndRejectsChangedSample',
    'io.github.genichimaruo.worldgenassist.server.GridDensity263Test.typedGeometryAndCacheNeverCrossWorkKinds',
    'io.github.genichimaruo.worldgenassist.server.SurfaceDensity263Test',
    'io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope263Test'
)
if($RemoteWorkKind -in @('decisions','complete')){
    $selections=@(
        'io.github.genichimaruo.worldgenassist.server.TerrainDecision263Test',
        'io.github.genichimaruo.worldgenassist.server.BlockDensity263Test',
        'io.github.genichimaruo.worldgenassist.server.RemoteDensityValidator263Test.acceptsExactVolumeAndRejectsChangedSample',
        'io.github.genichimaruo.worldgenassist.server.GridDensity263Test',
        'io.github.genichimaruo.worldgenassist.server.SurfaceDensity263Test',
        'io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope263Test'
    )
}
$expectedTests=if($RemoteWorkKind -in @('decisions','complete')){13}else{10}
if($TestProfile -eq 'complete'){
    if($RemoteWorkKind -ne 'complete' -or $ReuseBuildEvidence -or $ReuseCorrectnessEvidence){throw 'Complete terrain requires fresh affected build and correctness evidence'}
    $selections+='io.github.genichimaruo.worldgenassist.server.CompleteTerrain263Test';$expectedTests=17
}
if($TestProfile -eq 'complete-timing'){
    if($RemoteWorkKind -ne 'complete' -or $RemoteApplicationProfile -ne 'overlap' -or $ReuseBuildEvidence -or $ReuseCorrectnessEvidence){throw 'Complete timing requires fresh affected build and overlap correctness evidence'}
    $selections=@('io.github.genichimaruo.worldgenassist.server.AdaptiveDemandWait263Test');$expectedTests=5
}
if($TestProfile -eq 'complete-biomes'){
    if($RemoteWorkKind -ne 'complete' -or $RemoteApplicationProfile -ne 'overlap' -or $ReuseBuildEvidence -or $ReuseCorrectnessEvidence){throw 'Complete biome changes require fresh affected build and overlap correctness evidence'}
    $selections=@('io.github.genichimaruo.worldgenassist.common.PrivateBiomeCache263Test',
        'io.github.genichimaruo.worldgenassist.common.TerrainBiomeWindow263Test',
        'io.github.genichimaruo.worldgenassist.server.BlockDensity263Test.shapeBoundsKindIsolationAndSigns',
        'io.github.genichimaruo.worldgenassist.server.TerrainDecision263Test.operatorGateCodesAndTypedCacheAreBounded');$expectedTests=6
}
if($TestProfile -eq 'complete-capacity'){
    if($RemoteWorkKind -ne 'complete' -or $RemoteApplicationProfile -ne 'overlap' -or $ReuseBuildEvidence -or $ReuseCorrectnessEvidence){throw 'Complete cache capacity requires fresh affected build and overlap correctness evidence'}
    $selections=@('io.github.genichimaruo.worldgenassist.common.PrivateBiomeCache263Test');$expectedTests=2
}
if($TestProfile -eq 'complete-preparation'){
    if($RemoteWorkKind -ne 'complete' -or $RemoteApplicationProfile -ne 'overlap' -or $ReuseBuildEvidence -or $ReuseCorrectnessEvidence){throw 'Complete prepared bypass requires fresh affected build and overlap correctness evidence'}
    $selections=@('io.github.genichimaruo.worldgenassist.server.RemotePreparation263Test.comparisonWaitsForBothInputsAndQueueCancellationReleasesAdmission',
        'io.github.genichimaruo.worldgenassist.server.RemotePreparation263Test.runningCancelledPreparationKeepsItsSlotUntilInvocationExits',
        'io.github.genichimaruo.worldgenassist.server.RemotePreparation263Test.preparedReplyBypassesBlockedPreparationWithoutReleasingItsRemoteSlot',
        'io.github.genichimaruo.worldgenassist.server.RemotePreparation263Test.preparedCancellationAndFailuresReleaseOnceAndIgnoreLateReplies');$expectedTests=4
}
$verification=if($TestProfile -in @('complete-peer','complete-shaping')){'peer'}else{'server'}
if($TestProfile -eq 'complete-peer'){
    if($RemoteWorkKind -ne 'complete' -or $RemoteApplicationProfile -ne 'overlap' -or $ReuseBuildEvidence -or $ReuseCorrectnessEvidence){throw 'Peer terrain verification requires fresh affected build and overlap correctness evidence'}
    $selections=@('io.github.genichimaruo.worldgenassist.server.CompleteTerrainPeer263Test',
        'io.github.genichimaruo.worldgenassist.server.CompleteTerrain263Test.twoSuccessfulInitialAuditsGateFurtherWorkAndPrivateDraws',
        'io.github.genichimaruo.worldgenassist.server.CompleteTerrain263Test.cancellationEpochInvalidationAndAdmissionNeverAcceptStaleAudits');$expectedTests=5
}
if($TestProfile -eq 'complete-shaping'){
    if($RemoteWorkKind -ne 'complete' -or $RemoteApplicationProfile -ne 'overlap' -or $ReuseBuildEvidence -or $ReuseCorrectnessEvidence){throw 'Structural shaping requires fresh affected build and overlap correctness evidence'}
    $selections=@('io.github.genichimaruo.worldgenassist.common.TerrainBeardifier263Test',
        'io.github.genichimaruo.worldgenassist.network.TerrainShapingPayload263Test',
        'io.github.genichimaruo.worldgenassist.server.TerrainShapingCache263Test',
        'io.github.genichimaruo.worldgenassist.server.CompleteTerrainPeer263Test.agreementRequiresDistinctAssignmentsAndEveryTerrainComponentAndFailsPromptly',
        'io.github.genichimaruo.worldgenassist.server.BlockDensity263Test.shapeBoundsKindIsolationAndSigns',
        'io.github.genichimaruo.worldgenassist.server.TerrainDecision263Test.operatorGateCodesAndTypedCacheAreBounded');$expectedTests=7
}
if($TestProfile -eq 'scheduling'){$selections=@('io.github.genichimaruo.worldgenassist.server.RemoteAwareScheduling263Test');$expectedTests=3}
if($TestProfile -eq 'prefetch'){$selections=@('io.github.genichimaruo.worldgenassist.server.GenerationPrefetch263Test');$expectedTests=6}
if($TestProfile -eq 'admission'){$selections=@('io.github.genichimaruo.worldgenassist.server.QueuedTerrainAdmission263Test','io.github.genichimaruo.worldgenassist.server.RemoteAwareScheduling263Test');$expectedTests=5}
if($TestProfile -eq 'capacity'){$selections=@('io.github.genichimaruo.worldgenassist.server.RemoteWindow263Test');$expectedTests=3}
if(-not $Execute){[ordered]@{test_selections=$selections;expected_tests=$expectedTests;forge_fragment_tests=if($TestProfile -eq 'complete'){3}elseif($TestProfile -eq 'transport'){2}else{0};builds=@('fabric','forge','neoforge');reuse_build_evidence=$ReuseBuildEvidence;test_profile=$TestProfile;prefetch_lookahead=$PrefetchLookahead;window_profile=$WindowProfile;condition_order=$ConditionOrder;remote_work_kind=$RemoteWorkKind;runtime=if($LocalOnly){'NOT_RUN'}else{"new physical server on E; two-owner Overworld correctness then matched view$ViewDistance 1 warmup + 3 measured repeats; current protocol/$RemoteWorkKind/cooperative/$WindowProfile/$RemoteApplicationProfile"};sequence='implementation complete before one batch'}|ConvertTo-Json -Depth 4;exit 0}
$root=Join-Path $workspace ('test-artifacts/block-density-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $root|Out-Null
$oldJava=$env:JAVA_HOME;$oldPath=$env:Path
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4';$env:Path="$env:JAVA_HOME\bin;$env:Path"
$pwsh=(Get-Command pwsh.exe).Source
$steps=@();$issues=[Collections.Generic.List[string]]::new();$lock=$null;$hash=$null;$junit=$null;$nativeJunit=$null;$performancePath=$null;$nativeArtifacts=@();$reusedEvidence=$null;$reusedCorrectness=$null
function Step([string]$Name,[string]$Executable,[string[]]$Arguments,[int]$Seconds){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$workspace
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
        Write-Host "BLOCK_DENSITY_STEP name=$Name status=$($record.status)";return $record
    }finally{$process.Dispose()}
}
try{
    $lock=[IO.File]::Open((Join-Path $workspace 'test-artifacts/validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    foreach($name in @('Run-CompleteTerrainGate.ps1','Run-BlockDensityGate.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1','Compare-WorldgenScenarioMatrix.ps1','Run-ConstrainedServerBenchmark.ps1')){
        $tokens=$null;$parseErrors=$null
        [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$parseErrors)
        if($parseErrors.Count){throw "$name syntax: $(($parseErrors.Message)-join '; ')"}
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }
    if($ReuseBuildEvidence){
        $priorRoot=[IO.Path]::GetFullPath($ReuseBuildEvidence)
        $evidenceBase=[IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts'))+[IO.Path]::DirectorySeparatorChar
        if(-not $priorRoot.StartsWith($evidenceBase,[StringComparison]::OrdinalIgnoreCase)){throw 'Build evidence must be a workspace evidence child'}
        $prior=Get-Content -LiteralPath (Join-Path $priorRoot 'summary.json') -Raw|ConvertFrom-Json
        if(-not $prior.success -or $prior.junit.failures -or $prior.junit.errors -or $prior.junit.skipped -or $prior.junit.tests -ne $expectedTests){throw 'Prior build/test evidence is incomplete'}
        $oldSources=@{}
        foreach($line in Get-Content -LiteralPath (Join-Path $priorRoot 'correctness/assisted/source-manifest-before.sha256')){
            if($line -match '^([0-9A-F]{64})  (src/.+|build\.gradle|settings\.gradle|gradle\.properties)$'){$oldSources[$Matches[2]]=$Matches[1]}
        }
        $currentSources=@(Get-ChildItem -LiteralPath (Join-Path $workspace 'src') -File -Recurse)
        foreach($name in @('build.gradle','settings.gradle','gradle.properties')){$currentSources+=Get-Item -LiteralPath (Join-Path $workspace $name)}
        if($currentSources.Count -ne $oldSources.Count){throw 'Source inventory changed since the saved build'}
        foreach($file in $currentSources){
            $relative=$file.FullName.Substring($workspace.Length+1).Replace('\','/')
            if($oldSources[$relative] -ne (Get-FileHash -LiteralPath $file.FullName).Hash){throw "Source changed since saved build: $relative"}
        }
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1')
        if((Get-FileHash -LiteralPath $artifact.Path).Hash -ne $prior.artifact_sha256){throw 'Saved Fabric build hash differs'}
        foreach($native in $prior.native_artifacts){
            $built=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $native.loader
            if((Get-FileHash -LiteralPath $built.Path).Hash -ne $native.sha256){throw 'Saved native build hash differs'}
        }
        $junit=$prior.junit;$nativeJunit=$prior.forge_fragment_junit
        $reusedEvidence=[ordered]@{root=$priorRoot;fabric_source_hashes_match=$true;jar_hashes_match=$true;units_and_builds_fresh=$false;scope='Only application timing, bounded job windows, condition order or prefetch configuration changes; tests/builds retain original evidence identity'}
        [IO.File]::WriteAllText((Join-Path $root 'reused-build-evidence.json'),($reusedEvidence|ConvertTo-Json -Depth 4))
        $steps+=[ordered]@{name='original-unit-build-evidence';status='REUSED';root=$priorRoot}
    }else{
    $arguments=@('/d','/c',(Join-Path $workspace 'gradlew.bat'),'test','--rerun-tasks')
    foreach($selection in $selections){$arguments+=@('--tests',$selection)}
    $arguments+='build'
    $steps+=Step 'unit-build' "$env:SystemRoot\System32\cmd.exe" $arguments 1800
    if($steps[-1].status -ne 'PASSED'){throw 'Affected unit/build failed'}
    $junit=[ordered]@{tests=0;failures=0;errors=0;skipped=0}
    foreach($class in @($selections|ForEach-Object {if($_ -match '263Test\.'){$_.Substring(0,$_.IndexOf('263Test.')+7)}else{$_}}|Sort-Object -Unique)){
        $path=Join-Path $workspace "build/test-results/test/TEST-$class.xml"
        Copy-Item -LiteralPath $path -Destination $root
        [xml]$xml=Get-Content -LiteralPath $path
        foreach($field in @('tests','failures','errors','skipped')){$junit[$field]+=[int]$xml.testsuite.GetAttribute($field)}
    }
    if($junit.tests -ne $expectedTests -or $junit.failures -or $junit.errors -or $junit.skipped){throw 'Affected JUnit evidence incomplete'}
    foreach($loader in @('forge','neoforge')){
        $nativeArgs=@('/d','/c',(Join-Path $workspace 'gradlew.bat'),'-p',(Join-Path $workspace "loaders/$loader"))
        if($loader -eq 'forge' -and $TestProfile -in @('transport','complete')){
            $nativeArgs+=@('test','--tests','io.github.genichimaruo.worldgenassist.forge.ForgeResultAssemblerTest.reassemblesOutOfOrderAndDuplicateFragments','--tests','io.github.genichimaruo.worldgenassist.forge.ForgeResultAssemblerTest.fullTerrainPackedAndFloatFragmentsRemainBoundedAndRoundTrip','build')
            if($TestProfile -eq 'complete'){$nativeArgs=$nativeArgs[0..($nativeArgs.Length-2)]+@('--tests','io.github.genichimaruo.worldgenassist.forge.ForgeResultAssemblerTest.completeTerrainVariableRawFragmentsPreserveMetadata','build')}
        }else{$nativeArgs+=@('build','-x','test')}
        $steps+=Step "$loader-build" "$env:SystemRoot\System32\cmd.exe" $nativeArgs 1800
        if($steps[-1].status -ne 'PASSED'){throw "$loader build failed"}
        if($loader -eq 'forge' -and $TestProfile -in @('transport','complete')){
            $nativeTestPath=Join-Path $workspace 'loaders/forge/build/test-results/test/TEST-io.github.genichimaruo.worldgenassist.forge.ForgeResultAssemblerTest.xml'
            Copy-Item -LiteralPath $nativeTestPath -Destination $root
            [xml]$nativeXml=Get-Content -LiteralPath $nativeTestPath
            $nativeJunit=[ordered]@{}
            foreach($field in @('tests','failures','errors','skipped')){$nativeJunit[$field]=[int]$nativeXml.testsuite.GetAttribute($field)}
            $expectedNative=if($TestProfile -eq 'complete'){3}else{2}
            if($nativeJunit.tests -ne $expectedNative -or $nativeJunit.failures -or $nativeJunit.errors -or $nativeJunit.skipped){throw 'Affected Forge fragment evidence incomplete'}
        }
    }
    }
    $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1')
    $hash=(Get-FileHash -LiteralPath $artifact.Path).Hash
    Copy-Item -LiteralPath $artifact.Path -Destination $root
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach($loader in @('forge','neoforge')){
        $native=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $loader
        $zip=[IO.Compression.ZipFile]::OpenRead($native.Path)
        try{
            $descriptor=if($loader -eq 'forge'){'META-INF/mods.toml'}else{'META-INF/neoforge.mods.toml'}
            $reader=[IO.StreamReader]::new($zip.GetEntry($descriptor).Open())
            try{$metadata=$reader.ReadToEnd()}finally{$reader.Dispose()}
            if($metadata -notmatch ('(?m)^version="'+[regex]::Escape($artifact.Version)+'"\s*$')){throw 'Native version mismatch'}
        }finally{$zip.Dispose()}
        Copy-Item -LiteralPath $native.Path -Destination $root
        $nativeArtifacts+=[ordered]@{loader=$loader;sha256=(Get-FileHash -LiteralPath $native.Path).Hash;metadata_matches=$true}
    }
    if(-not $LocalOnly){
        if($ReuseCorrectnessEvidence){
            $savedRoot=[IO.Path]::GetFullPath($ReuseCorrectnessEvidence)
            $evidenceBase=[IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts'))+[IO.Path]::DirectorySeparatorChar
            if(-not $savedRoot.StartsWith($evidenceBase,[StringComparison]::OrdinalIgnoreCase)){throw 'Correctness evidence must be a workspace evidence child'}
            $saved=Get-Content -LiteralPath (Join-Path $savedRoot 'summary.json') -Raw|ConvertFrom-Json
            $savedWindow=if($saved.PSObject.Properties.Name -contains 'window_profile'){$saved.window_profile}else{'wide'}
            if($saved.artifact_sha256 -ne $hash -or $saved.remote_work_kind -ne $RemoteWorkKind -or
                $saved.remote_application_profile -ne $RemoteApplicationProfile -or $saved.prefetch_lookahead -ne $PrefetchLookahead -or $savedWindow -ne $WindowProfile){throw 'Saved correctness configuration/artifact differs'}
            foreach($name in @('physical-profile','correctness-vanilla','correctness-assisted','correctness-comparison')){
                if(@($saved.steps|Where-Object {$_.name -eq $name -and $_.status -eq 'PASSED'}).Count -ne 1){throw "Saved step is not passed: $name"}
            }
            $correct=Join-Path $savedRoot 'correctness'
            $pairManifest=$null
            foreach($mode in @('vanilla','assisted')){
                $directory=Join-Path $correct $mode
                $result=Get-Content -LiteralPath (Join-Path $directory 'scenario-result.json') -Raw|ConvertFrom-Json
                if(-not $result.success -or -not $result.cleanup_safe -or -not $result.loopback_only -or $result.artifact_sha256 -ne $hash -or
                    $result.source_manifest_before_sha256 -ne $result.source_manifest_after_sha256 -or $result.mode -ne $mode -or
                    $result.players -ne 2 -or $result.dimension -ne 'overworld' -or $result.validation_cells -ne 8 -or
                    $result.prefetch_lookahead -ne $PrefetchLookahead -or $result.remote_work_kind -ne $RemoteWorkKind -or
                    $result.remote_application_profile -ne $RemoteApplicationProfile){throw 'Saved correctness result differs or is incomplete'}
                if($pairManifest -and $pairManifest -ne $result.source_manifest_after_sha256){throw 'Saved pair source manifests differ'}
                $pairManifest=$result.source_manifest_after_sha256
                $before=Get-Content -LiteralPath (Join-Path $directory 'source-manifest-before.sha256') -Raw
                $after=Get-Content -LiteralPath (Join-Path $directory 'source-manifest-after.sha256') -Raw
                if($before -ne $after){throw 'Saved scenario changed source during runtime'}
                foreach($line in ($after -split '\r?\n')){
                    if(-not $line){continue};if($line -notmatch '^([0-9A-F]{64})  (.+)$'){throw 'Invalid saved manifest'}
                    $expected=$Matches[1];$path=[IO.Path]::GetFullPath((Join-Path $workspace $Matches[2]))
                    if(-not $path.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
                        (Get-FileHash -LiteralPath $path).Hash -ne $expected){throw 'Current source/harness differs from saved correctness'}
                }
            }
            $reusedCorrectness=[ordered]@{root=$savedRoot;source_harness_and_jar_match=$true;runtime_fresh=$false;original_gate_success=$saved.success;original_issues=$saved.issues}
            $steps+=[ordered]@{name='original-correctness-evidence';status='REUSED';root=$savedRoot}
        }else{
        $steps+=Step 'physical-profile' $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Test-PhysicalServerProfile.ps1')) 60
        if($steps[-1].status -ne 'PASSED'){throw 'Physical server profile failed'}
        $correct=Join-Path $root 'correctness';New-Item -ItemType Directory -Force -Path $correct|Out-Null
        $cases=@()
        foreach($mode in @('vanilla','assisted')){
            $directory=Join-Path $correct $mode
            $steps+=Step "correctness-$mode" $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-RemoteHost','gen1c@100.103.102.109','-RemoteRoot','E:/WorldgenAssist/port26.3','-Dimension','overworld','-Mode',$mode,'-Players','2','-Purpose','correctness','-CacheEntries','128','-Prediction','false','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','0','-ServerJvmProcessors','0','-CorrectnessDemandWaitMs','100','-NoiseBackend','cooperative','-WindowProfile',$WindowProfile,'-RemoteApplicationProfile',$RemoteApplicationProfile,'-PrefetchLookahead',[string]$PrefetchLookahead,'-RemoteWorkKind',$RemoteWorkKind,'-CompleteVerification',$verification,'-OutputRoot',$directory) 1200
            if($steps[-1].status -ne 'PASSED'){throw "Affected runtime failed: $mode"}
            $result=Get-Content -LiteralPath (Join-Path $directory 'scenario-result.json') -Raw|ConvertFrom-Json
            if(-not $result.success -or -not $result.cleanup_safe -or $result.artifact_sha256 -ne $hash){throw 'Runtime cleanup/artifact mismatch'}
            $cases+=[ordered]@{id="correctness-overworld-$mode-p2-cache";dimension='overworld';mode=$mode;players=2;purpose='correctness';cache_entries=128;prediction=$false;validation_cells=8}
        }
        [IO.File]::WriteAllText((Join-Path $correct 'matrix-plan.json'),([ordered]@{runtime_and_performance_cases=$cases}|ConvertTo-Json -Depth 5))
        $steps+=Step 'correctness-comparison' $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'),'-RunRoot',$correct) 300
        }
        $comparison=Get-Content -LiteralPath (Join-Path $correct 'analysis/scenario-matrix-analysis.json') -Raw|ConvertFrom-Json
        if($comparison.status -ne 'COMPLETE' -or $comparison.issues.Count -ne 0 -or
            $comparison.correctness_pairs.Count -ne 1 -or $comparison.correctness_pairs[0].status -ne 'PASS'){throw 'Correctness comparison incomplete'}
        $log=Get-Content -LiteralPath (Join-Path $correct 'assisted/remote-evidence/latest.log') -Raw
        if($TestProfile -eq 'prefetch' -and $log -notmatch 'scheduler\.summary .*task_hints=[1-9][0-9]*'){
            throw 'Runtime did not exercise early Minecraft terrain-task observation'
        }
        # Fresh prefetch implementation must exercise its new dispatch route.
        # Configuration-only reuse already proved that route with the exact JAR;
        # a different ordering may legitimately prefer loaded/stage hints instead.
        if($TestProfile -eq 'prefetch' -and -not $ReuseBuildEvidence -and
            ($log -notmatch 'scheduler\.summary .*dependency_hints=[1-9][0-9]*' -or
            $log -notmatch 'job\.sent .*source=prefetch hint=task_dependency candidate_age_ms=')){
            throw 'Runtime did not observe and actually dispatch an existing task terrain dependency'
        }
        if($TestProfile -eq 'scheduling' -and $log -notmatch 'backend\.cooperative_summary scheduler=fork_join_remote_ready reordered=[1-9][0-9]*'){
            throw 'Runtime did not exercise remote-aware cooperative queue reordering'
        }
        if($TestProfile -eq 'scheduling' -and $log -notmatch 'backend\.enabled stage=noise mode=cooperative scheduler=fork_join_remote_ready workers=2 queue_per_worker=16 queue_capacity=32'){
            throw 'Runtime cooperative workers/admission differ from scheduling candidate'
        }
        $applied=[Collections.Generic.HashSet[string]]::new()
        $usePattern=if($RemoteWorkKind -eq 'complete'){'job\.complete id=(\S+) .*work_kind=COMPLETE_TERRAIN .*decision_samples=(\d+)'}elseif($RemoteWorkKind -eq 'decisions'){'job\.complete id=(\S+) .*work_kind=TERRAIN_DECISIONS_AND_SURFACE .*decision_samples=(\d+)'}else{'job\.complete id=(\S+) .*work_kind=BLOCK_DENSITY_AND_SURFACE remote_samples=(\d+)'}
        if($RemoteWorkKind -eq 'complete' -and ($log -notmatch 'job\.full_terrain_accepted .*audited=true' -or $log -notmatch 'job\.full_terrain_accepted .*audited=false')){throw 'Complete terrain must exercise independent full audits and subsequent accepted unaudited chunks'}
        if($RemoteWorkKind -eq 'complete' -and $log -match 'job\.full_terrain_apply_rejected|Independent whole-terrain audit mismatch'){throw 'Complete terrain acceptance or application failed'}
        foreach($entry in [regex]::Matches($log,$usePattern)){if([long]$entry.Groups[2].Value -ge 98304){[void]$applied.Add($entry.Groups[1].Value)}}
        if($TestProfile -in @('admission','capacity')){
            $stageJobs=@([regex]::Matches($log,'job\.sent id=(\S+) .*source=prefetch hint=terrain_stage candidate_age_ms=')|ForEach-Object {$_.Groups[1].Value})
            $stageApplied=@($stageJobs|Where-Object {$applied.Contains($_)})
            if($stageJobs.Count -eq 0 -or $stageApplied.Count -eq 0){throw 'Actual terrain-stage registration and full validated application were not exercised'}
            [IO.File]::WriteAllText((Join-Path $root 'terrain-stage-admission-use.json'),([ordered]@{sent=$stageJobs.Count;applied=$stageApplied.Count;nonblocking=$true;source='actual-stage snapshot via existing server batch dispatcher'}|ConvertTo-Json))
        }
        if($TestProfile -eq 'capacity' -and $log -notmatch 'scheduler\.summary .*capacity_skips=[1-9][0-9]*'){
            throw 'Runtime did not exercise capacity-based refill skips'
        }
        $owners=Get-Content -LiteralPath (Join-Path $correct 'assisted/remote-evidence/owner-map.json') -Raw|ConvertFrom-Json
        $use=@()
        $peerUse=@()
        $peerApplications=@([regex]::Matches($log,'job\.peer_terrain_applied id=(\S+) peer_owner=(\S+)'))
        if($verification -eq 'peer' -and $log -match 'peer_terrain_mismatch|Independent peer whole-terrain mismatch|worker\.quarantine'){throw 'Peer verification rejected terrain or quarantined a worker'}
        foreach($owner in $owners.PSObject.Properties){
            $jobs=@([regex]::Matches($log,'job\.sent id=(\S+) .*owner='+[regex]::Escape([string]$owner.Value)+'\b')|ForEach-Object {$_.Groups[1].Value}|Where-Object {$applied.Contains($_)})
            if($jobs.Count -eq 0){throw "No actual block-density application for $($owner.Name)"}
            $use+=[ordered]@{owner=$owner.Name;block_density_jobs_applied=$jobs.Count}
            if($verification -eq 'peer'){
                $peerJobs=@($peerApplications|Where-Object {$_.Groups[1].Value -in $jobs -and $_.Groups[2].Value -ne [string]$owner.Value})
                if($peerJobs.Count -eq 0){throw "No distinct peer-verified actual terrain application for $($owner.Name)"}
                $peerUse+=[ordered]@{owner=$owner.Name;peer_verified_jobs_applied=$peerJobs.Count;whole_terrain_equal=$true}
            }
        }
        if($verification -eq 'peer'){[IO.File]::WriteAllText((Join-Path $root 'peer-terrain-use.json'),($peerUse|ConvertTo-Json))}
        if($TestProfile -eq 'complete-shaping'){
            $sent=@{};foreach($entry in [regex]::Matches($log,'job\.sent id=(\S+) chunk=(-?\d+,-?\d+) .*owner=(\S+)')){$sent[$entry.Groups[1].Value]=@{chunk=$entry.Groups[2].Value;owner=$entry.Groups[3].Value}}
            $digests=@{}
            foreach($mode in @('vanilla','assisted')){
                $result=Get-Content -LiteralPath (Join-Path $correct "$mode/scenario-result.json") -Raw|ConvertFrom-Json
                $digests[$mode]=@{};foreach($entry in $result.correctness.noise_digests){$digests[$mode][$entry.chunk]=$entry.digest}
            }
            $shapedUse=@()
            foreach($entry in [regex]::Matches($log,'job\.shaped_terrain_applied id=(\S+) pieces=(\d+) junctions=(\d+)')){
                $id=$entry.Groups[1].Value
                if(-not $applied.Contains($id) -or -not $sent.ContainsKey($id)){continue}
                $chunk=$sent[$id].chunk
                # Assisted onboarding may generate extra coordinates; require actual shaping in the shared comparison region.
                if(-not $digests.vanilla.ContainsKey($chunk) -or -not $digests.assisted.ContainsKey($chunk)){continue}
                if($digests.vanilla[$chunk] -ne $digests.assisted[$chunk]){throw "Shaped terrain differs at $chunk"}
                $shapedUse+=[ordered]@{id=$id;chunk=$chunk;owner=$sent[$id].owner;pieces=[int]$entry.Groups[2].Value;junctions=[int]$entry.Groups[3].Value;digest_equal=$true}
            }
            if($shapedUse.Count -eq 0){throw 'No nonempty shaping was actually applied in the shared vanilla/assisted comparison region'}
            [IO.File]::WriteAllText((Join-Path $root 'shaped-terrain-use.json'),([ordered]@{applied_matching=$shapedUse.Count;chunks=$shapedUse}|ConvertTo-Json -Depth 5))
        }
        [IO.File]::WriteAllText((Join-Path $root 'block-density-use.json'),($use|ConvertTo-Json))
        $lock.Dispose();$lock=$null
        $steps+=Step 'performance-pair' $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-ConstrainedServerBenchmark.ps1'),'-Execute','-PhysicalServer','-ServerFlightRecording','-ViewDistance',[string]$ViewDistance,'-MeasureFullView','-QuietRemoteTrace','-AssistedNoiseBackend','cooperative','-VanillaNoiseBackend','cooperative','-WindowProfile',$WindowProfile,'-ConditionOrder',$ConditionOrder,'-RemoteApplicationProfile',$RemoteApplicationProfile,'-PrefetchLookahead',[string]$PrefetchLookahead,'-RemoteWorkKind',$RemoteWorkKind,'-CompleteVerification',$verification) 7200
        $output=Get-Content -LiteralPath (Join-Path $root 'performance-pair-out.log') -Raw
        $match=[regex]::Match($output,'CONSTRAINED_SERVER_BENCHMARK status=COMPLETE summary=(?<path>[^\r\n]+)')
        if($steps[-1].status -ne 'PASSED' -or -not $match.Success){throw 'Performance pair incomplete'}
        $performancePath=$match.Groups['path'].Value.Trim()
        $performance=Get-Content -LiteralPath $performancePath -Raw|ConvertFrom-Json
        if($performance.status -ne 'COMPLETE' -or $performance.artifact_sha256 -ne $hash){throw 'Performance identity mismatch'}
    }
}catch{$issues.Add($_.Exception.ToString())}
finally{
    if($null -ne $lock){$lock.Dispose()}
    $env:JAVA_HOME=$oldJava;$env:Path=$oldPath
    $summary=[ordered]@{schema='worldgen-assist.block-density-gate.v1';success=($issues.Count -eq 0);local_only=[bool]$LocalOnly;test_profile=$TestProfile;prefetch_lookahead=$PrefetchLookahead;window_profile=$WindowProfile;condition_order=$ConditionOrder;remote_work_kind=$RemoteWorkKind;remote_application_profile=$RemoteApplicationProfile;complete_verification=$verification;reused_build_evidence=$reusedEvidence;reused_correctness_evidence=$reusedCorrectness;artifact_sha256=$hash;native_artifacts=$nativeArtifacts;test_selections=$selections;junit=$junit;forge_fragment_junit=$nativeJunit;steps=$steps;issues=@($issues);root=$root;performance_summary=$performancePath}
    $path=Join-Path $root 'summary.json';[IO.File]::WriteAllText($path,($summary|ConvertTo-Json -Depth 8))
    Write-Output "BLOCK_DENSITY_GATE_COMPLETE summary=$path success=$($summary.success)";if(-not $summary.success){exit 1}
}
