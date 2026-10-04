[CmdletBinding()]
param([switch]$Execute,[switch]$LocalOnly,[ValidateRange(2,32)][int]$ViewDistance=32,
    [ValidateSet('ready','overlap')][string]$RemoteApplicationProfile='overlap',
    [ValidateRange(0,64)][int]$PrefetchLookahead=0,
    [ValidateSet('wide','deep')][string]$WindowProfile='wide',
    [ValidateSet('vanilla-first','assisted-first')][string]$ConditionOrder='vanilla-first',
    [ValidateSet('transport','scheduling','prefetch','admission','capacity')][string]$TestProfile='transport',[string]$ReuseBuildEvidence,[string]$ReuseCorrectnessEvidence)
# Reuse the single sequential gate runner; no duplicate tests/build/runtime commands.
& (Join-Path $PSScriptRoot 'Run-BlockDensityGate.ps1') -Execute:$Execute -LocalOnly:$LocalOnly -ViewDistance $ViewDistance -RemoteWorkKind decisions -RemoteApplicationProfile $RemoteApplicationProfile -PrefetchLookahead $PrefetchLookahead -WindowProfile $WindowProfile -ConditionOrder $ConditionOrder -TestProfile $TestProfile -ReuseBuildEvidence $ReuseBuildEvidence -ReuseCorrectnessEvidence $ReuseCorrectnessEvidence
