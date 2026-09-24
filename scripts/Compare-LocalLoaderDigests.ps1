param(
    [Parameter(Mandatory=$true)][string]$AssistedLog,
    [Parameter(Mandatory=$true)][string]$VanillaLog,
    [Parameter(Mandatory=$true)][string]$OutFile,
    [ValidateSet('all','minecraft:overworld','minecraft:the_nether','minecraft:the_end')]
    [string]$Dimension = 'minecraft:overworld'
)

$ErrorActionPreference = 'Stop'

function Read-Evidence([string]$Path) {
    $digests = @{}
    $jobs = @{}
    $ownerDimensions = @{}
    $remote = [System.Collections.Generic.List[string]]::new()
    $issues = [System.Collections.Generic.List[string]]::new()
    foreach ($line in [System.IO.File]::ReadLines((Resolve-Path -LiteralPath $Path))) {
        if ($line -match '\[CAWG\] stage\.digest stage=noise chunk=(-?\d+,-?\d+) .*digest=([0-9a-f]{64}).*dimension=([a-z0-9_:]+)') {
            $key = "$($Matches[3])/$($Matches[1])"
            if ($Dimension -ne 'all' -and -not $key.StartsWith("$Dimension/", [StringComparison]::Ordinal)) { continue }
            if ($digests.ContainsKey($key) -and $digests[$key] -ne $Matches[2]) {
                $issues.Add("conflicting digest: $key")
            }
            $digests[$key] = $Matches[2]
        }
        if ($line -match '\[CAWG\] worker.dimension_changed owner=([a-f0-9-]+) from=[a-z0-9_:]+ to=([a-z0-9_:]+)') {
            $ownerDimensions[$Matches[1]] = $Matches[2]
        }
        if ($line -match '\[CAWG\] job.sent id=([a-f0-9-]+) chunk=(-?\d+,-?\d+) .*owner=([a-f0-9-]+)') {
            $ownerDimension = if ($ownerDimensions.ContainsKey($Matches[3])) { $ownerDimensions[$Matches[3]] } else { 'minecraft:overworld' }
            $jobs[$Matches[1]] = "$ownerDimension/$($Matches[2])"
        }
        if ($line -match '\[CAWG\] job.complete id=([a-f0-9-]+) source=remote') {
            if ($jobs.ContainsKey($Matches[1])) {
                $key = $jobs[$Matches[1]]
                if ($Dimension -eq 'all' -or $key.StartsWith("$Dimension/", [StringComparison]::Ordinal)) { $remote.Add($key) }
            } else {
                $issues.Add("remote completion without sent job: $($Matches[1])")
            }
        }
    }
    return @{ digests=$digests; remote=$remote; issues=$issues }
}

$assisted = Read-Evidence $AssistedLog
$vanilla = Read-Evidence $VanillaLog
$shared = @($assisted.digests.Keys | Where-Object { $vanilla.digests.ContainsKey($_) })
$different = @($shared | Where-Object { $assisted.digests[$_] -ne $vanilla.digests[$_] })
$missingApplied = @($assisted.remote | Where-Object { -not $vanilla.digests.ContainsKey($_) })
$differentApplied = @($assisted.remote | Where-Object {
    $vanilla.digests.ContainsKey($_) -and $assisted.digests[$_] -ne $vanilla.digests[$_]
})
$issues = @($assisted.issues) + @($vanilla.issues)
if ($assisted.digests.Count -eq 0 -or $vanilla.digests.Count -eq 0 -or $shared.Count -eq 0 -or $assisted.remote.Count -eq 0) {
    $issues += 'missing digest or remotely applied result'
}
if ($different.Count -gt 0) { $issues += "different shared digests: $($different.Count)" }
if ($missingApplied.Count -gt 0) { $issues += "applied chunks absent from vanilla: $($missingApplied.Count)" }
if ($differentApplied.Count -gt 0) { $issues += "different applied digests: $($differentApplied.Count)" }

$result = [ordered]@{
    schema = 'worldgen-assist.local-loader-digest-comparison.v1'
    dimension = $Dimension
    status = if ($issues.Count -eq 0) { 'PASS' } else { 'FAIL' }
    assisted_log = (Resolve-Path -LiteralPath $AssistedLog).Path
    vanilla_log = (Resolve-Path -LiteralPath $VanillaLog).Path
    assisted_log_sha256 = (Get-FileHash -LiteralPath $AssistedLog -Algorithm SHA256).Hash
    vanilla_log_sha256 = (Get-FileHash -LiteralPath $VanillaLog -Algorithm SHA256).Hash
    assisted_digests = $assisted.digests.Count
    vanilla_digests = $vanilla.digests.Count
    shared_digests = $shared.Count
    different_shared = $different.Count
    remote_applied = $assisted.remote.Count
    missing_applied = $missingApplied.Count
    different_applied = $differentApplied.Count
    issues = $issues
}
$directory = Split-Path -Parent $OutFile
if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutFile -Encoding UTF8
$result | ConvertTo-Json -Depth 5
if ($issues.Count -gt 0) { exit 1 }
