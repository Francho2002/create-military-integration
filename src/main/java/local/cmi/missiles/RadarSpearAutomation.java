package local.cmi.missiles;

import com.happysg.radar.block.radar.behavior.IRadar;
import com.happysg.radar.block.radar.bearing.RadarBearingBlockEntity;
import com.happysg.radar.block.radar.plane.StationaryRadarBlockEntity;
import com.happysg.radar.block.radar.track.RadarTrack;
import com.happysg.radar.block.radar.track.TrackCategory;
import com.wariumce.block.entity.RadarSpearMissileHardpointBlockEntity;
import com.wariumce.init.CrustyChunksModBlocks;
import com.wariumce.procedures.MissileHardpointFireProcedure;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import local.cmi.core.Actor;
import local.cmi.core.CombatServices;
import local.cmi.core.MilitaryContact;
import local.cmi.core.Relation;
import local.cmi.core.TargetId;

/** Server-thread automation driven only by tracks returned by a linked physical Create: Radars block. */
public final class RadarSpearAutomation {
    private static final int MAX_SABLE_TRACKS_PER_SCAN = 128;
    private static final String AUTO = "cmiRadarAuto";
    private static final String RADAR_DIM = "cmiRadarDimension";
    private static final String RADAR_POS = "cmiRadarPosition";
    private static final String TARGET = "cmiRadarAutoTarget";
    private static final String TRACK_TICK = "cmiRadarAutoTrackTick";
    private static final String LOCK_SAMPLES = "cmiRadarAutoLockSamples";
    private static final String LAST_UPDATE = "cmiRadarAutoLastUpdate";
    private static final String LAST_FIRE_SAMPLE = "cmiRadarAutoLastFireSample";
    private static final ThreadLocal<LaunchPermit> FIRE_PERMIT = new ThreadLocal<>();

    private static final class LaunchPermit {
        private final LevelAccessor level;
        private final BlockPos pos;
        private boolean consumed;
        private LaunchPermit(LevelAccessor level, BlockPos pos) { this.level = level; this.pos = pos; }
    }
    private record Observed(MilitaryContact contact, long scannedAt, double distanceSquared) {
        RadarSpearLockPolicy.Candidate candidate() {
            return new RadarSpearLockPolicy.Candidate(contact.id().uuid(), distanceSquared, scannedAt);
        }
    }

    private RadarSpearAutomation() {}

