param(
    [Parameter(Mandatory)][string]$NeoWarfare,
    [Parameter(Mandatory)][string]$Radars,
    [Parameter(Mandatory)][string]$CBC,
    [Parameter(Mandatory)][string]$Create,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/..").Path
$pins = @{}
Get-Content -LiteralPath (Join-Path $repo 'dependency-hashes.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=([0-9A-Fa-f]{64})$') { $pins[$Matches[1]] = $Matches[2].ToUpperInvariant() }
}
function RequireHash([string]$jar, [string]$key) {
    if (-not $pins.ContainsKey($key)) { throw "Missing pinned hash: $key" }
    $actual = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
    if ($actual -ne $pins[$key]) { throw "Dependency hash mismatch for $key ($actual)" }
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
    if ($next.Success) { return $rest.Substring(0, $next.Index) }
    return $rest
}
function Has([string]$text, [string]$term, [string]$description) {
    if ($text.IndexOf($term, [StringComparison]::Ordinal) -lt 0) { throw "Missing $description : $term" }
}
function Ordered([string]$text, [string[]]$terms, [string]$description) {
    $last = -1
    foreach ($term in $terms) {
        $at = $text.IndexOf($term, $last + 1, [StringComparison]::Ordinal)
        if ($at -lt 0) { throw "Changed $description : expected $term in order" }
        $last = $at
    }
}
function Descriptor([string]$listing, [string]$method, [string]$descriptor) {
    if ($listing -notmatch ([regex]::Escape($method) + '\([^\r\n]*\);\s*descriptor:\s*' + [regex]::Escape($descriptor))) {
        throw "Descriptor mismatch: $method $descriptor"
    }
}
function ZipText([string]$jar, [string]$entry) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $file = $archive.GetEntry($entry)
        if ($null -eq $file) { throw "Missing artifact resource: $entry" }
        $reader = [System.IO.StreamReader]::new($file.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
}

RequireHash $NeoWarfare 'cbcmodernwarfare-0.0.6v+mc.1.21.1-neoforge.jar'
RequireHash $Radars 'create-radars-0.4.9.4.jar'
RequireHash $CBC 'createbigcannons-5.11.7+mc.1.21.1.jar'
RequireHash $Create 'create-1.21.1-6.0.10.jar'

$tagPath = 'data/create_radar/tags/entity_types/radar_projectile.json'
$expectedTag = '{"replace":false,"values":["cbcmodernwarfare:munitions_contraption"]}'
$sourceTag = (Get-Content -Raw -LiteralPath (Join-Path $repo "src/main/resources/$tagPath")).Replace("`r", '').Replace("`n", '').Replace(' ', '').Replace("`t", '')
if ($sourceTag -ne $expectedTag) { throw "Source tag must append only cbcmodernwarfare:munitions_contraption with replace=false" }
$artifactTag = (ZipText $Artifact $tagPath).Replace("`r", '').Replace("`n", '').Replace(' ', '').Replace("`t", '')
if ($artifactTag -ne $expectedTag) { throw "Packaged artifact tag does not match the verified source tag" }

