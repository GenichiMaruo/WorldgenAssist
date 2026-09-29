[CmdletBinding()]
param([switch]$Execute, [switch]$TwoLogicalOnly)

# Six isolated two-owner scenarios in one bounded batch. No production build or
# unrelated JUnit suite is repeated for this harness-only experiment.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifacts = Join-Path $workspace 'test-artifacts'
$runner = Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'
$comparator = Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'
$pwsh = (Get-Command pwsh.exe -ErrorAction Stop).Source
$profiles = @(
    [ordered]@{name='unrestricted';logical_processors=0;modes=@('vanilla','assisted')},
    [ordered]@{name='four-logical';logical_processors=4;modes=@('assisted','vanilla')},
    [ordered]@{name='two-logical';logical_processors=2;modes=@('vanilla','assisted')}
)
if ($TwoLogicalOnly) { $profiles = @($profiles | Where-Object { $_.logical_processors -eq 2 }) }
if (-not $Execute) {
    [ordered]@{schema='worldgen-assist.constrained-server-plan.v1';players=2;dimension='overworld';warmup_runs=1;measured_repeats=3;profiles=$profiles;primary_proxy='server 9x9 NOISE completion; client-visible 81-chunk receipt is measured separately'} | ConvertTo-Json -Depth 6
    exit 0
}

