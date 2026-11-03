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
import net.minecraft.world.level.ChunkPos;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** One-shot bridge from one managed CBC shot to one exact, physically tracked Mk 82/Mk 84. */
public final class ProjectExplosiveInterception {
    private ProjectExplosiveInterception() {}

    private static final int MAX_ACTIVE_PERMITS = 1024;
    private static final int MAX_FLIGHT_TICKS = 200;
    private static final Map<AbstractCannonProjectile, ImpactPermit> IMPACT_PERMITS = new WeakHashMap<>();
    private static final ThreadLocal<PendingShot> PENDING = new ThreadLocal<>();

    private static final class ImpactPermit {
        final ProjectExplosivePermitPolicy.Identity identity;
        final long scannedAt;
        final long createdAt;
        final long expiresAt;
        final ProjectExplosivePermitPolicy.OneShot use = new ProjectExplosivePermitPolicy.OneShot();
        BlockPos targetPosition;
        BlockPos interceptorPosition;
        BlockPos cannonPosition;

        ImpactPermit(ProjectExplosivePermitPolicy.Identity identity, long scannedAt, long createdAt,
                long expiresAt, BlockPos targetPosition, BlockPos interceptorPosition, BlockPos cannonPosition) {
            this.identity = identity;
            this.scannedAt = scannedAt;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.targetPosition = targetPosition.immutable();
            this.interceptorPosition = interceptorPosition.immutable();
            this.cannonPosition = cannonPosition.immutable();
        }
    }

    /** Temporary references exist only inside the synchronous native fire call. */
    private static final class PendingShot {
        final ServerLevel level;
        final BlockPos mount;
        final BlockPos radar;
        final UUID cannon;
        final Entity target;
        final long scannedAt;
        final Actor actor;
        final ProjectExplosivePermitPolicy.Identity identity;
        int cannonProjectileSpawns;
        boolean captured;
        boolean ambiguous;
        AbstractCannonProjectile capturedProjectile;

        PendingShot(ServerLevel level, BlockPos mount, BlockPos radar, PitchOrientedContraptionEntity cannon,
                Entity target, long scannedAt, Actor actor, ProjectExplosivePermitPolicy.Identity identity) {
            this.level = level;
            this.mount = mount.immutable();
            this.radar = radar.immutable();
            this.cannon = cannon.getUUID();
            this.target = target;
            this.scannedAt = scannedAt;
            this.actor = actor;
            this.identity = identity;
        }
    }

    /** Called only around the existing native fire path for an already authorized PE candidate. */
    public static synchronized boolean fireWithPermit(ServerLevel level, BlockPos mount, BlockPos radar,
            PitchOrientedContraptionEntity cannon, Entity target, long scannedAt, Actor actor,
            long now, BooleanSupplier nativeFire) {
        if (level == null || mount == null || radar == null || cannon == null || target == null || actor == null ||
                actor.owner() == null || nativeFire == null || !CmiConfig.projectExplosiveConventionalInterceptionEnabled() ||
                !onServerThread(level) || level.getGameTime() != now || now < 0 || scannedAt < 0 ||
                level.getEntity(target.getUUID()) != target ||
                !CiwsCoordinator.projectExplosiveShotBindingCurrent(level, mount, radar, cannon, actor)) return false;

        var authorization = ProjectExplosiveBombObservation.authorizeCiwsTargetFromRadar(level, actor, radar,
                target, now, 1, scannedAt).orElse(null);
        ProjectExplosivePermitPolicy.Identity identity = impactIdentity(level, mount, radar, cannon.getUUID(),
                target, actor, authorization);
        if (identity == null) return false;

        PendingShot previous = PENDING.get();
        PendingShot pending = new PendingShot(level, mount, radar, cannon, target, scannedAt, actor, identity);
        PENDING.set(pending);
        boolean committed = false;
        try {
            boolean nativeAccepted = nativeFire.getAsBoolean();
            AbstractCannonProjectile captured = pending.capturedProjectile;
            ImpactPermit permit = captured == null ? null : IMPACT_PERMITS.get(captured);
            committed = nativeAccepted && pending.captured && !pending.ambiguous && captured != null &&
                    captured.isAlive() && captured.level() == level && level.getEntity(captured.getUUID()) == captured &&
                    permit != null && permit.identity.equals(identity) &&
                    currentAuthorization(captured, level, target, permit) != null;
            return committed;
        } finally {
            if (!committed && pending.capturedProjectile != null) IMPACT_PERMITS.remove(pending.capturedProjectile);
            if (previous == null) PENDING.remove(); else PENDING.set(previous);
        }
    }

