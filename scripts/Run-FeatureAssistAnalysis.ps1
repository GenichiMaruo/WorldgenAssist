[CmdletBinding()]
param([Parameter(Mandatory)][string]$ComparisonRoot)
# One offline sequence on closed current-artifact logs/JFR; no games/tests/MOD builds/SSH.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root=(Resolve-Path -LiteralPath $ComparisonRoot).Path
if(-not $root.StartsWith((Join-Path $workspace 'test-artifacts')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Owned completed comparison required'}
$parent=Get-Content -LiteralPath (Join-Path $root 'summary.json') -Raw|ConvertFrom-Json
if(-not $parent.success -or $parent.schema -ne 'worldgen-assist.feature-assist-comparison.v1'){throw 'Completed scoped feature comparison required'}
$java='C:\Program Files\Java\jdk-25.0.4\bin\java.exe'
foreach($mode in @('off','parallel')){
    $case=Join-Path $root $mode
    $result=Get-Content -LiteralPath (Join-Path $case 'scenario-result.json') -Raw|ConvertFrom-Json
    if(-not $result.success -or -not $result.cleanup_safe -or $result.source_manifest_after_sha256 -ne $parent.source_sha256 -or $result.artifact_sha256 -ne $parent.artifacts[0].sha256){throw 'Stopped runtime identity differs'}
    $output=Join-Path $case 'analysis-features'
    if(Test-Path -LiteralPath $output){throw 'Offline output already exists'}
    New-Item -ItemType Directory -Path $output|Out-Null
    & (Join-Path $PSScriptRoot 'Measure-CompleteTerrainCoverage.ps1') -LogPath (Join-Path $case 'remote-evidence/latest.log') -OutputPath (Join-Path $output 'complete-coverage.json') -ArtifactSha256 $result.artifact_sha256|Out-Null
    $windows=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/flight-recording-windows.json') -Raw|ConvertFrom-Json -DateKind String
    $window=@($windows|Where-Object {$_.phase -eq 'measured' -and $_.repeat -eq 2})
    if($window.Count -ne 1){throw 'Exact repeat2 JFR window missing'}
    & $java (Join-Path $PSScriptRoot 'AnalyzeWorldgenJfr.java') (Join-Path $case 'remote-evidence/server.jfr') $window[0].start_utc $window[0].end_utc (Join-Path $output 'repeat2-jfr.json') $result.artifact_sha256
    if($LASTEXITCODE){throw 'Offline JFR analysis failed'}
    Copy-Item -LiteralPath $PSCommandPath,(Join-Path $PSScriptRoot 'AnalyzeWorldgenJfr.java'),(Join-Path $PSScriptRoot 'Measure-CompleteTerrainCoverage.ps1') -Destination $output
    Write-Output "FEATURE_ASSIST_ANALYSIS_COMPLETE mode=$mode output=$output"
}
