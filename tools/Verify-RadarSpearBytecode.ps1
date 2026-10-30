param(
    [Parameter(Mandatory)][string]$Warium,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference = 'Stop'
function Listing([string]$jar, [string]$type, [string]$mode) {
    $result = (& javap -p $mode -classpath $jar $type 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "javap failed for $type : $result" }
    return $result
}
function RequireDescriptor([string]$listing, [string]$name, [string]$descriptor) {
    if ($listing -notmatch ([regex]::Escape($name) + '\([^\r\n]*\);\s*descriptor:\s*' + [regex]::Escape($descriptor))) { throw "Descriptor mismatch: $name $descriptor" }
}
$entity = 'com.wariumce.entity.RadarSpearMissileProjectileEntity'
$flight = 'com.wariumce.procedures.RadarSpearFlightProcedure'
$et = Listing $Warium $entity '-s'
$ft = Listing $Warium $flight '-s'
$em = Listing $Artifact 'local.cmi.mixin.missiles.RadarSpearEntityMixin' '-s'
$fm = Listing $Artifact 'local.cmi.mixin.missiles.RadarSpearFlightMixin' '-s'
$callback = 'Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;'
RequireDescriptor $et 'tick' '()V'
RequireDescriptor $et 'onHitEntity' '(Lnet/minecraft/world/phys/EntityHitResult;)V'
RequireDescriptor $et 'onHitBlock' '(Lnet/minecraft/world/phys/BlockHitResult;)V'
RequireDescriptor $ft 'execute' '(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/entity/Entity;)V'
RequireDescriptor $em 'cmi$identifyLaunch' "($callback)V"
RequireDescriptor $em 'cmi$avoidNativeEntityExplosion' "(Lnet/minecraft/world/phys/EntityHitResult;$callback)V"
RequireDescriptor $em 'cmi$avoidNativeBlockExplosion' "(Lnet/minecraft/world/phys/BlockHitResult;$callback)V"
RequireDescriptor $fm 'cmi$managedFlight' "(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/entity/Entity;$callback)V"
$code = Listing $Warium $entity '-c'
$tick = [regex]::Match($code, '(?s)public void tick\(\);(.*?)(?=\n\s*public |\n\s*static \{|\z)').Groups[1].Value
if ($tick -notmatch '(?s)AbstractArrow.tick.*RadarSpearFlightProcedure.execute') { throw 'Radar Spear tick order changed' }
foreach ($method in @('onHitEntity','onHitBlock')) {
    $body = [regex]::Match($code, '(?s)public void ' + $method + '\([^\r\n]*\);(.*?)(?=\n\s*public |\n\s*static \{|\z)').Groups[1].Value
    if ($body -notmatch ('(?s)AbstractArrow\.' + $method + '.*TankFireProjectileHitsBlockProcedure.execute')) { throw "Native impact sequence changed: $method" }
}
$src = Get-Content -Raw -LiteralPath "$PSScriptRoot/../src/main/java/local/cmi/mixin/missiles/RadarSpearEntityMixin.java"
foreach ($method in @('tick','onHitEntity','onHitBlock')) {
    if ($src -notmatch ('@Inject\(method\s*=\s*"' + $method + '"\s*,\s*at\s*=\s*@At\("HEAD"\)\s*,\s*cancellable\s*=\s*true')) { throw "Injection point changed: $method" }
}
'OK: Radar Spear target/handler descriptors, HEAD/cancellable hooks and native tick/impact call order. Does not apply mixins.'
$fire = Listing $Warium 'com.wariumce.procedures.MissileHardpointFireProcedure' '-c'
$spawn = [regex]::Matches($fire, 'invokestatic\s+#[0-9]+\s+// Method com/wariumce/util/BlockWeaponRecoil\.spawn:')
if ($spawn.Count -ne 4) { throw "Expected four spawn calls, got $($spawn.Count)" }
$radar = $fire.Substring($fire.IndexOf('Field com/wariumce/init/CrustyChunksModBlocks.RADAR_SPEAR_MISSILE_HARDPOINT'))
if ($radar -notmatch '(?s)MissileHardpointFireProcedure\$15.*BlockWeaponRecoil\.spawn:.*saveWithFullMetadata.*setBlock:.*loadWithComponents:') { throw 'Radar spawn or replacement order changed' }
$factory = Listing $Warium 'com.wariumce.procedures.MissileHardpointFireProcedure$15' '-c'
if ($factory -notmatch 'new\s+#[0-9]+\s+// class com/wariumce/entity/RadarSpearMissileProjectileEntity') { throw 'Radar factory type changed' }
$recoil = Listing $Warium 'com.wariumce.util.BlockWeaponRecoil' '-c'
if ($recoil -notmatch '(?s)boolean spawn\(.*?Level.addFreshEntity:') { throw 'Spawn insertion changed' }
$launch = Listing $Artifact 'local.cmi.mixin.missiles.RadarSpearLaunchMixin' '-s'
RequireDescriptor $launch 'cmi$captureLaunch' '(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/core/BlockPos;Lcom/wariumce/util/BlockWeaponRecoil$Weapon;)Z'
$launchSource = Get-Content -Raw -LiteralPath "$PSScriptRoot/../src/main/java/local/cmi/mixin/missiles/RadarSpearLaunchMixin.java"
if ($launchSource -notmatch 'ordinal\s*=\s*3') { throw 'Radar spawn redirect ordinal changed' }
'OK: Radar launch factory, fourth spawn, insertion and block replacement order; mixins not applied.'
