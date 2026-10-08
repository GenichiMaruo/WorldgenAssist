[CmdletBinding()]
param([switch]$Execute,[string]$SavedSafetyRoot='test-artifacts/ordinary-saved-safety-20261007-011147-936',[string]$ReuseProbeRoot,[ValidateSet('fabric','forge','neoforge')][string]$Loader='fabric',[string]$FabricGameplayRoot='test-artifacts/ordinary-gameplay-gate-20261007-230655-166',[string]$ReleasePackageRoot='test-artifacts/release-alpha8-20261008',[switch]$NativeBatchExperiment,[switch]$NativeClientRequestExperiment)
# Complete every input-driver/harness edit before this single sequential batch.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$base=Join-Path $workspace 'test-artifacts'
function Owned([string]$Path){$p=(Resolve-Path -LiteralPath $Path).Path;if(-not $p.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Owned evidence child required'};return $p}
$savedPath=Owned $SavedSafetyRoot
$reusePath=if($ReuseProbeRoot){Owned $ReuseProbeRoot}else{$null}
if($Loader -ne 'fabric' -and $reusePath){throw 'Failed Fabric input JAR reuse is not a native profile'}
$fabricGameplayPath=if($Loader -ne 'fabric'){Owned $FabricGameplayRoot}else{$null}
$releasePackagePath=Owned $ReleasePackageRoot
if($NativeBatchExperiment -and ($Loader -eq 'fabric' -or $reusePath)){throw 'Native batching is an explicit native three-repeat profile'}
if($NativeClientRequestExperiment -and -not $NativeBatchExperiment){throw 'Client request comparison requires the bounded native three-repeat profile'}
$measuredRepeats=if($NativeBatchExperiment){3}else{1}
if(-not $Execute){[ordered]@{saved_evidence=$savedPath;reused_probe_root=$reusePath;conditions=@('original','assisted');native_batch_experiment=[bool]$NativeBatchExperiment;both_conditions_assisted=[bool]$NativeBatchExperiment;loader=$Loader;view=32;players=2;warmup=1;measured=$measuredRepeats;phases=@('scan','ground','mine','place','reconnect');fresh_junit=0;mod_builds=0;test_only_jar_builds=$(if($reusePath){0}else{1});remote='gen1c@100.103.102.109';root='E:/WorldgenAssist/port26.3';scope='Exact selected-loader JAR; natural survival input, server witnesses and stopped saved NBT/voxel/light. Explicit native experiment compares SAME-JAR batching OFF/ON; otherwise remote OFF/ON.'}|ConvertTo-Json -Depth 6;exit 0}
$root=Join-Path $base ($(if($Loader -eq 'fabric'){'ordinary-gameplay-gate-'}else{'ordinary-'+$Loader+'-gameplay-gate-'})+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'));New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=[Collections.Generic.List[object]]::new();$before=@();$artifacts=@();$classpathInventory=@();$inputs=@();$players=@();$cases=@{};$lightingCenters=@{};$runtime=@();$lock=$null;$probeJar=$null;$probeSha=$null;$savedReport=$null;$lightReport=$null;$confirmation=$null
$AuthoritativeBiomeInputs=$true;$PublicationFence=$false
. (Join-Path $PSScriptRoot 'FeatureIntervalEvidence.ps1')
. (Join-Path $PSScriptRoot 'FeatureFixtureEvidence.ps1')
. (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1')
. (Join-Path $PSScriptRoot 'NativeGameplayEvidence.ps1')
function Manifest {
    $files=@();foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -File -Recurse}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Step([string]$Name,[string]$Executable,[string[]]$Arguments,[int]$Seconds=1800){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$workspace;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in $Arguments){[void]$info.ArgumentList.Add($argument)}
    $p=[Diagnostics.Process]::new();$p.StartInfo=$info
    try {
        if(-not $p.Start()){throw "Cannot start $Name"};$out=$p.StandardOutput.ReadToEndAsync();$err=$p.StandardError.ReadToEndAsync();$finished=$p.WaitForExit($Seconds*1000)
        if(-not $finished){$p.Kill($true);$p.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$out.GetAwaiter().GetResult());[IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$err.GetAwaiter().GetResult())
        $steps.Add(@{name=$Name;success=($finished -and $p.ExitCode -eq 0);exit_code=$p.ExitCode;timed_out=(-not $finished)});Write-Host "ORDINARY_GAMEPLAY_STEP name=$Name success=$($steps[-1].success)"
        if(-not $steps[-1].success){throw "Step failed: $Name; original failure/logs retained"}
    }finally{$p.Dispose()}
}
function Remote([string]$Name,[string]$Code){$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('$ErrorActionPreference="Stop";$env:TEMP="E:/WorldgenAssist/temp";$env:TMP=$env:TEMP;'+$Code));Step $Name (Get-Command ssh.exe).Source @('-o','BatchMode=yes','-o','ConnectTimeout=15','gen1c@100.103.102.109','powershell -NoProfile -NonInteractive -EncodedCommand '+$encoded) 60}
function Saved-Inventory([string]$Name,[string]$World,[string[]]$Paths){
    if($World -notmatch '^scenario-overworld-(vanilla|assisted)-p2-performance-[0-9-]+$'){throw 'Exact owned world required'}
    foreach($p in $Paths){if($p -notmatch '^(dimensions/minecraft/overworld/region/r\.-?\d+\.-?\d+\.mca|players/(data/[0-9a-f-]+\.dat|stats/[0-9a-f-]+\.json))$'){throw 'Bounded saved path required'}}
    $code='if(@(Get-CimInstance Win32_Process -Filter "Name=''java.exe''"|Where-Object {$_.CommandLine -and $_.CommandLine.Replace(''\'',''/'') -match ''E:/WorldgenAssist/port26.3/(fabric-server-launch\.jar|native-runtime/(forge|neoforge)/)''}).Count){throw "Owned server still live"};'
    $code+='$world=''E:/WorldgenAssist/port26.3/'+$World+''';$paths=@('+(($Paths|ForEach-Object {"'$_'"})-join ',')+');'
    $code+='@($paths|ForEach-Object {$path=Join-Path $world $_;$f=Get-Item -LiteralPath $path;if($f.Length -gt 128MB){throw "Input bound"};[ordered]@{relative=$_;bytes=$f.Length;sha256=(Get-FileHash -LiteralPath $path).Hash}})|ConvertTo-Json'
    Remote $Name $code
    $rows=@(Get-Content -LiteralPath (Join-Path $root "$Name-out.log") -Raw|ConvertFrom-Json)
    if($rows.Count -ne $Paths.Count -or (($rows.relative|Sort-Object)-join ';') -cne (($Paths|Sort-Object)-join ';')){throw 'Saved inventory scope differs'};return $rows
}
function Noise-Coordinates([string]$Log,[int]$Repeat=1){
    $window=[regex]::Match($Log,'(?s)CAWG_SCENARIO_MEASURED_BEGIN_'+$Repeat+'\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_'+$Repeat+'\b');if(-not $window.Success){throw 'Measured interval missing'}
    $positions=@([regex]::Matches($window.Groups['body'].Value,'stage\.complete stage=noise chunk=(-?\d+,-?\d+)\b')|ForEach-Object {$_.Groups[1].Value}|Sort-Object -Unique)
    if($positions.Count -ne 10658){throw 'Required10658 actual measured tasks missing'}
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($positions-join ';'))))
}
function Lighting-Reports {
    $reports=@();foreach($name in @('original','assisted')){$path=Join-Path $root ($name+'-saved-lighting.json');if(Test-Path -LiteralPath $path){$reports+=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json}}
    return [ordered]@{success=($reports.Count -eq 2 -and @($reports|Where-Object {-not $_.success}).Count -eq 0);cases=@($reports|ForEach-Object {$_.cases});centers=$lightingCenters;scope='Each condition recomputed separately at its actual interaction centers, using unchanged original lighting reader.'}
}
try {
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $saved=Get-Content -LiteralPath (Join-Path $savedPath 'summary.json') -Raw|ConvertFrom-Json
    if(-not $saved.success -or $saved.schema -ne 'worldgen-assist.ordinary-saved-safety.v1' -or $saved.issues.Count -or $saved.steps.Count -ne 3 -or @($saved.steps|Where-Object {-not $_.success -or $_.timed_out -or $_.exit_code}).Count -or -not $saved.players.success -or $saved.players.players.Count -ne 4 -or -not $saved.lighting.success -or $saved.lighting.cases.Count -ne 2){throw 'Exact closed saved safety proof required'}
    foreach($input in $saved.inputs){foreach($f in $input.inventory){if((Get-FileHash -LiteralPath (Join-Path $input.destination $f.relative)).Hash -ne $f.sha256){throw 'Prior saved proof input changed'}}}
    $confirmPath=Owned $saved.parent;$confirmation=Get-Content -LiteralPath (Join-Path $confirmPath 'summary.json') -Raw|ConvertFrom-Json
    if(-not $confirmation.success -or $confirmation.schema -ne 'worldgen-assist.authoritative-biome-confirmation.v1' -or $confirmation.issues.Count -or $confirmation.steps.Count -ne 4 -or @($confirmation.steps|Where-Object {-not $_.success -or $_.exit_code -or $_.timed_out}).Count){throw 'Exact closed opposite-order speed proof required'}
    $fullPath=Owned $confirmation.gate_evidence;$full=Get-Content -LiteralPath (Join-Path $fullPath 'summary.json') -Raw|ConvertFrom-Json
    if(-not $full.success -or -not $full.authoritative_biome_inputs -or $full.steps.Count -ne 16 -or @($full.steps|Where-Object {-not $_.success -or $_.exit_code -or $_.timed_out}).Count -or $full.junit.tests -ne 4 -or $full.junit.failures -or $full.junit.errors -or $full.junit.skipped){throw 'Exact unchanged4-method/3-build parent required'}
    $old=[IO.File]::ReadAllLines((Join-Path $savedPath 'source-manifest-before.sha256'));if(($old-join "`n") -cne ([IO.File]::ReadAllLines((Join-Path $savedPath 'source-manifest-after.sha256'))-join "`n")){throw 'Prior saved batch source identity differs'}
    $before=Manifest
    $allowed=@('scripts/Run-WorldgenScenario.ps1','scripts/Remote-WorldgenScenarioServer.ps1','scripts/WorldgenGameplayClient.ps1','scripts/WorldgenGameplayServer.ps1','scripts/Run-OrdinaryGameplayGate.ps1','scripts/SavedGameplayInspector263.java','scripts/gameplay-probe/GameplayProbe.java','scripts/gameplay-probe/GameplayTickMixin.java','scripts/gameplay-probe/GameplayHeldInputMixin.java','scripts/GameplaySiteInspector263.java','scripts/Run-GameplaySiteDiagnosis.ps1')
    $allowed+=@('scripts/NativeGameplayEvidence.ps1','scripts/Run-NativeOrdinaryGameplayGate.ps1','scripts/native-gameplay-probe/NativeReceiptProbe.java','scripts/native-gameplay-probe/NativeReceiptMixin.java','scripts/native-gameplay-probe/ForgeProbeMod.java','scripts/native-gameplay-probe/NeoProbeMod.java')
    $allowed+='scripts/Build-ReleasePackage.ps1'
    if($NativeBatchExperiment){
        $allowed+='scripts/Run-NativeRequestBatchGate.ps1'
        $allowed+=@('loaders/forge/src/main/java/io/github/genichimaruo/worldgenassist/forge/ForgeRemoteJobSender.java','loaders/forge/src/main/java/io/github/genichimaruo/worldgenassist/forge/ForgeClientInit.java','loaders/forge/src/main/java/io/github/genichimaruo/worldgenassist/forge/ForgeNetwork.java','loaders/neoforge/src/main/java/io/github/genichimaruo/worldgenassist/neoforge/NeoRemoteJobSender.java','loaders/neoforge/src/main/java/io/github/genichimaruo/worldgenassist/neoforge/NeoClientInit.java','loaders/neoforge/src/main/java/io/github/genichimaruo/worldgenassist/neoforge/NeoWorldgenAssist.java')
    }
    if($NativeClientRequestExperiment){
        $allowed+=@('src/client/java/io/github/genichimaruo/worldgenassist/client/ClientConnectionIngress.java','src/client/java/io/github/genichimaruo/worldgenassist/client/NativeClientRequestIngress.java','src/client/java/io/github/genichimaruo/worldgenassist/mixin/NativeClientRequestConnectionMixin263.java','src/client/java/io/github/genichimaruo/worldgenassist/mixin/NativeClientRespawnRequestMixin263.java','src/portTest/java/io/github/genichimaruo/worldgenassist/client/ClientConnectionIngress263Test.java','loaders/forge/src/main/resources/worldgen_assist.mixins.json','loaders/neoforge/src/main/resources/META-INF/neoforge.mods.toml','loaders/neoforge/src/main/resources/worldgen_assist.native_client.mixins.json')
    }
    $normalizedBefore=@($before|ForEach-Object {
        if($_.Substring(66) -eq 'gradle.properties'){
            $normalized=[IO.File]::ReadAllText((Join-Path $workspace 'gradle.properties')).Replace('mod_version=0.1.0-alpha.8+mc26.3','mod_version=0.1.0-alpha.8-dev.23+mc26.3')
            if($NativeBatchExperiment){$normalized=$normalized.Replace('mod_version=0.1.0-alpha.9-dev.1+mc26.3','mod_version=0.1.0-alpha.8-dev.23+mc26.3')}
            if($NativeClientRequestExperiment){$normalized=$normalized.Replace('mod_version=0.1.0-alpha.9-dev.2+mc26.3','mod_version=0.1.0-alpha.8-dev.23+mc26.3')}
            [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($normalized)))+'  gradle.properties'
        }else{$_}
    })
    if((@($old|Where-Object {$_.Substring(66) -notin $allowed})-join "`n") -cne (@($normalizedBefore|Where-Object {$_.Substring(66) -notin $allowed})-join "`n")){throw 'Only explicit input-driver/harness and exact release-version delta allowed; production/test/build/helpers must match'}
    [IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    foreach($path in $allowed){$copy=Join-Path $root ('source-snapshot/'+$path);New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($copy))|Out-Null;Copy-Item -LiteralPath (Join-Path $workspace $path) -Destination $copy}
    $retained=Join-Path $root 'prior-harness';New-Item -ItemType Directory -Path $retained|Out-Null
    foreach($name in @('Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1')){
        $prior=Join-Path $confirmPath $name;$row=@($old|Where-Object {$_.Substring(66) -eq 'scripts/'+$name})
        if($row.Count -ne 1 -or (Get-FileHash -LiteralPath $prior).Hash -ne $row[0].Substring(0,64)){throw 'Original changed harness identity missing'};Copy-Item -LiteralPath $prior -Destination $retained
    }
    $expected=@{'common.AuthoritativeBiomeWindow263Test'='originalWindowSurvivesWireAndPrivateRestorationWithoutSharingMutableChunks()';'network.TerrainShapingPayload263Test'='boundedShapingSurvivesMixedBatchAndInvalidLengthIsRejectedBeforeReadingBody()';'server.CompleteTerrainPeer263Test'='agreementRequiresDistinctAssignmentsAndEveryTerrainComponentAndFailsPromptly()';'server.BiomePhase263Test'='distinctPeerPhaseIsBoundedRevocableAndConsistentWithFinalReplies()'}
    foreach($entry in $expected.GetEnumerator()){$f=Join-Path $fullPath ('TEST-io.github.genichimaruo.worldgenassist.'+$entry.Key+'.xml');[xml]$x=Get-Content -LiteralPath $f;if([int]$x.testsuite.tests -ne 1 -or [int]$x.testsuite.failures -or [int]$x.testsuite.errors -or [int]$x.testsuite.skipped -or $x.testsuite.testcase.name -cne $entry.Value){throw 'Prior affected method proof differs'};Copy-Item -LiteralPath $f -Destination $root}
    foreach($comparison in @('correctness/compare-assisted','native/forge/comparison','native/neoforge/comparison')){
        $proof=Get-Content -LiteralPath (Join-Path $fullPath ($comparison+'/summary.json')) -Raw|ConvertFrom-Json;if(-not $proof.success -or $proof.comparisons.Count -ne 4){throw 'Prior strict fixture proof incomplete'}
        foreach($stage in @('noise','decoration','saved_structures','saved_light')){$rows=@($proof.comparisons|Where-Object stage -eq $stage);if($rows.Count -ne 1 -or $rows[0].required -ne 882 -or $rows[0].equal -ne 882 -or $rows[0].missing.Count -or $rows[0].changed.Count){throw 'Prior3-loader882 stage proof differs'}}
    }
    foreach($artifactLoader in @('fabric','forge','neoforge')){
        $a=@($confirmation.artifacts|Where-Object loader -eq $artifactLoader);if($a.Count -ne 1 -or $a[0].version -ne '0.1.0-alpha.8-dev.23+mc26.3' -or (Get-FileHash -LiteralPath $a[0].path).Hash -ne $a[0].sha256 -or (Get-FileHash -LiteralPath (Join-Path $confirmPath ([IO.Path]::GetFileName($a[0].path)))).Hash -ne $a[0].sha256 -or (Get-FileHash -LiteralPath (Join-Path $fullPath ([IO.Path]::GetFileName($a[0].path)))).Hash -ne $a[0].sha256){throw 'Exact original dev23 identities required'}
        if($NativeBatchExperiment){if($artifactLoader -eq $Loader){$artifacts+=Resolve-NativeBatchArtifact $a[0] ([bool]$NativeClientRequestExperiment)}else{$artifacts+=$a[0]}}else{$artifacts+=Resolve-GameplayReleaseArtifact $a[0] $releasePackagePath}
    }
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Run-FeaturePipelineGate.ps1'),[ref]$tokens,[ref]$errors);if($errors.Count){throw 'Unchanged helper syntax differs'}
    if($Loader -ne 'fabric'){$artifacts=@($artifacts|Sort-Object @{Expression={if($_.loader -eq $Loader){0}else{1}}},loader)}
    $definition=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Check-Physical'},$true));if($definition.Count -ne 1){throw 'Original physical helper missing'};. ([scriptblock]::Create($definition[0].Extent.Text))
    $classpathInventory=@(Get-Content -LiteralPath (Join-Path $confirmPath 'performance/assisted/clients/owner-0/installed-client-classpath.json') -Raw|ConvertFrom-Json)
    foreach($entry in $classpathInventory){if((Get-FileHash -LiteralPath $entry.path).Hash -ne $entry.sha256){throw 'Exact installed runtime changed'}}
    $game=@($classpathInventory|Where-Object name -eq 'original Minecraft 26.3 client');if($game.Count -ne 1 -or $game[0].sha256 -ne '4508D006323F24FA02876310C192D739AF56516EB259000AC50F0909A68C9A2D'){throw 'Original26.3 game required'}
    $classpath=(@($classpathInventory.path)+@($artifacts[0].path))-join ';';$jdk='C:/Program Files/Java/jdk-25.0.4/bin'
    $probeClasses=Join-Path $root 'probe-classes';$offlineClasses=Join-Path $root 'offline-classes';New-Item -ItemType Directory -Path $probeClasses,$offlineClasses|Out-Null
    $probeSources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'gameplay-probe') -Filter '*.java' -File|Sort-Object Name);if($probeSources.Count -ne 3){throw 'Exactly3 input driver sources required'}
    foreach($source in $probeSources){Copy-Item -LiteralPath $source.FullName -Destination $root}
    $probeJar=Join-Path $root 'worldgen-gameplay-probe.jar'
    if($Loader -ne 'fabric'){
        $nativeProbe=New-NativeGameplayProbe $Loader $fabricGameplayPath $probeClasses $probeJar $classpath $jdk
        $probeSha=$nativeProbe.sha256;$classpathInventory+=@($nativeProbe.libraries)
    }elseif($reusePath){
        $reuse=Get-Content -LiteralPath (Join-Path $reusePath 'summary.json') -Raw|ConvertFrom-Json
        if($reuse.schema -ne 'worldgen-assist.ordinary-gameplay-gate.v1' -or $reuse.success -or $reuse.saved_safety_parent -ne $savedPath -or $reuse.steps.Count -ne 4 -or ($reuse.steps.name-join ';') -cne 'compile-test-input-driver;package-test-input-driver;compile-offline-readers;physical-environment' -or @($reuse.steps[0..2]|Where-Object {-not $_.success -or $_.exit_code -or $_.timed_out}).Count -or $reuse.steps[3].success -or $reuse.steps[3].exit_code -ne 255 -or $reuse.runtime.Count -or $reuse.inputs.Count -or $reuse.issues.Count -ne 1 -or $reuse.issues[0] -notmatch 'Step failed: physical-environment' -or (Get-Content -LiteralPath (Join-Path $reusePath 'physical-environment-err.log') -Raw) -notmatch 'connect to host 100\.103\.102\.109 port 22: Connection timed out'){throw 'Only exact closed SSH-preflight failure/test JAR may be reused'}
        $reuseBefore=[IO.File]::ReadAllLines((Join-Path $reusePath 'source-manifest-before.sha256'));$reuseAfter=[IO.File]::ReadAllLines((Join-Path $reusePath 'source-manifest-after.sha256'))
        if(($reuseBefore-join "`n") -cne ($reuseAfter-join "`n") -or (@($reuseBefore|Where-Object {$_.Substring(66) -ne 'scripts/Run-OrdinaryGameplayGate.ps1'})-join "`n") -cne (@($before|Where-Object {$_.Substring(66) -ne 'scripts/Run-OrdinaryGameplayGate.ps1'})-join "`n")){throw 'Only coordinator reuse support may differ; all input driver/runtime/readers must be exact'}
        foreach($artifact in $artifacts){$prior=@($reuse.artifacts|Where-Object loader -eq $artifact.loader);if($prior.Count -ne 1 -or $prior[0].sha256 -ne $artifact.sha256){throw 'Reused production JAR identity differs'}}
        if($reuse.probe_jar -ne (Join-Path $reusePath 'worldgen-gameplay-probe.jar') -or (Get-FileHash -LiteralPath $reuse.probe_jar).Hash -ne $reuse.probe_sha256){throw 'Original successfully packaged test JAR changed'}
        foreach($source in $probeSources){if((Get-FileHash -LiteralPath (Join-Path $reusePath $source.Name)).Hash -ne (Get-FileHash -LiteralPath $source.FullName).Hash){throw 'Retained input driver source changed'}}
        Copy-Item -LiteralPath $reuse.probe_jar -Destination $probeJar;Copy-Item -LiteralPath (Join-Path $reusePath 'summary.json') -Destination (Join-Path $root 'reused-probe-parent.json')
        $probeSha=(Get-FileHash -LiteralPath $probeJar).Hash;if($probeSha -ne $reuse.probe_sha256){throw 'Reused test JAR copy differs'}
        Write-Host 'ORDINARY_GAMEPLAY_REUSE test_input_driver_build=2 runtime_verdict=0'
    }else{
        Step 'compile-test-input-driver' (Join-Path $jdk 'javac.exe') (@('-encoding','UTF-8','-proc:none','-cp',$classpath,'-d',$probeClasses)+@($probeSources.FullName)) 120
        @{schemaVersion=1;id='worldgen-gameplay-probe';version='1.0.0';name='Unshipped owned gameplay evidence';environment='client';mixins=@('gameplay-probe.mixins.json');depends=@{fabricloader='=0.19.5';minecraft='=26.3';java='>=25'}}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $probeClasses 'fabric.mod.json') -Encoding utf8NoBOM
        @{required=$true;minVersion='0.8';package='io.github.genichimaruo.worldgenassist.probe.mixin';compatibilityLevel='JAVA_25';client=@('GameplayTickMixin','GameplayHeldInputMixin');injectors=@{defaultRequire=1}}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $probeClasses 'gameplay-probe.mixins.json') -Encoding utf8NoBOM
        Step 'package-test-input-driver' (Join-Path $jdk 'jar.exe') @('--create','--file',$probeJar,'-C',$probeClasses,'.') 60;$probeSha=(Get-FileHash -LiteralPath $probeJar).Hash
    }
    $readerXml=Join-Path $savedPath 'TEST-io.github.genichimaruo.worldgenassist.server.SavedLighting263Test.xml';[xml]$x=Get-Content -LiteralPath $readerXml
    if([int]$x.testsuite.tests -ne 1 -or [int]$x.testsuite.failures -or [int]$x.testsuite.errors -or [int]$x.testsuite.skipped -or $x.testsuite.testcase.name -cne 'savedValuesKeepMissingSkySemanticsAndCorruptNibblesWithoutRepair()'){throw 'Exact unchanged one-method original lighting-reader proof required'};Copy-Item -LiteralPath $readerXml -Destination $root
    $readerSources=@((Join-Path $PSScriptRoot 'SavedGameplayInspector263.java'),(Join-Path $workspace 'src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightView263.java'),(Join-Path $workspace 'src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightingInspector263.java'))
    foreach($source in $readerSources){Copy-Item -LiteralPath $source -Destination $root};Step 'compile-offline-readers' (Join-Path $jdk 'javac.exe') (@('-encoding','UTF-8','-proc:none','-cp',$classpath,'-d',$offlineClasses)+$readerSources) 120
    Remote 'physical-environment' '$cpu=Get-CimInstance Win32_Processor;$drive=Get-CimInstance Win32_LogicalDisk -Filter "DeviceID=''E:''";[ordered]@{machine=$env:COMPUTERNAME;cpu=@($cpu|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors);e_free_bytes=$drive.FreeSpace;runtime_exists=(Test-Path -LiteralPath "E:/WorldgenAssist/java25/bin/java.exe");root="E:/WorldgenAssist/port26.3"}|ConvertTo-Json -Depth 5'
    $observed=Get-Content -LiteralPath (Join-Path $root 'physical-environment-out.log') -Raw|ConvertFrom-Json;$oldEnv=Get-Content -LiteralPath (Join-Path $confirmPath 'physical-environment-out.log') -Raw|ConvertFrom-Json
    if(-not $observed.runtime_exists -or $observed.e_free_bytes -lt 20GB -or $observed.machine -ne $oldEnv.machine -or ($observed.cpu|ConvertTo-Json -Compress) -cne ($oldEnv.cpu|ConvertTo-Json -Compress)){throw 'Exact weak E-host prerequisite differs'}
    Get-CimInstance Win32_Processor|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $root 'client-cpu.json');if(([IO.File]::ReadAllText((Join-Path $root 'client-cpu.json')).Trim()) -cne ([IO.File]::ReadAllText((Join-Path $confirmPath 'client-cpu.json')).Trim())){throw 'Same stronger client PC required'}
    $oldLog=Get-Content -LiteralPath (Join-Path $confirmPath 'performance/original/remote-evidence/latest.log') -Raw;$coordinateHash=Noise-Coordinates $oldLog
    $common=@('-RemoteHost','gen1c@100.103.102.109','-RemoteRoot','E:/WorldgenAssist/port26.3','-Dimension','overworld','-Players','2','-CacheEntries','128','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','0','-ServerJvmProcessors','0','-NoiseBackend','cooperative','-WindowProfile','deep','-RemoteApplicationProfile','overlap','-PrefetchLookahead','0','-RemoteWorkKind','complete','-CompleteVerification','peer','-AuthoritativeBiomes','demand','-Purpose','performance','-Prediction','true','-ViewDistance','32','-MeasureFullView','-QuietRemoteTrace','-WarmupRuns','1','-MeasuredRepeats','1','-GameplayProbeJar',$probeJar,'-GameplayProbeSha256',$probeSha)
    $common+=@('-Loader',$Loader)
    $conditions=@(@{name='original';mode='vanilla';backend='parallel'},@{name='assisted';mode='assisted';backend='parallel'})
    if($NativeBatchExperiment){$repeatIndex=[Array]::IndexOf($common,'-MeasuredRepeats');$common[$repeatIndex+1]='3';$common+='-NativeBatchExperiment';$conditions=@(@{name='original';mode='assisted';backend='parallel';batching='false'},@{name='assisted';mode='assisted';backend='parallel';batching='true'})}
    if($NativeClientRequestExperiment){$common+='-NativeClientRequestExperiment';$conditions=@(@{name='original';mode='assisted';backend='parallel';batching='false';ingress='false'},@{name='assisted';mode='assisted';backend='parallel';batching='false';ingress='true'})}
    foreach($condition in $conditions){
        $conditionArgs=if($NativeBatchExperiment){@('-NativeRequestBatching',$condition.batching)}else{@()}
        if($NativeClientRequestExperiment){$conditionArgs+=@('-NativeClientRequestIngress',$condition.ingress)}
        $case=Join-Path $root $condition.name;Step ('runtime-'+$condition.name) (Get-Command pwsh.exe).Source (@('-NoProfile','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'))+$common+$conditionArgs+@('-Mode',$condition.mode,'-FeatureBackend','parallel','-OutputRoot',$case)) 1800
        $result=Check-Physical $case $condition $false
        if($result.loader -ne $Loader){throw 'Actual client/server loader differs'}
        if($Loader -ne 'fabric'){
            $nativeProfile=Get-Content -LiteralPath (Join-Path $case 'native-runtime.json') -Raw|ConvertFrom-Json
            $runtimeLabel=if($Loader -eq 'neoforge'){$nativeProfile.manifest_sha256.Substring(0,16)}else{$nativeProfile.manifest_sha256}
            if($NativeBatchExperiment){$runtimeLabel=$nativeProfile.manifest_sha256.Substring(0,16)+'-'+$artifacts[0].sha256.Substring(0,12);if($nativeProfile.artifact_sha256 -ne $artifacts[0].sha256){throw 'Native candidate root artifact differs'}}
            $expectedNativeRoot='E:/WorldgenAssist/port26.3/native-runtime/'+$Loader+'/'+$runtimeLabel
            if($nativeProfile.loader -ne $Loader -or $result.native_runtime.sha256 -ne $nativeProfile.manifest_sha256 -or $nativeProfile.root -cne $expectedNativeRoot){throw 'Actual bounded E-native runtime identity differs'}
            $identity=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/native-runtime-identity.json') -Raw|ConvertFrom-Json
            if(-not $identity.before_after_equal -or $identity.loader -ne $Loader -or $identity.manifest_sha256 -ne $nativeProfile.manifest_sha256 -or $identity.files -ne $nativeProfile.files){throw 'Native server before/after identity missing'}
            if($condition.name -eq 'original'){$nativeOriginalHash=$nativeProfile.manifest_sha256}elseif($nativeProfile.manifest_sha256 -ne $nativeOriginalHash){throw 'Native runtime differs between conditions'}
        }
        if(-not $result.gameplay_probe -or $result.gameplay_probe_sha256 -ne $probeSha -or $result.client_worker_threads -ne 4 -or $result.performance.warmup_runs -ne 1 -or $result.performance.measured_repeats -ne $measuredRepeats -or $result.performance.measured.Count -ne $measuredRepeats){throw 'Exact current ordinary gameplay workload differs'}
        foreach($measurement in $result.performance.measured){if($measurement.completed_tasks -ne 10658 -or $measurement.failed_tasks -or $measurement.timeouts){throw 'Exact measured task/error scope differs'}}
        $log=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/latest.log') -Raw;$gameLog=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/minecraft-latest.log') -Raw
        if((Noise-Coordinates $log) -ne $coordinateHash -or $gameLog -notmatch 'Stopping server' -or $gameLog -notmatch 'Saving worlds' -or $log -match 'fixture.player_paused|job.timeout'){throw 'Equal ordinary coordinates/clean stop required'}
        $intervals=@();$receiptCoverage=@();$batchCounts=@();$requestPaths=@()
        foreach($repeat in 1..$measuredRepeats){
            $window=[regex]::Match($log,'(?s)CAWG_SCENARIO_MEASURED_BEGIN_'+$repeat+'\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_'+$repeat+'\b').Groups['body'].Value
            $repeatHash=Noise-Coordinates $log $repeat
            if($repeatHash -ne (Noise-Coordinates $oldLog $repeat)){throw 'Exact same repeat coordinate set differs'}
            $interval=Get-FeatureIntervalEvidence $window
            if($interval.bodies -ne 10082 -or $interval.light_initializations -ne 10082 -or $interval.peak_bodies -ne 2 -or $interval.conflicting_pairs -or $interval.feature_light_conflicting_pairs -or $interval.light_light_conflicting_pairs){throw 'Actual feature/light workload or ownership differs'}
            $intervals+=@{repeat=$repeat;coordinate_sha256=$repeatHash;evidence=$interval}
            if($condition.mode -eq 'assisted' -and ($log -notmatch 'job.full_terrain_accepted .*audited=true' -or $log -notmatch 'job.full_terrain_accepted .*audited=false' -or $window -notmatch 'job.peer_terrain_applied' -or $window -notmatch 'job.authoritative_biomes_applied')){throw 'Actual independent peer/audit/input use missing'}
            foreach($owner in 0..1){
                $clientLog=Get-Content -LiteralPath (Join-Path $case "clients/owner-$owner/client/logs/latest.log") -Raw
                $allReceipts=@([regex]::Matches($clientLog,'benchmark\.chunk_received repeat='+$repeat+' chunk=(-?\d+,-?\d+)\b')|ForEach-Object {$_.Groups[1].Value}|Sort-Object -Unique)
                $sign=if($owner -eq 0){1}else{-1};$centerX=$sign*(1000+(1+$repeat)*256);$centerZ=$sign*(-2000-(1+$repeat)*256);$expected=@{}
                foreach($offset in @(Get-WorldgenMeasurementOffsets 32 'view')){$expected[([string]($centerX+$offset.x)+','+([string]($centerZ+$offset.z)))]=$true}
                $receipts=@($allReceipts|Where-Object {$expected.ContainsKey($_)})
                if($expected.Count -ne 3461 -or $receipts.Count -ne 3461){throw 'Actual targeted full view32 owner receipt proof differs'}
                $receiptCoverage+=@{repeat=$repeat;owner=$owner;required=3461;received=$receipts.Count;extra_received=$allReceipts.Count-$receipts.Count}
                if($NativeBatchExperiment){
                    $uuid=if($owner -eq 0){'0557a034-0101-3132-9f82-f0d4761d4b04'}else{'c7afee9d-8411-38e2-8537-1c157fc2c41b'}
                    $batches=@([regex]::Matches($window,'jobs\.batch_sent owner='+$uuid+' count=(\d+)\b'))
                    if(($condition.batching -eq 'false' -and $batches.Count) -or ($condition.batching -eq 'true' -and -not $batches.Count)){throw 'Actual native per-owner batch selection differs'}
                    foreach($batch in $batches){if([int]$batch.Groups[1].Value -lt 2 -or [int]$batch.Groups[1].Value -gt 4){throw 'Actual native batch exceeds2..4 jobs'}}
                    $batchCounts+=@{repeat=$repeat;owner=$owner;packets=$batches.Count;jobs=@($batches|ForEach-Object {[int]$_.Groups[1].Value})}
                    if($NativeClientRequestExperiment){
                        $sent=@{};foreach($job in [regex]::Matches($window,'job\.sent id=(\S+) .*owner='+$uuid+'\b')){$sent[$job.Groups[1].Value]=$true}
                        $dispatch=@([regex]::Matches($clientLog,'job\.client_dispatch id=(\S+) worker_queue_ms=([0-9.]+) .*request_path=(main|network)\b')|Where-Object {$sent.ContainsKey($_.Groups[1].Value)})
                        $expectedPath=if($condition.ingress -eq 'true'){'network'}else{'main'}
                        if(-not $dispatch.Count -or @($dispatch|Where-Object {$_.Groups[3].Value -ne $expectedPath}).Count){throw 'Actual measured per-owner native request path differs'}
                        $queue=@($dispatch|ForEach-Object {[double]::Parse($_.Groups[2].Value,[Globalization.CultureInfo]::InvariantCulture)})
                        $requestPaths+=@{repeat=$repeat;owner=$owner;path=$expectedPath;sent=$sent.Count;matched_dispatches=$dispatch.Count;worker_queue_mean_ms=($queue|Measure-Object -Average).Average}
                    }
                }
            }
        }
        if($NativeBatchExperiment){$batchConfig=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/native-batching-config.json') -Raw|ConvertFrom-Json;if(-not $batchConfig.experiment -or $batchConfig.batching -ne $condition.batching -or $batchConfig.artifact_sha256 -ne $artifacts[0].sha256 -or $batchConfig.native_channel -ne 6 -or $batchConfig.maximum_jobs -ne 4 -or -not $result.native_batch_experiment -or $result.native_request_batching -ne $condition.batching){throw 'Actual native batch launch selection differs'}}
        if($NativeClientRequestExperiment){
            if(-not $result.native_client_request_experiment -or $result.native_transport_experiment -ne 'client_request' -or $result.native_client_request_ingress -ne $condition.ingress){throw 'Canonical client ingress experiment identity missing'}
            $lifetime=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/native-client-lifetime.json') -Raw|ConvertFrom-Json
            if(-not $lifetime.success -or $lifetime.nonce -ne $result.gameplay_nonce -or $lifetime.after_measured_repeats -ne 3 -or $lifetime.performance_sample -or -not $lifetime.original_dimension_commands -or $lifetime.witnesses.Count -ne 4){throw 'Post-measurement original dimension lifetime proof missing'}
            foreach($owner in 0..1){
                $clientLog=Get-Content -LiteralPath (Join-Path $case "clients/owner-$owner/client/logs/latest.log") -Raw
                $launch=Get-Content -LiteralPath (Join-Path $case "clients/owner-$owner/native-client-request-config.json") -Raw|ConvertFrom-Json
                if($launch.experiment -ne 'client_request' -or $launch.ingress -ne $condition.ingress -or $launch.artifact_sha256 -ne $artifacts[0].sha256 -or $launch.loader -ne $Loader -or $launch.native_channel -ne 6 -or $launch.server_batching -ne 'false' -or (Get-FileHash -LiteralPath $launch.argument_path).Hash -ne $launch.argument_sha256 -or $clientLog -notmatch ('native\.client_request_ingress enabled='+$condition.ingress+'\b')){throw 'Actual client JVM request selection differs'}
                $name=if($owner -eq 0){'ScenarioOwnerA'}else{'ScenarioOwnerB'};$uuid=if($owner -eq 0){'0557a034-0101-3132-9f82-f0d4761d4b04'}else{'c7afee9d-8411-38e2-8537-1c157fc2c41b'}
                $witness=@($lifetime.witnesses|Where-Object owner -eq $name)
                if($witness.Count -ne 2 -or ($witness.dimension-join ';') -cne 'minecraft:the_end;minecraft:overworld' -or @($witness|Where-Object uuid -ne $uuid).Count){throw 'Both original actor transitions required'}
                foreach($row in $witness){if($gameLog -notmatch [regex]::Escape($row.marker)){throw 'Authoritative transition health witness missing'}}
                if($clientLog -notmatch '(?s)benchmark\.native_dimension dimension=minecraft:the_end\b.*benchmark\.native_dimension dimension=minecraft:overworld\b'){throw 'Both original client respawn transitions missing'}
                if($condition.ingress -eq 'true'){
                    if($clientLog -notmatch '(?s)worker\.native_request_bound dimension=minecraft:overworld.*worker\.native_request_suspended.*worker\.native_request_resumed dimension=minecraft:the_end.*worker\.native_request_suspended.*worker\.native_request_resumed dimension=minecraft:overworld.*worker\.native_request_revoked.*worker\.native_request_bound dimension=minecraft:overworld'){throw 'Actual respawn fence and reconnect replacement sequence missing'}
                }elseif($clientLog -match 'worker\.native_request_(bound|suspended|resumed|revoked)'){throw 'MAIN control must not use captured native ingress'}
            }
        }
        $journal=$result.gameplay_journal;if(-not $journal.success -or $journal.nonce -ne $result.gameplay_nonce -or ($journal.phases.phase-join ';') -cne 'scan;ground;mine;place;reconnect' -or $journal.physics_paused -or $journal.world_blocks_set_by_console -or $journal.health_set){throw 'Exact natural gameplay journal missing'}
        if($NativeBatchExperiment -and ($journal.interaction_location -ne 2 -or $journal.after_measured_repeats -ne 3 -or $result.gameplay_interaction_location -ne 2 -or (Get-Content -LiteralPath (Join-Path $case 'remote-runner.log') -Raw) -notmatch 'SERVER_GAMEPLAY_RECEIPT_WAIT 3 2')){throw 'Exact post-measurement natural interaction location/return receipts missing'}
        foreach($phase in $journal.phases){if($phase.clients.Count -ne 2 -or (($phase.clients.owner|Sort-Object)-join ';') -cne 'ScenarioOwnerA;ScenarioOwnerB'){throw 'Both original actors required'};foreach($row in $phase.clients){if(-not $row.success -or $row.health -ne 20 -or -not $row.alive -or $row.nonce -ne $journal.nonce){throw 'Gameplay actor state failed'};if($phase.phase -ne 'scan' -and ($row.game_type -ne 0 -or $row.flying -or -not $row.on_ground -or $gameLog -notmatch [regex]::Escape('CAWG_GAMEPLAY_PLAYER_'+$journal.nonce+'_'+$phase.phase+'_'+$row.owner))){throw 'Authoritative survival landing proof missing'};if($phase.phase -eq 'mine' -and $row.original_mining_calls -lt 2){throw 'Original multi-tick mining missing'};if($phase.phase -in @('mine','place','reconnect') -and $gameLog -notmatch [regex]::Escape('CAWG_GAMEPLAY_BLOCK_'+$journal.nonce+'_'+$phase.phase+'_'+$row.owner)){throw 'Authoritative block proof missing'};if($phase.phase -eq 'reconnect' -and -not $row.connection_changed){throw 'Original reconnect missing'}}}
        $remote=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/remote-result.json') -Raw|ConvertFrom-Json;$paths=@();$centers=@()
        $final=@($journal.phases|Where-Object phase -eq 'reconnect')[0].clients
        foreach($row in $final){$cx=[int][Math]::Floor($row.target[0]/16.0);$cz=[int][Math]::Floor($row.target[2]/16.0);$centers+=@{x=$cx;z=$cz};foreach($dx in -4..4){foreach($dz in -4..4){$rx=[int][Math]::Floor(($cx+$dx)/32.0);$rz=[int][Math]::Floor(($cz+$dz)/32.0);$paths+='dimensions/minecraft/overworld/region/r.'+$rx+'.'+$rz+'.mca'}}};$paths=@($paths|Sort-Object -Unique);$lightingCenters[$condition.name]=$centers
        foreach($row in $final){$uuid=if($row.owner -eq 'ScenarioOwnerA'){'0557a034-0101-3132-9f82-f0d4761d4b04'}else{'c7afee9d-8411-38e2-8537-1c157fc2c41b'};$paths+=@(('players/data/'+$uuid+'.dat'),('players/stats/'+$uuid+'.json'))}
        $inventory=@(Saved-Inventory ($condition.name+'-saved-before') $remote.case $paths);$destination=Join-Path $root ($condition.name+'-saved')
        foreach($file in $inventory){$local=Join-Path $destination $file.relative;New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($local))|Out-Null;Step ($condition.name+'-copy-'+[IO.Path]::GetFileName($local)) (Get-Command scp.exe).Source @('-q',('gen1c@100.103.102.109:E:/WorldgenAssist/port26.3/'+$remote.case+'/'+$file.relative),$local) 60;if((Get-Item -LiteralPath $local).Length -ne $file.bytes -or (Get-FileHash -LiteralPath $local).Hash -ne $file.sha256){throw 'Saved copy identity differs'}}
        $afterRemote=@(Saved-Inventory ($condition.name+'-saved-after') $remote.case $paths);if(($inventory|ConvertTo-Json -Compress) -cne ($afterRemote|ConvertTo-Json -Compress)){throw 'Remote saved inputs changed during copy'}
        foreach($row in $final){$uuid=if($row.owner -eq 'ScenarioOwnerA'){'0557a034-0101-3132-9f82-f0d4761d4b04'}else{'c7afee9d-8411-38e2-8537-1c157fc2c41b'};$rx=[int][Math]::Floor($row.target[0]/512.0);$rz=[int][Math]::Floor($row.target[2]/512.0);$players+=@{condition=$condition.name;owner=$row.owner;data=(Join-Path $destination ('players/data/'+$uuid+'.dat'));stats=(Join-Path $destination ('players/stats/'+$uuid+'.json'));region=(Join-Path $destination "dimensions/minecraft/overworld/region/r.$rx.$rz.mca");x=$row.stand[0]+0.5;y=$row.stand[1];z=$row.stand[2]+0.5;target=@($row.target)}}
        $cases[$condition.name]=Join-Path $destination 'dimensions/minecraft/overworld/region';$inputs+=@{condition=$condition.name;world=$remote.case;destination=$destination;inventory=$inventory};$runtime+=@{condition=$condition.name;result=(Join-Path $case 'scenario-result.json');journal=$journal;measured_features=$intervals;coordinate_sha256=$coordinateHash;receipt_coverage=$receiptCoverage;batch_counts=$batchCounts;request_paths=$requestPaths}
    }
    $descriptor=Join-Path $root 'saved-gameplay-descriptor.json';@{output=(Join-Path $root 'saved-gameplay.json');players=$players}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $descriptor -Encoding utf8
    $offlineClasspath=$offlineClasses+';'+$classpath
    Step 'saved-gameplay' (Join-Path $jdk 'java.exe') @('-Xmx512m','-cp',$offlineClasspath,'io.github.genichimaruo.worldgenassist.server.SavedGameplayInspector263',$descriptor) 120
    foreach($name in @('original','assisted')){$lightDescriptor=Join-Path $root ($name+'-saved-lighting-descriptor.json');$singleCase=@{};$singleCase[$name]=$cases[$name];@{output=(Join-Path $root ($name+'-saved-lighting.json'));centers=$lightingCenters[$name];cases=$singleCase}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $lightDescriptor -Encoding utf8;Step ($name+'-saved-lighting') (Join-Path $jdk 'java.exe') @('-Xmx2g','-cp',$offlineClasspath,'io.github.genichimaruo.worldgenassist.server.SavedLightingInspector263',$lightDescriptor) 300}
    $savedReport=Get-Content -LiteralPath (Join-Path $root 'saved-gameplay.json') -Raw|ConvertFrom-Json;$lightReport=Lighting-Reports
    if(-not $savedReport.success -or $savedReport.players.Count -ne 4 -or -not $lightReport.success -or $lightReport.cases.Count -ne 2){throw 'Final saved proof incomplete'}
    foreach($row in $lightReport.cases){if(-not $row.success -or $row.required_chunks -ne 50 -or $row.halo_chunks -ne 162 -or $row.compared_values -ne 10649600 -or $row.changed_chunks -or $row.block_differences -or $row.sky_differences -or $row.saved_full_sky_below_original_source){throw 'Strict post-gameplay original saved lighting failed'}}
}catch{$issues.Add($_.Exception.ToString())}
finally {
    try {
        $after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after);if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Source changed during batch')}
        foreach($artifact in $artifacts){if((Get-FileHash -LiteralPath $artifact.path).Hash -ne $artifact.sha256){$issues.Add('Production JAR changed')}}
        if($probeSha -and (Get-FileHash -LiteralPath $probeJar).Hash -ne $probeSha){$issues.Add('Test-only driver JAR changed')}
        foreach($entry in $classpathInventory){if((Get-FileHash -LiteralPath $entry.path).Hash -ne $entry.sha256){$issues.Add('Original runtime changed')}}
        foreach($input in $inputs){foreach($file in $input.inventory){if((Get-FileHash -LiteralPath (Join-Path $input.destination $file.relative)).Hash -ne $file.sha256){$issues.Add('Copied saved input changed')}}}
        if(Test-Path -LiteralPath (Join-Path $root 'saved-gameplay.json')){$savedReport=Get-Content -LiteralPath (Join-Path $root 'saved-gameplay.json') -Raw|ConvertFrom-Json};$lightReport=Lighting-Reports
    }catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()}
    [ordered]@{schema='worldgen-assist.ordinary-gameplay-gate.v1';native_batch_experiment=[bool]$NativeBatchExperiment;native_client_request_experiment=[bool]$NativeClientRequestExperiment;native_transport_experiment=$(if($NativeClientRequestExperiment){'client_request'}elseif($NativeBatchExperiment){'batching'}else{'none'});measured_repeats=$measuredRepeats;both_conditions_assisted=[bool]$NativeBatchExperiment;loader=$Loader;fabric_input_parent=$fabricGameplayPath;success=($issues.Count -eq 0);root=$root;saved_safety_parent=$savedPath;reused_probe_root=$reusePath;artifacts=$artifacts;probe_jar=$probeJar;probe_sha256=$probeSha;steps=@($steps);runtime=$runtime;inputs=$inputs;saved_gameplay=$savedReport;lighting=$lightReport;issues=@($issues);new_junit=0;mod_builds=0;reused_unit_methods=4;reused_reader_methods=1;new_native=$(if($Loader -eq 'fabric'){0}else{@($steps|Where-Object {$_.name -match '^runtime-'}).Count});beta_claim=$false;scope=('Exact selected '+$Loader+' JAR; ordinary weak E-server/view32/two clients, natural survival landing/original multi-tick mining/placing/client reconnect plus authoritative checks and stopped saved player/voxel/light. Explicit native experiment compares SAME-JAR batching OFF/ON, or MAIN/NETWORK request with batchingFALSE; three same-coordinate repeats, both remoteON. Other profile uses one remoteOFF/ON repeat. Server restart/continuous motion remain unproved.')}|ConvertTo-Json -Depth 18|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Output "ORDINARY_GAMEPLAY_GATE success=$($issues.Count -eq 0) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
