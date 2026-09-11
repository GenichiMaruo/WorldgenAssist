param([ValidateSet('assisted','vanilla','link-drop')][string]$Mode='assisted', [ValidateSet('dev','installed')][string]$ClientRuntime='dev')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if ($Mode -eq 'link-drop' -and $ClientRuntime -ne 'dev') { throw 'Link-drop withholding requires the development-only fault injector' }
$workspace=Split-Path -Parent $PSScriptRoot
$remoteHost='gen1c@100.117.255.71'
$remoteRoot='C:/Users/gen1c/AppData/Local/Temp/WorldgenAssist-20260909'
$runtimeLabel=if($ClientRuntime -eq 'installed'){'installed-'}else{''}
$root=Join-Path $workspace ('test-artifacts/multipc-'+$runtimeLabel+$Mode+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path (Join-Path $root 'client'),(Join-Path $root 'server/logs') | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $root 'runner.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Remote-FixtureServer.ps1') -Destination $root
if ($ClientRuntime -eq 'installed') { Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'New-InstalledFixtureClient.ps1') -Destination $root }
if (Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue) { throw 'Local tunnel port is occupied' }
if ((Get-Content -LiteralPath (Join-Path $workspace 'run/eula.txt') -Raw) -notmatch '(?m)^eula=true\s*$') { throw 'User EULA acceptance required' }
@('onboardAccessibility:false','skipMultiplayerWarning:true','tutorialStep:none','renderDistance:10','maxFps:30','enableVsync:false','soundCategory_master:0.0') |
    Set-Content -LiteralPath (Join-Path $root 'client/options.txt')
