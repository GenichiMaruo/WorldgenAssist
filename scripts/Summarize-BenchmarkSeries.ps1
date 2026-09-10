[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)]
	[string[]] $RunDirectories,

	[Parameter(Mandatory = $true)]
	[string] $OutputDirectory,

	[Parameter(Mandatory = $true)]
	[string] $SeriesId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$invariantCulture = [Globalization.CultureInfo]::InvariantCulture
$modes = @('VANILLA', 'LOCAL_BACKEND')

function Convert-ToDouble {
	param([object] $Value)
	return [double]::Parse([string] $Value, $invariantCulture)
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
	$sumSquaredDeviation = ($Values | ForEach-Object {
		[Math]::Pow($_ - $mean, 2.0)
	} | Measure-Object -Sum).Sum
	return [PSCustomObject]@{
		mean = $mean
		p95 = Get-NearestRankPercentile $Values 0.95
		p99 = Get-NearestRankPercentile $Values 0.99
		standard_deviation = [Math]::Sqrt($sumSquaredDeviation / $Values.Count)
		minimum = ($Values | Measure-Object -Minimum).Minimum
		maximum = ($Values | Measure-Object -Maximum).Maximum
	}
}

$runRows = @()
$activeTickRows = @()
$seenRunIds = @{}

foreach ($runDirectory in $RunDirectories) {
	$resolvedRun = (Resolve-Path -LiteralPath $runDirectory).Path
	$noisePath = Join-Path $resolvedRun 'noise-results\noise-stage-summary.csv'
	$tickPath = Join-Path $resolvedRun 'tick-results\tick-summary.csv'
	$tickRecordsPath = Join-Path $resolvedRun 'tick-results\tick-records.csv'
	$noise = @(Import-Csv -LiteralPath $noisePath)
	$ticks = @(Import-Csv -LiteralPath $tickPath)
	$tickRecords = @(Import-Csv -LiteralPath $tickRecordsPath)

	if ($noise.Count -ne 2 -or $ticks.Count -ne 2) {
		throw "Expected exactly two mode summaries in $resolvedRun."
	}
	$runId = $noise[0].run_id
	if ($seenRunIds.ContainsKey($runId)) {
		throw "Duplicate run_id '$runId'."
	}
	$seenRunIds[$runId] = $true
	if (@($noise | Where-Object run_id -ne $runId).Count -gt 0 -or
		@($ticks | Where-Object run_id -ne $runId).Count -gt 0 -or
		@($tickRecords | Where-Object run_id -ne $runId).Count -gt 0) {
		throw "Mixed run IDs found in $resolvedRun."
	}

	foreach ($mode in $modes) {
		$noiseMode = @($noise | Where-Object mode -eq $mode)
		$tickMode = @($ticks | Where-Object mode -eq $mode)
		$activeMode = @($tickRecords | Where-Object {
			$_.mode -eq $mode -and [bool]::Parse($_.worldgen_active)
		})
		if ($noiseMode.Count -ne 1 -or $tickMode.Count -ne 1) {
			throw "Run '$runId' does not contain one summary for mode '$mode'."
		}
		if ([long] $noiseMode[0].count -ne [long] $tickMode[0].noise_completed) {
			throw "Run '$runId' mode '$mode' has unequal NOISE and tick completion counts."
		}
		if ($activeMode.Count -ne [int] $tickMode[0].worldgen_active_ticks) {
			throw "Run '$runId' mode '$mode' has an active-tick count mismatch."
		}

		$runRows += [PSCustomObject]@{
			series_id = $SeriesId
			run_id = $runId
			mode = $mode
			scheduler = $noiseMode[0].scheduler
			noise_count = [long] $noiseMode[0].count
			noise_failures = [long] $noiseMode[0].failures
			stage_mean_ms = Convert-ToDouble $noiseMode[0].stage_mean_ms
			queue_mean_ms = Convert-ToDouble $noiseMode[0].queue_mean_ms
			compute_mean_ms = Convert-ToDouble $noiseMode[0].compute_mean_ms
			cpu_supported_count = [long] $noiseMode[0].cpu_supported_count
			cpu_mean_ms = Convert-ToDouble $noiseMode[0].cpu_mean_ms
			workload_span_ms = Convert-ToDouble $noiseMode[0].workload_span_ms
			throughput_tasks_per_second = Convert-ToDouble $noiseMode[0].throughput_tasks_per_second
			worldgen_active_ticks = [long] $tickMode[0].worldgen_active_ticks
			over_budget_ticks = [long] $tickMode[0].over_budget_ticks
			tick_mean_ms = Convert-ToDouble $tickMode[0].tick_mean_ms
			tick_p95_ms = Convert-ToDouble $tickMode[0].tick_p95_ms
			tick_max_ms = Convert-ToDouble $tickMode[0].tick_max_ms
			measurement_window_ms = Convert-ToDouble $tickMode[0].measurement_window_ms
			max_noise_active = [long] $tickMode[0].max_noise_active
			local_peak_task_active_max = Convert-ToDouble $tickMode[0].local_peak_task_active_max
			local_peak_admitted_max = Convert-ToDouble $tickMode[0].local_peak_admitted_max
			local_fallbacks = [long] $tickMode[0].local_fallbacks
		}

		foreach ($activeTick in $activeMode) {
			$activeTickRows += [PSCustomObject]@{
				series_id = $SeriesId
				run_id = $runId
				mode = $mode
				tick = [long] $activeTick.tick
				elapsed_ms = Convert-ToDouble $activeTick.elapsed_ms
				over_budget = [bool]::Parse($activeTick.over_budget)
				noise_completed = [long] $activeTick.noise_completed
				noise_failed = [long] $activeTick.noise_failed
				noise_active_start = [long] $activeTick.noise_active_start
				noise_active_end = [long] $activeTick.noise_active_end
				local_peak_task_active = [int] $activeTick.local_peak_task_active
				local_peak_admitted = [int] $activeTick.local_peak_admitted
				local_fallbacks_delta = [long] $activeTick.local_fallbacks_delta
			}
		}
	}

	if ([long] $noise[0].count -ne [long] $noise[1].count) {
		throw "Run '$runId' has unequal vanilla/local NOISE counts."
	}
}

