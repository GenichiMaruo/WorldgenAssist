[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('forge','neoforge')][string]$Loader,
    [Parameter(Mandatory)][ValidateSet('assisted','vanilla')][string]$Mode,
    [Parameter(Mandatory)][ValidateRange(1,2)][int]$Players,
    [Parameter(Mandatory)][string]$InstalledRoot,
    [Parameter(Mandatory)][string]$OutputRoot,
    [Parameter(Mandatory)][string]$AssetsRoot,
    [string]$OptionsTemplate,
    [switch]$TerrainDecisions,
    [switch]$CompleteTerrain,
    [ValidateSet('server','peer')][string]$CompleteVerification='server',
    [switch]$RemoteBiomes,
    [switch]$BiomeDigest,
    [ValidateRange(0,32)][int]$ServerActiveProcessorCount=0,
    [ValidateRange(2,10)][int]$ViewDistance=4,
    [ValidateSet('ready','overlap')][string]$RemoteApplicationProfile='ready',
    [switch]$StructuralShaping,
    [ValidateSet('off','serial','parallel')][string]$FeatureBackend='off',
    [switch]$DecorationDigest,
    [switch]$FeatureFixture,
    [string]$FeatureReplayFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if($CompleteTerrain){$TerrainDecisions=[switch]$true}
if($DecorationDigest -and -not $CompleteTerrain){throw 'Decoration comparison requires the bounded complete-terrain region fixture'}
if(($StructuralShaping -or $CompleteVerification -eq 'peer' -or $RemoteApplicationProfile -eq 'overlap') -and -not $CompleteTerrain){throw 'Shaping/peer/overlap native profiles require complete terrain'}
$nativeCenters=if($StructuralShaping){@(@{x=1000;z=-2000},@{x=-1000;z=2000})}else{@(@{x=100;z=100},@{x=-200;z=-200})}
$expectedWorkKind=if($CompleteTerrain){'COMPLETE_TERRAIN'}else{'TERRAIN_DECISIONS_AND_SURFACE'}
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts')).TrimEnd('\') + '\'
$installed = [IO.Path]::GetFullPath($InstalledRoot)
$output = [IO.Path]::GetFullPath($OutputRoot)
. (Join-Path $PSScriptRoot 'FeatureFixtureEvidence.ps1')
$replay=$null
if($FeatureFixture -and (-not $DecorationDigest -or -not $StructuralShaping -or $Players -ne 2)){throw 'Bounded structural two-owner diagnostic fixture required'}
if($FeatureReplayFile){
    if(-not $FeatureFixture -or -not [IO.Path]::GetFullPath($FeatureReplayFile).StartsWith($fixtureRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Replay requires fixture evidence child'}
    $replay=Read-FeatureReplay $FeatureReplayFile
}
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
    if ($TerrainDecisions) {
        foreach ($key in @('WORLDGEN_ASSIST_LOCAL_WORKERS','WORLDGEN_ASSIST_LOCAL_QUEUE_PER_WORKER')) {
            [void]$info.Environment.Remove($key)
        }
    }
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
        $pattern = if ($TerrainDecisions) {
            'job\.complete id=([0-9a-f-]+) source=(?:remote|cache|prefetch) .*work_kind='+$expectedWorkKind+' .*decision_samples=98304\b'
        } else { 'job.complete id=([0-9a-f-]+) source=remote' }
        if ($line -match $pattern -and $ids.Contains($Matches[1])) { return $true }
    }
    return $false
}

