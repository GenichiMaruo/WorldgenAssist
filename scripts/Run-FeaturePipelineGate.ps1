[CmdletBinding()]
param([switch]$Execute,[switch]$LocalOnly,[string]$ResumeNeoOriginalRoot,[switch]$RegionalSingleWorker)
# Finish implementation first. One lock, ten affected methods, three builds,
# fresh targeted correctness, then exactly three controlled performance runs.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base=[IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts'))
$classes=@('io.github.genichimaruo.worldgenassist.server.FeatureStage263Test','io.github.genichimaruo.worldgenassist.server.DecorationDigest263Test','io.github.genichimaruo.worldgenassist.server.FixtureWeightedOrder263Test')
$functional=@(
    @{name='original';mode='vanilla';backend='off'},
    @{name='serial';mode='vanilla';backend='serial'},
    @{name='parallel';mode='vanilla';backend='parallel'},
    @{name='assisted';mode='assisted';backend='parallel'}
)
$conditions=@($functional[0],$functional[2],$functional[3])
$expectedMethods=10;$testSelectors=$classes
if($RegionalSingleWorker){
    if($ResumeNeoOriginalRoot){throw 'One-worker profile requires fresh affected evidence'}
    $classes=@('io.github.genichimaruo.worldgenassist.server.FeatureStage263Test')
    $featureClass=$classes[0]
    $testSelectors=@("${featureClass}.stockBurstAndAccessFootprintMatchTheAdmissionReservation","${featureClass}.disjointWorkOverlapsWhileConflictingPendingJobsKeepFifo")
    $expectedMethods=2
    $functional=@(@{name='original';mode='vanilla';backend='off'},@{name='assisted';mode='assisted';backend='guarded'})
    $conditions=@(@{name='parallel';mode='assisted';backend='parallel'},@{name='guarded';mode='assisted';backend='guarded'})
}
if(-not $Execute){
    [ordered]@{tests=$testSelectors;expected_methods=$expectedMethods;builds=@('fabric','forge','neoforge');physical_correctness=$functional;feature_fixture='Participants precede demand, frozen ticks,1682 original terrains before recorded/replayed1458 features before1458 light initializations, all1250 snapshots before original SPAWN; unchanged882 compared. Controlled phases, not ordinary mixed-stage coverage';native_correctness='Same phased recorded/replayed two-owner original/assisted pair per native loader, view4 functional only. Neo monster-room list order fixed only inside scoped feature body, original weights/random selector preserved';performance=$conditions;view_distance=32;warmups=1;repeats=3;remote_host='gen1c@100.103.102.109';remote_root='E:/WorldgenAssist/port26.3';cpu_limits=0;sequence='All implementation before one sequential batch; correctness failure prevents performance'}|ConvertTo-Json -Depth 6
    exit 0
}
New-Item -ItemType Directory -Force -Path $base|Out-Null
$root=Join-Path $base ('feature-pipeline-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$pwsh=(Get-Command pwsh.exe).Source
$savedJava=$env:JAVA_HOME;$savedPath=$env:Path
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4';$env:Path="$env:JAVA_HOME\bin;$env:Path"
$steps=[Collections.Generic.List[object]]::new();$issues=[Collections.Generic.List[string]]::new()
$savedMods=[Collections.Generic.List[object]]::new();$deployed=[Collections.Generic.List[string]]::new()
$artifacts=@();$junit=$null;$before=$null;$lock=$null;$performance=@();$use=@();$resume=$null
. (Join-Path $PSScriptRoot 'FeatureIntervalEvidence.ps1')
. (Join-Path $PSScriptRoot 'FeatureFixtureEvidence.ps1')
function Evidence-Child([string]$Path){
    $resolved=[IO.Path]::GetFullPath($Path)
    if(-not $resolved.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Expected a workspace evidence child'}
    return $resolved
}
function Manifest {
    $files=@()
    foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -Recurse -File}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    return @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Step([string]$Name,[string]$Executable,[string[]]$Arguments,[int]$Seconds=1800){
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
        $record=[ordered]@{name=$Name;success=($finished -and $process.ExitCode -eq 0);exit_code=$process.ExitCode;timed_out=(-not $finished)}
        $steps.Add($record);Write-Host "FEATURE_PIPELINE_STEP name=$Name success=$($record.success)"
        if(-not $record.success){throw "Step failed: $Name (see retained logs)"}
    }finally{$process.Dispose()}
}
function Script-Step([string]$Name,[string]$Script,[string[]]$Arguments,[int]$Seconds=1800){
    Step $Name $pwsh (@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot $Script))+$Arguments) $Seconds
}
function Check-Physical([string]$Case,[object]$Condition,[bool]$Digest){
    $result=Get-Content -LiteralPath (Join-Path $Case 'scenario-result.json') -Raw|ConvertFrom-Json
    if(-not $result.success -or -not $result.cleanup_safe -or -not $result.loopback_only -or $result.artifact_sha256 -ne $artifacts[0].sha256 -or $result.feature_backend -ne $Condition.backend -or $result.mode -ne $Condition.mode -or [bool]$result.decoration_digest -ne $Digest){throw 'Physical identity/profile/cleanup mismatch'}
    if($result.server_logical_processors -ne 0 -or $result.server_jvm_processors -ne 0 -or $result.remote_work_kind -ne 'complete' -or $result.complete_verification -ne 'peer' -or $result.source_manifest_before_sha256 -ne $result.source_manifest_after_sha256){throw 'Physical authority/CPU/source profile mismatch'}
    $config=Get-Content -LiteralPath (Join-Path $Case 'remote-evidence/feature-backend-config.json') -Raw|ConvertFrom-Json
    if($config.mode -ne $Condition.backend -or [bool]$config.decoration_digest -ne $Digest -or $config.capacity -ne 128 -or $config.message_reservation -ne 18){throw 'Observed feature configuration differs'}
    if($Condition.backend -eq 'guarded' -and $config.workers -ne 1){throw 'Actual single guarded worker missing'}
    $backend=Get-Content -LiteralPath (Join-Path $Case 'remote-evidence/noise-backend-config.json') -Raw|ConvertFrom-Json
    $window=Get-Content -LiteralPath (Join-Path $Case 'remote-evidence/pipeline-window-config.json') -Raw|ConvertFrom-Json
    $application=Get-Content -LiteralPath (Join-Path $Case 'remote-evidence/remote-application-config.json') -Raw|ConvertFrom-Json
    if($backend.noise_backend -ne 'cooperative' -or $window.owner_window -ne 32 -or $window.total_window -ne 64 -or $window.lookahead -ne 0 -or $application.profile -ne 'overlap' -or $application.base_wait_ms -ne 100 -or $application.maximum_wait_ms -ne 200){throw 'Controlled terrain/window/timing profile differs'}
    if(-not $Digest){
        $scenario=Get-Content -LiteralPath (Join-Path $Case 'remote-evidence/scenario-config.json') -Raw|ConvertFrom-Json
        $region=Get-Content -LiteralPath (Join-Path $Case 'remote-evidence/measurement-region.json') -Raw|ConvertFrom-Json
        if($scenario.view_distance -ne 32 -or $region.shape -ne 'view' -or $region.radius -ne 32 -or $region.expected_chunks_per_owner -ne 3461){throw 'Actual full view32 workload differs'}
        foreach($owner in 0..1){
            $saved=@(Select-String -LiteralPath (Join-Path $Case "clients/owner-$owner/client/options.txt") -Pattern '^renderDistance:(\d+)$')
            if($saved.Count -ne 1 -or [int]$saved.Matches[0].Groups[1].Value -ne 32){throw 'Actual client view distance differs from32'}
        }
    }
    $log=Get-Content -LiteralPath (Join-Path $Case 'remote-evidence/latest.log') -Raw
    if($Condition.backend -ne 'off' -and $log -notmatch ('feature.backend mode='+$Condition.backend.ToUpperInvariant()+' workers=')){throw 'Actual scheduler constructor replacement missing'}
    if($log -match 'fixture.failed|saved_structures.failed|outside guarded stock|message reservation exceeded|Mixin apply failed|Encountered an unexpected exception|job.full_terrain_apply_rejected|peer_terrain_mismatch|worker.quarantine|Independent whole-terrain audit mismatch|Independent peer whole-terrain mismatch'){throw 'Runtime failure marker'}
    if($Digest){if(-not $result.feature_fixture){throw 'Controlled decoration fixture missing'};$null=Assert-FeatureFixture $log $result.feature_replay_sha256}
    elseif($result.feature_fixture -or $log -match 'fixture.armed|fixture.feature_started|fixture.complete'){throw 'Fixture control must be absent in performance'}
    return $result
}
function Check-Assistance([string]$Case,[bool]$Native){
    $logRoot=if($Native){$Case}else{Join-Path $Case 'remote-evidence'}
    $log=Get-Content -LiteralPath (Join-Path $logRoot 'latest.log') -Raw
    if($log -notmatch 'job.full_terrain_accepted .*audited=true' -or $log -notmatch 'job.full_terrain_accepted .*audited=false' -or $log -match 'job.full_terrain_apply_rejected|peer_terrain_mismatch|worker.quarantine|Independent whole-terrain audit mismatch|Independent peer whole-terrain mismatch'){throw 'Actual complete terrain audits/acceptance missing or rejected'}
    $required=@{};foreach($chunk in @(Get-Content -LiteralPath (Join-Path $logRoot 'decoration-required-chunks.json') -Raw|ConvertFrom-Json)){$required[$chunk]=$true}
    $sent=@{};foreach($entry in [regex]::Matches($log,'job.sent id=(\S+) chunk=(?<chunk>-?\d+,-?\d+) .*owner=(?<owner>\S+)')){$sent[$entry.Groups[1].Value]=@{chunk=$entry.Groups['chunk'].Value;owner=$entry.Groups['owner'].Value}}
    $peer=@{};foreach($entry in [regex]::Matches($log,'job.peer_terrain_applied id=(\S+) peer_owner=(\S+)')){$peer[$entry.Groups[1].Value]=$entry.Groups[2].Value}
    $shaped=@{};foreach($entry in [regex]::Matches($log,'job.shaped_terrain_applied id=(\S+) pieces=(\d+) junctions=(\d+)')){$shaped[$entry.Groups[1].Value]=$true}
    $rows=@()
    foreach($entry in [regex]::Matches($log,'job.complete id=(\S+) .*work_kind=COMPLETE_TERRAIN .*decision_samples=(\d+)')){
        $id=$entry.Groups[1].Value
        if([long]$entry.Groups[2].Value -lt 98304 -or -not $sent.ContainsKey($id) -or -not $required.ContainsKey($sent[$id].chunk)){continue}
        $rows+=[ordered]@{id=$id;chunk=$sent[$id].chunk;owner=$sent[$id].owner;peer=($peer.ContainsKey($id) -and $peer[$id] -ne $sent[$id].owner);shaped=$shaped.ContainsKey($id)}
    }
    $owners=@($rows|ForEach-Object {$_.owner}|Sort-Object -Unique)
    if($owners.Count -ne 2 -or @($rows|Where-Object shaped).Count -eq 0){throw 'Both owners and actual shared-region shaping were not exercised'}
    foreach($owner in $owners){if(@($rows|Where-Object {$_.owner -eq $owner -and $_.peer}).Count -eq 0){throw 'Distinct peer actual application missing for an owner'}}
    return [ordered]@{case=$Case;scope='Actual applications in the required digest region';owners=$owners;applied=$rows.Count;peer_applied=@($rows|Where-Object peer).Count;shaped_applied=@($rows|Where-Object shaped).Count}
}
function Check-ReusedComparison([string]$Directory){
    $comparison=Get-Content -LiteralPath (Join-Path $Directory 'summary.json') -Raw|ConvertFrom-Json
    if(-not $comparison.success -or $comparison.comparisons.Count -ne 4){throw 'Reused strict comparison incomplete'}
    foreach($stage in @('noise','decoration','saved_structures','saved_light')){
        $rows=@($comparison.comparisons|Where-Object stage -eq $stage)
        if($rows.Count -ne 1 -or $rows[0].required -ne 882 -or $rows[0].equal -ne 882 -or $rows[0].missing.Count -or $rows[0].changed.Count){throw 'Reused strict comparison does not cover every882 coordinate'}
    }
    if($comparison.parallel.conflicting_pairs -or $comparison.parallel.feature_light_conflicting_pairs -or $comparison.parallel.light_light_conflicting_pairs){throw 'Reused footprint conflict'}
}
try{
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $before=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    foreach($script in @('Run-FeaturePipelineGate.ps1','Compare-DecorationSnapshots.ps1','FeatureIntervalEvidence.ps1','FeatureFixtureEvidence.ps1','Compare-WorldgenScenarioMatrix.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1','Run-InstalledNativeLoaderScenario.ps1')){
        $tokens=$null;$errors=$null;[void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $script),[ref]$tokens,[ref]$errors)
        if($errors.Count){throw "$script syntax: $(($errors.Message)-join '; ')"}
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $script) -Destination $root
    }
    if($ResumeNeoOriginalRoot){
        if($LocalOnly){throw 'Native resume cannot be local-only'}
        $ResumeNeoOriginalRoot=Evidence-Child (Resolve-Path -LiteralPath $ResumeNeoOriginalRoot).Path
        $resume=Get-Content -LiteralPath (Join-Path $ResumeNeoOriginalRoot 'summary.json') -Raw|ConvertFrom-Json
        if($resume.success -or $resume.issues.Count -ne 1 -or $resume.issues[0] -ne 'System.Management.Automation.RuntimeException: Actual scoped Neo monster-room order/weights missing' -or $resume.performance.Count){throw 'Resume is restricted to the completed Neo original weight-marker failure'}
        $oldBefore=[IO.File]::ReadAllLines((Join-Path $ResumeNeoOriginalRoot 'source-manifest-before.sha256'))
        $oldAfter=[IO.File]::ReadAllLines((Join-Path $ResumeNeoOriginalRoot 'source-manifest-after.sha256'))
        if(($oldBefore-join "`n") -cne ($oldAfter-join "`n")){throw 'Original batch input identity changed'}
        $runnerPattern='  scripts/Run-FeaturePipelineGate.ps1$'
        if((($oldBefore|Where-Object {$_ -notmatch $runnerPattern})-join "`n") -cne (($before|Where-Object {$_ -notmatch $runnerPattern})-join "`n")){throw 'Reuse requires identical production, tests, builds and every child harness; only this coordinator may change'}
        $oldRunner=@($oldBefore|Where-Object {$_ -match $runnerPattern})
        if($oldRunner.Count -ne 1 -or $oldRunner[0].Substring(0,64) -ne (Get-FileHash -LiteralPath (Join-Path $ResumeNeoOriginalRoot 'Run-FeaturePipelineGate.ps1')).Hash){throw 'Original coordinator snapshot mismatch'}
        foreach($name in @('affected-unit-fabric-build','forge-build','neoforge-build','physical-environment','correctness-original','correctness-serial','correctness-parallel','correctness-assisted','decoration-serial','decoration-parallel','decoration-assisted','forge-original','forge-assisted','forge-decoration','neoforge-original')){
            $oldStep=@($resume.steps|Where-Object name -eq $name)
            if($oldStep.Count -ne 1 -or -not $oldStep[0].success -or $oldStep[0].timed_out -or $oldStep[0].exit_code -ne 0){throw "Original step not successful: $name"}
            $steps.Add([ordered]@{name=$name;success=$true;exit_code=0;timed_out=$false;reused_from=$ResumeNeoOriginalRoot})
        }
        $junit=$resume.junit
        foreach($class in $classes){
            [xml]$xml=Get-Content -LiteralPath (Join-Path $ResumeNeoOriginalRoot "TEST-$class.xml")
            if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Original affected tests did not pass'}
        }
        Write-Host "FEATURE_PIPELINE_REUSE root=$ResumeNeoOriginalRoot identity=unchanged_except_coordinator"
    }else{
    $args=@('/d','/c',(Join-Path $workspace 'gradlew.bat'),'test','--no-daemon')
    foreach($selector in $testSelectors){$args+=@('--tests',$selector)}
    Step 'affected-unit-fabric-build' "$env:SystemRoot\System32\cmd.exe" ($args+@('build'))
    $junit=[ordered]@{tests=0;failures=0;errors=0;skipped=0}
    foreach($class in $classes){
        $file=Join-Path $workspace "build/test-results/test/TEST-$class.xml";Copy-Item -LiteralPath $file -Destination $root
        [xml]$xml=Get-Content -LiteralPath $file
        foreach($key in @('tests','failures','errors','skipped')){$junit[$key]+=[int]$xml.testsuite.GetAttribute($key)}
    }
    if($junit.tests -ne $expectedMethods -or $junit.failures -or $junit.errors -or $junit.skipped){throw 'Affected selected-method evidence incomplete'}
    foreach($loader in @('forge','neoforge')){Step "$loader-build" "$env:SystemRoot\System32\cmd.exe" @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'-p',(Join-Path $workspace "loaders/$loader"),'build','-x','test','--no-daemon')}
    }
    if($junit.tests -ne $expectedMethods -or $junit.failures -or $junit.errors -or $junit.skipped){throw 'Affected selected-method evidence incomplete'}
    foreach($loader in @('fabric','forge','neoforge')){
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $loader
        if($resume){
            $prior=@($resume.artifacts|Where-Object loader -eq $loader)
            if($prior.Count -ne 1 -or $prior[0].version -ne $artifact.Version -or $prior[0].sha256 -ne (Get-FileHash -LiteralPath $artifact.Path).Hash -or $prior[0].sha256 -ne (Get-FileHash -LiteralPath (Join-Path $ResumeNeoOriginalRoot ([IO.Path]::GetFileName($artifact.Path)))).Hash){throw 'Original artifact identity mismatch'}
        }
        $artifacts+=[ordered]@{loader=$loader;path=$artifact.Path;sha256=(Get-FileHash -LiteralPath $artifact.Path).Hash;version=$artifact.Version}
        Copy-Item -LiteralPath $artifact.Path -Destination $root
    }
    if(-not $LocalOnly){
        # Read actual weak-host environment without changing its global settings.
        $code='$ErrorActionPreference="Stop";$env:TEMP="E:/WorldgenAssist/temp";$env:TMP=$env:TEMP;$cpu=Get-CimInstance Win32_Processor;$drive=Get-CimInstance Win32_LogicalDisk -Filter "DeviceID=''E:''";[ordered]@{machine=$env:COMPUTERNAME;cpu=@($cpu|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors);e_free_bytes=$drive.FreeSpace;runtime_exists=(Test-Path -LiteralPath "E:/WorldgenAssist/java25/bin/java.exe");root="E:/WorldgenAssist/port26.3"}|ConvertTo-Json -Depth 5'
        $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
        Step 'physical-environment' (Get-Command ssh.exe).Source @('-o','BatchMode=yes','-o','ConnectTimeout=15','gen1c@100.103.102.109','powershell -NoProfile -NonInteractive -EncodedCommand '+$encoded) 60
        $profile=Get-Content -LiteralPath (Join-Path $root 'physical-environment-out.log') -Raw|ConvertFrom-Json
        if(-not $profile.runtime_exists -or $profile.e_free_bytes -lt 20GB){throw 'E runtime/free capacity prerequisite is missing'}
        Get-CimInstance Win32_Processor|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $root 'client-cpu.json')
        $common=@('-RemoteHost','gen1c@100.103.102.109','-RemoteRoot','E:/WorldgenAssist/port26.3','-Dimension','overworld','-Players','2','-CacheEntries','128','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','0','-ServerJvmProcessors','0','-NoiseBackend','cooperative','-WindowProfile','deep','-RemoteApplicationProfile','overlap','-PrefetchLookahead','0','-RemoteWorkKind','complete','-CompleteVerification','peer')
        $functionalRoot=if($resume){$ResumeNeoOriginalRoot}else{$root}
        if(-not $resume){
        foreach($condition in $functional){
            $case=Join-Path $root ('correctness/'+$condition.name)
            $fixtureArgs=@('-FeatureFixture')
            if($condition.name -ne 'original'){$fixtureArgs+=@('-FeatureReplayFile',(Join-Path $root 'feature-replay.json'))}
            Script-Step ('correctness-'+$condition.name) 'Run-WorldgenScenario.ps1' ($common+@('-Mode',$condition.mode,'-Purpose','correctness','-Prediction','false','-CorrectnessDemandWaitMs','100','-FeatureBackend',$condition.backend,'-DecorationDigest','-OutputRoot',$case)+$fixtureArgs)
            $null=Check-Physical $case $condition $true
            if($condition.name -eq 'original'){$null=Write-FeatureReplay (Join-Path $case 'remote-evidence/latest.log') (Join-Path $root 'feature-replay.json')}
        }
        foreach($name in @($functional|Where-Object name -ne 'original'|ForEach-Object {$_.name})){
            $args=@('-BaselineRoot',(Join-Path $root 'correctness/original'),'-CandidateRoot',(Join-Path $root "correctness/$name"),'-OutputRoot',(Join-Path $root "correctness/compare-$name"))
            if($name -ne 'serial' -and -not $RegionalSingleWorker){$args+='-RequireParallel'}
            Script-Step "decoration-$name" 'Compare-DecorationSnapshots.ps1' $args 300
        }
        }else{
            foreach($condition in $functional){$null=Check-Physical (Join-Path $functionalRoot ('correctness/'+$condition.name)) $condition $true}
            foreach($name in @('serial','parallel','assisted')){Check-ReusedComparison (Join-Path $functionalRoot "correctness/compare-$name")}
            foreach($name in @('original','assisted')){
                $native=Get-Content -LiteralPath (Join-Path $functionalRoot "native/forge/$name/result.json") -Raw|ConvertFrom-Json
                if(-not $native.success -or -not $native.cleanup_safe -or -not $native.loopback_only -or $native.mod_sha256 -ne $artifacts[1].sha256){throw 'Reused Forge runtime identity/cleanup mismatch'}
            }
            Check-ReusedComparison (Join-Path $functionalRoot 'native/forge/comparison')
            $use+=Check-Assistance (Join-Path $functionalRoot 'native/forge/assisted') $true
        }
        $use+=Check-Assistance (Join-Path $functionalRoot 'correctness/assisted') $false
        $assets=(Get-Content -LiteralPath (Join-Path $functionalRoot 'correctness/assisted/assets-root.txt') -Raw).Trim()
        $assets=Evidence-Child $assets
        foreach($loader in @('forge','neoforge')){
            if($resume -and $loader -eq 'forge'){continue}
            New-Item -ItemType Directory -Force -Path (Join-Path $root "native/$loader")|Out-Null
            $installed=Evidence-Child (Join-Path $base $(if($loader -eq 'forge'){'port26.3-forge-installed'}else{'port26.3-neo-installed'}))
            $mods=Evidence-Child (Join-Path $installed 'server/mods')
            $backup=Join-Path $root "saved-server-mods/$loader";New-Item -ItemType Directory -Force -Path $backup|Out-Null
            foreach($old in Get-ChildItem -LiteralPath $mods -File -Filter 'worldgen-assist-*.jar'){
                $source=Evidence-Child $old.FullName;$target=Evidence-Child (Join-Path $backup $old.Name)
                Move-Item -LiteralPath $source -Destination $target;$savedMods.Add(@{original=$source;backup=$target})
            }
            $built=@($artifacts|Where-Object loader -eq $loader)[0]
            $target=Evidence-Child (Join-Path $mods ([IO.Path]::GetFileName($built.path)))
            Copy-Item -LiteralPath $built.path -Destination $target;$deployed.Add($target)
            foreach($condition in @($functional|Where-Object {$_.name -in @('original','assisted')})){
                $case=if($resume -and $condition.name -eq 'original'){Join-Path $ResumeNeoOriginalRoot "native/$loader/original"}else{Join-Path $root "native/$loader/$($condition.name)"}
                $fixtureArgs=@('-FeatureFixture')
                if($condition.name -ne 'original'){$fixtureArgs+=@('-FeatureReplayFile',(Join-Path $root "native/$loader/feature-replay.json"))}
                if(-not ($resume -and $condition.name -eq 'original')){Script-Step "$loader-$($condition.name)" 'Run-InstalledNativeLoaderScenario.ps1' (@('-Loader',$loader,'-Mode',$condition.mode,'-Players','2','-InstalledRoot',$installed,'-AssetsRoot',$assets,'-OutputRoot',$case,'-CompleteTerrain','-CompleteVerification','peer','-RemoteApplicationProfile','overlap','-StructuralShaping','-FeatureBackend',$condition.backend,'-DecorationDigest')+$fixtureArgs)}
                $native=Get-Content -LiteralPath (Join-Path $case 'result.json') -Raw|ConvertFrom-Json
                if(-not $native.success -or -not $native.cleanup_safe -or -not $native.loopback_only -or $native.mod_sha256 -ne $built.sha256 -or $native.feature_backend -ne $condition.backend -or -not $native.complete_terrain -or -not $native.structural_shaping -or $native.complete_verification -ne 'peer'){throw 'Native artifact/profile mismatch'}
                if($loader -eq 'neoforge'){
                    $nativeLog=Get-Content -LiteralPath (Join-Path $case 'latest.log') -Raw
                    if($nativeLog -notmatch 'fixture.monster_room_order entries=\[minecraft:skeleton:100, minecraft:spider:100, minecraft:zombie:200\] weights_preserved=true scoped_feature_only=true'){throw 'Actual scoped Neo monster-room order/weights missing'}
                }
                if($condition.name -eq 'original'){
                    if($resume){$null=Assert-FeatureFixture $nativeLog 'record'}
                    $nativeOriginalRoot=$case
                    $null=Write-FeatureReplay (Join-Path $case 'latest.log') (Join-Path $root "native/$loader/feature-replay.json")
                }
            }
            $compareArgs=@('-BaselineRoot',$nativeOriginalRoot,'-CandidateRoot',(Join-Path $root "native/$loader/assisted"),'-OutputRoot',(Join-Path $root "native/$loader/comparison"))
            if(-not $RegionalSingleWorker){$compareArgs+='-RequireParallel'}
            Script-Step "$loader-decoration" 'Compare-DecorationSnapshots.ps1' $compareArgs 300
            $use+=Check-Assistance (Join-Path $root "native/$loader/assisted") $true
        }
        foreach($condition in $conditions){
            $case=Join-Path $root ('performance/'+$condition.name)
            $recordingArgs=if($RegionalSingleWorker){@('-ServerFlightRecording')}else{@()}
            Script-Step ('performance-'+$condition.name) 'Run-WorldgenScenario.ps1' ($common+@('-Mode',$condition.mode,'-Purpose','performance','-Prediction','true','-FeatureBackend',$condition.backend,'-OutputRoot',$case,'-ViewDistance','32','-MeasureFullView','-QuietRemoteTrace','-WarmupRuns','1','-MeasuredRepeats','3')+$recordingArgs) 4200
            $result=Check-Physical $case $condition $false
            if($result.performance.warmup_runs -ne 1 -or $result.performance.measured_repeats -ne 3){throw 'Performance repetition profile differs'}
            $log=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/latest.log') -Raw
            $repeats=@()
            foreach($repeat in 1..3){
                $window=[regex]::Match($log,"(?s)CAWG_SCENARIO_MEASURED_BEGIN_$repeat\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_$repeat\b")
                if(-not $window.Success){throw 'Feature measured-window markers missing'}
                $interval=Get-FeatureIntervalEvidence $window.Groups['body'].Value
                if(-not $interval.bodies -or -not $interval.light_initializations -or $interval.conflicting_pairs -or ($condition.backend -ne 'off' -and ($interval.feature_light_conflicting_pairs -or $interval.light_light_conflicting_pairs)) -or ($condition.backend -eq 'parallel' -and -not $interval.overlapping_pairs)){throw 'Feature/light execution/independence coverage missing'}
                if($condition.backend -eq 'guarded' -and ($interval.overlapping_pairs -or $interval.peak_bodies -ne 1)){throw 'Single-worker body execution bound differs'}
                $repeats+=[ordered]@{repeat=$repeat;features=$interval}
            }
            $performance+=[ordered]@{condition=$condition.name;mode=$condition.mode;feature_backend=$condition.backend;result=(Join-Path $case 'scenario-result.json');repeats=$repeats}
        }
        $pairs=if($RegionalSingleWorker){@(@{name='one-worker';baseline='parallel';candidate='guarded'})}else{@(@{name='scheduler';baseline='original';candidate='parallel'},@{name='client';baseline='parallel';candidate='assisted'},@{name='combined';baseline='original';candidate='assisted'})}
        foreach($comparison in $pairs){
            $compareRoot=Join-Path $root ('performance/compare-'+$comparison.name);New-Item -ItemType Directory -Path $compareRoot|Out-Null
            $plan=@($conditions|Where-Object {$_.name -in @($comparison.baseline,$comparison.candidate)}|ForEach-Object {@{id=$_.name;purpose='performance';dimension='overworld';mode=$_.mode;feature_backend=$_.backend;players=2;cache_entries=128;prediction=$true;validation_cells=8}})
            @{runtime_and_performance_cases=$plan}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $compareRoot 'matrix-plan.json')
            Script-Step ('compare-'+$comparison.name) 'Compare-WorldgenScenarioMatrix.ps1' @('-RunRoot',$compareRoot,'-BaselineResultPath',(Join-Path $root "performance/$($comparison.baseline)/scenario-result.json"),'-CandidateResultPath',(Join-Path $root "performance/$($comparison.candidate)/scenario-result.json")) 300
            $analysis=Get-Content -LiteralPath (Join-Path $compareRoot 'analysis/scenario-matrix-analysis.json') -Raw|ConvertFrom-Json
            if($analysis.status -ne 'COMPLETE' -or $analysis.performance_pairs.Count -ne 1 -or $analysis.performance_pairs[0].status -ne 'COMPLETE'){throw 'Controlled comparison is incomplete'}
        }
    }
}catch{$issues.Add($_.Exception.ToString())}
finally{
    foreach($path in $deployed){try{if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath (Evidence-Child $path)}}catch{$issues.Add("Deployment cleanup: $_")}}
    foreach($entry in $savedMods){try{Move-Item -LiteralPath (Evidence-Child $entry.backup) -Destination (Evidence-Child $entry.original)}catch{$issues.Add("Fixture mod restore: $_")}}
    if($before){try{$after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after);if(($before-join "`n") -cne ($after-join "`n")){$issues.Add('Source/harness changed during batch')}}catch{$issues.Add("Final manifest: $_")}}
    if($lock){$lock.Dispose()}
    $env:JAVA_HOME=$savedJava;$env:Path=$savedPath
    $summary=[ordered]@{schema='worldgen-assist.feature-pipeline-gate.v1';success=($issues.Count -eq 0);root=$root;reuse_parent=$ResumeNeoOriginalRoot;regional_single_worker=[bool]$RegionalSingleWorker;local_only=[bool]$LocalOnly;junit=$junit;artifacts=$artifacts;steps=@($steps);assistance_use=$use;performance=$performance;issues=@($issues);beta_claim=$false;scope='Focused experimental stock Overworld scheduler; targeted pre-SPAWN decoration, saved structures and light; regional-single-worker profile has only2 affected methods and2 assisted performance conditions. Not general mod compatibility or final gameplay-state proof'}
    $summary|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $root 'summary.json') -Encoding utf8
    Write-Output "FEATURE_PIPELINE_GATE success=$($summary.success) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
