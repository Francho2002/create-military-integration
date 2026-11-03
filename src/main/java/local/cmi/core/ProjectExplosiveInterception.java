package local.cmi.core;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import local.cmi.weapons.CiwsCoordinator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * One-shot bridge from a managed CBC shot to one exact, physically tracked Mk 82/Mk 84.
 * No target, world, radar, or cannon reference is retained past the synchronous firing scope.
 */
public final class ProjectExplosiveInterception {
    private ProjectExplosiveInterception() {}

    private static final int MAX_ACTIVE_PERMITS = 1024;
    private static final int MAX_FLIGHT_TICKS = 200;
    private static final WeakHashMap<AbstractCannonProjectile, ImpactPermit> IMPACT_PERMITS = new WeakHashMap<>();
    private static final ThreadLocal<PendingShot> PENDING = new ThreadLocal<>();

    private record ImpactPermit(ResourceLocation dimension, BlockPos mount, BlockPos radar, UUID cannon, UUID target,
            long scannedAt, long createdAt, long expiresAt, Actor actor) {}

    private static final class PendingShot {
        final ServerLevel level;
        final BlockPos mount;
        final BlockPos radar;
        final UUID cannon;
        final UUID target;
        final long scannedAt;
        final Actor actor;
        boolean captured;

        PendingShot(ServerLevel level, BlockPos mount, BlockPos radar, PitchOrientedContraptionEntity cannon,
                UUID target, long scannedAt, Actor actor) {
            this.level = level;
            this.mount = mount.immutable();
            this.radar = radar.immutable();
            this.cannon = cannon.getUUID();
            this.target = target;
            this.scannedAt = scannedAt;
            this.actor = actor;
        }
    }

    /** Called only around WeaponAdapters.fire for an already authorized PE candidate. */
    public static boolean fireWithPermit(ServerLevel level, BlockPos mount, BlockPos radar,
            PitchOrientedContraptionEntity cannon, Entity target, long scannedAt, Actor actor,
            long now, BooleanSupplier nativeFire) {
        if (level == null || mount == null || radar == null || cannon == null || target == null || actor == null ||
                actor.owner() == null || nativeFire == null || !CmiConfig.projectExplosiveConventionalInterceptionEnabled() ||
                !level.getServer().isSameThread() || level.getGameTime() != now || now < 0 ||
                level.getEntity(target.getUUID()) != target ||
                !CiwsCoordinator.projectExplosiveShotBindingCurrent(level, mount, radar, cannon, actor) ||
                ProjectExplosiveBombObservation.authorizeCiwsTargetFromRadar(level, actor, radar, target,
                        now, 1, scannedAt).isEmpty()) return false;

        PendingShot previous = PENDING.get();
        PendingShot pending = new PendingShot(level, mount, radar, cannon, target.getUUID(), scannedAt, actor);
        PENDING.set(pending);
        try { return nativeFire.getAsBoolean() && pending.captured; }
        finally { if (previous == null) PENDING.remove(); else PENDING.set(previous); }
    }

    /** Called from EntityJoinLevelEvent while ShotContext is still inside native CBC fireShot. */
    public static void captureManagedCannonShot(ServerLevel level, Entity entity, Actor contextActor) {
        PendingShot pending = PENDING.get();
        if (pending == null || pending.captured || level == null || entity == null || contextActor == null ||
                pending.level != level || !(entity instanceof AbstractCannonProjectile cannonProjectile) ||
                !ShotContext.managed() || !entity.getPersistentData().getBoolean("cmiManagedShot") ||
                !sameActor(pending.actor, contextActor) || !entity.isAlive() || entity.level() != level) return;
        long now = level.getGameTime();
        if (now < pending.scannedAt || now < 0 || now > Long.MAX_VALUE - MAX_FLIGHT_TICKS ||
                !(level.getEntity(pending.cannon) instanceof PitchOrientedContraptionEntity carrier) ||
                !CiwsCoordinator.projectExplosiveShotBindingCurrent(level, pending.mount, pending.radar,
                        carrier, pending.actor) ||
                ProjectExplosiveBombObservation.authorizeCiwsTargetFromRadar(level, pending.actor, pending.radar,
                        level.getEntity(pending.target), now, 1, pending.scannedAt).isEmpty()) return;

        prune(level.dimension().location(), now);
        if (IMPACT_PERMITS.size() >= MAX_ACTIVE_PERMITS) return;
        IMPACT_PERMITS.put(cannonProjectile, new ImpactPermit(level.dimension().location(), pending.mount,
                pending.radar, pending.cannon, pending.target, pending.scannedAt, now, now + MAX_FLIGHT_TICKS, pending.actor));
        pending.captured = true;
    }

    /** Narrow override for CBC's native Projectile exclusion; geometry remains CBC's swept AABB test. */
    public static boolean canHit(AbstractCannonProjectile interceptor, ServerLevel level, Entity target) {
        if (interceptor == null || level == null || target == null || !level.getServer().isSameThread()) return false;
        ImpactPermit permit = IMPACT_PERMITS.get(interceptor);
        return permit != null && validQuick(interceptor, level, target, permit);
    }

