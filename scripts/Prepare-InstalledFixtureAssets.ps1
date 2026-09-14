# Build a private, checksum-verified asset root without modifying shared caches.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $env:USERPROFILE '.gradle/caches'
$metadata = Get-Content -LiteralPath (Join-Path $cache 'fabric-loom/26.2/mojang_minecraft_info.json') -Raw | ConvertFrom-Json
if ($metadata.id -ne '26.2' -or $metadata.assetIndex.sha1 -notmatch '^[a-f0-9]{40}$') { throw 'Invalid pinned asset metadata' }
$indexHash = $metadata.assetIndex.sha1
$expectedUrl = 'https://piston-meta.mojang.com/v1/packages/' + $indexHash + '/' + $metadata.assetIndex.id + '.json'
if ($metadata.assetIndex.url -ne $expectedUrl) { throw 'Unexpected asset-index origin' }
$root = Join-Path $workspace ('test-artifacts/installed-assets-' + $indexHash)
$indexPath = Join-Path $root 'indexes/26.2-32.json'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $indexPath) | Out-Null
if (-not (Test-Path -LiteralPath $indexPath)) { Invoke-WebRequest -Uri $expectedUrl -OutFile $indexPath }
if ((Get-FileHash -LiteralPath $indexPath -Algorithm SHA1).Hash -ne $indexHash) { throw 'Asset index checksum mismatch' }
$index = Get-Content -LiteralPath $indexPath -Raw | ConvertFrom-Json -AsHashtable
$downloaded = 0
$copied = 0
foreach ($asset in $index.objects.Values) {
    $hash = $asset.hash
    if ($hash -notmatch '^[a-f0-9]{40}$') { throw 'Invalid content-addressed asset hash' }
    $relative = 'objects/' + $hash.Substring(0, 2) + '/' + $hash
    $target = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $target)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        $cached = Join-Path (Join-Path $cache 'fabric-loom/assets') $relative
        if (Test-Path -LiteralPath $cached) { Copy-Item -LiteralPath $cached -Destination $target; $copied++ }
        else {
            Invoke-WebRequest -Uri ('https://resources.download.minecraft.net/' + $hash.Substring(0, 2) + '/' + $hash) -OutFile $target
            $downloaded++
        }
    }
    if ((Get-Item -LiteralPath $target).Length -ne $asset.size -or (Get-FileHash -LiteralPath $target -Algorithm SHA1).Hash -ne $hash) {
        throw "Asset checksum mismatch: $hash"
    }
}
[ordered]@{minecraft='26.2';index_url=$expectedUrl;index_sha1=$indexHash;objects=$index.objects.Count;downloaded=$downloaded;copied=$copied;verified_at=(Get-Date -Format o)} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'verification.json')
Write-Output $root
