[CmdletBinding()]
param([switch]$Execute)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $Execute){Write-Output 'One owned console producer: live reads before exit, then retention across a simulated midnight log rollover and EOF; no Minecraft/build/JUnit.';exit 0}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root=Join-Path $workspace ('test-artifacts/console-capture-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root | Out-Null
. (Join-Path $PSScriptRoot 'WorldgenScenarioConsole.ps1')
$process=$null;$capture=$null;$checks=@();$failure=$null;$started=$false
try {
    New-Item -ItemType Directory -Path (Join-Path $root 'rolling') | Out-Null
    $rolling=Join-Path $root 'rolling/latest.log'
    $quoted=$rolling.Replace("'","''")
    $code=@"
[Console]::OutputEncoding=[Text.UTF8Encoding]::new(`$false)
`$before='[23:59:59] stage.complete stage=noise chunk=1,1'
[IO.File]::WriteAllText('$quoted',`$before)
[Console]::WriteLine(`$before)
if([Console]::ReadLine() -ne 'continue'){exit 2}
`$after='[00:00:00] chunk.full_ready chunk=1,1'
[IO.File]::WriteAllText('$quoted',`$after)
[Console]::WriteLine(`$after)
"@
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=(Get-Command powershell.exe).Source;$info.Arguments='-NoProfile -NonInteractive -EncodedCommand '+[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code));$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardInput=$true;$info.RedirectStandardError=$true
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    if(-not $process.Start()){throw 'Could not start owned console test process'}
    $started=$true
    $stderr=$process.StandardError.ReadToEndAsync()
    $capture=Start-WorldgenConsoleCapture $process (Join-Path $root 'console.log')
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    do {$text=Read-WorldgenConsoleText $capture;if($text.Contains('stage.complete stage=noise')){break};Start-Sleep -Milliseconds 20}while([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited)
    if($process.HasExited -or -not $text.Contains('stage.complete stage=noise') -or $text.Contains('chunk.full_ready')){throw 'Console was not visible while the producer awaited continuation'}
    $checks += [ordered]@{name='live-before-exit';status='PASSED'}
    $process.StandardInput.WriteLine('continue');$process.StandardInput.Flush()
    if(-not $process.WaitForExit(10000) -or $process.ExitCode -ne 0){throw 'Console producer did not exit successfully'}
    Complete-WorldgenConsoleCapture $capture
    $text=Read-WorldgenConsoleText $capture
    $rolled=[IO.File]::ReadAllText($rolling)
    if([regex]::Matches($text,'stage.complete stage=noise').Count -ne 1 -or [regex]::Matches($text,'chunk.full_ready').Count -ne 1 -or $rolled.Contains('stage.complete stage=noise') -or -not $rolled.Contains('chunk.full_ready')){throw 'Rollover lost or duplicated console evidence'}
    $canonical=Join-Path $root 'latest.log'
    Save-WorldgenConsoleEvidence $capture $rolling $canonical
    Save-WorldgenConsoleEvidence $capture $rolling $canonical
    if([IO.File]::ReadAllText($canonical) -cne $text -or [IO.File]::ReadAllText((Join-Path $root 'minecraft-latest.log')) -cne $rolled){throw 'Canonical evidence or original rolling-log backup changed'}
    $checks += [ordered]@{name='rollover-and-eof';status='PASSED'}
    [IO.File]::WriteAllText((Join-Path $root 'stderr.log'),$stderr.GetAwaiter().GetResult())
}catch{$failure=$_.Exception.ToString()}
finally {
    if($null -ne $process){
        if($started -and -not $process.HasExited){$process.Kill();[void]$process.WaitForExit(10000)}
        if($started -and $null -ne $capture){Complete-WorldgenConsoleCapture $capture}
        $process.Dispose()
    }
    $summary=[ordered]@{schema='worldgen-assist.console-capture-test.v1';success=($null -eq $failure -and $checks.Count -eq 2);checks=$checks;failure=$failure;engine=$PSVersionTable.PSVersion.ToString();helper_sha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'WorldgenScenarioConsole.ps1')).Hash;root=$root}
    [IO.File]::WriteAllText((Join-Path $root 'summary.json'),($summary|ConvertTo-Json -Depth 5))
    Write-Output "CONSOLE_CAPTURE_TEST success=$($summary.success) summary=$(Join-Path $root 'summary.json')"
    if(-not $summary.success){exit 1}
}
