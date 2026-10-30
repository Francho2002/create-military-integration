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
$containerCode = Inspect 'dev.ryanhcode.sable.api.sublevel.SubLevelContainer' '-c'
$getChunk = [regex]::Match($containerCode, '(?s)public net\.minecraft\.world\.level\.chunk\.LevelChunk getChunk\([^\r\n]*\);(.*?)(?=\n\s*public |\n\s*protected |\z)').Groups[1].Value
if ($getChunk -notmatch '(?s)LevelPlot\.toLocal:.*LevelPlot\.getChunk:') { throw 'Sable global-to-local chunk contract changed' }
$ship = Inspect 'dev.ryanhcode.sable.sublevel.ServerSubLevel' '-s'
if (!$ship.Contains('()Lnet/minecraft/server/level/ServerLevel;') -or !$ship.Contains('()Ldev/ryanhcode/sable/sublevel/plot/ServerLevelPlot;')) { throw 'ServerSubLevel plot/level descriptors changed' }
$pose = Inspect 'dev.ryanhcode.sable.companion.math.Pose3dc' '-s'
if (!$pose.Contains('(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;') -or !$pose.Contains('transformPositionInverse')) { throw 'Sable inverse transform signature changed' }
$clipApi = Inspect 'dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension' '-s'
if ($clipApi -notmatch '(?s)sable\$setDoNotProject\(boolean\);\s*descriptor:\s*\(Z\)V') { throw 'Sable clip-control descriptor changed' }
$clipCode = Inspect 'dev.ryanhcode.sable.mixin.clip_overwrite.BlockGetterMixin' '-c'
if ($clipCode -notmatch '(?s)sable\$doNotProject.*originalClip') { throw 'Sable main-world-only clipping path changed' }
$chunkMixin = Inspect 'dev.ryanhcode.sable.mixin.plot.LevelChunkMixin' '-c'
if ($chunkMixin -notmatch 'SableCommonEvents.handleBlockChange') { throw 'Sable block-change callback path changed' }
$firstHit = Get-Content -Raw -LiteralPath "$root/src/main/java/local/cmi/core/SableFirstHit.java"
foreach ($required in @('ship.getPlot().getChunk(ship.getPlot().toLocal(chunkPos))', 'extension.sable$setDoNotProject(true)', 'mainWorldCorridorClear', 'CombatServices.relation')) {
    if (!$firstHit.Contains($required)) { throw "Missing first-hit guard: $required" }
}
'OK: pinned Sable plot/chunk/level/inverse-transform signatures, global-to-local chunk conversion and chunk-holder lookup. Does not initialize Sable or execute world geometry.'