try {
    if (Get-NetTCPConnection -State Listen -LocalPort 25575,25585 -ErrorAction SilentlyContinue) { throw 'Fixture ports already listening' }
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    if($replay){Copy-Item -LiteralPath $replay.path -Destination (Join-Path $output 'feature-replay.json');if((Get-FileHash -LiteralPath (Join-Path $output 'feature-replay.json')).Hash.ToLowerInvariant() -ne $replay.sha256){throw 'Copied replay identity differs'}}
    $properties = $previousProperties
    foreach ($entry in @{
        'server-ip'='127.0.0.1';'server-port'='25585';'online-mode'='false';
        'enable-rcon'='true';'rcon.port'='25575';'rcon.password'=$password;
        'level-name'=$world;'level-seed'='8675309';'gamemode'='creative';
        'view-distance'=[string]$ViewDistance;'simulation-distance'='3';'max-players'=[string]$Players;
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
        WORLDGEN_ASSIST_FEATURE_BACKEND = $FeatureBackend
        WORLDGEN_ASSIST_DECORATION_DIGEST = ([bool]$DecorationDigest).ToString().ToLowerInvariant()
        WORLDGEN_ASSIST_FEATURE_FIXTURE = ([bool]$FeatureFixture).ToString().ToLowerInvariant()
        WORLDGEN_ASSIST_FEATURE_REPLAY = if($replay){Join-Path $output 'feature-replay.json'}else{''}
    }
    if ($TerrainDecisions) {
        $serverEnvironment.WORLDGEN_ASSIST_NOISE_BACKEND = 'cooperative'
        $serverEnvironment.WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT = '32'
        $serverEnvironment.WORLDGEN_ASSIST_REMOTE_OWNER_WINDOW = '16'
        $serverEnvironment.WORLDGEN_ASSIST_REMOTE_WORK_KIND = 'decisions'
        $serverEnvironment.WORLDGEN_ASSIST_REMOTE_ALLOW_TERRAIN_DECISIONS = 'true'
        if($CompleteTerrain){
            $serverEnvironment.WORLDGEN_ASSIST_REMOTE_WORK_KIND='complete'
            $serverEnvironment.WORLDGEN_ASSIST_REMOTE_ALLOW_TERRAIN_DECISIONS='false'
            $serverEnvironment.WORLDGEN_ASSIST_REMOTE_ALLOW_COMPLETE_TERRAIN='true'
            $serverEnvironment.WORLDGEN_ASSIST_REMOTE_COMPLETE_VERIFICATION=$CompleteVerification
            $serverEnvironment.WORLDGEN_ASSIST_REMOTE_ALLOW_REMOTE_BIOMES=([bool]$RemoteBiomes).ToString().ToLowerInvariant()
        }
        $serverEnvironment.WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES = '128'
        $serverEnvironment.WORLDGEN_ASSIST_REMOTE_PREFETCH = 'true'
        $serverEnvironment.WORLDGEN_ASSIST_REMOTE_PREFETCH_LOOKAHEAD = '0'
        $serverEnvironment.WORLDGEN_ASSIST_REMOTE_READY_SURFACE_ONLY = ($RemoteApplicationProfile -eq 'ready').ToString().ToLowerInvariant()
        if($RemoteApplicationProfile -eq 'overlap'){
            # Functional native fixture only, not a speed comparison: allow the two-thread clients to finish.
            $serverEnvironment.WORLDGEN_ASSIST_REMOTE_DEMAND_WAIT_MS='1000'
            $serverEnvironment.WORLDGEN_ASSIST_REMOTE_ADAPTIVE_DEMAND_WAIT='true'
        }
        $serverEnvironment.WORLDGEN_ASSIST_REMOTE_PREPARE_VALIDATION = 'true'
        $serverEnvironment.WORLDGEN_ASSIST_REMOTE_PREDICTION = 'false'
    }
    $serverArguments = @('-Xmx3G')
    if($ServerActiveProcessorCount){$serverArguments+="-XX:ActiveProcessorCount=$ServerActiveProcessorCount"}
    if($BiomeDigest){$serverArguments+='-Dworldgen_assist.biome.digest=true'}
    if ($TerrainDecisions) { $serverArguments += '-Dworldgen_assist.remote.diagnostics=true' }
    $serverArguments += @($nativeArguments,'nogui')
    $server = Start-Owned $java $serverArguments $serverRoot $serverEnvironment
    $serverStdout = $server.StandardOutput.ReadToEndAsync()
    $serverStderr = $server.StandardError.ReadToEndAsync()
    $serverLog = Join-Path $serverRoot 'logs/latest.log'
    Wait-Log $serverLog 'Done \(' 180 $server $server.StartTime.ToUniversalTime()

    $ownerNames = @('NativeA','NativeB')
    # Offline UUIDs are name-derived, not the launcher's supplied UUID. Wait
    # for this owner's handshake; a previous owner's line is not sufficient.
    $ownerIds = @('06b47fed-0490-3ea4-bd30-132cc3635f08','5caeb99e-d557-357d-9c19-ec1a763cd224')
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
            @('version:5023','onboardAccessibility:false','skipMultiplayerWarning:true','tutorialStep:none',
                'renderDistance:4','simulationDistance:4','fullscreen:false','enableVsync:false','maxFps:30') +
                (@('forward','back','left','right','jump','sneak','sprint','attack','use') | ForEach-Object { 'key_key.' + $_ + ':key.keyboard.unknown' }) |
                Set-Content -LiteralPath (Join-Path $clientRoot 'options.txt') -Encoding utf8
        }
        if($BiomeDigest){
            $optionPath=Join-Path $clientRoot 'options.txt'
            $optionLines=@(Get-Content -LiteralPath $optionPath|Where-Object {$_ -notmatch '^(renderDistance|graphicsPreset):'})
            $optionLines+=@("renderDistance:$ViewDistance",'graphicsPreset:"custom"')
            $optionLines|Set-Content -LiteralPath $optionPath -Encoding utf8
            $argumentPath=$launch.Arguments[0].Substring(1)
            @('-Dworldgen_assist.client.worker_threads=4')+@(Get-Content -LiteralPath $argumentPath)|Set-Content -LiteralPath $argumentPath
        }
        $clientEnvironment = @{WORLDGEN_ASSIST_REMOTE=if($Mode -eq 'assisted'){'true'}else{'false'}}
        if ($TerrainDecisions) { $clientEnvironment.WORLDGEN_ASSIST_CLIENT_JOB_WINDOW = '16' }
        $client = Start-Owned $launch.Executable $launch.Arguments $launch.WorkingDirectory $clientEnvironment
        $clients.Add([pscustomobject]@{name=$name;profile=$profile;process=$client;stdout=$client.StandardOutput.ReadToEndAsync();stderr=$client.StandardError.ReadToEndAsync();sha256=$launch.ModSha256})
        Wait-Log $serverLog ([regex]::Escape($name) + ' joined the game') 180 $client $server.StartTime.ToUniversalTime()
        if ($Mode -eq 'assisted') { Wait-Log $serverLog ('worker.register owner=' + $ownerIds[$index] + ' status=ACCEPTED') 60 $client $server.StartTime.ToUniversalTime() }
    }
    Send-ServerCommand 'gamerule minecraft:spectators_generate_chunks false'
    if ($TerrainDecisions) {
        $marker = 'CAWG_NATIVE_DECISIONS_BEGIN'
        Send-ServerCommand "say $marker"
        if($FeatureFixture){Send-ServerCommand 'worldgenassist_feature_fixture_start';Wait-Log $serverLog 'fixture.armed tickets=882 features=1458 spawns=1250 frozen=true' 30 $server $server.StartTime.ToUniversalTime()}
        else{for ($index=0; $index -lt $Players; $index++) {
            $center=$nativeCenters[$index]
            Send-ServerCommand ("execute in minecraft:overworld run tp $($ownerNames[$index]) "+($center.x*16)+' 150 '+($center.z*16))
        }}
        if ($Mode -eq 'assisted') {
            $deadline = [datetime]::UtcNow.AddSeconds(90)
            $ready = $false
            while ([datetime]::UtcNow -lt $deadline) {
                if ($server.HasExited -or ($clients | Where-Object { $_.process.HasExited })) { throw 'Native process exited during decision application' }
                $missing = @($ownerIds | Select-Object -First $Players | Where-Object { -not (Owner-Completes $serverLog $_ $marker) })
                if ($missing.Count -eq 0) { $ready = $true; break }
                Start-Sleep -Seconds 2
            }
            if (-not $ready) { throw 'Full terrain decisions were not applied for every native owner' }
        }
        # Complete the same bounded regions in both modes so every applied
        # owner's coordinate also has independent vanilla comparison data.
        $required = @{}
        for ($index=0; $index -lt $Players; $index++) {
            $center=$nativeCenters[$index]
            if(-not $FeatureFixture){foreach ($xs in @(@(-10,0),@(1,10))) { foreach ($zs in @(@(-10,0),@(1,10))) {
                Send-ServerCommand ("execute in minecraft:overworld run forceload add " + (($center.x+$xs[0])*16) + ' ' + (($center.z+$zs[0])*16) + ' ' + (($center.x+$xs[1])*16) + ' ' + (($center.z+$zs[1])*16))
            } }}
            for ($dx=-10;$dx -le 10;$dx++) { for ($dz=-10;$dz -le 10;$dz++) { $required["$($center.x+$dx),$($center.z+$dz)"]=$true } }
        }
        $decorationRemaining=if($DecorationDigest){$required.Clone()}else{@{}}
        if($DecorationDigest){@($required.Keys|Sort-Object)|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $output 'decoration-required-chunks.json')}
        $deadline = [datetime]::UtcNow.AddSeconds($(if($DecorationDigest){300}else{120}))
        while (($required.Count -gt 0 -or $decorationRemaining.Count -gt 0) -and [datetime]::UtcNow -lt $deadline) {
            if ($server.HasExited -or ($clients | Where-Object { $_.process.HasExited })) { throw 'Native process exited before paired regions completed' }
            foreach ($entry in [regex]::Matches((Get-Content -LiteralPath $serverLog -Raw),
                'stage\.digest stage=noise chunk=(-?\d+,-?\d+) .*dimension=minecraft:overworld\b')) {
                $required.Remove($entry.Groups[1].Value)
            }
            if($DecorationDigest){foreach($entry in [regex]::Matches((Get-Content -LiteralPath $serverLog -Raw),'stage\.digest stage=decoration chunk=(-?\d+,-?\d+) .*dimension=minecraft:overworld\b')){$decorationRemaining.Remove($entry.Groups[1].Value)}}
            if ($required.Count -or $decorationRemaining.Count) { Start-Sleep -Seconds 2 }
        }
        if ($required.Count) { throw "Native comparison region missing $($required.Count) terrain digests" }
        if ($decorationRemaining.Count) { throw "Native comparison region missing $($decorationRemaining.Count) final-decoration digests" }
        if($FeatureFixture){Wait-Log $serverLog 'fixture.complete ' 120 $server $server.StartTime.ToUniversalTime()}
    } elseif ($Mode -eq 'assisted') {
        $owners = @($ownerIds | Select-Object -First $Players)
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
    $result = [ordered]@{schema='worldgen-assist.installed-native-scenario.v1';loader=$Loader;mode=$Mode;players=$Players;world=$world;success=$success;cleanup_safe=$cleanupSafe;loopback_only=$true;failure=$failure;mod_sha256=$modHash;terrain_decisions=[bool]$TerrainDecisions;complete_terrain=[bool]$CompleteTerrain;complete_verification=$CompleteVerification;remote_application_profile=$RemoteApplicationProfile;structural_shaping=[bool]$StructuralShaping;remote_biomes=[bool]$RemoteBiomes;biome_digest=[bool]$BiomeDigest;view_distance=4;validation_cells=8}
    $result.server_active_processor_count=$ServerActiveProcessorCount
    $result.view_distance=$ViewDistance
    $result.client_worker_threads=if($BiomeDigest){4}else{2}
    $result.feature_backend=$FeatureBackend
    $result.decoration_digest=[bool]$DecorationDigest
    $result.feature_fixture=[bool]$FeatureFixture
    $result.feature_replay_sha256=if($replay){$replay.sha256}else{'record'}
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
}
if (-not $success) { throw $failure }
Get-Content -LiteralPath (Join-Path $output 'result.json') -Raw
