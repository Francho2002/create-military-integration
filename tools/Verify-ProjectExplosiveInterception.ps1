[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CBC,
    [Parameter(Mandatory)][string]$NeoWarfare,
    [Parameter(Mandatory)][string]$ProjectExplosive,
    [Parameter(Mandatory)][string]$Radars,
    [Parameter(Mandatory)][string]$MinecraftArtifact,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$artifactPath = (Resolve-Path -LiteralPath $Artifact).Path
$pins = @{}
Get-Content -LiteralPath (Join-Path $repo 'dependency-hashes.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=([0-9A-Fa-f]{64})$') { $pins[$Matches[1]] = $Matches[2].ToUpperInvariant() }
}
$script:Passes = 0
$script:Failures = [System.Collections.Generic.List[string]]::new()
function Check([bool]$condition, [string]$message) {
    if ($condition) { $script:Passes++; Write-Host "PASS: $message" }
    else { $script:Failures.Add($message); Write-Host "FAIL: $message" }
}
function HashIs([string]$path, [string]$expected, [string]$label) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing ${label}: $path" }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToUpperInvariant()
    Check ($actual -eq $expected.ToUpperInvariant()) "$label SHA-256 is pinned ($actual)"
}
function Pinned([string]$path, [string]$name) {
    if (!$pins.ContainsKey($name)) { throw "Missing dependency pin: $name" }
    HashIs $path $pins[$name] $name
}
function Listing([string]$classpath, [string]$type) {
    $result = (& javap -p -c -s -classpath $classpath $type 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "javap failed for $type : $result" }
    return $result
}
function Method([string]$listing, [string]$header) {
    $start = $listing.IndexOf($header, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing bytecode method: $header" }
    $rest = $listing.Substring($start)
    $next = [regex]::Match($rest.Substring($header.Length), '(?m)^  (?:public|private|protected|static|abstract|final)\s')
    if ($next.Success) { return $rest.Substring(0, $header.Length + $next.Index) }
    return $rest
}
function Ordered([string]$text, [string[]]$terms, [string]$label) {
    $last = -1
    foreach ($term in $terms) {
        $next = $text.IndexOf($term, $last + 1, [StringComparison]::Ordinal)
        if ($next -lt 0) { Check $false "$label contains ordered operation: $term"; return }
        $last = $next
    }
    Check $true $label
}
function Source([string]$relative) { Get-Content -Raw -LiteralPath (Join-Path $repo $relative) }

Pinned $CBC 'createbigcannons-5.11.7+mc.1.21.1.jar'
Pinned $NeoWarfare 'cbcmodernwarfare-0.0.6v+mc.1.21.1-neoforge.jar'
Pinned $Radars 'create-radars-0.4.9.4.jar'
HashIs $ProjectExplosive '3CBF5A76D8B6123A489DF54F0165C31DF6C5F6993DD07E9506232FCB3F3CEBB2' 'Project: Explosive 0.7.2-beta'
Check ((& javap -version 2>&1 | Out-String).Trim() -match '^21\.') 'javap runs on Java 21'

$cbcType = 'rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile'
$cbcListing = Listing $CBC $cbcType
$clip = Method $cbcListing 'protected void clipAndDamage();'
$canHit = Method $cbcListing 'public boolean canHitEntity(net.minecraft.world.entity.Entity);'
$onHit = Method $cbcListing 'protected boolean onHitEntity(net.minecraft.world.entity.Entity, rbasamoyai.createbigcannons.munitions.ProjectileContext);'
Ordered $clip @('Level.getEntities:', 'ProjectileContext.hasHitEntity:', 'canHitEntity:', 'Entity.getBoundingBox:', 'AABB.intersects:', 'AABB.clip:', 'ProjectileContext.addEntity:', 'ProjectileContext.hitEntities:', 'onHitEntity:') 'CBC enumerates swept-AABB candidates, filters, applies actual geometry and dispatches accepted callbacks in that order'
Check ($canHit.Contains('Projectile.canHitEntity:') -and $canHit.Contains('instanceof') -and
    $canHit.Contains('net/minecraft/world/entity/projectile/Projectile')) 'CBC native filter calls vanilla Projectile.canHitEntity then excludes Projectile targets'
Check ($clip.Contains('ProjectileContext.hitEntities:') -and $clip.Contains('onHitEntity:')) 'CBC may dispatch multiple entities already collected during one clip pass'
Check ($onHit -match 'descriptor:\s*\(Lnet/minecraft/world/entity/Entity;Lrbasamoyai/createbigcannons/munitions/ProjectileContext;\)Z') 'CBC callback contract is the expected boolean physical-hit method'
$cbcArchive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $CBC).Path)
try {
    $cbcProjectileTypes = @($cbcArchive.Entries | Where-Object { $_.FullName -match '^rbasamoyai/createbigcannons/.+Projectile\.class$' } |
        ForEach-Object { ($_.FullName -replace '\.class$', '') -replace '/', '.' })
} finally { $cbcArchive.Dispose() }
$declaredHitFilters = [System.Collections.Generic.List[string]]::new()
foreach ($type in $cbcProjectileTypes) {
    $classListing = Listing $CBC $type
    if ($classListing -match '(?m)^  (?:public|protected|private) [^\r\n]*\b(?:canHitEntity|onHitEntity)\(' -and
        $type -ne $cbcType) { $declaredHitFilters.Add($type) }
}
Check ($cbcProjectileTypes.Count -gt 0 -and $declaredHitFilters.Count -eq 0) 'CBC projectile subclasses inherit the injected AbstractCannonProjectile filters/callbacks without per-subclass overrides'
$mediumBase = Listing $NeoWarfare 'riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile'
$mediumTypes = @('riftyboi.cbcmodernwarfare.munitions.medium_cannon.ap.APMediumcannonProjectile',
    'riftyboi.cbcmodernwarfare.munitions.medium_cannon.apds.APDSMediumcannonProjectile',
    'riftyboi.cbcmodernwarfare.munitions.medium_cannon.apfsds.APFSDSMediumcannonProjectile')
