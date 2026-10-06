[CmdletBinding()]
param([switch]$Execute,[ValidateSet('original','biome-only','publication')][string]$Coordinate='original',[ValidateSet('plain','cache-carvers','tie-fitness')][string]$HistoryProfile='plain',[string]$SavedRegionDirectory)
# One offline original-code hypothesis probe. No JUnit/build/world rerun.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$centerX=if($Coordinate -eq 'publication'){-2013}elseif($Coordinate -eq 'biome-only'){-2051}else{-2012};$centerZ=if($Coordinate -eq 'publication'){3043}elseif($Coordinate -eq 'biome-only'){3027}else{3041}
if(-not $Execute){@{junit_methods=0;builds=0;terrain_chunks=0;biome_histories=$(if($HistoryProfile -eq 'tie-fitness'){0}else{4});history_profile=$HistoryProfile;biome_target_chunks_per_history=$(if($HistoryProfile -eq 'tie-fitness'){0}else{9});biome_warm_chunks_per_history=$(if($HistoryProfile -eq 'tie-fitness'){0}else{4});fitness_target_quart_voxels=$(if($HistoryProfile -eq 'tie-fitness'){1}else{0});center="$centerX,$centerZ";seed=8675309}|ConvertTo-Json;exit 0}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$base=Join-Path $workspace 'test-artifacts'
$root=Join-Path $base ('biome-history-probe-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'));New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$before=@();$lock=$null;$artifact=$null;$result=$null;$step=$null
$savedJava=$env:JAVA_HOME;$savedPath=$env:Path
$savedInputs=@()
if($SavedRegionDirectory){
    $SavedRegionDirectory=(Resolve-Path -LiteralPath $SavedRegionDirectory).Path
    if($Coordinate -ne 'publication' -or -not $SavedRegionDirectory.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned saved publication window required'}
    $savedInputs=@(Get-ChildItem -LiteralPath $SavedRegionDirectory -Filter '*.mca' -File|ForEach-Object {@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
    if($savedInputs.Count -ne 1 -or $savedInputs[0].name -ne 'r.-63.95.mca'){throw 'Exactly one known saved region required'}
}
function Manifest {
    $files=@();foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -Recurse -File}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
try{
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $before=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    Copy-Item -LiteralPath $PSCommandPath -Destination $root;Copy-Item -LiteralPath (Join-Path $workspace 'src/portTest/java/io/github/genichimaruo/worldgenassist/server/BiomeHistoryInspector263.java') -Destination $root
    $built=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader fabric
    $artifact=@{path=$built.Path;sha256=(Get-FileHash -LiteralPath $built.Path).Hash;version=$built.Version}
    $env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4';$env:Path="$env:JAVA_HOME\bin;$env:Path"
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName="$env:SystemRoot\System32\cmd.exe";$info.WorkingDirectory=$workspace
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($arg in @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'inspectBiomeHistory','--no-daemon',('-PbiomeHistoryOutput='+(Join-Path $root 'biome-history.json')),"-PbiomeHistoryX=$centerX","-PbiomeHistoryZ=$centerZ","-PbiomeHistoryProfile=$HistoryProfile","-PbiomeHistorySavedRegion=$SavedRegionDirectory")){[void]$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(-not $process.Start()){throw 'Could not start original biome probe'}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();$finished=$process.WaitForExit(180000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root 'probe-out.log'),$stdout.GetAwaiter().GetResult());[IO.File]::WriteAllText((Join-Path $root 'probe-err.log'),$stderr.GetAwaiter().GetResult())
        $step=@{success=($finished -and $process.ExitCode -eq 0);exit_code=$process.ExitCode;timed_out=(-not $finished)}
        if(-not $step.success){throw 'Original biome probe failed; retained logs'}
    }finally{$process.Dispose()}
    $result=Get-Content -LiteralPath (Join-Path $root 'biome-history.json') -Raw|ConvertFrom-Json
    if($HistoryProfile -eq 'tie-fitness'){
        if(-not $result.success -or $result.history_profile -ne $HistoryProfile -or ($result.coordinate -join ',') -ne '-8048,-16,12168' -or $result.minimum_biomes.Count -lt 1){throw 'Exact target fitness evidence required'}
    }elseif(-not $result.success -or $result.histories.Count -ne 4 -or $result.target_quart_voxels_per_history -ne 13824 -or $result.history_profile -ne $HistoryProfile){throw 'Narrow biome probe scope incomplete'}
    if($SavedRegionDirectory -and $HistoryProfile -ne 'tie-fitness' -and @($result.histories|Where-Object {$null -eq $_.saved_changed_voxels}).Count){throw 'Complete saved voxel comparison required'}
    if($SavedRegionDirectory -and $savedInputs[0].sha256 -ne (Get-FileHash -LiteralPath (Join-Path $SavedRegionDirectory $savedInputs[0].name)).Hash){throw 'Immutable saved biome input required'}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    try{$after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after);if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Inputs changed during probe')};if($artifact -and $artifact.sha256 -ne (Get-FileHash -LiteralPath $artifact.path).Hash){$issues.Add('Production JAR changed during offline probe')}}catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()};$env:JAVA_HOME=$savedJava;$env:Path=$savedPath
    @{schema='worldgen-assist.biome-history-batch.v1';success=($issues.Count -eq 0);root=$root;artifact=$artifact;step=$step;result=$result;saved_region=$SavedRegionDirectory;saved_inputs=$savedInputs;issues=@($issues);scope='Offline original biome history probe only. Optional retained saved quart values; synthetic tie/history witness is not actual failed payload or full terrain correctness proof.'}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Output "BIOME_HISTORY_PROBE success=$($issues.Count -eq 0) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
