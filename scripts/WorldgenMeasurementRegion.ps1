# Minecraft 26.3 ChunkTrackingView.isWithinDistance(..., false), verified from
# .gradle/source-inspect-26.3/net/minecraft/server/level/ChunkTrackingView.java.
function Get-WorldgenMeasurementOffsets([int]$Radius, [string]$Shape = 'square') {
    for ($dx = -$Radius; $dx -le $Radius; $dx++) {
        for ($dz = -$Radius; $dz -le $Radius; $dz++) {
            $x = [Math]::Max(0, [Math]::Abs($dx) - 1)
            $z = [Math]::Max(0, [Math]::Abs($dz) - 1)
            if ($Shape -eq 'square' -or $x*$x + $z*$z -lt $Radius*$Radius) {
                [pscustomobject]@{x=$dx;z=$dz}
            }
        }
    }
}