    /** True only for a deliberately enabled, correctly typed CMI flag. */
    public static boolean controls(LevelAccessor world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof RadarSpearMissileHardpointBlockEntity)) return false;
        CompoundTag data = be.getPersistentData();
        return data.contains(AUTO) && (!data.contains(AUTO, Tag.TAG_BYTE) || data.getBoolean(AUTO));
    }

    /** Fail-closed result used by the administrative command before enabling the mode. */
    public static String enableError(ServerLevel level, BlockPos pos, UUID player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RadarSpearMissileHardpointBlockEntity)) return "position is not a loaded Radar Spear hardpoint";
        CompoundTag data = be.getPersistentData();
        Actor owner = CombatServices.actor(level, pos);
        if (owner.owner() == null || !owner.owner().equals(player)) return "the hardpoint must have this player's CMI ownership";
        if (!data.contains("cmiManaged", Tag.TAG_BYTE) || !data.getBoolean("cmiManaged")) return "configure a CMI Radar Spear profile first";
        String profile = data.getString("cmiRadarProfile");
        if (!"direct_sable".equals(profile) && !"explosive_sable".equals(profile)) return "the CMI Sable warhead profile is missing or invalid";
        RadarBinding binding = radarBinding(level, pos, be);
        if (binding.error != null) return binding.error;
        return null;
    }

    /** Link only a loaded radar implementation shipped by the pinned Create: Radars mod. */
    public static String bindRadar(ServerLevel level, BlockPos hardpointPos, BlockPos radarPos, UUID player) {
        BlockEntity launcher = level.getBlockEntity(hardpointPos);
        if (!(launcher instanceof RadarSpearMissileHardpointBlockEntity)) return "position is not a loaded Radar Spear hardpoint";
        Actor launcherOwner = CombatServices.actor(level, hardpointPos);
        if (launcherOwner.owner() == null || !launcherOwner.owner().equals(player)) return "the hardpoint must have this player's CMI ownership";
        CompoundTag data = launcher.getPersistentData();
        if (data.contains(AUTO) && (!data.contains(AUTO, Tag.TAG_BYTE) || data.getBoolean(AUTO))) return "disable automatic mode before changing its radar";
        if (!level.hasChunkAt(radarPos)) return "the radar chunk is not loaded";
        BlockEntity radarBe = level.getBlockEntity(radarPos);
        if (!realRadar(radarBe)) return "position is not a loaded Stationary Radar or Radar Bearing from Create: Radars";
        Actor radarOwner = CombatServices.actor(level, radarPos);
        if (radarOwner.owner() == null || !radarOwner.owner().equals(launcherOwner.owner())) return "radar and hardpoint must have the same known CMI owner";
        IRadar radar = (IRadar) radarBe;
        if (!radar.isRunning()) return "the selected physical radar is not running";
        data.putString(RADAR_DIM, level.dimension().location().toString());
        data.putLong(RADAR_POS, radarPos.asLong());
        clearLock(data);
        launcher.setChanged();
        return null;
    }

    /** Called at the head of Warium's normal tick-trigger procedure for explicitly auto-enabled hardpoints. */
    public static void tick(LevelAccessor world, BlockPos pos) {
        if (world.isClientSide() || !(world instanceof ServerLevel level)) return;
        BlockEntity launcher = level.getBlockEntity(pos);
        if (!(launcher instanceof RadarSpearMissileHardpointBlockEntity)) return;
        CompoundTag data = launcher.getPersistentData();
        if (!data.contains(AUTO, Tag.TAG_BYTE) || !data.getBoolean(AUTO)) {
            clearLockAndRestore(data, launcher);
            return;
        }
        long now = level.getGameTime();
        if (data.contains(LAST_UPDATE, Tag.TAG_LONG) && data.getLong(LAST_UPDATE) == now) return;
        // The pinned radar scanner refreshes at gameTime % 5 == 1. Process at +1 tick,
        // after its scan, so a track no older than one tick is required for a decision.
        if (now % 5 != 2) return;
        data.putLong(LAST_UPDATE, now);

        Actor actor = CombatServices.actor(level, pos);
        RadarBinding binding = radarBinding(level, pos, launcher);
        if (binding.error != null || actor.owner() == null || !data.contains("cmiManaged", Tag.TAG_BYTE) || !data.getBoolean("cmiManaged") ||
                !("direct_sable".equals(data.getString("cmiRadarProfile")) || "explosive_sable".equals(data.getString("cmiRadarProfile")))) {
            clearLockAndRestore(data, launcher);
            return;
        }

        List<Observed> observed = readAuthorizedTracks(level, pos, actor, binding.radar);
        UUID current = data.hasUUID(TARGET) ? data.getUUID(TARGET) : null;
        List<RadarSpearLockPolicy.Candidate> choices = observed.stream().map(Observed::candidate).toList();
        RadarSpearLockPolicy.Candidate selected = RadarSpearLockPolicy.select(current, choices);
        if (selected == null) {
            clearLockAndRestore(data, launcher);
            launcher.setChanged();
            return;
        }
        Observed target = observed.stream().filter(c -> c.contact.id().uuid().equals(selected.id())).findFirst().orElse(null);
        if (target == null || !CombatServices.canEngage(level, actor, target.contact)) {
            clearLockAndRestore(data, launcher);
            launcher.setChanged();
            return;
        }

        RadarSpearLockPolicy.Lock previous = readLock(data);
        RadarSpearLockPolicy.Lock next = RadarSpearLockPolicy.observe(previous, selected);
        data.putUUID(TARGET, next.target());
        data.putLong(TRACK_TICK, next.lastScan());
        data.putInt(LOCK_SAMPLES, next.samples());
        writeDesignation(data, target.contact.bounds().getCenter());
        launcher.setChanged();

        if (!RadarSpearLockPolicy.shouldFire(next, data.getLong(LAST_FIRE_SAMPLE))) return;
        if (!readyLoadedHardpoint(level, pos, launcher) || !currentTrackStillAuthorized(level, pos, next.target())) return;

        // Mark this radar sample before entering Warium, so a repeated tick cannot request another fire.
        data.putLong(LAST_FIRE_SAMPLE, selected.scannedAt());
        launcher.setChanged();
        BlockState state = level.getBlockState(pos);
        LaunchPermit old = FIRE_PERMIT.get();
        FIRE_PERMIT.set(new LaunchPermit(level, pos.immutable()));
        try {
            MissileHardpointFireProcedure.execute(level, pos.getX(), pos.getY(), pos.getZ(), state);
        } finally {
            if (old == null) FIRE_PERMIT.remove(); else FIRE_PERMIT.set(old);
        }
    }

    /** The native fire procedure is blocked on auto hardpoints except for the scoped CMI call above. */
    public static boolean authorizedNativeFire(LevelAccessor world, BlockPos pos) {
        LaunchPermit permit = FIRE_PERMIT.get();
        if (permit == null || permit.consumed || permit.level != world || !permit.pos.equals(pos)) return false;
        permit.consumed = true;
        return true;
    }

    /** Revalidates the selected live radar track at Warium's exact spawn redirect. */
    public static boolean authorizesLaunch(ServerLevel level, BlockPos pos, UUID targetId, Vec3 designatedPoint) {
        BlockEntity launcher = level.getBlockEntity(pos);
        if (!(launcher instanceof RadarSpearMissileHardpointBlockEntity) || !controls(level, pos) || !readyLoadedHardpoint(level, pos, launcher)) return false;
        CompoundTag data = launcher.getPersistentData();
        if (!data.hasUUID(TARGET) || !data.getUUID(TARGET).equals(targetId) || data.getInt(LOCK_SAMPLES) < 2 ||
                !finite(designatedPoint) || !RadarSpearMode.hasDesignation(data)) return false;
        RadarBinding binding = radarBinding(level, pos, launcher);
        if (binding.error != null) return false;
        Actor actor = CombatServices.actor(level, pos);
        Observed selected = readAuthorizedTracks(level, pos, actor, binding.radar).stream()
                .filter(c -> c.contact.id().uuid().equals(targetId)).findFirst().orElse(null);
        if (selected == null || !CombatServices.canEngage(level, actor, selected.contact)) return false;
        Vec3 currentCenter = selected.contact.bounds().getCenter();
        return finite(currentCenter) && selected.contact.id().kind() == TargetId.Kind.SABLE &&
                selected.contact.platform() != null && selected.contact.platform().equals(targetId) &&
                designatedPoint.distanceToSqr(currentCenter) <= 1.0e-8;
    }

    private static boolean currentTrackStillAuthorized(ServerLevel level, BlockPos pos, UUID targetId) {
        BlockEntity launcher = level.getBlockEntity(pos);
        if (!(launcher instanceof RadarSpearMissileHardpointBlockEntity)) return false;
        Actor actor = CombatServices.actor(level, pos);
        RadarBinding binding = radarBinding(level, pos, launcher);
        return binding.error == null && readAuthorizedTracks(level, pos, actor, binding.radar).stream()
                .anyMatch(c -> c.contact.id().uuid().equals(targetId) && CombatServices.canEngage(level, actor, c.contact));
    }

    private static List<Observed> readAuthorizedTracks(ServerLevel level, BlockPos hardpoint, Actor actor, IRadar radar) {
        if (actor.owner() == null || radar == null || !radar.isRunning()) return List.of();
        long now = level.getGameTime();
        List<RadarTrack> sableTracks = new ArrayList<>();
        for (RadarTrack track : radar.getTracks()) {
            if (track == null || track.trackCategory() != TrackCategory.SABLE) continue;
            if (sableTracks.size() == MAX_SABLE_TRACKS_PER_SCAN) return List.of(); // Fail closed rather than truncate nondeterministically.
            sableTracks.add(track);
        }
        sableTracks.sort(java.util.Comparator.comparing(RadarTrack::id));
        List<Observed> result = new ArrayList<>();
        Actor currentActor = CombatServices.actor(level, hardpoint);
        for (RadarTrack track : sableTracks) {
            if (!finite(track.position())) continue;
            if (!RadarSpearLockPolicy.fresh(now, track.scannedTime())) continue; // Require the latest physical sweep, not a stale cached contact.
            UUID shipId;
            try { shipId = UUID.fromString(track.id()); }
            catch (IllegalArgumentException malformedTrack) { continue; }
            TargetId id = new TargetId(TargetId.Kind.SABLE, shipId);
            MilitaryContact contact = CombatServices.resolve(level, id).orElse(null);
            if (contact == null || contact.owner() == null || contact.platform() == null || !shipId.equals(contact.platform())) continue;
            if (shipId.equals(currentActor.platform())) continue;
            if (CombatServices.relation(level, actor, contact) != Relation.ENEMY || !CombatServices.canEngage(level, actor, contact)) continue;
            Vec3 center = contact.bounds().getCenter();
            if (!finite(center)) continue;
            double distanceSquared = center.distanceToSqr(Vec3.atCenterOf(hardpoint));
            if (!Double.isFinite(distanceSquared)) continue;
            result.add(new Observed(contact, track.scannedTime(), distanceSquared));
        }
        return result;
    }

    private record RadarBinding(IRadar radar, String error) {}
    private static RadarBinding radarBinding(ServerLevel level, BlockPos hardpoint, BlockEntity launcher) {
        CompoundTag data = launcher.getPersistentData();
        if (!data.contains(RADAR_DIM, Tag.TAG_STRING) || !data.contains(RADAR_POS, Tag.TAG_LONG)) return new RadarBinding(null, "link a physical Create: Radars block with /cmi radarspearradar first");
        if (!level.dimension().location().toString().equals(data.getString(RADAR_DIM))) return new RadarBinding(null, "the linked radar must be in the hardpoint's current dimension");
        BlockPos radarPos = BlockPos.of(data.getLong(RADAR_POS));
        if (!level.hasChunkAt(radarPos)) return new RadarBinding(null, "the linked radar chunk is not loaded");
        BlockEntity radarBe = level.getBlockEntity(radarPos);
        if (!realRadar(radarBe)) return new RadarBinding(null, "the linked physical radar is missing or no longer a supported radar block");
        Actor launcherOwner = CombatServices.actor(level, hardpoint);
        Actor radarOwner = CombatServices.actor(level, radarPos);
        if (launcherOwner.owner() == null || radarOwner.owner() == null || !launcherOwner.owner().equals(radarOwner.owner())) return new RadarBinding(null, "radar/hardpoint ownership is missing or no longer compatible");
        IRadar radar = (IRadar) radarBe;
        if (!radar.isRunning()) return new RadarBinding(null, "the linked physical radar is stopped");
        return new RadarBinding(radar, null);
    }

    private static boolean realRadar(BlockEntity be) {
        return be instanceof StationaryRadarBlockEntity || be instanceof RadarBearingBlockEntity;
    }

    private static boolean readyLoadedHardpoint(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be instanceof RadarSpearMissileHardpointBlockEntity && level.hasChunkAt(pos) &&
                level.getBlockState(pos).is(CrustyChunksModBlocks.RADAR_SPEAR_MISSILE_HARDPOINT.get());
    }

    private static void writeDesignation(CompoundTag data, Vec3 point) {
        if (!finite(point)) return;
        backupDesignation(data);
        data.putDouble("TargetX", point.x); data.putDouble("TargetY", point.y); data.putDouble("TargetZ", point.z);
        data.putDouble("cmiAutoWrittenTargetX", point.x); data.putDouble("cmiAutoWrittenTargetY", point.y); data.putDouble("cmiAutoWrittenTargetZ", point.z);
    }

    private static void backupDesignation(CompoundTag data) {
        if (data.getBoolean("cmiAutoTargetBackup")) return;
        for (String axis : new String[]{"X", "Y", "Z"}) {
            String key = "Target" + axis;
            boolean present = data.contains(key, Tag.TAG_DOUBLE) || data.contains(key, Tag.TAG_FLOAT) || data.contains(key, Tag.TAG_INT) || data.contains(key, Tag.TAG_LONG);
            data.putBoolean("cmiAutoOriginal" + axis + "Present", present);
            if (present) data.putDouble("cmiAutoOriginalTarget" + axis, data.getDouble(key));
        }
        data.putBoolean("cmiAutoTargetBackup", true);
    }

    public static void setEnabled(BlockEntity launcher, boolean enabled) {
        CompoundTag data = launcher.getPersistentData();
        if (!enabled) {
            restoreDesignation(data);
            data.putBoolean(AUTO, false);
            clearLock(data);
        } else {
            if (!data.getBoolean(AUTO)) restoreDesignation(data);
            data.putBoolean(AUTO, true);
            clearLock(data);
            data.remove(LAST_FIRE_SAMPLE);
            data.remove(LAST_UPDATE);
        }
        launcher.setChanged();
    }

    private static void clearLockAndRestore(CompoundTag data, BlockEntity launcher) {
        restoreDesignation(data);
        clearLock(data);
        launcher.setChanged();
    }

    private static void restoreDesignation(CompoundTag data) {
        if (data.getBoolean("cmiAutoTargetBackup")) {
            boolean stillOurs = true;
            for (String axis : new String[]{"X", "Y", "Z"}) {
                String key = "Target" + axis, written = "cmiAutoWrittenTarget" + axis;
                if (!data.contains(written, Tag.TAG_DOUBLE) || !data.contains(key, Tag.TAG_DOUBLE) ||
                        Double.compare(data.getDouble(key), data.getDouble(written)) != 0) stillOurs = false;
            }
            if (stillOurs) for (String axis : new String[]{"X", "Y", "Z"}) {
                String key = "Target" + axis;
                if (data.getBoolean("cmiAutoOriginal" + axis + "Present")) data.putDouble(key, data.getDouble("cmiAutoOriginalTarget" + axis));
                else data.remove(key);
            }
        }
        for (String axis : new String[]{"X", "Y", "Z"}) {
            data.remove("cmiAutoOriginal" + axis + "Present"); data.remove("cmiAutoOriginalTarget" + axis); data.remove("cmiAutoWrittenTarget" + axis);
        }
        data.remove("cmiAutoTargetBackup");
    }

    private static RadarSpearLockPolicy.Lock readLock(CompoundTag data) {
        if (!data.hasUUID(TARGET) || !data.contains(TRACK_TICK, Tag.TAG_LONG) || !data.contains(LOCK_SAMPLES, Tag.TAG_INT)) return RadarSpearLockPolicy.Lock.empty();
        int samples = data.getInt(LOCK_SAMPLES);
        if (samples < 1 || samples > 2) return RadarSpearLockPolicy.Lock.empty();
        return new RadarSpearLockPolicy.Lock(data.getUUID(TARGET), data.getLong(TRACK_TICK), samples);
    }

    private static void clearLock(CompoundTag data) {
        data.remove(TARGET); data.remove(TRACK_TICK); data.remove(LOCK_SAMPLES); data.remove(LAST_FIRE_SAMPLE);
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
