[CmdletBinding()]
param([switch]$Execute)
# All edits precede this sequential affected-method/build/Fabric-speed/native batch.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $Execute){
    [ordered]@{methods=1;builds=3;physical='Two-owner terrain parity then weak E-server view32 off/on,warm1/3';
        native='Fresh Forge and NeoForge original/assisted pairs,view4 correctness only';
        unchanged_tests=0;condition_order='assisted-first';feature_backend='off'}|ConvertTo-Json
    exit 0
}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root=Join-Path $workspace ('test-artifacts/packed-application-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=[Collections.Generic.List[object]]::new();$parent=$null;$native=$null
function Step([string]$Name,[string]$Script,[string[]]$Arguments,[int]$Seconds){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=(Get-Command pwsh.exe).Source
    $info.WorkingDirectory=$workspace;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in @('-NoProfile','-File',(Join-Path $PSScriptRoot $Script))+$Arguments){[void]$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(-not $process.Start()){throw "Could not start $Name"}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        $finished=$process.WaitForExit($Seconds*1000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        $output=$stdout.GetAwaiter().GetResult()
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$output)
        [IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$stderr.GetAwaiter().GetResult())
        $steps.Add([ordered]@{name=$Name;success=($finished -and $process.ExitCode -eq 0);exit_code=$process.ExitCode})
        Write-Host "PACKED_APPLICATION_STEP name=$Name success=$($steps[-1].success)"
        if(-not $steps[-1].success){throw "Step failed: $Name (retained logs)"}
        return $output
    }finally{$process.Dispose()}
}
try{
    $output=Step 'physical-gate' 'Run-CompleteTerrainGate.ps1' @('-Execute','-PackedApplication','-ViewDistance','32','-ConditionOrder','assisted-first') 10800
    if($output -notmatch '(?m)^BLOCK_DENSITY_GATE_COMPLETE summary=(.+) success=True\r?$'){throw 'Physical gate summary missing'}
    $parent=$Matches[1].Trim()
    $prior=Get-Content -LiteralPath $parent -Raw|ConvertFrom-Json
    if(-not $prior.success -or $prior.test_profile -ne 'complete-packed-application' -or $prior.junit.tests -ne 1 -or
        $prior.junit.failures -or $prior.junit.errors -or $prior.junit.skipped){throw 'Affected method/build/runtime evidence incomplete'}
    $output=Step 'native-gate' 'Run-TerrainNativeGate.ps1' @('-Execute','-BuildEvidence',(Split-Path $parent)) 5400
    if($output -notmatch '(?m)^TERRAIN_NATIVE_GATE_COMPLETE summary=(.+) success=True\r?$'){throw 'Native gate summary missing'}
    $native=$Matches[1].Trim()
    $proof=Get-Content -LiteralPath $native -Raw|ConvertFrom-Json
    if(-not $proof.success -or -not $proof.runtime_fresh -or $proof.issues.Count){throw 'Fresh native proof incomplete'}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    $summary=[ordered]@{schema='worldgen-assist.packed-application-gate.v1';success=($issues.Count -eq 0);
        physical=$parent;native=$native;steps=$steps.ToArray();issues=$issues.ToArray();beta_claim=$false}
    [IO.File]::WriteAllText((Join-Path $root 'summary.json'),($summary|ConvertTo-Json -Depth 6))
    Write-Output "PACKED_APPLICATION_GATE success=$($summary.success) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
