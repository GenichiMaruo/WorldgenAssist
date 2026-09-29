[CmdletBinding()]
param([switch]$Execute, [switch]$LocalOnly)

# Only reply-session, ingress, batch tests and the affected two-owner runtime pair.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testClasses=@('io.github.genichimaruo.worldgenassist.client.ClientReplySession263Test','io.github.genichimaruo.worldgenassist.server.ConnectionScopedResultIngress263Test','io.github.genichimaruo.worldgenassist.server.RemoteBatchDispatch263Test')
if(-not $Execute){
    [ordered]@{test_classes=$testClasses;build='Fabric plus affected native client transports';runtime=if($LocalOnly){'NOT_RUN'}else{'Overworld two-owner correctness pair, two logical server CPUs, 100ms base wait, prefetch'};sequence='implementation first; one sequential batch; no performance matrix'} | ConvertTo-Json
    exit 0
}
$root=Join-Path $workspace ('test-artifacts/network-dispatch-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $root | Out-Null
$oldJava=$env:JAVA_HOME;$oldPath=$env:Path
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4';$env:Path="$env:JAVA_HOME\bin;$env:Path"
$steps=@();$issues=[Collections.Generic.List[string]]::new();$lock=$null;$hash=$null;$junit=$null;$transport=$null
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
        return [ordered]@{name=$Name;status=if($finished -and $process.ExitCode -eq 0){'PASSED'}else{'FAILED'};exit_code=$process.ExitCode}
    }finally{$process.Dispose()}
}
try{
    $lock=[IO.File]::Open((Join-Path $workspace 'test-artifacts/validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    foreach($name in @('Run-NetworkDispatchGate.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1')){
        $tokens=$null;$errors=$null
        [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors)
        if($errors.Count){throw "$name syntax: $(($errors.Message)-join '; ')"}
    }
    $transport=$null
    $began=Get-Date
    $arguments=@('/d','/c',(Join-Path $workspace 'gradlew.bat'),'test','--rerun-tasks')
    foreach($testClass in $testClasses){$arguments+=@('--tests',$testClass)}
    $arguments+='build'
    $steps+=Step 'unit-build' "$env:SystemRoot\System32\cmd.exe" $arguments 1800
    if($steps[-1].status -ne 'PASSED'){throw 'Focused unit/build step failed'}
    $junit=[ordered]@{tests=0;failures=0;errors=0;skipped=0}
    foreach($testClass in $testClasses){
        $xmlPath=Join-Path $workspace ("build/test-results/test/TEST-$testClass.xml")
        if((Get-Item -LiteralPath $xmlPath).LastWriteTime -lt $began){throw 'JUnit evidence is stale'}
        Copy-Item -LiteralPath $xmlPath -Destination (Join-Path $root "$testClass.xml")
        [xml]$xml=Get-Content -LiteralPath $xmlPath
        foreach($field in @('tests','failures','errors','skipped')){$junit[$field]+=[int]$xml.testsuite.GetAttribute($field)}
    }
    if($junit.tests -ne 6 -or $junit.failures -or $junit.errors -or $junit.skipped){throw 'Focused JUnit coverage was incomplete'}
    foreach($loader in @('forge','neoforge')){
        $steps+=Step "build-$loader" "$env:SystemRoot\System32\cmd.exe" @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'-p',(Join-Path $workspace "loaders/$loader"),'build','-x','test') 1800
        if($steps[-1].status -ne 'PASSED'){throw "Affected $loader transport build failed"}
    }
    $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -RequireBuilt
    $hash=(Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash
    if(-not $LocalOnly){
        $cases=@()
        foreach($mode in @('vanilla','assisted')){
            $directory=Join-Path $root $mode
            $steps+=Step "runtime-$mode" $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-Dimension','overworld','-Mode',$mode,'-Players','2','-Purpose','correctness','-CacheEntries','128','-Prediction','false','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','2','-CorrectnessDemandWaitMs','100','-OutputRoot',$directory) 1200
            if($steps[-1].status -ne 'PASSED'){throw "Affected runtime failed: $mode"}
            $result=Get-Content -LiteralPath (Join-Path $directory 'scenario-result.json') -Raw|ConvertFrom-Json
            if(-not $result.success -or -not $result.cleanup_safe -or $result.artifact_sha256 -ne $hash -or $result.demand_wait_ms -ne 100){throw 'Runtime safety, artifact identity or base wait mismatch'}
            $cpu=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/server-cpu-limit.json') -Raw|ConvertFrom-Json
            if($cpu.requested_logical_processors -ne 2 -or $cpu.applied_affinity_mask -ne 3){throw 'Two-CPU fixture evidence differs'}
            if($mode -eq 'assisted'){
                $log=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/latest.log') -Raw
                $network=@([regex]::Matches($log,'job\.result_ingress .*path=network\b')).Count
                $main=@([regex]::Matches($log,'job\.result_ingress .*path=main\b')).Count
                $batches=@([regex]::Matches($log,'jobs\.batch_sent .*count=[2-4]\b')).Count
                if(-not $network -or $main -or -not $batches){throw 'Network ingress or bounded batch route was not exercised'}
                $clients=@()
                for($owner=0;$owner -lt 2;$owner++){
                    $clientLog=Get-Content -LiteralPath (Join-Path $directory "clients/owner-$owner/client/logs/latest.log") -Raw
                    $dispatch=[regex]::Matches($clientLog,'job\.client_dispatch .*send_wait_ms=(?<ms>[\d.]+) path=worker_direct\b')
                    if(-not $dispatch.Count){throw "Direct worker dispatch not exercised for owner $owner"}
                    $samples=@($dispatch|ForEach-Object {[double]::Parse($_.Groups['ms'].Value,[Globalization.CultureInfo]::InvariantCulture)})
                    $clients+=[ordered]@{owner=$owner;direct_dispatch_count=$dispatch.Count;send_handoff_mean_ms=($samples|Measure-Object -Average).Average}
                }
                $transport=[ordered]@{network_ingress_count=$network;main_ingress_count=$main;request_batch_count=$batches;clients=$clients}
                [IO.File]::WriteAllText((Join-Path $root 'transport-paths.json'),($transport|ConvertTo-Json -Depth 6))
            }
            $cases += [ordered]@{id="correctness-overworld-$mode-p2-cache";dimension='overworld';mode=$mode;players=2;purpose='correctness';cache_entries=128;prediction=$false;validation_cells=8}
        }
        [IO.File]::WriteAllText((Join-Path $root 'matrix-plan.json'),([ordered]@{runtime_and_performance_cases=$cases}|ConvertTo-Json -Depth 5))
        $steps+=Step 'comparison' $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'),'-RunRoot',$root) 300
        $comparison=Get-Content -LiteralPath (Join-Path $root 'analysis/scenario-matrix-analysis.json') -Raw|ConvertFrom-Json
        if($steps[-1].status -ne 'PASSED' -or $comparison.status -ne 'COMPLETE'){throw 'Affected runtime comparison is incomplete'}
    }
}catch{$issues.Add($_.Exception.ToString())}
finally{
    if($null -ne $lock){$lock.Dispose(); Remove-Item -LiteralPath (Join-Path $workspace 'test-artifacts/validation-matrix.lock') -ErrorAction SilentlyContinue}
    $env:JAVA_HOME=$oldJava;$env:Path=$oldPath
    $summary=[ordered]@{schema='worldgen-assist.network-dispatch-gate.v1';success=($issues.Count -eq 0);local_only=[bool]$LocalOnly;artifact_sha256=$hash;test_classes=$testClasses;junit=$junit;transport=$transport;steps=$steps;issues=@($issues);root=$root;performance='NOT_RUN; no speedup evidence for this artifact'}
    $path=Join-Path $root 'summary.json';[IO.File]::WriteAllText($path,($summary|ConvertTo-Json -Depth 8))
    Write-Output "NETWORK_DISPATCH_GATE_COMPLETE summary=$path success=$($summary.success)"
    if(-not $summary.success){exit 1}
}