    /** Called from EntityJoinLevelEvent only while the managed CBC fire scope is active. */
    public static synchronized void captureManagedCannonShot(ServerLevel level, Entity entity, Actor contextActor) {
        PendingShot pending = PENDING.get();
        if (pending == null || level == null || entity == null || pending.level != level ||
                !(entity instanceof AbstractCannonProjectile cannonProjectile) || !onServerThread(level)) return;

        // Count every CBC projectile joining in this synchronous native call before checking
        // eligibility; a second spawn with incomplete CMI metadata must still revoke the permit.
        pending.cannonProjectileSpawns++;
        if (!ProjectExplosivePermitPolicy.uniqueCbcSpawn(pending.cannonProjectileSpawns)) {
            if (pending.capturedProjectile != null) IMPACT_PERMITS.remove(pending.capturedProjectile);
            pending.ambiguous = true;
            pending.captured = false;
            return;
        }
        if (!ShotContext.managed() || contextActor == null ||
                !entity.getPersistentData().getBoolean("cmiManagedShot") || !sameActor(pending.actor, contextActor) ||
                !entity.isAlive() || entity.level() != level) {
            pending.ambiguous = true;
            return;
        }

        long now = level.getGameTime();
        if (now < pending.scannedAt || now < 0 || now > Long.MAX_VALUE - MAX_FLIGHT_TICKS ||
                !(level.getEntity(pending.cannon) instanceof PitchOrientedContraptionEntity carrier) ||
                !CiwsCoordinator.projectExplosiveShotBindingCurrent(level, pending.mount, pending.radar,
                        carrier, pending.actor)) return;
        var authorization = ProjectExplosiveBombObservation.authorizeCiwsTargetFromRadar(level, pending.actor,
                pending.radar, pending.target, now, 1, pending.scannedAt).orElse(null);
        ProjectExplosivePermitPolicy.Identity identity = impactIdentity(level, pending.mount, pending.radar,
                pending.cannon, pending.target, pending.actor, authorization);
        if (!ProjectExplosivePermitPolicy.sameIdentity(pending.identity, identity)) return;

        prune(level.dimension().location(), now);
        if (IMPACT_PERMITS.size() >= MAX_ACTIVE_PERMITS) return;
        ImpactPermit permit = new ImpactPermit(identity, pending.scannedAt, now, now + MAX_FLIGHT_TICKS,
                pending.target.blockPosition(), cannonProjectile.blockPosition(),
                carrier.blockPosition());
        IMPACT_PERMITS.put(cannonProjectile, permit);
        pending.capturedProjectile = cannonProjectile;
        pending.captured = true;
    }

    /** CBC retains its swept-AABB and segment geometry; this only removes its generic Projectile exclusion. */
    public static synchronized boolean canHit(AbstractCannonProjectile interceptor, ServerLevel level, Entity target) {
        if (interceptor == null || level == null || target == null || !onServerThread(level)) return false;
        ImpactPermit permit = IMPACT_PERMITS.get(interceptor);
        if (permit == null) return false;
        if (currentAuthorization(interceptor, level, target, permit) == null) {
            IMPACT_PERMITS.remove(interceptor);
            return false;
        }
        permit.targetPosition = target.blockPosition().immutable();
        permit.interceptorPosition = interceptor.blockPosition().immutable();
        return true;
    }

