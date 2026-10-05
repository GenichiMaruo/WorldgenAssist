[CmdletBinding()]
param([Parameter(Mandatory)][string]$BaselineRoot,[Parameter(Mandatory)][string]$CandidateRoot,
    [Parameter(Mandatory)][string]$OutputRoot,[switch]$RequireParallel)
# Offline only. Require every fixture coordinate, rather than a shared subset.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'FeatureIntervalEvidence.ps1')
. (Join-Path $PSScriptRoot 'FeatureFixtureEvidence.ps1')
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$evidenceBase=[IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts'))+[IO.Path]::DirectorySeparatorChar
foreach($path in @($BaselineRoot,$CandidateRoot,$OutputRoot)){
    if(-not [IO.Path]::GetFullPath($path).StartsWith($evidenceBase,[StringComparison]::OrdinalIgnoreCase)){throw 'Evidence must remain under test-artifacts'}
}
if(Test-Path -LiteralPath $OutputRoot){throw 'OutputRoot must be new'}
New-Item -ItemType Directory -Path $OutputRoot|Out-Null
$issues=[Collections.Generic.List[string]]::new()
function Read-Evidence([string]$Root){
    $native=Test-Path -LiteralPath (Join-Path $Root 'result.json')
    $resultName=if($native){'result.json'}else{'scenario-result.json'}
    $result=Get-Content -LiteralPath (Join-Path $Root $resultName) -Raw|ConvertFrom-Json
    if(-not $result.success -or -not $result.cleanup_safe -or -not $result.loopback_only -or -not $result.decoration_digest -or $result.players -ne 2){throw "Incomplete runtime: $Root"}
    if(-not $native -and ($result.purpose -ne 'correctness' -or $result.dimension -ne 'overworld' -or $result.source_manifest_before_sha256 -ne $result.source_manifest_after_sha256)){throw 'Physical source identity/scope is invalid'}
    $logRoot=if($native){$Root}else{Join-Path $Root 'remote-evidence'}
    $log=Get-Content -LiteralPath (Join-Path $logRoot 'latest.log') -Raw
    if(-not $result.feature_fixture){throw 'Recorded/replayed frozen fixture required for current comparison'}
    $fixture=Assert-FeatureFixture $log $result.feature_replay_sha256
    if($result.feature_replay_sha256 -ne 'record'){
        $replay=Read-FeatureReplay (Join-Path $logRoot 'feature-replay.json')
        if($replay.sha256 -ne $result.feature_replay_sha256){throw 'Retained replay hash differs'}
    }
    if($log -match 'saved_structures.failed|outside guarded stock|message reservation exceeded|stage.failed|Mixin apply failed|Encountered an unexpected exception|Exception generating new chunk'){throw "Runtime failure marker: $Root"}
    if($log -notmatch 'saved_structures.complete chunks=[1-9][0-9]*'){throw 'Saved structure completion proof missing'}
    if($log -notmatch 'saved_light.complete chunks=[1-9][0-9]*'){throw 'Saved light completion proof missing'}
    $required=@(Get-Content -LiteralPath (Join-Path $logRoot 'decoration-required-chunks.json') -Raw|ConvertFrom-Json)
    if($required.Count -ne 882 -or @($required|Sort-Object -Unique).Count -ne 882 -or @($required|Where-Object {$_ -notmatch '^-?\d+,-?\d+$'}).Count){throw 'Required coordinate inventory is invalid'}
    $maps=@{}
    foreach($stage in @('noise','decoration','saved_structures','saved_light')){
        $map=@{};$format=if($stage -eq 'noise'){2}else{1}
        foreach($entry in [regex]::Matches($log,"stage\.digest stage=$stage chunk=(?<chunk>-?\d+,-?\d+) format=$format algorithm=SHA-256 digest=(?<digest>[0-9a-f]{64})(?<components>[^\r\n]*?) dimension=minecraft:overworld")){
            $key=$entry.Groups['chunk'].Value;$digest=$entry.Groups['digest'].Value
            if($map.ContainsKey($key) -and $map[$key].digest -cne $digest){throw "Different repeated $stage digest at $key"}
            $map[$key]=[ordered]@{digest=$digest;components=$entry.Groups['components'].Value.Trim()}
        }
        $maps[$stage]=$map
    }
    return [pscustomobject]@{result=$result;native=$native;required=$required;maps=$maps;fixture=$fixture;replay=if($result.feature_replay_sha256 -ne 'record'){$replay}else{$null};log=$log;overlap=(Get-FeatureIntervalEvidence $log)}
}
$comparisons=@();$parallel=$null
try{
    $baseline=Read-Evidence $BaselineRoot;$candidate=Read-Evidence $CandidateRoot
    $recorded=@([regex]::Matches($baseline.log,'fixture.feature_started chunk=(-?\d+,-?\d+) frozen=true')|ForEach-Object {$_.Groups[1].Value})
    if($baseline.result.feature_replay_sha256 -ne 'record' -or $null -eq $candidate.replay -or ($recorded -join ';') -cne ($candidate.replay.features -join ';')){throw 'Candidate must replay the original exact feature sequence'}
    if($baseline.native -ne $candidate.native){throw 'Loader evidence kinds differ'}
    if($baseline.native){
        if($baseline.result.loader -ne $candidate.result.loader -or $baseline.result.mod_sha256 -ne $candidate.result.mod_sha256){throw 'Native loader/JAR identity differs'}
    }else{
        if($baseline.result.artifact_sha256 -ne $candidate.result.artifact_sha256 -or $baseline.result.source_manifest_after_sha256 -ne $candidate.result.source_manifest_after_sha256){throw 'Physical source/JAR identity differs'}
    }
    if((($baseline.required|Sort-Object)-join ';') -cne (($candidate.required|Sort-Object)-join ';')){throw 'Required coordinate sets differ'}
    foreach($stage in @('noise','decoration','saved_structures','saved_light')){
        $missing=@();$changed=@();$equal=0
        foreach($key in $baseline.required){
            if(-not $baseline.maps[$stage].ContainsKey($key) -or -not $candidate.maps[$stage].ContainsKey($key)){$missing+=$key;continue}
            $a=$baseline.maps[$stage][$key];$b=$candidate.maps[$stage][$key]
            if($a.digest -cne $b.digest){$changed+=[ordered]@{chunk=$key;baseline=$a;candidate=$b}}else{$equal++}
        }
        if($missing.Count -or $changed.Count){$issues.Add("${stage}: missing=$($missing.Count), changed=$($changed.Count)")}
        $comparisons+=[ordered]@{stage=$stage;required=882;equal=$equal;missing=$missing;changed=$changed}
    }
    $parallel=$candidate.overlap
    if($parallel.conflicting_pairs){$issues.Add('Conflicting feature bodies overlapped')}
    if(-not $parallel.light_initializations -or $parallel.feature_light_conflicting_pairs -or $parallel.light_light_conflicting_pairs){$issues.Add('Light initialization coverage or conflict ownership failed')}
    if($RequireParallel -and ($candidate.result.feature_backend -ne 'parallel' -or $parallel.overlapping_pairs -eq 0)){$issues.Add('Actual parallel feature body overlap was not exercised')}
}catch{$issues.Add($_.Exception.ToString())}
$summary=[ordered]@{schema='worldgen-assist.decoration-comparison.v1';success=($issues.Count -eq 0);baseline_root=$BaselineRoot;candidate_root=$CandidateRoot;comparisons=$comparisons;parallel=$parallel;issues=@($issues);scope='Every required final-decoration coordinate, saved FULL structure tag and exact saved light arrays/flag; not final gameplay/mob/rendered state'}
$summary|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $OutputRoot 'summary.json') -Encoding utf8
Write-Output "DECORATION_COMPARISON success=$($summary.success) summary=$(Join-Path $OutputRoot 'summary.json')"
if(-not $summary.success){exit 1}
