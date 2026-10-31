package local.cmi.weapons;

import com.happysg.radar.block.radar.behavior.IRadar;
import com.happysg.radar.block.radar.bearing.RadarBearingBlockEntity;
import com.happysg.radar.block.radar.plane.StationaryRadarBlockEntity;
import com.happysg.radar.block.radar.track.RadarTrack;
import com.happysg.radar.block.radar.track.TrackCategory;
import com.mrvew.firecontrol.ballistics.FiringSolver;
import com.mrvew.firecontrol.ballistics.TargetMotion;
import com.mrvew.firecontrol.ballistics.V3;
import com.mrvew.firecontrol.cannon.CannonProbe;
import com.mrvew.firecontrol.control.ComputerFireControl;
import com.mrvew.firecontrol.ship.Ships;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;
import local.cmi.core.Actor;
import local.cmi.core.CombatServices;
import local.cmi.core.MilitaryContact;
import local.cmi.core.Relation;
import local.cmi.core.TargetId;
import local.cmi.core.TrajectorySafety;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.ChunkPos;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import riftyboi.cbcmodernwarfare.cannon_control.compact_mount.CompactCannonMountBlockEntity;

/** CIWS decisions are made only on the server thread from the linked physical radar's PROJECTILE tracks. */
public final class CiwsCoordinator {
    private static final String MODE = "cmiCIWS", RADAR_DIM = "cmiCiwsRadarDimension", RADAR_POS = "cmiCiwsRadarPosition";
    private static final String TARGET = "cmiCiwsTarget", SCAN = "cmiCiwsScan", SAMPLES = "cmiCiwsSamples";
    private static final String LAST_UPDATE = "cmiCiwsLastUpdate", LAST_FIRE = "cmiCiwsLastFireScan", HOLD = "cmiCiwsFireHoldUntil";
    private static final String RESERVED = "cmiCiwsReservedTarget";
    private static final int MAX_TRACKS = 128, MAX_CANDIDATES = 8, MAX_FLIGHT_TICKS = 200;
    private static final ThreadLocal<FirePermit> FIRE_PERMIT = new ThreadLocal<>();
    private static final WeakHashMap<CompactCannonMountBlockEntity, Boolean> SEEN = new WeakHashMap<>();

    private static final class FirePermit {
        final ServerLevel level;
        final PitchOrientedContraptionEntity cannon;
        final CiwsPolicy.Once once = new CiwsPolicy.Once();
        FirePermit(ServerLevel level, PitchOrientedContraptionEntity cannon) { this.level = level; this.cannon = cannon; }
    }
    private record Binding(IRadar radar, String error) {}
    private record Plan(MilitaryContact contact, Entity target, long scannedAt, double closestTime,
                        double distanceSquared, double tolerance, FiringSolver solver, FiringSolver.Solution solution) {
        CiwsPolicy.Candidate candidate() { return new CiwsPolicy.Candidate(contact.id().uuid(), scannedAt, closestTime, distanceSquared); }
    }

    private CiwsCoordinator() {}

    /** A present malformed flag is controlled/fail-closed. A missing or false flag leaves native fire untouched. */
    public static boolean controls(BlockEntity be) {
        if (!(be instanceof CompactCannonMountBlockEntity)) return false;
        CompoundTag tag = be.getPersistentData();
        return tag.contains(MODE) && (!tag.contains(MODE, Tag.TAG_BYTE) || tag.getBoolean(MODE));
    }

    public static boolean hasConfiguredMode(CompactCannonMountBlockEntity mount) {
        return mount.getPersistentData().contains(MODE);
    }

