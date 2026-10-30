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