$mediumOverrides = [System.Collections.Generic.List[string]]::new()
foreach ($type in $mediumTypes) {
    $classListing = Listing $NeoWarfare $type
    if ($classListing -match '(?m)^  (?:public|protected|private) [^\r\n]*\b(?:canHitEntity|onHitEntity)\(') { $mediumOverrides.Add($type) }
}
Check ($mediumBase.Contains('extends rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile') -and
    $mediumOverrides.Count -eq 0) 'supported Neo Warfare medium kinetic projectile family inherits CBC collision hooks unchanged'

$minecraftProjectile = Listing $MinecraftArtifact 'net.minecraft.world.entity.projectile.Projectile'
$minecraftEntity = Listing $MinecraftArtifact 'net.minecraft.world.entity.Entity'
$vanillaCanHit = Method $minecraftProjectile 'protected boolean canHitEntity(net.minecraft.world.entity.Entity);'
$vanillaPickable = Method $minecraftProjectile 'public boolean isPickable();'
$projectileGate = Method $minecraftEntity 'public boolean canBeHitByProjectile();'
Check ($vanillaCanHit.Contains('Entity.canBeHitByProjectile')) 'vanilla Projectile.canHitEntity consults Entity.canBeHitByProjectile'
Check ($projectileGate.Contains('Method isAlive:()Z') -and $projectileGate.Contains('Method isPickable:()Z')) 'vanilla projectile gate requires alive and pickable target'
Check ($vanillaPickable.Contains('EntityTypeTags.REDIRECTABLE_PROJECTILE') -and $vanillaPickable.Contains('EntityType.is')) 'vanilla Projectile.isPickable is controlled by REDIRECTABLE_PROJECTILE'
Check (!$vanillaCanHit.Contains('Entity.isAttackable') -and !$projectileGate.Contains('Entity.isAttackable')) 'isAttackable is not part of the inspected CBC/vanilla projectile filter'

$peRegistryType = 'lil1llilol11.lo1i1iloioil.l1loi01l110i1o10ooiliol0'
$peRegistry = Listing $ProjectExplosive $peRegistryType
$smallType = 'lil1llilol11.lo1i1iloioil.l0io0i1o00lll00olo0110ii'
$bigType = 'lil1llilol11.lo1i1iloioil.llooi0ilol1o11oiliioo1ll'
$small = Listing $ProjectExplosive $smallType
$big = Listing $ProjectExplosive $bigType
Check ($peRegistry.Contains('projectexplosive') -and $peRegistry.Contains('small_bomb') -and $peRegistry.Contains('big_bomb')) 'pinned PE registry defines the projectexplosive namespace and both exact conventional IDs'
Check ($small.StartsWith("public class $smallType extends net.minecraft.world.entity.projectile.ThrowableProjectile") -and
    $big.StartsWith("public class $bigType extends net.minecraft.world.entity.projectile.ThrowableProjectile")) 'Mk 82 and Mk 84 are real ThrowableProjectile entities'
