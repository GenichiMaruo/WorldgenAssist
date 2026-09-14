[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_]{3,16}$')][string]$Username,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{32}$')][string]$Uuid,
    [Parameter(Mandatory)][string]$Root,
    [string]$AssetsRoot,
    [ValidateSet('installed', 'development')][string]$ClientRuntime = 'installed',
    [ValidateSet('none', 'withhold')][string]$FixtureFault = 'none'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# This is a deliberately small adapter over the checksum-verified installed
# client builder. The development route exists only for the explicit fixture
# withholding probe: SeededLeafFixtureClient accepts its fault flag only while
# Fabric Loader reports a development environment. Installed launchers cannot
# request a fault, so this helper cannot introduce a production fault hook.
$workspace = Split-Path -Parent $PSScriptRoot
$profile = [IO.Path]::GetFullPath($Root)
$separator = [IO.Path]::DirectorySeparatorChar
$evidenceRoot = [IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts')).TrimEnd([char[]]@($separator)) + $separator
if (-not $profile.StartsWith($evidenceRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Client profile must be beneath test-artifacts'
}
$clientDir = Join-Path $profile 'client'
New-Item -ItemType Directory -Force -Path $clientDir | Out-Null
@('onboardAccessibility:false', 'skipMultiplayerWarning:true', 'tutorialStep:none', 'renderDistance:10',
  'maxFps:30', 'enableVsync:false', 'soundCategory_master:0.0') |
    Set-Content -LiteralPath (Join-Path $clientDir 'options.txt')

if ($ClientRuntime -eq 'development') {
    # The Gradle fixture run receives the owner name through its existing,
    # build-defined -PfixtureUsername input. Its offline UUID is intentionally
    # derived by the dev launcher; fixture-route owner mapping comes from the
    # accepted handshake, not this installed-launcher UUID parameter.
    return @{
        Executable = "$env:SystemRoot\System32\cmd.exe"
        Arguments = @('/d', '/c', (Join-Path $workspace 'gradlew.bat'), 'runFixtureClient', "-PfixtureRunRoot=$profile", "-PfixtureUsername=$Username", '--no-daemon', '--console=plain')
        WorkingDirectory = $workspace
        Username = $Username
        Uuid = $Uuid
        Runtime = 'development'
        FixtureFault = $FixtureFault
    }
}
if ($FixtureFault -ne 'none') { throw 'Fixture faults require the Fabric Loader development runtime' }

$installed = if ([string]::IsNullOrWhiteSpace($AssetsRoot)) {
    & (Join-Path $PSScriptRoot 'New-InstalledFixtureClient.ps1') -Root $profile
} else {
    & (Join-Path $PSScriptRoot 'New-InstalledFixtureClient.ps1') -Root $profile -AssetsRoot $AssetsRoot
}
$argumentFile = $installed.Arguments[0].Substring(1)
$lines = [Collections.Generic.List[string]](Get-Content -LiteralPath $argumentFile)
function Replace-One([string]$Expected, [string]$Replacement) {
    $indexes = @($lines | ForEach-Object -Begin { $index = 0 } -Process {
        $current = $index; $index++
        if ($_ -eq $Expected) { $current }
    })
    if ($indexes.Count -ne 1) { throw "Expected exactly one installed-client argument: $Expected" }
    $lines[$indexes[0]] = $Replacement
}
Replace-One '"FixtureWorker"' ('"' + $Username + '"')
Replace-One '"00000000000000000000000000000001"' ('"' + $Uuid + '"')
$lines | Set-Content -LiteralPath $argumentFile -Encoding utf8

return @{ Executable = $installed.Executable; Arguments = $installed.Arguments; WorkingDirectory = $installed.WorkingDirectory; Username = $Username; Uuid = $Uuid; Runtime = 'installed'; FixtureFault = 'none' }
