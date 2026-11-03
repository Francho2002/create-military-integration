package local.cmi.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.happysg.radar.block.radar.track.TrackCategory;
import net.minecraft.world.phys.Vec3;

/** Deterministic policy and source-contract regression; it does not emulate a game collision. */
public final class ProjectExplosiveInterceptionRegression {
    private ProjectExplosiveInterceptionRegression() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean acquisition(String id, boolean enabled, boolean track, boolean live,
            boolean playerOwner, boolean metadata, Relation relation, boolean protectedTarget,
            boolean stateKnown, boolean impactOrExplosionStarted) {
        return NeutralizationPolicy.allowsConventionalBombAcquisition(id, enabled, track, live,
                playerOwner, metadata, relation, protectedTarget, stateKnown, impactOrExplosionStarted);
    }

    private static boolean neutralization(String id, boolean enabled, boolean track, boolean live,
            boolean playerOwner, boolean metadata, Relation relation, boolean protectedTarget,
            boolean stateKnown, boolean impactOrExplosionStarted, boolean managedShot,
            boolean physicalCollision, boolean permitConsumed) {
        return NeutralizationPolicy.allowsConventionalBombNeutralization(id, enabled, track, live,
                playerOwner, metadata, relation, protectedTarget, stateKnown,
                impactOrExplosionStarted, managedShot, physicalCollision, permitConsumed);
    }

