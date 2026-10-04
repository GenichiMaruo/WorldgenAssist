[CmdletBinding()]
param([switch]$Execute,[switch]$LocalOnly,[switch]$BoundedWait,[ValidateRange(2,32)][int]$ViewDistance=32,
    [ValidateSet('vanilla-first','assisted-first')][string]$ConditionOrder='vanilla-first')
$ErrorActionPreference='Stop'
$application=if($BoundedWait){'overlap'}else{'ready'}
$profile=if($BoundedWait){'complete-timing'}else{'complete'}
& (Join-Path $PSScriptRoot 'Run-BlockDensityGate.ps1') -Execute:$Execute -LocalOnly:$LocalOnly -ViewDistance $ViewDistance -RemoteWorkKind complete -RemoteApplicationProfile $application -PrefetchLookahead 0 -WindowProfile deep -ConditionOrder $ConditionOrder -TestProfile $profile
exit $LASTEXITCODE
