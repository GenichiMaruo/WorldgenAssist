[CmdletBinding()]
param([switch]$Execute,[Parameter(Mandatory)][string]$BuildEvidence)
# Dev.15 confirmation only: unchanged units/builds, reverse physical pair,
# then both fresh native pairs. Children take the shared runtime lock in sequence.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base=Join-Path $workspace 'test-artifacts'
function Evidence-Child([string]$Path){
    $resolved=[IO.Path]::GetFullPath($Path)
    if(-not $resolved.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Workspace evidence child required'}
    return $resolved
}
$original=Evidence-Child $BuildEvidence
if(-not $Execute){
    [ordered]@{build_evidence=$original;new_units=0;new_builds=0;
        sequence=@('same-artifact vanilla-first weak E-server view32 pair,warm1/repeats3',
            'fresh Forge two-owner vanilla/assisted parity','fresh NeoForge two-owner vanilla/assisted parity');
        native_scope='Local view4 correctness,not weak-server performance';beta_claim=$false}|ConvertTo-Json -Depth 4
    exit 0
}
$root=Join-Path $base ('indexed-terrain-confirmation-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=[Collections.Generic.List[object]]::new()
$artifacts=[Collections.Generic.List[object]]::new();$before=@();$lock=$null;$performancePath=$null;$nativePath=$null
function Manifest{
    $files=@()
    foreach($directory in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){
        $files+=Get-ChildItem -LiteralPath (Join-Path $workspace $directory) -File -Recurse
    }
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){
        $files+=Get-Item -LiteralPath (Join-Path $workspace $name)
    }
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Step([string]$Name,[string]$Script,[string[]]$Arguments,[int]$Seconds){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=(Get-Command pwsh.exe).Source
    $info.WorkingDirectory=$workspace;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot $Script))+$Arguments){[void]$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(-not $process.Start()){throw "Could not start $Name"}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        $finished=$process.WaitForExit($Seconds*1000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        $output=$stdout.GetAwaiter().GetResult()
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$output)
        [IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$stderr.GetAwaiter().GetResult())
        $steps.Add([ordered]@{name=$Name;success=($finished -and $process.ExitCode -eq 0);exit_code=$process.ExitCode})
        Write-Host "INDEXED_CONFIRMATION_STEP name=$Name success=$($steps[-1].success)"
        if(-not $steps[-1].success){throw "Step failed: $Name (retained logs)"}
        return $output
    }finally{$process.Dispose()}
}
try{
    $lock=[IO.File]::Open((Join-Path $base 'indexed-terrain-confirmation.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $preflightLock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try{
        $prior=Get-Content -LiteralPath (Join-Path $original 'summary.json') -Raw|ConvertFrom-Json
        if(-not $prior.success -or $prior.test_profile -ne 'complete-prefetch-index' -or $prior.junit.tests -ne 5 -or
            $prior.junit.failures -or $prior.junit.errors -or $prior.junit.skipped -or
            $prior.remote_work_kind -ne 'complete' -or $prior.complete_verification -ne 'peer' -or
            $prior.remote_application_profile -ne 'overlap' -or $prior.window_profile -ne 'deep' -or
            $prior.prefetch_lookahead -ne 0 -or $prior.condition_order -ne 'assisted-first'){throw 'Successful dev15 parent evidence required'}
        $expectedTests=@{
            'GenerationPrefetch263Test'=@('cachedOrderingStillExpiresReassignsAndBalancesAfterEveryRemoval()',
                'nearerHintReplacesOnlyItsOwnersFartherHintAtBalancedCapacity()',
                'boundedCandidatesDeduplicateAndInterleaveOwnersAndDiscardObsoleteDemand()',
                'largeReservoirKeepsBoundedPrefixesStableAcrossExpiryMovementAndOwnerCleanup()');
            'RemoteWindow263Test'=@('completeHintPolicyKeepsJobDeadlinesSeparateAndRequiresExplicitCompleteChoice()')
        }
        foreach($class in $expectedTests.Keys){
            [xml]$xml=Get-Content -LiteralPath (Join-Path $original "TEST-io.github.genichimaruo.worldgenassist.server.$class.xml")
            if([int]$xml.testsuite.tests -ne $expectedTests[$class].Count -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or
                ((@($xml.testsuite.testcase|ForEach-Object name|Sort-Object)-join '|') -cne (($expectedTests[$class]|Sort-Object)-join '|'))){throw 'Original affected methods incomplete'}
        }
        $oldSources=@{}
        foreach($line in Get-Content -LiteralPath (Join-Path $original 'correctness/assisted/source-manifest-before.sha256')){
            if($line -match '^([A-F0-9]{64})  (src/.+|gradle\.properties|build\.gradle|settings\.gradle)$'){$oldSources[$Matches[2]]=$Matches[1]}
        }
        $before=Manifest
        $currentSources=@($before|Where-Object {$_ -match '^([A-F0-9]{64})  (src/.+|gradle\.properties|build\.gradle|settings\.gradle)$'})
        if($oldSources.Count -ne $currentSources.Count){throw 'Production source inventory changed'}
        foreach($line in $currentSources){[void]($line -match '^([A-F0-9]{64})  (.+)$');if($oldSources[$Matches[2]] -ne $Matches[1]){throw "Production source changed: $($Matches[2])"}}
        # Physical parent records common inputs. Native inputs were unchanged in
        # the verified dev15 commit; require those exact files as well.
        & git -c "safe.directory=$workspace" diff --exit-code f074234 -- loaders/forge/src loaders/neoforge/src loaders/forge/build.gradle loaders/neoforge/build.gradle
        if($LASTEXITCODE){throw 'Native source differs from the verified dev15 commit'}
        foreach($loader in @('fabric','forge','neoforge')){
            $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $loader
            if($artifact.Version -ne '0.1.0-alpha.8-dev.15+mc26.3'){throw 'Expected unchanged dev15 version'}
            $sha=(Get-FileHash -LiteralPath $artifact.Path).Hash
            $expected=if($loader -eq 'fabric'){$prior.artifact_sha256}else{($prior.native_artifacts|Where-Object loader -eq $loader).sha256}
            if($sha -ne $expected -or (Get-FileHash -LiteralPath (Join-Path $original $artifact.FileName)).Hash -ne $sha){throw 'Exact original artifact identity missing'}
            $artifacts.Add([ordered]@{loader=$loader;path=$artifact.Path;sha256=$sha})
        }
        $originalPerformance=Get-Content -LiteralPath $prior.performance_summary -Raw|ConvertFrom-Json
        if($originalPerformance.status -ne 'COMPLETE' -or $originalPerformance.issues.Count -or $originalPerformance.artifact_sha256 -ne $prior.artifact_sha256 -or
            $originalPerformance.view_distance -ne 32 -or $originalPerformance.condition_order -ne 'assisted-first'){throw 'Original physical pair incomplete'}
        [IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    }finally{$preflightLock.Dispose()}
    $output=Step 'reverse-performance' 'Run-ConstrainedServerBenchmark.ps1' @('-Execute','-PhysicalServer','-ServerFlightRecording','-ViewDistance','32',
        '-MeasureFullView','-QuietRemoteTrace','-AssistedNoiseBackend','cooperative','-VanillaNoiseBackend','cooperative','-WindowProfile','deep',
        '-ConditionOrder','vanilla-first','-RemoteApplicationProfile','overlap','-PrefetchLookahead','0','-RemoteWorkKind','complete','-CompleteVerification','peer') 7200
    if($output -notmatch '(?m)^CONSTRAINED_SERVER_BENCHMARK status=COMPLETE summary=(.+)\r?$'){throw 'Reverse performance summary missing'}
    $performancePath=Evidence-Child $Matches[1].Trim()
    $reverse=Get-Content -LiteralPath $performancePath -Raw|ConvertFrom-Json
    $oldPair=@($originalPerformance.results|Where-Object mode -eq 'pair');$newPair=@($reverse.results|Where-Object mode -eq 'pair')
    if($reverse.status -ne 'COMPLETE' -or $reverse.issues.Count -or $reverse.artifact_sha256 -ne $prior.artifact_sha256 -or
        $reverse.condition_order -ne 'vanilla-first' -or $newPair.Count -ne 1 -or $oldPair.Count -ne 1 -or
        $newPair[0].coordinates_sha256 -ne $oldPair[0].coordinates_sha256){throw 'Reverse pair identity/coordinates incomplete'}
    $coverage=Join-Path (Split-Path $performancePath) 'u/analysis/complete-application-coverage.json'
    [void](Step 'reverse-coverage' 'Measure-CompleteTerrainCoverage.ps1' @('-LogPath',(Join-Path (Split-Path $performancePath) 'u/assisted/remote-evidence/latest.log'),
        '-OutputPath',$coverage,'-ArtifactSha256',$prior.artifact_sha256) 300)
    $output=Step 'fresh-native-pairs' 'Run-TerrainNativeGate.ps1' @('-Execute','-BuildEvidence',$original) 5400
    if($output -notmatch '(?m)^TERRAIN_NATIVE_GATE_COMPLETE summary=(.+) success=True\r?$'){throw 'Native summary missing'}
    $nativePath=Evidence-Child $Matches[1].Trim()
    $native=Get-Content -LiteralPath $nativePath -Raw|ConvertFrom-Json
    if(-not $native.success -or $native.issues.Count -or $native.runtime_fresh -ne $true -or $native.build_evidence -ne $original){throw 'Fresh native pair evidence incomplete'}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    try{
        $after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after)
        if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Inputs changed during confirmation')}
        foreach($artifact in $artifacts){if((Get-FileHash -LiteralPath $artifact.path).Hash -ne $artifact.sha256){$issues.Add('Artifact changed during confirmation')}}
    }catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()}
    $summary=[ordered]@{schema='worldgen-assist.indexed-terrain-confirmation.v1';success=($issues.Count -eq 0);root=$root;
        build_evidence=$original;units_and_builds_fresh=$false;original_methods=5;artifacts=$artifacts.ToArray();
        reverse_performance=$performancePath;fresh_native=$nativePath;steps=$steps.ToArray();issues=$issues.ToArray();beta_claim=$false;
        scope='Same dev15 artifacts,reverse-order natural weak E-server/view32 comparison and fresh local native correctness. No new production or unchanged unit/build rerun.'}
    [IO.File]::WriteAllText((Join-Path $root 'summary.json'),($summary|ConvertTo-Json -Depth 7))
    Write-Output "INDEXED_TERRAIN_CONFIRMATION success=$($summary.success) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
