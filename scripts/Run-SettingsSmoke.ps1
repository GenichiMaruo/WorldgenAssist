[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputRoot,[ValidateRange(60,600)][int]$TimeoutSeconds=240)

Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output=[IO.Path]::GetFullPath($OutputRoot)
$evidenceRoot=[IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts')).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
$jdk='C:\Program Files\Java\jdk-25.0.4'
$success=$false;$cleanupSafe=$true;$failure=$null;$process=$null
if(-not $output.StartsWith($evidenceRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'OutputRoot must be beneath test-artifacts'}
New-Item -ItemType Directory -Force -Path $output|Out-Null
$profile=Join-Path $output 'settings-client'
New-Item -ItemType Directory -Force -Path $profile|Out-Null
@('version:5023','onboardAccessibility:false','skipMultiplayerWarning:true','tutorialStep:none','maxFps:30','enableVsync:false','soundCategory_master:0.0')|Set-Content -LiteralPath (Join-Path $profile 'options.txt')
try{
    $start=[Diagnostics.ProcessStartInfo]::new();$start.FileName="$env:SystemRoot\System32\cmd.exe"
    foreach($argument in @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'runSettingsSmokeClient',"-PfixtureRunRoot=$output",'--no-daemon','--console=plain')){[void]$start.ArgumentList.Add($argument)}
    $start.WorkingDirectory=$workspace;$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.Environment['JAVA_HOME']=$jdk;$start.Environment['Path']="$jdk\bin;$($start.Environment['Path'])"
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start
    if(-not $process.Start()){throw 'Could not start settings smoke client'}
    $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
    if(-not $process.WaitForExit($TimeoutSeconds*1000)){
        & "$env:SystemRoot\System32\taskkill.exe" '/PID' $process.Id '/T' '/F'|Out-Null
        if(-not $process.WaitForExit(30000)){$cleanupSafe=$false}
        throw "Settings smoke exceeded $TimeoutSeconds seconds"
    }
    $stdout.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $output 'gradle-stdout.log')
    $stderr.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $output 'gradle-stderr.log')
    if($process.ExitCode -ne 0){throw "Settings smoke Gradle runtime exited $($process.ExitCode)"}
    $source=Join-Path $profile 'settings-smoke-result.json'
    if(-not(Test-Path -LiteralPath $source -PathType Leaf)){throw 'Settings smoke did not write its result'}
    $raw=Get-Content -LiteralPath $source -Raw;$result=$raw|ConvertFrom-Json
    if(-not [bool]$result.success -or [int]$result.screenshots -ne 4 -or -not [bool]$result.persistence -or -not [bool]$result.separate_disclosure_gate){throw 'Settings smoke result did not prove all checks'}
    Copy-Item -LiteralPath $source -Destination (Join-Path $output 'settings-smoke-details.json')
    $success=$true
}catch{$failure=$_.Exception.Message}finally{
    if($null -ne $process){
        if(-not $process.HasExited){try{& "$env:SystemRoot\System32\taskkill.exe" '/PID' $process.Id '/T' '/F'|Out-Null;$cleanupSafe=$process.WaitForExit(30000)}catch{$cleanupSafe=$false}}
        $process.Dispose()
    }
    [ordered]@{schema='worldgen-assist.settings-smoke-result.v1';success=$success;cleanup_safe=$cleanupSafe;failure=$failure;profile=$profile;screenshots=@('settings-client.png','settings-server-1.png','settings-server-2.png','settings-server-3.png')}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $output 'settings-smoke-result.json') -Encoding utf8
}
if(-not $success){throw $failure}