    public static String bindRadar(ServerLevel level, BlockPos mountPos, BlockPos radarPos, UUID player) {
        BlockEntity be = level.hasChunkAt(mountPos) ? level.getBlockEntity(mountPos) : null;
        if (!(be instanceof CompactCannonMountBlockEntity mount)) return "mount must be a loaded Neo Warfare compact mount";
        if (!mainLevel(level, mountPos) || !mainLevel(level, radarPos)) return "Sable plot-mounted radar/mount is not supported";
        if (controls(mount)) return "disable CIWS before changing the radar";
        Actor actor = CombatServices.actor(level, mountPos);
        if (!ownedBy(mount, actor, player)) return "claim the mount with /cmi claim first";
        if (!level.hasChunkAt(radarPos)) return "radar chunk is not loaded";
        BlockEntity radarBe = level.getBlockEntity(radarPos);
        if (!realRadar(radarBe)) return "radar must be a Stationary Radar or Radar Bearing";
        Actor radarOwner = CombatServices.actor(level, radarPos);
        if (radarOwner.owner() == null || !actor.owner().equals(radarOwner.owner())) return "radar and mount must have the same known CMI owner";
        if (!running((IRadar)radarBe)) return "radar is stopped or unavailable";
        clearLock(level, mount);
        CompoundTag data = mount.getPersistentData();
        data.putString(RADAR_DIM, level.dimension().location().toString());
        data.putLong(RADAR_POS, radarPos.asLong());
        mount.setChanged();
        return null;
    }

    public static String enableError(ServerLevel level, BlockPos pos, UUID player) {
        BlockEntity be = level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null;
        if (!(be instanceof CompactCannonMountBlockEntity mount)) return "mount must be a loaded Neo Warfare compact mount";
        if (!mainLevel(level, pos)) return "Sable plot-mounted CIWS is not supported";
        Actor actor = CombatServices.actor(level, pos);
        if (!ownedBy(mount, actor, player)) return "claim the mount with /cmi claim first";
        var cannon = WeaponAdapters.compactContraption(mount);
        if (!ready(level, mount, cannon, actor)) return "assembled medium/rotary/CBC autocannon with a verified, non-incendiary, zero-spread kinetic loadout is required";
        if (ballistic(level, cannon) == null) return "finite, bounded projectile lifetime and verified ballistics are required";
        return binding(level, mount).error();
    }

    public static void setEnabled(ServerLevel level, CompactCannonMountBlockEntity mount, boolean enabled) {
        clearLock(level, mount);
        CompoundTag data = mount.getPersistentData();
        data.putBoolean(MODE, enabled);
        data.remove(LAST_UPDATE); data.remove(LAST_FIRE); data.remove(HOLD);
        mount.setChanged();
    }