    /** Invoked only after CBC accepted the real swept-AABB/segment collision. Consumes before effects. */
    public static synchronized boolean onPhysicalCbcHit(AbstractCannonProjectile interceptor,
            ServerLevel level, Entity target) {
        if (interceptor == null || level == null || target == null || !onServerThread(level)) return false;
        ImpactPermit permit = IMPACT_PERMITS.remove(interceptor); // one callback, including failure, consumes this permit
        if (permit == null || !permit.use.consume()) return false;
        var authorization = currentAuthorization(interceptor, level, target, permit);
        if (authorization == null || interceptor.getPersistentData().getBoolean("cmiPeImpactConsumed")) return false;
        String registryId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        if (!NeutralizationPolicy.allowsConventionalBombNeutralization(registryId,
                CmiConfig.projectExplosiveConventionalInterceptionEnabled(), true, target.isAlive(),
                authorization.playerOwnerVerified(), authorization.metadataConsistent(), authorization.relation(),
                authorization.protectedTarget(), authorization.detonationStateKnown(), authorization.impactOrDetonationStarted(),
                interceptor.getPersistentData().getBoolean("cmiManagedShot"), true, true)) return false;

        // PE remove(DISCARDED) performs normal cleanup; it does not invoke the impact callback.
        interceptor.getPersistentData().putBoolean("cmiPeImpactConsumed", true);
        target.discard();
        interceptor.discard();
        return true;
    }

    private static ProjectExplosiveBombObservation.Authorization currentAuthorization(AbstractCannonProjectile interceptor,
            ServerLevel level, Entity target, ImpactPermit permit) {
        ProjectExplosivePermitPolicy.Identity issued = permit.identity;
        if (!CmiConfig.projectExplosiveConventionalInterceptionEnabled() || !onServerThread(level) ||
                interceptor.level() != level || !interceptor.isAlive() ||
                !interceptor.getPersistentData().getBoolean("cmiManagedShot") ||
                !ProjectExplosiveCompatibility.isConventionalBomb(target) || !target.isAlive() || target.level() != level ||
                !issued.dimension().equals(level.dimension().location().toString()) || !issued.target().equals(target.getUUID()) ||
                level.getEntity(target.getUUID()) != target || interceptor.getPersistentData().getBoolean("cmiPeImpactConsumed")) return null;
        long now = level.getGameTime();
        if (!ProjectExplosivePermitPolicy.valid(issued, issued, true, false, permit.createdAt,
                permit.expiresAt, now) || now < permit.scannedAt) return null;
        try {
            if (!(target instanceof ProjectExplosiveBombState state) || state.cmi$impactOrDetonationStarted()) return null;
            Entity cannonEntity = level.getEntity(issued.cannon());
            if (!(cannonEntity instanceof PitchOrientedContraptionEntity carrier) ||
                    !sameActor(actor(issued), CombatServices.actor(level, interceptor)) ||
                    !CiwsCoordinator.projectExplosiveShotBindingCurrent(level, BlockPos.of(issued.mountPosition()),
                            BlockPos.of(issued.radarPosition()), carrier, actor(issued))) return null;
            var authorization = ProjectExplosiveBombObservation.authorizeCiwsTargetFromRadar(level, actor(issued),
                    BlockPos.of(issued.radarPosition()), target, now, 5, Long.MIN_VALUE).orElse(null);
            ProjectExplosivePermitPolicy.Identity current = impactIdentity(level, BlockPos.of(issued.mountPosition()),
                    BlockPos.of(issued.radarPosition()), issued.cannon(), target, actor(issued), authorization);
            if (!ProjectExplosivePermitPolicy.valid(issued, current, true, false, permit.createdAt,
                    permit.expiresAt, now) || authorization == null ||
                    authorization.contact().owner() == null || !issued.targetOwner().equals(authorization.contact().owner()) ||
                    !issued.targetOwner().equals(projectileOwner(target)) ||
                    !java.util.Objects.equals(issued.targetPlatform(), authorization.contact().platform()) ||
                    authorization.contact().id().uuid() == null || !authorization.contact().id().uuid().equals(issued.target()) ||
                    authorization.contact().seenTick() > now || authorization.trackScannedAt() < permit.scannedAt ||
                    authorization.relation() != Relation.ENEMY || authorization.protectedTarget() ||
                    !CombatServices.canEngage(level, actor(issued), authorization.contact())) return null;
            permit.targetPosition = target.blockPosition().immutable();
            permit.interceptorPosition = interceptor.blockPosition().immutable();
            permit.cannonPosition = carrier.blockPosition().immutable();
            return authorization;
        } catch (RuntimeException | LinkageError unavailableContract) {
            return null;
        }
    }

