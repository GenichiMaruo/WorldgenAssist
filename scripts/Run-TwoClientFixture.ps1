[CmdletBinding()]
param(
    [ValidateSet('fixture', 'trusted-raw')][string]$Route = 'fixture',
    [ValidateSet('simultaneous', 'vanilla', 'disconnect')][string]$Mode = 'simultaneous',
    [switch]$OwnerADevelopmentWithhold,
    [string]$RemoteHost = 'gen1c@100.117.255.71',
    [string]$RemoteRoot = 'C:/Users/gen1c/AppData/Local/Temp/WorldgenAssist-20260909'
)

# This is an installed-client functional harness, not a benchmark. It creates
# fresh evidence/world names and leaves old worlds, ledgers and published JARs
# untouched. Run it only after the final A-D verification checkpoint.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($OwnerADevelopmentWithhold -and ($Route -ne 'fixture' -or $Mode -ne 'disconnect')) {
    throw 'OwnerADevelopmentWithhold is only valid for the fixture disconnect probe'
}
$workspace = Split-Path -Parent $PSScriptRoot
$root = Join-Path $workspace ('test-artifacts/two-client-' + $Route + '-' + $Mode + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$serverEvidence = Join-Path $root 'server/logs'
$assetRoot = $null
$jdk = 'C:\Program Files\Java\jdk-25.0.4'
$api = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/0.156.0+26.2/d96e0d9ef8ea3604fac4ca7495d7c6148f3ac816/fabric-api-0.156.0+26.2.jar'
$artifact = & (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Workspace $workspace

if (-not (Test-Path -LiteralPath $artifact.Path)) { throw "Exact built mod artifact is missing: $($artifact.Path)" }
if (-not (Test-Path -LiteralPath "$jdk\bin\java.exe")) { throw 'Required JDK 25.0.4 missing' }
if (-not (Test-Path -LiteralPath $api)) { throw 'Pinned Fabric API cache entry missing' }
if ((Get-Content -LiteralPath (Join-Path $workspace 'run/eula.txt') -Raw) -notmatch '(?m)^eula=true\s*$') { throw 'Existing user EULA acceptance required' }
if (Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue) { throw 'Local tunnel port 25585 is occupied' }

New-Item -ItemType Directory -Path $root, $serverEvidence | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $root 'runner.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Remote-TwoClientFixtureServer.ps1') -Destination $root
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'New-TwoClientFixtureClient.ps1') -Destination $root
$jarHash = (Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash
[ordered]@{file=$artifact.FileName;sha256=$jarHash;path=$artifact.Path} | ConvertTo-Json |
    Set-Content -LiteralPath (Join-Path $root 'installed-jar-sha256.json')

function Write-SourceManifest([string]$Destination) {
    $inputs = @(
        Get-ChildItem -LiteralPath (Join-Path $workspace 'src') -File -Recurse
    foreach ($name in @(
        'build.gradle', 'settings.gradle', 'gradle.properties',
        'scripts/Run-TwoClientFixture.ps1', 'scripts/New-TwoClientFixtureClient.ps1',
        'scripts/Remote-TwoClientFixtureServer.ps1', 'scripts/Compare-TwoClientFixture.ps1'
    )) {
            $path = Join-Path $workspace $name
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required build input missing: $name" }
            Get-Item -LiteralPath $path
        }
    ) | Sort-Object FullName
    $inputs | ForEach-Object {
        "$( (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash )  $($_.FullName.Substring($workspace.Length + 1))"
    } | Set-Content -LiteralPath $Destination
}
function Assert-SourceSnapshotUnchanged {
    $before = Join-Path $root 'source-sha256.txt'
    $after = Join-Path $root 'source-sha256-after.txt'
    Write-SourceManifest $after
    if (@(Compare-Object -ReferenceObject (Get-Content -LiteralPath $before) -DifferenceObject (Get-Content -LiteralPath $after)).Count -ne 0) {
        throw 'Source or build input changed during runtime fixture; comparison is rejected'
    }
}
function Receive-RemoteEvidence([string]$Case) {
    if ([string]::IsNullOrWhiteSpace($Case)) { return $false }
    $destination = Join-Path $root 'remote-evidence'
    if (Test-Path -LiteralPath $destination) { return $true }
    & scp -q -r ($RemoteHost + ':' + $RemoteRoot + '/evidence/' + $Case) $destination
    return $LASTEXITCODE -eq 0
}
Write-SourceManifest (Join-Path $root 'source-sha256.txt')

function Remote-Code([string]$Code) {
    $prefixed = '$ErrorActionPreference="Stop"; $ProgressPreference="SilentlyContinue"; ' + $Code
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($prefixed))
    & ssh -o BatchMode=yes -o ConnectTimeout=10 $RemoteHost "powershell -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand $encoded"
    if ($LASTEXITCODE -ne 0) { throw 'SSH command failed' }
}
function Start-Owned([string]$Executable, [string[]]$Arguments, [hashtable]$Environment = @{}, [string]$WorkingDirectory = $workspace) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    foreach ($key in $Environment.Keys) { $info.Environment[$key] = $Environment[$key] }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
    if (-not $process.Start()) { throw "Failed to start owned process: $Executable" }
    return $process
}
function Start-FixtureOwner(
    [string]$Name,
    [string]$Uuid,
    [string]$ProfileName,
    [ValidateSet('installed', 'development')][string]$ClientRuntime = 'installed',
    [ValidateSet('none', 'withhold')][string]$FixtureFault = 'none'
) {
    $profile = Join-Path $root ('clients/' + $ProfileName)
    $launch = & (Join-Path $PSScriptRoot 'New-TwoClientFixtureClient.ps1') -Username $Name -Uuid $Uuid -Root $profile -AssetsRoot $assetRoot -ClientRuntime $ClientRuntime -FixtureFault $FixtureFault
    $environment = @{
        JAVA_HOME = $jdk; Path = "$jdk\bin;$env:Path"
        WORLDGEN_ASSIST_REMOTE = if ($Route -eq 'trusted-raw' -and $Mode -ne 'vanilla') { 'true' } else { 'false' }
        WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_PUBLIC_FIXTURE = if ($Route -eq 'fixture' -and $Mode -ne 'vanilla') { 'true' } else { 'false' }
        WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_FIXTURE_FAULT = $launch.FixtureFault
    }
    $process = Start-Owned $launch.Executable $launch.Arguments $environment $launch.WorkingDirectory
    return [pscustomobject]@{ Name=$Name; Profile=$profile; Process=$process; Stdout=$process.StandardOutput.ReadToEndAsync(); Stderr=$process.StandardError.ReadToEndAsync(); Runtime=$launch.Runtime; FixtureFault=$launch.FixtureFault; Executable=$launch.Executable; Arguments=@($launch.Arguments); WorkingDirectory=$launch.WorkingDirectory; Username=$launch.Username; Uuid=$launch.Uuid }
}
function Write-OwnerLaunchManifest([pscustomobject]$Owner) {
    [ordered]@{
        owner = $Owner.Name
        username = $Owner.Username
        requested_uuid = $Owner.Uuid
        runtime = $Owner.Runtime
        fixture_fault = $Owner.FixtureFault
        executable = $Owner.Executable
        arguments = @($Owner.Arguments)
        working_directory = $Owner.WorkingDirectory
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root ($Owner.Name + '-launch.json'))
}
function Get-OwnedClientWindow([pscustomobject]$Owner) {
    if ($Owner.Runtime -eq 'installed') { return $Owner.Process }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        $processes = @(Get-CimInstance -ClassName Win32_Process)
        $ownedPids = [Collections.Generic.HashSet[int]]::new()
        $pending = [Collections.Generic.Queue[int]]::new()
        [void]$ownedPids.Add([int]$Owner.Process.Id); $pending.Enqueue([int]$Owner.Process.Id)
        while ($pending.Count -gt 0) {
            $parentId = $pending.Dequeue()
            foreach ($child in @($processes | Where-Object { $_.ParentProcessId -eq $parentId })) {
                if ($ownedPids.Add([int]$child.ProcessId)) { $pending.Enqueue([int]$child.ProcessId) }
            }
        }
        foreach ($processId in @($ownedPids | Sort-Object -Descending)) {
            if ($processId -eq $Owner.Process.Id) { continue }
            $candidate = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($null -ne $candidate -and $candidate.MainWindowHandle -ne [IntPtr]::Zero) { return $candidate }
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Development client did not expose an owned Minecraft window: $($Owner.Name)"
}
function Confirm-NaturalClientExit([pscustomobject]$Owner) {
    $windowProcess = Get-OwnedClientWindow $Owner
    # Keep the handle before the externally obtained dev process exits so its
    # exit code remains available to System.Diagnostics.Process afterwards.
    $windowHandle = $windowProcess.Handle
    if (-not $windowProcess.CloseMainWindow()) { throw "Client did not accept window close: $($Owner.Name)" }
    if (-not $windowProcess.WaitForExit(30000) -or $windowProcess.ExitCode -ne 0) { throw "Client window process did not exit gracefully: $($Owner.Name)" }
    if ($windowProcess.Id -ne $Owner.Process.Id -and -not $Owner.Process.WaitForExit(30000)) { throw "Development launcher did not exit after client close: $($Owner.Name)" }
    if ($Owner.Process.ExitCode -ne 0) { throw "Client launcher did not exit gracefully: $($Owner.Name)" }
    $clientLog = Join-Path $Owner.Profile 'client/logs/latest.log'
    if ((Get-Content -LiteralPath $clientLog -Raw) -notmatch 'Stopping!') { throw "Minecraft shutdown marker missing: $($Owner.Name)" }
    if ((Get-Content -LiteralPath $clientLog -Raw) -match 'Mixin apply failed|Encountered an unexpected exception') { throw "Client runtime error: $($Owner.Name)" }
    "owner=$($Owner.Name); runtime=$($Owner.Runtime); close_request=owned_process_main_window; close_target_pid=$($windowProcess.Id); natural_exit=0; minecraft_stopping_marker=true" |
        Set-Content -LiteralPath (Join-Path $root ($Owner.Name + '-shutdown.txt'))
}

$tunnel = $null; $server = $null; $ownerA = $null; $ownerB = $null; $success = $false
$case = $null; $remoteEvidenceDownloaded = $false
try {
    Write-Output "evidence=$root"
    # Prepare the checksum-verified isolated asset root before touching the
    # remote server, so an installed-client preflight failure cannot leave a
    # remote fixture waiting for workers.
    $assetRoot = & (Join-Path $PSScriptRoot 'Prepare-InstalledFixtureAssets.ps1')
    if ([string]::IsNullOrWhiteSpace([string]$assetRoot) -or -not (Test-Path -LiteralPath $assetRoot -PathType Container)) {
        throw 'Verified installed-client asset preparation did not return a directory'
    }
    [IO.Path]::GetFullPath($assetRoot) | Set-Content -LiteralPath (Join-Path $root 'installed-client-assets-root.txt')
    Remote-Code ('New-Item -ItemType Directory -Force -Path "' + $RemoteRoot + '/mods" | Out-Null')
    Remote-Code ('$old=@(Get-ChildItem -LiteralPath "' + $RemoteRoot + '/mods" -Filter "worldgen-assist-*.jar" -File | Where-Object { $_.Name -ne "' + $artifact.FileName + '" }); if ($old.Count -gt 0) { throw "Archive the previous fixture mod outside mods before testing a new version; no automatic deletion performed" }')
    & scp -q $artifact.Path $api ($RemoteHost + ':' + $RemoteRoot + '/mods/')
    if ($LASTEXITCODE -ne 0) { throw 'Mod transfer failed' }
    & scp -q (Join-Path $workspace 'run/eula.txt') (Join-Path $PSScriptRoot 'Remote-TwoClientFixtureServer.ps1') ($RemoteHost + ':' + $RemoteRoot + '/')
    if ($LASTEXITCODE -ne 0) { throw 'Remote helper transfer failed' }
    Remote-Code ('$expected="' + $jarHash + '"; if ((Get-FileHash -LiteralPath "' + $RemoteRoot + '/mods/' + $artifact.FileName + '" -Algorithm SHA256).Hash -ne $expected) { throw "JAR hash mismatch" }; "REMOTE_JAR_SHA256=" + $expected') |
        Tee-Object -FilePath (Join-Path $root 'remote-jar-verification.txt')

    $tunnel = Start-Owned 'ssh.exe' @('-N', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=2', '-L', '127.0.0.1:25585:127.0.0.1:25585', $RemoteHost)
    $tunnelOut = $tunnel.StandardOutput.ReadToEndAsync(); $tunnelErr = $tunnel.StandardError.ReadToEndAsync()
    $remoteCommand = '& "' + $RemoteRoot + '/Remote-TwoClientFixtureServer.ps1" -Root "' + $RemoteRoot + '" -Route ' + $Route + ' -Mode ' + $Mode
    $remoteEncoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($remoteCommand))
    $server = Start-Owned 'ssh.exe' @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', $RemoteHost, "powershell -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand $remoteEncoded")
    $serverErr = $server.StandardError.ReadToEndAsync()
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(240); $lineTask = $server.StandardOutput.ReadLineAsync()
    while ($null -eq $case) {
        if ($tunnel.HasExited) { throw 'SSH tunnel exited before server readiness' }
        if ([DateTime]::UtcNow -gt $readyDeadline) { throw 'Remote readiness timeout' }
        if ($lineTask.IsCompleted) {
            $line = $lineTask.GetAwaiter().GetResult()
            if ($null -eq $line) { throw 'Remote runner ended before readiness' }
            $line | Add-Content -LiteralPath (Join-Path $root 'remote-runner.log')
            if ($line -match '^SERVER_READY (\S+)$') { $case = $Matches[1] } else { $lineTask = $server.StandardOutput.ReadLineAsync() }
        }
        Start-Sleep -Milliseconds 250
    }
    $serverOut = $server.StandardOutput.ReadToEndAsync()
    $ownerARuntime = if ($OwnerADevelopmentWithhold) { 'development' } else { 'installed' }
    $ownerAFault = if ($OwnerADevelopmentWithhold) { 'withhold' } else { 'none' }
    Write-Output "remote=ready case=$case; starting owner A runtime=$ownerARuntime fault=$ownerAFault and installed owner B"
    $ownerA = Start-FixtureOwner 'FixtureOwnerA' '000000000000000000000000000000a1' 'owner-a' $ownerARuntime $ownerAFault
    $ownerB = Start-FixtureOwner 'FixtureOwnerB' '000000000000000000000000000000b2' 'owner-b'
    Write-OwnerLaunchManifest $ownerA
    Write-OwnerLaunchManifest $ownerB
    $finishDeadline = [DateTime]::UtcNow.AddSeconds(420)
    while (-not $server.HasExited) {
        if ($tunnel.HasExited -or $ownerA.Process.HasExited -or $ownerB.Process.HasExited) { throw 'Tunnel or client exited before remote fixture completed' }
        if ([DateTime]::UtcNow -gt $finishDeadline) { throw 'Two-client fixture exceeded deadline' }
        Start-Sleep -Milliseconds 500
    }
    $serverOut.GetAwaiter().GetResult() | Add-Content -LiteralPath (Join-Path $root 'remote-runner.log')
    $remoteEvidenceDownloaded = Receive-RemoteEvidence $case
    if (-not $remoteEvidenceDownloaded) { throw 'Evidence download failed' }
    Copy-Item -LiteralPath (Join-Path $root 'remote-evidence/latest.log') -Destination (Join-Path $root 'server/logs/latest.log')
    if ($server.ExitCode -ne 0) { throw 'Remote fixture failed' }
    if ((Get-Content -LiteralPath (Join-Path $root 'remote-evidence/summary.txt') -Raw) -notmatch '(?m)^success=True\s*$') { throw 'Remote server did not record success' }
    foreach ($owner in @($ownerA, $ownerB)) { Confirm-NaturalClientExit $owner }
    Assert-SourceSnapshotUnchanged
    $success = $true
} finally {
    if (-not $remoteEvidenceDownloaded -and $null -ne $case) {
        try { $remoteEvidenceDownloaded = Receive-RemoteEvidence $case }
        catch { $_ | Out-String | Set-Content -LiteralPath (Join-Path $root 'remote-evidence-download-error.txt') }
    }
    if (Test-Path -LiteralPath (Join-Path $root 'source-sha256.txt')) {
        try { Write-SourceManifest (Join-Path $root 'source-sha256-after.txt') }
        catch { $_ | Out-String | Set-Content -LiteralPath (Join-Path $root 'source-manifest-error.txt') }
    }
    foreach ($owned in @($ownerA, $ownerB)) {
        if ($null -eq $owned) { continue }
        if (-not $owned.Process.HasExited) { $owned.Process.Kill($true); $owned.Process.WaitForExit() }
        $owned.Stdout.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root ($owned.Name + '-stdout.log'))
        $owned.Stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root ($owned.Name + '-stderr.log'))
        $owned.Process.Dispose()
    }
    foreach ($owned in @($server, $tunnel)) {
        if ($null -ne $owned -and -not $owned.HasExited) { $owned.Kill($true); $owned.WaitForExit() }
    }
    if ($null -ne $server) { $serverErr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root 'remote-stderr.log') }
    if ($null -ne $tunnel) { $tunnelOut.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root 'tunnel-stdout.log'); $tunnelErr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root 'tunnel-stderr.log') }
    $clientSummary = if ($OwnerADevelopmentWithhold) {
        'server=installed JAR; ownerA=Fabric Loader development runtime with WITHHOLD_RESULT; ownerB=installed JAR profile'
    } else {
        'server=installed JAR; clients=two installed JAR profiles'
    }
    @("route=$Route", "mode=$Mode", "owner_a_development_withhold=$OwnerADevelopmentWithhold", "success=$success", $clientSummary, 'launch_arguments=FixtureOwnerA-launch.json,FixtureOwnerB-launch.json', 'source_hashes=source-sha256.txt,source-sha256-after.txt', 'transport=SSH tunnel over Tailscale', 'performance=NOT_EVALUATED') |
        Set-Content -LiteralPath (Join-Path $root 'summary.txt')
    Write-Output "evidence=$root success=$success"
}