$registrations = Listing $NeoWarfare 'riftyboi.cbcmodernwarfare.index.CBCModernWarfareEntityTypes'
Has $registrations 'MUNITIONS_CONTRAPTION' 'Neo Warfare entity registry entry'
Has $registrations 'MunitionsPhysicsContraptionEntity' 'registered multiblock entity class'
Has $registrations 'munitions_contraption' 'registered entity path'
$mod = Listing $NeoWarfare 'riftyboi.cbcmodernwarfare.CBCModernWarfare'
Has $mod 'String cbcmodernwarfare' 'Neo Warfare registrate namespace'
Has $mod 'CreateRegistrate.create:(Ljava/lang/String;)Lcom/simibubi/create/foundation/data/CreateRegistrate;' 'Neo Warfare registry namespace binding'
$entity = Listing $NeoWarfare 'riftyboi.cbcmodernwarfare.munitions.contraptions.MunitionsPhysicsContraptionEntity'
Has $entity 'extends com.simibubi.create.content.contraptions.OrientedContraptionEntity' 'multiblock contraption inheritance'
Descriptor $entity 'setDeltaMovement' '(Lnet/minecraft/world/phys/Vec3;)V'
$setMotion = Section $entity 'public void setDeltaMovement(net.minecraft.world.phys.Vec3);'
Has $setMotion 'setContraptionMotion:(Lnet/minecraft/world/phys/Vec3;)V' 'real motion setter'
$tick = Section $entity 'public void tick();'
foreach ($term in @('getDeltaMovement:()Lnet/minecraft/world/phys/Vec3;', 'setContraptionMotion:(Lnet/minecraft/world/phys/Vec3;)V', 'clipAndDamage:()V')) {
    Has $tick $term 'multiblock physical tick and collision'
}
$bounds = Section $entity 'public net.minecraft.world.phys.AABB setContraptionBounds();'
foreach ($term in @('Contraption.getBlocks:()Ljava/util/Map;', 'createBoundsFromPositions:', 'AABB.inflate:(D)Lnet/minecraft/world/phys/AABB;', 'getBoundingBox:()Lnet/minecraft/world/phys/AABB;')) {
    Has $bounds $term 'munitions-derived entity bounds'
}
$launcher = Listing $NeoWarfare 'riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMunitionsLauncherContraption'
$fire = Section $launcher 'public void fireShot(net.minecraft.server.level.ServerLevel, rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity);'
Descriptor $launcher 'fireShot' '(Lnet/minecraft/server/level/ServerLevel;Lrbasamoyai/createbigcannons/cannon_control/contraption/PitchOrientedContraptionEntity;)V'
Ordered $fire @('MunitionsPhysicsContraptionEntity.create:', 'Entity.setPos:(DDD)V', 'MunitionsPhysicsContraptionEntity.shoot:', 'ServerLevel.addFreshEntity:(Lnet/minecraft/world/entity/Entity;)Z') 'native multiblock launch path'

$oriented = Listing $Create 'com.simibubi.create.content.contraptions.OrientedContraptionEntity'
Has $oriented 'extends com.simibubi.create.content.contraptions.AbstractContraptionEntity' 'Create contraption base class'
$scanner = Listing $Radars 'com.happysg.radar.block.radar.behavior.RadarScanningBlockBehavior'
Has $scanner 'private boolean scanContraptions;' 'native contraption scan filter'
Has $scanner 'private boolean scanProjectiles;' 'native projectile category filter'
$scan = Section $scanner 'private void scanForEntityTracks();'
Ordered $scan @('Field scanProjectiles:Z', 'net/minecraft/world/entity/projectile/Projectile', 'Field scanContraptions:Z', 'com/simibubi/create/content/contraptions/AbstractContraptionEntity', 'Level.getEntitiesOfClass') 'physical class-specific radar sweep'
foreach ($field in @('private boolean scanContraptions;', 'private boolean scanProjectiles;')) { Has $scanner $field 'radar scan filter declaration' }
$tags = Listing $Radars 'com.happysg.radar.block.radar.track.RadarEntityTypeTags'
Has $tags 'RADAR_PROJECTILE' 'native projectile tag key'
Has $tags 'create_radar' 'native radar tag namespace'
$category = Listing $Radars 'com.happysg.radar.block.radar.track.TrackCategory'
$classify = Section $category 'public static com.happysg.radar.block.radar.track.TrackCategory get(net.minecraft.world.entity.Entity);'
Ordered $classify @('RADAR_HOSTILE', 'RADAR_ANIMAL', 'RADAR_MOB', 'RADAR_PROJECTILE', 'Field PROJECTILE:', 'AbstractContraptionEntity') 'tag precedes contraption fallback category'
$track = Listing $Radars 'com.happysg.radar.block.radar.track.RadarTrack'
Descriptor $track 'id' '()Ljava/lang/String;'
Descriptor $track 'position' '()Lnet/minecraft/world/phys/Vec3;'
Descriptor $track 'velocity' '()Lnet/minecraft/world/phys/Vec3;'
Descriptor $track 'scannedTime' '()J'
Descriptor $track 'trackCategory' '()Lcom/happysg/radar/block/radar/track/TrackCategory;'
$trackCtor = Section $track 'public com.happysg.radar.block.radar.track.RadarTrack(net.minecraft.world.entity.Entity);'
Ordered $trackCtor @('Entity.getUUID:()Ljava/util/UUID;', 'UUID.toString:()Ljava/lang/String;', 'Entity.position:()Lnet/minecraft/world/phys/Vec3;', 'Entity.getDeltaMovement:()Lnet/minecraft/world/phys/Vec3;', 'Level.getGameTime:()J', 'TrackCategory.get:') 'real entity UUID/position/velocity/time/category in radar track'