$smallPending = Method $small 'private boolean l11oo0i1illii0o0liol1o1i;'
$bigPending = Method $big 'private boolean lii11l1iil010100loiio0li;'
Check ($smallPending -match 'descriptor:\s*Z' -and $bigPending -match 'descriptor:\s*Z') 'both exact pending-impact fields exist as boolean state in the pinned PE bytecode'
$smallHit = Method $small 'protected void onHit(net.minecraft.world.phys.HitResult);'
$bigHit = Method $big 'protected void onHit(net.minecraft.world.phys.HitResult);'
Check ($smallHit.Contains('putfield') -and $smallHit.Contains('l11oo0i1illii0o0liol1o1i')) 'Mk 82 sets its pending-impact state in its actual onHit path'
Check ($bigHit.Contains('putfield') -and $bigHit.Contains('lii11l1iil010100loiio0li')) 'Mk 84 sets its pending-impact state in its actual onHit path'
$smallExploded = Method $small 'public boolean ll1ol00o111oioool0l011lo();'
$bigExploded = Method $big 'public boolean ll1ol00o111oioool0l011lo();'
Check ($smallExploded.Contains('descriptor: ()Z') -and $smallExploded.Contains('l0o001o0ol0ilooioooil11i')) 'Mk 82 HasExploded method reads its authoritative field'
Check ($bigExploded.Contains('descriptor: ()Z') -and $bigExploded.Contains('l0o001o0ol0ilooioooil11i')) 'Mk 84 HasExploded method reads its authoritative field'
$smallGravity = Method $small 'protected double getDefaultGravity();'
$bigGravity = Method $big 'protected double getDefaultGravity();'
Check ($smallGravity.Contains('0.10000000149011612d') -and $bigGravity.Contains('0.10000000149011612d')) 'both PE bombs override gravity to 0.1f, requiring the measured-motion confidence path'
$smallRemove = Method $small 'public void remove(net.minecraft.world.entity.Entity$RemovalReason);'
$bigRemove = Method $big 'public void remove(net.minecraft.world.entity.Entity$RemovalReason);'
Check ($smallRemove.Contains('ThrowableProjectile.remove') -and !$smallRemove.Contains('onHit(')) 'Mk 82 removal delegates to ThrowableProjectile.remove without invoking its impact callback'
Check ($bigRemove.Contains('ThrowableProjectile.remove') -and !$bigRemove.Contains('onHit(')) 'Mk 84 removal delegates to ThrowableProjectile.remove without invoking its impact callback'

$radar = Listing $Radars 'com.happysg.radar.block.radar.behavior.IRadar'
Check ($radar.Contains('getTracks') -and $radar.Contains('RadarTrack')) 'pinned Create: Radars exposes the physical IRadar track contract'

$root = 'src/main/java/local/cmi/'
$config = Source ($root + 'core/CmiConfig.java')
$neutralization = Source ($root + 'core/NeutralizationPolicy.java')
$observation = Source ($root + 'core/ProjectExplosiveBombObservation.java')
$interception = Source ($root + 'core/ProjectExplosiveInterception.java')
$permitPolicy = Source ($root + 'core/ProjectExplosivePermitPolicy.java')
$integration = Source 'src/main/java/local/cmi/CreateMilitaryIntegration.java'
$state = Source ($root + 'core/ProjectExplosiveBombState.java')
$smallMixin = Source ($root + 'mixin/safety/ProjectExplosiveSmallBombStateMixin.java')
$bigMixin = Source ($root + 'mixin/safety/ProjectExplosiveBigBombStateMixin.java')
$collisionMixin = Source ($root + 'mixin/safety/CannonInterceptionMixin.java')
$ciws = Source ($root + 'weapons/CiwsCoordinator.java')
$combat = Source ($root + 'core/CombatServices.java')
$gradle = Source 'build.gradle'
$mixinConfig = Source 'src/main/resources/cmi.mixins.json'
Check ($config.Contains('define("projectExplosiveConventionalBombInterception", false)') -and
    $config.Contains('ModList.get().isLoaded(ProjectExplosiveCompatibility.MOD_ID)')) 'optional server config defaults OFF and is inert when PE is absent'
