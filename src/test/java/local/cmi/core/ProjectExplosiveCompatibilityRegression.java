package local.cmi.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import local.cmi.weapons.CiwsPolicy;

/** Deterministic, non-game regression for optional Project: Explosive recognition and denial policy. */
public final class ProjectExplosiveCompatibilityRegression {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Map<String, ProjectExplosiveCompatibility.Kind> futureCandidates = new LinkedHashMap<>();
        futureCandidates.put("projectexplosive:small_bomb", ProjectExplosiveCompatibility.Kind.SMALL_BOMB);
        futureCandidates.put("projectexplosive:big_bomb", ProjectExplosiveCompatibility.Kind.BIG_BOMB);
        futureCandidates.put("projectexplosive:fire_bomb", ProjectExplosiveCompatibility.Kind.FIRE_BOMB);
        futureCandidates.put("projectexplosive:nuclear_bomb", ProjectExplosiveCompatibility.Kind.NUCLEAR_BOMB);
        futureCandidates.put("projectexplosive:icbm", ProjectExplosiveCompatibility.Kind.ICBM);
        Map<String, ProjectExplosiveCompatibility.Kind> supportEntities = new LinkedHashMap<>();
        supportEntities.put("projectexplosive:icbm_booster", ProjectExplosiveCompatibility.Kind.ICBM_BOOSTER);
        supportEntities.put("projectexplosive:smoketrail", ProjectExplosiveCompatibility.Kind.SMOKETRAIL);
        supportEntities.put("projectexplosive:fire_trail", ProjectExplosiveCompatibility.Kind.FIRE_TRAIL);
        supportEntities.put("projectexplosive:multi_block", ProjectExplosiveCompatibility.Kind.MULTI_BLOCK);

        for (var entry : futureCandidates.entrySet()) {
            var classification = ProjectExplosiveCompatibility.classifyRegistryId(entry.getKey());
            check(classification.recognized(), entry.getKey() + " must be recognized exactly");
            check(classification.kind() == entry.getValue(), entry.getKey() + " maps to the expected kind");
            check(classification.role() == ProjectExplosiveCompatibility.Role.FUTURE_INTEROPERABILITY_CANDIDATE,
                    entry.getKey() + " is only a future interoperability candidate");
            check(!classification.acquirable(), entry.getKey() + " cannot be acquired by CIWS");
            check(!classification.neutralizable(), entry.getKey() + " cannot be neutralized");
        }
        for (var entry : supportEntities.entrySet()) {
            var classification = ProjectExplosiveCompatibility.classifyRegistryId(entry.getKey());
            check(classification.recognized(), entry.getKey() + " must be recognized exactly");
            check(classification.kind() == entry.getValue(), entry.getKey() + " maps to the expected kind");
            check(classification.excludedFromCiws(), entry.getKey() + " is excluded from CIWS");
            check(!classification.acquirable(), entry.getKey() + " cannot be acquired by CIWS");
            check(!classification.neutralizable(), entry.getKey() + " cannot be neutralized");
        }
        check(futureCandidates.size() + supportEntities.size() == 9, "catalog has exactly nine verified entity IDs");
        for (String unknown : new String[] { null, "", "projectexplosive:unknown", "projectexplosive:ICBM",
                "othermod:icbm", "projectexplosive", "projectexplosive:icbm:extra" }) {
            check(!ProjectExplosiveCompatibility.classifyRegistryId(unknown).recognized(), "unknown ID rejected: " + unknown);
        }
        check(ProjectExplosiveCompatibility.blocksAutomaticCiwsRegistryId("projectexplosive:unknown"),
                "unlisted PE IDs fail closed for CIWS");
        check(ProjectExplosiveCompatibility.blocksAutomaticCiwsRegistryId(null) &&
                ProjectExplosiveCompatibility.blocksAutomaticCiwsRegistryId("") &&
                ProjectExplosiveCompatibility.blocksAutomaticCiwsRegistryId("projectexplosive"),
                "absent or malformed entity IDs fail closed");
        check(ProjectExplosiveCompatibility.blocksAutomaticCiwsRegistryId("projectexplosive:icbm"),
                "known ICBM remains blocked for CIWS");
        check(!ProjectExplosiveCompatibility.blocksAutomaticCiwsRegistryId("othermod:icbm"),
                "another namespace is not misclassified as Project: Explosive");