    /** Invoked from the existing compact-mount server tick only for the explicit mode. */
    public static void tick(ServerLevel level, CompactCannonMountBlockEntity mount) {
        CompoundTag data = mount.getPersistentData();
        if (!SEEN.containsKey(mount)) {
            SEEN.put(mount, Boolean.TRUE);
            clearLock(level, mount);
            data.remove(LAST_UPDATE); data.remove(LAST_FIRE); data.remove(HOLD);
        }
        if (!data.contains(MODE, Tag.TAG_BYTE) || !data.getBoolean(MODE)) { clearLock(level, mount); return; }
        long now = level.getGameTime();
        if (now % 5 != 2 || (data.contains(LAST_UPDATE, Tag.TAG_LONG) && data.getLong(LAST_UPDATE) == now)) return;
        data.putLong(LAST_UPDATE, now);
        Actor actor = CombatServices.actor(level, mount.getBlockPos());
        var cannon = WeaponAdapters.compactContraption(mount);
        Binding binding = binding(level, mount);
        if (!mainLevel(level, mount.getBlockPos()) || binding.error() != null || !baseReady(level, mount, cannon, actor)) {
            clearLock(level, mount); return;
        }
        // Keep a fired round's reservation until its predicted arrival while the physical radar
        // still sees the same authorized incoming projectile, even during breech cooldown.
        if (data.contains(HOLD, Tag.TAG_LONG) && now <= data.getLong(HOLD)) {
            if (!heldTargetStillValid(level, mount, actor, binding.radar(), now)) { clearLock(level, mount); data.remove(HOLD); return; }
            UUID target = data.getUUID(RESERVED);
            int ttl = (int)Math.min(MAX_FLIGHT_TICKS + 2L, data.getLong(HOLD) - now + 1);
            if (!CombatServices.reserve(level, new TargetId(TargetId.Kind.ENTITY, target), reservationIdentity(level, mount), ttl)) clearLock(level, mount);
            return;
        }
        data.remove(HOLD);
        if (!ready(level, mount, cannon, actor)) { clearLock(level, mount); return; }
        var shell = ballistic(level, cannon);
        if (shell == null) { clearLock(level, mount); return; }
        var aim = CannonProbe.aim(level, cannon);
        Vec3 pivot = Ships.vec(aim.pivot());
        if (!finite(pivot) || !aim.barrel().isFinite()) { clearLock(level, mount); return; }
        List<Plan> plans = plans(level, mount, cannon, actor, binding.radar(), shell, aim, pivot, now);
        if (plans == null || plans.isEmpty()) { clearLock(level, mount); return; }
        UUID oldTarget = data.hasUUID(TARGET) ? data.getUUID(TARGET) : null;
        CiwsPolicy.Candidate selected = CiwsPolicy.select(oldTarget, plans.stream().map(Plan::candidate).toList());
        if (selected == null) { clearLock(level, mount); return; }
        Plan plan = plans.stream().filter(p -> p.contact().id().uuid().equals(selected.id())).findFirst().orElse(null);
        if (plan == null) { clearLock(level, mount); return; }
        if (data.hasUUID(RESERVED) && !data.getUUID(RESERVED).equals(selected.id())) release(level, mount, data.getUUID(RESERVED));
        UUID identity = reservationIdentity(level, mount);
        if (!CombatServices.reserve(level, plan.contact().id(), identity, 10)) { clearLock(level, mount); return; }
        data.putUUID(RESERVED, selected.id());
        CiwsPolicy.Lock previous = readLock(data);
        CiwsPolicy.Lock lock = CiwsPolicy.observe(previous, selected);
        data.putUUID(TARGET, lock.target()); data.putLong(SCAN, lock.lastScan()); data.putInt(SAMPLES, lock.samples());
        mount.setChanged();

        Vec3 desired = Ships.vec(plan.solution().direction());
        double[] angles = ComputerFireControl.mountAngles(desired);
        if (!Double.isFinite(angles[0]) || !Double.isFinite(angles[1]) || angles[1] > cannon.maximumElevation() || angles[1] < -cannon.maximumDepression()) return;
        WeaponAdapters.aimCompact(mount, (float) angles[0], (float) angles[1]);
        long lastFire = data.contains(LAST_FIRE, Tag.TAG_LONG) ? data.getLong(LAST_FIRE) : Long.MIN_VALUE;
        if (!CiwsPolicy.shouldFire(lock, lastFire) || (data.contains(HOLD, Tag.TAG_LONG) && now <= data.getLong(HOLD))) return;
        // The barrel reported before setYaw/setPitch must itself have an acceptable simulated miss.
        FiringSolver.Approach actual = plan.solver().approach(aim.barrel());
        if (!CiwsPolicy.acceptableSolution(aim.barrel().isFinite(), true, actual.miss(), plan.tolerance(), actual.time(), shell.lifetimeTicks())) return;
        MilitaryContact current = CombatServices.resolve(level, plan.contact().id()).orElse(null);
        if (current == null || current.owner() == null || !current.projectile() || !plan.target().isAlive() ||
                level.getEntity(selected.id()) != plan.target() || CombatServices.relation(level, actor, current) != Relation.ENEMY ||
                !CombatServices.canEngage(level, actor, current) || !ready(level, mount, cannon, actor)) { clearLock(level, mount); return; }
        // Check the whole finite lifetime, including a miss beyond the enemy projectile.
        if (!safePath(level, actor, cannon, plan.target(), pivot, plan.solver(), aim.barrel(), shell.lifetimeTicks())) return;
        if (!CombatServices.canEngage(level, actor, current) || !CombatServices.reserve(level, current.id(), identity, (int)Math.ceil(actual.time()) + 2)) return;
        data.putLong(LAST_FIRE, selected.scannedAt());
        data.putLong(HOLD, now + (long)Math.ceil(actual.time()) + 1);
        mount.setChanged();
        FirePermit prior = FIRE_PERMIT.get();
        FIRE_PERMIT.set(new FirePermit(level, cannon));
        try { WeaponAdapters.fire(level, cannon); }
        finally { if (prior == null) FIRE_PERMIT.remove(); else FIRE_PERMIT.set(prior); }
    }

    /** Gate native fireShot for CIWS-controlled medium/rotary only; the permit is consumed once. */
    public static boolean permitsNativeFire(ServerLevel level, PitchOrientedContraptionEntity cannon) {
        if (!(cannon.getController() instanceof BlockEntity mount) || !controls(mount)) return true;
        FirePermit permit = FIRE_PERMIT.get();
        return permit != null && permit.level == level && permit.cannon == cannon && permit.once.consume();
    }