New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$runRoot = Join-Path $artifacts ('csb-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$lockPath = Join-Path $artifacts 'validation-matrix.lock'
$lock = $null
$results = @()
$issues = @()
$runtimeSafe = $true
$jarHash = $null
function Get-MeasuredCoordinateSignature([string]$CaseRoot) {
    $log = Get-Content -LiteralPath (Join-Path $CaseRoot 'remote-evidence/latest.log') -Raw
    $repeats = @()
    for ($repeat=1; $repeat -le 3; $repeat++) {
        $match = [regex]::Match($log,"(?s)CAWG_SCENARIO_MEASURED_BEGIN_$repeat\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_$repeat\b")
        if (-not $match.Success) { throw "Missing measured repeat $repeat in $CaseRoot" }
        $coordinates = @([regex]::Matches($match.Groups['body'].Value,'stage\.complete stage=noise chunk=(?<chunk>-?\d+,-?\d+)\b') | ForEach-Object {$_.Groups['chunk'].Value} | Sort-Object -Unique)
        if ($coordinates.Count -eq 0) { throw "No NOISE coordinates in $CaseRoot repeat $repeat" }
        $repeats += ($coordinates -join ';')
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($repeats -join "`n"))
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
}
function Invoke-Quiet([string[]]$Arguments,[string]$OutputPath,[string]$ErrorPath) {
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$pwsh
    foreach($argument in $Arguments){[void]$start.ArgumentList.Add($argument)}
    $start.WorkingDirectory=$workspace
    $start.UseShellExecute=$false
    $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true
    $start.RedirectStandardError=$true
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start
    try {
        if(-not $process.Start()){throw 'Could not start PowerShell child'}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(1800000)){$process.Kill($true);$process.WaitForExit(30000)|Out-Null;throw 'Child exceeded 30-minute limit'}
        $stdout.GetAwaiter().GetResult() | Set-Content -LiteralPath $OutputPath
        $stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath $ErrorPath
        return $process.ExitCode
    } finally {$process.Dispose()}
}
try {
    New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
    try { $lock = [IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
    catch { throw "Another runtime batch owns $lockPath" }
    $lock.SetLength(0)
    $bytes = [Text.Encoding]::UTF8.GetBytes("pid=$PID`nstarted=$((Get-Date).ToString('o'))`nrun_root=$runRoot`n")
    $lock.Write($bytes,0,$bytes.Length);$lock.Flush()
    $artifact = & (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Workspace $workspace
    if ($artifact.Minecraft -ne '26.3' -or -not(Test-Path -LiteralPath $artifact.Path -PathType Leaf)) { throw 'Exact 26.3 Fabric JAR is missing' }
    $jarHash = (Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash
    foreach ($profile in $profiles) {
        $tierCode=if($profile.logical_processors -eq 0){'u'}else{'c'+$profile.logical_processors}
        $tierRoot = Join-Path $runRoot $tierCode
        New-Item -ItemType Directory -Force -Path $tierRoot | Out-Null
        $planCases = @($profile.modes | ForEach-Object {
            [ordered]@{id="performance-overworld-$_-p2-cache_prediction_validation";purpose='performance';dimension='overworld';mode=$_;players=2;cache_entries=128;prediction=$true;validation_cells=8;warmup_runs=1;measured_repeats=3;server_logical_processors=$profile.logical_processors}
        })
        [ordered]@{schema='worldgen-assist.validation-matrix-plan.v1';scope='two-owner constrained-server performance pair';generated_at=(Get-Date).ToString('o');runtime_and_performance_cases=$planCases} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $tierRoot 'matrix-plan.json') -Encoding utf8
        foreach ($mode in $profile.modes) {
            $caseRoot = Join-Path $tierRoot $mode
            if (-not $runtimeSafe) { $results += [ordered]@{tier=$profile.name;mode=$mode;status='SKIPPED';reason='Prior scenario cleanup safety was not proved';path=$caseRoot};continue }
            New-Item -ItemType Directory -Force -Path $caseRoot | Out-Null
            $arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',$runner,'-Dimension','overworld','-Mode',$mode,'-Players','2','-Purpose','performance','-CacheEntries','128','-Prediction','true','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors',[string]$profile.logical_processors,'-OutputRoot',$caseRoot)
            $started = Get-Date
            $exitCode = Invoke-Quiet $arguments (Join-Path $caseRoot 'batch-run.log') (Join-Path $caseRoot 'batch-error.log')
            $resultPath = Join-Path $caseRoot 'scenario-result.json'
            $status = 'FAILED'; $reason = "runner exit=$exitCode"
            if (Test-Path -LiteralPath $resultPath -PathType Leaf) {
                try {
                    $scenario = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
                    $runtimeSafe = [bool]$scenario.cleanup_safe
                    $cpuPath = Join-Path $caseRoot 'remote-evidence/server-cpu-limit.json'
                    if (Test-Path -LiteralPath $cpuPath -PathType Leaf) {
                        $cpu = Get-Content -LiteralPath $cpuPath -Raw | ConvertFrom-Json
                        if ([int]$cpu.requested_logical_processors -ne [int]$profile.logical_processors) { $reason = 'Recorded CPU limit differs from requested limit' }
                        elseif ($exitCode -eq 0 -and $scenario.success -and $scenario.cleanup_safe -and $scenario.artifact_sha256 -eq $jarHash) { $status='PASSED';$reason=$null }
                    } else { $reason='CPU-limit application evidence is missing' }
                } catch { $runtimeSafe=$false;$reason="Invalid scenario evidence: $($_.Exception.Message)" }
            } else { $runtimeSafe=$false;$reason='Scenario result and cleanup proof are missing' }
            $results += [ordered]@{tier=$profile.name;mode=$mode;status=$status;reason=$reason;duration_seconds=[Math]::Round(((Get-Date)-$started).TotalSeconds,3);path=$caseRoot}
        }
        $passed = @($results | Where-Object { $_.tier -eq $profile.name -and $_.status -eq 'PASSED' }).Count
        if ($passed -eq 2) {
            $analysisExit = Invoke-Quiet @('-NoProfile','-ExecutionPolicy','Bypass','-File',$comparator,'-RunRoot',$tierRoot) (Join-Path $tierRoot 'analysis-run.log') (Join-Path $tierRoot 'analysis-error.log')
            $analysisPath = Join-Path $tierRoot 'analysis/scenario-matrix-analysis.json'
            if ($analysisExit -ne 0 -or -not(Test-Path -LiteralPath $analysisPath -PathType Leaf)) { $issues += "$($profile.name): paired comparison failed; see analysis-error.log" }
        } else { $issues += "$($profile.name): fewer than two valid scenarios" }
    }
    $baseSignature = $null
    $baseCoordinates = $null
    $baseManifest = $null
    foreach ($profile in $profiles) {
        $tierCode=if($profile.logical_processors -eq 0){'u'}else{'c'+$profile.logical_processors}
        $tierRoot=Join-Path $runRoot $tierCode
        $analysisPath = Join-Path $tierRoot 'analysis/scenario-matrix-analysis.json'
        if (-not(Test-Path -LiteralPath $analysisPath -PathType Leaf)) { continue }
        $analysis = Get-Content -LiteralPath $analysisPath -Raw | ConvertFrom-Json
        if ($analysis.status -ne 'COMPLETE' -or @($analysis.performance_pairs).Count -ne 1) { $issues += "$($profile.name): analysis status=$($analysis.status)"; continue }
        $pair = $analysis.performance_pairs[0]
        $vanilla = Get-Content -LiteralPath (Join-Path $tierRoot 'vanilla/scenario-result.json') -Raw | ConvertFrom-Json
        $assisted = Get-Content -LiteralPath (Join-Path $tierRoot 'assisted/scenario-result.json') -Raw | ConvertFrom-Json
        if ($null -eq $baseManifest) { $baseManifest=$vanilla.source_manifest_after_sha256 }
        elseif ($vanilla.source_manifest_after_sha256 -cne $baseManifest -or $assisted.source_manifest_after_sha256 -cne $baseManifest) { $issues += "$($profile.name): source snapshot differs across CPU profiles" }
        $signature = @($vanilla.performance.measured | ForEach-Object { [string]$_.completed_tasks }) -join ','
        if ($null -eq $baseSignature) { $baseSignature=$signature } elseif ($signature -cne $baseSignature) { $issues += "$($profile.name): completed workload differs across CPU profiles" }
        $vanillaRoot = Join-Path $tierRoot 'vanilla'
        $coordinateSignature = Get-MeasuredCoordinateSignature $vanillaRoot
        if ($null -eq $baseCoordinates) { $baseCoordinates=$coordinateSignature } elseif ($coordinateSignature -cne $baseCoordinates) { $issues += "$($profile.name): measured NOISE coordinates differ across CPU profiles" }
        if ($null -eq $pair.metrics.server_region_ready_ms -or $null -eq $pair.metrics.client_region_receipt_ms) { $issues += "$($profile.name): server-ready or client-receipt metric is missing"; continue }
        $results += [ordered]@{tier=$profile.name;mode='pair';status='COMPLETE';server_logical_processors=$profile.logical_processors;coordinates_sha256=$coordinateSignature;region_ready_ms_vanilla=$pair.metrics.server_region_ready_ms.vanilla.median;region_ready_ms_assisted=$pair.metrics.server_region_ready_ms.assisted.median;client_receipt_ms_vanilla=$pair.metrics.client_region_receipt_ms.vanilla.median;client_receipt_ms_assisted=$pair.metrics.client_region_receipt_ms.assisted.median;throughput_vanilla=$pair.metrics.throughput_tasks_per_second.vanilla.median;throughput_assisted=$pair.metrics.throughput_tasks_per_second.assisted.median;tick_p95_vanilla=$pair.metrics.tick_p95_ms.vanilla.median;tick_p95_assisted=$pair.metrics.tick_p95_ms.assisted.median;server_cpu_ms_vanilla=$pair.metrics.server_cpu_ms.vanilla.median;server_cpu_ms_assisted=$pair.metrics.server_cpu_ms.assisted.median;analysis=$analysisPath}
    }
} catch { $issues += $_.Exception.ToString() }
finally {
    if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $runRoot -PathType Container) {
        $complete=@($results | Where-Object {$_.mode -eq 'pair' -and $_.status -eq 'COMPLETE'}).Count
        $summary=[ordered]@{schema='worldgen-assist.constrained-server-benchmark.v1';status=if($issues.Count -eq 0 -and $complete -eq $profiles.Count){'COMPLETE'}else{'INCOMPLETE'};artifact_sha256=$jarHash;client_visible_wait_measured=($issues.Count -eq 0 -and $complete -eq $profiles.Count);server_region_ready_definition='Elapsed from scripted relocation until both owners have completed the 9x9 NOISE region on the server; excludes client chunk delivery';client_receipt_definition='Elapsed from each client receiving the repeat BEGIN message until both clients have loaded all 81 chunks in their 9x9 region';results=$results;issues=$issues;run_root=$runRoot}
        $summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $runRoot 'summary.json') -Encoding utf8
        Write-Output "CONSTRAINED_SERVER_BENCHMARK status=$($summary.status) summary=$(Join-Path $runRoot 'summary.json')"
        if ($summary.status -ne 'COMPLETE') { exit 1 }
    }
}
