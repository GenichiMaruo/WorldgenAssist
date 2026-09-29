[CmdletBinding()]
param([switch] $Execute)

# One sequential gate for the bounded parallel-worker scheduler. Child logs
# stay in test-artifacts; only the final summary is printed to the console.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root = Join-Path $workspace ('test-artifacts/parallel-assist-gate-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$case = 'performance-overworld-assisted-p2-cache_prediction_validation'
$test = 'io.github.genichimaruo.worldgenassist.server.WorkerRegistry263Test'
if (-not $Execute) {
    [ordered]@{ test = $test; paired_scenario = $case; native_builds = @('Forge', 'NeoForge'); sequence = 'single batch' } | ConvertTo-Json
    exit 0
}

$jdk = 'C:\Program Files\Java\jdk-25.0.4'
if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin/java.exe'))) { throw "Missing pinned JDK: $jdk" }
New-Item -ItemType Directory -Force -Path $root | Out-Null
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

function Invoke-Step {
    param([string] $Name, [string] $File, [string[]] $Arguments, [string] $Directory, [int] $TimeoutSeconds)
    $out = Join-Path $root ($Name + '-stdout.log')
    $err = Join-Path $root ($Name + '-stderr.log')
    $process = Start-Process -FilePath $File -ArgumentList $Arguments -WorkingDirectory $Directory `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    try {
        $finished = $process.WaitForExit($TimeoutSeconds * 1000)
        if (-not $finished) { $process.Kill($true); [void] $process.WaitForExit(30000) }
        return [ordered]@{ name = $Name; status = if (-not $finished) { 'TIMED_OUT' } elseif ($process.ExitCode -eq 0) { 'PASSED' } else { 'FAILED' }; exit_code = if ($finished) { $process.ExitCode } else { $null }; stdout = $out; stderr = $err }
    } finally { $process.Dispose() }
}

try {
    $steps = @()
    $pwsh = (Get-Command pwsh.exe -ErrorAction Stop).Source
    $steps += Invoke-Step -Name 'fabric-pair-and-unit' -File $pwsh -Directory $workspace -TimeoutSeconds 14400 `
        -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'Run-ValidationMatrix.ps1'), '-Execute', '-CaseId', $case, '-TestClass', $test)
    if ($steps[0].status -eq 'PASSED') {
        foreach ($loader in @('forge', 'neoforge')) {
            $directory = Join-Path $workspace ("loaders/$loader")
            $steps += Invoke-Step -Name "$loader-build" -File "$env:SystemRoot\System32\cmd.exe" -Directory $directory -TimeoutSeconds 3600 `
                -Arguments @('/d', '/c', (Join-Path $workspace 'gradlew.bat'), '-p', $directory, 'build', '-x', 'test')
        }
    }
    $output = Get-Content -LiteralPath $steps[0].stdout -Raw
    $match = [regex]::Match($output, 'VALIDATION_MATRIX_COMPLETE summary=(?<path>[^\r\n ]+)')
    $matrixPath = if ($match.Success) { $match.Groups['path'].Value } else { $null }
    $matrix = if ($null -ne $matrixPath -and (Test-Path -LiteralPath $matrixPath)) { Get-Content -LiteralPath $matrixPath -Raw | ConvertFrom-Json } else { $null }
    $success = @($steps | Where-Object { $_.status -ne 'PASSED' }).Count -eq 0 -and $null -ne $matrix -and $matrix.totals.failed -eq 0
    $summary = [ordered]@{ schema = 'worldgen-assist.parallel-assist-gate.v1'; root = $root; steps = $steps; matrix_summary = $matrixPath; matrix_totals = if ($null -ne $matrix) { $matrix.totals } else { $null }; success = $success }
    $summaryPath = Join-Path $root 'summary.json'
    [IO.File]::WriteAllText($summaryPath, ($summary | ConvertTo-Json -Depth 10))
    Write-Output "PARALLEL_ASSIST_GATE_COMPLETE summary=$summaryPath success=$success"
    if (-not $success) { exit 1 }
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
}
