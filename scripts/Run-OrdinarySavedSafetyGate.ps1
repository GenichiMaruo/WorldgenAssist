[CmdletBinding()]
param([switch]$Execute,[Parameter(Mandatory)][string]$ConfirmationRoot,[string]$ReuseSavedRoot)
# One sequential offline batch on exactly two completed dev23 ordinary worlds.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base=Join-Path $workspace 'test-artifacts'
$parentPath=(Resolve-Path -LiteralPath $ConfirmationRoot).Path
if(-not $parentPath.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Owned confirmation child required'}
$readerEvidence=Join-Path $base 'saved-lighting-gate-20261005-165556-107'
$reusePath=$null
if($ReuseSavedRoot){$reusePath=(Resolve-Path -LiteralPath $ReuseSavedRoot).Path;if(-not $reusePath.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Owned saved-copy child required'}}
if(-not $Execute){[ordered]@{parent=$parentPath;conditions=@('original','assisted');lighting_interior_chunks=50;halo_chunks=162;players=4;new_junit=0;mod_builds=0;new_games=0;reader_evidence=$readerEvidence;remote='gen1c@100.103.102.109';root='E:/WorldgenAssist/port26.3';scope='Stopped original creative-world saves; original light recomputation, NBT and death/damage statistics; not survival interaction proof'}|ConvertTo-Json;exit 0}
$root=Join-Path $base ('ordinary-saved-safety-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=[Collections.Generic.List[object]]::new()
$before=@();$artifacts=@();$records=@();$players=@();$cases=@{};$lock=$null;$classpathInventory=@();$lightReport=$null;$playerReport=$null;$reusedCopies=$null
function Manifest {
    $files=@();foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -Recurse -File}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Step([string]$Name,[string]$Executable,[string[]]$Arguments,[int]$Seconds=300){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$workspace
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in $Arguments){[void]$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try {
        if(-not $process.Start()){throw 'Offline step could not start'}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();$finished=$process.WaitForExit($Seconds*1000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$stdout.GetAwaiter().GetResult());[IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$stderr.GetAwaiter().GetResult())
        $steps.Add(@{name=$Name;success=($finished -and $process.ExitCode -eq 0);exit_code=$process.ExitCode;timed_out=(-not $finished)})
        Write-Host "ORDINARY_SAVED_STEP name=$Name success=$($steps[-1].success)"
        if(-not $steps[-1].success){throw "Step failed: $Name; raw reports retained"}
    }finally{$process.Dispose()}
}
function Remote-Inventory([string]$Name,[string]$World,[string[]]$RelativePaths){
    if($World -notmatch '^scenario-overworld-(vanilla|assisted)-p2-performance-[0-9-]+$'){throw 'Owned stopped world identity required'}
    foreach($path in $RelativePaths){if($path -notmatch '^(dimensions/minecraft/overworld/region/r\.-?\d+\.-?\d+\.mca|players/(data/[0-9a-f-]+\.dat|stats/[0-9a-f-]+\.json))$'){throw 'Bounded exact saved input required'}}
    $code='$ErrorActionPreference="Stop";$env:TEMP="E:/WorldgenAssist/temp";$env:TMP=$env:TEMP;'
    $code+='if(@(Get-CimInstance Win32_Process -Filter "Name=''java.exe''"|Where-Object {$_.CommandLine -and $_.CommandLine.Replace(''\'',''/'') -match ''E:/WorldgenAssist/port26.3/fabric-server-launch.jar''}).Count){throw "Owned server still live"};'
    $code+='$world=''E:/WorldgenAssist/port26.3/'+$World+''';$paths=@('+(($RelativePaths|ForEach-Object {"'$_'"}) -join ',')+');'
    $code+='@($paths|ForEach-Object {$path=Join-Path $world $_;$file=Get-Item -LiteralPath $path;if($file.Length -gt 128MB){throw "Input bound"};[ordered]@{relative=$_;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $path).Hash}})|ConvertTo-Json'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
    Step $Name (Get-Command ssh.exe).Source @('-o','BatchMode=yes','-o','ConnectTimeout=15','gen1c@100.103.102.109','powershell -NoProfile -NonInteractive -EncodedCommand '+$encoded) 60
    $inventory=@(Get-Content -LiteralPath (Join-Path $root "$Name-out.log") -Raw|ConvertFrom-Json)
    if($inventory.Count -ne $RelativePaths.Count -or (($inventory.relative|Sort-Object)-join ';') -cne (($RelativePaths|Sort-Object)-join ';')){throw 'Saved inventory differs'}
    return $inventory
}
try {
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $parent=Get-Content -LiteralPath (Join-Path $parentPath 'summary.json') -Raw|ConvertFrom-Json
    if(-not $parent.success -or $parent.schema -ne 'worldgen-assist.authoritative-biome-confirmation.v1' -or $parent.issues.Count -or $parent.steps.Count -ne 4 -or @($parent.steps|Where-Object {-not $_.success -or $_.timed_out}).Count){throw 'Exact successful closed dev23 confirmation required'}
    $old=[IO.File]::ReadAllLines((Join-Path $parentPath 'source-manifest-before.sha256'))
    if(($old-join "`n") -cne ([IO.File]::ReadAllLines((Join-Path $parentPath 'source-manifest-after.sha256'))-join "`n")){throw 'Parent source identity differs'}
    $before=Manifest;$added=@('scripts/Run-OrdinarySavedSafetyGate.ps1','scripts/SavedPlayerInspector263.java')
    $comparable=@($before|Where-Object {$_.Substring(66) -notin $added})
    if(($old-join "`n") -cne ($comparable-join "`n")){throw 'Only exact two offline helper additions allowed'}
    if($reusePath){
        $reusedCopies=Get-Content -LiteralPath (Join-Path $reusePath 'summary.json') -Raw|ConvertFrom-Json
        if($reusedCopies.schema -ne 'worldgen-assist.ordinary-saved-safety.v1' -or $reusedCopies.success -or $reusedCopies.parent -ne $parentPath -or $reusedCopies.inputs.Count -ne 2 -or $reusedCopies.steps.Count -ne 18 -or $reusedCopies.issues.Count -ne 1 -or $reusedCopies.issues[0] -notmatch 'Step failed: saved-players; raw reports retained' -or @($reusedCopies.steps|Where-Object {-not $_.success -and $_.name -ne 'saved-players'}).Count){throw 'Exact closed coordinate-expectation failure/copies required'}
        $copiedSource=[IO.File]::ReadAllLines((Join-Path $reusePath 'source-manifest-before.sha256'))
        if(($copiedSource-join "`n") -cne ([IO.File]::ReadAllLines((Join-Path $reusePath 'source-manifest-after.sha256'))-join "`n")){throw 'Saved-copy batch input identity differs'}
        $oldUnchanged=@($copiedSource|Where-Object {$_.Substring(66) -ne 'scripts/Run-OrdinarySavedSafetyGate.ps1'})
        $nowUnchanged=@($before|Where-Object {$_.Substring(66) -ne 'scripts/Run-OrdinarySavedSafetyGate.ps1'})
        if(($oldUnchanged-join "`n") -cne ($nowUnchanged-join "`n")){throw 'Only offline coordinator correction allowed for copy reuse'}
    }
    [IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    foreach($artifact in $parent.artifacts){
        if($artifact.version -ne '0.1.0-alpha.8-dev.23+mc26.3' -or $artifact.sha256 -ne (Get-FileHash -LiteralPath $artifact.path).Hash -or $artifact.sha256 -ne (Get-FileHash -LiteralPath (Join-Path $parentPath ([IO.Path]::GetFileName($artifact.path)))).Hash){throw 'Exact current/retained dev23 artifact required'}
        $artifacts+=$artifact
    }
    if($artifacts.Count -ne 3){throw 'Three exact loader artifacts required'}
    $readerManifest=[IO.File]::ReadAllLines((Join-Path $readerEvidence 'source-manifest-before.sha256'))
    foreach($file in @('src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLighting263Test.java','src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightView263.java','src/main/java/io/github/genichimaruo/worldgenassist/server/SavedLightDigest.java','src/main/java/io/github/genichimaruo/worldgenassist/server/DecorationStageDigest.java')){
        $row=@($readerManifest|Where-Object {$_.Substring(66) -eq $file})
        if($row.Count -ne 1 -or $row[0].Substring(0,64) -ne (Get-FileHash -LiteralPath (Join-Path $workspace $file)).Hash){throw 'Exact unchanged reader test/dependencies required'}
    }
    $xmlPath=Join-Path $readerEvidence 'TEST-io.github.genichimaruo.worldgenassist.server.SavedLighting263Test.xml';[xml]$xml=Get-Content -LiteralPath $xmlPath
    if([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or $xml.testsuite.testcase.name -cne 'savedValuesKeepMissingSkySemanticsAndCorruptNibblesWithoutRepair()'){throw 'Exact prior one-method reader proof required'}
    Copy-Item -LiteralPath $xmlPath,$PSCommandPath,(Join-Path $PSScriptRoot 'SavedPlayerInspector263.java') -Destination $root
    $classpathInventory=@(Get-Content -LiteralPath (Join-Path $parentPath 'performance/assisted/clients/owner-0/installed-client-classpath.json') -Raw|ConvertFrom-Json)
    foreach($entry in $classpathInventory){if((Get-FileHash -LiteralPath $entry.path).Hash -ne $entry.sha256){throw 'Original runtime classpath changed'}}
    $game=@($classpathInventory|Where-Object name -eq 'original Minecraft 26.3 client')
    if($game.Count -ne 1 -or $game[0].sha256 -ne '4508D006323F24FA02876310C192D739AF56516EB259000AC50F0909A68C9A2D'){throw 'Exact original26.3 game required'}
    $fabric=@($artifacts|Where-Object loader -eq 'fabric');$classpath=(@($classpathInventory.path)+@($fabric[0].path))-join ';'
    $classes=Join-Path $root 'classes';New-Item -ItemType Directory -Path $classes|Out-Null
    $sources=@((Join-Path $workspace 'src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightView263.java'),(Join-Path $workspace 'src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightingInspector263.java'),(Join-Path $PSScriptRoot 'SavedPlayerInspector263.java'))
    foreach($source in $sources){Copy-Item -LiteralPath $source -Destination $root -Force}
    $jdk='C:/Program Files/Java/jdk-25.0.4/bin'
    Step 'compile-offline-readers' (Join-Path $jdk 'javac.exe') (@('-encoding','UTF-8','-proc:none','-cp',$classpath,'-d',$classes)+$sources) 120
    # Original /tp Vec3Argument centers integer absolute X/Z by +0.5, including negatives.
    $owners=@(@{name='ScenarioOwnerA';uuid='0557a034-0101-3132-9f82-f0d4761d4b04';x=32384.5;z=-48383.5},@{name='ScenarioOwnerB';uuid='c7afee9d-8411-38e2-8537-1c157fc2c41b';x=-32383.5;z=48384.5})
    $centers=@(@{x=1512;z=-2512},@{x=-1512;z=2512});$regionNames=@('r.-48.78.mca','r.47.-79.mca')
    foreach($condition in @('original','assisted')){
        $case=Join-Path $parentPath ('performance/'+$condition)
        $result=Get-Content -LiteralPath (Join-Path $case 'scenario-result.json') -Raw|ConvertFrom-Json
        $remote=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/remote-result.json') -Raw|ConvertFrom-Json
        $config=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/scenario-config.json') -Raw|ConvertFrom-Json
        $disconnect=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/fixture-disconnects.json') -Raw|ConvertFrom-Json
        if(-not $result.success -or -not $result.cleanup_safe -or -not $remote.success -or -not $remote.cleanup_safe -or -not $result.loopback_only -or -not $remote.loopback_only -or $result.feature_fixture -or $result.decoration_digest -or $result.feature_backend -ne 'parallel' -or $result.artifact_sha256 -ne $fabric[0].sha256 -or $result.source_manifest_before_sha256 -ne $result.source_manifest_after_sha256 -or $result.source_manifest_after_sha256 -ne $parent.source_sha256){throw 'Stopped ordinary same-JAR runtime required'}
        if($config.seed -ne 8675309 -or $config.view_distance -ne 32 -or $config.warmup_runs -ne 1 -or $config.measured_repeats -ne 3 -or $config.movement -ne 'relocation' -or -not $disconnect.all_disconnects_observed){throw 'Exact stopped measured-world scope differs'}
        $log=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/minecraft-latest.log') -Raw
        if($log -notmatch 'Stopping server' -or $log -notmatch 'Saving worlds' -or $log -notmatch 'CAWG_SCENARIO_MEASURED_END_3' -or $log -match 'fixture.armed|fixture.player_paused'){throw 'Original saved ordinary completed world required'}
        $paths=@($regionNames|ForEach-Object {'dimensions/minecraft/overworld/region/'+$_})+@(foreach($owner in $owners){'players/data/'+$owner.uuid+'.dat';'players/stats/'+$owner.uuid+'.json'})
        if($reusePath){
            $retained=@($reusedCopies.inputs|Where-Object condition -eq $condition)
            if($retained.Count -ne 1 -or $retained[0].source_case -ne $case -or $retained[0].world -ne $remote.case -or $retained[0].destination -ne (Join-Path $reusePath $condition)){throw 'Exact source-world/copy path required'}
            $inventory=@($retained[0].inventory);$destination=$retained[0].destination
            if($inventory.Count -ne $paths.Count -or (($inventory.relative|Sort-Object)-join ';') -cne (($paths|Sort-Object)-join ';')){throw 'Exact retained inventory required'}
            foreach($file in $inventory){if((Get-Item -LiteralPath (Join-Path $destination $file.relative)).Length -ne $file.bytes -or (Get-FileHash -LiteralPath (Join-Path $destination $file.relative)).Hash -ne $file.sha256){throw 'Previously verified immutable saved copy changed'}}
        }else{
            $inventory=@(Remote-Inventory ($condition+'-before') $remote.case $paths)
            $destination=Join-Path $root $condition
            foreach($file in $inventory){
                $local=Join-Path $destination $file.relative;New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($local))|Out-Null
                Step ($condition+'-copy-'+[IO.Path]::GetFileName($local)) (Get-Command scp.exe).Source @('-q',('gen1c@100.103.102.109:E:/WorldgenAssist/port26.3/'+$remote.case+'/'+$file.relative),$local) 60
                if((Get-Item -LiteralPath $local).Length -ne $file.bytes -or (Get-FileHash -LiteralPath $local).Hash -ne $file.sha256){throw 'Saved copy identity differs'}
            }
            $afterRemote=@(Remote-Inventory ($condition+'-after') $remote.case $paths)
            if(($inventory|ConvertTo-Json -Compress) -cne ($afterRemote|ConvertTo-Json -Compress)){throw 'Remote saved inputs changed during copy'}
        }
        $cases[$condition]=Join-Path $destination 'dimensions/minecraft/overworld/region'
        foreach($owner in $owners){$players+=@{condition=$condition;owner=$owner.name;uuid=$owner.uuid;data=(Join-Path $destination ('players/data/'+$owner.uuid+'.dat'));stats=(Join-Path $destination ('players/stats/'+$owner.uuid+'.json'));x=$owner.x;z=$owner.z}}
        $records+=@{condition=$condition;source_case=$case;world=$remote.case;inventory=$inventory;destination=$destination}
    }
    $lightDescriptor=Join-Path $root 'lighting-descriptor.json';@{output=(Join-Path $root 'lighting-invariants.json');centers=$centers;cases=$cases}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $lightDescriptor -Encoding utf8
    $playerDescriptor=Join-Path $root 'player-descriptor.json';@{output=(Join-Path $root 'player-safety.json');players=$players}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $playerDescriptor -Encoding utf8
    $analysisClasspath=$classes+';'+$classpath
    Step 'saved-players' (Join-Path $jdk 'java.exe') @('-Xmx512m','-cp',$analysisClasspath,'io.github.genichimaruo.worldgenassist.server.SavedPlayerInspector263',$playerDescriptor) 60
    Step 'saved-lighting' (Join-Path $jdk 'java.exe') @('-Xmx2g','-cp',$analysisClasspath,'io.github.genichimaruo.worldgenassist.server.SavedLightingInspector263',$lightDescriptor) 300
    $playerReport=Get-Content -LiteralPath (Join-Path $root 'player-safety.json') -Raw|ConvertFrom-Json
    $lightReport=Get-Content -LiteralPath (Join-Path $root 'lighting-invariants.json') -Raw|ConvertFrom-Json
    if(-not $playerReport.success -or $playerReport.players.Count -ne 4 -or -not $lightReport.success -or $lightReport.cases.Count -ne 2){throw 'Exact saved-player/light proof incomplete'}
    foreach($row in $lightReport.cases){if(-not $row.success -or $row.required_chunks -ne 50 -or $row.halo_chunks -ne 162 -or $row.compared_values -ne 10649600 -or $row.changed_chunks -or $row.block_differences -or $row.sky_differences -or $row.saved_full_sky_below_original_source){throw 'Strict own-block light equality failed'}}
}catch{$issues.Add($_.Exception.ToString())}
finally {
    try {
        if(Test-Path -LiteralPath (Join-Path $root 'player-safety.json')){$playerReport=Get-Content -LiteralPath (Join-Path $root 'player-safety.json') -Raw|ConvertFrom-Json}
        if(Test-Path -LiteralPath (Join-Path $root 'lighting-invariants.json')){$lightReport=Get-Content -LiteralPath (Join-Path $root 'lighting-invariants.json') -Raw|ConvertFrom-Json}
        $after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after)
        if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Sources changed during offline batch')}
        foreach($artifact in $artifacts){if($artifact.sha256 -ne (Get-FileHash -LiteralPath $artifact.path).Hash){$issues.Add('Production artifact changed')}}
        foreach($record in $records){foreach($file in $record.inventory){if($file.sha256 -ne (Get-FileHash -LiteralPath (Join-Path $record.destination $file.relative)).Hash){$issues.Add('Copied saved input changed')}}}
        foreach($entry in $classpathInventory){if($entry.sha256 -ne (Get-FileHash -LiteralPath $entry.path).Hash){$issues.Add('Runtime classpath changed')}}
    }catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()}
    [ordered]@{schema='worldgen-assist.ordinary-saved-safety.v1';success=($issues.Count -eq 0);root=$root;parent=$parentPath;reused_saved_root=$reusePath;artifacts=$artifacts;steps=@($steps);inputs=$records;players=$playerReport;lighting=$lightReport;issues=@($issues);new_junit=0;reused_reader_methods=1;mod_builds=0;new_games=0;beta_claim=$false;scope='Two exact stopped dev23 reverse-order ordinary creative worlds. Original NBT/death-damage stats for all4 players and original light recomputation over50 interior/162 halo chunks per condition. No live world reads/writes, repairs, Minecraft launches, survival interactions, historical-cause or universal beta proof.'}|ConvertTo-Json -Depth 15|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Output "ORDINARY_SAVED_SAFETY success=$($issues.Count -eq 0) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
