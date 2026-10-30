param(
    [Parameter(Mandatory)][string]$Warium,
    [Parameter(Mandatory)][string]$Sable,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference='Stop'
function Listing([string]$jar,[string]$type,[string]$mode) {
    $text=(& javap -p $mode -classpath $jar $type 2>&1)-join "`n"
    if($LASTEXITCODE -ne 0){throw "javap failed for $type : $text"}
    return $text
}
$hash=(Get-FileHash -LiteralPath $Warium -Algorithm SHA256).Hash
if($hash -ne 'AA0DD2FA8B5C8C2B4FFF40B4FE2189D94BC9D07240F43A5E6AF95D109B39E27E'){throw "Unexpected Warium jar hash: $hash"}
$tank=Listing $Warium 'com.wariumce.procedures.TankFireProjectileHitsBlockProcedure' '-s'
$small=Listing $Warium 'com.wariumce.procedures.SmallExplosionProcedure' '-s'
$heavy=Listing $Warium 'com.wariumce.procedures.HeavyCrackProcedureProcedure' '-s'
if(!$tank.Contains('(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/entity/Entity;)V')){throw 'Unexpected pinned Warium TankFire descriptor'}
if(!$small.Contains('(Lnet/minecraft/world/level/LevelAccessor;DDD)V')){throw 'Unexpected pinned Warium SmallExplosion descriptor'}
if(!$heavy.Contains('(Lnet/minecraft/world/level/LevelAccessor;DDD)V')){throw 'Unexpected pinned Warium HeavyCrack descriptor'}
$native=Listing $Warium 'com.wariumce.procedures.TankFireProjectileHitsBlockProcedure' '-c'
foreach($value in @('CrustyChunksMod.queueServerWork','SmallExplosionProcedure.execute','25','5.0f','4.0f','22.0f','Entity.discard')){
    if($native -notmatch [regex]::Escape($value)){throw "Native TankFire sequence changed or was not found: $value"}
}
$explosion=Listing $Warium 'com.wariumce.procedures.SmallExplosionProcedure' '-c'
if(([regex]::Matches($explosion,'Level\.explode:')).Count -ne 3){throw 'Expected two strength-3 TNT explosion paths and one strength-7 NONE call in pinned SmallExplosion'}
foreach($value in @('3.0f','7.0f','75','wariumce:farblast','wariumce:smallfarblast','wariumce:smallexplosion')){
    if($explosion -notmatch [regex]::Escape($value)){throw "Native SmallExplosion effect missing: $value"}
}
$area=Listing $Artifact 'local.cmi.missiles.RadarSpearAreaDamage' '-c'
$view=Listing $Artifact 'local.cmi.missiles.RadarSpearAreaDamage$PlotView' '-c'
$allArea=$area+"`n"+$view
foreach($value in @('LevelPlot.getChunk','LevelPlot.toLocal','LevelPlot.getEmbeddedLevelAccessor','CommonHooks.fireBlockBreak','Level.setBlock','Level.destroyBlock','RadarSpearImpact.consume','CombatServices.canEngage','SableFirstHit.territoryClear')){
    if($allArea -notmatch [regex]::Escape($value)){throw "Managed area implementation guard/effect missing: $value"}
}
$source=Get-Content -Raw -LiteralPath "$PSScriptRoot/../src/main/java/local/cmi/missiles/RadarSpearAreaDamage.java"
foreach($guard in @('MIN_BLOCKS_PER_EFFECT = 2','approved.size()<MIN_BLOCKS_PER_EFFECT','plot.getChunk(plot.toLocal(chunkPos))','plot.getEmbeddedLevelAccessor()','Sable.HELPER.getTrackingOrVehicleSubLevel(entity)')){
    if(!$source.Contains($guard)){throw "Managed area policy guard missing: $guard"}
}
if($source.Contains('level.hasChunkAt(pos)')){throw 'Main-world chunk query must not be used for local Sable plot positions'}
foreach($forbidden in @('Level.explode','TankFireProjectileHitsBlockProcedure.execute','SmallExplosionProcedure.execute','CrustyChunksMod.queueServerWork','ServerLevel.addFreshEntity')){
    if($allArea -match [regex]::Escape($forbidden)){throw "Unsafe native/deferred effect found in CMI area handler: $forbidden"}
}
$impact=Listing $Artifact 'local.cmi.missiles.RadarSpearImpact' '-c'
if($impact -notmatch 'explosive_sable' -or $impact -notmatch 'matchesProfile'){throw 'Strict explosive_sable authorization contract missing'}
$bridge=Listing $Artifact 'local.cmi.missiles.RadarSpearBridge' '-c'
if($bridge -notmatch 'explosive_sable' -or $bridge -notmatch 'RadarSpearAreaDamage\.impact'){throw 'Impact dispatch is not explicitly profile gated'}
$sablePlot=Listing $Sable 'dev.ryanhcode.sable.sublevel.plot.LevelPlot' '-s'
foreach($descriptor in @('(Lnet/minecraft/world/level/ChunkPos;)Z','(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/LevelChunk;')){
    if(!$sablePlot.Contains($descriptor)){throw "Sable plot contract changed: $descriptor"}
}
$embedded=Listing $Sable 'dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor' '-c'
$getBlockState=[regex]::Match($embedded,'(?s)public net\.minecraft\.world\.level\.block\.state\.BlockState getBlockState\([^\r\n]*\);(.*?)(?=\n\s*public |\n\s*protected |\z)').Groups[1].Value
if($getBlockState -notmatch '(?s)BlockPos\.offset:.*Level\.getBlockState:'){throw 'Embedded Sable collision-view access no longer offsets local coordinates into the plot storage'}
'OK: pinned Warium routes and values, strict profile dispatch, bounded plot writes, and absence of native explosions/fragments/deferred jobs in the CMI area handler. Static only; no world execution.'