$combat = Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/java/local/cmi/core/CombatServices.java')
Has $combat 'MunitionsPhysicsContraptionEntity)return true' 'CMI recognizes the exact multiblock projectile class'
$ciws = Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/java/local/cmi/weapons/CiwsCoordinator.java')
foreach ($term in @('radar.getTracks()', 'TrackCategory.PROJECTILE', 'CiwsPolicy.projectileId', 'level.getEntity(id)', 'CombatServices.resolve', 'CombatServices.relation', 'CombatServices.canEngage', 'CiwsPolicy.approaching', 'TrajectorySafety.clear', 'WeaponAdapters.fire')) {
    Has $ciws $term 'existing radar/IFF/CIWS path'
}
foreach ($term in @('RadarScanningBlockBehavior.TYPE', 'filters.cmi$scanContraptions()', 'filters.cmi$scanProjectiles()', 'if (!filters.cmi$scanContraptions() || !filters.cmi$scanProjectiles()) return null;')) {
    Has $ciws $term 'fail-closed CIWS radar filter gate'
}
$access = Listing $Artifact 'local.cmi.mixin.weapons.RadarScanningFilterAccess'
Descriptor $access 'cmi$scanContraptions' '()Z'
Descriptor $access 'cmi$scanProjectiles' '()Z'
$mixins = Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/resources/cmi.mixins.json')
Has $mixins 'weapons.RadarScanningFilterAccess' 'registered radar filter accessor mixin'
foreach ($forbidden in @('CombatServices.contacts(', 'getAllEntities(', 'Sensor.scan(', 'CombatServices.neutralize(')) {
    if ($ciws.Contains($forbidden)) { throw "CIWS acquisition/fire loop must not use $forbidden" }
}

$ownership = Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/java/local/cmi/mixin/safety/ShotOwnershipMixin.java')
Has $ownership 'MountedMunitionsLauncherContraption' 'authentic Neo Warfare launcher attribution hook'
Has $ownership '@WrapMethod(method="fireShot")' 'scoped native launcher fire hook'
$join = Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/java/local/cmi/CreateMilitaryIntegration.java')
foreach ($term in @('ShotContext.current()', 'CombatServices.projectile(event.getEntity())', 't.putUUID("cmiOwner",actor.owner())', 't.putString("cmiIff",actor.iff()==null?"":actor.iff())', 't.putUUID("cmiPlatform",actor.platform())')) {
    Has $join $term 'server entity-join provenance assignment'
}
$weapon = Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/java/local/cmi/weapons/WeaponAdapters.java')
Has $weapon 'ShotContext.runManaged(() -> cannon.fireShot(level, entity))' 'native CIWS fire and attribution'

$cbcProjectile = Listing $CBC 'rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile'
$collision = Section $cbcProjectile 'protected void clipAndDamage();'
foreach ($term in @('canHitEntity:', 'onHitEntity:')) { Has $collision $term 'real CBC swept collision dispatch' }
$interception = Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/java/local/cmi/mixin/safety/CannonInterceptionMixin.java')
foreach ($term in @('method="canHitEntity"', 'method="onHitEntity"', 'CombatServices.resolve', 'CombatServices.canEngage', 'CombatServices.neutralize')) {
    Has $interception $term 'collision-only CMI authorization and neutralization'
}
Has $interception 'self.getPersistentData().getBoolean("cmiManagedShot")' 'managed CIWS shot gate'

'OK: pinned Neo Warfare entity registration, native physical radar query and tag classification, track identity/motion, launch provenance, CIWS IFF path, entity bounds, and real collision-only neutralization. Static only; no game runtime started.'
