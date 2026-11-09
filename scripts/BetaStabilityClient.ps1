# Only the owned beta profiles use this controller; no probe ships in a release.
function Start-BetaControl([string]$Phase,[object[]]$Clients,[string]$Nonce){
    foreach($client in $Clients){
        $directory=Join-Path $client.profile 'client/gameplay-probe'
        $state=Join-Path $directory 'beta-state.json';if(Test-Path -LiteralPath $state){Remove-Item -LiteralPath $state}
        Write-Utf8 (Join-Path $directory 'beta-control.new.json') (@{phase=$Phase;owner=$client.name;nonce=$Nonce}|ConvertTo-Json -Compress)
        [IO.File]::Move((Join-Path $directory 'beta-control.new.json'),(Join-Path $directory 'beta-control.json'),$true)
    }
}
function Open-BetaStateReport([string]$Path){
    # Java publishes by renaming the completed file. On Windows our read
    # handle must allow that rename while we finish reading the old file.
    $share=[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
    return [IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,$share)
}
function Get-BetaReports([string]$Phase,[object[]]$Clients,[string]$Nonce){
    $rows=@()
    foreach($client in $Clients){
        $file=Join-Path $client.profile 'client/gameplay-probe/beta-state.json'
        try{$report=Open-BetaStateReport $file}
        catch [IO.FileNotFoundException]{return $null}
        try{
            $length=$report.Length
            if($length -lt 1 -or $length -gt 8192){throw 'Beta report bound'}
            $bytes=[byte[]]::new([int]$length)
            $report.ReadExactly($bytes,0,$bytes.Length)
            if($report.ReadByte() -ne -1){throw 'Beta report changed in place'}
        }finally{$report.Dispose()}
        $row=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)|ConvertFrom-Json
        if($row.phase -ne $Phase){return $null}
        if($row.nonce -ne $Nonce -or $row.owner -ne $client.name -or -not $row.success){throw "Beta input failure $($client.name): $($row.error)"}
        if($row.health -ne 20 -or $row.position.Count -ne 3 -or [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()-$row.client_epoch_ms -gt 15000){throw 'Beta live health/heartbeat missing'}
        if($Phase -eq 'arm' -and (-not $row.arm_ready -or $row.on_ground -or -not $row.flying -or -not $row.loaded_current_chunk -or $row.screen -ne '' -or [Math]::Abs([double]$row.vertical_velocity) -ge 0.05)){throw 'Original creative takeoff and settled hover required'}
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
