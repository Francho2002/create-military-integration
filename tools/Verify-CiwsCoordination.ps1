param(
    [Parameter(Mandatory)][string]$CBC,
    [Parameter(Mandatory)][string]$NeoWarfare,
    [string]$ModpackDir,
    [string]$CandidatesDir,
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/..").Path
function Require([bool]$condition, [string]$message) { if (!$condition) { throw $message } }
function Has([string]$text, [string]$term, [string]$description) {
    if (!$text.Contains($term)) { throw "Missing $description : $term" }
}
function Ordered([string]$text, [string[]]$terms, [string]$description) {
    $last = -1
    foreach ($term in $terms) {
        $pos = $text.IndexOf($term, $last + 1, [StringComparison]::Ordinal)
        if ($pos -lt 0) { throw "Changed $description : expected $term in order" }
        $last = $pos
    }
}
function SourceSection([string]$source, [string]$signature) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing source method: $signature" }
    $open = $source.IndexOf('{', $start)
    if ($open -lt 0) { throw "Missing method body: $signature" }
    $depth = 0
    for ($i = $open; $i -lt $source.Length; $i++) {
        if ($source[$i] -eq '{') { $depth++ }
        elseif ($source[$i] -eq '}') {
            $depth--
            if ($depth -eq 0) { return $source.Substring($start, $i - $start + 1) }
        }
    }
    throw "Unclosed method body: $signature"
}
function Listing([string]$jar, [string]$type) {
    $lines = & javap -p -c -s -classpath $jar $type 2>&1
    $exit = $LASTEXITCODE
    $text = $lines -join "`n"
    if ($exit -ne 0) { throw "javap failed for $type : $text" }
    return $text
}
function RequireHash([string]$jar, [string]$key) {
    $pins = @{}
    Get-Content -LiteralPath (Join-Path $repo 'dependency-hashes.properties') | ForEach-Object {
        if ($_ -match '^([^#=]+)=([0-9A-Fa-f]{64})$') { $pins[$Matches[1]] = $Matches[2].ToUpperInvariant() }
    }
    Require ((Test-Path -LiteralPath $jar) -and $pins.ContainsKey($key)) "Missing fixed JAR or SHA pin: $key"
    $actual = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
    Require ($actual -eq $pins[$key]) "Dependency SHA-256 mismatch for $key : $actual"
}
function HasVoidFireShot([string]$jar, [string]$type) {
    $listing = Listing $jar $type
    $desc = '(Lnet/minecraft/server/level/ServerLevel;Lrbasamoyai/createbigcannons/cannon_control/contraption/PitchOrientedContraptionEntity;)V'
    Require ($listing -match ('fireShot\([^\r\n]*\);\s*descriptor:\s*' + [regex]::Escape($desc))) "Native fireShot is not the inspected void contract for $type"
    return $listing
}

$pins = @{}
Get-Content -LiteralPath (Join-Path $repo 'dependency-hashes.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=([0-9A-Fa-f]{64})$') { $pins[$Matches[1]] = $Matches[2].ToUpperInvariant() }
    elseif (-not [string]::IsNullOrWhiteSpace($_) -and $_ -notmatch '^\s*#') { throw "Malformed dependency pin: $_" }
}
$expectedNames = @('create-1.21.1-6.0.10.jar', 'sable-neoforge-1.21.1-2.0.6.jar',
    'createbigcannons-5.11.7+mc.1.21.1.jar', 'cbcmodernwarfare-0.0.6v+mc.1.21.1-neoforge.jar',
    'cbcwariumce-1.2.0.jar', 'wariumce-1.0.4.jar', 'create-radars-0.4.9.4.jar',
    'create-better-targeting-1.0.0.1.jar', 'ftb-teams-neoforge-2101.1.11.jar',
    'ftb-chunks-neoforge-2101.1.22.jar', 'minecolonies-1.1.1403-1.21.1.jar')