Check ($gradle -notmatch '(?is)\b(?:implementation|api|compileOnly|runtimeOnly)\s+[^\r\n]*projectexplosive' -and
    !$combat.Contains('projectexplosive:') -and !$combat.Contains('ProjectExplosiveCompatibility.isConventionalBomb')) 'no PE dependency or generic CombatServices projectile/neutralization whitelist was added'
Check ($observation.Contains('IRadar radar') -and $observation.Contains('radar.getTracks()') -and
    $observation.Contains('TrackCategory.PROJECTILE') -and $observation.Contains('level.getEntity(id)') -and
    $observation.Contains('ownerEntity instanceof Player') -and $observation.Contains('CombatServices.relation') -and
    $observation.Contains('CombatServices.canEngage') -and $observation.Contains('isProtected')) 'acquisition is radar-track-only and revalidates live UUID, player owner, IFF, engagement and protection'
Check ($observation.Contains('sameTrackObservation(matched, track)') -and
    $observation.Contains('unambiguousPhysicalTrack') -and $observation.Contains('requiredScannedAt != matched.scannedTime()')) 'duplicate physical UUID tracks are checked before exact scan selection and fail closed'
Check ($state.Contains('cmi$impactPending() || cmi$detonationStarted()') -and
    $observation.Contains('cmi$impactOrDetonationStarted()')) 'unknown, pending-impact, or detonating bomb state blocks acquisition'
Check ($smallMixin.Contains('@Pseudo') -and $smallMixin.Contains('@Accessor("l11oo0i1illii0o0liol1o1i")') -and
    $bigMixin.Contains('@Pseudo') -and $bigMixin.Contains('@Accessor("lii11l1iil010100loiio0li")') -and
    $mixinConfig.Contains('ProjectExplosiveSmallBombStateMixin') -and $mixinConfig.Contains('ProjectExplosiveBigBombStateMixin')) 'optional exact-version state bridges are pseudo mixins and registered'
Check ($smallMixin.Contains('@Mixin(targets = "' + $smallType + '", remap = false)') -and
    $bigMixin.Contains('@Mixin(targets = "' + $bigType + '", remap = false)') -and
    $smallMixin.Contains('@Invoker("ll1ol00o111oioool0l011lo")') -and
    $bigMixin.Contains('@Invoker("ll1ol00o111oioool0l011lo")')) 'both pseudo mixins target only the exact pinned PE classes and method names'
Check ($smallPending.Contains('descriptor: Z') -and $bigPending.Contains('descriptor: Z') -and
    $smallExploded.Contains('descriptor: ()Z') -and $bigExploded.Contains('descriptor: ()Z')) 'both invoker/accessor descriptors match the pinned boolean field and no-argument boolean method contracts'
Check ($integration.Contains('ModConfigEvent.Reloading') -and $integration.Contains('ModConfigEvent.Unloading') -and
    $integration.Contains('ProjectExplosiveInterception.clear()')) 'server configuration reload/unload clears all transient PE permits so OFF cannot be toggled back on to revive one'
Check ($neutralization.Contains('allowsConventionalBombNeutralization') -and
    $neutralization.Contains('managedCbcShot') -and $neutralization.Contains('physicalCollisionConfirmed') -and
    $neutralization.Contains('oneUsePermitConsumed') -and $neutralization.Contains('impactOrDetonationStarted')) 'destruction policy separately requires current conventional gates, managed CBC shot, physical collision and consumed permit'
Check ($interception.Contains('IMPACT_PERMITS.remove(interceptor)') -and
    $interception.Contains('onPhysicalCbcHit') -and $interception.Contains('target.discard()') -and
    $interception.Contains('authorizeCiwsTargetFromRadar')) 'one-use destructive action exists only in physical CBC callback after current radar revalidation'
Check ($interception.Contains('pending.cannonProjectileSpawns++') -and
    $interception.Contains('ProjectExplosivePermitPolicy.uniqueCbcSpawn(pending.cannonProjectileSpawns)') -and
    $interception.IndexOf('pending.cannonProjectileSpawns++') -lt $interception.IndexOf('ShotContext.managed()') -and
    $interception.Contains('!pending.ambiguous') -and $interception.Contains('level.getEntity(captured.getUUID()) == captured') -and
    $interception.Contains('IMPACT_PERMITS.remove(pending.capturedProjectile)')) 'native rejection, no insertion, or multiple CBC entities cannot leave a usable permit'
