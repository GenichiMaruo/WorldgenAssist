[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)]
	[string] $VanillaLog,

	[Parameter(Mandatory = $true)]
	[string] $LocalLog,

	[Parameter(Mandatory = $true)]
	[string] $OutputDirectory,

	[Parameter(Mandatory = $true)]
	[string] $RunId,

	[string] $Marker = 'CAWG_BENCHMARK_MEASURED_BEGIN'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$invariantCulture = [Globalization.CultureInfo]::InvariantCulture

function Get-Median {
	param([double[]] $Values)

	$sorted = @($Values | Sort-Object)
	if ($sorted.Count -eq 0) {
		throw 'Cannot calculate a median for an empty collection.'
	}

	$middle = [Math]::Floor($sorted.Count / 2)
	if ($sorted.Count % 2 -eq 0) {
		return ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
	}

	return $sorted[$middle]
}

function Get-NearestRankPercentile {
	param(
		[double[]] $Values,
		[ValidateRange(0.0, 1.0)]
		[double] $Percentile
	)

	$sorted = @($Values | Sort-Object)
	if ($sorted.Count -eq 0) {
		throw 'Cannot calculate a percentile for an empty collection.'
	}

	$index = [Math]::Max(0, [Math]::Ceiling($Percentile * $sorted.Count) - 1)
	return $sorted[$index]
}

function Get-Statistics {
	param([double[]] $Values)

	if ($Values.Count -eq 0) {
		throw 'Cannot calculate statistics for an empty collection.'
	}

	$mean = ($Values | Measure-Object -Average).Average
	$sumSquaredDeviation = ($Values | ForEach-Object { [Math]::Pow($_ - $mean, 2.0) } | Measure-Object -Sum).Sum
	return [PSCustomObject]@{
		mean = $mean
		median = Get-Median $Values
		p95 = Get-NearestRankPercentile $Values 0.95
		p99 = Get-NearestRankPercentile $Values 0.99
		standard_deviation = [Math]::Sqrt($sumSquaredDeviation / $Values.Count)
		minimum = ($Values | Measure-Object -Minimum).Minimum
		maximum = ($Values | Measure-Object -Maximum).Maximum
	}
}

