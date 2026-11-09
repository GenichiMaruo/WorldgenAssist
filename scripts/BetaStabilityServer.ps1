# Appended after existing gameplay, before final save/stop. Same start arguments/world.
function Wait-BetaAck([string]$Phase,[int]$Seconds=120){
    $path=Join-Path $evidence ('beta-'+$Phase+'.ack')
    [Console]::Out.WriteLine("SERVER_BETA_WAIT $Phase")
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    while(-not(Test-Path -LiteralPath $path)){if($process.HasExited -or [DateTime]::UtcNow -gt $deadline){throw "Beta acknowledgement timed out: $Phase"};Start-Sleep -Milliseconds 250}
    if((Get-Item -LiteralPath $path).Length -gt 32768){throw 'Beta ack bound'}
    $ack=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
    if($ack.nonce -ne $GameplayNonce -or $ack.players.Count -ne 2){throw 'Beta acknowledgement identity'}
    foreach($item in $ack.players){if($item.state.nonce -ne $GameplayNonce -or $item.state.owner -notin $ownerNames -or -not $item.state.success -or $item.state.health -ne 20){throw 'Beta client evidence incomplete'}}
    if((@($ack.players.state.owner|Sort-Object)-join ';') -cne (($ownerNames|Sort-Object)-join ';')){throw 'Distinct beta owners required'}
    return $ack
}
function Invoke-BetaExploration([object[]]$saved,[object]$report) {
        foreach($row in $saved){Send-Command ('gamemode creative '+$row.owner)}
        $arm=Wait-BetaAck 'arm'
        foreach($row in $saved){
            $state=@($arm.players|Where-Object {$_.state.owner -eq $row.owner})[0].state
            $x=$row.stand[0]+0.5;$y=$row.stand[1];$z=$row.stand[2]+0.5
            if($state.phase -ne 'arm' -or -not $state.arm_ready -or -not $state.flying -or $state.on_ground -or [Math]::Abs($state.position[0]-$x) -gt 0.1 -or [Math]::Abs($state.position[2]-$z) -gt 0.1 -or $state.position[1]-$y -lt 2 -or $state.position[1]-$y -gt 12){throw 'Takeoff witness differed from the owned saved patch'}
            $marker='CAWG_BETA_ARMED_'+$GameplayNonce+'_'+$row.owner;$offset=(Log-Text).Length
            Send-Command ('execute positioned '+$x+' '+($y+5)+' '+$z+' if entity @a[name='+$row.owner+',distance=..8,gamemode=creative,nbt={Health:20.0f,DeathTime:0s,OnGround:0b,abilities:{flying:1b}}] run say '+$marker)
            Wait-Log ([regex]::Escape($marker)) 30 $offset|Out-Null
        }
        $report['flight_ready']=$arm.players
        # This is the sole corridor positioning, before any timed movement.
        foreach($row in $saved){Send-Command ('tp '+$row.owner+' '+($row.stand[0]+0.5)+' 330 '+($row.stand[2]+0.5))}
        Send-Command ('say CAWG_BETA_EXPLORE_BEGIN_'+$GameplayNonce)
        [void](Wait-BetaAck 'explore')
        $started=[DateTime]::UtcNow;$previous=@{};$loaded=@{};$missing=@{}
        foreach($minute in 1..$BetaStabilityMinutes){
            $due=$started.AddMinutes($minute)
            while([DateTime]::UtcNow -lt $due){if($process.HasExited){throw 'Server exited during beta exploration'};Start-Sleep -Milliseconds 1000}
            $ack=Wait-BetaAck ('sample-'+$minute)
            if($ack.free_physical_bytes -lt 8GB){throw 'Client host physical memory headroom fell below8GB'}
            foreach($item in $ack.players){
                $row=$item.state;$name=$row.owner
                if($row.phase -ne 'explore' -or -not $row.flying -or $row.elapsed_ms -lt ($minute*60000-15000)){throw 'Full duration/original creative flight evidence missing'}
                if($previous.ContainsKey($name) -and $row.travel_blocks-$previous[$name] -lt 30){throw 'Client movement stalled for one minute'}
                if($row.loaded_current_chunk){$loaded[$name]=1+$loaded[$name];$missing[$name]=0}else{$missing[$name]=1+$missing[$name]}
                if($missing[$name] -ge 3){throw 'Current chunk receipt stalled for three minutes'}
                if($item.private_bytes -gt 16GB){throw 'Client private memory exceeded bounded16GB'}
                $previous[$name]=$row.travel_blocks
                $marker='CAWG_BETA_ALIVE_'+$GameplayNonce+'_'+$minute+'_'+$name;$offset=(Log-Text).Length
                Send-Command ('execute as '+$name+' if entity @s[gamemode=creative,nbt={Health:20.0f,DeathTime:0s,abilities:{flying:1b}}] run say '+$marker)
                Wait-Log ([regex]::Escape($marker)) 30 $offset|Out-Null
            }
            $process.Refresh();if($process.PrivateMemorySize64 -gt 10GB){throw 'Server private memory exceeded bounded10GB'}
            $report.samples+=@{minute=$minute;utc=[DateTime]::UtcNow.ToString('o');clients=$ack.players;client_host_free_physical_bytes=$ack.free_physical_bytes;client_host_total_physical_bytes=$ack.total_physical_bytes;server_pid=$process.Id;server_private_bytes=$process.PrivateMemorySize64;server_working_set_bytes=$process.WorkingSet64}
            $report|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $evidence 'beta-stability.json')
            [Console]::Out.WriteLine("SERVER_BETA_PROGRESS minute=$minute total=$BetaStabilityMinutes")
        }
        $stop=Wait-BetaAck 'stop'
        Send-Command ('say CAWG_BETA_EXPLORE_END_'+$GameplayNonce)
        foreach($item in $stop.players){if($item.state.travel_blocks -lt 6000 -or $item.state.reversals -lt 3 -or $loaded[$item.state.owner] -lt 10){throw 'Actual repeated movement/chunk receipt proof missing'}}
        foreach($row in $saved){Send-Command ('tp '+$row.owner+' '+($row.stand[0]+0.5)+' '+($row.stand[1]+1.5)+' '+($row.stand[2]+0.5))}
        $landing=Wait-BetaAck 'landing'
        foreach($row in $saved){
            $state=@($landing.players|Where-Object {$_.state.owner -eq $row.owner})[0].state
            $site=$state.landing
            $x=$row.stand[0]+0.5;$y=$row.stand[1]+1.5;$z=$row.stand[2]+0.5
            if($state.phase -cne 'landing' -or $state.on_ground -or -not $state.flying -or [Math]::Abs($state.position[0]-$x) -gt 0.05 -or [Math]::Abs($state.position[1]-$y) -gt 0.05 -or [Math]::Abs($state.position[2]-$z) -gt 0.05 -or -not $site.hover_at_site -or $site.stable_observations -lt 4 -or -not $site.stand_chunk_loaded -or -not $site.target_chunk_loaded -or $site.floor -cne $row.stand_block -or -not $site.floor_solid_dry -or -not $site.clear_dry_column -or $site.placed -cne 'minecraft:cobblestone'){throw 'Landing witness differed from saved natural patch'}
            if($row.stand_block -cnotmatch '^minecraft:[a-z0-9_]{1,80}$'){throw 'Invalid recorded floor ID'}
            $floor=@($row.stand[0],($row.stand[1]-1),$row.stand[2])-join ' ';$placed=$row.target-join ' '
            $marker='CAWG_BETA_LANDING_'+$GameplayNonce+'_'+$row.owner;$offset=(Log-Text).Length
            $check='execute positioned '+$x+' '+$y+' '+$z+' if entity @a[name='+$row.owner+',distance=..0.1,gamemode=creative,nbt={Health:20.0f,DeathTime:0s,OnGround:0b,abilities:{flying:1b}}] if loaded '+$floor+' if loaded '+$placed+' if block '+$floor+' '+$row.stand_block+' if block '+$placed+' minecraft:cobblestone'
            foreach($h in 0..3){$space=@($row.stand[0],($row.stand[1]+$h),$row.stand[2])-join ' ';$check+=' if block '+$space+' minecraft:air'}
            Send-Command ($check+' run say '+$marker);Wait-Log ([regex]::Escape($marker)) 30 $offset|Out-Null
        }
        $report['landing_ready']=$landing.players
        foreach($row in $saved){Send-Command ('gamemode survival '+$row.owner)}
        $groundPath=Join-Path $evidence 'gameplay-ground.ack';Remove-Item -LiteralPath $groundPath
        $ground=Wait-GameplayAcknowledgement 'ground'
        foreach($row in $ground.players){Assert-GameplayPlayer $row.owner 'after_exploration';Assert-GameplayBlock $row 'minecraft:cobblestone' 'after_exploration'}
}
function Invoke-BetaStability {
    $journal=Get-Content -LiteralPath (Join-Path $evidence 'gameplay-journal.json') -Raw|ConvertFrom-Json
    $saved=@($journal.phases|Where-Object phase -eq 'reconnect')[0].clients
    $report=[ordered]@{success=$false;nonce=$GameplayNonce;minutes=$BetaStabilityMinutes;world=$case;loader=$Loader;movement='original creative flight input in bounded +/-768-block corridor; survival before/after';memory_policy=@{client_private_limit_gib=16;client_host_minimum_free_physical_gib=8;server_private_limit_gib=10;forced_gc=$false};samples=@();restart=$null;reload=$null}
    try {
        foreach($row in $saved){Assert-GameplayPlayer $row.owner 'before_restart';Assert-GameplayBlock $row 'minecraft:cobblestone' 'before_restart'}
        foreach($name in $ownerNames){$offset=(Log-Text).Length;Send-Command ('kick '+$name+' Owned beta restart');Wait-Log ([regex]::Escape($name)+' lost connection:') 30 $offset|Out-Null}
        Send-Command 'save-all flush';Send-Command 'stop'
        if(-not $process.WaitForExit(60000) -or $process.ExitCode -ne 0){throw 'First server did not stop cleanly'}
        $first=Join-Path $evidence 'beta-first-runtime';New-Item -ItemType Directory -Path $first|Out-Null
        Save-WorldgenConsoleEvidence $stdout $latest (Join-Path $first 'latest.log')
        $stderr.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $first 'stderr.log')
        $snapshot=Join-Path $evidence 'beta-before-save';New-Item -ItemType Directory -Path $snapshot|Out-Null
        $world=Join-Path $resolved $case
        $paths=@('level.dat')
        foreach($row in $saved){$uuid=New-OwnerUuid $row.owner;$paths+=@(('players/data/'+$uuid+'.dat'),('players/stats/'+$uuid+'.json'),('dimensions/minecraft/overworld/region/r.'+[int][Math]::Floor($row.target[0]/512.0)+'.'+[int][Math]::Floor($row.target[2]/512.0)+'.mca'))}
        $inventory=@(foreach($relative in @($paths|Sort-Object -Unique)){$source=Join-Path $world $relative;$dest=Join-Path $snapshot $relative;New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($dest))|Out-Null;Copy-Item -LiteralPath $source -Destination $dest;@{relative=$relative;sha256=(Get-FileHash -LiteralPath $source).Hash;bytes=(Get-Item -LiteralPath $source).Length}})
        $oldPid=$process.Id;$arguments=$start.Arguments;$propertiesPath=Join-Path $runtimeWorkingRoot 'server.properties';$propertiesHash=(Get-FileHash -LiteralPath $propertiesPath).Hash
        Copy-Item -LiteralPath $propertiesPath -Destination (Join-Path $first 'server.properties')
        # Verified Settings.store calls Java Properties.store on startup, which
        # replaces the timestamp comment. Compare every noncomment setting line.
        $propertiesBody=@([IO.File]::ReadAllLines($propertiesPath)|Where-Object {$_ -notmatch '^\s*[#!]' -and $_.Trim()}|Sort-Object)
        $script:betaFirstConsole=Get-Content -LiteralPath (Join-Path $first 'latest.log') -Raw
        $process.Dispose();$script:process=[Diagnostics.Process]::new();$script:process.StartInfo=$start
        foreach($row in $inventory){if((Get-FileHash -LiteralPath (Join-Path $world $row.relative)).Hash -ne $row.sha256){throw 'Saved restart input changed'}}
        if(-not $process.Start()){throw 'Same-world restart failed'};$script:processHandle=$process.Handle
        $script:stdout=Start-WorldgenConsoleCapture $process (Join-Path $evidence 'restart-stdout.log');$script:stderr=$process.StandardError.ReadToEndAsync()
        Wait-Log 'Done \(' 180 $betaFirstConsole.Length|Out-Null
        Copy-Item -LiteralPath $propertiesPath -Destination (Join-Path $evidence 'restart-server.properties')
        $restartProperties=@([IO.File]::ReadAllLines($propertiesPath)|Where-Object {$_ -notmatch '^\s*[#!]' -and $_.Trim()}|Sort-Object)
        if($process.StartInfo.Arguments -cne $arguments -or ($propertiesBody-join "`n") -cne ($restartProperties-join "`n")){throw 'Restart arguments/world setting values changed'}
        $rejoin=Wait-BetaAck 'rejoin'
        foreach($row in $saved){Wait-Log ([regex]::Escape($row.owner)+' joined the game') 120 $betaFirstConsole.Length|Out-Null;Assert-GameplayPlayer $row.owner 'reloaded';Assert-GameplayBlock $row 'minecraft:cobblestone' 'reloaded';Wait-Log ('worker\.register owner='+[regex]::Escape((New-OwnerUuid $row.owner))+' status=ACCEPTED') 60 $betaFirstConsole.Length|Out-Null}
        $report.restart=@{first_pid=$oldPid;second_pid=$process.Id;first_exit_code=0;same_arguments=$true;before_exploration=$true;properties_sha256=$propertiesHash;restart_properties_sha256=(Get-FileHash -LiteralPath $propertiesPath).Hash;all_noncomment_setting_lines_equal=$true;world_path=$world;inputs=$inventory;loaded_block_before_any_postrestart_write=$true}
        $report.reload=$rejoin
        foreach($phase in @('mine','place','reconnect')){
            Remove-Item -LiteralPath (Join-Path $evidence ('gameplay-'+$phase+'.ack'))
            if($phase -eq 'reconnect'){Send-Command 'save-all flush'}
            $ack=Wait-GameplayAcknowledgement $phase
            foreach($row in $ack.players){Assert-GameplayPlayer $row.owner ('postrestart_'+$phase);Assert-GameplayBlock $row $(if($phase -eq 'mine'){'minecraft:air'}else{'minecraft:cobblestone'}) ('postrestart_'+$phase)}
            if($phase -eq 'reconnect'){$journal.phases[-1].clients=$ack.players}
        }
        Invoke-BetaExploration $saved $report
        foreach($phase in @('mine','place','reconnect')){
            Remove-Item -LiteralPath (Join-Path $evidence ('gameplay-'+$phase+'.ack'))
            if($phase -eq 'reconnect'){Send-Command 'save-all flush'}
            $ack=Wait-GameplayAcknowledgement $phase
            foreach($row in $ack.players){Assert-GameplayPlayer $row.owner ('postexploration_'+$phase);Assert-GameplayBlock $row $(if($phase -eq 'mine'){'minecraft:air'}else{'minecraft:cobblestone'}) ('postexploration_'+$phase)}
            if($phase -eq 'reconnect'){$journal.phases[-1].clients=$ack.players}
        }
        $journal|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $evidence 'gameplay-journal.json')
        $report.success=$true
    }finally{$report|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $evidence 'beta-stability.json')}
}
