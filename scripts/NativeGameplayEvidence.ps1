# Helpers for unshipped native probes and E-drive-only cached server runtimes.
function Resolve-GameplayReleaseArtifact([object]$Candidate,[string]$PackageRoot){
    $proof=Get-Content -LiteralPath (Join-Path $PackageRoot 'build-verification.json') -Raw|ConvertFrom-Json
    if(-not $proof.success -or $proof.candidate_version -ne '0.1.0-alpha.8-dev.23+mc26.3' -or $proof.version -ne '0.1.0-alpha.8+mc26.3' -or $proof.builds.Count -ne 3 -or @($proof.builds|Where-Object status -ne 'PASSED').Count){throw 'Exact successful alpha8 packaging required'}
    $row=@($proof.artifacts|Where-Object loader -eq $Candidate.loader)
    $current=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $Candidate.loader
    if($row.Count -ne 1 -or $row[0].candidate_sha256 -ne $Candidate.sha256 -or -not $row[0].runtime_content_unchanged -or -not $row[0].source_entries_unchanged -or $current.Version -ne $proof.version -or (Get-FileHash -LiteralPath $current.Path).Hash -ne $row[0].sha256 -or (Get-FileHash -LiteralPath (Join-Path $PackageRoot ('assets/'+$row[0].file))).Hash -ne $row[0].sha256){throw 'Current exact release artifact differs'}
    $original=[IO.Compression.ZipFile]::OpenRead($Candidate.path);$release=[IO.Compression.ZipFile]::OpenRead($current.Path)
    try{
        $originalEntries=@($original.Entries|Where-Object {-not $_.FullName.EndsWith('/')});$releaseEntries=@($release.Entries|Where-Object {-not $_.FullName.EndsWith('/')})
        if((($originalEntries.FullName|Sort-Object)-join ';') -cne (($releaseEntries.FullName|Sort-Object)-join ';')){throw 'Release archive entries differ'}
        $metadata=switch($Candidate.loader){fabric{'fabric.mod.json'} forge{'META-INF/mods.toml'} neoforge{'META-INF/neoforge.mods.toml'}}
        foreach($entry in $originalEntries){
            $other=$release.GetEntry($entry.FullName);$oldStream=$entry.Open();$newStream=$other.Open()
            try{
                if($entry.FullName -eq $metadata){$oldReader=[IO.StreamReader]::new($oldStream);$newReader=[IO.StreamReader]::new($newStream);if($oldReader.ReadToEnd().Replace($proof.candidate_version,$proof.version) -cne $newReader.ReadToEnd()){throw 'Exact metadata-only replacement required'}}
                elseif([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($oldStream)) -cne [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($newStream))){throw ('Release runtime content differs: '+$entry.FullName)}
            }finally{$oldStream.Dispose();$newStream.Dispose()}
        }
    }finally{$original.Dispose();$release.Dispose()}
    return @{loader=$Candidate.loader;path=$current.Path;sha256=$row[0].sha256;version=$proof.version;candidate_sha256=$Candidate.sha256;packaging_evidence=$PackageRoot}
}
function New-NativeGameplayProbe([string]$Loader,[string]$Parent,[string]$Classes,[string]$Jar,[string]$Classpath,[string]$Jdk){
    $prior=Get-Content -LiteralPath (Join-Path $Parent 'summary.json') -Raw|ConvertFrom-Json
    if(-not $prior.success -or $prior.schema -ne 'worldgen-assist.ordinary-gameplay-gate.v1' -or $prior.steps.Count -ne 25 -or $prior.issues.Count -or @($prior.steps|Where-Object {-not $_.success -or $_.exit_code -or $_.timed_out}).Count -or -not $prior.saved_gameplay.success -or -not $prior.lighting.success){throw 'Exact closed Fabric ordinary gameplay parent required'}
    if((Get-FileHash -LiteralPath $prior.probe_jar).Hash -ne $prior.probe_sha256){throw 'Retained successful input JAR differs'}
    if((Get-Content -LiteralPath (Join-Path $Parent 'source-manifest-before.sha256') -Raw) -cne (Get-Content -LiteralPath (Join-Path $Parent 'source-manifest-after.sha256') -Raw)){throw 'Original successful Fabric source was not frozen'}
    foreach($name in @('GameplayProbe','GameplayTickMixin','GameplayHeldInputMixin')){
        if((Get-FileHash -LiteralPath (Join-Path $Parent ($name+'.java'))).Hash -ne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot ('gameplay-probe/'+$name+'.java'))).Hash){throw 'Original successful shared input source differs'}
    }
    $zip=[IO.Compression.ZipFile]::OpenRead($prior.probe_jar)
    try {
        $names=@('io/github/genichimaruo/worldgenassist/probe/GameplayProbe.class','io/github/genichimaruo/worldgenassist/probe/mixin/GameplayTickMixin.class','io/github/genichimaruo/worldgenassist/probe/mixin/GameplayHeldInputMixin.class')
        foreach($name in $names){$entry=$zip.GetEntry($name);if($null -eq $entry){throw 'Original input bytecode missing'};$dest=Join-Path $Classes $name;New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($dest))|Out-Null;[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$dest)}
    }finally{$zip.Dispose()}
    $installed=Join-Path $base $(if($Loader -eq 'forge'){'port26.3-forge-installed'}else{'port26.3-neo-installed'})
    $version=if($Loader -eq 'forge'){'26.3-forge-66.0.3'}else{'neoforge-26.3.0.13-beta'}
    $nativeProfile=Get-Content -LiteralPath (Join-Path $installed "client-profile/versions/$version/$version.json") -Raw|ConvertFrom-Json
    if($nativeProfile.id -ne $version -or $nativeProfile.inheritsFrom -ne '26.3'){throw 'Exact native installed profile required'}
    $libraries=@();$libraryInventory=@();foreach($library in $nativeProfile.libraries){$path=Join-Path $installed ('client-profile/libraries/'+$library.downloads.artifact.path);if((Get-FileHash -LiteralPath $path -Algorithm SHA1).Hash.ToLowerInvariant() -ne $library.downloads.artifact.sha1){throw 'Native compile library changed'};$libraries+=$path;$libraryInventory+=@{name=$library.name;path=$path;sha256=(Get-FileHash -LiteralPath $path).Hash}}
    $descriptor=Join-Path $installed "client-profile/versions/$version/$version.json"
    $libraryInventory+=@{name='native installed profile descriptor';path=$descriptor;sha256=(Get-FileHash -LiteralPath $descriptor).Hash}
    $entry=if($Loader -eq 'forge'){'ForgeProbeMod'}else{'NeoProbeMod'}
    $sources=@('NativeReceiptProbe','NativeReceiptMixin',$entry)|ForEach-Object {Join-Path $PSScriptRoot ('native-gameplay-probe/'+$_+'.java')}
    Step 'compile-native-only-input-adapter' (Join-Path $Jdk 'javac.exe') (@('-encoding','UTF-8','-proc:none','-cp',($Classpath+';'+($libraries-join ';')),'-d',$Classes)+$sources) 120
    foreach($source in $sources){Copy-Item -LiteralPath $source -Destination $root}
    # Forge's installed Mixin0.8.7 recognises JAVA_21, matching the production config.
    # The actual runtime/class files remain Java25, as in the verified WGA artifact.
    @{required=$true;minVersion='0.8';package='io.github.genichimaruo.worldgenassist.probe.mixin';compatibilityLevel='JAVA_21';client=@('GameplayTickMixin','GameplayHeldInputMixin','NativeReceiptMixin');injectors=@{defaultRequire=1}}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $Classes 'gameplay-probe.mixins.json') -Encoding utf8NoBOM
    $meta=Join-Path $Classes 'META-INF';New-Item -ItemType Directory -Path $meta|Out-Null
    $toml=@('license="All Rights Reserved"','[[mods]]','modId="worldgen_gameplay_probe"','version="1.0.0"','displayName="Unshipped owned gameplay evidence"','description="Client input and receipt evidence only."')
    if($Loader -eq 'forge'){
        $toml=@('modLoader="javafml"','loaderVersion="[66,67)"')+$toml+@('displayTest="IGNORE_SERVER_VERSION"')
        $name='mods.toml'
        [IO.File]::WriteAllLines((Join-Path $meta 'MANIFEST.MF'),@('Manifest-Version: 1.0','MixinConfigs: gameplay-probe.mixins.json',''),[Text.UTF8Encoding]::new($false))
    }else{$toml+=@('[[mixins]]','config="gameplay-probe.mixins.json"');$name='neoforge.mods.toml'}
    [IO.File]::WriteAllLines((Join-Path $meta $name),$toml,[Text.UTF8Encoding]::new($false))
    $packageArgs=@('--create','--file',$Jar);if($Loader -eq 'forge'){$packageArgs+=@('--manifest',(Join-Path $meta 'MANIFEST.MF'))};$packageArgs+=@('-C',$Classes,'.')
    Step 'package-native-only-input-driver' (Join-Path $Jdk 'jar.exe') $packageArgs 60
    return @{sha256=(Get-FileHash -LiteralPath $Jar).Hash;libraries=$libraryInventory}
}