function Read-NoiseRecords {
	param(
		[string] $Path,
		[string] $Mode,
		[string[]] $ExpectedBackend,
		[string] $RequiredMarker,
		[string] $CurrentRunId
	)

	$resolvedPath = (Resolve-Path -LiteralPath $Path).Path
	$lines = @(Get-Content -LiteralPath $resolvedPath)
	if ($lines | Where-Object { $_.Contains('[CAWG] stage.digest_enabled') }) {
		throw "Digest mode was enabled in $resolvedPath; timing data is invalid."
	}
	if (-not ($lines | Where-Object { $_.Contains('[CAWG] benchmark.enabled stage=noise') })) {
		throw "NOISE task benchmark instrumentation was not enabled in $resolvedPath."
	}

	$localBackendLines = @($lines | Where-Object {
		$_ -match '\[CAWG\] backend\.enabled stage=noise mode=(?:local|delegate)\b'
	})
	if ($Mode -eq 'LOCAL_BACKEND' -and $localBackendLines.Count -eq 0) {
		throw "Local backend enablement was not found in $resolvedPath."
	}
	if ($Mode -eq 'VANILLA' -and $localBackendLines.Count -gt 0) {
		throw "The vanilla log unexpectedly enabled the local backend: $resolvedPath."
	}
	$scheduler = 'minecraft_shared_fork_join_async'
	if ($Mode -eq 'LOCAL_BACKEND') {
		$scheduler = if ($localBackendLines[0] -match '\sscheduler=(?<scheduler>\S+)') {
			$Matches.scheduler
		} else {
			'legacy_thread_pool_fifo'
		}
	}

	$markerIndex = -1
	for ($index = 0; $index -lt $lines.Count; $index++) {
		if ($lines[$index].Contains($RequiredMarker)) {
			$markerIndex = $index
			break
		}
	}
	if ($markerIndex -lt 0) {
		throw "Benchmark marker '$RequiredMarker' was not found in $resolvedPath."
	}

	$numberPattern = '-?\d+(?:\.\d+)?(?:[Ee][+-]?\d+)?'
	$stagePattern = '^\[(?<timestamp>[^]]+)\].*\[CAWG\] stage\.(?<result>complete|failed) stage=noise chunk=(?<x>-?\d+),(?<z>-?\d+) elapsed_ms=(?<elapsed>' + $numberPattern + ') started_thread=(?<started>\S+) completion_thread=(?<completion>\S+) generated_chunks=(?<generated>\d+) failures=(?<failures>\d+)'
	$taskPattern = '^\[(?<timestamp>[^]]+)\].*\[CAWG\] task\.(?<result>complete|failed) stage=noise backend=(?<backend>\S+)(?: execution=(?<execution>\S+))? chunk=(?<x>-?\d+),(?<z>-?\d+) queue_ms=(?<queue>' + $numberPattern + ') compute_ms=(?<compute>' + $numberPattern + ')(?: cpu_ms=(?<cpu>' + $numberPattern + '))? scheduled_nanos=(?<scheduled>\d+) started_nanos=(?<started>\d+) completed_nanos=(?<completed>\d+)'
	$stageByCoordinate = @{}
	$taskByCoordinate = @{}

	for ($index = $markerIndex + 1; $index -lt $lines.Count; $index++) {
		$line = $lines[$index]
		if ($line -match $stagePattern) {
			$key = "$($Matches.x),$($Matches.z)"
			if ($stageByCoordinate.ContainsKey($key)) {
				throw "Duplicate NOISE-stage coordinate '$key' after the marker in $resolvedPath."
			}
			$stageByCoordinate[$key] = [PSCustomObject]@{
				timestamp = $Matches.timestamp
				result = $Matches.result
				chunk_x = [int] $Matches.x
				chunk_z = [int] $Matches.z
				stage_elapsed_ms = [double]::Parse($Matches.elapsed, $invariantCulture)
				started_thread = $Matches.started
				completion_thread = $Matches.completion
				generated_chunks = [long] $Matches.generated
				failures = [long] $Matches.failures
			}
			continue
		}

		if ($line -match $taskPattern) {
			$key = "$($Matches.x),$($Matches.z)"
			if ($taskByCoordinate.ContainsKey($key)) {
				throw "Duplicate NOISE-task coordinate '$key' after the marker in $resolvedPath."
			}
			if ($Matches.backend -notin $ExpectedBackend) {
				throw "Unexpected backend '$($Matches.backend)' for '$key' in $resolvedPath; expected one of '$($ExpectedBackend -join ',')'."
			}
			$taskByCoordinate[$key] = [PSCustomObject]@{
				timestamp = $Matches.timestamp
				result = $Matches.result
				requested_backend = $Matches.backend
				execution_route = if ($Matches.ContainsKey('execution')) { $Matches.execution } else { 'legacy_unspecified' }
				queue_ms = [double]::Parse($Matches.queue, $invariantCulture)
				compute_ms = [double]::Parse($Matches.compute, $invariantCulture)
				cpu_ms = if ($Matches.ContainsKey('cpu')) { [double]::Parse($Matches.cpu, $invariantCulture) } else { -1.0 }
				scheduled_nanos = [long] $Matches.scheduled
				started_nanos = [long] $Matches.started
				completed_nanos = [long] $Matches.completed
			}
		}
	}

	if ($stageByCoordinate.Count -eq 0) {
		throw "No NOISE-stage records were found after the marker in $resolvedPath."
	}
	if ($taskByCoordinate.Count -eq 0) {
		throw "No NOISE-task records were found after the marker in $resolvedPath."
	}
	$taskRoutes = @($taskByCoordinate.Values | ForEach-Object execution_route | Select-Object -Unique)
	if ($Mode -eq 'VANILLA' -and @($taskRoutes | Where-Object { $_ -notin @('vanilla', 'legacy_unspecified') }).Count -gt 0) {
		throw "Vanilla log reported a non-vanilla execution route in $resolvedPath."
	}
	if ($Mode -eq 'LOCAL_BACKEND') {
		$unexpectedRoutes = @($taskRoutes | Where-Object { $_ -notin @('local_pool', 'vanilla_fallback', 'vanilla_delegate', 'legacy_unspecified') })
		if ($unexpectedRoutes.Count -gt 0) {
			throw "Local log reported unexpected execution routes in ${resolvedPath}: $($unexpectedRoutes -join ',')."
		}
	}

	$stageOnly = @($stageByCoordinate.Keys | Where-Object { -not $taskByCoordinate.ContainsKey($_) })
	$taskOnly = @($taskByCoordinate.Keys | Where-Object { -not $stageByCoordinate.ContainsKey($_) })
	if ($stageOnly.Count -gt 0 -or $taskOnly.Count -gt 0) {
		throw "Stage/task coordinate sets differ in ${resolvedPath}: stage_only=$($stageOnly.Count), task_only=$($taskOnly.Count)."
	}

	return @($stageByCoordinate.Keys | ForEach-Object {
		$stage = $stageByCoordinate[$_]
		$task = $taskByCoordinate[$_]
		if ($stage.result -ne $task.result) {
			throw "Stage/task results differ for coordinate '$_' in $resolvedPath."
		}
		[PSCustomObject]@{
			timestamp = $stage.timestamp
			task_timestamp = $task.timestamp
			mode = $Mode
			scheduler = $scheduler
			requested_backend = $task.requested_backend
			execution_route = $task.execution_route
			run_id = $CurrentRunId
			chunk_x = $stage.chunk_x
			chunk_z = $stage.chunk_z
			stage_elapsed_ms = $stage.stage_elapsed_ms
			task_queue_ms = $task.queue_ms
			task_compute_ms = $task.compute_ms
			task_cpu_ms = $task.cpu_ms
			scheduled_nanos = $task.scheduled_nanos
			started_nanos = $task.started_nanos
			completed_nanos = $task.completed_nanos
			started_thread = $stage.started_thread
			completion_thread = $stage.completion_thread
			result = $stage.result
			generated_chunks = $stage.generated_chunks
			failures = $stage.failures
		}
	})
}

