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

        String root = "src/main/java/local/cmi/";
        String compatibility = Files.readString(Path.of(root + "core/ProjectExplosiveCompatibility.java"));
        String coordinator = Files.readString(Path.of(root + "weapons/CiwsCoordinator.java"));
        String combat = Files.readString(Path.of(root + "core/CombatServices.java"));
        String interception = Files.readString(Path.of(root + "mixin/safety/CannonInterceptionMixin.java"));
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
        check(plans.contains("ProjectExplosiveCompatibility.blocksAutomaticCiws(target)"),
                "PE type is rejected before plan/solver acquisition");
        check(held.contains("ProjectExplosiveCompatibility.blocksAutomaticCiws(entity)"),
                "held/reserved target revalidation rejects PE");
        check(fresh.contains("ProjectExplosiveCompatibility.blocksAutomaticCiws(expected)"),
                "pre-fire fresh-track validation rejects PE");
        check(compatibility.contains("public boolean acquirable() { return false; }") &&
                compatibility.contains("public boolean neutralizable() { return false; }"),
                "recognition is distinct from acquisition and neutralization");
        check(!combat.contains("projectexplosive:") && combat.contains("AbstractCannonProjectile") &&
                combat.contains("wariumce:radar_spear_missile_projectile"),
                "legacy CBC/Warium projectile recognition remains and PE was not added to the whitelist");
        check(interception.contains("CombatServices.projectile(other)") && interception.contains("CombatServices.neutralize") &&
                !interception.contains("projectexplosive:"), "real collision hook remains unchanged and PE is not neutralized by recognition");
        System.out.println("OK: exact PE registry recognition is optional and observational; all PE CIWS acquisition and neutralization remain blocked");
    }
}