Require ($pins.Count -eq $expectedNames.Count) 'Pinned dependency set changed; review this verifier deliberately.'
foreach ($name in $expectedNames) { Require ($pins.ContainsKey($name)) "Missing fixed SHA-256 pin: $name" }
if ([bool]$ModpackDir -xor [bool]$CandidatesDir) { throw 'Supply both -ModpackDir and -CandidatesDir, or neither.' }
if ($ModpackDir -and $CandidatesDir) {
    foreach ($name in $expectedNames) {
        $folder = if ($name -in @('create-radars-0.4.9.4.jar', 'create-better-targeting-1.0.0.1.jar')) { $CandidatesDir } else { $ModpackDir }
        $jarPath = Join-Path $folder $name
        Require (Test-Path -LiteralPath $jarPath) "Missing pinned dependency JAR: $jarPath"
        $actual = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToUpperInvariant()
        Require ($actual -eq $pins[$name]) "Dependency SHA-256 mismatch for $name : $actual"
    }
    $dependencyEvidence = 'local bytes for all 11 pinned dependencies match their pins'
} else {
    $dependencyEvidence = '11 dependency pin records present; local JAR bytes not supplied'
}

RequireHash $CBC 'createbigcannons-5.11.7+mc.1.21.1.jar'
RequireHash $NeoWarfare 'cbcmodernwarfare-0.0.6v+mc.1.21.1-neoforge.jar'
Require (Test-Path -LiteralPath $Artifact) "Missing CMI artifact: $Artifact"

$coordinatorPath = Join-Path $repo 'src/main/java/local/cmi/weapons/CiwsCoordinator.java'
$policyPath = Join-Path $repo 'src/main/java/local/cmi/weapons/CiwsCoordinationPolicy.java'
$coordinationRegressionPath = Join-Path $repo 'src/test/java/local/cmi/weapons/CiwsCoordinationRegression.java'
$reservationPath = Join-Path $repo 'src/main/java/local/cmi/core/ReservationBook.java'
$weaponPath = Join-Path $repo 'src/main/java/local/cmi/weapons/WeaponAdapters.java'
$coordinator = Get-Content -Raw -LiteralPath $coordinatorPath
$policy = Get-Content -Raw -LiteralPath $policyPath
$regression = Get-Content -Raw -LiteralPath $coordinationRegressionPath
$reservation = Get-Content -Raw -LiteralPath $reservationPath
$weapon = Get-Content -Raw -LiteralPath $weaponPath
$tick = SourceSection $coordinator 'public static void tick(ServerLevel level, CompactCannonMountBlockEntity mount)'
$plans = SourceSection $coordinator 'private static List<Plan> plans('
$order = SourceSection $policy 'public static List<CiwsPolicy.Candidate> orderedCandidates('
$safePath = SourceSection $coordinator 'private static boolean safePath('
$clearLock = SourceSection $coordinator 'private static void clearLock('