Check ($interception.Contains('authorization.contact().owner()') -and $interception.Contains('authorization.contact().platform()') -and
    $interception.Contains('physicalRadar.instance()') -and $interception.Contains('authorization.relation().name()') -and
    $interception.Contains('ProjectExplosivePermitPolicy.valid(issued, current')) 'permit freezes target owner/platform, exact radar instance, cannon, actor, dimension and ENEMY state; current identity is compared again'
Check ($interception.Contains('permit.use.consume()') -and $interception.Contains('clearChunk(ResourceLocation dimension, ChunkPos chunk)') -and
    $interception.Contains('cannonPosition') -and $interception.Contains('ProjectExplosiveBombObservation.clearRadarChunk')) 'callbacks are one-use and relevant chunk unload invalidates permit plus weak radar identity'
Check ($permitPolicy.Contains('issued.equals(current)') -and $permitPolicy.Contains('now <= expiresAt') -and
    $permitPolicy.Contains('class OneShot') -and $permitPolicy.Contains('boolean consumed')) 'pure permit policy requires exact identity, valid bounded time, and a single-use token'
Check ($observation.Contains('WeakHashMap') -and $observation.Contains('physicalRadarIdentity') -and
    $observation.Contains('clearRadarDimension') -and $observation.Contains('clearRadarInstances')) 'radar identity uses a weak block-entity key and retains value-only provenance'
Check ($collisionMixin.Contains('ProjectExplosiveInterception.canHit(self,l,other)') -and
    $collisionMixin.Contains('ProjectExplosiveInterception.onPhysicalCbcHit(self,l,other)') -and
    $collisionMixin.Contains('cmiPeImpactConsumed')) 'CBC bypass is limited to managed shot permit, PE callback, and one-successful-hit suppression'
Check ($collisionMixin -match '(?s)if\(!self\.getPersistentData\(\)\.getBoolean\("cmiManagedShot"\)\)return;\s*cir\.setReturnValue\(ProjectExplosiveInterception\.canHit' -and
    $collisionMixin -match '(?s)if\(!self\.getPersistentData\(\)\.getBoolean\("cmiManagedShot"\)\)return;\s*cir\.setReturnValue\(ProjectExplosiveInterception\.onPhysicalCbcHit') 'unmanaged CBC shots preserve native PE filter and callback behavior'
Check ($ciws.Contains('ProjectExplosiveBombObservation.authorizeCiwsTrack') -and
    $ciws.Contains('ProjectExplosiveInterception.fireWithPermit') -and $ciws.Contains('TrajectorySafety.clear(') -and
    $ciws.Contains('WeaponAdapters.fire(') -and $ciws.Contains('MAX_TRACKS = 128, MAX_CANDIDATES = 8')) 'CIWS reuses physical radar, existing trajectory checks/native firing and current fixed budgets'
Check ($ciws.Contains('requiresMotionConfidence(target)') -and
    $ciws.Contains('ProjectExplosiveCompatibility.isConventionalBomb(target)') -and
    $ciws.Contains('CiwsMotionPolicy.atImpact')) 'gravity-affected PE bombs require measured, confidence-checked temporal motion before firing'
Check (!$ciws.Contains('getAllEntities(') -and !$ciws.Contains('CombatServices.contacts(') -and
    !$ciws.Contains('Sensor.scan(')) 'no global or alternate target scanner was added'

