[CmdletBinding()]
param([switch]$Execute,[switch]$CaptureProbe)
# One modified peer-comparison method, Fabric build, one exact view32 reproduction.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$base=Join-Path $workspace 'test-artifacts'
$selection='io.github.genichimaruo.worldgenassist.server.CompleteTerrainPeer263Test.agreementRequiresDistinctAssignmentsAndEveryTerrainComponentAndFailsPromptly'
if($CaptureProbe){$selection='io.github.genichimaruo.worldgenassist.common.PublicPeerProbe263Test.onlyExplicitPublicRegionIsCapturedWithExactInputsAndCountAndByteBounds'}
if(-not $Execute){@{tests=@($selection);expected_methods=1;builds=@('fabric');runtime='Only assisted/parallel view32,warmup1/repeats3, same locations including -2012,3041. Diagnostics only, no speed claim/native reruns.';remote_host='gen1c@100.103.102.109';remote_root='E:/WorldgenAssist/port26.3'}|ConvertTo-Json;exit 0}
$root=Join-Path $base ('peer-mismatch-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'));New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=@();$before=@();$lock=$null;$junit=$null;$artifact=$null;$runtime=$null;$differences=@();$quarantines=0;$captured=@()
$savedJava=$env:JAVA_HOME;$savedPath=$env:Path;$inspection=$null
function Manifest {
    $files=@();foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -Recurse -File}
    foreach($name in @('gradle.properties','build.gradle','settings.gradle','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Step([string]$Name,[string]$Executable,[string[]]$Arguments,[int]$Seconds=1800){
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$workspace
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($arg in $Arguments){[void]$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(-not $process.Start()){throw "Could not start $Name"}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();$finished=$process.WaitForExit($Seconds*1000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$stdout.GetAwaiter().GetResult());[IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$stderr.GetAwaiter().GetResult())
        $script:steps+=@{name=$Name;success=($finished -and $process.ExitCode -eq 0);exit_code=$process.ExitCode;timed_out=(-not $finished)}
        Write-Host "PEER_MISMATCH_STEP name=$Name success=$($script:steps[-1].success)"
        if(-not $script:steps[-1].success){throw "Step failed: $Name (see retained logs)"}
    }finally{$process.Dispose()}
}
try{
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $before=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-before.sha256'),$before)
    foreach($file in @('Run-PeerMismatchGate.ps1','Run-WorldgenScenario.ps1','Remote-WorldgenScenarioServer.ps1')){
        $tokens=$null;$errors=$null;[void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$tokens,[ref]$errors)
        if($errors.Count){throw "Syntax error in $file"};Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $root
    }
    $env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4';$env:Path="$env:JAVA_HOME\bin;$env:Path"
    Step 'affected-unit-fabric-build' "$env:SystemRoot\System32\cmd.exe" @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'test','--tests',$selection,'build')
    $class=$selection.Substring(0,$selection.LastIndexOf('.'))
    $xmlPath=Join-Path $workspace "build/test-results/test/TEST-$class.xml"
    Copy-Item -LiteralPath $xmlPath -Destination $root;[xml]$xml=Get-Content -LiteralPath $xmlPath
    $junit=@{};foreach($key in @('tests','failures','errors','skipped')){$junit[$key]=[int]$xml.testsuite.GetAttribute($key)}
    if($junit.tests -ne 1 -or $junit.failures -or $junit.errors -or $junit.skipped){throw 'Only the modified comparison method must pass'}
    $built=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader fabric
    $artifact=@{path=$built.Path;version=$built.Version;sha256=(Get-FileHash -LiteralPath $built.Path).Hash}
    Copy-Item -LiteralPath $built.Path -Destination $root
    $case=Join-Path $root 'reproduction'
    $runtimeArgs=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),'-RemoteHost','gen1c@100.103.102.109','-RemoteRoot','E:/WorldgenAssist/port26.3','-Dimension','overworld','-Mode','assisted','-Players','2','-CacheEntries','128','-ValidationCells','8','-Seed','8675309','-ServerLogicalProcessors','0','-ServerJvmProcessors','0','-NoiseBackend','cooperative','-WindowProfile','deep','-RemoteApplicationProfile','overlap','-PrefetchLookahead','0','-RemoteWorkKind','complete','-CompleteVerification','peer','-Purpose','performance','-Prediction','true','-FeatureBackend','parallel','-ViewDistance','32','-MeasureFullView','-QuietRemoteTrace','-WarmupRuns','1','-MeasuredRepeats','3','-OutputRoot',$case)
    if($CaptureProbe){$runtimeArgs+='-ClientPeerProbe'}
    Step 'assisted-reproduction' (Get-Command pwsh.exe).Source $runtimeArgs 4200
    $runtime=Get-Content -LiteralPath (Join-Path $case 'scenario-result.json') -Raw|ConvertFrom-Json
    if(-not $runtime.success -or -not $runtime.cleanup_safe -or -not $runtime.loopback_only -or $runtime.artifact_sha256 -ne $artifact.sha256 -or $runtime.source_manifest_before_sha256 -ne $runtime.source_manifest_after_sha256 -or $runtime.feature_fixture -or $runtime.decoration_digest){throw 'Reproduction identity/cleanup differs'}
    $log=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/latest.log') -Raw
    $differences=@([regex]::Matches($log,'[^\r\n]*job\.peer_terrain_difference[^\r\n]*')|ForEach-Object Value)
    $quarantines=[regex]::Matches($log,'worker\.quarantined').Count
    if($CaptureProbe){
        if(-not $runtime.client_peer_probe){throw 'Actual capture fixture missing'}
        foreach($owner in 0..1){
            $probe=Join-Path $case "clients/owner-$owner/client/worldgen-assist-peer-probe"
            $files=@(Get-ChildItem -LiteralPath $probe -Filter '*.json' -File)
            if($files.Count -lt 1 -or $files.Count -gt 4096){throw 'Bounded public-region capture missing for an owner'}
            if((Get-Content -LiteralPath (Join-Path $case (@('ScenarioOwnerA','ScenarioOwnerB')[$owner]+'-stdout.log')) -Raw) -match 'public_peer_probe.failed'){throw 'Client probe capture failed'}
        }
        Step 'inspect-public-captures' "$env:SystemRoot\System32\cmd.exe" @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'inspectPublicPeerCaptures',"-PpeerCaptureCase=$case",('-PpeerCaptureOutput='+(Join-Path $root 'capture-inspection.json'))) 300
        $inspection=Get-Content -LiteralPath (Join-Path $root 'capture-inspection.json') -Raw|ConvertFrom-Json
        if(-not $inspection.success -or $inspection.paired_count -lt 1){throw 'Actual bounded capture comparison incomplete'}
        $captured=@($inspection.captures)
        if($inspection.runtime_failed_pairs_missing_capture.Count){$issues.Add('Actual failed pair missing bounded public-region capture; failure remains unexplained')}
        if($inspection.differences.Count){$issues.Add('Captured peer outputs disagree; inspect actual biome voxels/palettes before changing generation')}
    }
    $differences|Set-Content -LiteralPath (Join-Path $root 'peer-differences.log')
    if($quarantines -or $differences.Count){$issues.Add('Peer mismatch reproduced; inspect bounded component diagnostics. Runtime clean stop is not correctness success.')}
    if($log -match 'Mixin apply failed|Encountered an unexpected exception|job.full_terrain_apply_rejected|fixture.armed'){throw 'Runtime rejection/fixture/error marker'}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    try{$after=Manifest;[IO.File]::WriteAllLines((Join-Path $root 'source-manifest-after.sha256'),$after);if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Inputs changed during batch')};if($artifact -and $artifact.sha256 -ne (Get-FileHash -LiteralPath $artifact.path).Hash){$issues.Add('Artifact changed during batch')}}catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()};$env:JAVA_HOME=$savedJava;$env:Path=$savedPath
    @{schema='worldgen-assist.peer-mismatch-gate.v1';success=($issues.Count -eq 0);root=$root;capture_probe=[bool]$CaptureProbe;captured=$captured;capture_inspection=$inspection;junit=$junit;artifact=$artifact;steps=$steps;runtime_result=$runtime;differences=$differences;quarantines=$quarantines;issues=@($issues);beta_claim=$false;scope='One affected method/Fabric build/single same-workload reproduction,then offline original-body and exact biome-window inspection. Explicit public-region captures only on client workers,<=4096 pairs/512MiB per process. Diagnostics only,no speed claim; historical failures retained.'}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Output "PEER_MISMATCH_GATE success=$($issues.Count -eq 0) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
