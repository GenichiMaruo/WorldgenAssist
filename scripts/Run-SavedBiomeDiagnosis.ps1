[CmdletBinding()]
param([switch]$Execute,[string]$SourceCaseRoot='test-artifacts/feature-pipeline-gate-20261006-184431-462/performance/parallel')
# One offline batch against the closed public dev22 failure. No Minecraft or JUnit rerun.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base=Join-Path $workspace 'test-artifacts'
$source=(Resolve-Path -LiteralPath $SourceCaseRoot).Path
if(-not $source.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned completed source required'}
if(-not $Execute){@{scope='One retained public region and four original BIOMES/cache/carver histories; no JUnit/MOD build/games';source=$source;center='-2013,3043';remote_host='gen1c@100.103.102.109';remote_files=1}|ConvertTo-Json;exit 0}
$root=Join-Path $base ('saved-biome-diagnosis-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$local=Join-Path $root 'region';New-Item -ItemType Directory -Path $local|Out-Null
$issues=[Collections.Generic.List[string]]::new();$hashes=$null;$probeRoot='';$inputHashes=@()
function Remote-Read([string]$Code){
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Code))
    $reply=& ssh.exe -o BatchMode=yes -o ConnectTimeout=15 gen1c@100.103.102.109 "powershell -NoProfile -NonInteractive -EncodedCommand $encoded"
    if($LASTEXITCODE -ne 0){throw 'Read-only remote proof failed'}
    return (($reply-join "`n")|ConvertFrom-Json)
}
try{
    Copy-Item -LiteralPath $PSCommandPath -Destination $root
    $inputFiles=@('scripts/Run-SavedBiomeDiagnosis.ps1','scripts/Run-BiomeHistoryProbe.ps1','src/portTest/java/io/github/genichimaruo/worldgenassist/server/BiomeHistoryInspector263.java','build.gradle')
    $inputHashes=@($inputFiles|ForEach-Object {@{name=$_;sha256=(Get-FileHash -LiteralPath (Join-Path $workspace $_)).Hash}})
    $result=Get-Content -LiteralPath (Join-Path $source 'scenario-result.json') -Raw|ConvertFrom-Json
    $remote=Get-Content -LiteralPath (Join-Path $source 'remote-evidence/remote-result.json') -Raw|ConvertFrom-Json
    $config=Get-Content -LiteralPath (Join-Path $source 'remote-evidence/scenario-config.json') -Raw|ConvertFrom-Json
    $log=Get-Content -LiteralPath (Join-Path $source 'remote-evidence/latest.log') -Raw
    if(-not $result.success -or -not $result.cleanup_safe -or -not $remote.success -or -not $remote.cleanup_safe -or
        $remote.case -ne 'scenario-overworld-assisted-p2-performance-20261006-190551-283' -or
        $result.artifact_sha256 -ne 'FB611B3943D0E529423D0241D3E0E73041DA19DBE5D7E49E05527B5FC3E8671E' -or
        $result.source_manifest_before_sha256 -ne $result.source_manifest_after_sha256 -or
        $config.seed -ne 8675309 -or $config.view_distance -ne 32 -or
        $log -notmatch 'Stopping server' -or $log -notmatch 'Saving worlds' -or
        $log -notmatch 'job.full_terrain_apply_rejected id=56864f6f-44e6-4008-aeab-cca3ec21050b'){throw 'Exact closed failure identity missing'}
    $remoteFile='E:/WorldgenAssist/port26.3/'+$remote.case+'/dimensions/minecraft/overworld/region/r.-63.95.mca'
    $code='$ErrorActionPreference="Stop";$ProgressPreference="SilentlyContinue";$env:TEMP="E:/WorldgenAssist/temp";$env:TMP=$env:TEMP;'
    $code+='if(@(Get-CimInstance Win32_Process -Filter "Name=''java.exe''"|Where-Object {$_.CommandLine -and $_.CommandLine.Replace(''\'',''/'') -match ''E:/WorldgenAssist/port26.3/fabric-server-launch.jar''}).Count){throw "Owned server still live"};'
    $code+='$path='''+$remoteFile+''';$file=Get-Item -LiteralPath $path;if($file.Length -gt 128MB){throw "Region too large"};[ordered]@{path=$path;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $path).Hash}|ConvertTo-Json -Compress'
    $hashes=Remote-Read $code
    & scp.exe -q ('gen1c@100.103.102.109:'+$remoteFile) $local
    if($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath (Join-Path $local 'r.-63.95.mca')).Hash -ne $hashes.sha256){throw 'Copied region hash differs'}
    $afterCopy=Remote-Read $code
    if($afterCopy.sha256 -ne $hashes.sha256 -or $afterCopy.bytes -ne $hashes.bytes){throw 'Stopped source changed during copy'}
    $hashes|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $root 'remote-region-proof.json')
    $lines=& (Join-Path $PSScriptRoot 'Run-BiomeHistoryProbe.ps1') -Execute -Coordinate publication -HistoryProfile cache-carvers -SavedRegionDirectory $local
    $lines|Set-Content -LiteralPath (Join-Path $root 'probe-runner.log')
    $marker=@($lines|Where-Object {$_ -match '^BIOME_HISTORY_PROBE success=True summary=(.+)$'})
    if($marker.Count -ne 1){throw 'Offline original helper did not finish'}
    $probeRoot=Split-Path -Parent ([regex]::Match($marker[0],'summary=(.+)$').Groups[1].Value)
    $probe=Get-Content -LiteralPath (Join-Path $probeRoot 'summary.json') -Raw|ConvertFrom-Json
    if(-not $probe.success -or $probe.result.histories.Count -ne 4 -or @($probe.result.histories|Where-Object {$null -eq $_.saved_changed_voxels}).Count){throw 'All saved voxel comparisons required'}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    foreach($file in $inputHashes){if($file.sha256 -ne (Get-FileHash -LiteralPath (Join-Path $workspace $file.name)).Hash){$issues.Add('Diagnostic inputs changed')}}
    if($hashes -and (Get-FileHash -LiteralPath (Join-Path $local 'r.-63.95.mca')).Hash -ne $hashes.sha256){$issues.Add('Retained region changed')}
    @{success=($issues.Count -eq 0);source=$source;root=$root;remote_region=$hashes;probe_root=$probeRoot;input_hashes=$inputHashes;issues=@($issues);scope='Read-only stopped dev22 public world; original biome generation and original saved codec only. No actual failed client payload, no performance or parity claim.'}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Output "SAVED_BIOME_DIAGNOSIS success=$($issues.Count -eq 0) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
