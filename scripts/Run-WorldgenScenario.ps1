[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('overworld','the_nether','the_end','fixture')][string]$Dimension,
    [Parameter(Mandatory)][ValidateSet('vanilla','assisted')][string]$Mode,
    [Parameter(Mandatory)][ValidateRange(1,2)][int]$Players,
    [Parameter(Mandatory)][ValidateSet('correctness','performance')][string]$Purpose,
    [Parameter(Mandatory)][ValidateRange(0,256)][int]$CacheEntries,
    [Parameter(Mandatory)][ValidateSet('true','false')][string]$Prediction,
    [Parameter(Mandatory)][ValidateRange(0,64)][int]$ValidationCells,
    [Parameter(Mandatory)][long]$Seed,
    [Parameter(Mandatory)][string]$OutputRoot,
    [ValidateSet(0,1,2,4)][int]$ServerLogicalProcessors = 0,
    [ValidateSet(0,1,2,4)][int]$ServerJvmProcessors = 0,
    [ValidateSet('current','prepared','prefetch')][string]$PipelineProfile = 'prefetch',
    [ValidateSet('relocation','continuous')][string]$Movement = 'relocation',
    [ValidateRange(5,1000)][int]$CorrectnessDemandWaitMs = 1000,
    [ValidateRange(2,32)][int]$ViewDistance = 10,
    [switch]$MeasureFullView,
    [switch]$QuietRemoteTrace,
    [switch]$ServerFlightRecording,
    [switch]$ClientPeerProbe,
    [ValidateRange(1,10)][int]$WarmupRuns=1,
    [ValidateRange(1,10)][int]$MeasuredRepeats=3,
    [ValidateSet('vanilla','local','cooperative')][string]$NoiseBackend='vanilla',
    [ValidateSet('standard','wide','deep')][string]$WindowProfile='standard',
    [ValidateSet('ready','overlap')][string]$RemoteApplicationProfile='ready',
    [ValidateRange(0,64)][int]$PrefetchLookahead=0,
    [ValidateSet('grid','surface','density','block','decisions','complete')][string]$RemoteWorkKind='grid',
    [ValidateSet('server','peer')][string]$CompleteVerification='server',
    [ValidateSet('inline','decoder')][string]$SectionPreparation='inline',
    [ValidateSet('off','serial','parallel')][string]$FeatureBackend='off',
    [switch]$DecorationDigest,
    [switch]$FeatureFixture,
    [string]$FeatureReplayFile,
    [string]$RemoteHost = 'gen1c@100.117.255.71',
    [string]$RemoteRoot = 'C:/Users/gen1c/AppData/Local/Temp/WorldgenAssist-20260909/port26.3'
)

