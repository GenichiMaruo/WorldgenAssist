[CmdletBinding()]
param([switch] $Execute, [string] $ReuseMatrixSummary)

# One bounded, sequential gate for the 26.3 custom-dimension and density-wire
# change. Child output stays in test-artifacts; print one final summary only.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifacts = Join-Path $workspace 'test-artifacts'
$runRoot = Join-Path $artifacts ('focused-worldgen-gate-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$jdk = 'C:\Program Files\Java\jdk-25.0.4'
$testClasses = @(
    'io.github.genichimaruo.worldgenassist.common.TerrainDensityResultEnvelope263Test',
    'io.github.genichimaruo.worldgenassist.client.ClientDensitySampler263Test',
    'io.github.genichimaruo.worldgenassist.server.RemoteWorldgenEligibility263Test',
    'io.github.genichimaruo.worldgenassist.server.RemoteDensityValidator263Test'
)
$caseIds = @(
    'correctness-fixture-assisted-p1-direct',
    'correctness-overworld-assisted-p1-direct',
    'performance-overworld-assisted-p1-cache_prediction_validation'
)

if (-not $Execute) {
    [ordered]@{
        scope = 'focused 26.3 gate; paired fixture/Overworld correctness and one performance profile'
        fabric_tests = $testClasses
        fabric_pairs = $caseIds
        forge_tests = @('io.github.genichimaruo.worldgenassist.forge.ForgeResultAssemblerTest')
        native_builds = @('Forge', 'NeoForge')
        execution = 'sequential; no full test suite or full matrix'
        reuse_matrix_summary = $ReuseMatrixSummary
    } | ConvertTo-Json -Depth 5
    exit 0
}

if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin/java.exe') -PathType Leaf)) {
    throw "Pinned JDK is missing: $jdk"
}
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$lockPath = Join-Path $artifacts 'focused-worldgen-gate.lock'
$lock = $null
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

function Invoke-GateStep {
    param([string] $Name, [string] $Executable, [string[]] $Arguments,
        [string] $WorkingDirectory, [int] $TimeoutSeconds)
    $stdoutPath = Join-Path $runRoot ($Name + '-stdout.log')
    $stderrPath = Join-Path $runRoot ($Name + '-stderr.log')
    $began = Get-Date
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    try {
        $finished = $process.WaitForExit($TimeoutSeconds * 1000)
        if (-not $finished) {
            $process.Kill($true)
            [void] $process.WaitForExit(30000)
        }
        return [ordered]@{
            name = $Name
            status = if (-not $finished) { 'TIMED_OUT' } elseif ($process.ExitCode -eq 0) { 'PASSED' } else { 'FAILED' }
            exit_code = if ($finished) { $process.ExitCode } else { $null }
            elapsed_seconds = [math]::Round(((Get-Date) - $began).TotalSeconds, 1)
            stdout = $stdoutPath
            stderr = $stderrPath
        }
    } finally {
        $process.Dispose()
    }
}

