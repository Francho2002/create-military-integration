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
import local.cmi.core.TargetId;
import local.cmi.core.TrajectorySafety;

/** Server-only adapter for a Radar Spear explicitly armed through its launch block. */
public final class RadarSpearBridge {
    private RadarSpearBridge() {}
    public static void initialize(ServerLevel level, Entity missile) {
        CompoundTag t = missile.getPersistentData();
        if (RadarSpearMode.managed(t)) {
            if (!validManaged(level, t)) missile.discard();
            return;
        }
        // Decide once, before AbstractArrow.tick can produce a collision. A native
        // missile must never be adopted later merely because a launcher changes.
        if (!RadarSpearMode.beginDecision(t)) return;
        if (!t.contains("LX", 99) || !t.contains("LY", 99) || !t.contains("LZ", 99)) return;
        if (!Double.isFinite(t.getDouble("LX")) || !Double.isFinite(t.getDouble("LY")) || !Double.isFinite(t.getDouble("LZ"))) return;
        BlockPos p = BlockPos.containing(t.getDouble("LX"), t.getDouble("LY"), t.getDouble("LZ"));
        if (!level.hasChunkAt(p)) return;
        BlockEntity launcher = level.getBlockEntity(p);
        if (launcher == null) return;
        CompoundTag source = launcher.getPersistentData();
        if (source.contains("cmiManaged") && !source.contains("cmiManaged", net.minecraft.nbt.Tag.TAG_BYTE)) { missile.discard(); return; }
        if (!source.getBoolean("cmiManaged")) return;
        Actor actor = CombatServices.actor(level, p);
        if (actor.owner() == null || !source.contains("TargetX", 99) || !source.contains("TargetY", 99) || !source.contains("TargetZ", 99)) { missile.discard(); return; }
        Vec3 point = new Vec3(source.getDouble("TargetX"), source.getDouble("TargetY"), source.getDouble("TargetZ"));
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z) || !level.hasChunkAt(BlockPos.containing(point))) { missile.discard(); return; }
        var target = CombatServices.contacts(level).stream().filter(c -> c.bounds().contains(point) && CombatServices.canEngage(level, actor, c))
                .min(Comparator.comparingDouble(c -> c.position().distanceToSqr(point)));
        if (target.isEmpty()) { missile.discard(); return; }
        t.putUUID("cmiOwner", actor.owner());
        t.putString("cmiIff", actor.iff() == null ? "" : actor.iff());
        if (actor.platform() != null) t.putUUID("cmiPlatform", actor.platform());
        t.putUUID("cmiTargetId", target.get().id().uuid());
        t.putString("cmiTargetKind", target.get().id().kind().name());
        t.putBoolean("cmiManaged", true);
    }
    private static boolean validManaged(ServerLevel level, CompoundTag t) {
        if (!t.contains("cmiManaged", net.minecraft.nbt.Tag.TAG_BYTE) || !t.hasUUID("cmiOwner") || !t.hasUUID("cmiTargetId")
                || (t.contains("Time") && (!t.contains("Time", 99) || !Double.isFinite(t.getDouble("Time"))))
                || !t.contains("LX", 99) || !t.contains("LY", 99) || !t.contains("LZ", 99)
                || !Double.isFinite(t.getDouble("LX")) || !Double.isFinite(t.getDouble("LY")) || !Double.isFinite(t.getDouble("LZ"))
                || !level.hasChunkAt(BlockPos.containing(t.getDouble("LX"), t.getDouble("LY"), t.getDouble("LZ")))) return false;
        try {
            var id = new TargetId(TargetId.Kind.valueOf(t.getString("cmiTargetKind")), t.getUUID("cmiTargetId"));
            var actor = new Actor(t.getUUID("cmiOwner"), t.getString("cmiIff"), t.hasUUID("cmiPlatform") ? t.getUUID("cmiPlatform") : null);
            return CombatServices.resolve(level, id).filter(target -> CombatServices.canEngage(level, actor, target)).isPresent();
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
                if (CombatServices.reserve(level, id, missile.getUUID(), 2)) {
                    CombatServices.neutralize(level, actor, target);
                    CombatServices.release(level, id, missile.getUUID());
                }
            });
        }
        missile.discard();
    }
    public static void tick(ServerLevel level, Entity missile) {
        CompoundTag t = missile.getPersistentData();
        if (!t.hasUUID("cmiOwner")) { missile.discard(); return; }
        Actor actor = new Actor(t.getUUID("cmiOwner"), t.getString("cmiIff"), t.hasUUID("cmiPlatform") ? t.getUUID("cmiPlatform") : null);
        double time = t.getDouble("Time") + 1;
        t.putDouble("Time", time); t.putDouble("MaxTime", 130);
        if (time > 130 || missile.isUnderWater()) { missile.discard(); return; }
        missile.setNoGravity(true);
        Vec3 motion = missile.getDeltaMovement().scale(1.02);
        if (!TrajectorySafety.clear(level, actor, List.of(missile.position(), missile.position().add(motion)), missile.getBbWidth() * 0.5, missile)) {
            missile.discard(); return;
        }
        missile.setDeltaMovement(motion);
        Optional<MilitaryContact> found = Optional.empty();
        if (t.hasUUID("cmiTargetId")) {
            try { found = CombatServices.resolve(level, new TargetId(TargetId.Kind.valueOf(t.getString("cmiTargetKind")), t.getUUID("cmiTargetId"))); }
            catch (IllegalArgumentException ignored) { }
        }
        if (found.isEmpty() || !CombatServices.canEngage(level, actor, found.get())) { t.remove("cmiTargetId"); return; }
        MilitaryContact target = found.get();
        BlockPos launch = BlockPos.containing(t.getDouble("LX"), t.getDouble("LY"), t.getDouble("LZ"));
        BlockEntity block = level.getBlockEntity(launch);
        if (block != null && block.getPersistentData().getBoolean("cmiManaged")) {
            CompoundTag b = block.getPersistentData();
            b.putDouble("TargetX", target.position().x); b.putDouble("TargetY", target.position().y); b.putDouble("TargetZ", target.position().z);
            b.putDouble("MX", target.velocity().x); b.putDouble("MY", target.velocity().y); b.putDouble("MZ", target.velocity().z);
            block.setChanged();
        }
        if (target.projectile() && !CombatServices.reserve(level, target.id(), missile.getUUID(), 2)) { t.remove("cmiTargetId"); return; }
        if (time >= 10 && target.projectile() && (missile.getBoundingBox().intersects(target.bounds()) || target.bounds().inflate(missile.getBbWidth() * 0.5).clip(missile.position().subtract(motion), missile.position()).isPresent())) {
            if (CombatServices.neutralize(level, actor, target)) { CombatServices.release(level, target.id(), missile.getUUID()); missile.discard(); }
            return;
        }
        Vec3 delta = target.position().subtract(missile.position());
        double speed = motion.length(), distance = delta.length();
        if (time < 10 || speed < 1.0e-6 || distance < 1.0e-6) return;
        Vec3 desired = delta.add(target.velocity().scale(distance / speed)).scale(speed / distance);
        double limit = 0.03 * speed;
        Vec3 next = new Vec3(clamp(motion.x, (7 * motion.x + desired.x) / 8, limit), clamp(motion.y, (7 * motion.y + desired.y) / 8, limit), clamp(motion.z, (7 * motion.z + desired.z) / 8, limit));
        if (!TrajectorySafety.clear(level, actor, List.of(missile.position(), missile.position().add(next)), missile.getBbWidth() * 0.5, missile)) { missile.discard(); return; }
        missile.setDeltaMovement(next);
    }
    private static double clamp(double old, double next, double limit) { return Math.min(old + limit, Math.max(old - limit, next)); }
}
