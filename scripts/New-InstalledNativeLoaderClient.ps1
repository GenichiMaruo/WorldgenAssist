[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('forge','neoforge')][string]$Loader,
    [Parameter(Mandatory)][string]$Root,
    [Parameter(Mandatory)][string]$InstallerProfile,
    [Parameter(Mandatory)][string]$AssetsRoot,
    [ValidatePattern('^[A-Za-z0-9_]{3,16}$')][string]$Username='NativeOwner',
    [ValidatePattern('^[0-9a-f]{32}$')][string]$Uuid='000000000000000000000000000000c3'
)

Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64'){
    throw 'This isolated installed-client fixture supports Windows x64 only'
}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot=[IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts')).TrimEnd('\')+'\'
$profile=[IO.Path]::GetFullPath($Root)
$installed=[IO.Path]::GetFullPath($InstallerProfile)
foreach($path in @($profile,$installed)){
    if(-not $path.StartsWith($testRoot,[StringComparison]::OrdinalIgnoreCase)){
        throw 'Fixture paths must remain beneath test-artifacts'
    }
}
$version=if($Loader -eq 'forge'){'26.3-forge-66.0.3'}else{'neoforge-26.3.0.13-beta'}
$descriptor=Join-Path $installed "versions/$version/$version.json"
$loaderProfile=Get-Content -LiteralPath $descriptor -Raw|ConvertFrom-Json -AsHashtable
if($loaderProfile.id -ne $version -or $loaderProfile.inheritsFrom -ne '26.3'){
    throw 'Installed native loader profile version mismatch'
}
$mojang=Get-Content -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/fabric-loom/26.3/mojang_minecraft_info.json') -Raw|ConvertFrom-Json -AsHashtable
if($mojang.id -ne '26.3'){throw 'Pinned Mojang 26.3 metadata is missing'}
$assets=[IO.Path]::GetFullPath($AssetsRoot)
$assetIndex=Join-Path $assets "indexes/26.3-$($mojang.assetIndex.id).json"
if((Get-FileHash -LiteralPath $assetIndex -Algorithm SHA1).Hash -ne $mojang.assetIndex.sha1){
    throw 'Mojang asset index SHA-1 mismatch'
}
$client=Join-Path $profile 'client'
New-Item -ItemType Directory -Force -Path $client,(Join-Path $client 'mods'),(Join-Path $client 'natives')|Out-Null
$artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Workspace $workspace -Loader $Loader
$mod=$artifact.Path
$modCopy=Join-Path $client ('mods/'+$artifact.FileName)
Copy-Item -LiteralPath $mod -Destination $modCopy
$modHash=(Get-FileHash -LiteralPath $mod -Algorithm SHA256).Hash
if((Get-FileHash -LiteralPath $modCopy -Algorithm SHA256).Hash -ne $modHash){throw 'Copied mod JAR SHA-256 mismatch'}

$classpath=[Collections.Generic.List[string]]::new()
$provenance=[Collections.Generic.List[object]]::new()
function Add-Verified([string]$Name,[string]$Path,[string]$Sha1){
    if(-not(Test-Path -LiteralPath $Path -PathType Leaf)){throw "Missing library: $Name"}
    if((Get-FileHash -LiteralPath $Path -Algorithm SHA1).Hash -ne $Sha1){throw "Library SHA-1 mismatch: $Name"}
    $classpath.Add($Path)
    $provenance.Add([ordered]@{name=$Name;path=$Path;sha256=(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash})
}
$loaderKeys=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach($library in $loaderProfile.libraries){
    $parts=$library.name.Split(':')
    [void]$loaderKeys.Add($parts[0]+':'+$parts[1])
    $artifact=$library.downloads.artifact
    $path=Join-Path (Join-Path $installed 'libraries') $artifact.path
    Add-Verified $library.name $path $artifact.sha1
}
$cache=Join-Path $env:USERPROFILE '.gradle/caches'
foreach($library in $mojang.libraries){
    if($library.ContainsKey('rules')){
        $allowed=$false
        foreach($rule in $library.rules){
            if($rule.ContainsKey('features')){throw 'Unhandled Mojang library feature rule'}
            if(-not $rule.ContainsKey('os') -or $rule.os.name -eq 'windows'){$allowed=$rule.action -eq 'allow'}
        }
        if(-not $allowed){continue}
    }
    if($library.name -match ':natives-windows-(arm64|x86)$'){continue}
    $parts=$library.name.Split(':')
    if($loaderKeys.Contains($parts[0]+':'+$parts[1])){continue}
    $fileName=$parts[1]+'-'+$parts[2]+$(if($parts.Length -eq 4){'-'+$parts[3]}else{''})+'.jar'
    $dir=Join-Path $cache ("modules-2/files-2.1/$($parts[0])/$($parts[1])/$($parts[2])")
    $matches=@(Get-ChildItem -LiteralPath $dir -Recurse -File -Filter $fileName|Where-Object {
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA1).Hash -eq $library.downloads.artifact.sha1
    })
    if($matches.Count -ne 1){throw "Exactly one verified Mojang library required: $($library.name)"}
    Add-Verified $library.name $matches[0].FullName $library.downloads.artifact.sha1
}
$game=Join-Path $cache 'fabric-loom/26.3/minecraft-client.jar'
Add-Verified 'minecraft:client:26.3' $game $mojang.downloads.client.sha1

$args=[Collections.Generic.List[string]]::new()
$args.Add('-Xmx2G')
$args.Add("-XX:ErrorFile=$client/hs_err_pid%p.log")
$args.Add('--enable-native-access=ALL-UNNAMED')
$args.Add('--sun-misc-unsafe-memory-access=allow')
foreach($arg in $loaderProfile.arguments.jvm){
    $args.Add(([string]$arg).Replace('${library_directory}',(Join-Path $installed 'libraries')))
}
foreach($arg in @(
    "-Djava.library.path=$client/natives/java", "-Djna.tmpdir=$client/natives/jna",
    "-Dorg.lwjgl.system.SharedLibraryExtractPath=$client/natives/lwjgl",
    "-Dio.netty.native.workdir=$client/natives/netty", '-cp',($classpath -join ';'),
    $loaderProfile.mainClass,'--username',$Username,'--uuid',$Uuid,'--accessToken','0',
    '--version',$loaderProfile.id,'--versionType','release','--gameDir',$client,
    '--assetsDir',$assets,'--assetIndex',"26.3-$($mojang.assetIndex.id)",
    '--quickPlayMultiplayer','127.0.0.1:25585')){$args.Add([string]$arg)}
foreach($arg in $loaderProfile.arguments.game){$args.Add([string]$arg)}
$argFile=Join-Path $profile 'installed-client-args.txt'
$args|ForEach-Object {'"'+$_.Replace('\','/').Replace('"','\"')+'"'}|Set-Content -LiteralPath $argFile -Encoding utf8
$provenance|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $profile 'installed-client-classpath.json') -Encoding utf8
[ordered]@{schema='worldgen-assist.native-client-launch.v1';loader=$Loader;version=$version;mod_sha256=$modHash;
    installer_profile=$installed;assets=$assets;classpath_entries=$classpath.Count;username=$Username;uuid=$Uuid}|
    ConvertTo-Json|Set-Content -LiteralPath (Join-Path $profile 'launch-summary.json') -Encoding utf8
return @{Executable='C:\Program Files\Java\jdk-25.0.4\bin\java.exe';Arguments=@('@'+$argFile);WorkingDirectory=$client;
    ModSha256=$modHash;Loader=$Loader;Version=$version}
