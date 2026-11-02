param(
    [Parameter(Mandatory)][string]$Sable,
    [Parameter(Mandatory)][string]$Warium,
    [Parameter(Mandatory)][string]$Radars,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$pins = @{}
Get-Content -LiteralPath (Join-Path $root 'dependency-hashes.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=([0-9A-Fa-f]{64})$') { $pins[$Matches[1]] = $Matches[2].ToUpperInvariant() }
}
function RequireHash([string]$jar, [string]$name) {
    if (!(Test-Path -LiteralPath $jar)) { throw "Missing pinned dependency: $jar" }
    if (!$pins.ContainsKey($name) -or (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $pins[$name]) { throw "Dependency hash mismatch: $name" }
}
function Listing([string]$jar, [string]$type, [string[]]$nested = @()) {
    $classpath = (@($jar) + $nested) -join [IO.Path]::PathSeparator
    $result = (& javap -p -c -s -classpath $classpath $type 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "javap failed for $type : $result" }
    return $result
}
function VerboseListing([string]$jar, [string]$type, [string[]]$nested = @()) {
    $classpath = (@($jar) + $nested) -join [IO.Path]::PathSeparator
    $result = (& javap -p -c -v -classpath $classpath $type 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "javap -v failed for $type : $result" }
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
function Descriptor([string]$listing, [string]$method, [string]$descriptor) {
    if ($listing -notmatch ([regex]::Escape($method) + '\([^\r\n]*\);\s*descriptor:\s*' + [regex]::Escape($descriptor))) {
        throw "Descriptor mismatch: $method $descriptor"
    }
}
function Has([string]$text, [string]$term, [string]$description) {
    if ($text.IndexOf($term, [StringComparison]::Ordinal) -lt 0) { throw "Missing $description : $term" }
}
function HasPattern([string]$text, [string]$pattern, [string]$description) {
    if ($text -notmatch $pattern) { throw "Missing $description : $pattern" }
}

RequireHash $Sable 'sable-neoforge-1.21.1-2.0.6.jar'
RequireHash $Warium 'wariumce-1.0.4.jar'
RequireHash $Radars 'create-radars-0.4.9.4.jar'
if (!(Test-Path -LiteralPath $Artifact)) { throw "Missing built artifact: $Artifact" }
$nested = @((Get-ChildItem -LiteralPath "$root/build/compile-nested" -File -Filter '*.jar').FullName)

$container = Listing $Sable 'dev.ryanhcode.sable.api.sublevel.SubLevelContainer' $nested
Descriptor $container 'getContainer' '(Lnet/minecraft/server/level/ServerLevel;)Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;'
Descriptor $container 'getSubLevel' '(Ljava/util/UUID;)Ldev/ryanhcode/sable/sublevel/SubLevel;'
Descriptor $container 'getAllSubLevels' '()Ljava/util/List;'
Descriptor $container 'getPlot' '(Lnet/minecraft/world/level/ChunkPos;)Ldev/ryanhcode/sable/sublevel/plot/LevelPlot;'
Descriptor $container 'getChunk' '(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/LevelChunk;'
$containerVerbose = VerboseListing $Sable 'dev.ryanhcode.sable.api.sublevel.SubLevelContainer' $nested
Has $containerVerbose 'REF_invokeVirtual dev/ryanhcode/sable/sublevel/SubLevel.tick:()V' 'parent container to sublevel tick method reference'
$plot = Listing $Sable 'dev.ryanhcode.sable.sublevel.plot.LevelPlot' $nested
Descriptor $plot 'toLocal' '(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/ChunkPos;'
Descriptor $plot 'toGlobal' '(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/ChunkPos;'
Descriptor $plot 'getChunk' '(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/LevelChunk;'
Descriptor $plot 'contains' '(Lnet/minecraft/world/level/ChunkPos;)Z'
$sublevel = Listing $Sable 'dev.ryanhcode.sable.sublevel.SubLevel' $nested
Descriptor $sublevel 'getLevel' '()Lnet/minecraft/world/level/Level;'
Descriptor $sublevel 'getPlot' '()Ldev/ryanhcode/sable/sublevel/plot/LevelPlot;'
$sublevelTick = Section $sublevel 'public void tick();'
Has $sublevelTick 'LevelPlot.tick:()V' 'sublevel plot tick call'
$serverPlot = Listing $Sable 'dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot' $nested
$plotTick = Section $serverPlot 'public void tick();'
foreach ($term in @('LevelLightEngine.runLightUpdates', 'LevelLightEngine.hasLightWork')) { Has $plotTick $term 'ServerLevelPlot tick path' }
foreach ($forbidden in @('LevelChunk.tickBlockEntities', 'BlockEntityTicker', 'BlockEntity.tick', 'LevelChunk$BoundTickingBlockEntity')) {
    if ($plotTick.Contains($forbidden)) { throw "Sable plot tick unexpectedly calls block-entity ticking: $forbidden" }
}
$embedded = Listing $Sable 'dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor' $nested
Has $embedded 'implements net.minecraft.world.level.CommonLevelAccessor,net.minecraft.world.level.ServerLevelAccessor' 'embedded plot accessor interface'
Has (Section $embedded 'public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos);') 'BlockPos.offset' 'embedded plot block lookup offset'

$pose = Listing $Sable 'dev.ryanhcode.sable.companion.math.Pose3dc' $nested
Descriptor $pose 'transformPosition' '(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;'
$access = Listing $Sable 'dev.ryanhcode.sable.companion.SubLevelAccess' $nested
Descriptor $access 'logicalPose' '()Ldev/ryanhcode/sable/companion/math/Pose3dc;'

$fire = Listing $Warium 'com.wariumce.procedures.MissileHardpointFireProcedure'
Descriptor $fire 'execute' '(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/level/block/state/BlockState;)V'
$fireMethod = Section $fire 'public static void execute(net.minecraft.world.level.LevelAccessor, double, double, double, net.minecraft.world.level.block.state.BlockState);'
HasPattern $fireMethod '(?m)\binstanceof\s+#\d+\s+// class net/minecraft/server/level/ServerLevel\b' 'Warium ServerLevel-gated projectile branch'
HasPattern $fireMethod '(?m)\bcheckcast\s+#\d+\s+// class net/minecraft/server/level/ServerLevel\b' 'Warium parent ServerLevel cast'
Has $fireMethod 'BlockWeaponRecoil.spawn:(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/core/BlockPos;Lcom/wariumce/util/BlockWeaponRecoil$Weapon;)Z' 'Warium native projectile insertion route'
$recoil = Listing $Warium 'com.wariumce.util.BlockWeaponRecoil'
Descriptor $recoil 'spawn' '(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/core/BlockPos;Lcom/wariumce/util/BlockWeaponRecoil$Weapon;)Z'

$refs = Get-Content -Raw -LiteralPath (Join-Path $root 'src/main/java/local/cmi/core/MountedBlockReference.java')
foreach ($term in @('Space.WORLD', 'Space.SABLE', 'ResourceKey.create', 'level.hasChunkAt(position)', 'container.getSubLevel(shipUuid)',
        'ship.isRemoved()', 'plot.toGlobal(localChunk)', 'plot.getChunk(localChunk)', 'chunk.getBlockEntity(storedPosition)',
        'ship.logicalPose()', 'blockData.contains("cmiOwner")', 'sableActor(shipUuid, ship, resolved.get().blockEntity().getPersistentData())',
        'CmiData.get(resolved.get().level()).ship(shipUuid)', 'CombatServices.actor')) {
    Has $refs $term 'mounted-reference space/lookup/ownership/pose guard'
}
Has $refs 'worldCenter(ship.logicalPose(), position)' 'live Sable pose transform on each resolution'

$automation = Get-Content -Raw -LiteralPath (Join-Path $root 'src/main/java/local/cmi/missiles/RadarSpearAutomation.java')
$ciws = Get-Content -Raw -LiteralPath (Join-Path $root 'src/main/java/local/cmi/weapons/CiwsCoordinator.java')
foreach ($source in @($automation, $ciws)) {
    Has $source 'MountedBlockReference.sable' 'typed Sable reference binding'
    Has $source 'BindingExecutable", false' 'non-executable Sable binding marker'
}
$commands = Get-Content -Raw -LiteralPath (Join-Path $root 'src/main/java/local/cmi/MountedSystemCommands.java')
foreach ($term in @('"mounted"', '"inspect"', '"sable"', 'if (enabled) {', 'BLOQUEADO',
        'disableNonExecutableMounted(mount);', 'RadarSpearAutomation.setEnabled(blockEntity, false)', 'setSableRadarSpearProfile',
        'data.putBoolean("cmiManaged", true)', 'data.putUUID("cmiOwner", actor.owner())',
        'data.putString("cmiRadarProfile", profile)', 'Auto remains false; launch and native spawn remain BLOQUEADOS')) {
    Has $commands $term 'unambiguous Sable command and fail-closed mode'
}
$ciwsStart = $commands.IndexOf('private static int setCiws(', [StringComparison]::Ordinal)
$autoStart = $commands.IndexOf('private static int setRadarSpearAuto(', [StringComparison]::Ordinal)
$profileStart = $commands.IndexOf('private static int setSableRadarSpearProfile(', [StringComparison]::Ordinal)
if ($ciwsStart -lt 0 -or $autoStart -lt 0 -or $profileStart -lt 0) { throw 'Missing Sable mode command handlers' }
$ciwsEnable = $commands.Substring($ciwsStart, $autoStart - $ciwsStart)
$radarEnable = $commands.Substring($autoStart, $profileStart - $autoStart)
$ciwsDisable = $ciwsEnable.IndexOf('disableNonExecutableMounted(mount);', [StringComparison]::Ordinal)
$ciwsBlocked = $ciwsEnable.IndexOf('BLOQUEADO:', [StringComparison]::Ordinal)
$radarClear = $radarEnable.IndexOf('RadarSpearAutomation.setEnabled(blockEntity, false)', [StringComparison]::Ordinal)
$radarBlocked = $radarEnable.IndexOf('BLOQUEADO:', [StringComparison]::Ordinal)
if ($ciwsDisable -lt 0 -or $ciwsBlocked -lt 0 -or $ciwsDisable -gt $ciwsBlocked) { throw 'CIWS Sable true path does not clear its old flag before returning BLOQUEADO' }
if ($radarClear -lt 0 -or $radarBlocked -lt 0 -or $radarClear -gt $radarBlocked) { throw 'Radar Spear Sable true path does not clear its old flag before returning BLOQUEADO' }
$profileBody = $commands.Substring($profileStart, $commands.IndexOf('private static boolean ownedByCurrentPlayer(', $profileStart, [StringComparison]::Ordinal) - $profileStart)
$profileDisable = $profileBody.IndexOf('RadarSpearAutomation.setEnabled(blockEntity, false)', [StringComparison]::Ordinal)
$profileManaged = $profileBody.IndexOf('data.putBoolean("cmiManaged", true)', [StringComparison]::Ordinal)
$profileOwner = $profileBody.IndexOf('data.putUUID("cmiOwner", actor.owner())', [StringComparison]::Ordinal)
$profileValue = $profileBody.IndexOf('data.putString("cmiRadarProfile", profile)', [StringComparison]::Ordinal)
$profileBlocked = $profileBody.IndexOf('launch and native spawn remain BLOQUEADOS', [StringComparison]::Ordinal)
if ($profileDisable -lt 0 -or $profileManaged -lt 0 -or $profileOwner -lt 0 -or $profileValue -lt 0 -or $profileBlocked -lt 0 -or
        !($profileDisable -lt $profileManaged -and $profileManaged -lt $profileOwner -and $profileOwner -lt $profileValue -and $profileValue -lt $profileBlocked)) {
    throw 'Sable Radar Spear profile must disable auto, write managed owner/profile, and report that launch stays blocked'
}
foreach ($forbidden in @('MissileHardpointFireProcedure.execute(', 'BlockWeaponRecoil.spawn(', 'WeaponAdapters.fire(')) {
    if ($commands.Contains($forbidden)) { throw "Sable mounted commands must not create or fire projectiles: $forbidden" }
}

$artifactListing = Listing $Artifact 'local.cmi.core.MountedBlockReference'
foreach ($method in @('resolve', 'actor', 'sableActor', 'worldCenter', 'storedPlotPosition', 'load', 'save')) {
    if ($artifactListing -notmatch ('\b' + [regex]::Escape($method) + '\(')) { throw "MountedBlockReference method absent from artifact: $method" }
}
'OK: pinned Sable plot lookup and tick restrictions, current-pose transforms, Warium ServerLevel-gated hardpoint branch and Level spawn signature, typed fail-closed references/ownership and non-executable mounted commands. Static inspection only; no runtime tick, mixin, radar, aiming or projectile spawn was executed.'
