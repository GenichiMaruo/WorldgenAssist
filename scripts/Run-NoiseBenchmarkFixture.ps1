[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)]
	[ValidateSet('vanilla', 'delegate', 'local')]
	[string] $Mode,

	[Parameter(Mandatory = $true)]
	[ValidatePattern('^[A-Za-z0-9._-]+$')]
	[string] $WorldName,

	[Parameter(Mandatory = $true)]
	[string] $OutputLog,

	[ValidateRange(1, 64)]
	[int] $LocalWorkers = 19,

	[ValidateRange(0, 16)]
	[int] $LocalQueuePerWorker = 1,

	[ValidateRange(10, 180)]
	[int] $StartupTimeoutSeconds = 90,

	[ValidateRange(10, 300)]
	[int] $WorkloadTimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runDirectory = Join-Path $repositoryRoot 'run'
$propertiesPath = Join-Path $runDirectory 'server.properties'
$latestLogPath = Join-Path $runDirectory 'logs\latest.log'
$worldPath = Join-Path $runDirectory $WorldName
$outputLogPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputLog))
$benchmarkOutputRoot = [IO.Path]::GetFullPath((Join-Path $runDirectory 'benchmarks'))
$jdkDirectory = 'C:\Program Files\Java\jdk-25.0.4'
$gradleWrapper = Join-Path $repositoryRoot 'gradlew.bat'
$marker = 'CAWG_BENCHMARK_MEASURED_BEGIN'

if (-not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) {
	throw "Server properties not found: $propertiesPath"
}
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
	throw "Gradle wrapper not found: $gradleWrapper"
}
if (-not (Test-Path -LiteralPath $jdkDirectory -PathType Container)) {
	throw "Required JDK not found: $jdkDirectory"
}
if (Test-Path -LiteralPath $worldPath) {
	throw "Benchmark world already exists; refusing to reuse it: $worldPath"
}
$benchmarkOutputPrefix = $benchmarkOutputRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $outputLogPath.StartsWith($benchmarkOutputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
	throw "Output log must be inside the benchmark evidence directory: $benchmarkOutputRoot"
}
if (Test-Path -LiteralPath $outputLogPath) {
	throw "Output log already exists; refusing to overwrite benchmark evidence: $outputLogPath"
}

