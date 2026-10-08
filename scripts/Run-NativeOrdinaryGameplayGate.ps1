[CmdletBinding()]
param([switch]$Execute,[string]$FabricGameplayRoot='test-artifacts/ordinary-gameplay-gate-20261007-230655-166',[string]$ReleasePackageRoot='test-artifacts/release-alpha8-20261008')
# Finish every implementation/doc edit before this one sequential native batch.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$base=Join-Path $workspace 'test-artifacts'
$parent=(Resolve-Path -LiteralPath $FabricGameplayRoot).Path
if(-not $parent.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Owned successful Fabric evidence child required'}
if(-not $Execute){@{loaders=@('forge','neoforge');conditions=@('original','assisted');remote='gen1c@100.103.102.109';remote_root='E:/WorldgenAssist/port26.3';players=2;view=32;warmup=1;measured=1;fresh_junit=0;mod_builds=0;reused_fabric_input=$parent;scope='Four native ordinary runtimes; stock survival input, authoritative witnesses and stopped saved player/voxel/light checks. No beta or new reproducible performance claim.'}|ConvertTo-Json;exit 0}
$root=Join-Path $base ('native-ordinary-gameplay-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'));New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=[Collections.Generic.List[object]]::new();$results=@();$before=@()
function Manifest {
    $files=@();foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -File -Recurse}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
try {
    foreach($name in @('Run-NativeOrdinaryGameplayGate.ps1','Run-OrdinaryGameplayGate.ps1','NativeGameplayEvidence.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1')){
        $parseTokens=$null;$parseErrors=$null
        $null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$parseTokens,[ref]$parseErrors)
        if($parseErrors.Count){throw ($name+': '+(($parseErrors.Message)-join '; '))}
    }
    $proof=Get-Content -LiteralPath (Join-Path $parent 'summary.json') -Raw|ConvertFrom-Json
    if(-not $proof.success -or $proof.schema -ne 'worldgen-assist.ordinary-gameplay-gate.v1' -or $proof.steps.Count -ne 25 -or $proof.issues.Count -or -not $proof.saved_gameplay.success -or -not $proof.lighting.success){throw 'Closed successful Fabric gameplay proof required'}
    $before=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    foreach($loader in @('forge','neoforge')){
        $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=(Get-Command pwsh.exe).Source;$info.WorkingDirectory=$workspace;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
        foreach($arg in @('-NoProfile','-File',(Join-Path $PSScriptRoot 'Run-OrdinaryGameplayGate.ps1'),'-Execute','-Loader',$loader,'-FabricGameplayRoot',$parent,'-ReleasePackageRoot',$ReleasePackageRoot)){[void]$info.ArgumentList.Add($arg)}
        $process=[Diagnostics.Process]::new();$process.StartInfo=$info
        try {
            if(-not $process.Start()){throw 'Cannot start native gameplay gate'}
            $out=$process.StandardOutput.ReadToEndAsync();$err=$process.StandardError.ReadToEndAsync()
            # Each child owns the existing shared validation lock and its games.
            $process.WaitForExit()
            $output=$out.GetAwaiter().GetResult();$errorText=$err.GetAwaiter().GetResult()
            [IO.File]::WriteAllText((Join-Path $root ($loader+'-out.log')),$output);[IO.File]::WriteAllText((Join-Path $root ($loader+'-err.log')),$errorText)
            $steps.Add(@{loader=$loader;exit_code=$process.ExitCode;success=($process.ExitCode -eq 0)})
            Write-Host "NATIVE_GAMEPLAY_STEP loader=$loader success=$($steps[-1].success)"
            $match=[regex]::Match($output,'ORDINARY_GAMEPLAY_GATE success=(True|False) summary=(.+)')
            if(-not $match.Success){throw 'Native child did not retain its terminal summary'}
            $path=$match.Groups[2].Value.Trim();if(-not $path.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Native result escaped owned evidence'}
            $result=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
            $results+=@{loader=$loader;path=$path;success=$result.success;players=$result.saved_gameplay;lighting=$result.lighting;runtime=$result.runtime}
            if($process.ExitCode -ne 0 -or -not $result.success -or $result.loader -ne $loader -or $result.steps.Count -ne 25 -or $result.runtime.Count -ne 2 -or $result.saved_gameplay.players.Count -ne 4 -or $result.lighting.cases.Count -ne 2){throw "Native ordinary gameplay failed: $loader; original child failure retained"}
        }finally{$process.Dispose()}
    }
}catch{$issues.Add($_.Exception.ToString())}
finally {
    $after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after)
    if($before.Count -and ($before-join [Environment]::NewLine) -cne ($after-join [Environment]::NewLine)){$issues.Add('Sources changed during selected native batch')}
    @{schema='worldgen-assist.native-ordinary-gameplay-gate.v1';success=($issues.Count -eq 0 -and $results.Count -eq 2);root=$root;fabric_parent=$parent;steps=@($steps);loaders=$results;issues=@($issues);fresh_junit=0;mod_builds=0;beta_claim=$false;scope='Exact dev23/3 artifacts and retained method/882 proofs; weak E-server/view32/two stronger-PC clients. Four fresh native ordinary survival/input/reconnect/saved-state/light runtimes; no server restart/continuous movement or new repeatable speed proof.'}|ConvertTo-Json -Depth 24|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Host "NATIVE_GAMEPLAY_GATE success=$($issues.Count -eq 0 -and $results.Count -eq 2) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
