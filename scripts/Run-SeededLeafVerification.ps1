param(
    [string[]]$FocusedTests = @('*SeededLeaf*', '*RemoteDensityFieldTest', '*ClientTerrainDensityComputer*'),
    [switch]$FocusedOnly
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$jdkPath = 'C:\Program Files\Java\jdk-25.0.4'
if (-not (Test-Path -LiteralPath (Join-Path $jdkPath 'bin/java.exe'))) {
    throw 'Required JDK 25.0.4 installation is missing; no alternate JDK selected.'
}
$runRoot = Join-Path $workspace ('test-artifacts/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $runRoot | Out-Null
$priorJavaHome = $env:JAVA_HOME
$priorPath = $env:Path
$env:JAVA_HOME = $jdkPath
$env:Path = "$jdkPath\bin;$priorPath"

function Write-SourceManifest([string]$Name) {
    $sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $workspace 'src') -File -Recurse)
    foreach ($config in @('build.gradle', 'settings.gradle', 'gradle.properties')) {
        $sourceFiles += Get-Item -LiteralPath (Join-Path $workspace $config)
    }
    $sourceFiles | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($workspace.Length + 1).Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        "$hash  $relative"
    } | Set-Content -LiteralPath (Join-Path $runRoot $Name)
}

function Save-Junit([string]$Phase, [datetime]$EarliestWrite) {
    $xmlRoot = Join-Path $workspace 'build/test-results/test'
    $files = @(Get-ChildItem -LiteralPath $xmlRoot -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { throw "No JUnit XML produced for $Phase" }
    if (@($files | Where-Object { $_.LastWriteTime -lt $EarliestWrite }).Count -gt 0) {
        throw "$Phase has stale JUnit XML (test execution may not have started); no totals accepted."
    }
    $destination = Join-Path $runRoot "$Phase-xml"
    New-Item -ItemType Directory -Path $destination | Out-Null
    $totals = [ordered]@{ suites = 0; tests = 0; failures = 0; errors = 0; skipped = 0 }
    foreach ($file in $files) {
        Copy-Item -LiteralPath $file.FullName -Destination $destination
        [xml]$document = Get-Content -LiteralPath $file.FullName
        $totals.suites++
        foreach ($counter in @('tests', 'failures', 'errors', 'skipped')) {
            $totals[$counter] += [int]$document.testsuite.$counter
        }
    }
    $totals | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot "$Phase-junit.json")
    if ($totals.tests -eq 0 -or $totals.failures -ne 0 -or $totals.errors -ne 0 -or $totals.skipped -ne 0) {
        throw "$Phase JUnit totals failed the no-failures/errors/skips gate"
    }
}

function Invoke-Phase([string]$Phase, [string[]]$GradleArguments, [bool]$WithJunit) {
    $started = Get-Date
    $rendered = '.\gradlew.bat ' + (($GradleArguments | ForEach-Object { "'$_'" }) -join ' ')
    "$Phase start=$($started.ToString('o')) command=$rendered" |
        Add-Content -LiteralPath (Join-Path $runRoot 'commands.txt')
    $previousErrorPolicy = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & (Join-Path $workspace 'gradlew.bat') @GradleArguments 2>&1 |
            Tee-Object -FilePath (Join-Path $runRoot "$Phase.log")
        $phaseExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorPolicy
    }
    "$Phase end=$((Get-Date).ToString('o')) seconds=$(((Get-Date) - $started).TotalSeconds) exit=$phaseExit" |
        Add-Content -LiteralPath (Join-Path $runRoot 'commands.txt')
    # Preserve failed test XML too, without treating earlier phases as new evidence.
    if ($WithJunit) { Save-Junit $Phase $started }
    if ($phaseExit -ne 0) { throw "$Phase failed with exit $phaseExit; later phases not run." }
}

Push-Location $workspace
try {
    "evidence=$runRoot"
    @("time=$((Get-Date).ToString('o'))", "workspace=$workspace", "jdk=$jdkPath",
        "os=$([System.Environment]::OSVersion.VersionString)", 'runtime=NOT RUN', 'multi_pc=DEFERRED') |
        Set-Content -LiteralPath (Join-Path $runRoot 'environment.txt')
    $ErrorActionPreference = 'Continue'
    & (Join-Path $jdkPath 'bin/java.exe') -version 2>&1 |
        Tee-Object -FilePath (Join-Path $runRoot 'java-version.txt')
    $ErrorActionPreference = 'Stop'
    Write-SourceManifest 'sources-before.sha256'
    Invoke-Phase 'A-compile' @('compileJava', 'compileClientJava') $false
    $focusedArguments = @('test')
    foreach ($pattern in $FocusedTests) { $focusedArguments += @('--tests', $pattern) }
    $focusedArguments += '--rerun-tasks'
    Invoke-Phase 'B-focused' $focusedArguments $true
    if (-not $FocusedOnly) {
        Invoke-Phase 'C-full' @('test', '--rerun-tasks') $true
        Invoke-Phase 'D-build' @('build', '--rerun-tasks') $true
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Workspace $workspace
        Get-Item -LiteralPath $artifact.Path,$artifact.SourcesPath | ForEach-Object {
            [pscustomobject]@{ file = $_.Name; bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
        } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot 'artifacts-sha256.json')
    }
} finally {
    Write-SourceManifest 'sources-after.sha256'
    $changed = Compare-Object (Get-Content -LiteralPath (Join-Path $runRoot 'sources-before.sha256')) `
        (Get-Content -LiteralPath (Join-Path $runRoot 'sources-after.sha256'))
    $changed | Out-String | Set-Content -LiteralPath (Join-Path $runRoot 'source-differences.txt')
    Pop-Location
    $env:JAVA_HOME = $priorJavaHome
    $env:Path = $priorPath
    "evidence=$runRoot"
    if ($changed) { throw 'Source snapshot changed during verification; final-snapshot evidence is invalid.' }
}
