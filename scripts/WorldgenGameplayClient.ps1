# Local controller for the separate unshipped gameplay input driver.
function Start-GameplayControl([string]$Phase,[object[]]$Clients,[string]$Nonce){
    if($Phase -notmatch '^(scan|ground|mine|place|reconnect)$' -or $Nonce -notmatch '^[a-f0-9]{32}$'){throw 'Owned gameplay control identity required'}
    foreach($client in $Clients){
        $directory=Join-Path $client.profile 'client/gameplay-probe'
        $temp=Join-Path $directory 'control.new.json';$target=Join-Path $directory 'control.json'
        Write-Utf8 $temp (@{phase=$Phase;nonce=$Nonce;owner=$client.name}|ConvertTo-Json -Compress)
        [IO.File]::Move($temp,$target,$true)
    }
}
function Get-GameplayReports([string]$Phase,[object[]]$Clients,[string]$Nonce){
    $rows=@()
    foreach($client in $Clients){
        $file=Join-Path $client.profile ('client/gameplay-probe/result-'+$Phase+'.json')
        if(-not(Test-Path -LiteralPath $file)){return $null}
        if((Get-Item -LiteralPath $file).Length -gt 8192){throw 'Gameplay report bound'}
        $row=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json
        if($row.nonce -ne $Nonce -or $row.owner -ne $client.name -or $row.phase -ne $Phase -or -not $row.success){throw "Gameplay client failure: $($client.name) / $Phase / $($row.error)"}
        if($row.health -ne 20 -or -not $row.alive -or $row.position.Count -ne 3 -or $row.stand.Count -ne 3 -or $row.target.Count -ne 3){throw 'Original client state/scan required'}
        if($Phase -ne 'scan' -and ($row.game_type -ne 0 -or $row.flying -or -not $row.on_ground)){throw 'Natural survival state required'}
        if($Phase -eq 'reconnect' -and -not $row.connection_changed){throw 'Actual original reconnection required'}
        if($Phase -eq 'mine' -and ($row.original_mining_calls -lt 2 -or $row.actual_target_block -ne 'minecraft:air')){throw 'Original multi-tick mining proof missing'}
        if($Phase -in @('place','reconnect') -and $row.actual_target_block -ne 'minecraft:cobblestone'){throw 'Actual client placed block missing'}
        $rows+=$row
    }
    return [ordered]@{nonce=$Nonce;phase=$Phase;players=$rows}
}
