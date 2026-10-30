param([Parameter(Mandatory)][string]$Sable)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$nested = @((Get-ChildItem -LiteralPath "$root/build/compile-nested" -File -Filter '*.jar').FullName)
$classpath = (@($Sable) + $nested) -join [IO.Path]::PathSeparator
function Inspect([string]$type, [string]$mode) {
    $listing = (& javap -p $mode -classpath $classpath $type 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "javap failed: $type : $listing" }
    return $listing
}
$plot = Inspect 'dev.ryanhcode.sable.sublevel.plot.LevelPlot' '-s'
foreach ($descriptor in @('(Lnet/minecraft/world/level/ChunkPos;)Z', '(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/LevelChunk;')) {
    if (!$plot.Contains($descriptor)) { throw "Plot API descriptor missing: $descriptor" }
}
$bytecode = Inspect 'dev.ryanhcode.sable.sublevel.plot.LevelPlot' '-c'
if ($bytecode -notmatch 'getChunkHolder' -or $bytecode -notmatch 'PlotChunkHolder.getChunk') { throw 'Plot chunk-holder path changed' }
$ship = Inspect 'dev.ryanhcode.sable.sublevel.ServerSubLevel' '-s'
if (!$ship.Contains('()Lnet/minecraft/server/level/ServerLevel;') -or !$ship.Contains('()Ldev/ryanhcode/sable/sublevel/plot/ServerLevelPlot;')) { throw 'ServerSubLevel plot/level descriptors changed' }
$pose = Inspect 'dev.ryanhcode.sable.companion.math.Pose3dc' '-s'
if (!$pose.Contains('(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;') -or !$pose.Contains('transformPositionInverse')) { throw 'Sable inverse transform signature changed' }
'OK: pinned Sable plot/chunk/level/inverse-transform signatures and chunk-holder lookup. Does not initialize Sable or execute world geometry.'
