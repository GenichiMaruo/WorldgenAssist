param(
    [string]$Workspace=(Split-Path -Parent $PSScriptRoot),
    [ValidateSet('fabric','forge','neoforge')][string]$Loader='fabric'
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$properties=@{}
foreach ($line in Get-Content -LiteralPath (Join-Path $Workspace 'gradle.properties')) {
    if ($line -match '^\s*(mod_version|minecraft_version|archives_base_name)\s*=\s*([^\s#]+)\s*$') {
        if ($properties.ContainsKey($Matches[1])) { throw 'Duplicate artifact property' }
        $properties[$Matches[1]]=$Matches[2]
    }
}
foreach ($key in @('mod_version','minecraft_version','archives_base_name')) {
    if (-not $properties.ContainsKey($key) -or $properties[$key] -notmatch '^[A-Za-z0-9][A-Za-z0-9.+_-]*$') { throw "Invalid artifact property: $key" }
}
$baseName=$properties.archives_base_name
$buildRoot=if($Loader -eq 'fabric'){$Workspace}else{Join-Path $Workspace "loaders/$Loader"}
if($Loader -ne 'fabric'){$baseName+='-'+$Loader}
$fileName=$baseName+'-'+$properties.mod_version+'.jar'
[pscustomobject]@{
    Version=$properties.mod_version; Minecraft=$properties.minecraft_version; Loader=$Loader; FileName=$fileName
    Path=(Join-Path $buildRoot ('build/libs/'+$fileName))
    SourcesPath=(Join-Path $buildRoot ('build/libs/'+$baseName+'-'+$properties.mod_version+'-sources.jar'))
    Tag=('v'+$properties.mod_version)
}
