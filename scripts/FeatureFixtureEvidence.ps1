# Offline bounded public-seed fixture inputs and actual runtime coverage. No Minecraft execution.
function Assert-FeatureFixturePlayers([string]$Log) {
    if($Log -match 'unsafe fixture player'){throw 'Fixture player fell, lost health, or left floating state'}
    $rows=@([regex]::Matches($Log,'fixture.player_safe owner=((?:ScenarioOwner|Native)[AB]) x=(-?[0-9.]+) y=([0-9.]+) z=(-?[0-9.]+) health=([0-9.]+) alive=true flying=true complete=true'))
    if($rows.Count -ne 2 -or @($rows|ForEach-Object {$_.Groups[1].Value}|Sort-Object -Unique).Count -ne 2){throw 'Actual completed player safety check missing for both owners'}
    foreach($row in $rows){
        $sign=if($row.Groups[1].Value.EndsWith('A')){1}else{-1}
        if([double]$row.Groups[2].Value -ne $sign*16000 -or [double]$row.Groups[3].Value -ne 150 -or [double]$row.Groups[4].Value -ne $sign*-32000 -or [double]$row.Groups[5].Value -ne 20){throw 'Fixture player position/full health differs'}
    }
}
function Read-FeatureReplay([string]$Path) {
    $file=Get-Item -LiteralPath $Path
    if($file.Length -gt 131072){throw 'Replay input exceeds128KiB'}
    $value=Get-Content -LiteralPath $Path -Raw|ConvertFrom-Json
    if(((@($value.PSObject.Properties.Name|Sort-Object))-join ',') -ne 'features,schema,seed' -or $value.schema -ne 'worldgen-assist.feature-replay.v1' -or $value.seed -ne 8675309){throw 'Replay schema/seed differs'}
    $expected=@{}
    foreach($sign in @(1,-1)){foreach($dx in -13..13){foreach($dz in -13..13){$expected[([string]($sign*1000+$dx)+','+($sign*-2000+$dz))]=$true}}}
    $seen=@{}
    foreach($key in $value.features){if(-not $expected.ContainsKey([string]$key) -or $seen.ContainsKey([string]$key)){throw 'Replay coordinate missing/duplicate/outside scope'};$seen[[string]$key]=$true}
    if($seen.Count -ne 1458){throw 'Complete1458 feature inventory required'}
    return [ordered]@{path=$file.FullName;sha256=(Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant();features=@($value.features)}
}
function Assert-FeatureFixture([string]$Log,[string]$Hash) {
    if($Log -match 'fixture.failed|fixture scope generated before|fixture ticks unfrozen'){throw 'Fixture failed'}
    if($Log -notmatch 'fixture.participants ownerA=(?:ScenarioOwnerA|NativeA) chunkA=1000,-2000 ownerB=(?:ScenarioOwnerB|NativeB) chunkB=-1000,2000 creative=true before_tickets=true'){throw 'Actual participants must precede fixture demand'}
    $paused=@([regex]::Matches($Log,'fixture.player_paused owner=((?:ScenarioOwner|Native)[AB]) connection_active=true')|ForEach-Object {$_.Groups[1].Value})
    if($paused.Count -ne 2 -or @($paused|Sort-Object -Unique).Count -ne 2){throw 'Both fixture players must be paused through the barrier'}
    if($Log -match 'Timed out|keep up! Is the server overloaded\? Running [3-9][0-9]{4,}ms'){throw 'Fixture connection/main-thread stall detected'}
    $escaped=[regex]::Escape($Hash)
    if(@([regex]::Matches($Log,"fixture\.armed tickets=882 features=1458 spawns=1250 frozen=true replay_sha256=$escaped\b")).Count -ne 1){throw 'Observed fixture arm/replay/freeze differs'}
    $counts='expectedFeatures=1458, receivedFeatures=1458, finishedFeatures=1458, expectedSpawns=1250, receivedSpawns=1250, capturedSnapshots=1250, completedSpawns=1250, waitingFeatures=0'
    $replay=if($Hash -eq 'record'){'false'}else{'true'}
    $phases=', expectedTerrains=1682, receivedTerrains=1682, preparedTerrains=1682, terrainsReleased=true, receivedInitializations=1458, completedInitializations=1458'
    if($Log -notmatch ("fixture.complete replay_sha256=$escaped snapshot=Snapshot\["+[regex]::Escape($counts)+", replay=$replay, failed=false, closed=false"+[regex]::Escape($phases)+'\]')){throw 'All terrain/feature/light/SPAWN/snapshot completions are required'}
    if($Log -notmatch ('fixture.stopped snapshot=Snapshot\['+[regex]::Escape($counts)+", replay=$replay, failed=false, closed=true"+[regex]::Escape($phases)+'\]')){throw 'Clean complete fixture shutdown is required'}
    $starts=@([regex]::Matches($Log,'fixture.feature_started chunk=(-?\d+,-?\d+) frozen=true'))
    if($starts.Count -ne 1458 -or @($starts|ForEach-Object {$_.Groups[1].Value}|Sort-Object -Unique).Count -ne 1458){throw 'Actual frozen feature inventory differs'}
    $terrainBefore=@([regex]::Matches($Log.Substring(0,$starts[0].Index),'stage.digest stage=noise chunk=(?<x>-?\d+),(?<z>-?\d+)')|Where-Object {
        $x=[long]$_.Groups['x'].Value;$z=[long]$_.Groups['z'].Value
        ([Math]::Abs($x-1000) -le 14 -and [Math]::Abs($z+2000) -le 14) -or ([Math]::Abs($x+1000) -le 14 -and [Math]::Abs($z-2000) -le 14)
    }|ForEach-Object {$_.Groups['x'].Value+','+$_.Groups['z'].Value}|Sort-Object -Unique)
    if($terrainBefore.Count -ne 1682){throw 'Every original terrain body/snapshot must precede feature computation'}
    $featureEnd=0L;$lightStart=[long]::MaxValue;$scopedLight=0
    foreach($entry in [regex]::Matches($Log,'(?<stage>feature|light_init).executed chunk=(?<x>-?\d+),(?<z>-?\d+) dimension=minecraft:overworld [^\r\n]*?start_ns=(?<start>\d+) end_ns=(?<end>\d+)')){
        $x=[long]$entry.Groups['x'].Value;$z=[long]$entry.Groups['z'].Value
        if(-not (([Math]::Abs($x-1000) -le 13 -and [Math]::Abs($z+2000) -le 13) -or ([Math]::Abs($x+1000) -le 13 -and [Math]::Abs($z-2000) -le 13))){continue}
        if($entry.Groups['stage'].Value -eq 'feature'){$featureEnd=[Math]::Max($featureEnd,[long]$entry.Groups['end'].Value)}
        else{$lightStart=[Math]::Min($lightStart,[long]$entry.Groups['start'].Value);$scopedLight++}
    }
    if($scopedLight -ne 1458 -or $featureEnd -le 0 -or $lightStart -lt $featureEnd){throw 'Every original feature body must precede scoped light initialization'}
    return [ordered]@{terrains=1682;features=1458;initializations=1458;spawns=1250;snapshots=1250;replay_sha256=$Hash;frozen=$true;scope='Controlled terrain/decoration/light phases; not normal mixed-stage gameplay coverage'}
}
function Write-FeatureReplay([string]$LogPath,[string]$OutputPath) {
    $log=Get-Content -LiteralPath $LogPath -Raw
    $null=Assert-FeatureFixture $log 'record'
    if(Test-Path -LiteralPath $OutputPath){throw 'Replay evidence must be new'}
    $features=@([regex]::Matches($log,'fixture.feature_started chunk=(-?\d+,-?\d+) frozen=true')|ForEach-Object {$_.Groups[1].Value})
    [IO.File]::WriteAllText($OutputPath,([ordered]@{schema='worldgen-assist.feature-replay.v1';seed=8675309;features=$features}|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
    return Read-FeatureReplay $OutputPath
}