function Wait-ForLogCondition {
	param(
		[Diagnostics.Process] $Process,
		[scriptblock] $Condition,
		[int] $TimeoutSeconds,
		[string] $Description
	)

	$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
	while ([DateTime]::UtcNow -lt $deadline) {
		if ($Process.HasExited) {
			throw "Server exited before $Description; exit_code=$($Process.ExitCode)."
		}
		if (Test-Path -LiteralPath $latestLogPath -PathType Leaf) {
			try {
				$stream = [IO.FileStream]::new(
					$latestLogPath,
					[IO.FileMode]::Open,
					[IO.FileAccess]::Read,
					[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
				)
				try {
					$reader = [IO.StreamReader]::new($stream)
					try {
						$contents = $reader.ReadToEnd()
					} finally {
						$reader.Dispose()
					}
				} finally {
					$stream.Dispose()
				}
				if (& $Condition $contents) {
					return
				}
			} catch [IO.IOException] {
				# The logger can rotate latest.log between the existence check and open.
			}
		}
		Start-Sleep -Milliseconds 250
	}
	throw "Timed out waiting for $Description after $TimeoutSeconds seconds."
}

$originalProperties = [IO.File]::ReadAllText($propertiesPath)
$process = $null
$processStarted = $false
try {
	$benchmarkProperties = [regex]::Replace($originalProperties, '(?m)^level-name=.*$', "level-name=$WorldName")
	$benchmarkProperties = [regex]::Replace($benchmarkProperties, '(?m)^level-seed=.*$', 'level-seed=8675309')
	[IO.File]::WriteAllText($propertiesPath, $benchmarkProperties, [Text.UTF8Encoding]::new($false))

	$outputDirectory = Split-Path -Parent $outputLogPath
	New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

	$startInfo = [Diagnostics.ProcessStartInfo]::new()
	$startInfo.FileName = "$env:SystemRoot\System32\cmd.exe"
	$startInfo.ArgumentList.Add('/d')
	$startInfo.ArgumentList.Add('/c')
	$startInfo.ArgumentList.Add($gradleWrapper)
	$startInfo.ArgumentList.Add('runServer')
	$startInfo.ArgumentList.Add('--args')
	$startInfo.ArgumentList.Add('nogui')
	$startInfo.WorkingDirectory = $repositoryRoot
	$startInfo.UseShellExecute = $false
	$startInfo.CreateNoWindow = $true
	$startInfo.RedirectStandardInput = $true
	$startInfo.RedirectStandardOutput = $true
	$startInfo.RedirectStandardError = $true
	$startInfo.Environment['JAVA_HOME'] = $jdkDirectory
	$startInfo.Environment['Path'] = "$jdkDirectory\bin;$($startInfo.Environment['Path'])"
	$startInfo.Environment['WORLDGEN_ASSIST_NOISE_BACKEND'] = $Mode
	$startInfo.Environment['WORLDGEN_ASSIST_NOISE_BENCHMARK'] = 'true'
	$startInfo.Environment['WORLDGEN_ASSIST_TICK_BENCHMARK'] = 'true'
	$startInfo.Environment['WORLDGEN_ASSIST_NOISE_DIGEST'] = 'false'
	if ($Mode -eq 'local') {
		$startInfo.Environment['WORLDGEN_ASSIST_LOCAL_WORKERS'] = [string] $LocalWorkers
		$startInfo.Environment['WORLDGEN_ASSIST_LOCAL_QUEUE_PER_WORKER'] = [string] $LocalQueuePerWorker
	} else {
		$null = $startInfo.Environment.Remove('WORLDGEN_ASSIST_LOCAL_WORKERS')
		$null = $startInfo.Environment.Remove('WORLDGEN_ASSIST_LOCAL_QUEUE_PER_WORKER')
	}

	$process = [Diagnostics.Process]::new()
	$process.StartInfo = $startInfo
	if (-not $process.Start()) {
		throw 'Failed to start the benchmark server process.'
	}
	$processStarted = $true
	$process.BeginOutputReadLine()
	$process.BeginErrorReadLine()

	Wait-ForLogCondition -Process $process -TimeoutSeconds $StartupTimeoutSeconds -Description 'server readiness' -Condition {
		param($contents)
		$contents.Contains("Preparing level `"$WorldName`"") -and $contents.Contains('Done (')
	}

	$commands = @(
		"say $marker",
		'forceload add 1024 1024',
		'forceload add -1024 1024',
		'forceload add 1024 -1024',
		'forceload add -1024 -1024',
		'forceload add 3072 512',
		'forceload add -3072 512',
		'forceload add 512 3072',
		'forceload add 512 -3072'
	)
	foreach ($command in $commands) {
		$process.StandardInput.WriteLine($command)
	}
	$process.StandardInput.Flush()

	Wait-ForLogCondition -Process $process -TimeoutSeconds $WorkloadTimeoutSeconds -Description '648 measured NOISE tasks' -Condition {
		param($contents)
		$markerIndex = $contents.LastIndexOf($marker, [StringComparison]::Ordinal)
		if ($markerIndex -lt 0) {
			return $false
		}
		$measured = $contents.Substring($markerIndex)
		$stageCount = [regex]::Matches($measured, '\[CAWG\] stage\.(?:complete|failed) stage=noise').Count
		$taskCount = [regex]::Matches($measured, '\[CAWG\] task\.(?:complete|failed) stage=noise').Count
		return $stageCount -eq 648 -and $taskCount -eq 648
	}

	$process.StandardInput.WriteLine('stop')
	$process.StandardInput.Flush()
	if (-not $process.WaitForExit(30000)) {
		throw 'Server did not stop within 30 seconds.'
	}
	if ($process.ExitCode -ne 0) {
		throw "Benchmark server failed; exit_code=$($process.ExitCode)."
	}

	[IO.File]::Copy($latestLogPath, $outputLogPath, $false)
	Write-Output "mode=$Mode world=$WorldName measured_tasks=648 output_log=$outputLogPath"
} finally {
	if ($null -ne $process -and $processStarted -and -not $process.HasExited) {
		try {
			$process.StandardInput.WriteLine('stop')
			$process.StandardInput.Flush()
			if (-not $process.WaitForExit(30000)) {
				$process.Kill($true)
				$process.WaitForExit()
			}
		} catch {
			try {
				if (-not $process.HasExited) {
					$process.Kill($true)
				}
				$process.WaitForExit()
			} catch {
				Write-Warning "Unable to confirm benchmark server termination: $($_.Exception.Message)"
			}
		}
	}
	[IO.File]::WriteAllText($propertiesPath, $originalProperties, [Text.UTF8Encoding]::new($false))
}