$modeSummaries = foreach ($mode in $modes) {
	$modeRuns = @($runRows | Where-Object mode -eq $mode)
	$modeTicks = @($activeTickRows | Where-Object mode -eq $mode)
	if ($modeRuns.Count -ne $RunDirectories.Count) {
		throw "Mode '$mode' does not have one row for every run."
	}
	$tickStatistics = Get-Statistics @($modeTicks | ForEach-Object elapsed_ms)
	$overBudget = @($modeTicks | Where-Object over_budget).Count
	[PSCustomObject]@{
		series_id = $SeriesId
		mode = $mode
		scheduler = $modeRuns[0].scheduler
		runs = $modeRuns.Count
		noise_count = ($modeRuns.noise_count | Measure-Object -Sum).Sum
		noise_failures = ($modeRuns.noise_failures | Measure-Object -Sum).Sum
		stage_mean_of_run_means_ms = ($modeRuns.stage_mean_ms | Measure-Object -Average).Average
		queue_mean_of_run_means_ms = ($modeRuns.queue_mean_ms | Measure-Object -Average).Average
		compute_mean_of_run_means_ms = ($modeRuns.compute_mean_ms | Measure-Object -Average).Average
		cpu_supported_count = ($modeRuns.cpu_supported_count | Measure-Object -Sum).Sum
		cpu_mean_of_run_means_ms = ($modeRuns.cpu_mean_ms | Measure-Object -Average).Average
		workload_span_mean_ms = ($modeRuns.workload_span_ms | Measure-Object -Average).Average
		throughput_mean_tasks_per_second = ($modeRuns.throughput_tasks_per_second | Measure-Object -Average).Average
		worldgen_active_ticks = $modeTicks.Count
		over_budget_ticks = $overBudget
		over_budget_percent = 100.0 * $overBudget / $modeTicks.Count
		pooled_tick_mean_ms = $tickStatistics.mean
		pooled_tick_p95_ms = $tickStatistics.p95
		pooled_tick_p99_ms = $tickStatistics.p99
		pooled_tick_standard_deviation_ms = $tickStatistics.standard_deviation
		pooled_tick_min_ms = $tickStatistics.minimum
		pooled_tick_max_ms = $tickStatistics.maximum
		measurement_window_mean_ms = ($modeRuns.measurement_window_ms | Measure-Object -Average).Average
		max_noise_active = ($modeRuns.max_noise_active | Measure-Object -Maximum).Maximum
		local_peak_task_active_max = if ($mode -eq 'LOCAL_BACKEND') {
			($modeRuns.local_peak_task_active_max | Measure-Object -Maximum).Maximum
		} else { [double]::NaN }
		local_peak_admitted_max = if ($mode -eq 'LOCAL_BACKEND') {
			($modeRuns.local_peak_admitted_max | Measure-Object -Maximum).Maximum
		} else { [double]::NaN }
		local_fallbacks = ($modeRuns.local_fallbacks | Measure-Object -Sum).Sum
	}
}

