param(
    [Parameter(Mandatory=$true)][string]$Password,
    [Parameter(Mandatory=$true)][string]$Command,
    [ValidateRange(1,65535)][int]$Port = 25575
)

$ErrorActionPreference = 'Stop'
$client = [System.Net.Sockets.TcpClient]::new()
try {
    $client.Connect([System.Net.IPAddress]::Loopback, $Port)
    $client.ReceiveTimeout = 10000
    $client.SendTimeout = 10000
    $stream = $client.GetStream()
    $reader = [System.IO.BinaryReader]::new($stream)

    function Send-Packet([int]$Id, [int]$Type, [string]$Text) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        $buffer = [System.IO.MemoryStream]::new()
        try {
            $writer = [System.IO.BinaryWriter]::new($buffer)
            $writer.Write([int](10 + $bytes.Length))
            $writer.Write($Id)
            $writer.Write($Type)
            $writer.Write($bytes)
            $writer.Write([byte]0)
            $writer.Write([byte]0)
            $packet = $buffer.ToArray()
            $stream.Write($packet, 0, $packet.Length)
            $stream.Flush()
        } finally { $buffer.Dispose() }
    }

    function Read-Packet {
        $length = $reader.ReadInt32()
        if ($length -lt 10 -or $length -gt 1048576) { throw "Invalid RCON packet length: $length" }
        $body = $reader.ReadBytes($length)
        if ($body.Length -ne $length) { throw 'Truncated RCON packet' }
        $id = [BitConverter]::ToInt32($body, 0)
        $type = [BitConverter]::ToInt32($body, 4)
        $terminator = [Array]::IndexOf($body, [byte]0, 8)
        if ($terminator -lt 8) { throw 'RCON response is not terminated' }
        [pscustomobject]@{
            Id = $id
            Type = $type
            Text = [System.Text.Encoding]::UTF8.GetString($body, 8, $terminator - 8)
        }
    }

    Send-Packet 9123 3 $Password
    $authenticated = $false
    for ($index = 0; $index -lt 3; $index++) {
        $packet = Read-Packet
        if ($packet.Id -eq -1) { throw 'RCON authentication failed' }
        if ($packet.Id -eq 9123 -and $packet.Type -eq 2) { $authenticated = $true; break }
    }
    if (-not $authenticated) { throw 'RCON authentication response was not received' }
    Send-Packet 9124 2 $Command
    $response = Read-Packet
    if ($response.Id -ne 9124 -or $response.Type -ne 0) { throw 'Unexpected RCON command response' }
    $response.Text
} finally {
    $client.Dispose()
}
