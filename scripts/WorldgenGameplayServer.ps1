# Server console operations only; no world edits, player pauses or health changes.
function Wait-GameplayAcknowledgement([string]$Phase){
    $path=Join-Path $evidence ('gameplay-'+$Phase+'.ack')
    [Console]::Out.WriteLine("SERVER_GAMEPLAY_WAIT $Phase")
    $deadline=[DateTime]::UtcNow.AddSeconds(120)
    while(-not(Test-Path -LiteralPath $path)){
        if($process.HasExited -or [DateTime]::UtcNow -gt $deadline){throw "Gameplay phase timed out: $Phase"}
        Start-Sleep -Milliseconds 250
    }
    if((Get-Item -LiteralPath $path).Length -gt 32768){throw 'Gameplay acknowledgement bound'}
    $ack=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
    if($ack.nonce -ne $GameplayNonce -or $ack.phase -ne $Phase -or $ack.players.Count -ne 2 -or (($ack.players.owner|Sort-Object)-join ';') -cne (($ownerNames|Sort-Object)-join ';')){throw 'Gameplay phase/actor identity differs'}
    foreach($row in $ack.players){if(-not $row.success -or $row.nonce -ne $GameplayNonce -or $row.phase -ne $Phase -or $row.health -ne 20 -or -not $row.alive){throw 'Gameplay client proof incomplete'}}
    return $ack
}
function Assert-GameplayPlayer([string]$Name,[string]$Phase){
    $marker='CAWG_GAMEPLAY_PLAYER_'+$GameplayNonce+'_'+$Phase+'_'+$Name
    $offset=(Log-Text).Length
    Send-Command ('execute as '+$Name+' if entity @s[gamemode=survival,nbt={Health:20.0f,DeathTime:0s,OnGround:1b,abilities:{flying:0b}}] run say '+$marker)
    Wait-Log ([regex]::Escape($marker)) 15 $offset|Out-Null
}
function Assert-GameplayBlock([object]$Row,[string]$Expected,[string]$Phase){
    $marker='CAWG_GAMEPLAY_BLOCK_'+$GameplayNonce+'_'+$Phase+'_'+$Row.owner
    $offset=(Log-Text).Length
    $deadline=[DateTime]::UtcNow.AddSeconds(15)
    do {
        if($process.HasExited){throw 'Server exited before authoritative block confirmation'}
        Send-Command ('execute if block '+($Row.target-join ' ')+' '+$Expected+' run say '+$marker)
        Start-Sleep -Milliseconds 500
        $text=Log-Text
        if($text.Length -gt $offset -and $text.IndexOf($marker,$offset,[StringComparison]::Ordinal) -ge 0){return}
    }while([DateTime]::UtcNow -lt $deadline)
    foreach($id in @('minecraft:air','minecraft:grass_block','minecraft:dirt','minecraft:cobblestone','minecraft:stone','minecraft:sand')){
        Send-Command ('execute if block '+($Row.target-join ' ')+' '+$id+' run say CAWG_GAMEPLAY_OBSERVED_'+$GameplayNonce+'_'+$Phase+'_'+$Row.owner+'_'+$id)
    }
    Send-Command ('data get entity '+$Row.owner+' SelectedItem')
    Send-Command ('data get entity '+$Row.owner+' Pos')
    Start-Sleep -Seconds 1
    throw "Authoritative block confirmation timed out: $marker; original block/item/position diagnostics retained"
}
function Invoke-OrdinaryGameplay {
    $journal=[ordered]@{nonce=$GameplayNonce;success=$false;phases=@();physics_paused=$false;world_blocks_set_by_console=$false;health_set=$false}
    try {
        $scan=Wait-GameplayAcknowledgement 'scan'
        foreach($row in $scan.players){
            if($row.stand.Count -ne 3 -or $row.target.Count -ne 3){throw 'Bounded scan geometry required'}
            foreach($coordinate in @($row.stand)+@($row.target)){if([double]$coordinate -ne [int]$coordinate){throw 'Integer natural patch required'}}
            $sign=if($row.owner -eq $ownerNames[0]){1}else{-1}
            $cx=$sign*(16000+4096*($WarmupRuns+$MeasuredRepeats));$cz=$sign*(-32000-4096*($WarmupRuns+$MeasuredRepeats))
            if([Math]::Abs($row.stand[0]-$cx) -gt 97 -or [Math]::Abs($row.stand[2]-$cz) -gt 97 -or $row.stand[1] -lt -60 -or $row.stand[1] -gt 315 -or $row.target[0] -ne $row.stand[0]+1 -or $row.target[1] -ne $row.stand[1]-1 -or $row.target[2] -ne $row.stand[2]){throw 'Only loaded last measured center patch allowed'}
            foreach($id in @($row.stand_block,$row.target_block)){if($id -notin @('minecraft:grass_block','minecraft:dirt','minecraft:coarse_dirt','minecraft:rooted_dirt','minecraft:stone','minecraft:andesite','minecraft:diorite','minecraft:granite','minecraft:deepslate','minecraft:sandstone','minecraft:red_sandstone','minecraft:sand','minecraft:red_sand','minecraft:gravel')){throw 'Natural solid ground required'}}
            if($row.support_block -notmatch '^minecraft:[a-z0-9_]{1,80}$'){throw 'Support identifier bound'}
            $floor=@($row.stand[0],($row.stand[1]-1),$row.stand[2]);$head=@($row.stand[0],($row.stand[1]+1),$row.stand[2]);$support=@($row.target[0],($row.target[1]-1),$row.target[2])
            $marker='CAWG_GAMEPLAY_NATURAL_'+$GameplayNonce+'_'+$row.owner;$offset=(Log-Text).Length
            Send-Command ('execute if block '+($floor-join ' ')+' '+$row.stand_block+' if block '+($row.target-join ' ')+' '+$row.target_block+' if block '+($support-join ' ')+' '+$row.support_block+' if block '+($row.stand-join ' ')+' minecraft:air if block '+($head-join ' ')+' minecraft:air run say '+$marker)
            Wait-Log ([regex]::Escape($marker)) 15 $offset|Out-Null
            Send-Command ('gamemode survival '+$row.owner)
            Send-Command ('tp '+$row.owner+' '+[string]($row.stand[0]+0.5)+' '+[string]($row.stand[1]+1.5)+' '+[string]($row.stand[2]+0.5))
            Send-Command ('give '+$row.owner+' minecraft:iron_pickaxe 1')
            Send-Command ('give '+$row.owner+' minecraft:cobblestone 8')
        }
        $journal.phases+=@{phase='scan';clients=$scan.players;authoritative_natural_patch=$true}
        foreach($phase in @('ground','mine','place','reconnect')){
            if($phase -eq 'reconnect'){Send-Command 'save-all flush';$reconnectOffset=(Log-Text).Length}
            $ack=Wait-GameplayAcknowledgement $phase
            foreach($row in $ack.players){
                $original=@($scan.players|Where-Object owner -eq $row.owner)
                if($original.Count -ne 1 -or ($row.stand-join ';') -cne ($original[0].stand-join ';') -or ($row.target-join ';') -cne ($original[0].target-join ';')){throw 'Gameplay target changed'}
                if($row.game_type -ne 0 -or $row.flying -or -not $row.on_ground){throw 'Actual client survival landing required'}
                Assert-GameplayPlayer $row.owner $phase
                if($phase -eq 'mine'){Assert-GameplayBlock $row 'minecraft:air' $phase}
                elseif($phase -in @('place','reconnect')){Assert-GameplayBlock $row 'minecraft:cobblestone' $phase}
                if($phase -eq 'reconnect'){
                    if(-not $row.connection_changed){throw 'Client did not reconnect'}
                    Wait-Log ([regex]::Escape($row.owner)+' lost connection:') 10 $reconnectOffset|Out-Null
                    Wait-Log ([regex]::Escape($row.owner)+' joined the game') 10 $reconnectOffset|Out-Null
                }
            }
            $journal.phases+=@{phase=$phase;clients=$ack.players;authoritative_player_checked=$true;authoritative_block_checked=($phase -ne 'ground')}
        }
        $journal.success=$true
    }finally{$journal|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $evidence 'gameplay-journal.json')}
}
