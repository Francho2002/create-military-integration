package local.cmi.missiles;

import java.util.Comparator;
import java.util.Optional;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import local.cmi.core.Actor;
import local.cmi.core.CombatServices;
import local.cmi.core.MilitaryContact;
import local.cmi.core.SableFirstHit;
import local.cmi.core.TargetId;
import local.cmi.core.TrajectorySafety;
import net.minecraft.world.phys.BlockHitResult;

/** Server-only adapter for a Radar Spear explicitly armed through its launch block. */
public final class RadarSpearBridge {
    private RadarSpearBridge() {}
    /** Capture the exact projectile while the original hardpoint still exists. */
    public static boolean captureLaunch(ServerLevel level, Entity missile, BlockPos pos) {
        BlockEntity launcher = level.getBlockEntity(pos);
        if (launcher == null) return true;
        CompoundTag source = launcher.getPersistentData();
        var mode = RadarSpearMode.launchMode(source);
        if (mode == RadarSpearMode.LaunchMode.NATIVE) return true;
        if (mode == RadarSpearMode.LaunchMode.INVALID) return false;
        CompoundTag t = missile.getPersistentData();
        Actor actor = CombatServices.actor(level, pos);
        if (actor.owner() == null || !RadarSpearMode.hasDesignation(source)) return false;
        Vec3 point = new Vec3(source.getDouble("TargetX"), source.getDouble("TargetY"), source.getDouble("TargetZ"));
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z) || !level.hasChunkAt(BlockPos.containing(point))) return false;
        var target = CombatServices.contacts(level).stream().filter(c -> c.bounds().contains(point))
                .min(Comparator.comparingDouble(c -> c.position().distanceToSqr(point)));
        if (target.isEmpty() || !CombatServices.canEngage(level, actor, target.get())) return false;
        String profile = RadarSpearMode.launchProfile(source, target.get().id(), target.get().projectile());
        if (profile == null || !RadarSpearImpact.arm(t, actor, target.get().id(), missile.getUUID(), level.getGameTime(), profile)) return false;
        t.putDouble("LX", pos.getX()); t.putDouble("LY", pos.getY()); t.putDouble("LZ", pos.getZ());
        t.putUUID("cmiOwner", actor.owner());
        t.putString("cmiIff", actor.iff() == null ? "" : actor.iff());
        if (actor.platform() != null) t.putUUID("cmiPlatform", actor.platform());
        t.putUUID("cmiTargetId", target.get().id().uuid());
        t.putString("cmiTargetKind", target.get().id().kind().name());
        t.putBoolean("cmiManaged", true);
        return true;
    }
    public static void initialize(ServerLevel level, Entity missile) {
        CompoundTag t = missile.getPersistentData();
        if (RadarSpearMode.managed(t) && !validManaged(level, missile)) discard(missile);
    }
    private static boolean validManaged(ServerLevel level, Entity missile) {
        CompoundTag t = missile.getPersistentData();
        if (!t.contains("cmiManaged", net.minecraft.nbt.Tag.TAG_BYTE) || !t.getBoolean("cmiManaged") || !t.hasUUID("cmiOwner") || !t.hasUUID("cmiTargetId")
                || (t.contains("Time") && (!t.contains("Time", 99) || !Double.isFinite(t.getDouble("Time"))))) return false;
        try {
            var id = new TargetId(TargetId.Kind.valueOf(t.getString("cmiTargetKind")), t.getUUID("cmiTargetId"));
            var actor = new Actor(t.getUUID("cmiOwner"), t.getString("cmiIff"), t.hasUUID("cmiPlatform") ? t.getUUID("cmiPlatform") : null);
            return CombatServices.resolve(level, id).filter(target -> CombatServices.canEngage(level, actor, target)
                    && RadarSpearImpact.matchesGuidance(t, missile.getUUID(), level.getGameTime(), actor, id, target.projectile())).isPresent();
        } catch (IllegalArgumentException invalidKind) { return false; }
    }
    /** Cancel the native arrow damage and explosion; only a designated hostile projectile may be neutralized. */
    public static void impactEntity(ServerLevel level, Entity missile, Entity hit) {
        CompoundTag t = missile.getPersistentData();
        if (t.hasUUID("cmiOwner") && t.hasUUID("cmiTargetId") && t.getUUID("cmiTargetId").equals(hit.getUUID())
                && "ENTITY".equals(t.getString("cmiTargetKind")) && t.getDouble("Time") >= 10) {
            Actor actor = new Actor(t.getUUID("cmiOwner"), t.getString("cmiIff"), t.hasUUID("cmiPlatform") ? t.getUUID("cmiPlatform") : null);
            TargetId id = new TargetId(TargetId.Kind.ENTITY, hit.getUUID());
            CombatServices.resolve(level, id).filter(MilitaryContact::projectile).ifPresent(target -> {
                if (RadarSpearImpact.matches(t, missile.getUUID(), level.getGameTime(), actor, id, true)
                        && CombatServices.reserve(level, id, missile.getUUID(), 2)
                        && RadarSpearImpact.consume(t, missile.getUUID(), level.getGameTime())) {
                    CombatServices.neutralize(level, actor, target);
                    CombatServices.release(level, id, missile.getUUID());
                }
            });
        }
        if (t.getDouble("Time") >= 10 && !CombatServices.projectile(hit)) RadarSpearImpact.impact(level, missile, hit);
        discard(missile);
    }
    public static void discard(Entity missile) {
        RadarSpearImpact.cancel(missile.getPersistentData());
        missile.discard();
    }
    /** The native callback provides the actual block collision; native Warium detonation remains cancelled. */
    public static void impactBlock(ServerLevel level, Entity missile, BlockHitResult hit) {
        Vec3 from = missile.position();
        Vec3 to = from.add(missile.getDeltaMovement());
        String profile = RadarSpearImpact.profile(missile.getPersistentData(), missile.getUUID(), level.getGameTime());
        if ("direct_sable".equals(profile)) RadarSpearStructural.impact(level, missile, hit, from, to);
        else if ("explosive_sable".equals(profile)) RadarSpearAreaDamage.impact(level, missile, hit, from, to);
        discard(missile);
    }
    private static boolean allowedPath(ServerLevel level, Actor actor, Entity missile, Vec3 from, Vec3 to) {
        CompoundTag data = missile.getPersistentData();
        if (!"SABLE".equals(data.getString("cmiTargetKind")) || !data.hasUUID("cmiTargetId")
                || RadarSpearImpact.interceptor(data, missile.getUUID(), level.getGameTime()))
            return TrajectorySafety.clear(level, actor, List.of(from, to), missile.getBbWidth() * 0.5, missile);
        var target = new TargetId(TargetId.Kind.SABLE, data.getUUID("cmiTargetId"));
        var first = SableFirstHit.evaluate(level, actor, target, from, to, missile);
        if (first.kind() == SableFirstHit.Kind.CLEAR)
            return TrajectorySafety.clear(level, actor, List.of(from, to), missile.getBbWidth() * 0.5, missile)
                    && SableFirstHit.mainWorldCorridorClear(level, actor, from, to, missile.getBbWidth() * 0.5);
        return data.getDouble("Time") >= 10 && first.kind() == SableFirstHit.Kind.TARGET
                && SableFirstHit.corridorClear(level, actor, from, first, missile);
    }
    /** Check current motion before the native arrow tick can move or collide. */
    public static boolean preflight(ServerLevel level, Entity missile) {
        if (!validManaged(level, missile)) return false;
        CompoundTag t = missile.getPersistentData();
        Actor actor = new Actor(t.getUUID("cmiOwner"), t.getString("cmiIff"), t.hasUUID("cmiPlatform") ? t.getUUID("cmiPlatform") : null);
        return allowedPath(level, actor, missile, missile.position(), missile.position().add(missile.getDeltaMovement()));
    }
    public static void tick(ServerLevel level, Entity missile) {
        CompoundTag t = missile.getPersistentData();
        if (!t.hasUUID("cmiOwner")) { discard(missile); return; }
        Actor actor = new Actor(t.getUUID("cmiOwner"), t.getString("cmiIff"), t.hasUUID("cmiPlatform") ? t.getUUID("cmiPlatform") : null);
        double time = t.getDouble("Time") + 1;
        t.putDouble("Time", time); t.putDouble("MaxTime", 130);
        if (time > 130 || missile.isUnderWater()) { discard(missile); return; }
        missile.setNoGravity(true);
        Vec3 motion = missile.getDeltaMovement().scale(1.02);
        if (!allowedPath(level, actor, missile, missile.position(), missile.position().add(motion))) {
            discard(missile); return;
        }
        missile.setDeltaMovement(motion);
        Optional<MilitaryContact> found = Optional.empty();
        if (t.hasUUID("cmiTargetId")) {
            try { found = CombatServices.resolve(level, new TargetId(TargetId.Kind.valueOf(t.getString("cmiTargetKind")), t.getUUID("cmiTargetId"))); }
            catch (IllegalArgumentException ignored) { }
        }
        if (found.isEmpty() || !CombatServices.canEngage(level, actor, found.get())) { t.remove("cmiTargetId"); return; }
        MilitaryContact target = found.get();
        if (target.projectile() && !CombatServices.reserve(level, target.id(), missile.getUUID(), 2)) { t.remove("cmiTargetId"); return; }
        if (time >= 10 && target.projectile() && (missile.getBoundingBox().intersects(target.bounds()) || target.bounds().inflate(missile.getBbWidth() * 0.5).clip(missile.position().subtract(motion), missile.position()).isPresent())) {
            if (RadarSpearImpact.matches(t, missile.getUUID(), level.getGameTime(), actor, target.id(), true)
                    && RadarSpearImpact.consume(t, missile.getUUID(), level.getGameTime())
                    && CombatServices.neutralize(level, actor, target)) { CombatServices.release(level, target.id(), missile.getUUID()); discard(missile); }
            return;
        }
        Vec3 delta = target.position().subtract(missile.position());
        double speed = motion.length(), distance = delta.length();
        if (time < 10 || speed < 1.0e-6 || distance < 1.0e-6) return;
        Vec3 desired = delta.add(target.velocity().scale(distance / speed)).scale(speed / distance);
        double limit = 0.03 * speed;
        Vec3 next = new Vec3(clamp(motion.x, (7 * motion.x + desired.x) / 8, limit), clamp(motion.y, (7 * motion.y + desired.y) / 8, limit), clamp(motion.z, (7 * motion.z + desired.z) / 8, limit));
        if (!allowedPath(level, actor, missile, missile.position(), missile.position().add(next))) { discard(missile); return; }
        missile.setDeltaMovement(next);
    }
    private static double clamp(double old, double next, double limit) { return Math.min(old + limit, Math.max(old - limit, next)); }
}
