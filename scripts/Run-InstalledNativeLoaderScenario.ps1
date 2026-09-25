[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('forge','neoforge')][string]$Loader,
    [Parameter(Mandatory)][ValidateSet('assisted','vanilla')][string]$Mode,
    [Parameter(Mandatory)][ValidateRange(1,2)][int]$Players,
    [Parameter(Mandatory)][string]$InstalledRoot,
    [Parameter(Mandatory)][string]$OutputRoot,
    [Parameter(Mandatory)][string]$AssetsRoot,
    [string]$OptionsTemplate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts')).TrimEnd('\') + '\'
$installed = [IO.Path]::GetFullPath($InstalledRoot)
$output = [IO.Path]::GetFullPath($OutputRoot)
foreach ($path in @($installed,$output)) {
    if (-not $path.StartsWith($fixtureRoot,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Native loader scenarios must remain under test-artifacts'
    }
}
if (Test-Path -LiteralPath $output) { throw 'OutputRoot must be new; existing evidence is immutable' }
if ($OptionsTemplate) {
    $OptionsTemplate = [IO.Path]::GetFullPath($OptionsTemplate)
    if (-not $OptionsTemplate.StartsWith($fixtureRoot,[StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $OptionsTemplate -PathType Leaf)) {
        throw 'OptionsTemplate must be a file beneath test-artifacts'
    }
}
$serverRoot = Join-Path $installed 'server'
$installerProfile = Join-Path $installed 'client-profile'
$artifact = & (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Workspace $workspace -Loader $Loader
$builtMod = $artifact.Path
$installedMod = Join-Path $serverRoot ('mods/'+$artifact.FileName)
if (-not (Test-Path -LiteralPath $builtMod -PathType Leaf) -or -not (Test-Path -LiteralPath $installedMod -PathType Leaf)) { throw 'Exact native loader JAR is missing' }
$modHash = (Get-FileHash -LiteralPath $builtMod -Algorithm SHA256).Hash
if ((Get-FileHash -LiteralPath $installedMod -Algorithm SHA256).Hash -ne $modHash) { throw 'Installed server JAR does not match the exact build' }
$serverProperties = Join-Path $serverRoot 'server.properties'
$previousProperties = Get-Content -LiteralPath $serverProperties -Raw
$java = 'C:\Program Files\Java\jdk-25.0.4\bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Pinned JDK 25.0.4 missing' }
$nativePath = if ($Loader -eq 'forge') { 'minecraftforge/forge/26.3-66.0.3' } else { 'neoforged/neoforge/26.3.0.13-beta' }
$nativeArguments = "@libraries/net/$nativePath/win_args.txt"
$password = 'WorldgenAssistFixture26_3'
$clients = [Collections.Generic.List[object]]::new()
$server = $null
$serverStdout = $null
$serverStderr = $null
$success = $false
$cleanupSafe = $true
$failure = $null
$world = 'native-' + $Loader + '-' + $Mode + '-' + $Players + '-' + (Get-Date -Format 'yyyyMMddHHmmssfff')

function Start-Owned([string]$Exe,[string[]]$Arguments,[string]$WorkingDirectory,[hashtable]$Environment) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Exe
    foreach ($argument in $Arguments) { [void]$info.ArgumentList.Add($argument) }
    $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($key in $Environment.Keys) { $info.Environment[$key] = $Environment[$key] }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    if (-not $process.Start()) { throw "Could not start $Exe" }
    $handle = $process.Handle
    return $process
}
function Wait-Log([string]$Path,[string]$Pattern,[int]$Seconds,[Diagnostics.Process]$Process,[datetime]$Started) {
    $deadline = [datetime]::UtcNow.AddSeconds($Seconds)
    while ([datetime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) { throw "Process exited waiting for $Pattern (code $($Process.ExitCode))" }
        $log = Get-Item -LiteralPath $Path -ErrorAction SilentlyContinue
        if ($log -and $log.LastWriteTimeUtc -ge $Started -and (Select-String -LiteralPath $Path -Pattern $Pattern -Quiet)) { return }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for $Pattern"
}
function Set-Property([string]$Text,[string]$Key,[string]$Value) {
    $pattern = '(?m)^' + [regex]::Escape($Key) + '=.*$'
    if ($Text -match $pattern) { return [regex]::Replace($Text,$pattern,($Key + '=' + $Value)) }
    return $Text.TrimEnd() + "`n" + $Key + '=' + $Value + "`n"
}
function Send-ServerCommand([string]$Command) {
    if ($null -eq $server -or $server.HasExited) { throw "Server is unavailable for command: $Command" }
    $server.StandardInput.WriteLine($Command)
    $server.StandardInput.Flush()
}
function Owner-Completes([string]$Log,[string]$Owner,[string]$Marker) {
    $text = Get-Content -LiteralPath $Log -Raw
    $start = $text.LastIndexOf($Marker,[StringComparison]::Ordinal)
    if ($start -lt 0) { return $false }
    $slice = $text.Substring($start)
    $ids = [Collections.Generic.HashSet[string]]::new()
    foreach ($line in ($slice -split "`n")) {
        if ($line -match 'job.sent id=([0-9a-f-]+).* owner=([0-9a-f-]+)' -and $Matches[2] -eq $Owner) { [void]$ids.Add($Matches[1]) }
    }
    foreach ($line in ($slice -split "`n")) {
        if ($line -match 'job.complete id=([0-9a-f-]+) source=remote' -and $ids.Contains($Matches[1])) { return $true }
    }
    return $false
}

try {
    if (Get-NetTCPConnection -State Listen -LocalPort 25575,25585 -ErrorAction SilentlyContinue) { throw 'Fixture ports already listening' }
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $properties = $previousProperties
    foreach ($entry in @{
        'server-ip'='127.0.0.1';'server-port'='25585';'online-mode'='false';
        'enable-rcon'='true';'rcon.port'='25575';'rcon.password'=$password;
        'level-name'=$world;'level-seed'='8675309';'gamemode'='creative';
        'view-distance'='4';'simulation-distance'='3';'max-players'=[string]$Players;
        'pause-when-empty-seconds'='-1'
    }.GetEnumerator()) { $properties = Set-Property $properties $entry.Key $entry.Value }
    [IO.File]::WriteAllText($serverProperties,$properties)
    $serverEnvironment = @{
        WORLDGEN_ASSIST_REMOTE = if ($Mode -eq 'assisted') { 'true' } else { 'false' }
        WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE = if ($Mode -eq 'assisted') { 'trusted_raw' } else { 'deny' }
        WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS = '30000'
        WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT = '8'
        WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS = '8'
        WORLDGEN_ASSIST_NOISE_DIGEST = 'true'
    }
    $server = Start-Owned $java @('-Xmx3G',$nativeArguments,'nogui') $serverRoot $serverEnvironment
    $serverStdout = $server.StandardOutput.ReadToEndAsync()
    $serverStderr = $server.StandardError.ReadToEndAsync()
    $serverLog = Join-Path $serverRoot 'logs/latest.log'
    Wait-Log $serverLog 'Done \(' 180 $server $server.StartTime.ToUniversalTime()

    $ownerNames = @('NativeA','NativeB')
    for ($index=0; $index -lt $Players; $index++) {
        $name = $ownerNames[$index]
        $uuid = if ($index -eq 0) { '000000000000000000000000000000e1' } else { '000000000000000000000000000000e2' }
        $profile = Join-Path $output ('client-' + $name)
        $launch = & (Join-Path $PSScriptRoot 'New-InstalledNativeLoaderClient.ps1') -Loader $Loader -Root $profile -InstallerProfile $installerProfile -AssetsRoot $AssetsRoot -Username $name -Uuid $uuid
        if ($launch.ModSha256 -ne $modHash) { throw 'Installed client JAR does not match the server build' }
        $clientRoot = Join-Path $profile 'client'
        if ($OptionsTemplate) {
            Copy-Item -LiteralPath $OptionsTemplate -Destination (Join-Path $clientRoot 'options.txt')
        } else {
            @('version:5023','renderDistance:4','simulationDistance:4','fullscreen:false','enableVsync:false') +
                (@('forward','back','left','right','jump','sneak','sprint','attack','use') | ForEach-Object { 'key_key.' + $_ + ':key.keyboard.unknown' }) |
                Set-Content -LiteralPath (Join-Path $clientRoot 'options.txt') -Encoding utf8
        }
        $client = Start-Owned $launch.Executable $launch.Arguments $launch.WorkingDirectory @{WORLDGEN_ASSIST_REMOTE=if($Mode -eq 'assisted'){'true'}else{'false'}}
        $clients.Add([pscustomobject]@{name=$name;profile=$profile;process=$client;stdout=$client.StandardOutput.ReadToEndAsync();stderr=$client.StandardError.ReadToEndAsync();sha256=$launch.ModSha256})
        Wait-Log $serverLog ([regex]::Escape($name) + ' joined the game') 180 $client $server.StartTime.ToUniversalTime()
        if ($Mode -eq 'assisted') { Wait-Log $serverLog 'worker.register owner=.* status=ACCEPTED' 60 $client $server.StartTime.ToUniversalTime() }
    }
    Send-ServerCommand 'gamerule minecraft:spectators_generate_chunks false'
    if ($Mode -eq 'assisted') {
        $owners = @(Select-String -LiteralPath $serverLog -Pattern 'worker.register owner=([0-9a-f-]+) status=ACCEPTED' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -Unique)
        if ($owners.Count -ne $Players) { throw 'Not all distinct owners registered' }
        Send-ServerCommand 'gamemode spectator @a'
        for ($index=0; $index -lt $Players; $index++) {
            $name = $ownerNames[$index]
            $owner = $owners[$index]
            $magnitude = 1600 + ($index * 1600)
            $base = if ($index -eq 0) { $magnitude } else { -$magnitude }
            Send-ServerCommand ("gamemode creative $name")
            $marker = "CAWG_NATIVE_OWNER_${index}_BEGIN"
            Send-ServerCommand ("say $marker")
            Send-ServerCommand ("execute in minecraft:overworld run tp $name $base 150 $base")
            $deadline = [datetime]::UtcNow.AddSeconds(60)
            $relocateAt = [datetime]::UtcNow.AddSeconds(20)
            $relocated = $false
            $ready = $false
            while ([datetime]::UtcNow -lt $deadline) {
                if (Owner-Completes $serverLog $owner $marker) { $ready = $true; break }
                if ($clients | Where-Object { $_.process.HasExited }) { throw 'A client exited before owner remote completion' }
                if (-not $relocated -and [datetime]::UtcNow -ge $relocateAt) {
                    $relocated = $true
                    $next = if ($index -eq 0) { $base + 1024 } else { $base - 1024 }
                    Send-ServerCommand ("execute in minecraft:overworld run tp $name $next 150 $next")
                }
                Start-Sleep -Seconds 2
            }
            if (-not $ready) { throw "Remote completion missing for $name" }
            Send-ServerCommand ("gamemode spectator $name")
        }
        Send-ServerCommand 'gamemode creative @a'
    } else {
        for ($index=0; $index -lt $Players; $index++) {
            $magnitude = 1600 + ($index * 1600)
            $base = if ($index -eq 0) { $magnitude } else { -$magnitude }
            Send-ServerCommand ("execute in minecraft:overworld run tp $($ownerNames[$index]) $base 150 $base")
        }
        Start-Sleep -Seconds 20
    }
    Send-ServerCommand 'save-all flush'
    Send-ServerCommand 'stop'
    if (-not $server.WaitForExit(60000) -or $server.ExitCode -ne 0) { throw 'Server did not stop cleanly' }
    Copy-Item -LiteralPath $serverLog -Destination (Join-Path $output 'latest.log')
    if (Select-String -LiteralPath $serverLog -Pattern 'Mixin apply failed|Encountered an unexpected exception' -Quiet) { throw 'Server runtime error in log' }
    foreach ($client in $clients) {
        if (-not $client.process.HasExited) {
            if (-not $client.process.CloseMainWindow() -or -not $client.process.WaitForExit(30000)) { throw "Client did not close: $($client.name)" }
        }
        if ($client.process.ExitCode -ne 0) { throw "Client failed: $($client.name) exit code $($client.process.ExitCode)" }
        $clientLog = Join-Path $client.profile 'client/logs/latest.log'
        if (Test-Path -LiteralPath $clientLog) {
            if (Select-String -LiteralPath $clientLog -Pattern 'Mixin apply failed|Encountered an unexpected exception' -Quiet) { throw "Client runtime error: $($client.name)" }
        }
    }
    $success = $true
} catch { $failure = $_.Exception.Message }
finally {
    if ($server -and -not $server.HasExited) {
        try { Send-ServerCommand 'stop'; [void]$server.WaitForExit(30000) } catch {}
    }
    foreach ($entry in $clients) {
        if (-not $entry.process.HasExited) {
            try { & "$env:SystemRoot\System32\taskkill.exe" '/PID' $entry.process.Id '/T' '/F' | Out-Null; [void]$entry.process.WaitForExit(30000) } catch { $cleanupSafe = $false }
        }
        $entry.stdout.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $output ($entry.name + '-stdout.log'))
        $entry.stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $output ($entry.name + '-stderr.log'))
        $entry.process.Dispose()
    }
    if ($server) {
        if (-not $server.HasExited) {
            try { & "$env:SystemRoot\System32\taskkill.exe" '/PID' $server.Id '/T' '/F' | Out-Null; [void]$server.WaitForExit(30000) } catch { $cleanupSafe = $false }
        }
        if ($serverStdout) { $serverStdout.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $output 'server-stdout.log') }
        if ($serverStderr) { $serverStderr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $output 'server-stderr.log') }
        $server.Dispose()
    }
    [IO.File]::WriteAllText($serverProperties,$previousProperties)
    $logCopy = Join-Path $output 'latest.log'
    if (-not (Test-Path -LiteralPath $logCopy) -and (Test-Path -LiteralPath (Join-Path $serverRoot 'logs/latest.log'))) {
        Copy-Item -LiteralPath (Join-Path $serverRoot 'logs/latest.log') -Destination $logCopy
    }
    $result = [ordered]@{schema='worldgen-assist.installed-native-scenario.v1';loader=$Loader;mode=$Mode;players=$Players;world=$world;success=$success;cleanup_safe=$cleanupSafe;loopback_only=$true;failure=$failure;mod_sha256=$modHash}
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
}
if (-not $success) { throw $failure }
Get-Content -LiteralPath (Join-Path $output 'result.json') -Raw
