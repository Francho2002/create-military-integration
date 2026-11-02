[CmdletBinding()]
param(
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:Failures = [System.Collections.Generic.List[string]]::new()
$script:Passes = 0

function Check([bool]$Condition, [string]$Message) {
    if ($Condition) { $script:Passes++; Write-Host "PASS: $Message" }
    else { $script:Failures.Add($Message); Write-Host "FAIL: $Message" }
}
function ReadSource([string]$RelativePath) {
    $path = Join-Path $repo $RelativePath
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required source is missing: $path" }
    return Get-Content -Raw -LiteralPath $path
}
function SourceMethod([string]$Source, [string]$Name) {
    # Find a method declaration by name and balance braces instead of pinning line layout.
    $match = [regex]::Match($Source, "(?s)(?:public|private|protected)\s+(?:static\s+)?[^;{}]*\b$([regex]::Escape($Name))\s*\([^;{}]*\)\s*(?:throws\s+[^{}]+)?\s*\{")
    if (!$match.Success) { return $null }
    $open = $Source.IndexOf('{', $match.Index)
    $depth = 0
    for ($i = $open; $i -lt $Source.Length; $i++) {
        if ($Source[$i] -eq '{') { $depth++ }
        elseif ($Source[$i] -eq '}') {
            $depth--
            if ($depth -eq 0) { return $Source.Substring($match.Index, $i - $match.Index + 1) }
        }
    }
    return $null
}
function Before([string]$Text, [string]$First, [string]$Second) {
    $a = $Text.IndexOf($First, [StringComparison]::Ordinal)
    $b = $Text.IndexOf($Second, [StringComparison]::Ordinal)
    return $a -ge 0 -and $b -ge 0 -and $a -lt $b
}
function BlockAfter([string]$Text, [int]$Index) {
    if ($Index -lt 0) { return $null }
    $open = $Text.IndexOf('{', $Index)
    if ($open -lt 0) { return $null }
    $depth = 0
    for ($i = $open; $i -lt $Text.Length; $i++) {
        if ($Text[$i] -eq '{') { $depth++ }
        elseif ($Text[$i] -eq '}') {
            $depth--
            if ($depth -eq 0) { return $Text.Substring($open + 1, $i - $open - 1) }
        }
    }
    return $null
}
function Listing([string]$Jar, [string]$Type) {
    $lines = & javap -p -c -classpath $Jar $Type 2>&1
    if ($LASTEXITCODE -ne 0) { throw "javap failed for $Type : $($lines -join "`n")" }
    return $lines -join "`n"
}
function Has([string]$Text, [string]$Pattern) { return $Text -match $Pattern }

$coordinator = ReadSource 'src/main/java/local/cmi/weapons/CiwsCoordinator.java'
$policy = ReadSource 'src/main/java/local/cmi/weapons/CiwsCoordinationPolicy.java'
$ciwsPolicy = ReadSource 'src/main/java/local/cmi/weapons/CiwsPolicy.java'
$reservations = ReadSource 'src/main/java/local/cmi/core/CombatServices.java'
$regression = ReadSource 'src/test/java/local/cmi/weapons/CiwsCoordinationRegression.java'
$weapon = ReadSource 'src/main/java/local/cmi/weapons/WeaponAdapters.java'
$plans = SourceMethod $coordinator 'plans'
$tracks = SourceMethod $coordinator 'projectileTracks'
$tick = SourceMethod $coordinator 'tick'
$safePath = SourceMethod $coordinator 'safePath'
$selector = SourceMethod $policy 'orderedCandidates'

Check ($coordinator.Contains('radar.getTracks()') -and $tracks) 'track input comes from the linked physical IRadar track snapshot'
Check ($coordinator.Contains('MAX_TRACKS = 128') -and $tracks -and $tracks.Contains('withinBudget') -and $tracks.Contains('return null')) 'more than 128 physical tracks fails closed'
Check ($tracks -and $tracks.Contains('TrackCategory.PROJECTILE') -and $tracks.Contains('cmi$scanProjectiles') -and $tracks.Contains('return new RadarSnapshot')) 'physical projectile category and configured radar sensor filters remain required'
Check ($plans -and $plans.Contains('CiwsPolicy.projectileId') -and $plans.Contains('level.getEntity(id)') -and $plans.Contains('CombatServices.projectile(target)')) 'track identity is validated against UUID and live projectile entity before planning'
Check ($plans -and $plans.Contains('CombatServices.resolve') -and $plans.Contains('contact.owner()') -and $plans.Contains('CombatServices.relation') -and $plans.Contains('CombatServices.canEngage')) 'ownership, IFF relation and engagement authorization remain preconditions'
Check ($plans -and $plans.Contains('sensorAllows') -and $coordinator.Contains('CiwsPolicy.sensorAllows')) 'sensorAllows continues to enforce projectile/contraption scan configuration'
Check ($plans -and $plans.Contains('CiwsMotionPolicy.assess') -and $plans.Contains('CiwsMotionPolicy.atImpact')) 'temporal model assessment and impact-time confidence gate remain required'
Check ($plans -and $plans.Contains('CiwsPolicy.approaching') -and $plans.Contains('distance') -and $plans.Contains('tolerance')) 'approach, distance and target tolerance are validated before plan admission'
Check ($plans -and $plans.Contains('safePath(') -and $safePath -and $safePath.Contains('TrajectorySafety.clear')) 'safePath and trajectory corridor clearance remain required'
Check ($plans -and $plans.Contains('MOTION.observe') -and $plans.Contains('MOTION.retain')) 'motion history stays tied to the radar/mount observer and authorized physical tracks'
Check ($coordinator.Contains('CombatServices.reserve') -and $coordinator.Contains('CiwsPolicy.observe') -and $coordinator.Contains('CiwsMotionPolicy.atImpact') -and $coordinator.Contains('radarTrackStillCurrent') -and $coordinator.Contains('motionStillCurrent') -and $coordinator.Contains('safePath') -and $coordinator.Contains('WeaponAdapters.fire')) 'reservation, lock, current radar/IFF/motion revalidation, corridor and native fire gates remain in the chain'
Check (!$coordinator.Contains('CombatServices.contacts(') -and !$coordinator.Contains('getAllEntities(') -and !$coordinator.Contains('Sensor.scan(') -and !$coordinator.Contains('neutralize(')) 'no global sensor or predictive neutralization path is introduced'
Check ($weapon.Contains('cannon.fireShot(level, entity)') -and $weapon.Contains('dispatched; the cannon itself may reject a shot') -and $coordinator.Contains('not proof that a projectile spawned')) 'native fireShot dispatch is distinguished from confirmed projectile creation'
Check ($tick -and $tick.Contains('clearLock(level, mount)') -and $tick.Contains('MOTION.clearMount') -and $tick.Contains('binding.error()')) 'loss of radar/binding clears lock and motion state'
Check ($tick -and $tick.Contains('plans == null || plans.isEmpty()') -and $tick.Contains('clearLock(level, mount)')) 'loss of valid entity/owner/IFF/plan clears lock through empty candidate state'

# The selector contract is checked separately from the caller: existing target retention is valid only
# after eligibility/deduplication, then alternatives sort by TTC, distance, and UUID.
$priority = [regex]::Match($policy, '(?s)Comparator\s*<\s*CiwsPolicy\.Candidate\s*>\s+\w+\s*=\s*Comparator\s*\.comparingDouble\([^)]*closestTime[^)]*\)\s*\.thenComparingDouble\([^)]*distanceSquared[^)]*\)\s*\.thenComparing\([^;]*id\(\)\.toString\(\)')
Check ($priority.Success) 'candidate priority is deterministic: TTC ascending, distance ascending, UUID ascending'
Check ($selector -and $selector.Contains('unique.merge') -and $selector.Contains('current == null ? null : unique.remove(current)')) 'prior target is retained only if present in the already eligible unique candidate set'
Check ($selector -and $selector.Contains('ordered.size() == maximum') -and $selector.Contains('List.copyOf(ordered)')) 'candidate selector bounds result size without mutating caller state'
Check ($plans -and (Before $plans 'CiwsCoordinationPolicy.orderedCandidates' 'new FiringSolver(')) 'eligible candidates are capped and ordered before any solver construction'
$saturationGuard = if ($plans) { $plans.IndexOf('withinBudget(++eligible, MAX_CANDIDATES)', [StringComparison]::Ordinal) } else { -1 }
$saturationBody = BlockAfter $plans $saturationGuard
Check ($saturationGuard -lt 0 -or ($saturationBody -and !$saturationBody.Contains('return null') -and !$saturationBody.Contains('MOTION.clearMount'))) 'a ninth legitimate pre-solver candidate cannot abort prior candidates or clear lock/motion due to saturation; unrelated fail-closed cleanup remains allowed'
Check ($plans -and $plans.Contains('MAX_CANDIDATES') -and $plans.Contains('orderedCandidates')) 'solver workload is drawn from the capped candidate set (at most eight solve attempts)'
Check ($regression.Contains('ninth candidate is dropped') -and $regression.Contains('solver loop is bounded by deterministic preselection')) 'regression source covers ninth-candidate saturation and bounded solver work'
Check ($regression.Contains('current target is retained only while it remains among eligible candidates') -and $regression.Contains('an ineligible current target is not retained')) 'regression source covers retaining a previous target only while it remains eligible'

# Artifact checks run when a built candidate jar exists; a missing artifact is reported as a static-only limit.
if (Test-Path -LiteralPath $Artifact -PathType Leaf) {
    $artifactCoordinator = Listing $Artifact 'local.cmi.weapons.CiwsCoordinator'
    $artifactPolicy = Listing $Artifact 'local.cmi.weapons.CiwsCoordinationPolicy'
    Check ($artifactCoordinator.Contains('CiwsCoordinationPolicy.orderedCandidates') -and $artifactCoordinator.Contains('FiringSolver.solve')) 'compiled coordinator contains bounded selector and solver integration'
    Check ($artifactCoordinator.Contains('IRadar.getTracks') -and $artifactCoordinator.Contains('CiwsMotionPolicy.atImpact') -and $artifactCoordinator.Contains('TrajectorySafety.clear')) 'compiled coordinator preserves physical radar, temporal and safe-path gates'
    Check ($artifactPolicy.Contains('orderedCandidates') -and $artifactPolicy.Contains('closestTime') -and $artifactPolicy.Contains('distanceSquared')) 'compiled candidate policy exposes deterministic bounded ordering'
    $jarHash = (Get-FileHash -LiteralPath $Artifact -Algorithm SHA256).Hash
    Write-Host "ARTIFACT: $Artifact SHA256=$jarHash"
} else {
    Write-Host "LIMIT: compiled artifact not present; source assertions only ($Artifact)"
}

Write-Host "Static assertions passed: $script:Passes; failed: $($script:Failures.Count). No runtime is executed."
if ($script:Failures.Count -gt 0) {
    Write-Host 'Unverified/failed contract points:'
    foreach ($failure in $script:Failures) { Write-Host " - $failure" }
    exit 1
}
Write-Host 'OK: CIWS 2L candidate budget and safety gates verified statically.'