# Physical radar stays the only source; all assignments are mount-local candidates.
foreach ($term in @('radar.getTracks()', 'TrackCategory.PROJECTILE', 'CiwsCoordinationPolicy.orderedCandidates',
        'CombatServices.reserve(level, candidatePlan.contact().id(), identity', 'continue;',
        'MAX_TRACKS = 128, MAX_CANDIDATES = 8, MAX_FLIGHT_TICKS = 200')) {
    Has $coordinator $term 'local physical-radar plan selection and bounded reservation fallback'
}
foreach ($forbidden in @('CombatServices.contacts(', 'getAllEntities(', 'Sensor.scan(', 'new AbstractCannonProjectile',
        'CombatServices.neutralize(', 'EntityJoinLevelEvent', 'setDeltaMovement(', 'setLifetime(')) {
    Require (!$coordinator.Contains($forbidden)) "Coordination must not use global sensing, synthetic fire, predictive neutralization, or alter projectile physics: $forbidden"
}
Has $order 'unique.merge(candidate.id()' 'candidate IDs are deduplicated before assignment'
Has $order 'unique.remove(current)' 'valid current target receives continuity priority'
Has $policy 'comparingDouble(CiwsPolicy.Candidate::closestTime)' 'priority uses time to closest approach'
Has $policy 'thenComparingDouble(CiwsPolicy.Candidate::distanceSquared)' 'priority uses distance as secondary key'
Has $policy 'candidate.id().toString()' 'UUID breaks equal-priority ties deterministically'
Has $order 'candidate.id().equals(deferred)' 'temporarily ceded threat is skipped by that mount'
Has $order 'ordered.size() == maximum' 'ordered candidate list obeys caller budget'
Has $policy 'now - startedAt >= PREPARING_BUDGET_TICKS' 'elapsed preparation timeout uses guarded subtraction'
Has $policy 'retryUntil < 0 || now < retryUntil' 'malformed retry deadline remains deferred fail-closed'
Has $regression 'Long.MAX_VALUE - 40' 'preparation overflow regression at deadline'
Has $regression 'Long.MAX_VALUE - 39' 'preparation regression one tick before deadline'
Has $regression 'retryDeferred(x, -1, now)' 'negative retry deadline regression'

# Shared reservations are owned by stable mount identity and can only be released by that owner.
Has $reservation 'existing != null && !existing.owner().equals(owner)' 'ReservationBook rejects another mount without replacing its reservation'
Has $reservation 'existing != null && existing.owner().equals(owner)' 'release cannot remove a reservation owned by a different mount'
Has $reservation 'entries.values().removeIf(r -> r.until() < now)' 'expired reservations may be reused by a later mount'
Has $coordinator 'UUID.nameUUIDFromBytes(("cmi-ciws:" + level.dimension().location() + ":" + mount.getBlockPos().asLong())' 'reservation identity is stable per dimension and mount'
Has $coordinator 'CombatServices.release(level, new TargetId(TargetId.Kind.ENTITY, target), reservationIdentity(level, mount))' 'lock cleanup releases only the current mount reservation'
Has $tick 'data.putLong(HOLD, now + (long)Math.ceil(actual.time()) + 1)' 'flight hold remains tied to finite predicted arrival'
Has $tick 'Math.min(MAX_FLIGHT_TICKS + 2L' 'in-flight reservation refresh remains bounded by projectile lifetime'

# PREPARING is short, renewed only within a bounded interval, then yields the target briefly.
foreach ($term in @('PREPARING_RESERVATION_TTL_TICKS = 10', 'PREPARING_BUDGET_TICKS = 40', 'RETRY_COOLDOWN_TICKS = 10',
        'preparationExpired', 'retryDeferred', 'retryUntil')) { Has $policy $term 'bounded preparation lease and retry cooldown' }
Has $tick 'CiwsCoordinationPolicy.PREPARING_RESERVATION_TTL_TICKS' 'preparing reservations use the short renewal TTL'
Has $tick 'data.putLong(PREPARING_SINCE, now)' 'preparation start is recorded when a target assignment begins'
Has $tick 'data.putUUID(DEFERRED_TARGET, timedOut)' 'timeout cedes the current target without losing the other candidates'
Has $plans 'if (id.equals(deferredTarget)) continue;' 'deferred target is not repeatedly solved while the local retry window remains active'
Has $tick 'data.remove(PREPARING_TARGET); data.remove(PREPARING_SINCE)' 'successful native invocation ends the preparation phase'
Has $tick 'CiwsPolicy.observe(previous, selected)' 'lock samples stay associated with the selected UUID'
Has $tick 'data.remove(PREPARING_TARGET); data.remove(PREPARING_SINCE);' 'clearLock removes preparation state when ownership/track/solution is lost'
Has $tick 'data.putLong(HOLD, now + (long)Math.ceil(actual.time()) + 1)' 'flight state is kept separate from preparation timeout'

