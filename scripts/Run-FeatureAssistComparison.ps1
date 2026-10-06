[CmdletBinding()]
param([Parameter(Mandatory)][string]$BuildEvidence,[ValidateSet('off-first','parallel-first')][string]$Order='off-first')
# Same current JAR, remote assistance ON in both. Performance-only scheduling experiment.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$base=Join-Path $workspace 'test-artifacts'
$evidence=(Resolve-Path -LiteralPath $BuildEvidence).Path
if(-not $evidence.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Workspace build evidence required'}
$root=Join-Path $base ('feature-assist-comparison-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'));New-Item -ItemType Directory -Path $root|Out-Null
$issues=[Collections.Generic.List[string]]::new();$steps=@();$artifacts=@();$results=@{};$receipts=@{};$metrics=@{};$intervals=@{}
$coordinates=$null;$source=$null;$lock=$null;$before=@()
. (Join-Path $PSScriptRoot 'FeatureIntervalEvidence.ps1')
. (Join-Path $PSScriptRoot 'WorldgenMeasurementRegion.ps1')
function Inputs {
    $files=@();foreach($name in @('src','loaders/forge/src','loaders/neoforge/src','scripts')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -Recurse -File}
    foreach($name in @('build.gradle','settings.gradle','gradle.properties','loaders/forge/build.gradle','loaders/neoforge/build.gradle')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
function Step([string]$Name,[string[]]$Arguments) {
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=(Get-Command pwsh.exe).Source;$info.WorkingDirectory=$workspace
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($arg in @('-NoProfile','-File',(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'))+$Arguments){[void]$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try {
        if(-not $process.Start()){throw "Could not start $Name"}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();$finished=$process.WaitForExit(5400000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $root "$Name-out.log"),$stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $root "$Name-err.log"),$stderr.GetAwaiter().GetResult())
        $script:steps+=@{name=$Name;exit_code=$process.ExitCode;timed_out=(-not $finished);success=($finished -and $process.ExitCode -eq 0)}
        Write-Output "FEATURE_ASSIST_STEP name=$Name success=$($script:steps[-1].success)"
        if(-not $script:steps[-1].success){throw "Runtime step failed: $Name"}
    }finally{$process.Dispose()}
}
function Paired([double[]]$Baseline,[double[]]$Candidate) {
    if($Baseline.Count -ne 3 -or $Candidate.Count -ne 3){throw 'Three same-coordinate repeats required'}
    $rows=@(for($i=0;$i -lt 3;$i++){
        if($Baseline[$i] -le 0 -or $Candidate[$i] -le 0){throw 'Positive measurements required'}
        @{repeat=$i+1;baseline_feature_off=$Baseline[$i];candidate_feature_parallel=$Candidate[$i];ratio=$Candidate[$i]/$Baseline[$i]}
    })
    @{rows=$rows;median_paired_percent=100*(@($rows.ratio|Sort-Object)[1]-1);lower_repeats=@($rows|Where-Object {$_.ratio -lt 1}).Count}
}
try {
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $gate=Get-Content -LiteralPath (Join-Path $evidence 'summary.json') -Raw|ConvertFrom-Json
    if(-not $gate.success -or $gate.test_profile -ne 'complete-biome-authority' -or $gate.junit.tests -ne 1 -or $gate.junit.failures -or $gate.junit.errors -or $gate.junit.skipped){throw 'Exact completed dev19 build evidence required'}
    # Test-only offline metadata may differ; every production Fabric source/build input must match.
    $saved=@{};foreach($line in Get-Content -LiteralPath (Join-Path $evidence 'correctness/assisted/source-manifest-before.sha256')){
        if($line -match '^([A-F0-9]{64})  (src/(?:main|client)/.+|build\.gradle|settings\.gradle|gradle\.properties)$'){$saved[$Matches[2]]=$Matches[1]}
    }
    $files=@();foreach($name in @('src/main','src/client')){$files+=Get-ChildItem -LiteralPath (Join-Path $workspace $name) -File -Recurse}
    foreach($name in @('build.gradle','settings.gradle','gradle.properties')){$files+=Get-Item -LiteralPath (Join-Path $workspace $name)}
    if($saved.Count -ne $files.Count){throw 'Production source inventory differs'}
    foreach($file in $files){$relative=$file.FullName.Substring($workspace.Length+1).Replace('\','/');if($saved[$relative] -ne (Get-FileHash -LiteralPath $file.FullName).Hash){throw "Production changed: $relative"}}
    foreach($loader in @('fabric','forge','neoforge')){
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $loader
        $retained=Join-Path $evidence ([IO.Path]::GetFileName($artifact.Path))
        $hash=(Get-FileHash -LiteralPath $artifact.Path).Hash
        if($hash -ne (Get-FileHash -LiteralPath $retained).Hash -or $artifact.Version -ne '0.1.0-alpha.8-dev.19+mc26.3'){throw 'Current/retained artifact identity differs'}
        $artifacts+=@{loader=$loader;path=$artifact.Path;sha256=$hash;version=$artifact.Version}
    }
    if($artifacts[0].sha256 -ne $gate.artifact_sha256){throw 'Completed Fabric artifact hash differs'}
    $before=Inputs;[IO.File]::WriteAllLines((Join-Path $root 'source-before.sha256'),$before)
    Copy-Item -LiteralPath $PSCommandPath,(Join-Path $PSScriptRoot 'FeatureIntervalEvidence.ps1'),(Join-Path $PSScriptRoot 'Run-WorldgenScenario.ps1'),(Join-Path $PSScriptRoot 'Remote-WorldgenScenarioServer.ps1') -Destination $root
    $tokens=$null;$parseErrors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Compare-WorldgenScenarioMatrix.ps1'),[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count){throw 'Original receipt collector parse failed'}
    foreach($name in @('Value','ClientReceiptSamples')){
        $function=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true))
        if($function.Count -ne 1){throw 'Original receipt helper missing'};. ([scriptblock]::Create($function[0].Extent.Text))
    }
    $modes=if($Order -eq 'off-first'){@('off','parallel')}else{@('parallel','off')}
    foreach($mode in $modes){
        $case=Join-Path $root $mode
        Step $mode @('-RemoteHost','gen1c@100.103.102.109','-RemoteRoot','E:/WorldgenAssist/port26.3','-Dimension','overworld','-Mode','assisted','-Players','2','-Purpose','performance','-CacheEntries','128','-Prediction','true','-ValidationCells','8','-Seed','8675309','-NoiseBackend','cooperative','-WindowProfile','deep','-RemoteApplicationProfile','overlap','-PrefetchLookahead','0','-RemoteWorkKind','complete','-CompleteVerification','peer','-SectionPreparation','inline','-RemoteBiomes','off','-FeatureBackend',$mode,'-ViewDistance','32','-MeasureFullView','-QuietRemoteTrace','-ServerFlightRecording','-OutputRoot',$case)
        $path=Join-Path $case 'scenario-result.json';$result=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json -DateKind String
        if(-not $result.success -or -not $result.cleanup_safe -or -not $result.loopback_only -or $result.mode -ne 'assisted' -or $result.artifact_sha256 -ne $artifacts[0].sha256 -or $result.feature_backend -ne $mode -or $result.feature_fixture -or $result.decoration_digest -or $result.biome_digest -or $result.remote_biomes -ne 'off' -or $result.section_preparation -ne 'inline' -or $result.client_worker_threads -ne 4 -or $result.performance.warmup_runs -ne 1 -or $result.performance.measured_repeats -ne 3 -or $result.source_manifest_before_sha256 -ne $result.source_manifest_after_sha256){throw 'Ordinary result/profile/cleanup differs'}
        if($source -and $source -ne $result.source_manifest_after_sha256){throw 'Source differs across conditions'};$source=$result.source_manifest_after_sha256
        $config=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/scenario-config.json') -Raw|ConvertFrom-Json
        $cpu=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/server-jvm-cpu-limit.json') -Raw|ConvertFrom-Json
        $window=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/pipeline-window-config.json') -Raw|ConvertFrom-Json
        $features=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/feature-backend-config.json') -Raw|ConvertFrom-Json
        if($config.view_distance -ne 32 -or $config.timeout_ms -ne 30000 -or $cpu.requested_active_processor_count -ne 0 -or $cpu.observed_available_processors -ne 8 -or $window.owner_window -ne 32 -or $window.total_window -ne 64 -or $features.mode -ne $mode -or $features.capacity -ne 128 -or $features.message_reservation -ne 18){throw 'Actual server configuration differs'}
        foreach($owner in 0..1){if(@(Select-String -LiteralPath (Join-Path $case "clients/owner-$owner/client/options.txt") -Pattern '^renderDistance:32$').Count -ne 1){throw 'Actual client view32 missing'}}
        $log=Get-Content -LiteralPath (Join-Path $case 'remote-evidence/latest.log') -Raw
        if($log -match 'fixture\.armed|fixture\.feature_started|fixture\.complete|fixture\.failed|outside guarded stock|message reservation exceeded|Mixin apply failed|Encountered an unexpected exception|biome\.apply_rejected|worker\.quarantin|peer_terrain_(?:mismatch|difference)|job\.full_terrain_apply_rejected|Independent (?:whole-terrain audit|peer whole-terrain) mismatch'){throw 'Runtime/verification failure marker'}
        if($log -notmatch 'job\.full_terrain_accepted .*audited=true' -or $log -notmatch 'job\.peer_terrain_applied'){throw 'Actual audit/peer use missing'}
        if($mode -eq 'parallel' -and $log -notmatch 'feature\.backend mode=PARALLEL workers=2'){throw 'Actual parallel constructor missing'}
        $intervals[$mode]=@();$keys=@(foreach($repeat in 1..3){
            $match=[regex]::Match($log,"(?s)CAWG_SCENARIO_MEASURED_BEGIN_$repeat\b(?<body>.*?)CAWG_SCENARIO_MEASURED_END_$repeat\b")
            if(-not $match.Success){throw 'Closed measured window missing'};$body=$match.Groups['body'].Value
            $positions=@([regex]::Matches($body,'stage\.complete stage=noise chunk=(?<chunk>-?\d+,-?\d+)\b')|ForEach-Object {$_.Groups['chunk'].Value}|Sort-Object -Unique)
            if($positions.Count -ne 10658){throw 'Different task coordinates'}
            $interval=Get-FeatureIntervalEvidence $body
            if(-not $interval.bodies -or -not $interval.light_initializations -or $interval.conflicting_pairs -or ($mode -eq 'parallel' -and ($interval.feature_light_conflicting_pairs -or $interval.light_light_conflicting_pairs -or -not $interval.overlapping_pairs))){throw 'Measured feature/light conflict or coverage failure'}
            $intervals[$mode]+=@{repeat=$repeat;evidence=$interval};$positions -join ';'
        }) -join "`n"
        $signature=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($keys)))
        if($coordinates -and $coordinates -ne $signature){throw 'Paired coordinates differ'};$coordinates=$signature
        foreach($row in $result.performance.measured){if($row.completed_tasks -ne 10658 -or $row.failed_tasks -or $row.timeouts){throw 'Measured task failure'}}
        $receipt=ClientReceiptSamples ([pscustomobject]@{path=$path;result=$result})
        if(-not $receipt.complete -or @($receipt.coverage|Where-Object {$_.expected_chunks_per_owner -ne 3461}).Count){throw 'Actual receipt coverage missing'}
        $results[$mode]=$result;$receipts[$mode]=$receipt
    }
    foreach($metric in @('server_full_region_ready_ms','server_cpu_ms','tick_p95_ms','apply_mean_ms','server_decode_mean_ms','rtt_mean_ms')){$metrics[$metric]=Paired ([double[]]$results.off.performance.measured.$metric) ([double[]]$results.parallel.performance.measured.$metric)}
    $metrics.client_region_receipt_ms=Paired $receipts.off.samples $receipts.parallel.samples
}catch{$issues.Add($_.Exception.ToString())}
finally {
    try {
        $after=Inputs;[IO.File]::WriteAllLines((Join-Path $root 'source-after.sha256'),$after)
        if($before.Count -and ($before-join "`n") -cne ($after-join "`n")){$issues.Add('Inputs changed during comparison')}
        foreach($artifact in $artifacts){if((Get-FileHash -LiteralPath $artifact.path).Hash -ne $artifact.sha256){$issues.Add('Artifact changed during comparison')}}
    }catch{$issues.Add($_.Exception.ToString())}
    if($lock){$lock.Dispose()}
    @{schema='worldgen-assist.feature-assist-comparison.v1';success=($issues.Count -eq 0);root=$root;order=$Order;
        reused_build_evidence=$evidence;artifacts=$artifacts;new_junit=0;new_mod_builds=0;steps=$steps;metrics=$metrics;feature_intervals=$intervals;
        receipt_coverage=$receipts;coordinates_sha256=$coordinates;source_sha256=$source;issues=@($issues);beta_claim=$false;
        scope='Performance-only current-dev19 same-JAR feature off/parallel with assistance ON BOTH. Natural weak E-server and two clients on one stronger PC,view32,warm1/3same relocations. Prior controlled feature fixtures and failed original saved-light control retain their older dev9 identity; no new decoration/light parity or native scheduler proof.'
    }|ConvertTo-Json -Depth 16|Set-Content -LiteralPath (Join-Path $root 'summary.json')
}
Write-Output "FEATURE_ASSIST_COMPARISON success=$($issues.Count -eq 0) root=$root"
if($issues.Count){exit 1}
