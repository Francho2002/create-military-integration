param(
    [Parameter(Mandatory)][string]$Warium,
    [Parameter(Mandatory)][string]$Radars,
    [Parameter(Mandatory)][string]$BetterTargeting,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference = 'Stop'

function Listing([string]$jar, [string]$type, [string]$mode) {
    $modeArgs = $mode -split '\s+'
    $result = (& javap -p @modeArgs -classpath $jar $type 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "javap failed for $type : $result" }
    return $result
}
function RequireDescriptor([string]$listing, [string]$name, [string]$descriptor) {
    if ($listing -notmatch ([regex]::Escape($name) + '\([^\r\n]*\);\s*descriptor:\s*' + [regex]::Escape($descriptor))) { throw "Descriptor mismatch: $name $descriptor" }
}
function RequirePinnedHash([string]$jar, [string]$key, [hashtable]$pins) {
    if (-not $pins.ContainsKey($key)) { throw "Missing pinned hash for $key" }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash
    if ($actual -ne $pins[$key]) { throw "Pinned dependency hash mismatch: $key ($actual)" }
}

$repo = (Resolve-Path "$PSScriptRoot/..").Path
$pins = @{}
Get-Content -LiteralPath (Join-Path $repo 'dependency-hashes.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=([0-9A-Fa-f]{64})$') { $pins[$Matches[1]] = $Matches[2].ToUpperInvariant() }
}
RequirePinnedHash $Warium 'wariumce-1.0.4.jar' $pins
RequirePinnedHash $Radars 'create-radars-0.4.9.4.jar' $pins
RequirePinnedHash $BetterTargeting 'create-better-targeting-1.0.0.1.jar' $pins

$iradar = Listing $Radars 'com.happysg.radar.block.radar.behavior.IRadar' '-s'
RequireDescriptor $iradar 'getTracks' '()Ljava/util/Collection;'
RequireDescriptor $iradar 'isRunning' '()Z'
RequireDescriptor $iradar 'getRange' '()F'
foreach ($type in @('com.happysg.radar.block.radar.plane.StationaryRadarBlockEntity','com.happysg.radar.block.radar.bearing.RadarBearingBlockEntity')) {
    $be = Listing $Radars $type '-p'
    if ($be -notmatch 'implements .*com\.happysg\.radar\.block\.radar\.behavior\.IRadar') { throw "$type no longer implements the physical IRadar API" }
}
$track = Listing $Radars 'com.happysg.radar.block.radar.track.RadarTrack' '-s'
RequireDescriptor $track 'trackCategory' '()Lcom/happysg/radar/block/radar/track/TrackCategory;'
RequireDescriptor $track 'id' '()Ljava/lang/String;'
RequireDescriptor $track 'scannedTime' '()J'
$scan = Listing $Radars 'com.happysg.radar.block.radar.behavior.RadarScanningBlockBehavior' '-p -s'
foreach ($method in @('tick','isInFovAndRange','scanForSableTracks','updateRadarTracks')) {
    if ($scan -notmatch ([regex]::Escape($method) + '\(')) { throw "Pinned radar scan behavior missing $method" }
}
$scanCode = Listing $Radars 'com.happysg.radar.block.radar.behavior.RadarScanningBlockBehavior' '-c'
if ($scanCode -notmatch 'scannedTime|RadarTrack') { throw 'Radar scanner bytecode does not expose tracked scan records' }

$trigger = Listing $Warium 'com.wariumce.procedures.HardpointTriggerTickProcedure' '-s'
RequireDescriptor $trigger 'execute' '(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/level/block/state/BlockState;)V'
$triggerCode = Listing $Warium 'com.wariumce.procedures.HardpointTriggerTickProcedure' '-c'
foreach ($literal in @('TargetX','TargetY','TargetZ','MissileHardpointFireProcedure.execute')) {
    if ($triggerCode -notmatch [regex]::Escape($literal)) { throw "Warium trigger contract changed: $literal" }
}
$fire = Listing $Warium 'com.wariumce.procedures.MissileHardpointFireProcedure' '-s'
RequireDescriptor $fire 'execute' '(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/level/block/state/BlockState;)V'
$fireCode = Listing $Warium 'com.wariumce.procedures.MissileHardpointFireProcedure' '-c'
if ($fireCode -notmatch 'RADAR_SPEAR_MISSILE_HARDPOINT' -or $fireCode -notmatch 'BlockWeaponRecoil\.spawn' -or $fireCode -notmatch 'EMPTY_MISSILE_HARDPOINT') {
    throw 'Warium native Radar Spear spawn/consumption route changed'
}

