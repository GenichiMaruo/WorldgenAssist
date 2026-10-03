# Capture each owned process's console in a non-rolling evidence file. Keep
# draining asynchronously; never wait for process completion to observe logs.
function Start-WorldgenConsoleCapture([Diagnostics.Process]$Process,[string]$Path) {
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite,1,$true)
    try {
        $completion=$Process.StandardOutput.BaseStream.CopyToAsync($stream)
        return [pscustomobject]@{Process=$Process;Path=$Path;Stream=$stream;Completion=$completion;Closed=$false;EvidenceSaved=$false}
    } catch {$stream.Dispose();throw}
}

function Read-WorldgenConsoleText([object]$Capture) {
    if($null -eq $Capture){return ' '}
    if($Capture.Completion.IsFaulted){[void]$Capture.Completion.GetAwaiter().GetResult()}
    $stream=[IO.FileStream]::new($Capture.Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
    $reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8,$true)
    try {
        $text=$reader.ReadToEnd()
        if([string]::IsNullOrEmpty($text)){return ' '}
        return $text
    } finally {$reader.Dispose()}
}

function Complete-WorldgenConsoleCapture([object]$Capture) {
    if($null -eq $Capture -or $Capture.Closed){return}
    if(-not $Capture.Process.HasExited){throw 'Stop the owned process before completing its console capture'}
    try {[void]$Capture.Completion.GetAwaiter().GetResult();$Capture.Stream.Flush()}
    finally {$Capture.Stream.Dispose();$Capture.Closed=$true}
}

function Save-WorldgenConsoleEvidence([object]$Capture,[string]$OriginalLog,[string]$Destination) {
    if($null -eq $Capture -or $Capture.EvidenceSaved){return}
    Complete-WorldgenConsoleCapture $Capture
    $directory=Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    if(Test-Path -LiteralPath $OriginalLog -PathType Leaf){
        Copy-Item -LiteralPath $OriginalLog -Destination (Join-Path $directory 'minecraft-latest.log') -Force
    }
    Copy-Item -LiteralPath $Capture.Path -Destination $Destination -Force
    $Capture.EvidenceSaved=$true
}
