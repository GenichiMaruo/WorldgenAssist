[CmdletBinding()]
param([Parameter(Mandatory)][string]$GateRoot)
# ONE offline sequence on already stopped source/JAR-identical runs; no game/test/build/SSH.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gate=[IO.Path]::GetFullPath($GateRoot)
if(-not $gate.StartsWith((Join-Path $workspace 'test-artifacts')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Expected workspace evidence child'}
$proof=Get-Content -LiteralPath (Join-Path $gate 'summary.json') -Raw|ConvertFrom-Json
if(-not $proof.success){throw 'Completed gate required'}
$java='C:\Program Files\Java\jdk-25.0.4\bin\java.exe'
foreach($mode in @('off','ready')){
    $case=Join-Path $gate $mode;$result=Get-Content -LiteralPath (Join-Path $case 'scenario-result.json') -Raw|ConvertFrom-Json
    if(-not $result.success -or -not $result.cleanup_safe -or $result.source_manifest_after_sha256 -ne $proof.source_sha256){throw 'Stopped scenario identity differs'}
    $output=Join-Path $case 'analysis-biomes'
    if(Test-Path -LiteralPath $output){throw 'Analysis evidence already exists'}
    New-Item -ItemType Directory -Path $output|Out-Null
    $log=Join-Path $case 'remote-evidence/latest.log'
    & (Join-Path $PSScriptRoot 'Measure-CompleteTerrainCoverage.ps1') -LogPath $log -OutputPath (Join-Path $output 'complete-coverage.json') -ArtifactSha256 $result.artifact_sha256|Out-Null
    & (Join-Path $PSScriptRoot 'Measure-EarlyBiomeCoverage.ps1') -LogPath $log -OutputPath (Join-Path $output 'biome-coverage.json') -ArtifactSha256 $result.artifact_sha256
    $windows=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/flight-recording-windows.json') -Raw|ConvertFrom-Json -DateKind String
    $window=@($windows|Where-Object {$_.phase -eq 'measured' -and $_.repeat -eq 2})
    if($window.Count -ne 1){throw 'Exact repeat2 recording window missing'}
    & $java (Join-Path $PSScriptRoot 'AnalyzeWorldgenJfr.java') (Join-Path $case 'remote-evidence/server.jfr') $window[0].start_utc $window[0].end_utc (Join-Path $output 'repeat2-jfr.json') $result.artifact_sha256
    if($LASTEXITCODE){throw 'Offline JFR analysis failed'}
    Copy-Item -LiteralPath $PSCommandPath,(Join-Path $PSScriptRoot 'AnalyzeWorldgenJfr.java'),(Join-Path $PSScriptRoot 'Measure-EarlyBiomeCoverage.ps1') -Destination $output
    Write-Output "EARLY_BIOME_ANALYSIS_COMPLETE mode=$mode output=$output"
}