# Revalidate the same radar/IFF/corridor/reservation gates before native fire.
foreach ($term in @('CombatServices.resolve', 'CombatServices.relation', 'Relation.ENEMY',
        'CombatServices.canEngage', 'CombatServices.reserve(level, current.id(), identity',
        'CiwsPolicy.shouldFire', 'FIRE_PERMIT.set(new FirePermit(level, cannon))', 'WeaponAdapters.fire(level, cannon)')) {
    Has $tick $term 'final physical-track, IFF, safe-path, exclusive-reservation and one-use fire authorization'
}
Has $coordinator 'radar.getTracks()' 'candidate plans originate from this mount radar snapshot'
Has $plans 'projectileTracks(radar)' 'plan builder receives only its explicitly linked radar tracks'
Ordered $plans @('CiwsPolicy.eligible(', 'if (!solvedTracks.add(id)) continue;', 'authorizedTracks.add(id);', 'new FiringSolver(') 'duplicate physical tracks are dropped after authorization and before solver work'
Has $safePath 'TrajectorySafety.clear(' 'trajectory safety remains in the final corridor check'
Has $clearLock 'release(level, mount, data.getUUID(RESERVED))' 'lock cleanup releases only an owned preliminary reservation'
Has $clearLock 'data.remove(PREPARING_TARGET); data.remove(PREPARING_SINCE)' 'lock cleanup cancels preparation metadata'
Has $weapon 'cannon.fireShot(level, entity)' 'CMI dispatches only to the native fireShot route'
Has $weapon 'return true; // dispatched; the cannon itself may reject a shot' 'adapter return does not claim native projectile creation'
foreach ($type in @('riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption',
        'riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedRotarycannonContraption')) {
    $null = HasVoidFireShot $NeoWarfare $type
}
$null = HasVoidFireShot $CBC 'rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption'

# The 12 pure scenarios cover assignment, expiry, locks, IFF, sensor locality and limits.
foreach ($scenario in @('A:', 'B:', 'C:', 'D:', 'E:', 'F:', 'G:', 'H:', 'I:', 'J:', 'K:', 'L:')) {
    Has $regression $scenario 'coordination scenario regression'
}
Has $regression 'ReservationBook<TargetId>' 'coordination tests use real TargetId-keyed reservation semantics'
Has $regression 'reservation remains exclusive through its inclusive until tick' 'inclusive reservation deadline regression'
Has $regression 'another mount cannot release the reservation owner' 'reservation owner-only release regression'

# Inspect compiled output as well as source. No physical mods or game process are loaded.
$artifactCoordinator = Listing $Artifact 'local.cmi.weapons.CiwsCoordinator'
$artifactPolicy = Listing $Artifact 'local.cmi.weapons.CiwsCoordinationPolicy'
$artifactReservation = Listing $Artifact 'local.cmi.core.ReservationBook'
foreach ($term in @('CiwsCoordinationPolicy.orderedCandidates', 'CiwsCoordinationPolicy.preparationExpired',
        'CiwsCoordinationPolicy.retryUntil', 'CombatServices.reserve', 'CiwsPolicy.observe', 'TrajectorySafety.clear',
        'WeaponAdapters.fire', 'java/util/Set.add')) { Has $artifactCoordinator $term 'compiled local multi-CIWS decision path' }
foreach ($term in @('orderedCandidates', 'PREPARING_BUDGET_TICKS', 'RETRY_COOLDOWN_TICKS', 'PREPARING_RESERVATION_TTL_TICKS')) {
    Has $artifactPolicy $term 'compiled bounded coordination policy'
}
foreach ($term in @('reserve', 'release', 'java/util/Map.remove')) { Has $artifactReservation $term 'compiled owner-exclusive reservation book' }

"OK: radar-local fallback, deterministic order, bounded PREPARING handoff, protected flight holds, IFF/safety gates, native fire contract, compiled artifact and overflow boundaries verified; $dependencyEvidence. Static only; no runtime executed."