function Get-Summary {
	param(
		[object[]] $Records,
		[string] $Mode,
		[string] $CurrentRunId
	)

	$successful = @($Records | Where-Object { $_.result -eq 'complete' })
	if ($successful.Count -eq 0) {
		throw "Mode $Mode has no successful records."
	}

	$stage = Get-Statistics ([double[]] @($successful | ForEach-Object { $_.stage_elapsed_ms }))
	$queue = Get-Statistics ([double[]] @($successful | ForEach-Object { $_.task_queue_ms }))
	$compute = Get-Statistics ([double[]] @($successful | ForEach-Object { $_.task_compute_ms }))
	$cpuValues = [double[]] @($successful | Where-Object { $_.task_cpu_ms -ge 0.0 } | ForEach-Object { $_.task_cpu_ms })
	$cpu = if ($cpuValues.Count -gt 0) { Get-Statistics $cpuValues } else { $null }
	$firstScheduled = ($successful.scheduled_nanos | Measure-Object -Minimum).Minimum
	$lastCompleted = ($successful.completed_nanos | Measure-Object -Maximum).Maximum
	$workloadSpanMillis = ($lastCompleted - $firstScheduled) / 1000000.0

	return [PSCustomObject]@{
		run_id = $CurrentRunId
		mode = $Mode
		scheduler = @($successful.scheduler | Select-Object -Unique) -join ','
		requested_backends = @($successful.requested_backend | Select-Object -Unique | Sort-Object) -join ','
		execution_routes = @($successful.execution_route | Select-Object -Unique | Sort-Object) -join ','
		count = $successful.Count
		failures = @($Records | Where-Object { $_.result -eq 'failed' }).Count
		local_pool_count = @($successful | Where-Object execution_route -eq 'local_pool').Count
		vanilla_fallback_count = @($successful | Where-Object execution_route -eq 'vanilla_fallback').Count
		vanilla_delegate_count = @($successful | Where-Object execution_route -eq 'vanilla_delegate').Count
		legacy_unattributed_count = @($successful | Where-Object execution_route -eq 'legacy_unspecified').Count
		stage_mean_ms = $stage.mean
		stage_median_ms = $stage.median
		stage_p95_ms = $stage.p95
		stage_p99_ms = $stage.p99
		stage_standard_deviation_ms = $stage.standard_deviation
		stage_min_ms = $stage.minimum
		stage_max_ms = $stage.maximum
		queue_mean_ms = $queue.mean
		queue_median_ms = $queue.median
		queue_p95_ms = $queue.p95
		queue_p99_ms = $queue.p99
		compute_mean_ms = $compute.mean
		compute_median_ms = $compute.median
		compute_p95_ms = $compute.p95
		compute_p99_ms = $compute.p99
		cpu_supported_count = $cpuValues.Count
		cpu_mean_ms = if ($null -eq $cpu) { [double]::NaN } else { $cpu.mean }
		cpu_median_ms = if ($null -eq $cpu) { [double]::NaN } else { $cpu.median }
		cpu_p95_ms = if ($null -eq $cpu) { [double]::NaN } else { $cpu.p95 }
		cpu_p99_ms = if ($null -eq $cpu) { [double]::NaN } else { $cpu.p99 }
		workload_span_ms = $workloadSpanMillis
		throughput_tasks_per_second = if ($workloadSpanMillis -eq 0.0) { [double]::NaN } else { $successful.Count * 1000.0 / $workloadSpanMillis }
	}
}

