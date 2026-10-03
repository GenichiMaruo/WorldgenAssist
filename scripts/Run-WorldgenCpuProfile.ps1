[CmdletBinding()]
param([switch]$Execute,[ValidateSet('vanilla','cooperative')][string]$NoiseBackend='vanilla',[switch]$CheckConsoleCapture)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(-not $Execute){Write-Output "One sequential unassisted/assisted CPU diagnostic pair, both backend=$NoiseBackend; exact existing JAR, two logical/JVM CPUs, view10, one warmup/one measured repeat. Optional two console-capture checks first. No build/JUnit/correctness rerun.";exit 0}
. (Join-Path $PSScriptRoot 'WorldgenScenarioConsole.ps1')
$root=Join-Path $workspace ('test-artifacts/cpu-profile-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$lock=$null;$results=@();$issues=@();$consoleGate=$null;$sourceManifest=$null
$artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1')
$hash=(Get-FileHash -LiteralPath $artifact.Path).Hash
$pwsh=(Get-Command pwsh.exe).Source
$jfr='C:\Program Files\Java\jdk-25.0.4\bin\jfr.exe'
function Process([string]$exe,[string[]]$arguments,[string]$out,[string]$err,[int]$seconds){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$exe;$info.WorkingDirectory=$workspace
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    if([IO.Path]::GetFileName($exe) -ieq 'powershell.exe'){
        # A Windows PowerShell child must not inherit PowerShell 7 module paths.
        $info.Environment['PSModulePath']=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/Modules'
    }
    foreach($argument in $arguments){[void]$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info;$capture=$null;$started=$false
    try{
        if(-not $process.Start()){throw 'Profile process failed to start'}
        $started=$true
        $capture=Start-WorldgenConsoleCapture $process $out
        $stderr=$process.StandardError.ReadToEndAsync()
        $finished=$process.WaitForExit($seconds*1000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        Complete-WorldgenConsoleCapture $capture
        [IO.File]::WriteAllText($err,$stderr.GetAwaiter().GetResult())
        if(-not $finished -or $process.ExitCode -ne 0){throw "Profile process failed: $exe; see $err"}
    }finally{
        if($started -and -not $process.HasExited){$process.Kill($true);$process.WaitForExit()}
        if($null -ne $capture -and -not $capture.Closed){
            Complete-WorldgenConsoleCapture $capture
        }
        $process.Dispose()
    }
}
try{
    $lock=[IO.File]::Open((Join-Path $workspace 'test-artifacts/validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    foreach($name in @('Run-WorldgenCpuProfile.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1','WorldgenScenarioConsole.ps1','Test-WorldgenConsoleCapture.ps1')){
        $tokens=$null;$errors=$null;[void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors)
        if($errors.Count){throw "$name syntax: $(($errors.Message)-join '; ')"}
    }
    if($CheckConsoleCapture){
        Process (Get-Command powershell.exe).Source @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Test-WorldgenConsoleCapture.ps1'),'-Execute') (Join-Path $root 'console-gate-out.log') (Join-Path $root 'console-gate-error.log') 60
        $output=Get-Content -LiteralPath (Join-Path $root 'console-gate-out.log') -Raw
        $match=[regex]::Match($output,'CONSOLE_CAPTURE_TEST success=True summary=(?<path>[^\r\n]+)')
        if(-not $match.Success){throw 'Console capture checks did not prove success'}
        $consoleGate=$match.Groups['path'].Value.Trim()
    }
    foreach($mode in @('vanilla','assisted')){
        $directory=Join-Path $root $mode;New-Item -ItemType Directory -Path $directory|Out-Null
        Process $pwsh @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-Dimension','overworld','-Mode',$mode,'-Players','2','-Purpose','performance','-CacheEntries','128','-Prediction','true','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','2','-ServerJvmProcessors','2','-ViewDistance','10','-MeasureFullView','-ServerFlightRecording','-QuietRemoteTrace','-MeasuredRepeats','1','-NoiseBackend',$NoiseBackend,'-OutputRoot',$directory) (Join-Path $directory 'batch-out.log') (Join-Path $directory 'batch-err.log') 1200
        $scenario=Get-Content -LiteralPath (Join-Path $directory 'scenario-result.json') -Raw|ConvertFrom-Json
        if(-not $scenario.success -or -not $scenario.cleanup_safe -or $scenario.artifact_sha256 -ne $hash -or $scenario.source_manifest_before_sha256 -ne $scenario.source_manifest_after_sha256){throw 'Profile runtime, cleanup, source or artifact identity mismatch'}
        if($null -eq $sourceManifest){$sourceManifest=$scenario.source_manifest_after_sha256}
        elseif($sourceManifest -ne $scenario.source_manifest_after_sha256){throw 'Profile source differs between modes'}
        $backend=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/noise-backend-config.json') -Raw|ConvertFrom-Json
        if($backend.noise_backend -ne $NoiseBackend){throw 'Profile backend differs from request'}
        $recording=Join-Path $directory 'remote-evidence/server.jfr'
        if(-not(Test-Path -LiteralPath $recording) -or (Get-Item -LiteralPath $recording).Length -lt 1024){throw 'JFR recording absent'}
        $config=Get-Content -LiteralPath (Join-Path $directory 'remote-evidence/flight-recording-config.json') -Raw|ConvertFrom-Json
        if(-not $config.enabled -or $config.measured_repeats -ne 1){throw 'Profile configuration mismatch'}
        # Stream deep JSON directly to disk; exports can exceed V8's string limit.
        Process $jfr @('print','--json','--stack-depth','128','--events','jdk.ExecutionSample',$recording) (Join-Path $directory 'execution-samples-deep.json') (Join-Path $directory 'jfr-print-error.log') 300
        $results += [ordered]@{mode=$mode;status='PASSED';noise_backend=$NoiseBackend;directory=$directory}
        Write-Output "CPU_PROFILE_STEP mode=$mode status=PASSED backend=$NoiseBackend"
    }
    [IO.File]::WriteAllText((Join-Path $root 'runtime-summary.json'),([ordered]@{status='COMPLETE';artifact_sha256=$hash;results=$results;noise_backend=$NoiseBackend}|ConvertTo-Json -Depth 6))
    Process (Get-Command node.exe).Source @((Join-Path $PSScriptRoot 'analyze-worldgen-cpu-profile.mjs'),$root) (Join-Path $root 'computational-analysis-out.log') (Join-Path $root 'computational-analysis-error.log') 300
    $analysis=Get-Content -LiteralPath (Join-Path $root 'computational-analysis.json') -Raw|ConvertFrom-Json
    if($analysis.outputs.Count -ne 2){throw 'Computational analysis did not cover both modes'}
}catch{$issues+= $_.Exception.ToString()}
finally{
    if($null -ne $lock){$lock.Dispose()}
    $summary=[ordered]@{schema='worldgen-assist.cpu-profile.v2';status=if($issues.Count){'INCOMPLETE'}else{'COMPLETE'};artifact_sha256=$hash;noise_backend=$NoiseBackend;console_capture_gate=$consoleGate;results=$results;issues=$issues;root=$root;definition='Measured-window ExecutionSample only, native waits excluded; overlapping sampling percentages are not CPU duration or an end-to-end speed gate'}
    $path=Join-Path $root 'summary.json';[IO.File]::WriteAllText($path,($summary|ConvertTo-Json -Depth 6))
    Write-Output "CPU_PROFILE_COMPLETE status=$($summary.status) summary=$path"
    if($issues.Count){exit 1}
}
