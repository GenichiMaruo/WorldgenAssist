[CmdletBinding()]
param([Parameter(Mandatory)][string]$GateRoot,[string]$PriorOriginalRoot,
    [ValidateSet('serial','parallel','assisted')][string[]]$ComparisonCases=@('serial','parallel','assisted'))
# One offline batch over existing, stopped E-host worlds. No generated world or JUnit run.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base=[IO.Path]::GetFullPath((Join-Path $workspace 'test-artifacts'))+[IO.Path]::DirectorySeparatorChar
foreach($path in @($GateRoot,$PriorOriginalRoot)|Where-Object {$_}){if(-not [IO.Path]::GetFullPath($path).StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'Inputs must be retained workspace evidence'} }
$root=Join-Path $workspace ('test-artifacts/saved-decoration-inspection-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root|Out-Null
$lock=$null;$issues=[Collections.Generic.List[string]]::new();$sources=@{};$regions=@();$cases=@{};$records=@()
$savedJava=$env:JAVA_HOME;$savedPath=$env:Path
try{
    $lock=[IO.File]::Open((Join-Path $workspace 'test-artifacts/validation-matrix.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    foreach($file in @('build.gradle','scripts/Inspect-SavedDecoration.ps1','src/portTest/java/io/github/genichimaruo/worldgenassist/server/SavedDecorationInspector263.java','src/main/java/io/github/genichimaruo/worldgenassist/server/NoiseStageDigest.java','src/main/java/io/github/genichimaruo/worldgenassist/server/SavedLightDigest.java','src/main/java/io/github/genichimaruo/worldgenassist/server/DecorationStageDigest.java')){$sources[$file]=(Get-FileHash -LiteralPath (Join-Path $workspace $file)).Hash}
    $required=@(Get-Content -LiteralPath (Join-Path $GateRoot 'correctness/original/remote-evidence/decoration-required-chunks.json') -Raw|ConvertFrom-Json)
    if($required.Count -ne 882 -or @($required|Sort-Object -Unique).Count -ne 882){throw 'Required inventory must cover the full fixture'}
    $regionNames=@($required|ForEach-Object {
        if($_ -notmatch '^(-?\d+),(-?\d+)$'){throw 'Invalid expected coordinate'}
        'r.'+[Math]::Floor([int]$Matches[1]/32.0)+'.'+[Math]::Floor([int]$Matches[2]/32.0)+'.mca'
    }|Sort-Object -Unique)
    $names=@('original')+@($ComparisonCases|Sort-Object -Unique)
    if($PriorOriginalRoot){$names+='prior-original'}
    foreach($name in $names){
        $inputRoot=if($name -eq 'prior-original'){[IO.Path]::GetFullPath($PriorOriginalRoot)}else{Join-Path $GateRoot "correctness/$name"}
        $result=Get-Content -LiteralPath (Join-Path $inputRoot 'scenario-result.json') -Raw|ConvertFrom-Json
        $remote=Get-Content -LiteralPath (Join-Path $inputRoot 'remote-evidence/remote-result.json') -Raw|ConvertFrom-Json
        if(-not $result.success -or -not $result.cleanup_safe -or -not $remote.success -or -not $remote.cleanup_safe -or -not $remote.loopback_only -or $remote.case -notmatch '^scenario-overworld-(vanilla|assisted)-p2-correctness-[0-9-]+$'){throw 'Original runtime is incomplete or has invalid owned world identity'}
        $remotePath='E:/WorldgenAssist/port26.3/'+$remote.case+'/dimensions/minecraft/overworld/region'
        $local=Join-Path $root "$name/region";New-Item -ItemType Directory -Force -Path $local|Out-Null
        $code='$ErrorActionPreference="Stop";$ProgressPreference="SilentlyContinue";$env:TEMP="E:/WorldgenAssist/temp";$env:TMP=$env:TEMP;$root='''+$remotePath+''';$names=@('+(($regionNames|ForEach-Object {"'$_'"}) -join ',')+');@($names|ForEach-Object {$path=Join-Path $root $_;$file=Get-Item -LiteralPath $path;if($file.Length -gt 128MB){throw "Region inspection input too large"};[ordered]@{name=$_;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $path).Hash}})|ConvertTo-Json'
        $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
        $hashes=& ssh.exe -o BatchMode=yes -o ConnectTimeout=15 gen1c@100.103.102.109 "powershell -NoProfile -NonInteractive -EncodedCommand $encoded"
        if($LASTEXITCODE -ne 0){throw 'Read-only remote region hashing failed'}
        $hashes|Set-Content -LiteralPath (Join-Path $root "$name/remote-region-hashes.json")
        $inventory=@(($hashes -join "`n")|ConvertFrom-Json)
        if($inventory.Count -ne $regionNames.Count -or (($inventory.name|Sort-Object)-join ';') -cne ($regionNames-join ';')){throw 'Remote retained region inventory differs'}
        foreach($file in $inventory){
            & scp.exe -q ('gen1c@100.103.102.109:'+$remotePath+'/'+$file.name) $local
            if($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath (Join-Path $local $file.name)).Hash -ne $file.sha256){throw 'Retained world transfer/hash mismatch'}
        }
        $cases[$name]=$local
        $records+=[ordered]@{name=$name;original_case=$inputRoot;remote_world=$remote.case;original_jar_sha256=$result.artifact_sha256;regions=$inventory}
    }
    $descriptor=Join-Path $root 'descriptor.json'
    @{output=(Join-Path $root 'saved-blocks.json');coordinates=$required;cases=$cases}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $descriptor -Encoding utf8
    $env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4';$env:Path="$env:JAVA_HOME\bin;$env:Path"
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName="$env:SystemRoot\System32\cmd.exe";$info.WorkingDirectory=$workspace
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($arg in @('/d','/c',(Join-Path $workspace 'gradlew.bat'),'inspectSavedDecoration',('-PsavedDecorationDescriptor='+$descriptor))){[void]$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(-not $process.Start()){throw 'Could not start original-game-codec inspector'}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        $finished=$process.WaitForExit(600000)
        if(-not $finished){$process.Kill($true);$process.WaitForExit()}
        $stdout.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $root 'inspection-out.log')
        $stderr.GetAwaiter().GetResult()|Set-Content -LiteralPath (Join-Path $root 'inspection-err.log')
        if(-not $finished -or $process.ExitCode -ne 0){throw 'Original-game-codec inspection failed; see retained logs'}
    }finally{$process.Dispose()}
    foreach($file in $sources.Keys){if($sources[$file] -ne (Get-FileHash -LiteralPath (Join-Path $workspace $file)).Hash){throw 'Inspector/source changed during read-only analysis'}}
}catch{$issues.Add($_.Exception.ToString())}
finally{
    if($lock){$lock.Dispose()}
    $env:JAVA_HOME=$savedJava;$env:Path=$savedPath
    @{schema='worldgen-assist.retained-decoration-inspection.v1';success=($issues.Count -eq 0);root=$root;sources=$sources;inputs=$records;issues=@($issues);scope='Read-only existing saved worlds; not a new runtime or beta gate'}|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $root 'summary.json')
    Write-Output "RETAINED_DECORATION_INSPECTION success=$($issues.Count -eq 0) summary=$(Join-Path $root 'summary.json')"
}
if($issues.Count){exit 1}