$sensor = Listing $BetterTargeting 'com.mrvew.firecontrol.target.Sensor' '-p'
if ($sensor -notmatch 'scan\(') { throw 'Expected Better Targeting Sensor scan entry point was not found for source verification' }
$sensorCode = Listing $BetterTargeting 'com.mrvew.firecontrol.target.Sensor' '-c'
if ($sensorCode -notmatch 'ServerLevel\.getEntitiesOfClass' -or $sensorCode -match 'com/happysg/radar/block/radar/behavior/IRadar') { throw 'Better Targeting Sensor scan assumptions changed; inspect before using it as a non-radar helper' }
$radarSource = Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/java/local/cmi/missiles/RadarSpearAutomation.java')
if ($radarSource -notmatch 'IRadar' -or $radarSource -notmatch '\.getTracks\(' -or $radarSource -notmatch 'TrackCategory\.SABLE') { throw 'Automation no longer requires an actual physical radar Sable track' }
if ($radarSource -match 'CombatServices\.contacts\s*\(' -or $radarSource -match 'Sensor\.scan\s*\(') { throw 'Radar acquisition regressed to a global contact scan/non-physical sensor' }
if ($radarSource -match 'new\s+RadarSpearMissileProjectileEntity' -or $radarSource -match 'BlockWeaponRecoil\.spawn\s*\(') { throw 'CMI must not directly create the missile or invoke Warium recoil spawn' }
if ($radarSource -notmatch 'MissileHardpointFireProcedure\.execute\s*\(') { throw 'Automation does not enter Warium native launch procedure' }
foreach ($guard in @('data\.contains\(AUTO\) && \(!data\.contains\(AUTO, Tag\.TAG_BYTE\) \|\| data\.getBoolean\(AUTO\)\)','MAX_SABLE_TRACKS_PER_SCAN\s*=\s*128','TrackCategory\.SABLE','RadarSpearLockPolicy\.fresh','contact\.owner\(\) == null','contact\.platform\(\) != null','Relation\.ENEMY','CombatServices\.canEngage','shipId\.equals\(currentActor\.platform\(\)\)','cmiRadarProfile','direct_sable','explosive_sable')) {
    if ($radarSource -notmatch $guard) { throw "Missing automatic acquisition/IFF/own-platform guard: $guard" }
}
if ($radarSource -notmatch 'TargetX' -or $radarSource -notmatch 'TargetY' -or $radarSource -notmatch 'TargetZ') { throw 'CMI no longer writes the exact Warium target coordinate keys' }
if ($radarSource -notmatch 'MissileHardpointFireProcedure\.execute' -or $radarSource -notmatch 'authorizedNativeFire') { throw 'Native trigger/fire authorization gate is incomplete' }

$autoTrigger = Listing $Artifact 'local.cmi.mixin.missiles.RadarSpearAutoTriggerMixin' '-s'
$autoFire = Listing $Artifact 'local.cmi.mixin.missiles.RadarSpearAutoFireGuardMixin' '-s'
$callback = 'Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;'
RequireDescriptor $autoTrigger 'cmi$runRadarAutomation' "(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/level/block/state/BlockState;$callback)V"
RequireDescriptor $autoFire 'cmi$requireAuthorizedAutoRequest' "(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/level/block/state/BlockState;$callback)V"
$mixins = Get-Content -Raw -LiteralPath (Join-Path $repo 'src/main/resources/cmi.mixins.json')
foreach ($name in @('missiles.RadarSpearAutoTriggerMixin','missiles.RadarSpearAutoFireGuardMixin')) {
    if ($mixins -notmatch [regex]::Escape('"' + $name + '"')) { throw "Mixin not registered: $name" }
}
$bridge = Listing $Artifact 'local.cmi.missiles.RadarSpearBridge' '-c'
if ($bridge -notmatch 'RadarSpearAutomation\.authorizesLaunch') { throw 'Native launch capture does not revalidate the current physical radar track' }

'OK: pinned radar interface/tracks, Warium target and fire contracts, auto mixin descriptors and no direct spawn/global-contact acquisition. Static only; mixins/runtime not applied.'
