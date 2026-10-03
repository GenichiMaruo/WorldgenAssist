[CmdletBinding()]
param([switch]$Execute,[switch]$LocalOnly,[string]$BuildEvidenceRoot='',
    [ValidateRange(2,32)][int]$ViewDistance=10)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testClasses=@('io.github.genichimaruo.worldgenassist.server.GenerationLookahead263Test')
if(-not $Execute){
    [ordered]@{test_classes=$testClasses;builds=@('Fabric','Forge','NeoForge');correctness='Overworld two owners, two logical server CPUs, eight secret terrain-grid points; JVM availableProcessors=2; two bounded terrain workers, independent scoped results, eight point verification; both conditions use cooperative backend';performance=if($LocalOnly){'NOT_RUN'}else{"matched full-view distance $ViewDistance pair; warmup plus three fresh repeats"};sequence='implementation complete, then one sequential batch'}|ConvertTo-Json
    exit 0
}
$root=Join-Path $workspace ('test-artifacts/generation-lookahead-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $root|Out-Null
$oldJava=$env:JAVA_HOME;$oldPath=$env:Path
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4';$env:Path="$env:JAVA_HOME\bin;$env:Path"
$steps=@();$issues=[Collections.Generic.List[string]]::new();$lock=$null;$hash=$null;$junit=$null;$performancePath=$null;$nativeArtifacts=@()
$pwsh=(Get-Command pwsh.exe).Source
function Step([string]$Name,[string]$Executable,[string[]]$Arguments,[int]$Seconds){
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Executable;$info.WorkingDirectory=$workspace;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
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
        Write-Host "GENERATION_LOOKAHEAD_STEP name=$Name status=$($record.status)"
        return $record
    }finally{$process.Dispose()}
}
try{
    $lock=[IO.File]::Open((Join-Path $workspace 'test-artifacts/validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    foreach($name in @('Run-GenerationLookaheadGate.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1','Compare-WorldgenScenarioMatrix.ps1','Run-ConstrainedServerBenchmark.ps1')){
        $tokens=$null;$parseErrors=$null
        [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$parseErrors)
        if($parseErrors.Count){throw "$name syntax: $(($parseErrors.Message)-join '; ')"}
    }
    if($BuildEvidenceRoot){
        $reusePath=[IO.Path]::GetFullPath($BuildEvidenceRoot)
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1')
        $reuse=Get-Content -LiteralPath (Join-Path $reusePath 'summary.json') -Raw|ConvertFrom-Json
        if($reuse.schema -ne 'worldgen-assist.generation-lookahead-gate.v1' -or $reuse.artifact_sha256 -ne (Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash){throw 'Reuse build evidence artifact/schema mismatch'}
        if($reuse.junit.tests -ne 2 -or $reuse.junit.failures -or $reuse.junit.errors -or $reuse.junit.skipped){throw 'Reuse JUnit evidence incomplete'}
        foreach($name in @('unit-build','forge-build','neoforge-build')){
            $previous=@($reuse.steps|Where-Object {$_.name -eq $name -and $_.status -eq 'PASSED'})
            if($previous.Count -ne 1){throw "No successful original build evidence: $name"}
            $steps+=[ordered]@{name=$name;status='REUSED';evidence_root=$reusePath}
        }
        $junit=$reuse.junit
    }else{
    $began=Get-Date
    $buildArguments=@('/d','/c',(Join-Path $workspace 'gradlew.bat'),'test','--rerun-tasks')
    foreach($testClass in $testClasses){$buildArguments+=@('--tests',$testClass)}
    $buildArguments+='build'
    $steps+=Step 'unit-build' "$env:SystemRoot\System32\cmd.exe" $buildArguments 1800
    if($steps[-1].status -ne 'PASSED'){throw 'Focused unit/build step failed'}
    $junit=[ordered]@{tests=0;failures=0;errors=0;skipped=0}
    foreach($testClass in $testClasses){
        $xmlPath=Join-Path $workspace "build/test-results/test/TEST-$testClass.xml"
        if((Get-Item -LiteralPath $xmlPath).LastWriteTime -lt $began){throw 'JUnit evidence is stale'}
        Copy-Item -LiteralPath $xmlPath -Destination (Join-Path $root "TEST-$testClass.xml")
        [xml]$xml=Get-Content -LiteralPath $xmlPath
        foreach($field in @('tests','failures','errors','skipped')){$junit[$field]+=[int]$xml.testsuite.GetAttribute($field)}
    }
    if($junit.tests -ne 2 -or $junit.failures -or $junit.errors -or $junit.skipped){throw 'Focused JUnit coverage incomplete'}
    foreach($loader in @('forge','neoforge')){
        $steps+=Step "$loader-build" "$env:SystemRoot\System32\cmd.exe" @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'-p',(Join-Path $workspace "loaders/$loader"),'build','-x','test') 1800
        if($steps[-1].status -ne 'PASSED'){throw "$loader build failed"}
    }
    }
    $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1')
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach($nativeLoader in @('forge','neoforge')){
        $native=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $nativeLoader
        $archive=[IO.Compression.ZipFile]::OpenRead($native.Path)
        try{
            $descriptor=if($nativeLoader -eq 'forge'){'META-INF/mods.toml'}else{'META-INF/neoforge.mods.toml'}
            $entry=$archive.GetEntry($descriptor)
            if($null -eq $entry){throw "Missing loader metadata: $descriptor"}
            $reader=[IO.StreamReader]::new($entry.Open())
            try{$metadata=$reader.ReadToEnd()}finally{$reader.Dispose()}
            if($metadata -notmatch ('(?m)^version="'+[regex]::Escape($artifact.Version)+'"\s*$')){throw 'Native mod version differs from shared version'}
        }finally{$archive.Dispose()}
        Copy-Item -LiteralPath $native.Path -Destination $root
        Copy-Item -LiteralPath $native.SourcesPath -Destination $root
        $nativeArtifacts += [ordered]@{loader=$nativeLoader;version=$native.Version;sha256=(Get-FileHash -LiteralPath $native.Path).Hash;metadata_matches=$true}
    }
    $hash=(Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash
    Copy-Item -LiteralPath $artifact.Path -Destination $root
    Get-ChildItem -LiteralPath (Join-Path $workspace 'build/libs') -File -Filter '*sources.jar' | Where-Object {$_.Name -eq ([IO.Path]::GetFileNameWithoutExtension($artifact.Path)+'-sources.jar')} | Copy-Item -Destination $root
    if(-not $LocalOnly){
        $correctRoot=Join-Path $root 'correctness'
        New-Item -ItemType Directory -Force -Path $correctRoot|Out-Null
        $cases=@()
        foreach($mode in @('vanilla','assisted')){
            $directory=Join-Path $correctRoot $mode
            $requestedBackend='cooperative'
            $steps+=Step "correctness-$mode" $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-Dimension','overworld','-Mode',$mode,'-Players','2','-Purpose','correctness','-CacheEntries','128','-Prediction','false','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','2','-ServerJvmProcessors','2','-CorrectnessDemandWaitMs','100','-NoiseBackend',$requestedBackend,'-OutputRoot',$directory) 1200
            if($steps[-1].status -ne 'PASSED'){throw "Affected runtime failed: $mode"}
            $result=Get-Content -LiteralPath (Join-Path $directory 'scenario-result.json') -Raw|ConvertFrom-Json
            if(-not $result.success -or -not $result.cleanup_safe -or $result.artifact_sha256 -ne $hash){throw 'Runtime cleanup or artifact identity mismatch'}
            $cases += [ordered]@{id="correctness-overworld-$mode-p2-cache";dimension='overworld';mode=$mode;players=2;purpose='correctness';cache_entries=128;prediction=$false;validation_cells=8}
        }
        [IO.File]::WriteAllText((Join-Path $correctRoot 'matrix-plan.json'),([ordered]@{runtime_and_performance_cases=$cases}|ConvertTo-Json -Depth 5))
        $steps+=Step 'correctness-comparison' $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'),'-RunRoot',$correctRoot) 300
        $comparison=Get-Content -LiteralPath (Join-Path $correctRoot 'analysis/scenario-matrix-analysis.json') -Raw|ConvertFrom-Json
        if($steps[-1].status -ne 'PASSED' -or $comparison.status -ne 'COMPLETE'){throw 'Correctness comparison incomplete'}
        $surfaceLogs=@(Get-ChildItem -LiteralPath (Join-Path $correctRoot 'assisted') -Recurse -File -Filter '*log' | ForEach-Object {Get-Content -LiteralPath $_.FullName} | Where-Object {$_ -match 'job.complete .*work_kind=GRID_AND_SURFACE remote_samples=[1-9][0-9]*'})
        if($surfaceLogs.Count -lt 2){throw 'Runtime did not prove terrain-grid consumption'}
        foreach($owner in 0..1){
            $clientLog=Get-Content -LiteralPath (Join-Path $correctRoot "assisted/clients/owner-$owner/client/logs/latest.log") -Raw
            if($clientLog -notmatch 'job\.client_dispatch .*request_path=network'){throw "No direct network request execution for owner $owner"}
        }
        [IO.File]::WriteAllLines((Join-Path $root 'surface-use.log'),[string[]]$surfaceLogs)
        $runtimeLog=Get-Content -LiteralPath (Join-Path $correctRoot 'assisted/remote-evidence/latest.log') -Raw
        if($runtimeLog -notmatch 'prefetch\.policy lookahead=16 capacity=128'){throw 'Future demand policy was not active'}
        if($runtimeLog -notmatch 'backend\.enabled stage=noise mode=cooperative .*workers=2 queue_per_worker=4 queue_capacity=8'){throw 'Cooperative terrain workers were not initialized as requested'}
        $queuedIds=[Collections.Generic.HashSet[string]]::new()
        foreach($entry in [regex]::Matches($runtimeLog,'job\.complete id=(\S+) .*work_kind=GRID_AND_SURFACE remote_samples=\d+ grid_samples=[1-9][0-9]*')){[void]$queuedIds.Add($entry.Groups[1].Value)}
        $ownerMap=Get-Content -LiteralPath (Join-Path $correctRoot 'assisted/remote-evidence/owner-map.json') -Raw|ConvertFrom-Json
        foreach($owner in $ownerMap.PSObject.Properties){
            $found=$false
            foreach($entry in [regex]::Matches($runtimeLog,'job\.sent id=(\S+) .*owner='+[regex]::Escape([string]$owner.Value)+'\b')){
                if($queuedIds.Contains($entry.Groups[1].Value)){$found=$true;break}
            }
            if(-not $found){throw "No terrain-grid consumption for $($owner.Name)"}
        }
        # The benchmark owns the same runtime mutex itself, after all preceding
        # child processes have exited. No builds or runtime cases overlap.
        $lock.Dispose();$lock=$null
        $steps+=Step 'performance-pair' $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-ConstrainedServerBenchmark.ps1'),'-Execute','-TwoLogicalOnly','-ViewDistance',[string]$ViewDistance,'-MeasureFullView','-ServerJvmProcessors','2','-QuietRemoteTrace','-AssistedNoiseBackend','cooperative','-VanillaNoiseBackend','cooperative') 7200
        $output=Get-Content -LiteralPath (Join-Path $root 'performance-pair-out.log') -Raw
        $match=[regex]::Match($output,'CONSTRAINED_SERVER_BENCHMARK status=COMPLETE summary=(?<path>[^\r\n]+)')
        if($steps[-1].status -ne 'PASSED' -or -not $match.Success){throw 'Performance pair incomplete'}
        $performancePath=$match.Groups['path'].Value.Trim()
        $performance=Get-Content -LiteralPath $performancePath -Raw|ConvertFrom-Json
        if($performance.status -ne 'COMPLETE' -or $performance.artifact_sha256 -ne $hash){throw 'Performance artifact or comparison mismatch'}
        $quietConfig=Get-Content -LiteralPath (Join-Path $performance.run_root 'c2/assisted/remote-evidence/scenario-config.json') -Raw|ConvertFrom-Json
        if(-not $quietConfig.quiet_remote_trace){throw 'Performance tracing mode was not recorded'}
        $terrainLog=Get-Content -LiteralPath (Join-Path $performance.run_root 'c2/assisted/remote-evidence/latest.log') -Raw
        if($terrainLog -notmatch 'local_peak_task_active=[2-9]'){throw 'No actual parallel terrain execution was recorded'}
        $finishedChunks=[Collections.Generic.HashSet[string]]::new();$lateStores=0
        foreach($line in Get-Content -LiteralPath (Join-Path $performance.run_root 'c2/assisted/remote-evidence/latest.log')){
            if($line -match 'stage\.complete stage=noise chunk=(-?\d+,-?\d+)'){[void]$finishedChunks.Add($Matches[1])}
            if($line -match 'cache.store chunk=(-?\d+,-?\d+)' -and $finishedChunks.Contains($Matches[1])){$lateStores++}
        }
        [IO.File]::WriteAllText((Join-Path $root 'late-cache-check.json'),([ordered]@{completed_positions=$finishedChunks.Count;cache_stores_after_noise_completion=$lateStores}|ConvertTo-Json))
        if($lateStores -ne 0){throw "Late cache stores remain: $lateStores"}
    }
}catch{$issues.Add($_.Exception.ToString())}
finally{
    if($null -ne $lock){$lock.Dispose()}
    $env:JAVA_HOME=$oldJava;$env:Path=$oldPath
    $summary=[ordered]@{schema='worldgen-assist.generation-lookahead-gate.v1';success=($issues.Count -eq 0);local_only=[bool]$LocalOnly;artifact_sha256=$hash;native_artifacts=$nativeArtifacts;test_classes=$testClasses;junit=$junit;steps=$steps;issues=@($issues);root=$root;performance_summary=$performancePath}
    $path=Join-Path $root 'summary.json';[IO.File]::WriteAllText($path,($summary|ConvertTo-Json -Depth 8))
    Write-Output "GENERATION_LOOKAHEAD_GATE_COMPLETE summary=$path success=$($summary.success)"
    if(-not $summary.success){exit 1}
}
