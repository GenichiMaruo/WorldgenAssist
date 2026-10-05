[CmdletBinding()]
param([switch]$Execute,[Parameter(Mandatory)][string]$GateRoot)
# One new reader test and independent lighting on existing stopped worlds only.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base=Join-Path $workspace 'test-artifacts'
if(-not $Execute){@{tests=@('SavedLighting263Test');builds=0;terrain_runs=0;conditions=@('original','parallel','assisted');target_chunks_per_condition=50;halo_chunks_per_condition=162;source_gate=$GateRoot;remote_host='gen1c@100.103.102.109';remote_root='E:/WorldgenAssist/port26.3'}|ConvertTo-Json;exit 0}
$GateRoot=(Resolve-Path -LiteralPath $GateRoot).Path
if(-not $GateRoot.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned evidence parent required'}
$root=Join-Path $base ('saved-lighting-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=@();$records=@();$cases=@{};$lock=$null;$before=@();$artifacts=@();$junit=$null
$savedJava=$env:JAVA_HOME;$savedPath=$env:Path
function Manifest {
    $files=@()
    foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -Recurse -File}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Step([string]$Name,[string[]]$Arguments){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName="$env:SystemRoot\System32\cmd.exe";$info.WorkingDirectory=$workspace
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($arg in @('/d','/c',(Join-Path $workspace 'gradlew.bat'))+$Arguments){[void]$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(-not $process.Start()){throw 'Inspector could not start'}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();$finished=$process.WaitForExit(600000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$stderr.GetAwaiter().GetResult())
        $script:steps+=@{name=$Name;success=($finished -and $process.ExitCode -eq 0);exit_code=$process.ExitCode;timed_out=(-not $finished)}
        Write-Host "SAVED_LIGHTING_STEP name=$Name success=$($script:steps[-1].success)"
        if(-not $script:steps[-1].success){throw "Step failed: $Name; retained logs in $root"}
    }finally{$process.Dispose()}
}
try{
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $parent=Get-Content -LiteralPath (Join-Path $GateRoot 'summary.json') -Raw|ConvertFrom-Json
    if(-not $parent.success -or $parent.issues.Count){throw 'Completed pipeline gate required'}
    $old=[IO.File]::ReadAllLines((Join-Path $GateRoot 'source-manifest-before.sha256'))
    if(($old-join "`n") -cne ([IO.File]::ReadAllLines((Join-Path $GateRoot 'source-manifest-after.sha256'))-join "`n")){throw 'Parent input identity changed'}
    $before=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    $added=@('scripts/Run-SavedLightingGate.ps1','src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightView263.java','src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLighting263Test.java','src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightingInspector263.java')
    $oldComparable=@($old|Where-Object { $_.Substring(66) -ne 'build.gradle' })
    $newComparable=@($before|Where-Object { $_.Substring(66) -ne 'build.gradle' -and $_.Substring(66) -notin $added })
    if(($oldComparable-join "`n") -cne ($newComparable-join "`n")){throw 'All parent inputs except exact new offline helpers/build task must remain identical'}
    $build=[IO.File]::ReadAllText((Join-Path $workspace 'build.gradle')).Replace("`r`n","`n")
    $block=@'
// Independent light invariant on already-saved blocks; no production runtime or generator.
tasks.register('inspectSavedLighting', JavaExec) {
	dependsOn testClasses
	classpath = sourceSets.test.runtimeClasspath
	mainClass = 'io.github.genichimaruo.worldgenassist.server.SavedLightingInspector263'
	maxHeapSize = '2G'
	args providers.gradleProperty('savedLightingDescriptor').map { [it] }.getOrElse([])
}

'@
    $block=$block.Replace("`r`n","`n")+"`n"
    if($build.Split($block,[StringSplitOptions]::None).Count -ne 2){throw 'Only exact offline JavaExec addition permitted'}
    $priorBuild=$build.Replace($block,'');$expected=@($old|Where-Object { $_.Substring(66) -eq 'build.gradle' })[0].Substring(0,64)
    $hashes=@($priorBuild,$priorBuild.Replace("`n","`r`n"))|ForEach-Object {[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($_)))}
    if($expected -notin $hashes){throw 'Build differs beyond exact analysis task and newline encoding'}
    foreach($file in $added){Copy-Item -LiteralPath (Join-Path $workspace $file) -Destination $root}
    foreach($artifact in $parent.artifacts){
        if($artifact.sha256 -ne (Get-FileHash -LiteralPath $artifact.path).Hash -or $artifact.sha256 -ne (Get-FileHash -LiteralPath (Join-Path $GateRoot ([IO.Path]::GetFileName($artifact.path)))).Hash){throw 'Retained/current loader artifact identity differs'}
        $artifacts+=$artifact
    }
    $env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4';$env:Path="$env:JAVA_HOME\bin;$env:Path"
    Step 'reader-test' @('test','--tests','io.github.genichimaruo.worldgenassist.server.SavedLighting263Test')
    $xmlPath=Join-Path $workspace 'build/test-results/test/TEST-io.github.genichimaruo.worldgenassist.server.SavedLighting263Test.xml'
    Copy-Item -LiteralPath $xmlPath -Destination $root;[xml]$xml=Get-Content -LiteralPath $xmlPath
    $junit=@{};foreach($key in @('tests','failures','errors','skipped')){$junit[$key]=[int]$xml.testsuite.GetAttribute($key)}
    if($junit.tests -ne 1 -or $junit.failures -or $junit.errors -or $junit.skipped){throw 'One new reader test must pass'}
    $centers=@(@{x=1512;z=-2512},@{x=-1512;z=2512})
    $regionNames=@(foreach($center in $centers){foreach($dz in -4..4){foreach($dx in -4..4){'r.'+[Math]::Floor(($center.x+$dx)/32.0)+'.'+[Math]::Floor(($center.z+$dz)/32.0)+'.mca'}}})|Sort-Object -Unique
    foreach($name in @('original','parallel','assisted')){
        $inputRoot=Join-Path $GateRoot "performance/$name"
        $result=Get-Content -LiteralPath (Join-Path $inputRoot 'scenario-result.json') -Raw|ConvertFrom-Json
        $remote=Get-Content -LiteralPath (Join-Path $inputRoot 'remote-evidence/remote-result.json') -Raw|ConvertFrom-Json
        $config=Get-Content -LiteralPath (Join-Path $inputRoot 'remote-evidence/scenario-config.json') -Raw|ConvertFrom-Json
        $disconnect=Get-Content -LiteralPath (Join-Path $inputRoot 'remote-evidence/fixture-disconnects.json') -Raw|ConvertFrom-Json
        $mode=if($name -eq 'assisted'){'assisted'}else{'vanilla'};$backend=if($name -eq 'original'){'off'}else{'parallel'}
        if(-not $result.success -or -not $result.cleanup_safe -or -not $result.loopback_only -or -not $remote.success -or -not $remote.cleanup_safe -or -not $remote.loopback_only -or $remote.case -notmatch '^scenario-overworld-(vanilla|assisted)-p2-performance-[0-9-]+$'){throw 'Existing owned world not completed safely'}
        if($result.mode -ne $mode -or $result.feature_backend -ne $backend -or $result.feature_fixture -or $result.decoration_digest -or $result.purpose -ne 'performance' -or $result.artifact_sha256 -ne $artifacts[0].sha256 -or $result.source_manifest_before_sha256 -ne $result.source_manifest_after_sha256){throw 'Ordinary performance identity mismatch'}
        if($config.seed -ne 8675309 -or $config.warmup_runs -ne 1 -or $config.measured_repeats -ne 3 -or $config.view_distance -ne 32 -or $config.movement -ne 'relocation' -or -not $disconnect.all_disconnects_observed){throw 'Measured coordinates/view/disconnects differ'}
        $log=Get-Content -LiteralPath (Join-Path $inputRoot 'remote-evidence/latest.log') -Raw
        if($log -notmatch 'Stopping server' -or $log -notmatch 'Saving worlds' -or $log -notmatch 'CAWG_SCENARIO_MEASURED_END_1'){throw 'Stopped saved measurement missing'}
        $remotePath='E:/WorldgenAssist/port26.3/'+$remote.case+'/dimensions/minecraft/overworld/region'
        $local=Join-Path $root "$name/region";New-Item -ItemType Directory -Force -Path $local|Out-Null
        $code='$ErrorActionPreference="Stop";$ProgressPreference="SilentlyContinue";$env:TEMP="E:/WorldgenAssist/temp";$env:TMP=$env:TEMP;if(@(Get-CimInstance Win32_Process -Filter "Name=''java.exe''"|Where-Object {$_.CommandLine -and $_.CommandLine.Replace(''\'',''/'') -match ''E:/WorldgenAssist/port26.3/fabric-server-launch.jar''}).Count){throw "Owned server still running"};$root='''+$remotePath+''';$names=@('+(($regionNames|ForEach-Object {"'$_'"}) -join ',')+');@($names|ForEach-Object {$path=Join-Path $root $_;$file=Get-Item -LiteralPath $path;if($file.Length -gt 128MB){throw "Region input too large"};[ordered]@{name=$_;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $path).Hash}})|ConvertTo-Json'
        $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
        $hashes=& ssh.exe -o BatchMode=yes -o ConnectTimeout=15 gen1c@100.103.102.109 "powershell -NoProfile -NonInteractive -EncodedCommand $encoded"
        if($LASTEXITCODE -ne 0){throw 'Read-only remote hashing failed'}
        $hashes|Set-Content -LiteralPath (Join-Path $root "$name/remote-region-hashes.json")
        $inventory=@(($hashes-join "`n")|ConvertFrom-Json)
        if($inventory.Count -ne $regionNames.Count -or (($inventory.name|Sort-Object)-join ';') -cne ($regionNames-join ';')){throw 'Region inventory mismatch'}
        foreach($file in $inventory){
            & scp.exe -q ('gen1c@100.103.102.109:'+$remotePath+'/'+$file.name) $local
            if($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath (Join-Path $local $file.name)).Hash -ne $file.sha256){throw 'Saved world copy/hash differs'}
        }
        $cases[$name]=$local;$records+=@{name=$name;source_case=$inputRoot;remote_world=$remote.case;regions=$inventory;measured_repeat=1}
    }
    $descriptor=Join-Path $root 'descriptor.json'
    @{output=(Join-Path $root 'lighting-invariants.json');centers=$centers;cases=$cases}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $descriptor -Encoding utf8
    Step 'saved-invariants' @('inspectSavedLighting',('-PsavedLightingDescriptor='+$descriptor))
    $invariants=Get-Content -LiteralPath (Join-Path $root 'lighting-invariants.json') -Raw|ConvertFrom-Json
    if(-not $invariants.success -or $invariants.cases.Count -ne 3){throw 'All three ordinary saved lighting checks required'}
    foreach($item in $invariants.cases){if(-not $item.success -or $item.required_chunks -ne 50 -or $item.halo_chunks -ne 162 -or $item.compared_values -ne 10649600){throw 'Lighting scope/equality differs'}}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    try{
        $after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after)
        if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Inputs changed during batch')}
        foreach($artifact in $artifacts){if($artifact.sha256 -ne (Get-FileHash -LiteralPath $artifact.path).Hash){$issues.Add('Production artifact changed during offline inspection')}}
    }catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()};$env:JAVA_HOME=$savedJava;$env:Path=$savedPath
    @{schema='worldgen-assist.saved-lighting-gate.v1';success=($issues.Count -eq 0);root=$root;reuse_parent=$GateRoot;junit=$junit;artifacts=$artifacts;steps=$steps;inputs=$records;issues=@($issues);scope='One new offline reader test and own-block original light recomputation on 50 interior chunks/162 saved halo chunks per ordinary condition. No production build/new world run; not cross-world decoration or general beta proof.'}|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Output "SAVED_LIGHTING_GATE success=$($issues.Count -eq 0) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
