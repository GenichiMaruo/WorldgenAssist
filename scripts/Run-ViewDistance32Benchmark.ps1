[CmdletBinding()]
param([switch]$Execute)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
# Complete the affected harness checks and the one requested runtime pair in
# one batch. No Minecraft source modification, Gradle build, or JUnit rerun.
foreach($name in @('WorldgenMeasurementRegion.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1','Compare-WorldgenScenarioMatrix.ps1','Run-ConstrainedServerBenchmark.ps1','Run-ViewDistance32Benchmark.ps1')){
    $parseErrors=$null;$tokens=$null
    [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count){throw ($parseErrors|Out-String)}
}
. (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1')
$small=@(Get-WorldgenMeasurementOffsets 4 'square')
$full=@(Get-WorldgenMeasurementOffsets 32 'view')
if($small.Count -ne 81 -or $full.Count -lt 3000 -or @($full|Where-Object {$_.x -eq 32 -and $_.z -eq 0}).Count -ne 1 -or @($full|Where-Object {$_.x -eq 32 -and $_.z -eq 32}).Count -ne 0){throw 'Measurement geometry sanity check failed'}
Write-Output "VIEW32_HARNESS_CHECK_COMPLETE expected_chunks_per_owner=$($full.Count)"
$batchArguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-ConstrainedServerBenchmark.ps1'),'-TwoLogicalOnly','-ViewDistance','32','-MeasureFullView')
if($Execute){$batchArguments += '-Execute'}
& (Get-Command pwsh.exe -ErrorAction Stop).Source @batchArguments
exit $LASTEXITCODE
