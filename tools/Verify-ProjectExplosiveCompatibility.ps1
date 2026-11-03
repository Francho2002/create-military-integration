[CmdletBinding()]
param([string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar")
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$artifactPath = (Resolve-Path -LiteralPath $Artifact).Path
$script:Failures = [System.Collections.Generic.List[string]]::new()
$script:Passes = 0

function Check([bool]$Condition, [string]$Message) {
    if ($Condition) { $script:Passes++; Write-Host "PASS: $Message" }
    else { $script:Failures.Add($Message); Write-Host "FAIL: $Message" }
}
function ReadSource([string]$RelativePath) {
    $path = Join-Path $repo $RelativePath
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required source missing: $path" }
    return Get-Content -Raw -LiteralPath $path
}
function SourceMethod([string]$Source, [string]$Name) {
    $match = [regex]::Match($Source, "(?s)(?:public|private|protected)\s+(?:static\s+)?[^;{}]*\b$([regex]::Escape($Name))\s*\([^;{}]*\)\s*(?:throws\s+[^{}]+)?\s*\{")
    if (!$match.Success) { return $null }
    $open = $Source.IndexOf('{', $match.Index); $depth = 0
    for ($i = $open; $i -lt $Source.Length; $i++) {
        if ($Source[$i] -eq '{') { $depth++ }
        elseif ($Source[$i] -eq '}') { $depth--; if ($depth -eq 0) { return $Source.Substring($match.Index, $i - $match.Index + 1) } }
    }
    return $null
}
function Listing([string]$Type) {
    $lines = & javap -p -c -v -classpath $artifactPath $Type 2>&1
    if ($LASTEXITCODE -ne 0) { throw "javap failed for ${Type}: $($lines -join "`n")" }
    return $lines -join "`n"
}

$adapter = ReadSource 'src/main/java/local/cmi/core/ProjectExplosiveCompatibility.java'
$observation = ReadSource 'src/main/java/local/cmi/core/ProjectExplosiveBombObservation.java'
$neutralization = ReadSource 'src/main/java/local/cmi/core/NeutralizationPolicy.java'
$coordinator = ReadSource 'src/main/java/local/cmi/weapons/CiwsCoordinator.java'
$combat = ReadSource 'src/main/java/local/cmi/core/CombatServices.java'
$interception = ReadSource 'src/main/java/local/cmi/mixin/safety/CannonInterceptionMixin.java'
$integration = ReadSource 'src/main/java/local/cmi/CreateMilitaryIntegration.java'
$gradle = ReadSource 'build.gradle'
$hashes = ReadSource 'dependency-hashes.properties'
$regression = ReadSource 'src/test/java/local/cmi/core/ProjectExplosiveCompatibilityRegression.java'
$plans = SourceMethod $coordinator 'plans'
$held = SourceMethod $coordinator 'heldTargetStillValid'
$fresh = SourceMethod $coordinator 'radarTrackStillCurrent'
$ids = @(
    'projectexplosive:small_bomb', 'projectexplosive:big_bomb', 'projectexplosive:fire_bomb',
    'projectexplosive:nuclear_bomb', 'projectexplosive:icbm', 'projectexplosive:icbm_booster',
    'projectexplosive:smoketrail', 'projectexplosive:fire_trail', 'projectexplosive:multi_block'
)

Check ($ids.Count -eq 9) 'catalog enumerates nine exact registry IDs'
foreach ($id in $ids) { Check ([regex]::Matches($adapter, [regex]::Escape('"' + $id + '"')).Count -eq 1) "one centralized exact mapping for $id" }
Check ($adapter.Contains('BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())')) 'entity recognition resolves its registered EntityType ID'
Check ($adapter.Contains('classifyRegistryId(registryId).recognized() || blocksAutomaticCiwsRegistryId(registryId)')) 'resolved CIWS entities use exact catalog recognition plus namespace fail-closed'
Check ($adapter.Contains('case "projectexplosive:') -and $adapter.Contains('default -> UNKNOWN;')) 'exact switch and unknown fail-closed classification'
Check ($adapter.Contains('public boolean acquirable() { return false; }') -and $adapter.Contains('public boolean neutralizable() { return false; }')) 'recognition grants neither acquisition nor neutralization'
Check ($adapter.Contains('return "projectexplosive".equals(registryId.substring(0, separator));')) 'all IDs in the optional PE namespace are blocked, including unlisted future IDs'
Check ($adapter.Contains('if (registryId == null) return true;') -and
    $adapter.Contains('if (separator <= 0 || separator == registryId.length() - 1) return true;')) 'missing and malformed IDs fail closed'
Check (!$adapter.Contains('lil1llilol11') -and !$adapter.Contains('Class.forName') -and !$adapter.Contains('projectexplosive.')) 'adapter has no PE Java dependency or reflection'
Check ($gradle -notmatch '(?is)\b(?:implementation|api|compileOnly|runtimeOnly)\s+[^\r\n]*projectexplosive' -and
    !$hashes.ToLowerInvariant().Contains('projectexplosive')) 'no required PE dependency or hash pin was added'
Check ($null -ne $plans -and $plans.Contains('ProjectExplosiveCompatibility.blocksAutomaticCiws(target)') -and
    $plans.IndexOf('ProjectExplosiveCompatibility.blocksAutomaticCiws(target)') -lt $plans.IndexOf('FiringSolver solver =')) 'acquisition rejects PE before candidate solving'
Check ($null -ne $held -and $held.Contains('ProjectExplosiveCompatibility.blocksAutomaticCiws(entity)')) 'retained reservation/lock revalidation rejects PE'
Check ($null -ne $fresh -and $fresh.Contains('ProjectExplosiveCompatibility.blocksAutomaticCiws(expected)')) 'pre-fire physical track revalidation rejects PE'
Check ($coordinator.Contains('radar.getTracks()') -and !$coordinator.Contains('CombatServices.contacts(') -and
    !$coordinator.Contains('getAllEntities(') -and !$coordinator.Contains('Sensor.scan(')) 'CIWS sensor remains the linked physical radar only'
Check ($coordinator.Contains('CombatServices.relation(level, actor, contact)') -and $coordinator.Contains('CombatServices.canEngage(level, actor, contact)') -and
    $coordinator.Contains('TrajectorySafety.clear(') -and $coordinator.Contains('CombatServices.reserve(') -and
    $coordinator.Contains('WeaponAdapters.fire(')) 'IFF, trajectory, reservation and native-fire gates remain present'
Check (!$combat.Contains('projectexplosive:') -and $combat.Contains('AbstractCannonProjectile') -and
    $combat.Contains('wariumce:radar_spear_missile_projectile') -and $combat.Contains('NeutralizationPolicy.allows(key.toString(),projectile(e))')) 'legacy CBC/Warium recognition is preserved and destructive permission is separately centralized'
Check ($neutralization.Contains('classifyRegistryId(registryId).neutralizable()') -and $combat.Contains('!neutralizable(e)')) 'CombatServices.neutralize requires the independent neutralization policy'
Check ($interception.Contains('ProjectExplosiveCompatibility.isProjectExplosive(other)') -and
    $interception.Contains('CombatServices.neutralizable(other)') -and $interception.Contains('CombatServices.neutralize(')) 'managed physical collision cannot bypass PE neutralization denial'
Check ($integration.Contains('ProjectExplosiveCompatibility.isProjectExplosive(target)') -and
    $integration.Contains('!CombatServices.neutralizable(target)')) 'managed explosion excludes PE from neutralization and vanilla entity damage'
Check ($observation.Contains('radar.getTracks()') -and $observation.Contains('level.getEntity(id)') -and
    !$observation.Contains('CombatServices.contacts(') -and !$observation.Contains('getAllEntities(') -and
    !$observation.Contains('Sensor.scan(')) 'read-only PE adapter correlates only physical radar tracks'
Check ($observation.Contains('RadarScanningFilterAccess)(Object)scanning).cmi$scanProjectiles()') -and
    $observation.Contains('radar.isRunning()') -and $observation.Contains('level.getBlockEntity(radar.getWorldPos()) != radar')) 'adapter requires active loaded physical radar and scanProjectiles filter'
Check ($observation.Contains('new Decision(true, false, false, Rejection.AWAITING_PE_02B_AUTHORIZATION)') -and
    $observation.Contains('decision.acquisitionAuthorized()') -and $observation.Contains('decision.neutralizationAuthorized()')) 'future eligibility never becomes acquisition or destructive permission'
Check ($regression.Contains('owner null still cannot authorize fire') -and $regression.Contains('Relation.FRIEND') -and
    $regression.Contains('ICBM remains blocked for CIWS') -and $regression.Contains('projectexplosive:unknown')) 'regression covers identity, IFF and unknown PE IDs'

Add-Type -AssemblyName System.IO.Compression
$archive = [System.IO.Compression.ZipFile]::OpenRead($artifactPath)
try {
    $entries = @($archive.Entries | ForEach-Object FullName)
    Check ($entries -contains 'local/cmi/core/ProjectExplosiveCompatibility.class') 'compatibility classifier is in the compiled JAR'
    Check ($entries -contains 'local/cmi/core/NeutralizationPolicy.class') 'central neutralization policy is in the compiled JAR'
    Check ($entries -contains 'local/cmi/core/ProjectExplosiveBombObservation.class') 'read-only bomb observation adapter is in the compiled JAR'
    Check ($entries -contains 'local/cmi/weapons/CiwsCoordinator.class') 'guarded coordinator is in the compiled JAR'
    Check (@($entries | Where-Object { $_ -match '(^|/)projectexplosive/' }).Count -eq 0) 'JAR packages no Project: Explosive classes'
    Check (@($entries | Where-Object { $_ -match '\.jar$' }).Count -eq 0) 'JAR packages no dependency JARs'
} finally { $archive.Dispose() }
$adapterBytecode = Listing 'local.cmi.core.ProjectExplosiveCompatibility'
$neutralizationBytecode = Listing 'local.cmi.core.NeutralizationPolicy'
$observationBytecode = Listing 'local.cmi.core.ProjectExplosiveBombObservation'
$combatBytecode = Listing 'local.cmi.core.CombatServices'
$interceptionBytecode = Listing 'local.cmi.mixin.safety.CannonInterceptionMixin'
$integrationBytecode = Listing 'local.cmi.CreateMilitaryIntegration'
$coordinatorBytecode = Listing 'local.cmi.weapons.CiwsCoordinator'
foreach ($id in $ids) { Check ($adapterBytecode.Contains($id)) "compiled classifier contains $id" }
Check (($coordinatorBytecode.Split('ProjectExplosiveCompatibility.blocksAutomaticCiws').Count - 1) -ge 3) 'compiled CIWS bytecode contains acquisition, retention and fresh-track guards'
Check (!$adapterBytecode.Contains('projectexplosive/')) 'compiled classifier has no symbolic PE class reference'
Check ($neutralizationBytecode.Contains('ProjectExplosiveCompatibility.classifyRegistryId') -and
    $neutralizationBytecode.Contains('Classification.neutralizable')) 'compiled central policy consults explicit PE neutralization permission'
Check ($combatBytecode.Contains('CombatServices.neutralizable') -and $combatBytecode.Contains('NeutralizationPolicy.allows')) 'compiled CombatServices neutralize path uses separate policy'
Check ($interceptionBytecode.Contains('ProjectExplosiveCompatibility.isProjectExplosive') -and
    $interceptionBytecode.Contains('CombatServices.neutralizable')) 'compiled collision mixin contains explicit PE deny and central gate'
Check ($integrationBytecode.Contains('ProjectExplosiveCompatibility.isProjectExplosive') -and
    $integrationBytecode.Contains('CombatServices.neutralizable')) 'compiled explosion handler denies PE damage and uses central gate'
Check ($observationBytecode.Contains('IRadar.getTracks') -and $observationBytecode.Contains('ServerLevel.getEntity') -and
    !$observationBytecode.Contains('CombatServices.contacts') -and !$observationBytecode.Contains('ServerLevel.getAllEntities')) 'compiled observation path consumes physical radar snapshot and resolves only track UUIDs'

Write-Host "Project Explosive compatibility verifier: $script:Passes passed; $($script:Failures.Count) failed."
if ($script:Failures.Count -gt 0) { throw ($script:Failures -join "`n") }
