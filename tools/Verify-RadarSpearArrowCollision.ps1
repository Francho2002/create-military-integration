param([Parameter(Mandatory)][string]$Minecraft, [Parameter(Mandatory)][string]$Sable,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar")
$ErrorActionPreference = 'Stop'
function Inspect([string]$jar, [string]$type, [string]$mode) {
    $out = (& javap -p $mode -classpath $jar $type 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "javap failed for $type : $out" }
    return $out
}
$arrow = Inspect $Minecraft 'net.minecraft.world.entity.projectile.AbstractArrow' '-c'
$tick = [regex]::Match($arrow, '(?s)public void tick\(\);(.*?)(?=\n\s*(?:public|protected|private) |\z)').Groups[1].Value
$location = 'Method net/minecraft/world/phys/HitResult.getLocation:\(\)Lnet/minecraft/world/phys/Vec3;'
$find = 'Method findHitEntity:\(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;\)Lnet/minecraft/world/phys/EntityHitResult;'
if ([regex]::Matches($tick, $location).Count -ne 1 -or [regex]::Matches($tick, $find).Count -ne 1 -or $tick -notmatch "(?s)382: invokevirtual[^`n]*$location.*385: astore\s+6.*387: aload_0.*388: invokevirtual[^`n]*Method isRemoved:\(\)Z.*391: ifne\s+560.*399: invokevirtual[^`n]*$find.*402: astore\s+8.*409: aload\s+8.*411: astore\s+7") { throw 'AbstractArrow tick collision sequence, offsets or descriptors changed' }
$container = Inspect $Sable 'dev.ryanhcode.sable.api.sublevel.SubLevelContainer' '-s'
$plot = Inspect $Sable 'dev.ryanhcode.sable.sublevel.plot.LevelPlot' '-s'
foreach ($pair in @(@($container, 'getPlot\(net.minecraft.world.level.ChunkPos\);\s*descriptor:\s*\(Lnet/minecraft/world/level/ChunkPos;\)Ldev/ryanhcode/sable/sublevel/plot/LevelPlot;'), @($plot, 'getSubLevel\(\);\s*descriptor:\s*\(\)Ldev/ryanhcode/sable/sublevel/SubLevel;'), @($plot, 'contains\(net.minecraft.world.level.ChunkPos\);\s*descriptor:\s*\(Lnet/minecraft/world/level/ChunkPos;\)Z'))) { if ($pair[0] -notmatch $pair[1]) { throw 'Sable plot descriptor changed' } }
if ($container -notmatch 'inBounds\(net.minecraft.core.BlockPos\);\s*descriptor:\s*\(Lnet/minecraft/core/BlockPos;\)Z' -or $plot -notmatch 'toLocal\(net.minecraft.world.level.ChunkPos\);\s*descriptor:\s*\(Lnet/minecraft/world/level/ChunkPos;\)Lnet/minecraft/world/level/ChunkPos;') { throw 'Sable plot coordinate descriptor changed' }
$clip = Inspect $Sable 'dev.ryanhcode.sable.mixin.clip_overwrite.BlockGetterMixin' '-c'
if ($clip -notmatch '(?s)originalClip:.*BlockHitResult.getType:.*aload\s+20\s+\d+: astore\s+9') { throw 'Sable plot clip selection changed' }
$mixin = Inspect $Artifact 'local.cmi.mixin.missiles.RadarSpearArrowCollisionMixin' '-s'
if ($mixin -notmatch '(?s)cmi\$worldEntityTraceEnd\([^\r\n]*\);\s*descriptor:\s*\(Lnet/minecraft/world/phys/HitResult;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;\)Lnet/minecraft/world/phys/Vec3;') { throw 'CMI wrapper descriptor changed' }
$source = Get-Content -Raw -LiteralPath "$PSScriptRoot/../src/main/java/local/cmi/mixin/missiles/RadarSpearArrowCollisionMixin.java"
foreach ($required in @('@WrapOperation(method = "tick"', 'Lnet/minecraft/world/phys/HitResult;getLocation()Lnet/minecraft/world/phys/Vec3;', 'if (!cmi$managed(self)) return location;', 'RadarSpearBridge.discard(self)', 'plot.getChunk(plot.toLocal(chunk))', 'RadarSpearCollisionCoordinates.worldEndpoint')) {
    if (!$source.Contains($required)) { throw "Missing wrapper contract: $required" }
}
'OK: arrow tick location/entity collision sequence and Sable plot API/clip selection; runtime mixin application is not tested.'
