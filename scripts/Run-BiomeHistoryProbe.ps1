[CmdletBinding()]
param([switch]$Execute,[ValidateSet('original','biome-only')][string]$Coordinate='original',[ValidateSet('plain','cache-carvers')][string]$HistoryProfile='plain')
# One offline original-code hypothesis probe. No JUnit/build/world rerun.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$centerX=if($Coordinate -eq 'biome-only'){-2051}else{-2012};$centerZ=if($Coordinate -eq 'biome-only'){3027}else{3041}
if(-not $Execute){@{junit_methods=0;builds=0;terrain_chunks=0;biome_histories=4;history_profile=$HistoryProfile;biome_target_chunks_per_history=9;biome_warm_chunks_per_history=4;center="$centerX,$centerZ";seed=8675309}|ConvertTo-Json;exit 0}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$base=Join-Path $workspace 'test-artifacts'
$root=Join-Path $base ('biome-history-probe-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'));New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$before=@();$lock=$null;$artifact=$null;$result=$null;$step=$null
$savedJava=$env:JAVA_HOME;$savedPath=$env:Path
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
    foreach($arg in @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'inspectBiomeHistory',('-PbiomeHistoryOutput='+(Join-Path $root 'biome-history.json')),"-PbiomeHistoryX=$centerX","-PbiomeHistoryZ=$centerZ","-PbiomeHistoryProfile=$HistoryProfile")){[void]$info.ArgumentList.Add($arg)}
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
    if(-not $result.success -or $result.histories.Count -ne 4 -or $result.target_quart_voxels_per_history -ne 13824 -or $result.history_profile -ne $HistoryProfile){throw 'Narrow biome probe scope incomplete'}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    try{$after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after);if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Inputs changed during probe')};if($artifact -and $artifact.sha256 -ne (Get-FileHash -LiteralPath $artifact.path).Hash){$issues.Add('Production JAR changed during offline probe')}}catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()};$env:JAVA_HOME=$savedJava;$env:Path=$savedPath
    @{schema='worldgen-assist.biome-history-batch.v1';success=($issues.Count -eq 0);root=$root;artifact=$artifact;step=$step;result=$result;issues=@($issues);scope='Offline original biome history probe only. Synthetic tie witness plus exact failed window,not actual failed payload or full terrain correctness proof.'}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Output "BIOME_HISTORY_PROBE success=$($issues.Count -eq 0) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