# One isolated trusted-raw scenario.  This is intentionally a runner, not a
# benchmark report: the matrix comparator makes paired conclusions later.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath($OutputRoot)
$testRoot = [IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts')).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$jdk = 'C:\Program Files\Java\jdk-25.0.4'
$apiVersion='0.161.0+26.3'
$apiCandidates=@(Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/$apiVersion") -Recurse -File -Filter "fabric-api-$apiVersion.jar")
if($apiCandidates.Count -ne 1){throw 'Exactly one pinned Fabric API 26.3 JAR is required'}
$api=$apiCandidates[0].FullName
$launcher=Join-Path $workspace 'test-artifacts/installed-client-prep-26.3/fabric-server-mc.26.3-loader.0.19.5-launcher.1.1.2.jar'
$launcherHash='0B56AD54D762172E8B8748E467F584F071E4DEDECD93DC336CF2C68837E790BE'
if(-not(Test-Path -LiteralPath $launcher -PathType Leaf) -or (Get-FileHash -LiteralPath $launcher -Algorithm SHA256).Hash -ne $launcherHash){throw 'Pinned Fabric 26.3 server launcher is missing or mismatched'}
$success = $false; $cleanupSafe = $true; $failure = $null; $case = $null; $remoteDownloaded = $false
$tunnel = $null; $server = $null; $clients = @(); $serverOut = $null; $serverErr = $null; $tunnelOut = $null; $tunnelErr = $null
$predictionEnabled = [bool]::Parse($Prediction)
. (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1')
. (Join-Path $PSScriptRoot 'WorldgenScenarioConsole.ps1')
. (Join-Path $PSScriptRoot 'FeatureFixtureEvidence.ps1')
$replay=$null
if($FeatureFixture -and (-not $DecorationDigest -or $Purpose -ne 'correctness' -or $Dimension -ne 'overworld' -or $Players -ne 2 -or $Seed -ne 8675309 -or $ViewDistance -gt 10)){throw 'Bounded two-owner diagnostic fixture required'}
if($FeatureReplayFile){
    if(-not $FeatureFixture -or -not [IO.Path]::GetFullPath($FeatureReplayFile).StartsWith($testRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Replay requires a fixture evidence child'}
    $replay=Read-FeatureReplay $FeatureReplayFile
}
$measurementRadius = if($MeasureFullView){$ViewDistance}else{4}
if($ClientPeerProbe -and ($Dimension -ne 'overworld' -or $Mode -ne 'assisted' -or $Seed -ne 8675309 -or $RemoteWorkKind -ne 'complete' -or $CompleteVerification -ne 'peer' -or $Purpose -ne 'performance')){throw 'Public peer probe requires only the explicit known Overworld complete/peer performance fixture'}
$measurementShape = if($MeasureFullView){'view'}else{'square'}
$measurementOffsets = @(Get-WorldgenMeasurementOffsets $measurementRadius $measurementShape)
$clientLoad = @()

$legacyServer=$RemoteHost -eq 'gen1c@100.117.255.71' -and $RemoteRoot -eq 'C:/Users/gen1c/AppData/Local/Temp/WorldgenAssist-20260909/port26.3'
$physicalServer=$RemoteHost -eq 'gen1c@100.103.102.109' -and $RemoteRoot -eq 'E:/WorldgenAssist/port26.3'
if(-not($legacyServer -or $physicalServer)){throw 'This fixture requires an explicitly authorized host/root pair'}
$remotePrefix=if($physicalServer){'$env:TEMP="E:/WorldgenAssist/temp";$env:TMP=$env:TEMP;'}else{''}
if (-not $output.StartsWith($testRoot, [StringComparison]::OrdinalIgnoreCase) -or $output -eq $testRoot.TrimEnd([IO.Path]::DirectorySeparatorChar)) { throw 'OutputRoot must be a child of test-artifacts' }
if ($predictionEnabled -and $CacheEntries -eq 0) { throw 'Prediction requires CacheEntries greater than zero' }
if ($Mode -eq 'assisted' -and $ValidationCells -eq 0 -and $Purpose -eq 'correctness') { throw 'Assisted correctness requires authoritative validation cells' }
if ($RemoteApplicationProfile -eq 'overlap' -and $PipelineProfile -ne 'prefetch') { throw 'Overlap measurement requires the bounded prefetch pipeline' }

function Write-Utf8([string]$Path,[string]$Text) { [IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false)) }
function Write-Json([string]$Path,[object]$Value) { Write-Utf8 $Path ($Value | ConvertTo-Json -Depth 12) }
function Get-Manifest {
    $files = @(Get-ChildItem -LiteralPath (Join-Path $workspace 'src') -File -Recurse)
    foreach($relative in @('build.gradle','settings.gradle','gradle.properties','scripts/Get-WorldgenArtifact.ps1','scripts/New-InstalledFixtureClient.ps1','scripts/New-TwoClientFixtureClient.ps1','scripts/Prepare-InstalledFixtureAssets.ps1','scripts/Run-WorldgenScenario.ps1','scripts/Remote-WorldgenScenarioServer.ps1','scripts/WorldgenMeasurementRegion.ps1','scripts/WorldgenScenarioConsole.ps1','scripts/FeatureFixtureEvidence.ps1')) { $files += Get-Item -LiteralPath (Join-Path $workspace $relative) }
    return @($files | Sort-Object FullName | ForEach-Object { $relative=$_.FullName.Substring($workspace.Length+1).Replace('\','/'); "$(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256 | Select-Object -ExpandProperty Hash)  $relative" })
}
function Save-Manifest([string]$Name) {
    $lines=Get-Manifest; $path=Join-Path $output $Name; Write-Utf8 $path ($lines -join [Environment]::NewLine)
    return ([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))) | ForEach-Object { $_.ToString('x2') }) -join ''
}
function Start-Owned([string]$File,[string[]]$Arguments,[hashtable]$Environment=@{},[string]$WorkingDirectory=$workspace) {
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$File;foreach($argument in $Arguments){[void]$info.ArgumentList.Add($argument)};$info.WorkingDirectory=$WorkingDirectory;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($key in $Environment.Keys){$info.Environment[$key]=$Environment[$key]}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info;if(-not $process.Start()){throw "Could not start owned process: $File"};return $process
}
function Stop-Owned([Diagnostics.Process]$Process) {
    if($null -eq $Process -or $Process.HasExited){return $true}
    try { & "$env:SystemRoot\System32\taskkill.exe" '/PID' $Process.Id '/T' '/F' | Out-Null; $Process.WaitForExit(30000)|Out-Null; return $Process.HasExited } catch { return $false }
}
function Invoke-Remote([string]$Code) {
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($remotePrefix+$Code)); & ssh.exe -o BatchMode=yes -o ConnectTimeout=15 $RemoteHost "powershell -NoProfile -NonInteractive -EncodedCommand $encoded"
    if($LASTEXITCODE -ne 0){throw 'Remote preparation command failed'}
}
function Receive-RemoteEvidence {
    if([string]::IsNullOrWhiteSpace($case)){return $false}
    $destination=Join-Path $output 'remote-evidence'; & scp.exe -q -r ($RemoteHost + ':' + $RemoteRoot + '/evidence/' + $case) $destination
    return $LASTEXITCODE -eq 0
}
function Start-Client([string]$Name,[string]$Uuid,[int]$Index,[string]$AssetsRoot) {
    $profile=Join-Path $output ('clients/owner-'+$Index)
    $launch=& (Join-Path $PSScriptRoot 'New-TwoClientFixtureClient.ps1') -Username $Name -Uuid $Uuid -Root $profile -AssetsRoot $AssetsRoot
    # Isolated stationary workloads must not consume keyboard/mouse gameplay
    # input from the desktop running the automated clients. Only these newly
    # created evidence profiles are changed; server commands still move owners.
    # Minecraft 26.3 version.json world_version=5023. Without the version line,
    # Options.dataFix assumes version 0 and parses modern key names as integers.
    Add-Content -LiteralPath (Join-Path $profile 'client/options.txt') -Value 'version:5023'
    $optionPath=Join-Path $profile 'client/options.txt'
    $optionLines=@(Get-Content -LiteralPath $optionPath | Where-Object {$_ -notmatch '^(renderDistance|graphicsPreset):'})
    $optionLines += 'renderDistance:'+$ViewDistance
    # Options.processOptions reads the preset after individual settings; FANCY
    # reapplies distance 16 on load. CUSTOM preserves the requested distance.
    $optionLines += 'graphicsPreset:"custom"'
    $optionLines | Set-Content -LiteralPath $optionPath
    if($MeasureFullView){
        $argumentPath=$launch.Arguments[0].Substring(1)
        @(Get-Content -LiteralPath $argumentPath | ForEach-Object {if($_ -eq '-Xmx2G'){'-Xmx4G'}else{$_}}) | Set-Content -LiteralPath $argumentPath
    }
    if($RemoteWorkKind -eq 'complete'){
        $argumentPath=$launch.Arguments[0].Substring(1)
        @('-Dworldgen_assist.client.worker_threads=4')+@(Get-Content -LiteralPath $argumentPath) | Set-Content -LiteralPath $argumentPath
    }
    if($ClientPeerProbe){
        $argumentPath=$launch.Arguments[0].Substring(1)
        @('-Dworldgen_assist.client.peer_probe=true')+@(Get-Content -LiteralPath $argumentPath) | Set-Content -LiteralPath $argumentPath
    }
    @('forward','back','left','right','jump','sneak','sprint','attack','use') |
        ForEach-Object { 'key_key.' + $_ + ':key.keyboard.unknown' } |
        Add-Content -LiteralPath (Join-Path $profile 'client/options.txt')
    $environment=@{JAVA_HOME=$jdk;Path="$jdk\bin;$env:Path";WORLDGEN_ASSIST_REMOTE=if($Mode -eq 'assisted'){'true'}else{'false'};WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_PUBLIC_FIXTURE='false';WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_FIXTURE_FAULT='none';WORLDGEN_ASSIST_CLIENT_MEASURE_RECEIPT=if($Purpose -eq 'performance'){'true'}else{'false'}}
    $environment['WORLDGEN_ASSIST_CLIENT_REUSE_CONTEXT'] = if($PipelineProfile -eq 'current'){'false'}else{'true'}
    $environment['WORLDGEN_ASSIST_CLIENT_JOB_WINDOW'] = switch($WindowProfile){'deep'{'32'} 'wide'{'16'} default{'4'}}
    $process=Start-Owned $launch.Executable $launch.Arguments $environment $launch.WorkingDirectory
    $capture=$null
    try {
        $capture=Start-WorldgenConsoleCapture $process (Join-Path $output ($Name+'-stdout.log'))
        return [pscustomobject]@{name=$Name;profile=$profile;process=$process;stdout=$capture;stderr=$process.StandardError.ReadToEndAsync();launch=$launch}
    } catch {
        if(-not(Stop-Owned $process)){$script:cleanupSafe=$false}
        if($process.HasExited -and $null -ne $capture){Complete-WorldgenConsoleCapture $capture}
        $process.Dispose();throw
    }
}
function Close-Client([object]$Client) {
    if($Client.process.HasExited){return $Client.process.ExitCode -eq 0}
    if(-not $Client.process.CloseMainWindow()){return $false}
    if(-not $Client.process.WaitForExit(30000)){return $false}
    return $Client.process.ExitCode -eq 0
}
function Save-ClientFailureDiagnostic([object]$Client) {
    if($Client.process.HasExited){return}
    $dump=$null
    try {
        $dump=Start-Owned "$jdk\bin\java.exe" @('-m','jdk.jcmd/sun.tools.jcmd.JCmd',[string]$Client.process.Id,'Thread.print','-l')
        $dumpHandle=$dump.Handle
        $dumpOut=$dump.StandardOutput.ReadToEndAsync();$dumpErr=$dump.StandardError.ReadToEndAsync()
        if(-not $dump.WaitForExit(10000)){[void](Stop-Owned $dump)}
        Write-Utf8 (Join-Path $output ($Client.name+'-failure-threads.txt')) ($dumpOut.GetAwaiter().GetResult())
        Write-Utf8 (Join-Path $output ($Client.name+'-failure-threads-error.txt')) ($dumpErr.GetAwaiter().GetResult())
    } catch { Write-Utf8 (Join-Path $output ($Client.name+'-diagnostic-error.txt')) $_.Exception.ToString() }
    finally {if($null -ne $dump){$dump.Dispose()}}
}

try {
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    foreach($target in @($output,(Join-Path $output 'remote-evidence'))){if((Test-Path -LiteralPath $target) -and ((Get-Item -LiteralPath $target -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Output target must not be a reparse point: $target"}}
    $manifestBefore=Save-Manifest 'source-manifest-before.sha256'
    $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Workspace $workspace
    if($artifact.Minecraft -ne '26.3' -or -not(Test-Path -LiteralPath $artifact.Path -PathType Leaf)){throw "Exact 26.3 artifact is missing: $($artifact.Path)"}
    $artifactHash=(Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash.ToUpperInvariant()
    if(-not(Test-Path -LiteralPath "$jdk\bin\java.exe")){throw 'Pinned JDK 25.0.4 is missing'};if(-not(Test-Path -LiteralPath $api)){throw 'Pinned Fabric API cache entry is missing'}
    Write-Json (Join-Path $output 'scenario-input.json') ([ordered]@{schema='worldgen-assist.scenario-input.v1';dimension=$Dimension;mode=$Mode;players=$Players;purpose=$Purpose;cache_entries=$CacheEntries;prediction=$predictionEnabled;validation_cells=$ValidationCells;seed=$Seed;server_logical_processors=$ServerLogicalProcessors;server_jvm_processors=$ServerJvmProcessors;pipeline_profile=$PipelineProfile;movement=$Movement;artifact_path=$artifact.Path;artifact_sha256=$artifactHash;remote_host=$RemoteHost;remote_root=$RemoteRoot;loopback_listener='127.0.0.1:25585'})
    $assets=& (Join-Path $PSScriptRoot 'Prepare-InstalledFixtureAssets.ps1');if([string]::IsNullOrWhiteSpace([string]$assets) -or -not(Test-Path -LiteralPath $assets -PathType Container)){throw 'Installed asset preparation did not return a verified root'};[IO.Path]::GetFullPath($assets)|Set-Content -LiteralPath (Join-Path $output 'assets-root.txt')
    Invoke-Remote ('New-Item -ItemType Directory -Force -Path "'+$RemoteRoot+'/mods","'+$RemoteRoot+'/retired-mods","'+$RemoteRoot+'/scenario-staging" | Out-Null')
    & scp.exe -q $artifact.Path $api $launcher (Join-Path $workspace 'run/eula.txt') (Join-Path $PSScriptRoot 'Remote-WorldgenScenarioServer.ps1') (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1') (Join-Path $PSScriptRoot 'WorldgenScenarioConsole.ps1') ($RemoteHost + ':' + $RemoteRoot + '/scenario-staging/')
    if($LASTEXITCODE -ne 0){throw 'Remote scenario file transfer failed'}
    if($replay){
        Copy-Item -LiteralPath $replay.path -Destination (Join-Path $output 'feature-replay.json')
        & scp.exe -q $replay.path ($RemoteHost+':'+$RemoteRoot+'/scenario-staging/feature-replay.json')
        if($LASTEXITCODE -ne 0){throw 'Replay transfer failed'}
        Invoke-Remote ('if((Get-FileHash -LiteralPath "'+$RemoteRoot+'/scenario-staging/feature-replay.json").Hash.ToLowerInvariant() -ne "'+$replay.sha256+'"){throw "Replay hash differs"}')
    }
    if($ServerFlightRecording){
        $jfc=Join-Path $jdk 'lib/jfr/profile.jfc'
        if(-not(Test-Path -LiteralPath $jfc)){throw 'Pinned JDK profile.jfc is missing'}
        & scp.exe -q $jfc ($RemoteHost+':'+$RemoteRoot+'/scenario-staging/')
        if($LASTEXITCODE -ne 0){throw 'JFR settings transfer failed'}
        $jfcHash=(Get-FileHash -LiteralPath $jfc).Hash
        Invoke-Remote ('if((Get-FileHash -LiteralPath "'+$RemoteRoot+'/scenario-staging/profile.jfc").Hash -ne "'+$jfcHash+'"){throw "JFR settings hash mismatch"}')
    }
    Invoke-Remote ('$root="'+$RemoteRoot+'";$expected="'+$artifactHash+'";$launcherHash="'+$launcherHash+'";$name="'+$artifact.FileName+'";$stage=Join-Path $root ("scenario-staging/"+$name);if((Get-FileHash -LiteralPath $stage -Algorithm SHA256).Hash -ne $expected){throw "Staged artifact SHA-256 mismatch"};$stamp=Get-Date -Format "yyyyMMdd-HHmmss-fff";foreach($old in @(Get-ChildItem -LiteralPath (Join-Path $root "mods") -Filter "worldgen-assist-*.jar" -File)){if($old.Name -ne $name -or (Get-FileHash -LiteralPath $old.FullName -Algorithm SHA256).Hash -ne $expected){Move-Item -LiteralPath $old.FullName -Destination (Join-Path $root ("retired-mods/"+$stamp+"-"+$old.Name))}};Copy-Item -LiteralPath $stage -Destination (Join-Path $root ("mods/"+$name)) -Force;Copy-Item -LiteralPath (Join-Path $root "scenario-staging/fabric-api-0.161.0+26.3.jar") -Destination (Join-Path $root "mods/fabric-api-0.161.0+26.3.jar") -Force;Copy-Item -LiteralPath (Join-Path $root "scenario-staging/fabric-server-mc.26.3-loader.0.19.5-launcher.1.1.2.jar") -Destination (Join-Path $root "fabric-server-launch.jar") -Force;Copy-Item -LiteralPath (Join-Path $root "scenario-staging/eula.txt") -Destination (Join-Path $root "eula.txt") -Force;Copy-Item -LiteralPath (Join-Path $root "scenario-staging/Remote-WorldgenScenarioServer.ps1") -Destination (Join-Path $root "Remote-WorldgenScenarioServer.ps1") -Force;if((Get-FileHash -LiteralPath (Join-Path $root ("mods/"+$name)) -Algorithm SHA256).Hash -ne $expected -or (Get-FileHash -LiteralPath (Join-Path $root "fabric-server-launch.jar") -Algorithm SHA256).Hash -ne $launcherHash){throw "Installed artifact or launcher SHA-256 mismatch"}')
    $tunnel=Start-Owned 'ssh.exe' @('-N','-o','BatchMode=yes','-o','ExitOnForwardFailure=yes','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=2','-L','127.0.0.1:25585:127.0.0.1:25585',$RemoteHost);$tunnelOut=$tunnel.StandardOutput.ReadToEndAsync();$tunnelErr=$tunnel.StandardError.ReadToEndAsync()
    $remoteCommand='& "'+$RemoteRoot+'/Remote-WorldgenScenarioServer.ps1" -Root "'+$RemoteRoot+'" -Dimension '+$Dimension+' -Mode '+$Mode+' -Players '+$Players+' -Purpose '+$Purpose+' -CacheEntries '+$CacheEntries+' -Prediction '+$predictionEnabled.ToString().ToLowerInvariant()+' -ValidationCells '+$ValidationCells+' -Seed '+$Seed+' -ServerLogicalProcessors '+$ServerLogicalProcessors+' -PipelineProfile '+$PipelineProfile+' -Movement '+$Movement
    $remoteCommand += ' -CorrectnessDemandWaitMs '+$CorrectnessDemandWaitMs
    $remoteCommand += ' -ServerJvmProcessors '+$ServerJvmProcessors
    Invoke-Remote ('Copy-Item -LiteralPath "'+$RemoteRoot+'/scenario-staging/WorldgenMeasurementRegion.ps1" -Destination "'+$RemoteRoot+'/WorldgenMeasurementRegion.ps1" -Force')
    Invoke-Remote ('Copy-Item -LiteralPath "'+$RemoteRoot+'/scenario-staging/WorldgenScenarioConsole.ps1" -Destination "'+$RemoteRoot+'/WorldgenScenarioConsole.ps1" -Force')
    $remoteCommand += ' -ViewDistance '+$ViewDistance
    if($MeasureFullView){$remoteCommand += ' -MeasureFullView'}
    if($QuietRemoteTrace){$remoteCommand += ' -QuietRemoteTrace'}
    if($ServerFlightRecording){$remoteCommand += ' -ServerFlightRecording'}
    $remoteCommand += ' -WarmupRuns '+$WarmupRuns+' -MeasuredRepeats '+$MeasuredRepeats
    $remoteCommand += ' -NoiseBackend '+$NoiseBackend
    $remoteCommand += ' -WindowProfile '+$WindowProfile
    $remoteCommand += ' -RemoteApplicationProfile '+$RemoteApplicationProfile
    $remoteCommand += ' -PrefetchLookahead '+$PrefetchLookahead
    $remoteCommand += ' -RemoteWorkKind '+$RemoteWorkKind
    $remoteCommand += ' -CompleteVerification '+$CompleteVerification
    $remoteCommand += ' -SectionPreparation '+$SectionPreparation
    $remoteCommand += ' -FeatureBackend '+$FeatureBackend
    if($DecorationDigest){$remoteCommand += ' -DecorationDigest'}
    if($FeatureFixture){$remoteCommand += ' -FeatureFixture'}
    if($replay){$remoteCommand += ' -FeatureReplaySha256 '+$replay.sha256}
    # Process-scoped policy for our transferred helper; no machine/user policy change.
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($remotePrefix+$remoteCommand));$server=Start-Owned 'ssh.exe' @('-o','BatchMode=yes','-o','ConnectTimeout=15',$RemoteHost,"powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -OutputFormat Text -EncodedCommand $encoded");$serverErr=$server.StandardError.ReadToEndAsync()
    $ready=[DateTime]::UtcNow.AddSeconds(240)
    $serverReady=$false
    $lineTask=$server.StandardOutput.ReadLineAsync()
    while(-not $serverReady){
        if($tunnel.HasExited){throw 'SSH tunnel exited before server readiness'}
        if([DateTime]::UtcNow -gt $ready){throw 'Remote server readiness timed out'}
        if($lineTask.IsCompleted){
            $line=$lineTask.GetAwaiter().GetResult()
            if($null -eq $line){throw 'Remote helper ended before readiness'}
            $line|Add-Content -LiteralPath (Join-Path $output 'remote-runner.log')
            if($line -match '^SERVER_CASE (\S+)$'){$case=$Matches[1]}
            if($line -match '^SERVER_READY (\S+)$'){$case=$Matches[1];$serverReady=$true}
            else{$lineTask=$server.StandardOutput.ReadLineAsync()}
        }
        Start-Sleep -Milliseconds 200
    }
    $lineTask=$server.StandardOutput.ReadLineAsync()
    for($index=0;$index -lt $Players;$index++){$name=@('ScenarioOwnerA','ScenarioOwnerB')[$index];$uuid=if($index -eq 0){'000000000000000000000000000000a1'}else{'000000000000000000000000000000b2'};$clients += Start-Client $name $uuid $index $assets}
    $scenarioSeconds=if($MeasureFullView){3600}elseif($Purpose -eq 'performance'){900}else{420}
    $deadline=[DateTime]::UtcNow.AddSeconds($scenarioSeconds)
    $receiptPending=$null
    while(-not $server.HasExited){
        if($tunnel.HasExited){throw 'SSH tunnel exited during scenario'}
        foreach($client in $clients){if($client.process.HasExited){throw "Client exited during scenario: $($client.name) exit_code=$($client.process.ExitCode)"}}
        if([DateTime]::UtcNow -gt $deadline){throw 'Scenario exceeded its bounded deadline'}
        if($lineTask.IsCompleted){
            $line=$lineTask.GetAwaiter().GetResult()
            if($null -ne $line){
                $line|Add-Content -LiteralPath (Join-Path $output 'remote-runner.log')
                if($line -match '^SERVER_RECEIPT_WAIT (\d+) (\d+)$'){$receiptPending=@{repeat=[int]$Matches[1];location=[int]$Matches[2]}}
                $lineTask=$server.StandardOutput.ReadLineAsync()
            }
        }
        if($null -ne $receiptPending){
            $allReceived=$true
            for($owner=0;$owner -lt $Players;$owner++){
                $cx=1000+$receiptPending.location*256;$cz=-2000-$receiptPending.location*256
                if($owner -eq 1){$cx=-$cx;$cz=-$cz}
                $expected=@{};foreach($offset in $measurementOffsets){$expected[([string]($cx+$offset.x)+','+($cz+$offset.z))]=$true}
                $clientText=Read-WorldgenConsoleText $clients[$owner].stdout
                foreach($chunk in [regex]::Matches($clientText,('benchmark\.chunk_received repeat='+$receiptPending.repeat+' chunk=(?<chunk>-?\d+,-?\d+)\b'))){[void]$expected.Remove($chunk.Groups['chunk'].Value)}
                if($expected.Count -gt 0){$allReceived=$false}
            }
            if($allReceived){
                Invoke-Remote ('Set-Content -LiteralPath "'+$RemoteRoot+'/evidence/'+$case+'/receipt-'+$receiptPending.repeat+'.ack" -Value "complete"')
                $receiptPending=$null
            }
        }
        Start-Sleep -Milliseconds 1000
    }
    if($lineTask.IsCompleted){$lineTask.GetAwaiter().GetResult()|Add-Content -LiteralPath (Join-Path $output 'remote-runner.log')}
    $remoteDownloaded=Receive-RemoteEvidence;if(-not $remoteDownloaded){throw 'Remote evidence download failed'};if($server.ExitCode -ne 0){throw 'Remote scenario helper failed'}
    $remote=Get-Content -LiteralPath (Join-Path $output 'remote-evidence/remote-result.json') -Raw | ConvertFrom-Json;if(-not $remote.success -or -not $remote.cleanup_safe -or -not $remote.loopback_only){throw 'Remote result did not prove success, cleanup, and loopback binding'}
    $serverLog=Get-Content -LiteralPath (Join-Path $output 'remote-evidence/latest.log') -Raw;if($serverLog -match 'Mixin apply failed|Encountered an unexpected exception'){throw 'Server log contains a runtime error'};if($Mode -eq 'vanilla' -and $serverLog -match '\[CAWG\] job\.sent '){throw 'Vanilla scenario dispatched a remote job'};if($Purpose -eq 'performance' -and $serverLog -match '\[CAWG\] stage\.digest_enabled'){throw 'Performance scenario enabled digest logging'}
    foreach($client in $clients){
        $client.process.Refresh()
        $wallMs=([DateTime]::UtcNow-$client.process.StartTime.ToUniversalTime()).TotalMilliseconds
        $cpuMs=$client.process.TotalProcessorTime.TotalMilliseconds
        $clientLoad += [ordered]@{owner=$client.name;cpu_ms=$cpuMs;wall_ms=$wallMs;average_cpu_cores=$cpuMs/$wallMs;peak_working_set_bytes=$client.process.PeakWorkingSet64;window='process_start_through_server_completion_includes_join_and_warmup'}
    }
    foreach($client in $clients){
        if(-not(Close-Client $client)){throw "Client did not exit naturally: $($client.name)"}
        $clientLog=Join-Path $client.profile 'client/logs/latest.log'
        Save-WorldgenConsoleEvidence $client.stdout $clientLog $clientLog
        if((Test-Path -LiteralPath $clientLog) -and ((Get-Content -LiteralPath $clientLog -Raw) -match 'Mixin apply failed|Encountered an unexpected exception|Failed to load options')){throw "Client runtime error: $($client.name)"}
    }
    $manifestAfter=Save-Manifest 'source-manifest-after.sha256';if($manifestBefore -ne $manifestAfter){throw 'Source or harness manifest changed during the scenario'}
    $success=$true
} catch { $failure=$_.Exception.Message; $success=$false } finally {
    if(-not $success){foreach($client in $clients){Save-ClientFailureDiagnostic $client}}
    if(-not $success -and $null -ne $server){
        try {
            Invoke-Remote ('$root=[IO.Path]::GetFullPath("'+$RemoteRoot+'");$launcher=Join-Path $root "fabric-server-launch.jar";foreach($process in @(Get-CimInstance Win32_Process -Filter "name=''java.exe''" | Where-Object {$_.CommandLine -and $_.CommandLine.IndexOf($launcher,[StringComparison]::OrdinalIgnoreCase) -ge 0})){Stop-Process -Id $process.ProcessId -Force}')
            if(-not $server.HasExited){[void]$server.WaitForExit(30000)}
        } catch { $cleanupSafe=$false;Write-Utf8 (Join-Path $output 'remote-cleanup-error.txt') $_.Exception.ToString() }
    }
    if(-not $remoteDownloaded -and -not [string]::IsNullOrWhiteSpace($case)){try{$remoteDownloaded=Receive-RemoteEvidence}catch{Write-Utf8 (Join-Path $output 'remote-evidence-download-error.txt') $_.Exception.ToString()}}
    foreach($client in $clients){if(-not $client.process.HasExited){$cleanupSafe=(Stop-Owned $client.process) -and $cleanupSafe};$clientLog=Join-Path $client.profile 'client/logs/latest.log';Save-WorldgenConsoleEvidence $client.stdout $clientLog $clientLog;$client.stderr.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $output ($client.name+'-stderr.log'));$client.process.Dispose()}
    foreach($owned in @($server,$tunnel)){if($null -ne $owned -and -not $owned.HasExited){$cleanupSafe=(Stop-Owned $owned) -and $cleanupSafe}}
    if($null -ne $server -and $null -ne $serverErr){$serverErr.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $output 'remote-stderr.log');$server.Dispose()};if($null -ne $tunnel){if($null -ne $tunnelOut){$tunnelOut.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $output 'tunnel-stdout.log');$tunnelErr.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $output 'tunnel-stderr.log')};$tunnel.Dispose()}
    $remoteResultPath=Join-Path $output 'remote-evidence/remote-result.json';if(Test-Path -LiteralPath $remoteResultPath){try{$remoteResult=Get-Content -LiteralPath $remoteResultPath -Raw|ConvertFrom-Json;$cleanupSafe=$cleanupSafe -and [bool]$remoteResult.cleanup_safe}catch{$cleanupSafe=$false}}else{$cleanupSafe=$false}
    $beforePath=Join-Path $output 'source-manifest-before.sha256';$afterPath=Join-Path $output 'source-manifest-after.sha256';if(-not(Test-Path -LiteralPath $afterPath)){try{$manifestAfter=Save-Manifest 'source-manifest-after.sha256'}catch{$cleanupSafe=$false}}
    if(Test-Path -LiteralPath $beforePath){$manifestBefore=([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes((Get-Content -LiteralPath $beforePath -Raw).TrimEnd("`r","`n")))|ForEach-Object{$_.ToString('x2')})-join''};if(Test-Path -LiteralPath $afterPath){$manifestAfter=([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes((Get-Content -LiteralPath $afterPath -Raw).TrimEnd("`r","`n")))|ForEach-Object{$_.ToString('x2')})-join''}
    $result=[ordered]@{schema='worldgen-assist.scenario-result.v1';success=$success;cleanup_safe=$cleanupSafe;loopback_only=$true;artifact_sha256=if($null -ne $artifactHash){$artifactHash}else{$null};dimension=$Dimension;mode=$Mode;players=$Players;purpose=$Purpose;cache_entries=$CacheEntries;prediction=$predictionEnabled;validation_cells=$ValidationCells;server_logical_processors=$ServerLogicalProcessors;server_jvm_processors=$ServerJvmProcessors;pipeline_profile=$PipelineProfile;movement=$Movement;source_manifest_sha256=$manifestBefore;source_manifest_before_sha256=$manifestBefore;source_manifest_after_sha256=$manifestAfter;failure=$failure}
    $result.demand_wait_ms = if($Purpose -eq 'correctness'){$CorrectnessDemandWaitMs}else{100}
    $result.remote_application_profile = $RemoteApplicationProfile
    $result.prefetch_lookahead = $PrefetchLookahead
    $result.remote_work_kind = $RemoteWorkKind
    $result.complete_verification = $CompleteVerification
    $result.section_preparation = $SectionPreparation
    $result.feature_backend = $FeatureBackend
    $result.decoration_digest = [bool]$DecorationDigest
    $result.feature_fixture = [bool]$FeatureFixture
    $result.feature_replay_sha256 = if($replay){$replay.sha256}else{'record'}
    $result.client_worker_threads = if($RemoteWorkKind -eq 'complete'){4}else{2}
    $result.client_peer_probe = [bool]$ClientPeerProbe
    if($Purpose -eq 'correctness'){$path=Join-Path $output 'remote-evidence/correctness.json';$result.correctness=if(Test-Path -LiteralPath $path){Get-Content -LiteralPath $path -Raw|ConvertFrom-Json}else{[ordered]@{required_applied_chunks=@();noise_digests=@()}}}else{$path=Join-Path $output 'remote-evidence/performance.json';$result.performance=if(Test-Path -LiteralPath $path){Get-Content -LiteralPath $path -Raw|ConvertFrom-Json}else{[ordered]@{warmup_runs=1;measured_repeats=3;measured=@()}}}
    $result.scenario_client_load=$clientLoad
    Write-Json (Join-Path $output 'scenario-result.json') $result
}
if($null -ne $failure){throw $failure}
