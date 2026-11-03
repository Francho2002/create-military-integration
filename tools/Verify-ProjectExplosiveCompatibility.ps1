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
$coordinator = ReadSource 'src/main/java/local/cmi/weapons/CiwsCoordinator.java'
$combat = ReadSource 'src/main/java/local/cmi/core/CombatServices.java'
$interception = ReadSource 'src/main/java/local/cmi/mixin/safety/CannonInterceptionMixin.java'
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
    $combat.Contains('wariumce:radar_spear_missile_projectile')) 'legacy CBC/Warium projectile recognition is preserved; no PE whitelist expansion'
Check (!$interception.Contains('projectexplosive:') -and $interception.Contains('CombatServices.neutralize(')) 'collision neutralization hook has no PE-specific authority'
Check ($regression.Contains('owner null still cannot authorize fire') -and $regression.Contains('Relation.FRIEND') -and
    $regression.Contains('ICBM remains blocked for CIWS') -and $regression.Contains('projectexplosive:unknown')) 'regression covers identity, IFF and unknown PE IDs'

Add-Type -AssemblyName System.IO.Compression
$archive = [System.IO.Compression.ZipFile]::OpenRead($artifactPath)
try {
    $entries = @($archive.Entries | ForEach-Object FullName)
    Check ($entries -contains 'local/cmi/core/ProjectExplosiveCompatibility.class') 'compatibility classifier is in the compiled JAR'
    Check ($entries -contains 'local/cmi/weapons/CiwsCoordinator.class') 'guarded coordinator is in the compiled JAR'
    Check (@($entries | Where-Object { $_ -match '(^|/)projectexplosive/' }).Count -eq 0) 'JAR packages no Project: Explosive classes'
    Check (@($entries | Where-Object { $_ -match '\.jar$' }).Count -eq 0) 'JAR packages no dependency JARs'
} finally { $archive.Dispose() }
$adapterBytecode = Listing 'local.cmi.core.ProjectExplosiveCompatibility'
$coordinatorBytecode = Listing 'local.cmi.weapons.CiwsCoordinator'
foreach ($id in $ids) { Check ($adapterBytecode.Contains($id)) "compiled classifier contains $id" }
Check (($coordinatorBytecode.Split('ProjectExplosiveCompatibility.blocksAutomaticCiws').Count - 1) -ge 3) 'compiled CIWS bytecode contains acquisition, retention and fresh-track guards'
Check (!$adapterBytecode.Contains('projectexplosive/')) 'compiled classifier has no symbolic PE class reference'

Write-Host "Project Explosive compatibility verifier: $script:Passes passed; $($script:Failures.Count) failed."
if ($script:Failures.Count -gt 0) { throw ($script:Failures -join "`n") }
