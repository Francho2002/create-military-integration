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
import java.util.UUID;
import local.cmi.weapons.CiwsPolicy;
import local.cmi.mixin.weapons.RadarScanningFilterAccess;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Read-only PE bomb observation over an already configured physical Create: Radars
 * snapshot. This class never adds a track to CIWS, acquires a lock, or authorizes fire.
 */
public final class ProjectExplosiveBombObservation {
    private static final int MAX_TRACKS = 128;

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

    private static Decision denied(Rejection reason) { return new Decision(false, false, false, reason); }
    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
