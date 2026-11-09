[CmdletBinding()]
param([switch]$Execute,[ValidateSet(60)][int]$Minutes=60,[switch]$ReuseVerifiedFabric)
# One sequential affected batch: exact published alpha9, no new MOD builds/JUnit.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$base=Join-Path $workspace 'test-artifacts'
$freshLoaders=if($ReuseVerifiedFabric){@('forge','neoforge')}else{@('fabric','forge','neoforge')}
if(-not $Execute){@{loaders=@('fabric','forge','neoforge');fresh_loaders=$freshLoaders;reuse_verified_fabric=[bool]$ReuseVerifiedFabric;minutes_each=$Minutes;players=2;view=32;remote='gen1c@100.103.102.109';root='E:/WorldgenAssist/port26.3';mod_builds=0;junit=0;scope='Original input exploration, same-world restart, loaded block/player witnesses, second survival interaction, saved NBT/light.'}|ConvertTo-Json;exit 0}
$gate=Join-Path $base ('beta-stability-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'));New-Item -ItemType Directory -Path $gate|Out-Null
$root=$gate;$steps=[Collections.Generic.List[object]]::new();$issues=[Collections.Generic.List[string]]::new();$results=@();$artifacts=@();$lock=$null;$before=@()
$jdk='C:/Program Files/Java/jdk-25.0.4/bin';$NativeBatchExperiment=$false
. (Join-Path $PSScriptRoot 'NativeGameplayEvidence.ps1')
. (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1')
. (Join-Path $PSScriptRoot 'FeatureFixtureEvidence.ps1')
$AuthoritativeBiomeInputs=$true;$PublicationFence=$false
function Manifest {
    $files=@();foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts','docs')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -File -Recurse}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle','README.md','README.ja.md')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Get-FabricProofDigest([string]$Directory){
    # The complete 400-file loader directory is sealed, including raw logs,
    # minute samples, both saved inventories, inspector outputs and probe JAR.
    $lines=[string[]]@(Get-ChildItem -LiteralPath $Directory -File -Recurse -Force|ForEach-Object {
        $_.FullName.Substring($Directory.Length+1).Replace('\','/')+'  '+(Get-FileHash -LiteralPath $_.FullName).Hash
    })
    [Array]::Sort($lines,[StringComparer]::Ordinal)
    $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($lines -join [char]10))))
    if($lines.Count -ne 400 -or $hash -cne '7E0E812D944F9269BCFF88772BF2A0DB2F7365DA447BF145C20D61E337A78152'){throw 'Original Fabric evidence files changed or are missing'}
    return $hash
}
function Import-VerifiedFabric([object]$Artifact,[string[]]$CurrentManifest){
    $original=Join-Path $base 'beta-stability-gate-20261009-162356-447'
    $directory=Join-Path $original 'fabric'
    $summaryPath=Join-Path $original 'summary.json'
    $manifestPath=Join-Path $original 'source-manifest-before.sha256'
    $summaryHash=(Get-FileHash -LiteralPath $summaryPath).Hash
    $manifestHash=(Get-FileHash -LiteralPath $manifestPath).Hash
    if($summaryHash -cne '75550FF96B2580930E438DAE6560BF30A403C7FB991DA1B20439670409342BC3' -or
        $manifestHash -cne '6B290D27107BB7E4A9F0BB885D1301938BB02F7B7DEDDEB28E72AFCEC3336AE4' -or
        (Get-FileHash -LiteralPath (Join-Path $original 'source-manifest-after.sha256')).Hash -cne $manifestHash){throw 'Original Fabric parent or frozen source manifest differs'}
    $old=Get-Content -LiteralPath $summaryPath -Raw|ConvertFrom-Json
    $failed=@($old.steps|Where-Object {-not $_.success -or $_.timed_out -or $_.exit_code})
    if($old.success -or $old.schema -ne 'worldgen-assist.beta-stability-gate.v1' -or $old.results.Count -ne 1 -or $failed.Count -ne 1 -or
        $failed[0].root -ne (Join-Path $original 'forge') -or $failed[0].name -ne 'runtime-assistance-stability-restart'){throw 'Only the completed Fabric row from the closed Forge failure can be reused'}
    $row=$old.results[0]
    if($row.loader -cne 'fabric' -or $row.root -ne $directory -or $row.scenario -ne (Join-Path (Join-Path $directory 'assisted') 'scenario-result.json') -or
        -not $row.success -or -not $row.stability.success -or $row.stability.minutes -ne 60 -or $row.stability.samples.Count -ne 60 -or
        -not $row.stability.restart.loaded_block_before_any_postrestart_write -or $row.stability.restart.first_pid -eq $row.stability.restart.second_pid -or
        -not $row.saved_gameplay.success -or $row.saved_gameplay.players.Count -ne 4 -or -not $row.lighting.success -or $row.lighting.cases.Count -ne 1 -or
        $row.lighting.cases[0].compared_values -ne 10649600 -or $row.lighting.cases[0].block_differences -or $row.lighting.cases[0].sky_differences){throw 'Completed original Fabric restart/saved evidence required'}
    if($Artifact.loader -cne 'fabric' -or $Artifact.sha256 -cne $row.artifact_sha256 -or
        (Get-FileHash -LiteralPath $Artifact.path).Hash -cne $row.artifact_sha256 -or
        (Get-FileHash -LiteralPath (Join-Path $directory 'worldgen-gameplay-probe.jar')).Hash -cne $row.probe_sha256){throw 'Measured Fabric distribution or input driver differs'}
    # The imported run proves its original controller and input driver. Only
    # the listed test controls, packaging and documentation may have evolved.
    $allowed=@('scripts/Run-BetaStabilityGate.ps1','scripts/Run-WorldgenScenario.ps1','scripts/BetaStabilityClient.ps1','scripts/BetaStabilityServer.ps1','scripts/beta-probe/BetaStabilityProbe.java','scripts/Build-ReleasePackage.ps1')
    $changes=@(Compare-Object ([IO.File]::ReadAllLines($manifestPath)) $CurrentManifest -CaseSensitive)
    foreach($delta in $changes){
        $relative=$delta.InputObject.Substring(66)
        if(-not $relative.StartsWith('docs/',[StringComparison]::Ordinal) -and $relative -cnotin $allowed){throw "Input outside the permitted evidence-reuse scope changed: $relative"}
    }
    $digest=Get-FabricProofDigest $directory
    $row|Add-Member -NotePropertyName provenance -NotePropertyValue ([ordered]@{
        kind='retained_fabric';original_parent=$original;original_parent_success=$false;parent_summary_sha256=$summaryHash;original_source_manifest_sha256=$manifestHash
        original_evidence_directory=$directory;original_evidence_files=400;original_evidence_sha256=$digest
        manifest_delta=@($changes|Select-Object InputObject,SideIndicator);fresh_runtime=$false
    })
    return $row
}
function Step([string]$Name,[string]$Executable,[string[]]$Arguments,[int]$Seconds=120){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$workspace;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($arg in $Arguments){[void]$info.ArgumentList.Add($arg)}
    $p=[Diagnostics.Process]::new();$p.StartInfo=$info
    try {
        if(-not $p.Start()){throw "Cannot start $Name"};$out=$p.StandardOutput.ReadToEndAsync();$err=$p.StandardError.ReadToEndAsync()
        Write-Host "BETA_STEP_STARTED name=$Name evidence=$root"
        $finished=$p.WaitForExit($Seconds*1000);if(-not $finished){$p.Kill($true);$p.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root ($Name+'-out.log')),$out.GetAwaiter().GetResult());[IO.File]::WriteAllText((Join-Path $root ($Name+'-err.log')),$err.GetAwaiter().GetResult())
        $record=@{name=$Name;root=$root;success=($finished -and $p.ExitCode -eq 0);exit_code=$p.ExitCode;timed_out=(-not $finished)};$steps.Add($record)
        Write-Host "BETA_STEP_COMPLETE name=$Name success=$($record.success)"
        if(-not $record.success){throw "Affected step failed: $Name; original logs retained"}
    }finally{$p.Dispose()}
}
function Remote([string]$Name,[string]$Code){$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('$ErrorActionPreference="Stop";$ProgressPreference="SilentlyContinue";$env:TEMP="E:/WorldgenAssist/temp";$env:TMP=$env:TEMP;'+$Code));Step $Name (Get-Command ssh.exe).Source @('-o','BatchMode=yes','-o','ConnectTimeout=15','gen1c@100.103.102.109','powershell -NoProfile -NonInteractive -EncodedCommand '+$encoded) 60}
try {
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $before=Manifest;[IO.File]::WriteAllLines((Join-Path $gate 'source-manifest-before.sha256'),$before)
    $syntaxCode='$bad=@();foreach($file in @("Run-BetaStabilityGate.ps1","Run-WorldgenScenario.ps1","Remote-WorldgenScenarioServer.ps1","BetaStabilityClient.ps1","BetaStabilityServer.ps1","NativeGameplayEvidence.ps1","WorldgenGameplayClient.ps1","Build-ReleasePackage.ps1")){$t=$null;$e=$null;[void][Management.Automation.Language.Parser]::ParseFile((Join-Path "scripts" $file),[ref]$t,[ref]$e);$bad+=@($e)};if($bad.Count){$bad|ForEach-Object {Write-Error $_.ToString()};exit 1};Write-Output "Affected harness syntax passed"'
    Step 'affected-harness-syntax' (Get-Command pwsh.exe).Source @('-NoProfile','-NonInteractive','-Command',$syntaxCode)
    # An open reader must see its own snapshot while the writer publishes the next one.
    . (Join-Path $PSScriptRoot 'BetaStabilityClient.ps1')
    $first=Join-Path $gate 'sharing-check.json'
    $next=Join-Path $gate 'sharing-check.new.json'
    [IO.File]::WriteAllText($first,'1')
    [IO.File]::WriteAllText($next,'2')
    $reader=Open-BetaStateReport $first
    try {
        [IO.File]::Move($next,$first,$true)
        if($reader.ReadByte() -ne 49 -or [IO.File]::ReadAllText($first) -cne '2'){throw 'Atomic heartbeat replacement did not preserve both snapshots'}
    }finally{$reader.Dispose()}
    $steps.Add(@{name='heartbeat-replacement-with-live-reader';root=$gate;success=$true;exit_code=0;timed_out=$false})
    # All shipped production/build inputs must still match the closed dev2 proof.
    $parent=Join-Path $base 'native-client-request-gate-20261009-050308-626'
    $candidate=Get-Content -LiteralPath (Join-Path $parent 'summary.json') -Raw|ConvertFrom-Json
    if(-not $candidate.success -or $candidate.junit.tests -ne 3 -or $candidate.issues.Count -or $candidate.results.Count -ne 2){throw 'Closed native ingress proof required'}
    $expected=@(Get-Content -LiteralPath (Join-Path $parent 'source-manifest-before.sha256')|Where-Object {$_ -match '^([A-F0-9]{64})  (src/.+|build\.gradle|settings\.gradle|gradle\.properties|loaders/[^/]+/(?:src/.+|build\.gradle))$'})
    $current=@($before|Where-Object {$_ -match '^([A-F0-9]{64})  (src/.+|build\.gradle|settings\.gradle|gradle\.properties|loaders/[^/]+/(?:src/.+|build\.gradle))$'}|ForEach-Object {
        if($_.Substring(66) -eq 'gradle.properties'){$text=[IO.File]::ReadAllText((Join-Path $workspace 'gradle.properties')).Replace('mod_version=0.1.0-alpha.9+mc26.3','mod_version=0.1.0-alpha.9-dev.2+mc26.3');[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text)))+'  gradle.properties'}else{$_}
    })
    if((($expected|Sort-Object)-join "`n") -cne (($current|Sort-Object)-join "`n")){throw 'Shipped production/build identity changed'}
    $package=Join-Path $base 'release-alpha9-20261009';$pack=Get-Content -LiteralPath (Join-Path $package 'build-verification.json') -Raw|ConvertFrom-Json
    if(-not $pack.success -or $pack.version -ne '0.1.0-alpha.9+mc26.3' -or $pack.artifacts.Count -ne 3){throw 'Published alpha9 identity required'}
    foreach($loader in @('fabric','forge','neoforge')){
        $row=@($pack.artifacts|Where-Object loader -eq $loader)[0];$a=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $loader
        if($a.Version -ne $pack.version -or (Get-FileHash -LiteralPath $a.Path).Hash -ne $row.sha256 -or (Get-FileHash -LiteralPath (Join-Path $package ('assets/'+$row.file))).Hash -ne $row.sha256 -or -not $row.runtime_content_unchanged -or -not $row.source_entries_unchanged){throw 'Exact published runtime archive required'}
        $artifacts+=@{loader=$loader;path=$a.Path;sha256=$row.sha256;version=$a.Version}
    }
    $prior=Join-Path $base 'ordinary-gameplay-gate-20261007-230655-166';$fabric=Get-Content -LiteralPath (Join-Path $prior 'summary.json') -Raw|ConvertFrom-Json
    if(-not $fabric.success -or (Get-FileHash -LiteralPath $fabric.probe_jar).Hash -ne $fabric.probe_sha256){throw 'Original successful input driver required'}
    foreach($name in @('GameplayProbe','GameplayTickMixin','GameplayHeldInputMixin')){if((Get-FileHash -LiteralPath (Join-Path $prior ($name+'.java'))).Hash -ne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot ('gameplay-probe/'+$name+'.java'))).Hash){throw 'Original gameplay source differs'}}
    $confirm=Join-Path $base 'authoritative-biome-confirmation-20261007-003107-976'
    $inventory=@(Get-Content -LiteralPath (Join-Path $confirm 'performance/assisted/clients/owner-0/installed-client-classpath.json') -Raw|ConvertFrom-Json)
    foreach($entry in $inventory){if((Get-FileHash -LiteralPath $entry.path).Hash -ne $entry.sha256){throw 'Installed compile/runtime input changed'}}
    if($ReuseVerifiedFabric){
        $retained=Import-VerifiedFabric $artifacts[0] $before
        $results+=@($retained)
        $steps.Add(@{name='verify-retained-fabric-evidence';root=$gate;success=$true;exit_code=0;timed_out=$false})
        ConvertTo-Json -InputObject $results -Depth 25|Set-Content -LiteralPath (Join-Path $gate 'progress.json')
        Write-Host "BETA_LOADER_REUSED loader=fabric original=$($retained.root)"
    }
    $classpath=(@($inventory.path)+@($artifacts[0].path))-join ';'
    $offline=Join-Path $gate 'offline-classes';New-Item -ItemType Directory -Path $offline|Out-Null
    $readerSources=@((Join-Path $PSScriptRoot 'SavedGameplayInspector263.java'),(Join-Path $workspace 'src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightView263.java'),(Join-Path $workspace 'src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightingInspector263.java'))
    foreach($source in $readerSources){Copy-Item -LiteralPath $source -Destination $gate}
    Step 'compile-unchanged-offline-readers' (Join-Path $jdk 'javac.exe') (@('-encoding','UTF-8','-proc:none','-cp',$classpath,'-d',$offline)+$readerSources)
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Run-FeaturePipelineGate.ps1'),[ref]$tokens,[ref]$errors)
    $definition=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Check-Physical'},$true));if($errors.Count -or $definition.Count -ne 1){throw 'Original physical checker missing'};. ([scriptblock]::Create($definition[0].Extent.Text))
    Remote 'physical-preflight' 'if(-not(Test-Path -LiteralPath "E:/WorldgenAssist/java25/bin/java.exe") -or (Get-CimInstance Win32_LogicalDisk -Filter "DeviceID=''E:''").FreeSpace -lt 30GB){throw "E-drive runtime/free-space prerequisite"};if(@(Get-CimInstance Win32_Process -Filter "Name=''java.exe''"|Where-Object {$_.CommandLine -and $_.CommandLine.Replace(''\'',''/'') -match ''E:/WorldgenAssist/port26.3/(fabric-server-launch\.jar|native-runtime/(forge|neoforge)/)''}).Count){throw "Owned server already live"};Get-CimInstance Win32_Processor|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors|ConvertTo-Json'
    foreach($loader in $freshLoaders){
        $root=Join-Path $gate $loader;New-Item -ItemType Directory -Path $root|Out-Null
        $artifacts=@($artifacts|Sort-Object @{Expression={if($_.loader -eq $loader){0}else{1}}},loader)
        $classes=Join-Path $root 'probe-classes';New-Item -ItemType Directory -Path $classes|Out-Null;$jar=Join-Path $root 'worldgen-gameplay-probe.jar'
        if($loader -eq 'fabric'){
            $zip=[IO.Compression.ZipFile]::OpenRead($fabric.probe_jar)
            try{foreach($entry in @($zip.Entries|Where-Object {$_.FullName.EndsWith('.class')})){$dest=Join-Path $classes $entry.FullName;New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($dest))|Out-Null;[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$dest)}}finally{$zip.Dispose()}
            $sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'beta-probe') -Filter '*.java' -File|ForEach-Object FullName)
            Step 'compile-beta-only-input' (Join-Path $jdk 'javac.exe') (@('-encoding','UTF-8','-proc:none','-cp',$classpath,'-d',$classes)+$sources)
            @{schemaVersion=1;id='worldgen-gameplay-probe';version='1.0.0';name='Unshipped owned beta evidence';environment='client';mixins=@('gameplay-probe.mixins.json');depends=@{fabricloader='=0.19.5';minecraft='=26.3';java='>=25'}}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $classes 'fabric.mod.json') -Encoding utf8NoBOM
            @{required=$true;minVersion='0.8';package='io.github.genichimaruo.worldgenassist.probe.mixin';compatibilityLevel='JAVA_25';client=@('GameplayTickMixin','GameplayHeldInputMixin','BetaStabilityTickMixin','BetaStabilityInputMixin');injectors=@{defaultRequire=1}}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $classes 'gameplay-probe.mixins.json') -Encoding utf8NoBOM
            Step 'package-beta-only-input' (Join-Path $jdk 'jar.exe') @('--create','--file',$jar,'-C',$classes,'.');$probeHash=(Get-FileHash -LiteralPath $jar).Hash
        }else{$probe=New-NativeGameplayProbe $loader $prior $classes $jar $classpath $jdk $true;$probeHash=$probe.sha256}
        $case=Join-Path $root 'assisted'
        $args=@('-NoProfile','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-RemoteHost','gen1c@100.103.102.109','-RemoteRoot','E:/WorldgenAssist/port26.3','-Dimension','overworld','-Mode','assisted','-Players','2','-Purpose','performance','-CacheEntries','128','-Prediction','true','-ValidationCells','8','-Seed','8675309','-OutputRoot',$case,'-ViewDistance','32','-MeasureFullView','-QuietRemoteTrace','-WarmupRuns','1','-MeasuredRepeats','1','-NoiseBackend','cooperative','-WindowProfile','deep','-RemoteApplicationProfile','overlap','-RemoteWorkKind','complete','-CompleteVerification','peer','-AuthoritativeBiomes','demand','-FeatureBackend','parallel','-GameplayProbeJar',$jar,'-GameplayProbeSha256',$probeHash,'-Loader',$loader,'-BetaStabilityMinutes','60')
        Step 'runtime-assistance-stability-restart' (Get-Command pwsh.exe).Source $args 8400
        $physical=Check-Physical $case @{name='assisted';mode='assisted';backend='parallel'} $false
        $stability=$physical.beta_stability
        if(-not $stability.success -or $stability.minutes -ne 60 -or $stability.samples.Count -ne 60 -or -not $stability.restart.loaded_block_before_any_postrestart_write -or $stability.restart.first_pid -eq $stability.restart.second_pid -or $physical.gameplay_issues.Count){throw 'Full stability/same-world restart evidence incomplete'}
        $log=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/latest.log') -Raw
        if($log -match 'OutOfMemoryError|Exception in server tick loop|fixture.player_paused' -or $log -notmatch 'job.peer_terrain_applied' -or $log -notmatch 'job.authoritative_biomes_applied'){throw 'Runtime failure or actual assistance missing'}
        $explore=[regex]::Match($log,'(?s)CAWG_BETA_EXPLORE_BEGIN_'+$stability.nonce+'\b(?<body>.*?)CAWG_BETA_EXPLORE_END_'+$stability.nonce+'\b')
        if(-not $explore.Success -or $explore.Groups['body'].Value -notmatch 'stage.complete stage=noise' -or $explore.Groups['body'].Value -notmatch 'job.peer_terrain_applied'){throw 'Actual new generation and accepted assistance during exploration missing'}
        $timeouts=[regex]::Matches($explore.Groups['body'].Value,'job.timeout\b').Count
        $players=@();$centers=@();$final=@($physical.gameplay_journal.phases|Where-Object phase -eq 'reconnect')[0].clients
        $remote=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/remote-result.json') -Raw|ConvertFrom-Json
        $world=$remote.case;if($world -notmatch '^scenario-overworld-assisted-p2-performance-[0-9-]+$'){throw 'Owned stopped world required'}
        $paths=@()
        foreach($row in $final){$uuid=if($row.owner -eq 'ScenarioOwnerA'){'0557a034-0101-3132-9f82-f0d4761d4b04'}else{'c7afee9d-8411-38e2-8537-1c157fc2c41b'};$cx=[int][Math]::Floor($row.target[0]/16.0);$cz=[int][Math]::Floor($row.target[2]/16.0);$centers+=@{x=$cx;z=$cz};$paths+=@(('players/data/'+$uuid+'.dat'),('players/stats/'+$uuid+'.json'));foreach($dx in -4..4){foreach($dz in -4..4){$paths+='dimensions/minecraft/overworld/region/r.'+[int][Math]::Floor(($cx+$dx)/32.0)+'.'+[int][Math]::Floor(($cz+$dz)/32.0)+'.mca'}}}
        $paths=@($paths|Sort-Object -Unique);$pathLiteral=($paths|ForEach-Object {"'$_'"})-join ','
        $inventoryCode='if(@(Get-CimInstance Win32_Process -Filter "Name=''java.exe''"|Where-Object {$_.CommandLine -and $_.CommandLine.Replace(''\'',''/'') -match ''E:/WorldgenAssist/port26.3/(fabric-server-launch\.jar|native-runtime/(forge|neoforge)/)''}).Count){throw "Owned server live"};$world="E:/WorldgenAssist/port26.3/'+$world+'";@(@('+$pathLiteral+')|ForEach-Object {$path=Join-Path $world $_;[ordered]@{relative=$_;sha256=(Get-FileHash -LiteralPath $path).Hash;bytes=(Get-Item -LiteralPath $path).Length}})|ConvertTo-Json'
        Remote 'saved-before' $inventoryCode;$savedInventory=@(Get-Content -LiteralPath (Join-Path $root 'saved-before-out.log') -Raw|ConvertFrom-Json)
        $destination=Join-Path $root 'final-saved'
        foreach($file in $savedInventory){if($file.relative -notin $paths -or $file.bytes -gt 128MB){throw 'Saved scope/bound'};$dest=Join-Path $destination $file.relative;New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($dest))|Out-Null;Step ('copy-'+[IO.Path]::GetFileName($dest)) (Get-Command scp.exe).Source @('-q',('gen1c@100.103.102.109:E:/WorldgenAssist/port26.3/'+$world+'/'+$file.relative),$dest) 60;if((Get-FileHash -LiteralPath $dest).Hash -ne $file.sha256){throw 'Saved copy changed'}}
        Remote 'saved-after' $inventoryCode;if([IO.File]::ReadAllText((Join-Path $root 'saved-before-out.log')) -cne [IO.File]::ReadAllText((Join-Path $root 'saved-after-out.log'))){throw 'Stopped saved inputs changed during copy'}
        foreach($condition in @('before_restart','after_restart')){
            $savedRoot=if($condition -eq 'before_restart'){Join-Path $case 'remote-evidence/beta-before-save'}else{$destination}
            foreach($row in $final){$uuid=if($row.owner -eq 'ScenarioOwnerA'){'0557a034-0101-3132-9f82-f0d4761d4b04'}else{'c7afee9d-8411-38e2-8537-1c157fc2c41b'};$rx=[int][Math]::Floor($row.target[0]/512.0);$rz=[int][Math]::Floor($row.target[2]/512.0);$players+=@{condition=$condition;owner=$row.owner;data=(Join-Path $savedRoot ('players/data/'+$uuid+'.dat'));stats=(Join-Path $savedRoot ('players/stats/'+$uuid+'.json'));region=(Join-Path $savedRoot "dimensions/minecraft/overworld/region/r.$rx.$rz.mca");x=$row.stand[0]+0.5;y=$row.stand[1];z=$row.stand[2]+0.5;target=@($row.target)}}
        }
        $descriptor=Join-Path $root 'saved-gameplay-descriptor.json';@{output=(Join-Path $root 'saved-gameplay.json');players=$players}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $descriptor
        Step 'saved-before-and-after-restart' (Join-Path $jdk 'java.exe') @('-Xmx512m','-cp',($offline+';'+$classpath),'io.github.genichimaruo.worldgenassist.server.SavedGameplayInspector263',$descriptor)
        $lightDescriptor=Join-Path $root 'saved-lighting-descriptor.json';@{output=(Join-Path $root 'saved-lighting.json');centers=$centers;cases=@{after_restart=(Join-Path $destination 'dimensions/minecraft/overworld/region')}}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $lightDescriptor
        Step 'saved-lighting-after-restart' (Join-Path $jdk 'java.exe') @('-Xmx2g','-cp',($offline+';'+$classpath),'io.github.genichimaruo.worldgenassist.server.SavedLightingInspector263',$lightDescriptor) 300
        $saved=Get-Content -LiteralPath (Join-Path $root 'saved-gameplay.json') -Raw|ConvertFrom-Json;$light=Get-Content -LiteralPath (Join-Path $root 'saved-lighting.json') -Raw|ConvertFrom-Json
        if(-not $saved.success -or $saved.players.Count -ne 4 -or -not $light.success -or $light.cases.Count -ne 1 -or $light.cases[0].compared_values -ne 10649600){throw 'Saved original player/voxel/light proof failed'}
        $results+=@{loader=$loader;success=$true;root=$root;scenario=(Join-Path $case 'scenario-result.json');artifact_sha256=@($artifacts|Where-Object loader -eq $loader)[0].sha256;probe_sha256=$probeHash;stability=$stability;exploration_timeouts=$timeouts;saved_gameplay=$saved;lighting=$light;final_inventory=$savedInventory}
        $results|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (Join-Path $gate 'progress.json')
        Write-Host "BETA_LOADER_COMPLETE loader=$loader"
    }
}catch{$issues.Add($_.Exception.ToString());$_.ScriptStackTrace|Set-Content -LiteralPath (Join-Path $gate 'failure-stack.txt')}
finally {
    if($ReuseVerifiedFabric -and $results.Count -eq 3){
        try{$null=Import-VerifiedFabric (@($artifacts|Where-Object loader -eq 'fabric')[0]) $before}catch{$issues.Add($_.Exception.ToString())}
    }
    try{$after=Manifest;[IO.File]::WriteAllLines((Join-Path $gate 'source-manifest-after.sha256'),$after);if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Inputs changed during batch')};foreach($a in $artifacts){if((Get-FileHash -LiteralPath $a.path).Hash -ne $a.sha256){$issues.Add('Runtime artifact changed')}}}catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()}
    @{schema='worldgen-assist.beta-stability-gate.v1';root=$gate;success=($issues.Count -eq 0 -and $results.Count -eq 3);minutes_each=$Minutes;requested_fresh_loaders=$freshLoaders;reuse_verified_fabric=[bool]$ReuseVerifiedFabric;artifacts=$artifacts;results=$results;steps=@($steps);issues=@($issues);new_junit=0;mod_builds=0;beta_claim=$false;scope='Bounded trusted two-client60-minute creative exploration; original survival before/after, same-world restart and saved player/voxel/light. Not indefinite uptime, adversarial peers, other MODs or stock absent-MOD speed.'}|ConvertTo-Json -Depth 25|Set-Content -LiteralPath (Join-Path $gate 'summary.json')
    Write-Host "BETA_GATE_COMPLETE success=$($issues.Count -eq 0 -and $results.Count -eq 3) evidence=$gate"
}
if($issues.Count -or $results.Count -ne 3){exit 1}