try {
    $lock = [IO.FileStream]::new($lockPath, [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write, [IO.FileShare]::None)
    New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
    $steps = @()
    $pwsh = (Get-Command pwsh.exe -ErrorAction Stop).Source
    $matrixSummary = $null
    $matrixSummaryPath = $null
    if (-not [string]::IsNullOrWhiteSpace($ReuseMatrixSummary)) {
        $matrixSummaryPath = (Resolve-Path -LiteralPath $ReuseMatrixSummary -ErrorAction Stop).Path
        $matrixSummary = Get-Content -LiteralPath $matrixSummaryPath -Raw | ConvertFrom-Json
        $artifact = & (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Workspace $workspace
        $artifactHash = (Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash
        $required = @($caseIds | ForEach-Object { $_; ($_ -replace '-assisted-', '-vanilla-') })
        $passedCases = @($matrixSummary.cases | Where-Object { $_.status -eq 'PASSED' } | ForEach-Object { $_.id })
        if ($matrixSummary.totals.failed -ne 0 -or $matrixSummary.artifact.sha256 -ne $artifactHash -or
            @($required | Where-Object { $_ -notin $passedCases }).Count -ne 0) {
            throw 'The reusable matrix summary does not match the current artifact and required passed cases.'
        }
        $steps += [ordered]@{ name = 'fabric-matrix'; status = 'PASSED'; reason = 'Reused completed exact-artifact evidence.' }
    } else {
        $matrixArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            (Join-Path $PSScriptRoot 'Run-ValidationMatrix.ps1'), '-Execute', '-IncludeFixture',
            '-CaseId', ($caseIds -join ','), '-TestClass', ($testClasses -join ','))
        $steps += Invoke-GateStep -Name 'fabric-matrix' -Executable $pwsh -Arguments $matrixArgs `
            -WorkingDirectory $workspace -TimeoutSeconds 14400
    }

    $forge = Join-Path $workspace 'loaders/forge'
    $neo = Join-Path $workspace 'loaders/neoforge'
    if ($steps[0].status -eq 'PASSED') {
        $steps += Invoke-GateStep -Name 'forge-build' -Executable "$env:SystemRoot\System32\cmd.exe" `
            -Arguments @('/d', '/c', (Join-Path $workspace 'gradlew.bat'), '-p', $forge, 'test',
                '--tests', 'io.github.genichimaruo.worldgenassist.forge.ForgeResultAssemblerTest',
                'build') -WorkingDirectory $forge -TimeoutSeconds 3600
        $steps += Invoke-GateStep -Name 'neoforge-build' -Executable "$env:SystemRoot\System32\cmd.exe" `
            -Arguments @('/d', '/c', (Join-Path $workspace 'gradlew.bat'), '-p', $neo, 'build', '-x', 'test') `
            -WorkingDirectory $neo -TimeoutSeconds 3600
    } else {
        $steps += [ordered]@{ name = 'forge-build'; status = 'SKIPPED'; reason = 'Fabric focused gate failed.' }
        $steps += [ordered]@{ name = 'neoforge-build'; status = 'SKIPPED'; reason = 'Fabric focused gate failed.' }
    }

    if ($null -eq $matrixSummary) {
        $matrixOutput = Get-Content -LiteralPath $steps[0].stdout -Raw
        $summaryMatch = [regex]::Match($matrixOutput, 'VALIDATION_MATRIX_COMPLETE summary=(?<path>[^\r\n ]+)')
        if ($summaryMatch.Success -and (Test-Path -LiteralPath $summaryMatch.Groups['path'].Value)) {
            $matrixSummaryPath = $summaryMatch.Groups['path'].Value
            $matrixSummary = Get-Content -LiteralPath $matrixSummaryPath -Raw | ConvertFrom-Json
        }
    }
    $failed = @($steps | Where-Object { $_.status -ne 'PASSED' })
    if ($null -ne $matrixSummary -and $matrixSummary.totals.failed -gt 0) {
        $failed += [ordered]@{ name = 'fabric-matrix-cases'; status = 'FAILED'; failures = $matrixSummary.failures }
    }
    $summary = [ordered]@{
        schema = 'worldgen-assist.focused-worldgen-gate.v1'
        run_root = $runRoot
        completed_at = (Get-Date).ToString('o')
        steps = $steps
        matrix_summary = $matrixSummaryPath
        matrix_totals = if ($null -ne $matrixSummary) { $matrixSummary.totals } else { $null }
        failures = $failed
        success = $failed.Count -eq 0 -and $null -ne $matrixSummary
    }
    $summaryPath = Join-Path $runRoot 'summary.json'
    [IO.File]::WriteAllText($summaryPath, ($summary | ConvertTo-Json -Depth 12))
    Write-Output "FOCUSED_WORLDGEN_GATE_COMPLETE summary=$summaryPath success=$($summary.success)"
    if (-not $summary.success) { exit 1 }
} finally {
    if ($null -ne $lock) {
        $lock.Dispose()
        Remove-Item -LiteralPath $lockPath -Force
    }
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
}
