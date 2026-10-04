[CmdletBinding()]
param([switch]$Execute,[switch]$LocalOnly,[switch]$BoundedWait,[switch]$BiomeCache,[switch]$LargerBiomeCache,[switch]$PreparedBypass,[switch]$PeerVerification,[switch]$StructuralShaping,[ValidateRange(2,32)][int]$ViewDistance=32,
    [ValidateSet('vanilla-first','assisted-first')][string]$ConditionOrder='vanilla-first')
$ErrorActionPreference='Stop'
$application=if($BoundedWait -or $BiomeCache -or $LargerBiomeCache -or $PreparedBypass -or $PeerVerification -or $StructuralShaping){'overlap'}else{'ready'}
$profile=if($StructuralShaping){'complete-shaping'}elseif($PeerVerification){'complete-peer'}elseif($PreparedBypass){'complete-preparation'}elseif($LargerBiomeCache){'complete-capacity'}elseif($BiomeCache){'complete-biomes'}elseif($BoundedWait){'complete-timing'}else{'complete'}
& (Join-Path $PSScriptRoot 'Run-BlockDensityGate.ps1') -Execute:$Execute -LocalOnly:$LocalOnly -ViewDistance $ViewDistance -RemoteWorkKind complete -RemoteApplicationProfile $application -PrefetchLookahead 0 -WindowProfile deep -ConditionOrder $ConditionOrder -TestProfile $profile
exit $LASTEXITCODE
