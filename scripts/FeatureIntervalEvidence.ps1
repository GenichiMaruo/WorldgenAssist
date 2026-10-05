# Pure offline aggregation shared by correctness and performance gates.
function Get-FeatureIntervalEvidence([string]$Log){
    $intervals=@([regex]::Matches($Log,'feature.executed chunk=(?<x>-?\d+),(?<z>-?\d+) dimension=minecraft:overworld [^\r\n]*?thread=(?<thread>\S+) start_ns=(?<start>\d+) end_ns=(?<end>\d+)')|ForEach-Object {
        [pscustomobject]@{x=[long]$_.Groups['x'].Value;z=[long]$_.Groups['z'].Value;thread=$_.Groups['thread'].Value;start=[long]$_.Groups['start'].Value;end=[long]$_.Groups['end'].Value}
    }|Sort-Object start)
    $active=[Collections.Generic.List[object]]::new();$overlaps=0;$conflicts=0;$peak=0;$bodyNs=0.0
    foreach($interval in $intervals){
        if($interval.end -lt $interval.start){throw 'Invalid feature interval'}
        $bodyNs+=$interval.end-$interval.start
        for($i=$active.Count-1;$i -ge 0;$i--){if($active[$i].end -le $interval.start){$active.RemoveAt($i)}}
        foreach($other in $active){
            if($other.thread -ne $interval.thread){
                $overlaps++
                if([Math]::Abs($other.x-$interval.x) -le 16 -and [Math]::Abs($other.z-$interval.z) -le 16){$conflicts++}
            }
        }
        $active.Add($interval);$peak=[Math]::Max($peak,$active.Count)
    }
    $light=@([regex]::Matches($Log,'light_init.executed chunk=(?<x>-?\d+),(?<z>-?\d+) dimension=minecraft:overworld [^\r\n]*?thread=(?<thread>\S+) start_ns=(?<start>\d+) end_ns=(?<end>\d+) failed=(?<failed>true|false)')|ForEach-Object {
        if($_.Groups['failed'].Value -eq 'true'){throw 'Original light initialization failed'}
        [pscustomobject]@{x=[long]$_.Groups['x'].Value;z=[long]$_.Groups['z'].Value;thread=$_.Groups['thread'].Value;start=[long]$_.Groups['start'].Value;end=[long]$_.Groups['end'].Value;radius=0;kind='light'}
    })
    $combined=@(@($intervals|ForEach-Object {
        [pscustomobject]@{x=$_.x;z=$_.z;thread=$_.thread;start=$_.start;end=$_.end;radius=8;kind='feature'}
    })+$light|Sort-Object start)
    $active.Clear();$featureLight=0;$lightLight=0
    foreach($interval in $combined){
        if($interval.end -lt $interval.start){throw 'Invalid owned light interval'}
        for($i=$active.Count-1;$i -ge 0;$i--){if($active[$i].end -le $interval.start){$active.RemoveAt($i)}}
        foreach($other in $active){
            $radius=$other.radius+$interval.radius
            if([Math]::Abs($other.x-$interval.x) -le $radius -and [Math]::Abs($other.z-$interval.z) -le $radius){
                if($other.kind -ne $interval.kind){$featureLight++}
                elseif($interval.kind -eq 'light'){$lightLight++}
            }
        }
        $active.Add($interval)
    }
    return [ordered]@{bodies=$intervals.Count;overlapping_pairs=$overlaps;conflicting_pairs=$conflicts;peak_bodies=$peak;summed_body_ms=$bodyNs/1000000.0;light_initializations=$light.Count;feature_light_conflicting_pairs=$featureLight;light_light_conflicting_pairs=$lightLight;definition='FEATURES original synchronous invocation intervals; light initialization original invocation through asynchronous future completion. Light duration is ownership, not CPU time. Vanilla allows async light overlap; enabled scheduler excludes footprint conflicts.'}
}
