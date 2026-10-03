[CmdletBinding()]
param([switch]$Execute,[switch]$LocalOnly,
    [ValidateRange(2,32)][int]$ViewDistance=32)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testClass='io.github.genichimaruo.worldgenassist.server.RemoteDensityValidator263Test'
if(-not $Execute){
    [ordered]@{test_class=$testClass;builds=@('Fabric','Forge','NeoForge');correctness='Overworld two owners, two logical server CPUs, eight secret groups';performance=if($LocalOnly){'NOT_RUN'}else{"matched full-view distance $ViewDistance pair; warmup plus three fresh repeats"};sequence='implementation complete, then one sequential batch'}|ConvertTo-Json
    exit 0
}
$root=Join-Path $workspace ('test-artifacts/validation-cost-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $root|Out-Null
$oldJava=$env:JAVA_HOME;$oldPath=$env:Path
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4';$env:Path="$env:JAVA_HOME\bin;$env:Path"
$steps=@();$issues=[Collections.Generic.List[string]]::new();$lock=$null;$hash=$null;$junit=$null;$performancePath=$null
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
        Write-Host "VALIDATION_COST_STEP name=$Name status=$($record.status)"
        return $record
    }finally{$process.Dispose()}
}
try{
    $lock=[IO.File]::Open((Join-Path $workspace 'test-artifacts/validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    foreach($name in @('Run-ValidationCostGate.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1','Compare-WorldgenScenarioMatrix.ps1','Run-ConstrainedServerBenchmark.ps1')){
        $tokens=$null;$parseErrors=$null
        [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$parseErrors)
        if($parseErrors.Count){throw "$name syntax: $(($parseErrors.Message)-join '; ')"}
    }
    $began=Get-Date
    $steps+=Step 'unit-build' "$env:SystemRoot\System32\cmd.exe" @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'test','--rerun-tasks','--tests',$testClass,'build') 1800
    if($steps[-1].status -ne 'PASSED'){throw 'Focused unit/build step failed'}
    $xmlPath=Join-Path $workspace "build/test-results/test/TEST-$testClass.xml"
    if((Get-Item -LiteralPath $xmlPath).LastWriteTime -lt $began){throw 'JUnit evidence is stale'}
    Copy-Item -LiteralPath $xmlPath -Destination (Join-Path $root 'validator-tests.xml')
    [xml]$xml=Get-Content -LiteralPath $xmlPath
    $junit=[ordered]@{}
    foreach($field in @('tests','failures','errors','skipped')){$junit[$field]=[int]$xml.testsuite.GetAttribute($field)}
    if($junit.tests -ne 2 -or $junit.failures -or $junit.errors -or $junit.skipped){throw 'Focused JUnit coverage incomplete'}
    foreach($loader in @('forge','neoforge')){
        $steps+=Step "$loader-build" "$env:SystemRoot\System32\cmd.exe" @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'-p',(Join-Path $workspace "loaders/$loader"),'build','-x','test') 1800
        if($steps[-1].status -ne 'PASSED'){throw "$loader build failed"}
    }
    $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1')
    $hash=(Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash
    if(-not $LocalOnly){
        $correctRoot=Join-Path $root 'correctness'
        New-Item -ItemType Directory -Force -Path $correctRoot|Out-Null
        $cases=@()
        foreach($mode in @('vanilla','assisted')){
            $directory=Join-Path $correctRoot $mode
            $steps+=Step "correctness-$mode" $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-Dimension','overworld','-Mode',$mode,'-Players','2','-Purpose','correctness','-CacheEntries','128','-Prediction','false','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','2','-CorrectnessDemandWaitMs','100','-OutputRoot',$directory) 1200
            if($steps[-1].status -ne 'PASSED'){throw "Affected runtime failed: $mode"}
            $result=Get-Content -LiteralPath (Join-Path $directory 'scenario-result.json') -Raw|ConvertFrom-Json
            if(-not $result.success -or -not $result.cleanup_safe -or $result.artifact_sha256 -ne $hash){throw 'Runtime cleanup or artifact identity mismatch'}
            $cases += [ordered]@{id="correctness-overworld-$mode-p2-cache";dimension='overworld';mode=$mode;players=2;purpose='correctness';cache_entries=128;prediction=$false;validation_cells=8}
        }
        [IO.File]::WriteAllText((Join-Path $correctRoot 'matrix-plan.json'),([ordered]@{runtime_and_performance_cases=$cases}|ConvertTo-Json -Depth 5))
        $steps+=Step 'correctness-comparison' $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'),'-RunRoot',$correctRoot) 300
        $comparison=Get-Content -LiteralPath (Join-Path $correctRoot 'analysis/scenario-matrix-analysis.json') -Raw|ConvertFrom-Json
        if($steps[-1].status -ne 'PASSED' -or $comparison.status -ne 'COMPLETE'){throw 'Correctness comparison incomplete'}
        # The benchmark owns the same runtime mutex itself, after all preceding
        # child processes have exited. No builds or runtime cases overlap.
        $lock.Dispose();$lock=$null
        $steps+=Step 'performance-pair' $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-ConstrainedServerBenchmark.ps1'),'-Execute','-TwoLogicalOnly','-ViewDistance',[string]$ViewDistance,'-MeasureFullView') 7200
        $output=Get-Content -LiteralPath (Join-Path $root 'performance-pair-out.log') -Raw
        $match=[regex]::Match($output,'CONSTRAINED_SERVER_BENCHMARK status=COMPLETE summary=(?<path>[^\r\n]+)')
        if($steps[-1].status -ne 'PASSED' -or -not $match.Success){throw 'Performance pair incomplete'}
        $performancePath=$match.Groups['path'].Value.Trim()
        $performance=Get-Content -LiteralPath $performancePath -Raw|ConvertFrom-Json
        if($performance.status -ne 'COMPLETE' -or $performance.artifact_sha256 -ne $hash){throw 'Performance artifact or comparison mismatch'}
    }
}catch{$issues.Add($_.Exception.ToString())}
finally{
    if($null -ne $lock){$lock.Dispose()}
    $env:JAVA_HOME=$oldJava;$env:Path=$oldPath
    $summary=[ordered]@{schema='worldgen-assist.validation-cost-gate.v1';success=($issues.Count -eq 0);local_only=[bool]$LocalOnly;artifact_sha256=$hash;test_class=$testClass;junit=$junit;steps=$steps;issues=@($issues);root=$root;performance_summary=$performancePath}
    $path=Join-Path $root 'summary.json';[IO.File]::WriteAllText($path,($summary|ConvertTo-Json -Depth 8))
    Write-Output "VALIDATION_COST_GATE_COMPLETE summary=$path success=$($summary.success)"
    if(-not $summary.success){exit 1}
}
