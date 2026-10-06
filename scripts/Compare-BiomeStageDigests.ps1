[CmdletBinding()]
param([Parameter(Mandatory)][string]$VanillaLog,[Parameter(Mandatory)][string]$AssistedLog,
    [Parameter(Mandatory)][string]$Output,[string]$Marker='CAWG_BIOME_CORRECTNESS_BEGIN',[switch]$RequireApplied)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$maps=@{};$applied=[Collections.Generic.List[object]]::new();$issues=[Collections.Generic.List[string]]::new()
foreach($mode in @('vanilla','assisted')){
    $map=@{};$marked=$false;$path=if($mode -eq 'vanilla'){$VanillaLog}else{$AssistedLog}
    foreach($line in [IO.File]::ReadLines([IO.Path]::GetFullPath($path))){
        if($line.Contains($Marker,[StringComparison]::Ordinal)){$marked=$true}
        if($line -match 'biome\.digest chunk=(-?\d+,-?\d+) digest=([a-f0-9]{64})'){
            $chunk=$Matches[1];$hash=$Matches[2]
            if($map.ContainsKey($chunk) -and $map[$chunk] -ne $hash){$issues.Add("Conflicting $mode biome digest: $chunk")}
            $map[$chunk]=$hash
        }
        if($mode -eq 'assisted' -and $line -match 'biome\.applied id=(\S+) chunk=(-?\d+,-?\d+) source=(\S+)'){
            $applied.Add([ordered]@{id=$Matches[1];chunk=$Matches[2];source=$Matches[3];after_marker=$marked})
        }
    }
    if(-not $marked){$issues.Add("Missing $mode workload marker: $Marker")};$maps[$mode]=$map
}
$shared=@($maps.vanilla.Keys|Where-Object {$maps.assisted.ContainsKey($_)})
if(-not $shared.Count){$issues.Add('No shared full biome digests')}
foreach($chunk in $shared){if($maps.vanilla[$chunk] -ne $maps.assisted[$chunk]){$issues.Add("Full biome difference: $chunk")}}
$counted=@($applied|Where-Object after_marker);$unpairedWarmup=@($applied|Where-Object {-not $_.after_marker -and -not $maps.vanilla.ContainsKey($_.chunk)})
if($RequireApplied -and -not $counted.Count){$issues.Add('No postmarker actual remote biome application')}
foreach($entry in $counted){
    if(-not $maps.vanilla.ContainsKey($entry.chunk) -or -not $maps.assisted.ContainsKey($entry.chunk)){$issues.Add("Applied biome counterpart missing: $($entry.chunk)")}
    elseif($maps.vanilla[$entry.chunk] -ne $maps.assisted[$entry.chunk]){$issues.Add("Applied biome differs: $($entry.chunk)")}
}
$summary=[ordered]@{schema='worldgen-assist.full-biome-parity.v1';success=($issues.Count -eq 0);shared=$shared.Count;
    vanilla_count=$maps.vanilla.Count;assisted_count=$maps.assisted.Count;postmarker_applied=$counted.Count;
    postmarker_early_peer=@($counted|Where-Object source -eq 'early_peer').Count;postmarker_complete_cache=@($counted|Where-Object source -eq 'complete_cache').Count;
    excluded_unpaired_premarker=$unpairedWarmup.Count;applied=$counted;issues=$issues.ToArray();
    scope='Every shared center: all quart voxels and every section palette name; EVERY postmarker application must have an original counterpart and match'}
[IO.File]::WriteAllText([IO.Path]::GetFullPath($Output),($summary|ConvertTo-Json -Depth 7))
Write-Output "BIOME_PARITY success=$($summary.success) shared=$($summary.shared) applied=$($summary.postmarker_applied) early=$($summary.postmarker_early_peer)"
if($issues.Count){exit 1}