    public static void main(String[] args) throws Exception {
        String[] conventional = { "projectexplosive:small_bomb", "projectexplosive:big_bomb" };
        String[] forbidden = { "projectexplosive:fire_bomb", "projectexplosive:nuclear_bomb",
                "projectexplosive:icbm", "projectexplosive:icbm_booster", "projectexplosive:smoketrail",
                "projectexplosive:fire_trail", "projectexplosive:multi_block", "projectexplosive:unknown",
                "othermod:small_bomb", null, "broken-id" };

        for (String id : conventional) {
            check(ProjectExplosiveCompatibility.classifyRegistryId(id).recognized(), id + " is recognized");
            check(!ProjectExplosiveCompatibility.classifyRegistryId(id).acquirable() &&
                    !ProjectExplosiveCompatibility.classifyRegistryId(id).neutralizable(),
                    "registry recognition does not grant generic acquisition or neutralization: " + id);
            check(acquisition(id, true, true, true, true, true, Relation.ENEMY, false, true, false),
                    "only fully satisfied current physical-track/IFF/pre-impact gates admit candidate: " + id);

            check(!acquisition(id, false, true, true, true, true, Relation.ENEMY, false, true, false), "opt-in OFF rejects " + id);
            check(!acquisition(id, true, false, true, true, true, Relation.ENEMY, false, true, false), "missing or stale radar track rejects " + id);
            check(!acquisition(id, true, true, false, true, true, Relation.ENEMY, false, true, false), "dead/unloaded entity rejects " + id);
            check(!acquisition(id, true, true, true, false, true, Relation.ENEMY, false, true, false), "missing/non-player/unloaded owner rejects " + id);
            check(!acquisition(id, true, true, true, true, false, Relation.ENEMY, false, true, false), "contradictory CMI metadata rejects " + id);
            check(!acquisition(id, true, true, true, true, true, Relation.FRIEND, false, true, false), "FRIEND rejects " + id);
            check(!acquisition(id, true, true, true, true, true, Relation.NEUTRAL, false, true, false), "NEUTRAL rejects " + id);
            check(!acquisition(id, true, true, true, true, true, Relation.UNKNOWN, false, true, false), "UNKNOWN rejects " + id);
            check(!acquisition(id, true, true, true, true, true, Relation.ENEMY, true, true, false), "protected target rejects " + id);
            check(!acquisition(id, true, true, true, true, true, Relation.ENEMY, false, false, false), "unreadable PE state rejects " + id);
            check(!acquisition(id, true, true, true, true, true, Relation.ENEMY, false, true, true), "impact-pending or detonating bomb rejects " + id);

            check(neutralization(id, true, true, true, true, true, Relation.ENEMY, false, true, false,
                    true, true, true), "authorized destruction still needs every acquisition gate plus CBC evidence: " + id);
            check(!neutralization(id, true, true, true, true, true, Relation.ENEMY, false, true, false,
                    false, true, true), "unmanaged interceptor cannot neutralize " + id);
            check(!neutralization(id, true, true, true, true, true, Relation.ENEMY, false, true, false,
                    true, false, true), "prediction without physical collision cannot neutralize " + id);
            check(!neutralization(id, true, true, true, true, true, Relation.ENEMY, false, true, false,
                    true, true, false), "unconsumed/missing one-use permit cannot neutralize " + id);
            check(!neutralization(id, true, true, true, true, true, Relation.ENEMY, false, true, true,
                    true, true, true), "detonation or accepted impact state blocks callback destruction " + id);
        }

        for (String id : forbidden) {
            check(!acquisition(id, true, true, true, true, true, Relation.ENEMY, false, true, false),
                    "non-conventional, unknown or malformed type cannot be acquired: " + id);
            check(!neutralization(id, true, true, true, true, true, Relation.ENEMY, false, true, false,
                    true, true, true), "non-conventional, unknown or malformed type cannot be neutralized: " + id);
        }
        for (String id : new String[] { "projectexplosive:small_bomb", "projectexplosive:big_bomb",
                "projectexplosive:fire_bomb", "projectexplosive:nuclear_bomb", "projectexplosive:icbm",
                "projectexplosive:unknown" }) {
            check(!NeutralizationPolicy.allows(id, true), "generic destructive allowlist remains closed to PE: " + id);
        }
        check(!NeutralizationPolicy.allows(null, true) && !NeutralizationPolicy.allows("broken-id", true),
                "missing and malformed generic registry identities remain blocked");
        check(NeutralizationPolicy.allows("createbigcannons:autocannon_bullet", true), "CBC legacy policy unchanged");
        check(NeutralizationPolicy.allows("wariumce:radar_spear_missile_projectile", true), "Warium legacy policy unchanged");

        var expectedTrack = new ProjectExplosiveBombObservation.TrackIdentity("00000000-0000-0000-0000-000000000082",
                TrackCategory.PROJECTILE, 120L, new Vec3(10, 20, 30), new Vec3(1, -2, 3));
        check(ProjectExplosiveBombObservation.unambiguousTrackIdentities(List.of(expectedTrack), expectedTrack),
                "single exact physical observation is unambiguous");
        check(ProjectExplosiveBombObservation.unambiguousTrackIdentities(List.of(expectedTrack,
                        new ProjectExplosiveBombObservation.TrackIdentity(expectedTrack.id(), TrackCategory.PROJECTILE,
                                120L, new Vec3(10, 20, 30), new Vec3(1, -2, 3))), expectedTrack),
                "byte-for-byte equivalent duplicate observations for one UUID are harmless");
        check(!ProjectExplosiveBombObservation.unambiguousTrackIdentities(List.of(expectedTrack,
                        new ProjectExplosiveBombObservation.TrackIdentity(expectedTrack.id(), TrackCategory.PROJECTILE,
                                121L, expectedTrack.position(), expectedTrack.velocity())), expectedTrack),
                "contradictory duplicate timestamp for one UUID is rejected");
        check(!ProjectExplosiveBombObservation.unambiguousTrackIdentities(List.of(expectedTrack,
                        new ProjectExplosiveBombObservation.TrackIdentity(expectedTrack.id(), TrackCategory.PROJECTILE,
                                120L, new Vec3(11, 20, 30), expectedTrack.velocity())), expectedTrack),
                "contradictory duplicate position for one UUID is rejected");
        check(!ProjectExplosiveBombObservation.unambiguousTrackIdentities(List.of(expectedTrack,
                        new ProjectExplosiveBombObservation.TrackIdentity(expectedTrack.id(), TrackCategory.PROJECTILE,
                                120L, expectedTrack.position(), new Vec3(1, -3, 3))), expectedTrack),
                "contradictory duplicate velocity for one UUID is rejected");
        check(!ProjectExplosiveBombObservation.unambiguousTrackIdentities(List.of(expectedTrack,
                        new ProjectExplosiveBombObservation.TrackIdentity(expectedTrack.id(), TrackCategory.SABLE,
                                120L, expectedTrack.position(), expectedTrack.velocity())), expectedTrack),
                "contradictory duplicate category for one UUID is rejected");

        String sourceRoot = "src/main/java/local/cmi/";
        String config = Files.readString(Path.of(sourceRoot + "core/CmiConfig.java"));
        String observation = Files.readString(Path.of(sourceRoot + "core/ProjectExplosiveBombObservation.java"));
        String interceptor = Files.readString(Path.of(sourceRoot + "core/ProjectExplosiveInterception.java"));
        String smallState = Files.readString(Path.of(sourceRoot + "mixin/safety/ProjectExplosiveSmallBombStateMixin.java"));
        String bigState = Files.readString(Path.of(sourceRoot + "mixin/safety/ProjectExplosiveBigBombStateMixin.java"));
        String hook = Files.readString(Path.of(sourceRoot + "mixin/safety/CannonInterceptionMixin.java"));
        String coordinator = Files.readString(Path.of(sourceRoot + "weapons/CiwsCoordinator.java"));
        String combat = Files.readString(Path.of(sourceRoot + "core/CombatServices.java"));
        String held = coordinator.substring(coordinator.indexOf("private static boolean heldTargetStillValid("),
                coordinator.indexOf("private static boolean sensorAllows("));
        String radarRevalidation = observation.substring(observation.indexOf("public static java.util.Optional<Authorization> authorizeCiwsTargetFromRadar("),
                observation.indexOf("/** Conflicting observations for one UUID"));

        check(config.contains("define(\"projectExplosiveConventionalBombInterception\", false)"), "experimental configuration defaults OFF");
        check(config.contains("ModList.get().isLoaded(ProjectExplosiveCompatibility.MOD_ID)"), "opt-in is inert when PE is absent");
        check(observation.contains("IRadar radar") && observation.contains("radar.getTracks()") &&
                observation.contains("TrackCategory.PROJECTILE") && observation.contains("level.getEntity(entity.getUUID())"),
                "candidate originates in a physical PROJECTILE track and resolves the same live UUID");
        check(observation.contains("unambiguousPhysicalTrack") && observation.contains("unambiguousTrackIdentities") &&
                radarRevalidation.contains("sameTrackObservation(matched, track)") &&
                radarRevalidation.indexOf("sameTrackObservation(matched, track)") < radarRevalidation.indexOf("requiredScannedAt != matched.scannedTime()"),
                "conflicting same-UUID observations are rejected before timestamp selection at fire and impact revalidation");
        check(held.contains("unambiguousPhysicalTrack") &&
                held.indexOf("unambiguousPhysicalTrack") < held.indexOf("authorizeCiwsTrack"),
                "held PE locks revalidate physical-track ambiguity before retaining the lock");
        check(observation.contains("ownerEntity instanceof Player") &&
                observation.contains("level.getEntity(ownerEntity.getUUID()) != ownerEntity") &&
                observation.contains("cmi$impactOrDetonationStarted()") && observation.contains("metadataConsistent"),
                "candidate requires current player owner, CMI metadata consistency, and readable pre-impact state");
        check(observation.matches("(?s).*new Authorization\\(ciwsContact, true, false, metadataConsistent, relation,\\s+protectedTarget, true, impactOrDetonationStarted, track.scannedTime\\(\\), null\\).*"),
                "resolved player identity is not misrepresented as a verified CMI launch context");
        check(smallState.contains("@Accessor(\"l11oo0i1illii0o0liol1o1i\")") &&
                smallState.contains("@Invoker(\"ll1ol00o111oioool0l011lo\")"), "Mk 82 pending-impact and HasExploded state use exact optional accessors");
        check(bigState.contains("@Accessor(\"lii11l1iil010100loiio0li\")") &&
                bigState.contains("@Invoker(\"ll1ol00o111oioool0l011lo\")"), "Mk 84 pending-impact and HasExploded state use exact optional accessors");
        check(interceptor.contains("pending.captured") && interceptor.contains("pending.ambiguous") &&
                interceptor.contains("pending.cannonProjectileSpawns++") &&
                interceptor.contains("ProjectExplosivePermitPolicy.uniqueCbcSpawn(pending.cannonProjectileSpawns)") &&
                interceptor.indexOf("pending.cannonProjectileSpawns++") < interceptor.indexOf("ShotContext.managed()") &&
                interceptor.contains("level.getEntity(captured.getUUID()) == captured") &&
                interceptor.contains("IMPACT_PERMITS.remove(pending.capturedProjectile)"),
                "every CBC spawn is counted before eligibility; only one accepted live managed spawn retains a permit");
        check(interceptor.contains("target.discard()") && interceptor.contains("interceptor.discard()") &&
                interceptor.contains("authorizeCiwsTargetFromRadar") && interceptor.contains("allowsConventionalBombNeutralization"),
                "real CBC callback revalidates radar/IFF/state before discarding both entities");
        check(interceptor.contains("IMPACT_PERMITS.remove(interceptor)") &&
                interceptor.contains("MAX_ACTIVE_PERMITS = 1024") && interceptor.contains("MAX_FLIGHT_TICKS = 200"),
                "impact permit is one-use, bounded, and expires within the existing projectile lifetime budget");
        String permitPolicy = Files.readString(Path.of(sourceRoot + "core/ProjectExplosivePermitPolicy.java"));
        String integration = Files.readString(Path.of("src/main/java/local/cmi/CreateMilitaryIntegration.java"));
        check(interceptor.contains("ProjectExplosivePermitPolicy.Identity") &&
                interceptor.contains("authorization.contact().owner()") && interceptor.contains("physicalRadar.instance()") &&
                interceptor.contains("authorization.contact().platform()") && interceptor.contains("permit.identity"),
                "permit binds exact target owner/platform, physical radar instance, cannon, actor and dimension as immutable values");
        check(interceptor.contains("currentAuthorization") && interceptor.contains("projectileOwner(target)") &&
                interceptor.contains("authorization.relation() != Relation.ENEMY") &&
                interceptor.contains("projectExplosiveShotBindingCurrent"),
                "owner, IFF, radar, mount, cannon and actor are revalidated before filter and physical impact");
        check(permitPolicy.contains("issued.equals(current)") && permitPolicy.contains("expiresAt >= issuedAt") &&
                permitPolicy.contains("now <= expiresAt") && permitPolicy.contains("class OneShot") &&
                interceptor.contains("permit.use.consume()"),
                "identity equality, monotonic expiry and single-use callback are enforced by pure policy and runtime path");
        check(interceptor.contains("clearChunk(ResourceLocation dimension, ChunkPos chunk)") &&
                interceptor.contains("cannonPosition") && integration.contains("ProjectExplosiveInterception.clearChunk"),
                "dimension/chunk unload clears permits for radar, mount, cannon, target and interceptor");
        check(integration.contains("ModConfigEvent.Reloading") && integration.contains("ModConfigEvent.Unloading") &&
                integration.contains("ProjectExplosiveInterception.clear()"),
                "server config reload or unload permanently invalidates in-flight permits");
        check(interceptor.contains("ThreadLocal<PendingShot>") && interceptor.contains("ShotContext.managed()") &&
                interceptor.contains("sameActor(pending.actor, contextActor)"),
                "permit capture is scoped to managed server-thread CBC spawn with the exact actor");
        check(hook.contains("ProjectExplosiveInterception.canHit(self,l,other)") &&
                hook.contains("ProjectExplosiveInterception.onPhysicalCbcHit(self,l,other)") &&
                hook.contains("cmiPeImpactConsumed"), "CBC filter is narrow and later collected callbacks are suppressed after one successful PE hit");
        check(hook.contains("if(!self.getPersistentData().getBoolean(\"cmiManagedShot\"))return;\n            cir.setReturnValue(ProjectExplosiveInterception.canHit") &&
                hook.contains("if(!self.getPersistentData().getBoolean(\"cmiManagedShot\"))return;\n            cir.setReturnValue(ProjectExplosiveInterception.onPhysicalCbcHit"),
                "unmanaged CBC shots retain native PE filtering and callback behavior");
        check(coordinator.contains("ProjectExplosiveBombObservation.authorizeCiwsTrack") &&
                coordinator.contains("ProjectExplosiveInterception.fireWithPermit") &&
                coordinator.contains("CiwsPolicy.shouldFire(lock, lastFire)"),
                "PE enters only the existing physical-radar, lock, solver, safety and native fire path");
        check(!combat.contains("projectexplosive:") && !combat.contains("isConventionalBomb("),
                "CombatServices projectile and generic neutralization allowlists do not acquire PE");
        check(!coordinator.contains("getAllEntities(") && !coordinator.contains("CombatServices.contacts(") &&
                !coordinator.contains("Sensor.scan("), "no alternate or global CIWS sensor is introduced");

        System.out.println("OK: Mk 82/Mk 84 require opt-in, unambiguous live physical radar, real hostile Player owner, clear metadata, readable pre-impact state, and one actual CBC collision; all other PE types and generic neutralization remain blocked");
    }
}