    private static List<Plan> plans(ServerLevel level, CompactCannonMountBlockEntity mount,
            PitchOrientedContraptionEntity cannon, Actor actor, IRadar radar,
            com.mrvew.firecontrol.ballistics.ShellModel shell, CannonProbe.Aim aim, Vec3 pivot, long now) {
        List<RadarTrack> tracks = projectileTracks(radar);
        if (tracks == null) return null;
        List<Plan> result = new ArrayList<>();
        int eligible = 0;
        for (RadarTrack track : tracks) {
            UUID id = CiwsPolicy.projectileId(track.trackCategory() == TrackCategory.PROJECTILE, track.id(), now, track.scannedTime());
            if (id == null || !finite(track.position()) || !finite(track.velocity())) continue;
            Entity target = level.getEntity(id);
            if (target == null || !target.isAlive() || !CombatServices.projectile(target)) continue;
            var contact = CombatServices.resolve(level, new TargetId(TargetId.Kind.ENTITY, id)).orElse(null);
            if (contact == null || !CiwsPolicy.eligible(target.isAlive(), contact.projectile(), actor.owner(), contact.owner(),
                    actor.platform(), contact.platform(), CombatServices.relation(level, actor, contact), CombatServices.canEngage(level, actor, contact))) continue;
            Vec3 relative = contact.position().subtract(pivot), velocity = contact.velocity();
            if (!finite(relative) || !finite(velocity)) continue;
            double closest = CiwsPolicy.approaching(relative.x, relative.y, relative.z, velocity.x, velocity.y, velocity.z);
            double distance = relative.lengthSqr();
            var box = contact.bounds();
            double tolerance = CiwsPolicy.tolerance(box.getXsize(), box.getYsize(), box.getZsize());
            if (!Double.isFinite(closest) || !Double.isFinite(distance) || !Double.isFinite(tolerance)) continue;
            if (!CiwsPolicy.withinBudget(++eligible, MAX_CANDIDATES)) return null;
            TargetMotion motion = new TargetMotion(Ships.v3(contact.position()), Ships.v3(velocity), V3.ZERO);
            FiringSolver solver = new FiringSolver(shell, aim.pivot(), V3.ZERO, motion, 0, shell.lifetimeTicks());
            FiringSolver.Solution solution = solver.solve(FiringSolver.Arc.LOW, null, tolerance);
            if (solution == null || !solution.direction().isFinite()) continue;
            double[] angles = ComputerFireControl.mountAngles(Ships.vec(solution.direction()));
            boolean inArc = Double.isFinite(angles[0]) && Double.isFinite(angles[1]) && angles[1] <= cannon.maximumElevation() && angles[1] >= -cannon.maximumDepression();
            if (!CiwsPolicy.acceptableSolution(true, inArc, solution.miss(), tolerance, solution.flightTicks(), shell.lifetimeTicks())) continue;
            // Do not monopolize a contact with a predicted corridor that cannot be authorized.
            if (!safePath(level, actor, cannon, target, pivot, solver, solution.direction(), shell.lifetimeTicks())) continue;
            result.add(new Plan(contact, target, track.scannedTime(), closest, distance, tolerance, solver, solution));
        }
        return result;
    }

    private static List<RadarTrack> projectileTracks(IRadar radar) {
        List<RadarTrack> tracks = new ArrayList<>();
        try {
            if (!running(radar)) return null;
            var snapshot = radar.getTracks();
            if (snapshot == null) return null;
            int scanned = 0;
            for (RadarTrack t : snapshot) {
                if (!CiwsPolicy.withinBudget(++scanned, MAX_TRACKS)) return null;
                if (t == null || t.trackCategory() != TrackCategory.PROJECTILE) continue;
                tracks.add(t);
            }
        } catch (RuntimeException invalidRadar) { return null; }
        return tracks;
    }