    private static ProjectExplosivePermitPolicy.Identity impactIdentity(ServerLevel level, BlockPos mount,
            BlockPos radar, UUID cannon, Entity target, Actor actor,
            ProjectExplosiveBombObservation.Authorization authorization) {
        if (level == null || mount == null || radar == null || cannon == null || target == null || actor == null ||
                actor.owner() == null || authorization == null || authorization.physicalRadar() == null ||
                authorization.contact() == null || authorization.contact().id() == null ||
                !target.getUUID().equals(authorization.contact().id().uuid()) ||
                authorization.contact().owner() == null || !authorization.contact().owner().equals(projectileOwner(target)) ||
                authorization.relation() != Relation.ENEMY || authorization.protectedTarget() ||
                !authorization.playerOwnerVerified() || !authorization.metadataConsistent() ||
                !authorization.detonationStateKnown() || authorization.impactOrDetonationStarted()) return null;
        var physicalRadar = authorization.physicalRadar();
        if (!physicalRadar.dimension().equals(level.dimension().location()) || !physicalRadar.position().equals(radar) ||
                physicalRadar.owner() == null || !actor.owner().equals(physicalRadar.owner().owner())) return null;
        return new ProjectExplosivePermitPolicy.Identity(level.dimension().location().toString(), target.getUUID(),
                authorization.contact().owner(), authorization.contact().platform(), mount.asLong(), radar.asLong(),
                physicalRadar.blockEntityType(), physicalRadar.instance(), physicalRadar.owner().owner(),
                physicalRadar.owner().iff(), physicalRadar.owner().platform(), cannon,
                actor.owner(), actor.iff(), actor.platform(), authorization.relation().name(), authorization.protectedTarget());
    }

    private static Actor actor(ProjectExplosivePermitPolicy.Identity identity) {
        return new Actor(identity.actorOwner(), identity.actorIff(), identity.actorPlatform());
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

    private static boolean onServerThread(ServerLevel level) {
        return level.getServer() != null && level.getServer().isSameThread();
    }

    private static void prune(ResourceLocation dimension, long now) {
        Iterator<Map.Entry<AbstractCannonProjectile, ImpactPermit>> iterator = IMPACT_PERMITS.entrySet().iterator();
        while (iterator.hasNext()) {
            ImpactPermit permit = iterator.next().getValue();
            if (permit == null || permit.identity.dimension().equals(dimension.toString()) &&
                    (now < permit.createdAt || now > permit.expiresAt)) iterator.remove();
        }
    }

    public static synchronized void clearDimension(ResourceLocation dimension) {
        if (dimension == null) return;
        IMPACT_PERMITS.entrySet().removeIf(entry -> entry.getValue() == null ||
                dimension.toString().equals(entry.getValue().identity.dimension()));
        ProjectExplosiveBombObservation.clearRadarDimension(dimension);
    }

    public static synchronized void clearChunk(ResourceLocation dimension, ChunkPos chunk) {
        if (dimension == null || chunk == null) return;
        IMPACT_PERMITS.entrySet().removeIf(entry -> {
            ImpactPermit permit = entry.getValue();
            if (permit == null || !dimension.toString().equals(permit.identity.dimension())) return permit == null;
            return inChunk(BlockPos.of(permit.identity.mountPosition()), chunk) ||
                    inChunk(BlockPos.of(permit.identity.radarPosition()), chunk) ||
                    inChunk(permit.targetPosition, chunk) || inChunk(permit.interceptorPosition, chunk) ||
                    inChunk(permit.cannonPosition, chunk);
        });
        ProjectExplosiveBombObservation.clearRadarChunk(dimension, chunk);
    }

    private static boolean inChunk(BlockPos position, ChunkPos chunk) {
        return position != null && new ChunkPos(position).equals(chunk);
    }

    public static synchronized void clear() {
        IMPACT_PERMITS.clear();
        ProjectExplosiveBombObservation.clearRadarInstances();
        PENDING.remove();
    }
}
