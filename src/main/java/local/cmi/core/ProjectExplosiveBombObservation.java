package local.cmi.core;

import com.happysg.radar.block.radar.behavior.IRadar;
import com.happysg.radar.block.radar.behavior.RadarScanningBlockBehavior;
import com.happysg.radar.block.radar.bearing.RadarBearingBlockEntity;
import com.happysg.radar.block.radar.plane.StationaryRadarBlockEntity;
import com.happysg.radar.block.radar.track.RadarTrack;
import com.happysg.radar.block.radar.track.TrackCategory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import local.cmi.weapons.CiwsPolicy;
import local.cmi.mixin.weapons.RadarScanningFilterAccess;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Read-only PE bomb observation over an already configured physical Create: Radars
 * snapshot. {@link #inspect} remains observational; the explicitly named authorization methods
 * supply current, fail-closed gates to the existing CIWS path and never fabricate radar tracks.
 */
public final class ProjectExplosiveBombObservation {
    private static final int MAX_TRACKS = 128;
    // Weak keys avoid retaining unloaded radar block entities. Values contain no world/BE references.
    private static final Map<net.minecraft.world.level.block.entity.BlockEntity, RadarInstance> RADAR_INSTANCES = new WeakHashMap<>();

    private ProjectExplosiveBombObservation() {}

    public enum OwnerEvidence { PROJECTILE_OWNER_RESOLVED, UNKNOWN_OR_UNLOADED }

    public enum Rejection {
        NONE,
        TRACK_BUDGET_EXCEEDED,
        TRACK_NOT_CURRENT,
        ENTITY_NOT_LOADED_OR_ALIVE,
        UNKNOWN_PROJECT_EXPLOSIVE_TYPE,
        NOT_CONVENTIONAL_BOMB,
        INVALID_TRACK_VECTOR,
        OWNER_UNKNOWN_OR_UNLOADED,
        OBSERVER_OWNER_UNKNOWN,
        FRIEND,
        NEUTRAL,
        UNKNOWN_IFF,
        PROTECTED,
        AWAITING_PE_02B_AUTHORIZATION
    }

    public record Evidence(ProjectExplosiveCompatibility.Kind kind, boolean physicalTrackCurrent,
            boolean entityResolvedAndAlive, boolean vectorsFinite, boolean ownerKnown,
            boolean projectileOwnerResolved, boolean observerOwnerKnown, Relation relation,
            boolean protectedTarget) {}

    public record Decision(boolean futureEligible, boolean acquisitionAuthorized,
            boolean neutralizationAuthorized, Rejection rejection) {}

    public record Observation(ProjectExplosiveCompatibility.Kind kind, UUID entityId,
            Vec3 radarPosition, Vec3 radarVelocity, UUID owner, OwnerEvidence ownerEvidence,
            boolean cmiLaunchContextVerified, Relation relation, boolean futureEligible,
            boolean acquisitionAuthorized, boolean neutralizationAuthorized, Rejection rejection) {}

    /** Stable only for one loaded physical block-entity instance; it is never persisted. */
    public record RadarIdentity(net.minecraft.resources.ResourceLocation dimension, BlockPos position,
            String blockEntityType, UUID instance, Actor owner) {
        public RadarIdentity {
            position = position == null ? null : position.immutable();
        }
    }

    private record RadarInstance(net.minecraft.resources.ResourceLocation dimension, BlockPos position,
            String blockEntityType, UUID token) {}

    /** Current, fail-closed CIWS view backed by one physical PROJECTILE radar track. */
    public record Authorization(MilitaryContact contact, boolean playerOwnerVerified,
            boolean cmiLaunchContextVerified, boolean metadataConsistent, Relation relation, boolean protectedTarget,
            boolean detonationStateKnown, boolean impactOrDetonationStarted, long trackScannedAt,
            RadarIdentity physicalRadar) {
        public Authorization withPhysicalRadar(RadarIdentity radar) {
            return new Authorization(contact, playerOwnerVerified, cmiLaunchContextVerified, metadataConsistent,
                    relation, protectedTarget, detonationStateKnown, impactOrDetonationStarted, trackScannedAt, radar);
        }
    }

    /** Pure value form for testing duplicate physical tracks without constructing Minecraft entities. */
    public record TrackIdentity(String id, TrackCategory category, long scannedTime, Vec3 position, Vec3 velocity) {}

    /** Pure authorization assessment; even a future-eligible observation remains inert in this cycle. */
    public static Decision assess(Evidence evidence) {
        if (evidence == null || !evidence.physicalTrackCurrent()) return denied(Rejection.TRACK_NOT_CURRENT);
        if (!evidence.entityResolvedAndAlive()) return denied(Rejection.ENTITY_NOT_LOADED_OR_ALIVE);
        if (evidence.kind() == null || evidence.kind() == ProjectExplosiveCompatibility.Kind.UNKNOWN)
            return denied(Rejection.UNKNOWN_PROJECT_EXPLOSIVE_TYPE);
        if (!evidence.kind().conventionalBomb()) return denied(Rejection.NOT_CONVENTIONAL_BOMB);
        if (!evidence.vectorsFinite()) return denied(Rejection.INVALID_TRACK_VECTOR);
        if (!evidence.ownerKnown() || !evidence.projectileOwnerResolved())
            return denied(Rejection.OWNER_UNKNOWN_OR_UNLOADED);
        if (!evidence.observerOwnerKnown()) return denied(Rejection.OBSERVER_OWNER_UNKNOWN);
        if (evidence.relation() != Relation.ENEMY) {
            Rejection reason = evidence.relation() == Relation.FRIEND ? Rejection.FRIEND :
                    evidence.relation() == Relation.NEUTRAL ? Rejection.NEUTRAL : Rejection.UNKNOWN_IFF;
            return denied(reason);
        }
        if (evidence.protectedTarget()) return denied(Rejection.PROTECTED);
        return new Decision(true, false, false, Rejection.AWAITING_PE_02B_AUTHORIZATION);
    }

    /** Reads only the supplied physical radar's current tracks; no world-wide entity scan is performed. */
    public static List<Observation> inspect(ServerLevel level, Actor observer, IRadar radar, long now) {
        if (level == null || radar == null) return List.of();
        if (!level.getServer().isSameThread()) throw new IllegalStateException("CMI world access off server thread");
        if (!(radar instanceof StationaryRadarBlockEntity) && !(radar instanceof RadarBearingBlockEntity)) return List.of();
        if (!radar.isRunning() || !(radar instanceof SmartBlockEntity smart)) return List.of();
        if (radar.getWorldPos() == null || !level.hasChunkAt(radar.getWorldPos()) ||
                ((net.minecraft.world.level.block.entity.BlockEntity)radar).getLevel() != level ||
                level.getBlockEntity(radar.getWorldPos()) != radar) return List.of();
        RadarScanningBlockBehavior scanning = smart.getBehaviour(RadarScanningBlockBehavior.TYPE);
        if (scanning == null || !((RadarScanningFilterAccess)(Object)scanning).cmi$scanProjectiles()) return List.of();

        Collection<RadarTrack> tracks;
        try { tracks = radar.getTracks(); }
        catch (RuntimeException unavailable) { return List.of(); }
        if (tracks == null) return List.of();
        if (tracks.size() > MAX_TRACKS)
            return List.of(new Observation(ProjectExplosiveCompatibility.Kind.UNKNOWN, null, null, null,
                    null, OwnerEvidence.UNKNOWN_OR_UNLOADED, false, Relation.UNKNOWN, false, false,
                    false, Rejection.TRACK_BUDGET_EXCEEDED));

        List<Observation> out = new ArrayList<>();
        for (RadarTrack track : tracks) {
            if (track == null || track.trackCategory() != TrackCategory.PROJECTILE) continue;
            UUID id = CiwsPolicy.projectileId(true, track.id(), now, track.scannedTime());
            if (id == null) continue;
            Entity entity = level.getEntity(id);
            if (entity == null || !entity.isAlive()) continue;
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (key == null || !ProjectExplosiveCompatibility.isProjectExplosiveRegistryId(key.toString())) continue;

            var classification = ProjectExplosiveCompatibility.classifyRegistryId(key.toString());
            Entity ownerEntity = entity instanceof Projectile projectile ? projectile.getOwner() : null;
            UUID owner = ownerEntity == null ? null : ownerEntity.getUUID();
            boolean ownerKnown = owner != null;
            Relation relation = Relation.UNKNOWN;
            boolean protectedTarget = true;
            if (observer != null && observer.owner() != null && ownerKnown) {
                try {
                    MilitaryContact identity = new MilitaryContact(new TargetId(TargetId.Kind.ENTITY, id),
                            entity.getBoundingBox().getCenter(), entity.getDeltaMovement(), entity.getBoundingBox(),
                            owner, null, false, false, track.scannedTime());
                    relation = CombatServices.relation(level, observer, identity);
                    protectedTarget = CombatServices.isProtected(level, observer, entity.position());
                } catch (RuntimeException invalidIdentity) {
                    relation = Relation.UNKNOWN;
                    protectedTarget = true;
                }
            }

            Decision decision = assess(new Evidence(classification.kind(), true, true,
                    finite(track.position()) && finite(track.velocity()), ownerKnown,
                    ownerEntity != null && entity instanceof Projectile, observer != null && observer.owner() != null,
                    relation, protectedTarget));
            out.add(new Observation(classification.kind(), id, track.position(), track.velocity(), owner,
                    ownerKnown ? OwnerEvidence.PROJECTILE_OWNER_RESOLVED : OwnerEvidence.UNKNOWN_OR_UNLOADED,
                    false, relation, decision.futureEligible(), decision.acquisitionAuthorized(),
                    decision.neutralizationAuthorized(), decision.rejection()));
        }
        return List.copyOf(out);
    }

    /**
     * Adapts one track already obtained from the linked physical radar. The caller must have
     * applied the radar's scanProjectiles switch and UUID correlation before calling this method.
     */
    public static java.util.Optional<Authorization> authorizeCiwsTrack(ServerLevel level, Actor observer,
            Entity entity, RadarTrack track, long now, long maximumAge) {
        if (level == null || observer == null || observer.owner() == null || entity == null || track == null ||
                maximumAge < 0 || maximumAge > 5 || !CmiConfig.projectExplosiveConventionalInterceptionEnabled())
            return java.util.Optional.empty();
        if (!level.getServer().isSameThread() || entity.level() != level || !entity.isAlive() ||
                level.getEntity(entity.getUUID()) != entity || !ProjectExplosiveCompatibility.isConventionalBomb(entity))
            return java.util.Optional.empty();
        if (track.trackCategory() != TrackCategory.PROJECTILE || !finite(track.position()) || !finite(track.velocity()) ||
                track.scannedTime() < 0 || now < track.scannedTime() || now - track.scannedTime() > maximumAge ||
                !entity.getUUID().toString().equals(track.id())) return java.util.Optional.empty();
        if (!(entity instanceof Projectile projectile)) return java.util.Optional.empty();

        Entity ownerEntity;
        boolean impactOrDetonationStarted;
        try {
            ownerEntity = projectile.getOwner();
            if (!(ownerEntity instanceof Player) || ownerEntity.isRemoved() || ownerEntity.level() != level ||
                    level.getEntity(ownerEntity.getUUID()) != ownerEntity) return java.util.Optional.empty();
            if (!(entity instanceof ProjectExplosiveBombState state)) return java.util.Optional.empty();
            impactOrDetonationStarted = state.cmi$impactOrDetonationStarted();
        } catch (RuntimeException | LinkageError unavailableState) {
            return java.util.Optional.empty();
        }
        try {
            boolean metadataConsistent = metadataConsistent(entity, ownerEntity.getUUID());
            MilitaryContact base = CombatServices.resolve(level,
                    new TargetId(TargetId.Kind.ENTITY, entity.getUUID())).orElse(null);
            if (base == null || base.owner() == null || !ownerEntity.getUUID().equals(base.owner()))
                return java.util.Optional.empty();
            Relation relation = CombatServices.relation(level, observer, base);
            boolean protectedTarget = CombatServices.isProtected(level, observer, entity.position());
            boolean engageable = CombatServices.canEngage(level, observer, base);
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (key == null || !NeutralizationPolicy.allowsConventionalBombAcquisition(key.toString(),
                    CmiConfig.projectExplosiveConventionalInterceptionEnabled(), true, true, true,
                    metadataConsistent, relation, protectedTarget, true, impactOrDetonationStarted) || !engageable)
                return java.util.Optional.empty();

            MilitaryContact ciwsContact = new MilitaryContact(base.id(), base.position(), base.velocity(), base.bounds(),
                    base.owner(), base.platform(), true, base.hot(), base.seenTick());
            return java.util.Optional.of(new Authorization(ciwsContact, true, false, metadataConsistent, relation,
                    protectedTarget, true, impactOrDetonationStarted, track.scannedTime(), null));
        } catch (RuntimeException | LinkageError unavailableIdentity) {
            return java.util.Optional.empty();
        }
    }

    /** Re-reads the same loaded physical radar immediately before/at the real CBC callback. */
    public static java.util.Optional<Authorization> authorizeCiwsTargetFromRadar(ServerLevel level, Actor observer,
            BlockPos radarPos, Entity entity, long now, long maximumAge, long requiredScannedAt) {
        if (level == null || radarPos == null || entity == null || maximumAge < 0 || maximumAge > 5 ||
                !level.hasChunkAt(radarPos)) return java.util.Optional.empty();
        try {
            var blockEntity = level.getBlockEntity(radarPos);
            if (!(blockEntity instanceof IRadar radar) ||
                    !(radar instanceof StationaryRadarBlockEntity) && !(radar instanceof RadarBearingBlockEntity) ||
                    !(radar instanceof SmartBlockEntity smart) || radar.getWorldPos() == null ||
                    !radar.getWorldPos().equals(radarPos) || ((net.minecraft.world.level.block.entity.BlockEntity)radar).getLevel() != level ||
                    !radar.isRunning()) return java.util.Optional.empty();
            RadarScanningBlockBehavior scanning = smart.getBehaviour(RadarScanningBlockBehavior.TYPE);
            if (scanning == null || !((RadarScanningFilterAccess)(Object)scanning).cmi$scanProjectiles())
                return java.util.Optional.empty();
            Collection<RadarTrack> tracks = radar.getTracks();
            if (tracks == null || tracks.size() > MAX_TRACKS) return java.util.Optional.empty();
            RadarTrack matched = null;
            for (RadarTrack track : tracks) {
                if (track == null || !entity.getUUID().toString().equals(track.id())) continue;
                if (track.trackCategory() != TrackCategory.PROJECTILE) return java.util.Optional.empty();
                if (matched != null && !sameTrackObservation(matched, track)) return java.util.Optional.empty();
                matched = track;
            }
            if (matched == null || requiredScannedAt != Long.MIN_VALUE && requiredScannedAt != matched.scannedTime())
                return java.util.Optional.empty();
            Authorization authorization = authorizeCiwsTrack(level, observer, entity, matched, now, maximumAge).orElse(null);
            if (authorization == null) return java.util.Optional.empty();
            RadarIdentity identity = physicalRadarIdentity(level, radarPos, observer).orElse(null);
            return identity == null || identity.owner().owner() == null ||
                    !identity.owner().owner().equals(observer.owner()) ? java.util.Optional.empty() :
                    java.util.Optional.of(authorization.withPhysicalRadar(identity));
        } catch (RuntimeException | LinkageError unavailableRadar) {
            return java.util.Optional.empty();
        }
    }

    /** Captures the exact loaded radar instance and current CMI owner/platform identity as values. */
    public static synchronized java.util.Optional<RadarIdentity> physicalRadarIdentity(ServerLevel level,
            BlockPos radarPos, Actor expectedActor) {
        if (level == null || radarPos == null || expectedActor == null || expectedActor.owner() == null ||
                !level.getServer().isSameThread() || !level.hasChunkAt(radarPos)) return java.util.Optional.empty();
        try {
            var blockEntity = level.getBlockEntity(radarPos);
            if (!(blockEntity instanceof IRadar radar) ||
                    !(radar instanceof StationaryRadarBlockEntity) && !(radar instanceof RadarBearingBlockEntity) ||
                    !(radar instanceof SmartBlockEntity) || radar.getWorldPos() == null ||
                    !radar.getWorldPos().equals(radarPos) || blockEntity.getLevel() != level || !radar.isRunning())
                return java.util.Optional.empty();
            var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            if (type == null) return java.util.Optional.empty();
            Actor radarActor = CombatServices.actor(level, radarPos);
            if (radarActor.owner() == null || !expectedActor.owner().equals(radarActor.owner()))
                return java.util.Optional.empty();
            var dimension = level.dimension().location();
            BlockPos immutable = radarPos.immutable();
            String typeId = type.toString();
            RadarInstance instance = RADAR_INSTANCES.get(blockEntity);
            if (instance == null || !instance.dimension().equals(dimension) ||
                    !instance.position().equals(immutable) || !instance.blockEntityType().equals(typeId)) {
                instance = new RadarInstance(dimension, immutable, typeId, UUID.randomUUID());
                RADAR_INSTANCES.put(blockEntity, instance);
            }
            return java.util.Optional.of(new RadarIdentity(dimension, immutable, typeId, instance.token(), radarActor));
        } catch (RuntimeException | LinkageError unavailableRadarIdentity) {
            return java.util.Optional.empty();
        }
    }

    public static synchronized void clearRadarDimension(net.minecraft.resources.ResourceLocation dimension) {
        if (dimension != null) RADAR_INSTANCES.entrySet().removeIf(entry -> entry.getValue() == null ||
                dimension.equals(entry.getValue().dimension()));
    }

    public static synchronized void clearRadarChunk(net.minecraft.resources.ResourceLocation dimension, ChunkPos chunk) {
        if (dimension != null && chunk != null) RADAR_INSTANCES.entrySet().removeIf(entry -> {
            RadarInstance instance = entry.getValue();
            return instance == null || dimension.equals(instance.dimension()) &&
                    new ChunkPos(instance.position()).equals(chunk);
        });
    }

    public static synchronized void clearRadarInstances() { RADAR_INSTANCES.clear(); }

    /** Conflicting observations for one UUID are ambiguous and cannot seed PE acquisition. */
    public static boolean unambiguousPhysicalTrack(Collection<RadarTrack> tracks, RadarTrack expected) {
        if (tracks == null || expected == null || expected.id() == null) return false;
        for (RadarTrack other : tracks) {
            if (other == null || other == expected || !expected.id().equals(other.id())) continue;
            if (!sameTrackObservation(expected, other)) return false;
        }
        return true;
    }

    public static boolean unambiguousTrackIdentities(Collection<TrackIdentity> tracks, TrackIdentity expected) {
        if (tracks == null || expected == null || expected.id() == null) return false;
        for (TrackIdentity other : tracks) {
            if (other == null || other == expected || !expected.id().equals(other.id())) continue;
            if (!sameTrackObservation(expected, other)) return false;
        }
        return true;
    }

    private static boolean sameTrackObservation(RadarTrack first, RadarTrack second) {
        return first.trackCategory() == second.trackCategory() && first.scannedTime() == second.scannedTime() &&
                java.util.Objects.equals(first.position(), second.position()) &&
                java.util.Objects.equals(first.velocity(), second.velocity());
    }

    private static boolean sameTrackObservation(TrackIdentity first, TrackIdentity second) {
        return first.category() == second.category() && first.scannedTime() == second.scannedTime() &&
                java.util.Objects.equals(first.position(), second.position()) &&
                java.util.Objects.equals(first.velocity(), second.velocity());
    }

    private static boolean metadataConsistent(Entity entity, UUID projectileOwner) {
        var tag = entity.getPersistentData();
        if (tag.contains("cmiOwner") && (!tag.hasUUID("cmiOwner") || !projectileOwner.equals(tag.getUUID("cmiOwner")))) return false;
        if (tag.contains("cmiIff") && (!tag.contains("cmiIff", net.minecraft.nbt.Tag.TAG_STRING) || !tag.getString("cmiIff").isBlank())) return false;
        if (tag.contains("cmiPlatform")) return false; // PE launch provenance has no verified CMI platform contract.
        if (tag.contains("cmiManagedShot") && (!tag.contains("cmiManagedShot", net.minecraft.nbt.Tag.TAG_BYTE) || tag.getBoolean("cmiManagedShot"))) return false;
        if (tag.contains("cmiManaged") && (!tag.contains("cmiManaged", net.minecraft.nbt.Tag.TAG_BYTE) || tag.getBoolean("cmiManaged"))) return false;
        return true;
    }

    private static Decision denied(Rejection reason) { return new Decision(false, false, false, reason); }
    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
