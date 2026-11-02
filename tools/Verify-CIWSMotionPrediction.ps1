param(
    [Parameter(Mandatory)][string]$Radars,
    [Parameter(Mandatory)][string]$BetterTargeting,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/..").Path

function Require([bool]$condition, [string]$message) {
    if (!$condition) { throw $message }
}
function Listing([string]$jar, [string]$type) {
    $lines = & javap -p -c -s -classpath $jar $type 2>&1
    $exit = $LASTEXITCODE
    $text = $lines -join "`n"
    if ($exit -ne 0) { throw "javap failed for $type : $text" }
    return $text
}
function Has([string]$text, [string]$term, [string]$description) {
    if (!$text.Contains($term)) { throw "Missing $description : $term" }
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
    $pattern = [regex]::Escape($method) + '\([^\r\n]*\);\s*descriptor:\s*' + [regex]::Escape($descriptor)
    if ($listing -notmatch $pattern) { throw "Descriptor mismatch: $method $descriptor" }
}
function RequireHash([string]$jar, [string]$key) {
    $pins = @{}
    Get-Content -LiteralPath (Join-Path $repo 'dependency-hashes.properties') | ForEach-Object {
        if ($_ -match '^([^#=]+)=([0-9A-Fa-f]{64})$') { $pins[$Matches[1]] = $Matches[2].ToUpperInvariant() }
    }
    if (!(Test-Path -LiteralPath $jar) -or !$pins.ContainsKey($key)) { throw "Missing fixed jar or SHA pin: $key" }
    $actual = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
    if ($actual -ne $pins[$key]) { throw "Dependency SHA-256 mismatch for $key : $actual" }
}

RequireHash $BetterTargeting 'create-better-targeting-1.0.0.1.jar'
RequireHash $Radars 'create-radars-0.4.9.4.jar'
Require (Test-Path -LiteralPath $Artifact) "Missing built artifact: $Artifact"

# Better Targeting's fixed bytecode: the third vector is stored as acceleration and at(t)
# evaluates p + v*t + a*B(t), with B(t) capped into constant-velocity continuation at 30 ticks.
$targetMotion = Listing $BetterTargeting 'com.mrvew.firecontrol.ballistics.TargetMotion'
Descriptor $targetMotion 'acceleration' '()Lcom/mrvew/firecontrol/ballistics/V3;'
Descriptor $targetMotion 'at' '(D)Lcom/mrvew/firecontrol/ballistics/V3;'
Has $targetMotion 'private final com.mrvew.firecontrol.ballistics.V3 acceleration;' 'third TargetMotion record field'
$at = Section $targetMotion 'public com.mrvew.firecontrol.ballistics.V3 at(double);'
foreach ($term in @('double 30.0d', 'Math.min:(DD)D', 'Math.max:(DD)D', 'double 0.5d', 'Field acceleration:', 'V3.x:()D', 'V3.y:()D', 'V3.z:()D')) {
    Has $at $term 'TargetMotion accelerated state projection'
}
Require (([regex]::Matches($at, 'Field acceleration:')).Count -ge 3) 'TargetMotion.at does not apply acceleration on all three axes'

$solver = Listing $BetterTargeting 'com.mrvew.firecontrol.ballistics.FiringSolver'
Descriptor $solver 'approach' '(Lcom/mrvew/firecontrol/ballistics/V3;)Lcom/mrvew/firecontrol/ballistics/FiringSolver$Approach;'
Descriptor $solver 'path' '(Lcom/mrvew/firecontrol/ballistics/V3;D)Ljava/util/List;'
Descriptor $solver 'solve' '(Lcom/mrvew/firecontrol/ballistics/FiringSolver$Arc;[DD)Lcom/mrvew/firecontrol/ballistics/FiringSolver$Solution;'
Descriptor $solver 'solve' '(Lcom/mrvew/firecontrol/ballistics/FiringSolver$Arc;[DDZ)Lcom/mrvew/firecontrol/ballistics/FiringSolver$Solution;'
$approach = Section $solver 'public com.mrvew.firecontrol.ballistics.FiringSolver$Approach approach(com.mrvew.firecontrol.ballistics.V3);'
foreach ($term in @('TargetMotion.at:(D)Lcom/mrvew/firecontrol/ballistics/V3;', 'ShellModel.step:([D[D)V')) { Has $approach $term 'approach uses predicted target and simulated shell' }
$solve = Section $solver 'public com.mrvew.firecontrol.ballistics.FiringSolver$Solution solve(com.mrvew.firecontrol.ballistics.FiringSolver$Arc, double[], double, boolean);'
Has $solve 'TargetMotion.at:(D)Lcom/mrvew/firecontrol/ballistics/V3;' 'solve samples the target motion model'
Has $solve 'finish:(Lcom/mrvew/firecontrol/ballistics/FiringSolver$Arc;[D)Lcom/mrvew/firecontrol/ballistics/FiringSolver$Solution;' 'solve finishes through the shared approach calculation'
$finish = Section $solver 'private com.mrvew.firecontrol.ballistics.FiringSolver$Solution finish(com.mrvew.firecontrol.ballistics.FiringSolver$Arc, double[]);'
foreach ($term in @('approach:(Lcom/mrvew/firecontrol/ballistics/V3;)Lcom/mrvew/firecontrol/ballistics/FiringSolver$Approach;', 'TargetMotion.at:(D)Lcom/mrvew/firecontrol/ballistics/V3;')) {
    Has $finish $term 'solution time/intercept uses the same target motion'
}
$path = Section $solver 'public java.util.List<com.mrvew.firecontrol.ballistics.V3> path(com.mrvew.firecontrol.ballistics.V3, double);'
Has $path 'ShellModel.step:([D[D)V' 'path advances the physical fired shell'
Require (!$path.Contains('TargetMotion.at:')) 'FiringSolver.path unexpectedly predicts the target instead of the fired shell'

# Radar tracks are world observations and are refreshed by the physical scan every five ticks.
$track = Listing $Radars 'com.happysg.radar.block.radar.track.RadarTrack'
Descriptor $track 'position' '()Lnet/minecraft/world/phys/Vec3;'
Descriptor $track 'velocity' '()Lnet/minecraft/world/phys/Vec3;'
Descriptor $track 'scannedTime' '()J'
Descriptor $track 'id' '()Ljava/lang/String;'
$scanner = Listing $Radars 'com.happysg.radar.block.radar.behavior.RadarScanningBlockBehavior'
$tick = Section $scanner 'public void tick();'
foreach ($term in @('Level.getGameTime:()J', 'long 5l', 'lrem', 'lconst_1', 'updateRadarTracks:()V', 'scanForEntityTracks:()V')) {
    Has $tick $term 'five-tick physical radar update cadence'
}
$update = Section $scanner 'lambda$updateRadarTracks$1(net.minecraft.world.entity.Entity, java.lang.String, com.happysg.radar.block.radar.track.RadarTrack);'
Has $update 'RadarTrack.updateRadarTrack:(Lnet/minecraft/world/entity/Entity;)V' 'track refresh delegates to the fixed entity update method'
$refresh = Section $track 'public void updateRadarTrack(net.minecraft.world.entity.Entity);'
foreach ($term in @('Entity.position:()Lnet/minecraft/world/phys/Vec3;', 'Entity.getDeltaMovement:()Lnet/minecraft/world/phys/Vec3;', 'Level.getGameTime:()J', 'Field scannedTime:J')) {
    Has $refresh $term 'track refresh uses current entity position, velocity and scan time'
}

# CMI stores only values from physical radar tracks, keyed by radar + mount + target provenance.
$coordinatorPath = Join-Path $repo 'src/main/java/local/cmi/weapons/CiwsCoordinator.java'
$trackerPath = Join-Path $repo 'src/main/java/local/cmi/weapons/CiwsMotionTracker.java'
$policyPath = Join-Path $repo 'src/main/java/local/cmi/weapons/CiwsMotionPolicy.java'
$coordinator = Get-Content -Raw -LiteralPath $coordinatorPath
$tracker = Get-Content -Raw -LiteralPath $trackerPath
$policy = Get-Content -Raw -LiteralPath $policyPath
foreach ($term in @('radar.getTracks()', 'target instanceof MunitionsPhysicsContraptionEntity',
        'MOTION.observe(observer, id', 'track.scannedTime()', 'Ships.v3(track.position())', 'Ships.v3(track.velocity())',
        'new CiwsMotionTracker.Provenance(contact.owner(), contact.platform())',
        'MOTION.beginObservation(observer, now)',
        'V3 targetAcceleration = motionAssessment == null ? V3.ZERO : motionAssessment.acceleration()',
        'new TargetMotion(Ships.v3(contact.position()), Ships.v3(velocity), targetAcceleration)',
        'plan.solver().approach(aim.barrel())', 'solver.path(direction, lifetime)',
        'radarTrackStillCurrent(binding.radar()', 'motionStillCurrent(plan, current)', 'CiwsMotionPolicy.atImpact', 'TrajectorySafety.clear',
        'CombatServices.relation', 'CombatServices.canEngage', 'CombatServices.reserve', 'WeaponAdapters.fire')) {
    Has $coordinator $term 'CIWS motion history, shared model and final authorization'
}
Require (!$coordinator.Contains('new TargetMotion(Ships.v3(contact.position()), Ships.v3(velocity), V3.ZERO)')) 'CIWS regressed to unconditional zero acceleration for all targets'
foreach ($forbidden in @('CombatServices.contacts(', 'getAllEntities(', 'Sensor.scan(', 'CombatServices.neutralize(', 'new RadarSpearMissileProjectileEntity', 'BlockWeaponRecoil.spawn(')) {
    Require (!$coordinator.Contains($forbidden)) "Forbidden sensor, synthetic launch, or predictive neutralization: $forbidden"
}
foreach ($term in @('SCAN_INTERVAL_TICKS = 5', 'MAX_SAMPLES = 3', 'MAX_ENTRIES = 2048', 'STALE_AFTER_TICKS = 10',
        'scannedTime == previous.scannedTime()', 'delta != SCAN_INTERVAL_TICKS', 'Provenance', 'clearDimension', 'clearChunk',
        'inChunk(observer.mountPosition(), chunk)', 'inChunk(observer.radarPosition(), chunk)')) {
    Has $tracker $term 'bounded source-aware temporal sample history'
}
foreach ($term in @('Model { LINEAR, ACCELERATED, UNRELIABLE, INVALID, INSUFFICIENT }',
        'recentAcceleration', 'firstAcceleration', 'differenceOverObservedInterval',
        '0.5 * accelerationError * flightTicks * flightTicks', 'solverMiss >= hitTolerance')) {
    Has $policy $term 'acceleration stability and actual hit-margin uncertainty policy'
}
Require (!$tracker.Contains('net.minecraft.world.entity.Entity') -and !$tracker.Contains('ServerLevel') -and !$tracker.Contains('CombatServices.')) 'Tracker must not retain world/entity references or act as a sensor'

# Inspect compiled output as well as source so a stale or incomplete jar cannot pass.
$artifactCoordinator = Listing $Artifact 'local.cmi.weapons.CiwsCoordinator'
$artifactTracker = Listing $Artifact 'local.cmi.weapons.CiwsMotionTracker'
$artifactPolicy = Listing $Artifact 'local.cmi.weapons.CiwsMotionPolicy'
foreach ($term in @('CiwsMotionTracker.observe', 'CiwsMotionPolicy.assess', 'CiwsMotionPolicy.atImpact',
        'MunitionsPhysicsContraptionEntity', 'RadarTrack.scannedTime', 'WeaponAdapters.fire')) {
    Has $artifactCoordinator $term 'compiled CIWS motion integration'
}
foreach ($term in @('MAX_SAMPLES', 'MAX_ENTRIES', 'SCAN_INTERVAL_TICKS', 'prune')) { Has $artifactTracker $term 'compiled bounded motion history' }
foreach ($term in @('ACCELERATED', 'UNRELIABLE', 'atImpact')) { Has $artifactPolicy $term 'compiled confidence policy' }

'OK: fixed TargetMotion acceleration and 30-tick horizon; solve/approach share target projection while path remains the shell path; physical radar cadence and value-only CIWS motion gating verified. Static only; runtime remains unverified.'
