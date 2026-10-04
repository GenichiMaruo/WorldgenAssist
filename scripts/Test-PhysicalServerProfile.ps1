[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
foreach($name in @('Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1','Run-ConstrainedServerBenchmark.ps1','Run-RemoteOverlapGate.ps1')){
    $tokens=$null;$errors=$null
    [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors)
    if($errors.Count){throw "$name syntax: $(($errors.Message)-join '; ')"}
}
$runner=Join-Path $PSScriptRoot 'Run-ConstrainedServerBenchmark.ps1'
$plan=& $runner -PhysicalServer -ViewDistance 32 -MeasureFullView|ConvertFrom-Json
if($plan.remote_host -ne 'gen1c@100.103.102.109' -or $plan.remote_root -ne 'E:/WorldgenAssist/port26.3' -or $plan.profiles.Count -ne 1 -or $plan.profiles[0].logical_processors -ne 0 -or $plan.server_jvm_processors -ne 0 -or $plan.view_distance -ne 32){throw 'Natural physical server plan is not isolated on E or changes the CPU tier'}
$deep=& $runner -PhysicalServer -ViewDistance 32 -MeasureFullView -WindowProfile deep -ConditionOrder assisted-first|ConvertFrom-Json
if($deep.owner_window -ne 32 -or $deep.total_window -ne 64 -or $deep.window_profile -ne 'deep' -or
    ($deep.profiles[0].modes -join ',') -ne 'assisted,vanilla' -or $deep.profiles[0].logical_processors -ne 0){throw 'Deeper window/reversed physical order is incorrect or changes CPU bounds'}
$legacy=& $runner -TwoLogicalOnly|ConvertFrom-Json
if($legacy.remote_host -ne 'gen1c@100.117.255.71' -or $legacy.profiles[0].logical_processors -ne 2){throw 'Legacy plan changed'}
$rejected=$false
try{& $runner -PhysicalServer -TwoLogicalOnly|Out-Null}catch{$rejected=$true}
if(-not $rejected){throw 'Conflicting CPU profiles must be rejected'}
Write-Output 'PHYSICAL_SERVER_PROFILE_PASS natural E profile, bounded deep window/reversed order, legacy profile, conflicting limits and changed-script syntax'