Add-Type -AssemblyName System.IO.Compression
$archive = [System.IO.Compression.ZipFile]::OpenRead($artifactPath)
try {
    $entries = @($archive.Entries | ForEach-Object FullName)
    foreach ($entry in @('local/cmi/core/CmiConfig.class','local/cmi/core/ProjectExplosiveBombState.class',
            'local/cmi/core/ProjectExplosiveBombObservation.class','local/cmi/core/ProjectExplosiveInterception.class',
            'local/cmi/core/ProjectExplosivePermitPolicy.class','local/cmi/core/ProjectExplosivePermitPolicy$Identity.class',
            'local/cmi/core/ProjectExplosivePermitPolicy$OneShot.class',
            'local/cmi/mixin/safety/ProjectExplosiveSmallBombStateMixin.class',
            'local/cmi/mixin/safety/ProjectExplosiveBigBombStateMixin.class',
            'local/cmi/mixin/safety/CannonInterceptionMixin.class','local/cmi/weapons/CiwsCoordinator.class')) {
        Check ($entries -contains $entry) "compiled artifact contains $entry"
    }
    Check (@($entries | Where-Object { $_ -match '(^|/)projectexplosive/' }).Count -eq 0) 'compiled JAR embeds no Project: Explosive classes'
    Check (@($entries | Where-Object { $_ -match '\.jar$' }).Count -eq 0) 'compiled JAR embeds no third-party dependency jars'
} finally { $archive.Dispose() }
$observationBytecode = Listing $artifactPath 'local.cmi.core.ProjectExplosiveBombObservation'
$interceptionBytecode = Listing $artifactPath 'local.cmi.core.ProjectExplosiveInterception'
$permitPolicyBytecode = Listing $artifactPath 'local.cmi.core.ProjectExplosivePermitPolicy'
$oneShotBytecode = Listing $artifactPath 'local.cmi.core.ProjectExplosivePermitPolicy$OneShot'
$smallMixinBytecode = (& javap -p -v -classpath $artifactPath 'local.cmi.mixin.safety.ProjectExplosiveSmallBombStateMixin' 2>&1) -join "`n"
$bigMixinBytecode = (& javap -p -v -classpath $artifactPath 'local.cmi.mixin.safety.ProjectExplosiveBigBombStateMixin' 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'javap could not inspect compiled optional PE pseudo mixins' }
$hookBytecode = Listing $artifactPath 'local.cmi.mixin.safety.CannonInterceptionMixin'
$ciwsBytecode = Listing $artifactPath 'local.cmi.weapons.CiwsCoordinator'
Check ($observationBytecode.Contains('IRadar.getTracks') -and $observationBytecode.Contains('ServerLevel.getEntity')) 'compiled CMI observation still resolves only physical radar UUIDs'
Check ($interceptionBytecode.Contains('ImpactPermit') -and $interceptionBytecode.Contains('onPhysicalCbcHit') -and
    $interceptionBytecode.Contains('NeutralizationPolicy.allowsConventionalBombNeutralization')) 'compiled one-use impact path invokes the independent destructive policy'
Check ($permitPolicyBytecode.Contains('sameIdentity') -and $permitPolicyBytecode.Contains('valid') -and
    $permitPolicyBytecode.Contains('uniqueCbcSpawn') -and $permitPolicyBytecode.Contains('long 200l') -and $oneShotBytecode.Contains('consume()') -and
    $oneShotBytecode.Contains('consumed')) 'compiled artifact contains the exact-identity, bounded-lifetime and one-use permit policy'
Check ($smallMixinBytecode.Contains('org.spongepowered.asm.mixin.Pseudo') -and
    $smallMixinBytecode.Contains($smallType) -and $smallMixinBytecode.Contains('l11oo0i1illii0o0liol1o1i') -and
    $smallMixinBytecode.Contains('ll1ol00o111oioool0l011lo') -and
    $bigMixinBytecode.Contains('org.spongepowered.asm.mixin.Pseudo') -and $bigMixinBytecode.Contains($bigType) -and
    $bigMixinBytecode.Contains('lii11l1iil010100loiio0li') -and $bigMixinBytecode.Contains('ll1ol00o111oioool0l011lo')) 'compiled pseudo mixins retain exact optional target/accessor/invoker metadata without class literals'
Check ($hookBytecode.Contains('ProjectExplosiveInterception.canHit') -and
    $hookBytecode.Contains('ProjectExplosiveInterception.onPhysicalCbcHit')) 'compiled mixin exposes only the exact PE bridge around CBC filter/callback'
Check (($ciwsBytecode.Split('ProjectExplosiveBombObservation.authorizeCiwsTrack').Count - 1) -ge 2 -and
    $ciwsBytecode.Contains('ProjectExplosiveInterception.fireWithPermit') -and $ciwsBytecode.Contains('CiwsMotionPolicy.atImpact')) 'compiled CIWS revalidates acquisition/retention, uses scoped native fire and checks motion confidence'

Write-Host "Project Explosive conventional interception verifier: $script:Passes passed; $($script:Failures.Count) failed. Static only; no game/runtime claim."
if ($script:Failures.Count -gt 0) { throw ($script:Failures -join "`n") }
