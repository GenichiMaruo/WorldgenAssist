# Only the owned beta profiles use this controller; no probe ships in a release.
$script:betaReportBarriers=@{}
function Send-BetaAcknowledgement {
    param(
        [Parameter(Mandatory)][string]$LocalFile,
        [Parameter(Mandatory)][string]$CaseName,
        [Parameter(Mandatory)][string]$Phase
    )
    if($CaseName -cnotmatch '\A(?:scenario-overworld-assisted-p2-performance-[0-9]{8}-[0-9]{6}-[0-9]{3}|beta-ack-preflight-[0-9a-f]{32})\z' -or
       $Phase -cnotmatch '\A(?:arm|rejoin|explore|landing|stop|sample-(?:[1-9]|[1-5][0-9]|60))\z'){throw 'Unknown beta acknowledgement destination'}

    $root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../test-artifacts'))
    $file=(Get-Item -LiteralPath $LocalFile -Force -ErrorAction Stop).FullName
    if(-not $file.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase) -or
       [IO.Path]::GetFileName($file) -cne ('beta-'+$Phase+'.json')){throw 'Expected the completed phase report in test-artifacts'}
    for($entry=$file;;$entry=[IO.Path]::GetDirectoryName($entry)){
        if((Get-Item -LiteralPath $entry -Force -ErrorAction Stop).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Beta report path must not be a reparse point'}
        if($entry -eq $root){break}
    }

    $remote='gen1c@100.103.102.109'
    $directory='E:/WorldgenAssist/port26.3/evidence/'+$CaseName
    $stage='.beta-'+$Phase+'-'+[Guid]::NewGuid().ToString('N')+'.part'
    $target='beta-'+$Phase+'.ack'
    $options=@('-o','BatchMode=yes','-o','ConnectTimeout=15','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=2')
    # Keep the completed local file immutable while scp opens its own reader.
    $stream=[IO.File]::Open($file,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try{
        $bytes=$stream.Length
        if($bytes -lt 1 -or $bytes -gt 32768){throw 'Beta acknowledgement must be 1..32768 bytes'}
        $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))
        & scp.exe -q @options $file ($remote+':'+$directory+'/'+$stage)
        if($LASTEXITCODE -ne 0){throw "Beta acknowledgement upload failed for $Phase"}
    }finally{$stream.Dispose()}

    # Only validated path tokens, length and digest cross the command line.
    # The server waits for the final name; it never sees an incomplete JSON.
    $code=@'
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$env:TEMP='E:/WorldgenAssist/temp';$env:TMP=$env:TEMP
$dir='@DIRECTORY@'
$stage=Join-Path $dir '@STAGE@'
$target=Join-Path $dir '@FINAL@'
if(Test-Path -LiteralPath $target){throw 'Beta acknowledgement already exists'}
$f=Get-Item -LiteralPath $stage -Force
if($f.PSIsContainer -or ($f.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $f.Length -ne @BYTES@ -or (Get-FileHash -LiteralPath $stage -Algorithm SHA256).Hash -cne '@SHA@'){throw 'Staged beta acknowledgement identity differs'}
[IO.File]::Move($stage,$target)
'@
    $code=$code.Replace('@DIRECTORY@',$directory).Replace('@STAGE@',$stage).Replace('@FINAL@',$target).Replace('@BYTES@',[string]$bytes).Replace('@SHA@',$hash)
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
    & ssh.exe @options $remote ('powershell -NoProfile -NonInteractive -EncodedCommand '+$encoded)
    if($LASTEXITCODE -ne 0){throw "Beta acknowledgement verification/publication failed for $Phase"}
}
function Get-LatestBetaStateFile([string]$Directory,[long]$AfterSequence=0){
    $latest=$null
    foreach($path in [IO.Directory]::EnumerateFiles($Directory,'beta-state-*.json')){
        $name=[IO.Path]::GetFileName($path)
        if($name -cnotmatch '\Abeta-state-([0-9]{10})\.json\z'){continue}
        $sequence=[long]$Matches[1]
        if($sequence -le $AfterSequence -or $sequence -eq 0){continue}
        if($null -eq $latest -or $sequence -gt $latest.sequence){
            $latest=[pscustomobject]@{sequence=$sequence;path=$path}
        }
    }
    return $latest
}
function Open-LatestBetaStateReport([string]$Directory,[long]$AfterSequence=0){
    # Finished snapshots are never overwritten. The writer may prune an old
    # snapshot between enumeration and open; retry only that specific race.
    $share=[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
    for($attempt=0;$attempt -lt 2;$attempt++){
        $latest=Get-LatestBetaStateFile $Directory $AfterSequence
        if($null -eq $latest){return $null}
        try{$stream=[IO.File]::Open($latest.path,[IO.FileMode]::Open,[IO.FileAccess]::Read,$share)}
        catch [IO.FileNotFoundException]{continue}
        return [pscustomobject]@{sequence=$latest.sequence;path=$latest.path;stream=$stream}
    }
    return $null
}
function Start-BetaControl([string]$Phase,[object[]]$Clients,[string]$Nonce){
    foreach($client in $Clients){
        $directory=Join-Path $client.profile 'client/gameplay-probe'
        # The next command requires a new publication. Minute samples use
        # the existing explore command and continue enforcing live heartbeats.
        $latest=Get-LatestBetaStateFile $directory
        $floor=if($null -eq $latest){0L}else{$latest.sequence}
        Write-Utf8 (Join-Path $directory 'beta-control.new.json') (@{phase=$Phase;owner=$client.name;nonce=$Nonce}|ConvertTo-Json -Compress)
        [IO.File]::Move((Join-Path $directory 'beta-control.new.json'),(Join-Path $directory 'beta-control.json'),$true)
        $script:betaReportBarriers[$directory]=@{phase=$Phase;nonce=$Nonce;owner=$client.name;after_sequence=$floor}
    }
}
function Get-BetaReports([string]$Phase,[object[]]$Clients,[string]$Nonce){
    $rows=@()
    foreach($client in $Clients){
        $directory=Join-Path $client.profile 'client/gameplay-probe'
        $barrier=$script:betaReportBarriers[$directory]
        if($null -eq $barrier -or $barrier.phase -cne $Phase -or $barrier.nonce -cne $Nonce -or $barrier.owner -cne $client.name){throw 'Beta command/report binding differs'}
        $snapshot=Open-LatestBetaStateReport $directory $barrier.after_sequence
        if($null -eq $snapshot){return $null}
        $report=$snapshot.stream
        try{
            $length=$report.Length
            if($length -lt 1 -or $length -gt 8192){throw 'Beta report bound'}
            $bytes=[byte[]]::new([int]$length)
            $report.ReadExactly($bytes,0,$bytes.Length)
            if($report.ReadByte() -ne -1){throw 'Beta report changed in place'}
        }finally{$report.Dispose()}
        $row=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)|ConvertFrom-Json
        if(($row.sequence -isnot [long] -and $row.sequence -isnot [int]) -or $row.sequence -ne $snapshot.sequence){throw 'Beta report sequence differs from filename'}
        if($row.nonce -cne $Nonce -or $row.owner -cne $client.name -or -not $row.success){throw "Beta input failure $($client.name): $($row.error)"}
        if($row.phase -cne $Phase){return $null}
        if($row.health -ne 20 -or $row.position.Count -ne 3 -or [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()-$row.client_epoch_ms -gt 15000){throw 'Beta live health/heartbeat missing'}
        if($Phase -eq 'arm' -and (-not $row.arm_ready -or $row.on_ground -or -not $row.flying -or -not $row.loaded_current_chunk -or $row.screen -ne '' -or [Math]::Abs([double]$row.vertical_velocity) -ge 0.05)){throw 'Original creative takeoff and settled hover required'}
        if($Phase -eq 'landing'){
            $site=$row.landing
            if($row.on_ground -or -not $row.flying -or -not $row.loaded_current_chunk -or $row.screen -ne '' -or -not $site.hover_at_site -or -not $site.stand_chunk_loaded -or -not $site.target_chunk_loaded -or $site.floor -cne $site.expected_floor -or -not $site.floor_solid_dry -or -not $site.clear_dry_column -or $site.placed -cne 'minecraft:cobblestone' -or $site.stable_observations -lt 4){throw 'Stationary creative client and complete original landing terrain required'}
        }
        if($Phase -eq 'rejoin' -and (-not $row.on_ground -or $row.flying -or -not $row.loaded_current_chunk)){throw 'Reloaded survival state required'}
        $client.process.Refresh()
        $rows+=@{state=$row;java_pid=$client.process.Id;private_bytes=$client.process.PrivateMemorySize64;working_set_bytes=$client.process.WorkingSet64}
    }
    $os=Get-CimInstance Win32_OperatingSystem
    if($Phase -eq 'explore'){
        foreach($client in $Clients){
            $row=@($rows|Where-Object {$_.state.owner -eq $client.name})[0].state
            $bucket=if($row.elapsed_ms -lt 15000){'start'}elseif($row.elapsed_ms -ge 1770000 -and $row.elapsed_ms -lt 1860000){'midpoint'}elseif($row.elapsed_ms -ge 3570000){'end'}else{$null}
            if($bucket){
                $path=Join-Path $client.profile ('client/gameplay-probe/heap-'+$bucket+'.txt')
                if(-not(Test-Path -LiteralPath $path)){
                    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=Join-Path $jdk 'bin/jcmd.exe';$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
                    [void]$info.ArgumentList.Add([string]$client.process.Id);[void]$info.ArgumentList.Add('GC.heap_info')
                    $diagnostic=[Diagnostics.Process]::new();$diagnostic.StartInfo=$info
                    try{
                        if(-not $diagnostic.Start()){throw 'Owned heap diagnostic start failed'};$out=$diagnostic.StandardOutput.ReadToEndAsync();$err=$diagnostic.StandardError.ReadToEndAsync()
                        if(-not $diagnostic.WaitForExit(10000)){$diagnostic.Kill($true);$diagnostic.WaitForExit();throw 'Owned heap diagnostic timeout'}
                        if($diagnostic.ExitCode){throw ('Owned heap diagnostic failed: '+$err.GetAwaiter().GetResult())}
                        Write-Utf8 $path ($out.GetAwaiter().GetResult())
                    }finally{$diagnostic.Dispose()}
                }
            }
        }
    }
    return @{nonce=$Nonce;phase=$Phase;players=$rows;total_physical_bytes=([long]$os.TotalVisibleMemorySize*1024);free_physical_bytes=([long]$os.FreePhysicalMemory*1024)}
}
