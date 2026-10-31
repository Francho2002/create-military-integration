param(
    [Parameter(Mandatory)][string]$Radars,
    [Parameter(Mandatory)][string]$BetterTargeting,
    [Parameter(Mandatory)][string]$NeoWarfare,
    [Parameter(Mandatory)][string]$CBC,
    [Parameter(Mandatory)][string]$FTBChunks,
    [Parameter(Mandatory)][string]$FTBTeams,
    [Parameter(Mandatory)][string]$MineColonies,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/..").Path
$pins = @{}
Get-Content -LiteralPath (Join-Path $repo 'dependency-hashes.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=([0-9A-Fa-f]{64})$') { $pins[$Matches[1]] = $Matches[2].ToUpperInvariant() }
}
function RequireHash([string]$jar, [string]$key) {
    if (!$pins.ContainsKey($key) -or (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $pins[$key]) { throw "Dependency hash mismatch: $key" }
}
function Listing([string]$jar, [string]$type) {
    $result = (& javap -p -c -s -classpath $jar $type 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "javap failed for $type : $result" }
    return $result
}
function Section([string]$listing, [string]$header) {
    $start = $listing.IndexOf($header, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing bytecode method: $header" }
    $rest = $listing.Substring($start + $header.Length)
    $next = [regex]::Match($rest, '(?m)^  (?:public|private|protected|static)\s')
    if ($next.Success) { return $rest.Substring(0,$next.Index) }
    return $rest
}
function Has([string]$text,[string]$term,[string]$description) {
    if ($text.IndexOf($term,[StringComparison]::Ordinal) -lt 0) { throw "Missing $description : $term" }
}
function Ordered([string]$text,[string[]]$terms,[string]$description) {
    $last = -1
    foreach($term in $terms) {
        $pos = $text.IndexOf($term,$last + 1,[StringComparison]::Ordinal)
        if ($pos -lt 0) { throw "Changed $description : expected $term in order" }
        $last = $pos
    }
}
function Descriptor([string]$listing,[string]$method,[string]$descriptor) {
    if ($listing -notmatch ([regex]::Escape($method) + '\([^\r\n]*\);\s*descriptor:\s*' + [regex]::Escape($descriptor))) { throw "Descriptor mismatch: $method $descriptor" }
}
RequireHash $Radars 'create-radars-0.4.9.4.jar'
RequireHash $BetterTargeting 'create-better-targeting-1.0.0.1.jar'
RequireHash $NeoWarfare 'cbcmodernwarfare-0.0.6v+mc.1.21.1-neoforge.jar'
RequireHash $CBC 'createbigcannons-5.11.7+mc.1.21.1.jar'
RequireHash $FTBChunks 'ftb-chunks-neoforge-2101.1.22.jar'
RequireHash $FTBTeams 'ftb-teams-neoforge-2101.1.11.jar'
RequireHash $MineColonies 'minecolonies-1.1.1403-1.21.1.jar'

$iradar=Listing $Radars 'com.happysg.radar.block.radar.behavior.IRadar'
Descriptor $iradar 'getTracks' '()Ljava/util/Collection;'
Descriptor $iradar 'isRunning' '()Z'
foreach($type in @('com.happysg.radar.block.radar.plane.StationaryRadarBlockEntity','com.happysg.radar.block.radar.bearing.RadarBearingBlockEntity')) {
    $radar=Listing $Radars $type
    if($radar -notmatch 'implements .*com\.happysg\.radar\.block\.radar\.behavior\.IRadar'){throw "Not a physical IRadar: $type"}
    Descriptor $radar 'getTracks' '()Ljava/util/Collection;'
}
$track=Listing $Radars 'com.happysg.radar.block.radar.track.RadarTrack'
Descriptor $track 'id' '()Ljava/lang/String;'
Descriptor $track 'scannedTime' '()J'
Descriptor $track 'trackCategory' '()Lcom/happysg/radar/block/radar/track/TrackCategory;'
$ctor=Section $track 'public com.happysg.radar.block.radar.track.RadarTrack(net.minecraft.world.entity.Entity);'
Ordered $ctor @('Entity.getUUID:()Ljava/util/UUID;','java/util/UUID.toString:()Ljava/lang/String;','Entity.position:()Lnet/minecraft/world/phys/Vec3;','Entity.getDeltaMovement:()Lnet/minecraft/world/phys/Vec3;','Level.getGameTime:()J','TrackCategory.get:') 'PROJECTILE track constructor UUID/position/velocity/time/category'
Has (Section $track 'public java.lang.String id();') 'Method getId:' 'track id accessor'
Has (Section $track 'public java.lang.String getId();') 'Field id:Ljava/lang/String;' 'track id field'
$update=Section $track 'public void updateRadarTrack(net.minecraft.world.entity.Entity);'
foreach($part in @('Entity.position','Entity.getDeltaMovement','Level.getGameTime','Field scannedTime:J')) { Has $update $part 'track update' }
$scanner=Listing $Radars 'com.happysg.radar.block.radar.behavior.RadarScanningBlockBehavior'
$scanUpdate=Section $scanner 'private void updateRadarTracks();'
Ordered $scanUpdate @('Field scannedEntities','Entity.isAlive','isInFovAndRange','Field radarTracks','Entity.getUUID','java/util/UUID.toString','java/util/HashMap.compute') 'entity radar map key'
$lambda=Section $scanner 'lambda$updateRadarTracks$1(net.minecraft.world.entity.Entity, java.lang.String, com.happysg.radar.block.radar.track.RadarTrack);'
Has $lambda 'RadarTrack."<init>":(Lnet/minecraft/world/entity/Entity;)V' 'entity track creation'
Has $lambda 'RadarTrack.updateRadarTrack:(Lnet/minecraft/world/entity/Entity;)V' 'entity track refresh'
$scanTick=Section $scanner 'public void tick();'
foreach($term in @('long 5l','lrem','removeDeadTracks','updateRadarTracks','scanForEntityTracks')) { Has $scanTick $term 'radar sweep cadence/lifecycle' }
$behaviorCtor=Section $scanner 'public com.happysg.radar.block.radar.behavior.RadarScanningBlockBehavior(com.simibubi.create.foundation.blockEntity.SmartBlockEntity);'
Ordered $behaviorCtor @('bipush        100','Field trackExpiration:I','RadarServerConfig.radarFOV','Field fov:I','Field yRange:I','RadarServerConfig.radarBaseRange','Field range:D') 'radar expiration/FOV/height/range initialization'
$expiration=Section $scanner 'private void removeDeadTracks();'
Ordered $expiration @('Entity.isAlive','HashMap.remove','RadarTrack.scannedTime','Field trackExpiration:I','lcmp','ifle') 'dead and expired track cleanup'
$fovRange=Section $scanner 'private boolean isInFovAndRange(net.minecraft.world.phys.Vec3);'
foreach($term in @('Math.sqrt','Field range:D','RadarServerConfig.radarYScanRange','Math.abs','Math.atan2','Field angle:D','Field fov:I')) { Has $fovRange $term 'physical radar range/FOV/vertical filter' }
$scanEntities=Section $scanner 'private void scanForEntityTracks();'
foreach($term in @('Field scanProjectiles','net/minecraft/world/entity/projectile/Projectile','getEntitiesOfClass')) { Has $scanEntities $term 'physical projectile scan' }
$category=Listing $Radars 'com.happysg.radar.block.radar.track.TrackCategory'
$classify=Section $category 'public static com.happysg.radar.block.radar.track.TrackCategory get(net.minecraft.world.entity.Entity);'
foreach($term in @('RADAR_PROJECTILE','net/minecraft/world/entity/projectile/Projectile','Field PROJECTILE:')) { Has $classify $term 'projectile category classification' }

$ftbInner=Listing $BetterTargeting 'com.mrvew.firecontrol.iff.FtbCompat$Inner'
$claim=Section $ftbInner 'claimFriendly(net.minecraft.server.level.ServerLevel, net.minecraft.world.level.ChunkPos, java.util.UUID, java.util.Collection<java.util.UUID>);'
Ordered $claim @('Method claim:','Optional.empty:','ClaimedChunk.getTeamData','isTeamMember','isAlly','iconst_1','Optional.of:','iconst_0','Optional.of:') 'FTB claim presence semantics'
$ftbApi=Listing $FTBChunks 'dev.ftb.mods.ftbchunks.api.FTBChunksAPI$API'
Descriptor $ftbApi 'isManagerLoaded' '()Z'
$colonies=Listing $MineColonies 'com.minecolonies.core.colony.ColonyManager'
$colonyCheck=Section $colonies 'public boolean isCoordinateInAnyColony(net.minecraft.world.level.Level, net.minecraft.core.BlockPos);'
Ordered $colonyCheck @('Level.getChunkAt','ColonyUtils.getOwningColony') 'MineColonies chunk protection'

$mount=Listing $NeoWarfare 'riftyboi.cbcmodernwarfare.cannon_control.compact_mount.CompactCannonMountBlockEntity'
Descriptor $mount 'setYaw' '(F)V'
Descriptor $mount 'setPitch' '(F)V'
Descriptor $mount 'getContraption' '()Lrbasamoyai/createbigcannons/cannon_control/contraption/PitchOrientedContraptionEntity;'
Descriptor $mount 'isRunning' '()Z'
$shotDesc='(Lnet/minecraft/server/level/ServerLevel;Lrbasamoyai/createbigcannons/cannon_control/contraption/PitchOrientedContraptionEntity;)V'
foreach($target in @(
    @($NeoWarfare,'riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption'),
    @($NeoWarfare,'riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedRotarycannonContraption'),
    @($CBC,'rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption')
)) {
    $listing=Listing $target[0] $target[1]
    Descriptor $listing 'fireShot' $shotDesc
    $fire=Section $listing 'public void fireShot(net.minecraft.server.level.ServerLevel, rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity);'
    foreach($term in @('canFire:()Z','baseSpeed:()F','baseSpread:()F','spreadReductionPerBarrel:()F','shoot:(DDDFF)V')) { Has $fire $term "native loadout/fire $($target[1])" }
    if($target[1] -notmatch 'MountedMediumcannon') {
        foreach($term in @('projectileLifetime:()I','setLifetime:(I)V','handleFiring:()V')) { Has $fire $term "native lifetime/cooldown $($target[1])" }
        Has $listing 'fireShot:(Lnet/minecraft/server/level/ServerLevel;Lrbasamoyai/createbigcannons/cannon_control/contraption/PitchOrientedContraptionEntity;)V' 'autofire invocation guarded by mixin'
    }
}
$cbcMount=Listing $CBC 'rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption'
Has $cbcMount 'cannonMaterial:Lrbasamoyai/createbigcannons/cannons/autocannon/material/AutocannonMaterial;' 'CBC material accessor target'
$autoProjectile=Listing $CBC 'rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile'
Has (Section $autoProjectile 'public void setLifetime(int);') 'Field ageRemaining:I' 'finite CBC projectile lifetime'
$firing=Listing $BetterTargeting 'com.mrvew.firecontrol.ballistics.FiringSolver'
Descriptor $firing 'solve' '(Lcom/mrvew/firecontrol/ballistics/FiringSolver$Arc;[DD)Lcom/mrvew/firecontrol/ballistics/FiringSolver$Solution;'
Descriptor $firing 'approach' '(Lcom/mrvew/firecontrol/ballistics/V3;)Lcom/mrvew/firecontrol/ballistics/FiringSolver$Approach;'
Descriptor $firing 'path' '(Lcom/mrvew/firecontrol/ballistics/V3;D)Ljava/util/List;'

$source=Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/java/local/cmi/weapons/CiwsCoordinator.java')
foreach($term in @('radar.getTracks()','TrackCategory.PROJECTILE','CiwsPolicy.projectileId','level.getEntity(id)','CombatServices.projectile(target)','CiwsPolicy.eligible','Relation.ENEMY','CombatServices.canEngage','CiwsPolicy.approaching','CiwsPolicy.observe','CiwsPolicy.shouldFire','TrajectorySafety.clear','CombatServices.reserve','WeaponAdapters.fire','shell.lifetimeTicks()','MAX_FLIGHT_TICKS','ClipContext.Fluid.ANY')) { Has $source $term 'CIWS authorization or fire path' }
foreach($term in @('FtbCompat.claimFriendly','isManagerLoaded()',').isPresent()')) { Has $source $term 'all FTB claims and manager availability' }
foreach($forbidden in @('CombatServices.contacts(','getAllEntities(','Sensor.scan(','CombatServices.neutralize(','new RadarSpearMissileProjectileEntity','BlockWeaponRecoil.spawn(')) {
    if($source.Contains($forbidden)){throw "Forbidden synthetic CIWS operation: $forbidden"}
}
Ordered $source @('safePath(level, actor, cannon, plan.target()','CombatServices.canEngage(level, actor, current)','CombatServices.reserve(level, current.id()','FIRE_PERMIT.set','WeaponAdapters.fire') 'final corridor / revalidation / reservation / scoped native firing'
Has $source 'safePath(level, actor, cannon, target, pivot, solver, solution.direction()' 'candidate corridor before reservation'
$budget=Section $source 'private static List<RadarTrack> projectileTracks(IRadar radar) {'
Ordered $budget @('++scanned, MAX_TRACKS','t.trackCategory() != TrackCategory.PROJECTILE') 'entire radar snapshot budget'
$mix=Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/resources/cmi.mixins.json')
foreach($term in @('safety.CiwsFireGuardMixin','weapons.AutocannonMaterialAccess')) { Has $mix $term 'registered CIWS mixin' }
$guard=Listing $Artifact 'local.cmi.mixin.safety.CiwsFireGuardMixin'
Descriptor $guard 'cmi$requireCiwsPermit' '(Lnet/minecraft/server/level/ServerLevel;Lrbasamoyai/createbigcannons/cannon_control/contraption/PitchOrientedContraptionEntity;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V'
$guardSource=Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/java/local/cmi/mixin/safety/CiwsFireGuardMixin.java')
foreach($target in @('MountedMediumcannonContraption','MountedRotarycannonContraption','MountedAutocannonContraption')) { Has $guardSource $target 'guard target' }
$weapon=Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/java/local/cmi/weapons/WeaponAdapters.java')
foreach($term in @('ciwsVelocity','ciwsShell','spread() != 0.0f','isIncendiary','getNestedFuze','p.projectileLifetime()','ShotContext.runManaged')) { Has $weapon $term 'real loadout / attribution' }
'OK: pinned physical projectile-track identity chain, expiry/FOV/range/filter behavior, cadence, cannon fire/ballistics/cooldown descriptors, CIWS guard and source safety gates. Static only; no runtime weaving or gameplay verified.'
