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
		p95 = Get-NearestRankPercentile $Values 0.95
		p99 = Get-NearestRankPercentile $Values 0.99
		standard_deviation = [Math]::Sqrt($sumSquaredDeviation / $Values.Count)
		minimum = ($Values | Measure-Object -Minimum).Minimum
		maximum = ($Values | Measure-Object -Maximum).Maximum
	}
}

function Read-TickRecords {
	param(
		[string] $Path,
		[string] $Mode,
		[string] $RequiredMarker,
		[string] $CurrentRunId
	)

	$resolvedPath = (Resolve-Path -LiteralPath $Path).Path
	$lines = @(Get-Content -LiteralPath $resolvedPath)
	if ($lines | Where-Object { $_.Contains('[CAWG] stage.digest_enabled') }) {
		throw "Digest mode was enabled in $resolvedPath; timing data is invalid."
	}
	if (-not ($lines | Where-Object { $_.Contains('[CAWG] tick_benchmark.enabled') })) {
		throw "Server tick benchmark instrumentation was not enabled in $resolvedPath."
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
	$requiresLocalPool = $Mode -eq 'LOCAL_BACKEND' -and $scheduler -eq 'fork_join_async'

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
	$tickPattern = '^\[(?<timestamp>[^]]+)\].*\[CAWG\] tick\.complete tick=(?<tick>\d+) elapsed_ms=(?<elapsed>' + $numberPattern + ') over_budget=(?<over>true|false) budget_ms=(?<budget>' + $numberPattern + ') started_nanos=(?<started>\d+) completed_nanos=(?<completed>\d+) noise_completed=(?<noise_completed>\d+) noise_failed=(?<noise_failed>\d+) noise_active_start=(?<noise_active_start>\d+) noise_active_end=(?<noise_active_end>\d+) local_parallelism=(?<local_parallelism>-?\d+) local_pool_size=(?<local_pool_size>-?\d+) local_active=(?<local_active>-?\d+) local_running=(?<local_running>-?\d+) local_queued=(?<local_queued>-?\d+) local_admitted=(?<local_admitted>-?\d+) local_admission_capacity=(?<local_capacity>-?\d+) local_task_active=(?<local_task_active>-?\d+) local_peak_task_active=(?<local_peak_task_active>-?\d+) local_peak_admitted=(?<local_peak_admitted>-?\d+) local_fallbacks_delta=(?<fallbacks>\d+)'
	$seenTicks = @{}
	$records = @()

	for ($index = $markerIndex + 1; $index -lt $lines.Count; $index++) {
		$line = $lines[$index]
		if (-not ($line -match $tickPattern)) {
			continue
		}

		$tick = [long] $Matches.tick
		if ($seenTicks.ContainsKey($tick)) {
			throw "Duplicate tick '$tick' after the marker in $resolvedPath."
		}
		$seenTicks[$tick] = $true
		$noiseCompleted = [long] $Matches.noise_completed
		$noiseFailed = [long] $Matches.noise_failed
		$noiseActiveStart = [long] $Matches.noise_active_start
		$noiseActiveEnd = [long] $Matches.noise_active_end
		$localParallelism = [int] $Matches.local_parallelism
		if ($Mode -eq 'VANILLA' -and $localParallelism -ne -1) {
			throw "Vanilla tick '$tick' unexpectedly reported local pool data in $resolvedPath."
		}
		if ($requiresLocalPool -and $localParallelism -lt 1) {
			throw "Local tick '$tick' did not report a live local pool in $resolvedPath."
		}
		if ($Mode -eq 'LOCAL_BACKEND' -and -not $requiresLocalPool -and $localParallelism -ne -1) {
			throw "Delegating tick '$tick' unexpectedly reported dedicated local pool data in $resolvedPath."
		}

		$localCapacity = [int] $Matches.local_capacity
		$localAdmitted = [int] $Matches.local_admitted
		$localActive = [int] $Matches.local_active
		$records += [PSCustomObject]@{
			timestamp = $Matches.timestamp
			run_id = $CurrentRunId
			mode = $Mode
			scheduler = $scheduler
			tick = $tick
			elapsed_ms = [double]::Parse($Matches.elapsed, $invariantCulture)
			over_budget = [bool]::Parse($Matches.over)
			budget_ms = [double]::Parse($Matches.budget, $invariantCulture)
			started_nanos = [long] $Matches.started
			completed_nanos = [long] $Matches.completed
			noise_completed = $noiseCompleted
			noise_failed = $noiseFailed
			noise_active_start = $noiseActiveStart
			noise_active_end = $noiseActiveEnd
			worldgen_active = $noiseCompleted -gt 0 -or $noiseFailed -gt 0 -or $noiseActiveStart -gt 0 -or $noiseActiveEnd -gt 0
			local_parallelism = $localParallelism
			local_pool_size = [int] $Matches.local_pool_size
			local_active = $localActive
			local_running = [int] $Matches.local_running
			local_queued = [int] $Matches.local_queued
			local_admitted = $localAdmitted
			local_admission_capacity = $localCapacity
			local_task_active = [int] $Matches.local_task_active
			local_peak_task_active = [int] $Matches.local_peak_task_active
			local_peak_admitted = [int] $Matches.local_peak_admitted
			local_active_ratio = if ($localParallelism -gt 0) { $localActive / [double] $localParallelism } else { [double]::NaN }
			local_admitted_ratio = if ($localCapacity -gt 0) { $localAdmitted / [double] $localCapacity } else { [double]::NaN }
			local_fallbacks_delta = [long] $Matches.fallbacks
		}
	}

	if ($records.Count -eq 0) {
		throw "No server tick records were found after the marker in $resolvedPath."
	}
	return $records
}

function Get-Summary {
	param(
		[object[]] $Records,
		[string] $Mode,
		[string] $CurrentRunId
	)

	$active = @($Records | Where-Object { $_.worldgen_active })
	if ($active.Count -eq 0) {
		throw "Mode $Mode has no worldgen-active tick records."
	}
	$elapsed = Get-Statistics ([double[]] @($active | ForEach-Object { $_.elapsed_ms }))
	$overBudget = @($active | Where-Object { $_.over_budget }).Count
	$firstStarted = ($active.started_nanos | Measure-Object -Minimum).Minimum
	$lastCompleted = ($active.completed_nanos | Measure-Object -Maximum).Maximum
	$maxNoiseActive = ((@($active.noise_active_start) + @($active.noise_active_end)) | Measure-Object -Maximum).Maximum
	$local = @($active | Where-Object { $_.local_parallelism -gt 0 })

	return [PSCustomObject]@{
		run_id = $CurrentRunId
		mode = $Mode
		scheduler = @($Records.scheduler | Select-Object -Unique) -join ','
		total_ticks_after_marker = $Records.Count
		worldgen_active_ticks = $active.Count
		over_budget_ticks = $overBudget
		over_budget_percent = $overBudget * 100.0 / $active.Count
		budget_ms = @($active.budget_ms | Select-Object -Unique) -join ','
		tick_mean_ms = $elapsed.mean
		tick_p95_ms = $elapsed.p95
		tick_p99_ms = $elapsed.p99
		tick_standard_deviation_ms = $elapsed.standard_deviation
		tick_min_ms = $elapsed.minimum
		tick_max_ms = $elapsed.maximum
		measurement_window_ms = ($lastCompleted - $firstStarted) / 1000000.0
		noise_completed = ($active.noise_completed | Measure-Object -Sum).Sum
		noise_failed = ($active.noise_failed | Measure-Object -Sum).Sum
		max_noise_active = $maxNoiseActive
		local_sampled_ticks = $local.Count
		local_active_mean = if ($local.Count -eq 0) { [double]::NaN } else { ($local.local_active | Measure-Object -Average).Average }
		local_active_max = if ($local.Count -eq 0) { [double]::NaN } else { ($local.local_active | Measure-Object -Maximum).Maximum }
		local_running_max = if ($local.Count -eq 0) { [double]::NaN } else { ($local.local_running | Measure-Object -Maximum).Maximum }
		local_queued_mean = if ($local.Count -eq 0) { [double]::NaN } else { ($local.local_queued | Measure-Object -Average).Average }
		local_queued_max = if ($local.Count -eq 0) { [double]::NaN } else { ($local.local_queued | Measure-Object -Maximum).Maximum }
		local_admitted_mean = if ($local.Count -eq 0) { [double]::NaN } else { ($local.local_admitted | Measure-Object -Average).Average }
		local_admitted_max = if ($local.Count -eq 0) { [double]::NaN } else { ($local.local_admitted | Measure-Object -Maximum).Maximum }
		local_peak_task_active_max = if ($local.Count -eq 0) { [double]::NaN } else { ($local.local_peak_task_active | Measure-Object -Maximum).Maximum }
		local_peak_admitted_max = if ($local.Count -eq 0) { [double]::NaN } else { ($local.local_peak_admitted | Measure-Object -Maximum).Maximum }
		local_fallbacks = ($active.local_fallbacks_delta | Measure-Object -Sum).Sum
	}
}

$vanillaRecords = @(Read-TickRecords -Path $VanillaLog -Mode 'VANILLA' -RequiredMarker $Marker -CurrentRunId $RunId)
$localRecords = @(Read-TickRecords -Path $LocalLog -Mode 'LOCAL_BACKEND' -RequiredMarker $Marker -CurrentRunId $RunId)
$summaries = @(
	Get-Summary -Records $vanillaRecords -Mode 'VANILLA' -CurrentRunId $RunId
	Get-Summary -Records $localRecords -Mode 'LOCAL_BACKEND' -CurrentRunId $RunId
)
if ($summaries[0].noise_completed -ne $summaries[1].noise_completed) {
	throw "Measured NOISE completion counts differ: vanilla=$($summaries[0].noise_completed), local=$($summaries[1].noise_completed)."
}

$comparison = [PSCustomObject]@{
	run_id = $RunId
	vanilla_scheduler = $summaries[0].scheduler
	local_scheduler = $summaries[1].scheduler
	noise_completed = $summaries[0].noise_completed
	vanilla_active_ticks = $summaries[0].worldgen_active_ticks
	local_active_ticks = $summaries[1].worldgen_active_ticks
	vanilla_tick_mean_ms = $summaries[0].tick_mean_ms
	local_tick_mean_ms = $summaries[1].tick_mean_ms
	tick_mean_delta_ms = $summaries[1].tick_mean_ms - $summaries[0].tick_mean_ms
	vanilla_tick_p95_ms = $summaries[0].tick_p95_ms
	local_tick_p95_ms = $summaries[1].tick_p95_ms
	tick_p95_delta_ms = $summaries[1].tick_p95_ms - $summaries[0].tick_p95_ms
	vanilla_over_budget_percent = $summaries[0].over_budget_percent
	local_over_budget_percent = $summaries[1].over_budget_percent
	over_budget_percent_delta = $summaries[1].over_budget_percent - $summaries[0].over_budget_percent
	vanilla_max_noise_active = $summaries[0].max_noise_active
	local_max_noise_active = $summaries[1].max_noise_active
	local_active_max = $summaries[1].local_active_max
	local_queued_max = $summaries[1].local_queued_max
	local_admitted_max = $summaries[1].local_admitted_max
	local_peak_task_active_max = $summaries[1].local_peak_task_active_max
	local_peak_admitted_max = $summaries[1].local_peak_admitted_max
	local_fallbacks = $summaries[1].local_fallbacks
}

$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
@($vanillaRecords + $localRecords) |
	Sort-Object mode, tick |
	Export-Csv -LiteralPath (Join-Path $outputPath 'tick-records.csv') -NoTypeInformation -Encoding utf8
$summaries | Export-Csv -LiteralPath (Join-Path $outputPath 'tick-summary.csv') -NoTypeInformation -Encoding utf8
$comparison | Export-Csv -LiteralPath (Join-Path $outputPath 'tick-comparison.csv') -NoTypeInformation -Encoding utf8
$summaries | Format-Table mode, scheduler, worldgen_active_ticks, over_budget_ticks, tick_mean_ms, tick_p95_ms, tick_max_ms, noise_completed, max_noise_active, local_peak_task_active_max, local_peak_admitted_max -AutoSize
Write-Output ('noise_completed={0} tick_mean_delta_ms={1:F4} tick_p95_delta_ms={2:F4} over_budget_percent_delta={3:F2}' -f $comparison.noise_completed, $comparison.tick_mean_delta_ms, $comparison.tick_p95_delta_ms, $comparison.over_budget_percent_delta)
Write-Output "output_directory=$outputPath"
