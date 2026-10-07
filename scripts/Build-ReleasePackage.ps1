[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CandidateEvidenceRoot,
    [Parameter(Mandatory)][string]$OutputRoot,
    [Parameter(Mandatory)][string]$ExpectedVersion,
    [string]$CandidateVersion='0.1.0-alpha.6-dev.12+mc26.3',
    [string]$NativeEvidenceRoot
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$candidateRoot=[IO.Path]::GetFullPath($CandidateEvidenceRoot)
$releaseRoot=[IO.Path]::GetFullPath($OutputRoot)
$evidenceParent=[IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts'))+[IO.Path]::DirectorySeparatorChar
if(-not $candidateRoot.StartsWith($evidenceParent,[StringComparison]::OrdinalIgnoreCase) -or
   -not $releaseRoot.StartsWith($evidenceParent,[StringComparison]::OrdinalIgnoreCase) -or
   $candidateRoot -eq $releaseRoot){throw 'Release and candidate roots must be distinct children of test-artifacts'}
if(Test-Path -LiteralPath (Join-Path $releaseRoot 'build-verification.json')){throw 'Release evidence already exists'}
$candidate=Get-Content -LiteralPath (Join-Path $candidateRoot 'summary.json') -Raw|ConvertFrom-Json
if(-not $candidate.success -or $candidate.junit.failures -or $candidate.junit.errors -or $candidate.junit.skipped){throw 'Candidate evidence failed'}
if($CandidateVersion -eq '0.1.0-alpha.6-dev.12+mc26.3'){
    if($candidate.schema -ne 'worldgen-assist.remote-window-gate.v1' -or $candidate.junit.tests -ne 5){throw 'Expected successful focused alpha.6 dev.12 evidence'}
}elseif($CandidateVersion -eq '0.1.0-alpha.7-dev.16+mc26.3'){
    if($candidate.schema -ne 'worldgen-assist.block-density-gate.v1' -or $candidate.test_profile -ne 'complete-shaping' -or
       $candidate.junit.tests -ne 7 -or $candidate.local_only -or $candidate.complete_verification -ne 'peer'){throw 'Expected complete AG affected evidence'}
    $nativeRoot=[IO.Path]::GetFullPath($NativeEvidenceRoot)
    if(-not $nativeRoot.StartsWith($evidenceParent,[StringComparison]::OrdinalIgnoreCase)){throw 'Native evidence must be a workspace evidence child'}
    $native=Get-Content -LiteralPath (Join-Path $nativeRoot 'summary.json') -Raw|ConvertFrom-Json
    if(-not $native.success -or $native.build_evidence -ne $candidateRoot -or $native.runtime_fresh -ne $true -or $native.issues.Count){throw 'Fresh matching native evidence missing'}
    $performance=Get-Content -LiteralPath $candidate.performance_summary -Raw|ConvertFrom-Json
    if($performance.status -ne 'COMPLETE' -or $performance.artifact_sha256 -ne $candidate.artifact_sha256 -or $performance.issues.Count){throw 'Physical performance identity missing'}
    # Only mod_version may change; never relabel a changed production source with old runtime evidence.
    foreach($line in Get-Content -LiteralPath (Join-Path $candidateRoot 'correctness/assisted/source-manifest-before.sha256')){
        if($line -notmatch '^([A-F0-9]{64})  (src/.+|build\.gradle|settings\.gradle|gradle\.properties|loaders/.+/build\.gradle|loaders/forge/src/main/resources/worldgen_assist.mixins.json)$'){continue}
        $expected=$Matches[1];$relative=$Matches[2];$path=Join-Path $workspace $relative
        $actual=if($relative -eq 'gradle.properties'){
            $text=[IO.File]::ReadAllText($path)
            $text=[regex]::Replace($text,'(?m)^mod_version='+[regex]::Escape($ExpectedVersion)+'(?=\r?$)',('mod_version='+$CandidateVersion))
            [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text)))
        }else{(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
        if($actual -ne $expected){throw "Candidate production source changed: $relative"}
    }
}elseif($CandidateVersion -eq '0.1.0-alpha.8-dev.23+mc26.3'){
    if($candidate.schema -ne 'worldgen-assist.feature-pipeline-gate.v1' -or
       -not $candidate.authoritative_biome_inputs -or $candidate.local_only -or
       $candidate.junit.tests -ne 4 -or $candidate.issues.Count -or
       $candidate.steps.Count -ne 16 -or @($candidate.steps|Where-Object {-not $_.success -or $_.exit_code -ne 0 -or $_.timed_out}).Count){
        throw 'Expected closed successful dev23 four-method/three-loader gate'
    }
    foreach($line in Get-Content -LiteralPath (Join-Path $candidateRoot 'source-manifest-before.sha256')){
        if($line -notmatch '^([A-F0-9]{64})  (src/.+|build\.gradle|settings\.gradle|gradle\.properties|loaders/[^/]+/(?:src/.+|build\.gradle))$'){continue}
        $expected=$Matches[1];$relative=$Matches[2];$path=Join-Path $workspace $relative
        $actual=if($relative -eq 'gradle.properties'){
            $normalized=[regex]::Replace([IO.File]::ReadAllText($path),'(?m)^mod_version='+[regex]::Escape($ExpectedVersion)+'(?=\r?$)',('mod_version='+$CandidateVersion))
            [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($normalized)))
        }else{(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
        if($actual -ne $expected){throw "Candidate production source changed: $relative"}
    }
}else{throw 'Unsupported release candidate identity'}
New-Item -ItemType Directory -Force -Path $releaseRoot,(Join-Path $releaseRoot 'candidate'),(Join-Path $releaseRoot 'assets')|Out-Null
$candidateManifest=@()
foreach($loader in @('fabric','forge','neoforge')){
    $name='worldgen-assist'+$(if($loader -ne 'fabric'){'-'+$loader})+'-'+$CandidateVersion+'.jar'
    $path=Join-Path $candidateRoot $name
    $expected=if($CandidateVersion -eq '0.1.0-alpha.8-dev.23+mc26.3'){
        $matching=@($candidate.artifacts|Where-Object loader -eq $loader)
        if($matching.Count -ne 1 -or $matching[0].version -ne $CandidateVersion){throw 'Ambiguous candidate artifact'}
        $matching[0].sha256
    }elseif($loader -eq 'fabric'){$candidate.artifact_sha256}else{
        @($candidate.native_artifacts|Where-Object loader -eq $loader)[0].sha256
    }
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $expected){throw "Candidate hash mismatch: $loader"}
    Copy-Item -LiteralPath $path -Destination (Join-Path $releaseRoot "candidate/$loader.jar")
    $sourcePath=Join-Path $candidateRoot ($name -replace '\.jar$','-sources.jar')
    if(-not(Test-Path -LiteralPath $sourcePath) -and $CandidateVersion -in @('0.1.0-alpha.7-dev.16+mc26.3','0.1.0-alpha.8-dev.23+mc26.3')){
        $buildRoot=if($loader -eq 'fabric'){$workspace}else{Join-Path $workspace "loaders/$loader"}
        $sourcePath=Join-Path $buildRoot ('build/libs/'+($name -replace '\.jar$','-sources.jar'))
    }
    Copy-Item -LiteralPath $sourcePath -Destination (Join-Path $releaseRoot "candidate/$loader-sources.jar")
    $candidateManifest+=[ordered]@{loader=$loader;sha256=$expected;sources_sha256=(Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash}
}
$candidateManifest|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $releaseRoot 'candidate-manifest.json') -Encoding utf8
$oldJava=$env:JAVA_HOME;$oldPath=$env:Path
$lock=[IO.File]::Open((Join-Path $workspace 'test-artifacts/validation-matrix.lock'),'OpenOrCreate','ReadWrite','None')
try{
    $env:JAVA_HOME='C:/Program Files/Java/jdk-25.0.4'
    $env:Path="$env:JAVA_HOME/bin;$oldPath"
    $steps=@()
    foreach($loader in @('fabric','forge','neoforge')){
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $loader
        if($artifact.Version -ne $ExpectedVersion){throw 'Release metadata differs from requested version'}
        $arguments=@('assemble','-x','test','--console=plain','--no-daemon')
        if($loader -ne 'fabric'){$arguments=@('-p',(Join-Path $workspace "loaders/$loader"))+$arguments}
        $log=Join-Path $releaseRoot "build-$loader.log"
        & (Join-Path $workspace 'gradlew.bat') @arguments *> $log
        if($LASTEXITCODE -ne 0){Get-Content -LiteralPath $log -Tail 45;throw "$loader release build failed"}
        $steps+=[ordered]@{loader=$loader;status='PASSED';log=$log}
        Write-Output "RELEASE_BUILD_COMPLETE loader=$loader"
    }
    Add-Type -AssemblyName System.IO.Compression
    function Read-Entries([string]$Path){
        $zip=[IO.Compression.ZipFile]::OpenRead($Path);$entries=@{}
        try{
            foreach($entry in $zip.Entries){
                if($entry.FullName.EndsWith('/')){continue}
                $stream=$entry.Open()
                try{$entries[$entry.FullName]=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))}finally{$stream.Dispose()}
            }
        }finally{$zip.Dispose()}
        return ,$entries
    }
    function Read-TextEntry([string]$Path,[string]$Name){
        $zip=[IO.Compression.ZipFile]::OpenRead($Path)
        try{
            $entry=$zip.GetEntry($Name)
            if($null -eq $entry){throw "Missing archive entry: $Name"}
            $reader=[IO.StreamReader]::new($entry.Open())
            try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
        }finally{$zip.Dispose()}
    }
    $artifacts=@()
    foreach($loader in @('fabric','forge','neoforge')){
        $artifact=& (Join-Path $PSScriptRoot 'Get-WorldgenArtifact.ps1') -Loader $loader
        $beforePath=Join-Path $releaseRoot "candidate/$loader.jar"
        $before=Read-Entries $beforePath;$after=Read-Entries $artifact.Path
        $names=@(@($before.Keys)+@($after.Keys)|Sort-Object -Unique)
        $differences=@($names|Where-Object {-not $before.ContainsKey($_) -or -not $after.ContainsKey($_) -or $before[$_] -ne $after[$_]})
        $metadata=switch($loader){'fabric'{'fabric.mod.json'} 'forge'{'META-INF/mods.toml'} 'neoforge'{'META-INF/neoforge.mods.toml'}}
        if($differences.Count -ne 1 -or $differences[0] -ne $metadata){throw "Unexpected archive changes for ${loader}: $($differences -join ', ')"}
        $oldText=Read-TextEntry $beforePath $metadata
        $newText=Read-TextEntry $artifact.Path $metadata
        if($newText -notmatch [regex]::Escape($ExpectedVersion) -or
           $oldText.Replace($CandidateVersion,$ExpectedVersion) -cne $newText){throw 'Version is not the sole metadata change'}
        if(-not $after.ContainsKey('icon.png') -or
           ($loader -eq 'fabric' -and -not $after.ContainsKey('worldgen_assist.fabric.mixins.json'))){throw 'Missing release resource'}
        $sourceBefore=Read-Entries (Join-Path $releaseRoot "candidate/$loader-sources.jar")
        $sourceAfter=Read-Entries $artifact.SourcesPath
        $sourceNames=@(@($sourceBefore.Keys)+@($sourceAfter.Keys)|Sort-Object -Unique)
        $sourceChanges=@($sourceNames|Where-Object {-not $sourceBefore.ContainsKey($_) -or -not $sourceAfter.ContainsKey($_) -or $sourceBefore[$_] -ne $sourceAfter[$_]})
        if($sourceChanges.Count){throw "Unexpected source archive change: $($sourceChanges -join ', ')"}
        foreach($path in @($artifact.Path,$artifact.SourcesPath)){Copy-Item -LiteralPath $path -Destination (Join-Path $releaseRoot 'assets')}
        $artifacts+=[ordered]@{loader=$loader;file=$artifact.FileName;sha256=(Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash;
            sources_file=[IO.Path]::GetFileName($artifact.SourcesPath);sources_sha256=(Get-FileHash -LiteralPath $artifact.SourcesPath -Algorithm SHA256).Hash;
            candidate_sha256=(Get-FileHash -LiteralPath $beforePath -Algorithm SHA256).Hash;
            class_count=@($after.Keys|Where-Object {$_.EndsWith('.class')}).Count;changed_entries=$differences;runtime_content_unchanged=$true;source_entries_unchanged=$true}
    }
    [ordered]@{version=$ExpectedVersion;success=$true;candidate_evidence=$candidateRoot;builds=$steps;artifacts=$artifacts;
        candidate_version=$CandidateVersion;native_evidence=$NativeEvidenceRoot;
        verification='Only exact version metadata differs from the identified candidate; source entries unchanged. Prior tests retain original artifact identities. No new runtime/unit/performance tests.'}|
        ConvertTo-Json -Depth 7|Set-Content -LiteralPath (Join-Path $releaseRoot 'build-verification.json') -Encoding utf8
    Write-Output 'RELEASE_PACKAGE_VERIFIED'
}finally{$lock.Dispose();$env:JAVA_HOME=$oldJava;$env:Path=$oldPath}