        for (String id : new String[] { "projectexplosive:small_bomb", "projectexplosive:big_bomb",
                "projectexplosive:nuclear_bomb", "projectexplosive:icbm", "projectexplosive:unlisted_future_bomb" }) {
            check(!NeutralizationPolicy.allows(id, true),
                    "PE recognition/legacy projectile flag never grants neutralization: " + id);
        }
        check(NeutralizationPolicy.allows("createbigcannons:autocannon_bullet", true),
                "legacy CBC projectile neutralization remains authorized by its existing recognition contract");
        check(NeutralizationPolicy.allows("wariumce:radar_spear_missile_projectile", true),
                "legacy Warium projectile neutralization remains authorized by its existing recognition contract");
        check(!NeutralizationPolicy.allows("createbigcannons:autocannon_bullet", false),
                "unrecognized legacy projectile remains non-neutralizable");
        check(!NeutralizationPolicy.allows(null, true) && !NeutralizationPolicy.allows("broken-id", true),
                "malformed registry identity fails closed for neutralization");

        var ready = new ProjectExplosiveBombObservation.Evidence(ProjectExplosiveCompatibility.Kind.SMALL_BOMB,
                true, true, true, true, true, true, Relation.ENEMY, false);
        var futureCandidate = ProjectExplosiveBombObservation.assess(ready);
        check(futureCandidate.futureEligible(), "fresh physical Mk 82 with verified owner and ENEMY IFF is a future review candidate");
        check(!futureCandidate.acquisitionAuthorized() && !futureCandidate.neutralizationAuthorized(),
                "future-candidate observation never authorizes acquisition or neutralization in PE-02A");
        check(futureCandidate.rejection() == ProjectExplosiveBombObservation.Rejection.AWAITING_PE_02B_AUTHORIZATION,
                "otherwise eligible bomb is explicitly held for later neutralization review");
        var bigBomb = ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.BIG_BOMB, true, true, true, true, true, true, Relation.ENEMY, false));
        check(bigBomb.futureEligible() && !bigBomb.neutralizationAuthorized(), "Mk 84 follows the same inert observation contract");

        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.SMALL_BOMB, true, true, true, false, false, true, Relation.UNKNOWN, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.OWNER_UNKNOWN_OR_UNLOADED,
                "absent or unloaded Projectile owner rejects observation eligibility");
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.SMALL_BOMB, true, true, true, true, false, true, Relation.ENEMY, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.OWNER_UNKNOWN_OR_UNLOADED,
                "unresolved Projectile owner identity is rejected");
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.SMALL_BOMB, true, true, true, true, true, false, Relation.UNKNOWN, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.OBSERVER_OWNER_UNKNOWN,
                "unowned observer cannot evaluate a hostile identity");
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.SMALL_BOMB, true, true, true, true, true, true, Relation.FRIEND, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.FRIEND,
                "FRIEND prevails over an explicit hostile flag in observation policy");
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.SMALL_BOMB, true, true, true, true, true, true, Relation.NEUTRAL, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.NEUTRAL,
                "NEUTRAL cannot become an observation candidate");
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.SMALL_BOMB, true, true, true, true, true, true, Relation.UNKNOWN, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.UNKNOWN_IFF,
                "UNKNOWN cannot become an observation candidate");
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.SMALL_BOMB, false, true, true, true, true, true, Relation.ENEMY, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.TRACK_NOT_CURRENT,
                "stale or absent physical radar track rejects the candidate");
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.SMALL_BOMB, true, false, true, true, true, true, Relation.ENEMY, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.ENTITY_NOT_LOADED_OR_ALIVE,
                "unloaded entity cannot be correlated from a stale radar snapshot");
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.SMALL_BOMB, true, true, false, true, true, true, Relation.ENEMY, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.INVALID_TRACK_VECTOR,
                "non-finite radar kinematics reject the candidate");
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.NUCLEAR_BOMB, true, true, true, true, true, true, Relation.ENEMY, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.NOT_CONVENTIONAL_BOMB,
                "Fat Man/nuclear bombs remain outside the conventional candidate contract");
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.ICBM, true, true, true, true, true, true, Relation.ENEMY, false)).rejection()
                == ProjectExplosiveBombObservation.Rejection.NOT_CONVENTIONAL_BOMB,
                "ICBM remains excluded from conventional candidate contract");
        for (var kind : new ProjectExplosiveCompatibility.Kind[] { ProjectExplosiveCompatibility.Kind.ICBM_BOOSTER,
                ProjectExplosiveCompatibility.Kind.SMOKETRAIL, ProjectExplosiveCompatibility.Kind.FIRE_TRAIL,
                ProjectExplosiveCompatibility.Kind.MULTI_BLOCK, ProjectExplosiveCompatibility.Kind.FIRE_BOMB,
                ProjectExplosiveCompatibility.Kind.UNKNOWN }) {
            check(!ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                    kind, true, true, true, true, true, true, Relation.ENEMY, false)).futureEligible(),
                    "support, incendiary, or unknown PE kind stays excluded: " + kind);
        }
        check(ProjectExplosiveBombObservation.assess(new ProjectExplosiveBombObservation.Evidence(
                ProjectExplosiveCompatibility.Kind.SMALL_BOMB, true, true, true, true, true, true, Relation.ENEMY, true)).rejection()
                == ProjectExplosiveBombObservation.Rejection.PROTECTED,
                "protected target rejects future eligibility");

        UUID shooter = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000002");
        for (Relation relation : new Relation[] { Relation.FRIEND, Relation.NEUTRAL, Relation.UNKNOWN }) {
            check(!CiwsPolicy.eligible(true, true, shooter, owner, null, null, relation, true),
                    relation + " still cannot authorize fire");
        }
        check(!CiwsPolicy.eligible(true, true, shooter, null, null, null, Relation.ENEMY, true),
                "owner null still cannot authorize fire");
        check(CiwsPolicy.eligible(true, true, shooter, owner, null, null, Relation.ENEMY, true),
                "legacy generic ENEMY authorization policy remains unchanged");
        check(ShipOwnership.ownerRelation(shooter, owner, true, true) == Relation.FRIEND,
                "FRIEND remains stronger than explicit hostility in the existing IFF policy");

        String root = "src/main/java/local/cmi/";
        String compatibility = Files.readString(Path.of(root + "core/ProjectExplosiveCompatibility.java"));
        String observation = Files.readString(Path.of(root + "core/ProjectExplosiveBombObservation.java"));
        String neutralization = Files.readString(Path.of(root + "core/NeutralizationPolicy.java"));
        String coordinator = Files.readString(Path.of(root + "weapons/CiwsCoordinator.java"));
        String combat = Files.readString(Path.of(root + "core/CombatServices.java"));
        String interception = Files.readString(Path.of(root + "mixin/safety/CannonInterceptionMixin.java"));
        String integration = Files.readString(Path.of("src/main/java/local/cmi/CreateMilitaryIntegration.java"));
        String gradle = Files.readString(Path.of("build.gradle"));
        check(!gradle.matches("(?is).*\\b(?:implementation|api|compileOnly|runtimeOnly)\\s+[^\\r\\n]*projectexplosive.*"),
                "Project: Explosive is not a build dependency");
        check(!compatibility.contains("lil1llilol11") && !compatibility.contains("projectexplosive.") &&
                !compatibility.contains("Class.forName"), "adapter uses no PE classes or reflection");
        check(coordinator.contains("radar.getTracks()") && coordinator.contains("CombatServices.relation(level, actor, contact)") &&
                coordinator.contains("CombatServices.canEngage(level, actor, contact)"),
                "physical radar and regular identity/IFF authorization remain authoritative");
        check(!coordinator.contains("getAllEntities(") && !coordinator.contains("CombatServices.contacts(") &&
                !coordinator.contains("Sensor.scan("), "no global sensor was introduced");
        String plans = coordinator.substring(coordinator.indexOf("private static List<Plan> plans("),
                coordinator.indexOf("private static RadarSnapshot projectileTracks("));
        String held = coordinator.substring(coordinator.indexOf("private static boolean heldTargetStillValid("),
                coordinator.indexOf("private static boolean sensorAllows("));
        String fresh = coordinator.substring(coordinator.indexOf("private static boolean radarTrackStillCurrent("),
                coordinator.indexOf("private static CiwsMotionTracker.Observer motionObserver("));
        check(plans.contains("ProjectExplosiveCompatibility.isConventionalBomb(target)") &&
                plans.contains("ProjectExplosiveBombObservation.authorizeCiwsTrack") &&
                plans.contains("ProjectExplosiveCompatibility.blocksAutomaticCiws(target)"),
                "only exact conventional bombs enter the separately authorized radar plan path; other PE types reject before solving");
        check(held.contains("ProjectExplosiveCompatibility.isConventionalBomb(entity)") &&
                held.contains("ProjectExplosiveBombObservation.authorizeCiwsTrack") &&
                held.contains("ProjectExplosiveCompatibility.blocksAutomaticCiws(entity)"),
                "held/reserved PE targets require current conventional authorization; other PE types remain blocked");
        check(fresh.contains("ProjectExplosiveCompatibility.blocksAutomaticCiws(expected)"),
                "generic pre-fire fast path continues to reject all PE outside the narrow conventional branch");
        check(compatibility.contains("public boolean acquirable() { return false; }") &&
                compatibility.contains("public boolean neutralizable() { return false; }"),
                "recognition is distinct from acquisition and neutralization");
        check(!combat.contains("projectexplosive:") && combat.contains("AbstractCannonProjectile") &&
                combat.contains("wariumce:radar_spear_missile_projectile") && combat.contains("NeutralizationPolicy.allows(key.toString(),projectile(e))"),
                "legacy CBC/Warium recognition is preserved and destructive permission is separately centralized");
        check(combat.contains("!neutralizable(e)") && neutralization.contains("classifyRegistryId(registryId).neutralizable()"),
                "central neutralize path requires explicit policy beyond recognition and IFF");
        check(interception.contains("ProjectExplosiveCompatibility.isProjectExplosive(other)") &&
                interception.contains("ProjectExplosiveInterception.canHit(self,l,other)") &&
                interception.contains("ProjectExplosiveInterception.onPhysicalCbcHit(self,l,other)") &&
                interception.contains("CombatServices.neutralizable(other)") && interception.contains("CombatServices.neutralize"),
                "PE collisions use a separate narrow physical-hit policy; generic recognition cannot authorize them");
        check(integration.contains("ProjectExplosiveCompatibility.isProjectExplosive(target)") &&
                integration.contains("!CombatServices.neutralizable(target)"),
                "managed explosion path excludes PE from both neutralization and vanilla entity damage");
        check(observation.contains("radar.getTracks()") && observation.contains("level.getEntity(id)") &&
                !observation.contains("CombatServices.contacts(") && !observation.contains("getAllEntities(") &&
                !observation.contains("Sensor.scan("), "PE observations correlate only actual physical radar tracks");
        check(observation.contains("RadarScanningFilterAccess)(Object)scanning).cmi$scanProjectiles()") &&
                observation.contains("radar.isRunning()") && observation.contains("level.getBlockEntity(radar.getWorldPos()) != radar"),
                "observation requires an active, loaded Create: Radars block with projectile scanning enabled");
        check(observation.contains("new Decision(true, false, false, Rejection.AWAITING_PE_02B_AUTHORIZATION)") &&
                observation.contains("false, relation, decision.futureEligible(), decision.acquisitionAuthorized()"),
                "observation can mark a future review candidate without enabling acquisition or neutralization");
        System.out.println("OK: exact PE recognition remains separate from the narrow, revalidated Mk 82/Mk 84 interception policy");
    }
}