    /** Invoked only from CBC onHitEntity, after clipAndDamage has accepted its real AABB/segment hit. */
    public static boolean onPhysicalCbcHit(AbstractCannonProjectile interceptor, ServerLevel level, Entity target) {
        if (interceptor == null || level == null || target == null || !level.getServer().isSameThread()) return false;
        ImpactPermit permit = IMPACT_PERMITS.remove(interceptor); // consume before validation; no retry/double hit
        if (permit == null || !validQuick(interceptor, level, target, permit)) return false;
        long now = level.getGameTime();
        try {
            Entity cannonEntity = level.getEntity(permit.cannon());
            if (!(cannonEntity instanceof PitchOrientedContraptionEntity carrier) ||
                    !sameActor(permit.actor(), CombatServices.actor(level, interceptor)) ||
                    !CiwsCoordinator.projectExplosiveShotBindingCurrent(level, permit.mount(), permit.radar(),
                            carrier, permit.actor())) return false;
            var authorization = ProjectExplosiveBombObservation.authorizeCiwsTargetFromRadar(level, permit.actor(),
                    permit.radar(), target, now, 5, Long.MIN_VALUE).orElse(null);
            if (authorization == null || authorization.contact().owner() == null ||
                    !authorization.contact().owner().equals(projectileOwner(target)) ||
                    authorization.contact().id().uuid() == null || !authorization.contact().id().uuid().equals(permit.target()) ||
                    authorization.contact().seenTick() > now ||
                    authorization.trackScannedAt() < permit.scannedAt() ||
                    CombatServices.relation(level, permit.actor(), authorization.contact()) != Relation.ENEMY ||
                    !CombatServices.canEngage(level, permit.actor(), authorization.contact())) return false;

            String registryId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
            if (!NeutralizationPolicy.allowsConventionalBombNeutralization(registryId,
                    CmiConfig.projectExplosiveConventionalInterceptionEnabled(), true, target.isAlive(),
                    authorization.playerOwnerVerified(), authorization.metadataConsistent(), authorization.relation(),
                    authorization.protectedTarget(), authorization.detonationStateKnown(), authorization.impactOrDetonationStarted(),
                    interceptor.getPersistentData().getBoolean("cmiManagedShot"), true, true)) return false;

            // PE remove(DISCARDED) only performs its normal cleanup; no PE impact callback is invoked here.
            interceptor.getPersistentData().putBoolean("cmiPeImpactConsumed", true);
            target.discard();
            interceptor.discard();
            return true;
        } catch (RuntimeException | LinkageError unavailableContract) {
            return false;
        }
    }

    private static boolean validQuick(AbstractCannonProjectile interceptor, ServerLevel level, Entity target,
            ImpactPermit permit) {
        if (!CmiConfig.projectExplosiveConventionalInterceptionEnabled() || interceptor.level() != level ||
                !interceptor.isAlive() || !interceptor.getPersistentData().getBoolean("cmiManagedShot") ||
                !ProjectExplosiveCompatibility.isConventionalBomb(target) || !target.isAlive() || target.level() != level ||
                !permit.dimension().equals(level.dimension().location()) || !permit.target().equals(target.getUUID()) ||
                level.getEntity(target.getUUID()) != target) return false;
        long now = level.getGameTime();
        if (now < permit.createdAt() || now > permit.expiresAt()) return false;
        try {
            return target instanceof ProjectExplosiveBombState state && !state.cmi$impactOrDetonationStarted();
        } catch (RuntimeException | LinkageError unavailableState) {
            return false;
        }
    }

    private static UUID projectileOwner(Entity target) {
        return target instanceof net.minecraft.world.entity.projectile.Projectile projectile && projectile.getOwner() != null
                ? projectile.getOwner().getUUID() : null;
    }

    private static boolean sameActor(Actor first, Actor second) {
        return first != null && second != null && first.owner() != null && first.owner().equals(second.owner()) &&
                java.util.Objects.equals(first.platform(), second.platform()) &&
                java.util.Objects.equals(first.iff(), second.iff());
    }

    private static void prune(ResourceLocation dimension, long now) {
        Iterator<Map.Entry<AbstractCannonProjectile, ImpactPermit>> iterator = IMPACT_PERMITS.entrySet().iterator();
        while (iterator.hasNext()) {
            ImpactPermit permit = iterator.next().getValue();
            if (permit == null || permit.dimension().equals(dimension) &&
                    (now < permit.createdAt() || now > permit.expiresAt())) iterator.remove();
        }
    }

    public static void clearDimension(ResourceLocation dimension) {
        if (dimension == null) return;
        IMPACT_PERMITS.entrySet().removeIf(entry -> entry.getValue() == null || dimension.equals(entry.getValue().dimension()));
    }

    public static void clear() {
        IMPACT_PERMITS.clear();
        PENDING.remove();
    }
}
