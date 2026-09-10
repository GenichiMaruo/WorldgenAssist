param(
    [Parameter(Mandatory)][string[]]$FallbackRoots,
    [Parameter(Mandatory)][string]$VanillaRoot
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Read-Digests([string]$Log) {
    $map = @{}
    foreach ($entry in [regex]::Matches($Log, 'stage\.digest stage=noise chunk=(?<chunk>-?\d+,-?\d+) .*?digest=(?<digest>[a-f0-9]{64})\b')) {
        $chunk=$entry.Groups['chunk'].Value; $digest=$entry.Groups['digest'].Value
        if ($map.ContainsKey($chunk) -and $map[$chunk] -ne $digest) { throw "Conflicting digest: $chunk" }
        $map[$chunk]=$digest
    }
    return $map
}
if ((Get-Content (Join-Path $VanillaRoot 'summary.txt') -Raw) -notmatch '(?m)^success=True\s*$') { throw 'Baseline did not pass' }
$baselineText=Get-Content (Join-Path $VanillaRoot 'server/logs/latest.log') -Raw
if ($baselineText -match 'seeded_fixture\.(sent|applied) ') { throw 'Baseline dispatched remote work' }
$baseline=Read-Digests $baselineText
$baselineSources=Get-Content (Join-Path $VanillaRoot 'source-sha256.txt')
foreach ($caseRoot in $FallbackRoots) {
    $destination=Join-Path $caseRoot 'fallback-digest-comparison.json'
    if (Test-Path -LiteralPath $destination) { throw 'Refusing to overwrite comparison evidence' }
    if ((Get-Content (Join-Path $caseRoot 'summary.txt') -Raw) -notmatch '(?m)^success=True\s*$') { throw "Case did not pass: $caseRoot" }
    if (@(Compare-Object -ReferenceObject $baselineSources -DifferenceObject (Get-Content (Join-Path $caseRoot 'source-sha256.txt'))).Count -ne 0) { throw 'Source snapshots differ' }
    $log=Get-Content (Join-Path $caseRoot 'server/logs/latest.log') -Raw
    if ($log -match 'seeded_fixture\.applied ') { throw 'Fallback case installed remote work' }
    $actual=Read-Digests $log
    $required=@([regex]::Matches($log, 'seeded_fixture.result chunk=(?<chunk>-?\d+,-?\d+) status=(?:TIMED_OUT|RESULT_REJECTED|STALE_CONTEXT|DISCONNECTED)\b') |
        ForEach-Object { $_.Groups['chunk'].Value } | Sort-Object -Unique)
    if ($required.Count -eq 0) { throw 'No fallback terminal chunk to verify' }
    $rows=@($required | ForEach-Object {
        [ordered]@{chunk=$_;actual=$actual[$_];vanilla=$baseline[$_];equal=($actual.ContainsKey($_) -and $baseline.ContainsKey($_) -and $actual[$_] -eq $baseline[$_])}
    })
    $shared=@($actual.Keys | Where-Object { $baseline.ContainsKey($_) })
    $mismatches=@($shared | Where-Object { $actual[$_] -ne $baseline[$_] })
    $pass=$shared.Count -gt 0 -and $mismatches.Count -eq 0 -and @($rows | Where-Object { -not $_.equal }).Count -eq 0
    [ordered]@{fallback_root=[IO.Path]::GetFullPath($caseRoot);vanilla_root=[IO.Path]::GetFullPath($VanillaRoot);required_chunks=$rows;shared_chunks=$shared.Count;shared_mismatches=$mismatches;pass=$pass;performance='NOT_EVALUATED'} |
        ConvertTo-Json -Depth 5 | Tee-Object -FilePath $destination
    if (-not $pass) { throw 'Fallback digest missing or unequal' }
}