# Invoked by the existing scenario runner; no machine install or download.
function Prepare-NativeGameplayRuntime([string]$Loader){
    $installed=Join-Path $workspace ('test-artifacts/'+$(if($Loader -eq 'forge'){'port26.3-forge-installed'}else{'port26.3-neo-installed'}))
    $source=Join-Path $installed 'server'
    $files=@(Get-ChildItem -LiteralPath (Join-Path $source 'libraries') -File -Recurse)
    if($Loader -eq 'forge'){$files+=Get-Item -LiteralPath (Join-Path $source 'forge-26.3-66.0.3-shim.jar')}
    $rows=@($files|Sort-Object FullName|ForEach-Object {[ordered]@{relative=$_.FullName.Substring($source.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
    $runtimeBytes=[long]0;foreach($row in $rows){$runtimeBytes += [long]$row.bytes}
    if($rows.Count -gt 512 -or $runtimeBytes -gt 2GB){throw 'Bounded installed native runtime required'}
    foreach($row in $rows){if($row.relative -notmatch '^(libraries/[A-Za-z0-9_./+-]+|forge-26\.3-66\.0\.3-shim\.jar)$' -or $row.relative.Contains('..')){throw 'Native runtime path bound'}}
    $manifest=Join-Path $output 'native-runtime-manifest.json';Write-Json $manifest $rows
    $manifestHash=(Get-FileHash -LiteralPath $manifest).Hash
    # WinPS5.1 cannot extract NeoForge's longest library at a264-character path.
    # Shorten only its directory label; the complete manifest hash remains authority.
    $runtimeLabel=if($Loader -eq 'neoforge'){$manifestHash.Substring(0,16)}else{$manifestHash}
    $runtime="$RemoteRoot/native-runtime/$Loader/$runtimeLabel"
    foreach($row in $rows){if(($runtime+'/'+$row.relative).Length -ge 240){throw 'Native runtime path exceeds bounded WinPS extraction length'}}
    $bundle=Join-Path $output 'native-runtime.zip';$archive=[IO.Compression.ZipFile]::Open($bundle,[IO.Compression.ZipArchiveMode]::Create)
    try{foreach($row in $rows){[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $source $row.relative),$row.relative,[IO.Compression.CompressionLevel]::NoCompression)}}finally{$archive.Dispose()}
    $bundleHash=(Get-FileHash -LiteralPath $bundle).Hash
    & scp.exe -q $bundle $manifest ($RemoteHost+':'+$RemoteRoot+'/scenario-staging/')
    if($LASTEXITCODE -ne 0){throw 'Native cached runtime transfer failed'}
    $code='$nativeRoot="'+$runtime+'";$stage="'+$RemoteRoot+'/scenario-staging";'
    $code+='if(@(Get-CimInstance Win32_Process -Filter "Name=''java.exe''"|Where-Object {$_.CommandLine -and $_.CommandLine.Replace(''\'',''/'') -match ''E:/WorldgenAssist/port26.3/(fabric-server-launch\.jar|native-runtime/(forge|neoforge)/)''}).Count){throw "Owned server still live before native preparation"};'
    $code+='foreach($path in @("E:/WorldgenAssist","'+$RemoteRoot+'","'+$RemoteRoot+'/native-runtime","'+$RemoteRoot+'/native-runtime/'+$Loader+'",$nativeRoot)){if((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Native root must not be a reparse point"}};'
    $code+='if((Get-FileHash -LiteralPath ($stage+"/native-runtime.zip")).Hash -ne "'+$bundleHash+'" -or (Get-FileHash -LiteralPath ($stage+"/native-runtime-manifest.json")).Hash -ne "'+$manifestHash+'"){throw "Native transfer identity differs"};'
    $code+='if((Test-Path -LiteralPath ($nativeRoot+"/runtime-manifest.json")) -and (Get-FileHash -LiteralPath ($nativeRoot+"/runtime-manifest.json")).Hash -ne "'+$manifestHash+'"){throw "Native runtime label collision"};'
    $code+='$rows=Get-Content -LiteralPath ($stage+"/native-runtime-manifest.json") -Raw|ConvertFrom-Json;$runtimeBytes=[long]0;foreach($row in $rows){$runtimeBytes+=[long]$row.bytes};if($rows.Count -gt 512 -or $runtimeBytes -gt 2GB){throw "Native manifest bound"};'
    $code+='Add-Type -AssemblyName System.IO.Compression.FileSystem;New-Item -ItemType Directory -Force -Path $nativeRoot|Out-Null;$zip=[IO.Compression.ZipFile]::OpenRead($stage+"/native-runtime.zip");try{if($zip.Entries.Count -ne $rows.Count){throw "Native entry count"};'
    $code+='foreach($row in $rows){if($row.relative -notmatch "^(libraries/[A-Za-z0-9_./+-]+|forge-26\.3-66\.0\.3-shim\.jar)$" -or $row.relative.Contains("..")){throw "Native path bound"};$target=[IO.Path]::GetFullPath((Join-Path $nativeRoot $row.relative));if(-not $target.StartsWith([IO.Path]::GetFullPath($nativeRoot)+"\",[StringComparison]::OrdinalIgnoreCase)){throw "Native target escaped"};'
    $code+='$ancestor=[IO.Path]::GetDirectoryName($target);while($ancestor.Length -ge $nativeRoot.Length){if((Test-Path -LiteralPath $ancestor) -and ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Native directory reparse point"};$ancestor=[IO.Path]::GetDirectoryName($ancestor)};'
    $code+='$entry=$zip.GetEntry($row.relative);if($null -eq $entry -or $entry.Length -ne $row.bytes){throw "Native entry identity"};if(-not(Test-Path -LiteralPath $target)){New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($target))|Out-Null;[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target)};if(((Get-Item -LiteralPath $target -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -or (Get-Item -LiteralPath $target).Length -ne $row.bytes -or (Get-FileHash -LiteralPath $target).Hash -ne $row.sha256){throw "Native runtime hash differs"}}}finally{$zip.Dispose()};'
    $code+='New-Item -ItemType Directory -Force -Path ($nativeRoot+"/mods")|Out-Null;Copy-Item -LiteralPath ($stage+"/eula.txt") -Destination ($nativeRoot+"/eula.txt") -Force;'
    $code+='$name="'+$artifact.FileName+'";if(@(Get-ChildItem -LiteralPath ($nativeRoot+"/mods") -File -Filter "*.jar"|Where-Object {$_.Name -ne $name}).Count){throw "Unexpected native server mod"};Copy-Item -LiteralPath ($stage+"/"+$name) -Destination ($nativeRoot+"/mods/"+$name) -Force;if((Get-FileHash -LiteralPath ($nativeRoot+"/mods/"+$name)).Hash -ne "'+$artifactHash+'"){throw "Native MOD hash differs"};'
    $code+='Copy-Item -LiteralPath ($stage+"/native-runtime-manifest.json") -Destination ($nativeRoot+"/runtime-manifest.json") -Force'
    $prepareScript=Join-Path $output 'prepare-native-runtime.ps1'
    Write-Utf8 $prepareScript ('$ErrorActionPreference="Stop";$ProgressPreference="SilentlyContinue";'+$code)
    $prepareHash=(Get-FileHash -LiteralPath $prepareScript).Hash
    & scp.exe -q $prepareScript ($RemoteHost+':'+$RemoteRoot+'/scenario-staging/')
    if($LASTEXITCODE -ne 0){throw 'Native preparation helper transfer failed'}
    Invoke-Remote ('$helper="'+$RemoteRoot+'/scenario-staging/prepare-native-runtime.ps1";if((Get-FileHash -LiteralPath $helper).Hash -ne "'+$prepareHash+'"){throw "Native preparation helper hash differs"};Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force;& $helper')
    Write-Json (Join-Path $output 'native-runtime.json') @{loader=$Loader;root=$runtime;manifest_sha256=$manifestHash;bundle_sha256=$bundleHash;files=$rows.Count;source=$source}
    return @{sha256=$manifestHash;root=$runtime;installer_profile=(Join-Path $installed 'client-profile')}
}
