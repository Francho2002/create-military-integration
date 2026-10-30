param(
    [Parameter(Mandatory)][string]$NeoWarfare,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference = 'Stop'
$target = 'riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.guidance.infrared_homing.InfraredSeekerGuidanceBlockEntity'
$parent = 'riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.guidance.GuidanceBlockEntity'
$launcher = 'riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMunitionsLauncherContraption'
$mixin = 'local.cmi.mixin.missiles.InfraredGuidanceMixin'
$invoker = 'local.cmi.mixin.missiles.GuidanceTurningSpeedInvoker'
function Listing([string]$jar, [string]$type, [string]$mode) {
    $text = (& javap -p $mode -classpath $jar $type 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "javap failed for $type : $text" }
    return $text
}
function HasDescriptor([string]$listing, [string]$name, [string]$descriptor) {
    $pattern = [regex]::Escape($name) + '\([^\r\n]*\);\s*descriptor:\s*' + [regex]::Escape($descriptor)
    if ($listing -notmatch $pattern) { throw "Descriptor mismatch for $name : $descriptor" }
}
$targetText = Listing $NeoWarfare $target '-s'
$parentText = Listing $NeoWarfare $parent '-s'
$mixinText = Listing $Artifact $mixin '-s'
$invokerText = Listing $Artifact $invoker '-s'
$tick = '(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;Lcom/simibubi/create/content/contraptions/OrientedContraptionEntity;)V'
$guide = '(Lriftyboi/cbcmodernwarfare/munitions/contraptions/MunitionsPhysicsContraptionEntity;Lnet/minecraft/world/level/block/state/BlockState;)V'
$callback = 'Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;'
HasDescriptor $targetText 'tickGuidance' $tick
HasDescriptor $targetText 'guideEntity' $guide
HasDescriptor $parentText 'calculateTurningSpeed' '(DDLnet/minecraft/world/level/block/state/BlockState;)D'
HasDescriptor $mixinText 'cmi$acquire' ($tick.Replace(')V', "$callback)V"))
HasDescriptor $mixinText 'cmi$guide' ($guide.Replace(')V', "$callback)V"))
HasDescriptor $invokerText 'cmi$calculateTurningSpeed' '(DDLnet/minecraft/world/level/block/state/BlockState;)D'
foreach ($field in @('trackedEntityId','currentPos','isLockedOn')) {
    if ($targetText -notmatch ('\b' + [regex]::Escape($field) + ';')) { throw "Missing shadow field $field" }
}
$source = Get-Content -Raw -LiteralPath "$PSScriptRoot/../src/main/java/local/cmi/mixin/missiles/InfraredGuidanceMixin.java"
$invokerSource = Get-Content -Raw -LiteralPath "$PSScriptRoot/../src/main/java/local/cmi/mixin/missiles/GuidanceTurningSpeedInvoker.java"
if ($source -match '@Shadow\s+protected\s+abstract\s+double\s+calculateTurningSpeed' -or $source -notmatch 'GuidanceTurningSpeedInvoker\)\s*\(Object\)\s*this\)\.cmi\$calculateTurningSpeed') { throw 'Inherited turning-speed call is not routed through the parent invoker' }
if ($invokerSource -notmatch 'targets\s*=\s*"riftyboi\.cbcmodernwarfare\.munitions\.munitions_contraption_launcher\.guidance\.GuidanceBlockEntity"' -or $invokerSource -notmatch '@Invoker\(value\s*=\s*"calculateTurningSpeed"\s*,\s*remap\s*=\s*false\)') { throw 'Turning-speed invoker target/declaration changed' }
foreach ($method in @('tickGuidance','guideEntity')) {
    if ($source -notmatch ('@Inject\(method\s*=\s*"' + $method + '"\s*,\s*at\s*=\s*@At\("HEAD"\)\s*,\s*cancellable\s*=\s*true')) { throw "Injection point changed: $method" }
}
$launcherCode = Listing $NeoWarfare $launcher '-c'
if ($launcherCode -notmatch 'BlockEntity.loadStatic' -or $launcherCode -notmatch 'MunitionsLauncherGuidanceBlock.canFire') { throw 'Native launcher NBT/guide transfer path changed' }
'OK: IR target/callback descriptors, parent-target turning-speed invoker, target fields, HEAD/cancellable injections and native NBT/canFire call sites. Does not apply mixins.'