    private static boolean heldTargetStillValid(ServerLevel level, CompactCannonMountBlockEntity mount, Actor actor, IRadar radar, long now) {
        CompoundTag data = mount.getPersistentData();
        if (!data.hasUUID(RESERVED) || !data.hasUUID(TARGET) || !data.getUUID(RESERVED).equals(data.getUUID(TARGET))) return false;
        UUID expected = data.getUUID(TARGET);
        List<RadarTrack> tracks = projectileTracks(radar);
        if (tracks == null) return false;
        for (RadarTrack track : tracks) {
            UUID id = CiwsPolicy.projectileId(track.trackCategory() == TrackCategory.PROJECTILE, track.id(), now, track.scannedTime());
            if (!expected.equals(id) || !finite(track.position()) || !finite(track.velocity())) continue;
            Entity entity = level.getEntity(id);
            if (entity == null || !entity.isAlive() || !CombatServices.projectile(entity)) return false;
            MilitaryContact contact = CombatServices.resolve(level, new TargetId(TargetId.Kind.ENTITY, id)).orElse(null);
            if (contact == null || !CiwsPolicy.eligible(true, contact.projectile(), actor.owner(), contact.owner(), actor.platform(), contact.platform(),
                    CombatServices.relation(level, actor, contact), CombatServices.canEngage(level, actor, contact))) return false;
            Vec3 offset = contact.position().subtract(Vec3.atCenterOf(mount.getBlockPos())), velocity = contact.velocity();
            return finite(offset) && finite(velocity) && Double.isFinite(CiwsPolicy.approaching(offset.x, offset.y, offset.z, velocity.x, velocity.y, velocity.z));
        }
        return false;
    }