$vanillaRecords = @(Read-NoiseRecords -Path $VanillaLog -Mode 'VANILLA' -ExpectedBackend @('vanilla') -RequiredMarker $Marker -CurrentRunId $RunId)
$localRecords = @(Read-NoiseRecords -Path $LocalLog -Mode 'LOCAL_BACKEND' -ExpectedBackend @('local', 'delegate') -RequiredMarker $Marker -CurrentRunId $RunId)

$vanillaByCoordinate = @{}
foreach ($record in $vanillaRecords) {
	$vanillaByCoordinate["$($record.chunk_x),$($record.chunk_z)"] = $record
}
$localByCoordinate = @{}
foreach ($record in $localRecords) {
	$localByCoordinate["$($record.chunk_x),$($record.chunk_z)"] = $record
}

$onlyVanilla = @($vanillaByCoordinate.Keys | Where-Object { -not $localByCoordinate.ContainsKey($_) })
$onlyLocal = @($localByCoordinate.Keys | Where-Object { -not $vanillaByCoordinate.ContainsKey($_) })
if ($onlyVanilla.Count -gt 0 -or $onlyLocal.Count -gt 0) {
	throw "Coordinate sets differ: vanilla_only=$($onlyVanilla.Count), local_only=$($onlyLocal.Count)."
}

