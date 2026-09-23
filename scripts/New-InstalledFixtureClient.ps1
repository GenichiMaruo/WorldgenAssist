param([Parameter(Mandatory)][string]$Root, [string]$AssetsRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if (-not $IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') { throw 'This pinned native-library fixture supports Windows x64 only' }
$workspace=Split-Path -Parent $PSScriptRoot
$resolved=[IO.Path]::GetFullPath($Root)
$evidenceRoot=[IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts')).TrimEnd('\')+'\'
if (-not $resolved.StartsWith($evidenceRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Client must use a test-artifacts child' }
$clientDir=Join-Path $resolved 'client'
$versions=@{}
foreach($line in Get-Content -LiteralPath (Join-Path $workspace 'gradle.properties')){
    if($line -match '^\s*(minecraft_version|loader_version|fabric_api_version)\s*=\s*([^\s#]+)\s*$'){$versions[$Matches[1]]=$Matches[2]}
}
$minecraft=$versions.minecraft_version;$loader=$versions.loader_version;$apiVersion=$versions.fabric_api_version
if("$minecraft/$loader/$apiVersion" -notin @('26.2/0.19.3/0.156.0+26.2','26.3/0.19.5/0.161.0+26.3')){throw 'Installed fixture client is not verified for these dependency versions'}
$prep=Join-Path $workspace $(if($minecraft -eq '26.3'){'test-artifacts/installed-client-prep-26.3'}else{'test-artifacts/installed-client-prep-20260910'})
$cache=Join-Path $env:USERPROFILE '.gradle/caches'
$mojang=Get-Content (Join-Path $cache "fabric-loom/$minecraft/mojang_minecraft_info.json") -Raw | ConvertFrom-Json -AsHashtable
$fabric=Get-Content (Join-Path $prep 'fabric-profile.json') -Raw | ConvertFrom-Json -AsHashtable
if ($mojang.id -ne $minecraft -or $fabric.id -ne "fabric-loader-$loader-$minecraft") { throw 'Unapproved runtime version' }
New-Item -ItemType Directory -Force -Path (Join-Path $clientDir 'mods'),(Join-Path $clientDir 'natives') | Out-Null
$classpath=[Collections.Generic.List[string]]::new()
$provenance=[Collections.Generic.List[object]]::new()
function Add-Library([string]$Name,[string]$ExpectedSha1) {
    $parts=$Name.Split(':')
    $directory=Join-Path $cache ('modules-2/files-2.1/'+$parts[0]+'/'+$parts[1]+'/'+$parts[2])
    $fileName=$parts[1]+'-'+$parts[2]+$(if($parts.Length -eq 4){'-'+$parts[3]}else{''})+'.jar'
    $matches=@(Get-ChildItem -LiteralPath $directory -Recurse -File -Filter $fileName | Where-Object {
        -not $ExpectedSha1 -or (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA1).Hash -eq $ExpectedSha1
    })
    if ($matches.Count -ne 1) { throw "Exactly one verified cached library required: $Name" }
    $classpath.Add($matches[0].FullName)
    $provenance.Add([ordered]@{name=$Name;path=$matches[0].FullName;sha256=(Get-FileHash -LiteralPath $matches[0].FullName).Hash})
}
foreach ($library in $mojang.libraries) {
    if ($library.ContainsKey('rules')) {
        $allowed=$false
        foreach ($rule in $library.rules) {
            if ($rule.ContainsKey('features')) { throw 'Unhandled library feature rule' }
            if (-not $rule.ContainsKey('os') -or $rule.os.name -eq 'windows') { $allowed=$rule.action -eq 'allow' }
        }
        if (-not $allowed) { continue }
    }
    # This fixture is specifically Windows amd64, not another native architecture.
    if ($library.name -match ':natives-windows-(arm64|x86)$') { continue }
    Add-Library $library.name $library.downloads.artifact.sha1
}
foreach ($library in $fabric.libraries) {
    $sha=if($library.ContainsKey('sha1')){$library.sha1}else{''}
    Add-Library $library.name $sha
}
$gameJar=Join-Path $cache "fabric-loom/$minecraft/minecraft-client.jar"
if ((Get-FileHash -LiteralPath $gameJar -Algorithm SHA1).Hash -ne $mojang.downloads.client.sha1) { throw 'Original client JAR checksum mismatch' }
$classpath.Add($gameJar)
$provenance.Add([ordered]@{name="original Minecraft $minecraft client";path=$gameJar;sha256=(Get-FileHash -LiteralPath $gameJar).Hash})
$artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Workspace $workspace
$mod=$artifact.Path
$apiRoot=Join-Path $cache "modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/$apiVersion"
$apiFiles=@(Get-ChildItem -LiteralPath $apiRoot -Recurse -File -Filter "fabric-api-$apiVersion.jar")
if($apiFiles.Count -ne 1){throw 'Exactly one exact-version Fabric API JAR is required'}
$api=$apiFiles[0].FullName
Copy-Item -LiteralPath $mod,$api -Destination (Join-Path $clientDir 'mods')
Get-ChildItem -LiteralPath (Join-Path $clientDir 'mods') -File | ForEach-Object {
    [ordered]@{file=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resolved 'installed-client-mods.json')
$provenance | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $resolved 'installed-client-classpath.json')
$assets=if ($AssetsRoot) { [IO.Path]::GetFullPath($AssetsRoot) } else { Join-Path $cache 'fabric-loom/assets' }
if ((Get-FileHash -LiteralPath (Join-Path $assets ("indexes/$minecraft-$($mojang.assetIndex.id).json")) -Algorithm SHA1).Hash -ne $mojang.assetIndex.sha1) { throw 'Asset index checksum mismatch' }
$arguments=@('-Xmx2G',("-XX:ErrorFile=$clientDir/hs_err_pid%p.log"),'--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow','-Dfabric.development=false',
    "-Djava.library.path=$clientDir/natives/java","-Djna.tmpdir=$clientDir/natives/jna","-Dorg.lwjgl.system.SharedLibraryExtractPath=$clientDir/natives/lwjgl","-Dio.netty.native.workdir=$clientDir/natives/netty")
$arguments+=@($fabric.arguments.jvm)
$arguments+=@('-cp',($classpath -join ';'),$fabric.mainClass,'--username','FixtureWorker','--uuid','00000000000000000000000000000001',
    '--accessToken','0','--version',$fabric.id,'--versionType','release','--gameDir',$clientDir,'--assetsDir',$assets,'--assetIndex',("$minecraft-$($mojang.assetIndex.id)"),'--quickPlayMultiplayer','127.0.0.1:25585')
$argFile=Join-Path $resolved 'installed-client-args.txt'
$arguments | ForEach-Object { '"'+$_.Replace('\','/').Replace('"','\"')+'"' } | Set-Content -LiteralPath $argFile -Encoding utf8
return @{Executable='C:\Program Files\Java\jdk-25.0.4\bin\java.exe';Arguments=@('@'+$argFile);WorkingDirectory=$clientDir}
