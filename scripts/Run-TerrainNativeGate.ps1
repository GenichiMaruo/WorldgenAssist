[CmdletBinding()]
param([switch]$Execute,[Parameter(Mandatory)][string]$BuildEvidence,[string]$ReuseForgeRuntime)
# One sequential native batch after the physical performance batch is terminal.
# It reuses exact built JARs; no Gradle/JUnit or broad native matrix.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$evidenceBase=Join-Path $workspace 'test-artifacts'
function Evidence-Child([string]$Path) {
    $resolved=[IO.Path]::GetFullPath($Path)
    if(-not $resolved.StartsWith($evidenceBase+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'Expected a workspace evidence child'}
    return $resolved
}
$original=Evidence-Child $BuildEvidence
$reusedForge=$null
if($ReuseForgeRuntime){$ReuseForgeRuntime=Evidence-Child $ReuseForgeRuntime}
if(-not $Execute){[ordered]@{build_evidence=$original;tests='REUSED with original source/JAR identity';runtime=@('forge vanilla/assisted two owners','neoforge vanilla/assisted two owners');comparison='All shared and actually applied terrain digests; both owners and dependency dispatch';performance='NOT_RUN: native correctness only'}|ConvertTo-Json;exit 0}
$root=Join-Path $evidenceBase ('terrain-native-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$lock=$null;$steps=@();$issues=[Collections.Generic.List[string]]::new()
$savedMods=[Collections.Generic.List[object]]::new();$deployed=[Collections.Generic.List[string]]::new()
function Source-Manifest {
    $files=@(Get-ChildItem -LiteralPath (Join-Path $workspace 'src') -File -Recurse)
    foreach($name in @('build.gradle','settings.gradle','gradle.properties','loaders/forge/build.gradle',
        'loaders/neoforge/build.gradle','loaders/forge/src/main/resources/worldgen_assist.mixins.json',
        'scripts/Get-WorldgenArtifact.ps1','scripts/Run-TerrainNativeGate.ps1',
        'scripts/Run-InstalledNativeLoaderScenario.ps1','scripts/New-InstalledNativeLoaderClient.ps1',
        'scripts/Compare-LocalLoaderDigests.ps1')) { $files+=Get-Item -LiteralPath (Join-Path $workspace $name) }
    return @($files|Sort-Object FullName|ForEach-Object { (Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/') })
}
function Step([string]$Name,[string[]]$Arguments,[int]$Seconds=1200) {
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=(Get-Command pwsh.exe).Source
    $info.WorkingDirectory=$workspace;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in @('-NoProfile','-ExecutionPolicy','Bypass')+$Arguments){[void]$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try {
        if(-not $process.Start()){throw "Could not start $Name"}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        $finished=$process.WaitForExit($Seconds*1000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$stderr.GetAwaiter().GetResult())
        $result=[ordered]@{name=$Name;success=($finished -and $process.ExitCode -eq 0);exit_code=$process.ExitCode}
        Write-Host "TERRAIN_NATIVE_STEP name=$Name success=$($result.success)"
        return $result
    } finally {$process.Dispose()}
}
try {
    $lock=[IO.File]::Open((Join-Path $evidenceBase 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $prior=Get-Content -LiteralPath (Join-Path $original 'summary.json') -Raw|ConvertFrom-Json
    if(-not $prior.success -or $prior.junit.failures -or $prior.junit.errors -or $prior.junit.skipped -or
        $prior.remote_work_kind -notin @('decisions','complete')) {throw 'Original build/runtime evidence is incomplete'}
    $before=Source-Manifest
    [IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    $originalSources=@{}
    foreach($line in Get-Content -LiteralPath (Join-Path $original 'correctness/assisted/source-manifest-before.sha256')) {
        if($line -match '^([A-F0-9]{64})  (src/.+|build\.gradle|settings\.gradle|gradle\.properties)$') {$originalSources[$Matches[2]]=$Matches[1]}
    }
    $currentSources=@($before|Where-Object {$_ -match '^([A-F0-9]{64})  (src/.+|build\.gradle|settings\.gradle|gradle\.properties)$'})
    if($originalSources.Count -ne $currentSources.Count){throw 'Source inventory differs from original build'}
    foreach($line in $currentSources){[void]($line -match '^([A-F0-9]{64})  (.+)$');if($originalSources[$Matches[2]] -ne $Matches[1]){throw "Source changed: $($Matches[2])"}}
    if($ReuseForgeRuntime){
        $savedGate=Get-Content -LiteralPath (Join-Path $ReuseForgeRuntime 'summary.json') -Raw|ConvertFrom-Json
        if($savedGate.build_evidence -ne $original){throw 'Reused Forge runtime used different build evidence'}
        foreach($mode in @('vanilla','assisted')){
            $savedStep=@($savedGate.steps|Where-Object {$_.name -eq "forge-$mode" -and $_.success})
            if($savedStep.Count -ne 1){throw "No successful original Forge $mode runtime"}
        }
        # Only this orchestration/analysis code may differ. Game, loader,
        # launch helper and actual scenario inputs must retain their identity.
        $excluded='  scripts/(Run-TerrainNativeGate|Compare-LocalLoaderDigests)\.ps1$'
        $savedManifest=@(Get-Content -LiteralPath (Join-Path $ReuseForgeRuntime 'source-manifest-before.sha256')|Where-Object {$_ -notmatch $excluded})
        $currentManifest=@($before|Where-Object {$_ -notmatch $excluded})
        if(($savedManifest -join "`n") -cne ($currentManifest -join "`n")){throw 'Original Forge game/loader/launch/scenario source differs'}
        $reusedForge=[ordered]@{root=$ReuseForgeRuntime;original_gate_success=$savedGate.success;original_issues=$savedGate.issues;
            runtime_inputs_match_current=$true;runtime_fresh=$false;comparison_fresh=$true;
            source_scope='Original start snapshot equals current runtime inputs; original overall batch had no end snapshot because comparison stopped it'}
    }
    $assets=(Get-Content -LiteralPath (Join-Path $original 'correctness/assisted/assets-root.txt') -Raw).Trim()
    $assets=Evidence-Child $assets
    foreach($loader in @('forge','neoforge')) {
        $installed=Evidence-Child (Join-Path $evidenceBase $(if($loader -eq 'forge'){'port26.3-forge-installed'}else{'port26.3-neo-installed'}))
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $loader
        $sha=(Get-FileHash -LiteralPath $artifact.Path).Hash
        $saved=@($prior.native_artifacts|Where-Object loader -eq $loader)
        if($saved.Count -ne 1 -or $saved[0].sha256 -ne $sha -or -not $saved[0].metadata_matches){throw "$loader original artifact mismatch"}
        Copy-Item -LiteralPath $artifact.Path -Destination (Join-Path $root $artifact.FileName)
        $mods=Evidence-Child (Join-Path $installed 'server/mods')
        $backup=Join-Path $root "saved-server-mods/$loader"
        New-Item -ItemType Directory -Force -Path $backup|Out-Null
        if(-not ($loader -eq 'forge' -and $reusedForge)){
        foreach($old in Get-ChildItem -LiteralPath $mods -File -Filter 'worldgen-assist-*.jar') {
            $source=Evidence-Child $old.FullName;$target=Evidence-Child (Join-Path $backup $old.Name)
            Move-Item -LiteralPath $source -Destination $target
            $savedMods.Add([pscustomobject]@{original=$source;backup=$target})
        }
        $target=Evidence-Child (Join-Path $mods $artifact.FileName)
        Copy-Item -LiteralPath $artifact.Path -Destination $target;$deployed.Add($target)
        }
        foreach($mode in @('vanilla','assisted')) {
            $case=Join-Path $root "$loader/$mode"
            if($loader -eq 'forge' -and $reusedForge){
                $savedCase=Join-Path $ReuseForgeRuntime "forge/$mode"
                New-Item -ItemType Directory -Force -Path $case|Out-Null
                foreach($name in @('latest.log','result.json')){
                    $savedFile=Join-Path $savedCase $name;$copy=Join-Path $case $name
                    Copy-Item -LiteralPath $savedFile -Destination $copy
                    if((Get-FileHash -LiteralPath $savedFile).Hash -ne (Get-FileHash -LiteralPath $copy).Hash){throw 'Original Forge evidence copy differs'}
                }
                $steps+=[ordered]@{name="forge-$mode";success=$true;status='REUSED';root=$savedCase}
            }else{
            $scenarioArguments=@('-File',(Join-Path $PSScriptRoot 'Run-InstalledNativeLoaderScenario.ps1'),
                '-Loader',$loader,'-Mode',$mode,'-Players','2','-InstalledRoot',$installed,'-AssetsRoot',$assets,
                '-OutputRoot',$case,'-TerrainDecisions')
            if($prior.remote_work_kind -eq 'complete'){$scenarioArguments+='-CompleteTerrain'}
            $steps+=Step "$loader-$mode" $scenarioArguments
            if(-not $steps[-1].success){throw "Native scenario failed: $loader/$mode"}
            }
            $result=Get-Content -LiteralPath (Join-Path $case 'result.json') -Raw|ConvertFrom-Json
            if(-not $result.success -or -not $result.cleanup_safe -or -not $result.loopback_only -or
                -not $result.terrain_decisions -or $result.mod_sha256 -ne $sha){throw 'Native identity/cleanup/profile mismatch'}
            if($prior.remote_work_kind -eq 'complete' -and -not $result.complete_terrain){throw 'Native complete terrain selection missing'}
        }
        $assistedLog=Join-Path $root "$loader/assisted/latest.log"
        $text=Get-Content -LiteralPath $assistedLog -Raw
        $expectedHint=if($prior.remote_work_kind -eq 'complete'){'(?:terrain_stage|task_dependency)'}elseif($prior.test_profile -in @('admission','capacity')){'terrain_stage'}else{'task_dependency'}
        if($text -notmatch ('job\.sent .*hint='+$expectedHint+' candidate_age_ms=')){throw "$loader did not dispatch actual $expectedHint hints"}
        $comparisonArguments=@('-File',(Join-Path $PSScriptRoot 'Compare-LocalLoaderDigests.ps1'),
            '-AssistedLog',$assistedLog,'-VanillaLog',(Join-Path $root "$loader/vanilla/latest.log"),
            '-OutFile',(Join-Path $root "$loader/comparison.json"),'-TerrainDecisions','-AfterMarker','CAWG_NATIVE_DECISIONS_BEGIN')
        if($prior.remote_work_kind -eq 'complete'){$comparisonArguments+='-CompleteTerrain'}
        $steps+=Step "$loader-comparison" $comparisonArguments 300
        if(-not $steps[-1].success){throw "$loader terrain mismatch"}
    }
    $after=Source-Manifest
    [IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after)
    if(($before -join "`n") -cne ($after -join "`n")){throw 'Native source/harness changed during batch'}
} catch {$issues.Add($_.Exception.ToString())}
finally {
    foreach($path in $deployed){try {if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath (Evidence-Child $path)}}catch{$issues.Add("Deployment cleanup: $($_.Exception.Message)")}}
    foreach($entry in $savedMods){try {Move-Item -LiteralPath (Evidence-Child $entry.backup) -Destination (Evidence-Child $entry.original)}catch{$issues.Add("Fixture mod restore: $($_.Exception.Message)")}}
    if($lock){$lock.Dispose()}
    $summary=[ordered]@{schema='worldgen-assist.terrain-native-gate.v1';success=($issues.Count -eq 0);root=$root;
        build_evidence=$original;units_and_builds_fresh=$false;runtime_fresh=if($reusedForge){'MIXED: Forge reused, NeoForge fresh'}else{$true};reused_forge_runtime=$reusedForge;players=2;dimension='overworld';
        scope='Native decision application/dependency hints/terrain parity; local correctness, no native or physical-server speed claim';steps=$steps;issues=@($issues)}
    $path=Join-Path $root 'summary.json';[IO.File]::WriteAllText($path,($summary|ConvertTo-Json -Depth 6))
    Write-Output "TERRAIN_NATIVE_GATE_COMPLETE summary=$path success=$($summary.success)"
}
if($issues.Count){exit 1}