$paired = @($vanillaByCoordinate.Keys | ForEach-Object {
	$vanilla = $vanillaByCoordinate[$_]
	$local = $localByCoordinate[$_]
	[PSCustomObject]@{
		run_id = $RunId
		vanilla_scheduler = $vanilla.scheduler
		local_scheduler = $local.scheduler
		local_execution_route = $local.execution_route
		chunk_x = $vanilla.chunk_x
		chunk_z = $vanilla.chunk_z
		vanilla_stage_ms = $vanilla.stage_elapsed_ms
		local_stage_ms = $local.stage_elapsed_ms
		stage_delta_ms = $local.stage_elapsed_ms - $vanilla.stage_elapsed_ms
		stage_ratio = if ($vanilla.stage_elapsed_ms -eq 0.0) { [double]::NaN } else { $local.stage_elapsed_ms / $vanilla.stage_elapsed_ms }
		vanilla_queue_ms = $vanilla.task_queue_ms
		local_queue_ms = $local.task_queue_ms
		queue_delta_ms = $local.task_queue_ms - $vanilla.task_queue_ms
		vanilla_compute_ms = $vanilla.task_compute_ms
		local_compute_ms = $local.task_compute_ms
		compute_delta_ms = $local.task_compute_ms - $vanilla.task_compute_ms
		compute_ratio = if ($vanilla.task_compute_ms -eq 0.0) { [double]::NaN } else { $local.task_compute_ms / $vanilla.task_compute_ms }
		vanilla_cpu_ms = $vanilla.task_cpu_ms
		local_cpu_ms = $local.task_cpu_ms
		cpu_delta_ms = if ($vanilla.task_cpu_ms -lt 0.0 -or $local.task_cpu_ms -lt 0.0) { [double]::NaN } else { $local.task_cpu_ms - $vanilla.task_cpu_ms }
		cpu_ratio = if ($vanilla.task_cpu_ms -le 0.0 -or $local.task_cpu_ms -lt 0.0) { [double]::NaN } else { $local.task_cpu_ms / $vanilla.task_cpu_ms }
	}
} | Sort-Object chunk_x, chunk_z)

$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
@($vanillaRecords + $localRecords) |
	Sort-Object mode, chunk_x, chunk_z |
	Export-Csv -LiteralPath (Join-Path $outputPath 'noise-stage-records.csv') -NoTypeInformation -Encoding utf8
$paired | Export-Csv -LiteralPath (Join-Path $outputPath 'noise-stage-paired.csv') -NoTypeInformation -Encoding utf8

$summaries = @(
	Get-Summary -Records $vanillaRecords -Mode 'VANILLA' -CurrentRunId $RunId
	Get-Summary -Records $localRecords -Mode 'LOCAL_BACKEND' -CurrentRunId $RunId
)
$summaries | Export-Csv -LiteralPath (Join-Path $outputPath 'noise-stage-summary.csv') -NoTypeInformation -Encoding utf8
$routeSummaries = @(
	@($vanillaRecords + $localRecords) |
		Group-Object mode, execution_route |
		ForEach-Object { Get-Summary -Records @($_.Group) -Mode $_.Group[0].mode -CurrentRunId $RunId }
)
$routeSummaries | Export-Csv -LiteralPath (Join-Path $outputPath 'noise-stage-route-summary.csv') -NoTypeInformation -Encoding utf8
$summaries | Format-Table mode, scheduler, requested_backends, execution_routes, count, failures, local_pool_count, vanilla_fallback_count, vanilla_delegate_count, stage_mean_ms, queue_mean_ms, compute_mean_ms, cpu_mean_ms, workload_span_ms, throughput_tasks_per_second -AutoSize

$stageDeltaMean = ($paired.stage_delta_ms | Measure-Object -Average).Average
$computeDeltaMean = ($paired.compute_delta_ms | Measure-Object -Average).Average
$cpuDeltas = @($paired.cpu_delta_ms | Where-Object { -not [double]::IsNaN($_) })
$cpuDeltaMean = if ($cpuDeltas.Count -eq 0) { [double]::NaN } else { ($cpuDeltas | Measure-Object -Average).Average }
Write-Output ('paired_count={0} stage_mean_delta_ms={1:F4} compute_mean_delta_ms={2:F4} cpu_mean_delta_ms={3:F4}' -f $paired.Count, $stageDeltaMean, $computeDeltaMean, $cpuDeltaMean)
Write-Output "output_directory=$outputPath"