    private static boolean safePath(ServerLevel level, Actor actor, PitchOrientedContraptionEntity cannon, Entity target,
                                    Vec3 pivot, FiringSolver solver, V3 direction, int lifetime) {
        var path = new ArrayList<Vec3>();
        path.add(pivot);
        for (V3 point : solver.path(direction, lifetime)) path.add(Ships.vec(point));
        if (!TrajectorySafety.clear(level, actor, path, 0.5, cannon, target)) return false;
        // FtbCompat returns Optional.of(true OR false) for a claimed chunk and empty for unclaimed.
        // CIWS protects all claims, including owners that Iff.claim would label ENEMY.
        boolean chunksLoaded = net.neoforged.fml.ModList.get().isLoaded("ftbchunks");
        try {
            if (chunksLoaded && (!com.mrvew.firecontrol.iff.FtbCompat.hasChunks() || !dev.ftb.mods.ftbchunks.api.FTBChunksAPI.api().isManagerLoaded())) return false;
            int budget = 12000;
            if (chunksLoaded) for (int i = 1; i < path.size(); i++) {
                AABB corridor = new AABB(path.get(i - 1), path.get(i)).inflate(1.0);
                int x0 = (int)Math.floor(corridor.minX) >> 4, x1 = (int)Math.floor(corridor.maxX) >> 4;
                int z0 = (int)Math.floor(corridor.minZ) >> 4, z1 = (int)Math.floor(corridor.maxZ) >> 4;
                for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                    if (--budget < 0 || com.mrvew.firecontrol.iff.FtbCompat.claimFriendly(level, new ChunkPos(x, z), actor.owner(), List.of()).isPresent()) return false;
                }
            }
        } catch (RuntimeException | LinkageError missingClaimState) { return false; }
        // CBC's drag changes in fluid; the air-only ShellModel is not a safe approximation there.
        for (int i = 1; i < path.size(); i++) {
            if (level.clip(new ClipContext(path.get(i - 1), path.get(i), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.ANY, CollisionContext.empty())).getType() != HitResult.Type.MISS) return false;
        }
        return true;
    }

    private static com.mrvew.firecontrol.ballistics.ShellModel ballistic(ServerLevel level, PitchOrientedContraptionEntity cannon) {
        if (cannon == null || WeaponAdapters.ciwsVelocity(cannon).isEmpty()) return null;
        var shell = WeaponAdapters.ciwsShell(level, cannon).orElse(null);
        if (shell == null) return null;
        if (!Double.isFinite(shell.muzzleSpeed()) || shell.muzzleSpeed() <= 0 || !Double.isFinite(shell.gravity()) ||
                !Double.isFinite(shell.drag()) || shell.drag() < 0 || !Double.isFinite(shell.spawnDistance()) || shell.spawnDistance() < 0 ||
                shell.lifetimeTicks() <= 0 || shell.lifetimeTicks() > MAX_FLIGHT_TICKS) return null;
        return shell;
    }

    private static boolean ready(ServerLevel level, CompactCannonMountBlockEntity mount, PitchOrientedContraptionEntity cannon, Actor actor) {
        return baseReady(level, mount, cannon, actor) && WeaponAdapters.ciwsVelocity(cannon).isPresent();
    }
    private static boolean baseReady(ServerLevel level, CompactCannonMountBlockEntity mount, PitchOrientedContraptionEntity cannon, Actor actor) {
        return level.hasChunkAt(mount.getBlockPos()) && mount.getLevel() == level && actor.owner() != null &&
                mount.getPersistentData().hasUUID("cmiOwner") && actor.owner().equals(mount.getPersistentData().getUUID("cmiOwner")) &&
                cannon != null && cannon.isAlive() && cannon.level() == level && cannon.getController() == mount &&
                !cannon.isStalled() && cannon.canBeTurnedByController(mount);
    }

    private static Binding binding(ServerLevel level, CompactCannonMountBlockEntity mount) {
        CompoundTag data = mount.getPersistentData();
        if (!data.contains(RADAR_DIM, Tag.TAG_STRING) || !data.contains(RADAR_POS, Tag.TAG_LONG)) return new Binding(null, "link a radar with /cmi ciwsradar first");
        if (!level.dimension().location().toString().equals(data.getString(RADAR_DIM))) return new Binding(null, "radar must be in this dimension");
        BlockPos pos = BlockPos.of(data.getLong(RADAR_POS));
        if (!level.hasChunkAt(pos) || !mainLevel(level, pos)) return new Binding(null, "radar chunk missing or radar is in a Sable plot");
        BlockEntity be = level.getBlockEntity(pos);
        if (!realRadar(be)) return new Binding(null, "linked physical radar is missing");
        Actor owner = CombatServices.actor(level, mount.getBlockPos()), radarOwner = CombatServices.actor(level, pos);
        if (owner.owner() == null || radarOwner.owner() == null || !owner.owner().equals(radarOwner.owner())) return new Binding(null, "radar/mount owner mismatch");
        IRadar radar = (IRadar)be;
        return running(radar) ? new Binding(radar, null) : new Binding(null, "linked radar is not running");
    }

    private static boolean realRadar(BlockEntity be) { return be instanceof StationaryRadarBlockEntity || be instanceof RadarBearingBlockEntity; }
    private static boolean running(IRadar radar) { try { return radar != null && radar.isRunning(); } catch (RuntimeException unavailable) { return false; } }
    private static boolean mainLevel(ServerLevel level, BlockPos pos) { return level.hasChunkAt(pos) && Ships.containing(level, pos) == null; }
    private static boolean ownedBy(BlockEntity be, Actor actor, UUID player) {
        return actor.owner() != null && actor.owner().equals(player) && be.getPersistentData().hasUUID("cmiOwner") && player.equals(be.getPersistentData().getUUID("cmiOwner"));
    }
    private static boolean finite(Vec3 p) { return p != null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z); }
    private static UUID reservationIdentity(ServerLevel level, CompactCannonMountBlockEntity mount) {
        return UUID.nameUUIDFromBytes(("cmi-ciws:" + level.dimension().location() + ":" + mount.getBlockPos().asLong()).getBytes(StandardCharsets.UTF_8));
    }
    private static CiwsPolicy.Lock readLock(CompoundTag tag) {
        return tag.hasUUID(TARGET) && tag.contains(SCAN, Tag.TAG_LONG) && tag.contains(SAMPLES, Tag.TAG_INT)
                ? new CiwsPolicy.Lock(tag.getUUID(TARGET), tag.getLong(SCAN), tag.getInt(SAMPLES)) : CiwsPolicy.Lock.empty();
    }
    private static void release(ServerLevel level, CompactCannonMountBlockEntity mount, UUID target) {
        CombatServices.release(level, new TargetId(TargetId.Kind.ENTITY, target), reservationIdentity(level, mount));
    }
    private static void clearLock(ServerLevel level, CompactCannonMountBlockEntity mount) {
        CompoundTag data = mount.getPersistentData();
        if (data.hasUUID(RESERVED)) release(level, mount, data.getUUID(RESERVED));
        data.remove(RESERVED); data.remove(TARGET); data.remove(SCAN); data.remove(SAMPLES); data.remove(HOLD);
        mount.setChanged();
    }

    public static void clear() { SEEN.clear(); FIRE_PERMIT.remove(); }
}
