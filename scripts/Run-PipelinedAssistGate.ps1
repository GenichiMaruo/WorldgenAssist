[CmdletBinding()]
param([switch]$Execute, [switch]$LocalOnly, [string]$LocalEvidence, [string]$ExpectedArtifactSHA256)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root = Join-Path $workspace ('test-artifacts/pipelined-assist-gate-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$tests = 'io.github.genichimaruo.worldgenassist.client.ClientContextReuse263Test,io.github.genichimaruo.worldgenassist.client.ClientDensitySampler263Test,io.github.genichimaruo.worldgenassist.server.RemotePreparation263Test,io.github.genichimaruo.worldgenassist.server.GenerationPrefetch263Test'
$correctness = 'correctness-overworld-assisted-p2-cache'
if (-not $Execute) {
    [ordered]@{tests=$tests;correctness_pair=$correctness;native_builds=@('forge','neoforge');performance='2 logical CPU server, 2 clients, relocation and continuous movement; vanilla/current/prepared/prefetch; one warm-up and three measured repeats';sequence='all implementation first, one sequential batch'} | ConvertTo-Json
    exit 0
}
New-Item -ItemType Directory -Force -Path $root | Out-Null
$jdk = 'C:\Program Files\Java\jdk-25.0.4'
$oldJava = $env:JAVA_HOME; $oldPath = $env:Path
$env:JAVA_HOME = $jdk; $env:Path = "$jdk\bin;$env:Path"
$steps = @(); $runs = @(); $issues = [Collections.Generic.List[string]]::new()
$pwsh = (Get-Command pwsh.exe).Source
$performanceLock = $null
$hash = $null

function Invoke-Step([string]$Name,[string]$File,[string[]]$Arguments,[int]$Timeout=14400) {
    $out = Join-Path $root "$Name-out.log"; $err = Join-Path $root "$Name-err.log"
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$File; $info.WorkingDirectory=$workspace; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach($argument in $Arguments){[void]$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try {
        if(-not $process.Start()){throw "Could not start $Name"}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        $finished=$process.WaitForExit($Timeout*1000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText($out,$stdout.GetAwaiter().GetResult());[IO.File]::WriteAllText($err,$stderr.GetAwaiter().GetResult())
        return [ordered]@{name=$Name;status=if($finished -and $process.ExitCode -eq 0){'PASSED'}else{'FAILED'};exit_code=$process.ExitCode;stdout=$out;stderr=$err}
    } finally {$process.Dispose()}
}
function Stats([double[]]$Values) {
    $sorted=@($Values|Sort-Object)
    if($sorted.Count -eq 0){return $null}
    $middle=[int][Math]::Floor($sorted.Count/2)
    $median=if($sorted.Count%2){$sorted[$middle]}else{($sorted[$middle-1]+$sorted[$middle])/2}
    [ordered]@{count=$sorted.Count;median=$median;minimum=$sorted[0];maximum=$sorted[-1]}
}
function Client-Measurements([string]$Directory,[int]$Repeats) {
    $receipts=@();$preparation=@();$sendWait=@();$workerTotal=@()
    for($repeat=1;$repeat -le $Repeats;$repeat++){
        $ownerTimes=@();$location=1+$repeat;$cx=1000+$location*256;$cz=-2000-$location*256
        for($owner=0;$owner -lt 2;$owner++){
            $log=Get-Content -LiteralPath (Join-Path $Directory "clients/owner-$owner/client/logs/latest.log") -Raw
            $begin=[regex]::Match($log,"benchmark\.client_marker phase=BEGIN repeat=$repeat nanos=(?<nanos>\d+)")
            if(-not $begin.Success){throw "Missing client BEGIN: $Directory/$owner/$repeat"}
            $start=[long]$begin.Groups['nanos'].Value;$latest=$start;$seen=@{}
            $x=if($owner -eq 0){$cx}else{-$cx};$z=if($owner -eq 0){$cz}else{-$cz}
            foreach($match in [regex]::Matches($log,"benchmark\.chunk_received repeat=$repeat chunk=(?<x>-?\d+),(?<z>-?\d+) nanos=(?<nanos>\d+)")){
                $mx=[int]$match.Groups['x'].Value;$mz=[int]$match.Groups['z'].Value;$n=[long]$match.Groups['nanos'].Value
                if([Math]::Abs($mx-$x) -le 4 -and [Math]::Abs($mz-$z) -le 4 -and $n -ge $start -and -not $seen.ContainsKey("$mx,$mz")){
                    $seen["$mx,$mz"]=$true;$latest=[Math]::Max($latest,$n)
                }
            }
            if($seen.Count -ne 81){throw "Incomplete client coverage: $Directory/$owner/$repeat count=$($seen.Count)"}
            $ownerTimes+=($latest-$start)/1000000.0
            $slice=[regex]::Match($log,"(?s)CAWG_SCENARIO_MEASURED_BEGIN_$repeat\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_$repeat\b").Groups['body'].Value
            foreach($match in [regex]::Matches($slice,'job\.client_prepared .*preparation_ms=(?<ms>[\d.]+)')){$preparation += [double]::Parse($match.Groups['ms'].Value,[Globalization.CultureInfo]::InvariantCulture)}
            foreach($match in [regex]::Matches($slice,'job\.client_dispatch .*send_wait_ms=(?<ms>[\d.]+)')){$sendWait += [double]::Parse($match.Groups['ms'].Value,[Globalization.CultureInfo]::InvariantCulture)}
            foreach($match in [regex]::Matches($slice,'job\.client_dispatch .*worker_total_ms=(?<ms>[\d.]+)')){$workerTotal += [double]::Parse($match.Groups['ms'].Value,[Globalization.CultureInfo]::InvariantCulture)}
        }
        $receipts+=($ownerTimes|Measure-Object -Maximum).Maximum
    }
    [ordered]@{receipt=Stats $receipts;client_preparation=Stats $preparation;client_send_wait=Stats $sendWait;client_worker_total=Stats $workerTotal}
}

try {
    foreach($script in @('Run-PipelinedAssistGate.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1')){
        $tokens=$null;$parseErrors=$null
        [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $script),[ref]$tokens,[ref]$parseErrors)
        if($parseErrors.Count){throw ($script+': '+(($parseErrors|ForEach-Object {$_.Message}) -join '; '))}
    }
    if($LocalEvidence){
        if($LocalOnly -or $ExpectedArtifactSHA256 -notmatch '^[A-Fa-f0-9]{64}$'){throw 'Resuming runtime needs an exact artifact hash and cannot use LocalOnly'}
        $verified=Get-Content -LiteralPath $LocalEvidence -Raw|ConvertFrom-Json
        if(-not $verified.success -or -not $verified.local_only -or @($verified.steps|Where-Object {$_.status -ne 'PASSED'}).Count -or $verified.steps.Count -ne 3){throw 'Local evidence does not prove all selected tests and loader builds'}
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -RequireBuilt
        if((Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash -ne $ExpectedArtifactSHA256){throw 'Built artifact changed since verified local batch'}
        $steps += [ordered]@{name='local-verification-reused';status='PASSED';evidence=$LocalEvidence}
        $correctRoot=Join-Path $root 'correctness';New-Item -ItemType Directory -Force -Path $correctRoot|Out-Null
        $planCases=@()
        foreach($mode in @('vanilla','assisted')){
            $directory=Join-Path $correctRoot $mode
            $steps += Invoke-Step "correctness-$mode" $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-Dimension','overworld','-Mode',$mode,'-Players','2','-Purpose','correctness','-CacheEntries','128','-Prediction','false','-ValidationCells','8','-Seed','8675309','-OutputRoot',$directory)
            if($steps[-1].status -ne 'PASSED'){throw "Correctness scenario failed: $mode"}
            $scenario=Get-Content -LiteralPath (Join-Path $directory 'scenario-result.json') -Raw|ConvertFrom-Json
            if(-not $scenario.success -or -not $scenario.cleanup_safe -or $scenario.artifact_sha256 -ne $ExpectedArtifactSHA256){throw 'Correctness scenario or artifact identity failed'}
            $planCases += [ordered]@{id="correctness-overworld-$mode-p2-cache";purpose='correctness';dimension='overworld';mode=$mode;players=2;cache_entries=128;prediction=$false;validation_cells=8}
        }
        [IO.File]::WriteAllText((Join-Path $correctRoot 'matrix-plan.json'),([ordered]@{runtime_and_performance_cases=$planCases}|ConvertTo-Json -Depth 6))
        $steps += Invoke-Step 'correctness-comparison' $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'),'-RunRoot',$correctRoot) 300
        $comparison=Get-Content -LiteralPath (Join-Path $correctRoot 'analysis/scenario-matrix-analysis.json') -Raw|ConvertFrom-Json
        if($steps[-1].status -ne 'PASSED' -or $comparison.status -ne 'COMPLETE'){throw 'Correctness comparison is incomplete or failed'}
    }elseif($LocalOnly){
        $arguments=@('/d','/c',(Join-Path $workspace 'gradlew.bat'),'test')
        foreach($test in $tests.Split(',')){$arguments+=@('--tests',$test)}
        $arguments+=@('build','--continue')
        $steps += Invoke-Step 'fabric-unit-build' "$env:SystemRoot\System32\cmd.exe" $arguments 3600
    }else{
        $steps += Invoke-Step 'fabric-unit-correctness' $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-ValidationMatrix.ps1'),'-Execute','-CaseId',$correctness,'-TestClass',$tests)
    }
    if($steps[-1].status -ne 'PASSED'){throw 'Focused Fabric unit/build/runtime batch failed'}
    if(-not $LocalEvidence){ foreach($loader in @('forge','neoforge')){
        $steps += Invoke-Step "$loader-build" "$env:SystemRoot\System32\cmd.exe" @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'-p',(Join-Path $workspace "loaders/$loader"),'build','-x','test') 3600
        if($steps[-1].status -ne 'PASSED'){throw "$loader build failed"}
    } }
    $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -RequireBuilt
    $hash=(Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash
    if(-not $LocalOnly){
    $performanceLock=[IO.File]::Open((Join-Path $workspace 'test-artifacts/validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    foreach($movement in @('relocation','continuous')){
        foreach($variant in @('vanilla','current','prepared','prefetch')){
            $mode=if($variant -eq 'vanilla'){'vanilla'}else{'assisted'}
            $profile=if($variant -eq 'vanilla'){'current'}else{$variant}
            $directory=Join-Path $root "$movement-$variant"
            $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-Dimension','overworld','-Mode',$mode,'-Players','2','-Purpose','performance','-CacheEntries','128','-Prediction','false','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','2','-PipelineProfile',$profile,'-Movement',$movement,'-OutputRoot',$directory)
            $steps += Invoke-Step "$movement-$variant" $pwsh $arguments 1800
            $resultPath=Join-Path $directory 'scenario-result.json'
            if(-not(Test-Path -LiteralPath $resultPath)){throw "Missing result: $resultPath"}
            $result=Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
            if(-not $result.cleanup_safe){throw 'Scenario cleanup could not be proved; stopping the batch'}
            if($steps[-1].status -ne 'PASSED' -or -not $result.success -or $result.artifact_sha256 -ne $hash){throw "Scenario failed or artifact differs: $movement/$variant"}
            $cpu=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/server-cpu-limit.json') -Raw|ConvertFrom-Json
            if($cpu.requested_logical_processors -ne 2){throw 'Missing two-CPU evidence'}
            $client=Client-Measurements $directory 3
            $samples=@($result.performance.measured)
            if($samples.Count -ne 3){throw 'Expected three measured repeats'}
            $record=[ordered]@{movement=$movement;variant=$variant;artifact_sha256=$hash;path=$directory;client=$client;metrics=@{};source_manifest=$result.source_manifest_after_sha256}
            foreach($metric in @('server_region_ready_ms','server_full_region_ready_ms','server_cpu_ms','tick_p95_ms','throughput_tasks_per_second','prefetch_sent','prefetch_applied','ready_cache_used','demand_wait_fallbacks','validation_prepare_mean_ms','validation_compare_mean_ms','decode_queue_mean_ms')){
                $values=@($samples|ForEach-Object {if($null -ne $_.$metric){[double]$_.$metric}})
                $record.metrics[$metric]=Stats $values
            }
            $runs += $record
            if(@($runs|Where-Object {$_.source_manifest -ne $record.source_manifest}).Count){throw 'Source snapshot changed during performance batch'}
        }
    }
    }
} catch {$issues.Add($_.Exception.ToString())}
finally {
    if($null -ne $performanceLock){$performanceLock.Dispose()}
    $summary=[ordered]@{schema='worldgen-assist.pipelined-assist-gate.v1';success=($issues.Count -eq 0 -and ($LocalOnly -or $runs.Count -eq 8));local_only=[bool]$LocalOnly;local_evidence=$LocalEvidence;artifact_sha256=$hash;runtime_validation=if($LocalOnly){'NOT_RUN'}else{'INCLUDED'};root=$root;steps=$steps;performance=$runs;issues=@($issues);definitions=@{client_receipt='Both clients have received all 81 chunks of the fixed final 9x9 region, from their respective BEGIN markers; excludes rendering completion';server_full='All chunks of the same target region complete ChunkStatusTasks.full, measured from server BEGIN dispatch; excludes send queue';continuous='16 scripted steps of 8 blocks at 250ms intervals after relocation; includes approach and final-region generation';current='Same candidate artifact with context reuse, validation preparation and prefetch disabled; not a historical JAR'}}
    $path=Join-Path $root 'summary.json';[IO.File]::WriteAllText($path,($summary|ConvertTo-Json -Depth 15))
    $env:JAVA_HOME=$oldJava;$env:Path=$oldPath
    Write-Output "PIPELINED_ASSIST_GATE_COMPLETE summary=$path success=$($summary.success)"
    if(-not $summary.success){exit 1}
}
