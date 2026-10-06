[CmdletBinding()]
param([switch]$Execute,[ValidateSet('inline-first','decoder-first')][string]$Order='inline-first')
# One affected method/three builds/physical parity, then a same-JAR causal comparison.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $Execute){[ordered]@{methods=1;builds=3;correctness='Physical two-owner original/decoder-prepared parity';performance='Assistance enabled in BOTH; inline versus decoder, view32, 1warm/3same repeats';order=$Order;native_runtime='UNRUN';feature_backend='off'}|ConvertTo-Json;exit 0}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root=Join-Path $workspace ('test-artifacts/prepared-sections-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=[Collections.Generic.List[object]]::new()
$results=@{};$receipts=@{};$metrics=@{};$proof=$null;$coordinates=$null;$manifest=$null
$lock=$null
$selfHash=(Get-FileHash -LiteralPath $PSCommandPath).Hash
Copy-Item -LiteralPath $PSCommandPath -Destination $root
function Step([string]$Name,[string]$Script,[string[]]$Arguments,[int]$Seconds){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=(Get-Command pwsh.exe).Source
    $info.WorkingDirectory=$workspace;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in @('-NoProfile','-File',(Join-Path $PSScriptRoot $Script))+$Arguments){[void]$info.ArgumentList.Add($argument)}
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
        Write-Host "PREPARED_SECTIONS_STEP name=$Name success=$($steps[-1].success)"
        if(-not $steps[-1].success){throw "Step failed: $Name"}
        return $output
    }finally{$process.Dispose()}
}
function Paired([double[]]$Baseline,[double[]]$Candidate){
    if($Baseline.Count -ne 3 -or $Candidate.Count -ne 3){throw 'Exactly three same-coordinate repeats required'}
    $rows=@(for($i=0;$i -lt 3;$i++){
        if($Baseline[$i] -le 0 -or $Candidate[$i] -le 0){throw 'Positive measured values required'}
        [ordered]@{repeat=$i+1;baseline_inline=$Baseline[$i];candidate_decoder=$Candidate[$i];ratio=$Candidate[$i]/$Baseline[$i]}
    })
    [ordered]@{rows=$rows;median_paired_percent=100*(@($rows.ratio|Sort-Object)[1]-1);lower_repeats=@($rows|Where-Object {$_.ratio -lt 1}).Count}
}
try{
    $output=Step 'affected-gate' 'Run-BlockDensityGate.ps1' @('-Execute','-SkipPerformance','-SectionPreparation','decoder','-TestProfile','complete-packed-application','-RemoteWorkKind','complete','-RemoteApplicationProfile','overlap','-WindowProfile','deep','-PrefetchLookahead','0') 5400
    if($output -notmatch '(?m)^BLOCK_DENSITY_GATE_COMPLETE summary=(.+) success=True\r?$'){throw 'Affected summary missing'}
    $proof=$Matches[1].Trim();$gate=Get-Content -LiteralPath $proof -Raw|ConvertFrom-Json
    if(-not $gate.success -or $gate.junit.tests -ne 1 -or $gate.junit.failures -or $gate.junit.errors -or $gate.junit.skipped){throw 'Affected gate incomplete'}
    $lock=[IO.File]::Open((Join-Path $workspace 'test-artifacts/validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    # Reuse the exact existing receipt collector's functions, never execute its matrix.
    . (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1')
    $tokens=$null;$parseErrors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'),[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count){throw 'Receipt collector parse failed'}
    foreach($name in @('Value','ClientReceiptSamples')){
        $function=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true))
        if($function.Count -ne 1){throw "Missing original receipt helper: $name"}
        . ([scriptblock]::Create($function[0].Extent.Text))
    }
    $modes=if($Order -eq 'inline-first'){@('inline','decoder')}else{@('decoder','inline')}
    foreach($mode in $modes){
        $directory=Join-Path $root $mode
        $output=Step "performance-$mode" 'Run-WorldgenScenario.ps1' @('-RemoteHost','gen1c@100.103.102.109','-RemoteRoot','E:/WorldgenAssist/port26.3','-Dimension','overworld','-Mode','assisted','-Players','2','-Purpose','performance','-CacheEntries','128','-Prediction','true','-ValidationCells','8','-Seed','8675309','-NoiseBackend','cooperative','-WindowProfile','deep','-RemoteApplicationProfile','overlap','-PrefetchLookahead','0','-RemoteWorkKind','complete','-CompleteVerification','peer','-SectionPreparation',$mode,'-ViewDistance','32','-MeasureFullView','-QuietRemoteTrace','-ServerFlightRecording','-OutputRoot',$directory) 5400
        $path=Join-Path $directory 'scenario-result.json';$result=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json -DateKind String
        if(-not $result.success -or -not $result.cleanup_safe -or -not $result.loopback_only -or $result.artifact_sha256 -ne $gate.artifact_sha256 -or
            $result.source_manifest_before_sha256 -ne $result.source_manifest_after_sha256 -or $result.section_preparation -ne $mode -or
            $result.feature_backend -ne 'off' -or $result.client_worker_threads -ne 4 -or $result.performance.warmup_runs -ne 1 -or $result.performance.measured_repeats -ne 3){throw "Invalid performance identity: $mode"}
        if($manifest -and $manifest -ne $result.source_manifest_after_sha256){throw 'Source differs between conditions'}
        $manifest=$result.source_manifest_after_sha256
        $log=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/latest.log') -Raw
        if($log -match 'worker\.quarantined|job\.peer_terrain_difference|job\.full_terrain_apply_rejected|Independent whole-terrain audit mismatch|Independent peer whole-terrain mismatch'){throw 'Generation verification failed'}
        $configuration=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/section-preparation-config.json') -Raw|ConvertFrom-Json
        if($configuration.mode -ne $mode){throw 'Actual preparation mode differs'}
        $actual=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/scenario-config.json') -Raw|ConvertFrom-Json
        $windowConfig=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/pipeline-window-config.json') -Raw|ConvertFrom-Json
        $cpu=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/server-jvm-cpu-limit.json') -Raw|ConvertFrom-Json
        if($actual.view_distance -ne 32 -or $actual.timeout_ms -ne 30000 -or -not $actual.quiet_remote_trace -or
            $cpu.requested_active_processor_count -ne 0 -or $cpu.observed_available_processors -ne 8 -or
            $windowConfig.owner_window -ne 32 -or $windowConfig.total_window -ne 64 -or $windowConfig.lookahead -ne 0){throw 'Actual performance conditions differ'}
        foreach($owner in 0..1){
            $option=@(Select-String -LiteralPath (Join-Path $directory "clients/owner-$owner/client/options.txt") -Pattern '^renderDistance:(\d+)$')
            if($option.Count -ne 1 -or [int]$option.Matches[0].Groups[1].Value -ne 32){throw 'Actual client distance differs'}
        }
        $signature=@(for($repeat=1;$repeat -le 3;$repeat++){
            $window=[regex]::Match($log,"(?s)CAWG_SCENARIO_MEASURED_BEGIN_$repeat\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_$repeat\b")
            if(-not $window.Success){throw 'Measured window missing'}
            $body=$window.Groups['body'].Value
            if($body -notmatch ('terrain\.bulk_applied .*preparation='+$mode+'\b')){throw "Actual measured $mode path missing"}
            if($mode -eq 'inline' -and $body -match 'terrain\.bulk_applied .*preparation=decoder\b'){throw 'Baseline used decoder preparation'}
            $keys=@([regex]::Matches($body,'stage\.complete stage=noise chunk=(?<chunk>-?\d+,-?\d+)\b')|ForEach-Object {$_.Groups['chunk'].Value}|Sort-Object -Unique)
            if($keys.Count -ne 10658){throw 'Unexpected workload size'}
            $keys -join ';'
        }) -join "`n"
        $signature=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($signature)))
        if($coordinates -and $coordinates -ne $signature){throw 'Measured coordinates differ'};$coordinates=$signature
        foreach($row in $result.performance.measured){if($row.failed_tasks -or $row.timeouts -or $row.completed_tasks -ne 10658){throw 'Failed or incomplete measured workload'}}
        $receipt=ClientReceiptSamples ([pscustomobject]@{path=$path;result=$result})
        if(-not $receipt.complete -or @($receipt.coverage|Where-Object {$_.expected_chunks_per_owner -ne 3461}).Count){throw 'Incomplete actual client receipts'}
        $receipts[$mode]=$receipt;$results[$mode]=$result
    }
    foreach($name in @('server_full_region_ready_ms','server_cpu_ms','tick_p95_ms','apply_mean_ms','server_decode_mean_ms','rtt_mean_ms')){
        $metrics[$name]=Paired ([double[]]$results.inline.performance.measured.$name) ([double[]]$results.decoder.performance.measured.$name)
    }
    $metrics.client_region_receipt_ms=Paired $receipts.inline.samples $receipts.decoder.samples
    if((Get-FileHash -LiteralPath $PSCommandPath).Hash -ne $selfHash){throw 'Coordinator changed during batch'}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    if($null -ne $lock){$lock.Dispose()}
    $summary=[ordered]@{schema='worldgen-assist.prepared-sections-gate.v1';success=($issues.Count -eq 0);order=$Order;affected_gate=$proof;assistance_enabled_in_both=$true;coordinates_sha256=$coordinates;source_sha256=$manifest;coordinator_sha256=$selfHash;steps=$steps.ToArray();metrics=$metrics;receipt_coverage=$receipts;issues=$issues.ToArray();native_runtime='UNRUN';beta_claim=$false}
    [IO.File]::WriteAllText((Join-Path $root 'summary.json'),($summary|ConvertTo-Json -Depth 12))
    Write-Output "PREPARED_SECTIONS_GATE success=$($summary.success) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
