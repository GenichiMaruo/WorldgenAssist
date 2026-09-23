[CmdletBinding()]
param(
    [switch] $Execute,
    [string[]] $CaseId = @(),
    [string] $CapabilityManifest = (Join-Path $PSScriptRoot 'validation-capabilities.json'),
    [switch] $SkipPublicFixtureRegression,
    [switch] $SkipPerformance,
    [ValidateRange(60, 7200)]
    [int] $BuildTimeoutSeconds = 1800,
    [ValidateRange(60, 7200)]
    [int] $ScenarioTimeoutSeconds = 1800
)

# This is deliberately a batch runner, not a live dashboard.  It writes case
# logs and the final summary to its evidence directory, waits for each owned process once,
# and prints only the final summary path.  Do not tail it from an AI loop.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$minecraftTarget = [regex]::Match((Get-Content -LiteralPath (Join-Path $workspace 'gradle.properties') -Raw), '(?m)^minecraft_version=([^\r\n]+)\s*$')
if ($Execute -and ($minecraftTarget.Success -eq $false -or $minecraftTarget.Groups[1].Value.Trim() -ne '26.2')) {
    throw 'The installed-client validation matrix is pinned to Minecraft 26.2; port its assets, loader and public-fixture cases before executing it on another version.'
}
$artifactsRoot = Join-Path $workspace 'test-artifacts'
$runRoot = Join-Path $artifactsRoot ('validation-matrix-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$runLockPath = Join-Path $artifactsRoot 'validation-matrix.lock'
$jdkPath = 'C:\Program Files\Java\jdk-25.0.4'
$scenarioRunner = Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'
$settingsSmokeRunner = Join-Path $PSScriptRoot 'Run-SettingsSmoke.ps1'
$publicFixtureRunner = Join-Path $PSScriptRoot 'Run-SeededLeafRuntimeFixture.ps1'
$comparisonRunner = Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'
$pwshCommand = Get-Command 'pwsh.exe' -ErrorAction SilentlyContinue
$localPwsh = if ($null -eq $pwshCommand) { $null } else { $pwshCommand.Source }

function Write-Utf8File {
    param([string] $Path, [string] $Text)
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

function Write-JsonFile {
    param([string] $Path, [object] $Value)
    Write-Utf8File -Path $Path -Text ($Value | ConvertTo-Json -Depth 12)
}

function Get-SourceManifest {
    $files = @(Get-ChildItem -LiteralPath (Join-Path $workspace 'src') -File -Recurse)
    $scriptsRoot = Join-Path $workspace 'scripts'
    if (Test-Path -LiteralPath $scriptsRoot -PathType Container) {
        $files += Get-ChildItem -LiteralPath $scriptsRoot -File -Recurse
    }
    foreach ($name in @('build.gradle', 'settings.gradle', 'gradle.properties')) {
        $path = Join-Path $workspace $name
        if (Test-Path -LiteralPath $path -PathType Leaf) { $files += Get-Item -LiteralPath $path }
    }
    return @($files | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($workspace.Length + 1).Replace('\', '/')
        "$(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256 | Select-Object -ExpandProperty Hash)  $relative"
    })
}

function Get-ArtifactInfo {
    $artifactScript = Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1'
    if (-not (Test-Path -LiteralPath $artifactScript -PathType Leaf)) { return $null }
    try {
        $artifact = & $artifactScript -Workspace $workspace
        if (-not (Test-Path -LiteralPath $artifact.Path -PathType Leaf)) { return $null }
        return [ordered]@{
            path = [IO.Path]::GetFullPath($artifact.Path)
            sha256 = (Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash
            bytes = (Get-Item -LiteralPath $artifact.Path).Length
            version = $artifact.Version
            minecraft = $artifact.Minecraft
        }
    } catch { return $null }
}

function Save-JunitEvidence {
    param([string] $CaseDirectory, [datetime] $Started)
    $xmlRoot = Join-Path $workspace 'build/test-results/test'
    $xmlFiles = @(Get-ChildItem -LiteralPath $xmlRoot -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)
    if ($xmlFiles.Count -eq 0) {
        return [ordered]@{ present = $false; reason = 'No JUnit XML was produced.'; totals = $null; failures = @() }
    }
    $stale = @($xmlFiles | Where-Object { $_.LastWriteTime -lt $Started })
    if ($stale.Count -gt 0) {
        return [ordered]@{ present = $false; reason = 'JUnit XML is stale; test execution may not have started.'; totals = $null; failures = @() }
    }
    $copyRoot = Join-Path $CaseDirectory 'junit-xml'
    New-Item -ItemType Directory -Force -Path $copyRoot | Out-Null
    $totals = [ordered]@{ suites = 0; tests = 0; failures = 0; errors = 0; skipped = 0 }
    $failures = @()
    foreach ($xmlFile in $xmlFiles) {
        Copy-Item -LiteralPath $xmlFile.FullName -Destination $copyRoot
        [xml] $xml = Get-Content -LiteralPath $xmlFile.FullName
        $suite = $xml.testsuite
        if ($null -eq $suite) { continue }
        $totals.suites++
        foreach ($counter in @('tests', 'failures', 'errors', 'skipped')) {
            $value = $suite.GetAttribute($counter)
            if (-not [string]::IsNullOrWhiteSpace($value)) { $totals[$counter] += [int] $value }
        }
        foreach ($testCase in $suite.SelectNodes('testcase')) {
            foreach ($node in $testCase.SelectNodes('failure|error')) {
                if ($null -eq $node) { continue }
                $failures += [ordered]@{
                    suite = $suite.name
                    test = $testCase.name
                    type = $node.GetAttribute('type')
                    message = $node.GetAttribute('message')
                    xml = (Join-Path 'junit-xml' $xmlFile.Name).Replace('\', '/')
                }
            }
        }
    }
    return [ordered]@{ present = $true; reason = $null; totals = $totals; failures = @($failures) }
}

function Stop-OwnedProcessTree {
    param([Diagnostics.Process] $Process)
    if ($null -eq $Process -or $Process.HasExited) { return $true }
    try {
        # PID is the process started by this invocation; /T is limited to its tree.
        & "$env:SystemRoot\System32\taskkill.exe" '/PID' $Process.Id '/T' '/F' | Out-Null
        $Process.WaitForExit(30000) | Out-Null
        return $Process.HasExited
    } catch { return $false }
}

function Invoke-OwnedProcess {
    param(
        [string] $CaseId,
        [string] $FileName,
        [string[]] $Arguments,
        [string] $WorkingDirectory,
        [int] $TimeoutSeconds,
        [bool] $CaptureJunit
    )
    $caseDirectory = Join-Path $runRoot ("cases\$CaseId")
    New-Item -ItemType Directory -Force -Path $caseDirectory | Out-Null
    $stdoutPath = Join-Path $caseDirectory 'stdout.log'
    $stderrPath = Join-Path $caseDirectory 'stderr.log'
    $started = Get-Date
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FileName
    foreach ($argument in $Arguments) { $startInfo.ArgumentList.Add($argument) }
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    if (Test-Path -LiteralPath (Join-Path $jdkPath 'bin\java.exe')) {
        $startInfo.Environment['JAVA_HOME'] = $jdkPath
        $startInfo.Environment['Path'] = "$jdkPath\bin;$($startInfo.Environment['Path'])"
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $timedOut = $false
    $cleanedUp = $true
    try {
        if (-not $process.Start()) { throw 'Process did not start.' }
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $timedOut = $true
            $cleanedUp = Stop-OwnedProcessTree -Process $process
        }
        $output = $outputTask.GetAwaiter().GetResult()
        $error = $errorTask.GetAwaiter().GetResult()
        Write-Utf8File -Path $stdoutPath -Text $output
        Write-Utf8File -Path $stderrPath -Text $error
        $junit = if ($CaptureJunit) { Save-JunitEvidence -CaseDirectory $caseDirectory -Started $started } else { $null }
        $status = if ($timedOut) { 'TIMED_OUT' } elseif ($process.ExitCode -eq 0) { 'PASSED' } else { 'FAILED' }
        $failure = $null
        if ($CaptureJunit) {
            if (-not $junit.present) {
                $status = 'FAILED'
                $failure = $junit.reason
            } elseif ($junit.totals.tests -eq 0 -or $junit.totals.failures -ne 0 -or $junit.totals.errors -ne 0 -or $junit.totals.skipped -ne 0) {
                $status = 'FAILED'
                $failure = 'JUnit totals contain zero tests, failures, errors, or skipped tests.'
            }
        }
        if ($null -eq $failure -and $timedOut) { $failure = "Case exceeded its $TimeoutSeconds second timeout." }
        if ($null -eq $failure -and $status -eq 'FAILED') { $failure = 'Child process exited nonzero; see stdout/stderr and JUnit XML.' }
        return [ordered]@{
            id = $CaseId; status = $status
            started = $started.ToString('o'); ended = (Get-Date).ToString('o'); duration_seconds = ((Get-Date) - $started).TotalSeconds
            exit_code = if ($timedOut -and -not $process.HasExited) { $null } else { $process.ExitCode }
            timed_out = $timedOut; cleanup_safe = $cleanedUp; stdout = $stdoutPath; stderr = $stderrPath; junit = $junit; failure = $failure; reason = $null
        }
    } catch {
        Write-Utf8File -Path $stderrPath -Text $_.Exception.ToString()
        return [ordered]@{
            id = $CaseId; status = 'FAILED'; started = $started.ToString('o'); ended = (Get-Date).ToString('o')
            duration_seconds = ((Get-Date) - $started).TotalSeconds; exit_code = $null; timed_out = $false
            cleanup_safe = (Stop-OwnedProcessTree -Process $process); stdout = $stdoutPath; stderr = $stderrPath; junit = $null
            failure = $_.Exception.Message
        }
    } finally { $process.Dispose() }
}

function New-SkippedCase {
    param([string] $Id, [string] $Reason, [object] $PlanCase)
    return [ordered]@{ id = $Id; status = 'SKIPPED'; reason = $Reason; plan = $PlanCase; started = $null; ended = $null; failure = $null; stdout = $null; stderr = $null; junit = $null }
}

function Read-Capabilities {
    if (-not (Test-Path -LiteralPath $CapabilityManifest -PathType Leaf)) {
        return [ordered]@{ available = $false; reason = "Capability manifest is absent: $CapabilityManifest"; raw = $null }
    }
    try {
        $raw = Get-Content -LiteralPath $CapabilityManifest -Raw | ConvertFrom-Json
        if ($raw.schema -ne 'worldgen-assist.validation-capabilities.v1') { throw 'Unsupported or missing schema.' }
        return [ordered]@{ available = $true; reason = $null; raw = $raw }
    } catch {
        return [ordered]@{ available = $false; reason = "Capability manifest is invalid: $($_.Exception.Message)"; raw = $null }
    }
}

function Test-ScenarioCapability {
    param([object] $Capabilities, [object] $Artifact)
    if (-not $Capabilities.available) { return $Capabilities.reason }
    $runtime = $Capabilities.raw.runtime
    if ($null -eq $runtime -or -not [bool] $runtime.available) {
        return if ($null -ne $runtime -and $runtime.reason) { [string] $runtime.reason } else { 'All-dimension runtime capability is not declared available.' }
    }
    if (-not (Test-Path -LiteralPath $scenarioRunner -PathType Leaf)) { return "Scenario runner is absent: $scenarioRunner" }
    if ([string]::IsNullOrWhiteSpace($localPwsh)) { return 'PowerShell 7 (pwsh.exe) is required for local scenario execution.' }
    if ($null -eq $Artifact) { return 'The exact distribution JAR has not been built.' }
    if ([string] $runtime.artifact_policy -ne 'fresh_build_exact_version') { return 'Capability manifest must declare artifact_policy=fresh_build_exact_version.' }
    if (-not $runtime.loopback_only) { return 'Capability manifest does not attest loopback-only binding.' }
    if ([string]::IsNullOrWhiteSpace([string] $runtime.public_test_seed)) { return 'Capability manifest has no declared public test seed.' }
    $pinProperty = $runtime.PSObject.Properties['expected_artifact_sha256']
    $optionalPin = if ($null -eq $pinProperty) { '' } else { [string] $pinProperty.Value }
    if (-not [string]::IsNullOrWhiteSpace($optionalPin) -and $Artifact.sha256 -ne $optionalPin.ToUpperInvariant()) { return 'Built distribution JAR SHA-256 differs from the optional capability pin.' }
    return $null
}

function Test-AggregationCapability {
    param([object] $Capabilities)
    if (-not $Capabilities.available) { return $Capabilities.reason }
    $aggregation = $Capabilities.raw.aggregation
    if ($null -eq $aggregation -or -not [bool] $aggregation.available) {
        return if ($null -ne $aggregation -and $aggregation.reason) { [string] $aggregation.reason } else { 'Runtime/performance aggregation is not declared available.' }
    }
    if ([string]::IsNullOrWhiteSpace($localPwsh)) { return 'PowerShell 7 (pwsh.exe) is required for local aggregation.' }
    if (-not (Test-Path -LiteralPath $comparisonRunner -PathType Leaf)) { return "Comparison runner is absent: $comparisonRunner" }
    return $null
}

function Read-ScenarioContract {
    param([string] $CaseDirectory, [string] $ExpectedSha256)
    $path = Join-Path $CaseDirectory 'scenario-result.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return [ordered]@{ valid = $false; cleanupSafe = $false; reason = 'Scenario did not write scenario-result.json.' } }
    try {
        $result = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        if (-not $result.success) { return [ordered]@{ valid = $false; cleanupSafe = [bool]$result.cleanup_safe; reason = 'Scenario reported success=false.' } }
        if (-not $result.cleanup_safe -or -not $result.loopback_only) { return [ordered]@{ valid = $false; cleanupSafe = $false; reason = 'Scenario did not prove cleanup-safe loopback execution.' } }
        if ([string] $result.artifact_sha256 -ne $ExpectedSha256) { return [ordered]@{ valid = $false; cleanupSafe = [bool]$result.cleanup_safe; reason = 'Scenario exact JAR hash does not match the built artifact.' } }
        return [ordered]@{ valid = $true; cleanupSafe = $true; reason = $null }
    } catch { return [ordered]@{ valid = $false; cleanupSafe = $false; reason = "Invalid scenario-result.json: $($_.Exception.Message)" } }
}

function Read-SettingsSmokeContract {
    param([string] $CaseDirectory)
    $path=Join-Path $CaseDirectory 'settings-smoke-result.json'
    if(-not(Test-Path -LiteralPath $path -PathType Leaf)){return [ordered]@{valid=$false;cleanupSafe=$false;reason='Settings smoke did not write settings-smoke-result.json.'}}
    try{$result=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
        if($result.schema -ne 'worldgen-assist.settings-smoke-result.v1'){return [ordered]@{valid=$false;cleanupSafe=[bool]$result.cleanup_safe;reason='Settings smoke result schema is invalid.'}}
        if(-not [bool]$result.success){return [ordered]@{valid=$false;cleanupSafe=[bool]$result.cleanup_safe;reason="Settings smoke reported failure: $($result.failure)"}}
        if(-not [bool]$result.cleanup_safe){return [ordered]@{valid=$false;cleanupSafe=$false;reason='Settings smoke cleanup was not proved.'}}
        return [ordered]@{valid=$true;cleanupSafe=$true;reason=$null}
    }catch{return [ordered]@{valid=$false;cleanupSafe=$false;reason="Invalid settings smoke result: $($_.Exception.Message)"}}
}

function Read-AnalysisContract {
    $path = Join-Path $runRoot 'analysis\scenario-matrix-analysis.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return [ordered]@{ valid = $false; status = $null; reason = 'Aggregation did not write scenario-matrix-analysis.json.' } }
    try {
        $analysis = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        if ($analysis.schema -ne 'worldgen-assist.scenario-matrix-analysis.v1') { return [ordered]@{ valid = $false; status = $analysis.status; reason = 'Aggregation report schema is invalid.' } }
        if ($analysis.status -eq 'COMPLETE') { return [ordered]@{ valid = $true; status = 'COMPLETE'; reason = $null } }
        return [ordered]@{ valid = $false; status = $analysis.status; reason = "Aggregation completed with status=$($analysis.status); see $path" }
    } catch { return [ordered]@{ valid = $false; status = $null; reason = "Aggregation report is invalid: $($_.Exception.Message)" } }
}

$conditionProfiles = @(
    [ordered]@{ id = 'direct'; cache_entries = 0; prediction = $false; validation_cells = 8 },
    [ordered]@{ id = 'cache'; cache_entries = 128; prediction = $false; validation_cells = 8 },
    [ordered]@{ id = 'prediction'; cache_entries = 128; prediction = $true; validation_cells = 8 }
)
$performanceProfiles = @(
    [ordered]@{ id = 'baseline'; cache_entries = 0; prediction = $false; validation_cells = 0; warmup_runs = 1; measured_repeats = 3 },
    [ordered]@{ id = 'cache_prediction_validation'; cache_entries = 128; prediction = $true; validation_cells = 8; warmup_runs = 1; measured_repeats = 3 }
)
$scenarioPlan = @()
foreach ($dimension in @('overworld', 'the_nether', 'the_end')) {
    foreach ($mode in @('vanilla', 'assisted')) {
        foreach ($players in @(1, 2)) {
            foreach ($profile in $conditionProfiles) {
                $scenarioPlan += [ordered]@{ id = "correctness-$dimension-$mode-p$players-$($profile.id)"; purpose = 'correctness'; dimension = $dimension; mode = $mode; players = $players; cache_entries = $profile.cache_entries; prediction = $profile.prediction; validation_cells = $profile.validation_cells }
            }
            foreach ($profile in $performanceProfiles) {
                $scenarioPlan += [ordered]@{ id = "performance-$dimension-$mode-p$players-$($profile.id)"; purpose = 'performance'; dimension = $dimension; mode = $mode; players = $players; cache_entries = $profile.cache_entries; prediction = $profile.prediction; validation_cells = $profile.validation_cells; warmup_runs = $profile.warmup_runs; measured_repeats = $profile.measured_repeats }
            }
        }
    }
}

$selection = @($CaseId | ForEach-Object { $_ -split ',' } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
$targeted = $selection.Count -gt 0
if ($targeted) {
    foreach ($id in $selection) { if ($id -notin @($scenarioPlan | ForEach-Object { $_.id })) { throw "Unknown scenario CaseId: $id" } }
    # Correctness/performance claims need an independent vanilla/assisted pair.
    $selection = @($selection + @($selection | ForEach-Object { if ($_ -match '-assisted-') { $_ -replace '-assisted-', '-vanilla-' } else { $_ -replace '-vanilla-', '-assisted-' } }) | Sort-Object -Unique)
    $scenarioPlan = @($scenarioPlan | Where-Object { $_.id -in $selection })
}
$plan = [ordered]@{
    schema = 'worldgen-assist.validation-matrix-plan.v1'
    scope = if ($targeted) { 'selected paired scenarios; not a full regression run' } else { 'full matrix' }
    generated_at = (Get-Date).ToString('o')
    execution_note = 'Every scenario is isolated. Runtime/performance success requires scenario-result.json; unimplemented capability remains SKIPPED.'
    build_cases = @(
        [ordered]@{ id = 'compile'; gradle = @('compileJava', 'compileClientJava', '--continue'); junit = $false },
        [ordered]@{ id = 'unit'; gradle = @('test', '--rerun-tasks', '--continue'); junit = $true },
        [ordered]@{ id = 'build'; gradle = @('build', '-x', 'test', '--continue'); junit = $false }
    )
    runtime_and_performance_cases = $scenarioPlan
}

if (-not $Execute) {
    Write-Output 'Plan only. Re-run with -Execute after all-dimension capability and runners are available.'
    $plan | ConvertTo-Json -Depth 12
    exit 0
}

New-Item -ItemType Directory -Force -Path $artifactsRoot | Out-Null
$lock = $null
try {
    try { $lock = [IO.FileStream]::new($runLockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None) }
    catch { throw "Another validation matrix owns $runLockPath. This runner never shares a Gradle/runtime stream." }
    New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
    $lockBytes = [Text.Encoding]::UTF8.GetBytes("pid=$PID`nstarted=$((Get-Date).ToString('o'))`nrun_root=$runRoot`n")
    $lock.Write($lockBytes, 0, $lockBytes.Length); $lock.Flush()
    Write-JsonFile -Path (Join-Path $runRoot 'matrix-plan.json') -Value $plan
    Write-Utf8File -Path (Join-Path $runRoot 'sources-before.sha256') -Text ((Get-SourceManifest) -join [Environment]::NewLine)
    Write-JsonFile -Path (Join-Path $runRoot 'environment.json') -Value ([ordered]@{ workspace = $workspace; jdk = $jdkPath; runtime_progress = 'file-only'; model_polling = 'not required'; capability_manifest = $CapabilityManifest })

    $results = @()
    foreach ($buildCase in $plan.build_cases) {
        if ($targeted -and $buildCase.id -eq 'unit') { $results += New-SkippedCase -Id 'unit' -Reason 'Not selected: targeted scenario run.' -PlanCase $null; continue }
        $gradleArguments = @('/d', '/c', (Join-Path $workspace 'gradlew.bat'))
        $gradleArguments += @($buildCase.gradle)
        $results += Invoke-OwnedProcess -CaseId $buildCase.id -FileName "$env:SystemRoot\System32\cmd.exe" -Arguments $gradleArguments -WorkingDirectory $workspace -TimeoutSeconds $BuildTimeoutSeconds -CaptureJunit ([bool]$buildCase.junit)
    }
    $artifact = Get-ArtifactInfo
    if ($null -ne $artifact) { Write-JsonFile -Path (Join-Path $runRoot 'artifact.json') -Value $artifact }
    $capabilities = Read-Capabilities
    $runtimeDependenciesReady = @($results | Where-Object { $_.id -in @('compile', 'build') -and $_.status -ne 'PASSED' }).Count -eq 0
    $runtimeDependencyReason = 'Compile or distribution build failed; runtime was skipped to avoid using a stale JAR.'
    $runtimeSafe = $true
    if($targeted){
        $results+=New-SkippedCase -Id 'settings-menu-smoke' -Reason 'Not selected: targeted scenario run.' -PlanCase $null
    }elseif(-not $runtimeDependenciesReady){
        $results+=New-SkippedCase -Id 'settings-menu-smoke' -Reason $runtimeDependencyReason -PlanCase $null
    }elseif([string]::IsNullOrWhiteSpace($localPwsh) -or -not(Test-Path -LiteralPath $settingsSmokeRunner -PathType Leaf)){
        $results+=New-SkippedCase -Id 'settings-menu-smoke' -Reason 'PowerShell 7 or the settings smoke runner is unavailable.' -PlanCase $null
    }else{
        $settingsDirectory=Join-Path $runRoot 'cases\settings-menu-smoke'
        $settingsResult=Invoke-OwnedProcess -CaseId 'settings-menu-smoke' -FileName $localPwsh -Arguments @('-NoProfile','-ExecutionPolicy','Bypass','-File',$settingsSmokeRunner,'-OutputRoot',$settingsDirectory) -WorkingDirectory $workspace -TimeoutSeconds $ScenarioTimeoutSeconds -CaptureJunit $false
        $settingsContract=Read-SettingsSmokeContract -CaseDirectory $settingsDirectory
        if($settingsResult.status -eq 'PASSED' -and -not $settingsContract.valid){$settingsResult.status='FAILED';$settingsResult.failure=$settingsContract.reason}
        if($settingsResult.timed_out -or -not $settingsResult.cleanup_safe -or -not $settingsContract.cleanupSafe){$runtimeSafe=$false}
        $results+=$settingsResult
    }
    foreach ($scenario in $plan.runtime_and_performance_cases) {
        if ($SkipPerformance -and $scenario.purpose -eq 'performance') { $results += New-SkippedCase -Id $scenario.id -Reason 'Performance was disabled by -SkipPerformance.' -PlanCase $scenario; continue }
        if (-not $runtimeDependenciesReady) { $results += New-SkippedCase -Id $scenario.id -Reason $runtimeDependencyReason -PlanCase $scenario; continue }
        if (-not $runtimeSafe) { $results += New-SkippedCase -Id $scenario.id -Reason 'A prior runtime case left cleanup/port safety unproven.' -PlanCase $scenario; continue }
        $capabilityReason = Test-ScenarioCapability -Capabilities $capabilities -Artifact $artifact
        if ($null -ne $capabilityReason) { $results += New-SkippedCase -Id $scenario.id -Reason $capabilityReason -PlanCase $scenario; continue }
        $caseDirectory = Join-Path $runRoot ("cases\$($scenario.id)")
        $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scenarioRunner, '-Dimension', $scenario.dimension, '-Mode', $scenario.mode, '-Players', [string]$scenario.players, '-Purpose', $scenario.purpose, '-CacheEntries', [string]$scenario.cache_entries, '-Prediction', [string]$scenario.prediction.ToString().ToLowerInvariant(), '-ValidationCells', [string]$scenario.validation_cells, '-Seed', [string]$capabilities.raw.runtime.public_test_seed, '-OutputRoot', $caseDirectory)
        $processResult = Invoke-OwnedProcess -CaseId $scenario.id -FileName $localPwsh -Arguments $arguments -WorkingDirectory $workspace -TimeoutSeconds $ScenarioTimeoutSeconds -CaptureJunit $false
        $contract = Read-ScenarioContract -CaseDirectory $caseDirectory -ExpectedSha256 $artifact.sha256
        $processResult.plan = $scenario
        if ($processResult.status -eq 'PASSED' -and -not $contract.valid) { $processResult.status = 'FAILED'; $processResult.failure = $contract.reason }
        if ($processResult.timed_out -or -not $processResult.cleanup_safe -or -not $contract.cleanupSafe) { $runtimeSafe = $false }
        $results += $processResult
    }

    if (-not $SkipPublicFixtureRegression -and -not $targeted) {
        if (-not $runtimeDependenciesReady) {
            $results += New-SkippedCase -Id 'public-fixture-regression' -Reason $runtimeDependencyReason -PlanCase $null
        } elseif (-not $runtimeSafe) {
            $results += New-SkippedCase -Id 'public-fixture-regression' -Reason 'A prior runtime case left cleanup/port safety unproven.' -PlanCase $null
        } elseif ([string]::IsNullOrWhiteSpace($localPwsh)) {
            $results += New-SkippedCase -Id 'public-fixture-regression' -Reason 'PowerShell 7 (pwsh.exe) is required for local fixture execution.' -PlanCase $null
        } elseif (Test-Path -LiteralPath $publicFixtureRunner -PathType Leaf) {
            foreach ($mode in @('vanilla', 'assisted', 'timeout', 'malformed', 'pending-reload', 'pending-disconnect')) {
                if (-not $runtimeSafe) {
                    $results += New-SkippedCase -Id "public-fixture-$mode" -Reason 'A prior runtime case left cleanup/port safety unproven.' -PlanCase $null
                    continue
                }
                $fixtureResult = Invoke-OwnedProcess -CaseId "public-fixture-$mode" -FileName $localPwsh -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $publicFixtureRunner, '-Mode', $mode) -WorkingDirectory $workspace -TimeoutSeconds $ScenarioTimeoutSeconds -CaptureJunit $false
                if ($fixtureResult.timed_out -or -not $fixtureResult.cleanup_safe) { $runtimeSafe = $false }
                $results += $fixtureResult
            }
        } else { $results += New-SkippedCase -Id 'public-fixture-regression' -Reason "Runner is absent: $publicFixtureRunner" -PlanCase $null }
    } else {
        $results += New-SkippedCase -Id 'public-fixture-regression' -Reason 'Public fixture regression was not selected for this run.' -PlanCase $null
    }

    $aggregationReason = Test-AggregationCapability -Capabilities $capabilities
    if ($null -ne $aggregationReason) {
        $results += New-SkippedCase -Id 'aggregate-runtime-performance' -Reason $aggregationReason -PlanCase $null
    } else {
        $aggregationResult = Invoke-OwnedProcess -CaseId 'aggregate-runtime-performance' -FileName $localPwsh -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $comparisonRunner, '-RunRoot', $runRoot) -WorkingDirectory $workspace -TimeoutSeconds $ScenarioTimeoutSeconds -CaptureJunit $false
        $analysisContract = Read-AnalysisContract
        if ($aggregationResult.status -eq 'PASSED' -and -not $analysisContract.valid) { $aggregationResult.status = 'FAILED'; $aggregationResult.failure = $analysisContract.reason }
        $results += $aggregationResult
    }

    $sourcesAfter = Get-SourceManifest
    Write-Utf8File -Path (Join-Path $runRoot 'sources-after.sha256') -Text ($sourcesAfter -join [Environment]::NewLine)
    $sourceChanged = Compare-Object (Get-Content -LiteralPath (Join-Path $runRoot 'sources-before.sha256')) $sourcesAfter
    if ($sourceChanged) { $results += [ordered]@{ id = 'source-snapshot'; status = 'FAILED'; failure = 'Source/build files changed during verification; this evidence cannot attest one snapshot.'; stdout = $null; stderr = $null; junit = $null } }
    $totals = [ordered]@{ passed = @($results | Where-Object status -eq 'PASSED').Count; failed = @($results | Where-Object status -in @('FAILED', 'TIMED_OUT')).Count; skipped = @($results | Where-Object status -eq 'SKIPPED').Count; total = $results.Count }
    $allFailures = @($results | Where-Object { $_.status -in @('FAILED', 'TIMED_OUT') } | ForEach-Object { [ordered]@{ id = $_.id; status = $_.status; failure = $_.failure; stderr = $_.stderr; stdout = $_.stdout; junit_failures = if ($null -ne $_.junit) { $_.junit.failures } else { @() } } })
    $summary = [ordered]@{ schema = 'worldgen-assist.validation-matrix-summary.v1'; run_root = $runRoot; completed_at = (Get-Date).ToString('o'); runtime_safe = $runtimeSafe; capabilities = $capabilities; artifact = $artifact; totals = $totals; cases = $results; failures = $allFailures }
    Write-JsonFile -Path (Join-Path $runRoot 'summary.json') -Value $summary
    $markdown = @('# Validation matrix result', '', "- Evidence: $runRoot", "- Runtime cleanup safety: $runtimeSafe", "- Passed: $($totals.passed); failed: $($totals.failed); skipped: $($totals.skipped)", '', '## Cases', '', '| Case | Status | Reason / evidence |', '|---|---|---|')
    foreach ($result in $results) {
        $detail = if ($result.failure) { $result.failure } elseif ($result.reason) { $result.reason } elseif ($result.stderr) { $result.stderr } else { '' }
        $markdown += "| $($result.id) | $($result.status) | $($detail -replace '\|', '\\|') |"
    }
    $markdown += @('', '## Failures', '')
    if ($allFailures.Count -eq 0) { $markdown += 'None.' } else { foreach ($failure in $allFailures) { $markdown += "- $($failure.id): $($failure.failure) (log: $($failure.stderr))" } }
    Write-Utf8File -Path (Join-Path $runRoot 'summary.md') -Text ($markdown -join [Environment]::NewLine)
    Write-Output "VALIDATION_MATRIX_COMPLETE summary=$runRoot\summary.json passed=$($totals.passed) failed=$($totals.failed) skipped=$($totals.skipped)"
    if ($totals.failed -ne 0) { exit 1 }
} finally {
    if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $runLockPath -Force -ErrorAction SilentlyContinue }
}