Get-ChildItem -LiteralPath (Join-Path $workspace 'src') -File -Recurse | Sort-Object FullName | ForEach-Object {
    "$( (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash )  $($_.FullName.Substring($workspace.Length+1))"
} | Set-Content -LiteralPath (Join-Path $root 'source-sha256.txt')
$api=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/0.156.0+26.2/d96e0d9ef8ea3604fac4ca7495d7c6148f3ac816/fabric-api-0.156.0+26.2.jar'
$artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Workspace $workspace
$mod=$artifact.Path
function Remote-Code([string]$Code) {
    $Code='$ErrorActionPreference="Stop"; $ProgressPreference="SilentlyContinue"; '+$Code
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Code))
    & ssh -o BatchMode=yes -o ConnectTimeout=10 $remoteHost "powershell -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand $encoded"
    if ($LASTEXITCODE -ne 0) { throw 'SSH command failed' }
}
function Start-Owned([string]$Executable,[string[]]$Arguments,[hashtable]$Environment=@{},[string]$WorkingDirectory=$workspace) {
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Executable
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $info.WorkingDirectory=$WorkingDirectory
    $info.UseShellExecute=$false
    $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true
    $info.RedirectStandardError=$true
    foreach ($key in $Environment.Keys) { $info.Environment[$key]=$Environment[$key] }
    $process=[Diagnostics.Process]::new()
    $process.StartInfo=$info
    if (-not $process.Start()) { throw 'Failed to start owned process' }
    return $process
}
$tunnel=$null; $server=$null; $client=$null; $case=$null; $success=$false
try {
    Write-Output "evidence=$root"
    Remote-Code ('New-Item -ItemType Directory -Force -Path "'+$remoteRoot+'/mods" | Out-Null')
    Remote-Code ('$old=@(Get-ChildItem -LiteralPath "'+$remoteRoot+'/mods" -Filter "worldgen-assist-*.jar" -File | Where-Object { $_.Name -ne "'+$artifact.FileName+'" }); if ($old.Count -gt 0) { throw "Archive the previous fixture mod outside mods before testing a new version; no automatic deletion performed" }')
    & scp -q $mod $api ($remoteHost+':'+$remoteRoot+'/mods/')
    if ($LASTEXITCODE -ne 0) { throw 'Mod transfer failed' }
    & scp -q (Join-Path $workspace 'run/eula.txt') (Join-Path $PSScriptRoot 'Remote-FixtureServer.ps1') ($remoteHost+':'+$remoteRoot+'/')
    if ($LASTEXITCODE -ne 0) { throw 'Runner transfer failed' }
    $hash=(Get-FileHash -LiteralPath $mod -Algorithm SHA256).Hash
    Remote-Code ('$expected="'+$hash+'"; if ((Get-FileHash -LiteralPath "'+$remoteRoot+'/mods/'+$artifact.FileName+'" -Algorithm SHA256).Hash -ne $expected) { throw "JAR hash mismatch" }; "REMOTE_JAR_SHA256="+$expected') |
        Tee-Object -FilePath (Join-Path $root 'jar-verification.txt')
    $tunnel=Start-Owned 'ssh.exe' @('-N','-o','BatchMode=yes','-o','ExitOnForwardFailure=yes','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=2','-L','127.0.0.1:25585:127.0.0.1:25585',$remoteHost)
    $tunnelOut=$tunnel.StandardOutput.ReadToEndAsync(); $tunnelErr=$tunnel.StandardError.ReadToEndAsync()
    $code='& "'+$remoteRoot+'/Remote-FixtureServer.ps1" -Root "'+$remoteRoot+'" -Mode '+$Mode
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
    $server=Start-Owned 'ssh.exe' @('-o','BatchMode=yes','-o','ConnectTimeout=10',$remoteHost,"powershell -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand $encoded")
    $serverErr=$server.StandardError.ReadToEndAsync()
    $deadline=[DateTime]::UtcNow.AddSeconds(240)
    $lineTask=$server.StandardOutput.ReadLineAsync()
    while ($null -eq $case) {
        if ($tunnel.HasExited) { throw 'SSH tunnel exited' }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Remote readiness timeout' }
        if ($lineTask.IsCompleted) {
            $line=$lineTask.GetAwaiter().GetResult()
            if ($null -eq $line) { throw 'Remote runner ended before ready' }
            $line | Add-Content -LiteralPath (Join-Path $root 'remote-runner.log')
            if ($line -match '^SERVER_READY (\S+)$') { $case=$Matches[1]; break }
            $lineTask=$server.StandardOutput.ReadLineAsync()
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Output "remote=ready case=$case; starting local client"
    $serverOut=$server.StandardOutput.ReadToEndAsync()
    $jdk='C:\Program Files\Java\jdk-25.0.4'
    $envVars=@{JAVA_HOME=$jdk;Path="$jdk\bin;$env:Path";WORLDGEN_ASSIST_REMOTE='false';WORLDGEN_ASSIST_SEEDED_LEAF_PUBLIC_FIXTURE='false';WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_PUBLIC_FIXTURE=($(if($Mode -ne 'vanilla'){'true'}else{'false'}));WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_FIXTURE_FAULT=($(if($Mode -eq 'link-drop'){'timeout'}else{'none'}))}
    if ($ClientRuntime -eq 'installed') {
        $installed=& (Join-Path $PSScriptRoot 'New-InstalledFixtureClient.ps1') -Root $root
        $client=Start-Owned $installed.Executable $installed.Arguments $envVars $installed.WorkingDirectory
    } else {
        $client=Start-Owned "$env:SystemRoot\System32\cmd.exe" @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'runFixtureClient',"-PfixtureRunRoot=$root",'--no-daemon','--console=plain') $envVars
    }
    $clientOut=$client.StandardOutput.ReadToEndAsync(); $clientErr=$client.StandardError.ReadToEndAsync()
    if ($Mode -eq 'link-drop') {
        $heldDeadline=[DateTime]::UtcNow.AddSeconds(180); $dropAfter=$null; $heldId=$null
        while ($null -eq $heldId) {
            if ($client.HasExited -or $server.HasExited -or $tunnel.HasExited) { throw 'Process exited before held-link-drop probe' }
            if ([DateTime]::UtcNow -ge $heldDeadline) { throw 'No pending held job before link-drop deadline' }
            $clientLogPath=Join-Path $root 'client/logs/latest.log'
            $clientText=if(Test-Path -LiteralPath $clientLogPath){Get-Content -LiteralPath $clientLogPath -Raw}else{''}
            if ($null -eq $dropAfter -and $clientText -match 'seeded_fixture.client_handshake accepted=true') { $dropAfter=[DateTime]::UtcNow.AddSeconds(7) }
            if ($null -ne $dropAfter -and [DateTime]::UtcNow -ge $dropAfter) {
                $heldMatches=[regex]::Matches($clientText,'seeded_fixture.client_fault_held id=(?<id>\S+) mode=timeout')
                if ($heldMatches.Count -gt 0) {
                    $candidate=$heldMatches[$heldMatches.Count-1]; $candidateId=$candidate.Groups['id'].Value
                    if ($clientText.Substring($candidate.Index) -notmatch ('client_cancelled id='+[regex]::Escape($candidateId))) { $heldId=$candidateId }
                }
            }
            if ($null -eq $heldId) { Start-Sleep -Milliseconds 100 }
        }
        [ordered]@{id=$heldId;observed=(Get-Date -Format o);action='terminate only owned SSH forwarding process'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'held-link-drop.json')
        $tunnel.Kill(); $tunnel.WaitForExit()
    }
    $deadline=[DateTime]::UtcNow.AddSeconds(300)
    while (-not $server.HasExited) {
        if ($client.HasExited -or ($Mode -ne 'link-drop' -and $tunnel.HasExited)) { throw 'Client or tunnel exited early' }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Remote fixture exceeded deadline' }
        Start-Sleep -Milliseconds 500
    }
    $serverOut.GetAwaiter().GetResult() | Add-Content -LiteralPath (Join-Path $root 'remote-runner.log')
    if ($server.ExitCode -ne 0) { throw 'Remote fixture failed' }
    & scp -q -r ($remoteHost+':'+$remoteRoot+'/evidence/'+$case) (Join-Path $root 'remote-evidence')
    if ($LASTEXITCODE -ne 0) { throw 'Evidence download failed' }
    Copy-Item -LiteralPath (Join-Path $root 'remote-evidence/latest.log') -Destination (Join-Path $root 'server/logs/latest.log')
    if ((Get-Content -LiteralPath (Join-Path $root 'remote-evidence/summary.txt') -Raw) -notmatch 'success=True') { throw 'Remote success not recorded' }
    if ($Mode -eq 'link-drop') {
        $serverText=Get-Content -LiteralPath (Join-Path $root 'server/logs/latest.log') -Raw
        $sent=[regex]::Match($serverText,('seeded_fixture.sent id='+[regex]::Escape($heldId)+' chunk=(?<chunk>-?\d+,-?\d+)'))
        if (-not $sent.Success -or $serverText.Substring($sent.Index) -notmatch ('seeded_fixture.result chunk='+[regex]::Escape($sent.Groups['chunk'].Value)+' status=DISCONNECTED')) { throw 'Exact held link-drop job did not become DISCONNECTED' }
    }
    if ($ClientRuntime -eq 'installed') {
        # The server has disconnected and stopped. Sample only this public-fixture
        # JVM's live heap after the diagnostic command's full GC, before shutdown.
        $probe=Start-Owned "$jdk\bin\jcmd.exe" @([string]$client.Id,'GC.class_histogram') @{} $root
        $probeOut=$probe.StandardOutput.ReadToEndAsync(); $probeErr=$probe.StandardError.ReadToEndAsync()
        try {
            if (-not $probe.WaitForExit(30000)) { $probe.Kill(); $probe.WaitForExit(); throw 'Client heap diagnostic timeout' }
            $histogram=$probeOut.GetAwaiter().GetResult()
            $histogram | Set-Content -LiteralPath (Join-Path $root 'client-live-histogram.txt')
            $probeErr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root 'client-live-histogram-stderr.txt')
            if ($probe.ExitCode -ne 0 -or $histogram -notmatch 'Total\s+\d+') { throw 'Client heap diagnostic failed' }
            $retained=@([regex]::Matches($histogram,'(?m)^\s*\d+:\s+(?<count>\d+)\s+\d+\s+io\.github\.genichimaruo\.worldgenassist\.(?:common\.(?:AuthorizedSeededLeafJob|SeededLeafJob|SeededLeafJobClaim|SeededLeafDensityResult|SeededLeafTranscript|SeededLeafTranscript\$Entry)|client\.SeededLeafClientWorker\$Attempt)(?=\s|$)') | Where-Object { [long]$_.Groups['count'].Value -gt 0 })
            if ($retained.Count -ne 0) { throw 'Client retains a fixture job/transcript/result after disconnect and full GC' }
            'targeted_live_job_classes=0; scope=one normal completed/disconnected session; deep_heap_graph=NOT_PROVEN' | Set-Content -LiteralPath (Join-Path $root 'client-retention-check.txt')
        } finally { $probe.Dispose() }
        # Ask only this owned Java process to close its window, then require a
        # natural zero exit and Minecraft's verified exitWorldAndClose marker.
        $client.Refresh()
        if (-not $client.CloseMainWindow()) { throw 'Owned installed client did not accept window close' }
        if (-not $client.WaitForExit(30000) -or $client.ExitCode -ne 0) { throw 'Installed client did not exit gracefully' }
        if ((Get-Content -LiteralPath (Join-Path $root 'client/logs/latest.log') -Raw) -notmatch 'Stopping!') { throw 'Client shutdown marker missing' }
        'close_request=owned_process_main_window; natural_exit=0; minecraft_stopping_marker=true' | Set-Content -LiteralPath (Join-Path $root 'client-shutdown.txt')
    }
    $success=$true
} finally {
    foreach ($owned in @($client,$server,$tunnel)) {
        if ($null -ne $owned -and -not $owned.HasExited) { $owned.Kill($true); $owned.WaitForExit() }
    }
    if ($null -ne $client) {
        $clientOut.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root 'client-stdout.log')
        $clientErr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root 'client-stderr.log')
    }
    if ($null -ne $server) { $serverErr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root 'remote-stderr.log') }
    if ($null -ne $tunnel) { $tunnelErr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $root 'tunnel-stderr.log') }
    @("mode=$Mode","success=$success",'performance=NOT_EVALUATED',"server=installed JAR; client=$ClientRuntime",'transport=SSH tunnel over Tailscale') |
        Set-Content -LiteralPath (Join-Path $root 'summary.txt')
    Write-Output "evidence=$root success=$success"
}