$vanilla = $modeSummaries | Where-Object mode -eq 'VANILLA'
$local = $modeSummaries | Where-Object mode -eq 'LOCAL_BACKEND'
$comparison = [PSCustomObject]@{
	series_id = $SeriesId
	runs = $RunDirectories.Count
	noise_count_per_mode = $vanilla.noise_count
	stage_mean_delta_ms = $local.stage_mean_of_run_means_ms - $vanilla.stage_mean_of_run_means_ms
	queue_mean_delta_ms = $local.queue_mean_of_run_means_ms - $vanilla.queue_mean_of_run_means_ms
	compute_mean_delta_ms = $local.compute_mean_of_run_means_ms - $vanilla.compute_mean_of_run_means_ms
	cpu_mean_delta_ms = $local.cpu_mean_of_run_means_ms - $vanilla.cpu_mean_of_run_means_ms
	workload_span_mean_delta_ms = $local.workload_span_mean_ms - $vanilla.workload_span_mean_ms
	throughput_mean_delta_tasks_per_second = $local.throughput_mean_tasks_per_second - $vanilla.throughput_mean_tasks_per_second
	pooled_tick_mean_delta_ms = $local.pooled_tick_mean_ms - $vanilla.pooled_tick_mean_ms
	pooled_tick_p95_delta_ms = $local.pooled_tick_p95_ms - $vanilla.pooled_tick_p95_ms
	over_budget_percent_delta = $local.over_budget_percent - $vanilla.over_budget_percent
	local_peak_task_active_max = $local.local_peak_task_active_max
	local_peak_admitted_max = $local.local_peak_admitted_max
	local_fallbacks = $local.local_fallbacks
}

$resolvedOutput = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null
$runRows | Export-Csv -LiteralPath (Join-Path $resolvedOutput 'series-run-summary.csv') -NoTypeInformation
$activeTickRows | Export-Csv -LiteralPath (Join-Path $resolvedOutput 'series-active-ticks.csv') -NoTypeInformation
$modeSummaries | Export-Csv -LiteralPath (Join-Path $resolvedOutput 'series-mode-summary.csv') -NoTypeInformation
@($comparison) | Export-Csv -LiteralPath (Join-Path $resolvedOutput 'series-comparison.csv') -NoTypeInformation

$modeSummaries | Format-Table mode, runs, noise_count, stage_mean_of_run_means_ms, cpu_mean_of_run_means_ms, worldgen_active_ticks, over_budget_ticks, pooled_tick_mean_ms, pooled_tick_p95_ms, local_peak_task_active_max, local_peak_admitted_max -AutoSize
Write-Output "stage_mean_delta_ms=$($comparison.stage_mean_delta_ms) cpu_mean_delta_ms=$($comparison.cpu_mean_delta_ms) pooled_tick_mean_delta_ms=$($comparison.pooled_tick_mean_delta_ms)"
Write-Output "output_directory=$resolvedOutput"
