[CmdletBinding()]
param([Parameter(Mandatory)][string]$EvidenceRoot)
# One offline original-code analysis of immutable, already stopped worlds.
# No JUnit, MOD build, Minecraft process, remote transfer or saved-world write.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base=Join-Path $workspace 'test-artifacts'
$evidence=(Resolve-Path -LiteralPath $EvidenceRoot).Path
if(-not $evidence.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Owned retained evidence required'}
$root=Join-Path $base ('saved-lighting-diagnosis-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$lock=$null;$oldJava=$env:JAVA_HOME;$oldPath=$env:Path
$issues=[Collections.Generic.List[string]]::new();$success=$false;$regions=@();$exitCode=$null;$report=$null;$parent=$null
function Inputs {
    $files=@(Get-ChildItem -LiteralPath (Join-Path $workspace 'src') -Recurse -File)
    $files+=Get-Item -LiteralPath (Join-Path $workspace 'build.gradle'),$PSCommandPath
    @($files|Sort-Object FullName|ForEach-Object {(Get-FileHash -LiteralPath $_.FullName).Hash+'  '+$_.FullName.Substring($workspace.Length+1).Replace('\','/')})
}
try {
    $lock=[IO.File]::Open((Join-Path $base 'validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $parent=Get-Content -LiteralPath (Join-Path $evidence 'summary.json') -Raw|ConvertFrom-Json
    $prior=Get-Content -LiteralPath (Join-Path $evidence 'lighting-invariants.json') -Raw|ConvertFrom-Json
    if($parent.success -or $prior.success -or $parent.junit.tests -ne 1 -or $parent.junit.failures -or $parent.junit.errors -or $parent.junit.skipped){throw 'Expected retained failed original lighting control and successful reader evidence'}
    if($prior.cases.Count -ne 3 -or $parent.inputs.Count -ne 3){throw 'Exactly three retained ordinary conditions required'}
    $cases=@{}
    foreach($input in $parent.inputs){
        $original=Get-Content -LiteralPath (Join-Path $input.source_case 'scenario-result.json') -Raw|ConvertFrom-Json
        if(-not $original.success -or -not $original.cleanup_safe){throw 'Stopped successful source runtime required'}
        $directory=(Resolve-Path -LiteralPath (Join-Path $evidence ($input.name+'/region'))).Path
        foreach($region in $input.regions){
            $file=Join-Path $directory $region.name
            if((Get-Item -LiteralPath $file).Length -ne $region.bytes -or (Get-FileHash -LiteralPath $file).Hash -ne $region.sha256){throw 'Retained region bytes changed'}
            $regions+=@{path=$file;sha256=$region.sha256}
        }
        $cases[$input.name]=$directory
    }
    $before=Inputs;[IO.File]::WriteAllLines((Join-Path $root 'source-before.sha256'),$before)
    Copy-Item -LiteralPath $PSCommandPath,(Join-Path $workspace 'src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightingInspector263.java'),(Join-Path $workspace 'src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedLightView263.java') -Destination $root
    $descriptor=Join-Path $root 'descriptor.json'
    @{output=(Join-Path $root 'lighting-diagnosis.json');centers=@(@{x=1512;z=-2512},@{x=-1512;z=2512});cases=$cases}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $descriptor
    $env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4';$env:Path="$env:JAVA_HOME\bin;$env:Path"
    & (Join-Path $workspace 'gradlew.bat') inspectSavedLighting ('-PsavedLightingDescriptor='+$descriptor) --no-daemon *> (Join-Path $root 'analysis.log')
    $exitCode=$LASTEXITCODE
    $report=Get-Content -LiteralPath (Join-Path $root 'lighting-diagnosis.json') -Raw|ConvertFrom-Json
    if($exitCode -ne 1 -or $report.success){throw 'Original strict lighting failure must remain visible'}
    foreach($row in $report.cases){
        $old=@($prior.cases|Where-Object {$_.case -eq $row.case})
        if($old.Count -ne 1){throw 'Original condition missing'}
        foreach($field in @('success','halo_chunks','required_chunks','compared_values','changed_chunks','block_differences','sky_differences')){
            if($row.$field -ne $old[0].$field){throw "Unchanged invariant result differs: $($row.case)/$field"}
        }
    }
    foreach($region in $regions){if((Get-FileHash -LiteralPath $region.path).Hash -ne $region.sha256){throw 'Offline inspector changed retained evidence'}}
    $after=Inputs;[IO.File]::WriteAllLines((Join-Path $root 'source-after.sha256'),$after)
    if(($before-join "`n") -cne ($after-join "`n")){throw 'Analysis inputs changed while running'}
    $success=$true
}catch{$issues.Add($_.Exception.ToString())}
finally {
    $env:JAVA_HOME=$oldJava;$env:Path=$oldPath
    if($lock){$lock.Dispose()}
    @{schema='worldgen-assist.saved-lighting-diagnosis.v1';success=$success;root=$root;parent=$evidence;
        source_runtime_artifacts=if($parent){$parent.artifacts}else{@()};regions=$regions;inspector_exit_code=$exitCode;
        lighting_invariant_success=if($report){$report.success}else{$null};cases=if($report){$report.cases}else{@()};issues=@($issues);
        junit_tests=0;mod_builds=0;new_game_runs=0;remote_operations=0;
        scope='Diagnosis completion only. Original lighting gate remains FAILED. Own saved blocks, original source-height calculation, unchanged propagation and saved reader; no saved values repaired and no new production runtime proof.'
    }|ConvertTo-Json -Depth 18|Set-Content -LiteralPath (Join-Path $root 'summary.json')
}
Write-Output "SAVED_LIGHT_DIAGNOSIS_COMPLETE success=$success root=$root"
if(-not $success){exit 1}
