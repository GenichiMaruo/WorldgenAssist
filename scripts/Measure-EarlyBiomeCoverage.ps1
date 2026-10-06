[CmdletBinding()]
param([Parameter(Mandatory)][string]$LogPath,[Parameter(Mandatory)][string]$OutputPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string]$ArtifactSha256)
# Existing closed logs only. BIO-only dependency chunks are reported separately.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$noise=[Collections.Generic.HashSet[string]]::new()
$biomes=[Collections.Generic.Dictionary[string,object]]::new()
$rows=[Collections.Generic.List[object]]::new();$repeat=0
foreach($line in [IO.File]::ReadLines([IO.Path]::GetFullPath($LogPath))){
    if($line -match 'CAWG_SCENARIO_MEASURED_BEGIN_(\d+)\b'){
        if($repeat){throw 'Overlapping measured intervals'}
        $repeat=[int]$Matches[1];$noise.Clear();$biomes.Clear();continue
    }
    if(-not $repeat){continue}
    if($line -match 'stage\.complete stage=noise chunk=(-?\d+,-?\d+)\b'){[void]$noise.Add($Matches[1])}
    if($line -match 'biome\.applied id=(\S+) chunk=(-?\d+,-?\d+) source=(early_peer|complete_cache) apply_ms=(\S+)'){
        $id=$Matches[1];$position=$Matches[2];$source=$Matches[3];$token=$Matches[4]
        $duration=0.0
        if(-not [double]::TryParse($token,[Globalization.NumberStyles]::Float,[Globalization.CultureInfo]::InvariantCulture,[ref]$duration) -or
            -not [double]::IsFinite($duration) -or $duration -lt 0){throw 'Invalid whole BIO duration token'}
        if($biomes.ContainsKey($position)){throw 'Repeated BIO application in one interval'}
        $biomes[$position]=[pscustomobject]@{id=$id;source=$source;duration_ms=$duration}
    }
    if($line -match 'CAWG_SCENARIO_MEASURED_END_(\d+)\b'){
        if([int]$Matches[1] -ne $repeat -or $noise.Count -ne 10658){throw 'Mismatched measured workload'}
        $within=0;$early=0;$cache=0;$sum=0.0
        foreach($entry in $biomes.GetEnumerator()){
            if($noise.Contains($entry.Key)){$within++}
            if($entry.Value.source -eq 'early_peer'){$early++}else{$cache++}
            $sum+=$entry.Value.duration_ms
        }
        $rows.Add([ordered]@{repeat=$repeat;noise_tasks=$noise.Count;biome_applications=$biomes.Count;
            matched_noise_tasks=$within;percent_noise_tasks=100.0*$within/$noise.Count;
            biome_only_or_other_interval=$biomes.Count-$within;early_peer=$early;complete_cache=$cache;
            apply_mean_ms=if($biomes.Count){$sum/$biomes.Count}else{0};apply_sum_ms=$sum})
        $repeat=0
    }
}
if($repeat -or $rows.Count -ne 3){throw 'Expected three closed measured windows'}
$report=[ordered]@{schema='worldgen-assist.actual-biome-coverage.v1';artifact_sha256=$ArtifactSha256.ToUpperInvariant();
    source_log=[IO.Path]::GetFullPath($LogPath);source_log_sha256=(Get-FileHash -LiteralPath $LogPath).Hash;
    definition='Actual successful BIO reuse in closed measured windows. Percent denominator is the SAME-window10658 NOISE tasks, NOT all BIO tasks. BIO-only/dependency/other-interval coordinates separate. Apply durations are overlapping preflight/write event spans, not CPU time or a critical path.';
    repeats=$rows.ToArray()}
if(Test-Path -LiteralPath $OutputPath){throw 'Analysis output already exists'}
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath),($report|ConvertTo-Json -Depth 6))
