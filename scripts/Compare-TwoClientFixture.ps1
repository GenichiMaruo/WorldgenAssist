[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AssistedRoot,
    [Parameter(Mandatory)][string]$VanillaRoot,
    [ValidateSet('fixture', 'trusted-raw')][string]$Route = 'fixture',
    [ValidateSet('simultaneous', 'disconnect')][string]$Mode = 'simultaneous'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-Digests([string]$Log) {
    $map = @{}
    foreach ($entry in [regex]::Matches($Log, 'stage\.digest stage=noise chunk=(?<chunk>-?\d+,-?\d+) .*?digest=(?<digest>[a-f0-9]{64})\b')) {
        $chunk = $entry.Groups['chunk'].Value; $digest = $entry.Groups['digest'].Value
        if ($map.ContainsKey($chunk) -and $map[$chunk] -ne $digest) { throw "Conflicting digest for $chunk" }
        $map[$chunk] = $digest
    }
    return $map
}
function Same-File([string]$Name) {
    $left = Get-Content -LiteralPath (Join-Path $AssistedRoot $Name) -Raw
    $right = Get-Content -LiteralPath (Join-Path $VanillaRoot $Name) -Raw
    if ($Name -like 'source-sha256*') {
        # Harness revisions are retained in each run's manifest/copies, but
        # output equivalence requires the same production/test/build inputs.
        $left = ($left -split '\r?\n' | Where-Object { $_ -notmatch '^[A-Fa-f0-9]{64}  scripts[\\/]' }) -join "`n"
        $right = ($right -split '\r?\n' | Where-Object { $_ -notmatch '^[A-Fa-f0-9]{64}  scripts[\\/]' }) -join "`n"
    }
    if ($left -ne $right) { throw "Evidence differs: $Name" }
}
function Owner-Map {
    $path = Join-Path $AssistedRoot 'remote-evidence/owner-map.json'
    if (-not (Test-Path -LiteralPath $path)) { throw 'Assisted owner map is missing' }
    return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
}
function Owner-Records([string]$Log, [string]$Marker, [string]$Owner) {
    $escaped = [regex]::Escape($Owner)
    $records = [Collections.Generic.List[object]]::new()
    foreach ($entry in [regex]::Matches($Log, '(?m)^.*$')) {
        $line = $entry.Value
        if ($line -notmatch $Marker -or $line -notmatch ('owner=' + $escaped + '\b')) { continue }
        $chunk = [regex]::Match($line, 'chunk=(?<chunk>-?\d+,-?\d+)')
        if ($chunk.Success) {
            $id = [regex]::Match($line, 'id=(?<id>\S+)')
            $status = [regex]::Match($line, 'status=(?<status>\S+)')
            $records.Add([pscustomobject]@{
                chunk=$chunk.Groups['chunk'].Value; line=$line; index=$entry.Index
                id=if($id.Success){$id.Groups['id'].Value}else{$null}
                status=if($status.Success){$status.Groups['status'].Value}else{$null}
            })
        }
    }
    return $records.ToArray()
}
function Assert-OwnerArea([object[]]$Records, [int]$CenterX, [int]$CenterZ, [string]$Label) {
    foreach ($record in $Records) {
        $parts = $record.chunk.Split(',')
        $x = [int]$parts[0]; $z = [int]$parts[1]
        # Match generated 26.2 ChunkTrackingView.isInViewDistance, without the
        # extra neighbor padding. Both staged players have effective view ten.
        $dx = [Math]::Max(0, [Math]::Abs($x - $CenterX) - 1)
        $dz = [Math]::Max(0, [Math]::Abs($z - $CenterZ) - 1)
        if (($dx * $dx + $dz * $dz) -ge 100) {
            throw "$Label chunk outside its fixed owner area: $($record.chunk)"
        }
    }
}
function Id-Records([string]$Log, [string]$Marker) {
    $records = [Collections.Generic.List[object]]::new()
    foreach ($entry in [regex]::Matches($Log, '(?m)^.*$')) {
        $line = $entry.Value
        if ($line -notmatch $Marker) { continue }
        $id = [regex]::Match($line, 'id=(?<id>\S+)')
        if (-not $id.Success) { continue }
        $chunk = [regex]::Match($line, 'chunk=(?<chunk>-?\d+,-?\d+)')
        $records.Add([pscustomobject]@{id=$id.Groups['id'].Value;chunk=if($chunk.Success){$chunk.Groups['chunk'].Value}else{$null};line=$line;index=$entry.Index})
    }
    return $records.ToArray()
}
function First-MatchIndex([string]$Log, [string]$Pattern) {
    $match = [regex]::Match($Log, $Pattern)
    if ($match.Success) { return $match.Index }
    return -1
}
function Overlap-Evidence {
    $path = Join-Path $AssistedRoot 'remote-evidence/dispatch-overlap.json'
    if (-not (Test-Path -LiteralPath $path)) { throw 'Matched dispatch-overlap evidence is missing' }
    return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
}
function Assert-PairOverlap([object]$SentA, [object]$SentB, [object[]]$TerminalA, [object[]]$TerminalB, [string]$Label) {
    $aTerminal = @($TerminalA | Where-Object { $_.index -gt $SentA.index } | Sort-Object index | Select-Object -First 1)
    $bTerminal = @($TerminalB | Where-Object { $_.index -gt $SentB.index } | Sort-Object index | Select-Object -First 1)
    if (($aTerminal.Count -ne 0 -and $SentB.index -ge $aTerminal[0].index) -or ($bTerminal.Count -ne 0 -and $SentA.index -ge $bTerminal[0].index)) {
        throw "$Label jobs did not overlap before their matching terminal events"
    }
    return [pscustomobject]@{sent_a=$SentA;sent_b=$SentB;terminal_a=if($aTerminal.Count){$aTerminal[0]}else{$null};terminal_b=if($bTerminal.Count){$bTerminal[0]}else{$null}}
}

foreach ($root in @($AssistedRoot, $VanillaRoot)) {
    if ((Get-Content -LiteralPath (Join-Path $root 'summary.txt') -Raw) -notmatch '(?m)^success=True\s*$') { throw "Fixture did not pass: $root" }
}
if ((Get-Content -LiteralPath (Join-Path $AssistedRoot 'summary.txt') -Raw) -notmatch ('(?m)^route=' + [regex]::Escape($Route) + '\s*$')) { throw 'Assisted route mismatch' }
# Vanilla disables both assistance routes, so one independent same-source
# baseline can validate either route. Require its actual mode and no dispatch.
if ((Get-Content -LiteralPath (Join-Path $VanillaRoot 'summary.txt') -Raw) -notmatch '(?m)^mode=vanilla\s*$') { throw 'Baseline must be a vanilla run' }
Same-File 'source-sha256.txt'
Same-File 'source-sha256-after.txt'
Same-File 'installed-jar-sha256.json'
foreach ($root in @($AssistedRoot, $VanillaRoot)) {
    if (@(Compare-Object -ReferenceObject (Get-Content -LiteralPath (Join-Path $root 'source-sha256.txt')) -DifferenceObject (Get-Content -LiteralPath (Join-Path $root 'source-sha256-after.txt'))).Count -ne 0) {
        throw "Source or build input changed during fixture: $root"
    }
}
$destination = Join-Path $AssistedRoot ('two-client-' + $Mode + '-digest-comparison.json')
if (Test-Path -LiteralPath $destination) { throw 'Refusing to overwrite previous comparison evidence' }

$assistedText = Get-Content -LiteralPath (Join-Path $AssistedRoot 'server/logs/latest.log') -Raw
$vanillaText = Get-Content -LiteralPath (Join-Path $VanillaRoot 'server/logs/latest.log') -Raw
if ($vanillaText -match '(?:seeded_fixture\.(?:sent|applied)|job\.sent) ') { throw 'Vanilla baseline dispatched assistance' }
$overlapEvidence = Overlap-Evidence
if ($overlapEvidence.route -ne $Route) { throw 'Dispatch overlap route does not match comparator route' }
if ($Route -eq 'fixture' -and $vanillaText -match 'seeded_fixture\.(sent|applied) ') { throw 'Vanilla baseline dispatched fixture work' }
if ($Route -eq 'trusted-raw') {
    if ($vanillaText -match 'job\.sent ') { throw 'Trusted-raw vanilla baseline dispatched remote work' }
    if ($assistedText -match 'seeded_fixture\.(sent|applied) ') { throw 'Trusted-raw evidence used fixture work' }
    if ($assistedText -match 'prediction\.sent ') { throw 'Trusted-raw direct-job fixture unexpectedly enabled prediction' }
    $owners = Owner-Map
    $ownerA = [string]$owners.FixtureOwnerA; $ownerB = [string]$owners.FixtureOwnerB
    if ([string]::IsNullOrWhiteSpace($ownerA) -or [string]::IsNullOrWhiteSpace($ownerB) -or $ownerA -eq $ownerB) { throw 'Invalid trusted-raw owner mapping' }
    $registerA = First-MatchIndex $assistedText ('worker\.register owner=' + [regex]::Escape($ownerA) + ' status=ACCEPTED')
    $registerB = First-MatchIndex $assistedText ('worker\.register owner=' + [regex]::Escape($ownerB) + ' status=ACCEPTED')
    if ($registerA -lt 0 -or $registerB -lt 0) { throw 'Both trusted-raw owners must register as accepted workers' }
    $sentA = @(Owner-Records $assistedText 'job\.sent' $ownerA)
    $sentB = @(Owner-Records $assistedText 'job\.sent' $ownerB)
    $firstSentA = @($sentA | Where-Object { $_.id -eq $overlapEvidence.owner_a.id -and $_.chunk -eq $overlapEvidence.owner_a.chunk } | Select-Object -First 1)
    $firstSentB = @($sentB | Where-Object { $_.id -eq $overlapEvidence.owner_b.id -and $_.chunk -eq $overlapEvidence.owner_b.chunk } | Select-Object -First 1)
    if ($firstSentA.Count -ne 1 -or $firstSentB.Count -ne 1) { throw 'Matched trusted-raw sent pair is absent from the server log' }
    Assert-OwnerArea $sentA 1000 -2000 'Trusted-raw owner A dispatched'
    Assert-OwnerArea $sentB -1000 2000 'Trusted-raw owner B dispatched'
    $completions = @(Id-Records $assistedText 'job\.complete')
    $fallbacks = @(Id-Records $assistedText 'job\.fallback_local')
    $pair = Assert-PairOverlap $firstSentA[0] $firstSentB[0] @($completions + $fallbacks | Where-Object { $_.id -eq $firstSentA[0].id }) @($completions + $fallbacks | Where-Object { $_.id -eq $firstSentB[0].id }) 'Trusted-raw matched pair'
    $requiredChunks = @($firstSentA[0].chunk, $firstSentB[0].chunk)
    if ($Mode -eq 'simultaneous') {
        if (@($completions | Where-Object { $_.id -eq $firstSentA[0].id }).Count -eq 0 -or @($completions | Where-Object { $_.id -eq $firstSentB[0].id }).Count -eq 0) {
            throw 'Both trusted-raw sent jobs must complete remotely'
        }
    } else {
        $cleanupIndex = $assistedText.IndexOf('Two-client fixture complete')
        if ($cleanupIndex -lt 0) { throw 'Trusted-raw cleanup boundary is missing' }
        $disconnectIndex = First-MatchIndex $assistedText ('worker\.disconnect owner=' + [regex]::Escape($ownerA) + ' cancelled_jobs=')
        if ($disconnectIndex -lt 0 -or $disconnectIndex -ge $cleanupIndex) { throw 'Trusted-raw owner A disconnect was not observed before cleanup' }
        $fallbackA = @($fallbacks | Where-Object { $_.id -eq $firstSentA[0].id -and $_.index -gt $disconnectIndex -and $_.index -lt $cleanupIndex } | Select-Object -First 1)
        if ($fallbackA.Count -ne 1) { throw 'Trusted-raw owner A sent job did not fall back after disconnect' }
        if (@($fallbacks | Where-Object { $_.id -eq $firstSentB[0].id -and $_.index -lt $cleanupIndex }).Count -ne 0) { throw 'Trusted-raw owner B was cancelled by owner A disconnect' }
        $completeB = @($completions | Where-Object { $_.id -eq $firstSentB[0].id -and $_.index -gt $fallbackA[0].index -and $_.index -lt $cleanupIndex } | Select-Object -First 1)
        if ($completeB.Count -ne 1) { throw 'Trusted-raw owner B did not complete after owner A fallback' }
    }
    $assistedDigests = Read-Digests $assistedText; $vanillaDigests = Read-Digests $vanillaText
    $rows = @($requiredChunks | Sort-Object -Unique | ForEach-Object {
        [ordered]@{chunk=$_;assisted=$assistedDigests[$_];vanilla=$vanillaDigests[$_];equal=($assistedDigests.ContainsKey($_) -and $vanillaDigests.ContainsKey($_) -and $assistedDigests[$_] -eq $vanillaDigests[$_])}
    })
    $shared = @($assistedDigests.Keys | Where-Object { $vanillaDigests.ContainsKey($_) })
    $mismatches = @($shared | Where-Object { $assistedDigests[$_] -ne $vanillaDigests[$_] })
    $pass = @($rows | Where-Object { -not $_.equal }).Count -eq 0 -and $shared.Count -gt 0 -and $mismatches.Count -eq 0
    $destination = Join-Path $AssistedRoot ('two-client-' + $Route + '-' + $Mode + '-digest-comparison.json')
    if (Test-Path -LiteralPath $destination) { throw 'Refusing to overwrite previous comparison evidence' }
    [ordered]@{route=$Route;mode=$Mode;assisted_root=[IO.Path]::GetFullPath($AssistedRoot);vanilla_root=[IO.Path]::GetFullPath($VanillaRoot);owner_a=$ownerA;owner_b=$ownerB;dispatch_overlap=@{owner_a=@{id=$firstSentA[0].id;chunk=$firstSentA[0].chunk};owner_b=@{id=$firstSentB[0].id;chunk=$firstSentB[0].chunk};both_pending_before_terminal=$true};required_chunks=$rows;shared_chunks=$shared.Count;shared_mismatches=$mismatches;pass=$pass;performance='NOT_EVALUATED'} | ConvertTo-Json -Depth 7 | Tee-Object -FilePath $destination
    if (-not $pass) { throw 'Missing or unequal trusted-raw vanilla/assisted NOISE digest' }
    return
}
$owners = Owner-Map
$ownerA = [string]$owners.FixtureOwnerA; $ownerB = [string]$owners.FixtureOwnerB
if ([string]::IsNullOrWhiteSpace($ownerA) -or [string]::IsNullOrWhiteSpace($ownerB) -or $ownerA -eq $ownerB) { throw 'Invalid owner mapping' }
$sentA = @(Owner-Records $assistedText 'seeded_fixture\.sent' $ownerA)
$sentB = @(Owner-Records $assistedText 'seeded_fixture\.sent' $ownerB)
$appliedA = @(Owner-Records $assistedText 'seeded_fixture\.applied' $ownerA)
$appliedB = @(Owner-Records $assistedText 'seeded_fixture\.applied' $ownerB)
$resultA = @(Owner-Records $assistedText 'seeded_fixture\.result' $ownerA)
$resultB = @(Owner-Records $assistedText 'seeded_fixture\.result' $ownerB)
 $firstSentA = @($sentA | Where-Object { $_.id -eq $overlapEvidence.owner_a.id -and $_.chunk -eq $overlapEvidence.owner_a.chunk } | Select-Object -First 1)
 $firstSentB = @($sentB | Where-Object { $_.id -eq $overlapEvidence.owner_b.id -and $_.chunk -eq $overlapEvidence.owner_b.chunk } | Select-Object -First 1)
if ($firstSentA.Count -ne 1 -or $firstSentB.Count -ne 1) { throw 'Matched fixture sent pair is absent from the server log' }
$pair = Assert-PairOverlap $firstSentA[0] $firstSentB[0] @($resultA | Where-Object { $_.chunk -eq $firstSentA[0].chunk -and $_.status -ne 'BUSY' }) @($resultB | Where-Object { $_.chunk -eq $firstSentB[0].chunk -and $_.status -ne 'BUSY' }) 'Fixture matched pair'
Assert-OwnerArea $sentA 1000 -2000 'Owner A dispatched'
Assert-OwnerArea $sentB -1000 2000 'Owner B dispatched'
Assert-OwnerArea $appliedA 1000 -2000 'Owner A applied'
Assert-OwnerArea $appliedB -1000 2000 'Owner B applied'
$assistedDigests = Read-Digests $assistedText; $vanillaDigests = Read-Digests $vanillaText
$required = @($appliedA + $appliedB | ForEach-Object { $_.chunk } | Sort-Object -Unique)
if ($Mode -eq 'simultaneous' -and ($appliedA.Count -eq 0 -or $appliedB.Count -eq 0)) { throw 'Both owners must have an installed remote result' }
if ($Mode -eq 'disconnect') {
    $cleanupIndex = $assistedText.IndexOf('Two-client fixture complete')
    if ($cleanupIndex -lt 0) { throw 'Fixture cleanup boundary is missing' }
    $disconnectedA = @($resultA | Where-Object { $_.status -eq 'DISCONNECTED' -and $_.chunk -eq $firstSentA[0].chunk -and $_.index -gt $firstSentA[0].index -and $_.index -lt $cleanupIndex })
    if ($disconnectedA.Count -eq 0) { throw 'Owner A has no pending-job DISCONNECTED result' }
    $aTerminal = $disconnectedA | Sort-Object index | Select-Object -First 1
    if (@($resultB | Where-Object { $_.status -eq 'DISCONNECTED' -and $_.index -lt $cleanupIndex }).Count -ne 0) { throw 'Owner B was cancelled by owner A disconnect' }
    $bAppliedAfter = @($appliedB | Where-Object { $_.id -eq $firstSentB[0].id -and $_.index -gt $aTerminal.index -and $_.index -lt $cleanupIndex })
    if ($bAppliedAfter.Count -eq 0) { throw 'Owner B did not install after owner A terminal disconnect result' }
    $fallbackChunks = @($disconnectedA | ForEach-Object { $_.chunk } | Sort-Object -Unique)
    $required = @($required + $fallbackChunks | Sort-Object -Unique)
}
if ($required.Count -eq 0) { throw 'No installed or terminal fallback chunk to compare' }
$overlap = @([regex]::Matches($assistedText, 'seeded_fixture\.admitted .*active=(?<active>\d+)') | Where-Object { [int]$_.Groups['active'].Value -ge 2 })
if ($overlap.Count -eq 0) { throw 'No active>=2 admission log proves concurrent recording' }
$rows = @($required | ForEach-Object {
    [ordered]@{chunk=$_;assisted=$assistedDigests[$_];vanilla=$vanillaDigests[$_];equal=($assistedDigests.ContainsKey($_) -and $vanillaDigests.ContainsKey($_) -and $assistedDigests[$_] -eq $vanillaDigests[$_])}
})
$shared = @($assistedDigests.Keys | Where-Object { $vanillaDigests.ContainsKey($_) })
$mismatches = @($shared | Where-Object { $assistedDigests[$_] -ne $vanillaDigests[$_] })
$pass = @($rows | Where-Object { -not $_.equal }).Count -eq 0 -and $shared.Count -gt 0 -and $mismatches.Count -eq 0
[ordered]@{
    mode=$Mode; assisted_root=[IO.Path]::GetFullPath($AssistedRoot); vanilla_root=[IO.Path]::GetFullPath($VanillaRoot)
    owner_a=$ownerA; owner_b=$ownerB; owner_a_applied=$appliedA.Count; owner_b_applied=$appliedB.Count
    dispatch_overlap=@{owner_a=@{id=$firstSentA[0].id;chunk=$firstSentA[0].chunk;line_index=$firstSentA[0].index};owner_b=@{id=$firstSentB[0].id;chunk=$firstSentB[0].chunk;line_index=$firstSentB[0].index};both_pending_before_result=$true}
    required_chunks=$rows; shared_chunks=$shared.Count; shared_mismatches=$mismatches; overlap_admissions=$overlap.Count
    pass=$pass; performance='NOT_EVALUATED'
} | ConvertTo-Json -Depth 6 | Tee-Object -FilePath $destination
if (-not $pass) { throw 'Missing or unequal vanilla/assisted NOISE digest' }
