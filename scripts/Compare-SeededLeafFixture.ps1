param(
    [Parameter(Mandatory)][string]$AssistedRoot,
    [Parameter(Mandatory)][string]$VanillaRoot,
    [ValidatePattern('^[a-zA-Z0-9_-]+\.json$')][string]$ComparisonFileName='digest-comparison.json'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$assisted = Get-Content -LiteralPath (Join-Path $AssistedRoot 'server/logs/latest.log') -Raw
$vanilla = Get-Content -LiteralPath (Join-Path $VanillaRoot 'server/logs/latest.log') -Raw
$digestPattern = 'stage\.digest stage=noise chunk=(?<chunk>-?\d+,-?\d+) .*?digest=(?<digest>[a-f0-9]{64})\b'
function Digests([string]$Log) {
    $map = @{}
    foreach ($match in [regex]::Matches($Log, $digestPattern)) {
        $chunk = $match.Groups['chunk'].Value
        $hash = $match.Groups['digest'].Value
        if ($map.ContainsKey($chunk) -and $map[$chunk] -ne $hash) { throw "Conflicting digest for $chunk" }
        $map[$chunk] = $hash
    }
    return $map
}
$assistedMap = Digests $assisted
$vanillaMap = Digests $vanilla
$applied = @([regex]::Matches($assisted, 'seeded_fixture\.applied id=\S+ chunk=(?<chunk>-?\d+,-?\d+)') |
    ForEach-Object { $_.Groups['chunk'].Value } | Sort-Object -Unique)
if ($applied.Count -eq 0) { throw 'No actual field installation was logged; READY alone is insufficient.' }
$assistedSources = Get-Content -LiteralPath (Join-Path $AssistedRoot 'source-sha256.txt')
$vanillaSources = Get-Content -LiteralPath (Join-Path $VanillaRoot 'source-sha256.txt')
$differences = @(Compare-Object -ReferenceObject $assistedSources -DifferenceObject $vanillaSources)
if ($differences.Count -ne 0) { throw 'Source snapshots differ; comparison is not accepted.' }
$rows = @($applied | ForEach-Object {
    $chunk = $_
    $equal = $assistedMap.ContainsKey($chunk) -and $vanillaMap.ContainsKey($chunk) -and $assistedMap[$chunk] -eq $vanillaMap[$chunk]
    [ordered]@{ chunk = $chunk; assisted = $assistedMap[$chunk]; vanilla = $vanillaMap[$chunk]; equal = $equal }
})
$shared = @($assistedMap.Keys | Where-Object { $vanillaMap.ContainsKey($_) })
$sharedMismatches = @($shared | Where-Object { $assistedMap[$_] -ne $vanillaMap[$_] })
$passed = @($rows | Where-Object { -not $_.equal }).Count -eq 0 -and $sharedMismatches.Count -eq 0
$report = [ordered]@{
    assisted_root = [IO.Path]::GetFullPath($AssistedRoot); vanilla_root = [IO.Path]::GetFullPath($VanillaRoot)
    applied_chunks = $rows; shared_chunks = $shared.Count; shared_mismatches = $sharedMismatches
    pass = $passed; performance = 'NOT_EVALUATED'
}
$destination = Join-Path $AssistedRoot $ComparisonFileName
if (Test-Path -LiteralPath $destination) { throw 'Refusing to overwrite previous comparison evidence' }
$report | ConvertTo-Json -Depth 5 | Tee-Object -FilePath $destination
if (-not $passed) { throw 'Missing or unequal vanilla/assisted NOISE digest' }
