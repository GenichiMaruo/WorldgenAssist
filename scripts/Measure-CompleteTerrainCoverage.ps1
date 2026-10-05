[CmdletBinding()]
param([Parameter(Mandatory)][string]$LogPath,[Parameter(Mandatory)][string]$OutputPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string]$ArtifactSha256)
# Offline only: join actual application events to original task completions.
# This helper is scoped to the ordinary, single-Overworld measured scenario.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$jobs=[Collections.Generic.Dictionary[string,string]]::new()
$noise=[Collections.Generic.HashSet[string]]::new()
$applied=[Collections.Generic.Dictionary[string,string]]::new()
$rows=[Collections.Generic.List[object]]::new()
$repeat=0;$audited=0
foreach($line in [IO.File]::ReadLines([IO.Path]::GetFullPath($LogPath))){
    if($line.Contains('job.sent id=') -and $line -match 'job\.sent id=(\S+) chunk=(-?\d+,-?\d+) '){$jobs[$Matches[1]]=$Matches[2]}
    if($line.Contains('CAWG_SCENARIO_MEASURED_BEGIN_') -and $line -match 'CAWG_SCENARIO_MEASURED_BEGIN_(\d+)'){
        if($repeat){throw 'Overlapping measured intervals'}
        $repeat=[int]$Matches[1];$noise.Clear();$applied.Clear();$audited=0;continue
    }
    if(-not $repeat){continue}
    if($line.Contains('stage.complete stage=noise ') -and $line -match 'stage\.complete stage=noise chunk=(-?\d+,-?\d+) '){[void]$noise.Add($Matches[1])}
    if($line.Contains('job.full_terrain_accepted ') -and $line.Contains('audited=true')){$audited++}
    if($line.Contains('job.complete id=') -and $line.Contains('work_kind=COMPLETE_TERRAIN remote_samples=98304 ') -and
        $line -match 'job\.complete id=(\S+) source=(remote|cache|prefetch) '){
        $id=$Matches[1];$source=$Matches[2]
        if(-not $jobs.ContainsKey($id)){throw "Application has no recorded original server assignment: $id"}
        $position=$jobs[$id]
        if($applied.ContainsKey($position)){throw "Repeated terrain application in one interval: $position"}
        $applied[$position]=$source
    }
    if($line.Contains('CAWG_SCENARIO_MEASURED_END_') -and $line -match 'CAWG_SCENARIO_MEASURED_END_(\d+)'){
        if([int]$Matches[1] -ne $repeat -or -not $noise.Count){throw 'Missing or mismatched measured interval'}
        $sources=[ordered]@{remote=0;cache=0;prefetch=0};$outside=0
        foreach($entry in $applied.GetEnumerator()){
            if($noise.Contains($entry.Key)){$sources[$entry.Value]++}else{$outside++}
        }
        $total=$sources.remote+$sources.cache+$sources.prefetch
        $rows.Add([ordered]@{repeat=$repeat;noise_tasks=$noise.Count;complete_applications=$total;
            complete_percent=100.0*$total/$noise.Count;by_source=$sources;
            early_percent_of_applications=if($total){100.0*($sources.cache+$sources.prefetch)/$total}else{0};
            applications_without_same_interval_noise=$outside;accepted_full_audits=$audited})
        $repeat=0
    }
}
if($repeat -or $rows.Count -ne 3){throw 'Expected exactly three closed measured intervals'}
$report=[ordered]@{schema='worldgen-assist.actual-complete-coverage.v1';artifact_sha256=$ArtifactSha256.ToUpperInvariant();
    source_log=[IO.Path]::GetFullPath($LogPath);source_log_sha256=(Get-FileHash -LiteralPath $LogPath).Hash;
    definition='Actual COMPLETE_TERRAIN application events with all98304 samples, all sources; original server-issued UUID/coordinates joined to NOISE completions in the same measured interval. Accepted full audits are a different event population. No runtime generation or critical-path inference.';
    repeats=$rows.ToArray()}
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath),($report|ConvertTo-Json -Depth 6))
$rows|ConvertTo-Json -Depth 5
